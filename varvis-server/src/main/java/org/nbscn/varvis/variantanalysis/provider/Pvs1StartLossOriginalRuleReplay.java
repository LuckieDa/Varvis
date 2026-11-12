package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Pairs official summary applied codes with the same original interpretation and
 * its exact loaded guideline. Does not read defaultStrength as applied strength. */
final class Pvs1StartLossOriginalRuleReplay {
    private Pvs1StartLossOriginalRuleReplay() { }
    static ObjectNode replay(ObjectMapper mapper, JsonNode summary, JsonNode original,
            PredictionSpecializationGuard.ReferenceSpecification specification) {
        var out = mapper.createObjectNode().put("status", "UNRESOLVED").put("independenceEstablished", false);
        try {
            require(specification.matchesMethod(original.path("assertionMethod")), "ORIGINAL_GUIDELINE_BINDING_MISMATCH");
            require("VariantPathogenicityInterpretation".equals(original.path("type").asText())
                    && "VariantPathogenicityInterpretationGuideline".equals(original.at("/assertionMethod/type").asText())
                    && summary.path("uuid").isTextual() && !summary.path("uuid").asText().isBlank()
                    && summary.path("uuid").equals(original.path("uuid"))
                    && summary.path("mondoId").equals(original.at("/condition/id"))
                    && ("CAR:" + summary.path("caId").asText()).equals(original.at("/variant/id").asText())
                    && summary.path("classification").equals(original.at("/statementOutcome/label")), "ORIGINAL_INTERPRETATION_UNAVAILABLE_OR_MISMATCH");
            require(summary.path("metCodes").isArray() && !summary.path("metCodes").isEmpty()
                    && summary.path("metCodes").size() <= 28, "SOURCE_APPLIED_CODES_UNAVAILABLE");
            Map<String, String> applied = new LinkedHashMap<>();
            for (var node : summary.path("metCodes")) {
                String code = node.asText(), base = code.split("_", 2)[0];
                require(node.isTextual() && code.matches("(?:PVS1|PS[1-4]|PM[1-6]|PP[1-5]|BA1|BS[1-4]|BP[1-7])(?:_(?:Very Strong|Strong|Moderate|Supporting|Stand Alone))?")
                        && applied.putIfAbsent(base, code) == null, "SOURCE_APPLIED_CODES_CONFLICT");
            }
            Set<String> met = new LinkedHashSet<>();
            for (var line : original.path("evidenceLine")) for (var item : line.path("evidenceItem")) {
                require("CriterionAssessment".equals(item.path("type").asText()), "ORIGINAL_CRITERIA_UNRESOLVED");
                String base = item.at("/criterion/label").asText();
                if (!"Met".equals(item.at("/statementOutcome/label").asText())) {
                    require(!applied.containsKey(base), "SOURCE_APPLIED_CODES_CONFLICT"); continue;
                }
                require(met.add(base) && applied.containsKey(base), "SOURCE_APPLIED_CODES_CONFLICT");
            }
            require(met.equals(applied.keySet()), "SOURCE_APPLIED_CODES_CONFLICT");
            JsonNode entity = specification.entity();
            var rulesets = entity.at("/ld/RuleSet");
            require(rulesets.isArray() && rulesets.size() == 1, "ORIGINAL_RULESET_SELECTION_UNRESOLVED");
            var rules = rulesets.get(0).at("/entContent/rules");
            var replay = Pvs1StartLossCombinationReplay.replay(mapper, rules, new LinkedHashSet<>(applied.values()));
            out.set("combination", replay);
            out.set("specificationSource", specification.source());
            out.put("rulesLocator", "/ld/RuleSet/0/entContent/rules").put("appliedCodesLocator", "/metCodes");
            require("REPLAYED".equals(replay.path("status").asText()), replay.path("reasonCode").asText());
            require(summary.path("classification").equals(replay.path("classification")), "ORIGINAL_CLASSIFICATION_REPLAY_MISMATCH");
            // Derive strength from the guideline partition actually used for the
            // exact applied code; a bare code is not assigned its generic default.
            Map<String, String> partitions = new LinkedHashMap<>();
            for (var rule : rules.path("mainRules")) for (var condition : rule.path("conditions"))
                for (var code : condition.path("applicableCriteriaCodes")) partitions.put(code.asText(), condition.path("partitionPath").asText());
            var facts = out.putArray("actualAppliedCriteria");
            int lineIndex = 0;
            for (var line : original.path("evidenceLine")) {
                int itemIndex = 0;
                for (var item : line.path("evidenceItem")) {
                    if ("Met".equals(item.at("/statementOutcome/label").asText())) {
                        String base = item.at("/criterion/label").asText(), appliedCode = applied.get(base);
                        String partition = partitions.get(appliedCode);
                        if (partition == null) {
                            throw new IllegalArgumentException("APPLIED_CRITERION_NOT_IN_ORIGINAL_RULES");
                        }
                        String strength = partition.replace('.', ' '), explicit = line.at("/evidenceStrength/label").asText();
                        require(explicit.isBlank() || line.path("evidenceItem").size() == 1 && explicit.equals(strength), "SOURCE_APPLIED_STRENGTH_CONFLICT");
                        facts.addObject().put("code", base).put("appliedCode", appliedCode).put("sourceAppliedStrength", strength)
                                .put("strengthBasis", "EXPLICIT_MET_CODE_AND_BOUND_ORIGINAL_RULE_PARTITION")
                                .put("sourceLocator", "/evidenceLine/" + lineIndex + "/evidenceItem/" + itemIndex);
                    }
                    itemIndex++;
                }
                lineIndex++;
            }
            return out.put("status", "ORIGINAL_CLASSIFICATION_REPLAYED")
                    .put("reasonCode", "ORIGINAL_CLASSIFICATION_REPLAYED_INDEPENDENCE_NOT_ESTABLISHED");
        } catch (IllegalArgumentException unresolved) {
            out.remove("actualAppliedCriteria");
            return out.put("reasonCode", unresolved.getMessage());
        }
    }
    static void attach(ObjectNode assessment, ObjectNode replay) {
        assessment.set("originalRuleReplay", replay);
        if (!"ORIGINAL_CLASSIFICATION_REPLAYED".equals(replay.path("status").asText())) return;
        for (var criterion : assessment.path("criteria")) for (var applied : replay.path("actualAppliedCriteria")) {
            if (!criterion.path("sourceLocator").equals(applied.path("sourceLocator"))) continue;
            var current = (ObjectNode) criterion;
            current.put("appliedStrengthStatus", "EXPLICIT_SUMMARY_AND_BOUND_RULE");
            current.set("sourceAppliedStrength", applied.path("sourceAppliedStrength"));
            current.set("sourceAppliedCode", applied.path("appliedCode"));
            removeReasons(current.path("qualificationBlockers"), Set.of("ACTUAL_APPLIED_STRENGTH_UNRESOLVED"));
        }
        if (assessment.path("sourceEvidenceAudit") instanceof ObjectNode audit) {
            audit.put("combinationReplayed", true);
            removeReasons(audit.path("unresolvedReasons"), Set.of("ACTUAL_APPLIED_STRENGTH_UNRESOLVED",
                    "ORIGINAL_COMBINATION_RULE_UNSPECIFIED", "ORIGINAL_COMBINATION_RULE_NOT_IMPLEMENTED"));
        }
    }
    private static void removeReasons(JsonNode node, Set<String> removed) {
        if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array)
            for (int i = array.size() - 1; i >= 0; i--) if (removed.contains(array.get(i).asText())) array.remove(i);
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
