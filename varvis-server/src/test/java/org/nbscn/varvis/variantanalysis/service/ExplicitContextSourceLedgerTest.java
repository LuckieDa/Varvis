package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Small synthetic envelopes only; checksum verification is not biological validation. */
class ExplicitContextSourceLedgerTest {

    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesExactReferencesAndReturnsDefensiveCopiesWithoutExportingPath() throws Exception {
        for (String dataClass : List.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME")) {
            ExplicitContextSourceLedger ledger = load(envelope(dataClass, "receipts/allele-1"));
            SourceRef reference = ledger.reference("receipts/allele-1");
            assertEquals(dataClass, ledger.dataClass());
            assertEquals("synthetic-receipts", reference.source());
            assertEquals("v1", reference.version());
            assertEquals("receipts/allele-1", reference.recordLocator());
            JsonNode first = ledger.resolve(reference);
            ((ObjectNode) first.path("receipt")).put("result", "changed");
            assertEquals("SYNTHETIC_VALIDATION_RECEIPT", ledger.resolve(reference).path("receipt").path("result").asText());
            assertFalse(mapper.writeValueAsString(reference).contains(temporary.toString()));
        }
    }

    @Test
    void missingRecordsAndConflictingSourceVersionOrHashFailExplicitly() throws Exception {
        ExplicitContextSourceLedger ledger = load(envelope("SYNTHETIC", "receipts/allele-1"));
        SourceRef reference = ledger.reference("receipts/allele-1");
        assertEquals("SOURCE_RECORD_MISSING", assertThrows(IllegalArgumentException.class,
                () -> ledger.reference("receipts/missing")).getMessage());
        assertEquals("SOURCE_RECORD_MISSING", assertThrows(IllegalArgumentException.class,
                () -> ledger.resolve(new SourceRef(reference.source(), reference.version(), "receipts/missing",
                        reference.artifactSha256()))).getMessage());
        for (SourceRef conflict : List.of(
                new SourceRef("other", reference.version(), reference.recordLocator(), reference.artifactSha256()),
                new SourceRef(reference.source(), "other", reference.recordLocator(), reference.artifactSha256()),
                new SourceRef(reference.source(), reference.version(), reference.recordLocator(), "0".repeat(64)))) {
            assertEquals("SOURCE_IDENTITY_CONFLICT", assertThrows(IllegalArgumentException.class,
                    () -> ledger.resolve(conflict)).getMessage());
        }
    }

    @Test
    void checksActualFileHashAndFourMiBLimit() throws Exception {
        Path path = temporary.resolve("source.json");
        Files.writeString(path, envelope("SYNTHETIC", "receipts/allele-1"));
        assertEquals("SOURCE_ARTIFACT_HASH_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> ExplicitContextSourceLedger.load(mapper, path, "0".repeat(64))).getMessage());
        assertEquals("SOURCE_ARTIFACT_SHA256_INVALID", assertThrows(IllegalArgumentException.class,
                () -> ExplicitContextSourceLedger.load(mapper, path, "not-a-sha256")).getMessage());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(4 * 1024 * 1024 + 1);
        }
        assertEquals("SOURCE_ARTIFACT_TOO_LARGE", assertThrows(IllegalArgumentException.class,
                () -> ExplicitContextSourceLedger.load(mapper, path, "0".repeat(64))).getMessage());
    }

    @Test
    void rejectsAbsoluteAndParentTraversalLocatorsDuringLoadAndResolution() throws Exception {
        ExplicitContextSourceLedger ledger = load(envelope("SYNTHETIC", "receipts/allele-1"));
        SourceRef reference = ledger.reference("receipts/allele-1");
        for (String locator : List.of("/var/synthetic/receipt", "D:\\synthetic\\receipt",
                "D:/synthetic/receipt", "\\\\host\\synthetic\\receipt", "file:///synthetic/receipt",
                "https://example.invalid/receipt", "../receipt", "receipts/../receipt", "receipts\\..\\receipt")) {
            assertEquals("SOURCE_RECORD_LOCATOR_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> load(envelope("SYNTHETIC", locator))).getMessage());
            assertEquals("SOURCE_RECORD_LOCATOR_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> ledger.reference(locator)).getMessage());
            assertEquals("SOURCE_RECORD_LOCATOR_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> ledger.resolve(new SourceRef(reference.source(), reference.version(), locator,
                            reference.artifactSha256()))).getMessage());
        }
    }

    @Test
    void rejectsDuplicateJsonKeysAndTrailingJsonWithoutChangingMapper() throws Exception {
        for (String json : List.of(
                "{\"source\":\"first\",\"source\":\"second\"}",
                "{\"records\":{\"receipt\":{\"value\":1,\"value\":2}}}",
                envelope("SYNTHETIC", "receipts/allele-1") + " {}")) {
            assertEquals("SOURCE_JSON_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> load(json)).getMessage());
        }
        assertFalse(mapper.getFactory().isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
    }

    @Test
    void rejectsUnsupportedDataClassAndNonObjectRecords() throws Exception {
        assertEquals("SOURCE_ENVELOPE_INVALID", assertThrows(IllegalArgumentException.class,
                () -> load(envelope("UNKNOWN", "receipts/allele-1"))).getMessage());
        ObjectNode changed = (ObjectNode) mapper.readTree(envelope("SYNTHETIC", "receipts/allele-1"));
        ((ObjectNode) changed.path("records")).put("receipts/allele-1", "not-an-object");
        assertEquals("SOURCE_RECORD_INVALID", assertThrows(IllegalArgumentException.class,
                () -> load(mapper.writeValueAsString(changed))).getMessage());
    }

    @Test
    void rejectsAbsolutePathsInExportedSourceAndVersionWithoutRestrictingLogicalIdentifiers() throws Exception {
        for (String field : List.of("source", "version")) {
            for (String path : List.of("/var/synthetic/source", "D:\\synthetic\\source",
                    "D:/synthetic/source", "\\\\host\\synthetic\\source", "file:///synthetic/source",
                    " logical-source ", "../synthetic-source", "v1/../source", "D:relative", "source\nvalue")) {
                ObjectNode changed = (ObjectNode) mapper.readTree(envelope("SYNTHETIC", "receipts/allele-1"));
                changed.put(field, path);
                assertEquals("SOURCE_ENVELOPE_INVALID", assertThrows(IllegalArgumentException.class,
                        () -> load(mapper.writeValueAsString(changed))).getMessage());
            }
        }
        ObjectNode accepted = (ObjectNode) mapper.readTree(envelope("SYNTHETIC", "receipts/allele-1"));
        accepted.put("source", "https://example.invalid/synthetic-source").put("version", "v1/receipt-profile");
        SourceRef reference = load(mapper.writeValueAsString(accepted)).reference("receipts/allele-1");
        assertEquals("https://example.invalid/synthetic-source", reference.source());
        assertEquals("v1/receipt-profile", reference.version());
    }

    private String envelope(String dataClass, String locator) throws Exception {
        ObjectNode result = mapper.createObjectNode().put("source", "synthetic-receipts")
                .put("version", "v1").put("dataClass", dataClass);
        result.putObject("records").putObject(locator).putObject("receipt")
                .put("result", "SYNTHETIC_VALIDATION_RECEIPT");
        return mapper.writeValueAsString(result);
    }

    private ExplicitContextSourceLedger load(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Path path = temporary.resolve("source.json");
        Files.write(path, bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return ExplicitContextSourceLedger.load(mapper, path, hash);
    }
}
