package org.nbscn.varvis.utils;

import org.springframework.util.StringUtils;

public final class VariantUtils {

    private VariantUtils() {
    }

    public static String normalizeVariantType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    public static String normalizeClinicalSignificance(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
