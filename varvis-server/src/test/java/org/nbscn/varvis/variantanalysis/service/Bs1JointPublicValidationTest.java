package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two fixed public sites only. No upload/model execution, no changes to original store or artifacts. */
@EnabledIfSystemProperty(named = "varvis.bs1.jointValidation", matches = "true")
class Bs1JointPublicValidationTest {
    @Test void boundedRealFactsUseTheProductionRuntimeWithHonestSourceRecoveryBoundary() throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("varvis.bs1.archiveRoot")));
        Path work = Path.of(Objects.requireNonNull(System.getProperty("varvis.bs1.validationWork")));
        var base = new AcmgBusinessIntegrationTest(); var mapper = base.mapper; var adapter = new RuntimeEvidenceV2Adapter(mapper);
        Path archived = root.resolve("frequency-recheck-d25e7417-56a2-42f1-a79d-2c38d6d89d55/reconstructed-get.json");
        byte[] bytes = Files.readAllBytes(archived); String archiveSha = hash(bytes);
        assertEquals("34838534ce4aba9071b47ce1e10f31e528aefa1167bb92cb63d847e3fd48aa10", archiveSha);
        var old = mapper.readTree(bytes); String source = old.path("executionSourceSha256").asText();
        var report = mapper.createObjectNode().put("schemaVersion", "bs1-joint-filter-validation-v1")
                .put("archiveSha256", archiveSha).put("archiveRunId", old.path("runId").asText())
                .put("formalEnabled", false).put("ordinaryPageCovered", false).put("newVepCalls", 0).put("newExomiserCalls", 0)
                .put("dataClass", "SYNTHETIC").put("scope", "REAL_RUNTIME_WITH_ARCHIVED_PUBLIC_CONTEXT_NOT_NEW_BUSINESS_RUN");
        var results = report.putArray("results");
        // Copy the database only. Original absolute artifact paths and source identities remain unchanged.
        Path originalDb = root.resolve("business-db.mv.db"), copiedDb = work.resolve("recovery-copy.mv.db");
        String originalDbSha = hash(Files.readAllBytes(originalDb)); Files.copy(originalDb, copiedDb);
        base.properties = new VariantAnalysisProperties(); base.properties.setStorageRoot(registeredStorageRoot(base.mapper, root));
        base.dataSource = new DriverManagerDataSource("jdbc:h2:file:" + work.resolve("recovery-copy").toString().replace('\\', '/') + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        base.analysisId = old.path("analysisId").asText();
        try {
            base.recreateRepositories();
            var job = base.jobs.findById(base.analysisId).orElseThrow(); var rows = base.jobs.findInterpretations(base.analysisId, 0, 20);
            try {
                var recovered = base.sources.recover(job, rows);
                report.put("sourceRecovery", "READY").put("sourceSnapshot", recovered.snapshotSha256());
                // Never fall through to direct runtime when a legal normal submit is available.
                throw new IllegalStateException("PUBLIC_SOURCE_RECOVERABLE_USE_ISOLATED_SUBMIT_BEFORE_QUERIES");
            } catch (IllegalArgumentException | java.nio.file.NoSuchFileException unavailable) {
                report.put("sourceRecovery", "BLOCKED").put("sourceRecoveryReason", AcmgExecutionSources.code(unavailable))
                        .put("sourceRecoveryException", unavailable.getClass().getSimpleName());
                var sourceRecord = mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
                String originalPath = sourceRecord.path("analysisVcf").asText();
                report.put("registeredAnalysisVcfExists", Files.isRegularFile(Path.of(originalPath)))
                        .put("sourceRecoveryDetail", "Original registered artifact path retained; no path, timestamp, file key or source witness was rewritten");
            }
        } finally { base.closeDatabase(); }
        assertEquals(originalDbSha, hash(Files.readAllBytes(originalDb)));
        var settings = new VariantAnalysisProperties(); var g = settings.getGnomad();
        g.setEnabled(true); g.setPopulationInputEnabled(true); g.setVersion("gnomAD joint v4.1 GRCh38");
        g.setTabixExecutable(Objects.requireNonNull(System.getProperty("varvis.bs1.tabix")));
        g.setTimeout(Duration.ofSeconds(120)); g.setRemoteTotalTimeout(Duration.ofSeconds(120)); g.setRemoteMaxAttempts(1);
        g.setRemoteIndexCacheDir(Objects.requireNonNull(System.getProperty("varvis.bs1.indexCache")));
        g.setAllowFullVcfFallback(false); g.setAllowVcfInfoFallback(false);
        var runtime = Bs1Runtime.configured(mapper, Bs1Runtime.policies(mapper), settings, new Bs1NativeFilterSupplement(mapper, settings, work));
        var candidates = mapper.readTree(Files.readAllBytes(root.resolve("formal-topn.json")));
        try {
            for (int position : List.of(120988847, 120993678)) {
                ObjectNode previous = null;
                for (var row : old.path("candidates")) for (var attempt : row.path("attempts"))
                    if (attempt.at("/pm2/context/variant/evidenceQueryAllele/position").asInt() == position) {
                        assertNull(previous); previous = (ObjectNode) attempt.path("pm2");
                    }
                var original = Objects.requireNonNull(previous); var context = (ObjectNode) original.path("context").deepCopy();
                // Validates the full original atomic identity; old raw facts are never injected into the new query.
                PopulationObservation.restore(mapper, original, source, "SYNTHETIC");
                context.put("schemaVersion", "evidence-context-v3"); context.remove("transcript");
                context.put("contextId", adapter.canonicalVersionedContextId(context));
                VariantInterpretation candidate = null;
                for (var row : candidates) if (row.path("candidateId").equals(original.path("candidateId")) && row.at("/observation/key/position").asInt() == position) {
                    assertNull(candidate); candidate = mapper.treeToValue(row, VariantInterpretation.class);
                }
                assertNotNull(candidate);
                // Durable budget marker prevents another invocation/process from resetting this task's budget.
                Files.writeString(work.resolve("joint-query-" + position + ".claimed"), java.time.Instant.now().toString(), StandardOpenOption.CREATE_NEW);
                var result = runtime.evaluate(context, Objects.requireNonNull(candidate), new AnalysisContext(base.analysisId, GenomeAssembly.GRCh38, AnalysisMode.WES), source);
                var row = results.addObject().put("position", position);
                row.set("result", result);
                row.put("rawEqualsArchivedFullRow", original.path("rawPopulationRecord").equals(result.at("/observation/rawRecord")));
                row.put("oldRawSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(original.path("rawPopulationRecord").asText()));
                report.set("actualCalls", runtime.counts()); if (!results.isEmpty()) report.set("queryDiagnostics", results.get(results.size() - 1).at("/result/observation/transport/diagnostics"));
                Files.writeString(work.resolve("result.json"), report.toPrettyString());
            }
        } finally {
            report.set("actualCalls", runtime.counts()); if (!results.isEmpty()) report.set("queryDiagnostics", results.get(results.size() - 1).at("/result/observation/transport/diagnostics"));
            Files.writeString(work.resolve("result.json"), report.toPrettyString());
        }
        assertEquals(2, runtime.counts().path("provider").asInt());
        assertEquals(0, runtime.counts().path("native").asInt()); assertEquals(0, runtime.counts().path("allSites").asInt());
        assertEquals(archiveSha, hash(Files.readAllBytes(archived)));
        assertEquals(report, mapper.readTree(Files.readString(work.resolve("result.json"))));
        System.out.println("BS1_JOINT_REAL_RESULT=" + work.resolve("result.json"));
    }
    /** Follow-up is read-only and never consumes a second resource-query budget. */
    @Test @EnabledIfSystemProperty(named = "varvis.bs1.recoveryOnly", matches = "true")
    void originalLogicalStorageConfigurationLocatesTheRemainingArtifactBlocker() throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("varvis.bs1.archiveRoot")));
        Path work = Path.of(Objects.requireNonNull(System.getProperty("varvis.bs1.validationWork")));
        assertTrue(Files.isRegularFile(work.resolve("recovery-copy.mv.db")));
        var base = new AcmgBusinessIntegrationTest(); var mapper = base.mapper;
        var report = (ObjectNode) mapper.readTree(Files.readAllBytes(work.resolve("result.json")));
        String scientificResults = report.path("results").toString();
        base.properties = new VariantAnalysisProperties(); base.properties.setStorageRoot(registeredStorageRoot(mapper, root));
        base.dataSource = new DriverManagerDataSource("jdbc:h2:file:" + work.resolve("recovery-copy").toString().replace('\\', '/') + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        String originalSha = hash(Files.readAllBytes(root.resolve("business-db.mv.db")));
        try {
            base.recreateRepositories();
            String id = "13bce19e-eab3-41d3-8218-5a38c0ae7aa3";
            var job = base.jobs.findById(id).orElseThrow();
            var rows = base.jobs.findInterpretations(id, 0, 20);
            var source = mapper.readTree(base.store.source(id).orElseThrow().json());
            assertEquals(Path.of(source.path("uploadedFile").asText()), job.getUploadedFile().toAbsolutePath().normalize());
            var failure = assertThrows(java.nio.file.NoSuchFileException.class, () -> base.sources.recover(job, rows));
            report.putObject("sourceRecoveryWithOriginalConfiguration").put("status", "BLOCKED")
                    .put("reasonCode", "REGISTERED_EXECUTION_VCF_PATH_MISSING").put("exceptionType", failure.getClass().getSimpleName())
                    .put("missingArtifact", failure.getFile()).put("taskPathAssociationMatched", true).put("newScientificCalls", 0);
            assertEquals(scientificResults, report.path("results").toString());
            Files.writeString(work.resolve("result.json"), report.toPrettyString());
        } finally { base.closeDatabase(); }
        assertEquals(originalSha, hash(Files.readAllBytes(root.resolve("business-db.mv.db"))));
    }
    @Test @EnabledIfSystemProperty(named = "varvis.bs1.finalReplay", matches = "true")
    void finalQualityContractReusesThisTasksCapturedFactsWithoutQueries() throws Exception {
        Path work = Path.of(Objects.requireNonNull(System.getProperty("varvis.bs1.validationWork")));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var report = (ObjectNode) mapper.readTree(Files.readAllBytes(work.resolve("result.json")));
        String acquired = report.path("results").toString();
        var provider = org.mockito.Mockito.mock(VariantEvidenceProvider.class);
        var runtime = new Bs1Runtime(mapper, Bs1Runtime.policies(mapper), provider, null);
        var finalResults = report.putArray("finalResults");
        for (var row : report.path("results")) {
            var previous = row.path("result"); var observation = previous.path("observation");
            var result = runtime.replay(previous.path("context"), observation, observation.path("sourceSnapshot").asText());
            assertEquals(previous.at("/evaluation/evaluationResult/status"), result.at("/evaluation/evaluationResult/status"));
            assertEquals("1.1.1", result.at("/qualityDecision/contract/version").asText());
            assertEquals(observation, result.path("observation"));
            var entry = finalResults.addObject().put("position", row.path("position").asInt()); entry.set("result", result);
        }
        report.set("finalReplayCalls", runtime.counts()); report.set("finalRuntimeIdentity", runtime.identity());
        assertEquals(0, runtime.counts().path("provider").asInt()); assertEquals(2, runtime.counts().path("evaluator").asInt());
        org.mockito.Mockito.verifyNoInteractions(provider); assertEquals(acquired, report.path("results").toString());
        Files.writeString(work.resolve("result.json"), report.toPrettyString());
    }
    private static Path registeredStorageRoot(com.fasterxml.jackson.databind.ObjectMapper mapper, Path root) throws Exception {
        String uri = mapper.readTree(Files.readAllBytes(root.resolve("formal-job.json"))).path("uploadedFile").asText();
        return Objects.requireNonNull(Objects.requireNonNull(Path.of(java.net.URI.create(uri)).getParent()).getParent());
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
