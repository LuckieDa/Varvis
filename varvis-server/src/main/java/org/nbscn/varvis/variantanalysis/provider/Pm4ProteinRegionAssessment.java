package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.lang.Nullable;

/** Region facts for a future general PM4 path. No evidence strength, disease adoption or BP3 functional inference.
 * The optional assertion file and pin must be supplied by trusted server configuration, never an uploaded variant.
 * A reviewed negative assertion is an external input; this class never creates, signs or infers it from a no-hit.
 */
public final class Pm4ProteinRegionAssessment {
    public static final String VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    @Nullable private final Path assertions;
    private final String acceptedPin;
    private final Clock clock;

    public Pm4ProteinRegionAssessment(ObjectMapper mapper) { this(mapper, null, "", Clock.systemUTC()); }
    public Pm4ProteinRegionAssessment(ObjectMapper mapper, Path reviewedAssertions, String serverAcceptedRawSha256) {
        this(mapper, reviewedAssertions, serverAcceptedRawSha256, Clock.systemUTC());
    }
    Pm4ProteinRegionAssessment(ObjectMapper mapper, @Nullable Path assertions, String pin, Clock clock) {
        this.mapper = UniProtProteinRegionSupply.strictMapper(mapper); this.adapter = new RuntimeEvidenceV2Adapter(this.mapper);
        this.assertions = assertions; this.acceptedPin = pin; this.clock = clock;
        require(assertions == null ? pin.isEmpty() : pin.matches("[0-9a-f]{64}"), "PM4_REGION_ASSERTION_PIN_REQUIRED");
    }

    public ObjectNode identity() {
        var result = mapper.createObjectNode().put("methodVersion", VERSION).put("assertionsSha256", acceptedPin);
        if (assertions != null) result.put("reviewValidityDate", LocalDate.now(clock).toString());
        return result;
    }

    ObjectNode assess(ObjectNode model, ObjectNode annotations) {
        var result = mapper.createObjectNode().put("schemaVersion", "pm4-protein-region-assessment-v1")
                .put("methodVersion", VERSION).put("regionState", "UNKNOWN").put("scientificEvaluationPerformed", false)
                .put("functionalContextRequiredByGeneralRegionGate", false)
                .put("bp3FunctionalConclusion", "NOT_EVALUATED")
                .put("annotationSnapshotSha256", adapter.canonicalSha256(annotations));
        var support = result.putArray("supportingAnnotationLocators");
        try {
            require(!Thread.currentThread().isInterrupted(), "PM4_REGION_ASSESSMENT_INTERRUPTED");
            require("READY".equals(annotations.path("status").asText()), "PM4_REGION_MAPPING_UNAVAILABLE");
            require(adapter.canonicalSha256(model).equals(annotations.path("modelCanonicalSha256").asText())
                    && model.path("transcript").equals(annotations.path("transcript"))
                    && model.path("protein").equals(annotations.path("protein")), "PM4_REGION_MAPPING_IDENTITY_MISMATCH");
            var query = annotations.path("queryRange");
            require("REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE".equals(query.path("coordinateSystem").asText())
                    && positive(query.path("start")) && positive(query.path("end"))
                    && query.path("start").asInt() <= query.path("end").asInt()
                    && query.path("end").asInt() <= model.path("proteinLength").asInt(), "PM4_REGION_QUERY_INVALID");
            int start = query.path("start").asInt(), end = query.path("end").asInt();
            result.set("queryRange", query.deepCopy());
            require(annotations.path("features").isArray() && annotations.path("unmappedFeatures").isArray(), "PM4_REGION_FEATURES_INVALID");
            var repeatRanges = new ArrayList<JsonNode>();
            for (var feature : annotations.path("features")) {
                if (!"Repeat".equals(feature.path("type").asText())) continue;
                var nativeFeature = mapper.readTree(feature.path("rawFeatureJson").asText());
                require(nativeFeature != null && "Repeat".equals(nativeFeature.path("type").asText())
                        && positive(feature.path("start")) && positive(feature.path("end"))
                        && feature.path("start").equals(nativeFeature.at("/location/start/value"))
                        && feature.path("end").equals(nativeFeature.at("/location/end/value"))
                        && "EXACT".equals(nativeFeature.at("/location/start/modifier").asText())
                        && "EXACT".equals(nativeFeature.at("/location/end/modifier").asText())
                        && feature.path("sourceLocator").asText().matches("/features/[0-9]+"), "PM4_REGION_REPEAT_PROJECTION_INVALID");
                if (feature.path("start").asInt() <= end && feature.path("end").asInt() >= start) {
                    repeatRanges.add(feature); support.add(feature.path("sourceLocator").asText());
                }
            }
            var qualified = qualifiedIntervals(model, annotations);
            result.set("qualifiedCoverage", qualified.deepCopy());
            var negative = new ArrayList<JsonNode>();
            for (var row : qualified.path("intervals")) {
                if (row.path("start").asInt() > end || row.path("end").asInt() < start) continue;
                if ("REPEAT".equals(row.path("repeatStatus").asText())) repeatRanges.add(row);
                if ("NON_REPEAT".equals(row.path("repeatStatus").asText())) {
                    negative.add(row);
                }
            }
            for (var positive : repeatRanges) for (var nonRepeat : negative)
                if (Math.max(start, Math.max(positive.path("start").asInt(), nonRepeat.path("start").asInt()))
                        <= Math.min(end, Math.min(positive.path("end").asInt(), nonRepeat.path("end").asInt())))
                    return result.put("reasonCode", "PM4_REGION_SOURCE_CONFLICT");
            if (!repeatRanges.isEmpty())
                return result.put("regionState", "REPEAT_OVERLAP").put("reasonCode", "PM4_EQUIVALENT_RANGE_INTERSECTS_REPEAT");
            if (!annotations.path("unmappedFeatures").isEmpty())
                return result.put("reasonCode", "PM4_REGION_UNRESOLVED_SOURCE_COORDINATES");
            if (!"READY".equals(qualified.path("status").asText()))
                return result.put("reasonCode", qualified.path("reasonCode").asText());
            negative.sort(Comparator.comparingInt(n -> n.path("start").asInt()));
            long cursor = start;
            for (var row : negative) {
                if (row.path("start").asInt() > cursor) break;
                cursor = Math.max(cursor, 1L + row.path("end").asInt());
            }
            if (cursor <= end) return result.put("reasonCode", "PM4_NON_REPEAT_QUALIFIED_COVERAGE_GAP");
            return result.put("regionState", "NON_REPEAT_SUPPORTED").put("reasonCode", "PM4_NON_REPEAT_SUPPORTED_BY_PINNED_REVIEWED_ASSERTIONS");
        } catch (IllegalArgumentException invalid) { return result.put("reasonCode", invalid.getMessage()); }
        catch (IOException invalid) { return result.put("reasonCode", "PM4_REGION_SOURCE_IO_OR_JSON_INVALID"); }
    }

    private ObjectNode qualifiedIntervals(ObjectNode model, ObjectNode annotations) throws IOException {
        var result = mapper.createObjectNode().put("status", "UNAVAILABLE");
        var file = assertions;
        if (file == null) return result.put("reasonCode", "PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED");
        byte[] bytes = UniProtProteinRegionSupply.readBytes(file, 4_000_000);
        require(CodingTranscriptMaterialSupply.sha(bytes).equals(acceptedPin), "PM4_REGION_ASSERTION_BYTES_CHANGED");
        var doc = mapper.readTree(bytes);
        require(doc instanceof ObjectNode && "pm4-reviewed-protein-repeat-assertions-v1".equals(doc.path("schemaVersion").asText())
                && "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE".equals(doc.path("coordinateSystem").asText())
                && List.of("PUBLIC_REFERENCE", "SYNTHETIC_TEST_ONLY").contains(doc.path("dataClass").asText()), "PM4_REGION_ASSERTION_FORMAT_INVALID");
        var review = doc.path("review");
        require("REVIEWED_APPLICABLE".equals(review.path("status").asText()) && text(review, "reviewId")
                && text(review, "reviewer") && text(review, "basis") && text(review, "evaluatedAt") && text(review, "validUntil"),
                "PM4_REGION_ASSERTION_REVIEW_REQUIRED");
        try {
            LocalDate now = LocalDate.now(clock), date = LocalDate.parse(review.path("evaluatedAt").asText()),
                    until = LocalDate.parse(review.path("validUntil").asText());
            require(!date.isAfter(now) && !until.isBefore(now) && !until.isBefore(date), "PM4_REGION_ASSERTION_REVIEW_NOT_CURRENT");
        } catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("PM4_REGION_ASSERTION_REVIEW_DATE_INVALID"); }
        require(doc.path("records").isArray() && doc.path("records").size() <= 10_000, "PM4_REGION_ASSERTION_RECORDS_INVALID");
        JsonNode match = null; int index = -1, selected = -1;
        for (var row : doc.path("records")) {
            index++;
            require(row.isObject(), "PM4_REGION_ASSERTION_RECORD_INVALID");
            if (row.path("hgncId").equals(model.path("hgncId")) && row.path("transcript").equals(model.path("transcript"))
                    && row.path("protein").equals(model.path("protein"))) {
                require(match == null, "PM4_REGION_ASSERTION_RECORD_AMBIGUOUS"); match = row; selected = index;
            }
        }
        if (match == null) return result.put("reasonCode", "PM4_NON_REPEAT_QUALIFIED_PROTEIN_NOT_COVERED");
        require(match.path("proteinSequenceSha256WithoutStop").equals(annotations.path("proteinSequenceSha256WithoutStop"))
                && match.path("uniProtRawSha256").equals(annotations.at("/source/rawSha256")), "PM4_REGION_ASSERTION_SOURCE_IDENTITY_MISMATCH");
        require(match.path("intervals").isArray() && match.path("intervals").size() <= 10_000, "PM4_REGION_ASSERTION_INTERVALS_INVALID");
        var sorted = new ArrayList<JsonNode>();
        for (var row : match.path("intervals")) {
            require(row.isObject() && positive(row.path("start")) && positive(row.path("end"))
                    && row.path("start").asInt() <= row.path("end").asInt()
                    && row.path("end").asInt() <= model.path("proteinLength").asInt()
                    && List.of("REPEAT", "NON_REPEAT", "UNKNOWN").contains(row.path("repeatStatus").asText())
                    && text(row, "basis") && row.path("sourceLocators").isArray() && !row.path("sourceLocators").isEmpty(),
                    "PM4_REGION_ASSERTION_INTERVAL_INVALID");
            for (var locator : row.path("sourceLocators"))
                require(locator.isTextual() && locator.asText().matches("https://[^\\s]+"), "PM4_REGION_ASSERTION_SOURCE_LOCATOR_INVALID");
            sorted.add(row);
        }
        sorted.sort(Comparator.comparingInt(n -> n.path("start").asInt()));
        int previousEnd = 0;
        for (var row : sorted) {
            require(row.path("start").asInt() > previousEnd, "PM4_REGION_ASSERTION_OVERLAP_AMBIGUOUS");
            previousEnd = row.path("end").asInt();
        }
        result.put("status", "READY").put("acceptedRawSha256", acceptedPin).put("recordLocator", "/records/" + selected)
                .set("review", review.deepCopy());
        result.set("dataClass", doc.path("dataClass").deepCopy());
        result.set("intervals", match.path("intervals").deepCopy());
        result.put("usage", "EXTERNAL_REVIEWED_REGION_ASSERTIONS_NOT_INFERRED_FROM_ANNOTATION_ABSENCE");
        return result;
    }

    private static boolean positive(JsonNode value) { return UniProtProteinRegionSupply.positive(value); }
    private static boolean text(JsonNode node, String field) { return node.path(field).isTextual() && !node.path(field).asText().isBlank(); }
    private static void require(boolean allowed, String reason) { UniProtProteinRegionSupply.require(allowed, reason); }
}
