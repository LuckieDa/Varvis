package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.Pvs1SelectedBatch;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The unified completion gate must agree with the native A1 public disposition. */
class Pvs1StartLossCompletionTest {
    @TempDir Path temporary;
    private AcmgBusinessIntegrationTest fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new AcmgBusinessIntegrationTest(); fixture.temporary = temporary;
        fixture.setup(); fixture.recreateService(fixture.runtime());
        ReflectionTestUtils.setField(requireNonNull(fixture.service), "unifiedLocal", true);
    }

    @AfterEach void close() { fixture.closeDatabase(); }

    private ObjectNode response(String section) {
        var response = fixture.mapper.createObjectNode();
        response.putArray("enabledCriteria").add(Pvs1SelectedBatch.CRITERION);
        response.putArray("unmatchedInputs");
        var criterion = response.putArray("candidates").addObject().put("candidateId", "target")
                .putArray("attempts").addObject().putObject("localBatch").putArray("criteria")
                .addObject().put("criterionId", Pvs1SelectedBatch.CRITERION)
                .put("status", "NOT_APPLICABLE").put("appliedStrength", "NONE");
        var details = criterion.putObject("details");
        var evaluation = details.putObject("originalResult").put("appliedStrength", "NONE");
        evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED");
        evaluation.putObject("criterionApplicability").put("status", "NOT_APPLICABLE");
        evaluation.putObject("deterministicTrace").put("executionStatus", "COMPLETED")
                .put("evaluatorId", "pvs1-start-loss-automatic-generic");
        details.putObject("pvs1Facts").put("schemaVersion", "pvs1-selected-facts-v1")
                .putObject(section).put("decisionOutcome", "NOT_APPLICABLE").put("decisionStrength", "NONE");
        return response;
    }

    private void finish(ObjectNode response) {
        ReflectionTestUtils.invokeMethod(requireNonNull(fixture.service), "finishCurrentScope", response, response.objectNode());
    }

    @Test void sourceEvaluatedExclusionCompletesWithoutChangingScientificResult() {
        for (String section : List.of("automaticAssessment", "expertAssessment", "startLossAssessment")) {
            var response = response(section);
            var original = response.at("/candidates/0/attempts/0/localBatch/criteria/0").deepCopy();
            finish(response);
            assertEquals("COMPLETED", response.path("status").asText(), section);
            assertTrue(response.at("/candidates/0/enabledRuleResultsComplete").asBoolean(), section);
            assertEquals(original, response.at("/candidates/0/attempts/0/localBatch/criteria/0"));
        }
    }

    @Test void missingProofFailuresUnknownOtherBranchesAndDuplicateResultsRemainBlocked() {
        for (String mutation : List.of("details", "facts", "decision", "failed", "unknown", "otherBranch", "strength", "duplicate")) {
            var response = response("automaticAssessment");
            var criterion = (ObjectNode) response.at("/candidates/0/attempts/0/localBatch/criteria/0");
            switch (mutation) {
                case "details" -> criterion.remove("details");
                case "facts" -> ((ObjectNode) criterion.path("details")).remove("pvs1Facts");
                case "decision" -> ((ObjectNode) criterion.at("/details/pvs1Facts/automaticAssessment")).put("decisionOutcome", "INSUFFICIENT_INFORMATION");
                case "failed" -> ((ObjectNode) criterion.at("/details/originalResult/deterministicTrace")).put("executionStatus", "FAILED");
                case "unknown" -> ((ObjectNode) criterion.at("/details/originalResult/criterionApplicability")).put("status", "UNKNOWN");
                case "otherBranch" -> ((ObjectNode) criterion.at("/details/originalResult/deterministicTrace")).put("evaluatorId", "pvs1-nmd");
                case "strength" -> criterion.put("appliedStrength", "MODERATE");
                case "duplicate" -> ((com.fasterxml.jackson.databind.node.ArrayNode) response.at("/candidates/0/attempts/0/localBatch/criteria")).add(criterion.deepCopy());
                default -> throw new AssertionError(mutation);
            }
            finish(response);
            assertEquals("COMPLETED_WITH_BLOCKERS", response.path("status").asText(), mutation);
        }
    }
}
