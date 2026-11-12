package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
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

/** Another process's saved-result reader. It assembles only the real repository,
 * Service and Controller read paths; every scientific supplier is forbidden.
 */
public final class T01PublishedSupplyHistoryReader {
    private T01PublishedSupplyHistoryReader() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "ARGUMENTS_REQUIRED");
        Path database = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toRealPath();
        require(Files.isDirectory(database) && Files.isDirectory(output)
                && Files.isRegularFile(database.resolve("business-db.mv.db")), "EXISTING_DATABASE_REQUIRED");
        var mapper = Jackson2ObjectMapperBuilder.json().build()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode manifest = mapper.readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
        require(manifest != null && manifest.isObject(), "MANIFEST_OBJECT_REQUIRED");
        if (manifest == null) throw new IllegalStateException("T01_HISTORY_MANIFEST_OBJECT_REQUIRED");
        String analysisId = manifest.path("analysisId").asText();
        var writerPid = manifest.path("writerPid");
        require(!analysisId.isBlank() && writerPid.isIntegralNumber() && writerPid.canConvertToLong()
                && writerPid.asLong() > 0 && writerPid.asLong() != ProcessHandle.current().pid(), "WRITER_IDENTITY_INVALID");
        require(ProcessHandle.of(writerPid.asLong()).map(handle -> !handle.isAlive()).orElse(true), "WRITER_PROCESS_STILL_ALIVE");
        var runs = manifest.path("runs");
        require(runs.isArray() && !runs.isEmpty(), "MANIFEST_RUNS_REQUIRED");

        var source = new DriverManagerDataSource("jdbc:h2:file:" + database.resolve("business-db").toString().replace('\\', '/')
                + ";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try {
            var properties = new VariantAnalysisProperties();properties.setStorageRoot(database.resolve("jobs"));
            properties.getPersistence().setTablePrefix("");properties.getPersistence().setRetryBackoff(Duration.ZERO);
            var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(source),
                    new DataSourceTransactionManager(source), properties, mapper);
            var job = jobs.findById(analysisId).orElseThrow(() -> failure("PERSISTED_ANALYSIS_REQUIRED"));
            require(analysisId.equals(job.getId()), "PERSISTED_ANALYSIS_ID_MISMATCH");
            var persisted = jobs.findInterpretations(analysisId, 0, 200);
            require(!persisted.isEmpty(), "PERSISTED_CANDIDATES_REQUIRED");
            JsonNode candidatesBefore = mapper.valueToTree(persisted);
            var candidateIds = new HashSet<String>();
            persisted.forEach(candidate -> require(candidate.getCandidateId() != null
                    && candidateIds.add(candidate.getCandidateId()), "PERSISTED_CANDIDATE_IDENTITY_INVALID"));
            var store = new AcmgBusinessStore(source);var sourceBefore = store.source(analysisId).orElseThrow(() -> failure("PUBLISHED_SOURCE_REQUIRED"));
            byte[] sourceBytes = exact(output.resolve("expected-source.json"), sourceBefore.json(), "SOURCE");
            require(AcmgBusinessStore.sha(sourceBefore.json()).equals(sourceBefore.sha256()), "PUBLISHED_SOURCE_SHA_MISMATCH");
            var expectedSource = object(mapper, sourceBytes, "SOURCE");
            var historyBefore = store.runs(analysisId);
            var forbidden = new AtomicInteger();
            var service = new AcmgBusinessService(mapper, jobs, store, new AcmgExecutionSources(mapper, store),
                    () -> forbidden(forbidden), () -> forbidden(forbidden), AcmgRecommendations.unavailable(mapper),
                    () -> forbidden(forbidden), () -> forbidden(forbidden), () -> forbidden(forbidden), null);
            MockMvc http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
            var artifacts = new AcmgBusinessArtifacts(mapper);
            var serviceHistory = mapper.valueToTree(service.list(analysisId));
            JsonNode controllerHistory = mapper.readTree(get(http, "/variant-analyses/" + analysisId + "/acmg/runs"));
            require(serviceHistory.equals(controllerHistory) && controllerHistory.isArray()
                    && controllerHistory.size() == historyBefore.size(), "HISTORY_CHANGED");
            var historyIds = new HashSet<String>();
            for (var row : controllerHistory) {
                String id = row.path("runId").asText();require(historyIds.add(id), "HISTORY_DUPLICATE_RUN");
                var saved = store.run(analysisId, id).orElseThrow(() -> failure("HISTORY_RUN_MISSING"));
                String status = saved.status(), created = saved.createdAt();
                if (status == null || created == null) throw failure("HISTORY_RUN_METADATA_REQUIRED");
                require(status.equals(row.path("status").asText()) && created.equals(row.path("createdAt").asText())
                        && ("/variant-analyses/" + analysisId + "/acmg/runs/" + id).equals(row.path("resultUrl").asText()), "HISTORY_RUN_MISMATCH");
            }
            var storedIds = new HashSet<String>();historyBefore.forEach(run -> storedIds.add(run.id()));
            require(storedIds.equals(historyIds), "HISTORY_RUN_IDS_CHANGED");
            Files.write(output.resolve("controller-history.json"), mapper.writeValueAsBytes(controllerHistory));
            Files.write(output.resolve("raw-source.json"), sourceBytes);

            var receipt = mapper.createObjectNode().put("schemaVersion", "t01-published-supply-history-reader-v1")
                    .put("processId", ProcessHandle.current().pid()).put("writerPid", writerPid.asLong())
                    .put("writerProcessExited", true).put("databaseAccessMode", "READ_ONLY")
                    .put("rebuildKind", "ACTUAL_REPOSITORIES_SERVICE_CONTROLLER_READ_PATHS")
                    .put("currentFactoryReassembly", "NOT_RUN").put("persistedCandidateCount", persisted.size())
                    .put("historyRunCount", historyBefore.size()).put("manifestRunCount", runs.size()).put("sourceRawSha256", sourceBefore.sha256());
            var checks = receipt.putArray("runs");var names = new HashSet<String>();var requestedIds = new HashSet<String>();
            for (var item : runs) {
                require(item.isObject(), "MANIFEST_RUN_INVALID");
                String name = item.path("name").asText(), id = item.path("runId").asText();
                require(name.matches("[A-Za-z0-9_-]{1,100}") && names.add(name) && !id.isBlank() && requestedIds.add(id), "MANIFEST_RUN_IDENTITY_INVALID");
                Path folder = output.resolve(name).toRealPath();require(folder.startsWith(output), "RUN_OUTPUT_OUTSIDE_ROOT");
                var saved = store.run(analysisId, id).orElseThrow(() -> failure("SAVED_RUN_REQUIRED"));
                String publicText = saved.responseJson(), privateText = saved.privateJson(), requestText = saved.requestJson();
                if (publicText == null || privateText == null || requestText == null) throw failure("COMPLETE_RUN_PAYLOADS_REQUIRED");
                require(analysisId.equals(saved.analysisId()) && id.equals(saved.id()) && saved.status() != null
                        && !"RUNNING".equals(saved.status()), "SAVED_RUN_IDENTITY_INVALID");
                byte[] publicBytes = exact(folder.resolve("expected-public.json"), publicText, "PUBLIC");
                byte[] privateBytes = exact(folder.resolve("expected-private.json"), privateText, "PRIVATE");
                byte[] requestBytes = exact(folder.resolve("expected-request.json"), requestText, "REQUEST");
                ObjectNode expected = object(mapper, publicBytes, "PUBLIC"), privateData = object(mapper, privateBytes, "PRIVATE"), request = object(mapper, requestBytes, "REQUEST");
                require(AcmgBusinessStore.sha(publicText).equals(saved.responseSha256()), "PUBLIC_RAW_SHA_MISMATCH");
                require(AcmgBusinessStore.sha(privateText).equals(expected.path("privateArtifactSha256").asText()), "PRIVATE_RAW_SHA_MISMATCH");
                String canonicalRequest = artifacts.requestHash(request);
                require(canonicalRequest.equals(saved.requestSha256()) && canonicalRequest.equals(expected.path("requestSha256").asText()), "CANONICAL_REQUEST_SHA_MISMATCH");
                require(request.path("executionProfile").isObject() && request.path("executionProfile").equals(expected.path("executionProfile"))
                        && request.at("/executionProfile/enabledCriteria").equals(expected.path("enabledCriteria")), "SAVED_PROFILE_CHANGED");
                require(request.path("declaration").equals(privateData.path("declaration")), "PRIVATE_DECLARATION_MISMATCH");
                require(analysisId.equals(expected.path("analysisId").asText()) && id.equals(expected.path("runId").asText()), "PUBLIC_RUN_IDENTITY_MISMATCH");

                ObjectNode full = service.read(analysisId, id);require(expected.equals(full), "SERVICE_FULL_JSON_CHANGED");
                String endpoint = "/variant-analyses/" + analysisId + "/acmg/runs/" + id;
                byte[] export = get(http, endpoint);require(expected.equals(object(mapper, export, "CONTROLLER_EXPORT")), "CONTROLLER_FULL_JSON_CHANGED");
                Files.write(folder.resolve("controller-full-export.json"), export);
                Files.write(folder.resolve("raw-full-export.json"), publicBytes);
                Files.write(folder.resolve("raw-private.json"), privateBytes);Files.write(folder.resolve("raw-request.json"), requestBytes);
                var index = service.readView(analysisId, id, null, 0, null);
                JsonNode controllerIndex = mapper.readTree(get(http, endpoint + "/view"));
                require(index.equals(controllerIndex) && AcmgResultViews.INDEX.equals(index.path("viewKind").asText()), "INDEX_CHANGED");
                String viewSha = AcmgBusinessStore.sha(full.toString());
                require(viewSha.equals(index.path("sourceViewSha256").asText()) && expected.path("candidateIds").equals(index.path("candidateIds")), "INDEX_SOURCE_BINDING_MISMATCH");
                Files.write(folder.resolve("controller-index.json"), mapper.writeValueAsBytes(controllerIndex));
                int backgrounds = 0, candidateIndex = 0;
                require(expected.path("candidates").isArray(), "PUBLIC_CANDIDATES_REQUIRED");
                for (var candidate : expected.path("candidates")) {
                    String candidateId = candidate.path("candidateId").asText();
                    require(candidateIds.contains(candidateId) && candidate.path("attempts").isArray()
                            && candidate.path("attempts").size() == 1, "PUBLIC_CANDIDATE_OR_SELECTED_CONTEXT_NOT_PERSISTED");
                    for (int background = 0; background < candidate.path("attempts").size(); background++) {
                        var serviceView = service.readView(analysisId, id, candidateId, background, viewSha);
                        byte[] viewBytes = background(http, endpoint, candidateId, background, viewSha);
                        JsonNode controllerView = mapper.readTree(viewBytes);
                        require(serviceView.equals(controllerView) && candidate.path("attempts").get(background)
                                .equals(controllerView.at("/candidates/0/attempts/" + background)), "BACKGROUND_CHANGED");
                        Files.write(folder.resolve("controller-background-" + candidateIndex + "-" + background + ".json"), viewBytes);backgrounds++;
                    }
                    candidateIndex++;
                }
                var unchanged = store.run(analysisId, id).orElseThrow();
                require(expected.equals(service.read(analysisId, id)) && saved.equals(unchanged), "READ_MUTATED_SAVED_RUN");
                String retainedPrivate = unchanged.privateJson();
                if (retainedPrivate == null) throw failure("PRIVATE_GRAPH_REQUIRED");
                require(privateData.equals(object(mapper, retainedPrivate.getBytes(StandardCharsets.UTF_8), "PRIVATE_GRAPH")), "PRIVATE_SOURCE_GRAPH_CHANGED");
                checks.addObject().put("name", name).put("runId", id).put("storedPublicRawSha256", saved.responseSha256())
                        .put("storedPrivateRawSha256", AcmgBusinessStore.sha(privateText)).put("storedRequestRawSha256", AcmgBusinessStore.sha(requestText))
                        .put("canonicalRequestSha256", canonicalRequest).put("controllerExportRawSha256", AcmgBusinessStore.sha(new String(export, StandardCharsets.UTF_8)))
                        .put("rawPublicPrivateRequestBytesMatched", true).put("serviceAndControllerFullJsonMatched", true)
                        .put("controllerWhitespaceByteEqualityClaimed", false).put("completePrivateGraphMatched", true)
                        .put("allSavedBackgroundCount", backgrounds).put("allSavedBackgroundsMatched", true)
                        .put("enabledCriteriaCount", expected.path("enabledCriteria").size());
            }
            require(storedIds.containsAll(requestedIds), "MANIFEST_RUN_NOT_IN_HISTORY");
            require(historyBefore.equals(store.runs(analysisId)) && sourceBefore.equals(store.source(analysisId).orElseThrow()), "READ_MUTATED_HISTORY_OR_SOURCE");
            require(expectedSource.equals(object(mapper, store.source(analysisId).orElseThrow().json().getBytes(StandardCharsets.UTF_8), "SOURCE_GRAPH")), "SOURCE_GRAPH_CHANGED");
            require(candidatesBefore.equals(mapper.valueToTree(jobs.findInterpretations(analysisId, 0, 200))), "READ_MUTATED_PERSISTED_CANDIDATES");
            require(forbidden.get() == 0, "SCIENTIFIC_RUNTIME_WAS_CALLED");
            receipt.put("forbiddenRuntimeCalls", forbidden.get()).put("sourceAndHistoryUnchanged", true)
                    .put("completeSourceGraphMatched", true).put("persistedCandidatesUnchanged", true)
                    .put("noCurrentScientificRuntimeAssembled", true).put("passed", true);
            Files.write(output.resolve("reader-receipt.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
            System.out.println("PASS: exited Writer's exact stored bytes and actual Service/Controller full/index/background reads match; no scientific factory assembled");
        } finally { new JdbcTemplate(source).execute("SHUTDOWN"); }
    }

    private static byte[] exact(Path expected, String saved, String name) throws Exception {
        byte[] bytes = saved.getBytes(StandardCharsets.UTF_8);
        require(Arrays.equals(Files.readAllBytes(expected), bytes), name + "_BYTES_CHANGED");return bytes;
    }
    private static ObjectNode object(ObjectMapper mapper, byte[] bytes, String name) throws Exception {
        JsonNode parsed = mapper.readTree(bytes);
        if (!(parsed instanceof ObjectNode object)) throw failure(name + "_JSON_OBJECT_REQUIRED");return object;
    }
    private static byte[] get(MockMvc http, String endpoint) throws Exception {
        var response = http.perform(MockMvcRequestBuilders.get(requireNonNull(endpoint))).andReturn().getResponse();
        require(response.getStatus() == 200, "READ_HTTP_FAILED");return response.getContentAsByteArray();
    }
    private static byte[] background(MockMvc http, String endpoint, String id, int index, String sha) throws Exception {
        var response = http.perform(MockMvcRequestBuilders.get(endpoint + "/view/background").param("candidateId", id)
                .param("backgroundIndex", Integer.toString(index)).param("sourceSha256", sha)).andReturn().getResponse();
        require(response.getStatus() == 200, "BACKGROUND_HTTP_FAILED");return response.getContentAsByteArray();
    }
    private static <T> T forbidden(AtomicInteger count) {
        count.incrementAndGet();throw new AssertionError("T01_HISTORY_MUST_NOT_EXECUTE_SCIENTIFIC_RUNTIME");
    }
    private static IllegalStateException failure(String reason) { return new IllegalStateException("T01_HISTORY_" + reason); }
    private static void require(boolean condition, String reason) { if (!condition) throw failure(reason); }
}
