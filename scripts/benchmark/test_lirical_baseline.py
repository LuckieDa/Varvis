import json
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lirical_baseline as lirical


def request(request_id, case_id, group, order, terms):
    return {"requestId": request_id, "caseId": case_id, "groupId": group, "selectionOrder": order,
            "vcfSha256": "a" * 64, "tbiSha256": "b" * 64, "vcfSampleId": case_id,
            "hpoTerms": terms, "phenopacketSha256": "c" * 64}


class LiricalBaselineTest(unittest.TestCase):
    def test_freeze_matrix_requires_all_pairs_and_truth_free_fields(self):
        items = []
        for order in range(1, 26):
            items.extend([request(f"g0-{order}", f"case-{order}", "G0", order, []),
                          request(f"g1-{order}", f"case-{order}", "G1", order, ["HP:0000001"])])
        matrix = lirical.freeze_matrix({"requests": list(reversed(items))})
        self.assertEqual(50, matrix["requestCount"])
        self.assertTrue(all(item["truthFree"] for item in matrix["requests"]))
        self.assertEqual("g0-1", matrix["requests"][0]["requestId"])

    def test_g0_and_g1_contracts_reject_invalid_hpo(self):
        with self.assertRaisesRegex(lirical.LiricalBaselineError, "G0_G1_HPO"):
            lirical.safe_request(request("g0", "case", "G0", 1, ["HP:0000001"]))
        with self.assertRaisesRegex(lirical.LiricalBaselineError, "G0_G1_HPO"):
            lirical.safe_request(request("g1", "case", "G1", 1, []))

    def test_yaml_has_only_execution_fields(self):
        item = lirical.safe_request(request("g1", "case", "G1", 1, ["HP:0000001"]))
        text = lirical.request_yaml(item, "case.vcf.gz")
        self.assertIn("hpoIds", text)
        self.assertIn("sex: UNKNOWN", text)
        self.assertNotIn("truth", text.lower())
        self.assertNotIn("prediction", text.lower())

    def test_forbidden_fields_are_rejected(self):
        item = lirical.safe_request(request("g0", "case", "G0", 1, []))
        item["truthRank"] = 1
        with self.assertRaisesRegex(lirical.LiricalBaselineError, "FORBIDDEN"):
            lirical.request_yaml(item, "case.vcf.gz")

    def test_atomic_output_rejects_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            self.assertEqual("CREATED", lirical.write_atomic(path, {"status": "PASS"}))
            self.assertEqual("REUSED_IDENTICAL", lirical.write_atomic(path, {"status": "PASS"}))
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "OUTPUT_CONFLICT"):
                lirical.write_atomic(path, {"status": "FAIL"})

    def test_runtime_binding_keeps_paths_but_not_truth_status(self):
        items = []
        for order in range(1, 26):
            for group, terms in (("G0", []), ("G1", ["HP:0000001"])):
                item = request(f"{group}-{order}", f"case-{order}", group, order, terms)
                item.update({"vcfPath": f"{order}.vcf.gz", "tbiPath": f"{order}.vcf.gz.tbi",
                             "truthBindingStatus": "MUST_NOT_BE_COPIED"})
                items.append(item)
        binding = lirical.runtime_binding({"requests": items})
        self.assertNotIn("truthBindingStatus", json.dumps(binding))
        self.assertIn("1.vcf.gz", json.dumps(binding))

    def test_windows_path_converts_for_explicit_wsl_command(self):
        self.assertEqual("/mnt/d/Tool/lirical/data", lirical.wsl_path(r"D:\Tool\lirical\data"))

    def test_g1_only_matrix_retains_exactly_one_request_per_case(self):
        items = []
        for order in range(1, 26):
            items.extend([request(f"g0-{order}", f"case-{order}", "G0", order, []),
                          request(f"g1-{order}", f"case-{order}", "G1", order, ["HP:0000001"])])
        full = lirical.freeze_matrix({"requests": items})
        g1 = lirical.freeze_g1_only_matrix(full)
        self.assertEqual(25, g1["requestCount"])
        self.assertTrue(all(item["groupId"] == "G1" for item in g1["requests"]))

    def test_pre_run_manifest_is_truth_free_for_g1_only_scope(self):
        items = [request(f"g1-{order}", f"case-{order}", "G1", order, ["HP:0000001"])
                 for order in range(1, 26)]
        matrix = {"requests": items}
        binding = {"requests": [dict(item, vcfPath="input.vcf.gz", tbiPath="input.vcf.gz.tbi") for item in items]}
        with tempfile.TemporaryDirectory() as directory:
            java, jar = Path(directory) / "java", Path(directory) / "lirical.jar"
            java.write_text("java", encoding="utf-8")
            jar.write_text("jar", encoding="utf-8")
            result = lirical.pre_run_manifest(binding, matrix, java, jar, Path(directory), Path(directory))
        self.assertEqual("FROZEN_PRE_RUN", result["status"])
        self.assertFalse(result["truthRead"])
        self.assertEqual(["G1"], result["groups"])

    def test_execution_binding_requires_exact_frozen_hpo_and_order(self):
        items = [request(f"g1-{order}", f"case-{order}", "G1", order, ["HP:0000001"])
                 for order in range(1, 26)]
        binding = {"requests": [dict(item, vcfPath=f"{item['caseId']}.vcf.gz", tbiPath="index.tbi")
                                for item in reversed(items)]}
        result = lirical.bound_execution_requests(binding, {"requests": items})
        self.assertEqual("g1-1", result[0]["requestId"])
        binding["requests"][0]["hpoTerms"] = ["HP:9999999"]
        with self.assertRaisesRegex(lirical.LiricalBaselineError, "RUNTIME_BINDING_DRIFT"):
            lirical.bound_execution_requests(binding, {"requests": items})

    def test_batch_records_all_terminal_requests_after_an_individual_failure(self):
        items = [request(f"g1-{order}", f"case-{order}", "G1", order, ["HP:0000001"])
                 for order in range(1, 26)]
        def fake_run(item, *_args):
            return {"requestId": item["requestId"],
                    "terminalStatus": "FAILED_NONZERO_EXIT" if item["selectionOrder"] == 2 else "COMPLETED_LOCKED"}
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(lirical, "run_request", side_effect=fake_run):
            result = lirical.run_batch(items, Path(directory), Path("java"), Path("jar"), Path("data"), Path("resources"), 1)
        self.assertEqual(25, result["completed"])
        self.assertEqual(24, result["successful"])

    def test_tsv_rank_drives_native_and_dense_gene_projection(self):
        payload = {"analysisResults": [
            {"diseaseId": "OMIM:2", "posttestProbability": .5, "compositeLR": 2,
             "genotypeLR": {"geneId": {"id": "NCBIGene:2", "symbol": "GENE2"}}},
            {"diseaseId": "OMIM:JSON_ONLY", "posttestProbability": .99, "compositeLR": 3,
             "genotypeLR": {"geneId": {"id": "NCBIGene:4", "symbol": "GENE4"}}},
            {"diseaseId": "OMIM:1", "posttestProbability": .9, "compositeLR": 4,
             "genotypeLR": {"geneId": {"id": "NCBIGene:2", "symbol": "GENE2"}}},
            {"diseaseId": "OMIM:3", "posttestProbability": .1, "compositeLR": 1,
             "genotypeLR": {"geneId": {"id": "NCBIGene:3", "symbol": "GENE3"}}},
        ]}
        with tempfile.TemporaryDirectory() as directory:
            json_path, tsv_path = Path(directory) / "result.json", Path(directory) / "result.tsv"
            json_path.write_text(json.dumps(payload), encoding="utf-8")
            tsv_path.write_text(
                "rank\tdiseaseCurie\tvariants\n"
                "1\tOMIM:1\t1:1A>G\n"
                "2\tOMIM:2\t\n"
                "3\tOMIM:3\t2:2C>T\n", encoding="utf-8")
            result = lirical.parse_output(json_path, tsv_path)
        self.assertEqual(3, result["nativeDiseaseCount"])
        self.assertEqual(2, len(result["geneRanks"]))
        self.assertEqual(1, result["geneRanks"][0]["rank"])
        self.assertEqual(1, result["geneRanks"][0]["firstDiseaseRank"])
        self.assertEqual(2, result["geneRanks"][1]["rank"])
        self.assertEqual(3, result["geneRanks"][1]["firstDiseaseRank"])
        self.assertEqual("OMIM:1", result["nativeDiseaseRanks"][0]["diseaseId"])
        self.assertEqual("LIRICAL_TSV_EXPLICIT_RANK", result["rankSource"])
        self.assertNotIn("GENE4", json.dumps(result))
        self.assertEqual("NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK",
                         result["variantRankStatus"])

    def test_gene_identifier_and_symbol_mappings_must_be_consistent(self):
        payload = {"analysisResults": [
            {"diseaseId": "OMIM:1", "posttestProbability": .9,
             "genotypeLR": {"geneId": {"id": "NCBIGene:1", "symbol": "GENE"}}},
            {"diseaseId": "OMIM:2", "posttestProbability": .8,
             "genotypeLR": {"geneId": {"id": "NCBIGene:2", "symbol": "GENE"}}},
        ]}
        with tempfile.TemporaryDirectory() as directory:
            json_path, tsv_path = Path(directory) / "result.json", Path(directory) / "result.tsv"
            json_path.write_text(json.dumps(payload), encoding="utf-8")
            tsv_path.write_text(
                "rank\tdiseaseCurie\tvariants\n1\tOMIM:1\t\n2\tOMIM:2\t\n", encoding="utf-8")
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "GENE_ID_CONFLICT"):
                lirical.parse_output(json_path, tsv_path)

    def test_tsv_rank_must_be_contiguous(self):
        payload = {"analysisResults": [
            {"diseaseId": "OMIM:1", "posttestProbability": .9},
            {"diseaseId": "OMIM:2", "posttestProbability": .8},
        ]}
        with tempfile.TemporaryDirectory() as directory:
            json_path, tsv_path = Path(directory) / "result.json", Path(directory) / "result.tsv"
            json_path.write_text(json.dumps(payload), encoding="utf-8")
            tsv_path.write_text("rank\tdiseaseCurie\tvariants\n1\tOMIM:1\t\n3\tOMIM:2\t\n", encoding="utf-8")
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "RANK_NOT_CONTIGUOUS"):
                lirical.parse_output(json_path, tsv_path)

    def test_missing_native_output_is_rejected_with_structured_code(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            json_path = root / "result.json"
            json_path.write_text(json.dumps({"analysisResults": []}), encoding="utf-8")
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "LIRICAL_TSV_MISSING"):
                lirical.parse_output(json_path, root / "missing.tsv")
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "LIRICAL_JSON_MISSING"):
                lirical.parse_output(root / "missing.json", root / "missing.tsv")

    def test_tsv_disease_must_have_unique_json_binding(self):
        payload = {"analysisResults": [{"diseaseId": "OMIM:1", "posttestProbability": .9}]}
        with tempfile.TemporaryDirectory() as directory:
            json_path, tsv_path = Path(directory) / "result.json", Path(directory) / "result.tsv"
            json_path.write_text(json.dumps(payload), encoding="utf-8")
            tsv_path.write_text("rank\tdiseaseCurie\tvariants\n1\tOMIM:2\t\n", encoding="utf-8")
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "TSV_JSON_DISEASE_MISSING"):
                lirical.parse_output(json_path, tsv_path)

    def test_tsv_posttest_order_is_verified_from_json(self):
        payload = {"analysisResults": [
            {"diseaseId": "OMIM:1", "posttestProbability": .1},
            {"diseaseId": "OMIM:2", "posttestProbability": .9},
        ]}
        with tempfile.TemporaryDirectory() as directory:
            json_path, tsv_path = Path(directory) / "result.json", Path(directory) / "result.tsv"
            json_path.write_text(json.dumps(payload), encoding="utf-8")
            tsv_path.write_text("rank\tdiseaseCurie\tvariants\n1\tOMIM:1\t\n2\tOMIM:2\t\n", encoding="utf-8")
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "POSTTEST_ORDER_INVALID"):
                lirical.parse_output(json_path, tsv_path)

    def test_batch_manifest_requires_and_records_all_locked_requests(self):
        items = [request(f"g1-{order}", f"case-{order}", "G1", order, ["HP:0000001"])
                 for order in range(1, 26)]
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest"
            manifest.mkdir()
            for item in items:
                (manifest / (item["requestId"] + ".lock.json")).write_text(
                    json.dumps({"terminalStatus": "COMPLETED_LOCKED"}), encoding="utf-8")
            result = lirical.freeze_batch_manifest(items, directory)
        self.assertEqual(25, result["successfulRequestCount"])
        self.assertFalse(result["truthRead"])

    def test_projection_batch_rejects_locked_artifact_hash_drift(self):
        items = [request(f"g1-{order}", f"case-{order}", "G1", order, ["HP:0000001"])
                 for order in range(1, 26)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest").mkdir()
            for item in items:
                output = lirical.request_output_dir(root, item)
                output.mkdir(parents=True)
                json_path, tsv_path = output / "result.json", output / "result.tsv"
                json_path.write_text(json.dumps({
                    "analysisResults": [{"diseaseId": "OMIM:1", "posttestProbability": .9}]
                }), encoding="utf-8")
                tsv_path.write_text("rank\tdiseaseCurie\tvariants\n1\tOMIM:1\t\n", encoding="utf-8")
                (root / "manifest" / f"{item['requestId']}.lock.json").write_text(json.dumps({
                    "terminalStatus": "COMPLETED_LOCKED",
                    "artifacts": [
                        {"logicalPath": "result.json", "sha256": lirical.sha256(json_path)},
                        {"logicalPath": "result.tsv", "sha256": lirical.sha256(tsv_path)},
                    ],
                }), encoding="utf-8")
            first_json = lirical.request_output_dir(root, items[0]) / "result.json"
            first_json.write_text(first_json.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            with self.assertRaisesRegex(lirical.LiricalBaselineError, "OUTPUT_ARTIFACT_HASH_MISMATCH"):
                lirical.parse_batch(items, root, root / "projections-v2")

    def test_post_lock_evaluator_reads_truth_only_from_locked_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            projection_root = root / "projections"
            projection_root.mkdir()
            matrix_requests = []
            locks = []
            approved_cases = []
            for order in range(1, 26):
                request_id = f"g1-{order}"
                case_id = f"case-{order}"
                matrix_requests.append({
                    "requestId": request_id, "caseId": case_id, "groupId": "G1",
                    "selectionOrder": order, "truthFree": True,
                })
                locks.append({"requestId": request_id})
                approved_cases.append({
                    "caseId": case_id, "inclusionStatus": "APPROVED_MAIN",
                    "truthGenes": ["GENE"], "diseaseIds": ["OMIM:1"],
                })
                projection = {
                    "schemaVersion": "stage3a-lirical-output-projection-v2",
                    "rankSource": "LIRICAL_TSV_EXPLICIT_RANK",
                    "truthRead": False, "requestId": request_id, "caseId": case_id,
                    "selectionOrder": order,
                    "variantRankStatus": "NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK",
                    "nativeDiseaseRanks": [
                        {"rank": 1, "diseaseId": "OMIM:2"},
                        {"rank": 2, "diseaseId": "OMIM:1"},
                    ],
                    "geneRanks": [{"rank": 1, "geneSymbol": "GENE"}],
                }
                (projection_root / f"{request_id}.json").write_text(json.dumps(projection), encoding="utf-8")
            approved_cases.extend({
                "caseId": f"backup-{order}", "inclusionStatus": "APPROVED_BACKUP",
                "truthGenes": ["BACKUP"], "diseaseIds": ["OMIM:999"],
            } for order in range(1, 6))
            approved_path = root / "approved.json"
            approved_path.write_text(json.dumps({
                "mainCaseCount": 25, "backupCaseCount": 5, "cases": approved_cases,
            }), encoding="utf-8")
            result = lirical.evaluate_g1(
                projection_root, {"status": "OUTPUTS_LOCKED", "truthRead": False, "requestCount": 25,
                       "successfulRequestCount": 25, "lastOutputLockEpoch": 0, "locks": locks},
                approved_path, {"requestCount": 25, "requests": matrix_requests, "_sha256": "a" * 64})
        self.assertTrue(result["truthReadAfterLastOutputLock"])
        self.assertEqual(1.0, result["geneEndpoint"]["metrics"]["recallAt"]["1"])
        self.assertEqual(0.0, result["diseaseEndpoint"]["metrics"]["recallAt"]["1"])
        self.assertEqual(1.0, result["diseaseEndpoint"]["metrics"]["recallAt"]["3"])
        self.assertEqual("NOT_COMPARABLE_LIRICAL_NATIVE_OUTPUT_HAS_NO_VARIANT_RANK",
                         result["variantEndpoint"]["status"])

    def test_evaluator_rejects_execution_truth_before_opening_truth_manifest(self):
        with self.assertRaisesRegex(lirical.LiricalBaselineError, "EXECUTION_TRUTH_CONTAMINATION"):
            lirical.evaluate_g1(
                Path("missing-projections"),
                {"status": "OUTPUTS_LOCKED", "truthRead": True},
                Path("missing-approved-dataset.json"),
                {"requests": []},
            )

    def test_g1_comparison_requires_full_coverage_and_reports_pairing(self):
        baseline = {"requests": []}
        evaluated = []
        for order in range(1, 26):
            request_id = f"g1-{order}"
            baseline["requests"].append({"requestId": request_id, "caseId": f"case-{order}", "groupId": "G1",
                                         "selectionOrder": order, "geneRank": 2,
                                         "candidateGeneCount": 100, "terminalStatus": "COMPLETED_LOCKED"})
            evaluated.append({"requestId": request_id, "caseId": f"case-{order}", "selectionOrder": order,
                              "geneRank": 1, "candidateGeneCount": 60,
                              "terminalStatus": "COMPLETED_LOCKED"})
        gene_metrics = lirical.rank_metrics(evaluated, "geneRank")
        result = lirical.compare_g1({
            "schemaVersion": "stage3a-lirical-g1-evaluation-v2",
            "truthRead": True, "truthReadAfterLastOutputLock": True,
            "geneEndpoint": {"metrics": gene_metrics}, "evaluated": evaluated,
        }, baseline, "d" * 64)
        self.assertEqual(25, result["paired"]["liricalImproved"])
        self.assertEqual(1.0, result["lirical"]["recallAt"]["1"])
        self.assertEqual("d" * 64, result["lockedExomiserBaselineSha256"])

    def test_corrected_baseline_freeze_preserves_superseded_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            v2_root = root / "evaluation-v2"
            projection_root = v2_root / "projections"
            projection_root.mkdir(parents=True)
            records = []
            for order in range(1, 26):
                request_id = f"g1-{order}"
                path = projection_root / f"{request_id}.json"
                path.write_text(json.dumps({"requestId": request_id}), encoding="utf-8")
                records.append({"requestId": request_id, "projectionSha256": lirical.sha256(path)})
            projection_batch = v2_root / "projection-batch.json"
            projection_batch.write_text(json.dumps({
                "schemaVersion": "stage3a-lirical-g1-projection-batch-v2",
                "requestCount": 25, "truthRead": False, "records": records,
            }), encoding="utf-8")
            evaluation = v2_root / "lirical-g1-evaluation-v2.json"
            evaluation.write_text(json.dumps({
                "schemaVersion": "stage3a-lirical-g1-evaluation-v2",
                "requestCount": 25, "truthReadAfterLastOutputLock": True,
            }), encoding="utf-8")
            comparison = v2_root / "lirical-vs-exomiser-g1-comparison-v2.json"
            comparison.write_text(json.dumps({
                "schemaVersion": "stage3a-lirical-vs-exomiser-g1-comparison-v2",
            }), encoding="utf-8")
            old_evaluation, old_comparison = root / "old-evaluation.json", root / "old-comparison.json"
            raw_run = root / "raw-run"
            manifest_root = raw_run / "manifest"
            manifest_root.mkdir(parents=True)
            lock_summaries = []
            for order in range(1, 26):
                lock_record = {
                    "requestId": f"g1-{order}", "caseId": f"case-{order}",
                    "groupId": "G1", "selectionOrder": order,
                    "terminalStatus": "COMPLETED_LOCKED", "truthRead": False,
                }
                raw_output = lirical.request_output_dir(raw_run, lock_record)
                raw_output.mkdir(parents=True)
                raw_artifact = raw_output / "result.json"
                raw_artifact.write_text("locked raw", encoding="utf-8")
                raw_artifact.chmod(raw_artifact.stat().st_mode & ~0o222)
                lock_record["artifacts"] = [{
                    "logicalPath": "result.json", "bytes": raw_artifact.stat().st_size,
                    "sha256": lirical.sha256(raw_artifact),
                }]
                lock_file = manifest_root / f"g1-{order}.lock.json"
                lock_file.write_text(json.dumps(lock_record), encoding="utf-8")
                lock_summaries.append({
                    "requestId": f"g1-{order}", "lockSha256": lirical.sha256(lock_file),
                })
            batch_manifest = manifest_root / "batch-manifest.json"
            batch_manifest.write_text(json.dumps({
                "status": "OUTPUTS_LOCKED", "truthRead": False,
                "requestCount": 25, "successfulRequestCount": 25, "locks": lock_summaries,
            }), encoding="utf-8")
            old_evaluation.write_text("old evaluation", encoding="utf-8")
            old_comparison.write_text("old comparison", encoding="utf-8")
            old_evaluation_sha256 = lirical.sha256(old_evaluation)
            supersession, lock_path = v2_root / "supersession-manifest.json", root / "evaluation-v2.lock.json"
            lock = lirical.freeze_corrected_baseline(
                v2_root, projection_batch, evaluation, comparison,
                old_evaluation, old_comparison, batch_manifest,
                old_evaluation_sha256, lirical.sha256(old_comparison),
                lirical.sha256(batch_manifest), "4b89035", "a" * 40,
                supersession, lock_path)
            supersession_payload = lirical.read_json(supersession)
        self.assertEqual(29, lock["artifactCount"])
        self.assertEqual("SUPERSEDED_INVALID_JSON_ARRAY_ORDER_AS_RANK",
                         supersession_payload["supersededReason"])
        self.assertFalse(supersession_payload["rawExecution"]["rawOutputsRerun"])
        self.assertEqual(25, supersession_payload["rawExecution"]["artifactCount"])
        self.assertEqual(old_evaluation_sha256,
                         supersession_payload["supersededArtifacts"][0]["sha256"])


if __name__ == "__main__":
    unittest.main()
