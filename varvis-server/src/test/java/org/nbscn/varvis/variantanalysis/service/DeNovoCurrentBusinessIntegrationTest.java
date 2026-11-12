package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.DeNovoSelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;

/** Actual CURRENT source-qualified/owned-reader/HTTP/H2 chain. Sources, reference and upstream transport are synthetic. */
class DeNovoCurrentBusinessIntegrationTest {
    @TempDir(factory = org.nbscn.varvis.variantanalysis.provider.DeNovoCurrentTestDirectory.class, cleanup = CleanupMode.ON_SUCCESS) Path temporary;
    private DeNovoCurrentBusinessFixture fixture;
    @AfterEach void close() { if (fixture != null) fixture.close(); }

    private DeNovoCurrentBusinessFixture prepare(String kind, String scenario) throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        Path scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required; skip is not source-chain success");
        fixture = new DeNovoCurrentBusinessFixture(temporary, python, scripts);
        fixture.prepare(kind, scenario);
        return fixture;
    }
    private int evaluations() { return fixture.modules.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum(); }
    private int sourceExecutions() { return fixture.readers.stream().mapToInt(r -> r.executions()).sum(); }
    private String populationQueries() throws Exception {
        Path path = temporary.resolve("queries.txt"); return Files.exists(path) ? Files.readString(path) : "";
    }
    private JsonNode module(ObjectNode response) throws Exception {
        var run = fixture.base.store.run(fixture.base.analysisId, response.path("runId").asText()).orElseThrow();
        return fixture.base.mapper.readTree(run.privateJson()).at("/localBatch/modules/deNovo/results/0");
    }
    private JsonNode publicCriterion(ObjectNode response, String code) {
        for (var criterion : response.at("/candidates/0/attempts/0/localBatch/criteria"))
            if (("ACMG_AMP_2015:" + code).equals(criterion.path("criterionId").asText())) return criterion;
        return fixture.base.mapper.missingNode();
    }
    private Set<String> criterionIds(ObjectNode response) {
        var ids = new LinkedHashSet<String>();
        for (var criterion : response.at("/candidates/0/attempts/0/localBatch/criteria")) ids.add(criterion.path("criterionId").asText());
        return ids;
    }
    private ObjectNode request(String selection) {
        var request = fixture.base.request.deepCopy(); request.remove("selectedCriteria");
        if (!"all".equals(selection)) {
            var ids = request.putArray("selectedCriteria");
            if (!"pm6-only".equals(selection)) ids.add("ACMG_AMP_2015:PS2");
            if (!"ps2-only".equals(selection)) ids.add("ACMG_AMP_2015:PM6");
        }
        return request;
    }
    private ObjectNode submit(ObjectNode request) throws Exception {
        var http = fixture.base.http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/variant-analyses/{id}/acmg/runs", fixture.base.analysisId).param("workflow", "CURRENT")
                .contentType("application/json").content(requireNonNull(fixture.base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        assertEquals(200, http.getStatus(), http.getErrorMessage() + " " + http.getContentAsString());
        return (ObjectNode) fixture.base.mapper.readTree(http.getContentAsByteArray());
    }
    private void assertReadOnly(ObjectNode request, ObjectNode result) throws Exception {
        int before = evaluations(), readers = sourceExecutions(); String population = populationQueries();
        var saved = fixture.base.store.run(fixture.base.analysisId, result.path("runId").asText()).orElseThrow();
        assertEquals(result, submit(request));
        assertEquals(result, fixture.base.service.read(fixture.base.analysisId, result.path("runId").asText()));
        var response = fixture.base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",
                fixture.base.analysisId, result.path("runId").asText())).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals(result, fixture.base.mapper.readTree(response.getContentAsByteArray()));
        assertEquals(saved, fixture.base.store.run(fixture.base.analysisId, result.path("runId").asText()).orElseThrow());
        assertEquals(before, evaluations(), "Repeat/history must not evaluate de novo again");
        assertEquals(readers, sourceExecutions(), "Repeat/history must not reexecute the owned Python reader");
        assertEquals(population, populationQueries(), "Repeat/history must not query population again");
    }

    @ParameterizedTest
    @CsvSource({"JSON,TISSUE_BOTH,STRONG,PS2,0,true", "JATS,TISSUE_C1,MODERATE,PS2,0,true",
            "JSON,TISSUE_C2,MODERATE,PS2,0,false", "JATS,COMBINED_ASSUMED,SUPPORTING,PS2,2,false",
            "JSON,GENERAL_ASSUMED,SUPPORTING,PM6,2,false"})
    void currentSinglePairAllPreserveQualifiedMethodsAndAnotherJvmHistory(String kind, String scenario, String strength,
            String assigned, int points, boolean confirmed) throws Exception {
        var f = prepare(kind, scenario); var runs = new LinkedHashMap<String, ObjectNode>();
        assertEquals("READY_FOR_EVALUATION", f.source.at("/core/status").asText(), f.source.toPrettyString());
        assertEquals("NOT_RUN", f.source.at("/core/criterionDecision").asText());
        assertTrue(f.base.service.configuredCriteria().containsAll(DeNovoSelectedBatch.CRITERIA));
        JsonNode sharedProof = null, sharedQualified = null;
        for (String selection : List.of("ps2-only", "pm6-only", "pair", "all")) {
            int before = evaluations(); String beforePopulation = populationQueries(); var request = request(selection);
            var response = submit(request); runs.put(selection, response);
            assertEquals(before + 1, evaluations(), "Exactly one evaluation per selected variant/background");
            var actual = module(response); var summary = actual.path("publicSummary");
            assertEquals("denovo-local-evidence-1.4.1", actual.at("/qualifiedResult/version").asText());
            assertEquals("denovo-local-evidence-1.4.1", response.at("/executionProfile/moduleBindings/deNovo/qualifierVersion").asText(),
                    "The repaired qualifier must have a distinct actual request/cache identity");
            assertEquals("MET", actual.at("/qualifiedResult/result/status").asText(), actual.toPrettyString());
            assertEquals(assigned, actual.at("/qualifiedResult/result/code").asText());
            assertEquals(strength, actual.at("/qualifiedResult/result/strength").asText());
            assertEquals(points, summary.path("effectiveQuarterPoints").asInt());
            assertEquals(confirmed, summary.at("/events/0/confirmedParentage").asBoolean());
            assertEquals("NOT_RUN", summary.at("/coreMaterials/0/criterionDecision").asText());
            assertEquals("EXACT", summary.at("/coreMaterials/0/selectionMatch").asText());
            assertEquals("NOT_RUN", summary.at("/coreMaterials/0/sourceDiscovery/criterionDecision").asText());
            assertEquals(scenario.equals("GENERAL_ASSUMED") ? "NOT_APPLICABLE" : "ADOPTED", summary.at("/adoptionProof/status").asText());
            assertEquals("COMPLETE", summary.at("/adoptionProof/projectionStatus").asText());
            if (scenario.equals("COMBINED_ASSUMED")) {
                assertEquals("SVI_1_1_COMBINED_PS2", summary.at("/adoptionProof/policy/eventMode").asText());
                assertFalse(summary.at("/adoptionProof/events/0/combined/confirmedParentage").asBoolean(true));
                assertEquals("ASSUMED_NOT_TESTED", summary.at("/adoptionProof/events/0/combined/maternity").asText());
            } else if (!scenario.equals("GENERAL_ASSUMED")) {
                assertEquals("EXPERT_FIXED_STRENGTH", summary.path("calculationMethod").asText());
                assertEquals("NO_SVI_POINT_SUM", summary.path("strengthAdjustment").asText());
                assertEquals(strength, summary.at("/adoptionProof/events/0/adoptedStrength").asText());
            }
            Set<String> wanted = new LinkedHashSet<>();
            if (selection.equals("all")) wanted.addAll(f.base.service.configuredCriteria());
            else for (var id : request.path("selectedCriteria")) wanted.add(id.asText());
            assertEquals(wanted, Set.copyOf(fixture.base.mapper.convertValue(response.path("enabledCriteria"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})));
            var wantedLocal = new LinkedHashSet<>(wanted); wantedLocal.removeAll(Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1"));
            assertEquals(wantedLocal, criterionIds(response), "No unselected local evidence row may be emitted");
            for (String code : List.of("PS2", "PM6")) {
                var criterion = publicCriterion(response, code);
                if (!wanted.contains("ACMG_AMP_2015:" + code)) { assertTrue(criterion.isMissingNode()); continue; }
                assertEquals(summary, criterion.at("/details/deNovoFacts"), "Actual public result must preserve the exact module proof");
                assertEquals(assigned.equals(code) ? "MET" : "NOT_EVALUATED", criterion.path("status").asText());
                assertEquals(assigned.equals(code) ? strength : "NONE", criterion.path("appliedStrength").asText());
            }
            for (var binding : summary.path("calculationBindings")) {
                assertFalse(binding.path("declaredCalculatorVersion").asText().isBlank());
                assertEquals(binding.path("runtimeCalculatorVersion"), binding.path("executedCalculatorVersion"));
            }
            assertEquals(selection.endsWith("only") ? 1 : 2, actual.path("criteria").size());
            if (!selection.equals("all")) assertEquals(beforePopulation, populationQueries(), "Unselected population sources must not be queried");
            if (sharedProof == null) { sharedProof = summary.path("adoptionProof"); sharedQualified = actual.path("qualifiedResult"); }
            else {
                assertEquals(sharedProof, summary.path("adoptionProof"));
                assertEquals(sharedQualified, actual.path("qualifiedResult"), "Choice must not change source-qualified facts");
            }
            assertReadOnly(request, response);
        }
        assertEquals(4, evaluations()); assertEquals(1, sourceExecutions(), "The same source/context is read once, then source-bound cache reused");
        roundTrip(kind + "-" + scenario, runs);
    }

    @ParameterizedTest
    @CsvSource({"JSON,TISSUE_QUALITY", "JSON,TISSUE_CROSS_TRIO", "JSON,COMBINED_EXCLUDED",
            "JSON,TISSUE_ORDINARY_PARENT_PRESENT", "JSON,TISSUE_UNKNOWN", "JSON,TISSUE_WRONG_TARGET",
            "JSON,TISSUE_C2_NO_DIFFERENTIAL"})
    void currentSourceCounterEvidenceAndUnknownsRemainBlockedAfterPersistence(String kind, String scenario) throws Exception {
        prepare(kind, scenario);
        if (scenario.equals("TISSUE_CROSS_TRIO"))
            assertNotEquals(fixture.source.at("/core/specialEvent/personId"), fixture.source.at("/core/specialEvent/trio/personId"),
                    "The original used by this counterexample must actually contain the independent contradictory trio subject");
        var request = request("pair"); var result = submit(request); var actual = module(result);
        String expected = scenario.equals("TISSUE_C2_NO_DIFFERENTIAL") ? "NOT_MET" : "NOT_EVALUATED";
        assertEquals(expected, actual.at("/qualifiedResult/result/status").asText(), actual.toPrettyString());
        assertEquals("NONE", actual.at("/qualifiedResult/result/code").asText());
        assertEquals("NOT_ADOPTED", actual.at("/publicSummary/adoptionProof/status").asText());
        if (scenario.equals("TISSUE_CROSS_TRIO")) assertTrue(actual.toString().contains("DENOVO_EVENT_PERSON_TRIO_CONFLICT"));
        if (scenario.equals("TISSUE_QUALITY")) assertTrue(actual.toString().contains("DENOVO_TARGET_TEST_QUALITY_FAILED"));
        if (scenario.equals("COMBINED_EXCLUDED")) assertTrue(actual.toString().contains("DENOVO_PARENTAGE_EXCLUDED"));
        if (scenario.equals("TISSUE_UNKNOWN")) assertTrue(actual.toString().contains("DENOVO_TISSUE_PARENTAL_FACTS_REQUIRED"));
        for (String code : List.of("PS2", "PM6")) {
            assertEquals(expected, publicCriterion(result, code).path("status").asText());
            assertEquals("NONE", publicCriterion(result, code).path("appliedStrength").asText());
        }
        assertReadOnly(request, result); assertEquals(1, evaluations());
        roundTrip(kind + "-" + scenario, Map.of("pair", result));
    }

    @Test void legacySelectedCriteriaGuardIsUnchangedByCurrentAssembly() throws Exception {
        prepare("JSON", "TISSUE_BOTH"); var request = request("ps2-only");
        request.put("declarationVersion", org.nbscn.varvis.variantanalysis.provider.AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        var legacy = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController(fixture.legacy)).build();
        var response = legacy.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/variant-analyses/{id}/acmg/runs", fixture.base.analysisId).contentType("application/json")
                .content(requireNonNull(fixture.base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        assertEquals(400, response.getStatus()); assertEquals("ACMG_SELECTION_REQUIRES_CURRENT_WORKBENCH", response.getErrorMessage());
        assertEquals(0, evaluations()); assertTrue(fixture.base.store.runs(fixture.base.analysisId).isEmpty());
    }

    private void roundTrip(String caseName, Map<String, ObjectNode> results) throws Exception {
        var f = fixture; Path readback = temporary.resolve("reopen"); Files.createDirectories(readback);
        var manifest = f.base.mapper.createObjectNode(); var source = f.base.store.source(f.base.analysisId).orElseThrow();
        manifest.put("expectedSourceSha256", source.sha256()); var runs = manifest.putArray("runs");
        var artifact = f.base.mapper.createObjectNode().put("sourceClass", "SYNTHETIC_CURRENT_SOURCE_QUALIFIED_OWNED_READER")
                .put("clinicalApproval", "NONE").put("noNewVepModelNetwork", true).put("case", caseName);
        artifact.set("sourceFixture", f.source); var publicResults = artifact.putObject("publicResults");
        for (var entry : results.entrySet()) {
            var response = entry.getValue(); var saved = f.base.store.run(f.base.analysisId, response.path("runId").asText()).orElseThrow();
            runs.addObject().put("name", entry.getKey()).put("runId", saved.id());
            Path folder = readback.resolve(entry.getKey()); Files.createDirectories(folder);
            Files.writeString(folder.resolve("expected-public.json"), saved.responseJson());
            Files.writeString(folder.resolve("expected-private.json"), saved.privateJson());
            Files.writeString(folder.resolve("expected-request.json"), saved.requestJson());
            Files.writeString(folder.resolve("expected-source.json"), source.json()); publicResults.set(entry.getKey(), response);
            assertEquals(AcmgBusinessStore.sha(saved.responseJson()), saved.responseSha256());
        }
        Files.write(readback.resolve("manifest.json"), f.base.mapper.writeValueAsBytes(manifest));
        int evaluated = evaluations(), readers = sourceExecutions(); String queries = populationQueries();
        f.base.closeDatabase();
        String javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var child = new SystemExternalProcessRunner().run(List.of(javaExe, "-cp", System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")), DeNovoCurrentBusinessReopenReader.class.getName(), temporary.toString(),
                f.base.analysisId, readback.toString()), temporary, Duration.ofSeconds(45));
        assertTrue(child.isSuccess(), child.getOutput());
        assertEquals(evaluated, evaluations()); assertEquals(readers, sourceExecutions()); assertEquals(queries, populationQueries());
        for (var entry : results.entrySet()) {
            Path folder = readback.resolve(entry.getKey());
            assertEquals(entry.getValue(), f.base.mapper.readTree(Files.readAllBytes(folder.resolve("full-export.json"))));
            assertEquals(entry.getValue(), f.base.mapper.readTree(Files.readAllBytes(folder.resolve("controller-full-export.json"))));
        }
        String output = System.getProperty("denovo.current.outputDir", "");
        if (!output.isBlank()) {
            Path folder = Path.of(output).resolve(caseName); Files.createDirectories(folder);
            Files.write(folder.resolve("actual-current-response.json"), f.base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(artifact));
            try (var files = Files.walk(readback)) {
                for (Path from : files.filter(Files::isRegularFile).toList()) {
                    Path to = folder.resolve("another-jvm").resolve(readback.relativize(from)); Files.createDirectories(to.getParent()); Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
