package org.nbscn.varvis.variantanalysis.benchmark.preparation;

public interface PhevalPreparationExecutor {
    PhevalExecutionResult execute(PhevalPreparationRequest request, PhevalCommandPlan plan);
}
