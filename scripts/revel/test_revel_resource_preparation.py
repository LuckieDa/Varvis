import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import fetch_revel_hnf1a_segment as fetcher
import prepare_hnf1a_revel_resource as prepare


class TinyFasta:
    def __init__(self, base):
        self.value = base

    def base(self, position):
        return self.value


class ResourceChecksTest(unittest.TestCase):
    def setUp(self):
        self.model = {"cdsSequence": "CCTTAA", "cdsLength": 6,
                      "exons": [{"genomicStart": 100, "genomicEnd": 105, "cdsStart": 1}]}
        self.row = {"grch38_pos": "100", "hg19_pos": "200", "ref": "C", "alt": "A",
                    "aaref": "P", "aaalt": "T", "REVEL": "0.150"}

    def test_real_codon_translation_and_inclusive_low_boundary(self):
        check = prepare.verify_record(self.row, self.model, TinyFasta("C"), TinyFasta("C"))
        self.assertEqual({"status": "VERIFIED", "cdsPosition": 1, "proteinPosition": 1,
                          "codon": "CCT/ACT", "scoreBin": "LOW"}, check)

    def test_source_amino_acid_conflict_is_not_accepted(self):
        self.row["aaalt"] = "S"
        check = prepare.verify_record(self.row, self.model, TinyFasta("C"), TinyFasta("C"))
        self.assertEqual("AMINO_ACID_MISMATCH", check["status"])

    def test_original_build_ref_mismatch_is_not_labeled_verified(self):
        check = prepare.verify_record(self.row, self.model, TinyFasta("C"), TinyFasta("G"))
        self.assertEqual("FASTA_REF_MISMATCH", check["status"])

    def test_missing_mapping_and_non_finite_score_are_not_zero(self):
        self.row["grch38_pos"] = "."
        self.assertEqual("MAPPING_MISSING", prepare.verify_record(self.row, self.model, None, None)["status"])
        self.row.update(grch38_pos="100", REVEL="NaN")
        self.assertEqual("INVALID_SCORE", prepare.verify_record(self.row, self.model, TinyFasta("C"), TinyFasta("C"))["status"])

    def test_enumeration_excludes_synonymous_and_stop_changes(self):
        alleles = prepare.enumerate_missense(self.model)
        self.assertIn((100, "C", "A"), alleles)
        self.assertNotIn((102, "T", "C"), alleles)
        self.assertFalse(any(position >= 103 for position, _, _ in alleles))

    def test_full_body_200_is_refused_before_read(self):
        class Response:
            status = 200
            headers = {"Content-Length": str(fetcher.ARCHIVE_BYTES)}

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, amount):
                raise AssertionError("A 200 full-archive body must never be read")

        with tempfile.TemporaryDirectory() as directory, patch.object(fetcher.urllib.request, "urlopen", return_value=Response()):
            with self.assertRaisesRegex(ValueError, "exact byte range"):
                fetcher.fetch_range(Path(directory), 0, 10, "refused")
            receipt = json.loads((Path(directory) / "refused.json").read_text())
            self.assertEqual(200, receipt["status"])
            self.assertIn("failure", receipt)

    def test_wrong_content_range_is_refused_before_read(self):
        class Response:
            status = 206
            headers = {"Content-Range": "bytes 0-20/35934024", "Content-Length": "21"}

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, amount):
                raise AssertionError("Mismatched content range must never be read")

        with tempfile.TemporaryDirectory() as directory, patch.object(fetcher.urllib.request, "urlopen", return_value=Response()):
            with self.assertRaisesRegex(ValueError, "exact byte range"):
                fetcher.fetch_range(Path(directory), 10, 20, "refused")


if __name__ == "__main__":
    unittest.main()
