package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPilotG2DatasetFreezerTest {
    private static final String COMMIT = "5e9d68380f74850fd5865088773fd6aa9af18173";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void freezesApprovedPilotG2IdempotentlyAndRejectsConflicts(@TempDir Path temporary) throws Exception {
        Path root = copyDataset(temporary.resolve("dataset"));
        PublicPilotG2DatasetFreezer freezer = new PublicPilotG2DatasetFreezer();
        Map<String, Object> first = freezer.freeze(root, COMMIT);
        Map<String, Object> second = freezer.freeze(root, COMMIT);
        assertEquals("PASSED", first.get("status"));
        assertEquals(first, second);
        JsonNode manifest = mapper.readTree(root.resolve(
                "truth-masked-hpo-review-v1/approved-g2/pilot-g2-input-manifest.json").toFile());
        assertEquals("READY_IDENTICAL_TO_G1", manifest.path("status").asText());
        assertEquals(30, manifest.path("caseCount").asInt());
        assertEquals(208, manifest.path("observedTermCount").asInt());
        assertEquals(369, manifest.path("excludedTermCount").asInt());
        assertEquals(20000, manifest.path("candidateHardCap").asInt());
        assertEquals(8000, manifest.path("vepCap").asInt());
        assertEquals("NOT_STARTED", manifest.path("pilotExecutionStatus").asText());
        assertFalse(manifest.toString().matches(".*[A-Za-z]:\\\\.*"));
        for (JsonNode item : manifest.path("cases")) {
            assertEquals(item.path("hpoInputChecksum").asText(), item.path("g1HpoInputChecksum").asText());
            assertFalse(item.path("excludedTermsArePositiveInput").asBoolean(true));
            assertEquals(1, item.path("truthCanonicalIdentities").size());
        }
        Path output = root.resolve("truth-masked-hpo-review-v1/approved-g2/pilot-g2-validation.json");
        Files.write(output, "{}\n".getBytes(StandardCharsets.UTF_8));
        IOException conflict = assertThrows(IOException.class, () -> freezer.freeze(root, COMMIT));
        assertTrue(conflict.getMessage().contains("G2_OUTPUT_CONFLICT"));
    }

    @Test
    void rejectsUnapprovedOrWrongIdentityInputs(@TempDir Path temporary) throws Exception {
        Path unapproved = copyDataset(temporary.resolve("unapproved"));
        Path manifestPath = unapproved.resolve("approved-dataset-manifest.json");
        JsonNode manifest = mapper.readTree(manifestPath.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("status", "PROPOSED_NOT_APPROVED");
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        IOException status = assertThrows(IOException.class,
                () -> new PublicPilotG2DatasetFreezer().freeze(unapproved, COMMIT));
        assertTrue(status.getMessage().contains("G2_DATASET_NOT_APPROVED"));

        Path wrong = copyDataset(temporary.resolve("wrong"));
        Path mappingPath = wrong.resolve(
                "truth-masked-hpo-review-v1/internal/DO_NOT_PROVIDE_TO_REVIEWER.mapping.json");
        JsonNode mapping = mapper.readTree(mappingPath.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) mapping.path("mappings").get(0))
                .put("packetFingerprint", String.join("", java.util.Collections.nCopies(64, "0")));
        mapper.writerWithDefaultPrettyPrinter().writeValue(mappingPath.toFile(), mapping);
        IOException identity = assertThrows(IOException.class,
                () -> new PublicPilotG2DatasetFreezer().freeze(wrong, COMMIT));
        assertTrue(identity.getMessage().contains("G2_PACKET_FINGERPRINT_MISMATCH"));
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.freezePilotG2", matches = "true")
    void freezesTrackedPilotG2WhenExplicitlyEnabled() throws Exception {
        Path root = repositoryDataset();
        Map<String, Object> result = new PublicPilotG2DatasetFreezer().freeze(root,
                System.getProperty("varvis.g2SourceCommit"));
        assertEquals("PASSED", result.get("status"));
    }

    private Path copyDataset(Path target) throws IOException {
        Path source = repositoryDataset();
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) Files.createDirectories(destination);
                    else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        }
        return target;
    }

    private Path repositoryDataset() {
        Path repository = Paths.get(System.getProperty("varvis.repoRoot", "..")).toAbsolutePath().normalize();
        return repository.resolve(
                "docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1");
    }
}
