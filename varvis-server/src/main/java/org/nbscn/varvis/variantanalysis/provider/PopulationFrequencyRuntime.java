package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit disease-frequency execution after the versioned specification selector.
 * This class does not select, discover or enable policies, and never queries a provider.
 */
public final class PopulationFrequencyRuntime {
    public ObjectNode evaluateBs1(JsonNode context, JsonNode observation, String sourceSnapshot, JsonNode selection, JsonNode nativeFacts) {
        PopulationObservation.verify(mapper, observation, context, sourceSnapshot);
        var policy = selection.path("policy");
        var expected = AcmgSpecificationSelector.selectBs1(mapper, List.of(policy), context);
        if (!expected.equals(selection) || !"READY".equals(selection.path("status").asText())) throw new IllegalArgumentException("BS1_SELECTION_INVALID");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var quality = Bs1Quality.assess(mapper, observation, policy, nativeFacts);
        var evidence = adapter.adaptVersioned(context, (ObjectNode) observation.path("carrier"));
        var result = mapper.createObjectNode().put("schemaVersion", "bs1-rule-result-v1").put("criterionId", BS1);
        result.set("context", context); result.set("preparation", selection); result.set("observation", observation);
        result.set("qualityDecision", quality); result.set("evidence", evidence);
        result.set("evaluation", new GnomadPopulationCriterionEvaluator(mapper).evaluateBs1Policy(context, evidence, policy, selection, quality));
        return result;
    }
    public static final String BS1 = "ACMG_AMP_2015:BS1";
    public static final String BA1 = "ACMG_AMP_2015:BA1";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final GnomadPopulationInputProfile inputProfile;

    public PopulationFrequencyRuntime(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.inputProfile = GnomadPopulationInputProfile.loadExecutableV2();
    }

    /** A single rule is selected independently so a missing BA1 policy cannot disable a valid BS1 policy.
     * The caller must obtain policy and receipt from a pinned, reviewed specification catalog;
     * self-declared JSON is not an independent scientific review or an activation permission.
     */
    public ObjectNode evaluate(JsonNode context, JsonNode observation, String executionSourceSha256,
                               JsonNode policy, JsonNode selectionReceipt) {
        var result = base();
        if (selectionReceipt == null || !selectionReceipt.isObject())
            return disposition(result, null, "POPULATION_FREQUENCY_SELECTION_REQUIRED");
        result.set("policySelection", selectionReceipt.deepCopy());
        JsonNode ids = selectionReceipt.path("criterionIds");
        String criterion = ids.isArray() && ids.size() == 1 ? ids.path(0).asText() : null;
        if (!Set.of(BA1, BS1).contains(criterion == null ? "" : criterion))
            return disposition(result, null, "POPULATION_FREQUENCY_SELECTION_CRITERION_INVALID");
        String issue = selectionIssue(context, policy, selectionReceipt);
        if (issue != null) return disposition(result, criterion, issue);
        issue = PopulationFrequencyPolicy.issue(policy, criterion);
        if (issue == null) issue = PopulationFrequencyPolicy.contextIssue(policy, context);
        if (issue != null) return disposition(result, criterion, issue);
        result.set("policy", policy.deepCopy());
        if (observation == null || !observation.path("evidence").isObject()) {
            if (observation != null) result.set("populationAvailability", observation.path("evaluationResult").deepCopy());
            return disposition(result, criterion, observation == null ? "POPULATION_FREQUENCY_OBSERVATION_MISSING"
                    : observation.at("/evaluationResult/reasonCode").asText("POPULATION_FREQUENCY_OBSERVATION_INVALID"));
        }
        try {
            var parsed = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).reparsePopulationObservation(
                    context, observation, executionSourceSha256, GnomadPopulationInputProfile.loadBusinessPm2Supporting(), inputProfile);
            var evidence = adapter.adapt(context, parsed.carrier());
            result.set("context", context.deepCopy());
            result.set("observationIdentity", parsed.identity());
            result.set("evidence", evidence);
            result.put("executionSourceSha256", executionSourceSha256);
            if ("HIT".equals(evidence.at("/queryOutcome/status").asText())) {
                // The original parser intentionally omits both absent INFO and explicit '.' values.
                // This successor requires proof of field presence from the already-verified raw record.
                var completeness = result.putObject("observedInputCompleteness");
                Set<String> present = new java.util.HashSet<>();
                Set<String> declared = new java.util.HashSet<>();
                String[] columns = observation.path("rawPopulationRecord").asText().split("\t", -1);
                for (String field : columns[7].split(";")) {
                    String[] pair = field.split("=", 2);
                    present.add(pair[0]);
                    if (pair.length == 2 && !pair[1].isBlank()) declared.add(pair[0]);
                }
                boolean exomesPresent = present.contains("exomes_filters");
                boolean genomesPresent = present.contains("genomes_filters");
                completeness.put("exomesFilterInfoPresent", exomesPresent).put("genomesFilterInfoPresent", genomesPresent);
                if (!exomesPresent || !genomesPresent)
                    return disposition(result, criterion, "POPULATION_FREQUENCY_SUBTYPE_FILTER_PRESENCE_REQUIRED");
                if (!declared.contains("exomes_filters") || !declared.contains("genomes_filters"))
                    return disposition(result, criterion, "POPULATION_FREQUENCY_SUBTYPE_FILTER_VALUE_REQUIRED");
            }
            var evaluation = new GnomadPopulationCriterionEvaluator(mapper).evaluatePopulationPolicy(context, evidence,
                    policy, criterion, selectionReceipt.path("selectionId").asText());
            result.withArray("criterionEvaluations").add(evaluation);
            result.put("evaluatorCalls", 1);
            return result;
        } catch (IllegalArgumentException invalid) {
            return disposition(result, criterion, "POPULATION_FREQUENCY_OBSERVATION_INVALID");
        }
    }

    private String selectionIssue(JsonNode context, JsonNode policy, JsonNode receipt) {
        if (context == null || !context.isObject() || !context.path("contextId").equals(receipt.path("contextId")))
            return "POPULATION_FREQUENCY_SELECTION_CONTEXT_MISMATCH";
        ObjectNode projection = ((ObjectNode) receipt).deepCopy();
        projection.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(projection)).equals(receipt.path("selectionId").asText())
                || receipt.path("catalogId").asText().isBlank() || receipt.path("catalogVersion").asText().isBlank()
                || !receipt.path("catalogSha256").asText().matches("[0-9a-f]{64}"))
            return "POPULATION_FREQUENCY_SELECTION_IDENTITY_INVALID";
        if (!Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText()))
            return receipt.path("reasonCode").asText("POPULATION_FREQUENCY_POLICY_SELECTION_INCOMPLETE");
        if (policy == null || !policy.isObject()
                || !receipt.path("policyId").equals(policy.path("policyId"))
                || !receipt.path("policyVersion").equals(policy.path("policyVersion"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText()))
            return "POPULATION_FREQUENCY_SELECTION_POLICY_MISMATCH";
        return null;
    }

    private ObjectNode base() {
        var result = mapper.createObjectNode().put("scope", "EXACT_DISEASE_FREQUENCY_POLICY")
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("profileSha256", inputProfile.getProfileSha256())
                .put("providerCalls", 0).put("evaluatorCalls", 0).put("classificationEnabled", false)
                .put("overlapPolicy", "DISEASE_BA1_PRECEDENCE_NO_ADDITIVE_POPULATION_STRENGTH");
        result.putArray("criterionEvaluations");
        return result;
    }

    private ObjectNode disposition(ObjectNode result, String criterion, String reason) {
        var value = result.withArray("criterionEvaluations").addObject();
        if (criterion != null) value.put("criterionId", criterion);
        value.putObject("criterionApplicability").put("status", reason.endsWith("NOT_APPLICABLE") ? "NOT_APPLICABLE" : "UNKNOWN")
                .put("reasonCode", reason);
        value.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        return result;
    }

    /** Only combine results for the same complete context and observed allele/resource identity. */
    public ObjectNode summarizeOverlap(List<ObjectNode> results) {
        String contextId = null;
        JsonNode observation = null;
        Set<String> evaluated = new java.util.HashSet<>();
        Set<String> met = new java.util.HashSet<>();
        for (ObjectNode result : results) {
            String current = result.at("/policySelection/contextId").asText();
            if (current.isBlank() || contextId != null && !contextId.equals(current)
                    || result.has("context") && !current.equals(result.at("/context/contextId").asText()))
                throw new IllegalArgumentException("Population overlap context mismatch");
            contextId = current;
            JsonNode identity = result.path("observationIdentity");
            if (result.has("observationIdentity")) {
                if (!identity.isObject() || observation != null && !observation.equals(identity))
                    throw new IllegalArgumentException("Population overlap observation mismatch");
                observation = identity;
            }
            for (JsonNode evaluation : result.path("criterionEvaluations")) {
                String id = evaluation.path("criterionId").asText();
                if (!Set.of(BA1, BS1).contains(id) || !evaluated.add(id))
                    throw new IllegalArgumentException("Population overlap requires unique BA1/BS1 evaluations");
                if (evaluation.has("contextId") && !current.equals(evaluation.path("contextId").asText()))
                    throw new IllegalArgumentException("Population overlap evaluation context mismatch");
                String status = evaluation.at("/evaluationResult/status").asText();
                if (Set.of("MET", "NOT_MET").contains(status)
                        && (!identity.isObject() || !result.has("context")))
                    throw new IllegalArgumentException("Population overlap completed evaluation binding missing");
                if ("MET".equals(status)) met.add(id);
            }
        }
        var summary = mapper.createObjectNode().put("populationStrengthCount", met.isEmpty() ? 0 : 1)
                .put("overlapPolicy", "DISEASE_BA1_PRECEDENCE_NO_ADDITIVE_POPULATION_STRENGTH");
        if (!met.isEmpty()) summary.put("preferredPopulationCriterion", met.contains(BA1) ? BA1 : BS1);
        return summary;
    }
}
