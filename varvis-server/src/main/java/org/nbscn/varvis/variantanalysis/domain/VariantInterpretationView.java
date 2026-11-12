package org.nbscn.varvis.variantanalysis.domain;

import java.util.Locale;

public enum VariantInterpretationView {
    CANDIDATES,
    FREQUENCY_FILTERED,
    ALL;

    public static VariantInterpretationView from(String value) {
        if (value == null || value.trim().isEmpty()) {
            return CANDIDATES;
        }
        String normalized = value.trim().replace("-", "_").toUpperCase(Locale.ROOT);
        if ("FILTERED".equals(normalized) || "POPULATION_FILTERED".equals(normalized)) {
            return FREQUENCY_FILTERED;
        }
        return valueOf(normalized);
    }
}
