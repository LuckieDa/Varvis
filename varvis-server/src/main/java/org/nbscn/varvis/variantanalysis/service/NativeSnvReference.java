package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Bounded indexed REF observation using a caller-trusted, previously completed checksum receipt. */
final class NativeSnvReference {
    static final String QUERY_METHOD = "HTSJDK_INDEXED_FASTA_SINGLE_BASE";
    static final String SPAN_QUERY_METHOD = "HTSJDK_INDEXED_FASTA_BOUNDED_REF_SPAN";
    static final String QUERY_VERSION = "2.24.1";
    private final ObjectMapper mapper;
    private final VariantAnalysisProperties properties;
    private final NormalizationResourceValidator validator;
    private final Path resourceRoot;
    private final List<ExplicitContextSourceLedger> sources;
    private final SourceRef checksumSource;
    private final GenomeAssembly assembly;

    NativeSnvReference(ObjectMapper mapper, VariantAnalysisProperties properties, NormalizationResourceValidator validator,
                       Path resourceRoot, List<ExplicitContextSourceLedger> sources, SourceRef checksumSource) {
        this(mapper, properties, validator, resourceRoot, sources, checksumSource, GenomeAssembly.GRCh38);
    }

    NativeSnvReference(ObjectMapper mapper, VariantAnalysisProperties properties, NormalizationResourceValidator validator,
                       Path resourceRoot, List<ExplicitContextSourceLedger> sources, SourceRef checksumSource, GenomeAssembly assembly) {
        this.mapper = mapper;
        this.properties = properties;
        this.validator = validator;
        this.resourceRoot = resourceRoot == null ? null : resourceRoot.toAbsolutePath().normalize();
        this.sources = sources;
        this.checksumSource = checksumSource;
        this.assembly = Objects.requireNonNull(assembly);
    }

    Observation observe(String contig, int position, String expected) throws IOException {
        if (expected == null || !expected.matches("[ACGT]{1,51}")) throw new Blocked("REFERENCE_ALLELE_UNSUPPORTED");
        return observe(contig, position, expected, expected.length());
    }

    /** Bounded reference span for checking equivalence of two short-indel representations. */
    Observation observeSpan(String contig, int position, int length) throws IOException {
        require(length > 0 && length <= 201, "CONVERSION_NORMALIZATION_SPAN_UNSUPPORTED");
        return observe(contig, position, null, length);
    }

    /** Technical bound, distinct from the frozen allele-length scope and old conversion proof. */
    Observation observeNormalizationSpan(String contig, int position, int length) throws IOException {
        require(length > 0 && length <= 100_000, "NORMALIZATION_EQUIVALENCE_SPAN_LIMIT");
        return observe(contig, position, null, length);
    }

    Observation observeNormalizationInput(String contig, int position, String expected) throws IOException {
        if (!(expected != null && expected.matches("[ACGT]{1,4096}"))) {
            throw new Blocked("NORMALIZATION_INPUT_SPAN_LIMIT");
        }
        return observe(contig, position, expected, expected.length());
    }

    private Observation observe(String contig, int position, String expected, int length) throws IOException {
        JsonNode receipt = checksum();
        var settings = properties.getNormalization();
        Path fasta = settings.referenceFor(assembly);
        if (!(fasta != null && Files.isRegularFile(fasta))) { throw new Blocked("REFERENCE_RESOURCE_UNAVAILABLE"); }
        fasta = fasta.toAbsolutePath().normalize();
        String locator = receipt.path("logicalName").asText();
        require(resourceRoot != null && safeRelative(locator)
                        && resourceRoot.resolve(locator).normalize().startsWith(resourceRoot)
                        && resourceRoot.resolve(locator).normalize().equals(fasta), "REFERENCE_RECEIPT_RESOURCE_CONFLICT");
        boolean manifestReuse = receipt.has("acceptedReferenceManifest");
        require(manifestReuse || assembly.name().equals(receipt.path("assembly").asText())
                        && text(receipt, "resourceId") && text(receipt, "resourceVersion")
                        && "VERIFIED".equals(receipt.path("hashStatus").asText())
                        && "SHA-256".equals(receipt.path("hashAlgorithm").asText())
                        && "RAW_BYTES".equals(receipt.path("hashScope").asText())
                        && receipt.path("unchangedDuringHash").isBoolean() && receipt.path("unchangedDuringHash").booleanValue(),
                "REFERENCE_CHECKSUM_RECEIPT_INVALID");
        String observedSha = receipt.path("observedSha256").asText();
        require(observedSha.matches("[0-9a-f]{64}") && observedSha.equals(receipt.path("expectedSha256").asText())
                        && observedSha.equals(settings.referenceSha256For(assembly)), "REFERENCE_CHECKSUM_IDENTITY_CONFLICT");
        if (!manifestReuse) {
            Instant start = utc(receipt.path("startedAt").asText()), end = utc(receipt.path("completedAt").asText());
            require(!end.isBefore(start) && !end.isAfter(Instant.now()), "REFERENCE_CHECKSUM_TIME_INVALID");
        }
        Stamp before = Stamp.capture(fasta);
        require(exactPositiveLong(receipt.path("byteLength"), before.size)
                        && exactPositiveLong(receipt.path("mtimeNs"), before.mtimeNs), "REFERENCE_CHECKSUM_NOT_APPLICABLE");
        Path index = Path.of(fasta + ".fai");
        require(Files.isRegularFile(index), "REFERENCE_INDEX_UNAVAILABLE");
        byte[] indexBytes = boundedBytes(index);
        String indexSha = sha(indexBytes);
        require(indexSha.equals(receipt.path("indexSha256").asText()), "REFERENCE_INDEX_IDENTITY_CONFLICT");
        require(settings.profileIdFor(assembly) != null && !settings.profileIdFor(assembly).isBlank()
                        && "READY".equals(validator.validate(assembly).getStatus()), "REFERENCE_PROFILE_NOT_READY");

        String queryContig = contig;
        ObjectNode aliasProof = mapper.createObjectNode().put("method", "EXACT_CONTIG");
        Path alias = settings.contigAliasFileFor(assembly);
        byte[] aliasBytes = null;
        String observed;
        try (var reference = new IndexedFastaSequenceFile(fasta)) {
            if (!reference.getIndex().hasIndexEntry(queryContig)) {
                require(alias != null && Files.isRegularFile(alias), "REFERENCE_CONTIG_UNAVAILABLE");
                aliasBytes = boundedBytes(alias);
                var aliases = new HashMap<String, String>();
                for (String line : new String(aliasBytes, StandardCharsets.UTF_8).split("\\R")) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] fields = line.strip().split("\\s+");
                    require(fields.length == 2 && aliases.putIfAbsent(fields[0], fields[1]) == null, "REFERENCE_ALIAS_INVALID");
                }
                queryContig = aliases.get(contig);
                require(queryContig != null && reference.getIndex().hasIndexEntry(queryContig), "REFERENCE_CONTIG_UNAVAILABLE");
                aliasProof.put("method", "CONFIGURED_EXPLICIT_CONTIG_ALIAS").put("artifactSha256", sha(aliasBytes))
                        .put("rawUtf8", new String(aliasBytes, StandardCharsets.UTF_8));
            }
            long endPosition = (long) position + length - 1;
            require(position > 0 && endPosition <= reference.getIndex().getIndexEntry(queryContig).getSize(), "REFERENCE_POSITION_UNAVAILABLE");
            observed = new String(reference.getSubsequenceAt(queryContig, position, endPosition).getBases(), StandardCharsets.US_ASCII);
        }
        require(before.equals(Stamp.capture(fasta)) && indexSha.equals(sha(boundedBytes(index)))
                        && (aliasBytes == null || sha(aliasBytes).equals(sha(boundedBytes(alias)))), "REFERENCE_CHANGED_DURING_QUERY");
        String rawObserved = observed;
        observed = observed.toUpperCase(java.util.Locale.ROOT);
        ObjectNode observation = mapper.createObjectNode().put("assembly", assembly.name()).put("contig", contig)
                .put("referenceContig", queryContig).put("position", position).put("expectedReference", expected)
                .put("observedReference", observed).put("status", expected == null ? "OBSERVED" : expected.equals(observed) ? "MATCH" : "MISMATCH")
                .put("method", length == 1 ? QUERY_METHOD : SPAN_QUERY_METHOD)
                .put("version", QUERY_VERSION).put("observedAt", Instant.now().toString());
        if (!rawObserved.equals(observed)) observation.put("rawObservedReference", rawObserved).put("softMaskCaseNormalized", true);
        observation.set("contigAssociation", aliasProof);
        ObjectNode identity = mapper.createObjectNode().put("kind", "REFERENCE_IDENTITY").put("assembly", assembly.name())
                .put("expectedSha256", observedSha).put("observedSha256", observedSha).put("verificationStatus", "VERIFIED")
                .put("verificationMethod", "SHA-256").put("verificationVersion", manifestReuse
                        ? "ACCEPTED_MANIFEST_INDEX_AND_LOCAL_REF_V1" : "ACCEPTED_RECEIPT_APPLICABILITY_V1")
                .put("verificationRecordLocator", checksumSource.recordLocator()).put("referenceArtifactLocator", locator)
                .put("profileId", settings.profileIdFor(assembly)).put("indexSha256", indexSha)
                .put("fullReferenceHashComputed", false).put("checksumApplicabilityCheckedAt", Instant.now().toString());
        identity.set("acceptedChecksumSource", mapper.valueToTree(checksumSource));
        identity.set(manifestReuse ? "acceptedReferenceManifest" : "actualChecksumReceipt",
                manifestReuse ? receipt.path("acceptedReferenceManifest").deepCopy() : receipt.deepCopy());
        return new Observation(identity, observation);
    }

    private JsonNode checksum() {
        List<JsonNode> found = sources.stream().map(source -> {
            try { return source.resolve(checksumSource); } catch (IllegalArgumentException ex) { return null; }
        }).filter(Objects::nonNull).toList();
        require(found.size() == 1, "REFERENCE_CHECKSUM_RECEIPT_REQUIRED");
        JsonNode record = found.get(0);
        if (record.path("actualChecksumReceipt").isObject()) return record.path("actualChecksumReceipt");
        require(assembly == GenomeAssembly.GRCh37 && record.path("acceptedReferenceManifest").isObject(),
                "REFERENCE_CHECKSUM_RECEIPT_REQUIRED");
        return acceptedManifest(record.path("acceptedReferenceManifest"));
    }

    /** Reuses a caller-pinned historical checksum; never claims to have rehashed the FASTA. */
    private JsonNode acceptedManifest(JsonNode accepted) {
        try {
            String raw = accepted.path("rawUtf8").asText();
            require(!raw.isBlank() && raw.length() <= 1024 * 1024
                            && sha(raw.getBytes(StandardCharsets.UTF_8)).equals(accepted.path("manifestSha256").asText()),
                    "REFERENCE_MANIFEST_IDENTITY_CONFLICT");
            JsonNode manifest = mapper.readTree(raw);
            if (manifest == null) throw new Blocked("REFERENCE_MANIFEST_NOT_VERIFIED");
            require(manifest != null && "varvis-reference-resource-manifest-v1".equals(manifest.path("schemaVersion").asText())
                            && "GRCh37.p13".equals(manifest.path("assembly").asText())
                            && "CHECKSUM_INDEX_AND_SYNTHETIC_SMOKE_VALIDATED".equals(manifest.path("validationStatus").asText())
                            && "PASS".equals(manifest.at("/validation/expandedSha256").asText())
                            && "PASS".equals(manifest.at("/validation/faiIdentity").asText()), "REFERENCE_MANIFEST_NOT_VERIFIED");
            String logical = accepted.path("logicalName").asText();
            require(safeRelative(logical) && Path.of(logical).getFileName().toString().equals(manifest.at("/files/fasta/name").asText())
                            && (manifest.at("/files/fasta/name").asText() + ".fai").equals(manifest.at("/files/fai/name").asText()),
                    "REFERENCE_MANIFEST_RESOURCE_CONFLICT");
            require(!utc(manifest.path("generatedAtUtc").asText()).isAfter(utc(accepted.path("acceptedAt").asText()))
                            && !utc(accepted.path("acceptedAt").asText()).isAfter(Instant.now()), "REFERENCE_MANIFEST_TIME_INVALID");
            ObjectNode receipt = mapper.createObjectNode().put("logicalName", logical)
                    .put("observedSha256", manifest.at("/files/fasta/sha256").asText())
                    .put("expectedSha256", manifest.at("/files/fasta/sha256").asText())
                    .put("indexSha256", manifest.at("/files/fai/sha256").asText());
            receipt.set("byteLength", manifest.at("/files/fasta/bytes"));
            receipt.set("mtimeNs", accepted.path("acceptedFileMtimeNs"));
            receipt.set("acceptedReferenceManifest", accepted.deepCopy());
            return receipt;
        } catch (IOException invalid) { throw new Blocked("REFERENCE_MANIFEST_INVALID"); }
    }

    private static Instant utc(String value) {
        try {
            require(value.endsWith("Z"), "REFERENCE_CHECKSUM_TIME_INVALID");
            return Instant.parse(value);
        } catch (java.time.DateTimeException exception) { throw new Blocked("REFERENCE_CHECKSUM_TIME_INVALID"); }
    }
    private static boolean exactPositiveLong(JsonNode value, long expected) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0 && value.longValue() == expected;
    }
    private static boolean text(JsonNode value, String field) { return value.path(field).isTextual() && !value.path(field).asText().isBlank(); }
    private static boolean safeRelative(String value) {
        return !value.isBlank() && !value.startsWith("/") && !value.startsWith("\\") && !value.contains(":")
                && java.util.Arrays.stream(value.replace('\\', '/').split("/", -1)).noneMatch(".."::equals);
    }
    private static byte[] boundedBytes(Path path) throws IOException {
        require(Files.size(path) <= 4 * 1024 * 1024, "REFERENCE_INDEX_OR_ALIAS_TOO_LARGE");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
            require(bytes.length <= 4 * 1024 * 1024, "REFERENCE_INDEX_OR_ALIAS_TOO_LARGE");
            return bytes;
        }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static void require(boolean condition, String reason) { if (!condition) throw new Blocked(reason); }
    static final class Blocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String reason;
        Blocked(String reason) { this.reason = reason; }
    }
    private record Stamp(long size, long mtimeNs, Object fileKey) {
        static Stamp capture(Path path) throws IOException {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new Stamp(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey());
        }
    }
    record Observation(ObjectNode identity, ObjectNode observation) { }
}
