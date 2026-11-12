package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SYNTHETIC / TEST_ONLY: only preparer output is supplied to the normal PM4 business chain. */
class Pm4PreparedBusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    Pm4BusinessIntegrationTest shared;
    ReviewedRuleBundle bundle;
    Path outputs;
    @AfterEach void close() { if (base != null) base.closeDatabase(); }
    void wire(Path prepared, String sha) throws Exception {
        bundle = ReviewedRuleBundle.load(base.mapper, prepared.resolve("bundle.json"), sha);
        base.recreateService(() -> { base.invocations.incrementAndGet(); return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources); });
        base.service = AcmgBusinessService.pm4Development(base.service, bundle);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    ObjectNode options() throws Exception {
        return (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId).param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    @ParameterizedTest @ValueSource(strings={"NON_REPEAT", "REPEAT", "NOT_SUPPORTED", "UNKNOWN", "GAP", "INSERTION_NEIGHBOR_GAP", "SEGMENTED"})
    void generatedFilesAreConsumedAndSaved(String scenario) throws Exception {
        String artifactRoot = System.getProperty("varvis.pm4.preparation.artifacts");
        outputs = artifactRoot == null ? temporary.resolve(scenario) : Path.of(artifactRoot).resolve(scenario);
        Files.createDirectories(outputs);
        var inputs = new Pm4PreparationFixtures(outputs.resolve("inputs"));
        var region = inputs.fixtures.get(0).regions;
        switch (scenario) {
            case "REPEAT", "UNKNOWN" -> ((ObjectNode) region.at("/intervals/0")).put("repeatStatus", scenario);
            case "NOT_SUPPORTED" -> ((ObjectNode) region.at("/intervals/0")).put("functionalContext", scenario);
            case "GAP" -> ((ObjectNode) region.at("/intervals/0")).put("start", 107);
            case "INSERTION_NEIGHBOR_GAP", "SEGMENTED" -> {
                ((ObjectNode) region.at("/intervals/0")).put("end", 105);
                region.withArray("intervals").addObject().put("start", scenario.equals("SEGMENTED") ? 106 : 107).put("end", 120)
                        .put("repeatStatus", "NON_REPEAT").put("functionalContext", "SUPPORTED");
            }
            default -> { }
        }
        inputs.publishReviewedInputs(); Path prepared = outputs.resolve("prepared");
        var preparation = inputs.prepare(prepared); assertTrue(preparation.ready(), preparation.report().toPrettyString());
        boolean unknown = Set.of("UNKNOWN", "GAP", "INSERTION_NEIGHBOR_GAP").contains(scenario);
        assertEquals(unknown, preparation.report().at("/backgrounds/0/hasUnknownOrGap").asBoolean());
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        shared = new Pm4BusinessIntegrationTest(); shared.base = base; shared.temporary = temporary;
        base.fixture.annotations = new LinkedHashMap<>();
        base.fixture.annotations.put("public-hbb-compatible-e7v", List.of(base.mapper.treeToValue(inputs.fixtures.get(0)
                .annotation("6_7insGCT", "Glu2_Pro3insAla", "inframe_insertion"), TranscriptConsequence.class)));
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(base.mapper.treeToValue(inputs.fixtures.get(1)
                .annotation("6_7insGCTGCT", "Glu2_Pro3insAlaAla", "inframe_insertion"), TranscriptConsequence.class)));
        base.variantTsvOverride = tsv("1\t11\t105\tA\tAGCT\tHBB\t3043\tAR\t0.8", "2\t13\t105\tA\tAGCTGCT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t105\t.\tA\tAGCT\t.\tPASS\t.\tGT\t.", "chr13\t105\t.\tA\tAGCTGCT\t.\tPASS\t.\tGT\t."));
        shared.tinyReference(); String bundleSha = preparation.report().path("bundleSha256").asText(); wire(prepared, bundleSha);
        var options = options(); var request = shared.confirmedRequest(options); var saved = base.postRequest(request);
        assertEquals(unknown ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED", saved.path("status").asText(), saved.toPrettyString());
        for (int i = 0; i < 2; i++) {
            assertTrue(saved.at("/candidates/" + i + "/enabledRuleResultsComplete").asBoolean());
            JsonNode result = saved.at("/candidates/" + i + "/attempts/0/pm4");
            assertEquals(i + 1, result.at("/resolvedInput/netAminoAcidChange").asInt(), saved.toPrettyString());
            assertEquals(6, result.at("/resolvedInput/referenceProteinLength").asInt());
            assertEquals(7 + i, result.at("/resolvedInput/alteredProteinLength").asInt());
            assertEquals(105, result.at("/resolvedInput/genomicCoverageStart").asInt()); assertEquals(106, result.at("/resolvedInput/genomicCoverageEnd").asInt());
            String expected = i == 1 || scenario.equals("NON_REPEAT") || scenario.equals("SEGMENTED") ? "MET" : unknown ? "NOT_EVALUATED" : "NOT_MET";
            assertEquals(expected, result.at("/evaluation/evaluationResult/status").asText());
            String reason = i == 1 ? "PM4_SUPPORTED_NON_REPEAT_LENGTH_CHANGE" : switch (scenario) {
                case "REPEAT" -> "PM4_REPEAT_REGION"; case "NOT_SUPPORTED" -> "PM4_FUNCTIONAL_CONTEXT_NOT_MET";
                case "UNKNOWN", "GAP", "INSERTION_NEIGHBOR_GAP" -> "PM4_REGION_COVERAGE_INCOMPLETE";
                default -> "PM4_SUPPORTED_NON_REPEAT_LENGTH_CHANGE";
            };
            assertEquals(reason, result.at("/evaluation/evaluationResult/reasonCode").asText());
            assertDigests((ObjectNode) result.path("evaluation"), inputs);
        }
        assertEquals(2, bundle.lengthCalculations()); assertEquals(unknown ? 1 : 2, bundle.pm4EvaluatorCalls());
        artifact("input-options", options); artifact("request", request); artifact("submit", saved);
        if (!scenario.equals("NON_REPEAT")) return;
        String run = saved.path("runId").asText(); base.closeDatabase();
        assertTrue(Files.size(temporary.resolve("business-db.mv.db")) > 0); base.recreateRepositories(); wire(prepared, bundleSha);
        int before = base.invocations.get();
        var reopened = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var reused = base.postRequest(request); assertEquals(saved, reopened); assertEquals(saved, reused);
        assertEquals(1, base.store.runs(base.analysisId).size()); assertEquals(before, base.invocations.get());
        assertEquals(0, bundle.lengthCalculations()); assertEquals(0, bundle.pm4EvaluatorCalls());
        artifact("get-reopened", reopened); artifact("reused", reused);
        artifact("counters", base.mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("fileH2Reopened", true)
                .put("firstLengthCalculations", 2).put("firstEvaluations", 2).put("newLengthCalculations", bundle.lengthCalculations())
                .put("newEvaluations", bundle.pm4EvaluatorCalls()).put("newCoordinatorCalls", base.invocations.get() - before));
        var revised = new Pm4PreparationFixtures(outputs.resolve("inputs-v2"));
        revised.fixtures.get(0).model.put("modelVersion", "2"); revised.publishReviewedInputs();
        var next = revised.prepare(outputs.resolve("prepared-v2")); assertTrue(next.ready());
        assertNotEquals(bundleSha, next.report().path("bundleSha256").asText()); wire(outputs.resolve("prepared-v2"), next.report().path("bundleSha256").asText());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(request)));
        var updated = base.postRequest(shared.confirmedRequest(options())); assertNotEquals(run, updated.path("runId").asText());
        assertEquals("COMPLETED", updated.path("status").asText()); assertEquals(saved, base.service.read(base.analysisId, run)); artifact("updated-pin", updated);
    }
    void assertDigests(ObjectNode value, Pm4PreparationFixtures inputs) {
        var evaluation = value.deepCopy(); var trace = ((ObjectNode) evaluation.path("deterministicTrace")).deepCopy();
        String hash = trace.path("traceSha256").asText(); trace.remove("traceSha256"); assertEquals(hash, inputs.hash(trace));
        String id = evaluation.path("evaluationId").asText(); evaluation.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + inputs.hash(evaluation), id);
    }
    void artifact(String name, JsonNode value) throws Exception { Files.writeString(outputs.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
}
