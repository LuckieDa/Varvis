package org.nbscn.varvis.variantanalysis.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExomiserRuntimeValidatorTest {

    @TempDir
    Path temporary;

    @Test
    void acceptsWindowsJdk21Runtime() throws IOException {
        VariantAnalysisProperties.Exomiser settings = runtime(
                ExomiserRuntimeValidator.HostPlatform.WINDOWS,
                "21.0.11"
        );
        ExomiserRuntimeReadiness readiness = validator(
                ExomiserRuntimeValidator.HostPlatform.WINDOWS,
                true
        ).validate(settings);

        assertTrue(readiness.isReady());
        assertEquals("READY", readiness.getStatus());
        assertEquals("WINDOWS", readiness.getRuntimePlatform());
        assertTrue(readiness.getMessage().contains("WINDOWS"));
    }

    @Test
    void acceptsExecutableLinuxJdk21Runtime() throws IOException {
        VariantAnalysisProperties.Exomiser settings = runtime(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                "21.0.11"
        );
        ExomiserRuntimeReadiness readiness = validator(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                true
        ).validate(settings);

        assertTrue(readiness.isReady());
        assertEquals("READY", readiness.getStatus());
        assertEquals("LINUX", readiness.getRuntimePlatform());
        assertTrue(readiness.getMessage().contains("LINUX"));
    }

    @Test
    void rejectsMissingOrUnboundedExomiserHeapContract() throws IOException {
        VariantAnalysisProperties.Exomiser settings = runtime(
                ExomiserRuntimeValidator.HostPlatform.WINDOWS,
                "21.0.11"
        );
        settings.setJavaHeap("unbounded");

        ExomiserRuntimeReadiness readiness = validator(
                ExomiserRuntimeValidator.HostPlatform.WINDOWS,
                true
        ).validate(settings);

        assertFalse(readiness.isReady());
        assertEquals("EXOMISER_JAVA_HEAP_INVALID", readiness.getStatus());
        assertTrue(readiness.getMissingResources().contains("exomiser.java-heap"));
        assertFalse(ExomiserRuntimeValidator.validJavaHeap("128m"));
        assertFalse(ExomiserRuntimeValidator.validJavaHeap("17g"));
        assertFalse(ExomiserRuntimeValidator.validJavaHeap("9223372036854775807g"));
        assertFalse(ExomiserRuntimeValidator.validJavaHeap("999999999999999999999999g"));
        assertTrue(ExomiserRuntimeValidator.validJavaHeap("512m"));
        assertTrue(ExomiserRuntimeValidator.validJavaHeap("4g"));
    }

    @Test
    void rejectsLinuxJavaWithoutExecutablePermission() throws IOException {
        VariantAnalysisProperties.Exomiser settings = runtime(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                "21.0.11"
        );

        ExomiserRuntimeReadiness readiness = validator(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                false
        ).validate(settings);

        assertFalse(readiness.isReady());
        assertEquals("EXOMISER_JAVA21_LINUX_UNAVAILABLE", readiness.getStatus());
        assertTrue(readiness.getMissingResources().contains("exomiser.java-executable"));
    }

    @Test
    void rejectsWindowsJdkIdentityOnLinux() throws IOException {
        VariantAnalysisProperties.Exomiser settings = runtime(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                "21.0.11"
        );
        Path javaHome = Path.of(settings.getJavaExecutable()).getParent().getParent();
        writeRelease(javaHome, ExomiserRuntimeValidator.HostPlatform.WINDOWS, "21.0.11");

        ExomiserRuntimeReadiness readiness = validator(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                true
        ).validate(settings);

        assertFalse(readiness.isReady());
        assertEquals("EXOMISER_JAVA21_LINUX_IDENTITY_INVALID", readiness.getStatus());
        assertTrue(readiness.getMissingResources().contains("exomiser.java-identity"));
    }

    @Test
    void rejectsNonJdk21LinuxIdentity() throws IOException {
        VariantAnalysisProperties.Exomiser settings = runtime(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                "17.0.16"
        );

        ExomiserRuntimeReadiness readiness = validator(
                ExomiserRuntimeValidator.HostPlatform.LINUX,
                true
        ).validate(settings);

        assertFalse(readiness.isReady());
        assertEquals("EXOMISER_JAVA21_LINUX_IDENTITY_INVALID", readiness.getStatus());
    }

    @Test
    void rejectsUnsupportedHostBeforeAttemptingJavaIdentity() throws IOException {
        VariantAnalysisProperties.Exomiser settings = runtime(
                ExomiserRuntimeValidator.HostPlatform.WINDOWS,
                "21.0.11"
        );

        ExomiserRuntimeReadiness readiness = validator(
                ExomiserRuntimeValidator.HostPlatform.UNSUPPORTED,
                true
        ).validate(settings);

        assertFalse(readiness.isReady());
        assertEquals("EXOMISER_HOST_PLATFORM_UNSUPPORTED", readiness.getStatus());
        assertEquals("UNSUPPORTED", readiness.getRuntimePlatform());
        assertTrue(readiness.getMissingResources().contains("exomiser.host-platform"));
    }

    @Test
    void detectsSupportedAndUnsupportedHostNames() {
        assertEquals(ExomiserRuntimeValidator.HostPlatform.WINDOWS,
                ExomiserRuntimeValidator.HostPlatform.detect("Windows Server 2025"));
        assertEquals(ExomiserRuntimeValidator.HostPlatform.LINUX,
                ExomiserRuntimeValidator.HostPlatform.detect("Linux"));
        assertEquals(ExomiserRuntimeValidator.HostPlatform.UNSUPPORTED,
                ExomiserRuntimeValidator.HostPlatform.detect("Mac OS X"));
    }

    private ExomiserRuntimeValidator validator(ExomiserRuntimeValidator.HostPlatform platform,
                                                boolean executable) {
        return new ExomiserRuntimeValidator(platform, ignored -> executable);
    }

    private VariantAnalysisProperties.Exomiser runtime(
            ExomiserRuntimeValidator.HostPlatform platform,
            String javaVersion) throws IOException {
        Path root = temporary.resolve(platform.name().toLowerCase());
        Path javaHome = root.resolve("jdk");
        Path java = javaHome.resolve("bin").resolve(
                platform == ExomiserRuntimeValidator.HostPlatform.WINDOWS ? "java.exe" : "java"
        );
        Files.createDirectories(java.getParent());
        Files.write(java, platform == ExomiserRuntimeValidator.HostPlatform.WINDOWS
                ? new byte[] {'M', 'Z'}
                : new byte[] {0x7f, 'E', 'L', 'F'});
        writeRelease(javaHome, platform, javaVersion);

        Path cli = root.resolve("exomiser-cli.jar");
        Files.write(cli, new byte[] {'P', 'K'});
        Path data = root.resolve("data");
        Files.createDirectories(data.resolve("2602_hg38"));
        Files.createDirectories(data.resolve("2602_phenotype"));
        Path template = root.resolve("analysis.yml");
        Files.write(template, "analysis: {}\n".getBytes(StandardCharsets.UTF_8));

        VariantAnalysisProperties.Exomiser settings = new VariantAnalysisProperties().getExomiser();
        settings.setEnabled(true);
        settings.setJavaExecutable(java.toString());
        settings.setCliJar(cli.toString());
        settings.setDataDirectory(data.toString());
        settings.setAnalysisTemplate(template.toString());
        settings.setResourceFingerprint("test-fingerprint");
        return settings;
    }

    private void writeRelease(Path javaHome,
                              ExomiserRuntimeValidator.HostPlatform platform,
                              String javaVersion) throws IOException {
        Files.write(javaHome.resolve("release"), (
                "JAVA_VERSION=\"" + javaVersion + "\"\n"
                        + "OS_NAME=\"" + (platform == ExomiserRuntimeValidator.HostPlatform.WINDOWS
                        ? "Windows" : "Linux") + "\"\n"
                        + "OS_ARCH=\"x86_64\"\n"
                        + "IMAGE_TYPE=\"JDK\"\n"
        ).getBytes(StandardCharsets.UTF_8));
    }
}
