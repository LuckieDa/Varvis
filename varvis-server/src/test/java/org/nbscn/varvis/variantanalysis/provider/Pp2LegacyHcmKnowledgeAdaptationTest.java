package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real accepted daily/compiler artifacts; mutations are engineering controls,
 * never new published facts, variant clearance or historical reclassification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Pp2LegacyHcmKnowledgeAdaptationTest {
    private static final String DAILY_SHA = "d633875c63d21bb736fba881931b00b124b5c119fd10d277f3482d7485dbfd30";
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    @TempDir Path work;
    Path dailyPath;
    byte[] dailyBytes = new byte[0];
    ObjectNode daily = mapper.createObjectNode(), newlyCompiled = mapper.createObjectNode();
    int recordIndex, specificationIndex;

    @BeforeAll void existingFrozenArtifacts() throws Exception {
        String legacy = System.getProperty("t05.pp2.legacyKnowledge"), current = System.getProperty("t05.hcm.knowledge");
        org.junit.jupiter.api.Assumptions.assumeTrue(legacy != null && current != null, "Explicit existing daily and current compiler artifacts required");
        if (legacy == null || current == null) throw new IllegalStateException("PP2_KNOWLEDGE_FIXTURES_REQUIRED");
        dailyPath = Path.of(legacy).toRealPath(); dailyBytes = Files.readAllBytes(dailyPath);
        assertEquals(DAILY_SHA, CodingTranscriptMaterialSupply.sha(dailyBytes));
        var old = mapper.readTree(dailyBytes); var compiled = mapper.readTree(Files.readAllBytes(Path.of(current)));
        if (!(old instanceof ObjectNode oldObject) || !(compiled instanceof ObjectNode newObject))
            throw new IllegalStateException("PP2_KNOWLEDGE_FIXTURE_OBJECT_REQUIRED");
        daily = oldObject; newlyCompiled = newObject;
        recordIndex = find(daily.path("records"), "specificationKey", "GN100@1.0");
        specificationIndex = find(daily.path("specifications"), "specificationKey", "GN100@1.0");
        assertEquals("UNSUPPORTED_CONDITIONS", record(daily).path("status").asText());
        assertEquals("PP2_SOURCE_RESTRICTION", record(daily).at("/interpretation/type").asText());
    }

    private static int find(JsonNode array, String field, String value) {
        for (int i = 0; i < array.size(); i++) if (value.equals(array.path(i).path(field).asText())) return i;
        throw new IllegalStateException("PP2_KNOWLEDGE_FIXTURE_SCOPE_REQUIRED");
    }
    private ObjectNode record(ObjectNode document) { return (ObjectNode) document.path("records").path(recordIndex); }
    private ObjectNode specification(ObjectNode document) { return (ObjectNode) document.path("specifications").path(specificationIndex); }
    private ObjectNode context(JsonNode row) {
        var scope = row.path("scope"); var out = mapper.createObjectNode().put("moi", scope.path("moi").asText());
        out.putObject("gene").put("hgncId", scope.path("hgncId").asText());
        out.putObject("disease").put("mondoId", scope.path("mondoId").asText());
        String tx = scope.path("transcript").asText(); int dot = tx.lastIndexOf('.');
        out.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", tx.substring(dot + 1));
        out.putObject("variant").putObject("evidenceQueryAllele").put("assembly", scope.path("assembly").asText());
        return out;
    }
    private ObjectNode model(JsonNode row) {
        return mapper.createObjectNode().put("hgncId", row.at("/scope/hgncId").asText())
                .put("assembly", row.at("/scope/assembly").asText()).put("transcript", row.at("/scope/transcript").asText());
    }
    private ObjectNode select(PublishedCriterionKnowledge knowledge, JsonNode row) {
        return knowledge.select("ACMG_AMP_2015:PP2", context(row), model(row));
    }
    private PublishedCriterionKnowledge loadControl(ObjectNode document) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(document); Path file = Files.createTempFile(work, "knowledge-control-", ".json");
        Files.write(file, bytes); return PublishedCriterionKnowledge.load(mapper, file, CodingTranscriptMaterialSupply.sha(bytes));
    }
    private void refresh(ObjectNode document) {
        var spec = specification(document); var row = record(document);
        spec.put("entitySha256", adapter.canonicalSha256(spec.path("original")));
        row.put("clauseSha256", adapter.canonicalSha256(spec.path("original").at(row.path("clausePointer").asText())));
    }

    @Test void actualDailySourceAdvancesOnlyTheSoftwareMethodInMemoryWithoutChangingBytes() throws Exception {
        var documentBefore = daily.deepCopy(); var oldRow = record(daily).deepCopy();
        var knowledge = PublishedCriterionKnowledge.load(mapper, dailyPath, DAILY_SHA);
        var selected = select(knowledge, oldRow);
        assertEquals("READY", selected.path("status").asText());
        assertEquals(Pp2HcmPublishedSource.TYPE, selected.at("/record/interpretation/type").asText());
        assertEquals(oldRow.path("scope"), selected.at("/record/scope"));
        assertEquals("NM_001018005.2", selected.at("/record/scope/transcript").asText());
        assertEquals(specification(daily), selected.path("specification"));
        var trace = selected.at("/record/sourceMethodAdaptation");
        assertEquals("UNSUPPORTED_CONDITIONS", trace.path("originalStatus").asText());
        assertEquals("PP2_ADDITIONAL_CONDITIONS_UNSUPPORTED", trace.path("originalReasonCode").asText());
        assertEquals("PP2_SOURCE_RESTRICTION", trace.path("originalInterpretationType").asText());
        assertEquals(oldRow.path("interpretation"), trace.path("originalInterpretation"));
        assertEquals(adapter.canonicalSha256(oldRow), trace.path("originalRecordSha256").asText());
        assertEquals(adapter.canonicalSha256(selected.at("/record/interpretation")), trace.path("derivedInterpretationSha256").asText());
        assertFalse(trace.path("variantEvaluationCreated").asBoolean(true));
        assertFalse(trace.path("phenotypeReviewGranted").asBoolean(true));
        assertFalse(selected.has("evaluation")); assertFalse(selected.has("review"));
        assertFalse(selected.has("otherPhenotypeReview"));
        assertTrue(knowledge.hasInterpretationForBackground("ACMG_AMP_2015:PP2", context(oldRow), Pp2HcmPublishedSource.TYPE));
        assertEquals(PublishedCriterionKnowledge.PP2_HCM_SOURCE_METHOD_ADAPTATION_VERSION,
                knowledge.identity().path("pp2HcmSourceMethodAdaptationVersion").asText());
        assertEquals(DAILY_SHA, knowledge.identity().path("rawSha256").asText());
        assertEquals(documentBefore, daily); assertArrayEquals(dailyBytes, Files.readAllBytes(dailyPath));
        selected.removeAll(); assertEquals("READY", select(knowledge, oldRow).path("status").asText());
    }

    @Test void actualLegacyAndNewCompilerDeriveTheIdenticalSourceMethodAndKeepOriginalScopes() throws Exception {
        var legacy = select(PublishedCriterionKnowledge.load(mapper, dailyPath, DAILY_SHA), record(daily));
        var current = select(loadControl(newlyCompiled), newlyCompiled.path("records").path(0));
        assertEquals("READY", current.path("status").asText());
        for (String field : List.of("scope", "clausePointer", "clauseSha256", "scopePointer", "interpretation", "status", "reasonCode"))
            assertEquals(legacy.path("record").path(field), current.path("record").path(field), field);
        assertEquals(legacy.path("specification"), current.path("specification"));
        assertFalse(current.at("/record").has("sourceMethodAdaptation"));
        assertTrue(Pp2HcmPublishedSource.inspect(mapper, legacy).recognised());
        assertNull(Pp2PublishedAssessment.validate(legacy, true));
        assertFalse(legacy.path("clinicalValidationCompleted").asBoolean(true));
    }

    @Test void everyOtherDailyRecordIncludingRasRetainsItsOriginalDispositionAndInterpretation() throws Exception {
        var knowledge = PublishedCriterionKnowledge.load(mapper, dailyPath, DAILY_SHA);
        for (int i = 0; i < daily.path("records").size(); i++) if (i != recordIndex) {
            var row = daily.path("records").path(i); var selected = select(knowledge, row);
            assertEquals(row, selected.path("record"), row.path("recordId").asText());
            assertFalse(knowledge.hasInterpretationForBackground("ACMG_AMP_2015:PP2", context(row), Pp2HcmPublishedSource.TYPE));
        }
        assertArrayEquals(dailyBytes, Files.readAllBytes(dailyPath));
    }

    @ParameterizedTest @ValueSource(strings = {"EXECUTABLE", "CONFLICT", "MANUAL_REASON", "DISABLED_ROW", "DISABLED_INTERPRETATION",
            "DISABLED_DOCUMENT", "UNKNOWN_POLICY", "UNKNOWN_INTERPRETATION", "UNKNOWN_COMPILER", "UNKNOWN_SOURCE_VERSION",
            "UNVERIFIED_PREPARATION", "BA1_INHERITED", "CHANGED_ASSESSMENT", "REMOVED_CONDITION", "EXTRA_CONDITION", "NOT_APPLICABLE"})
    void unknownVersionsExplicitPolicyChoicesAndUnrecognisedSourceConditionsAreNotUpgraded(String change) throws Exception {
        var document = daily.deepCopy(); var row = record(document); var spec = specification(document);
        var original = (ObjectNode) spec.path("original"); var clause = (ObjectNode) original.at(row.path("clausePointer").asText()).path("entContent");
        switch (change) {
            case "EXECUTABLE" -> row.put("status", "EXECUTABLE");
            case "CONFLICT" -> row.put("status", "SOURCE_CONFLICT");
            case "MANUAL_REASON" -> row.put("reasonCode", "PROJECT_RULE_DISABLED");
            case "DISABLED_ROW" -> row.put("enabled", false);
            case "DISABLED_INTERPRETATION" -> ((ObjectNode) row.path("interpretation")).put("enabled", false);
            case "DISABLED_DOCUMENT" -> document.put("enabled", false);
            case "UNKNOWN_POLICY" -> document.put("policyVersion", "2.0.0");
            case "UNKNOWN_INTERPRETATION" -> ((ObjectNode) row.path("interpretation")).put("version", "2.0.0");
            case "UNKNOWN_COMPILER" -> ((ObjectNode) document.path("preparation")).put("methodVersion", "2.0.0");
            case "UNKNOWN_SOURCE_VERSION" -> {
                ((ObjectNode) original.path("entContent")).put("version", "1.1");
                spec.put("specificationKey", "GN100@1.1"); row.put("specificationKey", "GN100@1.1");
            }
            case "UNVERIFIED_PREPARATION" -> ((ObjectNode) document.path("preparation")).put("originalRawVerified", false);
            case "BA1_INHERITED" -> ((ObjectNode) document.path("preparation")).put("priorBa1ApprovalInherited", true);
            case "CHANGED_ASSESSMENT" -> ((ObjectNode) row.path("interpretation")).put("assessment", "Project disabled this rule.");
            case "REMOVED_CONDITION" -> {
                var strength = (ObjectNode) clause.at("/strengthDescriptor/4");
                strength.put("text", strength.path("text").asText().replace("Therefore, this rule should NOT be applied", "This condition was removed"));
            }
            case "EXTRA_CONDITION" -> clause.put("instructionsToUse", "Only apply when PM2 is met.");
            case "NOT_APPLICABLE" -> {
                row.put("status", "NOT_APPLICABLE"); clause.put("applicability", "Not applicable");
                for (var strength : clause.path("strengthDescriptor")) ((ObjectNode) strength).put("applicability", "Not applicable");
            }
            default -> fail(change);
        }
        refresh(document); var before = document.deepCopy(); var knowledge = loadControl(document);
        var selected = select(knowledge, row);
        assertFalse(knowledge.hasInterpretationForBackground("ACMG_AMP_2015:PP2", context(row), Pp2HcmPublishedSource.TYPE), change);
        assertEquals(row, selected.path("record"), change); assertFalse(selected.path("record").has("sourceMethodAdaptation"), change);
        assertEquals(before, document);
    }

    @ParameterizedTest @ValueSource(strings = {"ENTITY", "CLAUSE", "WRONG_SCOPE", "UNRELEASED", "UNKNOWN_DOCUMENT_VERSION"})
    void existingLoaderStillRejectsTamperedHashesWrongScopeAndUnreleasedSource(String change) {
        var document = daily.deepCopy(); var row = record(document); var spec = specification(document);
        switch (change) {
            case "ENTITY" -> spec.put("entitySha256", "0".repeat(64));
            case "CLAUSE" -> row.put("clauseSha256", "0".repeat(64));
            case "WRONG_SCOPE" -> ((ObjectNode) row.path("scope")).put("hgncId", "HGNC:999999");
            case "UNRELEASED" -> {
                for (var state : spec.at("/original/entContent/states")) if (state.path("current").asBoolean()) ((ObjectNode) state).put("name", "Draft");
                refresh(document);
            }
            case "UNKNOWN_DOCUMENT_VERSION" -> document.put("methodVersion", "2.0.0");
            default -> fail(change);
        }
        assertThrows(IllegalArgumentException.class, () -> loadControl(document));
    }

    @Test void acceptedFileHashIsStillMandatoryAndHistoricalOriginalJsonIsNotRewritten() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PublishedCriterionKnowledge.load(mapper, dailyPath, "0".repeat(64)));
        assertEquals("UNSUPPORTED_CONDITIONS", mapper.readTree(Files.readAllBytes(dailyPath)).at("/records/4/status").asText());
        assertArrayEquals(dailyBytes, Files.readAllBytes(dailyPath));
    }
}
