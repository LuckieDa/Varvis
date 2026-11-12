package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.controller.SplicingExperimentCheckController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Opt-in historical real outputs, fresh file-H2 copies, integrated controllers, no new evaluation. */
@EnabledIfSystemProperty(named = "varvis.joint.history.enabled", matches = "true")
class JointHistoricalSavedReadTest {
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final Map<String, AtomicInteger> factories = new LinkedHashMap<>();
    private int httpGets;

    @Test void archivedResultsAndExperimentVersionsSurviveIntegratedServiceReconstruction() throws Exception {
        Path archive = Path.of(Objects.requireNonNull(System.getProperty("varvis.joint.history.archive-root"),
                "varvis.joint.history.archive-root is required")).toAbsolutePath().normalize();
        Path manifest = archive.resolve(System.getProperty("varvis.joint.history.cases-file", "historical-read-cases.json"));
        ObjectNode cases = readObject(manifest);
        assertEquals("joint-historical-read-cases-v1", cases.path("schemaVersion").asText());
        Set<String> required = Set.of("pm2-vhl", "pvs1-hnf1a", "ps1-pm5-v1", "ps1-pm5-v2",
                "bp7-lmna-history", "bp7-cftr-success", "bp7-cftr-failure");
        var present = new java.util.HashSet<String>();
        for (JsonNode item : cases.path("cases")) assertTrue(present.add(item.path("id").asText()));
        assertEquals(required, present, "The historical compatibility cases must not silently disappear");
        for (String name : Set.of("prediction", "ba1", "pm2", "pvs1", "ps1Pm5"))
            factories.put(name, new AtomicInteger());
        Path parent = Path.of(System.getProperty("varvis.joint.history.output-root", "target/joint-historical-read"))
                .toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path output = Files.createDirectory(parent.resolve("attempt-" + UUID.randomUUID()));
        ObjectNode report = mapper.createObjectNode().put("status", "RUNNING")
                .put("environment", "INTEGRATED_MOCKMVC_FILE_H2_COPY").put("manifestSha256", sha(manifest))
                .put("newEvaluationPostExecuted", false).put("pageCovered", false);
        var results = report.putArray("cases");
        try {
            for (JsonNode item : cases.path("cases")) verifyCase(item, output, results.addObject());
            assertTrue(factories.values().stream().allMatch(value -> value.get() == 0));
            report.put("status", "PASSED").put("completeHistoricalResponsesPreserved", true);
        } catch (Exception | AssertionError failure) {
            report.put("status", "FAILED").put("failure", failure.toString());
            throw failure;
        } finally {
            report.put("actualHttpGetRequests", httpGets);
            ObjectNode counts = report.putObject("runtimeFactoryCalls");
            factories.forEach((name, count) -> counts.put(name, count.get()));
            Files.write(output.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                    StandardOpenOption.CREATE_NEW);
            System.out.println("JOINT_HISTORICAL_READ=" + output);
        }
    }

    private void verifyCase(JsonNode item, Path output, ObjectNode result) throws Exception {
        String id = item.path("id").asText();
        Path originalDatabase = verifiedFile(item.path("database"));
        Path originalSaved = verifiedFile(item.path("saved"));
        verifiedFile(item.path("provenance"));
        ObjectNode expected = readObject(originalSaved);
        String analysis = expected.path("analysisId").asText(), run = expected.path("runId").asText();
        assertFalse(analysis.isBlank()); assertFalse(run.isBlank());
        Path directory = Files.createDirectory(output.resolve(id));
        copyLocked(originalDatabase, directory.resolve("business-db.mv.db"), item.path("database").path("sha256").asText());
        Path checksRoot = directory.resolve("checks");
        for (JsonNode check : item.path("checks")) {
            Path original = verifiedFile(check);
            String checkId = check.path("checkId").asText();
            assertTrue(checkId.matches("[a-f0-9]{64}"));
            Path destination = checksRoot.resolve(AcmgBusinessStore.sha(analysis + "\n" + run)).resolve(checkId + ".json");
            Files.createDirectories(Objects.requireNonNull(destination.getParent()));
            copyLocked(original, destination, check.path("sha256").asText());
        }
        result.put("id", id).put("analysisId", analysis).put("runId", run)
                .put("sourceTip", item.path("sourceTip").asText()).put("sourceSavedSha256", sha(originalSaved))
                .put("sourceDatabaseSha256", sha(originalDatabase));
        var phases = result.putArray("phases");
        String url = "jdbc:h2:file:" + directory.resolve("business-db").toString().replace('\\', '/')
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;IFEXISTS=TRUE";
        for (int phase = 0; phase < 2; phase++) {
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            try {
                var properties = new VariantAnalysisProperties();
                properties.setStorageRoot(directory.resolve("jobs"));
                properties.getPersistence().setTablePrefix("");
                properties.getPersistence().setRetryBackoff(Duration.ZERO);
                var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(dataSource),
                        new DataSourceTransactionManager(dataSource), properties, mapper);
                var store = new AcmgBusinessStore(dataSource);
                var business = new AcmgBusinessService(mapper, jobs, store, new AcmgExecutionSources(mapper, store),
                        () -> forbidden("prediction"), () -> forbidden("ba1"), AcmgRecommendations.unavailable(mapper),
                        () -> forbidden("pm2"), () -> forbidden("pvs1"), () -> forbidden("ps1Pm5"), null);
                // Historical reads must not require the current experiment catalog to be available.
                var checks = new SplicingExperimentChecks(mapper,
                        new ClassPathResource("acmg/absent-historical-read-catalog.json"), checksRoot, business::read);
                MockMvc http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(business),
                        new SplicingExperimentCheckController(checks)).build();
                String endpoint = "/variant-analyses/" + analysis + "/acmg/runs/" + run;
                Path phaseOutput = Files.createDirectory(directory.resolve("phase-" + phase));
                assertSameJson(expected, httpGet(http, endpoint, 200, phaseOutput.resolve("saved-get.json")), id);
                assertSameJson(expected, business.read(analysis, run), id + "/direct-read");
                if (item.path("checks").isArray()) verifyChecks(item.path("checks"), http, endpoint, phaseOutput);
                phases.addObject().put("phase", phase).put("actualGetExecuted", true)
                        .put("completeResponseIdentical", true).put("runId", run);
            } finally {
                new JdbcTemplate(dataSource).execute("SHUTDOWN");
            }
        }
        verifiedFile(item.path("database")); verifiedFile(item.path("saved")); verifiedFile(item.path("provenance"));
        for (JsonNode check : item.path("checks")) verifiedFile(check);
        result.put("originalBytesUnchanged", true).put("rebuiltReadPassed", true);
    }

    private void verifyChecks(JsonNode originals, MockMvc http, String base, Path output) throws Exception {
        String endpoint = base + "/splicing-checks";
        JsonNode rows = httpGet(http, endpoint, 200, output.resolve("check-versions.json"));
        assertTrue(rows.isArray()); assertEquals(originals.size(), rows.size());
        for (JsonNode original : originals) {
            String id = original.path("checkId").asText();
            JsonNode matched = null;
            for (JsonNode row : rows) if (id.equals(row.path("checkId").asText())) {
                assertNull(matched, "Duplicate historical check version"); matched = row;
            }
            if (matched == null) throw new AssertionError("Missing historical check: " + id);
            assertEquals(original.path("availability"), matched.path("availability"));
            if ("UNAVAILABLE".equals(original.path("availability").asText())) {
                httpGet(http, endpoint + "/" + id, 503, output.resolve(id + "-unavailable.json"));
                continue;
            }
            ObjectNode expected = readObject(verifiedFile(original));
            assertSameJson(expected, httpGet(http, endpoint + "/" + id, 200, output.resolve(id + ".json")), id);
            assertSameJson(expected, httpGet(http, endpoint + "/" + id + "/export", 200,
                    output.resolve(id + "-export.json")), id + "/export");
        }
    }

    private JsonNode httpGet(MockMvc http, String endpoint, int expectedStatus, Path output) throws Exception {
        int before = factories.values().stream().mapToInt(value -> Objects.requireNonNull(value).get()).sum();
        var response = http.perform(get(Objects.requireNonNull(endpoint))).andReturn().getResponse(); httpGets++;
        byte[] bytes = response.getContentAsByteArray();
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
        ObjectNode event = mapper.createObjectNode().put("method", "GET").put("endpoint", endpoint)
                .put("statusCode", response.getStatus()).put("bodySha256", sha(output))
                .put("runtimeFactoryIncrement", factories.values().stream().mapToInt(value -> Objects.requireNonNull(value).get()).sum() - before);
        Files.write(output.resolveSibling(output.getFileName() + ".event.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(event), StandardOpenOption.CREATE_NEW);
        assertEquals(expectedStatus, response.getStatus(), endpoint);
        assertEquals(0, event.path("runtimeFactoryIncrement").asInt());
        JsonNode parsed = bytes.length == 0 ? mapper.nullNode() : mapper.readTree(bytes);
        return Objects.requireNonNull(parsed);
    }

    private <T> T forbidden(String name) {
        Objects.requireNonNull(factories.get(name)).incrementAndGet();
        throw new AssertionError("Historical GET must not construct " + name + " runtime");
    }

    private ObjectNode readObject(Path path) throws IOException {
        JsonNode value = mapper.readTree(Files.readAllBytes(path));
        if (!(value instanceof ObjectNode object)) throw new IOException("Expected object: " + path);
        return object;
    }

    private Path verifiedFile(JsonNode item) throws Exception {
        assertTrue(item.path("path").isTextual()); assertTrue(item.path("sha256").asText().matches("[a-f0-9]{64}"));
        Path path = Path.of(item.path("path").asText()).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), path.toString()); assertEquals(item.path("sha256").asText(), sha(path), path.toString());
        return path;
    }

    private static void copyLocked(Path source, Path target, String expectedSha256) throws Exception {
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             var lock = input.tryLock(0, Long.MAX_VALUE, true)) {
            if (lock == null) throw new IOException("Archived source is still locked: " + source);
            assertTrue(lock.isValid());
            try (FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.allocate(65536);
                while (input.read(buffer) != -1) {
                    buffer.flip(); while (buffer.hasRemaining()) output.write(buffer); buffer.clear();
                }
            }
            assertEquals(expectedSha256, sha(target));
        }
        assertEquals(expectedSha256, sha(source));
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
    }

    private static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void assertSameJson(JsonNode expected, JsonNode actual, String at) {
        if (expected.isNumber() && actual.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), at); return;
        }
        if (expected.isObject()) {
            assertTrue(actual.isObject(), at); assertEquals(expected.size(), actual.size(), at);
            expected.fieldNames().forEachRemaining(field -> assertSameJson(expected.path(field), actual.path(field), at + "/" + field));
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), at); assertEquals(expected.size(), actual.size(), at);
            for (int i = 0; i < expected.size(); i++) assertSameJson(expected.path(i), actual.path(i), at + "/" + i);
        } else assertEquals(expected, actual, at);
    }
}
