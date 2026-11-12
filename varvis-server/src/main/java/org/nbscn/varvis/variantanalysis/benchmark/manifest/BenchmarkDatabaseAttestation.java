package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "analysisReference", "jobStatus", "persistedInterpretationCount",
        "rankedInterpretationCount", "verifiedAt", "attestationFingerprint"
})
public class BenchmarkDatabaseAttestation {
    private String analysisReference;
    private String jobStatus;
    private Integer persistedInterpretationCount;
    private Integer rankedInterpretationCount;
    private String verifiedAt;
    private String attestationFingerprint;

    public String getAnalysisReference() { return analysisReference; }
    public void setAnalysisReference(String value) { analysisReference = value; }
    public String getJobStatus() { return jobStatus; }
    public void setJobStatus(String value) { jobStatus = value; }
    public Integer getPersistedInterpretationCount() { return persistedInterpretationCount; }
    public void setPersistedInterpretationCount(Integer value) { persistedInterpretationCount = value; }
    public Integer getRankedInterpretationCount() { return rankedInterpretationCount; }
    public void setRankedInterpretationCount(Integer value) { rankedInterpretationCount = value; }
    public String getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(String value) { verifiedAt = value; }
    public String getAttestationFingerprint() { return attestationFingerprint; }
    public void setAttestationFingerprint(String value) { attestationFingerprint = value; }
}
