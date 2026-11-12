package org.nbscn.varvis.variantanalysis.trio.ranking;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.trio.TrioEvidenceReportEntry;
import org.nbscn.varvis.variantanalysis.trio.TrioGenotypeCall;
import org.nbscn.varvis.variantanalysis.trio.TrioGenotypeObservation;
import org.nbscn.varvis.variantanalysis.trio.TrioRole;
import org.nbscn.varvis.variantanalysis.trio.TrioVariantObservation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TrioRankingContributionService {

    public static final String COMPONENT = "trio_segregation_support";

    private final VariantAnalysisProperties properties;

    public TrioRankingContributionService(VariantAnalysisProperties properties) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
    }

    public TrioRankingContribution calculate(VariantInterpretation interpretation,
                                             TrioEvidenceReportEntry trioEvidence) {
        return calculate(interpretation, trioEvidence, null);
    }

    public TrioRankingContribution calculate(VariantInterpretation interpretation,
                                             TrioEvidenceReportEntry trioEvidence,
                                             TrioVariantObservation observation) {
        String model = trioEvidence == null || trioEvidence.getModel() == null
                ? null
                : trioEvidence.getModel().name();
        String confidence = trioEvidence == null || trioEvidence.getConfidence() == null
                ? null
                : trioEvidence.getConfidence().name();
        List<String> warnings = trioEvidence == null
                ? Collections.<String>emptyList()
                : trioEvidence.getWarnings();
        return calculateInternal(
                interpretation,
                model,
                confidence,
                trioEvidence == null || trioEvidence.getParentalOrigin() == null
                        ? null
                        : trioEvidence.getParentalOrigin().name(),
                observation,
                warnings,
                trioEvidence != null && !trioEvidence.getPartnerVariantIds().isEmpty(),
                false,
                false);
    }

    public TrioRankingContribution calculate(VariantInterpretation interpretation,
                                             EvidenceItem trioEvidence) {
        return calculate(interpretation, trioEvidence, observationFromDetails(trioEvidence));
    }

    public TrioRankingContribution calculate(VariantInterpretation interpretation,
                                             EvidenceItem trioEvidence,
                                             TrioVariantObservation observation) {
        String model = detail(trioEvidence, "model");
        String confidence = detail(trioEvidence, "confidence");
        String parentalOrigin = detail(trioEvidence, "parentalOrigin");
        return calculateInternal(
                interpretation,
                model,
                confidence,
                parentalOrigin,
                observation,
                Collections.<String>emptyList(),
                hasPartnerVariant(trioEvidence),
                Boolean.parseBoolean(detail(trioEvidence, "syntheticSharedUnaffectedParent")),
                Boolean.parseBoolean(detail(trioEvidence, "syntheticDominantLikeModel")));
    }

    public void applyContribution(VariantInterpretation interpretation,
                                  TrioRankingContribution contribution) {
        if (interpretation == null || contribution == null || !contribution.isApplied()
                || !Double.isFinite(contribution.getContributionScore())
                || contribution.getContributionScore() == 0.0d) {
            return;
        }
        Map<String, Double> scoreBreakdown =
                new LinkedHashMap<String, Double>(interpretation.getScoreBreakdown());
        double baseScore = scoreOrZero(interpretation.getRankingScore());
        Double existingContribution = scoreBreakdown.get(COMPONENT);
        if (existingContribution != null && Double.isFinite(existingContribution.doubleValue())) {
            baseScore -= existingContribution.doubleValue();
        }
        scoreBreakdown.put(COMPONENT, contribution.getContributionScore());
        Map<String, ScoreBreakdownComponent> scoreComponents =
                new LinkedHashMap<String, ScoreBreakdownComponent>(interpretation.getScoreComponents());
        List<String> reasons = componentReasons(contribution);
        scoreComponents.put(COMPONENT, new ScoreBreakdownComponent(
                contribution.getContributionScore(),
                reasons));

        List<String> positiveReasons = new ArrayList<String>(interpretation.getPositiveReasons());
        List<String> negativeReasons = new ArrayList<String>(interpretation.getNegativeReasons());
        if (contribution.getContributionDirection() == TrioRankingContributionDirection.PENALTY) {
            addIfAbsent(negativeReasons, contribution.getExplanation());
        } else {
            addIfAbsent(positiveReasons, contribution.getExplanation());
        }
        List<String> rationale = new ArrayList<String>(interpretation.getRationale());
        addAllIfAbsent(rationale, reasons);
        List<String> warnings = new ArrayList<String>(interpretation.getWarnings());
        addIfAbsent(warnings, "Trio weighted ranking is feature-flagged, bounded, and not a clinical diagnosis.");
        addAllIfAbsent(warnings, contribution.getWarnings());

        interpretation.applyRanking(
                interpretation.getPriorityTier(),
                baseScore + contribution.getContributionScore(),
                scoreBreakdown,
                scoreComponents,
                interpretation.getTierReason(),
                positiveReasons,
                negativeReasons,
                rationale,
                warnings,
                interpretation.getFilterReasons()
        );
    }

    private TrioRankingContribution calculateInternal(VariantInterpretation interpretation,
                                                      String model,
                                                      String confidence,
                                                      String parentalOrigin,
                                                      TrioVariantObservation observation,
                                                      List<String> warnings,
                                                      boolean hasPartnerVariant,
                                                      boolean syntheticSharedUnaffectedParent,
                                                      boolean syntheticDominantLikeModel) {
        VariantAnalysisProperties.Trio trio = properties.getTrio();
        String variantId = interpretation == null ? null : interpretation.getVariantId();
        String variantKey = variantKey(interpretation);
        if (!trio.isRankingEnabled()) {
            return neutral(
                    variantId,
                    variantKey,
                    false,
                    TrioRankingContributionLevel.DISABLED,
                    TrioRankingNotAppliedReason.FEATURE_DISABLED,
                    model,
                    confidence,
                    false,
                    "Trio weighted ranking is disabled; segregation evidence remains explanation-only.",
                    warnings);
        }

        if (isHardGateProtected(interpretation)) {
            return neutral(
                    variantId,
                    variantKey,
                    true,
                    TrioRankingContributionLevel.NEUTRAL,
                    TrioRankingNotAppliedReason.HARD_GATE_PROTECTED,
                    model,
                    confidence,
                    true,
                    "Trio evidence cannot override existing hard gates.",
                    warnings);
        }

        if (trio.normalizedRankingMaxScoreContribution() <= 0.0d) {
            return neutral(
                    variantId,
                    variantKey,
                    true,
                    TrioRankingContributionLevel.NEUTRAL,
                    TrioRankingNotAppliedReason.MAX_CONTRIBUTION_ZERO,
                    model,
                    confidence,
                    false,
                    "Trio weighted ranking is enabled but the maximum contribution is 0.",
                    warnings);
        }

        String normalizedModel = normalize(model);
        if (isSharedParentPenaltyCandidate(model, confidence)
                && hasExplicitSharedParentContext(syntheticSharedUnaffectedParent, syntheticDominantLikeModel)) {
            if (!canApplySharedParentPenalty(
                    trio,
                    model,
                    confidence,
                    syntheticSharedUnaffectedParent,
                    syntheticDominantLikeModel)) {
                return neutral(
                        variantId,
                        variantKey,
                        true,
                        TrioRankingContributionLevel.NEUTRAL,
                        TrioRankingNotAppliedReason.INSUFFICIENT_DISEASE_MODEL_CONTEXT,
                        model,
                        confidence,
                        false,
                        "Shared-parent trio evidence is context only unless penalty flags and disease context are explicit.",
                        warnings);
            }
            return contribution(
                    variantId,
                    variantKey,
                    TrioRankingContributionLevel.WEAK_PENALTY,
                    TrioRankingContributionDirection.PENALTY,
                    cappedScore(-weakScore(), true),
                    model,
                    confidence,
                    "Explicit synthetic shared-parent context contributes a weak bounded trio penalty.",
                    warnings);
        }

        if (!"SUPPORTED".equals(normalize(confidence))
                && !canApplyPossibleBoost(trio, model, confidence)
                && !canApplySharedParentPenalty(
                trio,
                model,
                confidence,
                syntheticSharedUnaffectedParent,
                syntheticDominantLikeModel)) {
            return neutral(
                    variantId,
                    variantKey,
                    true,
                    TrioRankingContributionLevel.NEUTRAL,
                    TrioRankingNotAppliedReason.LOW_CONFIDENCE,
                    model,
                    confidence,
                    false,
                    "Trio evidence confidence is not sufficient for weighted ranking.",
                    warnings);
        }
        if ("DE_NOVO_CANDIDATE".equals(normalizedModel)) {
            TrioRankingNotAppliedReason parentReason = deNovoParentBlocker(observation);
            if (parentReason != null) {
                return neutral(
                        variantId,
                        variantKey,
                        true,
                        TrioRankingContributionLevel.NEUTRAL,
                        parentReason,
                        model,
                        confidence,
                        false,
                        "De novo boost requires both parents to be confirmed reference.",
                        warnings);
            }
            return contribution(
                    variantId,
                    variantKey,
                    TrioRankingContributionLevel.STRONG_BOOST,
                    TrioRankingContributionDirection.BOOST,
                    cappedScore(strongScore(), false),
                    model,
                    confidence,
                    "Supported de novo segregation contributes a bounded trio boost.",
                    warnings);
        }
        if ("COMPOUND_HETEROZYGOUS_CANDIDATE".equals(normalizedModel)) {
            if (!hasPartnerVariant || (!"PATERNAL".equals(normalize(parentalOrigin))
                    && !"MATERNAL".equals(normalize(parentalOrigin))
                    && !"BIPARENTAL".equals(normalize(parentalOrigin)))) {
                return neutral(
                        variantId,
                        variantKey,
                        true,
                        TrioRankingContributionLevel.NEUTRAL,
                        TrioRankingNotAppliedReason.UNSUPPORTED_MODEL,
                        model,
                        confidence,
                        false,
                        "Compound heterozygous boost requires complementary parental origin.",
                        warnings);
            }
            return contribution(
                    variantId,
                    variantKey,
                    TrioRankingContributionLevel.STRONG_BOOST,
                    TrioRankingContributionDirection.BOOST,
                    cappedScore(strongScore(), false),
                    model,
                    confidence,
                    "Supported compound heterozygous evidence contributes a bounded trio boost.",
                    warnings);
        }
        if ("HOMOZYGOUS_RECESSIVE_CANDIDATE".equals(normalizedModel)) {
            return contribution(
                    variantId,
                    variantKey,
                    TrioRankingContributionLevel.MODERATE_BOOST,
                    TrioRankingContributionDirection.BOOST,
                    cappedScore(moderateScore(), false),
                    model,
                    confidence,
                    "Supported homozygous recessive evidence contributes a bounded trio boost.",
                    warnings);
        }
        if ("X_LINKED_CANDIDATE".equals(normalizedModel)) {
            return contribution(
                    variantId,
                    variantKey,
                    TrioRankingContributionLevel.MODERATE_BOOST,
                    TrioRankingContributionDirection.BOOST,
                    cappedScore(moderateScore(), false),
                    model,
                    confidence,
                    "Supported X-linked evidence contributes a bounded trio boost.",
                    warnings);
        }
        if (canApplyPossibleBoost(trio, model, confidence)) {
            return contribution(
                    variantId,
                    variantKey,
                    TrioRankingContributionLevel.WEAK_BOOST,
                    TrioRankingContributionDirection.BOOST,
                    cappedScore(weakScore(), false),
                    model,
                    confidence,
                    "Possible inherited trio evidence contributes a weak bounded boost.",
                    warnings);
        }
        return neutral(
                variantId,
                variantKey,
                true,
                TrioRankingContributionLevel.NEUTRAL,
                TrioRankingNotAppliedReason.EVIDENCE_UNINFORMATIVE,
                model,
                confidence,
                false,
                "Trio evidence is uninformative for weighted ranking.",
                warnings);
    }

    private TrioRankingContribution neutral(String variantId,
                                            String variantKey,
                                            boolean enabled,
                                            TrioRankingContributionLevel level,
                                            TrioRankingNotAppliedReason reason,
                                            String model,
                                            String confidence,
                                            boolean hardGateProtected,
                                            String explanation,
                                            List<String> warnings) {
        return new TrioRankingContribution(
                variantId,
                variantKey,
                enabled,
                false,
                level,
                TrioRankingContributionDirection.NEUTRAL,
                0.0d,
                false,
                reason,
                model,
                confidence,
                hardGateProtected,
                explanation,
                warnings);
    }

    private TrioRankingContribution contribution(String variantId,
                                                 String variantKey,
                                                 TrioRankingContributionLevel level,
                                                 TrioRankingContributionDirection direction,
                                                 double score,
                                                 String model,
                                                 String confidence,
                                                 String explanation,
                                                 List<String> warnings) {
        return new TrioRankingContribution(
                variantId,
                variantKey,
                true,
                true,
                level,
                direction,
                score,
                score != 0.0d,
                null,
                model,
                confidence,
                false,
                explanation,
                warnings);
    }

    private TrioRankingNotAppliedReason deNovoParentBlocker(TrioVariantObservation observation) {
        if (observation == null) {
            return TrioRankingNotAppliedReason.MISSING_SAMPLE_MAPPING;
        }
        TrioGenotypeObservation proband = observation.genotype(TrioRole.PROBAND);
        if (proband != null && !proband.carriesAlternateAllele()) {
            return TrioRankingNotAppliedReason.LOW_CONFIDENCE;
        }
        TrioRankingNotAppliedReason father = parentBlocker(observation.genotype(TrioRole.FATHER));
        if (father != null) {
            return father;
        }
        return parentBlocker(observation.genotype(TrioRole.MOTHER));
    }

    private TrioRankingNotAppliedReason parentBlocker(TrioGenotypeObservation genotype) {
        if (genotype == null) {
            return TrioRankingNotAppliedReason.PARENT_UNOBSERVED;
        }
        switch (genotype.getCall()) {
            case HOM_REF:
                return null;
            case UNOBSERVED:
                return TrioRankingNotAppliedReason.PARENT_UNOBSERVED;
            case NO_CALL:
                return TrioRankingNotAppliedReason.PARENT_NO_CALL;
            case UNCONFIRMED_HOM_REF:
                return TrioRankingNotAppliedReason.PARENT_UNCONFIRMED_HOM_REF;
            default:
                return TrioRankingNotAppliedReason.UNSUPPORTED_MODEL;
        }
    }

    private boolean canApplyPossibleBoost(VariantAnalysisProperties.Trio trio, String model, String confidence) {
        return trio.isRankingAllowPossibleBoost()
                && "INHERITED_CANDIDATE".equals(normalize(model))
                && "POSSIBLE".equals(normalize(confidence));
    }

    private boolean canApplySharedParentPenalty(VariantAnalysisProperties.Trio trio,
                                                String model,
                                                String confidence,
                                                boolean syntheticSharedUnaffectedParent,
                                                boolean syntheticDominantLikeModel) {
        return isSharedParentPenaltyCandidate(model, confidence)
                && trio.isRankingAllowNegativePenalty()
                && trio.isRankingEnableSharedParentPenalty()
                && syntheticSharedUnaffectedParent
                && syntheticDominantLikeModel;
    }

    private boolean hasExplicitSharedParentContext(boolean syntheticSharedUnaffectedParent,
                                                   boolean syntheticDominantLikeModel) {
        return syntheticSharedUnaffectedParent || syntheticDominantLikeModel;
    }

    private boolean isSharedParentPenaltyCandidate(String model, String confidence) {
        return "INHERITED_CANDIDATE".equals(normalize(model))
                && ("POSSIBLE".equals(normalize(confidence)) || "SUPPORTED".equals(normalize(confidence)));
    }

    private double strongScore() {
        return properties.getTrio().normalizedRankingMaxScoreContribution();
    }

    private double moderateScore() {
        return properties.getTrio().normalizedRankingMaxScoreContribution() * 0.6d;
    }

    private double weakScore() {
        return properties.getTrio().normalizedRankingMaxScoreContribution() * 0.25d;
    }

    private double cappedScore(double value, boolean penalty) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        if (penalty && !properties.getTrio().isRankingAllowNegativePenalty()) {
            return 0.0d;
        }
        double cap = properties.getTrio().normalizedRankingMaxScoreContribution();
        if (cap <= 0.0d) {
            return 0.0d;
        }
        if (value > cap) {
            return cap;
        }
        if (value < -cap) {
            return -cap;
        }
        return value;
    }

    private List<String> componentReasons(TrioRankingContribution contribution) {
        List<String> reasons = new ArrayList<String>();
        reasons.add(contribution.getExplanation());
        reasons.add("Trio ranking contribution: "
                + contribution.getContributionDirection().name()
                + " / " + contribution.getContributionLevel().name()
                + " / score " + contribution.getContributionScore());
        reasons.add("Trio contribution is bounded by variant-analysis.trio.ranking-max-score-contribution.");
        reasons.add("TRIO EvidenceItem.rawScore remains null; this component is a feature-flagged ranking delta.");
        return reasons;
    }

    private boolean isHardGateProtected(VariantInterpretation interpretation) {
        if (interpretation == null) {
            return false;
        }
        VariantAnalysisBucket bucket = interpretation.getAnalysisBucket();
        if (bucket == VariantAnalysisBucket.FILTERED_COMMON
                || bucket == VariantAnalysisBucket.FILTERED_QC
                || bucket == VariantAnalysisBucket.FILTERED_NOT_CARRIED) {
            return true;
        }
        String tier = normalize(interpretation.getPriorityTier());
        if ("FILTERED_COMMON".equals(tier)
                || "POPULATION_FILTERED".equals(tier)
                || "FILTERED_QC".equals(tier)
                || "BENIGN_LIKELY".equals(tier)) {
            return true;
        }
        for (String reason : interpretation.getFilterReasons()) {
            if (reason != null && reason.toUpperCase(Locale.ROOT).contains("GT=0/0")) {
                return true;
            }
        }
        return false;
    }

    private String detail(EvidenceItem evidence, String name) {
        if (evidence == null || evidence.getDetails() == null) {
            return null;
        }
        Map<String, Object> details = evidence.getDetails();
        Object value = details.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private TrioVariantObservation observationFromDetails(EvidenceItem evidence) {
        if (evidence == null || evidence.getDetails() == null) {
            return null;
        }
        Map<TrioRole, TrioGenotypeObservation> genotypes =
                new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);
        addGenotype(genotypes, TrioRole.PROBAND, detail(evidence, "probandGenotype"));
        addGenotype(genotypes, TrioRole.FATHER, detail(evidence, "fatherGenotype"));
        addGenotype(genotypes, TrioRole.MOTHER, detail(evidence, "motherGenotype"));
        if (genotypes.isEmpty()) {
            return null;
        }
        return new TrioVariantObservation(
                detail(evidence, "trioVariantId"),
                detail(evidence, "geneSymbol"),
                null,
                0L,
                genotypes);
    }

    private void addGenotype(Map<TrioRole, TrioGenotypeObservation> genotypes,
                             TrioRole role,
                             String value) {
        TrioGenotypeCall call = callFromDetail(value);
        if (call == null) {
            return;
        }
        genotypes.put(role, new TrioGenotypeObservation(role, genotypeText(value), call, null, null, null));
    }

    private TrioGenotypeCall callFromDetail(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String token = value.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
        try {
            return TrioGenotypeCall.valueOf(token);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String genotypeText(String value) {
        if (value == null) {
            return null;
        }
        int start = value.indexOf('(');
        int end = value.indexOf(')', start + 1);
        if (start >= 0 && end > start) {
            return value.substring(start + 1, end);
        }
        return null;
    }

    private boolean hasPartnerVariant(EvidenceItem evidence) {
        if (evidence == null || evidence.getDetails() == null) {
            return false;
        }
        Object value = evidence.getDetails().get("partnerVariantIds");
        if (value instanceof List<?>) {
            return !((List<?>) value).isEmpty();
        }
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private String variantKey(VariantInterpretation interpretation) {
        if (interpretation == null) {
            return null;
        }
        VariantQcObservation observation = interpretation.getObservation();
        if (observation == null || observation.getKey() == null) {
            return interpretation.getVariantId();
        }
        NormalizedVariantKey key = observation.getKey();
        return key.getGenomeAssembly()
                + ":" + key.getChromosome()
                + ":" + key.getPosition()
                + ":" + key.getReference()
                + ">"
                + key.getAlternate();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private double scoreOrZero(Double value) {
        return value == null || !Double.isFinite(value.doubleValue()) ? 0.0d : value.doubleValue();
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
}
