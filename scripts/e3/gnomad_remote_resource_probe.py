#!/usr/bin/env python3
"""Run the bounded, opt-in gnomAD v4.1 remote-resource probe.

The probe verifies the official chr1 joint VCF transport, header contract and
three public fixed alleles, then binds a separately verified fixed-locus Hail
result for both all-sites AN tables. It never reads or writes a complete gnomAD
resource and it does not evaluate clinical rules.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


PROBE_SCHEMA_VERSION = "gnomad-remote-resource-probe-v1"
ALL_SITES_HAIL_PROBE_SCHEMA_VERSION = "gnomad-all-sites-hail-probe-v1"
PROFILE_RELATIVE_PATH = Path(
    "docs/execution/E3_P0R_GNOMAD_REMOTE_RESOURCE_PROFILE.json"
)
JOINT_RESOURCE_ID = "GNOMAD_JOINT_V4_1_GRCH38_SITES"
ALL_SITES_RESOURCE_IDS = (
    "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN",
    "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN",
)
OFFICIAL_CHR1_DATA_URI = (
    "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/"
    "vcf/joint/gnomad.joint.v4.1.sites.chr1.vcf.bgz"
)
OFFICIAL_CHR1_INDEX_URI = OFFICIAL_CHR1_DATA_URI + ".tbi"
DISCORDANCE_THRESHOLD_RAW = "1e-4"
COMMAND_TIMEOUT_SECONDS = 60
HTTP_TIMEOUT_SECONDS = 30
PASS_HIT_CASE_ID = "PASS_HIT"
EXACT_ALLELE_NO_RECORD_CASE_ID = "EXACT_ALLELE_NO_RECORD"
DISCORDANCE_QC_HIT_CASE_ID = "DISCORDANCE_QC_HIT"
FIXED_ASSEMBLY = "GRCh38"
FIXED_CANONICAL_CONTIG = "1"
FIXED_RESOURCE_CONTIG = "chr1"
ALL_SITES_HIT_CASE_ID = "ALL_SITES_AN_HIT"
ALL_SITES_NO_RECORD_CASE_ID = "ALL_SITES_AN_NO_RECORD"
ALL_SITES_HIT_LOCUS = {
    "assembly": "GRCh38",
    "canonicalContig": "3",
    "resourceContig": "chr3",
    "position": 10052405,
}
ALL_SITES_NO_RECORD_LOCUS = {
    "assembly": "GRCh38",
    "canonicalContig": "1",
    "resourceContig": "chr1",
    "position": 1,
}
EXPECTED_HAIL_TOOL = {
    "hailVersion": "0.2.139-5e5eedb87ff8",
    "pysparkVersion": "3.5.9",
    "pythonVersion": "3.12.3",
    "backend": "LOCAL_SPARK_SINGLE_CORE",
    "gcsConnector": {
        "version": "2.2.33",
        "byteLength": 38604231,
        "sha256": (
            "cd47e5ef4d18a97576e0421b74812b87b2cb0d580c59bbc7113f5f526d46e48b"
        ),
        "authentication": "UNAUTHENTICATED_PUBLIC_READ_ONLY",
    },
}
FORBIDDEN_ARTIFACT_KEYS = {
    "criteria",
    "criterion",
    "criterionevaluation",
    "criterionevaluations",
    "criterionresult",
    "evaluationresult",
    "classification",
    "acmgclassification",
    "combinationresult",
    "reviewstatus",
}
FORBIDDEN_ARTIFACT_STATUSES = {
    "MET",
    "NOT_MET",
    "NOT_EVALUATED",
    "NOT_APPLICABLE",
    "CONFLICTING",
    "NEEDS_REVIEW",
    "ABSTAIN",
}
LOCAL_PATH_PATTERN = re.compile(
    r"(?:(?<![A-Za-z0-9])[A-Za-z]:[\\/]|\\\\|file:(?://)?|"
    r"(?<![:A-Za-z0-9])/(?:data|home|root|Users|tmp|var|opt|srv|mnt)(?:/|$))",
    re.IGNORECASE,
)
INFO_EXPECTATIONS: dict[str, tuple[str, str]] = {
    "AC_joint": ("A", "Integer"),
    "AN_joint": ("1", "Integer"),
    "AF_joint": ("A", "Float"),
    "nhomalt_joint": ("A", "Integer"),
    "grpmax_joint": ("A", "String"),
    "AC_grpmax_joint": ("A", "Integer"),
    "AN_grpmax_joint": ("A", "Integer"),
    "AF_grpmax_joint": ("A", "Float"),
    "nhomalt_grpmax_joint": ("A", "Integer"),
    "faf95_joint": ("A", "Float"),
    "fafmax_faf95_max_joint": ("A", "Float"),
    "fafmax_faf95_max_gen_anc_joint": ("A", "String"),
    "AC_exomes": ("A", "Integer"),
    "AN_exomes": ("1", "Integer"),
    "AF_exomes": ("A", "Float"),
    "AC_genomes": ("A", "Integer"),
    "AN_genomes": ("1", "Integer"),
    "AF_genomes": ("A", "Float"),
    "stat_union_p_value": ("A", "Float"),
    "stat_union_test_name": ("A", "String"),
    "stat_union_gen_ancs": (".", "String"),
    "exomes_filters": (".", "String"),
    "genomes_filters": (".", "String"),
}

FILTER_EXPECTATIONS = {
    "PASS",
    "EXOMES_FILTERED",
    "GENOMES_FILTERED",
    "BOTH_FILTERED",
}

NUMERIC_FIELDS = {
    name
    for name, (_, field_type) in INFO_EXPECTATIONS.items()
    if field_type in {"Integer", "Float"}
}
OUTPUT_FIELDS = (
    "AC_joint",
    "AN_joint",
    "AF_joint",
    "nhomalt_joint",
    "grpmax_joint",
    "AC_grpmax_joint",
    "AN_grpmax_joint",
    "AF_grpmax_joint",
    "nhomalt_grpmax_joint",
    "faf95_joint",
    "fafmax_faf95_max_joint",
    "fafmax_faf95_max_gen_anc_joint",
    "AC_exomes",
    "AN_exomes",
    "AF_exomes",
    "AC_genomes",
    "AN_genomes",
    "AF_genomes",
    "stat_union_p_value",
    "stat_union_test_name",
    "stat_union_gen_ancs",
)


class ProbeError(RuntimeError):
    """A bounded probe failed a frozen safety or identity assertion."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def sha256_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def reject_clinical_payload(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", str(key).lower())
            if normalized in FORBIDDEN_ARTIFACT_KEYS:
                raise ProbeError("CLINICAL_PAYLOAD_LEAKED")
            reject_clinical_payload(nested)
    elif isinstance(value, list):
        for nested in value:
            reject_clinical_payload(nested)
    elif isinstance(value, str) and value in FORBIDDEN_ARTIFACT_STATUSES:
        raise ProbeError("CLINICAL_PAYLOAD_LEAKED")


def unique_objects_by_id(
    values: Any,
    key: str,
    required_ids: set[str],
    reason_code: str,
) -> dict[str, dict[str, Any]]:
    if not isinstance(values, list) or len(values) != len(required_ids):
        raise ProbeError(reason_code)
    result: dict[str, dict[str, Any]] = {}
    for value in values:
        if not isinstance(value, dict) or not isinstance(value.get(key), str):
            raise ProbeError(reason_code)
        identifier = value[key]
        if identifier in result:
            raise ProbeError(reason_code)
        result[identifier] = value
    if set(result) != required_ids:
        raise ProbeError(reason_code)
    return result


def read_json_object(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8-sig") as handle:
            value = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise ProbeError("JSON_READ_FAILED") from exc
    if not isinstance(value, dict):
        raise ProbeError("JSON_OBJECT_REQUIRED")
    return value


def resources_by_id(profile: dict[str, Any]) -> dict[str, dict[str, Any]]:
    resources = profile.get("resources")
    if not isinstance(resources, list):
        raise ProbeError("PROFILE_RESOURCES_REQUIRED")
    result: dict[str, dict[str, Any]] = {}
    for resource in resources:
        if not isinstance(resource, dict) or not isinstance(resource.get("resourceId"), str):
            raise ProbeError("PROFILE_RESOURCE_INVALID")
        resource_id = resource["resourceId"]
        if resource_id in result:
            raise ProbeError("PROFILE_RESOURCE_DUPLICATED")
        result[resource_id] = resource
    return result


def verify_identity_fingerprint(resource: dict[str, Any]) -> None:
    identity = resource.get("identity")
    expected = resource.get("identityFingerprintSha256")
    if not isinstance(identity, dict) or not isinstance(expected, str):
        raise ProbeError("RESOURCE_IDENTITY_REQUIRED")
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ProbeError("RESOURCE_IDENTITY_FINGERPRINT_INVALID")
    if sha256_json(identity) != expected:
        raise ProbeError("RESOURCE_IDENTITY_FINGERPRINT_MISMATCH")


def chromosome_uri(template: Any) -> str:
    if not isinstance(template, str):
        raise ProbeError("JOINT_URI_TEMPLATE_REQUIRED")
    try:
        uri = template.format(chromosome="1", contig="chr1")
    except (KeyError, ValueError) as exc:
        raise ProbeError("JOINT_URI_TEMPLATE_INVALID") from exc
    return uri


def normalized_headers(headers: Any) -> dict[str, str]:
    return {key.lower(): value.strip() for key, value in headers.items()}


def head_request(uri: str) -> dict[str, Any]:
    request = Request(uri, method="HEAD", headers={"User-Agent": "Varvis-E3-P0R/1"})
    try:
        with urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            status = response.status
            headers = normalized_headers(response.headers)
    except (HTTPError, URLError, TimeoutError, OSError) as exc:
        raise ProbeError("REMOTE_HEAD_FAILED") from exc
    if status != 200:
        raise ProbeError("REMOTE_HEAD_STATUS_UNEXPECTED")
    try:
        content_length = int(headers["content-length"])
    except (KeyError, ValueError) as exc:
        raise ProbeError("REMOTE_CONTENT_LENGTH_INVALID") from exc
    if headers.get("accept-ranges", "").lower() != "bytes":
        raise ProbeError("REMOTE_BYTE_RANGES_UNAVAILABLE")
    return {
        "statusCode": status,
        "contentLength": content_length,
        "etag": headers.get("etag", "").strip('"'),
        "lastModified": headers.get("last-modified"),
        "generation": headers.get("x-goog-generation"),
        "metageneration": headers.get("x-goog-metageneration"),
        "acceptRanges": headers.get("accept-ranges"),
    }


def one_byte_range_request(uri: str, expected_total: int) -> dict[str, Any]:
    request = Request(
        uri,
        method="GET",
        headers={"Range": "bytes=0-0", "User-Agent": "Varvis-E3-P0R/1"},
    )
    try:
        with urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            status = response.status
            headers = normalized_headers(response.headers)
            body = response.read(2)
    except (HTTPError, URLError, TimeoutError, OSError) as exc:
        raise ProbeError("REMOTE_RANGE_FAILED") from exc
    if status != 206 or len(body) != 1:
        raise ProbeError("REMOTE_RANGE_NOT_BOUNDED")
    expected_content_range = f"bytes 0-0/{expected_total}"
    if headers.get("content-range") != expected_content_range:
        raise ProbeError("REMOTE_CONTENT_RANGE_MISMATCH")
    return {
        "statusCode": status,
        "contentRange": headers.get("content-range"),
        "bytesRead": len(body),
        "firstByteHex": body.hex(),
    }


def run_tabix(tabix: Path, *arguments: str) -> list[str]:
    try:
        completed = subprocess.run(
            [str(tabix), *arguments],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="strict",
            timeout=COMMAND_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as exc:
        raise ProbeError("TABIX_TIMEOUT") from exc
    except OSError as exc:
        raise ProbeError("TABIX_START_FAILED") from exc
    if completed.returncode != 0:
        raise ProbeError("TABIX_COMMAND_FAILED")
    return completed.stdout.splitlines()


def tabix_version(tabix: Path) -> dict[str, str]:
    lines = run_tabix(tabix, "--version")
    if not lines:
        raise ProbeError("TABIX_VERSION_UNAVAILABLE")
    match = re.fullmatch(r"tabix \(htslib\) ([0-9][0-9A-Za-z.+-]*)", lines[0].strip())
    if match is None:
        raise ProbeError("TABIX_VERSION_UNRECOGNIZED")
    return {"name": "tabix", "version": match.group(1), "htslibVersion": match.group(1)}


def parse_header(
    lines: Iterable[str],
) -> tuple[dict[str, tuple[str, str]], set[str], dict[str, str]]:
    info: dict[str, tuple[str, str]] = {}
    filters: set[str] = set()
    metadata: dict[str, str] = {}
    info_pattern = re.compile(
        r'^##INFO=<ID=([^,]+),Number=([^,]+),Type=([^,]+),Description="(.*)">$'
    )
    filter_pattern = re.compile(r'^##FILTER=<ID=([^,]+),Description="(.*)">$')
    for line in lines:
        info_match = info_pattern.fullmatch(line)
        if info_match:
            info[info_match.group(1)] = (
                info_match.group(2),
                info_match.group(3),
            )
            continue
        filter_match = filter_pattern.fullmatch(line)
        if filter_match:
            filters.add(filter_match.group(1))
            continue
        if line.startswith("##fileformat="):
            metadata["fileformat"] = line.partition("=")[2]
        elif line.startswith("##hailversion="):
            metadata["hailversion"] = line.partition("=")[2]
        elif line.startswith("##contig=<ID=chr1,"):
            metadata["chr1Contig"] = line
    return info, filters, metadata


def extract_profile_info_contract(field_contract: Any) -> dict[str, tuple[str, str]]:
    if not isinstance(field_contract, dict):
        raise ProbeError("PROFILE_INFO_CONTRACT_REQUIRED")
    found: dict[str, tuple[str, str]] = {}
    for section in ("primaryFields", "componentFields", "auxiliaryQcFields"):
        fields = field_contract.get(section)
        if not isinstance(fields, list):
            raise ProbeError("PROFILE_INFO_CONTRACT_REQUIRED")
        for field in fields:
            if not isinstance(field, dict) or not isinstance(
                field.get("infoId"), str
            ):
                raise ProbeError("PROFILE_INFO_CONTRACT_REQUIRED")
            field_id = field["infoId"]
            if field_id in found:
                raise ProbeError("PROFILE_INFO_CONTRACT_DUPLICATED")
            found[field_id] = (str(field.get("number")), str(field.get("type")))
    return found


def verify_header_contract(
    identity: dict[str, Any], input_semantics: Any, header_lines: list[str]
) -> dict[str, Any]:
    actual_info, actual_filters, metadata = parse_header(header_lines)
    if metadata.get("fileformat") != "VCFv4.2":
        raise ProbeError("JOINT_FILEFORMAT_MISMATCH")
    if metadata.get("hailversion") != "0.2.130-bea04d9c79b5":
        raise ProbeError("JOINT_HAIL_VERSION_MISMATCH")
    expected_contig = "##contig=<ID=chr1,length=248956422,assembly=gnomAD_GRCh38>"
    if metadata.get("chr1Contig") != expected_contig:
        raise ProbeError("JOINT_CONTIG_IDENTITY_MISMATCH")

    profile_info = extract_profile_info_contract(identity.get("fieldContract"))
    for field_id, expected in INFO_EXPECTATIONS.items():
        if actual_info.get(field_id) != expected:
            raise ProbeError("JOINT_HEADER_INFO_MISMATCH")
        if profile_info.get(field_id) != expected:
            raise ProbeError("PROFILE_INFO_CONTRACT_MISMATCH")

    if not isinstance(input_semantics, dict):
        raise ProbeError("PROFILE_INPUT_SEMANTICS_REQUIRED")
    profile_filters = input_semantics.get("filterSemantics")
    if not isinstance(profile_filters, dict):
        raise ProbeError("PROFILE_FILTER_CONTRACT_REQUIRED")
    if not FILTER_EXPECTATIONS.issubset(actual_filters):
        raise ProbeError("JOINT_HEADER_FILTER_MISMATCH")
    if set(profile_filters) != FILTER_EXPECTATIONS:
        raise ProbeError("PROFILE_FILTER_CONTRACT_MISMATCH")

    return {
        "status": "MATCH",
        "headerLineCount": len(header_lines),
        "fileformat": metadata["fileformat"],
        "hailVersion": metadata["hailversion"],
        "chr1": {"length": 248956422, "assembly": "gnomAD_GRCh38"},
        "selectedInfoFields": {
            field_id: {"number": value[0], "type": value[1]}
            for field_id, value in INFO_EXPECTATIONS.items()
        },
        "filters": sorted(FILTER_EXPECTATIONS),
    }


def verify_profile_qc(input_semantics: Any) -> None:
    if not isinstance(input_semantics, dict):
        raise ProbeError("PROFILE_INPUT_SEMANTICS_REQUIRED")
    qc = input_semantics.get("frequencyConflictQc")
    if not isinstance(qc, dict):
        raise ProbeError("PROFILE_FREQUENCY_QC_REQUIRED")
    expected = {
        "sourceField": "stat_union_p_value",
        "conflictOperator": "LESS_THAN",
        "thresholdRaw": DISCORDANCE_THRESHOLD_RAW,
    }
    for key, value in expected.items():
        if qc.get(key) != value:
            raise ProbeError("PROFILE_FREQUENCY_QC_MISMATCH")
    if qc.get("rawFieldsRetained") != [
        "stat_union_p_value",
        "stat_union_test_name",
        "stat_union_gen_ancs",
    ]:
        raise ProbeError("PROFILE_FREQUENCY_QC_RAW_FIELDS_MISMATCH")


def verify_profile_fixed_cases(probe_state: Any) -> None:
    if not isinstance(probe_state, dict) or not isinstance(probe_state.get("cases"), list):
        raise ProbeError("PROFILE_FIXED_CASES_REQUIRED")
    expected = {
        PASS_HIT_CASE_ID: (
            FIXED_ASSEMBLY,
            FIXED_CANONICAL_CONTIG,
            FIXED_RESOURCE_CONTIG,
            12591,
            "C",
            "T",
            "HIT",
            1,
        ),
        EXACT_ALLELE_NO_RECORD_CASE_ID: (
            FIXED_ASSEMBLY,
            FIXED_CANONICAL_CONTIG,
            FIXED_RESOURCE_CONTIG,
            12591,
            "C",
            "A",
            "NO_RECORD",
            0,
        ),
        DISCORDANCE_QC_HIT_CASE_ID: (
            FIXED_ASSEMBLY,
            FIXED_CANONICAL_CONTIG,
            FIXED_RESOURCE_CONTIG,
            55006970,
            "G",
            "T",
            "HIT",
            1,
        ),
    }
    observed: dict[str, tuple[Any, ...]] = {}
    for case in probe_state["cases"]:
        if not isinstance(case, dict) or not isinstance(case.get("queryAllele"), dict):
            raise ProbeError("PROFILE_FIXED_CASE_INVALID")
        allele = case["queryAllele"]
        query = case.get("queryOutcome")
        if not isinstance(query, dict):
            raise ProbeError("PROFILE_FIXED_CASE_INVALID")
        if case.get("resourceExecution", {}).get("status") != "SUCCEEDED":
            raise ProbeError("PROFILE_FIXED_CASE_AXES_MISMATCH")
        if case.get("resourceAvailability", {}).get("status") != "AVAILABLE":
            raise ProbeError("PROFILE_FIXED_CASE_AXES_MISMATCH")
        if case.get("resourceApplicability", {}).get("status") != "APPLICABLE":
            raise ProbeError("PROFILE_FIXED_CASE_AXES_MISMATCH")
        observed[str(case.get("caseId"))] = (
            allele.get("assembly"),
            allele.get("canonicalContig"),
            allele.get("resourceContig"),
            allele.get("position"),
            allele.get("reference"),
            allele.get("alternate"),
            query.get("status"),
            query.get("recordCount"),
        )
    if observed != expected:
        raise ProbeError("PROFILE_FIXED_CASES_MISMATCH")


def parse_info(raw: str) -> dict[str, str | None]:
    values: dict[str, str | None] = {}
    if raw == ".":
        return values
    for entry in raw.split(";"):
        key, separator, value = entry.partition("=")
        if not key:
            raise ProbeError("VCF_INFO_INVALID")
        values[key] = value if separator else None
    return values


def parse_records(lines: Iterable[str]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line in lines:
        columns = line.split("\t")
        if len(columns) < 8:
            raise ProbeError("VCF_RECORD_INVALID")
        try:
            position = int(columns[1])
        except ValueError as exc:
            raise ProbeError("VCF_POSITION_INVALID") from exc
        records.append(
            {
                "chromosome": columns[0],
                "position": position,
                "reference": columns[3],
                "alternates": columns[4].split(","),
                "filter": columns[6],
                "info": parse_info(columns[7]),
            }
        )
    return records


def exact_matches(
    records: Iterable[dict[str, Any]],
    chromosome: str,
    position: int,
    reference: str,
    alternate: str,
) -> list[tuple[dict[str, Any], int]]:
    matches: list[tuple[dict[str, Any], int]] = []
    for record in records:
        if (
            record["chromosome"] == chromosome
            and record["position"] == position
            and record["reference"] == reference
            and alternate in record["alternates"]
        ):
            matches.append((record, record["alternates"].index(alternate)))
    return matches


def plain_decimal(raw: str) -> str:
    try:
        value = Decimal(raw)
    except InvalidOperation as exc:
        raise ProbeError("VCF_DECIMAL_INVALID") from exc
    if not value.is_finite():
        raise ProbeError("VCF_DECIMAL_NON_FINITE")
    if value == 0:
        return "0"
    return format(value.normalize(), "f")


def selected_raw_value(record: dict[str, Any], field_id: str, alt_index: int) -> str:
    raw = record["info"].get(field_id)
    if raw is None:
        raise ProbeError("REQUIRED_INFO_VALUE_MISSING")
    number = INFO_EXPECTATIONS[field_id][0]
    if number == "A":
        values = raw.split(",")
        if alt_index >= len(values):
            raise ProbeError("NUMBER_A_VALUE_MISSING")
        return values[alt_index]
    return raw


def output_value(record: dict[str, Any], field_id: str, alt_index: int) -> dict[str, str]:
    raw = selected_raw_value(record, field_id, alt_index)
    value = {"raw": raw}
    if field_id in NUMERIC_FIELDS:
        value["normalized"] = plain_decimal(raw)
    return value


def query_fingerprint(
    resource_id: str,
    canonical_contig: str,
    resource_contig: str,
    position: int,
    reference: str,
    alternate: str,
) -> str:
    return sha256_json(
        {
            "resourceId": resource_id,
            "assembly": FIXED_ASSEMBLY,
            "canonicalContig": canonical_contig,
            "resourceContig": resource_contig,
            "position": position,
            "reference": reference,
            "alternate": alternate,
        }
    )


def transport_evidence(
    data_head: dict[str, Any],
    data_range: dict[str, Any],
    index_head: dict[str, Any],
    index_range: dict[str, Any],
) -> dict[str, Any]:
    return {
        "origin": "storage.googleapis.com",
        "data": {"head": data_head, "range": data_range},
        "index": {"head": index_head, "range": index_range},
        "explicitOneByteProbeBodyBytesRead": (
            data_range["bytesRead"] + index_range["bytesRead"]
        ),
    }


def axes(
    execution_status: str,
    availability_status: str,
    query_status: str,
    applicability_status: str,
    record_count: int,
    fingerprint: str | None = None,
    reason_code: str | None = None,
    attempted_at: str | None = None,
    completed_at: str | None = None,
) -> dict[str, Any]:
    execution: dict[str, Any] = {"status": execution_status}
    if reason_code is not None:
        execution["reasonCode"] = reason_code
    if execution_status == "SUCCEEDED":
        execution["attemptedAt"] = attempted_at
        execution["completedAt"] = completed_at
    query: dict[str, Any] = {"status": query_status, "recordCount": record_count}
    if fingerprint is not None:
        query["queryFingerprint"] = fingerprint
    return {
        "resourceExecution": execution,
        "resourceAvailability": {"status": availability_status},
        "queryOutcome": query,
        "resourceApplicability": {"status": applicability_status},
    }


def discordance(record: dict[str, Any], alt_index: int) -> dict[str, Any]:
    p_value_raw = selected_raw_value(record, "stat_union_p_value", alt_index)
    try:
        is_discordant = Decimal(p_value_raw) < Decimal(DISCORDANCE_THRESHOLD_RAW)
    except InvalidOperation as exc:
        raise ProbeError("DISCORDANCE_P_VALUE_INVALID") from exc
    return {
        "isDiscordant": is_discordant,
        "derivation": "STAT_UNION_P_VALUE_ONLY",
        "threshold": {
            "operator": "<",
            "raw": DISCORDANCE_THRESHOLD_RAW,
            "normalized": plain_decimal(DISCORDANCE_THRESHOLD_RAW),
        },
        "pValue": {
            "raw": p_value_raw,
            "normalized": plain_decimal(p_value_raw),
        },
        "testNameRaw": selected_raw_value(record, "stat_union_test_name", alt_index),
        "geneticAncestryGroupsRaw": selected_raw_value(
            record, "stat_union_gen_ancs", alt_index
        ),
    }


def hit_case(
    resource_id: str,
    label: str,
    records: list[dict[str, Any]],
    position: int,
    reference: str,
    alternate: str,
    attempted_at: str,
    completed_at: str,
) -> dict[str, Any]:
    matches = exact_matches(
        records, FIXED_RESOURCE_CONTIG, position, reference, alternate
    )
    if len(matches) != 1:
        raise ProbeError("FIXED_HIT_DRIFTED")
    record, alt_index = matches[0]
    if record["filter"] != "PASS":
        raise ProbeError("FIXED_HIT_FILTER_DRIFTED")
    fingerprint = query_fingerprint(
        resource_id,
        FIXED_CANONICAL_CONTIG,
        FIXED_RESOURCE_CONTIG,
        position,
        reference,
        alternate,
    )
    return {
        "caseId": label,
        "variant": {
            "assembly": FIXED_ASSEMBLY,
            "canonicalContig": FIXED_CANONICAL_CONTIG,
            "resourceContig": FIXED_RESOURCE_CONTIG,
            "position": position,
            "reference": reference,
            "alternate": alternate,
        },
        "axes": axes(
            "SUCCEEDED",
            "AVAILABLE",
            "HIT",
            "APPLICABLE",
            1,
            fingerprint,
            attempted_at=attempted_at,
            completed_at=completed_at,
        ),
        "locusRecordCount": len(records),
        "filterRaw": record["filter"],
        "values": {
            field_id: output_value(record, field_id, alt_index)
            for field_id in OUTPUT_FIELDS
        },
        "frequencyDiscordance": discordance(record, alt_index),
    }


def no_record_case(
    resource_id: str,
    records: list[dict[str, Any]],
    position: int,
    reference: str,
    alternate: str,
    attempted_at: str,
    completed_at: str,
) -> dict[str, Any]:
    matches = exact_matches(
        records, FIXED_RESOURCE_CONTIG, position, reference, alternate
    )
    if matches:
        raise ProbeError("FIXED_NO_RECORD_DRIFTED")
    fingerprint = query_fingerprint(
        resource_id,
        FIXED_CANONICAL_CONTIG,
        FIXED_RESOURCE_CONTIG,
        position,
        reference,
        alternate,
    )
    return {
        "caseId": EXACT_ALLELE_NO_RECORD_CASE_ID,
        "variant": {
            "assembly": FIXED_ASSEMBLY,
            "canonicalContig": FIXED_CANONICAL_CONTIG,
            "resourceContig": FIXED_RESOURCE_CONTIG,
            "position": position,
            "reference": reference,
            "alternate": alternate,
        },
        "axes": axes(
            "SUCCEEDED",
            "AVAILABLE",
            "NO_RECORD",
            "APPLICABLE",
            0,
            fingerprint,
            attempted_at=attempted_at,
            completed_at=completed_at,
        ),
        "locusRecordCount": len(records),
        "frequencyMeasurement": None,
        "reasonCode": "EXACT_ALLELE_ABSENT",
    }


def verify_all_sites_identity(resource: dict[str, Any]) -> dict[str, Any]:
    verify_identity_fingerprint(resource)
    identity = resource["identity"]
    required = (
        "tableUri",
        "partitionCount",
        "rowCount",
        "writerHailVersion",
        "nativeFileVersion",
        "keyFields",
        "rowSchema",
        "globalsSchema",
        "strataResolution",
        "metadataAnchors",
    )
    if any(identity.get(field) in (None, "", [], {}) for field in required):
        raise ProbeError("ALL_SITES_METADATA_INCOMPLETE")
    probe_state = resource.get("probeState")
    if not isinstance(probe_state, dict):
        raise ProbeError("ALL_SITES_PROBE_STATE_REQUIRED")
    if probe_state.get("metadataStatus") != "VERIFIED":
        raise ProbeError("ALL_SITES_METADATA_NOT_VERIFIED")
    if probe_state.get("remotePointQueryStatus") != "VERIFIED":
        raise ProbeError("ALL_SITES_POINT_QUERY_NOT_VERIFIED")
    if probe_state.get("runtimeIntegrationStatus") != "NOT_IMPLEMENTED":
        raise ProbeError("ALL_SITES_RUNTIME_BOUNDARY_MISMATCH")
    if (
        probe_state.get("verificationScope")
        != "PUBLIC_FIXED_LOCUS_HAIL_FILTER_INTERVALS"
    ):
        raise ProbeError("ALL_SITES_VERIFICATION_SCOPE_MISMATCH")
    selection = probe_state.get("observedStrataSelection")
    if not isinstance(selection, dict):
        raise ProbeError("ALL_SITES_STRATA_SELECTION_REQUIRED")
    selector = identity["strataResolution"]["selector"]
    if selection.get("selector") != selector or selection.get("selectedMetadata") != selector:
        raise ProbeError("ALL_SITES_STRATA_SELECTION_MISMATCH")
    if (
        not isinstance(selection.get("selectedIndex"), int)
        or not isinstance(selection.get("selectedSampleCount"), int)
        or not isinstance(selection.get("mappingEntryCount"), int)
        or selection["mappingEntryCount"] <= 0
    ):
        raise ProbeError("ALL_SITES_STRATA_SELECTION_INVALID")
    profile_cases = probe_state.get("cases")
    if not isinstance(profile_cases, list) or len(profile_cases) != 2:
        raise ProbeError("ALL_SITES_PROFILE_CASES_REQUIRED")
    cases_by_id = {
        case.get("caseId"): case for case in profile_cases if isinstance(case, dict)
    }
    if set(cases_by_id) != {ALL_SITES_HIT_CASE_ID, ALL_SITES_NO_RECORD_CASE_ID}:
        raise ProbeError("ALL_SITES_PROFILE_CASE_IDS_MISMATCH")
    expected_cases = {
        ALL_SITES_HIT_CASE_ID: (ALL_SITES_HIT_LOCUS, "HIT", 1),
        ALL_SITES_NO_RECORD_CASE_ID: (ALL_SITES_NO_RECORD_LOCUS, "NO_RECORD", 0),
    }
    for case_id, (locus, outcome, count) in expected_cases.items():
        case = cases_by_id[case_id]
        if case.get("queryLocus") != locus:
            raise ProbeError("ALL_SITES_PROFILE_CASE_LOCUS_MISMATCH")
        expected_axes = {
            "resourceExecution": {"status": "SUCCEEDED"},
            "resourceAvailability": {"status": "AVAILABLE"},
            "queryOutcome": {"status": outcome, "recordCount": count},
            "resourceApplicability": {"status": "APPLICABLE"},
        }
        if any(case.get(axis_name) != value for axis_name, value in expected_axes.items()):
            raise ProbeError("ALL_SITES_PROFILE_CASE_AXES_MISMATCH")
    hit_measurement = cases_by_id[ALL_SITES_HIT_CASE_ID].get("measurement")
    if not isinstance(hit_measurement, dict) or not isinstance(
        hit_measurement.get("adjAlleleNumber"), int
    ):
        raise ProbeError("ALL_SITES_PROFILE_ADJ_AN_REQUIRED")
    if "measurement" in cases_by_id[ALL_SITES_NO_RECORD_CASE_ID]:
        raise ProbeError("ALL_SITES_NO_RECORD_MEASUREMENT_FORBIDDEN")
    anchors = identity["metadataAnchors"]
    expected_suffixes = {"README.txt", "metadata.json.gz"}
    if not isinstance(anchors, list) or len(anchors) != len(expected_suffixes):
        raise ProbeError("ALL_SITES_METADATA_ANCHORS_INVALID")
    anchors_by_suffix: dict[str, dict[str, Any]] = {}
    for anchor in anchors:
        if not isinstance(anchor, dict) or not isinstance(anchor.get("logicalSuffix"), str):
            raise ProbeError("ALL_SITES_METADATA_ANCHOR_INVALID")
        suffix = anchor["logicalSuffix"]
        if suffix in anchors_by_suffix:
            raise ProbeError("ALL_SITES_METADATA_ANCHOR_INVALID")
        anchors_by_suffix[suffix] = anchor
    if set(anchors_by_suffix) != expected_suffixes:
        raise ProbeError("ALL_SITES_METADATA_ANCHOR_INVALID")
    live_metadata: dict[str, Any] = {}
    official_metadata_uris: dict[str, str] = {}
    for suffix in sorted(expected_suffixes):
        anchor = anchors_by_suffix[suffix]
        expected = {
            "contentLength": anchor.get("byteLength"),
            "generation": str(anchor.get("generation")),
            "etag": anchor.get("etag"),
        }
        if (
            not isinstance(expected["contentLength"], int)
            or not isinstance(expected["etag"], str)
            or not re.fullmatch(r"[0-9]+", expected["generation"])
        ):
            raise ProbeError("ALL_SITES_PROFILE_METADATA_INVALID")
        https_uri = anchor.get("httpsUri")
        if not isinstance(https_uri, str):
            raise ProbeError("ALL_SITES_METADATA_URI_REQUIRED")
        expected_prefix = identity["tableUri"].replace(
            "gs://", "https://storage.googleapis.com/", 1
        ).rstrip("/")
        if https_uri != f"{expected_prefix}/{suffix}":
            raise ProbeError("ALL_SITES_METADATA_URI_MISMATCH")
        observed = head_request(https_uri)
        for key, value in expected.items():
            if observed.get(key) != value:
                raise ProbeError("ALL_SITES_LIVE_METADATA_DRIFTED")
        live_metadata[suffix] = observed
        official_metadata_uris[suffix] = https_uri
    return {
        "resourceId": resource["resourceId"],
        "resourceVersion": resource.get("resourceVersion"),
        "assembly": resource.get("assembly"),
        "identityFingerprintSha256": resource["identityFingerprintSha256"],
        "metadataVerification": {
            "anchorHeadStatus": "ANCHOR_HEAD_MATCHED",
            "schemaSource": "PROFILE_DECLARED",
        },
        "officialUris": {
            "table": identity["tableUri"],
            "metadata": official_metadata_uris,
        },
        "liveAnchorHeadMetadata": live_metadata,
    }


def profile_case_axes(case: dict[str, Any]) -> dict[str, Any]:
    return {
        axis_name: case[axis_name]
        for axis_name in (
            "resourceExecution",
            "resourceAvailability",
            "queryOutcome",
            "resourceApplicability",
        )
    }


def validate_all_sites_hail_result(
    artifact: dict[str, Any],
    profile: dict[str, Any],
    resources: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    if artifact.get("probeSchemaVersion") != ALL_SITES_HAIL_PROBE_SCHEMA_VERSION:
        raise ProbeError("ALL_SITES_HAIL_SCHEMA_MISMATCH")
    if artifact.get("status") != "VERIFIED":
        raise ProbeError("ALL_SITES_HAIL_RESULT_NOT_VERIFIED")
    expected_profile = {
        "profileId": profile.get("profileId"),
        "profileVersion": profile.get("profileVersion"),
        "profileSha256": sha256_json(profile),
    }
    if artifact.get("profile") != expected_profile:
        raise ProbeError("ALL_SITES_HAIL_PROFILE_MISMATCH")
    boundary = artifact.get("boundary")
    expected_boundary = {
        "fixedPublicLociOnly": True,
        "indexedIntervalsOnly": True,
        "fullHailTableDownloaded": False,
        "derivedResourceCacheCreated": False,
        "runtimeIntegrationRun": False,
        "patientDataUsed": False,
        "clinicalRuleEvaluationRun": False,
        "machinePathsIncluded": False,
    }
    if boundary != expected_boundary:
        raise ProbeError("ALL_SITES_HAIL_BOUNDARY_MISMATCH")
    reject_clinical_payload(artifact)
    serialized = canonical_json(artifact)
    if LOCAL_PATH_PATTERN.search(serialized):
        raise ProbeError("ALL_SITES_HAIL_MACHINE_PATH_LEAKED")
    tool = artifact.get("tool")
    expected_tool_keys = set(EXPECTED_HAIL_TOOL) | {"javaVersion"}
    if not isinstance(tool, dict) or set(tool) != expected_tool_keys:
        raise ProbeError("ALL_SITES_HAIL_TOOL_PROVENANCE_MISMATCH")
    for key, value in EXPECTED_HAIL_TOOL.items():
        if tool.get(key) != value:
            raise ProbeError("ALL_SITES_HAIL_TOOL_PROVENANCE_MISMATCH")
    if not str(tool.get("javaVersion", "")).startswith(
        'openjdk version "11.0.32"'
    ):
        raise ProbeError("ALL_SITES_HAIL_TOOL_PROVENANCE_MISMATCH")
    top_execution = artifact.get("execution")
    if not isinstance(top_execution, dict) or set(top_execution) != {
        "startedAt",
        "completedAt",
    }:
        raise ProbeError("ALL_SITES_HAIL_EXECUTION_PROVENANCE_MISMATCH")
    if not all(
        isinstance(top_execution.get(key), str)
        for key in ("startedAt", "completedAt")
    ) or artifact.get("observedAt") != top_execution["completedAt"]:
        raise ProbeError("ALL_SITES_HAIL_EXECUTION_PROVENANCE_MISMATCH")
    artifact_by_id = unique_objects_by_id(
        artifact.get("resources"),
        "resourceId",
        set(ALL_SITES_RESOURCE_IDS),
        "ALL_SITES_HAIL_RESOURCE_IDS_MISMATCH",
    )

    concise_resources: dict[str, dict[str, Any]] = {}
    for resource_id in ALL_SITES_RESOURCE_IDS:
        source = artifact_by_id[resource_id]
        profile_resource = resources[resource_id]
        identity = profile_resource["identity"]
        expected_identity = {
            "resourceVersion": profile_resource.get("resourceVersion"),
            "assembly": profile_resource.get("assembly"),
            "identityFingerprintSha256": profile_resource.get(
                "identityFingerprintSha256"
            ),
            "officialTableUri": identity.get("tableUri"),
        }
        if any(source.get(key) != value for key, value in expected_identity.items()):
            raise ProbeError("ALL_SITES_HAIL_RESOURCE_IDENTITY_MISMATCH")
        resource_execution = source.get("execution")
        if not isinstance(resource_execution, dict) or set(resource_execution) != {
            "startedAt",
            "completedAt",
            "queryMode",
            "maximumRowsCollected",
            "partitionCountPlanned",
        }:
            raise ProbeError("ALL_SITES_HAIL_RESOURCE_EXECUTION_MISMATCH")
        if (
            not isinstance(resource_execution.get("startedAt"), str)
            or not isinstance(resource_execution.get("completedAt"), str)
            or resource_execution.get("queryMode")
            != "HAIL_FILTER_INTERVALS_FIXED_PUBLIC_LOCI"
            or resource_execution.get("maximumRowsCollected") != 3
            or resource_execution.get("partitionCountPlanned") not in {1, 2}
        ):
            raise ProbeError("ALL_SITES_HAIL_RESOURCE_EXECUTION_MISMATCH")
        profile_state = profile_resource["probeState"]
        expected_selection = profile_state["observedStrataSelection"]
        strata = source.get("strata")
        if not isinstance(strata, dict):
            raise ProbeError("ALL_SITES_HAIL_STRATA_REQUIRED")
        for key in (
            "selector",
            "selectedIndex",
            "selectedMetadata",
            "selectedSampleCount",
        ):
            if strata.get(key) != expected_selection.get(key):
                raise ProbeError("ALL_SITES_HAIL_STRATA_MISMATCH")
        mapping = strata.get("mapping")
        expected_mapping_count = expected_selection["mappingEntryCount"]
        if not isinstance(mapping, list) or len(mapping) != expected_mapping_count:
            raise ProbeError("ALL_SITES_HAIL_MAPPING_COUNT_MISMATCH")
        if [entry.get("index") for entry in mapping if isinstance(entry, dict)] != list(
            range(expected_mapping_count)
        ):
            raise ProbeError("ALL_SITES_HAIL_MAPPING_INDEX_MISMATCH")
        exact_matches = [
            entry["index"]
            for entry in mapping
            if entry.get("metadata") == strata.get("selector")
        ]
        if exact_matches != [strata.get("selectedIndex")]:
            raise ProbeError("ALL_SITES_HAIL_STRATA_SELECTOR_NOT_UNIQUE")
        selected_mapping = mapping[expected_selection["selectedIndex"]]
        if (
            selected_mapping.get("metadata") != expected_selection["selectedMetadata"]
            or selected_mapping.get("sampleCount")
            != expected_selection["selectedSampleCount"]
        ):
            raise ProbeError("ALL_SITES_HAIL_SELECTED_MAPPING_MISMATCH")

        expected_case_ids = {
            ALL_SITES_HIT_CASE_ID,
            ALL_SITES_NO_RECORD_CASE_ID,
        }
        source_cases_by_id = unique_objects_by_id(
            source.get("cases"),
            "caseId",
            expected_case_ids,
            "ALL_SITES_HAIL_CASE_IDS_MISMATCH",
        )
        profile_cases_by_id = unique_objects_by_id(
            profile_state.get("cases"),
            "caseId",
            expected_case_ids,
            "ALL_SITES_PROFILE_CASE_IDS_MISMATCH",
        )
        concise_cases: list[dict[str, Any]] = []
        for case_id in (ALL_SITES_HIT_CASE_ID, ALL_SITES_NO_RECORD_CASE_ID):
            case = source_cases_by_id[case_id]
            expected_case = profile_cases_by_id[case_id]
            if case.get("queryLocus") != expected_case.get("queryLocus"):
                raise ProbeError("ALL_SITES_HAIL_CASE_LOCUS_MISMATCH")
            if case.get("axes") != profile_case_axes(expected_case):
                raise ProbeError("ALL_SITES_HAIL_CASE_AXES_MISMATCH")
            concise_case: dict[str, Any] = {
                "caseId": case_id,
                "queryLocus": case["queryLocus"],
                "axes": case["axes"],
            }
            if case_id == ALL_SITES_HIT_CASE_ID:
                measurement = case.get("measurement")
                expected_measurement = expected_case.get("measurement")
                if not isinstance(measurement, dict) or not isinstance(
                    expected_measurement, dict
                ):
                    raise ProbeError("ALL_SITES_HAIL_MEASUREMENT_REQUIRED")
                allele_numbers = measurement.get("alleleNumberArray")
                if (
                    not isinstance(allele_numbers, list)
                    or len(allele_numbers) != expected_mapping_count
                ):
                    raise ProbeError("ALL_SITES_HAIL_AN_ARRAY_MISMATCH")
                selected_index = expected_selection["selectedIndex"]
                adj_an = expected_measurement["adjAlleleNumber"]
                if (
                    measurement.get("adjStrataIndex") != selected_index
                    or measurement.get("adjAlleleNumber") != adj_an
                    or allele_numbers[selected_index] != adj_an
                ):
                    raise ProbeError("ALL_SITES_HAIL_ADJ_AN_MISMATCH")
                if measurement.get("regionFlags") != expected_measurement.get(
                    "regionFlags"
                ):
                    raise ProbeError("ALL_SITES_HAIL_REGION_FLAGS_MISMATCH")
                concise_measurement: dict[str, Any] = {
                    "adjAlleleNumber": adj_an,
                    "adjStrataIndex": selected_index,
                    "alleleNumberArrayLength": len(allele_numbers),
                    "alleleNumberArraySha256": sha256_json(allele_numbers),
                }
                if "regionFlags" in measurement:
                    concise_measurement["regionFlags"] = measurement["regionFlags"]
                concise_case["measurement"] = concise_measurement
            elif "measurement" in case:
                raise ProbeError("ALL_SITES_HAIL_NO_RECORD_MEASUREMENT_FORBIDDEN")
            concise_cases.append(concise_case)

        concise_resources[resource_id] = {
            "resourceId": resource_id,
            **expected_identity,
            "execution": resource_execution,
            "strata": {
                "selector": strata["selector"],
                "selectedIndex": strata["selectedIndex"],
                "selectedMetadata": strata["selectedMetadata"],
                "selectedSampleCount": strata["selectedSampleCount"],
                "mappingEntryCount": len(mapping),
                "mappingSha256": sha256_json(mapping),
            },
            "cases": concise_cases,
        }
    return {
        "observedAt": artifact.get("observedAt"),
        "tool": tool,
        "execution": top_execution,
        "resources": concise_resources,
        "boundary": boundary,
    }


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    parent = path.parent.resolve()
    parent.mkdir(parents=True, exist_ok=True)
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=parent,
            prefix=path.name + ".",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temporary_name = handle.name
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temporary_name, path)
    finally:
        if temporary_name is not None and os.path.exists(temporary_name):
            os.unlink(temporary_name)


def run_probe(
    tabix: Path,
    all_sites_hail_result_path: Path,
    output: Path,
) -> dict[str, Any]:
    if not tabix.is_file():
        raise ProbeError("TABIX_EXECUTABLE_REQUIRED")
    if not all_sites_hail_result_path.is_file():
        raise ProbeError("ALL_SITES_HAIL_RESULT_REQUIRED")
    repository_root = Path(__file__).resolve().parents[2]
    profile_path = repository_root / PROFILE_RELATIVE_PATH
    profile = read_json_object(profile_path)
    resources = resources_by_id(profile)
    try:
        joint = resources[JOINT_RESOURCE_ID]
        all_sites_resources = [resources[resource_id] for resource_id in ALL_SITES_RESOURCE_IDS]
    except KeyError as exc:
        raise ProbeError("PROFILE_REQUIRED_RESOURCE_MISSING") from exc

    verify_identity_fingerprint(joint)
    identity = joint["identity"]
    input_semantics = joint.get("inputSemantics")
    verify_profile_qc(input_semantics)
    verify_profile_fixed_cases(joint.get("probeState"))
    data_uri = chromosome_uri(identity.get("dataUriTemplate"))
    index_uri = chromosome_uri(identity.get("indexUriTemplate"))
    if data_uri != OFFICIAL_CHR1_DATA_URI or index_uri != OFFICIAL_CHR1_INDEX_URI:
        raise ProbeError("JOINT_OFFICIAL_URI_MISMATCH")

    started_at = utc_now()
    data_head = head_request(data_uri)
    index_head = head_request(index_uri)
    expected_data = {
        "contentLength": 72058349094,
        "etag": "11c62331b0a654fce6a9cd43838de648",
        "generation": "1714521653973212",
    }
    expected_index = {
        "contentLength": 225210,
        "etag": "bfa8b1ca198afccfa348c1949be6670a",
        "generation": "1714521654854864",
    }
    for key, expected in expected_data.items():
        if data_head.get(key) != expected:
            raise ProbeError("JOINT_DATA_OBJECT_DRIFTED")
    for key, expected in expected_index.items():
        if index_head.get(key) != expected:
            raise ProbeError("JOINT_INDEX_OBJECT_DRIFTED")
    data_range = one_byte_range_request(data_uri, data_head["contentLength"])
    index_range = one_byte_range_request(index_uri, index_head["contentLength"])
    if data_range["firstByteHex"] != "1f" or index_range["firstByteHex"] != "1f":
        raise ProbeError("REMOTE_BGZF_PREFIX_MISMATCH")

    version = tabix_version(tabix)
    header_lines = run_tabix(tabix, "-H", data_uri)
    header_contract = verify_header_contract(identity, input_semantics, header_lines)
    pass_lines = run_tabix(tabix, data_uri, "chr1:12591-12591")
    conflict_lines = run_tabix(tabix, data_uri, "chr1:55006970-55006970")
    pass_records = parse_records(pass_lines)
    conflict_records = parse_records(conflict_lines)
    all_sites_metadata_results = [
        verify_all_sites_identity(resource) for resource in all_sites_resources
    ]
    all_sites_hail_result = validate_all_sites_hail_result(
        read_json_object(all_sites_hail_result_path),
        profile,
        resources,
    )
    all_sites_query_by_id = all_sites_hail_result["resources"]
    all_sites_results = []
    for metadata_result in all_sites_metadata_results:
        resource_id = metadata_result["resourceId"]
        metadata_result["remotePointQuery"] = all_sites_query_by_id[resource_id]
        all_sites_results.append(metadata_result)
    completed_at = utc_now()

    result = {
        "probeSchemaVersion": PROBE_SCHEMA_VERSION,
        "status": "VERIFIED",
        "observedAt": completed_at,
        "profile": {
            "profileId": profile.get("profileId"),
            "profileVersion": profile.get("profileVersion"),
            "profileSha256": sha256_json(profile),
        },
        "tool": version,
        "joint": {
            "resourceId": joint["resourceId"],
            "resourceVersion": joint.get("resourceVersion"),
            "assembly": joint.get("assembly"),
            "identityFingerprintSha256": joint["identityFingerprintSha256"],
            "officialUris": {"data": data_uri, "index": index_uri},
            "transport": transport_evidence(
                data_head, data_range, index_head, index_range
            ),
            "headerContract": header_contract,
            "cases": [
                hit_case(
                    joint["resourceId"],
                    PASS_HIT_CASE_ID,
                    pass_records,
                    12591,
                    "C",
                    "T",
                    started_at,
                    completed_at,
                ),
                no_record_case(
                    joint["resourceId"],
                    pass_records,
                    12591,
                    "C",
                    "A",
                    started_at,
                    completed_at,
                ),
                hit_case(
                    joint["resourceId"],
                    DISCORDANCE_QC_HIT_CASE_ID,
                    conflict_records,
                    55006970,
                    "G",
                    "T",
                    started_at,
                    completed_at,
                ),
            ],
        },
        "allSites": {
            "observedAt": all_sites_hail_result["observedAt"],
            "tool": all_sites_hail_result["tool"],
            "execution": all_sites_hail_result["execution"],
            "resources": all_sites_results,
        },
        "boundary": {
            "retrieval": "REMOTE_INDEXED_FIXED_POINTS_ONLY",
            "fullVcfDownloaded": False,
            "fullHailTableDownloaded": False,
            "derivedResourceCacheCreated": False,
            "allSitesLocusQueryRun": True,
            "runtimeIntegrationRun": False,
            "patientDataUsed": False,
            "clinicalRuleEvaluationRun": False,
            "machinePathsIncluded": False,
        },
    }
    reject_clinical_payload(result)
    serialized = canonical_json(result)
    if LOCAL_PATH_PATTERN.search(serialized):
        raise ProbeError("MACHINE_PATH_LEAKED")
    atomic_write_json(output, result)
    return result


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the bounded gnomAD v4.1 remote-resource probe."
    )
    parser.add_argument("--tabix", required=True, type=Path)
    parser.add_argument("--all-sites-hail-result", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        result = run_probe(
            args.tabix.resolve(),
            args.all_sites_hail_result.resolve(),
            args.output.resolve(),
        )
    except ProbeError as exc:
        print(f"gnomAD remote probe failed: {exc}", file=sys.stderr)
        return 1
    print(result["status"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
