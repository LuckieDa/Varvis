package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted mechanical ownership contracts only: no native process or VEP is launched. */
@EnabledOnOs(OS.WINDOWS)
class VepDescendantGraceContractTest {
    @TempDir Path work;

    @ParameterizedTest @ValueSource(strings = {"conhost.exe", "worker.exe", "UNKNOWN"})
    void actualVepCommandWaitsForTheLastWriterAndReadsOnlyAfterJobEmptyAndClose(String image) throws Exception {
        var child = new ScriptedExecution(40, 0, image, true);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> child);
        try {
            ProcessResult result = run(pool, Duration.ofSeconds(3));
            assertTrue(result.isSuccess(), result.getOutput());
            assertEquals(250, child.command.descendantExitGraceMillis());
            assertTrue(child.command.rejectOrphanedRoot());
            assertTrue(child.command.preserveDiagnosticOutput());
            assertTrue(child.finalWriterFinished.get());
            assertTrue(child.closed.get());
            assertFalse(child.terminated.get());
            assertTrue(result.getOutput().contains("last-descendant-final-output"));
            assertTrue(result.getOutput().contains("closed-handles-output-snapshot"));
            assertEquals("{\"id\":\"complete-after-last-writer\"}\n", Files.readString(work.resolve("result.jsonl")));
            assertEquals(true, result.getExecutionReceipt().get("outputReadComplete"));
            assertEquals(true, result.getExecutionReceipt().get("stdoutStderrHandlesClosed"));
            assertEquals(1, result.getExecutionReceipt().get("activeAtRootExit"));
            assertEquals(0, result.getExecutionReceipt().get("activeProcessCount"));
            assertEquals("NORMAL_EXIT", result.getExecutionReceipt().get("cleanupTrigger"));
            assertEquals("RELEASED", result.getExecutionReceipt().get("slotDisposition"));
            assertEquals(0, pool.occupiedSlots());
        } finally { pool.shutdown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"conhost.exe", "worker.exe", "UNKNOWN"})
    void aPersistentChildCannotBeExemptedByItsNameOrUnreadableImage(String image) throws Exception {
        var child = new ScriptedExecution(Long.MAX_VALUE, 0, image, true);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> child);
        try {
            ProcessResult result = run(pool, Duration.ofSeconds(3));
            assertFalse(result.isSuccess());
            assertEquals("VEP_ROOT_EXITED_WITH_ACTIVE_DESCENDANTS", result.getOutput());
            assertEquals(250, child.command.descendantExitGraceMillis());
            assertTrue(child.terminated.get());
            assertTrue(child.closed.get());
            assertFalse(child.finalWriterFinished.get());
            assertEquals(false, result.getExecutionReceipt().get("outputReadComplete"));
            assertEquals("ROOT_EXITED_WITH_ACTIVE_DESCENDANTS", result.getExecutionReceipt().get("cleanupTrigger"));
            if ("UNKNOWN".equals(image)) {
                var receipt = new ObjectMapper().valueToTree(result.getExecutionReceipt());
                boolean unreadableLiveMemberPreserved = false;
                for (var snapshot : receipt.path("lifecycle")) for (var member : snapshot.path("members"))
                    if (!member.path("signaled").asBoolean(true)
                            && "QUERY_MEMBER_IMAGE_UNAVAILABLE".equals(member.path("imageQueryReason").asText())
                            && member.path("imageQueryWin32Error").asInt() == 5) unreadableLiveMemberPreserved = true;
                assertTrue(unreadableLiveMemberPreserved);
            }
            assertEquals(0, result.getExecutionReceipt().get("activeProcessCount"));
            assertEquals(0, pool.occupiedSlots());
        } finally { pool.shutdown(); }
    }

    @Test void aNonVepCommandKeepsZeroGraceAndRejectsALiveTail() throws Exception {
        var child = new ScriptedExecution(10000, 0, "conhost.exe", true);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> child);
        try {
            var command = command();
            assertEquals(0, command.descendantExitGraceMillis());
            var ticket = pool.launch(command, 3000, 1);
            assertEquals("ROOT_EXITED_WITH_ACTIVE_DESCENDANTS", OwnedQueryExecution.await(ticket).failure());
            assertTrue(child.terminated.get());
            assertTrue(child.closed.get());
            assertFalse(child.finalWriterFinished.get());
            assertEquals(0, pool.occupiedSlots());
        } finally { pool.shutdown(); }
    }

    @Test void originalSourceDeadlineCanExpireInsideTheVepGraceWindow() throws Exception {
        var child = new ScriptedExecution(Long.MAX_VALUE, 0, "UNKNOWN", true);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> child);
        try {
            ProcessResult result = run(pool, Duration.ofMillis(40));
            assertFalse(result.isSuccess());
            assertTrue(result.isTimedOut());
            assertEquals("TIMEOUT", result.getExecutionReceipt().get("cleanupTrigger"));
            assertEquals(false, result.getExecutionReceipt().get("outputReadComplete"));
            assertTrue(child.terminated.get());
            assertTrue(child.closed.get());
            assertEquals(0, pool.occupiedSlots());
        } finally { pool.shutdown(); }
    }

    @Test void cleanDescendantExitCannotChangeARootFailureIntoSuccess() throws Exception {
        var child = new ScriptedExecution(30, 7, "worker.exe", true);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> child);
        try {
            ProcessResult result = run(pool, Duration.ofSeconds(3));
            assertFalse(result.isSuccess());
            assertEquals(7, result.getExitCode());
            assertEquals(7, result.getExecutionReceipt().get("rootExitCodeObserved"));
            assertEquals(0, result.getExecutionReceipt().get("activeProcessCount"));
            assertTrue(child.finalWriterFinished.get());
            assertTrue(child.closed.get());
            assertFalse(child.terminated.get());
        } finally { pool.shutdown(); }
    }

    @Test void emptyJobAndClosedHandlesStillCannotPublishPartialJson() throws Exception {
        var child = new ScriptedExecution(30, 0, "worker.exe", false);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> child);
        try {
            IOException failure = assertThrows(IOException.class, () -> run(pool, Duration.ofSeconds(3)));
            assertEquals("VEP_OUTPUT_JSON_INVALID_OR_INCOMPLETE", failure.getMessage());
            assertTrue(child.finalWriterFinished.get());
            assertTrue(child.closed.get());
            assertFalse(child.terminated.get());
            var receipt = new ObjectMapper().readTree(Files.readAllBytes(receiptPath()));
            assertFalse(receipt.path("outputReadComplete").asBoolean(true));
            assertTrue(receipt.path("exitConfirmed").asBoolean());
            assertEquals(0, pool.occupiedSlots());
        } finally { pool.shutdown(); }
    }

    @Test void aFinalWriterCannotExceedTheOutputLimitDuringDescendantGrace() throws Exception {
        var child = new ScriptedExecution(30, 0, "worker.exe", true, 1024 * 1024);
        var pool = new OwnedQueryExecution.OwnerPool(1, () -> child);
        try {
            ProcessResult result = run(pool, Duration.ofSeconds(3));
            assertFalse(result.isSuccess());
            assertEquals("VEP_OUTPUT_LIMIT_EXCEEDED", result.getOutput());
            assertTrue(child.finalWriterFinished.get());
            assertTrue(child.closed.get());
            assertFalse(child.terminated.get());
            assertEquals(false, result.getExecutionReceipt().get("outputReadComplete"));
            assertEquals(0, pool.occupiedSlots());
        } finally { pool.shutdown(); }
    }

    private ProcessResult run(OwnedQueryExecution.OwnerPool pool, Duration timeout) throws IOException {
        Path wrapper = work.resolve("fixture.cmd");
        Files.writeString(wrapper, "@echo off\nsetlocal\nset \"PERL5LIB=mechanical-only\"\n"
                + "\"%MSYS2_ROOT%\\usr\\bin\\perl.exe\" \"never-launched.pl\" %*\n\nendlocal\n");
        return new VepOwnedProcessRunner(pool, Map.of(), 1024 * 1024)
                .run(List.of(wrapper.toString(), "--output_file", work.resolve("result.jsonl").toString()), work, timeout);
    }

    private OwnedQueryExecution.CommandSpec command() {
        return new OwnedQueryExecution.CommandSpec(List.of("mechanical-no-native-launch"), work, Map.of(),
                work.resolve("stdout.log"), work.resolve("stderr.log"), "NON_VEP_SCRIPTED_MECHANICAL_ONLY", Map.of(), true, true);
    }

    private Path receiptPath() throws IOException {
        try (var directories = Files.list(work)) {
            return directories.filter(path -> path.getFileName().toString().startsWith("owned-"))
                    .findFirst().orElseThrow().resolve("execution.json");
        }
    }

    private static final class ScriptedExecution implements OwnedQueryExecution.NativeExecution {
        private final long delayNanos;
        private final int rootCode;
        private final String image;
        private final boolean completeJson;
        private final int payloadBytes;
        private volatile long rootReferencesReleasedAt;
        private final AtomicBoolean terminated = new AtomicBoolean(), closed = new AtomicBoolean();
        private final AtomicBoolean finalWriterFinished = new AtomicBoolean();
        private volatile OwnedQueryExecution.CommandSpec command;
        private Path json;

        ScriptedExecution(long delayMillis, int rootCode, String image, boolean completeJson) {
            this(delayMillis, rootCode, image, completeJson, 0);
        }
        ScriptedExecution(long delayMillis, int rootCode, String image, boolean completeJson, int payloadBytes) {
            delayNanos = delayMillis == Long.MAX_VALUE ? Long.MAX_VALUE : TimeUnit.MILLISECONDS.toNanos(delayMillis);
            this.rootCode = rootCode; this.image = image; this.completeJson = completeJson; this.payloadBytes = payloadBytes;
        }
        public void launch(OwnedQueryExecution.CommandSpec spec) throws IOException {
            command = spec;
            Files.writeString(spec.output(), "root-output-before-descendant-finishes\n", StandardCharsets.UTF_8);
            Files.writeString(spec.error(), "", StandardCharsets.UTF_8);
            int output = spec.argv().indexOf("--output_file");
            if (output >= 0) {
                json = Path.of(spec.argv().get(output + 1));
                Files.writeString(json, "{\"id\":", StandardCharsets.UTF_8);
            }
        }
        public void resume() { }
        public boolean rootExited(int waitMillis) { return true; }
        public int exitCode() { return rootCode; }
        public void releaseExitedRootReferences() {
            if (rootReferencesReleasedAt == 0) rootReferencesReleasedAt = System.nanoTime();
        }
        public int activeProcesses() throws IOException {
            if (terminated.get()) return 0;
            if (rootReferencesReleasedAt > 0 && System.nanoTime() - rootReferencesReleasedAt >= delayNanos) {
                if (finalWriterFinished.compareAndSet(false, true)) {
                    Files.writeString(command.output(), "last-descendant-final-output\n", StandardCharsets.UTF_8);
                    if (json != null) {
                        String payload = payloadBytes > 0 ? "{\"padding\":\"" + "x".repeat(payloadBytes) + "\"}\n"
                                : completeJson ? "{\"id\":\"complete-after-last-writer\"}\n" : "{\"id\":";
                        Files.writeString(json, payload, StandardCharsets.UTF_8);
                    }
                }
                return 0;
            }
            return 1;
        }
        public void terminate() { terminated.set(true); }
        public void close() throws IOException {
            if (finalWriterFinished.get() && !terminated.get())
                Files.writeString(command.output(), "last-descendant-final-output\nclosed-handles-output-snapshot\n", StandardCharsets.UTF_8);
            closed.set(true);
        }
        public Map<String, Object> identity() {
            return Map.of("processId", 41L, "rootCreationIdentity", "10:41");
        }
        public List<Map<String, Object>> memberIdentities() {
            return List.of(Map.of("processId", 42L, "creationIdentity", "10:42", "jobBound", true));
        }
        public Map<String, Object> diagnostics(String stage) throws IOException {
            int active = activeProcesses();
            var diagnostic = new LinkedHashMap<String, Object>();
            diagnostic.put("stage", stage); diagnostic.put("activeProcesses", active);
            var member = new LinkedHashMap<String, Object>();
            member.put("processId", 42L); member.put("creationIdentity", "10:42"); member.put("jobBound", true);
            member.put("signaled", active == 0); member.put("executableImage", image);
            if ("UNKNOWN".equals(image)) {
                member.put("imageQueryReason", "QUERY_MEMBER_IMAGE_UNAVAILABLE"); member.put("imageQueryWin32Error", 5);
            }
            diagnostic.put("members", active == 0 ? List.of() : List.of(member));
            return diagnostic;
        }
    }
}
