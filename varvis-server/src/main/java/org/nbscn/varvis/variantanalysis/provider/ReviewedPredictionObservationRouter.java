package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Trusted source router. The selected model and resource are never replaced after a missing score. */
public final class ReviewedPredictionObservationRouter {
    public static final String VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final AlphaMissenseProvider alpha;
    private final Supplier<SpliceAiBusinessRuntime> splice;
    @Nullable private final RevelPublisherIndexObservationProvider revel;
    private final boolean alphaEnabled, spliceEnabled, revelEnabled;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding alphaBinding, spliceBinding;
    private final ObjectNode identity;

    public ReviewedPredictionObservationRouter(ObjectMapper mapper, AlphaMissenseProvider alpha, boolean alphaEnabled,
            Supplier<SpliceAiBusinessRuntime> splice, boolean spliceEnabled,
            @Nullable RevelPublisherIndexObservationProvider revel, boolean revelEnabled) {
        this.mapper = Objects.requireNonNull(mapper); this.alpha = Objects.requireNonNull(alpha);
        this.splice = Objects.requireNonNull(splice); this.revel = revel;
        this.alphaEnabled = alphaEnabled; this.spliceEnabled = spliceEnabled; this.revelEnabled = revelEnabled;
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper));
        alphaBinding = wiring.bind(AlphaMissenseCriterionEvaluator.loadProfile(mapper), "ALPHAMISSENSE_2023_08_03_GRCH38_TABIX");
        spliceBinding = wiring.bind(SpliceAiCriterionEvaluator.loadProfile(mapper), "SPLICEAI_V1_3_1_RAW_GRCH38_LOCAL_COMPUTE");
        identity = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-observation-router-v1")
                .put("version", VERSION).put("modelFallbackEnabled", false).put("automaticTrigger", false);
        identity.putObject("ALPHAMISSENSE").put("enabled", alphaEnabled).set("resourceBinding", binding(alphaBinding));
        identity.putObject("SPLICEAI").put("enabled", spliceEnabled).set("resourceBinding", binding(spliceBinding));
        var r = identity.putObject("REVEL").put("enabled", revelEnabled).put("status", !revelEnabled ? "DISABLED" : revel == null ? "UNAVAILABLE" : "CONFIGURED");
        if (revel != null) {
            r.set("resourceBinding", revel.resourceBinding());
            if (RevelLocalIndexQuery.METHOD.equals(revel.executionIdentity().path("method").asText()))
                r.set("executionIdentity", revel.executionIdentity());
        }
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    boolean enabled(String model) {
        return switch (model) { case "ALPHAMISSENSE" -> alphaEnabled; case "SPLICEAI" -> spliceEnabled; case "REVEL" -> revelEnabled; default -> false; };
    }
    private ObjectNode binding(RuntimeEvidenceV2ProviderWiring.ResourceBinding value) {
        return mapper.createObjectNode().put("resourceId", value.getResourceId()).put("resourceVersion", value.getResourceVersion())
                .put("identityFingerprintSha256", value.getIdentityFingerprintSha256());
    }

    public record Input(ReviewedPredictionBusinessRuntime.PreparedContext prepared, VariantInterpretation candidate, List<JsonNode> knownConflicts) {
        public Input { Objects.requireNonNull(prepared); Objects.requireNonNull(candidate); knownConflicts = knownConflicts.stream().map(value -> value == null
                ? com.fasterxml.jackson.databind.node.NullNode.instance : value.<JsonNode>deepCopy()).toList(); }
        @Override public List<JsonNode> knownConflicts() { return knownConflicts.stream().map(value -> value.<JsonNode>deepCopy()).toList(); }
    }
    public Session open(List<Input> inputs, AnalysisContext analysis) { return new Session(inputs, analysis); }

    public final class Session implements ReviewedPredictionBundle.ObservationProvider {
        private final Map<String, Input> contexts = new LinkedHashMap<>();
        private final List<SpliceAiBusinessRuntime.Input> spliceInputs = new ArrayList<>();
        private final AnalysisContext analysis;
        @Nullable private SpliceAiBusinessRuntime spliceRuntime;
        @Nullable private SpliceAiRawBatchRunner.BatchResult spliceBatch;
        private boolean spliceAttempted;
        private final ObjectNode audit = mapper.createObjectNode().put("schemaVersion", "prediction-source-session-v1")
                .put("sourceDelegations", 0).put("rawBatchInvocations", 0).put("callbackInvocations", 0);
        private Session(List<Input> inputs, AnalysisContext analysis) {
            this.analysis = Objects.requireNonNull(analysis);
            for (Input input : List.copyOf(inputs)) {
                ObjectNode snapshot = input.prepared().snapshot();
                JsonNode context = snapshot.path("context");
                String id = context.path("contextId").asText();
                Input prior = contexts.putIfAbsent(id, input);
                if (prior != null && (!prior.prepared().snapshot().path("context").equals(context)
                        || !prior.candidate().getCandidateId().equals(input.candidate().getCandidateId())
                        || !prior.knownConflicts().equals(input.knownConflicts())))
                    throw new IllegalArgumentException("PREDICTION_ROUTER_CONTEXT_AMBIGUOUS");
                if (!input.prepared().cancelled() && input.prepared().lookupRequests().stream().anyMatch(request -> "SPLICEAI".equals(request.preselectedModel())
                        && binding(spliceBinding).equals(request.snapshot().path("resourceBinding")))) {
                    var tx = wiring.selectedVepConsequence(context, input.candidate());
                    if (tx != null && context instanceof ObjectNode object)
                        spliceInputs.add(new SpliceAiBusinessRuntime.Input(object.deepCopy(), input.candidate(), tx, "NOT_A_LEGACY_SELECTION"));
                }
            }
        }
        void includeLegacySplice(List<SpliceAiBusinessRuntime.Input> inputs) {
            if (spliceAttempted) throw new IllegalStateException("PREDICTION_SPLICE_BATCH_ALREADY_STARTED");
            spliceInputs.addAll(inputs);
        }
        SpliceAiBusinessRuntime spliceRuntime() throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("PREDICTION_BUNDLE_INTERRUPTED");
            if (!spliceEnabled) throw new IllegalArgumentException("PREDICTION_SPLICEAI_DISABLED");
            if (!spliceAttempted) {
                spliceAttempted = true;
                var created = Objects.requireNonNull(splice.get()); spliceRuntime = created;
                increment("sourceDelegations"); increment("rawBatchInvocations");
                spliceBatch = created.compute(List.copyOf(spliceInputs));
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("PREDICTION_BUNDLE_INTERRUPTED");
            if (spliceRuntime == null || spliceBatch == null) throw new IllegalArgumentException("PREDICTION_SPLICEAI_UNAVAILABLE");
            return spliceRuntime;
        }
        SpliceAiRawBatchRunner.BatchResult spliceBatch() throws InterruptedException {
            spliceRuntime();
            return Objects.requireNonNull(spliceBatch);
        }
        public ObjectNode audit() {
            ObjectNode copy = audit.deepCopy();
            var runtime = spliceRuntime;
            var lineage = runtime == null ? null : runtime.batchLineage();
            if (lineage != null) copy.set("rawBatchLineage", lineage);
            return copy;
        }
        private void increment(String key) { audit.put(key, audit.path(key).asInt() + 1); }
        @Override public ObjectNode lookup(ReviewedPredictionBundle.ObservationRequest request) throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("PREDICTION_BUNDLE_INTERRUPTED");
            increment("callbackInvocations");
            ObjectNode requested = request.snapshot();
            JsonNode context = requested.path("context");
            Input input = contexts.get(context.path("contextId").asText());
            if (input == null || !input.prepared().snapshot().path("context").equals(context)
                    || input.prepared().lookupRequests().stream().noneMatch(planned -> planned.snapshot().equals(requested)))
                throw new IllegalArgumentException("PREDICTION_ROUTER_REQUEST_NOT_PREPARED");
            ObjectNode result = mapper.createObjectNode(); result.set("context", context.deepCopy());
            result.set("knownConflicts", mapper.valueToTree(input.knownConflicts()));
            String model = request.preselectedModel();
            if ("ALPHAMISSENSE".equals(model)) {
                if (!alphaEnabled) return unavailable(result, "PREDICTION_ALPHAMISSENSE_DISABLED");
                if (!binding(alphaBinding).equals(requested.path("resourceBinding"))) return unavailable(result, "PREDICTION_ROUTER_RESOURCE_MISMATCH");
                if (!alphaBinding.getProvider().equals(alpha.source()) || !alphaBinding.getProviderVersion().equals(alpha.version()))
                    return unavailable(result, "PREDICTION_ALPHAMISSENSE_PROVIDER_BINDING_MISMATCH");
                increment("sourceDelegations");
                var atomic = wiring.annotateVariantProvider(context, alpha, input.candidate(), analysis, alphaBinding);
                if (atomic.size() != 1) return unavailable(result, "PREDICTION_ROUTER_ATOMIC_CARDINALITY_INVALID");
                result.set("evidence", atomic.get(0));
            } else if ("SPLICEAI".equals(model)) {
                if (!spliceEnabled) return unavailable(result, "PREDICTION_SPLICEAI_DISABLED");
                if (!binding(spliceBinding).equals(requested.path("resourceBinding"))) return unavailable(result, "PREDICTION_ROUTER_RESOURCE_MISMATCH");
                var tx = wiring.selectedVepConsequence(context, input.candidate());
                if (tx == null || !(context instanceof ObjectNode object)) return unavailable(result, "PREDICTION_ROUTER_TRANSCRIPT_MISSING");
                var runtime = spliceRuntime();
                result.set("evidence", runtime.observe(new SpliceAiBusinessRuntime.Input(object, input.candidate(), tx, "NOT_A_LEGACY_SELECTION"), analysis, spliceBatch()));
                result.set("rawBatchLineage", runtime.batchLineage());
            } else if ("REVEL".equals(model)) {
                if (!revelEnabled) return unavailable(result, "PREDICTION_REVEL_DISABLED");
                var publisher = revel;
                if (publisher == null) return unavailable(result, "PREDICTION_REVEL_UNAVAILABLE");
                if (!publisher.resourceBinding().equals(requested.path("resourceBinding"))) return unavailable(result, "PREDICTION_ROUTER_RESOURCE_MISMATCH");
                increment("sourceDelegations");
                ObjectNode source = publisher.lookup(request);
                ArrayNode conflicts = (ArrayNode) result.path("knownConflicts");
                if (source.path("knownConflicts").isArray()) source.path("knownConflicts").forEach(value -> conflicts.add(value.deepCopy()));
                else return unavailable(result, "PREDICTION_SOURCE_CONFLICTS_UNDECLARED");
                source.set("knownConflicts", conflicts); result = source;
            } else return unavailable(result, "PREDICTION_MODEL_NOT_SUPPORTED");
            return result.deepCopy();
        }
        private ObjectNode unavailable(ObjectNode result, String reason) {
            result.putObject("providerDisposition").put("status", "UNAVAILABLE").put("reasonCode", reason);
            return result;
        }
    }
}
