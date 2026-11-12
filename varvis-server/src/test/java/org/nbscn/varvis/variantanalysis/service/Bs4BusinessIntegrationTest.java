package org.nbscn.varvis.variantanalysis.service;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Bs4BusinessFixture.at;

class Bs4BusinessIntegrationTest {
    Bs4BusinessFixture b;
    @BeforeEach void setup() throws Exception { b = new Bs4BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }
    @ParameterizedTest @ValueSource(strings={"alpha-2","beta-3"})
    void fixedCoresSaveRebuildAndUnavailableMaterialRead(String name) throws Exception {
        b.start(name); var options=b.options(); var ranked=b.jobs.findInterpretations(b.analysisId,0,1);
        assertEquals("BOUND_NOT_EVALUATED",options.at("/bs4Preparation/status").asText());assertEquals("NOT_EVALUATED",options.at("/bs4Preparation/scientificApplicability").asText());
        assertFalse(options.at("/bs4Selection/confirmed").asBoolean());assertEquals(0,b.core.get());assertEquals(1,options.path("selectedCount").asInt());
        var request=b.request(options);var saved=b.submit(request);b.verifyArtifact(saved);var core=saved.at("/candidates/0/attempts/0/bs4");
        assertEquals("CORE_RESULT_ONLY",core.path("resultMode").asText());
        for(String field:List.of("formalEnabled","automaticTrigger","classificationEnabled","adoptionSupported","clinicalValidationCompleted")) {
            assertFalse(saved.path(field).asBoolean(true)); assertFalse(saved.path("executionProfile").path(field).asBoolean(true));
        }
        assertEquals("COMPLETED",saved.path("status").asText());assertEquals(-4,core.path("appliedPoints").asInt());
        assertEquals(name.equals("alpha-2")?1:2,core.path("eventCount").asInt());
        assertEquals("STRONG",core.at("/evaluation/appliedStrength").asText());
        byte[] raw=Files.readAllBytes(b.material);var direct=new Bs4NonSegregationCriterionEvaluator(b.mapper).evaluate(Bs4NonSegregationBundle.load(b.mapper,raw,Bs4BusinessFixture.sha(raw),b.canonical(b.bundle)));
        assertEquals(b.mapper.readTree(b.mapper.writeValueAsBytes(direct)),core);b.artifact("direct-core",direct);
        var list=b.service.list(b.analysisId);var candidate=b.service.candidate(b.analysisId,ranked.get(0).getCandidateId(),saved.path("runId").asText());
        b.rebuildAndCompare(request,saved);assertEquals(list,b.service.list(b.analysisId));assertEquals(candidate,b.service.candidate(b.analysisId,ranked.get(0).getCandidateId(),saved.path("runId").asText()));
        Files.move(b.material,b.temporary.resolve("retained-material.json"));b.wire();var before=b.counts();assertEquals(saved,b.submit(request));assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText()));assertEquals(before,b.counts());
        assertEquals(list,b.service.list(b.analysisId));assertEquals(candidate,b.service.candidate(b.analysisId,ranked.get(0).getCandidateId(),saved.path("runId").asText()));assertEquals(before,b.counts());
        var changed=request.deepCopy();at(changed,"/bs4Selection").put("caseId","other");b.reject(changed,"BS4_MATERIAL_UNAVAILABLE");
        Files.copy(b.temporary.resolve("retained-material.json"),b.material);Files.writeString(b.material," ",StandardOpenOption.APPEND);assertEquals(saved,b.submit(request));b.reject(changed,"BS4_RAW_PIN_MISMATCH");
        assertEquals(1,b.core.get());assertEquals(0,b.forbidden.get());b.artifact("final-counts",b.counts());
    }
    @ParameterizedTest @ValueSource(strings={"alpha-1","probe/review-homozygous-alt-sibling"})
    void frozenScientificNeRemainsRealCoreAndCanRetry(String name)throws Exception {
        b.start(name);var options=b.options();assertEquals(0,b.core.get());var request=b.request(options);var saved=b.submit(request);
        assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());var core=saved.at("/candidates/0/attempts/0/bs4");
        assertEquals(name.equals("alpha-1")?"BS4_NONSEGREGATION_NOT_ESTABLISHED":"BS4_CHILD_GENOTYPE_CLINICAL_CONFLICT",core.at("/evaluationResult/reasonCode").asText());
        for(String field:List.of("rawPoints","appliedPoints","evidence","evaluation","evaluationId","appliedStrength","deterministicTrace"))assertFalse(core.has(field));
        byte[] raw=Files.readAllBytes(b.material);assertEquals(b.mapper.readTree(b.mapper.writeValueAsBytes(new Bs4NonSegregationCriterionEvaluator(b.mapper).evaluate(Bs4NonSegregationBundle.load(b.mapper,raw,Bs4BusinessFixture.sha(raw),b.canonical(b.bundle))))),core);
        b.verifyArtifact(saved);b.rebuildAndCompare(request,saved);
        var retry=b.request(b.options()).put("retryOf",saved.path("runId").asText());var again=b.submit(retry);assertNotEquals(saved.path("runId"),again.path("runId"));assertEquals(core,again.at("/candidates/0/attempts/0/bs4"));
        var before=b.counts();assertEquals(again,b.submit(retry));assertEquals(before,b.counts());assertEquals(2,b.core.get());assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText()));
    }
    @Test void exactSelectionAndEarlyScopeIsolation()throws Exception {
        b.start("alpha-2");var r=b.request(b.options());
        for(String field:List.of("candidateId","contextId","alleleId","observationId","occurrenceId","caseId","familyContextId","materialBindingSha256","preparationToken","policyVersion")){var bad=r.deepCopy();at(bad,"/bs4Selection").put(field,"other");b.reject(bad,"BS4_SELECTION_STALE");}
        var bad=r.deepCopy();at(bad,"/bs4Selection").put("confirmed",false);b.reject(bad,"BS4_EXPLICIT_CONFIRMATION_REQUIRED");
        b.reject(r.deepCopy().put("topN",2),"BS4_TOP_ONE_REQUIRED");b.reject(r.deepCopy().put("sampleId","other"),"BS4_SAMPLE_CONFLICT");
        for(String field:List.of("patientContextId","familyContextId","sex","role")){bad=r.deepCopy();at(bad,"/patient").put(field,"other");b.reject(bad,"BS4_PATIENT_CONFLICT");}
        for(String field:List.of("association","calibration","revelApplication")){bad=r.deepCopy();bad.putObject(field);b.reject(bad,"BS4_EXCLUSIVE_SELECTION_REQUIRED");}
        for(String field:List.of("ps2Selection","pm6Selection","pm3Selection","bp2Selection","pp1Selection")){bad=r.deepCopy();bad.putObject(field);b.reject(bad,field.substring(0,3).toUpperCase(Locale.ROOT)+"_SCOPE_REQUIRED");}
        assertEquals(0,b.core.get());assertTrue(b.store.runs(b.analysisId).isEmpty());
    }
}
