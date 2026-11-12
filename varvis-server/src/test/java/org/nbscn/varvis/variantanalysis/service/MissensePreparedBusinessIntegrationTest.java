package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SYNTHETIC / TEST_ONLY production-prepared files, public evaluate and isolated file H2. */
class MissensePreparedBusinessIntegrationTest {
    @TempDir Path temporary;
    Ps1GeneralBusinessIntegrationTest shared;
    AcmgBusinessIntegrationTest base;
    MissensePreparationFixture preparation;
    Path bundleFile;
    boolean pm5;
    void setup(boolean usePm5) throws Exception {
        pm5=usePm5; shared=new Ps1GeneralBusinessIntegrationTest(); shared.temporary=temporary; shared.setup(false); base=shared.base;
        preparation=new MissensePreparationFixture(temporary.resolve("inputs"),shared.fixture,pm5);
    }
    @AfterEach void close(){if(shared!=null)shared.close();}
    void wire() throws Exception {
        ObjectNode document=(ObjectNode)base.mapper.readTree(bundleFile.toFile());
        var bundle=shared.fixture.loadPrepared(temporary,bundleFile,shared.fixture.canonicalSha256(document));
        base.recreateService(()->{base.invocations.incrementAndGet();return new ExplicitCaseInputCoordinator(base.mapper,base.fixture.resolver,base.producer,base.fixture.sources);});
        base.service=pm5?AcmgBusinessService.pm5Development(base.service,bundle):AcmgBusinessService.ps1Development(base.service,bundle);
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    ObjectNode request() throws Exception {return shared.confirmedRequest(shared.options()).put("declarationVersion",pm5?AcmgBusinessRuleScope.PM5_DECLARATION:AcmgBusinessRuleScope.PS1_DECLARATION);}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void generatedTwoBackgroundFilesSaveReopenReuseAndRejectStaleIdentity(boolean usePm5) throws Exception {
        setup(usePm5); bundleFile=preparation.release("released");
        assertEquals(0,shared.fixture.discoveryCalls);assertEquals(0,shared.fixture.annotationCalls);assertEquals(0,shared.fixture.spliceCalls);assertEquals(0,base.invocations.get());
        wire(); var options=shared.options();var request=request();var saved=base.postRequest(request);
        shared.assertSummary(saved,"COMPLETED");
        for(int i=0;i<2;i++){
            JsonNode evaluation=shared.evaluation(saved,i);assertEquals("MET",evaluation.at("/evaluationResult/status").asText(),saved.toPrettyString());
            assertEquals(pm5?(i==0?"MODERATE":"SUPPORTING"):(i==0?"STRONG":"MODERATE"),evaluation.path("appliedStrength").asText());
            shared.assertDigests(evaluation);
            JsonNode comparison=saved.at("/candidates/"+i+"/attempts/0/missenseComparison");
            assertEquals(shared.fixture.policies.get(i),comparison.path("policy"));
            assertEquals(shared.fixture.canonicalSha256(shared.fixture.materials.get(i)),comparison.at("/comparisonTrace/materialsSha256").asText());
        }
        assertEquals(2,saved.at("/missenseCalls/evaluator").asInt()); assertEquals(2,shared.fixture.discoveryCalls);assertEquals(2,shared.fixture.annotationCalls);assertEquals(2,shared.fixture.spliceCalls);
        String run=saved.path("runId").asText();base.closeDatabase();assertTrue(Files.size(temporary.resolve("business-db.mv.db"))>0);
        base.recreateRepositories();wire();int coordinator=base.invocations.get();
        var reopened=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,run)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved,reopened);assertEquals(saved,base.postRequest(request));assertEquals(coordinator,base.invocations.get());assertEquals(1,base.store.runs(base.analysisId).size());
        assertEquals(2,shared.fixture.discoveryCalls);assertEquals(2,shared.fixture.annotationCalls);assertEquals(2,shared.fixture.spliceCalls);
        artifact("options",options);artifact("request",request);artifact("submit",saved);artifact("get-reopened",reopened);
        artifact("counters",base.mapper.createObjectNode().put("preparationProviderCalls",0).put("preparationEvaluatorCalls",0).put("businessEvaluatorCalls",2)
                .put("discovery",2).put("annotation",2).put("splice",2).put("reuseAdditionalCoordinatorCalls",0).put("reuseAdditionalComputations",0).put("fileH2Reopened",true));
        archiveInputsAndBundle();
        preparation.replace(0,"material",m->m.put("version","2"));var stale=preparation.prepare(temporary.resolve("stale-approval"));
        assertFalse(stale.ready());assertEquals("EXTERNAL_APPROVAL_BINDING_MISMATCH",stale.report().path("reasonCode").asText());
        preparation.approve(temporary.resolve("stale-approval"));Path updated=temporary.resolve("updated-release");assertTrue(preparation.prepare(updated).ready());bundleFile=updated.resolve("bundle.json");wire();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->base.service.submit(base.analysisId,base.mapper.writeValueAsBytes(request)));
        assertEquals(2,shared.fixture.discoveryCalls);var next=base.postRequest(request());assertNotEquals(run,next.path("runId").asText());assertEquals(saved,base.service.read(base.analysisId,run));
        shared.assertSummary(next,"COMPLETED");artifact("updated-pin",next);artifact("stale-approval",stale.report());
    }
    @ParameterizedTest @ValueSource(strings={"PS1_DIFFERENT","PS1_INDEPENDENCE","PS1_RNA_PAIR","PM5_PARTIAL"})
    void preparedMaterialsKeepOriginalRuntimeRefusals(String scenario) throws Exception {
        setup(scenario.startsWith("PM5"));
        if(scenario.equals("PS1_DIFFERENT")) {
            shared.fixture.different=true;
            for(int i=0;i<2;i++) {final int index=i;var allele=shared.fixture.allele(i==0?"11":"13",103,"G","A");
                preparation.replace(i,"referenceAudit",a->{var r=(ObjectNode)a.at("/references/0");r.set("allele",allele);r.set("annotation",shared.fixture.annotation(index,true));});
                preparation.replace(i,"rnaReview",a->((ObjectNode)a.at("/comparisons/0")).set("referenceAllele",allele));
            }
        }
        if(scenario.equals("PS1_INDEPENDENCE"))preparation.replace(0,"referenceAudit",a->((ObjectNode)a.at("/references/0")).remove("independence"));
        if(scenario.equals("PS1_RNA_PAIR"))preparation.replace(0,"rnaReview",a->a.putArray("comparisons"));
        if(scenario.equals("PM5_PARTIAL")) {shared.fixture.incomplete=true;preparation.replace(0,"substitutionMatrix",a->{((ObjectNode)a.at("/matrix/E")).put("D",10);((ObjectNode)a.at("/matrix/D")).put("E",10);});}
        bundleFile=preparation.release("released");wire();var saved=base.postRequest(request());
        boolean negative=scenario.equals("PS1_DIFFERENT");shared.assertSummary(saved,negative?"COMPLETED":"COMPLETED_WITH_BLOCKERS");
        assertEquals(negative?"NOT_MET":"NOT_EVALUATED",shared.evaluation(saved,0).at("/evaluationResult/status").asText(),saved.toPrettyString());
        assertEquals(negative?"NOT_MET":"MET",shared.evaluation(saved,1).at("/evaluationResult/status").asText());
        String expected=switch(scenario){case "PS1_DIFFERENT"->"NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON";case "PS1_INDEPENDENCE"->"REFERENCE_NOT_INDEPENDENT";case "PS1_RNA_PAIR"->"RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON";default->"MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE";};
        assertEquals(expected,negative?saved.at("/candidates/0/attempts/0/missenseComparison/scientificDecision/reasonCode").asText():shared.evaluation(saved,0).at("/evaluationResult/reasonCode").asText());artifact(scenario,saved);
    }
    void artifact(String name,JsonNode value)throws Exception{String location=System.getProperty("varvis.missense.preparation.artifacts");if(location==null)return;Path dir=Path.of(location).resolve(pm5?"PM5":"PS1");Files.createDirectories(dir);base.mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(name+".json").toFile(),value);}
    void archiveInputsAndBundle()throws Exception{String location=System.getProperty("varvis.missense.preparation.artifacts");if(location==null)return;Path dest=Path.of(location).resolve(pm5?"PM5":"PS1").resolve("preparation");try(var paths=Files.walk(preparation.root)){for(Path p:paths.toList()){Path q=dest.resolve(preparation.root.relativize(p));if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}}}
}
