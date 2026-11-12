package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import htsjdk.tribble.readers.TabixReader;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only exact-record access to the versioned ClinVar comparator sidecar. */
public final class ClinVarComparatorSidecar {

    private final ObjectMapper mapper;
    private final Path sidecar;
    private final String resourceId;
    private final String resourceVersion;
    private final String identityFingerprintSha256;

    public ClinVarComparatorSidecar(ObjectMapper mapper,
                                    Path sidecar,
                                    String resourceId,
                                    String resourceVersion,
                                    String identityFingerprintSha256) {
        this.mapper = mapper;
        this.sidecar = sidecar;
        this.resourceId = resourceId;
        this.resourceVersion = resourceVersion;
        this.identityFingerprintSha256 = identityFingerprintSha256;
    }

    public LookupResult lookup(NormalizedVariantKey allele, String variationId) {
        if (allele == null || !hasText(variationId)
                || !variationId.matches("[1-9][0-9]*")) {
            return LookupResult.notEvaluated("COMPARATOR_LOOKUP_CONTEXT_INCOMPLETE", provenance(null, variationId));
        }
        if (!"GRCh38".equals(allele.getGenomeAssembly())
                || !singleBase(allele.getReference())
                || !singleBase(allele.getAlternate())) {
            return LookupResult.notEvaluated("COMPARATOR_SCOPE_NOT_APPLICABLE", provenance(allele, variationId));
        }
        if (!available()) {
            return LookupResult.failed("COMPARATOR_SIDECAR_UNAVAILABLE", provenance(allele, variationId));
        }
        try (TabixReader reader = new TabixReader(sidecar.toAbsolutePath().normalize().toString())) {
            return lookupExact(reader, allele, variationId);
        } catch (IOException | RuntimeException failure) {
            return LookupResult.failed("COMPARATOR_SIDECAR_PARSE_FAILED", provenance(allele, variationId));
        }
    }

    record Query(NormalizedVariantKey allele, String variationId) { }

    /** Bounded PM1 query. Native tabix reuses BGZF blocks across all requested IDs. */
    List<LookupResult> lookupBatch(List<Query> queries, String tabixExecutable) {
        if (!available()) return failedQueries(queries, "COMPARATOR_SIDECAR_UNAVAILABLE");
        if (queries.size() > 128 || tabixExecutable.isBlank()) return failedQueries(queries, "COMPARATOR_SIDECAR_BATCH_SCOPE_INVALID");
        if (queries.isEmpty()) return List.of();
        try {
            var byId = new HashMap<String, Set<Query>>();
            for (var query : queries) byId.computeIfAbsent(query.variationId(), ignored -> new HashSet<>()).add(query);
            Path file = sidecar.toAbsolutePath().normalize();
            var command = new ArrayList<>(List.of(tabixExecutable, "--cache", "64", file.toString()));
            for (String id : byId.keySet().stream().sorted().toList()) {
                if (!id.matches("[1-9][0-9]{0,9}")) throw new IOException("COMPARATOR_SIDECAR_BATCH_SCOPE_INVALID");
                command.add("VID:" + id + "-" + id);
            }
            var process = BoundedTabixProcessRunner.clinVarSidecar().run(command, java.util.Objects.requireNonNull(file.getParent()),
                    java.time.Duration.ofSeconds(10));
            var results = new HashMap<Query, LookupResult>();
            for (String line : process.getOutput().lines().toList()) collectMatches(line, byId, results);
            return queries.stream().map(q -> results.getOrDefault(q, LookupResult.noRecord(provenance(q.allele(), q.variationId())))).toList();
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage();
            return failedQueries(queries, reason != null && reason.matches("COMPARATOR_SIDECAR_[A-Z_]+")
                    ? reason : "COMPARATOR_SIDECAR_BATCH_UNAVAILABLE");
        }
    }

    private void collectMatches(String line, Map<String, Set<Query>> byId, Map<Query, LookupResult> results) throws IOException {
        int end = line.indexOf('\t', 4);
        if (!line.startsWith("VID\t") || end < 0) throw new IOException("COMPARATOR_SIDECAR_PARSE_FAILED");
        var queries = byId.get(line.substring(4, end));
        if (queries == null) throw new IOException("COMPARATOR_SIDECAR_BATCH_SCOPE_INVALID");
        for (var query : queries) {
            var current = results.get(query);
            if (current != null && current.getStatus() == Status.FAILED) continue;
            var source = provenance(query.allele(), query.variationId());
            try {
                var record = parse(line, source);
                if (record.matches(query.allele(), query.variationId()))
                    results.put(query, current == null ? LookupResult.hit(record, source)
                            : LookupResult.notEvaluated("COMPARATOR_RECORD_AMBIGUOUS", source));
            } catch (IOException | RuntimeException invalid) {
                results.put(query, LookupResult.failed("COMPARATOR_SIDECAR_PARSE_FAILED", source));
            }
        }
    }

    private boolean available() {
        return mapper != null && sidecar != null && Files.isRegularFile(sidecar)
                && (Files.isRegularFile(sidecar.resolveSibling(sidecar.getFileName() + ".tbi"))
                || Files.isRegularFile(sidecar.resolveSibling(sidecar.getFileName() + ".csi")));
    }

    private List<LookupResult> failedQueries(List<Query> queries, String reason) {
        return queries.stream().map(q -> LookupResult.failed(reason, provenance(q.allele(), q.variationId()))).toList();
    }

    private LookupResult lookupExact(TabixReader reader, NormalizedVariantKey allele, String variationId) throws IOException {
        var source = provenance(allele, variationId);
        var iterator = reader.query("VID:" + variationId + "-" + variationId);
        var matches = new ArrayList<ComparatorRecord>();
        String line;
        while (iterator != null && (line = iterator.next()) != null) {
            var record = parse(line, source);
            if (record.matches(allele, variationId)) matches.add(record);
        }
        if (matches.isEmpty()) {
            return LookupResult.noRecord(source);
        }
        if (matches.size() != 1) {
            return LookupResult.notEvaluated("COMPARATOR_RECORD_AMBIGUOUS", source);
        }
        return LookupResult.hit(matches.get(0), source);
    }

    private ComparatorRecord parse(String line, Provenance provenance) throws IOException {
        String[] columns = line.split("\\t", -1);
        if (columns.length != 13) {
            throw new IOException("Comparator sidecar row must contain 13 columns");
        }
        if (!"VID".equals(columns[0]) || !columns[1].equals(columns[2])
                || !columns[1].equals(columns[3])) {
            throw new IOException("Comparator sidecar Variation ID index is invalid");
        }
        positiveInteger(columns[1]);
        int position = positiveInteger(columns[6]);
        ArrayNode genes = array(columns[9]);
        ArrayNode variantSummaryRecords = array(columns[11]);
        ArrayNode submissions = array(columns[12]);
        List<String> geneSymbols = new ArrayList<String>();
        for (JsonNode gene : genes) {
            if (!gene.isTextual() || !hasText(gene.asText())) {
                throw new IOException("gene_symbols_json must contain strings");
            }
            geneSymbols.add(gene.asText());
        }
        return new ComparatorRecord(
                columns[5], position, position, columns[3], columns[4], columns[7], columns[8],
                geneSymbols, columns[10], variantSummaryRecords, submissions, provenance);
    }

    private ArrayNode array(String value) throws IOException {
        JsonNode parsed = mapper.readTree(value);
        if (parsed == null || !parsed.isArray()) {
            throw new IOException("Comparator sidecar JSON column must be an array");
        }
        return (ArrayNode) parsed;
    }

    private int positiveInteger(String value) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new NumberFormatException("not positive");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException("Comparator sidecar coordinate is invalid", failure);
        }
    }

    private Provenance provenance(NormalizedVariantKey allele, String variationId) {
        String logicalName = sidecar == null || sidecar.getFileName() == null
                ? null : sidecar.getFileName().toString();
        String locator = allele == null ? null
                : CoordinateUtils.normalizeChromosome(allele.getChromosome())
                + ":" + allele.getPosition() + "-" + allele.getPosition()
                + ";variationId=" + variationId;
        return new Provenance(resourceId, resourceVersion, identityFingerprintSha256, logicalName, locator);
    }

    private boolean singleBase(String value) {
        return value != null && value.matches("[ACGTacgt]");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public enum Status {
        HIT,
        NO_RECORD,
        NOT_EVALUATED,
        FAILED
    }

    public static final class LookupResult {
        private final Status status;
        private final String reasonCode;
        private final ComparatorRecord record;
        private final Provenance provenance;

        private LookupResult(Status status,
                             String reasonCode,
                             ComparatorRecord record,
                             Provenance provenance) {
            this.status = status;
            this.reasonCode = reasonCode;
            this.record = record;
            this.provenance = provenance;
        }

        private static LookupResult hit(ComparatorRecord record, Provenance provenance) {
            return new LookupResult(Status.HIT, null, record, provenance);
        }

        private static LookupResult noRecord(Provenance provenance) {
            return new LookupResult(Status.NO_RECORD, "COMPARATOR_RECORD_NOT_FOUND", null, provenance);
        }

        private static LookupResult notEvaluated(String reasonCode, Provenance provenance) {
            return new LookupResult(Status.NOT_EVALUATED, reasonCode, null, provenance);
        }

        private static LookupResult failed(String reasonCode, Provenance provenance) {
            return new LookupResult(Status.FAILED, reasonCode, null, provenance);
        }

        public Status getStatus() {
            return status;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public ComparatorRecord getRecord() {
            return record;
        }

        public Provenance getProvenance() {
            return provenance;
        }
    }

    public static final class ComparatorRecord {
        private final String chromosome;
        private final int start;
        private final int end;
        private final String variationId;
        private final String assembly;
        private final String reference;
        private final String alternate;
        private final List<String> geneSymbols;
        private final String conflictState;
        private final ArrayNode variantSummaryRecords;
        private final ArrayNode submissions;
        private final Provenance provenance;

        private ComparatorRecord(String chromosome,
                                 int start,
                                 int end,
                                 String variationId,
                                 String assembly,
                                 String reference,
                                 String alternate,
                                 List<String> geneSymbols,
                                 String conflictState,
                                 ArrayNode variantSummaryRecords,
                                 ArrayNode submissions,
                                 Provenance provenance) {
            this.chromosome = chromosome;
            this.start = start;
            this.end = end;
            this.variationId = variationId;
            this.assembly = assembly;
            this.reference = reference;
            this.alternate = alternate;
            this.geneSymbols = Collections.unmodifiableList(new ArrayList<String>(geneSymbols));
            this.conflictState = conflictState;
            this.variantSummaryRecords = variantSummaryRecords.deepCopy();
            this.submissions = submissions.deepCopy();
            this.provenance = provenance;
        }

        private boolean matches(NormalizedVariantKey allele, String requestedVariationId) {
            return requestedVariationId.equals(variationId)
                    && "GRCh38".equals(assembly)
                    && CoordinateUtils.normalizeChromosome(allele.getChromosome()).equals(
                    CoordinateUtils.normalizeChromosome(chromosome))
                    && allele.getPosition() == start
                    && start == end
                    && allele.getReference().equalsIgnoreCase(reference)
                    && allele.getAlternate().equalsIgnoreCase(alternate);
        }

        public String getChromosome() { return chromosome; }
        public int getStart() { return start; }
        public int getEnd() { return end; }
        public String getVariationId() { return variationId; }
        public String getAssembly() { return assembly; }
        public String getReference() { return reference; }
        public String getAlternate() { return alternate; }
        public List<String> getGeneSymbols() { return geneSymbols; }
        public String getConflictState() { return conflictState; }
        public ArrayNode getVariantSummaryRecords() { return variantSummaryRecords.deepCopy(); }
        public ArrayNode getSubmissions() { return submissions.deepCopy(); }
        public Provenance getProvenance() { return provenance; }
    }

    public static final class Provenance {
        private final String resourceId;
        private final String resourceVersion;
        private final String identityFingerprintSha256;
        private final String logicalName;
        private final String recordLocator;

        private Provenance(String resourceId,
                           String resourceVersion,
                           String identityFingerprintSha256,
                           String logicalName,
                           String recordLocator) {
            this.resourceId = resourceId;
            this.resourceVersion = resourceVersion;
            this.identityFingerprintSha256 = identityFingerprintSha256;
            this.logicalName = logicalName;
            this.recordLocator = recordLocator;
        }

        public String getResourceId() { return resourceId; }
        public String getResourceVersion() { return resourceVersion; }
        public String getIdentityFingerprintSha256() { return identityFingerprintSha256; }
        public String getLogicalName() { return logicalName; }
        public String getRecordLocator() { return recordLocator; }
    }
}
