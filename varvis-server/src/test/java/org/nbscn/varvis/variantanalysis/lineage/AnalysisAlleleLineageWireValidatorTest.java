package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageWireValidatorTest {

    private final AnalysisAlleleLineageWireValidator validator =
            new AnalysisAlleleLineageWireValidator();

    @Test
    void acceptsSealedCanonicalSyntheticDocument() {
        assertDoesNotThrow(() -> validator.validate(
                AnalysisAlleleLineageWireFixtures.validDocument()));
    }

    @Test
    void acceptsVerifiedConvertBeforeNormalizeCrossAssemblyLineage() {
        HeaderRecord header = AnalysisAlleleLineageWireFixtures.crossAssemblyHeader();
        AlleleKey source = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37,
                "1", 100, "A", "G");
        AlleleKey lifted = AnalysisAlleleLineageWireFixtures.key(
                org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,
                "1", 110, "A", "G");
        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.crossAssemblyMatchedAllele(
                2, 1, AnalysisAlleleLineageWireFixtures.TOKEN, source, lifted,
                AnalysisAlleleLineageWireFixtures.engineId(lifted));
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, AnalysisAlleleLineageWireFixtures.engineId(lifted),
                lifted.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.withLiftedArtifact(
                AnalysisAlleleLineageWireFixtures.footer(4, 1, 1, 1, 1, 1, 1, 0, 0),
                AnalysisAlleleLineageWireFixtures.LIFTED_SHA);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                header,
                List.of(AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1)),
                List.of(allele),
                List.of(candidate),
                footer));

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void inputRecordRowsMakeZeroAltRecordsAndRecordCountRecomputable() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        InputRecord zeroAlt = AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 0);
        InputRecord variant = AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1);
        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 2, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, key,
                AnalysisAlleleLineageWireFixtures.ENGINE_ID);
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                4, 1, AnalysisAlleleLineageWireFixtures.ENGINE_ID, key.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                5, 2, 1, 0, 1, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), List.of(zeroAlt, variant), List.of(allele), List.of(candidate), footer));

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void rejectsMissingMultiAltIndex() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        InputRecord input = AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 2);
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleRecord onlyFirst = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2, 1, 1, 2, AnalysisAlleleLineageWireFixtures.TOKEN, key,
                AnalysisAlleleLineageWireFixtures.ENGINE_ID);
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                3, 1, 1, 0, 1, 1, 0, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), List.of(input), List.of(onlyFirst), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_COUNT_MISMATCH, () -> validator.validate(document));
    }

    @Test
    void acceptsManySourcesToOneCanonicalWithoutCollapsingOccurrenceCount() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey key = AnalysisAlleleLineageWireFixtures.key("2", 200, "C", "T");
        String secondToken = "89abcdef0123456701234567";
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, key,
                AnalysisAlleleLineageWireFixtures.engineId(key));
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                4, 2, 1, 1, secondToken, key, AnalysisAlleleLineageWireFixtures.engineId(key));
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                5, 1, AnalysisAlleleLineageWireFixtures.engineId(key), key.canonicalVariantId());
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                6, 2, 2, 0, 2, 1, 1, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(first, second),
                List.of(candidate),
                footer));

        assertDoesNotThrow(() -> validator.validate(document));
    }

    @Test
    void rejectsSameTokenForDifferentSourcePairs() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey firstKey = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleKey secondKey = AnalysisAlleleLineageWireFixtures.key("1", 101, "C", "T");
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, firstKey,
                AnalysisAlleleLineageWireFixtures.ENGINE_ID);
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                4, 2, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, secondKey,
                AnalysisAlleleLineageWireFixtures.engineId(secondKey));
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                5, 2, 2, 0, 2, 2, 0, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(first, second), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_DUPLICATE_ID, () -> validator.validate(document));
    }

    @Test
    void rejectsEngineIdThatWasNotDerivedFromItsCanonicalAllele() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleKey firstKey = AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G");
        AlleleKey secondKey = AnalysisAlleleLineageWireFixtures.key("1", 101, "C", "T");
        AlleleRecord first = AnalysisAlleleLineageWireFixtures.matchedAllele(
                3, 1, 1, 1, AnalysisAlleleLineageWireFixtures.TOKEN, firstKey,
                AnalysisAlleleLineageWireFixtures.ENGINE_ID);
        AlleleRecord second = AnalysisAlleleLineageWireFixtures.matchedAllele(
                4, 2, 1, 1, "89abcdef0123456701234567", secondKey,
                AnalysisAlleleLineageWireFixtures.ENGINE_ID);
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.footer(
                5, 2, 2, 0, 2, 1, 0, 0, 0);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(),
                List.of(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1, 1, 1),
                        AnalysisAlleleLineageWireFixtures.inputRecord(2, 2, 1)),
                List.of(first, second), List.of(), footer));

        assertCode(ReasonCode.LINEAGE_DANGLING_REFERENCE, () -> validator.validate(document));
    }

    @Test
    void rejectsCardinalityThatDoesNotMatchTheProjectedArray() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord source = valid.alleleRecords().get(0);
        AlleleRecord poisoned = new AlleleRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.sourceAlleleToken(), source.sourceRecordOrdinal(),
                source.sourceAlternateIndex(), source.sourceAlternateCount(), source.inputStatus(),
                source.inputReasonCodes(), source.sourceKey(), source.liftoverStatus(),
                source.liftoverReasonCodes(), source.liftedKeys(),
                AnalysisAlleleLineageWire.RelationCardinality.ONE_TO_ONE,
                source.liftoverTransforms(), source.targetReferenceValidated(),
                source.normalizationStatus(), source.normalizationReasonCodes(),
                source.canonicalKeys(), source.normalizationCardinality(),
                source.normalizationTransforms(), source.engineMatchStatus(),
                source.engineReasonCodes(), source.engineAlleles());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), List.of(poisoned), valid.candidateLinks(),
                valid.footer()));

        assertCode(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, () -> validator.validate(document));
    }

    @Test
    void rejectsDanglingCandidateReference() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        CandidateLinkRecord candidate = AnalysisAlleleLineageWireFixtures.linkedCandidate(
                3, 1, "engine-allele-v1-999999999999999999999999",
                AnalysisAlleleLineageWireFixtures.key("1", 100, "A", "G").canonicalVariantId());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(candidate),
                valid.footer()));

        assertCode(ReasonCode.LINEAGE_DANGLING_REFERENCE, () -> validator.validate(document));
    }

    @Test
    void nonLinkedCandidateMustNotCarryASingleGuessedTarget() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        CandidateLinkRecord ambiguous = new CandidateLinkRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.CANDIDATE_LINK,
                3,
                "exomiser-v1-00000001",
                CandidateSource.EXOMISER,
                1,
                AnalysisAlleleLineageWireFixtures.ENGINE_ID,
                valid.alleleRecords().get(0).canonicalKeys().get(0).canonicalVariantId(),
                CandidateLinkStatus.AMBIGUOUS,
                List.of(ReasonCode.CANDIDATE_ALLELE_AMBIGUOUS)
        );
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), valid.inputRecords(), valid.alleleRecords(), List.of(ambiguous),
                valid.footer()));

        assertCode(ReasonCode.LINEAGE_FIELD_INVALID, () -> validator.validate(document));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void rejectsEveryTamperedFooterCount(int countIndex) {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        long[] counts = {1, 1, 0, 1, 1, 1, 0, 0};
        counts[countIndex]++;
        FooterRecord footer = AnalysisAlleleLineageWireFixtures.copyFooter(
                valid.footer(), counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
                counts[6], counts[7]);
        Document document = AnalysisAlleleLineageWireFixtures.reseal(valid.withFooter(footer));

        assertCode(ReasonCode.LINEAGE_COUNT_MISMATCH, () -> validator.validate(document));
    }

    @Test
    void rejectsRecordOrderAndOrdinalGaps() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        InputRecord moved = new InputRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.INPUT_RECORD,
                2,
                1,
                1,
                valid.inputRecords().get(0).inputRecordStatus(),
                List.of());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                valid.header(), List.of(moved), valid.alleleRecords(), valid.candidateLinks(),
                valid.footer()));

        assertCode(ReasonCode.LINEAGE_ORDER_INVALID, () -> validator.validate(document));
    }

    @Test
    void rejectsNonCanonicalReasonOrder() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord source = valid.footer();
        FooterRecord poisoned = new FooterRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(), source.status(),
                List.of(ReasonCode.LINEAGE_COUNT_MISMATCH,
                        ReasonCode.LINEAGE_FINGERPRINT_MISMATCH),
                source.inputRecordCount(), source.inputAlleleCount(), source.liftedAlleleCount(),
                source.canonicalAlleleCount(), source.engineAlleleCount(),
                source.candidateLinkCount(), source.unresolvedAlleleCount(),
                source.rejectedAlleleCount(), source.liftedArtifactSha256(),
                source.canonicalArtifactSha256(), source.engineArtifactSha256(),
                source.fingerprintAlgorithm(), source.contentFingerprint());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(valid.withFooter(poisoned));

        assertCode(ReasonCode.LINEAGE_ORDER_INVALID, () -> validator.validate(document));
    }

    @Test
    void documentDefensivelyCopiesAllRecordLists() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        List<InputRecord> inputs = new ArrayList<>(valid.inputRecords());
        Document copy = new Document(valid.header(), inputs, valid.alleleRecords(),
                valid.candidateLinks(), valid.footer());

        inputs.clear();

        assertEquals(1, copy.inputRecords().size());
        assertThrows(UnsupportedOperationException.class, () -> copy.inputRecords().clear());
    }

    @Test
    void rejectsTamperedFingerprintWithoutEchoingSensitiveValues() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        Document tampered = valid.withFooter(valid.footer().withContentFingerprint("0".repeat(64)));

        LineageValidationException exception = assertThrows(
                LineageValidationException.class, () -> validator.validate(tampered));

        assertEquals(ReasonCode.LINEAGE_FINGERPRINT_MISMATCH, exception.getReasonCode());
        assertFalse(exception.getMessage().contains(AnalysisAlleleLineageWireFixtures.TOKEN));
        assertFalse(exception.getMessage().contains("GRCh38|1|100|A|G"));
    }

    @Test
    void documentAndRecordsHaveRedactedToStringRepresentations() {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();
        List<String> rendered = new ArrayList<>();
        rendered.add(document.toString());
        document.recordsInCanonicalSections().forEach(value -> rendered.add(value.toString()));
        document.alleleRecords().get(0).canonicalKeys().forEach(value -> rendered.add(value.toString()));
        document.alleleRecords().get(0).engineAlleles().forEach(value -> rendered.add(value.toString()));

        assertFalse(String.join("|", rendered).contains(AnalysisAlleleLineageWireFixtures.TOKEN));
        assertFalse(String.join("|", rendered).contains("GRCh38|1|100|A|G"));
    }

    private static void assertCode(ReasonCode expected, Runnable action) {
        LineageValidationException exception = assertThrows(LineageValidationException.class, action::run);
        assertEquals(expected, exception.getReasonCode());
    }
}
