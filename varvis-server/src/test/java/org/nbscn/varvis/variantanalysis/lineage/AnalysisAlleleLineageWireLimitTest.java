package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.DocumentStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineMatchStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RelationCardinality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageWireLimitTest {

    private final AnalysisAlleleLineageWireValidator validator =
            new AnalysisAlleleLineageWireValidator();

    @Test
    void acceptsThePublishedHardCeilingsForASmallDocument() {
        Document document = replaceLimits(
                AnalysisAlleleLineageWireFixtures.validDocument(),
                hardCeilings());

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @ParameterizedTest
    @MethodSource("limitsAbovePublishedHardCeilings")
    void rejectsEveryLimitValueAboveItsPublishedHardCeiling(Limits limits) {
        Document document = replaceLimits(
                AnalysisAlleleLineageWireFixtures.validDocument(), limits);

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @ParameterizedTest
    @MethodSource("limitsWithAZeroValue")
    void rejectsEveryNonPositiveLimitValue(Limits limits) {
        Document document = replaceLimits(
                AnalysisAlleleLineageWireFixtures.validDocument(), limits);

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @Test
    void rejectsCanonicalRecordAboveConfiguredLineBytes() {
        Limits source = AnalysisAlleleLineageWireFixtures.header().limits();
        Limits limits = new Limits(
                source.maxSidecarBytes(), 1, source.maxRecordCount(),
                source.maxInputAlleleCount(), source.maxCandidateLinkCount(),
                source.maxAlleleBytes(), source.maxRelationFanIn(),
                source.maxRelationFanOut());
        Document document = replaceLimits(
                AnalysisAlleleLineageWireFixtures.validDocument(), limits);

        assertCode(ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void rejectsCanonicalDocumentAboveConfiguredSidecarBytes() {
        Limits source = AnalysisAlleleLineageWireFixtures.header().limits();
        Limits limits = new Limits(
                1, source.maxLineBytes(), source.maxRecordCount(),
                source.maxInputAlleleCount(), source.maxCandidateLinkCount(),
                source.maxAlleleBytes(), source.maxRelationFanIn(),
                source.maxRelationFanOut());
        Document document = replaceLimits(
                AnalysisAlleleLineageWireFixtures.validDocument(), limits);

        assertCode(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void rejectsBodyAboveConfiguredRecordCount() {
        Limits source = AnalysisAlleleLineageWireFixtures.header().limits();
        Limits limits = new Limits(
                source.maxSidecarBytes(), source.maxLineBytes(), 2,
                source.maxInputAlleleCount(), source.maxCandidateLinkCount(),
                source.maxAlleleBytes(), source.maxRelationFanIn(),
                source.maxRelationFanOut());
        Document document = replaceLimits(
                AnalysisAlleleLineageWireFixtures.validDocument(), limits);

        assertCode(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void rejectsAlleleRowsAboveConfiguredInputAlleleCount() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Limits source = valid.header().limits();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                valid.header(),
                new Limits(
                        source.maxSidecarBytes(), source.maxLineBytes(),
                        source.maxRecordCount(), 1, source.maxCandidateLinkCount(),
                        source.maxAlleleBytes(), source.maxRelationFanIn(),
                        source.maxRelationFanOut()));
        Document document = new Document(
                header, valid.inputRecords(),
                List.of(valid.alleleRecords().get(0), valid.alleleRecords().get(0)),
                valid.candidateLinks(), valid.footer());

        assertCode(ReasonCode.LINEAGE_ALLELE_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void rejectsCandidateRowsAboveConfiguredCandidateCount() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Limits source = valid.header().limits();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                valid.header(),
                new Limits(
                        source.maxSidecarBytes(), source.maxLineBytes(),
                        source.maxRecordCount(), source.maxInputAlleleCount(), 1,
                        source.maxAlleleBytes(), source.maxRelationFanIn(),
                        source.maxRelationFanOut()));
        Document document = new Document(
                header, valid.inputRecords(), valid.alleleRecords(),
                List.of(valid.candidateLinks().get(0), valid.candidateLinks().get(0)),
                valid.footer());

        assertCode(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void rejectsDocumentsAboveTheSyntheticInMemoryBodyCapBeforeTraversal() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Document document = new Document(
                valid.header(),
                Collections.nCopies(
                        AnalysisAlleleLineageWireValidator.MAX_IN_MEMORY_BODY_RECORDS + 1,
                        valid.inputRecords().get(0)),
                List.of(), List.of(), valid.footer());

        assertCode(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void acceptsExactlyTheSyntheticInMemoryBodyCap() {
        int limit = AnalysisAlleleLineageWireValidator.MAX_IN_MEMORY_BODY_RECORDS;
        List<AnalysisAlleleLineageWire.InputRecord> inputRecords = new ArrayList<>(limit);
        for (int ordinal = 1; ordinal <= limit; ordinal++) {
            inputRecords.add(AnalysisAlleleLineageWireFixtures.inputRecord(
                    ordinal, ordinal, 0));
        }
        HeaderRecord source = AnalysisAlleleLineageWireFixtures.header();
        Limits sourceLimits = source.limits();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                source,
                new Limits(
                        sourceLimits.maxSidecarBytes(), sourceLimits.maxLineBytes(), limit,
                        sourceLimits.maxInputAlleleCount(),
                        sourceLimits.maxCandidateLinkCount(), sourceLimits.maxAlleleBytes(),
                        sourceLimits.maxRelationFanIn(), sourceLimits.maxRelationFanOut()));
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                limit + 1L, limit, 0, 0, 0, 0, 0, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header, inputRecords, List.of(), List.of(), footer));

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void rejectsCanonicalAndEngineFanInAboveConfiguredBound() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("2", 200, "C", "T");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(key);
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, key, engineId);
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                4, 2, 1, 1, "89abcdef0123456701234567", key, engineId);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, engineId, key.canonicalVariantId());
        Limits source = valid.header().limits();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                valid.header(),
                new Limits(
                        source.maxSidecarBytes(), source.maxLineBytes(),
                        source.maxRecordCount(), source.maxInputAlleleCount(),
                        source.maxCandidateLinkCount(), source.maxAlleleBytes(), 1,
                        source.maxRelationFanOut()));
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                6, 2, 2, 0, 2, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(first, second), List.of(candidate), footer));

        assertCode(ReasonCode.LINEAGE_FAN_IN_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void rejectsLiftedFanInAboveConfiguredBoundBeforeNormalizationTerminalState() {
        HeaderRecord sourceHeader = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        Limits sourceLimits = sourceHeader.limits();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                sourceHeader,
                new Limits(
                        sourceLimits.maxSidecarBytes(), sourceLimits.maxLineBytes(),
                        sourceLimits.maxRecordCount(), sourceLimits.maxInputAlleleCount(),
                        sourceLimits.maxCandidateLinkCount(), sourceLimits.maxAlleleBytes(), 1,
                        sourceLimits.maxRelationFanOut()));
        AlleleKey sourceKey = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37,
                "1", 100, "A", "G");
        AlleleKey liftedKey = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,
                "1", 110, "A", "G");
        AlleleRecord firstBase = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                3, 1, AnalysisAlleleLineageWireFixtures.TOKEN, sourceKey, liftedKey,
                AnalysisAlleleLineageWireFixtures.engineId(liftedKey));
        AlleleRecord secondBase = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                4, 2, "89abcdef0123456701234567", sourceKey, liftedKey,
                AnalysisAlleleLineageWireFixtures.engineId(liftedKey));
        AlleleRecord first = normalizationRejected(firstBase);
        AlleleRecord second = normalizationRejected(secondBase);
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION, RecordType.FOOTER, 5,
                DocumentStatus.FAILED,
                List.of(ReasonCode.NORMALIZATION_REFERENCE_MISMATCH),
                2, 2, 2, 0, 0, 0, 0, 2,
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA, null, null,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM, null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(first, second), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_FAN_IN_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void countAndFanInLimitsAreInclusiveAtTheirConfiguredBoundary() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("2", 200, "C", "T");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(key);
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, key, engineId);
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                4, 2, 1, 1, "89abcdef0123456701234567", key, engineId);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, engineId, key.canonicalVariantId());
        Limits source = valid.header().limits();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                valid.header(),
                new Limits(
                        source.maxSidecarBytes(), source.maxLineBytes(), 5, 2, 1,
                        source.maxAlleleBytes(), 2, source.maxRelationFanOut()));
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                6, 2, 2, 0, 2, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(first, second), List.of(candidate), footer));

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void alleleBytesAreAcceptedAtTheConfiguredBoundary() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Limits source = valid.header().limits();
        Document document = replaceLimits(
                valid,
                new Limits(
                        source.maxSidecarBytes(), source.maxLineBytes(),
                        source.maxRecordCount(), source.maxInputAlleleCount(),
                        source.maxCandidateLinkCount(), 1, source.maxRelationFanIn(),
                        source.maxRelationFanOut()));

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void rejectsAlleleBytesAboveTheConfiguredBoundary() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "AA", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(key);
        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, key, engineId);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, engineId, key.canonicalVariantId());
        Limits source = valid.header().limits();
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(
                valid.header(),
                new Limits(
                        source.maxSidecarBytes(), source.maxLineBytes(),
                        source.maxRecordCount(), source.maxInputAlleleCount(),
                        source.maxCandidateLinkCount(), 1, source.maxRelationFanIn(),
                        source.maxRelationFanOut()));
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header, valid.inputRecords(), List.of(allele), List.of(candidate),
                valid.footer()));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID,
                () -> validator.validate(document));
    }

    private static Document replaceLimits(Document source, Limits limits) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.withLimits(source.header(), limits);
        return AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header, source.inputRecords(), source.alleleRecords(),
                source.candidateLinks(), source.footer()));
    }

    private static AlleleRecord normalizationRejected(AlleleRecord source) {
        return new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                NormalizationStatus.REJECTED,
                List.of(ReasonCode.NORMALIZATION_REFERENCE_MISMATCH),
                List.of(), RelationCardinality.ONE_TO_ZERO, List.of(),
                EngineMatchStatus.NOT_SUBMITTED,
                List.of(ReasonCode.ENGINE_NOT_SUBMITTED), List.of());
    }

    private static Limits hardCeilings() {
        return new Limits(
                AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES,
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                AnalysisAlleleLineageWire.MAX_BODY_RECORD_COUNT,
                AnalysisAlleleLineageWire.MAX_INPUT_ALLELE_COUNT,
                AnalysisAlleleLineageWire.MAX_CANDIDATE_LINK_COUNT,
                AnalysisAlleleLineageWire.MAX_ALLELE_BYTES,
                AnalysisAlleleLineageWire.MAX_RELATION_FAN_IN,
                AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT);
    }

    private static Stream<Limits> limitsAbovePublishedHardCeilings() {
        Limits hard = hardCeilings();
        return Stream.of(
                new Limits(hard.maxSidecarBytes() + 1, hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(),
                        hard.maxRelationFanIn(), hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes() + 1,
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(),
                        hard.maxRelationFanIn(), hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount() + 1, hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(),
                        hard.maxRelationFanIn(), hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount() + 1,
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(),
                        hard.maxRelationFanIn(), hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount() + 1, hard.maxAlleleBytes(),
                        hard.maxRelationFanIn(), hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes() + 1,
                        hard.maxRelationFanIn(), hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(),
                        hard.maxRelationFanIn() + 1, hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(),
                        hard.maxRelationFanIn(), hard.maxRelationFanOut() + 1));
    }

    private static Stream<Limits> limitsWithAZeroValue() {
        Limits hard = hardCeilings();
        return Stream.of(
                new Limits(0, hard.maxLineBytes(), hard.maxRecordCount(),
                        hard.maxInputAlleleCount(), hard.maxCandidateLinkCount(),
                        hard.maxAlleleBytes(), hard.maxRelationFanIn(),
                        hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), 0, hard.maxRecordCount(),
                        hard.maxInputAlleleCount(), hard.maxCandidateLinkCount(),
                        hard.maxAlleleBytes(), hard.maxRelationFanIn(),
                        hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(), 0,
                        hard.maxInputAlleleCount(), hard.maxCandidateLinkCount(),
                        hard.maxAlleleBytes(), hard.maxRelationFanIn(),
                        hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), 0, hard.maxCandidateLinkCount(),
                        hard.maxAlleleBytes(), hard.maxRelationFanIn(),
                        hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(), 0,
                        hard.maxAlleleBytes(), hard.maxRelationFanIn(),
                        hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), 0, hard.maxRelationFanIn(),
                        hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(), 0,
                        hard.maxRelationFanOut()),
                new Limits(hard.maxSidecarBytes(), hard.maxLineBytes(),
                        hard.maxRecordCount(), hard.maxInputAlleleCount(),
                        hard.maxCandidateLinkCount(), hard.maxAlleleBytes(),
                        hard.maxRelationFanIn(), 0));
    }

    private static void assertCode(ReasonCode expected, Runnable action) {
        LineageValidationException exception = assertThrows(
                LineageValidationException.class, action::run);
        assertEquals(expected, exception.getReasonCode());
    }
}
