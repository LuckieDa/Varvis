package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageNdjsonCodecTest {

    private final AnalysisAlleleLineageNdjsonCodec codec =
            new AnalysisAlleleLineageNdjsonCodec();

    @Test
    void writesAndReadsCanonicalDocumentWithoutClosingCallerStreams() throws Exception {
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();
        TrackingOutputStream output = new TrackingOutputStream();

        codec.write(expected, output);

        assertFalse(output.closed);
        byte[] encoded = output.toByteArray();
        assertEquals('\n', encoded[encoded.length - 1]);
        assertFalse(new String(encoded, StandardCharsets.UTF_8).contains("\r"));

        TrackingInputStream input = new TrackingInputStream(encoded);
        Document actual = codec.read(input);

        assertFalse(input.closed);
        assertEquals(expected, actual);
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        codec.write(actual, second);
        assertArrayEquals(encoded, second.toByteArray());
    }

    @Test
    void rejectsUtf8Bom() throws Exception {
        byte[] canonical = encoded();
        byte[] withBom = new byte[canonical.length + 3];
        withBom[0] = (byte) 0xef;
        withBom[1] = (byte) 0xbb;
        withBom[2] = (byte) 0xbf;
        System.arraycopy(canonical, 0, withBom, 3, canonical.length);

        assertFailure(withBom, ReasonCode.LINEAGE_FIELD_INVALID, "encoding");
    }

    @Test
    void rejectsCrlf() throws Exception {
        byte[] value = encodedText().replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        assertFailure(value, ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
    }

    @Test
    void rejectsMissingFinalLf() throws Exception {
        byte[] canonical = encoded();
        byte[] truncated = Arrays.copyOf(canonical, canonical.length - 1);

        assertFailure(truncated, ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
    }

    @Test
    void rejectsEmptyPhysicalLine() throws Exception {
        String value = encodedText().replaceFirst("\n", "\n\n");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
    }

    @Test
    void rejectsUnknownRootFieldWithoutEchoingItsNameOrValue() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"generatorVersion\\\"", "\\\"patientSecret\\\"");

        LineageValidationException exception = assertFailure(
                value.getBytes(StandardCharsets.UTF_8), ReasonCode.LINEAGE_UNKNOWN_FIELD, "field");
        assertFalse(exception.getMessage().contains("patientSecret"));
    }

    @Test
    void rejectsUnknownNestedAlleleField() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"chromosome\\\"", "\\\"patientSecret\\\"");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_UNKNOWN_FIELD, "field");
    }

    @Test
    void rejectsDuplicateJsonKey() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"recordType\\\":\\\"HEADER\\\"",
                "\\\"schemaVersion\\\":\\\"analysis-allele-lineage-v1\\\"," +
                        "\\\"recordType\\\":\\\"HEADER\\\"");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID, "json");
    }

    @Test
    void rejectsStringCoercionForInteger() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"recordOrdinal\\\":0", "\\\"recordOrdinal\\\":\\\"0\\\"");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID, "recordOrdinal");
    }

    @Test
    void rejectsFloatingPointForInteger() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"recordOrdinal\\\":0", "\\\"recordOrdinal\\\":0.0");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID, "recordOrdinal");
    }

    @Test
    void rejectsUnknownEnumValue() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"recordType\\\":\\\"HEADER\\\"",
                "\\\"recordType\\\":\\\"header\\\"");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID, "recordType");
    }

    @Test
    void rejectsUnpairedEscapedSurrogate() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"normalizationToolVersion\\\":\\\"1.23.1\\\"",
                "\\\"normalizationToolVersion\\\":\\\"\\\\uD800\\\"");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID, "normalizationToolVersion");
    }

    @Test
    void rejectsInvalidUtf8WithoutLeakingParserDetails() throws Exception {
        byte[] value = encoded();
        int marker = indexOf(value, "varvis-lineage-writer-v1".getBytes(StandardCharsets.UTF_8));
        value[marker] = (byte) 0xff;

        LineageValidationException exception = assertFailure(
                value, ReasonCode.LINEAGE_FIELD_INVALID, "json");
        assertEquals("LINEAGE_FIELD_INVALID:json", exception.getMessage());
    }

    @Test
    void rejectsJsonExtensions() throws Exception {
        String canonical = firstLine(encodedText());
        AnalysisAlleleLineageRecordParser parser = new AnalysisAlleleLineageRecordParser();

        assertParserFailure(parser, canonical.substring(0, canonical.length() - 1) + ",}");
        assertParserFailure(parser, "/*comment*/" + canonical);
        assertParserFailure(parser, canonical.replaceFirst("\\\"schemaVersion\\\"", "schemaVersion"));
        assertParserFailure(parser, canonical.replaceFirst("\\\"schemaVersion\\\"", "'schemaVersion'"));
    }

    @Test
    void rejectsWhitespaceEvenWhenJsonMeaningIsUnchanged() throws Exception {
        String value = " " + encodedText();

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID, "canonicalEncoding");
    }

    @Test
    void rejectsCanonicalFieldReordering() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"generatorVersion\\\":\\\"varvis-lineage-writer-v1\\\"," +
                        "\\\"pipelineProfileId\\\":\\\"convert-before-normalize-v1\\\"",
                "\\\"pipelineProfileId\\\":\\\"convert-before-normalize-v1\\\"," +
                        "\\\"generatorVersion\\\":\\\"varvis-lineage-writer-v1\\\"");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_ORDER_INVALID, "generatorVersion");
    }

    @Test
    void rejectsBodyRecordAfterFooter() throws Exception {
        String value = encodedText() + firstLine(encodedText()) + "\n";

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_ORDER_INVALID, "document");
    }

    @Test
    void reportsMissingFooterForLfTerminatedCrashArtifact() throws Exception {
        String value = encodedText();
        int footerStart = value.lastIndexOf("{\"schemaVersion\"");
        String withoutFooter = value.substring(0, footerStart);

        assertFailure(withoutFooter.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FOOTER_MISSING, null);
    }

    @Test
    void enforcesHeaderDeclaredLineLimitAgainstHeaderItself() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"maxLineBytes\\\":65536", "\\\"maxLineBytes\\\":1");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED, "maxLineBytes");
    }

    @Test
    void enforcesHeaderDeclaredTotalByteLimitAgainstHeaderItself() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"maxSidecarBytes\\\":536870912", "\\\"maxSidecarBytes\\\":1");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED, "maxSidecarBytes");
    }

    @Test
    void enforcesHeaderDeclaredBodyRecordLimitDuringRead() throws Exception {
        String value = encodedText().replaceFirst(
                "\\\"maxRecordCount\\\":100", "\\\"maxRecordCount\\\":2");

        assertFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxRecordCount");
    }

    private byte[] encoded() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(AnalysisAlleleLineageWireFixtures.validDocument(), output);
        return output.toByteArray();
    }

    private String encodedText() throws Exception {
        return new String(encoded(), StandardCharsets.UTF_8);
    }

    private LineageValidationException assertFailure(byte[] value,
                                                     ReasonCode code,
                                                     String field) {
        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(value)));
        assertEquals(code, exception.getReasonCode());
        assertEquals(field, exception.getField());
        return exception;
    }

    private static void assertParserFailure(AnalysisAlleleLineageRecordParser parser,
                                            String value) {
        assertThrows(LineageValidationException.class,
                () -> parser.parse(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String firstLine(String value) {
        return value.substring(0, value.indexOf('\n'));
    }

    private static int indexOf(byte[] source, byte[] target) {
        for (int index = 0; index <= source.length - target.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }
        throw new AssertionError("marker not found");
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] buf) {
            super(buf);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
