package org.nbscn.varvis.variantanalysis.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Only owned tiny Java subprocesses; no resource, service or external executable lookup. */
class BoundedTabixProcessRunnerTest {
    @TempDir Path directory;
    List<String> command(String mode) {
        String childClasses = Path.of(java.net.URI.create(java.util.Objects.requireNonNull(
                Child.class.getProtectionDomain().getCodeSource()).getLocation().toExternalForm())).toString();
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx32m", "-cp",
                childClasses,
                Child.class.getName(), mode, directory.resolve("owned.pid").toString());
    }

    @Test void completeAsciiStdoutPreservesLfAndCrLf() throws Exception {
        ProcessResult result = new BoundedTabixProcessRunner().run(command("success"), directory, Duration.ofSeconds(5));
        assertTrue(result.isSuccess()); assertEquals("row1\r\nrow2\n", result.getOutput()); assertOwnedStopped();
    }
    @ParameterizedTest @ValueSource(strings = {"stderr", "nonzero", "truncated", "flood", "nonascii"})
    void noFailedOrCappedPrefixIsACompleteProcessResult(String mode) {
        assertThrows(IOException.class, () -> new BoundedTabixProcessRunner().run(command(mode), directory, Duration.ofSeconds(5)));
        assertOwnedStopped();
    }
    @Test void timeoutTerminatesOnlyItsOwnProcess() {
        assertThrows(IOException.class, () -> new BoundedTabixProcessRunner().run(command("sleep"), directory, Duration.ofSeconds(2)));
        assertOwnedStopped();
    }
    @Test void cancellationPreservesInterruptAndStopsOwnedChildAndReaders() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<Boolean> restored = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { new BoundedTabixProcessRunner().run(command("sleep"), directory, Duration.ofSeconds(20)); }
            catch (Throwable error) { failed.set(error); }
            finally { restored.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!Files.exists(directory.resolve("owned.pid")) && caller.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("owned.pid")), () -> "Synthetic child did not signal readiness: " + failed.get());
        } finally {
            caller.interrupt(); caller.join(10000);
        }
        assertFalse(caller.isAlive()); assertInstanceOf(IOException.class, failed.get()); assertEquals(Boolean.TRUE, restored.get());
        assertOwnedStopped();
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().equals("varvis-revel-tabix-output")));
    }
    void assertOwnedStopped() {
        assertDoesNotThrow(() -> {
            long pid = Long.parseLong(Files.readString(directory.resolve("owned.pid")));
            ProcessHandle owned = ProcessHandle.of(pid).orElse(null);
            if (owned != null) assertFalse(owned.isAlive());
        });
    }
    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path ready = Path.of(args[1]);
            Path pending = ready.resolveSibling("owned.pid.pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            Files.move(pending, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            switch (args[0]) {
                case "success" -> System.out.write("row1\r\nrow2\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                case "stderr" -> System.err.print("synthetic failure");
                case "nonzero" -> System.exit(2);
                case "truncated" -> System.out.print("missing-LF");
                case "flood" -> { byte[] block = new byte[8192]; java.util.Arrays.fill(block, (byte)'x'); for (int i=0;i<600;i++) System.out.write(block); }
                case "nonascii" -> System.out.write(new byte[] {(byte)255, '\n'});
                case "sleep" -> Thread.sleep(30000);
                default -> throw new IllegalArgumentException("Synthetic mode required");
            }
        }
    }
}
