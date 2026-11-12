package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PhenotypeAwareSummary {

    public static final String RANKING_NOT_APPLIED_WARNING =
            "Phenotype input is captured but not yet used for ranking.";

    private static final PhenotypeAwareSummary DISABLED = new PhenotypeAwareSummary(
            0,
            0,
            0,
            0,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            false,
            false,
            GenePhenotypePriorSummary.notGenerated(),
            0,
            Collections.emptyList(),
            PhenotypeRescueSummary.disabled()
    );

    private final int inputHpoCount;
    private final int validHpoCount;
    private final int invalidHpoCount;
    private final int duplicateHpoCount;
    private final List<String> validHpoTerms;
    private final List<String> invalidHpoTerms;
    private final List<String> excludedHpoTerms;
    private final List<String> warnings;
    private final List<PhenotypeInput> phenotypeInputs;
    private final boolean enabled;
    private final boolean rankingApplied;
    private final GenePhenotypePriorSummary genePriorSummary;
    private final int candidatesWithPhenotypeSupport;
    private final List<PhenotypeSupportedCandidate> topPhenotypeSupportedCandidates;
    private final PhenotypeRescueSummary phenotypeRescueSummary;

    public PhenotypeAwareSummary(int inputHpoCount,
                                 int validHpoCount,
                                 int invalidHpoCount,
                                 int duplicateHpoCount,
                                 List<String> validHpoTerms,
                                 List<String> invalidHpoTerms,
                                 List<String> warnings,
                                 List<PhenotypeInput> phenotypeInputs,
                                 boolean enabled,
                                 boolean rankingApplied) {
        this(
                inputHpoCount,
                validHpoCount,
                invalidHpoCount,
                duplicateHpoCount,
                validHpoTerms,
                invalidHpoTerms,
                warnings,
                phenotypeInputs,
                enabled,
                rankingApplied,
                GenePhenotypePriorSummary.notGenerated(),
                0,
                Collections.emptyList(),
                PhenotypeRescueSummary.disabled()
        );
    }

    public PhenotypeAwareSummary(int inputHpoCount,
                                 int validHpoCount,
                                 int invalidHpoCount,
                                 int duplicateHpoCount,
                                 List<String> validHpoTerms,
                                 List<String> invalidHpoTerms,
                                 List<String> warnings,
                                 List<PhenotypeInput> phenotypeInputs,
                                 boolean enabled,
                                 boolean rankingApplied,
                                 GenePhenotypePriorSummary genePriorSummary) {
        this(
                inputHpoCount,
                validHpoCount,
                invalidHpoCount,
                duplicateHpoCount,
                validHpoTerms,
                invalidHpoTerms,
                warnings,
                phenotypeInputs,
                enabled,
                rankingApplied,
                genePriorSummary,
                0,
                Collections.emptyList(),
                PhenotypeRescueSummary.disabled()
        );
    }

    public PhenotypeAwareSummary(@JsonProperty("inputHpoCount") int inputHpoCount,
                                 @JsonProperty("validHpoCount") int validHpoCount,
                                 @JsonProperty("invalidHpoCount") int invalidHpoCount,
                                 @JsonProperty("duplicateHpoCount") int duplicateHpoCount,
                                 @JsonProperty("validHpoTerms") List<String> validHpoTerms,
                                 @JsonProperty("invalidHpoTerms") List<String> invalidHpoTerms,
                                 @JsonProperty("warnings") List<String> warnings,
                                 @JsonProperty("phenotypeInputs") List<PhenotypeInput> phenotypeInputs,
                                 @JsonProperty("enabled") boolean enabled,
                                 @JsonProperty("rankingApplied") boolean rankingApplied,
                                 @JsonProperty("genePriorSummary") GenePhenotypePriorSummary genePriorSummary,
                                 @JsonProperty("candidatesWithPhenotypeSupport") int candidatesWithPhenotypeSupport,
                                 @JsonProperty("topPhenotypeSupportedCandidates")
                                 List<PhenotypeSupportedCandidate> topPhenotypeSupportedCandidates,
                                 @JsonProperty("phenotypeRescueSummary") PhenotypeRescueSummary phenotypeRescueSummary) {
        this(
                inputHpoCount,
                validHpoCount,
                invalidHpoCount,
                duplicateHpoCount,
                validHpoTerms,
                invalidHpoTerms,
                warnings,
                phenotypeInputs,
                Collections.emptyList(),
                enabled,
                rankingApplied,
                genePriorSummary,
                candidatesWithPhenotypeSupport,
                topPhenotypeSupportedCandidates,
                phenotypeRescueSummary
        );
    }

    @JsonCreator
    public PhenotypeAwareSummary(@JsonProperty("inputHpoCount") int inputHpoCount,
                                 @JsonProperty("validHpoCount") int validHpoCount,
                                 @JsonProperty("invalidHpoCount") int invalidHpoCount,
                                 @JsonProperty("duplicateHpoCount") int duplicateHpoCount,
                                 @JsonProperty("validHpoTerms") List<String> validHpoTerms,
                                 @JsonProperty("invalidHpoTerms") List<String> invalidHpoTerms,
                                 @JsonProperty("warnings") List<String> warnings,
                                 @JsonProperty("phenotypeInputs") List<PhenotypeInput> phenotypeInputs,
                                 @JsonProperty("excludedHpoTerms") List<String> excludedHpoTerms,
                                 @JsonProperty("enabled") boolean enabled,
                                 @JsonProperty("rankingApplied") boolean rankingApplied,
                                 @JsonProperty("genePriorSummary") GenePhenotypePriorSummary genePriorSummary,
                                 @JsonProperty("candidatesWithPhenotypeSupport") int candidatesWithPhenotypeSupport,
                                 @JsonProperty("topPhenotypeSupportedCandidates")
                                 List<PhenotypeSupportedCandidate> topPhenotypeSupportedCandidates,
                                 @JsonProperty("phenotypeRescueSummary") PhenotypeRescueSummary phenotypeRescueSummary) {
        this.inputHpoCount = inputHpoCount;
        this.validHpoCount = validHpoCount;
        this.invalidHpoCount = invalidHpoCount;
        this.duplicateHpoCount = duplicateHpoCount;
        this.validHpoTerms = immutableCopy(validHpoTerms);
        this.invalidHpoTerms = immutableCopy(invalidHpoTerms);
        this.excludedHpoTerms = immutableCopy(excludedHpoTerms);
        this.warnings = immutableCopy(warnings);
        this.phenotypeInputs = immutableInputCopy(phenotypeInputs);
        this.enabled = enabled;
        this.rankingApplied = rankingApplied;
        this.genePriorSummary = genePriorSummary == null
                ? GenePhenotypePriorSummary.notGenerated()
                : genePriorSummary;
        this.candidatesWithPhenotypeSupport = Math.max(0, candidatesWithPhenotypeSupport);
        this.topPhenotypeSupportedCandidates = immutableSupportedCandidateCopy(topPhenotypeSupportedCandidates);
        this.phenotypeRescueSummary = phenotypeRescueSummary == null
                ? PhenotypeRescueSummary.disabled()
                : phenotypeRescueSummary;
    }

    public static PhenotypeAwareSummary disabled() {
        return DISABLED;
    }

    public static PhenotypeAwareSummary historicalSummaryUnavailable() {
        return new PhenotypeAwareSummary(
                0,
                0,
                0,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("Historical phenotype summary is unavailable for this older job."),
                Collections.emptyList(),
                false,
                false,
                GenePhenotypePriorSummary.notGenerated(),
                0,
                Collections.emptyList(),
                PhenotypeRescueSummary.disabled()
        );
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    private static List<PhenotypeInput> immutableInputCopy(List<PhenotypeInput> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<PhenotypeInput>(values));
    }

    private static List<PhenotypeSupportedCandidate> immutableSupportedCandidateCopy(
            List<PhenotypeSupportedCandidate> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<PhenotypeSupportedCandidate>(values));
    }

    public int getInputHpoCount() {
        return inputHpoCount;
    }

    public int getValidHpoCount() {
        return validHpoCount;
    }

    public int getInvalidHpoCount() {
        return invalidHpoCount;
    }

    public int getDuplicateHpoCount() {
        return duplicateHpoCount;
    }

    public List<String> getValidHpoTerms() {
        return validHpoTerms;
    }

    public List<String> getInvalidHpoTerms() {
        return invalidHpoTerms;
    }

    public List<String> getExcludedHpoTerms() {
        return excludedHpoTerms;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public int getExcludedHpoCount() {
        return excludedHpoTerms.size();
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<PhenotypeInput> getPhenotypeInputs() {
        return phenotypeInputs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True for positive or explicitly absent phenotype input; ranking remains positive-only. */
    public boolean hasAnyPhenotypeInput() {
        return !phenotypeInputs.isEmpty()
                || !validHpoTerms.isEmpty()
                || !invalidHpoTerms.isEmpty()
                || !excludedHpoTerms.isEmpty();
    }

    public boolean isRankingApplied() {
        return rankingApplied;
    }

    public GenePhenotypePriorSummary getGenePriorSummary() {
        return genePriorSummary;
    }

    public int getCandidatesWithPhenotypeSupport() {
        return candidatesWithPhenotypeSupport;
    }

    public List<PhenotypeSupportedCandidate> getTopPhenotypeSupportedCandidates() {
        return topPhenotypeSupportedCandidates;
    }

    public PhenotypeRescueSummary getPhenotypeRescueSummary() {
        return phenotypeRescueSummary;
    }

    public PhenotypeAwareSummary withGenePriorSummary(GenePhenotypePriorSummary genePriorSummary) {
        return new PhenotypeAwareSummary(
                inputHpoCount,
                validHpoCount,
                invalidHpoCount,
                duplicateHpoCount,
                validHpoTerms,
                invalidHpoTerms,
                warnings,
                phenotypeInputs,
                excludedHpoTerms,
                enabled,
                rankingApplied,
                genePriorSummary,
                candidatesWithPhenotypeSupport,
                topPhenotypeSupportedCandidates,
                phenotypeRescueSummary
        );
    }

    public PhenotypeAwareSummary withWarnings(List<String> updatedWarnings) {
        return new PhenotypeAwareSummary(
                inputHpoCount,
                validHpoCount,
                invalidHpoCount,
                duplicateHpoCount,
                validHpoTerms,
                invalidHpoTerms,
                updatedWarnings,
                phenotypeInputs,
                excludedHpoTerms,
                enabled,
                rankingApplied,
                genePriorSummary,
                candidatesWithPhenotypeSupport,
                topPhenotypeSupportedCandidates,
                phenotypeRescueSummary
        );
    }

    public PhenotypeAwareSummary withRankingApplied(List<PhenotypeSupportedCandidate> supportedCandidates,
                                                    List<String> updatedWarnings) {
        List<PhenotypeSupportedCandidate> candidates = supportedCandidates == null
                ? Collections.emptyList()
                : supportedCandidates;
        return withRankingApplied(candidates.size(), candidates, updatedWarnings);
    }

    public PhenotypeAwareSummary withRankingApplied(int supportedCandidateCount,
                                                    List<PhenotypeSupportedCandidate> topSupportedCandidates,
                                                    List<String> updatedWarnings) {
        List<PhenotypeSupportedCandidate> candidates = topSupportedCandidates == null
                ? Collections.emptyList()
                : topSupportedCandidates;
        return new PhenotypeAwareSummary(
                inputHpoCount,
                validHpoCount,
                invalidHpoCount,
                duplicateHpoCount,
                validHpoTerms,
                invalidHpoTerms,
                updatedWarnings,
                phenotypeInputs,
                excludedHpoTerms,
                enabled,
                supportedCandidateCount > 0,
                genePriorSummary,
                supportedCandidateCount,
                candidates,
                phenotypeRescueSummary
        );
    }

    public PhenotypeAwareSummary withPhenotypeRescueSummary(PhenotypeRescueSummary rescueSummary) {
        return new PhenotypeAwareSummary(
                inputHpoCount,
                validHpoCount,
                invalidHpoCount,
                duplicateHpoCount,
                validHpoTerms,
                invalidHpoTerms,
                warnings,
                phenotypeInputs,
                excludedHpoTerms,
                enabled,
                rankingApplied,
                genePriorSummary,
                candidatesWithPhenotypeSupport,
                topPhenotypeSupportedCandidates,
                rescueSummary
        );
    }
}
