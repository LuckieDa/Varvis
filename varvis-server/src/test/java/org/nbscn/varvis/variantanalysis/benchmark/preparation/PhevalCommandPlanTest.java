package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhevalCommandPlanTest {

    @Test
    void planUsesTokensStableFingerprintAndPinnedVersion() {
        PhevalToolDescriptor tool = new PhevalToolDescriptor();
        PhevalPreparationRequest request = request();
        PhevalCommandPlan first = PhevalCommandPlanFactory.create(tool, request);
        PhevalCommandPlan second = PhevalCommandPlanFactory.create(tool, request);
        assertEquals("0.7.14", tool.getVersion());
        assertEquals(first.getFingerprint(), second.getFingerprint());
        assertTrue(first.getArgumentTokens().contains("--background-vcf"));
        assertTrue(first.getArgumentTokens().stream().noneMatch(value -> value.contains("cmd /c")));
    }

    @Test
    void unsafePathsTimeoutAndNonAllowlistedEnvironmentAreRejected() {
        PhevalPreparationRequest unsafeOutput = request();
        unsafeOutput.setExpectedOutputVcf("C:/private/output.vcf");
        assertThrows(IllegalArgumentException.class, () -> PhevalCommandPlanFactory.create(
                new PhevalToolDescriptor(), unsafeOutput));
        PhevalPreparationRequest invalidTimeout = request();
        invalidTimeout.setTimeoutSeconds(0);
        assertThrows(IllegalArgumentException.class, () -> PhevalCommandPlanFactory.create(
                new PhevalToolDescriptor(), invalidTimeout));
        assertThrows(IllegalArgumentException.class, () -> new PhevalCommandPlan(
                "benchmark/pheval/0.7.14/pheval", Arrays.asList("run"),
                Arrays.asList("USERNAME"), "benchmark/run", Arrays.asList("../output.vcf")));
    }

    @Test
    void syntheticFakeResultCannotLookLikeRealExecution() {
        PhevalCommandPlan plan = PhevalCommandPlanFactory.create(new PhevalToolDescriptor(), request());
        PhevalExecutionResult result = PhevalExecutionResult.synthetic(
                PhevalExecutionStatus.SUCCEEDED, plan, "benchmark/runs/a/output.vcf", null, "synthetic only");
        assertEquals("SYNTHETIC_FAKE", result.getExecutionMode());
        assertNotEquals("REAL_PHEVAL", result.getExecutionMode());
    }

    private PhevalPreparationRequest request() {
        PhevalPreparationRequest request = new PhevalPreparationRequest();
        request.setDatasetId("synthetic-dataset");
        request.setCaseId("synthetic-case");
        request.setGroupId("G1");
        request.setPhenopacketInputReference("benchmark/phenopackets/case.json");
        request.setBackgroundVcfReference("benchmark/background/input.vcf");
        request.setRegionReference("benchmark/background/exome.bed");
        request.setReferenceAssembly("GRCh38");
        request.setOutputDirectoryReference("benchmark/runs/synthetic");
        request.setExpectedOutputVcf("benchmark/runs/synthetic/output.vcf");
        request.setTimeoutSeconds(120);
        return request;
    }
}
