package org.nbscn.varvis.variantanalysis.benchmark.selection;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"schemaVersion", "datasetId", "purpose", "caseIds", "phenopacketSha256", "truthCanonicalIdentities"})
public class SmokeLockedTestExclusionRegistry {
    private String schemaVersion = "benchmark-smoke-exclusion-registry-v1";
    private String datasetId;
    private String purpose = "EXCLUDE_DEVELOPMENT_SMOKE_FROM_LOCKED_TEST";
    private List<String> caseIds = new ArrayList<String>();
    private List<String> phenopacketSha256 = new ArrayList<String>();
    private List<String> truthCanonicalIdentities = new ArrayList<String>();

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String value) { datasetId = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public List<String> getCaseIds() { return caseIds; }
    public void setCaseIds(List<String> value) { caseIds = value; }
    public List<String> getPhenopacketSha256() { return phenopacketSha256; }
    public void setPhenopacketSha256(List<String> value) { phenopacketSha256 = value; }
    public List<String> getTruthCanonicalIdentities() { return truthCanonicalIdentities; }
    public void setTruthCanonicalIdentities(List<String> value) { truthCanonicalIdentities = value; }

    public boolean excludes(PublicBenchmarkSmokeCandidate candidate) {
        if (candidate == null) return false;
        if (phenopacketSha256.contains(candidate.getSourceSha256())) return true;
        if (candidate.getImported() != null && candidate.getImported().getCaseData() != null) {
            if (caseIds.contains(candidate.getImported().getCaseData().getCaseId())) return true;
            BenchmarkDatasetCaseTruth truth = new BenchmarkDatasetCaseTruth(candidate);
            for (String identity : truth.identities) if (truthCanonicalIdentities.contains(identity)) return true;
        }
        return false;
    }

    private static class BenchmarkDatasetCaseTruth {
        private final List<String> identities = new ArrayList<String>();
        BenchmarkDatasetCaseTruth(PublicBenchmarkSmokeCandidate candidate) {
            if (candidate.getImported().getCaseData().getDatasetCase() != null) {
                candidate.getImported().getCaseData().getDatasetCase().getTruthVariants().forEach(truth -> {
                    if (truth != null && truth.getCanonicalIdentity() != null) identities.add(truth.getCanonicalIdentity().canonicalId());
                });
            }
        }
    }
}
