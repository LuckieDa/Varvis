import csv
import hashlib
import io
import unittest
import prepare_g2p_mechanisms as subject


class MechanismPreparationTest(unittest.TestCase):
    def row(self, **overrides):
        row = dict.fromkeys(subject.REQUIRED, "")
        row.update({"g2p id": "G2P00001", "hgnc id": "123", "gene symbol": "TEST_ONLY",
                    "disease MONDO": "MONDO:0000001", "allelic requirement": "biallelic_autosomal",
                    "confidence": "definitive", "molecular mechanism": "loss of function",
                    "molecular mechanism support": "inferred", "publications": "12345;67890",
                    "review": "source review is not project adoption"})
        row.update(overrides)
        return row

    def encoded(self, rows):
        buf = io.StringIO(newline="")
        writer = csv.DictWriter(buf, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
        return buf.getvalue().encode("utf-8")

    def prepare(self, rows):
        raw = self.encoded(rows)
        return subject.prepare(raw, hashlib.sha256(raw).hexdigest(), "TEST_ONLY")

    def test_entire_source_and_inferred_mechanism_survive_without_approval(self):
        row = self.row(comments='  multiline\n"scope"  ')
        result = self.prepare([row])
        record = result["records"][0]
        self.assertEqual(row, record["sourceRecord"])
        self.assertEqual("HGNC:123", record["hgncId"])
        self.assertEqual("AUTOSOMAL_RECESSIVE", record["moiCandidate"])
        self.assertEqual("inferred", record["mechanismSupport"])
        self.assertFalse(record["runtimeEligible"])
        self.assertFalse(result["clinicalAcceptance"])
        self.assertEqual(0, result["counts"]["adoptedPvs1Backgrounds"])

    def test_new_gene_requires_data_only_and_missing_disease_never_joins(self):
        result = self.prepare([self.row(), self.row(**{"hgnc id": "999", "disease MONDO": ""}),
                               self.row(**{"hgnc id": "888", "disease MONDO": ""})])
        self.assertEqual(3, result["counts"]["sourceRecords"])
        self.assertEqual(1, result["counts"]["exactBackgroundGroups"])
        self.assertIsNone(result["records"][1]["mondoId"])

    def test_multiple_mechanisms_are_retained_and_never_pick_lof(self):
        result = self.prepare([self.row(), self.row(**{"molecular mechanism": "gain of function"})])
        background = result["backgrounds"][0]
        self.assertEqual(["GAIN_OF_FUNCTION", "LOSS_OF_FUNCTION"], background["sourceMechanisms"])
        self.assertEqual("MULTIPLE_SOURCE_MECHANISMS_REQUIRE_REVIEW", background["status"])
        self.assertFalse(background["runtimeEligible"])

    def test_allelic_requirements_remain_distinct_and_x_is_not_inferred_as_ad(self):
        result = self.prepare([self.row(**{"allelic requirement": x}) for x in
                               ["biallelic_autosomal", "monoallelic_autosomal", "monoallelic_X_hemizygous"]])
        self.assertEqual(3, result["counts"]["exactBackgroundGroups"])
        self.assertIsNone(result["records"][2]["moiCandidate"])
        self.assertEqual("monoallelic_X_hemizygous", result["records"][2]["allelicRequirement"])

    def test_unknown_or_negative_source_never_becomes_lof_from_consequence_label(self):
        for mechanism in ["undetermined", "dominant negative", "undetermined non-loss-of-function", "new label"]:
            with self.subTest(mechanism=mechanism):
                row = self.row(**{"molecular mechanism": mechanism, "variant consequence": "absent gene product"})
                result = self.prepare([row])["records"][0]
                self.assertNotEqual("LOSS_OF_FUNCTION", result["mechanismCandidate"])
                self.assertIn("SOURCE_DOES_NOT_ASSERT_LOSS_OF_FUNCTION", result["reasonCodes"])

    def test_restrictions_and_disputed_association_are_not_discarded(self):
        result = self.prepare([self.row(**{"confidence": "disputed", "cross cutting modifier": "restricted mutation set"})])["records"][0]
        self.assertIn("SOURCE_ASSOCIATION_REQUIRES_ASSESSMENT", result["reasonCodes"])
        self.assertIn("SOURCE_SCOPE_DETAILS_REQUIRE_REVIEW", result["reasonCodes"])

    def test_pin_and_malformed_header_fail_before_output(self):
        raw = self.encoded([self.row()])
        with self.assertRaisesRegex(ValueError, "SHA_MISMATCH"):
            subject.prepare(raw, "0" * 64, "TEST_ONLY")
        for raw in [b"g2p id,g2p id\na,b\n", b"unexpected\na\n"]:
            with self.assertRaisesRegex(ValueError, "COLUMNS_INVALID"):
                subject.prepare(raw, hashlib.sha256(raw).hexdigest(), "TEST_ONLY")

    def test_deterministic_output_and_source_row_identity(self):
        rows = [self.row(), self.row()]
        self.assertEqual(self.prepare(rows), self.prepare(rows))
        result = self.prepare(rows)
        self.assertNotEqual(result["records"][0]["recordId"], result["records"][1]["recordId"])


if __name__ == "__main__":
    unittest.main()
