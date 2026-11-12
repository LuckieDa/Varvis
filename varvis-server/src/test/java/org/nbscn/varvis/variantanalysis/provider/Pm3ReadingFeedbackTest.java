package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pm3ReadingFeedbackTest {
    @Test void unresolvedCasesRetainSeparateNeedsAndAlternativeRequirementsBeforeScoring() {
        var p=paper(true);
        var cases=p.putObject("observations").putArray("cases");
        for(String label:java.util.List.of("case-A","case-B")) {
            var c=cases.addObject().put("caseLabel",label);
            c.putArray("facts").addObject().putObject("citation").put("unitId","/article/table[1]/tr[2]/td[1]");
            var gap=c.putObject("semantics").putArray("assemblyGaps").addObject()
                    .put("reasonCode","PM3_CASE_PARTNER_OR_HOMOZYGOSITY_UNRESOLVED");
            var alternatives=gap.putArray("requiredAnyOf");alternatives.addArray().add("partnerVariant");alternatives.addArray().add("reportedZygosity");
        }
        var out=feedback(p,json.createObjectNode());
        assertEquals("CONTINUE_READING",out.path("state").asText());
        int count=0;
        for(var need:out.at("/papers/0/needs"))if(need.path("reasonCode").asText().equals("PM3_CASE_PARTNER_OR_HOMOZYGOSITY_UNRESOLVED")) {
            count++;assertEquals("GENOTYPE",need.path("group").asText());
            assertEquals(2,need.path("requiredAnyOf").size());assertEquals(1,need.path("sourceUnitIds").size());
        }
        assertEquals(2,count);
        p.withArray("issues").add("DENOVO_MODEL_TIMEOUT");
        out=feedback(p,json.createObjectNode());
        assertEquals("INCOMPLETE",out.path("state").asText());
        assertEquals("EXTRACTION_INCOMPLETE",out.at("/papers/0/factAvailability").asText());
        assertFalse(out.at("/papers/0/absenceEstablished").asBoolean(true));
    }
    final ObjectMapper json=new ObjectMapper();
    ObjectNode paper(boolean pending) {
        var p=json.createObjectNode().put("pmcid","PMC123").put("bundleSha256","a".repeat(64));
        var plan=p.putObject("readingPlan").put("scope","HEURISTIC_RELEVANT_TARGET_UNITS_NOT_FULL_PAPER");
        if(pending)plan.putArray("pendingRelevantChunks").add(1);else plan.putArray("pendingRelevantChunks");
        p.putArray("completedReviewChunks").add(0);p.putArray("issues");return p;
    }
    ObjectNode assessment(String status,String reason,String requirementStatus) {
        var a=json.createObjectNode();var calc=a.putArray("diseaseAssessments").addObject().putObject("calculation").put("status",status);
        var contribution=calc.putArray("contributions").addObject().put("reason",reason).put("observationId","case:0");
        contribution.putArray("sourceReferences").add("PMC123#/article/table[1]/tr[2]/td[1]");
        if(requirementStatus!=null)contribution.putArray("requirements").addObject().put("reason",reason).put("status",requirementStatus);
        return a;
    }
    ObjectNode feedback(ObjectNode p,ObjectNode a){return new Pm3ReadingFeedback(json).build(p,a,"b".repeat(64));}
    @Test void contributionNeedIsAssignedOnlyToItsExactPaperAndSourceUnits() {
        var worker=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");
        worker.putArray("papers").add(paper(true)).add(paper(true).put("pmcid","PMC1234"));
        var a=assessment("NOT_EVALUATED","PM3_TARGET_IDENTITY_UNRESOLVED","MISSING_INFORMATION");
        var before=a.deepCopy();var f=feedback(worker,a);
        assertEquals(1,f.at("/papers/0/needs").size());
        assertEquals("/article/table[1]/tr[2]/td[1]",f.at("/papers/0/needs/0/sourceUnitIds/0").asText());
        assertTrue(f.at("/papers/1/needs").isEmpty());
        assertTrue(f.path("unassignedNeeds").isEmpty());assertEquals(before,a);
    }
    @Test void missingOrAmbiguousSourceIsRetainedWithoutAssigningItToAnotherPaper() {
        var a=assessment("NOT_EVALUATED","PM3_TARGET_IDENTITY_UNRESOLVED","MISSING_INFORMATION");
        ((ObjectNode)a.at("/diseaseAssessments/0/calculation/contributions/0")).remove("sourceReferences");
        var f=feedback(paper(true),a);
        assertEquals("INCOMPLETE",f.path("state").asText());
        assertEquals(1,f.path("unassignedNeeds").size());assertTrue(f.at("/papers/0/needs").isEmpty());
        var worker=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");
        worker.putArray("papers").add(paper(true)).add(paper(true).put("bundleSha256","c".repeat(64)));
        f=feedback(worker,assessment("NOT_EVALUATED","PM3_TARGET_IDENTITY_UNRESOLVED","MISSING_INFORMATION"));
        assertEquals("PM3_READING_NEED_SOURCE_UNRESOLVED",f.path("stopReason").asText());
        assertEquals(1,f.path("unassignedNeeds").size());assertTrue(f.at("/papers/0/needs").isEmpty());
    }
    @Test void positiveDoesNotSkipPendingConflictScope() {
        var a=assessment("MET","PM3_OBSERVATION_COUNTED",null);
        assertEquals("CONTINUE_READING",feedback(paper(true),a).path("state").asText());
        assertEquals("SUFFICIENT_WITHIN_CHECKED_SCOPE",feedback(paper(false),a).path("state").asText());
    }
    @Test void legitimateUnknownPhaseBranchDoesNotAcquireMandatoryPhase() {
        var f=feedback(paper(false),assessment("MET","PM3_OBSERVATION_COUNTED",null));
        assertTrue(f.at("/papers/0/needs").isEmpty());assertFalse(f.path("fullPaperRead").asBoolean());
    }
    @Test void missingDrivenModeStopsOnCompleteCurrentObservationWithoutClaimingAllRead() {
        var f=new Pm3ReadingFeedback(json).build(paper(true),assessment("MET","PM3_OBSERVATION_COUNTED",null),"b".repeat(64),true);
        assertEquals("SUFFICIENT_WITHIN_CHECKED_SCOPE",f.path("state").asText());
        assertTrue(f.path("hasPendingRelevantWork").asBoolean());assertFalse(f.path("fullPaperRead").asBoolean());
        assertEquals("PM3_CURRENT_REQUIREMENTS_SATISFIED",f.path("stopReason").asText());
        var missing=new Pm3ReadingFeedback(json).build(paper(true),assessment("NOT_EVALUATED","PM3_TARGET_IDENTITY_UNRESOLVED","MISSING_INFORMATION"),"b".repeat(64),true);
        assertEquals("CONTINUE_READING",missing.path("state").asText());
    }
    @Test void missingIdentityIsNotPaperAbsenceBeforeCoverageComplete() {
        var a=assessment("NOT_EVALUATED","PM3_TARGET_IDENTITY_UNRESOLVED","MISSING_INFORMATION");
        assertEquals("CONTINUE_READING",feedback(paper(true),a).path("state").asText());
        var f=feedback(paper(false),a);
        assertEquals("SEARCHED_SCOPE_INSUFFICIENT",f.path("state").asText());
        assertEquals("IDENTITY",f.at("/papers/0/needs/0/group").asText());
    }
    @Test void conflictIsNotOverwrittenByPositiveOtherCase() {
        var f=feedback(paper(false),assessment("MET","PM3_OCCURRENCE_CONFLICT","CONFLICT"));
        assertEquals("CONFLICT_REQUIRES_REVIEW",f.path("state").asText());
    }
    @Test void cachePermissionAndTransportFailureMeanIncomplete() {
        var p=paper(true);p.withArray("issues").add("MODEL_CACHE_MISSING");
        assertEquals("INCOMPLETE",feedback(p,assessment("MET","PM3_OBSERVATION_COUNTED",null)).path("state").asText());
    }
    @Test void populationGapIsRoutedExternally() {
        var f=feedback(paper(false),assessment("NOT_EVALUATED","PM3_TARGET_RARITY_NOT_ESTABLISHED","MISSING_INFORMATION"));
        assertEquals("EXTERNAL_RESOURCE",f.at("/papers/0/needs/0/group").asText());
        assertEquals("DEPENDENCY_UNRESOLVED",f.path("state").asText());
    }
    @Test void sufficientCacheDoesNotRequireModelPermissionForUnreadBlocks() {
        var p=paper(true);p.put("readingCachePass",true).put("readingModelEnabled",false);
        p.withArray("issues").add("MODEL_CACHE_MISSING");
        var reader=new Pm3ReadingFeedback(json);
        assertEquals("SUFFICIENT_WITHIN_CHECKED_SCOPE",reader.build(p,assessment("MET","PM3_OBSERVATION_COUNTED",null),"b".repeat(64),true).path("state").asText());
        assertEquals("CONTINUE_READING",reader.build(p,assessment("NOT_EVALUATED","PM3_TARGET_IDENTITY_UNRESOLVED","MISSING_INFORMATION"),"b".repeat(64),true).path("state").asText());
        p.withArray("issues").add("MODEL_TRANSPORT_TIMEOUT");
        assertEquals("INCOMPLETE",reader.build(p,assessment("MET","PM3_OBSERVATION_COUNTED",null),"b".repeat(64),true).path("state").asText());
    }
    @Test void sourceRejectedBlockRemainsUnfinishedWhileOtherBlocksContinue() {
        var p=paper(true);p.withArray("issues").add("PM3_MODEL_REVIEW_SOURCE_CHECK_REJECTED");
        p.withObject("/readingPlan").putArray("blockedReviewChunks").add(0);
        var a=assessment("MET","PM3_OBSERVATION_COUNTED",null);
        assertEquals("CONTINUE_READING",feedback(p,a).path("state").asText());
        p.withObject("/readingPlan").putArray("pendingRelevantChunks");
        assertEquals("INCOMPLETE",feedback(p,a).path("state").asText());
    }
}
