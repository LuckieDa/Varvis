package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisAlleleLineageWirePrivacyTest {

    private final AnalysisAlleleLineageWireValidator validator =
            new AnalysisAlleleLineageWireValidator();

    @Test
    void legalHeaderSlotCannotCarryAPathOrCaseLabel() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        String canary = "C:\\patients\\CASE-001\\input.vcf";
        HeaderRecord source = valid.header();
        HeaderRecord poisoned = new HeaderRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.generatorVersion(), source.pipelineProfileId(),
                source.sourceAlleleTokenAlgorithm(), source.inputAssembly(), source.analysisAssembly(),
                source.inputArtifactSha256(), source.liftoverPolicy(), source.liftoverTool(),
                source.liftoverToolVersion(), source.liftoverChainSha256(),
                source.liftoverReferenceSha256(), source.normalizationProfileId(),
                source.normalizationReferenceSha256(), source.normalizationTool(), canary,
                source.normalizationCommandDigest(), source.engineName(), source.engineVersion(),
                source.engineResourceFingerprint(), source.engineParserSchemaVersion(),
                source.candidateIdentitySchemaVersion(), source.limitProfileId(), source.limits());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                poisoned, valid.inputRecords(), valid.alleleRecords(), valid.candidateLinks(),
                valid.footer()));

        LineageValidationException exception = assertThrows(
                LineageValidationException.class, () -> validator.validate(document));

        assertEquals(ReasonCode.LINEAGE_FIELD_INVALID, exception.getReasonCode());
        assertFalse(exception.getMessage().contains(canary));
        assertEquals("LINEAGE_FIELD_INVALID:normalizationToolVersion", exception.getMessage());
    }

    @Test
    void exceptionNeverEchoesInvalidSchemaValue() {
        Document valid = AnalysisAlleleLineageWireFixtures.validDocument();
        String canary = "patient@example.invalid";
        HeaderRecord source = valid.header();
        HeaderRecord poisoned = new HeaderRecord(
                canary, source.recordType(), source.recordOrdinal(), source.generatorVersion(),
                source.pipelineProfileId(), source.sourceAlleleTokenAlgorithm(), source.inputAssembly(),
                source.analysisAssembly(), source.inputArtifactSha256(), source.liftoverPolicy(),
                source.liftoverTool(), source.liftoverToolVersion(), source.liftoverChainSha256(),
                source.liftoverReferenceSha256(), source.normalizationProfileId(),
                source.normalizationReferenceSha256(), source.normalizationTool(),
                source.normalizationToolVersion(), source.normalizationCommandDigest(),
                source.engineName(), source.engineVersion(), source.engineResourceFingerprint(),
                source.engineParserSchemaVersion(), source.candidateIdentitySchemaVersion(),
                source.limitProfileId(), source.limits());
        Document document = AnalysisAlleleLineageWireFixtures.reseal(new Document(
                poisoned, valid.inputRecords(), valid.alleleRecords(), valid.candidateLinks(),
                valid.footer()));

        LineageValidationException exception = assertThrows(
                LineageValidationException.class, () -> validator.validate(document));

        assertEquals(ReasonCode.LINEAGE_SCHEMA_UNSUPPORTED, exception.getReasonCode());
        assertFalse(exception.getMessage().contains(canary));
    }

    @Test
    void exceptionConstructorCannotEchoAnUncontrolledFieldPath() {
        String canary = "C:\\patients\\CASE-001\\input.vcf";

        LineageValidationException exception = new LineageValidationException(
                ReasonCode.LINEAGE_FIELD_INVALID, canary);

        assertEquals("LINEAGE_FIELD_INVALID", exception.getMessage());
        assertNull(exception.getField());
        assertFalse(exception.getMessage().contains(canary));
    }
}
