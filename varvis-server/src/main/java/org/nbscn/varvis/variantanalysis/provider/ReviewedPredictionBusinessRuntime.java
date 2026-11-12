package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Explicit business binding. A configured but invalid resource never becomes a null legacy configuration. */
public final class ReviewedPredictionBusinessRuntime {
    /** Explicit successor entry, selected by server configuration only; no legacy bundle/BP7 dispatch. */
    public static PredictionDevelopmentRuntime development(ObjectMapper mapper, AlphaMissenseProvider provider) {
        return PredictionDevelopmentRuntime.configured(mapper, provider);
    }
    public static final String VERSION = "1.0.0";
    public static final List<String> CLAUSES = List.of("PP3", "BP4", "BP7");
    private final ObjectMapper mapper;
    @Nullable private final ReviewedPredictionBundle bundle;
    private final ObjectNode identity;
    private final ReviewedPredictionContextPreparation contexts;

    public ReviewedPredictionBusinessRuntime(ObjectMapper mapper, ReviewedPredictionBundle bundle, JsonNode routerIdentity) {
        this.mapper = Objects.requireNonNull(mapper);
        this.bundle = Objects.requireNonNull(bundle);
        identity = base(mapper).put("status", "CONFIGURED");
        identity.set("bundle", bundle.identity());
        identity.set("observationRouter", Objects.requireNonNull(routerIdentity).deepCopy());
        contexts = new ReviewedPredictionContextPreparation(mapper, identity);
    }

    private ReviewedPredictionBusinessRuntime(ObjectMapper mapper, String expectedFileSha256, String expectedCanonicalSha256) {
        this.mapper = Objects.requireNonNull(mapper); bundle = null;
        identity = base(mapper).put("status", "UNAVAILABLE").put("reasonCode", "PREDICTION_BUSINESS_CONFIGURATION_UNAVAILABLE")
                .put("expectedFileSha256", pin(expectedFileSha256)).put("expectedCanonicalSha256", pin(expectedCanonicalSha256));
        contexts = new ReviewedPredictionContextPreparation(mapper, identity);
    }

    public static ReviewedPredictionBusinessRuntime unavailable(ObjectMapper mapper, String fileSha256, String canonicalSha256) {
        return new ReviewedPredictionBusinessRuntime(mapper, fileSha256, canonicalSha256);
    }
    private static String pin(String value) { return value != null && value.matches("[0-9a-f]{64}") ? value : "MISSING_OR_INVALID"; }
    private static ObjectNode base(ObjectMapper mapper) {
        return mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-business-binding-v1").put("version", VERSION)
                .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    public ReviewedPredictionContextPreparation contexts() { return contexts; }

    public final class PreparedContext {
        @Nullable private final ReviewedPredictionBundle.Prepared prepared;
        private final ObjectNode snapshot;
        private PreparedContext(@Nullable ReviewedPredictionBundle.Prepared prepared, ObjectNode snapshot) {
            this.prepared = prepared; this.snapshot = snapshot.deepCopy();
        }
        public ObjectNode snapshot() { return snapshot.deepCopy(); }
        public boolean fullyOutsideCatalog() {
            return !cancelled() && CLAUSES.stream().allMatch(code -> ReviewedPredictionBundle.usesPreexistingClause(snapshot.at("/clauses/" + code)));
        }
        public boolean cancelled() { return "CANCELLED".equals(snapshot.path("preparationStatus").asText()); }
        public List<ReviewedPredictionBundle.ObservationRequest> lookupRequests() { return prepared == null ? List.of() : prepared.lookupRequests(); }
        private ReviewedPredictionBusinessRuntime owner() { return ReviewedPredictionBusinessRuntime.this; }
    }

    public PreparedContext prepare(ObjectNode context) {
        return prepare(context, null);
    }

    public PreparedContext prepare(ObjectNode context, @Nullable ObjectNode suppliedModel) {
        var bundle = this.bundle;
        if (bundle != null) {
            var prepared = suppliedModel == null ? bundle.prepare(context) : bundle.prepare(context, suppliedModel);
            return new PreparedContext(prepared, prepared.snapshot());
        }
        return new PreparedContext(null, unavailableResult(context, true));
    }

    public ObjectNode execute(PreparedContext prepared, @Nullable VariantInterpretation candidate,
                              @Nullable ReviewedPredictionBundle.ObservationProvider provider) {
        return execute(prepared, candidate, provider, true);
    }
    public ObjectNode execute(PreparedContext prepared, @Nullable VariantInterpretation candidate,
                              @Nullable ReviewedPredictionBundle.ObservationProvider provider, boolean includeBp7) {
        if (prepared.owner() != this) throw new IllegalArgumentException("PREDICTION_BUSINESS_PREPARATION_MISMATCH");
        if (bundle != null && prepared.prepared != null) return bundle.execute(prepared.prepared, candidate, provider, includeBp7);
        if (prepared.cancelled()) return refuse(prepared, "PREDICTION_BUNDLE_INTERRUPTED");
        JsonNode context = prepared.snapshot.path("context");
        return unavailableResult(context, false);
    }

    ObjectNode refuse(PreparedContext prepared, String reason) {
        if (prepared.owner() != this) throw new IllegalArgumentException("PREDICTION_BUSINESS_PREPARATION_MISMATCH");
        ObjectNode output = prepared.snapshot();
        output.remove("preparationId");
        output.put("schemaVersion", "reviewed-prediction-business-refusal-v1").put("reasonCode", reason)
                .put("executionStatus", "PREDICTION_BUNDLE_INTERRUPTED".equals(reason) ? "CANCELLED" : "UNAVAILABLE")
                .put("providerCalls", 0).put("classificationEnabled", false).put("adoptionSupported", false);
        for (String code : CLAUSES) {
            ObjectNode clause = (ObjectNode) output.path("clauses").path(code);
            clause.put("clauseRouting", "CURRENT_CATALOG_RESULT");
            ObjectNode result = ((ObjectNode) clause.path("result")).deepCopy();
            result.remove(List.of("resultId", "evaluation"));
            result.put("status", "NOT_EVALUATED").put("reasonCode", reason).put("providerCalls", 0).put("evaluatorCalls", 0);
            result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
            result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
            result.put("resultId", "prediction-result-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(result));
            clause.set("result", result);
        }
        output.putObject("savedInputs"); output.putArray("lookups"); output.putArray("queriedScoreModels");
        output.putObject("executionCounts").put("lookupCallbackAttempts", 0).put("completedClauseEvaluations", 0);
        output.put("resultId", "prediction-business-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(output));
        return output;
    }

    public static String refusalReason(JsonNode result) {
        String nested = result.at("/evaluationResult/reasonCode").asText();
        return nested.isBlank() ? result.path("reasonCode").asText("PREDICTION_CLAUSE_UNAVAILABLE") : nested;
    }

    private ObjectNode unavailableResult(JsonNode context, boolean preparation) {
        boolean cancelled = Thread.currentThread().isInterrupted();
        String reason = cancelled ? "PREDICTION_BUNDLE_INTERRUPTED" : identity.path("reasonCode").asText();
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", preparation ? "reviewed-prediction-preparation-v1" : "reviewed-prediction-bundle-result-v1")
                .put("providerCalls", 0).put("executionStatus", cancelled ? "CANCELLED" : "UNAVAILABLE")
                .put("preparationStatus", cancelled ? "CANCELLED" : "UNAVAILABLE").put("reasonCode", reason)
                .put("classificationEnabled", false).put("adoptionSupported", false);
        output.set("context", context.deepCopy()); output.set("businessIdentity", identity());
        ObjectNode plans = output.putObject("selectionPlans"), clauses = output.putObject("clauses");
        for (String code : CLAUSES) {
            ObjectNode refusal = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:" + code)
                    .put("status", "NOT_EVALUATED").put("reasonCode", reason).put("providerCalls", 0).put("evaluatorCalls", 0);
            refusal.set("context", context.deepCopy());
            refusal.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
            refusal.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
            refusal.put("resultId", "prediction-result-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(refusal));
            plans.set(code, refusal.deepCopy());
            clauses.putObject(code).put("criterionId", "ACMG_AMP_2015:" + code).put("clauseRouting", "CURRENT_CATALOG_RESULT").set("result", refusal);
        }
        output.putObject("lookupRequests"); output.putObject("savedInputs"); output.putArray("lookups"); output.putArray("queriedScoreModels");
        output.putObject("executionCounts").put("lookupCallbackAttempts", 0).put("completedClauseEvaluations", 0);
        output.put(preparation ? "preparationId" : "resultId", "prediction-business-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(output));
        return output;
    }
}
