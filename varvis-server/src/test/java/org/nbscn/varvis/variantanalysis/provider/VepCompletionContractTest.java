package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VepCompletionContractTest {
    @TempDir Path work;

    @Test void imageQueryFailurePreservesMemberWaitIdentityAndCannotHideRunningWork() throws Exception {
        var api = org.mockito.Mockito.mock(WindowsJobExecution.Api.class);
        var member = new com.sun.jna.Pointer(43);
        org.mockito.Mockito.when(api.QueryInformationJobObject(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.isNull()))
                .thenAnswer(call -> {
                    Object result = call.getArgument(2);
                    if (result instanceof WindowsJobExecution.Accounting accounting) accounting.active = 1;
                    if (result instanceof WindowsJobExecution.ProcessIds ids) {
                        ids.assigned = 1; ids.listed = 1; ids.ids[0] = new com.sun.jna.Pointer(42);
                    }
                    return true;
                });
        org.mockito.Mockito.when(api.OpenProcess(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(42))).thenReturn(member);
        org.mockito.Mockito.when(api.IsProcessInJob(org.mockito.ArgumentMatchers.eq(member), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> { ((com.sun.jna.ptr.IntByReference) call.getArgument(2)).setValue(1); return true; });
        org.mockito.Mockito.when(api.GetProcessTimes(org.mockito.ArgumentMatchers.eq(member), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(api.GetExitCodeProcess(org.mockito.ArgumentMatchers.eq(member), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(api.QueryFullProcessImageNameW(org.mockito.ArgumentMatchers.eq(member), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        org.mockito.Mockito.when(api.GetLastError()).thenReturn(31);
        org.mockito.Mockito.when(api.CloseHandle(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var execution = new WindowsJobExecution(api);
        var job = WindowsJobExecution.class.getDeclaredField("job"); job.setAccessible(true); job.set(execution, new com.sun.jna.Pointer(1));
        try {
            for (int wait : List.of(0, 258)) {
                org.mockito.Mockito.when(api.WaitForSingleObject(member, 0)).thenReturn(wait);
                var diagnostic = execution.diagnostics("SYNTHETIC_IMAGE_QUERY_FAILURE");
                assertEquals(wait == 0, OwnedQueryExecution.onlyExitedMembers(diagnostic));
                var observed = ((List<?>) diagnostic.get("members")).get(0);
                assertInstanceOf(Map.class, observed);
                var row = (Map<?, ?>) observed;
                assertEquals("0:0", row.get("creationIdentity")); assertEquals(true, row.get("jobBound"));
                assertEquals("UNKNOWN", row.get("executableImage")); assertEquals(31, row.get("imageQueryWin32Error"));
                assertEquals("QUERY_MEMBER_IMAGE_UNAVAILABLE", row.get("imageQueryReason"));
                assertEquals(wait == 0, row.get("signaled"));
            }
            org.mockito.Mockito.verify(api, org.mockito.Mockito.times(2)).CloseHandle(member);
        } finally { execution.close(); }
    }

    @Test void releasesExitedReferencesBeforeAccountingAndPreservesNonzeroExit() throws Exception {
        for (int code : List.of(0, 7)) {
            var references = new java.util.concurrent.atomic.AtomicBoolean(true);
            var killed = new java.util.concurrent.atomic.AtomicBoolean();
            var pool = new OwnedQueryExecution.OwnerPool(1, () -> new Stub() {
                public int exitCode() { return code; }
                public int activeProcesses() { return references.get() ? 1 : 0; }
                public void releaseExitedRootReferences() { references.set(false); }
                public void terminate() { killed.set(true); }
            });
            try {
                var ticket = pool.launch(command(), 1000, 1);
                assertEquals(code, OwnedQueryExecution.await(ticket).exitCode());
                assertFalse(killed.get()); assertFalse(references.get());
                assertEquals(code, ticket.receipt().get("rootExitCodeObserved"));
                assertEquals(1, ticket.receipt().get("activeAtRootExit"));
                assertEquals(0, ticket.receipt().get("activeProcessCount"));
            } finally { pool.shutdown(); }
        }
    }

    @Test void onlyKnownExitedMembersQualifyForBoundedNaturalCleanup() {
        assertTrue(OwnedQueryExecution.onlyExitedMembers(Map.of("members", List.of(Map.of("signaled", true, "jobBound", true)))));
        assertFalse(OwnedQueryExecution.onlyExitedMembers(Map.of("members", List.of(Map.of("signaled", false, "jobBound", true)))));
        assertFalse(OwnedQueryExecution.onlyExitedMembers(Map.of("members", List.of(Map.of("observation", "OPEN_PROCESS_RACE_OR_FAILURE")))));
        assertFalse(OwnedQueryExecution.onlyExitedMembers(Map.of()));
    }
    @Test void naturalAccountingDelayHasOneWindowAndCannotMaskAnUnfinishedJob() throws Exception {
        var live = new java.util.concurrent.atomic.AtomicBoolean(true);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> new Stub() {
            public int activeProcesses() { return live.get() ? 1 : 0; }
            public void terminate() { live.set(false); }
        });
        try {
            var ticket = pool.launch(command(), 30000, 1);
            assertEquals("ROOT_EXITED_WITH_ACTIVE_DESCENDANTS", OwnedQueryExecution.await(ticket).failure());
            assertTrue(ticket.naturalCleanupMillis >= 490 && ticket.naturalCleanupMillis < 700);
            assertTrue(ticket.terminationRequested);
            assertTrue((long) ticket.receipt().get("cleanupElapsedMillis") >= ticket.naturalCleanupMillis);
            assertEquals(true, ticket.receipt().get("cleanupWithinBudget"));
        } finally { pool.shutdown(); }
    }

    @Test void unconfirmedCloseIsBoundedAndRetainsOwnerUntilItActuallyCloses() throws Exception {
        var closeEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> new Stub() {
            public void close() throws java.io.IOException {
                closeEntered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new java.io.IOException(e); }
            }
        });
        var ticket = pool.launch(command(), 30000, 1);
        try {
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            long start = System.nanoTime();
            assertEquals("CLEANUP_UNCONFIRMED", OwnedQueryExecution.await(ticket).failure());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
            assertEquals(1, pool.occupiedSlots()); assertEquals(false, ticket.receipt().get("exitConfirmed"));
        } finally { release.countDown(); ticket.done.get(2, TimeUnit.SECONDS); pool.shutdown(); }
    }

    @Test void foregroundWrapperRetainsInitializationAndPropagatesActualPerlExit() throws Exception {
        Path original = work.resolve("original.cmd"), owned = Files.createDirectory(work.resolve("owned"));
        String source = "@echo off\nsetlocal\nset \"PERL5LIB=existing-initialization\"\n"
                + "\"%MSYS2_ROOT%\\usr\\bin\\perl.exe\" \"fixture.pl\" %*\n\nendlocal\n";
        Files.writeString(original, source);
        var command = VepOwnedProcessRunner.foregroundCommand(List.of(original.toString(), "a b"), owned);
        String wrapped = Files.readString(Path.of(command.get(0)));
        assertTrue(wrapped.contains("set \"PERL5LIB=existing-initialization\""));
        assertTrue(wrapped.endsWith("set \"VARVIS_VEP_EXIT=%ERRORLEVEL%\"\nendlocal & exit /b %VARVIS_VEP_EXIT%\n"));
        assertEquals(source, Files.readString(original)); assertEquals("a b", command.get(1));
        Files.writeString(original, source.replace("endlocal", "echo masks exit\nendlocal"));
        assertThrows(java.io.IOException.class, () -> VepOwnedProcessRunner.foregroundCommand(List.of(original.toString()), owned));
    }

    private OwnedQueryExecution.CommandSpec command() {
        return new OwnedQueryExecution.CommandSpec(List.of("no-native-spawn"), work, Map.of(),
                work.resolve("out"), work.resolve("err"), "synthetic-vep-completion", Map.of(), true, true);
    }
    private static class Stub implements OwnedQueryExecution.NativeExecution {
        public void launch(OwnedQueryExecution.CommandSpec command) { }
        public void resume() { }
        public boolean rootExited(int millis) { return true; }
        public int exitCode() { return 0; }
        public int activeProcesses() { return 0; }
        public void terminate() { }
        public void close() throws java.io.IOException { }
        public Map<String, Object> diagnostics(String phase) { return Map.of("members", List.of()); }
    }
}
