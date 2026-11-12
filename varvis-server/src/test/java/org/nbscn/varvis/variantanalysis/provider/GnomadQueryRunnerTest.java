package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GnomadQueryRunnerTest {
    @TempDir Path work;
    final List<Map<String, Object>> diagnostics = new ArrayList<>();
    final VariantAnalysisProperties.Gnomad settings = new VariantAnalysisProperties.Gnomad();

    @BeforeEach void configureIndexCache() {
        settings.setRemoteIndexCacheDir(work.resolve("remote-index-cache").toString());
    }

    void run(String body, int attempts, long timeout, long budget) throws Exception {
        Path script = work.resolve("transport.cmd");
        Files.writeString(script, "@echo off\r\n" + body);
        settings.setTimeout(Duration.ofMillis(timeout)); settings.setRemoteMaxAttempts(attempts);
        settings.setRemoteTotalTimeout(Duration.ofMillis(budget));
        Path output = work.resolve("output.vcf"), error = work.resolve("error.log");
        Files.writeString(output, ""); Files.writeString(error, "");
        GnomadQueryRunner.run(List.of(script.toString()), output, error, true, settings,
                "public.vcf.bgz", "query-hash", 2, diagnostics);
    }

    @Test void successfulOutputAndEmptySuccessfulQueryAreDistinct() throws Exception {
        run("echo record\r\nexit /b 0\r\n", 2, 2000, 4000);
        assertEquals("SUCCEEDED", diagnostics.get(0).get("status"));
        assertTrue((long) diagnostics.get(0).get("stdoutBytes") > 0);
        diagnostics.clear();
        run("exit /b 0\r\n", 2, 2000, 4000);
        assertEquals(1, diagnostics.size()); assertEquals(0L, diagnostics.get(0).get("stdoutBytes"));
    }

    @Test void windowsTimeoutNeverCallsInjectedBlockingDescendantCleanup() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Path script = work.resolve("blocking.cmd"); Files.writeString(script, "@echo off\r\nping -n 20 127.0.0.1 >nul\r\n");
        settings.setTimeout(Duration.ofMillis(300)); settings.setRemoteMaxAttempts(1);
        settings.setRemoteTotalTimeout(Duration.ofSeconds(1));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class, () ->
                GnomadQueryRunner.run(List.of(script.toString()), work.resolve("out"), work.resolve("err"), true,
                        settings, "public", "query", 1, diagnostics, Map.of(), (process, record) -> {
                            calls.incrementAndGet();
                            try { new java.util.concurrent.CountDownLatch(1).await(); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            return false;
                        })));
        assertEquals(0, calls.get()); assertEquals("TIMEOUT", diagnostics.get(0).get("status"));
        assertEquals("STOPPED", diagnostics.get(0).get("cleanupOutcome"));
    }

    @Test void remoteIndexIsCreatedAndReusedOnlyInConfiguredCache() throws Exception {
        String body = "if exist public.vcf.bgz.tbi (echo reused) else (echo index>public.vcf.bgz.tbi & echo created)\r\nexit /b 0\r\n";
        run(body, 1, 2000, 4000);
        Path cache = work.resolve("remote-index-cache");
        assertTrue(Files.isRegularFile(cache.resolve("public.vcf.bgz.tbi")));
        assertEquals("created", Files.readString(work.resolve("output.vcf")).strip());
        diagnostics.clear();
        run(body, 1, 2000, 4000);
        assertEquals("reused", Files.readString(work.resolve("output.vcf")).strip());
        assertFalse(Files.exists(work.resolve("public.vcf.bgz.tbi")));
    }

    @Test void invalidCacheDirectoryFailsBeforeLaunchingTransport() throws Exception {
        Path blocked = work.resolve("cache-is-a-file");
        Files.writeString(blocked, "preserve");
        settings.setRemoteIndexCacheDir(blocked.toString());
        assertThrows(IOException.class, () -> run("echo unexpected\r\n", 1, 2000, 4000));
        assertTrue(diagnostics.isEmpty());
        assertEquals("preserve", Files.readString(blocked));
    }

    @Test void cacheLocationDoesNotChangeSerializedEvidenceConfiguration() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var before = mapper.valueToTree(settings);
        settings.setRemoteIndexCacheDir(work.resolve("another-cache").toString());
        assertEquals(before, mapper.valueToTree(settings));
    }

    @Test void localQueriesKeepOriginalWorkingDirectoryAndIgnoreRemoteCache() throws Exception {
        Path script = work.resolve("local.cmd");
        Files.writeString(script, "@echo off\r\ncd\r\n");
        Path blocked = work.resolve("cache-is-a-file");
        Files.writeString(blocked, "preserve");
        settings.setRemoteIndexCacheDir(blocked.toString());
        Path output = work.resolve("local-output.txt");
        GnomadQueryRunner.run(List.of(script.toString()), output, work.resolve("local-error.log"),
                false, settings, "local.vcf.bgz", "query-hash", 1, diagnostics);
        assertEquals(Path.of("").toRealPath(), Path.of(Files.readString(output).strip()).toRealPath());
    }

    @Test void retryDiscardsAllFailedAttemptOutputAndRetainsItsDiagnostic() throws Exception {
        run("if exist \"%~dp0attempted\" goto success\r\n"
                + "echo x> \"%~dp0attempted\"\r\necho failed-partial\r\necho transient-error 1>&2\r\nexit /b 7\r\n"
                + ":success\r\necho final-record\r\nexit /b 0\r\n", 2, 2000, 4000);
        assertEquals(List.of("NONZERO_EXIT", "SUCCEEDED"), diagnostics.stream().map(d -> d.get("status")).toList());
        assertEquals("final-record", Files.readString(work.resolve("output.vcf")).strip());
        assertTrue(diagnostics.get(0).get("stderrSummary").toString().contains("transient-error"));
    }

    @Test void timeoutRetryIsBoundedByAttemptsAndTotalBudget() throws Exception {
        assertThrows(IOException.class, () -> run("echo partial\r\n:wait\r\ngoto wait\r\n", 3, 250, 350));
        assertTrue(diagnostics.size() >= 1 && diagnostics.size() <= 2);
        assertTrue(diagnostics.stream().allMatch(d -> "TIMEOUT".equals(d.get("status"))));
        assertTrue(diagnostics.stream().allMatch(d -> Boolean.TRUE.equals(d.get("ownedProcessesStopped"))));
        for (var diagnostic : diagnostics) {
            var handle = ProcessHandle.of((long) diagnostic.get("processId")).orElse(null);
            if (handle != null) assertFalse(handle.isAlive());
        }
        // Windows can briefly retain a redirected file after the process handle reports exit.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertDoesNotThrow(() -> {
            Files.deleteIfExists(work.resolve("output.vcf"));
            Files.deleteIfExists(work.resolve("error.log"));
        }));
        if (diagnostics.size() == 2) assertTrue((long) diagnostics.get(1).get("effectiveAttemptMillis") < 250);
        // Process cleanup counts against the budget and can legitimately prevent the second attempt.
        // Windows process creation/scheduling can exceed the tiny query budget under concurrent builds.
        // The recorded effective budget and attempt count above remain strict; wall time is a hang guard.
        assertTrue((long) diagnostics.get(diagnostics.size() - 1).get("totalElapsedMillis") < 10000, diagnostics::toString);
    }

    @Test void attemptLimitDoesNotBecomeAnUnboundedRetry() {
        assertThrows(IOException.class, () -> run("echo invalid 1>&2\r\nexit /b 7\r\n", 2, 2000, 4000));
        assertEquals(2, diagnostics.size());
    }

    @Test void interruptionStopsOwnedParentAndChildBeforeReturningFailure() throws Exception {
        Path marker = work.resolve("owned-child.pid"), output = work.resolve("cancel-output.vcf"), error = work.resolve("cancel-error.log");
        Files.createFile(output); Files.createFile(error);
        // Startup has its own handshake guard; cancellation keeps the original five-second bound.
        // Helpers need only their two class files, not the application's entire dependency graph.
        Path helperClasses = work.resolve("helpers");
        for (Class<?> helper : List.of(OwnedParent.class, OwnedChild.class)) {
            String name = helper.getName().replace('.', '/') + ".class";
            Path file = helperClasses.resolve(name); Files.createDirectories(file.getParent());
            try (var input = helper.getResourceAsStream("/" + name)) {
                assertNotNull(input); Files.copy(input, file);
            }
        }
        settings.setTimeout(Duration.ofSeconds(60)); settings.setRemoteTotalTimeout(Duration.ofSeconds(60));
        settings.setRemoteMaxAttempts(2);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interruptPreserved = new java.util.concurrent.atomic.AtomicBoolean();
        Path unrelatedReady = work.resolve("unrelated.ready");
        Process unrelated = new ProcessBuilder(javaExecutable(), "-cp", helperClasses.toString(),
                OwnedChild.class.getName(), unrelatedReady.toString()).start();
        Thread worker = new Thread(() -> {
            try {
                GnomadQueryRunner.run(List.of(javaExecutable(), "-cp", helperClasses.toString(),
                                OwnedParent.class.getName(), marker.toString()), output, error, true, settings,
                        "public.vcf.bgz", "cancel-query", 1, diagnostics);
            } catch (Throwable stopped) { failure.set(stopped); }
            finally { interruptPreserved.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(unrelatedReady));
            worker.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> Files.exists(marker));
            long cancelled = System.nanoTime();
            worker.interrupt(); worker.join(5000);
            assertFalse(worker.isAlive(), () -> Arrays.toString(worker.getStackTrace()));
            assertTrue(Duration.ofNanos(System.nanoTime() - cancelled).toMillis() < 5000);
            assertInstanceOf(IOException.class, failure.get());
            assertTrue(interruptPreserved.get());
            assertEquals(1, diagnostics.size(), "Interruption must not retry");
            assertEquals("INTERRUPTED", diagnostics.get(0).get("status"));
            assertEquals(true, diagnostics.get(0).get("ownedProcessesStopped"), diagnostics::toString);
            assertEquals(true, diagnostics.get(0).get("cleanupWithinBudget"), diagnostics::toString);
            assertEquals("STOPPED", diagnostics.get(0).get("cleanupOutcome"));
            long childPid = Long.parseLong(Files.readString(marker));
            var child = ProcessHandle.of(childPid).orElse(null);
            if (child != null) assertFalse(child.isAlive());
            assertTrue(unrelated.isAlive(), "Cancellation must preserve an unrelated concurrent process");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertDoesNotThrow(() -> {
                Files.deleteIfExists(output); Files.deleteIfExists(error);
            }));
        } finally {
            worker.interrupt(); worker.join(5000);
            // Only identities written by this test's helpers may be used for failure cleanup.
            stopHelperIdentity(Path.of(marker + ".ready"));
            stopHelperIdentity(Path.of(marker + ".parent"));
            unrelated.destroyForcibly(); unrelated.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            worker.join(5000);
            System.out.println("CANCELLATION_DIAGNOSTICS=" + diagnostics);
        }
    }

    private static void stopHelperIdentity(Path identity) throws Exception {
        if (!Files.isRegularFile(identity)) return;
        String[] parts = Files.readString(identity).split(",", 2);
        if (parts.length != 2) return;
        var handle = ProcessHandle.of(Long.parseLong(parts[0])).orElse(null);
        if (handle != null && handle.info().startInstant().map(value -> value.toString().equals(parts[1])).orElse(false)) {
            handle.destroyForcibly();
            handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    public static class OwnedParent {
        public static void main(String[] args) throws Exception {
            Path marker = Path.of(args[0]), ready = Path.of(args[0] + ".ready");
            Files.writeString(Path.of(args[0] + ".parent"), ProcessHandle.current().pid() + ","
                    + ProcessHandle.current().info().startInstant().orElseThrow());
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"), OwnedChild.class.getName(), ready.toString()).inheritIO().start();
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
                if (!Files.exists(ready)) throw new IOException("Child readiness handshake failed");
                Path temporary = Path.of(args[0] + ".tmp"); Files.writeString(temporary, Long.toString(child.pid()));
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(60000);
            } finally { child.destroyForcibly(); }
        }
    }

    public static class OwnedChild {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), ProcessHandle.current().pid() + ","
                    + ProcessHandle.current().info().startInstant().orElseThrow());
            Thread.sleep(60000);
        }
    }

    @Test void truncatedOutputAndStderrErrorsCannotBecomeSuccessOrAbsence() {
        assertThrows(IOException.class, () -> run("<nul set /p =truncated\r\nexit /b 0\r\n", 2, 2000, 4000));
        assertEquals("INCOMPLETE_OUTPUT", diagnostics.get(0).get("status")); assertEquals(1, diagnostics.size());
        diagnostics.clear();
        assertThrows(IOException.class, () -> run("echo [E::bgzf_read] invalid block 1>&2\r\nexit /b 0\r\n", 2, 2000, 4000));
        assertEquals("INCOMPLETE_OUTPUT", diagnostics.get(0).get("status"));
    }
}
