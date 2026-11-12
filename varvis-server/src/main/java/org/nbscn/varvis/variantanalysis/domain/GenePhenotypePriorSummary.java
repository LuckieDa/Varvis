package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GenePhenotypePriorSummary {

    private final boolean enabled;
    private final String source;
    private final String sourceVersion;
    private final GenePhenotypePriorStatus status;
    private final int inputHpoCount;
    private final int validHpoCount;
    private final int genesWithPrior;
    private final List<GenePhenotypePrior> topGenePriors;
    private final List<String> warnings;
    private final Instant generatedAt;

    @JsonCreator
    public GenePhenotypePriorSummary(@JsonProperty("enabled") boolean enabled,
                                     @JsonProperty("source") String source,
                                     @JsonProperty("sourceVersion") String sourceVersion,
                                     @JsonProperty("status") GenePhenotypePriorStatus status,
                                     @JsonProperty("inputHpoCount") int inputHpoCount,
                                     @JsonProperty("validHpoCount") int validHpoCount,
                                     @JsonProperty("genesWithPrior") int genesWithPrior,
                                     @JsonProperty("topGenePriors") List<GenePhenotypePrior> topGenePriors,
                                     @JsonProperty("warnings") List<String> warnings,
                                     @JsonProperty("generatedAt") Instant generatedAt) {
        this.enabled = enabled;
        this.source = source;
        this.sourceVersion = sourceVersion;
        this.status = status;
        this.inputHpoCount = inputHpoCount;
        this.validHpoCount = validHpoCount;
        this.genesWithPrior = genesWithPrior;
        this.topGenePriors = immutablePriorCopy(topGenePriors);
        this.warnings = immutableStringCopy(warnings);
        this.generatedAt = generatedAt;
    }

    public static GenePhenotypePriorSummary notGenerated() {
        return new GenePhenotypePriorSummary(
                false,
                null,
                null,
                GenePhenotypePriorStatus.INVALID_INPUT,
                0,
                0,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                null
        );
    }

    private static List<GenePhenotypePrior> immutablePriorCopy(List<GenePhenotypePrior> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<GenePhenotypePrior>(values));
    }

    private static List<String> immutableStringCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getSource() {
        return source;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public GenePhenotypePriorStatus getStatus() {
        return status;
    }

    public int getInputHpoCount() {
        return inputHpoCount;
    }

    public int getValidHpoCount() {
        return validHpoCount;
    }

    public int getGenesWithPrior() {
        return genesWithPrior;
    }

    public List<GenePhenotypePrior> getTopGenePriors() {
        return topGenePriors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
