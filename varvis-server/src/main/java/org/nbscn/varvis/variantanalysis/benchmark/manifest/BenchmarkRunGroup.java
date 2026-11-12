package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({
        "groupId", "caseId", "hpoInputMode", "hpoTerms", "hpoInputChecksum",
        "hpoProvenance", "transformationProvenance", "configurationFingerprint"
})
public class BenchmarkRunGroup {

    private String groupId;
    private String caseId;
    private String hpoInputMode;
    private List<String> hpoTerms = new ArrayList<String>();
    private String hpoInputChecksum;
    private String hpoProvenance;
    private String transformationProvenance;
    private String configurationFingerprint;

    public BenchmarkRunGroup() {
    }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getHpoInputMode() { return hpoInputMode; }
    public void setHpoInputMode(String hpoInputMode) { this.hpoInputMode = hpoInputMode; }
    public List<String> getHpoTerms() { return hpoTerms; }
    public void setHpoTerms(List<String> hpoTerms) { this.hpoTerms = hpoTerms; }
    public String getHpoInputChecksum() { return hpoInputChecksum; }
    public void setHpoInputChecksum(String hpoInputChecksum) { this.hpoInputChecksum = hpoInputChecksum; }
    public String getHpoProvenance() { return hpoProvenance; }
    public void setHpoProvenance(String hpoProvenance) { this.hpoProvenance = hpoProvenance; }
    public String getTransformationProvenance() { return transformationProvenance; }
    public void setTransformationProvenance(String transformationProvenance) { this.transformationProvenance = transformationProvenance; }
    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public void setConfigurationFingerprint(String configurationFingerprint) { this.configurationFingerprint = configurationFingerprint; }
}
