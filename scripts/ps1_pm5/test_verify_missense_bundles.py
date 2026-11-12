"""Successor registry extension preserves frozen aliases and rejects identity drift."""
import copy
import hashlib
import json
import unittest
from unittest import mock
from . import verify_missense_bundles as verifier

e1 = verifier.e1

class MissenseSuccessorContractTest(unittest.TestCase):
    def test_versioned_audit_rna_and_source_snapshot_hashes_reject_tampering(self):
        original_hash = e1.sha256_file
        for filename, reason in (
                ("clinvar-missense-braf-reference-audit-v2.json", "REFERENCE_AUDIT_FILE_HASH_MISMATCH"),
                ("braf-missense-rna-review-v1.json", "RNA_REVIEW_FILE_HASH_MISMATCH"),
                ("erepo-braf-reference-t241k-v1.json", "REFERENCE_SOURCE_SNAPSHOT_HASH_MISMATCH")):
            with self.subTest(filename=filename), mock.patch.object(e1, "sha256_file", side_effect=lambda p: "0" * 64 if p.name == filename else original_hash(p)):
                with self.assertRaisesRegex(AssertionError, reason):
                    verifier.verify_resources()

    def test_reviewed_scientific_resources_validate(self):
        self.assertEqual("PASS", verifier.verify()["scientificResources"])

    def test_default_validator_does_not_silently_accept_new_alias(self):
        schemas, authority, rules, overlay = verifier.verify_resources()
        with self.assertRaisesRegex(e1.ContractError, "not the observed frozen set"):
            e1.validate_cspec_registry(overlay, authority, rules, schemas,
                                       authority_path=verifier.AUTHORITY, rule_registry_path=verifier.RULES)

    def test_successor_cannot_override_frozen_identity(self):
        schemas = e1.ContractSchemas()
        overlay = e1.load_json(e1.CSPEC_REGISTRY_PATH)
        first = overlay["overlays"][0]
        with self.assertRaisesRegex(e1.ContractError, "cannot override a frozen"):
            e1.validate_cspec_registry(overlay, e1.load_json(e1.AUTHORITY_PATH), e1.load_json(e1.RULE_REGISTRY_PATH), schemas,
                successor_alias_identities={(first["cspecId"], first["version"]): set()})

    def test_successor_alias_must_match_approved_snapshot(self):
        schemas, authority, rules, overlay = verifier.verify_resources()
        identity = ("GN049", "2.3.0")
        with self.assertRaisesRegex(e1.ContractError, "not the observed frozen set"):
            e1.validate_cspec_registry(copy.deepcopy(overlay), authority, rules, schemas,
                authority_path=verifier.AUTHORITY, rule_registry_path=verifier.RULES,
                successor_alias_identities={identity: set()})

class MissenseSavedScienceTest(unittest.TestCase):
    def specimen(self, version="1.0.0", rule="PS1", scv=None):
        policy, audit = verifier.scientific_resources(version)
        normalized = verifier.normalized_audit(audit)
        ref = copy.deepcopy(next((item for item in normalized["references"] if item["scv"] == scv), normalized["references"][0]))
        target = dict(ref["allele"], alternate=next(base for base in "ACGT" if base not in (ref["allele"]["reference"], ref["allele"]["alternate"])))
        raw = {"results": [{"queryId": query, "allele": allele, "status": "HIT", "exactAlleleMatch": True,
                            "annotations": [{"geneSymbol": "BRAF", "deltaScores": {"DS_" + suffix: "0.01" for suffix in ("AG", "AL", "DG", "DL")}}]}
                           for query, allele in [("TARGET", target), (ref["scv"], ref["allele"])]]}
        variants = [{"queryId": row["queryId"], "allele": row["allele"], "geneSymbol": "BRAF", "selectedTranscriptId": "ENST00000646891.2"} for row in raw["results"]]
        raw["requestId"] = "spliceai-batch-sha256-" + hashlib.sha256(json.dumps(variants, separators=(",", ":")).encode()).hexdigest()
        fields = {"clinvar.comparator." + key: str(value) for key, value in ref["allele"].items()}
        fields.update({"clinvar.assertion.scv": ref["scv"], "vep.proteinRelation": "SAME_AMINO_ACID_CHANGE" if rule == "PS1" else "SAME_RESIDUE_DIFFERENT_AMINO_ACID"})
        for role in ("target", "comparator"):
            fields.update({"vep." + role + ".proteinPosition": str(ref["proteinPosition"]),
                           "vep." + role + ".referenceAminoAcid": ref["referenceAminoAcid"],
                           "vep." + role + ".alternateAminoAcid": ref["alternateAminoAcid"],
                           "vep." + role + ".transcriptId": policy["applicability"]["transcript"]})
        if rule == "PM5":
            fields["vep.target.alternateAminoAcid"] = "R"
        if version == "2.0.0" and ref["scv"] == "SCV001424734.1":
            ref["rnaReview"] = copy.deepcopy(policy["rnaEvidenceReview"]["reviews"][0])
        decisions = {rule: {"status": "MET", "evidenceIds": ["synthetic-comparator"]},
                     "rawSpliceBatchSha256": e1.canonical_sha256(raw), "referenceAuditSha256": e1.canonical_sha256(normalized)}
        return {"policy": policy, "references": [ref], "rawSpliceBatch": raw, "scientificDecisions": decisions,
                "context": {"transcript": {"accession": "ENST00000646891", "version": 2}, "variant": {"evidenceQueryAllele": target}, "disease": {"mondoId": "MONDO:0021060"}},
                "spliceEvidence": [{"queryId": row["queryId"], "allele": row["allele"], "status": "AVAILABLE", "maximumScore": 0.01} for row in raw["results"]],
                "atomicEvidence": [{"evidenceId": "synthetic-comparator", "measurements": [{"metricId": key, "rawValue": value} for key, value in fields.items()]}],
                "criterionEvaluations": [{"criterionId": "ACMG_AMP_2015:" + rule, "baseStrength": "STRONG" if rule == "PS1" else "MODERATE", "evaluationResult": {"status": "MET"}, "evidenceIds": ["synthetic-comparator"],
                                           "deterministicTrace": {"parameters": [{"name": "business.profile.sha256", "value": e1.canonical_sha256(policy)},
                                                                                 {"name": "business.science.sha256", "value": e1.canonical_sha256(decisions)}]}}]}

    def test_complete_synthetic_science_projection_is_consistent(self):
        verifier.verify_scientific_slice(self.specimen())

    def test_raw_score_tampering_breaks_scientific_identity(self):
        value = self.specimen()
        value["rawSpliceBatch"]["results"][0]["annotations"][0]["deltaScores"]["DS_AG"] = "0.44"
        with self.assertRaisesRegex(AssertionError, "RAW_SPLICE_BATCH_HASH_MISMATCH"):
            verifier.verify_scientific_slice(value)

    def test_display_score_cannot_conceal_raw_score(self):
        value = self.specimen()
        value["spliceEvidence"][0]["maximumScore"] = 0
        with self.assertRaisesRegex(AssertionError, "SPLICE_SCORE_DISPLAY_MISMATCH"):
            verifier.verify_scientific_slice(value)

    def test_reference_audit_is_not_replaced_by_a_positive_label(self):
        value = self.specimen()
        value["references"][0]["independenceBasis"] = "ClinVar Pathogenic only"
        with self.assertRaisesRegex(AssertionError, "SAVED_REFERENCE_AUDIT_DRIFT"):
            verifier.verify_scientific_slice(value)

    def test_raw_request_identity_binds_the_selected_transcript(self):
        value = self.specimen()
        value["context"]["transcript"]["version"] = 3
        with self.assertRaisesRegex(AssertionError, "RAW_REQUEST_INPUT_IDENTITY_MISMATCH"):
            verifier.verify_scientific_slice(value)

    def test_missing_raw_inputs_cannot_attest_met(self):
        value = self.specimen()
        del value["rawSpliceBatch"]
        with self.assertRaises(AssertionError):
            verifier.verify_scientific_slice(value)

    def test_v2_synthetic_t_r_k_comparison_and_v2_legacy_reference_validate(self):
        verifier.verify_scientific_slice(self.specimen("2.0.0", "PM5", "SCV001424734.1"))
        verifier.verify_scientific_slice(self.specimen("2.0.0", "PS1", "SCV000927045.1"))

    def test_old_saved_policy_does_not_inherit_successor_rna(self):
        old = self.specimen()
        self.assertNotIn("rnaEvidenceReview", old["policy"])
        self.assertEqual("1.0.0", old["policy"]["profileVersion"])
        verifier.verify_scientific_slice(old)

    def test_unregistered_saved_profile_version_cannot_select_arbitrary_resource(self):
        value = self.specimen()
        value["policy"]["profileVersion"] = "99.0.0"
        with self.assertRaisesRegex(AssertionError, "UNREGISTERED_SCIENTIFIC_PROFILE_VERSION"):
            verifier.verify_scientific_slice(value)

    def test_saved_rna_review_and_pm5_relationship_tampering_fail_policy_identity(self):
        for path in ("rna", "relation"):
            value = self.specimen("2.0.0", "PM5", "SCV001424734.1")
            if path == "rna":
                value["policy"]["rnaEvidenceReview"]["reviews"][0]["normalRnaClaimed"] = True
            else:
                value["policy"]["pm5RelationshipPolicy"]["relationships"][-1]["targetAlternate"] = "K"
            with self.assertRaisesRegex(AssertionError, "SAVED_SCIENTIFIC_POLICY_DRIFT"):
                verifier.verify_scientific_slice(value)

    def test_display_rna_review_and_concern_cannot_diverge_from_saved_policy(self):
        value = self.specimen("2.0.0", "PM5", "SCV001424734.1")
        value["references"][0]["rnaReview"]["normalRnaClaimed"] = True
        with self.assertRaisesRegex(AssertionError, "SAVED_RNA_DISPLAY_DRIFT"):
            verifier.verify_scientific_slice(value)
        value = self.specimen("2.0.0", "PM5", "SCV001424734.1")
        value["rnaReviewEvidence"] = [dict(value["policy"]["rnaEvidenceReview"]["knownConcerns"][0], reviewStatus="RESOLVED")]
        with self.assertRaisesRegex(AssertionError, "SAVED_RNA_EVIDENCE_DRIFT"):
            verifier.verify_scientific_slice(value)

    def rna_inputs(self):
        value = self.specimen("2.0.0", "PM5", "SCV001424734.1")
        fields = {item["metricId"]: str(item["rawValue"]) for item in value["atomicEvidence"][0]["measurements"]}
        return value["policy"], value["references"][0], value["context"]["variant"]["evidenceQueryAllele"], fields

    def test_new_reference_cannot_skip_review_by_omitting_required_list(self):
        policy, ref, target, fields = self.rna_inputs()
        policy["rnaEvidenceReview"]["requiredReferenceScvs"] = []
        policy["rnaEvidenceReview"]["reviews"] = []
        with self.assertRaisesRegex(AssertionError, "RNA_REVIEW_COMPARISON_MISSING_OR_AMBIGUOUS"):
            verifier.verify_rna_comparison(policy, ref, target, fields)

    def test_low_prediction_cannot_override_target_or_reference_unresolved_rna_concern(self):
        for role in ("target", "reference"):
            policy, ref, target, fields = self.rna_inputs()
            concern = policy["rnaEvidenceReview"]["knownConcerns"][0]["allele"]
            if role == "target":
                target = concern
            else:
                ref["allele"] = concern
            with self.assertRaisesRegex(AssertionError, "MET_WITH_UNRESOLVED_RNA_CONCERN"):
                verifier.verify_rna_comparison(policy, ref, target, fields)

    def test_rna_review_scope_status_unknown_effect_and_no_normal_claim_required(self):
        cases = [("scope", "RNA_REVIEW_SCOPE_MISMATCH"),
                 ("status", "RNA_REVIEW_NOT_REVIEWED"),
                 ("rnaEffect", "RNA_REVIEW_CANNOT_CLAIM_NORMAL_RNA"),
                 ("normalRnaClaimed", "RNA_REVIEW_CANNOT_CLAIM_NORMAL_RNA"),
                 ("sources", "RNA_REVIEW_SOURCES_MISSING")]
        for field, error in cases:
            policy, ref, target, fields = self.rna_inputs()
            review = policy["rnaEvidenceReview"]["reviews"][0]
            if field == "scope": policy["rnaEvidenceReview"]["scope"]["mondoId"] = "MONDO:other"
            elif field == "sources": review["sourceArtifacts"] = []
            elif field == "normalRnaClaimed": review[field] = True
            else: review[field] = "NORMAL"
            with self.assertRaisesRegex(AssertionError, error):
                verifier.verify_rna_comparison(policy, ref, target, fields)

    def test_new_amino_acid_normalization_preserves_q_t_k_r(self):
        _, audit = verifier.scientific_resources("2.0.0")
        probe = copy.deepcopy(audit["references"][0])
        for original, expected in (("Gln", "Q"), ("Thr", "T"), ("Lys", "K"), ("Arg", "R")):
            probe["alternateAminoAcid"] = original
            self.assertEqual(expected, verifier.normalized_audit({"references": [probe]})["references"][0]["alternateAminoAcid"])


if __name__ == "__main__":
    unittest.main()
