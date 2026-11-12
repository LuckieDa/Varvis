package org.nbscn.varvis.variantanalysis.benchmark.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBenchmarkPilotRealFreezeTest {
    @Test
    @EnabledIfSystemProperty(named = "varvis.realPilotFreeze", matches = "true")
    void freezesAcceptedLocalPilotProposalWithoutExecutingCases() throws Exception {
        Path output = Paths.get(System.getProperty("varvis.pilotOutput"));
        PublicBenchmarkPilotProposalFreezer freezer = new PublicBenchmarkPilotProposalFreezer();
        Map<String, Object> report = freezer.freeze(
                Paths.get(System.getProperty("varvis.phenopacketRoot")),
                Paths.get(System.getProperty("varvis.benchmarkBed")),
                Paths.get(System.getProperty("varvis.smokeDataset")),
                Paths.get(System.getProperty("varvis.smokeAcceptance")),
                output,
                System.getProperty("varvis.phenopacketArchiveSha256"),
                System.getProperty("varvis.benchmarkBedSha256"),
                System.getProperty("varvis.hpoSha256"),
                System.getProperty("varvis.backgroundFingerprint"));
        assertEquals(10377, report.get("inputJsonCount"));
        assertEquals(Boolean.TRUE, report.get("selectionComplete"));
        assertEquals(30, ((java.util.List<?>) report.get("cases")).size());
        assertEquals(Boolean.FALSE, report.get("rankingFieldsUsed"));
        assertEquals(Boolean.FALSE, report.get("publicPilotExecuted"));
        JsonNode manifest = new ObjectMapper().readTree(output.resolve("dataset-proposal-manifest.json").toFile());
        assertEquals("PROPOSED_NOT_APPROVED", manifest.path("status").asText());
        assertEquals("NOT_STARTED", manifest.path("executionStatus").asText());
        assertEquals(25, manifest.path("mainCaseCount").asInt());
        assertEquals(5, manifest.path("backupCaseCount").asInt());
        assertEquals(30, manifest.path("cases").size());
        try (Stream<Path> files = Files.list(output.resolve("truth-masked-hpo-review-v1/reviewer-visible"))) {
            assertEquals(30, files.filter(path -> path.getFileName().toString()
                    .endsWith(".packet.json")).count());
        }
        assertFalse(new String(Files.readAllBytes(output.resolve("dataset-proposal-manifest.json")),
                java.nio.charset.StandardCharsets.UTF_8).matches("(?s).*\\b[A-Za-z]:[\\\\/].*"));
        byte[] first = digest(output.resolve("dataset-proposal-manifest.json"));
        freezer.freeze(Paths.get(System.getProperty("varvis.phenopacketRoot")),
                Paths.get(System.getProperty("varvis.benchmarkBed")),
                Paths.get(System.getProperty("varvis.smokeDataset")),
                Paths.get(System.getProperty("varvis.smokeAcceptance")), output,
                System.getProperty("varvis.phenopacketArchiveSha256"),
                System.getProperty("varvis.benchmarkBedSha256"),
                System.getProperty("varvis.hpoSha256"),
                System.getProperty("varvis.backgroundFingerprint"));
        assertTrue(Arrays.equals(first, digest(output.resolve("dataset-proposal-manifest.json"))));
    }

    private byte[] digest(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
