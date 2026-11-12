import json
import tempfile
import unittest
from pathlib import Path

import approve_pilot_dataset as approver


class PilotDatasetApprovalTest(unittest.TestCase):
    def test_canonical_identity_rejects_non_grch38_and_symbolic_truth(self):
        valid = {"genomeAssembly": "GRCh38", "chromosome": "1", "position": 1,
                 "reference": "A", "alternate": "T"}
        self.assertEqual("GRCh38|1|1|A|T", approver.canonical_string(valid))
        invalid = dict(valid, genomeAssembly="GRCh37")
        with self.assertRaisesRegex(approver.ApprovalError, "TRUTH_ASSEMBLY_INVALID"):
            approver.canonical_string(invalid)
        invalid = dict(valid, alternate="<DEL>")
        with self.assertRaisesRegex(approver.ApprovalError, "TRUTH_ALTERNATE_INVALID"):
            approver.canonical_string(invalid)

    def test_atomic_writer_is_idempotent_and_rejects_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "approval.json"
            approver.write_atomic(path, {"status": "PASSED"})
            self.assertEqual("REUSED_IDENTICAL", approver.write_atomic(path, {"status": "PASSED"}))
            with self.assertRaisesRegex(approver.ApprovalError, "APPROVAL_OUTPUT_CONFLICT"):
                approver.write_atomic(path, {"status": "FAILED"})

    def test_reviewer_leak_scan_reports_only_hashed_value(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            visible = root / "reviewer-visible"
            visible.mkdir()
            (visible / "packet.json").write_text("hidden-case-id", encoding="utf-8")
            leaks = approver.reviewer_leaks(root, {"caseId": {"hidden-case-id"}})
            self.assertEqual(1, len(leaks))
            self.assertNotIn("hidden-case-id", json.dumps(leaks))


if __name__ == "__main__":
    unittest.main()
