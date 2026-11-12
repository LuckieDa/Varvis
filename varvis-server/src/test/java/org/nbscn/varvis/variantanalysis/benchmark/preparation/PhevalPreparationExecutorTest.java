package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhevalPreparationExecutorTest {

    @Test
    void fakeExecutorCoversSuccessFailureTimeoutMissingAndMalformedOutput() {
        PhevalCommandPlan plan = PhevalCommandPlanFactory.create(new PhevalToolDescriptor(), request());
        PhevalPreparationExecutor fake = (request, command) ->
                PhevalExecutionResult.synthetic(PhevalExecutionStatus.SUCCEEDED, command,
                        "benchmark/runs/synthetic/output.vcf", null, "fake output");
        assertEquals(PhevalExecutionStatus.SUCCEEDED, fake.execute(request(), plan).getStatus());
        assertEquals(PhevalExecutionStatus.FAILED, fakeResult(PhevalExecutionStatus.FAILED, plan).getStatus());
        assertEquals(PhevalExecutionStatus.TIMED_OUT, fakeResult(PhevalExecutionStatus.TIMED_OUT, plan).getStatus());
        assertEquals(PhevalExecutionStatus.OUTPUT_MISSING, fakeResult(PhevalExecutionStatus.OUTPUT_MISSING, plan).getStatus());
        assertEquals(PhevalExecutionStatus.INVALID_OUTPUT, fakeResult(PhevalExecutionStatus.INVALID_OUTPUT, plan).getStatus());
    }

    private PhevalExecutionResult fakeResult(PhevalExecutionStatus status, PhevalCommandPlan plan) {
        return PhevalExecutionResult.synthetic(status, plan, null, status.name(), "synthetic fake status");
    }

    private PhevalPreparationRequest request() {
        PhevalPreparationRequest value = new PhevalPreparationRequest();
        value.setDatasetId("synthetic-dataset");
        value.setCaseId("synthetic-case");
        value.setGroupId("G0");
        value.setPhenopacketInputReference("benchmark/case.json");
        value.setBackgroundVcfReference("benchmark/background.vcf");
        value.setRegionReference("benchmark/exome.bed");
        value.setReferenceAssembly("GRCh38");
        value.setOutputDirectoryReference("benchmark/output");
        value.setExpectedOutputVcf("benchmark/output/result.vcf");
        value.setTimeoutSeconds(60);
        return value;
    }
}
