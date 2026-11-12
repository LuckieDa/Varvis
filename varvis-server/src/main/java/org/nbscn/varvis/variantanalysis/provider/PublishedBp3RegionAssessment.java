package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Source-derived expert regions, not absence inferred from an empty feature search. */
public final class PublishedBp3RegionAssessment {
    public static final String VERSION = "1.0.0";
    static final String TYPE = "PUBLISHED_BP3_EXPLICIT_REPEAT_REGION_V1";
    private static final Set<String> NA = Set.of("not applicable", "not applicable for this vcep");
    private static final Pattern REGION = Pattern.compile(
            "Use for ([A-Za-z0-9_-]+) repetitive regions only \\(amino acids ([1-9][0-9]*)-([1-9][0-9]*)\\)\\. "
            + "These events can be multiple and large \\(e\\.g\\. 9 plus 15 total 20-30 bp is known, a single 21bp event is common\\)\\. "
            + "Please note that \\1 encompasses residues ([1-9][0-9]*) through ([1-9][0-9]*), "
            + "with UniProt identifying particularly disordered regions between ([0-9]+-[0-9]+(?:, [0-9]+-[0-9]+)*), and ([0-9]+-[0-9]+)\\.");
    private PublishedBp3RegionAssessment() { }

    static void verifyRecord(JsonNode row, JsonNode original) {
        var expected = derive(original.at(row.path("clausePointer").asText()).path("entContent"));
        if (!expected.path("status").equals(row.path("status")) || !expected.path("reasonCode").equals(row.path("reasonCode"))
                || !expected.path("interpretation").equals(row.path("interpretation")))
            throw new IllegalArgumentException("BP3_PUBLISHED_INTERPRETATION_SOURCE_MISMATCH");
    }

    static ObjectNode derive(JsonNode clause) {
        String app = normalized(clause.path("applicability")).toLowerCase(java.util.Locale.ROOT);
        var active = new ArrayList<JsonNode>();
        for (var s : clause.path("strengthDescriptor")) if ("Applicable".equals(s.path("applicability").asText())) active.add(s);
        var texts = new ArrayList<String>();
        for (String key : List.of("additionalComments", "instructionsToUse")) texts.add(normalized(clause.path(key)));
        var bodies = new ArrayList<>(texts);
        for (var s : active) bodies.add(normalized(s.path("text")));
        if (NA.contains(app)) return retained(active.isEmpty() ? "NOT_APPLICABLE" : "SOURCE_CONFLICT",
                active.isEmpty() ? "BP3_SOURCE_NOT_APPLICABLE" : "BP3_SOURCE_APPLICABILITY_CONFLICT");
        if (!"applicable".equals(app)) return retained("UNSUPPORTED_CONDITIONS", "BP3_SOURCE_APPLICABILITY_UNRESOLVED");
        if (bodies.stream().anyMatch(s -> Pattern.compile("(?i)^(?:not applicable\\b|do not use\\b)").matcher(s).find()))
            return retained("SOURCE_CONFLICT", "BP3_SOURCE_APPLICABILITY_CONFLICT");
        if (bodies.stream().anyMatch(s -> s.contains("BA1") || s.contains("frequency commensurate")))
            return retained("UNSUPPORTED_CONDITIONS", "BP3_OTHER_INFRAME_POPULATION_EVIDENCE_REQUIRED");
        if (active.size() != 1 || !"Supporting".equals(active.get(0).path("strength").asText())
                || !"approved".equals(active.get(0).path("status").asText()))
            return retained("UNSUPPORTED_CONDITIONS", "BP3_SOURCE_STRENGTH_UNRESOLVED");
        var match = REGION.matcher(normalized(active.get(0).path("text")));
        if (texts.stream().allMatch(mappedItem -> mappedItem.isEmpty()) && normalized(active.get(0).path("instructionsToUse")).isEmpty()
                && "Benign".equals(clause.path("baseStrength").asText())
                && "Benign Supporting".equals(clause.path("defaultStrength").asText()) && match.matches()) {
            try {
                int start = Integer.parseInt(match.group(2)), end = Integer.parseInt(match.group(3));
                int outerStart = Integer.parseInt(match.group(4)), outerEnd = Integer.parseInt(match.group(5));
                boolean ranges = true;
                for (String span : (match.group(6) + ", " + match.group(7)).split(", ")) {
                    var pair = span.split("-"); int a = Integer.parseInt(pair[0]), b = Integer.parseInt(pair[1]);
                    ranges &= outerStart <= a && a <= b && b <= outerEnd;
                }
                if (outerStart <= start && start <= end && end <= outerEnd && outerEnd <= 1_000_000 && ranges) {
                    var result = object().put("status", "EXECUTABLE").put("reasonCode", "BP3_SUPPORTED_PUBLISHED_REGION_GRAMMAR");
                    result.putObject("interpretation").put("type", TYPE).put("version", VERSION)
                            .put("regionName", match.group(1)).put("start", start).put("end", end)
                            .put("modelRegionStart", outerStart).put("modelRegionEnd", outerEnd).put("strength", "SUPPORTING")
                            .put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE")
                            .put("supportedVariantScope", "SINGLE_SIMPLE_INFRAME_COMPLETE_EQUIVALENT_ENVELOPE");
                    return result;
                }
            } catch (NumberFormatException invalid) { /* Unsupported numeric source, never truncate. */ }
        }
        return retained("UNSUPPORTED_CONDITIONS", "BP3_PUBLISHED_REGION_OR_CONDITIONS_REQUIRED");
    }

    /** Called only with a verified length-change material and an exact published selection. */
    static ObjectNode assess(ObjectNode selection, ObjectNode model, JsonNode association) {
        var out = object().put("schemaVersion", "bp3-published-region-assessment-v1").put("methodVersion", VERSION)
                .put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION").put("clinicalValidationCompleted", false)
                .put("repeatObservation", "INCOMPLETE").put("regionState", "UNKNOWN");
        if (!"READY".equals(selection.path("status").asText())) return out.put("reasonCode", selection.path("reasonCode").asText());
        verifyRecord(selection.path("record"), selection.at("/specification/original"));
        var rule = selection.at("/record/interpretation"); out.set("selectedRule", rule.deepCopy());
        if (!"GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE".equals(association.path("sequenceDerivationStatus").asText()))
            return out.put("reasonCode", "BP3_VERIFIED_INFRAME_SEQUENCE_REQUIRED");
        if (!"READY".equals(association.path("equivalentEditEnvelopeStatus").asText()))
            return out.put("reasonCode", association.path("equivalentEditEnvelopeReasonCode").asText("BP3_EQUIVALENT_ENVELOPE_UNDETERMINED"));
        var envelope = association.path("equivalentEditEnvelope");
        if (!envelope.path("proteinStart").isIntegralNumber() || !envelope.path("proteinEnd").isIntegralNumber()
                || !envelope.path("proteinStart").canConvertToInt() || !envelope.path("proteinEnd").canConvertToInt())
            return out.put("reasonCode", "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED");
        int first = envelope.path("proteinStart").asInt(), last = envelope.path("proteinEnd").asInt();
        if (first < 1 || last < first || last > model.path("proteinLength").asInt()
                || rule.path("modelRegionEnd").asInt() > model.path("proteinLength").asInt())
            return out.put("reasonCode", "BP3_PUBLISHED_REGION_MODEL_MISMATCH");
        out.putObject("queryRange").put("start", first).put("end", last).put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE");
        // Test the complete equivalence envelope, never one convenient alignment in a repeat.
        if (first < rule.path("start").asInt() || last > rule.path("end").asInt())
            return out.put("regionState", "EXCLUDED").put("reasonCode", "BP3_OUTSIDE_PUBLISHED_ELIGIBLE_REGION");
        return out.put("repeatObservation", "KNOWN_REPEAT").put("regionState", "QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION")
                .put("reasonCode", "BP3_PUBLISHED_REPEAT_REGION_MATCHED");
    }

    private static ObjectNode retained(String status, String reason) {
        var out = object().put("status", status).put("reasonCode", reason);
        out.putObject("interpretation").put("type", "BP3_RETAINED_UNEXECUTABLE_SOURCE_V1").put("version", VERSION); return out;
    }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static String normalized(JsonNode value) { return value.asText("").replaceAll("(?U)\\s+", " ").strip(); }
}
