package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
class Pm6BusinessR1Test {
    Pm6BusinessFixture base;
    @BeforeEach void setup() throws Exception { base = new Pm6BusinessFixture(); base.setup(); }
    @AfterEach void close() throws Exception { base.close(); }
    static ObjectNode at(JsonNode node, String pointer) { return (ObjectNode) node.at(pointer); }

    void deriveMaterial(String gt, boolean phased, String format, String raw) throws Exception {
        base.issue("phase-derived", n -> {
            at(n, "/observations/0/facts/records/0").put("rawSampleValue", raw);
            at(n, "/observations/0/facts/records/0/observation").put("genotype", gt).put("phased", phased);
            at(n, "/observations/0/facts/members/0/detection").put("sampleFormat", format);
        });
    }
    @ParameterizedTest @ValueSource(strings = {"unphased", "record-only", "phase-set"})
    void consistentPublishedPhaseReachesOptionsAndRealCore(String mode) throws Exception {
        boolean phased = !mode.equals("unphased"), ps = mode.equals("phase-set"); String gt = phased ? "0|1" : "0/1";
        base.probandFormat += ps ? ":PS" : ""; base.probandSample = gt + ":40:60:21,19" + (ps ? ":10101" : "");
        base.start("alpha"); deriveMaterial(gt, phased, base.probandFormat, base.probandSample);
        var call = base.jobs.findInterpretations(base.analysisId, 0, 1).get(0).getAnalysisVcfCall();
        assertEquals(ps ? "PHASE_SET" : phased ? "RECORD_ONLY" : "UNPHASED", call.getPhasingStatus().name());
        assertEquals(phased && !ps ? List.of("PS") : List.of(), call.getMissingFields());
        var request = base.request(base.options()); var saved = base.submit(request); var result = saved.at("/candidates/0/attempts/0/pm6");
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText()); assertEquals(1, result.path("deNovoPoints").asInt());
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText()); assertEquals(base.bundle, result.path("sourceBundle"));
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
        String expected = scenario.equals("gt") ? "PM6_PROBAND_GT_CONFLICT" : scenario.equals("phased") ? "PM6_PROBAND_PHASE_CONFLICT" : "PM6_PROBAND_CALL_INCOMPLETE";
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
        assertEquals("PM6_EXECUTION_OR_SAVE_FAILED", failure.getReason()); assertEquals(1, base.core.get());
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
        base.artifact("retry-shutdown", base.mapper.createObjectNode().put("dbRawSha256", Pm6BusinessFixture.sha(Files.readAllBytes(base.temporary.resolve("business-db.mv.db")))));
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
        var result = second.at("/candidates/0/attempts/0/pm6");
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals(material.equals("alpha-unknown") ? "PM6_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE" : "PM6_RAW_FT_CONFLICT", result.at("/evaluationResult/reasonCode").asText());
        assertFalse(result.has("evaluation")); assertFalse(result.has("deNovoPoints")); assertEquals(3, result.at("/criterionEvaluations/0").size());
        assertEquals(first, base.service.read(base.analysisId, first.path("runId").asText())); var before = base.counts();
        assertEquals(second, base.submit(retry)); assertEquals(before, base.counts()); assertEquals(2, base.core.get()); base.artifact("ne-retry-counts", base.counts());
        Files.move(base.material, base.temporary.resolve("material-retained.json")); base.wire();
        assertEquals(second, base.submit(retry)); assertEquals(before, base.counts());
        base.reject(retry.deepCopy().put("retryOf", second.path("runId").asText()), "PM6_MATERIAL_UNAVAILABLE");
        assertEquals(2, base.core.get()); assertEquals(0, base.forbidden.get()); base.artifact("unavailable-new-retry-counts", base.counts());
    }
    @Test void retryReferenceMustExistInCurrentAnalysisAndHaveAllowedTerminalState() throws Exception {
        base.start("alpha"); var request = base.request(base.options());
        base.reject(request.deepCopy().put("retryOf", UUID.randomUUID().toString()), "RETRY_RUN_NOT_FOUND");
        var other = new Pm6BusinessFixture();
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
