package org.nbscn.varvis.variantanalysis.casevalidation;

import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CaseValidationRun {

    private final String mode;
    private final List<VariantInterpretation> variants;
    private final PhenotypeAwareSummary phenotypeSummary;
    private final int cacheHitCount;
    private final int cacheMissCount;
    private final int providerBatchCount;
    private final String providerName;
    private final String providerVersion;
    private final String cacheVersion;
    private final String optionsHash;
    private final String inputAssembly;
    private final String annotationAssembly;
    private final String resourceAssembly;
    private final String knowledgeAssembly;
    private final boolean liftoverApplied;
    private final String liftoverStatus;
    private final List<String> warnings;

    public CaseValidationRun(String mode,
                             List<VariantInterpretation> variants,
                             PhenotypeAwareSummary phenotypeSummary,
                             int cacheHitCount,
                             int cacheMissCount,
                             int providerBatchCount,
                             String providerVersion,
                             String optionsHash,
                             List<String> warnings) {
        this(
                mode,
                variants,
                phenotypeSummary,
                cacheHitCount,
                cacheMissCount,
                providerBatchCount,
                null,
                providerVersion,
                null,
                optionsHash,
                null,
                null,
                null,
                null,
                false,
                null,
                warnings
        );
    }

    public CaseValidationRun(String mode,
                             List<VariantInterpretation> variants,
                             PhenotypeAwareSummary phenotypeSummary,
                             int cacheHitCount,
                             int cacheMissCount,
                             int providerBatchCount,
                             String providerName,
                             String providerVersion,
                             String cacheVersion,
                             String optionsHash,
                             String inputAssembly,
                             String annotationAssembly,
                             String resourceAssembly,
                             String knowledgeAssembly,
                             boolean liftoverApplied,
                             String liftoverStatus,
                             List<String> warnings) {
        this.mode = mode;
        this.variants = variants == null
                ? Collections.<VariantInterpretation>emptyList()
                : Collections.unmodifiableList(new ArrayList<VariantInterpretation>(variants));
        this.phenotypeSummary = phenotypeSummary;
        this.cacheHitCount = cacheHitCount;
        this.cacheMissCount = cacheMissCount;
        this.providerBatchCount = providerBatchCount;
        this.providerName = providerName;
        this.providerVersion = providerVersion;
        this.cacheVersion = cacheVersion;
        this.optionsHash = optionsHash;
        this.inputAssembly = inputAssembly;
        this.annotationAssembly = annotationAssembly;
        this.resourceAssembly = resourceAssembly;
        this.knowledgeAssembly = knowledgeAssembly;
        this.liftoverApplied = liftoverApplied;
        this.liftoverStatus = liftoverStatus;
        this.warnings = warnings == null || warnings.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public static CaseValidationRun of(String mode, List<VariantInterpretation> variants) {
        return new CaseValidationRun(
                mode,
                variants,
                null,
                0,
                0,
                0,
                null,
                null,
                Collections.<String>emptyList()
        );
    }

    public String getMode() {
        return mode;
    }

    public List<VariantInterpretation> getVariants() {
        return variants;
    }

    public PhenotypeAwareSummary getPhenotypeSummary() {
        return phenotypeSummary;
    }

    public int getCacheHitCount() {
        return cacheHitCount;
    }

    public int getCacheMissCount() {
        return cacheMissCount;
    }

    public int getProviderBatchCount() {
        return providerBatchCount;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderVersion() {
        return providerVersion;
    }

    public String getCacheVersion() {
        return cacheVersion;
    }

    public String getOptionsHash() {
        return optionsHash;
    }

    public String getInputAssembly() {
        return inputAssembly;
    }

    public String getAnnotationAssembly() {
        return annotationAssembly;
    }

    public String getResourceAssembly() {
        return resourceAssembly;
    }

    public String getKnowledgeAssembly() {
        return knowledgeAssembly;
    }

    public boolean isLiftoverApplied() {
        return liftoverApplied;
    }

    public String getLiftoverStatus() {
        return liftoverStatus;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
