package org.nbscn.varvis.variantanalysis.service;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Pp1BusinessFixture.at;

class Pp1BusinessIntegrationTest {
    Pp1BusinessFixture b;
    @BeforeEach void setup() throws Exception { b = new Pp1BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }
    @ParameterizedTest @ValueSource(strings={"alpha-1","alpha-3","beta-4","alpha-2-healthy"})
    void fixedCoresSaveRebuildAndUnavailableMaterialRead(String name) throws Exception {
        b.start(name); var options=b.options(); var ranked=b.jobs.findInterpretations(b.analysisId,0,1);
        assertEquals("BOUND_NOT_EVALUATED",options.at("/pp1Preparation/status").asText());assertEquals("NOT_EVALUATED",options.at("/pp1Preparation/scientificApplicability").asText());
        assertFalse(options.at("/pp1Selection/confirmed").asBoolean());assertEquals(0,b.core.get());assertEquals(1,options.path("selectedCount").asInt());
        var request=b.request(options);var saved=b.submit(request);b.verifyArtifact(saved);var core=saved.at("/candidates/0/attempts/0/pp1");
        assertEquals("COMPLETED",saved.path("status").asText());assertEquals(name.equals("alpha-1")?1:name.equals("alpha-3")?3:name.equals("beta-4")?4:2,core.path("appliedPoints").asInt());
        assertEquals(name.equals("alpha-1")?"SUPPORTING":name.equals("beta-4")?"STRONG":"MODERATE",core.at("/evaluation/appliedStrength").asText());
        byte[] raw=Files.readAllBytes(b.material);var direct=new Pp1SegregationCriterionEvaluator(b.mapper).evaluate(Pp1SegregationBundle.load(b.mapper,raw,Pp1BusinessFixture.sha(raw),b.canonical(b.bundle)));
        assertEquals(b.mapper.readTree(b.mapper.writeValueAsBytes(direct)),core);b.artifact("direct-core",direct);
        var list=b.service.list(b.analysisId);var candidate=b.service.candidate(b.analysisId,ranked.get(0).getCandidateId(),saved.path("runId").asText());
        b.rebuildAndCompare(request,saved);assertEquals(list,b.service.list(b.analysisId));assertEquals(candidate,b.service.candidate(b.analysisId,ranked.get(0).getCandidateId(),saved.path("runId").asText()));
        Files.move(b.material,b.temporary.resolve("retained-material.json"));b.wire();var before=b.counts();assertEquals(saved,b.submit(request));assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText()));assertEquals(before,b.counts());
        var changed=request.deepCopy();at(changed,"/pp1Selection").put("caseId","other");b.reject(changed,"PP1_MATERIAL_UNAVAILABLE");
        Files.copy(b.temporary.resolve("retained-material.json"),b.material);Files.writeString(b.material," ",StandardOpenOption.APPEND);assertEquals(saved,b.submit(request));b.reject(changed,"PP1_RAW_PIN_MISMATCH");
        assertEquals(1,b.core.get());assertEquals(0,b.forbidden.get());b.artifact("final-counts",b.counts());
    }
    @ParameterizedTest @ValueSource(strings={"two-affected-carrier-parents","new-valid-transmission-stale-declared-count"})
    void frozenScientificNeRemainsRealCoreAndCanRetry(String name)throws Exception {
        b.start("probe/"+name);var options=b.options();assertEquals(0,b.core.get());var request=b.request(options);var saved=b.submit(request);
        assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());var core=saved.at("/candidates/0/attempts/0/pp1");
        assertEquals(name.startsWith("two")?"PP1_UNIQUE_TRANSMITTING_PARENT_REQUIRED":"PP1_DECLARED_COUNT_CONFLICT",core.at("/evaluationResult/reasonCode").asText());
        for(String field:List.of("rawPoints","appliedPoints","evidence","evaluation","evaluationId","appliedStrength","deterministicTrace"))assertFalse(core.has(field));
        byte[] raw=Files.readAllBytes(b.material);assertEquals(b.mapper.readTree(b.mapper.writeValueAsBytes(new Pp1SegregationCriterionEvaluator(b.mapper).evaluate(Pp1SegregationBundle.load(b.mapper,raw,Pp1BusinessFixture.sha(raw),b.canonical(b.bundle))))),core);
        b.verifyArtifact(saved);b.rebuildAndCompare(request,saved);
        var retry=b.request(b.options()).put("retryOf",saved.path("runId").asText());var again=b.submit(retry);assertNotEquals(saved.path("runId"),again.path("runId"));assertEquals(core,again.at("/candidates/0/attempts/0/pp1"));
        var before=b.counts();assertEquals(again,b.submit(retry));assertEquals(before,b.counts());assertEquals(2,b.core.get());assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText()));
    }
    @Test void exactSelectionAndEarlyScopeIsolation()throws Exception {
        b.start("alpha-1");var r=b.request(b.options());
        for(String field:List.of("candidateId","contextId","alleleId","observationId","occurrenceId","caseId","familyContextId","materialBindingSha256","preparationToken","policyVersion")){var bad=r.deepCopy();at(bad,"/pp1Selection").put(field,"other");b.reject(bad,"PP1_SELECTION_STALE");}
        var bad=r.deepCopy();at(bad,"/pp1Selection").put("confirmed",false);b.reject(bad,"PP1_EXPLICIT_CONFIRMATION_REQUIRED");
        b.reject(r.deepCopy().put("topN",2),"PP1_TOP_ONE_REQUIRED");b.reject(r.deepCopy().put("sampleId","other"),"PP1_SAMPLE_CONFLICT");
        for(String field:List.of("patientContextId","familyContextId","sex","role")){bad=r.deepCopy();at(bad,"/patient").put(field,"other");b.reject(bad,"PP1_PATIENT_CONFLICT");}
        for(String field:List.of("association","calibration","revelApplication")){bad=r.deepCopy();bad.putObject(field);b.reject(bad,"PP1_EXCLUSIVE_SELECTION_REQUIRED");}
        for(String field:List.of("ps2Selection","pm6Selection","pm3Selection","bp2Selection")){bad=r.deepCopy();bad.putObject(field);b.reject(bad,field.substring(0,3).toUpperCase(Locale.ROOT)+"_SCOPE_REQUIRED");}
        assertEquals(0,b.core.get());assertTrue(b.store.runs(b.analysisId).isEmpty());
    }
}
