package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Bp2BusinessFixture.at;

/** Independent expectations, physical fixture paths, real published two-locus input and actual core. */
class Bp2BusinessMaterialsTest {
    Bp2BusinessFixture b;
    @BeforeEach void setup() throws Exception { b=new Bp2BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }
    private JsonNode minimal(ObjectNode saved, String reason) {
        assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());
        JsonNode c=saved.at("/candidates/0/attempts/0/bp2");
        assertEquals("NOT_EVALUATED",c.at("/evaluationResult/status").asText());
        assertEquals(reason,c.at("/evaluationResult/reasonCode").asText());
        assertEquals("UNKNOWN",c.at("/criterionApplicability/status").asText());
        assertEquals(9,c.size()); assertEquals(3,c.at("/criterionEvaluations/0").size());
        for(String f:List.of("evidence","evaluation","evaluationId","appliedStrength","bp2Points","pm3Points","deterministicTrace")) assertFalse(c.has(f));
        return c;
    }
    @ParameterizedTest @ValueSource(strings={"beta","frozen/restricted-sex-patient-mismatch","frozen/restricted-age-patient-mismatch","frozen/restricted-population-patient-mismatch"})
    void frozenPhysicalBytesReachCoreAndRemainMinimal(String name) throws Exception {
        b.start(name); byte[] original=Files.readAllBytes(b.material);
        String pinsPath="/bp2-phase-core/"+(name.startsWith("frozen/")?"r1/"+name+"/pins.json":name+"-pins.json");
        try(var in=getClass().getResourceAsStream(pinsPath)) {
            assertNotNull(in); byte[] pinBytes=in.readAllBytes(); var pins=b.mapper.readTree(pinBytes);
            Files.write(b.archive.resolve("physical-pins.json"),pinBytes,StandardOpenOption.CREATE_NEW);
            assertEquals(pins.path("rawSha256").asText(),Bp2BusinessFixture.sha(original));
            assertEquals(pins.path("canonicalSha256").asText(),b.canonical(b.bundle));
        }
        var r=b.request(b.options()); var saved=b.submit(r);
        var c=minimal(saved,"BP2_PENETRANCE_CONDITIONS_NOT_ESTABLISHED");
        var direct=new Bp2PhaseCriterionEvaluator(b.mapper).evaluate(Bp2PhaseBundle.load(b.mapper,original,Bp2BusinessFixture.sha(original),b.canonical(b.bundle)));
        assertEquals(direct,c); b.artifact("direct-frozen-core",direct); b.verifyArtifact(saved);
        assertArrayEquals(original,Files.readAllBytes(b.material));
        var counts=b.counts(); assertEquals(saved,b.submit(r)); assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText())); assertEquals(counts,b.counts());
        assertEquals(1,b.core.get()); assertEquals(0,b.forbidden.get());
    }
    @ParameterizedTest @ValueSource(strings={"age","sex","population"})
    void validNewReviewedPinsCannotOverrideUnsatisfiedCondition(String kind) throws Exception {
        b.start("r1/restricted");
        String reason=switch(kind) { case "age" -> "BP2_PENETRANCE_AGE_NOT_SATISFIED"; case "sex" -> "BP2_PENETRANCE_SEX_NOT_SATISFIED"; default -> "BP2_PENETRANCE_POPULATION_NOT_SATISFIED"; };
        b.artifact("independent-expected",b.mapper.createObjectNode().put("status","NOT_EVALUATED").put("reasonCode",reason));
        b.issue("condition-"+kind,n->{
            String path="/observations/0/diseaseApplicability/penetrance/conditions/";
            if(kind.equals("age")) at(n,path+"1/patient").put("value",8);
            else if(kind.equals("sex")) at(n,path+"2/constraint").putArray("values").add("FEMALE");
            else at(n,path+"0/patient").put("populationId","TEST_ONLY_POP_B");
        });
        var request=b.request(b.options()); var saved=b.submit(request); minimal(saved,reason); b.verifyArtifact(saved);
        var retry=request.deepCopy().put("retryOf",saved.path("runId").asText()); var next=b.submit(retry); minimal(next,reason);
        assertNotEquals(saved.path("runId"),next.path("runId")); assertEquals(saved,b.service.read(b.analysisId,saved.path("runId").asText()));
        b.rebuildAndCompare(retry,next); var before=b.counts();
        Path retained=b.temporary.resolve("retained-ne-material.json"); Files.move(b.material,retained); b.wire();
        assertEquals(saved,b.submit(request)); assertEquals(next,b.submit(retry)); assertEquals(before,b.counts());
        b.reject(request.deepCopy().put("retryOf",next.path("runId").asText()),"BP2_MATERIAL_UNAVAILABLE");
        Files.copy(retained,b.material); b.wire(); assertArrayEquals(Files.readAllBytes(retained),Files.readAllBytes(b.material));
        assertEquals(2,b.core.get()); assertEquals(0,b.forbidden.get()); b.artifact("material-restored-counts",b.counts());
    }
    @ParameterizedTest @ValueSource(strings={"value","constraint","materialVersion","mechanism","source"})
    void fullDiseaseConditionsAndSourceParticipateInConfirmation(String kind) throws Exception {
        b.start("r1/restricted"); var old=b.request(b.options());
        b.issue("binding-"+kind,n->{var d=at(n,"/observations/0/diseaseApplicability");
            switch(kind) {
                case "value" -> at(d,"/penetrance/conditions/1/patient").put("value",19);
                case "constraint" -> at(d,"/penetrance/conditions/1/constraint").put("threshold",17);
                case "materialVersion" -> d.put("materialVersion","TEST_ONLY_NEW_VERSION");
                case "mechanism" -> d.put("mechanism","TEST_ONLY_NEW_MECHANISM");
                case "source" -> at(d,"/review").put("basis","TEST_ONLY new preserved source review");
                default -> throw new IllegalArgumentException(kind);
            }
        });
        b.reject(old,"BP2_SELECTION_STALE"); assertEquals(0,b.core.get());
        assertNotEquals(old.path("bp2Selection"),b.request(b.options()).path("bp2Selection"));
    }
    @Test void reorderedMembersRecordsDetectionsAndRefsDoNotDefineRoles() throws Exception {
        b.start("alpha"); b.issue("reordered",n->{
            for(String p:List.of("/observations/0/facts/members","/observations/0/facts/records","/observations/0/facts/detections","/observations/0/phase/recordRefs")) {
                var array=(com.fasterxml.jackson.databind.node.ArrayNode)n.at(p); var copy=array.deepCopy(); array.removeAll();
                for(int i=copy.size()-1;i>=0;i--) array.add(copy.get(i));
            }
            at(n,"/observations/0/diseaseApplicability").remove("penetrance");
        });
        var saved=b.submit(b.request(b.options())); assertEquals("COMPLETED",saved.path("status").asText()); b.verifyArtifact(saved);
    }
    @Test void corruptCwbPredecessorIsNotRehabilitatedByRetry() throws Exception {
        b.start("beta"); var request=b.request(b.options()); var saved=b.submit(request);
        String id=saved.path("runId").asText(); var original=b.store.run(b.analysisId,id).orElseThrow();
        new org.springframework.jdbc.core.JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?","{}",id);
        var before=b.counts(); b.reject(request.deepCopy().put("retryOf",id),"ACMG_SAVED_RESULT_INTEGRITY_FAILURE"); assertEquals(before,b.counts());
        new org.springframework.jdbc.core.JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?",original.responseJson(),id);
        assertEquals(saved,b.service.read(b.analysisId,id));
    }
}
