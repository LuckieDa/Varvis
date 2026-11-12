package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PhenotypeSupportedCandidate {

    private final String variantId;
    private final String geneSymbol;
    private final String tier;
    private final double phenotypeScore;
    private final int phenotypeRank;
    private final double scoreBoost;
    private final String source;

    @JsonCreator
    public PhenotypeSupportedCandidate(@JsonProperty("variantId") String variantId,
                                       @JsonProperty("geneSymbol") String geneSymbol,
                                       @JsonProperty("tier") String tier,
                                       @JsonProperty("phenotypeScore") double phenotypeScore,
                                       @JsonProperty("phenotypeRank") int phenotypeRank,
                                       @JsonProperty("scoreBoost") double scoreBoost,
                                       @JsonProperty("source") String source) {
        this.variantId = variantId;
        this.geneSymbol = geneSymbol;
        this.tier = tier;
        this.phenotypeScore = phenotypeScore;
        this.phenotypeRank = phenotypeRank;
        this.scoreBoost = scoreBoost;
        this.source = source;
    }

    public String getVariantId() {
        return variantId;
    }

    public String getGeneSymbol() {
        return geneSymbol;
    }

    public String getTier() {
        return tier;
    }

    public double getPhenotypeScore() {
        return phenotypeScore;
    }

    public int getPhenotypeRank() {
        return phenotypeRank;
    }

    public double getScoreBoost() {
        return scoreBoost;
    }

    public String getSource() {
        return source;
    }
}
