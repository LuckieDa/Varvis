#!/usr/bin/env python3
"""Truth-free Module 3 normalization and offline hypothesis assembly."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import stat
import sys
from collections import defaultdict
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any, Iterable

import diagnostic_hypothesis_contract as contract


contract._schema = lru_cache(maxsize=None)(contract._schema)

MODULE3_VERSION = "module3-normalizer-v1"
HPO_HASH_RULE = "ordered-json-array-sha256-v1"
LIRICAL_GENE_RULE = "DENSE_FIRST_TSV_DISEASE_OCCURRENCE"
LIRICAL_VARIANT_STATUS = (
    "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
HPO_RE = re.compile(r"^HP:\d{7}$")
FORBIDDEN_KEYS = {
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


class Module3Error(ValueError):
    pass


def _json_load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _json_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _canonical_hash(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _stable_id(prefix: str, *parts: Any) -> str:
    return f"{prefix}-" + _canonical_hash(list(parts))[:24]


def _normalized_key(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def _reject_forbidden(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = _normalized_key(key)
            if normalized != "truthfree" and (
                normalized in FORBIDDEN_KEYS or normalized.startswith("truth")
            ):
                raise Module3Error(f"forbidden output field at {path}.{key}")
            _reject_forbidden(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_forbidden(child, f"{path}[{index}]")


def _write_json(path: Path, value: Any) -> None:
    path.write_bytes(_json_bytes(value))


def _require_sha(value: str, label: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise Module3Error(f"invalid SHA-256 for {label}")
    return value


def _verify_file(path: Path, expected_sha: str, label: str) -> None:
    if not path.is_file():
        raise Module3Error(f"missing {label}: {path}")
    actual = _sha256(path)
    if actual != _require_sha(expected_sha, label):
        raise Module3Error(f"SHA-256 mismatch for {label}: {path}")
    if not _is_read_only(path):
        raise Module3Error(f"locked source is writable: {path}")


def _is_read_only(path: Path) -> bool:
    if os.name == "nt":
        return bool(path.stat().st_file_attributes & stat.FILE_ATTRIBUTE_READONLY)
    return not bool(path.stat().st_mode & stat.S_IWUSR)


def _set_read_only(path: Path) -> None:
    path.chmod(stat.S_IREAD)


def _iso_from_epoch(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, tz=timezone.utc).isoformat()


def _hpo_hash(terms: list[str]) -> str:
    if len(terms) != len(set(terms)) or any(not HPO_RE.fullmatch(x) for x in terms):
        raise Module3Error("HPO terms must be unique ordered HP:NNNNNNN values")
    return _canonical_hash(terms)


def _finite_float(value: Any, label: str) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError) as error:
        raise Module3Error(f"invalid numeric {label}: {value}") from error
    if not math.isfinite(parsed):
        raise Module3Error(f"non-finite numeric {label}: {value}")
    return parsed


def _positive_rank(value: Any, label: str) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError) as error:
        raise Module3Error(f"invalid rank {label}: {value}") from error
    if parsed < 1:
        raise Module3Error(f"rank must be positive for {label}")
    return parsed


def _read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise Module3Error(f"missing TSV header: {path}")
        return list(reader)


def _read_lirical_tsv(path: Path) -> list[dict[str, str]]:
    data_lines: list[str] = []
    with path.open("r", encoding="utf-8-sig") as stream:
        for line in stream:
            if not line.startswith("!") and line.strip():
                data_lines.append(line)
    if not data_lines:
        raise Module3Error(f"missing LIRICAL TSV data: {path}")
    return list(csv.DictReader(data_lines, delimiter="\t"))


def _artifact_ref(
    *,
    artifact_id: str,
    engine: str,
    engine_version: str,
    run_id: str,
    request_id: str,
    path: Path,
    expected_sha: str,
    artifact_schema: str,
    resource_versions: dict[str, str],
    vcf_sha: str,
    hpo_sha: str,
    locked_at: str,
    producer: str,
) -> dict[str, Any]:
    _verify_file(path, expected_sha, artifact_id)
    return {
        "schemaVersion": "raw-engine-result-ref-v1",
        "artifactId": artifact_id,
        "engine": engine,
        "engineVersion": engine_version,
        "runId": run_id,
        "requestId": request_id,
        "artifactPath": str(path.resolve()),
        "artifactSha256": expected_sha,
        "artifactSchema": artifact_schema,
        "assembly": "GRCh38",
        "resourceVersions": resource_versions,
        "inputHashes": {"vcfSha256": vcf_sha, "hpoSha256": hpo_sha},
        "lockStatus": "LOCKED",
        "lockedAt": locked_at,
        "provenance": {
            "producer": producer,
            "createdAt": locked_at,
            "verificationCommand": "SHA-256 equality against immutable lock manifest",
        },
    }


def _gene_identity(gene_id: str, symbol: str, provenance: str) -> dict[str, Any]:
    if not re.fullmatch(r"NCBIGene:\d+", gene_id or "") or not symbol:
        raise Module3Error(f"invalid stable gene identity: {gene_id}/{symbol}")
    return {
        "stableId": gene_id,
        "symbol": symbol,
        "mappingStatus": "MAPPED",
        "mappingProvenance": provenance,
    }


def _variant_identity(row: dict[str, str]) -> dict[str, Any]:
    chromosome = row["CONTIG"]
    position = _positive_rank(row["START"], "variant position")
    ref = row["REF"].upper()
    alt = row["ALT"].upper()
    if not re.fullmatch(r"[ACGTN]+", ref) or not re.fullmatch(r"[ACGTN]+", alt):
        raise Module3Error(f"unsupported REF/ALT identity: {ref}/{alt}")
    return {
        "canonicalId": f"GRCh38:{chromosome}:{position}:{ref}:{alt}",
        "assembly": "GRCh38",
        "chromosome": chromosome,
        "position": position,
        "ref": ref,
        "alt": alt,
        "mappingStatus": "NATIVE",
    }


def _nonempty_tokens(prefixes: Iterable[tuple[str, Any]]) -> list[str]:
    return [
        f"{key}:{value}"
        for key, value in prefixes
        if value is not None and str(value).strip() not in {"", ".", "NA", "null"}
    ]


def _exo_gene_evidence(
    request_id: str, artifact_id: str, rows: list[dict[str, str]]
) -> tuple[list[dict[str, Any]], dict[str, list[str]], dict[str, dict[str, Any]]]:
    evidence: list[dict[str, Any]] = []
    by_gene: dict[str, list[str]] = defaultdict(list)
    identities: dict[str, dict[str, Any]] = {}
    for index, row in enumerate(rows, start=1):
        gene_id = f"NCBIGene:{row['ENTREZ_GENE_ID']}"
        gene = _gene_identity(gene_id, row["GENE_SYMBOL"], "Exomiser locked gene TSV")
        previous = identities.setdefault(gene_id, gene)
        if previous["symbol"] != gene["symbol"]:
            raise Module3Error(f"gene ID/symbol conflict: {gene_id}")
        item = {
            "schemaVersion": "normalized-engine-evidence-v1",
            "evidenceId": _stable_id(
                "evidence",
                "EXOMISER",
                "GENE_NATIVE_RANK",
                request_id,
                index,
                row.get("ID"),
            ),
            "sourceArtifactId": artifact_id,
            "engine": "EXOMISER",
            "endpointType": "GENE_NATIVE_RANK",
            "nativeRank": _positive_rank(row["#RANK"], "Exomiser gene"),
            "nativeScore": _finite_float(
                row["EXOMISER_GENE_COMBINED_SCORE"], "Exomiser gene score"
            ),
            "gene": gene,
            "inheritance": [row["MOI"]] if row.get("MOI") else [],
            "phenotypeEvidence": {
                "exomiserGenePhenoScore": _finite_float(
                    row["EXOMISER_GENE_PHENO_SCORE"], "Exomiser gene phenotype score"
                )
            },
            "genotypeEvidence": {
                "exomiserGeneVariantScore": _finite_float(
                    row["EXOMISER_GENE_VARIANT_SCORE"],
                    "Exomiser gene variant score",
                )
            },
            "supportingEvidence": _nonempty_tokens(
                [
                    ("MOI", row.get("MOI")),
                    ("P_VALUE", row.get("P-VALUE")),
                    ("PHIVE_ALL_SPECIES_SCORE", row.get("PHIVE_ALL_SPECIES_SCORE")),
                ]
            ),
            "mappingStatus": "MAPPED",
            "support": "SUPPORTS",
        }
        evidence.append(item)
        by_gene[gene_id].append(item["evidenceId"])
    return evidence, dict(by_gene), identities


def _exo_variant_evidence(
    request_id: str, artifact_id: str, rows: list[dict[str, str]]
) -> tuple[
    list[dict[str, Any]],
    dict[str, list[str]],
    dict[str, dict[str, dict[str, Any]]],
    dict[str, dict[str, Any]],
]:
    evidence: list[dict[str, Any]] = []
    by_gene: dict[str, list[str]] = defaultdict(list)
    variants_by_gene: dict[str, dict[str, dict[str, Any]]] = defaultdict(dict)
    identities: dict[str, dict[str, Any]] = {}
    for index, row in enumerate(rows, start=1):
        gene_id = f"NCBIGene:{row['ENTREZ_GENE_ID']}"
        gene = _gene_identity(gene_id, row["GENE_SYMBOL"], "Exomiser locked variant TSV")
        previous = identities.setdefault(gene_id, gene)
        if previous["symbol"] != gene["symbol"]:
            raise Module3Error(f"gene ID/symbol conflict: {gene_id}")
        variant = _variant_identity(row)
        variants_by_gene[gene_id].setdefault(variant["canonicalId"], variant)
        item = {
            "schemaVersion": "normalized-engine-evidence-v1",
            "evidenceId": _stable_id(
                "evidence",
                "EXOMISER",
                "VARIANT_NATIVE_RANK",
                request_id,
                index,
                row.get("ID"),
            ),
            "sourceArtifactId": artifact_id,
            "engine": "EXOMISER",
            "endpointType": "VARIANT_NATIVE_RANK",
            "nativeRank": _positive_rank(row["#RANK"], "Exomiser variant"),
            "nativeScore": _finite_float(
                row["EXOMISER_VARIANT_SCORE"], "Exomiser variant score"
            ),
            "gene": gene,
            "variant": variant,
            "inheritance": [row["MOI"]] if row.get("MOI") else [],
            "genotypeEvidence": {
                key: value
                for key, value in {
                    "genotype": row.get("GENOTYPE"),
                    "filter": row.get("FILTER"),
                    "quality": row.get("QUAL"),
                }.items()
                if value not in {None, ""}
            },
            "supportingEvidence": _nonempty_tokens(
                [
                    ("FUNCTIONAL_CLASS", row.get("FUNCTIONAL_CLASS")),
                    ("HGVS", row.get("HGVS")),
                    ("ACMG_CLASS", row.get("EXOMISER_ACMG_CLASSIFICATION")),
                    ("ACMG_EVIDENCE", row.get("EXOMISER_ACMG_EVIDENCE")),
                    ("CLINVAR", row.get("CLINVAR_PRIMARY_INTERPRETATION")),
                    ("MAX_FREQ", row.get("MAX_FREQ")),
                ]
            ),
            "mappingStatus": "MAPPED",
            "support": "SUPPORTS",
        }
        evidence.append(item)
        by_gene[gene_id].append(item["evidenceId"])
    return evidence, dict(by_gene), dict(variants_by_gene), identities


def _lirical_evidence(
    request_id: str,
    projection_artifact_id: str,
    raw_tsv_artifact_id: str,
    projection: dict[str, Any],
    disease_labels: dict[tuple[int, str], str],
) -> tuple[
    list[dict[str, Any]],
    dict[tuple[str, str], str],
    dict[tuple[str, str], list[str]],
    dict[str, dict[str, Any]],
    str,
]:
    evidence: list[dict[str, Any]] = []
    disease_evidence: dict[tuple[str, str], str] = {}
    derived_by_first_disease: dict[tuple[str, str], list[str]] = defaultdict(list)
    identities: dict[str, dict[str, Any]] = {}
    for index, row in enumerate(projection["nativeDiseaseRanks"], start=1):
        rank = _positive_rank(row["rank"], "LIRICAL disease")
        disease_id = row["diseaseId"]
        gene_id = row["geneId"]
        label = disease_labels.get((rank, disease_id), disease_id)
        gene = _gene_identity(
            gene_id, row["geneSymbol"], "LIRICAL corrected projection exact ID"
        )
        previous = identities.setdefault(gene_id, gene)
        if previous["symbol"] != gene["symbol"]:
            raise Module3Error(f"gene ID/symbol conflict: {gene_id}")
        item = {
            "schemaVersion": "normalized-engine-evidence-v1",
            "evidenceId": _stable_id(
                "evidence",
                "LIRICAL",
                "DISEASE_NATIVE_RANK",
                request_id,
                index,
                disease_id,
                gene_id,
            ),
            "sourceArtifactId": projection_artifact_id,
            "engine": "LIRICAL",
            "endpointType": "DISEASE_NATIVE_RANK",
            "nativeRank": rank,
            "probability": _finite_float(
                row["posttestProbability"], "LIRICAL post-test probability"
            ),
            "likelihoodRatio": _finite_float(
                row["compositeLR"], "LIRICAL composite LR"
            ),
            "disease": {
                "curies": [disease_id],
                "label": label,
                "mappingStatus": "MAPPED",
                "mappingProvenance": "exact CURIE/rank match to locked LIRICAL TSV",
            },
            "gene": gene,
            "phenotypeEvidence": row["phenotypeEvidence"],
            "genotypeEvidence": row["genotypeEvidence"],
            "supportingEvidence": [
                f"LABEL_SOURCE_ARTIFACT:{raw_tsv_artifact_id}",
                *(
                    [row["supportingVariants"]]
                    if str(row.get("supportingVariants", "")).strip()
                    else []
                ),
            ],
            "mappingStatus": "MAPPED",
            "support": "SUPPORTS",
        }
        evidence.append(item)
        disease_evidence[(disease_id, gene_id)] = item["evidenceId"]

    for index, row in enumerate(projection["geneRanks"], start=1):
        gene_id = row["geneId"]
        gene = _gene_identity(
            gene_id, row["geneSymbol"], "LIRICAL corrected dense gene projection"
        )
        previous = identities.setdefault(gene_id, gene)
        if previous["symbol"] != gene["symbol"]:
            raise Module3Error(f"gene ID/symbol conflict: {gene_id}")
        item = {
            "schemaVersion": "normalized-engine-evidence-v1",
            "evidenceId": _stable_id(
                "evidence",
                "LIRICAL",
                "GENE_DERIVED_DENSE_RANK",
                request_id,
                index,
                row["firstDiseaseId"],
                gene_id,
            ),
            "sourceArtifactId": projection_artifact_id,
            "engine": "LIRICAL",
            "endpointType": "GENE_DERIVED_DENSE_RANK",
            "nativeRank": _positive_rank(row["rank"], "LIRICAL derived gene"),
            "derivation": {
                "sourceEndpointType": "DISEASE_NATIVE_RANK",
                "rule": "dense rank by first native disease rank",
                "ruleVersion": "lirical-gene-projection-v2",
            },
            "gene": gene,
            "supportingEvidence": [
                f"FIRST_DISEASE_RANK:{row['firstDiseaseRank']}",
                f"FIRST_DISEASE_ID:{row['firstDiseaseId']}",
            ],
            "mappingStatus": "MAPPED",
            "support": "SUPPORTS",
        }
        evidence.append(item)
        derived_by_first_disease[
            (row["firstDiseaseId"], gene_id)
        ].append(item["evidenceId"])

    unavailable_id = _stable_id(
        "evidence", "LIRICAL", "VARIANT_UNAVAILABLE", request_id
    )
    evidence.append(
        {
            "schemaVersion": "normalized-engine-evidence-v1",
            "evidenceId": unavailable_id,
            "sourceArtifactId": projection_artifact_id,
            "engine": "LIRICAL",
            "endpointType": "NOT_COMPARABLE",
            "mappingStatus": "UNAVAILABLE",
            "unavailableReason": LIRICAL_VARIANT_STATUS,
            "support": "UNAVAILABLE",
        }
    )
    return (
        evidence,
        disease_evidence,
        dict(derived_by_first_disease),
        identities,
        unavailable_id,
    )


def _exomiser_disease_unavailable(
    request_id: str, gene_artifact_id: str
) -> dict[str, Any]:
    return {
        "schemaVersion": "normalized-engine-evidence-v1",
        "evidenceId": _stable_id(
            "evidence", "EXOMISER", "DISEASE_UNAVAILABLE", request_id
        ),
        "sourceArtifactId": gene_artifact_id,
        "engine": "EXOMISER",
        "endpointType": "NOT_COMPARABLE",
        "mappingStatus": "UNAVAILABLE",
        "unavailableReason": "EXOMISER_OUTPUT_HAS_NO_NATIVE_DISEASE_RANK",
        "support": "UNAVAILABLE",
    }


def _merge_gene_identities(
    identity_maps: Iterable[dict[str, dict[str, Any]]]
) -> dict[str, dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for identities in identity_maps:
        for gene_id, identity in identities.items():
            previous = merged.setdefault(gene_id, identity)
            if previous["symbol"] != identity["symbol"]:
                raise Module3Error(
                    f"gene ID/symbol conflict: {gene_id} "
                    f"{previous['symbol']} != {identity['symbol']}"
                )
    return merged


def _assemble_hypotheses(
    *,
    projection: dict[str, Any],
    evidence_by_disease: dict[tuple[str, str], str],
    lirical_derived_by_disease: dict[tuple[str, str], list[str]],
    exo_gene_evidence: dict[str, list[str]],
    exo_variant_evidence: dict[str, list[str]],
    variants_by_gene: dict[str, dict[str, dict[str, Any]]],
    gene_identities: dict[str, dict[str, Any]],
    disease_labels: dict[tuple[int, str], str],
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    hypotheses: list[dict[str, Any]] = []
    lirical_genes: set[str] = set()
    exact_gene_matches: set[str] = set()
    lirical_only = 0

    for row in projection["nativeDiseaseRanks"]:
        disease_id = row["diseaseId"]
        gene_id = row["geneId"]
        lirical_genes.add(gene_id)
        exo_gene_ids = exo_gene_evidence.get(gene_id, [])
        exo_variant_ids = exo_variant_evidence.get(gene_id, [])
        if exo_gene_ids or exo_variant_ids:
            exact_gene_matches.add(gene_id)
        else:
            lirical_only += 1
        evidence_ids = [
            evidence_by_disease[(disease_id, gene_id)],
            *lirical_derived_by_disease.get((disease_id, gene_id), []),
            *exo_gene_ids,
            *exo_variant_ids,
        ]
        variants = sorted(
            variants_by_gene.get(gene_id, {}).values(),
            key=lambda item: (
                item["chromosome"],
                item["position"],
                item["ref"],
                item["alt"],
            ),
        )
        label = disease_labels.get((int(row["rank"]), disease_id), disease_id)
        hypothesis = {
            "schemaVersion": "diagnostic-hypothesis-v1",
            "hypothesisId": "",
            "assembly": "GRCh38",
            "diseaseIdentities": [
                {
                    "curies": [disease_id],
                    "label": label,
                    "mappingStatus": "MAPPED",
                    "mappingProvenance": "exact CURIE/rank match to locked LIRICAL TSV",
                }
            ],
            "geneIdentities": [gene_identities[gene_id]],
            "variantIdentities": variants,
            "evidenceIds": list(dict.fromkeys(evidence_ids)),
            "inheritanceCompatibility": "UNKNOWN",
            "phenotypeSupport": "SUPPORTED",
            "genotypeSupport": "SUPPORTED",
            "conflicts": [],
            "unmappedEvidenceIds": [],
            "completenessStatus": (
                "COMPLETE" if exo_gene_ids or exo_variant_ids else "PARTIAL"
            ),
            "mappingProvenance": [
                "LIRICAL disease-to-gene stable ID",
                "Exomiser evidence joined only by exact NCBIGene ID"
                if exo_gene_ids or exo_variant_ids
                else "No exact Exomiser NCBIGene match",
            ],
        }
        hypothesis["hypothesisId"] = contract.hypothesis_id(hypothesis)
        hypotheses.append(hypothesis)

    exomiser_genes = set(exo_gene_evidence) | set(exo_variant_evidence)
    exomiser_only_genes = sorted(exomiser_genes - lirical_genes)
    for gene_id in exomiser_only_genes:
        evidence_ids = [
            *exo_gene_evidence.get(gene_id, []),
            *exo_variant_evidence.get(gene_id, []),
        ]
        variants = sorted(
            variants_by_gene.get(gene_id, {}).values(),
            key=lambda item: (
                item["chromosome"],
                item["position"],
                item["ref"],
                item["alt"],
            ),
        )
        hypothesis = {
            "schemaVersion": "diagnostic-hypothesis-v1",
            "hypothesisId": "",
            "assembly": "GRCh38",
            "diseaseIdentities": [],
            "geneIdentities": [gene_identities[gene_id]],
            "variantIdentities": variants,
            "evidenceIds": list(dict.fromkeys(evidence_ids)),
            "inheritanceCompatibility": "UNKNOWN",
            "phenotypeSupport": "NOT_APPLICABLE",
            "genotypeSupport": "SUPPORTED",
            "conflicts": [],
            "unmappedEvidenceIds": [],
            "completenessStatus": "PARTIAL",
            "mappingProvenance": [
                "Exomiser-only exact NCBIGene hypothesis",
                "No LIRICAL disease endpoint for this stable gene ID",
            ],
        }
        hypothesis["hypothesisId"] = contract.hypothesis_id(hypothesis)
        hypotheses.append(hypothesis)

    ids = [item["hypothesisId"] for item in hypotheses]
    if len(ids) != len(set(ids)):
        raise Module3Error("duplicate deterministic hypothesis ID")
    return hypotheses, {
        "diseaseGeneHypotheses": len(projection["nativeDiseaseRanks"]),
        "exactMatchedGenes": len(exact_gene_matches),
        "liricalOnlyDiseaseHypotheses": lirical_only,
        "exomiserOnlyGeneHypotheses": len(exomiser_only_genes),
    }


def normalize_case(
    *,
    request: dict[str, Any],
    raw_artifacts: list[dict[str, Any]],
    exomiser_gene_rows: list[dict[str, str]],
    exomiser_variant_rows: list[dict[str, str]],
    lirical_projection: dict[str, Any],
    disease_labels: dict[tuple[int, str], str],
    created_at: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    request_id = request["requestId"]
    by_engine_schema = {
        (item["engine"], item["artifactSchema"]): item["artifactId"]
        for item in raw_artifacts
    }
    exo_gene_artifact = by_engine_schema[("EXOMISER", "exomiser-15.1-genes-tsv")]
    exo_variant_artifact = by_engine_schema[
        ("EXOMISER", "exomiser-15.1-variants-tsv")
    ]
    lirical_projection_artifact = by_engine_schema[
        ("LIRICAL", "lirical-corrected-projection-v2")
    ]
    lirical_tsv_artifact = by_engine_schema[
        ("LIRICAL", "lirical-2.4-native-disease-tsv")
    ]

    exo_genes, exo_gene_by_gene, exo_gene_identities = _exo_gene_evidence(
        request_id, exo_gene_artifact, exomiser_gene_rows
    )
    (
        exo_variants,
        exo_variant_by_gene,
        variants_by_gene,
        exo_variant_identities,
    ) = _exo_variant_evidence(
        request_id, exo_variant_artifact, exomiser_variant_rows
    )
    (
        lirical_evidence,
        disease_evidence,
        derived_by_disease,
        lirical_identities,
        _,
    ) = _lirical_evidence(
        request_id,
        lirical_projection_artifact,
        lirical_tsv_artifact,
        lirical_projection,
        disease_labels,
    )
    evidence = [
        *exo_genes,
        *exo_variants,
        _exomiser_disease_unavailable(request_id, exo_gene_artifact),
        *lirical_evidence,
    ]
    gene_identities = _merge_gene_identities(
        [exo_gene_identities, exo_variant_identities, lirical_identities]
    )
    hypotheses, assembly_stats = _assemble_hypotheses(
        projection=lirical_projection,
        evidence_by_disease=disease_evidence,
        lirical_derived_by_disease=derived_by_disease,
        exo_gene_evidence=exo_gene_by_gene,
        exo_variant_evidence=exo_variant_by_gene,
        variants_by_gene=variants_by_gene,
        gene_identities=gene_identities,
        disease_labels=disease_labels,
    )
    batch = {
        "schemaVersion": "diagnostic-hypothesis-batch-v1",
        "batchId": _stable_id("module3-batch", request_id),
        "assembly": "GRCh38",
        "createdAt": created_at,
        "truthFree": True,
        "fusionApplied": False,
        "rawArtifacts": raw_artifacts,
        "evidence": evidence,
        "hypotheses": hypotheses,
    }
    _reject_forbidden(batch)
    contract.validate_batch(batch)
    summary = {
        "exomiserGeneEvidence": len(exo_genes),
        "exomiserVariantEvidence": len(exo_variants),
        "exomiserDiseaseEndpointUnavailable": 1,
        "liricalDiseaseEvidence": len(lirical_projection["nativeDiseaseRanks"]),
        "liricalDerivedGeneEvidence": len(lirical_projection["geneRanks"]),
        "liricalVariantEndpointUnavailable": 1,
        "normalizedEvidence": len(evidence),
        "hypotheses": len(hypotheses),
        **assembly_stats,
        "unmappedEvidence": 0,
        "identityConflicts": 0,
    }
    return batch, summary


def _artifact_entry(
    artifacts: list[dict[str, Any]], suffix: str, *, key: str
) -> dict[str, Any]:
    matches = [
        item
        for item in artifacts
        if str(item.get(key, "")).replace("\\", "/").endswith(suffix)
    ]
    if len(matches) != 1:
        raise Module3Error(f"expected one locked artifact ending {suffix}")
    return matches[0]


def _discover_exomiser(
    request: dict[str, Any], main_root: Path, canary_root: Path
) -> tuple[Path, Path, dict[str, Any], Path]:
    request_id = request["requestId"]
    main_lock = list((main_root / "manifest").glob(f"*{request_id}-G1.lock.json"))
    if main_lock:
        if len(main_lock) != 1:
            raise Module3Error(f"ambiguous Exomiser lock for {request_id}")
        lock_path = main_lock[0]
        lock = _json_load(lock_path)
        gene = _artifact_entry(lock["artifacts"], "result.genes.tsv", key="logicalPath")
        variant = _artifact_entry(
            lock["artifacts"], "result.variants.tsv", key="logicalPath"
        )
        gene_path = main_root / Path(gene["logicalPath"])
        variant_path = main_root / Path(variant["logicalPath"])
    else:
        lock_path = canary_root / "manifest" / "G1.lock.json"
        lock = _json_load(lock_path)
        if lock.get("requestId") != request_id:
            raise Module3Error(f"missing Exomiser lock for {request_id}")
        pre_run = _json_load(canary_root / "manifest" / "pre-run.json")
        g1_request = next(
            (
                item
                for item in pre_run["requests"]
                if item["groupId"] == "G1" and item["requestId"] == request_id
            ),
            None,
        )
        if (
            pre_run.get("caseId") != request["caseId"]
            or g1_request is None
            or g1_request.get("hpoTerms") != request["hpoTerms"]
        ):
            raise Module3Error(f"canary pre-run binding mismatch: {request_id}")
        lock["inputVcfSha256"] = pre_run["commonInput"]["vcfSha256"]
        lock["hpoCount"] = len(g1_request["hpoTerms"])
        gene = _artifact_entry(lock["artifacts"], "canary-g1.genes.tsv", key="name")
        variant = _artifact_entry(
            lock["artifacts"], "canary-g1.variants.tsv", key="name"
        )
        gene_path = canary_root / "G1" / gene["name"]
        variant_path = canary_root / "G1" / variant["name"]
    if lock.get("truthRead") is not False or not lock.get("lockedAt"):
        raise Module3Error(f"Exomiser source is not truth-free locked: {request_id}")
    if lock.get("inputVcfSha256") != request["vcfSha256"]:
        raise Module3Error(f"Exomiser VCF binding mismatch: {request_id}")
    if lock.get("hpoCount") != len(request["hpoTerms"]):
        raise Module3Error(f"Exomiser HPO count mismatch: {request_id}")
    _verify_file(gene_path, gene["sha256"], "Exomiser genes TSV")
    _verify_file(variant_path, variant["sha256"], "Exomiser variants TSV")
    lock["_geneArtifact"] = gene
    lock["_variantArtifact"] = variant
    return gene_path, variant_path, lock, lock_path


def _discover_lirical(
    request: dict[str, Any], lirical_root: Path
) -> tuple[Path, Path, dict[str, Any], dict[str, Any], str]:
    request_id = request["requestId"]
    projection_path = lirical_root / "evaluation-v2" / "projections" / f"{request_id}.json"
    projection = _json_load(projection_path)
    if (
        projection.get("requestId") != request_id
        or projection.get("caseId") != request["caseId"]
        or projection.get("selectionOrder") != request["selectionOrder"]
        or projection.get("truthRead") is not False
        or projection.get("geneProjectionRule") != LIRICAL_GENE_RULE
        or projection.get("variantRankStatus") != LIRICAL_VARIANT_STATUS
    ):
        raise Module3Error(f"LIRICAL projection binding mismatch: {request_id}")

    evaluation_lock_path = lirical_root / "manifest" / "evaluation-v2.lock.json"
    evaluation_lock = _json_load(evaluation_lock_path)
    projection_entry = _artifact_entry(
        evaluation_lock["artifacts"],
        f"projections/{request_id}.json",
        key="logicalPath",
    )
    _verify_file(projection_path, projection_entry["sha256"], "LIRICAL projection")

    raw_lock_path = lirical_root / "manifest" / f"{request_id}.lock.json"
    raw_lock = _json_load(raw_lock_path)
    if (
        raw_lock.get("truthRead") is not False
        or raw_lock.get("inputVcfSha256") != request["vcfSha256"]
        or raw_lock.get("hpoCount") != len(request["hpoTerms"])
    ):
        raise Module3Error(f"LIRICAL raw lock binding mismatch: {request_id}")
    raw_tsv_entry = _artifact_entry(raw_lock["artifacts"], "result.tsv", key="logicalPath")
    raw_json_entry = _artifact_entry(
        raw_lock["artifacts"], "result.json", key="logicalPath"
    )
    if (
        raw_tsv_entry["sha256"] != projection["rawTsvSha256"]
        or raw_json_entry["sha256"] != projection["rawJsonSha256"]
    ):
        raise Module3Error(f"LIRICAL projection/raw hash mismatch: {request_id}")

    request_dirs = list(
        (lirical_root / "requests").glob(f"{request['selectionOrder']:02d}-*")
    )
    if len(request_dirs) != 1 or request["caseId"] not in request_dirs[0].name:
        raise Module3Error(f"ambiguous LIRICAL request directory: {request_id}")
    raw_tsv_path = request_dirs[0] / "G1" / "result.tsv"
    _verify_file(raw_tsv_path, raw_tsv_entry["sha256"], "LIRICAL raw TSV")

    batch_manifest = _json_load(lirical_root / "manifest" / "batch-manifest.json")
    lock_entry = next(
        (
            item
            for item in batch_manifest["locks"]
            if item["requestId"] == request_id
        ),
        None,
    )
    if lock_entry is None:
        raise Module3Error(f"missing LIRICAL batch lock: {request_id}")
    locked_at = _iso_from_epoch(float(lock_entry["lockedAtEpoch"]))
    raw_lock["_tsvArtifact"] = raw_tsv_entry
    projection["_projectionArtifact"] = projection_entry
    projection["_projectionLockedAt"] = datetime.fromtimestamp(
        evaluation_lock_path.stat().st_mtime, tz=timezone.utc
    ).isoformat()
    return projection_path, raw_tsv_path, projection, raw_lock, locked_at


def _disease_labels(rows: list[dict[str, str]]) -> dict[tuple[int, str], str]:
    labels: dict[tuple[int, str], str] = {}
    for row in rows:
        rank = _positive_rank(row["rank"], "LIRICAL TSV disease")
        curie = row["diseaseCurie"]
        label = row["diseaseName"].strip()
        if not label:
            raise Module3Error(f"missing disease label for {curie}")
        key = (rank, curie)
        previous = labels.setdefault(key, label)
        if previous != label:
            raise Module3Error(f"conflicting disease label for {key}")
    return labels


def _case_source_artifacts(
    *,
    request: dict[str, Any],
    exo_gene_path: Path,
    exo_variant_path: Path,
    exo_lock: dict[str, Any],
    lirical_projection_path: Path,
    lirical_raw_tsv_path: Path,
    lirical_projection: dict[str, Any],
    lirical_raw_lock: dict[str, Any],
    lirical_raw_locked_at: str,
    exomiser_run_id: str,
    lirical_run_id: str,
) -> list[dict[str, Any]]:
    request_id = request["requestId"]
    request_hash = hashlib.sha256(request_id.encode("utf-8")).hexdigest()
    hpo_sha = _hpo_hash(request["hpoTerms"])
    vcf_sha = _require_sha(request["vcfSha256"], "request VCF")
    common = {"vcf_sha": vcf_sha, "hpo_sha": hpo_sha}
    return [
        _artifact_ref(
            artifact_id=f"artifact-exomiser-genes-{request_hash[:16]}",
            engine="EXOMISER",
            engine_version="15.1.0",
            run_id=exomiser_run_id,
            request_id=request_id,
            path=exo_gene_path,
            expected_sha=exo_lock["_geneArtifact"]["sha256"],
            artifact_schema="exomiser-15.1-genes-tsv",
            resource_versions={
                "exomiser": "15.1.0",
                "hg38": "2602",
                "phenotype": "2602",
                "clinvar": "2602",
            },
            locked_at=exo_lock["lockedAt"],
            producer="Stage 3A locked Exomiser G1",
            **common,
        ),
        _artifact_ref(
            artifact_id=f"artifact-exomiser-variants-{request_hash[:16]}",
            engine="EXOMISER",
            engine_version="15.1.0",
            run_id=exomiser_run_id,
            request_id=request_id,
            path=exo_variant_path,
            expected_sha=exo_lock["_variantArtifact"]["sha256"],
            artifact_schema="exomiser-15.1-variants-tsv",
            resource_versions={
                "exomiser": "15.1.0",
                "hg38": "2602",
                "phenotype": "2602",
                "clinvar": "2602",
            },
            locked_at=exo_lock["lockedAt"],
            producer="Stage 3A locked Exomiser G1",
            **common,
        ),
        _artifact_ref(
            artifact_id=f"artifact-lirical-projection-{request_hash[:16]}",
            engine="LIRICAL",
            engine_version="2.4.0",
            run_id=lirical_run_id,
            request_id=request_id,
            path=lirical_projection_path,
            expected_sha=lirical_projection["_projectionArtifact"]["sha256"],
            artifact_schema="lirical-corrected-projection-v2",
            resource_versions={
                "lirical": "2.4.0",
                "variantData": "2602",
                "assembly": "hg38",
            },
            locked_at=lirical_projection["_projectionLockedAt"],
            producer="Module 1 corrected locked LIRICAL projection",
            **common,
        ),
        _artifact_ref(
            artifact_id=f"artifact-lirical-tsv-{request_hash[:16]}",
            engine="LIRICAL",
            engine_version="2.4.0",
            run_id=lirical_run_id,
            request_id=request_id,
            path=lirical_raw_tsv_path,
            expected_sha=lirical_raw_lock["_tsvArtifact"]["sha256"],
            artifact_schema="lirical-2.4-native-disease-tsv",
            resource_versions={
                "lirical": "2.4.0",
                "variantData": "2602",
                "assembly": "hg38",
            },
            locked_at=lirical_raw_locked_at,
            producer="Stage 3A locked LIRICAL G1",
            **common,
        ),
    ]


def _case_documents(
    batch: dict[str, Any],
    request: dict[str, Any],
    summary: dict[str, Any],
    created_at: str,
    source_lock_hashes: dict[str, str],
) -> dict[str, Any]:
    request_hash = hashlib.sha256(request["requestId"].encode("utf-8")).hexdigest()
    common = {
        "requestIdentityHash": request_hash,
        "selectionOrder": request["selectionOrder"],
        "truthFree": True,
        "fusionApplied": False,
        "createdAt": created_at,
    }
    source_manifest = {
        "schemaVersion": "module3-source-artifact-manifest-v1",
        **common,
        "hpoHashRule": HPO_HASH_RULE,
        "hpoSha256": _hpo_hash(request["hpoTerms"]),
        "vcfSha256": request["vcfSha256"],
        "tbiSha256": request["tbiSha256"],
        "sourceLockSha256": source_lock_hashes,
        "artifacts": batch["rawArtifacts"],
    }
    evidence_doc = {
        "schemaVersion": "module3-normalized-engine-evidence-set-v1",
        **common,
        "evidence": batch["evidence"],
    }
    hypotheses_doc = {
        "schemaVersion": "module3-diagnostic-hypothesis-set-v1",
        **common,
        "hypotheses": batch["hypotheses"],
    }
    validation = {
        "schemaVersion": "module3-case-validation-v1",
        **common,
        "status": "PASSED",
        "contractVersion": "diagnostic-hypothesis-batch-v1",
        "summary": summary,
        "checks": {
            "sourceHashes": "PASSED",
            "sourceLocks": "PASSED",
            "endpointCompatibility": "PASSED",
            "stableIdentities": "PASSED",
            "forbiddenFieldRejection": "PASSED",
            "fusionFieldRejection": "PASSED",
            "schemaValidation": "PASSED",
        },
    }
    return {
        "source-artifact-manifest.json": source_manifest,
        "normalized-engine-evidence.json": evidence_doc,
        "diagnostic-hypotheses.json": hypotheses_doc,
        "diagnostic-hypothesis-batch.json": batch,
        "validation.json": validation,
    }


def _write_case(case_root: Path, documents: dict[str, Any], locked_at: str) -> dict[str, Any]:
    case_root.mkdir(parents=True, exist_ok=False)
    hashes: dict[str, str] = {}
    for filename, document in documents.items():
        _reject_forbidden(document)
        path = case_root / filename
        _write_json(path, document)
        hashes[filename] = _sha256(path)
    lock = {
        "schemaVersion": "module3-case-lock-v1",
        "status": "COMPLETED_LOCKED",
        "truthFree": True,
        "fusionApplied": False,
        "lockedAt": locked_at,
        "artifacts": [
            {
                "logicalPath": filename,
                "bytes": (case_root / filename).stat().st_size,
                "sha256": digest,
            }
            for filename, digest in sorted(hashes.items())
        ],
    }
    _write_json(case_root / "lock.json", lock)
    return {
        "caseDirectory": case_root.name,
        "contentHashes": hashes,
        "lockSha256": _sha256(case_root / "lock.json"),
    }


def _prepare_requests(path: Path) -> list[dict[str, Any]]:
    matrix = _json_load(path)
    requests = sorted(matrix["requests"], key=lambda item: item["selectionOrder"])
    if (
        len(requests) != 25
        or [item["selectionOrder"] for item in requests] != list(range(1, 26))
        or any(item["groupId"] != "G1" for item in requests)
        or any(item["assembly"] != "GRCh38" for item in requests)
        or any(item.get("truthFree") is not True for item in requests)
        or len({item["requestId"] for item in requests}) != 25
        or len({item["caseId"] for item in requests}) != 25
    ):
        raise Module3Error("request matrix is not the frozen 25-case G1 scope")
    for request in requests:
        _require_sha(request["vcfSha256"], "matrix VCF")
        _require_sha(request["tbiSha256"], "matrix TBI")
        _hpo_hash(request["hpoTerms"])
    return requests


def execute_run(
    *,
    request_matrix: Path,
    exomiser_main_root: Path,
    exomiser_canary_root: Path,
    lirical_root: Path,
    output_root: Path,
    run_id: str,
    created_at: str,
    execution_commit: str,
    lock_outputs: bool = True,
) -> dict[str, Any]:
    run_root = output_root.resolve() / run_id
    if run_root.exists():
        raise Module3Error(f"run root already exists: {run_root}")
    run_root.mkdir(parents=True)
    cases_root = run_root / "cases"
    cases_root.mkdir()
    requests = _prepare_requests(request_matrix)
    case_records: list[dict[str, Any]] = []
    totals: dict[str, int] = defaultdict(int)
    run_locked_at = created_at

    for request in requests:
        (
            exo_gene_path,
            exo_variant_path,
            exo_lock,
            exo_lock_path,
        ) = _discover_exomiser(
            request, exomiser_main_root, exomiser_canary_root
        )
        (
            lirical_projection_path,
            lirical_raw_tsv_path,
            projection,
            lirical_raw_lock,
            lirical_raw_locked_at,
        ) = _discover_lirical(request, lirical_root)
        raw_artifacts = _case_source_artifacts(
            request=request,
            exo_gene_path=exo_gene_path,
            exo_variant_path=exo_variant_path,
            exo_lock=exo_lock,
            lirical_projection_path=lirical_projection_path,
            lirical_raw_tsv_path=lirical_raw_tsv_path,
            lirical_projection=projection,
            lirical_raw_lock=lirical_raw_lock,
            lirical_raw_locked_at=lirical_raw_locked_at,
            exomiser_run_id=exo_lock_path.parents[1].name,
            lirical_run_id=lirical_root.name,
        )
        batch, summary = normalize_case(
            request=request,
            raw_artifacts=raw_artifacts,
            exomiser_gene_rows=_read_tsv(exo_gene_path),
            exomiser_variant_rows=_read_tsv(exo_variant_path),
            lirical_projection={
                key: value
                for key, value in projection.items()
                if not key.startswith("_")
            },
            disease_labels=_disease_labels(_read_lirical_tsv(lirical_raw_tsv_path)),
            created_at=created_at,
        )
        request_hash = hashlib.sha256(
            request["requestId"].encode("utf-8")
        ).hexdigest()
        case_directory = f"{request['selectionOrder']:02d}-{request_hash[:12]}"
        documents = _case_documents(
            batch,
            request,
            summary,
            created_at,
            {
                "exomiser": _sha256(exo_lock_path),
                "lirical": _sha256(
                    lirical_root / "manifest" / f"{request['requestId']}.lock.json"
                ),
            },
        )
        lock_record = _write_case(
            cases_root / case_directory, documents, run_locked_at
        )
        for key, value in summary.items():
            if isinstance(value, int):
                totals[key] += value
        case_records.append(
            {
                "selectionOrder": request["selectionOrder"],
                "caseIdentityHash": hashlib.sha256(
                    request["caseId"].encode("utf-8")
                ).hexdigest(),
                "requestIdentityHash": request_hash,
                "terminalStatus": "COMPLETED_LOCKED",
                "summary": summary,
                **lock_record,
            }
        )

    aggregate = {
        "schemaVersion": "module3-aggregate-structure-summary-v1",
        "status": "PASSED",
        "truthFree": True,
        "fusionApplied": False,
        "caseCount": len(case_records),
        "sourceCoverage": {
            "exomiserG1": len(case_records),
            "liricalG1Corrected": len(case_records),
        },
        "totals": dict(sorted(totals.items())),
    }
    per_case = {
        "schemaVersion": "module3-per-case-normalization-matrix-v1",
        "status": "COMPLETED",
        "truthFree": True,
        "fusionApplied": False,
        "cases": case_records,
    }
    batch_manifest = {
        "schemaVersion": "module3-batch-manifest-v1",
        "status": "COMPLETED_PENDING_DETERMINISM_AUDIT",
        "runId": run_id,
        "createdAt": created_at,
        "executionCommit": execution_commit,
        "normalizerVersion": MODULE3_VERSION,
        "module2ContractStatus": "MODULE2_UNIFIED_DIAGNOSTIC_HYPOTHESIS_CONTRACT_FROZEN",
        "truthFree": True,
        "fusionApplied": False,
        "caseCount": len(case_records),
        "sourcePolicy": {
            "exomiser": "locked G1 gene/variant TSV only",
            "lirical": "locked corrected-v2 projection and native disease TSV only",
            "evaluationArtifactsRead": False,
        },
        "hpoHashRule": HPO_HASH_RULE,
        "cases": [
            {
                "selectionOrder": item["selectionOrder"],
                "caseIdentityHash": item["caseIdentityHash"],
                "requestIdentityHash": item["requestIdentityHash"],
                "caseDirectory": item["caseDirectory"],
                "lockSha256": item["lockSha256"],
            }
            for item in case_records
        ],
    }
    _write_json(run_root / "aggregate-structure-summary.json", aggregate)
    _write_json(run_root / "per-case-normalization-matrix.json", per_case)
    _write_json(run_root / "batch-manifest.json", batch_manifest)

    canonical_hashes = {
        f"cases/{case['caseDirectory']}/{filename}": digest
        for case in case_records
        for filename, digest in case["contentHashes"].items()
    }
    _write_json(
        run_root / "canonical-content-hashes.json",
        {
            "schemaVersion": "module3-canonical-content-hashes-v1",
            "truthFree": True,
            "fusionApplied": False,
            "artifacts": canonical_hashes,
        },
    )
    if lock_outputs:
        for path in sorted(run_root.rglob("*"), reverse=True):
            if path.is_file():
                _set_read_only(path)
    return {
        "runRoot": str(run_root),
        "caseCount": len(case_records),
        "totals": dict(totals),
        "canonicalHashes": canonical_hashes,
        "locked": lock_outputs,
    }


def preflight_sources(
    *,
    request_matrix: Path,
    exomiser_main_root: Path,
    exomiser_canary_root: Path,
    lirical_root: Path,
) -> dict[str, Any]:
    requests = _prepare_requests(request_matrix)
    counts = {
        "exomiserGeneRows": 0,
        "exomiserVariantRows": 0,
        "liricalDiseaseRows": 0,
        "liricalDerivedGeneRows": 0,
    }
    for request in requests:
        exo_gene_path, exo_variant_path, _, _ = _discover_exomiser(
            request, exomiser_main_root, exomiser_canary_root
        )
        (
            _,
            lirical_raw_tsv_path,
            projection,
            _,
            _,
        ) = _discover_lirical(request, lirical_root)
        gene_rows = _read_tsv(exo_gene_path)
        variant_rows = _read_tsv(exo_variant_path)
        labels = _disease_labels(_read_lirical_tsv(lirical_raw_tsv_path))
        if any(
            (int(row["rank"]), row["diseaseId"]) not in labels
            for row in projection["nativeDiseaseRanks"]
        ):
            raise Module3Error(
                f"LIRICAL disease label binding incomplete: {request['requestId']}"
            )
        counts["exomiserGeneRows"] += len(gene_rows)
        counts["exomiserVariantRows"] += len(variant_rows)
        counts["liricalDiseaseRows"] += len(projection["nativeDiseaseRanks"])
        counts["liricalDerivedGeneRows"] += len(projection["geneRanks"])
    return {
        "status": "PASS",
        "truthFree": True,
        "caseCount": len(requests),
        "sourceCoverage": {"exomiserG1": 25, "liricalG1Corrected": 25},
        "counts": counts,
    }


def finalize_run(run_root: Path, locked_at: str) -> dict[str, Any]:
    if (run_root / "checksum-manifest.json").exists():
        raise Module3Error("run is already finalized")
    determinism_path = run_root / "deterministic-recalculation-audit.json"
    determinism = _json_load(determinism_path)
    if (
        determinism.get("status") != "PASSED"
        or determinism.get("canonicalContentHashesEqual") is not True
    ):
        raise Module3Error("deterministic recalculation has not passed")

    matrix_path = run_root / "per-case-normalization-matrix.json"
    matrix = _json_load(matrix_path)
    lock_hashes: dict[str, str] = {}
    for case in matrix["cases"]:
        case_root = run_root / "cases" / case["caseDirectory"]
        lock_path = case_root / "lock.json"
        lock = _json_load(lock_path)
        lock["lockedAt"] = locked_at
        _write_json(lock_path, lock)
        lock_hash = _sha256(lock_path)
        case["lockSha256"] = lock_hash
        lock_hashes[case["requestIdentityHash"]] = lock_hash
    _write_json(matrix_path, matrix)

    manifest_path = run_root / "batch-manifest.json"
    manifest = _json_load(manifest_path)
    for case in manifest["cases"]:
        case["lockSha256"] = lock_hashes[case["requestIdentityHash"]]
    manifest["status"] = "COMPLETED_LOCKED"
    manifest["lastOutputLockedAt"] = locked_at
    manifest["deterministicRecalculation"] = "PASSED"
    _write_json(manifest_path, manifest)

    artifacts = []
    for path in sorted(run_root.rglob("*")):
        if not path.is_file() or path.name == "checksum-manifest.json":
            continue
        artifacts.append(
            {
                "logicalPath": path.relative_to(run_root).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": _sha256(path),
            }
        )
    checksum_manifest = {
        "schemaVersion": "module3-checksum-manifest-v1",
        "status": "LOCKED",
        "truthFree": True,
        "fusionApplied": False,
        "lockedAt": locked_at,
        "artifactCount": len(artifacts),
        "artifacts": artifacts,
    }
    _write_json(run_root / "checksum-manifest.json", checksum_manifest)
    for path in sorted(run_root.rglob("*"), reverse=True):
        if path.is_file():
            _set_read_only(path)
    return {
        "status": "PASS",
        "runRoot": str(run_root.resolve()),
        "lockedAt": locked_at,
        "artifactCount": len(artifacts) + 1,
    }


def verify_run(run_root: Path) -> dict[str, Any]:
    manifest = _json_load(run_root / "batch-manifest.json")
    matrix = _json_load(run_root / "per-case-normalization-matrix.json")
    if manifest["caseCount"] != 25 or len(matrix["cases"]) != 25:
        raise Module3Error("run does not cover 25 cases")
    file_count = 0
    byte_count = 0
    writable: list[str] = []
    for path in run_root.rglob("*"):
        if not path.is_file():
            continue
        file_count += 1
        byte_count += path.stat().st_size
        if not _is_read_only(path):
            writable.append(str(path))
    if writable:
        raise Module3Error(f"run contains writable artifacts: {len(writable)}")
    for case in matrix["cases"]:
        case_root = run_root / "cases" / case["caseDirectory"]
        lock = _json_load(case_root / "lock.json")
        for artifact in lock["artifacts"]:
            path = case_root / artifact["logicalPath"]
            _verify_file(path, artifact["sha256"], "locked Module 3 output")
        batch = _json_load(case_root / "diagnostic-hypothesis-batch.json")
        contract.validate_batch(batch)
    return {
        "status": "PASS",
        "caseCount": 25,
        "fileCount": file_count,
        "bytes": byte_count,
        "readOnlyFiles": file_count,
    }


def compare_runs(primary: Path, recalculated: Path) -> dict[str, Any]:
    first = _json_load(primary / "canonical-content-hashes.json")["artifacts"]
    second = _json_load(recalculated / "canonical-content-hashes.json")["artifacts"]
    if first != second:
        changed = sorted(set(first) ^ set(second))
        changed.extend(key for key in first.keys() & second.keys() if first[key] != second[key])
        raise Module3Error(f"deterministic recalculation mismatch: {len(changed)}")
    return {
        "schemaVersion": "module3-deterministic-recalculation-audit-v1",
        "status": "PASSED",
        "truthFree": True,
        "fusionApplied": False,
        "artifactCount": len(first),
        "canonicalContentHashesEqual": True,
    }


def cleanup_derived_temp(allowed_root: Path, target: Path) -> dict[str, Any]:
    root = allowed_root.resolve()
    resolved_target = target.resolve()
    if (
        resolved_target == root
        or root not in resolved_target.parents
        or not any(
            marker in resolved_target.name or marker in root.name
            for marker in ("failed-attempt", "recalculation")
        )
    ):
        raise Module3Error(f"unsafe temporary cleanup target: {resolved_target}")
    if not resolved_target.is_dir():
        raise Module3Error(f"temporary cleanup target is not a directory: {resolved_target}")
    files = list(resolved_target.rglob("*"))
    bytes_removed = sum(path.stat().st_size for path in files if path.is_file())
    file_count = sum(path.is_file() for path in files)
    shutil.rmtree(resolved_target)
    return {
        "status": "PASS",
        "removed": str(resolved_target),
        "fileCount": file_count,
        "bytes": bytes_removed,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("--request-matrix", type=Path, required=True)
    run_parser.add_argument("--exomiser-main-root", type=Path, required=True)
    run_parser.add_argument("--exomiser-canary-root", type=Path, required=True)
    run_parser.add_argument("--lirical-root", type=Path, required=True)
    run_parser.add_argument("--output-root", type=Path, required=True)
    run_parser.add_argument("--run-id", required=True)
    run_parser.add_argument("--created-at", required=True)
    run_parser.add_argument("--execution-commit", required=True)
    run_parser.add_argument("--no-lock", action="store_true")

    preflight_parser = subparsers.add_parser("preflight")
    preflight_parser.add_argument("--request-matrix", type=Path, required=True)
    preflight_parser.add_argument("--exomiser-main-root", type=Path, required=True)
    preflight_parser.add_argument("--exomiser-canary-root", type=Path, required=True)
    preflight_parser.add_argument("--lirical-root", type=Path, required=True)

    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("run_root", type=Path)

    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("primary", type=Path)
    compare_parser.add_argument("recalculated", type=Path)
    compare_parser.add_argument("--output", type=Path)

    finalize_parser = subparsers.add_parser("finalize")
    finalize_parser.add_argument("run_root", type=Path)
    finalize_parser.add_argument("--locked-at", required=True)

    cleanup_parser = subparsers.add_parser("cleanup-derived-temp")
    cleanup_parser.add_argument("--allowed-root", type=Path, required=True)
    cleanup_parser.add_argument("--target", type=Path, required=True)

    args = parser.parse_args()
    try:
        if args.command == "run":
            result = execute_run(
                request_matrix=args.request_matrix,
                exomiser_main_root=args.exomiser_main_root,
                exomiser_canary_root=args.exomiser_canary_root,
                lirical_root=args.lirical_root,
                output_root=args.output_root,
                run_id=args.run_id,
                created_at=args.created_at,
                execution_commit=args.execution_commit,
                lock_outputs=not args.no_lock,
            )
        elif args.command == "preflight":
            result = preflight_sources(
                request_matrix=args.request_matrix,
                exomiser_main_root=args.exomiser_main_root,
                exomiser_canary_root=args.exomiser_canary_root,
                lirical_root=args.lirical_root,
            )
        elif args.command == "verify":
            result = verify_run(args.run_root)
        elif args.command == "finalize":
            result = finalize_run(args.run_root, args.locked_at)
        elif args.command == "cleanup-derived-temp":
            result = cleanup_derived_temp(args.allowed_root, args.target)
        else:
            result = compare_runs(args.primary, args.recalculated)
            if args.output:
                _write_json(args.output, result)
        display = dict(result)
        if "canonicalHashes" in display:
            display["canonicalArtifactCount"] = len(display.pop("canonicalHashes"))
        print(json.dumps(display, ensure_ascii=False))
        return 0
    except (Module3Error, contract.ContractError, OSError, json.JSONDecodeError) as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
