package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.DocumentStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineAlleleRef;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineMatchStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationTransform;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RelationCardinality;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageStreamingValidatorDifferentialTest {

    private static final AnalysisAlleleLineageCanonicalProjector PROJECTOR =
            new AnalysisAlleleLineageCanonicalProjector();

    @Test
    void duplicateAltDeclarationMatchesTheInMemoryOracle(@TempDir Path parent) throws Exception {
        Document declared = duplicateAltDocument(true);
        AnalysisAlleleLineageStreamingValidator.ValidationSummary summary =
                assertAcceptedByBoth(declared, workspace(parent, "duplicate-declared"));
        assertEquals(DocumentStatus.COMPLETE, summary.status());

        assertRejectedByBoth(
                duplicateAltDocument(false),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "inputReasonCodes",
                workspace(parent, "duplicate-undeclared"));
    }

    @Test
    void parentCoverageFailuresKeepTheInMemoryOracleReasonAndField(@TempDir Path parent)
            throws Exception {
        assertRejectedByBoth(
                oneAlleleCoverageDocument(2, 1, 1),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "sourceAlternateIndex",
                workspace(parent, "parent-count-conflict"));

        assertRejectedByBoth(
                oneAlleleCoverageDocument(0, 1, 1),
                ReasonCode.LINEAGE_DANGLING_REFERENCE,
                "sourceRecordOrdinal",
                workspace(parent, "no-target-parent"));

        assertRejectedByBoth(
                oneAlleleCoverageDocument(2, 2, 2),
                ReasonCode.LINEAGE_COUNT_MISMATCH,
                "sourceAlternateCount",
                workspace(parent, "missing-first-index"));
    }

    @Test
    void duplicateCandidateKeepsTheInMemoryOracleCandidateIdField(@TempDir Path parent)
            throws Exception {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        CandidateLinkRecord first = valid.candidateLinks().get(0);
        CandidateLinkRecord duplicate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4,
                first.candidateSourceOrdinal(),
                first.engineAlleleId(),
                first.analysisCanonicalVariantId());
        Document document = sealed(
                valid.header(), valid.inputRecords(), valid.alleleRecords(),
                List.of(first, duplicate),
                AnalysisAlleleLineageWireFixtures.footer(5, 1, 1, 0, 1, 1, 2, 0, 0));

        assertRejectedByBoth(
                document,
                ReasonCode.LINEAGE_DUPLICATE_ID,
                "candidateId",
                workspace(parent, "duplicate-candidate-id"));
    }

    @Test
    void contradictorySourceOutcomeMatchesTheInMemoryOracle(@TempDir Path parent)
            throws Exception {
        assertRejectedByBoth(
                contradictorySourceOutcomeDocument(false),
                ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                "sourceKey",
                workspace(parent, "source-outcome"));
    }

    @Test
    void multiDefectStreamingPrecedenceIsStableAcrossSpoolLayouts(@TempDir Path parent)
            throws Exception {
        Document document = contradictorySourceOutcomeDocument(true);
        LineageValidationException oracle = assertThrows(
                LineageValidationException.class,
                () -> new AnalysisAlleleLineageWireValidator().validate(document));
        assertEquals(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, oracle.getReasonCode());
        assertEquals("sourceKey", oracle.getField());

        int[][] layouts = {{1, 2}, {3, 2}, {4, 4}, {256, 4}};
        for (int index = 0; index < layouts.length; index++) {
            Path workspace = workspace(parent, "multi-defect-layout-" + index);
            AnalysisAlleleLineageStreamingValidator layoutValidator =
                    validator(layouts[index][0], layouts[index][1]);
            LineageValidationException streaming = assertThrows(
                    LineageValidationException.class,
                    () -> layoutValidator.validate(
                            new ByteArrayInputStream(canonicalNdjson(document)),
                            workspace,
                            document.header()));
            assertEquals(ReasonCode.LINEAGE_DUPLICATE_ID, streaming.getReasonCode());
            assertEquals("sourceAlleleToken", streaming.getField());
            assertWorkspaceEmpty(workspace);
        }
    }

    @Test
    void liftedTargetSourceConflictMatchesTheInMemoryOracle(@TempDir Path parent)
            throws Exception {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey firstSource = AnalysisAlleleLineageWireFixtures.key(
                GenomeAssembly.GRCh37, "1", 100, "A", "G");
        AlleleKey secondSource = AnalysisAlleleLineageWireFixtures.key(
                GenomeAssembly.GRCh37, "1", 101, "A", "G");
        AlleleKey lifted = AnalysisAlleleLineageWireFixtures.key(
                GenomeAssembly.GRCh38, "1", 110, "A", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(lifted);
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                3, 1, token(1), firstSource, lifted, engineId);
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                4, 2, token(2), secondSource, lifted, engineId);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, engineId, lifted.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.withLiftedArtifact(
                AnalysisAlleleLineageWireFixtures.footer(6, 2, 2, 2, 2, 1, 1, 0, 0),
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA);
        Document document = sealed(
                header, inputs(2), List.of(first, second), List.of(candidate), footer);

        assertRejectedByBoth(
                document,
                ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                "liftedKeys",
                workspace(parent, "lifted-source-conflict"));
    }

    @Test
    void canonicalEngineOutcomeConflictMatchesTheInMemoryOracle(@TempDir Path parent)
            throws Exception {
        AlleleKey canonical = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord matched = normalizedAllele(
                3, 1, token(1),
                AnalysisAlleleLineageWireFixtures.key("1", 101, "A", "G"),
                canonical, EngineMatchStatus.MATCHED);
        AlleleRecord notFound = normalizedAllele(
                4, 2, token(2),
                AnalysisAlleleLineageWireFixtures.key("1", 102, "A", "G"),
                canonical, EngineMatchStatus.NOT_FOUND);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, AnalysisAlleleLineageWireFixtures.engineId(canonical),
                canonical.canonicalVariantId());
        Document document = sealed(
                AnalysisAlleleLineageWireFixtures.header(),
                inputs(2), List.of(matched, notFound), List.of(candidate),
                AnalysisAlleleLineageWireFixtures.footer(6, 2, 2, 0, 2, 1, 1, 0, 0));

        assertRejectedByBoth(
                document,
                ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                "engineMatchStatus",
                workspace(parent, "canonical-engine-conflict"));
    }

    @Test
    void danglingEngineAndMissingMatchedCandidateKeepStableFields(@TempDir Path parent)
            throws Exception {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey absent = AnalysisAlleleLineageWireFixtures.key("1", 999, "C", "T");
        CandidateLinkRecord dangling = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, AnalysisAlleleLineageWireFixtures.engineId(absent),
                absent.canonicalVariantId());
        Document danglingDocument = sealed(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(dangling),
                valid.footer());
        assertRejectedByBoth(
                danglingDocument,
                ReasonCode.LINEAGE_DANGLING_REFERENCE,
                "engineAlleleId",
                workspace(parent, "dangling-engine"));

        AlleleKey wrongCanonical = AnalysisAlleleLineageWireFixtures.key(
                "1", 998, "C", "T");
        CandidateLinkRecord canonicalMismatch =
                AnalysisAlleleLineageWireFixtures.linkedCandidate(
                        3,
                        1,
                        valid.alleleRecords().get(0).engineAlleles().get(0).engineAlleleId(),
                        wrongCanonical.canonicalVariantId());
        Document canonicalMismatchDocument = sealed(
                valid.header(), valid.inputRecords(), valid.alleleRecords(),
                List.of(canonicalMismatch), valid.footer());
        assertRejectedByBoth(
                canonicalMismatchDocument,
                ReasonCode.LINEAGE_DANGLING_REFERENCE,
                "analysisCanonicalVariantId",
                workspace(parent, "candidate-canonical-mismatch"));

        Document missingCandidate = sealed(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(),
                AnalysisAlleleLineageWireFixtures.footer(3, 1, 1, 0, 1, 1, 0, 0, 0));
        assertRejectedByBoth(
                missingCandidate,
                ReasonCode.LINEAGE_DANGLING_REFERENCE,
                "candidateLinks",
                workspace(parent, "missing-candidate"));

        AnalysisAlleleLineageStreamingValidator.ValidationSummary summary =
                assertAcceptedByBoth(valid, workspace(parent, "matched-candidate"));
        assertEquals(1L, summary.engineAlleleCount());
        assertEquals(1L, summary.candidateLinkCount());
    }

    @Test
    void malformedLinkedCandidateCanonicalIdKeepsTheOracleDanglingField(@TempDir Path parent)
            throws Exception {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        CandidateLinkRecord source = valid.candidateLinks().get(0);
        List<String> malformedCanonicalIds = List.of(
                "not-a-canonical-id",
                "GRCh99|1|100|A|G",
                "GRCh38|1|2147483648|A|G",
                "GRCh38|1|0|A|G",
                "GRCh38|1|0100|A|G",
                "GRCh38|1|100|A|",
                "GRCh38|1|100|A|A",
                "GRCh38|1|100|A|病例");
        for (int index = 0; index < malformedCanonicalIds.size(); index++) {
            CandidateLinkRecord malformed = new CandidateLinkRecord(
                    source.schemaVersion(),
                    source.recordType(),
                    source.recordOrdinal(),
                    source.candidateId(),
                    source.candidateSource(),
                    source.candidateSourceOrdinal(),
                    source.engineAlleleId(),
                    malformedCanonicalIds.get(index),
                    source.linkStatus(),
                    source.reasonCodes());
            Document document = sealed(
                    valid.header(), valid.inputRecords(), valid.alleleRecords(),
                    List.of(malformed), valid.footer());
            assertRejectedByBoth(
                    document,
                    ReasonCode.LINEAGE_DANGLING_REFERENCE,
                    "analysisCanonicalVariantId",
                    workspace(parent, "malformed-candidate-canonical-" + index));
        }

        CandidateLinkRecord malformedReference = new CandidateLinkRecord(
                source.schemaVersion(),
                source.recordType(),
                source.recordOrdinal(),
                source.candidateId(),
                source.candidateSource(),
                source.candidateSourceOrdinal(),
                "engine-allele-v1-" + "f".repeat(24),
                "GRCh38|1|100|A|病例",
                source.linkStatus(),
                source.reasonCodes());
        Document malformedReferenceDocument = sealed(
                valid.header(), valid.inputRecords(), valid.alleleRecords(),
                List.of(malformedReference), valid.footer());
        assertRejectedByBoth(
                malformedReferenceDocument,
                ReasonCode.LINEAGE_DANGLING_REFERENCE,
                "engineAlleleId",
                workspace(parent, "malformed-engine-and-canonical"));
    }

    @Test
    void relationFanInAcceptsNAndRejectsNPlusOneLikeTheOracle(@TempDir Path parent)
            throws Exception {
        AnalysisAlleleLineageStreamingValidator.ValidationSummary boundary =
                assertAcceptedByBoth(
                        fanInDocument(2, 2), workspace(parent, "fan-in-n"));
        assertEquals(1L, boundary.engineAlleleCount());

        assertRejectedByBoth(
                fanInDocument(3, 2),
                ReasonCode.LINEAGE_FAN_IN_LIMIT_EXCEEDED,
                "canonicalKeys",
                workspace(parent, "fan-in-n-plus-one"));
    }

    @Test
    void candidateFanOutAcceptsNAndRejectsNPlusOneLikeTheOracle(@TempDir Path parent)
            throws Exception {
        AnalysisAlleleLineageStreamingValidator.ValidationSummary boundary =
                assertAcceptedByBoth(
                        fanOutDocument(2, 2), workspace(parent, "fan-out-n"));
        assertEquals(2L, boundary.candidateLinkCount());

        assertRejectedByBoth(
                fanOutDocument(3, 2),
                ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED,
                "candidateLinks",
                workspace(parent, "fan-out-n-plus-one"));
    }

    @Test
    void partialFailedAndNotFoundFooterSemanticsMatchTheOracle(@TempDir Path parent)
            throws Exception {
        AnalysisAlleleLineageStreamingValidator.ValidationSummary partial =
                assertAcceptedByBoth(
                        partialLiftoverDocument(), workspace(parent, "partial"));
        assertEquals(DocumentStatus.PARTIAL, partial.status());
        assertEquals(
                List.of(ReasonCode.LIFTOVER_NO_TARGET, ReasonCode.LIFTOVER_PARTIAL),
                partial.reasonCodes());

        AnalysisAlleleLineageStreamingValidator.ValidationSummary failed =
                assertAcceptedByBoth(
                        failedAmbiguousEngineDocument(), workspace(parent, "failed"));
        assertEquals(DocumentStatus.FAILED, failed.status());
        assertEquals(List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS), failed.reasonCodes());

        AnalysisAlleleLineageStreamingValidator.ValidationSummary notFound =
                assertAcceptedByBoth(
                        completeNotFoundDocument(), workspace(parent, "not-found"));
        assertEquals(DocumentStatus.COMPLETE, notFound.status());
        assertEquals(List.of(), notFound.reasonCodes());
        assertEquals(0L, notFound.engineAlleleCount());
        assertEquals(0L, notFound.candidateLinkCount());
    }

    @Test
    void footerStatusAndReasonMismatchesKeepOracleDiagnostics(@TempDir Path parent)
            throws Exception {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        assertRejectedByBoth(
                withFooterState(
                        valid, DocumentStatus.COMPLETE,
                        List.of(ReasonCode.ENGINE_ALLELE_NOT_FOUND),
                        valid.footer().liftedArtifactSha256(),
                        valid.footer().canonicalArtifactSha256(),
                        valid.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "status",
                workspace(parent, "complete-extra-reason"));
        assertRejectedByBoth(
                withFooterState(
                        valid, DocumentStatus.PARTIAL, List.of(),
                        valid.footer().liftedArtifactSha256(),
                        valid.footer().canonicalArtifactSha256(),
                        valid.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "status",
                workspace(parent, "partial-without-failure"));

        Document partial = partialLiftoverDocument();
        assertRejectedByBoth(
                withFooterState(
                        partial, DocumentStatus.PARTIAL,
                        List.of(ReasonCode.LIFTOVER_NO_TARGET),
                        partial.footer().liftedArtifactSha256(),
                        partial.footer().canonicalArtifactSha256(),
                        partial.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "status",
                workspace(parent, "partial-incomplete-reasons"));
        assertRejectedByBoth(
                withFooterState(
                        valid, DocumentStatus.FAILED, List.of(),
                        valid.footer().liftedArtifactSha256(),
                        valid.footer().canonicalArtifactSha256(),
                        valid.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "status",
                workspace(parent, "failed-with-success"));

        Document failed = failedAmbiguousEngineDocument();
        assertRejectedByBoth(
                withFooterState(
                        failed, DocumentStatus.FAILED,
                        List.of(ReasonCode.ENGINE_ALLELE_CONFLICT),
                        failed.footer().liftedArtifactSha256(),
                        failed.footer().canonicalArtifactSha256(),
                        failed.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "status",
                workspace(parent, "failed-wrong-reason"));
        assertRejectedByBoth(
                withFooterState(
                        valid, DocumentStatus.LIMIT_EXCEEDED, List.of(),
                        valid.footer().liftedArtifactSha256(),
                        valid.footer().canonicalArtifactSha256(),
                        valid.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_REUSE_STATUS_INELIGIBLE,
                "status",
                workspace(parent, "sealed-limit-exceeded"));
    }

    @Test
    void liftoverArtifactRequirementAndProhibitionMatchTheOracle(@TempDir Path parent)
            throws Exception {
        Document partial = partialLiftoverDocument();
        assertRejectedByBoth(
                withFooterState(
                        partial, partial.footer().status(), partial.footer().reasonCodes(),
                        null,
                        partial.footer().canonicalArtifactSha256(),
                        partial.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "liftedArtifactSha256",
                workspace(parent, "missing-liftover-artifact"));

        Document unavailable = failedUnavailableLiftoverDocument(null, null, null);
        assertAcceptedByBoth(unavailable, workspace(parent, "unavailable-without-artifact"));
        assertRejectedByBoth(
                failedUnavailableLiftoverDocument(
                        AnalysisAlleleLineageWireFixtures.LIFTED_SHA, null, null),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "liftedArtifactSha256",
                workspace(parent, "unavailable-phantom-artifact"));
    }

    @Test
    void failedCanonicalArtifactSemanticsMatchTheOracle(@TempDir Path parent)
            throws Exception {
        assertAcceptedByBoth(
                failedUnavailableLiftoverDocument(
                        null, AnalysisAlleleLineageWireFixtures.CANONICAL_SHA, null),
                workspace(parent, "failed-optional-canonical"));
        assertRejectedByBoth(
                failedUnavailableLiftoverDocument(null, "not-a-sha", null),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "canonicalArtifactSha256",
                workspace(parent, "failed-malformed-optional-canonical"));

        Document failedWithCanonical = failedAmbiguousEngineDocument();
        assertRejectedByBoth(
                withFooterState(
                        failedWithCanonical,
                        failedWithCanonical.footer().status(),
                        failedWithCanonical.footer().reasonCodes(),
                        failedWithCanonical.footer().liftedArtifactSha256(),
                        null,
                        failedWithCanonical.footer().engineArtifactSha256()),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "canonicalArtifactSha256",
                workspace(parent, "failed-required-canonical"));
    }

    @Test
    void engineArtifactBindingBranchesMatchTheOracle(@TempDir Path parent)
            throws Exception {
        Document failedWithSubmission = failedAmbiguousEngineDocument();
        assertRejectedByBoth(
                withFooterState(
                        failedWithSubmission,
                        failedWithSubmission.footer().status(),
                        failedWithSubmission.footer().reasonCodes(),
                        failedWithSubmission.footer().liftedArtifactSha256(),
                        failedWithSubmission.footer().canonicalArtifactSha256(),
                        null),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "engineArtifactSha256",
                workspace(parent, "submitted-engine-artifact-missing"));

        assertAcceptedByBoth(
                candidateOnlyFailureDocument(AnalysisAlleleLineageWireFixtures.ENGINE_SHA),
                workspace(parent, "candidate-only-engine-artifact"));
        assertRejectedByBoth(
                candidateOnlyFailureDocument(null),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "engineArtifactSha256",
                workspace(parent, "candidate-only-engine-artifact-missing"));

        assertAcceptedByBoth(
                failedUnavailableLiftoverDocument(
                        null, null, AnalysisAlleleLineageWireFixtures.ENGINE_SHA),
                workspace(parent, "failed-optional-engine"));
        assertRejectedByBoth(
                failedUnavailableLiftoverDocument(null, null, "not-a-sha"),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "engineArtifactSha256",
                workspace(parent, "failed-malformed-optional-engine"));

        assertRejectedByBoth(
                emptyCompleteDocument(null),
                ReasonCode.LINEAGE_FIELD_INVALID,
                "engineArtifactSha256",
                workspace(parent, "complete-engine-artifact-missing"));
    }

    private static Document duplicateAltDocument(boolean fullyDeclared) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.header();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(key);
        AlleleRecord first = withInputReasons(
                AnalysisAlleleLineageWireFixtures.matchedAllele(
                        2, 1, 1, 2, token(1), key, engineId),
                List.of(ReasonCode.INPUT_DUPLICATE_ALT));
        AlleleRecord second = withInputReasons(
                AnalysisAlleleLineageWireFixtures.matchedAllele(
                        3, 1, 2, 2, token(2), key, engineId),
                fullyDeclared ? List.of(ReasonCode.INPUT_DUPLICATE_ALT) : List.of());
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4, 1, engineId, key.canonicalVariantId());
        return sealed(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 2)),
                List.of(first, second), List.of(candidate),
                AnalysisAlleleLineageWireFixtures.footer(5, 1, 2, 0, 2, 1, 1, 0, 0));
    }

    private static Document contradictorySourceOutcomeDocument(boolean duplicateToken) {
        AlleleKey sourceKey = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord matched = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, token(1), sourceKey,
                AnalysisAlleleLineageWireFixtures.engineId(sourceKey));
        AlleleRecord rejected = normalizationRejectedAllele(
                4, 2, duplicateToken ? token(1) : token(2), sourceKey);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, AnalysisAlleleLineageWireFixtures.engineId(sourceKey),
                sourceKey.canonicalVariantId());
        FooterRecord footer = statusFooter(
                6, DocumentStatus.PARTIAL,
                List.of(ReasonCode.NORMALIZATION_REFERENCE_MISMATCH),
                2, 2, 0, 1, 1, 1, 0, 1,
                null, AnalysisAlleleLineageWireFixtures.CANONICAL_SHA,
                AnalysisAlleleLineageWireFixtures.ENGINE_SHA);
        return sealed(
                AnalysisAlleleLineageWireFixtures.header(),
                inputs(2), List.of(matched, rejected), List.of(candidate), footer);
    }

    private static Document oneAlleleCoverageDocument(int parentAlternateCount,
                                                       int alleleAlternateIndex,
                                                       int alleleAlternateCount) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.header();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(key);
        InputRecord input = AnalysisAlleleLineageWireFixtures.inputRecord(
                1, 1, parentAlternateCount);
        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, alleleAlternateIndex, alleleAlternateCount, token(1), key, engineId);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, engineId, key.canonicalVariantId());
        return sealed(
                header, List.of(input), List.of(allele), List.of(candidate),
                AnalysisAlleleLineageWireFixtures.footer(4, 1, 1, 0, 1, 1, 1, 0, 0));
    }

    private static Document fanInDocument(int sourceCount, int maxFanIn) {
        HeaderRecord sourceHeader = AnalysisAlleleLineageWireFixtures.header();
        HeaderRecord header = withRelationLimits(sourceHeader, maxFanIn,
                sourceHeader.limits().maxRelationFanOut());
        AlleleKey canonical = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(canonical);
        List<AlleleRecord> alleles = new ArrayList<>();
        for (int index = 1; index <= sourceCount; index++) {
            alleles.add(normalizedAllele(
                    sourceCount + index,
                    index,
                    token(index),
                    AnalysisAlleleLineageWireFixtures.key("1", 100 + index, "A", "G"),
                    canonical,
                    EngineMatchStatus.MATCHED));
        }
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                2L * sourceCount + 1, 1, engineId, canonical.canonicalVariantId());
        return sealed(
                header, inputs(sourceCount), alleles, List.of(candidate),
                AnalysisAlleleLineageWireFixtures.footer(
                        2L * sourceCount + 2, sourceCount, sourceCount, 0,
                        sourceCount, 1, 1, 0, 0));
    }

    private static Document fanOutDocument(int candidateCount, int maxFanOut) {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        HeaderRecord header = withRelationLimits(
                valid.header(), valid.header().limits().maxRelationFanIn(), maxFanOut);
        AlleleRecord allele = valid.alleleRecords().get(0);
        List<CandidateLinkRecord> candidates = new ArrayList<>();
        for (int index = 1; index <= candidateCount; index++) {
            candidates.add(AnalysisAlleleLineageWireFixtures.linkedCandidate(
                    2L + index,
                    index,
                    allele.engineAlleles().get(0).engineAlleleId(),
                    allele.canonicalKeys().get(0).canonicalVariantId()));
        }
        return sealed(
                header, valid.inputRecords(), valid.alleleRecords(), candidates,
                AnalysisAlleleLineageWireFixtures.footer(
                        3L + candidateCount, 1, 1, 0, 1, 1, candidateCount, 0, 0));
    }

    private static Document partialLiftoverDocument() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey successfulSource = AnalysisAlleleLineageWireFixtures.key(
                GenomeAssembly.GRCh37, "1", 100, "A", "G");
        AlleleKey failedSource = AnalysisAlleleLineageWireFixtures.key(
                GenomeAssembly.GRCh37, "1", 101, "C", "T");
        AlleleKey lifted = AnalysisAlleleLineageWireFixtures.key(
                GenomeAssembly.GRCh38, "1", 110, "A", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(lifted);
        AlleleRecord success = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                3, 1, token(1), successfulSource, lifted, engineId);
        AlleleRecord failed = unmappedAllele(4, 2, token(2), failedSource);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, engineId, lifted.canonicalVariantId());
        FooterRecord footer = statusFooter(
                6, DocumentStatus.PARTIAL,
                List.of(ReasonCode.LIFTOVER_NO_TARGET, ReasonCode.LIFTOVER_PARTIAL),
                2, 2, 1, 1, 1, 1, 0, 1,
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA,
                AnalysisAlleleLineageWireFixtures.CANONICAL_SHA,
                AnalysisAlleleLineageWireFixtures.ENGINE_SHA);
        return sealed(header, inputs(2), List.of(success, failed), List.of(candidate), footer);
    }

    private static Document failedAmbiguousEngineDocument() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.header();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord ambiguous = identityAllele(
                2, 1, token(1), key, EngineMatchStatus.AMBIGUOUS);
        FooterRecord footer = statusFooter(
                3, DocumentStatus.FAILED,
                List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS),
                1, 1, 0, 1, 0, 0, 1, 0,
                null, AnalysisAlleleLineageWireFixtures.CANONICAL_SHA,
                AnalysisAlleleLineageWireFixtures.ENGINE_SHA);
        return sealed(header, inputs(1), List.of(ambiguous), List.of(), footer);
    }

    private static Document failedUnavailableLiftoverDocument(String liftedArtifact,
                                                              String canonicalArtifact,
                                                              String engineArtifact) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey source = AnalysisAlleleLineageWireFixtures.key(
                GenomeAssembly.GRCh37, "1", 100, "A", "G");
        AlleleRecord unavailable = new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                2,
                token(1),
                1,
                1,
                1,
                InputStatus.ACCEPTED,
                List.of(),
                source,
                LiftoverStatus.UNAVAILABLE,
                List.of(ReasonCode.LIFTOVER_UNAVAILABLE),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                null,
                NormalizationStatus.NOT_SUBMITTED,
                List.of(),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                EngineMatchStatus.NOT_SUBMITTED,
                List.of(ReasonCode.ENGINE_NOT_SUBMITTED),
                List.of());
        FooterRecord footer = statusFooter(
                3, DocumentStatus.FAILED,
                List.of(ReasonCode.LIFTOVER_UNAVAILABLE),
                1, 1, 0, 0, 0, 0, 0, 1,
                liftedArtifact, canonicalArtifact, engineArtifact);
        return sealed(header, inputs(1), List.of(unavailable), List.of(), footer);
    }

    private static Document candidateOnlyFailureDocument(String engineArtifact) {
        CandidateLinkRecord candidate = new CandidateLinkRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.CANDIDATE_LINK,
                1,
                "exomiser-v1-00000001",
                CandidateSource.EXOMISER,
                1,
                null,
                null,
                CandidateLinkStatus.UNMATCHED,
                List.of(ReasonCode.CANDIDATE_ALLELE_UNMATCHED));
        FooterRecord footer = statusFooter(
                2, DocumentStatus.FAILED,
                List.of(ReasonCode.CANDIDATE_ALLELE_UNMATCHED),
                0, 0, 0, 0, 0, 1, 0, 0,
                null, null, engineArtifact);
        return sealed(
                AnalysisAlleleLineageWireFixtures.header(),
                List.of(), List.of(), List.of(candidate), footer);
    }

    private static Document emptyCompleteDocument(String engineArtifact) {
        FooterRecord footer = statusFooter(
                1, DocumentStatus.COMPLETE, List.of(),
                0, 0, 0, 0, 0, 0, 0, 0,
                null, AnalysisAlleleLineageWireFixtures.CANONICAL_SHA, engineArtifact);
        return sealed(
                AnalysisAlleleLineageWireFixtures.header(),
                List.of(), List.of(), List.of(), footer);
    }

    private static Document completeNotFoundDocument() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.header();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord notFound = identityAllele(
                2, 1, token(1), key, EngineMatchStatus.NOT_FOUND);
        return sealed(
                header, inputs(1), List.of(notFound), List.of(),
                AnalysisAlleleLineageWireFixtures.footer(3, 1, 1, 0, 1, 0, 0, 0, 0));
    }

    private static AlleleRecord identityAllele(long recordOrdinal,
                                               long sourceOrdinal,
                                               String token,
                                               AlleleKey key,
                                               EngineMatchStatus engineStatus) {
        return outcomeAllele(
                recordOrdinal, sourceOrdinal, token, key,
                NormalizationStatus.IDENTITY, key,
                List.of(NormalizationTransform.IDENTITY), engineStatus);
    }

    private static AlleleRecord normalizedAllele(long recordOrdinal,
                                                 long sourceOrdinal,
                                                 String token,
                                                 AlleleKey source,
                                                 AlleleKey canonical,
                                                 EngineMatchStatus engineStatus) {
        return outcomeAllele(
                recordOrdinal, sourceOrdinal, token, source,
                NormalizationStatus.NORMALIZED, canonical,
                List.of(NormalizationTransform.LEFT_ALIGNED), engineStatus);
    }

    private static AlleleRecord outcomeAllele(long recordOrdinal,
                                              long sourceOrdinal,
                                              String token,
                                              AlleleKey source,
                                              NormalizationStatus normalizationStatus,
                                              AlleleKey canonical,
                                              List<NormalizationTransform> transforms,
                                              EngineMatchStatus engineStatus) {
        List<ReasonCode> engineReasons = switch (engineStatus) {
            case MATCHED -> List.of();
            case NOT_FOUND -> List.of(ReasonCode.ENGINE_ALLELE_NOT_FOUND);
            case AMBIGUOUS -> List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS);
            case CONFLICT -> List.of(ReasonCode.ENGINE_ALLELE_CONFLICT);
            case NOT_SUBMITTED -> List.of(ReasonCode.ENGINE_NOT_SUBMITTED);
        };
        List<EngineAlleleRef> engineAlleles = engineStatus == EngineMatchStatus.MATCHED
                ? List.of(new EngineAlleleRef(
                AnalysisAlleleLineageWireFixtures.engineId(canonical),
                canonical.canonicalVariantId()))
                : List.of();
        return new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                recordOrdinal,
                token,
                sourceOrdinal,
                1,
                1,
                InputStatus.ACCEPTED,
                List.of(),
                source,
                LiftoverStatus.NOT_REQUIRED,
                List.of(ReasonCode.LIFTOVER_NOT_REQUIRED),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                null,
                normalizationStatus,
                List.of(),
                List.of(canonical),
                RelationCardinality.ONE_TO_ONE,
                transforms,
                engineStatus,
                engineReasons,
                engineAlleles);
    }

    private static AlleleRecord normalizationRejectedAllele(long recordOrdinal,
                                                             long sourceOrdinal,
                                                             String token,
                                                             AlleleKey source) {
        return new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                recordOrdinal,
                token,
                sourceOrdinal,
                1,
                1,
                InputStatus.ACCEPTED,
                List.of(),
                source,
                LiftoverStatus.NOT_REQUIRED,
                List.of(ReasonCode.LIFTOVER_NOT_REQUIRED),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                null,
                NormalizationStatus.REJECTED,
                List.of(ReasonCode.NORMALIZATION_REFERENCE_MISMATCH),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                EngineMatchStatus.NOT_SUBMITTED,
                List.of(ReasonCode.ENGINE_NOT_SUBMITTED),
                List.of());
    }

    private static AlleleRecord unmappedAllele(long recordOrdinal,
                                               long sourceOrdinal,
                                               String token,
                                               AlleleKey source) {
        return new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                recordOrdinal,
                token,
                sourceOrdinal,
                1,
                1,
                InputStatus.ACCEPTED,
                List.of(),
                source,
                LiftoverStatus.UNMAPPED,
                List.of(ReasonCode.LIFTOVER_NO_TARGET),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                null,
                NormalizationStatus.NOT_SUBMITTED,
                List.of(),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                EngineMatchStatus.NOT_SUBMITTED,
                List.of(ReasonCode.ENGINE_NOT_SUBMITTED),
                List.of());
    }

    private static AlleleRecord withInputReasons(AlleleRecord source,
                                                 List<ReasonCode> reasons) {
        return new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                reasons, source.sourceKey(), source.liftoverStatus(), source.liftoverReasonCodes(),
                source.liftedKeys(), source.liftoverCardinality(), source.liftoverTransforms(),
                source.targetReferenceValidated(), source.normalizationStatus(),
                source.normalizationReasonCodes(), source.canonicalKeys(),
                source.normalizationCardinality(), source.normalizationTransforms(),
                source.engineMatchStatus(), source.engineReasonCodes(), source.engineAlleles());
    }

    private static HeaderRecord withRelationLimits(HeaderRecord source,
                                                   int maxFanIn,
                                                   int maxFanOut) {
        Limits limits = source.limits();
        return AnalysisAlleleLineageWireFixtures.withLimits(source, new Limits(
                limits.maxSidecarBytes(),
                limits.maxLineBytes(),
                limits.maxRecordCount(),
                limits.maxInputAlleleCount(),
                limits.maxCandidateLinkCount(),
                limits.maxAlleleBytes(),
                maxFanIn,
                maxFanOut));
    }

    private static FooterRecord statusFooter(long recordOrdinal,
                                             DocumentStatus status,
                                             List<ReasonCode> reasons,
                                             long inputRecordCount,
                                             long inputAlleleCount,
                                             long liftedAlleleCount,
                                             long canonicalAlleleCount,
                                             long engineAlleleCount,
                                             long candidateLinkCount,
                                             long unresolvedAlleleCount,
                                             long rejectedAlleleCount,
                                             String liftedArtifact,
                                             String canonicalArtifact,
                                             String engineArtifact) {
        return new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.FOOTER,
                recordOrdinal,
                status,
                reasons,
                inputRecordCount,
                inputAlleleCount,
                liftedAlleleCount,
                canonicalAlleleCount,
                engineAlleleCount,
                candidateLinkCount,
                unresolvedAlleleCount,
                rejectedAlleleCount,
                liftedArtifact,
                canonicalArtifact,
                engineArtifact,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                null);
    }

    private static Document withFooterState(Document source,
                                            DocumentStatus status,
                                            List<ReasonCode> reasons,
                                            String liftedArtifact,
                                            String canonicalArtifact,
                                            String engineArtifact) {
        FooterRecord current = source.footer();
        FooterRecord replacement = statusFooter(
                current.recordOrdinal(),
                status,
                reasons,
                current.inputRecordCount(),
                current.inputAlleleCount(),
                current.liftedAlleleCount(),
                current.canonicalAlleleCount(),
                current.engineAlleleCount(),
                current.candidateLinkCount(),
                current.unresolvedAlleleCount(),
                current.rejectedAlleleCount(),
                liftedArtifact,
                canonicalArtifact,
                engineArtifact);
        return sealed(
                source.header(), source.inputRecords(), source.alleleRecords(),
                source.candidateLinks(), replacement);
    }

    private static List<InputRecord> inputs(int count) {
        List<InputRecord> inputs = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            inputs.add(AnalysisAlleleLineageWireFixtures.inputRecord(index, index, 1));
        }
        return inputs;
    }

    private static Document sealed(HeaderRecord header,
                                   List<InputRecord> inputs,
                                   List<AlleleRecord> alleles,
                                   List<CandidateLinkRecord> candidates,
                                   FooterRecord footer) {
        return AnalysisAlleleLineageWireFixtures.reseal(
                new Document(header, inputs, alleles, candidates, footer));
    }

    private static AnalysisAlleleLineageStreamingValidator.ValidationSummary assertAcceptedByBoth(
            Document document,
            Path workspace) throws Exception {
        assertDoesNotThrow(() -> new AnalysisAlleleLineageWireValidator().validate(document));
        AnalysisAlleleLineageStreamingValidator.ValidationSummary summary = validator().validate(
                new ByteArrayInputStream(canonicalNdjson(document)), workspace, document.header());
        assertWorkspaceEmpty(workspace);
        return summary;
    }

    private static void assertRejectedByBoth(Document document,
                                             ReasonCode expectedReason,
                                             String expectedField,
                                             Path workspace) throws Exception {
        LineageValidationException oracle = assertThrows(
                LineageValidationException.class,
                () -> new AnalysisAlleleLineageWireValidator().validate(document));
        assertEquals(expectedReason, oracle.getReasonCode());
        assertEquals(expectedField, oracle.getField());

        LineageValidationException streaming = assertThrows(
                LineageValidationException.class,
                () -> validator().validate(
                        new ByteArrayInputStream(canonicalNdjson(document)),
                        workspace,
                        document.header()));
        assertEquals(oracle.getReasonCode(), streaming.getReasonCode());
        assertEquals(oracle.getField(), streaming.getField());
        assertWorkspaceEmpty(workspace);
    }

    private static AnalysisAlleleLineageStreamingValidator validator() {
        return validator(256, 4);
    }

    private static AnalysisAlleleLineageStreamingValidator validator(int maxBufferedRecords,
                                                                     int mergeFanIn) {
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                10_000,
                1_048_576,
                maxBufferedRecords,
                mergeFanIn,
                67_108_864,
                8,
                1_024);
        return new AnalysisAlleleLineageStreamingValidator(
                (workspace, supplied) -> new BoundedExternalSortSpool(
                        workspace,
                        supplied,
                        new SecureRandom(),
                        new PortableIdentitySource(),
                        ignored -> BoundedExternalSortSpool.PrivacyStatus.PRIVATE,
                        BoundedExternalSortSpool.FaultHook.NOOP,
                        BoundedExternalSortSpool.Observer.NOOP),
                limits);
    }

    private static byte[] canonicalNdjson(Document document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (WireRecord record : document.recordsInCanonicalSections()) {
            output.write(PROJECTOR.canonicalRecordBytes(record));
            output.write('\n');
        }
        return output.toByteArray();
    }

    private static String token(int value) {
        return String.format("%024x", value);
    }

    private static Path workspace(Path parent, String name) throws IOException {
        return Files.createDirectory(parent.resolve(name));
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
                    : new TestIdentity(
                    null,
                    path.toAbsolutePath().normalize(),
                    attributes.creationTime(),
                    attributes.isDirectory());
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
