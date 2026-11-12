package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowsOwnedExecutionTest {
    @TempDir Path work;
    OwnedQueryExecution.CommandSpec command(String role, String... args) throws Exception {
        Path classes = work.resolve("helper-classes"); String name = WindowsOwnedProcessFixture.class.getName().replace('.', '/') + ".class";
        Path file = classes.resolve(name); Files.createDirectories(file.getParent());
        if (!Files.exists(file)) try (var input = WindowsOwnedProcessFixture.class.getResourceAsStream("/" + name)) {
            assertNotNull(input); Files.copy(input, file);
        }
        var argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp", classes.toString(),
                WindowsOwnedProcessFixture.class.getName(), work.toString(), role)); argv.addAll(List.of(args));
        return new OwnedQueryExecution.CommandSpec(argv, work, Map.of("VARVIS_OWNED_TEST", "ownership-only"),
                work.resolve(role + ".out"), work.resolve(role + ".err"), "public-helper");
    }
    void ready(String role) { org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).until(() -> Files.exists(work.resolve(role + ".pid"))); }
    void closed(OwnedQueryExecution.Ticket ticket) throws Exception { ticket.cancel("TEST_FINALLY"); ticket.done.get(5, TimeUnit.SECONDS); }

    @Test void readyParentChildGrandchildCancelKeepsUnrelatedSameNameAlive() throws Exception {
        var pool = new OwnedQueryExecution.OwnerPool(2, WindowsJobExecution::new);
        var unrelated = pool.launch(command("unrelated", "0"), 60000, 2);
        var owned = pool.launch(command("tree", "2"), 60000, 2);
        try {
            ready("unrelated"); ready("tree-child-child");
            long start = System.nanoTime(); owned.cancel("INTERRUPTED");
            assertThrows(InterruptedException.class, () -> OwnedQueryExecution.await(owned));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 5000); assertEquals("STOPPED", owned.receipt().get("cleanupOutcome"), owned.receipt()::toString);
            assertTrue((long) owned.receipt().get("cleanupElapsedMillis") <= 2000);
            assertFalse(unrelated.done.isDone());
            for (String role : List.of("tree", "tree-child", "tree-child-child"))
                assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(work.resolve(role + ".pid")))).map(value -> Objects.requireNonNull(value).isAlive()).orElse(false));
            System.out.println("OWNED_CANCELLATION_RECEIPT=" + owned.receipt() + "; returnMillis=" + millis + "; unrelatedAlive=true");
        } finally { closed(owned); closed(unrelated); pool.shutdown(); }
    }
    @Test void lateChildAndExitedRootRemainOwned() throws Exception {
        var pool = new OwnedQueryExecution.OwnerPool(2, WindowsJobExecution::new);
        var late = pool.launch(command("late", "1"), 60000, 2);
        var exited = pool.launch(command("exit-root", "1"), 60000, 2);
        try {
            ready("late"); ready("exit-root-child");
            assertFalse(exited.done.isDone(), "Root exit is not whole-job completion");
            Files.writeString(work.resolve("spawn"), "go"); ready("late-child");
            for (var ticket : List.of(late, exited)) {
                ticket.cancel("INTERRUPTED"); assertThrows(InterruptedException.class, () -> OwnedQueryExecution.await(ticket));
                assertEquals("STOPPED", ticket.receipt().get("cleanupOutcome"));
            }
        } finally { closed(late); closed(exited); pool.shutdown(); }
    }
    @Test void nativeArgumentsCwdEnvironmentAndRedirectedBytesAreExact() throws Exception {
        work = Files.createDirectories(work.resolve("中文 path"));
        var pool = new OwnedQueryExecution.OwnerPool(1, WindowsJobExecution::new);
        String[] args = {"", "a b", "a\"b", "tail\\", "space tail\\", "中文", "\\\""};
        var spec = command("echo", args); var ticket = pool.launch(spec, 60000, 1);
        try {
            assertEquals(0, OwnedQueryExecution.await(ticket).exitCode());
            var lines = Files.readAllLines(spec.output());
            for (int i = 0; i < args.length; i++) assertEquals(args[i], new String(Base64.getDecoder().decode(lines.get(i)), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(work.toString(), new String(Base64.getDecoder().decode(lines.get(args.length)), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("ownership-only", Files.readString(spec.error()).strip());
        } finally { closed(ticket); pool.shutdown(); }
    }
    @Test void launchAndResumeFailuresCannotExecuteUnownedCommand() throws Exception {
        var real = com.sun.jna.Native.load("kernel32", WindowsJobExecution.Api.class);
        for (String operation : List.of("UpdateProcThreadAttribute", "CreateProcessW", "ResumeThread")) {
            var facade = (WindowsJobExecution.Api) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{WindowsJobExecution.Api.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals(operation)) return operation.equals("ResumeThread") ? -1 : false;
                        return method.invoke(real, arguments);
                    });
            var pool = new OwnedQueryExecution.OwnerPool(1, () -> new WindowsJobExecution(facade));
            var ticket = pool.launch(command("failure", "0"), 60000, 1);
            try {
                var result = OwnedQueryExecution.await(ticket); assertFalse(result.failure().isEmpty());
                assertFalse(Files.exists(work.resolve("failure.pid"))); assertTrue(ticket.exitConfirmed);
                assertEquals(0, pool.occupiedSlots());
            } finally { closed(ticket); pool.shutdown(); }
        }
    }
    @Test void pendingNativeLaunchOrTerminationHasBoundedCallerAndRetainsItsSlot() throws Exception {
        for (boolean blockLaunch : List.of(true, false)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var resumed = new AtomicBoolean();
            var alive = new AtomicBoolean(); var finished = new AtomicBoolean();
            var pool = new OwnedQueryExecution.OwnerPool(1, () -> new OwnedQueryExecution.NativeExecution() {
                void block() throws IOException { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new IOException(e); } }
                public void launch(OwnedQueryExecution.CommandSpec spec) throws IOException { if (blockLaunch) block(); alive.set(true); }
                public void resume() { resumed.set(true); }
                public boolean rootExited(int millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return false; }
                public int exitCode() { return 0; }
                public int activeProcesses() { return alive.get() ? 1 : 0; }
                public void terminate() throws IOException { if (!blockLaunch) block(); alive.set(false); }
                public void close() { finished.set(true); }
            });
            var ticket = pool.launch(command("blocked", "0"), 60000, 1);
            try {
                if (blockLaunch) assertTrue(entered.await(5, TimeUnit.SECONDS));
                else org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilTrue(resumed);
                long start = System.nanoTime(); ticket.cancel("INTERRUPTED");
                assertThrows(InterruptedException.class, () -> OwnedQueryExecution.await(ticket));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
                assertEquals("UNCONFIRMED", ticket.receipt().get("cleanupOutcome")); assertEquals("RETAINED", ticket.receipt().get("slotDisposition"));
                assertFalse(ticket.done.isDone()); assertEquals(1, pool.occupiedSlots());
                assertThrows(IOException.class, () -> pool.launch(command("rejected", "0"), 1000, 1));
                assertEquals(blockLaunch ? false : true, resumed.get());
            } finally { release.countDown(); ticket.done.get(5, TimeUnit.SECONDS); pool.shutdown(); }
            assertTrue(finished.get()); assertEquals(0, pool.occupiedSlots());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !pool.cleanupHistory().isEmpty());
            assertEquals(ticket.id, pool.cleanupHistory().get(0).get("attemptId"));
            assertEquals(true, pool.cleanupHistory().get(0).get("exitConfirmed"));
            assertEquals(false, pool.cleanupHistory().get(0).get("cleanupWithinBudget"));
            assertEquals("RELEASED", pool.cleanupHistory().get(0).get("slotDisposition"));
            assertEquals(blockLaunch ? false : true, resumed.get(), "Late cancelled launch must never resume");
        }
    }
    @Test void delegatedTopologiesAreRejectedAndNoDescendantEnumerationIsLinked() throws Exception {
        assertThrows(IOException.class, () -> WindowsJobExecution.windowsCommand(List.of("C:/Windows/System32/wsl.exe", "tabix")));
        // Fail this guard if a future edit reintroduces discovery, even in a late cleanup/finally path.
        for (Class<?> type : List.of(WindowsJobExecution.class, OwnedQueryExecution.class, OwnedQueryExecution.OwnerPool.class)) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (var input = type.getResourceAsStream(resource)) {
                assertNotNull(input); String bytes = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains("java/lang/ProcessHandle")); assertFalse(bytes.contains("descendants"));
            }
        }
    }

    @Test void cancellationBeforeLaunchAndAfterRootExitCannotPublishSuccess() throws Exception {
        for (boolean beforeLaunch : List.of(true, false)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var launches = new AtomicInteger();
            var execution = new OwnedQueryExecution.NativeExecution() {
                public void launch(OwnedQueryExecution.CommandSpec command) { launches.incrementAndGet(); }
                public void resume() { }
                public boolean rootExited(int millis) { return true; }
                public int exitCode() { return 0; }
                public int activeProcesses() { return 0; }
                public void terminate() { }
                public void close() throws IOException {
                    if (!beforeLaunch) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new IOException(e); } }
                }
            };
            var pool = new OwnedQueryExecution.OwnerPool(1, () -> {
                if (beforeLaunch) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); } }
                return execution;
            });
            var ticket = pool.launch(command("publication", "0"), 60000, 1);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); ticket.cancel("INTERRUPTED"); release.countDown();
                assertThrows(InterruptedException.class, () -> OwnedQueryExecution.await(ticket));
                assertEquals(beforeLaunch ? 0 : 1, launches.get());
                assertEquals("RELEASED", ticket.state.get()); assertEquals("INTERRUPTED", ticket.receipt().get("cleanupTrigger"));
                assertEquals(false, ticket.receipt().get("retryAllowed"));
            } finally { release.countDown(); closed(ticket); pool.shutdown(); }
        }
    }

    @Test void configuredNativeTabixUsesProductionArgumentShapeOnLocalPublicData() throws Exception {
        String executable = System.getProperty("varvis.owned.nativeTabix");
        Assumptions.assumeTrue(executable != null, "Opt-in installed native tool, no network");
        Path tabix = Path.of(Objects.requireNonNull(executable));
        Path source = work.resolve("public.vcf"), compressed = work.resolve("public.vcf.gz"), regions = work.resolve("regions.bed");
        String row = "chr12\t120988847\t.\tG\tA\t.\tPASS\t.\n";
        Files.writeString(source, "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" + row);
        Files.writeString(regions, "chr12\t120988846\t120988847\n");
        var pool = new OwnedQueryExecution.OwnerPool(1, WindowsJobExecution::new);
        var commands = List.of(List.of(tabix.resolveSibling("bgzip.exe").toString(), "-c", source.toString()),
                List.of(tabix.toString(), "-p", "vcf", compressed.toString()),
                List.of(tabix.toString(), "--cache", "10", "-R", regions.toString(), compressed.toString()));
        try {
            for (int i = 0; i < commands.size(); i++) {
                var spec = new OwnedQueryExecution.CommandSpec(commands.get(i), work, Map.of(),
                        i == 0 ? compressed : work.resolve("tool-" + i + ".out"), work.resolve("tool-" + i + ".err"), "public-local-tabix");
                var ticket = pool.launch(spec, 30000, 1);
                try {
                    assertEquals(0, OwnedQueryExecution.await(ticket).exitCode(), ticket.receipt()::toString);
                    assertEquals("STOPPED", ticket.receipt().get("cleanupOutcome"));
                    if (i == 2) assertEquals(row, Files.readString(spec.output()).replace("\r\n", "\n"));
                } finally { closed(ticket); }
            }
        } finally { pool.shutdown(); }
    }
}
