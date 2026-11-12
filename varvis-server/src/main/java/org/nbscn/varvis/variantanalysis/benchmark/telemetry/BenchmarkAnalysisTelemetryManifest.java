package org.nbscn.varvis.variantanalysis.benchmark.telemetry;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkAnalysisTelemetryManifest {

    public static final String SCHEMA_VERSION = "benchmark-analysis-telemetry-v1";

    private String schemaVersion = SCHEMA_VERSION;
    private String attemptId;
    private String requestFingerprint;
    private String analysisReference;
    private String configurationFingerprint;
    private String status;
    private String createdAt;
    private Integer candidateHardLimit;
    private Integer vepCandidateCap;
    private Map<String, CountValue> counts = new LinkedHashMap<String, CountValue>();
    private List<TruthTelemetry> truths;

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getAnalysisReference() { return analysisReference; }
    public void setAnalysisReference(String analysisReference) { this.analysisReference = analysisReference; }
    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public void setConfigurationFingerprint(String configurationFingerprint) { this.configurationFingerprint = configurationFingerprint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public Integer getCandidateHardLimit() { return candidateHardLimit; }
    public void setCandidateHardLimit(Integer candidateHardLimit) { this.candidateHardLimit = candidateHardLimit; }
    public Integer getVepCandidateCap() { return vepCandidateCap; }
    public void setVepCandidateCap(Integer vepCandidateCap) { this.vepCandidateCap = vepCandidateCap; }
    public Map<String, CountValue> getCounts() { return counts; }
    public void setCounts(Map<String, CountValue> counts) { this.counts = counts; }
    public List<TruthTelemetry> getTruths() { return truths; }
    public void setTruths(List<TruthTelemetry> truths) { this.truths = truths; }

    public static class CountValue {
        private String status;
        private Integer value;
        private String unit;
        private String source;

        public CountValue() { }

        public CountValue(String status, Integer value, String unit, String source) {
            this.status = status;
            this.value = value;
            this.unit = unit;
            this.source = source;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Integer getValue() { return value; }
        public void setValue(Integer value) { this.value = value; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    public static class TruthTelemetry {
        private NormalizedVariantKey originalIdentity;
        private NormalizedVariantKey normalizedIdentity;
        private Map<String, StageValue> stages = new LinkedHashMap<String, StageValue>();
        private String firstLossStage;
        private String failureCode;

        public NormalizedVariantKey getOriginalIdentity() { return originalIdentity; }
        public void setOriginalIdentity(NormalizedVariantKey originalIdentity) { this.originalIdentity = originalIdentity; }
        public NormalizedVariantKey getNormalizedIdentity() { return normalizedIdentity; }
        public void setNormalizedIdentity(NormalizedVariantKey normalizedIdentity) { this.normalizedIdentity = normalizedIdentity; }
        public Map<String, StageValue> getStages() { return stages; }
        public void setStages(Map<String, StageValue> stages) { this.stages = stages; }
        public String getFirstLossStage() { return firstLossStage; }
        public void setFirstLossStage(String firstLossStage) { this.firstLossStage = firstLossStage; }
        public String getFailureCode() { return failureCode; }
        public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    }

    public static class StageValue {
        private String status;
        private String reason;

        public StageValue() { }

        public StageValue(String status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
