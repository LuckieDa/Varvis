"""Focused tests for the E3-C3 SpliceAI/VEP BP7 successor contracts."""

from __future__ import annotations

import copy
import json
import re
import unittest

from scripts.e3_c3 import validate_spliceai_bp7_successor as contract
from scripts.evidence import acmg_evidence_v2_contract as e1


class SpliceAiBp7SuccessorContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.predecessor_registry = e1.load_json(contract.PREDECESSOR_REGISTRY_PATH)
        cls.registry = e1.load_json(contract.REGISTRY_PATH)
        cls.predecessor_profile = e1.load_json(contract.PREDECESSOR_PROFILE_PATH)
        cls.profile = e1.load_json(contract.PROFILE_PATH)

    def validate_profile(self, profile: dict) -> None:
        contract.validate_successor_profile(
            profile, self.predecessor_profile, self.registry
        )

    def test_committed_profile_registry_and_lineage_pass(self) -> None:
        result = contract.validate_committed_successors()
        self.assertEqual("PASS", result["status"])
        self.assertEqual(["BP7"], result["advancedCriteria"])
        self.assertEqual("SUPPORTING", result["strengthCap"])

    def test_successor_reuses_existing_profile_and_registry_schemas(self) -> None:
        self.assertEqual(
            self.predecessor_profile["profileSchemaVersion"],
            self.profile["profileSchemaVersion"],
        )
        self.assertEqual(
            "acmg-rule-registry-v2", self.profile["registryBinding"]["registrySchemaVersion"]
        )
        self.assertEqual(
            contract.REGISTRY_SCHEMA_RAW_SHA256,
            e1.sha256_file(contract.v2_registry.SCHEMA_PATH),
        )

        forged = copy.deepcopy(self.profile)
        forged["profileSchemaVersion"] = "spliceai-bp7-profile-v2"
        with self.assertRaisesRegex(e1.ContractError, "profile schema version"):
            self.validate_profile(forged)

    def test_only_bp7_implementation_status_changes(self) -> None:
        contract.validate_successor_registry(
            self.registry, self.predecessor_registry
        )
        before = {
            item["code"]: item["implementationStatus"]
            for item in self.predecessor_registry["criteria"]
        }
        after = {
            item["code"]: item["implementationStatus"]
            for item in self.registry["criteria"]
        }
        self.assertEqual(
            {"BP7"}, {code for code in after if after[code] != before[code]}
        )
        self.assertEqual("V2_IMPLEMENTED", after["BP7"])

    def test_unrelated_registry_change_is_rejected(self) -> None:
        forged = copy.deepcopy(self.registry)
        ps1 = next(item for item in forged["criteria"] if item["code"] == "PS1")
        ps1["implementationStatus"] = "V2_IMPLEMENTED"
        with self.assertRaisesRegex(e1.ContractError, "unauthorized.*PS1"):
            contract.validate_successor_registry(forged, self.predecessor_registry)

    def test_bp7_change_beyond_status_is_rejected(self) -> None:
        forged = copy.deepcopy(self.registry)
        bp7 = next(item for item in forged["criteria"] if item["code"] == "BP7")
        bp7["notes"] = "Unauthorized semantic drift."
        with self.assertRaisesRegex(e1.ContractError, "BP7 changed beyond"):
            contract.validate_successor_registry(forged, self.predecessor_registry)

    def test_forged_profile_lineage_is_rejected(self) -> None:
        forged = copy.deepcopy(self.profile)
        forged["lineage"]["predecessorProfileCanonicalSha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "lineage"):
            self.validate_profile(forged)

    def test_synonymous_boundaries_are_zero_based_and_inclusive_at_fourth(self) -> None:
        rule = self.profile["bp7PositionRules"]["synonymousVariant"]
        self.assertTrue(rule["exonFirstBaseExcluded"])
        self.assertEqual("ZERO_BASED", rule["distanceFromExonEndSemantics"])
        self.assertEqual(3, rule["minimumDistanceFromExonEndInclusive"])
        self.assertEqual("NOT_APPLICABLE", rule["excludedBoundaryOutcome"])

    def test_intronic_boundaries_are_plus_seven_and_minus_twenty_one(self) -> None:
        rule = self.profile["bp7PositionRules"]["intronicVariant"]
        self.assertEqual(7, rule["donorMinimumSignedOffsetInclusive"])
        self.assertEqual(-21, rule["acceptorMaximumSignedOffsetInclusive"])
        self.assertEqual("NOT_APPLICABLE", rule["excludedBoundaryOutcome"])

    def test_missing_position_and_ordinal_inference_policy_are_explicit(self) -> None:
        rules = self.profile["bp7PositionRules"]
        self.assertEqual("NOT_EVALUATED", rules["missingPositionOutcome"])
        self.assertFalse(rules["inferenceFromExonOrIntronOrdinalAllowed"])

    def test_position_input_is_bound_to_the_e2_vep_resource(self) -> None:
        binding = self.profile["bp7PositionRules"]["resourceBinding"]
        self.assertEqual("VEP_116_GRCH38", binding["resourceId"])
        self.assertEqual("VEP 116.0 / cache 116_GRCh38", binding["resourceVersion"])
        self.assertEqual("GRCh38", binding["assembly"])
        self.assertEqual(
            "43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed",
            binding["identityFingerprintSha256"],
        )

    def test_synthetic_smoke_outputs_are_schema_valid_and_path_free(self) -> None:
        smoke = e1.load_json(contract.SMOKE_PATH)
        schemas = e1.ContractSchemas()
        schemas.validate(smoke["context"], "urn:varvis:schema:evidence-context-v2")
        for evidence in smoke["atomicEvidence"].values():
            schemas.validate(evidence, "urn:varvis:schema:atomic-evidence-v2")
        schemas.validate(
            smoke["sameContextBp4Evaluation"],
            "urn:varvis:schema:criterion-evaluation-v2",
        )
        schemas.validate(
            smoke["bp7Evaluation"], "urn:varvis:schema:criterion-evaluation-v2"
        )
        self.assertEqual("MET", smoke["bp7Evaluation"]["evaluationResult"]["status"])
        self.assertFalse(smoke["clinicalValidation"])
        self.assertFalse(smoke["realSpliceAiCliRerun"])
        serialized = json.dumps(smoke, ensure_ascii=False)
        self.assertIsNone(re.search(r'(?i)(?:^|["\s])[a-z]:[\\/]', serialized))
        self.assertNotIn("/mnt/", serialized)
        self.assertNotIn("/home/", serialized)

    def test_bp7_requires_same_context_bp4_and_allows_cooccurrence(self) -> None:
        dependency = self.profile["bp7Dependency"]
        self.assertEqual("MET", dependency["requiredStatusForMet"])
        self.assertTrue(dependency["sameContextIdRequired"])
        self.assertTrue(dependency["sameSelectedTranscriptRequired"])
        self.assertTrue(dependency["sameSpliceAiAtomicEvidenceRequired"])
        self.assertEqual("NOT_MET", dependency["bp4NotMetOutcome"])
        self.assertEqual(
            "NOT_EVALUATED", dependency["bp4NotEvaluatedOrMissingOutcome"]
        )
        self.assertTrue(
            self.profile["evaluation"]["coOccurrence"]["criteriaMayBothBeMet"]
        )

    def test_unknown_successor_field_is_rejected(self) -> None:
        forged = copy.deepcopy(self.profile)
        forged["futureTranscriptPlatform"] = {}
        with self.assertRaisesRegex(e1.ContractError, "root fields"):
            self.validate_profile(forged)


if __name__ == "__main__":
    unittest.main()
