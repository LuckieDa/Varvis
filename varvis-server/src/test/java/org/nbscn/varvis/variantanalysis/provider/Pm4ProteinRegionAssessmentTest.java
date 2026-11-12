package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

/** External approval examples are SYNTHETIC_TEST_ONLY, never fabricated approvals for real proteins. */
class Pm4ProteinRegionAssessmentTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);
    UniProtProteinRegionSupplyTest source;
    ObjectNode model, annotations;

    void setup(boolean repeats, int start, int end) throws Exception {
        source = new UniProtProteinRegionSupplyTest(); source.root = Files.createDirectory(root.resolve("source"));
        model = source.model(); var raw = source.raw("P12345");
        if (!repeats) ((ArrayNode) raw.path("features")).remove(0);
        annotations = source.supply(raw).mapModel(model,start,end);
        assertEquals("READY", annotations.path("status").asText());
    }
    ObjectNode qualified() {
        var doc = mapper.createObjectNode().put("schemaVersion", "pm4-reviewed-protein-repeat-assertions-v1")
                .put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE").put("dataClass", "SYNTHETIC_TEST_ONLY");
        doc.putObject("review").put("status", "REVIEWED_APPLICABLE").put("reviewId", "TEST_ONLY_NOT_SCIENTIFIC_APPROVAL")
                .put("reviewer", "SYNTHETIC_TEST_ONLY").put("basis", "Invented test coordinates")
                .put("evaluatedAt", "2026-10-12").put("validUntil", "2026-11-12");
        var record = doc.putArray("records").addObject();
        for (String field : new String[]{"hgncId","transcript","protein"}) record.set(field, model.path(field));
        record.set("proteinSequenceSha256WithoutStop", annotations.path("proteinSequenceSha256WithoutStop"));
        record.set("uniProtRawSha256", annotations.at("/source/rawSha256"));
        record.putArray("intervals").add(interval(1,6,"NON_REPEAT"));
        return doc;
    }
    ObjectNode interval(int start, int end, String state) {
        var row = mapper.createObjectNode().put("start", start).put("end", end).put("repeatStatus", state).put("basis", "SYNTHETIC_TEST_ONLY");
        row.putArray("sourceLocators").add("https://example.invalid/SYNTHETIC_TEST_ONLY"); return row;
    }
    Pm4ProteinRegionAssessment accepted(ObjectNode doc) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(doc); var path = root.resolve("assertions.json"); Files.write(path, bytes);
        return new Pm4ProteinRegionAssessment(mapper,path,CodingTranscriptMaterialSupply.sha(bytes),clock);
    }

    @Test void knownRepeatOverlapBlocksNonRepeatWithoutInventingBp3Function() throws Exception {
        setup(true,2,4); var result = new Pm4ProteinRegionAssessment(mapper).assess(model, annotations);
        assertEquals("REPEAT_OVERLAP", result.path("regionState").asText());
        assertEquals("PM4_EQUIVALENT_RANGE_INTERSECTS_REPEAT", result.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", result.path("bp3FunctionalConclusion").asText());
        assertFalse(result.path("functionalContextRequiredByGeneralRegionGate").asBoolean(true));
        assertFalse(result.path("scientificEvaluationPerformed").asBoolean(true));
    }
    @Test void databaseNoHitIsUnknownEvenWithFunctionalDomain() throws Exception {
        setup(false,2,4); var result = new Pm4ProteinRegionAssessment(mapper).assess(model,annotations);
        assertEquals("UNKNOWN", result.path("regionState").asText());
        assertEquals("PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED", result.path("reasonCode").asText());
    }
    @Test void qualifiedCompleteCoverageSupportsNonRepeatWithExactSourceProvenance() throws Exception {
        setup(false,2,4); var result = accepted(qualified()).assess(model, annotations);
        assertEquals("NON_REPEAT_SUPPORTED", result.path("regionState").asText(), result.toString());
        assertEquals("/records/0", result.at("/qualifiedCoverage/recordLocator").asText());
        assertEquals("SYNTHETIC_TEST_ONLY", result.at("/qualifiedCoverage/dataClass").asText());
    }
    @Test void adjacentNegativeIntervalsCoverWithoutFillingGaps() throws Exception {
        setup(false,2,5); var doc=qualified(); var intervals=((ObjectNode)doc.at("/records/0")).putArray("intervals");
        intervals.add(interval(2,3,"NON_REPEAT")).add(interval(4,5,"NON_REPEAT"));
        assertEquals("NON_REPEAT_SUPPORTED", accepted(doc).assess(model,annotations).path("regionState").asText());
        ((ObjectNode)intervals.get(1)).put("start",5);
        assertEquals("PM4_NON_REPEAT_QUALIFIED_COVERAGE_GAP", accepted(doc).assess(model,annotations).path("reasonCode").asText());
    }
    @Test void knownRepeatAndNegativeSameBasesConflictRatherThanVote() throws Exception {
        setup(true,2,4); var result=accepted(qualified()).assess(model,annotations);
        assertEquals("UNKNOWN",result.path("regionState").asText());
        assertEquals("PM4_REGION_SOURCE_CONFLICT",result.path("reasonCode").asText());
        assertFalse(result.path("supportingAnnotationLocators").isEmpty());
    }
    @Test void neighboringRepeatAndNonRepeatRegionsAreNotAConflict() throws Exception {
        setup(true,4,6); var doc=qualified(); ((ObjectNode)doc.at("/records/0")).putArray("intervals").add(interval(6,6,"NON_REPEAT"));
        assertEquals("REPEAT_OVERLAP",accepted(doc).assess(model,annotations).path("regionState").asText());
    }
    @Test void uncertainNativeFeatureBlocksNegativeButNotObservedPositive() throws Exception {
        setup(false,2,4); annotations.withArray("unmappedFeatures").addObject().put("reasonCode","FEATURE_UNCERTAIN_COORDINATES");
        assertEquals("PM4_REGION_UNRESOLVED_SOURCE_COORDINATES",accepted(qualified()).assess(model,annotations).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"review","expired","future","date","protein","source","missing-gene","overlap","huge","zero","unknown","no-source","duplicate"})
    void invalidOrIncompleteReviewNeverManufacturesNonRepeat(String fault) throws Exception {
        setup(false,2,4); var doc=qualified(); var review=(ObjectNode)doc.path("review"); var row=(ObjectNode)doc.at("/records/0");
        var interval=(ObjectNode)row.path("intervals").get(0);
        switch(fault) {
            case "review" -> review.put("status","UNREVIEWED");
            case "expired" -> review.put("validUntil","2026-10-12");
            case "future" -> review.put("evaluatedAt","2026-10-14");
            case "date" -> review.put("evaluatedAt","bad-date");
            case "protein" -> row.put("proteinSequenceSha256WithoutStop","a".repeat(64));
            case "source" -> row.put("uniProtRawSha256","a".repeat(64));
            case "missing-gene" -> row.put("hgncId","HGNC:12345");
            case "overlap" -> row.withArray("intervals").add(interval(3,4,"NON_REPEAT"));
            case "huge" -> interval.put("end",4294967300L);
            case "zero" -> interval.put("start",0);
            case "unknown" -> interval.put("repeatStatus","UNKNOWN");
            case "no-source" -> interval.putArray("sourceLocators");
            case "duplicate" -> doc.withArray("records").add(row.deepCopy());
            default -> fail();
        }
        var result=accepted(doc).assess(model,annotations);
        assertEquals("UNKNOWN",result.path("regionState").asText(),result.toString());
        assertTrue(result.path("reasonCode").asText().startsWith("PM4_"));
    }
    @Test void acceptedFileCannotChangeUnderOldPin() throws Exception {
        setup(false,2,4); var engine=accepted(qualified());
        assertEquals("NON_REPEAT_SUPPORTED",engine.assess(model,annotations).path("regionState").asText());
        Files.writeString(root.resolve("assertions.json"),"{}");
        assertEquals("PM4_REGION_ASSERTION_BYTES_CHANGED",engine.assess(model,annotations).path("reasonCode").asText());
    }
    @Test void mappingIdentityCannotBeReusedForAnotherModel() throws Exception {
        setup(true,2,4); model.put("protein","ENSP000001.2");
        assertEquals("PM4_REGION_MAPPING_IDENTITY_MISMATCH",new Pm4ProteinRegionAssessment(mapper).assess(model,annotations).path("reasonCode").asText());
    }
    @Test void autoPreparationUsesFullEnvelopeAndPreservesCandidateAndAuditBoundary() throws Exception {
        var fixture=new ProteinLengthChangeMaterialSupplyTest(); fixture.root=Files.createDirectory(root.resolve("transcript"));
        fixture.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var provider=new UniProtProteinRegionSupplyTest(); provider.root=Files.createDirectory(root.resolve("uniprot"));
        var prep=new Pm4AutomaticProteinMaterialPreparation(mapper,fixture.prebuilt(),provider.supply(provider.raw("P12345")),
                new Pm4ProteinRegionAssessment(mapper));
        var before=mapper.valueToTree(fixture.data.candidate); var context=fixture.data.context.deepCopy();
        var first=prep.prepare(context,fixture.data.candidate);
        assertEquals("REGION_FACTS_PREPARED",first.path("status").asText(),first.toString());
        assertEquals("UNREVIEWED",first.path("reviewStatus").asText());
        assertFalse(first.has("evaluation")); assertFalse(first.has("policy"));
        assertEquals(first.at("/sequenceMaterial/alleleAssociation/equivalentEditEnvelope/proteinStart"),first.at("/regionAssessment/queryRange/start"));
        assertEquals(before,mapper.valueToTree(fixture.data.candidate)); assertEquals(context,fixture.data.context);
        assertEquals(first.path("model"),prep.prepare(context,fixture.data.candidate).path("model"));
        byte[] serialized = mapper.writeValueAsBytes(first);
        // JSON preserves the integer value, not Jackson's in-memory IntNode/LongNode distinction.
        assertArrayEquals(serialized,mapper.writeValueAsBytes(mapper.readTree(serialized)));
    }
    @Test void autoPreparationReportsMissingKnowledgeWithoutErasingUsefulSequenceFacts() throws Exception {
        var fixture=new ProteinLengthChangeMaterialSupplyTest(); fixture.root=Files.createDirectory(root.resolve("transcript"));
        fixture.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var provider=new UniProtProteinRegionSupplyTest(); provider.root=Files.createDirectory(root.resolve("uniprot"));
        var raw=provider.raw("P12345"); raw.putArray("features");
        var prep=new Pm4AutomaticProteinMaterialPreparation(mapper,fixture.prebuilt(),provider.supply(raw),new Pm4ProteinRegionAssessment(mapper));
        var result=prep.prepare(fixture.data.context,fixture.data.candidate);
        assertEquals("PREPARED_WITH_GAPS",result.path("status").asText());
        assertTrue(result.has("model")); assertEquals(1,result.at("/sequenceMaterial/alleleAssociation/netAminoAcidChange").asInt());
    }
}
