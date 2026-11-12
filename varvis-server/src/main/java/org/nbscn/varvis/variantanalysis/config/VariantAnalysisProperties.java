package org.nbscn.varvis.variantanalysis.config;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ConfigurationProperties(prefix = "variant-analysis")
public class VariantAnalysisProperties {

    private Path storageRoot = Paths.get(System.getProperty("java.io.tmpdir"), "varvis-variant-analyses");
    // Legacy binding compatibility only. Neither value enables deletion of formal history.
    private Duration retention = Duration.ZERO;
    private long cleanupDelayMs = Duration.ofHours(1).toMillis();
    private long maxUploadSizeBytes = 10L * 1024L * 1024L;
    private long maxExpandedSizeBytes = 100L * 1024L * 1024L;
    private String analysisAssemblyPolicy = "GRCh38";
    // application.yml selects the product default; this object remains legacy-safe for isolated tests.
    private AnalysisEngine defaultEngine = AnalysisEngine.VARVIS;
    private final LiftOver liftOver = new LiftOver();
    private final Normalization normalization = new Normalization();
    private final Persistence persistence = new Persistence();
    private final Exomiser exomiser = new Exomiser();
    private final Cadd cadd = new Cadd();
    private final ClinVar clinVar = new ClinVar();
    private final Gnomad gnomad = new Gnomad();
    private final SpliceAi spliceAi = new SpliceAi();
    private final AlphaMissense alphaMissense = new AlphaMissense();
    private final AlphaGenome alphaGenome = new AlphaGenome();
    private final Vep vep = new Vep();
    private final AcmgReady acmgReady = new AcmgReady();
    private final HpoOntology hpoOntology = new HpoOntology();
    private final PhenotypePrior phenotypePrior = new PhenotypePrior();
    private final PhenotypeRanking phenotypeRanking = new PhenotypeRanking();
    private final Ranking ranking = new Ranking();
    private final LargeFile largeFile = new LargeFile();
    private final Trio trio = new Trio();
    private final LlmShadow llmShadow = new LlmShadow();

    public Path getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    /** @deprecated Formal analysis history is persistent; this legacy setting is ignored. */
    @Deprecated
    public Duration getRetention() {
        return retention;
    }

    @Deprecated
    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    /** @deprecated There is no scheduled deletion of formal analysis history. */
    @Deprecated
    public long getCleanupDelayMs() {
        return cleanupDelayMs;
    }

    @Deprecated
    public void setCleanupDelayMs(long cleanupDelayMs) {
        this.cleanupDelayMs = cleanupDelayMs;
    }

    public long getMaxUploadSizeBytes() {
        return maxUploadSizeBytes;
    }

    public void setMaxUploadSizeBytes(long maxUploadSizeBytes) {
        this.maxUploadSizeBytes = maxUploadSizeBytes;
    }

    public long getMaxExpandedSizeBytes() {
        return maxExpandedSizeBytes;
    }

    public void setMaxExpandedSizeBytes(long maxExpandedSizeBytes) {
        this.maxExpandedSizeBytes = maxExpandedSizeBytes;
    }

    public String getAnalysisAssemblyPolicy() {
        return analysisAssemblyPolicy;
    }

    public void setAnalysisAssemblyPolicy(String analysisAssemblyPolicy) {
        this.analysisAssemblyPolicy = analysisAssemblyPolicy;
    }

    public AnalysisEngine getDefaultEngine() {
        return defaultEngine;
    }

    public void setDefaultEngine(AnalysisEngine defaultEngine) {
        this.defaultEngine = defaultEngine == null ? AnalysisEngine.VARVIS : defaultEngine;
    }

    public GenomeAssembly resolveAnalysisAssembly(GenomeAssembly inputAssembly) {
        GenomeAssembly normalizedInput = inputAssembly == null ? GenomeAssembly.GRCh38 : inputAssembly;
        String normalizedPolicy = normalizePolicy(analysisAssemblyPolicy);
        if ("input".equals(normalizedPolicy)
                || "preserveinput".equals(normalizedPolicy)
                || "native".equals(normalizedPolicy)) {
            return normalizedInput;
        }
        return GenomeAssembly.GRCh38;
    }

    public Normalization getNormalization() {
        return normalization;
    }

    public LiftOver getLiftOver() {
        return liftOver;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public Exomiser getExomiser() {
        return exomiser;
    }

    public Cadd getCadd() {
        return cadd;
    }

    public ClinVar getClinVar() {
        return clinVar;
    }

    public Gnomad getGnomad() {
        return gnomad;
    }

    public SpliceAi getSpliceAi() {
        return spliceAi;
    }

    public AlphaMissense getAlphaMissense() {
        return alphaMissense;
    }

    public AlphaGenome getAlphaGenome() {
        return alphaGenome;
    }

    public Vep getVep() {
        return vep;
    }

    public AcmgReady getAcmgReady() {
        return acmgReady;
    }

    public HpoOntology getHpoOntology() {
        return hpoOntology;
    }

    public PhenotypePrior getPhenotypePrior() {
        return phenotypePrior;
    }

    public PhenotypeRanking getPhenotypeRanking() {
        return phenotypeRanking;
    }

    public Ranking getRanking() {
        return ranking;
    }

    public LlmShadow getLlmShadow() {
        return llmShadow;
    }

    public LargeFile getLargeFile() {
        return largeFile;
    }

    public Trio getTrio() {
        return trio;
    }

    /** Safe, disabled-by-default Exomiser contract settings. */
    public static class Exomiser {

        private boolean enabled;
        private String version = "15.1.0";
        private String dataVersion = "2602";
        private String javaExecutable = "";
        private String javaHeap = "4g";
        private String cliJar = "";
        private String dataDirectory = "";
        private String analysisTemplate = "";
        private String resourceFingerprint = "";
        private Duration timeout = Duration.ofMinutes(30);
        /** Keeps Exomiser as the primary product ranking while retaining local evidence influence. */
        private double enginePrimaryWeight = 0.75d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getDataVersion() {
            return dataVersion;
        }

        public void setDataVersion(String dataVersion) {
            this.dataVersion = dataVersion;
        }

        public String getJavaExecutable() {
            return javaExecutable;
        }

        public void setJavaExecutable(String javaExecutable) {
            this.javaExecutable = javaExecutable;
        }

        public String getJavaHeap() {
            return javaHeap;
        }

        public void setJavaHeap(String javaHeap) {
            this.javaHeap = javaHeap;
        }

        public String getCliJar() {
            return cliJar;
        }

        public void setCliJar(String cliJar) {
            this.cliJar = cliJar;
        }

        public String getDataDirectory() {
            return dataDirectory;
        }

        public void setDataDirectory(String dataDirectory) {
            this.dataDirectory = dataDirectory;
        }

        public String getAnalysisTemplate() {
            return analysisTemplate;
        }

        public void setAnalysisTemplate(String analysisTemplate) {
            this.analysisTemplate = analysisTemplate;
        }

        public String getResourceFingerprint() {
            return resourceFingerprint;
        }

        public void setResourceFingerprint(String resourceFingerprint) {
            this.resourceFingerprint = resourceFingerprint;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public double getEnginePrimaryWeight() {
            return enginePrimaryWeight;
        }

        public void setEnginePrimaryWeight(double enginePrimaryWeight) {
            this.enginePrimaryWeight = enginePrimaryWeight;
        }

        public double normalizedEnginePrimaryWeight() {
            return Math.max(0.0d, Math.min(1.0d, enginePrimaryWeight));
        }
    }

    public static class Normalization {

        private Mode mode = Mode.COPY_ONLY;
        private String bcftoolsExecutable = "bcftools";
        private Duration timeout = Duration.ofMinutes(10);
        private String grch37Reference;
        private String grch38Reference;
        private String grch37ProfileId;
        private String grch38ProfileId;
        private String grch37ReferenceSha256;
        private String grch38ReferenceSha256;
        private String grch37ContigAliasFile;
        private String grch38ContigAliasFile;

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public String getBcftoolsExecutable() {
            return bcftoolsExecutable;
        }

        public void setBcftoolsExecutable(String bcftoolsExecutable) {
            this.bcftoolsExecutable = bcftoolsExecutable;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Path getGrch38Reference() {
            return pathOrNull(grch38Reference);
        }

        public void setGrch38Reference(String grch38Reference) {
            this.grch38Reference = grch38Reference;
        }

        public Path getGrch37Reference() {
            return pathOrNull(grch37Reference);
        }

        public void setGrch37Reference(String grch37Reference) {
            this.grch37Reference = grch37Reference;
        }

        public Path referenceFor(GenomeAssembly assembly) {
            if (assembly == GenomeAssembly.GRCh37) {
                return getGrch37Reference();
            }
            if (assembly == GenomeAssembly.GRCh38) {
                return getGrch38Reference();
            }
            return null;
        }

        public String getGrch37ProfileId() {
            return grch37ProfileId;
        }

        public void setGrch37ProfileId(String grch37ProfileId) {
            this.grch37ProfileId = grch37ProfileId;
        }

        public String getGrch38ProfileId() {
            return grch38ProfileId;
        }

        public void setGrch38ProfileId(String grch38ProfileId) {
            this.grch38ProfileId = grch38ProfileId;
        }

        public String profileIdFor(GenomeAssembly assembly) {
            if (assembly == GenomeAssembly.GRCh37) {
                return grch37ProfileId;
            }
            if (assembly == GenomeAssembly.GRCh38) {
                return grch38ProfileId;
            }
            return null;
        }

        public String getGrch37ReferenceSha256() {
            return grch37ReferenceSha256;
        }

        public void setGrch37ReferenceSha256(String grch37ReferenceSha256) {
            this.grch37ReferenceSha256 = grch37ReferenceSha256;
        }

        public String getGrch38ReferenceSha256() {
            return grch38ReferenceSha256;
        }

        public void setGrch38ReferenceSha256(String grch38ReferenceSha256) {
            this.grch38ReferenceSha256 = grch38ReferenceSha256;
        }

        public String referenceSha256For(GenomeAssembly assembly) {
            if (assembly == GenomeAssembly.GRCh37) {
                return grch37ReferenceSha256;
            }
            if (assembly == GenomeAssembly.GRCh38) {
                return grch38ReferenceSha256;
            }
            return null;
        }

        public Path getGrch37ContigAliasFile() {
            return pathOrNull(grch37ContigAliasFile);
        }

        public void setGrch37ContigAliasFile(String grch37ContigAliasFile) {
            this.grch37ContigAliasFile = grch37ContigAliasFile;
        }

        public Path getGrch38ContigAliasFile() {
            return pathOrNull(grch38ContigAliasFile);
        }

        public void setGrch38ContigAliasFile(String grch38ContigAliasFile) {
            this.grch38ContigAliasFile = grch38ContigAliasFile;
        }

        public Path contigAliasFileFor(GenomeAssembly assembly) {
            if (assembly == GenomeAssembly.GRCh37) {
                return getGrch37ContigAliasFile();
            }
            if (assembly == GenomeAssembly.GRCh38) {
                return getGrch38ContigAliasFile();
            }
            return null;
        }

        private Path pathOrNull(String value) {
            return value == null || value.trim().isEmpty() ? null : Paths.get(value.trim());
        }
    }

    public static class LiftOver {

        private Mode mode = Mode.DISABLED;
        private String javaExecutable = "java";
        private String javaHeap = "6g";
        private String picardJar;
        private String hg19ToHg38Chain;
        private Duration timeout = Duration.ofMinutes(30);
        private double minMatch = 0.95d;
        private boolean evidenceProjectionEnabled = true;
        private int evidenceProjectionMaxCandidates = 20000;

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public String getJavaExecutable() {
            return javaExecutable;
        }

        public void setJavaExecutable(String javaExecutable) {
            this.javaExecutable = javaExecutable;
        }

        public String getJavaHeap() {
            return javaHeap;
        }

        public void setJavaHeap(String javaHeap) {
            this.javaHeap = javaHeap;
        }

        public Path getPicardJar() {
            return pathOrNull(picardJar);
        }

        public void setPicardJar(String picardJar) {
            this.picardJar = picardJar;
        }

        public Path getHg19ToHg38Chain() {
            return pathOrNull(hg19ToHg38Chain);
        }

        public void setHg19ToHg38Chain(String hg19ToHg38Chain) {
            this.hg19ToHg38Chain = hg19ToHg38Chain;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public double getMinMatch() {
            return minMatch;
        }

        public void setMinMatch(double minMatch) {
            this.minMatch = minMatch;
        }

        public boolean isEvidenceProjectionEnabled() {
            return evidenceProjectionEnabled;
        }

        public void setEvidenceProjectionEnabled(boolean evidenceProjectionEnabled) {
            this.evidenceProjectionEnabled = evidenceProjectionEnabled;
        }

        public int getEvidenceProjectionMaxCandidates() {
            return evidenceProjectionMaxCandidates;
        }

        public void setEvidenceProjectionMaxCandidates(int evidenceProjectionMaxCandidates) {
            this.evidenceProjectionMaxCandidates = evidenceProjectionMaxCandidates;
        }

        public int normalizedEvidenceProjectionMaxCandidates() {
            return Math.max(0, evidenceProjectionMaxCandidates);
        }

        private Path pathOrNull(String value) {
            return value == null || value.trim().isEmpty() ? null : Paths.get(value.trim());
        }

        public enum Mode {
            DISABLED,
            PICARD
        }
    }

    public static class Cadd {

        private boolean enabled;
        private String version = "v1.7";
        private String tabixExecutable = "tabix";
        private Duration timeout = Duration.ofMinutes(30);
        private String grch38SnvFile;
        private String grch38IndelFile;
        private int queryWindowBp = 1000;
        private int queryMergeGapBp = 200;
        private int parallelism = 1;
        private int maxCandidates = 5000;
        private int batchSize = 500;
        private Duration batchTimeout = Duration.ofSeconds(60);
        private int fallbackMaxVariants;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getTabixExecutable() {
            return tabixExecutable;
        }

        public void setTabixExecutable(String tabixExecutable) {
            this.tabixExecutable = tabixExecutable;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Path getGrch38SnvFile() {
            return pathOrNull(grch38SnvFile);
        }

        public void setGrch38SnvFile(String grch38SnvFile) {
            this.grch38SnvFile = grch38SnvFile;
        }

        public Path getGrch38IndelFile() {
            return pathOrNull(grch38IndelFile);
        }

        public void setGrch38IndelFile(String grch38IndelFile) {
            this.grch38IndelFile = grch38IndelFile;
        }

        public int getQueryWindowBp() {
            return queryWindowBp;
        }

        public void setQueryWindowBp(int queryWindowBp) {
            this.queryWindowBp = queryWindowBp;
        }

        public int getQueryMergeGapBp() {
            return queryMergeGapBp;
        }

        public void setQueryMergeGapBp(int queryMergeGapBp) {
            this.queryMergeGapBp = queryMergeGapBp;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        public int normalizedMaxCandidates() {
            return Math.max(0, maxCandidates);
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int normalizedBatchSize() {
            return Math.max(1, batchSize);
        }

        public Duration getBatchTimeout() {
            return batchTimeout;
        }

        public void setBatchTimeout(Duration batchTimeout) {
            this.batchTimeout = batchTimeout;
        }

        public Duration normalizedBatchTimeout() {
            if (batchTimeout != null && !batchTimeout.isNegative() && !batchTimeout.isZero()) {
                return batchTimeout;
            }
            return timeout == null ? Duration.ofSeconds(60) : timeout;
        }

        public int getFallbackMaxVariants() {
            return fallbackMaxVariants;
        }

        public void setFallbackMaxVariants(int fallbackMaxVariants) {
            this.fallbackMaxVariants = fallbackMaxVariants;
        }

        public int normalizedFallbackMaxVariants() {
            return Math.max(0, fallbackMaxVariants);
        }

        private Path pathOrNull(String value) {
            return value == null || value.trim().isEmpty() ? null : Paths.get(value.trim());
        }
    }

    public static class Persistence {

        private int batchSize = 500;
        private int observationBatchSize = 200;
        private int interpretationBatchSize = 200;
        private int interpretationBatchMaxBytes = 1048576;
        private int deletionBatchSize = 10000;
        private int retryAttempts = 3;
        private Duration retryBackoff = Duration.ofSeconds(1);
        private boolean persistObservations = true;
        private boolean persistSummaryOnlyInterpretations;
        private String tablePrefix;
        private boolean acceptanceTableBootstrapEnabled;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int normalizedBatchSize() {
            return Math.max(1, batchSize);
        }

        public int getObservationBatchSize() {
            return observationBatchSize;
        }

        public void setObservationBatchSize(int observationBatchSize) {
            this.observationBatchSize = observationBatchSize;
        }

        public int normalizedObservationBatchSize() {
            return observationBatchSize > 0 ? observationBatchSize : normalizedBatchSize();
        }

        public boolean isPersistObservations() {
            return persistObservations;
        }

        public void setPersistObservations(boolean persistObservations) {
            this.persistObservations = persistObservations;
        }

        public boolean isPersistSummaryOnlyInterpretations() {
            return persistSummaryOnlyInterpretations;
        }

        public void setPersistSummaryOnlyInterpretations(boolean persistSummaryOnlyInterpretations) {
            this.persistSummaryOnlyInterpretations = persistSummaryOnlyInterpretations;
        }

        /**
         * Optional, private table prefix used only by a controlled acceptance
         * environment. Empty preserves the established production table names.
         */
        public String getTablePrefix() {
            return tablePrefix;
        }

        public void setTablePrefix(String tablePrefix) {
            this.tablePrefix = tablePrefix;
        }

        public boolean isAcceptanceTableBootstrapEnabled() {
            return acceptanceTableBootstrapEnabled;
        }

        public void setAcceptanceTableBootstrapEnabled(boolean acceptanceTableBootstrapEnabled) {
            this.acceptanceTableBootstrapEnabled = acceptanceTableBootstrapEnabled;
        }

        public int getInterpretationBatchSize() {
            return interpretationBatchSize;
        }

        public void setInterpretationBatchSize(int interpretationBatchSize) {
            this.interpretationBatchSize = interpretationBatchSize;
        }

        public int normalizedInterpretationBatchSize() {
            return interpretationBatchSize > 0 ? interpretationBatchSize : normalizedBatchSize();
        }

        public int getInterpretationBatchMaxBytes() {
            return interpretationBatchMaxBytes;
        }

        public void setInterpretationBatchMaxBytes(int interpretationBatchMaxBytes) {
            this.interpretationBatchMaxBytes = interpretationBatchMaxBytes;
        }

        public int normalizedInterpretationBatchMaxBytes() {
            return Math.max(0, interpretationBatchMaxBytes);
        }

        public int getDeletionBatchSize() {
            return deletionBatchSize;
        }

        public void setDeletionBatchSize(int deletionBatchSize) {
            this.deletionBatchSize = deletionBatchSize;
        }

        public int normalizedDeletionBatchSize() {
            return deletionBatchSize > 0 ? deletionBatchSize : 10000;
        }

        public int getRetryAttempts() {
            return retryAttempts;
        }

        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        public int normalizedRetryAttempts() {
            return Math.max(1, retryAttempts);
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public Duration normalizedRetryBackoff() {
            return retryBackoff == null || retryBackoff.isNegative() ? Duration.ZERO : retryBackoff;
        }
    }

    public static class LargeFile {

        private boolean enabled = true;
        private int variantThreshold = 100000;
        private int passVariantThreshold = 50000;
        private int candidateHardLimit = 20000;
        private int expensiveAnnotationLimit = 20000;
        private boolean defaultSkipExpensiveForFiltered = true;
        private boolean phenotypePriorRescueEnabled = true;
        private int phenotypePriorRescueMaxGenes = 50;
        private int phenotypePriorRescueMaxVariantsPerGene = 2;
        private int phenotypePriorRescuePaddingBp = 0;
        private String phenotypePriorRescueIntervalFile;
        private boolean phenotypePriorRescueUseVarvisGeneCoordinates;
        private String phenotypePriorRescueGeneCoordinateAssembly;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getVariantThreshold() {
            return variantThreshold;
        }

        public void setVariantThreshold(int variantThreshold) {
            this.variantThreshold = variantThreshold;
        }

        public int normalizedVariantThreshold() {
            return Math.max(0, variantThreshold);
        }

        public int getPassVariantThreshold() {
            return passVariantThreshold;
        }

        public void setPassVariantThreshold(int passVariantThreshold) {
            this.passVariantThreshold = passVariantThreshold;
        }

        public int normalizedPassVariantThreshold() {
            return Math.max(0, passVariantThreshold);
        }

        public int getCandidateHardLimit() {
            return candidateHardLimit;
        }

        public void setCandidateHardLimit(int candidateHardLimit) {
            this.candidateHardLimit = candidateHardLimit;
        }

        public int normalizedCandidateHardLimit() {
            return Math.max(0, candidateHardLimit);
        }

        public int getExpensiveAnnotationLimit() {
            return expensiveAnnotationLimit;
        }

        public void setExpensiveAnnotationLimit(int expensiveAnnotationLimit) {
            this.expensiveAnnotationLimit = expensiveAnnotationLimit;
        }

        public int normalizedExpensiveAnnotationLimit() {
            return Math.max(0, expensiveAnnotationLimit);
        }

        public boolean isDefaultSkipExpensiveForFiltered() {
            return defaultSkipExpensiveForFiltered;
        }

        public void setDefaultSkipExpensiveForFiltered(boolean defaultSkipExpensiveForFiltered) {
            this.defaultSkipExpensiveForFiltered = defaultSkipExpensiveForFiltered;
        }

        public boolean isPhenotypePriorRescueEnabled() {
            return phenotypePriorRescueEnabled;
        }

        public void setPhenotypePriorRescueEnabled(boolean phenotypePriorRescueEnabled) {
            this.phenotypePriorRescueEnabled = phenotypePriorRescueEnabled;
        }

        public int getPhenotypePriorRescueMaxGenes() {
            return phenotypePriorRescueMaxGenes;
        }

        public void setPhenotypePriorRescueMaxGenes(int phenotypePriorRescueMaxGenes) {
            this.phenotypePriorRescueMaxGenes = phenotypePriorRescueMaxGenes;
        }

        public int normalizedPhenotypePriorRescueMaxGenes() {
            return Math.max(0, phenotypePriorRescueMaxGenes);
        }

        public int getPhenotypePriorRescueMaxVariantsPerGene() {
            return phenotypePriorRescueMaxVariantsPerGene;
        }

        public void setPhenotypePriorRescueMaxVariantsPerGene(
                int phenotypePriorRescueMaxVariantsPerGene) {
            this.phenotypePriorRescueMaxVariantsPerGene = phenotypePriorRescueMaxVariantsPerGene;
        }

        public int normalizedPhenotypePriorRescueMaxVariantsPerGene() {
            return Math.max(0, phenotypePriorRescueMaxVariantsPerGene);
        }

        public int getPhenotypePriorRescuePaddingBp() {
            return phenotypePriorRescuePaddingBp;
        }

        public void setPhenotypePriorRescuePaddingBp(int phenotypePriorRescuePaddingBp) {
            this.phenotypePriorRescuePaddingBp = phenotypePriorRescuePaddingBp;
        }

        public int normalizedPhenotypePriorRescuePaddingBp() {
            return Math.max(0, phenotypePriorRescuePaddingBp);
        }

        public Path getPhenotypePriorRescueIntervalFile() {
            return pathOrNull(phenotypePriorRescueIntervalFile);
        }

        public void setPhenotypePriorRescueIntervalFile(String phenotypePriorRescueIntervalFile) {
            this.phenotypePriorRescueIntervalFile = phenotypePriorRescueIntervalFile;
        }

        public boolean isPhenotypePriorRescueUseVarvisGeneCoordinates() {
            return phenotypePriorRescueUseVarvisGeneCoordinates;
        }

        public void setPhenotypePriorRescueUseVarvisGeneCoordinates(
                boolean phenotypePriorRescueUseVarvisGeneCoordinates) {
            this.phenotypePriorRescueUseVarvisGeneCoordinates = phenotypePriorRescueUseVarvisGeneCoordinates;
        }

        public String getPhenotypePriorRescueGeneCoordinateAssembly() {
            return phenotypePriorRescueGeneCoordinateAssembly;
        }

        public void setPhenotypePriorRescueGeneCoordinateAssembly(String phenotypePriorRescueGeneCoordinateAssembly) {
            this.phenotypePriorRescueGeneCoordinateAssembly = phenotypePriorRescueGeneCoordinateAssembly;
        }
    }

    public static class ClinVar {

        private boolean enabled;
        private String version = "local-grch38";
        private String grch37Version;
        private String grch38Version;
        private String grch37File;
        private String grch38File;
        private boolean allowVcfInfoFallback = true;
        private Duration timeout = Duration.ofMinutes(5);
        private final CompactIndex compactIndex = new CompactIndex();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getGrch37Version() {
            return grch37Version;
        }

        public void setGrch37Version(String grch37Version) {
            this.grch37Version = grch37Version;
        }

        public String getGrch38Version() {
            return grch38Version;
        }

        public void setGrch38Version(String grch38Version) {
            this.grch38Version = grch38Version;
        }

        public String versionFor(String assembly) {
            if ("GRCh37".equalsIgnoreCase(assembly) && hasText(grch37Version)) {
                return grch37Version.trim();
            }
            if ("GRCh38".equalsIgnoreCase(assembly) && hasText(grch38Version)) {
                return grch38Version.trim();
            }
            return version;
        }

        private boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }

        public Path getGrch37File() {
            return pathOrNull(grch37File);
        }

        public void setGrch37File(String grch37File) {
            this.grch37File = grch37File;
        }

        public Path getGrch38File() {
            return pathOrNull(grch38File);
        }

        public void setGrch38File(String grch38File) {
            this.grch38File = grch38File;
        }

        public boolean isAllowVcfInfoFallback() {
            return allowVcfInfoFallback;
        }

        public void setAllowVcfInfoFallback(boolean allowVcfInfoFallback) {
            this.allowVcfInfoFallback = allowVcfInfoFallback;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public CompactIndex getCompactIndex() {
            return compactIndex;
        }

        public static class CompactIndex {

            private boolean enabled;
            private String file;
            private String sourceVersion;
            private int batchSize = 5000;
            private boolean allowTabixFallback = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public Path getFile() {
                return pathOrNull(file);
            }

            public void setFile(String file) {
                this.file = file;
            }

            public String getSourceVersion() {
                return sourceVersion;
            }

            public void setSourceVersion(String sourceVersion) {
                this.sourceVersion = sourceVersion;
            }

            public int getBatchSize() {
                return batchSize;
            }

            public void setBatchSize(int batchSize) {
                this.batchSize = batchSize;
            }

            public int normalizedBatchSize() {
                return Math.max(1, batchSize);
            }

            public boolean isAllowTabixFallback() {
                return allowTabixFallback;
            }

            public void setAllowTabixFallback(boolean allowTabixFallback) {
                this.allowTabixFallback = allowTabixFallback;
            }
        }
    }

    public static class Gnomad {

        private boolean enabled;
        private String version = "local-grch38";
        private String tabixExecutable = "tabix";
        private String bcftoolsExecutable = "bcftools";
        private String grch38Files;
        private String grch38CompactFiles;
        private boolean allowFullVcfFallback;
        private boolean populationInputEnabled;
        private boolean localPopulationInProcessEnabled;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        public boolean isLocalPopulationInProcessEnabled() { return localPopulationInProcessEnabled; }
        public void setLocalPopulationInProcessEnabled(boolean value) { localPopulationInProcessEnabled = value; }
        private boolean allowVcfInfoFallback = true;
        private String alleleFrequencyFields = "AF,AF_popmax,gnomAD_AF,gnomADg_AF,gnomADe_AF";
        private Duration timeout = Duration.ofMinutes(5);
        private int queryWindowBp = 1000;
        private int queryMergeGapBp = 200;
        private int tabixCacheMb = 256;
        private int parallelism = 4;
        private int remoteMaxAttempts = 1;
        private Duration remoteTotalTimeout;
        private String remoteIndexCacheDir;
        private String remoteTransport = "TABIX";
        private String rangeBindingsFile;
        private boolean ba1LocalExomesEnabled;
        public boolean isBa1LocalExomesEnabled() { return ba1LocalExomesEnabled; }
        public void setBa1LocalExomesEnabled(boolean value) { ba1LocalExomesEnabled = value; }
        public String getRemoteTransport() { return remoteTransport; }
        public void setRemoteTransport(String value) {
            if (!"TABIX".equals(value) && !"jdk-range-tabix-v1".equals(value)) throw new IllegalArgumentException("GNOMAD_TRANSPORT_INVALID");
            remoteTransport = value;
        }
        public String getRangeBindingsFile() { return rangeBindingsFile; }
        public void setRangeBindingsFile(String value) { rangeBindingsFile = value; }
        private Integer ba1NativeTimeoutSeconds;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public Integer getBa1NativeTimeoutSeconds() { return ba1NativeTimeoutSeconds; }
        public void setBa1NativeTimeoutSeconds(Integer value) {
            if (value != null && (value < 1 || value > 120))
                throw new IllegalArgumentException("BA1_SUBTYPE_TIMEOUT_INVALID");
            ba1NativeTimeoutSeconds = value;
        }


        @com.fasterxml.jackson.annotation.JsonIgnore
        public Path getRemoteIndexCacheDir() { return pathOrNull(remoteIndexCacheDir); }
        public void setRemoteIndexCacheDir(String value) { remoteIndexCacheDir = value; }

        public int getRemoteMaxAttempts() { return remoteMaxAttempts; }
        public void setRemoteMaxAttempts(int value) {
            if (value < 1 || value > 3) throw new IllegalArgumentException("GNOMAD_ATTEMPTS_OUT_OF_RANGE");
            remoteMaxAttempts = value;
        }
        public Duration getRemoteTotalTimeout() { return remoteTotalTimeout; }
        public void setRemoteTotalTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("GNOMAD_TOTAL_TIMEOUT_INVALID");
            remoteTotalTimeout = value;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getTabixExecutable() {
            return tabixExecutable;
        }

        public void setTabixExecutable(String tabixExecutable) {
            this.tabixExecutable = tabixExecutable;
        }

        public String getBcftoolsExecutable() {
            return bcftoolsExecutable;
        }

        public void setBcftoolsExecutable(String bcftoolsExecutable) {
            this.bcftoolsExecutable = bcftoolsExecutable;
        }

        public List<Path> getGrch38Files() {
            return pathsOrEmpty(grch38Files);
        }

        public void setGrch38Files(String grch38Files) {
            this.grch38Files = grch38Files;
        }

        public List<Path> getGrch38CompactFiles() {
            return pathsOrEmpty(grch38CompactFiles);
        }

        public void setGrch38CompactFiles(String grch38CompactFiles) {
            this.grch38CompactFiles = grch38CompactFiles;
        }

        public boolean isAllowFullVcfFallback() {
            return allowFullVcfFallback;
        }

        public void setAllowFullVcfFallback(boolean allowFullVcfFallback) {
            this.allowFullVcfFallback = allowFullVcfFallback;
        }

        public boolean isPopulationInputEnabled() {
            return populationInputEnabled;
        }

        public void setPopulationInputEnabled(boolean populationInputEnabled) {
            this.populationInputEnabled = populationInputEnabled;
        }

        public boolean isAllowVcfInfoFallback() {
            return allowVcfInfoFallback;
        }

        public void setAllowVcfInfoFallback(boolean allowVcfInfoFallback) {
            this.allowVcfInfoFallback = allowVcfInfoFallback;
        }

        public List<String> getAlleleFrequencyFields() {
            if (alleleFrequencyFields == null || alleleFrequencyFields.trim().isEmpty()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<String>();
            for (String value : alleleFrequencyFields.split("[,;]")) {
                if (value != null && !value.trim().isEmpty()) {
                    result.add(value.trim());
                }
            }
            return result;
        }

        public void setAlleleFrequencyFields(String alleleFrequencyFields) {
            this.alleleFrequencyFields = alleleFrequencyFields;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getQueryWindowBp() {
            return queryWindowBp;
        }

        public void setQueryWindowBp(int queryWindowBp) {
            this.queryWindowBp = queryWindowBp;
        }

        public int getQueryMergeGapBp() {
            return queryMergeGapBp;
        }

        public void setQueryMergeGapBp(int queryMergeGapBp) {
            this.queryMergeGapBp = queryMergeGapBp;
        }

        public int getTabixCacheMb() {
            return tabixCacheMb;
        }

        public void setTabixCacheMb(int tabixCacheMb) {
            this.tabixCacheMb = tabixCacheMb;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }
    }

    public static class Ranking {

        private double caddReviewThreshold = 15.0d;
        private double caddHighlightThreshold = 20.0d;
        private double spliceAiHighlightThreshold = 0.5d;
        private double alphaMissenseHighlightThreshold = 0.564d;
        private double commonAlleleFrequencyThreshold = 0.01d;
        private double veryCommonAlleleFrequencyThreshold = 0.05d;
        private int minDepth = 10;
        private int minGenotypeQuality = 20;
        private double minAlleleBalance = 0.2d;
        private int compactModeThreshold = 100000;
        private int detailedMaxCandidates = 5000;
        private String version = "tier-breakdown-v1";

        public double getCaddReviewThreshold() {
            return caddReviewThreshold;
        }

        public void setCaddReviewThreshold(double caddReviewThreshold) {
            this.caddReviewThreshold = caddReviewThreshold;
        }

        public double getCaddHighlightThreshold() {
            return caddHighlightThreshold;
        }

        public void setCaddHighlightThreshold(double caddHighlightThreshold) {
            this.caddHighlightThreshold = caddHighlightThreshold;
        }

        public double getSpliceAiHighlightThreshold() {
            return spliceAiHighlightThreshold;
        }

        public void setSpliceAiHighlightThreshold(double spliceAiHighlightThreshold) {
            this.spliceAiHighlightThreshold = spliceAiHighlightThreshold;
        }

        public double getAlphaMissenseHighlightThreshold() {
            return alphaMissenseHighlightThreshold;
        }

        public void setAlphaMissenseHighlightThreshold(double alphaMissenseHighlightThreshold) {
            this.alphaMissenseHighlightThreshold = alphaMissenseHighlightThreshold;
        }

        public double getCommonAlleleFrequencyThreshold() {
            return commonAlleleFrequencyThreshold;
        }

        public void setCommonAlleleFrequencyThreshold(double commonAlleleFrequencyThreshold) {
            this.commonAlleleFrequencyThreshold = commonAlleleFrequencyThreshold;
        }

        public double getVeryCommonAlleleFrequencyThreshold() {
            return veryCommonAlleleFrequencyThreshold;
        }

        public void setVeryCommonAlleleFrequencyThreshold(double veryCommonAlleleFrequencyThreshold) {
            this.veryCommonAlleleFrequencyThreshold = veryCommonAlleleFrequencyThreshold;
        }

        public int getMinDepth() {
            return minDepth;
        }

        public void setMinDepth(int minDepth) {
            this.minDepth = minDepth;
        }

        public int getMinGenotypeQuality() {
            return minGenotypeQuality;
        }

        public void setMinGenotypeQuality(int minGenotypeQuality) {
            this.minGenotypeQuality = minGenotypeQuality;
        }

        public double getMinAlleleBalance() {
            return minAlleleBalance;
        }

        public void setMinAlleleBalance(double minAlleleBalance) {
            this.minAlleleBalance = minAlleleBalance;
        }

        public int getCompactModeThreshold() {
            return compactModeThreshold;
        }

        public void setCompactModeThreshold(int compactModeThreshold) {
            this.compactModeThreshold = compactModeThreshold;
        }

        public int normalizedCompactModeThreshold() {
            return Math.max(0, compactModeThreshold);
        }

        public int getDetailedMaxCandidates() {
            return detailedMaxCandidates;
        }

        public void setDetailedMaxCandidates(int detailedMaxCandidates) {
            this.detailedMaxCandidates = detailedMaxCandidates;
        }

        public int normalizedDetailedMaxCandidates() {
            return Math.max(0, detailedMaxCandidates);
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    /** Privacy boundary for the non-clinical, read-only LLM shadow preview. */
    public static class LlmShadow {

        private boolean enabled = false;
        private String provider = "NONE";
        private int candidateLimit = 10;
        private int maxOutputCharacters = 1000;
        private String promptSchemaVersion = "llm-shadow-input-v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public int getCandidateLimit() {
            return candidateLimit;
        }

        public void setCandidateLimit(int candidateLimit) {
            this.candidateLimit = candidateLimit;
        }

        public int normalizedCandidateLimit() {
            return Math.max(1, Math.min(10, candidateLimit));
        }

        public int getMaxOutputCharacters() {
            return maxOutputCharacters;
        }

        public void setMaxOutputCharacters(int maxOutputCharacters) {
            this.maxOutputCharacters = maxOutputCharacters;
        }

        public int normalizedMaxOutputCharacters() {
            return Math.max(128, Math.min(2000, maxOutputCharacters));
        }

        public String getPromptSchemaVersion() {
            return promptSchemaVersion;
        }

        public void setPromptSchemaVersion(String promptSchemaVersion) {
            this.promptSchemaVersion = promptSchemaVersion;
        }

        public String normalizedPromptSchemaVersion() {
            if (promptSchemaVersion == null) return "llm-shadow-input-v1";
            String value = promptSchemaVersion.trim();
            return value.length() >= 1 && value.length() <= 40 && value.matches("[A-Za-z0-9_.-]+")
                    ? value : "llm-shadow-input-v1";
        }
    }

    public static class SpliceAi {

        private boolean enabled;
        private String version = "v1.3.1";
        private String grch38File;
        private String resourcePath;
        private String indexPath;
        private String snvResourcePath;
        private String snvIndexPath;
        private String indelResourcePath;
        private String indelIndexPath;
        private String genomeBuild = "GRCh38";
        private boolean allowVcfInfoFallback = true;
        private Duration timeout = Duration.ofSeconds(10);
        private int maxCandidates = 0;
        private int batchSize = 500;
        private double highThreshold = 0.8d;
        private double moderateThreshold = 0.5d;
        private double lowThreshold = 0.2d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public Path getGrch38File() {
            return getResourcePath();
        }

        public void setGrch38File(String grch38File) {
            this.grch38File = grch38File;
        }

        public Path getResourcePath() {
            Path resource = pathOrNull(resourcePath);
            return resource == null ? pathOrNull(grch38File) : resource;
        }

        public void setResourcePath(String resourcePath) {
            this.resourcePath = resourcePath;
        }

        public Path getIndexPath() {
            return pathOrNull(indexPath);
        }

        public void setIndexPath(String indexPath) {
            this.indexPath = indexPath;
        }

        public Path getSnvResourcePath() {
            Path resource = pathOrNull(snvResourcePath);
            return resource == null ? getResourcePath() : resource;
        }

        public void setSnvResourcePath(String snvResourcePath) {
            this.snvResourcePath = snvResourcePath;
        }

        public Path getSnvIndexPath() {
            Path index = pathOrNull(snvIndexPath);
            return index == null ? getIndexPath() : index;
        }

        public void setSnvIndexPath(String snvIndexPath) {
            this.snvIndexPath = snvIndexPath;
        }

        public Path getIndelResourcePath() {
            Path resource = pathOrNull(indelResourcePath);
            return resource == null ? getResourcePath() : resource;
        }

        public void setIndelResourcePath(String indelResourcePath) {
            this.indelResourcePath = indelResourcePath;
        }

        public Path getIndelIndexPath() {
            Path index = pathOrNull(indelIndexPath);
            return index == null ? getIndexPath() : index;
        }

        public void setIndelIndexPath(String indelIndexPath) {
            this.indelIndexPath = indelIndexPath;
        }

        public String getGenomeBuild() {
            return genomeBuild;
        }

        public void setGenomeBuild(String genomeBuild) {
            this.genomeBuild = genomeBuild;
        }

        public boolean isAllowVcfInfoFallback() {
            return allowVcfInfoFallback;
        }

        public void setAllowVcfInfoFallback(boolean allowVcfInfoFallback) {
            this.allowVcfInfoFallback = allowVcfInfoFallback;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration normalizedTimeout() {
            return timeout == null ? Duration.ofSeconds(10) : timeout;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        public int normalizedMaxCandidates() {
            return Math.max(0, maxCandidates);
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int normalizedBatchSize() {
            return Math.max(1, batchSize);
        }

        public double getHighThreshold() {
            return highThreshold;
        }

        public void setHighThreshold(double highThreshold) {
            this.highThreshold = highThreshold;
        }

        public double getModerateThreshold() {
            return moderateThreshold;
        }

        public void setModerateThreshold(double moderateThreshold) {
            this.moderateThreshold = moderateThreshold;
        }

        public double getLowThreshold() {
            return lowThreshold;
        }

        public void setLowThreshold(double lowThreshold) {
            this.lowThreshold = lowThreshold;
        }
    }

    public static class AlphaMissense {

        private boolean enabled;
        private String version = "2023-08-03";
        private String grch38File;
        private Duration timeout = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public Path getGrch38File() {
            return pathOrNull(grch38File);
        }

        public void setGrch38File(String grch38File) {
            this.grch38File = grch38File;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class AlphaGenome {

        private boolean enabled;
        private String version = "official-api";
        private String pythonExecutable = "python";
        private String scriptPath = "scripts/alphagenome_score_variant.py";
        private Duration timeout = Duration.ofMinutes(5);
        private int defaultCandidateLimit = 20;
        private int maxCandidateLimit = 100;
        private int sequenceLength = 1048576;
        private int maxAttempts = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getPythonExecutable() {
            return pythonExecutable;
        }

        public void setPythonExecutable(String pythonExecutable) {
            this.pythonExecutable = pythonExecutable;
        }

        public Path getScriptPath() {
            return pathOrNull(scriptPath);
        }

        public void setScriptPath(String scriptPath) {
            this.scriptPath = scriptPath;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
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

        public int getSequenceLength() {
            return sequenceLength;
        }

        public void setSequenceLength(int sequenceLength) {
            this.sequenceLength = sequenceLength;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Vep {

        private static final String TRANSCRIPT_PROJECTION_VERSION = "transcript-consequence-v3-nearest-exon-jb-8789d689";

        private boolean enabled;
        private boolean ownedExecutionEnabled;
        private long ownedMaxOutputBytes = 64L * 1024 * 1024;
        public boolean isOwnedExecutionEnabled() { return ownedExecutionEnabled; }
        public void setOwnedExecutionEnabled(boolean value) { ownedExecutionEnabled = value; }
        public long getOwnedMaxOutputBytes() { return ownedMaxOutputBytes; }
        public void setOwnedMaxOutputBytes(long value) { ownedMaxOutputBytes = value; }
        private String executable;
        private String cacheDir;
        private String fasta;
        private String pluginsDir;
        private boolean lofteeEnabled;
        private boolean executionEnabled;
        private boolean preRankingEnabled = true;
        private int preRankingMaxCandidates = 3000;
        private int preRankingBatchSize = 200;
        private int preRankingTimeoutSeconds = 600;
        private boolean preRankingCacheEnabled = true;
        private boolean preRankingFailOpen = true;
        private int preRankingMaxConcurrency = 1;
        private String preRankingTempDir;
        private String preRankingProvider = "vep";
        private String preRankingAssemblyPolicy = "strict";
        private int maxCandidates = 300;
        private int batchSize = 500;
        private int timeoutSeconds = 600;
        private String mode = "top-candidates";
        private String tempDir;
        private boolean cleanupTemp = true;
        private String version = "not-configured";
        private String ensemblCacheVersion = "not-configured";
        private String maneVersion = "not-configured";
        private String lofteeVersion = "not-configured";
        private final Map<String, ResourceProfile> resources = new LinkedHashMap<String, ResourceProfile>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public Path getCacheDir() {
            return pathOrNull(cacheDir);
        }

        public void setCacheDir(String cacheDir) {
            this.cacheDir = cacheDir;
        }

        public Path getFasta() {
            return pathOrNull(fasta);
        }

        public void setFasta(String fasta) {
            this.fasta = fasta;
        }

        public Path getPluginsDir() {
            return pathOrNull(pluginsDir);
        }

        public void setPluginsDir(String pluginsDir) {
            this.pluginsDir = pluginsDir;
        }

        public boolean isLofteeEnabled() {
            return lofteeEnabled;
        }

        public void setLofteeEnabled(boolean lofteeEnabled) {
            this.lofteeEnabled = lofteeEnabled;
        }

        public boolean isExecutionEnabled() {
            return executionEnabled;
        }

        public void setExecutionEnabled(boolean executionEnabled) {
            this.executionEnabled = executionEnabled;
        }

        public boolean isPreRankingEnabled() {
            return preRankingEnabled;
        }

        public void setPreRankingEnabled(boolean preRankingEnabled) {
            this.preRankingEnabled = preRankingEnabled;
        }

        public int getPreRankingMaxCandidates() {
            return preRankingMaxCandidates;
        }

        public void setPreRankingMaxCandidates(int preRankingMaxCandidates) {
            this.preRankingMaxCandidates = preRankingMaxCandidates;
        }

        public int normalizedPreRankingMaxCandidates() {
            return Math.max(0, preRankingMaxCandidates);
        }

        public int getPreRankingBatchSize() {
            return preRankingBatchSize;
        }

        public void setPreRankingBatchSize(int preRankingBatchSize) {
            this.preRankingBatchSize = preRankingBatchSize;
        }

        public int normalizedPreRankingBatchSize() {
            return Math.max(1, preRankingBatchSize);
        }

        public int getPreRankingTimeoutSeconds() {
            return preRankingTimeoutSeconds;
        }

        public void setPreRankingTimeoutSeconds(int preRankingTimeoutSeconds) {
            this.preRankingTimeoutSeconds = preRankingTimeoutSeconds;
        }

        public Duration normalizedPreRankingTimeout() {
            return Duration.ofSeconds(Math.max(1, preRankingTimeoutSeconds));
        }

        public boolean isPreRankingCacheEnabled() {
            return preRankingCacheEnabled;
        }

        public void setPreRankingCacheEnabled(boolean preRankingCacheEnabled) {
            this.preRankingCacheEnabled = preRankingCacheEnabled;
        }

        public boolean isPreRankingFailOpen() {
            return preRankingFailOpen;
        }

        public void setPreRankingFailOpen(boolean preRankingFailOpen) {
            this.preRankingFailOpen = preRankingFailOpen;
        }

        public int getPreRankingMaxConcurrency() {
            return preRankingMaxConcurrency;
        }

        public void setPreRankingMaxConcurrency(int preRankingMaxConcurrency) {
            this.preRankingMaxConcurrency = preRankingMaxConcurrency;
        }

        public int normalizedPreRankingMaxConcurrency() {
            return Math.max(1, preRankingMaxConcurrency);
        }

        public Path getPreRankingTempDir() {
            return pathOrNull(preRankingTempDir);
        }

        public void setPreRankingTempDir(String preRankingTempDir) {
            this.preRankingTempDir = preRankingTempDir;
        }

        public String getPreRankingProvider() {
            return preRankingProvider;
        }

        public void setPreRankingProvider(String preRankingProvider) {
            this.preRankingProvider = preRankingProvider;
        }

        public String normalizedPreRankingProvider() {
            return hasText(preRankingProvider) ? preRankingProvider.trim() : "vep";
        }

        public String getPreRankingAssemblyPolicy() {
            return preRankingAssemblyPolicy;
        }

        public void setPreRankingAssemblyPolicy(String preRankingAssemblyPolicy) {
            this.preRankingAssemblyPolicy = preRankingAssemblyPolicy;
        }

        public String normalizedPreRankingAssemblyPolicy() {
            return hasText(preRankingAssemblyPolicy) ? preRankingAssemblyPolicy.trim() : "strict";
        }

        public String preRankingOptionsHash() {
            return preRankingOptionsHash(null);
        }

        public String preRankingOptionsHash(ResourceProfile profile) {
            return preRankingOptionsHash(
                    profile == null ? null : profile.normalizedAssembly(),
                    profile == null ? version : profile.effectiveVersion(version),
                    profile == null ? ensemblCacheVersion : profile.effectiveEnsemblCacheVersion(ensemblCacheVersion),
                    profile == null ? maneVersion : profile.effectiveManeVersion(maneVersion),
                    profile == null ? lofteeVersion : profile.effectiveLofteeVersion(lofteeVersion)
            );
        }

        public String preRankingOptionsHash(String assembly,
                                            String providerVersion,
                                            String cacheVersion,
                                            String maneVersion,
                                            String lofteeVersion) {
            String raw = normalizedPreRankingProvider()
                    + "|assemblyPolicy=" + normalizedPreRankingAssemblyPolicy()
                    + "|loftee=" + lofteeEnabled
                    + "|assembly=" + nullToEmpty(assembly)
                    + "|providerVersion=" + nullToEmpty(providerVersion)
                    + "|cacheVersion=" + nullToEmpty(cacheVersion)
                    + "|mane=" + nullToEmpty(maneVersion)
                    + "|lofteeVersion=" + nullToEmpty(lofteeVersion)
                    + "|transcriptProjection=" + TRANSCRIPT_PROJECTION_VERSION;
            if (ownedExecutionEnabled) raw += "|runner=vep-windows-job-v3|outputLimit=" + ownedMaxOutputBytes;
            return sha256(raw);
        }

        public Map<String, ResourceProfile> getResources() {
            return resources;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Path getTempDir() {
            return pathOrNull(tempDir);
        }

        public void setTempDir(String tempDir) {
            this.tempDir = tempDir;
        }

        public boolean isCleanupTemp() {
            return cleanupTemp;
        }

        public void setCleanupTemp(boolean cleanupTemp) {
            this.cleanupTemp = cleanupTemp;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getEnsemblCacheVersion() {
            return ensemblCacheVersion;
        }

        public void setEnsemblCacheVersion(String ensemblCacheVersion) {
            this.ensemblCacheVersion = ensemblCacheVersion;
        }

        public String getManeVersion() {
            return maneVersion;
        }

        public void setManeVersion(String maneVersion) {
            this.maneVersion = maneVersion;
        }

        public String getLofteeVersion() {
            return lofteeVersion;
        }

        public void setLofteeVersion(String lofteeVersion) {
            this.lofteeVersion = lofteeVersion;
        }

        private boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }

        private String nullToEmpty(String value) {
            return value == null ? "" : value;
        }

        private String sha256(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder(hash.length * 2);
                for (byte current : hash) {
                    builder.append(String.format("%02x", current & 0xff));
                }
                return builder.toString();
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 digest is not available", ex);
            }
        }

        public static class ResourceProfile {

            private boolean enabled;
            private String assembly;
            private String executable;
            private String cacheDir;
            private String fasta;
            private String pluginsDir;
            private String species = "homo_sapiens";
            private String version;
            private String ensemblCacheVersion;
            private String maneVersion;
            private String lofteeVersion;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getAssembly() {
                return assembly;
            }

            public void setAssembly(String assembly) {
                this.assembly = assembly;
            }

            public String normalizedAssembly() {
                if (!hasText(assembly)) {
                    return null;
                }
                String normalized = assembly.trim().replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if ("grch38".equals(normalized) || "hg38".equals(normalized)) {
                    return "GRCh38";
                }
                if ("grch37".equals(normalized) || "hg19".equals(normalized) || "b37".equals(normalized)) {
                    return "GRCh37";
                }
                return assembly.trim();
            }

            public String getExecutable() {
                return executable;
            }

            public void setExecutable(String executable) {
                this.executable = executable;
            }

            public Path getCacheDir() {
                return pathOrNull(cacheDir);
            }

            public void setCacheDir(String cacheDir) {
                this.cacheDir = cacheDir;
            }

            public Path getFasta() {
                return pathOrNull(fasta);
            }

            public void setFasta(String fasta) {
                this.fasta = fasta;
            }

            public Path getPluginsDir() {
                return pathOrNull(pluginsDir);
            }

            public void setPluginsDir(String pluginsDir) {
                this.pluginsDir = pluginsDir;
            }

            public String getSpecies() {
                return species;
            }

            public void setSpecies(String species) {
                this.species = species;
            }

            public String effectiveSpecies() {
                return hasText(species) ? species.trim() : "homo_sapiens";
            }

            public String getVersion() {
                return version;
            }

            public void setVersion(String version) {
                this.version = version;
            }

            public String effectiveVersion(String fallback) {
                return hasText(version) ? version.trim() : fallback;
            }

            public String getEnsemblCacheVersion() {
                return ensemblCacheVersion;
            }

            public void setEnsemblCacheVersion(String ensemblCacheVersion) {
                this.ensemblCacheVersion = ensemblCacheVersion;
            }

            public String effectiveEnsemblCacheVersion(String fallback) {
                return hasText(ensemblCacheVersion) ? ensemblCacheVersion.trim() : fallback;
            }

            public String getManeVersion() {
                return maneVersion;
            }

            public void setManeVersion(String maneVersion) {
                this.maneVersion = maneVersion;
            }

            public String effectiveManeVersion(String fallback) {
                return hasText(maneVersion) ? maneVersion.trim() : fallback;
            }

            public String getLofteeVersion() {
                return lofteeVersion;
            }

            public void setLofteeVersion(String lofteeVersion) {
                this.lofteeVersion = lofteeVersion;
            }

            public String effectiveLofteeVersion(String fallback) {
                return hasText(lofteeVersion) ? lofteeVersion.trim() : fallback;
            }

            private boolean hasText(String value) {
                return value != null && !value.trim().isEmpty();
            }
        }
    }

    public static class AcmgReady {

        private boolean enabled = true;
        private String ruleVersion = "acmg-ready-v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRuleVersion() {
            return ruleVersion;
        }

        public void setRuleVersion(String ruleVersion) {
            this.ruleVersion = ruleVersion;
        }
    }

    /** Integrity-verified local HPO ontology; unavailable resources degrade with an explicit warning. */
    public static class HpoOntology {

        private String file;
        private String expectedDataVersion;
        private String expectedSha256;

        public Path getFile() {
            return pathOrNull(file);
        }

        public void setFile(String file) {
            this.file = file;
        }

        public String getExpectedDataVersion() {
            return expectedDataVersion;
        }

        public void setExpectedDataVersion(String expectedDataVersion) {
            this.expectedDataVersion = expectedDataVersion;
        }

        public String getExpectedSha256() {
            return expectedSha256;
        }

        public void setExpectedSha256(String expectedSha256) {
            this.expectedSha256 = expectedSha256;
        }
    }

    public static class PhenotypePrior {

        private boolean enabled;
        private String source = "LOCAL_FIXTURE";
        private String localFile;
        private String version = "mock-v1";
        private int maxGenes = 50;
        private boolean failOnMalformedRow;
        private boolean requireMatchingHpo = true;
        private String format = "TSV";
        private String manifestFile;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Path getLocalFile() {
            return pathOrNull(localFile);
        }

        public void setLocalFile(String localFile) {
            this.localFile = localFile;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public int getMaxGenes() {
            return maxGenes;
        }

        public void setMaxGenes(int maxGenes) {
            this.maxGenes = maxGenes;
        }

        public int normalizedMaxGenes() {
            return Math.max(0, maxGenes);
        }

        public boolean isFailOnMalformedRow() {
            return failOnMalformedRow;
        }

        public void setFailOnMalformedRow(boolean failOnMalformedRow) {
            this.failOnMalformedRow = failOnMalformedRow;
        }

        public boolean isRequireMatchingHpo() {
            return requireMatchingHpo;
        }

        public void setRequireMatchingHpo(boolean requireMatchingHpo) {
            this.requireMatchingHpo = requireMatchingHpo;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public Path getManifestFile() {
            return pathOrNull(manifestFile);
        }

        public void setManifestFile(String manifestFile) {
            this.manifestFile = manifestFile;
        }
    }

    public static class PhenotypeRanking {

        private boolean enabled;
        private double maxScoreBoost = 8.0d;
        private double strongThreshold = 0.8d;
        private double moderateThreshold = 0.5d;
        private double lowThreshold = 0.2d;
        private boolean confidenceAware = true;
        private double manualSourceConfidenceCap = 0.9d;
        private double aiSourceConfidenceCap = 0.75d;
        private double unknownSourceConfidenceCap = 0.8d;
        private double minimumMatchedTermCoverageFactor = 0.5d;
        private double minimumConfidenceForBoost = 0.5d;
        private boolean applyToFilteredCommon;
        private boolean applyToFilteredQc;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMaxScoreBoost() {
            return maxScoreBoost;
        }

        public void setMaxScoreBoost(double maxScoreBoost) {
            this.maxScoreBoost = maxScoreBoost;
        }

        public double normalizedMaxScoreBoost() {
            return Math.max(0.0d, maxScoreBoost);
        }

        public double getStrongThreshold() {
            return strongThreshold;
        }

        public void setStrongThreshold(double strongThreshold) {
            this.strongThreshold = strongThreshold;
        }

        public double getModerateThreshold() {
            return moderateThreshold;
        }

        public void setModerateThreshold(double moderateThreshold) {
            this.moderateThreshold = moderateThreshold;
        }

        public double getLowThreshold() {
            return lowThreshold;
        }

        public void setLowThreshold(double lowThreshold) {
            this.lowThreshold = lowThreshold;
        }

        public boolean isConfidenceAware() {
            return confidenceAware;
        }

        public void setConfidenceAware(boolean confidenceAware) {
            this.confidenceAware = confidenceAware;
        }

        public double getManualSourceConfidenceCap() {
            return manualSourceConfidenceCap;
        }

        public void setManualSourceConfidenceCap(double manualSourceConfidenceCap) {
            this.manualSourceConfidenceCap = manualSourceConfidenceCap;
        }

        public double getAiSourceConfidenceCap() {
            return aiSourceConfidenceCap;
        }

        public void setAiSourceConfidenceCap(double aiSourceConfidenceCap) {
            this.aiSourceConfidenceCap = aiSourceConfidenceCap;
        }

        public double getUnknownSourceConfidenceCap() {
            return unknownSourceConfidenceCap;
        }

        public void setUnknownSourceConfidenceCap(double unknownSourceConfidenceCap) {
            this.unknownSourceConfidenceCap = unknownSourceConfidenceCap;
        }

        public double getMinimumMatchedTermCoverageFactor() {
            return minimumMatchedTermCoverageFactor;
        }

        public void setMinimumMatchedTermCoverageFactor(double minimumMatchedTermCoverageFactor) {
            this.minimumMatchedTermCoverageFactor = minimumMatchedTermCoverageFactor;
        }

        public double getMinimumConfidenceForBoost() {
            return minimumConfidenceForBoost;
        }

        public void setMinimumConfidenceForBoost(double minimumConfidenceForBoost) {
            this.minimumConfidenceForBoost = minimumConfidenceForBoost;
        }

        public boolean isApplyToFilteredCommon() {
            return applyToFilteredCommon;
        }

        public void setApplyToFilteredCommon(boolean applyToFilteredCommon) {
            this.applyToFilteredCommon = applyToFilteredCommon;
        }

        public boolean isApplyToFilteredQc() {
            return applyToFilteredQc;
        }

        public void setApplyToFilteredQc(boolean applyToFilteredQc) {
            this.applyToFilteredQc = applyToFilteredQc;
        }
    }

    public static class Trio {

        private boolean enabled;
        private boolean pipelineEnabled;
        private boolean evidenceOnly = true;
        private boolean failOpen = true;
        private int minDepthForConfirmedRef = 10;
        private int minGqForConfirmedRef = 20;
        private int maxVariantsForTrioEvidence = 5000;
        private String inputMode = "DISABLED";
        private boolean rankingEnabled;
        private double rankingMaxScoreContribution = 0.0d;
        private boolean rankingAllowNegativePenalty;
        private boolean rankingRequireSupportedConfidence = true;
        private boolean rankingAllowPossibleBoost;
        private boolean rankingEnableSharedParentPenalty;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isPipelineEnabled() {
            return pipelineEnabled;
        }

        public void setPipelineEnabled(boolean pipelineEnabled) {
            this.pipelineEnabled = pipelineEnabled;
        }

        public boolean isEvidenceOnly() {
            return evidenceOnly;
        }

        public void setEvidenceOnly(boolean evidenceOnly) {
            this.evidenceOnly = evidenceOnly;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public int getMinDepthForConfirmedRef() {
            return minDepthForConfirmedRef;
        }

        public void setMinDepthForConfirmedRef(int minDepthForConfirmedRef) {
            this.minDepthForConfirmedRef = minDepthForConfirmedRef;
        }

        public int normalizedMinDepthForConfirmedRef() {
            return minDepthForConfirmedRef > 0
                    ? minDepthForConfirmedRef
                    : org.nbscn.varvis.variantanalysis.trio.TrioVcfParseRequest
                    .DEFAULT_MIN_DEPTH_FOR_CONFIRMED_REF;
        }

        public int getMinGqForConfirmedRef() {
            return minGqForConfirmedRef;
        }

        public void setMinGqForConfirmedRef(int minGqForConfirmedRef) {
            this.minGqForConfirmedRef = minGqForConfirmedRef;
        }

        public int normalizedMinGqForConfirmedRef() {
            return minGqForConfirmedRef > 0
                    ? minGqForConfirmedRef
                    : org.nbscn.varvis.variantanalysis.trio.TrioVcfParseRequest
                    .DEFAULT_MIN_GQ_FOR_CONFIRMED_REF;
        }

        public int getMaxVariantsForTrioEvidence() {
            return maxVariantsForTrioEvidence;
        }

        public void setMaxVariantsForTrioEvidence(int maxVariantsForTrioEvidence) {
            this.maxVariantsForTrioEvidence = maxVariantsForTrioEvidence;
        }

        public int normalizedMaxVariantsForTrioEvidence() {
            return Math.max(0, maxVariantsForTrioEvidence);
        }

        public String getInputMode() {
            return inputMode;
        }

        public void setInputMode(String inputMode) {
            this.inputMode = inputMode;
        }

        public String normalizedInputMode() {
            return inputMode == null || inputMode.trim().isEmpty()
                    ? "DISABLED"
                    : inputMode.trim();
        }

        public boolean isRankingEnabled() {
            return rankingEnabled;
        }

        public void setRankingEnabled(boolean rankingEnabled) {
            this.rankingEnabled = rankingEnabled;
        }

        public double getRankingMaxScoreContribution() {
            return rankingMaxScoreContribution;
        }

        public void setRankingMaxScoreContribution(double rankingMaxScoreContribution) {
            this.rankingMaxScoreContribution = rankingMaxScoreContribution;
        }

        public double normalizedRankingMaxScoreContribution() {
            return Double.isFinite(rankingMaxScoreContribution)
                    ? Math.max(0.0d, rankingMaxScoreContribution)
                    : 0.0d;
        }

        public boolean isRankingAllowNegativePenalty() {
            return rankingAllowNegativePenalty;
        }

        public void setRankingAllowNegativePenalty(boolean rankingAllowNegativePenalty) {
            this.rankingAllowNegativePenalty = rankingAllowNegativePenalty;
        }

        public boolean isRankingRequireSupportedConfidence() {
            return rankingRequireSupportedConfidence;
        }

        public void setRankingRequireSupportedConfidence(boolean rankingRequireSupportedConfidence) {
            this.rankingRequireSupportedConfidence = rankingRequireSupportedConfidence;
        }

        public boolean isRankingAllowPossibleBoost() {
            return rankingAllowPossibleBoost;
        }

        public void setRankingAllowPossibleBoost(boolean rankingAllowPossibleBoost) {
            this.rankingAllowPossibleBoost = rankingAllowPossibleBoost;
        }

        public boolean isRankingEnableSharedParentPenalty() {
            return rankingEnableSharedParentPenalty;
        }

        public void setRankingEnableSharedParentPenalty(boolean rankingEnableSharedParentPenalty) {
            this.rankingEnableSharedParentPenalty = rankingEnableSharedParentPenalty;
        }
    }

    private static Path pathOrNull(String value) {
        return value == null || value.trim().isEmpty() ? null : Paths.get(value.trim());
    }

    private static String normalizePolicy(String value) {
        return value == null
                ? ""
                : value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static List<Path> pathsOrEmpty(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> result = new ArrayList<Path>();
        for (String token : value.split("[,;]")) {
            Path path = pathOrNull(token);
            if (path != null) {
                result.add(path);
            }
        }
        return result;
    }

    public enum Mode {
        COPY_ONLY,
        BCFTOOLS
    }
}
