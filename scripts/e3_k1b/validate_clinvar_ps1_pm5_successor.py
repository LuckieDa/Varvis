#!/usr/bin/env python3
"""Validate the minimal E3-K1B ClinVar missense PS1/PM5 contracts."""

from __future__ import annotations

import copy
import json
import re
import sys
from pathlib import Path
from typing import Any, Mapping

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.e3_p1a import validate_acmg_rule_registry_v2 as v2_registry
from scripts.evidence import acmg_evidence_v2_contract as e1


ACMG_ROOT = REPO_ROOT / "varvis-server" / "src" / "main" / "resources" / "acmg"
PREDECESSOR_REGISTRY_PATH = (
    ACMG_ROOT / "rule-registry-v2" / "acmg-rule-registry-e3-c3-2026-09-02.json"
)
REGISTRY_PATH = (
    ACMG_ROOT / "rule-registry-v2" / "acmg-rule-registry-e3-k1b-2026-09-04.json"
)
PROFILE_PATH = ACMG_ROOT / "clinvar-missense-ps1-pm5-profile-v1.json"
COMPARATOR_MANIFEST_PATH = ACMG_ROOT / "clinvar-comparator-sidecar-manifest-v1.json"

REGISTRY_SCHEMA_RAW_SHA256 = (
    "b1904ee863e0c2bcb6d3e4324214e8873d79cf71647a3bd9badbee5e1784ce07"
)
PREDECESSOR_REGISTRY_RAW_SHA256 = (
    "5fb10c64a0d157708529e3617247375170117ee25d64f5a970efd484008b6f64"
)
PREDECESSOR_REGISTRY_CANONICAL_SHA256 = (
    "1816c94310d9fc320143d02dd3f86e97155f9dfaa818a52145db4a5429d29942"
)
REGISTRY_RAW_SHA256 = (
    "4f2e96746541bf6269a9d06a919d3eb0759ee3bf079f06f4868a23561514e9ed"
)
REGISTRY_CANONICAL_SHA256 = (
    "a9a5485463ace52e6a21ded2e9a129a016ddec2aa3ebb5c9086c7987c5e46c1f"
)
COMPARATOR_MANIFEST_RAW_SHA256 = (
    "a74d57c58ddff614f5efc0ab9e4cc2bb5c20494ac7d88ee38aa5a03d9c84561e"
)
COMPARATOR_MANIFEST_CANONICAL_SHA256 = (
    "9e3a29f9f9638394ecab632dd2cb3da4611c18b3c8b61e3f7cb3ac6b50aaaf71"
)
PROFILE_RAW_SHA256 = (
    "4b3e43f206c28c23342680f56f767250b30e7cc2dfb670bbacd22e5c4fac7662"
)
PROFILE_CANONICAL_SHA256 = (
    "69a886bab3933796b4bc14b9b60960dd3da65cecce206b9244cbf2ba9674d79e"
)

EXPECTED_REGISTRY_ID = "acmg-rule-registry-e3-k1b-2026-09-04"
EXPECTED_PROFILE_ID = "CLINVAR_MISSENSE_PS1_PM5"
PS1 = "ACMG_AMP_2015:PS1"
PM5 = "ACMG_AMP_2015:PM5"


def _fail(message: str) -> None:
    raise e1.ContractError(message)


def _expect(actual: Any, expected: Any, field: str) -> None:
    if actual != expected:
        _fail(f"ClinVar PS1/PM5 {field} changed from the frozen profile")


def validate_successor_registry(
    successor: Mapping[str, Any], predecessor: Mapping[str, Any]
) -> None:
    """Allow only registry identity plus the PS1/PM5 status transition."""

    if e1.sha256_file(v2_registry.SCHEMA_PATH) != REGISTRY_SCHEMA_RAW_SHA256:
        _fail("acmg-rule-registry-v2 schema changed")
    if e1.sha256_file(PREDECESSOR_REGISTRY_PATH) != PREDECESSOR_REGISTRY_RAW_SHA256:
        _fail("immediate predecessor registry raw SHA-256 mismatch")
    if e1.canonical_sha256(predecessor) != PREDECESSOR_REGISTRY_CANONICAL_SHA256:
        _fail("immediate predecessor registry canonical SHA-256 mismatch")

    v2_registry._validate_schema(successor)
    expected = copy.deepcopy(dict(predecessor))
    expected["registryId"] = EXPECTED_REGISTRY_ID
    for criterion in expected["criteria"]:
        if criterion["code"] in {"PS1", "PM5"}:
            criterion["implementationStatus"] = "V2_IMPLEMENTED"

    if successor != expected:
        if successor.get("registryId") != EXPECTED_REGISTRY_ID:
            _fail("E3-K1B registry ID mismatch")
        if successor.get("lineage") != predecessor.get("lineage"):
            _fail("successor frozen E1 registry lineage changed")
        successor_criteria = successor.get("criteria", [])
        for actual, wanted in zip(
            successor_criteria, expected["criteria"], strict=False
        ):
            if actual != wanted:
                code = actual.get("code", "UNKNOWN")
                if code in {"PS1", "PM5"}:
                    _fail(f"{code} changed beyond implementationStatus")
                _fail(f"unauthorized successor criterion change: {code}")
        _fail("successor registry changed beyond ID and PS1/PM5 status")


def validate_profile(
    profile: Mapping[str, Any], registry: Mapping[str, Any]
) -> None:
    """Validate the closed, executable K1B missense profile."""

    if profile.get("profileSha256") is not None:
        _fail("profile must not self-hash")
    expected_root = {
        "profileSchemaVersion",
        "profileId",
        "profileVersion",
        "status",
        "canonicalizationProfile",
        "authorityCutoff",
        "asOf",
        "scope",
        "lineage",
        "activation",
        "registryBinding",
        "resources",
        "applicability",
        "assertionEligibility",
        "criteria",
        "evaluationPolicy",
        "failurePolicy",
    }
    _expect(set(profile), expected_root, "root fields")
    _expect(
        {
            "profileSchemaVersion": profile["profileSchemaVersion"],
            "profileId": profile["profileId"],
            "profileVersion": profile["profileVersion"],
            "status": profile["status"],
            "canonicalizationProfile": profile["canonicalizationProfile"],
            "authorityCutoff": profile["authorityCutoff"],
            "asOf": profile["asOf"],
            "scope": profile["scope"],
        },
        {
            "profileSchemaVersion": "clinvar-missense-ps1-pm5-profile-v1",
            "profileId": EXPECTED_PROFILE_ID,
            "profileVersion": "1.0.0",
            "status": "RUNTIME_EVALUATION_SLICE",
            "canonicalizationProfile": "varvis-json-c14n-v1",
            "authorityCutoff": "2026-08-31",
            "asOf": "2026-09-04T00:00:00Z",
            "scope": "GERMLINE_MENDELIAN_SMALL_SEQUENCE_VARIANT",
        },
        "identity",
    )
    _expect(
        profile["lineage"],
        {
            "predecessorRegistryId": "acmg-rule-registry-e3-c3-2026-09-02",
            "predecessorRegistryCanonicalSha256": (
                PREDECESSOR_REGISTRY_CANONICAL_SHA256
            ),
            "predecessorRegistryRawSha256": PREDECESSOR_REGISTRY_RAW_SHA256,
            "comparatorManifestVersion": (
                "clinvar-comparator-sidecar-manifest-v1"
            ),
            "comparatorManifestCanonicalSha256": (
                COMPARATOR_MANIFEST_CANONICAL_SHA256
            ),
            "comparatorManifestRawSha256": COMPARATOR_MANIFEST_RAW_SHA256,
        },
        "lineage",
    )
    _expect(
        profile["activation"],
        {
            "runtimeBound": True,
            "evaluationEnabled": True,
            "patientFlowEnabled": False,
            "classificationEnabled": False,
        },
        "activation",
    )
    _expect(
        profile["registryBinding"],
        {
            "registryId": EXPECTED_REGISTRY_ID,
            "registrySchemaVersion": "acmg-rule-registry-v2",
            "registrySha256": REGISTRY_RAW_SHA256,
            "registryClasspathResource": (
                "/acmg/rule-registry-v2/"
                "acmg-rule-registry-e3-k1b-2026-09-04.json"
            ),
            "criterionIds": [PS1, PM5],
        },
        "registry binding",
    )
    _expect(
        profile["resources"],
        [
            {
                "role": (
                    "BOUNDED_COMPARATOR_DISCOVERY_AND_ALLELE_IDENTITY_"
                    "CROSSCHECK_ONLY"
                ),
                "resourceId": "CLINVAR_2026_03_21_GRCH38",
                "resourceVersion": "ClinVar 2026-03-21 GRCh38",
                "assembly": "GRCh38",
                "identityFingerprintSha256": (
                    "20125a3041d57b5200f761c67f678cb85bdc122ef2d98b533e031986f1f7f836"
                ),
            },
            {
                "role": "SELECTED_TRANSCRIPT_PROTEIN_RELATION",
                "resourceId": "VEP_116_GRCH38",
                "resourceVersion": "VEP 116.0 / cache 116_GRCh38",
                "assembly": "GRCh38",
                "identityFingerprintSha256": (
                    "43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed"
                ),
            },
            {
                "role": "CONDITION_LINKED_ASSERTION_SEMANTICS_ONLY",
                "resourceId": "CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38",
                "resourceVersion": (
                    "ClinVar 2026-03-05 monthly tab-delimited archive"
                ),
                "assembly": "GRCh38",
                "identityFingerprintSha256": (
                    "e0f51a58a2ed011e765438411c1c44378aa0352a6404f1e79393fbd8e6e5cfde"
                ),
            },
        ],
        "resource bindings",
    )

    criteria = {item["criterionId"]: item for item in profile["criteria"]}
    _expect(set(criteria), {PS1, PM5}, "criterion set")
    _expect(criteria[PS1]["requiredProteinRelation"], "SAME_AMINO_ACID_CHANGE", "PS1 relation")
    _expect(criteria[PS1]["baseStrength"], "STRONG", "PS1 strength")
    _expect(criteria[PS1]["appliedStrength"], "STRONG", "PS1 applied strength")
    _expect(criteria[PS1]["ruleBasis"], "ORIGINAL_2015_NON_SPLICING_MISSENSE", "PS1 rule basis")
    _expect(criteria[PM5]["requiredProteinRelation"], "SAME_RESIDUE_DIFFERENT_AMINO_ACID", "PM5 relation")
    _expect(criteria[PM5]["baseStrength"], "MODERATE", "PM5 strength")
    _expect(criteria[PM5]["appliedStrength"], "MODERATE", "PM5 applied strength")
    _expect(criteria[PM5]["ruleBasis"], "ORIGINAL_2015_NON_SPLICING_MISSENSE", "PM5 rule basis")

    registry_criteria = {item["criterionId"]: item for item in registry["criteria"]}
    for criterion_id in (PS1, PM5):
        registered = registry_criteria[criterion_id]
        bound = criteria[criterion_id]
        _expect(registered["implementationStatus"], "V2_IMPLEMENTED", f"{criterion_id} registry status")
        _expect(bound["currentDesignation"], registered["currentGeneral"]["designation"], f"{criterion_id} designation")
        _expect(bound["baseStrength"], registered["currentGeneral"]["strength"], f"{criterion_id} registry strength")
        _expect(set(bound["authoritySourceIds"]), set(registered["currentGeneral"]["authoritySourceIds"]), f"{criterion_id} authorities")
        _expect(set(bound["original2015AuthoritySourceIds"]), set(registered["original2015"]["authoritySourceIds"]), f"{criterion_id} original-2015 authorities")

    _expect(
        profile["applicability"],
        {
            "assembly": "GRCh38",
            "variantType": "SNV",
            "simpleAlleleRequired": True,
            "selectedConsequenceRequired": "missense_variant",
            "splicingEffectRouteExcluded": True,
            "requiredContextFields": [
                "variant.evidenceQueryAllele",
                "gene.symbol",
                "disease.mondoId",
                "transcript.accession",
                "transcript.version",
            ],
            "outsideProfileOutcome": "NOT_APPLICABLE",
        },
        "applicability",
    )
    _expect(
        profile["assertionEligibility"],
        {
            "sourceUnit": "SINGLE_CONDITION_LINKED_SCV_ASSERTION",
            "crossScvRcvFieldAssemblyAllowed": False,
            "conditionIdentifier": {
                "contextNamespace": "MONDO",
                "acceptedSourceForms": [
                    "MONDO:<digits>",
                    "MONDO:MONDO:<digits>",
                ],
                "doublePrefixNormalization": "REMOVE_ONE_LEADING_MONDO_PREFIX",
                "match": "EXACT_AFTER_BOUNDED_NORMALIZATION",
                "forbiddenMatching": [
                    "CONDITION_NAME_FUZZY",
                    "ALIAS",
                    "TEXT_CONTAINS",
                    "ONTOLOGY_ANCESTOR_DESCENDANT",
                    "CROSS_ONTOLOGY_INFERENCE",
                ],
            },
            "classification": {"acceptedExactValues": ["Pathogenic"]},
            "reviewStatus": {
                "acceptedExactValues": [
                    "reviewed by expert panel",
                    "practice guideline",
                ]
            },
            "requiredAssertionFields": [
                "scvAccession",
                "conditionIdentifiers",
                "classification",
                "reviewStatus",
                "dateLastEvaluated",
                "sourceLocator",
            ],
            "geneMatch": "EXACT_SYMBOL",
        },
        "assertion eligibility",
    )
    _expect(
        profile["evaluationPolicy"],
        {
            "criteriaEvaluatedIndependently": True,
            "differentEligibleComparatorsMaySatisfyBothCriteria": True,
            "multipleComparatorsIncreaseStrength": False,
            "successfulQueryWithoutEligibleComparatorOutcome": "NOT_MET",
            "sameConditionEligibleDirectionConflict": {
                "outcome": "NOT_EVALUATED",
                "conflict": "PRESENT",
                "review": "REQUIRED",
            },
            "recordLevelConflictFromOtherConditionAffectsExactCondition": False,
            "combinationPerformed": False,
            "classificationPerformed": False,
        },
        "evaluation policy",
    )
    _expect(
        profile["failurePolicy"],
        {
            "resourceFailure": "NOT_EVALUATED",
            "resourceIdentityMismatch": "NOT_EVALUATED",
            "profileOrRegistryMismatch": "NOT_EVALUATED",
            "missingRequiredContext": "NOT_EVALUATED",
            "missingRequiredAssertionField": "NOT_EVALUATED",
            "unparseableCondition": "NOT_EVALUATED",
            "sameConditionEligibleDirectionConflict": "NOT_EVALUATED",
        },
        "failure policy",
    )

    serialized = json.dumps(profile, ensure_ascii=False)
    if re.search(r'(?i)(?:^|["\s])[a-z]:[\\/]', serialized) or "/mnt/" in serialized:
        _fail("ClinVar PS1/PM5 profile contains a machine-local absolute path")


def validate_committed_contracts() -> dict[str, Any]:
    predecessor = e1.load_json(PREDECESSOR_REGISTRY_PATH)
    registry = e1.load_json(REGISTRY_PATH)
    profile = e1.load_json(PROFILE_PATH)
    manifest = e1.load_json(COMPARATOR_MANIFEST_PATH)

    validate_successor_registry(registry, predecessor)
    validate_profile(profile, registry)
    if e1.sha256_file(REGISTRY_PATH) != REGISTRY_RAW_SHA256:
        _fail("committed successor registry raw SHA-256 mismatch")
    if e1.canonical_sha256(registry) != REGISTRY_CANONICAL_SHA256:
        _fail("committed successor registry canonical SHA-256 mismatch")
    if e1.sha256_file(COMPARATOR_MANIFEST_PATH) != COMPARATOR_MANIFEST_RAW_SHA256:
        _fail("K1A comparator manifest raw SHA-256 mismatch")
    if e1.canonical_sha256(manifest) != COMPARATOR_MANIFEST_CANONICAL_SHA256:
        _fail("K1A comparator manifest canonical SHA-256 mismatch")
    if e1.sha256_file(PROFILE_PATH) != PROFILE_RAW_SHA256:
        _fail("committed profile raw SHA-256 mismatch")
    if e1.canonical_sha256(profile) != PROFILE_CANONICAL_SHA256:
        _fail("committed profile canonical SHA-256 mismatch")

    return {
        "status": "PASS",
        "profileId": profile["profileId"],
        "profileVersion": profile["profileVersion"],
        "profileCanonicalSha256": PROFILE_CANONICAL_SHA256,
        "registryId": registry["registryId"],
        "registryCanonicalSha256": REGISTRY_CANONICAL_SHA256,
        "advancedCriteria": ["PS1", "PM5"],
        "patientFlowEnabled": False,
        "classificationEnabled": False,
    }


def main() -> int:
    try:
        result = validate_committed_contracts()
    except (e1.ContractError, KeyError, OSError, TypeError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
