package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInputStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeRescueSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhenotypeAwareRerankingServiceTest {

    @Test
    void disabledByDefaultKeepsRankingAndSummaryUnchanged() {
        VariantInterpretation variant = reviewVariant("MECP2");
        PhenotypeAwareSummary summary = summaryWithPrior(prior("MECP2", 0.95d, 1));

        PhenotypeAwareSummary result = new PhenotypeAwareRerankingService(new VariantAnalysisProperties())
                .apply(Collections.singletonList(variant), summary);

        assertFalse(result.isRankingApplied());
        assertEquals(10.0d, variant.getRankingScore(), 0.0001d);
        assertFalse(variant.getScoreComponents().containsKey("phenotype_support"));
    }

    @Test
    void appliesPhenotypeSupportToReviewCandidateAndUpdatesSummary() {
        VariantInterpretation variant = reviewVariant("MECP2");
        PhenotypeAwareSummary summary = summaryWithPrior(prior("MECP2", 0.95d, 1));

        PhenotypeAwareSummary result = enabledReranker().apply(Collections.singletonList(variant), summary);

        assertTrue(result.isRankingApplied());
        assertEquals(1, result.getCandidatesWithPhenotypeSupport());
        assertEquals("MECP2", result.getTopPhenotypeSupportedCandidates().get(0).getGeneSymbol());
        assertFalse(result.getWarnings().contains(PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING));
        assertTrue(String.valueOf(result.getWarnings()).contains("candidate prioritization only"));
        assertEquals(17.2d, variant.getRankingScore(), 0.0001d);
        assertEquals(7.2d, variant.getScoreBreakdown().get("phenotype_support"), 0.0001d);
        assertTrue(String.valueOf(variant.getScoreComponents().get("phenotype_support").getReasons())
                .contains("phenotype prior rank 1"));
        assertTrue(String.valueOf(variant.getPositiveReasons()).contains("Gene MECP2 is supported"));
        assertTrue(String.valueOf(variant.getWarnings()).contains("not a diagnosis"));
        EvidenceItem evidence = evidence(variant, "LOCAL_GENE_PRIOR");
        assertEquals("PHENOTYPE_GENE_PRIOR", evidence.getEvidenceType());
        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals(0.95d, evidence.getRawScore(), 0.0001d);
        assertEquals(Arrays.asList("HP:0001250"), evidence.getDetails().get("matchedHpoTerms"));
        assertEquals(8.0d, (Double) evidence.getDetails().get("baseScoreBoost"), 0.0001d);
        assertEquals(0.9d, (Double) evidence.getDetails().get("provenanceConfidenceFactor"), 0.0001d);
        assertEquals(1.0d, (Double) evidence.getDetails().get("matchedTermCoverageFactor"), 0.0001d);
    }

    @Test
    void confidenceAndMatchedTermCoverageBoundAiSuggestedBoost() {
        VariantInterpretation variant = reviewVariant("MECP2");
        GenePhenotypePrior prior = prior("MECP2", 0.95d, 1);
        List<PhenotypeInput> inputs = Arrays.asList(
                input("HP:0001250", "AI_RECOMMENDATION", 0.98d),
                input("HP:0001298", "MANUAL_CONFIRMED", 1.0d),
                input("HP:0001695", "MANUAL_CONFIRMED", 1.0d),
                input("HP:0002637", "MANUAL_CONFIRMED", 1.0d)
        );

        PhenotypeAwareSummary result = enabledReranker().apply(
                Collections.singletonList(variant),
                summaryWithPriorAndInputs(prior, inputs)
        );

        assertTrue(result.isRankingApplied());
        assertEquals(13.75d, variant.getRankingScore(), 0.0001d);
        EvidenceItem evidence = evidence(variant, "LOCAL_GENE_PRIOR");
        assertEquals(0.75d,
                (Double) evidence.getDetails().get("provenanceConfidenceFactor"), 0.0001d);
        assertEquals(0.625d,
                (Double) evidence.getDetails().get("matchedTermCoverageFactor"), 0.0001d);
        assertEquals(3.75d, (Double) evidence.getDetails().get("scoreBoost"), 0.0001d);
    }

    @Test
    void explicitlyAbsentInputsNeverEnterRerankingCoverageOrMatches() {
        VariantInterpretation variant = reviewVariant("MECP2");
        PhenotypeInput positive = input("HP:0001298", "MANUAL_CONFIRMED", 1.0d);
        PhenotypeInput excluded = new PhenotypeInput(
                "HP:0001250",
                "Explicitly absent",
                "MANUAL_CONFIRMED",
                PhenotypeInputStatus.VALID,
                1.0d,
                "HP:0001250",
                Collections.emptyList(),
                "HP:0001250",
                null,
                "Explicitly absent",
                HpoOntologyTermStatus.CURRENT,
                true
        );
        PhenotypeAwareSummary summary = new PhenotypeAwareSummary(
                1,
                1,
                0,
                0,
                Collections.singletonList("HP:0001298"),
                Collections.emptyList(),
                Collections.singletonList(PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING),
                Arrays.asList(positive, excluded),
                Collections.singletonList("HP:0001250"),
                true,
                false,
                summary(GenePhenotypePriorStatus.AVAILABLE,
                        Collections.singletonList(prior("MECP2", 0.95d, 1))),
                0,
                Collections.emptyList(),
                PhenotypeRescueSummary.disabled()
        );

        enabledReranker().apply(Collections.singletonList(variant), summary);

        EvidenceItem evidence = evidence(variant, "LOCAL_GENE_PRIOR");
        assertEquals(0, evidence.getDetails().get("matchedInputCount"));
        assertEquals(1, evidence.getDetails().get("validInputCount"));
        assertEquals(0.5d,
                (Double) evidence.getDetails().get("matchedTermCoverageFactor"), 0.0001d);
    }

    @Test
    void confidenceBelowSafetyThresholdDoesNotChangeRanking() {
        VariantInterpretation variant = reviewVariant("MECP2");
        PhenotypeAwareSummary summary = summaryWithPriorAndInputs(
                prior("MECP2", 0.95d, 1),
                Collections.singletonList(input("HP:0001250", "AI_RECOMMENDATION", 0.30d))
        );

        PhenotypeAwareSummary result = enabledReranker().apply(
                Collections.singletonList(variant), summary
        );

        assertFalse(result.isRankingApplied());
        assertEquals(10.0d, variant.getRankingScore(), 0.0001d);
        assertFalse(variant.getScoreComponents().containsKey("phenotype_support"));
    }

    @Test
    void phenotypeSupportCanMoveCandidateAheadWithinSameTierByScore() {
        VariantInterpretation supported = reviewVariant("MECP2");
        VariantInterpretation unsupported = reviewVariant("OTHER");
        supported.applyRanking("REVIEW", 10.0d, Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyList());
        unsupported.applyRanking("REVIEW", 12.0d, Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyList());

        enabledReranker().apply(
                Arrays.asList(supported, unsupported),
                summaryWithPrior(prior("MECP2", 0.95d, 1))
        );

        assertEquals("REVIEW", supported.getPriorityTier());
        assertEquals("REVIEW", unsupported.getPriorityTier());
        assertTrue(supported.getRankingScore() > unsupported.getRankingScore());
        assertFalse(unsupported.getScoreComponents().containsKey("phenotype_support"));
    }

    @Test
    void hardGateTiersAreNotPromotedByPhenotypeSupport() {
        VariantInterpretation common = variant(
                "PASS",
                "0/1",
                30,
                99,
                0.4d,
                "MECP2",
                "missense_variant",
                gnomad("APPLICABLE", 0.5d),
                cadd(40.0d)
        );
        VariantInterpretation qc = variant(
                "q10",
                "0/1",
                5,
                10,
                0.4d,
                "MECP2",
                "missense_variant",
                gnomad("APPLICABLE", 0.00001d),
                cadd(40.0d)
        );
        VariantInterpretation referenceGenotype = variant(
                "PASS",
                "0/0",
                30,
                99,
                0.0d,
                "MECP2",
                "missense_variant",
                clinVar("Likely_pathogenic")
        );
        VariantInterpretation benign = variant(
                "PASS",
                "0/1",
                30,
                99,
                0.4d,
                "MECP2",
                "missense_variant",
                clinVar("Likely_benign"),
                gnomad("APPLICABLE", 0.2d)
        );
        new TransparentVariantRankingService(new VariantAnalysisProperties())
                .rank(Arrays.asList(common, qc, referenceGenotype, benign));

        PhenotypeAwareSummary result = enabledReranker().apply(
                Arrays.asList(common, qc, referenceGenotype, benign),
                summaryWithPrior(prior("MECP2", 0.95d, 1))
        );

        assertFalse(result.isRankingApplied());
        assertEquals("FILTERED_COMMON", common.getPriorityTier());
        assertEquals("FILTERED_QC", qc.getPriorityTier());
        assertEquals("FILTERED_QC", referenceGenotype.getPriorityTier());
        assertEquals("BENIGN_LIKELY", benign.getPriorityTier());
        for (VariantInterpretation variant : Arrays.asList(common, qc, referenceGenotype, benign)) {
            assertFalse(variant.getScoreComponents().containsKey("phenotype_support"));
            assertFalse(String.valueOf(variant.getPositiveReasons()).contains("phenotype prior"));
        }
    }

    @Test
    void noPriorOrUnavailableDoNotCreateNegativeOrLowRiskSignals() {
        VariantInterpretation noPriorVariant = reviewVariant("MECP2");
        VariantInterpretation unavailableVariant = reviewVariant("MECP2");
        PhenotypeAwareSummary noPriorSummary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001250")
        ).withGenePriorSummary(summary(GenePhenotypePriorStatus.NO_PRIOR, Collections.<GenePhenotypePrior>emptyList()));
        PhenotypeAwareSummary unavailableSummary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001250")
        ).withGenePriorSummary(summary(GenePhenotypePriorStatus.UNAVAILABLE, Collections.<GenePhenotypePrior>emptyList()));

        PhenotypeAwareSummary noPriorResult = enabledReranker().apply(
                Collections.singletonList(noPriorVariant),
                noPriorSummary
        );
        PhenotypeAwareSummary unavailableResult = enabledReranker().apply(
                Collections.singletonList(unavailableVariant),
                unavailableSummary
        );

        assertFalse(noPriorResult.isRankingApplied());
        assertFalse(unavailableResult.isRankingApplied());
        assertFalse(noPriorVariant.getNegativeReasons().toString().toLowerCase().contains("benign"));
        assertFalse(unavailableVariant.getNegativeReasons().toString().toLowerCase().contains("low risk"));
        assertFalse(noPriorVariant.getScoreComponents().containsKey("phenotype_support"));
        assertFalse(unavailableVariant.getScoreComponents().containsKey("phenotype_support"));
    }

    @Test
    void availablePriorWithoutCandidateGeneAddsLinkageWarningOnly() {
        VariantInterpretation naked = variant(
                "PASS",
                "0/1",
                30,
                99,
                0.4d,
                null,
                "missense_variant",
                gnomad("APPLICABLE", 0.0001d)
        );
        naked.applyRanking(
                "REVIEW",
                10.0d,
                Collections.singletonMap("base", 10.0d),
                Collections.emptyMap(),
                "Base review tier",
                Collections.singletonList("Base positive reason"),
                Collections.emptyList(),
                Collections.singletonList("Base rationale"),
                Collections.emptyList(),
                Collections.emptyList()
        );

        PhenotypeAwareSummary result = enabledReranker().apply(
                Collections.singletonList(naked),
                summaryWithPrior(prior("MECP2", 0.95d, 1))
        );

        assertFalse(result.isRankingApplied());
        assertTrue(String.valueOf(result.getWarnings()).contains("HPO_INPUT_PRESENT_BUT_NO_GENE_LINKAGE"));
        assertFalse(naked.getScoreComponents().containsKey("phenotype_support"));
        assertEquals(10.0d, naked.getRankingScore(), 0.0001d);
    }

    @Test
    void repeatedApplicationIsIdempotentAndNonFiniteBoostConfigurationIsNeutral() {
        VariantInterpretation variant = reviewVariant("GENE1");
        PhenotypeAwareSummary summary = summaryWithPrior(prior("GENE1", 0.95d, 1));
        PhenotypeAwareRerankingService reranker = enabledReranker();

        PhenotypeAwareSummary applied = reranker.apply(Collections.singletonList(variant), summary);
        double score = variant.getRankingScore();
        Map<String, Double> breakdown = variant.getScoreBreakdown();
        List<String> rationale = variant.getRationale();
        reranker.apply(Collections.singletonList(variant), applied);

        assertEquals(score, variant.getRankingScore());
        assertEquals(breakdown, variant.getScoreBreakdown());
        assertEquals(rationale, variant.getRationale());

        VariantAnalysisProperties invalidProperties = new VariantAnalysisProperties();
        invalidProperties.getPhenotypeRanking().setEnabled(true);
        invalidProperties.getPhenotypeRanking().setMaxScoreBoost(Double.POSITIVE_INFINITY);
        VariantInterpretation invalid = reviewVariant("GENE1");
        new PhenotypeAwareRerankingService(invalidProperties)
                .apply(Collections.singletonList(invalid), summary);
        assertEquals(10.0d, invalid.getRankingScore());
        assertFalse(invalid.getScoreBreakdown().containsKey("phenotype_support"));
    }

    private PhenotypeAwareRerankingService enabledReranker() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypeRanking().setEnabled(true);
        properties.getPhenotypeRanking().setMaxScoreBoost(8.0d);
        return new PhenotypeAwareRerankingService(properties);
    }

    private PhenotypeAwareSummary summaryWithPrior(GenePhenotypePrior prior) {
        return PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
                .withGenePriorSummary(summary(GenePhenotypePriorStatus.AVAILABLE, Collections.singletonList(prior)));
    }

    private PhenotypeAwareSummary summaryWithPriorAndInputs(GenePhenotypePrior prior,
                                                            List<PhenotypeInput> inputs) {
        List<String> validTerms = new java.util.ArrayList<String>();
        for (PhenotypeInput input : inputs) {
            validTerms.add(input.getHpoId());
        }
        return new PhenotypeAwareSummary(
                inputs.size(),
                inputs.size(),
                0,
                0,
                validTerms,
                Collections.emptyList(),
                Collections.singletonList(PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING),
                inputs,
                true,
                false,
                summary(GenePhenotypePriorStatus.AVAILABLE, Collections.singletonList(prior))
        );
    }

    private PhenotypeInput input(String hpoId, String source, double confidence) {
        return new PhenotypeInput(
                hpoId,
                hpoId,
                source,
                PhenotypeInputStatus.VALID,
                confidence,
                hpoId,
                Collections.emptyList()
        );
    }

    private GenePhenotypePriorSummary summary(GenePhenotypePriorStatus status, List<GenePhenotypePrior> priors) {
        return new GenePhenotypePriorSummary(
                true,
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                status,
                1,
                1,
                priors.size(),
                priors,
                Collections.emptyList(),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private GenePhenotypePrior prior(String gene, double score, int rank) {
        return new GenePhenotypePrior(
                gene,
                "ENSG00000169057",
                score,
                rank,
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                Collections.singletonList("HP:0001250"),
                Collections.singletonList("HP:0001250: Mock phenotype-gene association"),
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Collections.emptyList()
        );
    }

    private VariantInterpretation reviewVariant(String gene) {
        VariantInterpretation variant = variant(
                "PASS",
                "0/1",
                30,
                99,
                0.4d,
                gene,
                "missense_variant",
                gnomad("APPLICABLE", 0.0001d)
        );
        variant.applyRanking(
                "REVIEW",
                10.0d,
                Collections.singletonMap("base", 10.0d),
                Collections.emptyMap(),
                "Base review tier",
                Collections.singletonList("Base positive reason"),
                Collections.emptyList(),
                Collections.singletonList("Base rationale"),
                Collections.emptyList(),
                Collections.emptyList()
        );
        return variant;
    }

    private VariantInterpretation variant(String filterStatus,
                                          String genotype,
                                          Integer depth,
                                          Integer genotypeQuality,
                                          Double alleleBalance,
                                          String gene,
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
                "GRCh38:1:100:A:G:" + gene + ":" + filterStatus + ":" + genotype,
                observation,
                Collections.singletonList(new TranscriptConsequence(gene, null, null, null, null, consequence)),
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

    private EvidenceItem evidence(VariantInterpretation variant, String source) {
        for (EvidenceItem item : variant.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
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
