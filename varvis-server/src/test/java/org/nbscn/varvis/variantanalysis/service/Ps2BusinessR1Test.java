package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R1: real micro-VCF publication and actual core/store; only a single finish failure is injected. */
class Ps2BusinessR1Test {
    Ps2BusinessIntegrationTest base;
    @BeforeEach void setup() throws Exception { base = new Ps2BusinessIntegrationTest(); base.setup(); }
    @AfterEach void close() throws Exception { base.close(); }
    static ObjectNode at(JsonNode node, String pointer) { return (ObjectNode) node.at(pointer); }

    void deriveMaterial(String gt, boolean phased, String format, String raw) throws Exception {
        var before = base.bundle.deepCopy();
        at(base.bundle, "/observations/0/facts/records/0").put("rawSampleValue", raw);
        at(base.bundle, "/observations/0/facts/records/0/observation").put("genotype", gt).put("phased", phased);
        at(base.bundle, "/observations/0/facts/members/0/detection").put("sampleFormat", format);
        var facts = at(base.bundle, "/observations/0/facts"); var projection = facts.deepCopy(); projection.remove("sources");
        for (var source : facts.path("sources")) {
            String text = base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projection);
            ((ObjectNode) source).put("rawUtf8", text).put("rawSha256", Ps2BusinessIntegrationTest.sha(text.getBytes(StandardCharsets.UTF_8)))
                    .put("canonicalSha256", base.canonical(projection));
        }
        var assessment = at(base.bundle, "/observations/0/assessment"); assessment.put("factsSha256", base.canonical(facts)); assessment.remove("contentSha256");
        assessment.put("contentSha256", base.canonical(assessment));
        at(base.bundle, "/policy").put("factsSha256", base.canonical(facts)).put("assessmentSha256", base.canonical(assessment));
        for (var entry : base.bundle.at("/catalog/entries")) at(entry, "/policyBinding").put("policySha256", base.canonical(base.bundle.path("policy")));
        base.bundle.put("catalogSha256", base.canonical(base.bundle.path("catalog")));
        assertEquals(before.path("context"), base.bundle.path("context"));
        byte[] bytes = base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(base.bundle);
        base.material = base.temporary.resolve("r1-TEST_ONLY-derived-material.json"); Files.write(base.material, bytes, StandardOpenOption.CREATE_NEW);
        Files.write(base.archive.resolve("r1-derived-material-raw.json"), bytes, StandardOpenOption.CREATE_NEW);
        base.artifact("derivation-before", before); base.artifact("derivation-pins", base.mapper.createObjectNode()
                .put("testOnly", true).put("originalCanonicalSha256", base.canonical(before)).put("derivedRawSha256", Ps2BusinessIntegrationTest.sha(bytes))
                .put("derivedCanonicalSha256", base.canonical(base.bundle)).put("contextUnchanged", true));
        base.pins(bytes); base.wire();
    }
    @ParameterizedTest @ValueSource(strings = {"unphased", "record-only", "phase-set"})
    void consistentPublishedPhaseReachesOptionsAndRealCore(String mode) throws Exception {
        boolean phased = !mode.equals("unphased"), ps = mode.equals("phase-set"); String gt = phased ? "0|1" : "0/1";
        base.probandFormat += ps ? ":PS" : ""; base.probandSample = gt + ":40:60:21,19" + (ps ? ":10101" : "");
        base.start("alpha"); deriveMaterial(gt, phased, base.probandFormat, base.probandSample);
        var call = base.jobs.findInterpretations(base.analysisId, 0, 1).get(0).getAnalysisVcfCall();
        assertEquals(ps ? "PHASE_SET" : phased ? "RECORD_ONLY" : "UNPHASED", call.getPhasingStatus().name());
        assertEquals(phased && !ps ? List.of("PS") : List.of(), call.getMissingFields());
        var request = base.request(base.options()); var saved = base.submit(request); var result = saved.at("/candidates/0/attempts/0/ps2");
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText()); assertEquals(2, result.path("deNovoPoints").asInt());
        assertEquals("STRONG", result.at("/evaluation/appliedStrength").asText()); assertEquals(base.bundle, result.path("sourceBundle"));
        assertEquals(1, base.core.get()); base.rebuildAndCompare(request, saved);
    }
    @ParameterizedTest @ValueSource(strings = {"gt", "phased", "missing-dp", "missing-gq", "missing-ad", "invalid-ad", "invalid-phase"})
    void phaseFixDoesNotIgnoreCriticalMissingInvalidOrMaterialMismatch(String scenario) throws Exception {
        base.probandSample = "0|1:40:60:21,19";
        switch (scenario) {
            case "missing-dp" -> base.probandSample = "0|1:.:60:21,19";
            case "missing-gq" -> base.probandSample = "0|1:40:.:21,19";
            case "missing-ad" -> base.probandSample = "0|1:40:60:.";
            case "invalid-ad" -> base.probandSample = "0|1:40:60:-1,19";
            case "invalid-phase" -> { base.probandFormat += ":PS"; base.probandSample = "0/1:40:60:21,19:10101"; }
            default -> { }
        }
        base.start("alpha");
        deriveMaterial(scenario.equals("gt") ? "0/1" : "0|1", !scenario.equals("phased"), "GT:DP:GQ:AD", "0|1:40:60:21,19");
        String expected = scenario.equals("gt") ? "PS2_PROBAND_GT_CONFLICT" : scenario.equals("phased") ? "PS2_PROBAND_PHASE_CONFLICT" : "PS2_PROBAND_CALL_INCOMPLETE";
        var failure = assertThrows(ResponseStatusException.class, () -> base.service.inputOptions(base.analysisId, 1));
        base.artifact("phase-rejection", base.mapper.createObjectNode().put("scenario", scenario).put("reason", failure.getReason()).set("counts", base.counts()));
        assertEquals(expected, failure.getReason()); assertEquals(0, base.core.get()); assertEquals(0, base.forbidden.get());
    }
    @Test void transientFinishFailureCanExplicitlyRetryThenRebuildWithoutExtraCore() throws Exception {
        base.start("alpha"); var request = base.request(base.options());
        base.store = spy(base.store); var finishes = new AtomicInteger();
        doAnswer(call -> { if (finishes.incrementAndGet() == 1) throw new org.springframework.dao.TransientDataAccessResourceException("TEST_ONLY_ONCE_FINISH");
            return call.callRealMethod(); }).when(base.store).finish(anyString(), anyString(), anyString(), anyString());
        base.wire(); var failure = assertThrows(ResponseStatusException.class, () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(request)));
        assertEquals("PS2_EXECUTION_OR_SAVE_FAILED", failure.getReason()); assertEquals(1, base.core.get());
        var failed = base.store.runs(base.analysisId).get(0); assertEquals("FAILED", failed.status());
        var failedGet = base.service.read(base.analysisId, failed.id()); var beforeRepeat = base.counts();
        assertEquals(failedGet, base.submit(request)); assertEquals(beforeRepeat, base.counts()); assertEquals(1, finishes.get());
        base.artifact("after-failed-finish", base.mapper.createObjectNode().put("runId", failed.id()).put("status", failed.status()).set("counts", base.counts()));
        var retry = request.deepCopy().put("retryOf", failed.id());
        var firstRetryPool = Executors.newFixedThreadPool(2);
        try {
            byte[] raw = base.mapper.writeValueAsBytes(retry);
            var attempts = firstRetryPool.invokeAll(List.of((Callable<ObjectNode>) () -> base.service.submit(base.analysisId, raw), () -> base.service.submit(base.analysisId, raw)));
            assertEquals(attempts.get(0).get().path("runId"), attempts.get(1).get().path("runId"));
            for (int i = 0; i < attempts.size(); i++) base.artifact("concurrent-first-retry-" + i, attempts.get(i).get());
        } finally { firstRetryPool.shutdownNow(); assertTrue(firstRetryPool.awaitTermination(10, TimeUnit.SECONDS)); }
        var saved = base.submit(retry);
        assertEquals("COMPLETED", saved.path("status").asText()); assertNotEquals(failed.id(), saved.path("runId").asText());
        assertEquals(2, base.core.get()); assertEquals(2, finishes.get()); assertEquals(failedGet, base.service.read(base.analysisId, failed.id()));
        var beforeConcurrent = base.counts(); var pool = Executors.newFixedThreadPool(2);
        try {
            byte[] raw = base.mapper.writeValueAsBytes(retry);
            var results = pool.invokeAll(List.of((Callable<ObjectNode>) () -> base.service.submit(base.analysisId, raw), () -> base.service.submit(base.analysisId, raw)));
            for (var result : results) assertEquals(saved, result.get());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(beforeConcurrent, base.counts()); base.artifact("after-concurrent-reuse", base.counts());
        base.artifact("saved-store", base.mapper.readTree(base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().responseJson()));
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN"); assertFalse(Files.exists(base.temporary.resolve("business-db.lock.db")));
        base.artifact("retry-shutdown", base.mapper.createObjectNode().put("dbRawSha256", Ps2BusinessIntegrationTest.sha(Files.readAllBytes(base.temporary.resolve("business-db.mv.db")))));
        base.repositories(); base.wire(); var before = base.counts();
        var get = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, get); base.artifact("retry-rebuilt-get", get);
        assertEquals(saved, base.submit(retry)); assertEquals(before, base.counts()); assertEquals(2, base.store.runs(base.analysisId).size());
        assertEquals(failedGet, base.service.read(base.analysisId, failed.id())); assertEquals(0, base.forbidden.get());
        Files.move(base.material, base.temporary.resolve("material-retained.json")); base.wire();
        assertEquals(saved, base.submit(retry)); assertEquals(before, base.counts());
        base.reject(retry.deepCopy().put("retryOf", saved.path("runId").asText()), "RETRY_REQUIRES_TERMINAL_FAILURE");
        base.artifact("after-rebuild-and-unavailable-reuse", base.counts());
    }
    @ParameterizedTest @ValueSource(strings = {"alpha-unknown", "alpha-ft-conflict"})
    void terminalRetryRemainsMinimalNotEvaluated(String material) throws Exception {
        base.start("alpha"); base.replaceMaterial(material); var request = base.request(base.options()); var first = base.submit(request);
        var retry = request.deepCopy().put("retryOf", first.path("runId").asText()); var second = base.submit(retry);
        assertNotEquals(first.path("runId"), second.path("runId")); assertEquals("COMPLETED_WITH_BLOCKERS", second.path("status").asText());
        var result = second.at("/candidates/0/attempts/0/ps2");
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals(material.equals("alpha-unknown") ? "PS2_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE" : "PS2_RAW_FT_CONFLICT", result.at("/evaluationResult/reasonCode").asText());
        assertFalse(result.has("evaluation")); assertFalse(result.has("deNovoPoints")); assertEquals(3, result.at("/criterionEvaluations/0").size());
        assertEquals(first, base.service.read(base.analysisId, first.path("runId").asText())); var before = base.counts();
        assertEquals(second, base.submit(retry)); assertEquals(before, base.counts()); assertEquals(2, base.core.get()); base.artifact("ne-retry-counts", base.counts());
        Files.move(base.material, base.temporary.resolve("material-retained.json")); base.wire();
        assertEquals(second, base.submit(retry)); assertEquals(before, base.counts());
        base.reject(retry.deepCopy().put("retryOf", second.path("runId").asText()), "PS2_MATERIAL_UNAVAILABLE");
        assertEquals(2, base.core.get()); assertEquals(0, base.forbidden.get()); base.artifact("unavailable-new-retry-counts", base.counts());
    }
    @Test void retryReferenceMustExistInCurrentAnalysisAndHaveAllowedTerminalState() throws Exception {
        base.start("alpha"); var request = base.request(base.options());
        base.reject(request.deepCopy().put("retryOf", UUID.randomUUID().toString()), "RETRY_RUN_NOT_FOUND");
        var other = new Ps2BusinessIntegrationTest();
        other.temporary = Files.createDirectory(base.temporary.resolve("other-analysis"));
        other.archive = Files.createDirectory(base.archive.resolve("other-analysis"));
        other.properties = base.properties; other.dataSource = base.dataSource; other.repositories();
        other.start("beta"); var otherSaved = other.submit(other.request(other.options()));
        assertTrue(base.store.run(other.analysisId, otherSaved.path("runId").asText()).isPresent());
        base.reject(request.deepCopy().put("retryOf", otherSaved.path("runId").asText()), "RETRY_RUN_NOT_FOUND");
        String runningId = UUID.randomUUID().toString();
        assertTrue(base.store.claim(new AcmgBusinessStore.Run(runningId, base.analysisId, "0".repeat(64), "{}", "RUNNING", "2026-09-28T00:00:00Z", null, null, null)));
        base.reject(request.deepCopy().put("retryOf", runningId), "RETRY_REQUIRES_TERMINAL_FAILURE");
        assertEquals(0, base.core.get()); var completed = base.submit(request);
        base.reject(request.deepCopy().put("retryOf", completed.path("runId").asText()), "RETRY_REQUIRES_TERMINAL_FAILURE");
        assertEquals(1, base.core.get()); base.artifact("retry-state-gates", base.counts());
    }
}
