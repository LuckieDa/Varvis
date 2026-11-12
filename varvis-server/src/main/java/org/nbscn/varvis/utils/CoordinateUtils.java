package org.nbscn.varvis.utils;

import org.springframework.util.StringUtils;

public final class CoordinateUtils {

    private CoordinateUtils() {
    }

    public static String normalizeChromosome(String chromosome) {
        if (!StringUtils.hasText(chromosome)) {
            return null;
        }
        String raw = chromosome.trim().toUpperCase();
        if (raw.startsWith("CHR")) {
            raw = raw.substring(3);
        }
        if ("M".equals(raw)) {
            return "MT";
        }
        return raw;
    }

    public static long[] normalizeRange(Long startBp, Long endBp) {
        if (startBp == null || endBp == null) {
            throw new IllegalArgumentException("startBp/endBp must not be null");
        }
        long start = Math.min(startBp, endBp);
        long end = Math.max(startBp, endBp);
        return new long[]{start, end};
    }

    public static boolean overlaps(long startA, long endA, long startB, long endB) {
        return startA <= endB && endA >= startB;
    }
}
