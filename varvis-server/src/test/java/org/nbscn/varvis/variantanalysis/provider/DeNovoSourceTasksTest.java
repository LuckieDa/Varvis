package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic scheduling, not literature or clinical validation. No network. */
class DeNovoSourceTasksTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    String id(String value) { return FamilyDeNovoJson.sha256(bytes(value)); }
    DeNovoSourceTasks.Read done(DeNovoSourceTasks tasks, String key) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<deadline) {
            var value=tasks.read(root,key);
            if (!java.util.Set.of("RUNNING","QUEUED").contains(value.status().path("status").asText())) return value;
            Thread.sleep(10);
        }
        fail("Owned synthetic task did not finish"); return null;
    }
    @Test void batchIdentityBeyond64KiBIsBoundedAndReusable() throws Exception {
        byte[] identity=bytes("x".repeat(80000));
        try(var tasks=new DeNovoSourceTasks(1,1)) {
            String key=tasks.start(root,identity,remaining->bytes("{}")).status().path("taskId").asText();
            assertEquals("READY",done(tasks,key).status().path("status").asText());
            assertNotNull(tasks.start(root,identity,remaining->{fail("duplicate execution");return null;}).result());
            assertThrows(java.io.IOException.class,()->tasks.start(root,new byte[DeNovoSourceTasks.MAX_IDENTITY+1],remaining->bytes("{}")));
        }
    }
    @Test void slowSourceReturnsImmediatelyAndDuplicatePollingNeverStartsWork() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var count=new AtomicInteger();
        try (var tasks=new DeNovoSourceTasks(1,2)) {
            var first=assertTimeoutPreemptively(Duration.ofSeconds(2),()->tasks.start(root,bytes("one"),remaining->{
                count.incrementAndGet();entered.countDown();release.await();return bytes("{\"synthetic\":true}");
            }));
            assertNull(first.result());assertTrue(entered.await(2,TimeUnit.SECONDS));
            for (int i=0;i<4;i++) {
                assertNull(tasks.start(root,bytes("one"),remaining->{fail("duplicate launched");return null;}).result());
                assertNull(tasks.read(root,id("one")).result());
            }
            assertEquals(1,count.get());release.countDown();assertEquals("READY",done(tasks,id("one")).status().path("status").asText());
            assertNull(first.result()); // Historical pending receipt remains frozen.
        }
        try (var restarted=new DeNovoSourceTasks(1,1)) {
            assertArrayEquals(bytes("{\"synthetic\":true}"),restarted.start(root,bytes("one"),remaining->{fail("restart re-executed");return null;}).result());
        }
    }
    @Test void boundedQueueRejectsExtraJobAndCancellationReleasesQueuedOwnership() throws Exception {
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);var queuedCalls=new AtomicInteger();
        var tasks=new DeNovoSourceTasks(1,1);
        try {
            tasks.start(root,bytes("running"),remaining->{entered.countDown();gate.await();return bytes("{}");});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            tasks.start(root,bytes("queued"),remaining->{queuedCalls.incrementAndGet();return bytes("{}");});
            assertEquals("DENOVO_TASK_QUEUE_FULL",tasks.start(root,bytes("full"),remaining->bytes("{}")).status().path("reasonCode").asText());
        } finally { tasks.close(); }
        assertEquals(0,queuedCalls.get());
        assertEquals("CANCELLED",tasks.read(root,id("running")).status().path("status").asText());
        assertEquals("DENOVO_TASK_CANCELLED",tasks.read(root,id("queued")).status().path("reasonCode").asText());
    }
    @Test void explicitModelCancellationKeepsOriginalsAndNeverRetries() throws Exception {
        var entered=new CountDownLatch(1);var queuedCalls=new AtomicInteger();
        try(var tasks=new DeNovoSourceTasks(1,1,5000,true)) {
            tasks.start(root,bytes("cancel-running"),remaining->{entered.countDown();new CountDownLatch(1).await();return bytes("{}");});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            tasks.start(root,bytes("cancel-queued"),remaining->{queuedCalls.incrementAndGet();return bytes("{}");});
            assertEquals("CANCELLED",tasks.cancel(root,id("cancel-queued")).status().path("status").asText());
            tasks.cancel(root,id("cancel-running"));
            assertEquals("CANCELLED",done(tasks,id("cancel-running")).status().path("status").asText());
            assertFalse(tasks.read(root,id("cancel-running")).status().path("canRetry").asBoolean());
            assertEquals(0,queuedCalls.get());
            assertTrue(Files.isRegularFile(root.resolve(id("cancel-running")).resolve("identity.json")));
        }
        try(var restarted=new DeNovoSourceTasks(1,1,5000,true)) {
            assertEquals("CANCELLED",restarted.start(root,bytes("cancel-running"),remaining->{fail("cancelled paid job repeated");return null;}).status().path("status").asText());
        }
    }
    @Test void deadlineIncludesWaitingAndInterruptsOwnedWork() throws Exception {
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var queued=new AtomicInteger();
        try (var tasks=new DeNovoSourceTasks(1,1,300)) {
            tasks.start(root,bytes("slow"),remaining->{entered.countDown();try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown();throw expected; }return bytes("{}");});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            // The second task may start just after the first timeout; it gets only its remaining budget.
            tasks.start(root,bytes("second"),remaining->{queued.incrementAndGet();assertTrue(remaining.toMillis()<300);new CountDownLatch(1).await();return bytes("{}");});
            assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            assertEquals("DENOVO_TASK_TIMEOUT",done(tasks,id("slow")).status().path("reasonCode").asText());
            var second=done(tasks,id("second")).status();
            assertTrue(java.util.Set.of("DENOVO_TASK_TIMEOUT","DENOVO_TASK_QUEUE_TIMEOUT").contains(second.path("reasonCode").asText()),second.toString());
        }
    }
    @Test void staleRunningReceiptIsInterruptedNotAutomaticallyRestarted() throws Exception {
        String key=id("stale");Path dir=Files.createDirectory(root.resolve(key));Files.write(dir.resolve("identity.json"),bytes("stale"));
        Files.write(dir.resolve("owner.lock"),new byte[0]);
        Files.write(dir.resolve("state.running.json"),mapper.writeValueAsBytes(mapper.createObjectNode().put("schemaVersion",DeNovoSourceTasks.VERSION).put("taskId",key).put("status","RUNNING").put("scientificExecution",false).put("modelCalls",0)));
        try(var tasks=new DeNovoSourceTasks(1,1)) {
            assertEquals("DENOVO_TASK_INTERRUPTED",tasks.start(root,bytes("stale"),remaining->{fail("stale task relaunched");return null;}).status().path("reasonCode").asText());
        }
    }
    @Test void preciseSourceFailureIsRetainedAndReadDoesNotRetry() throws Exception {
        try(var tasks=new DeNovoSourceTasks(1,1)) {
            tasks.start(root,bytes("failed"),remaining->{throw new DeNovoSourceTasks.SourceFailure("DENOVO_SUPPLY_PROCESS_TIMEOUT");});
            assertEquals("DENOVO_SUPPLY_PROCESS_TIMEOUT",done(tasks,id("failed")).status().path("reasonCode").asText());
            assertNull(tasks.start(root,bytes("failed"),remaining->{fail("failure retried");return null;}).result());
        }
    }
    @Test void changedResultAndIdentityCannotBeAdopted() throws Exception {
        try(var tasks=new DeNovoSourceTasks(1,1)) {
            tasks.start(root,bytes("tamper"),remaining->bytes("{}"));done(tasks,id("tamper"));
            Files.writeString(root.resolve(id("tamper")).resolve("result.json"),"{\"changed\":true}");
            assertEquals("DENOVO_TASK_RESULT_MISMATCH",tasks.read(root,id("tamper")).status().path("reasonCode").asText());
            Files.writeString(root.resolve(id("tamper")).resolve("identity.json"),"different");
            assertEquals("DENOVO_TASK_IDENTITY_MISMATCH",tasks.read(root,id("tamper")).status().path("reasonCode").asText());
        }
    }
    @Test void missingTaskAndInvalidPathCannotLaunchOrCreateAnything() throws Exception {
        try(var tasks=new DeNovoSourceTasks(1,1)) {
            assertEquals("DENOVO_TASK_NOT_FOUND",tasks.read(root,id("missing")).status().path("reasonCode").asText());
            assertThrows(java.io.IOException.class,()->tasks.read(root,"../elsewhere"));
            try(var entries=Files.list(root)){assertEquals(0,entries.count());}
        }
    }
}
