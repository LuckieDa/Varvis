package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/** Optional preparation metadata; absent on legacy Stage 2B-2 manifests. */
@JsonPropertyOrder({
        "phenopacketSchemaVersion", "adapterVersion", "eligibilityStatus",
        "exclusionReasons", "sourceHpoFingerprint", "truthFingerprint",
        "backgroundFingerprint", "phevalVersion", "executionMode",
        "commandPlanFingerprint", "randomSeed", "expectedArtifactLogicalReference",
        "generatedArtifactLogicalReference", "outputChecksum", "validationStatus",
        "preparationFailureStatus"
})
public class BenchmarkPreparationProvenance {

    private String phenopacketSchemaVersion;
    private String adapterVersion;
    private String eligibilityStatus;
    private List<String> exclusionReasons = new ArrayList<String>();
    private String sourceHpoFingerprint;
    private String truthFingerprint;
    private String backgroundFingerprint;
    private String phevalVersion;
    private String executionMode;
    private String commandPlanFingerprint;
    private Long randomSeed;
    private String expectedArtifactLogicalReference;
    private String generatedArtifactLogicalReference;
    private String outputChecksum;
    private String validationStatus;
    private String preparationFailureStatus;

    public String getPhenopacketSchemaVersion() { return phenopacketSchemaVersion; }
    public void setPhenopacketSchemaVersion(String value) { phenopacketSchemaVersion = value; }
    public String getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(String value) { adapterVersion = value; }
    public String getEligibilityStatus() { return eligibilityStatus; }
    public void setEligibilityStatus(String value) { eligibilityStatus = value; }
    public List<String> getExclusionReasons() { return exclusionReasons; }
    public void setExclusionReasons(List<String> value) { exclusionReasons = value; }
    public String getSourceHpoFingerprint() { return sourceHpoFingerprint; }
    public void setSourceHpoFingerprint(String value) { sourceHpoFingerprint = value; }
    public String getTruthFingerprint() { return truthFingerprint; }
    public void setTruthFingerprint(String value) { truthFingerprint = value; }
    public String getBackgroundFingerprint() { return backgroundFingerprint; }
    public void setBackgroundFingerprint(String value) { backgroundFingerprint = value; }
    public String getPhevalVersion() { return phevalVersion; }
    public void setPhevalVersion(String value) { phevalVersion = value; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String value) { executionMode = value; }
    public String getCommandPlanFingerprint() { return commandPlanFingerprint; }
    public void setCommandPlanFingerprint(String value) { commandPlanFingerprint = value; }
    public Long getRandomSeed() { return randomSeed; }
    public void setRandomSeed(Long value) { randomSeed = value; }
    public String getExpectedArtifactLogicalReference() { return expectedArtifactLogicalReference; }
    public void setExpectedArtifactLogicalReference(String value) { expectedArtifactLogicalReference = value; }
    public String getGeneratedArtifactLogicalReference() { return generatedArtifactLogicalReference; }
    public void setGeneratedArtifactLogicalReference(String value) { generatedArtifactLogicalReference = value; }
    public String getOutputChecksum() { return outputChecksum; }
    public void setOutputChecksum(String value) { outputChecksum = value; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String value) { validationStatus = value; }
    public String getPreparationFailureStatus() { return preparationFailureStatus; }
    public void setPreparationFailureStatus(String value) { preparationFailureStatus = value; }
}
