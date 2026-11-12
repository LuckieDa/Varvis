#!/usr/bin/env python3
"""Run the bounded E3-C2R SpliceAI 1.3.1 raw-score compatibility probe.

The probe invokes the installed official CLI with ``-D 4999 -M 0`` for one
public fixed point and one two-variant tiny batch.  It records resource
identity, exact-allele DS/DP output, timings and repeatability.  It does not
evaluate ACMG criteria or read patient data.

The same entry point also exposes a small machine-readable batch mode for the
E3-C2 Java boundary.  A batch request is converted to one VCF, invokes the
official CLI exactly once, and returns path-free structured HIT/NO_RECORD or
FAILED/TIMEOUT results.  It still does not evaluate an ACMG criterion.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any


DISTANCE_BP = 4999
MASK_VALUE = 0
FIXED_VARIANT = {
    "caseId": "PUBLIC_FIXED_POINT",
    "origin": "PUBLIC_FIXED_POINT",
    "assembly": "GRCh38",
    "contig": "1",
    "position": 925775,
    "reference": "G",
    "alternate": "T",
}
TINY_BATCH_VARIANTS = (
    FIXED_VARIANT,
    {
        "caseId": "SYNTHETIC_INSERTION",
        "origin": "SYNTHETIC",
        "assembly": "GRCh38",
        "contig": "1",
        "position": 925775,
        "reference": "G",
        "alternate": "GT",
    },
)
EXPECTED_PACKAGE_VERSION = "1.3.1"
EXPECTED_WHEEL_SHA256 = (
    "63c633b8de6803d4ffb613e6ef62f1a896fec9fc13245c0f000ff7adea279776"
)
EXPECTED_FASTA_BYTES = 3151425851
EXPECTED_FASTA_INDEX_SHA256 = (
    "0998f61682f4041b11f0d156e1db6dae3e4c743e26643a3f45ea7faea70cb604"
)
EXPECTED_FASTA_CONTIGS = 194
BATCH_REQUEST_SCHEMA = "spliceai-raw-batch-request-v1"
BATCH_RESULT_SCHEMA = "spliceai-raw-batch-result-v1"
SAFE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")
LOCAL_PATH_PATTERN = re.compile(
    r"(?:(?<![A-Za-z0-9])[A-Za-z]:[\\/]|\\\\|file:(?://)?|"
    r"(?<![:A-Za-z0-9])/(?:home|root|Users|tmp|var|opt|srv|mnt)(?:/|$))",
    re.IGNORECASE,
)


class ProbeError(RuntimeError):
    """The bounded compatibility probe failed."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace(
        "+00:00", "Z"
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def load_json_object(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ProbeError("PROFILE_OBJECT_REQUIRED")
    return value


def package_root() -> Path:
    spec = importlib.util.find_spec("spliceai")
    if spec is None or not spec.submodule_search_locations:
        raise ProbeError("SPLICEAI_PACKAGE_NOT_FOUND")
    return Path(next(iter(spec.submodule_search_locations))).resolve()


def package_inventory(profile: dict[str, Any], wheel_path: Path) -> dict[str, Any]:
    version = importlib.metadata.version("spliceai")
    if version != EXPECTED_PACKAGE_VERSION:
        raise ProbeError("SPLICEAI_VERSION_MISMATCH")
    wheel_sha256 = sha256_file(wheel_path)
    if wheel_sha256 != EXPECTED_WHEEL_SHA256:
        raise ProbeError("SPLICEAI_WHEEL_SHA256_MISMATCH")

    resource = profile.get("resource")
    if not isinstance(resource, dict) or not isinstance(resource.get("identity"), dict):
        raise ProbeError("PROFILE_RESOURCE_IDENTITY_REQUIRED")
    identity = resource["identity"]
    expected_fingerprint = resource.get("identityFingerprintSha256")
    if canonical_sha256(identity) != expected_fingerprint:
        raise ProbeError("PROFILE_RESOURCE_IDENTITY_FINGERPRINT_MISMATCH")

    root = package_root()
    assets: list[dict[str, Any]] = []
    for declared in identity["models"] + [identity["geneAnnotation"]]:
        logical_path = declared["logicalPath"]
        relative_path = logical_path.removeprefix("spliceai/")
        path = root / relative_path
        observed = {
            "logicalPath": logical_path,
            "byteLength": path.stat().st_size,
            "sha256": sha256_file(path),
        }
        if observed != {
            "logicalPath": logical_path,
            "byteLength": declared["byteLength"],
            "sha256": declared["sha256"],
        }:
            raise ProbeError("SPLICEAI_PACKAGE_ASSET_MISMATCH")
        assets.append(observed)

    cli_sources: list[dict[str, Any]] = []
    for declared in identity["cliSources"]:
        path = root / declared["logicalPath"].removeprefix("spliceai/")
        observed = {
            "logicalPath": declared["logicalPath"],
            "byteLength": path.stat().st_size,
            "sha256": sha256_file(path),
        }
        if observed != declared:
            raise ProbeError("SPLICEAI_CLI_SOURCE_MISMATCH")
        cli_sources.append(observed)

    main_source = (root / "__main__.py").read_text(encoding="utf-8")
    utils_source = (root / "utils.py").read_text(encoding="utf-8")
    source_semantics_verified = all(
        marker in main_source
        for marker in (
            "choices=range(0, 5000)",
            "choices=[0, 1]",
            "ann = Annotator(args.R, args.A)",
            "for record in vcf:",
        )
    ) and all(
        marker in utils_source
        for marker in (
            "cov = 2*dist_var+1",
            "mask_pa = np.logical_and",
            "*(1-mask_pa)",
            "{:.2f}|{:.2f}|{:.2f}|{:.2f}",
        )
    )
    if not source_semantics_verified:
        raise ProbeError("SPLICEAI_CLI_SEMANTICS_NOT_VERIFIED")

    return {
        "packageVersion": version,
        "installArtifact": {
            "fileName": wheel_path.name,
            "byteLength": wheel_path.stat().st_size,
            "sha256": wheel_sha256,
        },
        "assets": assets,
        "cliSources": cli_sources,
        "sourceSemanticsVerified": True,
        "resourceIdentityFingerprintSha256": expected_fingerprint,
    }


def reference_inventory(reference: Path) -> dict[str, Any]:
    index = Path(str(reference) + ".fai")
    if not reference.is_file() or not index.is_file():
        raise ProbeError("REFERENCE_OR_INDEX_MISSING")
    contig_count = sum(1 for line in index.read_text(encoding="utf-8").splitlines() if line)
    observed = {
        "logicalName": "vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa",
        "assembly": "GRCh38",
        "byteLength": reference.stat().st_size,
        "indexLogicalName": "vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa.fai",
        "indexSha256": sha256_file(index),
        "indexedContigCount": contig_count,
        "identityFingerprintSha256": (
            "43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed"
        ),
        "fullFastaByteSha256Claimed": False,
    }
    if (
        observed["byteLength"] != EXPECTED_FASTA_BYTES
        or observed["indexSha256"] != EXPECTED_FASTA_INDEX_SHA256
        or observed["indexedContigCount"] != EXPECTED_FASTA_CONTIGS
    ):
        raise ProbeError("REFERENCE_IDENTITY_MISMATCH")
    return observed


def write_vcf(path: Path, variants: tuple[dict[str, Any], ...]) -> None:
    lines = [
        "##fileformat=VCFv4.2",
    ]
    for contig in dict.fromkeys(str(variant["contig"]) for variant in variants):
        lines.append(f"##contig=<ID={contig}>")
    lines.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO")
    for variant in variants:
        lines.append(
            "\t".join(
                (
                    str(variant["contig"]),
                    str(variant["position"]),
                    str(variant["caseId"]),
                    str(variant["reference"]),
                    str(variant["alternate"]),
                    ".",
                    "PASS",
                    ".",
                )
            )
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def load_batch_request(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        request = json.load(handle)
    if not isinstance(request, dict):
        raise ProbeError("BATCH_REQUEST_OBJECT_REQUIRED")
    if request.get("requestSchemaVersion") != BATCH_REQUEST_SCHEMA:
        raise ProbeError("BATCH_REQUEST_SCHEMA_INVALID")
    request_id = request.get("requestId")
    if not isinstance(request_id, str) or not SAFE_ID_PATTERN.fullmatch(request_id):
        raise ProbeError("BATCH_REQUEST_ID_INVALID")
    if request.get("assembly") != "GRCh38":
        raise ProbeError("BATCH_ASSEMBLY_UNSUPPORTED")
    if request.get("distanceBp") != DISTANCE_BP:
        raise ProbeError("BATCH_DISTANCE_INVALID")
    if request.get("mask") != MASK_VALUE:
        raise ProbeError("BATCH_MASK_INVALID")
    variants = request.get("variants")
    if not isinstance(variants, list) or not variants:
        raise ProbeError("BATCH_VARIANTS_REQUIRED")

    seen_ids: set[str] = set()
    seen_alleles: set[tuple[str, int, str, str]] = set()
    for variant in variants:
        if not isinstance(variant, dict):
            raise ProbeError("BATCH_VARIANT_OBJECT_REQUIRED")
        query_id = variant.get("queryId")
        if not isinstance(query_id, str) or not SAFE_ID_PATTERN.fullmatch(query_id):
            raise ProbeError("BATCH_QUERY_ID_INVALID")
        if query_id in seen_ids:
            raise ProbeError("BATCH_QUERY_ID_DUPLICATED")
        seen_ids.add(query_id)
        allele_value = variant.get("allele")
        if not isinstance(allele_value, dict):
            raise ProbeError("BATCH_ALLELE_OBJECT_REQUIRED")
        if allele_value.get("assembly") != "GRCh38":
            raise ProbeError("BATCH_ALLELE_ASSEMBLY_UNSUPPORTED")
        contig = allele_value.get("contig")
        position = allele_value.get("position")
        reference = allele_value.get("reference")
        alternate = allele_value.get("alternate")
        gene_symbol = variant.get("geneSymbol")
        selected_transcript = variant.get("selectedTranscriptId")
        if not isinstance(contig, str) or not contig or any(
            marker in contig for marker in ("\t", "\n", "\r", ",")
        ):
            raise ProbeError("BATCH_CONTIG_INVALID")
        if not isinstance(position, int) or isinstance(position, bool) or position < 1:
            raise ProbeError("BATCH_POSITION_INVALID")
        if not isinstance(reference, str) or not reference or any(
            marker in reference for marker in ("\t", "\n", "\r", ",")
        ):
            raise ProbeError("BATCH_REFERENCE_INVALID")
        if not isinstance(alternate, str) or not alternate or any(
            marker in alternate for marker in ("\t", "\n", "\r", ",")
        ):
            raise ProbeError("BATCH_ALTERNATE_INVALID")
        if not isinstance(gene_symbol, str) or not gene_symbol.strip():
            raise ProbeError("BATCH_GENE_SYMBOL_REQUIRED")
        if not isinstance(selected_transcript, str) or not selected_transcript.strip():
            raise ProbeError("BATCH_SELECTED_TRANSCRIPT_REQUIRED")
        allele = (contig, position, reference, alternate)
        if allele in seen_alleles:
            raise ProbeError("BATCH_EXACT_ALLELE_DUPLICATED")
        seen_alleles.add(allele)
    return request


def run_cli(
    input_vcf: Path, output_vcf: Path, reference: Path, timeout_seconds: int
) -> float:
    command = [
        sys.executable,
        "-m",
        "spliceai",
        "-I",
        str(input_vcf),
        "-O",
        str(output_vcf),
        "-R",
        str(reference),
        "-A",
        "grch38",
        "-D",
        str(DISTANCE_BP),
        "-M",
        str(MASK_VALUE),
    ]
    environment = os.environ.copy()
    environment.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    started = time.perf_counter()
    completed = subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout_seconds,
        env=environment,
    )
    elapsed = time.perf_counter() - started
    if completed.returncode != 0:
        diagnostic = (completed.stderr or completed.stdout)[-2000:]
        raise ProbeError(f"SPLICEAI_CLI_FAILED: {diagnostic}")
    if not output_vcf.is_file():
        raise ProbeError("SPLICEAI_OUTPUT_MISSING")
    return elapsed


def parse_annotation(raw: str) -> dict[str, Any]:
    fields = raw.split("|")
    if len(fields) != 10:
        raise ProbeError("SPLICEAI_INFO_FIELD_COUNT_INVALID")
    score_names = ("DS_AG", "DS_AL", "DS_DG", "DS_DL")
    position_names = ("DP_AG", "DP_AL", "DP_DG", "DP_DL")
    scores = dict(zip(score_names, fields[2:6], strict=True))
    positions = dict(zip(position_names, fields[6:10], strict=True))
    if any(value == "." for value in (*scores.values(), *positions.values())):
        raise ProbeError("SPLICEAI_SCORE_MISSING")
    if any(abs(int(value)) > DISTANCE_BP for value in positions.values()):
        raise ProbeError("SPLICEAI_DELTA_POSITION_OUT_OF_RANGE")
    maximum_raw = max(scores.values(), key=Decimal)
    return {
        "allele": fields[0],
        "geneSymbol": fields[1],
        "deltaScores": scores,
        "deltaPositions": {name: int(value) for name, value in positions.items()},
        "maximumRawScore": maximum_raw,
    }


def spliceai_info(info: str) -> list[dict[str, Any]]:
    for item in info.split(";"):
        if item.startswith("SpliceAI="):
            return [parse_annotation(value) for value in item[9:].split(",")]
    raise ProbeError("SPLICEAI_INFO_MISSING")


def parse_output(path: Path) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        annotations = spliceai_info(fields[7])
        if any(annotation["allele"] != fields[4] for annotation in annotations):
            raise ProbeError("SPLICEAI_EXACT_ALLELE_MISMATCH")
        results.append(
            {
                "caseId": fields[2],
                "allele": {
                    "assembly": "GRCh38",
                    "contig": fields[0],
                    "position": int(fields[1]),
                    "reference": fields[3],
                    "alternate": fields[4],
                },
                "exactAlleleMatch": True,
                "annotations": annotations,
                "maximumRawScore": max(
                    (annotation["maximumRawScore"] for annotation in annotations),
                    key=Decimal,
                ),
            }
        )
    return results


def _batch_allele(variant: dict[str, Any]) -> dict[str, Any]:
    return dict(variant["allele"])


def _batch_key(value: dict[str, Any]) -> tuple[str, int, str, str]:
    allele = value["allele"] if "allele" in value else value
    return (
        str(allele["contig"]),
        int(allele["position"]),
        str(allele["reference"]),
        str(allele["alternate"]),
    )


def parse_batch_output(
    path: Path,
) -> dict[tuple[str, int, str, str], list[dict[str, Any]]]:
    """Parse exact-allele output without treating absent SpliceAI INFO as failure."""

    records: dict[tuple[str, int, str, str], list[dict[str, Any]]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 8:
            raise ProbeError("SPLICEAI_OUTPUT_RECORD_INVALID")
        for alternate in fields[4].split(","):
            key = (fields[0], int(fields[1]), fields[3], alternate)
            annotations: list[dict[str, Any]] = []
            for item in fields[7].split(";"):
                if item.startswith("SpliceAI="):
                    annotations = [
                        parse_annotation(value) for value in item[9:].split(",")
                    ]
                    break
            exact = [
                annotation
                for annotation in annotations
                if annotation["allele"] == alternate
            ]
            records.setdefault(key, []).extend(exact)
    return records


def _batch_success_result(
    variant: dict[str, Any],
    records: dict[tuple[str, int, str, str], list[dict[str, Any]]],
) -> dict[str, Any]:
    annotations = records.get(_batch_key(variant), [])
    if not annotations:
        return {
            "queryId": variant["queryId"],
            "allele": _batch_allele(variant),
            "status": "NO_RECORD",
            "exactAlleleMatch": False,
            "annotations": [],
        }

    annotated: list[dict[str, Any]] = []
    for annotation in annotations:
        annotated.append(
            {
                "geneSymbol": annotation["geneSymbol"],
                "deltaScores": dict(annotation["deltaScores"]),
                "deltaPositions": dict(annotation["deltaPositions"]),
                "maximumRawScore": annotation["maximumRawScore"],
            }
        )
    return {
        "queryId": variant["queryId"],
        "allele": _batch_allele(variant),
        "status": "HIT",
        "exactAlleleMatch": True,
        "annotations": annotated,
    }


def run_batch_request(
    request: dict[str, Any],
    reference: Path,
    timeout_seconds: int,
    cli_runner: Any = run_cli,
) -> dict[str, Any]:
    """Execute one validated request with exactly one official CLI invocation."""

    variants = request["variants"]
    cli_variants = tuple(
        {
            "caseId": variant["queryId"],
            "contig": variant["allele"]["contig"],
            "position": variant["allele"]["position"],
            "reference": variant["allele"]["reference"],
            "alternate": variant["allele"]["alternate"],
        }
        for variant in variants
    )
    execution_status = "SUCCEEDED"
    reason_code: str | None = None
    results: list[dict[str, Any]]
    with tempfile.TemporaryDirectory(prefix="varvis-e3-c2-batch-") as temp_name:
        temp = Path(temp_name)
        input_vcf = temp / "batch.vcf"
        output_vcf = temp / "batch.out.vcf"
        write_vcf(input_vcf, cli_variants)
        try:
            cli_runner(input_vcf, output_vcf, reference, timeout_seconds)
            records = parse_batch_output(output_vcf)
            results = [
                _batch_success_result(variant, records) for variant in variants
            ]
        except subprocess.TimeoutExpired:
            execution_status = "TIMEOUT"
            reason_code = "SPLICEAI_CLI_TIMEOUT"
            results = []
        except (OSError, ProbeError, ValueError):
            execution_status = "FAILED"
            reason_code = "SPLICEAI_CLI_FAILED"
            results = []

    payload: dict[str, Any] = {
        "resultSchemaVersion": BATCH_RESULT_SCHEMA,
        "requestId": request["requestId"],
        "status": execution_status,
        "results": results,
    }
    if reason_code is not None:
        payload["reasonCode"] = reason_code
    ensure_path_free(payload)
    return payload


def file_snapshot(path: Path) -> tuple[int, int]:
    stat = path.stat()
    return stat.st_size, stat.st_mtime_ns


def query_masked_fixed_point(path: Path) -> tuple[list[dict[str, Any]], bool]:
    import pysam

    index = Path(str(path) + ".tbi")
    before = (file_snapshot(path), file_snapshot(index))
    matches: list[dict[str, Any]] = []
    with pysam.TabixFile(str(path)) as table:
        contig = "1" if "1" in table.contigs else "chr1"
        for line in table.fetch(contig, FIXED_VARIANT["position"] - 1, FIXED_VARIANT["position"]):
            fields = line.split("\t")
            if (
                int(fields[1]) == FIXED_VARIANT["position"]
                and fields[3] == FIXED_VARIANT["reference"]
                and FIXED_VARIANT["alternate"] in fields[4].split(",")
            ):
                matches.extend(spliceai_info(fields[7]))
    after = (file_snapshot(path), file_snapshot(index))
    if before != after:
        raise ProbeError("LEGACY_MASKED_RESOURCE_CHANGED")
    return matches, before == after


def runtime_inventory() -> dict[str, Any]:
    packages = (
        "tensorflow-cpu",
        "keras",
        "numpy",
        "pandas",
        "h5py",
        "pysam",
        "pyfaidx",
        "setuptools",
    )
    return {
        "pythonVersion": platform.python_version(),
        "platform": "WSL2_LINUX_X86_64",
        "machine": platform.machine(),
        "packages": {
            name: importlib.metadata.version(name) for name in packages
        },
    }


def ensure_path_free(payload: dict[str, Any]) -> None:
    if LOCAL_PATH_PATTERN.search(json.dumps(payload, ensure_ascii=False)):
        raise ProbeError("LOCAL_PATH_LEAKED")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-request", type=Path)
    parser.add_argument("--profile", type=Path)
    parser.add_argument("--reference-fasta", type=Path, required=True)
    parser.add_argument("--wheel-artifact", type=Path)
    parser.add_argument("--masked-snv-vcf", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    args = parser.parse_args()
    if args.batch_request is None and (
        args.profile is None or args.wheel_artifact is None
    ):
        parser.error("probe mode requires --profile and --wheel-artifact")
    return args


def main() -> int:
    args = parse_args()
    if args.batch_request is not None:
        request = load_batch_request(args.batch_request)
        payload = run_batch_request(
            request, args.reference_fasta, args.timeout_seconds
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps({"status": payload["status"]}, sort_keys=True))
        return 0

    profile = load_json_object(args.profile)
    package = package_inventory(profile, args.wheel_artifact)
    reference = reference_inventory(args.reference_fasta)

    with tempfile.TemporaryDirectory(prefix="varvis-e3-c2r-") as temp_name:
        temp = Path(temp_name)

        single_input = temp / "single.vcf"
        single_output = temp / "single.out.vcf"
        write_vcf(single_input, (FIXED_VARIANT,))
        single_seconds = run_cli(
            single_input, single_output, args.reference_fasta, args.timeout_seconds
        )
        single_results = parse_output(single_output)

        batch_input = temp / "tiny-batch.vcf"
        batch_output_1 = temp / "tiny-batch.run1.vcf"
        batch_output_2 = temp / "tiny-batch.run2.vcf"
        write_vcf(batch_input, TINY_BATCH_VARIANTS)
        batch_seconds_1 = run_cli(
            batch_input, batch_output_1, args.reference_fasta, args.timeout_seconds
        )
        batch_seconds_2 = run_cli(
            batch_input, batch_output_2, args.reference_fasta, args.timeout_seconds
        )
        batch_results_1 = parse_output(batch_output_1)
        batch_results_2 = parse_output(batch_output_2)

    single_hash = canonical_sha256(single_results)
    batch_hash_1 = canonical_sha256(batch_results_1)
    batch_hash_2 = canonical_sha256(batch_results_2)
    repeatable = batch_hash_1 == batch_hash_2
    fixed_in_batch = next(
        result for result in batch_results_1 if result["caseId"] == "PUBLIC_FIXED_POINT"
    )
    single_batch_stable = single_results[0] == fixed_in_batch
    if not repeatable or not single_batch_stable:
        raise ProbeError("SPLICEAI_OUTPUT_NOT_REPEATABLE")

    masked_comparison: dict[str, Any] = {
        "performed": False,
        "scope": "TECHNICAL_ONLY_NOT_CLINICAL",
        "legacyResourceModified": False,
    }
    if args.masked_snv_vcf is not None:
        masked_annotations, unchanged = query_masked_fixed_point(args.masked_snv_vcf)
        raw_annotations = single_results[0]["annotations"]
        masked_comparison = {
            "performed": True,
            "scope": "TECHNICAL_ONLY_NOT_CLINICAL",
            "fixedAllele": single_results[0]["allele"],
            "rawUnmaskedAnnotations": raw_annotations,
            "legacyMaskedAnnotations": masked_annotations,
            "outputsIdentical": raw_annotations == masked_annotations,
            "legacyResourceModified": not unchanged,
        }

    payload: dict[str, Any] = {
        "smokeSchemaVersion": "spliceai-raw-resource-smoke-v1",
        "stage": "E3-C2R",
        "generatedAt": utc_now(),
        "status": "PASSED",
        "scope": {
            "inputData": "PUBLIC_AND_SYNTHETIC_FIXED_POINTS_ONLY",
            "patientDataUsed": False,
            "criterionEvaluationProduced": False,
            "clinicalClassificationProduced": False,
            "clinicalValidation": False,
        },
        "profileBinding": {
            "profileId": profile["profileId"],
            "profileVersion": profile["profileVersion"],
            "canonicalSha256": canonical_sha256(profile),
            "rawFileSha256": sha256_file(args.profile),
        },
        "runtime": runtime_inventory(),
        "resourceIdentity": {
            "resourceId": profile["resource"]["resourceId"],
            "package": package,
            "reference": reference,
        },
        "cliSemantics": {
            "assemblyAnnotation": "grch38",
            "distanceArgument": DISTANCE_BP,
            "maskArgument": MASK_VALUE,
            "rawUnmasked": True,
            "predictionWindow": {
                "upstreamBp": DISTANCE_BP,
                "downstreamBp": DISTANCE_BP,
                "inclusiveOutputPositionSpanBp": 2 * DISTANCE_BP + 1,
            },
            "scoreFields": ["DS_AG", "DS_AL", "DS_DG", "DS_DL"],
            "positionFields": ["DP_AG", "DP_AL", "DP_DG", "DP_DL"],
            "maximumScoreDefinition": "MAX_OF_FOUR_DS_DECIMAL_LEXEMES",
            "cliScorePrecision": "TWO_DECIMAL_OUTPUT",
        },
        "singleFixedPoint": {
            "processInvocations": 1,
            "wallSeconds": round(single_seconds, 3),
            "projectionSha256": single_hash,
            "results": single_results,
        },
        "tinyBatch": {
            "variantCount": len(TINY_BATCH_VARIANTS),
            "processInvocationsPerRun": 1,
            "modelInitializationEvidence": (
                "OFFICIAL_CLI_CREATES_ONE_ANNOTATOR_BEFORE_ITERATING_THE_VCF"
            ),
            "modelInitializationInstrumentedCountClaimed": False,
            "run1WallSeconds": round(batch_seconds_1, 3),
            "run2WallSeconds": round(batch_seconds_2, 3),
            "run1ProjectionSha256": batch_hash_1,
            "run2ProjectionSha256": batch_hash_2,
            "repeatable": repeatable,
            "singleVsBatchFixedPointStable": single_batch_stable,
            "results": batch_results_1,
        },
        "legacyMaskedComparison": masked_comparison,
        "limitations": [
            "RESOURCE_COMPATIBILITY_PROBE_ONLY",
            "OFFICIAL_CLI_DS_VALUES_ARE_FORMATTED_TO_TWO_DECIMAL_PLACES",
            "NO_PP3_BP4_EVALUATOR",
            "NO_ALPHA_MISSENSE_SPLICEAI_SELECTOR",
            "NO_DEFAULT_PATIENT_FLOW",
            "NOT_CLINICAL_VALIDATION",
        ],
    }
    ensure_path_free(payload)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"status": "PASSED", "outputSha256": sha256_file(args.output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
