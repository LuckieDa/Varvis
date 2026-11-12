package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual PM4 core, normal persisted source/Top-N/context/SQL chain; all sequence, VEP and ranking data are synthetic. */
class GeneralPm4BusinessTest {
    @TempDir Path temporary;
    Pm4BusinessIntegrationTest old;
    GeneralPm4Runtime pm4;
    final AtomicInteger factories = new AtomicInteger();
    boolean forbid;
    boolean extended; String function; GeneralBp3Runtime bp3;
    @AfterEach void close() { if (old != null) old.close(); }
    void wire() throws Exception {
        var base = old.base;
        var reviews = temporary.resolve("effect-reviews"); Files.createDirectories(reviews);
        var bp7 = extended ? new Bp7SelectedBatch(base.mapper,new SplicingExperimentChecks(base.mapper,
                new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"),temporary.resolve("experiments"),
                (a,r)->{throw new AssertionError("No business reads");}).captureCatalog()) : null;
        base.service = AcmgBusinessService.localBatch(base.service, effects -> new LocalEvidenceBatchRuntime(base.mapper,
                base.mapper.createObjectNode().put("testConfiguration", "SYNTHETIC_PM4_GENERAL_1"), null, null, null, null,
                bp7, () -> { assertFalse(forbid, "Saved reads must not open PM4"); factories.incrementAndGet(); return pm4; },
                extended ? ()->{throw new IllegalArgumentException("PVS1_TEST_MATERIAL_UNAVAILABLE");} : null,
                extended ? ()->{assertFalse(forbid);return bp3;} : null), reviews);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    @ParameterizedTest @CsvSource({"true,false", "false,false", "false,true"})
    void actualPm4ResultAndMissingSecondModelSaveReopenAndReuse(boolean repeat, boolean operational) throws Exception {
        old = new Pm4BusinessIntegrationTest(); old.temporary = temporary; old.setup();
        var base = old.base;
        String sha = Pm4BusinessIntegrationTest.sha(Files.readAllBytes(temporary.resolve("pm4-test-only.fa")));
        pm4 = GeneralPm4TestSupport.create(base.mapper, temporary.resolve("general-material"), old.fixture.model,
                (contig, start, length) -> new CodingTranscriptMaterialSupply.ReferenceSpan(
                        Pm4TestFixtures.CDS.substring(start - 100, start - 100 + length),
                base.mapper.createObjectNode().put("expectedSha256", sha).put("dataClass", "SYNTHETIC")), repeat, !repeat && !operational, false, operational ? "ANNOTATION_SEQUENCE_V1" : "REVIEWED_ONLY", raw -> {
                    if(extended) {
                        var feature=(ObjectNode)raw.path("features").get(0);feature.withObject("location").withObject("start").put("value",1);
                        feature.withObject("location").withObject("end").put("value",6);raw.putArray("features").add(feature);
                    }
                });
        if(extended) {
            old.fixture.variant(105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
            old.fixture.context.withObject("/variant/normalization").put("referenceSequenceSha256",sha);
            old.fixture.context.withObject("variant").put("alleleId",old.fixture.adapter.canonicalAlleleId(
                    old.fixture.context.at("/variant/normalizedAllele"),old.fixture.context.at("/variant/normalization")));
            old.fixture.context.put("contextId",old.fixture.adapter.canonicalContextId(old.fixture.context));
            bp3=GeneralPm4TestSupport.bp3(base.mapper,temporary.resolve("general-material"),pm4,old.fixture.context,old.fixture.candidate,function);
        }
        wire();
        var options = base.service.inputOptions(base.analysisId, 2);
        assertEquals(extended?"1.4.0-extended":"1.2.0", options.at("/executionProfile/profileVersion").asText());
        assertEquals(extended?9:6, options.path("enabledCriteria").size()); assertEquals(0, factories.get());
        var request = base.request.deepCopy().put("declarationVersion", "ordinary-local-computational-batch1-v1");
        var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var r = options.at("/candidates/" + i + "/recommendation");
            var item = (ObjectNode)request.path("hypotheses").get(i); var hypothesis = (ObjectNode)item.path("hypothesis");
            hypothesis.put("hypothesisId", r.path("hypothesisId").asText());
            com.fasterxml.jackson.databind.JsonNode relation = null, annotation = null;
            for (var row : r.path("relationships")) if (row.path("mondoId").equals(hypothesis.path("mondoId"))) relation = row;
            for (var row : r.path("annotations")) if (row.path("annotationId").equals(r.path("recommendedAnnotationId"))) annotation = row;
            assertNotNull(relation, r.toPrettyString()); assertNotNull(annotation, r.toPrettyString());
            hypothesis.set("moi", relation.path("moi"));
            var tx = (ObjectNode)item.path("transcript"); String accession = annotation.path("versionedTranscriptId").asText(); int dot = accession.lastIndexOf('.');
            tx.put("accession", accession.substring(0,dot)).put("version", Integer.parseInt(accession.substring(dot+1)));
            tx.set("annotationSource", annotation.path("annotationSource")); tx.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            tx.put("selectionPolicy", r.path("policyId").asText() + "@" + r.path("policyVersion").asText());
            ((ObjectNode)tx.path("source")).put("recordLocator", "recommendations/" + r.path("recommendationId").asText() + "/selection");
            var ref = refs.addObject();
            for (String field : java.util.List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, r.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
        }
        var before = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var first = base.postRequest(request);
        int pm4Index=extended?7:5;
        assertEquals(repeat ? "NOT_MET" : "MET", first.at("/candidates/0/attempts/0/localBatch/criteria/"+pm4Index+"/status").asText(), first.toPrettyString());
        if(extended) assertEquals("NO_KNOWN_FUNCTION".equals(function)?"MET":"KNOWN_FUNCTION".equals(function)?"NOT_MET":"NOT_EVALUATED",
                first.at("/candidates/0/attempts/0/localBatch/criteria/8/status").asText(),first.toPrettyString());
        if (operational) {
            assertEquals("PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET", first.at("/candidates/0/attempts/0/localBatch/criteria/5/reasonCode").asText());
            assertTrue(first.at("/candidates/0/attempts/0/localBatch/criteria/5/details/proteinLengthFacts/nonRepeatFromNoHit").asBoolean());
            assertFalse(Files.exists(temporary.resolve("general-material/assertions.json")));
        }
        assertEquals("NOT_EVALUATED", first.at("/candidates/1/attempts/0/localBatch/criteria/"+pm4Index+"/status").asText());
        assertFalse(first.at("/candidates/0/attempts/0").has("pm4"), "Do not execute the legacy PM4 bundle too");
        assertEquals(before, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)));
        String runId = first.path("runId").asText(); var stored = base.store.run(base.analysisId, runId).orElseThrow();
        assertTrue(stored.privateJson().contains("shortRepeats")); assertEquals(1, factories.get());
        assertEquals(first, base.postRequest(request));
        int evaluations = pm4.evaluations();
        base.closeDatabase(); base.recreateRepositories(); old.wire(); forbid = true; wire();
        var read = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(first, read); assertEquals(first, base.postRequest(request));
        assertEquals(evaluations, pm4.evaluations()); assertEquals(1, factories.get());
        assertEquals(stored.responseJson(), base.store.run(base.analysisId,runId).orElseThrow().responseJson());
        assertEquals(stored.privateJson(), base.store.run(base.analysisId,runId).orElseThrow().privateJson());
        String output = System.getProperty(extended ? "varvis.eleven.artifacts" : "varvis.pm4.general.artifacts");
        if (output != null) {
            Path dir = Path.of(output).resolve(extended ? "bp3-business-"+function : operational ? "OPERATIONAL_MET" : repeat ? "NOT_MET" : "MET"); Files.createDirectories(dir);
            Files.writeString(dir.resolve("options.json"), options.toString());
            Files.writeString(dir.resolve("request.json"), stored.requestJson());
            Files.writeString(dir.resolve("saved-public.json"), stored.responseJson());
            Files.writeString(dir.resolve("saved-private.json"), stored.privateJson());
            Files.writeString(dir.resolve("reopened-public.json"), base.store.run(base.analysisId,runId).orElseThrow().responseJson());
            Files.writeString(dir.resolve("counts.json"), base.mapper.createObjectNode().put("dataClass", "SYNTHETIC")
                    .put("pm4Evaluations", evaluations).put("factoryCalls", factories.get()).put("newAfterReopen", 0).toString());
        }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"NO_KNOWN_FUNCTION","KNOWN_FUNCTION","MISSING"})
    void extendedBp3ActualResultAndMissingModelSurviveSaveReopenAndReuse(String status) throws Exception {
        extended=true;function=status;actualPm4ResultAndMissingSecondModelSaveReopenAndReuse(true,false);
        assertEquals(2,bp3.evaluations());
    }
}
