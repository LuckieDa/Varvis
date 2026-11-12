import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bind_pilot_application_artifact as artifact


class PilotApplicationArtifactTest(unittest.TestCase):
    def jar(self, directory, local_config=False):
        path = Path(directory) / "application.jar"
        with zipfile.ZipFile(path, "w") as output:
            output.writestr(artifact.REQUIRED_CLASS, b"class")
            if local_config:
                output.writestr("BOOT-INF/classes/database-local.properties", b"secret")
        return path

    def test_valid_jar_is_bound_without_local_path(self):
        with tempfile.TemporaryDirectory() as directory:
            value = artifact.inspect(self.jar(directory), "a" * 40, "benchmark/varvis-pilot/artifacts/app.jar")
            self.assertEqual("VALID", value["status"])
            self.assertNotIn(directory, str(value))

    def test_missing_adapter_or_local_configuration_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "empty.jar"
            with zipfile.ZipFile(path, "w") as output:
                output.writestr("BOOT-INF/classes/Other.class", b"class")
            with self.assertRaisesRegex(artifact.ArtifactError, "EXECUTION_ADAPTER_MISSING"):
                artifact.inspect(path, "a" * 40, "benchmark/app.jar")
            with self.assertRaisesRegex(artifact.ArtifactError, "LOCAL_CONFIGURATION_EMBEDDED"):
                artifact.inspect(self.jar(directory, True), "a" * 40, "benchmark/app.jar")

    def test_explicit_repair_only_replaces_same_artifact_with_corrected_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = self.jar(directory)
            target = Path(directory) / "manifest.json"
            first = artifact.inspect(jar, "a" * 40, "benchmark/app.jar")
            artifact.write_atomic(target, first)
            repaired = artifact.inspect(jar, "b" * 40, "benchmark/app.jar")
            artifact.write_atomic(target, repaired, replace_mismatched_provenance=True)
            self.assertEqual("b" * 40, __import__("json").loads(target.read_text())["applicationSourceCommit"])


if __name__ == "__main__":
    unittest.main()
