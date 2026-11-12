package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"hpoId", "label", "originalStatus", "onset", "decisionOptions"})
public class HpoReviewTerm {
    private String hpoId;
    private String label;
    private HpoReviewOriginalStatus originalStatus;
    private String onset;
    private List<String> decisionOptions = new ArrayList<String>();

    public String getHpoId() { return hpoId; }
    public void setHpoId(String value) { hpoId = value; }
    public String getLabel() { return label; }
    public void setLabel(String value) { label = value; }
    public HpoReviewOriginalStatus getOriginalStatus() { return originalStatus; }
    public void setOriginalStatus(HpoReviewOriginalStatus value) { originalStatus = value; }
    public String getOnset() { return onset; }
    public void setOnset(String value) { onset = value; }
    public List<String> getDecisionOptions() { return decisionOptions; }
    public void setDecisionOptions(List<String> value) { decisionOptions = value == null ? new ArrayList<String>() : value; }
}
