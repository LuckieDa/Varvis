package org.nbscn.varvis.variantanalysis.benchmark.execution;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkCaseResultManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestCodec;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthMatch;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkMetricsAggregatorTest {
    private final BenchmarkManifestCodec codec = new BenchmarkManifestCodec();

    @Test
    void aggregatesFullRanksUnavailableFailuresAndStageRetention() throws Exception {
        BenchmarkCaseResultManifest ranked = result("case-result-ranked.synthetic.json");
        BenchmarkCaseResultManifest notIn = result("case-result-not-in-universe.synthetic.json");
        BenchmarkCaseResultManifest unavailable = result("case-result-unavailable.synthetic.json");
        BenchmarkCaseResultManifest assemblyMismatch = result("case-result-assembly-mismatch.synthetic.json");
        notIn.setGroupId("G0");
        unavailable.setGroupId("G0");
        assemblyMismatch.setGroupId("G0");
        notIn.setPrimaryFailureCode("F9");
        unavailable.setPrimaryFailureCode("F2");

        BenchmarkMetricSummary summary = new BenchmarkMetricsAggregator().aggregate(
                Arrays.asList(ranked, notIn, unavailable, assemblyMismatch), "G0");
        assertEquals(4, summary.getEligibleCases());
        assertEquals(1, summary.getRankedCases());
        assertEquals(1, summary.getNotInRankingUniverseCases());
        assertEquals(2, summary.getUnavailableCases());
        assertEquals(0.0, summary.getRecallAtK().get(100), 0.0);
        assertEquals((1.0 / 6001.0) / 4.0, summary.getMeanReciprocalRank(), 0.000000001);
        assertEquals(1, summary.getFailureCounts().get("F9"));
        assertEquals(2, summary.getFailureCounts().get("F2"));
        assertEquals(1, summary.getStagePresenceCounts().get("PERSISTED"));
    }

    @Test
    void multiTruthUsesBestNumericRankWithoutTreatingUnavailableAsLastRank() throws Exception {
        BenchmarkCaseResultManifest ranked = result("case-result-ranked.synthetic.json");
        BenchmarkTruthMatch second = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(ranked.getTruthMatches().get(0)),
                BenchmarkTruthMatch.class);
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 101, "A", "G");
        second.setTruthCanonicalIdentity(key);
        second.setMatchedCanonicalIdentity(key);
        second.setRank(5);
        ranked.getTruthMatches().add(second);
        BenchmarkMetricSummary summary = new BenchmarkMetricsAggregator().aggregate(Arrays.asList(ranked), "G0");
        assertEquals(1.0, summary.getRecallAtK().get(5), 0.0);
        assertEquals(0.2, summary.getMeanReciprocalRank(), 0.0);
    }

    private BenchmarkCaseResultManifest result(String name) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/benchmark/" + name)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) { output.write(buffer, 0, count); }
            return codec.readCaseResultJson(new String(output.toByteArray(), "UTF-8"));
        }
    }
}
