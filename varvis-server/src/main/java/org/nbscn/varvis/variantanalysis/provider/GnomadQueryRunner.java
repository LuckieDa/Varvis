package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded transport attempts; only a complete successful attempt is eligible for parsing. */
final class GnomadQueryRunner {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GnomadQueryRunner.class);
    private GnomadQueryRunner() { }

    static void run(List<String> command, Path output, Path error, boolean remote,
                    VariantAnalysisProperties.Gnomad settings, String resource, String querySha256,
                    int loci, List<Map<String, Object>> diagnostics) throws IOException {
        run(command, output, error, remote, settings, resource, querySha256, loci, diagnostics, Map.of());
    }

    static void run(List<String> command, Path output, Path error, boolean remote,
                    VariantAnalysisProperties.Gnomad settings, String resource, String querySha256,
                    int loci, List<Map<String, Object>> diagnostics, Map<String, Object> group) throws IOException {
        run(command, output, error, remote, settings, resource, querySha256, loci, diagnostics, group,
                GnomadQueryRunner::stopOwned);
    }

    // The legacy enumerating cleanup is an explicit non-Windows dependency, never a Windows fallback.
    static void run(List<String> command, Path output, Path error, boolean remote,
                    VariantAnalysisProperties.Gnomad settings, String resource, String querySha256,
                    int loci, List<Map<String, Object>> diagnostics, Map<String, Object> group,
                    java.util.function.BiPredicate<Process, Map<String, Object>> legacyCleanup) throws IOException {
        long perAttempt = settings.getTimeout().toMillis();
        if (perAttempt <= 0) throw new IOException("gnomAD query timeout must be positive");
        Path remoteDirectory = remote ? remoteIndexDirectory(settings) : null;
        int limit = remote ? settings.getRemoteMaxAttempts() : 1;
        var configuredTotal = settings.getRemoteTotalTimeout();
        long total = remote && configuredTotal != null ? configuredTotal.toMillis() : perAttempt;
        long started = System.nanoTime();
        IOException last = null;
        for (int attempt = 1; attempt <= limit; attempt++) {
            long remaining = total - elapsed(started);
            if (remaining <= 0) break;
            long allowed = Math.min(perAttempt, remaining);
            Map<String, Object> record = new LinkedHashMap<>();
            record.putAll(group);
            record.put("resourceFileName", resource);
            record.put("querySha256", querySha256);
            record.put("locusCount", loci);
            record.put("queryMode", "TABIX_REGIONS_BED");
            record.put("diagnosticScope", "TRANSPORT_EXECUTION_NOT_ALLELE_EVALUATION");
            record.put("attempt", attempt);
            record.put("maximumAttempts", limit);
            record.put("attemptTimeoutMillis", perAttempt);
            record.put("totalTimeoutMillis", total);
            record.put("effectiveAttemptMillis", allowed);
            record.put("startedAt", Instant.now().toString());
            long attemptStart = System.nanoTime();
            Process process = null;
            boolean success = false, retryable = false;
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            try {
                boolean completed;
                int exitCode;
                if (windows) {
                    var owned = runOwned(command, output, error, remoteDirectory, allowed, settings.getParallelism(), querySha256, record);
                    completed = !owned.timedOut(); exitCode = owned.exitCode();
                } else {
                // Each attempt truncates both files; a failed attempt's bytes can never leak into a retry.
                ProcessBuilder builder = new ProcessBuilder(command)
                        .redirectOutput(output.toAbsolutePath().toFile()).redirectError(error.toAbsolutePath().toFile());
                if (remoteDirectory != null) {
                    // Tabix saves remote indexes in its working directory. Keep them out of the checkout.
                    List<String> resolved = new ArrayList<>(command);
                    String executable = resolved.get(0);
                    if (executable.contains("/") || executable.contains("\\")) {
                        resolved.set(0, Path.of(executable).toAbsolutePath().normalize().toString());
                    }
                    builder.command(resolved).directory(remoteDirectory.toFile());
                }
                process = builder.start();
                record.put("processId", process.pid());
                record.put("lastExecutionStage", "PROCESS_STARTED");
                record.put("launchMillis", elapsed(attemptStart));
                LOGGER.info("gnomAD process started resource={} querySha256={} pid={} executable={} workingDirectory={}",
                        resource, querySha256, process.pid(), builder.command().get(0),
                        builder.directory() == null ? Path.of("").toAbsolutePath() : builder.directory());
                long wait = Math.max(1, allowed - elapsed(attemptStart));
                completed = process.waitFor(wait, TimeUnit.MILLISECONDS);
                exitCode = completed ? process.exitValue() : -1;
                record.put("processWaitMillis", elapsed(attemptStart) - (long) record.get("launchMillis"));
                }
                if (!completed) {
                    record.put("status", "TIMEOUT");
                    record.put("exitCode", null);
                    retryable = windows ? Boolean.TRUE.equals(record.get("retryAllowed")) : legacyCleanup.test(process, record);
                    last = new IOException(retryable ? "gnomAD tabix query timed out"
                            : "gnomAD tabix query timed out; " + record.get("cleanupOutcome"));
                } else {
                    record.put("lastExecutionStage", "PROCESS_EXITED");
                    record.put("exitCode", exitCode);
                    if (exitCode != 0) {
                        record.put("status", "NONZERO_EXIT");
                        retryable = true;
                        last = new IOException("gnomAD tabix query failed with exit code " + exitCode + ": " + summary(error));
                    } else if (!completeOutput(output) || hasError(error)) {
                        record.put("status", "INCOMPLETE_OUTPUT");
                        last = new IOException("gnomAD tabix returned incomplete or error output");
                    } else {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled before result publication");
                        record.put("status", "SUCCEEDED");
                        success = true;
                    }
                }
            } catch (InterruptedException interrupted) {
                if (!windows) legacyCleanup.test(process, record);
                Thread.currentThread().interrupt();
                record.put("status", "INTERRUPTED");
                throw new IOException("gnomAD tabix query interrupted; " + record.get("cleanupOutcome"), interrupted);
            } catch (IOException failure) {
                record.put("status", "EXECUTION_FAILED");
                last = failure;
            } finally {
                record.put("elapsedMillis", elapsed(attemptStart));
                record.put("totalElapsedMillis", elapsed(started));
                record.put("stdoutBytes", Files.size(output));
                record.put("stderrBytes", Files.size(error));
                record.put("stderrSummary", summary(error));
                record.put("completedAt", Instant.now().toString());
                synchronized (diagnostics) {
                    if (diagnostics.size() >= 2000) diagnostics.remove(0);
                    diagnostics.add(Collections.unmodifiableMap(record));
                }
                LOGGER.info("gnomAD query resource={} loci={} attempt={}/{} timeoutMs={} totalBudgetMs={} status={} elapsedMs={} stdoutBytes={} stderrBytes={}",
                        resource, loci, attempt, limit, allowed, total, record.get("status"), record.get("elapsedMillis"),
                        record.get("stdoutBytes"), record.get("stderrBytes"));
            }
            if (success) return;
            if (!retryable || Thread.currentThread().isInterrupted()) break;
        }
        throw last == null ? new IOException("gnomAD tabix query timed out before another attempt") : last;
    }

    private record OwnedAttempt(boolean timedOut, int exitCode) { }
    private static OwnedAttempt runOwned(List<String> command, Path output, Path error, Path cwd, long budget,
            int concurrency, String identity, Map<String, Object> record) throws IOException, InterruptedException {
        Files.writeString(output, ""); Files.writeString(error, "");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled before admission");
        String unique = ".owner-" + UUID.randomUUID();
        var spec = new OwnedQueryExecution.CommandSpec(command,
                cwd == null ? Path.of("").toAbsolutePath() : cwd, Map.of(),
                output.toAbsolutePath().resolveSibling(output.getFileName() + unique),
                error.toAbsolutePath().resolveSibling(error.getFileName() + unique), identity);
        var ticket = OwnedQueryExecution.WINDOWS.launch(spec, budget, concurrency);
        try {
            var result = OwnedQueryExecution.await(ticket);
            var cancellation = ticket.cancellation.get();
            if (cancellation != null && "TIMEOUT".equals(cancellation.trigger())) return new OwnedAttempt(true, -1);
            if (!result.failure().isEmpty()) throw new IOException(result.failure());
            if (!ticket.exitConfirmed) throw new IOException("OWNED_EXIT_UNCONFIRMED");
            Files.copy(spec.output(), output, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(spec.error(), error, StandardCopyOption.REPLACE_EXISTING);
            if (Thread.currentThread().isInterrupted()) { ticket.cancel("INTERRUPTED"); throw new InterruptedException(); }
            return new OwnedAttempt(false, result.exitCode());
        } finally {
            record.putAll(ticket.receipt());
            if (ticket.done.isDone()) OwnedQueryExecution.deleteOutput(spec);
            // Pending native operations retain their slot and private output. The owner cleans them later.
        }
    }

    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    static String queryIdentity(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Path remoteIndexDirectory(VariantAnalysisProperties.Gnomad settings) throws IOException {
        Path directory = settings.getRemoteIndexCacheDir();
        if (directory == null) {
            String temporaryRoot = System.getProperty("java.io.tmpdir");
            if (temporaryRoot == null || temporaryRoot.isBlank()) {
                throw new IOException("gnomAD remote index cache directory is not configured");
            }
            directory = Path.of(temporaryRoot, "varvis-gnomad-index-cache");
        }
        return Files.createDirectories(directory.toAbsolutePath().normalize());
    }

    private static boolean stopOwned(Process process, Map<String, Object> record) {
        // Handles are captured from this invocation only. Never terminate a distribution or unrelated service.
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(2);
        boolean interrupted = Thread.interrupted();
        boolean stopped = process == null;
        try {
            if (process == null) return true;
            record.put("cleanupStage", "ENUMERATING_DESCENDANTS");
            var owned = new ArrayList<>(process.descendants().toList());
            owned.add(process.toHandle());
            record.put("cleanupEnumerationMillis", elapsed(started));
            if (System.nanoTime() > deadline) record.put("cleanupOverrunStage", "ENUMERATING_DESCENDANTS");
            record.put("ownedProcessCount", owned.size());
            record.put("cleanupStage", "TERMINATING_OWNED_PROCESSES");
            for (var handle : owned) if (handle.pid() != process.pid() && handle.isAlive()) handle.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            record.put("cleanupTerminationMillis", elapsed(started) - (long) record.get("cleanupEnumerationMillis"));
            if (System.nanoTime() > deadline) record.putIfAbsent("cleanupOverrunStage", "TERMINATING_OWNED_PROCESSES");
            record.put("cleanupStage", "WAITING_FOR_OWNED_EXIT");
            // Enumeration and termination also consume the cleanup budget. Native OS calls may
            // exceed it; report that failure explicitly and never start another attempt in that case.
            for (var handle : owned) {
                while (handle.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    try { handle.onExit().get(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException interruption) { interrupted = true; }
                    catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                        return false;
                    }
                }
            }
            stopped = true;
            return System.nanoTime() <= deadline;
        } finally {
            record.put("ownedProcessesStopped", stopped);
            record.put("cleanupElapsedMillis", elapsed(started));
            record.put("cleanupBudgetMillis", 2000L);
            boolean withinBudget = System.nanoTime() <= deadline;
            record.put("cleanupWithinBudget", withinBudget);
            record.put("cleanupOutcome", !withinBudget ? "CLEANUP_BUDGET_EXCEEDED"
                    : stopped ? "STOPPED" : "OWNED_PROCESS_EXIT_UNCONFIRMED");
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean completeOutput(Path output) throws IOException {
        long size = Files.size(output);
        if (size == 0) return true; // Only exit=0 with no stderr error can represent a genuine empty query.
        try (var input = java.nio.channels.FileChannel.open(output, StandardOpenOption.READ)) {
            var last = java.nio.ByteBuffer.allocate(1);
            input.read(last, size - 1);
            return last.array()[0] == '\n';
        }
    }

    private static boolean hasError(Path error) throws IOException {
        try (var reader = Files.newBufferedReader(error, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null)
                if (line.startsWith("[E::") || line.startsWith("[tabix]") || line.startsWith("tbx_index_load failed")) return true;
        }
        return false;
    }

    private static String summary(Path error) throws IOException {
        try (var input = Files.newInputStream(error)) {
            String value = new String(input.readNBytes(2048), StandardCharsets.UTF_8);
            if (value.toLowerCase(Locale.ROOT).matches("(?s).*(authorization|cookie|bearer|token|password|secret).*"))
                return "[sensitive stderr omitted]";
            return value.replaceAll("https?://\\S+", "[resource-url]")
                    .replaceAll("[A-Za-z]:[\\\\/]\\S+", "[local-path]").strip();
        }
    }
}
