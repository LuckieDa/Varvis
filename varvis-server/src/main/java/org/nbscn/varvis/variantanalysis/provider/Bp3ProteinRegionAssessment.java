package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import org.springframework.lang.Nullable;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.positive;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.require;

/** Exact, externally reviewed region knowledge. A complete annotation search is not a function-free assertion. */
public final class Bp3ProteinRegionAssessment {
    public static final String VERSION = "1.0.0";
    public static final String SCHEMA = "bp3-reviewed-protein-region-assertions-v1";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    @Nullable private final Path file;
    private final String pin;
    private final Clock clock;

    public Bp3ProteinRegionAssessment(ObjectMapper mapper) { this(mapper, null, "", Clock.systemUTC()); }
    public Bp3ProteinRegionAssessment(ObjectMapper mapper, Path file, String serverAcceptedSha256) {
        this(mapper, file, serverAcceptedSha256, Clock.systemUTC());
    }
    Bp3ProteinRegionAssessment(ObjectMapper mapper, @Nullable Path file, String pin, Clock clock) {
        require(file == null ? pin.isEmpty() : pin.matches("[0-9a-f]{64}"), "BP3_REGION_ASSERTION_PIN_REQUIRED");
        this.mapper = UniProtProteinRegionSupply.strictMapper(mapper); this.adapter = new RuntimeEvidenceV2Adapter(this.mapper);
        this.file = file; this.pin = pin; this.clock = clock;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("version", VERSION).put("assertionsSha256", pin)
                .put("functionAbsenceInferredFromNoHit", false).put("createsScientificApproval", false);
        if (file != null) out.put("reviewValidityDate", LocalDate.now(clock).toString());
        return out;
    }
    ObjectNode assess(ObjectNode context, ObjectNode model, ObjectNode annotations, ObjectNode combined) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp3-protein-region-assessment-v1").put("version", VERSION)
                .put("regionState", "UNKNOWN").put("repeatObservation", "INCOMPLETE")
                .put("functionAbsenceInferredFromNoHit", false).put("annotationSnapshotSha256", adapter.canonicalSha256(annotations))
                .put("combinedSnapshotSha256", adapter.canonicalSha256(combined));
        var repeats = out.putArray("repeatObservations"); var functional = out.putArray("functionalLeads");
        try {
            require(!Thread.currentThread().isInterrupted(), "BP3_REGION_ASSESSMENT_INTERRUPTED");
            require("READY".equals(annotations.path("status").asText()), "BP3_REGION_MAPPING_UNAVAILABLE");
            require(adapter.canonicalSha256(model).equals(annotations.path("modelCanonicalSha256").asText())
                    && model.path("transcript").equals(annotations.path("transcript"))
                    && model.path("protein").equals(annotations.path("protein"))
                    && model.path("hgncId").equals(context.at("/gene/hgncId")), "BP3_REGION_MAPPING_IDENTITY_MISMATCH");
            var query = annotations.path("queryRange");
            require("REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE".equals(query.path("coordinateSystem").asText())
                    && span(query, model.path("proteinLength").asInt()), "BP3_REGION_QUERY_INVALID");
            out.set("queryRange", query.deepCopy());
            require(annotations.path("features").isArray() && annotations.path("unmappedFeatures").isArray(), "BP3_REGION_FEATURES_INVALID");
            for (var feature : annotations.path("features")) {
                String json = feature.path("rawFeatureJson").asText(); var nativeFeature = mapper.readTree(json);
                require(nativeFeature != null && feature.path("type").equals(nativeFeature.path("type"))
                        && span(feature, model.path("proteinLength").asInt())
                        && feature.path("start").equals(nativeFeature.at("/location/start/value"))
                        && feature.path("end").equals(nativeFeature.at("/location/end/value"))
                        && "EXACT".equals(nativeFeature.at("/location/start/modifier").asText())
                        && "EXACT".equals(nativeFeature.at("/location/end/modifier").asText())
                        && feature.path("sourceLocator").asText().matches("/features/[0-9]+")
                        && CodingTranscriptMaterialSupply.sha(json.getBytes(StandardCharsets.UTF_8))
                        .equals(feature.path("featureJsonSha256").asText()), "BP3_REGION_FEATURE_PROJECTION_INVALID");
                if (!overlap(query, feature)) continue;
                if ("Repeat".equals(feature.path("type").asText())) repeats.add(feature.deepCopy());
                // Chain is the mature protein envelope, not evidence of a local functional site.
                else if (!"Chain".equals(feature.path("type").asText())) functional.add(feature.deepCopy());
            }
            require("READY".equals(combined.path("status").asText())
                    && CombinedProteinRegionSupply.BP3_PROJECTION_VERSION.equals(combined.path("bp3ProjectionVersion").asText())
                    && combined.path("repeatOverlaps").isArray() && combined.path("functionalLeads").isArray(), "BP3_COMBINED_FUNCTION_CONTEXT_UNAVAILABLE");
            for (var hit : combined.path("repeatOverlaps")) {
                require(span(hit, model.path("proteinLength").asInt()), "BP3_COMBINED_RANGE_INVALID");
                if (overlap(query, hit)) repeats.add(hit.deepCopy());
            }
            for (var hit : combined.path("functionalLeads")) {
                require(span(hit, model.path("proteinLength").asInt()), "BP3_COMBINED_RANGE_INVALID");
                if (overlap(query, hit)) functional.add(hit.deepCopy());
            }
            boolean complete = annotations.path("unmappedFeatures").isEmpty()
                    && combined.path("unplacedCount").isIntegralNumber() && combined.path("unplacedCount").asInt(-1) == 0
                    && combined.path("operationalSourceComplete").isBoolean() && combined.path("operationalSourceComplete").asBoolean();
            out.put("repeatObservation", !repeats.isEmpty() ? "KNOWN_REPEAT" : complete ? "NO_REPEAT_OBSERVED" : "INCOMPLETE");
            var knowledge = knowledge(context, model, annotations, combined); out.set("qualifiedCoverage", knowledge);
            if (!"READY".equals(knowledge.path("status").asText())) return out.put("reasonCode", knowledge.path("reasonCode").asText());
            if (!complete) return out.put("reasonCode", "BP3_REGION_SOURCE_COMPLETENESS_REQUIRED");
            JsonNode positive = null; boolean excluded = false;
            for (var row : knowledge.path("intervals")) {
                if (!overlap(query, row)) continue;
                if ("NON_REPEAT".equals(row.path("repeatStatus").asText())) {
                    for (var repeat : repeats) if (overlap(row, repeat)) return out.put("reasonCode", "BP3_REGION_SOURCE_CONFLICT");
                    excluded = true;
                }
                if ("KNOWN_FUNCTION".equals(row.path("functionalStatus").asText())) excluded = true;
                if ("NO_KNOWN_FUNCTION".equals(row.path("functionalStatus").asText()))
                    for (var lead : functional) if (overlap(row, lead)) return out.put("reasonCode", "BP3_FUNCTIONAL_ANNOTATION_CONFLICT_REVIEW_REQUIRED");
                if ("REPEAT".equals(row.path("repeatStatus").asText()) && "NO_KNOWN_FUNCTION".equals(row.path("functionalStatus").asText())
                        && row.path("start").asInt() <= query.path("start").asInt() && row.path("end").asInt() >= query.path("end").asInt()) positive = row;
            }
            if (excluded) return out.put("regionState", "EXCLUDED").put("reasonCode", "BP3_REVIEWED_NON_REPEAT_OR_KNOWN_FUNCTION");
            if (positive != null) return out.put("regionState", "QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION")
                    .put("reasonCode", "BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION");
            return out.put("reasonCode", "BP3_EQUIVALENT_ENVELOPE_FUNCTIONAL_COVERAGE_INCOMPLETE");
        } catch (IllegalArgumentException bad) { return out.put("reasonCode", safeReason(bad)); }
        catch (IOException bad) { return out.put("reasonCode", "BP3_REGION_SOURCE_IO_OR_JSON_INVALID"); }
    }

    private ObjectNode knowledge(ObjectNode context, ObjectNode model, ObjectNode annotations, ObjectNode combined) throws IOException {
        var out = mapper.createObjectNode().put("status", "UNAVAILABLE");
        if (file == null) return out.put("reasonCode", "BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED");
        byte[] bytes = UniProtProteinRegionSupply.readBytes(file, 16_000_000);
        require(pin.equals(CodingTranscriptMaterialSupply.sha(bytes)), "BP3_REGION_ASSERTION_BYTES_CHANGED");
        var doc = mapper.readTree(bytes);
        if (!(doc != null && SCHEMA.equals(doc.path("schemaVersion").asText())
                && "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE".equals(doc.path("coordinateSystem").asText())
                && Set.of("PUBLIC_REFERENCE", "SYNTHETIC_TEST_ONLY").contains(doc.path("dataClass").asText())
                && doc.path("records").isArray() && doc.path("records").size() <= 10000)) {
            throw new IllegalArgumentException("BP3_REGION_ASSERTION_FORMAT_INVALID");
        }
        require(!"SYNTHETIC_TEST_ONLY".equals(doc.path("dataClass").asText()) || "SYNTHETIC".equals(context.path("dataClass").asText()),
                "BP3_SYNTHETIC_ASSERTION_RUNTIME_FORBIDDEN");
        JsonNode match = null; int index = -1, selected = -1;
        for (var row : doc.path("records")) {
            index++; require(row.isObject(), "BP3_REGION_ASSERTION_RECORD_INVALID");
            if (row.at("/scope/hgncId").equals(context.at("/gene/hgncId")) && row.at("/scope/mondoId").equals(context.at("/disease/mondoId"))
                    && row.at("/scope/moi").equals(context.path("moi")) && row.at("/scope/assembly").equals(model.path("assembly"))
                    && row.path("transcript").equals(model.path("transcript")) && row.path("protein").equals(model.path("protein"))) {
                require(match == null, "BP3_REGION_ASSERTION_RECORD_AMBIGUOUS"); match = row; selected = index;
            }
        }
        if (match == null) return out.put("reasonCode", "BP3_FUNCTION_KNOWLEDGE_CONTEXT_NOT_COVERED");
        require(match.at("/scope/hgncId").asText().matches("HGNC:[1-9][0-9]*")
                && match.at("/scope/mondoId").asText().matches("MONDO:[0-9]{7}") && text(match.path("scope"), "moi"), "BP3_REGION_ASSERTION_SCOPE_INVALID");
        var review = match.path("review");
        require("REVIEWED_APPLICABLE".equals(review.path("status").asText()) && text(review, "reviewId")
                && text(review, "reviewer") && text(review, "basis"), "BP3_REGION_ASSERTION_REVIEW_REQUIRED");
        try {
            LocalDate now = LocalDate.now(clock), date = LocalDate.parse(review.path("evaluatedAt").asText()), until = LocalDate.parse(review.path("validUntil").asText());
            require(!date.isAfter(now) && !until.isBefore(now) && !until.isBefore(date), "BP3_REGION_ASSERTION_REVIEW_NOT_CURRENT");
        } catch (java.time.DateTimeException bad) { throw new IllegalArgumentException("BP3_REGION_ASSERTION_REVIEW_DATE_INVALID"); }
        require(match.path("proteinSequenceSha256WithoutStop").equals(annotations.path("proteinSequenceSha256WithoutStop"))
                && match.path("uniProtRawSha256").equals(annotations.at("/source/rawSha256"))
                && match.path("combinedRawSha256").equals(combined.path("sha256")), "BP3_REGION_ASSERTION_SOURCE_IDENTITY_MISMATCH");
        require(match.path("intervals").isArray() && !match.path("intervals").isEmpty() && match.path("intervals").size() <= 1000,
                "BP3_REGION_ASSERTION_INTERVALS_INVALID");
        var sorted = new ArrayList<JsonNode>();
        for (var row : match.path("intervals")) {
            require(span(row, model.path("proteinLength").asInt()) && Set.of("REPEAT", "NON_REPEAT", "UNKNOWN").contains(row.path("repeatStatus").asText())
                    && Set.of("NO_KNOWN_FUNCTION", "KNOWN_FUNCTION", "UNKNOWN").contains(row.path("functionalStatus").asText())
                    && text(row, "basis") && row.path("sourceLocators").isArray() && !row.path("sourceLocators").isEmpty()
                    && row.path("sourceLocators").size() <= 100, "BP3_REGION_ASSERTION_INTERVAL_INVALID");
            for (var link : row.path("sourceLocators")) require(link.isTextual() && link.asText().matches("https://[^\\s]+"), "BP3_REGION_ASSERTION_SOURCE_LOCATOR_INVALID");
            sorted.add(row);
        }
        sorted.sort(Comparator.comparingInt(n -> n.path("start").asInt())); int previous = 0;
        for (var row : sorted) { require(row.path("start").asInt() > previous, "BP3_REGION_ASSERTION_OVERLAP_AMBIGUOUS"); previous = row.path("end").asInt(); }
        out.put("status", "READY").put("acceptedRawSha256", pin).put("recordLocator", "/records/" + selected);
        out.set("record", match.deepCopy()); out.set("intervals", match.path("intervals").deepCopy()); out.set("dataClass", doc.path("dataClass"));
        return out;
    }
    private static boolean text(JsonNode node, String field) { return node.path(field).isTextual() && !node.path(field).asText().isBlank(); }
    private static boolean span(JsonNode node, int length) { return positive(node.path("start")) && positive(node.path("end"))
            && node.path("start").asInt() <= node.path("end").asInt() && node.path("end").asInt() <= length; }
    private static boolean overlap(JsonNode a, JsonNode b) { return Math.max(a.path("start").asInt(), b.path("start").asInt()) <= Math.min(a.path("end").asInt(), b.path("end").asInt()); }
    static String safeReason(RuntimeException bad) { String reason = bad.getMessage(); return reason != null && reason.matches("[A-Z][A-Z0-9_]{0,180}") ? reason : "BP3_EXECUTION_UNAVAILABLE"; }
}
