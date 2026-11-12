"""Small offline regressions; imported public smoke is a fixture, not a new resource run."""

import copy
import json
from pathlib import Path
import tempfile
import unittest

from scripts.e4_a2b import verify_alphamissense_dispatch as verifier
from scripts.evidence import acmg_evidence_v2_contract as e1


class DispatchVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.smoke = e1.load_json(verifier.c1.SMOKE_PATH)

    def batch(self, hgnc_id=None, symbol=None, strict_fixture=False):
        smoke = copy.deepcopy(self.smoke)
        context = smoke["context"]
        old_context, old_evidence = context["contextId"], smoke["atomicEvidence"]["evidenceId"]
        if hgnc_id is not None:
            context["gene"].update(hgncId=hgnc_id, symbol=symbol)
        if strict_fixture:
            context["transcript"]["selectionPolicy"] = verifier.REAL_EXACT_SELECTION_POLICY
        context["contextId"] = e1.content_id("context-sha256-", e1.context_identity_projection(context))
        atomic = smoke["atomicEvidence"]
        atomic["contextId"] = context["contextId"]
        digest = e1.canonical_sha256(e1.evidence_identity_projection(atomic))
        atomic.update(evidenceId="evidence-sha256-" + digest,
                      deduplicationKey=e1.content_id("dedup-sha256-", e1.evidence_deduplication_projection(atomic)))
        atomic["provenance"]["identityProjectionSha256"] = digest
        smoke["criterionEvaluations"] = e1._replace_ids(smoke["criterionEvaluations"],
            {old_context: context["contextId"], old_evidence: atomic["evidenceId"]})
        selection = {
            "schemaVersion": "cspec-selection-v1", "canonicalizationProfile": "varvis-json-c14n-v1",
            "contextId": context["contextId"], "overlayRegistryId": verifier.a2a.OVERLAY_ID,
            "overlayRegistrySha256": e1.sha256_file(verifier.a2a.OVERLAY_PATH),
            "policyId": "CSPEC_EXACT_CONTEXT_SELECTION_V1", "outcome": "ZERO_MATCH",
            "candidateOverlayIds": [], "conflict": {"status": "NONE", "conflictIds": []},
            "review": {"status": "NOT_REQUIRED", "decision": "NONE", "reviewRecordIds": []},
        }
        selection["selectionId"] = e1.content_id(
            "cspec-selection-sha256-", e1.cspec_selection_identity_projection(selection))
        # The old standalone E3 fixture had a placeholder selection. Bind this test's explicit
        # selection and full registry authorities before passing it to the verifier. This is
        # fixture construction, not an assertion that the historical Java output was bundle-valid.
        criteria = {item["criterionId"]: item for item in e1.load_json(verifier.a2a.RULE_PATH)["criteria"]}
        for evaluation in smoke["criterionEvaluations"]:
            evaluation["ruleBinding"]["cspecSelectionId"] = selection["selectionId"]
            evaluation["ruleBinding"]["authoritySourceIds"] = copy.deepcopy(
                criteria[evaluation["criterionId"]]["currentGeneral"]["authoritySourceIds"])
            trace = evaluation["deterministicTrace"]
            if trace["executionStatus"] == "COMPLETED":
                trace["traceSha256"] = e1.canonical_sha256(e1.deterministic_trace_projection(trace, include_hash=False))
            evaluation["evaluationId"] = e1.content_id(
                "evaluation-sha256-", e1.evaluation_identity_projection(evaluation))
        original = {
            "candidateId": "synthetic-candidate-e4-a2b", "hypothesisId": "synthetic-hypothesis",
            "context": context, "binding": {"source": "SYNTHETIC_BINDING_FIXTURE"},
            "provenance": {"source": "SYNTHETIC_PROVENANCE_FIXTURE"}, "blockers": [],
        }
        preflight = {
            "original": original, "selection": selection, "selectionId": selection["selectionId"],
            "outcome": "ZERO_MATCH", "candidateOverlayIds": [], "reviewRecords": [], "conflicts": [],
            "constraints": {"criterionEvaluationConstraint": "EVALUATE_WITH_GENERAL_RULES",
                            "combinationDisposition": "CONTINUE", "reviewConstraint": "NOT_REQUIRED",
                            "overlaySelectionStatus": "NO_MATCH"}, "blockers": [],
        }
        attempt = {
            "preflight": copy.deepcopy(preflight), "context": context, "selection": selection,
            "atomicEvidence": [smoke["atomicEvidence"]], "criterionEvaluations": smoke["criterionEvaluations"],
            "transcriptInput": {"versionedTranscriptId": "{accession}.{version}".format(**context["transcript"]),
                                "source": "VEP" if strict_fixture else "VEP_SYNTHETIC", "geneSymbol": context["gene"]["symbol"],
                                "annotationVersion": context["transcript"]["annotationSourceVersion"],
                                "aminoAcidChange": next(metric["rawValue"] for metric in atomic["measurements"]
                                                         if metric["metricId"] == "alphamissense.proteinVariant"),
                                "consequence": "missense_variant",
                                "proteinIdentityMappingStatus": "NOT_ESTABLISHED"}, "blockers": [],
            "providerCalls": 1, "evaluatorCalls": 1,
        }
        candidate_id = original["candidateId"]
        return {
            "preflight": {"analysisId": "synthetic-analysis-e4-a2b", "requestedN": 1, "selectedCount": 1,
                          "candidateIds": [candidate_id], "registryPairing": verifier.expected_pairing(),
                          "candidates": [{"candidateId": candidate_id, "attempts": [preflight]}],
                          "unmatchedInputs": [], "candidateOrderingChanged": False},
            "candidates": [{"candidateId": candidate_id, "attempts": [attempt]}],
            "providerCalls": 1, "evaluatorCalls": 1,
        }

    @staticmethod
    def attempt(batch):
        return batch["candidates"][0]["attempts"][0]

    def block(self, batch, provider_calls=0, remove_selection=False):
        attempt = self.attempt(batch)
        blocker = {"status": "DISPATCH_BLOCKED", "reasonCode": "SYNTHETIC_BLOCKER", "field": "source"}
        attempt.update(atomicEvidence=[], criterionEvaluations=[], blockers=[blocker],
                       providerCalls=provider_calls, evaluatorCalls=0)
        batch.update(providerCalls=provider_calls, evaluatorCalls=0)
        if remove_selection:
            source = batch["preflight"]["candidates"][0]["attempts"][0]
            source.update(selection=None, outcome=None, selectionId=None, blockers=[blocker])
            attempt.update(preflight=copy.deepcopy(source), selection=None)
        return batch

    def test_builds_existing_bundle_without_repairing_or_mutating_java_records(self):
        batch = self.batch()
        before = copy.deepcopy(batch)
        summary = verifier.verify_dispatch(batch)
        self.assertEqual(1, summary["counts"]["bundles"])
        self.assertEqual(1, summary["counts"]["providerCalls"])
        self.assertEqual("NOT_RUN", summary["combinationExecutionStatus"])
        self.assertFalse(summary["clinicalValidation"])
        self.assertEqual(batch, before)
        bundle = verifier.construct_bundle(self.attempt(batch), batch["preflight"]["registryPairing"])
        for output, source in (("context", "context"), ("cspecSelection", "selection"),
                               ("atomicEvidence", "atomicEvidence"), ("criterionEvaluations", "criterionEvaluations")):
            self.assertEqual(self.attempt(batch)[source], bundle[output])

    def test_identity_and_trace_tampering_fails_before_rehash_can_mask_it(self):
        cases = (
            (lambda a: a["atomicEvidence"][0].update(evidenceId="evidence-sha256-" + "0" * 64), "ATOMIC_ID"),
            (lambda a: a["atomicEvidence"][0].update(deduplicationKey="dedup-sha256-" + "0" * 64), "ATOMIC_DEDUP"),
            (lambda a: a["criterionEvaluations"][0].update(evaluationId="evaluation-sha256-" + "0" * 64), "EVALUATION_ID"),
            (lambda a: a["criterionEvaluations"][0]["deterministicTrace"].update(traceSha256="0" * 64), "EVALUATION_ID|TRACE_ID"),
            (lambda a: a["selection"].update(selectionId="cspec-selection-sha256-" + "0" * 64), "PROVENANCE_CHANGED|CONTEXT_SELECTION|SELECTION_ID"),
        )
        for mutate, reason in cases:
            with self.subTest(reason=reason):
                batch = self.batch()
                mutate(self.attempt(batch))
                with self.assertRaisesRegex(e1.ContractError, reason):
                    verifier.verify_dispatch(batch)

    def test_no_formal_selection_preserves_blocker_and_produces_no_fake_bundle(self):
        batch = self.block(self.batch(), remove_selection=True)
        summary = verifier.verify_dispatch(batch)
        self.assertEqual(1, summary["counts"]["blockedBeforeDispatch"])
        self.assertEqual([], summary["identities"])
        self.attempt(batch)["providerCalls"] = 1
        with self.assertRaisesRegex(e1.ContractError, "NO_SELECTION_MUST_NOT_DISPATCH"):
            verifier.verify_dispatch(batch)

    def test_incomplete_provider_failure_is_retained_without_fake_evaluation(self):
        batch = self.block(self.batch(), provider_calls=1)
        summary = verifier.verify_dispatch(batch)
        self.assertEqual(1, summary["counts"]["incompleteProviderFailures"])
        self.assertEqual([], summary["identities"])
        self.attempt(batch)["criterionEvaluations"] = [copy.deepcopy(self.smoke["criterionEvaluations"][0])]
        with self.assertRaisesRegex(e1.ContractError, "BLOCKED_ATTEMPT_MUST_NOT_FAKE_EVIDENCE"):
            verifier.verify_dispatch(batch)

    def test_evaluator_failure_keeps_atomic_identity_without_fabricating_bundle(self):
        batch = self.batch()
        self.attempt(batch).update(criterionEvaluations=[], blockers=[{
            "status": "ALPHAMISSENSE_DISPATCH_BLOCKED", "reasonCode": "EVALUATOR_INSTANCE_FAILED",
            "field": "criterionEvaluations"}])
        summary = verifier.verify_dispatch(batch)
        self.assertEqual(1, summary["counts"]["incompleteEvaluatorFailures"])
        self.assertEqual([], summary["identities"])
        self.attempt(batch)["atomicEvidence"][0]["evidenceId"] = "evidence-sha256-" + "0" * 64
        with self.assertRaisesRegex(e1.ContractError, "ATOMIC_ID_MISMATCH"):
            verifier.verify_dispatch(batch)

    def test_preserves_batch_row_order_original_provenance_and_aggregate_calls(self):
        mutations = (
            (lambda b: b["candidates"][0].update(candidateId="other"), "CANDIDATE_ORDER"),
            (lambda b: self.attempt(b)["preflight"]["original"]["provenance"].update(source="changed"), "PROVENANCE_CHANGED"),
            (lambda b: b.update(providerCalls=2), "BATCH_CALL_COUNTS"),
            (lambda b: b["preflight"]["registryPairing"].update(ruleRegistrySha256="0" * 64), "REGISTRY_PAIRING"),
        )
        for mutate, reason in mutations:
            with self.subTest(reason=reason):
                batch = self.batch()
                mutate(batch)
                with self.assertRaisesRegex(e1.ContractError, reason):
                    verifier.verify_dispatch(batch)

    def test_missing_and_foreign_evidence_references_fail(self):
        batch = self.batch()
        item = self.attempt(batch)["criterionEvaluations"][0]
        item["evidenceIds"] = ["evidence-sha256-" + "0" * 64]
        item["evaluationId"] = e1.content_id("evaluation-sha256-", e1.evaluation_identity_projection(item))
        with self.assertRaisesRegex(e1.ContractError, "evidence reference missing"):
            verifier.verify_dispatch(batch)

    def test_subset_of_pinned_rule_authorities_cannot_be_repaired_by_bundle_construction(self):
        batch = self.batch()
        item = self.attempt(batch)["criterionEvaluations"][0]
        item["ruleBinding"]["authoritySourceIds"] = ["authority-clingen-svi-pp3-bp4-extension-2025"]
        item["evaluationId"] = e1.content_id("evaluation-sha256-", e1.evaluation_identity_projection(item))
        with self.assertRaisesRegex(e1.ContractError, "authority source binding mismatch"):
            verifier.verify_dispatch(batch)

    def test_complete_cardinality_and_multiple_batch_accounting(self):
        summary = verifier.verify_dispatch([self.batch(), self.block(self.batch(), remove_selection=True)])
        self.assertEqual(2, summary["batchCount"])
        self.assertEqual(2, summary["counts"]["attempts"])
        self.assertEqual(1, summary["counts"]["bundles"])
        batch = self.batch()
        self.attempt(batch)["criterionEvaluations"].pop()
        with self.assertRaisesRegex(e1.ContractError, "EVIDENCE_CARDINALITY"):
            verifier.verify_dispatch(batch)

    def test_consistently_forged_wrapper_ids_and_policy_do_not_bypass_formal_selection(self):
        for field, value in (("selectionId", "cspec-selection-sha256-" + "0" * 64),
                             ("outcome", "SINGLE_SELECTABLE_MATCH"), ("candidateOverlayIds", ["cspec-overlay-other"]),
                             ("constraints", {"criterionEvaluationConstraint": "FORCE_NOT_EVALUATED"})):
            with self.subTest(field=field):
                batch = self.batch()
                source = batch["preflight"]["candidates"][0]["attempts"][0]
                source[field] = value
                self.attempt(batch)["preflight"] = copy.deepcopy(source)
                with self.assertRaisesRegex(e1.ContractError, "PREFLIGHT_(SELECTION|POLICY)_WRAPPER_MISMATCH"):
                    verifier.verify_dispatch(batch)

    def test_transcript_source_gene_and_annotation_version_are_bound_to_context(self):
        for field in ("versionedTranscriptId", "geneSymbol", "annotationVersion", "source"):
            with self.subTest(field=field):
                batch = self.batch()
                self.attempt(batch)["transcriptInput"][field] = "WRONG"
                with self.assertRaisesRegex(e1.ContractError, "TRANSCRIPT_INPUT_IDENTITY_MISMATCH"):
                    verifier.verify_dispatch(batch)

    def test_validly_rehashed_foreign_resource_and_calibration_profile_are_rejected(self):
        mutations = (
            (lambda b: b["atomicEvidence"][0]["source"].update(sourceId="OTHER_PREDICTOR"), "RESOURCE_IDENTITY"),
            (lambda b: b["atomicEvidence"][0]["source"].update(resourceSha256="0" * 64), "RESOURCE_IDENTITY"),
            (lambda b: b["atomicEvidence"][0]["measurements"][0].update(calibrationProfileId="OTHER_PROFILE"), "CALIBRATION"),
            (lambda b: next(x for x in b["criterionEvaluations"][0]["deterministicTrace"]["parameters"]
                            if x["name"] == "profile.sha256").update(value="0" * 64), "PROFILE_PARAMETER"),
            (lambda b: b["criterionEvaluations"][0]["ruleBinding"].update(ruleRegistrySha256="0" * 64), "registry fingerprint"),
        )
        for mutate, reason in mutations:
            with self.subTest(reason=reason):
                batch = self.batch()
                attempt = self.attempt(batch)
                bundle = verifier.construct_bundle(attempt, batch["preflight"]["registryPairing"])
                mutate(bundle)
                # Mutate and re-ID only this negative fixture, proving semantic misuse is rejected
                # even when every hash/reference is internally valid. Never repair verifier inputs.
                changed = e1.rehash_bundle(bundle)
                attempt.update(atomicEvidence=changed["atomicEvidence"], criterionEvaluations=changed["criterionEvaluations"])
                with self.assertRaisesRegex(e1.ContractError, reason):
                    verifier.verify_dispatch(batch)

    def test_complete_bundle_rejects_outer_blockers_but_missing_selection_can_preserve_invalid_context(self):
        batch = self.batch()
        self.attempt(batch)["blockers"] = [{"status": "DISPATCH_BLOCKED", "reasonCode": "SYNTHETIC_BLOCKER", "field": "source"}]
        with self.assertRaisesRegex(e1.ContractError, "COMPLETE_BUNDLE_HAS_OUTER_BLOCKERS"):
            verifier.verify_dispatch(batch)
        batch = self.block(self.batch(), remove_selection=True)
        source = batch["preflight"]["candidates"][0]["attempts"][0]
        source["original"]["context"] = {"contextId": "malformed-input-retained"}
        self.attempt(batch).update(preflight=copy.deepcopy(source), context=copy.deepcopy(source["original"]["context"]))
        self.assertEqual(1, verifier.verify_dispatch(batch)["counts"]["blockedBeforeDispatch"])

    def projected_metadata_batch(self):
        batch = self.block(self.batch(), remove_selection=True)
        attempt = self.attempt(batch)
        registry = e1.load_json(verifier.a2a.OVERLAY_PATH)
        overlay = registry["overlays"][0]
        context = attempt["context"]
        context["gene"].update(hgncId=overlay["selector"]["hgncId"], symbol="CDH1")
        context["disease"].update(mondoId=overlay["selector"]["mondoId"])
        context["transcript"].update(overlay["selector"]["transcript"])
        context["contextId"] = e1.content_id("context-sha256-", e1.context_identity_projection(context))
        projection = {"schemaVersion": "cspec-selection-v1", "canonicalizationProfile": "varvis-json-c14n-v1",
                      "contextId": context["contextId"], "overlayRegistryId": registry["registryId"],
                      "overlayRegistrySha256": e1.sha256_file(verifier.a2a.OVERLAY_PATH),
                      "policyId": registry["selectionPolicy"]["policyId"], "outcome": "SINGLE_NON_SELECTABLE_MATCH",
                      "candidateOverlayIds": [overlay["overlayId"]], "reasonCode": "CSPEC_MATCH_NOT_EXECUTABLE"}
        source = batch["preflight"]["candidates"][0]["attempts"][0]
        source.update(selectionId=e1.content_id("cspec-selection-sha256-", e1.cspec_selection_identity_projection(projection)),
                      outcome=projection["outcome"], candidateOverlayIds=projection["candidateOverlayIds"],
                      constraints=copy.deepcopy(registry["selectionPolicy"]["outcomes"]["singleNonSelectableMatch"]))
        source["original"]["context"] = copy.deepcopy(context)
        source["blockers"] = [{"status": "CSPEC_PREFLIGHT_BLOCKED", "reasonCode": reason, "field": "reviewRecords"}
                              for reason in ("CSPEC_MATCH_NOT_EXECUTABLE", "CSPEC_REVIEW_RECORDS_REQUIRED")]
        attempt.update(preflight=copy.deepcopy(source), blockers=copy.deepcopy(source["blockers"]))
        return batch

    def test_missing_review_projected_id_is_verified_without_manufacturing_selection(self):
        batch = self.projected_metadata_batch()
        before = copy.deepcopy(batch)
        summary = verifier.verify_dispatch(batch)
        self.assertEqual(1, summary["counts"]["projectedSelectionsVerified"])
        self.assertEqual([], summary["identities"])
        self.assertEqual(before, batch)
        self.assertIsNone(self.attempt(batch)["selection"])
        for field, value in (("selectionId", "cspec-selection-sha256-" + "0" * 64), ("outcome", "ZERO_MATCH"),
                             ("candidateOverlayIds", []), ("constraints", {})):
            with self.subTest(field=field):
                mutated = copy.deepcopy(batch)
                source = mutated["preflight"]["candidates"][0]["attempts"][0]
                source[field] = value
                self.attempt(mutated)["preflight"] = copy.deepcopy(source)
                with self.assertRaisesRegex(e1.ContractError, "PROJECTED_SELECTION_(ID|POLICY)_MISMATCH"):
                    verifier.verify_dispatch(mutated)

    def receipt_batch(self, hgnc_id=None, symbol=None, strict_fixture=False):
        batch = self.batch(hgnc_id, symbol, strict_fixture)
        attempt = self.attempt(batch)
        context, variant = attempt["context"], attempt["context"]["variant"]
        source = batch["preflight"]["candidates"][0]["attempts"][0]
        original = source["original"]
        receipts = []

        def ledger(name, records):
            envelope = {"source": name, "version": "synthetic-receipt-v1", "dataClass": "SYNTHETIC", "records": records}
            raw = json.dumps(envelope, separators=(",", ":"), ensure_ascii=False)
            digest = e1.sha256_bytes(raw.encode("utf-8"))
            receipts.append({"source": name, "sha256": digest, "rawJson": raw})
            return {"source": name, "version": envelope["version"], "artifactSha256": digest}

        preparation_sha, vep_sha = "a" * 64, "b" * 64
        reference_sha = variant["normalization"]["referenceSequenceSha256"]
        manifest = {"hashStatus": "VERIFIED", "unchangedDuringHash": True,
                    "observedSha256": reference_sha, "expectedSha256": reference_sha}
        reference_record = {"kind": "REFERENCE_IDENTITY", "verificationStatus": "VERIFIED",
                            "observedSha256": reference_sha, "expectedSha256": reference_sha,
                            "actualChecksumReceipt": manifest, "preparationArtifactSha256": preparation_sha}
        reference = {**ledger("SYNTHETIC_REFERENCE", {"reference": reference_record}), "recordLocator": "reference"}
        associated = {"analysisId": batch["preflight"]["analysisId"], "candidateId": original["candidateId"],
                      "hypothesisId": original["hypothesisId"], "patientContextId": context["patientContext"]["patientContextId"]}
        hypothesis_provenance = {"source": "SYNTHETIC", "version": "1", "recordLocator": "synthetic/hypothesis"}
        original["binding"] = {"provenance": {"patientHypothesis": {"provenance": hypothesis_provenance}}}
        patient = {**associated, **context["patientContext"], "kind": "PATIENT_CONTEXT",
                   "hypothesis": {"hgncId": context["gene"]["hgncId"], "mondoId": context["disease"]["mondoId"],
                                  "moi": context["moi"], "provenance": hypothesis_provenance}}
        transcript = {**associated, **context["transcript"], "kind": "TRANSCRIPT_CHOICE",
                      "hgncId": context["gene"]["hgncId"], "geneSymbol": context["gene"]["symbol"],
                      "annotationSource": attempt["transcriptInput"]["source"],
                      "annotationArtifactSha256": vep_sha, "preparationArtifactSha256": preparation_sha}
        observation = {key: variant["inputAllele"][key] for key in ("assembly", "contig", "position")}
        observation.update(expectedReference=variant["inputAllele"]["reference"], observedReference=variant["inputAllele"]["reference"], status="MATCH")
        normalization = {**associated, **variant["normalization"], "kind": "NORMALIZATION",
                         "executionStatus": "SUCCEEDED",
                         "originalAllele": variant["inputAllele"], "normalizedAllele": variant["normalizedAllele"],
                         "inputObservedReference": variant["inputAllele"]["reference"],
                         "normalizedObservedReference": variant["normalizedAllele"]["reference"], "reference": reference,
                         "preparationArtifactSha256": preparation_sha, "actualReferenceObservation": observation}
        lineage = {**associated, "kind": "ASSEMBLY_LINEAGE", "originalInputAssembly": "GRCh38",
                   "preRankingMappingStatus": "NATIVE", "originalInputAllele": variant["inputAllele"]}
        identity = ledger("SYNTHETIC_INPUT", {"patient": patient, "transcript": transcript, "normalization": normalization, "lineage": lineage})
        original["provenance"] = {name: {**identity, "recordLocator": name} for name in ("patient", "transcript", "normalization")}
        original["provenance"].update(reference=reference, assemblyLineage={"source": {**identity, "recordLocator": "lineage"}})
        attempt["preflight"] = copy.deepcopy(source)
        batch["smokeInputProvenance"] = {"sourceReceipts": receipts, "inputPreparationSha256": preparation_sha,
                                       "vepOutputSha256": vep_sha, "referenceManifest": manifest}
        if strict_fixture:
            # Explicitly constructed unit-test envelopes, never claimed as a real local resource run.
            batch["smokeInputProvenance"].update(resourceDataClass="PUBLIC", patientContextDataClass="SYNTHETIC",
                hypothesisDataClass="SYNTHETIC", candidateRankingDataClass="SYNTHETIC", clinicalValidation=False,
                selectionPolicy=verifier.REAL_EXACT_SELECTION_POLICY)
        return batch

    def test_smoke_receipts_resolve_original_raw_bytes_and_context_without_mutation(self):
        batch = self.receipt_batch()
        before = copy.deepcopy(batch)
        self.assertEqual(2, verifier.verify_dispatch(batch)["counts"]["smokeSourceReceiptsVerified"])
        self.assertEqual(before, batch)
        del batch["smokeInputProvenance"]["sourceReceipts"]
        with self.assertRaisesRegex(e1.ContractError, "SMOKE_SOURCE_RECEIPTS_REQUIRED"):
            verifier.verify_dispatch(batch)

    def test_receipt_raw_hash_locator_and_source_version_tampering_is_rejected(self):
        for field in ("rawJson", "recordLocator", "source", "version"):
            with self.subTest(field=field):
                batch = self.receipt_batch()
                if field == "rawJson":
                    batch["smokeInputProvenance"]["sourceReceipts"][0]["rawJson"] += " "
                else:
                    source = batch["preflight"]["candidates"][0]["attempts"][0]
                    source["original"]["provenance"]["transcript"][field] = "wrong"
                    self.attempt(batch)["preflight"] = copy.deepcopy(source)
                with self.assertRaisesRegex(e1.ContractError, "SMOKE_(RECEIPT_RAW_SHA_MISMATCH|SOURCE_REF_UNRESOLVED)"):
                    verifier.verify_dispatch(batch)

    def test_correctly_rehashed_receipt_with_wrong_association_cannot_claim_context_proof(self):
        batch = self.receipt_batch()
        receipt = batch["smokeInputProvenance"]["sourceReceipts"][1]
        envelope = json.loads(receipt["rawJson"])
        envelope["records"]["transcript"]["candidateId"] = "wrong-candidate"
        receipt["rawJson"] = json.dumps(envelope, separators=(",", ":"))
        receipt["sha256"] = e1.sha256_bytes(receipt["rawJson"].encode("utf-8"))
        source = batch["preflight"]["candidates"][0]["attempts"][0]
        provenance = source["original"]["provenance"]
        for name in ("patient", "transcript", "normalization"):
            provenance[name]["artifactSha256"] = receipt["sha256"]
        provenance["assemblyLineage"]["source"]["artifactSha256"] = receipt["sha256"]
        self.attempt(batch)["preflight"] = copy.deepcopy(source)
        with self.assertRaisesRegex(e1.ContractError, "SMOKE_RECEIPT_ASSOCIATION_MISMATCH"):
            verifier.verify_dispatch(batch)

    def test_strict_gate_counts_distinct_genes_across_batches_and_preserves_java_nodes(self):
        first = self.receipt_batch("HGNC:1", "SYNTH1", strict_fixture=True)
        second = self.receipt_batch("HGNC:2", "SYNTH2", strict_fixture=True)
        batches = [first, second]
        before = copy.deepcopy(batches)
        result = verifier.verify_real_exact_dispatch(batches, 2)
        self.assertEqual(2, result["realExactAcceptance"]["observedDistinctGenes"])
        self.assertEqual(before, batches)
        with self.assertRaisesRegex(e1.ContractError, "DISTINCT_GENE_COUNT_INSUFFICIENT"):
            verifier.verify_real_exact_dispatch([first, copy.deepcopy(first)], 2)

    def test_strict_gate_rejects_missing_score_false_match_and_incomplete_results(self):
        first = self.receipt_batch("HGNC:1", "SYNTH1", strict_fixture=True)
        second = self.receipt_batch("HGNC:2", "SYNTH2", strict_fixture=True)
        cases = (
            (lambda a: a["atomicEvidence"][0]["measurements"].__setitem__(slice(None),
                [m for m in a["atomicEvidence"][0]["measurements"] if m["metricId"] != "alphamissense.amPathogenicity"]), "MEASUREMENT_REQUIRED"),
            (lambda a: next(m for m in a["atomicEvidence"][0]["measurements"]
                            if m["metricId"] == "alphamissense.transcriptProteinMatch").update(rawValue=False, normalizedValue=False), "MATCH_REQUIRED"),
            (lambda a: a["criterionEvaluations"][0]["evaluationResult"].update(status="NOT_EVALUATED"), "COMPLETED_SUPPORTING"),
        )
        for mutate, reason in cases:
            with self.subTest(reason=reason):
                changed = copy.deepcopy(second)
                mutate(self.attempt(changed))
                # This is the additional acceptance gate after ordinary contract validation;
                # it must reject missing execution facts, independently of content-ID checks.
                with self.assertRaisesRegex(e1.ContractError, reason):
                    verifier._require_real_exact_genes([first, changed], 2)

    def test_strict_gate_requires_explicit_resource_and_synthetic_role_metadata(self):
        batch = self.receipt_batch("HGNC:1", "SYNTH1", strict_fixture=True)
        del batch["smokeInputProvenance"]["hypothesisDataClass"]
        with self.assertRaisesRegex(e1.ContractError, "REAL_EXACT_DATA_ROLES_REQUIRED"):
            verifier.verify_real_exact_dispatch(batch, 2)

    def test_committed_four_gene_mismatches_remain_no_score_not_evaluated_negatives(self):
        root = verifier.REPO_ROOT / "docs" / "execution"
        summary = e1.load_json(root / "E4_A2B_RESOURCE_SMOKE_SUMMARY.json")
        historical = [context for attempt in summary["realDispatchAttempts"] for context in attempt["contexts"]]
        self.assertEqual({"BRAF", "KRAS", "HBB", "TP53"}, {item["gene"]["symbol"] for item in historical})
        for record in historical:
            with self.subTest(gene=record["gene"]["symbol"]):
                self.assertIs(record["transcriptProteinMatch"], False)
                self.assertIs(record["actualScoreProduced"], False)
                self.assertEqual({"ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"},
                                 {item["criterionId"] for item in record["evaluations"]})
                self.assertTrue(all(item["result"]["status"] == "NOT_EVALUATED" for item in record["evaluations"]))
                self.assertTrue(all(row["transcriptId"] != record["transcriptInput"]["versionedTranscriptId"]
                                    for row in record["alphaMissenseIdentityColumnObservations"]))
        actual_java = e1.load_json(root / "E4_A2B_ALPHAMISSENSE_DISPATCH_SMOKE.json")
        before = copy.deepcopy(actual_java)
        self.assertEqual(2, verifier.verify_dispatch(actual_java)["counts"]["bundles"])
        for row in actual_java["candidates"]:
            self.assertTrue(all(item["evaluationResult"]["status"] == "NOT_EVALUATED"
                                for item in row["attempts"][0]["criterionEvaluations"]))
            self.assertFalse(any(metric["metricId"] == "alphamissense.amPathogenicity"
                                 for metric in row["attempts"][0]["atomicEvidence"][0]["measurements"]))
        with self.assertRaises(e1.ContractError):
            verifier.verify_real_exact_dispatch(actual_java, 2)
        self.assertEqual(before, actual_java)

    def preparation_batch(self, directory, mutate_vep=None, mutate_choice=None, mutate_prepared=None):
        # Tiny synthetic files exercise linkage only; no VEP/provider is executed here.
        batch = self.receipt_batch("HGNC:1", "SYNTH1", strict_fixture=True)
        attempt = self.attempt(batch)
        context, transcript = attempt["context"], attempt["transcriptInput"]
        transcript.update(geneId="ENSG00000000001", hgvsc="ENST00000335137.4:c.4G>T",
                          hgvsp="ENSP00000000001.1:p.Val2Leu", vepProteinIdentity="ENSP00000000001.1")
        choice = {**context["variant"]["inputAllele"], "caseId": "synthetic-raw-source",
                  "hgncId": context["gene"]["hgncId"], "symbol": context["gene"]["symbol"],
                  "syntheticHypothesisMondoId": context["disease"]["mondoId"], "syntheticHypothesisMoi": context["moi"],
                  "transcript": transcript["versionedTranscriptId"], "expectedHgvsp": transcript["hgvsp"],
                  "expectedProteinIdentity": transcript["vepProteinIdentity"], "expectedAminoAcidChange": "V2L",
                  "selectionPolicy": verifier.REAL_EXACT_SELECTION_POLICY,
                  "selectionBasis": "SYNTHETIC_UNIT_FIXTURE", "selectionSourceLocator": "synthetic/vep",
                  "selectionSourceSha256": "c" * 64, "identityReceiptLocator": "synthetic/identity"}
        input_line = "\t".join(str(choice[k]) for k in ("contig", "position", "caseId", "reference", "alternate")) + "\t.\tPASS\t."
        annotation = {"transcript_id": choice["transcript"], "gene_id": transcript["geneId"],
                      "gene_symbol": choice["symbol"], "hgnc_id": choice["hgncId"], "variant_allele": choice["alternate"],
                      "hgvsc": transcript["hgvsc"], "hgvsp": choice["expectedHgvsp"], "amino_acids": "V/L",
                      "protein_start": 2, "protein_end": 2, "consequence_terms": ["missense_variant"]}
        record = {"id": choice["caseId"], "input": input_line, "assembly_name": "GRCh38",
                  "seq_region_name": choice["contig"], "start": choice["position"], "end": choice["position"],
                  "allele_string": choice["reference"] + "/" + choice["alternate"], "transcript_consequences": [annotation]}
        if mutate_vep:
            mutate_vep(record)
        if mutate_choice:
            mutate_choice(choice)
        vcf = directory / "input.vcf"
        vep = directory / "output.jsonl"
        vcf.write_bytes(("##fileformat=VCFv4.2\n##reference=GRCh38\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" + input_line + "\n").encode())
        vep.write_bytes((json.dumps(record) + "\n").encode())
        fixtures_path, preparation_path = directory / "fixtures.json", directory / "preparation.json"
        fixed = {"fixedChoices": [choice], "choicesFixedAt": "2026-09-05T12:00:00Z",
                 "selectionPolicy": verifier.REAL_EXACT_SELECTION_POLICY}
        fixtures_path.write_bytes(json.dumps(fixed).encode())
        prepared = {"status": "PREPARED", "startedAt": "2026-09-05T12:00:01Z", "vep": {
            "exitCode": 0, "toolVersion": "116.0", "cacheVersion": "116_GRCh38", "outputRecordCount": 1,
            "outputJson": vep.name, "outputSha256": e1.sha256_file(vep),
            "inputVcf": vcf.name, "inputVcfSha256": e1.sha256_file(vcf)}}
        observation = json.loads(batch["smokeInputProvenance"]["sourceReceipts"][1]["rawJson"])["records"]["normalization"]["actualReferenceObservation"]
        prepared.update(referenceManifest=copy.deepcopy(batch["smokeInputProvenance"]["referenceManifest"]),
                        referenceObservations=[copy.deepcopy(observation)], vepReferenceObservations=[copy.deepcopy(observation)])
        if mutate_prepared:
            mutate_prepared(prepared)
        preparation = {**fixed, "sourceArtifact": fixtures_path.name, "sourceArtifactSha256": e1.sha256_file(fixtures_path),
                       "status": "PREPARED", "attempts": [prepared]}
        preparation_path.write_bytes(json.dumps(preparation).encode())
        smoke = batch["smokeInputProvenance"]
        smoke.update(copy.deepcopy(fixed))
        replacements = {smoke["inputPreparationSha256"]: e1.sha256_file(preparation_path),
                        smoke["vepOutputSha256"]: e1.sha256_file(vep)}
        # Source-ledger fixture identities are built correctly after choosing the raw inputs;
        # verifier assertions never repair a Java instance or its source references.
        for receipt in smoke["sourceReceipts"]:
            envelope = e1._replace_ids(json.loads(receipt["rawJson"]), replacements)
            for item in envelope["records"].values():
                if item["kind"] == "TRANSCRIPT_CHOICE":
                    item.update(explicitSelectionReceipt=copy.deepcopy(choice),
                                annotationRecordLocator="real-vep-output/" + choice["caseId"] + "/" + choice["transcript"])
            raw = json.dumps(envelope, separators=(",", ":"))
            digest = e1.sha256_bytes(raw.encode())
            replacements[receipt["sha256"]] = digest
            receipt.update(rawJson=raw, sha256=digest)
        return e1._replace_ids(batch, replacements), preparation_path, fixtures_path

    def test_raw_preparation_vcf_vep_and_transcript_receipts_resolve_without_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            batch, preparation, fixtures = self.preparation_batch(Path(directory))
            before = copy.deepcopy(batch)
            self.assertEqual(1, verifier.verify_dispatch(batch)["counts"]["bundles"])
            result = verifier.verify_preparation_sources(batch, preparation, fixtures)
            self.assertEqual(1, result["verifiedChoices"])
            self.assertEqual(before, batch)
            (Path(directory) / "output.jsonl").write_bytes(b"{}\n")
            with self.assertRaisesRegex(e1.ContractError, "PREPARATION_VEP_RAW_SHA_MISMATCH"):
                verifier.verify_preparation_sources(batch, preparation, fixtures)

    def test_rehashed_source_artifacts_cannot_hide_raw_vep_or_fixed_choice_mismatch(self):
        cases = (
            (lambda record: record.update(start=69095), None, None, "VEP_INPUT_ALLELE_MISMATCH"),
            (lambda record: record["transcript_consequences"][0].update(variant_allele="A"), None, None, "VEP_SELECTED_IDENTITY_MISMATCH"),
            (None, lambda choice: choice.update(transcript="ENST00000335137.5"), None, "VEP_SELECTED_TRANSCRIPT_MISMATCH"),
            (None, None, lambda prepared: prepared["referenceObservations"][0].update(observedAt="different-receipt"),
             "NORMALIZATION_REF_OBSERVATION_MISMATCH"),
        )
        for mutate_vep, mutate_choice, mutate_prepared, reason in cases:
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as directory:
                batch, preparation, fixtures = self.preparation_batch(Path(directory), mutate_vep, mutate_choice, mutate_prepared)
                self.assertEqual(1, verifier.verify_dispatch(batch)["counts"]["bundles"])
                before = copy.deepcopy(batch)
                with self.assertRaisesRegex(e1.ContractError, reason):
                    verifier.verify_preparation_sources(batch, preparation, fixtures)
                self.assertEqual(before, batch)


if __name__ == "__main__":
    unittest.main()
