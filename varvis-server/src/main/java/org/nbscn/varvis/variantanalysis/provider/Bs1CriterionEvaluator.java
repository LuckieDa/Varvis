package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Pure BS1 tier evaluation: no resources, BA1 exception table, or PM2 policy dependency. */
final class Bs1CriterionEvaluator {
    static final String VERSION = "1.0.0-bs1-policy-v2";
    private Bs1CriterionEvaluator() { }
    static ObjectNode evaluate(ObjectMapper mapper, JsonNode context, ObjectNode evidence, JsonNode policy,
            JsonNode selection, JsonNode quality) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var result = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2")
                .put("contextId", context.path("contextId").asText()).put("canonicalizationProfile", "varvis-json-c14n-v1")
                .put("criterionId", Bs1Policy.CRITERION).put("baseStrength", "STRONG");
        var registry = registry(mapper);
        result.putObject("ruleBinding").put("ruleRegistryId", registry.path("registryId").asText())
                .put("ruleRegistrySha256", adapter.canonicalSha256(registry)).put("currentGeneralDesignation", "BS1")
                .put("cspecSelectionId", selection.path("selectionId").asText()).set("authoritySourceIds", registry.path("authoritySourceIds"));
        result.putArray("evidenceIds").add(evidence.path("evidenceId").asText());
        String version = Bs1SexChromosomePolicy.isStatistic(policy) ? Bs1SexChromosomeStatistic.version(policy)
                : Bs1FrequencyStatistic.isAf(policy) ? Bs1FrequencyStatistic.VERSION : VERSION;
        var trace = result.putObject("deterministicTrace").put("evaluatorId", "varvis-bs1-policy-evaluator").put("evaluatorVersion", version);
        trace.putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        var parameters = trace.putArray("parameters");
        for (var item : Map.of("policySha256", adapter.canonicalSha256(policy), "qualitySha256", adapter.canonicalSha256(quality),
                "exceptionSetSha256", adapter.canonicalSha256(policy.path("exceptionSet")), "selectedMode", selection.path("selectedMode").asText()).entrySet())
            parameters.addObject().put("name", item.getKey()).put("value", item.getValue());
        // Stable ordering is part of the deterministic identity.
        var ordered = new ArrayList<JsonNode>(); parameters.forEach(ordered::add);
        ordered.sort(Comparator.comparing(p -> p.path("name").asText())); parameters.removeAll(); ordered.forEach(parameters::add);
        var steps = trace.putArray("steps");
        var facts = new HashMap<String, BigDecimal>();
        for (String key : List.of((Bs1FrequencyStatistic.isAf(policy) || Bs1SexChromosomePolicy.isAf(policy)) ? "AF" : "FAF95", "AN", "AC")) {
            BigDecimal value = Bs1Quality.number(quality.path("facts").path(key).asText());
            if (value != null) facts.put(key, value);
        }
        String status = "NOT_EVALUATED", strength = "NONE", reason = null;
        if (!"READY".equals(quality.path("status").asText())) reason = quality.path("issues").path(0).path("reasonCode").asText("BS1_QUALITY_NOT_READY");
        if (reason == null) {
            int commonIndex = 0;
            for (var condition : policy.path("commonConditions")) {
                String value = Bs1SexChromosomeStatistic.condition(condition, facts);
                steps.addObject().put("stepCode", "BS1_COMMON_CONDITION_" + ++commonIndex).put("outcome", value).putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
                if (!"TRUE".equals(value)) { reason = "UNKNOWN".equals(value) ? "BS1_COMMON_INPUT_MISSING" : "BS1_COMMON_CONDITION_NOT_SATISFIED"; break; }
            }
        }
        if (reason == null) {
            var tiers = new ArrayList<JsonNode>();
            for (var tier : policy.path("tiers")) if (tier.path("moi").equals(selection.path("selectedMode"))) tiers.add(tier);
            tiers.sort(Comparator.comparingInt(t -> t.path("priority").asInt()));
            if (tiers.isEmpty()) reason = "BS1_NO_APPLICABLE_TIER";
            else status = "NOT_MET";
            int tierIndex = 0;
            for (var tier : tiers) {
                tierIndex++; int conditionIndex = 0;
                boolean unknown = false, satisfied = true, insufficientAn = false;
                for (var condition : tier.path("conditions")) {
                    String value = Bs1SexChromosomeStatistic.condition(condition, facts);
                    // Insufficient sample support is an unevaluable tier, not a negative frequency result.
                    if ("AN".equals(condition.path("fact").asText()) && "FALSE".equals(value)) { value = "UNKNOWN"; insufficientAn = true; }
                    unknown |= "UNKNOWN".equals(value); satisfied &= "TRUE".equals(value);
                    steps.addObject().put("stepCode", "BS1_TIER_" + tierIndex + "_CONDITION_" + ++conditionIndex).put("outcome", value)
                            .putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
                    parameters.addObject().put("name", "tier." + tier.path("tierId").asText() + "." + condition.path("fact").asText() + "." + condition.path("operator").asText())
                            .put("value", condition.path("decimal").asText()).put("unit", condition.path("unit").asText());
                }
                if (unknown) { status = "NOT_EVALUATED"; reason = insufficientAn ? "BS1_TIER_AN_REQUIREMENT_NOT_SATISFIED" : "BS1_TIER_INPUT_UNKNOWN"; break; }
                if (satisfied) { status = "MET"; strength = tier.path("strength").asText(); break; }
            }
        }
        if (reason != null) status = "NOT_EVALUATED";
        boolean complete = !"NOT_EVALUATED".equals(status);
        result.putObject("criterionApplicability").put("status", "APPLICABLE");
        var evaluation = result.putObject("evaluationResult").put("status", status);
        if (!complete) evaluation.put("reasonCode", reason);
        result.put("appliedStrength", strength);
        trace.put("executionStatus", complete ? "COMPLETED" : "NOT_RUN");
        if (steps.isEmpty()) steps.addObject().put("stepCode", "BS1_QUALITY_GATE").put("outcome", "UNKNOWN").putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        if (complete) {
            trace.put("algorithmId", "BS1_POLICY_TIERS").put("algorithmVersion", version);
            trace.put("traceSha256", adapter.canonicalSha256(trace));
        } else trace.put("reasonCode", reason);
        result.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        result.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        var identity = result.deepCopy(); identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity));
        return result;
    }
    static ObjectNode registry(ObjectMapper mapper) {
        try (var in = Bs1CriterionEvaluator.class.getResourceAsStream("/acmg/bs1-core-registry-v1.json")) {
            if (in == null) throw new java.io.IOException("missing registry");
            return (ObjectNode) mapper.readTree(in);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("BS1_REGISTRY_UNAVAILABLE", invalid); }
    }
}
