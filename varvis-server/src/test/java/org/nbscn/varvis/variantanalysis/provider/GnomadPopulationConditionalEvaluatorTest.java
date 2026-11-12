package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Existing parser/wiring/catalog/evaluator with explicit synthetic admitted policies.
 * Never adopts the genuine GN027 full publication or qualifies clinical joint people.
 */
class GnomadPopulationConditionalEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final PopulationFrequencyRuntimeTest facts = new PopulationFrequencyRuntimeTest();
    private final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
    private final Ba1ConditionalCatalogTest catalogs = new Ba1ConditionalCatalogTest();

    private ObjectNode selection() throws Exception {
        return new Ba1SpecificationCatalog(mapper, catalogs.snapshot()).select(catalogs.mapping(), facts.diseaseContext("MYH7"));
    }
    private Map<String, String> info(String faf, String an) {
        var values = facts.jointInfo(faf, an, "0.1");
        String af = new BigDecimal("200").divide(new BigDecimal(an), MathContext.DECIMAL64).toPlainString();
        values.put("AC_joint_nfe", "200"); values.put("AC_grpmax_joint", "200");
        values.put("AF_joint_nfe", af); values.put("AF_grpmax_joint", af);
        return values;
    }
    private ObjectNode carrier(Map<String, String> values) throws Exception {
        var context = facts.diseaseContext("MYH7");
        var observation = facts.observation(context, values, "PASS");
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var carrier = wiring.reparsePopulationObservation(context, observation, "a".repeat(64),
                GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile).carrier();
        wiring.preserveBa1SubtypeFilters(carrier, observation.path("rawPopulationRecord").asText());
        return carrier;
    }
    private ObjectNode evaluate(ObjectNode carrier, ObjectNode selection) throws Exception {
        return new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(
                facts.diseaseContext("MYH7").at("/variant/alleleId").asText(), carrier, profile, selection);
    }
    @ParameterizedTest @CsvSource({"0.002,2000,MET,TRUE", "0.002000000000001,2000,MET,TRUE",
            "0.001999999999999,2000,NOT_EVALUATED,FALSE", "0.003,1999,NOT_EVALUATED,UNKNOWN"})
    void completeOrIsExecutedWithExactFrequencyBoundaryAndUnknownClinicalCount(String faf, String an,
                                                                               String status, String frequencyTruth) throws Exception {
        var result = evaluate(carrier(info(faf, an)), selection());
        assertEquals(status, result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(frequencyTruth, result.at("/ruleConditionEvaluation/frequencyTruth").asText());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/countTruth").asText());
        assertEquals("BA1_COUNT_SOURCE_SCOPE_UNQUALIFIED", result.at("/ruleConditionEvaluation/countReasonCode").asText());
        assertEquals("UNVERIFIED_OR_PARTIAL", result.at("/ruleConditionEvaluation/countScopeCompleteness").asText());
        assertEquals(GnomadPopulationCriterionEvaluator.BA1_CONDITIONAL_EVALUATOR_VERSION,
                result.at("/deterministicTrace/evaluatorVersion").asText());
        assertEquals(Ba1ConditionalFrequencyRule.VERSION, result.at("/ruleBinding/conditionRuntimeContract").asText());
        assertEquals("MET".equals(status) ? "STANDALONE" : "NONE", result.path("appliedStrength").asText());
        if ("MET".equals(status)) {
            assertEquals("COMPLETED", result.at("/deterministicTrace/executionStatus").asText());
            assertEquals("GNOMAD_JOINT_BA1_CONDITIONAL_V1", result.at("/deterministicTrace/algorithmId").asText());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"nhomalt_joint", "nhomalt_grpmax_joint", "nhomalt_joint_nfe", "faf95_joint"})
    void missingUnusedArmStatisticDoesNotEraseAnIndependentlyTrueFrequency(String missing) throws Exception {
        var values = info("0.003", "2000"); values.remove(missing);
        var result = evaluate(carrier(values), selection());
        assertEquals("MET", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("TRUE", result.at("/ruleConditionEvaluation/frequencyTruth").asText());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/countTruth").asText());
    }
    @Test void missingFrequencyDoesNotBecomeZeroOrTurnAnUnqualifiedHomoCountIntoPeople() throws Exception {
        var values = info("0.001", "2000"); values.remove("fafmax_faf95_max_joint"); values.put("nhomalt_joint", "10");
        var result = evaluate(carrier(values), selection());
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/frequencyTruth").asText());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/countTruth").asText());
        assertEquals("10", result.at("/ruleConditionEvaluation/observedHomozygoteStatistic/rawValue").asText());
        assertEquals("UNRESOLVED", result.at("/ruleConditionEvaluation/observedHomozygoteStatistic/qualificationStatus").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "10", "50"})
    void highRawHomozygoteStatisticsCannotMakeAFalseFrequencyACompletedNegativeOrPositive(String hom) throws Exception {
        var values = info("0.001", "2000"); values.put("nhomalt_joint", hom);
        var result = evaluate(carrier(values), selection());
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("FALSE", result.at("/ruleConditionEvaluation/frequencyTruth").asText());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/countTruth").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"FILTER", "MISSING_FILTER", "DISCORDANT", "MISSING_DISCORDANCE",
            "CORE_MISSING", "ANCESTRY_CORE_MISSING", "INVALID_NUMBER", "AF_COUNT", "FAF_GROUP", "HOMO_AC"})
    void sourceQualityAndCoreMissingFieldsAreNotWaivedByTheOr(String fault) throws Exception {
        var values = info("0.003", "2000");
        switch (fault) {
            case "FILTER" -> values.put("exomes_filters", "AC0");
            case "MISSING_FILTER" -> values.remove("genomes_filters");
            case "DISCORDANT" -> values.put("stat_union_p_value", "0.00001");
            case "MISSING_DISCORDANCE" -> values.remove("stat_union_p_value");
            case "CORE_MISSING" -> values.remove("AN_joint");
            case "ANCESTRY_CORE_MISSING" -> values.remove("AC_joint_nfe");
            case "INVALID_NUMBER" -> values.put("nhomalt_joint", "-1");
            case "AF_COUNT" -> values.put("AC_joint_nfe", "500");
            case "FAF_GROUP" -> values.put("faf95_joint_nfe", "0.00001");
            case "HOMO_AC" -> values.put("nhomalt_joint", "10001");
            default -> throw new AssertionError(fault);
        }
        var result = evaluate(carrier(values), selection());
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), fault + result.toPrettyString());
        assertFalse(result.at("/evaluationResult/reasonCode").asText().isBlank(), fault);
    }
    @Test void resourceCalibrationAndExactContractRemainBoundAndInputsAreReadOnly() throws Exception {
        var valid = carrier(info("0.003", "2000")); var selected = selection();
        byte[] before = mapper.writeValueAsBytes(valid), policyBefore = mapper.writeValueAsBytes(selected);
        var result = evaluate(valid, selected); assertEquals("MET", result.at("/evaluationResult/status").asText());
        assertArrayEquals(before, mapper.writeValueAsBytes(valid)); assertArrayEquals(policyBefore, mapper.writeValueAsBytes(selected));
        assertEquals(result, evaluate(valid, selected));
        var wrongSource = valid.deepCopy(); wrongSource.withObject("/source").put("resourceVersion", "wrong");
        assertEquals("RESOURCE_IDENTITY_MISMATCH", evaluate(wrongSource, selected).at("/evaluationResult/reasonCode").asText());
        var wrongCalibration = valid.deepCopy();
        for (var node : wrongCalibration.path("measurements")) if ("gnomad.exactAlleleMatch".equals(node.path("metricId").asText()))
            ((ObjectNode) node).put("calibrationProfileId", "wrong");
        assertEquals("PROFILE_CALIBRATION_MISMATCH", evaluate(wrongCalibration, selected).at("/evaluationResult/reasonCode").asText());
        var badContract = selected.deepCopy(); badContract.withObject("/policy").put("conditionRuntimeContract", "unrecognized");
        assertEquals("BA1_CONDITIONAL_RUNTIME_CONTRACT_UNSUPPORTED", evaluate(valid, badContract).at("/evaluationResult/reasonCode").asText());
    }
    @Test void unsupportedPublicationSelectionIsNeverPromotedToTheConditionalRoute() throws Exception {
        var selected = selection(); selected.put("status", "BLOCKED").put("reasonCode", "BA1_GLOBAL_PUBLICATION_TEXT_UNSUPPORTED");
        var result = evaluate(carrier(info("0.003", "2000")), selected);
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals("BA1_GLOBAL_PUBLICATION_TEXT_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
        assertFalse(result.has("ruleConditionEvaluation"));
    }
}
