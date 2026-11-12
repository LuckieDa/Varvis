package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class VepResourceValidator {

    private final VariantAnalysisProperties properties;
    private final VepResourceProfileResolver profileResolver;

    @Autowired
    public VepResourceValidator(VariantAnalysisProperties properties) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.profileResolver = new VepResourceProfileResolver(this.properties);
    }

    public VepResourceValidator(VariantAnalysisProperties properties,
                                VepResourceProfileResolver profileResolver) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.profileResolver = profileResolver == null
                ? new VepResourceProfileResolver(this.properties)
                : profileResolver;
    }

    public VepReadinessReport validate() {
        return validate(GenomeAssembly.GRCh38);
    }

    public VepReadinessReport validate(GenomeAssembly assembly) {
        VariantAnalysisProperties.Vep vep = properties.getVep();
        boolean executionEnabled = vep.isExecutionEnabled();
        VepResourceProfileResolution resolution = profileResolver.resolve(assembly);
        if (!resolution.isResolved()) {
            List<String> missing = new ArrayList<String>();
            if ("UNAVAILABLE".equals(resolution.getStatus())) {
                missing.add("vep.resources." + (assembly == null ? "unknown" : assembly.name()));
            }
            return new VepReadinessReport(
                    false,
                    executionEnabled,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    vep.isLofteeEnabled(),
                    false,
                    vep.getVersion(),
                    vep.getEnsemblCacheVersion(),
                    vep.getManeVersion(),
                    vep.getLofteeVersion(),
                    resolution.getStatus(),
                    missing,
                    resolution.getWarnings(),
                    explanation(resolution)
            );
        }
        VepResourceProfile profile = resolution.getProfile();
        boolean enabled = profile.isEnabled();
        boolean executableConfigured = StringUtils.hasText(profile.getExecutable());
        boolean executableExists = executableConfigured && executableExists(profile.getExecutable());
        boolean executableRunnable = executableExists;
        boolean cacheConfigured = profile.getCacheDir() != null;
        boolean cacheCompressedArchive = cacheConfigured && isCompressedArchive(profile.getCacheDir());
        boolean cacheExists = cacheConfigured && !cacheCompressedArchive && Files.isDirectory(profile.getCacheDir());
        boolean fastaConfigured = profile.getFasta() != null;
        boolean fastaCompressedArchive = fastaConfigured && isCompressedArchive(profile.getFasta());
        boolean fastaExists = fastaConfigured && !fastaCompressedArchive && Files.isRegularFile(profile.getFasta());
        boolean fastaIndexExists = fastaExists && Files.isRegularFile(Paths.get(profile.getFasta().toString() + ".fai"));
        boolean pluginsConfigured = profile.getPluginsDir() != null;
        boolean pluginsExists = pluginsConfigured && Files.isDirectory(profile.getPluginsDir());
        boolean lofteePluginPresent = pluginsExists && lofteePluginPresent(profile.getPluginsDir());
        List<String> missing = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>(resolution.getWarnings());

        if (!enabled) {
            return new VepReadinessReport(
                    false,
                    executionEnabled,
                    executableConfigured,
                    executableExists,
                    executableRunnable,
                    cacheConfigured,
                    cacheExists,
                    fastaConfigured,
                    fastaExists,
                    fastaIndexExists,
                    pluginsConfigured,
                    pluginsExists,
                    vep.isLofteeEnabled(),
                    lofteePluginPresent,
                    profile.getVersion(),
                    profile.getEnsemblCacheVersion(),
                    profile.getManeVersion(),
                    profile.getLofteeVersion(),
                    "DISABLED",
                    missing,
                    warnings,
                    "VEP resource profile for " + profile.getAssembly().name()
                            + " is disabled; ANN fallback is used when present."
            );
        }

        if (!executableConfigured) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".executable");
        } else if (!executableExists) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".executable.exists");
        }
        if (!cacheConfigured) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".cache-dir");
        } else if (cacheCompressedArchive) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".cache-dir.unpacked-directory");
        } else if (!cacheExists) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".cache-dir.exists");
        }
        if (!fastaConfigured) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".fasta");
        } else if (fastaCompressedArchive) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".fasta.uncompressed-file");
        } else if (!fastaExists) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".fasta.exists");
        } else if (!fastaIndexExists) {
            missing.add("vep.resources." + profile.getAssembly().name() + ".fasta.fai");
        }
        if (vep.isLofteeEnabled()) {
            if (!pluginsConfigured) {
                missing.add("vep.resources." + profile.getAssembly().name() + ".plugins-dir");
            } else if (!pluginsExists) {
                missing.add("vep.resources." + profile.getAssembly().name() + ".plugins-dir.exists");
            } else if (!lofteePluginPresent) {
                missing.add("loftee.plugin");
            }
        }
        if (!executionEnabled) {
            warnings.add("VEP execution is disabled; readiness is for manual small-run preparation only.");
        }
        if (!StringUtils.hasText(profile.getVersion()) || "not-configured".equalsIgnoreCase(profile.getVersion())) {
            warnings.add("VEP version is not configured; record the local VEP release before real validation.");
        }
        if (!StringUtils.hasText(profile.getEnsemblCacheVersion())
                || "not-configured".equalsIgnoreCase(profile.getEnsemblCacheVersion())) {
            warnings.add("Ensembl cache version is not configured.");
        }
        if (!StringUtils.hasText(profile.getManeVersion())
                || "not-configured".equalsIgnoreCase(profile.getManeVersion())) {
            warnings.add("MANE version is not configured.");
        }

        String status;
        String explanation;
        if (!missing.isEmpty()) {
            status = partiallyConfigured(
                    executableConfigured,
                    cacheConfigured,
                    fastaConfigured,
                    pluginsConfigured
            ) ? "PARTIAL" : "UNAVAILABLE";
            explanation = "VEP " + profile.getAssembly().name()
                    + " resource profile is enabled but required resources are missing: "
                    + String.join(", ", missing);
        } else {
            status = "READY";
            explanation = executionEnabled
                    ? "VEP " + profile.getAssembly().name()
                    + " resources are ready and execution is explicitly enabled."
                    : "VEP " + profile.getAssembly().name()
                    + " resources are ready for a manual small-run, but execution remains disabled.";
        }

        return new VepReadinessReport(
                true,
                executionEnabled,
                executableConfigured,
                executableExists,
                executableRunnable,
                cacheConfigured,
                cacheExists,
                fastaConfigured,
                fastaExists,
                fastaIndexExists,
                pluginsConfigured,
                pluginsExists,
                vep.isLofteeEnabled(),
                lofteePluginPresent,
                profile.getVersion(),
                profile.getEnsemblCacheVersion(),
                profile.getManeVersion(),
                profile.getLofteeVersion(),
                status,
                missing,
                warnings,
                explanation
        );
    }

    private boolean executableExists(String executable) {
        Path configured = Paths.get(executable);
        if (configured.isAbsolute() || executable.contains("\\") || executable.contains("/")) {
            return Files.isRegularFile(configured);
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.trim().isEmpty()) {
            return false;
        }
        String[] extensions = executableExtensions();
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            if (!StringUtils.hasText(directory)) {
                continue;
            }
            for (String extension : extensions) {
                Path candidate = Paths.get(directory, executable + extension);
                if (Files.isRegularFile(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String[] executableExtensions() {
        String pathExt = System.getenv("PATHEXT");
        if (!StringUtils.hasText(pathExt)) {
            return new String[] {""};
        }
        String[] raw = pathExt.split(";");
        String[] result = new String[raw.length + 1];
        result[0] = "";
        for (int index = 0; index < raw.length; index++) {
            result[index + 1] = raw[index].toLowerCase();
        }
        return result;
    }

    private boolean lofteePluginPresent(Path pluginsDir) {
        return Files.isRegularFile(pluginsDir.resolve("LoF.pm"))
                || Files.isRegularFile(pluginsDir.resolve("loftee").resolve("LoF.pm"))
                || Files.isRegularFile(pluginsDir.resolve("loftee-master").resolve("LoF.pm"));
    }

    private boolean partiallyConfigured(boolean executableConfigured,
                                        boolean cacheConfigured,
                                        boolean fastaConfigured,
                                        boolean pluginsConfigured) {
        return executableConfigured || cacheConfigured || fastaConfigured || pluginsConfigured;
    }

    private boolean isCompressedArchive(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String filename = path.getFileName().toString().toLowerCase();
        return filename.endsWith(".tar.gz")
                || filename.endsWith(".tgz")
                || filename.endsWith(".zip")
                || filename.endsWith(".gz");
    }

    private String explanation(VepResourceProfileResolution resolution) {
        if ("ASSEMBLY_UNSUPPORTED".equals(resolution.getStatus())) {
            return resolution.getReason();
        }
        return "VEP resource profile is unavailable: " + resolution.getReason();
    }
}
