package org.nbscn.varvis.exception;

/** A server persistence prerequisite failed before an analysis was queued. */
public class VariantAnalysisPersistenceException extends RuntimeException {
    private final String errorCode;

    public VariantAnalysisPersistenceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
