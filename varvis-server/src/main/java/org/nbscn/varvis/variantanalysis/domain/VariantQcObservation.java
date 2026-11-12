package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class VariantQcObservation {

    private final NormalizedVariantKey key;
    private final String sampleId;
    private final String genotype;
    private final Integer depth;
    private final Integer genotypeQuality;
    private final String alleleDepths;
    private final Double alleleBalance;
    private final String filterStatus;
    private final String variantType;
    private final String originalRecord;

    @JsonCreator
    public VariantQcObservation(@JsonProperty("key") NormalizedVariantKey key,
                                @JsonProperty("sampleId") String sampleId,
                                @JsonProperty("genotype") String genotype,
                                @JsonProperty("depth") Integer depth,
                                @JsonProperty("genotypeQuality") Integer genotypeQuality,
                                @JsonProperty("alleleDepths") String alleleDepths,
                                @JsonProperty("alleleBalance") Double alleleBalance,
                                @JsonProperty("filterStatus") String filterStatus,
                                @JsonProperty("variantType") String variantType,
                                @JsonProperty("originalRecord") String originalRecord) {
        this.key = key;
        this.sampleId = sampleId;
        this.genotype = genotype;
        this.depth = depth;
        this.genotypeQuality = genotypeQuality;
        this.alleleDepths = alleleDepths;
        this.alleleBalance = alleleBalance;
        this.filterStatus = filterStatus;
        this.variantType = variantType;
        this.originalRecord = originalRecord;
    }

    public NormalizedVariantKey getKey() {
        return key;
    }

    public String getSampleId() {
        return sampleId;
    }

    public String getGenotype() {
        return genotype;
    }

    public Integer getDepth() {
        return depth;
    }

    public Integer getGenotypeQuality() {
        return genotypeQuality;
    }

    public String getAlleleDepths() {
        return alleleDepths;
    }

    public Double getAlleleBalance() {
        return alleleBalance;
    }

    public String getFilterStatus() {
        return filterStatus;
    }

    public String getVariantType() {
        return variantType;
    }

    public String getOriginalRecord() {
        return originalRecord;
    }

    public NormalizationAlleleMapping normalizationMapping() {
        return NormalizationAlleleMapping.from(originalRecord, key);
    }
}
