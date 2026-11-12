package org.nbscn.varvis.variantanalysis.benchmark.execution;

public enum BenchmarkExecutionState {
    PLANNED,
    RESOURCE_PENDING,
    READY,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED_COMPLETED
}
