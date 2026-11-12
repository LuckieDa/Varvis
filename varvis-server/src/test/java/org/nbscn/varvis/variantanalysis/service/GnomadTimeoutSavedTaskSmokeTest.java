package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit opt-in, previously authorized single-task SQL export restored ONLY into a new H2 database.
 * Production source recovery, recommendation verification, population providers/evaluators and HTTP persistence.
 * Unchanged prediction dispatch/receipts are retained from the integrity-checked original run, not executed anew.
 * This test never connects to the source database or starts an analysis, model, or existing service.
 */
@EnabledIfSystemProperty(named = "varvis.gnomad.timeout.savedTask", matches = "true")
class GnomadTimeoutSavedTaskSmokeTest {
    final AcmgBusinessIntegrationTest base = new AcmgBusinessIntegrationTest();
    final AtomicInteger dispatchReplays = new AtomicInteger(), populationFactories = new AtomicInteger();
    final List<Pm2SupportingRuntime> population = new ArrayList<>();
    final List<Ba1AlleleRuntime> ba1 = new ArrayList<>();
    JsonNode original;
    ObjectNode privateData;
    MockEnvironment environment;
    Path work;

    @Test void originalTop20ExplicitRetryPersistsAndReusesWithoutNewPredictions() throws Exception {
        Path exported = Path.of(System.getProperty("varvis.gnomad.timeout.taskExport"));
        String exportHash = sha(exported);
        assertEquals(System.getProperty("varvis.gnomad.timeout.taskExportSha256"), exportHash);
        work = Path.of(System.getProperty("varvis.gnomad.timeout.output")).toAbsolutePath().resolve("saved-task-" + UUID.randomUUID());
        Files.createDirectories(work); base.temporary = work;
        ObjectNode report = base.mapper.createObjectNode().put("status", "BLOCKED").put("taskExportSha256", exportHash)
                .put("mode", "AUTHORIZED_TASK_H2_HTTP_REAL_POPULATION_RETAINED_PREDICTION_DISPATCH");
        try {
            // Match Spring Boot's ISO date serialization before repositories copy the mapper.
            base.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            base.setup();
            var envelope = base.mapper.readTree(exported.toFile());
            assertEquals("USER_AUTHORIZED_READ_ONLY_SINGLE_TASK", envelope.path("scope").asText());
            base.analysisId = envelope.path("analysisId").asText();
            restore(envelope);
            var saved = base.store.run(base.analysisId, envelope.path("runId").asText()).orElseThrow();
            assertEquals(saved.responseSha256(), AcmgBusinessStore.sha(saved.responseJson()));
            original = base.mapper.readTree(saved.responseJson());
            privateData = (ObjectNode) base.mapper.readTree(saved.privateJson());
            assertEquals(original.path("privateArtifactSha256").asText(), AcmgBusinessStore.sha(saved.privateJson()));
            Path publicFile = Path.of(System.getProperty("varvis.gnomad.timeout.export"));
            assertEquals(System.getProperty("varvis.gnomad.timeout.exportSha256"), sha(publicFile));
            // Browser JSON.stringify writes 1.0 as 1 in ordinary VCF provenance. Compare decimal
            // values exactly, not approximately; frozen identity objects and all raw hashes stay unchanged.
            assertTrue(original.equals((left, right) -> left.isNumber() && right.isNumber()
                    ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right) ? 0 : 1,
                    base.mapper.readTree(publicFile.toFile())), "Public export must preserve all stored values");
            base.request = (ObjectNode) privateData.path("declaration").deepCopy();
            configure();
            var rows = base.jobs.findInterpretations(base.analysisId, 0, 20);
            write("formal-top20.json", base.mapper.valueToTree(rows));
            assertEquals(original.path("candidateIds"), base.mapper.valueToTree(rows.stream().map(c -> Objects.requireNonNull(c).getCandidateId()).toList()));
            var recovered = base.sources.recover(base.jobs.findById(base.analysisId).orElseThrow(), rows);
            assertEquals(original.path("executionSourceSha256").asText(), recovered.snapshotSha256());
            assertTrue(recovered.candidateBlockers().isEmpty(), recovered.candidateBlockers().toString());
            wire();
            sameJson(original, base.postRequest(base.request)); // Original timeouts remain the original run.
            assertEquals(0, populationFactories.get()); assertEquals(0, dispatchReplays.get());
            sameJson(original, read(original.path("runId").asText()));
            String retryOf = importPriorIsolatedRun();
            var predecessor = base.store.run(base.analysisId, retryOf).orElseThrow();
            var retry = base.request.deepCopy().put("retryOf", retryOf);
            var fresh = base.postRequest(retry);
            write("retry-response.json", fresh);
            assertNotEquals(original.path("runId"), fresh.path("runId"));
            assertEquals(original.path("candidateIds"), fresh.path("candidateIds"));
            assertEquals(original.path("executionSourceSha256"), fresh.path("executionSourceSha256"));
            assertEquals(20, fresh.path("candidates").size());
            var differences = report.putArray("pm2Changes");
            for (int i = 0; i < 20; i++) {
                var before = original.at("/candidates/" + i); var after = fresh.at("/candidates/" + i);
                assertEquals(before.path("candidateId"), after.path("candidateId"));
                assertEquals("SUCCEEDED", after.at("/ba1/evidence/resourceExecution/status").asText(), "BA1 rank " + (i + 1));
                var oldAttempt = before.at("/attempts/0"); var attempt = after.at("/attempts/0");
                sameJson(oldAttempt.path("context"), attempt.path("context"));
                sameJson(oldAttempt.path("criterionEvaluations"), attempt.path("criterionEvaluations"));
                sameJson(oldAttempt.path("atomicEvidence"), attempt.path("atomicEvidence"));
                var oldPm2 = pm2Result(oldAttempt.path("pm2")); var newPm2 = pm2Result(attempt.path("pm2"));
                differences.addObject().put("rank", i + 1).put("candidateId", after.path("candidateId").asText())
                        .set("before", oldPm2);
                ((ObjectNode) differences.get(i)).set("after", newPm2);
                if (oldAttempt.path("context").isObject()) assertEquals("SUCCEEDED", attempt.at("/pm2/evidence/resourceExecution/status").asText(), "PM2 rank " + (i + 1));
                if (!"RESOURCE_EXECUTION_TIMEOUT".equals(oldPm2.path("reasonCode").asText())) assertEquals(oldPm2, newPm2);
            }
            assertEquals("NOT_MET", pm2Result(fresh.at("/candidates/16/attempts/0/pm2")).path("status").asText());
            var newRun = base.store.run(base.analysisId, fresh.path("runId").asText()).orElseThrow();
            write("retry-private.json", base.mapper.readTree(newRun.privateJson()));
            write("retry-request.json", retry);
            assertEquals(fresh, read(fresh.path("runId").asText()));
            write("get-response.json", read(fresh.path("runId").asText()));
            int factories = populationFactories.get(), replays = dispatchReplays.get();
            base.closeDatabase(); base.recreateRepositories(); wire();
            assertEquals(fresh, read(fresh.path("runId").asText()));
            write("recreated-get-response.json", read(fresh.path("runId").asText()));
            assertEquals(fresh, base.postRequest(retry));
            write("repeat-response.json", base.postRequest(retry));
            sameJson(original, read(original.path("runId").asText()));
            sameJson(original, base.postRequest(base.request));
            assertEquals(factories, populationFactories.get()); assertEquals(replays, dispatchReplays.get());
            assertEquals(saved, base.store.run(base.analysisId, saved.id()).orElseThrow());
            assertEquals(predecessor, base.store.run(base.analysisId, retryOf).orElseThrow());
            assertEquals(exportHash, sha(exported));
            assertEquals(System.getProperty("varvis.gnomad.timeout.exportSha256"), sha(publicFile));
            report.put("status", "SAVED_TASK_REAL_POPULATION_HTTP_RETRY_REBUILD_REUSE_VERIFIED")
                    .put("retryOf", retryOf)
                    .put("newRunId", fresh.path("runId").asText()).put("formalCandidates", 20)
                    .put("retainedPredictionDispatches", replays).put("newPredictionCalls", 0)
                    .put("ba1HistoricalSuccessesReused", 20 - fresh.at("/ba1Calls/provider").asInt())
                    .put("providerCalls", fresh.path("providerCalls").asInt()).put("evaluatorCalls", fresh.path("evaluatorCalls").asInt())
                    .put("repeatNewProviderCalls", 0).put("repeatNewEvaluatorCalls", 0);
        } finally {
            report.put("pm2ProviderCalls", population.stream().mapToInt(r -> Objects.requireNonNull(r).providerCalls()).sum())
                    .put("pm2EvaluatorCalls", population.stream().mapToInt(r -> Objects.requireNonNull(r).evaluatorCalls()).sum());
            report.set("pm2QueryDiagnostics", base.mapper.valueToTree(population.stream().flatMap(r -> r.queryDiagnostics().stream()).toList()));
            report.set("ba1QueryDiagnostics", base.mapper.valueToTree(ba1.stream().flatMap(r -> r.queryDiagnostics().stream()).toList()));
            write("report.json", report); base.closeDatabase();
        }
    }

    private void restore(JsonNode exported) throws Exception {
        // SQL identifiers are allowlisted independently of the read-only export; only this NEW H2 is writable.
        assertTrue(Objects.requireNonNull(base.dataSource.getUrl()).startsWith("jdbc:h2:file:" + work.toString().replace('\\', '/')));
        Set<String> allowed = Set.of("variant_analysis_job", "exomiser_analysis_job", "variant_analysis_interpretation", "variant_analysis_acmg_source", "variant_analysis_acmg_run");
        var jdbc = new JdbcTemplate(Objects.requireNonNull(base.dataSource));
        for (var table : exported.path("tables")) {
            String name = table.path("name").asText(); assertTrue(allowed.contains(name));
            List<String> columns = new ArrayList<>();
            for (var c : table.path("columns")) { String field = c.path("name").asText(); assertTrue(field.matches("[a-z][a-z0-9_]+")); columns.add(field); }
            String sql = "INSERT INTO " + name + " (" + String.join(",", columns) + ") VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
            for (var row : table.path("rows")) {
                assertEquals(columns.size(), row.size());
                int identity = columns.indexOf(name.equals("variant_analysis_job") ? "id" : "analysis_id");
                assertEquals(base.analysisId, row.get(identity).asText());
                Object[] values = new Object[row.size()]; for (int i = 0; i < values.length; i++) values[i] = row.get(i).isNull() ? null : row.get(i).asText();
                jdbc.update(sql, values);
            }
        }
    }
    private String importPriorIsolatedRun() throws Exception {
        String previous = System.getProperty("varvis.gnomad.timeout.priorDatabase", "");
        if (previous.isBlank()) return original.path("runId").asText();
        Path source = Path.of(previous); String hash = sha(source);
        assertEquals(System.getProperty("varvis.gnomad.timeout.priorDatabaseSha256"), hash);
        Path copy = work.resolve("retained-run.mv.db"); Files.copy(source, copy);
        var readOnly = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:file:" + work.resolve("retained-run").toString().replace('\\', '/') + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
        String id = Objects.requireNonNull(System.getProperty("varvis.gnomad.timeout.priorRunId"));
        var retained = new AcmgBusinessStore(readOnly).run(base.analysisId, id).orElseThrow();
        assertEquals(retained.responseSha256(), AcmgBusinessStore.sha(retained.responseJson()));
        var response = base.mapper.readTree(retained.responseJson());
        assertEquals(original.path("candidateIds"), response.path("candidateIds"));
        assertEquals(original.path("executionSourceSha256"), response.path("executionSourceSha256"));
        assertTrue(base.store.claim(retained)); base.store.finish(id, retained.status(), retained.responseJson(), retained.privateJson());
        sameJson(response, base.service.read(base.analysisId, id));
        assertEquals(hash, sha(source)); return id;
    }
    private void configure() throws Exception {
        var local = new Properties(); try (var in = Files.newInputStream(Path.of("config/variant-analysis-local.properties"))) { local.load(in); }
        environment = new MockEnvironment().withProperty("VARVIS_RESOURCE_ROOT", Objects.requireNonNull(System.getProperty("varvis.e4b3.resourceRoot")));
        for (String key : local.stringPropertyNames()) environment.setProperty(Objects.requireNonNull(key), Objects.requireNonNull(local.getProperty(key)));
        Binder.get(environment).bind("variant-analysis", Bindable.ofInstance(base.properties));
        // Existing upload/reference paths are metadata/read-only inputs; the JDBC database remains the new H2.
        base.properties.setStorageRoot(Path.of(System.getProperty("varvis.gnomad.timeout.originalStorageRoot")));
        base.properties.getPersistence().setTablePrefix(""); base.recreateRepositories();
    }
    private void wire() throws Exception {
        var recs = spy(AcmgRecommendations.unavailable(base.mapper));
        doAnswer(call -> {
            VariantInterpretation c = call.getArgument(1);
            assertEquals(original.path("executionSourceSha256").asText(), call.getArgument(3));
            assertTrue(((ArrayNode) call.getArgument(4)).isEmpty());
            for (var r : privateData.path("verifiedRecommendations")) if (r.path("candidateId").asText().equals(c.getCandidateId())) {
                var checked = AcmgRecommendations.unavailable(base.mapper).recommend(base.analysisId, c, call.getArgument(2), call.getArgument(3), call.getArgument(4));
                for (String field : List.of("policyHash", "geneIdentitySource", "relationshipSource", "allele", "candidateMoiSources", "relationships", "clinicalPreferredTranscript"))
                    assertEquals(r.path(field), checked.path(field), "Retained recommendation identity: " + field);
                return r.deepCopy();
            }
            throw new IllegalStateException("RETAINED_RECOMMENDATION_NOT_MATCHED");
        }).when(recs).recommend(anyString(), any(), any(), any(), any());
        var coordinator = mock(ExplicitCaseInputCoordinator.class);
        Path bindings = Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.binding-directory"));
        var resolver = PatientDiseaseContextBindingResolver.load(base.mapper, bindings.resolve("manifest.json"), bindings.resolve("mane-crosswalk.json"), bindings.resolve("clingen-catalog.json"));
        when(coordinator.referenceOptions(any())).thenAnswer(call -> resolver.referenceOptions(base.mapper, call.getArgument(0)));
        when(coordinator.dispatch(any(), any(), any(), any(), anyList(), anyInt(), anyList(), anyMap())).thenAnswer(call -> {
            List<VariantInterpretation> ranked = call.getArgument(4);
            assertEquals(original.path("candidateIds"), base.mapper.valueToTree(ranked.stream().map(c -> Objects.requireNonNull(c).getCandidateId()).toList()));
            assertTrue(((Map<?, ?>) call.getArgument(7)).isEmpty());
            List<ExplicitCaseInputCoordinator.CaseDeclaration> declarations = call.getArgument(6); assertEquals(1, declarations.size());
            var expected = AcmgSubmission.parse(base.mapper.writeValueAsBytes(base.request), base.mapper);
            assertEquals(expected.patient(), declarations.get(0).patient()); assertEquals(expected.sampleId(), declarations.get(0).sampleId());
            assertEquals(expected.dataClass(), declarations.get(0).dataClass());
            assertEquals(recs.withPopulationContexts(expected, (ArrayNode) privateData.path("verifiedRecommendations")), declarations.get(0).hypotheses());
            dispatchReplays.incrementAndGet();
            ObjectNode adapter = original.at("/result/adapterResult").deepCopy();
            ((ObjectNode) adapter.path("dispatch")).put("providerCalls", 0).put("evaluatorCalls", 0);
            for (var row : adapter.at("/dispatch/candidates")) for (var attempt : row.path("attempts")) ((ObjectNode) attempt).put("providerCalls", 0).put("evaluatorCalls", 0);
            List<ExplicitContextSourceLedger> ledgers = new ArrayList<>();
            for (var receipt : privateData.path("sourceReceipts")) {
                byte[] raw = receipt.path("rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ledgers.add(Objects.requireNonNull(ReflectionTestUtils.invokeMethod(ExplicitContextSourceLedger.class, "verifiedBytes", base.mapper, raw, receipt.path("sha256").asText())));
            }
            var production = mock(NativeSnvSourceProducer.Production.class);
            var provenance = ((ObjectNode) privateData.path("sourceProduction")).deepCopy();
            provenance.put("retainedFromRunId", original.path("runId").asText()).put("referenceQueryAttempts", 0);
            when(production.provenance()).thenReturn(provenance);
            List<ExplicitCaseInputCoordinator.DeclarationOutcome> outcomes = base.mapper.convertValue(original.at("/result/declarations"), base.mapper.getTypeFactory().constructCollectionType(List.class, ExplicitCaseInputCoordinator.DeclarationOutcome.class));
            return new ExplicitCaseInputCoordinator.Result(base.mapper.treeToValue(adapter, CandidateAcmgInputAdapter.Result.class), outcomes, ledgers, production);
        });
        var checksum = ExplicitContextSourceLedger.load(base.mapper, Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")), environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
        var producer = new NativeSnvSourceProducer(base.mapper, base.properties, Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")), List.of(checksum), checksum.reference(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-locator")));
        base.service = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources, () -> coordinator,
                () -> { var r = new Ba1AlleleRuntime(base.mapper, settings()); ba1.add(r); return new AcmgBusinessService.Ba1Runtime(producer, r); }, recs,
                () -> { populationFactories.incrementAndGet(); var r = new Pm2SupportingRuntime(base.mapper, settings()); population.add(r); return r; });
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    private VariantAnalysisProperties settings() {
        var settings = new VariantAnalysisProperties(); var g = settings.getGnomad(); g.setEnabled(true); g.setPopulationInputEnabled(true);
        g.setVersion("gnomAD joint v4.1 GRCh38"); g.setTabixExecutable(base.properties.getGnomad().getTabixExecutable());
        ReflectionTestUtils.invokeMethod(AcmgBusinessConfig.class, "configurePopulationQuery", settings, environment); return settings;
    }
    private JsonNode read(String run) throws Exception {
        return base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode pm2Result(JsonNode pm2) { return pm2.has("evaluation") ? pm2.at("/evaluation/evaluationResult") : pm2.path("evaluationResult"); }
    private void sameJson(JsonNode expected, JsonNode actual) {
        assertTrue(expected.equals((left, right) -> left.isNumber() && right.isNumber()
                ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right) ? 0 : 1, actual),
                "Complete JSON values must match exactly, including runId and evidence identities");
    }
    private void write(String name, JsonNode node) throws Exception { base.mapper.writerWithDefaultPrettyPrinter().writeValue(work.resolve(name).toFile(), node); }
    private static String sha(Path path) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
