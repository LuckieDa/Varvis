"""Observer passthrough tests; no engine/model invocation."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock
import trace_spliceai_business as trace


class TraceTest(unittest.TestCase):
    def exercise(self, outcome):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source.vcf", root / "output.vcf"
            source.write_text("public synthetic input", encoding="utf-8")
            command = ["python", "-m", "spliceai", "-I", str(source), "-O", str(output), "-D", "4999", "-M", "0"]
            kwargs = dict(timeout=540, capture_output=True, text=True, env={"TEST_SECRET": "not-for-diagnostic"})
            runner = Mock(side_effect=outcome if isinstance(outcome, Exception) else None, return_value=outcome)
            def entry(*_args, **_kwargs):
                return subprocess.run(command, **kwargs)
            with patch.object(sys, "argv", ["trace", "--diagnostic-directory", str(root / "trace")]), \
                    patch.object(subprocess, "run", runner), patch.object(trace.runpy, "run_path", entry):
                if isinstance(outcome, Exception):
                    with self.assertRaises(type(outcome)): trace.main()
                else: trace.main()
                self.assertIs(subprocess.run, runner)
            runner.assert_called_once_with(command, **kwargs)
            saved, = (root / "trace").glob("*/diagnostic.json")
            record = json.loads(saved.read_text(encoding="utf-8"))
            self.assertEqual(record["command"], command)
            self.assertFalse(record["automaticRetry"])
            self.assertNotIn("not-for-diagnostic", saved.read_text(encoding="utf-8"))
            self.assertEqual((saved.parent / "input.vcf").read_text(), source.read_text())
            return record

    def test_success_passthrough(self):
        self.assertEqual(self.exercise(subprocess.CompletedProcess([], 0, "ok", ""))["returnCode"], 0)

    def test_failure_exit_and_stderr_retained(self):
        record = self.exercise(subprocess.CompletedProcess([], 2, "", "synthetic failure"))
        self.assertEqual((record["returnCode"], record["stderr"]), (2, "synthetic failure"))

    def test_timeout_not_retried_or_reinterpreted(self):
        self.assertEqual(self.exercise(subprocess.TimeoutExpired("fixture", 540))["errorType"], "TimeoutExpired")


if __name__ == "__main__":
    unittest.main()
