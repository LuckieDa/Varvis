package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit reviewed-bundle callback. Publisher lookup only; the existing runtime still verifies native CDS/VEP. */
public final class RevelPublisherIndexObservationProvider implements ReviewedPredictionBundle.ObservationProvider {
    public static final String ID = "revel-publisher-index-observation";
    public static final String VERSION = "1.0.1";
    private static final int MAX_ROWS = 1000;
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RevelPublisherIndexManifest index;
    private final ExternalProcessRunner runner;
    private final boolean localReader;
    @org.springframework.lang.Nullable private PredictionBatchFactCache batchFacts;

    /** Opt in only on the fresh provider constructed for one local prediction request. */
    RevelPublisherIndexObservationProvider withBatchFactReuse() {
        if (batchFacts != null) throw new IllegalStateException("REVEL_BATCH_REUSE_ALREADY_ENABLED");
        batchFacts = new PredictionBatchFactCache(); return this;
    }

    /** Explicit local transport; preserves the historical review and rehashes relocated artifacts at load. */
    public static RevelPublisherIndexObservationProvider loadLocal(ObjectMapper mapper, Path indexRoot,
            Path reviewReceipt, String expectedReviewFileSha256, String expectedReviewCanonicalSha256) throws IOException {
        return new RevelPublisherIndexObservationProvider(mapper, indexRoot, indexRoot, reviewReceipt,
                expectedReviewFileSha256, expectedReviewCanonicalSha256, new BoundedTabixProcessRunner(), true);
    }

    public static RevelPublisherIndexObservationProvider load(ObjectMapper mapper, Path indexRoot, Path tabix,
            Path reviewReceipt, String expectedReviewFileSha256, String expectedReviewCanonicalSha256) throws IOException {
        return new RevelPublisherIndexObservationProvider(mapper, indexRoot, tabix, reviewReceipt,
                expectedReviewFileSha256, expectedReviewCanonicalSha256, new BoundedTabixProcessRunner());
    }

    /** Injection seam for bounded synthetic transport tests; never supplied by an HTTP request. */
    RevelPublisherIndexObservationProvider(ObjectMapper mapper, Path indexRoot, Path tabix, Path reviewReceipt,
            String expectedReviewFileSha256, String expectedReviewCanonicalSha256, ExternalProcessRunner runner) throws IOException {
        this(mapper, indexRoot, tabix, reviewReceipt, expectedReviewFileSha256, expectedReviewCanonicalSha256, runner, false);
    }

    private RevelPublisherIndexObservationProvider(ObjectMapper mapper, Path indexRoot, Path tabix, Path reviewReceipt,
            String expectedReviewFileSha256, String expectedReviewCanonicalSha256, ExternalProcessRunner runner, boolean localReader) throws IOException {
        this.mapper = Objects.requireNonNull(mapper); this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.localReader = localReader;
        this.runner = Objects.requireNonNull(runner);
        this.index = new RevelPublisherIndexManifest(mapper, indexRoot, tabix, reviewReceipt, expectedReviewFileSha256, expectedReviewCanonicalSha256, localReader);
    }

    public ObjectNode resourceBinding() { return index.binding.deepCopy(); }

    public ObjectNode executionIdentity() {
        return mapper.createObjectNode().put("method", localReader ? RevelLocalIndexQuery.METHOD : "EXTERNAL_TABIX")
                .put("version", localReader ? RevelLocalIndexQuery.VERSION : VERSION);
    }

    @Override public ObjectNode lookup(ReviewedPredictionBundle.ObservationRequest request) {
        return lookupSnapshot(request.snapshot());
    }

    ObjectNode lookupSnapshot(ObjectNode request) {
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", "revel-publisher-index-observation-v1")
                .put("status", "UNAVAILABLE").put("queryAttempts", 0).put("physicalTabixCalls", 0);
        output.putArray("knownConflicts"); output.putArray("rawRecords");
        output.set("indexProvenance", index.provenance.deepCopy());
        if (localReader) output.put("physicalLocalQueries", 0).putNull("compressedDataBytesRead");
        if (containsLocalPath(request.path("context")) || containsLocalPath(request.path("transcriptModel")))
            return output.put("reasonCode", "REVEL_LOCAL_PATH_REQUIRES_SANITIZED_REVIEWED_MODEL");
        output.set("context", request.path("context").deepCopy());
        output.set("transcriptModel", request.path("transcriptModel").deepCopy());
        boolean contextValid = false;
        try {
            RevelPublisherIndexManifest.require("REVEL".equals(request.path("preselectedModel").asText()), "REVEL_PRESELECTION_REQUIRED");
            JsonNode context = request.path("context");
            adapter.validateCompleteContext(context); contextValid = true;
            validateRequest(request);
            index.recheck();
            requireNotInterrupted();
            JsonNode allele = context.at("/variant/evidenceQueryAllele");
            String contig = allele.path("contig").asText();
            String position = allele.path("position").asText();
            String region = contig + ":" + position + "-" + position;
            String started = Instant.now().toString();
            ArrayNode matches = mapper.createArrayNode(), coordinate = mapper.createArrayNode();
            String finished;
            var cache = batchFacts;
            if (cache == null) {
                var raw = acquire(region, contig);
                copyTransportCounts(output, raw, false);
                requireRaw(raw);
                parse(raw.path("text").asText(), request, coordinate, matches);
                finished = raw.path("finished").asText();
            } else {
                var key = mapper.createObjectNode().put("region", region).put("dataClass", context.path("dataClass").asText());
                key.set("resource", index.binding.deepCopy()); key.set("transport", executionIdentity());
                String rawKey = adapter.canonicalSha256(key);
                var capture = cache.obtain(rawKey, () -> acquire(region, contig), "REVEL_QUERY_PROCESS_FAILED");
                var raw = capture.value();
                started = raw.path("started").asText(); finished = raw.path("finished").asText();
                copyTransportCounts(output, raw, capture.reused());
                output.putObject("rawFactReuse").put("version", PredictionBatchFactCache.VERSION)
                        .put("keySha256", rawKey).put("reused", capture.reused()).put("scope", "THIS_REQUEST_RAW_COORDINATE_ONLY")
                        .put("textSha256", raw.path("textSha256").asText());
                requireRaw(raw);
                parse(raw.path("text").asText(), request, coordinate, matches);
            }
            index.recheck();
            requireNotInterrupted();
            ObjectNode observedEvidence = evidence(context, matches, started, null, finished);
            requireNotInterrupted();
            output.set("rawRecords", matches); output.set("coordinateRecords", coordinate);
            output.put("coordinateRowCount", coordinate.size()).put("matchedRowCount", matches.size())
                    .put("status", matches.isEmpty() ? "NO_RECORD" : "HIT");
            output.set("evidence", observedEvidence);
            requireNotInterrupted();
        } catch (IOException | RuntimeException failure) {
            String reason = Thread.currentThread().isInterrupted() ? "REVEL_QUERY_INTERRUPTED" : failure.getMessage();
            if (reason == null || !reason.matches("(?:REVEL_|PREDICTION_FACT_REUSE_)[A-Z0-9_]+")) reason = "REVEL_QUERY_INVALID_OR_UNAVAILABLE";
            output.put("status", "UNAVAILABLE").put("reasonCode", reason);
            output.putArray("rawRecords"); output.remove(List.of("coordinateRecords", "coordinateRowCount", "matchedRowCount"));
            if (contextValid) output.set("evidence", evidence(request.path("context"), mapper.createArrayNode(), Instant.now().toString(), reason, Instant.now().toString()));
        }
        return output;
    }

    private ObjectNode acquire(String region, String contig) {
        var raw = mapper.createObjectNode().put("started", Instant.now().toString()).put("physicalLocalQueries", 0)
                .put("physicalTabixCalls", 0).put("compressedDataBytesRead", 0);
        String text = "";
        try {
            if (index.contigs.contains(contig)) {
                if (localReader) {
                    raw.put("physicalLocalQueries", 1);
                    var result = new RevelLocalIndexQuery().query(index.root.resolve(RevelPublisherIndexManifest.DATA),
                            index.root.resolve(RevelPublisherIndexManifest.INDEX), region, Duration.ofSeconds(15));
                    text = result.output(); raw.put("physicalLocalQueries", 1).put("compressedDataBytesRead", result.compressedBytes());
                } else {
                    raw.put("physicalTabixCalls", 1);
                    var result = runner.run(List.of(index.tabix.toString(), index.root.resolve(RevelPublisherIndexManifest.DATA).toString(), region), index.root, Duration.ofSeconds(15));
                    requireNotInterrupted();
                    RevelPublisherIndexManifest.require(result != null && result.isSuccess(), "REVEL_QUERY_PROCESS_FAILED");
                    text = Objects.requireNonNull(result).getOutput(); raw.put("physicalTabixCalls", 1);
                }
            }
            if (!(text != null && text.length() <= BoundedTabixProcessRunner.MAX_STDOUT
                    && (text.isEmpty() || text.endsWith("\n")))) {
                throw new IOException("REVEL_QUERY_OUTPUT_INCOMPLETE");
            }
            raw.put("text", text).put("textSha256", RevelPublisherIndexManifest.digest(text.getBytes(StandardCharsets.UTF_8)))
                    .put("finished", Instant.now().toString());
            return raw;
        } catch (IOException | RuntimeException unavailable) {
            String reason = unavailable.getMessage();
            if (reason == null || !reason.matches("REVEL_[A-Z0-9_]+")) reason = "REVEL_QUERY_INVALID_OR_UNAVAILABLE";
            return raw.put("failureReason", reason).put("finished", Instant.now().toString());
        }
    }

    private static void requireRaw(ObjectNode raw) {
        if (raw.hasNonNull("failureReason")) throw new IllegalArgumentException(raw.path("failureReason").asText());
    }

    private void copyTransportCounts(ObjectNode output, ObjectNode raw, boolean reused) {
        output.put("queryAttempts", reused ? 0 : 1).put("physicalTabixCalls", reused ? 0 : raw.path("physicalTabixCalls").asInt());
        if (localReader) output.put("physicalLocalQueries", reused ? 0 : raw.path("physicalLocalQueries").asInt())
                .put("compressedDataBytesRead", reused ? 0 : raw.path("compressedDataBytesRead").asLong());
    }

    private static void requireNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("REVEL_QUERY_INTERRUPTED");
    }

    private void validateRequest(ObjectNode request) throws IOException {
        RevelPublisherIndexManifest.require("reviewed-prediction-observation-request-v1".equals(request.path("schemaVersion").asText())
                && request.path("resourceBinding").equals(index.binding), "REVEL_REQUEST_RESOURCE_BINDING_MISMATCH");
        ObjectNode identity = request.deepCopy(); identity.remove(List.of("lookupId", "plannedClauses", "allSelectionPlans"));
        RevelPublisherIndexManifest.require(("prediction-lookup-sha256-" + adapter.canonicalSha256(identity)).equals(request.path("lookupId").asText()), "REVEL_LOOKUP_REQUEST_IDENTITY_MISMATCH");
        JsonNode context = request.path("context"), allele = context.at("/variant/evidenceQueryAllele");
        String confirmation = context.at("/transcript/selectionPolicy").asText();
        RevelPublisherIndexManifest.require(!org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(confirmation)
                && !"PM2_CONTEXT_ONLY".equals(confirmation), "REVEL_FULL_TRANSCRIPT_CONFIRMATION_REQUIRED");
        RevelPublisherIndexManifest.require("GRCh38".equals(allele.path("assembly").asText())
                && "NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                && "SNV".equals(context.at("/variant/variantType").asText()) && RevelPublisherIndexManifest.primary(allele.path("contig").asText())
                && RevelPublisherIndexManifest.integer(allele.path("position"), 1, (1 << 29) - 1)
                && allele.path("reference").asText().matches("[ACGT]") && allele.path("alternate").asText().matches("[ACGT]")
                && !allele.path("reference").equals(allele.path("alternate")), "REVEL_NATIVE_GRCH38_SNV_REQUIRED");
        JsonNode model = request.path("transcriptModel"), binding = request.path("transcriptModelBinding");
        RevelPublisherIndexManifest.require(model.path("modelId").equals(binding.path("modelId")) && model.path("modelVersion").equals(binding.path("modelVersion"))
                && adapter.canonicalSha256(model).equals(binding.path("canonicalSha256").asText())
                && CodingTranscriptMapping.modelIssue(model, context) == null, "REVEL_TRANSCRIPT_MODEL_IDENTITY_MISMATCH");
        JsonNode bridge = request.path("revelTranscriptBinding");
        String accession = bridge.path("accession").asText();
        boolean technical = GeneralRevelPolicy.BRIDGE.equals(bridge.path("status").asText())
                && model.path("transcript").asText().matches("ENST[0-9]+\\.[1-9][0-9]*");
        RevelPublisherIndexManifest.require(accession.matches("ENST[0-9]+")
                && (technical || "REVIEWED_ACCESSION_BRIDGE_WITH_ALLELE_SPECIFIC_SEQUENCE_CHECK".equals(bridge.path("status").asText()))
                && bridge.path("sourceIds").isArray() && !bridge.path("sourceIds").isEmpty(), "REVEL_REVIEWED_SOURCE_TRANSCRIPT_REQUIRED");
        String selected = model.path("transcript").asText();
        if (selected.startsWith("ENST")) RevelPublisherIndexManifest.require(selected.matches(java.util.regex.Pattern.quote(accession) + "\\.[1-9][0-9]*"), "REVEL_SOURCE_TRANSCRIPT_BRIDGE_MISMATCH");
        else RevelPublisherIndexManifest.require(selected.equals(model.at("/transcriptBridge/refseqTranscript").asText())
                && "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE".equals(model.at("/transcriptBridge/status").asText())
                && model.at("/transcriptBridge/ensemblTranscript").asText().matches(java.util.regex.Pattern.quote(accession) + "\\.[1-9][0-9]*"), "REVEL_SOURCE_TRANSCRIPT_BRIDGE_MISMATCH");
    }

    private static boolean containsLocalPath(JsonNode node) {
        if (node.isTextual()) return localPath(node.asText());
        if (node.isObject()) for (var property : node.properties()) if (localPath(property.getKey())) return true;
        if (node.isContainerNode()) for (JsonNode child : node) if (containsLocalPath(child)) return true;
        return false;
    }

    private static boolean localPath(String value) {
        return value.startsWith("/") || value.startsWith("\\\\") || value.startsWith("file:")
                || value.matches("(?s).*(?:^|[^A-Za-z])[A-Za-z]:[\\\\/].*");
    }

    private void parse(String text, ObjectNode request, ArrayNode coordinate, ArrayNode matches) throws IOException {
        requireNotInterrupted();
        if (text == null) throw new IOException("REVEL_QUERY_OUTPUT_INCOMPLETE");
        RevelPublisherIndexManifest.require(text.length() <= BoundedTabixProcessRunner.MAX_STDOUT
                && (text.isEmpty() || text.endsWith("\n")), "REVEL_QUERY_OUTPUT_INCOMPLETE");
        JsonNode allele = request.at("/context/variant/evidenceQueryAllele");
        String sourceTranscript = request.at("/revelTranscriptBinding/accession").asText();
        Set<Long> ordinals = new HashSet<>(); long previous = 0;
        int begin = 0;
        while (begin < text.length()) {
            requireNotInterrupted();
            int end = text.indexOf('\n', begin); String line = text.substring(begin, end); begin = end + 1;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1); // tabix stdout transport only.
            RevelPublisherIndexManifest.require(coordinate.size() < MAX_ROWS && line.length() <= 16512 && line.chars().allMatch(c -> c > 0 && c < 128 && c != '\r'), "REVEL_QUERY_ROW_LIMIT_OR_ENCODING_INVALID");
            String[] columns = line.split("\t", -1);
            RevelPublisherIndexManifest.require(columns.length == 4 && columns[2].matches("[1-9][0-9]{0,8}"), "REVEL_INDEX_ROW_INVALID");
            long ordinal = Long.parseLong(columns[2]);
            RevelPublisherIndexManifest.require(ordinal <= index.sourceRows && ordinal > previous && ordinals.add(ordinal), "REVEL_SOURCE_ORDINAL_INVALID_OR_CONFLICTING"); previous = ordinal;
            String raw = columns[3]; String[] cells = raw.split(",", -1);
            RevelPublisherIndexManifest.require(raw.length() <= 16384 && cells.length == 9 && columns[0].equals(cells[0]) && columns[1].equals(cells[2])
                    && columns[0].equals(allele.path("contig").asText()) && columns[1].equals(allele.path("position").asText()), "REVEL_PUBLISHED_COORDINATE_MISMATCH");
            String rawSha = RevelPublisherIndexManifest.digest(raw.getBytes(StandardCharsets.US_ASCII));
            ObjectNode row = coordinate.addObject().put("recordId", "revel-member-" + index.binding.path("sourceArtifactSha256").asText() + "-data-row-" + ordinal)
                    .put("sourceLine", ordinal).put("rawCsv", raw).put("rawCsvSha256", rawSha)
                    .put("rawHashConvention", "ORIGINAL_ASCII_CSV_EXCLUDING_SINGLE_LF");
            RevelPublisherIndexManifest.require(cells[3].matches("[ACGT]") && cells[4].matches("[ACGT]") && !cells[3].equals(cells[4]), "REVEL_SOURCE_ALLELE_UNRESOLVED");
            if (!cells[3].equals(allele.path("reference").asText()) || !cells[4].equals(allele.path("alternate").asText())) continue;
            String[] transcripts = cells[8].split(";", -1); boolean selected = false;
            for (String transcript : transcripts) {
                RevelPublisherIndexManifest.require(transcript.matches("ENST[0-9]+"), "REVEL_SOURCE_TRANSCRIPT_UNRESOLVED");
                if (sourceTranscript.equals(transcript)) selected = true;
            }
            if (selected) matches.add(row.deepCopy());
        }
    }

    private ObjectNode evidence(JsonNode context, ArrayNode rows, String started, String failure, String finished) {
        ObjectNode carrier = mapper.createObjectNode().put("evidenceType", "COMPUTATIONAL_PREDICTION")
                .put("producedAt", finished).put("sourcePayloadSha256", adapter.canonicalSha256(rows));
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source").put("sourceId", index.binding.path("resourceId").asText())
                .put("resourceVersion", index.binding.path("resourceVersion").asText()).put("resourceSha256", index.binding.path("identityFingerprintSha256").asText())
                .put("retrievedAt", finished);
        ObjectNode locator = mapper.createObjectNode().put("locatorType", "TABLE").put("locatorValue", "revel_with_transcript_ids; one-based data rows; header excluded")
                .put("artifactSha256", index.binding.path("sourceArtifactSha256").asText()).put("retrievedAt", finished);
        source.putArray("sourceLocators").add(locator);
        ArrayNode records = source.putArray("sourceRecords"), measurements = carrier.putArray("measurements");
        for (JsonNode row : rows) records.addObject().put("recordId", row.path("recordId").asText()).set("locator", locator.deepCopy()
                .put("locatorValue", "revel_with_transcript_ids; data row " + row.path("sourceLine").asText() + "; header excluded; raw CSV sha256=" + row.path("rawCsvSha256").asText()));
        if (failure == null) {
            carrier.putObject("resourceExecution").put("status", "SUCCEEDED").put("attemptedAt", started).put("completedAt", finished);
            carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
            carrier.putObject("queryOutcome").put("status", rows.isEmpty() ? "NO_RECORD" : "HIT").put("recordCount", rows.size())
                    .put("queryFingerprint", adapter.canonicalSha256(context.at("/variant/evidenceQueryAllele")));
            carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
            // A consensus measurement is only a lossless projection. Runtime re-reads every raw row and verifies CDS/VEP.
            BigDecimal agreed = null; String rawScore = null; boolean consistent = !rows.isEmpty();
            for (JsonNode row : rows) {
                String score = row.path("rawCsv").asText().split(",", -1)[7];
                BigDecimal value = PredictionClausePolicy.decimal(mapper.getNodeFactory().textNode(score));
                if (value == null || agreed != null && agreed.compareTo(value) != 0) { consistent = false; break; }
                agreed = value; rawScore = score;
            }
            if (consistent && agreed != null && rawScore != null) measurements.addObject().put("metricId", "revel.score").put("rawValue", rawScore)
                    .put("normalizedValue", agreed.stripTrailingZeros().toPlainString()).put("unit", "dimensionless").put("calibrationProfileId", RevelPredictionObservation.INPUT_PROFILE);
        } else {
            carrier.putObject("resourceExecution").put("status", "FAILED").put("reasonCode", failure).put("attemptedAt", started).put("completedAt", finished);
            carrier.putObject("resourceAvailability").put("status", "UNAVAILABLE").put("reasonCode", failure);
            carrier.putObject("queryOutcome").put("status", "NOT_QUERIED").put("recordCount", 0);
            carrier.putObject("resourceApplicability").put("status", "UNKNOWN").put("reasonCode", failure);
        }
        return adapter.adapt(context, carrier);
    }
}
