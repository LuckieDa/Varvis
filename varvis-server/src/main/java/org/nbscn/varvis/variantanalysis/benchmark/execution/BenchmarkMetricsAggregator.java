package org.nbscn.varvis.variantanalysis.benchmark.execution;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkCaseResultManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkFailureCode;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestValidator;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkStageRetention;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthMatch;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkMetricsAggregator {
    private static final List<Integer> CUTOFFS = Arrays.asList(1, 5, 10, 20, 50, 100);

    public BenchmarkMetricSummary aggregate(List<BenchmarkCaseResultManifest> results, String groupId) {
        BenchmarkMetricSummary summary = new BenchmarkMetricSummary();
        Map<Integer, Integer> hits = new LinkedHashMap<Integer, Integer>();
        for (Integer cutoff : CUTOFFS) { hits.put(cutoff, 0); }
        double reciprocalRank = 0.0;
        if (results != null) {
            for (BenchmarkCaseResultManifest result : results) {
                if (result == null || !groupId.equals(result.getGroupId())) { continue; }
                BenchmarkManifestValidator.requireValid(result);
                summary.setEligibleCases(summary.getEligibleCases() + 1);
                Integer bestRank = bestRank(result.getTruthMatches());
                if (bestRank == null) {
                    if (containsStatus(result.getTruthMatches(), RankStatus.RANK_UNAVAILABLE)) {
                        summary.setUnavailableCases(summary.getUnavailableCases() + 1);
                    } else {
                        summary.setNotInRankingUniverseCases(summary.getNotInRankingUniverseCases() + 1);
                    }
                } else {
                    summary.setRankedCases(summary.getRankedCases() + 1);
                    reciprocalRank += 1.0 / bestRank;
                    for (Integer cutoff : CUTOFFS) {
                        if (bestRank <= cutoff) { hits.put(cutoff, hits.get(cutoff) + 1); }
                    }
                }
                if (result.getPrimaryFailureCode() != null) {
                    if (!BenchmarkFailureCode.isAllowed(result.getPrimaryFailureCode())) {
                        throw new IllegalArgumentException("UNSUPPORTED_FAILURE_CODE");
                    }
                    increment(summary.getFailureCounts(), result.getPrimaryFailureCode());
                }
                for (BenchmarkStageRetention retention : result.getStageRetention()) {
                    if (retention != null && "PRESENT".equals(retention.getPresence())) {
                        increment(summary.getStagePresenceCounts(), retention.getStage());
                    }
                }
            }
        }
        int denominator = summary.getEligibleCases();
        summary.setMeanReciprocalRank(denominator == 0 ? 0.0 : reciprocalRank / denominator);
        for (Integer cutoff : CUTOFFS) {
            summary.getRecallAtK().put(cutoff, denominator == 0 ? 0.0 : ((double) hits.get(cutoff)) / denominator);
        }
        return summary;
    }

    private Integer bestRank(List<BenchmarkTruthMatch> matches) {
        Integer best = null;
        if (matches != null) {
            for (BenchmarkTruthMatch match : matches) {
                if (match != null && match.getRankStatus() == RankStatus.RANKED && match.getRank() != null
                        && (best == null || match.getRank() < best)) {
                    best = match.getRank();
                }
            }
        }
        return best;
    }

    private boolean containsStatus(List<BenchmarkTruthMatch> matches, RankStatus status) {
        if (matches != null) {
            for (BenchmarkTruthMatch match : matches) {
                if (match != null && match.getRankStatus() == status) { return true; }
            }
        }
        return false;
    }

    private void increment(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, current == null ? 1 : current + 1);
    }
}
