package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"severity", "code", "path", "message"})
public class PhenopacketImportIssue {

    private String severity;
    private String code;
    private String path;
    private String message;

    public PhenopacketImportIssue() {
    }

    public PhenopacketImportIssue(String severity, String code, String path, String message) {
        this.severity = severity;
        this.code = code;
        this.path = path;
        this.message = message;
    }

    public String getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getPath() { return path; }
    public String getMessage() { return message; }
    public void setSeverity(String value) { severity = value; }
    public void setCode(String value) { code = value; }
    public void setPath(String value) { path = value; }
    public void setMessage(String value) { message = value; }

    @Override
    public String toString() {
        return code + "@" + path + ":" + message;
    }
}
