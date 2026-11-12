package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"toolName", "version", "expectedPythonRequirement", "logicalInstallationPath", "executionMode", "availabilityStatus"})
public class PhevalToolDescriptor {

    private String toolName = "PhEval";
    private String version = "0.7.14";
    private String expectedPythonRequirement;
    private String logicalInstallationPath = "benchmark/pheval/0.7.14";
    private String executionMode = "ISOLATED_EXTERNAL_TOOL";
    private PhevalExecutionStatus availabilityStatus = PhevalExecutionStatus.TOOL_UNAVAILABLE;

    public String getToolName() { return toolName; }
    public void setToolName(String value) { toolName = value; }
    public String getVersion() { return version; }
    public void setVersion(String value) { version = value; }
    public String getExpectedPythonRequirement() { return expectedPythonRequirement; }
    public void setExpectedPythonRequirement(String value) { expectedPythonRequirement = value; }
    public String getLogicalInstallationPath() { return logicalInstallationPath; }
    public void setLogicalInstallationPath(String value) { logicalInstallationPath = value; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String value) { executionMode = value; }
    public PhevalExecutionStatus getAvailabilityStatus() { return availabilityStatus; }
    public void setAvailabilityStatus(PhevalExecutionStatus value) { availabilityStatus = value; }

    public void validate() {
        if (!"PhEval".equals(toolName) || !"0.7.14".equals(version)) {
            throw new IllegalArgumentException("PHEVAL_VERSION_MISMATCH");
        }
        PhevalPathSafety.requireLogicalRelative(logicalInstallationPath, "logicalInstallationPath");
    }
}
