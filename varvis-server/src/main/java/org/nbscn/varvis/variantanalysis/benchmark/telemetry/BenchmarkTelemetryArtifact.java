package org.nbscn.varvis.variantanalysis.benchmark.telemetry;

public class BenchmarkTelemetryArtifact {

    private String schemaVersion;
    private String logicalFileName;
    private String sha256;
    private String status;

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getLogicalFileName() { return logicalFileName; }
    public void setLogicalFileName(String logicalFileName) { this.logicalFileName = logicalFileName; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
