package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpliceAiBatchingTest {
    @TempDir Path temporary;
    final NativeSpliceAiRuntimeTest helper = new NativeSpliceAiRuntimeTest();

    List<NativeSpliceAiRuntimeTest.Fixture> inputs(int count) throws Exception {
        helper.temporary = temporary;
        var base = helper.input(); var inputs = new ArrayList<NativeSpliceAiRuntimeTest.Fixture>();
        for (int index = 0; index < count; index++) {
            int position = 20000 + index; var context = base.context().deepCopy();
            for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
                context.withObject("/variant/" + field).put("position", position);
            context.withObject("/variant").put("alleleId", helper.f.adapter.canonicalAlleleId(
                    context.at("/variant/evidenceQueryAllele"), context.at("/variant/normalization")));
            context.put("contextId", helper.f.adapter.canonicalContextId(context));
            var observation = (ObjectNode) helper.f.mapper.valueToTree(base.candidate().getObservation());
            observation.withObject("/key").put("position", position);
            var candidate = new VariantInterpretation("batch-" + index, helper.f.mapper.treeToValue(observation, VariantQcObservation.class), List.of(), List.of());
            candidate.applyTranscriptAnnotations(base.candidate().getTranscriptAnnotations());
            inputs.add(new NativeSpliceAiRuntimeTest.Fixture(context, candidate, base.model()));
        }
        return inputs;
    }

    ObjectNode response(ObjectNode request, List<NativeSpliceAiRuntimeTest.Fixture> inputs) {
        var out = helper.f.mapper.createObjectNode().put("status", "SUCCEEDED").put("annotationFileSha256", "a".repeat(64));
        var rows = out.putArray("results");
        // Reverse transport order to prove association uses the query identity, not row order.
        for (int i = request.path("variants").size() - 1; i >= 0; i--) {
            var unit = request.path("variants").get(i); int position = unit.at("/allele/position").asInt();
            var input = inputs.get(position - 20000); var row = helper.fact(input).deepCopy();
            row.set("queryId", unit.path("queryId")); row.withObject("/referenceWindow").put("start", position - 9999);
            if (position % 2 == 1) row.withObject("/annotations/0/deltaScores").put("DS_AG", "0.50");
            rows.add(row);
        }
        return out;
    }

    NativeSpliceAiRuntime runtime(List<NativeSpliceAiRuntimeTest.Fixture> inputs, java.util.function.Function<ObjectNode, ObjectNode> run) throws Exception {
        return runtime(inputs, Duration.ofSeconds(540), System::nanoTime, (request, budget) -> run.apply(request));
    }

    NativeSpliceAiRuntime runtime(List<NativeSpliceAiRuntimeTest.Fixture> inputs, Duration budget, java.util.function.LongSupplier clock,
            java.util.function.BiFunction<ObjectNode, Duration, ObjectNode> run) throws Exception {
        var first = inputs.get(0);
        return new NativeSpliceAiRuntime(helper.f.mapper, c -> first.model(),
                (c, s, n) -> new CodingTranscriptMaterialSupply.ReferenceSpan(first.candidate().getObservation().getKey().getReference().repeat(n),
                        helper.f.mapper.createObjectNode().put("expectedSha256", first.context().at("/variant/normalization/referenceSequenceSha256").asText())),
                helper.guard("HGNC:99999"), helper.f.mapper.createObjectNode(), budget, clock, run);
    }

    List<NativeSpliceAiRuntime.Plan> plans(NativeSpliceAiRuntime runtime, List<NativeSpliceAiRuntimeTest.Fixture> inputs) {
        return inputs.stream().map(i -> runtime.prepare(i.context(), i.candidate(), false)).toList();
    }

    @ParameterizedTest @ValueSource(ints = {1, 10, 11, 20, 21, 50})
    void allSupportedUnitsAreBatchedAndAssociatedOnce(int count) throws Exception {
        var inputs = inputs(count); var requests = new ArrayList<ObjectNode>();
        var runtime = runtime(inputs, request -> { requests.add(request.deepCopy()); return response(request, inputs); });
        var plans = plans(runtime, inputs); runtime.compute(plans);
        assertEquals((count + 9) / 10, requests.size());
        assertEquals(count, requests.stream().mapToInt(r -> r.path("variants").size()).sum());
        assertTrue(requests.stream().allMatch(r -> r.path("variants").size() <= 10));
        for (int i = 0; i < count; i++) assertEquals(i % 2 == 0 ? "BENIGN" : "HARMFUL",
                runtime.evaluate(plans.get(i)).at("/mechanism/direction").asText(), "unit " + i);
        var before = runtime.audit(); runtime.compute(plans);
        assertEquals(before, runtime.audit());
    }

    @Test void duplicateContextsAcrossChunkBoundaryDoNotRepeatModelWork() throws Exception {
        var inputs = inputs(11); var requests = new ArrayList<ObjectNode>();
        var runtime = runtime(inputs, r -> { requests.add(r.deepCopy()); return response(r, inputs); });
        var plans = new ArrayList<>(plans(runtime, inputs)); plans.add(10, plans.get(0)); plans.add(plans.get(1));
        runtime.compute(plans); assertEquals(List.of(10, 1), requests.stream().map(r -> r.path("variants").size()).toList());
        assertEquals(runtime.evaluate(plans.get(0)), runtime.evaluate(plans.get(10)));
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "DUPLICATE", "ALLELE", "FOREIGN_BATCH", "HASH", "NULL", "EXCEPTION"})
    void failedMiddleBatchCannotEraseEarlierOrPoisonLaterResults(String fault) throws Exception {
        var inputs = inputs(21); var requests = new ArrayList<ObjectNode>();
        var runtime = runtime(inputs, request -> {
            requests.add(request.deepCopy()); var out = response(request, inputs);
            if (requests.size() == 2) {
                var rows = (com.fasterxml.jackson.databind.node.ArrayNode) out.path("results");
                switch (fault) {
                    case "MISSING" -> rows.remove(0);
                    case "DUPLICATE" -> rows.set(1, rows.get(0).deepCopy());
                    case "ALLELE" -> ((ObjectNode) rows.get(0)).withObject("/allele").put("position", 20000);
                    case "FOREIGN_BATCH" -> rows.set(0, response(requests.get(0), inputs).at("/results/0"));
                    case "HASH" -> ((ObjectNode) rows.get(0)).put("annotationFileSha256", "b".repeat(64));
                    case "NULL" -> { return null; }
                    case "EXCEPTION" -> throw new IllegalArgumentException("broken transport");
                }
            }
            return out;
        });
        var plans = plans(runtime, inputs); runtime.compute(plans); assertEquals(3, requests.size());
        for (int i = 0; i < 21; i++) assertEquals(i >= 10 && i < 20 ? "NOT_EVALUATED" : "CALIBRATED",
                runtime.evaluate(plans.get(i)).at("/mechanism/status").asText(), "unit " + i);
        var audit = helper.f.mapper.readTree(runtime.audit().path("batchJson").asText());
        assertEquals("PARTIAL", audit.path("status").asText()); assertEquals(11, audit.path("acceptedUnits").asInt());
        runtime.compute(plans); assertEquals(3, requests.size());
    }

    @Test void oneTotalDeadlineRejectsLateBatchAndNeverLaunchesRemainingBatch() throws Exception {
        var inputs = inputs(21); var clock = new java.util.concurrent.atomic.AtomicLong(); var budgets = new ArrayList<Long>();
        var runtime = runtime(inputs, Duration.ofMillis(100), clock::get, (request, budget) -> {
            budgets.add(budget.toMillis()); clock.addAndGet(Duration.ofMillis(budgets.size() == 1 ? 30 : 80).toNanos());
            return response(request, inputs);
        });
        var plans = plans(runtime, inputs); runtime.compute(plans);
        assertEquals(List.of(100L, 70L), budgets);
        assertEquals("CALIBRATED", runtime.evaluate(plans.get(0)).at("/mechanism/status").asText());
        for (int i = 10; i < 21; i++) assertEquals("NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED",
                runtime.evaluate(plans.get(i)).at("/mechanism/reasonCode").asText());
        runtime.compute(plans); assertEquals(2, budgets.size());
    }

    @ParameterizedTest @ValueSource(strings = {"NATIVE_SPLICE_TIMEOUT", "NATIVE_PROCESS_CLEANUP_UNCONFIRMED", "NATIVE_SPLICE_INTERRUPTED", "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED"})
    void timeoutCancellationOrUnconfirmedCleanupStopsLaterProcesses(String reason) throws Exception {
        var inputs = inputs(21); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var runtime = runtime(inputs, request -> calls.incrementAndGet() == 1 ? response(request, inputs)
                : helper.f.mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", reason));
        var plans = plans(runtime, inputs); runtime.compute(plans); assertEquals(2, calls.get());
        assertEquals("CALIBRATED", runtime.evaluate(plans.get(0)).at("/mechanism/status").asText());
        assertEquals(reason, runtime.evaluate(plans.get(20)).at("/mechanism/reasonCode").asText());
    }

    @Test void preInterruptedAndEmptySelectionsNeverStartProcesses() throws Exception {
        var inputs = inputs(11); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var runtime = runtime(inputs, r -> { calls.incrementAndGet(); return response(r, inputs); });
        var plans = plans(runtime, inputs);
        try {
            Thread.currentThread().interrupt(); runtime.compute(plans); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(0, calls.get());
        assertEquals("NATIVE_SPLICE_INTERRUPTED", runtime.evaluate(plans.get(0)).at("/mechanism/reasonCode").asText());
        var empty = runtime(inputs, r -> { calls.incrementAndGet(); return null; }); empty.compute(List.of()); assertEquals(0, calls.get());
    }

    @Test void blockedUnitsDoNotConsumeSlotsAndBudgetChangesIdentity() throws Exception {
        var inputs = inputs(11); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var runtime = runtime(inputs, r -> { calls.incrementAndGet(); return response(r, inputs); });
        var plans = new ArrayList<>(plans(runtime, inputs));
        plans.add(runtime.prepare(inputs.get(0).context(), inputs.get(0).candidate(), true));
        runtime.compute(plans); assertEquals(2, calls.get());
        assertEquals("PREDICTION_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION", runtime.evaluate(plans.get(11)).at("/mechanism/reasonCode").asText());
        var other = runtime(inputs, Duration.ofSeconds(539), System::nanoTime, (r,b) -> response(r, inputs));
        assertNotEquals(runtime.audit().path("identity"), other.audit().path("identity"));
        assertThrows(IllegalArgumentException.class, () -> other.compute(List.of(plans.get(0))));
    }

    @Test void completeBp4UsesOnlyItsOwnSuccessfullyFinishedChunk() throws Exception {
        var inputs = inputs(11); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var runtime = runtime(inputs, r -> calls.incrementAndGet() == 1 ? response(r, inputs)
                : helper.f.mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "NATIVE_SPLICE_PROCESS_FAILED"));
        var plans = plans(runtime, inputs); runtime.compute(plans);
        for (int index : List.of(0, 1, 10)) {
            var mechanisms = helper.f.mapper.createObjectNode();
            mechanisms.putObject("PROTEIN_MISSENSE").put("status", "CALIBRATED").put("direction", "BENIGN").put("appliedStrength", "SUPPORTING");
            mechanisms.set("SPLICING", runtime.evaluate(plans.get(index)).path("mechanism"));
            var policy = PredictionDevelopmentRuntime.load(helper.f.mapper, "prediction-core-policy-v1.json");
            policy.set("context", inputs.get(index).context()); policy.set("effectRestrictions", PredictionCoreTest.restrictions(helper.f.mapper, policy));
            var applied = PredictionMechanismApplication.apply(helper.f.mapper, policy, mechanisms, null);
            assertEquals(index == 0 ? "MET" : index == 1 ? "NOT_MET" : "NOT_EVALUATED", applied.at("/BP4/status").asText());
        }
    }
}
