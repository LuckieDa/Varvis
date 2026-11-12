package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class AcmgExecutionProgressTest {
    @Test void timingsAreObservationOnlyAndFreezeOnCloseEvenWhenWorkFails() {
        var registry = new AcmgExecutionProgress(); var id = UUID.randomUUID().toString();
        try (var session = registry.begin("a", id)) {
            assertEquals("value", AcmgExecutionProgress.measure("LOOKUP", () -> "value"));
            var failure = new IllegalArgumentException("unchanged");
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> AcmgExecutionProgress.measure("LOOKUP", () -> { throw failure; })));
            AcmgExecutionProgress.stage("VERIFY_EVALUATE", "c", "h1", "BP1");
            AcmgExecutionProgress.stage("VERIFY_EVALUATE", "c", "h2", "BP1");
            assertEquals(2, session.snapshot().at("/timing/operations/0/calls").asInt());
            session.complete(new ObjectMapper().createObjectNode().put("status", "COMPLETED"));
        }
        var one = registry.read("a", id);
        assertEquals(one, registry.read("a", id));
        assertFalse(one.path("executionReceipt").asBoolean());
        assertEquals(1, java.util.stream.StreamSupport.stream(one.at("/timing/stages").spliterator(), false)
                .filter(n -> "VERIFY_EVALUATE:BP1".equals(n.path("stage").asText())).count());
    }
    @Test void progressIsIsolatedAndNeverAReceipt() throws Exception {
        var registry = new AcmgExecutionProgress(); var operation = UUID.randomUUID().toString();
        try (var session = registry.begin("analysis", operation)) {
            AcmgExecutionProgress.bindRun("analysis", "run");
            AcmgExecutionProgress.stage("VERIFY_EVALUATE", "candidate", "disease", "PP1");
            var first = registry.read("analysis", operation);
            assertFalse(first.path("executionReceipt").asBoolean());
            assertEquals("PP1", first.path("criterion").asText());
            assertEquals("UNAVAILABLE", registry.read("other", operation).path("status").asText());
            assertEquals("UNAVAILABLE", AcmgExecutionProgress.forRun("other", "run").path("status").asText());
            var pool = Executors.newSingleThreadExecutor();
            try {
                pool.submit(() -> AcmgExecutionProgress.stage("SAVE", null, null, null)).get();
            } finally { pool.shutdownNow(); }
            assertEquals("VERIFY_EVALUATE", AcmgExecutionProgress.forRun("analysis", "run").path("phase").asText());
            first.put("phase", "MUTATED");
            assertEquals("VERIFY_EVALUATE", registry.read("analysis", operation).path("phase").asText());
            session.complete(new ObjectMapper().createObjectNode().put("runId", "run").put("status", "COMPLETED_WITH_BLOCKERS"));
        }
        assertEquals("SAVED", registry.read("analysis", operation).path("phase").asText());
        assertEquals("UNAVAILABLE", AcmgExecutionProgress.forRun("analysis", "run").path("status").asText());
        assertThrows(IllegalArgumentException.class, () -> registry.begin("analysis", operation));
    }
    @Test void interruptedRequestDoesNotClaimCompletedResultAndThreadCanBeReused() {
        var registry = new AcmgExecutionProgress(); var id = UUID.randomUUID().toString();
        try (var session = registry.begin("a", id)) { AcmgExecutionProgress.bindRun("a", "r"); }
        assertEquals("STOPPED", registry.read("a", id).path("status").asText());
        assertEquals("BACKGROUND_VERIFY", registry.read("a", id).path("phase").asText());
        assertFalse(registry.read("a", id).has("reasonCode"));
        try (var next = registry.begin("b", UUID.randomUUID().toString())) {
            AcmgExecutionProgress.stage("SOURCE_RECOVERY", null, null, null);
            assertEquals("b", next.snapshot().path("analysisId").asText());
        }
    }
    @Test void existingRunningResultIsNotReportedSaved() {
        var registry = new AcmgExecutionProgress(); var id = UUID.randomUUID().toString();
        try (var session = registry.begin("a", id)) {
            session.complete(new ObjectMapper().createObjectNode().put("runId", "existing").put("status", "RUNNING"));
        }
        assertEquals("EXISTING_RUN", registry.read("a", id).path("phase").asText());
    }

    @Test void cancellationAndCommittedResultRemainDistinctWhenTheResponseFails() {
        var registry = new AcmgExecutionProgress();
        var cancelled = UUID.randomUUID().toString();
        try (var session = registry.begin("a", cancelled)) {
            AcmgExecutionProgress.stage("VERIFY_EVALUATE", "v", "h", "PVS1");
            session.fail("ACMG_USER_STOPPED");
        }
        var stopped = registry.read("a", cancelled);
        assertEquals("CANCELLED", stopped.path("status").asText());
        assertEquals("VERIFY_EVALUATE", stopped.path("phase").asText());
        assertEquals("ACMG_USER_STOPPED", stopped.path("reasonCode").asText());

        var interrupted = UUID.randomUUID().toString();
        try (var session = registry.begin("a", interrupted)) {
            AcmgExecutionProgress.stage("SAVE", null, null, null);
            AcmgExecutionProgress.committed("a", "saved", "COMPLETED_WITH_BLOCKERS");
            session.fail("ACMG_REQUEST_FAILED");
        }
        var saved = registry.read("a", interrupted);
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
        assertEquals("SAVED", saved.path("phase").asText());
        assertEquals("saved", saved.path("runId").asText());
        assertFalse(saved.has("reasonCode"));
    }
}
