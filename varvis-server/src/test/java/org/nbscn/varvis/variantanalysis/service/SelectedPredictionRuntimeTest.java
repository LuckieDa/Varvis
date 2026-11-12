package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real preparation/source gates with synthetic prediction rows; no scientific resource adoption. */
class SelectedPredictionRuntimeTest {
    @TempDir Path temporary;
    private SelectedEvidencePreparationTest.Prepared selected(boolean blocked, boolean changed) throws Exception {
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = temporary;
        return fixture.prepare(false, blocked, changed, 1, true);
    }
    private SelectedPredictionRuntime runtime(SelectedEvidencePreparationTest.Prepared selected, String score, AtomicInteger calls) {
        return runtime(selected, score, calls, null);
    }
    private SelectedPredictionRuntime runtime(SelectedEvidencePreparationTest.Prepared selected, String score, AtomicInteger calls, NativeSpliceAiRuntime nativeSplice) {
        ObjectMapper mapper = selected.mapper(); var resources = mapper.createObjectNode();
        var resource = PredictionCoreTest.resource(mapper); resources.set("ALPHAMISSENSE", resource);
        resources.putObject("SPLICEAI").put("calibrationCompatibility", "UNVERIFIED");
        var context = selected.result().assembly().candidates().get(0).attempts().get(0).context();
        String transcript = context == null ? "" : context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        var tx = selected.original().getTranscriptAnnotations().stream().filter(t -> transcript.equals(t.getVersionedTranscriptId()))
                .findFirst().orElse(selected.original().getTranscriptAnnotations().get(0));
        var core = new PredictionDevelopmentRuntime(mapper, PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json"),
                PredictionDevelopmentRuntime.load(mapper, "prediction-am-calibration-v1.json"), resources, request -> {
                    calls.incrementAndGet();
                    var raw = mapper.createObjectNode().put("status", "HIT").put("dataClass", "SYNTHETIC");
                    raw.set("queryAllele", request.path("queryAllele").deepCopy()); raw.set("resource", resource.deepCopy());
                    raw.putArray("rows").addObject().put("transcriptId", tx.getVersionedTranscriptId())
                            .put("proteinVariant", tx.getAminoAcidChange()).put("rawScore", score);
                    return raw;
                }, true, plan -> PredictionCoreTest.restrictions(mapper, plan));
        return new SelectedPredictionRuntime(mapper, core, Hnf1aRevelRuntime.unavailable(mapper), null, null,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> PredictionCoreTest.restrictions(mapper, plan), null, null, nativeSplice);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0.99", "0.1"})
    void singlePredictionPreservesDecisionAndDoesNotReuseADifferentScope(String score) throws Exception {
        var p = selected(false, false);
        var fullRuntime = runtime(p, score, new AtomicInteger());
        var full = fullRuntime.execute(fullRuntime.prepare(p.result(), analysis(p)));
        for (String code : java.util.List.of("PP3", "BP4")) {
            var calls = new AtomicInteger(); var oneRuntime = runtime(p, score, calls);
            var batch = oneRuntime.prepare(p.result(), analysis(p));
            var scope = java.util.List.of("ACMG_AMP_2015:" + code);
            var one = oneRuntime.execute(batch, scope);
            for (int i=0; i<one.path("results").size(); i++) {
                assertEquals(1, one.path("results").get(i).path("criteria").size());
                assertEquals(full.at("/results/"+i+"/criteria/"+code), one.at("/results/"+i+"/criteria/"+code));
            }
            int count = calls.get(); assertEquals(one, oneRuntime.execute(batch, scope)); assertEquals(count, calls.get());
            assertThrows(IllegalArgumentException.class, () -> oneRuntime.execute(batch));
        }
    }
    @Test void nativeSpliceMechanismCompletesBenignPairThroughSelectedOrchestratorOnlyOnce() throws Exception {
        var selected = selected(false, false); var mapper = selected.mapper();
        var raw = org.mockito.Mockito.mock(NativeSpliceAiRuntime.class);
        var plan = org.mockito.Mockito.mock(NativeSpliceAiRuntime.Plan.class);
        org.mockito.Mockito.when(plan.snapshot()).thenReturn(mapper.createObjectNode().put("status", "PREPARED"));
        org.mockito.Mockito.when(raw.prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(plan);
        var observed = mapper.createObjectNode(); observed.putObject("mechanism").put("status", "CALIBRATED").put("direction", "BENIGN")
                .put("appliedStrength", "SUPPORTING").put("reasonCode", "CALIBRATED_INTERVAL");
        observed.putObject("observation").put("status", "HIT");
        org.mockito.Mockito.when(raw.evaluate(plan)).thenReturn(observed);
        org.mockito.Mockito.when(raw.audit()).thenReturn(mapper.createObjectNode().put("batchInvocations", 1));
        var calls = new AtomicInteger(); var runtime = runtime(selected, "0.01", calls, raw);
        var batch = runtime.prepare(selected.result(), analysis(selected));
        var result = runtime.execute(batch);
        assertEquals("MET", result.at("/results/0/criteria/BP4/status").asText(), result.toPrettyString());
        assertEquals("RAW", result.at("/results/0/mechanismSummary/mechanisms/1/scoreMode").asText());
        assertEquals(result, runtime.execute(batch));
        org.mockito.Mockito.verify(raw, org.mockito.Mockito.times(1)).compute(org.mockito.ArgumentMatchers.anyList());
        assertEquals(1, calls.get());
    }
    private AnalysisContext analysis(SelectedEvidencePreparationTest.Prepared p) {
        return new AnalysisContext(p.result().assembly().analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES);
    }
    @Test void actualSelectedProjectionPlansEveryBackgroundAndSharesOnlyObservation() throws Exception {
        var p = selected(false, false); var calls = new AtomicInteger(); var runtime = runtime(p, "0.99", calls);
        var batch = runtime.prepare(p.result(), analysis(p)); assertEquals(0, calls.get());
        assertEquals(2, batch.snapshot().path("plans").size());
        var output = runtime.execute(batch); assertEquals(1, calls.get(), output.toPrettyString());
        assertEquals("MET", output.at("/results/0/criteria/PP3/status").asText(), output.toPrettyString());
        assertEquals("NOT_MET", output.at("/results/0/criteria/BP4/status").asText());
        assertNotEquals(output.at("/results/0/sourceProjection/bindingSha256"), output.at("/results/1/sourceProjection/bindingSha256"));
        assertEquals(output.at("/results/0/developmentResult/observation"), output.at("/results/1/developmentResult/observation"));
        assertEquals("evidence-context-v3", output.at("/results/0/sourceProjection/originalContext/schemaVersion").asText());
        assertEquals("evidence-context-v2", output.at("/results/0/sourceProjection/evaluationContext/schemaVersion").asText());
        assertEquals(output, runtime.execute(batch)); assertEquals(1, calls.get());
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }
    @Test void benignProteinCannotReplaceTheRequiredSplicingMechanism() throws Exception {
        var p = selected(false, false); var calls = new AtomicInteger(); var runtime = runtime(p, "0.1", calls);
        var output = runtime.execute(runtime.prepare(p.result(), analysis(p)));
        assertEquals("BENIGN", output.at("/results/0/developmentResult/mechanisms/PROTEIN_MISSENSE/direction").asText());
        assertEquals("NOT_EVALUATED", output.at("/results/0/criteria/BP4/status").asText());
        assertEquals("BP4_REQUIRED_MECHANISM_UNRESOLVED", output.at("/results/0/criteria/BP4/reasonCode").asText());
    }
    @Test void sourceFailurePreventsEveryProviderAndPreservesSelectedPositions() throws Exception {
        var p = selected(true, false); var calls = new AtomicInteger(); var runtime = runtime(p, "0.99", calls);
        var output = runtime.execute(runtime.prepare(p.result(), analysis(p)));
        assertEquals(0, calls.get()); assertEquals(0, output.path("sharedSpliceProviderCalls").asInt());
        assertEquals(2, output.path("results").size());
        assertEquals("NOT_EVALUATED", output.at("/results/0/criteria/PP3/status").asText());
    }
    @Test void changedQueryCannotReuseOriginalAnnotationsOrAnUnrelatedRuntimeBatch() throws Exception {
        var p = selected(false, true); var calls = new AtomicInteger(); var runtime = runtime(p, "0.99", calls);
        var batch = runtime.prepare(p.result(), analysis(p));
        var output = runtime.execute(batch);
        assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", output.at("/results/0/criteria/PP3/reasonCode").asText());
        assertEquals(0, calls.get()); assertEquals(0, output.path("sharedSpliceProviderCalls").asInt());
        assertThrows(IllegalArgumentException.class, () -> runtime(p, "0.1", calls).execute(batch));
    }
    @Test void typedDependencySubsetPreparesOnlyTheActualRequestedAttemptAndKeepsOutputScope() throws Exception {
        var p = selected(false, false); var calls = new AtomicInteger(); var runtime = runtime(p, "0.99", calls);
        var candidate = p.result().assembly().candidates().get(0);
        var attempt = candidate.attempts().get(0);
        var keys = new java.util.HashSet<SelectedPredictionRuntime.AttemptKey>();
        keys.add(new SelectedPredictionRuntime.AttemptKey(candidate.candidateId(), attempt.hypothesisId()));
        var batch = runtime.prepare(p.result(), analysis(p), keys); keys.clear();
        assertEquals(0, calls.get()); assertEquals(1, batch.snapshot().path("plans").size());
        var output = runtime.execute(batch, java.util.List.of("ACMG_AMP_2015:PP3"));
        assertEquals(1, output.path("results").size());
        assertEquals(attempt.hypothesisId(), output.at("/results/0/plan/hypothesisId").asText());
        assertEquals(1, output.at("/results/0/criteria").size()); assertTrue(output.at("/results/0/criteria/PP3").isObject());
        int before = calls.get(); assertEquals(output, runtime.execute(batch, java.util.List.of("ACMG_AMP_2015:PP3"))); assertEquals(before, calls.get());
    }
    @Test void unpreparedAttemptSubsetIsRejectedBeforeAnyAcquisitionAndEmptySubsetDoesNoWork() throws Exception {
        var p = selected(false, false); var calls = new AtomicInteger(); var runtime = runtime(p, "0.99", calls);
        var wrong = java.util.Set.of(new SelectedPredictionRuntime.AttemptKey(p.original().getCandidateId(), "unselected-background"));
        assertEquals("PREDICTION_ATTEMPT_SUBSET_NOT_PREPARED", assertThrows(IllegalArgumentException.class,
                () -> runtime.prepare(p.result(), analysis(p), wrong)).getMessage());
        var empty = runtime.prepare(p.result(), analysis(p), java.util.Set.of());
        assertTrue(empty.snapshot().path("plans").isEmpty());
        assertTrue(runtime.execute(empty, java.util.List.of("ACMG_AMP_2015:PP3")).path("results").isEmpty());
        assertEquals(0, calls.get());
    }
}
