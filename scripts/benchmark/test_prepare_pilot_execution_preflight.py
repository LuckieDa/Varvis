import json
import tempfile
import unittest
from pathlib import Path

import prepare_pilot_execution_preflight as preflight


class PilotExecutionPreflightTest(unittest.TestCase):
    def test_atomic_output_is_idempotent_and_rejects_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            preflight.write_atomic(path, {"status": "READY"})
            self.assertEqual("REUSED_IDENTICAL", preflight.write_atomic(path, {"status": "READY"}))
            with self.assertRaisesRegex(preflight.PreflightError, "PREFLIGHT_OUTPUT_CONFLICT"):
                preflight.write_atomic(path, {"status": "FAILED"})

    def test_provider_report_never_contains_configuration_value(self):
        secret = "not-for-output"
        properties = {
            "variant-analysis.gnomad.enabled": "true",
            "variant-analysis.gnomad.grch38-files": secret,
        }
        report = preflight.provider_status(properties)
        self.assertNotIn(secret, json.dumps(report))
        self.assertTrue(all("credentialPresent" in item for item in report))

    def test_configured_provider_paths_accept_files_and_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file_path = root / "resource.dat"
            directory_path = root / "cache"
            file_path.write_text("synthetic", encoding="utf-8")
            directory_path.mkdir()
            properties = {"file": str(file_path), "directory": str(directory_path)}
            self.assertTrue(preflight.existing_configured_paths(
                properties, ["file", "directory"]))

    def test_term_checksum_is_stable_and_order_independent(self):
        self.assertEqual(preflight.term_checksum(["HP:2", "HP:1"]),
                         preflight.term_checksum(["HP:1", "HP:2"]))


if __name__ == "__main__":
    unittest.main()
