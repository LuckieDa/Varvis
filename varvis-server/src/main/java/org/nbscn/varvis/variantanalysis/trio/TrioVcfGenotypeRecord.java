package org.nbscn.varvis.variantanalysis.trio;

public class TrioVcfGenotypeRecord {

    private final TrioVcfVariantKey variantKey;
    private final TrioRole role;
    private final String sampleId;
    private final String geneSymbol;
    private final String recordFilter;
    private final String sampleFilter;
    private final String rawSampleValue;
    private final TrioGenotypeObservation observation;

    public TrioVcfGenotypeRecord(TrioVcfVariantKey variantKey,
                                 TrioRole role,
                                 String sampleId,
                                 String geneSymbol,
                                 String recordFilter,
                                 String sampleFilter,
                                 String rawSampleValue,
                                 TrioGenotypeObservation observation) {
        this.variantKey = variantKey;
        this.role = role;
        this.sampleId = sampleId;
        this.geneSymbol = geneSymbol;
        this.recordFilter = recordFilter;
        this.sampleFilter = sampleFilter;
        this.rawSampleValue = rawSampleValue;
        this.observation = observation;
    }

    public TrioVcfVariantKey getVariantKey() {
        return variantKey;
    }

    public TrioRole getRole() {
        return role;
    }

    public String getSampleId() {
        return sampleId;
    }

    public String getGeneSymbol() {
        return geneSymbol;
    }

    public String getRecordFilter() {
        return recordFilter;
    }

    public String getSampleFilter() {
        return sampleFilter;
    }

    public String getRawSampleValue() {
        return rawSampleValue;
    }

    public TrioGenotypeObservation getObservation() {
        return observation;
    }
}
