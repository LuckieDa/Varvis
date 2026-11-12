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
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R1: real micro-VCF publication and actual core/store; only a single finish failure is injected. */
class Bs4BusinessRetryTest {
    Bs4BusinessFixture base;
    @BeforeEach void setup() throws Exception { base = new Bs4BusinessFixture(); base.setup(); }
    @AfterEach void close() throws Exception { base.close(); }
    static ObjectNode at(JsonNode node, String pointer) { return (ObjectNode) node.at(pointer); }

    @Test void blockedRunCanRetryWithNewPinnedMaterialAndFreshConfirmation() throws Exception {
        base.start("alpha-1"); var request=base.request(base.options()); var blocked=base.submit(request);
        assertEquals("COMPLETED_WITH_BLOCKERS",blocked.path("status").asText());
        byte[] repaired;
        try(var input=getClass().getResourceAsStream("/bs4-nonsegregation-core/alpha-2-bundle.json")) {
            assertNotNull(input); repaired=input.readAllBytes();
        }
        base.saveNew("new-reviewed-material",repaired);
        base.reject(request.deepCopy().put("retryOf",blocked.path("runId").asText()),"BS4_SELECTION_STALE");
        var retry=base.request(base.options()).put("retryOf",blocked.path("runId").asText());
        var saved=base.submit(retry); assertEquals("COMPLETED",saved.path("status").asText());
        assertNotEquals(blocked.path("runId"),saved.path("runId")); assertEquals(2,base.core.get());
        assertEquals(blocked,base.service.read(base.analysisId,blocked.path("runId").asText()));
        var before=base.counts(); assertEquals(saved,base.submit(retry)); assertEquals(before,base.counts());
        base.verifyArtifact(saved); base.artifact("changed-pins-retry",saved);
    }

    @Test void transientFinishFailureCanExplicitlyRetryThenRebuildWithoutExtraCore() throws Exception {
        base.start("alpha-2"); var request = base.request(base.options());
        base.store = spy(base.store); var finishes = new AtomicInteger();
        doAnswer(call -> { if (finishes.incrementAndGet() == 1) throw new org.springframework.dao.TransientDataAccessResourceException("TEST_ONLY_ONCE_FINISH");
            return call.callRealMethod(); }).when(base.store).finish(anyString(), anyString(), anyString(), anyString());
        base.wire(); var failure = assertThrows(ResponseStatusException.class, () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(request)));
        assertEquals("BS4_EXECUTION_OR_SAVE_FAILED", failure.getReason()); assertEquals(1, base.core.get());
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
        base.artifact("retry-shutdown", base.mapper.createObjectNode().put("dbRawSha256", Bs4BusinessFixture.sha(Files.readAllBytes(base.temporary.resolve("business-db.mv.db")))));
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
    @Test void retryReferenceMustExistInCurrentAnalysisAndHaveAllowedTerminalState() throws Exception {
        base.start("alpha-2"); var request = base.request(base.options());
        base.reject(request.deepCopy().put("retryOf", UUID.randomUUID().toString()), "RETRY_RUN_NOT_FOUND");
        var other = new Bs4BusinessFixture();
        other.temporary = Files.createDirectory(base.temporary.resolve("other-analysis"));
        other.archive = Files.createDirectory(base.archive.resolve("other-analysis"));
        other.properties = base.properties; other.dataSource = base.dataSource; other.repositories();
        other.start("beta-3"); var otherSaved = other.submit(other.request(other.options()));
        assertTrue(base.store.run(other.analysisId, otherSaved.path("runId").asText()).isPresent());
        base.reject(request.deepCopy().put("retryOf", otherSaved.path("runId").asText()), "RETRY_RUN_NOT_FOUND");
        String runningId = UUID.randomUUID().toString();
        assertTrue(base.store.claim(new AcmgBusinessStore.Run(runningId, base.analysisId, "0".repeat(64), "{}", "RUNNING", "2026-09-28T00:00:00Z", null, null, null)));
        base.reject(request.deepCopy().put("retryOf", runningId), "RETRY_REQUIRES_TERMINAL_FAILURE");
        assertEquals(0, base.core.get()); var completed = base.submit(request);
        base.reject(request.deepCopy().put("retryOf", completed.path("runId").asText()), "RETRY_REQUIRES_TERMINAL_FAILURE");
        assertEquals(1, base.core.get()); base.artifact("retry-state-gates", base.counts());
    }

    @Test void concurrentFirstRequestClaimsOnce() throws Exception {
        base.start("alpha-2");var request=base.request(base.options());byte[] raw=base.mapper.writeValueAsBytes(request);var pool=Executors.newFixedThreadPool(2);
        try {var futures=pool.invokeAll(List.of((Callable<ObjectNode>)()->base.service.submit(base.analysisId,raw),()->base.service.submit(base.analysisId,raw)));assertEquals(futures.get(0).get().path("runId"),futures.get(1).get().path("runId"));}
        finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        var saved=base.submit(request);assertEquals("COMPLETED",saved.path("status").asText());assertEquals(1,base.core.get());assertEquals(1,base.store.runs(base.analysisId).size());base.artifact("first-concurrent-counts",base.counts());
    }
}
