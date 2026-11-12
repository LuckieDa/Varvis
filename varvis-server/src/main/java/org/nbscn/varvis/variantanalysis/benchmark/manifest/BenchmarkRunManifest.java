package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({
        "schemaVersion", "runId", "createdAt", "datasetId", "datasetVersion",
        "protocolVersion", "startedAt", "endedAt", "status", "failureSummary",
        "gitCommit", "branch", "applicationVersion", "javaVersion", "osFamily",
        "executionProvenance",
        "rankingContractVersion", "backgroundFingerprint", "truthFingerprint",
        "configuration", "sharedConfigurationFingerprint", "groups"
})
public class BenchmarkRunManifest {

    private String schemaVersion = BenchmarkManifestSchema.RUN;
    private String runId;
    private String createdAt;
    private String datasetId;
    private String datasetVersion;
    private String protocolVersion;
    private String startedAt;
    private String endedAt;
    private String status;
    private String failureSummary;
    private String gitCommit;
    private String branch;
    private String applicationVersion;
    private String javaVersion;
    private String osFamily;
    private BenchmarkExecutionProvenance executionProvenance;
    private String rankingContractVersion;
    private String backgroundFingerprint;
    private String truthFingerprint;
    private BenchmarkRunConfiguration configuration;
    private String sharedConfigurationFingerprint;
    private List<BenchmarkRunGroup> groups = new ArrayList<BenchmarkRunGroup>();

    public BenchmarkRunManifest() {
    }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getEndedAt() { return endedAt; }
    public void setEndedAt(String endedAt) { this.endedAt = endedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
    public String getGitCommit() { return gitCommit; }
    public void setGitCommit(String gitCommit) { this.gitCommit = gitCommit; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getApplicationVersion() { return applicationVersion; }
    public void setApplicationVersion(String applicationVersion) { this.applicationVersion = applicationVersion; }
    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
    public String getOsFamily() { return osFamily; }
    public void setOsFamily(String osFamily) { this.osFamily = osFamily; }
    public BenchmarkExecutionProvenance getExecutionProvenance() { return executionProvenance; }
    public void setExecutionProvenance(BenchmarkExecutionProvenance value) { executionProvenance = value; }
    @JsonIgnore
    public String getExecutionProvenanceStatus() {
        return executionProvenance == null ? "LEGACY_UNKNOWN" : "RECORDED";
    }
    public String getRankingContractVersion() { return rankingContractVersion; }
    public void setRankingContractVersion(String rankingContractVersion) { this.rankingContractVersion = rankingContractVersion; }
    public String getBackgroundFingerprint() { return backgroundFingerprint; }
    public void setBackgroundFingerprint(String backgroundFingerprint) { this.backgroundFingerprint = backgroundFingerprint; }
    public String getTruthFingerprint() { return truthFingerprint; }
    public void setTruthFingerprint(String truthFingerprint) { this.truthFingerprint = truthFingerprint; }
    public BenchmarkRunConfiguration getConfiguration() { return configuration; }
    public void setConfiguration(BenchmarkRunConfiguration configuration) { this.configuration = configuration; }
    public String getSharedConfigurationFingerprint() { return sharedConfigurationFingerprint; }
    public void setSharedConfigurationFingerprint(String sharedConfigurationFingerprint) { this.sharedConfigurationFingerprint = sharedConfigurationFingerprint; }
    public List<BenchmarkRunGroup> getGroups() { return groups; }
    public void setGroups(List<BenchmarkRunGroup> groups) { this.groups = groups; }
}
