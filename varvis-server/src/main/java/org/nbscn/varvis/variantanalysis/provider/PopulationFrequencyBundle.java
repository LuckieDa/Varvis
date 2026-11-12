package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.lang.Nullable;

/** Immutable, explicitly configured knowledge bundle; no provider query or automatic discovery. */
public final class PopulationFrequencyBundle {
    private static final long MAXIMUM_BYTES = 2_000_000;
    private final ObjectMapper mapper;
    @Nullable private final AcmgSpecificationSelector selector;
    private final ObjectNode identity;

    private PopulationFrequencyBundle(ObjectMapper mapper, String expectedSha256) {
        this.mapper = Objects.requireNonNull(mapper);
        this.selector = null;
        this.identity = mapper.createObjectNode().put("schemaVersion", "population-frequency-bundle-binding-v1")
                .put("status", "UNAVAILABLE").put("reasonCode", "POPULATION_POLICY_CONFIGURATION_UNAVAILABLE")
                .put("expectedBundleSha256", expectedSha256.matches("[0-9a-f]{64}") ? expectedSha256 : "INVALID_OR_MISSING")
                .put("algorithmId", "gnomad-population-criterion-evaluator").put("algorithmVersion", "1.2.0-disease-policy")
                .put("automaticTrigger", false).put("classificationEnabled", false);
    }

    public static PopulationFrequencyBundle unavailable(ObjectMapper mapper, String expectedSha256) {
        return new PopulationFrequencyBundle(mapper, expectedSha256);
    }

    public static PopulationFrequencyBundle load(ObjectMapper mapper, Path path, String expectedSha256) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAXIMUM_BYTES)
            throw new IllegalArgumentException("POPULATION_POLICY_BUNDLE_SIZE_INVALID");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int) MAXIMUM_BYTES + 1);
            if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("POPULATION_POLICY_BUNDLE_SIZE_INVALID");
            JsonNode parsed = mapper.readTree(bytes);
            if (!(parsed instanceof ObjectNode document)) throw new IllegalArgumentException("POPULATION_POLICY_BUNDLE_NOT_OBJECT");
            return new PopulationFrequencyBundle(mapper, document, expectedSha256);
        }
    }

    public PopulationFrequencyBundle(ObjectMapper mapper, ObjectNode document, String expectedSha256) {
        this.mapper = Objects.requireNonNull(mapper);
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        String digest = adapter.canonicalSha256(document);
        if (!digest.equals(expectedSha256)) throw new IllegalArgumentException("POPULATION_POLICY_BUNDLE_INTEGRITY_MISMATCH");
        if (!"population-frequency-bundle-v1".equals(document.path("schemaVersion").asText())
                || document.path("bundleId").asText().isBlank() || document.path("bundleVersion").asText().isBlank()
                || !"REVIEWED_IMPLEMENTED".equals(document.at("/review/status").asText())
                || document.at("/review/reviewId").asText().isBlank()
                || !(document.path("catalog") instanceof ObjectNode catalogNode)
                || !(document.path("policies") instanceof ArrayNode policyNodes))
            throw new IllegalArgumentException("POPULATION_POLICY_BUNDLE_REVIEW_REQUIRED");
        this.selector = new AcmgSpecificationSelector(mapper, catalogNode,
                document.path("catalogSha256").asText(), policyNodes);
        this.identity = mapper.createObjectNode().put("schemaVersion", "population-frequency-bundle-binding-v1")
                .put("status", "CONFIGURED")
                .put("bundleId", document.path("bundleId").asText())
                .put("bundleVersion", document.path("bundleVersion").asText())
                .put("bundleSha256", digest).put("catalogSha256", document.path("catalogSha256").asText())
                .put("algorithmId", "gnomad-population-criterion-evaluator")
                .put("algorithmVersion", "1.2.0-disease-policy")
                .put("automaticTrigger", false).put("classificationEnabled", false);
    }

    public ObjectNode identity() { return identity.deepCopy(); }

    /** Caller supplies the existing PM2 atomic observation after the explicit context is confirmed. */
    public ObjectNode evaluate(JsonNode context, JsonNode observation, String executionSourceSha256) {
        var runtime = new PopulationFrequencyRuntime(mapper);
        var combined = mapper.createObjectNode().put("scope", "EXACT_DISEASE_FREQUENCY_POLICY")
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0)
                .put("evaluatorCalls", 0).put("classificationEnabled", false);
        combined.set("bundleIdentity", identity());
        ObjectNode routing = combined.putObject("clauseRouting");
        ArrayNode results = combined.putArray("ruleResults");
        ArrayNode evaluations = combined.putArray("criterionEvaluations");
        List<ObjectNode> overlapInputs = new ArrayList<>();
        for (String criterion : List.of(PopulationFrequencyRuntime.BA1, PopulationFrequencyRuntime.BS1)) {
            routing.put(criterion, "CURRENT_CATALOG_RESULT");
            if (selector == null) {
                ObjectNode result = mapper.createObjectNode().put("providerCalls", 0).put("evaluatorCalls", 0);
                ObjectNode evaluation = result.putArray("criterionEvaluations").addObject().put("criterionId", criterion);
                evaluation.putObject("criterionApplicability").put("status", "UNKNOWN")
                        .put("reasonCode", "POPULATION_POLICY_CONFIGURATION_UNAVAILABLE");
                evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED")
                        .put("reasonCode", "POPULATION_POLICY_CONFIGURATION_UNAVAILABLE");
                results.add(result); evaluations.add(evaluation.deepCopy());
                continue;
            }
            var selected = selector.select(context, criterion);
            // Only a valid, fully checked catalog/context can establish that this clause is outside its scope.
            // Invalid catalogs and failed searches must never silently reinstate a predecessor's positive result.
            if ("CLAUSE_SEARCH_SCOPE_NOT_COVERED".equals(selected.receipt().path("reasonCode").asText()))
                routing.put(criterion, "PREEXISTING_POLICY_OUTSIDE_CATALOG_SCOPE");
            var result = runtime.evaluate(context, observation, executionSourceSha256,
                    selected.selectedPolicy(), selected.receipt());
            results.add(result);
            for (JsonNode evaluation : result.path("criterionEvaluations")) evaluations.add(evaluation.deepCopy());
            combined.put("evaluatorCalls", combined.path("evaluatorCalls").asInt() + result.path("evaluatorCalls").asInt());
            overlapInputs.add(result);
        }
        try {
            combined.set("overlap", runtime.summarizeOverlap(overlapInputs));
        } catch (IllegalArgumentException incomplete) {
            combined.putObject("overlap").put("status", "NOT_COMBINABLE").put("populationStrengthCount", 0)
                    .put("reasonCode", "POPULATION_FREQUENCY_OVERLAP_IDENTITY_INCOMPLETE");
        }
        return combined;
    }

    public static boolean usesCurrentClause(JsonNode combined, String criterion) {
        return "CURRENT_CATALOG_RESULT".equals(combined.path("clauseRouting").path(criterion).asText());
    }

}
