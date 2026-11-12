package org.nbscn.varvis.variantanalysis.benchmark.selection;

import java.util.ArrayList;
import java.util.List;

public class SmokeSetSelectionResult {
    private String algorithmVersion;
    private String algorithmFingerprint;
    private boolean complete;
    private List<SmokeSetSelectionDecision> decisions = new ArrayList<SmokeSetSelectionDecision>();

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String value) { algorithmVersion = value; }
    public String getAlgorithmFingerprint() { return algorithmFingerprint; }
    public void setAlgorithmFingerprint(String value) { algorithmFingerprint = value; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean value) { complete = value; }
    public List<SmokeSetSelectionDecision> getDecisions() { return decisions; }
    public void setDecisions(List<SmokeSetSelectionDecision> value) { decisions = value; }
}
