package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ordinary confirmation contract and actual H2 publication; all science/transport here is synthetic. */
class Bs1AcceptanceBusinessTest {
    @TempDir Path temporary;
    Bs1BusinessIntegrationTest fixture;
    AcmgBusinessIntegrationTest base;
    AcmgBusinessService formal;
    @BeforeEach void setup() throws Exception {
        fixture = new Bs1BusinessIntegrationTest(); fixture.temporary = temporary; fixture.setup(); base = fixture.base;
        var current = new CurrentPopulationBusinessTest(); current.temporary = temporary;
        current.fixture = fixture.fixture; current.base = base; current.current(); formal = base.service;
        base.service = AcmgBusinessService.bs1Acceptance(formal, () -> Bs1Runtime.configured(base.mapper, fixture.policies,
                fixture.fixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString()), null));
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        base.request.put("declarationVersion", AcmgBusinessRuleScope.BS1_DECLARATION); base.request.putArray("hypotheses");
        var options = base.service.inputOptions(base.analysisId, 2); var selections = base.request.putArray("recommendations");
        for (var row : options.path("candidates")) {
            var recommendation = row.path("recommendation"); var selected = selections.addObject();
            for (String key : new String[]{"candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"}) selected.set(key, recommendation.path(key));
            selected.set("stableRelationshipKey", recommendation.at("/bs1ContextPreparation/eligibleRelationshipKeys/0")); selected.putNull("annotationId");
        }
    }
    @AfterEach void close() { fixture.close(); }
    @Test void ordinaryBs1WithoutPm2PolicyOrTranscriptSavesAndReadsIdempotently() throws Exception {
        var result = fixture.fixture.post();
        assertEquals(base.mapper.valueToTree(AcmgBusinessRuleScope.BS1_ACCEPTANCE), result.path("enabledCriteria"));
        assertEquals(2, result.at("/bs1Calls/provider").asInt(), result.toPrettyString());
        for (var row : result.path("candidates")) {
            var attempt = row.at("/attempts/0");
            assertEquals("evidence-context-v3", attempt.at("/bs1/context/schemaVersion").asText(), attempt.toPrettyString());
            assertFalse(attempt.at("/bs1/context").has("transcript"));
            assertEquals("MET", attempt.at("/bs1/evaluation/evaluationResult/status").asText());
            assertFalse(attempt.has("hnf1aFrequency")); assertFalse(attempt.has("populationFrequency"));
        }
        int before = Files.readAllLines(temporary.resolve("queries.txt")).size();
        assertEquals(result, fixture.fixture.post()); assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
        assertEquals(before, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertEquals("3.0.1", formal.inputOptions(base.analysisId, 2).at("/executionProfile/profileVersion").asText());
        assertEquals(base.mapper.valueToTree(AcmgBusinessRuleScope.LEGACY_TWO_RULE), formal.inputOptions(base.analysisId, 2).path("enabledCriteria"));
    }
    @Test void oldTwoRuleOrDevelopmentDeclarationCannotAuthorizeAcceptance() throws Exception {
        for (String version : new String[]{"ordinary-ui-v3", Bs1Runtime.DECLARATION, "ba1-explicit-input-v1"}) {
            base.request.put("declarationVersion", version);
            base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                    .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
        }
        assertFalse(Files.exists(temporary.resolve("queries.txt")));
    }
    @Test void absentConfirmationStaysUnevaluatedAndClientCannotExpandScope() throws Exception {
        for (var selection : base.request.path("recommendations")) ((ObjectNode) selection).putNull("stableRelationshipKey");
        var missing = fixture.fixture.post(); assertEquals(0, missing.at("/bs1Calls/provider").asInt());
        assertEquals(0, missing.at("/bs1Calls/evaluator").asInt());
        for (var row : missing.path("candidates")) {
            assertTrue(row.at("/attempts/0/bs1/hypothesisId").isNull());
            assertEquals("EXPLICIT_INPUT_MISSING", row.at("/attempts/0/bs1/evaluation/evaluationResult/reasonCode").asText());
        }
        base.request.putArray("enabledCriteria").add("ACMG_AMP_2015:PS3");
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isBadRequest());
    }
    @Test void wrongCandidateRecommendationIsRejectedBeforeAnyQuery() throws Exception {
        ((ObjectNode) base.request.at("/recommendations/0")).put("candidateId", "not-this-candidate");
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        assertFalse(Files.exists(temporary.resolve("queries.txt")));
    }
}
