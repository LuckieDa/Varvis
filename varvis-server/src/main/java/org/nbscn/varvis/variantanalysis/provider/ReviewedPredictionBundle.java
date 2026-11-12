package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Pinned policy selection before any score lookup. No business registration or automatic activation. */
public final class ReviewedPredictionBundle {
    public static final String ORCHESTRATOR_ID = "reviewed-prediction-bundle";
    public static final String ORCHESTRATOR_VERSION = "1.0.3";
    private static final int MAXIMUM_BYTES = 8_000_000;
    private static final List<String> CRITERIA = List.of(PredictionClausePolicy.PP3, PredictionClausePolicy.BP4, PredictionClausePolicy.BP7);
    private static final String CURRENT = "CURRENT_CATALOG_RESULT";
    private static final String PREEXISTING = "PREEXISTING_POLICY_OUTSIDE_CATALOG_SCOPE";
    private static final String INTERRUPTED = "PREDICTION_BUNDLE_INTERRUPTED";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final AcmgSpecificationSelector selector;
    private final ObjectNode materials;
    private final ObjectNode identity;

    /** Trusted application callback; never a policy/request supplied executable. */
    @FunctionalInterface public interface ObservationProvider {
        @Nullable ObjectNode lookup(ObservationRequest request) throws Exception;
    }

    /** Every accessor returns a copy; callbacks cannot change another clause or the saved request. */
    public static final class ObservationRequest {
        private final ObjectNode snapshot;
        private ObservationRequest(ObjectNode snapshot) { this.snapshot = snapshot.deepCopy(); }
        public ObjectNode snapshot() { return snapshot.deepCopy(); }
        public String lookupId() { return snapshot.path("lookupId").asText(); }
        public String preselectedModel() { return snapshot.path("preselectedModel").asText(); }
    }

    /** Owner-bound immutable preparation. It contains no score lookup or stored-VEP evaluation. */
    public static final class Prepared {
        private final ReviewedPredictionBundle owner;
        private final ObjectNode bundleIdentity;
        private final ObjectNode context;
        private final ObjectNode output;
        private final Map<String, ObjectNode> policies;
        private final Map<String, ObjectNode> receipts;
        private final Map<String, String> keys;
        private final Map<String, ObjectNode> requests;
        private final boolean cancelled;

        private Prepared(ReviewedPredictionBundle owner, ObjectNode context, ObjectNode output,
                         Map<String, ObjectNode> policies, Map<String, ObjectNode> receipts,
                         Map<String, String> keys, Map<String, ObjectNode> requests, boolean cancelled) {
            this.owner = owner;
            bundleIdentity = owner.identity();
            this.context = context.deepCopy();
            this.output = output.deepCopy();
            this.policies = copyNodes(policies);
            this.receipts = copyNodes(receipts);
            this.keys = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(keys));
            this.requests = copyNodes(requests);
            // Remember a cancellation even if a later caller clears its thread flag.
            this.cancelled = cancelled || Thread.currentThread().isInterrupted();
        }

        public boolean cancelled() { return cancelled; }

        /** Audit snapshot only. Neither this JSON nor a modified copy can be executed as a Prepared object. */
        public ObjectNode snapshot() {
            ObjectNode result = output.deepCopy().put("schemaVersion", "reviewed-prediction-preparation-v1")
                    .put("preparationStatus", cancelled ? "CANCELLED" : "PREPARED")
                    .put("evaluatorCalls", 0).put("storedVepProjections", 0);
            result.set("context", context.deepCopy());
            ObjectNode selected = result.putObject("selectedPolicies"), selections = result.putObject("policySelections");
            for (String criterion : CRITERIA) {
                JsonNode plan = result.path("selectionPlans").path(code(criterion));
                if (plan.path("policy").isObject()) selected.set(criterion, plan.path("policy").deepCopy());
                if (plan.path("policySelection").isObject()) selections.set(criterion, plan.path("policySelection").deepCopy());
            }
            ObjectNode lookups = result.putObject("criterionLookupIds");
            keys.forEach(lookups::put);
            if (cancelled) {
                result.put("reasonCode", INTERRUPTED);
                for (JsonNode clause : result.path("clauses")) {
                    ObjectNode envelope = (ObjectNode) clause;
                    envelope.put("clauseRouting", CURRENT).set("result", owner.refusal((ObjectNode) envelope.path("result"), INTERRUPTED));
                }
            }
            result.put("preparationId", "prediction-preparation-sha256-" + owner.adapter.canonicalSha256(result));
            return result;
        }

        /** Prepared requests can be grouped for a caller-owned batch. Cancelled preparation exposes no executable requests. */
        public List<ObservationRequest> lookupRequests() {
            return cancelled ? List.of() : requests.values().stream().map(ObservationRequest::new).toList();
        }

        private static Map<String, ObjectNode> copyNodes(Map<String, ObjectNode> source) {
            Map<String, ObjectNode> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, value.deepCopy()));
            return java.util.Collections.unmodifiableMap(copy);
        }
    }

    public static ReviewedPredictionBundle load(ObjectMapper mapper, Path path, String expectedFileSha256,
                                                String expectedCanonicalSha256) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAXIMUM_BYTES)
            throw new IllegalArgumentException("PREDICTION_BUNDLE_SIZE_INVALID");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("PREDICTION_BUNDLE_SIZE_INVALID");
            if (!fileSha(bytes).equals(expectedFileSha256)) throw new IllegalArgumentException("PREDICTION_BUNDLE_FILE_IDENTITY_MISMATCH");
            ObjectMapper strict = mapper.copy();
            strict.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            if (!(strict.readTree(bytes) instanceof ObjectNode document)) throw new IllegalArgumentException("PREDICTION_BUNDLE_NOT_OBJECT");
            return new ReviewedPredictionBundle(mapper, document, expectedCanonicalSha256);
        }
    }

    /** The canonical hash must come from trusted configuration, never the case document itself. */
    public ReviewedPredictionBundle(ObjectMapper mapper, ObjectNode document, String expectedCanonicalSha256) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        String sha = adapter.canonicalSha256(document);
        if (!sha.equals(expectedCanonicalSha256)) throw new IllegalArgumentException("PREDICTION_BUNDLE_IDENTITY_MISMATCH");
        if (!onlyFields(document, Set.of("schemaVersion", "bundleId", "bundleVersion", "review", "activation",
                "catalog", "catalogSha256", "policies", "materials"))
                || !"reviewed-prediction-bundle-v1".equals(document.path("schemaVersion").asText())
                || !text(document.path("bundleId")) || !text(document.path("bundleVersion"))
                || !onlyFields(document.path("review"), Set.of("status", "reviewId"))
                || !"REVIEWED_IMPLEMENTED".equals(document.at("/review/status").asText())
                || !text(document.at("/review/reviewId"))
                || !onlyFields(document.path("activation"), Set.of("automaticTrigger", "classificationEnabled"))
                || !falseBoolean(document.at("/activation/automaticTrigger")) || !falseBoolean(document.at("/activation/classificationEnabled"))
                || !(document.path("catalog") instanceof ObjectNode catalog) || !(document.path("policies") instanceof ArrayNode policies)
                || !(document.path("materials") instanceof ObjectNode sourceMaterials)
                || !onlyFields(sourceMaterials, Set.of("transcriptModels"))
                || !(sourceMaterials.path("transcriptModels") instanceof ObjectNode models))
            throw new IllegalArgumentException("PREDICTION_BUNDLE_REVIEW_OR_VOCABULARY_INVALID");
        for (var entry : models.properties())
            if (!entry.getValue().isObject() || !entry.getKey().matches("[0-9a-f]{64}")
                    || !entry.getKey().equals(adapter.canonicalSha256(entry.getValue())))
                throw new IllegalArgumentException("PREDICTION_BUNDLE_MODEL_INTEGRITY_INVALID");
        selector = new AcmgSpecificationSelector(mapper, catalog, document.path("catalogSha256").asText(), policies);
        materials = sourceMaterials.deepCopy();
        identity = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-bundle-binding-v1")
                .put("bundleId", document.path("bundleId").asText()).put("bundleVersion", document.path("bundleVersion").asText())
                .put("bundleSha256", sha).put("catalogSha256", document.path("catalogSha256").asText())
                .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        identity.putObject("algorithms").put(PredictionClauseRuntime.EVALUATOR_ID, PredictionClauseRuntime.EVALUATOR_VERSION);
        identity.putObject("orchestrator").put("id", ORCHESTRATOR_ID).put("version", ORCHESTRATOR_VERSION);
    }

    public ObjectNode identity() { return identity.deepCopy(); }

    /** Compatibility entry point; preparation and execution use the same implementation as explicit batching. */
    public ObjectNode evaluate(ObjectNode context, @Nullable VariantInterpretation candidate, @Nullable ObservationProvider provider) {
        return execute(prepare(context), candidate, provider);
    }

    /** Fix all three policies and complete lookup identities before any provider or VEP evaluation. */
    public Prepared prepare(ObjectNode context) {
        return prepare(context, null);
    }

    /** Server-owned technical material may fill an absent model, but cannot change a policy's exact binding. */
    public Prepared prepare(ObjectNode context, @Nullable ObjectNode suppliedModel) {
        boolean cancelled = Thread.currentThread().isInterrupted();
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-bundle-result-v1")
                .put("providerCalls", 0).put("classificationEnabled", false).put("adoptionSupported", false);
        output.set("bundleIdentity", identity());
        ObjectNode safeContext = context.deepCopy();
        ObjectNode plans = output.putObject("selectionPlans"), clauses = output.putObject("clauses");
        ObjectNode requests = output.putObject("lookupRequests");
        output.putObject("savedInputs"); output.putArray("lookups"); output.putArray("queriedScoreModels");
        var runtime = new PredictionClauseRuntime(mapper);
        Map<String, ObjectNode> policies = new LinkedHashMap<>();
        Map<String, ObjectNode> receipts = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, ObjectNode> groupedRequests = new LinkedHashMap<>();
        for (String criterion : CRITERIA) {
            cancelled = cancelled || Thread.currentThread().isInterrupted();
            String code = code(criterion);
            var selection = selector.select(safeContext, criterion);
            ObjectNode policy = selection.selectedPolicy();
            ObjectNode receipt = selection.receipt();
            ObjectNode plan = runtime.plan(safeContext, policy, receipt);
            plans.set(code, plan.deepCopy());
            ObjectNode envelope = clauses.putObject(code).put("criterionId", criterion).put("clauseRouting",
                    "CLAUSE_SEARCH_SCOPE_NOT_COVERED".equals(receipt.path("reasonCode").asText()) ? PREEXISTING : CURRENT);
            envelope.set("result", plan.deepCopy());
            if (!"READY".equals(plan.path("status").asText()) || policy == null) continue;
            policies.put(criterion, policy.deepCopy()); receipts.put(criterion, receipt.deepCopy());
            if (PredictionClausePolicy.BP7.equals(criterion)) continue;
            ObjectNode request = request(safeContext, policy, suppliedModel);
            if (request == null) {
                envelope.set("result", refusal(plan, "PREDICTION_BUNDLE_NATIVE_MODEL_UNAVAILABLE"));
                continue;
            }
            String key = "prediction-lookup-sha256-" + adapter.canonicalSha256(request);
            keys.put(criterion, key);
            ObjectNode group = groupedRequests.get(key);
            if (group == null) {
                group = request.deepCopy().put("lookupId", key);
                group.putArray("plannedClauses"); groupedRequests.put(key, group);
            }
            ((ArrayNode) group.path("plannedClauses")).add(plan.deepCopy());
        }
        // Capture immutable lookup plans before the first callback. No policy selection occurs below.
        for (var entry : groupedRequests.entrySet()) {
            entry.getValue().set("allSelectionPlans", plans.deepCopy());
            requests.set(entry.getKey(), entry.getValue().deepCopy());
        }
        return new Prepared(this, safeContext, output, policies, receipts, keys, groupedRequests, cancelled);
    }

    /** No policy selection occurs here. Each explicit execution has independent observations, results and counts. */
    public ObjectNode execute(Prepared prepared, @Nullable VariantInterpretation candidate, @Nullable ObservationProvider provider) {
        return execute(prepared, candidate, provider, true);
    }
    public ObjectNode execute(Prepared prepared, @Nullable VariantInterpretation candidate, @Nullable ObservationProvider provider, boolean includeBp7) {
        Objects.requireNonNull(prepared, "PREDICTION_PREPARED_REQUIRED");
        if (prepared.owner != this || !identity.equals(prepared.bundleIdentity))
            throw new IllegalArgumentException("PREDICTION_PREPARED_BUNDLE_MISMATCH");
        boolean cancelled = prepared.cancelled || Thread.currentThread().isInterrupted();
        ObjectNode output = prepared.output.deepCopy(), safeContext = prepared.context.deepCopy();
        ObjectNode plans = (ObjectNode) output.path("selectionPlans"), clauses = (ObjectNode) output.path("clauses");
        ObjectNode savedInputs = (ObjectNode) output.path("savedInputs");
        ArrayNode lookups = (ArrayNode) output.path("lookups"), models = (ArrayNode) output.path("queriedScoreModels");
        Map<String, ObjectNode> policies = prepared.policies, receipts = prepared.receipts, groupedRequests = prepared.requests;
        Map<String, String> keys = prepared.keys;
        var runtime = new PredictionClauseRuntime(mapper);
        Map<String, ObjectNode> observations = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        Set<String> queriedModels = new LinkedHashSet<>();
        Set<String> completedClauses = new LinkedHashSet<>();
        for (var entry : groupedRequests.entrySet()) {
            if (cancelled || Thread.currentThread().isInterrupted()) { cancelled = true; break; }
            if (candidate == null || provider == null) {
                failures.put(entry.getKey(), candidate == null ? "PREDICTION_BUNDLE_CANDIDATE_MISSING" : "PREDICTION_BUNDLE_PROVIDER_UNAVAILABLE");
                continue;
            }
            String model = entry.getValue().path("preselectedModel").asText();
            if (queriedModels.add(model)) models.add(model);
            output.put("providerCalls", output.path("providerCalls").asInt() + 1);
            ObjectNode attempt = lookups.addObject().put("lookupId", entry.getKey()).put("preselectedModel", model);
            try {
                ObjectNode supplied = provider.lookup(new ObservationRequest(entry.getValue()));
                // A returned input remains an audit of an actual callback, even if the
                // callback also cancelled this execution. It is not accepted for evaluation.
                ObjectNode snapshot = supplied == null ? null : supplied.deepCopy();
                if (snapshot != null) {
                    attempt.put("observationSha256", adapter.canonicalSha256(snapshot));
                    savedInputs.set(entry.getKey(), snapshot.deepCopy());
                }
                if (Thread.currentThread().isInterrupted()) {
                    attempt.put("status", "INTERRUPTED").put("reasonCode", INTERRUPTED);
                    cancelled = true; break;
                }
                if (snapshot == null) {
                    failures.put(entry.getKey(), "PREDICTION_BUNDLE_OBSERVATION_MISSING");
                    attempt.put("status", "NO_OBSERVATION"); continue;
                }
                // A source callback cannot exchange the native model selected from trusted materials.
                if ("REVEL".equals(model) && !snapshot.path("transcriptModel").equals(entry.getValue().path("transcriptModel"))) {
                    failures.put(entry.getKey(), "PREDICTION_BUNDLE_OBSERVATION_MODEL_MISMATCH");
                    attempt.put("status", "INVALID"); continue;
                }
                observations.put(entry.getKey(), snapshot);
                attempt.put("status", "OBSERVED");
            } catch (Exception failed) {
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
                if (Thread.currentThread().isInterrupted()) {
                    attempt.put("status", "INTERRUPTED").put("reasonCode", INTERRUPTED);
                    cancelled = true; break;
                }
                failures.put(entry.getKey(), "PREDICTION_BUNDLE_PROVIDER_FAILED");
                attempt.put("status", "FAILED");
            }
        }
        for (String criterion : List.of(PredictionClausePolicy.PP3, PredictionClausePolicy.BP4)) {
            if (cancelled || Thread.currentThread().isInterrupted()) { cancelled = true; break; }
            String key = keys.get(criterion);
            if (key == null) continue;
            ObjectNode plan = (ObjectNode) plans.path(code(criterion));
            ObjectNode observed = observations.get(key), policy = policies.get(criterion), receipt = receipts.get(criterion);
            ObjectNode result;
            if (observed == null || policy == null || receipt == null || candidate == null)
                result = refusal(plan, failures.getOrDefault(key, "PREDICTION_BUNDLE_OBSERVATION_MISSING"));
            else result = runtime.evaluate(safeContext.deepCopy(), candidate, policy.deepCopy(), receipt.deepCopy(), observed.deepCopy(), null);
            ((ObjectNode) clauses.path(code(criterion))).put("lookupId", key).set("result", result);
            completedClauses.add(code(criterion));
        }
        ObjectNode bp7Policy = policies.get(PredictionClausePolicy.BP7), bp7Receipt = receipts.get(PredictionClausePolicy.BP7);
        cancelled = cancelled || Thread.currentThread().isInterrupted();
        if (includeBp7 && !cancelled && bp7Policy != null && bp7Receipt != null) {
            ObjectNode plan = (ObjectNode) plans.path("BP7");
            JsonNode bp4 = clauses.at("/BP4/result");
            ObjectNode result;
            if (candidate == null || !(bp4 instanceof ObjectNode currentBp4) || !"EVALUATED".equals(bp4.path("status").asText())
                    || !(bp4.path("observation") instanceof ObjectNode observation))
                result = refusal(plan, "PREDICTION_BUNDLE_CURRENT_BP4_REQUIRED");
            else result = runtime.evaluate(safeContext.deepCopy(), candidate, bp7Policy.deepCopy(), bp7Receipt.deepCopy(),
                    observation.deepCopy(), currentBp4.deepCopy());
            ((ObjectNode) clauses.path("BP7")).set("result", result);
            completedClauses.add("BP7");
        }
        cancelled = cancelled || Thread.currentThread().isInterrupted();
        output.put("executionStatus", cancelled ? "CANCELLED" : "COMPLETE");
        if (cancelled) {
            output.put("reasonCode", INTERRUPTED);
            for (String criterion : CRITERIA) {
                String code = code(criterion);
                if (completedClauses.contains(code)) continue;
                // Preserve the original selection in selectionPlans, but never hand
                // cancelled remaining work to a caller's preexisting-policy fallback.
                ((ObjectNode) clauses.path(code)).put("clauseRouting", CURRENT)
                        .set("result", refusal((ObjectNode) plans.path(code), INTERRUPTED));
            }
        }
        // A lookup callback attempt is not necessarily one physical model/Tabix
        // operation. Keep its count separate from completed clause evaluations
        // and the internal, provider-free BP4 dependency revalidation.
        ObjectNode counts = output.putObject("executionCounts")
                .put("lookupCallbackAttempts", output.path("providerCalls").asInt());
        for (String field : List.of("evaluatorCalls", "storedVepProjections", "dependencyRevalidationCalls",
                "dependencyProviderCalls", "dependencyStoredVepProjections")) {
            int total = 0;
            for (JsonNode clause : clauses) total += clause.path("result").path(field).asInt();
            counts.put("evaluatorCalls".equals(field) ? "completedClauseEvaluations" : field, total);
        }
        output.put("resultId", "prediction-bundle-result-sha256-" + adapter.canonicalSha256(output));
        return output;
    }

    @Nullable private ObjectNode request(ObjectNode context, ObjectNode policy, @Nullable ObjectNode suppliedModel) {
        ObjectNode request = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-observation-request-v1")
                .put("preselectedModel", policy.path("preselectedModel").asText());
        request.set("context", context.deepCopy()); request.set("resourceBinding", policy.path("resourceBinding").deepCopy());
        if ("REVEL".equals(policy.path("preselectedModel").asText())) {
            JsonNode binding = policy.path("transcriptModelBinding");
            JsonNode model = materials.path("transcriptModels").path(binding.path("canonicalSha256").asText());
            if (suppliedModel != null) {
                if (model.isObject() && !model.equals(suppliedModel)) return null;
                model = suppliedModel;
            }
            if (!model.isObject() || !model.path("modelId").equals(binding.path("modelId"))
                    || !model.path("modelVersion").equals(binding.path("modelVersion"))
                    || !binding.path("canonicalSha256").asText().equals(adapter.canonicalSha256(model))
                    || CodingTranscriptMapping.modelIssue(model, context) != null) return null;
            request.set("transcriptModelBinding", binding.deepCopy()); request.set("transcriptModel", model.deepCopy());
            request.set("revelTranscriptBinding", policy.path("revelTranscriptBinding").deepCopy());
        }
        return request;
    }

    private ObjectNode refusal(ObjectNode plan, String reason) {
        ObjectNode result = plan.deepCopy(); result.remove("resultId"); result.put("status", "NOT_EVALUATED");
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
        result.put("resultId", "prediction-result-sha256-" + adapter.canonicalSha256(result));
        return result;
    }

    public static boolean usesPreexistingClause(JsonNode clauseEnvelope) { return PREEXISTING.equals(clauseEnvelope.path("clauseRouting").asText()); }
    private static boolean falseBoolean(JsonNode value) { return value.isBoolean() && !value.booleanValue(); }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
    private static boolean onlyFields(JsonNode value, Set<String> allowed) {
        if (!value.isObject()) return false;
        var names = value.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }
    private static String code(String criterion) { return criterion.substring(criterion.indexOf(':') + 1); }
    private static String fileSha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
