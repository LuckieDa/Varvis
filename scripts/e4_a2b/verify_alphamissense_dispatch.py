"""Verify opt-in AlphaMissense dispatch output without repairing Java identities."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from collections import Counter
from datetime import datetime
from decimal import Decimal
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.e3_c1 import validate_alphamissense_supporting_successor as c1
from scripts.e4_a2a import verify_cspec_preflight as a2a
from scripts.evidence import acmg_evidence_v2_contract as e1

REAL_EXACT_SELECTION_POLICY = "EXPLICIT_PUBLIC_VEP_AM_IDENTITY_COMPATIBILITY_V1"


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise e1.ContractError(reason)


def expected_pairing() -> dict:
    profile = e1.load_json(c1.PROFILE_PATH)
    return {
        "authorityCatalogId": "authority-catalog-acmg-evidence-v2-2026-08-31",
        "authorityCatalogSha256": e1.sha256_file(e1.AUTHORITY_PATH),
        "ruleRegistryId": a2a.RULE_ID,
        "ruleRegistrySha256": e1.sha256_file(a2a.RULE_PATH),
        "overlayRegistryId": a2a.OVERLAY_ID,
        "overlayRegistrySha256": e1.sha256_file(a2a.OVERLAY_PATH),
        "asOf": a2a.AS_OF,
        "authorityCutoff": "2026-08-31",
        "executionSlice": {
            "profileId": profile["profileId"], "profileVersion": profile["profileVersion"],
            "profileCanonicalSha256": e1.canonical_sha256(profile), "profileAsOf": profile["asOf"],
            "evaluatorId": "alphamissense-criterion-evaluator", "evaluatorVersion": "1.0.0-e3-c1",
            "criterionIds": list(c1.CRITERION_IDS), "strengthCap": "SUPPORTING",
        },
    }


def _latest_timestamp(value: object) -> str:
    times = [a2a.AS_OF]

    def visit(node: object) -> None:
        if isinstance(node, dict):
            for key, child in node.items():
                if key.endswith("At") and isinstance(child, str):
                    times.append(child)
                else:
                    visit(child)
        elif isinstance(node, list):
            for child in node:
                visit(child)

    visit(value)
    return max(times, key=e1._parse_datetime)


def _validate_java_atomic(atomic: dict, context: dict, schemas: e1.ContractSchemas) -> None:
    schemas.validate(atomic, "urn:varvis:schema:atomic-evidence-v2")
    require(atomic["contextId"] == context["contextId"], "ATOMIC_CONTEXT_MISMATCH")
    resource = e1.load_json(c1.PROFILE_PATH)["resources"][0]
    require(atomic["evidenceType"] == "COMPUTATIONAL_PREDICTION"
            and atomic["source"]["sourceId"] == resource["resourceId"]
            and atomic["source"]["resourceVersion"] == resource["resourceVersion"]
            and atomic["source"]["resourceSha256"] == resource["identityFingerprintSha256"],
            "ALPHAMISSENSE_RESOURCE_IDENTITY_MISMATCH")
    digest = e1.canonical_sha256(e1.evidence_identity_projection(atomic))
    require(atomic["evidenceId"] == "evidence-sha256-" + digest
            and atomic["provenance"]["identityProjectionSha256"] == digest, "ATOMIC_ID_MISMATCH")
    require(atomic["deduplicationKey"] == e1.content_id(
        "dedup-sha256-", e1.evidence_deduplication_projection(atomic)), "ATOMIC_DEDUP_ID_MISMATCH")


def _validate_java_evidence(attempt: dict, schemas: e1.ContractSchemas) -> None:
    context, selection = attempt["context"], attempt["selection"]
    evidence, evaluations = attempt["atomicEvidence"], attempt["criterionEvaluations"]
    require(len(evidence) == 1 and len(evaluations) == 2, "DISPATCH_EVIDENCE_CARDINALITY")
    _validate_java_atomic(evidence[0], context, schemas)
    require({item["criterionId"] for item in evaluations} == set(c1.CRITERION_IDS),
            "ONLY_PP3_BP4_ALLOWED")
    for item in evaluations:
        schemas.validate(item, "urn:varvis:schema:criterion-evaluation-v2")
        require(item["contextId"] == context["contextId"]
                and item["ruleBinding"]["cspecSelectionId"] == selection["selectionId"],
                "EVALUATION_CONTEXT_SELECTION_MISMATCH")
        require(item["evaluationId"] == e1.content_id(
            "evaluation-sha256-", e1.evaluation_identity_projection(item)), "EVALUATION_ID_MISMATCH")
        require(item["baseStrength"] == "SUPPORTING"
                and item["appliedStrength"] in {"NONE", "SUPPORTING"}, "SUPPORTING_CAP_EXCEEDED")
        trace = item["deterministicTrace"]
        require(trace["evaluatorId"] == "alphamissense-criterion-evaluator"
                and trace["evaluatorVersion"] == "1.0.0-e3-c1", "EVALUATOR_IDENTITY_MISMATCH")
        parameters = trace["parameters"]
        profile = e1.load_json(c1.PROFILE_PATH)
        expected_parameters = {"profile.id": profile["profileId"], "profile.version": profile["profileVersion"],
                               "profile.sha256": e1.canonical_sha256(profile)}
        if item["evaluationResult"]["status"] in {"MET", "NOT_MET"}:
            expected_parameters.update({"strength.cap": "SUPPORTING", "threshold.bp4.maxInclusive": "0.169",
                                        "threshold.pp3.minInclusive": "0.792"})
            require(all(metric.get("calibrationProfileId") == profile["profileId"] + "@" + profile["profileVersion"]
                        for metric in evidence[0]["measurements"] if metric["metricId"].startswith("alphamissense.")),
                    "ALPHAMISSENSE_CALIBRATION_MISMATCH")
        for name, expected in expected_parameters.items():
            require([parameter.get("value") for parameter in parameters if parameter.get("name") == name] == [expected],
                    "SUPPORTING_PROFILE_PARAMETER_MISMATCH")
        if trace["executionStatus"] == "COMPLETED":
            require(trace["traceSha256"] == e1.canonical_sha256(
                e1.deterministic_trace_projection(trace, include_hash=False)), "TRACE_ID_MISMATCH")
    require(sum(item["evaluationResult"]["status"] == "MET" for item in evaluations) <= 1,
            "PP3_BP4_MUTUAL_EXCLUSION")


def _validate_formal_preflight(attempt: dict) -> None:
    source, selection = attempt["preflight"], attempt["selection"]
    a2a.verify_preflight_rows([{
        "context": attempt["context"], "selection": selection,
        "reviewRecords": source["reviewRecords"], "conflicts": source["conflicts"],
    }])
    require(all(source.get(field) == selection[field] for field in (
        "selectionId", "outcome", "candidateOverlayIds")), "PREFLIGHT_SELECTION_WRAPPER_MISMATCH")
    policy_key = {"ZERO_MATCH": "zeroMatch", "SINGLE_SELECTABLE_MATCH": "singleSelectableMatch",
                  "SINGLE_NON_SELECTABLE_MATCH": "singleNonSelectableMatch",
                  "MULTIPLE_EXACT_MATCHES": "multipleExactMatches"}[selection["outcome"]]
    policy = e1.load_json(a2a.OVERLAY_PATH)["selectionPolicy"]["outcomes"][policy_key]
    require(source["constraints"] == policy, "PREFLIGHT_POLICY_WRAPPER_MISMATCH")


def _validate_transcript_input(attempt: dict) -> None:
    transcript, context = attempt["transcriptInput"], attempt["context"]
    require(isinstance(transcript, dict)
            and transcript.get("versionedTranscriptId") == "{accession}.{version}".format(**context["transcript"])
            and transcript.get("geneSymbol") == context["gene"]["symbol"]
            and transcript.get("annotationVersion") == context["transcript"]["annotationSourceVersion"]
            and isinstance(transcript.get("source"), str) and transcript["source"].startswith("VEP")
            and transcript.get("proteinIdentityMappingStatus") == "NOT_ESTABLISHED",
            "TRANSCRIPT_INPUT_IDENTITY_MISMATCH")


def _validate_projected_selection(attempt: dict, schemas: e1.ContractSchemas) -> None:
    """Check a real selection projection without manufacturing its missing review envelope."""
    source, context = attempt["preflight"], attempt["context"]
    schemas.validate(context, "urn:varvis:schema:evidence-context-v2")
    e1._assert_context_scope(context)
    require(context["variant"]["assemblyLineage"]["mappingStatus"] == "NATIVE"
            and all(context["variant"][field]["assembly"] == "GRCh38"
                    for field in ("inputAllele", "normalizedAllele", "evidenceQueryAllele")),
            "PROJECTED_SELECTION_NATIVE_GRCH38_REQUIRED")
    require(context["variant"]["alleleId"] == e1.content_id("allele-sha256-", e1.allele_identity_projection(context))
            and context["contextId"] == e1.content_id("context-sha256-", e1.context_identity_projection(context)),
            "PROJECTED_SELECTION_CONTEXT_ID_MISMATCH")
    registry = e1.load_json(a2a.OVERLAY_PATH)
    matches = [overlay for overlay in registry["overlays"] if e1._overlay_context_match(overlay, context)]
    if not matches:
        outcome, key = "ZERO_MATCH", "zeroMatch"
    elif len(matches) > 1:
        outcome, key = "MULTIPLE_EXACT_MATCHES", "multipleExactMatches"
    elif matches[0]["selectionStatus"] == "SELECTABLE":
        outcome, key = "SINGLE_SELECTABLE_MATCH", "singleSelectableMatch"
    else:
        outcome, key = "SINGLE_NON_SELECTABLE_MATCH", "singleNonSelectableMatch"
    policy = copy.deepcopy(registry["selectionPolicy"]["outcomes"][key])
    projection = {
        "schemaVersion": "cspec-selection-v1", "canonicalizationProfile": "varvis-json-c14n-v1",
        "contextId": context["contextId"], "overlayRegistryId": registry["registryId"],
        "overlayRegistrySha256": e1.sha256_file(a2a.OVERLAY_PATH),
        "policyId": registry["selectionPolicy"]["policyId"], "outcome": outcome,
        "candidateOverlayIds": [overlay["overlayId"] for overlay in matches],
    }
    if "reasonCode" in policy:
        projection["reasonCode"] = policy["reasonCode"]
    if outcome == "SINGLE_SELECTABLE_MATCH":
        projection.update(selectedOverlayId=matches[0]["overlayId"], selectedOverlaySha256=matches[0]["snapshotSha256"])
    expected_id = e1.content_id("cspec-selection-sha256-", e1.cspec_selection_identity_projection(projection))
    require(source["selectionId"] == expected_id and source["outcome"] == outcome
            and source["candidateOverlayIds"] == projection["candidateOverlayIds"], "PROJECTED_SELECTION_ID_MISMATCH")
    reasons = {blocker["reasonCode"] for blocker in source["blockers"]}
    require(bool(reasons), "PROJECTED_SELECTION_BLOCKER_REQUIRED")
    require((policy.get("reasonCode") in reasons) if policy["reviewConstraint"] == "REQUIRED"
            else "UNEXPECTED_SELECTION_REFERENCES" in reasons, "PROJECTED_SELECTION_REASON_MISMATCH")
    if "UNEXPECTED_SELECTION_REFERENCES" in reasons and policy["reviewConstraint"] == "NOT_REQUIRED":
        policy.update(criterionEvaluationConstraint="FORCE_NOT_EVALUATED", combinationDisposition="ABSTAIN")
    require(source["constraints"] == policy, "PROJECTED_SELECTION_POLICY_MISMATCH")


def _validate_smoke_receipts(batch: dict) -> int:
    """Replay only this smoke's exported receipt hashes/associations, not a production ledger."""
    if "smokeInputProvenance" not in batch:
        return 0
    smoke = batch["smokeInputProvenance"]
    receipts = smoke.get("sourceReceipts")
    require(isinstance(receipts, list) and bool(receipts), "SMOKE_SOURCE_RECEIPTS_REQUIRED")
    ledgers = {}

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "SMOKE_RECEIPT_DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    for receipt in receipts:
        raw = receipt.get("rawJson")
        require(isinstance(raw, str) and e1.sha256_bytes(raw.encode("utf-8")) == receipt.get("sha256"),
                "SMOKE_RECEIPT_RAW_SHA_MISMATCH")
        envelope = json.loads(raw, object_pairs_hook=unique_object)
        require(isinstance(envelope, dict) and receipt.get("source") == envelope.get("source")
                and isinstance(envelope.get("version"), str) and bool(envelope["version"])
                and envelope.get("dataClass") == "SYNTHETIC" and isinstance(envelope.get("records"), dict),
                "SMOKE_RECEIPT_ENVELOPE_MISMATCH")
        key = (envelope["source"], envelope["version"], receipt["sha256"])
        require(key not in ledgers, "SMOKE_RECEIPT_IDENTITY_DUPLICATE")
        ledgers[key] = envelope["records"]

    def resolve(ref, kind):
        require(isinstance(ref, dict), "SMOKE_SOURCE_REF_REQUIRED")
        key = (ref.get("source"), ref.get("version"), ref.get("artifactSha256"))
        records = ledgers.get(key, {})
        record = records.get(ref.get("recordLocator"))
        require(isinstance(record, dict) and record.get("kind") == kind, "SMOKE_SOURCE_REF_UNRESOLVED")
        return record

    for row in batch["candidates"]:
        for attempt in row["attempts"]:
            original, context = attempt["preflight"]["original"], attempt["context"]
            if context is None:
                continue  # An unavailable context stays blocked; no receipt is promoted into one.
            provenance, variant = original["provenance"], context["variant"]
            patient = resolve(provenance["patient"], "PATIENT_CONTEXT")
            transcript = resolve(provenance["transcript"], "TRANSCRIPT_CHOICE")
            normalization = resolve(provenance["normalization"], "NORMALIZATION")
            reference = resolve(provenance["reference"], "REFERENCE_IDENTITY")
            lineage = resolve(provenance["assemblyLineage"]["source"], "ASSEMBLY_LINEAGE")
            association = {"analysisId": batch["preflight"]["analysisId"], "candidateId": row["candidateId"],
                           "hypothesisId": original["hypothesisId"], "patientContextId": context["patientContext"]["patientContextId"]}
            for record in (patient, transcript, normalization, lineage):
                require(all(record.get(key) == value for key, value in association.items()), "SMOKE_RECEIPT_ASSOCIATION_MISMATCH")
            require(all(patient.get(key) == context["patientContext"].get(key)
                        for key in ("role", "affectedStatus", "sex", "familyContextId")), "SMOKE_PATIENT_RECEIPT_MISMATCH")
            hypothesis = patient["hypothesis"]
            require(hypothesis.get("hgncId") == context["gene"]["hgncId"]
                    and hypothesis.get("mondoId") == context["disease"]["mondoId"] and hypothesis.get("moi") == context["moi"]
                    and hypothesis.get("provenance") == original["binding"]["provenance"]["patientHypothesis"]["provenance"],
                    "SMOKE_HYPOTHESIS_RECEIPT_MISMATCH")
            require(all(transcript.get(key) == value for key, value in context["transcript"].items())
                    and transcript.get("hgncId") == context["gene"]["hgncId"] and transcript.get("geneSymbol") == context["gene"]["symbol"]
                    and transcript.get("annotationArtifactSha256") == smoke["vepOutputSha256"]
                    and transcript.get("preparationArtifactSha256") == smoke["inputPreparationSha256"], "SMOKE_TRANSCRIPT_RECEIPT_MISMATCH")
            if attempt["transcriptInput"] is not None:
                require(transcript.get("annotationSource") == attempt["transcriptInput"].get("source"), "SMOKE_ANNOTATION_SOURCE_MISMATCH")
            require(normalization.get("originalAllele") == variant["inputAllele"]
                    and normalization.get("normalizedAllele") == variant["normalizedAllele"]
                    and normalization.get("executionStatus") == "SUCCEEDED"
                    and all(normalization.get(key) == variant["normalization"][key]
                            for key in ("status", "method", "methodVersion", "refValidation"))
                    and normalization.get("inputObservedReference") == variant["inputAllele"]["reference"]
                    and normalization.get("normalizedObservedReference") == variant["normalizedAllele"]["reference"]
                    and normalization.get("reference") == provenance["reference"]
                    and normalization.get("preparationArtifactSha256") == smoke["inputPreparationSha256"], "SMOKE_NORMALIZATION_RECEIPT_MISMATCH")
            observation = normalization.get("actualReferenceObservation", {})
            require(all(observation.get(key) == variant["inputAllele"][key] for key in ("assembly", "contig", "position"))
                    and observation.get("expectedReference") == observation.get("observedReference") == variant["inputAllele"]["reference"]
                    and observation.get("status") == "MATCH", "SMOKE_REFERENCE_OBSERVATION_MISMATCH")
            resolve(normalization["reference"], "REFERENCE_IDENTITY")
            require(lineage.get("originalInputAssembly") == "GRCh38" and lineage.get("preRankingMappingStatus") == "NATIVE"
                    and lineage.get("originalInputAllele") == variant["inputAllele"]
                    and lineage.get("transformationId") is None, "SMOKE_LINEAGE_RECEIPT_MISMATCH")
            require(reference.get("actualChecksumReceipt") == smoke["referenceManifest"]
                    and reference.get("preparationArtifactSha256") == smoke["inputPreparationSha256"]
                    and reference.get("verificationStatus") == "VERIFIED"
                    and reference.get("observedSha256") == reference.get("expectedSha256") == variant["normalization"]["referenceSequenceSha256"],
                    "SMOKE_REFERENCE_RECEIPT_MISMATCH")
            manifest = smoke["referenceManifest"]
            require(manifest.get("hashStatus") == "VERIFIED" and manifest.get("unchangedDuringHash") is True
                    and manifest.get("observedSha256") == manifest.get("expectedSha256") == reference["observedSha256"],
                    "SMOKE_REFERENCE_MANIFEST_MISMATCH")
    return len(receipts)


def construct_bundle(attempt: dict, pairing: dict, schemas: e1.ContractSchemas | None = None) -> dict:
    """Add only the existing bundle/NOT_RUN envelope; validate all original Java nodes first."""
    schemas = schemas or e1.ContractSchemas()
    original = copy.deepcopy(attempt)
    require(pairing == expected_pairing(), "DISPATCH_REGISTRY_PAIRING_MISMATCH")
    preflight = attempt["preflight"]
    require(attempt["selection"] is not None, "FORMAL_SELECTION_REQUIRED")
    _validate_formal_preflight(attempt)
    require(not attempt["blockers"], "COMPLETE_BUNDLE_HAS_OUTER_BLOCKERS")
    _validate_java_evidence(attempt, schemas)
    context = attempt["context"]
    combination = {
        "schemaVersion": "combination-result-v2", "contextId": context["contextId"],
        "executionStatus": "NOT_RUN", "result": "NO_RESULT",
        "reasonCode": "E1_COMBINATION_NOT_IMPLEMENTED", "provisional": True,
        "clinicalSignoffStatus": "NOT_SIGNED",
        "criterionEvaluationIds": [item["evaluationId"] for item in attempt["criterionEvaluations"]],
        "conflict": {"status": "NONE", "conflictIds": []},
        "review": {"status": "NOT_REQUIRED", "decision": "NONE", "reviewRecordIds": []},
    }
    combination["combinationId"] = e1.content_id(
        "combination-sha256-", e1.combination_identity_projection(combination))
    binding = {key: pairing[key] for key in (
        "authorityCatalogId", "authorityCatalogSha256", "ruleRegistryId", "ruleRegistrySha256",
        "authorityCutoff", "asOf")}
    binding.update(cspecOverlayRegistryId=pairing["overlayRegistryId"],
                   cspecOverlayRegistrySha256=pairing["overlayRegistrySha256"])
    bundle = {
        "schemaVersion": "evidence-bundle-v2", "sourceOfTruth": "ACMG_EVIDENCE_V2",
        "dataClass": context["dataClass"], "createdAt": _latest_timestamp(attempt),
        "producer": {"producerId": "varvis-e4-a2b-offline-bundle-verifier", "producerVersion": "1.0.0",
                     "canonicalizationProfile": "varvis-json-c14n-v1"},
        "authorityBinding": binding, "context": copy.deepcopy(context),
        "cspecSelection": copy.deepcopy(attempt["selection"]),
        "atomicEvidence": copy.deepcopy(attempt["atomicEvidence"]),
        "criterionEvaluations": copy.deepcopy(attempt["criterionEvaluations"]),
        "reviewRecords": copy.deepcopy(preflight["reviewRecords"]),
        "conflicts": copy.deepcopy(preflight["conflicts"]), "legacyAdapterRecords": [],
        "combination": combination, "candidateOrderingChanged": False, "clinicalConclusionClaimed": False,
    }
    bundle["bundleId"] = e1.content_id("bundle-sha256-", e1.bundle_identity_projection(bundle))
    # Comparison only: never use rehash to replace or repair the emitted Java records.
    require(e1.rehash_bundle(bundle) == bundle, "JAVA_IDENTITIES_CHANGED_BY_REHASH")
    e1.validate_bundle(bundle, e1.load_json(e1.AUTHORITY_PATH), e1.load_json(a2a.RULE_PATH),
                       e1.load_json(a2a.OVERLAY_PATH), schemas,
                       rule_registry_path=a2a.RULE_PATH, cspec_registry_path=a2a.OVERLAY_PATH)
    require(attempt == original, "ORIGINAL_JAVA_RECORDS_MUTATED")
    return bundle


def verify_dispatch(data: dict | list) -> dict:
    batches = data if isinstance(data, list) else [data]
    require(bool(batches), "DISPATCH_BATCHES_REQUIRED")
    schemas, counts, identities = e1.ContractSchemas(), Counter(), []
    for batch_index, batch in enumerate(batches):
        counts["smokeSourceReceiptsVerified"] += _validate_smoke_receipts(batch)
        preflight = batch["preflight"]
        pairing = preflight["registryPairing"]
        if pairing is not None:
            require(pairing == expected_pairing(), "DISPATCH_REGISTRY_PAIRING_MISMATCH")
        rows = batch["candidates"]
        require(type(preflight["requestedN"]) is int and preflight["requestedN"] > 0
                and preflight["selectedCount"] == len(rows) <= preflight["requestedN"]
                and preflight["candidateOrderingChanged"] is False, "PREFLIGHT_BATCH_SHAPE_INVALID")
        require(len(rows) == len(preflight["candidates"]), "DISPATCH_ROW_COUNT_CHANGED")
        require([row["candidateId"] for row in rows] == preflight["candidateIds"],
                "DISPATCH_CANDIDATE_ORDER_CHANGED")
        provider_calls = evaluator_calls = 0
        for row, source_row in zip(rows, preflight["candidates"], strict=True):
            require(row["candidateId"] == source_row["candidateId"]
                    and len(row["attempts"]) == len(source_row["attempts"]), "DISPATCH_ATTEMPTS_CHANGED")
            for attempt, source in zip(row["attempts"], source_row["attempts"], strict=True):
                counts["attempts"] += 1
                require(attempt["preflight"] == source, "PREFLIGHT_PROVENANCE_CHANGED")
                require(attempt["context"] == source["original"]["context"]
                        and attempt["selection"] == source["selection"], "DISPATCH_CONTEXT_SELECTION_CHANGED")
                require(source["original"]["candidateId"] == row["candidateId"], "DISPATCH_ASSOCIATION_CHANGED")
                p, v = attempt["providerCalls"], attempt["evaluatorCalls"]
                require(type(p) is int and type(v) is int and (p, v) in {(0, 0), (1, 0), (1, 1)},
                        "DISPATCH_CALL_COUNTS_INVALID")
                provider_calls += p
                evaluator_calls += v
                blockers = attempt["blockers"]
                require(isinstance(blockers, list), "DISPATCH_BLOCKERS_REQUIRED")
                for blocker in blockers:
                    require(isinstance(blocker, dict) and all(isinstance(blocker.get(key), str)
                            and blocker[key] for key in ("status", "reasonCode", "field")),
                            "DISPATCH_BLOCKER_INCOMPLETE")
                require(all(blocker in blockers for blocker in source["blockers"]),
                        "PREFLIGHT_BLOCKERS_DROPPED")
                selection = attempt["selection"]
                if selection is None:
                    require((p, v) == (0, 0), "NO_SELECTION_MUST_NOT_DISPATCH")
                    if source.get("selectionId") is not None:
                        require(pairing is not None, "PROJECTED_SELECTION_PAIRING_REQUIRED")
                        _validate_projected_selection(attempt, schemas)
                        counts["projectedSelectionsVerified"] += 1
                else:
                    require(pairing is not None, "FORMAL_SELECTION_PAIRING_REQUIRED")
                    if (p, v) != (1, 1) or not attempt["criterionEvaluations"]:
                        _validate_formal_preflight(attempt)
                    if selection["outcome"] != "ZERO_MATCH" or source["blockers"]:
                        require((p, v) == (0, 0), "CSPEC_BLOCKED_MUST_NOT_DISPATCH")
                if p:
                    _validate_transcript_input(attempt)
                if (p, v) != (1, 1):
                    require(not attempt["atomicEvidence"] and not attempt["criterionEvaluations"]
                            and bool(blockers), "BLOCKED_ATTEMPT_MUST_NOT_FAKE_EVIDENCE")
                    counts["blockedBeforeDispatch" if p == 0 else "incompleteProviderFailures"] += 1
                    continue
                require(selection is not None and not source["blockers"], "PREFLIGHT_BLOCKED_MUST_NOT_DISPATCH")
                transcript = attempt["transcriptInput"]
                if not attempt["criterionEvaluations"] and blockers:
                    require(len(attempt["atomicEvidence"]) == 1, "INCOMPLETE_EVALUATOR_ATOMIC_REQUIRED")
                    _validate_java_atomic(attempt["atomicEvidence"][0], attempt["context"], schemas)
                    counts["incompleteEvaluatorFailures"] += 1
                    continue
                bundle = construct_bundle(attempt, pairing, schemas)
                counts["bundles"] += 1
                identities.append({
                    "batchIndex": batch_index, "candidateId": row["candidateId"],
                    "hypothesisId": source["original"]["hypothesisId"],
                    "contextId": bundle["context"]["contextId"], "selectionId": bundle["cspecSelection"]["selectionId"],
                    "evidenceIds": [item["evidenceId"] for item in bundle["atomicEvidence"]],
                    "evaluationIds": [item["evaluationId"] for item in bundle["criterionEvaluations"]],
                    "resourceExecution": bundle["atomicEvidence"][0]["resourceExecution"]["status"],
                    "queryOutcome": bundle["atomicEvidence"][0]["queryOutcome"]["status"],
                    "criterionResults": {item["criterionId"]: item["evaluationResult"]["status"]
                                         for item in bundle["criterionEvaluations"]},
                    "transcriptInput": copy.deepcopy(transcript),
                    "bundleId": bundle["bundleId"], "combinationId": bundle["combination"]["combinationId"],
                })
        require(type(batch["providerCalls"]) is int and type(batch["evaluatorCalls"]) is int
                and batch["providerCalls"] == provider_calls and batch["evaluatorCalls"] == evaluator_calls,
                "BATCH_CALL_COUNTS_MISMATCH")
        counts["providerCalls"] += provider_calls
        counts["evaluatorCalls"] += evaluator_calls
    return {"status": "PASS", "batchCount": len(batches), "counts": dict(counts),
            "originalJavaIdentitiesAndReferencesVerified": True, "originalJavaNodesUnchanged": True,
            "combinationExecutionStatus": "NOT_RUN", "combinationResult": "NO_RESULT",
            "clinicalValidation": False, "identities": identities}


def _require_real_exact_genes(data: dict | list, required_genes: int, *, metadata_field="smokeInputProvenance",
                              receipt_field=None) -> dict:
    """Additional acceptance gate for already validated current smoke outputs, across batches."""
    require(type(required_genes) is int and required_genes >= 2, "REAL_EXACT_MINIMUM_TWO_GENES_REQUIRED")
    batches = data if isinstance(data, list) else [data]
    genes, symbols, observations = set(), set(), []
    for batch in batches:
        smoke = batch.get(metadata_field, {})
        require(smoke.get("resourceDataClass") == "PUBLIC"
                and all(smoke.get(field) == "SYNTHETIC" for field in (
                    "patientContextDataClass", "hypothesisDataClass", "candidateRankingDataClass"))
                and smoke.get("clinicalValidation") is False, "REAL_EXACT_DATA_ROLES_REQUIRED")
        require(smoke.get("selectionPolicy") == REAL_EXACT_SELECTION_POLICY, "REAL_EXACT_SELECTION_POLICY_REQUIRED")
        require(bool(batch.get(receipt_field) if receipt_field else smoke.get("sourceReceipts")), "SMOKE_SOURCE_RECEIPTS_REQUIRED")
        for row in batch["candidates"]:
            for attempt in row["attempts"]:
                require(not attempt["blockers"] and attempt["providerCalls"] == attempt["evaluatorCalls"] == 1
                        and len(attempt["atomicEvidence"]) == 1 and len(attempt["criterionEvaluations"]) == 2,
                        "REAL_EXACT_COMPLETE_DISPATCH_REQUIRED")
                context, transcript, atomic = attempt["context"], attempt["transcriptInput"], attempt["atomicEvidence"][0]
                require(context["dataClass"] == "SYNTHETIC" and transcript["source"] == "VEP"
                        and transcript.get("consequence") == "missense_variant"
                        and context["transcript"]["selectionPolicy"] == smoke["selectionPolicy"],
                        "REAL_EXACT_VEP_CONTEXT_REQUIRED")
                require(atomic["resourceExecution"]["status"] == "SUCCEEDED"
                        and atomic["resourceAvailability"]["status"] == "AVAILABLE"
                        and atomic["queryOutcome"]["status"] == "HIT" and atomic["queryOutcome"].get("recordCount") == 1
                        and atomic["resourceApplicability"]["status"] == "APPLICABLE", "REAL_EXACT_RESOURCE_AXES_REQUIRED")

                def metric(name):
                    found = [item for item in atomic["measurements"] if item["metricId"] == "alphamissense." + name]
                    require(len(found) == 1, "REAL_EXACT_MEASUREMENT_REQUIRED:" + name)
                    require(found[0].get("calibrationProfileId") == "ALPHAMISSENSE_PP3_BP4_SUPPORTING@1.0.0",
                            "REAL_EXACT_CALIBRATION_MISMATCH")
                    return found[0]

                for name in ("exactAlleleMatch", "transcriptProteinMatch"):
                    match = metric(name)
                    require(match["rawValue"] is True and match["normalizedValue"] is True,
                            "REAL_EXACT_TRANSCRIPT_PROTEIN_MATCH_REQUIRED")
                for name, expected in (("transcriptId", transcript["versionedTranscriptId"]),
                                       ("proteinVariant", transcript.get("aminoAcidChange"))):
                    identity = metric(name)
                    require(isinstance(expected, str) and bool(expected)
                            and identity["rawValue"] == identity["normalizedValue"] == expected,
                            "REAL_EXACT_TRANSCRIPT_PROTEIN_IDENTITY_MISMATCH")
                score_metric = metric("amPathogenicity")
                require(score_metric["rawValue"] == score_metric["normalizedValue"], "REAL_EXACT_SCORE_LEXEME_MISMATCH")
                score = c1._decimal(score_metric["rawValue"], "AlphaMissense actual score")
                require(Decimal("0") <= score <= Decimal("1"), "REAL_EXACT_SCORE_RANGE_INVALID")
                expected_results = {"ACMG_AMP_2015:PP3": "MET" if score >= Decimal("0.792") else "NOT_MET",
                                    "ACMG_AMP_2015:BP4": "MET" if score <= Decimal("0.169") else "NOT_MET"}
                for evaluation in attempt["criterionEvaluations"]:
                    status = expected_results[evaluation["criterionId"]]
                    require(evaluation["deterministicTrace"]["executionStatus"] == "COMPLETED"
                            and evaluation["evaluationResult"]["status"] == status
                            and evaluation["appliedStrength"] == ("SUPPORTING" if status == "MET" else "NONE"),
                            "REAL_EXACT_COMPLETED_SUPPORTING_EVALUATIONS_REQUIRED")
                genes.add(context["gene"]["hgncId"])
                symbols.add(context["gene"]["symbol"])
                observations.append({"hgncId": context["gene"]["hgncId"], "symbol": context["gene"]["symbol"],
                                     "contextId": context["contextId"], "transcript": transcript["versionedTranscriptId"],
                                     "aminoAcidChange": transcript["aminoAcidChange"],
                                     "rawScore": score_metric["rawValue"], "criterionResults": expected_results})
    require(len(genes) >= required_genes and len(symbols) >= required_genes, "REAL_EXACT_DISTINCT_GENE_COUNT_INSUFFICIENT")
    return {"status": "PASS", "requiredDistinctGenes": required_genes, "observedDistinctGenes": len(genes),
            "selectionPolicy": REAL_EXACT_SELECTION_POLICY, "observations": observations,
            "clinicalValidation": False, "originalJavaNodesUnchanged": True}


def verify_real_exact_dispatch(data: dict | list, required_genes: int = 2) -> dict:
    """Explicit real exact-hit acceptance; ordinary failure validation remains verify_dispatch."""
    result = verify_dispatch(data)
    result["realExactAcceptance"] = _require_real_exact_genes(data, required_genes)
    return result


def verify_preparation_sources(batch: dict, preparation_path: Path, fixtures_path: Path) -> dict:
    """Additional tiny-artifact proof, alongside ordinary/strict Java instance validation."""
    require(isinstance(batch, dict), "PREPARATION_SINGLE_BATCH_REQUIRED")
    original = copy.deepcopy(batch)

    def read_tiny(path):
        require(path.is_file() and path.stat().st_size <= 8 * 1024 * 1024, "PREPARATION_TINY_ARTIFACT_REQUIRED")
        return path.read_bytes()

    def artifact(locator):
        require(isinstance(locator, str) and bool(locator) and not Path(locator).is_absolute()
                and "\\" not in locator and ":" not in locator, "PREPARATION_RELATIVE_ARTIFACT_REQUIRED")
        root = preparation_path.resolve().parent
        path = (root / locator).resolve()
        require(path.is_relative_to(root), "PREPARATION_ARTIFACT_OUTSIDE_ROOT")
        return read_tiny(path)

    preparation_raw, fixture_raw = read_tiny(preparation_path), read_tiny(fixtures_path)
    preparation, fixtures = json.loads(preparation_raw), json.loads(fixture_raw)
    smoke = batch["smokeInputProvenance"]
    preparation_sha = e1.sha256_bytes(preparation_raw)
    require(preparation_sha == smoke["inputPreparationSha256"], "PREPARATION_RAW_SHA_MISMATCH")
    require(preparation.get("sourceArtifactSha256") == e1.sha256_bytes(fixture_raw)
            and preparation.get("sourceArtifact") == fixtures_path.name, "PREPARATION_FIXTURE_ARTIFACT_MISMATCH")
    for field in ("fixedChoices", "choicesFixedAt", "selectionPolicy"):
        require(fixtures.get(field) is not None and fixtures[field] == preparation.get(field) == smoke.get(field),
                "PREPARATION_FIXED_CHOICES_MISMATCH:" + field)
    require(fixtures["selectionPolicy"] == REAL_EXACT_SELECTION_POLICY, "PREPARATION_SELECTION_POLICY_MISMATCH")
    choices = fixtures["fixedChoices"]
    require(isinstance(choices, list) and len(choices) == len(batch["candidates"])
            and len({c["caseId"] for c in choices}) == len(choices), "PREPARATION_CANDIDATE_COUNT_MISMATCH")
    require(preparation.get("status") == "PREPARED" and bool(preparation.get("attempts"))
            and preparation["attempts"][-1].get("status") == "PREPARED", "PREPARATION_LAST_ATTEMPT_NOT_PREPARED")
    prepared = preparation["attempts"][-1]
    require(prepared.get("referenceManifest") == smoke.get("referenceManifest"), "PREPARATION_REFERENCE_MANIFEST_MISMATCH")
    require(all(isinstance(prepared.get(key), list) and len(prepared[key]) == len(choices)
                for key in ("referenceObservations", "vepReferenceObservations")), "PREPARATION_REFERENCE_OBSERVATIONS_REQUIRED")
    require(datetime.fromisoformat(fixtures["choicesFixedAt"].replace("Z", "+00:00"))
            < datetime.fromisoformat(prepared["startedAt"].replace("Z", "+00:00")), "PREPARATION_CHOICE_NOT_FIXED_BEFORE_EXECUTION")
    vep = prepared["vep"]
    require(vep.get("exitCode") == 0 and vep.get("toolVersion") == "116.0"
            and vep.get("cacheVersion") == "116_GRCh38", "PREPARATION_REAL_VEP_EXECUTION_REQUIRED")
    vep_raw, vcf_raw = artifact(vep["outputJson"]), artifact(vep["inputVcf"])
    vep_sha = e1.sha256_bytes(vep_raw)
    require(vep_sha == vep.get("outputSha256") == smoke["vepOutputSha256"], "PREPARATION_VEP_RAW_SHA_MISMATCH")
    require(e1.sha256_bytes(vcf_raw) == vep.get("inputVcfSha256"), "PREPARATION_VCF_RAW_SHA_MISMATCH")
    records = [json.loads(line) for line in vep_raw.decode("utf-8").splitlines() if line.strip()]
    vcf_lines = vcf_raw.decode("utf-8").splitlines()
    input_lines = [line for line in vcf_lines if line and not line.startswith("#")]
    require("##reference=GRCh38" in vcf_lines and len(records) == len(input_lines) == len(choices)
            and vep.get("outputRecordCount") == len(records), "PREPARATION_RECORD_COUNT_OR_ASSEMBLY_MISMATCH")
    _validate_smoke_receipts(batch)
    ledgers = {(r["source"], json.loads(r["rawJson"])["version"], r["sha256"]): json.loads(r["rawJson"])["records"]
               for r in smoke["sourceReceipts"]}
    for index, (choice, row) in enumerate(zip(choices, batch["candidates"])):
        expected_input = "\t".join(str(choice[k]) for k in ("contig", "position", "caseId", "reference", "alternate")) + "\t.\tPASS\t."
        require(choice["assembly"] == "GRCh38" and choice["selectionPolicy"] == fixtures["selectionPolicy"]
                and input_lines.count(expected_input) == 1, "PREPARATION_FIXED_VCF_ALLELE_MISMATCH")
        found = [record for record in records if record.get("id") == choice["caseId"]]
        require(len(found) == 1, "PREPARATION_VEP_CASE_ID_MISMATCH")
        record = found[0]
        require(record.get("input") == expected_input and record.get("assembly_name") == "GRCh38"
                and record.get("seq_region_name") == choice["contig"]
                and record.get("start") == record.get("end") == choice["position"]
                and record.get("allele_string") == choice["reference"] + "/" + choice["alternate"],
                "PREPARATION_VEP_INPUT_ALLELE_MISMATCH")
        selected = [a for a in record.get("transcript_consequences", []) if a.get("transcript_id") == choice["transcript"]]
        require(len(selected) == 1, "PREPARATION_VEP_SELECTED_TRANSCRIPT_MISMATCH")
        annotation = selected[0]
        amino, position = annotation.get("amino_acids", ""), annotation.get("protein_start")
        require(re.fullmatch(r"[ACDEFGHIKLMNPQRSTVWY]/[ACDEFGHIKLMNPQRSTVWY]", amino)
                and type(position) is int and position > 0 and annotation.get("protein_end") == position,
                "PREPARATION_VEP_AMINO_ACID_REQUIRED")
        change = amino[0] + str(position) + amino[2]
        require(annotation.get("gene_symbol") == choice["symbol"] and annotation.get("hgnc_id") == choice["hgncId"]
                and annotation.get("variant_allele") == choice["alternate"]
                and ("allele_num" not in annotation or type(annotation["allele_num"]) is int and annotation["allele_num"] == 1)
                and "missense_variant" in annotation.get("consequence_terms", [])
                and annotation.get("hgvsp") == choice["expectedHgvsp"]
                and annotation["hgvsp"].split(":", 1)[0] == choice["expectedProteinIdentity"]
                and change == choice["expectedAminoAcidChange"], "PREPARATION_VEP_SELECTED_IDENTITY_MISMATCH")
        require(len(row["attempts"]) == 1, "PREPARATION_SINGLE_ATTEMPT_PER_CHOICE_REQUIRED")
        attempt = row["attempts"][0]
        context, transcript = attempt["context"], attempt["transcriptInput"]
        for key in ("referenceObservations", "vepReferenceObservations"):
            observed = prepared[key][index]
            require(all(observed.get(field) == choice[field] for field in ("assembly", "contig", "position"))
                    and observed.get("expectedReference") == observed.get("observedReference") == choice["reference"]
                    and observed.get("status") == "MATCH", "PREPARATION_REFERENCE_OBSERVATION_MISMATCH")
        require(context["gene"] == {"hgncId": choice["hgncId"], "symbol": choice["symbol"]}
                and all(context["variant"]["inputAllele"][key] == choice[key]
                        for key in ("assembly", "contig", "position", "reference", "alternate"))
                and context["disease"]["mondoId"] == choice["syntheticHypothesisMondoId"]
                and context["moi"] == choice["syntheticHypothesisMoi"], "PREPARATION_JAVA_CONTEXT_CHOICE_MISMATCH")
        expected_transcript = {"source": "VEP", "annotationVersion": vep["toolVersion"], "geneSymbol": choice["symbol"],
                               "geneId": annotation.get("gene_id"), "versionedTranscriptId": choice["transcript"],
                               "hgvsc": annotation.get("hgvsc"), "hgvsp": annotation["hgvsp"],
                               "vepProteinIdentity": choice["expectedProteinIdentity"], "aminoAcidChange": change,
                               "consequence": "missense_variant"}
        require(all(transcript.get(k) == value for k, value in expected_transcript.items()), "PREPARATION_JAVA_TRANSCRIPT_MISMATCH")
        ref = attempt["preflight"]["original"]["provenance"]["transcript"]
        receipt = ledgers[(ref["source"], ref["version"], ref["artifactSha256"])][ref["recordLocator"]]
        require(receipt.get("explicitSelectionReceipt") == choice
                and receipt.get("annotationRecordLocator") == "real-vep-output/" + choice["caseId"] + "/" + choice["transcript"]
                and receipt.get("annotationArtifactSha256") == vep_sha
                and receipt.get("preparationArtifactSha256") == preparation_sha, "PREPARATION_TRANSCRIPT_SOURCE_RECEIPT_MISMATCH")
        ref = attempt["preflight"]["original"]["provenance"]["normalization"]
        normalization = ledgers[(ref["source"], ref["version"], ref["artifactSha256"])][ref["recordLocator"]]
        require(normalization.get("actualReferenceObservation") == prepared["referenceObservations"][index],
                "PREPARATION_NORMALIZATION_REF_OBSERVATION_MISMATCH")
    require(batch == original, "ORIGINAL_JAVA_RECORDS_MUTATED")
    return {"status": "PASS", "inputPreparationSha256": preparation_sha, "fixtureSha256": e1.sha256_bytes(fixture_raw),
            "vepOutputSha256": vep_sha, "inputVcfSha256": e1.sha256_bytes(vcf_raw), "verifiedChoices": len(choices),
            "originalJavaNodesUnchanged": True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dispatch", type=Path, action="append", required=True)
    parser.add_argument("--summary-output", type=Path)
    parser.add_argument("--require-real-exact-genes", type=int,
                        help="Require this many distinct real exact-hit genes across all --dispatch files (minimum 2)")
    parser.add_argument("--preparation", type=Path, help="Verify raw tiny preparation/VCF/VEP artifacts for one strict dispatch")
    parser.add_argument("--fixtures", type=Path, help="Explicit fixed choices associated with --preparation")
    args = parser.parse_args()
    if args.preparation is not None or args.fixtures is not None:
        require(args.preparation is not None and args.fixtures is not None
                and len(args.dispatch) == 1 and args.require_real_exact_genes is not None,
                "PREPARATION_REQUIRES_FIXTURES_AND_SINGLE_STRICT_DISPATCH")
    resources = a2a.verify_resources()
    c1.validate_committed_successors()
    inputs = [e1.load_json(path) for path in args.dispatch]
    summary = {"status": "PASS", "registryPairing": resources,
               "dispatchChecks": [verify_dispatch(value) for value in inputs]}
    if args.require_real_exact_genes is not None:
        batches = [batch for value in inputs for batch in (value if isinstance(value, list) else [value])]
        summary["realExactAcceptance"] = _require_real_exact_genes(batches, args.require_real_exact_genes)
    if args.preparation is not None:
        summary["preparationSourceVerification"] = verify_preparation_sources(inputs[0], args.preparation, args.fixtures)
    output = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    if args.summary_output:
        args.summary_output.write_text(output, encoding="utf-8", newline="\n")
    print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
