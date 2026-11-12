package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bp2TestMaterials.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class Bp2PenetranceR1Test {
    private interface Mutation { void apply(ObjectNode r) throws Exception; }
    private record Vector(String name, String reason, Mutation mutation) { }
    private static ObjectNode restricted() throws Exception { return (ObjectNode) M.readTree(resource("r1/restricted-bundle.json")); }
    private static ObjectNode condition(ObjectNode r, int index) { return object(observation(r), "/diseaseApplicability/penetrance/conditions/" + index); }
    private static ObjectNode patient(ObjectNode r, int index) { return object(condition(r, index), "/patient"); }
    private static ObjectNode rule(ObjectNode r, int index) { return object(condition(r, index), "/constraint"); }
    private static void minimal(ObjectNode result, String reason) {
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result::toPrettyString);
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText());
        assertEquals("UNKNOWN", result.at("/criterionApplicability/status").asText());
        for (String field : List.of("evidence", "evaluation", "evaluationId", "evidenceId", "appliedStrength", "deterministicTrace", "bp2Points", "pm3Points")) assertFalse(result.has(field));
        assertEquals(3, result.at("/criterionEvaluations/0").size());
    }
    @TestFactory Stream<DynamicTest> independentlyExpectedConditionMatrix() {
        var cases = new ArrayList<Vector>();
        cases.add(new Vector("at-boundary-18", "MET", r -> { }));
        cases.add(new Vector("above-boundary-30", "MET", r -> patient(r, 1).put("value", 30)));
        cases.add(new Vector("zero-boundary", "MET", r -> { rule(r, 1).put("threshold", 0); patient(r, 1).put("value", 0); }));
        cases.add(new Vector("age-8", "BP2_PENETRANCE_AGE_NOT_SATISFIED", r -> patient(r, 1).put("value", 8)));
        cases.add(new Vector("age-unknown", "BP2_PENETRANCE_PATIENT_FACT_UNKNOWN", r -> patient(r, 1).put("status", "UNKNOWN").putNull("value")));
        cases.add(new Vector("age-null-known", "BP2_PENETRANCE_AGE_VALUE_INVALID", r -> patient(r, 1).putNull("value")));
        for (long value : new long[] {-1, 151, 4294967314L}) cases.add(new Vector("age-invalid-" + value, "BP2_PENETRANCE_AGE_VALUE_INVALID", r -> patient(r, 1).put("value", value)));
        cases.add(new Vector("threshold-negative", "BP2_PENETRANCE_AGE_VALUE_INVALID", r -> rule(r, 1).put("threshold", -1)));
        cases.add(new Vector("age-months", "BP2_PENETRANCE_AGE_PROFILE_UNSUPPORTED", r -> patient(r, 1).put("unit", "MONTHS")));
        cases.add(new Vector("threshold-months", "BP2_PENETRANCE_AGE_PROFILE_UNSUPPORTED", r -> rule(r, 1).put("unit", "MONTHS")));
        cases.add(new Vector("operator-unsupported", "BP2_PENETRANCE_AGE_PROFILE_UNSUPPORTED", r -> rule(r, 1).put("operator", "LT")));
        cases.add(new Vector("sex-not-in-set", "BP2_PENETRANCE_SEX_NOT_SATISFIED", r -> rule(r, 2).putArray("values").add("FEMALE")));
        cases.add(new Vector("patient-context-sex-conflict", "BP2_PENETRANCE_CONTEXT_SEX_CONFLICT", r -> patient(r, 2).put("value", "FEMALE")));
        cases.add(new Vector("sex-unknown", "BP2_PENETRANCE_PATIENT_FACT_UNKNOWN", r -> patient(r, 2).put("value", "UNKNOWN")));
        cases.add(new Vector("sex-invalid-condition", "BP2_PENETRANCE_SEX_CONDITION_INVALID", r -> rule(r, 2).putArray("values").add("UNKNOWN")));
        cases.add(new Vector("population-B", "BP2_PENETRANCE_POPULATION_NOT_SATISFIED", r -> patient(r, 0).put("populationId", "TEST_ONLY_POP_B")));
        cases.add(new Vector("population-unknown", "BP2_PENETRANCE_PATIENT_FACT_UNKNOWN", r -> patient(r, 0).put("populationId", "UNKNOWN")));
        cases.add(new Vector("population-wrong-system", "BP2_PENETRANCE_POPULATION_SYSTEM_CONFLICT", r -> patient(r, 0).put("populationSystem", "TEST_ONLY_OTHER_VERSION")));
        cases.add(new Vector("population-case-not-folded", "BP2_PENETRANCE_POPULATION_NOT_SATISFIED", r -> patient(r, 0).put("populationId", "test_only_pop_a")));
        cases.add(new Vector("population-no-trim", "BP2_PENETRANCE_POPULATION_NOT_SATISFIED", r -> patient(r, 0).put("populationId", "TEST_ONLY_POP_A ")));
        cases.add(new Vector("population-NFC", "MET", r -> { rule(r, 0).putArray("populationIds").add("TEST_ONLY_é"); patient(r, 0).put("populationId", "TEST_ONLY_e\u0301"); }));
        cases.add(new Vector("population-NFC-collision", "BP2_PENETRANCE_POPULATION_CONDITION_INVALID", r -> rule(r, 0).putArray("populationIds").add("TEST_ONLY_é").add("TEST_ONLY_e\u0301")));
        for (int i : List.of(0, 1, 2)) cases.add(new Vector("restricted-label-unrestricted-" + i, "BP2_PENETRANCE_REVIEW_CONTRADICTION", r -> condition(r, i).put("conclusion", "REVIEWED_UNRESTRICTED")));
        for (String field : List.of("patientContextId", "caseId", "familyContextId", "hgncId", "mondoId", "moi", "targetAlleleId", "partnerAlleleId"))
            cases.add(new Vector("patient-binding-" + field, "BP2_PENETRANCE_PATIENT_BINDING_CONFLICT", r -> object(patient(r, 1), "/binding").put(field, "different")));
        for (String field : List.of("mechanism", "materialVersion")) cases.add(new Vector("patient-" + field, "BP2_PENETRANCE_PATIENT_BINDING_CONFLICT", r -> patient(r, 1).put(field, "other")));
        return cases.stream().map(v -> DynamicTest.dynamicTest(v.name(), () -> {
            ObjectNode input = restricted(); v.mutation().apply(input); reissue(input, "diseaseApplicability");
            ObjectNode before = input.deepCopy(), result = evaluate(input); artifact("r1-" + v.name(), input, result); assertEquals(before, input);
            if (v.reason().equals("MET")) { assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result::toPrettyString); assertEquals("SUPPORTING", result.at("/evaluation/appliedStrength").asText()); assertEquals("BENIGN", result.path("direction").asText()); assertEquals(result, evaluate(input)); }
            else minimal(result, v.reason());
        }));
    }
    @Test void exactFixedRestrictedBytesAndIndependentExpected() throws Exception {
        byte[] bytes = resource("r1/restricted-bundle.json"); var pins = M.readTree(resource("r1/restricted-pins.json"));
        var expected = M.readTree(resource("r1/restricted-expected.json"));
        ObjectNode result = new Bp2PhaseCriterionEvaluator(M).evaluate(Bp2PhaseBundle.load(M, bytes, pins.path("rawSha256").asText(), pins.path("canonicalSha256").asText()));
        assertEquals(expected.path("status"), result.at("/evaluation/evaluationResult/status")); assertEquals(expected.path("strength"), result.at("/evaluation/appliedStrength"));
        assertEquals(expected.path("contextId"), result.at("/evaluation/contextId")); artifact("r1-fixed-restricted", restricted(), result);
    }
    @Test void legacyTransRefusedAndCisPreserved() throws Exception {
        try (var stream = getClass().getResourceAsStream("/bp2-phase-core/beta-bundle.json")) {
            assertNotNull(stream); ObjectNode old = (ObjectNode) M.readTree(stream.readAllBytes());
            ObjectNode result = evaluate(old); minimal(result, "BP2_PENETRANCE_CONDITIONS_NOT_ESTABLISHED"); artifact("r1-old-beta", old, result);
        }
        for (String tag : List.of("alpha", "gamma", "beta")) assertTrue(evaluate(base(tag)).has("evaluation"), tag);
    }
    @Test void unchangedPinsAndEmbeddedReportsRemainBinding() throws Exception {
        ObjectNode r = restricted(); patient(r, 1).put("value", 8); source(object(observation(r), "/diseaseApplicability"));
        minimal(evaluate(r), "BP2_PART_PIN_MISMATCH"); artifact("r1-stale-review", r, evaluate(r));
        r = restricted(); patient(r, 1).put("value", 8); pinsOnly(r); minimal(evaluate(r), "BP2_SOURCE_FACTS_MISMATCH"); artifact("r1-stale-source", r, evaluate(r));
    }
    @Test void unrestrictedStructureCannotCarryHiddenLimits() throws Exception {
        ObjectNode r = restricted(); rule(r, 1).put("mode", "UNRESTRICTED"); condition(r, 1).put("conclusion", "REVIEWED_UNRESTRICTED"); reissue(r, "diseaseApplicability");
        var error = assertThrows(IllegalArgumentException.class, () -> load(r)); assertTrue(error.getMessage().startsWith("BP2_UNKNOWN_FIELD_"));
        ObjectNode clear = base("beta"); condition(clear, 1).put("conclusion", "SATISFIED"); reissue(clear, "diseaseApplicability"); minimal(evaluate(clear), "BP2_PENETRANCE_REVIEW_CONTRADICTION");
    }
}
