package org.nbscn.varvis.variantanalysis.dto;

import java.time.Instant;

/**
 * Minimal, read-only lifecycle diagnosis for one variant-analysis job.
 *
 * <p>The contract intentionally contains only fixed enums and persisted
 * timestamps. It must not grow raw errors, event messages, paths, sample data,
 * ranking data, or provider payloads.</p>
 */
public final class VariantAnalysisDiagnosticResponse {

    public enum RunState {
        UNKNOWN,
        WAITING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public enum Stage {
        UNKNOWN,
        UPLOADED,
        VALIDATING,
        NORMALIZING,
        QC_EXTRACTING,
        BASE_ANNOTATING,
        RANKING,
        READY,
        FAILED
    }

    public enum FailureCode {
        NONE,
        INVALID_INPUT,
        ENGINE_UNAVAILABLE,
        ENGINE_TIMEOUT,
        ENGINE_EXECUTION_FAILED,
        ENGINE_INTERRUPTED,
        ENGINE_OUTPUT_INVALID,
        NORMALIZATION_FAILED,
        PERSISTENCE_INTERRUPTED,
        APPLICATION_RESTARTED,
        INTERNAL_FAILURE,
        UNKNOWN_FAILURE
    }

    public enum FailureCategory {
        NONE,
        INPUT,
        CONFIGURATION,
        TIMEOUT,
        EXECUTION,
        INTERRUPTION,
        OUTPUT,
        NORMALIZATION,
        PERSISTENCE,
        INTERNAL,
        UNKNOWN
    }

    public enum NextAction {
        WAIT_FOR_COMPLETION,
        REVIEW_RESULTS,
        CORRECT_INPUT_AND_RESUBMIT,
        RESUBMIT_ANALYSIS,
        CONTACT_OPERATOR
    }

    public enum RetryDisposition {
        NOT_APPLICABLE,
        AFTER_INPUT_CORRECTION,
        RESUBMISSION_ALLOWED,
        AFTER_OPERATOR_REMEDIATION
    }

    private final String schemaVersion;
    private final RunState runState;
    private final Stage stage;
    private final FailureCode failureCode;
    private final FailureCategory failureCategory;
    private final NextAction nextAction;
    private final RetryDisposition retryDisposition;
    private final Instant createdAt;
    private final Instant updatedAt;

    public VariantAnalysisDiagnosticResponse(RunState runState,
                                             Stage stage,
                                             FailureCode failureCode,
                                             FailureCategory failureCategory,
                                             NextAction nextAction,
                                             RetryDisposition retryDisposition,
                                             Instant createdAt,
                                             Instant updatedAt) {
        this.schemaVersion = "variant-analysis-diagnostics-v1";
        this.runState = runState == null ? RunState.UNKNOWN : runState;
        this.stage = stage == null ? Stage.UNKNOWN : stage;
        this.failureCode = failureCode == null ? FailureCode.UNKNOWN_FAILURE : failureCode;
        this.failureCategory = failureCategory == null ? FailureCategory.UNKNOWN : failureCategory;
        this.nextAction = nextAction == null ? NextAction.CONTACT_OPERATOR : nextAction;
        this.retryDisposition = retryDisposition == null
                ? RetryDisposition.AFTER_OPERATOR_REMEDIATION : retryDisposition;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public RunState getRunState() {
        return runState;
    }

    public Stage getStage() {
        return stage;
    }

    public FailureCode getFailureCode() {
        return failureCode;
    }

    public FailureCategory getFailureCategory() {
        return failureCategory;
    }

    public NextAction getNextAction() {
        return nextAction;
    }

    public RetryDisposition getRetryDisposition() {
        return retryDisposition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
