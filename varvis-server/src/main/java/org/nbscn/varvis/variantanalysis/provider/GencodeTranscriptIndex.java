package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.lang.Nullable;

/** Pinned local GTF annotation plus qualified genomic sequence; no RNA, disease or policy approval. */
public final class GencodeTranscriptIndex {
    public static final String VERSION = "1.0.0";
    private record Entry(long offset, int length, String sha) { }
    private final ObjectMapper mapper;
    private final Path root, data, index;
    private final ObjectNode manifest;
    private final String pin;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, ObjectNode> models = new LinkedHashMap<>();
    private final Map<String, java.util.List<String>> geneRows = new LinkedHashMap<>();
    private boolean inventoryLoaded;
    private record InventoryEntry(String transcript, String hgncId, String symbol, long offset, int length, String sha) { }
    private final Map<String, java.util.List<InventoryEntry>> allInventory = new LinkedHashMap<>();
    @Nullable private BasicFileAttributes inventoryDataStamp, inventoryIndexStamp;
    private final BasicFileAttributes dataStamp, indexStamp, manifestStamp;

    public GencodeTranscriptIndex(ObjectMapper mapper, Path root, String manifestSha) throws IOException {
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.root = root.toRealPath(); this.pin = manifestSha;
        Path manifestFile = child("manifest.json");
        manifestStamp = stamp(manifestFile);
        var value = this.mapper.readTree(bytes(manifestFile, 65536, manifestSha));
        require(value instanceof ObjectNode, "GENCODE_MANIFEST_INVALID"); manifest = (ObjectNode) value;
        require("gencode-transcript-index-v1".equals(manifest.path("schemaVersion").asText())
                && VERSION.equals(manifest.path("methodVersion").asText()) && "GRCh38".equals(manifest.path("assembly").asText())
                && "TECHNICAL_MATERIAL_ONLY".equals(manifest.path("activation").asText())
                && manifest.path("release").isIntegralNumber() && manifest.path("release").asInt() > 0
                && manifest.at("/source/sha256").asText().matches("[0-9a-f]{64}")
                && manifest.at("/source/url").asText().equals("https://ftp.ebi.ac.uk/pub/databases/gencode/Gencode_human/release_"
                    + manifest.path("release").asInt() + "/gencode.v" + manifest.path("release").asInt() + ".annotation.gtf.gz")
                && manifest.path("independentRnaVerification").isBoolean() && !manifest.path("independentRnaVerification").asBoolean(), "GENCODE_MANIFEST_INVALID");
        require("annotations.jsonl".equals(manifest.at("/data/file").asText()) && "index.tsv".equals(manifest.at("/index/file").asText()), "GENCODE_FILES_INVALID");
        data = child("annotations.jsonl"); index = child("index.tsv");
        dataStamp = stamp(data); indexStamp = stamp(index);
        require(dataStamp.size() == manifest.at("/data/bytes").asLong() && dataStamp.size() <= 512_000_000
                && indexStamp.size() == manifest.at("/index/bytes").asLong(), "GENCODE_FILE_SIZE_MISMATCH");
        byte[] raw = bytes(index, 32_000_000, manifest.at("/index/sha256").asText());
        String text = new String(raw, StandardCharsets.US_ASCII);
        require(text.endsWith("\n"), "GENCODE_INDEX_INCOMPLETE");
        long end = 0;
        for (String line : text.split("\n")) {
            String[] fields = line.split("\t", -1);
            require(fields.length == 4 && fields[0].matches("ENST[0-9]+\\.[1-9][0-9]*")
                    && fields[1].matches("[0-9]{1,12}") && fields[2].matches("[1-9][0-9]{0,6}")
                    && fields[3].matches("[0-9a-f]{64}"), "GENCODE_INDEX_INVALID");
            long offset = Long.parseLong(fields[1]); int length = Integer.parseInt(fields[2]);
            require(offset == end && length <= 1_000_000 && offset + length <= dataStamp.size()
                    && entries.size() < 200_000 && entries.putIfAbsent(fields[0], new Entry(offset, length, fields[3])) == null, "GENCODE_INDEX_BOUNDS_OR_DUPLICATE");
            end = offset + length;
        }
        require(end == dataStamp.size() && entries.size() == manifest.at("/counts/indexed").asInt(), "GENCODE_INDEX_COUNT_MISMATCH");
        recheck();
    }

    public ObjectNode identity() {
        return mapper.createObjectNode().put("method", "EXACT_GENCODE_LOCAL_ANNOTATION").put("version", VERSION)
                .put("manifestSha256", pin).put("release", manifest.path("release").asInt()).put("indexedTranscripts", entries.size())
                .put("sourceGtfSha256", manifest.at("/source/sha256").asText()).put("independentRnaVerification", false)
                .put("clinicalAcceptance", false).put("networkAllowed", false);
    }

    /** Bounded, once-per-instance inventory of the pinned coding index. Excluded GTF
     * transcripts are deliberately not treated as biologically absent. No reference reads. */
    public synchronized ObjectNode inventory(ObjectNode context) throws IOException {
        recheck();
        if (manifest.has("inventory") || manifest.has("inventoryIndex")) return completeSourceInventory(context);
        if (!inventoryLoaded) {
            var collected = new LinkedHashMap<String, java.util.List<String>>();
            try (var input = new RandomAccessFile(data.toFile(), "r")) {
                for (var indexed : entries.entrySet()) {
                    require(!Thread.currentThread().isInterrupted(), "TRANSCRIPT_MATERIAL_INTERRUPTED");
                    var entry = indexed.getValue();
                    byte[] raw = new byte[entry.length()]; input.seek(entry.offset()); input.readFully(raw);
                    require(CodingTranscriptMaterialSupply.sha(raw).equals(entry.sha()), "GENCODE_ANNOTATION_HASH_MISMATCH");
                    var row = mapper.readTree(raw);
                    require(row instanceof ObjectNode && indexed.getKey().equals(row.path("transcript").asText())
                            && row.path("hgncId").asText().matches("HGNC:[0-9]+"), "GENCODE_INVENTORY_INVALID");
                    var rows = collected.computeIfAbsent(row.path("hgncId").asText(), ignored -> new java.util.ArrayList<>());
                    require(rows.size() < 1000, "GENCODE_GENE_TRANSCRIPT_LIMIT");
                    rows.add(indexed.getKey());
                }
            }
            recheck(); geneRows.putAll(collected); inventoryLoaded = true;
        }
        var out = mapper.createObjectNode().put("schemaVersion", "gencode-gene-inventory-v1")
                .put("coverage", "INDEXED_CODING_TRANSCRIPTS_ONLY").put("functionalCoverageComplete", false)
                .put("hgncId", context.at("/gene/hgncId").asText());
        out.set("identity", identity()); out.set("excludedCounts", manifest.path("counts").deepCopy());
        var rows = out.putArray("transcripts");
        try (var input = new RandomAccessFile(data.toFile(), "r")) {
        for (var tx : geneRows.getOrDefault(context.at("/gene/hgncId").asText(), java.util.List.of())) {
            var entry = java.util.Objects.requireNonNull(entries.get(tx));
            byte[] raw = new byte[entry.length()]; input.seek(entry.offset()); input.readFully(raw);
            require(CodingTranscriptMaterialSupply.sha(raw).equals(entry.sha()), "GENCODE_ANNOTATION_HASH_MISMATCH");
            var row = (ObjectNode) mapper.readTree(raw);
            require(row.path("geneSymbol").equals(context.at("/gene/symbol"))
                    && row.path("assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))
                    && row.path("contig").equals(context.at("/variant/evidenceQueryAllele/contig")), "GENCODE_CONTEXT_IDENTITY_MISMATCH");
            rows.add(row.put("annotationRowSha256", entry.sha()));
        }
        }
        recheck();
        return out;
    }

    /** Complete source enumeration is a structural coverage fact, never functional approval. */
    private ObjectNode completeSourceInventory(ObjectNode context) throws IOException {
        require("ALL_SOURCE_TRANSCRIPTS_BEFORE_MODEL_FILTER".equals(manifest.path("inventoryCoverage").asText())
                && "inventory.jsonl".equals(manifest.at("/inventory/file").asText())
                && "inventory-index.tsv".equals(manifest.at("/inventoryIndex/file").asText()), "GENCODE_INVENTORY_MANIFEST_INVALID");
        Path inventoryData = child("inventory.jsonl"), inventoryIndex = child("inventory-index.tsv");
        if (inventoryDataStamp == null) {
            var dataMetadata = stamp(inventoryData); var indexMetadata = stamp(inventoryIndex);
            require(dataMetadata.size() == manifest.at("/inventory/bytes").asLong() && dataMetadata.size() <= 1_000_000_000L
                    && indexMetadata.size() == manifest.at("/inventoryIndex/bytes").asLong(), "GENCODE_INVENTORY_SIZE_MISMATCH");
            var raw = bytes(inventoryIndex, 96_000_000, manifest.at("/inventoryIndex/sha256").asText());
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
            require(text.endsWith("\n"), "GENCODE_INVENTORY_INDEX_INCOMPLETE");
            long end = 0; var seen = new java.util.HashSet<String>();
            var collected = new LinkedHashMap<String, java.util.List<InventoryEntry>>();
            for (String line : text.split("\n")) {
                String[] f = line.split("\t", -1);
                require(f.length == 6 && !f[0].isBlank() && f[0].length() <= 100 && f[1].length() <= 100 && f[2].length() <= 100
                        && f[3].matches("[0-9]{1,12}") && f[4].matches("[1-9][0-9]{0,6}") && f[5].matches("[0-9a-f]{64}")
                        && seen.add(f[0]) && seen.size() <= 500_000, "GENCODE_INVENTORY_INDEX_INVALID");
                long offset = Long.parseLong(f[3]); int length = Integer.parseInt(f[4]);
                require(offset == end && length <= 1_000_000 && offset + length <= dataMetadata.size(), "GENCODE_INVENTORY_INDEX_BOUNDS");
                var entry = new InventoryEntry(f[0], f[1], f[2], offset, length, f[5]);
                for (String key : java.util.List.of("symbol:" + f[2], "hgnc:" + f[1])) {
                    if (key.endsWith(":")) continue;
                    var rows = collected.computeIfAbsent(key, ignored -> new java.util.ArrayList<>());
                    require(rows.size() < 1000, "GENCODE_GENE_TRANSCRIPT_LIMIT"); rows.add(entry);
                }
                end += length;
            }
            require(end == dataMetadata.size() && seen.size() == manifest.path("transcripts").asInt(), "GENCODE_INVENTORY_COUNT_MISMATCH");
            same(inventoryData, dataMetadata); same(inventoryIndex, indexMetadata);
            allInventory.putAll(collected); inventoryDataStamp = dataMetadata; inventoryIndexStamp = indexMetadata;
        }
        var dataMetadata = inventoryDataStamp; var indexMetadata = inventoryIndexStamp;
        require(dataMetadata != null && indexMetadata != null, "GENCODE_INVENTORY_UNAVAILABLE");
        if (dataMetadata == null || indexMetadata == null) throw new IllegalArgumentException("GENCODE_INVENTORY_UNAVAILABLE");
        same(inventoryData, dataMetadata); same(inventoryIndex, indexMetadata);
        var out = mapper.createObjectNode().put("schemaVersion", "gencode-gene-inventory-v1")
                .put("coverage", "ALL_SOURCE_TRANSCRIPTS_BEFORE_MODEL_FILTER").put("sourceCoverageComplete", true)
                .put("functionalCoverageComplete", false).put("hgncId", context.at("/gene/hgncId").asText());
        out.set("identity", identity()); var rows = out.putArray("transcripts");
        var selectedEntries = new java.util.LinkedHashSet<InventoryEntry>();
        selectedEntries.addAll(allInventory.getOrDefault("symbol:" + context.at("/gene/symbol").asText(), java.util.List.of()));
        selectedEntries.addAll(allInventory.getOrDefault("hgnc:" + context.at("/gene/hgncId").asText(), java.util.List.of()));
        require(selectedEntries.size() <= 1000, "GENCODE_GENE_TRANSCRIPT_LIMIT");
        try (var input = new RandomAccessFile(inventoryData.toFile(), "r")) {
            for (var entry : selectedEntries) {
                require(!Thread.currentThread().isInterrupted(), "TRANSCRIPT_MATERIAL_INTERRUPTED");
                byte[] raw = new byte[entry.length()]; input.seek(entry.offset()); input.readFully(raw);
                require(CodingTranscriptMaterialSupply.sha(raw).equals(entry.sha()), "GENCODE_INVENTORY_HASH_MISMATCH");
                var value = mapper.readTree(raw);
                require(value instanceof ObjectNode && entry.transcript().equals(value.path("transcript").asText())
                        && entry.symbol().equals(value.path("geneSymbol").asText()) && entry.hgncId().equals(value.path("hgncId").asText()),
                        "GENCODE_INVENTORY_IDENTITY_MISMATCH");
                var row = (ObjectNode) value;
                if (!row.path("hgncId").equals(context.at("/gene/hgncId"))) out.put("sourceCoverageComplete", false);
                rows.add(row.put("annotationRowSha256", entry.sha()));
            }
        }
        if (rows.isEmpty()) out.put("sourceCoverageComplete", false);
        same(inventoryData, dataMetadata); same(inventoryIndex, indexMetadata); recheck(); return out;
    }

    /** A missing exact version is never replaced by a neighboring version or a different transcript. */
    public synchronized ObjectNode annotation(ObjectNode context) throws IOException {
        recheck();
        require(!Thread.currentThread().isInterrupted(), "TRANSCRIPT_MATERIAL_INTERRUPTED");
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        Entry entry = entries.get(tx);
        if (entry == null) {
            throw new IllegalArgumentException("GENCODE_EXACT_TRANSCRIPT_VERSION_UNAVAILABLE");
        }
        byte[] raw = new byte[entry.length()];
        try (var input = new RandomAccessFile(data.toFile(), "r")) { input.seek(entry.offset()); input.readFully(raw); }
        require(CodingTranscriptMaterialSupply.sha(raw).equals(entry.sha()), "GENCODE_ANNOTATION_HASH_MISMATCH");
        var row = mapper.readTree(raw);
        require(row instanceof ObjectNode && tx.equals(row.path("transcript").asText())
                && context.at("/gene/hgncId").equals(row.path("hgncId")) && context.at("/gene/symbol").equals(row.path("geneSymbol"))
                && context.at("/variant/evidenceQueryAllele/assembly").equals(row.path("assembly"))
                && context.at("/variant/evidenceQueryAllele/contig").equals(row.path("contig")), "GENCODE_CONTEXT_IDENTITY_MISMATCH");
        require(row.path("exons").isArray() && !row.path("exons").isEmpty() && row.path("exons").size() <= 1000
                && row.path("strand").isIntegralNumber() && Math.abs(row.path("strand").asInt()) == 1, "GENCODE_EXON_INVALID");
        recheck();
        return ((ObjectNode) row).deepCopy();
    }

    public synchronized ObjectNode model(ObjectNode context, CodingTranscriptMaterialSupply.ReferenceReader reader) throws IOException {
        var row = annotation(context);
        String tx = row.path("transcript").asText();
        Entry entry = java.util.Objects.requireNonNull(entries.get(tx));
        String contig = row.path("contig").asText();
        require(contig.matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y)"), "GENCODE_PRIMARY_CONTIG_REQUIRED");
        int first = row.path("exons").get(0).path("genomicStart").asInt();
        var witness = span(reader, contig, first, 1, null);
        require(witness.identity().path("expectedSha256").asText().matches("[0-9a-f]{64}")
                && witness.identity().path("expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")), "TRANSCRIPT_MATERIAL_CONTEXT_REFERENCE_MISMATCH");
        String key = entry.sha() + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(witness.identity());
        var cached = models.get(key);
        if (cached != null) { recheck(); return cached.deepCopy(); }
        var sequence = new StringBuilder();
        long total = 0;
        for (var exon : row.path("exons")) {
            require(exon.path("genomicStart").isIntegralNumber() && exon.path("genomicStart").canConvertToInt()
                    && exon.path("genomicEnd").isIntegralNumber() && exon.path("genomicEnd").canConvertToInt(), "GENCODE_EXON_INVALID");
            int start = exon.path("genomicStart").asInt(), end = exon.path("genomicEnd").asInt();
            total += (long) end - start + 1;
            require(start > 0 && end >= start && total <= 1_000_000, "GENCODE_TRANSCRIPT_BASE_LIMIT");
            var part = new StringBuilder();
            for (long position = start; position <= end;) {
                int length = (int) Math.min(100_000L, end - position + 1);
                part.append(span(reader, contig, (int) position, length, witness.identity()).bases()); position += length;
            }
            sequence.append(CodingTranscriptMapping.orientAllele(row, part.toString()));
        }
        long offset = 1L - row.at("/exons/0/cdsStart").asInt();
        int codingLength = row.path("cdsLength").asInt();
        require(total == row.path("transcriptLength").asLong() && offset >= 0 && codingLength >= 6
                && offset + codingLength <= sequence.length(), "GENCODE_CDS_BOUNDS_INVALID");
        var model = row.deepCopy(); model.remove("rawGtfSha256");
        model.put("schemaVersion", "native-coding-transcript-model-v1").put("modelId", "GENCODE_" + manifest.path("release").asInt() + "_" + tx)
                .put("modelVersion", VERSION).put("cdsSequence", sequence.substring((int) offset, (int) offset + codingLength))
                .put("proteinLength", codingLength / 3 - 1).put("usageScope", "PREDICTION_SEQUENCE_CHECK_ONLY");
        var verification = model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(sequence.toString()))
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText())).put("queriedBases", total)
                .put("independentRnaVerification", false).put("method", "PINNED_GTF_PLUS_QUALIFIED_REFERENCE");
        verification.set("referenceIdentity", witness.identity().deepCopy());
        model.putArray("sourceRecords").addObject().put("sourceId", "GENCODE:" + tx).put("sourceKind", "GENCODE_GTF_LOCAL_SNAPSHOT")
                .put("version", "v" + manifest.path("release").asInt()).put("sha256", manifest.at("/source/sha256").asText())
                .put("url", manifest.at("/source/url").asText()).put("annotationRowSha256", entry.sha())
                .put("rawGtfSha256", row.path("rawGtfSha256").asText()).put("indexManifestSha256", pin);
        String issue = CodingTranscriptMapping.modelIssue(model, context); require(issue == null, issue);
        span(reader, contig, first, 1, witness.identity()); recheck();
        if (models.size() >= 128) models.remove(models.keySet().iterator().next());
        models.put(key, model.deepCopy()); return model;
    }

    private static CodingTranscriptMaterialSupply.ReferenceSpan span(CodingTranscriptMaterialSupply.ReferenceReader reader,
            String contig, int start, int length, @Nullable ObjectNode expected) throws IOException {
        require(!Thread.currentThread().isInterrupted(), "TRANSCRIPT_MATERIAL_INTERRUPTED");
        require(start > 0, "GENCODE_EXON_INVALID");
        var result = reader.read(contig, start, length);
        if (!(result != null && result.identity() != null && !result.identity().isEmpty() && result.bases() != null
                && result.bases().length() == length && result.bases().matches("[ACGT]+"))) {
            throw new IllegalArgumentException("TRANSCRIPT_REFERENCE_SPAN_INVALID");
        }
        require(expected == null || expected.equals(result.identity()), "TRANSCRIPT_REFERENCE_IDENTITY_CHANGED");
        return result;
    }
    private Path child(String name) throws IOException {
        Path path = root.resolve(name).toRealPath(); require(path.getParent().equals(root), "GENCODE_PATH_OUTSIDE_ROOT"); return path;
    }
    private static byte[] bytes(Path path, int limit, String pin) throws IOException {
        require(pin.matches("[0-9a-f]{64}") && Files.size(path) <= limit, "GENCODE_FILE_INVALID");
        byte[] raw; try (var stream = Files.newInputStream(path)) { raw = stream.readNBytes(limit + 1); }
        require(raw.length <= limit && CodingTranscriptMaterialSupply.sha(raw).equals(pin), "GENCODE_FILE_HASH_MISMATCH"); return raw;
    }
    private static BasicFileAttributes stamp(Path path) throws IOException {
        var value = Files.readAttributes(path, BasicFileAttributes.class); require(value.isRegularFile(), "GENCODE_FILE_INVALID"); return value;
    }
    private void recheck() throws IOException {
        same(data, dataStamp); same(index, indexStamp); same(root.resolve("manifest.json"), manifestStamp);
    }
    private void same(Path path, BasicFileAttributes expected) throws IOException {
        require(path.toRealPath().getParent().equals(root), "GENCODE_PATH_OUTSIDE_ROOT");
        var actual = stamp(path);
        require(actual.size() == expected.size() && actual.lastModifiedTime().equals(expected.lastModifiedTime())
                && java.util.Objects.equals(actual.fileKey(), expected.fileKey()), "GENCODE_RESOURCE_CHANGED");
    }
    private static void require(boolean value, @Nullable String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
