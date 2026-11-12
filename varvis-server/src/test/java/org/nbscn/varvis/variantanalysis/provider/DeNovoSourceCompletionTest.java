package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DeNovoSourceCompletionTest {
    @TempDir Path work;
    private OwnedQueryExecution.CommandSpec command(long grace) {
        return new OwnedQueryExecution.CommandSpec(List.of("synthetic-no-launch"), work, Map.of(),
                work.resolve("out"), work.resolve("err"), "source-test", Map.of(), true, true, grace);
    }
    private static class ExitingChild implements OwnedQueryExecution.NativeExecution {
        final long delay; long released; boolean killed;
        ExitingChild(long delay) { this.delay=delay; }
        public void launch(OwnedQueryExecution.CommandSpec command) { }
        public void resume() { }
        public boolean rootExited(int wait) { return true; }
        public int exitCode() { return 0; }
        public void releaseExitedRootReferences() { if(released==0) released=System.nanoTime(); }
        public int activeProcesses() { return killed || (released>0 && System.nanoTime()-released>=TimeUnit.MILLISECONDS.toNanos(delay)) ? 0 : 1; }
        public void terminate() { killed=true; }
        public void close() { }
        public Map<String,Object> diagnostics(String stage) {
            return Map.of("members", List.of(Map.of("jobBound",true,"signaled",false)));
        }
    }
    @Test void shortLiveTeardownMustReallyExitWithinOptInWindow() throws Exception {
        var child=new ExitingChild(30); var pool=new OwnedQueryExecution.OwnerPool(1,()->child);
        try {
            var ticket=pool.launch(command(250),2000,1); var result=OwnedQueryExecution.await(ticket);
            assertEquals("",result.failure()); assertEquals(0,result.exitCode()); assertFalse(child.killed);
            assertEquals(0,ticket.active); assertTrue(ticket.exitConfirmed);
        } finally {pool.shutdown();}
    }
    @Test void unchangedDefaultAndPersistentChildStillRejectAndTerminate() throws Exception {
        for(long grace:List.of(0L,250L)) {
            var child=new ExitingChild(10000); var pool=new OwnedQueryExecution.OwnerPool(1,()->child);
            try {
                var ticket=pool.launch(command(grace),2000,1);
                assertEquals("ROOT_EXITED_WITH_ACTIVE_DESCENDANTS",OwnedQueryExecution.await(ticket).failure());
                assertTrue(child.killed); assertTrue(ticket.exitConfirmed); assertEquals(0,ticket.active);
            } finally {pool.shutdown();}
        }
    }
    @Test void graceCannotExtendOriginalDeadline() throws Exception {
        var child=new ExitingChild(10000); var pool=new OwnedQueryExecution.OwnerPool(1,()->child);
        try {
            var ticket=pool.launch(command(250),30,1); OwnedQueryExecution.await(ticket);
            assertEquals("TIMEOUT",ticket.cancellation.get().trigger()); assertTrue(child.killed); assertTrue(ticket.exitConfirmed);
        } finally {pool.shutdown();}
    }
}
