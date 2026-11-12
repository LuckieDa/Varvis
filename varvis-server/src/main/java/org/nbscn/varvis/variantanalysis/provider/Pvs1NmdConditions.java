package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit source-bound position conditions. No gene names, threshold defaults or adoption. */
final class Pvs1NmdConditions {
    private Pvs1NmdConditions() { }
    static final String SCHEMA = "pvs1-truncating-nmd-policy-v2";
    private static final Set<String> FIELDS = Set.of("conditionId", "coordinate", "minimumInclusive", "maximumExclusive", "sourceIds", "basis");

    static boolean valid(JsonNode policy) {
        var conditions = policy.at("/nmdScope/positionConditions");
        boolean escape = Pvs1NmdEscape.SCHEMA.equals(policy.path("schemaVersion").asText())
                || Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText());
        if (!SCHEMA.equals(policy.path("schemaVersion").asText()) && !escape) return conditions.isMissingNode();
        if (escape && conditions.isMissingNode()) return true;
        return validConditions(policy,conditions);
    }

    static boolean validConditions(JsonNode policy, JsonNode conditions) {
        if (!conditions.isArray() || conditions.isEmpty() || conditions.size() > 16) return false;
        var ids = new HashSet<String>();
        for (var c : conditions) {
            if (!c.isObject() || !text(c.path("conditionId")) || !ids.add(c.path("conditionId").asText())
                    || !text(c.path("basis")) || !Set.of("REFERENCE_EVENT_SPAN", "REFERENCE_PTC_SPAN", "ALTERED_PTC_SPAN").contains(c.path("coordinate").asText())) return false;
            var names = c.fieldNames(); while (names.hasNext()) if (!FIELDS.contains(names.next())) return false;
            if (!c.has("minimumInclusive") && !c.has("maximumExclusive")) return false;
            for (String f : List.of("minimumInclusive", "maximumExclusive"))
                if (c.has(f) && (!c.path(f).isIntegralNumber() || !c.path(f).canConvertToInt() || c.path(f).asInt() <= 0)) return false;
            if (c.has("minimumInclusive") && c.has("maximumExclusive") && c.path("minimumInclusive").asInt() >= c.path("maximumExclusive").asInt()) return false;
            if (!c.path("sourceIds").isArray() || c.path("sourceIds").isEmpty()) return false;
            var sources = new HashSet<String>();
            for (var id : c.path("sourceIds")) {
                if (!text(id) || !sources.add(id.asText())) return false;
                boolean found = false;
                for (var s : policy.path("scientificSources")) if (id.equals(s.path("sourceId"))) found = true;
                if (!found) return false;
            }
        }
        return true;
    }

    static String evaluate(JsonNode policy, ObjectNode inputs) {
        if (!SCHEMA.equals(policy.path("schemaVersion").asText())
                && !Pvs1NmdEscape.SCHEMA.equals(policy.path("schemaVersion").asText())
                && !Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())) return null;
        if (policy.at("/nmdScope/positionConditions").isMissingNode()) return null;
        return evaluateConditions(policy.at("/nmdScope/positionConditions"),inputs,"positionConditionChecks");
    }

    static String evaluateConditions(JsonNode conditions, ObjectNode inputs, String auditKey) {
        var audit = inputs.putArray(auditKey);
        for (var c : conditions) {
            var row = audit.addObject(); row.set("condition", c.deepCopy());
            List<Integer> positions = positions(c.path("coordinate").asText(), inputs);
            if (positions.isEmpty()) {
                row.put("status", "UNRESOLVED");
                return "PVS1_POLICY_POSITION_UNRESOLVED";
            }
            var values = row.putArray("checkedPositions"); positions.forEach(values::add);
            for (int value : positions) if (c.has("minimumInclusive") && value < c.path("minimumInclusive").asInt()
                    || c.has("maximumExclusive") && value >= c.path("maximumExclusive").asInt()) {
                row.put("status", "OUTSIDE_SUPPORTED_RANGE");
                return "PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE";
            }
            row.put("status", "PASS");
        }
        return null;
    }

    // Shared read-only coordinate selection for execution and its public proof.
    static List<Integer> positions(String coordinate, JsonNode inputs) {
        if ("SPLICE_CDS_DELETION".equals(inputs.path("variantMechanism").asText())) {
            if ("REFERENCE_EVENT_SPAN".equals(coordinate)) {
                var start = inputs.path("referenceEditStart0");var end = inputs.path("referenceEditEnd0");
                if (!integer(start) || !integer(end) || start.asInt() < 0 || end.asInt() <= start.asInt()) return List.of();
                return List.of(start.asInt() + 1, end.asInt());
            }
            if ("REFERENCE_PTC_SPAN".equals(coordinate)) return values(inputs, "ptcReferenceStart", "ptcReferenceMiddle", "ptcReferenceEnd");
            return values(inputs, "ptcStart", "ptcEnd");
        }
        boolean indel = "FRAMESHIFT".equals(inputs.path("variantMechanism").asText());
        var result = new ArrayList<Integer>();
        if ("REFERENCE_EVENT_SPAN".equals(coordinate)) {
            if (!indel) return values(inputs, "cdsPosition");
            // Check both normalized genomic and HGVS representations. Insertions include
            // both flanking bases; a permissive HGVS shift cannot erase a boundary crossing.
            for (String prefix : List.of("reference", "hgvscReference")) {
                var lo = inputs.path(prefix + "EditStart0"); var hi = inputs.path(prefix + "EditEnd0");
                if (!integer(lo) || !integer(hi) || lo.asInt() < 0 || hi.asInt() < lo.asInt()) return List.of();
                if (lo.asInt() == Integer.MAX_VALUE || hi.asInt() == Integer.MAX_VALUE) return List.of();
                if (lo.asInt() == hi.asInt()) { result.add(lo.asInt()); result.add(lo.asInt()+1); }
                else { result.add(lo.asInt()+1); result.add(hi.asInt()); }
            }
            return result.stream().anyMatch(p -> p <= 0) ? List.of() : result;
        }
        if ("REFERENCE_PTC_SPAN".equals(coordinate) && indel) {
            if (!"REFERENCE_BASES".equals(inputs.path("ptcReferenceMapping").asText())) return List.of();
            return values(inputs, "ptcReferenceStart", "ptcReferenceMiddle", "ptcReferenceEnd");
        }
        return values(inputs, "ptcStart", "ptcEnd");
    }

    private static List<Integer> values(JsonNode inputs, String... names) {
        var result = new ArrayList<Integer>();
        for (String name : names) {
            var n = inputs.path(name); if (!integer(n) || n.asInt() <= 0) return List.of();
            result.add(n.asInt());
        }
        return result;
    }
    private static boolean integer(JsonNode n) { return n.isIntegralNumber() && n.canConvertToInt(); }
    private static boolean text(JsonNode n) { return n.isTextual() && !n.asText().isBlank(); }
}
