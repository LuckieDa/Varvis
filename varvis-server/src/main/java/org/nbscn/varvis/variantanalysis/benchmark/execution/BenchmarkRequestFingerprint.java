package org.nbscn.varvis.variantanalysis.benchmark.execution;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatabaseAttestation;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkExecutionProvenance;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BenchmarkRequestFingerprint {

    private BenchmarkRequestFingerprint() {
    }

    public static String truthSet(List<BenchmarkRealCaseRequest.TruthVariant> truths) {
        List<String> identities = new ArrayList<String>();
        if (truths != null) {
            for (BenchmarkRealCaseRequest.TruthVariant truth : truths) {
                NormalizedVariantKey key = truth == null ? null : truth.getCanonicalIdentity();
                identities.add(key == null ? "MISSING" : key.canonicalId());
            }
        }
        Collections.sort(identities);
        return sha256(join(identities));
    }

    public static String executionProvenance(BenchmarkExecutionProvenance provenance) {
        if (provenance == null) {
            return null;
        }
        return sha256(join(
                provenance.getSchemaVersion(),
                provenance.getApplicationSourceCommit(),
                provenance.getOrchestrationSourceCommit(),
                provenance.getApplicationArtifactSha256(),
                provenance.getConfigurationFingerprint(),
                provenance.getWorkingTreeState()
        ));
    }

    public static String request(BenchmarkRealCaseRequest value) {
        return sha256(join(
                value.getSchemaVersion(), value.getRunId(), value.getDatasetId(),
                value.getDatasetVersion(), value.getCaseId(), value.getGroupId(),
                value.getPhenopacketSha256(), value.getInputVcfSha256(),
                value.getTruthSetFingerprint(), value.getExecutionProvenanceFingerprint(),
                value.getConfigurationFingerprint(), value.getAttemptId()
        ));
    }

    public static String databaseAttestation(String requestFingerprint,
                                             String analysisReference,
                                             String jobStatus,
                                             int persistedCount,
                                             int rankedCount) {
        return sha256(join(requestFingerprint, analysisReference, jobStatus,
                String.valueOf(persistedCount), String.valueOf(rankedCount)));
    }

    public static boolean matches(BenchmarkDatabaseAttestation attestation, String requestFingerprint) {
        return attestation != null && attestation.getPersistedInterpretationCount() != null
                && attestation.getRankedInterpretationCount() != null
                && databaseAttestation(requestFingerprint, attestation.getAnalysisReference(),
                attestation.getJobStatus(), attestation.getPersistedInterpretationCount(),
                attestation.getRankedInterpretationCount()).equals(attestation.getAttestationFingerprint());
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static String join(String... values) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, values);
        return join(list);
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(value == null ? "" : value);
        }
        return result.toString();
    }
}
