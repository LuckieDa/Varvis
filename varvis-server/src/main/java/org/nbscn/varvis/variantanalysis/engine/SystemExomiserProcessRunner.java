package org.nbscn.varvis.variantanalysis.engine;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Native process runner with a bounded timeout and forced process termination. */
@Component
public class SystemExomiserProcessRunner implements ExomiserProcessRunner {

    private static final Duration TERMINATION_GRACE_PERIOD = Duration.ofMillis(500);
    private static final Duration FORCED_TERMINATION_WAIT = Duration.ofSeconds(10);

    @Override
    public ExomiserProcessResult run(List<String> command,
                                     Path workingDirectory,
                                     Path stdout,
                                     Path stderr,
                                     Map<String, String> environment,
                                     Duration timeout) throws IOException, InterruptedException {
        Files.createDirectories(workingDirectory);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        Process process = builder.start();
        try {
            long timeoutMillis = timeout == null ? 0L : timeout.toMillis();
            boolean finished = timeoutMillis > 0L
                    && process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcessTree(process, TERMINATION_GRACE_PERIOD);
                return new ExomiserProcessResult(-1, true);
            }
            awaitProcessCleanup(process);
            return new ExomiserProcessResult(process.exitValue(), false);
        } catch (InterruptedException interrupted) {
            try {
                terminateProcessTreeUninterruptibly(process);
            } catch (IOException cleanupFailure) {
                interrupted.addSuppressed(cleanupFailure);
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private void terminateProcessTreeUninterruptibly(Process process) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    terminateProcessTree(process, TERMINATION_GRACE_PERIOD);
                    return;
                } catch (InterruptedException retry) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void terminateProcessTree(Process process, Duration gracePeriod)
            throws InterruptedException, IOException {
        List<ProcessHandle> descendants = new ArrayList<ProcessHandle>();
        process.toHandle().descendants().forEach(descendants::add);
        descendants.sort(Comparator.comparingLong((ProcessHandle handle) -> handle.pid()).reversed());
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroy();
            }
        }
        process.destroy();
        waitForExit(process, descendants, gracePeriod);
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        waitForExit(process, descendants, FORCED_TERMINATION_WAIT);
        if (process.isAlive() || anyAlive(descendants)) {
            throw new IOException("Exomiser process tree did not terminate within the bounded wait");
        }
        awaitProcessCleanup(process);
    }

    /**
     * Synchronize with {@link Process}'s exit completion before returning. On Windows,
     * {@link ProcessHandle#isAlive()} can become false before the Java process wrapper
     * has released streams associated with redirected stdout and stderr.
     */
    private void awaitProcessCleanup(Process process) throws InterruptedException, IOException {
        process.waitFor();
        IOException closeFailure = null;
        try {
            process.getOutputStream().close();
        } catch (IOException error) {
            closeFailure = error;
        }
        try {
            process.getInputStream().close();
        } catch (IOException error) {
            closeFailure = appendSuppressed(closeFailure, error);
        }
        try {
            process.getErrorStream().close();
        } catch (IOException error) {
            closeFailure = appendSuppressed(closeFailure, error);
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private IOException appendSuppressed(IOException primary, IOException additional) {
        if (primary == null) {
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    private void waitForExit(Process process,
                             List<ProcessHandle> descendants,
                             Duration gracePeriod) throws InterruptedException {
        long timeoutNanos = Math.max(0L, gracePeriod.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        while ((process.isAlive() || anyAlive(descendants)) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
    }

    private boolean anyAlive(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                return true;
            }
        }
        return false;
    }
}
