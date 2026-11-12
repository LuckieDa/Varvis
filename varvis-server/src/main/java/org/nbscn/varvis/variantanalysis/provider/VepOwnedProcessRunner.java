package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

/** VEP-only opt-in adapter. Each invocation owns a private Job before any child runs.
 * stdout/stderr use independent inherited file handles (no pipe backpressure).
 * The owner enforces file limits while the computation is running.
 */
public final class VepOwnedProcessRunner implements ExternalProcessRunner {
    public static final String VERSION = "vep-windows-job-v3";
    // Allow bounded natural teardown after the foreground root exits. This never exempts a
    // process by image/name: the existing owner must still confirm an empty Job before reading.
    static final long DESCENDANT_EXIT_GRACE_MILLIS = 250;
    private static final OwnedQueryExecution.OwnerPool OWNERS = new OwnedQueryExecution.OwnerPool(2, WindowsJobExecution::new);
    private static final java.util.concurrent.atomic.AtomicBoolean DEGRADED = new java.util.concurrent.atomic.AtomicBoolean();
    private final OwnedQueryExecution.OwnerPool owners;
    private final Map<String, String> environment;
    private final long maxResultBytes;
    public VepOwnedProcessRunner(long maxResultBytes) { this(OWNERS, Map.of(), maxResultBytes); }
    VepOwnedProcessRunner(OwnedQueryExecution.OwnerPool owners, Map<String, String> environment, long maxResultBytes) {
        this.owners = owners; this.environment = Map.copyOf(environment); this.maxResultBytes = maxResultBytes;
    }
    public static ExternalProcessRunner select(VariantAnalysisProperties properties, ExternalProcessRunner legacy) {
        if (!properties.getVep().isOwnedExecutionEnabled()) return legacy;
        org.slf4j.LoggerFactory.getLogger(VepOwnedProcessRunner.class).info(
                "VEP runner selected: version={}, ownedExecutionEnabled=true, maxOutputBytes={}",
                VERSION, properties.getVep().getOwnedMaxOutputBytes());
        return new VepOwnedProcessRunner(properties.getVep().getOwnedMaxOutputBytes());
    }
    public static boolean preserveWorkspace(VariantAnalysisProperties properties) {
        return properties.getVep().isOwnedExecutionEnabled();
    }
    @Override public ProcessResult run(List<String> argv, Path cwd, Duration timeout) throws IOException {
        if (DEGRADED.get()) throw new IOException("VEP_OWNED_EXECUTOR_DEGRADED_CLEANUP_UNCONFIRMED");
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IOException("VEP_OWNED_PLATFORM_UNSUPPORTED");
        if (cwd == null || !Files.isDirectory(cwd) || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofHours(1)) > 0 || maxResultBytes < 1024 || maxResultBytes > 1024L*1024*1024)
            throw new IOException("VEP_OWNED_CONTRACT_INVALID");
        if (Thread.currentThread().isInterrupted()) throw new IOException("VEP_INTERRUPTED_BEFORE_LAUNCH");
        Path directory = Files.createDirectory(cwd.resolve("owned-" + UUID.randomUUID()));
        Path stdout = directory.resolve("stdout.log"), stderr = directory.resolve("stderr.log");
        var limits = new LinkedHashMap<Path, Long>();
        limits.put(stdout, 4L*1024*1024); limits.put(stderr, 4L*1024*1024);
        int outputArgument = argv.indexOf("--output_file");
        if (outputArgument >= 0) {
            if (outputArgument+1 >= argv.size()) throw new IOException("VEP_OUTPUT_ARGUMENT_MISSING");
            Path output = Path.of(argv.get(outputArgument+1)).toAbsolutePath().normalize();
            if (!output.startsWith(cwd.toAbsolutePath().normalize())) throw new IOException("VEP_OUTPUT_OUTSIDE_WORKSPACE");
            limits.put(output, maxResultBytes);
        }
        var spec = new OwnedQueryExecution.CommandSpec(foregroundCommand(argv, directory), cwd, environment, stdout, stderr,
                VERSION, limits, true, true, DESCENDANT_EXIT_GRACE_MILLIS);
        var ticket = owners.launch(spec, timeout.toMillis(), 2);
        OwnedQueryExecution.Outcome outcome;
        String failure = "";
        try { outcome = OwnedQueryExecution.await(ticket); }
        catch (InterruptedException interrupted) {
            failure = "VEP_INTERRUPTED";
            throw new IOException(failure, interrupted);
        } finally {
            var receipt = new LinkedHashMap<String, Object>(ticket.receipt());
            if (!Boolean.TRUE.equals(receipt.get("exitConfirmed")) || !Boolean.TRUE.equals(receipt.get("cleanupWithinBudget"))) DEGRADED.set(true);
            receipt.put("runnerVersion", VERSION); receipt.put("purpose", "VEP_ANNOTATION");
            receipt.put("stdoutStderrHandlesClosed", ticket.exitConfirmed && ticket.done.isDone());
            receipt.put("outputReadComplete", false);
            if (!failure.isEmpty()) receipt.put("failure", failure);
            new ObjectMapper().writeValue(directory.resolve("execution.json").toFile(), receipt);
        }
        var receipt = new LinkedHashMap<String, Object>(ticket.receipt());
        receipt.put("runnerVersion", VERSION); receipt.put("purpose", "VEP_ANNOTATION");
        receipt.put("stdoutStderrHandlesClosed", ticket.exitConfirmed && ticket.done.isDone());
        boolean clean = Boolean.TRUE.equals(receipt.get("exitConfirmed")) && Boolean.TRUE.equals(receipt.get("cleanupWithinBudget"));
        boolean timedOut = ticket.cancellation.get() != null && "TIMEOUT".equals(ticket.cancellation.get().trigger());
        failure = !clean ? "VEP_CLEANUP_UNCONFIRMED" : !outcome.failure().isEmpty() ? "VEP_" + outcome.failure()
                : ticket.cancellation.get() != null ? "VEP_" + ticket.cancellation.get().trigger() : "";
        if (!clean) throw new IOException(failure); // Retained owner/slot is not reused.
        for (var limit : limits.entrySet()) if (Files.exists(limit.getKey()) && Files.size(limit.getKey()) > limit.getValue()) failure = "VEP_OUTPUT_LIMIT_EXCEEDED";
        String log = "";
        if (failure.isEmpty()) {
            log = Files.readString(stdout, StandardCharsets.UTF_8) + Files.readString(stderr, StandardCharsets.UTF_8);
            if (outputArgument >= 0 && outcome.exitCode() == 0) validateJsonOutput(Path.of(argv.get(outputArgument+1)));
            receipt.put("outputReadComplete", true);
        } else receipt.put("outputReadComplete", false);
        receipt.put("failure", failure); receipt.put("executionExitCode", outcome.exitCode());
        new ObjectMapper().writeValue(directory.resolve("execution.json").toFile(), receipt);
        return new ProcessResult(failure.isEmpty() ? outcome.exitCode() : -1, failure.isEmpty() ? log : failure, timedOut, receipt);
    }

    static List<String> foregroundCommand(List<String> argv, Path directory) throws IOException {
        if (argv.isEmpty()) throw new IOException("VEP_WRAPPER_REQUIRED");
        Path original = Path.of(argv.get(0)).toAbsolutePath().normalize();
        if (!original.toString().toLowerCase(Locale.ROOT).endsWith(".cmd") || Files.size(original) > 16384)
            throw new IOException("VEP_FOREGROUND_WRAPPER_UNVERIFIED");
        String source = Files.readString(original).replace("\r\n", "\n");
        String end = source.stripTrailing();
        int perl = end.lastIndexOf("\n\"%MSYS2_ROOT%\\usr\\bin\\perl.exe\" ");
        if (perl < 0 || !end.substring(perl).matches("(?s)\n[^\n]+ %\\*\n+endlocal"))
            throw new IOException("VEP_FOREGROUND_WRAPPER_UNVERIFIED");
        String root = original.getParent().getParent().toString();
        if (root.matches("(?s).*[\"&|<>^%!\\r\\n].*")) throw new IOException("VEP_WRAPPER_PATH_UNSUPPORTED");
        end = end.replace("for %%I in (\"%~dp0..\") do set \"VEP_ROOT=%%~fI\"", "set \"VEP_ROOT=" + root + "\"");
        end = end.substring(0, end.length() - "endlocal".length())
                + "set \"VARVIS_VEP_EXIT=%ERRORLEVEL%\"\nendlocal & exit /b %VARVIS_VEP_EXIT%\n";
        Path wrapper = directory.resolve("foreground-vep.cmd");
        Files.writeString(wrapper, end, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var command = new ArrayList<>(argv); command.set(0, wrapper.toString()); return List.copyOf(command);
    }

    static void validateJsonOutput(Path output) throws IOException {
        var reader = new ObjectMapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (var lines = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line; int records = 0;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) continue;
                var node = reader.readTree(line);
                if (node == null || !(node.isObject() || node.isArray())) throw new IOException("VEP_OUTPUT_JSON_INCOMPLETE");
                records++;
            }
            if (records == 0) throw new IOException("VEP_OUTPUT_JSON_EMPTY");
        } catch (IOException invalid) { throw new IOException("VEP_OUTPUT_JSON_INVALID_OR_INCOMPLETE", invalid); }
    }
}
