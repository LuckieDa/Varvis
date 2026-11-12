"""Focused offline checks for bounded compatibility preparation semantics."""

import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from scripts.e4_a2b import prepare_compatible_smoke as prep


class CompatiblePreparationTest(unittest.TestCase):
    def setUp(self):
        self.choice = {"symbol": "GJB2", "caseId": "public-gjb2", "assembly": "GRCh38",
                       "contig": "13", "position": 20189473, "reference": "C", "alternate": "T"}
        self.annotation = {"gene_symbol": "GJB2", "transcript_id": "ENST00000382848.5",
                           "protein_id": "ENSP00000372299", "hgvsp": "ENSP00000372299.3:p.Val37Ile",
                           "amino_acids": "V/I", "protein_start": 37, "protein_end": 37,
                           "variant_allele": "T",
                           "consequence_terms": ["missense_variant"]}
        self.records = [{"id": "public-gjb2", "transcript_consequences": [self.annotation],
                         "input": "13\t20189473\tpublic-gjb2\tC\tT\t.\tPASS\t.",
                         "assembly_name": "GRCh38", "seq_region_name": "13", "start": 20189473,
                         "end": 20189473, "allele_string": "C/T"}]
        self.identity = dict(zip(prep.IDENTITY_COLUMNS,
            ["chr13", "20189473", "C", "T", "hg38", "P29033", "ENST00000382848.5", "V37I"]))

    def test_projection_never_retains_score_or_class_columns(self):
        raw = b"chr13\t20189473\tC\tT\thg38\tP29033\tENST00000382848.5\tV37I\tDO_NOT_PARSE_SCORE\tDO_NOT_PARSE_CLASS\n"
        result = prep.project_am_identity(raw)
        self.assertEqual([self.identity], result)
        self.assertNotIn("DO_NOT_PARSE", json.dumps(result))
        with self.assertRaisesRegex(ValueError, "AM_IDENTITY_COLUMN_COUNT"):
            prep.project_am_identity(b"chr13\t20189473\n")

    def test_exact_intersection_keeps_version_and_allele_and_amino_acid(self):
        found = prep.exact_intersections(self.choice, self.records, [self.identity])
        self.assertEqual(1, len(found))
        self.assertEqual("ENSP00000372299.3", found[0]["proteinIdentity"])
        for field, value in [("transcriptId", "ENST00000382848.4"), ("proteinVariant", "V37A"),
                             ("alternate", "A"), ("genome", "hg19"), ("position", "20189474")]:
            with self.subTest(field=field):
                identity = dict(self.identity, **{field: value})
                self.assertEqual([], prep.exact_intersections(self.choice, self.records, [identity]))

    def test_non_missense_or_unversioned_vep_is_not_compatible(self):
        for changes in [{"consequence_terms": ["synonymous_variant"]},
                        {"transcript_id": "ENST00000382848"}, {"protein_end": 38}]:
            records = copy.deepcopy(self.records)
            records[0]["transcript_consequences"][0].update(changes)
            self.assertEqual([], prep.exact_intersections(self.choice, records, [self.identity]))

    def test_fixed_fixture_is_not_repaired_from_new_vep(self):
        choice = dict(self.choice, transcript="ENST00000382848.5",
                      expectedHgvsp="ENSP00000372299.3:p.Val37Ile",
                      expectedProteinIdentity="ENSP00000372299.3", expectedAminoAcidChange="V37I")
        self.assertEqual(1, prep.checked_selected_annotations([choice], self.records)[0]["selectedTranscriptCount"])
        for field, value, reason in [("expectedHgvsp", "wrong", "SELECTED_HGVSP_MISMATCH"),
                                    ("expectedProteinIdentity", "wrong", "SELECTED_PROTEIN_MISMATCH"),
                                    ("expectedAminoAcidChange", "V37A", "SELECTED_AMINO_ACID_MISMATCH")]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, reason):
                prep.checked_selected_annotations([dict(choice, **{field: value})], self.records)
        self.assertEqual("V37I", choice["expectedAminoAcidChange"])

    def test_scope_and_fixture_version_are_required(self):
        for changes, reason in [({"assembly": "GRCh37"}, "NATIVE_GRCH38_REQUIRED"),
                                ({"alternate": "CT"}, "SNV_REQUIRED")]:
            with self.assertRaisesRegex(ValueError, reason):
                prep.validate_choices([dict(self.choice, **changes)], False)
        with self.assertRaisesRegex(ValueError, "VERSIONED_EXPLICIT_TRANSCRIPT_REQUIRED"):
            prep.validate_choices([dict(self.choice, transcript="ENST00000382848")], True)

    def test_same_case_id_cannot_disguise_a_different_input_or_query_allele(self):
        for field, value in [("input", "13\t20189473\tpublic-gjb2\tC\tA\t.\tPASS\t."),
                             ("seq_region_name", "14"), ("start", 20189474), ("end", 20189474),
                             ("allele_string", "C/A"), ("assembly_name", "GRCh37")]:
            with self.subTest(field=field):
                records = copy.deepcopy(self.records)
                records[0][field] = value
                with self.assertRaisesRegex(ValueError, "VEP_(ORIGINAL_INPUT|RECORD_ALLELE)_MISMATCH"):
                    prep.exact_intersections(self.choice, records, [self.identity])
                with self.assertRaisesRegex(ValueError, "VEP_(ORIGINAL_INPUT|RECORD_ALLELE)_MISMATCH"):
                    prep.checked_selected_annotations([self.choice], records)

    def test_annotation_allele_and_optional_allele_number_cannot_disagree(self):
        for changes in [{"variant_allele": "A"}, {"allele_num": 2}, {"allele_num": "1"}, {"allele_num": True}]:
            with self.subTest(changes=changes):
                records = copy.deepcopy(self.records)
                records[0]["transcript_consequences"][0].update(changes)
                with self.assertRaisesRegex(ValueError, "VEP_ANNOTATION_ALLELE"):
                    prep.exact_intersections(self.choice, records, [self.identity])
        records = copy.deepcopy(self.records)
        records[0]["transcript_consequences"][0]["allele_num"] = 1
        self.assertEqual(1, len(prep.exact_intersections(self.choice, records, [self.identity])))

    def test_diagnostics_scrub_external_machine_paths_and_keep_relative_locators(self):
        message = (r'''failed 'C:\Users\Example User\Temp\input.json' and /tmp/private/input.txt and \\host\share\data '''
                   'and file:///D:/private/item; source relative/receipt.json#gene; https://example.org/source')
        scrubbed = prep.scrub(message, Path("F:/resources"), Path("F:/target"))
        for forbidden in ["C:\\Users", "/tmp/", "\\\\host", "file:///", "D:/private"]:
            self.assertNotIn(forbidden, scrubbed)
        self.assertIn("relative/receipt.json#gene", scrubbed)
        self.assertIn("https://example.org/source", scrubbed)

    def test_panel_stops_before_next_gene_when_any_exact_intersection_exists(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            panel = {"registeredAt": "2026-09-05T16:06:19Z", "candidates": [
                dict(self.choice, queryOrder=i, symbol=symbol) for i, symbol in enumerate(["GJB2", "BRCA1", "HFE"], 1)]}
            source = root / "panel.json"
            prep.write(source, panel)
            args = SimpleNamespace(fixtures=None, panel=source, output=root / "probe.json",
                                   resource_root=root, reference_receipt_from=root / "receipt.json")
            with patch.object(prep, "run_vep", return_value=self.records) as vep, \
                    patch.object(prep, "query_am_identity", return_value=[self.identity]) as am:
                report = prep.execute(args, panel)
            self.assertEqual(1, vep.call_count)
            self.assertEqual(1, am.call_count)
            self.assertEqual(["BRCA1", "HFE"], report["unqueriedRegisteredGenes"])
            self.assertEqual("AWAITING_EXPLICIT_TRANSCRIPT_CHOICE", report["status"])

    def test_receipt_failure_prevents_vep_and_no_fullhash_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            receipt = root / "receipt.json"
            args = SimpleNamespace(resource_root=root, reference_receipt_from=receipt)
            with patch.object(prep, "reuse_or_checksum", side_effect=ValueError("receipt mismatch")) as reuse, \
                    patch.object(prep.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "receipt mismatch"):
                    prep.run_vep(args, {}, {}, [self.choice], root)
            self.assertEqual(receipt, reuse.call_args.args[2])
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
