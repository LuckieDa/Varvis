package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({
        "phenopacketId", "caseId", "datasetId", "datasetVersion", "observedHpo",
        "excludedHpo", "diseaseIds", "diseaseLabels", "truthGeneIds", "truthGenes",
        "inheritanceMode", "datasetCase"
})
public class PhenopacketCaseData {

    private String phenopacketId;
    private String caseId;
    private String datasetId;
    private String datasetVersion;
    private List<PhenopacketHpoTerm> observedHpo = new ArrayList<PhenopacketHpoTerm>();
    private List<PhenopacketHpoTerm> excludedHpo = new ArrayList<PhenopacketHpoTerm>();
    private List<String> diseaseIds = new ArrayList<String>();
    private List<String> diseaseLabels = new ArrayList<String>();
    private List<String> truthGeneIds = new ArrayList<String>();
    private List<String> truthGenes = new ArrayList<String>();
    private String inheritanceMode;
    private BenchmarkDatasetCase datasetCase;

    public String getPhenopacketId() { return phenopacketId; }
    public void setPhenopacketId(String value) { phenopacketId = value; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String value) { caseId = value; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String value) { datasetId = value; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String value) { datasetVersion = value; }
    public List<PhenopacketHpoTerm> getObservedHpo() { return observedHpo; }
    public void setObservedHpo(List<PhenopacketHpoTerm> value) { observedHpo = value; }
    public List<PhenopacketHpoTerm> getExcludedHpo() { return excludedHpo; }
    public void setExcludedHpo(List<PhenopacketHpoTerm> value) { excludedHpo = value; }
    public List<String> getDiseaseIds() { return diseaseIds; }
    public void setDiseaseIds(List<String> value) { diseaseIds = value; }
    public List<String> getDiseaseLabels() { return diseaseLabels; }
    public void setDiseaseLabels(List<String> value) { diseaseLabels = value; }
    public List<String> getTruthGeneIds() { return truthGeneIds; }
    public void setTruthGeneIds(List<String> value) { truthGeneIds = value; }
    public List<String> getTruthGenes() { return truthGenes; }
    public void setTruthGenes(List<String> value) { truthGenes = value; }
    public String getInheritanceMode() { return inheritanceMode; }
    public void setInheritanceMode(String value) { inheritanceMode = value; }
    public BenchmarkDatasetCase getDatasetCase() { return datasetCase; }
    public void setDatasetCase(BenchmarkDatasetCase value) { datasetCase = value; }
}
