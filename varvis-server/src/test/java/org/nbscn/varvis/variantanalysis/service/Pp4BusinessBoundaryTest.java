package org.nbscn.varvis.variantanalysis.service;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Pp4BusinessFixture.at;
class Pp4BusinessBoundaryTest {
    Pp4BusinessFixture b;
    @BeforeEach void setup() throws Exception {b=new Pp4BusinessFixture();b.setup();}
    @AfterEach void close() throws Exception {b.close();}
    @ParameterizedTest @ValueSource(strings={"0|1","1|0","phase-set"})
    void exactPhasedCallIsAcceptedWithoutPhaseSetRequirement(String mode)throws Exception {
        b.phaseGt=mode.equals("phase-set")?"0|1":mode;b.phaseSet=mode.equals("phase-set");b.start("alpha");
        b.issue("phased",n->{var r=at(n,"/observations/0/facts/records/0");at(r,"/observation").put("genotype",b.phaseGt).put("phased",true);
            r.put("rawSampleValue",b.phaseGt+":40:60:20,20:PASS"+(b.phaseSet?":7":""));
            if(b.phaseSet)at(n,"/observations/0/facts/members/0/detection").put("sampleFormat","GT:DP:GQ:AD:FT:PS");});
        var saved=b.submit(b.request(b.options()));assertEquals("COMPLETED",saved.path("status").asText());assertEquals("2",saved.at("/candidates/0/attempts/0/pp4/appliedPoints").asText());
    }
    @Test void onlyFormalTopOneCanBind()throws Exception {b.decoyFirst=true;b.start("alpha");assertEquals("PP4_TARGET_ALLELE_CONFLICT",assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());assertEquals(0,b.core.get());}
    @ParameterizedTest @ValueSource(strings={"eligibility","testing","resultSelection","alternative-method","clinical","cohort","ledger"})
    void allClinicalCohortAndLedgerChangesInvalidateTokenWithoutScientificPreparation(String mode)throws Exception {
        b.start("alpha");var old=b.request(b.options());
        b.issue("change-"+mode,n->{switch(mode){
            case "eligibility","testing","resultSelection"->at(n,"/observations/0/diagnosticYield/ascertainmentDetails").put(mode,"UNKNOWN");
            case "alternative-method"->at(n,"/observations/0/phenotypeReview/alternatives/0").put("method","UNKNOWN");
            case "clinical"->at(n,"/observations/0/phenotypeReview/review").put("basis","TEST_ONLY updated basis");
            case "cohort"->at(n,"/observations/0/diagnosticYield").put("cohortVersion","TEST_ONLY_updated");
            case "ledger"->at(n,"/observations/0/locusAttribution/review").put("basis","TEST_ONLY updated ledger review");default->throw new AssertionError();}});
        var fresh=b.options();assertEquals("BOUND_NOT_EVALUATED",fresh.at("/pp4Preparation/status").asText());assertNotEquals(old.path("pp4Selection"),fresh.path("pp4Selection"));b.reject(old,"PP4_SELECTION_STALE");assertEquals(0,b.core.get());
        var saved=b.submit(b.request(fresh));if(List.of("eligibility","testing","resultSelection","alternative-method").contains(mode))assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings={"gt","phased","quality","gt-order","family","sample","gene","moi","context"})
    void wrongIdentityOrPublishedProjectionFailsBeforeClaim(String mode)throws Exception {
        b.start("alpha");b.issue("mismatch-"+mode,n->{var r=at(n,"/observations/0/facts/records/0");switch(mode){
            case "gt"->at(r,"/observation").put("genotype","1|0");case "phased"->at(r,"/observation").put("phased",true);
            case "quality"->{r.put("rawSampleValue","0/1:50:60:25,25:PASS");at(r,"/observation").put("depth",50).put("alleleDepth","25,25");}
            case "gt-order"->{r.put("rawSampleValue","1/0:40:60:20,20:PASS");at(r,"/observation").put("genotype","1/0");}
            case "family"->at(n,"/observations/0/facts").put("familyId","other");case "sample"->at(n,"/observations/0/facts/members/0").put("sampleId","other");
            case "gene"->at(n,"/context/gene").put("symbol","OTHER_TEST_ONLY");case "moi"->at(n,"/context").put("moi","AUTOSOMAL_RECESSIVE");
            case "context"->at(n,"/context").put("contextId","context-sha256-"+"0".repeat(64));default->throw new AssertionError();}});
        String reason=switch(mode){case "gt","phased"->"PP4_RECORD_RAW_PROJECTION_CONFLICT";case "quality"->"PP4_PROBAND_QUALITY_CONFLICT";case "gt-order"->"PP4_PROBAND_GT_CONFLICT";case "family"->"PP4_PATIENT_MATERIAL_CONFLICT";case "sample"->"PP4_SAMPLE_CONFLICT";case "gene","moi"->"PP4_CANDIDATE_BACKGROUND_CONFLICT";default->"PP4_CONTEXT_IDENTITY_INVALID";};
        assertEquals(reason,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());assertEquals(0,b.core.get());assertTrue(b.store.runs(b.analysisId).isEmpty());b.artifact("refusal",b.mapper.createObjectNode().put("reason",reason).set("counts",b.counts()));
    }
    @Test void modesRemainExplicitAndForbiddenSuppliersHavePositiveControls()throws Exception {
        b.start("alpha");var cfg=new org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig();b.env.withProperty("variant-analysis.acmg-business.enabled","false");assertEquals("PP4_BUSINESS_EXECUTION_DISABLED",assertThrows(IllegalArgumentException.class,()->cfg.acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,b.env)).getMessage());b.env.withProperty("variant-analysis.acmg-business.enabled","true");
        for(String flag:List.of("revel-application-enabled","saved-observation-calibration-only","saved-observation-association-only")){b.env.withProperty("variant-analysis.acmg-business."+flag,"true");assertEquals("PP4_EXECUTION_MODE_CONFLICT",assertThrows(IllegalArgumentException.class,()->cfg.acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,b.env)).getMessage());b.env.withProperty("variant-analysis.acmg-business."+flag,"false");}
        for(String field:Pp4BusinessFixture.supplierFields()){var v=(java.util.function.Supplier<?>)org.springframework.test.util.ReflectionTestUtils.getField(requireNonNull(b.service),requireNonNull(field));assertNotNull(v);assertThrows(AssertionError.class,v::get);}assertEquals(8,b.forbidden.get());b.submit(b.request(b.options()));assertEquals(8,b.forbidden.get());b.artifact("forbidden-positive-controls",b.counts());
    }
    @Test void bs4ScopeRejectsAllPp4MarkersBeforeDispatchAndPreservesHistory()throws Exception {
        var old=new Bs4BusinessFixture();old.setup();try{old.start("alpha-2");var request=old.request(old.options());var saved=old.submit(request);var before=old.counts();
            var marked=request.deepCopy();marked.putObject("pp4Selection").put("confirmed",true);old.reject(marked,"PP4_SCOPE_REQUIRED");old.reject(request.deepCopy().put("declarationVersion",AcmgBusinessRuleScope.PP4_DECLARATION),"PP4_SCOPE_REQUIRED");old.reject(request.deepCopy().put("sampleId",Pp4PhenotypeContextPreparation.TOKEN+"x"),"PP4_SCOPE_REQUIRED");assertEquals(saved,old.submit(request));assertEquals(saved,old.service.read(old.analysisId,saved.path("runId").asText()));assertEquals(before,old.counts());
        }finally{old.close();}
    }

    @Test void defaultProfileIgnoresUnselectedPp4FileConfiguration() {
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("variant-analysis.acmg-business.pp4-bundle-file","missing-test-only.json");
        var service=new org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig().acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,env);
        assertNull(org.springframework.test.util.ReflectionTestUtils.getField(requireNonNull(service),"pp4Preparation"));
        var profile=(AcmgExecutionProfile)org.springframework.test.util.ReflectionTestUtils.getField(requireNonNull(service),"executionProfile");assertNotNull(profile);assertEquals(AcmgBusinessRuleScope.CURRENT,profile.criteria());
    }
    @Test void changedPublishedSourceInvalidatesConfirmationWithoutChangingCoreMaterial()throws Exception {
        b.start("alpha");var request=b.request(b.options());var source=(ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
        var corrupted=source.deepCopy().put("engineVersion","TEST_ONLY conflicting engine version");b.mutatePublishedSource(corrupted);
        b.reject(request,"EXECUTION_SNAPSHOT_EXECUTION_MISMATCH");
        // A distinct RAW snapshot with the same valid metadata still needs fresh confirmation.
        var reordered=b.mapper.createObjectNode();var fields=new ArrayList<String>();source.fieldNames().forEachRemaining(fields::add);Collections.reverse(fields);for(String field:fields)reordered.set(field,source.path(field));
        b.mutatePublishedSource(reordered);var fresh=b.options();
        assertNotEquals(request.path("pp4Selection"),fresh.path("pp4Selection"));b.reject(request,"PP4_SELECTION_STALE");assertEquals(0,b.core.get());
        assertEquals("COMPLETED",b.submit(b.request(fresh)).path("status").asText());
    }
    @Test void preparedSnapshotRemainsImmutableAndNoMaterialRereadAfterClaim()throws Exception {
        b.start("alpha");var request=b.request(b.options());var prep=(Pp4PhenotypeContextPreparation)org.springframework.test.util.ReflectionTestUtils.getField(requireNonNull(b.service),"pp4Preparation");assertNotNull(prep);
        org.mockito.Mockito.doAnswer(call->{var prepared=(Pp4PhenotypeContextPreparation.Prepared)call.getArgument(0);
            var savedDoc=prepared.document();prepared.document().removeAll();prepared.selection().removeAll();prepared.bundle().snapshot().removeAll();assertEquals(savedDoc,prepared.document());
            java.nio.file.Files.move(b.material,b.temporary.resolve("material-retained-after-claim.json"));b.core.incrementAndGet();return call.callRealMethod();}).when(prep).evaluate(org.mockito.ArgumentMatchers.any());
        var saved=b.submit(request);assertEquals("COMPLETED",saved.path("status").asText());assertEquals(2,b.loads.get());assertEquals(1,b.core.get());b.verifyArtifact(saved);
    }
}
