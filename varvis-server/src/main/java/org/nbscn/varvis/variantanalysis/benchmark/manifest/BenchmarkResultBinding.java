package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "attemptId", "requestFingerprint", "datasetVersion", "phenopacketSha256",
        "inputVcfSha256", "truthSetFingerprint", "executionProvenanceFingerprint",
        "configurationFingerprint"
})
public class BenchmarkResultBinding {
    private String attemptId;
    private String requestFingerprint;
    private String datasetVersion;
    private String phenopacketSha256;
    private String inputVcfSha256;
    private String truthSetFingerprint;
    private String executionProvenanceFingerprint;
    private String configurationFingerprint;

    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String value) { attemptId = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String value) { datasetVersion = value; }
    public String getPhenopacketSha256() { return phenopacketSha256; }
    public void setPhenopacketSha256(String value) { phenopacketSha256 = value; }
    public String getInputVcfSha256() { return inputVcfSha256; }
    public void setInputVcfSha256(String value) { inputVcfSha256 = value; }
    public String getTruthSetFingerprint() { return truthSetFingerprint; }
    public void setTruthSetFingerprint(String value) { truthSetFingerprint = value; }
    public String getExecutionProvenanceFingerprint() { return executionProvenanceFingerprint; }
    public void setExecutionProvenanceFingerprint(String value) { executionProvenanceFingerprint = value; }
    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public void setConfigurationFingerprint(String value) { configurationFingerprint = value; }
}
