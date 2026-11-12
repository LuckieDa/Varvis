package org.nbscn.varvis.variantanalysis.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class VariantAnalysisExportBundleResponse {

    private final Map<String, Object> jobMetadata;
    private final Map<String, Object> reference;
    private final Map<String, Object> summary;
    private final VariantAnalysisProviderStatusResponse providerStatus;
    private final Map<String, String> providerVersions;
    private final Map<String, Object> ranking;
    private final String transcriptAnnotationStatus;
    private final Map<String, Object> vepExecutionSummary;
    private final Map<String, Object> acmgReadySummary;
    private final List<Map<String, Object>> topCandidates;
    private final List<String> unavailableResources;
    private final Map<String, Object> runtimeMetrics;
    private final Instant generatedAt;

    public VariantAnalysisExportBundleResponse(Map<String, Object> jobMetadata,
                                               Map<String, Object> reference,
                                               Map<String, Object> summary,
                                               VariantAnalysisProviderStatusResponse providerStatus,
                                               Map<String, String> providerVersions,
                                               Map<String, Object> ranking,
                                               String transcriptAnnotationStatus,
                                               Map<String, Object> vepExecutionSummary,
                                               Map<String, Object> acmgReadySummary,
                                               List<Map<String, Object>> topCandidates,
                                               List<String> unavailableResources,
                                               Map<String, Object> runtimeMetrics,
                                               Instant generatedAt) {
        this.jobMetadata = jobMetadata;
        this.reference = reference;
        this.summary = summary;
        this.providerStatus = providerStatus;
        this.providerVersions = providerVersions;
        this.ranking = ranking;
        this.transcriptAnnotationStatus = transcriptAnnotationStatus;
        this.vepExecutionSummary = vepExecutionSummary;
        this.acmgReadySummary = acmgReadySummary;
        this.topCandidates = topCandidates;
        this.unavailableResources = unavailableResources;
        this.runtimeMetrics = runtimeMetrics;
        this.generatedAt = generatedAt;
    }

    public Map<String, Object> getJobMetadata() {
        return jobMetadata;
    }

    public Map<String, Object> getReference() {
        return reference;
    }

    public Map<String, Object> getSummary() {
        return summary;
    }

    public VariantAnalysisProviderStatusResponse getProviderStatus() {
        return providerStatus;
    }

    public Map<String, String> getProviderVersions() {
        return providerVersions;
    }

    public Map<String, Object> getRanking() {
        return ranking;
    }

    public String getTranscriptAnnotationStatus() {
        return transcriptAnnotationStatus;
    }

    public Map<String, Object> getVepExecutionSummary() {
        return vepExecutionSummary;
    }

    public Map<String, Object> getAcmgReadySummary() {
        return acmgReadySummary;
    }

    public List<Map<String, Object>> getTopCandidates() {
        return topCandidates;
    }

    public List<String> getUnavailableResources() {
        return unavailableResources;
    }

    public Map<String, Object> getRuntimeMetrics() {
        return runtimeMetrics;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
