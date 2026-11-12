package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.nbscn.varvis.variantanalysis.service.PhenotypePriorGeneIntervalService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explainability-only telemetry for large-file phenotype-prior rescue. */
public class PhenotypeRescueSummary {

    private static final PhenotypeRescueSummary DISABLED = new PhenotypeRescueSummary(
            "DISABLED", "DISABLED", "UNCONFIGURED", 0, 0, 0, 0, 0, 0,
            "UNRESOLVED", 0, Collections.<String>emptyList());

    private final String phenotypePriorStatus;
    private final String intervalResourceStatus;
    private final String intervalSourceVersion;
    private final int phenotypePriorGeneCount;
    private final int intervalMappedPriorGeneCount;
    private final int rescueCandidateCount;
    private final int rescuedSelectedCount;
    private final int rescueRejectedByHardGateCount;
    private final int rescueMissingIntervalGeneCount;
    private final String effectiveAssembly;
    private final int candidateCap;
    private final List<String> warnings;

    @JsonCreator
    public PhenotypeRescueSummary(
            @JsonProperty("phenotypePriorStatus") String phenotypePriorStatus,
            @JsonProperty("intervalResourceStatus") String intervalResourceStatus,
            @JsonProperty("intervalSourceVersion") String intervalSourceVersion,
            @JsonProperty("phenotypePriorGeneCount") int phenotypePriorGeneCount,
            @JsonProperty("intervalMappedPriorGeneCount") int intervalMappedPriorGeneCount,
            @JsonProperty("rescueCandidateCount") int rescueCandidateCount,
            @JsonProperty("rescuedSelectedCount") int rescuedSelectedCount,
            @JsonProperty("rescueRejectedByHardGateCount") int rescueRejectedByHardGateCount,
            @JsonProperty("rescueMissingIntervalGeneCount") int rescueMissingIntervalGeneCount,
            @JsonProperty("effectiveAssembly") String effectiveAssembly,
            @JsonProperty("candidateCap") int candidateCap,
            @JsonProperty("warnings") List<String> warnings) {
        this.phenotypePriorStatus = valueOrUnknown(phenotypePriorStatus);
        this.intervalResourceStatus = valueOrUnknown(intervalResourceStatus);
        this.intervalSourceVersion = valueOrUnknown(intervalSourceVersion);
        this.phenotypePriorGeneCount = nonNegative(phenotypePriorGeneCount);
        this.intervalMappedPriorGeneCount = nonNegative(intervalMappedPriorGeneCount);
        this.rescueCandidateCount = nonNegative(rescueCandidateCount);
        this.rescuedSelectedCount = nonNegative(rescuedSelectedCount);
        this.rescueRejectedByHardGateCount = nonNegative(rescueRejectedByHardGateCount);
        this.rescueMissingIntervalGeneCount = nonNegative(rescueMissingIntervalGeneCount);
        this.effectiveAssembly = valueOrUnknown(effectiveAssembly);
        this.candidateCap = nonNegative(candidateCap);
        this.warnings = warnings == null || warnings.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public static PhenotypeRescueSummary disabled() {
        return DISABLED;
    }

    public static PhenotypeRescueSummary from(
            PhenotypePriorGeneIntervalService.RescueEvaluation evaluation,
            int rescuedSelectedCount,
            int rescueRejectedByHardGateCount) {
        if (evaluation == null) {
            return disabled();
        }
        return new PhenotypeRescueSummary(
                evaluation.getPhenotypePriorStatus(),
                evaluation.getIntervalResourceStatus(),
                evaluation.getIntervalSourceVersion(),
                evaluation.getPhenotypePriorGeneCount(),
                evaluation.getIntervalMappedPriorGeneCount(),
                evaluation.getRescueCandidateCount(),
                rescuedSelectedCount,
                rescueRejectedByHardGateCount,
                evaluation.getRescueMissingIntervalGeneCount(),
                evaluation.getEffectiveAssembly(),
                evaluation.getCandidateCap(),
                evaluation.getWarnings());
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "UNKNOWN" : value;
    }

    public String getPhenotypePriorStatus() { return phenotypePriorStatus; }
    public String getIntervalResourceStatus() { return intervalResourceStatus; }
    public String getIntervalSourceVersion() { return intervalSourceVersion; }
    public int getPhenotypePriorGeneCount() { return phenotypePriorGeneCount; }
    public int getIntervalMappedPriorGeneCount() { return intervalMappedPriorGeneCount; }
    public int getRescueCandidateCount() { return rescueCandidateCount; }
    public int getRescuedSelectedCount() { return rescuedSelectedCount; }
    public int getRescueRejectedByHardGateCount() { return rescueRejectedByHardGateCount; }
    public int getRescueMissingIntervalGeneCount() { return rescueMissingIntervalGeneCount; }
    public String getEffectiveAssembly() { return effectiveAssembly; }
    public int getCandidateCap() { return candidateCap; }
    public List<String> getWarnings() { return warnings; }
}
