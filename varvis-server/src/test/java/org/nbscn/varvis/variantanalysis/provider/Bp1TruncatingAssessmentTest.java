package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic sequence transport plus admitted finite source grammar. These are
 * condition-execution controls, not real patient variants or clinical approval.
 * Outcomes follow audit-bp1/independent-expectations fixed before this revision.
 */
class Bp1TruncatingAssessmentTest {
    @TempDir Path root;

    private Bp1TruncatingAssessment.Result run(Bp1LofDiseaseContraindicationTest.Fixture f) throws Exception {
        return Bp1TruncatingAssessment.assess(f.mapper, f.catalog(), f.context, f.candidate, f.material());
    }

    @Test void establishedNamedLofDiseaseContradictsTheSourceConditionWithoutAwardingBenignEvidence() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        var result = run(f); var facts = result.snapshot();
        assertEquals("NOT_MET", result.status(), facts::toPrettyString);
        assertEquals("BP1_GENE_HAS_ESTABLISHED_LOF_DISEASE_CORRELATION", result.reasonCode());
        assertEquals("NONE", facts.path("appliedStrength").asText());
        assertEquals("NO_ESTABLISHED_CORRELATION", facts.at("/required/geneLofDiseaseCorrelation").asText());
        assertEquals("CONTRAINDICATION_ESTABLISHED", facts.at("/available/geneLofDiseaseCorrelation").asText());
        assertEquals("TRUNCATION_CANDIDATE", facts.at("/sequenceConsequence/status").asText());
        assertFalse(facts.at("/sequenceConsequence/lossOfFunctionEstablished").asBoolean(true));
        assertFalse(facts.path("scientificQualificationGranted").asBoolean(true));
        assertTrue(facts.path("missing").isEmpty());
    }

    @Test void anotherGenesNamedDiseaseNeverBecomesThisGenesAbsenceQualification() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root, "MAP2K1", "HGNC:6840", "ENST000001.4");
        var result = run(f); var facts = result.snapshot();
        assertEquals("NOT_EVALUATED", result.status(), facts::toPrettyString);
        assertEquals("BP1_NO_ESTABLISHED_LOF_DISEASE_CORRELATION_NOT_VERIFIED", result.reasonCode());
        assertEquals("UNKNOWN", facts.at("/available/geneLofDiseaseCorrelation").asText());
        assertEquals("PTPN11", facts.at("/lofDiseaseCorrelation/sourceMention/namedGeneSymbol").asText());
        assertEquals("MAP2K1", facts.at("/lofDiseaseCorrelation/sourceMention/selectedGeneSymbol").asText());
        assertFalse(facts.at("/lofDiseaseCorrelation/sourceMention/subjectMatchesSelectedScope").asBoolean(true));
        assertEquals("GENE_LOF_DISEASE_CORRELATION", facts.at("/missing/0/conditionId").asText());
        assertFalse(facts.at("/required/missingRecordMeansNoCorrelation").asBoolean(true));
        assertEquals("NONE", facts.at("/evaluation/appliedStrength").asText());
    }

    @Test void exactStructuredFactCanOnlyRefuteTheNoLofCondition() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root, "MAP2K1", "HGNC:6840", "ENST000001.4");
        var material = f.material();
        var source = org.mockito.Mockito.mock(ReviewedRuleBundle.class);
        var proof = f.mapper.createObjectNode().put("status", "VERIFIED_ESTABLISHED_LOF")
                .put("modelSha256", material.snapshot().path("modelCanonicalSha256").asText());
        // Only test the consumer boundary here; the real catalog is exercised separately.
        org.mockito.Mockito.when(source.establishedLofForBp1(f.context)).thenReturn(proof);
        var admitted = Bp1TruncatingAssessment.assess(f.mapper, f.catalog(), f.context, f.candidate, material, () -> source);
        assertEquals("NOT_MET", admitted.status());
        assertEquals("NONE", admitted.snapshot().path("appliedStrength").asText());
        assertEquals(proof, admitted.publicSummary().path("structuredLof"));
        assertFalse(admitted.snapshot().path("scientificQualificationGranted").asBoolean(true));

        proof.put("modelSha256", "f".repeat(64));
        var mismatch = Bp1TruncatingAssessment.assess(f.mapper, f.catalog(), f.context, f.candidate, material, () -> source);
        assertEquals("NOT_EVALUATED", mismatch.status());
        assertFalse(mismatch.publicSummary().has("structuredLof"));
    }

    @Test void invalidCurrentAlleleKeepsItsBindingReasonRatherThanPretendingTheSourceIsInsufficient() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        var changed = f.context.deepCopy();
        ((ObjectNode) changed.at("/variant/evidenceQueryAllele")).put("alternate", "C");
        changed.put("contextId", f.adapter.canonicalContextId(changed));
        var result = Bp1TruncatingAssessment.assess(f.mapper, f.catalog(), changed, f.candidate, f.material());
        assertEquals("NOT_EVALUATED", result.status(), result.snapshot()::toPrettyString);
        assertNotEquals("BP1_NO_ESTABLISHED_LOF_DISEASE_CORRELATION_NOT_VERIFIED", result.reasonCode());
        assertEquals("NONE", result.snapshot().path("appliedStrength").asText());
    }

    @Test void sourceConflictCannotBeConvertedIntoAConditionalRuleOrNegativeEvaluation() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        ((ObjectNode) f.doc.at("/records/0")).put("status", "SOURCE_CONFLICT").put("reasonCode", "PUBLISHED_SOURCE_CONFLICT");
        var result = run(f);
        assertEquals("NOT_EVALUATED", result.status(), result.snapshot()::toPrettyString);
        assertEquals("BP1_TRUNCATING_SOURCE_ADMISSION_UNRESOLVED", result.reasonCode());
    }

    @Test void unknownInterpretationCannotBorrowTheKnownTruncatingSource() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        ((ObjectNode) f.doc.at("/records/0/interpretation")).put("type", "UNKNOWN_CONDITION");
        var result = run(f);
        assertEquals("NOT_EVALUATED", result.status(), result.snapshot()::toPrettyString);
        assertEquals("BP1_TRUNCATING_INTERPRETATION_UNSUPPORTED", result.reasonCode());
    }

    @Test void missingActualStructureCannotMintConditionAssessmentFromSourceJson() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        var result = Bp1TruncatingAssessment.assess(f.mapper, f.catalog(), f.context, f.candidate, null);
        assertEquals("NOT_EVALUATED", result.status());
        assertEquals("BP1_TRUNCATING_INPUT_UNAVAILABLE", result.reasonCode());
        assertFalse(result.snapshot().path("missing").isEmpty());
    }

    @Test void publicSummaryAndPrivateSnapshotAreImmutableAndDoNotPublishRawSequence() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        var result = run(f);
        assertEquals("NOT_MET", result.status(), result.snapshot()::toPrettyString);
        var privateCopy = result.snapshot(); privateCopy.removeAll();
        var publicCopy = result.publicSummary(); publicCopy.put("status", "MET");
        publicCopy.withObject("/available").put("geneLofDiseaseCorrelation", "NO_ESTABLISHED_CORRELATION");
        var fresh = result.publicSummary();
        assertEquals("NOT_MET", fresh.path("status").asText());
        assertEquals("CONTRAINDICATION_ESTABLISHED", fresh.at("/available/geneLofDiseaseCorrelation").asText());
        assertFalse(fresh.toString().contains("cdsSequence"));
        assertFalse(fresh.toString().contains("rawJson"));
        assertFalse(fresh.toString().contains("genomicSequence"));
        assertEquals("NOT_MET", result.snapshot().path("status").asText());
    }

    @Test void capabilityDoesNotAdvertiseUnimplementedNativeNegativeQualification() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        var capability = Bp1TruncatingAssessment.identity(f.mapper);
        assertTrue(capability.path("establishedLofContraindicationSupported").asBoolean());
        assertFalse(capability.path("noEstablishedLofCorrelationQualificationSupported").asBoolean(true));
        assertFalse(capability.path("sourceDefinedTruncationMeansExperimentalLof").asBoolean(true));
        assertFalse(capability.path("missingRecordMeansNoCorrelation").asBoolean(true));
        assertFalse(capability.path("createsReviewReceipt").asBoolean(true));
    }
    @Test void neitherFactsNorConstructorEnvelopeCanMintAConditionResult() throws Exception {
        var f = new Bp1LofDiseaseContraindicationTest.Fixture(root);
        var facts = run(f).snapshot(); facts.put("status", "MET").put("appliedStrength", "SUPPORTING");
        var envelope = f.mapper.createObjectNode(); envelope.set("facts", facts);
        for (var input : java.util.List.of(facts, envelope)) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> f.mapper.treeToValue(input, Bp1TruncatingAssessment.Result.class));
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(input, Bp1TruncatingAssessment.Result.class));
        }
    }

}
