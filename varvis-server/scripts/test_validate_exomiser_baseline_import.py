import contextlib
import csv
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_exomiser_baseline_import as validator


FIXTURE_PATH = (
    Path(__file__).resolve().parent
    / "fixtures"
    / "exomiser_baseline_synthetic.tsv"
)


def write_tsv(path, rows, columns=None):
    fieldnames = columns or validator.CANONICAL_COLUMNS
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=fieldnames,
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(
            {field: row.get(field, "") for field in fieldnames} for row in rows
        )


def base_row(**overrides):
    row = {name: "" for name in validator.CANONICAL_COLUMNS}
    row.update(
        {
            "case_id": "SYNTH-EXO-TEST",
            "hpo_terms": "HP:0001250;HP:0004322",
            "gene_symbol": "FGFR3",
            "gene_id": "2261",
            "exomiser_gene_rank": "1",
            "exomiser_score": "0.9",
            "exomiser_gene_score": "0.8",
            "source": "EXOMISER_BASELINE",
            "source_version": "exomiser-15.1.0;data-2602",
            "genome_build": "GRCh38",
            "variant_match_status": "GENE_ONLY",
            "evidence": "Synthetic fixture only; not real Exomiser output",
        }
    )
    row.update(overrides)
    return row


class ValidateExomiserBaselineImportTest(unittest.TestCase):

    def test_help_documents_synthetic_boundary(self):
        stdout = io.StringIO()
        with self.assertRaises(SystemExit) as error:
            with contextlib.redirect_stdout(stdout):
                validator.parse_args(["--help"])

        help_text = stdout.getvalue()
        self.assertEqual(0, error.exception.code)
        self.assertIn("EXOMISER_BASELINE", help_text)
        self.assertIn("raw Exomiser", help_text)
        self.assertIn("Synthetic fixtures are not real", help_text)
        self.assertIn("Exomiser output", help_text)

    def test_valid_synthetic_fixture_reports_summary(self):
        summary = validator.validate_file(FIXTURE_PATH)

        self.assertEqual(4, summary["total_rows"])
        self.assertEqual(4, summary["valid_rows"])
        self.assertEqual(0, summary["invalid_rows"])
        self.assertEqual(2, summary["warning_rows"])
        self.assertEqual(4, summary["unique_genes"])
        self.assertEqual(2, summary["variant_level_rows"])
        self.assertEqual(2, summary["gene_only_rows"])
        self.assertEqual([], summary["errors"])

    def test_missing_required_header_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing_header.tsv"
            columns = [name for name in validator.CANONICAL_COLUMNS if name != "warnings"]
            write_tsv(path, [base_row()], columns=columns)

            with self.assertRaisesRegex(ValueError, "Missing: warnings"):
                validator.validate_file(path)

    def test_invalid_rank_and_score_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad_numeric.tsv"
            write_tsv(
                path,
                [
                    base_row(
                        exomiser_gene_rank="0",
                        exomiser_score="not-a-score",
                    )
                ],
            )

            summary = validator.validate_file(path)

        self.assertEqual(1, summary["total_rows"])
        self.assertEqual(0, summary["valid_rows"])
        self.assertEqual(1, summary["invalid_rows"])
        self.assertIn("exomiser_gene_rank", summary["errors"][0])

    def test_invalid_score_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad_score.tsv"
            write_tsv(path, [base_row(exomiser_score="not-a-score")])

            summary = validator.validate_file(path)

        self.assertEqual(1, summary["invalid_rows"])
        self.assertIn("exomiser_score", summary["errors"][0])

    def test_invalid_source_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad_source.tsv"
            write_tsv(path, [base_row(source="PHEN2GENE_IMPORT")])

            summary = validator.validate_file(path)

        self.assertEqual(1, summary["invalid_rows"])
        self.assertIn("source must be EXOMISER_BASELINE", summary["errors"][0])

    def test_invalid_variant_match_status_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad_status.tsv"
            write_tsv(path, [base_row(variant_match_status="PATHOGENIC")])

            summary = validator.validate_file(path)

        self.assertEqual(1, summary["invalid_rows"])
        self.assertIn("variant_match_status", summary["errors"][0])

    def test_local_absolute_path_detection(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "path_leak.tsv"
            write_tsv(path, [base_row(evidence=r"D:\LSD\Project\Tool\exomiser\data")])

            summary = validator.validate_file(path)

        self.assertEqual(1, summary["invalid_rows"])
        self.assertIn("local absolute path", summary["errors"][0])

    def test_normalized_output_writes_valid_rows_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.tsv"
            output_path = root / "normalized.tsv"
            write_tsv(
                input_path,
                [
                    base_row(hpo_terms="hp:0001250, HP:0004322"),
                    base_row(case_id=""),
                ],
            )

            summary = validator.validate_file(input_path, output_path)

            with output_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            normalized_text = output_path.read_text(encoding="utf-8")
        self.assertEqual(1, summary["valid_rows"])
        self.assertEqual(1, summary["invalid_rows"])
        self.assertEqual("HP:0001250;HP:0004322", rows[0]["hpo_terms"])
        self.assertNotIn(str(root), normalized_text)


if __name__ == "__main__":
    unittest.main()
