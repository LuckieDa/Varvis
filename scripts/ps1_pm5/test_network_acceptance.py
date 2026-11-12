import copy
import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path

from scripts.ps1_pm5.network_acceptance import Client, declaration, multipart, require_pm5_met, patient_context_id, resume_analysis, joint_declaration, parse_confirmed_contexts, verify_confirmed_context_identity


class NetworkAcceptanceTest(unittest.TestCase):
    def options(self):
        return {"candidates": [{"candidateId": "formal-1", "recommendation": {
            "recommendationId": "server-issued", "policyVersion": "1", "policyHash": "server-hash", "generatedAt": "time",
            "hypothesisId": "formal-hypothesis", "policyId": "policy", "relationships": [{"selectable": True,
                "hgncId": "HGNC:1097", "mondoId": "MONDO:0021060", "moi": "AUTOSOMAL_DOMINANT",
                "relationshipType": "VCEP_SPECIFICATION_SCOPE", "stableRelationshipKey": "server-relationship"}],
            "ps1Pm5CompatibleTranscript": {"ps1Pm5Compatible": True, "identityCompatible": False,
                "versionedTranscriptId": "ENST00000646891.2", "annotationId": "server-annotation",
                "annotationSource": "VEP", "annotationSourceVersion": "115"}}}]}

    def declare(self, options):
        return declaration(options, "synthetic-proband", "HGNC:1097", "MONDO:0021060", "AUTOSOMAL_DOMINANT", 3, "patient-context-public-case")

    def test_formal_ids_and_source_backed_transcript_survive_am_incompatibility(self):
        result = self.declare(self.options())
        self.assertEqual("formal-1", result["hypotheses"][0]["hypothesis"]["candidateId"])
        self.assertEqual("server-annotation", result["recommendations"][0]["annotationId"])
        self.assertEqual(2, result["hypotheses"][0]["transcript"]["version"])

    def test_ambiguous_relationship_is_not_silently_selected(self):
        options = self.options()
        rows = options["candidates"][0]["recommendation"]["relationships"]
        rows.append(copy.deepcopy(rows[0]))
        with self.assertRaises(ValueError): self.declare(options)

    def test_unselectable_or_different_disease_does_not_create_hypothesis(self):
        for mutation in ({"selectable": False}, {"mondoId": "MONDO:OTHER"}, {"moi": "AUTOSOMAL_RECESSIVE"}):
            options = self.options()
            options["candidates"][0]["recommendation"]["relationships"][0].update(mutation)
            with self.assertRaises(ValueError): self.declare(options)

    def test_missing_transcript_or_candidates_remains_blocked(self):
        options = self.options()
        options["candidates"][0]["recommendation"]["ps1Pm5CompatibleTranscript"] = None
        with self.assertRaises(ValueError): self.declare(options)
        with self.assertRaises(ValueError): self.declare({"candidates": []})

    def test_other_server_or_user_service_cannot_be_targeted(self):
        for url in ("http://127.0.0.1:8086", "http://example.com:18217", "https://127.0.0.1:18217", "http://127.0.0.1:18217/path"):
            with self.assertRaises(ValueError): Client(url, Path("unused"), 1)

    def test_joint_port_requires_explicit_mode_and_stays_loopback(self):
        with self.assertRaises(ValueError): Client("http://127.0.0.1:18227", Path("unused"), 1)
        Client("http://127.0.0.1:18227", Path("unused"), 1, joint=True)
        for url in ("http://127.0.0.1:18217", "http://localhost:18227", "http://example.com:18227"):
            with self.assertRaises(ValueError): Client(url, Path("unused"), 1, joint=True)

    def joint_options(self):
        options = self.options()
        recommendation = options["candidates"][0]["recommendation"]
        transcript = recommendation["ps1Pm5CompatibleTranscript"]
        transcript["contextSelectable"] = True
        recommendation.update(stableRelationshipKey="server-relationship", recommendedAnnotationId="server-annotation",
                              annotations=[transcript])
        return options

    def test_joint_uses_server_selected_transcript_and_keeps_unresolved_formal_rows(self):
        options = self.joint_options()
        unresolved = copy.deepcopy(options["candidates"][0])
        unresolved["candidateId"] = "formal-unresolved"
        unresolved["recommendation"]["stableRelationshipKey"] = None
        options["candidates"].append(unresolved)
        result = joint_declaration(options, "synthetic-proband", 2, "patient-context-public-joint")
        self.assertEqual(2, len(result["recommendations"]))
        self.assertEqual(1, len(result["hypotheses"]))
        self.assertIsNone(result["recommendations"][1]["stableRelationshipKey"])
        self.assertIsNone(result["recommendations"][1]["annotationId"])
        self.assertEqual("ENST00000646891", result["hypotheses"][0]["transcript"]["accession"])

    def test_joint_population_only_does_not_confirm_prediction_or_missense_transcript(self):
        result = joint_declaration(self.joint_options(), "synthetic-proband", 1, "patient-context-public-joint", True)
        self.assertEqual([], result["hypotheses"])
        self.assertIsNone(result["recommendations"][0]["annotationId"])
        self.assertEqual("server-relationship", result["recommendations"][0]["stableRelationshipKey"])

    def test_joint_does_not_invent_choice_when_source_is_ambiguous_or_unselectable(self):
        for field in ("relationships", "annotations"):
            options = self.joint_options()
            values = options["candidates"][0]["recommendation"][field]
            values.append(copy.deepcopy(values[0]))
            result = joint_declaration(options, "synthetic-proband", 1, "patient-context-public-joint")
            self.assertEqual([], result["hypotheses"])
        with self.assertRaises(ValueError):
            joint_declaration({"candidates": []}, "synthetic-proband", 1, "patient-context-public-joint")

    def confirmed_options(self):
        options = self.joint_options()
        row = options["candidates"][0]
        row["geneSymbol"] = "BRAF"
        recommendation = row["recommendation"]
        recommendation["stableRelationshipKey"] = None
        original = recommendation["annotations"][0]
        original["annotationIndex"] = 0
        # Original DTO omits derived getters; the server publishes version identity in transcriptChoices.
        row["transcriptAnnotations"] = [{"transcriptAccession": "ENST00000646891", "geneSymbol": "BRAF", "status": "COMPLETED",
                                          "source": original["annotationSource"], "version": original["annotationSourceVersion"]}]
        row["transcriptChoices"] = [{"annotationIndex": 0, "versionedTranscriptId": original["versionedTranscriptId"]}]
        other = copy.deepcopy(recommendation["relationships"][0])
        other.update(mondoId="MONDO:0015280", stableRelationshipKey="other-disease", relationshipType="GENE_VALIDITY")
        recommendation["relationships"].append(other)
        return options

    def contexts(self):
        return [{"geneSymbol": "BRAF", "hgncId": "HGNC:1097", "mondoId": "MONDO:0021060", "moi": "AD"}]

    def test_explicit_domain_context_resolves_braf_without_collapsing_other_diseases(self):
        options = self.confirmed_options()
        before = copy.deepcopy(options)
        result = joint_declaration(options, "synthetic-proband", 1, "patient-context-public-joint", confirmed_contexts=self.contexts())
        self.assertEqual("server-relationship", result["recommendations"][0]["stableRelationshipKey"])
        self.assertEqual(1, len(result["hypotheses"]))
        self.assertEqual("MONDO:0021060", result["hypotheses"][0]["hypothesis"]["mondoId"])
        self.assertEqual(before, options, "Server options and other disease relationships remain unchanged")

    def test_explicit_domain_context_does_not_authorize_ar_or_unrelated_candidates(self):
        options = self.confirmed_options()
        ar = copy.deepcopy(options["candidates"][0]); ar["candidateId"] = "formal-ar"
        for relation in ar["recommendation"]["relationships"]: relation["selectable"] = False
        options["candidates"].append(ar)
        result = joint_declaration(options, "synthetic-proband", 2, "patient-context-public-joint", confirmed_contexts=self.contexts())
        self.assertEqual(1, len(result["hypotheses"]))
        self.assertIsNone(result["recommendations"][1]["stableRelationshipKey"])
        self.assertIsNone(result["recommendations"][1]["annotationId"])

    def test_explicit_domain_context_requires_unique_same_indexed_transcript(self):
        for mutation in ("duplicate", "wrong-index", "wrong-version"):
            options = self.confirmed_options(); row = options["candidates"][0]
            if mutation == "duplicate": row["recommendation"]["annotations"].append(copy.deepcopy(row["recommendation"]["annotations"][0]))
            elif mutation == "wrong-index": row["recommendation"]["annotations"][0]["annotationIndex"] = 5
            else: row["transcriptAnnotations"][0]["version"] = "wrong"
            result = joint_declaration(options, "synthetic-proband", 1, "patient-context-public-joint", confirmed_contexts=self.contexts())
            self.assertEqual([], result["hypotheses"])
            self.assertIsNone(result["recommendations"][0]["annotationId"])

    def test_explicit_context_file_rejects_internal_keys_duplicates_and_post_prepare_change(self):
        raw = json.dumps(self.contexts()).encode()
        self.assertEqual(self.contexts(), parse_confirmed_contexts(raw))
        bad = self.contexts(); bad[0]["stableRelationshipKey"] = "internal"
        for value in (bad, self.contexts() * 2, []):
            with self.assertRaises(ValueError): parse_confirmed_contexts(json.dumps(value).encode())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); file = root / "confirmed-contexts.json"; file.write_bytes(raw)
            digest = hashlib.sha256(raw).hexdigest(); state = {"confirmedContextsSha256": digest}
            self.assertEqual(digest, verify_confirmed_context_identity(root, state))
            with self.assertRaises(ValueError): verify_confirmed_context_identity(root, state, "changed")
            with self.assertRaises(ValueError): verify_confirmed_context_identity(root, {}, digest)
            file.write_bytes(raw + b" ")
            with self.assertRaises(ValueError): verify_confirmed_context_identity(root, state)

    def test_multipart_retains_original_vcf_bytes_and_explicit_scope(self):
        vcf = b"##fileformat=VCFv4.2\n#CHROM\tPOS\n"
        body, content_type = multipart(vcf, "synthetic-proband", "INCLUDE_INTRONS")
        self.assertIn(vcf, body)
        self.assertIn(b"INCLUDE_INTRONS", body)
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))

    def saved(self, pm5_status="MET", strength="MODERATE", ps1_status="NOT_MET"):
        return {"candidateIds": ["formal-1"], "candidates": [{"candidateId": "formal-1", "attempts": [{
            "missenseComparison": {"criterionEvaluations": [
                {"criterionId": "ACMG_AMP_2015:PM5", "evaluationResult": {"status": pm5_status},
                 "appliedStrength": strength, "criterionApplicability": {"status": "APPLICABLE"}, "contextId": "context"},
                {"criterionId": "ACMG_AMP_2015:PS1", "evaluationResult": {"status": ps1_status}}
            ]}}]}]}

    def test_formal_pm5_met_moderate_satisfies_independent_positive_requirement(self):
        positives = require_pm5_met(self.saved(), ["formal-1"])
        self.assertEqual("formal-1", positives[0]["candidateId"])
        self.assertEqual("MODERATE", positives[0]["appliedStrength"])

    def test_not_met_or_not_evaluated_is_not_a_pm5_positive(self):
        for status in ("NOT_MET", "NOT_EVALUATED"):
            with self.assertRaises(AssertionError): require_pm5_met(self.saved(status), ["formal-1"])

    def test_wrong_strength_or_concurrent_ps1_met_is_not_the_required_positive(self):
        for saved in (self.saved(strength="STRONG"), self.saved(strength="NONE"), self.saved(ps1_status="MET")):
            with self.assertRaises(AssertionError): require_pm5_met(saved, ["formal-1"])

    def test_nonformal_or_missing_rule_cannot_supply_pm5_acceptance(self):
        with self.assertRaises(AssertionError): require_pm5_met(self.saved(), ["different-formal-candidate"])
        saved = self.saved()
        saved["candidates"][0]["attempts"][0]["missenseComparison"]["criterionEvaluations"].pop()
        with self.assertRaises(AssertionError): require_pm5_met(saved, ["formal-1"])

    def test_generated_patient_id_matches_the_actual_frozen_schema(self):
        schema = json.loads(Path("docs/contracts/acmg-evidence-v2/schemas/evidence-context-v2.schema.json").read_bytes())
        pattern = schema["$defs"]["patientContext"]["properties"]["patientContextId"]["pattern"]
        analysis = "11111111-1111-4111-8111-111111111111"
        self.assertIsNotNone(re.fullmatch(pattern, patient_context_id(analysis)))
        self.assertIsNone(re.fullmatch(pattern, "public-" + analysis))
        with self.assertRaises(ValueError):
            declaration(self.options(), "synthetic-proband", "HGNC:1097", "MONDO:0021060", "AUTOSOMAL_DOMINANT", 3, "public-" + analysis)

    def resume_fixture(self, actual_changes=None, identity_changes=None):
        analysis = "11111111-1111-4111-8111-111111111111"
        old = {"analysisId": analysis, "instanceId": "instance", "sourceHead": "head", "vcfSha256": "vcf-sha", "requirePm5Met": True}
        current = {"instanceId": "instance", "sourceHead": "head", "processId": 2}
        current.update(identity_changes or {})
        actual = {"id": analysis, "status": "READY", "fileSha256": "vcf-sha"}
        actual.update(actual_changes or {})
        calls = []
        class ReadOnlyClient:
            def call(self, name, method, path):
                calls.append((method, path))
                return actual
        root = Path("varvis-server/target/ps1-pm5-private")
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as temporary:
            path = Path(temporary) / "network-state.json"
            raw = json.dumps(old).encode()
            path.write_bytes(raw)
            try:
                result = resume_analysis(ReadOnlyClient(), path, current, "client-sha", False)
            finally:
                self.assertEqual(raw, path.read_bytes(), "The previous acceptance state must remain byte-identical")
        return result, calls, hashlib.sha256(raw).hexdigest()

    def test_resume_uses_real_get_and_preserves_old_state_and_positive_requirement(self):
        (actual, state), calls, source_sha = self.resume_fixture()
        self.assertEqual("READY", actual["status"])
        self.assertEqual([("GET", "/variant-analyses/11111111-1111-4111-8111-111111111111")], calls)
        self.assertEqual(source_sha, state["resumedFromStateSha256"])
        self.assertTrue(state["requirePm5Met"])
        self.assertEqual("client-sha", state["clientScriptSha256"])
        self.assertNotIn("formalCandidateIds", state, "Fresh input-options must supply all formal candidates")

    def test_resume_rejects_different_instance_source_or_nonready_analysis(self):
        for identity in ({"instanceId": "other"}, {"sourceHead": "other"}):
            with self.assertRaises(ValueError): self.resume_fixture(identity_changes=identity)
        for actual in ({"status": "RUNNING"}, {"id": "other"}, {"fileSha256": "other"}):
            with self.assertRaises(ValueError): self.resume_fixture(actual_changes=actual)


if __name__ == "__main__":
    unittest.main()
