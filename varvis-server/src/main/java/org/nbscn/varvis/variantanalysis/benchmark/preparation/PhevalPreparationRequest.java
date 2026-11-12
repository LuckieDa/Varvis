package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "datasetId", "caseId", "groupId", "phenopacketInputReference", "backgroundVcfReference",
        "regionReference", "referenceAssembly", "outputDirectoryReference", "expectedOutputVcf",
        "randomSeed", "timeoutSeconds", "provenanceFingerprint"
})
public class PhevalPreparationRequest {

    private String datasetId;
    private String caseId;
    private String groupId;
    private String phenopacketInputReference;
    private String backgroundVcfReference;
    private String regionReference;
    private String referenceAssembly;
    private String outputDirectoryReference;
    private String expectedOutputVcf;
    private Long randomSeed;
    private long timeoutSeconds;
    private String provenanceFingerprint;

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String value) { datasetId = value; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String value) { caseId = value; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String value) { groupId = value; }
    public String getPhenopacketInputReference() { return phenopacketInputReference; }
    public void setPhenopacketInputReference(String value) { phenopacketInputReference = value; }
    public String getBackgroundVcfReference() { return backgroundVcfReference; }
    public void setBackgroundVcfReference(String value) { backgroundVcfReference = value; }
    public String getRegionReference() { return regionReference; }
    public void setRegionReference(String value) { regionReference = value; }
    public String getReferenceAssembly() { return referenceAssembly; }
    public void setReferenceAssembly(String value) { referenceAssembly = value; }
    public String getOutputDirectoryReference() { return outputDirectoryReference; }
    public void setOutputDirectoryReference(String value) { outputDirectoryReference = value; }
    public String getExpectedOutputVcf() { return expectedOutputVcf; }
    public void setExpectedOutputVcf(String value) { expectedOutputVcf = value; }
    public Long getRandomSeed() { return randomSeed; }
    public void setRandomSeed(Long value) { randomSeed = value; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long value) { timeoutSeconds = value; }
    public String getProvenanceFingerprint() { return provenanceFingerprint; }
    public void setProvenanceFingerprint(String value) { provenanceFingerprint = value; }

    public void validate() {
        require(datasetId, "datasetId");
        require(caseId, "caseId");
        require(groupId, "groupId");
        require(referenceAssembly, "referenceAssembly");
        PhevalPathSafety.requireLogicalRelative(phenopacketInputReference, "phenopacketInputReference");
        PhevalPathSafety.requireLogicalRelative(backgroundVcfReference, "backgroundVcfReference");
        PhevalPathSafety.requireLogicalRelative(regionReference, "regionReference");
        PhevalPathSafety.requireLogicalRelative(outputDirectoryReference, "outputDirectoryReference");
        PhevalPathSafety.requireLogicalRelative(expectedOutputVcf, "expectedOutputVcf");
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("INVALID_TIMEOUT");
    }

    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("REQUIRED_" + field.toUpperCase());
    }
}
