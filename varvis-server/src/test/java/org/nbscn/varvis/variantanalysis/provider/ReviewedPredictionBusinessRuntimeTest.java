package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Attempt;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner.ContextSeed;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic business routing only; no real score, VEP, reference or clinical acceptance. */
class ReviewedPredictionBusinessRuntimeTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final ExplicitAlphaMissenseDispatcherTest fixtures = new ExplicitAlphaMissenseDispatcherTest();
    final PredictionClauseRuntimeTest policies = new PredictionClauseRuntimeTest();
    final ReviewedPredictionBundleTest bundles = new ReviewedPredictionBundleTest();
    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    ObjectNode document(String... codes) throws Exception {
        var f = fixtures.fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        var base = policies.alpha("0.9");
        var contextFixture = new PredictionClauseRuntimeTest.Fixture(f.candidate(), f.attempt().context(), base.observation());
        var values = new java.util.ArrayList<ObjectNode>();
        for (String code : codes) values.add(policies.policy(contextFixture, code, "ALPHAMISSENSE", "PP3".equals(code) ? "GE" : "LE", "PP3".equals(code) ? "0.792" : "0.0704"));
        return bundles.document(values.toArray(ObjectNode[]::new));
    }
    ReviewedPredictionBusinessRuntime runtime(ObjectNode document, ReviewedPredictionObservationRouter router) {
        return new ReviewedPredictionBusinessRuntime(mapper, new ReviewedPredictionBundle(mapper, document, adapter.canonicalSha256(document)), router.identity());
    }
    ReviewedPredictionObservationRouter router(AlphaMissenseProvider provider, boolean enabled) {
        return new ReviewedPredictionObservationRouter(mapper, provider, enabled,
                () -> { throw new AssertionError("Synthetic AM policy must not run SpliceAI"); }, false, null, false);
    }
    ExplicitAlphaMissenseDispatcher dispatcher(AlphaMissenseProvider provider, ReviewedPredictionBusinessRuntime runtime, ReviewedPredictionObservationRouter router) {
        return new ExplicitAlphaMissenseDispatcher(mapper, provider, () -> { throw new AssertionError("Unexpected raw batch"); },
                () -> Hnf1aRevelRuntime.unavailable(mapper), null, runtime, router);
    }
    ExplicitAlphaMissenseDispatcherTest.Fixture confirmed(ReviewedPredictionBusinessRuntime runtime) throws Exception {
        return confirmed(runtime.contexts().selectionPolicy());
    }
    ExplicitAlphaMissenseDispatcherTest.Fixture confirmed(String selection) throws Exception {
        var f = fixtures.fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        return confirmed(f, selection);
    }
    ExplicitAlphaMissenseDispatcherTest.Fixture confirmed(ExplicitAlphaMissenseDispatcherTest.Fixture f, String selection) throws Exception {
        var tx = f.candidate().getTranscriptAnnotations().get(0);
        f.candidate().applyTranscriptAnnotations(List.of(new org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence(
                tx.getGeneSymbol(), null, tx.getTranscriptAccession(), tx.getHgvsc(), tx.getHgvsp(), tx.getConsequence(),
                tx.getGeneId(), tx.getTranscriptId(), "protein_coding", tx.getImpact(), tx.getExon(), tx.getIntron(), true, true, false,
                tx.getProteinPosition(), tx.getAminoAcids(), tx.getCodons(), null, null, null, "VEP_SYNTHETIC", "COMPLETED", "116.0", java.time.Instant.parse("2026-09-13T00:00:00Z"))));
        ObjectNode draft = f.attempt().seed().draft(); ((ObjectNode) draft.path("transcript")).put("selectionPolicy", selection);
        var seed = new ContextSeed(f.candidate().getCandidateId(), draft);
        var context = new TopNAcmgContextPlanner(mapper).plan(fixtures.analysis, List.of(f.candidate()), 1, List.of(seed)).candidates().get(0).readyContexts().get(0);
        var original = f.attempt();
        return new ExplicitAlphaMissenseDispatcherTest.Fixture(f.candidate(), new Attempt(original.candidateId(), original.hypothesisId(), original.binding(), original.provenance(), seed, context, List.of()));
    }
    @Test void allContextsArePreparedBeforeTheFirstCurrentOrLegacySourceCall() throws Exception {
        var preparations = new java.util.concurrent.atomic.AtomicInteger();
        var provider = fixtures.new FixtureProvider("0.9", false, false) {
            @Override public java.util.Map<org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey, List<org.nbscn.varvis.variantanalysis.domain.EvidenceItem>> annotateSelectedTranscripts(
                    List<org.nbscn.varvis.variantanalysis.domain.VariantInterpretation> variants, org.nbscn.varvis.variantanalysis.domain.AnalysisContext context,
                    java.util.Map<org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence> selected) {
                assertEquals(2, preparations.get(), "Both context plans must predate even a permitted legacy lookup");
                return super.annotateSelectedTranscripts(variants, context, selected);
            }
        };
        var router = router(provider, true); var document = document("PP3", "BP4");
        var bundle = org.mockito.Mockito.spy(new ReviewedPredictionBundle(mapper, document, adapter.canonicalSha256(document)));
        org.mockito.Mockito.doAnswer(invocation -> { preparations.incrementAndGet(); return invocation.callRealMethod(); })
                .when(bundle).prepare(org.mockito.ArgumentMatchers.any(ObjectNode.class));
        var runtime = new ReviewedPredictionBusinessRuntime(mapper, bundle, router.identity());
        var first = confirmed(runtime);
        var second = confirmed(fixtures.fixture(2, "SYNTH1", "HGNC:1", "MONDO:0000004", "ENST000001.4", null), runtime.contexts().selectionPolicy());
        var output = dispatcher(provider, runtime, router).dispatch(fixtures.assembly(List.of(first, second)), List.of(first.candidate(), second.candidate()), fixtures.analysis);
        assertEquals(2, preparations.get()); assertEquals(2, provider.calls, output.toString());
    }
    @Test void missingCompletionTimestampStillRefusesWithoutAnySourceCall() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, true);
        var runtime = runtime(document("PP3", "BP4"), router); var f = confirmed(runtime);
        var original = fixtures.fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        f.candidate().applyTranscriptAnnotations(original.candidate().getTranscriptAnnotations());
        var output = dispatch(dispatcher(provider, runtime, router), f);
        assertEquals(0, provider.calls);
        assertTrue(output.candidates().get(0).attempts().get(0).blockers().stream().anyMatch(b -> "PREDICTION_VERSIONED_ANNOTATION_REQUIRED".equals(b.reasonCode())));
    }
    ExplicitAlphaMissenseDispatcher.DispatchResult dispatch(ExplicitAlphaMissenseDispatcher dispatcher, ExplicitAlphaMissenseDispatcherTest.Fixture f) {
        return dispatcher.dispatch(fixtures.assembly(List.of(f)), List.of(f.candidate()), fixtures.analysis);
    }
    @Test void unavailableBindingIsSavedWithoutAnyLegacyLookup() throws Exception {
        var provider = fixtures.new FixtureProvider("0.99", false, false);
        var runtime = ReviewedPredictionBusinessRuntime.unavailable(mapper, "a".repeat(64), "b".repeat(64));
        var output = dispatch(dispatcher(provider, runtime, null), confirmed(runtime));
        var a = output.candidates().get(0).attempts().get(0);
        assertEquals(0, provider.calls); assertEquals(0, output.providerCalls()); assertEquals(0, output.evaluatorCalls());
        assertEquals("UNAVAILABLE", a.transcriptInput().at("/reviewedPrediction/executionStatus").asText());
        for (String code : ReviewedPredictionBusinessRuntime.CLAUSES)
            assertEquals("PREDICTION_BUSINESS_CONFIGURATION_UNAVAILABLE", a.transcriptInput().at("/reviewedPrediction/clauses/" + code + "/result/reasonCode").asText());
        var artifact = new AcmgBusinessArtifacts(mapper).bundles(output).get(0).path("bundle");
        assertEquals("reviewed-prediction-business-artifact-v1", artifact.path("schemaVersion").asText());
        assertFalse(artifact.path("adoptionSupported").asBoolean(true)); assertFalse(artifact.has("cspecSelection"));
    }
    @Test void partialCatalogEvaluatesOnlyTheUncoveredLegacyClause() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, true);
        var runtime = runtime(document("PP3"), router);
        var output = dispatch(dispatcher(provider, runtime, router), confirmed(runtime));
        var a = output.candidates().get(0).attempts().get(0);
        assertEquals(2, provider.calls, a.blockers().toString()); // one preexisting BP4 input, one selected current PP3 input
        assertEquals(2, output.evaluatorCalls(), a.transcriptInput().toPrettyString());
        JsonNode legacy = a.transcriptInput().path("preexistingPrediction");
        assertEquals(1, legacy.path("criterionEvaluations").size());
        assertEquals("ACMG_AMP_2015:BP4", legacy.at("/criterionEvaluations/0/criterionId").asText());
        assertEquals(1, a.transcriptInput().at("/reviewedPrediction/executionCounts/completedClauseEvaluations").asInt());
        assertEquals(2, a.criterionEvaluations().size());
        assertEquals(1, a.transcriptInput().at("/predictionPreparation/lookupRequests").size());
    }
    @Test void sharedCurrentAmClausesQueryOnceAndNeverRunLegacyPair() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, true);
        var runtime = runtime(document("PP3", "BP4"), router);
        var output = dispatch(dispatcher(provider, runtime, router), confirmed(runtime));
        var a = output.candidates().get(0).attempts().get(0);
        assertEquals(1, provider.calls, a.blockers().toString()); assertEquals(2, output.evaluatorCalls());
        assertEquals(0, a.transcriptInput().at("/preexistingPrediction/evaluatorCalls").asInt());
        assertEquals(1, a.transcriptInput().at("/reviewedPrediction/executionCounts/lookupCallbackAttempts").asInt());
        assertEquals("MET", a.transcriptInput().at("/reviewedPrediction/clauses/PP3/result/evaluation/evaluationResult/status").asText());
    }
    @Test void disabledSourceDoesNotUseLegacyOrAnotherModel() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, false);
        var runtime = runtime(document("PP3", "BP4"), router);
        var output = dispatch(dispatcher(provider, runtime, router), confirmed(runtime));
        assertEquals(0, provider.calls); assertEquals(0, output.evaluatorCalls());
        String text = output.candidates().get(0).attempts().get(0).transcriptInput().toString();
        assertTrue(text.contains("PREDICTION_ALPHAMISSENSE_DISABLED"), text);
    }
    @Test void disabledAmAlsoBlocksPartialAndFullyOutsideLegacyPaths() throws Exception {
        for (boolean allOutside : List.of(false, true)) {
            var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, false);
            var document = document("PP3");
            if (allOutside) { ((ObjectNode) document.path("catalog")).putArray("entries"); bundles.rehash(document); }
            var runtime = runtime(document, router); var output = dispatch(dispatcher(provider, runtime, router), confirmed(runtime));
            assertEquals(0, provider.calls); assertEquals(0, output.providerCalls()); assertEquals(0, output.evaluatorCalls());
            assertTrue(output.candidates().get(0).attempts().get(0).blockers().stream().anyMatch(b -> "PREDICTION_ALPHAMISSENSE_DISABLED".equals(b.reasonCode())));
        }
    }
    @Test void unavailablePreparationKeepsCancellationAndEveryChangedClauseSeal() throws Exception {
        var runtime = ReviewedPredictionBusinessRuntime.unavailable(mapper, "a".repeat(64), "b".repeat(64));
        var f = confirmed(runtime); Thread.currentThread().interrupt();
        var prepared = runtime.prepare(f.attempt().context()); assertTrue(prepared.cancelled()); Thread.interrupted();
        ObjectNode result = runtime.execute(prepared, f.candidate(), request -> fail("No callback"));
        assertEquals("CANCELLED", result.path("executionStatus").asText());
        for (String code : ReviewedPredictionBusinessRuntime.CLAUSES) {
            ObjectNode clause = ((ObjectNode) result.at("/clauses/" + code + "/result")).deepCopy();
            String id = clause.remove("resultId").asText();
            assertEquals("prediction-result-sha256-" + adapter.canonicalSha256(clause), id);
            assertEquals("PREDICTION_BUNDLE_INTERRUPTED", clause.at("/evaluationResult/reasonCode").asText());
        }
        assertEquals(prepared.snapshot().path("selectionPlans"), result.path("selectionPlans"));
    }
    @Test void currentThreeSpliceClausesAndMultipleContextsShareOneRawBatch() throws Exception {
        runSharedSplice(false, true);
    }
    @Test void fullyOutsideLegacySpliceRetainsOneActualProviderInvocation() throws Exception {
        runSharedSplice(true, true);
    }
    @Test void disabledSpliceAlsoPreventsFullyOutsideLegacySupplierInvocation() throws Exception {
        runSharedSplice(true, false);
    }
    void runSharedSplice(boolean outside, boolean enabled) throws Exception {
        var f = policies.splice("0.01", 7);
        var pp3 = policies.policy(f, "PP3", "SPLICEAI", "GE", "0.2");
        var bp4 = policies.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ((com.fasterxml.jackson.databind.node.ArrayNode) pp3.at("/scope/transcripts")).addObject().put("accession", "ENST000002").put("version", 4);
        ((com.fasterxml.jackson.databind.node.ArrayNode) bp4.at("/scope/transcripts")).addObject().put("accession", "ENST000002").put("version", 4);
        var bp7 = policies.bp7Policy(f, bp4); ((ObjectNode) bp7.path("scope")).set("transcripts", bp4.at("/scope/transcripts").deepCopy());
        ObjectNode document = bundles.document(pp3, bp4, bp7);
        if (outside) { ((ObjectNode) document.path("catalog")).putArray("entries"); bundles.rehash(document); }
        var rawCalls = new java.util.concurrent.atomic.AtomicInteger(); var rawQueries = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<SpliceAiBusinessRuntime> raw = () -> new SpliceAiBusinessRuntime(mapper, queries -> {
            rawCalls.incrementAndGet(); rawQueries.addAndGet(queries.size()); ObjectNode result = mapper.createObjectNode(); var rows = result.putArray("results");
            for (var q : queries) {
                var row = rows.addObject().put("queryId", q.getQueryId()).put("status", "HIT").put("exactAlleleMatch", true); var key = q.getKey();
                row.putObject("allele").put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome()).put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
                var a = row.putArray("annotations").addObject().put("geneSymbol", "SYNTH1").put("maximumRawScore", "0.01");
                var ds = a.putObject("deltaScores"); var dp = a.putObject("deltaPositions");
                for (String name : List.of("AG", "AL", "DG", "DL")) { ds.put("DS_" + name, "0.01"); dp.put("DP_" + name, "0"); }
            }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(result, 0);
        });
        var provider = fixtures.new FixtureProvider("0.9", false, false);
        var router = new ReviewedPredictionObservationRouter(mapper, provider, true, raw, enabled, null, false);
        var runtime = runtime(document, router); var splice = new SpliceAiBusinessDispatchTest();
        var first = confirmed(splice.fixture(1, 7), runtime.contexts().selectionPolicy());
        var secondRaw = fixtures.fixture(2, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000002.4", null); splice.setTranscript(secondRaw, 7);
        var second = confirmed(secondRaw, runtime.contexts().selectionPolicy());
        var output = new ExplicitAlphaMissenseDispatcher(mapper, provider, raw, () -> Hnf1aRevelRuntime.unavailable(mapper), null, runtime, router)
                .dispatch(fixtures.assembly(List.of(first, second)), List.of(first.candidate(), second.candidate()), fixtures.analysis);
        assertEquals(enabled ? 1 : 0, rawCalls.get(), output.toString()); assertEquals(enabled ? 1 : 0, rawQueries.get());
        assertEquals(enabled ? 1 : 0, output.providerCalls()); assertEquals(0, provider.calls);
        if (!enabled) { assertEquals(0, output.evaluatorCalls()); return; }
        assertEquals(outside ? 4 : 6, output.evaluatorCalls());
        for (var row : output.candidates()) {
            var a = row.attempts().get(0); assertTrue(a.blockers().isEmpty(), a.blockers().toString());
            assertEquals(3, a.criterionEvaluations().size());
            assertEquals("MET", a.criterionEvaluations().stream().filter(e -> "ACMG_AMP_2015:BP7".equals(e.path("criterionId").asText())).findFirst().orElseThrow().at("/evaluationResult/status").asText());
            assertEquals("DISPATCH_FINISHED", a.transcriptInput().at("/sourceSessionAudit/snapshotStage").asText());
            assertEquals(1, a.transcriptInput().at("/sourceSessionAudit/rawBatchInvocations").asInt());
            assertEquals(outside ? 0 : 2, a.transcriptInput().at("/sourceSessionAudit/callbackInvocations").asInt());
        }
    }
    @Test void preInterruptedCallBlocksEvenOutsideCatalogFallback() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, true);
        ObjectNode document = document("PP3"); ((ObjectNode) document.path("catalog")).putArray("entries"); bundles.rehash(document);
        var runtime = runtime(document, router); var f = confirmed(runtime);
        Thread.currentThread().interrupt();
        var output = dispatch(dispatcher(provider, runtime, router), f);
        assertEquals(0, provider.calls); assertEquals(0, output.evaluatorCalls());
        assertEquals("CANCELLED", output.candidates().get(0).attempts().get(0).transcriptInput().at("/reviewedPrediction/executionStatus").asText());
    }
    @Test void wrongCapabilityCannotPromoteContextOrRunScores() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, true);
        var runtime = runtime(document("PP3", "BP4"), router);
        var output = dispatch(dispatcher(provider, runtime, router), confirmed("REVIEWED_PREDICTION_CONTEXT@1.0.0:" + "a".repeat(64)));
        assertEquals(0, provider.calls); assertNull(output.candidates().get(0).attempts().get(0).context());
        assertTrue(output.candidates().get(0).attempts().get(0).blockers().stream().anyMatch(b -> "PREDICTION_CONTEXT_CAPABILITY_NOT_CONFIRMED".equals(b.reasonCode())));
    }
    @Test void populationOnlyConfirmationRemainsSeparate() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false); var router = router(provider, true);
        var runtime = runtime(document("PP3", "BP4"), router);
        var output = dispatch(dispatcher(provider, runtime, router), confirmed(org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION));
        assertEquals(0, provider.calls); assertEquals("PM2_CONTEXT_ONLY", output.candidates().get(0).attempts().get(0).transcriptInput().path("predictionPath").asText());
        assertFalse(output.candidates().get(0).attempts().get(0).transcriptInput().has("reviewedPrediction"));
    }
    @Test void callbackCancellationRetainsActualCallAndStopsEvaluation() throws Exception {
        var provider = fixtures.new FixtureProvider("0.9", false, false) {
            @Override public java.util.Map<org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey, List<org.nbscn.varvis.variantanalysis.domain.EvidenceItem>> annotateSelectedTranscripts(
                    List<org.nbscn.varvis.variantanalysis.domain.VariantInterpretation> variants, org.nbscn.varvis.variantanalysis.domain.AnalysisContext context,
                    java.util.Map<org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence> selected) {
                var result = super.annotateSelectedTranscripts(variants, context, selected); Thread.currentThread().interrupt(); return result;
            }
        };
        var router = router(provider, true); var runtime = runtime(document("PP3", "BP4"), router);
        var output = dispatch(dispatcher(provider, runtime, router), confirmed(runtime));
        assertEquals(1, provider.calls); assertEquals(1, output.providerCalls()); assertEquals(0, output.evaluatorCalls());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals("CANCELLED", output.candidates().get(0).attempts().get(0).transcriptInput().at("/reviewedPrediction/executionStatus").asText());
    }
    @Test void executionProfileNullCompatibilityAndCapabilityIntegrity() {
        var unavailable = ReviewedPredictionBusinessRuntime.unavailable(mapper, "a".repeat(64), "b".repeat(64));
        var old = new AcmgExecutionProfile(mapper).document();
        assertEquals(old, new AcmgExecutionProfile(mapper, null, null, null, null, null, null).document());
        var current = new AcmgExecutionProfile(mapper, null, null, null, null, unavailable.identity(), unavailable.contexts().identity()).document();
        assertEquals(old, current.path("baseExecutionProfile")); assertFalse(current.path("adoptionSupported").asBoolean(true));
        assertThrows(IllegalArgumentException.class, () -> new AcmgExecutionProfile(mapper, null, null, null, null, unavailable.identity(), mapper.createObjectNode()));
    }
    @Test void amMaskRejectsCrossFamilyAndEmptyMeansZeroEvaluation() throws Exception {
        var f = policies.alpha("0.9"); var evaluator = new AlphaMissenseCriterionEvaluator(mapper);
        assertEquals(List.of(), evaluator.evaluateClauses(f.context(), (ObjectNode) f.observation().path("evidence"),
                AlphaMissenseCriterionEvaluator.loadProfile(mapper), AlphaMissenseCriterionEvaluator.loadSuccessorRegistry(mapper), "", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluateClauses(f.context(), (ObjectNode) f.observation().path("evidence"),
                AlphaMissenseCriterionEvaluator.loadProfile(mapper), AlphaMissenseCriterionEvaluator.loadSuccessorRegistry(mapper), "", Set.of("ACMG_AMP_2015:BS3")));
    }
    @Test void partialLegacyBp7DoesNotLookUpOrSecretlyReevaluateCoveredBp4() throws Exception {
        var f = policies.splice("0.05", 7);
        var pp3 = policies.policy(f, "PP3", "SPLICEAI", "GE", "0.2");
        var bp4 = policies.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode document = bundles.document(pp3, bp4);
        // Both current clauses have an explicit unsupported extra requirement: no score is allowed.
        for (JsonNode p : document.path("policies")) ((ObjectNode) p).put("unknownOperator", true);
        for (int i = 0; i < 2; i++) ((ObjectNode) document.at("/catalog/entries/" + i + "/policyBinding"))
                .put("policySha256", adapter.canonicalSha256(document.path("policies").get(i)));
        bundles.rehash(document);
        var provider = fixtures.new FixtureProvider("0.9", false, false);
        var rawCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<SpliceAiBusinessRuntime> raw = () -> new SpliceAiBusinessRuntime(mapper, queries -> {
            rawCalls.incrementAndGet(); return SpliceAiRawBatchRunner.BatchResult.failed("UNEXPECTED_SYNTHETIC_CALL", null);
        });
        var router = new ReviewedPredictionObservationRouter(mapper, provider, true, raw, true, null, false);
        var runtime = runtime(document, router);
        var splice = new SpliceAiBusinessDispatchTest();
        var input = confirmed(splice.fixture(1, 7), runtime.contexts().selectionPolicy());
        var output = dispatch(new ExplicitAlphaMissenseDispatcher(mapper, provider, raw, () -> Hnf1aRevelRuntime.unavailable(mapper), null, runtime, router), input);
        assertEquals(0, rawCalls.get()); assertEquals(0, provider.calls); assertEquals(0, output.evaluatorCalls());
        assertTrue(output.candidates().get(0).attempts().get(0).blockers().stream().anyMatch(b -> "BP7_PREEXISTING_BP4_POLICY_NOT_ALLOWED".equals(b.reasonCode())), output.toString());
    }
}
