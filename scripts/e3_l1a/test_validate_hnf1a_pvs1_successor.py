"""Focused tests for the E3-L1A HNF1A PVS1 successor contracts."""

from __future__ import annotations

import copy
import unittest

from scripts.e3_l1a import validate_hnf1a_pvs1_successor as contract
from scripts.evidence import acmg_evidence_v2_contract as e1


class Hnf1aPvs1SuccessorContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.predecessor_rule = e1.load_json(contract.PREDECESSOR_RULE_REGISTRY_PATH)
        cls.rule = e1.load_json(contract.RULE_REGISTRY_PATH)
        cls.predecessor_cspec = e1.load_json(
            contract.PREDECESSOR_CSPEC_REGISTRY_PATH
        )
        cls.cspec = e1.load_json(contract.CSPEC_REGISTRY_PATH)
        cls.profile = e1.load_json(contract.PROFILE_PATH)
        cls.catalog = e1.load_json(e1.AUTHORITY_PATH)
        cls.schemas = e1.ContractSchemas()

    def test_committed_successors_and_pinned_profile_pass(self) -> None:
        result = contract.validate_committed_contracts()
        self.assertEqual("PASS", result["status"])
        self.assertEqual(["PVS1"], result["advancedCriteria"])
        self.assertTrue(result["evaluationEnabled"])
        self.assertFalse(result["patientFlowEnabled"])
        self.assertFalse(result["classificationEnabled"])

    def test_rule_successor_changes_only_id_and_pvs1_status(self) -> None:
        contract.validate_rule_successor(self.rule, self.predecessor_rule)
        before = {
            item["code"]: item["implementationStatus"]
            for item in self.predecessor_rule["criteria"]
        }
        after = {
            item["code"]: item["implementationStatus"]
            for item in self.rule["criteria"]
        }
        self.assertEqual(
            {"PVS1"}, {code for code in after if before[code] != after[code]}
        )
        expected = copy.deepcopy(self.predecessor_rule)
        expected["registryId"] = contract.EXPECTED_RULE_REGISTRY_ID
        next(item for item in expected["criteria"] if item["code"] == "PVS1")[
            "implementationStatus"
        ] = "V2_IMPLEMENTED"
        self.assertEqual(expected, self.rule)

    def test_rule_successor_rejects_unrelated_or_semantic_change(self) -> None:
        unrelated = copy.deepcopy(self.rule)
        next(item for item in unrelated["criteria"] if item["code"] == "BP7")[
            "notes"
        ] = "Unauthorized semantic drift."
        with self.assertRaisesRegex(e1.ContractError, "unauthorized.*BP7"):
            contract.validate_rule_successor(unrelated, self.predecessor_rule)

        pvs1 = copy.deepcopy(self.rule)
        next(item for item in pvs1["criteria"] if item["code"] == "PVS1")[
            "currentGeneral"
        ]["strength"] = "STRONG"
        with self.assertRaisesRegex(e1.ContractError, "PVS1 changed beyond"):
            contract.validate_rule_successor(pvs1, self.predecessor_rule)

    def test_cspec_successor_changes_only_id_and_base_rule_binding(self) -> None:
        contract.validate_cspec_successor(
            self.cspec,
            self.predecessor_cspec,
            self.rule,
            self.catalog,
            self.schemas,
        )
        changed = {
            field
            for field in self.cspec
            if self.cspec[field] != self.predecessor_cspec[field]
        }
        self.assertEqual(
            {"registryId", "baseRuleRegistryId", "baseRuleRegistrySha256"},
            changed,
        )
        self.assertEqual(
            self.predecessor_cspec["selectionPolicy"], self.cspec["selectionPolicy"]
        )
        self.assertEqual(self.predecessor_cspec["overlays"], self.cspec["overlays"])
        overlay = self.cspec["overlays"][0]
        self.assertEqual("GN007", overlay["cspecId"])
        self.assertEqual("METADATA_ONLY", overlay["implementationStatus"])
        self.assertEqual("NOT_SELECTABLE", overlay["selectionStatus"])

    def test_cspec_successor_rejects_overlay_or_binding_drift(self) -> None:
        overlay_drift = copy.deepcopy(self.cspec)
        overlay_drift["overlays"][0]["selectionStatus"] = "SELECTABLE"
        with self.assertRaisesRegex(e1.ContractError, "overlays"):
            contract.validate_cspec_successor(
                overlay_drift,
                self.predecessor_cspec,
                self.rule,
                self.catalog,
                self.schemas,
            )

        binding_drift = copy.deepcopy(self.cspec)
        binding_drift["baseRuleRegistrySha256"] = contract.RULE_CANONICAL_SHA256
        with self.assertRaisesRegex(e1.ContractError, "base-rule binding"):
            contract.validate_cspec_successor(
                binding_drift,
                self.predecessor_cspec,
                self.rule,
                self.catalog,
                self.schemas,
            )

    def test_profile_is_exact_and_rejects_activation_drift(self) -> None:
        contract.validate_profile(self.profile)
        self.assertEqual(
            contract.PREREQUISITE_PROFILE_CANONICAL_SHA256,
            self.profile["prerequisiteBinding"]["profileCanonicalSha256"],
        )
        forged = copy.deepcopy(self.profile)
        forged["activation"]["patientFlowEnabled"] = True
        with self.assertRaisesRegex(e1.ContractError, "fixed E3-L1A contract"):
            contract.validate_profile(forged)

    def test_runtime_instances_form_a_valid_recomputable_e1_bundle(self) -> None:
        instances = self.runtime_instances()
        bundle = contract.validate_runtime_instances(instances)
        self.assertEqual("evidence-bundle-v2", bundle["schemaVersion"])
        self.assertEqual("MET", bundle["criterionEvaluations"][0]["evaluationResult"]["status"])
        self.assertEqual(
            [instances["atomicEvidence"]["evidenceId"]],
            bundle["criterionEvaluations"][0]["evidenceIds"],
        )
        self.assertEqual(bundle, e1.rehash_bundle(bundle))

    def test_runtime_instances_reject_a_forged_selection(self) -> None:
        instances = self.runtime_instances()
        instances["cspecSelection"]["selectionId"] = (
            "cspec-selection-sha256-" + "0" * 64
        )
        with self.assertRaisesRegex(e1.ContractError, "canonical bound ZERO_MATCH"):
            contract.validate_runtime_instances(instances)

    def runtime_instances(self) -> dict:
        frozen = e1.load_json(e1.SYNTHETIC_ROOT / "valid-evidence-bundle.json")
        context = copy.deepcopy(frozen["context"])
        context["contextId"] = "context-sha256-" + "0" * 64
        context["variant"]["alleleId"] = "allele-sha256-" + "0" * 64
        context["variant"]["variantType"] = "SNV"
        fixed_allele = {
            "assembly": "GRCh38",
            "contig": "12",
            "position": 120989032,
            "reference": "C",
            "alternate": "T",
        }
        for field in ("inputAllele", "normalizedAllele", "evidenceQueryAllele"):
            context["variant"][field] = copy.deepcopy(fixed_allele)
        context["variant"]["assemblyLineage"] = {"mappingStatus": "NATIVE"}
        context["gene"] = {"hgncId": "HGNC:11621", "symbol": "HNF1A"}
        context["disease"] = {
            "mondoId": "MONDO:0015967",
            "label": "monogenic diabetes",
        }
        context["moi"] = "AUTOSOMAL_DOMINANT"
        context["transcript"] = {
            "accession": "ENST00000257555",
            "version": 11,
            "selectionPolicy": "PINNED_MANE_SELECT",
            "annotationSourceVersion": "116.0",
        }

        timestamp = "2026-09-05T01:00:00Z"
        record_id = "VEP:GRCh38:12:120989032:C:T:ENST00000257555.11"
        atomic = {
            "schemaVersion": "atomic-evidence-v2",
            "evidenceId": "evidence-sha256-" + "0" * 64,
            "contextId": context["contextId"],
            "deduplicationKey": "dedup-sha256-" + "0" * 64,
            "canonicalizationProfile": "varvis-json-c14n-v1",
            "evidenceType": "VARIANT_CONSEQUENCE",
            "direction": "UNDETERMINED",
            "source": {
                "sourceId": "VEP_116_GRCH38",
                "resourceVersion": "VEP 116.0 / cache 116_GRCh38",
                "resourceSha256": (
                    "43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed"
                ),
                "retrievedAt": timestamp,
                "sourceRecords": [
                    {
                        "recordId": record_id,
                        "locator": {
                            "locatorType": "DATABASE_RECORD",
                            "locatorValue": "VEP_116_GRCH38:" + record_id,
                            "sourceRecordId": record_id,
                            "retrievedAt": timestamp,
                        },
                    }
                ],
                "sourceLocators": [
                    {
                        "locatorType": "REPOSITORY_ARTIFACT",
                        "locatorValue": "resource-identity:VEP_116_GRCH38@GRCh38",
                        "retrievedAt": timestamp,
                    }
                ],
            },
            "resourceExecution": {
                "status": "SUCCEEDED",
                "attemptedAt": "2026-09-05T00:59:59Z",
                "completedAt": timestamp,
            },
            "resourceAvailability": {"status": "AVAILABLE"},
            "queryOutcome": {
                "status": "HIT",
                "recordCount": 1,
                "queryFingerprint": "1" * 64,
            },
            "resourceApplicability": {"status": "APPLICABLE"},
            "measurements": [
                {
                    "metricId": "vep.selectedTranscriptId",
                    "rawValue": "ENST00000257555.11",
                    "unit": "transcript-id",
                },
                {
                    "metricId": "consequenceTerm",
                    "rawValue": "stop_gained&splice_region_variant",
                },
                {
                    "metricId": "vep.exon",
                    "rawValue": "2/10",
                    "normalizedValue": "2/10",
                },
            ],
            "derivedFromEvidenceIds": [],
            "conflict": {"status": "NONE", "conflictIds": []},
            "review": {
                "status": "NOT_REQUIRED",
                "decision": "NONE",
                "reviewRecordIds": [],
            },
            "provenance": {
                "producerId": "varvis-runtime-v2-adapter",
                "producerVersion": "2.0.0-e2",
                "producedAt": timestamp,
                "sourcePayloadSha256": "2" * 64,
                "identityProjectionSha256": "0" * 64,
            },
        }
        selection = {
            "schemaVersion": "cspec-selection-v1",
            "selectionId": "cspec-selection-sha256-" + "0" * 64,
            "contextId": context["contextId"],
            "canonicalizationProfile": "varvis-json-c14n-v1",
            "overlayRegistryId": contract.EXPECTED_CSPEC_REGISTRY_ID,
            "overlayRegistrySha256": contract.CSPEC_RAW_SHA256,
            "policyId": "CSPEC_EXACT_CONTEXT_SELECTION_V1",
            "outcome": "ZERO_MATCH",
            "candidateOverlayIds": [],
            "conflict": {"status": "NONE", "conflictIds": []},
            "review": {
                "status": "NOT_REQUIRED",
                "decision": "NONE",
                "reviewRecordIds": [],
            },
        }
        evaluation = {
            "schemaVersion": "criterion-evaluation-v2",
            "evaluationId": "evaluation-sha256-" + "0" * 64,
            "contextId": context["contextId"],
            "canonicalizationProfile": "varvis-json-c14n-v1",
            "criterionId": contract.PVS1,
            "ruleBinding": {
                "ruleRegistryId": contract.EXPECTED_RULE_REGISTRY_ID,
                "ruleRegistrySha256": contract.RULE_RAW_SHA256,
                "currentGeneralDesignation": "PVS1",
                "authoritySourceIds": [
                    "authority-clingen-svi-pvs1-2018",
                    "authority-clingen-svi-splicing-2023",
                ],
                "cspecSelectionId": selection["selectionId"],
            },
            "criterionApplicability": {"status": "APPLICABLE"},
            "evaluationResult": {"status": "MET"},
            "baseStrength": "VERY_STRONG",
            "appliedStrength": "VERY_STRONG",
            "evidenceIds": [atomic["evidenceId"]],
            "deterministicTrace": {
                "executionStatus": "COMPLETED",
                "evaluatorId": "hnf1a-fixed-point-pvs1-criterion-evaluator",
                "evaluatorVersion": "1.0.0-e3-l1a",
                "algorithmId": "HNF1A_FIXED_POINT_PVS1_VERY_STRONG_V1",
                "algorithmVersion": "1.0.0",
                "inputEvidenceIds": [atomic["evidenceId"]],
                "parameters": [],
                "steps": [
                    {
                        "stepCode": "PVS1_FIXED_POINT_READY",
                        "outcome": "TRUE",
                        "inputEvidenceIds": [atomic["evidenceId"]],
                    }
                ],
                "traceSha256": "0" * 64,
            },
            "conflict": {"status": "NONE", "conflictIds": []},
            "review": {
                "status": "NOT_REQUIRED",
                "decision": "NONE",
                "reviewRecordIds": [],
            },
        }
        combination = {
            "schemaVersion": "combination-result-v2",
            "combinationId": "combination-sha256-" + "0" * 64,
            "contextId": context["contextId"],
            "executionStatus": "NOT_RUN",
            "result": "NO_RESULT",
            "reasonCode": "E1_COMBINATION_NOT_IMPLEMENTED",
            "provisional": True,
            "clinicalSignoffStatus": "NOT_SIGNED",
            "criterionEvaluationIds": [evaluation["evaluationId"]],
            "conflict": {"status": "NONE", "conflictIds": []},
            "review": {
                "status": "NOT_REQUIRED",
                "decision": "NONE",
                "reviewRecordIds": [],
            },
        }
        bundle = {
            "schemaVersion": "evidence-bundle-v2",
            "bundleId": "bundle-sha256-" + "0" * 64,
            "sourceOfTruth": "ACMG_EVIDENCE_V2",
            "dataClass": "SYNTHETIC",
            "createdAt": "2026-09-05T01:00:01Z",
            "producer": {
                "producerId": "synthetic-e3-l1a-test",
                "producerVersion": "1.0.0",
                "canonicalizationProfile": "varvis-json-c14n-v1",
            },
            "authorityBinding": {
                "authorityCatalogId": self.catalog["catalogId"],
                "authorityCatalogSha256": contract.AUTHORITY_RAW_SHA256,
                "ruleRegistryId": contract.EXPECTED_RULE_REGISTRY_ID,
                "ruleRegistrySha256": contract.RULE_RAW_SHA256,
                "cspecOverlayRegistryId": contract.EXPECTED_CSPEC_REGISTRY_ID,
                "cspecOverlayRegistrySha256": contract.CSPEC_RAW_SHA256,
                "authorityCutoff": self.catalog["authorityCutoff"],
                "asOf": self.catalog["asOf"],
            },
            "context": context,
            "cspecSelection": selection,
            "atomicEvidence": [atomic],
            "criterionEvaluations": [evaluation],
            "reviewRecords": [],
            "conflicts": [],
            "legacyAdapterRecords": [],
            "combination": combination,
            "candidateOrderingChanged": False,
            "clinicalConclusionClaimed": False,
        }
        bundle = e1.rehash_bundle(bundle)
        return {
            "evidenceContext": bundle["context"],
            "atomicEvidence": bundle["atomicEvidence"][0],
            "cspecSelection": bundle["cspecSelection"],
            "criterionEvaluation": bundle["criterionEvaluations"][0],
        }


if __name__ == "__main__":
    unittest.main()
