package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlphaMissenseCriterionEvaluatorTest {

    private static final String SELECTION_ID = "cspec-selection-sha256-"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private ObjectMapper mapper;
    private RuntimeEvidenceV2Adapter adapter;
    private AlphaMissenseCriterionEvaluator evaluator;
    private ObjectNode profile;
    private ObjectNode registry;
    private ObjectNode context;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        evaluator = new AlphaMissenseCriterionEvaluator(mapper);
        profile = AlphaMissenseCriterionEvaluator.loadProfile(mapper);
        registry = AlphaMissenseCriterionEvaluator.loadSuccessorRegistry(mapper);
        try (InputStream input = getClass()
                .getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            context = (ObjectNode) mapper.readTree(input).path("context").deepCopy();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0.169,NOT_MET,MET",
            "0.170,NOT_MET,NOT_MET",
            "0.791,NOT_MET,NOT_MET",
            "0.792,MET,NOT_MET",
            "0.001,NOT_MET,MET",
            "0.999,MET,NOT_MET"
    })
    void appliesOnlyTheFrozenSupportingBoundaries(String score,
                                                   String pp3Status,
                                                   String bp4Status) {
        ObjectNode atomic = atomic(hitCarrier(score, true, true,
                selectedTranscript(), "K34R", "P12345"));

        List<ObjectNode> evaluations = evaluate(atomic);
        ObjectNode pp3 = criterion(evaluations, "ACMG_AMP_2015:PP3");
        ObjectNode bp4 = criterion(evaluations, "ACMG_AMP_2015:BP4");

        assertEvaluation(pp3, pp3Status);
        assertEvaluation(bp4, bp4Status);
        assertFalse("MET".equals(pp3Status) && "MET".equals(bp4Status));
        assertTrue(evaluations.stream()
                .filter(value -> "MET".equals(value.path("evaluationResult")
                        .path("status").asText()))
                .count() <= 1);
        assertEquals(score, parameter(pp3, "score.raw").path("value").asText());
        assertEquals("ALPHAMISSENSE_PP3_BP4_SUPPORTING@1.0.0",
                measurement(atomic, "alphamissense.amPathogenicity")
                        .path("calibrationProfileId").asText());
    }

    @Test
    void exactTranscriptAndProteinMatchProducesStableMutuallyExclusiveEvaluations() {
        ObjectNode atomic = atomic(hitCarrier("0.9000", true, true,
                selectedTranscript(), "p.Lys34Arg", "P12345"));

        List<ObjectNode> first = evaluate(atomic);
        List<ObjectNode> second = evaluate(atomic);

        assertEquals(first, second);
        assertEquals("MET", criterion(first, "ACMG_AMP_2015:PP3")
                .path("evaluationResult").path("status").asText());
        assertEquals("SUPPORTING", criterion(first, "ACMG_AMP_2015:PP3")
                .path("appliedStrength").asText());
        assertEquals("NOT_MET", criterion(first, "ACMG_AMP_2015:BP4")
                .path("evaluationResult").path("status").asText());
        assertEquals("NONE", criterion(first, "ACMG_AMP_2015:BP4")
                .path("appliedStrength").asText());
        first.forEach(this::assertStableEnvelope);
    }

    @Test
    void transcriptOrProteinMismatchCannotBeEvaluated() {
        ObjectNode alleleMismatch = atomic(hitCarrier("0.900", false, true,
                selectedTranscript(), "K34R", "P12345"));
        ObjectNode transcriptMismatch = atomic(hitCarrier("0.900", true, true,
                "NM_999999.1", "K34R", "P12345"));
        ObjectNode proteinMismatch = atomic(hitCarrier("0.900", true, false,
                selectedTranscript(), "K34R", "P12345"));

        assertNotEvaluated(evaluate(alleleMismatch), "EXACT_ALLELE_NOT_CONFIRMED",
                "UNKNOWN");
        assertNotEvaluated(evaluate(transcriptMismatch), "TRANSCRIPT_IDENTITY_MISMATCH",
                "UNKNOWN");
        assertNotEvaluated(evaluate(proteinMismatch),
                "TRANSCRIPT_PROTEIN_MATCH_NOT_CONFIRMED", "UNKNOWN");
    }

    @Test
    void incompleteContextAndMissingScoreCannotBeEvaluated() {
        ObjectNode atomic = atomic(hitCarrier("0.900", true, true,
                selectedTranscript(), "K34R", "P12345"));
        ObjectNode incomplete = context.deepCopy();
        ((ObjectNode) incomplete.path("contextCompleteness"))
                .put("status", "INCOMPLETE").putArray("missingFields")
                .add("TRANSCRIPT_VERSION");

        assertNotEvaluated(evaluator.evaluate(incomplete, atomic, profile, registry,
                SELECTION_ID), "CONTEXT_INCOMPLETE", "UNKNOWN");

        ObjectNode missingScoreCarrier = hitCarrier("0.900", true, true,
                selectedTranscript(), "K34R", "P12345");
        removeMeasurement(missingScoreCarrier, "alphamissense.amPathogenicity");
        assertNotEvaluated(evaluate(atomic(missingScoreCarrier)),
                "REQUIRED_MEASUREMENT_MISSING",
                "UNKNOWN");
    }

    @Test
    void nonMissenseProviderOutcomeIsNotApplicable() {
        ObjectNode atomic = atomic(nonApplicableCarrier());

        assertNotEvaluated(evaluate(atomic),
                "ALPHAMISSENSE_PROFILE_NOT_APPLICABLE", "NOT_APPLICABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_RECORD", "FAILED", "TIMEOUT"})
    void providerAbsenceFailureAndTimeoutCannotBeEvaluated(String status) {
        ObjectNode atomic = atomic(failedCarrier(status));
        String reason = switch (status) {
            case "NO_RECORD" -> "ALPHAMISSENSE_NO_RECORD";
            case "FAILED" -> "RESOURCE_EXECUTION_FAILED";
            case "TIMEOUT" -> "RESOURCE_EXECUTION_TIMEOUT";
            default -> throw new IllegalArgumentException(status);
        };

        assertNotEvaluated(evaluate(atomic), reason, "UNKNOWN");
    }

    @Test
    void rejectsHiddenRoundingAndKeepsTheRawDecimalLexeme() {
        ObjectNode carrier = hitCarrier("0.7920", true, true,
                selectedTranscript(), "K34R", "P12345");
        measurement(carrier, "alphamissense.amPathogenicity")
                .put("normalizedValue", "0.791");
        ObjectNode rounded = atomic(carrier);

        assertNotEvaluated(evaluate(rounded), "ALPHAMISSENSE_SCORE_INVALID", "UNKNOWN");

        ObjectNode exact = atomic(hitCarrier("0.7920", true, true,
                selectedTranscript(), "K34R", "P12345"));
        ObjectNode pp3 = criterion(evaluate(exact), "ACMG_AMP_2015:PP3");
        assertEquals("MET", pp3.path("evaluationResult").path("status").asText());
        assertEquals("0.7920", parameter(pp3, "score.raw").path("value").asText());
        assertEquals("0.792", parameter(pp3, "score.normalized").path("value").asText());
    }

    private List<ObjectNode> evaluate(ObjectNode atomic) {
        return evaluator.evaluate(context, atomic, profile, registry, SELECTION_ID);
    }

    private ObjectNode atomic(ObjectNode carrier) {
        return adapter.adapt(context, carrier);
    }

    private ObjectNode hitCarrier(String score,
                                  boolean exactAllele,
                                  boolean transcriptProteinMatch,
                                  String transcript,
                                  String proteinChange,
                                  String proteinId) {
        ObjectNode carrier = baseCarrier();
        addMeasurement(carrier, "alphamissense.exactAlleleMatch",
                Boolean.toString(exactAllele), Boolean.toString(exactAllele), "boolean");
        addMeasurement(carrier, "alphamissense.transcriptProteinMatch",
                Boolean.toString(transcriptProteinMatch),
                Boolean.toString(transcriptProteinMatch), "boolean");
        addMeasurement(carrier, "alphamissense.transcriptId",
                transcript, transcript, "transcript-id");
        addMeasurement(carrier, "alphamissense.proteinVariant",
                proteinChange, proteinChange, "protein-change");
        addMeasurement(carrier, "alphamissense.uniprotId",
                proteinId, proteinId, "protein-id");
        BigDecimal decimal = new BigDecimal(score);
        addMeasurement(carrier, "alphamissense.amPathogenicity", score,
                decimal.stripTrailingZeros().signum() == 0
                        ? "0" : decimal.stripTrailingZeros().toPlainString(), "score");
        return carrier;
    }

    private ObjectNode baseCarrier() {
        JsonNode resource = profile.path("resources").path(0);
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "COMPUTATIONAL_PREDICTION");
        carrier.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", resource.path("resourceId").asText());
        source.put("resourceVersion", resource.path("resourceVersion").asText());
        source.put("resourceSha256", resource.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "DATABASE_RECORD");
        locator.put("locatorValue", "ALPHAMISSENSE:GRCh38:1:100:A:G:"
                + selectedTranscript() + ":K34R");
        locator.put("sourceRecordId", "ALPHAMISSENSE:1:100:A:G:"
                + selectedTranscript() + ":K34R");
        locator.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode record = mapper.createObjectNode();
        record.put("recordId", locator.path("sourceRecordId").asText());
        record.set("locator", locator.deepCopy());
        source.putArray("sourceRecords").add(record);
        source.putArray("sourceLocators").add(locator);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED")
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        ObjectNode queryProjection = mapper.createObjectNode();
        queryProjection.put("resourceId", resource.path("resourceId").asText());
        queryProjection.set("queryAllele", carrier.path("queryAllele").deepCopy());
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(queryProjection));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putArray("measurements");
        carrier.put("producedAt", "2026-09-02T00:00:00Z");
        carrier.put("sourcePayloadSha256",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return carrier;
    }

    private ObjectNode nonApplicableCarrier() {
        ObjectNode carrier = baseCarrier();
        clearHit(carrier);
        ObjectNode execution = (ObjectNode) carrier.path("resourceExecution");
        execution.removeAll();
        execution.put("status", "NOT_RUN").put("reasonCode", "NON_MISSENSE_VARIANT");
        ((ObjectNode) carrier.path("queryOutcome"))
                .put("status", "NOT_QUERIED").put("recordCount", 0)
                .remove("queryFingerprint");
        ObjectNode applicability = (ObjectNode) carrier.path("resourceApplicability");
        applicability.put("status", "NOT_APPLICABLE")
                .put("reasonCode", "NON_MISSENSE_VARIANT");
        return carrier;
    }

    private ObjectNode failedCarrier(String status) {
        ObjectNode carrier = baseCarrier();
        clearHit(carrier);
        ObjectNode query = (ObjectNode) carrier.path("queryOutcome");
        if ("NO_RECORD".equals(status)) {
            query.put("status", "NO_RECORD").put("recordCount", 0);
            return carrier;
        }
        query.removeAll();
        query.put("status", "NOT_QUERIED").put("recordCount", 0);
        ObjectNode execution = (ObjectNode) carrier.path("resourceExecution");
        execution.removeAll();
        execution.put("status", status).put("reasonCode", "PROVIDER_" + status)
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        ObjectNode availability = (ObjectNode) carrier.path("resourceAvailability");
        availability.removeAll();
        availability.put("status", "UNKNOWN").put("reasonCode", "NOT_CHECKED");
        ObjectNode applicability = (ObjectNode) carrier.path("resourceApplicability");
        applicability.removeAll();
        applicability.put("status", "UNKNOWN").put("reasonCode", "NOT_EVALUATED");
        return carrier;
    }

    private void clearHit(ObjectNode carrier) {
        ((ArrayNode) carrier.path("source").path("sourceRecords")).removeAll();
        ((ArrayNode) carrier.path("measurements")).removeAll();
        ((ObjectNode) carrier.path("queryOutcome")).put("recordCount", 0);
    }

    private void addMeasurement(ObjectNode carrier,
                                String metricId,
                                String raw,
                                String normalized,
                                String unit) {
        ObjectNode value = ((ArrayNode) carrier.path("measurements")).addObject();
        value.put("metricId", metricId);
        value.put("rawValue", raw);
        value.put("normalizedValue", normalized);
        value.put("unit", unit);
        value.put("calibrationProfileId",
                AlphaMissenseCriterionEvaluator.CALIBRATION_PROFILE_ID);
    }

    private void removeMeasurement(ObjectNode atomic, String metricId) {
        ArrayNode measurements = (ArrayNode) atomic.path("measurements");
        for (int index = measurements.size() - 1; index >= 0; index--) {
            if (metricId.equals(measurements.path(index).path("metricId").asText())) {
                measurements.remove(index);
            }
        }
    }

    private ObjectNode measurement(ObjectNode parent, String metricId) {
        for (JsonNode value : parent.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("measurement not found: " + metricId);
    }

    private ObjectNode criterion(List<ObjectNode> evaluations, String criterionId) {
        return evaluations.stream()
                .filter(value -> criterionId.equals(value.path("criterionId").asText()))
                .findFirst().orElseThrow();
    }

    private ObjectNode parameter(ObjectNode evaluation, String name) {
        for (JsonNode value : evaluation.path("deterministicTrace").path("parameters")) {
            if (name.equals(value.path("name").asText())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("parameter not found: " + name);
    }

    private String selectedTranscript() {
        return context.path("transcript").path("accession").asText()
                + "." + context.path("transcript").path("version").asInt();
    }

    private void assertEvaluation(ObjectNode evaluation, String expectedStatus) {
        assertEquals(expectedStatus,
                evaluation.path("evaluationResult").path("status").asText());
        assertEquals("SUPPORTING", evaluation.path("baseStrength").asText());
        assertEquals("MET".equals(expectedStatus) ? "SUPPORTING" : "NONE",
                evaluation.path("appliedStrength").asText());
    }

    private void assertNotEvaluated(List<ObjectNode> evaluations,
                                    String reason,
                                    String applicability) {
        assertEquals(2, evaluations.size());
        for (ObjectNode evaluation : evaluations) {
            assertEquals(applicability,
                    evaluation.path("criterionApplicability").path("status").asText());
            assertEquals(reason,
                    evaluation.path("criterionApplicability").path("reasonCode").asText());
            assertEquals("NOT_EVALUATED",
                    evaluation.path("evaluationResult").path("status").asText());
            assertEquals(reason,
                    evaluation.path("evaluationResult").path("reasonCode").asText());
            assertEquals("NONE", evaluation.path("appliedStrength").asText());
            assertEquals("NOT_RUN",
                    evaluation.path("deterministicTrace").path("executionStatus").asText());
        }
    }

    private void assertStableEnvelope(ObjectNode evaluation) {
        assertEquals("criterion-evaluation-v2", evaluation.path("schemaVersion").asText());
        assertTrue(evaluation.path("evaluationId").asText()
                .matches("^evaluation-sha256-[0-9a-f]{64}$"));
        assertEquals(context.path("contextId").asText(), evaluation.path("contextId").asText());
        assertEquals("varvis-json-c14n-v1",
                evaluation.path("canonicalizationProfile").asText());
        assertEquals("acmg-rule-registry-e3-c1-2026-09-02",
                evaluation.path("ruleBinding").path("ruleRegistryId").asText());
        assertEquals("SUPPORTING", evaluation.path("baseStrength").asText());
        assertEquals("COMPLETED",
                evaluation.path("deterministicTrace").path("executionStatus").asText());
        assertTrue(evaluation.path("deterministicTrace").path("traceSha256").asText()
                .matches("^[0-9a-f]{64}$"));
        assertNotEquals(0, evaluation.path("evidenceIds").size());
    }
}
