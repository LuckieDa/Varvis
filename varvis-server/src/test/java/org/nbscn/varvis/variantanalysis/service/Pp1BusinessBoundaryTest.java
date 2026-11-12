package org.nbscn.varvis.variantanalysis.service;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Pp1BusinessFixture.at;

class Pp1BusinessBoundaryTest {
    Pp1BusinessFixture b;
    @BeforeEach void setup() throws Exception {b=new Pp1BusinessFixture();b.setup();}
    @AfterEach void close() throws Exception {b.close();}
    static ObjectNode probandRecord(ObjectNode n) {
        var f=n.at("/observations/0/facts"); String id="";
        for(var member:f.path("members"))if(member.path("proband").asBoolean())id=member.path("memberId").asText();
        for(var r:f.path("records"))if(r.path("memberId").asText().equals(id))return (ObjectNode)r;
        throw new AssertionError("fixture missing proband");
    }
    @ParameterizedTest @ValueSource(strings={"0|1","1|0","phase-set"})
    void exactPhasedGtIsAcceptedWithoutParentalOriginInference(String mode) throws Exception {
        b.phaseGt=mode.equals("phase-set")?"0|1":mode; b.phaseSet=mode.equals("phase-set"); b.start("alpha-1");
        b.issue("phase",n->{var r=probandRecord(n);Pp1BusinessMaterials.call(r,b.phaseGt);
            if(b.phaseSet){r.put("format",r.path("format").asText()+":PS");r.put("rawSample",r.path("rawSample").asText()+":7");}});
        var saved=b.submit(b.request(b.options())); assertEquals("COMPLETED",saved.path("status").asText());
        assertEquals(1,saved.at("/candidates/0/attempts/0/pp1/appliedPoints").asInt());b.verifyArtifact(saved);
    }
    @Test void topOneCannotSearchLowerCandidates() throws Exception {
        b.decoyFirst=true;b.start("alpha-1"); assertEquals("PP1_TARGET_ALLELE_CONFLICT",assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());assertEquals(0,b.core.get());
    }
    @ParameterizedTest @ValueSource(strings={"clinical","detection","parentage","usedEvidence","review"})
    void fullRosterAndReviewsInvalidateConfirmation(String kind) throws Exception {
        b.start("alpha-1");var old=b.request(b.options());
        b.issue("binding-"+kind,n->{switch(kind){
            case "clinical" -> at(n,"/observations/0/facts/members/0/clinical/review").put("basis","TEST_ONLY changed clinical basis");
            case "detection" -> at(n,"/observations/0/facts/members/0/detection/review").put("basis","TEST_ONLY changed detection basis");
            case "parentage" -> at(n,"/observations/0/facts/parentage/0/review").put("basis","TEST_ONLY changed relationship basis");
            case "usedEvidence" -> at(n,"/observations/0/locusAttribution/usedEvidence/0").put("basis","TEST_ONLY changed locus evidence basis");
            default -> at(n,"/observations/0/segregationReview/review").put("basis","TEST_ONLY changed roster review");}});
        b.reject(old,"PP1_SELECTION_STALE");assertEquals(0,b.core.get());assertNotEquals(old.path("pp1Selection"),b.request(b.options()).path("pp1Selection"));
    }
    @Test void reorderedRosterDoesNotChooseFirstParentAsProband() throws Exception {
        b.start("alpha-1"); b.issue("reorder",n->{for(String k:List.of("members","records")){var a=(ArrayNode)n.at("/observations/0/facts/"+k);var old=a.deepCopy();a.removeAll();for(int i=old.size()-1;i>=0;i--)a.add(old.get(i));}});
        assertEquals("COMPLETED",b.submit(b.request(b.options())).path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings={"gt","phased","depth","quality","ad","ft","missing-family","binding"})
    void materialProjectionAndRequiredIdentityFailBeforeCore(String mode) throws Exception {
        b.start("alpha-1");b.issue("bad-"+mode,n->{var r=probandRecord(n);var o=at(r,"/observation");switch(mode){
            case "gt" -> o.put("genotype","1|0");case "phased" -> o.put("phased",true);case "depth" -> o.put("depth",999);case "quality" -> o.put("genotypeQuality",999);case "ad" -> o.put("alleleDepth","1,1");
            case "ft" -> r.put("rawSample",r.path("rawSample").asText().replace("PASS","LowQual"));
            case "missing-family" -> at(n,"/context/patientContext").remove("familyContextId");
            case "binding" -> at(n,"/observations/0/facts/binding").put("caseId","OTHER_TEST_ONLY");default -> throw new IllegalArgumentException();}});
        String reason=switch(mode){case "ft"->"PP1_SAMPLE_FT_NOT_PASS";case "missing-family"->"PP1_FAMILY_CONTEXT_REQUIRED";case "binding"->"PP1_CASE_BINDING_CONFLICT";default->"PP1_RECORD_RAW_PROJECTION_CONFLICT";};
        assertEquals(reason,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());assertEquals(0,b.core.get());assertTrue(b.store.runs(b.analysisId).isEmpty());
        b.artifact("rejection",b.mapper.createObjectNode().put("reason",reason).set("counts",b.counts()));
    }
    @Test void modesAreExplicitAndForbiddenProvidersHavePositiveControls() throws Exception {
        b.start("alpha-1");var cfg=new org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig();
        b.env.withProperty("variant-analysis.acmg-business.enabled","false");assertEquals("PP1_BUSINESS_EXECUTION_DISABLED",assertThrows(IllegalArgumentException.class,()->cfg.acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,b.env)).getMessage());
        b.env.withProperty("variant-analysis.acmg-business.enabled","true");
        for(String flag:List.of("revel-application-enabled","saved-observation-calibration-only","saved-observation-association-only")){
            b.env.withProperty("variant-analysis.acmg-business."+flag,"true");assertEquals("PP1_EXECUTION_MODE_CONFLICT",assertThrows(IllegalArgumentException.class,()->cfg.acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,b.env)).getMessage());b.env.withProperty("variant-analysis.acmg-business."+flag,"false");}
        for(String field:Pp1BusinessFixture.supplierFields()){var v=(java.util.function.Supplier<?>)org.springframework.test.util.ReflectionTestUtils.getField(requireNonNull(b.service),requireNonNull(field));assertNotNull(v);assertThrows(AssertionError.class,v::get);}
        assertEquals(8,b.forbidden.get());b.submit(b.request(b.options()));assertEquals(8,b.forbidden.get());b.artifact("forbidden-positive-controls",b.counts());
    }
    @ParameterizedTest @ValueSource(strings={"PS2","PM6","PM3","BP2"})
    void otherScopesRejectEveryPp1MarkerBeforeDispatch(String scope) throws Exception {
        Ps2BusinessIntegrationTest old=switch(scope){case "PS2"->new Ps2BusinessIntegrationTest();case "PM6"->new Pm6BusinessFixture();case "PM3"->new Pm3BusinessFixture();default->new Bp2BusinessFixture();};
        old.setup();try{old.start("alpha");var r=old.request(old.options());
            var marked=r.deepCopy();marked.putObject("pp1Selection").put("confirmed",true);old.reject(marked,"PP1_SCOPE_REQUIRED");
            old.reject(r.deepCopy().put("declarationVersion",AcmgBusinessRuleScope.PP1_DECLARATION),"PP1_SCOPE_REQUIRED");
            old.reject(r.deepCopy().put("sampleId",Pp1SegregationContextPreparation.TOKEN+"x"),"PP1_SCOPE_REQUIRED");assertEquals(0,old.core.get());
        }finally{old.close();}
    }
    @Test void retryCannotBorrowAnotherScopeOrCorruptSavedIdentity() throws Exception {
        b.start("alpha-1"); var request=b.request(b.options());
        var identity=b.mapper.createObjectNode(); identity.putObject("declaration").put("declarationVersion",AcmgBusinessRuleScope.PM6_DECLARATION);
        identity.set("executionProfile",AcmgExecutionProfile.pm6Development(b.mapper,b.mapper.createObjectNode()).document());
        String id=UUID.randomUUID().toString();
        assertTrue(b.store.claim(new org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.Run(id,b.analysisId,b.canonical(identity),b.mapper.writeValueAsString(identity),"RUNNING","2026-09-29T00:00:00Z",null,null,null)));
        b.store.failed(id); b.reject(request.deepCopy().put("retryOf",id),"PP1_RETRY_SCOPE_MISMATCH");
        b.artifact("foreign-scope-predecessor",b.mapper.valueToTree(b.store.run(b.analysisId,id).orElseThrow()));
        var saved=b.submit(request); var original=b.store.run(b.analysisId,saved.path("runId").asText()).orElseThrow();
        new org.springframework.jdbc.core.JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?","{}",original.id());
        b.reject(request,"ACMG_SAVED_RESULT_INTEGRITY_FAILURE");
        new org.springframework.jdbc.core.JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?",original.responseJson(),original.id());
        assertEquals(saved,b.service.read(b.analysisId,original.id())); assertEquals(1,b.core.get());
    }

    @ParameterizedTest @ValueSource(strings={"allele","gene","moi","context","family","gt-order","quality"})
    void independentlyValidMaterialCannotReplacePublishedTarget(String mode) throws Exception {
        b.start("alpha-1");b.issue("target-"+mode,n->{switch(mode){
            case "allele" -> at(n,"/context/variant/inputAllele").put("position",999);
            case "gene" -> at(n,"/context/gene").put("symbol","OTHER_TEST_ONLY");
            case "moi" -> at(n,"/context").put("moi","AUTOSOMAL_RECESSIVE");
            case "context" -> at(n,"/context").put("contextId","context-sha256-"+"0".repeat(64));
            case "family" -> at(n,"/observations/0/facts").put("familyId","family-context-other");
            case "gt-order" -> Pp1BusinessMaterials.call(probandRecord(n),"1/0");
            case "quality" -> {var r=probandRecord(n);r.put("rawSample","0/1:50:60:25,25:PASS");at(r,"/observation").put("depth",50).put("alleleDepth","25,25");}
            default -> throw new IllegalArgumentException(mode);}});
        String expected=switch(mode){case "allele"->"PP1_TARGET_ALLELE_CONFLICT";case "gene","moi"->"PP1_CANDIDATE_BACKGROUND_CONFLICT";case "context"->"PP1_CONTEXT_IDENTITY_INVALID";case "family"->"PP1_PATIENT_MATERIAL_CONFLICT";case "gt-order"->"PP1_PROBAND_GT_CONFLICT";default->"PP1_PROBAND_QUALITY_CONFLICT";};
        assertEquals(expected,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());assertEquals(0,b.core.get());b.artifact("target-refusal",b.mapper.createObjectNode().put("reason",expected).set("counts",b.counts()));
    }
}
