package org.nbscn.varvis.variantanalysis.domain;

public enum VariantAnalysisStatus {
    UPLOADED,
    VALIDATING,
    NORMALIZING,
    QC_EXTRACTING,
    BASE_ANNOTATING,
    RANKING,
    READY,
    FAILED
}
