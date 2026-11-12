"""Boundary checks without TensorFlow, model execution or resource mutation."""
import copy
from decimal import InvalidOperation
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import run_spliceai_native_probe as native


def variant():
    return {"queryId": "public-snv", "geneSymbol": "SAMD11",
            "selectedTranscriptId": "PROBE_ONLY_NOT_ASSOCIATED",
            "allele": {"assembly": "GRCh38", "contig": "1", "position": 925775,
                       "reference": "G", "alternate": "T"}}


class FakeSequence:
    def __len__(self):
        return 248956422

    def __getitem__(self, key):
        return SimpleNamespace(seq="G")


class NativeProbeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "request.json"
        self.request = {"requestSchemaVersion": native.raw.BATCH_REQUEST_SCHEMA,
                        "requestId": "fixed-probe", "assembly": "GRCh38",
                        "distanceBp": 4999, "mask": 0, "variants": [variant()]}
        self.ann = SimpleNamespace(ref_fasta={"1": FakeSequence()},
                                   get_name_and_strand=Mock(return_value=(["SAMD11"], ["+"], [0])))
        self.official = SimpleNamespace(normalise_chrom=lambda chrom, _: chrom,
            get_delta_scores=Mock(return_value=["T|SAMD11|0.40|0.00|0.96|0.44|147|1098|-2|25"]))

    def load(self):
        self.path.write_text(json.dumps(self.request), encoding="utf-8")
        return native.load_request(self.path)

    def test_valid_request_preserves_exact_identity(self):
        self.assertEqual(self.load(), self.request)

    def test_invalid_request_matrix(self):
        cases = [("assembly", "GRCh37"), ("distanceBp", 5000), ("mask", 1),
                 ("requestId", "bad\nrequest"), ("requestSchemaVersion", "other")]
        for key, value in cases:
            with self.subTest(key=key):
                original = self.request[key]
                self.request[key] = value
                with self.assertRaises(native.raw.ProbeError):
                    self.load()
                self.request[key] = original

    def test_invalid_alleles_matrix(self):
        for value in ("<DEL>", "T,C", "*", "N", "t", "", "A" * 4097, "G"):
            with self.subTest(value=value[:20]):
                self.request["variants"][0]["allele"]["alternate"] = value
                with self.assertRaises(native.raw.ProbeError):
                    self.load()

    def test_boolean_position_rejected(self):
        self.request["variants"][0]["allele"]["position"] = True
        with self.assertRaises(native.raw.ProbeError):
            self.load()

    def test_out_of_scope_contig_rejected(self):
        self.request["variants"][0]["allele"]["contig"] = "../../reference"
        with self.assertRaises(native.raw.ProbeError):
            self.load()

    def test_duplicate_exact_allele_rejected(self):
        extra = copy.deepcopy(variant())
        extra["queryId"] = "second"
        self.request["variants"].append(extra)
        with self.assertRaises(native.raw.ProbeError):
            self.load()

    def test_batch_limit(self):
        self.request["variants"] = [variant() for _ in range(11)]
        for index, value in enumerate(self.request["variants"]):
            value["queryId"] = str(index)
            value["allele"]["position"] += index
        with self.assertRaisesRegex(native.raw.ProbeError, "BATCH_LIMIT"):
            self.load()

    def test_reference_mismatch_prevents_prediction(self):
        value = variant()
        value["allele"]["reference"] = "A"
        result = native.evaluate_record(value, self.ann, self.official)
        self.assertEqual(result["reasonCode"], "REFERENCE_ALLELE_MISMATCH")
        self.assertEqual(result["annotations"], [])
        self.official.get_delta_scores.assert_not_called()

    def test_position_outside_reference_prevents_prediction(self):
        value = variant()
        value["allele"]["position"] = 300000000
        result = native.evaluate_record(value, self.ann, self.official)
        self.assertEqual(result["reasonCode"], "POSITION_OUTSIDE_REFERENCE")
        self.official.get_delta_scores.assert_not_called()

    def test_incomplete_window_prevents_prediction(self):
        value = variant()
        value["allele"]["position"] = 100
        result = native.evaluate_record(value, self.ann, self.official)
        self.assertEqual(result["reasonCode"], "REFERENCE_WINDOW_INCOMPLETE")
        self.official.get_delta_scores.assert_not_called()

    def test_no_annotation_has_specific_reason(self):
        self.ann.get_name_and_strand.return_value = ([], [], [])
        self.official.get_delta_scores.return_value = []
        result = native.evaluate_record(variant(), self.ann, self.official)
        self.assertEqual(result["status"], "NO_RECORD")
        self.assertEqual(result["reasonCode"], "NO_MATCHING_GENE_ANNOTATION")

    def test_no_score_is_not_benign_or_no_record(self):
        self.official.get_delta_scores.return_value = []
        result = native.evaluate_record(variant(), self.ann, self.official)
        self.assertEqual(result["status"], "UNAVAILABLE")
        self.assertEqual(result["reasonCode"], "OFFICIAL_FUNCTION_RETURNED_NO_SCORE")

    def test_all_fields_and_gene_are_retained_without_transcript_claim(self):
        value = variant()
        value["geneSymbol"] = "REQUESTED_GENE_IS_NOT_PROOF"
        result = native.evaluate_record(value, self.ann, self.official)
        self.assertEqual(result["status"], "HIT")
        annotation = result["annotations"][0]
        self.assertEqual(annotation["geneSymbol"], "SAMD11")
        self.assertEqual(annotation["maximumRawScore"], "0.96")
        self.assertEqual(annotation["deltaScores"], dict(DS_AG="0.40", DS_AL="0.00", DS_DG="0.96", DS_DL="0.44"))
        self.assertEqual(annotation["deltaPositions"], dict(DP_AG=147, DP_AL=1098, DP_DG=-2, DP_DL=25))
        self.assertNotIn("transcriptId", annotation)
        args = self.official.get_delta_scores.call_args.args
        self.assertEqual((args[0].chrom, args[0].pos, args[0].ref, args[0].alts), ("1", 925775, "G", ("T",)))
        self.assertEqual(args[2:], (4999, 0))

    def test_invalid_score_and_position_rejected(self):
        for score, position in (("1.10", "1"), ("-0.1", "1"), ("NaN", "1"), ("0.1", "5000")):
            with self.subTest(score=score, position=position):
                self.official.get_delta_scores.return_value = [f"T|SAMD11|{score}|0.00|0.96|0.44|{position}|1098|-2|25"]
                with self.assertRaises((native.raw.ProbeError, InvalidOperation)):
                    native.evaluate_record(variant(), self.ann, self.official)

    def test_wrong_output_allele_rejected(self):
        self.official.get_delta_scores.return_value = ["C|SAMD11|0.40|0.00|0.96|0.44|147|1098|-2|25"]
        with self.assertRaisesRegex(native.raw.ProbeError, "ALLELE_MISMATCH"):
            native.evaluate_record(variant(), self.ann, self.official)

    def test_original_output_is_not_overwritten(self):
        output = Path(self.temporary.name) / "result.json"
        native.write_new_json(output, {"original": True})
        before = output.read_bytes()
        with self.assertRaises(FileExistsError):
            native.write_new_json(output, {"replacement": True})
        self.assertEqual(before, output.read_bytes())

    def test_isolated_keras_config_is_pinned(self):
        config = Path(self.temporary.name) / "keras.json"
        config.write_text(json.dumps(native.KERAS_CONFIG), encoding="utf-8")
        with patch.object(native, "keras_config_path", return_value=config):
            self.assertEqual(native.verify_keras_config(), native.raw.sha256_file(config))

    def test_changed_or_missing_keras_config_is_rejected(self):
        config = Path(self.temporary.name) / "keras.json"
        with patch.object(native, "keras_config_path", return_value=config):
            with self.assertRaises(FileNotFoundError):
                native.verify_keras_config()
            config.write_text(json.dumps(dict(native.KERAS_CONFIG, floatx="float64")), encoding="utf-8")
            with self.assertRaisesRegex(native.raw.ProbeError, "KERAS_CONFIGURATION_MISMATCH"):
                native.verify_keras_config()


if __name__ == "__main__":
    unittest.main()
