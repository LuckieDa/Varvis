package org.nbscn.varvis.variantanalysis.benchmark.execution;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local-only request for one public benchmark case/group execution. */
@JsonPropertyOrder({
        "schemaVersion", "runId", "datasetId", "datasetVersion", "caseId", "groupId", "resultId",
        "attemptId", "requestFingerprint", "phenopacketSha256", "truthSetFingerprint",
        "executionProvenanceFingerprint", "configurationFingerprint",
        "inputVcf", "inputVcfSha256", "sampleId", "assembly", "analysisMode",
        "hpoTerms", "hpoProvenance", "truthVariants", "runManifest", "timeoutSeconds",
        "expectedDatabaseCatalog", "outputDirectory"
})
public class BenchmarkRealCaseRequest {

    public static final String LEGACY_SCHEMA_VERSION = "benchmark-real-case-request-v1";
    public static final String SCHEMA_VERSION = "benchmark-real-case-request-v2";

    private String schemaVersion = SCHEMA_VERSION;
    private String runId;
    private String datasetId;
    private String datasetVersion;
    private String caseId;
    private String groupId;
    private String resultId;
    private String attemptId;
    private String requestFingerprint;
    private String phenopacketSha256;
    private String truthSetFingerprint;
    private String executionProvenanceFingerprint;
    private String configurationFingerprint;
    private String inputVcf;
    private String inputVcfSha256;
    private String sampleId;
    private String assembly;
    private String analysisMode = "WES";
    private List<String> hpoTerms = new ArrayList<String>();
    private List<String> hpoProvenance = new ArrayList<String>();
    private List<TruthVariant> truthVariants = new ArrayList<TruthVariant>();
    private String runManifest;
    private int timeoutSeconds = 3600;
    private String expectedDatabaseCatalog;
    private String outputDirectory;

    public void validate() {
        require(SCHEMA_VERSION.equals(schemaVersion), "UNSUPPORTED_REQUEST_SCHEMA");
        requireIdentifier(runId, "runId");
        requireIdentifier(datasetId, "datasetId");
        requireText(datasetVersion, "datasetVersion");
        requireIdentifier(caseId, "caseId");
        requireIdentifier(groupId, "groupId");
        requireIdentifier(resultId, "resultId");
        requireIdentifier(attemptId, "attemptId");
        requireChecksum(requestFingerprint, "INVALID_REQUEST_FINGERPRINT");
        requireChecksum(phenopacketSha256, "INVALID_PHENOPACKET_SHA256");
        requireChecksum(truthSetFingerprint, "INVALID_TRUTH_SET_FINGERPRINT");
        requireChecksum(executionProvenanceFingerprint, "INVALID_EXECUTION_PROVENANCE_FINGERPRINT");
        requireText(configurationFingerprint, "configurationFingerprint");
        requireText(inputVcf, "inputVcf");
        require(inputVcfSha256 != null && inputVcfSha256.matches("[0-9a-f]{64}"),
                "INVALID_INPUT_SHA256");
        requireText(sampleId, "sampleId");
        require("GRCh38".equals(assembly), "UNSUPPORTED_ASSEMBLY");
        require("WES".equals(analysisMode), "UNSUPPORTED_ANALYSIS_MODE");
        require(timeoutSeconds > 0, "INVALID_TIMEOUT");
        require(expectedDatabaseCatalog != null
                        && expectedDatabaseCatalog.matches("(?i)[a-z0-9_]*benchmark[a-z0-9_]*"),
                "BENCHMARK_DATABASE_REQUIRED");
        requireText(outputDirectory, "outputDirectory");
        requireText(runManifest, "runManifest");
        require(truthVariants != null && !truthVariants.isEmpty(), "TRUTH_VARIANT_REQUIRED");
        for (String hpo : safe(hpoTerms)) {
            require(hpo != null && hpo.matches("HP:\\d{7}"), "INVALID_HPO_TERM");
        }
        for (TruthVariant truth : truthVariants) {
            if (truth == null) {
                throw new IllegalArgumentException("TRUTH_VARIANT_REQUIRED");
            }
            truth.validate(assembly);
        }
        require(truthSetFingerprint.equals(BenchmarkRequestFingerprint.truthSet(truthVariants)),
                "TRUTH_SET_FINGERPRINT_MISMATCH");
        require(requestFingerprint.equals(BenchmarkRequestFingerprint.request(this)),
                "REQUEST_FINGERPRINT_MISMATCH");
    }

    private static List<String> safe(List<String> values) {
        return values == null ? Collections.<String>emptyList() : values;
    }

    private static void requireIdentifier(String value, String field) {
        require(value != null && value.matches("[A-Za-z0-9._-]+"), "INVALID_IDENTIFIER: " + field);
    }

    private static void requireText(String value, String field) {
        require(value != null && !value.trim().isEmpty(), "REQUIRED_FIELD: " + field);
    }

    private static void requireChecksum(String value, String code) {
        require(value != null && value.matches("[0-9a-f]{64}"), code);
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new IllegalArgumentException(code);
        }
    }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String value) { datasetId = value; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String value) { datasetVersion = value; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String value) { caseId = value; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String value) { groupId = value; }
    public String getResultId() { return resultId; }
    public void setResultId(String value) { resultId = value; }
    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String value) { attemptId = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public String getPhenopacketSha256() { return phenopacketSha256; }
    public void setPhenopacketSha256(String value) { phenopacketSha256 = value; }
    public String getTruthSetFingerprint() { return truthSetFingerprint; }
    public void setTruthSetFingerprint(String value) { truthSetFingerprint = value; }
    public String getExecutionProvenanceFingerprint() { return executionProvenanceFingerprint; }
    public void setExecutionProvenanceFingerprint(String value) { executionProvenanceFingerprint = value; }
    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public void setConfigurationFingerprint(String value) { configurationFingerprint = value; }
    public String getInputVcf() { return inputVcf; }
    public void setInputVcf(String value) { inputVcf = value; }
    public String getInputVcfSha256() { return inputVcfSha256; }
    public void setInputVcfSha256(String value) { inputVcfSha256 = value; }
    public String getSampleId() { return sampleId; }
    public void setSampleId(String value) { sampleId = value; }
    public String getAssembly() { return assembly; }
    public void setAssembly(String value) { assembly = value; }
    public String getAnalysisMode() { return analysisMode; }
    public void setAnalysisMode(String value) { analysisMode = value; }
    public List<String> getHpoTerms() { return hpoTerms; }
    public void setHpoTerms(List<String> value) { hpoTerms = value; }
    public List<String> getHpoProvenance() { return hpoProvenance; }
    public void setHpoProvenance(List<String> value) { hpoProvenance = value; }
    public List<TruthVariant> getTruthVariants() { return truthVariants; }
    public void setTruthVariants(List<TruthVariant> value) { truthVariants = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
    public String getExpectedDatabaseCatalog() { return expectedDatabaseCatalog; }
    public void setExpectedDatabaseCatalog(String value) { expectedDatabaseCatalog = value; }
    public String getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(String value) { outputDirectory = value; }
    public String getRunManifest() { return runManifest; }
    public void setRunManifest(String value) { runManifest = value; }

    @JsonPropertyOrder({"gene", "canonicalIdentity"})
    public static class TruthVariant {
        private String gene;
        private NormalizedVariantKey canonicalIdentity;

        public void validate(String assembly) {
            requireText(gene, "truthVariants.gene");
            require(canonicalIdentity != null, "TRUTH_IDENTITY_REQUIRED");
            require(assembly.equals(canonicalIdentity.getGenomeAssembly()), "TRUTH_ASSEMBLY_MISMATCH");
            GenomeAssembly genomeAssembly = GenomeAssembly.require(assembly);
            NormalizedVariantKey canonical = NormalizedVariantKey.canonical(
                    genomeAssembly,
                    canonicalIdentity.getChromosome(),
                    canonicalIdentity.getPosition(),
                    canonicalIdentity.getReference(),
                    canonicalIdentity.getAlternate()
            );
            require(canonical.equals(canonicalIdentity), "TRUTH_IDENTITY_NOT_CANONICAL");
        }

        public String getGene() { return gene; }
        public void setGene(String value) { gene = value; }
        public NormalizedVariantKey getCanonicalIdentity() { return canonicalIdentity; }
        public void setCanonicalIdentity(NormalizedVariantKey value) { canonicalIdentity = value; }
    }
}
