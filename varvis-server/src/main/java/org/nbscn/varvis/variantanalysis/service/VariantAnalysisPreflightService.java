package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisPreflightResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisProviderStatusResponse;
import org.nbscn.varvis.variantanalysis.engine.ExomiserRuntimeReadiness;
import org.nbscn.varvis.variantanalysis.engine.ExomiserRuntimeValidator;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.exception.VariantAnalysisPersistenceException;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class VariantAnalysisPreflightService {

    private static final List<String> GRCH38_ONLY = Collections.singletonList("GRCh38");

    private final VariantAnalysisProperties properties;
    private final VepResourceValidator vepResourceValidator;
    private final ExomiserRuntimeValidator exomiserRuntimeValidator;
    private final VariantAnalysisJobRepository repository;

    public VariantAnalysisPreflightService(VariantAnalysisProperties properties,
                                           VepResourceValidator vepResourceValidator,
                                           ExomiserRuntimeValidator exomiserRuntimeValidator,
                                           VariantAnalysisJobRepository repository) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.vepResourceValidator = vepResourceValidator;
        this.exomiserRuntimeValidator = exomiserRuntimeValidator;
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
    }

    public VariantAnalysisPreflightResponse check(String requestedInputAssembly) {
        GenomeAssembly requested = GenomeAssembly.require(requestedInputAssembly);
        GenomeAssembly effective = effectiveAnalysisAssembly(requested);
        boolean liftoverRequired = requested.requiresLiftOverTo(effective);
        boolean liftoverAvailable = liftoverAvailable();
        VariantAnalysisPreflightResponse.VepProfileStatus grch37 = vepProfile(GenomeAssembly.GRCh37);
        VariantAnalysisPreflightResponse.VepProfileStatus grch38 = vepProfile(GenomeAssembly.GRCh38);
        VariantAnalysisPreflightResponse.VepProfileStatus selected =
                effective == GenomeAssembly.GRCh37 ? grch37 : grch38;
        List<String> blockingErrors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        List<VariantAnalysisPreflightResponse.ComponentStatus> requiredComponents =
                new ArrayList<VariantAnalysisPreflightResponse.ComponentStatus>();
        List<VariantAnalysisPreflightResponse.ComponentStatus> optionalComponents =
                new ArrayList<VariantAnalysisPreflightResponse.ComponentStatus>();

        try {
            repository.verifyPersistenceSchema();
            requiredComponents.add(component("Analysis database", true, true, true, "READY",
                    "Analysis persistence columns are available.", Collections.emptyList()));
        } catch (VariantAnalysisPersistenceException unavailable) {
            requiredComponents.add(component("Analysis database", true, true, false,
                    unavailable.getErrorCode(), unavailable.getMessage(), Collections.emptyList()));
            blockingErrors.add(unavailable.getErrorCode() + ": " + unavailable.getMessage());
        }

        if (properties.getDefaultEngine() == AnalysisEngine.EXOMISER) {
            ExomiserRuntimeReadiness exomiser = exomiserRuntimeValidator.validate(properties.getExomiser());
            requiredComponents.add(component(
                    "Exomiser",
                    true,
                    exomiser.isEnabled(),
                    exomiser.isReady(),
                    exomiser.getStatus(),
                    exomiser.getMessage(),
                    exomiser.getMissingResources()
            ));
            if (!exomiser.isReady()) {
                blockingErrors.add("Exomiser runtime is not READY: " + exomiser.getStatus() + ".");
            }
            if (liftoverRequired) {
                VariantAnalysisPreflightResponse.ComponentStatus liftover = liftoverStatus();
                requiredComponents.add(liftover);
                if (!liftover.isReady()) {
                    blockingErrors.add("GRCh37 input requires Picard liftover, but required resources are unavailable.");
                }
            }
            addOptionalProviderStatuses(optionalComponents, warnings, selected);
        } else {
            if (requested == GenomeAssembly.GRCh37 && effective != GenomeAssembly.GRCh37) {
                blockingErrors.add("effectiveAnalysisAssembly != GRCh37; set analysis-assembly-policy=PRESERVE_INPUT "
                        + "for native GRCh37 pilot readiness.");
            }
            if (liftoverRequired && !liftoverAvailable) {
                blockingErrors.add("liftOver is required but not available.");
            }
            if (requested == GenomeAssembly.GRCh37 && effective == GenomeAssembly.GRCh37
                    && !"READY".equals(grch37.getStatus())) {
                blockingErrors.add("GRCh37 VEP profile is not READY.");
            }
            if (!"READY".equals(selected.getStatus())) {
                blockingErrors.add("Selected VEP profile " + selected.getAssembly()
                        + " is not READY: " + selected.getStatus() + ".");
            }
            requiredComponents.add(component(
                    "VEP " + selected.getAssembly(),
                    true,
                    selected.isEnabled(),
                    "READY".equals(selected.getStatus()),
                    selected.getStatus(),
                    "Legacy analysis requires the selected assembly-specific VEP profile.",
                    selected.getMissingResources()
            ));
        }

        boolean reportDirectoryWritable = writableDirectory(properties.getStorageRoot());
        requiredComponents.add(component(
                "Analysis storage",
                true,
                true,
                reportDirectoryWritable,
                reportDirectoryWritable ? "READY" : "UNAVAILABLE",
                reportDirectoryWritable
                        ? "Variant analysis storage is writable."
                        : "Variant analysis storage is not writable.",
                reportDirectoryWritable
                        ? Collections.emptyList()
                        : Collections.singletonList("variant-analysis.storage-root")
        ));
        if (!reportDirectoryWritable) {
            blockingErrors.add("Variant analysis report/storage directory is not writable.");
        }
        Long diskFreeBytes = diskFreeBytes(properties.getStorageRoot());
        boolean cacheWritable = properties.getVep().isPreRankingCacheEnabled();
        if (properties.getDefaultEngine() != AnalysisEngine.EXOMISER && !cacheWritable) {
            warnings.add("Persistent pre-ranking transcript annotation cache is disabled.");
        }
        if (requested == GenomeAssembly.GRCh37 && evidenceProjectionAvailable()) {
            warnings.add("GRCh37 candidates may be projected individually to verified GRCh38 alleles for "
                    + "GRCh38-only evidence queries; unmapped or REF-mismatched candidates remain unavailable.");
        }
        if (properties.getVep().isPreRankingEnabled()
                && properties.getVep().normalizedPreRankingMaxCandidates() <= 3000) {
            warnings.add("Candidate cap may truncate large bare VCF variants before pre-ranking VEP annotation.");
        }

        return new VariantAnalysisPreflightResponse(
                blockingErrors.isEmpty(),
                readinessStatus(blockingErrors, warnings),
                properties.getDefaultEngine().name(),
                exomiserRuntimeValidator.getRuntimePlatform(),
                requiredComponents,
                optionalComponents,
                properties.getAnalysisAssemblyPolicy(),
                requested.name(),
                effective.name(),
                liftoverRequired,
                liftoverAvailable,
                grch37,
                grch38,
                selected.getAssembly(),
                selected,
                providerCapabilities(effective),
                properties.getVep().normalizedPreRankingMaxCandidates(),
                truncationRisk(),
                cacheWritable,
                reportDirectoryWritable,
                diskFreeBytes,
                blockingErrors,
                warnings
        );
    }

    private String readinessStatus(List<String> blockingErrors, List<String> warnings) {
        if (!blockingErrors.isEmpty()) {
            return "BLOCKED";
        }
        return warnings.isEmpty() ? "READY" : "READY_WITH_OPTIONAL_LIMITATIONS";
    }

    private VariantAnalysisPreflightResponse.ComponentStatus component(String name,
                                                                        boolean required,
                                                                        boolean enabled,
                                                                        boolean ready,
                                                                        String status,
                                                                        String message,
                                                                        List<String> missing) {
        return new VariantAnalysisPreflightResponse.ComponentStatus(
                name, required, enabled, ready, status, message, missing
        );
    }

    private void addOptionalProviderStatuses(
            List<VariantAnalysisPreflightResponse.ComponentStatus> components,
            List<String> warnings,
            VariantAnalysisPreflightResponse.VepProfileStatus selectedVep) {
        VariantAnalysisProviderStatusResponse status = VariantAnalysisProviderStatusResponse.from(properties);
        addOptional(components, warnings, status.getClinVar());
        addOptional(components, warnings, status.getGnomad());
        addOptional(components, warnings, status.getCadd());
        addOptional(components, warnings, status.getSpliceAi());
        addOptional(components, warnings, status.getAlphaMissense());
        components.add(component(
                "VEP " + selectedVep.getAssembly(),
                false,
                selectedVep.isEnabled(),
                "READY".equals(selectedVep.getStatus()),
                selectedVep.getStatus(),
                "VEP transcript annotation is optional for the Exomiser base-analysis route.",
                selectedVep.getMissingResources()
        ));
        if (!"READY".equals(selectedVep.getStatus())) {
            warnings.add("Optional VEP transcript annotation is " + selectedVep.getStatus()
                    + "; Exomiser base analysis can continue.");
        }
    }

    private void addOptional(List<VariantAnalysisPreflightResponse.ComponentStatus> components,
                             List<String> warnings,
                             VariantAnalysisProviderStatusResponse.ProviderStatus provider) {
        if (provider == null) {
            return;
        }
        components.add(component(
                provider.getSource(),
                false,
                provider.isEnabled(),
                provider.isReady(),
                provider.getStatus(),
                provider.getMessage(),
                provider.getMissingResources()
        ));
        if (!provider.isReady()) {
            warnings.add("Optional provider " + provider.getSource() + " is " + provider.getStatus()
                    + "; Exomiser base analysis can continue without this evidence.");
        }
    }

    private GenomeAssembly effectiveAnalysisAssembly(GenomeAssembly requested) {
        // Exomiser 15.1.0 is configured only with the audited GRCh38/2602
        // resource set, so a GRCh37 product upload is explicitly lifted before
        // engine execution regardless of the legacy VEP preservation policy.
        if (properties.getDefaultEngine() == AnalysisEngine.EXOMISER) {
            return GenomeAssembly.GRCh38;
        }
        return properties.resolveAnalysisAssembly(requested);
    }

    private VariantAnalysisPreflightResponse.VepProfileStatus vepProfile(GenomeAssembly assembly) {
        VepReadinessReport report = vepResourceValidator.validate(assembly);
        return new VariantAnalysisPreflightResponse.VepProfileStatus(
                assembly.name(),
                report.isEnabled(),
                report.isExecutionEnabled(),
                profileStatus(report, properties.getVep().isEnabled()),
                report.getVepVersion(),
                report.getEnsemblCacheVersion(),
                report.isCacheDirExists(),
                report.isFastaExists(),
                report.isFastaIndexExists(),
                report.getMissingResources(),
                report.getWarnings()
        );
    }

    private String profileStatus(VepReadinessReport report, boolean globallyEnabled) {
        if (!globallyEnabled && !report.isEnabled()) {
            return "DISABLED";
        }
        String raw = report.getStatus();
        if ("READY".equals(raw) || "DISABLED".equals(raw)) {
            return raw;
        }
        String explanation = report.getExplanation() == null ? "" : report.getExplanation();
        if (explanation.contains("RESOURCE_PROFILE_ASSEMBLY_MISMATCH")) {
            return "ASSEMBLY_MISMATCH";
        }
        if ("PARTIAL".equals(raw) || !report.getMissingResources().isEmpty()) {
            return "MISSING_RESOURCE";
        }
        return raw == null ? "UNAVAILABLE" : raw;
    }

    private List<VariantAnalysisPreflightResponse.ProviderCapabilityStatus> providerCapabilities(
            GenomeAssembly queryAssembly) {
        List<VariantAnalysisPreflightResponse.ProviderCapabilityStatus> providers =
                new ArrayList<VariantAnalysisPreflightResponse.ProviderCapabilityStatus>();
        providers.add(new VariantAnalysisPreflightResponse.ProviderCapabilityStatus(
                "ClinVar",
                Arrays.asList("GRCh37", "GRCh38"),
                "native assembly-specific local resource",
                queryAssembly.name(),
                "Queries the matching native ClinVar resource when configured.",
                "UNAVAILABLE",
                true
        ));
        providers.add(grch38OnlyOrProjected("gnomAD", queryAssembly));
        providers.add(grch38OnlyOrProjected("CADD", queryAssembly));
        providers.add(grch38Only("AlphaMissense", queryAssembly));
        providers.add(grch38OnlyOrProjected("SpliceAI", queryAssembly));
        providers.add(new VariantAnalysisPreflightResponse.ProviderCapabilityStatus(
                "VEP",
                Arrays.asList("GRCh37", "GRCh38"),
                "assembly-specific resource profile",
                queryAssembly.name(),
                "Queries only the matching assembly-specific VEP resource profile.",
                "ASSEMBLY_UNSUPPORTED/UNAVAILABLE",
                true
        ));
        return providers;
    }

    private VariantAnalysisPreflightResponse.ProviderCapabilityStatus grch38Only(String provider,
                                                                                 GenomeAssembly queryAssembly) {
        boolean supported = queryAssembly == GenomeAssembly.GRCh38;
        return new VariantAnalysisPreflightResponse.ProviderCapabilityStatus(
                provider,
                GRCH38_ONLY,
                "GRCh38",
                queryAssembly.name(),
                supported
                        ? "Provider may query the configured GRCh38 resource and may return NO_RECORD after lookup."
                        : "Provider is not queried for this assembly.",
                supported ? null : "ASSEMBLY_UNSUPPORTED",
                true
        );
    }

    private VariantAnalysisPreflightResponse.ProviderCapabilityStatus grch38OnlyOrProjected(
            String provider,
            GenomeAssembly queryAssembly) {
        if (queryAssembly == GenomeAssembly.GRCh37 && evidenceProjectionAvailable()) {
            return new VariantAnalysisPreflightResponse.ProviderCapabilityStatus(
                    provider,
                    Arrays.asList("GRCh37", "GRCh38"),
                    "GRCh38 resource via verified candidate-level projection",
                    queryAssembly.name(),
                    "Retained GRCh37 candidates are queried only after hg19-to-hg38 projection and GRCh38 REF validation.",
                    "LIFTOVER_UNMAPPED/LIFTOVER_REF_MISMATCH/UNAVAILABLE",
                    true
            );
        }
        return grch38Only(provider, queryAssembly);
    }

    private boolean evidenceProjectionAvailable() {
        return properties.getLiftOver().isEvidenceProjectionEnabled()
                && regularFile(properties.getLiftOver().getHg19ToHg38Chain())
                && regularFile(properties.getNormalization().getGrch38Reference())
                && regularFile(fastaIndex(properties.getNormalization().getGrch38Reference()));
    }

    private Path fastaIndex(Path fasta) {
        return fasta == null ? null : fasta.resolveSibling(fasta.getFileName() + ".fai");
    }

    private boolean liftoverAvailable() {
        return liftoverStatus().isReady();
    }

    private VariantAnalysisPreflightResponse.ComponentStatus liftoverStatus() {
        VariantAnalysisProperties.LiftOver liftOver = properties.getLiftOver();
        List<String> missing = new ArrayList<String>();
        boolean enabled = liftOver.getMode() == VariantAnalysisProperties.LiftOver.Mode.PICARD;
        if (!enabled) {
            missing.add("variant-analysis.lift-over.mode=PICARD");
        }
        if (!StringUtils.hasText(liftOver.getJavaExecutable())) {
            missing.add("variant-analysis.lift-over.java-executable");
        }
        if (liftOver.getTimeout() == null
                || liftOver.getTimeout().isNegative()
                || liftOver.getTimeout().isZero()) {
            missing.add("variant-analysis.lift-over.timeout");
        }
        if (!regularFile(liftOver.getPicardJar())) {
            missing.add("variant-analysis.lift-over.picard-jar");
        }
        if (!regularFile(liftOver.getHg19ToHg38Chain())) {
            missing.add("variant-analysis.lift-over.hg19-to-hg38-chain");
        }
        Path reference = properties.getNormalization().getGrch38Reference();
        if (!regularFile(reference)) {
            missing.add("variant-analysis.normalization.grch38-reference");
        } else if (!regularFile(referenceDictionary(reference))) {
            missing.add("GRCh38 sequence dictionary");
        }
        boolean ready = enabled && missing.isEmpty();
        return component(
                "GRCh37 to GRCh38 liftover",
                true,
                enabled,
                ready,
                ready ? "READY" : (enabled ? "UNAVAILABLE" : "DISABLED"),
                ready
                        ? "Picard liftover resources required by the Exomiser GRCh37 route are ready."
                        : "GRCh37 input cannot reach the GRCh38-only Exomiser runtime.",
                missing
        );
    }

    private Path referenceDictionary(Path reference) {
        String name = reference.getFileName().toString();
        if (name.endsWith(".fa")) {
            name = name.substring(0, name.length() - 3);
        } else if (name.endsWith(".fasta")) {
            name = name.substring(0, name.length() - 6);
        }
        return reference.resolveSibling(name + ".dict");
    }

    private boolean regularFile(Path path) {
        return path != null && Files.isRegularFile(path) && Files.isReadable(path);
    }

    private boolean writableDirectory(Path path) {
        if (path == null) {
            return false;
        }
        try {
            Files.createDirectories(path);
            return Files.isDirectory(path) && Files.isWritable(path);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private Long diskFreeBytes(Path path) {
        if (path == null) {
            return null;
        }
        try {
            Path root = Files.exists(path) ? path : path.toAbsolutePath().getParent();
            if (root == null || !Files.exists(root)) {
                return null;
            }
            FileStore store = Files.getFileStore(root);
            return store.getUsableSpace();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private String truncationRisk() {
        int cap = properties.getVep().normalizedPreRankingMaxCandidates();
        if (cap <= 0) {
            return "BLOCKING: no variants will be selected for pre-ranking VEP annotation.";
        }
        if (cap <= 3000) {
            return "HIGH for large bare VCFs; use an ignored pilot override if this is a controlled local run.";
        }
        if (cap <= 10000) {
            return "MODERATE; cap and skipped counts are recorded in analysis events.";
        }
        return "LOWER, but monitor VEP runtime and batch count.";
    }
}
