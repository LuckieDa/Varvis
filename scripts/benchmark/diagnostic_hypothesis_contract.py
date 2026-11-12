#!/usr/bin/env python3
"""Module 2 truth-free diagnostic hypothesis contract utilities."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = ROOT / "docs" / "contracts" / "diagnostic-hypothesis-v1"
SCHEMA_ROOT = CONTRACT_ROOT / "schemas"

SCHEMAS = {
    "raw-engine-result-ref-v1": "raw-engine-result-ref-v1.schema.json",
    "normalized-engine-evidence-v1": "normalized-engine-evidence-v1.schema.json",
    "diagnostic-hypothesis-v1": "diagnostic-hypothesis-v1.schema.json",
    "diagnostic-hypothesis-batch-v1": "diagnostic-hypothesis-batch-v1.schema.json",
    "post-lock-hypothesis-evaluation-interface-v1": "post-lock-hypothesis-evaluation-interface-v1.schema.json",
}

RANK_ENDPOINTS = {
    "DISEASE_NATIVE_RANK",
    "GENE_NATIVE_RANK",
    "GENE_DERIVED_DENSE_RANK",
    "VARIANT_NATIVE_RANK",
}
ALLOWED_ENDPOINTS = {
    "EXOMISER": {
        "GENE_NATIVE_RANK",
        "VARIANT_NATIVE_RANK",
        "NOT_COMPARABLE",
        "UNAVAILABLE",
        "UNMAPPED",
        "NOT_APPLICABLE",
    },
    "LIRICAL": {
        "DISEASE_NATIVE_RANK",
        "GENE_DERIVED_DENSE_RANK",
        "NOT_COMPARABLE",
        "UNAVAILABLE",
        "UNMAPPED",
        "NOT_APPLICABLE",
    },
}
FORBIDDEN_RUNTIME_KEYS = {
    "truth",
    "truthgene",
    "truthvariant",
    "truthdisease",
    "expectedrank",
    "benchmarklabel",
    "hitatk",
    "misstaxonomy",
    "predictiontarget",
    "rescuelabel",
    "fusionscore",
    "fusionrank",
    "decisionrule",
    "decisionruleversion",
    "aireview",
    "clinicianreview",
    "overrideprovenance",
}


class ContractError(ValueError):
    pass


def _schema(name: str) -> dict[str, Any]:
    return json.loads((SCHEMA_ROOT / SCHEMAS[name]).read_text(encoding="utf-8"))


def _validate_schema(name: str, value: Any) -> None:
    errors = sorted(
        Draft202012Validator(
            _schema(name), format_checker=FormatChecker()
        ).iter_errors(value),
        key=lambda error: list(error.absolute_path),
    )
    if errors:
        summary = "; ".join(
            f"{'/'.join(map(str, error.absolute_path)) or '$'}: {error.message}"
            for error in errors
        )
        raise ContractError(f"{name} schema validation failed: {summary}")


def _normalized_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def _reject_forbidden_keys(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = _normalized_key(key)
            if normalized != "truthfree" and (
                normalized in FORBIDDEN_RUNTIME_KEYS or normalized.startswith("truth")
            ):
                raise ContractError(f"forbidden Module 2 runtime field at {path}.{key}")
            _reject_forbidden_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_forbidden_keys(child, f"{path}[{index}]")


def hypothesis_id(hypothesis: dict[str, Any]) -> str:
    payload = {
        "assembly": hypothesis["assembly"],
        "diseases": sorted(
            curie
            for disease in hypothesis["diseaseIdentities"]
            for curie in disease["curies"]
        ),
        "genes": sorted(gene["stableId"] for gene in hypothesis["geneIdentities"]),
        "variants": sorted(
            variant["canonicalId"] for variant in hypothesis["variantIdentities"]
        ),
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return "dhyp-" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]


def _validate_variant(variant: dict[str, Any], batch_assembly: str) -> None:
    if variant["assembly"] == batch_assembly:
        return
    mapping = variant.get("coordinateMapping")
    if not mapping:
        raise ContractError("silent cross-assembly variant merge is forbidden")
    if (
        mapping.get("sourceAssembly") != variant["assembly"]
        or mapping.get("targetAssembly") != batch_assembly
        or mapping.get("refAltValidation") != "MATCH"
    ):
        raise ContractError("cross-assembly mapping provenance or REF/ALT validation invalid")


def validate_batch(batch: dict[str, Any]) -> None:
    _reject_forbidden_keys(batch)
    _validate_schema("diagnostic-hypothesis-batch-v1", batch)

    artifacts: dict[str, dict[str, Any]] = {}
    for artifact in batch["rawArtifacts"]:
        _validate_schema("raw-engine-result-ref-v1", artifact)
        artifact_id = artifact["artifactId"]
        if artifact_id in artifacts:
            raise ContractError(f"duplicate artifactId: {artifact_id}")
        artifacts[artifact_id] = artifact

    evidence_by_id: dict[str, dict[str, Any]] = {}
    gene_symbols: dict[str, str] = {}
    variant_records: dict[str, tuple[Any, ...]] = {}
    for evidence in batch["evidence"]:
        _validate_schema("normalized-engine-evidence-v1", evidence)
        evidence_id = evidence["evidenceId"]
        if evidence_id in evidence_by_id:
            raise ContractError(f"duplicate evidenceId: {evidence_id}")
        evidence_by_id[evidence_id] = evidence

        artifact = artifacts.get(evidence["sourceArtifactId"])
        if artifact is None:
            raise ContractError(f"untraceable evidence: {evidence_id}")
        if artifact["engine"] != evidence["engine"]:
            raise ContractError(f"source engine mismatch: {evidence_id}")

        endpoint = evidence["endpointType"]
        if endpoint not in ALLOWED_ENDPOINTS[evidence["engine"]]:
            raise ContractError(
                f"{evidence['engine']} is incompatible with endpoint {endpoint}"
            )
        if endpoint in RANK_ENDPOINTS and "nativeRank" not in evidence:
            raise ContractError(f"rank endpoint missing nativeRank: {evidence_id}")
        if endpoint not in RANK_ENDPOINTS and "nativeRank" in evidence:
            raise ContractError(f"state endpoint cannot carry nativeRank: {evidence_id}")
        if endpoint == "GENE_DERIVED_DENSE_RANK":
            derivation = evidence.get("derivation")
            if not derivation or derivation["sourceEndpointType"] != "DISEASE_NATIVE_RANK":
                raise ContractError(f"derived gene rank missing disease derivation: {evidence_id}")
        elif "derivation" in evidence:
            raise ContractError(f"non-derived endpoint has derivation: {evidence_id}")
        if endpoint in {"UNAVAILABLE", "UNMAPPED"} and not evidence.get(
            "unavailableReason"
        ):
            raise ContractError(f"{endpoint} evidence missing explicit reason: {evidence_id}")

        gene = evidence.get("gene")
        if gene:
            previous = gene_symbols.setdefault(gene["stableId"], gene["symbol"])
            if previous != gene["symbol"]:
                raise ContractError(f"gene ID/symbol conflict for {gene['stableId']}")
        variant = evidence.get("variant")
        if variant:
            _validate_variant(variant, batch["assembly"])
            identity = (
                variant["assembly"],
                variant["chromosome"],
                variant["position"],
                variant["ref"],
                variant["alt"],
            )
            previous = variant_records.setdefault(variant["canonicalId"], identity)
            if previous != identity:
                raise ContractError(
                    f"variant identity conflict for {variant['canonicalId']}"
                )

    hypothesis_ids: set[str] = set()
    for hypothesis in batch["hypotheses"]:
        _validate_schema("diagnostic-hypothesis-v1", hypothesis)
        if hypothesis["assembly"] != batch["assembly"]:
            raise ContractError("hypothesis assembly differs from batch assembly")
        expected = hypothesis_id(hypothesis)
        if hypothesis["hypothesisId"] != expected:
            raise ContractError(
                f"unstable hypothesisId {hypothesis['hypothesisId']}; expected {expected}"
            )
        if expected in hypothesis_ids:
            raise ContractError(f"duplicate hypothesisId: {expected}")
        hypothesis_ids.add(expected)
        for evidence_id in hypothesis["evidenceIds"]:
            if evidence_id not in evidence_by_id:
                raise ContractError(f"hypothesis references unknown evidence: {evidence_id}")
        for evidence_id in hypothesis["unmappedEvidenceIds"]:
            if evidence_id not in hypothesis["evidenceIds"]:
                raise ContractError("unmapped evidence must also be an evidence reference")
        for gene in hypothesis["geneIdentities"]:
            previous = gene_symbols.setdefault(gene["stableId"], gene["symbol"])
            if previous != gene["symbol"]:
                raise ContractError(f"gene ID/symbol conflict for {gene['stableId']}")
        for variant in hypothesis["variantIdentities"]:
            _validate_variant(variant, batch["assembly"])


def validate_post_lock_interface(interface: dict[str, Any]) -> None:
    _validate_schema("post-lock-hypothesis-evaluation-interface-v1", interface)
    locked_at = datetime.fromisoformat(
        interface["outputsLockedAt"].replace("Z", "+00:00")
    )
    truth_read_at = datetime.fromisoformat(
        interface["truthFirstReadAt"].replace("Z", "+00:00")
    )
    if truth_read_at <= locked_at:
        raise ContractError("truthFirstReadAt must be later than outputsLockedAt")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _tsv_header(path: Path) -> list[str]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        return next(csv.reader(stream, delimiter="\t"))


def audit_locked_structures(
    exomiser_main_root: Path,
    exomiser_canary_root: Path,
    lirical_v2_root: Path,
) -> dict[str, Any]:
    required_variant = {
        "#RANK",
        "GENE_SYMBOL",
        "EXOMISER_VARIANT_SCORE",
        "CONTIG",
        "START",
        "REF",
        "ALT",
    }
    required_gene = {"#RANK", "GENE_SYMBOL", "EXOMISER_GENE_COMBINED_SCORE"}

    exomiser_rows: list[dict[str, Any]] = []
    lock_paths = sorted((exomiser_main_root / "manifest").glob("*G1.lock.json"))
    lock_paths.append(exomiser_canary_root / "manifest" / "G1.lock.json")
    for lock_path in lock_paths:
        lock = json.loads(lock_path.read_text(encoding="utf-8-sig"))
        if lock.get("terminalStatus") not in {
            "COMPLETED",
            "SUCCESS",
            "COMPLETED_LOCKED",
            "COMPLETED_OUTPUT_VALIDATED_LOCKED",
        }:
            raise ContractError(f"non-terminal Exomiser lock: {lock_path}")
        if lock.get("truthRead") is not False:
            raise ContractError(f"Exomiser lock is not truth-free: {lock_path}")
        if not lock.get("lockedAt"):
            raise ContractError(f"missing Exomiser lock time: {lock_path}")

        if lock_path.parent == exomiser_canary_root / "manifest":
            variant_path = exomiser_canary_root / "G1" / "canary-g1.variants.tsv"
            gene_path = exomiser_canary_root / "G1" / "canary-g1.genes.tsv"
        else:
            case_dir = next(
                (
                    directory
                    for directory in (exomiser_main_root / "requests").iterdir()
                    if directory.is_dir() and directory.name.endswith(lock["caseId"])
                ),
                None,
            )
            if case_dir is None:
                # Directory names carry selection order plus case ID.
                case_dir = next(
                    directory
                    for directory in (exomiser_main_root / "requests").iterdir()
                    if directory.is_dir() and lock["caseId"] in directory.name
                )
            variant_path = case_dir / "G1" / "result.variants.tsv"
            gene_path = case_dir / "G1" / "result.genes.tsv"
        variant_header = set(_tsv_header(variant_path))
        gene_header = set(_tsv_header(gene_path))
        if not required_variant <= variant_header or not required_gene <= gene_header:
            raise ContractError(f"Exomiser output schema mismatch: {lock_path.name}")
        exomiser_rows.append(
            {
                "requestIdHash": hashlib.sha256(
                    lock["requestId"].encode("utf-8")
                ).hexdigest(),
                "lockSha256": _sha256(lock_path),
                "variantArtifactSha256": _sha256(variant_path),
                "geneArtifactSha256": _sha256(gene_path),
                "nativeGeneRank": "DIRECTLY_MAPPABLE",
                "nativeVariantRank": "DIRECTLY_MAPPABLE",
                "diseaseRank": "NOT_COMPARABLE",
                "locked": True,
                "truthRead": False,
            }
        )

    lirical_rows: list[dict[str, Any]] = []
    for projection_path in sorted((lirical_v2_root / "projections").glob("*.json")):
        projection = json.loads(projection_path.read_text(encoding="utf-8-sig"))
        if projection.get("truthRead") is not False:
            raise ContractError(f"LIRICAL projection is not truth-free: {projection_path}")
        expected_keys = {
            "nativeDiseaseRanks",
            "geneRanks",
            "geneProjectionRule",
            "variantRankStatus",
            "rawJsonSha256",
            "rawTsvSha256",
        }
        if not expected_keys <= projection.keys():
            raise ContractError(f"LIRICAL projection schema mismatch: {projection_path}")
        if (
            projection["geneProjectionRule"]
            != "DENSE_FIRST_TSV_DISEASE_OCCURRENCE"
            or projection["variantRankStatus"]
            != "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK"
        ):
            raise ContractError(
                f"LIRICAL endpoint semantics mismatch: {projection_path}"
            )
        lirical_rows.append(
            {
                "requestIdHash": hashlib.sha256(
                    projection["requestId"].encode("utf-8")
                ).hexdigest(),
                "projectionSha256": _sha256(projection_path),
                "rawJsonSha256Recorded": bool(
                    re.fullmatch(r"[0-9a-f]{64}", projection["rawJsonSha256"])
                ),
                "rawTsvSha256Recorded": bool(
                    re.fullmatch(r"[0-9a-f]{64}", projection["rawTsvSha256"])
                ),
                "nativeDiseaseRank": "DIRECTLY_MAPPABLE",
                "derivedGeneRank": "DERIVED_WITH_RULE",
                "variantRank": projection["variantRankStatus"],
                "truthRead": False,
            }
        )

    if len(exomiser_rows) != 25 or len(lirical_rows) != 25:
        raise ContractError(
            f"expected 25/25 structures, got {len(exomiser_rows)}/{len(lirical_rows)}"
        )

    return {
        "schemaVersion": "module2-locked-structure-audit-v1",
        "status": "PASSED",
        "auditedAt": datetime.now(timezone.utc).isoformat(),
        "scope": {
            "truthRead": False,
            "engineExecution": False,
            "rawArtifactsModified": False,
            "patientPayloadCommitted": False,
        },
        "coverage": {
            "exomiserG1": len(exomiser_rows),
            "liricalG1Corrected": len(lirical_rows),
            "expectedPerEngine": 25,
        },
        "summary": {
            "exomiser": {
                "nativeGeneRankDirectlyMappable": 25,
                "nativeVariantRankDirectlyMappable": 25,
                "diseaseRankNotComparable": 25,
                "missingOrUnavailable": 0,
            },
            "lirical": {
                "nativeDiseaseRankDirectlyMappable": 25,
                "derivedGeneRankWithRule": 25,
                "nativeVariantRankUnavailable": 25,
                "missingOrUnavailable": 0,
            },
        },
        "records": {"exomiser": exomiser_rows, "lirical": lirical_rows},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("batch", type=Path)

    audit_parser = subparsers.add_parser("audit-locked-structures")
    audit_parser.add_argument("--exomiser-main-root", type=Path, required=True)
    audit_parser.add_argument("--exomiser-canary-root", type=Path, required=True)
    audit_parser.add_argument("--lirical-v2-root", type=Path, required=True)
    audit_parser.add_argument("--output", type=Path, required=True)

    id_parser = subparsers.add_parser("hypothesis-id")
    id_parser.add_argument("hypothesis", type=Path)

    args = parser.parse_args()
    try:
        if args.command == "validate":
            batch = json.loads(args.batch.read_text(encoding="utf-8-sig"))
            validate_batch(batch)
            print(json.dumps({"status": "PASS", "artifact": str(args.batch)}))
        elif args.command == "audit-locked-structures":
            report = audit_locked_structures(
                args.exomiser_main_root, args.exomiser_canary_root, args.lirical_v2_root
            )
            args.output.write_text(
                json.dumps(report, indent=2) + "\n", encoding="utf-8"
            )
            print(
                json.dumps(
                    {
                        "status": "PASS",
                        "exomiserG1": report["coverage"]["exomiserG1"],
                        "liricalG1Corrected": report["coverage"][
                            "liricalG1Corrected"
                        ],
                        "artifact": str(args.output),
                    }
                )
            )
        else:
            hypothesis = json.loads(args.hypothesis.read_text(encoding="utf-8-sig"))
            print(hypothesis_id(hypothesis))
    except (ContractError, OSError, json.JSONDecodeError) as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)}), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
