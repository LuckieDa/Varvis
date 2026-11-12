package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcmgReadyEvidenceMapperTest {

    private final AcmgReadyEvidenceMapper mapper =
            new AcmgReadyEvidenceMapper(new VariantAnalysisProperties());

    @Test
    void veryCommonFrequencySuggestsBa1AndManualReviewWhenClinVarPathogenic() {
        VariantInterpretation variant = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.12d),
                clinVar("APPLICABLE", "Likely_pathogenic")
        );

        AcmgEvidenceSuggestion ba1 = suggestion(variant, "BA1");

        assertEquals("SUGGESTED", ba1.getStatus());
        assertEquals("STANDALONE", ba1.getStrength());
        assertTrue(ba1.isManualReviewRequired());
        assertTrue(String.valueOf(ba1.getWarnings()).contains("ClinVar"));
    }

    @Test
    void commonFrequencySuggestsBs1WithoutBa1() {
        VariantInterpretation variant = variant("missense_variant", gnomad("APPLICABLE", 0.02d));

        assertEquals("NOT_SUGGESTED", suggestion(variant, "BA1").getStatus());
        assertEquals("SUGGESTED", suggestion(variant, "BS1").getStatus());
    }

    @Test
    void noRecordSuggestsPm2CandidateButDoesNotClaimAfZero() {
        VariantInterpretation variant = variant("missense_variant", gnomad("NO_RECORD", null));

        AcmgEvidenceSuggestion pm2 = suggestion(variant, "PM2_candidate");

        assertEquals("SUGGESTED", pm2.getStatus());
        assertTrue(pm2.isManualReviewRequired());
        assertTrue(pm2.getExplanation().contains("not equivalent to AF=0"));
    }

    @Test
    void unavailableGnomadDoesNotSuggestPm2OrFrequencyRules() {
        VariantInterpretation variant = variant("missense_variant", gnomad("UNAVAILABLE", null));

        assertEquals("NOT_EVALUATED", suggestion(variant, "BA1").getStatus());
        assertEquals("NOT_EVALUATED", suggestion(variant, "BS1").getStatus());
        assertEquals("NOT_EVALUATED", suggestion(variant, "PM2_candidate").getStatus());
        assertTrue(String.valueOf(suggestion(variant, "PM2_candidate").getWarnings())
                .contains("unavailable"));
    }

    @Test
    void nonSuccessfulGnomadStatesRemainNotEvaluatedInLegacySuggestions() {
        for (String status : Arrays.asList(
                "FAILED", "TIMEOUT", "DISABLED", "ASSEMBLY_UNSUPPORTED"
        )) {
            VariantInterpretation variant = variant("missense_variant", gnomad(status, null));

            assertEquals("NOT_EVALUATED", suggestion(variant, "BA1").getStatus(), status);
            assertEquals("NOT_EVALUATED", suggestion(variant, "BS1").getStatus(), status);
            assertEquals("NOT_EVALUATED", suggestion(variant, "PM2_candidate").getStatus(), status);
        }
    }

    @Test
    void lofLikeConsequenceSuggestsPvs1CandidateOnly() {
        VariantInterpretation variant = variant("frameshift_variant", gnomad("APPLICABLE", 0.0001d));

        AcmgEvidenceSuggestion pvs1 = suggestion(variant, "PVS1_candidate");

        assertEquals("SUGGESTED", pvs1.getStatus());
        assertEquals("VERY_STRONG", pvs1.getStrength());
        assertTrue(pvs1.isManualReviewRequired());
        assertTrue(pvs1.getExplanation().contains("candidate only"));
    }

    @Test
    void lofteeHighConfidenceEnhancesPvs1CandidateExplanation() {
        VariantInterpretation variant = variant(
                "missense_variant",
                Collections.singletonList(lofteeAnnotation("stop_gained", "HC")),
                gnomad("APPLICABLE", 0.0001d)
        );

        AcmgEvidenceSuggestion pvs1 = suggestion(variant, "PVS1_candidate");

        assertEquals("SUGGESTED", pvs1.getStatus());
        assertTrue(pvs1.getExplanation().contains("LOFTEE high-confidence"));
    }

    @Test
    void multipleDamagingPredictorsSuggestPp3Candidate() {
        VariantInterpretation variant = variant(
                "missense_variant",
                cadd(26.0d),
                alphaMissense(0.9d)
        );

        assertEquals("SUGGESTED", suggestion(variant, "PP3_candidate").getStatus());
        assertEquals("NOT_SUGGESTED", suggestion(variant, "BP4_candidate").getStatus());
    }

    @Test
    void singleDamagingPredictorIsNotEnoughForPp3Candidate() {
        VariantInterpretation variant = variant("missense_variant", cadd(26.0d));

        assertEquals("NOT_EVALUATED", suggestion(variant, "PP3_candidate").getStatus());
    }

    @Test
    void benignComputationalPatternSuggestsBp4Candidate() {
        VariantInterpretation variant = variant(
                "missense_variant",
                cadd(9.0d),
                alphaMissense(0.1d),
                spliceAi("APPLICABLE", 0.01d)
        );

        assertEquals("SUGGESTED", suggestion(variant, "BP4_candidate").getStatus());
    }

    @Test
    void synonymousLowSpliceSuggestsBp7Candidate() {
        VariantInterpretation variant = variant(
                "synonymous_variant",
                spliceAi("APPLICABLE", 0.02d)
        );

        AcmgEvidenceSuggestion bp7 = suggestion(variant, "BP7_candidate");

        assertEquals("SUGGESTED", bp7.getStatus());
        assertTrue(bp7.isManualReviewRequired());
    }

    @Test
    void spliceUnavailableDoesNotSuggestBp7Candidate() {
        VariantInterpretation variant = variant(
                "synonymous_variant",
                spliceAi("UNAVAILABLE", null)
        );

        assertEquals("NOT_EVALUATED", suggestion(variant, "BP7_candidate").getStatus());
    }

    @Test
    void disabledMapperLeavesInterpretationsUntouched() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getAcmgReady().setEnabled(false);
        AcmgReadyEvidenceMapper disabledMapper = new AcmgReadyEvidenceMapper(properties);
        VariantInterpretation variant = variant("missense_variant", gnomad("APPLICABLE", 0.12d));

        disabledMapper.apply(Collections.singletonList(variant));

        assertTrue(variant.getAcmgEvidenceSuggestions().isEmpty());
        assertTrue(disabledMapper.suggestions(variant).isEmpty());
    }

    @Test
    void bulkApplySkipsLargeFileSummaryOnlyInterpretations() {
        VariantInterpretation candidate = variant("frameshift_variant", gnomad("APPLICABLE", 0.0001d));
        VariantInterpretation summaryOnly = variant("frameshift_variant", gnomad("APPLICABLE", 0.0001d));
        summaryOnly.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_NOT_CARRIED,
                true,
                true,
                true,
                "LARGE_FILE_NOT_CARRIED_ALT",
                Collections.singletonList("large-file summary")
        );

        mapper.apply(Arrays.asList(candidate, summaryOnly));

        assertFalse(candidate.getAcmgEvidenceSuggestions().isEmpty());
        assertTrue(summaryOnly.getAcmgEvidenceSuggestions().isEmpty());
    }

    private AcmgEvidenceSuggestion suggestion(VariantInterpretation variant, String code) {
        for (AcmgEvidenceSuggestion suggestion : mapper.suggestions(variant)) {
            if (code.equals(suggestion.getCode())) {
                return suggestion;
            }
        }
        throw new AssertionError("Missing suggestion: " + code);
    }

    private VariantInterpretation variant(String consequence, EvidenceItem... evidence) {
        return variant(consequence, Collections.emptyList(), evidence);
    }

    private VariantInterpretation variant(String consequence,
                                          List<TranscriptConsequence> annotations,
                                          EvidenceItem... evidence) {
        VariantInterpretation variant = new VariantInterpretation(
                "GRCh38:1:100:A:G",
                observation(),
                Collections.singletonList(
                        new TranscriptConsequence("GENE1", null, "NM_000001.1", null, null, consequence)
                ),
                Arrays.asList(evidence)
        );
        variant.applyTranscriptAnnotations(annotations);
        return variant;
    }

    private VariantQcObservation observation() {
        return new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "G"),
                "proband",
                "0/1",
                30,
                99,
                "16,14",
                0.46d,
                "PASS",
                "SNV",
                "chr1\t100\t.\tA\tG"
        );
    }

    private EvidenceItem gnomad(String status, Double score) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        if (score != null) {
            details.put("populationMaximumAlleleFrequency", score);
        }
        return new EvidenceItem(
                "GNOMAD",
                "test",
                "POPULATION",
                status,
                score,
                "Maximum allele frequency",
                "gnomAD local allele frequency",
                details
        );
    }

    private EvidenceItem clinVar(String status, String label) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("clinicalSignificance", label);
        return new EvidenceItem(
                "CLINVAR",
                "test",
                "CLINICAL",
                status,
                null,
                label,
                "ClinVar local record",
                details
        );
    }

    private EvidenceItem cadd(Double score) {
        return scoreEvidence("CADD", "CADD PHRED", score);
    }

    private EvidenceItem alphaMissense(Double score) {
        return scoreEvidence("ALPHAMISSENSE", "AlphaMissense", score);
    }

    private EvidenceItem spliceAi(String status, Double score) {
        return new EvidenceItem(
                "SPLICEAI",
                "test",
                "SPLICE",
                status,
                score,
                "SpliceAI",
                "SpliceAI max delta",
                Collections.emptyMap()
        );
    }

    private EvidenceItem scoreEvidence(String source, String label, Double score) {
        return new EvidenceItem(
                source,
                "test",
                "COMPUTATIONAL",
                "APPLICABLE",
                score,
                label,
                label,
                Collections.emptyMap()
        );
    }

    private TranscriptConsequence lofteeAnnotation(String consequence, String lof) {
        return new TranscriptConsequence(
                "GENE1",
                null,
                "ENST000001",
                null,
                null,
                consequence,
                null,
                "ENST000001",
                "VEP",
                "HIGH",
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                lof,
                null,
                null,
                "VEP_COMPUTED",
                "COMPLETED",
                "test",
                Instant.now()
        );
    }
}
