package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual submit/source/context/runtime/H2/GET path. Coordinates, policies and transport are synthetic. */
class Bs1BusinessIntegrationTest {
    @TempDir Path temporary;
    Ba1BusinessIntegrationTest fixture;
    AcmgBusinessIntegrationTest base;
    List<JsonNode> policies;
    @BeforeEach void setup() throws Exception {
        fixture = new Ba1BusinessIntegrationTest(); fixture.temporary = temporary; fixture.setup(); base = fixture.base;
        base.request.put("declarationVersion", Bs1Runtime.DECLARATION);
        policies = new ArrayList<>();
        for (var item : base.request.path("hypotheses")) {
            ((ObjectNode) item).remove("transcript"); var h = item.path("hypothesis");
            var policy = (ObjectNode) Bs1Runtime.policies(base.mapper).get(0).deepCopy();
            policy.withObject("/qualityContract/releaseSemantics").remove("jointFilterDefinition");
            policy.withObject("/qualityContract/releaseSemantics").put("missingSubtype", "REQUIRED").put("subtypeDot", "UNKNOWN_REQUIRES_NATIVE");
            policy.put("policyId", "SYNTHETIC_ONLY_" + h.path("hgncId").asText());
            policy.withObject("/scope").put("hgncId", h.path("hgncId").asText()).put("mondoId", h.path("mondoId").asText());
            policy.withObject("/scope").putArray("modes").add(h.path("moi").asText());
            policy.withObject("/tiers/0").put("moi", h.path("moi").asText()); policies.add(policy);
        }
        Path script = temporary.resolve("synthetic-tabix.cmd");
        String raw = Files.readString(script).replace("fafmax_faf95_max_joint=0.055", "fafmax_faf95_max_joint=0.00004428")
                .replace("faf95_joint_nfe=0.054", "faf95_joint_nfe=0.00004428;exomes_filters=PASS;genomes_filters=PASS;stat_union_p_value=0.5;stat_union_test_name=cmh;stat_union_gen_ancs=nfe");
        Files.writeString(script, raw); wire();
    }
    void wire() {
        var disabled = mock(ExplicitAlphaMissenseDispatcher.class, invocation -> { throw new AssertionError("Disabled dispatcher"); });
        base.service = AcmgBusinessService.bs1Development(base.mapper, base.jobs, base.store, base.sources,
                () -> new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources, disabled),
                () -> Bs1Runtime.configured(base.mapper, policies, fixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString()), null));
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    @AfterEach void close() { fixture.close(); }
    @Test void explicitPurposeWithoutTranscriptSurvivesActualSaveAndReadAndRetryIsIdempotent() throws Exception {
        var result = fixture.post();
        assertEquals("COMPLETED", result.path("status").asText(), result.toString());
        assertEquals(2, result.at("/bs1Calls/provider").asInt()); assertEquals(2, result.at("/bs1Calls/evaluator").asInt());
        assertEquals(0, result.at("/bs1Calls/allSites").asInt()); assertFalse(result.has("ba1Calls")); assertFalse(result.has("pm2Calls"));
        for (var row : result.path("candidates")) {
            var attempt = row.at("/attempts/0"); assertFalse(attempt.path("context").has("transcript"));
            assertEquals("evidence-context-v3", attempt.at("/context/schemaVersion").asText());
            assertEquals("READY", attempt.at("/bs1/preparation/status").asText());
            assertEquals("MET", attempt.at("/bs1/evaluation/evaluationResult/status").asText());
            assertFalse(attempt.has("pm2")); assertFalse(attempt.has("populationFrequency"));
        }
        int queriesBeforeRead = Files.readAllLines(temporary.resolve("queries.txt")).size();
        assertEquals(result, fixture.post());
        assertEquals(queriesBeforeRead, Files.readAllLines(temporary.resolve("queries.txt")).size());
        var stored = base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow();
        base.recreateRepositories(); wire();
        assertEquals(stored.responseJson(), base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow().responseJson());
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
        assertEquals(result, fixture.post());
        assertEquals(queriesBeforeRead, Files.readAllLines(temporary.resolve("queries.txt")).size());
        // A changed quality contract must not reuse the preceding saved result.
        ((ObjectNode) policies.get(0)).withObject("/qualityContract").put("version", "SYNTHETIC_NEXT_QC");
        ((ObjectNode) policies.get(0)).withObject("/qualityContract/releaseSemantics")
                .put("missingSubtype", "OPTIONAL_WITH_BOUND_JOINT_FILTER").put("jointFilterDefinition", "GNOMAD_4_1_JOINT_FILTER");
        wire();
        var changed = fixture.post(); assertNotEquals(result.path("runId"), changed.path("runId"));
        assertEquals(queriesBeforeRead + 2, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertEquals("BS1_JOINT_FILTER_DEFINITION_UNBOUND", changed.at("/candidates/0/attempts/0/bs1/evaluation/evaluationResult/reasonCode").asText());
        assertEquals(changed, base.service.read(base.analysisId, changed.path("runId").asText()));
        assertEquals(changed, fixture.post());
        assertEquals(queriesBeforeRead + 2, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
    }
    @Test void missingDeclarationsAndWrongCandidateAreSavedWithoutQueries() throws Exception {
        base.request.putArray("hypotheses"); var empty = fixture.post();
        assertEquals(0, empty.at("/bs1Calls/provider").asInt()); assertEquals(0, empty.at("/bs1Calls/evaluator").asInt());
        assertEquals("COMPLETED_WITH_BLOCKERS", empty.path("status").asText());
        assertEquals("NOT_EVALUATED", empty.at("/candidates/0/attempts/0/bs1/evaluation/evaluationResult/status").asText());
        base.request = base.declaration().put("declarationVersion", Bs1Runtime.DECLARATION);
        for (var h : base.request.path("hypotheses")) { ((ObjectNode) h).remove("transcript"); ((ObjectNode) h.path("hypothesis")).put("candidateId", "not-this-candidate"); }
        var wrong = fixture.post(); assertFalse(wrong.path("unmatchedInputs").isEmpty()); assertEquals(0, wrong.at("/bs1Calls/provider").asInt());
    }
    @Test void oldDeclarationCannotAuthorizeBs1() throws Exception {
        base.request.put("declarationVersion", "ba1-explicit-input-v1");
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
        assertFalse(Files.exists(temporary.resolve("queries.txt")));
    }
    @Test void sameAlleleDifferentBackgroundSharesObservationNotContextOrEvaluation() throws Exception {
        var first = (ObjectNode) base.request.at("/hypotheses/0").deepCopy();
        first.withObject("/hypothesis").put("hypothesisId", "second-background").put("mondoId", "MONDO:0099999");
        base.request.withArray("/hypotheses").add(first);
        var policy = (ObjectNode) policies.get(0).deepCopy(); policy.put("policyId", "SYNTHETIC_OTHER_BACKGROUND");
        policy.withObject("/scope").put("mondoId", "MONDO:0099999"); policy.withObject("/tiers/0/conditions/0").put("decimal", "0.003"); policies.add(policy); wire();
        var result = fixture.post(); assertEquals(2, result.at("/bs1Calls/provider").asInt()); assertEquals(3, result.at("/bs1Calls/evaluator").asInt());
        var attempts = result.at("/candidates/0/attempts"); assertEquals(2, attempts.size());
        assertEquals(attempts.get(0).at("/bs1/observation/observationId"), attempts.get(1).at("/bs1/observation/observationId"));
        assertNotEquals(attempts.get(0).at("/context/contextId"), attempts.get(1).at("/context/contextId"));
        assertNotEquals(attempts.get(0).at("/bs1/evidence/evidenceId"), attempts.get(1).at("/bs1/evidence/evidenceId"));
        assertNotEquals(attempts.get(0).at("/bs1/evaluation/evaluationResult/status"), attempts.get(1).at("/bs1/evaluation/evaluationResult/status"));
    }
}
