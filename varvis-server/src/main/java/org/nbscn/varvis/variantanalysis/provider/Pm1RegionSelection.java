package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Pure location/priority operations. Callers separately validate their scientific source contract. */
final class Pm1RegionSelection {
    private Pm1RegionSelection() { }
    static boolean covers(JsonNode row, int position) {
        return position >= row.path("start").asInt() && position <= row.path("end").asInt();
    }
    @Nullable static JsonNode choose(List<JsonNode> matches, JsonNode precedence) {
        var graph = graph(precedence); JsonNode winner = null;
        for (var possible : matches) {
            boolean dominates = true;
            for (var other : matches) if (other != possible && !reaches(graph, possible.path("ruleId").asText(),
                    other.path("ruleId").asText(), new HashSet<>())) dominates = false;
            if (dominates) { if (winner != null) return null; winner = possible; }
        }
        return winner;
    }
    static Map<String, Set<String>> graph(JsonNode relations) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (var row : relations) graph.computeIfAbsent(row.path("higherRuleId").asText(), ignored -> new HashSet<>())
                .add(row.path("lowerRuleId").asText());
        return graph;
    }
    static boolean reaches(Map<String, Set<String>> graph, String from, String to, Set<String> visited) {
        if (!visited.add(from)) return false;
        for (String next : graph.getOrDefault(from, Set.of()))
            if (next.equals(to) || reaches(graph, next, to, visited)) return true;
        return false;
    }
}
