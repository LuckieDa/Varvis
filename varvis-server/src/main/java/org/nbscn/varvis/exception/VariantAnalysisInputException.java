package org.nbscn.varvis.exception;

/** Safe, structured validation failure for the product analysis request boundary. */
public class VariantAnalysisInputException extends IllegalArgumentException {

    private final String errorCode;

    public VariantAnalysisInputException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
