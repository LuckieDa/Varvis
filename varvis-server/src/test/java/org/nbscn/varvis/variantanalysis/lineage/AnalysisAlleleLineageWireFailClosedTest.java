package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.DocumentStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineMatchStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationTransform;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RelationCardinality;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageWireFailClosedTest {

    private final AnalysisAlleleLineageWireValidator validator =
            new AnalysisAlleleLineageWireValidator();

    @Test
    void successfulOneToManyNormalizationIsNotPublishableWithoutPerTargetEngineOutcomes() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord source = valid.alleleRecords().get(0);
        AlleleKey second = AnalysisAlleleLineageWireFixtures.key("1", 101, "C", "T");
        AlleleRecord oneToMany = new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                NormalizationStatus.NORMALIZED, List.of(),
                List.of(source.canonicalKeys().get(0), second), RelationCardinality.ONE_TO_MANY,
                List.of(NormalizationTransform.TRIMMED, NormalizationTransform.SPLIT),
                source.engineMatchStatus(), source.engineReasonCodes(), source.engineAlleles());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.copyFooter(
                valid.footer(), 1, 1, 0, 2, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), List.of(oneToMany), valid.candidateLinks(), footer));

        assertCode(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, () -> validator.validate(document));
    }

    @Test
    void normalizedStatusCannotClaimANoOpWithANonIdentityTransform() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord source = valid.alleleRecords().get(0);
        AlleleRecord falseTransform = new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                NormalizationStatus.NORMALIZED, List.of(), source.canonicalKeys(),
                source.normalizationCardinality(), List.of(NormalizationTransform.TRIMMED),
                source.engineMatchStatus(), source.engineReasonCodes(), source.engineAlleles());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), List.of(falseTransform),
                valid.candidateLinks(), valid.footer()));

        assertCode(ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                () -> validator.validate(document));
    }

    @Test
    void ambiguousEngineOutcomeCannotRetainAGuessedEngineTarget() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord source = valid.alleleRecords().get(0);
        AlleleRecord ambiguous = new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                source.normalizationStatus(), source.normalizationReasonCodes(), source.canonicalKeys(),
                source.normalizationCardinality(), source.normalizationTransforms(),
                EngineMatchStatus.AMBIGUOUS, List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS),
                source.engineAlleles());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), List.of(ambiguous), valid.candidateLinks(),
                valid.footer()));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @Test
    void canonicalArtifactFingerprintIsRequiredWhenCanonicalRowsExist() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord source = valid.alleleRecords().get(0);
        AlleleRecord ambiguous = new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                source.normalizationStatus(), source.normalizationReasonCodes(),
                source.canonicalKeys(), source.normalizationCardinality(),
                source.normalizationTransforms(), EngineMatchStatus.AMBIGUOUS,
                List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS), List.of());
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION, RecordType.FOOTER, 3,
                DocumentStatus.FAILED, List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS),
                1, 1, 0, 1, 0, 0, 1, 0,
                null, null, AnalysisAlleleLineageWireFixtures.ENGINE_SHA,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM, null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), List.of(ambiguous), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID,
                () -> validator.validate(document));
    }

    @Test
    void engineArtifactFingerprintIsRequiredWhenEngineWasSubmitted() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord source = valid.alleleRecords().get(0);
        AlleleRecord ambiguous = new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                source.normalizationStatus(), source.normalizationReasonCodes(),
                source.canonicalKeys(), source.normalizationCardinality(),
                source.normalizationTransforms(), EngineMatchStatus.AMBIGUOUS,
                List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS), List.of());
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION, RecordType.FOOTER, 3,
                DocumentStatus.FAILED, List.of(ReasonCode.ENGINE_ALLELE_AMBIGUOUS),
                1, 1, 0, 1, 0, 0, 1, 0,
                null, AnalysisAlleleLineageWireFixtures.CANONICAL_SHA, null,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM, null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), List.of(ambiguous), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID,
                () -> validator.validate(document));
    }

    @Test
    void engineArtifactFingerprintIsRequiredForCandidateOnlyFailure() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.header();
        CandidateLinkRecord unmatched = new CandidateLinkRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION, RecordType.CANDIDATE_LINK, 1,
                "exomiser-v1-00000001", CandidateSource.EXOMISER, 1,
                null, null, CandidateLinkStatus.UNMATCHED,
                List.of(ReasonCode.CANDIDATE_ALLELE_UNMATCHED));
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION, RecordType.FOOTER, 2,
                DocumentStatus.FAILED, List.of(ReasonCode.CANDIDATE_ALLELE_UNMATCHED),
                0, 0, 0, 0, 0, 1, 0, 0,
                null, null, null, AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM, null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header, List.of(), List.of(), List.of(unmatched), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID,
                () -> validator.validate(document));
    }

    @Test
    void unavailableLiftoverCanSealWithoutAPhantomArtifactFingerprint() {
        Document document = unavailableLiftoverDocument(null);

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void unavailableLiftoverCannotClaimAnArtifactWithoutSupportingBodyFacts() {
        Document document = unavailableLiftoverDocument(
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA);

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID,
                () -> validator.validate(document));
    }

    @Test
    void successfulLiftoverCannotOmitItsArtifactFingerprint() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey sourceKey = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37,
                "1", 100, "A", "G");
        AlleleKey liftedKey = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,
                "1", 110, "A", "G");
        String engineId = AnalysisAlleleLineageWireFixtures.engineId(liftedKey);
        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                2, 1, AnalysisAlleleLineageWireFixtures.TOKEN, sourceKey, liftedKey, engineId);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, engineId, liftedKey.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                4, 1, 1, 1, 1, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1)),
                List.of(allele), List.of(candidate), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID,
                () -> validator.validate(document));
    }

    @Test
    void matchedEngineAlleleMustHaveAtLeastOneLinkedCandidate() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                3, 1, 1, 0, 1, 1, 0, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_DANGLING_REFERENCE, () -> validator.validate(document));
    }

    @Test
    void engineNotFoundIsACompleteInterpretableZeroCandidateOutcome() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord source = valid.alleleRecords().get(0);
        AlleleRecord notFound = new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                source.normalizationStatus(), source.normalizationReasonCodes(), source.canonicalKeys(),
                source.normalizationCardinality(), source.normalizationTransforms(),
                EngineMatchStatus.NOT_FOUND, List.of(ReasonCode.ENGINE_ALLELE_NOT_FOUND), List.of());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                3, 1, 1, 0, 1, 0, 0, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), List.of(notFound), List.of(), footer));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void sharedCanonicalAlleleCannotHaveContradictoryEngineOutcomes() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord matched = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, key,
                AnalysisAlleleLineageWireFixtures.engineId(key));
        AlleleRecord secondBase = AnalysisAlleleLineageWireFixtures.matchedAllele(
                4, 2, 1, 1, "89abcdef0123456701234567", key,
                AnalysisAlleleLineageWireFixtures.engineId(key));
        AlleleRecord notFound = new AlleleRecord(
                secondBase.schemaVersion(), secondBase.recordType(), secondBase.recordOrdinal(),
                secondBase.sourceAlleleToken(), secondBase.sourceRecordOrdinal(),
                secondBase.sourceAlternateIndex(), secondBase.sourceAlternateCount(),
                secondBase.inputStatus(), secondBase.inputReasonCodes(), secondBase.sourceKey(),
                secondBase.liftoverStatus(), secondBase.liftoverReasonCodes(), secondBase.liftedKeys(),
                secondBase.liftoverCardinality(), secondBase.liftoverTransforms(),
                secondBase.targetReferenceValidated(), secondBase.normalizationStatus(),
                secondBase.normalizationReasonCodes(), secondBase.canonicalKeys(),
                secondBase.normalizationCardinality(), secondBase.normalizationTransforms(),
                EngineMatchStatus.NOT_FOUND, List.of(ReasonCode.ENGINE_ALLELE_NOT_FOUND), List.of());
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, AnalysisAlleleLineageWireFixtures.engineId(key), key.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                6, 2, 2, 0, 2, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(matched, notFound), List.of(candidate), footer));

        assertCode(ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                () -> validator.validate(document));
    }

    @Test
    void duplicateSourceAlleleCannotHaveContradictoryPreparationOutcomes() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord matched = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, key,
                AnalysisAlleleLineageWireFixtures.engineId(key));
        AlleleRecord rejected = new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                4,
                "89abcdef0123456701234567",
                2, 1, 1,
                InputStatus.ACCEPTED,
                List.of(),
                key,
                LiftoverStatus.NOT_REQUIRED,
                List.of(ReasonCode.LIFTOVER_NOT_REQUIRED),
                List.of(), RelationCardinality.ONE_TO_ZERO, List.of(), null,
                NormalizationStatus.REJECTED,
                List.of(ReasonCode.NORMALIZATION_REFERENCE_MISMATCH),
                List.of(), RelationCardinality.ONE_TO_ZERO, List.of(),
                EngineMatchStatus.NOT_SUBMITTED,
                List.of(ReasonCode.ENGINE_NOT_SUBMITTED), List.of());
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, AnalysisAlleleLineageWireFixtures.engineId(key), key.canonicalVariantId());
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.FOOTER,
                6,
                DocumentStatus.PARTIAL,
                List.of(ReasonCode.NORMALIZATION_REFERENCE_MISMATCH),
                2, 2, 0, 1, 1, 1, 0, 1,
                null,
                AnalysisAlleleLineageWireFixtures.CANONICAL_SHA,
                AnalysisAlleleLineageWireFixtures.ENGINE_SHA,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(matched, rejected), List.of(candidate), footer));

        assertCode(ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                () -> validator.validate(document));
    }

    @Test
    void duplicateAltTextMustBeDeclaredOnEveryDuplicateTarget() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, 1, 2, AnalysisAlleleLineageWireFixtures.TOKEN, key,
                AnalysisAlleleLineageWireFixtures.engineId(key));
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 2, 2, "89abcdef0123456701234567", key,
                AnalysisAlleleLineageWireFixtures.engineId(key));
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4, 1, AnalysisAlleleLineageWireFixtures.engineId(key), key.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                5, 1, 2, 0, 2, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 2)),
                List.of(first, second), List.of(candidate), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @Test
    void fullyDeclaredDuplicateAltTargetsRemainDistinctAndValid() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord first = withDuplicateAltReason(
                AnalysisAlleleLineageWireFixtures.matchedAllele(
                        2, 1, 1, 2, AnalysisAlleleLineageWireFixtures.TOKEN, key,
                        AnalysisAlleleLineageWireFixtures.engineId(key)));
        AlleleRecord second = withDuplicateAltReason(
                AnalysisAlleleLineageWireFixtures.matchedAllele(
                        3, 1, 2, 2, "89abcdef0123456701234567", key,
                        AnalysisAlleleLineageWireFixtures.engineId(key)));
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4, 1, AnalysisAlleleLineageWireFixtures.engineId(key), key.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                5, 1, 2, 0, 2, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 2)),
                List.of(first, second), List.of(candidate), footer));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void liftoverStatusCannotCarryAReasonFromAnotherTerminalState() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey sourceKey = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37,
                "1", 100, "A", "G");
        AlleleRecord contradictory = new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                2,
                AnalysisAlleleLineageWireFixtures.TOKEN,
                1,
                1,
                1,
                InputStatus.ACCEPTED,
                List.of(),
                sourceKey,
                LiftoverStatus.UNMAPPED,
                List.of(ReasonCode.LIFTOVER_MISMATCHED_REFERENCE),
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
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.withLiftedArtifact(
                failedFooter(3, List.of(ReasonCode.LIFTOVER_MISMATCHED_REFERENCE,
                        ReasonCode.ENGINE_NOT_SUBMITTED), 1, 0, 1),
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1)),
                List.of(contradictory), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @Test
    void ambiguousLiftoverTargetsRequireTransformProvenance() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey sourceKey = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37,
                "1", 100, "A", "G");
        AlleleKey firstTarget = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,
                "1", 110, "A", "G");
        AlleleKey secondTarget = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,
                "1", 120, "A", "G");
        AlleleRecord ambiguous = new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                2,
                AnalysisAlleleLineageWireFixtures.TOKEN,
                1, 1, 1,
                InputStatus.ACCEPTED,
                List.of(),
                sourceKey,
                LiftoverStatus.AMBIGUOUS,
                List.of(ReasonCode.LIFTOVER_AMBIGUOUS),
                List.of(firstTarget, secondTarget),
                RelationCardinality.ONE_TO_MANY,
                List.of(),
                true,
                NormalizationStatus.NOT_SUBMITTED,
                List.of(), List.of(), RelationCardinality.ONE_TO_ZERO, List.of(),
                EngineMatchStatus.NOT_SUBMITTED,
                List.of(ReasonCode.ENGINE_NOT_SUBMITTED), List.of());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.withLiftedArtifact(
                failedFooter(3, List.of(ReasonCode.LIFTOVER_AMBIGUOUS), 1, 0, 0),
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1)),
                List.of(ambiguous), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_CARDINALITY_CONFLICT,
                () -> validator.validate(document));
    }

    @Test
    void candidateSourceOrdinalUsesItsDedicatedStableErrorCode() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        CandidateLinkRecord invalid = new CandidateLinkRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.CANDIDATE_LINK,
                3,
                "exomiser-v1-00000000",
                AnalysisAlleleLineageWire.CandidateSource.EXOMISER,
                0,
                AnalysisAlleleLineageWireFixtures.ENGINE_ID,
                valid.alleleRecords().get(0).canonicalKeys().get(0).canonicalVariantId(),
                AnalysisAlleleLineageWire.CandidateLinkStatus.LINKED,
                List.of());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(invalid),
                valid.footer()));

        assertCode(ReasonCode.CANDIDATE_SOURCE_ORDINAL_INVALID,
                () -> validator.validate(document));
    }

    @Test
    void candidateSourceOrdinalAcceptsTheSignedIntMaximum() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        String canonicalId = valid.alleleRecords().get(0).canonicalKeys()
                .get(0).canonicalVariantId();
        CandidateLinkRecord boundary = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, Integer.MAX_VALUE, AnalysisAlleleLineageWireFixtures.ENGINE_ID, canonicalId);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(boundary),
                valid.footer()));

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void candidateSourceOrdinalRejectsTheFirstValueAboveSignedInt() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        String canonicalId = valid.alleleRecords().get(0).canonicalKeys()
                .get(0).canonicalVariantId();
        long overflow = (long) Integer.MAX_VALUE + 1L;
        CandidateLinkRecord invalid = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, overflow, AnalysisAlleleLineageWireFixtures.ENGINE_ID, canonicalId);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(invalid),
                valid.footer()));

        assertCode(ReasonCode.CANDIDATE_SOURCE_ORDINAL_INVALID,
                () -> validator.validate(document));
    }

    @Test
    void candidateFanOutIsBoundedPerEngineAllele() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        HeaderRecord header = withRelationFanOut(valid.header(), 1);
        String canonicalId = valid.alleleRecords().get(0).canonicalKeys().get(0).canonicalVariantId();
        CandidateLinkRecord first = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, AnalysisAlleleLineageWireFixtures.ENGINE_ID, canonicalId);
        CandidateLinkRecord second = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4, 2, AnalysisAlleleLineageWireFixtures.ENGINE_ID, canonicalId);
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                5, 1, 1, 0, 1, 1, 2, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header, valid.inputRecords(), valid.alleleRecords(), List.of(first, second), footer));

        assertCode(ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED,
                () -> validator.validate(document));
    }

    @Test
    void limitExceededCannotBePublishedAsASealedDocument() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord source = valid.footer();
        FooterRecord limit = new FooterRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                DocumentStatus.LIMIT_EXCEEDED,
                List.of(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED),
                source.inputRecordCount(), source.inputAlleleCount(), source.liftedAlleleCount(),
                source.canonicalAlleleCount(), source.engineAlleleCount(),
                source.candidateLinkCount(), source.unresolvedAlleleCount(),
                source.rejectedAlleleCount(), source.liftedArtifactSha256(),
                source.canonicalArtifactSha256(), source.engineArtifactSha256(),
                source.fingerprintAlgorithm(), null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(valid.withFooter(limit));

        assertCode(ReasonCode.LINEAGE_REUSE_STATUS_INELIGIBLE,
                () -> validator.validate(document));
    }

    @Test
    void failedFooterCannotSelfAttestAnUnrepresentedFailure() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.FOOTER,
                2,
                DocumentStatus.FAILED,
                List.of(ReasonCode.ENGINE_OUTPUT_INVALID),
                1, 0, 0, 0, 0, 0, 0, 0,
                null, null, null,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 0)),
                List.of(), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @Test
    void partialFooterReasonsMustBeDerivedFromActualTerminalStates() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord success = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN,
                AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G"),
                AnalysisAlleleLineageWireFixtures.ENGINE_ID);
        AlleleRecord rejected = rejectedAllele(4, 2, "89abcdef0123456701234567");
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, AnalysisAlleleLineageWireFixtures.ENGINE_ID,
                success.canonicalKeys().get(0).canonicalVariantId());
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.FOOTER,
                6,
                DocumentStatus.PARTIAL,
                List.of(ReasonCode.CANDIDATE_ALLELE_UNMATCHED),
                2, 2, 0, 1, 1, 1, 0, 1,
                null,
                AnalysisAlleleLineageWireFixtures.CANONICAL_SHA,
                AnalysisAlleleLineageWireFixtures.ENGINE_SHA,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(success, rejected), List.of(candidate), footer));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @Test
    void partialFooterWithExactlyDerivedReasonsIsValid() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord success = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN,
                AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G"),
                AnalysisAlleleLineageWireFixtures.ENGINE_ID);
        AlleleRecord rejected = rejectedAllele(4, 2, "89abcdef0123456701234567");
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, AnalysisAlleleLineageWireFixtures.ENGINE_ID,
                success.canonicalKeys().get(0).canonicalVariantId());
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.FOOTER,
                6,
                DocumentStatus.PARTIAL,
                List.of(ReasonCode.INPUT_ALLELE_UNSUPPORTED),
                2, 2, 0, 1, 1, 1, 0, 1,
                null,
                AnalysisAlleleLineageWireFixtures.CANONICAL_SHA,
                AnalysisAlleleLineageWireFixtures.ENGINE_SHA,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                null);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(success, rejected), List.of(candidate), footer));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void engineIdDerivationHasAStableKnownAnswer() {
        assertEquals("engine-allele-v1-5b3108435080796c349868b1",
                AnalysisAlleleLineageWire.engineAlleleId("GRCh38|1|100|A|G"));
    }

    private static AlleleRecord rejectedAllele(long recordOrdinal,
                                               long sourceRecordOrdinal,
                                               String token) {
        return new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                recordOrdinal,
                token,
                sourceRecordOrdinal,
                1,
                1,
                InputStatus.REJECTED,
                List.of(ReasonCode.INPUT_ALLELE_UNSUPPORTED),
                null,
                LiftoverStatus.NOT_SUBMITTED,
                List.of(),
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

    private static AlleleRecord withDuplicateAltReason(AlleleRecord source) {
        return new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                List.of(ReasonCode.INPUT_DUPLICATE_ALT), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(), source.liftoverCardinality(),
                source.liftoverTransforms(), source.targetReferenceValidated(),
                source.normalizationStatus(), source.normalizationReasonCodes(), source.canonicalKeys(),
                source.normalizationCardinality(), source.normalizationTransforms(),
                source.engineMatchStatus(), source.engineReasonCodes(), source.engineAlleles());
    }

    private static FooterRecord failedFooter(long recordOrdinal,
                                             List<ReasonCode> reasons,
                                             long inputAlleleCount,
                                             long canonicalAlleleCount,
                                             long rejectedAlleleCount) {
        return new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.FOOTER,
                recordOrdinal,
                DocumentStatus.FAILED,
                reasons,
                1,
                inputAlleleCount,
                0,
                canonicalAlleleCount,
                0,
                0,
                0,
                rejectedAlleleCount,
                null,
                null,
                null,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                null);
    }

    private static HeaderRecord withRelationFanOut(HeaderRecord source, int fanOut) {
        Limits limits = source.limits();
        return new HeaderRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.generatorVersion(), source.pipelineProfileId(),
                source.sourceAlleleTokenAlgorithm(), source.inputAssembly(), source.analysisAssembly(),
                source.inputArtifactSha256(), source.liftoverPolicy(), source.liftoverTool(),
                source.liftoverToolVersion(), source.liftoverChainSha256(),
                source.liftoverReferenceSha256(), source.normalizationProfileId(),
                source.normalizationReferenceSha256(), source.normalizationTool(),
                source.normalizationToolVersion(), source.normalizationCommandDigest(),
                source.engineName(), source.engineVersion(), source.engineResourceFingerprint(),
                source.engineParserSchemaVersion(), source.candidateIdentitySchemaVersion(),
                source.limitProfileId(), new Limits(
                limits.maxSidecarBytes(), limits.maxLineBytes(), limits.maxRecordCount(),
                limits.maxInputAlleleCount(), limits.maxCandidateLinkCount(), limits.maxAlleleBytes(),
                limits.maxRelationFanIn(), fanOut));
    }

    private static Document unavailableLiftoverDocument(String liftedArtifactSha256) {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey sourceKey = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37,
                "1", 100, "A", "G");
        AlleleRecord unavailable = new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION, RecordType.ALLELE_LINEAGE, 2,
                AnalysisAlleleLineageWireFixtures.TOKEN, 1, 1, 1,
                InputStatus.ACCEPTED, List.of(), sourceKey,
                LiftoverStatus.UNAVAILABLE, List.of(ReasonCode.LIFTOVER_UNAVAILABLE),
                List.of(), RelationCardinality.ONE_TO_ZERO, List.of(), null,
                NormalizationStatus.NOT_SUBMITTED, List.of(), List.of(),
                RelationCardinality.ONE_TO_ZERO, List.of(),
                EngineMatchStatus.NOT_SUBMITTED,
                List.of(ReasonCode.ENGINE_NOT_SUBMITTED), List.of());
        FooterRecord footer = new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION, RecordType.FOOTER, 3,
                DocumentStatus.FAILED, List.of(ReasonCode.LIFTOVER_UNAVAILABLE),
                1, 1, 0, 0, 0, 0, 0, 1,
                liftedArtifactSha256, null, null,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM, null);
        return AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1)),
                List.of(unavailable), List.of(), footer));
    }

    private static void assertCode(ReasonCode expected, Runnable action) {
        LineageValidationException exception = assertThrows(LineageValidationException.class, action::run);
        assertEquals(expected, exception.getReasonCode());
    }
}
