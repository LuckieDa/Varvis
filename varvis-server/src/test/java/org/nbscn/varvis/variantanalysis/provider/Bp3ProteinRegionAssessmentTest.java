package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** All asserted knowledge is invented SYNTHETIC_TEST_ONLY; no real-protein approval is generated. */
class Bp3ProteinRegionAssessmentTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);
    ObjectNode model, context, annotations, extra;
    void setup() throws Exception {
        var fixture = new Pm4TestFixtures(mapper, 1); fixture.variant(105, "A", "AGCT", "6_7insGCT", "Glu2_Pro3insAla");
        model = fixture.model; context = fixture.context;
        var source = new UniProtProteinRegionSupplyTest(); source.root = Files.createDirectory(root.resolve("source"));
        var raw = source.raw("P12345"); raw.putArray("features").add(source.feature("Repeat", 2, 5));
        annotations = source.supply(raw).mapModel(model, 2, 4);
        var doc = GeneralPm4TestSupport.combined(mapper, annotations); byte[] bytes = mapper.writeValueAsBytes(doc);
        Files.write(root.resolve("combined.json"), bytes);
        extra = new CombinedProteinRegionSupply(mapper, root.resolve("combined.json"), CodingTranscriptMaterialSupply.sha(bytes)).resolveForBp3(annotations);
    }
    ObjectNode qualified() {
        var doc = mapper.createObjectNode().put("schemaVersion", Bp3ProteinRegionAssessment.SCHEMA)
                .put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE").put("dataClass", "SYNTHETIC_TEST_ONLY");
        var row = doc.putArray("records").addObject();
        row.putObject("scope").put("hgncId", context.at("/gene/hgncId").asText()).put("mondoId", context.at("/disease/mondoId").asText())
                .put("moi", context.path("moi").asText()).put("assembly", model.path("assembly").asText());
        for (String key : new String[]{"transcript", "protein"}) row.set(key, model.path(key));
        row.set("proteinSequenceSha256WithoutStop", annotations.path("proteinSequenceSha256WithoutStop"));
        row.set("uniProtRawSha256", annotations.at("/source/rawSha256")); row.set("combinedRawSha256", extra.path("sha256"));
        row.putObject("review").put("status", "REVIEWED_APPLICABLE").put("reviewId", "SYNTHETIC_TEST_ONLY")
                .put("reviewer", "TEST_ONLY").put("basis", "Invented fixture, not a scientific approval")
                .put("evaluatedAt", "2026-10-13").put("validUntil", "2026-10-15");
        row.putArray("intervals").add(interval(2, 5)); return doc;
    }
    ObjectNode interval(int start, int end) {
        var out = mapper.createObjectNode().put("start", start).put("end", end).put("repeatStatus", "REPEAT")
                .put("functionalStatus", "NO_KNOWN_FUNCTION").put("basis", "Invented source only");
        out.putArray("sourceLocators").add("https://example.invalid/SYNTHETIC_TEST_ONLY"); return out;
    }
    Bp3ProteinRegionAssessment accepted(ObjectNode doc) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(doc); var file = root.resolve("knowledge.json"); Files.write(file, bytes);
        return new Bp3ProteinRegionAssessment(mapper, file, CodingTranscriptMaterialSupply.sha(bytes), clock);
    }
    ObjectNode assess(Bp3ProteinRegionAssessment instance) { return instance.assess(context, model, annotations, extra); }
    @Test void wholeEnvelopeRepeatAndIndependentFunctionKnowledgeAreRequired() throws Exception {
        setup(); var before = annotations.deepCopy(); var result = assess(accepted(qualified()));
        assertEquals("QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION", result.path("regionState").asText(), result.toString());
        assertEquals("/records/0", result.at("/qualifiedCoverage/recordLocator").asText());
        assertFalse(result.path("functionAbsenceInferredFromNoHit").asBoolean(true)); assertEquals(before, annotations);
    }
    @Test void annotationNoHitOrRepeatAloneNeverGrantsEligibility() throws Exception {
        setup(); var engine = new Bp3ProteinRegionAssessment(mapper);
        assertEquals("BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", assess(engine).path("reasonCode").asText());
        annotations.putArray("features"); var out = assess(engine);
        assertEquals("UNKNOWN", out.path("regionState").asText()); assertEquals("NO_REPEAT_OBSERVED", out.path("repeatObservation").asText());
    }
    @Test void reviewedKnownFunctionIsAnExplicitExclusionNotBenignSupport() throws Exception {
        setup(); var doc = qualified(); ((ObjectNode) doc.at("/records/0/intervals/0")).put("functionalStatus", "KNOWN_FUNCTION");
        assertEquals("EXCLUDED", assess(accepted(doc)).path("regionState").asText());
    }
    @Test void intervalCannotCoverOnlyOnePreferredHgvsPlacement() throws Exception {
        setup(); var doc = qualified(); ((ObjectNode) doc.at("/records/0/intervals/0")).put("end", 3);
        assertEquals("BP3_EQUIVALENT_ENVELOPE_FUNCTIONAL_COVERAGE_INCOMPLETE", assess(accepted(doc)).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"gene", "disease", "moi", "assembly", "transcript", "protein"})
    void knowledgeCannotBeBorrowedAcrossContexts(String field) throws Exception {
        setup(); var doc = qualified(); var row = (ObjectNode) doc.at("/records/0");
        if (field.equals("transcript") || field.equals("protein")) row.put(field, row.path(field).asText()+"0");
        else ((ObjectNode) row.path("scope")).put(field.equals("gene") ? "hgncId" : field.equals("disease") ? "mondoId" : field, "OTHER");
        assertEquals("BP3_FUNCTION_KNOWLEDGE_CONTEXT_NOT_COVERED", assess(accepted(doc)).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"review", "expired", "future", "date", "source", "combined", "sequence", "duplicate",
            "overlap", "huge", "negative", "empty", "status", "source-locator", "synthetic"})
    void invalidKnowledgeMustNotQualify(String fault) throws Exception {
        setup(); var doc = qualified(); var row = (ObjectNode) doc.at("/records/0"); var interval = (ObjectNode) row.at("/intervals/0");
        var review = (ObjectNode) row.path("review");
        switch (fault) {
            case "review" -> review.put("status", "UNREVIEWED");
            case "expired" -> review.put("validUntil", "2026-10-13");
            case "future" -> review.put("evaluatedAt", "2026-10-15");
            case "date" -> review.put("evaluatedAt", "n/a");
            case "source" -> row.put("uniProtRawSha256", "a".repeat(64));
            case "combined" -> row.put("combinedRawSha256", "a".repeat(64));
            case "sequence" -> row.put("proteinSequenceSha256WithoutStop", "a".repeat(64));
            case "duplicate" -> doc.withArray("records").add(row.deepCopy());
            case "overlap" -> row.withArray("intervals").add(interval(3, 4));
            case "huge" -> interval.put("end", 4294967300L);
            case "negative" -> interval.put("start", -1);
            case "empty" -> row.putArray("intervals");
            case "status" -> interval.put("functionalStatus", "NO_ANNOTATION");
            case "source-locator" -> interval.putArray("sourceLocators");
            case "synthetic" -> context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
            default -> fail();
        }
        var out = assess(accepted(doc)); assertEquals("UNKNOWN", out.path("regionState").asText(), out.toString());
        assertNotEquals("BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION", out.path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"unmapped", "unplaced", "incomplete", "old-projection", "source-status"})
    void incompleteSourcesAreNotHiddenByQualifiedAssertions(String fault) throws Exception {
        setup(); var engine = accepted(qualified());
        switch (fault) {
            case "unmapped" -> annotations.withArray("unmappedFeatures").addObject().put("reason", "UNCERTAIN");
            case "unplaced" -> extra.put("unplacedCount", 1);
            case "incomplete" -> extra.put("operationalSourceComplete", false);
            case "old-projection" -> extra.remove("bp3ProjectionVersion");
            case "source-status" -> extra.put("status", "UNAVAILABLE");
            default -> fail();
        }
        assertEquals("UNKNOWN", assess(engine).path("regionState").asText());
    }
    @Test void functionalLeadConflictsWithoutPretendingItProvesClinicalFunction() throws Exception {
        setup(); var engine = accepted(qualified()); extra.withArray("functionalLeads").addObject().put("start", 3).put("end", 4).put("kind", "domain");
        var result = assess(engine); assertEquals("UNKNOWN", result.path("regionState").asText());
        assertEquals("BP3_FUNCTIONAL_ANNOTATION_CONFLICT_REVIEW_REQUIRED", result.path("reasonCode").asText());
    }
    @Test void nonRepeatKnowledgeConflictsWithTheNativeRepeatObservation() throws Exception {
        setup(); var doc = qualified(); ((ObjectNode)doc.at("/records/0/intervals/0")).put("repeatStatus", "NON_REPEAT");
        assertEquals("BP3_REGION_SOURCE_CONFLICT", assess(accepted(doc)).path("reasonCode").asText());
    }
    @Test void sourceChangesAndCorruptedProjectionsFailClosed() throws Exception {
        setup(); var engine = accepted(qualified()); Files.writeString(root.resolve("knowledge.json"), "{}");
        assertEquals("BP3_REGION_ASSERTION_BYTES_CHANGED", assess(engine).path("reasonCode").asText());
        ((ObjectNode) annotations.at("/features/0")).put("start", 1);
        assertEquals("BP3_REGION_FEATURE_PROJECTION_INVALID", assess(engine).path("reasonCode").asText());
    }
}
