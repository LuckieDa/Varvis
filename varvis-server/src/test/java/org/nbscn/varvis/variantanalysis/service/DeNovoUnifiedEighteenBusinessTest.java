package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessRuleScope;
import org.nbscn.varvis.variantanalysis.provider.DeNovoCurrentTestDirectory;
import org.nbscn.varvis.variantanalysis.provider.DeNovoSelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** CURRENT18 normal product composition with real de novo source qualification.
 * Ranking transport, upstream annotation, reference and source observations are explicitly SYNTHETIC.
 * Expected science is fixed in DENOVO_UNIFIED_EIGHTEEN_EXPECTATIONS_2026-11-04.md before execution. */
class DeNovoUnifiedEighteenBusinessTest {
    @TempDir(factory = DeNovoCurrentTestDirectory.class, cleanup = CleanupMode.ON_SUCCESS) Path temporary;
    private DeNovoUnifiedEighteenFixture fixture;
    private static final Set<String> POPULATION = Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1");

    @AfterEach void close() { if (fixture != null) fixture.close(); }

    private void prepare(String scenario) throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        Path scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required; skip is not success");
        fixture = new DeNovoUnifiedEighteenFixture(temporary, python, scripts);
        fixture.prepare("JSON", scenario);
        assertEquals(18, fixture.base.service.configuredCriteria().size(), "Normal composition must derive CURRENT18");
        assertTrue(fixture.base.service.configuredCriteria().containsAll(DeNovoSelectedBatch.CRITERIA));
        // Assertion-only oracle, never passed to any profile, factory or request.
        var expected = new LinkedHashSet<String>();
        for (String code : "BA1 BS1 PM2 PP3 BP4 PS1 PM5 BP7 PVS1 PM4 BP3 PM1 PP2 BP1 PS2 PM6 PM3 PP1".split(" "))
            expected.add("ACMG_AMP_2015:" + code);
        int sourceReadsAfterFactoryCapture = fixture.readerExecutions();
        var options = fixture.workflows.inputOptions(fixture.base.analysisId, 1, "CURRENT");
        var offered = new LinkedHashSet<String>(); for (var id : options.path("enabledCriteria")) offered.add(id.asText());
        assertEquals(expected, offered); assertEquals(expected, new LinkedHashSet<>(fixture.base.service.configuredCriteria()));
        assertEquals(0, fixture.evaluations()); assertEquals(sourceReadsAfterFactoryCapture, fixture.readerExecutions());
        assertTrue(fixture.factories.isEmpty()); assertEquals(0, fixture.pp1Evaluations());
    }

    private ObjectNode request(String selection) {
        var request = fixture.base.request.deepCopy(); request.remove("selectedCriteria");
        if (!selection.equals("all")) {
            var ids = request.putArray("selectedCriteria");
            if (!selection.equals("pm6-only")) ids.add("ACMG_AMP_2015:PS2");
            if (!selection.equals("ps2-only")) ids.add("ACMG_AMP_2015:PM6");
        }
        return request;
    }

    private ObjectNode submit(ObjectNode request) throws Exception {
        var response = fixture.base.http.perform(post("/variant-analyses/{id}/acmg/runs", fixture.base.analysisId)
                .param("workflow", "CURRENT").contentType("application/json")
                .content(requireNonNull(fixture.base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getErrorMessage() + " " + response.getContentAsString());
        var value = (ObjectNode) fixture.base.mapper.readTree(response.getContentAsByteArray());
        assertNotEquals("FAILED", value.path("status").asText(), value.toPrettyString());
        return value;
    }

    private JsonNode module(ObjectNode response) throws Exception {
        var saved = fixture.base.store.run(fixture.base.analysisId, response.path("runId").asText()).orElseThrow();
        return fixture.base.mapper.readTree(saved.privateJson()).at("/localBatch/modules/deNovo/results/0");
    }

    private JsonNode criterion(ObjectNode response, String code) {
        for (var row : response.at("/candidates/0/attempts/0/localBatch/criteria"))
            if (("ACMG_AMP_2015:" + code).equals(row.path("criterionId").asText())) return row;
        return fixture.base.mapper.missingNode();
    }

    private String populationQueries() throws Exception {
        Path path = temporary.resolve("queries.txt"); return Files.exists(path) ? Files.readString(path) : "";
    }

    private void assertSelection(ObjectNode request, ObjectNode response, String selection) {
        var selected = new LinkedHashSet<String>();
        if (selection.equals("all")) selected.addAll(fixture.base.service.configuredCriteria());
        else for (var id : request.path("selectedCriteria")) selected.add(id.asText());
        var enabled = new LinkedHashSet<String>(); for (var id : response.path("enabledCriteria")) enabled.add(id.asText());
        assertEquals(selected, enabled);
        selected.removeAll(POPULATION);
        var rows = new LinkedHashSet<String>();
        for (var row : response.at("/candidates/0/attempts/0/localBatch/criteria")) rows.add(row.path("criterionId").asText());
        assertEquals(selected, rows, "Unselected local evidence must not emit rows or score");
        assertEquals(selected.size(), response.at("/candidates/0/attempts/0/localBatch/criteria").size(), "No duplicate criterion rows");
        if (selection.equals("all")) {
            assertEquals(16, rows.size());
            for (var row : response.at("/candidates/0/attempts/0/localBatch/criteria"))
                if (!DeNovoSelectedBatch.CRITERIA.contains(row.path("criterionId").asText())) {
                    assertNotEquals("MET", row.path("status").asText(), "No other target-qualified source was supplied: " + row);
                    assertEquals("NONE", row.path("appliedStrength").asText());
                }
            assertEquals("NOT_EVALUATED", criterion(response, "PVS1").path("status").asText());
            assertEquals("PVS1_MECHANISM_SOURCE_NOT_FOUND", criterion(response, "PVS1").path("reasonCode").asText());
        }
    }

    private void assertReadOnly(ObjectNode request, ObjectNode result) throws Exception {
        int evaluations = fixture.evaluations(), readers = fixture.readerExecutions();
        var factories = new LinkedHashMap<>(fixture.factories); long pp1 = fixture.pp1Evaluations(); String queries = populationQueries();
        var saved = fixture.base.store.run(fixture.base.analysisId, result.path("runId").asText()).orElseThrow();
        assertEquals(result, submit(request));
        assertEquals(result, fixture.base.service.read(fixture.base.analysisId, saved.id()));
        var full = fixture.base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", fixture.base.analysisId, saved.id()))
                .andReturn().getResponse();
        assertEquals(200, full.getStatus()); assertEquals(result, fixture.base.mapper.readTree(full.getContentAsByteArray()));
        assertEquals(saved, fixture.base.store.run(fixture.base.analysisId, saved.id()).orElseThrow());
        assertEquals(evaluations, fixture.evaluations()); assertEquals(readers, fixture.readerExecutions());
        assertEquals(factories, fixture.factories); assertEquals(pp1, fixture.pp1Evaluations()); assertEquals(queries, populationQueries());
    }

    @ParameterizedTest
    @CsvSource({"TISSUE_BOTH,PS2,STRONG,0,true", "COMBINED_ASSUMED,PS2,SUPPORTING,2,false",
            "GENERAL_ASSUMED,PM6,SUPPORTING,2,false", "TISSUE_QUALITY,NONE,NONE,0,false", "TISSUE_WRONG_TARGET,NONE,NONE,0,false"})
    void currentEighteenSingleRelatedAndAllKeepSourceQualifiedScience(String scenario, String assigned,
            String strength, int quarterPoints, boolean confirmed) throws Exception {
        prepare(scenario); boolean positive = !assigned.equals("NONE");
        var results = new LinkedHashMap<String, ObjectNode>(); JsonNode sharedQualified = null, sharedProof = null;
        var published = fixture.base.mapper.valueToTree(fixture.base.jobs.findInterpretations(fixture.base.analysisId, 0, 10));
        for (String selection : List.of("ps2-only", "pm6-only", "pair", "all")) {
            int before = fixture.evaluations(); String priorQueries = populationQueries();
            var request = request(selection); var response = submit(request); results.put(selection, response);
            assertEquals(before + 1, fixture.evaluations(), "One shared de novo evaluation per background, never two evidence scores");
            assertEquals(published, fixture.base.mapper.valueToTree(fixture.base.jobs.findInterpretations(fixture.base.analysisId, 0, 10)));
            assertSelection(request, response, selection);
            var actual = module(response); var summary = actual.path("publicSummary"); var qualified = actual.path("qualifiedResult");
            assertEquals("denovo-local-evidence-1.4.1", qualified.path("version").asText());
            assertEquals("denovo-local-evidence-1.4.1", response.at("/executionProfile/moduleBindings/deNovo/qualifierVersion").asText());
            assertEquals(positive ? "MET" : "NOT_EVALUATED", qualified.at("/result/status").asText(), actual.toPrettyString());
            assertEquals(assigned, qualified.at("/result/code").asText()); assertEquals(strength, qualified.at("/result/strength").asText());
            assertEquals(quarterPoints, summary.path("effectiveQuarterPoints").asInt());
            assertEquals(selection.endsWith("only") ? 1 : 2, actual.path("criteria").size());
            assertEquals("NOT_RUN", summary.at("/coreMaterials/0/criterionDecision").asText());
            assertEquals("NOT_RUN", summary.at("/coreMaterials/0/sourceDiscovery/criterionDecision").asText());
            if (positive) {
                assertEquals("EXACT", summary.at("/coreMaterials/0/selectionMatch").asText());
                assertEquals(confirmed, summary.at("/events/0/confirmedParentage").asBoolean());
                assertEquals(scenario.equals("GENERAL_ASSUMED") ? "NOT_APPLICABLE" : "ADOPTED", summary.at("/adoptionProof/status").asText());
                assertEquals("COMPLETE", summary.at("/adoptionProof/projectionStatus").asText());
                assertEquals(1, summary.path("events").size(), "The same biological event cannot be counted twice");
                assertEquals("COUNTED", summary.at("/events/0/disposition").asText());
                assertEquals(quarterPoints, summary.at("/events/0/rawQuarterPoints").asInt());
                if (scenario.equals("TISSUE_BOTH")) {
                    assertEquals("EXPERT_FIXED_STRENGTH", summary.path("calculationMethod").asText());
                    assertEquals("NO_SVI_POINT_SUM", summary.path("strengthAdjustment").asText());
                    assertEquals("STRONG", summary.at("/adoptionProof/events/0/adoptedStrength").asText());
                    assertEquals("C1_AND_C2", summary.at("/adoptionProof/events/0/conditionBranch").asText());
                    assertEquals("MET", summary.at("/adoptionProof/events/0/c1/status").asText());
                    assertEquals("MET", summary.at("/adoptionProof/events/0/c2/status").asText());
                    assertFalse(summary.at("/adoptionProof/events/0/c1/sources").isEmpty());
                    assertEquals(2, summary.at("/adoptionProof/events/0/c2/observations").size());
                    for (var observation : summary.at("/adoptionProof/events/0/c2/observations")) assertFalse(observation.path("sources").isEmpty());
                } else if (scenario.equals("COMBINED_ASSUMED")) {
                    assertEquals("SVI_1_1_COMBINED_PS2", summary.at("/adoptionProof/policy/eventMode").asText());
                    assertFalse(summary.at("/adoptionProof/events/0/combined/confirmedParentage").asBoolean(true));
                    assertEquals("ASSUMED_NOT_TESTED", summary.at("/adoptionProof/events/0/combined/maternity").asText());
                    assertEquals("ASSUMED_NOT_TESTED", summary.at("/adoptionProof/events/0/combined/paternity").asText());
                    assertEquals(fixture.source.at("/core/policySha256"), summary.at("/adoptionProof/policy/phenotypePolicySha256"));
                    assertEquals("POLICY_SHA256_MODE_SOURCE_LOCATOR", summary.at("/adoptionProof/policy/clauseIdentityMethod").asText());
                    assertFalse(summary.at("/adoptionProof/policy/sources").isEmpty());
                    for (var source : summary.at("/adoptionProof/policy/sources")) {
                        assertTrue(source.path("sourceSha256").asText().matches("[a-f0-9]{64}"));
                        assertFalse(source.path("locator").asText().isBlank());
                    }
                }
            } else {
                assertEquals("NOT_ADOPTED", summary.at("/adoptionProof/status").asText());
                if (scenario.equals("TISSUE_QUALITY")) assertTrue(actual.toString().contains("DENOVO_TARGET_TEST_QUALITY_FAILED"));
            }
            for (String code : List.of("PS2", "PM6")) {
                var row = criterion(response, code); boolean selected = !selection.equals(code.equals("PS2") ? "pm6-only" : "ps2-only");
                if (!selected) { assertTrue(row.isMissingNode()); continue; }
                assertEquals(summary, row.at("/details/deNovoFacts"));
                assertEquals(positive && assigned.equals(code) ? "MET" : "NOT_EVALUATED", row.path("status").asText());
                assertEquals(positive && assigned.equals(code) ? strength : "NONE", row.path("appliedStrength").asText());
            }
            if (sharedQualified == null) { sharedQualified = qualified; sharedProof = summary.path("adoptionProof"); }
            else {
                assertEquals(sharedQualified, qualified, "Other evidence selections must not change shared scientific facts/strength");
                assertEquals(sharedProof, summary.path("adoptionProof"));
            }
            if (!selection.equals("all")) {
                assertEquals(priorQueries, populationQueries(), "Unselected population must not query");
                assertTrue(fixture.factories.isEmpty(), "Unselected scientific factories must not open");
                assertEquals(0, fixture.pp1Evaluations());
            }
            assertReadOnly(request, response);
        }
        assertEquals(4, fixture.evaluations()); assertEquals(1, fixture.readerExecutions(), "One source/context read, then pinned cache reuse");
        assertTrue(fixture.pp1Evaluations() > 0, "Full18 must execute the actual offline PP1 source adapter");
        roundTrip(scenario, results);
    }

    @Test void legacySingleSelectionRemainsRejectedWithoutCreatingRun() throws Exception {
        prepare("TISSUE_BOTH"); var request = request("ps2-only");
        request.put("declarationVersion", AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        var http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(fixture.legacy)).build();
        var response = http.perform(post("/variant-analyses/{id}/acmg/runs", fixture.base.analysisId).contentType("application/json")
                .content(requireNonNull(fixture.base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        assertEquals(400, response.getStatus()); assertEquals("ACMG_SELECTION_REQUIRES_CURRENT_WORKBENCH", response.getErrorMessage());
        assertEquals(0, fixture.evaluations()); assertTrue(fixture.base.store.runs(fixture.base.analysisId).isEmpty());
    }

    private void roundTrip(String scenario, Map<String, ObjectNode> results) throws Exception {
        Path readback = Files.createDirectories(temporary.resolve("reopen")); var base = fixture.base;
        var source = base.store.source(base.analysisId).orElseThrow();
        var manifest = base.mapper.createObjectNode().put("expectedSourceSha256", source.sha256()); var runs = manifest.putArray("runs");
        var artifact = base.mapper.createObjectNode().put("sourceClass", "SYNTHETIC_CURRENT18_SOURCE_QUALIFIED_OWNED_READER")
                .put("clinicalApproval", "NONE").put("noNewVepModelNetwork", true).put("case", scenario).put("configuredCriteriaCount", 18);
        artifact.set("sourceFixture", fixture.source); var publicResults = artifact.putObject("publicResults");
        for (var entry : results.entrySet()) {
            var saved = base.store.run(base.analysisId, entry.getValue().path("runId").asText()).orElseThrow();
            runs.addObject().put("name", entry.getKey()).put("runId", saved.id()); Path folder = Files.createDirectories(readback.resolve(entry.getKey()));
            Files.writeString(folder.resolve("expected-public.json"), saved.responseJson());
            Files.writeString(folder.resolve("expected-private.json"), saved.privateJson());
            Files.writeString(folder.resolve("expected-request.json"), saved.requestJson());
            Files.writeString(folder.resolve("expected-source.json"), source.json());
            assertEquals(AcmgBusinessStore.sha(saved.responseJson()), saved.responseSha256()); publicResults.set(entry.getKey(), entry.getValue());
        }
        Files.write(readback.resolve("manifest.json"), base.mapper.writeValueAsBytes(manifest));
        int evaluations = fixture.evaluations(), readers = fixture.readerExecutions(); String queries = populationQueries(); base.closeDatabase();
        String javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var child = new SystemExternalProcessRunner().run(List.of(javaExe, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DeNovoUnifiedEighteenReopenReader.class.getName(), temporary.toString(), base.analysisId, readback.toString()), temporary, Duration.ofSeconds(60));
        assertTrue(child.isSuccess(), child.getOutput()); assertEquals(evaluations, fixture.evaluations());
        assertEquals(readers, fixture.readerExecutions()); assertEquals(queries, populationQueries());
        var receipt = base.mapper.readTree(Files.readAllBytes(readback.resolve("unified18-reopen-receipt.json")));
        assertTrue(receipt.path("passed").asBoolean()); assertNotEquals(ProcessHandle.current().pid(), receipt.path("processId").asLong());
        var originals = base.mapper.readTree(Files.readAllBytes(readback.resolve("reopen-receipt.json")));
        for (var run : originals.path("runs")) {
            assertTrue(run.at("/sourceOriginals/retainedOriginalCount").asInt() > 0);
            assertTrue(run.at("/sourceOriginals/coreArchiveCount").asInt() > 0);
            assertTrue(run.at("/sourceOriginals/nestedOriginalCount").asInt() > 0);
        }
        for (var entry : results.entrySet()) {
            var folder = readback.resolve(entry.getKey());
            assertEquals(entry.getValue(), base.mapper.readTree(Files.readAllBytes(folder.resolve("full-export.json"))));
            assertEquals(entry.getValue(), base.mapper.readTree(Files.readAllBytes(folder.resolve("controller-full-export.json"))));
        }
        String output = System.getProperty("denovo.unified18.outputDir", "");
        if (!output.isBlank()) {
            Path folder = Files.createDirectories(Path.of(output).resolve(scenario));
            Files.write(folder.resolve("actual-current18-response.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(artifact));
            try (var paths = Files.walk(readback)) {
                for (Path from : paths.filter(Files::isRegularFile).toList()) {
                    Path to = folder.resolve("another-jvm").resolve(readback.relativize(from)); Files.createDirectories(to.getParent());
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
