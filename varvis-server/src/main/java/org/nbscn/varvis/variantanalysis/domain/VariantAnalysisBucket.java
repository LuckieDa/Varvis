package org.nbscn.varvis.variantanalysis.domain;

public enum VariantAnalysisBucket {
    CANDIDATE,
    KNOWN_CLINICAL,
    HIGH_PRIORITY,
    REVIEW,
    FILTERED_QC,
    FILTERED_COMMON,
    FILTERED_NOT_CARRIED,
    DEFERRED_LOW_PRIORITY,
    DEFERRED_NO_EXPENSIVE_ANNOTATION
}
