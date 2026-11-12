package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Normal CURRENT/HTTP/H2 + real Java capture/Python/LocalIdentity. All source facts and adoption are synthetic. */
class Pp1FormalBusinessIntegrationTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;
    private Bs1KnowledgeBusinessTest fixture;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessService population;
    private AcmgBusinessWorkflows workflows;
    private Path workflow;

    @AfterEach void close() { if (fixture != null) fixture.close(); }

    private JsonNode formalEvaluation(JsonNode result) {
        for (var criterion : result.at("/candidates/0/attempts/0/localBatch/criteria"))
            if (Pp1SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText()))
                return criterion.at("/details/pp1Facts/evaluation");
        throw new AssertionError("PP1 criterion missing from saved business result");
    }

    private long pythonEvaluations(Path project) throws Exception {
        try (var paths = Files.list(project.resolve("local/work/pp1-evaluations"))) { return paths.filter(Files::isDirectory).count(); }
    }

    private void assertSameScience(JsonNode expected, JsonNode actual) {
        var left = ((ObjectNode) expected).deepCopy(); var right = ((ObjectNode) actual).deepCopy();
        left.remove(List.of("target", "ledger")); right.remove(List.of("target", "ledger"));
        assertEquals(left, right);
    }

    private void install() throws Exception {
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population, (effects, pin) ->
            new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("syntheticTest", "formal-pp1-business"),
                null, null, null, null,
                new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,
                    new ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                    (analysis, run) -> { throw new AssertionError("Source capture must not execute business"); }).captureCatalog()),
                null, null, null, null, null, null, true, null, null,
                new Pp1SelectedBatch(base.mapper, Pp1LiteratureJobs.businessSource(workflow))), temporary.resolve("reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        workflows = new AcmgBusinessWorkflows(base.service, null);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
        base.request.put("topN", 1).put("declarationVersion", AcmgBusinessRuleScope.UNIFIED_LOCAL_DECLARATION);
        var options = workflows.inputOptions(base.analysisId, 1, "CURRENT");
        var selections = base.request.putArray("recommendations");
        var hypotheses = base.request.putArray("hypotheses");
        for (var row : options.path("candidates")) {
            var rec = row.path("recommendation");
            JsonNode relation = null, annotation = null;
            for (var r : rec.path("relationships")) if (r.path("selectable").asBoolean()) { relation = r; break; }
            for (var a : rec.path("annotations")) if (a.path("contextSelectable").asBoolean()) { annotation = a; break; }
            assertNotNull(relation, rec.toPrettyString()); assertNotNull(annotation, rec.toPrettyString());
            var choice = selections.addObject();
            for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) choice.set(key, rec.path(key));
            choice.set("stableRelationshipKey", relation.path("stableRelationshipKey"));
            choice.set("annotationId", annotation.path("annotationId"));
            var source = base.mapper.createObjectNode().put("source", "EXPLICIT_USER_DECLARATION").put("version", "1.0.0")
                    .put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var item = hypotheses.addObject().put("affectedStatus", "UNKNOWN");
            var h = item.putObject("hypothesis"); h.set("candidateId", row.path("candidateId")); h.set("hypothesisId", rec.path("hypothesisId"));
            for (String key : List.of("hgncId", "mondoId", "moi")) h.set(key, relation.path(key));
            h.set("provenance", source);
            String tx = annotation.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
            var t = item.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                    .put("selectionReason", "SYNTHETIC explicit context for normal formal source replay");
            t.set("annotationSource", annotation.path("annotationSource")); t.set("annotationSourceVersion", annotation.path("annotationSourceVersion")); t.set("source", source);
        }
        assertEquals(1, hypotheses.size());
    }

    @ParameterizedTest @ValueSource(strings = {"positive", "negative", "missing"})
    void formalRawSourcesKeepUnknownContributionInNormalBusinessAndSavedRead(String mode) throws Exception {
        String pythonSetting = System.getProperty("varvis.pp1.python", "");
        Assumptions.assumeFalse(pythonSetting.isBlank(), "Explicit existing Python required; skipped is not a source pass");
        Path python = Path.of(pythonSetting);
        Path scripts = Path.of("../scripts/denovo").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required; skipped is not a source pass");
        fixture = new Bs1KnowledgeBusinessTest(); fixture.temporary = temporary; fixture.setup();
        base = fixture.local.fixture.base; population = base.service;
        Path project = temporary.resolve("project");
        System.out.println("PP1_FORMAL_SYNTHETIC_PROJECT=" + project);
        Files.createDirectories(project.resolve("local")); Files.createDirectories(project.resolve("scripts/denovo"));
        Files.createDirectories(temporary.resolve("reviews"));
        Files.writeString(project.resolve("scripts/denovo/bundle_replay_profiles.json"), "{}");
        Files.writeString(project.resolve("local/model.json"), "{}");
        workflow = project.resolve("local/workflow.json");
        Files.write(workflow, base.mapper.writeValueAsBytes(base.mapper.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1")
                .put("enabled", true).put("projectRoot", project.toString()).put("work", "local/work").put("library", "local/library")
                .put("modelConfig", "local/model.json").put("python", "local/python.cmd")));
        install();
        var h = base.request.at("/hypotheses/0/hypothesis");
        var prepared = base.service.preparePp1Literature(base.analysisId, base.mapper.writeValueAsBytes(base.request),
                h.path("candidateId").asText(), h.path("hypothesisId").asText());
        Path binding = temporary.resolve("binding.json"); Files.write(binding, base.mapper.writeValueAsBytes(prepared.binding()));
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B",
                scripts.resolve("test_pp1_formal_business_fixture.py").toString(), project.toString(), binding.toString(), mode, python.toString()), scripts, Duration.ofSeconds(60));
        assertTrue(process.isSuccess(), process.getOutput());
        var source = (ObjectNode) base.mapper.readTree(Files.readAllBytes(project.resolve("local/fixture.json")));
        assertTrue(source.path("noNetworkOrModel").asBoolean());
        assertEquals(0, source.at("/expected/modelCalls").asInt(-1));
        assertEquals(0, source.at("/expected/networkRequests").asInt(-1));
        install();
        var rechecked = base.service.preparePp1Literature(base.analysisId, base.mapper.writeValueAsBytes(base.request),
                h.path("candidateId").asText(), h.path("hypothesisId").asText());
        assertEquals(prepared.binding(), rechecked.binding());
        Pp1FormalTaskFixture.register(workflow, base.analysisId, rechecked.binding(), source);
        base.request.putArray("selectedCriteria").add("ACMG_AMP_2015:PP1");
        ObjectNode saved = base.postRequest(base.request);
        assertEquals("VERIFIED_SAME_CONTEXT_ACROSS_CRITERIA_V1",
                saved.at("/executionProfile/moduleBindings/pp1/sourceReuseCapability").asText(),
                "Fresh requests bind the repaired Java source-reuse capability without changing saved scientific schemas");
        JsonNode evaluation = formalEvaluation(saved);
        assertEquals("pp1-segregation-evaluation-v2", evaluation.path("schemaVersion").asText(), saved.toPrettyString());
        assertEquals("VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1", evaluation.path("method").asText());
        assertSameScience(source.at("/expected/evaluation"), evaluation);
        for (String field : List.of("geneSymbol", "diseaseId", "inheritance", "allele"))
            assertEquals(prepared.binding().at("/selection/" + field), evaluation.at("/target/" + field));
        assertEquals("UNKNOWN", evaluation.at("/ledger/status").asText());
        assertTrue(evaluation.at("/ledger/pp4Points").isNull());
        assertEquals("NOT_EVALUATED", evaluation.path("status").asText()); assertFalse(evaluation.path("needs").isEmpty());
        assertEquals(0, evaluation.path("awardedPP1Points").asInt());
        if (mode.equals("missing")) {
            assertTrue(evaluation.toString().contains("PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED"));
        } else {
            assertTrue(evaluation.toString().contains("PP1_PP4_LEDGER_UNKNOWN"));
        }
        long evaluated = pythonEvaluations(project); assertEquals(1, evaluated);
        assertEquals(saved, base.postRequest(base.request));
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        byte[] export = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(saved, base.mapper.readTree(export));
        assertEquals(evaluated, pythonEvaluations(project), "History and exact-repeat must not reexecute Python");
        var allRequest = base.request.deepCopy(); allRequest.remove("selectedCriteria");
        var all = base.postRequest(allRequest);
        assertTrue(all.path("enabledCriteria").size() > 1);
        assertSameScience(evaluation, formalEvaluation(all));
        assertEquals(prepared.binding().path("ledger"), formalEvaluation(all).path("ledger"));
        assertEquals(evaluated + 1, pythonEvaluations(project));
        try (var runs = Files.list(project.resolve("local/work/pp1-evaluations"))) {
            for (Path run : runs.toList()) {
                var actualReplay = base.mapper.readTree(Files.readAllBytes(run.resolve("evaluation/result.json")));
                assertEquals(0, actualReplay.path("modelCalls").asInt(-1));
                assertEquals(0, actualReplay.path("networkRequests").asInt(-1));
                assertEquals(1, actualReplay.path("evaluatorCalls").asInt(-1));
            }
        }
        assertEquals(all, base.postRequest(allRequest)); assertEquals(evaluated + 1, pythonEvaluations(project));
        Path readback = temporary.resolve("separate-jvm");
        for (var entry : List.of(java.util.Map.entry("single", saved), java.util.Map.entry("all", all))) {
            var stored = base.store.run(base.analysisId, entry.getValue().path("runId").asText()).orElseThrow();
            Path folder = readback.resolve(entry.getKey()); Files.createDirectories(folder);
            Files.writeString(folder.resolve("expected-public.json"), stored.responseJson());
            Files.writeString(folder.resolve("expected-private.json"), stored.privateJson());
        }
        var declaration = base.request.deepCopy();
        base.closeDatabase();
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var child = new SystemExternalProcessRunner().run(List.of(executable, "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Pp1FormalHistoryReader.class.getName(),
                temporary.toString(), base.analysisId, saved.path("runId").asText(), all.path("runId").asText(), readback.toString()),
                temporary, Duration.ofSeconds(30));
        assertTrue(child.isSuccess(), child.getOutput());
        assertEquals(saved, base.mapper.readTree(Files.readAllBytes(readback.resolve("single/full-export.json"))));
        assertEquals(all, base.mapper.readTree(Files.readAllBytes(readback.resolve("all/full-export.json"))));
        assertEquals(evaluated + 1, pythonEvaluations(project));
        base.recreateRepositories(); fixture.local.fixture.wire(); fixture.wire(); population = base.service; install(); base.request = declaration;
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        assertEquals(saved, base.postRequest(base.request));
        assertEquals(all, base.postRequest(allRequest)); assertEquals(evaluated + 1, pythonEvaluations(project));
        String output = System.getProperty("varvis.pp1.formal.output", "");
        if (!output.isBlank()) {
            Path folder = Path.of(output).resolve(mode); Files.createDirectories(folder);
            Files.write(folder.resolve("saved.json"), base.mapper.writeValueAsBytes(saved));
            Files.write(folder.resolve("all.json"), base.mapper.writeValueAsBytes(all));
            Files.write(folder.resolve("separate-jvm.json"), base.mapper.writeValueAsBytes(base.mapper.createObjectNode()
                    .put("passed", true).put("publicAndPrivateBytesUnchanged", true).put("actualPythonEvaluations", evaluated + 1)));
            Files.write(folder.resolve("source-fixture.json"), base.mapper.writeValueAsBytes(source));
        }
    }
}
