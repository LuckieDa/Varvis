package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossEvidenceGraphTest {
    final Pvs1StartLossAutomaticPreparationTest helper = new Pvs1StartLossAutomaticPreparationTest();
    final com.fasterxml.jackson.databind.ObjectMapper mapper = helper.mapper;
    @TempDir Path temporary;
    ObjectNode[] fixture() throws Exception {
        var summary = helper.summary(helper.base.fixture(1), 4, "Pathogenic");
        var raw = helper.original(summary, "PS3");
        var observation = mapper.createObjectNode().put("id", "obs:1").put("type", "InfFromBkgrndSciKnow")
                .put("description", "Source observation requiring scientific review, not a verified functional conclusion.");
        ((ObjectNode) raw.at("/evidenceLine/0/evidenceItem/0")).putArray("evidenceLine").addObject().putArray("evidenceItem").add("obs:1");
        raw.putArray("sourceObjects").add(observation);
        return new ObjectNode[]{summary, raw};
    }
    ObjectNode run(ObjectNode[] f) { return Pvs1StartLossAutomaticPreparation.assessment(mapper, f[0], f[1]); }
    boolean issue(ObjectNode out, String issue) { return out.at("/sourceEvidenceAudit/unresolvedReasons").toString().contains('"' + issue + '"'); }

    @Test void resolvesOriginalReferencesWithoutAdoptingTheirClaimsOrDefaultStrength() throws Exception {
        var f = fixture(); var before = f[1].deepCopy(); var out = run(f);
        assertTrue(out.at("/criteria/0/sourceGraphResolved").asBoolean());
        assertEquals("/sourceObjects/0", out.at("/criteria/0/originalObservations/0/sourceLocator").asText());
        assertEquals("/sourceObjects/0/description", out.at("/criteria/0/originalObservations/0/statementLocations/0/sourceLocator").asText());
        assertTrue(issue(out, "ACTUAL_APPLIED_STRENGTH_UNRESOLVED"));
        assertTrue(issue(out, "ORIGINAL_COMBINATION_RULE_UNSPECIFIED"));
        assertFalse(out.path("independenceEstablished").asBoolean());
        assertFalse(out.at("/sourceEvidenceAudit/combinationReplayed").asBoolean());
        assertEquals(before, f[1]);
    }
    @Test void cyclesMissingReferencesAndConflictingDefinitionsStayUnresolved() throws Exception {
        var f = fixture(); f[1].withArray("sourceObjects").removeAll();
        assertTrue(issue(run(f), "ORIGINAL_OBSERVATION_REFERENCE_UNRESOLVED"));
        f = fixture(); var observation = (ObjectNode) f[1].at("/sourceObjects/0");
        observation.putArray("evidenceLine").addObject().putArray("evidenceItem").add("obs:1");
        assertTrue(issue(run(f), "ORIGINAL_OBSERVATION_CYCLE"));
        f = fixture(); f[1].withArray("sourceObjects").add(f[1].at("/sourceObjects/0").deepCopy());
        assertTrue(run(f).at("/criteria/0/sourceGraphResolved").asBoolean());
        ((ObjectNode) f[1].at("/sourceObjects/1")).put("description", "Conflicting content");
        assertTrue(issue(run(f), "ORIGINAL_OBSERVATION_ID_CONFLICT"));
    }
    @Test void sameObservationAcrossCriteriaMustNotBeCountedTwice() throws Exception {
        var f = fixture(); var second = f[1].at("/evidenceLine/0").deepCopy();
        ((ObjectNode) second.at("/evidenceItem/0/criterion")).put("label", "PS4");
        f[1].withArray("evidenceLine").add(second);
        var out = run(f);
        assertTrue(issue(out, "SHARED_OBSERVATIONS_REQUIRE_DEPENDENCY_REVIEW"));
        assertEquals(2, out.at("/sourceEvidenceAudit/sharedObservationGroups/0/criterionLocators").size());
    }
    @Test void conflictingAliasesCannotSelectTheMoreConvenientDefinition() throws Exception {
        var f = fixture();
        ((ObjectNode) f[1].at("/sourceObjects/0")).put("@id", "https://example.org/first");
        f[1].withArray("sourceObjects").addObject().put("id", "obs:2").put("@id", "https://example.org/second")
                .put("type", "InfFromBkgrndSciKnow").put("description", "Different observation");
        var items = (com.fasterxml.jackson.databind.node.ArrayNode) f[1].at("/evidenceLine/0/evidenceItem/0/evidenceLine/0/evidenceItem");
        items.removeAll().addObject().put("id", "obs:1").put("@id", "https://example.org/second");
        assertTrue(issue(run(f), "ORIGINAL_OBSERVATION_ID_CONFLICT"));
    }
    @Test void typeOnlyObservationAndUnimplementedGuidelineNeverEstablishScience() throws Exception {
        var f = fixture(); ((ObjectNode) f[1].at("/sourceObjects/0")).remove("description");
        f[1].putObject("assertionMethod").put("id", "https://example.org/unimplemented-guideline");
        var out = run(f);
        assertTrue(issue(out, "ORIGINAL_OBSERVATION_FACTS_UNRESOLVED"));
        assertTrue(issue(out, "ORIGINAL_COMBINATION_RULE_NOT_IMPLEMENTED"));
    }
    @Test void boundsSharedGraphTraversalAndDocumentIndex() throws Exception {
        var f = fixture(); var node = (ObjectNode) f[1].at("/sourceObjects/0");
        for (int i = 0; i < 40; i++) node = node.putObject("nested");
        assertTrue(issue(run(f), "ORIGINAL_GRAPH_SIZE_LIMIT"));
    }
    @Test void nativeErepoOriginalKeepsItsUnverifiedObservationsAndSurvivesHistoryExport() throws Exception {
        String root = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "Native original fixture not configured");
        Path source = Path.of(root, "local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/erepo-native-example.json");
        byte[] raw = Files.readAllBytes(source);
        assertEquals("8b9e4511065d9b33567964cc84914b4dabd1da9159be9407f6708f126a2d7c76", CodingTranscriptMaterialSupply.sha(raw));
        var original = mapper.readTree(raw);
        var summary = mapper.createObjectNode().put("uuid", original.path("uuid").asText())
                .put("mondoId", original.at("/condition/id").asText()).put("caId", original.at("/variant/id").asText().replace("CAR:", ""))
                .put("classification", original.at("/statementOutcome/label").asText());
        var out = Pvs1StartLossAutomaticPreparation.assessment(mapper, summary, original);
        assertEquals(6, out.path("criteria").size());
        assertTrue(issue(out, "ORIGINAL_COMBINATION_RULE_UNSPECIFIED"));
        assertTrue(issue(out, "ACTUAL_APPLIED_STRENGTH_UNRESOLVED"));
        assertTrue(issue(out, "SHARED_OBSERVATIONS_REQUIRE_DEPENDENCY_REVIEW"));
        assertTrue(issue(out, "ORIGINAL_OBSERVATION_ID_CONFLICT"));
        for (var criterion : out.path("criteria")) {
            assertFalse(criterion.path("observationSemanticsVerified").asBoolean());
            for (var observation : criterion.path("originalObservations"))
                assertFalse(original.at(observation.path("sourceLocator").asText()).isMissingNode());
        }
        var archive = mapper.createObjectNode().put("rawJson", new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                .put("rawSha256", CodingTranscriptMaterialSupply.sha(raw));
        archive.set("assessment", out);
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(out), mapper.writeValueAsString(archive));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            var prepared = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-automatic-preparation-v1")
                    .put("methodVersion", Pvs1StartLossAutomaticPreparation.VERSION).put("runtimeEligible", false)
                    .put("scientificEvaluationPerformed", false).put("status", "INCOMPLETE");
            prepared.putArray("issues").add("PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
            prepared.putObject("upstreamReview").put("reasonCode", "PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE")
                    .putArray("records").addObject().put("recordId", summary.path("uuid").asText())
                    .put("reasonCode", out.path("reasonCode").asText()).set("nativeAssessment", out);
            Files.createDirectories(Path.of(output));
            Files.writeString(Path.of(output, "AUTO_NATIVE_EVIDENCE_GRAPH.json"), mapper.writeValueAsString(prepared));
        }
    }
}
