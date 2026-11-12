package org.nbscn.varvis.variantanalysis.benchmark.selection;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthPolicyAssessment;

import java.util.ArrayList;
import java.util.List;

public class PilotSelectionDecision {
    private PublicBenchmarkSmokeCandidate candidate;
    private String caseId;
    private PilotCandidateRole role;
    private BenchmarkTruthPolicyAssessment truthPolicyAssessment;
    private String variantStratum;
    private String hpoCountBand;
    private int selectionOrder;
    private List<PilotSelectionExclusionReason> reasons = new ArrayList<PilotSelectionExclusionReason>();

    public PublicBenchmarkSmokeCandidate getCandidate() { return candidate; }
    public void setCandidate(PublicBenchmarkSmokeCandidate value) { candidate = value; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String value) { caseId = value; }
    public PilotCandidateRole getRole() { return role; }
    public void setRole(PilotCandidateRole value) { role = value; }
    public BenchmarkTruthPolicyAssessment getTruthPolicyAssessment() { return truthPolicyAssessment; }
    public void setTruthPolicyAssessment(BenchmarkTruthPolicyAssessment value) { truthPolicyAssessment = value; }
    public String getVariantStratum() { return variantStratum; }
    public void setVariantStratum(String value) { variantStratum = value; }
    public String getHpoCountBand() { return hpoCountBand; }
    public void setHpoCountBand(String value) { hpoCountBand = value; }
    public int getSelectionOrder() { return selectionOrder; }
    public void setSelectionOrder(int value) { selectionOrder = value; }
    public List<PilotSelectionExclusionReason> getReasons() { return reasons; }
    public void setReasons(List<PilotSelectionExclusionReason> value) { reasons = value; }
}
