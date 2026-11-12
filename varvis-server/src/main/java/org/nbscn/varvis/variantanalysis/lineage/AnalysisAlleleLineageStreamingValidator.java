package org.nbscn.varvis.variantanalysis.lineage;

import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageFactCodec.CoverageFact;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageFactCodec.Envelope;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageFactCodec.LinkFact;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.DocumentStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineAlleleRef;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineMatchStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecordStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-memory semantic validator for canonical {@code analysis-allele-lineage-v1} NDJSON.
 *
 * <p>The input is read exactly once and only one bounded wire line is retained. Cross-record
 * relations are encoded without hashes into one owner-private external-sort spool, so the 16 GiB
 * disk, 64 MiB buffer, run-file and descriptor limits are global rather than multiplied by fact
 * family. This class is deliberately detached from product runtime, persistence and publishing.
 * The caller owns the input stream and the workspace itself; every spool session created below it
 * is removed before a successful return. Isolated semantic invariants retain A2's stable
 * reason/field diagnostics. If one document contains several independent defects, the first
 * streaming diagnostic follows deterministic online/fact-family precedence and is not promised
 * to equal A2's record-interleaved first diagnostic.</p>
 */
final class AnalysisAlleleLineageStreamingValidator {

    /*
     * The smallest canonical AlleleKey object is exactly:
     * {"assembly":"GRCh37","chromosome":"1","position":1,"reference":"A",
     *  "alternate":"C","canonicalVariantId":"GRCh37|1|1|A|C"}
     * (shown split only in this comment), which is 121 UTF-8 bytes. Therefore the 512 MiB wire
     * ceiling permits at most floor(536,870,912 / 121) lifted-key occurrences. With R body rows,
     * A allele rows, K candidate rows and L lifted occurrences, this emitter produces at most
     * R + 5A + 3K + L facts. The constants below are deliberately executable proof inputs. This
     * is a record-count proof only: repeated long identities can make fact bytes exceed the fixed
     * spool-byte limit before the wire-byte ceiling, so capacity qualification remains separate.
     */
    static final int MIN_CANONICAL_ALLELE_KEY_JSON_BYTES = 121;
    static final long MAX_LIFTED_RELATION_FACTS =
            AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES / MIN_CANONICAL_ALLELE_KEY_JSON_BYTES;
    static final long PROVEN_MAX_SEMANTIC_FACTS =
            AnalysisAlleleLineageWire.MAX_BODY_RECORD_COUNT
                    + 5L * AnalysisAlleleLineageWire.MAX_INPUT_ALLELE_COUNT
                    + 3L * AnalysisAlleleLineageWire.MAX_CANDIDATE_LINK_COUNT
                    + MAX_LIFTED_RELATION_FACTS;

    private static final byte LF = (byte) '\n';
    private static final long MAX_BUFFERED_BYTES = 67_108_864L;
    private static final int MAX_BUFFERED_RECORDS = 65_536;
    private static final int MERGE_FAN_IN = 64;
    private static final long MAX_SPOOL_BYTES = 17_179_869_184L;
    private static final int MAX_LIVE_RUN_FILES = 256;
    private static final int MAX_CREATED_RUN_FILES = 4_096;

    private final AnalysisAlleleLineageCanonicalProjector projector;
    private final AnalysisAlleleLineageRecordParser parser;
    private final FactSpoolFactory spoolFactory;
    private final BoundedExternalSortSpool.Limits spoolLimits;

    AnalysisAlleleLineageStreamingValidator() {
        this(BoundedExternalSortSpool::new, defaultSpoolLimits());
    }

    AnalysisAlleleLineageStreamingValidator(FactSpoolFactory spoolFactory,
                                             BoundedExternalSortSpool.Limits spoolLimits) {
        this.projector = new AnalysisAlleleLineageCanonicalProjector();
        this.parser = new AnalysisAlleleLineageRecordParser(projector);
        this.spoolFactory = Objects.requireNonNull(spoolFactory, "spoolFactory");
        this.spoolLimits = Objects.requireNonNull(spoolLimits, "spoolLimits");
    }

    /**
     * Validates one canonical stream against the complete expected header binding.
     *
     * @throws LineageValidationException for a controlled wire or semantic failure
     * @throws LineageStreamingException for a sanitized caller-stream failure
     * @throws LineageSpoolException for a sanitized private-storage failure
     */
    ValidationSummary validate(InputStream canonicalNdjson,
                               Path privateSpoolWorkspace,
                               HeaderRecord expectedHeader)
            throws LineageStreamingException, LineageSpoolException {
        if (canonicalNdjson == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "input");
        }
        if (privateSpoolWorkspace == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "spoolWorkspace");
        }
        if (expectedHeader == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "expectedHeader");
        }
        AnalysisAlleleLineageWireValidator.validateHeaderRecord(expectedHeader);

        AnalysisAlleleLineageNdjsonCodec.BoundedNdjsonInput source =
                new AnalysisAlleleLineageNdjsonCodec.BoundedNdjsonInput(canonicalNdjson);
        byte[] firstLine = readLine(
                source,
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES);
        if (firstLine == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FOOTER_MISSING);
        }
        WireRecord first = parser.parse(firstLine);
        if (!(first instanceof HeaderRecord header)) {
            fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordType");
            return null;
        }
        AnalysisAlleleLineageWireValidator.validateHeaderRecord(header);
        requireExpectedHeader(header, expectedHeader);
        if (firstLine.length > header.limits().maxLineBytes()) {
            fail(ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED, "maxLineBytes");
        }
        if (source.totalBytes() > header.limits().maxSidecarBytes()) {
            fail(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED, "maxSidecarBytes");
        }

        MessageDigest fingerprint = sha256();
        updateFingerprint(fingerprint, firstLine);
        BoundedExternalSortSpool spool = spoolFactory.create(privateSpoolWorkspace, spoolLimits);
        Throwable primaryFailure = null;
        try {
            return validateBody(source, header, fingerprint, spool);
        } catch (LineageStreamingException | LineageSpoolException
                 | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                if (primaryFailure == null) {
                    spool.close();
                } else {
                    spool.closeAfterFailure();
                }
            } catch (LineageSpoolException cleanupFailure) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
                // Preserve the selected semantic/transport failure while making a sanitized
                // cleanup failure observable to callers and operations telemetry.
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private ValidationSummary validateBody(
            AnalysisAlleleLineageNdjsonCodec.BoundedNdjsonInput source,
            HeaderRecord header,
            MessageDigest fingerprint,
            BoundedExternalSortSpool spool)
            throws LineageStreamingException, LineageSpoolException {
        int maxLineBytes = Math.min(
                header.limits().maxLineBytes(), AnalysisAlleleLineageWire.MAX_LINE_BYTES);
        long maxSidecarBytes = Math.min(
                header.limits().maxSidecarBytes(), AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES);
        long maxBodyRecords = Math.min(
                header.limits().maxRecordCount(), AnalysisAlleleLineageWire.MAX_BODY_RECORD_COUNT);
        long maxInputAlleles = Math.min(
                header.limits().maxInputAlleleCount(),
                AnalysisAlleleLineageWire.MAX_INPUT_ALLELE_COUNT);
        long maxCandidateLinks = Math.min(
                header.limits().maxCandidateLinkCount(),
                AnalysisAlleleLineageWire.MAX_CANDIDATE_LINK_COUNT);

        OnlineFacts facts = new OnlineFacts();
        Section section = Section.INPUT;
        long expectedRecordOrdinal = 1L;
        long bodyRecordCount = 0L;
        long previousAlleleSourceOrdinal = -1L;
        int previousAlleleIndex = -1;
        FooterRecord footer = null;

        while (footer == null) {
            byte[] line = readLine(source, maxLineBytes, maxSidecarBytes);
            if (line == null) {
                fail(ReasonCode.LINEAGE_FOOTER_MISSING, null);
            }
            WireRecord record = parser.parse(line);
            if (record instanceof FooterRecord value) {
                AnalysisAlleleLineageWireValidator.validateStreamingCommon(
                        value, RecordType.FOOTER, expectedRecordOrdinal);
                footer = value;
                updateFingerprint(fingerprint, projector.canonicalFingerprintRecordBytes(value));
                continue;
            }
            if (record.recordType() == RecordType.HEADER) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordType");
            }
            bodyRecordCount = incrementWithin(
                    bodyRecordCount, maxBodyRecords,
                    ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxRecordCount");

            if (record instanceof InputRecord value) {
                requireSection(section, Section.INPUT);
                AnalysisAlleleLineageWireValidator.validateStreamingCommon(
                        value, RecordType.INPUT_RECORD, expectedRecordOrdinal);
                AnalysisAlleleLineageWireValidator.validateStreamingInputRecord(
                        value, facts.inputRecordCount + 1L);
                facts.inputRecordCount = increment(facts.inputRecordCount, "inputRecordCount");
                append(spool, facts,
                        AnalysisAlleleLineageFactCodec.coverageKey(value.sourceRecordOrdinal()),
                        AnalysisAlleleLineageFactCodec.inputCoverageValue(value));
            } else if (record instanceof AlleleRecord value) {
                requireSection(section, Section.INPUT, Section.ALLELE);
                section = Section.ALLELE;
                AnalysisAlleleLineageWireValidator.validateStreamingCommon(
                        value, RecordType.ALLELE_LINEAGE, expectedRecordOrdinal);
                AnalysisAlleleLineageWireValidator.validateStreamingAlleleRecord(value, header);
                if (value.sourceRecordOrdinal() < previousAlleleSourceOrdinal
                        || (value.sourceRecordOrdinal() == previousAlleleSourceOrdinal
                        && value.sourceAlternateIndex() < previousAlleleIndex)) {
                    fail(ReasonCode.LINEAGE_ORDER_INVALID, "alleleRecords");
                }
                if (value.sourceRecordOrdinal() == previousAlleleSourceOrdinal
                        && value.sourceAlternateIndex() == previousAlleleIndex) {
                    fail(ReasonCode.LINEAGE_DUPLICATE_ID, "sourceAlleleToken");
                }
                previousAlleleSourceOrdinal = value.sourceRecordOrdinal();
                previousAlleleIndex = value.sourceAlternateIndex();
                facts.inputAlleleCount = incrementWithin(
                        facts.inputAlleleCount, maxInputAlleles,
                        ReasonCode.LINEAGE_ALLELE_LIMIT_EXCEEDED, "maxInputAlleleCount");
                acceptAllele(value, spool, facts);
            } else if (record instanceof CandidateLinkRecord value) {
                requireSection(section, Section.INPUT, Section.ALLELE, Section.CANDIDATE);
                section = Section.CANDIDATE;
                AnalysisAlleleLineageWireValidator.validateStreamingCommon(
                        value, RecordType.CANDIDATE_LINK, expectedRecordOrdinal);
                AnalysisAlleleLineageWireValidator.validateStreamingCandidateRecord(value);
                long candidateSequence = facts.candidateLinkCount;
                facts.candidateLinkCount = incrementWithin(
                        facts.candidateLinkCount, maxCandidateLinks,
                        ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxCandidateLinkCount");
                acceptCandidate(value, candidateSequence, spool, facts);
            } else {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "recordType");
            }
            updateFingerprint(fingerprint, line);
            expectedRecordOrdinal = increment(expectedRecordOrdinal, "recordOrdinal");
        }

        byte[] trailing = readLine(source, maxLineBytes, maxSidecarBytes);
        if (trailing != null) {
            fail(ReasonCode.LINEAGE_ORDER_INVALID, "document");
        }

        ReductionSummary reduction = reduce(spool, header, facts);
        if (facts.successfulAlleleCount > 0 && facts.hasLiftoverFailure) {
            facts.derivedFailureReasons.add(ReasonCode.LIFTOVER_PARTIAL);
        }
        List<ReasonCode> reasons = List.copyOf(facts.derivedFailureReasons);
        AnalysisAlleleLineageWireValidator.StreamingFooterFacts footerFacts =
                new AnalysisAlleleLineageWireValidator.StreamingFooterFacts(
                        facts.inputRecordCount,
                        facts.inputAlleleCount,
                        facts.liftedAlleleCount,
                        facts.canonicalAlleleCount,
                        reduction.engineAlleleCount(),
                        facts.candidateLinkCount,
                        facts.unresolvedAlleleCount,
                        facts.rejectedAlleleCount,
                        facts.successfulAlleleCount,
                        facts.unlinkedCandidateCount,
                        reasons,
                        facts.liftoverArtifactRequired,
                        facts.engineSubmitted
                );
        AnalysisAlleleLineageWireValidator.validateStreamingFooterRecord(
                footer, header, footerFacts);

        String computedFingerprint = HexFormat.of().formatHex(fingerprint.digest());
        byte[] computed = computedFingerprint.getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = footer.contentFingerprint().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(computed, supplied)) {
            fail(ReasonCode.LINEAGE_FINGERPRINT_MISMATCH, "contentFingerprint");
        }
        return new ValidationSummary(
                footer.status(),
                footer.reasonCodes(),
                facts.inputRecordCount,
                facts.inputAlleleCount,
                facts.liftedAlleleCount,
                facts.canonicalAlleleCount,
                reduction.engineAlleleCount(),
                facts.candidateLinkCount,
                facts.unresolvedAlleleCount,
                facts.rejectedAlleleCount,
                bodyRecordCount,
                facts.semanticFactCount,
                source.totalBytes(),
                computedFingerprint
        );
    }

    private static void acceptAllele(AlleleRecord allele,
                                     BoundedExternalSortSpool spool,
                                     OnlineFacts facts) throws LineageSpoolException {
        append(spool, facts,
                AnalysisAlleleLineageFactCodec.coverageKey(allele.sourceRecordOrdinal()),
                AnalysisAlleleLineageFactCodec.alleleCoverageValue(allele));
        append(spool, facts,
                AnalysisAlleleLineageFactCodec.tokenKey(allele.sourceAlleleToken()),
                AnalysisAlleleLineageFactCodec.sourcePairValue(allele));
        if (allele.inputStatus() == InputStatus.ACCEPTED) {
            append(spool, facts,
                    AnalysisAlleleLineageFactCodec.sourceOutcomeKey(allele),
                    AnalysisAlleleLineageFactCodec.sourceOutcomeValue(allele));
            append(spool, facts,
                    AnalysisAlleleLineageFactCodec.duplicateSourceKey(allele),
                    AnalysisAlleleLineageFactCodec.duplicateDeclaredValue(allele));
        }
        for (AlleleKey lifted : allele.liftedKeys()) {
            append(spool, facts,
                    AnalysisAlleleLineageFactCodec.liftedRelationKey(lifted),
                    AnalysisAlleleLineageFactCodec.liftedRelationValue(allele));
        }
        for (AlleleKey canonical : allele.canonicalKeys()) {
            append(spool, facts,
                    AnalysisAlleleLineageFactCodec.canonicalRelationKey(canonical),
                    AnalysisAlleleLineageFactCodec.canonicalRelationValue(allele));
        }
        for (EngineAlleleRef engine : allele.engineAlleles()) {
            append(spool, facts,
                    AnalysisAlleleLineageFactCodec.engineJoinKey(engine.engineAlleleId()),
                    AnalysisAlleleLineageFactCodec.engineDefinitionValue(allele, engine));
        }

        facts.liftedAlleleCount = add(facts.liftedAlleleCount,
                allele.liftedKeys().size(), "liftedAlleleCount");
        facts.canonicalAlleleCount = add(facts.canonicalAlleleCount,
                allele.canonicalKeys().size(), "canonicalAlleleCount");
        facts.engineSubmitted |= allele.engineMatchStatus() != EngineMatchStatus.NOT_SUBMITTED;
        facts.liftoverArtifactRequired |=
                AnalysisAlleleLineageWireValidator.streamingRequiresLiftoverArtifact(
                        allele.liftoverStatus());
        if (AnalysisAlleleLineageWireValidator.streamingRejected(allele)) {
            facts.rejectedAlleleCount = increment(facts.rejectedAlleleCount,
                    "rejectedAlleleCount");
        } else if (AnalysisAlleleLineageWireValidator.streamingUnresolved(allele)) {
            facts.unresolvedAlleleCount = increment(facts.unresolvedAlleleCount,
                    "unresolvedAlleleCount");
        }
        if (AnalysisAlleleLineageWireValidator.streamingSuccessful(allele)) {
            facts.successfulAlleleCount = increment(facts.successfulAlleleCount,
                    "successfulAlleleCount");
        }
        List<ReasonCode> failureReasons =
                AnalysisAlleleLineageWireValidator.streamingFailureReasons(allele);
        facts.derivedFailureReasons.addAll(failureReasons);
        facts.hasLiftoverFailure |= !failureReasons.isEmpty()
                && AnalysisAlleleLineageWireValidator.streamingLiftoverFailure(
                allele.liftoverStatus());
    }

    private static void acceptCandidate(CandidateLinkRecord candidate,
                                        long candidateSequence,
                                        BoundedExternalSortSpool spool,
                                        OnlineFacts facts) throws LineageSpoolException {
        append(spool, facts,
                AnalysisAlleleLineageFactCodec.candidateIdKey(candidate),
                AnalysisAlleleLineageFactCodec.candidateSourceValue(candidate));
        append(spool, facts,
                AnalysisAlleleLineageFactCodec.candidateSourceKey(candidate),
                AnalysisAlleleLineageFactCodec.candidateIdValue(candidate));
        append(spool, facts,
                AnalysisAlleleLineageFactCodec.candidateOrderKey(candidate),
                AnalysisAlleleLineageFactCodec.candidateOrderValue(candidateSequence));
        if (candidate.linkStatus() == CandidateLinkStatus.LINKED) {
            append(spool, facts,
                    AnalysisAlleleLineageFactCodec.engineJoinKey(candidate.engineAlleleId()),
                    AnalysisAlleleLineageFactCodec.candidateLinkValue(candidate));
        } else {
            facts.unlinkedCandidateCount = increment(
                    facts.unlinkedCandidateCount, "unlinkedCandidateCount");
            facts.derivedFailureReasons.addAll(candidate.reasonCodes());
        }
    }

    private static ReductionSummary reduce(BoundedExternalSortSpool spool,
                                           HeaderRecord header,
                                           OnlineFacts facts) throws LineageSpoolException {
        BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
        ReductionAccumulator reduction = new ReductionAccumulator(
                header.limits().maxRelationFanIn(),
                header.limits().maxRelationFanOut());
        BoundedExternalSortSpool.Entry current = cursor.next();
        while (current != null) {
            byte[] groupKey = current.key();
            byte kind = AnalysisAlleleLineageFactCodec.kind(groupKey);
            FactGroup group = new FactGroup(kind, groupKey, reduction);
            do {
                group.accept(current.value());
                current = cursor.next();
            } while (current != null && Arrays.equals(groupKey, current.key()));
            group.finish();
        }
        cursor.close();
        if (reduction.coverageGroups != facts.inputRecordCount) {
            fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceRecordOrdinal");
        }
        if (reduction.candidateOrderCount != facts.candidateLinkCount) {
            fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "candidateLinks");
        }
        if (reduction.matchedEngineWithoutCandidate) {
            fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "candidateLinks");
        }
        return new ReductionSummary(reduction.engineAlleleCount);
    }

    private static void append(BoundedExternalSortSpool spool,
                               OnlineFacts facts,
                               byte[] key,
                               byte[] value) throws LineageSpoolException {
        if (facts.semanticFactCount >= PROVEN_MAX_SEMANTIC_FACTS) {
            fail(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "semanticFactCount");
        }
        spool.append(key, value);
        facts.semanticFactCount++;
    }

    private void requireExpectedHeader(HeaderRecord actual, HeaderRecord expected) {
        byte[] actualBytes = projector.canonicalRecordBytes(actual);
        byte[] expectedBytes = projector.canonicalRecordBytes(expected);
        if (!MessageDigest.isEqual(actualBytes, expectedBytes)) {
            fail(ReasonCode.LINEAGE_REUSE_BINDING_MISMATCH, "expectedHeader");
        }
    }

    private static byte[] readLine(
            AnalysisAlleleLineageNdjsonCodec.BoundedNdjsonInput source,
            int maxLineBytes,
            long maxSidecarBytes) throws LineageStreamingException {
        try {
            return source.readLine(maxLineBytes, maxSidecarBytes);
        } catch (LineageValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new LineageStreamingException(LineageStreamingException.Code.INPUT_READ_FAILED);
        }
    }

    private static void updateFingerprint(MessageDigest fingerprint, byte[] canonicalLine) {
        fingerprint.update(canonicalLine);
        fingerprint.update(LF);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE");
        }
    }

    private static long increment(long current, String field) {
        return add(current, 1L, field);
    }

    private static long incrementWithin(long current,
                                        long ceiling,
                                        ReasonCode reasonCode,
                                        String field) {
        if (current < 0 || ceiling < 0 || current >= ceiling) {
            fail(reasonCode, field);
        }
        return current + 1L;
    }

    private static long add(long current, long addition, String field) {
        if (current < 0 || addition < 0 || addition > Long.MAX_VALUE - current) {
            fail(ReasonCode.LINEAGE_COUNT_MISMATCH, field);
        }
        return current + addition;
    }

    private static void requireSection(Section actual, Section... allowed) {
        for (Section value : allowed) {
            if (actual == value) {
                return;
            }
        }
        fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordType");
    }

    private static BoundedExternalSortSpool.Limits defaultSpoolLimits() {
        return new BoundedExternalSortSpool.Limits(
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                PROVEN_MAX_SEMANTIC_FACTS,
                MAX_BUFFERED_BYTES,
                MAX_BUFFERED_RECORDS,
                MERGE_FAN_IN,
                MAX_SPOOL_BYTES,
                MAX_LIVE_RUN_FILES,
                MAX_CREATED_RUN_FILES
        );
    }

    private static void fail(ReasonCode reasonCode, String field) {
        throw field == null
                ? new LineageValidationException(reasonCode)
                : new LineageValidationException(reasonCode, field);
    }

    @FunctionalInterface
    interface FactSpoolFactory {
        BoundedExternalSortSpool create(Path privateWorkspace,
                                        BoundedExternalSortSpool.Limits limits)
                throws LineageSpoolException;
    }

    record ValidationSummary(DocumentStatus status,
                             List<ReasonCode> reasonCodes,
                             long inputRecordCount,
                             long inputAlleleCount,
                             long liftedAlleleCount,
                             long canonicalAlleleCount,
                             long engineAlleleCount,
                             long candidateLinkCount,
                             long unresolvedAlleleCount,
                             long rejectedAlleleCount,
                             long bodyRecordCount,
                             long semanticFactCount,
                             long physicalBytes,
                             String contentFingerprint) {

        ValidationSummary {
            status = Objects.requireNonNull(status, "status");
            reasonCodes = List.copyOf(reasonCodes);
            contentFingerprint = Objects.requireNonNull(contentFingerprint, "contentFingerprint");
        }

        @Override
        public String toString() {
            return "ValidationSummary[redacted]";
        }
    }

    private enum Section {
        INPUT,
        ALLELE,
        CANDIDATE
    }

    private static final class OnlineFacts {
        private long inputRecordCount;
        private long inputAlleleCount;
        private long liftedAlleleCount;
        private long canonicalAlleleCount;
        private long candidateLinkCount;
        private long unresolvedAlleleCount;
        private long rejectedAlleleCount;
        private long successfulAlleleCount;
        private long unlinkedCandidateCount;
        private long semanticFactCount;
        private boolean liftoverArtifactRequired;
        private boolean engineSubmitted;
        private boolean hasLiftoverFailure;
        private final EnumSet<ReasonCode> derivedFailureReasons =
                EnumSet.noneOf(ReasonCode.class);
    }

    private record ReductionSummary(long engineAlleleCount) {
    }

    private static final class ReductionAccumulator {
        private final int maxFanIn;
        private final int maxFanOut;
        private long expectedCoverageOrdinal = 1L;
        private long nextCandidateSequence;
        private long coverageGroups;
        private long candidateOrderCount;
        private long engineAlleleCount;
        private boolean matchedEngineWithoutCandidate;

        private ReductionAccumulator(int maxFanIn, int maxFanOut) {
            this.maxFanIn = maxFanIn;
            this.maxFanOut = maxFanOut;
        }
    }

    /** O(1)-memory reducer for one equal-key fact group. */
    private static final class FactGroup {
        private final byte kind;
        private final ReductionAccumulator reduction;
        private long count;
        private boolean inputCoverageSeen;
        private int inputAlternateCount;
        private int inputStatusOrdinal = -1;
        private int alleleCoverageCount;
        private boolean alleleIndexMismatch;
        private boolean anyDeclaredDuplicate;
        private boolean allDeclaredDuplicate = true;
        private byte[] firstValue;
        private Envelope firstEnvelope;
        private LinkFact firstEngineDefinition;
        private long engineDefinitions;
        private long candidateDefinitions;
        private boolean candidatePhase;

        private FactGroup(byte kind, byte[] key, ReductionAccumulator reduction) {
            this.kind = kind;
            this.reduction = reduction;
            if (kind == AnalysisAlleleLineageFactCodec.COVERAGE) {
                long sourceOrdinal = AnalysisAlleleLineageFactCodec.decodeCoverageKey(key);
                if (sourceOrdinal != reduction.expectedCoverageOrdinal) {
                    fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "sourceRecordOrdinal");
                }
            } else if (kind != AnalysisAlleleLineageFactCodec.TOKEN
                    && kind != AnalysisAlleleLineageFactCodec.SOURCE_OUTCOME
                    && kind != AnalysisAlleleLineageFactCodec.DUPLICATE_SOURCE
                    && kind != AnalysisAlleleLineageFactCodec.LIFTED_RELATION
                    && kind != AnalysisAlleleLineageFactCodec.CANONICAL_RELATION
                    && kind != AnalysisAlleleLineageFactCodec.ENGINE_JOIN
                    && kind != AnalysisAlleleLineageFactCodec.CANDIDATE_ID
                    && kind != AnalysisAlleleLineageFactCodec.CANDIDATE_SOURCE
                    && kind != AnalysisAlleleLineageFactCodec.CANDIDATE_ORDER) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
            }
        }

        private void accept(byte[] value) {
            count = increment(count, "semanticFactCount");
            switch (kind) {
                case AnalysisAlleleLineageFactCodec.COVERAGE -> acceptCoverage(value);
                case AnalysisAlleleLineageFactCodec.TOKEN -> {
                    if (count > 1) {
                        fail(ReasonCode.LINEAGE_DUPLICATE_ID, "sourceAlleleToken");
                    }
                }
                case AnalysisAlleleLineageFactCodec.SOURCE_OUTCOME ->
                        requireSameValue(value, ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "sourceKey");
                case AnalysisAlleleLineageFactCodec.DUPLICATE_SOURCE -> {
                    boolean declared = AnalysisAlleleLineageFactCodec.decodeBoolean(value);
                    anyDeclaredDuplicate |= declared;
                    allDeclaredDuplicate &= declared;
                }
                case AnalysisAlleleLineageFactCodec.LIFTED_RELATION ->
                        acceptRelation(value, "liftedKeys", false);
                case AnalysisAlleleLineageFactCodec.CANONICAL_RELATION ->
                        acceptRelation(value, "canonicalKeys", true);
                case AnalysisAlleleLineageFactCodec.ENGINE_JOIN -> acceptEngineJoin(value);
                case AnalysisAlleleLineageFactCodec.CANDIDATE_ID -> {
                    if (count > 1) {
                        fail(ReasonCode.LINEAGE_DUPLICATE_ID, "candidateId");
                    }
                }
                case AnalysisAlleleLineageFactCodec.CANDIDATE_SOURCE -> {
                    if (count > 1) {
                        fail(ReasonCode.LINEAGE_DUPLICATE_ID, "candidateSourceOrdinal");
                    }
                }
                case AnalysisAlleleLineageFactCodec.CANDIDATE_ORDER -> {
                    long sequence = AnalysisAlleleLineageFactCodec.decodeCandidateOrderValue(value);
                    if (sequence != reduction.nextCandidateSequence) {
                        fail(ReasonCode.LINEAGE_ORDER_INVALID, "candidateLinks");
                    }
                    reduction.nextCandidateSequence++;
                    reduction.candidateOrderCount++;
                    if (count > 1) {
                        fail(ReasonCode.LINEAGE_ORDER_INVALID, "candidateLinks");
                    }
                }
                default -> fail(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
            }
        }

        private void acceptCoverage(byte[] value) {
            CoverageFact fact = AnalysisAlleleLineageFactCodec.decodeCoverageValue(value);
            if (fact.inputRecord()) {
                if (inputCoverageSeen || count != 1) {
                    fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceAlternateCount");
                }
                inputCoverageSeen = true;
                inputAlternateCount = fact.sourceAlternateCount();
                inputStatusOrdinal = fact.inputStatusOrdinal();
                return;
            }
            if (!inputCoverageSeen) {
                fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "sourceRecordOrdinal");
            }
            if (inputStatusOrdinal != InputRecordStatus.TARGETS_CAPTURED.ordinal()) {
                fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "sourceRecordOrdinal");
            }
            if (fact.sourceAlternateCount() != inputAlternateCount) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "sourceAlternateIndex");
            }
            alleleCoverageCount++;
            alleleIndexMismatch |= fact.sourceAlternateIndex() != alleleCoverageCount;
        }

        private void acceptRelation(byte[] value, String field, boolean compareEngineOutcome) {
            Envelope envelope = AnalysisAlleleLineageFactCodec.decodeEnvelope(value);
            if (firstEnvelope == null) {
                firstEnvelope = envelope;
            } else if (!firstEnvelope.sameFirst(envelope)) {
                fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                        compareEngineOutcome ? "engineMatchStatus" : field);
            }
            if (count > reduction.maxFanIn) {
                fail(ReasonCode.LINEAGE_FAN_IN_LIMIT_EXCEEDED, field);
            }
        }

        private void acceptEngineJoin(byte[] value) {
            LinkFact link = AnalysisAlleleLineageFactCodec.decodeLinkValue(value);
            if (link.engineDefinition()) {
                if (candidatePhase) {
                    fail(ReasonCode.LINEAGE_ORDER_INVALID, "engineAlleles");
                }
                engineDefinitions++;
                if (engineDefinitions > reduction.maxFanIn) {
                    fail(ReasonCode.LINEAGE_FAN_IN_LIMIT_EXCEEDED, "engineAlleles");
                }
                if (firstEngineDefinition == null) {
                    firstEngineDefinition = link;
                } else if (!firstEngineDefinition.sameCanonical(link)) {
                    fail(ReasonCode.LINEAGE_DUPLICATE_ID, "engineAlleleId");
                }
                return;
            }
            candidatePhase = true;
            candidateDefinitions++;
            if (candidateDefinitions > reduction.maxFanOut) {
                fail(ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED, "candidateLinks");
            }
            if (firstEngineDefinition == null) {
                fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "engineAlleleId");
            }
            if (!firstEngineDefinition.sameCanonical(link)) {
                fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "analysisCanonicalVariantId");
            }
        }

        private void requireSameValue(byte[] value, ReasonCode reasonCode, String field) {
            if (firstValue == null) {
                firstValue = value.clone();
            } else if (!Arrays.equals(firstValue, value)) {
                fail(reasonCode, field);
            }
        }

        private void finish() {
            switch (kind) {
                case AnalysisAlleleLineageFactCodec.COVERAGE -> finishCoverage();
                case AnalysisAlleleLineageFactCodec.DUPLICATE_SOURCE -> {
                    if ((count == 1 && anyDeclaredDuplicate)
                            || (count > 1 && !allDeclaredDuplicate)) {
                        fail(ReasonCode.LINEAGE_FIELD_INVALID, "inputReasonCodes");
                    }
                }
                case AnalysisAlleleLineageFactCodec.ENGINE_JOIN -> {
                    if (engineDefinitions == 0) {
                        fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "engineAlleleId");
                    }
                    if (candidateDefinitions == 0) {
                        // A2 validates every candidate reference before checking that each matched
                        // engine has a candidate. Defer this aggregate failure until all sorted
                        // groups have streamed so an invalid candidate keeps the same priority.
                        reduction.matchedEngineWithoutCandidate = true;
                    }
                    reduction.engineAlleleCount = increment(
                            reduction.engineAlleleCount, "engineAlleleCount");
                }
                case AnalysisAlleleLineageFactCodec.CANDIDATE_ORDER -> {
                    if (count != 1) {
                        fail(ReasonCode.LINEAGE_ORDER_INVALID, "candidateLinks");
                    }
                }
                default -> {
                    // All other families are completely checked while their values stream past.
                }
            }
        }

        private void finishCoverage() {
            if (!inputCoverageSeen) {
                fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "sourceRecordOrdinal");
            }
            if (inputStatusOrdinal == InputRecordStatus.NO_TARGET_ALLELES.ordinal()) {
                if (inputAlternateCount != 0 || alleleCoverageCount != 0) {
                    fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceAlternateCount");
                }
            } else if (inputStatusOrdinal == InputRecordStatus.TARGETS_CAPTURED.ordinal()) {
                if (inputAlternateCount <= 0 || alleleCoverageCount != inputAlternateCount) {
                    fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceAlternateCount");
                }
                if (alleleIndexMismatch) {
                    fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceAlternateIndex");
                }
            } else {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "inputRecordStatus");
            }
            reduction.coverageGroups++;
            reduction.expectedCoverageOrdinal++;
        }
    }
}
