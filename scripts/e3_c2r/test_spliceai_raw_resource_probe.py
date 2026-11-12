"""Direct tests for the machine-readable SpliceAI raw batch boundary."""

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.e3_c2r import spliceai_raw_resource_probe as probe


class SpliceAiRawBatchTest(unittest.TestCase):
    def request(self, alternate: str = "T") -> dict:
        return {
            "requestSchemaVersion": probe.BATCH_REQUEST_SCHEMA,
            "requestId": "batch-001",
            "assembly": "GRCh38",
            "distanceBp": 4999,
            "mask": 0,
            "variants": [
                {
                    "queryId": "query-001",
                    "allele": {
                        "assembly": "GRCh38",
                        "contig": "1",
                        "position": 925775,
                        "reference": "G",
                        "alternate": alternate,
                    },
                    "geneSymbol": "SAMD11",
                    "selectedTranscriptId": "ENST00000342066.8",
                }
            ],
        }

    def test_load_batch_request_freezes_raw_4999_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            path = Path(temp_name) / "request.json"
            path.write_text(json.dumps(self.request()), encoding="utf-8")
            loaded = probe.load_batch_request(path)

        self.assertEqual("GRCh38", loaded["assembly"])
        self.assertEqual(4999, loaded["distanceBp"])
        self.assertEqual(0, loaded["mask"])

        invalid = self.request()
        invalid["distanceBp"] = 5000
        with tempfile.TemporaryDirectory() as temp_name:
            path = Path(temp_name) / "request.json"
            path.write_text(json.dumps(invalid), encoding="utf-8")
            with self.assertRaisesRegex(probe.ProbeError, "BATCH_DISTANCE_INVALID"):
                probe.load_batch_request(path)

    def test_success_preserves_exact_allele_gene_ds_dp_and_maximum(self) -> None:
        calls: list[tuple[Path, Path, Path, int]] = []

        def fake_cli(input_vcf: Path, output_vcf: Path, reference: Path, timeout: int) -> float:
            calls.append((input_vcf, output_vcf, reference, timeout))
            output_vcf.write_text(
                "##fileformat=VCFv4.2\n"
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                "1\t925775\tquery-001\tG\tT\t.\tPASS\t"
                "SpliceAI=T|SAMD11|0.40|0.00|0.96|0.44|147|1098|-2|25\n",
                encoding="utf-8",
            )
            return 1.25

        result = probe.run_batch_request(
            self.request(), Path("reference.fa"), 600, fake_cli
        )

        self.assertEqual(1, len(calls))
        self.assertEqual(
            {
                "resultSchemaVersion": probe.BATCH_RESULT_SCHEMA,
                "requestId": "batch-001",
                "status": "SUCCEEDED",
                "results": [
                    {
                        "queryId": "query-001",
                        "allele": {
                            "assembly": "GRCh38",
                            "contig": "1",
                            "position": 925775,
                            "reference": "G",
                            "alternate": "T",
                        },
                        "status": "HIT",
                        "exactAlleleMatch": True,
                        "annotations": [
                            {
                                "geneSymbol": "SAMD11",
                                "deltaScores": {
                                    "DS_AG": "0.40",
                                    "DS_AL": "0.00",
                                    "DS_DG": "0.96",
                                    "DS_DL": "0.44",
                                },
                                "deltaPositions": {
                                    "DP_AG": 147,
                                    "DP_AL": 1098,
                                    "DP_DG": -2,
                                    "DP_DL": 25,
                                },
                                "maximumRawScore": "0.96",
                            }
                        ],
                    }
                ],
            },
            result,
        )

    def test_same_locus_different_alt_is_no_record(self) -> None:
        def fake_cli(input_vcf: Path, output_vcf: Path, reference: Path, timeout: int) -> float:
            output_vcf.write_text(
                "##fileformat=VCFv4.2\n"
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                "1\t925775\tquery-001\tG\tA\t.\tPASS\t"
                "SpliceAI=A|SAMD11|0.01|0.00|0.01|0.00|1|2|3|4\n",
                encoding="utf-8",
            )
            return 0.1

        result = probe.run_batch_request(
            self.request(), Path("reference.fa"), 600, fake_cli
        )

        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual("NO_RECORD", result["results"][0]["status"])
        self.assertFalse(result["results"][0]["exactAlleleMatch"])
        self.assertEqual([], result["results"][0]["annotations"])

    def test_write_vcf_declares_each_requested_contig(self) -> None:
        variants = (
            {
                "caseId": "q1",
                "contig": "1",
                "position": 1,
                "reference": "A",
                "alternate": "G",
            },
            {
                "caseId": "q2",
                "contig": "2",
                "position": 2,
                "reference": "C",
                "alternate": "T",
            },
        )
        with tempfile.TemporaryDirectory() as temp_name:
            path = Path(temp_name) / "batch.vcf"
            probe.write_vcf(path, variants)
            content = path.read_text(encoding="utf-8")

        self.assertIn("##contig=<ID=1>\n", content)
        self.assertIn("##contig=<ID=2>\n", content)

    def test_timeout_is_structured_and_path_free(self) -> None:
        def timeout_cli(*args: object) -> float:
            raise subprocess.TimeoutExpired(["spliceai"], 600)

        result = probe.run_batch_request(
            self.request(), Path("reference.fa"), 600, timeout_cli
        )

        self.assertEqual("TIMEOUT", result["status"])
        self.assertEqual("SPLICEAI_CLI_TIMEOUT", result["reasonCode"])
        self.assertEqual([], result["results"])
        probe.ensure_path_free(result)

    def test_cli_failure_is_structured_without_diagnostic_leakage(self) -> None:
        def failed_cli(*args: object) -> float:
            raise probe.ProbeError("SPLICEAI_CLI_FAILED: /tmp/private/output")

        result = probe.run_batch_request(
            self.request(), Path("reference.fa"), 600, failed_cli
        )

        self.assertEqual("FAILED", result["status"])
        self.assertEqual("SPLICEAI_CLI_FAILED", result["reasonCode"])
        self.assertEqual([], result["results"])
        self.assertNotIn("private", json.dumps(result))

    def test_run_cli_pins_distance_and_raw_mask_in_one_process(self) -> None:
        observed: list[list[str]] = []

        def fake_run(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
            observed.append(command)
            output = Path(command[command.index("-O") + 1])
            output.write_text("##fileformat=VCFv4.2\n", encoding="utf-8")
            return subprocess.CompletedProcess(command, 0, "", "")

        with tempfile.TemporaryDirectory() as temp_name:
            temp = Path(temp_name)
            input_vcf = temp / "input.vcf"
            output_vcf = temp / "output.vcf"
            input_vcf.write_text("##fileformat=VCFv4.2\n", encoding="utf-8")
            with mock.patch.object(probe.subprocess, "run", side_effect=fake_run):
                probe.run_cli(input_vcf, output_vcf, temp / "reference.fa", 600)

        self.assertEqual(1, len(observed))
        command = observed[0]
        self.assertEqual("4999", command[command.index("-D") + 1])
        self.assertEqual("0", command[command.index("-M") + 1])


if __name__ == "__main__":
    unittest.main()
