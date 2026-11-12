package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real ordinary controller/claim/finish/read and H2, synthetic science and source publication. */
class PredictionDevelopmentBusinessTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    AtomicInteger lookups = new AtomicInteger();
    ObjectNode policy, calibration, resources;
    @BeforeEach void setup(TestInfo info) throws Exception {
        String archive = System.getProperty("varvis.prediction.synthetic-archive", "");
        if (!archive.isBlank() && info.getTestMethod().orElseThrow().getName().startsWith("ordinaryClaim")) {
            // Explicit opt-in retention, created exclusively; never copies or edits an existing database.
            temporary = java.nio.file.Files.createDirectory(Path.of(archive));
        }
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup(); base.publish(false, GenomeAssembly.GRCh38, null);
        policy = PredictionDevelopmentRuntime.load(base.mapper, "prediction-core-policy-v1.json");
        calibration = PredictionDevelopmentRuntime.load(base.mapper, "prediction-am-calibration-v1.json");
        resources = base.mapper.createObjectNode(); resources.set("ALPHAMISSENSE", PredictionCoreTest.resource(base.mapper));
        base.service = AcmgBusinessService.predictionDevelopment(base.service, this::runtime, temporary);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        declare();
    }
    @AfterEach void close() { if (base != null) base.closeDatabase(); }
    PredictionDevelopmentRuntime runtime() {
        return new PredictionDevelopmentRuntime(base.mapper, policy, calibration, resources, request -> {
            lookups.incrementAndGet(); assertEquals("ALPHAMISSENSE", request.path("model").asText());
            var observation = base.mapper.createObjectNode().put("status", "HIT");
            for (String f : new String[]{"queryAllele", "resource", "dataClass"}) observation.set(f, request.path(f).deepCopy());
            var rows = observation.putArray("rows");
            for (var candidate : base.jobs.findInterpretations(base.analysisId, 0, 2))
                if (base.mapper.valueToTree(candidate.getObservation().getKey()).equals(request.path("queryAllele")))
                    for (var tx : candidate.getTranscriptAnnotations()) rows.addObject().put("transcriptId", tx.getVersionedTranscriptId())
                            .put("proteinVariant", tx.getAminoAcidChange()).put("rawScore", "0.990");
            return observation;
        }, true, plan -> PredictionCoreTest.restrictions(base.mapper, plan));
    }
    void declare() {
        int before = lookups.get();
        base.request.put("declarationVersion", PredictionDevelopmentRuntime.DECLARATION);
        var options = base.service.inputOptions(base.analysisId, 2); var selections = base.request.putArray("recommendations");
        for (int i = 0; i < options.path("candidates").size(); i++) {
            var r = options.at("/candidates/" + i + "/recommendation");
            JsonNode relation = null, annotation = null;
            var declaredTranscript = base.request.at("/hypotheses/" + i + "/transcript");
            String fixedTranscript = declaredTranscript.path("accession").asText() + "." + declaredTranscript.path("version").asInt();
            for (var row : r.path("relationships")) if (row.path("selectable").asBoolean()) { relation = row; break; }
            // The accepted fixture transcript is fixed before scores; annotation-ID sorting is not a selection policy.
            for (var row : r.path("annotations")) if (row.path("predictionContextReady").asBoolean()
                    && fixedTranscript.equals(row.path("versionedTranscriptId").asText())) { annotation = row; break; }
            assertNotNull(relation, r.toPrettyString()); assertNotNull(annotation, r.toPrettyString());
            var selection = selections.addObject();
            for (String key : new String[]{"candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"}) selection.set(key, r.path(key));
            selection.set("stableRelationshipKey", relation.path("stableRelationshipKey")); selection.set("annotationId", annotation.path("annotationId"));
            var hypothesis = (ObjectNode)base.request.at("/hypotheses/" + i + "/hypothesis");
            hypothesis.set("candidateId", r.path("candidateId")); hypothesis.set("hypothesisId", r.path("hypothesisId"));
            for (String key : new String[]{"hgncId", "mondoId", "moi"}) hypothesis.set(key, relation.path(key));
            var transcript = (ObjectNode)base.request.at("/hypotheses/" + i + "/transcript");
            String tx = annotation.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
            transcript.put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)));
            transcript.set("selectionPolicy", annotation.path("predictionContextSelectionPolicy"));
            transcript.set("annotationSource", annotation.path("annotationSource")); transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            ((ObjectNode)transcript.path("source")).put("recordLocator", "recommendations/" + r.path("recommendationId").asText() + "/selection");
        }
        assertEquals(before, lookups.get(), "input-options is score-free");
    }
    @Test void ordinaryClaimFinishReadAndRepeatPostDoNotRequeryOrUpgradeHistory() throws Exception {
        var result = base.postRequest(base.request);
        assertEquals(2, lookups.get(), result.toPrettyString());
        assertEquals(2, result.path("evaluatorCalls").asInt()); assertEquals(0, result.at("/predictionCalls/bp7").asInt());
        assertEquals(base.mapper.valueToTree(PredictionDevelopmentRuntime.CRITERIA), result.path("enabledCriteria"));
        for (var c : result.path("candidates")) {
            var p = c.at("/attempts/0/prediction"); assertEquals("MET", p.at("/criteria/PP3/status").asText(), p.toPrettyString());
            assertEquals("STRONG", p.at("/criteria/PP3/appliedStrength").asText());
            assertEquals("NOT_MET", p.at("/criteria/BP4/status").asText());
            assertEquals("0.990", p.at("/association/rawScore").asText());
        }
        assertEquals(result, base.postRequest(base.request)); assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
        assertEquals(2, lookups.get());
        var old = result.deepCopy(); calibration.put("version", "synthetic-change");
        base.service = AcmgBusinessService.predictionDevelopment(base.service, this::runtime, temporary);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build(); declare();
        // A successful completed run is not retryable; a changed profile is a normal new submission.
        var next = base.postRequest(base.request);
        assertNotEquals(old.path("runId"), next.path("runId")); assertEquals(4, lookups.get());
        assertEquals(old, base.service.read(base.analysisId, old.path("runId").asText()));
        if (!System.getProperty("varvis.prediction.synthetic-archive", "").isBlank()) {
            var receipt = base.mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("analysisId", base.analysisId)
                    .put("runId", next.path("runId").asText()).put("science", "SYNTHETIC_GRID_NOT_REAL_RESOURCE_VALIDATION");
            java.nio.file.Files.writeString(temporary.resolve("receipt.json"), receipt.toPrettyString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }
    @Test void unavailableCalibrationStillAcquiresOnceAndUnavailablePurposeNeverCalls() throws Exception {
        var candidate = base.jobs.findInterpretations(base.analysisId, 0, 2).get(0);
        ((ObjectNode)resources.path("ALPHAMISSENSE")).put("calibrationCompatibility", "UNVERIFIED");
        var r = runtime(); int before = lookups.get();
        var result = r.execute(r.prepare(candidate, null, "f".repeat(64), "raw-only", "SYNTHETIC", null));
        assertEquals(before + 1, lookups.get()); assertEquals("HIT", result.at("/observation/status").asText());
        assertEquals("OBSERVATION_ONLY", result.at("/plan/planMode").asText());
        assertFalse(result.at("/observation/rows").isEmpty()); assertEquals("NOT_EVALUATED", result.at("/criteria/PP3/status").asText());
        ((ObjectNode)resources.path("ALPHAMISSENSE")).remove("license");
        r = runtime(); before = lookups.get(); result = r.execute(r.prepare(candidate, null, "f".repeat(64), "unlicensed", "SYNTHETIC", null));
        assertEquals(before, lookups.get()); assertEquals("LICENSE_SOURCE_UNBOUND", result.at("/mechanisms/PROTEIN_MISSENSE/reasonCode").asText());
    }
    @Test void changedSavedEffectScopeCreatesNewNormalRequestAndGetKeepsOldSnapshot() throws Exception {
        var first = base.postRequest(base.request); assertEquals(2, lookups.get());
        var declaration = base.mapper.createObjectNode().put("fixture", "linked functional run pending");
        var hashes = new AcmgBusinessArtifacts(base.mapper);
        base.store.claim(new org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.Run("synthetic-pending-linked-run", base.analysisId,
                hashes.requestHash(declaration), declaration.toString(), "RUNNING", "2026-09-17T12:00:00Z", null, null, null));
        var next = base.postRequest(base.request); assertNotEquals(first.path("runId"), next.path("runId"));
        assertNotEquals(first.path("effectSnapshotSha256"), next.path("effectSnapshotSha256"));
        assertEquals(4, lookups.get()); assertEquals(next, base.postRequest(base.request)); assertEquals(4, lookups.get());
        assertEquals(first, base.service.read(base.analysisId, first.path("runId").asText()));
    }
    @Test void oldDeclarationCannotAuthorizeAndWrongCandidateAssociationCannotQuery() throws Exception {
        base.request.put("declarationVersion", "ordinary-ui-v3");
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
        base.request.put("declarationVersion", PredictionDevelopmentRuntime.DECLARATION);
        ((ObjectNode)base.request.at("/recommendations/0")).put("candidateId", "wrong-candidate");
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        assertEquals(0, lookups.get());
    }
    @Test void missingApplicationContextRetainsAuthorizedRawRowsAndMatchedSpecNeverFallsBack() throws Exception {
        base.request.putArray("hypotheses"); var result = base.postRequest(base.request);
        assertEquals(2, lookups.get(), result.toPrettyString());
        for (var c : result.path("candidates")) {
            var p = c.at("/attempts/0/prediction"); assertEquals("HIT", p.at("/observation/status").asText());
            assertEquals("NOT_EVALUATED", p.at("/criteria/PP3/status").asText());
        }
        var candidate = base.jobs.findInterpretations(base.analysisId, 0, 2).get(0);
        var normal = base.declaration(); base.request = normal; declare(); var valid = base.postRequest(base.request);
        var context = valid.at("/candidates/0/attempts/0/context"); assertTrue(context.isObject(), valid.toPrettyString());
        var incomplete = context.deepCopy(); ((ObjectNode)incomplete).remove("disease");
        var independent = runtime(); var partial = independent.execute(independent.prepare(candidate, incomplete, "f".repeat(64), "partial", "SYNTHETIC", null));
        assertEquals("ASSOCIATED", partial.at("/association/status").asText(), partial.toString());
        assertEquals("CALIBRATED", partial.at("/mechanisms/PROTEIN_MISSENSE/status").asText());
        assertEquals("NOT_EVALUATED", partial.at("/criteria/PP3/status").asText());
        var shared = runtime(); int rawBefore = lookups.get();
        var firstPlan = shared.prepare(candidate, context, "f".repeat(64), "background-one", "SYNTHETIC", null);
        var secondPlan = shared.prepare(candidate, context, "f".repeat(64), "background-two", "SYNTHETIC", null);
        var firstResult = shared.execute(firstPlan); var secondResult = shared.execute(secondPlan);
        assertEquals(rawBefore + 1, lookups.get(), "Same allele/resource/purpose may share raw records only");
        assertNotEquals(firstResult.path("resultId"), secondResult.path("resultId"));
        assertEquals("background-one", firstResult.at("/plan/hypothesisId").asText());
        assertEquals("background-two", secondResult.at("/plan/hypothesisId").asText());
        var view = firstPlan.snapshot(); view.put("model", "REVEL");
        assertEquals("ALPHAMISSENSE", firstPlan.snapshot().path("model").asText(), "Public snapshots cannot mutate owned plans");
        var spec = policy.putArray("specializations").addObject().put("preselectedModel", "REVEL").put("status", "UNSUPPORTED").put("reasonCode", "GN017_COMPLETE_PREDICTION_ADAPTATION_PENDING");
        spec.set("hgncId", context.at("/gene/hgncId")); spec.set("mondoId", context.at("/disease/mondoId")); spec.set("moi", context.path("moi"));
        var runtime = runtime(); int before = lookups.get(); var plan = runtime.prepare(candidate, context, "f".repeat(64), "same-allele-new-background", "SYNTHETIC", null);
        var blocked = runtime.execute(plan); assertEquals("REVEL", blocked.at("/plan/model").asText()); assertEquals(before, lookups.get());
        assertEquals("GN017_COMPLETE_PREDICTION_ADAPTATION_PENDING", blocked.at("/criteria/PP3/reasonCode").asText());
        assertNotEquals(plan.snapshot().path("planId"), runtime.prepare(candidate, context, "f".repeat(64), "another-background", "SYNTHETIC", null).snapshot().path("planId"));
    }
}
