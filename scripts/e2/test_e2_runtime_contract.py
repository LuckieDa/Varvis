#!/usr/bin/env python3
"""Offline E2 adapter/identity tests; these make no clinical-validation claim."""

from __future__ import annotations

import copy
import json
import os
import re
import sys
import unittest
from pathlib import Path
from typing import Any


sys.dont_write_bytecode = True

REPO_ROOT = Path(__file__).resolve().parents[2]
E1_SCRIPT_ROOT = REPO_ROOT / "scripts" / "evidence"
if str(E1_SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(E1_SCRIPT_ROOT))

import acmg_evidence_v2_contract as contract  # noqa: E402


FIXTURE_PATH = (
    REPO_ROOT
    / "varvis-server"
    / "src"
    / "test"
    / "resources"
    / "e2"
    / "runtime-evidence-v2-fixture.json"
)
MANIFEST_PATH = REPO_ROOT / "docs" / "execution" / "E2_RESOURCE_IDENTITY_MANIFEST.json"
E21_SUMMARY_PATH = REPO_ROOT / "docs" / "execution" / "E2_1_RUNTIME_WIRING_SMOKE.json"
ABSOLUTE_PATH = re.compile(
    r"(?:[A-Za-z]:[\\/]|\\\\|file:|(?:^|\s)/(?!/)\S+)", re.IGNORECASE
)


def load(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def strings(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for nested in value.values():
            yield from strings(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from strings(nested)


def atomic_from_carrier(context: dict[str, Any], carrier: dict[str, Any]) -> dict[str, Any]:
    evidence = {
        "schemaVersion": "atomic-evidence-v2",
        "contextId": context["contextId"],
        "canonicalizationProfile": "varvis-json-c14n-v1",
        "evidenceType": carrier["evidenceType"],
        "direction": "UNDETERMINED",
        "source": copy.deepcopy(carrier["source"]),
        "resourceExecution": copy.deepcopy(carrier["resourceExecution"]),
        "resourceAvailability": copy.deepcopy(carrier["resourceAvailability"]),
        "queryOutcome": copy.deepcopy(carrier["queryOutcome"]),
        "resourceApplicability": copy.deepcopy(carrier["resourceApplicability"]),
        "measurements": copy.deepcopy(carrier["measurements"]),
        "derivedFromEvidenceIds": [],
        "conflict": {"status": "NONE", "conflictIds": []},
        "review": {"status": "NOT_REQUIRED", "decision": "NONE", "reviewRecordIds": []},
        "provenance": {
            "producerId": "varvis-runtime-v2-adapter",
            "producerVersion": "2.0.0-e2",
            "producedAt": carrier["producedAt"],
            "sourcePayloadSha256": carrier["sourcePayloadSha256"],
        },
    }
    evidence["deduplicationKey"] = contract.content_id(
        "dedup-sha256-", contract.evidence_deduplication_projection(evidence)
    )
    identity_sha256 = contract.canonical_sha256(contract.evidence_identity_projection(evidence))
    evidence["evidenceId"] = f"evidence-sha256-{identity_sha256}"
    evidence["provenance"]["identityProjectionSha256"] = identity_sha256
    return evidence


class E2RuntimeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schemas = contract.ContractSchemas()
        cls.fixture = load(FIXTURE_PATH)

    def validate(self, carrier: dict[str, Any]) -> dict[str, Any]:
        evidence = atomic_from_carrier(self.fixture["context"], carrier)
        self.schemas.validate(evidence, "urn:varvis:schema:atomic-evidence-v2")
        contract._assert_atomic_state(evidence)
        return evidence

    def test_complete_context_and_hit_are_schema_valid_and_canonical(self) -> None:
        context = self.fixture["context"]
        self.schemas.validate(context, "urn:varvis:schema:evidence-context-v2")
        self.assertEqual(
            context["variant"]["alleleId"],
            contract.content_id("allele-sha256-", contract.allele_identity_projection(context)),
        )
        self.assertEqual(
            context["contextId"],
            contract.content_id("context-sha256-", contract.context_identity_projection(context)),
        )

        expected = self.fixture["expectedAtomicEvidence"]
        self.schemas.validate(expected, "urn:varvis:schema:atomic-evidence-v2")
        contract._assert_atomic_state(expected)
        evidence = self.validate(copy.deepcopy(self.fixture["carrier"]))
        self.assertEqual(expected, evidence)
        self.assertEqual(
            "dedup-sha256-65fadb498a56d3c635368f6c769a2927288ab85232518747b6806d233c6175f9",
            evidence["deduplicationKey"],
        )
        self.assertEqual(
            "evidence-sha256-778329d1a31e67b530003709339f975baddb2965afdb353e2229f0612a3871bd",
            evidence["evidenceId"],
        )

    def test_no_record_failure_timeout_and_assembly_unsupported_remain_distinct(self) -> None:
        for state in ("NO_RECORD", "FAILED", "TIMEOUT", "ASSEMBLY_UNSUPPORTED"):
            carrier = copy.deepcopy(self.fixture["carrier"])
            carrier["source"]["sourceRecords"] = []
            carrier["measurements"] = []
            carrier["queryOutcome"] = {"status": "NOT_QUERIED", "recordCount": 0}
            if state == "NO_RECORD":
                carrier["queryOutcome"] = {
                    "status": "NO_RECORD",
                    "recordCount": 0,
                    "queryFingerprint": "c" * 64,
                }
            else:
                carrier["resourceExecution"] = {
                    "status": "NOT_RUN" if state == "ASSEMBLY_UNSUPPORTED" else state,
                    "reasonCode": "ASSEMBLY_UNSUPPORTED" if state == "ASSEMBLY_UNSUPPORTED" else f"PROVIDER_{state}",
                }
                carrier["resourceApplicability"] = {
                    "status": "NOT_APPLICABLE" if state == "ASSEMBLY_UNSUPPORTED" else "UNKNOWN",
                    "reasonCode": "ASSEMBLY_UNSUPPORTED" if state == "ASSEMBLY_UNSUPPORTED" else "NOT_EVALUATED",
                }
            evidence = self.validate(carrier)
            self.assertNotEqual("HIT", evidence["queryOutcome"]["status"])

    def test_failure_cannot_be_relabelled_no_record(self) -> None:
        carrier = copy.deepcopy(self.fixture["carrier"])
        carrier["source"]["sourceRecords"] = []
        carrier["measurements"] = []
        carrier["resourceExecution"] = {"status": "FAILED", "reasonCode": "PROVIDER_FAILED"}
        carrier["resourceApplicability"] = {"status": "UNKNOWN", "reasonCode": "NOT_EVALUATED"}
        carrier["queryOutcome"] = {"status": "NO_RECORD", "recordCount": 0, "queryFingerprint": "c" * 64}
        evidence = atomic_from_carrier(self.fixture["context"], carrier)
        with self.assertRaises(contract.ContractError):
            contract._assert_atomic_state(evidence)


class E2ResourceIdentityManifestTest(unittest.TestCase):
    def test_identity_fingerprints_are_canonical_and_not_full_byte_claims(self) -> None:
        manifest = load(MANIFEST_PATH)
        self.assertEqual("CANONICAL_RESOURCE_IDENTITY_NOT_FULL_BYTES", manifest["fingerprintSemantics"]["scope"])
        self.assertFalse(manifest["fingerprintSemantics"]["byteIntegrityClaimed"])
        self.assertNotIn("manifestSha256", manifest)
        self.assertFalse(any(ABSOLUTE_PATH.search(value) for value in strings(manifest)))
        resource_ids = [resource["resourceId"] for resource in manifest["resources"]]
        self.assertEqual(len(resource_ids), len(set(resource_ids)))
        for resource in manifest["resources"]:
            self.assertEqual(resource["provider"], resource["identity"]["provider"])
            self.assertEqual(resource["assembly"], resource["identity"]["assembly"])
            self.assertEqual(
                resource["identityFingerprintSha256"],
                contract.canonical_sha256(resource["identity"]),
                resource["resourceId"],
            )

    def test_assembly_versions_and_vep_provenance_are_separate(self) -> None:
        manifest = load(MANIFEST_PATH)
        by_id = {item["resourceId"]: item for item in manifest["resources"]}
        self.assertEqual("ClinVar 2026-07-06 GRCh37", by_id["CLINVAR_2026_07_06_GRCH37"]["resourceVersion"])
        self.assertEqual("ClinVar 2026-03-21 GRCh38", by_id["CLINVAR_2026_03_21_GRCH38"]["resourceVersion"])
        for resource_id in ("VEP_116_GRCH37", "VEP_116_GRCH38"):
            identity = by_id[resource_id]["identity"]
            self.assertIn("tool", identity)
            self.assertIn("cache", identity)
            self.assertIn("fasta", identity)
            self.assertIn("assembly", identity)
            self.assertIn("options", identity)
            self.assertFalse(identity["options"]["lofteeEnabled"])


class E21RuntimeWiringSmokeTest(unittest.TestCase):
    EXPECTED_QUERIES = {
        "GNOMAD_GRCH38_HIT": "HIT",
        "GNOMAD_GRCH38_NO_RECORD": "NO_RECORD",
        "CLINVAR_GRCH37_HIT": "HIT",
        "CLINVAR_GRCH37_NO_RECORD": "NO_RECORD",
        "CLINVAR_GRCH38_HIT": "HIT",
        "CLINVAR_GRCH38_NO_RECORD": "NO_RECORD",
        "VEP_GRCH37_HIT": "HIT",
        "VEP_GRCH37_NO_RECORD": "NO_RECORD",
        "VEP_GRCH38_HIT": "HIT",
        "VEP_GRCH38_NO_RECORD": "NO_RECORD",
    }

    @classmethod
    def setUpClass(cls) -> None:
        cls.schemas = contract.ContractSchemas()
        cls.summary = load(E21_SUMMARY_PATH)
        cls.manifest = {
            resource["resourceId"]: resource for resource in load(MANIFEST_PATH)["resources"]
        }

    def test_committed_summary_is_path_free_and_manifest_bound(self) -> None:
        self.assertEqual("e2.1-runtime-wiring-smoke-v1", self.summary["schemaVersion"])
        self.assertEqual("PASS", self.summary["status"])
        self.assertEqual("SYNTHETIC", self.summary["contextDataClass"])
        self.assertTrue(self.summary["contractEngineeringOnly"])
        self.assertFalse(self.summary["clinicalValidation"])
        self.assertFalse(self.summary["byteIntegrityClaimed"])
        self.assertFalse(any(ABSOLUTE_PATH.search(value) for value in strings(self.summary)))

        metadata = self.summary["resourceMetadata"]
        self.assertEqual("PASS", metadata["status"])
        self.assertEqual("LIGHTWEIGHT_METADATA_ONLY", metadata["verificationScope"])
        self.assertEqual(len(self.manifest), metadata["manifestResourceCount"])
        gnomad = self.manifest["GNOMAD_EXOMES_V4_1_GRCH38_COMPACT"]["identity"]
        self.assertEqual(gnomad["dataComponentCount"], metadata["gnomad"]["dataComponentCount"])
        self.assertEqual(gnomad["indexComponentCount"], metadata["gnomad"]["indexComponentCount"])
        for assembly, resource_id in (
            ("GRCh37", "CLINVAR_2026_07_06_GRCH37"),
            ("GRCh38", "CLINVAR_2026_03_21_GRCH38"),
        ):
            identity = self.manifest[resource_id]["identity"]
            observed = metadata["clinvar"][assembly]
            self.assertEqual(identity["fileDate"], observed["fileDate"])
            self.assertEqual(identity["headerReference"], observed["reference"])
            self.assertTrue(observed["indexSha256Verified"])
        for assembly, resource_id in (
            ("GRCh37", "VEP_116_GRCH37"),
            ("GRCh38", "VEP_116_GRCH38"),
        ):
            identity = self.manifest[resource_id]["identity"]
            observed = metadata["vep"][assembly]
            self.assertEqual(identity["tool"]["version"], metadata["vep"]["toolVersion"])
            self.assertEqual(identity["fasta"]["indexedContigCount"], observed["indexedContigCount"])
            self.assertTrue(observed["cacheInfoSha256Verified"])
            self.assertTrue(observed["fastaIndexSha256Verified"])

        cases = {item["caseId"]: item for item in self.summary["cases"]}
        self.assertEqual(set(self.EXPECTED_QUERIES), set(cases))
        self.assertEqual(len(cases), len(self.summary["cases"]))
        for case_id, expected_query in self.EXPECTED_QUERIES.items():
            case = cases[case_id]
            source = case["source"]
            resource = self.manifest[source["resourceId"]]
            self.assertEqual(resource["provider"], case["provider"], case_id)
            self.assertEqual(resource["assembly"], case["queryAllele"]["assembly"], case_id)
            self.assertEqual(resource["assembly"], source["assembly"], case_id)
            self.assertEqual(resource["resourceVersion"], source["resourceVersion"], case_id)
            self.assertEqual(
                resource["identityFingerprintSha256"],
                source["identityFingerprintSha256"],
                case_id,
            )
            self.assertEqual("SUCCEEDED", case["resourceExecution"], case_id)
            self.assertEqual("AVAILABLE", case["resourceAvailability"], case_id)
            self.assertEqual(expected_query, case["queryOutcome"], case_id)
            self.assertEqual("APPLICABLE", case["resourceApplicability"], case_id)
            self.assertEqual("PASS", case["schemaValidation"], case_id)
            self.assertRegex(case["evidenceId"], r"^evidence-sha256-[0-9a-f]{64}$")

    def test_optional_full_smoke_atomic_records_validate_offline(self) -> None:
        configured = os.environ.get("VARVIS_E21_ATOMIC_SMOKE_PATH")
        if not configured:
            self.skipTest("Set VARVIS_E21_ATOMIC_SMOKE_PATH after the opt-in real provider smoke")
        full = load(Path(configured))
        self.assertEqual("PASS", full["status"])
        self.assertEqual("SYNTHETIC", full["contextDataClass"])
        self.assertTrue(full["contractEngineeringOnly"])
        self.assertFalse(full["clinicalValidation"])
        self.assertFalse(full["byteIntegrityClaimed"])
        self.assertFalse(any(ABSOLUTE_PATH.search(value) for value in strings(full)))

        summary_cases = {item["caseId"]: item for item in self.summary["cases"]}
        full_cases = {item["caseId"]: item for item in full["cases"]}
        self.assertEqual(set(self.EXPECTED_QUERIES), set(full_cases))
        self.assertEqual(len(full_cases), len(full["cases"]))
        for case_id, expected_query in self.EXPECTED_QUERIES.items():
            case = full_cases[case_id]
            summary_case = summary_cases[case_id]
            evidence = case["atomicEvidence"]
            self.schemas.validate(evidence, "urn:varvis:schema:atomic-evidence-v2")
            contract._assert_atomic_state(evidence)
            identity_sha256 = contract.canonical_sha256(
                contract.evidence_identity_projection(evidence)
            )
            self.assertEqual(f"evidence-sha256-{identity_sha256}", evidence["evidenceId"])
            self.assertEqual(identity_sha256, evidence["provenance"]["identityProjectionSha256"])
            self.assertEqual(
                contract.content_id(
                    "dedup-sha256-", contract.evidence_deduplication_projection(evidence)
                ),
                evidence["deduplicationKey"],
            )
            self.assertEqual(expected_query, evidence["queryOutcome"]["status"], case_id)
            self.assertEqual(summary_case["provider"], case["provider"], case_id)
            self.assertEqual(summary_case["queryAllele"], case["queryAllele"], case_id)
            self.assertEqual(summary_case["evidenceId"], evidence["evidenceId"], case_id)
            self.assertEqual(summary_case["source"]["resourceId"],
                             evidence["source"]["sourceId"], case_id)
            self.assertEqual(summary_case["source"]["resourceVersion"],
                             evidence["source"]["resourceVersion"], case_id)
            self.assertEqual(summary_case["source"]["identityFingerprintSha256"],
                             evidence["source"]["resourceSha256"], case_id)
            self.assertEqual(case["source"], evidence["source"], case_id)
            self.assertEqual(case["resourceExecution"], evidence["resourceExecution"], case_id)
            self.assertEqual(case["resourceAvailability"], evidence["resourceAvailability"], case_id)
            self.assertEqual(case["queryOutcome"], evidence["queryOutcome"], case_id)
            self.assertEqual(case["resourceApplicability"], evidence["resourceApplicability"], case_id)


if __name__ == "__main__":
    unittest.main()
