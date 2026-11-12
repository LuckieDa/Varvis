package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.tribble.readers.TabixReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;

/** Bounded precomputed acquisition only. A local filename or configured version is not scientific qualification. */
public final class SpliceAiLocalObservationSession {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final Path snv, snvIndex, indel, indelIndex;
    private final boolean enabled;
    private final Duration timeout;
    private final Map<String, Capture> observations = new LinkedHashMap<>();
    private int calls;

    SpliceAiLocalObservationSession(ObjectMapper mapper, VariantAnalysisProperties.SpliceAi settings) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        snv = settings.getSnvResourcePath(); snvIndex = index(snv, settings.getSnvIndexPath());
        indel = settings.getIndelResourcePath(); indelIndex = index(indel, settings.getIndelIndexPath());
        enabled = settings.isEnabled(); timeout = settings.normalizedTimeout();
    }

    public record Capture(SharedSpliceObservation observation, ExplicitContextSourceLedger source, SourceRef sourceReference) { }
    public int providerCalls() { return calls; }

    /** Caller supplies an already verified query identity; no source/normalization inference is performed here. */
    public Capture observe(NormalizedVariantKey key, String dataClass) {
        if (!(key != null && "GRCh38".equals(key.getGenomeAssembly()) && key.getPosition() > 0
                && key.getChromosome() != null && !key.getChromosome().isBlank()
                && key.getReference() != null && key.getReference().matches("[ACGT]{1,50}")
                && key.getAlternate() != null && key.getAlternate().matches("[ACGT]{1,50}")
                && !key.getReference().equals(key.getAlternate()))) {
            throw new IllegalArgumentException("SPLICE_QUERY_IDENTITY_MISMATCH");
        }
        require(dataClass != null && Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(dataClass), "SPLICE_DATA_CLASS_INVALID");
        ObjectNode query = mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
        boolean single = key.getReference().length() == 1 && key.getAlternate().length() == 1;
        Path data = single ? snv : indel, idx = single ? snvIndex : indelIndex;
        ObjectNode raw = mapper.createObjectNode().put("schemaVersion", SharedSpliceObservation.SOURCE_SCHEMA)
                .put("queryMethod", "HTSJDK_TABIX_POINT_LOOKUP").put("queryLibraryVersion", "2.24.1");
        raw.set("queryAllele", query); raw.putArray("records");
        ObjectNode resource = raw.putObject("resource").put("model", "SPLICEAI").put("origin", "PRECOMPUTED")
                .put("toolVersion", "UNKNOWN").put("scoreMode", "UNKNOWN").put("dataVersion", "UNKNOWN")
                .put("calibrationCompatibility", "UNVERIFIED").put("resourceIntegrity", "LOCAL_METADATA_ONLY");
        resource.putObject("parameters").put("status", "UNKNOWN").putNull("D").putNull("M");
        resource.putObject("annotation").put("status", "UNKNOWN").putNull("source").putNull("version")
                .putNull("transcriptSet").putNull("referenceSha256").putNull("modelSha256");
        raw.putObject("association").put("status", "NOT_EVALUATED").put("reasonCode", "SELECTED_TRANSCRIPT_ASSOCIATION_NOT_ESTABLISHED");
        raw.putObject("compatibility").put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED");
        if (!enabled) return capture(states(raw, "DISABLED", "UNKNOWN", "UNKNOWN", "NOT_QUERIED", "SPLICEAI_DISABLED"), dataClass);
        if (Thread.currentThread().isInterrupted()) return capture(states(raw, "NOT_RUN", "UNKNOWN", "UNKNOWN", "NOT_QUERIED", "SPLICE_QUERY_INTERRUPTED"), dataClass);
        if (data == null || idx == null || !Files.isRegularFile(data) || !Files.isRegularFile(idx))
            return capture(states(raw, "NOT_RUN", "UNAVAILABLE", "UNKNOWN", "NOT_QUERIED", "SPLICE_LOCAL_RESOURCE_UNAVAILABLE"), dataClass);
        String cacheKey = null;
        try {
            ObjectNode dataStamp = stamp(data), indexStamp = stamp(idx);
            resource.set("dataArtifact", dataStamp); resource.set("indexArtifact", indexStamp);
            // Session-local metadata is a cache dependency, not a claim of a full-file checksum.
            cacheKey = adapter.canonicalSha256(query) + ":" + dataClass + ":" + data.toAbsolutePath().normalize()
                    + ":" + idx.toAbsolutePath().normalize() + ":" + adapter.canonicalSha256(resource);
            Capture cached = observations.get(cacheKey);
            if (cached != null) return cached;
            calls++;
            long deadline = System.nanoTime() + timeout.toNanos();
            // HTSJDK 2.24 TabixReader does not close its index stream when construction fails.
            // Parse the selected local index with an owned stream before asking that reader to open it.
            require(Files.size(idx) <= 16_777_216, "SPLICE_INDEX_LIMIT_EXCEEDED");
            try (var indexInput = new htsjdk.samtools.util.BlockCompressedInputStream(idx.toFile())) {
                new htsjdk.tribble.index.tabix.TabixIndex(indexInput);
            }
            require(System.nanoTime() <= deadline, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
            // Own the data handle even if TabixReader construction rejects a corrupt index.
            try (var stream = new htsjdk.samtools.seekablestream.SeekableFileStream(data.toFile());
                 TabixReader reader = new TabixReader(data.toString(), idx.toString(), stream)) {
                var headers = resource.putArray("rawHeader"); String line; int headerBytes = 0;
                while ((line = reader.readLine()) != null && line.startsWith("#")) {
                    require(System.nanoTime() <= deadline, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
                    headerBytes += line.length(); require(headerBytes <= 65536, "SPLICE_HEADER_LIMIT_EXCEEDED");
                    headers.add(line);
                    if (line.startsWith("##fileDate=")) resource.put("dataVersion", line.substring("##fileDate=".length()));
                    if (line.startsWith("##INFO=<ID=SpliceAI,")) resource.put("toolDescription", line);
                }
                resource.put("headerSha256", adapter.canonicalSha256(headers));
                if (data.getFileName().toString().contains(".masked.")) {
                    resource.put("scoreMode", "MASKED").put("scoreModeEvidence", "RESOURCE_FILENAME");
                    ((ObjectNode) raw.path("compatibility")).put("reasonCode", "SPLICE_PARAMETERS_INCOMPATIBLE");
                }
                boolean assemblyProven = false;
                for (var header : headers) if (Set.of("##reference=GRCh38", "##reference=hg38", "##reference=GRCh38/hg38").contains(header.asText())) assemblyProven = true;
                require(assemblyProven, "SPLICE_RESOURCE_ASSEMBLY_UNVERIFIED");
                require(System.nanoTime() <= deadline, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
                int count = 0, sourceBytes = 0, retainedBytes = 0;
                for (String region : SpecialistProviderSupport.tabixRegions(key)) {
                    require(System.nanoTime() <= deadline, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
                    var iterator = reader.query(region);
                    while (iterator != null && (line = iterator.next()) != null) {
                        require(!Thread.currentThread().isInterrupted(), "SPLICE_QUERY_INTERRUPTED");
                        require(++count <= 512 && System.nanoTime() <= deadline, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
                        sourceBytes += line.length(); require(sourceBytes <= 1_000_000, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
                        String[] columns = line.split("\t", -1);
                        if (columns.length < 8 || !SpliceAiProvider.matchesVcfCoordinates(columns, key)) continue;
                        raw.withArray("/rawRecords").add(line);
                        String value = SpliceAiProvider.infoValue(columns[7], "SpliceAI");
                        if (!(value != null && !value.isBlank())) {
                            throw new IllegalArgumentException("SPLICE_RECORD_FIELDS_INCOMPLETE");
                        }
                        boolean matchedAnnotation = false;
                        for (String entry : value.split(",", -1)) {
                            String[] fields = entry.split("\\|", -1);
                            if (fields.length == 0 || !key.getAlternate().equals(fields[0])) continue;
                            matchedAnnotation = true;
                            require(fields.length == 10 && !fields[1].isBlank(), "SPLICE_RECORD_FIELDS_INCOMPLETE");
                            retainedBytes += line.length() + entry.length();
                            require(retainedBytes <= 1_000_000, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
                            ObjectNode record = mapper.createObjectNode().put("allele", fields[0]).put("geneSymbol", fields[1])
                                    .put("symbol", fields[1]).putNull("transcriptId").put("transcriptAssociationStatus", "UNKNOWN")
                                    .put("sourceRow", line).put("sourceRowSha256", PredictionCalibration.sha256(line.getBytes(StandardCharsets.UTF_8)))
                                    .put("sourceAnnotation", entry);
                            for (int i = 0; i < 4; i++) {
                                String kind = List.of("AG", "AL", "DG", "DL").get(i);
                                require(PredictionCalibration.decimal(fields[2 + i]) != null && fields[6 + i].matches("[+-]?[0-9]{1,10}"), "SPLICE_EVENT_FIELDS_INCOMPLETE");
                                record.put("DS_" + kind, fields[2 + i]); record.put("DP_" + kind, Integer.parseInt(fields[6 + i]));
                                record.put("DP_" + kind + "_original", fields[6 + i]);
                            }
                            raw.withArray("/records").add(record);
                        }
                        require(matchedAnnotation, "SPLICE_RECORD_ALLELE_MISMATCH");
                    }
                }
            }
            require(System.nanoTime() <= deadline, "SPLICE_QUERY_LIMIT_OR_TIMEOUT");
            require(dataStamp.equals(stamp(data)) && indexStamp.equals(stamp(idx)), "SPLICE_RESOURCE_CHANGED_DURING_QUERY");
            Capture result = capture(states(raw, "SUCCEEDED", "AVAILABLE", "APPLICABLE",
                    raw.path("records").isEmpty() ? "NO_RECORD" : "HIT", raw.path("records").isEmpty() ? "SPLICE_EXACT_ALLELE_NO_RECORD" : "SPLICE_RAW_RECORDS_OBTAINED"), dataClass);
            observations.put(cacheKey, result);
            return result;
        } catch (IOException | RuntimeException failure) {
            String reason = failure instanceof IllegalArgumentException && failure.getMessage() != null && failure.getMessage().startsWith("SPLICE_")
                    ? failure.getMessage() : "SPLICE_LOCAL_QUERY_FAILED";
            raw.put("exceptionType", failure.getClass().getSimpleName());
            Capture failed = capture(states(raw, "FAILED", "AVAILABLE", "UNKNOWN", "NOT_QUERIED", reason), dataClass);
            // Retain the diagnostic as a failed attempt, never as a successful fact or NO_RECORD.
            if (cacheKey != null) observations.put(cacheKey, failed);
            return failed;
        }
    }

    private Capture capture(ObjectNode raw, String dataClass) {
        try {
            var envelope = mapper.createObjectNode().put("source", "LOCAL_SPLICEAI_PRECOMPUTED").put("version", "1.0.0").put("dataClass", dataClass);
            envelope.putObject("records").set("observation", raw);
            byte[] bytes = mapper.writeValueAsBytes(envelope);
            var source = ExplicitContextSourceLedger.verifiedBytes(mapper, bytes, PredictionCalibration.sha256(bytes));
            var ref = source.reference("observation");
            return new Capture(SharedSpliceObservation.capture(mapper, raw.path("queryAllele"), ref, List.of(source)), source, ref);
        } catch (IOException invalid) { throw new IllegalStateException("SPLICE_SOURCE_CAPTURE_FAILED", invalid); }
    }
    private ObjectNode states(ObjectNode raw, String execution, String availability, String applicability, String query, String reason) {
        raw.putObject("resourceExecution").put("status", execution).put("reasonCode", reason);
        raw.putObject("resourceAvailability").put("status", availability);
        raw.putObject("resourceApplicability").put("status", applicability);
        raw.putObject("queryOutcome").put("status", query).put("recordCount", "HIT".equals(query) ? raw.path("records").size() : 0);
        return raw;
    }
    private ObjectNode stamp(Path path) throws IOException {
        return mapper.createObjectNode().put("name", path.getFileName().toString()).put("sizeBytes", Files.size(path))
                .put("mtimeMillis", Files.getLastModifiedTime(path).toMillis());
    }
    private static Path index(Path data, Path configured) { return configured != null ? configured : data == null ? null : Path.of(data + ".tbi"); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
