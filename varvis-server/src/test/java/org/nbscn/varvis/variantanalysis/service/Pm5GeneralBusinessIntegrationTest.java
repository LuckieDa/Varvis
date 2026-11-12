package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SYNTHETIC / TEST_ONLY. Reuses the PS1 directory, discovery and file H2 harness. */
class Pm5GeneralBusinessIntegrationTest {
    @TempDir Path temporary;
    Ps1GeneralBusinessIntegrationTest shared;
    AcmgBusinessIntegrationTest base;
    Ps1TestCatalog fixture;
    ObjectNode document;
    @BeforeEach void setup() throws Exception {
        shared = new Ps1GeneralBusinessIntegrationTest(); shared.temporary = temporary; shared.setup();
        base = shared.base; fixture = shared.fixture; fixture.configurePm5(); document = fixture.document(); wire();
    }
    @AfterEach void close() { if (shared != null) shared.close(); }
    void wire() throws Exception {
        var bundle = fixture.load(temporary, document);
        base.recreateService(() -> { base.invocations.incrementAndGet(); return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources); });
        base.service = AcmgBusinessService.pm5Development(base.service, bundle);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    ObjectNode request() throws Exception {
        return request(shared.options());
    }
    ObjectNode request(ObjectNode options) throws Exception {
        assertEquals(AcmgBusinessRuleScope.PM5_DECLARATION, options.path("requiredDeclaration").asText());
        assertEquals(List.of(MissenseComparisonRuntime.PM5), base.mapper.convertValue(options.path("enabledCriteria"), List.class));
        assertFalse(options.at("/pm5ReferencePreparation/transcriptConfirmationIsReferenceApproval").asBoolean(true));
        var request = shared.confirmedRequest(options).put("declarationVersion", AcmgBusinessRuleScope.PM5_DECLARATION);
        for (var hypothesis : request.path("hypotheses"))
            assertTrue(hypothesis.at("/transcript/selectionPolicy").asText().startsWith("REVIEWED_RULE_CONTEXT@4.0.0:"));
        return request;
    }
    JsonNode evaluation(JsonNode run, int candidate) { return shared.evaluation(run, candidate); }
    void summary(JsonNode run, String first, String second, String status) {
        shared.assertSummary(run, status);
        assertEquals(first, evaluation(run, 0).at("/evaluationResult/status").asText(), run.toPrettyString());
        assertEquals(second, evaluation(run, 1).at("/evaluationResult/status").asText(), run.toPrettyString());
        for (var c : run.path("candidates")) for (var a : c.path("attempts")) {
            assertEquals(1, a.at("/missenseComparison/criterionEvaluations").size());
            assertEquals(MissenseComparisonRuntime.PM5, a.at("/missenseComparison/criterionEvaluations/0/criterionId").asText());
        }
    }
    @Test void twoBackgroundsSavedReopenedAndReusedWithZeroNewWork() throws Exception {
        var options = shared.options();
        var request = request(options); var saved = base.postRequest(request);
        summary(saved, "MET", "MET", "COMPLETED");
        assertEquals("MODERATE", evaluation(saved, 0).path("appliedStrength").asText());
        assertEquals("SUPPORTING", evaluation(saved, 1).path("appliedStrength").asText());
        assertEquals("GRANTHAM", saved.at("/candidates/0/attempts/0/missenseComparison/proteinComparisons/0/matrixKind").asText());
        for (int i = 0; i < 2; i++) shared.assertDigests(evaluation(saved, i));
        assertEquals(2, fixture.discoveryCalls); assertEquals(2, fixture.annotationCalls); assertEquals(2, fixture.spliceCalls);
        assertEquals(2, saved.at("/missenseCalls/evaluator").asInt());
        String run = saved.path("runId").asText(); base.closeDatabase();
        assertTrue(Files.size(temporary.resolve("business-db.mv.db")) > 0);
        base.recreateRepositories(); wire(); int before = base.invocations.get();
        var reopened = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, reopened); assertEquals(saved, base.postRequest(request));
        assertEquals(before, base.invocations.get()); assertEquals(1, base.store.runs(base.analysisId).size());
        assertEquals(2, fixture.discoveryCalls); assertEquals(2, fixture.annotationCalls); assertEquals(2, fixture.spliceCalls);
        artifact("TWO_BACKGROUNDS", "submit", saved); artifact("TWO_BACKGROUNDS", "get-reopened", reopened);
        artifact("TWO_BACKGROUNDS", "input-options", options);
        artifact("TWO_BACKGROUNDS", "request", request); artifact("TWO_BACKGROUNDS", "bundle", document);
        artifact("TWO_BACKGROUNDS", "counters", base.mapper.createObjectNode().put("dataClass", "SYNTHETIC")
                .put("fileH2Reopened", true).put("discovery", fixture.discoveryCalls).put("annotation", fixture.annotationCalls)
                .put("splice", fixture.spliceCalls).put("additionalCoordinatorCalls", base.invocations.get() - before).put("additionalComputations", 0));
        fixture.materials.get(0).put("version", "2"); document = fixture.document(); wire();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(request)));
        var next = base.postRequest(request()); summary(next, "MET", "MET", "COMPLETED");
        assertNotEquals(run, next.path("runId").asText()); assertEquals(saved, base.service.read(base.analysisId, run));
        artifact("TWO_BACKGROUNDS", "updated-pin", next);
    }
    @ParameterizedTest @ValueSource(strings={"SAME", "MATRIX_FAIL", "PARTIAL", "UNAUDITED", "INDEPENDENCE", "SPLICE", "MATERIAL", "MATERIAL_SHA", "FAILURE_SUPPORTING"})
    void outcomesAndSummaryRemainAligned(String scenario) throws Exception {
        if (scenario.equals("SAME")) {
            fixture.different = false;
            for (int i = 0; i < 2; i++) {
                var m = fixture.materials.get(i); var ref = (ObjectNode) m.at("/referenceAudit/references/0");
                ref.set("allele", fixture.allele(m.at("/nativeModel/contig").asText(), 105, "A", "T"));
                ref.set("annotation", fixture.annotation(i, true));
                ((ObjectNode) m.at("/rnaReview/comparisons/0")).set("referenceAllele", ref.path("allele").deepCopy());
            }
        }
        fixture.incomplete = scenario.equals("PARTIAL");
        if (scenario.equals("MATRIX_FAIL") || scenario.equals("FAILURE_SUPPORTING") || scenario.equals("PARTIAL")) {
            ((ObjectNode) fixture.materials.get(0).at("/substitutionMatrix/matrix/E")).put("D", 10);
            ((ObjectNode) fixture.materials.get(0).at("/substitutionMatrix/matrix/D")).put("E", 10);
            if (scenario.equals("FAILURE_SUPPORTING")) ((ObjectNode) fixture.policies.get(0).at("/rules/PM5/failureStrengths")).put("PATHOGENIC", "SUPPORTING");
        }
        if (scenario.equals("INDEPENDENCE")) ((ObjectNode) fixture.materials.get(0).at("/referenceAudit/references/0")).remove("independence");
        fixture.missingSplice = scenario.equals("SPLICE");
        document = fixture.document();
        String sha = document.at("/policies/0/materialsSha256").asText();
        if (scenario.equals("MATERIAL")) ((ObjectNode) document.at("/materials/missenseMaterials")).remove(sha);
        if (scenario.equals("MATERIAL_SHA")) ((ObjectNode) document.at("/materials/missenseMaterials").path(sha)).put("version", "UNBOUND");
        if (scenario.equals("UNAUDITED")) {
            var unreviewed = fixture.materials.get(0).deepCopy(); unreviewed.withObject("referenceAudit").putArray("references");
            ((ObjectNode) document.at("/materials/missenseMaterials")).remove(sha);
            String nextSha = fixture.canonicalSha256(unreviewed);
            ((ObjectNode) document.at("/materials/missenseMaterials")).set(nextSha, unreviewed);
            ObjectNode policy = (ObjectNode) document.at("/policies/0"); policy.put("materialsSha256", nextSha);
            ((ObjectNode) document.at("/catalog/entries/0/policyBinding")).put("policySha256", fixture.canonicalSha256(policy));
            document.put("catalogSha256", fixture.canonicalSha256(document.path("catalog")));
        }
        wire(); var saved = base.postRequest(request());
        String first = switch (scenario) { case "SAME", "MATRIX_FAIL" -> "NOT_MET"; case "FAILURE_SUPPORTING" -> "MET"; default -> "NOT_EVALUATED"; };
        String second = scenario.equals("SPLICE") ? "NOT_EVALUATED" : scenario.equals("SAME") ? "NOT_MET" : "MET";
        summary(saved, first, second, first.equals("NOT_EVALUATED") ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED");
        if (first.equals("NOT_EVALUATED")) assertEquals(switch (scenario) {
            case "PARTIAL" -> "MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE";
            case "UNAUDITED" -> "INDEPENDENT_REFERENCE_EVIDENCE_MISSING";
            case "INDEPENDENCE" -> "REFERENCE_NOT_INDEPENDENT";
            case "MATERIAL" -> "PM5_REFERENCE_MATERIALS_MISSING";
            case "MATERIAL_SHA" -> "PM5_REFERENCE_MATERIALS_SHA_MISMATCH";
            default -> "SPLICE_EVIDENCE_UNAVAILABLE";
        }, evaluation(saved, 0).at("/evaluationResult/reasonCode").asText());
        else { shared.assertDigests(evaluation(saved, 0)); assertEquals(first.equals("NOT_MET") ? "NONE" : "SUPPORTING", evaluation(saved, 0).path("appliedStrength").asText()); }
        artifact(scenario, "submit", saved);
    }
    @Test void clientCannotInjectScienceOrUseWrongDeclarationOrToken() throws Exception {
        var valid = request();
        for (String field : List.of("policy", "referenceAudit", "materialsSha256", "approved")) {
            var forged = valid.deepCopy(); forged.putObject(field).put("approved", true);
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(forged)));
        }
        var wrong = valid.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.PS1_DECLARATION);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(wrong)));
        var unconfirmed = valid.deepCopy(); unconfirmed.remove("recommendations");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(unconfirmed)));
        String forgedToken = base.mapper.writeValueAsString(valid).replace("REVIEWED_RULE_CONTEXT@4.0.0:", "REVIEWED_RULE_CONTEXT@3.0.0:");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> base.service.submit(base.analysisId, forgedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(0, fixture.discoveryCalls); assertEquals(0, fixture.spliceCalls); assertTrue(base.store.runs(base.analysisId).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"MISSING", "DUPLICATE", "DAMAGED", "WRONG_CRITERION", "REASONLESS", "SECOND_ATTEMPT_MISSING"})
    void invalidResultsCannotComplete(String fault) throws Exception {
        // Reuse R1's corruption checks against this PM5-only service and confirmation.
        var saved = base.postRequest(request()); summary(saved, "MET", "MET", "COMPLETED");
        var damaged = saved.deepCopy();
        var evaluations = (com.fasterxml.jackson.databind.node.ArrayNode) damaged.at("/candidates/0/attempts/0/missenseComparison/criterionEvaluations");
        ObjectNode e = (ObjectNode) evaluations.get(0);
        switch (fault) {
            case "MISSING" -> evaluations.removeAll();
            case "DUPLICATE" -> evaluations.add(e.deepCopy());
            case "DAMAGED" -> e.remove("deterministicTrace");
            case "WRONG_CRITERION" -> e.put("criterionId", MissenseComparisonRuntime.PS1);
            case "REASONLESS" -> { e.removeAll(); e.put("criterionId", MissenseComparisonRuntime.PM5); e.putObject("evaluationResult").put("status", "NOT_EVALUATED"); }
            case "SECOND_ATTEMPT_MISSING" -> {
                var a = ((ObjectNode) damaged.at("/candidates/0/attempts/0")).deepCopy(); a.remove("missenseComparison");
                ((com.fasterxml.jackson.databind.node.ArrayNode) damaged.at("/candidates/0/attempts")).add(a);
            }
            default -> fail(fault);
        }
        var service = Objects.requireNonNull(base.service);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "finishCurrentScope", damaged);
        assertEquals("COMPLETED_WITH_BLOCKERS", damaged.path("status").asText());
        assertFalse(damaged.at("/candidates/0/enabledRuleResultsComplete").asBoolean());
        assertTrue(damaged.at("/candidates/1/enabledRuleResultsComplete").asBoolean());
        assertEquals(saved, service.read(base.analysisId, saved.path("runId").asText()));
        artifact("SUMMARY_" + fault, "summary", damaged);
    }
    void artifact(String scenario, String name, JsonNode value) throws Exception {
        String output = System.getProperty("varvis.pm5.artifacts"); if (output == null) return;
        Path dir = Path.of(output).resolve(scenario); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
}
