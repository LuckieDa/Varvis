package org.nbscn.varvis.variantanalysis.domain;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class VcfPreflightSummary {

    private final int totalVariants;
    private final int passVariants;
    private final int filteredVariants;
    private final int sampleCount;
    private final String selectedSampleId;
    private final int carriedAltCount;
    private final int notCarriedAltCount;
    private final int missingGenotypeCount;
    private final int multiAllelicSiteCount;
    private final boolean hasAnn;
    private final boolean hasCsq;
    private final boolean hasInfoAf;
    private final Map<String, Integer> filterDistribution;
    private final boolean largeFileMode;
    private final String largeFileModeReason;

    public VcfPreflightSummary(int totalVariants,
                               int passVariants,
                               int filteredVariants,
                               int sampleCount,
                               String selectedSampleId,
                               int carriedAltCount,
                               int notCarriedAltCount,
                               int missingGenotypeCount,
                               int multiAllelicSiteCount,
                               boolean hasAnn,
                               boolean hasCsq,
                               boolean hasInfoAf,
                               Map<String, Integer> filterDistribution,
                               boolean largeFileMode,
                               String largeFileModeReason) {
        this.totalVariants = totalVariants;
        this.passVariants = passVariants;
        this.filteredVariants = filteredVariants;
        this.sampleCount = sampleCount;
        this.selectedSampleId = selectedSampleId;
        this.carriedAltCount = carriedAltCount;
        this.notCarriedAltCount = notCarriedAltCount;
        this.missingGenotypeCount = missingGenotypeCount;
        this.multiAllelicSiteCount = multiAllelicSiteCount;
        this.hasAnn = hasAnn;
        this.hasCsq = hasCsq;
        this.hasInfoAf = hasInfoAf;
        this.filterDistribution = filterDistribution == null || filterDistribution.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(filterDistribution));
        this.largeFileMode = largeFileMode;
        this.largeFileModeReason = largeFileModeReason;
    }

    public static VcfPreflightSummary empty() {
        return new VcfPreflightSummary(
                0,
                0,
                0,
                0,
                null,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                Collections.emptyMap(),
                false,
                "no VCF QC summary is available"
        );
    }

    public static VcfPreflightSummary fromQcResult(VariantQcSummary summary,
                                                   Iterable<VariantQcObservation> observations,
                                                   String selectedSampleId,
                                                   VariantAnalysisProperties.LargeFile largeFile) {
        if (summary == null) {
            return empty();
        }
        int carriedAltCount = 0;
        int notCarriedAltCount = 0;
        int missingGenotypeCount = 0;
        Map<String, Integer> filterDistribution = new LinkedHashMap<String, Integer>();
        if (observations != null) {
            for (VariantQcObservation observation : observations) {
                if (observation == null) {
                    continue;
                }
                String filter = normalizeFilter(observation.getFilterStatus());
                filterDistribution.put(filter, filterDistribution.getOrDefault(filter, 0) + 1);
                String genotype = observation.getGenotype();
                if (isMissingGenotype(genotype)) {
                    missingGenotypeCount++;
                } else if (carriesAlternateAllele(genotype)) {
                    carriedAltCount++;
                } else {
                    notCarriedAltCount++;
                }
            }
        }
        return fromValues(
                summary,
                selectedSampleId,
                carriedAltCount,
                notCarriedAltCount,
                missingGenotypeCount,
                false,
                false,
                false,
                filterDistribution,
                largeFile
        );
    }

    public static VcfPreflightSummary fromQcSummary(VariantQcSummary summary,
                                                    String selectedSampleId,
                                                    VariantAnalysisProperties.LargeFile largeFile) {
        if (summary == null) {
            return empty();
        }
        Map<String, Integer> filterDistribution = new LinkedHashMap<String, Integer>();
        if (summary.getPassCount() > 0) {
            filterDistribution.put("PASS", summary.getPassCount());
        }
        if (summary.getFilteredCount() > 0) {
            filterDistribution.put("NON_PASS", summary.getFilteredCount());
        }
        return fromValues(
                summary,
                selectedSampleId,
                0,
                0,
                0,
                false,
                false,
                false,
                filterDistribution,
                largeFile
        );
    }

    public VcfPreflightSummary withFeatureFlags(boolean hasAnn, boolean hasCsq, boolean hasInfoAf) {
        return new VcfPreflightSummary(
                totalVariants,
                passVariants,
                filteredVariants,
                sampleCount,
                selectedSampleId,
                carriedAltCount,
                notCarriedAltCount,
                missingGenotypeCount,
                multiAllelicSiteCount,
                hasAnn,
                hasCsq,
                hasInfoAf,
                filterDistribution,
                largeFileMode,
                largeFileModeReason
        );
    }

    private static VcfPreflightSummary fromValues(VariantQcSummary summary,
                                                  String selectedSampleId,
                                                  int carriedAltCount,
                                                  int notCarriedAltCount,
                                                  int missingGenotypeCount,
                                                  boolean hasAnn,
                                                  boolean hasCsq,
                                                  boolean hasInfoAf,
                                                  Map<String, Integer> filterDistribution,
                                                  VariantAnalysisProperties.LargeFile largeFile) {
        LargeFileDecision decision = decideLargeFileMode(summary, largeFile);
        return new VcfPreflightSummary(
                summary.getVariantCount(),
                summary.getPassCount(),
                summary.getFilteredCount(),
                summary.getSampleCount(),
                selectedSampleId,
                carriedAltCount,
                notCarriedAltCount,
                missingGenotypeCount,
                summary.getMultiAllelicSiteCount(),
                hasAnn,
                hasCsq,
                hasInfoAf,
                filterDistribution,
                decision.enabled,
                decision.reason
        );
    }

    private static LargeFileDecision decideLargeFileMode(VariantQcSummary summary,
                                                         VariantAnalysisProperties.LargeFile largeFile) {
        VariantAnalysisProperties.LargeFile config = largeFile == null
                ? new VariantAnalysisProperties.LargeFile()
                : largeFile;
        if (!config.isEnabled()) {
            return new LargeFileDecision(false, "large-file mode is disabled");
        }
        int totalThreshold = config.normalizedVariantThreshold();
        int passThreshold = config.normalizedPassVariantThreshold();
        if (totalThreshold > 0 && summary.getVariantCount() >= totalThreshold) {
            return new LargeFileDecision(
                    true,
                    "variant count " + summary.getVariantCount()
                            + " reached threshold " + totalThreshold
            );
        }
        if (passThreshold > 0 && summary.getPassCount() >= passThreshold) {
            return new LargeFileDecision(
                    true,
                    "PASS variant count " + summary.getPassCount()
                            + " reached threshold " + passThreshold
            );
        }
        return new LargeFileDecision(false, "variant count below large-file thresholds");
    }

    private static String normalizeFilter(String filterStatus) {
        return filterStatus == null || filterStatus.trim().isEmpty()
                ? "MISSING"
                : filterStatus.trim();
    }

    private static boolean isMissingGenotype(String genotype) {
        return genotype == null || genotype.trim().isEmpty() || genotype.contains(".");
    }

    private static boolean carriesAlternateAllele(String genotype) {
        String[] alleles = genotype.trim().split("[/|]");
        boolean sawCalledAllele = false;
        for (String allele : alleles) {
            if (allele == null) {
                continue;
            }
            String value = allele.trim();
            if (value.isEmpty() || ".".equals(value)) {
                continue;
            }
            sawCalledAllele = true;
            if (!"0".equals(value)) {
                return true;
            }
        }
        return !sawCalledAllele;
    }

    public int getTotalVariants() {
        return totalVariants;
    }

    public int getPassVariants() {
        return passVariants;
    }

    public int getFilteredVariants() {
        return filteredVariants;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public String getSelectedSampleId() {
        return selectedSampleId;
    }

    public int getCarriedAltCount() {
        return carriedAltCount;
    }

    public int getNotCarriedAltCount() {
        return notCarriedAltCount;
    }

    public int getMissingGenotypeCount() {
        return missingGenotypeCount;
    }

    public int getMultiAllelicSiteCount() {
        return multiAllelicSiteCount;
    }

    public boolean isHasAnn() {
        return hasAnn;
    }

    public boolean isHasCsq() {
        return hasCsq;
    }

    public boolean isHasInfoAf() {
        return hasInfoAf;
    }

    public Map<String, Integer> getFilterDistribution() {
        return filterDistribution;
    }

    public boolean isLargeFileMode() {
        return largeFileMode;
    }

    public String getLargeFileModeReason() {
        return largeFileModeReason;
    }

    private static class LargeFileDecision {

        private final boolean enabled;
        private final String reason;

        private LargeFileDecision(boolean enabled, String reason) {
            this.enabled = enabled;
            this.reason = reason;
        }
    }
}
