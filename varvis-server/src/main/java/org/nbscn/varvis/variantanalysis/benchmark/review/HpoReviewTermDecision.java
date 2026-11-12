package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"hpoId", "originalStatus", "decision", "mappingErrorReason"})
public class HpoReviewTermDecision {
    private String hpoId;
    private HpoReviewOriginalStatus originalStatus;
    private HpoReviewDecisionType decision;
    private String mappingErrorReason;

    public String getHpoId() { return hpoId; }
    public void setHpoId(String value) { hpoId = value; }
    public HpoReviewOriginalStatus getOriginalStatus() { return originalStatus; }
    public void setOriginalStatus(HpoReviewOriginalStatus value) { originalStatus = value; }
    public HpoReviewDecisionType getDecision() { return decision; }
    public void setDecision(HpoReviewDecisionType value) { decision = value; }
    public String getMappingErrorReason() { return mappingErrorReason; }
    public void setMappingErrorReason(String value) { mappingErrorReason = value; }
}
