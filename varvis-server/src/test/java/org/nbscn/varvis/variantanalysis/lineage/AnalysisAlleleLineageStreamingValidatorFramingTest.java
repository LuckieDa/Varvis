package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageStreamingValidatorFramingTest {

    private static final AnalysisAlleleLineageCanonicalProjector PROJECTOR =
            new AnalysisAlleleLineageCanonicalProjector();

    @Test
    void rejectsAllBomFamiliesAtTheStreamingBoundary(@TempDir Path parent) throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        byte[] canonical = canonicalNdjson(document);
        int[][] prefixes = {
                {0xef, 0xbb, 0xbf},
                {0xfe, 0xff},
                {0xff, 0xfe},
                {0x00, 0x00, 0xfe, 0xff},
                {0xff, 0xfe, 0x00, 0x00}
        };

        for (int index = 0; index < prefixes.length; index++) {
            int[] prefix = prefixes[index];
            byte[] value = new byte[prefix.length + canonical.length];
            for (int offset = 0; offset < prefix.length; offset++) {
                value[offset] = (byte) prefix[offset];
            }
            System.arraycopy(canonical, 0, value, prefix.length, canonical.length);

            assertFailure(value, document.header(), parent, "bom-" + index,
                    ReasonCode.LINEAGE_FIELD_INVALID, "encoding");
        }
    }

    @Test
    void rejectsCrlfAndCrOnlyLineEndings(@TempDir Path parent) throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        String canonical = new String(canonicalNdjson(document), StandardCharsets.UTF_8);

        assertFailure(canonical.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                document.header(), parent, "crlf",
                ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
        assertFailure(canonical.replace('\n', '\r').getBytes(StandardCharsets.UTF_8),
                document.header(), parent, "cr-only",
                ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
    }

    @Test
    void rejectsMissingFinalLf(@TempDir Path parent) throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        byte[] canonical = canonicalNdjson(document);
        byte[] withoutFinalLf = Arrays.copyOf(canonical, canonical.length - 1);

        assertFailure(withoutFinalLf, document.header(), parent, "missing-final-lf",
                ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
    }

    @Test
    void rejectsAdditionalRecordAfterFooter(@TempDir Path parent) throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        byte[] canonical = canonicalNdjson(document);
        int headerEnd = indexOf(canonical, new byte[]{'\n'}) + 1;
        ByteArrayOutputStream trailing = new ByteArrayOutputStream();
        trailing.write(canonical);
        trailing.write(canonical, 0, headerEnd);

        assertFailure(trailing.toByteArray(), document.header(), parent, "trailing-record",
                ReasonCode.LINEAGE_ORDER_INVALID, "document");
    }

    @Test
    void rejectsMalformedUtf8AndRawNulInBody(@TempDir Path parent) throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        byte[] marker = "TARGETS_CAPTURED".getBytes(StandardCharsets.UTF_8);

        byte[] malformedUtf8 = canonicalNdjson(document);
        malformedUtf8[indexOf(malformedUtf8, marker)] = (byte) 0xff;
        assertFailure(malformedUtf8, document.header(), parent, "invalid-utf8",
                ReasonCode.LINEAGE_FIELD_INVALID, "json");

        byte[] rawNul = canonicalNdjson(document);
        rawNul[indexOf(rawNul, marker)] = 0;
        assertFailure(rawNul, document.header(), parent, "raw-nul",
                ReasonCode.LINEAGE_FIELD_INVALID, "json");
    }

    @Test
    void enforcesLowerHeaderDeclaredLineLimitAgainstHeader(@TempDir Path parent)
            throws Exception {
        Document source = AnalysisAlleleLineageWireFixtures.validDocument();
        Limits limits = source.header().limits();
        Document document = withLimits(source, new Limits(
                limits.maxSidecarBytes(),
                1,
                limits.maxRecordCount(),
                limits.maxInputAlleleCount(),
                limits.maxCandidateLinkCount(),
                limits.maxAlleleBytes(),
                limits.maxRelationFanIn(),
                limits.maxRelationFanOut()));

        assertFailure(canonicalNdjson(document), document.header(), parent, "line-limit",
                ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED, "maxLineBytes");
    }

    @Test
    void enforcesLowerHeaderDeclaredSidecarLimitAgainstHeader(@TempDir Path parent)
            throws Exception {
        Document source = AnalysisAlleleLineageWireFixtures.validDocument();
        Limits limits = source.header().limits();
        Document document = withLimits(source, new Limits(
                1,
                limits.maxLineBytes(),
                limits.maxRecordCount(),
                limits.maxInputAlleleCount(),
                limits.maxCandidateLinkCount(),
                limits.maxAlleleBytes(),
                limits.maxRelationFanIn(),
                limits.maxRelationFanOut()));

        assertFailure(canonicalNdjson(document), document.header(), parent, "sidecar-limit",
                ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED, "maxSidecarBytes");
    }

    @Test
    void enforcesLowerHeaderDeclaredBodyRecordLimitDuringStreaming(@TempDir Path parent)
            throws Exception {
        Document source = AnalysisAlleleLineageWireFixtures.validDocument();
        Limits limits = source.header().limits();
        Document document = withLimits(source, new Limits(
                limits.maxSidecarBytes(),
                limits.maxLineBytes(),
                2,
                limits.maxInputAlleleCount(),
                limits.maxCandidateLinkCount(),
                limits.maxAlleleBytes(),
                limits.maxRelationFanIn(),
                limits.maxRelationFanOut()));

        assertFailure(canonicalNdjson(document), document.header(), parent, "body-limit",
                ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxRecordCount");
    }

    private static Document withLimits(Document source, Limits limits) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                source.header(), limits);
        return AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                source.inputRecords(),
                source.alleleRecords(),
                source.candidateLinks(),
                source.footer()));
    }

    private static void assertFailure(byte[] value,
                                      HeaderRecord expectedHeader,
                                      Path parent,
                                      String workspaceName,
                                      ReasonCode reasonCode,
                                      String field) throws Exception {
        Path workspace = Files.createDirectory(parent.resolve(workspaceName));

        LineageValidationException failure = assertThrows(
                LineageValidationException.class,
                () -> validator().validate(
                        new ByteArrayInputStream(value), workspace, expectedHeader));

        assertEquals(reasonCode, failure.getReasonCode());
        assertEquals(field, failure.getField());
        assertWorkspaceEmpty(workspace);
    }

    private static AnalysisAlleleLineageStreamingValidator validator() {
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                50_000,
                1_048_576,
                4_096,
                4,
                134_217_728,
                8,
                4_096);
        return new AnalysisAlleleLineageStreamingValidator((workspace, supplied) ->
                new BoundedExternalSortSpool(
                        workspace,
                        supplied,
                        new SecureRandom(),
                        new PortableIdentitySource(),
                        ignored -> BoundedExternalSortSpool.PrivacyStatus.PRIVATE,
                        BoundedExternalSortSpool.FaultHook.NOOP,
                        BoundedExternalSortSpool.Observer.NOOP), limits);
    }

    private static byte[] canonicalNdjson(Document document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (WireRecord record : document.recordsInCanonicalSections()) {
            output.write(PROJECTOR.canonicalRecordBytes(record));
            output.write('\n');
        }
        return output.toByteArray();
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

    private static void assertWorkspaceEmpty(Path workspace) throws IOException {
        try (Stream<Path> children = Files.list(workspace)) {
            assertEquals(0L, children.count());
        }
    }

    private static final class PortableIdentitySource
            implements BoundedExternalSortSpool.FileIdentitySource {

        @Override
        public Object identity(Path path, BasicFileAttributes attributes) {
            Object providerKey = attributes.fileKey();
            return providerKey != null
                    ? new TestIdentity(providerKey, null, null, false)
                    : new TestIdentity(null, path.toAbsolutePath().normalize(),
                    attributes.creationTime(), attributes.isDirectory());
        }

        @Override
        public boolean authorizesDeletion() {
            return true;
        }
    }

    private record TestIdentity(Object providerKey,
                                Path path,
                                FileTime creationTime,
                                boolean directory) {

        private TestIdentity {
            if (providerKey == null) {
                Objects.requireNonNull(path, "path");
                Objects.requireNonNull(creationTime, "creationTime");
            }
        }
    }
}
