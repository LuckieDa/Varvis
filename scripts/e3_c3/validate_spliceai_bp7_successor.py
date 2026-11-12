#!/usr/bin/env python3
"""Validate the minimal E3-C3 SpliceAI/VEP BP7 successor contracts."""

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


ACMG_RESOURCE_ROOT = (
    REPO_ROOT / "varvis-server" / "src" / "main" / "resources" / "acmg"
)
PREDECESSOR_PROFILE_PATH = (
    ACMG_RESOURCE_ROOT / "spliceai-pp3-bp4-supporting-profile-v1.json"
)
PROFILE_PATH = ACMG_RESOURCE_ROOT / "spliceai-bp7-supporting-profile-v1.json"
PREDECESSOR_REGISTRY_PATH = (
    ACMG_RESOURCE_ROOT
    / "rule-registry-v2"
    / "acmg-rule-registry-e3-c1-2026-09-02.json"
)
REGISTRY_PATH = (
    ACMG_RESOURCE_ROOT
    / "rule-registry-v2"
    / "acmg-rule-registry-e3-c3-2026-09-02.json"
)
SMOKE_PATH = REPO_ROOT / "docs" / "execution" / "E3_C3_SPLICEAI_BP7_SYNTHETIC_SMOKE.json"

PREDECESSOR_PROFILE_CANONICAL_SHA256 = (
    "4115c944f70eb7c2586f876a7083a6825b853cf9afd7c03588eb7e1ca970f099"
)
PREDECESSOR_PROFILE_RAW_SHA256 = (
    "8a346fd5e2889f5f8a8e93f783063e828f96ce30a75021a98b19cfb67af5a630"
)
PREDECESSOR_REGISTRY_CANONICAL_SHA256 = (
    "61617eb005138ebc814b9c14afd9f3f34189fb7a93442c7c6843c780161b647e"
)
REGISTRY_SCHEMA_RAW_SHA256 = (
    "b1904ee863e0c2bcb6d3e4324214e8873d79cf71647a3bd9badbee5e1784ce07"
)
EXPECTED_PROFILE_ID = "SPLICEAI_PP3_BP4_BP7_SUPPORTING"
EXPECTED_PROFILE_VERSION = "1.1.0"
EXPECTED_REGISTRY_ID = "acmg-rule-registry-e3-c3-2026-09-02"
BP7 = "ACMG_AMP_2015:BP7"
BP4 = "ACMG_AMP_2015:BP4"


def _fail(message: str) -> None:
    raise e1.ContractError(message)


def _expect_exact(actual: Any, expected: Any, field: str) -> None:
    if actual != expected:
        _fail(f"SpliceAI BP7 successor {field} changed from the frozen contract")


def validate_successor_registry(
    successor: Mapping[str, Any], predecessor: Mapping[str, Any]
) -> None:
    """Validate the v2 schema and immediate E3-C1 -> E3-C3 BP7-only delta."""

    if e1.sha256_file(v2_registry.SCHEMA_PATH) != REGISTRY_SCHEMA_RAW_SHA256:
        _fail("acmg-rule-registry-v2 schema changed")
    v2_registry._validate_schema(successor)
    if e1.canonical_sha256(predecessor) != PREDECESSOR_REGISTRY_CANONICAL_SHA256:
        _fail("immediate predecessor registry canonical SHA-256 mismatch")
    if successor["registryId"] != EXPECTED_REGISTRY_ID:
        _fail("E3-C3 registry ID mismatch")
    if successor["registryId"] == predecessor["registryId"]:
        _fail("successor registry ID must differ from its predecessor")
    if successor["lineage"] != predecessor["lineage"]:
        _fail("successor frozen E1 registry lineage changed")

    expected = copy.deepcopy(dict(predecessor))
    expected["registryId"] = EXPECTED_REGISTRY_ID
    bp7 = next(item for item in expected["criteria"] if item["code"] == "BP7")
    bp7["implementationStatus"] = "V2_IMPLEMENTED"
    if successor != expected:
        for actual, wanted in zip(successor["criteria"], expected["criteria"], strict=True):
            if actual != wanted:
                code = actual.get("code", "UNKNOWN")
                if code == "BP7":
                    _fail("BP7 changed beyond implementationStatus")
                _fail(f"unauthorized successor criterion change: {code}")
        _fail("successor registry changed beyond ID and BP7 implementationStatus")


def validate_successor_profile(
    profile: Mapping[str, Any],
    predecessor: Mapping[str, Any],
    registry: Mapping[str, Any],
) -> None:
    """Validate the closed BP7 Supporting extension of the E3-C2 profile."""

    if e1.canonical_sha256(predecessor) != PREDECESSOR_PROFILE_CANONICAL_SHA256:
        _fail("predecessor profile canonical SHA-256 mismatch")
    if e1.sha256_file(PREDECESSOR_PROFILE_PATH) != PREDECESSOR_PROFILE_RAW_SHA256:
        _fail("predecessor profile raw SHA-256 mismatch")
    if profile.get("profileSha256") is not None:
        _fail("successor profile must not self-hash")
    _expect_exact(
        profile["profileSchemaVersion"],
        predecessor["profileSchemaVersion"],
        "profile schema version",
    )
    _expect_exact(profile["profileId"], EXPECTED_PROFILE_ID, "profile ID")
    _expect_exact(profile["profileVersion"], EXPECTED_PROFILE_VERSION, "profile version")
    _expect_exact(
        profile["lineage"],
        {
            "predecessorProfileId": predecessor["profileId"],
            "predecessorProfileVersion": predecessor["profileVersion"],
            "predecessorProfileCanonicalSha256": (
                PREDECESSOR_PROFILE_CANONICAL_SHA256
            ),
            "predecessorProfileRawSha256": PREDECESSOR_PROFILE_RAW_SHA256,
        },
        "lineage",
    )

    expected_root_fields = set(predecessor) | {"bp7PositionRules", "bp7Dependency"}
    if set(profile) != expected_root_fields:
        _fail("profile root fields differ from the minimal BP7 successor contract")

    frozen_fields = set(predecessor) - {
        "profileId",
        "profileVersion",
        "lineage",
        "registryBinding",
        "evaluation",
    }
    for field in frozen_fields:
        if profile[field] != predecessor[field]:
            _fail(f"successor profile changed frozen predecessor field: {field}")

    expected_registry_binding = {
        "registryId": EXPECTED_REGISTRY_ID,
        "registrySchemaVersion": "acmg-rule-registry-v2",
        "registrySha256": e1.sha256_file(REGISTRY_PATH),
        "registryClasspathResource": (
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-c3-2026-09-02.json"
        ),
        "criterionIds": [
            "ACMG_AMP_2015:PP3",
            BP4,
            BP7,
        ],
    }
    _expect_exact(profile["registryBinding"], expected_registry_binding, "registry binding")
    _expect_exact(registry["registryId"], EXPECTED_REGISTRY_ID, "bound registry ID")

    expected_evaluation = copy.deepcopy(predecessor["evaluation"])
    expected_evaluation["criteria"].append(
        {
            "criterionId": BP7,
            "currentDesignation": "BP7",
            "baseStrength": "SUPPORTING",
            "appliedStrengthCap": "SUPPORTING",
        }
    )
    expected_evaluation["coOccurrence"] = {
        "criterionIds": [BP4, BP7],
        "criteriaMayBothBeMet": True,
    }
    _expect_exact(profile["evaluation"], expected_evaluation, "evaluation policy")

    _expect_exact(
        profile["bp7PositionRules"],
        {
            "positionSource": "VEP_SELECTED_TRANSCRIPT",
            "resourceBinding": {
                "resourceId": "VEP_116_GRCH38",
                "resourceVersion": "VEP 116.0 / cache 116_GRCh38",
                "assembly": "GRCh38",
                "identityFingerprintSha256": (
                    "43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed"
                ),
            },
            "fieldContract": {
                "synonymousAtExonFirstBaseMeasurementId": (
                    "vep.synonymousAtExonFirstBase"
                ),
                "synonymousDistanceFromExonEndMeasurementId": (
                    "vep.synonymousDistanceFromExonEnd"
                ),
                "intronicSignedOffsetMeasurementId": "vep.intronicSignedOffset",
            },
            "synonymousVariant": {
                "requiredConsequenceTerm": "synonymous_variant",
                "exonFirstBaseExcluded": True,
                "distanceFromExonEndSemantics": "ZERO_BASED",
                "minimumDistanceFromExonEndInclusive": 3,
                "excludedBoundaryOutcome": "NOT_APPLICABLE",
            },
            "intronicVariant": {
                "requiredConsequenceTerm": "intron_variant",
                "donorMinimumSignedOffsetInclusive": 7,
                "acceptorMaximumSignedOffsetInclusive": -21,
                "excludedBoundaryOutcome": "NOT_APPLICABLE",
            },
            "missingPositionOutcome": "NOT_EVALUATED",
            "otherConsequenceOutcome": "NOT_APPLICABLE",
            "inferenceFromExonOrIntronOrdinalAllowed": False,
        },
        "position rules",
    )
    _expect_exact(
        profile["bp7Dependency"],
        {
            "criterionId": BP4,
            "requiredStatusForMet": "MET",
            "sameContextIdRequired": True,
            "sameSelectedTranscriptRequired": True,
            "sameSpliceAiAtomicEvidenceRequired": True,
            "bp4NotMetOutcome": "NOT_MET",
            "bp4NotEvaluatedOrMissingOutcome": "NOT_EVALUATED",
        },
        "BP4 dependency",
    )

    registered_bp7 = next(
        item for item in registry["criteria"] if item["criterionId"] == BP7
    )
    if registered_bp7["implementationStatus"] != "V2_IMPLEMENTED":
        _fail("bound registry does not implement BP7")
    if registered_bp7["currentGeneral"]["designation"] != "BP7":
        _fail("bound registry BP7 designation mismatch")
    if registered_bp7["currentGeneral"]["strength"] != "SUPPORTING":
        _fail("bound registry BP7 strength mismatch")
    if "authority-clingen-svi-splicing-2023" not in (
        registered_bp7["currentGeneral"]["authoritySourceIds"]
    ):
        _fail("bound registry BP7 authority mismatch")

    serialized = json.dumps(profile, ensure_ascii=False)
    if re.search(r"(?i)(?:^|[\"\s])[a-z]:[\\/]", serialized) or "/mnt/" in serialized:
        _fail("SpliceAI BP7 profile contains a machine-local absolute path")


def validate_committed_successors() -> dict[str, Any]:
    predecessor_registry = e1.load_json(PREDECESSOR_REGISTRY_PATH)
    registry = e1.load_json(REGISTRY_PATH)
    predecessor_profile = e1.load_json(PREDECESSOR_PROFILE_PATH)
    profile = e1.load_json(PROFILE_PATH)
    validate_successor_registry(registry, predecessor_registry)
    validate_successor_profile(profile, predecessor_profile, registry)
    return {
        "status": "PASS",
        "profileId": profile["profileId"],
        "profileVersion": profile["profileVersion"],
        "profileCanonicalSha256": e1.canonical_sha256(profile),
        "registryId": registry["registryId"],
        "registryCanonicalSha256": e1.canonical_sha256(registry),
        "advancedCriteria": ["BP7"],
        "strengthCap": "SUPPORTING",
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
