package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs one bounded SpliceAI raw prediction process for one variant batch.
 *
 * <p>The configured command receives {@code --batch-request <path> --output <path>}.
 * A WSL command must translate those Windows temporary paths in its wrapper; this class
 * deliberately keeps host-specific runtime paths outside evidence and profile data.</p>
 */
public final class SpliceAiRawBatchRunner {

    public static final String REQUEST_SCHEMA_VERSION = "spliceai-raw-batch-request-v1";
    public static final String RESULT_SCHEMA_VERSION = "spliceai-raw-batch-result-v1";
    public static final int DISTANCE_BP = 4999;
    public static final int MASK = 0;

    private final ObjectMapper mapper;
    private final List<String> baseCommand;
    private final Path referenceFasta;
    private final Path workingDirectory;
    private final Duration timeout;
    private final int maximumVariants;
    private final ExternalProcessRunner processRunner;

    public SpliceAiRawBatchRunner(ObjectMapper mapper,
                                  List<String> baseCommand,
                                  Path referenceFasta,
                                  Path workingDirectory,
                                  Duration timeout,
                                  int maximumVariants) {
        this(mapper, baseCommand, referenceFasta, workingDirectory, timeout, maximumVariants,
                new SystemExternalProcessRunner());
    }

    SpliceAiRawBatchRunner(ObjectMapper mapper,
                           List<String> baseCommand,
                           Path referenceFasta,
                           Path workingDirectory,
                           Duration timeout,
                           int maximumVariants,
                           ExternalProcessRunner processRunner) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(baseCommand, "baseCommand");
        if (baseCommand.isEmpty() || baseCommand.stream().anyMatch(
                value -> value == null || value.trim().isEmpty())) {
            throw new IllegalArgumentException("baseCommand must contain non-blank arguments");
        }
        this.baseCommand = Collections.unmodifiableList(new ArrayList<String>(baseCommand));
        this.referenceFasta = Objects.requireNonNull(referenceFasta, "referenceFasta")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.referenceFasta)) {
            throw new IllegalArgumentException("referenceFasta must be a readable file");
        }
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumVariants < 1) {
            throw new IllegalArgumentException("maximumVariants must be positive");
        }
        this.maximumVariants = maximumVariants;
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
    }

    /** Executes exactly one process for a valid query batch. */
    public BatchResult run(List<Query> queries) {
        ObjectNode request;
        try {
            request = request(queries);
        } catch (RuntimeException failure) {
            return BatchResult.failed("SPLICEAI_BATCH_REQUEST_INVALID", null);
        }

        Path temporaryDirectory = null;
        Path requestPath = null;
        Path outputPath = null;
        try {
            temporaryDirectory = Files.createTempDirectory(
                    workingDirectory, "varvis-spliceai-raw-");
            requestPath = temporaryDirectory.resolve("request.json");
            outputPath = temporaryDirectory.resolve("result.json");
            mapper.writeValue(requestPath.toFile(), request);

            List<String> command = new ArrayList<String>(baseCommand);
            command.add("--reference-fasta");
            command.add(commandPath(referenceFasta));
            command.add("--batch-request");
            command.add(commandPath(requestPath));
            command.add("--output");
            command.add(commandPath(outputPath));

            ProcessResult process = processRunner.run(command, workingDirectory, timeout);
            if (process.isTimedOut()) {
                return BatchResult.timeout(process.getExitCode());
            }
            if (!process.isSuccess()) {
                return BatchResult.failed("SPLICEAI_BATCH_PROCESS_FAILED", process.getExitCode());
            }
            if (!Files.isRegularFile(outputPath)) {
                return BatchResult.failed("SPLICEAI_BATCH_OUTPUT_MISSING", process.getExitCode());
            }
            JsonNode parsed;
            try {
                parsed = mapper.readTree(outputPath.toFile());
            } catch (IOException malformedOutput) {
                return BatchResult.failed("SPLICEAI_BATCH_RESULT_INVALID", process.getExitCode());
            }
            if (!validResultHeader(parsed, request.path("requestId").asText())) {
                return BatchResult.failed("SPLICEAI_BATCH_RESULT_INVALID", process.getExitCode());
            }
            String structuredStatus = text(parsed, "status");
            String structuredReason = text(parsed, "reasonCode");
            if ("TIMEOUT".equals(structuredStatus)) {
                return BatchResult.timeout(
                        blank(structuredReason) ? "SPLICEAI_BATCH_TIMEOUT" : structuredReason,
                        process.getExitCode());
            }
            if ("FAILED".equals(structuredStatus)) {
                return BatchResult.failed(
                        blank(structuredReason) ? "SPLICEAI_BATCH_EXECUTION_FAILED" : structuredReason,
                        process.getExitCode());
            }
            if (!"SUCCEEDED".equals(structuredStatus)
                    || !validSuccessResults(parsed.path("results"), request.path("variants"))) {
                return BatchResult.failed("SPLICEAI_BATCH_RESULT_INVALID", process.getExitCode());
            }
            return BatchResult.succeeded((ObjectNode) parsed, process.getExitCode());
        } catch (Exception failure) {
            if (SpecialistProviderSupport.isTimeout(failure)) {
                return BatchResult.timeout(null);
            }
            return BatchResult.failed("SPLICEAI_BATCH_EXECUTION_FAILED", null);
        } finally {
            deleteIfExists(outputPath);
            deleteIfExists(requestPath);
            deleteIfExists(temporaryDirectory);
        }
    }

    private ObjectNode request(List<Query> queries) {
        if (queries == null || queries.isEmpty() || queries.size() > maximumVariants) {
            throw new IllegalArgumentException("query count is outside the configured bound");
        }
        Set<String> queryIds = new LinkedHashSet<String>();
        ObjectNode request = mapper.createObjectNode();
        request.put("requestSchemaVersion", REQUEST_SCHEMA_VERSION);
        request.put("assembly", "GRCh38");
        request.put("distanceBp", DISTANCE_BP);
        request.put("mask", MASK);
        ArrayNode variants = request.putArray("variants");
        for (Query query : queries) {
            validate(query);
            if (!queryIds.add(query.queryId)) {
                throw new IllegalArgumentException("queryId is duplicated");
            }
            ObjectNode value = variants.addObject();
            value.put("queryId", query.queryId);
            value.set("allele", allele(query.key));
            value.put("geneSymbol", query.geneSymbol);
            value.put("selectedTranscriptId", query.selectedTranscriptId);
        }
        request.put("requestId", "spliceai-batch-sha256-" + sha256(variants));
        return request;
    }

    private void validate(Query query) {
        if (query == null || blank(query.queryId) || query.key == null
                || !"GRCh38".equals(query.key.getGenomeAssembly())
                || blank(query.key.getChromosome()) || query.key.getPosition() < 1
                || blank(query.key.getReference()) || blank(query.key.getAlternate())
                || blank(query.geneSymbol) || blank(query.selectedTranscriptId)) {
            throw new IllegalArgumentException("invalid SpliceAI batch query");
        }
    }

    private ObjectNode allele(NormalizedVariantKey key) {
        ObjectNode allele = mapper.createObjectNode();
        allele.put("assembly", key.getGenomeAssembly());
        allele.put("contig", key.getChromosome());
        allele.put("position", key.getPosition());
        allele.put("reference", key.getReference());
        allele.put("alternate", key.getAlternate());
        return allele;
    }

    private boolean validResultHeader(JsonNode result, String requestId) {
        return result != null
                && result.isObject()
                && RESULT_SCHEMA_VERSION.equals(text(result, "resultSchemaVersion"))
                && requestId.equals(text(result, "requestId"))
                && result.path("results").isArray();
    }

    private boolean validSuccessResults(JsonNode results, JsonNode requestedVariants) {
        if (!results.isArray() || results.size() != requestedVariants.size()) return false;
        java.util.Map<String, JsonNode> requested = new java.util.LinkedHashMap<String, JsonNode>();
        for (JsonNode variant : requestedVariants) {
            requested.put(text(variant, "queryId"), variant.path("allele"));
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode result : results) {
            String queryId = text(result, "queryId");
            String status = text(result, "status");
            JsonNode exact = result.get("exactAlleleMatch");
            JsonNode expectedAllele = requested.get(queryId);
            if (queryId == null || !seen.add(queryId) || expectedAllele == null
                    || !expectedAllele.equals(result.path("allele"))
                    || (exact == null || !exact.isBoolean())
                    || !result.path("annotations").isArray()) {
                return false;
            }
            if ("HIT".equals(status)) {
                if (!exact.asBoolean() || result.path("annotations").isEmpty()) return false;
            } else if ("NO_RECORD".equals(status)) {
                if (exact.asBoolean() || !result.path("annotations").isEmpty()) return false;
            } else {
                return false;
            }
        }
        return seen.size() == requested.size();
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SpliceAI request cannot be hashed", failure);
        }
    }

    static String stableQueryId(NormalizedVariantKey key) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.canonicalId().getBytes(StandardCharsets.UTF_8)));
            return "spliceai-query-sha256-" + hash;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
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
            // Temporary request/results carry no evidence state.
        }
    }

    public static final class Query {

        private final String queryId;
        private final NormalizedVariantKey key;
        private final String geneSymbol;
        private final String selectedTranscriptId;

        public Query(String queryId,
                     NormalizedVariantKey key,
                     String geneSymbol,
                     String selectedTranscriptId) {
            this.queryId = queryId;
            this.key = key;
            this.geneSymbol = geneSymbol;
            this.selectedTranscriptId = selectedTranscriptId;
        }

        public String getQueryId() { return queryId; }
        public NormalizedVariantKey getKey() { return key; }
        public String getGeneSymbol() { return geneSymbol; }
        public String getSelectedTranscriptId() { return selectedTranscriptId; }
    }

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

        static BatchResult succeeded(ObjectNode result, Integer exitCode) {
            return new BatchResult("SUCCEEDED", result, null, exitCode);
        }

        static BatchResult failed(String reasonCode, Integer exitCode) {
            return new BatchResult("FAILED", null, reasonCode, exitCode);
        }

        static BatchResult timeout(Integer exitCode) {
            return timeout("SPLICEAI_BATCH_TIMEOUT", exitCode);
        }

        private static BatchResult timeout(String reasonCode, Integer exitCode) {
            return new BatchResult("TIMEOUT", null, reasonCode, exitCode);
        }

        public String getStatus() { return status; }
        public ObjectNode getResult() { return result == null ? null : result.deepCopy(); }
        public String getReasonCode() { return reasonCode; }
        public Integer getExitCode() { return exitCode; }
    }
}
