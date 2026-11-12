package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;

/** Real source publication, request, SQL save and HTTP read; all clinical facts/policy and upstream execution are synthetic. */
class DeNovoBusinessIntegrationTest {
    @TempDir Path temporary;
    private AcmgBusinessIntegrationTest base;
    private DeNovoBatchFixture materials;
    private final List<DeNovoSelectedBatch> instances = new ArrayList<>();
    private final List<DeNovoLiteratureReader> readers = new ArrayList<>();
    private boolean literature;
    private boolean modelReplay;
    private boolean autoSupply;
    private boolean asyncSupply;
    private boolean failSourceOnce;
    private final java.util.concurrent.atomic.AtomicInteger sourceAttempts=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.CountDownLatch failSourceRelease=new java.util.concurrent.CountDownLatch(1);

    private void setup(String scenario) throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        base.variantTsvOverride = tsv("1\t11\t2\tT\tA\tCDH1\t999\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, null);
        materials = new DeNovoBatchFixture(temporary.resolve("materials"));
        if (scenario.equals("assumed")) materials.report.putArray("parentage");
        modelReplay = scenario.equals("model-replay");
        if (Set.of("missing", "literature", "auto-supply", "async-supply", "model-replay").contains(scenario)) ((ArrayNode) materials.report.path("tests")).remove(2);
        materials.save();
        asyncSupply = scenario.equals("async-supply");
        autoSupply = scenario.equals("auto-supply") || asyncSupply;
        literature = scenario.equals("literature") || autoSupply || modelReplay;
        if (literature) {
            Assumptions.assumeTrue(Files.isRegularFile(Path.of(System.getProperty("denovo.test.python", "missing-python"))));
            materials.addSyntheticLiterature();
            if (modelReplay) {
                var process = new SystemExternalProcessRunner().run(List.of(System.getProperty("denovo.test.python"), "-E", "-s", "-B", "-c",
                        "from test_model_literature import write_synthetic_fixture; import sys; write_synthetic_fixture(sys.argv[1])",
                        materials.root.toString()), Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")), java.time.Duration.ofSeconds(20));
                assertTrue(process.isSuccess(), process.getOutput());
            }
            if (autoSupply) {
                ((ObjectNode) materials.manifest.at("/literature/0")).putArray("alleles");
                materials.manifest.putArray("citationRecords").addObject().put("sourceId", "synthetic-literature-clinvar.xml").put("vcv", "VCV999999997");
                materials.writeManifest();
            }
        }
        base.request.put("declarationVersion", AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        ((ObjectNode) base.request.path("patient")).put("familyContextId", DeNovoBatchFixture.FAMILY);
        ((ObjectNode) base.request.at("/hypotheses/0/hypothesis")).put("hgncId", "HGNC:1748").put("mondoId", "MONDO:0007648").put("moi", "AD");
        install(); confirm();
    }
    private void install() {
        base.recreateService(base.runtime());
        base.service = AcmgBusinessService.localBatch(base.service, () -> {
            try {
                DeNovoSelectedBatch module;
                if (literature) {
                    var reader = new DeNovoLiteratureReader(base.mapper, Path.of(System.getProperty("denovo.test.python")),
                            Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")), temporary.resolve("literature-cache"));
                    if (modelReplay) {
                        Path index = materials.root.resolve("model-artifacts.json");
                        reader = new DeNovoLiteratureReader(base.mapper, Path.of(System.getProperty("denovo.test.python")),
                                Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")), temporary.resolve("literature-cache"),
                                index, DeNovoBatchFixture.sha(Files.readAllBytes(index)));
                    }
                    readers.add(reader); var manifest = materials.root.resolve("manifest.json");
                    var supply = autoSupply ? new DeNovoLiteratureSupply(base.mapper, Path.of(System.getProperty("denovo.test.python")),
                            Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")),temporary.resolve("supply-work"),
                            temporary.resolve("public-cache"),false) : null;
                    if (asyncSupply) supply=failSourceOnce ? DeNovoBatchFixture.failOnceSupply(base.mapper,
                            Path.of(System.getProperty("denovo.test.python")),Path.of(System.getProperty("denovo.test.scripts","../scripts/denovo")),
                            temporary.resolve("supply-work"),temporary.resolve("public-cache"),sourceAttempts,failSourceRelease) : requireNonNull(supply).asynchronous();
                    module = DeNovoSelectedBatch.load(base.mapper, manifest, DeNovoBatchFixture.sha(Files.readAllBytes(manifest)),
                            DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader, supply);
                } else module = materials.load();
                instances.add(module);
                return new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("fixture", "SYNTHETIC_DENOVO"),
                        null, null, null, null, null, null, null, null, null, null, null, false, module);
            } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
        });
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    private void confirm() {
        var options = base.service.inputOptions(base.analysisId, 2); var refs = base.request.putArray("recommendations");
        assertTrue(options.path("enabledCriteria").toString().contains("PS2"), options.toPrettyString());
        for (int i = 0; i < 2; i++) {
            var recommendation = options.at("/candidates/" + i + "/recommendation");
            var hypothesis = (ObjectNode) base.request.at("/hypotheses/" + i + "/hypothesis");
            hypothesis.set("hypothesisId", recommendation.path("hypothesisId")); JsonNode relation = null, annotation = null;
            for (var r : recommendation.path("relationships")) if (r.path("mondoId").equals(hypothesis.path("mondoId"))) relation = r;
            for (var a : recommendation.path("annotations")) if (a.path("annotationId").equals(recommendation.path("recommendedAnnotationId"))) annotation = a;
            assertNotNull(relation, recommendation.toPrettyString());
            if (annotation == null) {
                ((ObjectNode) base.request.at("/hypotheses/" + i)).remove("transcript");
                hypothesis.set("moi", relation.path("moi"));
                var ref = refs.addObject(); for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, recommendation.path(field));
                ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.putNull("annotationId"); continue;
            }
            hypothesis.set("moi", relation.path("moi")); var tx = (ObjectNode) base.request.at("/hypotheses/" + i + "/transcript");
            String accession = annotation.path("versionedTranscriptId").asText(); int dot = accession.lastIndexOf('.');
            tx.put("accession", accession.substring(0, dot)).put("version", Integer.parseInt(accession.substring(dot + 1)))
                    .put("selectionPolicy", recommendation.path("policyId").asText() + "@" + recommendation.path("policyVersion").asText());
            tx.set("annotationSource", annotation.path("annotationSource")); tx.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            ((ObjectNode) tx.path("source")).put("recordLocator", "recommendations/" + recommendation.path("recommendationId").asText() + "/selection");
            var ref = refs.addObject(); for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, recommendation.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", recommendation.path("recommendedAnnotationId"));
        }
    }
    @Test void explicitSourceRefreshRebindsRecommendationsWithoutScoringOrRewritingHistory() throws Exception {
        setup("confirmed");var workflows=new AcmgBusinessWorkflows(base.service,null);
        var before=base.service.inputOptions(base.analysisId,2);var saved=base.postRequest(base.request);int count=executions();
        byte[] raw="SYNTHETIC newly accepted public original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(materials.root.resolve("new-source.txt"),raw);
        ((ArrayNode)materials.manifest.path("sources")).addObject().put("sourceId","new-source").put("path","new-source.txt").put("sha256",DeNovoBatchFixture.sha(raw));
        materials.writeManifest();workflows.refreshCurrentPublicSources();
        assertEquals(count,executions());base.service=workflows.current();
        var after=workflows.inputOptions(base.analysisId,2,"CURRENT");
        assertNotEquals(before.at("/candidates/0/recommendation/policyHash"),after.at("/candidates/0/recommendation/policyHash"));
        assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()));
        confirm();var next=base.service.submit(base.analysisId,base.mapper.writeValueAsBytes(base.request));
        assertNotEquals(saved.path("runId"),next.path("runId"));assertEquals(count+1,executions());
        assertEquals(next,base.service.submit(base.analysisId,base.mapper.writeValueAsBytes(base.request)));assertEquals(count+1,executions());
    }
    @AfterEach void close() { if (base != null) base.closeDatabase(); }
    private int executions() { return instances.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum(); }

    @ParameterizedTest @ValueSource(strings={"confirmed", "assumed", "missing", "literature", "auto-supply", "model-replay"})
    void topTwoSaveReadRestartAndExactReuse(String scenario) throws Exception {
        setup(scenario);
        assertEquals(0,instances.stream().mapToInt(mappedItem -> mappedItem.sourceSupplyCalls()).sum());
        JsonNode ranking = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var result = base.postRequest(base.request); String run = result.path("runId").asText();
        var saved = base.store.run(base.analysisId, run).orElseThrow();
        var privateData = base.mapper.readTree(saved.privateJson());
        var batch = privateData.path("localBatch");
        assertTrue(batch.isObject(), privateData.toPrettyString());
        var actual = batch.at("/modules/deNovo/results/0/qualifiedResult/result");
        assertEquals(scenario.equals("missing") ? "NOT_EVALUATED" : "MET", actual.path("status").asText(), batch.toPrettyString());
        assertEquals(Set.of("confirmed", "literature", "auto-supply", "model-replay").contains(scenario) ? "PS2" : scenario.equals("assumed") ? "PM6" : "NONE", actual.path("code").asText());
        if (literature) assertTrue(actual.toString().contains("LITERATURE_CASE"));
        assertEquals(2, result.path("candidates").size()); assertEquals(1, executions());
        assertEquals(ranking, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)));
        assertTrue(batch.at("/modules/deNovo/results/1").toString().contains("DENOVO_INHERITANCE_NOT_SUPPORTED"));
        assertFalse(result.toString().contains("originalsByRawSha256")); assertFalse(result.toString().contains("SYNTHETIC target-specific result"));
        for (var fields = batch.at("/modules/deNovo/originalsByRawSha256").properties().iterator(); fields.hasNext();) {
            var field = fields.next(); assertEquals(field.getKey(), DeNovoBatchFixture.sha(Base64.getDecoder().decode(field.getValue().asText())));
        }
        int calls = base.invocations.get();
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN"); base.recreateRepositories(); install();
        var read = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(result, base.mapper.readTree(read)); assertEquals(result, base.postRequest(base.request));
        assertEquals(calls, base.invocations.get()); assertEquals(1, executions());
        assertEquals(saved.privateJson(), base.store.run(base.analysisId, run).orElseThrow().privateJson());
        if (literature) assertEquals(autoSupply ? 2 : 1, readers.stream().mapToInt(mappedItem -> mappedItem.executions()).sum());
        assertEquals(autoSupply ? 1 : 0,instances.stream().mapToInt(mappedItem -> mappedItem.sourceSupplyCalls()).sum());
        assertEquals(0,instances.stream().mapToInt(mappedItem -> mappedItem.publicNetworkRequests()).sum());
        if (autoSupply) {
            assertEquals(1,batch.at("/modules/deNovo/sourceSupply/targets").size());
            assertEquals(1,batch.at("/modules/deNovo/providerCalls").asInt());
            assertEquals(0,batch.at("/modules/deNovo/sourceSupply/modelCalls").asInt(-1));
        }
        String archive = System.getProperty("varvis.denovo.businessArtifacts");
        if (archive != null) {
            Path dir = Path.of(archive).resolve(scenario); Files.createDirectory(dir);
            Files.writeString(dir.resolve("response.json"), saved.responseJson(), StandardOpenOption.CREATE_NEW);
            Files.writeString(dir.resolve("private.json"), saved.privateJson(), StandardOpenOption.CREATE_NEW);
            Files.writeString(dir.resolve("after-reopen-private.json"), base.store.run(base.analysisId, run).orElseThrow().privateJson(), StandardOpenOption.CREATE_NEW);
            Files.write(dir.resolve("http-get.json"), read, StandardOpenOption.CREATE_NEW);
            Files.write(dir.resolve("request.json"), base.mapper.writeValueAsBytes(base.request), StandardOpenOption.CREATE_NEW);
        }
    }
    @Test void changedMaterialsCannotSilentlyReuseAnExistingRuntimeIdentity() throws Exception {
        setup("confirmed"); base.postRequest(base.request); int calls = executions();
        materials.report.put("reportVersion", "2"); materials.save();
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        assertEquals(calls, executions());
    }
    @ParameterizedTest @ValueSource(strings={"patient", "family", "unbound"})
    void currentReportCannotBeBorrowedByMatchingAlleleWithoutExactCaseBinding(String change) throws Exception {
        setup("confirmed");
        var patient = (ObjectNode) base.request.path("patient");
        if (change.equals("patient")) patient.put("patientContextId", "patient-context-different-case");
        else if (change.equals("family")) patient.put("familyContextId", "family-context-different-family");
        else patient.putNull("familyContextId");
        var response = base.postRequest(base.request);
        var saved = base.store.run(base.analysisId, response.path("runId").asText()).orElseThrow();
        var privateData = base.mapper.readTree(saved.privateJson());
        var batch = privateData.at("/localBatch/modules/deNovo/results/0");
        assertEquals("NOT_EVALUATED", batch.at("/publicSummary/status").asText(), batch.toString());
        assertTrue(batch.toString().contains(change.equals("unbound") ? "DENOVO_FAMILY_CONTEXT_REQUIRED"
                : "DENOVO_REPORT_PATIENT_OR_FAMILY_MISMATCH"), batch.toString());
        assertEquals(response, base.postRequest(base.request));
        assertEquals(change.equals("unbound") ? 0 : 1, executions());
    }
    @Test void changedSourceInvalidatesConfirmationBeforeCoreExecution() throws Exception {
        setup("confirmed"); base.request.put("topN", 1);
        ((ArrayNode) base.request.path("hypotheses")).remove(1); ((ArrayNode) base.request.path("recommendations")).remove(1);
        var snapshot = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        ((ObjectNode) snapshot.at("/candidates/0/matchedRecord")).put("recordOrdinal", 99); base.replaceSnapshot(snapshot);
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        assertEquals(0, executions()); assertTrue(base.store.runs(base.analysisId).isEmpty());
    }
    @Test void changedSourcePreventsAutomaticLiteratureSupply() throws Exception {
        setup("auto-supply");
        var snapshot=(ObjectNode)base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        ((ObjectNode)snapshot.at("/candidates/0/matchedRecord")).put("recordOrdinal",99);base.replaceSnapshot(snapshot);
        base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).contentType("application/json")
                .content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isConflict());
        assertEquals(0,instances.stream().mapToInt(mappedItem -> mappedItem.sourceSupplyCalls()).sum());assertEquals(0,executions());
    }
    @Test void missingFamilyFactDoesNotReplaceFailedTopOneWithAnotherCandidate() throws Exception {
        setup("missing"); base.request.put("topN", 1);
        ((ArrayNode) base.request.path("hypotheses")).remove(1); ((ArrayNode) base.request.path("recommendations")).remove(1);
        var result = base.postRequest(base.request);
        assertEquals(1, result.path("candidates").size()); assertEquals(1, executions());
        assertEquals(base.request.at("/hypotheses/0/hypothesis/candidateId"), result.at("/candidates/0/candidateId"));
        for (var c : result.at("/candidates/0/attempts/0/localBatch/criteria"))
            assertEquals("NOT_EVALUATED", c.path("status").asText());
    }
    @Test void asyncSourceStatusIsReadOnlyAndExplicitRetryUsesCompletedSourceWithoutChangingOldRun() throws Exception {
        setup("async-supply");
        var first=base.postRequest(base.request);String firstId=first.path("runId").asText();
        assertEquals("DENOVO_SUPPLY_PENDING",first.at("/localBatch/modules/deNovo/sourceTask/reasonCode").asText(),first.toString());
        var frozen=base.store.run(base.analysisId,firstId).orElseThrow();String oldJson=frozen.responseJson();
        var originalRequest=base.request.deepCopy();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        ObjectNode progress;
        do {
            progress=(ObjectNode)base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{runId}/denovo-source",base.analysisId,firstId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            if (!Set.of("QUEUED","RUNNING").contains(progress.path("status").asText())) break;
            Thread.sleep(25);
        } while (System.nanoTime()<deadline);
        assertEquals("READY",progress.path("status").asText(),progress.toString());
        assertFalse(progress.path("savedResultChanged").asBoolean(true));
        assertEquals(first,base.postRequest(originalRequest));assertEquals(oldJson,base.store.run(base.analysisId,firstId).orElseThrow().responseJson());
        base.request.put("retryOf",firstId);var second=base.postRequest(base.request);
        assertNotEquals(firstId,second.path("runId").asText());
        var privateData=base.mapper.readTree(base.store.run(base.analysisId,second.path("runId").asText()).orElseThrow().privateJson());
        assertEquals("MET",privateData.at("/localBatch/modules/deNovo/results/0/criteria/PS2/evaluationResult/status").asText(),privateData.toString());
        assertEquals(0,privateData.at("/localBatch/modules/deNovo/providerCalls").asInt(-1));
        assertEquals(0,privateData.at("/localBatch/modules/deNovo/publicNetworkRequests").asInt(-1));
        assertEquals(second,base.postRequest(base.request));
        try(var entries=Files.list(temporary.resolve("supply-work"))) {assertEquals(1,entries.filter(p->p.getFileName().toString().startsWith("selected-")).count());}
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN");base.recreateRepositories();install();
        assertEquals(first,base.service.read(base.analysisId,firstId));
        assertEquals("READY",base.service.deNovoSourceTaskStatus(base.analysisId,firstId).path("status").asText());
        assertEquals(second,base.postRequest(base.request));assertEquals(oldJson,base.store.run(base.analysisId,firstId).orElseThrow().responseJson());
        String archive=System.getProperty("varvis.denovo.businessArtifacts");
        if (archive!=null) {
            Path dir=Files.createDirectory(Path.of(archive).resolve("async-supply"));
            Files.writeString(dir.resolve("pending-response.json"),oldJson,StandardOpenOption.CREATE_NEW);
            Files.writeString(dir.resolve("pending-private.json"),frozen.privateJson(),StandardOpenOption.CREATE_NEW);
            Files.writeString(dir.resolve("pending-after-reopen.json"),base.store.run(base.analysisId,firstId).orElseThrow().responseJson(),StandardOpenOption.CREATE_NEW);
            var completed=base.store.run(base.analysisId,second.path("runId").asText()).orElseThrow();
            Files.writeString(dir.resolve("response.json"),completed.responseJson(),StandardOpenOption.CREATE_NEW);
            Files.writeString(dir.resolve("private.json"),completed.privateJson(),StandardOpenOption.CREATE_NEW);
            Files.write(dir.resolve("ready-status.json"),base.mapper.writeValueAsBytes(progress),StandardOpenOption.CREATE_NEW);
            Path work=temporary.resolve("supply-work");
            try(var paths=Files.walk(work)) {
                for(Path source:paths.filter(Files::isRegularFile).toList()) {
                    Path target=dir.resolve("source-work").resolve(work.relativize(source));Files.createDirectories(target.getParent());Files.copy(source,target);
                }
            }
        }
    }
    @Test void failedAsyncSourceRequiresExplicitRetryAndSuccessfulRetryNeverChangesOldHistory() throws Exception {
        failSourceOnce=true;setup("async-supply");
        var originalRequest=base.request.deepCopy();var first=base.postRequest(base.request);String firstId=first.path("runId").asText();
        String oldJson=base.store.run(base.analysisId,firstId).orElseThrow().responseJson();
        failSourceRelease.countDown();var failed=awaitSource(firstId,"FAILED");
        assertTrue(failed.path("canRetry").asBoolean());assertEquals(1,sourceAttempts.get());
        assertEquals(first,base.postRequest(originalRequest));assertEquals(1,sourceAttempts.get());
        base.request.put("retryOf",firstId);var second=base.postRequest(base.request);String secondId=second.path("runId").asText();
        assertEquals(second,base.postRequest(base.request));var ready=awaitSource(secondId,"READY");
        assertEquals(2,sourceAttempts.get());assertEquals(2,ready.path("attemptNumber").asInt());
        assertNotEquals(failed.path("taskId"),ready.path("taskId"));
        assertEquals("FAILED",base.service.deNovoSourceTaskStatus(base.analysisId,firstId).path("status").asText());
        base.request.put("retryOf",secondId);var adopted=base.postRequest(base.request);String adoptedId=adopted.path("runId").asText();
        var saved=base.store.run(base.analysisId,adoptedId).orElseThrow();var privateData=base.mapper.readTree(saved.privateJson());
        assertEquals("MET",privateData.at("/localBatch/modules/deNovo/results/0/criteria/PS2/evaluationResult/status").asText());
        assertEquals(2,sourceAttempts.get());
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN");base.recreateRepositories();install();
        assertEquals(adopted,base.postRequest(base.request));assertEquals(2,sourceAttempts.get());
        assertEquals(oldJson,base.store.run(base.analysisId,firstId).orElseThrow().responseJson());
        String archive=System.getProperty("varvis.denovo.businessArtifacts");
        if(archive!=null) {
            Path dir=Files.createDirectory(Path.of(archive).resolve("async-retry"));
            Files.writeString(dir.resolve("original-response.json"),oldJson);Files.write(dir.resolve("failed-status.json"),base.mapper.writeValueAsBytes(failed));
            Files.write(dir.resolve("pending-retry.json"),base.mapper.writeValueAsBytes(second));Files.write(dir.resolve("ready-status.json"),base.mapper.writeValueAsBytes(ready));
            Files.writeString(dir.resolve("response.json"),saved.responseJson());Files.writeString(dir.resolve("private.json"),saved.privateJson());
            Files.writeString(dir.resolve("original-after-reopen.json"),base.store.run(base.analysisId,firstId).orElseThrow().responseJson());
        }
    }
    private ObjectNode awaitSource(String runId,String phase) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);ObjectNode value;
        do {
            value=base.service.deNovoSourceTaskStatus(base.analysisId,runId);
            if(phase.equals(value.path("status").asText()) && !"DENOVO_TASK_EXIT_PENDING".equals(value.path("retryBlockedReason").asText())) return value;
            Thread.sleep(15);
        } while(System.nanoTime()<until);
        throw new AssertionError(value.toString());
    }
}
