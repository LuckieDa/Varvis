package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Separate-JVM read of actual isolated business records. Reconstructs the real SQL repository,
 * Service and Controller; no scientific runtime, material factory, submit or source recovery runs. */
public final class Pm4Bp3ComplexStoreReader {
    private static final String SCHEMA = "a5-pm4-bp3-complex-store-reader-v1";
    private Pm4Bp3ComplexStoreReader() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "Expected database directory, manifest and output directory");
        Path directory = absolute(args[0]).toRealPath();
        Path manifestPath = regular(args[1]);
        Path output = absolute(args[2]);
        require(Files.isDirectory(directory), "Database directory is unavailable");
        require(Files.isRegularFile(directory.resolve("business-db.mv.db")), "Existing isolated H2 database is required");
        Files.createDirectories(output);
        var mapper = new ObjectMapper().findAndRegisterModules()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode manifest = java.util.Objects.requireNonNull(mapper.readTree(Files.readAllBytes(manifestPath)), "Reader manifest object is required");
        require(manifest.isObject(), "Reader manifest object is required");
        String analysisId = text(manifest, "analysisId");
        require(manifest.path("runs").isArray() && !manifest.path("runs").isEmpty(), "Expected saved runs are required");
        require(manifest.path("originals").isArray(), "Explicit original-file pin list is required");
        var runtimeCalls = new AtomicInteger();
        ObjectNode receipt = mapper.createObjectNode().put("schemaVersion", SCHEMA)
                .put("scope", "SEPARATE_JVM_STORE_SERVICE_CONTROLLER_READ_ONLY")
                .put("scientificQualificationGranted", false).put("submitReuseTested", false)
                .put("analysisId", analysisId).put("manifestRawSha256", sha(manifestPath)).put("status", "IN_PROGRESS");
        var checks = receipt.putArray("runs");
        var originals = receipt.putArray("originals");
        verifyOriginals(manifest, originals);
        var dataSource = new DriverManagerDataSource("jdbc:h2:file:"
                + directory.resolve("business-db").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL;IFEXISTS=TRUE", "sa", "");
        try {
            var properties = new VariantAnalysisProperties();
            properties.setStorageRoot(directory.resolve("jobs"));
            properties.getPersistence().setRetryBackoff(Duration.ZERO);
            var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(dataSource),
                    new DataSourceTransactionManager(dataSource), properties, mapper);
            var store = new AcmgBusinessStore(dataSource);
            var sources = new AcmgExecutionSources(mapper, store);
            var service = new AcmgBusinessService(mapper, jobs, store, sources, () -> {
                runtimeCalls.incrementAndGet(); throw new AssertionError("Saved read must never open an evaluator");
            });
            var http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
            require(jobs.findById(analysisId).isPresent(), "Saved analysis job is unavailable");
            verifySource(mapper, manifest, store, analysisId, receipt);
            Set<String> runIds = new HashSet<>(), scenarios = new HashSet<>();
            for (JsonNode expected : manifest.path("runs")) {
                String runId = text(expected, "runId"), scenario = text(expected, "scenario");
                require(scenario.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}") && !scenario.equals(".."), "Unsafe output scenario");
                require(runIds.add(runId) && scenarios.add(scenario), "Duplicate expected run or scenario");
                JsonNode expectedRun = mapper.readTree(Files.readAllBytes(regular(text(expected, "expectedRunFile"))));
                String expectedPublic = Files.readString(regular(text(expected, "expectedPublicFile")), StandardCharsets.UTF_8);
                String expectedPrivate = Files.readString(regular(text(expected, "expectedPrivateFile")), StandardCharsets.UTF_8);
                var saved = store.run(analysisId, runId).orElseThrow(() -> new IllegalStateException("Expected run missing: " + runId));
                require(mapper.valueToTree(saved).equals(expectedRun), "A complete store.Run field changed: " + scenario);
                require(saved.requestJson().equals(text(expectedRun, "requestJson")), "Stored request bytes changed: " + scenario);
                require(saved.responseJson().equals(expectedPublic) && saved.responseJson().equals(text(expectedRun, "responseJson")),
                        "Stored public response bytes changed: " + scenario);
                require(saved.privateJson().equals(expectedPrivate) && saved.privateJson().equals(text(expectedRun, "privateJson")),
                        "Stored private response bytes changed: " + scenario);
                require(AcmgBusinessStore.sha(saved.responseJson()).equals(saved.responseSha256()), "Stored public digest changed: " + scenario);
                JsonNode response = mapper.readTree(expectedPublic);
                require(response.equals(service.read(analysisId, runId)), "Reconstructed Service read changed: " + scenario);
                var exchange = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysisId, runId)).andReturn();
                require(exchange.getResponse().getStatus() == 200, "Complete Controller GET failed: " + scenario);
                byte[] getBytes = exchange.getResponse().getContentAsByteArray();
                require(response.equals(mapper.readTree(getBytes)), "Complete Controller GET JSON changed: " + scenario);
                Path folder = output.resolve(scenario); Files.createDirectories(folder);
                Files.write(folder.resolve("full-get.json"), getBytes, StandardOpenOption.CREATE_NEW);
                Files.writeString(folder.resolve("full-export.json"), saved.responseJson(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                Files.write(folder.resolve("store-run.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(saved), StandardOpenOption.CREATE_NEW);
                require(expectedPublic.equals(Files.readString(folder.resolve("full-export.json"), StandardCharsets.UTF_8)),
                        "Complete export file differs from saved public string: " + scenario);
                require(mapper.valueToTree(saved).equals(mapper.valueToTree(store.run(analysisId, runId).orElseThrow())),
                        "Read-only Service/Controller mutated the saved run: " + scenario);
                checks.addObject().put("scenario", scenario).put("runId", runId).put("allStoreRunFieldsEqual", true)
                        .put("storePublicPrivateRequestBytesExact", true).put("controllerJsonEqual", true)
                        .put("serviceJsonEqual", true).put("fullExportBytesExact", true)
                        .put("publicSha256", saved.responseSha256()).put("privateSha256", AcmgBusinessStore.sha(saved.privateJson()))
                        .put("requestStringSha256", AcmgBusinessStore.sha(saved.requestJson()));
            }
            var history = http.perform(get("/variant-analyses/{id}/acmg/runs", analysisId)).andReturn();
            require(history.getResponse().getStatus() == 200, "Reconstructed Controller history GET failed");
            byte[] historyBytes = history.getResponse().getContentAsByteArray();
            JsonNode historyJson = mapper.readTree(historyBytes);
            require(historyJson.equals(mapper.valueToTree(service.list(analysisId))), "Controller/Service history disagrees");
            for (String runId : runIds) {
                int matches = 0;
                for (JsonNode item : historyJson) if (runId.equals(item.path("runId").asText())) matches++;
                require(matches == 1, "Expected run missing or duplicated in history: " + runId);
            }
            Files.write(output.resolve("history-get.json"), historyBytes, StandardOpenOption.CREATE_NEW);
            verifyOriginals(manifest, null);
            verifySource(mapper, manifest, store, analysisId, null);
            require(runtimeCalls.get() == 0, "A saved read invoked a runtime");
            receipt.put("storeBytesExact", true).put("controllerJsonEqual", true).put("historyJsonEqual", true)
                    .put("originalsUnchanged", true).put("runtimeCalls", runtimeCalls.get()).put("status", "PASS");
            System.out.println("PASS: separate JVM full store fields, public/private/request bytes, real Service/Controller GET and history; runtime calls 0");
        } catch (Exception | AssertionError failed) {
            receipt.put("status", "FAILED").put("failureType", failed.getClass().getSimpleName())
                    .put("failure", java.util.Objects.toString(failed.getMessage(), "UNKNOWN"));
            throw failed;
        } finally {
            receipt.put("runtimeCalls", runtimeCalls.get());
            try { new JdbcTemplate(dataSource).execute("SHUTDOWN"); }
            finally { Files.write(output.resolve("reader-receipt.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt)); }
        }
    }

    private static void verifySource(ObjectMapper mapper, JsonNode manifest, AcmgBusinessStore store,
            String analysisId, ObjectNode receipt) throws Exception {
        if (!manifest.hasNonNull("expectedSourceFile") && !manifest.hasNonNull("expectedSourceJsonFile")) return;
        var source = store.source(analysisId).orElseThrow(() -> new IllegalStateException("Expected stored execution source is unavailable"));
        require(AcmgBusinessStore.sha(source.json()).equals(source.sha256()), "Stored execution source digest changed");
        if (manifest.hasNonNull("expectedSourceFile")) {
            JsonNode expected = mapper.readTree(Files.readAllBytes(regular(text(manifest, "expectedSourceFile"))));
            require(mapper.valueToTree(source).equals(expected), "Stored source record fields changed");
        }
        if (manifest.hasNonNull("expectedSourceJsonFile"))
            require(source.json().equals(Files.readString(regular(text(manifest, "expectedSourceJsonFile")), StandardCharsets.UTF_8)),
                    "Stored execution source JSON bytes changed");
        if (receipt != null) receipt.put("storedSourceFieldsEqual", true).put("storedSourceSha256", source.sha256());
    }

    private static void verifyOriginals(JsonNode manifest, com.fasterxml.jackson.databind.node.ArrayNode rows) throws Exception {
        for (JsonNode original : manifest.path("originals")) {
            Path path = regular(text(original, "path")); String pin = text(original, "rawSha256");
            require(pin.matches("[0-9a-f]{64}") && pin.equals(sha(path)), "Referenced original bytes changed: " + path);
            if (rows != null) rows.addObject().put("path", path.toString()).put("bytes", Files.size(path)).put("rawSha256", pin);
        }
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field); require(value.isTextual() && !value.asText().isBlank(), "Missing text field: " + field); return value.asText();
    }
    private static Path absolute(String path) {
        Path value = Path.of(path); require(value.isAbsolute(), "Absolute fixture paths are required: " + path); return value.normalize();
    }
    private static Path regular(String path) throws Exception {
        Path value = absolute(path).toRealPath(); require(Files.isRegularFile(value), "Fixture file is unavailable: " + path); return value;
    }
    private static String sha(Path path) throws Exception {
        require(Files.size(path) <= 64L * 1024 * 1024, "Reader input exceeds the bounded fixture size: " + path);
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
