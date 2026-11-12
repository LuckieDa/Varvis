package org.nbscn.varvis.variantanalysis.benchmark.selection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Paths;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBenchmarkSmokeRealFreezeTest {
    @Test
    @EnabledIfSystemProperty(named = "varvis.realSmokeFreeze", matches = "true")
    void freezesAcceptedLocalPhenopacketStoreWithoutRunningBenchmark() throws Exception {
        java.nio.file.Path output = Paths.get(System.getProperty("varvis.smokeOutput"));
        PublicBenchmarkSmokeSetFreezer freezer = new PublicBenchmarkSmokeSetFreezer();
        Map<String,Object> report = freezer.freeze(
                Paths.get(System.getProperty("varvis.phenopacketRoot")), Paths.get(System.getProperty("varvis.benchmarkBed")), output,
                System.getProperty("varvis.phenopacketArchiveSha256"),
                System.getProperty("varvis.benchmarkBedSha256"),
                System.getProperty("varvis.backgroundFingerprint"));
        assertEquals(10377, report.get("inputJsonCount"));
        assertEquals(Boolean.TRUE, report.get("selectionComplete"));
        assertEquals(5, ((java.util.List<?>) report.get("cases")).size());
        assertEquals(Boolean.FALSE, report.get("rankingFieldsUsed"));
        assertEquals(Boolean.FALSE, report.get("publicBenchmarkExecuted"));
        assertTrue(report.get("selectorAlgorithmFingerprint").toString().matches("[0-9a-f]{64}"));
        byte[] first = digest(output.resolve("dataset-manifest.json"));
        freezer.freeze(Paths.get(System.getProperty("varvis.phenopacketRoot")),
                Paths.get(System.getProperty("varvis.benchmarkBed")), output,
                System.getProperty("varvis.phenopacketArchiveSha256"),
                System.getProperty("varvis.benchmarkBedSha256"),
                System.getProperty("varvis.backgroundFingerprint"));
        assertTrue(Arrays.equals(first, digest(output.resolve("dataset-manifest.json"))));
        org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetManifest dataset =
                new org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkManifestCodec()
                        .readDataset(output.resolve("dataset-manifest.json"));
        assertEquals("DEVELOPMENT_SMOKE", dataset.getPurpose());
        assertEquals(Boolean.FALSE, dataset.getEligibleForLockedTest());
    }

    private byte[] digest(java.nio.file.Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
