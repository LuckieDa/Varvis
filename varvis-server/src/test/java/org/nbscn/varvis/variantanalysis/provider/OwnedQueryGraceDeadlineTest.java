package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic owner scheduling fixtures, with no native processes or source execution. */
class OwnedQueryGraceDeadlineTest {
    @TempDir Path work;

    private OwnedQueryExecution.CommandSpec command() {
        return new OwnedQueryExecution.CommandSpec(List.of("mechanical-no-launch"), work, Map.of(),
                work.resolve("out"), work.resolve("err"), "grace-deadline-contract", Map.of(), true, true, 250);
    }

    @Test void finalPollFindingAnEmptyJobAfterDeadlineCannotPublishSuccessWithoutAWaitingCaller() throws Exception {
        var ticketRef = new AtomicReference<OwnedQueryExecution.Ticket>();
        var launched = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> new Stub() {
            public void launch(OwnedQueryExecution.CommandSpec spec) {
                try { launched.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            public int activeProcesses() {
                if (reads.incrementAndGet() < 3) return 1;
                var ticket = java.util.Objects.requireNonNull(ticketRef.get());
                long remaining = ticket.deadline + TimeUnit.MILLISECONDS.toNanos(10) - System.nanoTime();
                if (remaining > 0) {
                    try { TimeUnit.NANOSECONDS.sleep(remaining); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return 0;
            }
        });
        try {
            var ticket = pool.launch(command(), 300, 1); ticketRef.set(ticket); launched.countDown();
            // await() must not be what supplies the deadline cancellation in this counterexample.
            var outcome = ticket.done.get(3, TimeUnit.SECONDS);
            assertEquals("TIMEOUT", java.util.Objects.requireNonNull(ticket.cancellation.get()).trigger());
            assertEquals(-1, outcome.exitCode());
            assertEquals(0, ticket.receipt().get("rootExitCodeObserved"));
            assertTrue(ticket.terminationRequested); assertTrue(ticket.exitConfirmed);
            assertEquals(0, ticket.active); assertEquals(0, pool.occupiedSlots());
            assertEquals(true, ticket.receipt().get("cleanupWithinBudget"));
        } finally { launched.countDown(); pool.shutdown(); }
    }

    @Test void cancellationDuringFinalPollCannotBecomeSuccessWhenThePollReturnsZero() throws Exception {
        for (String trigger : List.of("INTERRUPTED", "USER_CANCELLED")) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var reads = new AtomicInteger();
            var pool = new OwnedQueryExecution.OwnerPool(1, () -> new Stub() {
                public int activeProcesses() {
                    if (reads.incrementAndGet() < 3) return 1;
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return 0;
                }
            });
            var ticket = pool.launch(command(), 3000, 1);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                ticket.cancel(trigger); release.countDown();
                assertEquals(-1, ticket.done.get(2, TimeUnit.SECONDS).exitCode());
                assertEquals(trigger, java.util.Objects.requireNonNull(ticket.cancellation.get()).trigger());
                assertTrue(ticket.exitConfirmed); assertTrue(ticket.terminationRequested);
                assertEquals(0, pool.occupiedSlots());
            } finally { release.countDown(); ticket.done.get(2, TimeUnit.SECONDS); pool.shutdown(); }
        }
    }

    private static class Stub implements OwnedQueryExecution.NativeExecution {
        public void launch(OwnedQueryExecution.CommandSpec command) { }
        public void resume() { }
        public boolean rootExited(int waitMillis) { return true; }
        public int exitCode() { return 0; }
        public int activeProcesses() { return 0; }
        public void terminate() { }
        public void close() { }
        public Map<String, Object> diagnostics(String stage) {
            return Map.of("stage", stage, "members", List.of(Map.of("jobBound", true, "signaled", false)));
        }
    }
}
