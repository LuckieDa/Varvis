package org.nbscn.varvis.variantanalysis.benchmark.preparation;

public enum SyntheticVcfValidationStatus {
    VALID,
    INPUT_MISSING,
    HEADER_MALFORMED,
    ASSEMBLY_MISMATCH,
    SAMPLE_MISMATCH,
    SCOPE_INCOMPLETE,
    TRUTH_MISSING,
    ALLELE_MISMATCH,
    MALFORMED_RECORD
}
