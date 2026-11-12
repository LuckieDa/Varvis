package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossPublicStatusTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode source(String section) {
        var source = mapper.createObjectNode();
        source.putObject(section).put("decisionOutcome", "NOT_APPLICABLE").put("decisionStrength", "NONE");
        var evaluation = source.putObject("evaluation").put("appliedStrength", "NONE");
        evaluation.putObject("criterionApplicability").put("status", "NOT_APPLICABLE");
        evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED");
        evaluation.putObject("deterministicTrace").put("executionStatus", "COMPLETED").put("evaluatorId", "pvs1-start-loss-automatic-generic");
        return source;
    }

    @ParameterizedTest @ValueSource(strings = {"automaticStartLossFacts", "expertStartLossFacts", "startLossFacts"})
    void completedStartLossExclusionIsVisibleWithoutRewritingOriginalEvaluation(String section) {
        var source = source(section); var before = source.deepCopy();
        assertEquals("NOT_APPLICABLE", LocalEvidenceBatchRuntime.startLossPublicStatus(Pvs1SelectedBatch.CRITERION, source.path("evaluation"), source));
        assertEquals(before, source);
        assertEquals("NOT_EVALUATED", source.at("/evaluation/evaluationResult/status").asText());
    }

    @Test void missingProofFailureOtherBranchAndNonzeroStrengthAreNotPromoted() {
        for (String mutation : java.util.List.of("facts", "outcome", "evaluator", "failed", "applicability", "strength", "otherCriterion")) {
            var source = source("automaticStartLossFacts"); String criterion = Pvs1SelectedBatch.CRITERION;
            switch (mutation) {
                case "facts" -> source.remove("automaticStartLossFacts");
                case "outcome" -> source.withObject("automaticStartLossFacts").put("decisionOutcome", "INSUFFICIENT_INFORMATION");
                case "evaluator" -> source.withObject("evaluation").withObject("deterministicTrace").put("evaluatorId", "pvs1-nmd");
                case "failed" -> source.withObject("evaluation").withObject("deterministicTrace").put("executionStatus", "FAILED");
                case "applicability" -> source.withObject("evaluation").withObject("criterionApplicability").put("status", "UNKNOWN");
                case "strength" -> source.withObject("evaluation").put("appliedStrength", "MODERATE");
                case "otherCriterion" -> criterion = "ACMG_AMP_2015:PM4";
                default -> throw new AssertionError(mutation);
            }
            assertEquals("NOT_EVALUATED", LocalEvidenceBatchRuntime.startLossPublicStatus(criterion, source.path("evaluation"), source), mutation);
        }
    }

    @Test void existingMetNotMetAndMissingSourceKeepTheirOriginalStatus() {
        var source = source("automaticStartLossFacts");
        for (String status : java.util.List.of("MET", "NOT_MET")) {
            source.withObject("evaluation").withObject("evaluationResult").put("status", status);
            assertEquals(status, LocalEvidenceBatchRuntime.startLossPublicStatus(Pvs1SelectedBatch.CRITERION, source.path("evaluation"), source));
        }
        source.withObject("evaluation").withObject("evaluationResult").put("status", "NOT_EVALUATED");
        assertEquals("NOT_EVALUATED", LocalEvidenceBatchRuntime.startLossPublicStatus(Pvs1SelectedBatch.CRITERION, source.path("evaluation"), null));
    }
}
