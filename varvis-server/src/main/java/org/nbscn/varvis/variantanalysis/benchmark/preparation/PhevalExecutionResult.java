package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"status", "executionMode", "toolVersion", "commandPlanFingerprint", "outputReference", "failureCode", "message"})
public class PhevalExecutionResult {

    private PhevalExecutionStatus status;
    private String executionMode;
    private String toolVersion;
    private String commandPlanFingerprint;
    private String outputReference;
    private String failureCode;
    private String message;

    public PhevalExecutionStatus getStatus() { return status; }
    public String getExecutionMode() { return executionMode; }
    public String getToolVersion() { return toolVersion; }
    public String getCommandPlanFingerprint() { return commandPlanFingerprint; }
    public String getOutputReference() { return outputReference; }
    public String getFailureCode() { return failureCode; }
    public String getMessage() { return message; }
    public void setStatus(PhevalExecutionStatus value) { status = value; }
    public void setExecutionMode(String value) { executionMode = value; }
    public void setToolVersion(String value) { toolVersion = value; }
    public void setCommandPlanFingerprint(String value) { commandPlanFingerprint = value; }
    public void setOutputReference(String value) { outputReference = value; }
    public void setFailureCode(String value) { failureCode = value; }
    public void setMessage(String value) { message = value; }

    public static PhevalExecutionResult synthetic(PhevalExecutionStatus status,
                                                  PhevalCommandPlan plan,
                                                  String outputReference,
                                                  String failureCode,
                                                  String message) {
        PhevalExecutionResult result = new PhevalExecutionResult();
        result.status = status;
        result.executionMode = "SYNTHETIC_FAKE";
        result.toolVersion = "0.7.14";
        result.commandPlanFingerprint = plan == null ? null : plan.getFingerprint();
        result.outputReference = outputReference;
        result.failureCode = failureCode;
        result.message = message;
        return result;
    }
}
