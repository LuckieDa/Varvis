"""Focused tests for the E3-C1 AlphaMissense Supporting-only contracts."""

from __future__ import annotations

import copy
import json
import re
import unittest

from scripts.e3_c1 import validate_alphamissense_supporting_successor as contract
from scripts.evidence import acmg_evidence_v2_contract as e1


class AlphaMissenseSupportingSuccessorContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.predecessor = e1.load_json(contract.PREDECESSOR_REGISTRY_PATH)
        cls.registry = e1.load_json(contract.REGISTRY_PATH)
        cls.profile = e1.load_json(contract.PROFILE_PATH)
        cls.authority_catalog = e1.load_json(e1.AUTHORITY_PATH)

    def validate_profile(self, profile: dict) -> None:
        contract.validate_profile(profile, self.registry, self.authority_catalog)

    def test_committed_profile_registry_and_lineage_pass(self) -> None:
        result = contract.validate_committed_successors()
        self.assertEqual("PASS", result["status"])
        self.assertEqual("SUPPORTING", result["strengthCap"])
        self.assertEqual(["PP3", "BP4"], result["advancedCriteria"])
        self.assertEqual(
            e1.canonical_sha256(e1.load_json(e1.AUTHORITY_PATH)),
            self.profile["lineage"]["authorityCatalogCanonicalSha256"],
        )

    def test_public_fixed_point_smoke_is_schema_valid(self) -> None:
        smoke = e1.load_json(contract.SMOKE_PATH)
        schemas = e1.ContractSchemas()
        schemas.validate(smoke["context"], "urn:varvis:schema:evidence-context-v2")
        schemas.validate(
            smoke["atomicEvidence"], "urn:varvis:schema:atomic-evidence-v2"
        )
        for evaluation in smoke["criterionEvaluations"]:
            schemas.validate(
                evaluation, "urn:varvis:schema:criterion-evaluation-v2"
            )
        self.assertEqual("PUBLIC", smoke["resourceDataClass"])
        self.assertEqual("SYNTHETIC", smoke["contextDataClass"])
        self.assertFalse(smoke["clinicalValidation"])
        self.assertEqual("0.2937", smoke["fixedPoint"]["expectedScore"])
        self.assertEqual(
            {"ACMG_AMP_2015:PP3": "NOT_MET", "ACMG_AMP_2015:BP4": "NOT_MET"},
            {
                item["criterionId"]: item["evaluationResult"]["status"]
                for item in smoke["criterionEvaluations"]
            },
        )
        serialized = json.dumps(smoke, ensure_ascii=False)
        self.assertIsNone(re.search(r'(?i)(?:^|["\s])[a-z]:[\\/]', serialized))
        self.assertNotIn("/home/", serialized)
        self.assertNotIn("/mnt/", serialized)

    def test_only_pp3_and_bp4_advance_from_e3_p1b(self) -> None:
        contract.validate_successor_registry(self.registry, self.predecessor)
        predecessor_statuses = {
            criterion["code"]: criterion["implementationStatus"]
            for criterion in self.predecessor["criteria"]
        }
        successor_statuses = {
            criterion["code"]: criterion["implementationStatus"]
            for criterion in self.registry["criteria"]
        }
        changed = {
            code
            for code, status in successor_statuses.items()
            if status != predecessor_statuses[code]
        }
        self.assertEqual({"PP3", "BP4"}, changed)
        self.assertEqual("V2_IMPLEMENTED", successor_statuses["PP3"])
        self.assertEqual("V2_IMPLEMENTED", successor_statuses["BP4"])

    def test_forged_immediate_predecessor_is_rejected(self) -> None:
        predecessor = copy.deepcopy(self.predecessor)
        predecessor["registryId"] = "acmg-rule-registry-forged-predecessor"
        with self.assertRaisesRegex(e1.ContractError, "immediate predecessor.*SHA-256"):
            contract.validate_successor_registry(self.registry, predecessor)

    def test_unrelated_registry_change_is_rejected(self) -> None:
        registry = copy.deepcopy(self.registry)
        ps1 = next(item for item in registry["criteria"] if item["code"] == "PS1")
        ps1["implementationStatus"] = "V2_IMPLEMENTED"
        with self.assertRaisesRegex(e1.ContractError, "unauthorized.*PS1"):
            contract.validate_successor_registry(registry, self.predecessor)

    def test_pp3_change_beyond_status_is_rejected(self) -> None:
        registry = copy.deepcopy(self.registry)
        pp3 = next(item for item in registry["criteria"] if item["code"] == "PP3")
        pp3["notes"] = "Unauthorized semantic drift."
        with self.assertRaisesRegex(e1.ContractError, "PP3 changed beyond"):
            contract.validate_successor_registry(registry, self.predecessor)

    def test_forged_profile_lineage_is_rejected(self) -> None:
        profile = copy.deepcopy(self.profile)
        profile["lineage"]["authorityCatalogCanonicalSha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "lineage"):
            self.validate_profile(profile)

    def test_e1_2025_authority_snapshot_drift_is_rejected(self) -> None:
        profile = copy.deepcopy(self.profile)
        profile["authorityBindings"][0]["snapshotSha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "authorityBindings"):
            self.validate_profile(profile)

    def test_unique_resource_identity_and_runtime_artifacts_are_pinned(self) -> None:
        resource = self.profile["resources"][0]
        self.assertEqual(
            e1.canonical_sha256(resource["identity"]),
            resource["identityFingerprintSha256"],
        )
        self.assertEqual(
            "9657246d5ed328ea13e28432130847cd11d2662657ed8ec56a65c08e6984c4d3",
            resource["identity"]["runtimeDataArtifact"]["sha256"],
        )
        self.assertEqual(
            "9340e53c57b8a14a92e5d6d14d786714f65447d64a1c536b11adc2dd29dae58c",
            resource["identity"]["runtimeIndexArtifact"]["sha256"],
        )
        forged = copy.deepcopy(self.profile)
        forged["resources"][0]["identity"]["runtimeDataArtifact"]["sizeBytes"] += 1
        with self.assertRaisesRegex(e1.ContractError, "resources"):
            self.validate_profile(forged)

    def test_thresholds_are_exact_inclusive_decimal_strings(self) -> None:
        thresholds = self.profile["thresholds"]
        self.assertEqual("0.169", thresholds["benignMaxInclusive"]["rawValue"])
        self.assertEqual(
            "LESS_THAN_OR_EQUAL",
            thresholds["benignMaxInclusive"]["comparisonOperator"],
        )
        self.assertEqual("0.792", thresholds["pathogenicMinInclusive"]["rawValue"])
        self.assertEqual(
            "GREATER_THAN_OR_EQUAL",
            thresholds["pathogenicMinInclusive"]["comparisonOperator"],
        )
        self.assertTrue(thresholds["intermediateInterval"]["lowerBoundExclusive"])
        self.assertTrue(thresholds["intermediateInterval"]["upperBoundExclusive"])
        self.assertNotIn("0.564", json.dumps(thresholds, sort_keys=True))

        forged = copy.deepcopy(self.profile)
        forged["thresholds"]["benignMaxInclusive"]["rawValue"] = "0.170"
        with self.assertRaisesRegex(e1.ContractError, "thresholds"):
            self.validate_profile(forged)

    def test_native_grch38_missense_and_exact_context_match_are_required(self) -> None:
        applicability = self.profile["applicability"]
        matching = self.profile["matching"]
        self.assertEqual("GRCh38", applicability["assembly"])
        self.assertTrue(applicability["nativeAssemblyOnly"])
        self.assertEqual("NATIVE", applicability["requiredAssemblyLineage"])
        self.assertEqual("SNV", applicability["variantType"])
        self.assertEqual("missense_variant", applicability["consequence"])
        self.assertEqual("VEP_EVIDENCE_CONTEXT", matching["transcriptSelectionSource"])
        self.assertTrue(matching["transcriptAndProteinExactMatchRequired"])
        self.assertFalse(matching["scoreBasedTranscriptSelectionAllowed"])
        self.assertFalse(matching["multiTranscriptMaximumSelectionAllowed"])

    def test_profile_is_supporting_only_and_mutually_exclusive(self) -> None:
        strength = self.profile["strengthPolicy"]
        self.assertEqual("SUPPORTING", strength["strengthCap"])
        self.assertTrue(strength["publishedStrongerIntervalsConservativelyDowngraded"])
        self.assertFalse(strength["moderateEnabled"])
        self.assertFalse(strength["strongEnabled"])
        self.assertFalse(strength["threePointSystemEnabled"])
        self.assertFalse(
            self.profile["evaluation"]["mutualExclusion"]["criteriaMayBothBeMet"]
        )
        self.assertEqual(
            {"SUPPORTING"},
            {
                criterion["appliedStrengthCap"]
                for criterion in self.profile["evaluation"]["criteria"]
            },
        )

    def test_unknown_profile_fields_are_rejected(self) -> None:
        profile = copy.deepcopy(self.profile)
        profile["futurePredictorPlatform"] = {}
        with self.assertRaisesRegex(e1.ContractError, "root fields"):
            self.validate_profile(profile)


if __name__ == "__main__":
    unittest.main()
