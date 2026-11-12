package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver;
import static org.nbscn.varvis.variantanalysis.provider.SavedPredictionApplicationInputs.*;

/** Read-only, pinned ordinary-business source. No provider, evaluator or threshold comparison. */
final class PredictionClauseSourceView {
    private final ObjectNode value;
    private PredictionClauseSourceView(ObjectNode value) { this.value = value.deepCopy(); }
    ObjectNode json() { return value.deepCopy(); }

    static String candidateIssue(ObjectMapper mapper, Input input, JsonNode candidate) {
        try {
            require(input.sourceKind() == SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1 && input.issue().isEmpty(), "CLAUSE_TRUSTED_SOURCE_REQUIRED");
            JsonNode row = unique(input.document("run").path("candidates"), "candidateId", candidate.path("candidateId").asText());
            require(row.path("attempts").isArray() && !row.path("attempts").isEmpty(), "CLAUSE_ATTEMPTS_REQUIRED");
            Set<String> ids = new HashSet<>();
            for (JsonNode attempt : row.path("attempts")) {
                String hypothesis = attempt.at("/preflight/original/hypothesisId").asText();
                require(!hypothesis.isBlank() && ids.add(hypothesis), "CLAUSE_HYPOTHESIS_AMBIGUOUS");
                validateAttempt(mapper, input, candidate, attempt);
                JsonNode clauses = attempt.at("/transcriptInput/reviewedPrediction/clauses");
                for (String code : List.of("PP3", "BP4")) if ("CURRENT_CATALOG_RESULT".equals(clauses.path(code).path("clauseRouting").asText()))
                    read(mapper, input, candidate, hypothesis, code);
            }
            int count = 0;
            for (JsonNode bundle : input.document("run").path("bundles")) if (candidate.path("candidateId").equals(bundle.path("candidateId"))) count++;
            require(count == ids.size(), "CLAUSE_BUSINESS_BUNDLE_SET_MISMATCH");
            for (JsonNode hypothesis : candidate.path("hypotheses")) require(ids.contains(hypothesis.path("hypothesisId").asText()), "CLAUSE_HYPOTHESIS_NOT_SAVED");
            return "";
        } catch (RuntimeException invalid) { return safeClauseIssue(invalid); }
    }

    static ObjectNode selectedAnnotation(ObjectMapper mapper, Input input, JsonNode candidate) {
        ObjectNode selected = null;
        JsonNode row = unique(input.document("run").path("candidates"), "candidateId", candidate.path("candidateId").asText());
        for (JsonNode attempt : row.path("attempts")) {
            JsonNode context = attempt.path("context");
            JsonNode annotation = annotation(input, candidate, context);
            ObjectNode next = mapper.createObjectNode().put("versionedTranscriptId", versionedAnnotationIdentity(annotation))
                    .put("annotationSourceSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(annotation));
            for (String field : List.of("source", "version", "status")) next.set(field, annotation.path(field));
            require(selected == null || selected.equals(next), "CONTEXT_MULTIPLE_SELECTIONS_UNSUPPORTED"); selected = next;
        }
        if (selected == null) throw new IllegalArgumentException("CONTEXT_SAVED_SELECTION_MISSING");
        return selected;
    }

    private static JsonNode annotation(Input input, JsonNode candidate, JsonNode context) {
        String full = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        JsonNode found = null;
        for (JsonNode annotation : publicationCandidate(input, candidate.path("candidateId").asText()).path("annotations")) {
            String identity;
            try { identity = versionedAnnotationIdentity(annotation); }
            catch (IllegalArgumentException unrelated) { continue; }
            if (!full.equals(identity)) continue;
            require(found == null, "CLAUSE_SELECTED_ANNOTATION_AMBIGUOUS"); found = annotation;
        }
        if (found == null) throw new IllegalArgumentException("CLAUSE_SELECTED_ANNOTATION_MISSING");
        require("COMPLETED".equals(found.path("status").asText()) && nonblank(found, "source")
                && found.path("version").equals(context.at("/transcript/annotationSourceVersion")), "CLAUSE_SELECTED_ANNOTATION_SOURCE_MISMATCH");
        return found;
    }

    private static void validateAttempt(ObjectMapper mapper, Input input, JsonNode candidate, JsonNode attempt) {
        var hash = new RuntimeEvidenceV2Adapter(mapper);
        hash.canonicalSha256(attempt);
        JsonNode original = attempt.at("/preflight/original"), context = attempt.path("context");
        require(attempt.isObject() && candidate.path("candidateId").equals(original.path("candidateId"))
                && context.isObject() && context.equals(original.path("context")), "CLAUSE_CANDIDATE_CONTEXT_MISMATCH");
        hash.validateCompleteContext(context);
        require(context.path("dataClass").equals(input.settings().at("/task/dataClass")), "CLAUSE_CONTEXT_PURPOSE_MISMATCH");
        JsonNode declared = null;
        for (JsonNode item : input.document("request").at("/declaration/hypotheses")) {
            JsonNode h = item.path("hypothesis");
            if (candidate.path("candidateId").equals(h.path("candidateId")) && original.path("hypothesisId").equals(h.path("hypothesisId"))) {
                require(declared == null, "CLAUSE_DECLARED_HYPOTHESIS_AMBIGUOUS"); declared = h;
            }
        }
        require(declared != null, "CLAUSE_DECLARED_HYPOTHESIS_MISSING");
        if (declared == null) throw new IllegalArgumentException("CLAUSE_DECLARED_HYPOTHESIS_MISSING");
        String declaredMoi = PatientDiseaseContextBindingResolver.normalizeMoi(declared.path("moi").textValue());
        String contextMoi = PatientDiseaseContextBindingResolver.normalizeMoi(context.path("moi").textValue());
        require(declared.path("hgncId").equals(context.at("/gene/hgncId")) && declared.path("mondoId").equals(context.at("/disease/mondoId"))
                && declaredMoi != null && declaredMoi.equals(contextMoi), "CLAUSE_DECLARED_BACKGROUND_MISMATCH");
        ObjectNode view = publicationCandidate(input, candidate.path("candidateId").asText());
        JsonNode allele = context.at("/variant/evidenceQueryAllele"), key = view.path("queryAllele");
        require(allele.path("assembly").equals(key.path("genomeAssembly")) && allele.path("contig").equals(key.path("chromosome"))
                && allele.path("position").equals(key.path("position")) && allele.path("reference").equals(key.path("reference"))
                && allele.path("alternate").equals(key.path("alternate")), "CLAUSE_PUBLICATION_ALLELE_MISMATCH");
        JsonNode found = null;
        for (JsonNode item : input.document("run").path("bundles")) if (candidate.path("candidateId").equals(item.path("candidateId"))
                && original.path("hypothesisId").equals(item.path("hypothesisId"))) {
            require(found == null, "CLAUSE_BUSINESS_BUNDLE_DUPLICATE"); found = item.path("bundle");
        }
        if (found == null) throw new IllegalArgumentException("CLAUSE_BUSINESS_BUNDLE_MISSING");
        digest(hash, found, "bundleId", "prediction-business-artifact-sha256-");
        require("reviewed-prediction-business-artifact-v1".equals(found.path("schemaVersion").asText())
                && found.path("execution").equals(attempt.path("transcriptInput")), "CLAUSE_BUSINESS_EXECUTION_MISMATCH");
        for (String field : List.of("context", "atomicEvidence", "criterionEvaluations", "blockers", "providerCalls", "evaluatorCalls"))
            require(found.path(field).equals(attempt.path(field)), "CLAUSE_BUSINESS_PROJECTION_MISMATCH");
        for (String field : List.of("classificationEnabled", "adoptionSupported", "clinicalConclusionClaimed", "candidateOrderingChanged"))
            require(found.path(field).isBoolean() && !found.path(field).booleanValue(), "CLAUSE_BUSINESS_FLAGS_INVALID");
        require("NOT_RUN".equals(found.path("combinationStatus").asText()), "CLAUSE_BUSINESS_FLAGS_INVALID");
        JsonNode prep = attempt.at("/transcriptInput/predictionPreparation"), result = attempt.at("/transcriptInput/reviewedPrediction");
        require("reviewed-prediction-preparation-v1".equals(prep.path("schemaVersion").asText()) && "PREPARED".equals(prep.path("preparationStatus").asText())
                && "reviewed-prediction-bundle-result-v1".equals(result.path("schemaVersion").asText()), "CLAUSE_BUNDLE_SCHEMA_UNSUPPORTED");
        digest(hash, prep, "preparationId", "prediction-preparation-sha256-");
        digest(hash, result, "resultId", "prediction-bundle-result-sha256-");
        require(context.equals(prep.path("context")) && prep.path("bundleIdentity").isObject(), "CLAUSE_PREPARATION_CONTEXT_MISMATCH");
        JsonNode identity = prep.path("bundleIdentity");
        require("reviewed-prediction-bundle-binding-v1".equals(identity.path("schemaVersion").asText())
                && nonblank(identity, "bundleId", "bundleVersion")
                && identity.path("bundleSha256").asText().matches("[0-9a-f]{64}")
                && identity.path("catalogSha256").asText().matches("[0-9a-f]{64}")
                && PredictionClauseRuntime.EVALUATOR_VERSION.equals(identity.path("algorithms").path(PredictionClauseRuntime.EVALUATOR_ID).asText())
                && ReviewedPredictionBundle.ORCHESTRATOR_ID.equals(identity.at("/orchestrator/id").asText())
                && ReviewedPredictionBundle.ORCHESTRATOR_VERSION.equals(identity.at("/orchestrator/version").asText()), "CLAUSE_BUNDLE_IDENTITY_UNSUPPORTED");
        for (String field : List.of("bundleIdentity", "selectionPlans", "lookupRequests")) require(prep.path(field).equals(result.path(field)), "CLAUSE_PREPARATION_EXECUTION_MISMATCH");
        require(prep.path("savedInputs").isObject() && prep.path("savedInputs").isEmpty() && prep.path("lookups").isArray() && prep.path("lookups").isEmpty()
                && prep.path("queriedScoreModels").isArray() && prep.path("queriedScoreModels").isEmpty(), "CLAUSE_PREPARATION_ALREADY_OBSERVED");
        for (String field : List.of("providerCalls", "evaluatorCalls", "storedVepProjections"))
            require(prep.path(field).isIntegralNumber() && prep.path(field).intValue() == 0, "CLAUSE_PREPARATION_ALREADY_OBSERVED");
        require(prep.path("selectedPolicies").isObject() && prep.path("policySelections").isObject() && prep.path("criterionLookupIds").isObject()
                && prep.path("lookupRequests").isObject() && result.path("savedInputs").isObject() && result.path("lookups").isArray(), "CLAUSE_BUNDLE_CONTAINER_INVALID");
        for (String code : List.of("PP3", "BP4", "BP7")) {
            JsonNode plan = prep.path("selectionPlans").path(code); String criterion = "ACMG_AMP_2015:" + code;
            require(plan.isObject() && context.equals(plan.path("context")), "CLAUSE_PLAN_CONTEXT_MISMATCH");
            if (plan.path("policySelection").isObject()) require(identity.path("catalogSha256").equals(plan.at("/policySelection/catalogSha256")), "CLAUSE_SELECTION_CATALOG_MISMATCH");
            require(plan.path("policy").equals(prep.path("selectedPolicies").path(criterion))
                    && plan.path("policySelection").equals(prep.path("policySelections").path(criterion)), "CLAUSE_PREPARED_POLICY_MISMATCH");
        }
        Set<String> requested = new HashSet<>();
        for (String criterion : iterableNames(prep.path("criterionLookupIds"))) {
            require(Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4").contains(criterion), "CLAUSE_LOOKUP_CRITERION_UNSUPPORTED");
            String lookupKey = prep.path("criterionLookupIds").path(criterion).asText();
            require(!lookupKey.isBlank() && prep.path("lookupRequests").path(lookupKey).isObject(), "CLAUSE_LOOKUP_REFERENCE_INVALID"); requested.add(lookupKey);
        }
        require(requested.size() == prep.path("lookupRequests").size(), "CLAUSE_UNREFERENCED_LOOKUP");
        Set<String> lookups = new HashSet<>();
        for (JsonNode lookup : result.path("lookups")) {
            String id = lookup.path("lookupId").asText();
            require(!id.isBlank() && lookups.add(id) && prep.path("lookupRequests").path(id).isObject(), "CLAUSE_LOOKUP_REFERENCE_INVALID");
            if (lookup.has("observationSha256")) require(result.path("savedInputs").path(id).isObject()
                    && hash.canonicalSha256(result.path("savedInputs").path(id)).equals(lookup.path("observationSha256").asText()), "CLAUSE_OBSERVATION_DIGEST_MISMATCH");
        }
        for (String id : iterableNames(result.path("savedInputs"))) require(lookups.contains(id), "CLAUSE_UNREFERENCED_OBSERVATION");
    }

    static PredictionClauseSourceView read(ObjectMapper mapper, Input input, JsonNode candidate, String hypothesisId, String code) {
        require(input.sourceKind() == SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1 && input.issue().isEmpty(), "CLAUSE_TRUSTED_SOURCE_REQUIRED");
        require(Set.of("PP3", "BP4").contains(code), "CLAUSE_CRITERION_UNSUPPORTED");
        var hash = new RuntimeEvidenceV2Adapter(mapper);
        JsonNode row = unique(input.document("run").path("candidates"), "candidateId", candidate.path("candidateId").asText()), attempt = null;
        for (JsonNode a : row.path("attempts")) if (hypothesisId.equals(a.at("/preflight/original/hypothesisId").asText())) {
            require(attempt == null, "CLAUSE_HYPOTHESIS_AMBIGUOUS"); attempt = a;
        }
        if (attempt == null) throw new IllegalArgumentException("CLAUSE_HYPOTHESIS_NOT_SAVED");
        validateAttempt(mapper, input, candidate, attempt);
        JsonNode context = attempt.path("context"), prep = attempt.at("/transcriptInput/predictionPreparation"), bundle = attempt.at("/transcriptInput/reviewedPrediction");
        JsonNode envelope = bundle.path("clauses").path(code), result = envelope.path("result"), policy = result.path("policy"), selection = result.path("policySelection");
        require("CURRENT_CATALOG_RESULT".equals(envelope.path("clauseRouting").asText()) && "prediction-clause-result-v1".equals(result.path("schemaVersion").asText()), "CLAUSE_SOURCE_SCHEMA_UNSUPPORTED");
        String criterion = "ACMG_AMP_2015:" + code;
        require(criterion.equals(result.path("criterionId").asText()) && criterion.equals(envelope.path("criterionId").asText())
                && context.equals(result.path("context")) && policy.isObject() && policy.equals(prep.path("selectedPolicies").path(criterion))
                && selection.equals(prep.path("policySelections").path(criterion)), "CLAUSE_RESULT_POLICY_CONTEXT_MISMATCH");
        require("REVEL".equals(policy.path("preselectedModel").asText()) && "SUPPORTING".equals(policy.path("appliedStrength").asText()), "CLAUSE_SOURCE_MODEL_OR_STRENGTH_UNSUPPORTED");
        digest(hash, result, "resultId", "prediction-result-sha256-");
        digest(hash, selection, "selectionId", "cspec-selection-sha256-");
        require(hash.canonicalSha256(policy).equals(selection.path("policySha256").asText()) && policy.path("policyId").equals(selection.path("policyId"))
                && policy.path("policyVersion").equals(selection.path("policyVersion")), "CLAUSE_SELECTION_BINDING_MISMATCH");
        ObjectNode out = mapper.createObjectNode().put("sourceKind", input.sourceKind().name()).put("sourceSchemaVersion", result.path("schemaVersion").asText())
                .put("sourceRunId", input.document("run").path("runId").asText()).put("candidateId", candidate.path("candidateId").asText())
                .put("hypothesisId", hypothesisId).put("contextSha256", hash.canonicalSha256(context)).put("sourceVerified", true)
                .put("sourceClauseOutcome", "NOT_EVALUATED").put("sourceClauseStrength", "NONE").put("direction", "UNKNOWN").put("issue", "");
        out.set("sourceBytePins", input.identity().path("sourceByteSha256")); out.set("resourceBinding", policy.path("resourceBinding"));
        out.set("policy", policy); out.set("policySelection", selection);
        out.putObject("policyBinding").put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText()).put("policySha256", hash.canonicalSha256(policy));
        JsonNode annotation = annotation(input, candidate, context);
        JsonNode persisted = attempt.at("/transcriptInput/persistedAnnotation");
        TranscriptConsequence selectedTranscript = transcript(mapper, annotation);
        require(persisted.isObject() && annotation.path("source").equals(persisted.path("source"))
                && annotation.path("version").equals(persisted.path("annotationVersion"))
                && versionedAnnotationIdentity(annotation).equals(persisted.path("versionedTranscriptId").asText())
                && java.util.Objects.equals(selectedTranscript.getHgvsc(), persisted.path("hgvsc").textValue())
                && java.util.Objects.equals(selectedTranscript.getHgvsp(), persisted.path("hgvsp").textValue())
                && java.util.Objects.equals(selectedTranscript.getGeneSymbol(), persisted.path("geneSymbol").textValue())
                && java.util.Objects.equals(selectedTranscript.getConsequence(), persisted.path("consequence").textValue()), "CLAUSE_PERSISTED_ANNOTATION_MISMATCH");
        out.set("selectedAnnotation", annotation); out.put("annotationSourceSha256", hash.canonicalSha256(annotation));
        out.set("transcriptModelBinding", policy.path("transcriptModelBinding")); out.set("revelTranscriptBinding", policy.path("revelTranscriptBinding"));
        out.put("preparationId", prep.path("preparationId").asText()).put("clauseResultId", result.path("resultId").asText());
        String key = prep.path("criterionLookupIds").path(criterion).asText();
        if (key.isBlank()) return refused(out, "CLAUSE_SOURCE_NOT_EXECUTED");
        require(key.equals(envelope.path("lookupId").asText()), "CLAUSE_LOOKUP_ID_MISMATCH");
        JsonNode request = prep.path("lookupRequests").path(key); require(request instanceof ObjectNode, "CLAUSE_LOOKUP_REQUEST_MISSING");
        ObjectNode requestIdentity = ((ObjectNode) request).deepCopy(); requestIdentity.remove(List.of("lookupId", "plannedClauses", "allSelectionPlans"));
        require(("prediction-lookup-sha256-" + hash.canonicalSha256(requestIdentity)).equals(key) && key.equals(request.path("lookupId").asText())
                && context.equals(request.path("context")) && "REVEL".equals(request.path("preselectedModel").asText())
                && request.path("allSelectionPlans").equals(prep.path("selectionPlans")), "CLAUSE_LOOKUP_REQUEST_IDENTITY_MISMATCH");
        int plans = 0;
        for (JsonNode plan : request.path("plannedClauses")) if (plan.equals(prep.path("selectionPlans").path(code))) plans++;
        require(plans == 1, "CLAUSE_LOOKUP_PLAN_MISMATCH");
        for (String field : List.of("resourceBinding", "transcriptModelBinding", "revelTranscriptBinding")) require(policy.path(field).equals(request.path(field)), "CLAUSE_LOOKUP_POLICY_MISMATCH");
        JsonNode observation = bundle.path("savedInputs").path(key);
        if (!observation.isObject()) return refused(out, "CLAUSE_OBSERVATION_UNAVAILABLE");
        JsonNode lookup = unique(bundle.path("lookups"), "lookupId", key);
        require("OBSERVED".equals(lookup.path("status").asText()) && "REVEL".equals(lookup.path("preselectedModel").asText())
                && hash.canonicalSha256(observation).equals(lookup.path("observationSha256").asText()), "CLAUSE_OBSERVATION_BINDING_MISMATCH");
        require("revel-publisher-index-observation-v1".equals(observation.path("schemaVersion").asText())
                && context.equals(observation.path("context")) && request.path("transcriptModel").equals(observation.path("transcriptModel"))
                && observation.equals(result.path("observation")), "CLAUSE_OBSERVATION_SCHEMA_OR_CONTEXT_MISMATCH");
        JsonNode atomic = observation.path("evidence");
        atomic(mapper, context, atomic);
        for (String[] field : List.of(new String[]{"resourceId", "sourceId"}, new String[]{"resourceVersion", "resourceVersion"}, new String[]{"identityFingerprintSha256", "resourceSha256"}))
            require(policy.path("resourceBinding").path(field[0]).equals(atomic.path("source").path(field[1])), "CLAUSE_ATOMIC_RESOURCE_MISMATCH");
        out.set("rawRecordReferences", observation.path("rawRecords")); out.set("coordinateRecords", observation.path("coordinateRecords"));
        out.put("lookupId", key).put("observationSha256", hash.canonicalSha256(observation));
        String execution = atomic.at("/resourceExecution/status").asText(), query = atomic.at("/queryOutcome/status").asText();
        out.put("resourceExecution", execution).put("queryOutcome", query);
        if (!"SUCCEEDED".equals(execution) || !"AVAILABLE".equals(atomic.at("/resourceAvailability/status").asText())) return refused(out, "REVEL_RESOURCE_NOT_READY");
        require(hash.canonicalSha256(observation.path("rawRecords")).equals(atomic.at("/provenance/sourcePayloadSha256").asText()), "CLAUSE_RAW_PAYLOAD_MISMATCH");
        int count = rows(mapper, observation, policy, context);
        if ("NO_RECORD".equals(query)) { require(count == 0 && observation.path("rawRecords").isEmpty(), "CLAUSE_NO_RECORD_CONTRADICTION"); return refused(out, "REVEL_NO_RECORD"); }
        require("HIT".equals(query), "CLAUSE_QUERY_OUTCOME_INVALID");
        if (count != 1) return refused(out, "REVEL_EXACT_SINGLE_ROW_REQUIRED");
        require(observation.path("knownConflicts").isArray() && observation.path("knownConflicts").isEmpty(), "CLAUSE_SOURCE_CONFLICT_UNRESOLVED");
        TranscriptConsequence tx = transcript(mapper, annotation);
        ObjectNode resolved = mapper.createObjectNode();
        var inputProof = RevelPredictionObservation.read(context, tx, (ObjectNode) observation, policy, resolved, hash);
        if (inputProof.issue() != null) return refused(out, inputProof.issue());
        require(resolved.equals(result.path("resolvedInput")) && atomic.equals(result.path("evidence")), "CLAUSE_RESOLVED_INPUT_MISMATCH");
        out.set("resolvedInput", resolved);
        require("REVEL".equals(result.path("preselectedModel").asText()) && "PROTEIN_MISSENSE".equals(result.path("evidenceMechanism").asText())
                && result.path("resourceBinding").equals(policy.path("resourceBinding"))
                && result.path("providerCalls").isIntegralNumber() && result.path("providerCalls").intValue() == 0
                && result.path("evaluatorCalls").isIntegralNumber() && result.path("evaluatorCalls").intValue() == 1
                && result.path("storedVepProjections").isIntegralNumber() && result.path("storedVepProjections").intValue() == 1
                && result.path("automaticTrigger").isBoolean() && !result.path("automaticTrigger").booleanValue()
                && result.path("classificationEnabled").isBoolean() && !result.path("classificationEnabled").booleanValue(), "CLAUSE_EXECUTION_SEMANTICS_MISMATCH");
        require("EVALUATED".equals(result.path("status").asText())
                && inputProof.score() != null, "CLAUSE_SOURCE_RESULT_STATUS_INVALID");
        var score = inputProof.score();
        if (score == null) throw new IllegalArgumentException("CLAUSE_SOURCE_RESULT_STATUS_INVALID");
        require(score.stripTrailingZeros().toPlainString().equals(result.path("validatedScore").asText()), "CLAUSE_SOURCE_RESULT_STATUS_INVALID");
        atomic(mapper, context, result.path("vepEvidence"));
        // Reproduce only the existing stored annotation projection; no provider or scientific evaluation.
        try (var resource = PredictionClauseSourceView.class.getResourceAsStream("/acmg/spliceai-business-vep-identity-v1.json")) {
            if (resource == null) throw new IllegalArgumentException("CLAUSE_VEP_IDENTITY_UNAVAILABLE");
            JsonNode profile = mapper.readTree(resource);
            if (profile == null) throw new IllegalArgumentException("CLAUSE_VEP_IDENTITY_UNAVAILABLE");
            var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, hash);
            var restored = contextSource(mapper, input, candidate).candidate();
            var selected = wiring.selectedVepConsequence(context, restored);
            if (selected == null) throw new IllegalArgumentException("CLAUSE_SELECTED_ANNOTATION_MISSING");
            require(wiring.projectStoredTranscript(context, restored, selected, wiring.bind(profile, "VEP_116_GRCH38"))
                    .equals(result.path("vepEvidence")), "CLAUSE_STORED_VEP_PROJECTION_MISMATCH");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("CLAUSE_VEP_IDENTITY_UNAVAILABLE"); }
        require(attempt.path("atomicEvidence").isArray() && contains(attempt.path("atomicEvidence"), atomic)
                && contains(attempt.path("atomicEvidence"), result.path("vepEvidence")), "CLAUSE_ATOMIC_PROJECTION_MISSING");
        require(PredictionClausePolicy.issue(context, policy, selection, hash) == null, "CLAUSE_SAVED_POLICY_INVALID");
        evaluation(mapper, result);
        require(contains(attempt.path("criterionEvaluations"), result.path("evaluation")), "CLAUSE_EVALUATION_PROJECTION_MISSING");
        String status = result.at("/evaluation/evaluationResult/status").asText();
        out.put("sourceClauseOutcome", status).put("sourceClauseStrength", "MET".equals(status) ? "SUPPORTING" : "NONE")
                .put("direction", "MET".equals(status) ? ("PP3".equals(code) ? "HARMFUL" : "BENIGN") : "NOT_MET");
        return new PredictionClauseSourceView(out);
    }

    private static PredictionClauseSourceView refused(ObjectNode out, String reason) { out.put("issue", reason); return new PredictionClauseSourceView(out); }
    private static TranscriptConsequence transcript(ObjectMapper mapper, JsonNode annotation) {
        ObjectNode copy = ((ObjectNode) annotation).deepCopy();
        for (String[] pair : List.of(new String[]{"hgvsc", "hgvsCoding"}, new String[]{"hgvsp", "hgvsProtein"})) if (copy.has(pair[0])) {
            require(!copy.has(pair[1]) || copy.path(pair[0]).equals(copy.path(pair[1])), "ANNOTATION_HGVS_ALIAS_CONFLICT"); copy.set(pair[1], copy.path(pair[0])); copy.remove(pair[0]);
        }
        try { return mapper.treeToValue(copy, TranscriptConsequence.class); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("CLAUSE_ANNOTATION_FORMAT_INVALID"); }
    }
    private static int rows(ObjectMapper mapper, JsonNode observation, JsonNode policy, JsonNode context) {
        require(observation.path("coordinateRecords").isArray() && observation.path("rawRecords").isArray(), "CLAUSE_RAW_ROWS_REQUIRED");
        var matches = mapper.createArrayNode(); Set<String> ids = new HashSet<>();
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        for (JsonNode row : observation.path("coordinateRecords")) {
            String raw = row.path("rawCsv").asText(); String[] cells = raw.split(",", -1);
            require(cells.length == 9 && !raw.contains("\n") && !raw.contains("\r") && raw.length() <= 16384
                    && row.path("sourceLine").isIntegralNumber() && row.path("sourceLine").canConvertToLong() && row.path("sourceLine").longValue() > 0
                    && PredictionCalibration.sha256(raw.getBytes(StandardCharsets.US_ASCII)).equals(row.path("rawCsvSha256").asText())
                    && ("revel-member-" + policy.at("/resourceBinding/sourceArtifactSha256").asText() + "-data-row-" + row.path("sourceLine").asText()).equals(row.path("recordId").asText())
                    && ids.add(row.path("recordId").asText()), "CLAUSE_RAW_ROW_IDENTITY_INVALID");
            require(cells[0].equals(allele.path("contig").asText()) && cells[2].equals(allele.path("position").asText()), "CLAUSE_COORDINATE_ROW_MISMATCH");
            if (cells[3].equals(allele.path("reference").asText()) && cells[4].equals(allele.path("alternate").asText())
                    && List.of(cells[8].split("[;&]", -1)).contains(policy.at("/revelTranscriptBinding/accession").asText())) matches.add(row);
        }
        require(matches.equals(observation.path("rawRecords")), "CLAUSE_MATCHED_ROW_SET_MISMATCH"); return matches.size();
    }
    private static void atomic(ObjectMapper mapper, JsonNode context, JsonNode atomic) {
        require(atomic.isObject(), "CLAUSE_ATOMIC_REQUIRED"); ObjectNode carrier = mapper.createObjectNode();
        for (String field : List.of("evidenceType", "source", "resourceExecution", "resourceAvailability", "queryOutcome", "resourceApplicability", "measurements")) carrier.set(field, atomic.path(field));
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele")); carrier.set("producedAt", atomic.at("/provenance/producedAt"));
        carrier.set("sourcePayloadSha256", atomic.at("/provenance/sourcePayloadSha256"));
        require(new RuntimeEvidenceV2Adapter(mapper).adapt(context, carrier).equals(atomic), "CLAUSE_ATOMIC_IDENTITY_MISMATCH");
    }
    private static void evaluation(ObjectMapper mapper, JsonNode result) {
        var hash = new RuntimeEvidenceV2Adapter(mapper); JsonNode e = result.path("evaluation"), trace = e.path("deterministicTrace");
        String status = e.at("/evaluationResult/status").asText();
        require("criterion-evaluation-v2".equals(e.path("schemaVersion").asText()) && Set.of("MET", "NOT_MET").contains(status)
                && "SUPPORTING".equals(e.path("baseStrength").asText()) && ("MET".equals(status) ? "SUPPORTING" : "NONE").equals(e.path("appliedStrength").asText())
                && result.path("criterionId").equals(e.path("criterionId")) && result.at("/context/contextId").equals(e.path("contextId"))
                && "APPLICABLE".equals(e.at("/criterionApplicability/status").asText())
                && PredictionClauseRuntime.EVALUATOR_ID.equals(trace.path("evaluatorId").asText())
                && PredictionClauseRuntime.EVALUATOR_VERSION.equals(trace.path("evaluatorVersion").asText()) && "COMPLETED".equals(trace.path("executionStatus").asText())
                && result.at("/policy/policyId").equals(trace.path("algorithmId")) && result.at("/policy/policyVersion").equals(trace.path("algorithmVersion")), "CLAUSE_EVALUATION_BINDING_MISMATCH");
        JsonNode registryBinding = SpliceAiCriterionEvaluator.loadBp7Profile(mapper).path("registryBinding");
        require(registryBinding.path("registryId").equals(e.at("/ruleBinding/ruleRegistryId"))
                && registryBinding.path("registrySha256").equals(e.at("/ruleBinding/ruleRegistrySha256")), "CLAUSE_RULE_REGISTRY_MISMATCH");
        ObjectNode identity = ((ObjectNode) e).deepCopy(); identity.remove(List.of("evaluationId", "review", "conflict"));
        require(("evaluation-sha256-" + hash.canonicalSha256(identity)).equals(e.path("evaluationId").asText()), "CLAUSE_EVALUATION_IDENTITY_MISMATCH");
        digest(hash, trace, "traceSha256", "");
        ObjectNode snapshot = ((ObjectNode) result).deepCopy(); snapshot.remove(List.of("evaluation", "resultId"));
        require(parameter(trace, "inputSnapshotSha256").equals(hash.canonicalSha256(snapshot))
                && parameter(trace, "policySha256").equals(hash.canonicalSha256(result.path("policy")))
                && parameter(trace, "score").equals(result.path("validatedScore").asText())
                && "REVEL".equals(parameter(trace, "preselectedModel")) && "PROTEIN_MISSENSE".equals(parameter(trace, "evidenceMechanism"))
                && result.at("/policySelection/selectionId").equals(e.at("/ruleBinding/cspecSelectionId")), "CLAUSE_TRACE_INPUT_MISMATCH");
        require("varvis-json-c14n-v1".equals(e.path("canonicalizationProfile").asText())
                && "NONE".equals(e.at("/conflict/status").asText()) && e.at("/conflict/conflictIds").isArray() && e.at("/conflict/conflictIds").isEmpty()
                && "NOT_REQUIRED".equals(e.at("/review/status").asText()) && "NONE".equals(e.at("/review/decision").asText())
                && e.at("/review/reviewRecordIds").isArray() && e.at("/review/reviewRecordIds").isEmpty()
                && trace.path("parameters").size() == 5 && trace.path("steps").isArray() && trace.path("steps").size() == 1
                && "PRESELECTED_REVIEWED_MODEL_THRESHOLD".equals(trace.at("/steps/0/stepCode").asText())
                && ("MET".equals(status) ? "TRUE" : "FALSE").equals(trace.at("/steps/0/outcome").asText()), "CLAUSE_EVALUATION_STATE_MISMATCH");
        JsonNode ids = mapper.createArrayNode().add(result.at("/evidence/evidenceId").asText());
        require(ids.equals(e.path("evidenceIds")) && ids.equals(trace.path("inputEvidenceIds")) && ids.equals(trace.at("/steps/0/inputEvidenceIds")), "CLAUSE_EVALUATION_EVIDENCE_MISMATCH");
    }
    private static String parameter(JsonNode trace, String name) { return unique(trace.path("parameters"), "name", name).path("value").asText(); }
    private static boolean contains(JsonNode rows, JsonNode value) { for (JsonNode row : rows) if (row.equals(value)) return true; return false; }
    private static Iterable<String> iterableNames(JsonNode node) { return () -> node.fieldNames(); }
    private static void digest(RuntimeEvidenceV2Adapter hash, JsonNode value, String field, String prefix) {
        require(value instanceof ObjectNode, "CLAUSE_SOURCE_OBJECT_REQUIRED");
        ObjectNode copy = ((ObjectNode) value).deepCopy(); copy.remove(field);
        require((prefix + hash.canonicalSha256(copy)).equals(value.path(field).asText()), "CLAUSE_SOURCE_DIGEST_MISMATCH");
    }
}
