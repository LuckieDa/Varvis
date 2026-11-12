package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SyntheticVcfValidationRequest {

    private String expectedAssembly;
    private int expectedSampleCount = 1;
    private String expectedSampleId;
    private List<String> requiredChromosomes = new ArrayList<String>(Arrays.asList(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
            "13", "14", "15", "16", "17", "18", "19", "20", "21", "22"));
    private List<NormalizedVariantKey> truthVariants = new ArrayList<NormalizedVariantKey>();
    private String expectedPreparationFingerprint;

    public String getExpectedAssembly() { return expectedAssembly; }
    public void setExpectedAssembly(String value) { expectedAssembly = value; }
    public int getExpectedSampleCount() { return expectedSampleCount; }
    public void setExpectedSampleCount(int value) { expectedSampleCount = value; }
    public String getExpectedSampleId() { return expectedSampleId; }
    public void setExpectedSampleId(String value) { expectedSampleId = value; }
    public List<String> getRequiredChromosomes() { return requiredChromosomes; }
    public void setRequiredChromosomes(List<String> value) { requiredChromosomes = value; }
    public List<NormalizedVariantKey> getTruthVariants() { return truthVariants; }
    public void setTruthVariants(List<NormalizedVariantKey> value) { truthVariants = value; }
    public String getExpectedPreparationFingerprint() { return expectedPreparationFingerprint; }
    public void setExpectedPreparationFingerprint(String value) { expectedPreparationFingerprint = value; }
}
