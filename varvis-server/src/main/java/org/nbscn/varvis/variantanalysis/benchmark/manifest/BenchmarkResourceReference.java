package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "resourceId", "resourceType", "version", "assembly", "sourceUri",
        "localRelativePath", "fileName", "byteSize", "checksumAlgorithm",
        "checksumValue", "status", "derivationProvenance"
})
public class BenchmarkResourceReference {

    private String resourceId;
    private String resourceType;
    private String version;
    private String assembly;
    private String sourceUri;
    private String localRelativePath;
    private String fileName;
    private Long byteSize;
    private String checksumAlgorithm;
    private String checksumValue;
    private String status;
    private String derivationProvenance;

    public BenchmarkResourceReference() {
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getAssembly() { return assembly; }
    public void setAssembly(String assembly) { this.assembly = assembly; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public String getLocalRelativePath() { return localRelativePath; }
    public void setLocalRelativePath(String localRelativePath) { this.localRelativePath = localRelativePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long byteSize) { this.byteSize = byteSize; }
    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(String checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; }
    public String getChecksumValue() { return checksumValue; }
    public void setChecksumValue(String checksumValue) { this.checksumValue = checksumValue; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDerivationProvenance() { return derivationProvenance; }
    public void setDerivationProvenance(String derivationProvenance) { this.derivationProvenance = derivationProvenance; }
}
