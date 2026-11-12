package org.nbscn.varvis.variantanalysis.dto;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantQcSummary;
import org.nbscn.varvis.variantanalysis.domain.VcfPreflightSummary;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class VariantAnalysisJobResponse {

    private String id;
    private VariantAnalysisStatus status;
    private String originalFileName;
    private String fileSha256;
    private String genomeAssembly;
    private String analysisAssembly;
    private boolean liftoverApplied;
    private String liftoverStatus;
    private int liftoverRejectedRecordCount;
    private long liftoverSourceRecordCount;
    private long liftoverLiftedRecordCount;
    private String analysisMode;
    private java.util.Map<String,String> analysisScopePolicy;
    private String selectedSampleId;
    private boolean normalizationApplied;
    private String normalizationEngine;
    private String engine;
    private String engineVersion;
    private String rankingMode;
    private String engineStatus;
    private String engineUnavailableReason;
    private String engineResourceFingerprint;
    private String engineOutputSha256;
    private boolean engineFallbackUsed;
    private String taskStatus;
    private String errorCode;
    private VariantQcSummary qcSummary;
    private VcfPreflightSummary preflightSummary;
    private PhenotypeAwareSummary phenotypeSummary;
    private VariantInterpretationCounts interpretationCounts;
    private Map<String, Object> largeFileSummary;
    private String analysisStage;
    private String enrichmentStatus;
    private Instant readyFastAt;
    private Instant readyFullAt;
    private Map<String, Object> providerProgress;
    private String partialEvidenceWarning;
    private List<String> warnings;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static VariantAnalysisJobResponse from(VariantAnalysisJob job) {
        return from(job, null);
    }

    public static VariantAnalysisJobResponse from(VariantAnalysisJob job, VariantInterpretationCounts counts) {
        VariantAnalysisJobResponse response = new VariantAnalysisJobResponse();
        response.id = job.getId();
        response.status = job.getStatus();
        response.originalFileName = job.getOriginalFileName();
        response.fileSha256 = job.getFileSha256();
        response.genomeAssembly = job.getGenomeAssembly().name();
        response.analysisMode = job.getAnalysisMode().name();
        response.analysisScopePolicy = org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy.publicDetails(job);
        response.selectedSampleId = job.getSelectedSampleId();
        response.normalizationApplied = job.isNormalizationApplied();
        response.normalizationEngine = job.getNormalizationEngine();
        EngineMetadata engineMetadata = job.getEngineMetadata();
        if (engineMetadata == null) {
            // Backward-compatible fallback for legacy repository rows and test doubles.
            engineMetadata = EngineMetadata.varvis(EngineExecutionStatus.NOT_REQUESTED);
        } else if (engineMetadata.getEngine() == org.nbscn.varvis.variantanalysis.domain.AnalysisEngine.VARVIS
                && engineMetadata.getStatus() == EngineExecutionStatus.NOT_REQUESTED
                && job.getStatus() == VariantAnalysisStatus.READY) {
            // JDBC restore predates engine metadata; derive only the current Varvis terminal state.
            engineMetadata = EngineMetadata.varvisRanked();
        }
        response.engine = engineMetadata.getEngine().name();
        response.analysisAssembly = engineMetadata.getEngine()
                == org.nbscn.varvis.variantanalysis.domain.AnalysisEngine.EXOMISER
                ? "GRCh38" : response.genomeAssembly;
        response.liftoverApplied = job.isNormalizationApplied()
                && job.getNormalizationEngine() != null
                && job.getNormalizationEngine().toUpperCase(java.util.Locale.ROOT).contains("LIFTOVER");
        response.liftoverStatus = liftoverStatus(job.getNormalizationEngine(), response.liftoverApplied);
        response.liftoverRejectedRecordCount = liftoverRejectedRecordCount(job.getNormalizationEngine());
        response.liftoverSourceRecordCount = liftoverRecordCount(job.getNormalizationEngine(), "SOURCE");
        response.liftoverLiftedRecordCount = liftoverRecordCount(job.getNormalizationEngine(), "LIFTED");
        response.engineVersion = engineMetadata.getEngineVersion();
        response.rankingMode = engineMetadata.getRankingMode();
        response.engineStatus = engineMetadata.getStatus().name();
        response.engineUnavailableReason = engineMetadata.getUnavailableReason();
        response.engineResourceFingerprint = engineMetadata.getResourceFingerprint();
        response.engineOutputSha256 = engineMetadata.getOutputSha256();
        response.engineFallbackUsed = engineMetadata.isFallbackUsed();
        response.errorCode = errorCode(job, engineMetadata);
        response.taskStatus = taskStatus(job, engineMetadata, response.errorCode);
        response.qcSummary = job.getQcSummary();
        response.preflightSummary = job.getPreflightSummary();
        response.phenotypeSummary = job.getPhenotypeSummary();
        response.interpretationCounts = counts;
        response.largeFileSummary = counts == null ? null : counts.toLargeFileSummary();
        VariantAnalysisProgressiveStatus progressive =
                VariantAnalysisProgressiveStatus.from(job, counts);
        response.analysisStage = progressive.getAnalysisStage();
        response.enrichmentStatus = progressive.getEnrichmentStatus();
        response.readyFastAt = progressive.getReadyFastAt();
        response.readyFullAt = progressive.getReadyFullAt();
        response.providerProgress = progressive.getProviderProgress();
        response.partialEvidenceWarning = progressive.getPartialEvidenceWarning();
        response.warnings = job.getWarnings();
        response.errorMessage = job.getErrorMessage();
        response.createdAt = job.getCreatedAt();
        response.updatedAt = job.getUpdatedAt();
        return response;
    }

    private static String taskStatus(VariantAnalysisJob job,
                                     EngineMetadata metadata,
                                     String errorCode) {
        if (job.getStatus() == VariantAnalysisStatus.READY) {
            return "SUCCEEDED";
        }
        if (job.getStatus() == VariantAnalysisStatus.FAILED) {
            return errorCode == null ? "FAILED" : errorCode;
        }
        if (job.getStatus() == VariantAnalysisStatus.UPLOADED) {
            return "QUEUED";
        }
        return "RUNNING";
    }

    private static String errorCode(VariantAnalysisJob job, EngineMetadata metadata) {
        if (job.getStatus() != VariantAnalysisStatus.FAILED) {
            return null;
        }
        if (metadata.getStatus() == EngineExecutionStatus.UNAVAILABLE) {
            return "ENGINE_UNAVAILABLE";
        }
        String reason = metadata.getUnavailableReason();
        if (reason == null || reason.trim().isEmpty()) {
            reason = job.getErrorMessage();
        }
        String normalized = reason == null ? "" : reason.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("OUTPUT_PARSE") || normalized.contains("OUTPUT_VARIANTS")
                || normalized.contains("TSV_")) {
            return "RESULT_PARSE_FAILED";
        }
        if (normalized.contains("INPUT") || normalized.contains("SAMPLE")
                || normalized.contains("GRCH38_REQUIRED")) {
            return "INVALID_INPUT";
        }
        if (normalized.startsWith("EXOMISER_")) {
            return "EXECUTION_FAILED";
        }
        return "FAILED";
    }

    private static String liftoverStatus(String normalizationEngine, boolean applied) {
        if (!applied) {
            return "NOT_APPLICABLE";
        }
        return normalizationEngine != null && normalizationEngine.contains("PARTIAL_LIFTOVER:")
                ? "PARTIAL_LIFTOVER" : "COMPLETE";
    }

    private static int liftoverRejectedRecordCount(String normalizationEngine) {
        if (normalizationEngine == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("PARTIAL_LIFTOVER:(\\d+)")
                .matcher(normalizationEngine);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static long liftoverRecordCount(String normalizationEngine, String name) {
        if (normalizationEngine == null || name == null) {
            return 0L;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(";" + name + "=(\\d+)")
                .matcher(normalizationEngine);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    public String getId() {
        return id;
    }

    public VariantAnalysisStatus getStatus() {
        return status;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public String getGenomeAssembly() {
        return genomeAssembly;
    }

    public String getAnalysisAssembly() {
        return analysisAssembly;
    }

    public boolean isLiftoverApplied() {
        return liftoverApplied;
    }

    public String getLiftoverStatus() {
        return liftoverStatus;
    }

    public int getLiftoverRejectedRecordCount() {
        return liftoverRejectedRecordCount;
    }

    public long getLiftoverSourceRecordCount() {
        return liftoverSourceRecordCount;
    }

    public long getLiftoverLiftedRecordCount() {
        return liftoverLiftedRecordCount;
    }

    public java.util.Map<String,String> getAnalysisScopePolicy() { return analysisScopePolicy; }

    public String getAnalysisMode() {
        return analysisMode;
    }

    public String getSelectedSampleId() {
        return selectedSampleId;
    }

    public boolean isNormalizationApplied() {
        return normalizationApplied;
    }

    public String getNormalizationEngine() {
        return normalizationEngine;
    }

    public String getEngine() {
        return engine;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public String getRankingMode() {
        return rankingMode;
    }

    public String getEngineStatus() {
        return engineStatus;
    }

    public String getEngineUnavailableReason() {
        return engineUnavailableReason;
    }

    public String getEngineResourceFingerprint() {
        return engineResourceFingerprint;
    }

    public String getEngineOutputSha256() {
        return engineOutputSha256;
    }

    public boolean isEngineFallbackUsed() {
        return engineFallbackUsed;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public VariantQcSummary getQcSummary() {
        return qcSummary;
    }

    public VcfPreflightSummary getPreflightSummary() {
        return preflightSummary;
    }

    public PhenotypeAwareSummary getPhenotypeSummary() {
        return phenotypeSummary;
    }

    public VariantInterpretationCounts getInterpretationCounts() {
        return interpretationCounts;
    }

    public Map<String, Object> getLargeFileSummary() {
        return largeFileSummary;
    }

    public String getAnalysisStage() {
        return analysisStage;
    }

    public String getEnrichmentStatus() {
        return enrichmentStatus;
    }

    public Instant getReadyFastAt() {
        return readyFastAt;
    }

    public Instant getReadyFullAt() {
        return readyFullAt;
    }

    public Map<String, Object> getProviderProgress() {
        return providerProgress;
    }

    public String getPartialEvidenceWarning() {
        return partialEvidenceWarning;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
