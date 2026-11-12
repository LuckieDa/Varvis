package org.nbscn.varvis.variantanalysis.benchmark.execution;

import java.util.ArrayList;
import java.util.List;

public class BenchmarkExecutionPlan {
    private String runId;
    private boolean dryRun;
    private BenchmarkExecutionState state;
    private String preflightCode;
    private List<BenchmarkCaseExecutionPlan> cases = new ArrayList<BenchmarkCaseExecutionPlan>();

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public BenchmarkExecutionState getState() { return state; }
    public void setState(BenchmarkExecutionState state) { this.state = state; }
    public String getPreflightCode() { return preflightCode; }
    public void setPreflightCode(String preflightCode) { this.preflightCode = preflightCode; }
    public List<BenchmarkCaseExecutionPlan> getCases() { return cases; }
    public void setCases(List<BenchmarkCaseExecutionPlan> cases) { this.cases = cases; }
}
