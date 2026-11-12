import json
import tempfile
import unittest
from pathlib import Path

import audit_public_benchmark_smoke as audit_module


def identity(position):
    return {
        "genomeAssembly": "GRCh38",
        "chromosome": "1",
        "position": position,
        "reference": "A",
        "alternate": "G",
    }


class PublicBenchmarkSmokeAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.dataset_dir = self.root / "dataset"
        self.run_root = self.root / "run"
        self.dataset_dir.mkdir()
        self.run_root.mkdir()
        cases = []
        groups = []
        for index in range(1, 6):
            case_id = f"CASE-{index}"
            truths = [{"canonicalIdentity": identity(index), "relationId": "truth-1"}]
            if index == 5:
                truths.append({"canonicalIdentity": identity(100 + index), "relationId": "truth-2"})
            cases.append({
                "caseId": case_id,
                "inclusionStatus": "INCLUDED",
                "truthVariants": truths,
                "truthRelationship": "MULTI_VARIANT_SOURCE_REPORTED" if index == 5 else "SINGLE_VARIANT",
            })
            for group_id in ("G0", "G1", "G2"):
                groups.append({
                    "caseId": case_id,
                    "groupId": group_id,
                    "hpoTerms": [] if group_id == "G0" else ["HP:0000001"],
                    "hpoInputChecksum": group_id,
                    "configurationFingerprint": "config",
                })
        dataset = {"datasetId": "dataset", "cases": cases}
        run = {
            "schemaVersion": "benchmark-run-manifest-v1",
            "runId": "run",
            "datasetId": "dataset",
            "datasetVersion": "1",
            "protocolVersion": "1",
            "status": "COMPLETED_WITH_FAILURES",
            "configuration": {"candidateCap": 20000, "vepCap": 8000},
            "groups": groups,
        }
        self._write(self.dataset_dir / "dataset-manifest.json", dataset)
        self._write(self.dataset_dir / "selection-acceptance.json", {"cases": []})
        g2_path = self.dataset_dir / "truth-masked-hpo-review-v1" / "final-decisions"
        g2_path.mkdir(parents=True)
        self._write(g2_path / "g2-reviewed-input-manifest.json", {
            "cases": [
                {
                    "caseId": case["caseId"],
                    "observedHpoTerms": ["HP:0000001"],
                    "excludedHpoTerms": [],
                }
                for case in cases
            ]
        })
        self._write(self.run_root / "run-manifest-run.json", run)

        ranks = {
            "G0": [1, None, None, None, 2],
            "G1": [None, None, 2, None, 3],
            "G2": [None, None, 2, None, 3],
        }
        for case_index, case in enumerate(cases):
            for group_id in ("G0", "G1", "G2"):
                rank = ranks[group_id][case_index]
                matches = []
                for truth_index, truth in enumerate(case["truthVariants"]):
                    truth_rank = rank
                    if case_index == 4 and truth_index == 1:
                        truth_rank = None if group_id == "G0" else 146
                    matches.append({
                        "truthCanonicalIdentity": truth["canonicalIdentity"],
                        "matchedCanonicalIdentity": truth["canonicalIdentity"] if truth_rank else None,
                        "rankStatus": "RANKED" if truth_rank else "NOT_IN_RANKING_UNIVERSE",
                        "rank": truth_rank,
                        "rankingUniverseSize": 20000,
                    })
                result = {
                    "schemaVersion": "benchmark-case-result-v1",
                    "resultId": f"{case['caseId']}-{group_id}",
                    "runId": "run",
                    "datasetId": "dataset",
                    "caseId": case["caseId"],
                    "groupId": group_id,
                    "caseExecutionStatus": "COMPLETED" if rank else "COMPLETED_WITH_FAILURES",
                    "analysisReference": f"analysis-{case_index}-{0 if group_id == 'G0' else 1}",
                    "truthMatches": matches,
                    "stageRetention": [{"stage": "SPIKED_INPUT", "presence": "PRESENT"}],
                    "providerStatuses": {},
                    "secondaryFailureCodes": [],
                }
                path = self.run_root / "cases" / case["caseId"] / "results" / group_id
                path.mkdir(parents=True)
                self._write(path / f"case-result-{case['caseId']}-{group_id}.json", result)
        aggregate = {
            "summaries": {
                "G0": self._existing(0.2, 0.4, 0.3, 1.5),
                "G1": self._existing(0, 0.4, 1.0 / 6.0, 2.5),
                "G2": self._existing(0, 0.4, 1.0 / 6.0, 2.5),
            }
        }
        self._write(self.run_root / "aggregate-summary.json", aggregate)

    def tearDown(self):
        self.temporary.cleanup()

    def _write(self, path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def _existing(self, recall1, other_recall, mrr, median):
        return {
            "eligibleCases": 5,
            "rankedCases": 2,
            "notInRankingUniverseCases": 3,
            "rankUnavailableCases": 0,
            "recallAtK": {"1": recall1, "5": other_recall, "10": other_recall, "50": other_recall},
            "meanReciprocalRank": mrr,
            "medianRank": median,
        }

    def test_independent_metrics_multi_truth_and_waterfall(self):
        result = audit_module.audit(self.run_root, self.dataset_dir / "dataset-manifest.json")
        self.assertTrue(result["comparisonWithExistingAggregate"]["matchesExistingReport"])
        self.assertEqual("2/5", result["independentMetrics"]["G0"]["rankedCoverage"])
        self.assertEqual(0.3, result["independentMetrics"]["G0"]["meanReciprocalRank"])
        self.assertEqual(-1, result["g0ToG1RankShifts"][-1]["shift"])
        multi = result["multiTruthAudit"][0]
        self.assertEqual("TRUTH_SET_SEMANTICS_UNRESOLVED", multi["pairSemantics"])
        self.assertFalse(multi["groups"]["G1"]["allReportedTruthsAtK"]["50"])
        ranked_146 = [row for row in result["truthStageWaterfall"] if row["rank"] == 146]
        self.assertEqual({"F9"}, {row["failureCode"] for row in ranked_146})
        self.assertTrue(all(row["firstLossStage"] == "UNRESOLVED" for row in result["truthStageWaterfall"] if row["formalRankingUniverse"] == "NOT_IN_RANKING_UNIVERSE"))

    def test_g1_g2_effective_equivalence_and_determinism(self):
        first = audit_module.audit(self.run_root, self.dataset_dir / "dataset-manifest.json")
        second = audit_module.audit(self.run_root, self.dataset_dir / "dataset-manifest.json")
        self.assertEqual(first["auditFingerprint"], second["auditFingerprint"])
        self.assertTrue(first["g1G2Audit"]["effectiveObservedHpoAndConfigurationEqual"])
        self.assertTrue(first["g1G2Audit"]["excludedHpoProvenanceEqual"])
        self.assertTrue(first["g1G2Audit"]["normalizedResultsEqual"])
        serialized = audit_module.canonical_json(first)
        self.assertNotIn(str(self.root), serialized)

    def test_rejects_duplicate_or_incomplete_result_matrix(self):
        missing = next(self.run_root.glob("cases/CASE-1/results/G0/*.json"))
        missing.unlink()
        with self.assertRaisesRegex(audit_module.AuditError, "CASE_RESULT_MATRIX_INCOMPLETE"):
            audit_module.audit(self.run_root, self.dataset_dir / "dataset-manifest.json")

    def test_rejects_unsupported_result_schema_and_mismatched_result_id(self):
        path = next(self.run_root.glob("cases/CASE-1/results/G0/*.json"))
        result = json.loads(path.read_text(encoding="utf-8"))
        result["schemaVersion"] = "benchmark-case-result-v2"
        self._write(path, result)
        with self.assertRaisesRegex(audit_module.AuditError, "UNSUPPORTED_CASE_RESULT_SCHEMA"):
            audit_module.audit(self.run_root, self.dataset_dir / "dataset-manifest.json")

        result["schemaVersion"] = "benchmark-case-result-v1"
        result["resultId"] = "wrong-result-id"
        self._write(path, result)
        with self.assertRaisesRegex(audit_module.AuditError, "CASE_RESULT_ID_MISMATCH"):
            audit_module.audit(self.run_root, self.dataset_dir / "dataset-manifest.json")


if __name__ == "__main__":
    unittest.main()
