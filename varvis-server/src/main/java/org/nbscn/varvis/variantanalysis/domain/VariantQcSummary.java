package org.nbscn.varvis.variantanalysis.domain;

public class VariantQcSummary {

    private final int sampleCount;
    private final int inputSiteCount;
    private final int variantCount;
    private final int multiAllelicSiteCount;
    private final int snvCount;
    private final int indelCount;
    private final int mnvCount;
    private final int otherCount;
    private final int passCount;
    private final int filteredCount;
    private final int missingDepthCount;
    private final int missingGenotypeQualityCount;

    public VariantQcSummary(int sampleCount,
                            int inputSiteCount,
                            int variantCount,
                            int multiAllelicSiteCount,
                            int snvCount,
                            int indelCount,
                            int mnvCount,
                            int otherCount,
                            int passCount,
                            int filteredCount,
                            int missingDepthCount,
                            int missingGenotypeQualityCount) {
        this.sampleCount = sampleCount;
        this.inputSiteCount = inputSiteCount;
        this.variantCount = variantCount;
        this.multiAllelicSiteCount = multiAllelicSiteCount;
        this.snvCount = snvCount;
        this.indelCount = indelCount;
        this.mnvCount = mnvCount;
        this.otherCount = otherCount;
        this.passCount = passCount;
        this.filteredCount = filteredCount;
        this.missingDepthCount = missingDepthCount;
        this.missingGenotypeQualityCount = missingGenotypeQualityCount;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public int getInputSiteCount() {
        return inputSiteCount;
    }

    public int getVariantCount() {
        return variantCount;
    }

    public int getMultiAllelicSiteCount() {
        return multiAllelicSiteCount;
    }

    public int getSnvCount() {
        return snvCount;
    }

    public int getIndelCount() {
        return indelCount;
    }

    public int getMnvCount() {
        return mnvCount;
    }

    public int getOtherCount() {
        return otherCount;
    }

    public int getPassCount() {
        return passCount;
    }

    public int getFilteredCount() {
        return filteredCount;
    }

    public int getMissingDepthCount() {
        return missingDepthCount;
    }

    public int getMissingGenotypeQualityCount() {
        return missingGenotypeQualityCount;
    }
}
