package org.nbscn.varvis.variantanalysis.benchmark.manifest;

public final class BenchmarkValidationError {

    private final String code;
    private final String path;
    private final String message;

    public BenchmarkValidationError(String code, String path, String message) {
        this.code = code;
        this.path = path;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getPath() { return path; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return code + " at " + path + ": " + message;
    }
}
