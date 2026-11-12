package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({
        "schemaVersion", "datasetId", "datasetVersion", "protocolVersion", "createdAt",
        "status", "purpose", "eligibleForLockedTest", "description", "phenopacketStoreVersion", "officialSourceUri", "license",
        "archiveFileName", "archiveByteSize", "archiveSha256", "selectionRuleVersion",
        "backgroundDatasetId", "backgroundDatasetVersion", "backgroundAssembly",
        "backgroundSampleId", "backgroundSelectionBasis", "chromosomeScope",
        "backgroundResources", "immutableBackgroundFingerprint", "cases", "singleton",
        "monogenic", "smallVariantsOnly", "assemblyRequirement", "truthAcceptancePolicy",
        "exclusionPolicy"
})
public class BenchmarkDatasetManifest {

    private String schemaVersion = BenchmarkManifestSchema.DATASET;
    private String datasetId;
    private String datasetVersion;
    private String protocolVersion;
    private String createdAt;
    private String status;
    private String purpose;
    private Boolean eligibleForLockedTest;
    private String description;
    private String phenopacketStoreVersion;
    private String officialSourceUri;
    private String license;
    private String archiveFileName;
    private Long archiveByteSize;
    private String archiveSha256;
    private String selectionRuleVersion;
    private String backgroundDatasetId;
    private String backgroundDatasetVersion;
    private String backgroundAssembly;
    private String backgroundSampleId;
    private String backgroundSelectionBasis;
    private List<String> chromosomeScope = new ArrayList<String>();
    private List<BenchmarkResourceReference> backgroundResources = new ArrayList<BenchmarkResourceReference>();
    private String immutableBackgroundFingerprint;
    private List<BenchmarkDatasetCase> cases = new ArrayList<BenchmarkDatasetCase>();
    private Boolean singleton;
    private Boolean monogenic;
    private Boolean smallVariantsOnly;
    private String assemblyRequirement;
    private String truthAcceptancePolicy;
    private String exclusionPolicy;

    public BenchmarkDatasetManifest() {
    }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public Boolean getEligibleForLockedTest() { return eligibleForLockedTest; }
    public void setEligibleForLockedTest(Boolean eligibleForLockedTest) { this.eligibleForLockedTest = eligibleForLockedTest; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPhenopacketStoreVersion() { return phenopacketStoreVersion; }
    public void setPhenopacketStoreVersion(String phenopacketStoreVersion) { this.phenopacketStoreVersion = phenopacketStoreVersion; }
    public String getOfficialSourceUri() { return officialSourceUri; }
    public void setOfficialSourceUri(String officialSourceUri) { this.officialSourceUri = officialSourceUri; }
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    public String getArchiveFileName() { return archiveFileName; }
    public void setArchiveFileName(String archiveFileName) { this.archiveFileName = archiveFileName; }
    public Long getArchiveByteSize() { return archiveByteSize; }
    public void setArchiveByteSize(Long archiveByteSize) { this.archiveByteSize = archiveByteSize; }
    public String getArchiveSha256() { return archiveSha256; }
    public void setArchiveSha256(String archiveSha256) { this.archiveSha256 = archiveSha256; }
    public String getSelectionRuleVersion() { return selectionRuleVersion; }
    public void setSelectionRuleVersion(String selectionRuleVersion) { this.selectionRuleVersion = selectionRuleVersion; }
    public String getBackgroundDatasetId() { return backgroundDatasetId; }
    public void setBackgroundDatasetId(String backgroundDatasetId) { this.backgroundDatasetId = backgroundDatasetId; }
    public String getBackgroundDatasetVersion() { return backgroundDatasetVersion; }
    public void setBackgroundDatasetVersion(String backgroundDatasetVersion) { this.backgroundDatasetVersion = backgroundDatasetVersion; }
    public String getBackgroundAssembly() { return backgroundAssembly; }
    public void setBackgroundAssembly(String backgroundAssembly) { this.backgroundAssembly = backgroundAssembly; }
    public String getBackgroundSampleId() { return backgroundSampleId; }
    public void setBackgroundSampleId(String backgroundSampleId) { this.backgroundSampleId = backgroundSampleId; }
    public String getBackgroundSelectionBasis() { return backgroundSelectionBasis; }
    public void setBackgroundSelectionBasis(String backgroundSelectionBasis) { this.backgroundSelectionBasis = backgroundSelectionBasis; }
    public List<String> getChromosomeScope() { return chromosomeScope; }
    public void setChromosomeScope(List<String> chromosomeScope) { this.chromosomeScope = chromosomeScope; }
    public List<BenchmarkResourceReference> getBackgroundResources() { return backgroundResources; }
    public void setBackgroundResources(List<BenchmarkResourceReference> backgroundResources) { this.backgroundResources = backgroundResources; }
    public String getImmutableBackgroundFingerprint() { return immutableBackgroundFingerprint; }
    public void setImmutableBackgroundFingerprint(String immutableBackgroundFingerprint) { this.immutableBackgroundFingerprint = immutableBackgroundFingerprint; }
    public List<BenchmarkDatasetCase> getCases() { return cases; }
    public void setCases(List<BenchmarkDatasetCase> cases) { this.cases = cases; }
    public Boolean getSingleton() { return singleton; }
    public void setSingleton(Boolean singleton) { this.singleton = singleton; }
    public Boolean getMonogenic() { return monogenic; }
    public void setMonogenic(Boolean monogenic) { this.monogenic = monogenic; }
    public Boolean getSmallVariantsOnly() { return smallVariantsOnly; }
    public void setSmallVariantsOnly(Boolean smallVariantsOnly) { this.smallVariantsOnly = smallVariantsOnly; }
    public String getAssemblyRequirement() { return assemblyRequirement; }
    public void setAssemblyRequirement(String assemblyRequirement) { this.assemblyRequirement = assemblyRequirement; }
    public String getTruthAcceptancePolicy() { return truthAcceptancePolicy; }
    public void setTruthAcceptancePolicy(String truthAcceptancePolicy) { this.truthAcceptancePolicy = truthAcceptancePolicy; }
    public String getExclusionPolicy() { return exclusionPolicy; }
    public void setExclusionPolicy(String exclusionPolicy) { this.exclusionPolicy = exclusionPolicy; }
}
