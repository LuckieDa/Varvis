package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class BenchmarkFailureCode {

    public static final String F0 = "F0";
    public static final String F1 = "F1";
    public static final String F2 = "F2";
    public static final String F3 = "F3";
    public static final String F4 = "F4";
    public static final String F5 = "F5";
    public static final String F6 = "F6";
    public static final String F7 = "F7";
    public static final String F8 = "F8";
    public static final String F9 = "F9";
    public static final String F10 = "F10";
    public static final String F11 = "F11";

    private static final Set<String> ALL = new HashSet<String>(Arrays.asList(
            F0, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11
    ));

    private BenchmarkFailureCode() {
    }

    public static boolean isAllowed(String code) {
        return code != null && ALL.contains(code);
    }
}
