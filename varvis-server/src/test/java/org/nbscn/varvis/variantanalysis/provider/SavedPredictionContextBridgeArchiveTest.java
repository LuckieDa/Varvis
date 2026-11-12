package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** One opt-in saved-source replay. No export, runtime services, queries or synthetic declarations. */
class SavedPredictionContextBridgeArchiveTest {
    @Test @EnabledIfSystemProperty(named = "context.bridge.archive.manifest", matches = ".+")
    void emptyExplicitInputRetainsFourSavedS0PassS1MissingReceipts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path output = Path.of(System.getProperty("context.bridge.archive.output"));
        assertTrue(Files.isDirectory(output));
        Files.writeString(output.resolve("archive-started.json"), "{\"batchLimit\":1,\"started\":true}", StandardOpenOption.CREATE_NEW);
        ObjectNode receipt = mapper.createObjectNode().put("bridgeVersion", SavedPredictionContextBridge.VERSION)
                .put("verifyCalls", 0).put("assessCalls", 0).put("explicitInputs", 0).put("status", "FAILED");
        var before = new LinkedHashMap<Path, String>();
        try {
            Path manifestPath = Path.of(System.getProperty("context.bridge.archive.manifest"));
            byte[] bytes = bounded(manifestPath);
            assertEquals(System.getProperty("context.bridge.archive.manifestSha"), PredictionCalibration.sha256(bytes));
            JsonNode manifest = mapper.readTree(bytes); assertNotNull(manifest);
            Map<String, SavedPredictionApplicationInputs.Artifact> artifacts = new LinkedHashMap<>();
            Map<String, String> pins = new LinkedHashMap<>();
            for (var field : manifest.path("artifacts").properties()) {
                Path source = Path.of(field.getValue().path("path").asText()); byte[] raw = bounded(source);
                String pin = field.getValue().path("sha256").asText(); assertEquals(pin, PredictionCalibration.sha256(raw));
                JsonNode tree = mapper.readTree(raw); assertNotNull(tree); before.put(source, pin);
                artifacts.put(field.getKey(), new SavedPredictionApplicationInputs.Artifact(raw, tree)); pins.put(field.getKey(), pin);
            }
            assertEquals(Set.of("run", "request", "private", "parent", "publication", "calibrationAdoption", "calibrationArchive.parent",
                    "calibrationArchive.request", "calibrationArchive.selections", "calibrationArchive.job", "calibrationArchive.candidates"), artifacts.keySet());
            Path settingsPath = Path.of(System.getProperty("context.bridge.archive.settings"));
            ObjectNode settings = (ObjectNode) mapper.readTree(bounded(settingsPath)); assertNotNull(settings);
            var hashes = new RuntimeEvidenceV2Adapter(mapper);
            assertEquals(manifest.path("settingsSha256").asText(), hashes.canonicalSha256(SavedPredictionApplicationInputs.semanticSettings(settings)));
            for (JsonNode candidate : settings.path("candidates")) assertTrue(candidate.path("hypotheses").isEmpty());
            var supply = new SavedPredictionApplicationInputs.Supply(System.getProperty("context.bridge.archive.supplyId"), pins, manifest.path("settingsSha256").asText());
            receipt.put("verifyCalls", 1);
            var saved = SavedPredictionApplicationInputs.verify(mapper, artifacts, settings, supply);
            assertEquals("", saved.issue());
            var prepared = new SavedPredictionContextBridge().prepare(mapper, saved, SavedPredictionContextBridge.TrustedContextSupply.empty());
            receipt.set("preparation", mapper.valueToTree(prepared.entries())); receipt.set("bridgeIdentity", prepared.identity());
            receipt.put("assessCalls", 1);
            var results = new PredictionApplicationEligibility(mapper).assess(saved, prepared);
            var array = receipt.putArray("receipts"); results.forEach(result -> array.add(result.json()));
            assertEquals(4, results.size());
            for (JsonNode result : array) {
                assertEquals("PASS", result.at("/stages/0/status").asText());
                assertEquals("BLOCKED", result.at("/stages/1/status").asText());
                assertEquals("EXPLICIT_CONTEXT_MISSING", result.path("reasonCode").asText());
                for (int stage = 2; stage <= 6; stage++) assertEquals("NOT_CHECKED", result.at("/stages/" + stage + "/status").asText());
                assertEquals("COMPLETE", result.at("/stages/7/status").asText());
                assertEquals("NONE", result.path("appliedStrength").asText()); assertEquals("NONE", result.path("proposedApplicationStrength").asText());
                assertFalse(result.path("applicationReady").asBoolean()); assertFalse(result.path("formalEnabled").asBoolean());
                assertFalse(result.path("criterionEvaluationCreated").asBoolean());
                assertEquals("DECLARATION_NOT_SUPPLIED", result.at("/contextPreparation/blockers/0/kind").asText());
            }
            for (var source : before.entrySet()) assertEquals(source.getValue(), PredictionCalibration.sha256(bounded(source.getKey())));
            receipt.put("status", "EXPECTED_MISSING_CONTEXT_VERIFIED").put("originalsUnchanged", true);
        } catch (Exception | AssertionError failure) {
            receipt.put("failureType", failure.getClass().getSimpleName());
            throw failure; // The structured failure is retained and JUnit must fail, never catch-and-pass.
        } finally {
            Files.write(output.resolve("native-sidecar.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
        }
    }
    private static byte[] bounded(Path path) throws Exception {
        assertTrue(Files.size(path) <= 4 * 1024 * 1024, "Archive input exceeds the bounded JSON limit");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1); assertTrue(bytes.length <= 4 * 1024 * 1024); return bytes;
        }
    }
}
