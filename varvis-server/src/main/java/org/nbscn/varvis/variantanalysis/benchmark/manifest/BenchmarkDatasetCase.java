package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonPropertyOrder({
        "caseId", "sourceRecordId", "sourceUri", "sourceLicense", "inclusionStatus",
        "exclusionReason", "assembly", "inheritanceMode", "truthGenes", "truthGeneIds",
        "truthVariants", "truthProvenance", "truthConfidence", "truthRelationship",
        "originalHpoTerms", "excludedHpoTerms", "hpoTermLabels", "hpoProvenance",
        "phenopacketId", "diseaseIds", "diseaseLabels", "diagnosisProvenance",
        "preparationProvenance"
})
public class BenchmarkDatasetCase {

    private String caseId;
    private String sourceRecordId;
    private String sourceUri;
    private String sourceLicense;
    private String inclusionStatus;
    private String exclusionReason;
    private String assembly;
    private String inheritanceMode;
    private List<String> truthGenes = new ArrayList<String>();
    private List<String> truthGeneIds = new ArrayList<String>();
    private List<BenchmarkTruthVariant> truthVariants = new ArrayList<BenchmarkTruthVariant>();
    private String truthProvenance;
    private String truthConfidence;
    private String truthRelationship;
    private List<String> originalHpoTerms = new ArrayList<String>();
    private List<String> excludedHpoTerms = new ArrayList<String>();
    private Map<String, String> hpoTermLabels = new LinkedHashMap<String, String>();
    private String hpoProvenance;
    private String phenopacketId;
    private List<String> diseaseIds = new ArrayList<String>();
    private List<String> diseaseLabels = new ArrayList<String>();
    private String diagnosisProvenance;
    private BenchmarkPreparationProvenance preparationProvenance;

    public BenchmarkDatasetCase() {
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getSourceRecordId() { return sourceRecordId; }
    public void setSourceRecordId(String sourceRecordId) { this.sourceRecordId = sourceRecordId; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public String getSourceLicense() { return sourceLicense; }
    public void setSourceLicense(String sourceLicense) { this.sourceLicense = sourceLicense; }
    public String getInclusionStatus() { return inclusionStatus; }
    public void setInclusionStatus(String inclusionStatus) { this.inclusionStatus = inclusionStatus; }
    public String getExclusionReason() { return exclusionReason; }
    public void setExclusionReason(String exclusionReason) { this.exclusionReason = exclusionReason; }
    public String getAssembly() { return assembly; }
    public void setAssembly(String assembly) { this.assembly = assembly; }
    public String getInheritanceMode() { return inheritanceMode; }
    public void setInheritanceMode(String inheritanceMode) { this.inheritanceMode = inheritanceMode; }
    public List<String> getTruthGenes() { return truthGenes; }
    public void setTruthGenes(List<String> truthGenes) { this.truthGenes = truthGenes; }
    public List<String> getTruthGeneIds() { return truthGeneIds; }
    public void setTruthGeneIds(List<String> truthGeneIds) { this.truthGeneIds = truthGeneIds; }
    public List<BenchmarkTruthVariant> getTruthVariants() { return truthVariants; }
    public void setTruthVariants(List<BenchmarkTruthVariant> truthVariants) { this.truthVariants = truthVariants; }
    public String getTruthProvenance() { return truthProvenance; }
    public void setTruthProvenance(String truthProvenance) { this.truthProvenance = truthProvenance; }
    public String getTruthConfidence() { return truthConfidence; }
    public void setTruthConfidence(String truthConfidence) { this.truthConfidence = truthConfidence; }
    public String getTruthRelationship() { return truthRelationship; }
    public void setTruthRelationship(String truthRelationship) { this.truthRelationship = truthRelationship; }
    public List<String> getOriginalHpoTerms() { return originalHpoTerms; }
    public void setOriginalHpoTerms(List<String> originalHpoTerms) { this.originalHpoTerms = originalHpoTerms; }
    public List<String> getExcludedHpoTerms() { return excludedHpoTerms; }
    public void setExcludedHpoTerms(List<String> excludedHpoTerms) { this.excludedHpoTerms = excludedHpoTerms; }
    public Map<String, String> getHpoTermLabels() { return hpoTermLabels; }
    public void setHpoTermLabels(Map<String, String> hpoTermLabels) { this.hpoTermLabels = hpoTermLabels; }
    public String getHpoProvenance() { return hpoProvenance; }
    public void setHpoProvenance(String hpoProvenance) { this.hpoProvenance = hpoProvenance; }
    public String getPhenopacketId() { return phenopacketId; }
    public void setPhenopacketId(String phenopacketId) { this.phenopacketId = phenopacketId; }
    public List<String> getDiseaseIds() { return diseaseIds; }
    public void setDiseaseIds(List<String> diseaseIds) { this.diseaseIds = diseaseIds; }
    public List<String> getDiseaseLabels() { return diseaseLabels; }
    public void setDiseaseLabels(List<String> diseaseLabels) { this.diseaseLabels = diseaseLabels; }
    public String getDiagnosisProvenance() { return diagnosisProvenance; }
    public void setDiagnosisProvenance(String diagnosisProvenance) { this.diagnosisProvenance = diagnosisProvenance; }
    public BenchmarkPreparationProvenance getPreparationProvenance() { return preparationProvenance; }
    public void setPreparationProvenance(BenchmarkPreparationProvenance preparationProvenance) { this.preparationProvenance = preparationProvenance; }
}
