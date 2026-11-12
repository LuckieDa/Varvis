package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;

/** Only the existing NOT_RUN bundle envelope; no classification or alternate canonicalizer. */
public final class AcmgBusinessArtifacts {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    public AcmgBusinessArtifacts(ObjectMapper mapper) { this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper); }
    public String requestHash(JsonNode request) { return adapter.canonicalSha256(request); }
    public ArrayNode bundles(ExplicitAlphaMissenseDispatcher.DispatchResult dispatch) {
        return bundles(dispatch, new AcmgExecutionProfile(mapper).contextCriteria());
    }
    public ArrayNode bundles(ExplicitAlphaMissenseDispatcher.DispatchResult dispatch, List<String> criteria) {
        ArrayNode rows = mapper.createArrayNode();
        for (var candidate : dispatch.candidates()) for (var attempt : candidate.attempts()) {
            if (attempt.transcriptInput() != null && attempt.transcriptInput().path("reviewedPrediction").isObject()) {
                ObjectNode bundle = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-business-artifact-v1")
                        .put("classificationEnabled", false).put("adoptionSupported", false).put("clinicalConclusionClaimed", false)
                        .put("combinationStatus", "NOT_RUN").put("candidateOrderingChanged", false);
                bundle.set("context", mapper.valueToTree(attempt.context()));
                bundle.set("execution", attempt.transcriptInput());
                bundle.set("atomicEvidence", mapper.valueToTree(attempt.atomicEvidence()));
                bundle.set("criterionEvaluations", mapper.valueToTree(attempt.criterionEvaluations()));
                bundle.set("blockers", mapper.valueToTree(attempt.blockers()));
                bundle.put("providerCalls", attempt.providerCalls()).put("evaluatorCalls", attempt.evaluatorCalls());
                bundle.put("bundleId", "prediction-business-artifact-sha256-" + adapter.canonicalSha256(bundle));
                rows.addObject().put("candidateId", candidate.candidateId()).put("hypothesisId", attempt.preflight().original().hypothesisId()).set("bundle", bundle);
                continue;
            }
            if (attempt.selection() == null || !attempt.blockers().isEmpty() || !AcmgExecutionProfile.evaluationsMatch(attempt.criterionEvaluations(), AcmgExecutionProfile.contextCriteria(attempt.transcriptInput()))) continue;
            ObjectNode context = attempt.context(); adapter.validateCompleteContext(context);
            ObjectNode combination = mapper.createObjectNode().put("schemaVersion", "combination-result-v2")
                    .put("contextId", context.path("contextId").asText()).put("executionStatus", "NOT_RUN")
                    .put("result", "NO_RESULT").put("reasonCode", "E1_COMBINATION_NOT_IMPLEMENTED")
                    .put("provisional", true).put("clinicalSignoffStatus", "NOT_SIGNED");
            combination.set("criterionEvaluationIds", ids(mapper.valueToTree(attempt.criterionEvaluations()), "evaluationId"));
            ObjectNode projection = combination.deepCopy();
            combination.putObject("conflict").put("status", "NONE").putArray("conflictIds");
            combination.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
            combination.put("combinationId", "combination-sha256-" + adapter.canonicalSha256(projection));
            ObjectNode pairing = IntronicSplicePolicy.splicePath(attempt.transcriptInput() == null ? "" : attempt.transcriptInput().path("predictionPath").asText())
                    ? CSpecExecutionPreflight.splice(mapper).registryPairing() : dispatch.preflight().registryPairing();
            if (attempt.transcriptInput() != null && "HNF1A_REVEL".equals(attempt.transcriptInput().path("predictionPath").asText()))
                pairing = Hnf1aRevelRuntime.registryPairing(mapper);
            ObjectNode authority = mapper.createObjectNode();
            for (String key : List.of("authorityCatalogId", "authorityCatalogSha256", "ruleRegistryId", "ruleRegistrySha256", "authorityCutoff", "asOf")) authority.set(key, pairing.path(key));
            authority.set("cspecOverlayRegistryId", pairing.path("overlayRegistryId"));
            authority.set("cspecOverlayRegistrySha256", pairing.path("overlayRegistrySha256"));
            ObjectNode bundle = mapper.createObjectNode().put("schemaVersion", "evidence-bundle-v2")
                    .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("dataClass", context.path("dataClass").asText())
                    .put("createdAt", Instant.now().toString()).put("candidateOrderingChanged", false).put("clinicalConclusionClaimed", false);
            bundle.putObject("producer").put("producerId", "varvis-pp3-bp4-business-entry").put("producerVersion", "1.0.0")
                    .put("canonicalizationProfile", "varvis-json-c14n-v1");
            bundle.set("authorityBinding", authority); bundle.set("context", context); bundle.set("cspecSelection", attempt.selection());
            bundle.set("atomicEvidence", mapper.valueToTree(attempt.atomicEvidence()));
            bundle.set("criterionEvaluations", mapper.valueToTree(attempt.criterionEvaluations()));
            bundle.set("reviewRecords", mapper.valueToTree(attempt.preflight().reviewRecords()));
            bundle.set("conflicts", mapper.valueToTree(attempt.preflight().conflicts()));
            bundle.putArray("legacyAdapterRecords"); bundle.set("combination", combination);
            ObjectNode identity = mapper.createObjectNode();
            for (String field : List.of("sourceOfTruth", "dataClass", "producer", "authorityBinding", "candidateOrderingChanged", "clinicalConclusionClaimed")) identity.set(field, bundle.path(field));
            identity.set("contextId", context.path("contextId")); identity.set("cspecSelectionId", attempt.selection().path("selectionId"));
            identity.set("evidenceIds", ids(bundle.path("atomicEvidence"), "evidenceId"));
            identity.set("evaluationIds", ids(bundle.path("criterionEvaluations"), "evaluationId"));
            identity.set("reviewRecordIds", ids(bundle.path("reviewRecords"), "reviewRecordId"));
            identity.set("conflictIds", ids(bundle.path("conflicts"), "conflictId"));
            identity.putArray("adapterRecordIds"); identity.set("combinationId", combination.path("combinationId"));
            bundle.put("bundleId", "bundle-sha256-" + adapter.canonicalSha256(identity));
            ObjectNode row = rows.addObject().put("candidateId", candidate.candidateId())
                    .put("hypothesisId", attempt.preflight().original().hypothesisId());
            row.set("bundle", bundle);
        }
        return rows;
    }
    private ArrayNode ids(JsonNode objects, String field) {
        // Sort the identity projection only; original evaluation/evidence arrays remain unchanged.
        var values = new java.util.ArrayList<String>(); objects.forEach(v -> values.add(v.path(field).asText()));
        values.sort((left, right) -> left.compareTo(right)); ArrayNode result = mapper.createArrayNode(); values.forEach(result::add); return result;
    }
}
