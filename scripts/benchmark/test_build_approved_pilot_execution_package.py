import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_approved_pilot_execution_package as package


class ApprovedPilotExecutionPackageTest(unittest.TestCase):
    def artifact(self):
        return {"status": "VALID", "logicalArtifactId": "benchmark/varvis-pilot/artifacts/app.jar",
                "applicationSourceCommit": "a" * 40, "sha256": "b" * 64, "sizeBytes": 1}

    def catalog(self):
        return {"logicalCatalogId": package.CATALOG_ID, "status": "READY_EMPTY",
                "analysisResultRowCount": 0, "schemaFingerprint": "c" * 64}

    def test_rejects_unsafe_logical_path(self):
        with self.assertRaisesRegex(package.PackageError, "UNSAFE_LOGICAL_PATH"):
            package.safe_logical("D:/unsafe", "artifact")

    def test_artifact_and_catalog_contract_reject_mismatch(self):
        artifact = self.artifact()
        artifact["sha256"] = "bad"
        with self.assertRaisesRegex(package.PackageError, "APPLICATION_ARTIFACT_SHA256_INVALID"):
            package.validate_artifact(artifact)
        catalog = self.catalog()
        catalog["analysisResultRowCount"] = 1
        with self.assertRaisesRegex(package.PackageError, "CATALOG_RESULT_ROWS_PRESENT"):
            package.validate_catalog(catalog)

    def test_atomic_write_is_idempotent_and_conflict_safe(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "package.json"
            package.write_atomic(target, {"status": "READY"})
            self.assertEqual("REUSED_IDENTICAL", package.write_atomic(target, {"status": "READY"}))
            with self.assertRaisesRegex(package.PackageError, "OUTPUT_CONFLICT"):
                package.write_atomic(target, {"status": "CHANGED"})

    def test_fingerprint_is_stable(self):
        self.assertEqual(package.fingerprint({"b": 2, "a": 1}),
                         package.fingerprint({"a": 1, "b": 2}))

    def test_truth_set_fingerprint_is_order_independent(self):
        self.assertEqual(package.truth_set_fingerprint(["GRCh38|2|2|A|G", "GRCh38|1|1|C|T"]),
                         package.truth_set_fingerprint(["GRCh38|1|1|C|T", "GRCh38|2|2|A|G"]))

    def test_truth_policy_fingerprint_carries_frozen_semantics(self):
        self.assertNotEqual(package.truth_set_fingerprint(["GRCh38|1|1|C|T"]),
                            package.truth_policy_fingerprint(["GRCh38|1|1|C|T"], "SINGLE_REQUIRED_ALLELE"))

    def test_input_preparation_fingerprint_binds_each_preparation_source(self):
        source = package.input_preparation_fingerprint("a" * 64, "b" * 64, "c" * 64)
        self.assertNotEqual(source, package.input_preparation_fingerprint("a" * 64, "b" * 64, "d" * 64))

    def test_approved_package_has_30_cases_90_groups_and_pending_runtime_inputs(self):
        repository = Path(__file__).resolve().parents[2]
        _, value, cases, requests = package.build(
            repository, self.artifact(), self.catalog(), "d" * 64, "e" * 64)
        self.assertEqual(30, len(cases))
        self.assertEqual(90, len(requests))
        self.assertEqual("approved-public-pilot-execution-package-v2", value["schemaVersion"])
        self.assertTrue(all(item["inputArtifact"]["status"] ==
                            "PENDING_AUTHORIZED_PHEVAL_PREPARATION" for item in requests))
        self.assertTrue(all(item["excludedTermsArePositiveInput"] is False for item in requests))


if __name__ == "__main__":
    unittest.main()
