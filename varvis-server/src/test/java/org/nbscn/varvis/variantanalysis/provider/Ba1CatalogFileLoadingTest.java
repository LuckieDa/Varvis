package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in file loading/selection only. Expectations never authorize publication. No network or database. */
class Ba1CatalogFileLoadingTest {
    private static final String PREFIX = "varvis.ba1.catalog.test.";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void configuredFileUsesProductionLoaderAndSelector() throws Exception {
        var configuration = configuration(System.getProperties());
        assumeTrue(!configuration.isEmpty(), "No BA1 catalog file/SHA/expectations configured; file acceptance NOT RUN");
        Path path = Path.of(configuration.get(0));
        String sha = configuration.get(1);
        var expected = mapper.readTree(Files.readAllBytes(Path.of(configuration.get(2))));
        var catalog = Ba1SpecificationCatalog.load(mapper, path, sha);
        var document = mapper.readTree(Files.readAllBytes(path));
        verifyExpectationBinding(expected, document, sha);
        var identity = catalog.identity();
        assertEquals(sha, identity.path("catalogSha256").asText());
        assertEquals(expected.at("/identity/status"), identity.path("status"));
        if (expected.at("/identity/status").asText().equals("READY")) {
            assertEquals("REVIEWED", document.at("/metadata/batchReview/status").asText());
            assertTrue(expected.path("reviewReceiptSha256").asText().matches("[0-9a-f]{64}"), "Expected review receipt pin is required");
            assertEquals(expected.path("reviewReceiptSha256"), document.at("/metadata/batchReview/reviewReceiptSha256"));
        } else {
            assertEquals("UNAVAILABLE", expected.at("/identity/status").asText());
            assertEquals(expected.at("/identity/reasonCode"), identity.path("reasonCode"));
        }
        assertTrue(expected.path("selections").isArray() && !expected.path("selections").isEmpty(), "No selection expectations");
        var results = mapper.createArrayNode();
        for (JsonNode example : expected.path("selections")) {
            assertTrue(example.path("mapping").isObject(), "Mapping fixture must be an object");
            assertTrue(example.has("context"), "Declare context explicitly, including null for absence");
            var selection = catalog.select(example.path("mapping"), example.path("context").isNull() ? null : example.path("context"));
            JsonNode checks = example.path("checks");
            assertTrue(checks.isObject() && checks.has("/status") && checks.has("/reasonCode"), "Expected status and reason are required");
            var fields = checks.fieldNames();
            while (fields.hasNext()) {
                String pointer = fields.next();
                assertTrue(pointer.startsWith("/"), "Expected JSON pointer");
                assertEquals(checks.path(pointer), selection.at(pointer), pointer);
            }
            results.addObject().put("status", selection.path("status").asText()).put("reasonCode", selection.path("reasonCode").asText());
        }
        var wrongSha = assertThrows(IllegalArgumentException.class,
                () -> Ba1SpecificationCatalog.load(mapper, path, "0".repeat(64)));
        assertEquals("BA1_CATALOG_FILE_IDENTITY_MISMATCH", wrongSha.getMessage());
        var report = mapper.createObjectNode().put("testBoundary", "FILE_LOADING_AND_SELECTION_ONLY_NOT_SEMANTIC_APPROVAL")
                .put("runtimeVersion", Ba1SpecificationCatalog.VERSION).put("wrongShaRejected", true);
        report.set("identity", identity); report.set("selections", results);
        System.out.println(report);
    }

    @Test void partialConfigurationFailsInsteadOfSkipping() {
        assertTrue(configuration(new Properties()).isEmpty());
        for (String key : List.of("path", "sha256", "expectations")) {
            var properties = new Properties(); properties.setProperty(PREFIX + key, "present");
            assertThrows(IllegalArgumentException.class, () -> configuration(properties));
        }
        var properties = new Properties();
        for (String key : List.of("path", "sha256", "expectations")) properties.setProperty(PREFIX + key, "present");
        assertThrows(IllegalArgumentException.class, () -> configuration(properties));
        properties.setProperty(PREFIX + "sha256", "a".repeat(64));
        assertEquals(3, configuration(properties).size());
    }

    @Test void expectationIdentityMismatchFailsInsteadOfSkipping() throws Exception {
        var document = mapper.readTree("{\"metadata\":{\"compilerVersion\":\"1.1.1\"}}");
        var expected = mapper.createObjectNode().put("catalogFileSha256", "a".repeat(64));
        expected.putObject("expectedMetadata").put("compilerVersion", "1.1.1");
        verifyExpectationBinding(expected, document, "a".repeat(64));
        assertThrows(AssertionError.class, () -> verifyExpectationBinding(expected, document, "b".repeat(64)));
        ((ObjectNode) expected.path("expectedMetadata")).put("compilerVersion", "wrong-compiler");
        assertThrows(AssertionError.class, () -> verifyExpectationBinding(expected, document, "a".repeat(64)));
    }

    private static List<String> configuration(Properties properties) {
        var values = List.of(properties.getProperty(PREFIX + "path", ""), properties.getProperty(PREFIX + "sha256", ""),
                properties.getProperty(PREFIX + "expectations", ""));
        int missing = 0;
        for (String value : values) if (value == null || value.isBlank()) missing++;
        if (missing == values.size()) return List.of();
        if (missing != 0 || !values.get(1).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("BA1_FILE_TEST_CONFIGURATION_INCOMPLETE_OR_INVALID");
        return values;
    }

    private static void verifyExpectationBinding(JsonNode expected, JsonNode document, String sha) {
        assertEquals(sha, expected.path("catalogFileSha256").asText(), "Expectation file belongs to another catalog");
        JsonNode bindings = expected.path("expectedMetadata");
        assertTrue(bindings.isObject() && !bindings.isEmpty(), "Expected metadata identity is required");
        var fields = bindings.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            assertEquals(bindings.path(field), document.path("metadata").path(field), "Expected metadata: " + field);
        }
    }
}
