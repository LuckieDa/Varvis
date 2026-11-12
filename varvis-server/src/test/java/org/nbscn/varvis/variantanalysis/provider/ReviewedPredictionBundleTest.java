package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Lookup sequencing and immutable transport tests; source fixtures are synthetic, not clinical policies. */
class ReviewedPredictionBundleTest {
    @Test void automaticallySuppliedModelFillsOnlyTheExactPreselectedBinding() throws Exception {
        var input = new RevelPredictionObservationTest().input("0.99", "GE", "0.7");
        var f = input.fixture();
        var bundle = load(document(input.policy())); // No embedded transcript model.
        assertTrue(bundle.prepare(f.context()).lookupRequests().isEmpty());
        ObjectNode model = (ObjectNode) f.observation().path("transcriptModel");
        var prepared = bundle.prepare(f.context(), model);
        assertEquals(1, prepared.lookupRequests().size());
        assertStatus(bundle.execute(prepared, f.candidate(), request -> f.observation()), "PP3", "MET");
        ObjectNode wrong = model.deepCopy().put("modelVersion", "other");
        assertTrue(bundle.prepare(f.context(), wrong).lookupRequests().isEmpty());
        // Prepared input owns its copy, including the supplied technical model.
        model.removeAll(); assertFalse(prepared.lookupRequests().get(0).snapshot().path("transcriptModel").isEmpty());
    }
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final PredictionClauseRuntimeTest base = new PredictionClauseRuntimeTest();
    @TempDir Path temporary;

    @Test void splitPreparationAndOldApiProduceByteIdenticalResults() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        var bundle = load(document(base.policy(f, "PP3", "SPLICEAI", "GE", "0.2"), bp4, base.bp7Policy(f, bp4)));
        var prepared = bundle.prepare(f.context());
        ObjectNode snapshot = prepared.snapshot();
        assertEquals("PREPARED", snapshot.path("preparationStatus").asText());
        for (String field : List.of("providerCalls", "evaluatorCalls", "storedVepProjections"))
            assertEquals(0, snapshot.path(field).asInt());
        assertEquals(0, snapshot.path("savedInputs").size());
        assertEquals(0, snapshot.path("lookups").size());
        assertEquals(1, prepared.lookupRequests().size());
        assertEquals(3, snapshot.path("selectedPolicies").size());
        assertEquals(3, snapshot.path("policySelections").size());
        AtomicInteger calls = new AtomicInteger();
        ObjectNode split = bundle.execute(prepared, f.candidate(), request -> {
            calls.incrementAndGet();
            assertEquals(snapshot.path("selectionPlans"), request.snapshot().path("allSelectionPlans"));
            for (String code : List.of("PP3", "BP4", "BP7"))
                assertEquals("READY", request.snapshot().at("/allSelectionPlans/" + code + "/status").asText());
            return f.observation();
        });
        assertEquals(1, calls.get());
        assertEquals(snapshot, prepared.snapshot());
        assertArrayEquals(mapper.writeValueAsBytes(bundle.evaluate(f.context(), f.candidate(), request -> f.observation())),
                mapper.writeValueAsBytes(split));
        assertStatus(split, "BP7", "MET"); assertSealed(split);
        ObjectNode failedSplit = bundle.execute(prepared, f.candidate(), request -> { throw new java.io.IOException("synthetic"); });
        assertArrayEquals(mapper.writeValueAsBytes(bundle.evaluate(f.context(), f.candidate(), request -> { throw new java.io.IOException("synthetic"); })),
                mapper.writeValueAsBytes(failedSplit));
    }

    @Test void preparedContextPoliciesModelsAndReturnedSnapshotsCannotChangeTheQuery() throws Exception {
        var input = new RevelPredictionObservationTest().input("0.99", "GE", "0.7");
        var f = input.fixture();
        ObjectNode document = document(input.policy());
        JsonNode model = f.observation().path("transcriptModel");
        ((ObjectNode) document.at("/materials/transcriptModels")).set(adapter.canonicalSha256(model), model.deepCopy());
        var bundle = load(document);
        ObjectNode externalContext = f.context().deepCopy();
        var prepared = bundle.prepare(externalContext);
        ObjectNode original = prepared.snapshot();
        var request = prepared.lookupRequests().get(0);
        ObjectNode expectedRequest = request.snapshot();
        externalContext.removeAll();
        document.removeAll();
        input.policy().removeAll();
        prepared.snapshot().removeAll();
        ObjectNode edited = request.snapshot();
        ((ObjectNode) edited.path("transcriptModel")).put("modelVersion", "UNREVIEWED_MUTATION");
        ((ObjectNode) edited.path("resourceBinding")).put("resourceVersion", "OTHER");
        ((ObjectNode) edited.path("context")).removeAll();
        assertEquals(expectedRequest, prepared.lookupRequests().get(0).snapshot());
        assertThrows(UnsupportedOperationException.class, () -> prepared.lookupRequests().clear());
        ObjectNode result = bundle.execute(prepared, f.candidate(), actual -> {
            assertEquals(expectedRequest, actual.snapshot()); return f.observation();
        });
        assertStatus(result, "PP3", "MET");
        assertEquals(original, prepared.snapshot());
    }

    @Test void preparedObjectCannotBeBorrowedByAnotherBundleEvenWithIdenticalIdentity() throws Exception {
        var f = base.alpha("0.99");
        ObjectNode document = document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792"));
        var owner = load(document); var prepared = owner.prepare(f.context());
        var equalButDifferentOwner = load(document);
        assertEquals(owner.identity(), equalButDifferentOwner.identity());
        AtomicInteger calls = new AtomicInteger();
        var failure = assertThrows(IllegalArgumentException.class, () -> equalButDifferentOwner.execute(prepared, f.candidate(), request -> {
            calls.incrementAndGet(); return f.observation();
        }));
        assertEquals("PREDICTION_PREPARED_BUNDLE_MISMATCH", failure.getMessage());
        document.put("bundleVersion", "changed");
        assertThrows(IllegalArgumentException.class, () -> load(document).execute(prepared, f.candidate(), request -> {
            calls.incrementAndGet(); return f.observation();
        }));
        assertEquals(0, calls.get());
        assertStatus(owner.execute(prepared, f.candidate(), request -> f.observation()), "PP3", "MET");
    }

    @Test void repeatedPreparedExecutionsHaveIndependentCountsAndNoImplicitObservationCache() throws Exception {
        var f = base.alpha("0.99");
        var bundle = load(document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792")));
        var prepared = bundle.prepare(f.context());
        ObjectNode snapshot = prepared.snapshot();
        AtomicInteger calls = new AtomicInteger();
        ReviewedPredictionBundle.ObservationProvider provider = request -> { calls.incrementAndGet(); return f.observation(); };
        ObjectNode first = bundle.execute(prepared, f.candidate(), provider);
        ObjectNode expected = first.deepCopy(); first.removeAll();
        ObjectNode second = bundle.execute(prepared, f.candidate(), provider);
        assertEquals(expected, second); assertEquals(2, calls.get());
        assertEquals(1, second.at("/executionCounts/lookupCallbackAttempts").asInt());
        assertEquals(1, second.at("/executionCounts/completedClauseEvaluations").asInt());
        assertEquals(snapshot, prepared.snapshot());
        ObjectNode absent = bundle.execute(prepared, f.candidate(), null);
        assertEquals(0, absent.path("providerCalls").asInt());
        assertEquals("PREDICTION_BUNDLE_PROVIDER_UNAVAILABLE", absent.at("/clauses/PP3/result/evaluationResult/reasonCode").asText());
    }

    @Test void preparedResourceIdentitiesRemainSeparateBeforeAnyExecution() throws Exception {
        var f = base.alpha("0.99");
        ObjectNode pp3 = base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ObjectNode bp4 = base.policy(f, "BP4", "ALPHAMISSENSE", "LE", "0.169");
        ((ObjectNode) bp4.path("resourceBinding")).put("resourceVersion", "independent-resource");
        var bundle = load(document(pp3, bp4)); var prepared = bundle.prepare(f.context());
        assertEquals(2, prepared.lookupRequests().size());
        assertNotEquals(prepared.lookupRequests().get(0).lookupId(), prepared.lookupRequests().get(1).lookupId());
        ObjectNode result = bundle.execute(prepared, f.candidate(), request -> f.observation());
        assertEquals(2, result.path("providerCalls").asInt());
        assertStatus(result, "PP3", "MET");
        assertEquals("PREDICTION_ATOMIC_RESOURCE_MISMATCH", result.at("/clauses/BP4/result/evaluationResult/reasonCode").asText());
    }

    @Test void cancellationCapturedAtPrepareEntryCannotBeClearedIntoExecution() throws Exception {
        var f = base.alpha("0.99");
        var bundle = load(document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792")));
        ObjectNode clearsFlagDuringCopy = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance) {
            private static final long serialVersionUID = 1L;
            @Override public ObjectNode deepCopy() {
                Thread.interrupted(); return super.deepCopy();
            }
        };
        clearsFlagDuringCopy.setAll(f.context());
        try {
            Thread.currentThread().interrupt();
            var prepared = bundle.prepare(clearsFlagDuringCopy);
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(prepared.cancelled());
            assertTrue(prepared.lookupRequests().isEmpty());
            assertEquals("CANCELLED", prepared.snapshot().path("preparationStatus").asText());
            for (String code : List.of("PP3", "BP4", "BP7"))
                assertFalse(ReviewedPredictionBundle.usesPreexistingClause(prepared.snapshot().at("/clauses/" + code)));
            ObjectNode result = bundle.execute(prepared, f.candidate(), request -> fail("Cancelled preparation cannot lookup"));
            assertEquals("CANCELLED", result.path("executionStatus").asText());
            assertEquals(0, result.at("/executionCounts/lookupCallbackAttempts").asInt());
            assertEquals(0, result.at("/executionCounts/completedClauseEvaluations").asInt());
            assertSealed(result);
        } finally { Thread.interrupted(); }
    }

    @Test void newExecutionInterruptionDoesNotCancelTheReusablePreparedObject() throws Exception {
        var f = base.alpha("0.99");
        var bundle = load(document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792")));
        var prepared = bundle.prepare(f.context()); ObjectNode snapshot = prepared.snapshot();
        try {
            Thread.currentThread().interrupt();
            ObjectNode result = bundle.execute(prepared, f.candidate(), request -> fail("Interrupted execute cannot lookup"));
            assertCancelled(result, 0);
        } finally { Thread.interrupted(); }
        assertFalse(prepared.cancelled()); assertEquals(snapshot, prepared.snapshot());
        assertStatus(bundle.execute(prepared, f.candidate(), request -> f.observation()), "PP3", "MET");
    }

    @Test void interruptedEntryMakesNoLookupAndNeverRoutesToPreexistingExecution() throws Exception {
        var f = base.alpha("0.99");
        var bundle = load(document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792")));
        AtomicInteger calls = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            ObjectNode result = bundle.evaluate(f.context(), f.candidate(), request -> {
                calls.incrementAndGet(); return f.observation();
            });
            assertEquals(0, calls.get());
            assertCancelled(result, 0);
            assertEquals(0, result.path("queriedScoreModels").size());
            assertEquals(0, result.path("savedInputs").size());
            assertEquals("READY", result.at("/selectionPlans/PP3/status").asText());
            for (String code : List.of("PP3", "BP4", "BP7"))
                assertFalse(ReviewedPredictionBundle.usesPreexistingClause(result.at("/clauses/" + code)));
        } finally { Thread.interrupted(); }
    }

    @Test void interruptingHighScoreCallbackStopsOtherModelAndAllThreeEvaluations() throws Exception {
        var alpha = base.alpha("0.99"); var splice = base.splice("0.01", 7);
        ObjectNode pp3 = base.policy(alpha, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ObjectNode bp4 = base.policy(splice, "BP4", "SPLICEAI", "LT", "0.1");
        var bundle = load(document(pp3, bp4, base.bp7Policy(splice, bp4)));
        // Establish that the same high-score source is actually evaluable before cancellation.
        ObjectNode control = bundle.evaluate(alpha.context(), alpha.candidate(), request -> alpha.observation());
        assertStatus(control, "PP3", "MET");
        AtomicInteger calls = new AtomicInteger();
        try {
            ObjectNode result = bundle.evaluate(alpha.context(), alpha.candidate(), request -> {
                calls.incrementAndGet();
                assertEquals("ALPHAMISSENSE", request.preselectedModel());
                for (String code : List.of("PP3", "BP4", "BP7"))
                    assertEquals("READY", request.snapshot().at("/allSelectionPlans/" + code + "/status").asText());
                Thread.currentThread().interrupt(); return alpha.observation();
            });
            assertEquals(1, calls.get());
            assertCancelled(result, 1);
            assertEquals(2, result.path("lookupRequests").size());
            assertEquals(1, result.path("queriedScoreModels").size());
            assertEquals("INTERRUPTED", result.at("/lookups/0/status").asText());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptedExceptionRestoresFlagAndStopsSubsequentModel() throws Exception {
        var alpha = base.alpha("0.99"); var splice = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(splice, "BP4", "SPLICEAI", "LT", "0.1");
        var bundle = load(document(base.policy(alpha, "PP3", "ALPHAMISSENSE", "GE", "0.792"), bp4, base.bp7Policy(splice, bp4)));
        AtomicInteger calls = new AtomicInteger();
        try {
            ObjectNode result = bundle.evaluate(alpha.context(), alpha.candidate(), request -> {
                calls.incrementAndGet(); throw new InterruptedException("synthetic callback cancellation");
            });
            assertEquals(1, calls.get());
            assertCancelled(result, 1);
            assertEquals(0, result.path("savedInputs").size());
            assertEquals("INTERRUPTED", result.at("/lookups/0/status").asText());
        } finally { Thread.interrupted(); }
    }

    @Test void sharedSpliceObservationReturnedAfterInterruptIsOnlyAnInputAudit() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        var bundle = load(document(base.policy(f, "PP3", "SPLICEAI", "GE", "0.2"), bp4, base.bp7Policy(f, bp4)));
        try {
            ObjectNode result = bundle.evaluate(f.context(), f.candidate(), request -> {
                Thread.currentThread().interrupt(); return f.observation();
            });
            assertCancelled(result, 1);
            assertEquals(1, result.path("lookupRequests").size());
            assertEquals(f.observation(), result.path("savedInputs").elements().next());
            assertEquals("INTERRUPTED", result.at("/lookups/0/status").asText());
            assertEquals(adapter.canonicalSha256(f.observation()), result.at("/lookups/0/observationSha256").asText());
            f.observation().removeAll();
            assertTrue(result.path("savedInputs").elements().next().has("evidence"));
        } finally { Thread.interrupted(); }
    }

    @Test void revelCancellationPreservesThePinnedNativeInputWithoutEvaluatingIt() throws Exception {
        var input = new RevelPredictionObservationTest().input("0.99", "GE", "0.7");
        var f = input.fixture();
        ObjectNode d = document(input.policy());
        JsonNode model = f.observation().path("transcriptModel");
        ((ObjectNode) d.at("/materials/transcriptModels")).set(adapter.canonicalSha256(model), model.deepCopy());
        var bundle = load(d);
        assertStatus(bundle.evaluate(f.context(), f.candidate(), request -> f.observation()), "PP3", "MET");
        try {
            ObjectNode result = bundle.evaluate(f.context(), f.candidate(), request -> {
                assertEquals("REVEL", request.preselectedModel());
                assertEquals(model, request.snapshot().path("transcriptModel"));
                Thread.currentThread().interrupt(); return f.observation();
            });
            assertCancelled(result, 1);
            assertEquals(f.observation(), result.path("savedInputs").elements().next());
            assertEquals("INTERRUPTED", result.at("/lookups/0/status").asText());
        } finally { Thread.interrupted(); }
    }

    @Test void secondCallbackInterruptRetainsBothActualAttemptsAndEarlierSavedInput() throws Exception {
        var alpha = base.alpha("0.99"); var splice = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(splice, "BP4", "SPLICEAI", "LT", "0.1");
        var bundle = load(document(base.policy(alpha, "PP3", "ALPHAMISSENSE", "GE", "0.792"), bp4, base.bp7Policy(splice, bp4)));
        try {
            ObjectNode result = bundle.evaluate(alpha.context(), alpha.candidate(), request -> {
                if ("ALPHAMISSENSE".equals(request.preselectedModel())) return alpha.observation();
                Thread.currentThread().interrupt(); throw new java.io.IOException("interrupted transport");
            });
            assertCancelled(result, 2);
            assertEquals(2, result.path("queriedScoreModels").size());
            assertEquals(1, result.path("savedInputs").size());
            assertEquals(alpha.observation(), result.path("savedInputs").elements().next());
            assertEquals("OBSERVED", result.at("/lookups/0/status").asText());
            assertEquals("INTERRUPTED", result.at("/lookups/1/status").asText());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptionDuringClauseKeepsCompletedWorkButDoesNotStartLaterClauses() throws Exception {
        var f = base.alpha("0.99");
        ObjectNode pp3 = base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ObjectNode bp4 = base.policy(f, "BP4", "ALPHAMISSENSE", "LE", "0.169");
        try {
            ObjectNode result = load(document(pp3, bp4)).evaluate(f.context(), interruptingCandidate(f.candidate()), request -> f.observation());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("CANCELLED", result.path("executionStatus").asText());
            assertStatus(result, "PP3", "MET");
            assertEquals(1, result.at("/executionCounts/lookupCallbackAttempts").asInt());
            assertEquals(1, result.at("/executionCounts/completedClauseEvaluations").asInt());
            assertEquals(1, result.at("/executionCounts/storedVepProjections").asInt());
            assertEquals(0, result.at("/executionCounts/dependencyRevalidationCalls").asInt());
            for (String code : List.of("BP4", "BP7"))
                assertEquals("PREDICTION_BUNDLE_INTERRUPTED", result.at("/clauses/" + code + "/result/evaluationResult/reasonCode").asText());
            assertSealed(result);
        } finally { Thread.interrupted(); }
    }

    @Test void completedBp4IsRetainedButCancellationPreventsBp7DependencyRevalidation() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        try {
            ObjectNode result = load(document(bp4, base.bp7Policy(f, bp4)))
                    .evaluate(f.context(), interruptingCandidate(f.candidate()), request -> f.observation());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("CANCELLED", result.path("executionStatus").asText());
            assertStatus(result, "BP4", "MET");
            assertEquals("READY", result.at("/selectionPlans/BP7/status").asText());
            assertEquals("PREDICTION_BUNDLE_INTERRUPTED", result.at("/clauses/BP7/result/evaluationResult/reasonCode").asText());
            assertEquals(1, result.at("/executionCounts/lookupCallbackAttempts").asInt());
            assertEquals(1, result.at("/executionCounts/completedClauseEvaluations").asInt());
            assertEquals(1, result.at("/executionCounts/storedVepProjections").asInt());
            assertEquals(0, result.at("/executionCounts/dependencyRevalidationCalls").asInt());
            assertSealed(result);
        } finally { Thread.interrupted(); }
    }

    VariantInterpretation interruptingCandidate(VariantInterpretation source) {
        return new VariantInterpretation(source.getCandidateIdentity(), source.getObservation(), source.getConsequences(), source.getEvidence()) {
            @Override public List<TranscriptConsequence> getTranscriptAnnotations() {
                // Cancellation during an existing provider-free evaluation's stored VEP projection.
                Thread.currentThread().interrupt(); return source.getTranscriptAnnotations();
            }
        };
    }

    @Test void ordinaryFirstModelIOExceptionStillAllowsIndependentBp4AndDependentBp7() throws Exception {
        var alpha = base.alpha("0.99"); var splice = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(splice, "BP4", "SPLICEAI", "LT", "0.1");
        var bundle = load(document(base.policy(alpha, "PP3", "ALPHAMISSENSE", "GE", "0.792"), bp4, base.bp7Policy(splice, bp4)));
        AtomicInteger calls = new AtomicInteger();
        ObjectNode result = bundle.evaluate(splice.context(), splice.candidate(), request -> {
            calls.incrementAndGet();
            if ("ALPHAMISSENSE".equals(request.preselectedModel())) throw new java.io.IOException("synthetic source failure");
            return splice.observation();
        });
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(2, calls.get()); assertEquals(2, result.path("providerCalls").asInt());
        assertEquals("COMPLETE", result.path("executionStatus").asText());
        assertEquals("PREDICTION_BUNDLE_PROVIDER_FAILED", result.at("/clauses/PP3/result/evaluationResult/reasonCode").asText());
        assertStatus(result, "BP4", "MET"); assertStatus(result, "BP7", "MET");
        assertEquals(2, result.at("/executionCounts/completedClauseEvaluations").asInt());
        assertEquals(1, result.at("/executionCounts/dependencyRevalidationCalls").asInt());
        assertSealed(result);
    }

    void assertCancelled(ObjectNode result, int calls) {
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals("CANCELLED", result.path("executionStatus").asText());
        assertEquals("PREDICTION_BUNDLE_INTERRUPTED", result.path("reasonCode").asText());
        assertEquals(calls, result.path("providerCalls").asInt());
        assertEquals(calls, result.at("/executionCounts/lookupCallbackAttempts").asInt());
        for (String field : List.of("completedClauseEvaluations", "storedVepProjections", "dependencyRevalidationCalls",
                "dependencyProviderCalls", "dependencyStoredVepProjections")) assertEquals(0, result.at("/executionCounts/" + field).asInt());
        for (String code : List.of("PP3", "BP4", "BP7"))
            assertEquals("PREDICTION_BUNDLE_INTERRUPTED", result.at("/clauses/" + code + "/result/evaluationResult/reasonCode").asText());
        assertSealed(result);
    }

    @Test void allClausesAreSelectedBeforeOneSharedLookupAndBp7UsesCurrentBp4() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode pp3 = base.policy(f, "PP3", "SPLICEAI", "GE", "0.2");
        ObjectNode bp4 = base.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        var bundle = load(document(pp3, bp4, base.bp7Policy(f, bp4)));
        AtomicInteger calls = new AtomicInteger();
        ObjectNode result = bundle.evaluate(f.context(), f.candidate(), request -> {
            assertEquals(0, calls.getAndIncrement());
            assertEquals("SPLICEAI", request.preselectedModel());
            ObjectNode snapshot = request.snapshot();
            for (String c : List.of("PP3", "BP4", "BP7")) assertEquals("READY", snapshot.at("/allSelectionPlans/" + c + "/status").asText());
            assertEquals(2, snapshot.path("plannedClauses").size());
            snapshot.removeAll();
            assertTrue(request.snapshot().has("context"));
            return f.observation();
        });
        assertEquals(1, calls.get()); assertEquals(1, result.path("providerCalls").asInt());
        assertEquals(1, result.path("savedInputs").size()); assertEquals(1, result.path("queriedScoreModels").size());
        assertStatus(result, "PP3", "NOT_MET"); assertStatus(result, "BP4", "MET"); assertStatus(result, "BP7", "MET");
        assertEquals(result.at("/clauses/BP4/result"), result.at("/clauses/BP7/result/bp4Dependency"));
        assertEquals(0, result.at("/clauses/BP7/result/dependencyProviderCalls").asInt());
        assertEquals(1, result.at("/clauses/BP7/result/independentEvidenceCount").asInt());
        f.observation().removeAll();
        assertTrue(result.at("/clauses/BP4/result/observation").has("evidence"));
        assertEquals(PredictionClauseRuntime.EVALUATOR_VERSION,
                bundle.identity().at("/algorithms/" + PredictionClauseRuntime.EVALUATOR_ID).asText());
        assertEquals(ReviewedPredictionBundle.ORCHESTRATOR_VERSION, bundle.identity().at("/orchestrator/version").asText());
        assertEquals(1, result.at("/executionCounts/lookupCallbackAttempts").asInt());
        assertEquals(3, result.at("/executionCounts/completedClauseEvaluations").asInt());
        assertEquals(3, result.at("/executionCounts/storedVepProjections").asInt());
        assertEquals(1, result.at("/executionCounts/dependencyRevalidationCalls").asInt());
        assertEquals(1, result.at("/executionCounts/dependencyStoredVepProjections").asInt());
        assertEquals(0, result.at("/executionCounts/dependencyProviderCalls").asInt());
        assertSealed(result);
    }

    @Test void greyScoresAndMissingScoresNeverChooseAnotherModel() throws Exception {
        for (String value : List.of("0.5", "")) {
            var f = base.alpha("0.5");
            ObjectNode pp3 = base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
            ObjectNode bp4 = base.policy(f, "BP4", "ALPHAMISSENSE", "LE", "0.169");
            if (value.isEmpty()) ((ObjectNode) f.observation().path("evidence")).putArray("measurements");
            AtomicInteger calls = new AtomicInteger();
            ObjectNode result = load(document(pp3, bp4)).evaluate(f.context(), f.candidate(), request -> {
                calls.incrementAndGet(); assertEquals("ALPHAMISSENSE", request.preselectedModel()); return f.observation();
            });
            assertEquals(1, calls.get());
            if (value.isEmpty()) assertEquals("NOT_EVALUATED", result.at("/clauses/PP3/result/status").asText());
            else { assertStatus(result, "PP3", "NOT_MET"); assertStatus(result, "BP4", "NOT_MET"); }
        }
    }

    @Test void refusedSelectionMakesZeroQueriesAndOnlyValidUncoveredClauseIsPreexisting() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode document = document(base.policy(f, "BP4", "SPLICEAI", "LT", "0.1"));
        ((ObjectNode) document.at("/catalog/entries/0")).put("disposition", "SPECIALIZED_UNIMPLEMENTED");
        rehash(document);
        ObjectNode result = load(document).evaluate(f.context(), f.candidate(), request -> fail("No selected policy may lookup"));
        assertEquals(0, result.path("providerCalls").asInt());
        assertTrue(ReviewedPredictionBundle.usesPreexistingClause(result.at("/clauses/PP3")));
        assertFalse(ReviewedPredictionBundle.usesPreexistingClause(result.at("/clauses/BP4")));
        document.put("catalogSha256", "f".repeat(64));
        ObjectNode invalid = load(document).evaluate(f.context(), f.candidate(), request -> fail("Invalid catalog may not lookup"));
        for (String code : List.of("PP3", "BP4", "BP7")) assertFalse(ReviewedPredictionBundle.usesPreexistingClause(invalid.at("/clauses/" + code)));
    }

    @Test void bp7AloneDoesNotTriggerMissingBp4OrScoreLookup() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        ObjectNode result = load(document(base.bp7Policy(f, bp4))).evaluate(f.context(), f.candidate(), request -> fail("BP7 never looks up"));
        assertEquals(0, result.path("providerCalls").asInt());
        assertEquals(0, result.at("/executionCounts/completedClauseEvaluations").asInt());
        assertEquals(0, result.at("/executionCounts/dependencyRevalidationCalls").asInt());
        assertSealed(result);
        assertEquals("PREDICTION_BUNDLE_CURRENT_BP4_REQUIRED", result.at("/clauses/BP7/result/evaluationResult/reasonCode").asText());
    }

    @Test void bp7RejectsWrongPinnedDependencyAndNeverMakesASecondLookup() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(f, "BP4", "SPLICEAI", "LT", "0.1"), bp7 = base.bp7Policy(f, bp4);
        ((ObjectNode) bp7.path("bp7Dependency")).put("policySha256", "f".repeat(64));
        ObjectNode result = load(document(bp4, bp7)).evaluate(f.context(), f.candidate(), request -> f.observation());
        assertStatus(result, "BP4", "MET"); assertEquals(1, result.path("providerCalls").asInt());
        assertEquals("PREDICTION_BP4_POLICY_BINDING_MISMATCH", result.at("/clauses/BP7/result/evaluationResult/reasonCode").asText());
    }

    @Test void differentModelsDoNotExchangeObservationsAndOneFailureDoesNotBlockOtherClause() throws Exception {
        var alpha = base.alpha("0.99"); var splice = base.splice("0.01", 7);
        ObjectNode pp3 = base.policy(alpha, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ObjectNode bp4 = base.policy(splice, "BP4", "SPLICEAI", "LT", "0.1");
        // Both declared policies use the same verified context. The source observation
        // itself must still match; a callback cannot swap one predictor for another.
        ObjectNode result = load(document(pp3, bp4)).evaluate(alpha.context(), alpha.candidate(), request -> {
            if ("SPLICEAI".equals(request.preselectedModel())) throw new java.io.IOException("Synthetic source failure");
            return alpha.observation();
        });
        assertEquals(2, result.path("providerCalls").asInt()); assertEquals(2, result.path("queriedScoreModels").size());
        assertStatus(result, "PP3", "MET");
        assertEquals("PREDICTION_BUNDLE_PROVIDER_FAILED", result.at("/clauses/BP4/result/evaluationResult/reasonCode").asText());
        result = load(document(pp3, bp4)).evaluate(alpha.context(), alpha.candidate(), request -> alpha.observation());
        assertStatus(result, "PP3", "MET");
        assertEquals("PREDICTION_ATOMIC_RESOURCE_MISMATCH", result.at("/clauses/BP4/result/evaluationResult/reasonCode").asText());
    }

    @Test void distinctResourceIdentityMakesTwoLookupsEvenForTheSameModel() throws Exception {
        var f = base.alpha("0.99");
        ObjectNode pp3 = base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792");
        ObjectNode bp4 = base.policy(f, "BP4", "ALPHAMISSENSE", "LE", "0.169");
        ((ObjectNode) bp4.path("resourceBinding")).put("resourceVersion", "separately-pinned-version");
        ObjectNode result = load(document(pp3, bp4)).evaluate(f.context(), f.candidate(), request -> f.observation());
        assertEquals(2, result.path("providerCalls").asInt()); assertEquals(1, result.path("queriedScoreModels").size());
        assertStatus(result, "PP3", "MET");
        assertEquals("NOT_EVALUATED", result.at("/clauses/BP4/result/status").asText());
    }

    @Test void observedConflictsRemainDeclaredAndPreventBp4AndBp7() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode bp4 = base.policy(f, "BP4", "SPLICEAI", "LT", "0.1");
        f.observation().putArray("knownConflicts").add("EXPERIMENTAL_RNA_DISCORDANCE");
        ObjectNode result = load(document(bp4, base.bp7Policy(f, bp4))).evaluate(f.context(), f.candidate(), request -> f.observation());
        assertEquals("PREDICTION_OBSERVED_DISCORDANCE_REQUIRES_REVIEW", result.at("/clauses/BP4/result/evaluationResult/reasonCode").asText());
        assertEquals("PREDICTION_BUNDLE_CURRENT_BP4_REQUIRED", result.at("/clauses/BP7/result/evaluationResult/reasonCode").asText());
        assertEquals(f.observation(), result.path("savedInputs").elements().next());
    }

    @Test void missingNativeModelPreventsRevelLookupAndChangedModelCannotBeReturned() throws Exception {
        var test = new RevelPredictionObservationTest(); var input = test.input("0.99", "GE", "0.7");
        ObjectNode document = document(input.policy());
        ObjectNode result = load(document).evaluate(input.fixture().context(), input.fixture().candidate(), request -> fail("Missing model must precede lookup"));
        assertEquals(0, result.path("providerCalls").asInt());
        assertEquals("PREDICTION_BUNDLE_NATIVE_MODEL_UNAVAILABLE", result.at("/clauses/PP3/result/evaluationResult/reasonCode").asText());
        JsonNode model = input.fixture().observation().path("transcriptModel");
        ((ObjectNode) document.at("/materials/transcriptModels")).set(adapter.canonicalSha256(model), model);
        result = load(document).evaluate(input.fixture().context(), input.fixture().candidate(), request -> {
            ObjectNode changed = input.fixture().observation().deepCopy();
            ((ObjectNode) changed.path("transcriptModel")).put("modelVersion", "other"); return changed;
        });
        assertEquals("PREDICTION_BUNDLE_OBSERVATION_MODEL_MISMATCH", result.at("/clauses/PP3/result/evaluationResult/reasonCode").asText());
    }

    @Test void nativeModelCanonicalBindingParticipatesInReuseKey() throws Exception {
        var test = new RevelPredictionObservationTest(); var input = test.input("0.99", "GE", "0.7");
        ObjectNode pp3 = input.policy(), bp4 = pp3.deepCopy().put("policyId", "separate-revel-bp4");
        bp4.putArray("criterionIds").add(PredictionClausePolicy.BP4);
        bp4.putObject("threshold").put("operator", "LE").put("decimal", "0.1").put("unit", "score");
        ObjectNode model = (ObjectNode) input.fixture().observation().path("transcriptModel");
        ObjectNode second = model.deepCopy().put("modelVersion", "2");
        ((ObjectNode) bp4.path("transcriptModelBinding")).put("modelVersion", "2").put("canonicalSha256", adapter.canonicalSha256(second));
        ObjectNode document = document(pp3, bp4);
        ObjectNode materials = (ObjectNode) document.at("/materials/transcriptModels");
        materials.set(adapter.canonicalSha256(model), model); materials.set(adapter.canonicalSha256(second), second);
        ObjectNode result = load(document).evaluate(input.fixture().context(), input.fixture().candidate(), request -> input.fixture().observation());
        assertEquals(2, result.path("providerCalls").asInt());
        assertEquals("PREDICTION_BUNDLE_OBSERVATION_MODEL_MISMATCH", result.at("/clauses/BP4/result/evaluationResult/reasonCode").asText());
    }

    @Test void bundleUnknownConditionsDraftsAndMissingActivationCannotBeIgnored() throws Exception {
        var f = base.alpha("0.9"); ObjectNode document = document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792"));
        ObjectNode changed = document.deepCopy(); changed.put("additionalRequirements", "unimplemented");
        assertThrows(IllegalArgumentException.class, () -> load(changed));
        ObjectNode hidden = document.deepCopy(); ((ObjectNode) hidden.path("materials")).put("alternativeModelFallback", true);
        assertThrows(IllegalArgumentException.class, () -> load(hidden));
        ObjectNode draft = document.deepCopy(); ((ObjectNode) draft.path("review")).put("status", "DRAFT");
        assertThrows(IllegalArgumentException.class, () -> load(draft));
        ObjectNode enabled = document.deepCopy(); ((ObjectNode) enabled.path("activation")).put("automaticTrigger", true);
        assertThrows(IllegalArgumentException.class, () -> load(enabled));
        ObjectNode missing = document.deepCopy(); ((ObjectNode) missing.path("activation")).remove("automaticTrigger");
        assertThrows(IllegalArgumentException.class, () -> load(missing));
    }

    @Test void fileAndCanonicalHashAreSeparateAndDuplicateJsonKeysAreRejected() throws Exception {
        var f = base.alpha("0.9"); ObjectNode document = document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792"));
        Path path = temporary.resolve("bundle.json"); byte[] bytes = mapper.writeValueAsBytes(document); Files.write(path, bytes);
        String fileSha = sha(bytes), canonical = adapter.canonicalSha256(document);
        assertEquals(load(document).identity(), ReviewedPredictionBundle.load(mapper, path, fileSha, canonical).identity());
        assertThrows(IllegalArgumentException.class, () -> ReviewedPredictionBundle.load(mapper, path, "f".repeat(64), canonical));
        assertThrows(IllegalArgumentException.class, () -> ReviewedPredictionBundle.load(mapper, path, fileSha, "f".repeat(64)));
        byte[] duplicate = "{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, duplicate);
        assertThrows(java.io.IOException.class, () -> ReviewedPredictionBundle.load(mapper, path, sha(duplicate), canonical));
    }

    @Test void savedBundleIdentityIsDeterministicAndFailuresDoNotCountAsCompletedEvaluations() throws Exception {
        var f = base.alpha("0.99");
        ObjectNode document = document(base.policy(f, "PP3", "ALPHAMISSENSE", "GE", "0.792"));
        ObjectNode result = load(document).evaluate(f.context(), f.candidate(), request -> f.observation());
        assertEquals(result, load(document).evaluate(f.context(), f.candidate(), request -> f.observation()));
        assertEquals(result, mapper.readTree(mapper.writeValueAsBytes(result)));
        assertSealed(result);
        ObjectNode failed = load(document).evaluate(f.context(), f.candidate(), request -> { throw new java.io.IOException("source failed"); });
        assertEquals(1, failed.at("/executionCounts/lookupCallbackAttempts").asInt());
        assertEquals(0, failed.at("/executionCounts/completedClauseEvaluations").asInt());
        assertEquals(0, failed.at("/executionCounts/storedVepProjections").asInt());
        assertEquals(0, failed.at("/executionCounts/dependencyRevalidationCalls").asInt());
        assertNotEquals(result.path("resultId"), failed.path("resultId"));
        assertSealed(failed);
        document.put("bundleVersion", "new-reviewed-snapshot");
        assertNotEquals(result.path("resultId"), load(document).evaluate(f.context(), f.candidate(), request -> f.observation()).path("resultId"));
    }

    void assertSealed(ObjectNode result) {
        ObjectNode content = result.deepCopy(); content.remove("resultId");
        assertEquals("prediction-bundle-result-sha256-" + adapter.canonicalSha256(content), result.path("resultId").asText());
    }

    ObjectNode document(ObjectNode... policies) {
        ObjectNode d = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-bundle-v1").put("bundleId", "synthetic-prediction-bundle").put("bundleVersion", "1");
        d.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "SYNTHETIC_REVIEW");
        d.putObject("activation").put("automaticTrigger", false).put("classificationEnabled", false);
        d.putObject("materials").putObject("transcriptModels");
        ArrayNode ps = d.putArray("policies");
        ObjectNode c = d.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "synthetic-catalog").put("catalogVersion", "1").put("searchedAt", "2026-09-13T14:00:00Z")
                .put("searchScope", "Synthetic transport scope; no clinical coverage");
        c.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review");
        c.set("sources", policies[0].path("sources").deepCopy());
        ArrayNode entries = c.putArray("entries");
        for (ObjectNode p : policies) {
            ps.add(p.deepCopy());
            ObjectNode e = entries.addObject().put("entryId", p.path("policyId").asText())
                    .put("criterionId", p.at("/criterionIds/0").asText()).put("searchStatus", "COMPLETE")
                    .put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "Synthetic separately reviewed clause");
            e.set("scope", p.path("scope").deepCopy()); e.putArray("sourceIds").add("synthetic-source");
            e.putObject("review").put("status", "REVIEWED").put("reviewId", "synthetic-review");
            e.putObject("policyBinding").put("policyId", p.path("policyId").asText()).put("policyVersion", p.path("policyVersion").asText())
                    .put("policySha256", adapter.canonicalSha256(p));
        }
        rehash(d); return d;
    }
    void rehash(ObjectNode document) { document.put("catalogSha256", adapter.canonicalSha256(document.path("catalog"))); }
    ReviewedPredictionBundle load(ObjectNode document) { return new ReviewedPredictionBundle(mapper, document, adapter.canonicalSha256(document)); }
    void assertStatus(ObjectNode result, String code, String expected) {
        assertEquals(expected, result.at("/clauses/" + code + "/result/evaluation/evaluationResult/status").asText(), result.toPrettyString());
    }
    String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
