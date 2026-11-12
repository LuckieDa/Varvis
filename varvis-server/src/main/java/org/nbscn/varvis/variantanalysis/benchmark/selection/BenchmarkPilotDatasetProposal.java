package org.nbscn.varvis.variantanalysis.benchmark.selection;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({
        "schemaVersion", "datasetId", "datasetVersion", "protocolVersion", "createdAt",
        "status", "purpose", "datasetRole", "eligibleForLockedTest", "approvalStatus",
        "executionStatus", "description", "phenopacketStoreVersion", "officialSourceUri",
        "license", "archiveFileName", "archiveByteSize", "archiveSha256",
        "selectorVersion", "selectorFingerprint", "deterministicSeed", "truthPolicyVersion",
        "assembly", "benchmarkRegionVersion", "benchmarkRegionSha256",
        "hpoOntologyVersion", "hpoOntologySha256", "backgroundFingerprint",
        "mainCaseCount", "backupCaseCount", "humanReviewStatus", "cases"
})
public class BenchmarkPilotDatasetProposal {
    private String schemaVersion = "benchmark-pilot-dataset-proposal-v1";
    private String datasetId;
    private String datasetVersion;
    private String protocolVersion;
    private String createdAt;
    private String status;
    private String purpose;
    private String datasetRole;
    private Boolean eligibleForLockedTest;
    private String approvalStatus;
    private String executionStatus;
    private String description;
    private String phenopacketStoreVersion;
    private String officialSourceUri;
    private String license;
    private String archiveFileName;
    private Long archiveByteSize;
    private String archiveSha256;
    private String selectorVersion;
    private String selectorFingerprint;
    private String deterministicSeed;
    private String truthPolicyVersion;
    private String assembly;
    private String benchmarkRegionVersion;
    private String benchmarkRegionSha256;
    private String hpoOntologyVersion;
    private String hpoOntologySha256;
    private String backgroundFingerprint;
    private Integer mainCaseCount;
    private Integer backupCaseCount;
    private String humanReviewStatus;
    private List<BenchmarkDatasetCase> cases = new ArrayList<BenchmarkDatasetCase>();

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String value) { datasetId = value; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String value) { datasetVersion = value; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String value) { protocolVersion = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { createdAt = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public String getDatasetRole() { return datasetRole; }
    public void setDatasetRole(String value) { datasetRole = value; }
    public Boolean getEligibleForLockedTest() { return eligibleForLockedTest; }
    public void setEligibleForLockedTest(Boolean value) { eligibleForLockedTest = value; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String value) { approvalStatus = value; }
    public String getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(String value) { executionStatus = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getPhenopacketStoreVersion() { return phenopacketStoreVersion; }
    public void setPhenopacketStoreVersion(String value) { phenopacketStoreVersion = value; }
    public String getOfficialSourceUri() { return officialSourceUri; }
    public void setOfficialSourceUri(String value) { officialSourceUri = value; }
    public String getLicense() { return license; }
    public void setLicense(String value) { license = value; }
    public String getArchiveFileName() { return archiveFileName; }
    public void setArchiveFileName(String value) { archiveFileName = value; }
    public Long getArchiveByteSize() { return archiveByteSize; }
    public void setArchiveByteSize(Long value) { archiveByteSize = value; }
    public String getArchiveSha256() { return archiveSha256; }
    public void setArchiveSha256(String value) { archiveSha256 = value; }
    public String getSelectorVersion() { return selectorVersion; }
    public void setSelectorVersion(String value) { selectorVersion = value; }
    public String getSelectorFingerprint() { return selectorFingerprint; }
    public void setSelectorFingerprint(String value) { selectorFingerprint = value; }
    public String getDeterministicSeed() { return deterministicSeed; }
    public void setDeterministicSeed(String value) { deterministicSeed = value; }
    public String getTruthPolicyVersion() { return truthPolicyVersion; }
    public void setTruthPolicyVersion(String value) { truthPolicyVersion = value; }
    public String getAssembly() { return assembly; }
    public void setAssembly(String value) { assembly = value; }
    public String getBenchmarkRegionVersion() { return benchmarkRegionVersion; }
    public void setBenchmarkRegionVersion(String value) { benchmarkRegionVersion = value; }
    public String getBenchmarkRegionSha256() { return benchmarkRegionSha256; }
    public void setBenchmarkRegionSha256(String value) { benchmarkRegionSha256 = value; }
    public String getHpoOntologyVersion() { return hpoOntologyVersion; }
    public void setHpoOntologyVersion(String value) { hpoOntologyVersion = value; }
    public String getHpoOntologySha256() { return hpoOntologySha256; }
    public void setHpoOntologySha256(String value) { hpoOntologySha256 = value; }
    public String getBackgroundFingerprint() { return backgroundFingerprint; }
    public void setBackgroundFingerprint(String value) { backgroundFingerprint = value; }
    public Integer getMainCaseCount() { return mainCaseCount; }
    public void setMainCaseCount(Integer value) { mainCaseCount = value; }
    public Integer getBackupCaseCount() { return backupCaseCount; }
    public void setBackupCaseCount(Integer value) { backupCaseCount = value; }
    public String getHumanReviewStatus() { return humanReviewStatus; }
    public void setHumanReviewStatus(String value) { humanReviewStatus = value; }
    public List<BenchmarkDatasetCase> getCases() { return cases; }
    public void setCases(List<BenchmarkDatasetCase> value) { cases = value; }
}
