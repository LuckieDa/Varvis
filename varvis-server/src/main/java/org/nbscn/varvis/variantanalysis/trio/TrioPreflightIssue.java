package org.nbscn.varvis.variantanalysis.trio;

public class TrioPreflightIssue {

    private final TrioPreflightSeverity severity;
    private final String code;
    private final String message;

    public TrioPreflightIssue(TrioPreflightSeverity severity, String code, String message) {
        this.severity = severity == null ? TrioPreflightSeverity.ERROR : severity;
        this.code = code;
        this.message = message;
    }

    public TrioPreflightSeverity getSeverity() {
        return severity;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
