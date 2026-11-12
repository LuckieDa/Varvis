"""Independent tiny source/sequence controls; no real assay or evidence qualification."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from prepare_splice_observations import (CDS_COORDINATES, CLAIM_SCHEMA, SCHEMA, decode_source,
                                         prepare_observation, sha)


def genbank():
    # RNA: GG|ATG AAA|CCC GGG|TTT TAA|CC. CDS 3..20 -> MKPGF*.
    return b'''LOCUS       SYNTHETIC 22 bp mRNA
VERSION     NM_000001.1
FEATURES             Location/Qualifiers
     gene            1..22
                     /gene="SYNTHETIC"
                     /db_xref="HGNC:1"
     exon            1..2
     exon            3..8
     exon            9..14
     exon            15..22
     CDS             3..20
                     /protein_id="NP_000001.1"
                     /translation="MKPGF"
ORIGIN
        1 ggatgaaacccgggttttaacc
//
'''


def fixture(html=False):
    quote = "RNA sequencing showed deletion of exon 3."
    text = "SYNTHETIC NM_000001.1 c.12+1G>A. " + quote
    source = ("<html><body><p>SYNTHETIC <i>NM_000001.1</i> c.12+1G&gt;A.</p><p>"
              + quote + "</p><script>ignore this</script></body></html>") if html else text
    raw = source.encode("utf-8")
    reference = genbank()
    claim = dict(schemaVersion=CLAIM_SCHEMA, observationId="TOY_RNA", transcript="NM_000001.1", cHgvsc="c.12+1G>A",
                 source=dict(url="https://example.org/toy-paper", sha256=sha(raw), mediaType="text/html" if html else "text/plain",
                             encoding="utf-8", quote=quote, quoteStart=text.index(quote), quoteEnd=len(text)),
                 refseq=dict(url="https://example.org/NM_000001.1.gb", sha256=sha(reference)),
                 rnaDeletion=dict(coordinateSystem=CDS_COORDINATES, exonOrdinal=3))
    return raw, reference, claim


class PrepareSpliceObservationTest(unittest.TestCase):
    def test_whole_exon_replay_is_pinned_review_only_and_never_adopted(self):
        raw, reference, claim = fixture()
        before = deepcopy(claim)
        result = prepare_observation(raw, reference, claim)
        self.assertEqual(before, claim)
        self.assertEqual(SCHEMA, result["schemaVersion"])
        self.assertEqual("RNA_OBSERVATION_SOURCE_BOUND", result["status"])
        self.assertFalse(result["runtimeEligible"])
        self.assertTrue(result["reviewRequired"])
        self.assertFalse(result["scientificEvaluationPerformed"])
        deletion = result["replayedDeletion"]
        self.assertEqual((7, 12, 9, 14, 6), (deletion["start"], deletion["end"], deletion["referenceRnaStart"],
                                            deletion["referenceRnaEnd"], deletion["deletedCdsLength"]))
        self.assertEqual("CCCGGG", deletion["deletedCdsSequence"])
        self.assertEqual([dict(referenceExonOrdinal=3, referenceRnaStart=9, referenceRnaEnd=14,
                               deletedRnaBases=6, fullExonRemoved=True)], deletion["affectedExons"])
        effect = result["sequenceEffect"]
        self.assertEqual("ATGAAATTTTAA", effect["alteredCdsSequence"])
        self.assertEqual("MKF*", effect["alteredProteinThroughFirstTermination"])
        self.assertEqual("IN_FRAME", effect["frame"])
        self.assertEqual(3, effect["firstChangedResiduePosition"])
        self.assertEqual((10, 12), (effect["firstStopCodonStartCds"], effect["firstStopCodonEndCds"]))
        self.assertFalse(effect["terminationBeforeRetainedReferenceStop"])
        self.assertEqual(sha(raw), result["sourceReceipts"][0]["sha256"])
        self.assertEqual(sha(reference), result["sourceReceipts"][1]["sha256"])
        self.assertIn("RESIDUAL_NORMAL_TRANSCRIPT_ASSESSED_SCOPE_AND_QUANTIFICATION", result["missingFacts"])
        self.assertNotIn("review", result)
        self.assertNotIn("appliedStrength", result)

    def test_html_entity_and_inline_markup_preserve_exact_visible_locator(self):
        raw, reference, claim = fixture(html=True)
        result = prepare_observation(raw, reference, claim)
        self.assertEqual("RNA_OBSERVATION_SOURCE_BOUND", result["status"])
        self.assertNotIn("ignore this", decode_source(raw, claim["source"]))
        self.assertEqual(claim["source"]["quoteStart"], result["sourceReceipts"][0]["quoteStart"])

    def test_version_sentence_punctuation_does_not_change_exact_accession_matching(self):
        raw, reference, claim = fixture()
        raw = raw.replace(b"NM_000001.1 ", b"NM_000001.1. ")
        claim["source"]["sha256"] = sha(raw)
        claim["source"]["quoteStart"] += 1
        claim["source"]["quoteEnd"] += 1
        self.assertEqual("NM_000001.1", prepare_observation(raw, reference, claim)["transcript"])

    def test_explicit_six_base_deletion_can_cross_exon_junction_without_dna_mapping(self):
        raw, reference, claim = fixture()
        claim["rnaDeletion"] = dict(coordinateSystem=CDS_COORDINATES, start=5, end=10, deletedSequence="AACCCG")
        result = prepare_observation(raw, reference, claim)
        self.assertEqual("ATGAGGTTTTAA", result["sequenceEffect"]["alteredCdsSequence"])
        self.assertEqual("MRF*", result["sequenceEffect"]["alteredProteinThroughFirstTermination"])
        self.assertEqual([2, 3], [exon["referenceExonOrdinal"] for exon in result["replayedDeletion"]["affectedExons"]])
        self.assertEqual([2, 4], [exon["deletedRnaBases"] for exon in result["replayedDeletion"]["affectedExons"]])
        self.assertFalse(any(exon["fullExonRemoved"] for exon in result["replayedDeletion"]["affectedExons"]))

    def test_one_base_deletion_reports_mechanical_frameshift_without_claiming_observed_protein(self):
        raw, reference, claim = fixture()
        claim["rnaDeletion"] = dict(coordinateSystem=CDS_COORDINATES, start=4, end=4, deletedSequence="A")
        effect = prepare_observation(raw, reference, claim)["sequenceEffect"]
        # ATG AAC CCG GGT TTT AA -> MNPGF; no complete stop in available CDS.
        self.assertEqual("ATGAACCCGGGTTTTAA", effect["alteredCdsSequence"])
        self.assertEqual("MNPGF", effect["alteredProteinThroughFirstTermination"])
        self.assertEqual("FRAME_SHIFTED", effect["frame"])
        self.assertEqual(2, effect["firstChangedResiduePosition"])
        self.assertIsNone(effect["firstStopCodonStartCds"])

    def test_explicit_whole_exon_removal_includes_utr_but_only_cds_changes_translation(self):
        raw, reference, claim = fixture()
        claim["rnaDeletion"] = dict(coordinateSystem=CDS_COORDINATES, exonOrdinal=4)
        result = prepare_observation(raw, reference, claim)
        self.assertEqual(8, result["replayedDeletion"]["deletedRnaLength"])
        self.assertEqual(6, result["replayedDeletion"]["deletedCdsLength"])
        self.assertEqual("ATGAAACCCGGG", result["sequenceEffect"]["alteredCdsSequence"])
        self.assertFalse(result["sequenceEffect"]["referenceStopCodonRetained"])
        self.assertIn("ALTERED_RNA_THREE_PRIME_END_AND_TERMINATION_SEQUENCE", result["missingFacts"])

    def test_start_loss_keeps_explicit_initiation_requirement(self):
        raw, reference, claim = fixture()
        claim["rnaDeletion"] = dict(coordinateSystem=CDS_COORDINATES, exonOrdinal=2)
        result = prepare_observation(raw, reference, claim)
        self.assertFalse(result["sequenceEffect"]["referenceStartCodonRetained"])
        self.assertIn("TRANSLATION_INITIATION_AFTER_REFERENCE_START_LOSS", result["missingFacts"])
        self.assertFalse(result["runtimeEligible"])

    def test_incomplete_genbank_exon_coverage_remains_a_parser_rejection(self):
        raw, reference, claim = fixture()
        reference = reference.replace(b"exon            15..22", b"exon            15..21")
        claim["refseq"]["sha256"] = sha(reference)
        with self.assertRaisesRegex(ValueError, "REFSEQ_EXONS_INCOMPLETE"):
            prepare_observation(raw, reference, claim)

    def test_digest_quote_locator_and_version_tampering_are_rejected(self):
        for mutation, reason in [("source-bytes", "SOURCE_BYTES_MISMATCH"), ("refseq-bytes", "REFSEQ_BYTES_MISMATCH"),
                                 ("quote", "QUOTE_NOT_FOUND"), ("locator", "QUOTE_LOCATOR_MISMATCH"),
                                 ("duplicate-quote", "QUOTE_NOT_UNIQUE"), ("transcript", "REFSEQ_TRANSCRIPT_VERSION_MISMATCH"),
                                 ("source-version", "SOURCE_TRANSCRIPT_VERSION_MISMATCH_OR_MISSING"),
                                 ("missing-version", "SOURCE_TRANSCRIPT_VERSION_MISMATCH_OR_MISSING"),
                                 ("missing-hgvs", "SOURCE_EXACT_IDENTITY_MENTION_MISSING"),
                                 ("fake-reviewed", "CLAIM_FIELDS_INVALID")]:
            with self.subTest(mutation=mutation):
                raw, reference, claim = fixture()
                if mutation == "source-bytes": raw += b"."
                elif mutation == "refseq-bytes": reference += b"\n"
                elif mutation == "quote": claim["source"]["quote"] = "Fabricated RNA outcome."
                elif mutation == "locator": claim["source"]["quoteStart"] += 1
                elif mutation == "duplicate-quote":
                    raw += (" " + claim["source"]["quote"]).encode("utf-8")
                    claim["source"]["sha256"] = sha(raw)
                elif mutation == "transcript": claim["transcript"] = "NM_000001.2"
                elif mutation in {"source-version", "missing-version", "missing-hgvs"}:
                    old, new = {"source-version": (b"NM_000001.1", b"NM_000001.2"),
                                "missing-version": (b"NM_000001.1", b"NM_000001  "),
                                "missing-hgvs": (b"c.12+1G>A", b"c.12+1G>T")}[mutation]
                    raw = raw.replace(old, new)
                    claim["source"]["sha256"] = sha(raw)
                    text = decode_source(raw, claim["source"])
                    claim["source"]["quoteStart"] = text.index(claim["source"]["quote"])
                    claim["source"]["quoteEnd"] = claim["source"]["quoteStart"] + len(claim["source"]["quote"])
                elif mutation == "fake-reviewed": claim["review"] = {"status": "REVIEWED"}
                with self.assertRaisesRegex(ValueError, reason):
                    prepare_observation(raw, reference, claim)

    def test_invalid_or_inconsistent_deletion_intervals_are_rejected(self):
        cases = [(dict(start=0, end=4), "DELETION_INTERVAL_INVALID"),
                 (dict(start=5, end=4), "DELETION_INTERVAL_INVALID"),
                 (dict(start=4, end=19), "DELETION_INTERVAL_INVALID"),
                 (dict(start=True, end=4), "DELETION_INTERVAL_INVALID"),
                 (dict(start=7, end=11, exonOrdinal=3), "EXON_INTERVAL_MISMATCH"),
                 (dict(exonOrdinal=1), "DELETION_HAS_NO_CDS_OVERLAP"),
                 (dict(exonOrdinal=5), "EXON_ORDINAL_INVALID"),
                 (dict(start=7, end=12, deletedSequence="AAAAAA"), "DELETED_SEQUENCE_MISMATCH"),
                 (dict(start=7, end=12, genomicEdit={}), "DELETION_FIELDS_INVALID")]
        for fields, reason in cases:
            with self.subTest(fields=fields):
                raw, reference, claim = fixture()
                claim["rnaDeletion"] = dict(coordinateSystem=CDS_COORDINATES, **fields)
                with self.assertRaisesRegex(ValueError, reason):
                    prepare_observation(raw, reference, claim)

    def test_cli_writes_reviewable_artifact_and_refuses_overwrite(self):
        raw, reference, claim = fixture()
        with tempfile.TemporaryDirectory(prefix="varvis-splice-observation-") as temporary:
            root = Path(temporary)
            (root / "source.html").write_bytes(raw)
            (root / "refseq.gb").write_bytes(reference)
            (root / "claim.json").write_text(json.dumps(claim), encoding="utf-8")
            output = root / "prepared.json"
            command = [sys.executable, "-B", str(Path(__file__).with_name("prepare_splice_observations.py")),
                       "--source", str(root / "source.html"), "--refseq", str(root / "refseq.gb"),
                       "--claim", str(root / "claim.json"), "--output", str(output)]
            first = subprocess.run(command, check=True, capture_output=True, text=True)
            self.assertFalse(json.loads(first.stdout)["runtimeEligible"])
            self.assertEqual("RNA_OBSERVATION_SOURCE_BOUND", json.loads(output.read_text(encoding="utf-8"))["status"])
            before = output.read_bytes()
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, second.returncode)
            self.assertIn("OUTPUT_EXISTS_REFUSE_OVERWRITE", second.stderr)
            self.assertEqual(before, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
