"""Focused tests for the E3-P1B PM2 successor contracts."""

from __future__ import annotations

import copy
import json
import re
import unittest

from scripts.e3_p1b import validate_gnomad_pm2_successor as contract
from scripts.evidence import acmg_evidence_v2_contract as e1


class GnomadPm2SuccessorContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.predecessor_profile = e1.load_json(contract.PREDECESSOR_PROFILE_PATH)
        cls.profile = e1.load_json(contract.PROFILE_PATH)
        cls.predecessor_registry = e1.load_json(contract.PREDECESSOR_REGISTRY_PATH)
        cls.registry = e1.load_json(contract.REGISTRY_PATH)

    def test_committed_successors_pass_with_empty_production_policy(self) -> None:
        result = contract.validate_committed_successors()
        self.assertEqual("PASS", result["status"])
        self.assertEqual(0, result["productionPm2PolicyRecordCount"])
        statuses = {
            criterion["code"]: criterion["implementationStatus"]
            for criterion in self.registry["criteria"]
        }
        self.assertEqual("V2_IMPLEMENTED", statuses["BA1"])
        self.assertEqual("V2_IMPLEMENTED", statuses["BS1"])
        self.assertEqual("V2_IMPLEMENTED", statuses["PM2"])

    def test_forged_immediate_predecessor_registry_hash_is_rejected(self) -> None:
        predecessor = copy.deepcopy(self.predecessor_registry)
        predecessor["registryId"] = "acmg-rule-registry-forged-predecessor"
        with self.assertRaisesRegex(e1.ContractError, "immediate predecessor.*SHA-256"):
            contract.validate_successor_registry(self.registry, predecessor)

    def test_forged_schema_lineage_hash_is_rejected(self) -> None:
        registry = copy.deepcopy(self.registry)
        registry["lineage"]["baseRegistrySha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "schema lineage"):
            contract.validate_successor_registry(registry, self.predecessor_registry)

    def test_only_pm2_status_may_advance_from_e3_p1a(self) -> None:
        registry = copy.deepcopy(self.registry)
        ps1 = next(item for item in registry["criteria"] if item["code"] == "PS1")
        ps1["implementationStatus"] = "V2_IMPLEMENTED"
        with self.assertRaisesRegex(e1.ContractError, "unauthorized.*PS1"):
            contract.validate_successor_registry(registry, self.predecessor_registry)

    def test_forged_profile_lineage_is_rejected(self) -> None:
        profile = copy.deepcopy(self.profile)
        profile["lineage"]["predecessorProfileSha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "profile lineage mismatch"):
            contract.validate_successor_profile(
                profile, self.predecessor_profile, self.registry
            )

    def test_forged_empty_policy_hash_is_rejected(self) -> None:
        profile = copy.deepcopy(self.profile)
        pm2 = next(
            item
            for item in profile["evaluation"]["criteria"]
            if item["criterionId"] == contract.PM2_CRITERION_ID
        )
        pm2["populationRule"]["policySnapshot"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "policy snapshot canonical"):
            contract.validate_successor_profile(
                profile, self.predecessor_profile, self.registry
            )

    def test_policy_record_contract_expresses_context_frequency_and_callability(self) -> None:
        resources = {
            resource["resourceId"]: resource for resource in self.profile["resources"]
        }
        contract.validate_policy_record(self.synthetic_policy(resources), resources)

    def test_policy_record_rejects_unfrozen_exome_flag(self) -> None:
        resources = {
            resource["resourceId"]: resource for resource in self.profile["resources"]
        }
        record = self.synthetic_policy(resources)
        record["allSitesCallability"]["exomes"]["excludedWhenAnyFlagsTrue"] = [
            "invented_flag"
        ]
        with self.assertRaisesRegex(e1.ContractError, "exome exclusion flags"):
            contract.validate_policy_record(record, resources)

    def test_public_fixed_locus_smoke_is_schema_valid_and_policy_gated(self) -> None:
        smoke = e1.load_json(contract.SMOKE_PATH)
        schemas = e1.ContractSchemas()
        schemas.validate(smoke["context"], "urn:varvis:schema:evidence-context-v2")
        for evidence in smoke["atomicEvidence"].values():
            schemas.validate(evidence, "urn:varvis:schema:atomic-evidence-v2")
        schemas.validate(
            smoke["pm2SupportingEvaluation"],
            "urn:varvis:schema:criterion-evaluation-v2",
        )
        self.assertEqual("NO_RECORD", smoke["atomicEvidence"]["joint"]["queryOutcome"]["status"])
        self.assertEqual([], smoke["atomicEvidence"]["joint"]["measurements"])
        self.assertEqual("HIT", smoke["atomicEvidence"]["exomesAllSites"]["queryOutcome"]["status"])
        self.assertEqual("HIT", smoke["atomicEvidence"]["genomesAllSites"]["queryOutcome"]["status"])
        evaluation = smoke["pm2SupportingEvaluation"]["evaluationResult"]
        self.assertEqual("NOT_EVALUATED", evaluation["status"])
        self.assertEqual("PM2_POLICY_NOT_FOUND_OR_AMBIGUOUS", evaluation["reasonCode"])
        serialized = json.dumps(smoke, ensure_ascii=False)
        self.assertIsNone(re.search(r"(?i)(?:^|[\"\s])[a-z]:[\\/]", serialized))
        self.assertNotIn("/mnt/", serialized)

    @staticmethod
    def synthetic_policy(resources: dict) -> dict:
        bindings = [
            {
                "resourceId": resource_id,
                "resourceVersion": resource["resourceVersion"],
                "identityFingerprintSha256": resource["identityFingerprintSha256"],
            }
            for resource_id, resource in resources.items()
        ]
        return {
            "policyId": "synthetic-pm2-policy",
            "version": "test-only.1",
            "gene": {"hgncId": "HGNC:1100"},
            "disease": {"mondoId": "MONDO:0005148"},
            "moi": "AUTOSOMAL_DOMINANT",
            "resourceReleaseBindings": bindings,
            "maximumAlleleFrequency": {
                "metricId": "gnomad.joint.faf95max",
                "rawValue": "0.0001",
                "comparisonOperator": "LESS_THAN_OR_EQUAL",
            },
            "allowWellCoveredAbsence": True,
            "allSitesCallability": {
                "satisfactionMode": "ANY_INDEPENDENT",
                "independentlySufficientDatasets": ["EXOMES", "GENOMES"],
                "exomes": {
                    "resourceId": "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN",
                    "minimumAdjAlleleNumber": {
                        "rawValue": "100000",
                        "comparisonOperator": "GREATER_THAN_OR_EQUAL",
                    },
                    "excludedWhenAnyFlagsTrue": list(contract.EXOME_FLAGS),
                },
                "genomes": {
                    "resourceId": "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN",
                    "minimumAdjAlleleNumber": {
                        "rawValue": "100000",
                        "comparisonOperator": "GREATER_THAN_OR_EQUAL",
                    },
                },
            },
            "authority": {
                "authoritySourceId": "authority-clingen-svi-pm2-v1.0",
                "authorityVersion": "1.0",
                "snapshotSha256": (
                    "30745e928c7146a479afdfb7f22230192a8d1d66f9239a463c53667dd9598c59"
                ),
            },
        }


if __name__ == "__main__":
    unittest.main()
