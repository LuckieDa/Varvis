package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecordStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageNdjsonCodecAdversarialTest {

    private static final String PRIVACY_CANARY = "PATIENT_SECRET_17";

    private final AnalysisAlleleLineageCanonicalProjector projector =
            new AnalysisAlleleLineageCanonicalProjector();
    private final AnalysisAlleleLineageNdjsonCodec codec =
            new AnalysisAlleleLineageNdjsonCodec();

    @ParameterizedTest
    @MethodSource("nonCanonicalEquivalentDocuments")
    void rejectsSemanticallyEquivalentButNonCanonicalBytes(byte[] bytes) {
        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(bytes)));

        assertFalse(exception.getMessage().contains(PRIVACY_CANARY));
        assertNull(exception.getCause());
    }

    static Stream<byte[]> nonCanonicalEquivalentDocuments() throws Exception {
        String canonical = encodedText();
        String escapedV = "\\" + "u0076";
        String firstLine = canonical.substring(0, canonical.indexOf('\n'));
        return Stream.of(
                canonical.replaceFirst("varvis-lineage-writer-v1",
                        escapedV + "arvis-lineage-writer-v1")
                        .getBytes(StandardCharsets.UTF_8),
                canonical.replaceFirst("\\\"recordOrdinal\\\":0",
                                "\\\"recordOrdinal\\\":-0")
                        .getBytes(StandardCharsets.UTF_8),
                canonical.replaceFirst("\\\"liftoverTool\\\":null,", "")
                        .getBytes(StandardCharsets.UTF_8),
                canonical.replaceFirst("\\n", firstLine + "\\n")
                        .getBytes(StandardCharsets.UTF_8),
                canonical.replaceFirst("\\n", " " + PRIVACY_CANARY + "\\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    void rejectsDuplicateFieldsInsideAlleleKeyAndEngineReference() throws Exception {
        String canonical = encodedText();
        String duplicateAlleleKey = canonical.replaceFirst(
                "\\\"assembly\\\":\\\"GRCh38\\\",\\\"chromosome\\\"",
                "\\\"assembly\\\":\\\"GRCh38\\\","
                        + "\\\"assembly\\\":\\\"GRCh38\\\",\\\"chromosome\\\"");
        String duplicateEngineRef = canonical.replaceFirst(
                "\\\"engineAlleleId\\\":\\\"(engine-allele-v1-[0-9a-f]{24})\\\","
                        + "\\\"analysisCanonicalVariantId\\\"",
                "\\\"engineAlleleId\\\":\\\"$1\\\","
                        + "\\\"engineAlleleId\\\":\\\"$1\\\","
                        + "\\\"analysisCanonicalVariantId\\\"");

        assertInvalid(duplicateAlleleKey.getBytes(StandardCharsets.UTF_8));
        assertInvalid(duplicateEngineRef.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("invalidEncodings")
    void rejectsUnsupportedBomAndMalformedUtf8(byte[] bytes) {
        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(bytes)));

        assertFalse(exception.getMessage().contains(PRIVACY_CANARY));
        assertNull(exception.getCause());
    }

    static Stream<byte[]> invalidEncodings() throws Exception {
        byte[] canonical = encodedBytes();
        List<byte[]> values = new ArrayList<>();
        values.add(prefix(canonical, 0xfe, 0xff));
        values.add(prefix(canonical, 0xff, 0xfe));
        values.add(prefix(canonical, 0x00, 0x00, 0xfe, 0xff));
        values.add(prefix(canonical, 0xff, 0xfe, 0x00, 0x00));

        byte[] overlong = canonical.clone();
        int marker = indexOf(overlong, "varvis-lineage-writer-v1".getBytes(StandardCharsets.UTF_8));
        overlong[marker] = (byte) 0xc0;
        overlong[marker + 1] = (byte) 0xaf;
        values.add(overlong);

        byte[] truncated = canonical.clone();
        truncated[marker] = (byte) 0xe2;
        truncated[marker + 1] = (byte) 0x82;
        values.add(truncated);

        byte[] nul = canonical.clone();
        nul[marker] = 0;
        values.add(nul);
        return values.stream();
    }

    @Test
    void rejectsLoneLowSurrogate() throws Exception {
        String escapedLowSurrogate = "\\" + "uDC00";
        String value = encodedText().replace(
                "\"normalizationToolVersion\":\"1.23.1\"",
                "\"normalizationToolVersion\":\"" + escapedLowSurrogate + "\"");

        assertInvalid(value.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("invalidIntegerEncodings")
    void rejectsNonCanonicalOrOutOfRangeIntegers(String replacement) throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"recordOrdinal\\\":0",
                "\\\"recordOrdinal\\\":" + replacement);

        assertInvalid(value.getBytes(StandardCharsets.UTF_8));
    }

    static Stream<String> invalidIntegerEncodings() {
        return Stream.of("1e0", "0.0", "+0", "00", "-0", "9223372036854775808",
                "-9223372036854775809");
    }

    @Test
    void rejectsIntOverflowWithoutEchoingTheNumber() throws Exception {
        String value = encodedText().replace(
                "\"maxLineBytes\":65536", "\"maxLineBytes\":2147483648");

        LineageValidationException exception = assertInvalid(
                value.getBytes(StandardCharsets.UTF_8));
        assertFalse(exception.getMessage().contains("2147483648"));
    }

    @Test
    void rejectsInterleavedSectionsBeforeFingerprintValidation() throws Exception {
        List<String> lines = new ArrayList<>(Arrays.asList(encodedText().split("\\n")));
        String input = lines.get(1).replaceFirst(
                "\\\"recordOrdinal\\\":1", "\\\"recordOrdinal\\\":2");
        String allele = lines.get(2).replaceFirst(
                "\\\"recordOrdinal\\\":2", "\\\"recordOrdinal\\\":1");
        lines.set(1, allele);
        lines.set(2, input);
        String value = String.join("\n", lines) + "\n";

        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))));
        assertEquals(ReasonCode.LINEAGE_ORDER_INVALID, exception.getReasonCode());
    }

    @Test
    void rejectsDuplicateHeaderDuplicateFooterAndOrdinalGap() throws Exception {
        String canonical = encodedText();
        List<String> lines = Arrays.asList(canonical.split("\\n"));
        String doubleHeader = lines.get(0) + "\n" + canonical;
        String doubleFooter = canonical + lines.get(lines.size() - 1) + "\n";
        String ordinalGap = canonical.replaceFirst(
                "\\\"recordOrdinal\\\":1", "\\\"recordOrdinal\\\":2");

        assertOrderFailure(doubleHeader);
        assertOrderFailure(doubleFooter);
        assertOrderFailure(ordinalGap);
    }

    @Test
    void rejectsResealedFooterCountTamper() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord footer = valid.footer();
        FooterRecord tampered = AnalysisAlleleLineageWireFixtures.copyFooter(
                footer,
                footer.inputRecordCount() + 1,
                footer.inputAlleleCount(),
                footer.liftedAlleleCount(),
                footer.canonicalAlleleCount(),
                footer.engineAlleleCount(),
                footer.candidateLinkCount(),
                footer.unresolvedAlleleCount(),
                footer.rejectedAlleleCount());
        Document resealed = projector.seal(valid.withFooter(tampered.withContentFingerprint(null)));

        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(encodeUnchecked(resealed))));
        assertEquals(ReasonCode.LINEAGE_COUNT_MISMATCH, exception.getReasonCode());
    }

    @Test
    void rejectsStaleAndNonLowercaseFingerprint() throws Exception {
        String stale = encodedText().replace(
                "\"engineVersion\":\"14.0.0\"", "\"engineVersion\":\"15.0.0\"");
        LineageValidationException staleException = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(stale.getBytes(StandardCharsets.UTF_8))));
        assertEquals(ReasonCode.LINEAGE_FINGERPRINT_MISMATCH, staleException.getReasonCode());

        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Document uppercase = valid.withFooter(valid.footer().withContentFingerprint(
                valid.footer().contentFingerprint().toUpperCase()));
        LineageValidationException uppercaseException = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(encodeUnchecked(uppercase))));
        assertEquals(ReasonCode.LINEAGE_FIELD_INVALID, uppercaseException.getReasonCode());
    }

    @Test
    void enforcesLowerInputAlleleAndCandidateLimitsDuringRead() {
        Limits lowerInputLimit = limits(1, 10);
        Limits lowerCandidateLimit = limits(10, 1);

        LineageValidationException alleleException = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(
                        encodeUnchecked(twoAlleleDocument(lowerInputLimit)))));
        assertEquals(ReasonCode.LINEAGE_ALLELE_LIMIT_EXCEEDED,
                alleleException.getReasonCode());

        LineageValidationException candidateException = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(
                        encodeUnchecked(twoAlleleDocument(lowerCandidateLimit)))));
        assertEquals(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED,
                candidateException.getReasonCode());
        assertEquals("maxCandidateLinkCount", candidateException.getField());
    }

    @Test
    void invalidWriterInputLeavesOutputUntouched() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord footer = valid.footer();
        FooterRecord invalid = AnalysisAlleleLineageWireFixtures.copyFooter(
                footer, footer.inputRecordCount() + 1, footer.inputAlleleCount(),
                footer.liftedAlleleCount(), footer.canonicalAlleleCount(),
                footer.engineAlleleCount(), footer.candidateLinkCount(),
                footer.unresolvedAlleleCount(), footer.rejectedAlleleCount());
        Document resealed = projector.seal(valid.withFooter(invalid.withContentFingerprint(null)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThrows(LineageValidationException.class, () -> codec.write(resealed, output));
        assertEquals(0, output.size());
    }

    @Test
    void supportsOneByteShortReadsAndZeroLengthBulkReads() throws Exception {
        byte[] canonical = encodedBytes();

        Document actual = codec.read(new ShortReadInputStream(canonical));

        assertEquals(AnalysisAlleleLineageWireFixtures.validDocument(), actual);
    }

    @Test
    void rejectsInvalidHeaderBeforeParsingOrNeedingAnotherBodyChunk() throws Exception {
        String firstLine = encodedText().substring(0, encodedText().indexOf('\n'))
                .replace("\"generatorVersion\":\"varvis-lineage-writer-v1\"",
                        "\"generatorVersion\":\"invalid\"");
        byte[] headerOnly = (firstLine + "\n").getBytes(StandardCharsets.UTF_8);

        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new HeaderOnlyThenThrowInputStream(headerOnly)));

        assertEquals(ReasonCode.LINEAGE_FIELD_INVALID, exception.getReasonCode());
        assertEquals("generatorVersion", exception.getField());
    }

    @Test
    void reasonArrayHardLimitUsesFieldErrorRatherThanRelationFanOutError() {
        InputRecord record = new InputRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.INPUT_RECORD,
                1,
                1,
                1,
                InputRecordStatus.TARGETS_CAPTURED,
                java.util.Collections.nCopies(
                        AnalysisAlleleLineageWire.MAX_REASON_CODES + 1,
                        ReasonCode.INPUT_DUPLICATE_ALT));
        byte[] line = projector.canonicalRecordBytes(record);

        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> new AnalysisAlleleLineageRecordParser().parse(line));

        assertEquals(ReasonCode.LINEAGE_FIELD_INVALID, exception.getReasonCode());
        assertEquals("reasonCodes", exception.getField());
    }

    @Test
    void independentInMemoryByteCeilingProtectsBothReaderAndWriter() {
        Document oversized = oversizedButWireValidDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        LineageValidationException writeException = assertThrows(
                LineageValidationException.class,
                () -> codec.write(oversized, output));
        assertEquals(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED,
                writeException.getReasonCode());
        assertEquals(0, output.size());

        byte[] physical = encodeUnchecked(oversized);
        org.junit.jupiter.api.Assertions.assertTrue(
                physical.length > AnalysisAlleleLineageNdjsonCodec.MAX_IN_MEMORY_SIDECAR_BYTES);
        LineageValidationException readException = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(physical)));
        assertEquals(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED,
                readException.getReasonCode());
        assertEquals("maxSidecarBytes", readException.getField());
    }

    @Test
    void byteCeilingIsInclusiveAndOverflowSafeAtExactBoundary() {
        long ceiling = AnalysisAlleleLineageNdjsonCodec.MAX_IN_MEMORY_SIDECAR_BYTES;

        assertFalse(AnalysisAlleleLineageNdjsonCodec.exceedsByteCeiling(0L, ceiling, ceiling));
        assertFalse(AnalysisAlleleLineageNdjsonCodec.exceedsByteCeiling(
                ceiling - 1L, 1L, ceiling));
        org.junit.jupiter.api.Assertions.assertTrue(
                AnalysisAlleleLineageNdjsonCodec.exceedsByteCeiling(ceiling, 1L, ceiling));
        org.junit.jupiter.api.Assertions.assertTrue(
                AnalysisAlleleLineageNdjsonCodec.exceedsByteCeiling(
                        Long.MAX_VALUE - 1L, 2L, Long.MAX_VALUE));
        org.junit.jupiter.api.Assertions.assertTrue(
                AnalysisAlleleLineageNdjsonCodec.exceedsByteCeiling(-1L, 0L, ceiling));
    }

    @Test
    void sharedParserKeepsRecordWhitelistsIsolatedAcrossThreadsAndAfterFailure()
            throws Exception {
        AnalysisAlleleLineageRecordParser parser = new AnalysisAlleleLineageRecordParser();
        String[] physicalLines = encodedText().split("\\n");
        String invalidHeader = physicalLines[0].replace(
                "\"generatorVersion\":\"varvis-lineage-writer-v1\"",
                "\"candidateId\":\"" + PRIVACY_CANARY + "\"");
        assertThrows(LineageValidationException.class,
                () -> parser.parse(invalidHeader.getBytes(StandardCharsets.UTF_8)));
        assertEquals(RecordType.HEADER,
                parser.parse(physicalLines[0].getBytes(StandardCharsets.UTF_8)).recordType());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                tasks.add(() -> {
                    ready.countDown();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            start.await(5, TimeUnit.SECONDS));
                    for (int iteration = 0; iteration < 250; iteration++) {
                        for (int lineIndex = 0; lineIndex < physicalLines.length; lineIndex++) {
                            byte[] line = physicalLines[lineIndex].getBytes(StandardCharsets.UTF_8);
                            assertEquals(RecordType.values()[lineIndex],
                                    parser.parse(line).recordType());
                        }
                        assertThrows(LineageValidationException.class,
                                () -> parser.parse(invalidHeader.getBytes(StandardCharsets.UTF_8)));
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Limits limits(long maxInputAlleles, long maxCandidates) {
        return new Limits(
                AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES,
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                100,
                maxInputAlleles,
                maxCandidates,
                AnalysisAlleleLineageWire.MAX_ALLELE_BYTES,
                20,
                20);
    }

    private Document twoAlleleDocument(Limits limits) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                AnalysisAlleleLineageWireFixtures.header(), limits);
        InputRecord input = AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 2);
        AlleleKey firstKey = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleKey secondKey = AnalysisAlleleLineageWireFixtures.key("1", 101, "C", "T");
        String firstEngine = AnalysisAlleleLineageWireFixtures.engineId(firstKey);
        String secondEngine = AnalysisAlleleLineageWireFixtures.engineId(secondKey);
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, 1, 2, "0123456789abcdef01234567", firstKey, firstEngine);
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 2, 2, "1123456789abcdef01234567", secondKey, secondEngine);
        CandidateLinkRecord firstCandidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4, 1, firstEngine, firstKey.canonicalVariantId());
        CandidateLinkRecord secondCandidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 2, secondEngine, secondKey.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                6, 1, 2, 0, 2, 2, 2, 0, 0);
        return projector.seal(new Document(
                header,
                List.of(input),
                List.of(first, second),
                List.of(firstCandidate, secondCandidate),
                footer));
    }

    private Document oversizedButWireValidDocument() {
        Limits limits = new Limits(
                AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES,
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                3_000,
                2,
                3_000,
                AnalysisAlleleLineageWire.MAX_ALLELE_BYTES,
                20,
                AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT);
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                AnalysisAlleleLineageWireFixtures.header(), limits);
        InputRecord input = AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 2);
        AlleleKey firstKey = AnalysisAlleleLineageWireFixtures.key(
                "1", 100, "A".repeat(4_500), "C".repeat(4_500));
        AlleleKey secondKey = AnalysisAlleleLineageWireFixtures.key(
                "1", 101, "G".repeat(4_500), "T".repeat(4_500));
        String firstEngine = AnalysisAlleleLineageWireFixtures.engineId(firstKey);
        String secondEngine = AnalysisAlleleLineageWireFixtures.engineId(secondKey);
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, 1, 2, "0123456789abcdef01234567", firstKey, firstEngine);
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 2, 2, "1123456789abcdef01234567", secondKey, secondEngine);
        List<CandidateLinkRecord> candidates = new ArrayList<>(2_048);
        long recordOrdinal = 4;
        for (long sourceOrdinal = 1; sourceOrdinal <= 1_024; sourceOrdinal++) {
            candidates.add(AnalysisAlleleLineageWireFixtures.linkedCandidate(
                    recordOrdinal++, sourceOrdinal, firstEngine, firstKey.canonicalVariantId()));
        }
        for (long sourceOrdinal = 1_025; sourceOrdinal <= 2_048; sourceOrdinal++) {
            candidates.add(AnalysisAlleleLineageWireFixtures.linkedCandidate(
                    recordOrdinal++, sourceOrdinal, secondEngine, secondKey.canonicalVariantId()));
        }
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                recordOrdinal, 1, 2, 0, 2, 2, 2_048, 0, 0);
        return projector.seal(new Document(
                header,
                List.of(input),
                List.of(first, second),
                candidates,
                footer));
    }

    private byte[] encodeUnchecked(Document document) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (WireRecord record : document.recordsInCanonicalSections()) {
            output.writeBytes(projector.canonicalRecordBytes(record));
            output.write('\n');
        }
        return output.toByteArray();
    }

    private static void assertOrderFailure(String value) {
        AnalysisAlleleLineageNdjsonCodec codec = new AnalysisAlleleLineageNdjsonCodec();
        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))));
        assertEquals(ReasonCode.LINEAGE_ORDER_INVALID, exception.getReasonCode());
    }

    private LineageValidationException assertInvalid(byte[] value) {
        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(value)));
        assertEquals(ReasonCode.LINEAGE_FIELD_INVALID, exception.getReasonCode());
        assertFalse(exception.getMessage().contains(PRIVACY_CANARY));
        assertNull(exception.getCause());
        return exception;
    }

    private static byte[] encodedBytes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new AnalysisAlleleLineageNdjsonCodec().write(
                AnalysisAlleleLineageWireFixtures.validDocument(), output);
        return output.toByteArray();
    }

    private static String encodedText() throws Exception {
        return new String(encodedBytes(), StandardCharsets.UTF_8);
    }

    private static byte[] prefix(byte[] source, int... prefix) {
        byte[] result = new byte[source.length + prefix.length];
        for (int index = 0; index < prefix.length; index++) {
            result[index] = (byte) prefix[index];
        }
        System.arraycopy(source, 0, result, prefix.length, source.length);
        return result;
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int index = 0; index <= source.length - target.length; index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        throw new AssertionError("marker not found");
    }

    private static final class ShortReadInputStream extends InputStream {
        private final byte[] bytes;
        private int index;
        private int bulkReads;

        private ShortReadInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if ((bulkReads++ & 1) == 0) {
                return 0;
            }
            if (index >= bytes.length) {
                return -1;
            }
            target[offset] = bytes[index++];
            return 1;
        }

        @Override
        public int read() {
            return index >= bytes.length ? -1 : bytes[index++] & 0xff;
        }
    }

    private static final class HeaderOnlyThenThrowInputStream extends InputStream {
        private final byte[] bytes;
        private int index;

        private HeaderOnlyThenThrowInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (index >= bytes.length) {
                throw new IOException(PRIVACY_CANARY);
            }
            target[offset] = bytes[index++];
            return 1;
        }

        @Override
        public int read() throws IOException {
            if (index >= bytes.length) {
                throw new IOException(PRIVACY_CANARY);
            }
            return bytes[index++] & 0xff;
        }
    }
}
