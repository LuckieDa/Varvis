#!/usr/bin/env python3
"""Bounded gnomAD v4.1 all-sites AN queries using an installed Hail runtime.

The original fixed public-locus probe remains available.  Batch mode reads only
the requested GRCh38 loci from both official all-sites Hail Tables and never
evaluates an ACMG criterion.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PROBE_SCHEMA_VERSION = "gnomad-all-sites-hail-probe-v1"
BATCH_REQUEST_SCHEMA_VERSION = "gnomad-all-sites-an-batch-request-v1"
BATCH_RESULT_SCHEMA_VERSION = "gnomad-all-sites-an-batch-result-v1"
BATCH_MAX_UNIQUE_LOCI = 500
PROFILE_RELATIVE_PATH = Path(
    "docs/execution/E3_P0R_GNOMAD_REMOTE_RESOURCE_PROFILE.json"
)
RESOURCE_IDS = (
    "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN",
    "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN",
)
OFFICIAL_TABLE_URIS = {
    "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN": (
        "gs://gcp-public-data--gnomad/release/4.1/ht/exomes/"
        "gnomad.exomes.v4.1.allele_number_all_sites.ht"
    ),
    "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN": (
        "gs://gcp-public-data--gnomad/release/4.1/ht/genomes/"
        "gnomad.genomes.v4.1.allele_number_all_sites.ht"
    ),
}
HIT_LOCUS = {
    "caseId": "ALL_SITES_AN_HIT",
    "assembly": "GRCh38",
    "canonicalContig": "3",
    "resourceContig": "chr3",
    "position": 10052405,
}
NO_RECORD_LOCUS = {
    "caseId": "ALL_SITES_AN_NO_RECORD",
    "assembly": "GRCh38",
    "canonicalContig": "1",
    "resourceContig": "chr1",
    "position": 1,
}
EXOME_REGION_FIELDS = (
    "outside_broad_capture_region",
    "outside_ukb_capture_region",
    "outside_broad_calling_region",
    "outside_ukb_calling_region",
)
GCS_CONNECTOR_VERSION = "2.2.33"
GCS_CONNECTOR_BYTES = 38604231
GCS_CONNECTOR_SHA256 = (
    "cd47e5ef4d18a97576e0421b74812b87b2cb0d580c59bbc7113f5f526d46e48b"
)
LOCAL_PATH_PATTERN = re.compile(
    r"(?:(?<![A-Za-z0-9])[A-Za-z]:[\\/]|\\\\|file:(?://)?|"
    r"(?<![:A-Za-z0-9])/(?:data|home|root|Users|tmp|var|opt|srv|mnt)(?:/|$))",
    re.IGNORECASE,
)
FORBIDDEN_CLINICAL_KEYS = {
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
FORBIDDEN_CLINICAL_STATUSES = {
    "MET",
    "NOT_MET",
    "NOT_EVALUATED",
    "NOT_APPLICABLE",
    "CONFLICTING",
    "NEEDS_REVIEW",
    "ABSTAIN",
}


class ProbeError(RuntimeError):
    """A fail-closed probe contract error."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


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
            if normalized in FORBIDDEN_CLINICAL_KEYS:
                raise ProbeError("CLINICAL_PAYLOAD_LEAKED")
            reject_clinical_payload(nested)
    elif isinstance(value, list):
        for nested in value:
            reject_clinical_payload(nested)
    elif isinstance(value, str) and value in FORBIDDEN_CLINICAL_STATUSES:
        raise ProbeError("CLINICAL_PAYLOAD_LEAKED")


def verify_connector(path: Path) -> None:
    if not path.is_file() or path.stat().st_size != GCS_CONNECTOR_BYTES:
        raise ProbeError("GCS_CONNECTOR_IDENTITY_MISMATCH")
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != GCS_CONNECTOR_SHA256:
        raise ProbeError("GCS_CONNECTOR_IDENTITY_MISMATCH")


def read_json_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ProbeError("JSON_OBJECT_REQUIRED")
    return value


def resources_by_id(profile: dict[str, Any]) -> dict[str, dict[str, Any]]:
    resources = profile.get("resources")
    if not isinstance(resources, list):
        raise ProbeError("PROFILE_RESOURCES_REQUIRED")
    result: dict[str, dict[str, Any]] = {}
    for resource in resources:
        if not isinstance(resource, dict) or not isinstance(
            resource.get("resourceId"), str
        ):
            raise ProbeError("PROFILE_RESOURCE_INVALID")
        resource_id = resource["resourceId"]
        if resource_id in result:
            raise ProbeError("PROFILE_RESOURCE_ID_DUPLICATED")
        result[resource_id] = resource
    return result


def verify_resource_identity(resource: dict[str, Any]) -> dict[str, Any]:
    resource_id = resource.get("resourceId")
    identity = resource.get("identity")
    fingerprint = resource.get("identityFingerprintSha256")
    if (
        resource_id not in OFFICIAL_TABLE_URIS
        or not isinstance(identity, dict)
        or not isinstance(fingerprint, str)
        or sha256_json(identity) != fingerprint
        or identity.get("tableUri") != OFFICIAL_TABLE_URIS[resource_id]
    ):
        raise ProbeError("ALL_SITES_RESOURCE_IDENTITY_MISMATCH")
    return identity


def to_builtin(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, (list, tuple)):
        return [to_builtin(item) for item in value]
    if hasattr(value, "items"):
        return {
            str(key): to_builtin(item)
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    raise ProbeError("UNSUPPORTED_HAIL_VALUE_TYPE")


def resolve_strata(
    strata_meta: Any,
    strata_sample_count: Any,
    selector: dict[str, str],
) -> dict[str, Any]:
    metadata = to_builtin(strata_meta)
    sample_counts = to_builtin(strata_sample_count)
    if not isinstance(metadata, list) or not isinstance(sample_counts, list):
        raise ProbeError("STRATA_GLOBALS_INVALID")
    if len(metadata) != len(sample_counts):
        raise ProbeError("STRATA_GLOBAL_LENGTH_MISMATCH")
    normalized_selector = {
        str(key): str(value) for key, value in sorted(selector.items())
    }
    mapping: list[dict[str, Any]] = []
    matches: list[int] = []
    for index, (entry, sample_count) in enumerate(zip(metadata, sample_counts)):
        if not isinstance(entry, dict):
            raise ProbeError("STRATA_META_ENTRY_INVALID")
        normalized_entry = {
            str(key): str(value) for key, value in sorted(entry.items())
        }
        if sample_count is not None and (
            not isinstance(sample_count, int) or isinstance(sample_count, bool)
        ):
            raise ProbeError("STRATA_SAMPLE_COUNT_INVALID")
        mapping.append(
            {
                "index": index,
                "metadata": normalized_entry,
                "sampleCount": sample_count,
            }
        )
        if normalized_entry == normalized_selector:
            matches.append(index)
    if len(matches) != 1:
        raise ProbeError("ADJ_STRATA_SELECTOR_NOT_UNIQUE")
    selected_index = matches[0]
    return {
        "selector": normalized_selector,
        "selectedIndex": selected_index,
        "selectedMetadata": mapping[selected_index]["metadata"],
        "selectedSampleCount": mapping[selected_index]["sampleCount"],
        "mapping": mapping,
    }


def axes(
    execution: str,
    query: str,
    record_count: int,
    query_fingerprint: str | None = None,
) -> dict[str, Any]:
    query_outcome: dict[str, Any] = {
        "status": query,
        "recordCount": record_count,
    }
    if query_fingerprint is not None:
        query_outcome["queryFingerprint"] = query_fingerprint
    return {
        "resourceExecution": {"status": execution},
        "resourceAvailability": {"status": "AVAILABLE"},
        "queryOutcome": query_outcome,
        "resourceApplicability": {"status": "APPLICABLE"},
    }


def query_locus(case: dict[str, Any]) -> dict[str, Any]:
    return {
        "assembly": case["assembly"],
        "canonicalContig": case["canonicalContig"],
        "resourceContig": case["resourceContig"],
        "position": case["position"],
    }


def resource_contig(canonical_contig: str) -> str:
    return f"chr{canonical_contig}"


def normalize_batch_request(request: dict[str, Any]) -> dict[str, Any]:
    verify_boundary(request)
    if request.get("requestSchemaVersion") != BATCH_REQUEST_SCHEMA_VERSION:
        raise ProbeError("BATCH_REQUEST_SCHEMA_VERSION_UNSUPPORTED")
    request_id = request.get("requestId")
    if not isinstance(request_id, str) or not request_id.strip():
        raise ProbeError("BATCH_REQUEST_ID_REQUIRED")
    if request.get("assembly") != "GRCh38":
        raise ProbeError("BATCH_ASSEMBLY_UNSUPPORTED")
    requested_loci = request.get("loci")
    if not isinstance(requested_loci, list) or not requested_loci:
        raise ProbeError("BATCH_LOCI_REQUIRED")

    loci: list[dict[str, Any]] = []
    locus_ids: set[str] = set()
    unique_keys: set[tuple[str, int]] = set()
    for value in requested_loci:
        if not isinstance(value, dict):
            raise ProbeError("BATCH_LOCUS_INVALID")
        locus_id = value.get("locusId")
        canonical_contig = value.get("canonicalContig")
        position = value.get("position")
        if not isinstance(locus_id, str) or not locus_id.strip():
            raise ProbeError("BATCH_LOCUS_ID_REQUIRED")
        if locus_id in locus_ids:
            raise ProbeError("BATCH_LOCUS_ID_DUPLICATED")
        if canonical_contig not in {
            *(str(number) for number in range(1, 23)),
            "X",
            "Y",
        }:
            raise ProbeError("BATCH_CANONICAL_CONTIG_UNSUPPORTED")
        if not isinstance(position, int) or isinstance(position, bool) or position < 1:
            raise ProbeError("BATCH_POSITION_INVALID")
        locus_ids.add(locus_id)
        unique_keys.add((canonical_contig, position))
        loci.append(
            {
                "locusId": locus_id,
                "assembly": "GRCh38",
                "canonicalContig": canonical_contig,
                "resourceContig": resource_contig(canonical_contig),
                "position": position,
            }
        )
    if len(unique_keys) > BATCH_MAX_UNIQUE_LOCI:
        raise ProbeError("BATCH_UNIQUE_LOCUS_LIMIT_EXCEEDED")
    return {
        "requestSchemaVersion": BATCH_REQUEST_SCHEMA_VERSION,
        "requestId": request_id,
        "assembly": "GRCh38",
        "loci": loci,
        "uniqueLoci": [
            {
                "assembly": "GRCh38",
                "canonicalContig": contig,
                "resourceContig": resource_contig(contig),
                "position": position,
            }
            for contig, position in sorted(
                unique_keys,
                key=lambda item: (
                    int(item[0]) if item[0].isdigit() else 23 + (item[0] == "Y"),
                    item[1],
                ),
            )
        ],
    }


def query_fingerprint(resource: dict[str, Any], locus: dict[str, Any]) -> str:
    return sha256_json(
        {
            "resourceId": resource["resourceId"],
            "identityFingerprintSha256": resource["identityFingerprintSha256"],
            "assembly": locus["assembly"],
            "canonicalContig": locus["canonicalContig"],
            "position": locus["position"],
        }
    )


def build_cases(
    resource: dict[str, Any],
    observed_rows: dict[tuple[str, int], dict[str, Any]],
    strata: dict[str, Any],
) -> list[dict[str, Any]]:
    selected_index = strata["selectedIndex"]
    cases: list[dict[str, Any]] = []
    for fixed_case, expected_outcome in (
        (HIT_LOCUS, "HIT"),
        (NO_RECORD_LOCUS, "NO_RECORD"),
    ):
        key = (fixed_case["resourceContig"], fixed_case["position"])
        row = observed_rows.get(key)
        actual_outcome = "HIT" if row is not None else "NO_RECORD"
        if actual_outcome != expected_outcome:
            raise ProbeError(f"FIXED_CASE_{fixed_case['caseId']}_DRIFTED")
        case = {
            "caseId": fixed_case["caseId"],
            "queryLocus": query_locus(fixed_case),
            "axes": axes(
                "SUCCEEDED",
                actual_outcome,
                1 if row is not None else 0,
            ),
        }
        if row is not None:
            allele_numbers = row.get("AN")
            if not isinstance(allele_numbers, list):
                raise ProbeError("ALLELE_NUMBER_ARRAY_REQUIRED")
            if selected_index >= len(allele_numbers):
                raise ProbeError("ADJ_STRATA_INDEX_OUT_OF_RANGE")
            adj_an = allele_numbers[selected_index]
            if not isinstance(adj_an, int) or isinstance(adj_an, bool) or adj_an < 0:
                raise ProbeError("ADJ_ALLELE_NUMBER_INVALID")
            measurement: dict[str, Any] = {
                "adjAlleleNumber": adj_an,
                "adjStrataIndex": selected_index,
                "alleleNumberArray": allele_numbers,
            }
            if resource.get("role") == "EXOME_LOCUS_ALLELE_NUMBER":
                region_flags: dict[str, Any] = {}
                for field in EXOME_REGION_FIELDS:
                    value = row.get(field)
                    if value is not None and not isinstance(value, bool):
                        raise ProbeError("EXOME_REGION_FLAG_INVALID")
                    region_flags[field] = value
                measurement["regionFlags"] = region_flags
            case["measurement"] = measurement
        cases.append(case)
    return cases


def build_batch_loci(
    resource: dict[str, Any],
    requested_loci: list[dict[str, Any]],
    observed_rows: dict[tuple[str, int], dict[str, Any]],
    strata: dict[str, Any],
) -> list[dict[str, Any]]:
    selected_index = strata["selectedIndex"]
    results: list[dict[str, Any]] = []
    for locus in requested_loci:
        key = (locus["resourceContig"], locus["position"])
        row = observed_rows.get(key)
        query_status = "HIT" if row is not None else "NO_RECORD"
        result = {
            "locusId": locus["locusId"],
            "queryLocus": query_locus(locus),
            "axes": axes(
                "SUCCEEDED",
                query_status,
                1 if row is not None else 0,
                query_fingerprint(resource, locus),
            ),
        }
        if row is not None:
            allele_numbers = row.get("AN")
            if not isinstance(allele_numbers, list):
                raise ProbeError("ALLELE_NUMBER_ARRAY_REQUIRED")
            if selected_index >= len(allele_numbers):
                raise ProbeError("ADJ_STRATA_INDEX_OUT_OF_RANGE")
            adj_an = allele_numbers[selected_index]
            if not isinstance(adj_an, int) or isinstance(adj_an, bool) or adj_an < 0:
                raise ProbeError("ADJ_ALLELE_NUMBER_INVALID")
            measurement: dict[str, Any] = {
                "adjAlleleNumber": adj_an,
                "adjStrataIndex": selected_index,
            }
            if resource.get("role") == "EXOME_LOCUS_ALLELE_NUMBER":
                region_flags: dict[str, Any] = {}
                for field in EXOME_REGION_FIELDS:
                    value = row.get(field)
                    if value is not None and not isinstance(value, bool):
                        raise ProbeError("EXOME_REGION_FLAG_INVALID")
                    region_flags[field] = value
                measurement["regionFlags"] = region_flags
            result["measurement"] = measurement
        results.append(result)
    return results


def row_to_builtin(row: Any, include_region_flags: bool) -> dict[str, Any]:
    locus = row.locus
    value: dict[str, Any] = {
        "resourceContig": str(locus.contig),
        "position": int(locus.position),
        "AN": [None if item is None else int(item) for item in row.AN],
    }
    if include_region_flags:
        for field in EXOME_REGION_FIELDS:
            value[field] = to_builtin(row[field])
    return value


def probe_resource(hl: Any, resource: dict[str, Any]) -> dict[str, Any]:
    identity = verify_resource_identity(resource)
    if resource.get("assembly") != "GRCh38":
        raise ProbeError("ALL_SITES_ASSEMBLY_UNSUPPORTED")
    table_uri = identity.get("tableUri")
    if not isinstance(table_uri, str):
        raise ProbeError("OFFICIAL_ALL_SITES_TABLE_URI_REQUIRED")
    selector = identity.get("strataResolution", {}).get("selector")
    if not isinstance(selector, dict) or not selector:
        raise ProbeError("STRATA_SELECTOR_REQUIRED")

    started_at = utc_now()
    table = hl.read_table(table_uri)
    globals_value = hl.eval(table.index_globals())
    strata = resolve_strata(
        globals_value.strata_meta,
        globals_value.strata_sample_count,
        selector,
    )
    intervals = []
    for fixed_case in (HIT_LOCUS, NO_RECORD_LOCUS):
        start = hl.Locus(
            fixed_case["resourceContig"],
            fixed_case["position"],
            reference_genome="GRCh38",
        )
        end = hl.Locus(
            fixed_case["resourceContig"],
            fixed_case["position"] + 1,
            reference_genome="GRCh38",
        )
        intervals.append(
            hl.Interval(start, end, includes_start=True, includes_end=False)
        )
    bounded = hl.filter_intervals(table, intervals, keep=True)
    partition_count = bounded.n_partitions()
    if not isinstance(partition_count, int) or not 1 <= partition_count <= 2:
        raise ProbeError("BOUNDED_QUERY_PARTITION_COUNT_UNEXPECTED")
    rows = bounded.take(3)
    if len(rows) > 2:
        raise ProbeError("BOUNDED_QUERY_RETURNED_UNEXPECTED_ROWS")
    include_region_flags = resource.get("role") == "EXOME_LOCUS_ALLELE_NUMBER"
    observed_rows: dict[tuple[str, int], dict[str, Any]] = {}
    for row in rows:
        built = row_to_builtin(row, include_region_flags)
        key = (built["resourceContig"], built["position"])
        if key in observed_rows:
            raise ProbeError("ALL_SITES_LOCUS_DUPLICATED")
        observed_rows[key] = built
    cases = build_cases(resource, observed_rows, strata)
    completed_at = utc_now()
    return {
        "resourceId": resource["resourceId"],
        "resourceVersion": resource.get("resourceVersion"),
        "assembly": resource.get("assembly"),
        "identityFingerprintSha256": resource.get("identityFingerprintSha256"),
        "officialTableUri": table_uri,
        "execution": {
            "startedAt": started_at,
            "completedAt": completed_at,
            "queryMode": "HAIL_FILTER_INTERVALS_FIXED_PUBLIC_LOCI",
            "maximumRowsCollected": 3,
            "partitionCountPlanned": partition_count,
        },
        "strata": strata,
        "cases": cases,
    }


def probe_resource_batch(
    hl: Any,
    resource: dict[str, Any],
    request: dict[str, Any],
) -> dict[str, Any]:
    identity = verify_resource_identity(resource)
    if resource.get("assembly") != request["assembly"]:
        raise ProbeError("ALL_SITES_ASSEMBLY_UNSUPPORTED")
    table_uri = identity.get("tableUri")
    if not isinstance(table_uri, str):
        raise ProbeError("OFFICIAL_ALL_SITES_TABLE_URI_REQUIRED")
    selector = identity.get("strataResolution", {}).get("selector")
    if not isinstance(selector, dict) or not selector:
        raise ProbeError("STRATA_SELECTOR_REQUIRED")

    started_at = utc_now()
    table = hl.read_table(table_uri)
    globals_value = hl.eval(table.index_globals())
    strata = resolve_strata(
        globals_value.strata_meta,
        globals_value.strata_sample_count,
        selector,
    )
    intervals = []
    for locus in request["uniqueLoci"]:
        start = hl.Locus(
            locus["resourceContig"],
            locus["position"],
            reference_genome="GRCh38",
        )
        end = hl.Locus(
            locus["resourceContig"],
            locus["position"] + 1,
            reference_genome="GRCh38",
        )
        intervals.append(
            hl.Interval(start, end, includes_start=True, includes_end=False)
        )
    bounded = hl.filter_intervals(table, intervals, keep=True)
    partition_count = bounded.n_partitions()
    if not isinstance(partition_count, int) or partition_count < 0:
        raise ProbeError("BOUNDED_QUERY_PARTITION_COUNT_UNEXPECTED")
    maximum_rows = len(request["uniqueLoci"]) + 1
    rows = bounded.take(maximum_rows)
    if len(rows) >= maximum_rows:
        raise ProbeError("BOUNDED_QUERY_RETURNED_UNEXPECTED_ROWS")
    include_region_flags = resource.get("role") == "EXOME_LOCUS_ALLELE_NUMBER"
    observed_rows: dict[tuple[str, int], dict[str, Any]] = {}
    for row in rows:
        built = row_to_builtin(row, include_region_flags)
        key = (built["resourceContig"], built["position"])
        if key in observed_rows:
            raise ProbeError("ALL_SITES_LOCUS_DUPLICATED")
        observed_rows[key] = built
    locus_results = build_batch_loci(
        resource,
        request["loci"],
        observed_rows,
        strata,
    )
    completed_at = utc_now()
    return {
        "resourceId": resource["resourceId"],
        "resourceVersion": resource.get("resourceVersion"),
        "assembly": resource.get("assembly"),
        "identityFingerprintSha256": resource.get("identityFingerprintSha256"),
        "officialTableUri": table_uri,
        "execution": {
            "startedAt": started_at,
            "completedAt": completed_at,
            "queryMode": "HAIL_FILTER_INTERVALS_BATCH_LOCI",
            "maximumRowsCollected": maximum_rows,
            "partitionCountPlanned": partition_count,
        },
        "strata": {
            "selector": strata["selector"],
            "selectedIndex": strata["selectedIndex"],
            "selectedMetadata": strata["selectedMetadata"],
            "selectedSampleCount": strata["selectedSampleCount"],
        },
        "loci": locus_results,
    }


def java_version() -> str:
    completed = subprocess.run(
        ["java", "-version"],
        check=True,
        capture_output=True,
        text=True,
    )
    output = (completed.stderr or completed.stdout).splitlines()
    return output[0].strip() if output else "UNKNOWN"


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=path.parent,
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


def verify_boundary(result: dict[str, Any]) -> None:
    reject_clinical_payload(result)
    serialized = canonical_json(result)
    if LOCAL_PATH_PATTERN.search(serialized):
        raise ProbeError("MACHINE_PATH_LEAKED")


def run_hail_session(
    hl: Any,
    temporary_root: Path,
    gcs_connector_jar: Path,
    operation: Any,
) -> Any:
    hl.init(
        master="local[1]",
        quiet=True,
        log=str(temporary_root / "hail.log"),
        tmp_dir=str(temporary_root / "remote"),
        local_tmpdir=str(temporary_root / "local"),
        spark_conf={
            "spark.driver.memory": "2g",
            "spark.default.parallelism": "1",
            "spark.sql.shuffle.partitions": "1",
            "spark.jars": gcs_connector_jar.as_uri(),
            "spark.driver.extraClassPath": str(gcs_connector_jar),
            "spark.executor.extraClassPath": str(gcs_connector_jar),
            "spark.hadoop.fs.gs.impl": (
                "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem"
            ),
            "spark.hadoop.fs.AbstractFileSystem.gs.impl": (
                "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFS"
            ),
            "spark.hadoop.fs.gs.auth.service.account.enable": "false",
            "spark.hadoop.fs.gs.auth.null.enable": "true",
            "spark.hadoop.google.cloud.auth.service.account.enable": "false",
            "spark.hadoop.google.cloud.auth.null.enable": "true",
        },
    )
    try:
        return operation()
    finally:
        hl.stop()


def run_probe(
    profile_path: Path,
    output: Path,
    gcs_connector_jar: Path,
) -> dict[str, Any]:
    profile = read_json_object(profile_path)
    resources = resources_by_id(profile)
    missing = [resource_id for resource_id in RESOURCE_IDS if resource_id not in resources]
    if missing:
        raise ProbeError("PROFILE_REQUIRED_RESOURCE_MISSING")
    verify_connector(gcs_connector_jar)

    import hail as hl
    import pyspark

    temporary_root = Path(tempfile.mkdtemp(prefix="varvis-e3-p0r-hail-"))
    os.environ.setdefault("SPARK_LOCAL_IP", "127.0.0.1")
    started_at = utc_now()
    try:
        resource_results = run_hail_session(
            hl,
            temporary_root,
            gcs_connector_jar,
            lambda: [
                probe_resource(hl, resources[resource_id])
                for resource_id in RESOURCE_IDS
            ],
        )
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
            "tool": {
                "hailVersion": hl.__version__,
                "pysparkVersion": pyspark.__version__,
                "pythonVersion": platform.python_version(),
                "javaVersion": java_version(),
                "backend": "LOCAL_SPARK_SINGLE_CORE",
                "gcsConnector": {
                    "version": GCS_CONNECTOR_VERSION,
                    "byteLength": GCS_CONNECTOR_BYTES,
                    "sha256": GCS_CONNECTOR_SHA256,
                    "authentication": "UNAUTHENTICATED_PUBLIC_READ_ONLY",
                },
            },
            "execution": {
                "startedAt": started_at,
                "completedAt": completed_at,
            },
            "resources": resource_results,
            "boundary": {
                "fixedPublicLociOnly": True,
                "indexedIntervalsOnly": True,
                "fullHailTableDownloaded": False,
                "derivedResourceCacheCreated": False,
                "runtimeIntegrationRun": False,
                "patientDataUsed": False,
                "clinicalRuleEvaluationRun": False,
                "machinePathsIncluded": False,
            },
        }
        verify_boundary(result)
        atomic_write_json(output, result)
        return result
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)


def run_batch(
    profile_path: Path,
    request_path: Path,
    output: Path,
    gcs_connector_jar: Path,
) -> dict[str, Any]:
    profile = read_json_object(profile_path)
    resources = resources_by_id(profile)
    missing = [resource_id for resource_id in RESOURCE_IDS if resource_id not in resources]
    if missing:
        raise ProbeError("PROFILE_REQUIRED_RESOURCE_MISSING")
    request = normalize_batch_request(read_json_object(request_path))
    verify_connector(gcs_connector_jar)

    import hail as hl
    import pyspark

    temporary_root = Path(tempfile.mkdtemp(prefix="varvis-e3-p1b-hail-"))
    os.environ.setdefault("SPARK_LOCAL_IP", "127.0.0.1")
    started_at = utc_now()
    try:
        resource_results = run_hail_session(
            hl,
            temporary_root,
            gcs_connector_jar,
            lambda: [
                probe_resource_batch(hl, resources[resource_id], request)
                for resource_id in RESOURCE_IDS
            ],
        )
        completed_at = utc_now()
        result = {
            "resultSchemaVersion": BATCH_RESULT_SCHEMA_VERSION,
            "requestId": request["requestId"],
            "status": "SUCCEEDED",
            "observedAt": completed_at,
            "profile": {
                "profileId": profile.get("profileId"),
                "profileVersion": profile.get("profileVersion"),
                "profileSha256": sha256_json(profile),
            },
            "tool": {
                "hailVersion": hl.__version__,
                "pysparkVersion": pyspark.__version__,
                "pythonVersion": platform.python_version(),
                "javaVersion": java_version(),
                "backend": "LOCAL_SPARK_SINGLE_CORE",
                "gcsConnector": {
                    "version": GCS_CONNECTOR_VERSION,
                    "byteLength": GCS_CONNECTOR_BYTES,
                    "sha256": GCS_CONNECTOR_SHA256,
                    "authentication": "UNAUTHENTICATED_PUBLIC_READ_ONLY",
                },
            },
            "execution": {
                "startedAt": started_at,
                "completedAt": completed_at,
                "queryMode": "HAIL_FILTER_INTERVALS_BATCH_LOCI",
                "requestedLocusCount": len(request["loci"]),
                "uniqueLocusCount": len(request["uniqueLoci"]),
                "hailInitializationCount": 1,
                "resourceQueryCount": len(RESOURCE_IDS),
            },
            "resources": resource_results,
            "boundary": {
                "indexedIntervalsOnly": True,
                "fullHailTableDownloaded": False,
                "derivedResourceCacheCreated": False,
                "patientDataUsed": False,
                "clinicalRuleEvaluationRun": False,
                "machinePathsIncluded": False,
            },
        }
        verify_boundary(result)
        atomic_write_json(output, result)
        return result
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a bounded gnomAD v4.1 all-sites AN Hail query."
    )
    parser.add_argument("--profile", type=Path)
    parser.add_argument("--batch-request", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--gcs-connector-jar", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    repository_root = Path(__file__).resolve().parents[2]
    profile_path = (
        args.profile.resolve()
        if args.profile is not None
        else repository_root / PROFILE_RELATIVE_PATH
    )
    try:
        if args.batch_request is None:
            result = run_probe(
                profile_path,
                args.output.resolve(),
                args.gcs_connector_jar.resolve(),
            )
        else:
            result = run_batch(
                profile_path,
                args.batch_request.resolve(),
                args.output.resolve(),
                args.gcs_connector_jar.resolve(),
            )
    except Exception as exc:
        reason = (
            str(exc)
            if isinstance(exc, ProbeError)
            else f"{type(exc).__name__}: {exc}"
        )
        print(f"gnomAD all-sites Hail probe failed: {reason}", file=sys.stderr)
        return 1
    print(result["status"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
