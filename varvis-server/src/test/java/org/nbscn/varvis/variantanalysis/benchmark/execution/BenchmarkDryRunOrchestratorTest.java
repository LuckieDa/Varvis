package org.nbscn.varvis.variantanalysis.benchmark.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetManifest;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestCodec;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunGroup;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunManifest;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkDryRunOrchestratorTest {
    private final BenchmarkManifestCodec codec = new BenchmarkManifestCodec();
    private final ObjectMapper mapper = new ObjectMapper();
    private final BenchmarkDryRunOrchestrator orchestrator = new BenchmarkDryRunOrchestrator();

    @Test
    void resourcePendingAndResumeAreExplicitWithoutExecutingCases() throws Exception {
        BenchmarkDatasetManifest dataset = dataset();
        BenchmarkRunManifest run = run();
        BenchmarkExecutionPlan plan = orchestrator.plan(dataset, run, false,
                Collections.singleton("case-alpha/G0"), 5);

        assertEquals(BenchmarkExecutionState.RESOURCE_PENDING, plan.getState());
        assertEquals("RESOURCE_PENDING", plan.getPreflightCode());
        assertEquals(BenchmarkExecutionState.SKIPPED_COMPLETED, plan.getCases().get(0).getState());
        assertTrue(plan.getCases().stream().skip(1)
                .allMatch(item -> item.getState() == BenchmarkExecutionState.RESOURCE_PENDING));
    }

    @Test
    void oneCaseAndFiveCaseSmokeUseFrozenGroupsAndOnlyHpoDiffers() throws Exception {
        BenchmarkDatasetManifest dataset = dataset();
        BenchmarkRunManifest run = run();
        expandToFiveCases(dataset, run);

        BenchmarkExecutionPlan single = orchestrator.plan(dataset, run, true,
                Collections.<String>emptySet(), 1);
        assertEquals(3, single.getCases().size());
        assertEquals(Arrays.asList("G0", "G1", "G2"), Arrays.asList(
                single.getCases().get(0).getGroupId(), single.getCases().get(1).getGroupId(),
                single.getCases().get(2).getGroupId()));
        assertTrue(single.getCases().get(0).getHpoTerms().isEmpty());
        assertEquals("SOURCE_PROVIDED", single.getCases().get(1).getHpoInputMode());
        assertEquals("HUMAN_REVIEWED", single.getCases().get(2).getHpoInputMode());
        assertEquals("DRY_RUN_NOT_EXECUTED", single.getCases().get(0).getExecutionMode());
        assertEquals("READ_COMPLETE_INTERNAL_RANK", single.getCases().get(0).getWorkflowSteps().get(4));

        BenchmarkExecutionPlan smoke = orchestrator.plan(dataset, run, true,
                Collections.<String>emptySet(), 5);
        assertEquals(15, smoke.getCases().size());
        Map<String, BenchmarkExecutionState> outcomes = new HashMap<String, BenchmarkExecutionState>();
        outcomes.put("case-3/G1", BenchmarkExecutionState.FAILED);
        orchestrator.executeSynthetic(smoke, new SyntheticFakeBenchmarkCaseExecutor(outcomes));
        assertEquals(BenchmarkExecutionState.FAILED, smoke.getState());
        assertEquals("SYNTHETIC_FAKE_FAILED", smoke.getCases().stream()
                .filter(item -> "case-3/G1".equals(item.key())).findFirst().get().getReasonCode());
        assertEquals("SYNTHETIC_FAKE", SyntheticFakeBenchmarkCaseExecutor.EXECUTION_MODE);
    }

    @Test
    void rejectsRunDatasetMismatchAndInvalidLimit() throws Exception {
        BenchmarkDatasetManifest dataset = dataset();
        BenchmarkRunManifest run = run();
        run.setDatasetId("different-dataset");
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.plan(dataset, run, true, Collections.<String>emptySet(), 1));
        run.setDatasetId(dataset.getDatasetId());
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.plan(dataset, run, true, Collections.<String>emptySet(), 0));
    }

    private void expandToFiveCases(BenchmarkDatasetManifest dataset, BenchmarkRunManifest run) throws Exception {
        BenchmarkDatasetCase templateCase = dataset.getCases().get(0);
        BenchmarkRunGroup g0 = run.getGroups().get(0);
        BenchmarkRunGroup g1 = run.getGroups().get(1);
        BenchmarkRunGroup g2 = run.getGroups().get(2);
        for (BenchmarkRunGroup template : Arrays.asList(g1, g2)) {
            BenchmarkRunGroup betaGroup = mapper.readValue(mapper.writeValueAsBytes(template), BenchmarkRunGroup.class);
            betaGroup.setCaseId("case-beta");
            run.getGroups().add(betaGroup);
        }
        for (int index = 3; index <= 5; index++) {
            String caseId = "case-" + index;
            BenchmarkDatasetCase cloneCase = mapper.readValue(mapper.writeValueAsBytes(templateCase), BenchmarkDatasetCase.class);
            cloneCase.setCaseId(caseId);
            cloneCase.setSourceRecordId("synthetic-record-" + index);
            dataset.getCases().add(cloneCase);
            for (BenchmarkRunGroup template : Arrays.asList(g0, g1, g2)) {
                BenchmarkRunGroup cloneGroup = mapper.readValue(mapper.writeValueAsBytes(template), BenchmarkRunGroup.class);
                cloneGroup.setCaseId(caseId);
                run.getGroups().add(cloneGroup);
            }
        }
    }

    private BenchmarkDatasetManifest dataset() throws Exception {
        return codec.readDatasetJson(resource("dataset-manifest.synthetic.json"));
    }

    private BenchmarkRunManifest run() throws Exception {
        return codec.readRunJson(resource("run-manifest.synthetic.json"));
    }

    private String resource(String name) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/benchmark/" + name)) {
            return new String(readAll(stream), "UTF-8");
        }
    }

    private byte[] readAll(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = stream.read(buffer)) >= 0) { output.write(buffer, 0, count); }
        return output.toByteArray();
    }
}
