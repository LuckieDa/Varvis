package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"decision", "hpoId", "patientEvidenceExcerpt", "sourceLocator", "rationale", "reviewerConfirmed"})
public class HpoReviewAddition {
    private String decision = "ADD_OBSERVED_WITH_PATIENT_EVIDENCE";
    private String hpoId;
    private String patientEvidenceExcerpt;
    private String sourceLocator;
    private String rationale;
    private Boolean reviewerConfirmed;

    public String getDecision() { return decision; }
    public void setDecision(String value) { decision = value; }
    public String getHpoId() { return hpoId; }
    public void setHpoId(String value) { hpoId = value; }
    public String getPatientEvidenceExcerpt() { return patientEvidenceExcerpt; }
    public void setPatientEvidenceExcerpt(String value) { patientEvidenceExcerpt = value; }
    public String getSourceLocator() { return sourceLocator; }
    public void setSourceLocator(String value) { sourceLocator = value; }
    public String getRationale() { return rationale; }
    public void setRationale(String value) { rationale = value; }
    public Boolean getReviewerConfirmed() { return reviewerConfirmed; }
    public void setReviewerConfirmed(Boolean value) { reviewerConfirmed = value; }
}
