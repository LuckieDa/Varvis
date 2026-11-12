package org.nbscn.varvis.variantanalysis.service;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Pp4BusinessFixture.at;

class Pp4BusinessIntegrationTest {
    Pp4BusinessFixture b;
    @BeforeEach void setup() throws Exception {b=new Pp4BusinessFixture();b.setup();}
    @AfterEach void close() throws Exception {b.close();}
    @ParameterizedTest @ValueSource(strings={"alpha","beta","numerical/auto-0092","numerical/auto-0098",
            "frozen/alpha-control","frozen/beta-control","frozen/alpha-ascertainment-unknown","frozen/beta-ascertainment-positive-only",
            "frozen/alpha-alternative-method-unknown","frozen/beta-phenocopy-method-unknown"})
    void frozenBytesExecuteSaveAndRebuildWithWholeCoreUnchanged(String name)throws Exception {
        b.start(name);var options=b.options();
        assertEquals("BOUND_NOT_EVALUATED",options.at("/pp4Preparation/status").asText());assertEquals("NOT_EVALUATED",options.at("/pp4Preparation/scientificApplicability").asText());
        assertFalse(options.at("/pp4Selection/confirmed").asBoolean());assertEquals(0,b.core.get());assertTrue(b.bundle.at("/observations/0/facts/parentage").isEmpty());
        var request=b.request(options);var saved=b.submit(request);b.verifyArtifact(saved);var core=saved.at("/candidates/0/attempts/0/pp4");
        byte[] raw=Files.readAllBytes(b.material);
        var direct=new Pp4PhenotypeCriterionEvaluator(b.mapper).evaluate(Pp4PhenotypeBundle.load(b.mapper,raw,Pp4BusinessFixture.sha(raw),b.canonical(b.bundle)));
        assertEquals(b.mapper.readTree(b.mapper.writeValueAsBytes(direct)),core);b.artifact("direct-core",direct);
        if(name.startsWith("frozen/")) {
            assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());
            String reason=name.contains("method")?"PP4_ALTERNATIVE_METHOD_UNESTABLISHED":name.contains("positive-only")?"PP4_COHORT_ASCERTAINMENT_INCOMPATIBLE":"PP4_COHORT_ASCERTAINMENT_UNESTABLISHED";
            assertEquals(reason,core.at("/evaluationResult/reasonCode").asText());
            for(String f:List.of("rawPoints","appliedPoints","rawHalfPoints","appliedHalfPoints","evidence","evaluation","evaluationId","appliedStrength","deterministicTrace"))assertFalse(core.has(f));
            var expected=b.mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("varvis.pp4.business.inputs"),name,"actual.json")));assertEquals(expected,core);
        } else {
            assertEquals("COMPLETED",saved.path("status").asText());
            String rawPoints=name.endsWith("0098")?"7":name.endsWith("0092")?"2.5":"2";
            String applied=name.endsWith("0098")?"5":rawPoints;
            assertEquals(rawPoints,core.path("rawPoints").asText());assertEquals(applied,core.path("appliedPoints").asText());assertTrue(core.path("appliedPoints").isTextual());
            assertEquals(name.endsWith("0098")?"STRONG":"MODERATE",core.at("/evaluation/appliedStrength").asText());
            if(name.startsWith("numerical/"))assertEquals(b.mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("varvis.pp4.business.inputs"),name,"output.json"))),core);
        }
        for(String f:List.of("formalEnabled","automaticTrigger","classificationEnabled","adoptionSupported","clinicalValidationCompleted")){assertFalse(saved.path(f).asBoolean(true));assertFalse(saved.path("executionProfile").path(f).asBoolean(true));}
        var list=b.service.list(b.analysisId);String candidateId=saved.at("/candidateIds/0").asText();var candidate=b.service.candidate(b.analysisId,candidateId,saved.path("runId").asText());
        b.rebuildAndCompare(request,saved);assertEquals(list,b.service.list(b.analysisId));assertEquals(candidate,b.service.candidate(b.analysisId,candidateId,saved.path("runId").asText()));
        Files.move(b.material,b.temporary.resolve("retained-material.json"));b.wire();var before=b.counts();assertEquals(saved,b.submit(request));assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText()));assertEquals(list,b.service.list(b.analysisId));assertEquals(candidate,b.service.candidate(b.analysisId,candidateId,saved.path("runId").asText()));assertEquals(before,b.counts());
        var changed=request.deepCopy();at(changed,"/pp4Selection").put("caseId","other");b.reject(changed,"PP4_MATERIAL_UNAVAILABLE");
        if(name.startsWith("frozen/"))b.reject(request.deepCopy().put("retryOf",saved.path("runId").asText()),"PP4_MATERIAL_UNAVAILABLE");
        Files.copy(b.temporary.resolve("retained-material.json"),b.material);Files.writeString(b.material," ",StandardOpenOption.APPEND);assertEquals(saved,b.submit(request));b.reject(changed,"PP4_RAW_PIN_MISMATCH");
        assertEquals(1,b.core.get());assertEquals(0,b.forbidden.get());b.artifact("final-counts",b.counts());
    }
    @Test void exactConfirmationAndMixedSelectionsFailBeforeCore()throws Exception {
        b.start("alpha");var r=b.request(b.options());
        for(String f:List.of("candidateId","contextId","alleleId","observationId","occurrenceId","caseId","familyContextId","materialBindingSha256","preparationToken","policyVersion")){var bad=r.deepCopy();at(bad,"/pp4Selection").put(f,"other");b.reject(bad,"PP4_SELECTION_STALE");}
        var bad=r.deepCopy();at(bad,"/pp4Selection").put("confirmed",false);b.reject(bad,"PP4_EXPLICIT_CONFIRMATION_REQUIRED");
        bad=r.deepCopy();bad.remove("pp4Selection");b.reject(bad,"PP4_EXPLICIT_CONFIRMATION_REQUIRED");
        b.reject(r.deepCopy().put("topN",2),"PP4_TOP_ONE_REQUIRED");b.reject(r.deepCopy().put("sampleId","other"),"PP4_SAMPLE_CONFLICT");b.reject(r.deepCopy().put("dataClass","PRIVACY_BOUNDED_RUNTIME"),"PP4_DATA_CLASS_CONFLICT");
        for(String f:List.of("patientContextId","familyContextId","sex","role")){bad=r.deepCopy();at(bad,"/patient").put(f,"other");b.reject(bad,"PP4_PATIENT_CONFLICT");}
        for(String f:List.of("association","calibration","revelApplication")){bad=r.deepCopy();bad.putObject(f);b.reject(bad,"PP4_EXCLUSIVE_SELECTION_REQUIRED");}
        for(String f:List.of("ps2Selection","pm6Selection","pm3Selection","bp2Selection","pp1Selection","bs4Selection")){bad=r.deepCopy();bad.putObject(f);b.reject(bad,f.substring(0,3).toUpperCase(Locale.ROOT)+"_SCOPE_REQUIRED");}
        bad=r.deepCopy();bad.putArray("recommendations");b.reject(bad,"PP4_EXCLUSIVE_SELECTION_REQUIRED");assertEquals(0,b.core.get());assertTrue(b.store.runs(b.analysisId).isEmpty());
    }
}
