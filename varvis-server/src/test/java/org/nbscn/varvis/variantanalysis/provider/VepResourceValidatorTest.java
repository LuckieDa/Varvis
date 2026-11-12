package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VepResourceValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledVepReportsDisabled() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();

        VepReadinessReport report = new VepResourceValidator(properties).validate();

        assertEquals("DISABLED", report.getStatus());
        assertFalse(report.isEnabled());
    }

    @Test
    void missingExecutableCacheAndFastaReportsUnavailable() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);

        VepReadinessReport report = new VepResourceValidator(properties).validate();

        assertEquals("UNAVAILABLE", report.getStatus());
        assertTrue(report.getMissingResources().contains("vep.resources.GRCh38.executable"));
        assertTrue(report.getMissingResources().contains("vep.resources.GRCh38.cache-dir"));
        assertTrue(report.getMissingResources().contains("vep.resources.GRCh38.fasta"));
    }

    @Test
    void readyResourcesReportReadyForManualSmallRun() throws IOException {
        VariantAnalysisProperties properties = readyProperties(false);

        VepReadinessReport report = new VepResourceValidator(properties).validate();

        assertEquals("READY", report.getStatus());
        assertTrue(report.isExecutableExists());
        assertTrue(report.isCacheDirExists());
        assertTrue(report.isFastaExists());
        assertTrue(report.isFastaIndexExists());
        assertTrue(report.getWarnings().stream().anyMatch(value -> value.contains("execution is disabled")));
    }

    @Test
    void missingFastaIndexKeepsVepUnavailableForRealExecution() throws IOException {
        VariantAnalysisProperties properties = readyProperties(false);
        Files.deleteIfExists(tempDir.resolve("GRCh38.fa.fai"));

        VepReadinessReport report = new VepResourceValidator(properties).validate();

        assertEquals("PARTIAL", report.getStatus());
        assertTrue(report.getMissingResources().contains("vep.resources.GRCh38.fasta.fai"));
    }

    @Test
    void lofteeEnabledRequiresPlugin() throws IOException {
        VariantAnalysisProperties properties = readyProperties(false);
        properties.getVep().setLofteeEnabled(true);
        properties.getVep().setPluginsDir(tempDir.resolve("plugins").toString());
        Files.createDirectories(tempDir.resolve("plugins"));

        VepReadinessReport report = new VepResourceValidator(properties).validate();

        assertEquals("PARTIAL", report.getStatus());
        assertTrue(report.getMissingResources().contains("loftee.plugin"));
    }

    @Test
    void grch37MissingProfileDoesNotFallbackToGrch38() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);

        VepReadinessReport report = new VepResourceValidator(properties).validate(GenomeAssembly.GRCh37);

        assertEquals("UNAVAILABLE", report.getStatus());
        assertTrue(report.getMissingResources().contains("vep.resources.GRCh37"));
        assertTrue(report.getExplanation().contains("RESOURCE_PROFILE_MISSING"));
    }

    @Test
    void configuredGrch37CompressedDownloadPathIsNotRuntimeReady() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(true);
        profile.setAssembly("GRCh37");
        profile.setExecutable(tempDir.resolve("vep").toString());
        profile.setCacheDir(tempDir.resolve("homo_sapiens_vep_116_GRCh37.tar.gz").toString());
        profile.setFasta(tempDir.resolve("Homo_sapiens.GRCh37.fa.gz").toString());
        properties.getVep().getResources().put("GRCh37", profile);

        VepReadinessReport report = new VepResourceValidator(properties).validate(GenomeAssembly.GRCh37);

        assertEquals("PARTIAL", report.getStatus());
        assertTrue(report.getMissingResources().contains("vep.resources.GRCh37.cache-dir.unpacked-directory"));
        assertTrue(report.getMissingResources().contains("vep.resources.GRCh37.fasta.uncompressed-file"));
    }

    private VariantAnalysisProperties readyProperties(boolean executionEnabled) throws IOException {
        Path executable = tempDir.resolve("vep");
        Path cacheDir = tempDir.resolve("cache");
        Path fasta = tempDir.resolve("GRCh38.fa");
        Files.write(executable, new byte[] {1});
        Files.createDirectories(cacheDir);
        Files.write(fasta, new byte[] {1});
        Files.write(tempDir.resolve("GRCh38.fa.fai"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(executionEnabled);
        properties.getVep().setExecutable(executable.toString());
        properties.getVep().setCacheDir(cacheDir.toString());
        properties.getVep().setFasta(fasta.toString());
        return properties;
    }
}
