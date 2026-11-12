package org.nbscn.varvis.variantanalysis.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in complete-output transport using the existing runner contract. Never truncates a successful result. */
final class BoundedTabixProcessRunner implements ExternalProcessRunner {
    static final int MAX_STDOUT = 4 * 1024 * 1024;
    private static final int MAX_STDERR = 65536;
    private final boolean clinVarSidecar;
    BoundedTabixProcessRunner() { this(false); }
    private BoundedTabixProcessRunner(boolean clinVarSidecar) { this.clinVarSidecar = clinVarSidecar; }
    static BoundedTabixProcessRunner clinVarSidecar() { return new BoundedTabixProcessRunner(true); }

    private String reason(String suffix) { return (clinVarSidecar ? "COMPARATOR_SIDECAR_" : "REVEL_QUERY_") + suffix; }

    @Override public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException(reason("INTERRUPTED"));
        long milliseconds = timeout.toMillis();
        if (milliseconds < 1 || milliseconds > 30000) throw new IOException(reason("TIMEOUT_INVALID"));
        Process process = null;
        Thread stdoutThread = null, stderrThread = null;
        boolean interrupted = false;
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        AtomicReference<IOException> failure = new AtomicReference<>();
        try {
            process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
            Process owned = process;
            stdoutThread = reader(owned, owned.getInputStream(), stdout, MAX_STDOUT, failure);
            stderrThread = reader(owned, owned.getErrorStream(), stderr, MAX_STDERR, failure);
            stdoutThread.start();
            stderrThread.start();
            if (!owned.waitFor(milliseconds, TimeUnit.MILLISECONDS)) throw new IOException(reason("TIMEOUT"));
            stdoutThread.join(2000); stderrThread.join(2000);
            if (stdoutThread.isAlive() || stderrThread.isAlive()) throw new IOException(reason("READER_INCOMPLETE"));
            IOException readFailure = failure.get();
            if (readFailure != null) throw readFailure;
            if (owned.exitValue() != 0 || !acceptableStderr(stderr)) throw new IOException(reason("PROCESS_FAILED"));
            byte[] bytes = stdout.toByteArray();
            for (byte value : bytes) if (value == 0 || !clinVarSidecar && value < 0)
                throw new IOException(reason(clinVarSidecar ? "INVALID_TEXT" : "NOT_ASCII"));
            if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') throw new IOException(reason("OUTPUT_INCOMPLETE"));
            var charset = clinVarSidecar ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII;
            return new ProcessResult(0, charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(), false);
        } catch (InterruptedException cancelled) {
            interrupted = true;
            throw new IOException(reason("INTERRUPTED"), cancelled);
        } finally {
            // Only this invocation's process and two readers are owned here.
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException cancelled) { interrupted = true; }
                close(process.getOutputStream());
                close(process.getInputStream()); close(process.getErrorStream());
            }
            for (Thread thread : new Thread[] {stdoutThread, stderrThread}) if (thread != null && thread.getState() != Thread.State.NEW) {
                try { thread.join(2000); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean acceptableStderr(ByteArrayOutputStream stderr) {
        // The ClinVar caller verifies the actual registered data/index pair. Copying it can change mtimes.
        return stderr.size() == 0 || clinVarSidecar && stderr.toString(StandardCharsets.UTF_8).lines()
                .allMatch(line -> line.startsWith("[W::hts_idx_load3] The index file is older than the data file: "));
    }

    private Thread reader(Process owned, InputStream stream, ByteArrayOutputStream output, int maximum,
                          AtomicReference<IOException> failure) {
        Thread thread = new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (read > maximum - output.size()) throw new IOException(reason("OUTPUT_LIMIT"));
                    output.write(buffer, 0, read);
                }
            } catch (IOException error) {
                failure.compareAndSet(null, error);
                if (owned.isAlive()) owned.destroyForcibly();
            }
        }, "varvis-bounded-tabix-output");
        thread.setDaemon(true);
        return thread;
    }

    private static void close(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { /* Failed transport cannot produce an observation. */ }
    }
}
