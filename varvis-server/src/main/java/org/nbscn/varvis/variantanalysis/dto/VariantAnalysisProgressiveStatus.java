package org.nbscn.varvis.variantanalysis.dto;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VcfPreflightSummary;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class VariantAnalysisProgressiveStatus {

    public static final String PREFLIGHT = "PREFLIGHT";
    public static final String READY_FAST = "READY_FAST";
    public static final String READY_FULL = "READY_FULL";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    public static final String PENDING = "PENDING";

    private static final String PARTIAL_WARNING =
            "READY_FAST/PARTIAL means base results are visible while some detailed evidence "
                    + "is compact, skipped, or unavailable. This is not benign or low risk.";

    private final String analysisStage;
    private final String enrichmentStatus;
    private final Instant readyFastAt;
    private final Instant readyFullAt;
    private final Map<String, Object> providerProgress;
    private final String partialEvidenceWarning;

    private VariantAnalysisProgressiveStatus(String analysisStage,
                                             String enrichmentStatus,
                                             Instant readyFastAt,
                                             Instant readyFullAt,
                                             Map<String, Object> providerProgress,
                                             String partialEvidenceWarning) {
        this.analysisStage = analysisStage;
        this.enrichmentStatus = enrichmentStatus;
        this.readyFastAt = readyFastAt;
        this.readyFullAt = readyFullAt;
        this.providerProgress = providerProgress;
        this.partialEvidenceWarning = partialEvidenceWarning;
    }

    public static VariantAnalysisProgressiveStatus from(VariantAnalysisJob job,
                                                        VariantInterpretationCounts counts) {
        if (job == null) {
            return new VariantAnalysisProgressiveStatus(
                    PREFLIGHT,
                    PENDING,
                    null,
                    null,
                    providerProgress(null, counts),
                    null
            );
        }
        VariantAnalysisStatus status = job.getStatus();
        if (status == VariantAnalysisStatus.FAILED) {
            return new VariantAnalysisProgressiveStatus(
                    FAILED,
                    FAILED,
                    null,
                    null,
                    providerProgress(job, counts),
                    job.getErrorMessage()
            );
        }
        if (status != VariantAnalysisStatus.READY) {
            return new VariantAnalysisProgressiveStatus(
                    PREFLIGHT,
                    PENDING,
                    null,
                    null,
                    providerProgress(job, counts),
                    null
            );
        }
        boolean largeFileMode = largeFileMode(job, counts);
        boolean partial = partialEvidence(counts);
        if (largeFileMode && partial) {
            return new VariantAnalysisProgressiveStatus(
                    READY_FAST,
                    PARTIAL,
                    job.getUpdatedAt(),
                    null,
                    providerProgress(job, counts),
                    PARTIAL_WARNING
            );
        }
        return new VariantAnalysisProgressiveStatus(
                READY_FULL,
                READY_FULL,
                job.getUpdatedAt(),
                job.getUpdatedAt(),
                providerProgress(job, counts),
                null
        );
    }

    public String getAnalysisStage() {
        return analysisStage;
    }

    public String getEnrichmentStatus() {
        return enrichmentStatus;
    }

    public Instant getReadyFastAt() {
        return readyFastAt;
    }

    public Instant getReadyFullAt() {
        return readyFullAt;
    }

    public Map<String, Object> getProviderProgress() {
        return providerProgress;
    }

    public String getPartialEvidenceWarning() {
        return partialEvidenceWarning;
    }

    public boolean isReadyFast() {
        return READY_FAST.equals(analysisStage) || READY_FULL.equals(analysisStage);
    }

    public boolean isReadyFull() {
        return READY_FULL.equals(analysisStage);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("analysisStage", analysisStage);
        values.put("enrichmentStatus", enrichmentStatus);
        values.put("readyFastAt", readyFastAt);
        values.put("readyFullAt", readyFullAt);
        values.put("providerProgress", providerProgress);
        values.put("partialEvidenceWarning", partialEvidenceWarning);
        values.put("readyFast", isReadyFast());
        values.put("readyFull", isReadyFull());
        return values;
    }

    private static boolean largeFileMode(VariantAnalysisJob job, VariantInterpretationCounts counts) {
        VcfPreflightSummary preflight = job == null ? null : job.getPreflightSummary();
        return (preflight != null && preflight.isLargeFileMode())
                || (counts != null && counts.getLargeFileModeCount() > 0);
    }

    private static boolean partialEvidence(VariantInterpretationCounts counts) {
        if (counts == null) {
            return false;
        }
        return counts.getSummaryOnlyCount() > 0
                || counts.getExpensiveAnnotationSkippedCount() > 0
                || (counts.isLargeFileCountsAvailable()
                        && counts.getAll() > 0
                        && counts.getFullInterpretationCount() < counts.getAll());
    }

    private static Map<String, Object> providerProgress(VariantAnalysisJob job,
                                                        VariantInterpretationCounts counts) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("statusSource", "job_status_and_large_file_counts");
        values.put("largeFileMode", largeFileMode(job, counts));
        if (counts != null) {
            values.put("largeFileCountsAvailable", counts.isLargeFileCountsAvailable());
            values.put("approximate", counts.isApproximate());
            values.put("candidateCount", counts.getCandidates());
            values.put("allVariantCount", counts.getAll());
            values.put("summaryOnlyCount", counts.getSummaryOnlyCount());
            values.put("fullInterpretationCount", counts.getFullInterpretationCount());
            values.put("expensiveAnnotationSkippedCount", counts.getExpensiveAnnotationSkippedCount());
            values.put("largeFileModeCount", counts.getLargeFileModeCount());
            values.put("bucketCounts", counts.getBucketCounts());
        }
        return values;
    }
}
