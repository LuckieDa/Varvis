import csv
import tempfile
import unittest
from pathlib import Path

import build_gene_interval_tsv as builder


class BuildGeneIntervalTsvTest(unittest.TestCase):

    def test_builds_stable_primary_contig_intervals_from_gtf(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "tiny.gtf"
            output_path = root / "intervals.tsv"
            input_path.write_text(
                "\n".join(
                    [
                        "chr4\ttest\tgene\t106300000\t106400000\t.\t+\t.\tgene_id \"ENSGPPA2\"; gene_name \"PPA2\";",
                        "chr4\ttest\texon\t106320000\t106321000\t.\t+\t.\tgene_id \"ENSGPPA2\"; gene_name \"PPA2\";",
                        "chrM\ttest\ttranscript\t1\t200\t.\t+\t.\tgene_id \"ENSGMT\"; gene_name \"MTTEST\";",
                        "KI270728.1\ttest\tgene\t1\t200\t.\t+\t.\tgene_id \"ALT\"; gene_name \"ALTGENE\";",
                        "chr7\ttest\texon\t100\t150\t.\t+\t.\tgene_id \"FALL\"; gene_name \"FALLBACK\";",
                        "chr7\ttest\texon\t120\t220\t.\t+\t.\tgene_id \"FALL\"; gene_name \"FALLBACK\";",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            intervals, stats = builder.build_intervals(input_path, "GRCh37", False)
            builder.write_tsv(output_path, "GRCh37", intervals)

            with output_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))

            self.assertEqual(["PPA2", "FALLBACK", "MTTEST"], [row["gene_symbol"] for row in rows])
            self.assertEqual("4", rows[0]["chromosome"])
            self.assertEqual("106300000", rows[0]["start"])
            self.assertEqual("106400000", rows[0]["end"])
            self.assertEqual("7", rows[1]["chromosome"])
            self.assertEqual("100", rows[1]["start"])
            self.assertEqual("220", rows[1]["end"])
            self.assertEqual("MT", rows[2]["chromosome"])
            self.assertEqual(1, stats["non_primary_contig_rows"])
            self.assertEqual(3, stats["interval_rows"])

    def test_include_non_primary_contigs_is_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "tiny.gff3"
            input_path.write_text(
                "GL000220.1\ttest\tgene\t10\t20\t.\t+\t.\tID=gene:ALT;Name=ALTGENE\n",
                encoding="utf-8",
            )

            intervals, _stats = builder.build_intervals(input_path, "GRCh38", True)

            self.assertEqual(1, len(intervals))
            self.assertIn(("ALTGENE", "GL000220.1"), intervals)

    def test_optional_feature_output_preserves_exons(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "tiny.gtf"
            output_path = root / "features.tsv"
            input_path.write_text(
                "\n".join(
                    [
                        'chr4\ttest\tgene\t100\t300\t.\t+\t.\tgene_name "GENE1";',
                        'chr4\ttest\texon\t120\t140\t.\t+\t.\tgene_name "GENE1";',
                        'chr4\ttest\texon\t220\t240\t.\t+\t.\tgene_name "GENE1";',
                    ]
                ) + "\n",
                encoding="utf-8",
            )

            intervals, stats = builder.build_feature_intervals(input_path, "GRCh37", False)
            builder.write_feature_tsv(output_path, "GRCh37", intervals)

            with output_path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))

            self.assertEqual(["GENE", "EXON", "EXON"], [row["feature_type"] for row in rows])
            self.assertEqual(3, stats["interval_rows"])


if __name__ == "__main__":
    unittest.main()
