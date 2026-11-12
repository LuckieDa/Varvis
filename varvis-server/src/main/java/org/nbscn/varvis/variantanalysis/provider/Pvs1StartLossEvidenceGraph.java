package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the original SEPIO evidence graph inside one acquired document.
 * A resolved graph is provenance, not scientific verification of its assertions.
 * References never trigger network requests or import facts from another record. */
final class Pvs1StartLossEvidenceGraph {
    private static final int MAX_NODES = 10000, MAX_DEPTH = 32, MAX_OBSERVATIONS = 1000;
    private final ObjectMapper mapper;
    private final JsonNode original;
    private final Map<String, List<String>> definitions = new HashMap<>();
    private final Set<String> indexIssues = new LinkedHashSet<>();
    private int nodes;
    private int traversals;

    private Pvs1StartLossEvidenceGraph(ObjectMapper mapper, JsonNode original) {
        this.mapper = mapper; this.original = original;
        index(original, "", 0);
    }

    static ObjectNode audit(ObjectMapper mapper, JsonNode original, ArrayNode criteria) {
        var graph = new Pvs1StartLossEvidenceGraph(mapper, original);
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-evidence-graph-v1")
                .put("scientificIndependenceEstablished", false).put("combinationReplayed", false);
        var issues = new LinkedHashSet<>(graph.indexIssues);
        var method = original.path("assertionMethod");
        out.set("originalAssertionMethod", method.isMissingNode() ? mapper.createObjectNode() : method.deepCopy());
        boolean identifiedMethod = method.isTextual() && !method.asText().isBlank()
                || method.isObject() && (!method.path("id").asText().isBlank() || !method.path("@id").asText().isBlank());
        issues.add(identifiedMethod ? "ORIGINAL_COMBINATION_RULE_NOT_IMPLEMENTED" : "ORIGINAL_COMBINATION_RULE_UNSPECIFIED");
        Map<String, Set<String>> users = new HashMap<>();
        for (var entry : criteria) {
            var criterion = (ObjectNode) entry;
            String pointer = criterion.path("sourceLocator").asText();
            var localIssues = new LinkedHashSet<>(graph.indexIssues);
            var observations = mapper.createArrayNode();
            if (graph.indexIssues.isEmpty()) graph.walk(original.at(pointer), pointer, new HashSet<>(), new HashSet<>(), observations, localIssues, 0);
            if (observations.isEmpty()) localIssues.add("ORIGINAL_OBSERVATIONS_MISSING");
            criterion.set("originalObservations", observations);
            criterion.put("sourceGraphResolved", localIssues.isEmpty());
            var blockers = criterion.putArray("qualificationBlockers");
            localIssues.forEach(blockers::add);
            if ("Met".equals(criterion.path("outcome").asText())) {
                if (!"EXPLICIT_SOURCE_VALUE".equals(criterion.path("appliedStrengthStatus").asText())) {
                    blockers.add("ACTUAL_APPLIED_STRENGTH_UNRESOLVED"); issues.add("ACTUAL_APPLIED_STRENGTH_UNRESOLVED");
                }
                blockers.add("OBSERVATION_SCIENTIFIC_SEMANTICS_UNVERIFIED");
                issues.add("OBSERVATION_SCIENTIFIC_SEMANTICS_UNVERIFIED");
                for (var observation : observations) {
                    String id = observation.path("sourceId").asText();
                    String key = id.isBlank() ? observation.path("contentSha256").asText() : id;
                    users.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(pointer);
                }
                issues.addAll(localIssues);
            }
        }
        var shared = out.putArray("sharedObservationGroups");
        users.entrySet().stream().sorted(Map.Entry.comparingByKey()).filter(e -> e.getValue().size() > 1).forEach(e -> {
            var group = shared.addObject().put("sourceIdentity", e.getKey());
            var locators = group.putArray("criterionLocators"); e.getValue().forEach(locators::add);
        });
        if (!shared.isEmpty()) issues.add("SHARED_OBSERVATIONS_REQUIRE_DEPENDENCY_REVIEW");
        if (criteria.isEmpty()) issues.add("ORIGINAL_CRITERIA_MISSING");
        var unresolved = out.putArray("unresolvedReasons"); issues.forEach(unresolved::add);
        return out.put("status", "INDEPENDENCE_NOT_ESTABLISHED");
    }

    private void index(JsonNode value, String pointer, int depth) {
        if (++nodes > MAX_NODES || depth > MAX_DEPTH) { indexIssues.add("ORIGINAL_GRAPH_SIZE_LIMIT"); return; }
        if (value.isObject()) {
            // A bare reference is not a definition. A typed but otherwise empty
            // observation is still resolved, then rejected for lack of facts.
            if (value.size() > (value.has("id") ? 1 : 0) + (value.has("@id") ? 1 : 0)) {
                for (String field : List.of("id", "@id")) {
                    String id = value.path(field).asText();
                    if (!id.isBlank()) definitions.computeIfAbsent(id, ignored -> new ArrayList<>()).add(pointer);
                }
            }
            for (var item : value.properties()) {
                if (!indexIssues.isEmpty()) return;
                index(item.getValue(), pointer + "/" + escape(item.getKey()), depth + 1);
            }
        } else if (value.isArray()) for (int i = 0; i < value.size() && indexIssues.isEmpty(); i++) index(value.get(i), pointer + "/" + i, depth + 1);
    }

    private void walk(JsonNode value, String pointer, Set<String> path, Set<String> emitted,
            ArrayNode observations, Set<String> issues, int depth) {
        if (++traversals > MAX_NODES || depth > MAX_DEPTH || observations.size() >= MAX_OBSERVATIONS) { issues.add("ORIGINAL_GRAPH_SIZE_LIMIT"); return; }
        boolean reference = value.isTextual() || value.isObject()
                && value.size() == (value.has("id") ? 1 : 0) + (value.has("@id") ? 1 : 0);
        if (reference) {
            String id = value.isTextual() ? value.asText() : value.path("id").asText(value.path("@id").asText());
            List<String> matches = definitions.get(id);
            if (matches == null || matches.isEmpty()) { issues.add("ORIGINAL_OBSERVATION_REFERENCE_UNRESOLVED"); return; }
            JsonNode found = original.at(matches.get(0));
            if (matches.stream().anyMatch(p -> !original.at(p).equals(found))) { issues.add("ORIGINAL_OBSERVATION_ID_CONFLICT"); return; }
            if (value.isObject() && value.has("id") && value.has("@id")) {
                var aliases = definitions.get(value.path("@id").asText());
                if (aliases == null || aliases.isEmpty()) { issues.add("ORIGINAL_OBSERVATION_REFERENCE_UNRESOLVED"); return; }
                if (aliases.stream().anyMatch(p -> !original.at(p).equals(found))) { issues.add("ORIGINAL_OBSERVATION_ID_CONFLICT"); return; }
            }
            pointer = matches.get(0); value = found;
        }
        if (!value.isObject()) { issues.add("ORIGINAL_OBSERVATION_SHAPE_INVALID"); return; }
        if (!path.add(pointer)) { issues.add("ORIGINAL_OBSERVATION_CYCLE"); return; }
        if (value.has("evidenceLine")) {
            var lines = value.path("evidenceLine");
            if (!lines.isArray() || lines.isEmpty()) issues.add("ORIGINAL_OBSERVATIONS_MISSING");
            else for (int i = 0; i < lines.size() && traversals <= MAX_NODES; i++) {
                var items = lines.get(i).path("evidenceItem");
                if (!items.isArray() || items.isEmpty()) { issues.add("ORIGINAL_OBSERVATIONS_MISSING"); continue; }
                for (int j = 0; j < items.size() && traversals <= MAX_NODES; j++) walk(items.get(j), pointer + "/evidenceLine/" + i + "/evidenceItem/" + j,
                        path, emitted, observations, issues, depth + 1);
            }
        } else if ("CriterionAssessment".equals(value.path("type").asText())) issues.add("ORIGINAL_OBSERVATIONS_MISSING");
        else if (emitted.add(pointer)) {
            for (String field : List.of("id", "@id")) {
                var matches = definitions.get(value.path(field).asText());
                JsonNode current = value;
                if (matches != null && matches.stream().anyMatch(p -> !original.at(p).equals(current)))
                    issues.add("ORIGINAL_OBSERVATION_ID_CONFLICT");
            }
            var row = observations.addObject().put("sourceLocator", pointer).put("sourceType", value.path("type").asText())
                    .put("sourceId", value.path("id").asText(value.path("@id").asText()))
                    .put("contentSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value))
                    .put("semanticsVerified", false);
            var literals = row.putArray("statementLocations");
            statements(value, pointer, literals, 0);
            if (literals.isEmpty()) issues.add("ORIGINAL_OBSERVATION_FACTS_UNRESOLVED");
        }
        path.remove(pointer);
    }

    private void statements(JsonNode value, String pointer, ArrayNode rows, int depth) {
        if (depth > MAX_DEPTH || rows.size() >= MAX_OBSERVATIONS) return;
        if (value.isObject()) for (var entry : value.properties()) {
            String p = pointer + "/" + escape(entry.getKey());
            if (Set.of("description", "comments", "value", "alleleFrequency", "count", "pValue", "oddsRatio").contains(entry.getKey())
                    && entry.getValue().isValueNode() && !entry.getValue().isNull())
                rows.addObject().put("sourceLocator", p).put("field", entry.getKey());
            else if (entry.getValue().isContainerNode()) statements(entry.getValue(), p, rows, depth + 1);
        }
        else if (value.isArray()) for (int i = 0; i < value.size(); i++) statements(value.get(i), pointer + "/" + i, rows, depth + 1);
    }
    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }
}
