#!/usr/bin/env python3
"""Validate the minimal E3-C1 AlphaMissense Supporting-only contracts."""

from __future__ import annotations

import copy
import json
import re
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Mapping

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.e3_p1a import validate_acmg_rule_registry_v2 as v2_registry
from scripts.evidence import acmg_evidence_v2_contract as e1


ACMG_RESOURCE_ROOT = (
    REPO_ROOT / "varvis-server" / "src" / "main" / "resources" / "acmg"
)
PROFILE_PATH = ACMG_RESOURCE_ROOT / "alphamissense-pp3-bp4-supporting-profile-v1.json"
PREDECESSOR_REGISTRY_PATH = (
    ACMG_RESOURCE_ROOT
    / "rule-registry-v2"
    / "acmg-rule-registry-e3-p1b-2026-09-02.json"
)
REGISTRY_PATH = (
    ACMG_RESOURCE_ROOT
    / "rule-registry-v2"
    / "acmg-rule-registry-e3-c1-2026-09-02.json"
)
SMOKE_PATH = (
    REPO_ROOT / "docs" / "execution" / "E3_C1_ALPHAMISSENSE_PP3_BP4_SMOKE.json"
)

PREDECESSOR_REGISTRY_CANONICAL_SHA256 = (
    "56356ec38a36e098348b78ba2a20c600ac6e602282f912329ce6aca81f949451"
)
EXPECTED_PROFILE_ID = "ALPHAMISSENSE_PP3_BP4_SUPPORTING"
EXPECTED_PROFILE_VERSION = "1.0.0"
EXPECTED_REGISTRY_ID = "acmg-rule-registry-e3-c1-2026-09-02"
AUTHORITY_SOURCE_ID = "authority-clingen-svi-pp3-bp4-extension-2025"
AUTHORITY_SNAPSHOT_SHA256 = (
    "f14b420b96ee479b87f7acf18c58a1c7fd006a5d888403bc0ea6425b2fcd619f"
)
IMPLEMENTED_CODES = ("PP3", "BP4")
CRITERION_IDS = ("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4")


def _fail(message: str) -> None:
    raise e1.ContractError(message)


def _expect_exact(actual: Any, expected: Any, field: str) -> None:
    if actual != expected:
        _fail(f"AlphaMissense profile {field} changed from the frozen contract")


def _decimal(raw_value: Any, field: str) -> Decimal:
    if not isinstance(raw_value, str):
        _fail(f"{field} must preserve the original decimal string")
    try:
        value = Decimal(raw_value)
    except InvalidOperation as exc:
        raise e1.ContractError(f"{field} is not a decimal") from exc
    if not value.is_finite():
        _fail(f"{field} must be a finite decimal")
    return value


def validate_successor_registry(
    successor: Mapping[str, Any], predecessor: Mapping[str, Any]
) -> None:
    """Validate the v2 schema and immediate E3-P1B -> E3-C1 PP3/BP4 delta."""

    v2_registry._validate_schema(successor)
    if e1.canonical_sha256(predecessor) != PREDECESSOR_REGISTRY_CANONICAL_SHA256:
        _fail("immediate predecessor registry canonical SHA-256 mismatch")
    if successor["registryId"] != EXPECTED_REGISTRY_ID:
        _fail("E3-C1 registry ID mismatch")
    if successor["registryId"] == predecessor["registryId"]:
        _fail("successor registry ID must differ from its immediate predecessor")
    if successor["lineage"] != predecessor["lineage"]:
        _fail("successor frozen E1 schema lineage changed")

    expected = copy.deepcopy(dict(predecessor))
    expected["registryId"] = EXPECTED_REGISTRY_ID
    for criterion in expected["criteria"]:
        if criterion["code"] in IMPLEMENTED_CODES:
            criterion["implementationStatus"] = "V2_IMPLEMENTED"
    if successor != expected:
        for actual, wanted in zip(successor["criteria"], expected["criteria"], strict=True):
            if actual != wanted:
                code = actual.get("code", "UNKNOWN")
                if code in IMPLEMENTED_CODES:
                    _fail(f"{code} changed beyond implementationStatus")
                _fail(f"unauthorized successor criterion change: {code}")
        _fail("successor registry changed beyond ID and PP3/BP4 implementationStatus")


def _validate_authority_binding(
    profile: Mapping[str, Any], authority_catalog: Mapping[str, Any]
) -> None:
    sources = [
        source
        for source in authority_catalog["sources"]
        if source.get("authoritySourceId") == AUTHORITY_SOURCE_ID
    ]
    if len(sources) != 1:
        _fail("frozen E1 AlphaMissense authority source is missing or duplicated")
    source = sources[0]
    _expect_exact(source["publicationStatus"], "RELEASED", "authority publication status")
    _expect_exact(source["normativeEligibility"], "ELIGIBLE", "authority eligibility")
    _expect_exact(source["documentVersion"], "2025 publication", "authority version")
    _expect_exact(source["snapshotSha256"], AUTHORITY_SNAPSHOT_SHA256, "authority snapshot")
    _expect_exact(source["sourceLocator"]["sourceRecordId"], "PMID:40084623", "authority record")
    _expect_exact(
        source["sourceLocator"]["artifactSha256"],
        AUTHORITY_SNAPSHOT_SHA256,
        "authority artifact",
    )
    _expect_exact(
        profile["authorityBindings"],
        [
            {
                "authoritySourceId": AUTHORITY_SOURCE_ID,
                "documentVersion": "2025 publication",
                "sourceRecordId": "PMID:40084623",
                "snapshotSha256": AUTHORITY_SNAPSHOT_SHA256,
            }
        ],
        "authorityBindings",
    )


def validate_profile(
    profile: Mapping[str, Any],
    registry: Mapping[str, Any],
    authority_catalog: Mapping[str, Any],
) -> None:
    """Validate the closed Supporting-only AlphaMissense calibration profile."""

    expected_root_fields = {
        "profileSchemaVersion",
        "profileId",
        "profileVersion",
        "status",
        "canonicalizationProfile",
        "authorityCutoff",
        "asOf",
        "scope",
        "lineage",
        "predictor",
        "resources",
        "activation",
        "registryBinding",
        "authorityBindings",
        "applicability",
        "matching",
        "fieldContract",
        "numericSemantics",
        "thresholds",
        "strengthPolicy",
        "evaluation",
        "failurePolicy",
    }
    if set(profile) != expected_root_fields:
        _fail("AlphaMissense profile root fields do not match the frozen contract")

    _expect_exact(profile["profileSchemaVersion"], "alphamissense-pp3-bp4-profile-v1", "schema")
    _expect_exact(profile["profileId"], EXPECTED_PROFILE_ID, "ID")
    _expect_exact(profile["profileVersion"], EXPECTED_PROFILE_VERSION, "version")
    _expect_exact(profile["status"], "RUNTIME_EVALUATION_SLICE", "status")
    _expect_exact(profile["canonicalizationProfile"], "varvis-json-c14n-v1", "canonicalization")
    _expect_exact(profile["authorityCutoff"], "2026-08-31", "authority cutoff")
    _expect_exact(profile["asOf"], "2026-09-02T00:00:00Z", "asOf")
    _expect_exact(profile["scope"], "GERMLINE_MENDELIAN_SMALL_SEQUENCE_VARIANT", "scope")
    base_registry = e1.load_json(e1.RULE_REGISTRY_PATH)
    expected_lineage = {
        "authorityCatalogId": authority_catalog["catalogId"],
        "authorityCatalogSchemaVersion": authority_catalog["schemaVersion"],
        "authorityCatalogCanonicalSha256": e1.canonical_sha256(authority_catalog),
        "baseRegistryId": base_registry["registryId"],
        "baseRegistrySchemaVersion": base_registry["schemaVersion"],
        "baseRegistrySha256": e1.canonical_sha256(base_registry),
    }
    _expect_exact(profile["lineage"], expected_lineage, "lineage")
    if registry["lineage"] != {
        "baseRegistryId": expected_lineage["baseRegistryId"],
        "baseSchemaVersion": expected_lineage["baseRegistrySchemaVersion"],
        "baseRegistrySha256": expected_lineage["baseRegistrySha256"],
    }:
        _fail("AlphaMissense profile and successor registry E1 lineage disagree")
    _expect_exact(
        profile["predictor"],
        {
            "name": "AlphaMissense",
            "providerSource": "ALPHAMISSENSE",
            "resourceVersion": "2023-08-03",
            "exclusiveComputationalPredictor": True,
        },
        "predictor",
    )
    expected_identity = {
        "provider": "ALPHAMISSENSE",
        "assembly": "GRCh38",
        "officialReleaseDate": "2023-08-03",
        "officialSourceLocator": (
            "https://storage.googleapis.com/dm_alphamissense/AlphaMissense_hg38.tsv.gz"
        ),
        "officialSourceArtifact": {
            "fileName": "AlphaMissense_hg38.tsv.gz",
            "sha256": "0516cfd71c0767ac8f9c469252d429000e94e02c008b6e3a46d4b4646fcd3475",
            "sizeBytes": 642961469,
        },
        "runtimeDataArtifact": {
            "format": "BGZF_TSV",
            "sha256": "9657246d5ed328ea13e28432130847cd11d2662657ed8ec56a65c08e6984c4d3",
            "sizeBytes": 628407716,
        },
        "runtimeIndexArtifact": {
            "format": "TABIX_TBI",
            "sha256": "9340e53c57b8a14a92e5d6d14d786714f65447d64a1c536b11adc2dd29dae58c",
            "sizeBytes": 684511,
        },
        "derivation": "REPACK_OFFICIAL_TSV_AS_BGZF_AND_TABIX_INDEX",
    }
    _expect_exact(
        profile["resources"],
        [
            {
                "resourceId": "ALPHAMISSENSE_2023_08_03_GRCH38_TABIX",
                "provider": "ALPHAMISSENSE",
                "assembly": "GRCh38",
                "resourceVersion": "2023-08-03",
                "identityFingerprintSha256": e1.canonical_sha256(expected_identity),
                "identity": expected_identity,
            }
        ],
        "resources",
    )
    _expect_exact(
        profile["activation"],
        {
            "runtimeBound": True,
            "evaluationEnabled": True,
            "patientFlowEnabled": False,
            "classificationEnabled": False,
        },
        "activation",
    )

    registry_binding = {
        "registryId": EXPECTED_REGISTRY_ID,
        "registrySchemaVersion": "acmg-rule-registry-v2",
        "registrySha256": e1.sha256_file(REGISTRY_PATH),
        "registryClasspathResource": (
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-c1-2026-09-02.json"
        ),
        "criterionIds": list(CRITERION_IDS),
    }
    _expect_exact(profile["registryBinding"], registry_binding, "registryBinding")
    _expect_exact(registry["registryId"], EXPECTED_REGISTRY_ID, "bound registry ID")
    _validate_authority_binding(profile, authority_catalog)

    _expect_exact(
        profile["applicability"],
        {
            "assembly": "GRCh38",
            "nativeAssemblyOnly": True,
            "requiredAssemblyLineage": "NATIVE",
            "variantType": "SNV",
            "consequence": "missense_variant",
            "nonMatchingOutcome": "NOT_APPLICABLE",
        },
        "applicability",
    )
    _expect_exact(
        profile["matching"],
        {
            "exactAlleleRequired": True,
            "transcriptSelectionSource": "VEP_EVIDENCE_CONTEXT",
            "transcriptVersionRequired": True,
            "proteinIdentityRequired": True,
            "aminoAcidChangeRequired": True,
            "transcriptAndProteinExactMatchRequired": True,
            "scoreBasedTranscriptSelectionAllowed": False,
            "multiTranscriptMaximumSelectionAllowed": False,
            "missingOrConflictingMatchOutcome": "NOT_EVALUATED",
        },
        "matching",
    )
    _expect_exact(
        profile["fieldContract"],
        {
            "scoreMeasurementId": "alphamissense.amPathogenicity",
            "requiredDetailFields": [
                "alphamissense.exactAlleleMatch",
                "alphamissense.transcriptProteinMatch",
                "alphamissense.transcriptId",
                "alphamissense.proteinVariant",
                "alphamissense.uniprotId",
            ],
            "requiredTrueFields": [
                "alphamissense.exactAlleleMatch",
                "alphamissense.transcriptProteinMatch",
            ],
            "transcriptIdentityField": "alphamissense.transcriptId",
            "proteinChangeField": "alphamissense.proteinVariant",
            "proteinIdentityField": "alphamissense.uniprotId",
        },
        "fieldContract",
    )
    _expect_exact(
        profile["numericSemantics"],
        {
            "rawLexemePreserved": True,
            "decimalRuntimeType": "java.math.BigDecimal",
            "implicitRoundingAllowed": False,
            "binaryFloatingPointAllowedForDecisions": False,
        },
        "numericSemantics",
    )

    thresholds = profile["thresholds"]
    _expect_exact(
        thresholds,
        {
            "benignMaxInclusive": {
                "rawValue": "0.169",
                "comparisonOperator": "LESS_THAN_OR_EQUAL",
                "bp4Result": "MET",
                "pp3Result": "NOT_MET",
            },
            "pathogenicMinInclusive": {
                "rawValue": "0.792",
                "comparisonOperator": "GREATER_THAN_OR_EQUAL",
                "pp3Result": "MET",
                "bp4Result": "NOT_MET",
            },
            "intermediateInterval": {
                "lowerBoundRawValue": "0.169",
                "lowerBoundExclusive": True,
                "upperBoundRawValue": "0.792",
                "upperBoundExclusive": True,
                "pp3Result": "NOT_MET",
                "bp4Result": "NOT_MET",
            },
        },
        "thresholds",
    )
    benign_threshold = _decimal(
        thresholds["benignMaxInclusive"]["rawValue"], "benign threshold"
    )
    if benign_threshold != Decimal("0.169"):
        _fail("AlphaMissense benign threshold changed")
    pathogenic_threshold = _decimal(
        thresholds["pathogenicMinInclusive"]["rawValue"], "pathogenic threshold"
    )
    if pathogenic_threshold != Decimal("0.792"):
        _fail("AlphaMissense pathogenic threshold changed")

    _expect_exact(
        profile["strengthPolicy"],
        {
            "strengthCap": "SUPPORTING",
            "publishedStrongerIntervalsConservativelyDowngraded": True,
            "moderateEnabled": False,
            "strongEnabled": False,
            "threePointSystemEnabled": False,
        },
        "strengthPolicy",
    )
    _expect_exact(
        profile["evaluation"],
        {
            "criteria": [
                {
                    "criterionId": "ACMG_AMP_2015:PP3",
                    "currentDesignation": "PP3",
                    "baseStrength": "SUPPORTING",
                    "appliedStrengthCap": "SUPPORTING",
                },
                {
                    "criterionId": "ACMG_AMP_2015:BP4",
                    "currentDesignation": "BP4",
                    "baseStrength": "SUPPORTING",
                    "appliedStrengthCap": "SUPPORTING",
                },
            ],
            "mutualExclusion": {"criteriaMayBothBeMet": False},
        },
        "evaluation",
    )
    _expect_exact(
        profile["failurePolicy"],
        {
            "notEvaluatedConditions": [
                "SCORE_MISSING",
                "NO_RECORD",
                "RESOURCE_FAILED",
                "RESOURCE_TIMEOUT",
                "CONTEXT_INCOMPLETE",
                "TRANSCRIPT_PROTEIN_MATCH_MISSING",
                "TRANSCRIPT_PROTEIN_MATCH_CONFLICT",
            ],
            "outcome": "NOT_EVALUATED",
        },
        "failurePolicy",
    )

    serialized = json.dumps(profile, ensure_ascii=False)
    if re.search(r"(?i)(?:^|[\"\s])[a-z]:[\\/]", serialized) or "/mnt/" in serialized:
        _fail("AlphaMissense profile contains a machine-local absolute path")


def validate_committed_successors() -> dict[str, Any]:
    predecessor = e1.load_json(PREDECESSOR_REGISTRY_PATH)
    registry = e1.load_json(REGISTRY_PATH)
    profile = e1.load_json(PROFILE_PATH)
    authority_catalog = e1.load_json(e1.AUTHORITY_PATH)
    validate_successor_registry(registry, predecessor)
    validate_profile(profile, registry, authority_catalog)
    return {
        "status": "PASS",
        "profileId": profile["profileId"],
        "profileVersion": profile["profileVersion"],
        "profileCanonicalSha256": e1.canonical_sha256(profile),
        "registryId": registry["registryId"],
        "registryCanonicalSha256": e1.canonical_sha256(registry),
        "advancedCriteria": list(IMPLEMENTED_CODES),
        "strengthCap": profile["strengthPolicy"]["strengthCap"],
    }


def main() -> int:
    try:
        result = validate_committed_successors()
    except (e1.ContractError, KeyError, OSError, TypeError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
