package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;

/** Synthetic adapter tests. Python source replay/scoring is independently tested. */
class Pp1SelectedBatchTest {
    final ObjectMapper json=new ObjectMapper();
    final List<String> criteria=List.of("ACMG_AMP_2015:PP1");
    ObjectNode context() {
        var c=json.createObjectNode().put("moi","AUTOSOMAL_DOMINANT");
        c.putObject("scope").put("analysisDomain","GERMLINE");c.putObject("gene").put("symbol","GENE1");
        c.putObject("disease").put("mondoId","MONDO:0000001");
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly","GRCh38").put("contig","1")
                .put("position",100).put("reference","A").put("alternate","G");return c;
    }
    ObjectNode binding(ObjectNode ctx) {return new Pp1AnalysisContext(json).bind(ctx,"c","h","a".repeat(64),criteria);}
    Pp1SelectedBatch batch(ObjectNode ctx,Consumer<ObjectNode> mutate,AtomicInteger calls) {
        return batch(ctx, mutate, calls, criteria);
    }
    Pp1SelectedBatch batch(ObjectNode ctx,Consumer<ObjectNode> mutate,AtomicInteger calls,List<String> declaredCriteria) {
        return batch(ctx,mutate,calls,declaredCriteria,null);
    }
    Pp1SelectedBatch batch(ObjectNode ctx,Consumer<ObjectNode> mutate,AtomicInteger calls,List<String> declaredCriteria,
            Pp1ContributionSnapshot.Source contributionSource) {
        var snapshot=json.createObjectNode().put("schemaVersion","pp1-business-source-snapshot-v1").put("analysisId","analysis");
        snapshot.putArray("documents").addObject().put("taskId","b".repeat(64)).putObject("request").set("analysisBinding",
                new Pp1AnalysisContext(json).bind(ctx,"c","h","a".repeat(64),declaredCriteria));
        snapshot.put("snapshotSha256",new RuntimeEvidenceV2Adapter(json).canonicalSha256(snapshot));
        var source=new Pp1SelectedBatch.Source() {
            public ObjectNode identity(){return json.createObjectNode().put("scope","SYNTHETIC");}
            public ObjectNode capture(String id,ObjectNode recovered){return snapshot;}
            public ObjectNode evaluate(ObjectNode original,ObjectNode current) {
                calls.incrementAndGet();var out=json.createObjectNode().put("schemaVersion","pp1-business-evaluation-v1")
                        .put("analysisBindingSha256",new RuntimeEvidenceV2Adapter(json).canonicalSha256(current))
                        .put("modelCalls",0).put("networkRequests",0).put("evaluatorCalls",1);
                boolean unavailable=original.path("documents").isEmpty() || "UNKNOWN".equals(current.at("/ledger/status").asText());
                var eval=out.putObject("evaluation").put("schemaVersion","pp1-segregation-evaluation-v1")
                        .put("status",unavailable?"NOT_EVALUATED":"MET")
                        .put("strength",unavailable?"NONE":"SUPPORTING").put("awardedPP1Points",unavailable?0:1);
                eval.set("target",current.path("selection"));eval.set("ledger",current.path("ledger"));
                eval.putArray("needs").addObject().put("reasonCode","SYNTHETIC_SCOPE");
                out.putArray("sourceCoverage");out.putArray("qualifications");mutate.accept(out);return out;
            }
        };
        var module=new Pp1SelectedBatch(json,source,contributionSource);module.prepare("analysis",json.createObjectNode());return module;
    }
    ObjectNode evaluate(Pp1SelectedBatch module,ObjectNode ctx) {
        var selected=mock(SelectedEvidencePreparation.class);when(selected.pp1SelectionContext("c","h")).thenReturn(ctx);
        var assembly=mock(org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyResult.class);
        when(assembly.analysisId()).thenReturn("analysis");when(selected.assembly()).thenReturn(assembly);
        return module.evaluate(selected,"c","h","a".repeat(64),criteria);
    }
    @Test void currentBoundReplayPreservesUnknownContributionAndKeepsNeeds() {
        var calls=new AtomicInteger();var ctx=context();var module=batch(ctx,r->{},calls);
        var result=evaluate(module,ctx);
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());assertEquals("NONE",result.path("appliedStrength").asText());
        assertEquals("UNKNOWN",result.at("/publicSummary/evaluation/ledger/status").asText());
        assertTrue(result.at("/publicSummary/evaluation/ledger/pp4Points").isNull());
        assertEquals(1,calls.get());assertEquals(1,module.evaluations());
        assertEquals("SYNTHETIC_SCOPE",result.at("/publicSummary/evaluation/needs/0/reasonCode").asText());
    }
    @Test void differentDiseaseDoesNotBorrowTask() {
        var ctx=context();var module=batch(ctx,r->{},new AtomicInteger());ctx.withObject("/disease").put("mondoId","MONDO:0000002");
        var result=evaluate(module,ctx);
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
        assertEquals("PP1_TASK_SELECTED_CONTEXT_STALE",result.at("/publicSummary/excludedTasks/0/reasonCode").asText());
    }
    @Test void fullProfileSourceIsReplayedWithCurrentSingleCriterionSelectionAndLedger() {
        var ctx=context(); var calls=new AtomicInteger();
        var result=evaluate(batch(ctx, r -> {
            assertEquals(binding(ctx).path("selection"),r.at("/evaluation/target"));
            assertEquals(binding(ctx).path("ledger"),r.at("/evaluation/ledger"));
        },calls,List.of(CRITERION,"ACMG_AMP_2015:PS2")),ctx);
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
        assertEquals(0,result.at("/publicSummary/excludedTasks").size()); assertEquals(1,calls.get());
    }
    private static final String CRITERION="ACMG_AMP_2015:PP1";
    @Test void changedAlleleGeneCandidateHypothesisOrSourceCannotBorrowFullProfileTask() {
        for(String field:List.of("allele","gene","candidate","hypothesis","source")) {
            var old=context();var current=old.deepCopy();
            if(field.equals("allele"))current.withObject("/variant/evidenceQueryAllele").put("position",101);
            if(field.equals("gene"))current.withObject("/gene").put("symbol","OTHER");
            String candidate=field.equals("candidate")?"other-candidate":"c";
            String hypothesis=field.equals("hypothesis")?"other-hypothesis":"h";
            var selected=mock(SelectedEvidencePreparation.class);when(selected.pp1SelectionContext(candidate,hypothesis)).thenReturn(current);
            var module=batch(old,r->{},new AtomicInteger(),List.of(CRITERION,"ACMG_AMP_2015:PS2"));
            var result=module.evaluate(selected,candidate,hypothesis,field.equals("source")?"e".repeat(64):"a".repeat(64),criteria);
            assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText(),field);
            assertEquals("PP1_TASK_SELECTED_CONTEXT_STALE",result.at("/publicSummary/excludedTasks/0/reasonCode").asText(),field);
        }
    }
    @Test void workerCannotReturnOtherLedgerOrSpendDuringScoring() {
        for(var mutation:List.<Consumer<ObjectNode>>of(r->r.withObject("/evaluation/ledger").put("pp4Points",4),r->r.put("modelCalls",1))) {
            var ctx=context();var module=batch(ctx,mutation,new AtomicInteger());var result=evaluate(module,ctx);
            assertEquals("PP1_EVALUATION_BINDING_INVALID",result.at("/evaluationResult/reasonCode").asText());
            assertEquals("NONE",result.path("appliedStrength").asText());
        }
    }
    @Test void unpreparedSnapshotIsExplicit() throws Exception {
        var source=mock(Pp1SelectedBatch.Source.class);when(source.identity()).thenReturn(json.createObjectNode());
        var result=evaluate(new Pp1SelectedBatch(json,source),context());
        assertEquals("PP1_SOURCE_SNAPSHOT_NOT_PREPARED",result.at("/evaluationResult/reasonCode").asText());
        verify(source,never()).evaluate(any(),any());
    }
    @Test void sourceReuseCapabilityChangesFreshRuntimeIdentityWithoutRewritingLegacyVersion() {
        var module=batch(context(),r->{},new AtomicInteger());
        var current=module.identity();
        assertEquals("VERIFIED_SAME_CONTEXT_ACROSS_CRITERIA_V1",current.path("sourceReuseCapability").asText());
        var legacy=current.deepCopy();legacy.remove("sourceReuseCapability");
        var adapter=new RuntimeEvidenceV2Adapter(json);
        assertNotEquals(adapter.canonicalSha256(legacy),adapter.canonicalSha256(current));
        assertEquals(legacy.path("version"),current.path("version"));
        assertEquals(legacy.path("source"),current.path("source"));
        assertEquals(Pp1ContributionSnapshot.CAPABILITY,current.path("contributionBindingCapability").asText());
        var oldContribution=current.deepCopy();oldContribution.remove(List.of("contributionBindingCapability","contributionSource"));
        assertNotEquals(adapter.canonicalSha256(oldContribution),adapter.canonicalSha256(current));
        current.put("sourceReuseCapability","tampered");
        assertEquals("VERIFIED_SAME_CONTEXT_ACROSS_CRITERIA_V1",module.identity().path("sourceReuseCapability").asText());
    }
    @Test void unsupportedStrengthIsRejected() {
        var ctx=context();var result=evaluate(batch(ctx,r->r.withObject("/evaluation").put("strength","VERY_STRONG"),new AtomicInteger()),ctx);
        assertEquals("PP1_EVALUATION_STRENGTH_INVALID",result.at("/evaluationResult/reasonCode").asText());
        assertFalse(result.path("publicSummary").has("evaluation"));
    }
    @Test void aWorkerCannotPublishAMetOrNegativeDecisionByOverridingUnknownContribution() {
        for(String status:List.of("MET","NOT_MET")) {
            var ctx=context();var result=evaluate(batch(ctx,r->r.withObject("/evaluation").put("status",status),new AtomicInteger()),ctx);
            assertEquals("PP1_CURRENT_CONTRIBUTION_REQUIRED",result.at("/evaluationResult/reasonCode").asText());
            assertEquals("NONE",result.path("appliedStrength").asText());
            assertFalse(result.path("publicSummary").has("evaluation"));
        }
    }
    @Test void explicitSyntheticInternalInventoryPreservesPp4AndRejectsOverCapacity() {
        var ctx=context();var source=syntheticCurrentContribution(ctx,"4");
        var accepted=evaluate(batch(ctx,r->{},new AtomicInteger(),criteria,source),ctx);
        assertEquals("MET",accepted.at("/evaluationResult/status").asText());
        assertEquals("4",accepted.at("/publicSummary/evaluation/ledger/pp4Points").asText());
        var rejected=evaluate(batch(ctx,r->r.withObject("/evaluation").put("awardedPP1Points",2),new AtomicInteger(),criteria,source),ctx);
        assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",rejected.at("/evaluationResult/reasonCode").asText());
        assertEquals("NONE",rejected.path("appliedStrength").asText());
    }
    @Test void publishedMeiosisCountsAndProofSurviveTheSelectedEvidenceSummary() {
        var ctx=context();
        var accepted=evaluate(batch(ctx,r->{
            var evaluation=r.withObject("/evaluation").put("strength","STRONG").put("awardedPP1Points",4);
            evaluation.putObject("meiosisAssessment").put("informativeMeioses",7).put("qualifiedFamilyCount",2);
            evaluation.putArray("events").addObject().put("parentLabel","I:1").put("memberLabel","II:2")
                    .put("informativeMeioses",1).putArray("sourceRefs").addObject().put("pmcid","PMC123");
        },new AtomicInteger(),criteria,syntheticCurrentContribution(ctx,"0")),ctx);
        assertEquals("MET",accepted.at("/evaluationResult/status").asText());
        assertEquals("STRONG",accepted.path("appliedStrength").asText());
        var saved=accepted.at("/publicSummary/evaluation");
        assertEquals(7,saved.at("/meiosisAssessment/informativeMeioses").asInt());
        assertEquals(2,saved.at("/meiosisAssessment/qualifiedFamilyCount").asInt());
        assertEquals("I:1",saved.at("/events/0/parentLabel").asText());
        assertEquals("PMC123",saved.at("/events/0/sourceRefs/0/pmcid").asText());
    }
    private Pp1ContributionSnapshot.Source syntheticCurrentContribution(ObjectNode ctx,String used) {
        var current=binding(ctx);
        var authority=json.createObjectNode().put("origin","SERVICE_OWNED").put("scope","CURRENT_EVALUATION_ONLY")
                .put("providerId","TEST_ONLY_SYNTHETIC_ADOPTED_PP4");
        var inventory=json.createObjectNode().put("schemaVersion",Pp1ContributionSnapshot.SCHEMA).put("analysisId","analysis")
                .put("sourceIdentitySha256",Pp1ContributionSnapshot.digest(json,authority));
        var entry=inventory.putArray("entries").addObject().put("bindingIdentitySha256",Pp1ContributionSnapshot.digest(json,current.path("identity")));
        entry.set("scope",current.at("/ledger/scope"));entry.set("allele",current.at("/ledger/allele"));
        entry.putObject("coverage").put("status","COMPLETE").put("provenanceSha256","b".repeat(64))
                .putArray("checkedResultSha256s").add("c".repeat(64));
        var contribution=entry.putArray("contributions").addObject().put("contributionId","d".repeat(64))
                .put("criterionId","ACMG_AMP_2015:PP4").put("status","ADOPTED").put("sourceResultSha256","c".repeat(64))
                .put("points",used);
        contribution.putArray("eventIds");contribution.putArray("exclusiveFactIds");
        inventory.put("snapshotSha256",Pp1ContributionSnapshot.digest(json,inventory));
        return new Pp1ContributionSnapshot.Source() {
            public ObjectNode identity(){return authority.deepCopy();}
            public ObjectNode capture(String analysis,ObjectNode recovered){return inventory.deepCopy();}
        };
    }
    @Test void decimalCapacityIsExactForBothLegacyAndFormalAllocationFields() {
        for(String used:List.of("0.5","1.25"))for(boolean formal:List.of(false,true)) {
            var ctx=context();var source=syntheticCurrentContribution(ctx,used);
            Consumer<ObjectNode> method=r->{if(formal)r.withObject("/evaluation").put("schemaVersion","pp1-segregation-evaluation-v2")
                    .put("method","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1").put("allocatedPP1Points","1");};
            var accepted=evaluate(batch(ctx,method,new AtomicInteger(),criteria,source),ctx);
            assertEquals("MET",accepted.at("/evaluationResult/status").asText());
            String field=formal?"allocatedPP1Points":"awardedPP1Points";
            var rejected=evaluate(batch(ctx,r->{method.accept(r);r.withObject("/evaluation").put(field,used.equals("0.5")?"5":"4");},
                    new AtomicInteger(),criteria,source),ctx);
            assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",rejected.at("/evaluationResult/reasonCode").asText());
        }
    }
    @Test void neitherLegacyNorFormalMetCanOmitItsActualAllocationField() {
        for(boolean formal:List.of(false,true)) {
            var ctx=context();var source=syntheticCurrentContribution(ctx,"1.25");
            var result=evaluate(batch(ctx,r->{
                var evaluation=r.withObject("/evaluation");
                if(formal)evaluation.put("schemaVersion","pp1-segregation-evaluation-v2")
                        .put("method","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1");
                evaluation.remove("awardedPP1Points");evaluation.remove("allocatedPP1Points");
            },new AtomicInteger(),criteria,source),ctx);
            assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",result.at("/evaluationResult/reasonCode").asText());
            assertEquals("NONE",result.path("appliedStrength").asText());
            assertFalse(result.path("publicSummary").has("evaluation"));
        }
    }
    @Test void formalAllocatedFieldCannotBypassLongDecimalRemainderOrMalformedValue() {
        for(String allocation:List.of("3.7500000000000000000000001","bad","NaN","-0.1")) {
            var ctx=context();var source=syntheticCurrentContribution(ctx,"1.25");
            var result=evaluate(batch(ctx,r->{r.withObject("/evaluation").put("schemaVersion","pp1-segregation-evaluation-v2")
                    .put("method","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1")
                    .put("allocatedPP1Points",allocation);},new AtomicInteger(),criteria,source),ctx);
            assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",result.at("/evaluationResult/reasonCode").asText());
        }
        var ctx=context();var source=syntheticCurrentContribution(ctx,"4.0000000000000000000000001");
        assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",evaluate(batch(ctx,r->{},new AtomicInteger(),criteria,source),ctx)
                .at("/evaluationResult/reasonCode").asText());
    }
    @Test void formalNonPositiveDecisionCannotAwardPointsButRawOppositionRemainsAllowed() {
        var ctx=context();var source=syntheticCurrentContribution(ctx,"1.25");
        for(String status:List.of("NOT_MET","NOT_EVALUATED")) {
            Consumer<ObjectNode> negative=r->r.withObject("/evaluation").put("schemaVersion","pp1-segregation-evaluation-v2")
                    .put("method","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1")
                    .put("status",status).put("strength","NONE").put("awardedPP1Points",0)
                    .put("allocatedPP1Points","0").put("rawCalibratedPoints","-1");
            assertEquals(status,evaluate(batch(ctx,negative,new AtomicInteger(),criteria,source),ctx).at("/evaluationResult/status").asText());
            assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",evaluate(batch(ctx,r->{negative.accept(r);
                r.withObject("/evaluation").put("awardedPP1Points",1);},new AtomicInteger(),criteria,source),ctx)
                    .at("/evaluationResult/reasonCode").asText());
            if(status.equals("NOT_EVALUATED"))assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",evaluate(batch(ctx,r->{negative.accept(r);
                r.withObject("/evaluation").put("allocatedPP1Points","0.1");},new AtomicInteger(),criteria,source),ctx)
                    .at("/evaluationResult/reasonCode").asText());
        }
    }
    @Test void formalMetAwardIsDistinctFromPotentialAndBoundToOriginalStrength() {
        var ctx=context();var source=syntheticCurrentContribution(ctx,"3.75");
        Consumer<ObjectNode> method=r->r.withObject("/evaluation").put("schemaVersion","pp1-segregation-evaluation-v2")
                .put("method","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1").put("allocatedPP1Points","1.25");
        var accepted=evaluate(batch(ctx,method,new AtomicInteger(),criteria,source),ctx);
        assertEquals("MET",accepted.at("/evaluationResult/status").asText());
        assertEquals(1,accepted.at("/publicSummary/evaluation/awardedPP1Points").asInt());
        assertEquals("1.25",accepted.at("/publicSummary/evaluation/allocatedPP1Points").asText());
        assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",evaluate(batch(ctx,r->{method.accept(r);
            r.withObject("/evaluation").put("awardedPP1Points","1");
        },new AtomicInteger(),criteria,source),ctx).at("/evaluationResult/reasonCode").asText());
        for(int awarded:List.of(0,2,4))assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",evaluate(batch(ctx,r->{
            method.accept(r);r.withObject("/evaluation").put("awardedPP1Points",awarded);
        },new AtomicInteger(),criteria,source),ctx).at("/evaluationResult/reasonCode").asText());
        assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",evaluate(batch(ctx,r->{method.accept(r);
            r.withObject("/evaluation").put("strength","MODERATE");
        },new AtomicInteger(),criteria,source),ctx).at("/evaluationResult/reasonCode").asText());
        assertEquals("PP1_EVALUATION_SHARED_CAPACITY_INVALID",evaluate(batch(ctx,r->{method.accept(r);
            r.withObject("/evaluation").put("strength","STRONG").put("awardedPP1Points",4);
        },new AtomicInteger(),criteria,source),ctx).at("/evaluationResult/reasonCode").asText());
    }
    @Test void formalUnknownLedgerCannotCarryAnAdoptedAward() {
        for(String status:List.of("NOT_EVALUATED","NOT_MET")) {
            var ctx=context();var result=evaluate(batch(ctx,r->r.withObject("/evaluation")
                    .put("schemaVersion","pp1-segregation-evaluation-v2").put("method","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1")
                    .put("status",status).put("awardedPP1Points",1),new AtomicInteger()),ctx);
            assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
            assertEquals("NONE",result.path("appliedStrength").asText());assertFalse(result.path("publicSummary").has("evaluation"));
            assertEquals(status.equals("NOT_MET")?"PP1_CURRENT_CONTRIBUTION_REQUIRED":"PP1_EVALUATION_SHARED_CAPACITY_INVALID",
                    result.at("/evaluationResult/reasonCode").asText());
        }
    }
    @Test void unknownOrContradictorySchemaAndMethodCannotMasqueradeAsFormal() {
        for(var pair:List.of(List.of("pp1-segregation-evaluation-v2","WRONG_METHOD"),
                List.of("pp1-segregation-evaluation-v2",""),List.of("pp1-segregation-evaluation-v1","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1"),
                List.of("pp1-segregation-evaluation-v999","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1"),
                List.of("","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1"))) {
            var ctx=context();var source=syntheticCurrentContribution(ctx,"1.25");
            var result=evaluate(batch(ctx,r->{var e=r.withObject("/evaluation");e.put("schemaVersion",pair.get(0));
                if(pair.get(1).isEmpty())e.remove("method");else e.put("method",pair.get(1));
                e.put("allocatedPP1Points","1");},new AtomicInteger(),criteria,source),ctx);
            assertEquals("PP1_EVALUATION_METHOD_INVALID",result.at("/evaluationResult/reasonCode").asText());
            assertFalse(result.path("publicSummary").has("evaluation"));
        }
    }
    @Test void realPythonBelowThresholdCalibrationRetainsItsPositivePotential() throws Exception {
        ObjectNode fixture;
        try(var input=getClass().getResourceAsStream("/pp1-segregation-core/formal-calibration-bf1_5.json")) {
            assertNotNull(input);fixture=(ObjectNode)json.readTree(input);
        }
        assertEquals("REAL_PYTHON_CALIBRATE_ON_SYNTHETIC_BAYES_FACTOR",fixture.at("/provenance/kind").asText());
        var calibration=(ObjectNode)fixture.path("calibration");
        assertEquals("NOT_MET",calibration.path("status").asText());assertEquals(0,calibration.path("awardedPP1Points").asInt());
        assertTrue(new java.math.BigDecimal(calibration.path("allocatedPP1Points").asText()).signum()>0);
        var ctx=context();var source=syntheticCurrentContribution(ctx,"0");
        var result=evaluate(batch(ctx,r->{r.withObject("/evaluation").setAll(calibration);
            r.withObject("/evaluation").put("schemaVersion","pp1-segregation-evaluation-v2")
                    .put("method","VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1");
        },new AtomicInteger(),criteria,source),ctx);
        assertEquals("NOT_MET",result.at("/evaluationResult/status").asText());assertEquals("NONE",result.path("appliedStrength").asText());
        assertEquals(calibration.path("allocatedPP1Points"),result.at("/publicSummary/evaluation/allocatedPP1Points"));
        assertEquals(0,result.at("/publicSummary/evaluation/awardedPP1Points").asInt());
    }
}
