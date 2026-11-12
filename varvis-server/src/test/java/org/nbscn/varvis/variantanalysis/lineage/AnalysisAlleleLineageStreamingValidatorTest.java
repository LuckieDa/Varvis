package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.DocumentStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageStreamingValidatorTest {

    private static final String INPUT_CANARY =
            "patient-Alice-C:\\private\\lineage\\secret.vcf";
    private static final AnalysisAlleleLineageCanonicalProjector PROJECTOR =
            new AnalysisAlleleLineageCanonicalProjector();

    @Test
    void validatesCompleteCanonicalDocumentAndReturnsFixedSummary(@TempDir Path parent)
            throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        byte[] ndjson = canonicalNdjson(document);
        Path workspace = workspace(parent, "valid");

        AnalysisAlleleLineageStreamingValidator.ValidationSummary summary = validator().validate(
                new ByteArrayInputStream(ndjson), workspace, document.header());

        assertEquals(DocumentStatus.COMPLETE, summary.status());
        assertEquals(List.of(), summary.reasonCodes());
        assertEquals(1L, summary.inputRecordCount());
        assertEquals(1L, summary.inputAlleleCount());
        assertEquals(0L, summary.liftedAlleleCount());
        assertEquals(1L, summary.canonicalAlleleCount());
        assertEquals(1L, summary.engineAlleleCount());
        assertEquals(1L, summary.candidateLinkCount());
        assertEquals(0L, summary.unresolvedAlleleCount());
        assertEquals(0L, summary.rejectedAlleleCount());
        assertEquals(3L, summary.bodyRecordCount());
        assertEquals(11L, summary.semanticFactCount());
        assertEquals(ndjson.length, summary.physicalBytes());
        assertEquals(document.footer().contentFingerprint(), summary.contentFingerprint());
        assertEquals("ValidationSummary[redacted]", summary.toString());
        assertThrows(UnsupportedOperationException.class,
                () -> summary.reasonCodes().add(ReasonCode.LINEAGE_FIELD_INVALID));
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void neverClosesTheCallerOwnedInputStreamOnSuccessOrFailure(@TempDir Path parent)
            throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        CloseTrackingInputStream successful = new CloseTrackingInputStream(
                canonicalNdjson(document));
        Path successWorkspace = workspace(parent, "caller-stream-success");

        validator().validate(successful, successWorkspace, document.header());

        assertFalse(successful.closed());
        assertWorkspaceEmpty(successWorkspace);

        CloseTrackingInputStream failed = new CloseTrackingInputStream(new byte[0]);
        Path failureWorkspace = workspace(parent, "caller-stream-failure");
        assertThrows(LineageValidationException.class,
                () -> validator().validate(failed, failureWorkspace, document.header()));
        assertFalse(failed.closed());
        assertWorkspaceEmpty(failureWorkspace);
    }

    @Test
    void validatesCrossAssemblyArtifactBinding(@TempDir Path parent) throws Exception {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey source = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37,
                "1", 100, "A", "G");
        AlleleKey lifted = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,
                "1", 110, "A", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(lifted);
        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                2, 1, AnalysisAlleleLineageWireFixtures.TOKEN, source, lifted, engineId);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, engineId, lifted.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.withLiftedArtifact(
                AnalysisAlleleLineageWireFixtures.footer(4, 1, 1, 1, 1, 1, 1, 0, 0),
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1)),
                List.of(allele),
                List.of(candidate),
                footer));
        Path workspace = workspace(parent, "cross-assembly");

        AnalysisAlleleLineageStreamingValidator.ValidationSummary summary = validator().validate(
                new ByteArrayInputStream(canonicalNdjson(document)), workspace, header);

        assertEquals(1L, summary.liftedAlleleCount());
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void bindsTheEntireExpectedHeaderBeforeCreatingSpool(@TempDir Path parent) throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        Path workspace = workspace(parent, "header-mismatch");

        LineageValidationException failure = assertThrows(LineageValidationException.class,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(document)),
                        workspace,
                        AnalysisAlleleLineageWireFixtures.crossAssemblyHeader()));

        assertEquals(ReasonCode.LINEAGE_REUSE_BINDING_MISMATCH, failure.getReasonCode());
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void independentlyRecomputesFooterCountsAndFingerprint(@TempDir Path parent) throws Exception {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord wrongCount = AnalysisAlleleLineageWireFixtures.copyFooter(
                valid.footer(), 2, 1, 0, 1, 1, 1, 0, 0);
        Document countDocument = valid.withFooter(wrongCount);
        Path countWorkspace = workspace(parent, "count");

        assertReason(ReasonCode.LINEAGE_COUNT_MISMATCH,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(countDocument)),
                        countWorkspace,
                        countDocument.header()));
        assertWorkspaceEmpty(countWorkspace);

        FooterRecord wrongFingerprint = valid.footer().withContentFingerprint("0".repeat(64));
        Document fingerprintDocument = valid.withFooter(wrongFingerprint);
        Path fingerprintWorkspace = workspace(parent, "fingerprint");

        assertReason(ReasonCode.LINEAGE_FINGERPRINT_MISMATCH,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(fingerprintDocument)),
                        fingerprintWorkspace,
                        fingerprintDocument.header()));
        assertWorkspaceEmpty(fingerprintWorkspace);
    }

    @Test
    void rejectsMissingAlleleCoverageAndMatchedEngineWithoutCandidate(@TempDir Path parent)
            throws Exception {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.header();
        Document missingAllele = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1)),
                List.of(),
                List.of(),
                AnalysisAlleleLineageWireFixtures.footer(2, 1, 0, 0, 0, 0, 0, 0, 0)));
        Path coverageWorkspace = workspace(parent, "coverage");

        assertReason(ReasonCode.LINEAGE_COUNT_MISMATCH,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(missingAllele)),
                        coverageWorkspace,
                        header));
        assertWorkspaceEmpty(coverageWorkspace);

        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Document noCandidate = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                valid.inputRecords(),
                valid.alleleRecords(),
                List.of(),
                AnalysisAlleleLineageWireFixtures.footer(3, 1, 1, 0, 1, 1, 0, 0, 0)));
        Path candidateWorkspace = workspace(parent, "matched-without-candidate");

        assertReason(ReasonCode.LINEAGE_DANGLING_REFERENCE,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(noCandidate)),
                        candidateWorkspace,
                        noCandidate.header()));
        assertWorkspaceEmpty(candidateWorkspace);
    }

    @Test
    void rejectsDuplicateTokensAndNonCanonicalCandidateOrder(@TempDir Path parent)
            throws Exception {
        Document duplicateToken = twoAlleleDocument(true, false);
        Path tokenWorkspace = workspace(parent, "token");

        assertReason(ReasonCode.LINEAGE_DUPLICATE_ID,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(duplicateToken)),
                        tokenWorkspace,
                        duplicateToken.header()));
        assertWorkspaceEmpty(tokenWorkspace);

        Document reversedCandidates = twoAlleleDocument(false, true);
        Path orderWorkspace = workspace(parent, "candidate-order");

        assertReason(ReasonCode.LINEAGE_ORDER_INVALID,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(reversedCandidates)),
                        orderWorkspace,
                        reversedCandidates.header()));
        assertWorkspaceEmpty(orderWorkspace);
    }

    @Test
    void sanitizesInputFailuresAndCleansPrivateSession(@TempDir Path parent) throws Exception {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        byte[] ndjson = canonicalNdjson(document);
        int headerBytes = firstLf(ndjson) + 1;
        Path workspace = workspace(parent, "input-failure");

        LineageStreamingException failure = assertThrows(LineageStreamingException.class,
                () -> validator().validate(
                        new FailingInputStream(ndjson, headerBytes), workspace, document.header()));

        assertEquals(LineageStreamingException.Code.INPUT_READ_FAILED, failure.code());
        assertEquals(LineageStreamingException.Code.INPUT_READ_FAILED.name(), failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(INPUT_CANARY));
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void preservesSemanticFailureAndMakesIncompleteAbortCleanupObservable(
            @TempDir Path parent) throws Exception {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Document noCandidate = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                valid.inputRecords(),
                valid.alleleRecords(),
                List.of(),
                AnalysisAlleleLineageWireFixtures.footer(3, 1, 1, 0, 1, 1, 0, 0, 0)));
        Path workspace = workspace(parent, "semantic-cleanup-failure");
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        AtomicReference<BoundedExternalSortSpool> createdSpool = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook hook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.BEFORE_DELETE
                    && failFirstDelete.compareAndSet(true, false)) {
                throw new IOException(INPUT_CANARY);
            }
        };
        AnalysisAlleleLineageStreamingValidator validator = validator(hook, createdSpool);

        LineageValidationException failure = assertThrows(LineageValidationException.class,
                () -> validator.validate(
                        new ByteArrayInputStream(canonicalNdjson(noCandidate)),
                        workspace,
                        noCandidate.header()));

        assertEquals(ReasonCode.LINEAGE_DANGLING_REFERENCE, failure.getReasonCode());
        assertEquals(1, failure.getSuppressed().length);
        LineageSpoolException cleanupFailure = (LineageSpoolException) failure.getSuppressed()[0];
        assertEquals(LineageSpoolException.Code.CLEANUP_INCOMPLETE, cleanupFailure.code());
        assertEquals(LineageSpoolException.Code.CLEANUP_INCOMPLETE.name(),
                cleanupFailure.getMessage());
        assertNull(cleanupFailure.getCause());
        assertFalse(cleanupFailure.toString().contains(INPUT_CANARY));

        createdSpool.get().closeAfterFailure();
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void defaultStorageBoundaryFailsClosedWhenPosixPrivacyIsUnavailable(@TempDir Path parent)
            throws Exception {
        Assumptions.assumeFalse(FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        Path workspace = workspace(parent, "default-fail-closed");

        LineageSpoolException failure = assertThrows(LineageSpoolException.class,
                () -> new AnalysisAlleleLineageStreamingValidator().validate(
                        new ByteArrayInputStream(canonicalNdjson(document)),
                        workspace,
                        document.header()));

        assertEquals(LineageSpoolException.Code.FILESYSTEM_UNSUPPORTED, failure.code());
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void acceptsMoreThanTheA2InMemoryBodyLimit(@TempDir Path parent) throws Exception {
        int inputCount = AnalysisAlleleLineageWireValidator.MAX_IN_MEMORY_BODY_RECORDS + 1;
        HeaderRecord source = AnalysisAlleleLineageWireFixtures.header();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(source, new Limits(
                source.limits().maxSidecarBytes(),
                source.limits().maxLineBytes(),
                20_000,
                source.limits().maxInputAlleleCount(),
                source.limits().maxCandidateLinkCount(),
                source.limits().maxAlleleBytes(),
                source.limits().maxRelationFanIn(),
                source.limits().maxRelationFanOut()));
        List<InputRecord> inputs = new ArrayList<>(inputCount);
        for (int index = 1; index <= inputCount; index++) {
            inputs.add(AnalysisAlleleLineageWireFixtures.inputRecord(index, index, 0));
        }
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                inputs,
                List.of(),
                List.of(),
                AnalysisAlleleLineageWireFixtures.footer(
                        inputCount + 1L, inputCount, 0, 0, 0, 0, 0, 0, 0)));
        assertReason(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED,
                () -> new AnalysisAlleleLineageWireValidator().validate(document));
        Path workspace = workspace(parent, "over-a2-limit");

        AnalysisAlleleLineageStreamingValidator.ValidationSummary summary = validator().validate(
                new ByteArrayInputStream(canonicalNdjson(document)), workspace, header);

        assertEquals(inputCount, summary.bodyRecordCount());
        assertEquals(inputCount, summary.semanticFactCount());
        assertWorkspaceEmpty(workspace);
    }

    private static Document twoAlleleDocument(boolean duplicateToken,
                                              boolean reverseCandidates) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.header();
        AlleleKey firstKey = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleKey secondKey = AnalysisAlleleLineageWireFixtures.key("1", 101, "C", "T");
        String firstEngine = AnalysisAlleleLineageWireFixtures.engineId(firstKey);
        String secondEngine = AnalysisAlleleLineageWireFixtures.engineId(secondKey);
        String firstToken = "1".repeat(24);
        String secondToken = duplicateToken ? firstToken : "2".repeat(24);
        List<AlleleRecord> alleles = List.of(
                AnalysisAlleleLineageWireFixtures.matchedAllele(
                        3, 1, 1, 1, firstToken, firstKey, firstEngine),
                AnalysisAlleleLineageWireFixtures.matchedAllele(
                        4, 2, 1, 1, secondToken, secondKey, secondEngine));
        CandidateLinkRecord firstCandidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, reverseCandidates ? 2 : 1,
                reverseCandidates ? secondEngine : firstEngine,
                reverseCandidates ? secondKey.canonicalVariantId() : firstKey.canonicalVariantId());
        CandidateLinkRecord secondCandidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                6, reverseCandidates ? 1 : 2,
                reverseCandidates ? firstEngine : secondEngine,
                reverseCandidates ? firstKey.canonicalVariantId() : secondKey.canonicalVariantId());
        return AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                alleles,
                List.of(firstCandidate, secondCandidate),
                AnalysisAlleleLineageWireFixtures.footer(7, 2, 2, 0, 2, 2, 2, 0, 0)));
    }

    private static AnalysisAlleleLineageStreamingValidator validator() {
        return validator(BoundedExternalSortSpool.FaultHook.NOOP, new AtomicReference<>());
    }

    private static AnalysisAlleleLineageStreamingValidator validator(
            BoundedExternalSortSpool.FaultHook faultHook,
            AtomicReference<BoundedExternalSortSpool> createdSpool) {
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
        return new AnalysisAlleleLineageStreamingValidator((workspace, supplied) -> {
            BoundedExternalSortSpool spool = new BoundedExternalSortSpool(
                    workspace,
                    supplied,
                    new SecureRandom(),
                    new PortableIdentitySource(),
                    ignored -> BoundedExternalSortSpool.PrivacyStatus.PRIVATE,
                    faultHook,
                    BoundedExternalSortSpool.Observer.NOOP);
            createdSpool.set(spool);
            return spool;
        }, limits);
    }

    private static byte[] canonicalNdjson(Document document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (WireRecord record : document.recordsInCanonicalSections()) {
            output.write(PROJECTOR.canonicalRecordBytes(record));
            output.write('\n');
        }
        return output.toByteArray();
    }

    private static int firstLf(byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        throw new AssertionError("missing LF");
    }

    private static Path workspace(Path parent, String name) throws IOException {
        return Files.createDirectory(parent.resolve(name));
    }

    private static void assertWorkspaceEmpty(Path workspace) throws IOException {
        try (Stream<Path> children = Files.list(workspace)) {
            assertEquals(0L, children.count());
        }
    }

    private static void assertReason(ReasonCode expected, CheckedRunnable runnable) {
        LineageValidationException failure = assertThrows(
                LineageValidationException.class, runnable::run);
        assertEquals(expected, failure.getReasonCode());
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class FailingInputStream extends InputStream {
        private final byte[] bytes;
        private final int failAt;
        private int index;

        private FailingInputStream(byte[] bytes, int failAt) {
            this.bytes = bytes.clone();
            this.failAt = failAt;
        }

        @Override
        public int read() throws IOException {
            if (index >= failAt) {
                throw new IOException(INPUT_CANARY);
            }
            return bytes[index++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (index >= failAt) {
                throw new IOException(INPUT_CANARY);
            }
            int count = Math.min(length, failAt - index);
            System.arraycopy(bytes, index, target, offset, count);
            index += count;
            return count;
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
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
