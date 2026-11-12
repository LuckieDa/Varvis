package org.nbscn.varvis.variantanalysis.ranking;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

@Service
public class TransparentVariantRankingService implements VariantRankingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransparentVariantRankingService.class);

    private static final String KNOWN_CLINICAL = "KNOWN_CLINICAL";
    private static final String HIGH_PRIORITY = "HIGH_PRIORITY";
    private static final String REVIEW = "REVIEW";
    private static final String LOW_PRIORITY = "LOW_PRIORITY";
    private static final String FILTERED_COMMON = "FILTERED_COMMON";
    private static final String FILTERED_QC = "FILTERED_QC";
    private static final String BENIGN_LIKELY = "BENIGN_LIKELY";
    private static final String UNRESOLVED = "UNRESOLVED";

    private final VariantAnalysisProperties properties;

    public TransparentVariantRankingService(VariantAnalysisProperties properties) {
        this.properties = properties;
    }

    @Override
    public void rank(List<VariantInterpretation> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        int compactThreshold = properties.getRanking().normalizedCompactModeThreshold();
        if (compactThreshold > 0 && variants.size() > compactThreshold) {
            rankLargeAnalysis(variants);
            return;
        }
        for (VariantInterpretation variant : variants) {
            rankDetailed(variant);
        }
    }

    private void rankLargeAnalysis(List<VariantInterpretation> variants) {
        LOGGER.info(
                "Large variant ranking compact mode enabled: input={}, threshold={}, detailedMaxCandidates={}",
                variants.size(),
                properties.getRanking().normalizedCompactModeThreshold(),
                properties.getRanking().normalizedDetailedMaxCandidates()
        );
        for (VariantInterpretation variant : variants) {
            rankCompact(variant);
        }
        int detailedLimit = properties.getRanking().normalizedDetailedMaxCandidates();
        if (detailedLimit <= 0) {
            return;
        }
        for (VariantInterpretation variant : topRanked(variants, detailedLimit)) {
            rankDetailed(variant);
        }
    }

    private List<VariantInterpretation> topRanked(List<VariantInterpretation> variants, int limit) {
        PriorityQueue<VariantInterpretation> queue = new PriorityQueue<VariantInterpretation>(
                limit,
                (left, right) -> Double.compare(scoreOrNegative(left.getRankingScore()),
                        scoreOrNegative(right.getRankingScore()))
        );
        for (VariantInterpretation variant : variants) {
            if (queue.size() < limit) {
                queue.add(variant);
            } else if (scoreOrNegative(variant.getRankingScore())
                    > scoreOrNegative(queue.peek().getRankingScore())) {
                queue.poll();
                queue.add(variant);
            }
        }
        return new ArrayList<VariantInterpretation>(queue);
    }

    private void rankDetailed(VariantInterpretation variant) {
        RankingContext context = new RankingContext(variant, properties.getRanking());
        assessClinicalEvidence(context);
        assessPopulationFrequency(context);
        assessConsequence(context);
        assessComputationalPrediction(context);
        assessSplicePrediction(context);
        assessQuality(context);
        assessGenotype(context);
        assessUnavailableEvidence(context);
        assignTier(context);
        context.apply();
    }

    private void rankCompact(VariantInterpretation variant) {
        VariantAnalysisProperties.Ranking ranking = properties.getRanking();
        EvidenceItem clinVar = bestEvidence(variant, "CLINVAR");
        EvidenceItem gnomad = bestEvidence(variant, "GNOMAD");
        double totalScore = 0.0d;
        boolean pathogenicClinVar = false;
        boolean benignClinVar = false;
        boolean vusClinVar = false;
        boolean conflictingClinVar = false;
        boolean common = false;
        boolean veryCommon = false;
        boolean gnomadNoRecord = false;
        boolean gnomadUnavailable = false;
        boolean highImpact = false;
        boolean synonymous = false;
        boolean computationalHighlight = false;
        boolean spliceHighlight = false;
        boolean qcFiltered = false;
        boolean nonCarriedAlternate = false;
        Double cadd = score(variant, "CADD");

        if (clinVar != null) {
            String label = text(clinVar.getLabel());
            String applicability = clinVar.getApplicability();
            if ("CONFLICTING".equals(applicability) || containsIgnoreCase(label, "conflict")) {
                conflictingClinVar = true;
                totalScore -= 10.0d;
            } else if ("APPLICABLE".equals(applicability) && isClinVarBenignLabel(label)) {
                benignClinVar = true;
                totalScore -= 120.0d;
            } else if ("APPLICABLE".equals(applicability) && isClinVarPathogenicLabel(label)) {
                pathogenicClinVar = alleleExactFast(clinVar);
                totalScore += pathogenicClinVar ? 120.0d : 30.0d;
            } else if ("APPLICABLE".equals(applicability) && isClinVarVusLabel(label)) {
                vusClinVar = true;
            }
        }

        if (gnomad != null) {
            String applicability = gnomad.getApplicability();
            Double rawAf = gnomad.getRawScore();
            Double af = validAlleleFrequency(rawAf);
            if ("NO_RECORD".equals(applicability)) {
                gnomadNoRecord = true;
            } else if ("UNAVAILABLE".equals(applicability)) {
                gnomadUnavailable = true;
            } else if ("APPLICABLE".equals(applicability) && af == null) {
                gnomadUnavailable = true;
            } else if ("APPLICABLE".equals(applicability) && af != null) {
                if (af >= ranking.getVeryCommonAlleleFrequencyThreshold()) {
                    veryCommon = true;
                    common = true;
                    totalScore += pathogenicClinVar ? -50.0d : -1500.0d;
                } else if (af >= ranking.getCommonAlleleFrequencyThreshold()) {
                    common = true;
                    totalScore += pathogenicClinVar ? -30.0d : -1000.0d;
                } else {
                    totalScore += 15.0d;
                }
            }
        }

        String highImpactConsequence = firstHighImpactConsequenceFast(variant);
        if (highImpactConsequence != null) {
            highImpact = true;
            totalScore += 35.0d;
        } else if (firstMissenseConsequenceFast(variant) != null) {
            totalScore += 5.0d;
        } else if (firstSynonymousConsequenceFast(variant) != null) {
            synonymous = true;
            totalScore -= 5.0d;
        } else if (firstLowCodingImpactConsequenceFast(variant) != null) {
            totalScore -= 5.0d;
        }

        double computationalScore = 0.0d;
        if (cadd != null) {
            if (cadd >= ranking.getCaddHighlightThreshold()) {
                computationalScore += 25.0d;
                computationalHighlight = true;
            } else if (cadd >= ranking.getCaddReviewThreshold()) {
                computationalScore += 10.0d;
            }
        }
        Double alphaMissense = score(variant, "ALPHAMISSENSE");
        if (alphaMissense != null && alphaMissense >= ranking.getAlphaMissenseHighlightThreshold()) {
            computationalScore += 20.0d;
            computationalHighlight = true;
        }
        totalScore += Math.min(computationalScore, 40.0d);

        Double spliceAi = score(variant, "SPLICEAI");
        if (spliceAi != null) {
            if (spliceAi >= ranking.getSpliceAiHighlightThreshold()) {
                spliceHighlight = true;
                totalScore += 30.0d;
            } else {
                totalScore += 5.0d * spliceAi;
            }
        }

        VariantQcObservation observation = variant.getObservation();
        String filter = observation.getFilterStatus();
        if (filter != null && !"PASS".equals(filter)) {
            qcFiltered = true;
            totalScore -= 500.0d;
        } else if (filter != null && !filter.trim().isEmpty()) {
            totalScore += 5.0d;
        }
        Integer depth = observation.getDepth();
        if (depth != null && depth < ranking.getMinDepth()) {
            qcFiltered = true;
            totalScore -= 150.0d;
        }
        Integer genotypeQuality = observation.getGenotypeQuality();
        if (genotypeQuality != null && genotypeQuality < ranking.getMinGenotypeQuality()) {
            qcFiltered = true;
            totalScore -= 150.0d;
        }
        Double alleleBalance = observation.getAlleleBalance();
        if (alleleBalance != null && alleleBalance > 0.0d
                && alleleBalance < ranking.getMinAlleleBalance()) {
            qcFiltered = true;
            totalScore -= 100.0d;
        }
        String genotype = observation.getGenotype();
        if (isNonCarriedAlternate(genotype)) {
            nonCarriedAlternate = true;
            totalScore -= 1000.0d;
        } else if (genotype != null && !genotype.trim().isEmpty() && !genotype.contains(".")) {
            totalScore += 5.0d;
        }
        if (conflictingClinVar) {
            totalScore -= 25.0d;
        }

        String tier;
        String tierReason;
        if (qcFiltered || nonCarriedAlternate) {
            tier = FILTERED_QC;
            tierReason = "QC or genotype support failed the configured candidate gate";
        } else if (benignClinVar) {
            tier = BENIGN_LIKELY;
            tierReason = "ClinVar benign/likely benign evidence strongly deprioritizes this variant";
        } else if (common && !pathogenicClinVar) {
            tier = FILTERED_COMMON;
            tierReason = "gnomAD population frequency crosses the rare-disease candidate gate";
        } else if (pathogenicClinVar && common) {
            tier = REVIEW;
            tierReason = "ClinVar pathogenic evidence conflicts with high gnomAD frequency";
        } else if (conflictingClinVar || vusClinVar) {
            tier = REVIEW;
            tierReason = conflictingClinVar
                    ? "ClinVar conflicting interpretation requires manual review"
                    : "ClinVar VUS is not pathogenic but should remain reviewable";
        } else if (pathogenicClinVar) {
            tier = KNOWN_CLINICAL;
            tierReason = "Allele-matched ClinVar pathogenic/likely pathogenic evidence is present";
        } else if ((highImpact || spliceHighlight || (computationalHighlight && !synonymous)) && !veryCommon) {
            tier = HIGH_PRIORITY;
            tierReason = "Rare-compatible variant has high-impact or strong prediction evidence";
        } else if (cadd != null && cadd >= ranking.getCaddReviewThreshold()) {
            tier = REVIEW;
            tierReason = "Computational evidence crosses the review threshold but is not clinical proof";
        } else if (gnomadUnavailable || gnomadNoRecord || variant.getEvidence().isEmpty()) {
            tier = UNRESOLVED;
            tierReason = "Key evidence is missing or unavailable, so the variant remains unresolved";
        } else {
            tier = LOW_PRIORITY;
            tierReason = "No rare-disease prioritization signal crossed the configured review thresholds";
        }

        variant.applyRanking(
                tier,
                totalScore,
                Collections.emptyMap(),
                Collections.emptyMap(),
                tierReason,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private void assessClinicalEvidence(RankingContext context) {
        EvidenceItem clinVar = context.bestEvidence("CLINVAR");
        if (clinVar == null) {
            context.addComponent(
                    "clinical_evidence",
                    0.0d,
                    "No ClinVar evidence is available in this analysis result"
            );
            return;
        }
        String label = text(clinVar.getLabel());
        String normalized = normalize(label);
        if ("CONFLICTING".equals(clinVar.getApplicability())
                || normalized.contains("conflict")) {
            context.conflictingClinVar = true;
            context.addComponent(
                    "clinical_evidence",
                    -10.0d,
                    "ClinVar reports conflicting interpretations; this requires manual review"
            );
            context.warnings.add("ClinVar conflicting interpretation is not treated as pathogenic");
            return;
        }
        if (!"APPLICABLE".equals(clinVar.getApplicability())) {
            context.addComponent(
                    "clinical_evidence",
                    0.0d,
                    "ClinVar evidence status is " + clinVar.getApplicability()
                            + "; it is not interpreted as benign or pathogenic"
            );
            return;
        }
        if (isClinVarBenign(normalized)) {
            context.benignClinVar = true;
            context.addComponent(
                    "clinical_evidence",
                    -120.0d,
                    "ClinVar label " + label + " supports benign or likely benign interpretation"
            );
            context.negativeReasons.add("ClinVar benign/likely benign evidence strongly lowers rare-disease priority");
            return;
        }
        if (isClinVarPathogenic(normalized) && alleleExact(clinVar)) {
            context.pathogenicClinVar = true;
            context.addComponent(
                    "clinical_evidence",
                    120.0d,
                    "Allele-matched ClinVar label " + label
                            + " supports known clinical review priority"
            );
            context.positiveReasons.add("ClinVar pathogenic/likely pathogenic evidence is present");
            return;
        }
        if (isClinVarPathogenic(normalized)) {
            context.addComponent(
                    "clinical_evidence",
                    30.0d,
                    "ClinVar label suggests pathogenicity, but allele-exact match metadata is missing"
            );
            context.warnings.add("ClinVar pathogenic label lacks explicit allele-exact match metadata");
            return;
        }
        if (isClinVarVus(normalized)) {
            context.vusClinVar = true;
            context.addComponent(
                    "clinical_evidence",
                    0.0d,
                    "ClinVar VUS is retained for review but is not treated as pathogenic"
            );
            context.positiveReasons.add("ClinVar VUS may be worth manual review");
            return;
        }
        context.addComponent(
                "clinical_evidence",
                0.0d,
                "ClinVar label " + label + " does not define a pathogenic or benign tier"
        );
    }

    private void assessPopulationFrequency(RankingContext context) {
        EvidenceItem gnomad = context.bestEvidence("GNOMAD");
        if (gnomad == null) {
            context.addComponent(
                    "population_frequency",
                    0.0d,
                    "No gnomAD evidence item is available; absence is not interpreted as low risk"
            );
            context.warnings.add("gnomAD evidence is missing; this is not AF=0");
            return;
        }
        String applicability = gnomad.getApplicability();
        if ("NO_RECORD".equals(applicability)) {
            context.gnomadNoRecord = true;
            context.addComponent(
                    "population_frequency",
                    0.0d,
                    "gnomAD has no matching record; this is not interpreted as AF=0"
            );
            context.warnings.add("gnomAD NO_RECORD means no matching local record, not confirmed rarity");
            return;
        }
        if ("UNAVAILABLE".equals(applicability)) {
            context.gnomadUnavailable = true;
            context.addComponent(
                    "population_frequency",
                    0.0d,
                    "gnomAD is unavailable; missing population evidence does not lower risk"
            );
            context.warnings.add("gnomAD UNAVAILABLE means population frequency cannot be evaluated");
            return;
        }
        if (!"APPLICABLE".equals(applicability)) {
            context.addComponent(
                    "population_frequency",
                    0.0d,
                    "gnomAD status is " + applicability + "; no population-frequency score is applied"
            );
            return;
        }
        Double rawAf = gnomad.getRawScore();
        Double af = validAlleleFrequency(rawAf);
        if (af == null) {
            context.gnomadUnavailable = true;
            context.addComponent(
                    "population_frequency",
                    0.0d,
                    "gnomAD evidence is applicable but has no finite allele-frequency value"
            );
            context.warnings.add("gnomAD applicable evidence has no finite AF value");
            return;
        }
        double commonThreshold = context.ranking.getCommonAlleleFrequencyThreshold();
        double veryCommonThreshold = context.ranking.getVeryCommonAlleleFrequencyThreshold();
        if (af >= veryCommonThreshold) {
            context.veryCommon = true;
            context.common = true;
            context.addComponent(
                    "population_frequency",
                    context.pathogenicClinVar ? -50.0d : -1500.0d,
                    "gnomAD maximum allele frequency " + formatNumeric(af)
                            + " is very common for a rare-disease candidate"
            );
            context.negativeReasons.add("gnomAD AF_popmax is at or above "
                    + formatNumeric(veryCommonThreshold));
        } else if (af >= commonThreshold) {
            context.common = true;
            context.addComponent(
                    "population_frequency",
                    context.pathogenicClinVar ? -30.0d : -1000.0d,
                    "gnomAD maximum allele frequency " + formatNumeric(af)
                            + " crosses the rare-disease frequency gate "
                            + formatNumeric(commonThreshold)
            );
            context.negativeReasons.add("gnomAD AF_popmax is at or above "
                    + formatNumeric(commonThreshold));
        } else {
            context.addComponent(
                    "population_frequency",
                    15.0d,
                    "gnomAD maximum allele frequency " + formatNumeric(af)
                            + " is below the configured rare-disease gate"
            );
            context.positiveReasons.add("Population frequency is below the configured gnomAD gate");
        }
    }

    private void assessConsequence(RankingContext context) {
        String highImpact = firstHighImpactConsequence(context.variant);
        if (highImpact != null) {
            context.highImpact = true;
            context.addComponent(
                    "predicted_consequence",
                    35.0d,
                    "High-impact consequence " + highImpact
                            + " supports prioritization because it may disrupt protein function"
            );
            context.positiveReasons.add("High-impact consequence detected: " + highImpact);
            return;
        }
        String missense = firstMissenseConsequence(context.variant);
        if (missense != null) {
            context.addComponent(
                    "predicted_consequence",
                    5.0d,
                    "Missense consequence " + missense
                            + " is interpreted with computational predictors, not alone"
            );
            return;
        }
        String synonymous = firstSynonymousConsequence(context.variant);
        if (synonymous != null) {
            context.synonymous = true;
            context.addComponent(
                    "predicted_consequence",
                    -5.0d,
                    "Synonymous consequence is not elevated without splice evidence"
            );
            context.negativeReasons.add("Synonymous consequence has no independent priority boost");
            return;
        }
        String lowCodingImpact = firstLowCodingImpactConsequence(context.variant);
        if (lowCodingImpact != null) {
            context.addComponent(
                    "predicted_consequence",
                    -5.0d,
                    "Non-coding or low-coding-impact consequence "
                            + lowCodingImpact
                            + " is not elevated without strong splice evidence"
            );
            context.negativeReasons.add("Non-coding consequence lacks strong splice evidence");
            return;
        }
        context.addComponent(
                "predicted_consequence",
                0.0d,
                "No high-impact transcript consequence was identified"
        );
    }

    private void assessComputationalPrediction(RankingContext context) {
        Double cadd = context.score("CADD");
        Double alphaMissense = context.score("ALPHAMISSENSE");
        double score = 0.0d;
        List<String> reasons = new ArrayList<String>();
        if (cadd != null) {
            context.cadd = cadd;
            if (cadd >= context.ranking.getCaddHighlightThreshold()) {
                score += 25.0d;
                context.computationalHighlight = true;
                reasons.add("CADD PHRED " + formatNumeric(cadd)
                        + " crosses the high-priority ranking threshold");
                context.positiveReasons.add("CADD supports deleteriousness prioritization");
            } else if (cadd >= context.ranking.getCaddReviewThreshold()) {
                score += 10.0d;
                reasons.add("CADD PHRED " + formatNumeric(cadd)
                        + " crosses the review threshold");
                context.positiveReasons.add("CADD supports manual review");
            } else {
                reasons.add("CADD PHRED " + formatNumeric(cadd)
                        + " does not cross review thresholds");
            }
        }
        if (alphaMissense != null) {
            if (alphaMissense >= context.ranking.getAlphaMissenseHighlightThreshold()) {
                score += 20.0d;
                context.computationalHighlight = true;
                reasons.add("AlphaMissense " + formatNumeric(alphaMissense)
                        + " supports missense prioritization");
                context.positiveReasons.add("AlphaMissense supports missense prioritization");
            } else {
                reasons.add("AlphaMissense " + formatNumeric(alphaMissense)
                        + " does not cross the configured highlight threshold");
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("No CADD or AlphaMissense score contributed to ranking");
        }
        context.addComponent("computational_prediction", Math.min(score, 40.0d), reasons);
    }

    private void assessSplicePrediction(RankingContext context) {
        Double spliceAi = context.score("SPLICEAI");
        if (spliceAi == null) {
            context.addComponent(
                    "splice_prediction",
                    0.0d,
                    "No SpliceAI score contributed to ranking"
            );
            return;
        }
        if (spliceAi >= context.ranking.getSpliceAiHighlightThreshold()) {
            context.spliceHighlight = true;
            context.addComponent(
                    "splice_prediction",
                    30.0d,
                    "SpliceAI maximum delta score " + formatNumeric(spliceAi)
                            + " crosses the configured splice review threshold"
            );
            context.positiveReasons.add("SpliceAI supports splice-effect review");
        } else {
            context.addComponent(
                    "splice_prediction",
                    5.0d * spliceAi,
                    "SpliceAI maximum delta score " + formatNumeric(spliceAi)
                            + " provides weak splice-effect support"
            );
        }
    }

    private void assessQuality(RankingContext context) {
        VariantQcObservation observation = context.variant.getObservation();
        List<String> reasons = new ArrayList<String>();
        double score = 0.0d;
        String filter = observation.getFilterStatus();
        if (filter != null && !"PASS".equals(filter)) {
            context.qcFiltered = true;
            score -= 500.0d;
            reasons.add("VCF FILTER is " + filter + ", not PASS");
            context.filterReasons.add("FILTER != PASS: " + filter);
        } else if (filter == null || filter.trim().isEmpty()) {
            reasons.add("VCF FILTER is missing; quality is unknown, not automatically low quality");
            context.warnings.add("FILTER is missing; QC certainty is limited");
        } else {
            score += 5.0d;
            reasons.add("VCF FILTER is PASS");
        }
        Integer depth = observation.getDepth();
        if (depth == null) {
            reasons.add("Read depth DP is missing; this is recorded as unknown");
            context.warnings.add("DP is missing; genotype support cannot be fully assessed");
        } else if (depth < context.ranking.getMinDepth()) {
            context.qcFiltered = true;
            score -= 150.0d;
            reasons.add("Read depth DP " + depth + " is below "
                    + context.ranking.getMinDepth());
            context.filterReasons.add("DP below threshold: " + depth);
        } else {
            reasons.add("Read depth DP " + depth + " meets the configured threshold");
        }
        Integer genotypeQuality = observation.getGenotypeQuality();
        if (genotypeQuality == null) {
            reasons.add("Genotype quality GQ is missing; this is recorded as unknown");
            context.warnings.add("GQ is missing; genotype quality cannot be fully assessed");
        } else if (genotypeQuality < context.ranking.getMinGenotypeQuality()) {
            context.qcFiltered = true;
            score -= 150.0d;
            reasons.add("Genotype quality GQ " + genotypeQuality + " is below "
                    + context.ranking.getMinGenotypeQuality());
            context.filterReasons.add("GQ below threshold: " + genotypeQuality);
        } else {
            reasons.add("Genotype quality GQ " + genotypeQuality + " meets the configured threshold");
        }
        Double alleleBalance = observation.getAlleleBalance();
        if (alleleBalance == null) {
            reasons.add("Allele balance is missing; this is recorded as unknown");
        } else if (alleleBalance > 0.0d && alleleBalance < context.ranking.getMinAlleleBalance()) {
            context.qcFiltered = true;
            score -= 100.0d;
            reasons.add("Allele balance " + formatNumeric(alleleBalance)
                    + " is below " + formatNumeric(context.ranking.getMinAlleleBalance()));
            context.filterReasons.add("Allele balance below threshold: "
                    + formatNumeric(alleleBalance));
        } else {
            reasons.add("Allele balance " + formatNumeric(alleleBalance)
                    + " does not trigger the weak-support filter");
        }
        context.addComponent("qc_quality", score, reasons);
    }

    private void assessGenotype(RankingContext context) {
        String genotype = context.variant.getObservation().getGenotype();
        if (isNonCarriedAlternate(genotype)) {
            context.nonCarriedAlternate = true;
            context.addComponent(
                    "genotype_support",
                    -1000.0d,
                    "Sample genotype is 0/0, so the submitted sample does not carry the ALT allele"
            );
            context.filterReasons.add("GT=0/0: selected sample does not carry ALT");
            return;
        }
        if (genotype == null || genotype.trim().isEmpty() || genotype.contains(".")) {
            context.addComponent(
                    "genotype_support",
                    0.0d,
                    "Genotype is missing or partially missing; carrier status is uncertain"
            );
            context.warnings.add("Genotype is missing or partially missing");
            return;
        }
        context.addComponent(
                "genotype_support",
                5.0d,
                "Sample genotype " + genotype + " carries the ALT allele"
        );
        context.positiveReasons.add("Selected sample carries the ALT allele");
    }

    private void assessUnavailableEvidence(RankingContext context) {
        int unavailableCount = 0;
        for (EvidenceItem evidence : context.variant.getEvidence()) {
            String applicability = evidence.getApplicability();
            if ("UNAVAILABLE".equals(applicability)) {
                unavailableCount++;
                context.warnings.add(evidence.getSource()
                        + " evidence is unavailable; absence is not interpreted as low risk");
            } else if ("NOT_APPLICABLE".equals(applicability)) {
                context.warnings.add(evidence.getSource()
                        + " evidence is not applicable for this variant and is not penalized");
            }
        }
        if (context.conflictingClinVar) {
            context.addComponent(
                    "conflict_penalty",
                    -25.0d,
                    "Conflicting clinical evidence requires manual review"
            );
        } else if (unavailableCount >= 3) {
            context.addComponent(
                    "conflict_penalty",
                    0.0d,
                    "Multiple evidence providers are unavailable; this is uncertainty, not low risk"
            );
        } else {
            context.addComponent(
                    "conflict_penalty",
                    0.0d,
                    "No explicit evidence conflict penalty was applied"
            );
        }
    }

    private void assignTier(RankingContext context) {
        if (context.qcFiltered || context.nonCarriedAlternate) {
            context.tier = FILTERED_QC;
            context.tierReason = "QC or genotype support failed the configured candidate gate";
            return;
        }
        if (context.benignClinVar) {
            context.tier = BENIGN_LIKELY;
            context.tierReason = "ClinVar benign/likely benign evidence strongly deprioritizes this variant";
            return;
        }
        if (context.common && !context.pathogenicClinVar) {
            context.tier = FILTERED_COMMON;
            context.tierReason = "gnomAD population frequency crosses the rare-disease candidate gate";
            context.filterReasons.add("AF_popmax >= "
                    + formatNumeric(context.ranking.getCommonAlleleFrequencyThreshold()));
            return;
        }
        if (context.pathogenicClinVar && context.common) {
            context.tier = REVIEW;
            context.tierReason = "ClinVar pathogenic evidence conflicts with high gnomAD frequency";
            context.warnings.add("HIGH_AF_WARNING: ClinVar P/LP is present but gnomAD AF_popmax is high");
            return;
        }
        if (context.conflictingClinVar || context.vusClinVar) {
            context.tier = REVIEW;
            context.tierReason = context.conflictingClinVar
                    ? "ClinVar conflicting interpretation requires manual review"
                    : "ClinVar VUS is not pathogenic but should remain reviewable";
            return;
        }
        if (context.pathogenicClinVar) {
            context.tier = KNOWN_CLINICAL;
            context.tierReason = "Allele-matched ClinVar pathogenic/likely pathogenic evidence is present";
            return;
        }
        boolean predictionOnlyHighlight = context.computationalHighlight && !context.synonymous;
        if ((context.highImpact || context.spliceHighlight || predictionOnlyHighlight)
                && !context.veryCommon) {
            context.tier = HIGH_PRIORITY;
            context.tierReason = "Rare-compatible variant has high-impact or strong prediction evidence";
            return;
        }
        if (context.cadd != null
                && context.cadd >= context.ranking.getCaddReviewThreshold()) {
            context.tier = REVIEW;
            context.tierReason = "Computational evidence crosses the review threshold but is not clinical proof";
            return;
        }
        if (context.gnomadUnavailable || context.gnomadNoRecord || context.variant.getEvidence().isEmpty()) {
            context.tier = UNRESOLVED;
            context.tierReason = "Key evidence is missing or unavailable, so the variant remains unresolved";
            return;
        }
        context.tier = LOW_PRIORITY;
        context.tierReason = "No rare-disease prioritization signal crossed the configured review thresholds";
    }

    private String firstHighImpactConsequence(VariantInterpretation variant) {
        for (TranscriptConsequence consequence : transcriptConsequences(variant)) {
            String value = consequence.getConsequence();
            if (value == null) {
                continue;
            }
            if (isHighImpactConsequence(consequence)) {
                return value;
            }
        }
        return null;
    }

    private String firstHighImpactConsequenceFast(VariantInterpretation variant) {
        for (TranscriptConsequence consequence : transcriptConsequences(variant)) {
            String value = consequence.getConsequence();
            if (value == null) {
                continue;
            }
            if (isHighImpactConsequence(consequence)) {
                return value;
            }
        }
        return null;
    }

    private String firstMissenseConsequence(VariantInterpretation variant) {
        for (TranscriptConsequence consequence : transcriptConsequences(variant)) {
            String value = consequence.getConsequence();
            if (hasConsequenceTerm(value, "missense_variant")) {
                return value;
            }
        }
        return null;
    }

    private String firstMissenseConsequenceFast(VariantInterpretation variant) {
        for (TranscriptConsequence consequence : transcriptConsequences(variant)) {
            String value = consequence.getConsequence();
            if (hasConsequenceTerm(value, "missense_variant")) {
                return value;
            }
        }
        return null;
    }

    private String firstSynonymousConsequence(VariantInterpretation variant) {
        for (TranscriptConsequence consequence : transcriptConsequences(variant)) {
            String value = consequence.getConsequence();
            if (hasConsequenceTerm(value, "synonymous_variant")) {
                return value;
            }
        }
        return null;
    }

    private String firstSynonymousConsequenceFast(VariantInterpretation variant) {
        return firstSynonymousConsequence(variant);
    }

    private String firstLowCodingImpactConsequence(VariantInterpretation variant) {
        for (TranscriptConsequence consequence : transcriptConsequences(variant)) {
            String value = consequence.getConsequence();
            if (isLowCodingImpactConsequence(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstLowCodingImpactConsequenceFast(VariantInterpretation variant) {
        return firstLowCodingImpactConsequence(variant);
    }

    private boolean isHighImpactConsequence(TranscriptConsequence consequence) {
        if (consequence == null || !isCodingOrLegacyTranscript(consequence)) {
            return false;
        }
        String value = consequence.getConsequence();
        return hasConsequenceTerm(
                value,
                "frameshift_variant",
                "frameshift",
                "stop_gained",
                "splice_acceptor_variant",
                "splice_acceptor",
                "splice_donor_variant",
                "splice_donor",
                "transcript_ablation",
                "start_lost",
                "stop_lost"
        );
    }

    private boolean isCodingOrLegacyTranscript(TranscriptConsequence consequence) {
        if (consequence == null) {
            return false;
        }
        String biotype = normalize(consequence.getTranscriptSource());
        if (biotype.isEmpty()) {
            return isVepAnnotationSource(consequence.getSource())
                    ? hasProteinCodingDetail(consequence)
                    : true;
        }
        if ("protein_coding".equals(biotype)) {
            return !isVepAnnotationSource(consequence.getSource()) || hasProteinCodingDetail(consequence);
        }
        return hasProteinCodingDetail(consequence);
    }

    private boolean hasProteinCodingDetail(TranscriptConsequence consequence) {
        if (consequence == null) {
            return false;
        }
        return hasText(consequence.getHgvsp())
                || hasText(consequence.getProteinPosition())
                || hasText(consequence.getAminoAcids())
                || hasText(consequence.getCodons());
    }

    private boolean isVepAnnotationSource(String source) {
        return source != null && normalize(source).contains("vep");
    }

    private boolean isLowCodingImpactConsequence(String value) {
        return hasConsequenceTerm(
                value,
                "intron_variant",
                "intergenic_variant",
                "upstream_gene_variant",
                "downstream_gene_variant",
                "3_prime_utr_variant",
                "5_prime_utr_variant",
                "regulatory_region_variant",
                "non_coding_transcript_variant",
                "nmd_transcript_variant"
        );
    }

    private boolean hasConsequenceTerm(String value, String... terms) {
        if (value == null || terms == null || terms.length == 0) {
            return false;
        }
        String[] tokens = normalize(value).split("[^a-z0-9_]+");
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            for (String term : terms) {
                if (token.equals(term)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<TranscriptConsequence> transcriptConsequences(VariantInterpretation variant) {
        if (variant == null) {
            return Collections.emptyList();
        }
        List<TranscriptConsequence> consequences = variant.getConsequences();
        List<TranscriptConsequence> annotations = variant.getTranscriptAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return consequences == null ? Collections.emptyList() : consequences;
        }
        if (consequences == null || consequences.isEmpty()) {
            return annotations;
        }
        List<TranscriptConsequence> combined =
                new ArrayList<TranscriptConsequence>(consequences.size() + annotations.size());
        combined.addAll(consequences);
        combined.addAll(annotations);
        return combined;
    }

    private EvidenceItem bestEvidence(VariantInterpretation variant, String source) {
        EvidenceItem best = null;
        int bestRank = Integer.MIN_VALUE;
        for (EvidenceItem item : variant.getEvidence()) {
            if (!source.equals(item.getSource())) {
                continue;
            }
            int rank = applicabilityRank(item.getApplicability());
            if (rank > bestRank) {
                best = item;
                bestRank = rank;
            } else if (rank == bestRank && best != null
                    && scoreOrNegative(item.getRawScore()) > scoreOrNegative(best.getRawScore())) {
                best = item;
            }
        }
        return best;
    }

    private Double score(VariantInterpretation variant, String source) {
        Double maximum = null;
        for (EvidenceItem item : variant.getEvidence()) {
            if (source.equals(item.getSource()) && "APPLICABLE".equals(item.getApplicability())) {
                Double rawScore = finiteScore(item.getRawScore());
                if (rawScore != null && (maximum == null || rawScore > maximum)) {
                    maximum = rawScore;
                }
            }
        }
        return maximum;
    }

    private int applicabilityRank(String applicability) {
        if ("APPLICABLE".equals(applicability)) {
            return 5;
        }
        if ("CONFLICTING".equals(applicability)) {
            return 4;
        }
        if ("NO_RECORD".equals(applicability)) {
            return 3;
        }
        if ("NOT_APPLICABLE".equals(applicability)) {
            return 2;
        }
        if ("UNAVAILABLE".equals(applicability)) {
            return 1;
        }
        return 0;
    }

    private double scoreOrNegative(Double value) {
        Double finite = finiteScore(value);
        return finite == null ? Double.NEGATIVE_INFINITY : finite;
    }

    private static Double finiteScore(Double value) {
        return value != null && Double.isFinite(value.doubleValue()) ? value : null;
    }

    private static Double validAlleleFrequency(Double value) {
        Double finite = finiteScore(value);
        return finite != null && finite.doubleValue() >= 0.0d && finite.doubleValue() <= 1.0d
                ? finite
                : null;
    }

    private boolean isClinVarPathogenicLabel(String value) {
        return containsIgnoreCase(value, "pathogenic")
                && !containsIgnoreCase(value, "benign")
                && !containsIgnoreCase(value, "conflict");
    }

    private boolean isClinVarBenignLabel(String value) {
        return containsIgnoreCase(value, "benign")
                && !containsIgnoreCase(value, "pathogenic")
                && !containsIgnoreCase(value, "conflict");
    }

    private boolean isClinVarVusLabel(String value) {
        return containsIgnoreCase(value, "uncertain")
                || containsIgnoreCase(value, "vus")
                || containsIgnoreCase(value, "uncertain_significance");
    }

    private boolean alleleExactFast(EvidenceItem evidence) {
        Object matchType = evidence.getDetails().get("matchType");
        if (matchType == null) {
            return false;
        }
        String value = String.valueOf(matchType);
        return containsIgnoreCase(value, "exact")
                || containsIgnoreCase(value, "vcf_info")
                || containsIgnoreCase(value, "chrom_pos_ref_alt");
    }

    private boolean containsIgnoreCase(String value, String needle) {
        if (value == null || needle == null) {
            return false;
        }
        int needleLength = needle.length();
        int max = value.length() - needleLength;
        for (int index = 0; index <= max; index++) {
            if (value.regionMatches(true, index, needle, 0, needleLength)) {
                return true;
            }
        }
        return false;
    }

    private boolean isClinVarPathogenic(String normalized) {
        return normalized.contains("pathogenic")
                && !normalized.contains("benign")
                && !normalized.contains("conflict");
    }

    private boolean isClinVarBenign(String normalized) {
        return normalized.contains("benign")
                && !normalized.contains("pathogenic")
                && !normalized.contains("conflict");
    }

    private boolean isClinVarVus(String normalized) {
        return normalized.contains("uncertain")
                || normalized.contains("vus")
                || normalized.contains("uncertain_significance");
    }

    private boolean alleleExact(EvidenceItem evidence) {
        Object matchType = evidence.getDetails().get("matchType");
        if (matchType == null) {
            return false;
        }
        String normalized = normalize(String.valueOf(matchType));
        return normalized.contains("exact")
                || normalized.contains("vcf_info")
                || normalized.contains("chrom_pos_ref_alt");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String text(String value) {
        return value == null || value.trim().isEmpty() ? "Unavailable" : value;
    }

    private String formatNumeric(Double value) {
        if (value == null) {
            return "-";
        }
        return formatNumeric(value.doubleValue());
    }

    private String formatNumeric(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        if (value != 0.0d && Math.abs(value) < 0.001d) {
            return String.format(Locale.ROOT, "%.2e", value);
        }
        return String.format(Locale.ROOT, "%.3f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private boolean isNonCarriedAlternate(String genotype) {
        if (genotype == null || genotype.trim().isEmpty()) {
            return false;
        }
        String[] alleles = genotype.trim().split("[/|]");
        boolean sawCalledAllele = false;
        for (String allele : alleles) {
            if (allele == null) {
                continue;
            }
            String value = allele.trim();
            if (value.isEmpty() || ".".equals(value)) {
                continue;
            }
            sawCalledAllele = true;
            if (!"0".equals(value)) {
                return false;
            }
        }
        return sawCalledAllele;
    }

    private static class RankingContext {

        private final VariantInterpretation variant;
        private final VariantAnalysisProperties.Ranking ranking;
        private final Map<String, Double> numericBreakdown = new LinkedHashMap<String, Double>();
        private final Map<String, ScoreBreakdownComponent> components =
                new LinkedHashMap<String, ScoreBreakdownComponent>();
        private final List<String> positiveReasons = new ArrayList<String>();
        private final List<String> negativeReasons = new ArrayList<String>();
        private final List<String> rationale = new ArrayList<String>();
        private final List<String> warnings = new ArrayList<String>();
        private final List<String> filterReasons = new ArrayList<String>();
        private String tier = UNRESOLVED;
        private String tierReason = "Evidence is incomplete";
        private double totalScore;
        private boolean pathogenicClinVar;
        private boolean benignClinVar;
        private boolean vusClinVar;
        private boolean conflictingClinVar;
        private boolean common;
        private boolean veryCommon;
        private boolean gnomadNoRecord;
        private boolean gnomadUnavailable;
        private boolean highImpact;
        private boolean synonymous;
        private boolean computationalHighlight;
        private boolean spliceHighlight;
        private boolean qcFiltered;
        private boolean nonCarriedAlternate;
        private Double cadd;

        private RankingContext(VariantInterpretation variant,
                               VariantAnalysisProperties.Ranking ranking) {
            this.variant = variant;
            this.ranking = ranking;
        }

        private void addComponent(String key, double score, String reason) {
            addComponent(key, score, Collections.singletonList(reason));
        }

        private void addComponent(String key, double score, List<String> reasons) {
            double finiteScore = Double.isFinite(score) ? score : 0.0d;
            totalScore += finiteScore;
            numericBreakdown.put(key, finiteScore);
            components.put(key, new ScoreBreakdownComponent(finiteScore, reasons));
            rationale.addAll(reasons);
        }

        private Double score(String source) {
            Double maximum = null;
            for (EvidenceItem item : variant.getEvidence()) {
                if (source.equals(item.getSource()) && "APPLICABLE".equals(item.getApplicability())) {
                    Double rawScore = finiteScore(item.getRawScore());
                    if (rawScore != null && (maximum == null || rawScore > maximum)) {
                        maximum = rawScore;
                    }
                }
            }
            return maximum;
        }

        private EvidenceItem bestEvidence(String source) {
            EvidenceItem best = null;
            int bestRank = Integer.MIN_VALUE;
            for (EvidenceItem item : variant.getEvidence()) {
                if (!source.equals(item.getSource())) {
                    continue;
                }
                int rank = applicabilityRank(item.getApplicability());
                if (rank > bestRank) {
                    best = item;
                    bestRank = rank;
                } else if (rank == bestRank && best != null
                        && scoreOrNegative(item.getRawScore()) > scoreOrNegative(best.getRawScore())) {
                    best = item;
                }
            }
            return best;
        }

        private int applicabilityRank(String applicability) {
            if ("APPLICABLE".equals(applicability)) {
                return 5;
            }
            if ("CONFLICTING".equals(applicability)) {
                return 4;
            }
            if ("NO_RECORD".equals(applicability)) {
                return 3;
            }
            if ("NOT_APPLICABLE".equals(applicability)) {
                return 2;
            }
            if ("UNAVAILABLE".equals(applicability)) {
                return 1;
            }
            return 0;
        }

        private double scoreOrNegative(Double score) {
            Double finite = finiteScore(score);
            return finite == null ? Double.NEGATIVE_INFINITY : finite;
        }

        private void apply() {
            List<String> allRationale = new ArrayList<String>();
            allRationale.add(tierReason);
            allRationale.addAll(rationale);
            variant.applyRanking(
                    tier,
                    totalScore,
                    numericBreakdown,
                    components,
                    tierReason,
                    positiveReasons,
                    negativeReasons,
                    allRationale,
                    warnings,
                    filterReasons
            );
        }
    }
}
