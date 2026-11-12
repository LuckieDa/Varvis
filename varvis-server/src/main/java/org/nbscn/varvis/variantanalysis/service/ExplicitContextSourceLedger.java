package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/**
 * Explicit local records and caller-trusted validation receipts, with byte integrity and traceability.
 * A checksum does not authenticate the source or establish biological correctness. This ledger neither
 * produces context nor validates receipt claims, and has no network or default patient-flow integration.
 * References retain logical record locators; the selected filesystem path is never part of an export.
 */
public final class ExplicitContextSourceLedger {

    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final String source;
    private final String version;
    private final String dataClass;
    private final String artifactSha256;
    private final JsonNode records;
    private final byte[] rawBytes;

    private ExplicitContextSourceLedger(JsonNode envelope, String artifactSha256, byte[] rawBytes) {
        this.source = envelope.path("source").asText();
        this.version = envelope.path("version").asText();
        this.dataClass = envelope.path("dataClass").asText();
        this.artifactSha256 = artifactSha256;
        this.records = envelope.path("records").deepCopy();
        this.rawBytes = rawBytes.clone();
    }

    public record SourceRef(String source, String version, String recordLocator, String artifactSha256) { }

    /** Loads only the caller-selected file, without changing the supplied ObjectMapper configuration. */
    public static ExplicitContextSourceLedger load(ObjectMapper mapper, Path path,
                                                   String expectedSha256) throws IOException {
        require(expectedSha256 != null && expectedSha256.matches("[0-9a-f]{64}"),
                "SOURCE_ARTIFACT_SHA256_INVALID");
        require(Files.size(path) <= MAX_BYTES, "SOURCE_ARTIFACT_TOO_LARGE");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        require(bytes.length <= MAX_BYTES, "SOURCE_ARTIFACT_TOO_LARGE");
        return verifiedBytes(mapper, bytes, expectedSha256);
    }

    /** Same bounded byte validation for receipts produced in-process; no temporary file round trip. */
    static ExplicitContextSourceLedger create(ObjectMapper mapper, JsonNode envelope) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(envelope);
        return verifiedBytes(mapper, bytes, sha256(bytes));
    }

    public static ExplicitContextSourceLedger verifiedBytes(ObjectMapper mapper, byte[] bytes, String expectedSha256) throws IOException {
        require(bytes.length <= MAX_BYTES, "SOURCE_ARTIFACT_TOO_LARGE");
        String actualSha256 = sha256(bytes);
        require(actualSha256.equals(expectedSha256), "SOURCE_ARTIFACT_HASH_MISMATCH");
        JsonNode envelope;
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            envelope = mapper.readTree(parser);
            require(parser.nextToken() == null, "SOURCE_JSON_INVALID");
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SOURCE_JSON_INVALID");
        }
        if (!(envelope != null && envelope.isObject()
                        && portableIdentity(envelope.path("source")) && portableIdentity(envelope.path("version"))
                        && ("SYNTHETIC".equals(envelope.path("dataClass").asText())
                        || "PRIVACY_BOUNDED_RUNTIME".equals(envelope.path("dataClass").asText()))
                        && envelope.path("records").isObject())) { throw new IllegalArgumentException("SOURCE_ENVELOPE_INVALID"); }
        Iterator<Map.Entry<String, JsonNode>> fields = envelope.path("records").properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            require(validLocator(field.getKey()), "SOURCE_RECORD_LOCATOR_INVALID");
            require(field.getValue().isObject(), "SOURCE_RECORD_INVALID");
        }
        return new ExplicitContextSourceLedger(envelope, actualSha256, bytes);
    }

    public com.fasterxml.jackson.databind.node.ObjectNode receipt(ObjectMapper mapper) {
        return mapper.createObjectNode().put("source", source).put("sha256", artifactSha256)
                .put("rawJson", new String(rawBytes, StandardCharsets.UTF_8));
    }

    public JsonNode resolve(SourceRef reference) {
        if (!(reference != null && source.equals(reference.source()) && version.equals(reference.version())
                        && artifactSha256.equals(reference.artifactSha256()))) { throw new IllegalArgumentException("SOURCE_IDENTITY_CONFLICT"); }
        return record(reference.recordLocator()).deepCopy();
    }

    public String dataClass() { return dataClass; }

    public SourceRef reference(String locator) {
        record(locator);
        return new SourceRef(source, version, locator, artifactSha256);
    }

    private JsonNode record(String locator) {
        require(validLocator(locator), "SOURCE_RECORD_LOCATOR_INVALID");
        JsonNode record = records.get(locator);
        require(record != null, "SOURCE_RECORD_MISSING");
        return record;
    }

    private static boolean validLocator(String locator) {
        if (locator == null || locator.isBlank() || !locator.equals(locator.strip())
                || locator.startsWith("/") || locator.startsWith("\\")
                || locator.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            return false;
        }
        for (String segment : locator.split("[\\\\/]", -1)) {
            if ("..".equals(segment)) { return false; }
        }
        return true;
    }

    private static boolean portableIdentity(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank() || !value.asText().equals(value.asText().strip())) { return false; }
        String identity = value.asText().strip();
        return !identity.startsWith("/") && !identity.startsWith("\\")
                && !identity.matches("^[A-Za-z]:.*")
                && !identity.regionMatches(true, 0, "file:", 0, 5)
                && java.util.Arrays.stream(identity.replace('\\', '/').split("/", -1)).noneMatch(".."::equals)
                && identity.chars().noneMatch(Character::isISOControl);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", exception);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
