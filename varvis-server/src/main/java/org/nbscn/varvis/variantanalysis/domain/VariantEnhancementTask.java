package org.nbscn.varvis.variantanalysis.domain;

import java.time.Instant;

public class VariantEnhancementTask {

    private final String id;
    private final String analysisId;
    private final String provider;
    private final int requestedCandidateLimit;
    private final Instant createdAt;

    private volatile VariantEnhancementStatus status;
    private volatile int selectedCandidateCount;
    private volatile int completedCount;
    private volatile int unavailableCount;
    private volatile String errorMessage;
    private volatile Instant updatedAt;

    public VariantEnhancementTask(String id, String analysisId, String provider, int requestedCandidateLimit) {
        this(id, analysisId, provider, requestedCandidateLimit, VariantEnhancementStatus.QUEUED,
                0, 0, 0, null, Instant.now(), null);
    }

    private VariantEnhancementTask(String id,
                                   String analysisId,
                                   String provider,
                                   int requestedCandidateLimit,
                                   VariantEnhancementStatus status,
                                   int selectedCandidateCount,
                                   int completedCount,
                                   int unavailableCount,
                                   String errorMessage,
                                   Instant createdAt,
                                   Instant updatedAt) {
        this.id = id;
        this.analysisId = analysisId;
        this.provider = provider;
        this.requestedCandidateLimit = requestedCandidateLimit;
        this.status = status;
        this.selectedCandidateCount = selectedCandidateCount;
        this.completedCount = completedCount;
        this.unavailableCount = unavailableCount;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static VariantEnhancementTask restore(String id,
                                                 String analysisId,
                                                 String provider,
                                                 int requestedCandidateLimit,
                                                 VariantEnhancementStatus status,
                                                 int selectedCandidateCount,
                                                 int completedCount,
                                                 int unavailableCount,
                                                 String errorMessage,
                                                 Instant createdAt,
                                                 Instant updatedAt) {
        return new VariantEnhancementTask(id, analysisId, provider, requestedCandidateLimit, status,
                selectedCandidateCount, completedCount, unavailableCount, errorMessage, createdAt, updatedAt);
    }

    public synchronized void running(int selectedCandidateCount) {
        this.status = VariantEnhancementStatus.RUNNING;
        this.selectedCandidateCount = selectedCandidateCount;
        this.updatedAt = Instant.now();
    }

    public synchronized void complete(int completedCount, int unavailableCount) {
        this.completedCount = completedCount;
        this.unavailableCount = unavailableCount;
        this.status = unavailableCount == 0 ? VariantEnhancementStatus.READY : VariantEnhancementStatus.PARTIAL;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public synchronized void fail(String errorMessage) {
        this.status = VariantEnhancementStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
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

    public int getRequestedCandidateLimit() {
        return requestedCandidateLimit;
    }

    public VariantEnhancementStatus getStatus() {
        return status;
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

