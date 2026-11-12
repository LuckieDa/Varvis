package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;
import org.nbscn.varvis.variantanalysis.benchmark.execution.BenchmarkRequestFingerprint;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkAnalysisTelemetryManifest;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryArtifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkManifestContractTest {

    private final BenchmarkManifestCodec codec = new BenchmarkManifestCodec();

    @Test
    void allThreeSchemasRoundTripWithStableJson() throws Exception {
        BenchmarkDatasetManifest dataset = codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
        BenchmarkRunManifest run = codec.readRunJson(resource("run-manifest.synthetic.json"));
        BenchmarkCaseResultManifest result = codec.readCaseResultJson(resource("case-result-ranked.synthetic.json"));

        assertStable(codec.toJson(dataset), codec.toJson(codec.readDatasetJson(codec.toJson(dataset))));
        assertStable(codec.toJson(run), codec.toJson(codec.readRunJson(codec.toJson(run))));
        assertStable(codec.toJson(result), codec.toJson(codec.readCaseResultJson(codec.toJson(result))));
        assertEquals(2, dataset.getCases().get(1).getTruthVariants().size());
        assertEquals(6001, result.getTruthMatches().get(0).getRank());
    }

    @Test
    void unsupportedSchemaVersionIsExplicit() throws Exception {
        String json = resource("dataset-manifest.synthetic.json").replace(
                "benchmark-dataset-manifest-v1", "benchmark-dataset-manifest-v99");
        BenchmarkManifestValidationException exception = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.readDatasetJson(json)
        );
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", exception.getErrors().get(0).getCode());
    }

    @Test
    void missingRequiredFieldIsStructured() throws Exception {
        BenchmarkDatasetManifest dataset = codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
        dataset.setDatasetId(null);
        BenchmarkManifestValidationException exception = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(dataset)
        );
        assertEquals("REQUIRED_FIELD", exception.getErrors().get(0).getCode());
        assertEquals("datasetId", exception.getErrors().get(0).getPath());
    }

    @Test
    void invalidChecksumAndUnsafePathsAreRejected() throws Exception {
        BenchmarkDatasetManifest dataset = codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
        dataset.getBackgroundResources().get(0).setChecksumValue("not-a-sha256");
        final BenchmarkDatasetManifest invalidChecksumDataset = dataset;
        BenchmarkManifestValidationException checksum = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(invalidChecksumDataset)
        );
        assertTrue(checksum.getErrors().stream().anyMatch(error -> "INVALID_CHECKSUM".equals(error.getCode())));

        dataset = codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
        dataset.getBackgroundResources().get(0).setLocalRelativePath("C:/secret/resource.vcf.gz");
        final BenchmarkDatasetManifest unsafePathDataset = dataset;
        BenchmarkManifestValidationException path = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(unsafePathDataset)
        );
        assertTrue(path.getErrors().stream().anyMatch(error -> "UNSAFE_PATH".equals(error.getCode())));

        dataset = codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
        dataset.getBackgroundResources().get(0).setLocalRelativePath("../resource.vcf.gz");
        final BenchmarkDatasetManifest traversalDataset = dataset;
        BenchmarkManifestValidationException traversal = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(traversalDataset)
        );
        assertTrue(traversal.getErrors().stream().anyMatch(error -> "UNSAFE_PATH".equals(error.getCode())));
    }

    @Test
    void datasetCaseIdsMustBeUnique() throws Exception {
        BenchmarkDatasetManifest dataset = codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
        dataset.getCases().get(1).setCaseId(dataset.getCases().get(0).getCaseId());
        BenchmarkManifestValidationException exception = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(dataset)
        );
        assertTrue(exception.getErrors().stream().anyMatch(error -> "DUPLICATE_CASE_ID".equals(error.getCode())));
    }

    @Test
    void runGroupsMustPreserveG0G1G2AndSharedConfiguration() throws Exception {
        BenchmarkRunManifest run = codec.readRunJson(resource("run-manifest.synthetic.json"));
        run.getGroups().get(1).setConfigurationFingerprint("different-config");
        final BenchmarkRunManifest mismatchedRun = run;
        BenchmarkManifestValidationException configuration = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(mismatchedRun)
        );
        assertTrue(configuration.getErrors().stream().anyMatch(error -> "CONFIGURATION_MISMATCH".equals(error.getCode())));

        run = codec.readRunJson(resource("run-manifest.synthetic.json"));
        run.getGroups().get(0).setHpoTerms(Arrays.asList("HP:0000001"));
        final BenchmarkRunManifest invalidG0Run = run;
        BenchmarkManifestValidationException g0 = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(invalidG0Run)
        );
        assertTrue(g0.getErrors().stream().anyMatch(error -> "G0_HPO_NOT_EMPTY".equals(error.getCode())));

        run = codec.readRunJson(resource("run-manifest.synthetic.json"));
        run.getGroups().get(3).setCaseId(run.getGroups().get(0).getCaseId());
        run.getGroups().get(3).setGroupId(run.getGroups().get(0).getGroupId());
        final BenchmarkRunManifest duplicateGroupRun = run;
        BenchmarkManifestValidationException duplicate = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(duplicateGroupRun)
        );
        assertTrue(duplicate.getErrors().stream().anyMatch(error -> "DUPLICATE_CASE_GROUP".equals(error.getCode())));
    }

    @Test
    void rankAboveFiveThousandIsValidAndUsesSharedRankStatus() throws Exception {
        BenchmarkCaseResultManifest result = codec.readCaseResultJson(resource("case-result-ranked.synthetic.json"));
        assertEquals(RankStatus.RANKED, result.getTruthMatches().get(0).getRankStatus());
        assertTrue(result.getTruthMatches().get(0).getRank() > 5000);
        assertEquals(RankStatus.class, result.getTruthMatches().get(0).getRankStatus().getDeclaringClass());
        assertEquals(10000, result.getTruthMatches().get(0).getRankingUniverseSize());
    }

    @Test
    void rankInvariantRejectsNullZeroNegativeAndOutOfRange() throws Exception {
        BenchmarkCaseResultManifest result = codec.readCaseResultJson(resource("case-result-ranked.synthetic.json"));
        result.getTruthMatches().get(0).setRank(null);
        assertValidationCode(result, "RANK_REQUIRED");

        result = codec.readCaseResultJson(resource("case-result-ranked.synthetic.json"));
        result.getTruthMatches().get(0).setRank(0);
        assertValidationCode(result, "RANK_REQUIRED");

        result = codec.readCaseResultJson(resource("case-result-ranked.synthetic.json"));
        result.getTruthMatches().get(0).setRank(-1);
        assertValidationCode(result, "RANK_REQUIRED");

        result = codec.readCaseResultJson(resource("case-result-ranked.synthetic.json"));
        result.getTruthMatches().get(0).setRank(10001);
        assertValidationCode(result, "RANK_OUT_OF_RANGE");
    }

    @Test
    void nonRankedStatusesRequireNullRank() throws Exception {
        BenchmarkCaseResultManifest result = codec.readCaseResultJson(
                resource("case-result-not-in-universe.synthetic.json"));
        result.getTruthMatches().get(0).setRank(1);
        assertValidationCode(result, "RANK_MUST_BE_NULL");
        result = codec.readCaseResultJson(resource("case-result-unavailable.synthetic.json"));
        assertEquals(RankStatus.RANK_UNAVAILABLE, result.getTruthMatches().get(0).getRankStatus());
        assertEquals(null, result.getTruthMatches().get(0).getRank());
    }

    @Test
    void assemblyMismatchIsSafeAndDoesNotInventRank() throws Exception {
        BenchmarkCaseResultManifest result = codec.readCaseResultJson(
                resource("case-result-assembly-mismatch.synthetic.json"));
        BenchmarkTruthMatch match = result.getTruthMatches().get(0);
        assertEquals("ASSEMBLY_MISMATCH", match.getMatchStatus());
        assertEquals(RankStatus.RANK_UNAVAILABLE, match.getRankStatus());
        assertEquals(null, match.getRank());
    }

    @Test
    void failureCodesAreRestrictedToFrozenF0ToF11() throws Exception {
        BenchmarkCaseResultManifest result = codec.readCaseResultJson(resource("case-result-ranked.synthetic.json"));
        result.setPrimaryFailureCode("F99");
        assertValidationCode(result, "UNSUPPORTED_FAILURE_CODE");
    }

    @Test
    void completedManifestCannotBeSilentlyOverwritten(@TempDir Path directory) throws Exception {
        BenchmarkManifestWriter.writeRun(directory,
                codec.readRunJson(resource("run-manifest.synthetic.json")), codec);
        IOException exception = assertThrows(
                IOException.class,
                () -> BenchmarkManifestWriter.writeRun(
                        directory, codec.readRunJson(resource("run-manifest.synthetic.json")), codec)
        );
        assertTrue(exception.getMessage().contains("COMPLETED_MANIFEST_EXISTS"));
    }

    @Test
    void completedWithFailuresCaseResultCannotBeSilentlyOverwritten(@TempDir Path directory)
            throws Exception {
        BenchmarkCaseResultManifest result = caseResultV2();
        result.setCaseExecutionStatus("COMPLETED_WITH_FAILURES");
        result.setPrimaryFailureCode(BenchmarkFailureCode.F9);
        result.setFailureNote("Synthetic ranked truth is below the frozen smoke cutoff.");
        BenchmarkManifestWriter.writeCaseResult(directory, result, codec);

        IOException exception = assertThrows(IOException.class,
                () -> BenchmarkManifestWriter.writeCaseResult(directory, result, codec));

        assertTrue(exception.getMessage().contains("COMPLETED_MANIFEST_EXISTS"));
    }

    @Test
    void failedValidationDoesNotLeaveAManifestFile(@TempDir Path directory) throws Exception {
        BenchmarkDatasetManifest invalid = new BenchmarkDatasetManifest();
        assertThrows(BenchmarkManifestValidationException.class,
                () -> BenchmarkManifestWriter.writeDataset(directory, invalid, codec));
        assertFalse(Files.exists(directory.resolve("dataset-manifest.json")));
        assertFalse(Files.exists(directory.resolve("dataset-manifest.json.tmp")));
    }

    @Test
    void syntheticJsonDoesNotContainMachineOrSensitivePathFields() throws Exception {
        String dataset = resource("dataset-manifest.synthetic.json");
        String run = resource("run-manifest.synthetic.json");
        assertFalse(dataset.contains("D:\\"));
        assertFalse(dataset.contains("hostname"));
        assertFalse(run.contains("username"));
        assertFalse(run.contains("C:/"));
    }

    @Test
    void preparationProvenanceIsOptionalAndRoundTrips() throws Exception {
        BenchmarkDatasetManifest dataset = codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
        BenchmarkPreparationProvenance provenance = new BenchmarkPreparationProvenance();
        provenance.setPhenopacketSchemaVersion("2");
        provenance.setAdapterVersion("phenopacket-adapter-v1");
        provenance.setEligibilityStatus("ELIGIBLE");
        provenance.setSourceHpoFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        provenance.setTruthFingerprint("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        provenance.setExecutionMode("SYNTHETIC_FAKE");
        provenance.setValidationStatus("VALID");
        dataset.getCases().get(0).setPreparationProvenance(provenance);
        String json = codec.toJson(dataset);
        BenchmarkDatasetManifest decoded = codec.readDatasetJson(json);
        assertEquals("SYNTHETIC_FAKE", decoded.getCases().get(0).getPreparationProvenance().getExecutionMode());
    }

    @Test
    void runV2SeparatesApplicationAndOrchestrationProvenance() throws Exception {
        BenchmarkRunManifest run = runV2();
        String json = codec.toJson(run);
        BenchmarkRunManifest decoded = codec.readRunJson(json);

        assertEquals("RECORDED", decoded.getExecutionProvenanceStatus());
        assertEquals("1111111111111111111111111111111111111111",
                decoded.getExecutionProvenance().getApplicationSourceCommit());
        assertEquals("2222222222222222222222222222222222222222",
                decoded.getExecutionProvenance().getOrchestrationSourceCommit());
        assertEquals(json, codec.toJson(decoded));
    }

    @Test
    void runV2RejectsInvalidCommitChecksumAndConfigurationMismatch() throws Exception {
        BenchmarkRunManifest run = runV2();
        run.getExecutionProvenance().setApplicationSourceCommit("short");
        assertRunValidationCode(run, "INVALID_GIT_COMMIT");

        run = runV2();
        run.getExecutionProvenance().setApplicationArtifactSha256("ABC");
        assertRunValidationCode(run, "INVALID_CHECKSUM");

        run = runV2();
        run.getExecutionProvenance().setConfigurationFingerprint("different");
        assertRunValidationCode(run, "CONFIGURATION_MISMATCH");

        run = runV2();
        run.getExecutionProvenance().setWorkingTreeState("MODIFIED_FILES");
        assertRunValidationCode(run, "UNSUPPORTED_STATUS");
    }

    @Test
    void runV1HasExplicitLegacyUnknownFallbackAndV2FrozenOverwriteProtection(@TempDir Path directory)
            throws Exception {
        BenchmarkRunManifest legacy = codec.readRunJson(resource("run-manifest.synthetic.json"));
        assertEquals("LEGACY_UNKNOWN", legacy.getExecutionProvenanceStatus());

        BenchmarkRunManifest run = runV2();
        BenchmarkManifestWriter.writeRun(directory, run, codec);
        IOException exception = assertThrows(IOException.class,
                () -> BenchmarkManifestWriter.writeRun(directory, run, codec));
        assertTrue(exception.getMessage().contains("COMPLETED_MANIFEST_EXISTS"));
    }

    @Test
    void caseResultV2RequiresBoundRequestAndDatabaseAttestation() throws Exception {
        BenchmarkCaseResultManifest result = caseResultV2();
        String json = codec.toJson(result);
        BenchmarkCaseResultManifest restored = codec.readCaseResultJson(json);
        assertEquals("attempt-1", restored.getResultBinding().getAttemptId());
        assertTrue(BenchmarkRequestFingerprint.matches(
                restored.getDatabaseAttestation(), restored.getResultBinding().getRequestFingerprint()));

        result = caseResultV2();
        result.setResultBinding(null);
        assertValidationCode(result, "REQUIRED_FIELD");

        result = caseResultV2();
        result.getDatabaseAttestation().setJobStatus("MISSING");
        assertValidationCode(result, "DATABASE_JOB_NOT_READY");
    }

    private BenchmarkRunManifest runV2() throws Exception {
        BenchmarkRunManifest run = codec.readRunJson(resource("run-manifest.synthetic.json"));
        run.setSchemaVersion(BenchmarkManifestSchema.RUN_V2);
        run.setGitCommit(null);
        BenchmarkExecutionProvenance provenance = new BenchmarkExecutionProvenance();
        provenance.setApplicationSourceCommit("1111111111111111111111111111111111111111");
        provenance.setOrchestrationSourceCommit("2222222222222222222222222222222222222222");
        provenance.setApplicationArtifactSha256(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        provenance.setConfigurationFingerprint(run.getSharedConfigurationFingerprint());
        provenance.setWorkingTreeState("DIRTY");
        run.setExecutionProvenance(provenance);
        return run;
    }

    private BenchmarkCaseResultManifest caseResultV2() throws Exception {
        BenchmarkCaseResultManifest result = codec.readCaseResultJson(
                resource("case-result-ranked.synthetic.json"));
        result.setSchemaVersion(BenchmarkManifestSchema.CASE_RESULT_V2);
        BenchmarkResultBinding binding = new BenchmarkResultBinding();
        binding.setAttemptId("attempt-1");
        binding.setRequestFingerprint(repeat('a', 64));
        binding.setDatasetVersion("1");
        binding.setPhenopacketSha256(repeat('b', 64));
        binding.setInputVcfSha256(repeat('c', 64));
        binding.setTruthSetFingerprint(repeat('d', 64));
        binding.setExecutionProvenanceFingerprint(repeat('e', 64));
        binding.setConfigurationFingerprint("synthetic-config-v1");
        result.setResultBinding(binding);
        BenchmarkDatabaseAttestation attestation = new BenchmarkDatabaseAttestation();
        attestation.setAnalysisReference(result.getAnalysisReference());
        attestation.setJobStatus("READY");
        attestation.setPersistedInterpretationCount(10000);
        attestation.setRankedInterpretationCount(10000);
        attestation.setVerifiedAt("2026-07-17T00:00:00Z");
        attestation.setAttestationFingerprint(BenchmarkRequestFingerprint.databaseAttestation(
                binding.getRequestFingerprint(), result.getAnalysisReference(), "READY", 10000, 10000));
        result.setDatabaseAttestation(attestation);
        BenchmarkTelemetryArtifact telemetry = new BenchmarkTelemetryArtifact();
        telemetry.setSchemaVersion(BenchmarkAnalysisTelemetryManifest.SCHEMA_VERSION);
        telemetry.setLogicalFileName("benchmark-telemetry-case-result-ranked.json");
        telemetry.setSha256(repeat('f', 64));
        telemetry.setStatus("COMPLETED");
        result.setTelemetryArtifact(telemetry);
        return result;
    }

    private void assertRunValidationCode(BenchmarkRunManifest run, String code) {
        BenchmarkManifestValidationException exception = assertThrows(
                BenchmarkManifestValidationException.class, () -> codec.toJson(run));
        assertTrue(exception.getErrors().stream().anyMatch(error -> code.equals(error.getCode())),
                exception.getErrors().toString());
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private void assertValidationCode(BenchmarkCaseResultManifest result, String code) {
        BenchmarkManifestValidationException exception = assertThrows(
                BenchmarkManifestValidationException.class,
                () -> codec.toJson(result)
        );
        assertTrue(exception.getErrors().stream().anyMatch(error -> code.equals(error.getCode())),
                exception.getErrors().toString());
    }

    private void assertStable(String first, String second) {
        assertEquals(first, second);
        assertNotNull(first);
        assertTrue(first.endsWith("\n"));
    }

    private String resource(String name) throws IOException {
        String path = "/benchmark/" + name;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing synthetic benchmark fixture: " + path);
            }
            return new String(readAll(input), StandardCharsets.UTF_8);
        }
    }

    private byte[] readAll(InputStream input) throws IOException {
        List<byte[]> chunks = new ArrayList<byte[]>();
        int total = 0;
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            byte[] chunk = Arrays.copyOf(buffer, read);
            chunks.add(chunk);
            total += read;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }
}
