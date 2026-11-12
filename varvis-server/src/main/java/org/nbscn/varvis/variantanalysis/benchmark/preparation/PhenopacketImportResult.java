package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"status", "caseData", "issues", "exclusionReasons", "provenance"})
public class PhenopacketImportResult {

    private PhenopacketEligibilityStatus status;
    private PhenopacketCaseData caseData;
    private List<PhenopacketImportIssue> issues = new ArrayList<PhenopacketImportIssue>();
    private List<PhenopacketExclusionReason> exclusionReasons = new ArrayList<PhenopacketExclusionReason>();
    private String provenance;

    public PhenopacketEligibilityStatus getStatus() { return status; }
    public void setStatus(PhenopacketEligibilityStatus value) { status = value; }
    public PhenopacketCaseData getCaseData() { return caseData; }
    public void setCaseData(PhenopacketCaseData value) { caseData = value; }
    public List<PhenopacketImportIssue> getIssues() { return issues; }
    public void setIssues(List<PhenopacketImportIssue> value) { issues = value; }
    public List<PhenopacketExclusionReason> getExclusionReasons() { return exclusionReasons; }
    public void setExclusionReasons(List<PhenopacketExclusionReason> value) { exclusionReasons = value; }
    public String getProvenance() { return provenance; }
    public void setProvenance(String value) { provenance = value; }

    public boolean isEligible() {
        return status == PhenopacketEligibilityStatus.ELIGIBLE;
    }
}
