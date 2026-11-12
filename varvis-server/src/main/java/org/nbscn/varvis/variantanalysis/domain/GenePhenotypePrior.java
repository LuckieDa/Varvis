package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GenePhenotypePrior {

    private final String geneSymbol;
    private final String geneId;
    private final double phenotypeScore;
    private final int phenotypeRank;
    private final String source;
    private final String sourceVersion;
    private final List<String> matchedHpoTerms;
    private final List<String> evidence;
    private final GenePhenotypePriorStatus status;
    private final Instant timestamp;
    private final List<String> warnings;

    @JsonCreator
    public GenePhenotypePrior(@JsonProperty("geneSymbol") String geneSymbol,
                              @JsonProperty("geneId") String geneId,
                              @JsonProperty("phenotypeScore") double phenotypeScore,
                              @JsonProperty("phenotypeRank") int phenotypeRank,
                              @JsonProperty("source") String source,
                              @JsonProperty("sourceVersion") String sourceVersion,
                              @JsonProperty("matchedHpoTerms") List<String> matchedHpoTerms,
                              @JsonProperty("evidence") List<String> evidence,
                              @JsonProperty("status") GenePhenotypePriorStatus status,
                              @JsonProperty("timestamp") Instant timestamp,
                              @JsonProperty("warnings") List<String> warnings) {
        this.geneSymbol = geneSymbol;
        this.geneId = geneId;
        this.phenotypeScore = phenotypeScore;
        this.phenotypeRank = phenotypeRank;
        this.source = source;
        this.sourceVersion = sourceVersion;
        this.matchedHpoTerms = immutableCopy(matchedHpoTerms);
        this.evidence = immutableCopy(evidence);
        this.status = status;
        this.timestamp = timestamp;
        this.warnings = immutableCopy(warnings);
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getGeneSymbol() {
        return geneSymbol;
    }

    public String getGeneId() {
        return geneId;
    }

    public double getPhenotypeScore() {
        return phenotypeScore;
    }

    public int getPhenotypeRank() {
        return phenotypeRank;
    }

    public String getSource() {
        return source;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public List<String> getMatchedHpoTerms() {
        return matchedHpoTerms;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public GenePhenotypePriorStatus getStatus() {
        return status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
