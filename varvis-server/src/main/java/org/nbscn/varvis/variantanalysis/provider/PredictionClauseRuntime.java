package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Explicit clause execution from a reviewed, preselected model. No provider calls or automatic activation. */
public final class PredictionClauseRuntime {
    /** Successor calibration avoids the legacy Supporting cap and does not load any disabled BP7 runtime. */
    public static ObjectNode calibrate(ObjectMapper mapper, JsonNode calibration, String rawScore, boolean syntheticGrid) {
        return PredictionCalibration.evaluate(mapper, calibration, rawScore, syntheticGrid);
    }
    /** No call from production or association-only wiring. Requires separately pinned local evidence. */
    static ObjectNode calibrateStandard(ObjectMapper mapper, JsonNode calibration,
            PredictionResourceCompatibility.StandardBinding compatibility, PredictionLocalResourceBinding local,
            JsonNode resource, JsonNode input, String rawScore) {
        String issue = PredictionCalibration.standardIssue(calibration);
        if (issue == null) issue = compatibility.issue(mapper, calibration, local, resource, input);
        ObjectNode out = issue == null ? PredictionCalibration.evaluateStandard(mapper, calibration, rawScore)
                : PredictionCalibration.refused(PredictionCalibration.standardResult(mapper, calibration, rawScore), issue);
        out.put("compatibilityEvidenceSha256", compatibility.sha256());
        out.put("calibrationCanonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(calibration));
        return out;
    }
    /** Real offline successor: score and scope can only originate from verified saved exports. */
    public static ObjectNode calibrateStandard(ObjectMapper mapper, SavedPredictionCalibration.VerifiedInput input) {
        return input.compare(mapper);
    }
    public static final String EVALUATOR_ID = "reviewed-prediction-clause-evaluator";
    public static final String EVALUATOR_VERSION = "1.0.4";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding vepBinding;
    private final ObjectNode registry;
    private final ObjectNode registryBinding;

    public PredictionClauseRuntime(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        registry = SpliceAiCriterionEvaluator.loadBp7RuleRegistry(mapper);
        ObjectNode positionProfile = SpliceAiCriterionEvaluator.loadBp7Profile(mapper);
        registryBinding = ((ObjectNode) positionProfile.path("registryBinding")).deepCopy();
        try (var input = getClass().getResourceAsStream("/acmg/spliceai-business-vep-identity-v1.json")) {
            if (input == null) throw new IllegalArgumentException("PREDICTION_VEP_RESOURCE_MISSING");
            JsonNode profile = mapper.readTree(input);
            if (profile == null) throw new IllegalArgumentException("PREDICTION_VEP_RESOURCE_INVALID");
            vepBinding = wiring.bind(profile, "VEP_116_GRCH38");
            if (!vepBinding.getIdentityFingerprintSha256().equals(positionProfile.at("/bp7PositionRules/resourceBinding/identityFingerprintSha256").asText()))
                throw new IllegalArgumentException("PREDICTION_VEP_RESOURCE_MISMATCH");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PREDICTION_VEP_RESOURCE_UNAVAILABLE", invalid); }
    }

    /** May run before any score lookup. A refused clause does not propose another predictor. */
    public ObjectNode plan(JsonNode context, JsonNode policy, JsonNode selection) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "prediction-clause-result-v1")
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("classificationEnabled", false).put("automaticTrigger", false);
        try {
            if (context != null) adapter.canonicalSha256(context);
            if (policy != null) adapter.canonicalSha256(policy);
            if (selection != null) adapter.canonicalSha256(selection);
        } catch (RuntimeException invalid) { return refusal(result, "PREDICTION_NONCANONICAL_POLICY_INPUT"); }
        if (context != null) result.set("context", context.deepCopy());
        if (policy != null) result.set("policy", policy.deepCopy());
        if (selection != null) result.set("policySelection", selection.deepCopy());
        String issue = PredictionClausePolicy.issue(context, policy, selection, adapter);
        String criterion = selection == null ? null : PredictionClausePolicy.singleCriterion(selection.path("criterionIds"));
        if (criterion != null) result.put("criterionId", criterion);
        if (issue != null) return refusal(result, issue);
        if (policy == null) return refusal(result, "PREDICTION_REVIEWED_CLAUSE_POLICY_REQUIRED");
        result.put("status", "READY").put("preselectedModel", policy.path("preselectedModel").asText())
                .put("evidenceMechanism", policy.path("evidenceMechanism").asText());
        result.set("resourceBinding", policy.path("resourceBinding").deepCopy());
        return result;
    }

    /** Observation contains one previously obtained atomic evidence record, never a list of alternative model scores.
     * BP7 receives the full saved result of its separately selected BP4 clause as its final argument.
     */
    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode policy,
                               ObjectNode selection, ObjectNode observation, ObjectNode bp4Dependency) {
        ObjectNode result = plan(context, policy, selection);
        if (!"READY".equals(result.path("status").asText())) return result;
        context = (ObjectNode) result.path("context");
        policy = (ObjectNode) result.path("policy");
        selection = (ObjectNode) result.path("policySelection");
        ObjectNode snapshot = observation == null ? mapper.createObjectNode() : observation.deepCopy();
        try { adapter.canonicalSha256(snapshot); }
        catch (RuntimeException invalid) { return refusal(result, "PREDICTION_NONCANONICAL_OBSERVATION"); }
        result.set("observation", snapshot);
        TranscriptConsequence tx;
        ObjectNode position;
        try {
            tx = wiring.selectedVepConsequence(context, candidate);
            if (tx == null) return refusal(result, "PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            position = wiring.projectStoredTranscript(context, candidate, tx, vepBinding);
        } catch (RuntimeException invalid) { return refusal(result, "PREDICTION_VEP_CONTEXT_IDENTITY_INVALID"); }
        result.set("vepEvidence", position);
        result.put("storedVepProjections", 1);
        if (policy.has("consequenceScope")) {
            String issue = PredictionConsequenceScope.issue(policy, tx, position, result.putObject("consequenceScopeValidation"));
            if (issue != null) return refusal(result, issue);
        }
        String model = result.path("preselectedModel").asText();
        String criterion = result.path("criterionId").asText();
        ObjectNode evidence;
        PredictionModelInput input;
        try {
            if (!(snapshot.path("evidence") instanceof ObjectNode atomic)) return refusal(result, "PREDICTION_OBSERVATION_MISSING");
            String issue = atomicIssue(context, atomic, policy.path("resourceBinding"));
            if (issue != null) return refusal(result, issue);
            if (!snapshot.path("context").equals(context)) return refusal(result, "PREDICTION_OBSERVATION_CONTEXT_MISMATCH");
            if (!snapshot.path("knownConflicts").isArray()) return refusal(result, "PREDICTION_OBSERVED_CONFLICTS_UNDECLARED");
            if (!snapshot.path("knownConflicts").isEmpty()) return refusal(result, "PREDICTION_OBSERVED_DISCORDANCE_REQUIRES_REVIEW");
            evidence = atomic.deepCopy();
            result.set("evidence", evidence);
            if ("ALPHAMISSENSE".equals(model)) {
                if (!proteinConsequence(tx)) return refusal(result, "PREDICTION_PROTEIN_MECHANISM_NOT_APPLICABLE");
                input = new AlphaMissenseCriterionEvaluator(mapper).inputForPolicy(context, evidence);
                if (input.issue() == null) {
                    boolean proteinMatched = false;
                    for (JsonNode metric : evidence.path("measurements"))
                        if ("alphamissense.proteinVariant".equals(metric.path("metricId").asText()))
                            proteinMatched = Objects.equals(tx.getAminoAcidChange(), metric.path("rawValue").asText());
                    if (!proteinMatched) return refusal(result, "PREDICTION_ALPHAMISSENSE_STORED_PROTEIN_MISMATCH");
                }
            } else if ("SPLICEAI".equals(model)) {
                input = new SpliceAiCriterionEvaluator(mapper).inputForPolicy(context, evidence, tx.getConsequence());
            } else {
                input = RevelPredictionObservation.read(context, tx, snapshot, policy, result.putObject("resolvedInput"), adapter);
            }
        } catch (RuntimeException invalid) { return refusal(result, "PREDICTION_OBSERVATION_INVALID"); }
        if (input.issue() != null) return refusal(result, input.issue());
        BigDecimal score = input.score();
        if (score == null) return refusal(result, "PREDICTION_SCORE_MISSING");
        String status;
        if (PredictionClausePolicy.BP7.equals(criterion)) {
            String issue;
            try { issue = dependencyIssue(context, candidate, policy, evidence, position, score, bp4Dependency, result); }
            catch (RuntimeException invalid) { return refusal(result, "PREDICTION_BP4_DEPENDENCY_INVALID"); }
            if (issue != null) return refusal(result, issue);
            result.set("bp4Dependency", bp4Dependency.deepCopy());
            ObjectNode bp4 = (ObjectNode) bp4Dependency.path("evaluation");
            ObjectNode positionDecision = new SpliceAiCriterionEvaluator(mapper).evaluateBusinessBp7(context, evidence, position,
                    bp4, selection.path("selectionId").asText());
            result.set("positionAndDependencyValidation", positionDecision);
            status = positionDecision.at("/evaluationResult/status").asText();
            if ("NOT_EVALUATED".equals(status)) return refusal(result, positionDecision.at("/evaluationResult/reasonCode").asText());
            result.put("sharedSpliceEvidence", true).put("independentEvidenceCount", 1);
        } else {
            status = PredictionClausePolicy.met(score, policy.path("threshold")) ? "MET" : "NOT_MET";
        }
        result.put("validatedScore", score.stripTrailingZeros().toPlainString()).put("evaluatorCalls", 1).put("status", "EVALUATED");
        result.set("evaluation", evaluation(result, status));
        return seal(result);
    }

    private String atomicIssue(JsonNode context, ObjectNode atomic, JsonNode resource) {
        if (!context.path("contextId").equals(atomic.path("contextId"))) return "PREDICTION_ATOMIC_CONTEXT_MISMATCH";
        if (!resource.path("resourceId").equals(atomic.at("/source/sourceId"))
                || !resource.path("resourceVersion").equals(atomic.at("/source/resourceVersion"))
                || !resource.path("identityFingerprintSha256").equals(atomic.at("/source/resourceSha256")))
            return "PREDICTION_ATOMIC_RESOURCE_MISMATCH";
        if (!"NONE".equals(atomic.at("/conflict/status").asText())
                || !"NOT_REQUIRED".equals(atomic.at("/review/status").asText())) return "PREDICTION_ATOMIC_REVIEW_REQUIRED";
        if (!"COMPUTATIONAL_PREDICTION".equals(atomic.path("evidenceType").asText())) return "PREDICTION_ATOMIC_TYPE_INVALID";
        ObjectNode carrier = mapper.createObjectNode();
        for (String field : List.of("evidenceType", "source", "resourceExecution", "resourceAvailability", "queryOutcome", "resourceApplicability", "measurements"))
            carrier.set(field, atomic.path(field).deepCopy());
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        carrier.put("producedAt", atomic.at("/provenance/producedAt").asText());
        carrier.put("sourcePayloadSha256", atomic.at("/provenance/sourcePayloadSha256").asText());
        return adapter.adapt(context, carrier).equals(atomic) ? null : "PREDICTION_ATOMIC_IDENTITY_INVALID";
    }

    private String dependencyIssue(ObjectNode context, VariantInterpretation candidate, JsonNode policy, JsonNode evidence,
                                   ObjectNode currentVep, BigDecimal score, ObjectNode dependency, ObjectNode result) {
        result.put("dependencyRevalidationCalls", 0).put("dependencyProviderCalls", 0).put("dependencyStoredVepProjections", 0);
        if (dependency == null) return "PREDICTION_SAME_SOURCE_BP4_REQUIRED";
        ObjectNode copy = dependency.deepCopy(); copy.remove("resultId");
        if (!("prediction-result-sha256-" + adapter.canonicalSha256(copy)).equals(dependency.path("resultId").asText())
                || !"EVALUATED".equals(dependency.path("status").asText())
                || !PredictionClausePolicy.BP4.equals(dependency.path("criterionId").asText())
                || !"SPLICEAI".equals(dependency.path("preselectedModel").asText())
                || !"SPLICING".equals(dependency.path("evidenceMechanism").asText())
                || !context.equals(dependency.path("context")) || !evidence.equals(dependency.path("evidence")))
            return "PREDICTION_SAME_SOURCE_BP4_BINDING_MISMATCH";
        JsonNode dependencyPolicy = dependency.path("policy");
        JsonNode dependencySelection = dependency.path("policySelection");
        JsonNode binding = policy.path("bp7Dependency");
        if (!(dependencyPolicy instanceof ObjectNode bp4Policy) || !(dependencySelection instanceof ObjectNode bp4Selection)
                || !PredictionClausePolicy.BP4.equals(PredictionClausePolicy.singleCriterion(dependencyPolicy.path("criterionIds")))
                || !PredictionClausePolicy.BP4.equals(PredictionClausePolicy.singleCriterion(dependencySelection.path("criterionIds")))
                || !"SPLICEAI".equals(dependencyPolicy.path("preselectedModel").asText())
                || !"SPLICING".equals(dependencyPolicy.path("evidenceMechanism").asText())
                || !binding.path("policyId").equals(dependencyPolicy.path("policyId"))
                || !binding.path("policyVersion").equals(dependencyPolicy.path("policyVersion"))
                || !binding.path("policySha256").asText().equals(adapter.canonicalSha256(dependencyPolicy))
                || PredictionClausePolicy.issue(context, dependencyPolicy, dependencySelection, adapter) != null)
            return "PREDICTION_BP4_POLICY_BINDING_MISMATCH";
        JsonNode evaluation = dependency.path("evaluation");
        String status = evaluation.at("/evaluationResult/status").asText();
        if (!Set.of("MET", "NOT_MET").contains(status)
                || !PredictionClausePolicy.BP4.equals(evaluation.path("criterionId").asText())
                || !EVALUATOR_ID.equals(evaluation.at("/deterministicTrace/evaluatorId").asText())
                || !EVALUATOR_VERSION.equals(evaluation.at("/deterministicTrace/evaluatorVersion").asText()))
            return "PREDICTION_BP4_NOT_EVALUATED";
        BigDecimal savedScore = PredictionClausePolicy.decimal(dependency.path("validatedScore"));
        if (savedScore == null || savedScore.compareTo(score) != 0
                || !(PredictionClausePolicy.met(score, dependencyPolicy.path("threshold")) ? "MET" : "NOT_MET").equals(status))
            return "PREDICTION_BP4_SAVED_CONCLUSION_INVALID";
        if (!(dependency.path("observation") instanceof ObjectNode savedObservation)
                || !context.equals(savedObservation.path("context")) || !evidence.equals(savedObservation.path("evidence"))
                || !currentVep.equals(dependency.path("vepEvidence"))) return "PREDICTION_BP4_SAVED_INPUT_BINDING_MISMATCH";
        if (!savedObservation.path("knownConflicts").isArray() || !savedObservation.path("knownConflicts").isEmpty())
            return "PREDICTION_BP4_SAVED_DISCORDANCE_REQUIRES_REVIEW";
        // Hashes are integrity identities, not scientific authorization. Re-run the selected BP4 clause
        // using the same stored VEP candidate and its saved observation; this performs no model/provider call.
        // The explicit BP4 policy/receipt guards above prevent recursive BP7 dependency evaluation.
        result.put("dependencyRevalidationCalls", 1);
        ObjectNode verified = evaluate(context, candidate, bp4Policy, bp4Selection, savedObservation, null);
        result.put("dependencyProviderCalls", verified.path("providerCalls").asInt())
                .put("dependencyStoredVepProjections", verified.path("storedVepProjections").asInt());
        if (!"EVALUATED".equals(verified.path("status").asText())) return "PREDICTION_BP4_SAVED_REVALIDATION_FAILED";
        if (!verified.equals(dependency)) return "PREDICTION_BP4_SAVED_EVALUATION_IDENTITY_INVALID";
        return null;
    }

    private ObjectNode evaluation(ObjectNode result, String status) {
        JsonNode context = result.path("context"), policy = result.path("policy"), selection = result.path("policySelection");
        String criterion = result.path("criterionId").asText();
        ObjectNode evaluation = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", criterion).put("baseStrength", "SUPPORTING").put("appliedStrength", "MET".equals(status) ? "SUPPORTING" : "NONE");
        ObjectNode binding = evaluation.putObject("ruleBinding").put("ruleRegistryId", registryBinding.path("registryId").asText())
                .put("ruleRegistrySha256", registryBinding.path("registrySha256").asText())
                .put("currentGeneralDesignation", criterion.substring(criterion.indexOf(':') + 1))
                .put("cspecSelectionId", selection.path("selectionId").asText());
        for (JsonNode rule : registry.path("criteria")) if (criterion.equals(rule.path("criterionId").asText()))
            binding.set("authoritySourceIds", rule.at("/currentGeneral/authoritySourceIds").deepCopy());
        evaluation.putObject("criterionApplicability").put("status", "APPLICABLE");
        evaluation.putObject("evaluationResult").put("status", status);
        var ids = evaluation.putArray("evidenceIds").add(result.at("/evidence/evidenceId").asText());
        if (PredictionClausePolicy.BP7.equals(criterion)) ids.add(result.at("/vepEvidence/evidenceId").asText());
        ObjectNode trace = evaluation.putObject("deterministicTrace").put("executionStatus", "COMPLETED")
                .put("evaluatorId", EVALUATOR_ID).put("evaluatorVersion", EVALUATOR_VERSION)
                .put("algorithmId", policy.path("policyId").asText()).put("algorithmVersion", policy.path("policyVersion").asText());
        trace.set("inputEvidenceIds", ids.deepCopy());
        var parameters = trace.putArray("parameters");
        parameters.addObject().put("name", "preselectedModel").put("value", result.path("preselectedModel").asText());
        parameters.addObject().put("name", "evidenceMechanism").put("value", result.path("evidenceMechanism").asText());
        parameters.addObject().put("name", "policySha256").put("value", adapter.canonicalSha256(policy));
        parameters.addObject().put("name", "inputSnapshotSha256").put("value", adapter.canonicalSha256(result));
        parameters.addObject().put("name", "score").put("value", result.path("validatedScore").asText());
        trace.putArray("steps").addObject().put("stepCode", PredictionClausePolicy.BP7.equals(criterion)
                ? "REVIEWED_POSITION_SAME_SOURCE_BP4_DEPENDENCY" : "PRESELECTED_REVIEWED_MODEL_THRESHOLD")
                .put("outcome", "MET".equals(status) ? "TRUE" : "FALSE").set("inputEvidenceIds", ids.deepCopy());
        trace.put("traceSha256", adapter.canonicalSha256(trace));
        evaluation.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        evaluation.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        ObjectNode identity = evaluation.deepCopy(); identity.remove(List.of("conflict", "review"));
        evaluation.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity));
        return evaluation;
    }

    private ObjectNode refusal(ObjectNode result, String reason) {
        result.put("status", "NOT_EVALUATED");
        result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        return seal(result);
    }
    private ObjectNode seal(ObjectNode result) {
        result.remove("resultId"); result.put("resultId", "prediction-result-sha256-" + adapter.canonicalSha256(result)); return result;
    }
    private static boolean proteinConsequence(TranscriptConsequence tx) {
        Set<String> terms = new HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")));
        return terms.contains("missense_variant") && terms.stream().noneMatch(t -> t.contains("splice") || t.contains("stop")
                || Set.of("intron_variant", "start_lost", "frameshift_variant").contains(t));
    }
}
