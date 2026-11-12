package org.nbscn.varvis.variantanalysis.benchmark.execution;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestValidator;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunGroup;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds an auditable dry-run plan; it never submits a Varvis analysis. */
public class BenchmarkDryRunOrchestrator {

    public BenchmarkExecutionPlan plan(BenchmarkDatasetManifest dataset,
                                       BenchmarkRunManifest run,
                                       boolean backgroundReady,
                                       Set<String> completedCaseGroups,
                                       int maximumCases) {
        BenchmarkManifestValidator.requireValid(dataset);
        BenchmarkManifestValidator.requireValid(run);
        if (!dataset.getDatasetId().equals(run.getDatasetId())
                || !dataset.getDatasetVersion().equals(run.getDatasetVersion())) {
            throw new IllegalArgumentException("DATASET_RUN_MISMATCH");
        }
        if (maximumCases <= 0) {
            throw new IllegalArgumentException("INVALID_CASE_LIMIT");
        }
        Set<String> datasetCases = new HashSet<String>();
        for (BenchmarkDatasetCase datasetCase : dataset.getCases()) {
            if (datasetCase != null && "INCLUDED".equals(datasetCase.getInclusionStatus())) {
                datasetCases.add(datasetCase.getCaseId());
            }
        }
        Set<String> completed = completedCaseGroups == null
                ? Collections.<String>emptySet() : completedCaseGroups;
        Set<String> selectedCases = new HashSet<String>();
        List<BenchmarkCaseExecutionPlan> items = new ArrayList<BenchmarkCaseExecutionPlan>();
        for (BenchmarkRunGroup group : run.getGroups()) {
            if (!datasetCases.contains(group.getCaseId())) {
                throw new IllegalArgumentException("RUN_CASE_NOT_INCLUDED: " + group.getCaseId());
            }
            if (!selectedCases.contains(group.getCaseId()) && selectedCases.size() >= maximumCases) {
                continue;
            }
            selectedCases.add(group.getCaseId());
            BenchmarkCaseExecutionPlan item = new BenchmarkCaseExecutionPlan();
            item.setCaseId(group.getCaseId());
            item.setGroupId(group.getGroupId());
            item.setHpoInputMode(group.getHpoInputMode());
            item.setHpoTerms(group.getHpoTerms() == null
                    ? Collections.<String>emptyList() : new ArrayList<String>(group.getHpoTerms()));
            item.setExecutionMode("DRY_RUN_NOT_EXECUTED");
            item.setWorkflowSteps(java.util.Arrays.asList(
                    "PREFLIGHT", "PREPARE_SPIKED_VCF", "SUBMIT_VARVIS_ANALYSIS",
                    "WAIT_FOR_PERSISTED_RESULT", "READ_COMPLETE_INTERNAL_RANK",
                    "WRITE_CASE_RESULT_MANIFEST"));
            if (completed.contains(item.key())) {
                item.setState(BenchmarkExecutionState.SKIPPED_COMPLETED);
                item.setReasonCode("COMPLETED_RESULT_PRESENT");
            } else if (!backgroundReady) {
                item.setState(BenchmarkExecutionState.RESOURCE_PENDING);
                item.setReasonCode("BACKGROUND_RESOURCE_PENDING");
            } else {
                item.setState(BenchmarkExecutionState.READY);
                item.setReasonCode("DRY_RUN_READY");
            }
            items.add(item);
        }
        BenchmarkExecutionPlan plan = new BenchmarkExecutionPlan();
        plan.setRunId(run.getRunId());
        plan.setDryRun(true);
        plan.setCases(items);
        plan.setState(backgroundReady ? BenchmarkExecutionState.READY : BenchmarkExecutionState.RESOURCE_PENDING);
        plan.setPreflightCode(backgroundReady ? "PREFLIGHT_READY" : "RESOURCE_PENDING");
        return plan;
    }

    public BenchmarkExecutionPlan executeSynthetic(BenchmarkExecutionPlan plan,
                                                   BenchmarkCaseExecutor executor) {
        if (plan == null || executor == null || !plan.isDryRun()) {
            throw new IllegalArgumentException("SYNTHETIC_EXECUTION_CONTRACT_REQUIRED");
        }
        boolean failed = false;
        for (BenchmarkCaseExecutionPlan item : plan.getCases()) {
            if (item.getState() == BenchmarkExecutionState.READY) {
                BenchmarkExecutionState outcome = executor.execute(item);
                if (outcome != BenchmarkExecutionState.COMPLETED
                        && outcome != BenchmarkExecutionState.FAILED) {
                    throw new IllegalArgumentException("INVALID_SYNTHETIC_EXECUTOR_OUTCOME");
                }
                item.setState(outcome);
                item.setReasonCode(outcome == BenchmarkExecutionState.COMPLETED
                        ? "SYNTHETIC_FAKE_COMPLETED" : "SYNTHETIC_FAKE_FAILED");
                failed |= outcome == BenchmarkExecutionState.FAILED;
            }
        }
        plan.setState(failed ? BenchmarkExecutionState.FAILED : BenchmarkExecutionState.COMPLETED);
        return plan;
    }
}
