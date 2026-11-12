package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisPreflightResponse;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.engine.ExomiserRuntimeValidator;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAnalysisPreflightServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void databaseMismatchIsARequiredBlockerEvenWhenExomiserIsReady() throws IOException {
        var properties = exomiserProperties();
        var repository = org.mockito.Mockito.mock(
                org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository.class);
        org.mockito.Mockito.doThrow(new org.nbscn.varvis.exception.VariantAnalysisPersistenceException(
                "ANALYSIS_DATABASE_SCHEMA_INCOMPATIBLE", "schema mismatch", null))
                .when(repository).verifyPersistenceSchema();
        var response = new VariantAnalysisPreflightService(properties, new VepResourceValidator(properties),
                new ExomiserRuntimeValidator(), repository).check("GRCh38");
        assertFalse(response.isApplicationReady());
        assertTrue(response.getBlockingErrors().stream().anyMatch(s -> s.contains("ANALYSIS_DATABASE_SCHEMA_INCOMPATIBLE")));
        assertTrue(response.getRequiredComponents().stream()
                .anyMatch(c -> "Analysis database".equals(c.getComponent()) && !c.isReady()));
    }

    @Test
    void grch37RequestBlocksWhenDefaultPolicyWouldAnalyzeGrch38() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir.resolve("storage"));

        VariantAnalysisPreflightResponse response = service(properties).check("GRCh37");

        assertFalse(response.isApplicationReady());
        assertEquals("GRCh37", response.getRequestedInputAssembly());
        assertEquals("GRCh38", response.getEffectiveAnalysisAssembly());
        assertTrue(response.isLiftoverRequired());
        assertFalse(response.isLiftoverAvailable());
        assertTrue(String.valueOf(response.getBlockingErrors()).contains("effectiveAnalysisAssembly != GRCh37"));
        assertTrue(String.valueOf(response.getBlockingErrors()).contains("liftOver is required"));
    }

    @Test
    void preserveInputWithReadyGrch37ProfileReportsPilotReadyAndGrch38OnlyProviderBehavior() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir.resolve("storage"));
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setPreRankingMaxCandidates(8000);
        configureGrch37Profile(properties);

        VariantAnalysisPreflightResponse response = service(properties).check("GRCh37");

        assertTrue(response.isApplicationReady());
        assertEquals("PRESERVE_INPUT", response.getAnalysisAssemblyPolicy());
        assertEquals("GRCh37", response.getEffectiveAnalysisAssembly());
        assertEquals("GRCh37", response.getSelectedVepProfile());
        assertEquals("READY", response.getSelectedVepProfileStatus().getStatus());
        assertFalse(response.isLiftoverRequired());
        assertEquals("READY", response.getGrch37VepProfile().getStatus());
        assertEquals(8000, response.getPreRankingCandidateCap());
        assertEquals("MODERATE; cap and skipped counts are recorded in analysis events.",
                response.getTruncationRisk());
        assertTrue(response.isReportDirectoryWritable());
        assertProviderNative(response, "ClinVar");
        assertProviderUnsupported(response, "gnomAD");
        assertProviderUnsupported(response, "CADD");
    }

    @Test
    void exomiserPreflightMakesGrch37LiftoverRequirementVisibleDespiteLegacyPreserveInputPolicy()
            throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir.resolve("storage"));
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        configureProfile(properties, "GRCh38");

        VariantAnalysisPreflightResponse response = service(properties).check("GRCh37");

        assertEquals("GRCh38", response.getEffectiveAnalysisAssembly());
        assertEquals("GRCh38", response.getSelectedVepProfile());
        assertTrue(response.isLiftoverRequired());
        assertFalse(String.valueOf(response.getBlockingErrors())
                .contains("effectiveAnalysisAssembly != GRCh37"));
    }

    @Test
    void exomiserGrch38BaseAnalysisIsReadyWithoutOptionalVep() throws IOException {
        VariantAnalysisProperties properties = exomiserProperties();

        VariantAnalysisPreflightResponse response = service(properties).check("GRCh38");

        assertTrue(response.isApplicationReady());
        assertEquals("EXOMISER", response.getDefaultEngine());
        assertEquals(currentRuntimePlatform(), response.getRuntimePlatform());
        assertEquals("READY_WITH_OPTIONAL_LIMITATIONS", response.getReadinessStatus());
        assertTrue(requiredComponent(response, "Exomiser").isReady());
        assertEquals("DISABLED", optionalComponent(response, "VEP GRCh38").getStatus());
        assertTrue(response.getBlockingErrors().isEmpty());
        assertTrue(String.valueOf(response.getWarnings()).contains("Exomiser base analysis can continue"));
    }

    @Test
    void exomiserMissingRuntimeBlocksBaseAnalysis() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        properties.setStorageRoot(tempDir.resolve("storage"));
        properties.getExomiser().setEnabled(true);

        VariantAnalysisPreflightResponse response = service(properties).check("GRCh38");

        assertFalse(response.isApplicationReady());
        assertEquals("BLOCKED", response.getReadinessStatus());
        assertFalse(requiredComponent(response, "Exomiser").isReady());
        assertTrue(String.valueOf(response.getBlockingErrors()).contains("Exomiser runtime is not READY"));
    }

    @Test
    void exomiserGrch37RequiresCompletePicardLiftoverResources() throws IOException {
        VariantAnalysisProperties properties = exomiserProperties();

        VariantAnalysisPreflightResponse blocked = service(properties).check("GRCh37");

        assertFalse(blocked.isApplicationReady());
        assertFalse(requiredComponent(blocked, "GRCh37 to GRCh38 liftover").isReady());

        configureLiftover(properties);
        VariantAnalysisPreflightResponse ready = service(properties).check("GRCh37");

        assertTrue(ready.isApplicationReady());
        assertTrue(ready.isLiftoverRequired());
        assertTrue(ready.isLiftoverAvailable());
        assertTrue(requiredComponent(ready, "GRCh37 to GRCh38 liftover").isReady());
    }

    @Test
    void preserveInputWithReadyGrch38ProfileSelectsGrch38Profile() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir.resolve("storage"));
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        configureProfile(properties, "GRCh38");

        VariantAnalysisPreflightResponse response = service(properties).check("GRCh38");

        assertTrue(response.isApplicationReady());
        assertEquals("GRCh38", response.getRequestedInputAssembly());
        assertEquals("GRCh38", response.getEffectiveAnalysisAssembly());
        assertEquals("GRCh38", response.getSelectedVepProfile());
        assertEquals("READY", response.getSelectedVepProfileStatus().getStatus());
        assertFalse(response.isLiftoverRequired());
        assertEquals("READY", response.getGrch38VepProfile().getStatus());
    }

    @Test
    void preserveInputWithMissingSelectedProfileBlocksOnlyThatAssembly() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir.resolve("storage"));
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        configureProfile(properties, "GRCh38");

        VariantAnalysisPreflightResponse response = service(properties).check("GRCh37");

        assertFalse(response.isApplicationReady());
        assertEquals("GRCh37", response.getSelectedVepProfile());
        assertEquals("MISSING_RESOURCE", response.getSelectedVepProfileStatus().getStatus());
        assertTrue(String.valueOf(response.getBlockingErrors()).contains("Selected VEP profile GRCh37"));
        assertEquals("READY", response.getGrch38VepProfile().getStatus());
    }

    private VariantAnalysisPreflightService service(VariantAnalysisProperties properties) {
        return new VariantAnalysisPreflightService(
                properties,
                new VepResourceValidator(properties),
                new ExomiserRuntimeValidator(),
                org.mockito.Mockito.mock(org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository.class)
        );
    }

    private VariantAnalysisProperties exomiserProperties() throws IOException {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        properties.setStorageRoot(tempDir.resolve("storage"));
        properties.getExomiser().setEnabled(true);
        properties.getExomiser().setResourceFingerprint("test-fingerprint");

        boolean windows = "WINDOWS".equals(currentRuntimePlatform());
        Path javaHome = tempDir.resolve("jdk");
        Path javaExecutable = javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");
        Files.createDirectories(javaExecutable.getParent());
        Files.write(javaExecutable, windows
                ? new byte[] {'M', 'Z'}
                : new byte[] {0x7f, 'E', 'L', 'F'});
        if (!windows) {
            Files.setPosixFilePermissions(javaExecutable, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
        }
        Files.write(javaHome.resolve("release"), (
                "JAVA_VERSION=\"21.0.11\"\n"
                        + "OS_NAME=\"" + (windows ? "Windows" : "Linux") + "\"\n"
                        + "OS_ARCH=\"x86_64\"\n"
                        + "IMAGE_TYPE=\"JDK\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path cli = tempDir.resolve("exomiser-cli.jar");
        Files.write(cli, new byte[] {'P', 'K'});
        Path data = tempDir.resolve("exomiser-data");
        Files.createDirectories(data.resolve("2602_hg38"));
        Files.createDirectories(data.resolve("2602_phenotype"));
        Path template = tempDir.resolve("analysis.yml");
        Files.write(template, "analysis: {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        properties.getExomiser().setJavaExecutable(javaExecutable.toString());
        properties.getExomiser().setCliJar(cli.toString());
        properties.getExomiser().setDataDirectory(data.toString());
        properties.getExomiser().setAnalysisTemplate(template.toString());
        return properties;
    }

    private String currentRuntimePlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("windows")) {
            return "WINDOWS";
        }
        if (osName.contains("linux")) {
            return "LINUX";
        }
        throw new IllegalStateException("Test requires a Windows or Linux host");
    }

    private void configureLiftover(VariantAnalysisProperties properties) throws IOException {
        Path picard = tempDir.resolve("picard.jar");
        Path chain = tempDir.resolve("hg19ToHg38.over.chain.gz");
        Path reference = tempDir.resolve("GRCh38.fa");
        Files.write(picard, new byte[] {1});
        Files.write(chain, new byte[] {1});
        Files.write(reference, new byte[] {1});
        Files.write(tempDir.resolve("GRCh38.dict"), new byte[] {1});
        properties.getLiftOver().setMode(VariantAnalysisProperties.LiftOver.Mode.PICARD);
        properties.getLiftOver().setPicardJar(picard.toString());
        properties.getLiftOver().setHg19ToHg38Chain(chain.toString());
        properties.getNormalization().setGrch38Reference(reference.toString());
    }

    private VariantAnalysisPreflightResponse.ComponentStatus requiredComponent(
            VariantAnalysisPreflightResponse response,
            String component) {
        return findComponent(response.getRequiredComponents(), component);
    }

    private VariantAnalysisPreflightResponse.ComponentStatus optionalComponent(
            VariantAnalysisPreflightResponse response,
            String component) {
        return findComponent(response.getOptionalComponents(), component);
    }

    private VariantAnalysisPreflightResponse.ComponentStatus findComponent(
            java.util.List<VariantAnalysisPreflightResponse.ComponentStatus> components,
            String component) {
        for (VariantAnalysisPreflightResponse.ComponentStatus status : components) {
            if (component.equals(status.getComponent())) {
                return status;
            }
        }
        throw new AssertionError("Missing component " + component);
    }

    private void configureGrch37Profile(VariantAnalysisProperties properties) throws IOException {
        configureProfile(properties, "GRCh37");
    }

    private void configureProfile(VariantAnalysisProperties properties, String assembly) throws IOException {
        Path executable = tempDir.resolve("varvis-vep.cmd");
        Path cache = tempDir.resolve("cache").resolve("homo_sapiens").resolve("116_" + assembly);
        Path fasta = tempDir.resolve("Homo_sapiens." + assembly + ".dna.primary_assembly.fa");
        Files.write(executable, new byte[] {1});
        Files.createDirectories(cache);
        Files.write(fasta, new byte[] {1});
        Files.write(tempDir.resolve("Homo_sapiens." + assembly + ".dna.primary_assembly.fa.fai"),
                new byte[] {1});

        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(true);
        profile.setAssembly(assembly);
        profile.setExecutable(executable.toString());
        profile.setCacheDir(cache.toString());
        profile.setFasta(fasta.toString());
        profile.setVersion("116.0");
        profile.setEnsemblCacheVersion("116_" + assembly);
        profile.setManeVersion("Ensembl_VEP_116_cache");
        properties.getVep().getResources().put(assembly, profile);
    }

    private void assertProviderUnsupported(VariantAnalysisPreflightResponse response, String provider) {
        for (VariantAnalysisPreflightResponse.ProviderCapabilityStatus capability
                : response.getProviderCapabilities()) {
            if (provider.equals(capability.getProvider())) {
                assertEquals("ASSEMBLY_UNSUPPORTED", capability.getUnsupportedStatus());
                assertEquals("GRCh37", capability.getQueryAssembly());
                assertTrue(capability.isNoRecordOnlyAfterValidQuery());
                return;
            }
        }
        throw new AssertionError("Missing provider capability for " + provider);
    }

    private void assertProviderNative(VariantAnalysisPreflightResponse response, String provider) {
        for (VariantAnalysisPreflightResponse.ProviderCapabilityStatus capability
                : response.getProviderCapabilities()) {
            if (provider.equals(capability.getProvider())) {
                assertEquals("UNAVAILABLE", capability.getUnsupportedStatus());
                assertEquals("GRCh37", capability.getQueryAssembly());
                assertTrue(capability.isNoRecordOnlyAfterValidQuery());
                return;
            }
        }
        throw new AssertionError("Missing provider capability for " + provider);
    }
}
