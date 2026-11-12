import contextlib
import csv
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_phen2gene_to_varvis_import as converter


class ConvertPhen2GeneToVarvisImportTest(unittest.TestCase):

    def test_help_documents_local_input_and_source_version(self):
        stdout = io.StringIO()
        with self.assertRaises(SystemExit) as error:
            with contextlib.redirect_stdout(stdout):
                converter.parse_args(["--help"])

        help_text = stdout.getvalue()
        self.assertEqual(0, error.exception.code)
        self.assertIn(".associated_gene_list", help_text)
        self.assertIn("Phen2Gene-1.2.3;hpo2gene-1.1.0", help_text)
        self.assertIn("not written into the generated TSV rows", help_text)

    def test_converts_json_and_skips_malformed_item(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.json"
            output_path = root / "output.tsv"
            input_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "Gene": "KCNQ2",
                                "Rank": "1",
                                "Gene ID": "KCNQ2",
                                "Score": "1.0",
                                "Status": "SeedGene",
                            },
                            {"Gene": "", "Rank": "2", "Score": "bad"},
                        ],
                        "errors": ["Example upstream warning"],
                    }
                ),
                encoding="utf-8",
            )

            summary = converter.convert(
                input_path,
                output_path,
                ["HP:0001250", "HP:0004322"],
                "phen2gene-api-unknown-version",
            )

            with output_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            self.assertEqual(1, len(rows))
            self.assertEqual("KCNQ2", rows[0]["gene_symbol"])
            self.assertEqual("", rows[0]["gene_id"])
            self.assertEqual("PHEN2GENE_IMPORT", rows[0]["source"])
            self.assertEqual("HP:0001250;HP:0004322", rows[0]["matched_hpo_terms"])
            self.assertEqual("Phen2Gene candidate gene prior", rows[0]["evidence"])
            self.assertEqual(1, summary["skipped_items"])
            self.assertIn("Example upstream warning", summary["warnings"])
            self.assertNotIn(str(root), output_path.read_text(encoding="utf-8"))

    def test_empty_result_writes_header_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "empty.json"
            output_path = root / "empty.tsv"
            input_path.write_text('{"results": [], "errors": []}', encoding="utf-8")

            summary = converter.convert(
                input_path,
                output_path,
                ["HP:0001250"],
                "phen2gene-api-unknown-version",
            )

            self.assertEqual(0, summary["output_rows"])
            self.assertEqual(
                "\t".join(converter.OUTPUT_COLUMNS),
                output_path.read_text(encoding="utf-8").strip(),
            )

    def test_accepts_standard_associated_gene_list_suffix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "tiny.associated_gene_list"
            output_path = root / "output.tsv"
            input_path.write_text(
                "Rank\tGene\tID\tScore\tStatus\n"
                "1\tFGFR3\t2261\t1.0\tSeedGene\n",
                encoding="utf-8",
            )

            summary = converter.convert(
                input_path,
                output_path,
                ["HP:0001250", "HP:0004322"],
                "Phen2Gene-1.2.3;hpo2gene-1.1.0",
            )

            with output_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            self.assertEqual(1, summary["output_rows"])
            self.assertEqual("FGFR3", rows[0]["gene_symbol"])
            self.assertEqual("2261", rows[0]["gene_id"])

    def test_scores_above_one_are_normalized_by_maximum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "scores.json"
            output_path = root / "scores.tsv"
            input_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {"Gene": "A", "Rank": 1, "Score": 20},
                            {"Gene": "B", "Rank": 2, "Score": 10},
                        ]
                    }
                ),
                encoding="utf-8",
            )

            summary = converter.convert(
                input_path,
                output_path,
                ["HP:0001250"],
                "test-version",
            )

            with output_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            self.assertEqual("1", rows[0]["phenotype_score"])
            self.assertEqual("0.5", rows[1]["phenotype_score"])
            self.assertEqual("normalized_by_max_input_score", summary["score_strategy"])


if __name__ == "__main__":
    unittest.main()
