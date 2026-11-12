package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual saved sources, full legacy declarations and H2 publication; population transport is synthetic. */
class CurrentPopulationBusinessTest {
    @TempDir Path temporary;
    Ba1BusinessIntegrationTest fixture;
    AcmgBusinessIntegrationTest base;

    @BeforeEach void setup() throws Exception {
        fixture = new Ba1BusinessIntegrationTest(); fixture.temporary = temporary; fixture.setup(); base = fixture.base;
    }
    @AfterEach void close() { fixture.close(); }

    void current() {
        var disabled = mock(ExplicitAlphaMissenseDispatcher.class, call -> { throw new AssertionError("Disabled dispatcher called: " + call.getMethod()); });
        var mixedPopulation = mock(PopulationFrequencyBundle.class, call -> { throw new AssertionError("Mixed legacy population preparation called: " + call.getMethod()); });
        base.service = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources,
                () -> new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources, disabled),
                () -> new AcmgBusinessService.Ba1Runtime(base.producer, fixture.ba1),
                AcmgRecommendations.current(base.mapper, null),
                () -> new Pm2SupportingRuntime(base.mapper, fixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString())),
                () -> { throw new AssertionError("PVS1 called"); },
                () -> { throw new AssertionError("PS1/PM5 called"); }, null,
                () -> { throw new AssertionError("PS3 called"); }, mixedPopulation, null, null, null, null,
                () -> { throw new AssertionError("PM1 called"); }, true);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    @Test void completeLegacyContextsCannotDispatchDisabledRulesAndCurrentRequestIsIdempotent() throws Exception {
        var old = fixture.post();
        String oldId = old.path("runId").asText();
        current();
        var options = base.service.inputOptions(base.analysisId, 2);
        assertEquals(base.mapper.valueToTree(AcmgBusinessRuleScope.LEGACY_TWO_RULE), options.path("enabledCriteria"));
        assertFalse(options.toString().contains("hnf1aFrequencyContextPreparation"));
        var result = fixture.post();
        assertNotEquals(oldId, result.path("runId").asText());
        assertNotEquals(old.path("requestSha256"), result.path("requestSha256"));
        assertEquals(old, base.service.read(base.analysisId, oldId));
        assertEquals(result, fixture.post());
        for (var row : result.path("candidates")) {
            assertTrue(row.path("ba1").isObject());
            assertTrue(row.path("enabledRuleResultsComplete").asBoolean(), row.toString());
            for (var attempt : row.path("attempts")) {
                assertTrue(attempt.path("context").isObject(), attempt.toString());
                assertTrue(attempt.path("pm2").isObject());
                assertEquals("READY", attempt.at("/pm2/contextPreparation/status").asText());
                assertEquals("POST_SUBMISSION", attempt.at("/pm2/contextPreparation/stage").asText());
                assertFalse(attempt.at("/pm2/contextPreparation").has("reasonCode"));
                assertEquals(attempt.at("/context/contextId"), attempt.at("/pm2/contextPreparation/contextId"));
                for (String key : List.of("criterionEvaluations", "pvs1", "pm1", "functional", "missenseComparison", "populationFrequency", "hnf1aFrequency", "bp7Disposition"))
                    assertFalse(attempt.has(key), key);
            }
        }
        for (String key : List.of("pvs1Calls", "pm1Calls", "functionalCalls", "missenseCalls", "hnf1aFrequencyCalls", "populationFrequencyCalls"))
            assertFalse(result.has(key), key);
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
    }

    @Test void absentContextDoesNotPreventIndependentBa1AndWrongAssociationStaysRejected() throws Exception {
        current(); base.request.putArray("hypotheses");
        var missing = fixture.post();
        for (var row : missing.path("candidates")) {
            assertTrue(row.path("ba1").has("evaluation"));
            assertEquals("NOT_EVALUATED", row.at("/attempts/0/pm2/evaluationResult/status").asText());
            assertEquals("BLOCKED", row.at("/attempts/0/pm2/contextPreparation/status").asText());
        }
        assertEquals(0, missing.at("/pm2Calls/provider").asInt());
        assertEquals("COMPLETED_WITH_BLOCKERS", missing.path("status").asText());
        base.request = base.declaration();
        ((ObjectNode) base.request.at("/hypotheses/0/hypothesis")).put("candidateId", "wrong-candidate");
        var wrong = fixture.post();
        assertFalse(wrong.path("unmatchedInputs").isEmpty());
        assertFalse(wrong.at("/candidates/0/attempts/0/context").isObject());
    }

    @Test void finalPreparationUsesAcceptedAssociationNotScientificOutcomeAndBothDataClassesPersist() throws Exception {
        current();
        for (String dataClass : List.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME")) {
            base.request.put("dataClass", dataClass);
            for (var input : base.request.path("hypotheses")) {
                var hypothesis = (ObjectNode) input.path("hypothesis");
                if ("AUTOSOMAL_RECESSIVE".equals(hypothesis.path("moi").asText())) hypothesis.put("moi", "AR");
                if ("AUTOSOMAL_DOMINANT".equals(hypothesis.path("moi").asText())) hypothesis.put("moi", "AD");
            }
            var saved = fixture.post();
            assertEquals(dataClass, saved.path("dataClass").asText());
            var stored = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
            assertEquals(dataClass, base.mapper.readTree(stored.requestJson()).at("/declaration/dataClass").asText());
            assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
            var declarations = base.mapper.convertValue(base.request.path("hypotheses"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<ExplicitCaseInputCoordinator.HypothesisInput>>() {});
            var row = saved.path("candidates").get(0);
            var attempt = (ObjectNode) row.path("attempts").get(0).deepCopy();
            assertEquals(dataClass, attempt.at("/context/dataClass").asText());
            var scientific = attempt.path("pm2").deepCopy();
            var before = attempt.deepCopy();
            var recommendation = base.mapper.createObjectNode().put("reasonCode", "PM2_CONTEXT_CONFIRMATION_REQUIRED");
            var ready = base.service.finalPm2Preparation(row.path("candidateId").asText(), attempt, declarations, recommendation, true, null);
            assertEquals("READY", ready.path("status").asText());
            assertEquals(before, attempt, "Diagnostic generation cannot mutate scientific observations or evaluations");
            attempt.putObject("pm2").putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", "RESOURCE_TIMEOUT");
            assertEquals(ready, base.service.finalPm2Preparation(row.path("candidateId").asText(), attempt, declarations, recommendation, true, null));
            attempt.set("pm2", scientific);
            attempt.put("candidateId", "another-candidate");
            assertEquals("BLOCKED", base.service.finalPm2Preparation(row.path("candidateId").asText(), attempt, declarations, recommendation, true, null).path("status").asText());
            attempt.put("candidateId", row.path("candidateId").asText());
            ((ObjectNode) attempt.path("context")).put("contextId", "tampered");
            assertEquals("BLOCKED", base.service.finalPm2Preparation(row.path("candidateId").asText(), attempt, declarations, recommendation, true, null).path("status").asText());
        }
    }

    @Test void currentProfileLoadsOnlyProjectedPopulationSlices() {
        var profile = AcmgExecutionProfile.legacyTwoRule(base.mapper, null).document();
        assertEquals(base.mapper.valueToTree(AcmgBusinessRuleScope.LEGACY_TWO_RULE), profile.path("enabledCriteria"));
        for (var slice : profile.path("slices")) for (JsonNode id : slice.path("criterionIds"))
            assertTrue(AcmgBusinessRuleScope.LEGACY_TWO_RULE.contains(id.asText()));
        assertFalse(profile.has("baseExecutionProfile"));
        assertFalse(profile.has("missenseScientificPolicy"));
        assertEquals(profile, AcmgExecutionProfile.legacyTwoRule(base.mapper, null).document());
    }

    @Test void absentDiseaseRelationshipDoesNotBorrowPredictionConsequenceFilters() {
        var candidate = base.jobs.findInterpretations(base.analysisId, 0, 1).get(0);
        for (var annotation : candidate.getTranscriptAnnotations())
            org.springframework.test.util.ReflectionTestUtils.setField(java.util.Objects.requireNonNull(annotation), "consequence", "upstream_gene_variant");
        var catalog = base.mapper.createObjectNode(); catalog.putArray("relationships");
        var recommendation = AcmgRecommendations.current(base.mapper, null).recommend(base.analysisId, candidate, catalog, "a".repeat(64));
        assertFalse(recommendation.path("annotations").isEmpty(), "Verified VEP annotation availability is independent of predictor applicability");
        assertEquals("NOT_QUERIED_POPULATION_CONTEXT_ONLY", recommendation.at("/resourceIdentity/reasonCode").asText());
        assertEquals("PM2_DISEASE_CATALOG_NO_RECORD", recommendation.at("/pm2ContextPreparation/diagnostics/reasonCode").asText());
        assertFalse(recommendation.at("/pm2ContextPreparation/available").asBoolean());
        for (var annotation : recommendation.path("annotations")) assertFalse(annotation.path("contextSelectable").asBoolean());
    }
}
