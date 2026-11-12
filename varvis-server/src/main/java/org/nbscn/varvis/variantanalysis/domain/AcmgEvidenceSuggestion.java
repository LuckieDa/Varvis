package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AcmgEvidenceSuggestion {

    private final String code;
    private final String strength;
    private final String status;
    private final String sourceEvidence;
    private final String explanation;
    private final boolean manualReviewRequired;
    private final String ruleVersion;
    private final List<String> warnings;

    @JsonCreator
    public AcmgEvidenceSuggestion(@JsonProperty("code") String code,
                                  @JsonProperty("strength") String strength,
                                  @JsonProperty("status") String status,
                                  @JsonProperty("sourceEvidence") String sourceEvidence,
                                  @JsonProperty("explanation") String explanation,
                                  @JsonProperty("manualReviewRequired") boolean manualReviewRequired,
                                  @JsonProperty("ruleVersion") String ruleVersion,
                                  @JsonProperty("warnings") List<String> warnings) {
        this.code = code;
        this.strength = strength;
        this.status = status;
        this.sourceEvidence = sourceEvidence;
        this.explanation = explanation;
        this.manualReviewRequired = manualReviewRequired;
        this.ruleVersion = ruleVersion;
        this.warnings = warnings == null || warnings.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public String getCode() {
        return code;
    }

    public String getStrength() {
        return strength;
    }

    public String getStatus() {
        return status;
    }

    public String getSourceEvidence() {
        return sourceEvidence;
    }

    public String getExplanation() {
        return explanation;
    }

    public boolean isManualReviewRequired() {
        return manualReviewRequired;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
