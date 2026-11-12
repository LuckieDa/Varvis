package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.controller.PublicLiteratureController;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Normal CURRENT18/PM3 queue/Python/capture/store paths, never a substituted Source or worker.
 * Ranking, reference, source and seeded cache transport are explicit SYNTHETIC TEST ONLY.
 * The source omits partner/phase/disease/rarity facts and must remain unscored. */
class T02Pm3LiteratureBusinessTest {
    public static final class Directory implements org.junit.jupiter.api.io.TempDirFactory {
        @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
                org.junit.jupiter.api.extension.ExtensionContext context) throws Exception {
            Path workingCheckout = Path.of("").toAbsolutePath().normalize().getParent();
            String configuredCheckout = System.getProperty("varvis.t02.testCheckout");
            Path checkout = configuredCheckout == null ? workingCheckout : Path.of(configuredCheckout).toAbsolutePath().normalize();
            if (checkout == null || !Files.isRegularFile(checkout.resolve("AGENTS.md"))) throw new IllegalStateException("T02_CHECKOUT_REQUIRED");
            Path root = Files.createDirectories(checkout.resolve("local/temp")).toRealPath();
            if (!root.startsWith(checkout.toRealPath())) throw new IllegalStateException("T02_TEMP_OUTSIDE_CHECKOUT");
            for (int i = 0; i < 100; i++) {
                try { return Files.createDirectory(root.resolve(java.util.UUID.randomUUID().toString().substring(0, 6))).toRealPath(); }
                catch (java.nio.file.FileAlreadyExistsException collision) { /* Only this disposable label is short. */ }
            }
            throw new IllegalStateException("T02_TEMP_COLLISION_LIMIT");
        }
    }
    @TempDir(factory = Directory.class, cleanup = CleanupMode.ON_SUCCESS) Path temporary;
    private Bs1KnowledgeBusinessTest populationFixture;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessService population;
    private AcmgBusinessWorkflows workflows;
    private PublicLiteratureController controller;
    private Pm3LiteratureRaritySupply.Factory populationRarity;
    private Pvs1UnifiedEighteenFixture pvs1;
    private UnifiedEighteenCompanionFixtures companions;
    private Path python, scripts, workflow;
    private String candidateId, selectionPin;

    @AfterEach void close() {
        if (controller != null) controller.close();
        if (populationFixture != null) populationFixture.close();
    }

    private void prepare() throws Exception {
        String setting = System.getProperty("varvis.pp1.python", System.getProperty("denovo.test.python", ""));
        Assumptions.assumeFalse(setting.isBlank(), "Existing Python must be explicit; skipped is not validation");
        python = Path.of(setting); Assumptions.assumeTrue(Files.isRegularFile(python));
        scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        populationFixture = new Bs1KnowledgeBusinessTest(); populationFixture.temporary = temporary; populationFixture.setup();
        base = populationFixture.local.fixture.base; population = base.service;
        pvs1 = new Pvs1UnifiedEighteenFixture(base.mapper, temporary.resolve("pvs1"), "NMD");
        companions = new UnifiedEighteenCompanionFixtures(base.mapper, temporary.resolve("companions"), pvs1);
        bootstrap();
        Path options = temporary.resolve("cold-options.json");
        Files.write(options, base.mapper.writeValueAsBytes(population.inputOptions(base.analysisId, 1)));
        seed("COLD", options); install();
        controller = new PublicLiteratureController(workflows, workflow.toString(),populationRarity);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows), controller).build();
        var response = base.http.perform(get("/variant-analyses/{id}/acmg/literature/pm3/options", base.analysisId).param("topN", "1"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var source = base.mapper.readTree(response.getContentAsByteArray());
        selectionPin = source.path("selectionSha256").asText(); candidateId = source.at("/selection/candidates/0/candidateId").asText();
        assertTrue(selectionPin.matches("[a-f0-9]{64}")); assertFalse(candidateId.isBlank());
    }

    private void bootstrap() throws Exception {
        Path prepared = companions.prepareEmptyLiteratureWorkflow(scripts, python).getParent().getParent();
        // Production files only; no scientific fixtures or source observations are copied.
        try (var paths = Files.walk(prepared.resolve("scripts"))) {
            for (Path from : paths.filter(Files::isRegularFile).toList()) {
                Path to = temporary.resolve("scripts").resolve(prepared.resolve("scripts").relativize(from));
                Files.createDirectories(to.getParent()); Files.copy(from, to);
            }
        }
        for (String relative : List.of("ps1_pm5/prepare_reference_evidence.py", "ps1_pm5/prepare_expert_reference_evidence.py", "ps1_pm5/pp2_relationship_material.py",
                "pvs1/prepare_applicability_sources.py", "pvs1/assemble_materials.py", "acmg-generalization/import_cspec_catalog.py")) {
            Path to = temporary.resolve("scripts").resolve(relative); Files.createDirectories(to.getParent());
            Files.copy(scripts.getParent().resolve(relative), to);
        }
        Files.copy(scripts.resolve("core_review_replay_profiles.json"),temporary.resolve("scripts/denovo/core_review_replay_profiles.json"));
        Path dictionary = temporary.resolve("docs/design/pp1/field-dictionary.json"); Files.createDirectories(dictionary.getParent());
        Files.copy(prepared.resolve("docs/design/pp1/field-dictionary.json"), dictionary);
        Path local = Files.createDirectories(temporary.resolve("local"));
        Files.copy(prepared.resolve("local/python.cmd"), local.resolve("python.cmd"));
        var config = base.mapper.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1")
                .put("enabled", true).put("projectRoot", temporary.toString()).put("work", "local/w")
                .put("library", "local/library").put("modelConfig", "local/model.json").put("modelBudget", "local/budget")
                .put("searchCache", "local/search").put("python", "local/python.cmd")
                .put("allowModel", false).put("allowPublicNetwork", false).put("pm3NeedDrivenReading", true)
                .put("pm3MaximumReadingRounds", 4);
        workflow = local.resolve("workflow.json"); Files.write(workflow, base.mapper.writeValueAsBytes(config));
    }

    private void install() throws Exception {
        var c = companions;
        var populationSettings = populationFixture.local.fixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString());
        populationSettings.getGnomad().setBa1LocalExomesEnabled(true);
        populationSettings.getGnomad().setGrch38Files(String.join(",", List.of("11", "13").stream()
                .map(contig -> temporary.resolve("gnomad.exomes.v4.1.sites.chr" + contig + ".vcf.bgz").toString()).toList()));
        var local = new VariantAnalysisProperties();
        local.getClinVar().setEnabled(true); local.getClinVar().setGrch38File(temporary.resolve("missing-clinvar.vcf.gz").toString());
        local.getSpliceAi().setEnabled(true); local.getSpliceAi().setGrch38File(temporary.resolve("missing-spliceai.vcf.gz").toString());
        local.getAlphaMissense().setEnabled(true); local.getAlphaMissense().setGrch38File(temporary.resolve("missing-alphamissense.tsv.gz").toString());
        populationRarity = new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity() { return populationFixture.local.fixture.ba1.identity(); }
            public Pm2LocalExomesRuntime create() { return new Pm2LocalExomesRuntime(base.mapper, populationSettings); }
        };
        var batch = AcmgBusinessService.localBatchWithEffectIdentity(population, (effects, pin) -> {
            try {
                return new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("dataClass", "SYNTHETIC_TEST_ONLY")
                        .put("purpose", "T02_CURRENT18_REAL_PM3_WORKER"), c.pm2Factory(populationSettings), c.spliceFactory(local),
                        c.predictionFactory(local, effects), c.reference(local), c.bp7(), c::pm4, pvs1::selectedBatch, c::bp3,
                        c::pm1, () -> c.pp2(false), () -> c.bp1(effects, pin), false, c.deNovo(), c.pm3(workflow, populationRarity), c.pp1(workflow));
            } catch (Exception invalid) { throw new IllegalArgumentException("T02_PM3_COMPANION_CONFIGURATION_FAILED", invalid); }
        }, temporary.resolve("reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, batch); workflows = new AcmgBusinessWorkflows(base.service, batch);
        var options = workflows.inputOptions(base.analysisId, 1, "CURRENT");
        assertEquals(18, options.path("enabledCriteria").size());
        base.request.put("topN", 1).put("declarationVersion", options.path("requiredDeclaration").asText());
        var row = options.at("/candidates/0"); var rec = row.path("recommendation"); JsonNode relation = null, annotation = null;
        for (var value : rec.path("relationships")) if (value.path("selectable").asBoolean() && "AR".equals(value.path("moi").asText())) { relation = value; break; }
        for (var value : rec.path("annotations")) if (value.path("contextSelectable").asBoolean()) { annotation = value; break; }
        assertNotNull(relation, rec.toPrettyString()); assertNotNull(annotation, rec.toPrettyString());
        var choice = base.request.putArray("recommendations").addObject();
        for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) choice.set(key, rec.path(key));
        choice.set("stableRelationshipKey", relation.path("stableRelationshipKey")); choice.set("annotationId", annotation.path("annotationId"));
        var source = base.mapper.createObjectNode().put("source", "EXPLICIT_USER_DECLARATION").put("version", "1.0.0")
                .put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
        var hypothesis = base.request.putArray("hypotheses").addObject().put("affectedStatus", "UNKNOWN"); var h = hypothesis.putObject("hypothesis");
        h.set("candidateId", row.path("candidateId")); h.set("hypothesisId", rec.path("hypothesisId")); h.set("provenance", source);
        for (String key : List.of("hgncId", "mondoId", "moi")) h.set(key, relation.path(key));
        String tx = annotation.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
        var t = hypothesis.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                .put("selectionReason", "SYNTHETIC explicit CURRENT source-chain test; missing biological facts stay missing");
        t.set("annotationSource", annotation.path("annotationSource")); t.set("annotationSourceVersion", annotation.path("annotationSourceVersion")); t.set("source", source);
    }

    private JsonNode seed(String phase, Path input) throws Exception {
        var child = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B",
                scripts.resolve("test_t02_pm3_java_fixture.py").toString(), temporary.toString(), input.toString(), phase), scripts, Duration.ofSeconds(60));
        assertTrue(child.isSuccess(), child.getOutput());
        return base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/t02-pm3-fixture.json")));
    }

    private ObjectNode postJson(String path, ObjectNode body) throws Exception {
        var response = base.http.perform(post(java.util.Objects.requireNonNull(path)).contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(body)))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getErrorMessage() + " " + response.getContentAsString());
        return (ObjectNode) base.mapper.readTree(response.getContentAsByteArray());
    }
    private ObjectNode task(String id) throws Exception {
        var response = base.http.perform(get("/variant-analyses/{id}/acmg/literature/pm3/tasks/{task}", base.analysisId, id)).andReturn().getResponse();
        assertEquals(200, response.getStatus()); return (ObjectNode) base.mapper.readTree(response.getContentAsByteArray());
    }
    private ObjectNode await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var result = task(id);
            if (!Set.of("QUEUED", "RUNNING").contains(result.path("status").asText())) {
                assertEquals("READY", result.path("status").asText(), result.toPrettyString()); return result;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Actual PM3 Python task did not finish within the test bound");
    }
    private Path taskFolder(String id) { return temporary.resolve("local/w/pm3-paper-tasks").resolve(AcmgBusinessStore.sha(base.analysisId)).resolve(id); }
    private JsonNode pm3(ObjectNode saved) {
        for (var row : saved.at("/candidates/0/attempts/0/localBatch/criteria")) if (Pm3SelectedBatch.CRITERION.equals(row.path("criterionId").asText())) return row;
        throw new AssertionError("Actual PM3 row missing: " + saved.toPrettyString());
    }
    private String queries() throws Exception { Path p = temporary.resolve("queries.txt"); return Files.exists(p) ? Files.readString(p) : ""; }

    @Test void normalSourceTaskContinuesThenCapturesAndSavesBiologicalMissingFactsAcrossSelections() throws Exception {
        prepare(); var source = base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/t02-pm3-fixture.json")));
        var request = base.mapper.createObjectNode().put("topN", 1).put("candidateId", candidateId).put("selectionSha256", selectionPin)
                .put("pmcid", source.path("pmcid").asText()).put("bundleSha256", source.path("bundleSha256").asText()).put("confirmPublicModel", true);
        String endpoint = "/variant-analyses/" + base.analysisId + "/acmg/literature/pm3/tasks";
        String parentId = postJson(endpoint, request).path("taskId").asText(); var parent = await(parentId);
        assertEquals("PARTIAL", parent.at("/result/status").asText(), parent.toPrettyString());
        assertTrue(parent.path("canContinueProcessing").asBoolean(), parent.toPrettyString());
        assertEquals(0, parent.at("/result/modelCalls").asInt(-1));
        byte[] parentRaw = Files.readAllBytes(taskFolder(parentId).resolve("result.json"));
        byte[] parentIdentity = Files.readAllBytes(taskFolder(parentId).resolve("identity.json"));
        assertEquals(parentId, postJson(endpoint, request).path("taskId").asText());
        String workflowPin = AcmgBusinessStore.sha(Files.readString(workflow));
        var warm = seed("WARM", taskFolder(parentId).resolve("request.json"));
        assertEquals(source.path("bundleSha256"), warm.path("bundleSha256")); assertTrue(warm.path("syntheticTransportCalls").asInt() > 0);
        assertEquals(0, warm.path("actualProviderCalls").asInt()); assertEquals(0, warm.path("networkRequests").asInt());
        assertEquals(workflowPin, AcmgBusinessStore.sha(Files.readString(workflow)));
        var continuation = base.mapper.createObjectNode().put("topN", 1).put("selectionSha256", selectionPin).put("confirmPublicModel", true);
        String childId = postJson(endpoint + "/" + parentId + "/continue", continuation).path("taskId").asText(); assertNotEquals(parentId, childId);
        var child = await(childId); assertEquals(0, child.at("/result/modelCalls").asInt(-1));
        assertEquals(0, child.at("/result/pendingReviewChunks").size());
        assertEquals(warm.path("chunkCount").asInt(), child.at("/result/completedReviewChunks").size());
        assertFalse(child.path("canContinueProcessing").asBoolean(), child.toPrettyString());
        assertEquals(childId, postJson(endpoint + "/" + parentId + "/continue", continuation).path("taskId").asText());
        assertArrayEquals(parentRaw, Files.readAllBytes(taskFolder(parentId).resolve("result.json")));
        assertArrayEquals(parentIdentity, Files.readAllBytes(taskFolder(parentId).resolve("identity.json")));
        byte[] budget = Files.readAllBytes(temporary.resolve("local/budget/budget.json"));
        var results = new LinkedHashMap<String, ObjectNode>(); JsonNode priorScience = null;
        var formal = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        for (String selection : List.of("single", "related", "all")) {
            var declaration = base.request.deepCopy(); declaration.remove("selectedCriteria");
            if (!selection.equals("all")) {
                var ids = declaration.putArray("selectedCriteria").add(Pm3SelectedBatch.CRITERION);
                if (selection.equals("related")) ids.add("ACMG_AMP_2015:BP7");
            }
            var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).param("workflow", "CURRENT")
                    .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(declaration)))).andReturn().getResponse();
            assertEquals(200, response.getStatus(), response.getErrorMessage() + " " + response.getContentAsString());
            var saved = (ObjectNode) base.mapper.readTree(response.getContentAsByteArray()); results.put(selection, saved);
            assertNotEquals("FAILED", saved.path("status").asText()); assertEquals(selection.equals("all") ? 18 : selection.equals("related") ? 2 : 1, saved.path("enabledCriteria").size());
            var criterion = pm3(saved); assertEquals("NOT_EVALUATED", criterion.path("status").asText(), criterion.toPrettyString());
            assertEquals("NONE", criterion.path("appliedStrength").asText());
            var science = base.mapper.createObjectNode();
            for (String field : List.of("status", "appliedStrength", "reasonCode")) science.set(field, criterion.path(field));
            if (priorScience == null) priorScience = science; else assertEquals(priorScience, science);
            var references = criterion.at("/details/pm3Facts/sourceTaskReferences"); assertFalse(references.isEmpty(), criterion.toPrettyString());
            assertTrue(references.toString().contains(parentId)); assertTrue(references.toString().contains(childId));
            var facts=criterion.at("/details/pm3Facts");assertEquals(3,facts.path("taskCoverage").size());
            var automaticTasks=java.util.stream.StreamSupport.stream(facts.path("taskCoverage").spliterator(),false)
                    .map(row->row.path("taskId").asText()).filter(taskId->!Set.of(parentId,childId).contains(taskId)).toList();
            assertEquals(1,automaticTasks.size());
            assertTrue(base.mapper.readTree(Files.readAllBytes(taskFolder(automaticTasks.get(0)).resolve("identity.json")))
                    .path("localFirstPass").asBoolean());
            var covered=new LinkedHashMap<String,JsonNode>();
            for(var item:facts.path("paperCoverage"))covered.put(item.path("taskId").asText(),item);
            assertTrue(covered.keySet().containsAll(Set.of(parentId,childId)));
            for(var item:List.of(covered.get(parentId),covered.get(childId))) {
                assertEquals("literature-stage-public-v1",item.path("projectionVersion").asText());
                assertEquals(source.path("pmcid"),item.path("pmcid"));assertEquals(source.path("bundleSha256"),item.path("bundleSha256"));
                assertEquals("AVAILABLE",item.at("/processingCheckpoint/projection/status").asText(),item.toPrettyString());
                assertEquals(item.path("bundleSha256"),item.at("/processingCheckpoint/bundleSha256"));
                assertTrue(item.path("capturedResultSha256").asText().matches("[a-f0-9]{64}"));
                assertFalse(item.at("/processingCheckpoint/savedScoreAdopted").asBoolean(true));
            }
            assertEquals(parent.path("resultSha256"),covered.get(parentId).path("capturedResultSha256"));
            assertEquals(child.path("resultSha256"),covered.get(childId).path("capturedResultSha256"));
            assertEquals(warm.path("chunkCount").asInt()*2,covered.get(childId).at("/processingCheckpoint/stages").size());

            var stored = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
            var privateData = base.mapper.readTree(Objects.requireNonNull(stored.privateJson()));
            assertNotNull(privateData.findValue("processingCheckpoint"), "Actual captured source checkpoint must survive private persistence");
            String populationQueries = queries();
            assertEquals(saved, base.postRequest(declaration)); assertEquals(saved, base.service.read(base.analysisId, stored.id()));
            var full = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, stored.id())).andReturn().getResponse();
            assertEquals(200, full.getStatus()); assertEquals(saved, base.mapper.readTree(full.getContentAsByteArray()));
            assertEquals(stored, base.store.run(base.analysisId, stored.id()).orElseThrow()); assertEquals(populationQueries, queries());
            assertArrayEquals(budget, Files.readAllBytes(temporary.resolve("local/budget/budget.json")));
            assertEquals(formal, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)));
            assertEquals(parent, task(parentId)); assertEquals(child, task(childId));
        }
        roundTrip(results, source, warm, parent, child);
    }

    @Test void acquisitionFailureWithoutPaperRemainsPublicAndDurableAtCapturedTaskScope() throws Exception {
        prepare();
        var config=(ObjectNode)base.mapper.readTree(Files.readAllBytes(workflow));
        Files.createDirectories(temporary.resolve("local/acquisition-library"));
        config.put("library","local/acquisition-library");Files.write(workflow,base.mapper.writeValueAsBytes(config));
        // The source configuration changed before this independent scenario;
        // obtain its current recommendations instead of submitting stale ones.
        controller.close();install();controller=new PublicLiteratureController(workflows,workflow.toString(),populationRarity);
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows),controller).build();
        var refreshed=base.http.perform(get("/variant-analyses/{id}/acmg/literature/pm3/options",base.analysisId).param("topN","1"))
                .andReturn().getResponse();assertEquals(200,refreshed.getStatus());
        selectionPin=base.mapper.readTree(refreshed.getContentAsByteArray()).path("selectionSha256").asText();
        var owned=(Pm3LiteratureJobs)org.springframework.test.util.ReflectionTestUtils.getField(java.util.Objects.requireNonNull(controller),"pm3");assertNotNull(owned);
        var actual=(ExternalProcessRunner)org.springframework.test.util.ReflectionTestUtils.getField(owned,"runner");assertNotNull(actual);
        var workerCalls=new java.util.concurrent.atomic.AtomicInteger();
        ExternalProcessRunner syntheticTransports=(command,cwd,timeout)->{
            workerCalls.incrementAndGet();var wrapped=new java.util.ArrayList<>(command);
            int script=wrapped.indexOf(temporary.resolve("scripts/denovo/run_pm3_paper_task.py").toString());
            assertTrue(script>=0,wrapped.toString());wrapped.set(script,scripts.resolve("test_t02_pm3_acquisition_worker.py").toString());
            return actual.run(wrapped,cwd,timeout);
        };
        org.springframework.test.util.ReflectionTestUtils.setField(owned,"runner",syntheticTransports);
        String endpoint="/variant-analyses/"+base.analysisId+"/acmg/literature/pm3/tasks";
        var request=base.mapper.createObjectNode().put("topN",1).put("candidateId",candidateId).put("selectionSha256",selectionPin).put("confirmPublicModel",true);
        String id=postJson(endpoint,request).path("taskId").asText();var source=await(id);
        assertEquals(1,workerCalls.get());assertEquals(0,source.at("/result/papers").size(),source.toPrettyString());
        assertEquals(1,source.at("/result/acquisitionRecovery").size());
        assertEquals("PAPER_DOWNLOAD_TIMEOUT",source.at("/result/acquisitionRecovery/0/reasonCode").asText());
        assertTrue(source.path("canContinueProcessing").asBoolean());
        assertFalse(Files.exists(temporary.resolve("local/budget/budget.json")));
        var transport=base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/acquisition-transport-receipt.json")));
        assertEquals(1,transport.path("syntheticDownloadAttempts").asInt());assertEquals(0,transport.path("externalNetworkRequests").asInt());
        var results=new LinkedHashMap<String,ObjectNode>();
        for(String selection:List.of("single","related","all")) {
            var declaration=base.request.deepCopy();declaration.remove("selectedCriteria");
            if(!"all".equals(selection)) {
                var ids=declaration.putArray("selectedCriteria").add(Pm3SelectedBatch.CRITERION);
                if("related".equals(selection))ids.add("ACMG_AMP_2015:BP7");
            }
            var response=base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).param("workflow","CURRENT")
                    .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(declaration)))).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getErrorMessage()+" "+response.getContentAsString());
            var saved=(ObjectNode)base.mapper.readTree(response.getContentAsByteArray());results.put(selection,saved);
            var criterion=pm3(saved);assertEquals("NOT_EVALUATED",criterion.path("status").asText());
            var facts=criterion.at("/details/pm3Facts");assertTrue(facts.path("paperCoverage").isEmpty());
            assertEquals(2,facts.path("taskCoverage").size(),facts.toPrettyString());
            var task=java.util.stream.StreamSupport.stream(facts.path("taskCoverage").spliterator(),false)
                    .filter(row->id.equals(row.path("taskId").asText())).findFirst().orElseThrow();
            assertEquals(source.path("resultSha256"),task.path("capturedResultSha256"));
            assertEquals("PAPER_DOWNLOAD_TIMEOUT",task.at("/acquisitionRecovery/0/reasonCode").asText());
            assertFalse(task.at("/acquisitionRecovery/0/sourceAbsenceProven").asBoolean(true));
            assertFalse(task.path("historyActionsAllowed").asBoolean(true));assertFalse(task.has("canContinueProcessing"));
            assertEquals(2,facts.path("sourceTaskReferences").size());
            assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()));
            assertEquals(saved,base.postRequest(declaration));assertEquals(1,workerCalls.get());assertEquals(source,task(id));
        }
        roundTrip(results,transport,transport,source,null);
    }

    @org.junit.jupiter.params.ParameterizedTest(name="unreconciled HTTP {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings={"IO","TIMEOUT","EXIT"})
    void normalHttpRejectsUnreconciledContinuationAndInjectedOriginsWithoutNewWorker(String failure) throws Exception {
        prepare();
        var config=(ObjectNode)base.mapper.readTree(Files.readAllBytes(workflow));
        config.put("allowModel",true);Files.write(workflow,base.mapper.writeValueAsBytes(config));
        var refreshed=base.http.perform(get("/variant-analyses/{id}/acmg/literature/pm3/options",base.analysisId).param("topN","1"))
                .andReturn().getResponse();assertEquals(200,refreshed.getStatus());
        selectionPin=base.mapper.readTree(refreshed.getContentAsByteArray()).path("selectionSha256").asText();
        var owned=(Pm3LiteratureJobs)org.springframework.test.util.ReflectionTestUtils.getField(java.util.Objects.requireNonNull(controller),"pm3");
        assertNotNull(owned);
        var actual=(ExternalProcessRunner)org.springframework.test.util.ReflectionTestUtils.getField(owned,"runner");
        assertNotNull(actual);
        var workerCalls=new java.util.concurrent.atomic.AtomicInteger();
        ExternalProcessRunner interrupted=(command,cwd,timeout)->{
            if(workerCalls.getAndIncrement()==0)return actual.run(command,cwd,timeout);
            if("IO".equals(failure))throw new java.io.IOException("SYNTHETIC_TEST_ONLY worker outcome unavailable");
            return new ProcessResult(-1,"SYNTHETIC_TEST_ONLY process failed","TIMEOUT".equals(failure));
        };
        // Only the external process boundary is fault-injected. Controller,
        // immutable queue, source first round and all action gates are real.
        org.springframework.test.util.ReflectionTestUtils.setField(owned,"runner",interrupted);
        var source=base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/t02-pm3-fixture.json")));
        var request=base.mapper.createObjectNode().put("topN",1).put("candidateId",candidateId).put("selectionSha256",selectionPin)
                .put("pmcid",source.path("pmcid").asText()).put("bundleSha256",source.path("bundleSha256").asText()).put("confirmPublicModel",true);
        String endpoint="/variant-analyses/"+base.analysisId+"/acmg/literature/pm3/tasks";
        String id=postJson(endpoint,request).path("taskId").asText();var before=await(id);
        assertEquals(2,workerCalls.get(),before.toPrettyString());
        assertFalse(before.path("canContinueProcessing").asBoolean());assertTrue(before.path("sharedBudgetReconciliationRequired").asBoolean());
        assertEquals("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED",before.path("processingContinuationBlockedReason").asText());
        assertEquals("CHECK_SHARED_LEDGER",before.at("/result/readingLoop/failedAttemptAccounting").asText());
        assertEquals(0,before.at("/result/modelCalls").asInt(-1));
        assertTrue(before.at("/result/readingCachePass").asBoolean());
        assertFalse(Files.exists(temporary.resolve("local/budget/budget.json")),"Only cache replay preceded the injected unknown worker outcome");
        var frozen=new LinkedHashMap<String,byte[]>();
        try(var paths=Files.walk(temporary.resolve("local"))) {
            for(Path file:paths.filter(Files::isRegularFile).toList())frozen.put(temporary.resolve("local").relativize(file).toString(),Files.readAllBytes(file));
        }
        var injected=request.deepCopy();injected.putObject("processingContinuation").put("parentTaskId",id)
                .put("parentResultSha256",before.path("resultSha256").asText());
        var rejectedStart=base.http.perform(post(endpoint).contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(injected))))
                .andReturn().getResponse();assertEquals(400,rejectedStart.getStatus());
        var continuation=base.mapper.createObjectNode().put("topN",1).put("selectionSha256",selectionPin).put("confirmPublicModel",true);
        var blocked=base.http.perform(post(endpoint+"/"+id+"/continue").contentType("application/json")
                .content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(continuation)))).andReturn().getResponse();
        assertEquals(409,blocked.getStatus(),blocked.getContentAsString());
        assertEquals("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED",base.mapper.readTree(blocked.getContentAsByteArray()).path("message").asText());
        String mapping="HTTP_409_SAFE_BLOCK";
        // An exact retry reads the same blocked task; it does not start a worker.
        assertEquals(id,postJson(endpoint,request).path("taskId").asText());
        assertEquals(before,task(id));assertEquals(1,owned.list(base.analysisId).size());assertEquals(2,workerCalls.get());
        var now=new LinkedHashMap<String,byte[]>();
        try(var paths=Files.walk(temporary.resolve("local"))) {
            for(Path file:paths.filter(Files::isRegularFile).toList())now.put(temporary.resolve("local").relativize(file).toString(),Files.readAllBytes(file));
        }
        assertEquals(frozen.keySet(),now.keySet());
        for(var item:frozen.entrySet())assertArrayEquals(item.getValue(),now.get(item.getKey()),item.getKey());
        String destination=System.getProperty("t02.http.recovery.output","");
        if(!destination.isBlank()) {
            Path output=Files.createDirectories(Path.of(destination).resolve(failure));
            var receipt=base.mapper.createObjectNode().put("failureKind",failure).put("taskId",id).put("workerCalls",workerCalls.get())
                    .put("externalModelCalls",0).put("actualInitialPythonWorker",true).put("startHttpStatus",400)
                    .put("continueMapping",mapping).put("taskAndOriginalBytesUnchanged",true).put("newTaskCount",0);
            Files.write(output.resolve("http-receipt.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
            Files.write(output.resolve("actual-blocked-task.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(before));
        }
    }

    private void roundTrip(Map<String, ObjectNode> results, JsonNode sourceFixture, JsonNode warmFixture, JsonNode parent, JsonNode sourceChild) throws Exception {
        Path out = Files.createDirectories(temporary.resolve("reopen")); var source = base.store.source(base.analysisId).orElseThrow();
        var manifest = base.mapper.createObjectNode().put("expectedSourceSha256", source.sha256()); var runs = manifest.putArray("runs");
        var artifact = base.mapper.createObjectNode().put("sourceClass", "SYNTHETIC_T02_PM3_REAL_TASK_CURRENT18")
                .put("actualProviderCalls", 0).put("networkRequests", 0).put("clinicalApproval", "NONE");
        artifact.set("coldSourceFixture", sourceFixture); artifact.set("warmSourceFixture", warmFixture); var publicResults = artifact.putObject("publicResults");
        for (var entry : results.entrySet()) {
            var saved = base.store.run(base.analysisId, entry.getValue().path("runId").asText()).orElseThrow();
            Path folder = Files.createDirectories(out.resolve(entry.getKey())); runs.addObject().put("name", entry.getKey()).put("runId", saved.id());
            Files.writeString(folder.resolve("expected-public.json"), Objects.requireNonNull(saved.responseJson()));
            Files.writeString(folder.resolve("expected-private.json"), Objects.requireNonNull(saved.privateJson()));
            Files.writeString(folder.resolve("expected-request.json"), Objects.requireNonNull(saved.requestJson()));
            Files.writeString(folder.resolve("expected-source.json"), source.json()); publicResults.set(entry.getKey(), entry.getValue());
        }
        Files.write(out.resolve("manifest.json"), base.mapper.writeValueAsBytes(manifest)); base.closeDatabase();
        String javaExe = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
        var child = new SystemExternalProcessRunner().run(List.of(javaExe, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DeNovoUnifiedEighteenReopenReader.class.getName(), temporary.toString(), base.analysisId, out.toString()), temporary, Duration.ofSeconds(60));
        assertTrue(child.isSuccess(), child.getOutput());
        var receipt = base.mapper.readTree(Files.readAllBytes(out.resolve("unified18-reopen-receipt.json"))); assertTrue(receipt.path("passed").asBoolean());
        String destination = System.getProperty("t02.pm3.business.output", "");
        if (!destination.isBlank()) {
            Path root = Files.createDirectories(sourceChild==null?Path.of(destination).resolve("acquisition-only"):Path.of(destination));
            Files.write(root.resolve("actual-t02-pm3-current18.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(artifact));
            Files.write(root.resolve("parent-task.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(parent));
            if(sourceChild!=null)Files.write(root.resolve("child-task.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sourceChild));
            else Files.write(root.resolve("discovery-task.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(parent));
            try(var files=Files.walk(temporary.resolve("local"))) {
                for(Path from:files.filter(Files::isRegularFile).toList()) {
                    Path to=root.resolve("isolated-source-local").resolve(temporary.resolve("local").relativize(from));Files.createDirectories(to.getParent());
                    Files.copy(from,to,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try (var files = Files.walk(out)) {
                for (Path from : files.filter(Files::isRegularFile).toList()) {
                    Path to = root.resolve("another-jvm").resolve(out.relativize(from)); Files.createDirectories(to.getParent());
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
