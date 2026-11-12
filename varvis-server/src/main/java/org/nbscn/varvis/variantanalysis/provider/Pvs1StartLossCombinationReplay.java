package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Executes only the supplied original CSpec count rules. Caller must separately
 * verify original-source identity/scope, actual applied codes and observation
 * independence. This calculator never qualifies a reference or a patient. */
final class Pvs1StartLossCombinationReplay {
    private static final Pattern CODE = Pattern.compile("(?:PVS1|PS[1-4]|PM[1-6]|PP[1-5]|BA1|BS[1-4]|BP[1-7])(?:_(?:Very Strong|Strong|Moderate|Supporting|Stand Alone))?");
    private static final Pattern CONDITION = Pattern.compile("(==|>=|<=|>|<)([0-9]|[12][0-9])");
    private static final Set<String> CLASSIFICATIONS = Set.of("Pathogenic", "Likely Pathogenic", "Benign", "Likely Benign");
    private Pvs1StartLossCombinationReplay() { }

    static ObjectNode replay(ObjectMapper mapper, JsonNode sourceRules, Set<String> appliedCodes) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-source-combination-v1")
                .put("status", "UNSUPPORTED").put("scientificIndependenceEstablished", false);
        var matched = out.putArray("matchedRules");
        try {
            require(only(sourceRules, Set.of("mainRules")), "ORIGINAL_COMBINATION_RULE_SHAPE_UNSUPPORTED");
            var rules = sourceRules.path("mainRules");
            require(rules.isArray() && !rules.isEmpty() && rules.size() <= 64, "ORIGINAL_COMBINATION_RULE_SHAPE_UNSUPPORTED");
            if (!(appliedCodes != null && appliedCodes.size() <= 28)) {
                throw new IllegalArgumentException("APPLIED_CRITERIA_INVALID");
            }
            var bases = new HashSet<String>();
            for (String code : appliedCodes) require(code != null && CODE.matcher(code).matches()
                    && bases.add(code.split("_", 2)[0]), "APPLIED_CRITERIA_DUPLICATE_OR_INVALID");
            Map<String, String> partitions = new HashMap<>();
            for (var rule : rules) {
                require(only(rule, Set.of("conditions", "inference", "rule"))
                        && rule.path("rule").isTextual() && !rule.path("rule").asText().isBlank()
                        && CLASSIFICATIONS.contains(rule.path("inference").asText()),
                        "ORIGINAL_COMBINATION_RULE_SHAPE_UNSUPPORTED");
                var conditions = rule.path("conditions");
                require(conditions.isArray() && !conditions.isEmpty() && conditions.size() <= 8,
                        "ORIGINAL_COMBINATION_RULE_SHAPE_UNSUPPORTED");
                var conditionPartitions = new HashSet<String>();
                for (var condition : conditions) {
                    String partition = condition.path("partitionPath").asText();
                    require(only(condition, Set.of("applicableCriteriaCodes", "condition", "label", "partitionPath", "getpartitionPath"))
                            && (!condition.has("getpartitionPath") || condition.path("getpartitionPath").isTextual() && condition.path("getpartitionPath").asText().isEmpty())
                            && partition.matches("(?:Pathogenic|Benign)\\.(?:Very Strong|Strong|Moderate|Supporting|Stand Alone)")
                            && conditionPartitions.add(partition) && CONDITION.matcher(condition.path("condition").asText()).matches(),
                            "ORIGINAL_COMBINATION_CONDITION_UNSUPPORTED");
                    var codes = condition.path("applicableCriteriaCodes");
                    require(codes.isArray() && !codes.isEmpty() && codes.size() <= 140, "ORIGINAL_COMBINATION_CODES_INVALID");
                    var seen = new HashSet<String>();
                    for (var code : codes) {
                        String value = code.asText();
                        require(code.isTextual() && CODE.matcher(value).matches() && seen.add(value), "ORIGINAL_COMBINATION_CODES_INVALID");
                        String prior = partitions.putIfAbsent(value, partition);
                        require(prior == null || prior.equals(partition), "ORIGINAL_COMBINATION_PARTITION_CONFLICT");
                        require(value.startsWith("B") == partition.startsWith("Benign."), "ORIGINAL_COMBINATION_PARTITION_CONFLICT");
                        require(!value.contains("_") || partition.endsWith("." + value.split("_", 2)[1]), "ORIGINAL_COMBINATION_PARTITION_CONFLICT");
                    }
                }
            }
            require(partitions.keySet().containsAll(appliedCodes), "APPLIED_CRITERION_NOT_IN_ORIGINAL_RULES");
            int ordinal = 0;
            Set<String> inferences = new LinkedHashSet<>();
            for (var rule : rules) {
                boolean satisfied = true;
                var counts = mapper.createArrayNode();
                for (var condition : rule.path("conditions")) {
                    int count = 0;
                    var used = mapper.createArrayNode();
                    for (var code : condition.path("applicableCriteriaCodes")) if (appliedCodes.contains(code.asText())) {
                        count++; used.add(code.asText());
                    }
                    var expression = CONDITION.matcher(condition.path("condition").asText()); expression.matches();
                    int threshold = Integer.parseInt(expression.group(2));
                    satisfied &= switch (expression.group(1)) {
                        case "==" -> count == threshold;
                        case ">=" -> count >= threshold;
                        case "<=" -> count <= threshold;
                        case ">" -> count > threshold;
                        case "<" -> count < threshold;
                        default -> false;
                    };
                    counts.addObject().put("partition", condition.path("partitionPath").asText()).put("count", count)
                            .put("condition", expression.group()).set("appliedCodes", used);
                }
                if (satisfied) {
                    String inference = rule.path("inference").asText(); inferences.add(inference);
                    matched.addObject().put("sourceLocator", "/mainRules/" + ordinal)
                            .put("rule", rule.path("rule").asText()).put("inference", inference).set("conditions", counts);
                }
                ordinal++;
            }
            boolean pathogenic = inferences.contains("Pathogenic") || inferences.contains("Likely Pathogenic");
            boolean benign = inferences.contains("Benign") || inferences.contains("Likely Benign");
            if (pathogenic && benign) return out.put("status", "CONFLICT").put("reasonCode", "ORIGINAL_COMBINATION_CONFLICT");
            String classification = inferences.contains("Pathogenic") ? "Pathogenic" : inferences.contains("Benign") ? "Benign"
                    : inferences.contains("Likely Pathogenic") ? "Likely Pathogenic" : inferences.contains("Likely Benign") ? "Likely Benign" : "Uncertain Significance";
            out.put("sourceRulesSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(sourceRules));
            var codes = out.putArray("appliedCodes"); appliedCodes.stream().sorted().forEach(codes::add);
            return out.put("status", "REPLAYED").put("classification", classification)
                    .put("reasonCode", "ORIGINAL_COUNT_RULES_REPLAYED_NOT_REFERENCE_QUALIFICATION");
        } catch (IllegalArgumentException unsupported) {
            matched.removeAll();
            return out.put("reasonCode", unsupported.getMessage());
        }
    }
    private static boolean only(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) return false;
        for (var field : node.properties()) if (!allowed.contains(field.getKey())) return false;
        return true;
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
