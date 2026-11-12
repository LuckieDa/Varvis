package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual publication, HTTP and file H2; identity-only fixture and synthetic REF/AM/gnomAD transports. */
class AcmgRecommendationBusinessTest {
    @TempDir Path temporary;
    Ba1BusinessIntegrationTest slice;
    AcmgBusinessIntegrationTest base;
    String resourceStamp = "SYNTHETIC_IDENTITY_V1";
    Set<String> incompatibleGenes = new HashSet<>();
    String identityFailureReason;
    @BeforeEach void setup() throws Exception {
        slice = new Ba1BusinessIntegrationTest(); slice.temporary = temporary; slice.setup(); base = slice.base; wire();
    }
    @AfterEach void close() { slice.close(); }
    void wire() {
        var suggestions = new AcmgRecommendations(base.mapper, c -> {
            if (identityFailureReason != null) return new AlphaMissenseProvider.IdentityLookup("UNAVAILABLE", identityFailureReason,
                    "2023-08-03", resourceStamp, List.of());
            String tx = "HBB".equals(c.getExomiserCandidateFeatures().getGeneSymbol()) ? "ENST00000647020.1" : "ENST00000382848.5";
            String aa = "HBB".equals(c.getExomiserCandidateFeatures().getGeneSymbol()) ? "E7V" : "V37I";
            return new AlphaMissenseProvider.IdentityLookup("AVAILABLE", "IDENTITY_RECORDS_AVAILABLE", "2023-08-03", resourceStamp,
                    incompatibleGenes.contains(c.getExomiserCandidateFeatures().getGeneSymbol()) ? List.of()
                            : List.of(new AlphaMissenseProvider.IdentityRecord(tx, aa, "SYNTHETIC_UNIPROT")));
        });
        base.service = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources,
                () -> new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources,
                        new ExplicitAlphaMissenseDispatcher(base.mapper, slice.am)),
                () -> new AcmgBusinessService.Ba1Runtime(base.producer, slice.ba1), suggestions);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    ObjectNode options() throws Exception {
        return (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    ObjectNode recommendationRequest(ObjectNode options) {
        ObjectNode request = base.request.deepCopy().put("declarationVersion", "ordinary-ui-v3"); var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var r = options.at("/candidates/" + i + "/recommendation");
            var item = (ObjectNode) request.path("hypotheses").get(i); var hypothesis = (ObjectNode) item.path("hypothesis");
            hypothesis.put("hypothesisId", r.path("hypothesisId").asText()); item.put("affectedStatus", "UNKNOWN");
            var transcript = (ObjectNode) item.path("transcript");
            transcript.put("selectionPolicy", r.path("policyId").asText() + "@" + r.path("policyVersion").asText());
            ((ObjectNode) transcript.path("source")).put("recordLocator", "recommendations/" + r.path("recommendationId").asText() + "/selection");
            JsonNode relation = null;
            for (var rel : r.path("relationships")) if (rel.path("mondoId").equals(hypothesis.path("mondoId"))) relation = rel;
            assertNotNull(relation, r.toPrettyString());
            hypothesis.set("moi", relation.path("moi")); // Raw catalog spelling; the existing resolver normalizes it.
            var ref = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, r.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", r.path("recommendedAnnotationId"));
        }
        return request;
    }
    @Test void publicOptionsUseRetainedAnnotationsThenSubmitPersistReadAndRefreshIdempotently() throws Exception {
        var options = options();
        assertEquals(0, slice.am.calls); assertFalse(Files.exists(temporary.resolve("queries.txt")));
        assertEquals("ENST00000647020.1", options.at("/candidates/0/recommendation/resourceCompatibleTranscript/versionedTranscriptId").asText(), options.toPrettyString());
        assertEquals("ENST00000382848.5", options.at("/candidates/1/recommendation/resourceCompatibleTranscript/versionedTranscriptId").asText());
        assertNotEquals(options.at("/candidates/0/recommendation/hypothesisId"), options.at("/candidates/1/recommendation/hypothesisId"));
        var request = recommendationRequest(options); var first = base.postRequest(request);
        assertEquals(4, first.path("providerCalls").asInt()); assertEquals(2, first.at("/ba1Calls/provider").asInt());
        assertEquals(2, first.path("bundles").size());
        var privateData = base.mapper.readTree(base.store.run(base.analysisId, first.path("runId").asText()).orElseThrow().privateJson());
        assertEquals(2, privateData.path("verifiedRecommendations").size());
        base.recreateRepositories(); wire();
        assertEquals(first, base.postRequest(recommendationRequest(options()))); assertEquals(2, slice.am.calls);
        assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    @Test void staleIdentityPolicyAndSourceReferencesFailBeforeAnyRuleCall() throws Exception {
        var request = recommendationRequest(options()); resourceStamp = "SYNTHETIC_IDENTITY_V2";
        var outcome = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json").content(Objects.requireNonNull(request.toString())))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(Objects.requireNonNull(outcome.getResolvedException(), "resolved exception").getMessage().contains("RECOMMENDATION_STALE"));
        assertEquals(0, slice.am.calls); assertTrue(base.store.runs(base.analysisId).isEmpty());
        assertFalse(Files.exists(temporary.resolve("queries.txt")));
    }
    @Test void diseaseRevisionAndServiceRecreationReuseByteIdenticalBa1WithoutExtraCalls() throws Exception {
        var request = recommendationRequest(options()); var first = base.postRequest(request);
        ((ObjectNode) request.path("hypotheses").get(0)).put("affectedStatus", "UNAFFECTED");
        base.recreateRepositories(); wire(); var revised = base.postRequest(request);
        assertNotEquals(first.path("runId"), revised.path("runId")); assertEquals(0, revised.at("/ba1Calls/provider").asInt());
        assertEquals(0, revised.at("/ba1Calls/evaluator").asInt());
        for (int i = 0; i < 2; i++) assertEquals(first.at("/candidates/" + i + "/ba1"), revised.at("/candidates/" + i + "/ba1"));
        assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertEquals(first, base.service.read(base.analysisId, first.path("runId").asText()));
        resourceStamp = "SYNTHETIC_IDENTITY_V2";
        var selectionRevision = base.postRequest(recommendationRequest(options()));
        assertEquals(0, selectionRevision.at("/ba1Calls/provider").asInt());
        assertEquals(first.at("/candidates/0/ba1"), selectionRevision.at("/candidates/0/ba1"));
    }
    @Test void concurrentDifferentDeclarationsShareBa1AndKeepSeparateRuns() throws Exception {
        var first = base.request.deepCopy(); var second = first.deepCopy().put("declarationVersion", "synthetic-v2");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(first)));
            var b = executor.submit(() -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(second)));
            var ar = a.get(45, TimeUnit.SECONDS); var br = b.get(45, TimeUnit.SECONDS);
            assertNotEquals(ar.path("runId"), br.path("runId"));
            assertEquals(2, ar.at("/ba1Calls/provider").asInt() + br.at("/ba1Calls/provider").asInt());
            assertEquals(ar.at("/candidates/0/ba1"), br.at("/candidates/0/ba1"));
        } finally { executor.shutdownNow(); }
    }
    @Test void populationReadinessReasonsReachTheApiAndDoNotBlockAm() throws Exception {
        Path script = temporary.resolve("synthetic-tabix.cmd");
        Files.writeString(script, Files.readString(script).replace("AN_joint_nfe=9000", "AN_joint_nfe=."));
        var result = base.postRequest(base.request); var ba1 = result.at("/candidates/0/ba1");
        assertEquals("POPULATION_INPUT_NOT_READY", ba1.at("/evaluation/evaluationResult/reasonCode").asText(), ba1.toPrettyString());
        assertFalse(ba1.path("recordInputReadinessReasonCodes").isEmpty(), ba1.toPrettyString());
        assertEquals(2, slice.am.calls);
        assertEquals("MET", result.at("/candidates/0/attempts/0/criterionEvaluations/0/evaluationResult/status").asText());
    }
    @Test void corruptSavedRunDoesNotContaminateCurrentPopulationEvaluation() throws Exception {
        var first = base.postRequest(base.request);
        new org.springframework.jdbc.core.JdbcTemplate(Objects.requireNonNull(base.dataSource)).update(
                "UPDATE variant_analysis_acmg_run SET response_sha256=? WHERE run_id=?", "corrupt-fixture", first.path("runId").asText());
        var revision = base.postRequest(base.request.deepCopy().put("declarationVersion", "synthetic-revision"));
        assertEquals(2, revision.at("/ba1Calls/provider").asInt());
        assertEquals("MET", revision.at("/candidates/0/ba1/evaluation/evaluationResult/status").asText());
    }
    @Test void malformedRecommendationReferencesAreRejectedAtHttpBoundary() throws Exception {
        var request = base.request.deepCopy(); request.putArray("recommendations").addNull();
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json").content(Objects.requireNonNull(request.toString()))).andExpect(status().isBadRequest());
        assertTrue(base.store.runs(base.analysisId).isEmpty()); assertEquals(0, slice.am.calls);
    }
    @ParameterizedTest @ValueSource(strings = { "prefix/%s", "%s/suffix", "%s?forged=1", "forged/%s/selection" })
    void forgedSelectionLocatorsAreStaleBeforeAnyRuleCall(String pattern) throws Exception {
        var request = recommendationRequest(options());
        var source = (ObjectNode) request.at("/hypotheses/0/transcript/source");
        source.put("recordLocator", pattern.formatted(source.path("recordLocator").asText()));
        var result = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json").content(Objects.requireNonNull(request.toString())))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(Objects.requireNonNull(result.getResolvedException(), "resolved exception").getMessage().contains("RECOMMENDATION_STALE"));
        assertEquals(0, slice.am.calls); assertFalse(Files.exists(temporary.resolve("queries.txt")));
        assertTrue(base.store.runs(base.analysisId).isEmpty(), "Rejected before any provider or evaluator can run");
    }
    @Test void displayTimestampCannotDetermineAcceptanceOrRefreshIdentity() throws Exception {
        var request = recommendationRequest(options());
        for (var ref : request.path("recommendations")) ((ObjectNode)ref).put("generatedAt", "client-display-only");
        var first = base.postRequest(request); assertEquals(4, first.path("providerCalls").asInt());
        assertEquals(first, base.postRequest(recommendationRequest(options())));
        assertEquals(2, slice.am.calls); assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    @Test void correctingDeclaredMoiPreservesBa1AndDoesNotRepeatItsCalls() throws Exception {
        // The old explicit path remains valid for blocked declarations; BA1 has no disease/MOI dependency.
        var conflict = base.request.deepCopy(); ((ObjectNode)conflict.at("/hypotheses/0/hypothesis")).put("moi", "AD");
        var blocked = base.postRequest(conflict);
        assertTrue(blocked.at("/candidates/0/attempts/0/blockers").toString().contains("BINDING_NOT_READY"), blocked.toPrettyString());
        assertEquals("CANDIDATE_HYPOTHESIS_MOI_CONFLICT", blocked.at("/candidates/0/attempts/0/preflight/original/binding/reasonCode").asText());
        assertEquals(0, blocked.at("/candidates/0/attempts/0/providerCalls").asInt());
        assertEquals(0, blocked.at("/candidates/0/attempts/0/evaluatorCalls").asInt());
        assertEquals(2, blocked.at("/ba1Calls/provider").asInt());
        assertEquals(2, blocked.at("/ba1Calls/evaluator").asInt());
        var corrected = base.postRequest(base.request);
        assertEquals(0, corrected.at("/ba1Calls/provider").asInt()); assertEquals(0, corrected.at("/ba1Calls/evaluator").asInt());
        assertEquals(blocked.at("/candidates/0/ba1"), corrected.at("/candidates/0/ba1"));
        assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    ObjectNode missingHypothesisRequest(ObjectNode options) {
        var request = base.request.deepCopy().put("declarationVersion", "ordinary-ui-v3");
        request.putArray("hypotheses"); var refs = request.putArray("recommendations");
        ((ObjectNode)request.at("/patient/source")).put("recordLocator", "analysis/" + base.analysisId + "/acmg/ordinary-ui-v3");
        for (var row : options.path("candidates")) {
            var r = row.path("recommendation"); var ref = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt", "stableRelationshipKey")) ref.set(field, r.path(field));
            ref.set("annotationId", r.path("recommendedAnnotationId"));
        }
        return request;
    }
    List<String> blockerCodes(JsonNode result, int index) {
        var codes = new ArrayList<String>();
        for (var blocker : result.at("/candidates/" + index + "/attempts/0/blockers")) codes.add(blocker.path("reasonCode").asText());
        return codes;
    }
    JsonNode readSaved(String runId) throws Exception {
        return base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    void assertNoPrediction(JsonNode result) {
        for (var candidate : result.path("candidates")) for (var attempt : candidate.path("attempts")) {
            assertTrue(attempt.path("context").isNull()); assertTrue(attempt.path("criterionEvaluations").isEmpty());
            assertTrue(attempt.path("atomicEvidence").isEmpty());
            assertEquals(0, attempt.path("providerCalls").asInt()); assertEquals(0, attempt.path("evaluatorCalls").asInt());
        }
        assertEquals(0, slice.am.calls); assertTrue(result.path("bundles").isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans = { true, false })
    void mixedMoiAlternativesPersistOnlyUnresolvedChoiceReasons(boolean selectAd) throws Exception {
        base.variantTsvOverride = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv(
                "1\t11\t2\tT\tA\tHBB\t3043\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, null);
        // Synthetic catalog alternative only; candidates and execution source still come from real publication code.
        base.fixture.resolver = spy(base.fixture.resolver);
        doAnswer(call -> {
            ObjectNode catalog = ((JsonNode) call.callRealMethod()).deepCopy();
            VariantInterpretation candidate = call.getArgument(1);
            if ("HBB".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol())) {
                var relations = (ArrayNode) catalog.path("relationships");
                ObjectNode ar = (ObjectNode) relations.get(0).deepCopy();
                relations.removeAll(); relations.add(ar);
                relations.add(ar.deepCopy().put("moi", "AD").put("reportLocator", "synthetic/mixed-moi/ad"));
                ((ObjectNode) catalog.path("relationshipSource")).put("sha256", "synthetic-mixed-moi-catalog");
            }
            return catalog;
        }).when(base.fixture.resolver).referenceOptions(any(), any());
        incompatibleGenes.add("HBB"); wire();
        var current = options(); var request = missingHypothesisRequest(current);
        JsonNode ad = null;
        for (var relation : current.at("/candidates/0/recommendation/relationships")) {
            if ("AD".equals(relation.path("moi").asText())) { ad = relation; assertTrue(relation.path("selectable").asBoolean()); }
            else { assertFalse(relation.path("selectable").asBoolean()); assertEquals("CANDIDATE_RELATIONSHIP_MOI_CONFLICT", relation.path("reasonCode").asText()); }
        }
        assertNotNull(ad);
        if (selectAd) ((ObjectNode) request.at("/recommendations/0")).set("stableRelationshipKey", ad.path("stableRelationshipKey"));
        var saved = base.postRequest(request); assertNoPrediction(saved);
        assertEquals("POPULATION_ONLY", current.at("/candidates/0/recommendation/resourceCompatibleTranscript/predictionPath").asText());
        assertEquals(selectAd ? List.of("EXPLICIT_INPUT_MISSING")
                : List.of("DISEASE_RELATIONSHIP_REVIEW_REQUIRED"), blockerCodes(saved, 0));
        assertEquals(current.path("candidateIds"), saved.path("candidateIds"));
        assertEquals(2, saved.at("/ba1Calls/provider").asInt()); assertEquals(2, saved.at("/ba1Calls/evaluator").asInt());
        assertEquals(saved, readSaved(saved.path("runId").asText()));
        assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    @ParameterizedTest @ValueSource(strings = { "stableRelationshipKey", "annotationId" })
    void omittedHypothesisWithForgedSelectionIsRejectedBeforeAnyRuleCall(String field) throws Exception {
        var request = missingHypothesisRequest(options());
        ((ObjectNode) request.at("/recommendations/0")).put(field, "forged-nonempty-selection");
        var result = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json").content(Objects.requireNonNull(request.toString())))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(Objects.requireNonNull(result.getResolvedException(), "resolved exception").getMessage().contains("RECOMMENDATION_STALE"));
        assertEquals(0, slice.am.calls); assertFalse(Files.exists(temporary.resolve("queries.txt")));
        assertTrue(base.store.runs(base.analysisId).isEmpty(), "Rejected before providers and evaluators; no durable run exists");
    }
    @Test void populationOnlyRecommendationsRetainMissingConfirmationAndReuseBa1AfterRestart() throws Exception {
        incompatibleGenes.addAll(List.of("HBB", "GJB2"));
        var request = missingHypothesisRequest(options()); var first = base.postRequest(request);
        assertNoPrediction(first);
        for (int i = 0; i < 2; i++) {
            assertFalse(blockerCodes(first, i).contains("NO_COMPATIBLE_TRANSCRIPT"));
            assertEquals(i == 0 ? List.of("DISEASE_RELATIONSHIP_REVIEW_REQUIRED") : List.of("EXPLICIT_INPUT_MISSING"), blockerCodes(first, i));
        }
        assertEquals(2, first.at("/ba1Calls/provider").asInt()); assertEquals(2, first.at("/ba1Calls/evaluator").asInt());
        var persisted = base.store.run(base.analysisId, first.path("runId").asText()).orElseThrow();
        assertEquals(org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(persisted.responseJson()), persisted.responseSha256());
        assertEquals(org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(persisted.privateJson()), first.path("privateArtifactSha256").asText());
        base.recreateRepositories(); wire();
        assertEquals(first, readSaved(first.path("runId").asText()));
        assertEquals(first, base.postRequest(missingHypothesisRequest(options())));
        var revision = request.deepCopy(); ((ObjectNode)revision.at("/patient/source")).put("recordLocator", "case/revision-2");
        var revised = base.postRequest(revision);
        assertNotEquals(first.path("runId"), revised.path("runId")); assertNoPrediction(revised);
        assertEquals(0, revised.at("/ba1Calls/provider").asInt()); assertEquals(0, revised.at("/ba1Calls/evaluator").asInt());
        assertEquals(first.path("candidateIds"), revised.path("candidateIds"));
        for (int i = 0; i < 2; i++) {
            assertEquals(blockerCodes(first, i), blockerCodes(revised, i));
            assertEquals(first.at("/candidates/" + i + "/ba1"), revised.at("/candidates/" + i + "/ba1"));
        }
        assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    @Test void omittedHypothesisPersistsServerMoiConflictAndIndependentReasons() throws Exception {
        base.variantTsvOverride = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv(
                "1\t11\t2\tT\tA\tHBB\t3043\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, null); wire(); incompatibleGenes.add("HBB");
        var current = options(); var saved = base.postRequest(missingHypothesisRequest(current));
        assertEquals(List.of("CANDIDATE_RELATIONSHIP_MOI_CONFLICT", "NO_COMPATIBLE_TRANSCRIPT"), blockerCodes(saved, 0));
        assertNoPrediction(saved);
        assertEquals(2, saved.at("/ba1Calls/provider").asInt()); assertEquals(2, saved.at("/ba1Calls/evaluator").asInt());
        assertEquals(current.path("candidateIds"), saved.path("candidateIds"));
        var privateData = base.mapper.readTree(base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().privateJson());
        assertEquals(current.at("/candidates/0/recommendation/recommendationId"), privateData.at("/verifiedRecommendations/0/recommendationId"));
        assertTrue(privateData.at("/declaration/hypotheses").isEmpty());
    }
    @Test void readyOmissionAndLegacyReadKeepGenericMissingReason() throws Exception {
        var legacy = base.request.deepCopy(); legacy.putArray("hypotheses"); var old = base.postRequest(legacy);
        assertEquals(List.of("EXPLICIT_INPUT_MISSING"), blockerCodes(old, 1));
        var current = options(); assertEquals("READY_FOR_CONFIRMATION", current.at("/candidates/1/recommendation/status").asText());
        var ready = base.postRequest(missingHypothesisRequest(current));
        assertEquals(List.of("EXPLICIT_INPUT_MISSING"), blockerCodes(ready, 1)); assertNoPrediction(ready);
        incompatibleGenes.addAll(List.of("HBB", "GJB2"));
        base.recreateRepositories(); wire();
        assertEquals(old, readSaved(old.path("runId").asText()));
        assertEquals(ready, readSaved(ready.path("runId").asText()));
        assertFalse(old.at("/candidates/1/attempts/0/blockers").toString().contains("NO_COMPATIBLE_TRANSCRIPT"));
    }
    @Test void changedServerReasonCreatesDistinctRunAndClientReasonsAreRejected() throws Exception {
        incompatibleGenes.addAll(List.of("HBB", "GJB2"));
        var request = missingHypothesisRequest(options()); var first = base.postRequest(request);
        identityFailureReason = "ALPHAMISSENSE_IDENTITY_QUERY_FAILED";
        var current = options(); var next = missingHypothesisRequest(current);
        assertNotEquals(request.at("/recommendations/0/recommendationId"), next.at("/recommendations/0/recommendationId"));
        var staleRevision = request.deepCopy(); ((ObjectNode)staleRevision.at("/patient/source")).put("recordLocator", "case/stale-revision");
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json").content(Objects.requireNonNull(staleRevision.toString())))
                .andExpect(status().isConflict());
        for (String field : List.of("reasonCode", "status", "explanation")) {
            var forged = next.deepCopy(); ((ObjectNode)forged.at("/recommendations/0")).put(field, "CLIENT_CLAIM");
            base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json").content(Objects.requireNonNull(forged.toString())))
                    .andExpect(status().isBadRequest());
        }
        var revised = base.postRequest(next); assertNotEquals(first.path("requestSha256"), revised.path("requestSha256"));
        assertTrue(current.toString().contains(identityFailureReason));
        assertEquals(List.of("EXPLICIT_INPUT_MISSING"), blockerCodes(revised, 1)); assertNoPrediction(revised);
        assertFalse(blockerCodes(revised, 1).contains("NO_COMPATIBLE_TRANSCRIPT"));
        assertEquals(0, revised.at("/ba1Calls/provider").asInt()); assertEquals(0, revised.at("/ba1Calls/evaluator").asInt());
        assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertEquals(first, readSaved(first.path("runId").asText()));
    }
    @Test void preciseSourceFailuresPersistAndChangeRecommendationIdentity() throws Exception {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(Objects.requireNonNull(base.dataSource));
        jdbc.update("UPDATE variant_analysis_acmg_source SET snapshot_json='{}' WHERE analysis_id=?", base.analysisId);
        var corruptOptions = options(); var corrupt = base.postRequest(missingHypothesisRequest(corruptOptions));
        assertEquals(List.of("EXECUTION_SNAPSHOT_HASH_MISMATCH"), blockerCodes(corrupt, 1));
        jdbc.update("DELETE FROM variant_analysis_acmg_source WHERE analysis_id=?", base.analysisId);
        var missingOptions = options(); var missing = base.postRequest(missingHypothesisRequest(missingOptions));
        assertNotEquals(corruptOptions.at("/candidates/1/recommendation/recommendationId"), missingOptions.at("/candidates/1/recommendation/recommendationId"));
        assertEquals(List.of("EXECUTION_SOURCE_UNAVAILABLE"), blockerCodes(missing, 1));
        assertEquals(0, corrupt.path("providerCalls").asInt()); assertEquals(0, missing.path("evaluatorCalls").asInt());
        assertEquals(0, slice.am.calls); assertFalse(Files.exists(temporary.resolve("queries.txt")));
        assertEquals(corrupt, readSaved(corrupt.path("runId").asText()));
    }
}
