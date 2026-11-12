package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt-in, small archived JSON only. No saved data or machine paths in Git. */
class PredictionEligibilityHistoryTest {
    @Test @EnabledIfSystemProperty(named = "eligibility.history.original", matches = ".+")
    void archivedCalibrationAndFormattedDownloadRemainIdenticalAndUnapplied() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path original = Path.of(System.getProperty("eligibility.history.original"));
        Path download = Path.of(System.getProperty("eligibility.history.download"));
        byte[] bytes = Files.readAllBytes(original), downloaded = Files.readAllBytes(download);
        assertEquals(System.getProperty("eligibility.history.originalSha"), PredictionCalibration.sha256(bytes));
        assertEquals(System.getProperty("eligibility.history.downloadSha"), PredictionCalibration.sha256(downloaded));
        JsonNode saved = mapper.readTree(bytes); assertEquals(saved, mapper.readTree(downloaded));
        JsonNode before = saved.deepCopy(); assertEquals(2, saved.path("candidates").size());
        String[] directions = {"UNINFORMATIVE", "BENIGN"}, strengths = {"NONE", "SUPPORTING"};
        for (int i = 0; i < 2; i++) {
            JsonNode prediction = saved.path("candidates").get(i).at("/attempts/0/prediction");
            var view = PredictionCalibrationView.read(prediction.at("/mechanisms/PROTEIN_MISSENSE"));
            assertEquals("", view.issue()); assertEquals(directions[i], view.direction()); assertEquals(strengths[i], view.sourceStrength());
            for (String criterion : List.of("PP3", "BP4")) {
                assertEquals("NOT_EVALUATED", prediction.path("criteria").path(criterion).path("status").asText());
                assertEquals("NONE", prediction.path("criteria").path(criterion).path("appliedStrength").asText());
            }
            assertFalse(prediction.at("/stages/applicationReady").asBoolean());
        }
        assertEquals(before, saved); assertArrayEquals(bytes, Files.readAllBytes(original)); assertArrayEquals(downloaded, Files.readAllBytes(download));
    }

    @Test @EnabledIfSystemProperty(named = "eligibility.receipt.directory", matches = ".+")
    void emitRepresentativeTestOnlyReceipts() throws Exception {
        var f = new PredictionEligibilityFixtures(); Path directory = Path.of(System.getProperty("eligibility.receipt.directory"));
        assertTrue(Files.isDirectory(directory));
        write(f, directory, "synthetic-pass", f.result(0, "BP4"));
        f.candidate(0).putArray("hypotheses"); f.seal(); write(f, directory, "synthetic-context-missing", f.result(0, "BP4"));
        f = new PredictionEligibilityFixtures(); f.settings.withObject("/catalog").putArray("entries"); f.seal(); write(f, directory, "synthetic-catalog-missing", f.result(0, "BP4"));
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/compatibilities/TEST_ONLY_COMPAT")).putArray("observationSha256s"); f.seal();
        write(f, directory, "synthetic-compatibility-outside-scope", f.result(0, "BP4"));
        f = new PredictionEligibilityFixtures(); ((ObjectNode) f.settings.at("/effectSnapshots/TEST_ONLY_0")).withArray("effects").addObject()
                .put("kind", "SAME_EFFECT").put("referenceId", "TEST_ONLY").put("sourceSha256", "b".repeat(64)).put("equivalence", "VERIFIED").put("adoption", "ADOPTED");
        f.seal(); write(f, directory, "synthetic-duplicate-adoption", f.result(0, "BP4"));
    }
    private static void write(PredictionEligibilityFixtures f, Path directory, String name, ObjectNode result) throws Exception {
        ObjectNode wrapper = f.mapper.createObjectNode().put("dataClass", "TEST_ONLY").put("realApplicationSuccess", false);
        wrapper.set("receipt", result); f.mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), wrapper);
    }
}
