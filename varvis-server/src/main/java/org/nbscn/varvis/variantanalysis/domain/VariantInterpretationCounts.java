package org.nbscn.varvis.variantanalysis.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class VariantInterpretationCounts {

    private final int candidates;
    private final int frequencyFiltered;
    private final int all;
    private final Map<String, Integer> bucketCounts;
    private final int summaryOnlyCount;
    private final int fullInterpretationCount;
    private final int expensiveAnnotationSkippedCount;
    private final int largeFileModeCount;
    private final int carriedAltCount;
    private final int notCarriedAltCount;
    private final boolean largeFileCountsAvailable;
    private final boolean approximate;

    public VariantInterpretationCounts(int candidates, int frequencyFiltered, int all) {
        this(
                candidates,
                frequencyFiltered,
                all,
                Collections.<String, Integer>emptyMap(),
                0,
                all,
                0,
                0,
                0,
                0,
                false,
                true
        );
    }

    public VariantInterpretationCounts(int candidates,
                                       int frequencyFiltered,
                                       int all,
                                       Map<String, Integer> bucketCounts,
                                       int summaryOnlyCount,
                                       int fullInterpretationCount,
                                       int expensiveAnnotationSkippedCount,
                                       int largeFileModeCount,
                                       boolean largeFileCountsAvailable,
                                       boolean approximate) {
        this(
                candidates,
                frequencyFiltered,
                all,
                bucketCounts,
                summaryOnlyCount,
                fullInterpretationCount,
                expensiveAnnotationSkippedCount,
                largeFileModeCount,
                0,
                0,
                largeFileCountsAvailable,
                approximate
        );
    }

    public VariantInterpretationCounts(int candidates,
                                       int frequencyFiltered,
                                       int all,
                                       Map<String, Integer> bucketCounts,
                                       int summaryOnlyCount,
                                       int fullInterpretationCount,
                                       int expensiveAnnotationSkippedCount,
                                       int largeFileModeCount,
                                       int carriedAltCount,
                                       int notCarriedAltCount,
                                       boolean largeFileCountsAvailable,
                                       boolean approximate) {
        this.candidates = Math.max(0, candidates);
        this.frequencyFiltered = Math.max(0, frequencyFiltered);
        this.all = Math.max(0, all);
        this.bucketCounts = immutableNonNegativeMap(bucketCounts);
        this.summaryOnlyCount = Math.max(0, summaryOnlyCount);
        this.fullInterpretationCount = Math.max(0, fullInterpretationCount);
        this.expensiveAnnotationSkippedCount = Math.max(0, expensiveAnnotationSkippedCount);
        this.largeFileModeCount = Math.max(0, largeFileModeCount);
        this.carriedAltCount = Math.max(0, carriedAltCount);
        this.notCarriedAltCount = Math.max(0, notCarriedAltCount);
        this.largeFileCountsAvailable = largeFileCountsAvailable;
        this.approximate = approximate;
    }

    public int getCandidates() {
        return candidates;
    }

    public int getFrequencyFiltered() {
        return frequencyFiltered;
    }

    public int getAll() {
        return all;
    }

    public Map<String, Integer> getBucketCounts() {
        return bucketCounts;
    }

    public int getSummaryOnlyCount() {
        return summaryOnlyCount;
    }

    public int getFullInterpretationCount() {
        return fullInterpretationCount;
    }

    public int getExpensiveAnnotationSkippedCount() {
        return expensiveAnnotationSkippedCount;
    }

    public int getLargeFileModeCount() {
        return largeFileModeCount;
    }

    public int getCarriedAltCount() {
        return carriedAltCount;
    }

    public int getNotCarriedAltCount() {
        return notCarriedAltCount;
    }

    public boolean isLargeFileCountsAvailable() {
        return largeFileCountsAvailable;
    }

    public boolean isApproximate() {
        return approximate;
    }

    public Map<String, Object> toLargeFileSummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("bucketCounts", bucketCounts);
        summary.put("summaryOnlyCount", summaryOnlyCount);
        summary.put("fullInterpretationCount", fullInterpretationCount);
        summary.put("expensiveAnnotationSkippedCount", expensiveAnnotationSkippedCount);
        summary.put("largeFileModeCount", largeFileModeCount);
        summary.put("carriedAltCount", carriedAltCount);
        summary.put("notCarriedAltCount", notCarriedAltCount);
        summary.put("largeFileCountsAvailable", largeFileCountsAvailable);
        summary.put("approximate", approximate);
        return summary;
    }

    public int forView(VariantInterpretationView view) {
        VariantInterpretationView normalized =
                view == null ? VariantInterpretationView.CANDIDATES : view;
        if (normalized == VariantInterpretationView.FREQUENCY_FILTERED) {
            return frequencyFiltered;
        }
        if (normalized == VariantInterpretationView.ALL) {
            return all;
        }
        return candidates;
    }

    private static Map<String, Integer> immutableNonNegativeMap(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> copy = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Integer value = entry.getValue();
            copy.put(entry.getKey(), value == null ? 0 : Math.max(0, value.intValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
