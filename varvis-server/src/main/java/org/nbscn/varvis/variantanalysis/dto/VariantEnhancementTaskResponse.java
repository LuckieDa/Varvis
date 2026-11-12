package org.nbscn.varvis.variantanalysis.dto;

import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;

import java.time.Instant;

public class VariantEnhancementTaskResponse {

    private String id;
    private String analysisId;
    private String provider;
    private VariantEnhancementStatus status;
    private int requestedCandidateLimit;
    private int selectedCandidateCount;
    private int completedCount;
    private int unavailableCount;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static VariantEnhancementTaskResponse from(VariantEnhancementTask task) {
        VariantEnhancementTaskResponse response = new VariantEnhancementTaskResponse();
        response.id = task.getId();
        response.analysisId = task.getAnalysisId();
        response.provider = task.getProvider();
        response.status = task.getStatus();
        response.requestedCandidateLimit = task.getRequestedCandidateLimit();
        response.selectedCandidateCount = task.getSelectedCandidateCount();
        response.completedCount = task.getCompletedCount();
        response.unavailableCount = task.getUnavailableCount();
        response.errorMessage = task.getErrorMessage();
        response.createdAt = task.getCreatedAt();
        response.updatedAt = task.getUpdatedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public String getProvider() {
        return provider;
    }

    public VariantEnhancementStatus getStatus() {
        return status;
    }

    public int getRequestedCandidateLimit() {
        return requestedCandidateLimit;
    }

    public int getSelectedCandidateCount() {
        return selectedCandidateCount;
    }

    public int getCompletedCount() {
        return completedCount;
    }

    public int getUnavailableCount() {
        return unavailableCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

