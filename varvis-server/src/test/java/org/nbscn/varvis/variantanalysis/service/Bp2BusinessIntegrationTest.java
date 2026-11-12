package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Bp2BusinessFixture.at;

class Bp2BusinessIntegrationTest {
    Bp2BusinessFixture b;
    @BeforeEach void setup() throws Exception { b = new Bp2BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }
    @ParameterizedTest @ValueSource(strings={"alpha", "gamma", "r1/beta", "r1/restricted"})
    void twoBackgroundsThirdRankPartnerSaveRebuildAndReadWithoutMaterial(String name) throws Exception {
        b.start(name); var ranked = b.jobs.findInterpretations(b.analysisId, 0, 100); var options = b.options();
        assertEquals("BOUND_NOT_EVALUATED", options.at("/bp2Preparation/status").asText());
        assertEquals(ranked.get(0).getCandidateId(), options.at("/bp2Selection/candidateId").asText());
        assertEquals(ranked.get(2).getCandidateId(), options.at("/bp2Selection/partnerCandidateId").asText());
        var request = b.request(options); var saved = b.submit(request); b.verifyArtifact(saved);
        var core = saved.at("/candidates/0/attempts/0/bp2");
        assertEquals("COMPLETED", saved.path("status").asText()); assertFalse(core.has("bp2Points"));
        assertEquals("SUPPORTING", core.at("/evaluation/appliedStrength").asText());
        byte[] raw = Files.readAllBytes(b.material);
        var direct = new Bp2PhaseCriterionEvaluator(b.mapper).evaluate(Bp2PhaseBundle.load(b.mapper, raw, Bp2BusinessFixture.sha(raw), b.canonical(b.bundle)));
        assertEquals(b.mapper.readTree(b.mapper.writeValueAsBytes(direct)), core); b.artifact("direct-core", direct);
        var list = b.service.list(b.analysisId); var candidate = b.service.candidate(b.analysisId, ranked.get(0).getCandidateId(), saved.path("runId").asText());
        b.rebuildAndCompare(request, saved); assertEquals(list, b.service.list(b.analysisId));
        assertEquals(candidate, b.service.candidate(b.analysisId, ranked.get(0).getCandidateId(), saved.path("runId").asText()));
        b.artifact("rebuilt-list", b.mapper.valueToTree(list)); b.artifact("rebuilt-candidate", candidate);
        Files.move(b.material, b.temporary.resolve("retained-material.json")); b.wire(); var before = b.counts();
        assertEquals(saved, b.submit(request)); assertEquals(saved, b.service.read(b.analysisId, saved.path("runId").asText())); assertEquals(before, b.counts());
        b.reject(request.deepCopy().put("retryOf", UUID.randomUUID().toString()), "RETRY_RUN_NOT_FOUND");
        var changed = request.deepCopy(); at(changed,"/bp2Selection").put("caseId","other"); b.reject(changed,"BP2_MATERIAL_UNAVAILABLE");
        Files.copy(b.temporary.resolve("retained-material.json"),b.material); Files.writeString(b.material, " ", StandardOpenOption.APPEND);
        assertEquals(saved,b.submit(request)); b.reject(changed,"BP2_RAW_PIN_MISMATCH");
        Files.copy(b.temporary.resolve("retained-material.json"),b.material,StandardCopyOption.REPLACE_EXISTING);
        assertArrayEquals(Files.readAllBytes(b.temporary.resolve("retained-material.json")),Files.readAllBytes(b.material));
        assertEquals(1,b.core.get()); assertEquals(0,b.forbidden.get()); b.artifact("unavailable-and-corrupt-file-reuse-counts",b.counts());
    }
    @Test void exactSelectionPatientAndCrossScopeGates() throws Exception {
        b.start("alpha"); var r=b.request(b.options());
        for(String field:List.of("candidateId","partnerCandidateId","contextId","targetAlleleId","partnerAlleleId","pairId","observationId","occurrenceId","caseId","familyContextId","materialBindingSha256","preparationToken","policyVersion")) {
            var bad=r.deepCopy(); at(bad,"/bp2Selection").put(field,"other"); b.reject(bad,"BP2_SELECTION_STALE");
        }
        var bad=r.deepCopy(); at(bad,"/bp2Selection").put("confirmed",false); b.reject(bad,"BP2_EXPLICIT_CONFIRMATION_REQUIRED");
        for(String field:List.of("patientContextId","familyContextId","role","sex")) { bad=r.deepCopy(); at(bad,"/patient").put(field,"other"); b.reject(bad,"BP2_PATIENT_CONFLICT"); }
        b.reject(r.deepCopy().put("sampleId","other"),"BP2_SAMPLE_CONFLICT");
        b.reject(r.deepCopy().put("dataClass","PRIVACY_BOUNDED_RUNTIME"),"BP2_DATA_CLASS_CONFLICT");
        b.reject(r.deepCopy().put("topN",2),"BP2_TOP_ONE_REQUIRED");
        for(String field:List.of("ps2Selection","pm6Selection","pm3Selection")) { bad=r.deepCopy(); bad.putObject(field); b.reject(bad,field.equals("ps2Selection")?"PS2_SCOPE_REQUIRED":field.equals("pm6Selection")?"PM6_SCOPE_REQUIRED":"PM3_SCOPE_REQUIRED"); }
        for(String field:List.of("association","calibration","revelApplication")) { bad=r.deepCopy(); bad.putObject(field); b.reject(bad,"BP2_EXCLUSIVE_SELECTION_REQUIRED"); }
        var cfg=new org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig();
        for(String scope:List.of("",AcmgBusinessRuleScope.PS2_DEVELOPMENT,AcmgBusinessRuleScope.PM6_DEVELOPMENT,AcmgBusinessRuleScope.PM3_DEVELOPMENT)) {
            var env=new org.springframework.mock.env.MockEnvironment().withProperty("variant-analysis.acmg-business.enabled","true").withProperty("variant-analysis.acmg-business.acceptance-scope",requireNonNull(scope));
            var svc=cfg.acmgBusinessService(b.mapper,b.jobs,b.store,b.sources,b.properties,env);
            assertEquals("BP2_SCOPE_REQUIRED",assertThrows(ResponseStatusException.class,()->svc.submit(b.analysisId,b.mapper.writeValueAsBytes(r))).getReason());
            var token=r.deepCopy(); token.remove("bp2Selection"); token.put("declarationVersion","ordinary-ui-v3"); at(token,"/patient").put("patientContextId",Bp2PhaseContextPreparation.TOKEN+"0".repeat(64));
            assertEquals("BP2_SCOPE_REQUIRED",assertThrows(ResponseStatusException.class,()->svc.submit(b.analysisId,b.mapper.writeValueAsBytes(token))).getReason());
        }
        assertEquals(0,b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"facts","phase","partnerClassification","diseaseApplicability"})
    void everyPartReviewChangesBinding(String part) throws Exception {
        b.start("alpha"); var old=b.request(b.options());
        b.issue("changed-"+part,n-> { var section=at(n,"/observations/0/"+part); if(part.equals("facts")) at(section,"/clinical/review").put("basis","TEST_ONLY new review basis"); else at(section,"/review").put("basis","TEST_ONLY new review basis"); });
        b.reject(old,"BP2_SELECTION_STALE"); assertNotEquals(old.path("bp2Selection"),b.request(b.options()).path("bp2Selection"));
    }
    @Test void unpublishedPartnerCannotBeSynthesizedFromBundle() throws Exception {
        b.partnerMode="unpublished"; b.start("alpha");
        assertEquals("BP2_PUBLISHED_PARTNER_REQUIRED",assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason()); assertEquals(0,b.core.get());
    }
    @Test void scientificNeAndExplicitRetryStayMinimal() throws Exception {
        b.start("alpha"); b.issue("unknown-phase",n->at(n,"/observations/0/phase").put("conclusion","UNKNOWN"));
        var r=b.request(b.options()); var saved=b.submit(r); assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());
        var retry=r.deepCopy().put("retryOf",saved.path("runId").asText());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            byte[] raw=b.mapper.writeValueAsBytes(retry);
            var results=pool.invokeAll(List.of((java.util.concurrent.Callable<ObjectNode>)()->b.service.submit(b.analysisId,raw),()->b.service.submit(b.analysisId,raw)));
            assertEquals(results.get(0).get().path("runId"),results.get(1).get().path("runId"));
            for(int i=0;i<results.size();i++)b.artifact("concurrent-cwb-retry-"+i,results.get(i).get());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)); }
        var next=b.submit(retry);
        var core=next.at("/candidates/0/attempts/0/bp2"); assertEquals("NOT_EVALUATED",core.at("/evaluationResult/status").asText());
        for(String field:List.of("bp2Points","evidence","evaluation","evaluationId","appliedStrength")) assertFalse(core.has(field));
        assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText())); var before=b.counts(); assertEquals(next,b.submit(retry)); assertEquals(before,b.counts()); assertEquals(2,b.core.get());
    }
}
