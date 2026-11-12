package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionMechanismSummaryTest {
    @Test void selectedScoreAndExactBandArePublicDisplayFactsWithoutChangingTheDecision() {
        var input=result(); var selected=input.withObject("/mechanisms/PROTEIN_MISSENSE");
        selected.put("rawScore","0.74");
        selected.putObject("band").put("lower","0.7").put("upper","0.8").put("lowerInclusive",true).put("upperInclusive",false)
                .put("privatePath","SECRET");
        var before=input.deepCopy();
        var actual=PredictionMechanismSummary.publicView(mapper,PredictionMechanismSummary.fromResult(mapper,input));
        assertEquals("0.74",actual.at("/mechanisms/0/values/rawScore").asText());
        assertEquals("0.7",actual.at("/mechanisms/0/thresholds/lower").asText());
        assertFalse(actual.at("/mechanisms/0/thresholds/upperInclusive").asBoolean(true));
        assertFalse(actual.toString().contains("SECRET"));assertEquals(before,input);
    }
    @Test void absentScoresStayAbsentAndUnsafeNumericFieldsAreNotCopied() {
        var summary=PredictionMechanismSummary.fromResult(mapper,result());
        assertFalse(summary.at("/mechanisms/0").has("values"));
        var selected=(ObjectNode)summary.at("/mechanisms/0");
        selected.putObject("values").put("rawScore","D:/SECRET").put("other","SECRET");
        selected.putObject("thresholds").put("lower","0.7").put("upper","0.8");
        var actual=PredictionMechanismSummary.publicView(mapper,summary);
        assertFalse(actual.toString().contains("SECRET"));assertFalse(actual.at("/mechanisms/0").has("thresholds"));
    }
    @Test void dependencyFailureKeepsStageAndCauseWithoutLeakingSourceOrChangingScores() {
        var input = result();
        input.withObject("/plan").putObject("effectRestrictions").put("status", "UNAVAILABLE")
                .put("reasonCode", "EFFECT_SOURCE_READ_TEMPORARY_FAILURE").put("failureStage", "HISTORY_INDEX")
                .put("privatePath", "D:/SECRET").put("sourceSha256", "a".repeat(64));
        var actual = PredictionMechanismSummary.publicView(mapper, PredictionMechanismSummary.fromResult(mapper, input));
        assertEquals("HISTORY_INDEX", actual.at("/functionalReferences/failureStage").asText());
        assertEquals("EFFECT_SOURCE_READ_TEMPORARY_FAILURE", actual.at("/functionalReferences/reasonCode").asText());
        assertFalse(actual.toString().contains("SECRET"));
        assertFalse(actual.at("/functionalReferences/absenceEstablished").asBoolean(true));
    }
    @Test void selectedScopeIncludesInternalDependenciesWithoutRequestingUnrelatedHistory() {
        for (String code : java.util.List.of("PP3", "BP4", "BP7", "BP1"))
            assertTrue(new AcmgCriterionSelection(java.util.List.of("ACMG_AMP_2015:" + code)).needsFunctionalReferences());
        for (String code : java.util.List.of("BA1", "BS1", "PM2", "PS1", "PM3", "PP1", "PS2", "PM6"))
            assertFalse(new AcmgCriterionSelection(java.util.List.of("ACMG_AMP_2015:" + code)).needsFunctionalReferences());
    }
    @Test void partialNativeBatchReasonsRemainVisibleWithoutLeakingPrivateAudit() {
        for (String reason : java.util.List.of("NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED", "NATIVE_TOTAL_UNIT_LIMIT_EXCEEDED", "NATIVE_BATCH_RECORDS_INVALID", "NATIVE_SPLICE_INTERRUPTED")) {
            var input = result(); input.withObject("/plan").put("route", "GENERAL_LOCAL_ALPHAMISSENSE");
            input.withObject("/mechanisms/SPLICING").put("reasonCode", reason);
            input.putObject("nativeSpliceResult").putObject("mechanism").put("reasonCode", reason).put("status", "NOT_EVALUATED");
            input.putObject("nativeSpliceAudit").put("batchJson", "D:/PRIVATE/raw");
            var output = PredictionMechanismSummary.publicView(mapper, PredictionMechanismSummary.fromResult(mapper, input));
            assertEquals(reason, output.at("/mechanisms/1/reasonCode").asText());
            assertFalse(output.at("/mechanisms/1/reason").asText().contains(reason));
            assertFalse(output.toString().contains("PRIVATE"));
            assertEquals("NOT_EVALUATED", input.at("/criteria/BP4/status").asText());
        }
    }
    @Test void blockedNativeAlternativeCannotReplaceAnExistingSpecializedObservation() {
        var input = result(); var before = PredictionMechanismSummary.fromResult(mapper, input);
        input.putObject("nativeSpliceResult").putObject("mechanism").put("status", "NOT_EVALUATED")
                .put("reasonCode", "PREDICTION_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION");
        assertEquals(before, PredictionMechanismSummary.fromResult(mapper, input));
    }
    @Test void materialReadinessIsPublicWithoutGrantingPolicyOrLeakingModel() {
        var input = result();
        input.withObject("/plan").putObject("transcriptMaterial").put("status", "READY").put("transcript", "ENST000001.4")
                .put("modelOrigin", "GENCODE_GTF_PLUS_REFERENCE").put("reasonCode", "EXACT_MODEL_REFERENCE_AND_VEP_AGREE")
                .put("privateModel", "D:/SECRET/sequence");
        var actual = PredictionMechanismSummary.publicView(mapper, PredictionMechanismSummary.fromResult(mapper, input));
        assertEquals("READY", actual.at("/transcriptMaterial/status").asText());
        assertFalse(actual.at("/transcriptMaterial/scientificApproval").asBoolean(true));
        assertFalse(actual.toString().contains("SECRET"));
        assertEquals("NOT_EVALUATED", input.at("/criteria/BP4/status").asText());
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode result() {
        var result = mapper.createObjectNode();
        var plan = result.putObject("plan").put("route", "REVEL_EXISTING_SUBSET").put("primaryMechanism", "PROTEIN_MISSENSE");
        plan.putArray("allowedPp3Mechanisms").add("PROTEIN_MISSENSE").add("SPLICING");
        plan.putArray("requiredBp4Mechanisms").add("PROTEIN_MISSENSE").add("SPLICING");
        var mechanisms = result.putObject("mechanisms");
        mechanisms.putObject("PROTEIN_MISSENSE").put("status", "CALIBRATED").put("direction", "BENIGN")
                .put("appliedStrength", "SUPPORTING").put("reasonCode", "EXISTING_PAIRED_CLAUSE_ASSESSMENT");
        mechanisms.putObject("SPLICING").put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED");
        var raw = result.putObject("sharedSpliceObservation").putObject("sourceRecord");
        raw.putObject("resourceExecution").put("status", "SUCCEEDED").put("reasonCode", "SPLICE_RAW_RECORDS_OBTAINED");
        raw.putObject("queryOutcome").put("status", "HIT");
        raw.putObject("resource").put("scoreMode", "MASKED").put("dataPath", "D:/PRIVATE/source.vcf.gz");
        raw.putObject("compatibility").put("reasonCode", "SPLICE_PARAMETERS_INCOMPATIBLE");
        result.putObject("criteria").putObject("BP4").put("status", "NOT_EVALUATED");
        return result;
    }
    @Test void hitAndMaskedAreExplainedWithoutChangingAnyDecisionOrRawFact() {
        var input = result(); var before = input.deepCopy();
        var summary = PredictionMechanismSummary.fromResult(mapper, input);
        assertEquals(before, input);
        assertEquals("EXPLANATION_ONLY", summary.path("purpose").asText());
        assertEquals("REVEL", summary.at("/mechanisms/0/model").asText());
        assertEquals("BENIGN", summary.at("/mechanisms/0/direction").asText());
        assertEquals("HIT", summary.at("/mechanisms/1/observationStatus").asText());
        assertEquals("MASKED", summary.at("/mechanisms/1/scoreMode").asText());
        assertEquals("NOT_EVALUATED", summary.at("/mechanisms/1/status").asText());
        assertEquals("SPLICE_PARAMETERS_INCOMPATIBLE", summary.at("/mechanisms/1/observationQualificationReasonCode").asText());
        assertFalse(summary.toString().contains("PRIVATE"));
    }
    @Test void auxiliaryMaskedRecordDoesNotReplaceQualifiedReviewedMechanism() {
        var input = result();
        ((ObjectNode) input.at("/mechanisms/SPLICING")).put("status", "CALIBRATED").put("direction", "HARMFUL")
                .put("appliedStrength", "SUPPORTING").put("reasonCode", "QUALIFIED_RAW_REVIEWED_RESULT");
        var summary = PredictionMechanismSummary.fromResult(mapper, input);
        assertEquals("CALIBRATED", summary.at("/mechanisms/1/status").asText());
        assertEquals("QUALIFIED_RAW_REVIEWED_RESULT", summary.at("/mechanisms/1/reasonCode").asText());
        assertEquals("SPLICE_PARAMETERS_INCOMPATIBLE", summary.at("/mechanisms/1/observationQualificationReasonCode").asText());
    }
    @Test void missingRecordIsNotZeroAndNoDataIsNotSuccessfulObservation() {
        var input = result(); var raw = (ObjectNode) input.at("/sharedSpliceObservation/sourceRecord");
        raw.putObject("queryOutcome").put("status", "NO_RECORD");
        raw.putObject("resourceExecution").put("reasonCode", "SPLICE_EXACT_ALLELE_NO_RECORD");
        var summary = PredictionMechanismSummary.fromResult(mapper, input);
        assertEquals("NO_RECORD", summary.at("/mechanisms/1/observationStatus").asText());
        assertEquals("SPLICE_EXACT_ALLELE_NO_RECORD", summary.at("/mechanisms/1/observationReasonCode").asText());
        assertEquals("NOT_EVALUATED", summary.at("/mechanisms/1/status").asText());
        input.remove("sharedSpliceObservation");
        assertEquals("UNKNOWN", PredictionMechanismSummary.fromResult(mapper, input).at("/mechanisms/1/observationStatus").asText());
    }
    @Test void publicProjectionCopiesOnlyKnownFieldsAndDoesNotInventOldSummaries() {
        var summary = PredictionMechanismSummary.fromResult(mapper, result());
        summary.put("rawPrivate", "SECRET");
        ((ObjectNode) summary.at("/mechanisms/1")).put("reason", "SECRET").put("reasonCode", "D:/SECRET/input");
        var actual = PredictionMechanismSummary.publicView(mapper, summary);
        assertFalse(actual.toString().contains("SECRET"));
        assertEquals("PREDICTION_DETAIL_REDACTED", actual.at("/mechanisms/1/reasonCode").asText());
        assertTrue(PredictionMechanismSummary.publicView(mapper, mapper.createObjectNode()).isEmpty());
        ((com.fasterxml.jackson.databind.node.ArrayNode) summary.path("mechanisms")).add(summary.at("/mechanisms/0").deepCopy());
        assertTrue(PredictionMechanismSummary.publicView(mapper, summary).isEmpty());
    }
    @Test void blockedPlanDoesNotClaimAnyToolWasSelected() {
        var blocked = mapper.createObjectNode();
        blocked.putObject("plan").put("route", "BLOCKED").put("reasonCode", "QUERY_TRANSCRIPT_REANNOTATION_REQUIRED");
        var summary = PredictionMechanismSummary.fromResult(mapper, blocked);
        for (var mechanism : summary.path("mechanisms")) {
            assertEquals("UNRESOLVED", mechanism.path("model").asText());
            assertEquals("NOT_EVALUATED", mechanism.path("status").asText());
            assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", mechanism.path("reasonCode").asText());
        }
    }
    @Test void businessPublicTransportRetainsExplanationAndDecisionSeparately() {
        var full = mapper.createObjectNode();
        var criterion = full.putArray("results").addObject().putObject("localBatch").putArray("criteria").addObject()
                .put("criterionId", "ACMG_AMP_2015:BP4").put("status", "NOT_EVALUATED")
                .put("reasonCode", "BP4_REQUIRED_MECHANISM_UNRESOLVED").put("appliedStrength", "NONE");
        criterion.putObject("details").set("predictionMechanisms", PredictionMechanismSummary.fromResult(mapper, result()));
        var publicResult = LocalEvidenceBatchRuntime.publicView(mapper, full);
        assertEquals("NOT_EVALUATED", publicResult.at("/results/0/localBatch/criteria/0/status").asText());
        assertEquals("MASKED", publicResult.at("/results/0/localBatch/criteria/0/details/predictionMechanisms/mechanisms/1/scoreMode").asText());
        assertFalse(publicResult.toString().contains("PRIVATE"));
    }
}
