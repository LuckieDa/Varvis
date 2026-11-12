package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "schemaVersion", "applicationSourceCommit", "orchestrationSourceCommit",
        "applicationArtifactSha256", "configurationFingerprint", "workingTreeState"
})
public class BenchmarkExecutionProvenance {

    private String schemaVersion = BenchmarkManifestSchema.EXECUTION_PROVENANCE;
    private String applicationSourceCommit;
    private String orchestrationSourceCommit;
    private String applicationArtifactSha256;
    private String configurationFingerprint;
    private String workingTreeState;

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getApplicationSourceCommit() { return applicationSourceCommit; }
    public void setApplicationSourceCommit(String value) { applicationSourceCommit = value; }
    public String getOrchestrationSourceCommit() { return orchestrationSourceCommit; }
    public void setOrchestrationSourceCommit(String value) { orchestrationSourceCommit = value; }
    public String getApplicationArtifactSha256() { return applicationArtifactSha256; }
    public void setApplicationArtifactSha256(String value) { applicationArtifactSha256 = value; }
    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public void setConfigurationFingerprint(String value) { configurationFingerprint = value; }
    public String getWorkingTreeState() { return workingTreeState; }
    public void setWorkingTreeState(String value) { workingTreeState = value; }
}
