package org.nbscn.varvis.variantanalysis.benchmark.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthMatch;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;
import org.nbscn.varvis.variantanalysis.service.PreRankingTranscriptAnnotationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkTelemetryCollectorTest {

    @Test
    void recordsRetainedReleasedRescuedDisplacedVepAndFormalRank() {
        NormalizedVariantKey rescued = key(100);
        NormalizedVariantKey displaced = key(200);
        BenchmarkTelemetryCollector collector = collector(rescued, displaced);
        collector.observeBasicInterpretations(Arrays.asList(variant(rescued), variant(displaced)));

        Map<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation> gate =
                new LinkedHashMap<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation>();
        gate.put(rescued, new BenchmarkTelemetryCollector.GateObservation(
                true, true, "candidate", null, "RESCUED", "counterfactual"));
        gate.put(displaced, new BenchmarkTelemetryCollector.GateObservation(
                true, false, "hard cap", null, "DISPLACED", "counterfactual"));
        collector.observeCandidateGate(2, 1, 1, 1, 1, 1, gate);
        collector.observeVepSelection(Arrays.asList(variant(rescued), variant(displaced)),
                Collections.singletonList(variant(rescued)), 1);
        collector.observeVepSummary(new PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary(
                "AVAILABLE", 1, 1, 1, 0, 0, 1, 1, 1L, Collections.<String>emptyList()));
        // The processor-side list may still include summary-only rows; formal rank calibrates
        // truth persistence against the actual repository universe.
        collector.observePersistence(Arrays.asList(variant(rescued), variant(displaced)), 1);

        BenchmarkTruthMatch ranked = match(rescued, RankStatus.RANKED);
        BenchmarkTruthMatch notIn = match(displaced, RankStatus.NOT_IN_RANKING_UNIVERSE);
        collector.observeRanking(Arrays.asList(ranked, notIn), 1);
        BenchmarkAnalysisTelemetryManifest manifest = collector.snapshot("analysis-1");

        assertEquals(Integer.valueOf(2), manifest.getCounts().get("cheapGateEligible").getValue());
        assertEquals(Integer.valueOf(1), manifest.getCounts().get("releasedByHardCap").getValue());
        assertEquals(Integer.valueOf(1), manifest.getCounts().get("phenotypeRescued").getValue());
        assertEquals(Integer.valueOf(1), manifest.getCounts().get("phenotypeDisplaced").getValue());
        assertEquals("RESCUED", manifest.getTruths().get(0).getStages()
                .get("PHENOTYPE_DISPOSITION").getStatus());
        assertEquals("RETAINED_AFTER_HARD_CAP", manifest.getTruths().get(1).getFirstLossStage());
        assertNull(manifest.getTruths().get(1).getFailureCode());
        assertEquals("NOT_SELECTED", manifest.getTruths().get(1).getStages().get("VEP_SELECTED").getStatus());
        assertEquals("RANKED", manifest.getTruths().get(0).getStages().get("RANKED_STATUS").getStatus());
        assertEquals("PRESENT", manifest.getTruths().get(0).getStages()
                .get("PERSISTED_INTERPRETATION").getStatus());
        assertEquals("ABSENT", manifest.getTruths().get(1).getStages()
                .get("PERSISTED_INTERPRETATION").getStatus());
    }

    @Test
    void rejectsBrokenCandidateCountInvariant() {
        BenchmarkTelemetryCollector collector = collector(key(100));
        assertThrows(IllegalStateException.class, () -> collector.observeCandidateGate(
                2, 1, 0, 0, 0, 1,
                Collections.<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation>emptyMap()));
    }

    @Test
    void writesAtomicallyWithoutSilentOverwrite(@TempDir Path directory) throws Exception {
        BenchmarkTelemetryCollector collector = collector(key(100));
        completeTelemetry(collector);
        BenchmarkAnalysisTelemetryManifest manifest = collector.snapshot("analysis-1");
        manifest.setCreatedAt("2026-08-27T00:00:00Z");
        BenchmarkTelemetryWriter.WriteResult firstWrite = BenchmarkTelemetryWriter.writeArtifact(
                directory, "result-1", manifest, new ObjectMapper());
        BenchmarkTelemetryArtifact artifact = firstWrite.getArtifact();
        Path target = directory.resolve(artifact.getLogicalFileName());
        assertTrue(firstWrite.isCreated());
        assertTrue(Files.isRegularFile(target));
        assertEquals(64, artifact.getSha256().length());
        assertFalse(Files.exists(directory.resolve(artifact.getLogicalFileName() + ".tmp")));
        byte[] original = Files.readAllBytes(target);
        String telemetryText = new String(original, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(telemetryText.contains("\r"));
        assertTrue(telemetryText.endsWith("\n"));
        assertFalse(telemetryText.endsWith("\n\n"));
        BenchmarkTelemetryWriter.WriteResult repeatedWrite = BenchmarkTelemetryWriter.writeArtifact(
                directory, "result-1", manifest, new ObjectMapper());
        assertFalse(repeatedWrite.isCreated());
        assertEquals(artifact.getSha256(), repeatedWrite.getArtifact().getSha256());
        assertTrue(Arrays.equals(original, Files.readAllBytes(target)));

        manifest.setCreatedAt("2026-08-27T00:00:01Z");
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> BenchmarkTelemetryWriter.write(
                        directory, "result-1", manifest, new ObjectMapper()));
        assertTrue(conflict.getMessage().contains("BENCHMARK_TELEMETRY_ARTIFACT_CONFLICT"));
        assertTrue(Arrays.equals(original, Files.readAllBytes(target)));
    }

    @Test
    void snapshotsAtExplicitTerminalTimeForDeterministicRetry() {
        BenchmarkTelemetryCollector collector = collector(key(100));
        completeTelemetry(collector);
        Instant terminalTime = Instant.parse("2026-08-27T00:00:00Z");

        BenchmarkAnalysisTelemetryManifest manifest = collector.snapshot(
                "analysis-1", terminalTime);

        assertEquals(terminalTime.toString(), manifest.getCreatedAt());
    }

    @Test
    void serializesConcurrentTelemetryWriters(@TempDir Path directory) throws Exception {
        BenchmarkTelemetryCollector collector = collector(key(100));
        completeTelemetry(collector);
        BenchmarkAnalysisTelemetryManifest manifest = collector.snapshot(
                "analysis-1", Instant.parse("2026-08-27T00:00:00Z"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BenchmarkTelemetryWriter.WriteResult> first = executor.submit(() -> {
                start.await();
                return BenchmarkTelemetryWriter.writeArtifact(
                        directory, "result-1", manifest, new ObjectMapper());
            });
            Future<BenchmarkTelemetryWriter.WriteResult> second = executor.submit(() -> {
                start.await();
                return BenchmarkTelemetryWriter.writeArtifact(
                        directory, "result-1", manifest, new ObjectMapper());
            });
            start.countDown();

            BenchmarkTelemetryWriter.WriteResult firstResult = first.get();
            BenchmarkTelemetryWriter.WriteResult secondResult = second.get();
            assertTrue(firstResult.isCreated() ^ secondResult.isCreated());
            assertEquals(firstResult.getArtifact().getSha256(),
                    secondResult.getArtifact().getSha256());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsIncompleteTelemetryBeforeWriting(@TempDir Path directory) {
        BenchmarkTelemetryCollector collector = collector(key(100));
        completeTelemetry(collector);
        BenchmarkAnalysisTelemetryManifest manifest = collector.snapshot("analysis-1");
        manifest.getCounts().remove("rankedInterpretations");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkTelemetryWriter.write(directory, "result-1", manifest, new ObjectMapper()));
        assertTrue(error.getMessage().contains("REQUIRED_COUNTS_MISSING"));
        assertFalse(Files.exists(directory.resolve("benchmark-telemetry-result-1.json")));
    }

    @Test
    void rejectsUnavailableCountWithSyntheticZero() {
        BenchmarkTelemetryCollector collector = collector(key(100));
        completeTelemetry(collector);
        BenchmarkAnalysisTelemetryManifest manifest = collector.snapshot("analysis-1");
        manifest.getCounts().get("rawVcfRecords").setStatus("UNAVAILABLE");
        manifest.getCounts().get("rawVcfRecords").setValue(Integer.valueOf(0));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkTelemetryValidator.requireValid(manifest));
    }

    private void completeTelemetry(BenchmarkTelemetryCollector collector) {
        NormalizedVariantKey truth = key(100);
        collector.observeNormalization(null, new org.nbscn.varvis.variantanalysis.domain.VcfQcResult(
                "SYNTHETIC_SAMPLE", null,
                Collections.singletonList(new VariantQcObservation(
                        truth, "SYNTHETIC", "0/1", 30, 99, null, 0.5, "PASS", "SNV", null)),
                Collections.<String>emptyList()));
        collector.observeBasicInterpretations(Collections.singletonList(variant(truth)));
        Map<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation> gate =
                new LinkedHashMap<NormalizedVariantKey, BenchmarkTelemetryCollector.GateObservation>();
        gate.put(truth, new BenchmarkTelemetryCollector.GateObservation(
                true, true, "candidate", null, "UNCHANGED", "counterfactual"));
        collector.observeCandidateGate(1, 1, 0, 0, 0, 20000, gate);
        collector.observeVepSelection(Collections.singletonList(variant(truth)),
                Collections.singletonList(variant(truth)), 8000);
        collector.observeVepSummary(new PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary(
                "AVAILABLE", 1, 1, 1, 0, 0, 1, 8000, 1L, Collections.<String>emptyList()));
        collector.observePersistence(Collections.singletonList(variant(truth)), 1);
        collector.observeRanking(Collections.singletonList(match(truth, RankStatus.RANKED)), 1);
    }

    private BenchmarkTelemetryCollector collector(NormalizedVariantKey... truths) {
        return new BenchmarkTelemetryCollector("attempt-1", repeat('a'), repeat('b'), Arrays.asList(truths));
    }

    private BenchmarkTruthMatch match(NormalizedVariantKey key, RankStatus status) {
        BenchmarkTruthMatch match = new BenchmarkTruthMatch();
        match.setTruthCanonicalIdentity(key);
        match.setRankStatus(status);
        match.setRankingUniverseSize(1);
        if (status == RankStatus.RANKED) match.setRank(1);
        return match;
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        return new VariantInterpretation(
                key.canonicalId(),
                new VariantQcObservation(key, "SYNTHETIC", "0/1", 30, 99, null, 0.5,
                        "PASS", "SNV", null),
                Collections.emptyList(), Collections.emptyList(), "CANDIDATE", 1.0,
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
    }

    private NormalizedVariantKey key(int position) {
        return NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "1", position, "A", "G");
    }

    private String repeat(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
