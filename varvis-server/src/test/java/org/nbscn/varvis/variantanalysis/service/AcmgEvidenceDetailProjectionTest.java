package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AcmgEvidenceDetailProjectionTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @TempDir Path temporary;
    private ObjectNode privateBatch;
    private ObjectNode response()throws Exception{
        privateBatch=(ObjectNode)mapper.readTree("""
            {"modules":{"pm2":{"results":[{"candidateId":"c1","hypothesisId":"h1","context":{"contextId":"ctx"},
            "evaluation":{"evaluationResult":{"status":"MET"}},
            "policySelection":{"policy":{"route":"GENERAL_ABSENCE_ONLY","allSitesCallability":{"exomes":{
            "minimumAdjAlleleNumber":{"rawValue":"2000","comparisonOperator":"GREATER_THAN"},"excludedWhenAnyFlagsTrue":["outside_broad_capture_region"]}}}},
            "observation":{"carrier":{"queryOutcome":{"status":"NO_RECORD"}}},"allSitesObservation":{"status":"HIT","adjAn":"10000","regionFlags":{"outside_broad_capture_region":false}}}]}},
            "results":[{"candidateId":"c1","hypothesisId":"h1","localBatch":{"criteria":[{"criterionId":"ACMG_AMP_2015:PM2","status":"MET","appliedStrength":"SUPPORTING","reasonCode":"SAVED",
            "details":{"modulePointer":"/modules/pm2/results/0","originalResult":{"evaluationResult":{"status":"MET"}}}}]}}]}
            """);
        var response=mapper.createObjectNode();response.putObject("localBatch");
        var candidate=response.putArray("candidates").addObject().put("candidateId","c1");
        var attempt=candidate.putArray("attempts").addObject().put("hypothesisId","h1");attempt.putObject("context").put("contextId","ctx");
        attempt.set("localBatch",privateBatch.at("/results/0/localBatch").deepCopy());
        var details=(ObjectNode)attempt.at("/localBatch/criteria/0/details");
        details.put("originalResultSha256",new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(details.path("originalResult")));details.remove("originalResult");
        return response;
    }
    @Test void sameSavedBatchAddsReadOnlyRuleFactsWithoutRewritingScientificResultOrOriginalBytes()throws Exception{
        var response=response();String before=response.toString(),privateBefore=privateBatch.toString();
        var view=AcmgEvidenceDetailProjection.enrich(mapper,response,privateBatch);
        assertEquals("AN > 2000",view.at("/candidates/0/attempts/0/localBatch/criteria/0/details/populationRule/conditions/2/requirement").asText());
        var conditions=view.at("/candidates/0/attempts/0/localBatch/criteria/0/details/populationRule/conditions");
        assertEquals(4,conditions.size());
        for(var row:conditions)assertEquals("TRUE",row.path("recordedOutcome").asText());
        assertEquals(before,response.toString());assertEquals(privateBefore,privateBatch.toString());
        assertEquals("MET",view.at("/candidates/0/attempts/0/localBatch/criteria/0/status").asText());
    }
    @Test void pm2FlagsOnlyRecordedThresholdFailuresAndLeavesMissingRegionUnknown()throws Exception{
        var response=response();
        var allSites=(ObjectNode)privateBatch.at("/modules/pm2/results/0/allSitesObservation");
        allSites.put("adjAn","2000").remove("regionFlags");
        var view=AcmgEvidenceDetailProjection.enrich(mapper,response,privateBatch);
        var conditions=view.at("/candidates/0/attempts/0/localBatch/criteria/0/details/populationRule/conditions");
        assertEquals("FALSE",conditions.at("/2/recordedOutcome").asText());
        assertEquals("UNKNOWN",conditions.at("/3/recordedOutcome").asText());
    }
    @Test void wrongContextPointerHashOrAmbiguousSavedCriterionCannotSupplyHistoricThreshold()throws Exception{
        for(int mode=0;mode<4;mode++){
            var response=response();
            if(mode==0)((ObjectNode)privateBatch.at("/modules/pm2/results/0/context")).put("contextId","different");
            if(mode==1)((ObjectNode)response.at("/candidates/0/attempts/0/localBatch/criteria/0/details")).put("modulePointer","/modules/pm2/results/99");
            if(mode==2)((ObjectNode)response.at("/candidates/0/attempts/0/localBatch/criteria/0/details")).put("originalResultSha256","wrong");
            if(mode==3)((com.fasterxml.jackson.databind.node.ArrayNode)privateBatch.at("/results/0/localBatch/criteria")).add(privateBatch.at("/results/0/localBatch/criteria/0").deepCopy());
            var view=AcmgEvidenceDetailProjection.enrich(mapper,response,privateBatch);
            assertFalse(view.at("/candidates/0/attempts/0/localBatch/criteria/0/details").has("populationRule"));
        }
    }
    @Test void viewFingerprintAndCachedBackgroundStillIdentifyUnmodifiedSavedSource()throws Exception{
        var saved=response();String before=saved.toString(),sha=AcmgBusinessStore.sha(before);
        var views=new AcmgResultViews();
        var index=views.readWithDetails(()->"same-file",()->new AcmgResultViews.VerifiedResult(saved,
                full->AcmgEvidenceDetailProjection.enrich(mapper,full,privateBatch)),null,0,null);
        assertEquals(sha,index.path("sourceViewSha256").asText());
        var background=views.readWithDetails(()->"same-file",()->{throw new AssertionError("must reuse verified cached view");},"c1",0,sha);
        assertTrue(background.at("/candidates/0/attempts/0/localBatch/criteria/0/details").has("populationRule"));
        assertEquals(before,saved.toString());
    }
    @Test void predictionDisplayRecoversOnlySameRecordedMechanismNumbersNotPrivateOrAuxiliaryObservations()throws Exception{
        predictionContextReplay(false);
    }
    @Test void projectedLegacyPredictionContextCanSupplyNumbersOnlyForItsOriginalWorkbenchContext()throws Exception{
        predictionContextReplay(true);
    }
    private void predictionContextReplay(boolean useProjection)throws Exception{
        var response=response();
        var original=mapper.createObjectNode();var plan=original.putObject("plan").put("route","GENERAL_LOCAL_ALPHAMISSENSE").put("candidateId","c1").put("hypothesisId","h1");
        plan.putObject("context").put("contextId","ctx");
        if(useProjection){
            var fixture=new SelectedEvidencePreparationTest();fixture.temporary=temporary;
            var prepared=fixture.prepare(false,false,false,1,true);
            var candidateId=prepared.original().getCandidateId();
            var attempt=(ObjectNode)response.at("/candidates/0/attempts/0");
            var projection=prepared.result().legacyProjection(candidateId,"context-one");
            ((ObjectNode)response.at("/candidates/0")).put("candidateId",candidateId);
            ((ObjectNode)privateBatch.at("/results/0")).put("candidateId",candidateId).put("hypothesisId","context-one");
            attempt.put("hypothesisId","context-one").set("context",projection.snapshot().path("originalContext"));
            plan.put("candidateId",candidateId).put("hypothesisId","context-one").set("context",projection.context());
            plan.set("sourceProjection",projection.snapshot());original.set("sourceProjection",projection.snapshot());
        }
        original.putObject("mechanisms").putObject("PROTEIN_MISSENSE").put("status","CALIBRATED").put("direction","HARMFUL").put("appliedStrength","STRONG")
            .put("rawScore","0.99").putObject("band").put("lower","0.9").put("upper","1").put("lowerInclusive",true).put("upperInclusive",true);
        var rawCriterion=original.putObject("criteria").putObject("PP3").put("status","MET").put("selectedMechanism","PROTEIN_MISSENSE");
        original.set("mechanismSummary",org.nbscn.varvis.variantanalysis.provider.PredictionMechanismSummary.fromResult(mapper,original));
        ((ObjectNode)privateBatch.path("modules")).putObject("prediction").putArray("results").add(original);
        var saved=(ObjectNode)privateBatch.at("/results/0/localBatch/criteria/0");saved.put("criterionId","ACMG_AMP_2015:PP3");
        var details=(ObjectNode)saved.path("details");details.put("modulePointer","/modules/prediction/results/0");details.set("originalResult",rawCriterion.deepCopy());
        var publicCriterion=saved.deepCopy();var publicDetails=(ObjectNode)publicCriterion.path("details");
        publicDetails.put("originalResultSha256",new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(rawCriterion));publicDetails.remove("originalResult");
        var summary=original.path("mechanismSummary").deepCopy();((ObjectNode)summary.at("/mechanisms/0")).remove(java.util.List.of("values","thresholds"));
        publicDetails.set("predictionMechanisms",summary);
        ((com.fasterxml.jackson.databind.node.ArrayNode)response.at("/candidates/0/attempts/0/localBatch/criteria")).set(0,publicCriterion);
        var view=AcmgEvidenceDetailProjection.enrich(mapper,response,privateBatch);
        assertEquals("0.99",view.at("/candidates/0/attempts/0/localBatch/criteria/0/details/predictionMechanisms/mechanisms/0/values/rawScore").asText());
        assertEquals("PROTEIN_MISSENSE",view.at("/candidates/0/attempts/0/localBatch/criteria/0/details/selectedPredictionMechanism").asText());
        assertFalse(response.at("/candidates/0/attempts/0/localBatch/criteria/0/details/predictionMechanisms/mechanisms/0").has("values"));
        if(useProjection){
            var projected=(ObjectNode)original.path("sourceProjection");
            var wrong=projected.deepCopy().put("bindingSha256","0".repeat(64));
            original.set("sourceProjection",wrong);plan.set("sourceProjection",wrong.deepCopy());
            assertFalse(AcmgEvidenceDetailProjection.enrich(mapper,response,privateBatch).at("/candidates/0/attempts/0/localBatch/criteria/0/details/predictionMechanisms/mechanisms/0").has("values"));
            original.set("sourceProjection",projected);plan.set("sourceProjection",projected.deepCopy());
        }
        ((ObjectNode)original.at("/mechanisms/PROTEIN_MISSENSE")).put("direction","BENIGN");
        assertFalse(AcmgEvidenceDetailProjection.enrich(mapper,response,privateBatch).at("/candidates/0/attempts/0/localBatch/criteria/0/details/predictionMechanisms/mechanisms/0").has("values"));
    }
}
