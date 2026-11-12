package org.nbscn.varvis.variantanalysis.trio.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrioRankingContribution {

    private final String variantId;
    private final String variantKey;
    private final boolean enabled;
    private final boolean implemented;
    private final TrioRankingContributionLevel contributionLevel;
    private final TrioRankingContributionDirection contributionDirection;
    private final double contributionScore;
    private final boolean applied;
    private final TrioRankingNotAppliedReason notAppliedReason;
    private final String model;
    private final String confidence;
    private final boolean hardGateProtected;
    private final String explanation;
    private final List<String> warnings;

    public TrioRankingContribution(String variantId,
                                   String variantKey,
                                   boolean enabled,
                                   boolean implemented,
                                   TrioRankingContributionLevel contributionLevel,
                                   TrioRankingContributionDirection contributionDirection,
                                   double contributionScore,
                                   boolean applied,
                                   TrioRankingNotAppliedReason notAppliedReason,
                                   String model,
                                   String confidence,
                                   boolean hardGateProtected,
                                   String explanation,
                                   List<String> warnings) {
        this.variantId = variantId;
        this.variantKey = variantKey;
        this.enabled = enabled;
        this.implemented = implemented;
        this.contributionLevel = contributionLevel == null
                ? TrioRankingContributionLevel.NEUTRAL
                : contributionLevel;
        this.contributionDirection = contributionDirection == null
                ? TrioRankingContributionDirection.NEUTRAL
                : contributionDirection;
        this.contributionScore = contributionScore;
        this.applied = applied;
        this.notAppliedReason = notAppliedReason;
        this.model = model;
        this.confidence = confidence;
        this.hardGateProtected = hardGateProtected;
        this.explanation = explanation;
        this.warnings = immutableList(warnings);
    }

    public String getVariantId() {
        return variantId;
    }

    public String getVariantKey() {
        return variantKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isImplemented() {
        return implemented;
    }

    public TrioRankingContributionLevel getContributionLevel() {
        return contributionLevel;
    }

    public TrioRankingContributionDirection getContributionDirection() {
        return contributionDirection;
    }

    public double getContributionScore() {
        return contributionScore;
    }

    public boolean isApplied() {
        return applied;
    }

    public TrioRankingNotAppliedReason getNotAppliedReason() {
        return notAppliedReason;
    }

    public String getModel() {
        return model;
    }

    public String getConfidence() {
        return confidence;
    }

    public boolean isHardGateProtected() {
        return hardGateProtected;
    }

    public String getExplanation() {
        return explanation;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    private List<String> immutableList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
