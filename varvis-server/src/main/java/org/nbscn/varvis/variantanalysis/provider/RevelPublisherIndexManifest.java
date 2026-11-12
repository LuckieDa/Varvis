package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Trusted mechanical index receipt. Large artifacts reuse its full-build checksum and current metadata. */
final class RevelPublisherIndexManifest {
    static final String DATA = "revel-grch38.lookup.tsv.gz";
    static final String INDEX = DATA + ".tbi";
    static final String EXCLUDED = "revel-unlocated-source-rows.tsv.gz";
    static final String SOURCE_LINE = "One-based data-row ordinal; header excluded. rawCsv excludes its original single LF; output adds one LF.";
    static final String VERSION_STATUS = "UNVERSIONED_SOURCE_NO_VERSION_EQUIVALENCE_CLAIM";
    private static final long MAX_DISK = 20_000_000_000L;
    private static final int MAX_JSON = 2 * 1024 * 1024;
    final Path root, tabix;
    final ObjectNode binding, provenance;
    final long sourceRows;
    final Set<String> contigs;
    private final Map<Path, Stamp> stamps = new LinkedHashMap<>();
    private final boolean localReader;
    private record Stamp(long bytes, long mtimeNs, Object fileKey) { }
    private record VerifiedHash(Path path, Stamp stamp, String sha256) { }
    private static final Set<VerifiedHash> VERIFIED_RELOCATIONS = new HashSet<>();

    RevelPublisherIndexManifest(ObjectMapper mapper, Path directory, Path executable, Path reviewPath,
                                String reviewFileSha, String reviewCanonicalSha) throws IOException {
        this(mapper, directory, executable, reviewPath, reviewFileSha, reviewCanonicalSha, false);
    }

    RevelPublisherIndexManifest(ObjectMapper mapper, Path directory, Path executable, Path reviewPath,
                                String reviewFileSha, String reviewCanonicalSha, boolean localReader) throws IOException {
        this.localReader = localReader;
        root = directory.toRealPath(); tabix = localReader ? root : executable.toRealPath();
        require(Files.isDirectory(root) && !root.toString().startsWith("\\\\") && !tabix.toString().startsWith("\\\\"), "REVEL_LOCAL_INDEX_REQUIRED");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        ObjectNode review = json(mapper, reviewPath, reviewFileSha);
        require(adapter.canonicalSha256(review).equals(reviewCanonicalSha), "REVEL_REVIEW_CANONICAL_IDENTITY_MISMATCH");
        fields(review, "schemaVersion adapterId adapterVersion review resourceId resourceVersion manifest artifacts tool producer clinicalAcceptance defaultEnabled");
        require("reviewed-revel-publisher-index-v1".equals(review.path("schemaVersion").asText())
                && RevelPublisherIndexObservationProvider.ID.equals(review.path("adapterId").asText())
                && RevelPublisherIndexObservationProvider.VERSION.equals(review.path("adapterVersion").asText())
                && no(review.path("clinicalAcceptance")) && no(review.path("defaultEnabled")), "REVEL_REVIEW_SCOPE_INVALID");
        fields(review.path("review"), "status reviewId");
        require("REVIEWED_INDEX_INTEGRITY".equals(review.at("/review/status").asText()) && text(review.at("/review/reviewId")), "REVEL_INDEX_INTEGRITY_REVIEW_REQUIRED");
        require(review.path("resourceId").asText().matches("[A-Z][A-Z0-9._-]{1,63}")
                && review.path("resourceVersion").asText().matches("[A-Za-z0-9][A-Za-z0-9_.+_-]{0,127}"), "REVEL_RESOURCE_IDENTITY_INVALID");
        JsonNode manifestReceipt = review.path("manifest");
        fields(manifestReceipt, "file bytes mtimeNs sha256");
        require("manifest.json".equals(manifestReceipt.path("file").asText()), "REVEL_COMPLETE_MANIFEST_REQUIRED");
        Path manifestPath = owned("manifest.json");
        pin(manifestPath, manifestReceipt, MAX_JSON);
        ObjectNode manifest = json(mapper, manifestPath, manifestReceipt.path("sha256").asText());
        validateManifest(manifest);
        fields(review.path("producer"), "version sha256");
        require(review.at("/producer/version").equals(manifest.path("toolVersion"))
                && review.at("/producer/sha256").equals(manifest.path("producerSha256")), "REVEL_PRODUCER_IDENTITY_MISMATCH");
        require(review.path("artifacts").isArray() && review.path("artifacts").size() == 3, "REVEL_ARTIFACT_RECEIPTS_REQUIRED");
        Set<String> names = new HashSet<>();
        for (JsonNode receipt : review.path("artifacts")) {
            fields(receipt, "file bytes mtimeNs sha256");
            String name = receipt.path("file").asText();
            require(Set.of(DATA, INDEX, EXCLUDED).contains(name) && names.add(name), "REVEL_ARTIFACT_SET_INVALID");
            JsonNode declared = null;
            for (JsonNode entry : manifest.path("artifacts")) if (name.equals(entry.path("file").asText())) declared = entry;
            if (declared == null) throw new IOException("REVEL_ARTIFACT_MANIFEST_MISMATCH");
            require(declared.path("bytes").equals(receipt.path("bytes")) && declared.path("sha256").equals(receipt.path("sha256")), "REVEL_ARTIFACT_MANIFEST_MISMATCH");
            Path path = owned(name);
            pin(path, receipt, INDEX.equals(name) ? 64 * 1024 * 1024 : MAX_DISK);
            if (INDEX.equals(name)) require(sha(path, 64 * 1024 * 1024).equals(receipt.path("sha256").asText()), "REVEL_INDEX_SHA_MISMATCH");
        }
        fields(review.path("tool"), "bytes mtimeNs sha256 version");
        require(integer(review.at("/tool/bytes"), 1, 20_000_000) && integer(review.at("/tool/mtimeNs"), 1, Long.MAX_VALUE)
                && hash(review.at("/tool/sha256")) && text(review.at("/tool/version")), "REVEL_FILE_RECEIPT_INVALID");
        JsonNode tool = manifest.at("/tools/tabix");
        require(review.at("/tool/sha256").equals(tool.path("sha256")) && review.at("/tool/version").equals(tool.path("version")), "REVEL_TOOL_MANIFEST_MISMATCH");
        if (!localReader) {
            pin(tabix, review.path("tool"), 20_000_000);
            require(sha(tabix, 20_000_000).equals(tool.path("sha256").asText()), "REVEL_TOOL_SHA_MISMATCH");
        }
        sourceRows = manifest.path("sourceDataRows").asLong();
        contigs = new HashSet<>(); for (JsonNode contig : manifest.at("/validation/contigs")) contigs.add(contig.asText());
        binding = mapper.createObjectNode().put("resourceId", review.path("resourceId").asText()).put("resourceVersion", review.path("resourceVersion").asText())
                .put("identityFingerprintSha256", reviewCanonicalSha).put("sourceArtifactSha256", manifest.at("/source/memberSha256").asText());
        provenance = mapper.createObjectNode().put("adapterId", RevelPublisherIndexObservationProvider.ID).put("adapterVersion", RevelPublisherIndexObservationProvider.VERSION)
                .put("reviewFileSha256", reviewFileSha).put("reviewCanonicalSha256", reviewCanonicalSha)
                .put("manifestSha256", manifestReceipt.path("sha256").asText()).put("zipSha256", manifest.at("/source/verifiedZipSha256").asText())
                .put("member", manifest.at("/source/member").asText()).put("memberSha256", manifest.at("/source/memberSha256").asText())
                .put("sourceLineConvention", SOURCE_LINE).put("sourceMappingMethod", "REVEL_publisher_liftOver")
                .put("sourceOriginalAssembly", "GRCh37").put("sourcePublishedAssembly", "GRCh38").put("sourceTranscriptVersionStatus", VERSION_STATUS)
                .put("largeArtifactIdentityMode", "REVIEWED_COMPLETE_BUILD_HASH_AND_CURRENT_METADATA_NOT_REHASHED_PER_QUERY")
                .put("nativeReferenceVerified", false).put("nativeCdsVerified", false).put("clinicalAcceptance", false);
        provenance.set("derivedArtifacts", manifest.path("artifacts").deepCopy());
        if (localReader) {
            provenance.put("queryMethod", RevelLocalIndexQuery.METHOD).put("queryLibraryVersion", "2.24.1")
                    .put("largeArtifactIdentityMode", "REVIEWED_BUILD_HASH_WITH_FULL_REHASH_ON_RELOCATED_MTIME_AT_LOAD")
                    .put("builderToolExecuted", false);
            var actual = provenance.putArray("currentArtifactStamps");
            for (var entry : stamps.entrySet()) actual.addObject().put("file", entry.getKey().getFileName().toString())
                    .put("bytes", entry.getValue().bytes).put("mtimeNs", entry.getValue().mtimeNs);
        }
        recheck();
    }

    void recheck() throws IOException {
        for (var entry : stamps.entrySet()) require(entry.getValue().equals(stamp(entry.getKey())), "REVEL_RESOURCE_CHANGED");
    }
    private Path owned(String name) throws IOException {
        Path path = root.resolve(name).toRealPath();
        require(path.getParent().equals(root), "REVEL_ARTIFACT_PATH_OUTSIDE_ROOT"); return path;
    }
    private void pin(Path path, JsonNode receipt, long maximum) throws IOException {
        require(integer(receipt.path("bytes"), 1, maximum) && integer(receipt.path("mtimeNs"), 1, Long.MAX_VALUE) && hash(receipt.path("sha256")), "REVEL_FILE_RECEIPT_INVALID");
        Stamp actual = stamp(path);
        require(actual.bytes == receipt.path("bytes").asLong(), "REVEL_FILE_RECEIPT_NOT_CURRENT");
        if (actual.mtimeNs != receipt.path("mtimeNs").asLong()) {
            require(localReader, "REVEL_FILE_RECEIPT_NOT_CURRENT");
            // Same historical bytes after migration, not an old checksum pasted onto a new timestamp.
            // Runs only at explicit local-reader initialization, never inside a point query.
            VerifiedHash key = new VerifiedHash(path, actual, receipt.path("sha256").asText());
            synchronized (VERIFIED_RELOCATIONS) {
                if (!VERIFIED_RELOCATIONS.contains(key)) {
                    require(sha(path, maximum).equals(key.sha256), "REVEL_RELOCATED_ARTIFACT_SHA_MISMATCH");
                    require(actual.equals(stamp(path)), "REVEL_RESOURCE_CHANGED");
                    if (VERIFIED_RELOCATIONS.size() >= 64) VERIFIED_RELOCATIONS.clear();
                    VERIFIED_RELOCATIONS.add(key);
                }
            }
            require(actual.equals(stamp(path)), "REVEL_RESOURCE_CHANGED");
        }
        stamps.put(path, actual);
    }
    private static Stamp stamp(Path path) throws IOException {
        BasicFileAttributes value = Files.readAttributes(path, BasicFileAttributes.class);
        require(value.isRegularFile(), "REVEL_ARTIFACT_NOT_FILE");
        return new Stamp(value.size(), value.lastModifiedTime().to(TimeUnit.NANOSECONDS), value.fileKey());
    }
    private static ObjectNode json(ObjectMapper mapper, Path path, String expectedSha) throws IOException {
        require(Files.size(path) <= MAX_JSON, "REVEL_JSON_SIZE_INVALID");
        byte[] bytes; try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_JSON + 1); }
        require(bytes.length <= MAX_JSON && digest(bytes).equals(expectedSha), "REVEL_JSON_FILE_IDENTITY_MISMATCH");
        ObjectMapper strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode parsed = strict.readTree(bytes);
        if (!(parsed instanceof ObjectNode object)) throw new IOException("REVEL_JSON_OBJECT_REQUIRED");
        return object;
    }

    private static void validateManifest(ObjectNode m) throws IOException {
        fields(m, "schemaVersion toolVersion status startedAt publisherLookupReady nativeReferenceVerified nativeCdsVerified classificationEnabled defaultEnabled providerCalls networkCalls sourceLineConvention sourceMappingMethod sourceOriginalAssembly sourcePublishedAssembly sourceTranscriptVersionStatus maximumDiskBytes producerSha256 source tools sourceDataRows indexedRows excludedRows excludedReasons rawMissingCellCounts rawAnnotationFlagCounts rawAnnotationFlagsDoNotFilterRowsOrEstablishScientificValidity validation artifacts sort finishedAt peakReservedLogicalDiskBytes logicalDiskBytesBeforeCleanup runtimeActivation");
        require("revel-publisher-lookup-build-v1".equals(m.path("schemaVersion").asText()) && "1.0.1".equals(m.path("toolVersion").asText())
                && "COMPLETE".equals(m.path("status").asText()) && yes(m.path("publisherLookupReady")) && hash(m.path("producerSha256")), "REVEL_COMPLETE_BUILD_REQUIRED");
        for (String field : Set.of("nativeReferenceVerified", "nativeCdsVerified", "classificationEnabled", "defaultEnabled")) require(no(m.path(field)), "REVEL_BUILD_SCIENTIFIC_SCOPE_INVALID");
        for (String field : Set.of("providerCalls", "networkCalls")) require(integer(m.path(field), 0, 0), "REVEL_BUILD_CALL_SCOPE_INVALID");
        require(SOURCE_LINE.equals(m.path("sourceLineConvention").asText()) && VERSION_STATUS.equals(m.path("sourceTranscriptVersionStatus").asText())
                && "REVEL_publisher_liftOver".equals(m.path("sourceMappingMethod").asText()) && "GRCh37".equals(m.path("sourceOriginalAssembly").asText())
                && "GRCh38".equals(m.path("sourcePublishedAssembly").asText()) && yes(m.path("rawAnnotationFlagsDoNotFilterRowsOrEstablishScientificValidity"))
                && "NOT_REGISTERED_NATIVE_REF_CDS_TRANSCRIPT_AND_CONFLICT_CHECKS_STILL_REQUIRED".equals(m.path("runtimeActivation").asText()), "REVEL_BUILD_SOURCE_SEMANTICS_INVALID");
        try { require(!Instant.parse(m.path("finishedAt").asText()).isBefore(Instant.parse(m.path("startedAt").asText())), "REVEL_BUILD_TIME_INVALID"); }
        catch (RuntimeException error) { throw new IOException("REVEL_BUILD_TIME_INVALID", error); }
        for (String field : Set.of("maximumDiskBytes", "peakReservedLogicalDiskBytes", "logicalDiskBytesBeforeCleanup")) require(integer(m.path(field), 1, MAX_DISK), "REVEL_BUILD_DISK_ACCOUNTING_INVALID");
        require(m.path("peakReservedLogicalDiskBytes").asLong() <= m.path("maximumDiskBytes").asLong()
                && m.path("logicalDiskBytesBeforeCleanup").asLong() <= m.path("peakReservedLogicalDiskBytes").asLong(), "REVEL_BUILD_DISK_ACCOUNTING_INVALID");
        for (String field : Set.of("sourceDataRows", "indexedRows", "excludedRows")) require(integer(m.path(field), "excludedRows".equals(field) ? 0 : 1, 500_000_000), "REVEL_SOURCE_ROW_ACCOUNTING_INVALID");
        require(m.path("sourceDataRows").asLong() == m.path("indexedRows").asLong() + m.path("excludedRows").asLong(), "REVEL_SOURCE_ROW_ACCOUNTING_INVALID");
        JsonNode s = m.path("source");
        fields(s, "zipFile expectedZipBytes expectedZipSha256 member expectedMemberBytes expectedMemberCrc32 expectedMemberSha256 verifiedZipSha256 memberSha256 memberCrc32 memberBytes memberSha256Check");
        require(text(s.path("zipFile")) && "revel_with_transcript_ids".equals(s.path("member").asText()) && integer(s.path("expectedZipBytes"), 1, 1_000_000_000)
                && integer(s.path("memberBytes"), 1, MAX_DISK) && s.path("memberBytes").equals(s.path("expectedMemberBytes"))
                && hash(s.path("expectedZipSha256")) && s.path("expectedZipSha256").equals(s.path("verifiedZipSha256"))
                && hash(s.path("memberSha256")) && s.path("memberCrc32").asText().matches("[0-9a-f]{8}")
                && s.path("memberCrc32").equals(s.path("expectedMemberCrc32")), "REVEL_FULL_MEMBER_IDENTITY_REQUIRED");
        require(s.path("expectedMemberSha256").isNull() ? "COMPUTED_FROM_FULL_SHA_PINNED_ZIP".equals(s.path("memberSha256Check").asText())
                : hash(s.path("expectedMemberSha256")) && s.path("expectedMemberSha256").equals(s.path("memberSha256")) && "MATCHED_EXPECTED".equals(s.path("memberSha256Check").asText()), "REVEL_MEMBER_SHA_CHECK_INVALID");
        fields(m.path("tools"), "bgzip tabix");
        for (JsonNode tool : m.path("tools")) { fields(tool, "path sha256 version"); require(text(tool.path("path")) && hash(tool.path("sha256")) && text(tool.path("version")), "REVEL_BUILD_TOOL_IDENTITY_INVALID"); }
        JsonNode v = m.path("validation");
        fields(v, "fullBgzfIntegrityTest fullIndexedRowsReparsed fullExcludedRowsReparsed indexedUncompressedSha256 excludedUncompressedSha256 projectedRowMultisetSumSha256 referenceQueries criterionEvaluations completeTbiParsed tabixBoundaryQueries contigs tabixStdoutNewlineModes");
        require(yes(v.path("fullBgzfIntegrityTest")) && yes(v.path("completeTbiParsed")) && v.path("fullIndexedRowsReparsed").equals(m.path("indexedRows"))
                && v.path("fullExcludedRowsReparsed").equals(m.path("excludedRows")) && integer(v.path("referenceQueries"), 0, 0) && integer(v.path("criterionEvaluations"), 0, 0), "REVEL_COMPLETE_VALIDATION_REQUIRED");
        for (String field : Set.of("indexedUncompressedSha256", "excludedUncompressedSha256", "projectedRowMultisetSumSha256")) require(hash(v.path(field)), "REVEL_COMPLETE_VALIDATION_REQUIRED");
        require(v.path("contigs").isArray() && !v.path("contigs").isEmpty() && v.path("contigs").size() <= 25, "REVEL_INDEX_CONTIGS_INVALID");
        Set<String> contigs = new HashSet<>();
        for (JsonNode contig : v.path("contigs")) require(contig.isTextual() && primary(contig.asText()) && contigs.add(contig.asText()), "REVEL_INDEX_CONTIGS_INVALID");
        require(integer(v.path("tabixBoundaryQueries"), contigs.size(), contigs.size() * 2L)
                && v.path("tabixStdoutNewlineModes").isArray() && !v.path("tabixStdoutNewlineModes").isEmpty(), "REVEL_INDEX_BOUNDARY_VALIDATION_REQUIRED");
        Set<String> modes = new HashSet<>();
        for (JsonNode mode : v.path("tabixStdoutNewlineModes")) require(Set.of("LF_STDOUT", "CRLF_STDOUT").contains(mode.asText()) && modes.add(mode.asText()), "REVEL_NEWLINE_CONTRACT_INVALID");
        require(m.path("artifacts").isArray() && m.path("artifacts").size() == 3, "REVEL_COMPLETE_ARTIFACTS_REQUIRED");
        Set<String> names = new HashSet<>();
        for (JsonNode file : m.path("artifacts")) { fields(file, "file bytes sha256"); require(Set.of(DATA, INDEX, EXCLUDED).contains(file.path("file").asText()) && names.add(file.path("file").asText()) && integer(file.path("bytes"), 1, MAX_DISK) && hash(file.path("sha256")), "REVEL_COMPLETE_ARTIFACTS_REQUIRED"); }
        for (String counts : Set.of("excludedReasons", "rawMissingCellCounts", "rawAnnotationFlagCounts")) {
            require(m.path(counts).isObject(), "REVEL_BUILD_COUNTS_INVALID");
            for (JsonNode count : m.path(counts)) require(integer(count, 0, m.path("sourceDataRows").asLong()), "REVEL_BUILD_COUNTS_INVALID");
        }
        long excluded = 0; for (JsonNode count : m.path("excludedReasons")) excluded += count.asLong();
        require(excluded == m.path("excludedRows").asLong(), "REVEL_EXCLUDED_ACCOUNTING_INVALID");
        JsonNode sort = m.path("sort"); fields(sort, "chunkBytes maximumBufferedRecords fanIn temporaryRuns cleanupBoundary");
        require(integer(sort.path("chunkBytes"), 1024, 134217728) && integer(sort.path("maximumBufferedRecords"), 500000, 500000)
                && integer(sort.path("fanIn"), 2, 64) && text(sort.path("cleanupBoundary")) && sort.path("temporaryRuns").isArray() && !sort.path("temporaryRuns").isEmpty(), "REVEL_SORT_ACCOUNTING_INVALID");
        names.clear();
        for (JsonNode run : sort.path("temporaryRuns")) { fields(run, "file bytes rows sha256"); require(run.path("file").asText().matches("sort-run-[0-9]{6,}.tsv") && names.add(run.path("file").asText()) && integer(run.path("bytes"), 1, MAX_DISK) && integer(run.path("rows"), 1, m.path("indexedRows").asLong()) && hash(run.path("sha256")), "REVEL_SORT_ACCOUNTING_INVALID"); }
    }

    static boolean primary(String value) { return value.matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)"); }
    static boolean integer(JsonNode n, long min, long max) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() >= min && n.asLong() <= max; }
    static boolean text(JsonNode n) { return n.isTextual() && !n.asText().isBlank() && n.asText().length() <= 65536; }
    static boolean hash(JsonNode n) { return n.isTextual() && n.asText().matches("[0-9a-f]{64}"); }
    private static boolean yes(JsonNode n) { return n.isBoolean() && n.booleanValue(); }
    private static boolean no(JsonNode n) { return n.isBoolean() && !n.booleanValue(); }
    static void fields(JsonNode node, String names) throws IOException {
        Set<String> fields = Set.of(names.split(" "));
        require(node.isObject() && node.size() == fields.size(), "REVEL_UNKNOWN_OR_MISSING_FIELD");
        var keys = node.fieldNames(); while (keys.hasNext()) require(fields.contains(keys.next()), "REVEL_UNKNOWN_OR_MISSING_FIELD");
    }
    static void require(boolean valid, String reason) throws IOException { if (!valid) throw new IOException(reason); }
    static String sha(Path path, long maximum) throws IOException {
        MessageDigest hash = digest(); long count = 0;
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("REVEL_IDENTITY_CHECK_INTERRUPTED");
                count += read; require(count <= maximum, "REVEL_IDENTITY_FILE_SIZE_LIMIT"); hash.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static String digest(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
