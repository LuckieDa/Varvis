package org.nbscn.varvis.variantanalysis.domain;

import java.time.Instant;

public class VariantAnalysisEvent {

    private final Long id;
    private final String analysisId;
    private final VariantAnalysisStatus status;
    private final String message;
    private final Instant createdAt;

    public VariantAnalysisEvent(Long id,
                                String analysisId,
                                VariantAnalysisStatus status,
                                String message,
                                Instant createdAt) {
        this.id = id;
        this.analysisId = analysisId;
        this.status = status;
        this.message = message;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public VariantAnalysisStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
