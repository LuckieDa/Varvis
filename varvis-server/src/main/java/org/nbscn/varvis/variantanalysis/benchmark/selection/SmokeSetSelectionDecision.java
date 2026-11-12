package org.nbscn.varvis.variantanalysis.benchmark.selection;

import java.util.ArrayList;
import java.util.List;

public class SmokeSetSelectionDecision {
    private String caseId;
    private SmokeSelectionStatus status;
    private List<SmokeSetExclusionReason> reasons = new ArrayList<SmokeSetExclusionReason>();
    private PublicBenchmarkSmokeCandidate candidate;

    public String getCaseId() { return caseId; }
    public void setCaseId(String value) { caseId = value; }
    public SmokeSelectionStatus getStatus() { return status; }
    public void setStatus(SmokeSelectionStatus value) { status = value; }
    public List<SmokeSetExclusionReason> getReasons() { return reasons; }
    public void setReasons(List<SmokeSetExclusionReason> value) { reasons = value; }
    public PublicBenchmarkSmokeCandidate getCandidate() { return candidate; }
    public void setCandidate(PublicBenchmarkSmokeCandidate value) { candidate = value; }
}
