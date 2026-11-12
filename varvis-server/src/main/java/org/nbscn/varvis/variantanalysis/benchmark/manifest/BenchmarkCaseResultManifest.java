package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryArtifact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonPropertyOrder({
        "schemaVersion", "resultId", "createdAt", "runId", "datasetId", "caseId", "groupId",
        "caseExecutionStatus", "analysisReference", "resultBinding", "databaseAttestation", "telemetryArtifact",
        "truthMatches", "stageRetention",
        "providerStatuses", "primaryFailureCode", "secondaryFailureCodes", "failureNote"
})
public class BenchmarkCaseResultManifest {

    private String schemaVersion = BenchmarkManifestSchema.CASE_RESULT;
    private String resultId;
    private String createdAt;
    private String runId;
    private String datasetId;
    private String caseId;
    private String groupId;
    private String caseExecutionStatus;
    private String analysisReference;
    private BenchmarkResultBinding resultBinding;
    private BenchmarkDatabaseAttestation databaseAttestation;
    private BenchmarkTelemetryArtifact telemetryArtifact;
    private List<BenchmarkTruthMatch> truthMatches = new ArrayList<BenchmarkTruthMatch>();
    private List<BenchmarkStageRetention> stageRetention = new ArrayList<BenchmarkStageRetention>();
    private Map<String, String> providerStatuses = new LinkedHashMap<String, String>();
    private String primaryFailureCode;
    private List<String> secondaryFailureCodes = new ArrayList<String>();
    private String failureNote;

    public BenchmarkCaseResultManifest() {
    }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getResultId() { return resultId; }
    public void setResultId(String resultId) { this.resultId = resultId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getCaseExecutionStatus() { return caseExecutionStatus; }
    public void setCaseExecutionStatus(String caseExecutionStatus) { this.caseExecutionStatus = caseExecutionStatus; }
    public String getAnalysisReference() { return analysisReference; }
    public void setAnalysisReference(String analysisReference) { this.analysisReference = analysisReference; }
    public BenchmarkResultBinding getResultBinding() { return resultBinding; }
    public void setResultBinding(BenchmarkResultBinding value) { resultBinding = value; }
    public BenchmarkDatabaseAttestation getDatabaseAttestation() { return databaseAttestation; }
    public void setDatabaseAttestation(BenchmarkDatabaseAttestation value) { databaseAttestation = value; }
    public BenchmarkTelemetryArtifact getTelemetryArtifact() { return telemetryArtifact; }
    public void setTelemetryArtifact(BenchmarkTelemetryArtifact value) { telemetryArtifact = value; }
    public List<BenchmarkTruthMatch> getTruthMatches() { return truthMatches; }
    public void setTruthMatches(List<BenchmarkTruthMatch> truthMatches) { this.truthMatches = truthMatches; }
    public List<BenchmarkStageRetention> getStageRetention() { return stageRetention; }
    public void setStageRetention(List<BenchmarkStageRetention> stageRetention) { this.stageRetention = stageRetention; }
    public Map<String, String> getProviderStatuses() { return providerStatuses; }
    public void setProviderStatuses(Map<String, String> providerStatuses) { this.providerStatuses = providerStatuses; }
    public String getPrimaryFailureCode() { return primaryFailureCode; }
    public void setPrimaryFailureCode(String primaryFailureCode) { this.primaryFailureCode = primaryFailureCode; }
    public List<String> getSecondaryFailureCodes() { return secondaryFailureCodes; }
    public void setSecondaryFailureCodes(List<String> secondaryFailureCodes) { this.secondaryFailureCodes = secondaryFailureCodes; }
    public String getFailureNote() { return failureNote; }
    public void setFailureNote(String failureNote) { this.failureNote = failureNote; }
}
