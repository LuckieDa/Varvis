package org.nbscn.varvis.variantanalysis.benchmark.execution;

import java.util.LinkedHashMap;
import java.util.Map;

public class BenchmarkMetricSummary {
    private int eligibleCases;
    private int rankedCases;
    private int notInRankingUniverseCases;
    private int unavailableCases;
    private double meanReciprocalRank;
    private Map<Integer, Double> recallAtK = new LinkedHashMap<Integer, Double>();
    private Map<String, Integer> failureCounts = new LinkedHashMap<String, Integer>();
    private Map<String, Integer> stagePresenceCounts = new LinkedHashMap<String, Integer>();

    public int getEligibleCases() { return eligibleCases; }
    public void setEligibleCases(int eligibleCases) { this.eligibleCases = eligibleCases; }
    public int getRankedCases() { return rankedCases; }
    public void setRankedCases(int rankedCases) { this.rankedCases = rankedCases; }
    public int getNotInRankingUniverseCases() { return notInRankingUniverseCases; }
    public void setNotInRankingUniverseCases(int value) { notInRankingUniverseCases = value; }
    public int getUnavailableCases() { return unavailableCases; }
    public void setUnavailableCases(int unavailableCases) { this.unavailableCases = unavailableCases; }
    public double getMeanReciprocalRank() { return meanReciprocalRank; }
    public void setMeanReciprocalRank(double value) { meanReciprocalRank = value; }
    public Map<Integer, Double> getRecallAtK() { return recallAtK; }
    public void setRecallAtK(Map<Integer, Double> value) { recallAtK = value; }
    public Map<String, Integer> getFailureCounts() { return failureCounts; }
    public void setFailureCounts(Map<String, Integer> value) { failureCounts = value; }
    public Map<String, Integer> getStagePresenceCounts() { return stagePresenceCounts; }
    public void setStagePresenceCounts(Map<String, Integer> value) { stagePresenceCounts = value; }
}
