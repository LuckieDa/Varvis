package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeNovoResultSummaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void publicProjectionPreservesContributionsAndExclusionsWithoutLocalProse() throws Exception {
        var qualified = mapper.readTree("""
                {"result":{"highHeterogeneityRawQuarterPoints":6,"highHeterogeneityEffectiveQuarterPoints":4,
                 "events":[{"observationId":"patient-secret","eventId":"family-secret","sourceKind":"CURRENT_CASE",
                    "disposition":"EXCLUDED","reasonCode":"DENOVO_PATERNAL_TARGET_TEST_REQUIRED","rawQuarterPoints":0,
                    "confirmedParentage":false,"sourceRefs":["C:/private/report.json","%s#/tests/0"],
                    "details":["DENOVO_PATERNAL_TARGET_TEST_REQUIRED","secret private prose"]},
                  {"eventId":"paper-event","sourceKind":"LITERATURE_CASE","disposition":"COUNTED",
                    "reasonCode":"DENOVO_CONFIRMED","rawQuarterPoints":2,"confirmedParentage":true,"sourceRefs":[],"details":[]} ]},
                 "qualifications":[{"eventId":"paper-event","phenotype":"CONSISTENT_HIGH_HETEROGENEITY","reasons":[]},
                    {"eventId":"family-secret","phenotype":"UNKNOWN","reasons":["DENOVO_PATERNAL_TARGET_TEST_REQUIRED","C:/private/report.json"]} ],
                 "privateReport":"A patient's entire document"}
                """.formatted("a".repeat(64)));
        String before = qualified.toString(); var summary = mapper.createObjectNode();
        DeNovoResultSummary.append(summary, qualified);
        assertEquals(1, summary.at("/events/0/ordinal").asInt());
        assertEquals(2, summary.at("/events/1/rawQuarterPoints").asInt());
        assertEquals("CONSISTENT_HIGH_HETEROGENEITY", summary.at("/events/1/phenotypeCategories/0").asText());
        assertEquals(4, summary.path("highHeterogeneityEffectiveQuarterPoints").asInt());
        assertEquals(1, summary.at("/events/0/sourceRefs").size());
        assertEquals(1, summary.path("qualificationIssues").size());
        for (String secret : new String[]{"patient-secret", "family-secret", "C:/private", "private prose", "entire document"})
            assertFalse(summary.toString().contains(secret), secret);
        assertEquals(before, qualified.toString());
        assertFalse(summary.path("completeLiteratureSearch").asBoolean());
        assertEquals("NONE", summary.path("clinicalApproval").asText());
    }

    @Test void scopeFailureDoesNotInventEventsOrScores() {
        var summary = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "DENOVO_SPECIFICATION_UNAVAILABLE");
        DeNovoResultSummary.append(summary, mapper.missingNode());
        assertTrue(summary.path("events").isEmpty());
        assertTrue(summary.path("qualificationIssues").isEmpty());
        assertFalse(summary.has("effectiveQuarterPoints"));
    }

    @Test void sourceConflictProjectionKeepsTheComparedResultsWithoutLeakingFreeProse() {
        var qualified = mapper.createObjectNode();
        var event = qualified.putObject("result").putArray("events").addObject();
        event.putArray("details").add("DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT:mother:ordinary=PRESENT:special=ABSENT")
                .add("DENOVO_PARENTAGE_EXCLUDED:paternity").add("DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT:private prose and file path");
        var summary = mapper.createObjectNode(); DeNovoResultSummary.append(summary, qualified);
        assertEquals("DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT", summary.at("/events/0/issues/0").asText());
        assertEquals("DENOVO_PARENTAGE_EXCLUDED", summary.at("/events/0/issues/1").asText());
        assertEquals("MOTHER", summary.at("/events/0/factComparisons/0/memberRole").asText());
        assertEquals("PRESENT", summary.at("/events/0/factComparisons/0/ordinaryResult").asText());
        assertEquals("ABSENT", summary.at("/events/0/factComparisons/0/specialEventResult").asText());
        assertFalse(summary.toString().contains("private prose"));
    }

    @Test void explicitMethodsPreserveFixedStrengthAndArAdjustmentWithoutInventingPoints() {
        var qualified = mapper.createObjectNode();
        var result = qualified.putObject("result").put("calculationMethod", "EXPERT_FIXED_STRENGTH")
                .put("strengthAdjustment", "NO_SVI_POINT_SUM");
        var out = mapper.createObjectNode(); DeNovoResultSummary.append(out, qualified);
        assertEquals("EXPERT_FIXED_STRENGTH", out.path("calculationMethod").asText());
        assertEquals("NO_SVI_POINT_SUM", out.path("strengthAdjustment").asText());
        assertFalse(out.has("effectiveQuarterPoints"));
        result.put("calculationMethod", "SVI_POINTS").put("strengthAdjustment", "ONE_LEVEL_AR_NO_SECOND_P_LP");
        DeNovoResultSummary.append(out, qualified);
        assertEquals("ONE_LEVEL_AR_NO_SECOND_P_LP", out.path("strengthAdjustment").asText());
        var unknown = mapper.createObjectNode();
        result.put("calculationMethod", "secret patient prose").put("strengthAdjustment", "secret source path");
        DeNovoResultSummary.append(unknown, qualified);
        assertFalse(unknown.has("calculationMethod")); assertFalse(unknown.has("strengthAdjustment"));
    }

    @Test void decisionExplanationUsesActualResultAndDoesNotScoreAgain() {
        var criteria = mapper.createObjectNode();
        for (String code : new String[]{"PS2", "PM6"}) {
            var entry = criteria.putObject(code).put("criterionId", "ACMG_AMP_2015:" + code)
                    .put("appliedStrength", "PS2".equals(code) ? "SUPPORTING" : "NONE");
            entry.putObject("evaluationResult").put("status", "PS2".equals(code) ? "MET" : "NOT_EVALUATED")
                    .put("reasonCode", "PS2".equals(code) ? "DENOVO_QUALIFIED_POINTS" : "DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2");
        }
        var out = mapper.createObjectNode(); String before = criteria.toString();
        DeNovoResultSummary.appendDecisions(out, criteria);
        assertEquals(before, criteria.toString());
        assertEquals("MET", out.at("/criterionExplanations/0/status").asText());
        assertTrue(out.at("/criterionExplanations/1/summary").asText().contains("不重复计分"));
        assertFalse(out.has("effectiveQuarterPoints"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) criteria.at("/PM6/evaluationResult"))
                .put("status", "NOT_MET").put("reasonCode", "DENOVO_POINTS_BELOW_SUPPORTING");
        DeNovoResultSummary.appendDecisions(out, criteria);
        assertTrue(out.at("/criterionExplanations/1/summary").asText().contains("已完成评价"));
    }

    @Test void coreViewKeepsPublicFactsAndSelectionButExcludesInternalProse() {
        var source = mapper.createObjectNode();
        var document = source.putArray("documents").addObject().put("status", "SOURCE_READ");
        var row = document.putObject("facts").putArray("cases").addObject();
        row.putObject("alleleIdentity").put("status", "UNRESOLVED");
        var core = row.putObject("coreEvaluation").put("caseKey", "PMC123#1")
                .put("documentSha256", "a".repeat(64)).put("ledgerSha256", "b".repeat(64));
        var display = core.putObject("display").put("schemaVersion", "denovo-core-display-v1")
                .put("dataClass", "PUBLIC_PAPER_RESEARCH").put("caseKey", "PMC123#1")
                .put("documentSha256", "a".repeat(64)).put("ledgerSha256", "b".repeat(64))
                .put("status", "INSUFFICIENT_DATA").put("criterionDecision", "NOT_RUN").put("privatePath", "secret-path");
        var field = display.putArray("fields").addObject().put("path", "/groups/parentage/maternity")
                .put("value", "ASSUMED_NOT_CONFIRMED").put("privateReport", "private patient");
        var citations = field.putArray("citations");
        citations.addObject().put("role", "PAPER_PRIMARY").put("quote", "No parentage testing.");
        citations.addObject().put("role", "REVIEW_RECORD").put("quote", "secret authority");
        field.putArray("sourceEvidence").addObject().put("role", "PAPER_ATTACHMENT").put("quote", "Patient 1")
                .put("locationLabel", "Supplement 1, paragraph 2").put("privatePath", "secret attachment path");
        var explanation = display.putObject("explanation").put("schemaVersion", "literature-explanation-v1")
                .put("caseKey", "PMC123#1").put("summary", "Source gap").put("privatePath", "secret summary");
        explanation.putObject("paper").put("paperId", "PMC123").put("title", "Public title");
        explanation.putArray("items").addObject().put("label", "Family").put("privateText", "secret item");
        var located = display.putObject("sourceDiscovery").put("version", "denovo-source-event-discovery-v1")
                .put("criterionDecision", "NOT_RUN").put("privatePath", "secret discovery path");
        var record = located.putArray("records").addObject().put("sourceSha256", "a".repeat(64))
                .put("recordLocator", "table-1").put("isEventCandidate", false).put("privatePath", "secret record path");
        record.putArray("missingPaths").add("/trio/maternity");
        record.putArray("fields").addObject().put("path", "/binding/geneSymbol").put("quote", "SYNTHETIC_GENE")
                .put("locator", "table-1-row-2-cell-2").put("kind", "JATS_UNIT").put("privatePath", "secret field path");
        var out = mapper.createObjectNode();
        DeNovoResultSummary.appendCoreMaterials(out, source, mapper.createObjectNode());
        assertEquals("UNRESOLVED", out.at("/coreMaterials/0/selectionMatch").asText());
        assertEquals("No parentage testing.", out.at("/coreMaterials/0/fields/0/citations/0/quote").asText());
        assertEquals("Patient 1", out.at("/coreMaterials/0/fields/0/sourceEvidence/0/quote").asText());
        assertEquals("Source gap", out.at("/coreMaterials/0/explanation/summary").asText());
        assertEquals("/trio/maternity", out.at("/coreMaterials/0/sourceDiscovery/records/0/missingPaths/0").asText());
        assertEquals("SYNTHETIC_GENE", out.at("/coreMaterials/0/sourceDiscovery/records/0/fields/0/quote").asText());
        assertFalse(out.toString().contains("secret"));
        assertFalse(out.toString().contains("private patient"));
        assertFalse(out.has("effectiveQuarterPoints"));
        display.put("documentSha256", "c".repeat(64));
        DeNovoResultSummary.appendCoreMaterials(out, source, mapper.createObjectNode());
        assertTrue(out.path("coreMaterials").isEmpty());
    }
}
