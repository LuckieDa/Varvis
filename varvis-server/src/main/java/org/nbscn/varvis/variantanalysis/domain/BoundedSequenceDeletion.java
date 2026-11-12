package org.nbscn.varvis.variantanalysis.domain;

/** Exact anchored deletion scope used by the selected PM1 sequence path. */
public final class BoundedSequenceDeletion {
    public static final String NORMALIZATION_METHOD = "BCFTOOLS_SELECTED_BOUNDED_SEQUENCE_DELETION";
    public static final int MAX_ANCHORED_LENGTH = 512;

    private BoundedSequenceDeletion() { }

    public static boolean extended(String reference, String alternate) {
        return reference != null && alternate != null && reference.length() > 50
                && reference.length() <= MAX_ANCHORED_LENGTH && alternate.length() == 1
                && reference.startsWith(alternate) && (reference.length() - 1) % 3 == 0
                && reference.matches("[ACGT]+");
    }
}
