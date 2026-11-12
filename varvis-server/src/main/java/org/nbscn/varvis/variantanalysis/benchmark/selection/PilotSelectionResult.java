package org.nbscn.varvis.variantanalysis.benchmark.selection;

import java.util.ArrayList;
import java.util.List;

public class PilotSelectionResult {
    private String algorithmVersion;
    private String algorithmFingerprint;
    private String deterministicSeed;
    private boolean complete;
    private List<PilotSelectionDecision> decisions = new ArrayList<PilotSelectionDecision>();

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String value) { algorithmVersion = value; }
    public String getAlgorithmFingerprint() { return algorithmFingerprint; }
    public void setAlgorithmFingerprint(String value) { algorithmFingerprint = value; }
    public String getDeterministicSeed() { return deterministicSeed; }
    public void setDeterministicSeed(String value) { deterministicSeed = value; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean value) { complete = value; }
    public List<PilotSelectionDecision> getDecisions() { return decisions; }
    public void setDecisions(List<PilotSelectionDecision> value) { decisions = value; }
}
