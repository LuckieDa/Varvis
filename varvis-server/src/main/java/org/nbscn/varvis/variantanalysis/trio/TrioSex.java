package org.nbscn.varvis.variantanalysis.trio;

public enum TrioSex {
    MALE,
    FEMALE,
    UNKNOWN;

    public static TrioSex fromRequest(String value) {
        if (value == null || value.trim().isEmpty()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    public String pedCode() {
        if (this == MALE) {
            return "1";
        }
        if (this == FEMALE) {
            return "2";
        }
        return "0";
    }
}
