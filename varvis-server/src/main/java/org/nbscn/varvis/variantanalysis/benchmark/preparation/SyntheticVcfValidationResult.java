package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"status", "issues", "observedAssembly", "sampleId", "observedChromosomes", "matchedTruthVariants"})
public class SyntheticVcfValidationResult {

    private SyntheticVcfValidationStatus status;
    private List<String> issues = new ArrayList<String>();
    private String observedAssembly;
    private String sampleId;
    private List<String> observedChromosomes = new ArrayList<String>();
    private List<String> matchedTruthVariants = new ArrayList<String>();

    public SyntheticVcfValidationStatus getStatus() { return status; }
    public List<String> getIssues() { return issues; }
    public String getObservedAssembly() { return observedAssembly; }
    public String getSampleId() { return sampleId; }
    public List<String> getObservedChromosomes() { return observedChromosomes; }
    public List<String> getMatchedTruthVariants() { return matchedTruthVariants; }
    public boolean isValid() { return status == SyntheticVcfValidationStatus.VALID; }
    public void setStatus(SyntheticVcfValidationStatus value) { status = value; }
    public void setObservedAssembly(String value) { observedAssembly = value; }
    public void setSampleId(String value) { sampleId = value; }
}
