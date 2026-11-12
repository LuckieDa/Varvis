import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_exomiser_output_to_baseline as converter
import validate_exomiser_baseline_import as validator


VARIANT_COLUMNS = [
    "#RANK",
    "ID",
    "GENE_SYMBOL",
    "ENTREZ_GENE_ID",
    "MOI",
    "EXOMISER_GENE_COMBINED_SCORE",
    "EXOMISER_GENE_PHENO_SCORE",
    "EXOMISER_GENE_VARIANT_SCORE",
    "EXOMISER_VARIANT_SCORE",
    "CONTIG",
    "START",
    "REF",
    "ALT",
    "FILTER",
    "GENOTYPE",
    "FUNCTIONAL_CLASS",
    "HGVS",
    "CONTRIBUTING_VARIANT",
    "MAX_FREQ_SOURCE",
    "MAX_FREQ",
    "EXOMISER_ACMG_CLASSIFICATION",
    "EXOMISER_ACMG_DISEASE_ID",
    "EXOMISER_ACMG_DISEASE_NAME",
]

GENE_COLUMNS = [
    "#RANK",
    "ID",
    "GENE_SYMBOL",
    "ENTREZ_GENE_ID",
    "MOI",
    "EXOMISER_GENE_COMBINED_SCORE",
    "EXOMISER_GENE_PHENO_SCORE",
    "EXOMISER_GENE_VARIANT_SCORE",
]


def write_tsv(path, columns, rows):
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=columns,
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        for row in rows:
            writer.writerow({name: row.get(name, "") for name in columns})


def write_jsonl(path, rows):
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, sort_keys=True) + "\n")


def base_variant(**overrides):
    row = {
        "#RANK": "1",
        "ID": "GENE1:var1",
        "GENE_SYMBOL": "GENE1",
        "ENTREZ_GENE_ID": "101",
        "MOI": "AR",
        "EXOMISER_GENE_COMBINED_SCORE": "0.9000",
        "EXOMISER_GENE_PHENO_SCORE": "0.8000",
        "EXOMISER_GENE_VARIANT_SCORE": "0.7000",
        "EXOMISER_VARIANT_SCORE": "0.6000",
        "CONTIG": "chr17",
        "START": "510930",
        "REF": "g",
        "ALT": "c",
        "FILTER": "PASS",
        "GENOTYPE": "0/1",
        "FUNCTIONAL_CLASS": "MISSENSE_VARIANT",
        "HGVS": "GENE1:c.1G>C",
        "CONTRIBUTING_VARIANT": "true",
        "MAX_FREQ_SOURCE": "gnomAD",
        "MAX_FREQ": "0.0001",
    }
    row.update(overrides)
    return row


def base_gene(**overrides):
    row = {
        "#RANK": "1",
        "ID": "101",
        "GENE_SYMBOL": "GENE1",
        "ENTREZ_GENE_ID": "101",
        "MOI": "AR",
        "EXOMISER_GENE_COMBINED_SCORE": "0.9000",
        "EXOMISER_GENE_PHENO_SCORE": "0.8000",
        "EXOMISER_GENE_VARIANT_SCORE": "0.7000",
    }
    row.update(overrides)
    return row


def jsonl_context(gene, disease_id, disease_name, hpo_terms=None):
    return {
        "geneSymbol": gene,
        "geneScores": [
            {
                "compatibleDiseaseMatches": [
                    {
                        "model": {
                            "diseaseId": disease_id,
                            "diseaseName": disease_name,
                        }
                    }
                ]
            }
        ],
        "priorityResults": {
            "HIPHIVE_PRIORITY": {
                "queryPhenotypeTerms": [
                    {"id": term} for term in (hpo_terms or ["HP:0001250", "HP:0004322"])
                ]
            }
        },
    }


class ConvertExomiserOutputToBaselineTest(unittest.TestCase):

    def test_converts_native_outputs_to_valid_canonical_tsv(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_dir = root / "output"
            input_dir.mkdir()
            output_tsv = root / "converted" / "exomiser_baseline.tsv"
            manifest_path = root / "converted" / "exomiser_baseline_manifest.json"

            write_tsv(
                input_dir / "tiny.genes.tsv",
                GENE_COLUMNS,
                [
                    base_gene(),
                    base_gene(
                        **{
                            "#RANK": "2",
                            "ID": "202",
                            "GENE_SYMBOL": "GENE2",
                            "ENTREZ_GENE_ID": "202",
                            "MOI": "AD",
                            "EXOMISER_GENE_COMBINED_SCORE": "0.5000",
                            "EXOMISER_GENE_PHENO_SCORE": "0.4000",
                            "EXOMISER_GENE_VARIANT_SCORE": "0.3000",
                        }
                    ),
                    base_gene(
                        **{
                            "#RANK": "3",
                            "ID": "303",
                            "GENE_SYMBOL": "GENE3",
                            "ENTREZ_GENE_ID": "303",
                            "MOI": "AR",
                            "EXOMISER_GENE_COMBINED_SCORE": "0.2000",
                            "EXOMISER_GENE_PHENO_SCORE": "0.1000",
                            "EXOMISER_GENE_VARIANT_SCORE": "0.0000",
                        }
                    ),
                ],
            )
            write_tsv(
                input_dir / "tiny.variants.tsv",
                VARIANT_COLUMNS,
                [
                    base_variant(
                        EXOMISER_ACMG_DISEASE_ID="OMIM:123456",
                        EXOMISER_ACMG_DISEASE_NAME="Synthetic disease A",
                    ),
                    base_variant(
                        **{
                            "#RANK": "2",
                            "ID": "GENE2:var2",
                            "GENE_SYMBOL": "GENE2",
                            "ENTREZ_GENE_ID": "202",
                            "MOI": "AD",
                            "EXOMISER_GENE_COMBINED_SCORE": "0.5000",
                            "EXOMISER_GENE_PHENO_SCORE": "0.4000",
                            "EXOMISER_GENE_VARIANT_SCORE": "0.3000",
                            "EXOMISER_VARIANT_SCORE": "0.2500",
                            "CONTIG": "1",
                            "START": "1001",
                            "REF": "A",
                            "ALT": "T",
                            "HGVS": "GENE2:c.2A>T",
                        }
                    ),
                ],
            )
            write_jsonl(
                input_dir / "tiny.jsonl",
                [
                    jsonl_context("GENE2", "OMIM:222222", "Synthetic disease B"),
                    jsonl_context("GENE3", "OMIM:333333", "Synthetic disease C"),
                ],
            )

            args = converter.parse_args(
                [
                    "--input-dir",
                    str(input_dir),
                    "--output-tsv",
                    str(output_tsv),
                    "--sample-id",
                    "HG001",
                    "--assembly",
                    "GRCh38",
                    "--source-version",
                    "15.1.0",
                    "--hpo-terms",
                    "hp:0004322,HP:0001250",
                    "--manifest",
                    str(manifest_path),
                ]
            )
            manifest = converter.convert(args)
            first_text = output_tsv.read_text(encoding="utf-8")
            converter.convert(args)
            second_text = output_tsv.read_text(encoding="utf-8")

            summary = validator.validate_file(output_tsv)
            with output_tsv.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            rows_by_gene = {row["gene_symbol"]: row for row in rows}
            manifest_on_disk = json.loads(manifest_path.read_text(encoding="utf-8"))

            self.assertEqual(first_text, second_text)
            self.assertEqual(0, summary["invalid_rows"])
            self.assertEqual(3, summary["total_rows"])
            self.assertEqual(2, summary["variant_level_rows"])
            self.assertEqual(1, summary["gene_only_rows"])
            self.assertEqual("GRCh38:17:510930:G:C", rows_by_gene["GENE1"]["variant_id"])
            self.assertEqual("17", rows_by_gene["GENE1"]["chrom"])
            self.assertEqual("AUTOSOMAL_RECESSIVE", rows_by_gene["GENE1"]["mode_of_inheritance"])
            self.assertEqual("", rows_by_gene["GENE1"]["exomiser_variant_rank"])
            self.assertEqual("OMIM:123456", rows_by_gene["GENE1"]["disease_id"])
            self.assertEqual("OMIM:222222", rows_by_gene["GENE2"]["disease_id"])
            self.assertEqual("GENE_ONLY", rows_by_gene["GENE3"]["variant_match_status"])
            self.assertEqual("", rows_by_gene["GENE3"]["exomiser_variant_score"])
            self.assertEqual("OMIM:333333", rows_by_gene["GENE3"]["disease_id"])
            self.assertIn(
                "exomiser_variant_rank_not_provided_by_native_output",
                rows_by_gene["GENE1"]["warnings"],
            )
            self.assertEqual("EXOMISER_BASELINE", rows_by_gene["GENE1"]["source"])
            self.assertEqual("15.1.0", rows_by_gene["GENE1"]["source_version"])
            self.assertNotIn(str(root), first_text)
            self.assertNotIn(str(root), manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest, manifest_on_disk)
            self.assertEqual(3, manifest["outputRowCount"])
            self.assertEqual(3, manifest["uniqueGeneCount"])
            self.assertEqual(2, manifest["uniqueVariantCount"])
            self.assertFalse(manifest["patientData"])
            self.assertFalse(manifest["comparisonRun"])
            self.assertEqual(converter.sha256_file(output_tsv), manifest["canonicalTsvSha256"])

    def test_missing_required_variant_column_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_dir = root / "output"
            input_dir.mkdir()
            write_tsv(input_dir / "tiny.genes.tsv", GENE_COLUMNS, [base_gene()])
            write_tsv(
                input_dir / "tiny.variants.tsv",
                [name for name in VARIANT_COLUMNS if name != "CONTIG"],
                [base_variant()],
            )
            write_jsonl(input_dir / "tiny.jsonl", [jsonl_context("GENE1", "", "")])

            args = converter.parse_args(
                [
                    "--input-dir",
                    str(input_dir),
                    "--output-tsv",
                    str(root / "out.tsv"),
                    "--sample-id",
                    "HG001",
                    "--assembly",
                    "GRCh38",
                    "--source-version",
                    "15.1.0",
                ]
            )

            with self.assertRaisesRegex(ValueError, "missing required columns: CONTIG"):
                converter.convert(args)


if __name__ == "__main__":
    unittest.main()
