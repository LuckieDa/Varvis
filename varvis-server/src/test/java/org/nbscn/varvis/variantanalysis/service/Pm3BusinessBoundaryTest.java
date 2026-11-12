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
import static org.nbscn.varvis.variantanalysis.service.Pm3BusinessFixture.at;

class Pm3BusinessBoundaryTest {
    Pm3BusinessFixture b;
    @BeforeEach void setup() throws Exception { b=new Pm3BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }
    @ParameterizedTest @ValueSource(strings={"ambiguous","outside-window","top1"})
    void publishedWindowNeverSelectsByRankOrReordersTarget(String mode) throws Exception {
        b.partnerMode=mode; b.decoyFirst=mode.equals("top1"); b.start("alpha");
        String expected=mode.equals("ambiguous")?"PM3_PARTNER_AMBIGUOUS":mode.equals("top1")?"PM3_TARGET_ALLELE_CONFLICT":"PM3_PUBLISHED_PARTNER_REQUIRED";
        assertEquals(expected,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());
        assertEquals(0,b.core.get()); b.artifact("window-rejection",b.mapper.createObjectNode().put("mode",mode).put("reason",expected).set("counts",b.counts()));
    }
    @ParameterizedTest @ValueSource(strings={"0|1","1|0","phase-set"})
    void consistentSingleSitePhaseDoesNotImplyTrans(String mode) throws Exception {
        b.phaseGt=mode.equals("phase-set")?"0|1":mode; b.phaseSet=mode.equals("phase-set"); b.start("alpha");
        b.issue("phase-calls",n->{ for(int i=0;i<2;i++) {
            var r=at(n,"/observations/0/facts/records/"+i); String raw=r.path("rawSampleValue").asText();
            r.put("rawSampleValue",b.phaseGt+raw.substring(raw.indexOf(':'))+(b.phaseSet?":7":""));
            at(r,"/observation").put("genotype",b.phaseGt).put("phased",true);
            if(b.phaseSet) at(n,"/observations/0/facts/detections/"+i).put("sampleFormat","GT:DP:GQ:AD:FT:PS");
        }});
        var saved=b.submit(b.request(b.options())); assertEquals("COMPLETED",saved.path("status").asText());
        b.issue("unknown-trans",n->at(n,"/observations/0/phase").put("conclusion","UNKNOWN"));
        var unknown=b.submit(b.request(b.options())); assertEquals("COMPLETED_WITH_BLOCKERS",unknown.path("status").asText());
        assertFalse(unknown.at("/candidates/0/attempts/0/pm3").has("pm3Points"));
    }
    @ParameterizedTest @ValueSource(strings={"target-call","partner-call","source-sample","source-pin","source-digest","source-partner","family","case","missing-family","roles"})
    void bothSourcesAndRequiredBindingsRejectBeforeCore(String mode) throws Exception {
        b.start("alpha"); var r=b.request(b.options()); String expected;
        if(mode.endsWith("-call")) {
            int index=mode.startsWith("target")?0:2;
            var source=(ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
            var candidate=b.mapper.<ObjectNode>valueToTree(b.jobs.findInterpretations(b.analysisId,0,100).get(index));
            at(candidate,"/analysisVcfCall").put("depth",999);
            b.store.saveCandidateAnnotationPayload(b.analysisId,candidate.path("candidateId").asText(),b.mapper.writeValueAsString(candidate));
            at(source,"/candidates/"+index).set("published",candidate);
            at(source,"/candidates/"+index+"/engineCandidate/analysisVcfCall").put("depth",999);
            b.mutatePublishedSource(source); expected="PM3_PROBAND_QUALITY_CONFLICT";
        } else if(mode.startsWith("source")) {
            var source=(ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
            if(mode.equals("source-sample")) source.put("sampleId","other");
            else if(mode.equals("source-digest")) source.put("observedVcfSha256","0".repeat(64));
            else if(mode.equals("source-pin")) source.put("uploadSha256","0".repeat(64));
            else at(source,"/candidates/2/matchedRecord").put("position",999);
            b.mutatePublishedSource(source); expected=mode.equals("source-digest")?"PM3_NATIVE_SOURCE_DIGEST_CONFLICT":mode.equals("source-partner")?"PM3_PUBLISHED_CANDIDATE_SOURCE_CONFLICT":"EXECUTION_SNAPSHOT_TASK_MISMATCH";
        } else {
            var wrong=r.deepCopy();
            if(mode.equals("family")) { at(wrong,"/patient").put("familyContextId","family-context-other"); expected="PM3_PATIENT_CONFLICT"; }
            else if(mode.equals("case")) { at(wrong,"/pm3Selection").put("caseId","other"); expected="PM3_SELECTION_STALE"; }
            else if(mode.equals("roles")) { var s=at(wrong,"/pm3Selection"); String target=s.path("targetAlleleId").asText(); s.set("targetAlleleId",s.path("partnerAlleleId"));s.put("partnerAlleleId",target); expected="PM3_SELECTION_STALE"; }
            else { b.issue("missing-family",n->at(n,"/context/patientContext").remove("familyContextId")); expected="PM3_FAMILY_CONTEXT_REQUIRED"; }
            r=wrong;
        }
        b.reject(r,expected); assertEquals(0,b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty());
    }
    @Test void configurationForbiddenCallsAndLegacyRequestIdentity() throws Exception {
        b.start("alpha"); var cfg=new org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig();
        b.env.withProperty("variant-analysis.acmg-business.enabled","false");
        assertEquals("PM3_BUSINESS_EXECUTION_DISABLED",assertThrows(IllegalArgumentException.class,()->cfg.acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,b.env)).getMessage());
        b.env.withProperty("variant-analysis.acmg-business.enabled","true");
        for(String flag:List.of("revel-application-enabled","saved-observation-calibration-only","saved-observation-association-only")) {
            b.env.withProperty("variant-analysis.acmg-business."+flag,"true");
            assertEquals("PM3_EXECUTION_MODE_CONFLICT",assertThrows(IllegalArgumentException.class,()->cfg.acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,b.env)).getMessage());
            b.env.withProperty("variant-analysis.acmg-business."+flag,"false");
        }
        for(String field:Pm3BusinessFixture.supplierFields()) {
            Object value=org.springframework.test.util.ReflectionTestUtils.getField(requireNonNull(b.service),requireNonNull(field));
            assertInstanceOf(java.util.function.Supplier.class,value); assertThrows(AssertionError.class,()->((java.util.function.Supplier<?>)requireNonNull(value)).get());
        }
        assertEquals(8,b.forbidden.get()); b.artifact("forbidden-positive-controls",b.counts()); var r=b.request(b.options());b.submit(r); assertEquals(8,b.forbidden.get());
        for(String declaration:List.of("ordinary-ui-v3",AcmgBusinessRuleScope.PS2_DECLARATION,AcmgBusinessRuleScope.PM6_DECLARATION)) {
            var old=new AcmgSubmission(1,declaration,null,"TEST_ONLY","SYNTHETIC",null,List.of());
            ObjectNode original=b.mapper.valueToTree(old); assertFalse(original.has("pm3Selection"));
            var parsed=AcmgSubmission.parse(b.mapper.writeValueAsBytes(original),b.mapper); assertEquals(original,b.mapper.valueToTree(parsed));
            var withNull=original.deepCopy();withNull.putNull("pm3Selection"); var again=AcmgSubmission.parse(b.mapper.writeValueAsBytes(withNull),b.mapper);
            assertEquals(original,b.mapper.valueToTree(again)); assertEquals(b.canonical(original),b.canonical(b.mapper.valueToTree(again)));
        }
    }

    @Test void retryCannotBorrowAnotherScopeOrCorruptSavedIdentity() throws Exception {
        b.start("alpha"); var request=b.request(b.options());
        var identity=b.mapper.createObjectNode(); identity.putObject("declaration").put("declarationVersion",AcmgBusinessRuleScope.PM6_DECLARATION);
        identity.set("executionProfile",AcmgExecutionProfile.pm6Development(b.mapper,b.mapper.createObjectNode()).document());
        String id=UUID.randomUUID().toString();
        assertTrue(b.store.claim(new org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.Run(id,b.analysisId,b.canonical(identity),b.mapper.writeValueAsString(identity),"RUNNING","2026-09-29T00:00:00Z",null,null,null)));
        b.store.failed(id); b.reject(request.deepCopy().put("retryOf",id),"PM3_RETRY_SCOPE_MISMATCH");
        b.artifact("foreign-scope-predecessor",b.mapper.valueToTree(b.store.run(b.analysisId,id).orElseThrow()));
        var saved=b.submit(request); var original=b.store.run(b.analysisId,saved.path("runId").asText()).orElseThrow();
        new org.springframework.jdbc.core.JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?","{}",original.id());
        b.reject(request,"ACMG_SAVED_RESULT_INTEGRITY_FAILURE");
        new org.springframework.jdbc.core.JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?",original.responseJson(),original.id());
        assertEquals(saved,b.service.read(b.analysisId,original.id())); assertEquals(1,b.core.get());
    }
    @ParameterizedTest @ValueSource(strings={"gt","phased","depth","quality","ad","ft"})
    void materialCallCannotOverridePublishedCallOrFailedFt(String mode) throws Exception {
        b.start("alpha"); b.issue("call-"+mode,n->{
            var r=at(n,"/observations/0/facts/records/1"); var o=at(r,"/observation");
            switch(mode) {
                case "gt" -> o.put("genotype","1|0");
                case "phased" -> o.put("phased",true);
                case "depth" -> o.put("depth",999);
                case "quality" -> o.put("genotypeQuality",999);
                case "ad" -> o.put("alleleDepth","1,1");
                case "ft" -> r.put("rawSampleValue",r.path("rawSampleValue").asText().replace("PASS","LowQual"));
                default -> throw new IllegalArgumentException(mode);
            }
        });
        if(mode.equals("ft")) {
            var result=b.submit(b.request(b.options())); assertEquals("COMPLETED_WITH_BLOCKERS",result.path("status").asText());
            assertEquals("NOT_EVALUATED",result.at("/candidates/0/attempts/0/pm3/evaluationResult/status").asText());
            assertFalse(result.at("/candidates/0/attempts/0/pm3").has("pm3Points"));
        } else {
            String expected=mode.equals("gt")?"PM3_PROBAND_GT_CONFLICT":mode.equals("phased")?"PM3_PROBAND_PHASE_CONFLICT":"PM3_PROBAND_QUALITY_CONFLICT";
            assertEquals(expected,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason()); assertEquals(0,b.core.get());
        }
    }
}
