package org.nbscn.varvis.variantanalysis.benchmark.execution;

import java.util.Collections;
import java.util.Map;

/** Test/dry-run executor; it never starts PhEval, Varvis, or an external process. */
public class SyntheticFakeBenchmarkCaseExecutor implements BenchmarkCaseExecutor {
    public static final String EXECUTION_MODE = "SYNTHETIC_FAKE";
    private final Map<String, BenchmarkExecutionState> outcomes;

    public SyntheticFakeBenchmarkCaseExecutor(Map<String, BenchmarkExecutionState> outcomes) {
        this.outcomes = outcomes == null
                ? Collections.<String, BenchmarkExecutionState>emptyMap() : outcomes;
    }

    @Override
    public BenchmarkExecutionState execute(BenchmarkCaseExecutionPlan plan) {
        if (plan == null || plan.getState() != BenchmarkExecutionState.READY) {
            return BenchmarkExecutionState.FAILED;
        }
        BenchmarkExecutionState configured = outcomes.get(plan.key());
        return configured == null ? BenchmarkExecutionState.COMPLETED : configured;
    }
}
