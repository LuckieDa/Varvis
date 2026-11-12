package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Disease-frequency orchestration using the existing population parser, adapter and evaluator. */
public final class Hnf1aPopulationRuntime {
    public static final String BS1 = "ACMG_AMP_2015:BS1";
    public static final String BA1 = "ACMG_AMP_2015:BA1";
    private final ObjectMapper mapper;
    private final GnomadPopulationInputProfile profile;

    public Hnf1aPopulationRuntime(ObjectMapper mapper) {
        this.mapper = mapper;
        this.profile = GnomadPopulationInputProfile.loadHnf1aFrequency();
    }

    public ObjectNode evaluate(JsonNode context, JsonNode observation, String executionSourceSha256) {
        if (context == null || !context.isObject()) return disposition("HNF1A_FREQUENCY_DECLARATION_REQUIRED");
        String issue = GnomadPopulationCriterionEvaluator.hnf1aContextIssue(context);
        if (issue != null) return disposition(issue);
        if (!observation.path("evidence").isObject()) {
            var unavailable = disposition(observation.at("/evaluationResult/reasonCode").asText("HNF1A_POPULATION_OBSERVATION_INVALID"));
            unavailable.set("populationAvailability", observation.path("evaluationResult").deepCopy());
            return unavailable;
        }
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var result = base();
        try {
            var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
            var parsed = wiring.reparsePopulationObservation(context, observation, executionSourceSha256,
                    GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile);
            var evidence = adapter.adapt(context, parsed.carrier());
            result.set("context", context.deepCopy());
            result.set("observationIdentity", parsed.identity());
            result.set("evidence", evidence);
            result.put("executionSourceSha256", executionSourceSha256);
            var policy = profile.getDocument().path("diseaseFrequencyPolicy");
            var selection = mapper.createObjectNode().put("schemaVersion", "hnf1a-frequency-policy-selection-v1")
                    .put("contextId", context.path("contextId").asText()).put("profileSha256", profile.getProfileSha256())
                    .put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText())
                    .put("policySha256", adapter.canonicalSha256(policy)).put("outcome", "SINGLE_EXACT_MATCH");
            selection.set("registryBinding", profile.getDocument().path("registryBinding"));
            selection.putArray("criterionIds").add(BA1).add(BS1);
            selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(selection));
            result.set("policySelection", selection);
            var evaluations = new GnomadPopulationCriterionEvaluator(mapper).evaluate(context, evidence, profile,
                    profile.getSuccessorRegistryDocument(), selection.path("selectionId").asText());
            for (ObjectNode evaluation : evaluations) result.withArray("criterionEvaluations").add(evaluation);
            result.put("evaluatorCalls", evaluations.size());
            boolean ba1Met = "MET".equals(evaluations.get(0).at("/evaluationResult/status").asText());
            result.put("preferredPopulationCriterion", ba1Met ? BA1 : BS1);
            result.put("populationStrengthCount", evaluations.stream().anyMatch(e -> "MET".equals(e.at("/evaluationResult/status").asText())) ? 1 : 0);
            return result;
        } catch (IllegalArgumentException invalid) {
            return disposition("HNF1A_POPULATION_OBSERVATION_INVALID");
        }
    }

    private ObjectNode base() {
        var result = mapper.createObjectNode().put("scope", "HNF1A_MONOGENIC_DIABETES_AD")
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("profileSha256", profile.getProfileSha256())
                .put("providerCalls", 0).put("evaluatorCalls", 0).put("classificationEnabled", false)
                .put("overlapPolicy", "DISEASE_BA1_PRECEDENCE_NO_ADDITIVE_POPULATION_STRENGTH");
        result.set("policy", profile.getDocument().path("diseaseFrequencyPolicy"));
        result.putArray("criterionEvaluations");
        return result;
    }

    public ObjectNode disposition(String reason) {
        var result = base();
        for (String id : List.of(BA1, BS1)) {
            var value = result.withArray("criterionEvaluations").addObject().put("criterionId", id);
            value.putObject("criterionApplicability").put("status", reason.endsWith("NOT_APPLICABLE") ? "NOT_APPLICABLE" : "UNKNOWN").put("reasonCode", reason);
            value.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        }
        return result;
    }
}
