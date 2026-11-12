package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.controller.PublicLiteratureController;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real CURRENT18/controller/worker/source capture and saved-result path.
 * Source observations, reference, adoption, upstream and cache-seeding transport are SYNTHETIC;
 * no source/evaluator/worker is replaced and an UNKNOWN contribution ledger stays UNKNOWN. */
class T02Pp1LiteratureBusinessTest {
    /** Complete source pins stay intact; only this test's disposable directory label is short. */
    public static final class T02Directory implements org.junit.jupiter.api.io.TempDirFactory {
        @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
                org.junit.jupiter.api.extension.ExtensionContext context) throws Exception {
            Path workingCheckout = Path.of("").toAbsolutePath().normalize().getParent();
            String configuredCheckout = System.getProperty("varvis.t02.testCheckout");
            Path checkout = configuredCheckout == null ? workingCheckout : Path.of(configuredCheckout).toAbsolutePath().normalize();
            if (checkout == null || !Files.isRegularFile(checkout.resolve("AGENTS.md")))
                throw new IllegalStateException("T02_CHECKOUT_REQUIRED");
            Path root = Files.createDirectories(checkout.resolve("local/temp")).toRealPath();
            if (!root.startsWith(checkout.toRealPath())) throw new IllegalStateException("T02_TEMP_OUTSIDE_CHECKOUT");
            for (int attempt = 0; attempt < 100; attempt++) {
                Path directory = root.resolve(java.util.UUID.randomUUID().toString().substring(0, 6));
                try {
                    Path actual = Files.createDirectory(directory).toRealPath();
                    if (!actual.startsWith(root)) throw new IllegalStateException("T02_TEMP_LINK_OUTSIDE_CHECKOUT");
                    System.out.println("T02_LOCAL_TEMP=" + actual); return actual;
                } catch (java.nio.file.FileAlreadyExistsException collision) { /* Try another disposable label. */ }
            }
            throw new IllegalStateException("T02_TEMP_ID_COLLISION_LIMIT");
        }
    }
    @TempDir(factory = T02Directory.class, cleanup = CleanupMode.ON_SUCCESS) Path temporary;
    private DeNovoUnifiedEighteenFixture fixture;
    private PublicLiteratureController controller;
    private Path python, scripts, project, workflow, targetFile, bindingFile;

    @AfterEach void close() {
        if (controller != null) controller.close();
        if (fixture != null) fixture.close();
    }

    private void prepare() throws Exception {
        String setting = System.getProperty("varvis.pp1.python", System.getProperty("denovo.test.python", ""));
        Assumptions.assumeFalse(setting.isBlank(), "Existing Python must be explicit; skip is not validation");
        python = Path.of(setting); Assumptions.assumeTrue(Files.isRegularFile(python));
        scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        fixture = new DeNovoUnifiedEighteenFixture(temporary, python, scripts);
        assertEquals(18, fixture.current.configuredCriteria().size());
        // Registered cache paths contain complete 64-character pins. A short isolated
        // project keeps their native Windows paths usable without changing any source ID.
        project = temporary;
        workflow = project.resolve("local/workflow.json");
        bootstrapProject();
        var h = fixture.base.request.at("/hypotheses/0/hypothesis");
        var prepared = fixture.current.preparePp1Literature(fixture.base.analysisId,
                fixture.base.mapper.writeValueAsBytes(fixture.base.request), h.path("candidateId").asText(), h.path("hypothesisId").asText());
        assertEquals("UNKNOWN", prepared.binding().at("/ledger/status").asText());
        assertTrue(prepared.binding().at("/ledger/pp4Points").isNull());
        targetFile = temporary.resolve("pp1-target.json"); bindingFile = temporary.resolve("pp1-binding.json");
        Files.write(targetFile, fixture.base.mapper.writeValueAsBytes(prepared.target()));
        Files.write(bindingFile, fixture.base.mapper.writeValueAsBytes(prepared.binding()));
        seed("EXTRACT_ONLY");
        install();
        var confirmed = fixture.current.preparePp1Literature(fixture.base.analysisId,
                fixture.base.mapper.writeValueAsBytes(fixture.base.request), h.path("candidateId").asText(), h.path("hypothesisId").asText());
        assertEquals(prepared.binding(), confirmed.binding(), "Material configuration must not replace the selected clinical context");
        assertEquals(prepared.target(), confirmed.target());
        controller = new PublicLiteratureController(fixture.workflows, workflow.toString());
        fixture.base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(fixture.workflows), controller).build();
    }

    private void bootstrapProject() throws Exception {
        // Reuse the accepted companion's isolated production code/dictionary only.
        // Its empty model placeholder is deliberately not copied over the fixture's
        // exact synthetic transport model. No source, knowledge, ledger or score is copied.
        Path prepared = fixture.literatureWorkflow.getParent().getParent();
        for (String directory : List.of("scripts", "docs")) {
            Path fromRoot = prepared.resolve(directory);
            try (var paths = Files.walk(fromRoot)) {
                for (Path from : paths.filter(Files::isRegularFile).toList()) {
                    Path target = project.resolve(directory).resolve(fromRoot.relativize(from));
                    Files.createDirectories(target.getParent()); Files.copy(from, target);
                }
            }
        }
        Path local = Files.createDirectories(project.resolve("local"));
        Files.copy(prepared.resolve("local/python.cmd"), local.resolve("python.cmd"));
        var configuration = fixture.base.mapper.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1")
                .put("enabled", true).put("projectRoot", project.toString()).put("work", "local/w")
                .put("library", "local/library").put("modelConfig", "local/model.json").put("modelBudget", "local/b")
                .put("searchCache", "local/search").put("python", "local/python.cmd")
                .put("allowModel", false).put("allowPublicNetwork", false);
        Files.write(workflow, fixture.base.mapper.writeValueAsBytes(configuration));
    }

    private void install() throws Exception {
        var base = fixture.base; var c = fixture.companions;
        var population = fixture.populationFixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString());
        population.getGnomad().setBa1LocalExomesEnabled(true);
        population.getGnomad().setGrch38Files(String.join(",", List.of("11", "13").stream()
                .map(contig -> temporary.resolve("gnomad.exomes.v4.1.sites.chr" + contig + ".vcf.bgz").toString()).toList()));
        var local = new VariantAnalysisProperties();
        local.getClinVar().setEnabled(true); local.getClinVar().setGrch38File(temporary.resolve("missing-clinvar.vcf.gz").toString());
        local.getSpliceAi().setEnabled(true); local.getSpliceAi().setGrch38File(temporary.resolve("missing-spliceai.vcf.gz").toString());
        local.getAlphaMissense().setEnabled(true); local.getAlphaMissense().setGrch38File(temporary.resolve("missing-alphamissense.tsv.gz").toString());
        var rarity = new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity() { return fixture.populationFixture.ba1.identity(); }
            public Pm2LocalExomesRuntime create() { return new Pm2LocalExomesRuntime(base.mapper, population); }
        };
        var batch = AcmgBusinessService.localBatchWithEffectIdentity(fixture.population, (effects, pin) -> {
            try {
                return new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("dataClass", "SYNTHETIC_TEST_ONLY")
                        .put("purpose", "T02_CURRENT18_REAL_PP1_WORKER"), c.pm2Factory(population), c.spliceFactory(local),
                        c.predictionFactory(local, effects), c.reference(local), c.bp7(), c::pm4, fixture.pvs1::selectedBatch,
                        c::bp3, c::pm1, () -> c.pp2(false), () -> c.bp1(effects, pin), false,
                        fixture.materials.load(), c.pm3(workflow, rarity), c.pp1(workflow));
            } catch (Exception invalid) { throw new IllegalArgumentException("T02_SYNTHETIC_COMPANION_CONFIGURATION_FAILED", invalid); }
        }, temporary.resolve("t02-reviews"));
        fixture.current = AcmgBusinessService.unifiedLocal(fixture.population, batch);
        fixture.workflows = new AcmgBusinessWorkflows(fixture.current, batch);
        base.service = fixture.current; fixture.confirmCurrent();
        assertEquals(18, fixture.current.configuredCriteria().size(), "Production composition computes the full profile");
    }

    private JsonNode seed(String phase) throws Exception {
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B",
                scripts.resolve("test_t02_pp1_java_fixture.py").toString(), project.toString(), targetFile.toString(),
                bindingFile.toString(), phase, python.toString()), scripts, Duration.ofSeconds(60));
        assertTrue(process.isSuccess(), process.getOutput());
        return fixture.base.mapper.readTree(Files.readAllBytes(project.resolve("local/t02-fixture.json")));
    }

    private ObjectNode taskRequest() throws Exception {
        var h = fixture.base.request.at("/hypotheses/0/hypothesis");
        var body = fixture.base.mapper.createObjectNode().put("candidateId", h.path("candidateId").asText())
                .put("hypothesisId", h.path("hypothesisId").asText()).put("confirmPublicModel", true);
        body.set("declaration", fixture.base.request.deepCopy()); return body;
    }

    private ObjectNode postTask(ObjectNode body) throws Exception {
        var response = fixture.base.http.perform(post("/variant-analyses/{id}/acmg/literature/pp1/tasks", fixture.base.analysisId)
                .contentType("application/json").content(java.util.Objects.requireNonNull(fixture.base.mapper.writeValueAsBytes(body)))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getErrorMessage() + " " + response.getContentAsString());
        return (ObjectNode) fixture.base.mapper.readTree(response.getContentAsByteArray());
    }

    private ObjectNode readTask(String id) throws Exception {
        var response = fixture.base.http.perform(get("/variant-analyses/{id}/acmg/literature/pp1/tasks/{task}",
                fixture.base.analysisId, id)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return (ObjectNode) fixture.base.mapper.readTree(response.getContentAsByteArray());
    }

    private ObjectNode await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var task = readTask(id);
            if (!Set.of("QUEUED", "RUNNING").contains(task.path("status").asText())) {
                assertEquals("READY", task.path("status").asText(), task.toPrettyString()); return task;
            }
            Thread.sleep(20);
        }
        fail("Real isolated PP1 worker did not finish within its test bound"); return fixture.base.mapper.createObjectNode();
    }

    private Path taskFolder(String id) {
        String analysis = AcmgBusinessStore.sha(fixture.base.analysisId);
        return project.resolve("local/w/pp1-paper-tasks").resolve(analysis).resolve(id);
    }

    private long directories(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) { return files.filter(Files::isDirectory).count(); }
    }

    private long tasks() throws Exception { return directories(taskFolder("unused").getParent()); }
    private long evaluations() throws Exception { return directories(project.resolve("local/w/pp1-evaluations")); }

    private ObjectNode submit(ObjectNode declaration) throws Exception {
        var response = fixture.base.http.perform(post("/variant-analyses/{id}/acmg/runs", fixture.base.analysisId)
                .param("workflow", "CURRENT").contentType("application/json")
                .content(java.util.Objects.requireNonNull(fixture.base.mapper.writeValueAsBytes(declaration)))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getErrorMessage() + " " + response.getContentAsString());
        return (ObjectNode) fixture.base.mapper.readTree(response.getContentAsByteArray());
    }

    private JsonNode pp1(ObjectNode result) {
        for (var row : result.at("/candidates/0/attempts/0/localBatch/criteria"))
            if (Pp1SelectedBatch.CRITERION.equals(row.path("criterionId").asText())) return row;
        throw new AssertionError("Actual PP1 row missing: " + result.toPrettyString());
    }

    private void sameScientificFacts(JsonNode expected, JsonNode actual) {
        var left = ((ObjectNode) expected).deepCopy(); var right = ((ObjectNode) actual).deepCopy();
        left.remove(List.of("target", "ledger")); right.remove(List.of("target", "ledger")); assertEquals(left, right);
    }

    @Test void normalPaperTaskContinuesAuditThenCapturesAndSavesUnknownAcrossSelections() throws Exception {
        prepare(); var initial = fixture.base.mapper.readTree(Files.readAllBytes(project.resolve("local/t02-fixture.json")));
        var parentRequest = taskRequest().put("pmcid", initial.path("pmcid").asText())
                .put("bundleSha256", initial.path("bundleSha256").asText());
        String parentId = postTask(parentRequest).path("taskId").asText(); var parent = await(parentId);
        assertEquals("PARTIAL", parent.at("/result/status").asText(), parent.toPrettyString());
        assertTrue(parent.path("canContinueProcessing").asBoolean(), parent.toPrettyString());
        assertEquals(0, parent.at("/result/modelAttempts").asInt(-1));
        byte[] parentRaw = Files.readAllBytes(taskFolder(parentId).resolve("result.json"));
        byte[] parentIdentity = Files.readAllBytes(taskFolder(parentId).resolve("identity.json"));
        assertEquals(parent, await(postTask(parentRequest).path("taskId").asText())); assertEquals(1, tasks());
        String workflowPin = AcmgBusinessStore.sha(Files.readString(workflow));
        var completed = seed("COMPLETE");
        assertEquals(initial.path("pmcid"), completed.path("pmcid")); assertEquals(initial.path("bundleSha256"), completed.path("bundleSha256"));
        assertEquals(workflowPin, AcmgBusinessStore.sha(Files.readString(workflow)), "Warm only real synthetic source caches, never change task configuration");
        var continuationRequest = taskRequest().put("parentTaskId", parentId);
        String childId = postTask(continuationRequest).path("taskId").asText(); assertNotEquals(parentId, childId);
        var child = await(childId); assertEquals("EVALUATED", child.at("/result/status").asText(), child.toPrettyString());
        assertEquals(0, child.at("/result/modelAttempts").asInt(-1));
        assertEquals("UNKNOWN", child.at("/result/evaluation/ledger/status").asText());
        assertTrue(child.at("/result/evaluation/ledger/pp4Points").isNull());
        assertEquals("NOT_EVALUATED", child.at("/result/evaluation/status").asText());
        var childIdentity = fixture.base.mapper.readTree(Files.readAllBytes(taskFolder(childId).resolve("identity.json")));
        assertEquals(parentId, childIdentity.at("/request/processingContinuation/parentTaskId").asText());
        assertEquals(AcmgBusinessStore.sha(new String(parentRaw, StandardCharsets.UTF_8)), childIdentity.at("/request/processingContinuation/parentResultSha256").asText());
        Path manifest = project.resolve("local/w/pp1-artifacts").resolve(childId).resolve("paper/artifacts.json");
        var accepted = fixture.base.mapper.readTree(Files.readAllBytes(manifest));
        assertFalse(accepted.path("acceptedArtifacts").isEmpty(), "Actual continuation must retain a replayable source review");
        var acceptedPins = new LinkedHashMap<Path, String>();
        for (var path : accepted.path("acceptedArtifacts")) {
            Path artifact = Path.of(path.asText()); assertTrue(Files.isRegularFile(artifact));
            acceptedPins.put(artifact, AcmgBusinessStore.sha(Files.readString(artifact)));
        }
        assertEquals(childId, postTask(continuationRequest).path("taskId").asText()); assertEquals(2, tasks());
        assertArrayEquals(parentRaw, Files.readAllBytes(taskFolder(parentId).resolve("result.json")));
        assertArrayEquals(parentIdentity, Files.readAllBytes(taskFolder(parentId).resolve("identity.json")));
        var beforeBad = tasks(); var bad = taskRequest().put("parentTaskId", parentId).put("hypothesisId", "wrong-background");
        var refused = fixture.base.http.perform(post("/variant-analyses/{id}/acmg/literature/pp1/tasks", fixture.base.analysisId)
                .contentType("application/json").content(java.util.Objects.requireNonNull(fixture.base.mapper.writeValueAsBytes(bad)))).andReturn().getResponse();
        assertEquals(409, refused.getStatus()); assertEquals(beforeBad, tasks());

        var results = new LinkedHashMap<String, ObjectNode>(); JsonNode scientific = null;
        var formal = fixture.base.mapper.valueToTree(fixture.base.jobs.findInterpretations(fixture.base.analysisId, 0, 10));
        for (String selection : List.of("single", "related", "all")) {
            var request = fixture.base.request.deepCopy(); request.remove("selectedCriteria");
            if (!selection.equals("all")) {
                var ids = request.putArray("selectedCriteria"); ids.add(Pp1SelectedBatch.CRITERION);
                if (selection.equals("related")) ids.add("ACMG_AMP_2015:BP7");
            }
            long before = evaluations(); var saved = submit(request); results.put(selection, saved);
            assertNotEquals("FAILED", saved.path("status").asText()); assertEquals(before + 1, evaluations());
            assertEquals(selection.equals("single") ? 1 : selection.equals("related") ? 2 : 18, saved.path("enabledCriteria").size());
            var wanted = new LinkedHashSet<String>();
            for (var id : saved.path("enabledCriteria")) if (!Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1").contains(id.asText())) wanted.add(id.asText());
            var actual = new LinkedHashSet<String>(); for (var row : saved.at("/candidates/0/attempts/0/localBatch/criteria")) actual.add(row.path("criterionId").asText());
            assertEquals(wanted, actual, "No unselected evidence rows");
            var row = pp1(saved); var facts = row.at("/details/pp1Facts"); var evaluation = facts.path("evaluation");
            assertEquals("NOT_EVALUATED", row.path("status").asText(), row.toPrettyString()); assertEquals("NONE", row.path("appliedStrength").asText());
            assertEquals("UNKNOWN", evaluation.at("/ledger/status").asText()); assertTrue(evaluation.at("/ledger/pp4Points").isNull());
            assertEquals(0, evaluation.path("awardedPP1Points").asInt()); assertFalse(evaluation.path("needs").isEmpty());
            assertFalse(facts.path("qualifications").isEmpty(), "Current PP1 replay must retain qualified source material even when ledger unknown");
            var coverageByTask=new LinkedHashMap<String,JsonNode>();
            for(var item:facts.path("sourceCoverage"))coverageByTask.put(item.path("taskId").asText(),item);
            assertEquals(Set.of(parentId,childId),coverageByTask.keySet());
            for(var item:coverageByTask.values()) {
                assertEquals("literature-stage-public-v1",item.path("projectionVersion").asText());
                assertTrue(item.path("capturedResultSha256").asText().matches("[a-f0-9]{64}"));
                assertEquals(initial.path("pmcid"),item.path("pmcid"));assertEquals(initial.path("bundleSha256"),item.path("bundleSha256"));
                assertEquals("AVAILABLE",item.at("/readingState/projection/status").asText(),item.toPrettyString());
                assertEquals(item.path("bundleSha256"),item.at("/readingState/required/registeredSourceBundle"));
                assertEquals(item.path("bundleSha256"),item.at("/readingState/checkpoint/bundleSha256"));
                assertFalse(item.at("/readingState/wholePaperAbsenceClaim").asBoolean(true));
                assertNoRawStageData(item.path("readingState"));
            }
            assertEquals(1,coverageByTask.get(parentId).at("/readingState/stageGaps").size());
            assertEquals("SYSTEM_STAGE",coverageByTask.get(parentId).at("/readingState/stageGaps/0/kind").asText());
            assertEquals(0,coverageByTask.get(parentId).at("/readingState/available/acceptedReviewArtifacts").asInt(-1));
            assertEquals(0,coverageByTask.get(childId).at("/readingState/stageGaps").size());
            assertEquals(1,coverageByTask.get(childId).at("/readingState/available/acceptedReviewArtifacts").asInt(-1));

            if (scientific == null) scientific = evaluation; else sameScientificFacts(scientific, evaluation);
            var record = fixture.base.store.run(fixture.base.analysisId, saved.path("runId").asText()).orElseThrow();
            JsonNode privateData = fixture.base.mapper.readTree(Objects.requireNonNull(record.privateJson()));
            assertFalse(privateData.at("/localBatch/modules/pp1").isMissingNode());
            assertEquals(saved, submit(request)); assertEquals(saved, fixture.base.service.read(fixture.base.analysisId, record.id()));
            var export = fixture.base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", fixture.base.analysisId, record.id())).andReturn().getResponse();
            assertEquals(200, export.getStatus()); assertEquals(saved, fixture.base.mapper.readTree(export.getContentAsByteArray()));
            assertEquals(before + 1, evaluations()); assertEquals(2, tasks()); assertEquals(record, fixture.base.store.run(fixture.base.analysisId, record.id()).orElseThrow());
            assertEquals(formal, fixture.base.mapper.valueToTree(fixture.base.jobs.findInterpretations(fixture.base.analysisId, 0, 10)));
        }
        assertEquals(3, evaluations());
        for (var pin : acceptedPins.entrySet()) assertEquals(pin.getValue(), AcmgBusinessStore.sha(Files.readString(pin.getKey())));
        assertEquals(parent, readTask(parentId)); assertEquals(child, readTask(childId));
        roundTrip(results, parent, child);
    }

    @Test void normalHttpDiscoveryUsesCurrentAuthorityAndStopsAfterThreeSourceBoundAttempts() throws Exception {
        prepare();var base=fixture.base;
        var config=(ObjectNode)base.mapper.readTree(Files.readAllBytes(workflow));
        Files.createDirectories(project.resolve("local/discovery-library"));
        config.put("library","local/discovery-library");Files.write(workflow,base.mapper.writeValueAsBytes(config));
        controller.close();install();controller=new PublicLiteratureController(fixture.workflows,workflow.toString());
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(fixture.workflows),controller).build();
        var jobs=(Pp1LiteratureJobs)org.springframework.test.util.ReflectionTestUtils.getField(java.util.Objects.requireNonNull(controller),"pp1");assertNotNull(jobs);
        var runner=(ExternalProcessRunner)org.springframework.test.util.ReflectionTestUtils.getField(jobs,"runner");assertNotNull(runner);
        var workerCalls=new java.util.concurrent.atomic.AtomicInteger();
        ExternalProcessRunner syntheticTransports=(command,cwd,timeout)->{
            workerCalls.incrementAndGet();var wrapped=new java.util.ArrayList<>(command);
            int script=wrapped.indexOf(project.resolve("scripts/denovo/run_pp1_paper_task.py").toString());assertTrue(script>=0,wrapped.toString());
            wrapped.set(script,scripts.resolve("test_t02_pm3_acquisition_worker.py").toString());return runner.run(wrapped,cwd,timeout);
        };
        org.springframework.test.util.ReflectionTestUtils.setField(jobs,"runner",syntheticTransports);
        var request=taskRequest();String id=postTask(request).path("taskId").asText();
        var originals=new LinkedHashMap<String,byte[]>();var actualResponses=base.mapper.createArrayNode();
        for(int attempt=1;attempt<=3;attempt++) {
            var current=await(id);actualResponses.add(current);
            assertEquals(attempt,workerCalls.get());assertEquals(attempt,current.path("discoveryAttempt").asInt());
            assertEquals(3,current.path("maximumDiscoveryAttempts").asInt());
            assertEquals(attempt<3,current.path("canContinueDiscovery").asBoolean(),current.toPrettyString());
            assertFalse(current.path("canContinueProcessing").asBoolean());assertFalse(current.path("sharedBudgetReconciliationRequired").asBoolean());
            assertTrue(current.at("/result/discoveryState/resumeAvailable").asBoolean());
            String parent=id;byte[] prior=Files.readAllBytes(taskFolder(parent).resolve("result.json"));originals.put(parent,prior);
            var wrong=taskRequest().put("parentTaskId",parent).put("hypothesisId","wrong-background");
            var rejected=base.http.perform(post("/variant-analyses/{id}/acmg/literature/pp1/tasks",base.analysisId)
                    .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(wrong)))).andReturn().getResponse();
            assertEquals(409,rejected.getStatus());assertEquals(attempt,workerCalls.get());
            if(attempt<3) {
                var continuation=taskRequest().put("parentTaskId",parent);
                String child=postTask(continuation).path("taskId").asText();assertNotEquals(parent,child);await(child);
                assertEquals(child,postTask(continuation).path("taskId").asText());id=child;
            } else {
                var refused=base.http.perform(post("/variant-analyses/{id}/acmg/literature/pp1/tasks",base.analysisId)
                        .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(taskRequest().put("parentTaskId",parent)))))
                        .andReturn().getResponse();assertEquals(409,refused.getStatus());
                assertEquals("PP1_DISCOVERY_CONTINUATION_LIMIT",base.mapper.readTree(refused.getContentAsByteArray()).path("message").asText());
                assertEquals("PP1_DISCOVERY_CONTINUATION_LIMIT",current.path("discoveryContinuationBlockedReason").asText());
            }
            for(var entry:originals.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(taskFolder(entry.getKey()).resolve("result.json")));
        }
        assertEquals(3,workerCalls.get());assertEquals(3,tasks());
        String destination=System.getProperty("varvis.t02.pp1.output","");
        if(!destination.isBlank()) {
            Path output=Files.createDirectories(Path.of(destination).resolve("discovery-http"));
            Files.write(output.resolve("actual-task-responses.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(actualResponses));
            Files.write(output.resolve("transport-receipt.json"),Files.readAllBytes(project.resolve("local/acquisition-transport-receipt.json")));
            var receipt=base.mapper.createObjectNode().put("actualController",true).put("actualPythonDiscoveryWorker",true)
                    .put("externalNetworkCalls",0).put("externalModelCalls",0).put("workerCalls",3).put("maxAttempts",3)
                    .put("wrongBackgroundHttpStatus",409).put("generationLimitHttpStatus",409).put("immutableParentBytes",true);
            Files.write(output.resolve("http-receipt.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
            for(var entry:originals.entrySet()) {
                Path target=Files.createDirectories(output.resolve("tasks").resolve(entry.getKey()));
                for(String file:List.of("identity.json","request.json","result.json"))Files.copy(taskFolder(entry.getKey()).resolve(file),target.resolve(file));
                Path paper=project.resolve("local/w/pp1-artifacts").resolve(entry.getKey()).resolve("paper");
                for(String file:List.of("discovery-state.json","artifacts.json"))Files.copy(paper.resolve(file),target.resolve(file));
            }
        }
    }

    private static void assertNoRawStageData(JsonNode value) {
        if(value.isObject()) {
            assertFalse(value.has("quote"));assertFalse(value.has("artifactPath"));
            assertFalse(value.has("parentPath"));assertFalse(value.has("acceptedArtifacts"));
            if(value.has("value")) {
                assertEquals("CAPTURED_SOURCE_VALUE_NOT_CURRENT_ADOPTION",value.path("interpretation").asText());
                assertTrue(Set.of("ENUM","BOOLEAN","INTEGER","OPAQUE_MEMBER_ID","SOURCE_ACCESSION","HGVS_LITERAL")
                        .contains(value.path("valueType").asText()),value.toPrettyString());
                var observed=value.path("value");assertTrue(observed.isValueNode());
                if("BOOLEAN".equals(value.path("valueType").asText()))assertTrue(observed.isBoolean());
                else if("INTEGER".equals(value.path("valueType").asText()))assertTrue(observed.isIntegralNumber() && observed.asLong()>=0);
                else {assertTrue(observed.isTextual());assertTrue(observed.asText().length()<=300);}
            }
            value.elements().forEachRemaining(T02Pp1LiteratureBusinessTest::assertNoRawStageData);
        } else if(value.isArray())value.elements().forEachRemaining(T02Pp1LiteratureBusinessTest::assertNoRawStageData);
        else if(value.isTextual()) {
            assertFalse(value.asText().contains("D:\\"));assertFalse(value.asText().contains("C:\\"));
        }
    }

    private void roundTrip(Map<String, ObjectNode> results, ObjectNode parent, ObjectNode child) throws Exception {
        var base = fixture.base; Path output = Files.createDirectories(temporary.resolve("t02-reopen"));
        var source = base.store.source(base.analysisId).orElseThrow(); var manifest = base.mapper.createObjectNode().put("expectedSourceSha256", source.sha256());
        var runs = manifest.putArray("runs");
        for (var entry : results.entrySet()) {
            var record = base.store.run(base.analysisId, entry.getValue().path("runId").asText()).orElseThrow();
            runs.addObject().put("name", entry.getKey()).put("runId", record.id()); Path folder = Files.createDirectories(output.resolve(entry.getKey()));
            Files.writeString(folder.resolve("expected-public.json"), Objects.requireNonNull(record.responseJson()));
            Files.writeString(folder.resolve("expected-private.json"), Objects.requireNonNull(record.privateJson()));
            Files.writeString(folder.resolve("expected-request.json"), Objects.requireNonNull(record.requestJson()));
            Files.writeString(folder.resolve("expected-source.json"), source.json());
        }
        Files.write(output.resolve("manifest.json"), base.mapper.writeValueAsBytes(manifest));
        long before = evaluations(); base.closeDatabase();
        String javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var process = new SystemExternalProcessRunner().run(List.of(javaExe, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DeNovoUnifiedEighteenReopenReader.class.getName(), temporary.toString(), base.analysisId, output.toString()), temporary, Duration.ofSeconds(60));
        assertTrue(process.isSuccess(), process.getOutput()); assertEquals(before, evaluations());
        for (var entry : results.entrySet()) assertEquals(entry.getValue(), base.mapper.readTree(Files.readAllBytes(output.resolve(entry.getKey()).resolve("controller-full-export.json"))));
        String archive = System.getProperty("varvis.t02.pp1.output", "");
        if (!archive.isBlank()) {
            Path to = Files.createDirectories(Path.of(archive));
            Files.write(to.resolve("parent-task.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(parent));
            Files.write(to.resolve("child-task.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(child));
            Files.write(to.resolve("source-fixture.json"), Files.readAllBytes(project.resolve("local/t02-fixture.json")));
            // Preserve actual synthetic originals, requests, receipts, immutable tasks,
            // and PP1 replay outputs before the successful temporary directory is cleaned.
            try (var paths = Files.walk(project.resolve("local"))) {
                for (Path from : paths.filter(Files::isRegularFile).toList()) {
                    Path target = to.resolve("isolated-source-local").resolve(project.resolve("local").relativize(from));
                    Files.createDirectories(target.getParent()); Files.copy(from, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try (var paths = Files.walk(output)) {
                for (Path from : paths.filter(Files::isRegularFile).toList()) {
                    Path target = to.resolve("another-jvm").resolve(output.relativize(from)); Files.createDirectories(target.getParent());
                    Files.copy(from, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
