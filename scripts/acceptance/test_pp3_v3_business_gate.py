import copy
import unittest

from pp3_v3_business_gate import budget_counts, require_raw_ready


class V3BusinessGateTest(unittest.TestCase):
    def setUp(self):
        self.job = {"id": "new-analysis", "status": "READY", "analysisStage": "READY_FULL"}
        self.receipt = dict.fromkeys(("jobBound", "rootExitObserved", "exitConfirmed",
                                     "cleanupWithinBudget", "stdoutStderrHandlesClosed",
                                     "outputReadComplete"), True)
        self.receipt.update(runnerVersion="vep-windows-job-v3", purpose="VEP_ANNOTATION",
                            failure="", rootExitCodeObserved=0, executionExitCode=0,
                            terminationRequested=False, activeProcessCount=0)
        self.candidates = [{"candidateId": "new-candidate", "variantId": "GRCh38|11|5227002|T|A",
                            "transcriptAnnotations": [{"source": "VEP", "status": "COMPLETED",
                                                       "transcriptId": "ENST-test.1"}]}]
        self.ledger = dict(schemaVersion="am-task-phase-budget-v1", taskId="new-task",
                           analysisId="new-analysis", resourceIdentity="resource:index",
                           status="ACTIVE", stopReason="", invocations={"LEGACY_ANNOTATION": 1},
                           transportAttempts={}, legacyCacheHits={}, trustedRawReuse=0, events=[])

    def gate(self):
        return require_raw_ready(self.job, self.receipt, self.candidates, self.ledger,
                                 "new-task", "resource:index")

    def test_ready_full_with_vep_failed_cannot_reach_submit(self):
        self.candidates[0]["transcriptAnnotations"] = [{"source": "VEP_UNAVAILABLE", "status": "FAILED"}]
        submitted = []
        with self.assertRaisesRegex(ValueError, "PUBLISHED_VEP_FAILED"):
            self.gate()
            submitted.append(True)
        self.assertEqual([], submitted)

    def test_execution_success_requires_all_receipt_guards(self):
        good = copy.deepcopy(self.receipt)
        for key, bad in (("runnerVersion", "vep-windows-job-v2"), ("failure", "error"),
                         ("terminationRequested", True), ("outputReadComplete", False),
                         ("exitConfirmed", False), ("activeProcessCount", 1)):
            self.receipt = dict(good, **{key: bad})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.gate()

    def test_empty_maps_mean_zero_and_valid_published_annotation_permits_raw(self):
        result = self.gate()
        self.assertEqual(["new-candidate"], result["eligibleCandidateIds"])
        self.assertEqual(0, result["counts"]["transportAttempts"]["LEGACY_ANNOTATION"])

    def test_nonzero_budget_is_checked_against_bound_distinct_events(self):
        for phase in ("LEGACY_ANNOTATION", "EXPLICIT_RAW"):
            self.ledger["transportAttempts"][phase] = 2
            for allele in ("GRCh38|11|5227002|T|A", "GRCh38|13|20189473|C|T"):
                self.ledger["events"].append(dict(purpose=phase, queryAllele=allele,
                    taskId="new-task", analysisId="new-analysis", resourceIdentity="resource:index"))
        budget_counts(self.ledger, "new-task", "new-analysis", "resource:index")
        with self.assertRaisesRegex(ValueError, "ALREADY_STARTED"):
            self.gate()
        self.ledger["transportAttempts"]["EXPLICIT_RAW"] = 3
        with self.assertRaisesRegex(ValueError, "CAP_EXCEEDED"):
            self.gate()

    def test_stopped_or_wrong_identity_budget_cannot_continue(self):
        for key, value in (("status", "STOPPED"), ("analysisId", "old-analysis"),
                           ("resourceIdentity", "other-resource"), ("taskId", "old-task")):
            ledger = dict(self.ledger, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                budget_counts(ledger, "new-task", "new-analysis", "resource:index")


if __name__ == "__main__":
    unittest.main()
