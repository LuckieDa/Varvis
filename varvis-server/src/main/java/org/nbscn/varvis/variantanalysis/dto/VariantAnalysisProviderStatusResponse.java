package org.nbscn.varvis.variantanalysis.dto;

import org.nbscn.varvis.variantanalysis.config.AlphaGenomeEnvironment;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.engine.ExomiserRuntimeReadiness;
import org.nbscn.varvis.variantanalysis.engine.ExomiserRuntimeValidator;
import org.nbscn.varvis.variantanalysis.provider.NormalizationReferenceReadiness;
import org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VariantAnalysisProviderStatusResponse {

    private ProviderStatus exomiser;
    private ProviderStatus cadd;
    private ProviderStatus clinVar;
    private ProviderStatus gnomad;
    private ProviderStatus spliceAi;
    private ProviderStatus alphaMissense;
    private ProviderStatus alphaGenome;
    private ProviderStatus vep;
    private ProviderStatus mane;
    private ProviderStatus loftee;
    private boolean vepExecutionEnabled;
    private boolean vepPreRankingEnabled;
    private String defaultEngine;
    private String analysisAssemblyPolicy;
    private String normalizationMode;
    private Map<String, NormalizationReferenceReadiness> normalizationProfiles = Collections.emptyMap();
    private Map<String, VepProfileReadiness> vepProfiles = Collections.emptyMap();

    public static VariantAnalysisProviderStatusResponse from(VariantAnalysisProperties properties) {
        VariantAnalysisProviderStatusResponse response = new VariantAnalysisProviderStatusResponse();
        VariantAnalysisProperties safeProperties = properties == null ? new VariantAnalysisProperties() : properties;
        response.exomiser = exomiserStatus(safeProperties);
        response.defaultEngine = safeProperties.getDefaultEngine().name();
        response.cadd = caddStatus(safeProperties);
        response.clinVar = clinVarStatus(safeProperties);
        response.gnomad = gnomadStatus(safeProperties);
        response.spliceAi = spliceAiStatus(safeProperties);
        response.alphaMissense = alphaMissenseStatus(safeProperties);
        response.alphaGenome = alphaGenomeStatus(safeProperties);
        response.analysisAssemblyPolicy = safeProperties.getAnalysisAssemblyPolicy();
        response.normalizationMode = safeProperties.getNormalization().getMode().name();
        NormalizationResourceValidator normalizationValidator =
                new NormalizationResourceValidator(safeProperties);
        Map<String, NormalizationReferenceReadiness> normalizationProfiles =
                new LinkedHashMap<String, NormalizationReferenceReadiness>();
        normalizationProfiles.put(GenomeAssembly.GRCh37.name(),
                normalizationValidator.validate(GenomeAssembly.GRCh37));
        normalizationProfiles.put(GenomeAssembly.GRCh38.name(),
                normalizationValidator.validate(GenomeAssembly.GRCh38));
        response.normalizationProfiles = Collections.unmodifiableMap(normalizationProfiles);
        response.vepExecutionEnabled = safeProperties.getVep().isExecutionEnabled();
        response.vepPreRankingEnabled = safeProperties.getVep().isPreRankingEnabled();
        VepResourceValidator validator = new VepResourceValidator(safeProperties);
        VepReadinessReport grch37Report = validator.validate(GenomeAssembly.GRCh37);
        VepReadinessReport grch38Report = validator.validate(GenomeAssembly.GRCh38);
        Map<String, VepProfileReadiness> profiles = new LinkedHashMap<String, VepProfileReadiness>();
        profiles.put(GenomeAssembly.GRCh37.name(), vepProfileReadiness(
                GenomeAssembly.GRCh37,
                grch37Report,
                safeProperties.getVep().isEnabled()
        ));
        profiles.put(GenomeAssembly.GRCh38.name(), vepProfileReadiness(
                GenomeAssembly.GRCh38,
                grch38Report,
                safeProperties.getVep().isEnabled()
        ));
        response.vepProfiles = Collections.unmodifiableMap(profiles);
        response.vep = aggregateVepStatus(safeProperties, grch38Report, profiles);
        response.mane = maneStatus(safeProperties, grch38Report);
        response.loftee = lofteeStatus(safeProperties, grch38Report);
        return response;
    }

    private static ProviderStatus exomiserStatus(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.Exomiser settings = properties.getExomiser();
        ExomiserRuntimeReadiness readiness = new ExomiserRuntimeValidator().validate(settings);
        ProviderStatus status = new ProviderStatus(
                "Exomiser",
                settings.getVersion() + " / data " + settings.getDataVersion(),
                readiness.isEnabled(),
                readiness.isReady()
        );
        status.setStatus(readiness.isReady() ? "READY" : (readiness.isEnabled() ? "UNAVAILABLE" : "DISABLED"));
        status.setPrimaryEvidenceSource("EXOMISER");
        status.setRuntimePlatform(readiness.getRuntimePlatform());
        status.setMissingResources(readiness.getMissingResources());
        status.setExplanation(readiness.getMessage());
        status.setMessage(readiness.getMessage());
        return status;
    }

    private static ProviderStatus caddStatus(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.Cadd cadd = properties.getCadd();
        boolean snvReady = indexedFileAvailable(cadd.getGrch38SnvFile());
        boolean indelReady = indexedFileAvailable(cadd.getGrch38IndelFile());
        boolean ready = cadd.isEnabled() && (snvReady || indelReady);
        ProviderStatus status = new ProviderStatus("CADD", cadd.getVersion(), cadd.isEnabled(), ready);
        status.setSnvReady(snvReady);
        status.setIndelReady(indelReady);
        if (!cadd.isEnabled()) {
            status.setMessage("CADD local lookup is disabled.");
        } else if (snvReady && indelReady) {
            status.setMessage("CADD SNV and InDel files are available.");
        } else if (snvReady) {
            status.setMessage("CADD SNV file is available; InDel file is unavailable.");
        } else if (indelReady) {
            status.setMessage("CADD InDel file is available; SNV file is unavailable.");
        } else {
            status.setMessage("Configured CADD files or Tabix indexes are unavailable.");
        }
        return status;
    }

    private static ProviderStatus clinVarStatus(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.ClinVar clinVar = properties.getClinVar();
        boolean grch37TabixReady = indexedFileAvailable(clinVar.getGrch37File());
        boolean grch38TabixReady = indexedFileAvailable(clinVar.getGrch38File());
        boolean compactEnabled = clinVar.getCompactIndex().isEnabled();
        boolean compactReady = plainFileAvailable(clinVar.getCompactIndex().getFile());
        boolean grch38Ready = compactEnabled
                ? compactReady || (clinVar.getCompactIndex().isAllowTabixFallback() && grch38TabixReady)
                : grch38TabixReady;
        boolean fileReady = grch37TabixReady || grch38Ready;
        boolean ready = clinVar.isEnabled()
                && fileReady;
        ProviderStatus status = new ProviderStatus("ClinVar", clinVar.getVersion(), clinVar.isEnabled(), ready);
        status.setFileReady(fileReady);
        status.setPrimaryEvidenceSource(compactEnabled ? "LOCAL_CLINVAR_COMPACT" : "LOCAL_CLINVAR");
        status.setFallbackEvidenceSource(clinVar.isAllowVcfInfoFallback() ? "VCF_INFO_CLINVAR" : null);
        status.setConfiguredFileCount(configuredClinVarFileCount(clinVar, compactEnabled));
        status.setAvailableFileCount((grch37TabixReady ? 1 : 0) + (grch38Ready ? 1 : 0));
        if (!clinVar.isEnabled()) {
            status.setMessage("ClinVar local lookup is disabled.");
        } else if (grch37TabixReady && grch38Ready) {
            status.setMessage("ClinVar GRCh37 and GRCh38 exact allele resources are available.");
        } else if (compactEnabled && compactReady) {
            status.setMessage("ClinVar compact exact allele index is available; GRCh37 resource is unavailable.");
        } else if (compactEnabled && !clinVar.getCompactIndex().isAllowTabixFallback()) {
            status.setMessage("Configured ClinVar compact exact allele index is unavailable and Tabix fallback is disabled.");
        } else if (grch37TabixReady) {
            status.setMessage("ClinVar GRCh37 exact allele resource is available; GRCh38 resource is unavailable.");
        } else if (grch38Ready) {
            status.setMessage("ClinVar GRCh38 exact allele resource is available; GRCh37 resource is unavailable.");
        } else {
            status.setMessage("Configured ClinVar GRCh37/GRCh38 files or indexes are unavailable.");
        }
        return status;
    }

    private static int configuredClinVarFileCount(VariantAnalysisProperties.ClinVar clinVar,
                                                   boolean compactEnabled) {
        int configured = clinVar.getGrch37File() == null ? 0 : 1;
        if (compactEnabled) {
            return configured + (clinVar.getCompactIndex().getFile() == null ? 0 : 1);
        }
        return configured + (clinVar.getGrch38File() == null ? 0 : 1);
    }

    private static ProviderStatus gnomadStatus(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.Gnomad gnomad = properties.getGnomad();
        List<Path> compactFiles = gnomad.getGrch38CompactFiles();
        List<Path> fullFiles = gnomad.getGrch38Files();
        List<Path> files = compactFiles.isEmpty() && gnomad.isAllowFullVcfFallback() ? fullFiles : compactFiles;
        int configured = files.size();
        int available = 0;
        for (Path file : files) {
            if (indexedFileAvailable(file)) {
                available++;
            }
        }
        boolean ready = gnomad.isEnabled() && available > 0;
        ProviderStatus status = new ProviderStatus("gnomAD", gnomad.getVersion(), gnomad.isEnabled(), ready);
        status.setFileReady(available > 0);
        status.setConfiguredFileCount(configured);
        status.setAvailableFileCount(available);
        status.setPrimaryEvidenceSource("LOCAL_GNOMAD");
        status.setFallbackEvidenceSource(gnomad.isAllowVcfInfoFallback() ? "VCF_INFO_GNOMAD" : null);
        if (!gnomad.isEnabled()) {
            status.setMessage("gnomAD local lookup is disabled.");
        } else if (!compactFiles.isEmpty() && available == configured && configured > 0) {
            status.setMessage("All configured compact gnomAD GRCh38 frequency indexes are available.");
        } else if (!compactFiles.isEmpty() && available > 0) {
            status.setMessage("Some compact gnomAD GRCh38 frequency indexes are available; missing chromosomes return UNAVAILABLE.");
        } else if (gnomad.isAllowFullVcfFallback() && available == configured && configured > 0) {
            status.setMessage("All configured gnomAD GRCh38 files are available.");
        } else if (gnomad.isAllowFullVcfFallback() && available > 0) {
            status.setMessage("Some configured gnomAD GRCh38 files are available.");
        } else if (compactFiles.isEmpty()) {
            status.setMessage("gnomAD compact GRCh38 indexes are not configured; full VCF fallback is disabled.");
        } else {
            status.setMessage("Configured compact gnomAD GRCh38 indexes are unavailable.");
        }
        return status;
    }

    private static ProviderStatus alphaMissenseStatus(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.AlphaMissense alphaMissense = properties.getAlphaMissense();
        boolean fileReady = indexedFileAvailable(alphaMissense.getGrch38File());
        boolean ready = alphaMissense.isEnabled() && fileReady;
        ProviderStatus status = new ProviderStatus(
                "AlphaMissense",
                alphaMissense.getVersion(),
                alphaMissense.isEnabled(),
                ready
        );
        status.setFileReady(fileReady);
        status.setMessage(ready
                ? "AlphaMissense GRCh38 file is available."
                : "AlphaMissense lookup is disabled or its indexed file is unavailable.");
        return status;
    }

    private static ProviderStatus spliceAiStatus(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.SpliceAi spliceAi = properties.getSpliceAi();
        boolean snvReady = indexedFileAvailable(spliceAi.getSnvResourcePath(), spliceAi.getSnvIndexPath());
        boolean indelReady = indexedFileAvailable(spliceAi.getIndelResourcePath(), spliceAi.getIndelIndexPath());
        boolean fileReady = snvReady && indelReady;
        boolean ready = spliceAi.isEnabled() && fileReady;
        ProviderStatus status = new ProviderStatus("SpliceAI", spliceAi.getVersion(), spliceAi.isEnabled(), ready);
        status.setFileReady(fileReady);
        status.setPrimaryEvidenceSource("LOCAL_SPLICEAI");
        status.setFallbackEvidenceSource(spliceAi.isAllowVcfInfoFallback() ? "VCF_INFO_SPLICEAI" : null);
        status.setBatchSize(spliceAi.normalizedBatchSize());
        status.setDefaultCandidateLimit(spliceAi.normalizedMaxCandidates());
        if (!spliceAi.isEnabled()) {
            status.setStatus("DISABLED");
            status.setMessage(spliceAi.isAllowVcfInfoFallback()
                    ? "SpliceAI local lookup is disabled; VCF INFO fallback is enabled when present."
                    : "SpliceAI local lookup is disabled.");
        } else if (fileReady) {
            status.setMessage("SpliceAI GRCh38 SNV and indel files with Tabix indexes are available.");
        } else {
            status.setMissingResources(spliceAiMissingResources(spliceAi, snvReady, indelReady));
            status.setWarnings(Collections.singletonList(
                    "SpliceAI UNAVAILABLE is not interpreted as low splice risk."
            ));
            status.setMessage("Configured SpliceAI GRCh38 SNV or indel resource/index is unavailable.");
        }
        status.setExplanation("SpliceAI maximum delta score is computational splice prediction evidence only; "
                + "NO_RECORD is not AF=0 or benign evidence, and UNAVAILABLE is not low risk.");
        return status;
    }

    private static List<String> spliceAiMissingResources(VariantAnalysisProperties.SpliceAi spliceAi,
                                                         boolean snvReady,
                                                         boolean indelReady) {
        List<String> missing = new ArrayList<String>();
        if (!snvReady) {
            missing.add(spliceAi.getSnvResourcePath() == null
                    ? "spliceai.snv-resource"
                    : "spliceai.snv-tabix-index");
        }
        if (!indelReady) {
            missing.add(spliceAi.getIndelResourcePath() == null
                    ? "spliceai.indel-resource"
                    : "spliceai.indel-tabix-index");
        }
        return missing;
    }

    private static ProviderStatus alphaGenomeStatus(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.AlphaGenome alphaGenome = properties.getAlphaGenome();
        boolean pythonConfigured = StringUtils.hasText(alphaGenome.getPythonExecutable());
        boolean scriptAvailable = alphaGenome.getScriptPath() != null
                && Files.isRegularFile(alphaGenome.getScriptPath());
        boolean apiKeyConfigured = AlphaGenomeEnvironment.apiKeyConfigured();
        boolean ready = alphaGenome.isEnabled() && pythonConfigured && scriptAvailable && apiKeyConfigured;
        ProviderStatus status = new ProviderStatus(
                "AlphaGenome",
                alphaGenome.getVersion(),
                alphaGenome.isEnabled(),
                ready
        );
        status.setPythonConfigured(pythonConfigured);
        status.setScriptAvailable(scriptAvailable);
        status.setApiKeyConfigured(apiKeyConfigured);
        status.setDefaultCandidateLimit(alphaGenome.getDefaultCandidateLimit());
        status.setMaxCandidateLimit(alphaGenome.getMaxCandidateLimit());
        if (!alphaGenome.isEnabled()) {
            status.setMessage("AlphaGenome remote lookup is disabled.");
        } else if (!apiKeyConfigured) {
            status.setMessage("AlphaGenome API key is not configured.");
        } else if (!pythonConfigured || !scriptAvailable) {
            status.setMessage("AlphaGenome Python adapter is not fully configured.");
        } else {
            status.setMessage("AlphaGenome remote lookup is ready.");
        }
        return status;
    }

    private static ProviderStatus aggregateVepStatus(VariantAnalysisProperties properties,
                                                     VepReadinessReport compatibilityReport,
                                                     Map<String, VepProfileReadiness> profiles) {
        VariantAnalysisProperties.Vep vep = properties.getVep();
        boolean anyReady = false;
        boolean anyEnabled = vep.isEnabled();
        List<String> profileSummaries = new ArrayList<String>();
        for (VepProfileReadiness profile : profiles.values()) {
            anyReady = anyReady || profile.isReady();
            anyEnabled = anyEnabled || profile.isEnabled();
            profileSummaries.add(profile.getAssembly() + " " + profile.getStatus());
        }
        ProviderStatus status = new ProviderStatus("VEP", versionSummary(vep, profiles), anyEnabled, anyReady);
        status.setFileReady(anyReady);
        status.setPrimaryEvidenceSource("VEP_CACHED");
        status.setFallbackEvidenceSource("ANN_ONLY");
        status.setStatus(anyReady ? "READY" : compatibilityReport.getStatus());
        status.setExecutionEnabled(vep.isExecutionEnabled());
        status.setDefaultCandidateLimit(vep.getMaxCandidates());
        status.setBatchSize(vep.getBatchSize());
        status.setTranscriptAnnotationStatus(anyReady ? "PENDING" : transcriptAnnotationStatus(compatibilityReport));
        status.setMissingResources(compatibilityReport.getMissingResources());
        status.setWarnings(compatibilityReport.getWarnings());
        status.setExplanation("VEP profile readiness: " + String.join("; ", profileSummaries) + ".");
        status.setMessage(status.getExplanation());
        return status;
    }

    private static String versionSummary(VariantAnalysisProperties.Vep vep,
                                         Map<String, VepProfileReadiness> profiles) {
        List<String> versions = new ArrayList<String>();
        for (VepProfileReadiness profile : profiles.values()) {
            if (StringUtils.hasText(profile.getProviderVersion())
                    && !versions.contains(profile.getProviderVersion())) {
                versions.add(profile.getProviderVersion());
            }
        }
        if (versions.isEmpty()) {
            return vep.getVersion();
        }
        return String.join(" / ", versions);
    }

    private static VepProfileReadiness vepProfileReadiness(GenomeAssembly assembly,
                                                           VepReadinessReport report,
                                                           boolean globallyEnabled) {
        String status = profileStatus(report, globallyEnabled);
        return new VepProfileReadiness(
                assembly.name(),
                report.isEnabled(),
                report.isExecutionEnabled(),
                "READY".equals(status),
                status,
                report.isCacheDirExists(),
                report.isFastaExists(),
                report.isFastaIndexExists(),
                report.getVepVersion(),
                report.getEnsemblCacheVersion(),
                transcriptAnnotationStatus(report),
                report.getMissingResources(),
                report.getWarnings(),
                report.getExplanation()
        );
    }

    private static String profileStatus(VepReadinessReport report, boolean globallyEnabled) {
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

    private static String transcriptAnnotationStatus(VepReadinessReport report) {
        if (!report.isEnabled()) {
            return "DISABLED";
        }
        if (!report.isExecutionEnabled() || !"READY".equals(report.getStatus())) {
            return "UNAVAILABLE";
        }
        return "PENDING";
    }

    private static ProviderStatus maneStatus(VariantAnalysisProperties properties,
                                             VepReadinessReport report) {
        VariantAnalysisProperties.Vep vep = properties.getVep();
        boolean ready = "READY".equals(report.getStatus());
        ProviderStatus status = new ProviderStatus("MANE", vep.getManeVersion(), vep.isEnabled(), ready);
        status.setPrimaryEvidenceSource("VEP_MANE");
        status.setStatus(vep.isEnabled() ? report.getStatus() : "DISABLED");
        status.setMissingResources(report.getMissingResources());
        status.setWarnings(report.getWarnings());
        status.setExplanation(vep.isEnabled()
                ? "MANE fields are parsed from VEP output when present. " + report.getExplanation()
                : "MANE annotation is disabled with VEP.");
        status.setMessage(status.getExplanation());
        return status;
    }

    private static ProviderStatus lofteeStatus(VariantAnalysisProperties properties,
                                               VepReadinessReport report) {
        VariantAnalysisProperties.Vep vep = properties.getVep();
        boolean enabled = vep.isEnabled() && vep.isLofteeEnabled();
        boolean ready = enabled && "READY".equals(report.getStatus());
        ProviderStatus status = new ProviderStatus("LOFTEE", vep.getLofteeVersion(), enabled, ready);
        status.setFileReady(report.isLofteePluginPresent());
        status.setPrimaryEvidenceSource("VEP_LOFTEE");
        if (!enabled) {
            status.setStatus("DISABLED");
            status.setMessage("LOFTEE is disabled.");
        } else {
            status.setStatus(report.getStatus());
            status.setMissingResources(report.getMissingResources());
            status.setWarnings(report.getWarnings());
            status.setExplanation("LOFTEE fields are parsed from VEP output when present; HC is not treated as a clinical conclusion. "
                    + report.getExplanation());
            status.setMessage(status.getExplanation());
        }
        return status;
    }

    private static boolean indexedFileAvailable(Path file) {
        if (!readableNonEmptyFile(file)) {
            return false;
        }
        Path index = file.resolveSibling(file.getFileName() + ".tbi");
        Path csi = file.resolveSibling(file.getFileName() + ".csi");
        return readableNonEmptyFile(index) || readableNonEmptyFile(csi);
    }

    private static boolean plainFileAvailable(Path file) {
        return readableNonEmptyFile(file);
    }

    private static boolean indexedFileAvailable(Path file, Path explicitIndex) {
        if (!readableNonEmptyFile(file)) {
            return false;
        }
        if (explicitIndex != null) {
            return readableNonEmptyFile(explicitIndex);
        }
        return indexedFileAvailable(file);
    }

    /**
     * Status reporting must not claim a local resource is ready merely because
     * a path exists. This deliberately performs only a one-byte probe: it is
     * safe for large resources and does not execute a provider lookup.
     */
    private static boolean readableNonEmptyFile(Path file) {
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(file)) {
            return input.read() >= 0;
        } catch (IOException ex) {
            return false;
        }
    }

    public ProviderStatus getCadd() {
        return cadd;
    }

    public ProviderStatus getExomiser() {
        return exomiser;
    }

    public ProviderStatus getClinVar() {
        return clinVar;
    }

    public ProviderStatus getGnomad() {
        return gnomad;
    }

    public ProviderStatus getSpliceAi() {
        return spliceAi;
    }

    public ProviderStatus getAlphaMissense() {
        return alphaMissense;
    }

    public ProviderStatus getAlphaGenome() {
        return alphaGenome;
    }

    public ProviderStatus getVep() {
        return vep;
    }

    public ProviderStatus getMane() {
        return mane;
    }

    public ProviderStatus getLoftee() {
        return loftee;
    }

    public boolean isVepExecutionEnabled() {
        return vepExecutionEnabled;
    }

    public boolean isVepPreRankingEnabled() {
        return vepPreRankingEnabled;
    }

    public String getDefaultEngine() {
        return defaultEngine;
    }

    public String getAnalysisAssemblyPolicy() {
        return analysisAssemblyPolicy;
    }

    public String getNormalizationMode() {
        return normalizationMode;
    }

    public Map<String, NormalizationReferenceReadiness> getNormalizationProfiles() {
        return normalizationProfiles;
    }

    public Map<String, VepProfileReadiness> getVepProfiles() {
        return vepProfiles;
    }

    public static class VepProfileReadiness {

        private final String assembly;
        private final boolean enabled;
        private final boolean executionEnabled;
        private final boolean ready;
        private final String status;
        private final boolean cacheAvailable;
        private final boolean fastaAvailable;
        private final boolean fastaIndexAvailable;
        private final String providerVersion;
        private final String cacheVersion;
        private final String transcriptAnnotationStatus;
        private final List<String> missingResources;
        private final List<String> warnings;
        private final String explanation;

        VepProfileReadiness(String assembly,
                            boolean enabled,
                            boolean executionEnabled,
                            boolean ready,
                            String status,
                            boolean cacheAvailable,
                            boolean fastaAvailable,
                            boolean fastaIndexAvailable,
                            String providerVersion,
                            String cacheVersion,
                            String transcriptAnnotationStatus,
                            List<String> missingResources,
                            List<String> warnings,
                            String explanation) {
            this.assembly = assembly;
            this.enabled = enabled;
            this.executionEnabled = executionEnabled;
            this.ready = ready;
            this.status = status;
            this.cacheAvailable = cacheAvailable;
            this.fastaAvailable = fastaAvailable;
            this.fastaIndexAvailable = fastaIndexAvailable;
            this.providerVersion = providerVersion;
            this.cacheVersion = cacheVersion;
            this.transcriptAnnotationStatus = transcriptAnnotationStatus;
            this.missingResources = missingResources == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(missingResources));
            this.warnings = warnings == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(warnings));
            this.explanation = explanation;
        }

        public String getAssembly() {
            return assembly;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isExecutionEnabled() {
            return executionEnabled;
        }

        public boolean isReady() {
            return ready;
        }

        public String getStatus() {
            return status;
        }

        public boolean isCacheAvailable() {
            return cacheAvailable;
        }

        public boolean isFastaAvailable() {
            return fastaAvailable;
        }

        public boolean isFastaIndexAvailable() {
            return fastaIndexAvailable;
        }

        public String getProviderVersion() {
            return providerVersion;
        }

        public String getCacheVersion() {
            return cacheVersion;
        }

        public String getTranscriptAnnotationStatus() {
            return transcriptAnnotationStatus;
        }

        public List<String> getMissingResources() {
            return missingResources;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public String getExplanation() {
            return explanation;
        }
    }

    public static class ProviderStatus {

        private final String source;
        private final String version;
        private final boolean enabled;
        private final boolean ready;
        private String status;
        private String message;
        private boolean snvReady;
        private boolean indelReady;
        private boolean fileReady;
        private boolean pythonConfigured;
        private boolean scriptAvailable;
        private boolean apiKeyConfigured;
        private int defaultCandidateLimit;
        private int maxCandidateLimit;
        private int batchSize;
        private int configuredFileCount;
        private int availableFileCount;
        private boolean executionEnabled;
        private String transcriptAnnotationStatus;
        private int processedCount;
        private int successCount;
        private int failedCount;
        private int cacheHitCount;
        private String primaryEvidenceSource;
        private String fallbackEvidenceSource;
        private String runtimePlatform;
        private List<String> missingResources = Collections.emptyList();
        private List<String> warnings = Collections.emptyList();
        private String explanation;

        ProviderStatus(String source, String version, boolean enabled, boolean ready) {
            this.source = source;
            this.version = version;
            this.enabled = enabled;
            this.ready = ready;
            this.status = ready ? "READY" : (enabled ? "UNAVAILABLE" : "DISABLED");
        }

        public String getSource() {
            return source;
        }

        public String getVersion() {
            return version;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isReady() {
            return ready;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public boolean isSnvReady() {
            return snvReady;
        }

        public void setSnvReady(boolean snvReady) {
            this.snvReady = snvReady;
        }

        public boolean isIndelReady() {
            return indelReady;
        }

        public void setIndelReady(boolean indelReady) {
            this.indelReady = indelReady;
        }

        public boolean isFileReady() {
            return fileReady;
        }

        public void setFileReady(boolean fileReady) {
            this.fileReady = fileReady;
        }

        public boolean isPythonConfigured() {
            return pythonConfigured;
        }

        public void setPythonConfigured(boolean pythonConfigured) {
            this.pythonConfigured = pythonConfigured;
        }

        public boolean isScriptAvailable() {
            return scriptAvailable;
        }

        public void setScriptAvailable(boolean scriptAvailable) {
            this.scriptAvailable = scriptAvailable;
        }

        public boolean isApiKeyConfigured() {
            return apiKeyConfigured;
        }

        public void setApiKeyConfigured(boolean apiKeyConfigured) {
            this.apiKeyConfigured = apiKeyConfigured;
        }

        public int getDefaultCandidateLimit() {
            return defaultCandidateLimit;
        }

        public void setDefaultCandidateLimit(int defaultCandidateLimit) {
            this.defaultCandidateLimit = defaultCandidateLimit;
        }

        public int getMaxCandidateLimit() {
            return maxCandidateLimit;
        }

        public void setMaxCandidateLimit(int maxCandidateLimit) {
            this.maxCandidateLimit = maxCandidateLimit;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getConfiguredFileCount() {
            return configuredFileCount;
        }

        public void setConfiguredFileCount(int configuredFileCount) {
            this.configuredFileCount = configuredFileCount;
        }

        public int getAvailableFileCount() {
            return availableFileCount;
        }

        public void setAvailableFileCount(int availableFileCount) {
            this.availableFileCount = availableFileCount;
        }

        public boolean isExecutionEnabled() {
            return executionEnabled;
        }

        public void setExecutionEnabled(boolean executionEnabled) {
            this.executionEnabled = executionEnabled;
        }

        public String getTranscriptAnnotationStatus() {
            return transcriptAnnotationStatus;
        }

        public void setTranscriptAnnotationStatus(String transcriptAnnotationStatus) {
            this.transcriptAnnotationStatus = transcriptAnnotationStatus;
        }

        public int getProcessedCount() {
            return processedCount;
        }

        public void setProcessedCount(int processedCount) {
            this.processedCount = processedCount;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public void setSuccessCount(int successCount) {
            this.successCount = successCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(int failedCount) {
            this.failedCount = failedCount;
        }

        public int getCacheHitCount() {
            return cacheHitCount;
        }

        public void setCacheHitCount(int cacheHitCount) {
            this.cacheHitCount = cacheHitCount;
        }

        public String getPrimaryEvidenceSource() {
            return primaryEvidenceSource;
        }

        public void setPrimaryEvidenceSource(String primaryEvidenceSource) {
            this.primaryEvidenceSource = primaryEvidenceSource;
        }

        public String getFallbackEvidenceSource() {
            return fallbackEvidenceSource;
        }

        public void setFallbackEvidenceSource(String fallbackEvidenceSource) {
            this.fallbackEvidenceSource = fallbackEvidenceSource;
        }

        public String getRuntimePlatform() {
            return runtimePlatform;
        }

        public void setRuntimePlatform(String runtimePlatform) {
            this.runtimePlatform = runtimePlatform;
        }

        public List<String> getMissingResources() {
            return missingResources;
        }

        public void setMissingResources(List<String> missingResources) {
            this.missingResources = missingResources == null ? Collections.emptyList() : missingResources;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public void setWarnings(List<String> warnings) {
            this.warnings = warnings == null ? Collections.emptyList() : warnings;
        }

        public String getExplanation() {
            return explanation;
        }

        public void setExplanation(String explanation) {
            this.explanation = explanation;
        }
    }
}
