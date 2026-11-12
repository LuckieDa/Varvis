package org.nbscn.varvis.variantanalysis.trio.ranking;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.trio.ParentalOrigin;
import org.nbscn.varvis.variantanalysis.trio.TrioGenotypeCall;
import org.nbscn.varvis.variantanalysis.trio.TrioGenotypeObservation;
import org.nbscn.varvis.variantanalysis.trio.TrioRole;
import org.nbscn.varvis.variantanalysis.trio.TrioVariantObservation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioRankingContributionServiceTest {

    @Test
    void flagDisabledReturnsNeutralDisabledContribution() {
        TrioRankingContribution contribution = service(false, 2.0d)
                .calculate(interpretation(), trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED"));

        assertFalse(contribution.isEnabled());
        assertFalse(contribution.isImplemented());
        assertFalse(contribution.isApplied());
        assertEquals(TrioRankingContributionLevel.DISABLED, contribution.getContributionLevel());
        assertEquals(TrioRankingContributionDirection.NEUTRAL, contribution.getContributionDirection());
        assertEquals(0.0d, contribution.getContributionScore());
        assertEquals(TrioRankingNotAppliedReason.FEATURE_DISABLED, contribution.getNotAppliedReason());
        assertEquals("DE_NOVO_CANDIDATE", contribution.getModel());
        assertEquals("SUPPORTED", contribution.getConfidence());
    }

    @Test
    void flagEnabledWithZeroCapReturnsNeutralContribution() {
        TrioRankingContribution contribution = service(true, 0.0d)
                .calculate(interpretation(), trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED"));

        assertTrue(contribution.isEnabled());
        assertFalse(contribution.isApplied());
        assertEquals(TrioRankingContributionLevel.NEUTRAL, contribution.getContributionLevel());
        assertEquals(TrioRankingContributionDirection.NEUTRAL, contribution.getContributionDirection());
        assertEquals(0.0d, contribution.getContributionScore());
        assertEquals(TrioRankingNotAppliedReason.MAX_CONTRIBUTION_ZERO, contribution.getNotAppliedReason());
    }

    @Test
    void supportedDeNovoAppliesPositiveContributionWhenEnabledAndCapped() {
        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(
                        interpretation(),
                        trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                        observation(
                                TrioGenotypeCall.HET_ALT,
                                TrioGenotypeCall.HOM_REF,
                                TrioGenotypeCall.HOM_REF));

        assertTrue(contribution.isEnabled());
        assertTrue(contribution.isImplemented());
        assertTrue(contribution.isApplied());
        assertNull(contribution.getNotAppliedReason());
        assertEquals(TrioRankingContributionLevel.STRONG_BOOST, contribution.getContributionLevel());
        assertEquals(TrioRankingContributionDirection.BOOST, contribution.getContributionDirection());
        assertEquals(2.0d, contribution.getContributionScore());
    }

    @Test
    void deNovoWithUnobservedParentDoesNotBoost() {
        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(
                        interpretation(),
                        trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                        observation(
                                TrioGenotypeCall.HET_ALT,
                                TrioGenotypeCall.UNOBSERVED,
                                TrioGenotypeCall.HOM_REF));

        assertNoScoreEffect(contribution);
        assertEquals(TrioRankingNotAppliedReason.PARENT_UNOBSERVED, contribution.getNotAppliedReason());
    }

    @Test
    void deNovoWithNoCallParentDoesNotBoost() {
        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(
                        interpretation(),
                        trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                        observation(
                                TrioGenotypeCall.HET_ALT,
                                TrioGenotypeCall.NO_CALL,
                                TrioGenotypeCall.HOM_REF));

        assertNoScoreEffect(contribution);
        assertEquals(TrioRankingNotAppliedReason.PARENT_NO_CALL, contribution.getNotAppliedReason());
    }

    @Test
    void deNovoWithUnconfirmedHomRefParentDoesNotBoost() {
        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(
                        interpretation(),
                        trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                        observation(
                                TrioGenotypeCall.HET_ALT,
                                TrioGenotypeCall.UNCONFIRMED_HOM_REF,
                                TrioGenotypeCall.HOM_REF));

        assertNoScoreEffect(contribution);
        assertEquals(TrioRankingNotAppliedReason.PARENT_UNCONFIRMED_HOM_REF,
                contribution.getNotAppliedReason());
    }

    @Test
    void supportedCompoundHetComplementaryOriginAppliesPositiveContribution() {
        TrioRankingContribution contribution = service(true, 3.0d)
                .calculate(
                        interpretation(),
                        trioEvidence("COMPOUND_HETEROZYGOUS_CANDIDATE", "SUPPORTED", ParentalOrigin.BIPARENTAL));

        assertTrue(contribution.isApplied());
        assertEquals(TrioRankingContributionLevel.STRONG_BOOST, contribution.getContributionLevel());
        assertEquals(TrioRankingContributionDirection.BOOST, contribution.getContributionDirection());
        assertEquals(3.0d, contribution.getContributionScore());
    }

    @Test
    void compoundHetWithoutComplementaryPartnerDoesNotBoost() {
        TrioRankingContribution contribution = service(true, 3.0d)
                .calculate(
                        interpretation(),
                        trioEvidenceWithoutPartner(
                                "COMPOUND_HETEROZYGOUS_CANDIDATE",
                                "SUPPORTED",
                                ParentalOrigin.PATERNAL));

        assertNoScoreEffect(contribution);
        assertEquals(TrioRankingNotAppliedReason.UNSUPPORTED_MODEL, contribution.getNotAppliedReason());
    }

    @Test
    void inheritedPossibleDoesNotBoostUnlessExplicitlyAllowed() {
        TrioRankingContribution disabled = service(true, 2.0d)
                .calculate(
                        interpretation(),
                        trioEvidence("INHERITED_CANDIDATE", "POSSIBLE", ParentalOrigin.PATERNAL));

        assertNoScoreEffect(disabled);
        assertEquals(TrioRankingNotAppliedReason.LOW_CONFIDENCE, disabled.getNotAppliedReason());

        VariantAnalysisProperties properties = properties(true, 2.0d);
        properties.getTrio().setRankingAllowPossibleBoost(true);
        TrioRankingContribution enabled = new TrioRankingContributionService(properties)
                .calculate(
                        interpretation(),
                        trioEvidence("INHERITED_CANDIDATE", "POSSIBLE", ParentalOrigin.PATERNAL));

        assertTrue(enabled.isApplied());
        assertEquals(TrioRankingContributionLevel.WEAK_BOOST, enabled.getContributionLevel());
        assertEquals(TrioRankingContributionDirection.BOOST, enabled.getContributionDirection());
        assertEquals(0.5d, enabled.getContributionScore());
    }

    @Test
    void hardGatedContextReturnsHardGateProtectedNeutralContribution() {
        VariantInterpretation interpretation = interpretation();
        interpretation.applyRanking(
                "FILTERED_COMMON",
                -1000.0d,
                Collections.singletonMap("population_frequency", -1000.0d),
                Collections.emptyMap(),
                "common allele hard gate",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("common allele hard gate"),
                Collections.emptyList(),
                Collections.singletonList("AF_popmax >= 0.01"));
        interpretation.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_COMMON,
                true,
                false,
                false,
                null,
                Collections.singletonList("common allele hard gate"));

        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(
                        interpretation,
                        trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                        observation(
                                TrioGenotypeCall.HET_ALT,
                                TrioGenotypeCall.HOM_REF,
                                TrioGenotypeCall.HOM_REF));

        assertTrue(contribution.isEnabled());
        assertFalse(contribution.isImplemented());
        assertFalse(contribution.isApplied());
        assertTrue(contribution.isHardGateProtected());
        assertEquals(TrioRankingContributionLevel.NEUTRAL, contribution.getContributionLevel());
        assertEquals(TrioRankingContributionDirection.NEUTRAL, contribution.getContributionDirection());
        assertEquals(0.0d, contribution.getContributionScore());
        assertEquals(TrioRankingNotAppliedReason.HARD_GATE_PROTECTED, contribution.getNotAppliedReason());
    }

    @Test
    void hardGatedCompoundHetReturnsHardGateProtectedNeutralContribution() {
        VariantInterpretation interpretation = interpretation();
        interpretation.applyRanking(
                "BENIGN_LIKELY",
                -1000.0d,
                Collections.singletonMap("clinvar_benign", -1000.0d),
                Collections.emptyMap(),
                "ClinVar benign hard gate",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("ClinVar benign hard gate"),
                Collections.emptyList(),
                Collections.emptyList());

        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(
                        interpretation,
                        trioEvidence(
                                "COMPOUND_HETEROZYGOUS_CANDIDATE",
                                "SUPPORTED",
                                ParentalOrigin.BIPARENTAL));

        assertNoScoreEffect(contribution);
        assertTrue(contribution.isHardGateProtected());
        assertEquals(TrioRankingNotAppliedReason.HARD_GATE_PROTECTED, contribution.getNotAppliedReason());
    }

    @Test
    void contributionCapIsEnforced() {
        TrioRankingContribution contribution = service(true, 1.25d)
                .calculate(
                        interpretation(),
                        trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                        observation(
                                TrioGenotypeCall.HET_ALT,
                                TrioGenotypeCall.HOM_REF,
                                TrioGenotypeCall.HOM_REF));

        assertEquals(1.25d, contribution.getContributionScore());
    }

    @Test
    void penaltyDisabledLeavesSharedParentContextNeutral() {
        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(interpretation(), sharedParentEvidence(true, true));

        assertNoScoreEffect(contribution);
        assertEquals(TrioRankingNotAppliedReason.INSUFFICIENT_DISEASE_MODEL_CONTEXT,
                contribution.getNotAppliedReason());
    }

    @Test
    void sharedParentExplicitContextDoesNotBecomePossibleBoostWhenPenaltyFlagsAreOff() {
        VariantAnalysisProperties properties = properties(true, 2.0d);
        properties.getTrio().setRankingAllowPossibleBoost(true);

        TrioRankingContribution contribution = new TrioRankingContributionService(properties)
                .calculate(interpretation(), sharedParentEvidence(true, true));

        assertNoScoreEffect(contribution);
        assertEquals(TrioRankingNotAppliedReason.INSUFFICIENT_DISEASE_MODEL_CONTEXT,
                contribution.getNotAppliedReason());
    }

    @Test
    void sharedParentPenaltyRequiresExplicitContextAndPenaltyFlags() {
        VariantAnalysisProperties properties = properties(true, 2.0d);
        properties.getTrio().setRankingAllowNegativePenalty(true);
        properties.getTrio().setRankingEnableSharedParentPenalty(true);

        TrioRankingContribution contribution = new TrioRankingContributionService(properties)
                .calculate(interpretation(), sharedParentEvidence(true, true));

        assertTrue(contribution.isApplied());
        assertEquals(TrioRankingContributionLevel.WEAK_PENALTY, contribution.getContributionLevel());
        assertEquals(TrioRankingContributionDirection.PENALTY, contribution.getContributionDirection());
        assertEquals(-0.5d, contribution.getContributionScore());
    }

    @Test
    void repeatedApplicationReplacesExistingTrioComponentInsteadOfAccumulatingPastCap() {
        VariantInterpretation interpretation = interpretation();
        TrioRankingContributionService service = service(true, 2.0d);
        TrioRankingContribution contribution = service.calculate(
                interpretation,
                trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                observation(
                        TrioGenotypeCall.HET_ALT,
                        TrioGenotypeCall.HOM_REF,
                        TrioGenotypeCall.HOM_REF));

        service.applyContribution(interpretation, contribution);
        service.applyContribution(interpretation, contribution);

        assertEquals(12.0d, interpretation.getRankingScore());
        assertEquals(2.0d, interpretation.getScoreBreakdown()
                .get(TrioRankingContributionService.COMPONENT));
    }

    @Test
    void applyContributionUpdatesScoreBreakdownButNotTierOrBucket() {
        VariantInterpretation interpretation = interpretation();
        TrioRankingContribution contribution = service(true, 2.0d)
                .calculate(
                        interpretation,
                        trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                        observation(
                                TrioGenotypeCall.HET_ALT,
                                TrioGenotypeCall.HOM_REF,
                                TrioGenotypeCall.HOM_REF));

        service(true, 2.0d).applyContribution(interpretation, contribution);

        assertEquals(12.0d, interpretation.getRankingScore());
        assertEquals("REVIEW", interpretation.getPriorityTier());
        assertEquals(VariantAnalysisBucket.CANDIDATE, interpretation.getAnalysisBucket());
        assertEquals(2.0d, interpretation.getScoreBreakdown()
                .get(TrioRankingContributionService.COMPONENT));
        assertTrue(interpretation.getScoreComponents()
                .containsKey(TrioRankingContributionService.COMPONENT));
    }

    @Test
    void nonFiniteContributionCapsAreNeutral() {
        for (double cap : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            TrioRankingContribution contribution = service(true, cap)
                    .calculate(
                            interpretation(),
                            trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                            observation(
                                    TrioGenotypeCall.HET_ALT,
                                    TrioGenotypeCall.HOM_REF,
                                    TrioGenotypeCall.HOM_REF));

            assertNoScoreEffect(contribution);
            assertEquals(TrioRankingNotAppliedReason.MAX_CONTRIBUTION_ZERO,
                    contribution.getNotAppliedReason());
        }
    }

    @Test
    void nonFiniteExistingComponentCannotPoisonReplacementScore() {
        VariantInterpretation interpretation = interpretation();
        interpretation.applyRanking(
                "REVIEW",
                10.0d,
                Collections.singletonMap(TrioRankingContributionService.COMPONENT, Double.NaN),
                Collections.emptyMap(),
                "Base review tier",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        TrioRankingContributionService service = service(true, 2.0d);
        TrioRankingContribution contribution = service.calculate(
                interpretation,
                trioEvidence("DE_NOVO_CANDIDATE", "SUPPORTED", ParentalOrigin.NONE),
                observation(TrioGenotypeCall.HET_ALT, TrioGenotypeCall.HOM_REF, TrioGenotypeCall.HOM_REF));

        service.applyContribution(interpretation, contribution);

        assertEquals(12.0d, interpretation.getRankingScore());
        assertEquals(2.0d, interpretation.getScoreBreakdown()
                .get(TrioRankingContributionService.COMPONENT));
    }

    private void assertNoScoreEffect(TrioRankingContribution contribution) {
        assertFalse(contribution.isApplied());
        assertEquals(0.0d, contribution.getContributionScore());
        assertEquals(TrioRankingContributionDirection.NEUTRAL, contribution.getContributionDirection());
    }

    private TrioRankingContributionService service(boolean rankingEnabled, double maxContribution) {
        return new TrioRankingContributionService(properties(rankingEnabled, maxContribution));
    }

    private VariantAnalysisProperties properties(boolean rankingEnabled, double maxContribution) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getTrio().setRankingEnabled(rankingEnabled);
        properties.getTrio().setRankingMaxScoreContribution(maxContribution);
        return properties;
    }

    private VariantInterpretation interpretation() {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh37", "1", 100, "A", "G"),
                "proband",
                "0/1",
                40,
                99,
                "20,20",
                0.5d,
                "PASS",
                "SNV",
                null);
        VariantInterpretation interpretation = new VariantInterpretation(
                "denovo-1",
                observation,
                Collections.emptyList(),
                Collections.emptyList());
        interpretation.applyRanking(
                "REVIEW",
                10.0d,
                Collections.singletonMap("base", 10.0d),
                Collections.emptyMap(),
                "Base review tier",
                Collections.singletonList("Base evidence"),
                Collections.emptyList(),
                Collections.singletonList("Base rationale"),
                Collections.emptyList(),
                Collections.emptyList());
        return interpretation;
    }

    private TrioVariantObservation observation(TrioGenotypeCall proband,
                                               TrioGenotypeCall father,
                                               TrioGenotypeCall mother) {
        Map<TrioRole, TrioGenotypeObservation> genotypes =
                new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);
        genotypes.put(TrioRole.PROBAND, genotype(TrioRole.PROBAND, proband));
        genotypes.put(TrioRole.FATHER, genotype(TrioRole.FATHER, father));
        genotypes.put(TrioRole.MOTHER, genotype(TrioRole.MOTHER, mother));
        return new TrioVariantObservation("denovo-1", "GENE1", "1", 100, genotypes);
    }

    private TrioGenotypeObservation genotype(TrioRole role, TrioGenotypeCall call) {
        return new TrioGenotypeObservation(role, genotypeText(call), call, 40, 99, null);
    }

    private String genotypeText(TrioGenotypeCall call) {
        if (call == TrioGenotypeCall.HOM_REF || call == TrioGenotypeCall.UNCONFIRMED_HOM_REF) {
            return "0/0";
        }
        if (call == TrioGenotypeCall.HOM_ALT) {
            return "1/1";
        }
        if (call == TrioGenotypeCall.NO_CALL) {
            return "./.";
        }
        if (call == TrioGenotypeCall.UNOBSERVED) {
            return null;
        }
        return "0/1";
    }

    private EvidenceItem trioEvidence(String model, String confidence) {
        return trioEvidence(model, confidence, ParentalOrigin.UNKNOWN);
    }

    private EvidenceItem trioEvidence(String model, String confidence, ParentalOrigin origin) {
        return trioEvidence(model, confidence, origin, true);
    }

    private EvidenceItem trioEvidenceWithoutPartner(String model, String confidence, ParentalOrigin origin) {
        return trioEvidence(model, confidence, origin, false);
    }

    private EvidenceItem trioEvidence(String model, String confidence, ParentalOrigin origin, boolean includePartner) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("model", model);
        details.put("confidence", confidence);
        details.put("parentalOrigin", origin.name());
        details.put("evidenceOnly", Boolean.TRUE);
        if (includePartner && "COMPOUND_HETEROZYGOUS_CANDIDATE".equals(model)) {
            details.put("partnerVariantIds", Collections.singletonList("partner-variant"));
        }
        details.put("probandGenotype", "HET_ALT (0/1)");
        details.put("fatherGenotype", "HOM_REF (0/0)");
        details.put("motherGenotype", "HOM_REF (0/0)");
        return new EvidenceItem(
                "TRIO",
                "VARVIS_TRIO_PIPELINE_V1",
                "SEGREGATION",
                "APPLICABLE",
                null,
                model + " / " + confidence,
                "Synthetic trio evidence for ranking contribution test.",
                details);
    }

    private EvidenceItem sharedParentEvidence(boolean parentUnaffected, boolean dominantLike) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("model", "INHERITED_CANDIDATE");
        details.put("confidence", "POSSIBLE");
        details.put("parentalOrigin", "PATERNAL");
        details.put("syntheticSharedUnaffectedParent", String.valueOf(parentUnaffected));
        details.put("syntheticDominantLikeModel", String.valueOf(dominantLike));
        return new EvidenceItem(
                "TRIO",
                "VARVIS_TRIO_PIPELINE_V1",
                "SEGREGATION",
                "APPLICABLE",
                null,
                "INHERITED_CANDIDATE / POSSIBLE",
                "Synthetic shared-parent trio evidence for ranking contribution test.",
                details);
    }
}
