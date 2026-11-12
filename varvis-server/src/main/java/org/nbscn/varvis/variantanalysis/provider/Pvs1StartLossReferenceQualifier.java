package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Per-reference isolation and allele-level reconciliation. The reviewed-source
 * adapter is called only AFTER original-byte, policy and context binding. It is
 * not a native ClinVar qualification rule and creates no scientific review. */
final class Pvs1StartLossReferenceQualifier {
    record Review(int qualifyingCount, boolean complete, ArrayNode records, Set<String> assessedSourceIds,
                  @org.springframework.lang.Nullable String reasonCode) { }
    private Pvs1StartLossReferenceQualifier() { }

    static Review reviewedSources(ObjectMapper mapper, JsonNode context, JsonNode model, int restart,
            Map<String, JsonNode> documents, Map<String, String> pointers, Set<String> sourceIds) {
        var rows = mapper.createArrayNode(); var issues = new TreeSet<String>();
        var groups = new LinkedHashMap<String, List<ObjectNode>>();
        var assessedSources = new java.util.LinkedHashSet<String>();
        boolean covered = true;
        for (String id : new TreeSet<>(sourceIds)) {
            JsonNode source = documents.get(id);
            if (source == null) { covered = false; issues.add("SOURCE_UNAVAILABLE"); continue; }
            covered &= source.path("complete").isBoolean() && source.path("complete").asBoolean()
                    && source.path("truncated").isBoolean() && !source.path("truncated").asBoolean()
                    && source.path("unresolvedCount").isIntegralNumber() && source.path("unresolvedCount").asLong(-1) == 0;
            if (!source.path("cdsStart").isIntegralNumber() || source.path("cdsStart").asLong() != 1
                    || !source.path("cdsEnd").isIntegralNumber() || source.path("cdsEnd").asLong() != restart - 1L
                    || !source.path("records").isArray() || source.path("records").size() > 10000) {
                covered = false; issues.add("UPSTREAM_REVIEW_INCOMPLETE"); continue;
            }
            assessedSources.add(id);
            int ordinal = 0;
            for (var reference : source.path("records")) {
                var row = rows.addObject().put("sourceId", id).put("sourceLocator", pointers.get(id) + "/records/" + ordinal++)
                        .put("recordId", reference.path("recordId").asText()).put("classification", reference.path("classification").asText());
                var allele = row.putObject("allele");
                for (String field : List.of("assembly", "contig", "position", "reference", "alternate"))
                    allele.set(field, reference.path("allele").path(field).deepCopy());
                String key = alleleKey(reference.path("allele"));
                String issue = assess(reference, row, context, model, restart);
                if (issue != null) { row.put("disposition", "REFERENCE_CONFLICT".equals(issue) ? "CONFLICT" : "UNRESOLVED").put("reasonCode", issue); issues.add(issue); }
                // Only records in this scientific background belong in the same
                // allele group. An unrelated disease does not create a conflict.
                if (!key.isEmpty() && !"REFERENCE_BACKGROUND_UNRESOLVED".equals(issue)
                        && !Set.of("TARGET_EXCLUDED", "WITHDRAWN_EXCLUDED", "OUTSIDE_UPSTREAM_INTERVAL").contains(row.path("disposition").asText()))
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        int count = 0;
        for (var group : groups.values()) {
            var classes = new TreeSet<String>();
            boolean conflict = false, unresolved = false;
            for (var row : group) {
                classes.add(row.path("classification").asText());
                conflict |= "CONFLICT".equals(row.path("disposition").asText());
                unresolved |= "UNRESOLVED".equals(row.path("disposition").asText());
            }
            conflict |= classes.contains("PATHOGENIC") && classes.stream().anyMatch(c ->
                    Set.of("UNCERTAIN_SIGNIFICANCE", "LIKELY_BENIGN", "BENIGN").contains(c));
            if (conflict || unresolved) {
                String reason = conflict ? "REFERENCE_CONFLICT" : "REFERENCE_STATUS_UNRESOLVED";
                issues.add(reason);
                for (var row : group) row.put("disposition", conflict ? "CONFLICT" : "UNRESOLVED").put("reasonCode", reason);
                continue;
            }
            boolean found = false;
            for (var row : group) if ("QUALIFYING".equals(row.path("disposition").asText())) {
                if (found) row.put("disposition", "DUPLICATE_EXCLUDED");
                else { count++; found = true; }
            }
        }
        boolean complete = covered && issues.isEmpty();
        String reason = null;
        if (count == 0 && !complete) reason = issues.contains("REFERENCE_CONFLICT") ? "REFERENCE_CONFLICT"
                : issues.contains("SOURCE_UNAVAILABLE") ? "SOURCE_UNAVAILABLE"
                : issues.isEmpty() ? "UPSTREAM_REVIEW_INCOMPLETE" : issues.first();
        return new Review(count, complete, rows, assessedSources, reason);
    }

    @org.springframework.lang.Nullable
    private static String assess(JsonNode reference, ObjectNode row, JsonNode context, JsonNode model, int restart) {
        if (!reference.path("hgncId").equals(context.at("/gene/hgncId"))
                || !reference.path("mondoId").equals(context.at("/disease/mondoId"))
                || !reference.path("moi").equals(context.path("moi"))) return "REFERENCE_BACKGROUND_UNRESOLVED";
        JsonNode allele = reference.path("allele"), target = context.at("/variant/evidenceQueryAllele");
        if (!allele.path("assembly").equals(target.path("assembly")) || !allele.path("contig").equals(target.path("contig"))
                || alleleKey(allele).isEmpty()) return "REFERENCE_MAPPING_UNRESOLVED";
        // The legacy reviewed-source contract is SNV-only. Unsupported records
        // remain visible; they cannot prove an empty interval.
        if (!allele.path("reference").asText().matches("[ACGT]") || !allele.path("alternate").asText().matches("[ACGT]"))
            return "REFERENCE_MAPPING_UNRESOLVED";
        int position = CodingTranscriptMapping.cdsPosition(model, allele.path("position").asInt());
        String sequence = model.path("cdsSequence").asText();
        if (position < 1 || position > sequence.length() || !sequence.substring(position - 1, position)
                .equals(CodingTranscriptMapping.orientAllele(model, allele.path("reference").asText()))) return "REFERENCE_MAPPING_UNRESOLVED";
        row.put("cdsPosition", position);
        if (position >= restart) { row.put("disposition", "OUTSIDE_UPSTREAM_INTERVAL"); return null; }
        if (alleleKey(allele).equals(alleleKey(target))) { row.put("disposition", "TARGET_EXCLUDED"); return null; }
        if (!reference.path("withdrawn").isBoolean()) return "REFERENCE_STATUS_UNRESOLVED";
        if (reference.path("withdrawn").asBoolean()) { row.put("disposition", "WITHDRAWN_EXCLUDED"); return null; }
        if (!reference.path("conflicting").isBoolean()) return "REFERENCE_STATUS_UNRESOLVED";
        if (reference.path("conflicting").asBoolean()) return "REFERENCE_CONFLICT";
        String classification = reference.path("classification").asText();
        if (!Set.of("PATHOGENIC", "LIKELY_PATHOGENIC", "UNCERTAIN_SIGNIFICANCE", "LIKELY_BENIGN", "BENIGN").contains(classification))
            return "REFERENCE_STATUS_UNRESOLVED";
        if (!"PATHOGENIC".equals(classification)) { row.put("disposition", "BASELINE_PATHOGENIC_REQUIRED"); return null; }
        if (!"INDEPENDENT_OF_START_LOSS_INFERENCE".equals(reference.path("classificationBasis").asText())
                || reference.path("recordId").asText().isBlank()) return "REFERENCE_INDEPENDENCE_UNRESOLVED";
        row.put("disposition", "QUALIFYING").put("classificationBasis", reference.path("classificationBasis").asText());
        return null;
    }

    private static String alleleKey(JsonNode allele) {
        if (!allele.path("position").isIntegralNumber() || !allele.path("position").canConvertToInt()
                || allele.path("position").asInt() < 1 || !allele.path("reference").asText().matches("[ACGT]{1,1000}")
                || !allele.path("alternate").asText().matches("[ACGT]{1,1000}")
                || allele.path("reference").equals(allele.path("alternate"))) return "";
        return allele.path("assembly").asText() + ":" + allele.path("contig").asText() + ":" + allele.path("position").asText()
                + ":" + allele.path("reference").asText() + ":" + allele.path("alternate").asText();
    }
}
