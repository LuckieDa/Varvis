package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PhenotypeInput {

    private final String hpoId;
    private final String label;
    private final String source;
    private final PhenotypeInputStatus status;
    private final Double confidence;
    private final String matchedTerm;
    private final List<String> warnings;
    private final String requestedHpoId;
    private final String replacementHpoId;
    private final String ontologyLabel;
    private final HpoOntologyTermStatus ontologyStatus;
    private final boolean excluded;

    public PhenotypeInput(@JsonProperty("hpoId") String hpoId,
                          @JsonProperty("label") String label,
                          @JsonProperty("source") String source,
                          @JsonProperty("status") PhenotypeInputStatus status,
                          @JsonProperty("confidence") Double confidence,
                          @JsonProperty("matchedTerm") String matchedTerm,
                          @JsonProperty("warnings") List<String> warnings) {
        this(
                hpoId,
                label,
                source,
                status,
                confidence,
                matchedTerm,
                warnings,
                hpoId,
                null,
                null,
                HpoOntologyTermStatus.UNAVAILABLE,
                false
        );
    }

    @JsonCreator
    public PhenotypeInput(@JsonProperty("hpoId") String hpoId,
                          @JsonProperty("label") String label,
                          @JsonProperty("source") String source,
                          @JsonProperty("status") PhenotypeInputStatus status,
                          @JsonProperty("confidence") Double confidence,
                          @JsonProperty("matchedTerm") String matchedTerm,
                          @JsonProperty("warnings") List<String> warnings,
                          @JsonProperty("requestedHpoId") String requestedHpoId,
                          @JsonProperty("replacementHpoId") String replacementHpoId,
                          @JsonProperty("ontologyLabel") String ontologyLabel,
                          @JsonProperty("ontologyStatus") HpoOntologyTermStatus ontologyStatus,
                          @JsonProperty("excluded") boolean excluded) {
        this.hpoId = hpoId;
        this.label = label;
        this.source = source;
        this.status = status;
        this.confidence = confidence;
        this.matchedTerm = matchedTerm;
        this.warnings = immutableCopy(warnings);
        this.requestedHpoId = requestedHpoId == null ? hpoId : requestedHpoId;
        this.replacementHpoId = replacementHpoId;
        this.ontologyLabel = ontologyLabel;
        this.ontologyStatus = ontologyStatus == null
                ? HpoOntologyTermStatus.UNAVAILABLE
                : ontologyStatus;
        this.excluded = excluded;
    }

    private List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getHpoId() {
        return hpoId;
    }

    public String getLabel() {
        return label;
    }

    public String getSource() {
        return source;
    }

    public PhenotypeInputStatus getStatus() {
        return status;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getMatchedTerm() {
        return matchedTerm;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public String getRequestedHpoId() {
        return requestedHpoId;
    }

    public String getReplacementHpoId() {
        return replacementHpoId;
    }

    public String getOntologyLabel() {
        return ontologyLabel;
    }

    public HpoOntologyTermStatus getOntologyStatus() {
        return ontologyStatus;
    }

    public boolean isExcluded() {
        return excluded;
    }
}
