package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInputStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeSupportedCandidate;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PhenotypeAwareRerankingService {

    private static final String COMPONENT = "phenotype_support";
    private static final String EVIDENCE_TYPE = "PHENOTYPE_GENE_PRIOR";
    private static final int TOP_SUPPORTED_CANDIDATE_LIMIT = 20;

    private final VariantAnalysisProperties properties;

    public PhenotypeAwareRerankingService(VariantAnalysisProperties properties) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
    }

    public PhenotypeAwareSummary apply(List<VariantInterpretation> variants,
                                       PhenotypeAwareSummary phenotypeSummary) {
        if (!isReady(variants, phenotypeSummary)) {
            return phenotypeSummary == null ? PhenotypeAwareSummary.disabled() : phenotypeSummary;
        }
        Map<String, GenePhenotypePrior> priorByGene =
                priorByGene(phenotypeSummary.getGenePriorSummary().getTopGenePriors());
        if (priorByGene.isEmpty()) {
            return phenotypeSummary;
        }
        if (!hasAnyGeneAnnotation(variants)) {
            return phenotypeSummary.withWarnings(noGeneLinkageWarnings(phenotypeSummary.getWarnings()));
        }
        List<PhenotypeSupportedCandidate> supportedCandidates =
                new ArrayList<PhenotypeSupportedCandidate>();
        for (VariantInterpretation variant : variants) {
            if (!canApplyToTier(variant.getPriorityTier())) {
                continue;
            }
            GenePhenotypePrior prior = matchingPrior(variant, priorByGene);
            if (prior == null) {
                continue;
            }
            PhenotypeBoostCalculation calculation = scoreBoost(prior, phenotypeSummary);
            if (calculation.finalBoost <= 0.0d) {
                continue;
            }
            applySupport(variant, prior, calculation);
            supportedCandidates.add(new PhenotypeSupportedCandidate(
                    variant.getVariantId(),
                    prior.getGeneSymbol(),
                    variant.getPriorityTier(),
                    prior.getPhenotypeScore(),
                    prior.getPhenotypeRank(),
                    calculation.finalBoost,
                    prior.getSource()
            ));
        }
        if (supportedCandidates.isEmpty()) {
            return phenotypeSummary;
        }
        return phenotypeSummary.withRankingApplied(
                supportedCandidates.size(),
                topSupportedCandidates(supportedCandidates),
                rankingWarnings(phenotypeSummary.getWarnings())
        );
    }

    private boolean isReady(List<VariantInterpretation> variants, PhenotypeAwareSummary phenotypeSummary) {
        if (!properties.getPhenotypeRanking().isEnabled()) {
            return false;
        }
        if (variants == null || variants.isEmpty() || phenotypeSummary == null || !phenotypeSummary.isEnabled()) {
            return false;
        }
        GenePhenotypePriorSummary priorSummary = phenotypeSummary.getGenePriorSummary();
        return priorSummary != null
                && priorSummary.getStatus() == GenePhenotypePriorStatus.AVAILABLE
                && !priorSummary.getTopGenePriors().isEmpty();
    }

    private Map<String, GenePhenotypePrior> priorByGene(List<GenePhenotypePrior> priors) {
        Map<String, GenePhenotypePrior> result = new LinkedHashMap<String, GenePhenotypePrior>();
        if (priors == null) {
            return result;
        }
        for (GenePhenotypePrior prior : priors) {
            String key = normalize(prior.getGeneSymbol());
            if (key != null && !result.containsKey(key)) {
                result.put(key, prior);
            }
        }
        return result;
    }

    private boolean canApplyToTier(String tier) {
        if ("KNOWN_CLINICAL".equals(tier) || "HIGH_PRIORITY".equals(tier)
                || "REVIEW".equals(tier) || "UNRESOLVED".equals(tier)) {
            return true;
        }
        if ("FILTERED_COMMON".equals(tier)) {
            return properties.getPhenotypeRanking().isApplyToFilteredCommon();
        }
        if ("FILTERED_QC".equals(tier)) {
            return properties.getPhenotypeRanking().isApplyToFilteredQc();
        }
        return false;
    }

    private GenePhenotypePrior matchingPrior(VariantInterpretation variant,
                                             Map<String, GenePhenotypePrior> priorByGene) {
        for (String gene : geneSymbols(variant)) {
            GenePhenotypePrior prior = priorByGene.get(normalize(gene));
            if (prior != null) {
                return prior;
            }
        }
        return null;
    }

    private boolean hasAnyGeneAnnotation(List<VariantInterpretation> variants) {
        if (variants == null || variants.isEmpty()) {
            return false;
        }
        for (VariantInterpretation variant : variants) {
            if (variant != null && !geneSymbols(variant).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Set<String> geneSymbols(VariantInterpretation variant) {
        Set<String> genes = new LinkedHashSet<String>();
        addGenes(genes, variant.getConsequences());
        addGenes(genes, variant.getTranscriptAnnotations());
        return genes;
    }

    private void addGenes(Set<String> genes, List<TranscriptConsequence> consequences) {
        if (consequences == null) {
            return;
        }
        for (TranscriptConsequence consequence : consequences) {
            if (consequence != null && consequence.getGeneSymbol() != null
                    && !consequence.getGeneSymbol().trim().isEmpty()) {
                genes.add(consequence.getGeneSymbol().trim());
            }
        }
    }

    private PhenotypeBoostCalculation scoreBoost(GenePhenotypePrior prior,
                                                  PhenotypeAwareSummary phenotypeSummary) {
        double baseBoost = baseScoreBoost(prior.getPhenotypeScore());
        if (baseBoost <= 0.0d) {
            return PhenotypeBoostCalculation.none();
        }
        VariantAnalysisProperties.PhenotypeRanking ranking = properties.getPhenotypeRanking();
        if (!ranking.isConfidenceAware()) {
            return new PhenotypeBoostCalculation(baseBoost, 1.0d, 1.0d, baseBoost, 0, 0);
        }
        List<PhenotypeInput> validInputs = validInputs(phenotypeSummary);
        if (validInputs.isEmpty()) {
            double confidence = clamp01(ranking.getUnknownSourceConfidenceCap());
            return boundedCalculation(baseBoost, confidence,
                    clamp01(ranking.getMinimumMatchedTermCoverageFactor()), 0, 0);
        }
        Set<String> matchedTerms = normalizedTerms(prior.getMatchedHpoTerms());
        List<PhenotypeInput> matchedInputs = new ArrayList<PhenotypeInput>();
        for (PhenotypeInput input : validInputs) {
            if (matchedTerms.contains(normalize(input.getHpoId()))) {
                matchedInputs.add(input);
            }
        }
        List<PhenotypeInput> confidenceInputs = matchedInputs.isEmpty() ? validInputs : matchedInputs;
        double confidence = averageConfidence(confidenceInputs, ranking);
        if (confidence < clamp01(ranking.getMinimumConfidenceForBoost())) {
            return PhenotypeBoostCalculation.none(baseBoost, confidence,
                    matchedInputs.size(), validInputs.size());
        }
        double rawCoverage = matchedInputs.isEmpty()
                ? 0.0d
                : (double) matchedInputs.size() / (double) validInputs.size();
        double minimumCoverage = clamp01(ranking.getMinimumMatchedTermCoverageFactor());
        double coverage = minimumCoverage + (1.0d - minimumCoverage) * clamp01(rawCoverage);
        return boundedCalculation(baseBoost, confidence, coverage,
                matchedInputs.size(), validInputs.size());
    }

    private double baseScoreBoost(double phenotypeScore) {
        VariantAnalysisProperties.PhenotypeRanking ranking = properties.getPhenotypeRanking();
        double max = finiteNonNegative(ranking.normalizedMaxScoreBoost());
        if (max <= 0.0d || !Double.isFinite(phenotypeScore)) {
            return 0.0d;
        }
        if (phenotypeScore >= ranking.getStrongThreshold()) {
            return max;
        }
        if (phenotypeScore >= ranking.getModerateThreshold()) {
            return max * 0.6d;
        }
        if (phenotypeScore >= ranking.getLowThreshold()) {
            return max * 0.25d;
        }
        return 0.0d;
    }

    private PhenotypeBoostCalculation boundedCalculation(double baseBoost,
                                                          double confidence,
                                                          double coverage,
                                                          int matchedInputCount,
                                                          int validInputCount) {
        double max = finiteNonNegative(properties.getPhenotypeRanking().normalizedMaxScoreBoost());
        double finalBoost = Math.min(max, Math.max(0.0d,
                baseBoost * clamp01(confidence) * clamp01(coverage)));
        return new PhenotypeBoostCalculation(baseBoost, clamp01(confidence), clamp01(coverage),
                finalBoost, matchedInputCount, validInputCount);
    }

    private List<PhenotypeInput> validInputs(PhenotypeAwareSummary summary) {
        List<PhenotypeInput> result = new ArrayList<PhenotypeInput>();
        if (summary == null || summary.getPhenotypeInputs() == null) {
            return result;
        }
        for (PhenotypeInput input : summary.getPhenotypeInputs()) {
            if (input != null && input.getStatus() == PhenotypeInputStatus.VALID
                    && !input.isExcluded()
                    && normalize(input.getHpoId()) != null) {
                result.add(input);
            }
        }
        return result;
    }

    private Set<String> normalizedTerms(List<String> terms) {
        Set<String> result = new LinkedHashSet<String>();
        if (terms == null) {
            return result;
        }
        for (String term : terms) {
            String normalized = normalize(term);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    private double averageConfidence(List<PhenotypeInput> inputs,
                                     VariantAnalysisProperties.PhenotypeRanking ranking) {
        if (inputs == null || inputs.isEmpty()) {
            return clamp01(ranking.getUnknownSourceConfidenceCap());
        }
        double total = 0.0d;
        for (PhenotypeInput input : inputs) {
            double explicit = input.getConfidence() == null ? 1.0d : clamp01(input.getConfidence());
            total += Math.min(explicit, sourceConfidenceCap(input.getSource(), ranking));
        }
        return total / inputs.size();
    }

    private double sourceConfidenceCap(String source,
                                       VariantAnalysisProperties.PhenotypeRanking ranking) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("CLINICAL") || normalized.contains("PHYSICIAN")
                || normalized.contains("DOCTOR")) {
            return 1.0d;
        }
        if (normalized.contains("AI")) {
            return clamp01(ranking.getAiSourceConfidenceCap());
        }
        if (normalized.contains("MANUAL") || normalized.contains("TABLE")) {
            return clamp01(ranking.getManualSourceConfidenceCap());
        }
        return clamp01(ranking.getUnknownSourceConfidenceCap());
    }

    private double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private void applySupport(VariantInterpretation variant,
                              GenePhenotypePrior prior,
                              PhenotypeBoostCalculation calculation) {
        double boost = calculation.finalBoost;
        Map<String, Double> scoreBreakdown = new LinkedHashMap<String, Double>(variant.getScoreBreakdown());
        double baseScore = scoreOrZero(variant.getRankingScore());
        Double existingBoost = scoreBreakdown.get(COMPONENT);
        if (existingBoost != null && Double.isFinite(existingBoost.doubleValue())) {
            baseScore -= existingBoost.doubleValue();
        }
        scoreBreakdown.put(COMPONENT, boost);
        Map<String, ScoreBreakdownComponent> scoreComponents =
                new LinkedHashMap<String, ScoreBreakdownComponent>(variant.getScoreComponents());
        List<String> reasons = componentReasons(prior, calculation);
        scoreComponents.put(COMPONENT, new ScoreBreakdownComponent(boost, reasons));

        List<String> positiveReasons = new ArrayList<String>(variant.getPositiveReasons());
        addIfAbsent(positiveReasons, "Gene " + prior.getGeneSymbol()
                + " is supported by phenotype prior from HPO terms.");
        List<String> rationale = new ArrayList<String>(variant.getRationale());
        addAllIfAbsent(rationale, reasons);
        List<String> warnings = new ArrayList<String>(variant.getWarnings());
        addIfAbsent(warnings, "Phenotype support requires manual review and is not a diagnosis.");

        variant.addEvidence(Collections.singletonList(phenotypeEvidence(prior, calculation)));
        variant.applyRanking(
                variant.getPriorityTier(),
                baseScore + boost,
                scoreBreakdown,
                scoreComponents,
                variant.getTierReason(),
                positiveReasons,
                variant.getNegativeReasons(),
                rationale,
                warnings,
                variant.getFilterReasons()
        );
    }

    private List<String> componentReasons(GenePhenotypePrior prior,
                                          PhenotypeBoostCalculation calculation) {
        List<String> reasons = new ArrayList<String>();
        reasons.add("Gene " + prior.getGeneSymbol()
                + " matched phenotype prior rank " + prior.getPhenotypeRank()
                + " with score " + format(prior.getPhenotypeScore())
                + "; phenotype support is candidate prioritization only.");
        reasons.add("Matched HPO terms: " + String.join(", ", prior.getMatchedHpoTerms()));
        reasons.add("Phenotype support boost: base " + format(calculation.baseBoost)
                + " x provenance confidence " + format(calculation.confidenceFactor)
                + " x matched-term coverage " + format(calculation.coverageFactor)
                + " = " + format(calculation.finalBoost) + ".");
        return reasons;
    }

    private EvidenceItem phenotypeEvidence(GenePhenotypePrior prior,
                                            PhenotypeBoostCalculation calculation) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("geneSymbol", prior.getGeneSymbol());
        details.put("geneId", prior.getGeneId());
        details.put("phenotypeScore", prior.getPhenotypeScore());
        details.put("phenotypeRank", prior.getPhenotypeRank());
        details.put("matchedHpoTerms", prior.getMatchedHpoTerms());
        details.put("evidence", prior.getEvidence());
        details.put("baseScoreBoost", calculation.baseBoost);
        details.put("provenanceConfidenceFactor", calculation.confidenceFactor);
        details.put("matchedTermCoverageFactor", calculation.coverageFactor);
        details.put("matchedInputCount", calculation.matchedInputCount);
        details.put("validInputCount", calculation.validInputCount);
        details.put("scoreBoost", calculation.finalBoost);
        return new EvidenceItem(
                prior.getSource(),
                prior.getSourceVersion(),
                EVIDENCE_TYPE,
                "APPLICABLE",
                prior.getPhenotypeScore(),
                "Gene phenotype prior",
                "Gene " + prior.getGeneSymbol()
                        + " is supported by phenotype prior; this is not a diagnosis.",
                details
        );
    }

    private List<PhenotypeSupportedCandidate> topSupportedCandidates(
            List<PhenotypeSupportedCandidate> supportedCandidates) {
        List<PhenotypeSupportedCandidate> ordered =
                new ArrayList<PhenotypeSupportedCandidate>(supportedCandidates);
        Collections.sort(ordered, new Comparator<PhenotypeSupportedCandidate>() {
            @Override
            public int compare(PhenotypeSupportedCandidate left, PhenotypeSupportedCandidate right) {
                int boostCompare = Double.compare(right.getScoreBoost(), left.getScoreBoost());
                if (boostCompare != 0) {
                    return boostCompare;
                }
                int scoreCompare = Double.compare(right.getPhenotypeScore(), left.getPhenotypeScore());
                if (scoreCompare != 0) {
                    return scoreCompare;
                }
                return Integer.compare(left.getPhenotypeRank(), right.getPhenotypeRank());
            }
        });
        if (ordered.size() <= TOP_SUPPORTED_CANDIDATE_LIMIT) {
            return ordered;
        }
        return new ArrayList<PhenotypeSupportedCandidate>(
                ordered.subList(0, TOP_SUPPORTED_CANDIDATE_LIMIT)
        );
    }

    private List<String> rankingWarnings(List<String> existingWarnings) {
        List<String> warnings = new ArrayList<String>();
        if (existingWarnings != null) {
            for (String warning : existingWarnings) {
                if (!PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING.equals(warning)) {
                    warnings.add(warning);
                }
            }
        }
        addIfAbsent(warnings, "Phenotype support is candidate prioritization only; it is not a diagnosis.");
        return warnings;
    }

    private List<String> noGeneLinkageWarnings(List<String> existingWarnings) {
        List<String> warnings = new ArrayList<String>();
        if (existingWarnings != null) {
            warnings.addAll(existingWarnings);
        }
        String warning = "HPO_INPUT_PRESENT_BUT_NO_GENE_LINKAGE: phenotype terms and gene prior are available, "
                + "but no candidate has gene annotation for phenotype-aware linkage.";
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
        return warnings;
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private double scoreOrZero(Double score) {
        return score == null || !Double.isFinite(score.doubleValue()) ? 0.0d : score;
    }

    private double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0d, value) : 0.0d;
    }

    private void addAllIfAbsent(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addIfAbsent(target, value);
        }
    }

    private void addIfAbsent(List<String> target, String value) {
        if (value != null && !target.contains(value)) {
            target.add(value);
        }
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static final class PhenotypeBoostCalculation {

        private final double baseBoost;
        private final double confidenceFactor;
        private final double coverageFactor;
        private final double finalBoost;
        private final int matchedInputCount;
        private final int validInputCount;

        private PhenotypeBoostCalculation(double baseBoost,
                                          double confidenceFactor,
                                          double coverageFactor,
                                          double finalBoost,
                                          int matchedInputCount,
                                          int validInputCount) {
            this.baseBoost = baseBoost;
            this.confidenceFactor = confidenceFactor;
            this.coverageFactor = coverageFactor;
            this.finalBoost = finalBoost;
            this.matchedInputCount = matchedInputCount;
            this.validInputCount = validInputCount;
        }

        private static PhenotypeBoostCalculation none() {
            return new PhenotypeBoostCalculation(0.0d, 0.0d, 0.0d, 0.0d, 0, 0);
        }

        private static PhenotypeBoostCalculation none(double baseBoost,
                                                      double confidence,
                                                      int matchedInputCount,
                                                      int validInputCount) {
            return new PhenotypeBoostCalculation(baseBoost, confidence, 0.0d, 0.0d,
                    matchedInputCount, validInputCount);
        }
    }
}
