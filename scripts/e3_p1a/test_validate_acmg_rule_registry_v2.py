"""Focused tests for the E3-P1A ACMG rule-registry v2 successor."""

from __future__ import annotations

import copy
import unittest

from scripts.e3_p1a import validate_acmg_rule_registry_v2 as contract
from scripts.evidence import acmg_evidence_v2_contract as e1


class AcmgRuleRegistryV2ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.base = e1.load_json(e1.RULE_REGISTRY_PATH)
        cls.successor = e1.load_json(contract.REGISTRY_PATH)

    def registry(self) -> dict:
        return copy.deepcopy(self.successor)

    def test_committed_successor_schema_lineage_and_delta_pass(self) -> None:
        contract.validate_successor_registry(self.successor, self.base)
        canonical_hash = e1.canonical_sha256(self.base)
        self.assertEqual(
            canonical_hash,
            self.successor["lineage"]["baseRegistrySha256"],
        )
        self.assertNotEqual(canonical_hash, e1.sha256_file(e1.RULE_REGISTRY_PATH))
        statuses = {
            criterion["code"]: criterion["implementationStatus"]
            for criterion in self.successor["criteria"]
        }
        self.assertEqual("V2_IMPLEMENTED", statuses["BA1"])
        self.assertEqual("V2_IMPLEMENTED", statuses["BS1"])

    def test_schema_requires_complete_lineage(self) -> None:
        registry = self.registry()
        del registry["lineage"]["baseSchemaVersion"]
        with self.assertRaisesRegex(e1.ContractError, "baseSchemaVersion"):
            contract.validate_successor_registry(registry, self.base)

    def test_v2_schema_is_v1_plus_only_the_authorized_delta(self) -> None:
        v1_schema = e1.load_json(
            e1.SCHEMA_ROOT / "acmg-rule-registry-v1.schema.json"
        )
        normalized_v2 = copy.deepcopy(e1.load_json(contract.SCHEMA_PATH))
        normalized_v2["$id"] = v1_schema["$id"]
        normalized_v2["title"] = v1_schema["title"]
        normalized_v2["properties"]["schemaVersion"] = copy.deepcopy(
            v1_schema["properties"]["schemaVersion"]
        )
        normalized_v2["required"].remove("lineage")
        del normalized_v2["properties"]["lineage"]
        normalized_v2["$defs"]["criterion"]["properties"][
            "implementationStatus"
        ]["enum"].remove("V2_IMPLEMENTED")
        self.assertEqual(v1_schema, normalized_v2)

    def test_forged_base_hash_is_rejected(self) -> None:
        registry = self.registry()
        registry["lineage"]["baseRegistrySha256"] = "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "canonical SHA-256"):
            contract.validate_successor_registry(registry, self.base)

    def test_unrelated_criterion_change_is_rejected(self) -> None:
        registry = self.registry()
        ps1 = next(item for item in registry["criteria"] if item["code"] == "PS1")
        ps1["implementationStatus"] = "V2_IMPLEMENTED"
        with self.assertRaisesRegex(e1.ContractError, "unauthorized.*PS1"):
            contract.validate_successor_registry(registry, self.base)

    def test_ba1_non_status_change_is_rejected(self) -> None:
        registry = self.registry()
        ba1 = next(item for item in registry["criteria"] if item["code"] == "BA1")
        ba1["notes"] = "Unauthorized semantic change."
        with self.assertRaisesRegex(e1.ContractError, "BA1 changed beyond"):
            contract.validate_successor_registry(registry, self.base)


if __name__ == "__main__":
    unittest.main()
