package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClinVarEvidenceMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compactAndTabixRecordsShareClinicalSemanticsAndRoundTripJson() throws Exception {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        List<ClinVarEvidenceMapper.Condition> conditions = Arrays.asList(
                new ClinVarEvidenceMapper.Condition("Disease A", "MedGen:C000001"),
                new ClinVarEvidenceMapper.Condition("Disease B", "MedGen:C000002")
        );
        ClinVarEvidenceMapper.Record compact = record("LOCAL_CLINVAR_COMPACT", conditions);
        ClinVarEvidenceMapper.Record tabix = record("LOCAL_CLINVAR", conditions);

        EvidenceItem compactEvidence = ClinVarEvidenceMapper.record(key, compact, "exact_allele");
        EvidenceItem tabixEvidence = ClinVarEvidenceMapper.record(key, tabix, "exact_allele");
        EvidenceItem restored = objectMapper.readValue(
                objectMapper.writeValueAsString(compactEvidence), EvidenceItem.class);

        assertEquals(EvidenceItem.CURRENT_SCHEMA_VERSION, compactEvidence.getSchemaVersion());
        assertEquals("Pathogenic/Likely_benign", compactEvidence.getDetails().get("aggregateClassification"));
        assertEquals("CONFLICTING_INTERPRETATIONS", compactEvidence.getDetails().get("conflictState"));
        assertEquals("CRITERIA_PROVIDED", compactEvidence.getDetails().get("reviewLevel"));
        assertEquals(2, ((List<?>) compactEvidence.getDetails().get("conditions")).size());
        assertEquals(compactEvidence.getDetails().get("aggregateClassification"),
                tabixEvidence.getDetails().get("aggregateClassification"));
        assertEquals(compactEvidence.getDetails().get("conflictState"), tabixEvidence.getDetails().get("conflictState"));
        assertEquals(compactEvidence.getDetails().get("conditions"), tabixEvidence.getDetails().get("conditions"));
        assertEquals(compactEvidence.getId(), restored.getId());
        assertEquals(compactEvidence.getDetails().get("conditions"), restored.getDetails().get("conditions"));
    }

    @Test
    void stableIdentitySeparatesConditionsButKeepsExactDuplicatesEqual() {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh37", "1", 100, "A", "G");
        EvidenceItem first = ClinVarEvidenceMapper.record(key, record("LOCAL_CLINVAR",
                Arrays.asList(new ClinVarEvidenceMapper.Condition("Disease A", "C1"))), "exact_allele");
        EvidenceItem duplicate = ClinVarEvidenceMapper.record(key, record("LOCAL_CLINVAR",
                Arrays.asList(new ClinVarEvidenceMapper.Condition("Disease A", "C1"))), "exact_allele");
        EvidenceItem differentCondition = ClinVarEvidenceMapper.record(key, record("LOCAL_CLINVAR",
                Arrays.asList(new ClinVarEvidenceMapper.Condition("Disease B", "C2"))), "exact_allele");

        assertEquals(first.getStableIdentityKey(), duplicate.getStableIdentityKey());
        assertNotEquals(first.getStableIdentityKey(), differentCondition.getStableIdentityKey());
        assertTrue(first.getStableIdentityKey().contains("GRCh37"));
    }

    @Test
    void statusEvidenceKeepsNonRecordStatesDistinctAndNeverAssignsScore() {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        EvidenceItem noRecord = ClinVarEvidenceMapper.status(
                key, "test", "LOCAL_CLINVAR", "GRCh38", "NO_RECORD", "missing", "exact");
        EvidenceItem unavailable = ClinVarEvidenceMapper.status(
                key, "test", "LOCAL_CLINVAR", "GRCh38", "UNAVAILABLE", "offline", "native");
        EvidenceItem unsupported = ClinVarEvidenceMapper.status(
                key, "test", "LOCAL_CLINVAR", "GRCh36", "ASSEMBLY_UNSUPPORTED", "unsupported", "native");
        EvidenceItem parseError = ClinVarEvidenceMapper.status(
                key, "test", "LOCAL_CLINVAR", "GRCh38", "PARSE_ERROR", "malformed", "native");
        EvidenceItem timeout = ClinVarEvidenceMapper.status(
                key, "test", "LOCAL_CLINVAR", "GRCh38", "TIMEOUT", "timeout", "native");

        assertEquals("NO_RECORD", noRecord.getEffectiveStatus());
        assertEquals("UNAVAILABLE", unavailable.getEffectiveStatus());
        assertEquals("ASSEMBLY_UNSUPPORTED", unsupported.getEffectiveStatus());
        assertEquals("PARSE_ERROR", parseError.getEffectiveStatus());
        assertEquals("TIMEOUT", timeout.getEffectiveStatus());
        assertEquals(null, timeout.getRawScore());
    }

    @Test
    void absentSourceFieldsRemainAbsentAndNeverBecomeAPathogenicDefault() {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        ClinVarEvidenceMapper.Record record = new ClinVarEvidenceMapper.Record(
                "LOCAL_CLINVAR", "ClinVar test 2026-07", "GRCh38",
                null, List.of(), null, List.of(), "123", null, null, null,
                null, null, null, List.of(), false);

        EvidenceItem evidence = ClinVarEvidenceMapper.record(key, record, "exact_allele");

        assertNull(evidence.getRawValue());
        assertEquals("ClinVar record", evidence.getLabel());
        assertFalse(evidence.getDetails().containsKey("aggregateClassification"));
        assertFalse(evidence.getDetails().containsKey("reviewStatus"));
        assertFalse(evidence.getDetails().containsKey("reviewLevel"));
        assertFalse(evidence.getDetails().containsKey("conditions"));
        assertFalse(evidence.getDetails().containsKey("lastEvaluated"));
        assertFalse(evidence.getDetails().containsKey("contributingSubmissionIdentifiers"));
        assertFalse(evidence.getDetails().containsKey("conflictState"));
    }

    private ClinVarEvidenceMapper.Record record(String source,
                                                List<ClinVarEvidenceMapper.Condition> conditions) {
        return new ClinVarEvidenceMapper.Record(source, "ClinVar test 2026-07", "GRCh38",
                "Pathogenic/Likely_benign",
                ClinVarEvidenceMapper.splitClassifications("Pathogenic/Likely_benign"),
                "criteria_provided", conditions, "123", "VCV000123", "456", "789",
                "2026-01-01", "GENE1", "GENE1:1", true);
    }
}
