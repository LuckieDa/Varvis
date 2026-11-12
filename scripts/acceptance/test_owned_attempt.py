import tempfile
from pathlib import Path
import unittest
from unittest.mock import Mock
from owned_attempt import launch


class ExclusiveAttemptTest(unittest.TestCase):
    def test_collision_keeps_every_old_byte_and_never_spawns(self):
        with tempfile.TemporaryDirectory() as root:
            attempt = Path(root) / "old"
            attempt.mkdir()
            for name in ("stdout.log", "stderr.log", "ownership.json"):
                (attempt / name).write_bytes(b"original-sentinel\x00\xff")
            spawn = Mock()
            with self.assertRaises(FileExistsError):
                launch(root, "old", ["synthetic-java"], 18099, root, spawn)
            spawn.assert_not_called()
            for file in attempt.iterdir():
                self.assertEqual(b"original-sentinel\x00\xff", file.read_bytes())

    def test_files_reserved_before_spawn_and_manifest_has_no_command_secrets(self):
        with tempfile.TemporaryDirectory() as root:
            def spawn(command, **kwargs):
                self.assertTrue((Path(root) / "new" / "ownership.json").is_file())
                kwargs["stdout"].write(b"new-only")
                return type("SyntheticProcess", (), {"pid": 123})()
            result = launch(root, "new", ["synthetic-java", "private-token"], 18099, root, spawn)
            self.assertEqual(123, result["pid"])
            self.assertNotIn("private-token", (Path(root) / "new" / "ownership.json").read_text())


if __name__ == "__main__":
    unittest.main()
