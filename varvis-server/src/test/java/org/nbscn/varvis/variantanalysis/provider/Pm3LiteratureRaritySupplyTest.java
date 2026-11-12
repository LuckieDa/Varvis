package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Pm3LiteratureRaritySupplyTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode row(boolean homo) {
        var row=new Pm3LiteraturePopulationContextTest().row();
        row.withObject("/reportedObservation").put("kind",homo?"REPORTED_HOMOZYGOUS":"REPORTED_TWO_ALLELES_PHASE_UNRESOLVED");
        if(!homo) {
            var partner=row.at("/identities/target").deepCopy();
            ((ObjectNode)partner.path("allele")).put("alternate","C");
            row.withObject("/identities").set("partner",partner);
        }
        return row;
    }
    ObjectNode paper(ObjectNode row) {
        var p=json.createObjectNode().put("schemaVersion","pm3-paper-process-v1").put("pmcid","PMC123")
                .put("bundleSha256","b".repeat(64)).put("businessPublishable",false).put("scientificExecution",false);
        p.putObject("observations").putArray("cases").addObject().put("caseLabel","Case 1")
                .putArray("resolvedObservations").add(row);
        return p;
    }
    ObjectNode request(ObjectNode row) {return json.createObjectNode().set("selectedAllele",row.at("/identities/target/allele").deepCopy());}
    Pm3LiteratureRaritySupply.Factory factory(Pm2LocalExomesRuntime runtime,AtomicInteger calls) {
        return new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity(){return json.createObjectNode().put("testIdentity","1");}
            public Pm2LocalExomesRuntime create(){calls.incrementAndGet();return runtime;}
        };
    }
    Pm2LocalExomesRuntime unavailable() {
        var runtime=mock(Pm2LocalExomesRuntime.class);var profile=Pm2LocalExomesProfile.load(json);
        when(runtime.profile()).thenReturn(profile.document());when(runtime.evaluationProfile()).thenReturn(profile);
        when(runtime.evaluateReference(any(),any(),any(),anyString(),isNull())).thenAnswer(invocation->{
            var result=json.createObjectNode();result.putObject("evaluationResult").put("reasonCode","PM2_TEST_RESOURCE_UNAVAILABLE");return result;
        });return runtime;
    }
    @Test void bothRolesUseSeparateExactQueriesAndPreserveUnavailableReason() {
        var runtime=unavailable();var calls=new AtomicInteger();var row=row(false);var input=paper(row);var before=input.deepCopy();
        var r=new Pm3LiteratureRaritySupply(json,factory(runtime,calls)).enrich(input,request(row),"analysis",Duration.ofSeconds(5));
        assertEquals(before,input);assertEquals(1,calls.get());
        var q=r.at("/observations/cases/0/resolvedObservations/0/rarityQualification");
        assertEquals("PM2_TEST_RESOURCE_UNAVAILABLE",q.at("/target/reasonCode").asText());
        assertEquals("UNKNOWN",q.at("/partner/qualification").asText());
        var contexts=org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(runtime,times(2)).evaluateReference(contexts.capture(),any(),any(),eq("b".repeat(64)),isNull());
        assertNotEquals(contexts.getAllValues().get(0).at("/variant/evidenceQueryAllele"),contexts.getAllValues().get(1).at("/variant/evidenceQueryAllele"));
        assertEquals(2,r.at("/populationQualification/qualificationAttempts").asInt());
        assertFalse(r.path("businessPublishable").asBoolean());
        when(runtime.evaluatorCalls()).thenReturn(2);
        var evaluated=new Pm3LiteratureRaritySupply(json,factory(runtime,calls)).enrich(input,request(row),"analysis",Duration.ofSeconds(5));
        assertTrue(evaluated.path("scientificExecution").asBoolean());
        assertFalse(evaluated.path("pm3ScientificExecution").asBoolean());
    }
    @Test void homoDoesNotQueryMissingPartnerAndUnreviewedProposalsAreIgnored() {
        var runtime=unavailable();var calls=new AtomicInteger();var row=row(true);var p=paper(row);
        p.withObject("/observations").putObject("unreviewedExtraction").putArray("cases").add(p.at("/observations/cases/0"));
        var r=new Pm3LiteratureRaritySupply(json,factory(runtime,calls)).enrich(p,request(row),"analysis",Duration.ofSeconds(5));
        verify(runtime,times(1)).evaluateReference(any(),any(),any(),anyString(),isNull());
        assertEquals("NOT_APPLICABLE",r.at("/observations/cases/0/resolvedObservations/0/rarityQualification/partner/qualification").asText());
    }
    @Test void missingDiseaseWrongTargetAndExpiredBudgetNeverInitializeProvider() {
        var runtime=unavailable();var calls=new AtomicInteger();var supply=new Pm3LiteratureRaritySupply(json,factory(runtime,calls));
        var row=row(false);var request=request(row);row.remove("diseaseContext");
        var r=supply.enrich(paper(row),request,"analysis",Duration.ofSeconds(5));
        assertEquals("PM3_POPULATION_AFFECTED_AR_CONTEXT_REQUIRED",r.at("/observations/cases/0/resolvedObservations/0/rarityQualification/target/reasonCode").asText());
        row=row(true);request.withObject("/selectedAllele").put("position",1);
        r=supply.enrich(paper(row),request,"analysis",Duration.ofSeconds(5));
        assertEquals("PM3_POPULATION_SELECTED_ALLELE_MISMATCH",r.at("/observations/cases/0/resolvedObservations/0/rarityQualification/target/reasonCode").asText());
        r=supply.enrich(paper(row),request(row),"analysis",Duration.ZERO);
        assertEquals("PM3_POPULATION_TASK_BUDGET_EXHAUSTED",r.at("/observations/cases/0/resolvedObservations/0/rarityQualification/target/reasonCode").asText());
        assertEquals(0,calls.get());verifyNoInteractions(runtime);
    }
    @Test void discoveryWalksEachPaperButDoesNotTrustWorkerRarityClaims() {
        var runtime=unavailable();var calls=new AtomicInteger();var row=row(true);
        row.putObject("rarityQualification").putObject("target").put("qualification","VERIFIED");
        var discovery=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");
        discovery.putArray("papers").add(paper(row)).add(paper(row.deepCopy()).put("pmcid","PMC456"));
        var r=new Pm3LiteratureRaritySupply(json,factory(runtime,calls)).enrich(discovery,request(row),"analysis",Duration.ofSeconds(5));
        assertEquals(1,calls.get());assertEquals(2,r.at("/populationQualification/unresolvedRoles").asInt());
        assertEquals("UNKNOWN",r.at("/papers/0/observations/cases/0/resolvedObservations/0/rarityQualification/target/qualification").asText());
    }
    @Test void exactVerifiedContextReusesButChangedSourceDoesNot() {
        var runtime=unavailable();var created=new AtomicInteger();var input=paper(row(true));var request=request(row(true));
        try(var mocked=mockConstruction(Pm3RarityQualification.class,(mock,context)->
            when(mock.acquire(any(),any(),any(),any(),any(),any(),any(),anyString(),isNull()))
                .thenReturn(json.createObjectNode().put("qualification","VERIFIED").put("qualificationEvaluatorCalls",1)))) {
            var supply=new Pm3LiteratureRaritySupply(json,factory(runtime,created));
            var first=supply.enrich(input,request,"same",Duration.ofSeconds(5));
            var repeat=supply.enrich(input,request,"same",Duration.ofSeconds(5));
            assertEquals(1,created.get());assertEquals(1,first.at("/populationQualification/qualificationAttempts").asInt());
            assertEquals(0,repeat.at("/populationQualification/qualificationAttempts").asInt());
            assertEquals(1,repeat.at("/populationQualification/exactContextCacheHits").asInt());
            assertEquals(0,repeat.at("/populationQualification/qualificationEvaluatorCalls").asInt());
            input.put("bundleSha256","c".repeat(64));
            var changed=supply.enrich(input,request,"same",Duration.ofSeconds(5));
            assertEquals(2,created.get());assertEquals(0,changed.at("/populationQualification/exactContextCacheHits").asInt());
        }
    }
}
