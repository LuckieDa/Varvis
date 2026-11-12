package org.nbscn.varvis.variantanalysis.benchmark.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkCaseResultManifest;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.PersistedRankingCandidate;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisRankReader;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenchmarkRealCaseExecutorTest {

    @Test
    void validatesDedicatedRequestAndRejectsWrongAssemblyOrDatabase() {
        BenchmarkRealCaseRequest request = request();
        request.validate();
        request.setAnalysisMode("SINGLETON");
        assertThrows(IllegalArgumentException.class, request::validate);
        request = request();
        request.setExpectedDatabaseCatalog("Varvis");
        assertThrows(IllegalArgumentException.class, request::validate);
        request = request();
        request.getTruthVariants().get(0).setCanonicalIdentity(
                NormalizedVariantKey.canonical(GenomeAssembly.GRCh37, "1", 101, "A", "G"));
        assertThrows(IllegalArgumentException.class, request::validate);
    }

    @Test
    void roundTripsFormalWesRequestFieldsForEveryHpoGroup() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        for (String group : Arrays.asList("G0", "G1", "G2")) {
            BenchmarkRealCaseRequest original = request();
            original.setGroupId(group);
            original.setSampleId("SYNTHETIC_SAMPLE");
            original.setInputVcf("synthetic-grch38.vcf");
            original.setHpoTerms("G0".equals(group)
                    ? Collections.<String>emptyList()
                    : Collections.singletonList("HP:0001250"));
            original.setHpoProvenance("G0".equals(group)
                    ? Collections.<String>emptyList()
                    : Collections.singletonList("synthetic provenance"));
            bind(original);

            String json = objectMapper.writeValueAsString(original);
            BenchmarkRealCaseRequest restored = objectMapper.readValue(json, BenchmarkRealCaseRequest.class);
            restored.validate();

            assertEquals("WES", restored.getAnalysisMode());
            assertEquals("GRCh38", restored.getAssembly());
            assertEquals("SYNTHETIC_SAMPLE", restored.getSampleId());
            assertEquals("synthetic-grch38.vcf", restored.getInputVcf());
            assertEquals(group, restored.getGroupId());
            assertEquals(original.getHpoTerms(), restored.getHpoTerms());
            assertEquals(original.getHpoProvenance(), restored.getHpoProvenance());
            assertEquals(original.getTruthVariants().get(0).getCanonicalIdentity(),
                    restored.getTruthVariants().get(0).getCanonicalIdentity());
        }
    }

    @Test
    void buildsOneBasedPersistedRankWithoutCsvOrVepCaps() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        NormalizedVariantKey truth = NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "1", 101, "A", "G");
        VariantInterpretation truthInterpretation = variant("truth", truth, 9.0);
        truthInterpretation.addEvidence(Collections.singletonList(
                EvidenceItem.unavailable("CLINVAR", "test-v1", "CLINICAL", "synthetic unavailable")));
        repository.replaceInterpretations("analysis-1", Arrays.asList(
                variant("v1", NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "1", 100, "C", "T"), 10.0),
                truthInterpretation
        ));
        BenchmarkRealCaseExecutor executor = new BenchmarkRealCaseExecutor(
                null, null, repository, null,
                Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC));
        BenchmarkCaseResultManifest result = executor.buildResult(
                request(), "analysis-1", new VariantAnalysisRankReader(repository, 1));
        assertEquals("COMPLETED", result.getCaseExecutionStatus());
        assertEquals(RankStatus.RANKED, result.getTruthMatches().get(0).getRankStatus());
        assertEquals(Integer.valueOf(2), result.getTruthMatches().get(0).getRank());
        assertEquals(Integer.valueOf(2), result.getTruthMatches().get(0).getRankingUniverseSize());
        assertEquals("UNAVAILABLE", result.getProviderStatuses().get("CLINVAR"));
        assertTrue(result.getFailureNote() == null);
    }

    @Test
    void preservesStructuredNonRankedStatusAndNullRank() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        repository.replaceInterpretations("analysis-2", Collections.singletonList(
                variant("other", NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "2", 202, "C", "T"), 1.0)
        ));
        BenchmarkRealCaseExecutor executor = new BenchmarkRealCaseExecutor(
                null, null, repository, null, Clock.systemUTC());
        BenchmarkCaseResultManifest result = executor.buildResult(
                request(), "analysis-2", new VariantAnalysisRankReader(repository, 1));
        assertEquals("COMPLETED_WITH_FAILURES", result.getCaseExecutionStatus());
        assertEquals(RankStatus.NOT_IN_RANKING_UNIVERSE, result.getTruthMatches().get(0).getRankStatus());
        assertEquals(null, result.getTruthMatches().get(0).getRank());
        assertFalse(result.getStageRetention().isEmpty());
    }

    @Test
    void rejectsRankedCandidateWithoutReadablePersistedInterpretation() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        NormalizedVariantKey truth = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "1", 101, "A", "G");
        when(repository.countInterpretations(eq("analysis-3"), eq(VariantInterpretationView.ALL)))
                .thenReturn(1);
        when(repository.findRankingPage("analysis-3", 0, 1)).thenReturn(Collections.singletonList(
                new PersistedRankingCandidate("truth", truth, 10.0, 0)));
        when(repository.findInterpretation("analysis-3", "truth")).thenReturn(Optional.<VariantInterpretation>empty());

        BenchmarkRealCaseExecutor executor = new BenchmarkRealCaseExecutor(
                null, null, repository, null, Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> executor.buildResult(
                request(), "analysis-3", new VariantAnalysisRankReader(repository, 1)));
    }

    private BenchmarkRealCaseRequest request() {
        BenchmarkRealCaseRequest request = new BenchmarkRealCaseRequest();
        request.setRunId("run-1");
        request.setDatasetId("dataset-1");
        request.setDatasetVersion("1");
        request.setCaseId("case-1");
        request.setGroupId("G0");
        request.setResultId("case-1-G0");
        request.setAttemptId("attempt-1");
        request.setPhenopacketSha256(repeat('b', 64));
        request.setExecutionProvenanceFingerprint(repeat('c', 64));
        request.setConfigurationFingerprint("synthetic-config-v1");
        request.setInputVcf("ignored.vcf.gz");
        request.setInputVcfSha256(repeat('a', 64));
        request.setSampleId("NA12878");
        request.setAssembly("GRCh38");
        request.setAnalysisMode("WES");
        request.setHpoTerms(Collections.<String>emptyList());
        request.setHpoProvenance(Collections.<String>emptyList());
        BenchmarkRealCaseRequest.TruthVariant truth = new BenchmarkRealCaseRequest.TruthVariant();
        truth.setGene("GENE1");
        truth.setCanonicalIdentity(NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "1", 101, "A", "G"));
        request.setTruthVariants(Collections.singletonList(truth));
        request.setTimeoutSeconds(60);
        request.setExpectedDatabaseCatalog("varvis_benchmark_stage2b4");
        request.setOutputDirectory("ignored-output");
        request.setRunManifest("run-manifest.json");
        bind(request);
        return request;
    }

    @Test
    void rejectsIncompleteArtifactSetBeforeRetryingTheSameResultId(@TempDir Path directory)
            throws Exception {
        BenchmarkRealCaseExecutor executor = new BenchmarkRealCaseExecutor(
                null, null, mock(VariantAnalysisJobRepository.class), null, Clock.systemUTC());
        executor.requireNoIncompleteArtifactSet(directory, "result-1");

        Path telemetry = directory.resolve("benchmark-telemetry-result-1.json");
        Files.write(telemetry, Collections.singletonList("synthetic orphan"));
        IllegalStateException orphan = assertThrows(IllegalStateException.class,
                () -> executor.requireNoIncompleteArtifactSet(directory, "result-1"));
        assertTrue(orphan.getMessage().contains(
                "BENCHMARK_INCOMPLETE_ARTIFACT_SET_EXISTS_USE_NEW_RESULT_ID"));

        Files.delete(telemetry);
        Files.write(directory.resolve("benchmark-telemetry-result-1.json.123.tmp"),
                Collections.singletonList("synthetic partial"));
        assertThrows(IllegalStateException.class,
                () -> executor.requireNoIncompleteArtifactSet(directory, "result-1"));
    }

    private void bind(BenchmarkRealCaseRequest request) {
        request.setTruthSetFingerprint(BenchmarkRequestFingerprint.truthSet(request.getTruthVariants()));
        request.setRequestFingerprint(BenchmarkRequestFingerprint.request(request));
    }

    private VariantInterpretation variant(String id, NormalizedVariantKey key, Double score) {
        return new VariantInterpretation(
                id,
                new VariantQcObservation(key, "NA12878", "0/1", 30, 99, null, 0.5,
                        "PASS", "SNV", null),
                Collections.emptyList(), Collections.emptyList(), "CANDIDATE", score,
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList()
        );
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
