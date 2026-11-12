package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Adds complete history/background and canonical request checks to the accepted source-byte reader.
 * This child JVM reconstructs read-only Service/Controller routes, never the CURRENT scientific factory. */
public final class DeNovoUnifiedEighteenReopenReader {
    private DeNovoUnifiedEighteenReopenReader() { }

    public static void main(String[] args) throws Exception {
        // Reuse the accepted exact public/private/request/source and nested original-byte audit.
        DeNovoCurrentBusinessReopenReader.main(args);
        Path database = Path.of(args[0]).toRealPath(), output = Path.of(args[2]).toRealPath();
        String analysisId = args[1];
        var mapper = Jackson2ObjectMapperBuilder.json().build()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode manifest = mapper.readTree(Files.readAllBytes(output.resolve("manifest.json")));
        var source = new DriverManagerDataSource("jdbc:h2:file:" + database.resolve("business-db").toString().replace('\\', '/')
                + ";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try {
            var properties = new VariantAnalysisProperties(); properties.setStorageRoot(database.resolve("jobs"));
            properties.getPersistence().setTablePrefix(""); properties.getPersistence().setRetryBackoff(Duration.ZERO);
            var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(source),
                    new DataSourceTransactionManager(source), properties, mapper);
            var store = new AcmgBusinessStore(source); var beforeRuns = store.runs(analysisId);
            var beforeSource = store.source(analysisId).orElseThrow(); var forbidden = new AtomicInteger();
            var service = new AcmgBusinessService(mapper, jobs, store, new AcmgExecutionSources(mapper, store),
                    () -> forbidden(forbidden), () -> forbidden(forbidden), AcmgRecommendations.unavailable(mapper),
                    () -> forbidden(forbidden), () -> forbidden(forbidden), () -> forbidden(forbidden), null);
            MockMvc http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
            JsonNode history = mapper.readTree(get(http, "/variant-analyses/" + analysisId + "/acmg/runs"));
            require(history.isArray() && history.size() == beforeRuns.size(), "HISTORY_COUNT_CHANGED");
            require(history.equals(mapper.valueToTree(service.list(analysisId))), "HISTORY_SERVICE_CONTROLLER_MISMATCH");
            var expectedIds = new HashSet<String>(); beforeRuns.forEach(run -> expectedIds.add(run.id()));
            var actualIds = new HashSet<String>();
            for (var row : history) {
                String id = row.path("runId").asText(); require(actualIds.add(id), "HISTORY_DUPLICATE_RUN");
                var saved = store.run(analysisId, id).orElseThrow();
                require(saved.status().equals(row.path("status").asText())
                        && saved.createdAt().equals(row.path("createdAt").asText())
                        && ("/variant-analyses/" + analysisId + "/acmg/runs/" + id).equals(row.path("resultUrl").asText()),
                        "HISTORY_SAVED_RUN_MISMATCH");
            }
            require(expectedIds.equals(actualIds), "HISTORY_RUN_IDS_CHANGED");
            Files.write(output.resolve("controller-history.json"), mapper.writeValueAsBytes(history));
            var receipt = mapper.createObjectNode().put("schemaVersion", "denovo-unified18-reopen-v1")
                    .put("rebuildKind", "READ_ONLY_SERVICE_CONTROLLER").put("currentFactoryReassembly", "NOT_RUN")
                    .put("baseByteAndOriginalAudit", "reopen-receipt.json").put("databaseAccessMode", "READ_ONLY")
                    .put("processId", ProcessHandle.current().pid()).put("historyRunCount", history.size());
            var checks = receipt.putArray("runs"); var artifacts = new AcmgBusinessArtifacts(mapper);
            for (var item : manifest.path("runs")) {
                String runId = item.path("runId").asText(), name = item.path("name").asText();
                require(name.matches("[A-Za-z0-9_-]{1,100}"), "RUN_NAME_INVALID");
                var saved = store.run(analysisId, runId).orElseThrow();
                String requestText = saved.requestJson();
                if (requestText == null) throw new IllegalStateException("DENOVO_UNIFIED18_REOPEN_REQUEST_REQUIRED");
                JsonNode request = mapper.readTree(requestText), full = service.read(analysisId, runId);
                require(artifacts.requestHash(request).equals(saved.requestSha256()), "CANONICAL_REQUEST_SHA_MISMATCH");
                require(saved.requestSha256().equals(full.path("requestSha256").asText()), "PUBLIC_REQUEST_SHA_MISMATCH");
                require(request.path("executionProfile").equals(full.path("executionProfile")), "PROFILE_CHANGED");
                JsonNode declaration = request.path("declaration"), criteria = full.path("enabledCriteria");
                require(criteria.isArray() && criteria.equals(request.at("/executionProfile/enabledCriteria")), "CRITERIA_CHANGED");
                boolean configuredAll = !declaration.hasNonNull("selectedCriteria");
                if (configuredAll) require(criteria.size() == 18, "CONFIGURED_ALL_IS_NOT_EIGHTEEN");
                int backgrounds = 0, candidateIndex = 0;
                String endpoint = "/variant-analyses/" + analysisId + "/acmg/runs/" + runId;
                JsonNode index = mapper.readTree(get(http, endpoint + "/view"));
                for (var candidate : full.path("candidates")) {
                    String candidateId = candidate.path("candidateId").asText();
                    require(!candidateId.isBlank(), "CANDIDATE_ID_REQUIRED");
                    for (int background = 0; background < candidate.path("attempts").size(); background++) {
                        var response = http.perform(MockMvcRequestBuilders.get(endpoint + "/view/background")
                                .param("candidateId", candidateId).param("backgroundIndex", Integer.toString(background))
                                .param("sourceSha256", index.path("sourceViewSha256").asText())).andReturn().getResponse();
                        require(response.getStatus() == 200, "BACKGROUND_HTTP_FAILED");
                        JsonNode view = mapper.readTree(response.getContentAsByteArray());
                        require(candidate.path("attempts").get(background).equals(view.at("/candidates/0/attempts/" + background)),
                                "BACKGROUND_CHANGED");
                        Files.write(output.resolve(name).resolve("controller-background-" + candidateIndex + "-" + background + ".json"),
                                response.getContentAsByteArray()); backgrounds++;
                    }
                    candidateIndex++;
                }
                require(saved.equals(store.run(analysisId, runId).orElseThrow()), "READ_MUTATED_SAVED_RUN");
                checks.addObject().put("name", name).put("runId", runId).put("canonicalRequestSha256", saved.requestSha256())
                        .put("canonicalRequestShaMatched", true).put("savedEnabledCriteriaCount", criteria.size())
                        .put("configuredAll", configuredAll).put("allSavedBackgroundCount", backgrounds)
                        .put("allSavedBackgroundsMatched", true);
            }
            require(beforeRuns.equals(store.runs(analysisId)) && beforeSource.equals(store.source(analysisId).orElseThrow()),
                    "READ_MUTATED_HISTORY_OR_SOURCE");
            require(forbidden.get() == 0, "SCIENTIFIC_RUNTIME_WAS_CALLED");
            receipt.put("forbiddenRuntimeCalls", forbidden.get()).put("sourceAndHistoryUnchanged", true)
                    .put("noCurrentScientificRuntimeAssembled", true).put("passed", true);
            Files.write(output.resolve("unified18-reopen-receipt.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
            System.out.println("PASS: CURRENT18 saved canonical request, history and all backgrounds match in another JVM; scientific factory not reconstructed");
        } finally { new JdbcTemplate(source).execute("SHUTDOWN"); }
    }

    private static byte[] get(MockMvc http, String path) throws Exception {
        var response = http.perform(MockMvcRequestBuilders.get(requireNonNull(path))).andReturn().getResponse();
        require(response.getStatus() == 200, "READ_HTTP_FAILED"); return response.getContentAsByteArray();
    }
    private static <T> T forbidden(AtomicInteger count) {
        count.incrementAndGet(); throw new AssertionError("DENOVO_UNIFIED18_HISTORY_MUST_NOT_EXECUTE");
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException("DENOVO_UNIFIED18_REOPEN_" + reason);
    }
}
