package org.nbscn.varvis.variantanalysis.provider;

import java.math.BigInteger;

/** Frozen Table 2, floor lookup only. Counts and comparisons never pass through floating point. */
public final class Pp4DiagnosticYield {
    public static final String VERSION = "pp4-table2-floor-cap5-v1";
    public static final long MAX_COUNT = 1_000_000_000L;
    private static final int[] THRESHOLDS = {191,254,330,415,506,596,680,754,816,864,902,930,950,965,975,983,988,992,994,996,997,998,999};
    public record Result(int thresholdPerThousand, int rawHalfPoints, int appliedHalfPoints, String strength) {
        public String rawPoints() { return decimal(rawHalfPoints); }
        public String appliedPoints() { return decimal(appliedHalfPoints); }
        public String capReason() { return rawHalfPoints > 10 ? "SINGLE_PP4_CAP_5" : "NOT_CAPPED"; }
    }
    private Pp4DiagnosticYield() { }
    private static String decimal(int halves) { return halves % 2 == 0 ? Integer.toString(halves / 2) : (halves / 2) + ".5"; }
    public static Result calculate(long numerator, long denominator) {
        if (denominator <= 0 || denominator > MAX_COUNT || numerator < 0 || numerator > denominator)
            throw new IllegalArgumentException("PP4_YIELD_COUNTS_INVALID");
        BigInteger n = BigInteger.valueOf(numerator), d = BigInteger.valueOf(denominator);
        if (n.multiply(BigInteger.valueOf(5)).compareTo(d) <= 0 || n.compareTo(d) >= 0)
            throw new IllegalArgumentException("PP4_YIELD_OUTSIDE_BOUNDED_INTERVAL");
        int row = 0;
        for (int i = 1; i < THRESHOLDS.length; i++)
            if (n.multiply(BigInteger.valueOf(1000)).compareTo(d.multiply(BigInteger.valueOf(THRESHOLDS[i]))) >= 0) row = i;
        int raw = row + 2, applied = Math.min(raw, 10);
        return new Result(THRESHOLDS[row], raw, applied, applied >= 8 ? "STRONG" : applied >= 4 ? "MODERATE" : "SUPPORTING");
    }
}
