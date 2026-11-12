package org.nbscn.varvis.variantanalysis.ranking;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransparentVariantRankingServiceTest {

    @Test
    void clinVarPathogenicLowFrequencyVariantBecomesKnownClinical() {
        VariantInterpretation variant = variant(
                "missense_variant",
                clinVar("Likely_pathogenic"),
                gnomad("APPLICABLE", 0.00001d),
                cadd(24.3d)
        );

        rank(variant);

        assertEquals("KNOWN_CLINICAL", variant.getPriorityTier());
        assertEquals("KNOWN_CLINICAL", variant.getTier());
        assertEquals(variant.getRankingScore(), variant.getTotalScore());
        assertTrue(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("ClinVar")));
        assertTrue(variant.getScoreComponents().containsKey("clinical_evidence"));
        assertNotNull(variant.getTierReason());
    }

    @Test
    void clinVarBenignVariantIsStronglyDeprioritized() {
        VariantInterpretation variant = variant(
                "missense_variant",
                clinVar("Likely_benign"),
                gnomad("APPLICABLE", 0.2d),
                cadd(30.0d)
        );

        rank(variant);

        assertEquals("BENIGN_LIKELY", variant.getPriorityTier());
        assertTrue(variant.getNegativeReasons().stream().anyMatch(value -> value.contains("benign")));
    }

    @Test
    void commonGnomadVariantWithoutClinVarPathogenicIsFiltered() {
        VariantInterpretation variant = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.02d),
                cadd(12.0d)
        );

        rank(variant);

        assertEquals("FILTERED_COMMON", variant.getPriorityTier());
        assertTrue(variant.getFilterReasons().stream().anyMatch(value -> value.contains("AF_popmax")));
    }

    @Test
    void commonGnomadVariantWithHighCaddDoesNotBecomeTopCandidate() {
        VariantInterpretation variant = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.5d),
                cadd(40.0d)
        );

        rank(variant);

        assertEquals("FILTERED_COMMON", variant.getPriorityTier());
        assertTrue(variant.getRankingScore() < -1000.0d);
        assertTrue(variant.getNegativeReasons().stream().anyMatch(value -> value.contains("gnomAD")));
    }

    @Test
    void clinVarVusGoesToReviewNotPathogenic() {
        VariantInterpretation variant = variant(
                "missense_variant",
                clinVar("Uncertain_significance"),
                gnomad("APPLICABLE", 0.0001d)
        );

        rank(variant);

        assertEquals("REVIEW", variant.getPriorityTier());
        assertFalse(variant.getTierReason().toLowerCase().contains("pathogenic/likely pathogenic"));
    }

    @Test
    void clinVarConflictingGoesToReviewWithWarning() {
        VariantInterpretation variant = variant(
                "missense_variant",
                evidence("CLINVAR", "CONFLICTING", null, "Conflicting_interpretations_of_pathogenicity")
        );

        rank(variant);

        assertEquals("REVIEW", variant.getPriorityTier());
        assertTrue(variant.getWarnings().stream().anyMatch(value -> value.contains("conflicting")));
    }

    @Test
    void gnomadNoRecordIsNotReportedAsConfirmedRare() {
        VariantInterpretation variant = variant(
                "missense_variant",
                gnomad("NO_RECORD", null),
                cadd(18.0d)
        );

        rank(variant);

        assertEquals("REVIEW", variant.getPriorityTier());
        assertTrue(variant.getRationale().stream().anyMatch(value -> value.contains("not interpreted as AF=0")));
        assertFalse(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("confirmed rarity")));
    }

    @Test
    void gnomadUnavailableDoesNotApplyPenalty() {
        VariantInterpretation variant = variant(
                "missense_variant",
                gnomad("UNAVAILABLE", null),
                cadd(10.0d)
        );

        rank(variant);

        assertEquals("UNRESOLVED", variant.getPriorityTier());
        assertEquals(0.0d, variant.getScoreBreakdown().get("population_frequency"));
        assertTrue(variant.getWarnings().stream().anyMatch(value -> value.contains("UNAVAILABLE")));
    }

    @Test
    void unifiedEvidenceMetadataDoesNotChangeRanking() {
        EvidenceItem legacy = gnomad("APPLICABLE", 0.00001d);
        EvidenceItem contextualized = legacy.withVariantContext("GRCh38|1|100|A|G", "GRCh38");
        VariantInterpretation baseline = variant("missense_variant", legacy, cadd(24.3d));
        VariantInterpretation unified = variant("missense_variant", contextualized, cadd(24.3d));

        rank(baseline);
        rank(unified);

        assertSameRanking(baseline, unified);
        assertEquals(baseline.getPositiveReasons(), unified.getPositiveReasons());
        assertEquals(baseline.getWarnings(), unified.getWarnings());
    }

    @Test
    void failedFilterOrLowDepthQualityMovesVariantToFilteredQc() {
        VariantInterpretation variant = variant(
                "q10",
                "0/1",
                5,
                10,
                0.4d,
                "missense_variant",
                gnomad("APPLICABLE", 0.0001d),
                cadd(35.0d)
        );

        rank(variant);

        assertEquals("FILTERED_QC", variant.getPriorityTier());
        assertTrue(variant.getFilterReasons().stream().anyMatch(value -> value.contains("FILTER")));
        assertTrue(variant.getFilterReasons().stream().anyMatch(value -> value.contains("DP")));
        assertTrue(variant.getFilterReasons().stream().anyMatch(value -> value.contains("GQ")));
    }

    @Test
    void referenceGenotypeMovesVariantToFilteredQc() {
        VariantInterpretation variant = variant(
                "PASS",
                "0/0",
                30,
                99,
                0.0d,
                "missense_variant",
                clinVar("Likely_pathogenic")
        );

        rank(variant);

        assertEquals("FILTERED_QC", variant.getPriorityTier());
        assertTrue(variant.getFilterReasons().stream().anyMatch(value -> value.contains("GT=0/0")));
    }

    @Test
    void highImpactLowFrequencyVariantBecomesHighPriority() {
        VariantInterpretation variant = variant(
                "frameshift_variant",
                gnomad("APPLICABLE", 0.00001d)
        );

        rank(variant);

        assertEquals("HIGH_PRIORITY", variant.getPriorityTier());
        assertTrue(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("High-impact")));
    }

    @Test
    void nearbySpliceAndNonCodingConsequencesDoNotBecomeHighPriorityWithoutSpliceEvidence() {
        VariantInterpretation variant = variant(
                "upstream_gene_variant&splice_donor_5th_base_variant",
                gnomad("UNAVAILABLE", null)
        );

        rank(variant);

        assertEquals("UNRESOLVED", variant.getPriorityTier());
        assertFalse(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("High-impact")));
        assertTrue(variant.getWarnings().stream().anyMatch(value -> value.contains("UNAVAILABLE")));
    }

    @Test
    void nonCodingVepFrameshiftAnnotationDoesNotBecomeHighPriority() {
        VariantInterpretation variant = variantWithTranscriptAnnotation(
                "INDEL",
                "frameshift_variant",
                "snRNA",
                gnomad("UNAVAILABLE", null)
        );

        rank(variant);

        assertEquals("UNRESOLVED", variant.getPriorityTier());
        assertFalse(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("High-impact")));
    }

    @Test
    void cachedVepFrameshiftWithoutBiotypeOrProteinDetailDoesNotBecomeHighPriority() {
        VariantInterpretation variant = variantWithTranscriptAnnotation(
                "INDEL",
                "frameshift_variant",
                null,
                gnomad("UNAVAILABLE", null)
        );

        rank(variant);

        assertEquals("UNRESOLVED", variant.getPriorityTier());
        assertFalse(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("High-impact")));
    }

    @Test
    void proteinCodingVepFrameshiftWithoutProteinDetailDoesNotBecomeHighPriority() {
        VariantInterpretation variant = variantWithTranscriptAnnotation(
                "INDEL",
                "frameshift_variant",
                "protein_coding",
                false,
                gnomad("UNAVAILABLE", null)
        );

        rank(variant);

        assertEquals("UNRESOLVED", variant.getPriorityTier());
        assertFalse(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("High-impact")));
    }

    @Test
    void proteinCodingVepFrameshiftAnnotationRemainsHighPriority() {
        VariantInterpretation variant = variantWithTranscriptAnnotation(
                "INDEL",
                "frameshift_variant",
                "protein_coding",
                gnomad("UNAVAILABLE", null)
        );

        rank(variant);

        assertEquals("HIGH_PRIORITY", variant.getPriorityTier());
        assertTrue(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("High-impact")));
    }

    @Test
    void compactRankingUsesExactHighImpactConsequenceTerms() {
        VariantInterpretation nearbySplice = variant(
                "splice_donor_5th_base_variant",
                gnomad("UNAVAILABLE", null)
        );
        VariantInterpretation missense = variant(
                "missense_variant",
                gnomad("UNAVAILABLE", null)
        );
        VariantInterpretation nonCodingFrameshift = variantWithTranscriptAnnotation(
                "INDEL",
                "frameshift_variant",
                "snRNA",
                gnomad("UNAVAILABLE", null)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getRanking().setCompactModeThreshold(1);
        properties.getRanking().setDetailedMaxCandidates(0);

        new TransparentVariantRankingService(properties)
                .rank(Arrays.asList(nearbySplice, missense, nonCodingFrameshift));

        assertEquals("UNRESOLVED", nearbySplice.getPriorityTier());
        assertFalse("HIGH_PRIORITY".equals(nearbySplice.getPriorityTier()));
        assertEquals("UNRESOLVED", nonCodingFrameshift.getPriorityTier());
    }

    @Test
    void synonymousVariantWithoutSpliceEvidenceIsNotOverElevated() {
        VariantInterpretation variant = variant(
                "synonymous_variant",
                gnomad("APPLICABLE", 0.00001d),
                cadd(25.0d)
        );

        rank(variant);

        assertEquals("REVIEW", variant.getPriorityTier());
        assertTrue(variant.getNegativeReasons().stream().anyMatch(value -> value.contains("Synonymous")));
        assertTrue(variant.getScoreBreakdown().get("predicted_consequence") < 0.0d);
    }

    @Test
    void highSpliceAiSupportsReviewButDoesNotOverrideCommonFrequencyFilter() {
        VariantInterpretation variant = variant(
                "splice_donor_variant",
                gnomad("APPLICABLE", 0.5d),
                spliceAi(0.91d)
        );

        rank(variant);

        assertEquals("FILTERED_COMMON", variant.getPriorityTier());
        assertTrue(variant.getPositiveReasons().stream().anyMatch(value -> value.contains("SpliceAI")));
        assertTrue(variant.getNegativeReasons().stream().anyMatch(value -> value.contains("gnomAD")));
    }

    @Test
    void everyRankedVariantReturnsBreakdownAndReasonLists() {
        VariantInterpretation variant = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.00001d),
                cadd(16.0d)
        );

        rank(variant);

        assertFalse(variant.getScoreBreakdown().isEmpty());
        assertFalse(variant.getScoreComponents().isEmpty());
        assertNotNull(variant.getTierReason());
        assertFalse(variant.getRationale().isEmpty());
        assertFalse(variant.getPositiveReasons().isEmpty());
    }

    @Test
    void filteredCandidateIncludesFilterReason() {
        VariantInterpretation variant = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.1d)
        );

        rank(variant);

        assertEquals("FILTERED_COMMON", variant.getPriorityTier());
        assertFalse(variant.getFilterReasons().isEmpty());
    }

    @Test
    void largeAnalysisUsesCompactRankingAndKeepsDetailedBreakdownForTopCandidatesOnly() {
        VariantInterpretation common = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.5d),
                cadd(40.0d)
        );
        VariantInterpretation knownClinical = variant(
                "missense_variant",
                clinVar("Pathogenic"),
                gnomad("APPLICABLE", 0.00001d)
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getRanking().setCompactModeThreshold(1);
        properties.getRanking().setDetailedMaxCandidates(1);

        new TransparentVariantRankingService(properties)
                .rank(Arrays.asList(common, knownClinical));

        assertEquals("FILTERED_COMMON", common.getPriorityTier());
        assertEquals("KNOWN_CLINICAL", knownClinical.getPriorityTier());
        assertTrue(common.getScoreComponents().isEmpty());
        assertFalse(knownClinical.getScoreComponents().isEmpty());
    }

    @Test
    void phenotypePriorConfigurationDoesNotChangeRankingOrOverrideHardGates() {
        VariantInterpretation baselineCommon = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.5d),
                cadd(40.0d)
        );
        VariantInterpretation baselineQc = variant(
                "q10",
                "0/1",
                5,
                10,
                0.4d,
                "missense_variant",
                gnomad("APPLICABLE", 0.00001d),
                cadd(40.0d)
        );
        VariantInterpretation baselineReferenceGenotype = variant(
                "PASS",
                "0/0",
                30,
                99,
                0.0d,
                "missense_variant",
                clinVar("Likely_pathogenic"),
                gnomad("APPLICABLE", 0.00001d)
        );
        new TransparentVariantRankingService(new VariantAnalysisProperties()).rank(
                Arrays.asList(baselineCommon, baselineQc, baselineReferenceGenotype)
        );

        VariantInterpretation phenotypeCommon = variant(
                "missense_variant",
                gnomad("APPLICABLE", 0.5d),
                cadd(40.0d)
        );
        VariantInterpretation phenotypeQc = variant(
                "q10",
                "0/1",
                5,
                10,
                0.4d,
                "missense_variant",
                gnomad("APPLICABLE", 0.00001d),
                cadd(40.0d)
        );
        VariantInterpretation phenotypeReferenceGenotype = variant(
                "PASS",
                "0/0",
                30,
                99,
                0.0d,
                "missense_variant",
                clinVar("Likely_pathogenic"),
                gnomad("APPLICABLE", 0.00001d)
        );
        VariantAnalysisProperties phenotypeProperties = new VariantAnalysisProperties();
        phenotypeProperties.getPhenotypePrior().setEnabled(true);
        phenotypeProperties.getPhenotypePrior().setSource("LOCAL_GENE_PRIOR");
        phenotypeProperties.getPhenotypePrior().setVersion("mock-v1");
        phenotypeProperties.getPhenotypePrior().setLocalFile("mock-gene-prior.tsv");
        new TransparentVariantRankingService(phenotypeProperties).rank(
                Arrays.asList(phenotypeCommon, phenotypeQc, phenotypeReferenceGenotype)
        );

        assertSameRanking(baselineCommon, phenotypeCommon);
        assertSameRanking(baselineQc, phenotypeQc);
        assertSameRanking(baselineReferenceGenotype, phenotypeReferenceGenotype);
        assertEquals("FILTERED_COMMON", phenotypeCommon.getPriorityTier());
        assertEquals("FILTERED_QC", phenotypeQc.getPriorityTier());
        assertEquals("FILTERED_QC", phenotypeReferenceGenotype.getPriorityTier());
        assertFalse(phenotypeCommon.getPriorityTier().equals("HIGH_PRIORITY"));
        assertFalse(phenotypeQc.getPriorityTier().equals("REVIEW"));
    }

    private void rank(VariantInterpretation variant) {
        new TransparentVariantRankingService(new VariantAnalysisProperties())
                .rank(Collections.singletonList(variant));
    }

    private void assertSameRanking(VariantInterpretation baseline, VariantInterpretation withPhenotypePriorConfig) {
        assertEquals(baseline.getPriorityTier(), withPhenotypePriorConfig.getPriorityTier());
        assertEquals(baseline.getTier(), withPhenotypePriorConfig.getTier());
        assertEquals(baseline.getRankingScore(), withPhenotypePriorConfig.getRankingScore(), 0.0001d);
        assertEquals(baseline.getScoreBreakdown(), withPhenotypePriorConfig.getScoreBreakdown());
        assertEquals(baseline.getFilterReasons(), withPhenotypePriorConfig.getFilterReasons());
    }

    private VariantInterpretation variant(String consequence, EvidenceItem... evidence) {
        return variant("PASS", "0/1", 30, 99, 0.4d, consequence, evidence);
    }

    private VariantInterpretation variantWithTranscriptAnnotation(String fallbackConsequence,
                                                                  String annotationConsequence,
                                                                  String transcriptBiotype,
                                                                  EvidenceItem... evidence) {
        boolean includeProteinDetail = "protein_coding".equals(transcriptBiotype);
        return variantWithTranscriptAnnotation(
                fallbackConsequence,
                annotationConsequence,
                transcriptBiotype,
                includeProteinDetail,
                evidence
        );
    }

    private VariantInterpretation variantWithTranscriptAnnotation(String fallbackConsequence,
                                                                  String annotationConsequence,
                                                                  String transcriptBiotype,
                                                                  boolean includeProteinDetail,
                                                                  EvidenceItem... evidence) {
        VariantInterpretation variant = variant(fallbackConsequence, evidence);
        variant.applyTranscriptAnnotations(Collections.singletonList(new TranscriptConsequence(
                "GENE1",
                null,
                "ENST000001",
                null,
                null,
                annotationConsequence,
                "ENSG000001",
                "ENST000001",
                transcriptBiotype,
                "HIGH",
                null,
                null,
                null,
                null,
                null,
                includeProteinDetail ? "12" : null,
                null,
                null,
                null,
                null,
                null,
                "VEP",
                "COMPLETED",
                "test",
                null
        )));
        return variant;
    }

    private VariantInterpretation variant(String filterStatus,
                                          String genotype,
                                          Integer depth,
                                          Integer genotypeQuality,
                                          Double alleleBalance,
                                          String consequence,
                                          EvidenceItem... evidence) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "G"),
                "proband",
                genotype,
                depth,
                genotypeQuality,
                "18,12",
                alleleBalance,
                filterStatus,
                "SNV",
                "chr1|100|A|G|1"
        );
        return new VariantInterpretation(
                "GRCh38:1:100:A:G",
                observation,
                Collections.singletonList(new TranscriptConsequence(null, null, null, null, null, consequence)),
                Arrays.asList(evidence)
        );
    }

    private EvidenceItem clinVar(String label) {
        return evidence("CLINVAR", "APPLICABLE", null, label);
    }

    private EvidenceItem gnomad(String applicability, Double score) {
        return evidence("GNOMAD", applicability, score, score == null ? applicability : "Maximum allele frequency");
    }

    private EvidenceItem cadd(Double score) {
        return evidence("CADD", "APPLICABLE", score, "CADD PHRED");
    }

    private EvidenceItem spliceAi(Double score) {
        return evidence("SPLICEAI", "APPLICABLE", score, "SpliceAI maximum delta score");
    }

    private EvidenceItem evidence(String source, String applicability, Double score, String label) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        if ("CLINVAR".equals(source)) {
            details.put("matchType", "exact_allele");
            details.put("status", applicability);
            details.put("value", label);
        }
        if ("GNOMAD".equals(source)) {
            details.put("status", applicability);
            details.put("value", score);
        }
        return new EvidenceItem(
                source,
                "test",
                evidenceType(source),
                applicability,
                score,
                label,
                label + " test evidence",
                details
        );
    }

    private String evidenceType(String source) {
        if ("CLINVAR".equals(source)) {
            return "CLINICAL";
        }
        if ("GNOMAD".equals(source)) {
            return "POPULATION";
        }
        return "COMPUTATIONAL";
    }
}
