"""No model calls: strict independent comparisons, coordinates and immutable outputs."""
import tempfile
import unittest
from pathlib import Path
from diagnose_lmna_spliceai import fasta_region, mane_annotation, parse_official_vcf, save


class LmnaDiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)

    def vcf(self, annotation="T|LMNA|0.03|0.05|0.09|0.03|469|33|-6|144", alt="T"):
        path = self.path / "out.vcf"
        path.write_text("#header\n1\t156138613\t.\tC\t" + alt + "\t.\tPASS\tSpliceAI=" + annotation + "\n")
        return path

    def test_exact_scores_and_signed_positions(self):
        actual = parse_official_vcf(self.vcf())
        self.assertEqual("0.09", actual["maximumRawScore"])
        self.assertEqual(-6, actual["deltaPositions"]["DP_DG"])
        self.assertEqual(144, actual["deltaPositions"]["DP_DL"])

    def test_wrong_allele_or_gene_cannot_match(self):
        for annotation, alt in [("T|OTHER|0.03|0.05|0.09|0.03|469|33|-6|144", "T"), ("G|LMNA|0.03|0.05|0.09|0.03|469|33|-6|144", "G")]:
            with self.subTest(annotation=annotation), self.assertRaises(ValueError):
                parse_official_vcf(self.vcf(annotation, alt))

    def test_missing_ambiguous_or_invalid_output_fails(self):
        for annotation in [".", "T|LMNA|.|0.05|0.09|0.03|469|33|-6|144", "T|LMNA|0.03|0.05|1.09|0.03|469|33|-6|144", "T|LMNA|0.03|0.05|0.09|0.03|469|33|-6000|144"]:
            with self.subTest(annotation=annotation), self.assertRaises((ValueError, ArithmeticError)):
                parse_official_vcf(self.vcf(annotation))
        path = self.vcf()
        path.write_text(path.read_text() * 2)
        with self.assertRaises(ValueError):
            parse_official_vcf(path)

    def test_reference_index_counts_newlines(self):
        for newline in (b"\n", b"\r\n"):
            path = self.path / "tiny.fa"
            header = b">1" + newline
            path.write_bytes(header + b"ACGT" + newline + b"TGCA" + newline)
            Path(str(path) + ".fai").write_text(f"1\t8\t{len(header)}\t4\t{4+len(newline)}\n")
            self.assertEqual("GTTGC", fasta_region(path, "1", 3, 7))
            with self.assertRaises(ValueError):
                fasta_region(path, "1", 0, 4)

    def test_existing_output_is_never_overwritten(self):
        path = self.path / "result.json"
        save(path, {"old": "failure"})
        before = path.read_bytes()
        with self.assertRaises(FileExistsError):
            save(path, {"replacement": "pass"})
        self.assertEqual(before, path.read_bytes())

    def test_mane_identity_must_be_explicit(self):
        with self.assertRaises(ValueError):
            mane_annotation({"id": "ENST00000368300", "version": 8})

    def test_mane_control_uses_zero_based_starts_and_inclusive_ends(self):
        coordinates = [(156114711,156115274),(156130617,156130773),(156134403,156134528),
                       (156134805,156134975),(156135187,156135312),(156135901,156136121),
                       (156136214,156136436),(156136921,156137028),(156137113,156137232),
                       (156137654,156137743),(156138488,156138757),(156139080,156140081)]
        tx = {"id": "ENST00000368300", "version": 9, "assembly_name": "GRCh38", "strand": 1,
              "seq_region_name": "1", "start": 156114711, "end": 156140081,
              "Exon": [{"start": a, "end": b} for a, b in coordinates]}
        row = mane_annotation(tx)
        self.assertEqual("156114710", row["TX_START"])
        self.assertEqual("156140081", row["TX_END"])
        self.assertEqual("156138487", row["EXON_START"].split(",")[-3])
        self.assertEqual("156138757", row["EXON_END"].split(",")[-3])


if __name__ == "__main__":
    unittest.main()
