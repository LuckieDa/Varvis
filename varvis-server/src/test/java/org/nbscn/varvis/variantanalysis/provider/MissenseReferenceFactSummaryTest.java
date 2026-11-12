package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MissenseReferenceFactSummaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PS1 = "ACMG_AMP_2015:PS1", PM5 = "ACMG_AMP_2015:PM5";
    private ObjectNode facts() {
        var out = mapper.createObjectNode(); var result = out.putObject("capture").putObject("result");
        result.put("status", "READY").putNull("reasonCode").putObject("discovery").put("readinessStatus", "READY");
        var decisions = result.putArray("candidateDecisions");
        decisions.addObject().put("status", "READY"); decisions.addObject().put("status", "NOT_APPLICABLE");
        var reference = result.putArray("comparators").addObject().put("variationId", "1234")
                .put("proteinRelation", "SAME_RESIDUE_DIFFERENT_AMINO_ACID").put("privatePath", "D:/private");
        reference.putArray("submissions").addObject().put("privateRaw", "D:/private");
        return out;
    }
    @Test void referenceFactsAndCriterionMatchingAreSeparateFromAnyApproval() {
        var input = facts(); var frozen = input.deepCopy();
        var ps1 = MissenseReferenceFactSummary.build(mapper, input, PS1);
        var pm5 = MissenseReferenceFactSummary.build(mapper, input, PM5);
        assertEquals(0, ps1.path("criterionMatches").asInt()); assertEquals(1, pm5.path("criterionMatches").asInt());
        assertEquals("COMPLETE", pm5.path("searchStatus").asText());
        assertEquals("REFERENCE_COMPARATOR_FACTS_RECORDED", pm5.path("searchReasonCode").asText());
        assertFalse(pm5.path("scientificApprovalGranted").asBoolean(true));
        assertFalse(pm5.toString().contains("private")); assertEquals(frozen, input);
    }
    @ParameterizedTest @ValueSource(strings={"NOT_APPLICABLE", "UNAVAILABLE", "INCOMPLETE", "COMPLETE"})
    void searchOutcomesRemainDistinct(String state) {
        var input = facts(); var result = (ObjectNode) input.at("/capture/result");
        if (state.equals("NOT_APPLICABLE")) {
            result.removeAll(); result.put("reasonCode", "SELECTED_TARGET_MISSENSE_CONSEQUENCE_REQUIRED");
        } else if (state.equals("UNAVAILABLE")) input.remove("capture");
        else if (state.equals("INCOMPLETE")) ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("candidateDecisions")).addObject().put("status", "NOT_EVALUATED");
        assertEquals(state, MissenseReferenceFactSummary.build(mapper, input, PM5).path("searchStatus").asText());
    }
    @ParameterizedTest @ValueSource(strings={"approved", "criterion", "count", "matches", "duplicate", "path", "complete", "unavailable"})
    void corruptSummariesCannotClaimFacts(String fault) {
        var summary = MissenseReferenceFactSummary.build(mapper, facts(), PM5);
        switch (fault) {
            case "approved" -> summary.put("scientificApprovalGranted", true);
            case "criterion" -> summary.put("criterionId", PS1);
            case "count" -> summary.put("discoveredCandidates", -1);
            case "matches" -> summary.put("criterionMatches", 0);
            case "duplicate" -> ((com.fasterxml.jackson.databind.node.ArrayNode) summary.path("references")).add(summary.at("/references/0").deepCopy());
            case "path" -> ((ObjectNode) summary.at("/references/0")).put("variationId", "D:/private");
            case "complete" -> { summary.put("unresolvedCandidates", 1); summary.put("discoveredCandidates", 3); }
            case "unavailable" -> summary.put("searchStatus", "UNAVAILABLE");
            default -> fail();
        }
        assertTrue(MissenseReferenceFactSummary.publicView(mapper, summary, PM5).isEmpty());
    }
    @Test void unknownFieldsAreNotPublicAndNoLegacySummaryIsInvented() {
        var summary = MissenseReferenceFactSummary.build(mapper, facts(), PM5); summary.put("privatePath", "D:/private");
        assertFalse(MissenseReferenceFactSummary.publicView(mapper, summary, PM5).toString().contains("private"));
        assertTrue(MissenseReferenceFactSummary.publicView(mapper, mapper.createObjectNode(), PM5).isEmpty());
    }

    @Test void ps1ProjectsOnlyVerifiedDifferentNativeProteinChanges() {
        var input = facts();
        var evaluated = input.putObject("evaluations").putObject("PS1");
        evaluated.putObject("targetIdentity").put("proteinVariantKey", "G20V");
        var assessments = evaluated.putArray("referenceAssessments");
        var independent = assessments.addObject().put("variationId", "1234").put("status", "ELIGIBLE")
                .put("reasonCode", MissenseComparisonRuntime.PS1_DIFFERENT_SUBSTITUTION);
        independent.putObject("nativeProteinIdentity").put("proteinVariantKey", "G20R");
        assessments.add(independent.deepCopy().put("status", "NOT_ADOPTED"));

        var ps1 = MissenseReferenceFactSummary.build(mapper, input, PS1);
        var comparison = ps1.at("/references/0/nativeProteinComparison");
        assertEquals("G20V", comparison.path("target").asText());
        assertEquals("G20R", comparison.path("reference").asText());
        assertTrue(comparison.path("independentReference").asBoolean());
        assertTrue(MissenseReferenceFactSummary.build(mapper, input, PM5).at("/references/0/nativeProteinComparison").isMissingNode());

        ((ObjectNode) comparison).put("reference", "G21R");
        assertTrue(MissenseReferenceFactSummary.publicView(mapper, ps1, PS1).at("/references/0/nativeProteinComparison").isMissingNode());
    }
}
