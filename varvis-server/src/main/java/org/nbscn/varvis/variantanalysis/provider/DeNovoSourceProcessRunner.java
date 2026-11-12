package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Reuses the existing Windows Job owner so cancellation includes Python's HTTP child. */
final class DeNovoSourceProcessRunner implements ExternalProcessRunner {
    static final String VERSION = "denovo-round-process-v2";
    private static final OwnedQueryExecution.OwnerPool OWNERS = new OwnedQueryExecution.OwnerPool(1, WindowsJobExecution::new);
    private final Duration maximum;
    DeNovoSourceProcessRunner() { this(Duration.ofSeconds(135)); }
    DeNovoSourceProcessRunner(Duration maximum) { this.maximum = maximum; }
    @Override public ProcessResult run(List<String> command, Path cwd, Duration timeout) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IOException("DENOVO_TASK_PLATFORM_UNSUPPORTED");
        int outputIndex = command.indexOf("--output");
        if (outputIndex < 0 || outputIndex + 1 >= command.size() || timeout == null || timeout.isNegative()
                || timeout.isZero() || timeout.compareTo(maximum) > 0)
            throw new IOException("DENOVO_TASK_PROCESS_CONTRACT_INVALID");
        Path output = Path.of(command.get(outputIndex + 1)).toAbsolutePath().normalize();
        // Each continuation owns its logs and cleanup receipt; sibling rounds must not collide.
        Path directory = Files.createDirectory(output.getParent().resolve("owned-process-" + output.getFileName()));
        Path stdout = directory.resolve("stdout.log"), stderr = directory.resolve("stderr.log");
        var spec = new OwnedQueryExecution.CommandSpec(command, cwd, Map.of("PYTHONIOENCODING", "utf-8"), stdout, stderr,
                DeNovoSourceTasks.VERSION, Map.of(stdout, 65536L, stderr, 65536L, output, 25165824L), true, true, 250);
        var ticket = OWNERS.launch(spec, timeout.toMillis(), 1);
        OwnedQueryExecution.Outcome outcome;
        try { outcome = OwnedQueryExecution.await(ticket); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("DENOVO_TASK_CANCELLED", interrupted); }
        finally {
            new ObjectMapper().writeValue(directory.resolve("execution.json").toFile(), ticket.receipt());
        }
        boolean clean = ticket.exitConfirmed && Boolean.TRUE.equals(ticket.receipt().get("cleanupWithinBudget"));
        if (!clean) throw new IOException("DENOVO_TASK_CLEANUP_UNCONFIRMED");
        boolean timedOut = ticket.cancellation.get() != null && "TIMEOUT".equals(ticket.cancellation.get().trigger());
        String log = "processExit="+outcome.exitCode()+"; ownershipFailure="+outcome.failure()+"\n"+read(stdout) + "\n" + read(stderr);
        return new ProcessResult(outcome.failure().isEmpty() ? outcome.exitCode() : -1, log, timedOut);
    }
    private static String read(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("DENOVO_TASK_PROCESS_OUTPUT_LIMIT");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
