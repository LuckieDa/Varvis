"""Offline safeguards for the public frameshift HTTP client; no server or resource queries."""
import copy
from pathlib import Path
import unittest

from scripts.pvs1.frameshift_network_acceptance import (
    CONTROLS, CRITERIA, PROFILE, explicit_declaration, require_pvs1_positives,
    validate_public_vcf, verify_instance, verify_reuse,
)


class FrameshiftNetworkAcceptanceTest(unittest.TestCase):
    def options(self):
        rows = []
        for index, (chromosome, position, reference, alternate) in enumerate(CONTROLS):
            annotation = {"annotationId": f"annotation-{index}", "annotationIndex": 0,
                          "versionedTranscriptId": "ENST00000257555.11", "contextSelectable": True,
                          "annotationSource": "VEP", "annotationSourceVersion": "116.0"}
            relation = {"stableRelationshipKey": f"relationship-{index}", "selectable": True,
                        "hgncId": "HGNC:11621", "mondoId": "MONDO:0015967", "moi": "AUTOSOMAL_DOMINANT"}
            rows.append({"candidateId": f"formal-{index}", "geneSymbol": "HNF1A",
                         "allele": {"genomeAssembly": "GRCh38", "chromosome": chromosome, "position": position,
                                    "reference": reference, "alternate": alternate},
                         "candidateIdentity": {"modeOfInheritance": "AD", "sourceOrdinal": index + 1},
                         "transcriptAnnotations": [{"geneSymbol": "HNF1A", "status": "COMPLETED", "source": "VEP", "version": "116.0"}],
                         "transcriptChoices": [{"annotationIndex": 0, "versionedTranscriptId": "ENST00000257555.11"}],
                         "recommendation": {"recommendationId": f"recommendation-{index}", "policyId": "hnf1a",
                                            "policyVersion": "new", "policyHash": "server-hash", "generatedAt": "2026-09-10T00:00:00Z",
                                            "hypothesisId": f"hypothesis-{index}", "relationships": [relation],
                                            "resourceCompatibleTranscript": annotation, "annotations": [annotation]}})
        ar = copy.deepcopy(rows[0]); ar["candidateId"] = "formal-ar"
        ar["candidateIdentity"]["modeOfInheritance"] = "AR"
        ar["recommendation"]["relationships"] = []
        rows.insert(1, ar)
        return {"analysisId": "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa", "enabledCriteria": sorted(CRITERIA), "candidates": rows}

    def saved(self, options):
        candidates = []
        for row in options["candidates"]:
            item = {"candidateId": row["candidateId"], "attempts": []}; candidates.append(item)
            if row["candidateIdentity"]["modeOfInheritance"] == "AR":
                item["attempts"].append({"pvs1": {"evaluationResult": {"status": "NOT_EVALUATED", "reasonCode": "PVS1_VERIFIED_CONTEXT_MISSING"}}})
                continue
            item["attempts"].append({"pvs1": {
                "context": {"moi": "AUTOSOMAL_DOMINANT", "gene": {"hgncId": "HGNC:11621"},
                            "disease": {"mondoId": "MONDO:0015967"}, "variant": {"evidenceQueryAllele": row["allele"]}},
                "evaluation": {"evaluationResult": {"status": "MET"}, "appliedStrength": "VERY_STRONG"}}})
        return {"runId": "same-saved-run", "candidateIds": [row["candidateId"] for row in options["candidates"]], "candidates": candidates}

    def test_explicit_four_ad_controls_preserve_ar_and_formal_order(self):
        options = self.options(); original = copy.deepcopy(options)
        result = explicit_declaration(options)
        self.assertEqual(4, len(result["hypotheses"]))
        self.assertEqual([row["candidateId"] for row in options["candidates"]], [row["candidateId"] for row in result["recommendations"]])
        self.assertIsNone(result["recommendations"][1]["annotationId"])
        self.assertIsNone(result["recommendations"][1]["stableRelationshipKey"])
        self.assertEqual(original, options)

    def test_dropped_or_changed_control_is_not_replaced(self):
        for change in ("dropped", "wrong-allele", "duplicate-ad"):
            with self.subTest(change=change):
                options = self.options()
                if change == "dropped": options["candidates"].pop()
                elif change == "wrong-allele": options["candidates"][-1]["allele"]["alternate"] = "GG"
                else: options["candidates"].append(copy.deepcopy(options["candidates"][-1]))
                with self.assertRaises(ValueError): explicit_declaration(options)

    def test_pm2_only_or_unversioned_transcript_cannot_confirm_pvs1(self):
        for change in ("absent", "unversioned", "wrong-transcript"):
            with self.subTest(change=change):
                options = self.options(); recommendation = options["candidates"][0]["recommendation"]
                if change == "absent": recommendation["resourceCompatibleTranscript"] = None
                else: recommendation["annotations"][0]["versionedTranscriptId"] = "ENST00000257555" if change == "unversioned" else "ENST00000257555.10"
                with self.assertRaises(ValueError): explicit_declaration(options)

    def test_four_positive_envelopes_and_unconfirmed_ar_are_distinct(self):
        options = self.options(); saved = self.saved(options)
        positives = require_pvs1_positives(saved, options)
        self.assertEqual(set(CONTROLS.values()), {row["control"] for row in positives})
        self.assertEqual(4, len(positives))

    def test_persistence_success_does_not_substitute_for_pvs1_science(self):
        options = self.options()
        for field, value in (("status", "NOT_EVALUATED"), ("appliedStrength", "STRONG")):
            saved = self.saved(options)
            evaluation = saved["candidates"][-1]["attempts"][0]["pvs1"]["evaluation"]
            if field == "status": evaluation["evaluationResult"][field] = value
            else: evaluation[field] = value
            with self.assertRaises(AssertionError): require_pvs1_positives(saved, options)

    def test_formal_order_and_original_allele_are_required(self):
        options = self.options(); saved = self.saved(options)
        saved["candidateIds"].reverse()
        with self.assertRaises(AssertionError): require_pvs1_positives(saved, options)
        saved = self.saved(options)
        saved["candidates"][0]["attempts"][0]["pvs1"]["context"]["moi"] = "AUTOSOMAL_RECESSIVE"
        with self.assertRaises(AssertionError): require_pvs1_positives(saved, options)

    def test_reuse_requires_full_bytes_same_run_and_no_new_calls(self):
        raw = b'{"runId":"stable","fraction":1.000000000000001}'
        event = {"runId": "stable", "callIncrement": {"vepPublication": 0, "pvs1Evaluator": 0}}
        verify_reuse(raw, raw, raw, event, event)
        for wrong in (raw + b"\n", b'{"runId":"stable","fraction":1.0}'):
            with self.assertRaises(AssertionError): verify_reuse(raw, wrong, raw, event, event)
        for wrong in ({"runId": "changed", "callIncrement": {"pvs1Evaluator": 0}},
                      {"runId": "stable", "callIncrement": {"pvs1Evaluator": 1}}):
            with self.assertRaises(AssertionError): verify_reuse(raw, raw, raw, wrong, event)

    def test_current_successor_and_private_instance_identity_are_required(self):
        root = Path("private-task").resolve(); instance_id = "instance-aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
        current = {"instanceId": instance_id, "sourceHead": "a" * 40, "branch": "main", "profileId": PROFILE,
                   "port": 18227, "mode": "JOINT", "transport": "REAL_NETWORK_HTTP", "database": "PRIVATE_H2",
                   "instanceRoot": str(root / "network" / instance_id), "executionProfile": {
                       "profileId": PROFILE, "enabledCriteria": sorted(CRITERIA), "automaticTrigger": False, "classificationEnabled": False}}
        verify_instance(current, root, instance_id, "a" * 40)
        for field, value in (("sourceHead", "b" * 40), ("port", 8086), ("profileId", "acmg-explicit-business-joint-v1")):
            changed = copy.deepcopy(current); changed[field] = value
            with self.assertRaises(ValueError): verify_instance(changed, root, instance_id, "a" * 40)
        changed = copy.deepcopy(current); changed["executionProfile"]["classificationEnabled"] = True
        with self.assertRaises(ValueError): verify_instance(changed, root, instance_id, "a" * 40)

    def test_vcf_keeps_fixed_alleles_order_and_explicit_synthetic_sample(self):
        raw = ("##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n" +
               "".join(f"chr{chrom}\t{pos}\t.\t{ref}\t{alt}\t99\tPASS\t.\tGT\t0/1\n" for chrom, pos, ref, alt in CONTROLS)).encode()
        validate_public_vcf(raw)
        for wrong in (raw.replace(b"synthetic-proband", b"real-patient"), raw.replace(b"0/1", b"1/1"), raw.replace(b"120993577", b"120993578")):
            with self.assertRaises(ValueError): validate_public_vcf(wrong)


if __name__ == "__main__":
    unittest.main()
