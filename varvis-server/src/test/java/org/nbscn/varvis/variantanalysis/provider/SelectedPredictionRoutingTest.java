package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic projection/effect fixtures isolate routing; the real preparation boundary is tested separately. */
class SelectedPredictionRoutingTest {
    @Test void preselectedAlphaUsesTheSameMaterialAndKeepsModelInSavedPublicExplanation() throws Exception {
        var fixture = new GencodeTranscriptIndexTest(); fixture.root = root;
        var supply = fixture.supply(fixture.setup()); var f = fixture.input.fixture(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var alpha = new GeneralAlphaMissenseRuntimeTest().runtime(calls, "0.999", "");
        var runtime = new SelectedPredictionRuntime(mapper, neverDevelopment(), Hnf1aRevelRuntime.unavailable(mapper), null, null,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> PredictionCoreTest.restrictions(mapper, plan), supply, null, null, alpha);
        var batch = runtime.prepare(selected(f.candidate(), f.context()), analysis); assertEquals(0, calls.get());
        assertEquals("GENERAL_LOCAL_ALPHAMISSENSE", batch.snapshot().at("/plans/0/route").asText());
        var result = runtime.execute(batch); assertEquals("MET", result.at("/results/0/criteria/PP3/status").asText(), result.toPrettyString());
        var publicView = PredictionMechanismSummary.publicView(mapper, result.at("/results/0/mechanismSummary"));
        assertEquals("GENERAL_LOCAL_ALPHAMISSENSE", publicView.path("route").asText());
        assertEquals("ALPHAMISSENSE", publicView.at("/mechanisms/0/model").asText());
        assertEquals(result, runtime.execute(batch)); assertEquals(1, calls.get());
        var general = new GeneralRevelRuntimeTest().runtime(fixture.input, new java.util.concurrent.atomic.AtomicInteger(), "");
        assertThrows(IllegalArgumentException.class, () -> new SelectedPredictionRuntime(mapper, neverDevelopment(), Hnf1aRevelRuntime.unavailable(mapper), null, null,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> mapper.nullNode(), supply, general, null, alpha));
    }
    @Test void genericModelUsesVerifiedGencodeMaterialAndExistingApplicationWithoutFallback() throws Exception {
        var fixture = new GencodeTranscriptIndexTest(); fixture.root = root;
        var supply = fixture.supply(fixture.setup()); var f = fixture.input.fixture();
        var raw = new GeneralRevelRuntimeTest();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var general = raw.runtime(fixture.input, calls, "");
        var runtime = new SelectedPredictionRuntime(mapper, neverDevelopment(), Hnf1aRevelRuntime.unavailable(mapper), null, null,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> PredictionCoreTest.restrictions(mapper, plan), supply, general);
        var batch = runtime.prepare(selected(f.candidate(), f.context()), analysis); assertEquals(0, calls.get());
        assertEquals("GENERAL_LOCAL_REVEL", batch.snapshot().at("/plans/0/route").asText());
        var result = runtime.execute(batch);
        assertEquals("MET", result.at("/results/0/criteria/PP3/status").asText(), result.toPrettyString());
        assertEquals("REVEL", result.at("/results/0/mechanismSummary/mechanisms/0/model").asText());
        assertEquals("GENERAL_LOCAL_REVEL", PredictionMechanismSummary.publicView(mapper, result.at("/results/0/mechanismSummary")).path("route").asText());
        assertEquals(result, runtime.execute(batch)); assertEquals(1, calls.get());
    }

    @Test void enabledGenericRouteCannotBypassConfiguredUnavailableReviewedPolicy() throws Exception {
        var fixture = new GencodeTranscriptIndexTest(); fixture.root = root;
        var supply = fixture.supply(fixture.setup()); var f = fixture.input.fixture();
        var calls = new java.util.concurrent.atomic.AtomicInteger(); var general = new GeneralRevelRuntimeTest().runtime(fixture.input, calls, "");
        var reviewed = ReviewedPredictionBusinessRuntime.unavailable(mapper, "a".repeat(64), "b".repeat(64));
        var router = new ReviewedPredictionObservationRouter(mapper, new AlphaMissenseProvider(new VariantAnalysisProperties()), false,
                () -> { throw new AssertionError("No fallback"); }, false, null, false);
        var runtime = new SelectedPredictionRuntime(mapper, neverDevelopment(), Hnf1aRevelRuntime.unavailable(mapper), reviewed, router,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> PredictionCoreTest.restrictions(mapper, plan), supply, general);
        var result = runtime.execute(runtime.prepare(selected(f.candidate(), f.context()), analysis));
        assertEquals("REVIEWED_POLICY", result.at("/results/0/plan/route").asText());
        assertEquals("NOT_EVALUATED", result.at("/results/0/criteria/PP3/status").asText()); assertEquals(0, calls.get());
    }
    @Test void gencodeMaterialPassesThroughSelectedPlansWithoutApprovingUnavailableRules() throws Exception {
        var fixture = new GencodeTranscriptIndexTest(); fixture.root = root;
        var index = fixture.setup(); var supply = fixture.supply(index); var f = fixture.input.fixture();
        var reviewed = ReviewedPredictionBusinessRuntime.unavailable(mapper, "a".repeat(64), "b".repeat(64));
        var router = new ReviewedPredictionObservationRouter(mapper, new AlphaMissenseProvider(new VariantAnalysisProperties()),
                false, () -> { throw new AssertionError("Unavailable policy must not read a score"); }, false, null, false);
        var runtime = new SelectedPredictionRuntime(mapper, neverDevelopment(), Hnf1aRevelRuntime.unavailable(mapper), reviewed, router,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> PredictionCoreTest.restrictions(mapper, plan), supply);
        var batch = runtime.prepare(selected(f.candidate(), f.context()), analysis);
        assertEquals("READY", batch.snapshot().at("/plans/0/transcriptMaterial/status").asText());
        var output = runtime.execute(batch);
        assertEquals("NOT_EVALUATED", output.at("/results/0/criteria/PP3/status").asText());
        assertEquals("NOT_EVALUATED", output.at("/results/0/criteria/BP4/status").asText());
        assertEquals("GENCODE_GTF_PLUS_REFERENCE", output.at("/results/0/mechanismSummary/transcriptMaterial/modelOrigin").asText());
        assertEquals(output, runtime.execute(batch));
    }
    @Test void automaticTranscriptMaterialIsPreparedAndSerializedBeforeTheOriginalRoute() throws Exception {
        var fixture = new CodingTranscriptMaterialSupplyTest(); fixture.root = root;
        var supply = fixture.setup(); var f = fixture.input.fixture();
        var reviewed = ReviewedPredictionBusinessRuntime.unavailable(mapper, "a".repeat(64), "b".repeat(64));
        var router = new ReviewedPredictionObservationRouter(mapper, new AlphaMissenseProvider(new VariantAnalysisProperties()),
                false, () -> { throw new AssertionError("No splice execution expected"); }, false, null, false);
        var runtime = new SelectedPredictionRuntime(mapper, neverDevelopment(), Hnf1aRevelRuntime.unavailable(mapper), reviewed, router,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper),
                plan -> PredictionCoreTest.restrictions(mapper, plan), supply);
        var batch = runtime.prepare(selected(f.candidate(), f.context()), analysis);
        assertEquals("READY", batch.snapshot().at("/plans/0/transcriptMaterial/status").asText());
        var result = runtime.execute(batch);
        assertEquals("READY", result.at("/results/0/plan/transcriptMaterial/status").asText());
        assertArrayEquals(mapper.writeValueAsBytes(result), mapper.writeValueAsBytes(mapper.readTree(mapper.writeValueAsBytes(result))));
        assertEquals(result, runtime.execute(batch)); assertEquals(11, fixture.bases.get());
        // No policy approval is conferred by technical readiness.
        assertEquals("NOT_EVALUATED", result.at("/results/0/criteria/PP3/status").asText());
        ((ObjectNode) f.context().path("transcript")).put("version", 5);
        var changed = runtime.execute(runtime.prepare(selected(f.candidate(), f.context()), analysis));
        assertEquals(0, changed.path("sharedSpliceProviderCalls").asInt());
        assertEquals("NOT_EVALUATED", changed.at("/results/0/criteria/PP3/status").asText());
    }
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final AnalysisContext analysis = new AnalysisContext("u14-prediction-routing", GenomeAssembly.GRCh38, AnalysisMode.WES);
    @TempDir Path root;

    private SelectedEvidencePreparation selected(VariantInterpretation candidate, ObjectNode context) {
        var projection = mock(LegacyContextProjection.class);
        var snapshot = mapper.createObjectNode().put("schemaVersion", "selected-legacy-context-projection-v1")
                .put("candidateId", candidate.getCandidateId()).put("hypothesisId", "synthetic-route").put("fixture", "SYNTHETIC_PROJECTION_ONLY");
        snapshot.set("evaluationContext", context); snapshot.put("bindingSha256", adapter.canonicalSha256(snapshot));
        when(projection.context()).thenAnswer(i -> context.deepCopy()); when(projection.queryCandidate()).thenReturn(candidate);
        when(projection.snapshot()).thenAnswer(i -> snapshot.deepCopy()); when(projection.bindingSha256()).thenReturn(snapshot.path("bindingSha256").asText());
        var selected = mock(SelectedEvidencePreparation.class);
        when(selected.legacyProjection(candidate.getCandidateId(), "synthetic-route")).thenReturn(projection);
        var attempt = new Attempt(candidate.getCandidateId(), "synthetic-route", null, null, null, context, List.of());
        when(selected.assembly()).thenReturn(new AssemblyResult(analysis.getAnalysisId(), 1, 1, List.of(candidate.getCandidateId()),
                List.of(new CandidateAssembly(candidate.getCandidateId(), List.of(attempt))), List.of(), false));
        return selected;
    }
    private PredictionDevelopmentRuntime neverDevelopment() {
        var resources = mapper.createObjectNode(); resources.set("ALPHAMISSENSE", PredictionCoreTest.resource(mapper));
        return new PredictionDevelopmentRuntime(mapper, PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json"),
                PredictionDevelopmentRuntime.load(mapper, "prediction-am-calibration-v1.json"), resources,
                ignored -> { throw new AssertionError("A preselected REVEL/reviewed route must never switch to development AM"); }, true);
    }
    private SelectedPredictionRuntime runtime(Hnf1aRevelRuntime revel, ReviewedPredictionBusinessRuntime reviewed, ReviewedPredictionObservationRouter router) {
        return new SelectedPredictionRuntime(mapper, neverDevelopment(), revel, reviewed, router,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> PredictionCoreTest.restrictions(mapper, plan));
    }
    private Hnf1aRevelRuntimeTest existingFixture() {
        var fixture = new Hnf1aRevelRuntimeTest(); fixture.directory = root; return fixture;
    }
    @Test void selectedCandidateWithoutAnyHypothesisRetainsItsResultPosition() {
        var selected = mock(SelectedEvidencePreparation.class);
        when(selected.assembly()).thenReturn(new AssemblyResult(analysis.getAnalysisId(), 1, 1, List.of("selected-without-context"),
                List.of(new CandidateAssembly("selected-without-context", List.of())), List.of(), false));
        var runtime = runtime(Hnf1aRevelRuntime.unavailable(mapper), null, null);
        var output = runtime.execute(runtime.prepare(selected, analysis));
        assertEquals(1, output.path("results").size());
        assertEquals("selected-without-context", output.at("/results/0/plan/candidateId").asText());
        assertEquals("SELECTED_CONTEXT_UNAVAILABLE", output.at("/results/0/criteria/PP3/reasonCode").asText());
        assertEquals(0, output.path("sharedSpliceProviderCalls").asInt());
    }
    @Test void preselectedSubsetNeverSwitchesModelAndBenignProteinLeavesBp4Unresolved() throws Exception {
        var fixture = existingFixture();
        VariantInterpretation candidate = ReflectionTestUtils.invokeMethod(requireNonNull(fixture), "candidate");
        ObjectNode context = ReflectionTestUtils.invokeMethod(requireNonNull(fixture), "context", candidate);
        Hnf1aRevelRuntime revel = ReflectionTestUtils.invokeMethod(requireNonNull(fixture), "runtime", "0.1");
        assertNotNull(candidate); assertNotNull(context); assertNotNull(revel);
        var runtime = runtime(revel, null, null); var batch = runtime.prepare(selected(candidate, context), analysis);
        assertEquals("REVEL_EXISTING_SUBSET", batch.snapshot().at("/plans/0/route").asText()); assertEquals(0, revel.providerCalls());
        var output = runtime.execute(batch);
        assertEquals("BENIGN", output.at("/results/0/mechanisms/PROTEIN_MISSENSE/direction").asText(), output.toPrettyString());
        assertEquals("NOT_EVALUATED", output.at("/results/0/criteria/BP4/status").asText());
        assertEquals("REVEL", output.at("/results/0/mechanismSummary/mechanisms/0/model").asText());
        assertEquals("BENIGN", output.at("/results/0/mechanismSummary/mechanisms/0/direction").asText());
        assertEquals("SPLICEAI_DISABLED", output.at("/results/0/mechanismSummary/mechanisms/1/observationReasonCode").asText());
        assertEquals("EXPLANATION_ONLY", output.at("/results/0/mechanismSummary/purpose").asText());
        assertEquals(1, revel.providerCalls()); assertEquals(0, output.path("developmentProviderCalls").asInt());
        assertEquals(output, runtime.execute(batch)); assertEquals(1, revel.providerCalls());
    }
    @Test void reviewedPreparedModelAndItsBothClausesUseTheExistingRouter() throws Exception {
        var fixture = new ReviewedPredictionBusinessRuntimeTest();
        var provider = fixture.fixtures.new FixtureProvider("0.9", false, false);
        var router = fixture.router(provider, true); var reviewed = fixture.runtime(fixture.document("PP3", "BP4"), router);
        var selected = fixture.confirmed(reviewed);
        var runtime = runtime(Hnf1aRevelRuntime.unavailable(mapper), reviewed, router);
        var batch = runtime.prepare(selected(selected.candidate(), selected.attempt().context()), analysis);
        assertEquals(0, provider.calls);
        var output = runtime.execute(batch);
        assertEquals(1, provider.calls, output.toPrettyString());
        assertEquals("ALPHAMISSENSE", output.at("/results/0/reviewedResult/queriedScoreModels/0").asText());
        assertEquals("ALPHAMISSENSE", output.at("/results/0/mechanismSummary/mechanisms/0/model").asText());
        assertEquals("MET", output.at("/results/0/criteria/PP3/status").asText(), output.toPrettyString());
        assertEquals("NOT_MET", output.at("/results/0/criteria/BP4/status").asText());
    }
    @Test void pairedClauseBridgeRejectsUnhandledStrengthInsteadOfDowngrading() {
        var runtime = runtime(Hnf1aRevelRuntime.unavailable(mapper), null, null);
        var met = mapper.createObjectNode().put("appliedStrength", "MODERATE");
        met.putObject("evaluationResult").put("status", "MET");
        var notMet = mapper.createObjectNode().put("appliedStrength", "NONE");
        notMet.putObject("evaluationResult").put("status", "NOT_MET");
        ObjectNode result = ReflectionTestUtils.invokeMethod(requireNonNull(runtime), "pairedMechanism", met, notMet);
        assertNotNull(result);
        assertEquals("NOT_EVALUATED", result.path("status").asText());
        assertEquals("PREDICTION_PAIRED_STRENGTH_UNSUPPORTED", result.path("reasonCode").asText());
        result = ReflectionTestUtils.invokeMethod(requireNonNull(runtime), "pairedMechanism", notMet, met);
        assertNotNull(result);
        assertEquals("PREDICTION_PAIRED_STRENGTH_UNSUPPORTED", result.path("reasonCode").asText());
    }
    @Test @EnabledIfSystemProperty(named = "varvis.u14.revel.manifest", matches = ".+")
    void fixedRealSubsetScoreWithSyntheticContextAndEffectClaims() throws Exception {
        var fixture = existingFixture();
        VariantInterpretation candidate = ReflectionTestUtils.invokeMethod(requireNonNull(fixture), "candidate");
        ObjectNode context = ReflectionTestUtils.invokeMethod(requireNonNull(fixture), "context", candidate);
        assertNotNull(candidate); assertNotNull(context);
        Instant started = Instant.now();
        var revel = new Hnf1aRevelRuntime(mapper, Path.of(System.getProperty("varvis.u14.revel.manifest")));
        var runtime = runtime(revel, null, null); var output = runtime.execute(runtime.prepare(selected(candidate, context), analysis));
        output.put("validationAttribution", "REAL_LOCAL_SCORE_WITH_SYNTHETIC_CONTEXT_AND_EFFECT_CLAIMS")
                .put("startedAt", started.toString()).put("completedAt", Instant.now().toString());
        Files.writeString(Path.of(System.getProperty("varvis.u14.revel.output")), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        assertEquals("0.888", output.at("/results/0/revelResult/transcriptInput/score").asText(), output.toPrettyString());
        assertEquals("MET", output.at("/results/0/criteria/PP3/status").asText());
        assertEquals("NOT_MET", output.at("/results/0/criteria/BP4/status").asText());
        assertEquals(1, revel.providerCalls());
    }
}
