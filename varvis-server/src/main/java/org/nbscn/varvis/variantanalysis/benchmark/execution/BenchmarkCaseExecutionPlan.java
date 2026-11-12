package org.nbscn.varvis.variantanalysis.benchmark.execution;

import java.util.ArrayList;
import java.util.List;

public class BenchmarkCaseExecutionPlan {
    private String caseId;
    private String groupId;
    private String hpoInputMode;
    private List<String> hpoTerms = new ArrayList<String>();
    private String executionMode;
    private List<String> workflowSteps = new ArrayList<String>();
    private BenchmarkExecutionState state;
    private String reasonCode;

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getHpoInputMode() { return hpoInputMode; }
    public void setHpoInputMode(String hpoInputMode) { this.hpoInputMode = hpoInputMode; }
    public List<String> getHpoTerms() { return hpoTerms; }
    public void setHpoTerms(List<String> hpoTerms) { this.hpoTerms = hpoTerms; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public List<String> getWorkflowSteps() { return workflowSteps; }
    public void setWorkflowSteps(List<String> workflowSteps) { this.workflowSteps = workflowSteps; }
    public BenchmarkExecutionState getState() { return state; }
    public void setState(BenchmarkExecutionState state) { this.state = state; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String key() { return caseId + "/" + groupId; }
}
