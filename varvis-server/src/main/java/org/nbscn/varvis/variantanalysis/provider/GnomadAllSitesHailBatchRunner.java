package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Runs one bounded gnomAD all-sites Hail query process for one locus batch.
 *
 * <p>The caller supplies the Python/script/resource command prefix. This runner only adds the
 * request and output files; it does not own resource configuration or retain a cache.</p>
 */
public final class GnomadAllSitesHailBatchRunner {

    public static final String REQUEST_SCHEMA_VERSION =
            "gnomad-all-sites-an-batch-request-v1";
    public static final String RESULT_SCHEMA_VERSION =
            "gnomad-all-sites-an-batch-result-v1";

    private final ObjectMapper mapper;
    private final List<String> baseCommand;
    private final Path workingDirectory;
    private final Duration timeout;
    private final int maximumLoci;
    private final ExternalProcessRunner processRunner;

    public GnomadAllSitesHailBatchRunner(ObjectMapper mapper,
                                         List<String> baseCommand,
                                         Path workingDirectory,
                                         Duration timeout,
                                         int maximumLoci) {
        this(mapper, baseCommand, workingDirectory, timeout, maximumLoci,
                new SystemExternalProcessRunner());
    }

    GnomadAllSitesHailBatchRunner(ObjectMapper mapper,
                                  List<String> baseCommand,
                                  Path workingDirectory,
                                  Duration timeout,
                                  int maximumLoci,
                                  ExternalProcessRunner processRunner) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(baseCommand, "baseCommand");
        if (baseCommand.isEmpty() || baseCommand.stream().anyMatch(
                value -> value == null || value.trim().isEmpty())) {
            throw new IllegalArgumentException("baseCommand must contain non-blank arguments");
        }
        this.baseCommand = Collections.unmodifiableList(new ArrayList<String>(baseCommand));
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumLoci < 1) {
            throw new IllegalArgumentException("maximumLoci must be positive");
        }
        this.maximumLoci = maximumLoci;
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
    }

    /** Executes exactly one process for a valid batch request. */
    public BatchResult run(ObjectNode request) {
        String requestId = request == null ? null : text(request, "requestId");
        try {
            validateRequest(request);
        } catch (RuntimeException failure) {
            return BatchResult.failed("HAIL_BATCH_REQUEST_INVALID", null);
        }

        Path temporaryDirectory = null;
        Path requestPath = null;
        Path outputPath = null;
        try {
            temporaryDirectory = Files.createTempDirectory(
                    workingDirectory, "varvis-gnomad-all-sites-");
            requestPath = temporaryDirectory.resolve("request.json");
            outputPath = temporaryDirectory.resolve("result.json");
            mapper.writeValue(requestPath.toFile(), request);

            List<String> command = new ArrayList<String>(baseCommand);
            command.add("--batch-request");
            command.add(commandPath(requestPath));
            command.add("--output");
            command.add(commandPath(outputPath));

            ProcessResult process = processRunner.run(command, workingDirectory, timeout);
            if (process.isTimedOut()) {
                return BatchResult.timeout(process.getExitCode());
            }
            if (!process.isSuccess()) {
                return BatchResult.failed("HAIL_BATCH_PROCESS_FAILED", process.getExitCode());
            }
            if (!Files.isRegularFile(outputPath)) {
                return BatchResult.failed("HAIL_BATCH_OUTPUT_MISSING", process.getExitCode());
            }
            JsonNode parsed;
            try {
                parsed = mapper.readTree(outputPath.toFile());
            } catch (IOException malformedOutput) {
                return BatchResult.failed("HAIL_BATCH_RESULT_INVALID", process.getExitCode());
            }
            if (!validSuccessResult(parsed, requestId)) {
                return BatchResult.failed("HAIL_BATCH_RESULT_INVALID", process.getExitCode());
            }
            return BatchResult.succeeded((ObjectNode) parsed, process.getExitCode());
        } catch (Exception failure) {
            if (SpecialistProviderSupport.isTimeout(failure)) {
                return BatchResult.timeout(null);
            }
            return BatchResult.failed("HAIL_BATCH_EXECUTION_FAILED", null);
        } finally {
            deleteIfExists(outputPath);
            deleteIfExists(requestPath);
            deleteIfExists(temporaryDirectory);
        }
    }

    private void validateRequest(ObjectNode request) {
        if (request == null
                || !REQUEST_SCHEMA_VERSION.equals(text(request, "requestSchemaVersion"))
                || blank(text(request, "requestId"))
                || !"GRCh38".equals(text(request, "assembly"))) {
            throw new IllegalArgumentException("invalid batch request header");
        }
        JsonNode loci = request.path("loci");
        if (!loci.isArray() || loci.isEmpty() || loci.size() > maximumLoci) {
            throw new IllegalArgumentException("batch loci are outside the configured bound");
        }
        for (JsonNode locus : loci) {
            if (!locus.isObject()
                    || blank(text(locus, "locusId"))
                    || blank(text(locus, "canonicalContig"))
                    || !locus.path("position").canConvertToInt()
                    || locus.path("position").asInt() < 1) {
                throw new IllegalArgumentException("invalid batch locus");
            }
        }
    }

    private boolean validSuccessResult(JsonNode result, String requestId) {
        return result != null
                && result.isObject()
                && RESULT_SCHEMA_VERSION.equals(text(result, "resultSchemaVersion"))
                && requestId.equals(text(result, "requestId"))
                && "SUCCEEDED".equals(text(result, "status"))
                && result.path("profile").isObject()
                && result.path("execution").isObject()
                && result.path("resources").isArray();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String commandPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void deleteIfExists(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary runner files carry no evidence state; best-effort cleanup is sufficient.
        }
    }

    /** Process outcome separated from the structured successful Hail result. */
    public static final class BatchResult {

        private final String status;
        private final ObjectNode result;
        private final String reasonCode;
        private final Integer exitCode;

        private BatchResult(String status, ObjectNode result, String reasonCode, Integer exitCode) {
            this.status = status;
            this.result = result == null ? null : result.deepCopy();
            this.reasonCode = reasonCode;
            this.exitCode = exitCode;
        }

        private static BatchResult succeeded(ObjectNode result, Integer exitCode) {
            return new BatchResult("SUCCEEDED", result, null, exitCode);
        }

        private static BatchResult failed(String reasonCode, Integer exitCode) {
            return new BatchResult("FAILED", null, reasonCode, exitCode);
        }

        private static BatchResult timeout(Integer exitCode) {
            return new BatchResult("TIMEOUT", null, "HAIL_BATCH_TIMEOUT", exitCode);
        }

        public String getStatus() {
            return status;
        }

        public ObjectNode getResult() {
            return result == null ? null : result.deepCopy();
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public Integer getExitCode() {
            return exitCode;
        }
    }
}
