package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact reviewed bindings or rechecked expert-submission links. No fuzzy names or inferred disease equivalence. */
public final class MissenseConditionMapping {
    public static final String VERSION = "1.0.0";
    private static final Pattern MONDO = Pattern.compile("MONDO:(?:MONDO:)?([0-9]{7})");
    private MissenseConditionMapping() { }

    static String issue(JsonNode mappings, JsonNode policy) {
        if (!mappings.isArray() || mappings.isEmpty() || mappings.size() > 256) return "REFERENCE_CONDITION_MAPPING_INVALID";
        var seen = new HashMap<String, String>();
        for (var row : mappings) {
            if (!MissenseComparisonPolicy.only(row, Set.of("schemaVersion", "status", "relation", "reviewId", "sourceCondition", "mondoId", "sourceIds"))
                    || !"reviewed-clinvar-condition-mapping-v1".equals(row.path("schemaVersion").asText())
                    || !Set.of("REVIEWED_EXACT", "SOURCE_VERIFIED_EXACT").contains(row.path("status").asText()) || !"EXACT_DISEASE".equals(row.path("relation").asText())
                    || !MissenseComparisonPolicy.text(row.path("reviewId")) || !row.path("mondoId").asText().matches("MONDO:[0-9]{7}")
                    || !MissenseComparisonPolicy.boundSources(policy, row.path("sourceIds"))) return "REFERENCE_CONDITION_MAPPING_INVALID";
            var condition = row.path("sourceCondition");
            if (!MissenseComparisonPolicy.only(condition, Set.of("submittedRaw", "reportedRaw")) || condition.size() != 2) return "REFERENCE_CONDITION_MAPPING_INVALID";
            if (condition.path("submittedRaw").asText().isBlank() && condition.path("reportedRaw").asText().isBlank()) return "REFERENCE_CONDITION_MAPPING_INVALID";
            for (String name : List.of("submittedRaw", "reportedRaw")) {
                var value = condition.path(name);
                if (!value.isTextual() || value.asText().length() > 4096) return "REFERENCE_CONDITION_MAPPING_INVALID";
                var ids = MONDO.matcher(value.asText());
                while (ids.find()) if (!("MONDO:" + ids.group(1)).equals(row.path("mondoId").asText())) return "REFERENCE_CONDITION_MAPPING_EXPLICIT_MONDO_CONFLICT";
            }
            String key = condition.path("submittedRaw").asText() + "\u0000" + condition.path("reportedRaw").asText();
            if (condition.path("submittedRaw").asText().contains("\u0000") || condition.path("reportedRaw").asText().contains("\u0000")
                    || seen.putIfAbsent(key, row.path("mondoId").asText()) != null) return "REFERENCE_CONDITION_MAPPING_AMBIGUOUS";
        }
        return null;
    }

    /** Returns only an exact source binding; an absent row is not evidence of a different disease. */
    static String resolve(String submitted, String reported, JsonNode mappings) {
        for (var row : mappings) if (submitted.equals(row.at("/sourceCondition/submittedRaw").asText())
                && reported.equals(row.at("/sourceCondition/reportedRaw").asText())) return row.path("mondoId").asText();
        return "";
    }
}
