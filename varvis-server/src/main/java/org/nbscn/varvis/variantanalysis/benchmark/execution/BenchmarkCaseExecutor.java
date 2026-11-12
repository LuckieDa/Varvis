package org.nbscn.varvis.variantanalysis.benchmark.execution;

public interface BenchmarkCaseExecutor {
    BenchmarkExecutionState execute(BenchmarkCaseExecutionPlan plan);
}
