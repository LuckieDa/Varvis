package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({
        "schemaVersion", "policyVersion", "datasetId", "datasetVersion", "createdAt",
        "status", "pilotScope", "officialCaseMetric", "auxiliaryCompleteTruthSetMetric",
        "assessments"
})
public class BenchmarkTruthPolicyManifest {

    private String schemaVersion = BenchmarkManifestSchema.TRUTH_POLICY;
    private String policyVersion = PublicPilotTruthPolicy.POLICY_VERSION;
    private String datasetId;
    private String datasetVersion;
    private String createdAt;
    private String status;
    private String pilotScope = "GRCH38_SINGLETON_SNV_SMALL_INDEL";
    private String officialCaseMetric = "BEST_ELIGIBLE_TRUTH_RANK";
    private String auxiliaryCompleteTruthSetMetric = "ALL_REQUIRED_TRUTHS_AT_K_DIAGNOSTIC_ONLY";
    private List<BenchmarkTruthPolicyAssessment> assessments =
            new ArrayList<BenchmarkTruthPolicyAssessment>();

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPilotScope() { return pilotScope; }
    public void setPilotScope(String pilotScope) { this.pilotScope = pilotScope; }
    public String getOfficialCaseMetric() { return officialCaseMetric; }
    public void setOfficialCaseMetric(String officialCaseMetric) { this.officialCaseMetric = officialCaseMetric; }
    public String getAuxiliaryCompleteTruthSetMetric() { return auxiliaryCompleteTruthSetMetric; }
    public void setAuxiliaryCompleteTruthSetMetric(String value) { auxiliaryCompleteTruthSetMetric = value; }
    public List<BenchmarkTruthPolicyAssessment> getAssessments() { return assessments; }
    public void setAssessments(List<BenchmarkTruthPolicyAssessment> assessments) { this.assessments = assessments; }
}
