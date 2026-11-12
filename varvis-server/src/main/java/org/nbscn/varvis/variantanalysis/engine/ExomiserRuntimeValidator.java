package org.nbscn.varvis.variantanalysis.engine;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Evaluates the same private Exomiser runtime contract used by job execution,
 * without starting a process or exposing configured absolute paths.
 */
@Component
public class ExomiserRuntimeValidator {

    static final String SUPPORTED_CLI_VERSION = "15.1.0";
    static final String SUPPORTED_DATA_VERSION = "2602";

    private final HostPlatform hostPlatform;
    private final Predicate<Path> executableCheck;

    public ExomiserRuntimeValidator() {
        this(HostPlatform.detect(System.getProperty("os.name")), Files::isExecutable);
    }

    ExomiserRuntimeValidator(HostPlatform hostPlatform, Predicate<Path> executableCheck) {
        this.hostPlatform = hostPlatform == null ? HostPlatform.UNSUPPORTED : hostPlatform;
        this.executableCheck = executableCheck == null ? Files::isExecutable : executableCheck;
    }

    public String getRuntimePlatform() {
        return hostPlatform.name();
    }

    public ExomiserRuntimeReadiness validate(VariantAnalysisProperties.Exomiser settings) {
        if (settings == null || !settings.isEnabled()) {
            return result(
                    false,
                    false,
                    "EXOMISER_DISABLED",
                    "Exomiser runtime is disabled on " + hostPlatform.name() + ".",
                    Collections.emptyList()
            );
        }

        List<String> missing = new ArrayList<String>();
        String firstFailure = null;
        Duration timeout = settings.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            firstFailure = "EXOMISER_TIMEOUT_INVALID";
            missing.add("exomiser.timeout");
        }
        if (!SUPPORTED_CLI_VERSION.equals(settings.getVersion())) {
            firstFailure = first(firstFailure, "EXOMISER_CLI_VERSION_UNSUPPORTED");
            missing.add("exomiser.version");
        }
        if (!SUPPORTED_DATA_VERSION.equals(settings.getDataVersion())) {
            firstFailure = first(firstFailure, "EXOMISER_DATA_VERSION_UNSUPPORTED");
            missing.add("exomiser.data-version");
        }
        if (!validJavaHeap(settings.getJavaHeap())) {
            firstFailure = first(firstFailure, "EXOMISER_JAVA_HEAP_INVALID");
            missing.add("exomiser.java-heap");
        }
        if (!StringUtils.hasText(settings.getResourceFingerprint())) {
            firstFailure = first(firstFailure, "EXOMISER_RESOURCE_FINGERPRINT_MISSING");
            missing.add("exomiser.resource-fingerprint");
        }

        Path java = path(settings.getJavaExecutable());
        if (hostPlatform == HostPlatform.UNSUPPORTED) {
            firstFailure = first(firstFailure, "EXOMISER_HOST_PLATFORM_UNSUPPORTED");
            missing.add("exomiser.host-platform");
        } else if (!validJavaExecutable(java)) {
            firstFailure = first(firstFailure, hostPlatform.javaUnavailableStatus());
            missing.add("exomiser.java-executable");
        } else {
            Path release = java.getParent().getParent().resolve("release");
            if (!Files.isReadable(release)) {
                firstFailure = first(firstFailure, "EXOMISER_JAVA_RELEASE_UNREADABLE");
                missing.add("exomiser.java-release");
            } else if (!validJdk21Release(release)) {
                firstFailure = first(firstFailure, hostPlatform.javaIdentityInvalidStatus());
                missing.add("exomiser.java-identity");
            }
        }

        Path cli = path(settings.getCliJar());
        if (!readableFileWithMagic(cli, 'P', 'K')) {
            firstFailure = first(firstFailure, "EXOMISER_CLI_UNAVAILABLE");
            missing.add("exomiser.cli-jar");
        }
        Path data = path(settings.getDataDirectory());
        if (!validDataDirectory(data)) {
            firstFailure = first(firstFailure, "EXOMISER_2602_RESOURCE_UNAVAILABLE");
            missing.add("exomiser.data-directory");
        }
        Path template = path(settings.getAnalysisTemplate());
        if (template == null || !Files.isRegularFile(template) || !Files.isReadable(template)) {
            firstFailure = first(firstFailure, StringUtils.hasText(settings.getAnalysisTemplate())
                    ? "EXOMISER_ANALYSIS_TEMPLATE_UNREADABLE"
                    : "EXOMISER_ANALYSIS_TEMPLATE_MISSING");
            missing.add("exomiser.analysis-template");
        }

        if (firstFailure == null) {
            return result(
                    true,
                    true,
                    "READY",
                    "Exomiser 15.1.0 with the 2602 GRCh38/phenotype dataset is ready on "
                            + hostPlatform.name() + ".",
                    Collections.emptyList()
            );
        }
        return result(
                true,
                false,
                firstFailure,
                "Exomiser runtime is not ready on " + hostPlatform.name() + ": " + firstFailure + ".",
                missing
        );
    }

    private ExomiserRuntimeReadiness result(boolean enabled,
                                             boolean ready,
                                             String status,
                                             String message,
                                             List<String> missingResources) {
        return new ExomiserRuntimeReadiness(
                enabled,
                ready,
                status,
                hostPlatform.name(),
                message,
                missingResources
        );
    }

    private String first(String current, String candidate) {
        return current == null ? candidate : current;
    }

    static boolean validJavaHeap(String value) {
        if (value == null || !value.matches("[1-9][0-9]*[mMgG]")) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        long amount;
        try {
            amount = Long.parseLong(normalized.substring(0, normalized.length() - 1));
        } catch (NumberFormatException error) {
            return false;
        }
        if (normalized.endsWith("g")) {
            return amount >= 1L && amount <= 16L;
        }
        return amount >= 512L && amount <= 16L * 1024L;
    }

    private Path path(String value) {
        try {
            return StringUtils.hasText(value) ? Paths.get(value.trim()) : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private boolean validJavaExecutable(Path java) {
        if (java == null || java.getParent() == null || java.getParent().getParent() == null) {
            return false;
        }
        String name = java.getFileName().toString();
        if (hostPlatform == HostPlatform.WINDOWS) {
            return name.equalsIgnoreCase("java.exe") && readableFileWithMagic(java, 'M', 'Z');
        }
        return hostPlatform == HostPlatform.LINUX
                && name.equals("java")
                && executableCheck.test(java)
                && readableFileWithMagic(java, 0x7f, 'E', 'L', 'F');
    }

    private boolean validJdk21Release(Path release) {
        try {
            String text = new String(Files.readAllBytes(release), StandardCharsets.UTF_8);
            return text.contains("JAVA_VERSION=\"21.")
                    && text.contains("OS_NAME=\"" + hostPlatform.releaseOsName() + "\"")
                    && (text.contains("OS_ARCH=\"x86_64\"")
                        || text.contains("OS_ARCH=\"amd64\""))
                    && text.contains("IMAGE_TYPE=\"JDK\"");
        } catch (IOException error) {
            return false;
        }
    }

    private boolean validDataDirectory(Path data) {
        return data != null
                && !forbiddenRuntimePath(data.toString())
                && Files.isDirectory(data)
                && Files.isDirectory(data.resolve(SUPPORTED_DATA_VERSION + "_hg38"))
                && Files.isDirectory(data.resolve(SUPPORTED_DATA_VERSION + "_phenotype"));
    }

    private boolean forbiddenRuntimePath(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        boolean windowsToWslPath = hostPlatform == HostPlatform.WINDOWS
                && (normalized.contains("/mnt/") || normalized.contains("//wsl$/"));
        return windowsToWslPath || normalized.contains("hg19") || normalized.contains("2512");
    }

    private boolean readableFileWithMagic(Path path, int... expected) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(path)) {
            for (int value : expected) {
                if (input.read() != value) {
                    return false;
                }
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    enum HostPlatform {
        WINDOWS("Windows", "EXOMISER_JAVA21_WINDOWS_UNAVAILABLE",
                "EXOMISER_JAVA21_WINDOWS_IDENTITY_INVALID"),
        LINUX("Linux", "EXOMISER_JAVA21_LINUX_UNAVAILABLE",
                "EXOMISER_JAVA21_LINUX_IDENTITY_INVALID"),
        UNSUPPORTED("", "EXOMISER_HOST_PLATFORM_UNSUPPORTED",
                "EXOMISER_HOST_PLATFORM_UNSUPPORTED");

        private final String releaseOsName;
        private final String javaUnavailableStatus;
        private final String javaIdentityInvalidStatus;

        HostPlatform(String releaseOsName,
                     String javaUnavailableStatus,
                     String javaIdentityInvalidStatus) {
            this.releaseOsName = releaseOsName;
            this.javaUnavailableStatus = javaUnavailableStatus;
            this.javaIdentityInvalidStatus = javaIdentityInvalidStatus;
        }

        static HostPlatform detect(String osName) {
            String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            if (normalized.contains("windows")) {
                return WINDOWS;
            }
            if (normalized.contains("linux")) {
                return LINUX;
            }
            return UNSUPPORTED;
        }

        String releaseOsName() {
            return releaseOsName;
        }

        String javaUnavailableStatus() {
            return javaUnavailableStatus;
        }

        String javaIdentityInvalidStatus() {
            return javaIdentityInvalidStatus;
        }
    }
}
