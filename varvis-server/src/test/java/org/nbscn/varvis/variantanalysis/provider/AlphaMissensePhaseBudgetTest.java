package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.tabix.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

class AlphaMissensePhaseBudgetTest {
    @TempDir Path work;
    private final NormalizedVariantKey first = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
    private final NormalizedVariantKey second = new NormalizedVariantKey("GRCh38", "1", 200, "A", "G");

    @Test void isolatedProviderPreservesExactScoresOrderAndCacheDoesNotSpendTransport() throws Exception {
        var settings = settings();
        var budget = budget("rank");
        var old = new AlphaMissenseProvider(settings);
        var isolated = new AlphaMissenseProvider(settings, null, budget);
        List<Map<String, Object>> previous = rank(old, settings);
        List<Map<String, Object>> current = rank(isolated, settings);
        assertEquals(previous, current);
        assertEquals(current, rank(isolated, settings));
        assertEquals(Map.of(AlphaMissensePhaseBudget.LEGACY, 2), budget.snapshot().get("transportAttempts"));
        assertEquals(Map.of(AlphaMissensePhaseBudget.LEGACY, 2), budget.snapshot().get("legacyCacheHits"));
        // A second provider cannot obtain a fresh quota, even for the same task/allele.
        var other = new AlphaMissenseProvider(settings, null, budget);
        assertThrows(AlphaMissensePhaseBudget.Exceeded.class, () -> rank(other, settings));
        assertEquals(Map.of(AlphaMissensePhaseBudget.LEGACY, 2), budget.snapshot().get("transportAttempts"));
    }

    @Test void phaseCapsAreAtomicAndNeverBorrowedOrRefunded() throws Exception {
        var budget = budget("caps");
        for (String phase : List.of(AlphaMissensePhaseBudget.LEGACY, AlphaMissensePhaseBudget.RAW))
            for (var key : List.of(first, second)) budget.reserve(phase, key, work.resolve("synthetic-resource"));
        assertEquals(Map.of(AlphaMissensePhaseBudget.LEGACY, 2, AlphaMissensePhaseBudget.RAW, 2), budget.snapshot().get("transportAttempts"));
        assertThrows(AlphaMissensePhaseBudget.Exceeded.class, () -> budget.reserve(AlphaMissensePhaseBudget.RAW, first, work));
        assertEquals("STOPPED", budget.snapshot().get("status"));
        var phaseOnly = budget("no-borrow");
        phaseOnly.reserve(AlphaMissensePhaseBudget.LEGACY, first, work);
        phaseOnly.reserve(AlphaMissensePhaseBudget.LEGACY, second, work);
        assertThrows(AlphaMissensePhaseBudget.Exceeded.class, () -> phaseOnly.reserve(AlphaMissensePhaseBudget.LEGACY, first, work));
        assertEquals(Map.of(AlphaMissensePhaseBudget.LEGACY, 2), phaseOnly.snapshot().get("transportAttempts"));
    }

    @Test void identityAndInvalidExplicitPurposeCannotOpenResource() throws Exception {
        var settings = settings(); var invalidBudget = budget("invalid-purpose");
        var provider = new AlphaMissenseProvider(settings, null, invalidBudget);
        var mapper = new ObjectMapper();
        var result = provider.lookupRecords(mapper, first, mapper.createObjectNode(), "not-authorized", "SYNTHETIC");
        assertEquals("PREDICTION_EXPLICIT_PURPOSE_REQUIRED", result.path("reasonCode").asText());
        assertEquals(Map.of(), invalidBudget.snapshot().get("transportAttempts"));
        assertThrows(AlphaMissensePhaseBudget.Exceeded.class, () -> provider.lookupIdentities(candidate(first, .9)));
        assertEquals(Map.of(), invalidBudget.snapshot().get("transportAttempts"));
    }
    @Test void rawCaptureIsWriteOnceAndDoesNotBecomeAReusableScoreCache() throws Exception {
        var budget = budget("capture");
        var raw = new ObjectMapper().createObjectNode().put("rawScore", "0.1558").put("observedAt", "synthetic-fixture-time");
        budget.reserve(AlphaMissensePhaseBudget.RAW, first, work);
        budget.captureRaw(first, raw);
        try (var paths = Files.list(work)) {
            Path file = paths.filter(p -> p.getFileName().toString().startsWith("am-raw-")).findFirst().orElseThrow();
            assertEquals(raw, new ObjectMapper().readTree(file.toFile()));
            assertThrows(AlphaMissensePhaseBudget.Exceeded.class, () -> budget.captureRaw(first, raw.put("rawScore", "changed")));
            assertEquals("0.1558", new ObjectMapper().readTree(file.toFile()).path("rawScore").asText());
            assertEquals(0, budget.snapshot().get("trustedRawReuse"));
        }
    }

    private AlphaMissensePhaseBudget budget(String name) {
        return new AlphaMissensePhaseBudget("synthetic-task", "SYNTHETIC_RESOURCE_NOT_REAL_AM", Set.of(first, second), work.resolve(name + ".json"));
    }
    private List<Map<String, Object>> rank(AlphaMissenseProvider provider, VariantAnalysisProperties properties) {
        var service = new VariantInterpretationServiceImpl((path, observations) -> List.of(), List.of(provider), new TransparentVariantRankingService(properties));
        var variants = new ArrayList<>(List.of(candidate(first, .99), candidate(second, .80)));
        var ranked = service.enrichEvidenceOnly(variants, new AnalysisContext("synthetic-budget-analysis", GenomeAssembly.GRCh38, AnalysisMode.WES));
        return ranked.stream().map(v -> Map.<String, Object>of("candidateId", v.getCandidateId(), "score", v.getRankingScore(), "breakdown", v.getScoreBreakdown())).toList();
    }
    private VariantInterpretation candidate(NormalizedVariantKey key, double engine) {
        var candidate = new VariantInterpretation(key.canonicalId(), new VariantQcObservation(key, "synthetic", "0/1", 40, 99,
                "20,20", .5, "PASS", "SNV", key.canonicalId()), List.of(new TranscriptConsequence(null, null, null, null, null, "missense_variant")), List.of());
        candidate.applyRanking("REVIEW", engine, Map.of("exomiserRawScore", engine), List.of(), List.of()); return candidate;
    }
    private VariantAnalysisProperties settings() throws Exception {
        Path file = work.resolve("synthetic-am.tsv.gz");
        var index = new TabixIndexCreator(new TabixFormat(0, 1, 2, 2, '#', 0));
        try (var output = new BlockCompressedOutputStream(file.toFile())) {
            for (var key : List.of(first, second)) {
                index.addFeature(new htsjdk.tribble.Feature() {
                    public String getContig() { return "1"; }
                    public int getStart() { return key.getPosition(); }
                    public int getEnd() { return key.getPosition(); }
                }, output.getFilePointer());
                String row = "1\t" + key.getPosition() + "\tA\tG\thg38\tSYNTHETIC\tENST000001.1\tA1G\t"
                        + (key.equals(first) ? "0.1000" : "0.9990") + "\tsynthetic\n";
                output.write(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            index.finalizeIndex(output.getFilePointer()).writeBasedOnFeaturePath(file);
        }
        var properties = new VariantAnalysisProperties();
        properties.getAlphaMissense().setEnabled(true); properties.getAlphaMissense().setGrch38File(file.toString());
        return properties;
    }
}
