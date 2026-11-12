package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DeNovoSourceRetryTest {
    @TempDir Path root;
    byte[] bytes(String value) {return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    DeNovoSourceTasks.Read terminal(DeNovoSourceTasks tasks,String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until) {
            var r=tasks.read(root,id);
            if(!java.util.Set.of("QUEUED","RUNNING").contains(r.status().path("status").asText()) &&
                    !"DENOVO_TASK_EXIT_PENDING".equals(r.status().path("retryBlockedReason").asText())) return r;
            Thread.sleep(10);
        }
        throw new AssertionError("Source did not settle");
    }
    @Test void explicitRetryIsDeduplicatedPreservesFailureAndSurvivesRestart() throws Exception {
        var calls=new AtomicInteger();String firstId;String nextId;byte[] original;
        try(var tasks=new DeNovoSourceTasks(1,2)) {
            DeNovoSourceTasks.Action action=remaining->{if(calls.incrementAndGet()==1)throw new DeNovoSourceTasks.SourceFailure("DENOVO_SUPPLY_PROCESS_TIMEOUT");return bytes("{\"source\":true}");};
            firstId=tasks.prepare(root,bytes("fixed"),action,null).status().path("taskId").asText();
            assertTrue(terminal(tasks,firstId).status().path("canRetry").asBoolean());
            original=Files.readAllBytes(root.resolve(firstId).resolve("state.terminal.json"));
            assertNull(tasks.prepare(root,bytes("fixed"),action,null).result());assertEquals(1,calls.get());
            var retried=tasks.prepare(root,bytes("fixed"),action,firstId);nextId=retried.status().path("taskId").asText();assertNotEquals(firstId,nextId);
            assertEquals(nextId,tasks.prepare(root,bytes("fixed"),action,firstId).status().path("taskId").asText());
            assertEquals("READY",terminal(tasks,nextId).status().path("status").asText());assertEquals(2,calls.get());
            assertEquals(nextId,tasks.prepare(root,bytes("fixed"),action,null).status().path("taskId").asText());
            assertArrayEquals(original,Files.readAllBytes(root.resolve(firstId).resolve("state.terminal.json")));
        }
        try(var tasks=new DeNovoSourceTasks(1,2)) {
            assertEquals(nextId,tasks.prepare(root,bytes("fixed"),remaining->{fail("restart called source");return null;},firstId).status().path("taskId").asText());
            assertEquals("FAILED",tasks.read(root,firstId).status().path("status").asText());
        }
    }
    @Test void onlyLatestExplicitFailureCanAdvanceAndThreeAttemptsAreTheLimit() throws Exception {
        var calls=new AtomicInteger();
        try(var tasks=new DeNovoSourceTasks(1,2)) {
            DeNovoSourceTasks.Action action=remaining->{calls.incrementAndGet();throw new DeNovoSourceTasks.SourceFailure("DENOVO_SUPPLY_PROCESS_FAILED");};
            String first=tasks.prepare(root,bytes("limit"),action,null).status().path("taskId").asText();terminal(tasks,first);
            String second=tasks.prepare(root,bytes("limit"),action,first).status().path("taskId").asText();terminal(tasks,second);
            assertEquals(second,tasks.prepare(root,bytes("limit"),action,first).status().path("taskId").asText());assertEquals(2,calls.get());
            String third=tasks.prepare(root,bytes("limit"),action,second).status().path("taskId").asText();var end=terminal(tasks,third);
            assertFalse(end.status().path("canRetry").asBoolean());assertEquals("DENOVO_TASK_RETRY_LIMIT",end.status().path("retryBlockedReason").asText());
            assertEquals("DENOVO_SUPPLY_PROCESS_FAILED",end.status().path("reasonCode").asText());
            tasks.prepare(root,bytes("limit"),action,third);assertEquals(3,calls.get());
        }
    }
    @Test void corruptOrDifferentIdentityDoesNotLaunchAReplacement() throws Exception {
        try(var tasks=new DeNovoSourceTasks(1,2)) {
            String id=tasks.prepare(root,bytes("bad"),r->bytes("{}"),null).status().path("taskId").asText();terminal(tasks,id);
            Files.writeString(root.resolve(id).resolve("result.json"),"changed");
            DeNovoSourceTasks.Action forbidden=r->{fail("unsafe retry executed");return null;};
            assertEquals("DENOVO_TASK_RESULT_MISMATCH",tasks.prepare(root,bytes("bad"),forbidden,id).status().path("reasonCode").asText());
            assertEquals("DENOVO_TASK_RETRY_IDENTITY_MISMATCH",tasks.prepare(root,bytes("different"),forbidden,id).status().path("reasonCode").asText());
        }
    }
    @Test void concurrentRetriesShareOneOwnedAttempt() throws Exception {
        var calls=new AtomicInteger();var release=new CountDownLatch(1);var callers=Executors.newFixedThreadPool(2);
        try(var tasks=new DeNovoSourceTasks(1,2)) {
            String id=tasks.prepare(root,bytes("race"),r->{throw new DeNovoSourceTasks.SourceFailure("DENOVO_SUPPLY_PROCESS_TIMEOUT");},null).status().path("taskId").asText();terminal(tasks,id);
            DeNovoSourceTasks.Action action=r->{calls.incrementAndGet();release.await();return bytes("{}");};
            var a=callers.submit(()->tasks.prepare(root,bytes("race"),action,id));var b=callers.submit(()->tasks.prepare(root,bytes("race"),action,id));
            String next=a.get(2,TimeUnit.SECONDS).status().path("taskId").asText();assertEquals(next,b.get(2,TimeUnit.SECONDS).status().path("taskId").asText());
            release.countDown();terminal(tasks,next);assertEquals(1,calls.get());
        } finally {release.countDown();callers.shutdownNow();}
    }
}
