package org.nbscn.varvis.variantanalysis.domain;

/** Truth-free lifecycle status for a ranking engine stage. */
public enum EngineExecutionStatus {
    NOT_REQUESTED,
    RUNNING,
    RANKED,
    UNAVAILABLE,
    FAILED
}
