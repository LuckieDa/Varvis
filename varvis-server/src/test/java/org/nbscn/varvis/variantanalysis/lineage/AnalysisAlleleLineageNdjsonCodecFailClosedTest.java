package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageNdjsonCodecFailClosedTest {

    private final AnalysisAlleleLineageNdjsonCodec codec =
            new AnalysisAlleleLineageNdjsonCodec();
    private final AnalysisAlleleLineageCanonicalProjector projector =
            new AnalysisAlleleLineageCanonicalProjector();

    @Test
    void rejectsEquivalentEscapesNegativeZeroOmittedNullAndTwoRootObjects() throws Exception {
        String header = lines(validBytes())[0];
        String escaped = "v" + "\\" + "u0061rvis";
        assertParserFailure(header.replaceFirst(
                        "varvis", java.util.regex.Matcher.quoteReplacement(escaped)),
                ReasonCode.LINEAGE_FIELD_INVALID);
        assertParserFailure(header.replaceFirst("\\\"recordOrdinal\\\":0",
                        "\\\"recordOrdinal\\\":-0"),
                ReasonCode.LINEAGE_FIELD_INVALID);
        assertParserFailure(header.replaceFirst(",\\\"liftoverTool\\\":null", ""),
                ReasonCode.LINEAGE_ORDER_INVALID);
        assertParserFailure(header + header, ReasonCode.LINEAGE_FIELD_INVALID);
    }

    @Test
    void rejectsNestedDuplicateKeys() throws Exception {
        String value = validText().replaceFirst(
                "\\\"assembly\\\":\\\"GRCh38\\\"",
                "\\\"assembly\\\":\\\"GRCh38\\\",\\\"assembly\\\":\\\"GRCh38\\\"");
        assertReadFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID);

        value = validText().replaceFirst(
                "\\\"engineAlleleId\\\":\\\"engine-allele-v1-[0-9a-f]{24}\\\"",
                "$0,$0");
        assertReadFailure(value.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID);
    }

    @Test
    void rejectsAllBomFamilies() throws Exception {
        byte[] canonical = validBytes();
        int[][] prefixes = {
                {0xef, 0xbb, 0xbf}, {0xfe, 0xff}, {0xff, 0xfe},
                {0x00, 0x00, 0xfe, 0xff}, {0xff, 0xfe, 0x00, 0x00}
        };
        for (int[] prefix : prefixes) {
            byte[] value = new byte[prefix.length + canonical.length];
            for (int index = 0; index < prefix.length; index++) {
                value[index] = (byte) prefix[index];
            }
            System.arraycopy(canonical, 0, value, prefix.length, canonical.length);
            assertReadFailure(value, ReasonCode.LINEAGE_FIELD_INVALID);
        }
    }

    @Test
    void rejectsLoneLowSurrogateAndMalformedUtf8Families() throws Exception {
        String lowSurrogate = validText().replaceFirst(
                "\\\"normalizationToolVersion\\\":\\\"1.23.1\\\"",
                "\\\"normalizationToolVersion\\\":\\\"\\\\uDC00\\\"");
        assertReadFailure(lowSurrogate.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_FIELD_INVALID);

        byte[] truncated = validBytes();
        int marker = indexOf(truncated, "varvis-lineage-writer-v1".getBytes(StandardCharsets.UTF_8));
        truncated[marker] = (byte) 0xc3;
        assertReadFailure(truncated, ReasonCode.LINEAGE_FIELD_INVALID);

        byte[] overlong = validBytes();
        marker = indexOf(overlong, "varvis-lineage-writer-v1".getBytes(StandardCharsets.UTF_8));
        overlong[marker] = (byte) 0xc0;
        overlong[marker + 1] = (byte) 0xaf;
        assertReadFailure(overlong, ReasonCode.LINEAGE_FIELD_INVALID);

        byte[] nul = validBytes();
        marker = indexOf(nul, "varvis-lineage-writer-v1".getBytes(StandardCharsets.UTF_8));
        nul[marker] = 0;
        assertReadFailure(nul, ReasonCode.LINEAGE_FIELD_INVALID);
    }

    @Test
    void rejectsNumericOverflowExponentPlusAndLeadingZero() throws Exception {
        String header = lines(validBytes())[0];
        assertParserFailure(header.replaceFirst("\\\"maxLineBytes\\\":65536",
                "\\\"maxLineBytes\\\":2147483648"), ReasonCode.LINEAGE_FIELD_INVALID);
        assertParserFailure(header.replaceFirst("\\\"recordOrdinal\\\":0",
                "\\\"recordOrdinal\\\":9223372036854775808"),
                ReasonCode.LINEAGE_FIELD_INVALID);
        assertParserFailure(header.replaceFirst("\\\"recordOrdinal\\\":0",
                "\\\"recordOrdinal\\\":0e0"), ReasonCode.LINEAGE_FIELD_INVALID);
        assertParserFailure(header.replaceFirst("\\\"recordOrdinal\\\":0",
                "\\\"recordOrdinal\\\":+0"), ReasonCode.LINEAGE_FIELD_INVALID);
        assertParserFailure(header.replaceFirst("\\\"recordOrdinal\\\":0",
                "\\\"recordOrdinal\\\":00"), ReasonCode.LINEAGE_FIELD_INVALID);
    }

    @Test
    void rejectsSectionInterleaveDoubleHeaderDoubleFooterAndOrdinalGap() throws Exception {
        String[] lines = lines(validBytes());
        String interleaved = lines[0] + "\n" + lines[1] + "\n"
                + lines[3].replace("\"recordOrdinal\":3", "\"recordOrdinal\":2") + "\n"
                + lines[2].replace("\"recordOrdinal\":2", "\"recordOrdinal\":3") + "\n"
                + lines[4] + "\n";
        assertReadFailure(interleaved.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_ORDER_INVALID);

        String doubleHeader = lines[0] + "\n"
                + lines[0].replace("\"recordOrdinal\":0", "\"recordOrdinal\":1") + "\n";
        assertReadFailure(doubleHeader.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_ORDER_INVALID);

        String doubleFooter = validText()
                + lines[4].replace("\"recordOrdinal\":4", "\"recordOrdinal\":5") + "\n";
        assertReadFailure(doubleFooter.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_ORDER_INVALID);

        String gap = validText().replaceFirst(
                "\\\"recordOrdinal\\\":1", "\\\"recordOrdinal\\\":2");
        assertReadFailure(gap.getBytes(StandardCharsets.UTF_8),
                ReasonCode.LINEAGE_ORDER_INVALID);
    }

    @Test
    void rejectsWrongRecordTypeFieldAsUnknown() throws Exception {
        String header = lines(validBytes())[0].replaceFirst(
                "\\\"generatorVersion\\\":\\\"varvis-lineage-writer-v1\\\"",
                "\\\"sourceRecordOrdinal\\\":1");

        LineageValidationException exception = assertParserFailure(
                header, ReasonCode.LINEAGE_UNKNOWN_FIELD);
        assertEquals("field", exception.getField());
    }

    @Test
    void rejectsResealedFooterCountMismatch() throws Exception {
        Document source = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.copyFooter(
                source.footer(), 2, 1, 0, 1, 1, 1, 0, 0);
        Document tampered = AnalysisAlleleLineageWireFixtures.reseal(source.withFooter(footer));

        assertReadFailure(uncheckedBytes(tampered.recordsInCanonicalSections().toArray(WireRecord[]::new)),
                ReasonCode.LINEAGE_COUNT_MISMATCH);
    }

    @Test
    void enforcesLoweredInputAlleleAndCandidateLimitsBeforeFooter() throws Exception {
        HeaderRecord original = AnalysisAlleleLineageWireFixtures.header();
        Limits limits = original.limits();
        HeaderRecord alleleHeader = AnalysisAlleleLineageWireFixtures.withLimits(original,
                new Limits(limits.maxSidecarBytes(), limits.maxLineBytes(), limits.maxRecordCount(),
                        1, limits.maxCandidateLinkCount(), limits.maxAlleleBytes(),
                        limits.maxRelationFanIn(), limits.maxRelationFanOut()));
        InputRecord input = AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 2);
        AlleleKey firstKey = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleKey secondKey = AnalysisAlleleLineageWireFixtures.key("1", 101, "C", "T");
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, 1, 2, AnalysisAlleleLineageWireFixtures.TOKEN, firstKey,
                AnalysisAlleleLineageWireFixtures.engineId(firstKey));
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 2, 2, "1".repeat(24), secondKey,
                AnalysisAlleleLineageWireFixtures.engineId(secondKey));
        assertReadFailure(uncheckedBytes(alleleHeader, input, first, second),
                ReasonCode.LINEAGE_ALLELE_LIMIT_EXCEEDED);

        HeaderRecord candidateHeader = AnalysisAlleleLineageWireFixtures.withLimits(original,
                new Limits(limits.maxSidecarBytes(), limits.maxLineBytes(), limits.maxRecordCount(),
                        limits.maxInputAlleleCount(), 1, limits.maxAlleleBytes(),
                        limits.maxRelationFanIn(), limits.maxRelationFanOut()));
        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, firstKey,
                AnalysisAlleleLineageWireFixtures.engineId(firstKey));
        CandidateLinkRecord firstCandidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, AnalysisAlleleLineageWireFixtures.engineId(firstKey),
                firstKey.canonicalVariantId());
        CandidateLinkRecord secondCandidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4, 2, AnalysisAlleleLineageWireFixtures.engineId(firstKey),
                firstKey.canonicalVariantId());
        assertReadFailure(uncheckedBytes(candidateHeader,
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1), allele,
                        firstCandidate, secondCandidate),
                ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED);
    }

    private byte[] validBytes() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(AnalysisAlleleLineageWireFixtures.validDocument(), output);
        return output.toByteArray();
    }

    private String validText() throws Exception {
        return new String(validBytes(), StandardCharsets.UTF_8);
    }

    private byte[] uncheckedBytes(WireRecord... records) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (WireRecord record : records) {
            output.write(projector.canonicalRecordBytes(record));
            output.write('\n');
        }
        return output.toByteArray();
    }

    private LineageValidationException assertReadFailure(byte[] value, ReasonCode reasonCode) {
        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> codec.read(new ByteArrayInputStream(value)));
        assertEquals(reasonCode, exception.getReasonCode());
        return exception;
    }

    private LineageValidationException assertParserFailure(String value, ReasonCode reasonCode) {
        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> new AnalysisAlleleLineageRecordParser().parse(
                        value.getBytes(StandardCharsets.UTF_8)));
        assertEquals(reasonCode, exception.getReasonCode());
        return exception;
    }

    private static String[] lines(byte[] value) {
        return new String(value, StandardCharsets.UTF_8).split("\n", -1);
    }

    private static int indexOf(byte[] source, byte[] target) {
        for (int index = 0; index <= source.length - target.length; index++) {
            if (Arrays.equals(Arrays.copyOfRange(source, index, index + target.length), target)) {
                return index;
            }
        }
        throw new AssertionError("marker not found");
    }
}
