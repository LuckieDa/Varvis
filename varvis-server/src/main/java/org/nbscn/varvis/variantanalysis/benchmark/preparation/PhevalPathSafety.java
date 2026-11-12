package org.nbscn.varvis.variantanalysis.benchmark.preparation;

final class PhevalPathSafety {

    private PhevalPathSafety() {
    }

    static void requireLogicalRelative(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("REQUIRED_" + field.toUpperCase());
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")
                || normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../")) {
            throw new IllegalArgumentException("UNSAFE_PATH_" + field.toUpperCase());
        }
    }
}
