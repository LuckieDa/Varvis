package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Another JVM's immutable saved-run audit. Uses actual JDBC and read-only Service/Controller paths.
 * No scientific runtime is assembled or available; this is deliberately distinct from CURRENT execution-factory acceptance. */
public final class DeNovoCurrentBusinessReopenReader {
    private static final int MAX_ARCHIVE_BYTES = 16_777_216;
    private DeNovoCurrentBusinessReopenReader() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "DENOVO_REOPEN_ARGUMENTS_REQUIRED");
        Path databaseDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path expectedRoot = Path.of(args[2]).toAbsolutePath().normalize();
        String analysisId = args[1];
        var mapper = Jackson2ObjectMapperBuilder.json().build()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode manifest = mapper.readTree(Files.readAllBytes(expectedRoot.resolve("manifest.json")));
        require(manifest.path("runs").isArray() && !manifest.path("runs").isEmpty(), "DENOVO_REOPEN_RUNS_REQUIRED");
        String expectedSourceSha = manifest.path("expectedSourceSha256").asText();
        require(expectedSourceSha.matches("[a-f0-9]{64}"), "DENOVO_REOPEN_SOURCE_PIN_REQUIRED");
        var source = new DriverManagerDataSource("jdbc:h2:file:" + databaseDirectory.resolve("business-db").toString().replace('\\', '/')
                + ";MODE=MySQL;IFEXISTS=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try {
            var properties = new VariantAnalysisProperties();
            properties.setStorageRoot(databaseDirectory.resolve("jobs"));
            properties.getPersistence().setTablePrefix(""); properties.getPersistence().setRetryBackoff(Duration.ZERO);
            var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(source),
                    new DataSourceTransactionManager(source), properties, mapper);
            var store = new AcmgBusinessStore(source);
            var forbidden = new AtomicInteger();
            var business = new AcmgBusinessService(mapper, jobs, store, new AcmgExecutionSources(mapper, store),
                    () -> forbidden(forbidden, "prediction"), () -> forbidden(forbidden, "ba1"), AcmgRecommendations.unavailable(mapper),
                    () -> forbidden(forbidden, "pm2"), () -> forbidden(forbidden, "pvs1"), () -> forbidden(forbidden, "ps1Pm5"), null);
            MockMvc http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(business)).build();
            var actualSource = store.source(analysisId).orElseThrow();
            require(expectedSourceSha.equals(actualSource.sha256()) && expectedSourceSha.equals(AcmgBusinessStore.sha(actualSource.json())),
                    "DENOVO_REOPEN_PUBLISHED_SOURCE_CHANGED");
            var receipt = mapper.createObjectNode().put("schemaVersion", "denovo-current-business-reopen-v1")
                    .put("rebuildKind", "READ_ONLY_SERVICE_CONTROLLER")
                    .put("currentFactoryReassembly", "NOT_RUN").put("sourceSha256", actualSource.sha256());
            var runs = receipt.putArray("runs"); var names = new HashSet<String>();
            for (var item : manifest.path("runs")) {
                String name = item.path("name").asText(), runId = item.path("runId").asText();
                require(name.matches("[A-Za-z0-9_-]{1,100}") && names.add(name) && !runId.isBlank(), "DENOVO_REOPEN_RUN_IDENTITY_INVALID");
                Path folder = expectedRoot.resolve(name);
                var saved = store.run(analysisId, runId).orElseThrow();
                require(saved.responseJson() != null && saved.privateJson() != null && saved.requestJson() != null,
                        "DENOVO_REOPEN_SAVED_ARTIFACTS_REQUIRED");
                equalStoredBytes(folder, "public", saved.responseJson());
                equalStoredBytes(folder, "private", saved.privateJson());
                equalStoredBytes(folder, "request", saved.requestJson());
                equalStoredBytes(folder, "source", actualSource.json());
                require(AcmgBusinessStore.sha(saved.responseJson()).equals(saved.responseSha256()), "DENOVO_REOPEN_RESPONSE_SHA_CHANGED");
                JsonNode expectedPublic = mapper.readTree(saved.responseJson()), privateData = mapper.readTree(saved.privateJson());
                require(AcmgBusinessStore.sha(saved.privateJson()).equals(expectedPublic.path("privateArtifactSha256").asText()),
                        "DENOVO_REOPEN_PRIVATE_SHA_CHANGED");
                var originals = verifyDeNovoOriginals(mapper, privateData);
                // Read/export through the real saved-result integrity path, never through an evaluator or copied result adapter.
                JsonNode direct = business.read(analysisId, runId);
                require(expectedPublic.equals(direct), "DENOVO_REOPEN_SERVICE_JSON_CHANGED");
                Files.write(folder.resolve("full-export.json"), mapper.writeValueAsBytes(direct));
                String endpoint = "/variant-analyses/" + analysisId + "/acmg/runs/" + runId;
                byte[] exported = httpGet(http, endpoint);
                require(expectedPublic.equals(mapper.readTree(exported)), "DENOVO_REOPEN_CONTROLLER_JSON_CHANGED");
                Files.write(folder.resolve("controller-full-export.json"), exported);
                // Index and one background are actual read-only routes and must retain the saved content without execution.
                JsonNode index = mapper.readTree(httpGet(http, endpoint + "/view"));
                Files.write(folder.resolve("controller-index.json"), mapper.writeValueAsBytes(index));
                for (var candidate : expectedPublic.path("candidates")) {
                    if (!candidate.path("attempts").isArray() || candidate.path("attempts").isEmpty()) continue;
                    String candidateId = candidate.path("candidateId").asText();
                    var viewResponse = http.perform(MockMvcRequestBuilders.get(endpoint + "/view/background")
                            .param("candidateId", candidateId).param("backgroundIndex", "0")
                            .param("sourceSha256", index.path("sourceViewSha256").asText())).andReturn().getResponse();
                    require(viewResponse.getStatus() == 200, "DENOVO_REOPEN_BACKGROUND_HTTP_FAILED");
                    JsonNode view = mapper.readTree(viewResponse.getContentAsByteArray());
                    require(candidate.path("attempts").get(0).equals(view.at("/candidates/0/attempts/0")),
                            "DENOVO_REOPEN_BACKGROUND_CHANGED");
                    Files.write(folder.resolve("controller-background.json"), viewResponse.getContentAsByteArray()); break;
                }
                require(expectedPublic.equals(business.read(analysisId, runId)), "DENOVO_REOPEN_REPEAT_READ_CHANGED");
                var unchanged = store.run(analysisId, runId).orElseThrow();
                require(saved.equals(unchanged), "DENOVO_REOPEN_READ_MUTATED_RUN");
                var result = runs.addObject().put("name", name).put("runId", runId)
                        .put("storedPublicSha256", AcmgBusinessStore.sha(saved.responseJson()))
                        .put("storedPrivateSha256", AcmgBusinessStore.sha(saved.privateJson()))
                        .put("storedRequestSha256", AcmgBusinessStore.sha(saved.requestJson()))
                        .put("controllerExportRawSha256", AcmgBusinessStore.sha(new String(exported, StandardCharsets.UTF_8)))
                        .put("storedBytesUnchanged", true).put("serviceAndControllerJsonEqualsStored", true)
                        .put("persistedExecutionProfilePreserved", expectedPublic.path("executionProfile").isObject());
                result.set("sourceOriginals", originals);
            }
            require(forbidden.get() == 0, "DENOVO_REOPEN_UNEXPECTED_RUNTIME_CALL");
            require(actualSource.equals(store.source(analysisId).orElseThrow()), "DENOVO_REOPEN_READ_MUTATED_SOURCE");
            receipt.put("forbiddenRuntimeCalls", forbidden.get()).put("evaluationCalls", 0)
                    .put("sourceAdapterCalls", 0).put("noCurrentScientificRuntimeAssembled", true)
                    .put("rawStoreBytesAndSourceUnchanged", true).put("passed", true);
            Files.write(expectedRoot.resolve("reopen-receipt.json"), mapper.writeValueAsBytes(receipt));
            System.out.println("PASS: de novo saved public/private/request/source originals and real read-only Service/Controller export in another JVM; no scientific runtime assembled");
        } finally { new JdbcTemplate(source).execute("SHUTDOWN"); }
    }

    private static byte[] httpGet(MockMvc http, String endpoint) throws Exception {
        var response = http.perform(MockMvcRequestBuilders.get(requireNonNull(endpoint))).andReturn().getResponse();
        require(response.getStatus() == 200, "DENOVO_REOPEN_HTTP_FAILED:" + response.getStatus());
        return response.getContentAsByteArray();
    }

    private static void equalStoredBytes(Path folder, String kind, String actual) throws Exception {
        byte[] expected = Files.readAllBytes(folder.resolve("expected-" + kind + ".json"));
        byte[] bytes = actual.getBytes(StandardCharsets.UTF_8);
        require(java.util.Arrays.equals(expected, bytes), "DENOVO_REOPEN_" + kind.toUpperCase(java.util.Locale.ROOT) + "_BYTES_CHANGED");
        Files.write(folder.resolve("actual-" + kind + ".json"), bytes);
    }

    private static ObjectNode verifyDeNovoOriginals(ObjectMapper mapper, JsonNode privateData) throws Exception {
        var receipt = mapper.createObjectNode();
        var originals = privateData.at("/localBatch/modules/deNovo/originalsByRawSha256");
        if (originals.isMissingNode()) return receipt.put("status", "NO_DENOVO_PRIVATE_MODULE").put("retainedOriginalCount", 0);
        require(originals.isObject(), "DENOVO_REOPEN_ORIGINALS_SHAPE_INVALID");
        int retained = 0, coreArchives = 0, nestedOriginals = 0;
        for (var fields = originals.properties().iterator(); fields.hasNext();) {
            var field = fields.next(); byte[] raw = checkedOriginal(field.getKey(), field.getValue()); retained++;
            if (raw.length == 0 || raw[0] != '{') continue;
            JsonNode document;
            try { document = mapper.readTree(raw); }
            catch (com.fasterxml.jackson.core.JsonProcessingException notJson) { continue; }
            for (var owner : document.findParents("coreAuditArchive")) {
                var archive = owner.path("coreAuditArchive");
                require("gzip+base64".equals(archive.path("encoding").asText()), "DENOVO_REOPEN_CORE_ARCHIVE_ENCODING_INVALID");
                byte[] decoded;
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(archive.path("payload").asText())))) {
                    decoded = gzip.readNBytes(MAX_ARCHIVE_BYTES + 1);
                }
                require(decoded.length <= MAX_ARCHIVE_BYTES && decoded.length == archive.path("uncompressedBytes").asInt(-1)
                                && sha(decoded).equals(archive.path("sha256").asText()), "DENOVO_REOPEN_CORE_ARCHIVE_SHA_INVALID");
                JsonNode audit = mapper.readTree(decoded), nested = audit.path("originalsBySha256");
                require(nested.isObject(), "DENOVO_REOPEN_CORE_ORIGINALS_REQUIRED");
                for (var nestedFields = nested.properties().iterator(); nestedFields.hasNext();) {
                    var original = nestedFields.next(); checkedOriginal(original.getKey(), original.getValue()); nestedOriginals++;
                }
                for (var facts : owner.findValues("coreEvaluation")) for (var pin : facts.path("sourcePins"))
                    require(nested.has(pin.path("sha256").asText()), "DENOVO_REOPEN_CORE_SOURCE_PIN_MISSING");
                coreArchives++;
            }
        }
        return receipt.put("status", "RAW_BYTES_SHA256_VERIFIED").put("retainedOriginalCount", retained)
                .put("coreArchiveCount", coreArchives).put("nestedOriginalCount", nestedOriginals);
    }

    private static byte[] checkedOriginal(String pin, JsonNode encoded) throws Exception {
        require(pin.matches("[a-f0-9]{64}") && encoded.isTextual(), "DENOVO_REOPEN_ORIGINAL_IDENTITY_INVALID");
        byte[] bytes = Base64.getDecoder().decode(encoded.asText());
        require(bytes.length <= MAX_ARCHIVE_BYTES && pin.equals(sha(bytes)), "DENOVO_REOPEN_ORIGINAL_SHA_INVALID");
        return bytes;
    }
    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static <T> T forbidden(AtomicInteger count, String name) {
        count.incrementAndGet(); throw new AssertionError("DENOVO_HISTORY_MUST_NOT_EXECUTE_" + name);
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
