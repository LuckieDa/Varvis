"""Focused tests for the E3-K1B ClinVar PS1/PM5 successor contracts."""

from __future__ import annotations

import copy
import unittest

from scripts.e3_k1b import validate_clinvar_ps1_pm5_successor as contract
from scripts.evidence import acmg_evidence_v2_contract as e1


class ClinVarPs1Pm5SuccessorContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.predecessor = e1.load_json(contract.PREDECESSOR_REGISTRY_PATH)
        cls.registry = e1.load_json(contract.REGISTRY_PATH)
        cls.profile = e1.load_json(contract.PROFILE_PATH)

    def test_committed_contracts_and_pinned_hashes_pass(self) -> None:
        result = contract.validate_committed_contracts()
        self.assertEqual("PASS", result["status"])
        self.assertEqual(["PS1", "PM5"], result["advancedCriteria"])
        self.assertFalse(result["patientFlowEnabled"])
        self.assertFalse(result["classificationEnabled"])

    def test_registry_changes_only_identity_and_ps1_pm5_status(self) -> None:
        contract.validate_successor_registry(self.registry, self.predecessor)
        before = {
            item["code"]: item["implementationStatus"]
            for item in self.predecessor["criteria"]
        }
        after = {
            item["code"]: item["implementationStatus"]
            for item in self.registry["criteria"]
        }
        self.assertEqual(
            {"PS1", "PM5"},
            {code for code in after if before[code] != after[code]},
        )
        self.assertEqual(self.predecessor["lineage"], self.registry["lineage"])

    def test_registry_rejects_unrelated_criterion_change(self) -> None:
        forged = copy.deepcopy(self.registry)
        pvs1 = next(item for item in forged["criteria"] if item["code"] == "PVS1")
        pvs1["implementationStatus"] = "V2_IMPLEMENTED"
        with self.assertRaisesRegex(e1.ContractError, "unauthorized.*PVS1"):
            contract.validate_successor_registry(forged, self.predecessor)

    def test_registry_rejects_ps1_semantic_change(self) -> None:
        forged = copy.deepcopy(self.registry)
        ps1 = next(item for item in forged["criteria"] if item["code"] == "PS1")
        ps1["currentGeneral"]["strength"] = "MODERATE"
        with self.assertRaisesRegex(e1.ContractError, "PS1 changed beyond"):
            contract.validate_successor_registry(forged, self.predecessor)

    def test_registry_rejects_forged_e1_lineage(self) -> None:
        forged = copy.deepcopy(self.registry)
        forged["lineage"]["baseRegistrySha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "lineage"):
            contract.validate_successor_registry(forged, self.predecessor)

    def test_profile_freezes_activation_resources_and_registry(self) -> None:
        contract.validate_profile(self.profile, self.registry)
        self.assertEqual(
            [
                "CLINVAR_2026_03_21_GRCH38",
                "VEP_116_GRCH38",
                "CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38",
            ],
            [item["resourceId"] for item in self.profile["resources"]],
        )
        self.assertEqual(
            contract.REGISTRY_RAW_SHA256,
            self.profile["registryBinding"]["registrySha256"],
        )

    def test_profile_freezes_ps1_strong_and_pm5_moderate(self) -> None:
        criteria = {
            item["criterionId"]: item for item in self.profile["criteria"]
        }
        self.assertEqual("STRONG", criteria[contract.PS1]["appliedStrength"])
        self.assertEqual("MODERATE", criteria[contract.PM5]["appliedStrength"])
        self.assertEqual(
            "ORIGINAL_2015_NON_SPLICING_MISSENSE",
            criteria[contract.PS1]["ruleBasis"],
        )
        self.assertEqual(
            ["authority-acmg-amp-2015-richards"],
            criteria[contract.PS1]["original2015AuthoritySourceIds"],
        )
        self.assertEqual(
            ["authority-acmg-amp-2015-richards"],
            criteria[contract.PM5]["original2015AuthoritySourceIds"],
        )
        self.assertEqual(
            "SAME_AMINO_ACID_CHANGE",
            criteria[contract.PS1]["requiredProteinRelation"],
        )
        self.assertEqual(
            "SAME_RESIDUE_DIFFERENT_AMINO_ACID",
            criteria[contract.PM5]["requiredProteinRelation"],
        )

    def test_profile_freezes_exact_mondo_and_assertion_eligibility(self) -> None:
        condition = self.profile["assertionEligibility"]["conditionIdentifier"]
        self.assertEqual("EXACT_AFTER_BOUNDED_NORMALIZATION", condition["match"])
        self.assertEqual(
            ["MONDO:<digits>", "MONDO:MONDO:<digits>"],
            condition["acceptedSourceForms"],
        )
        self.assertEqual(
            ["Pathogenic"],
            self.profile["assertionEligibility"]["classification"][
                "acceptedExactValues"
            ],
        )

    def test_profile_rejects_resource_identity_or_policy_drift(self) -> None:
        forged = copy.deepcopy(self.profile)
        forged["resources"][0]["identityFingerprintSha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "resource bindings"):
            contract.validate_profile(forged, self.registry)

        forged = copy.deepcopy(self.profile)
        forged["evaluationPolicy"][
            "successfulQueryWithoutEligibleComparatorOutcome"
        ] = "NOT_EVALUATED"
        with self.assertRaisesRegex(e1.ContractError, "evaluation policy"):
            contract.validate_profile(forged, self.registry)

    def test_profile_rejects_self_hash_or_new_root_field(self) -> None:
        forged = copy.deepcopy(self.profile)
        forged["profileSha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "must not self-hash"):
            contract.validate_profile(forged, self.registry)

        forged = copy.deepcopy(self.profile)
        forged["futureRulePlatform"] = {}
        with self.assertRaisesRegex(e1.ContractError, "root fields"):
            contract.validate_profile(forged, self.registry)


if __name__ == "__main__":
    unittest.main()
