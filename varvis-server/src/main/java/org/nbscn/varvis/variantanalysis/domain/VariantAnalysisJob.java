package org.nbscn.varvis.variantanalysis.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VariantAnalysisJob {

    private final String id;
    private final String originalFileName;
    private final String fileSha256;
    private final GenomeAssembly genomeAssembly;
    private final AnalysisMode analysisMode;
    private final String requestedSampleId;
    private final Path workspace;
    private final Path uploadedFile;
    private final Instant createdAt;

    private volatile VariantAnalysisStatus status;
    private volatile Instant updatedAt;
    private volatile String selectedSampleId;
    private volatile boolean normalizationApplied;
    private volatile String normalizationEngine;
    private volatile VariantQcSummary qcSummary;
    private volatile VcfPreflightSummary preflightSummary;
    private volatile List<VariantQcObservation> observations = Collections.emptyList();
    private volatile List<String> warnings = Collections.emptyList();
    private volatile PhenotypeAwareSummary phenotypeSummary;
    private volatile String errorMessage;
    // Application-layer provenance; intentionally not persisted in the legacy SQL schema.
    private volatile EngineMetadata engineMetadata = EngineMetadata.varvis(EngineExecutionStatus.NOT_REQUESTED);

    public VariantAnalysisJob(String id,
                              String originalFileName,
                              String fileSha256,
                              GenomeAssembly genomeAssembly,
                              AnalysisMode analysisMode,
                              String requestedSampleId,
                              Path workspace,
                              Path uploadedFile) {
        this(
                id,
                originalFileName,
                fileSha256,
                genomeAssembly,
                analysisMode,
                requestedSampleId,
                workspace,
                uploadedFile,
                PhenotypeAwareSummary.disabled()
        );
    }

    public VariantAnalysisJob(String id,
                              String originalFileName,
                              String fileSha256,
                              GenomeAssembly genomeAssembly,
                              AnalysisMode analysisMode,
                              String requestedSampleId,
                              Path workspace,
                              Path uploadedFile,
                              PhenotypeAwareSummary phenotypeSummary) {
        this(
                id,
                originalFileName,
                fileSha256,
                genomeAssembly,
                analysisMode,
                requestedSampleId,
                workspace,
                uploadedFile,
                Instant.now(),
                null,
                VariantAnalysisStatus.UPLOADED,
                null,
                false,
                null,
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                phenotypeSummary,
                null
        );
    }

    private VariantAnalysisJob(String id,
                               String originalFileName,
                               String fileSha256,
                               GenomeAssembly genomeAssembly,
                               AnalysisMode analysisMode,
                               String requestedSampleId,
                               Path workspace,
                               Path uploadedFile,
                               Instant createdAt,
                               Instant updatedAt,
                               VariantAnalysisStatus status,
                               String selectedSampleId,
                               boolean normalizationApplied,
                               String normalizationEngine,
                               VariantQcSummary qcSummary,
                               VcfPreflightSummary preflightSummary,
                               List<VariantQcObservation> observations,
                               List<String> warnings,
                               PhenotypeAwareSummary phenotypeSummary,
                               String errorMessage) {
        this.id = id;
        this.originalFileName = originalFileName;
        this.fileSha256 = fileSha256;
        this.genomeAssembly = genomeAssembly;
        this.analysisMode = analysisMode;
        this.requestedSampleId = requestedSampleId;
        this.workspace = workspace;
        this.uploadedFile = uploadedFile;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt == null ? createdAt : updatedAt;
        this.status = status;
        this.selectedSampleId = selectedSampleId;
        this.normalizationApplied = normalizationApplied;
        this.normalizationEngine = normalizationEngine;
        this.qcSummary = qcSummary;
        this.preflightSummary = preflightSummary;
        this.observations = immutableCopy(observations);
        this.warnings = immutableCopy(warnings);
        this.phenotypeSummary = phenotypeSummary == null
                ? PhenotypeAwareSummary.disabled()
                : phenotypeSummary;
        this.errorMessage = errorMessage;
    }

    public static VariantAnalysisJob restore(String id,
                                             String originalFileName,
                                             String fileSha256,
                                             GenomeAssembly genomeAssembly,
                                             AnalysisMode analysisMode,
                                             String requestedSampleId,
                                             Path workspace,
                                             Path uploadedFile,
                                             Instant createdAt,
                                             Instant updatedAt,
                                             VariantAnalysisStatus status,
                                             String selectedSampleId,
                                             boolean normalizationApplied,
                                             String normalizationEngine,
                                             VariantQcSummary qcSummary,
                                             List<String> warnings,
                                             String errorMessage) {
        return restore(
                id,
                originalFileName,
                fileSha256,
                genomeAssembly,
                analysisMode,
                requestedSampleId,
                workspace,
                uploadedFile,
                createdAt,
                updatedAt,
                status,
                selectedSampleId,
                normalizationApplied,
                normalizationEngine,
                qcSummary,
                warnings,
                errorMessage,
                PhenotypeAwareSummary.disabled()
        );
    }

    public static VariantAnalysisJob restore(String id,
                                             String originalFileName,
                                             String fileSha256,
                                             GenomeAssembly genomeAssembly,
                                             AnalysisMode analysisMode,
                                             String requestedSampleId,
                                             Path workspace,
                                             Path uploadedFile,
                                             Instant createdAt,
                                             Instant updatedAt,
                                             VariantAnalysisStatus status,
                                             String selectedSampleId,
                                             boolean normalizationApplied,
                                             String normalizationEngine,
                                             VariantQcSummary qcSummary,
                                             List<String> warnings,
                                             String errorMessage,
                                             PhenotypeAwareSummary phenotypeSummary) {
        return new VariantAnalysisJob(
                id,
                originalFileName,
                fileSha256,
                genomeAssembly,
                analysisMode,
                requestedSampleId,
                workspace,
                uploadedFile,
                createdAt,
                updatedAt,
                status,
                selectedSampleId,
                normalizationApplied,
                normalizationEngine,
                qcSummary,
                null,
                Collections.emptyList(),
                warnings,
                phenotypeSummary,
                errorMessage
        );
    }

    public synchronized void transitionTo(VariantAnalysisStatus nextStatus) {
        this.status = nextStatus;
        this.updatedAt = Instant.now();
    }

    public synchronized void complete(String selectedSampleId,
                                      boolean normalizationApplied,
                                      String normalizationEngine,
                                      VariantQcSummary qcSummary,
                                      List<VariantQcObservation> observations,
                                      List<String> warnings) {
        this.selectedSampleId = selectedSampleId;
        this.normalizationApplied = normalizationApplied;
        this.normalizationEngine = normalizationEngine;
        this.qcSummary = qcSummary;
        this.observations = immutableCopy(observations);
        this.warnings = immutableCopy(warnings);
        this.errorMessage = null;
        transitionTo(VariantAnalysisStatus.READY);
    }

    /**
     * Closes a job only after a restart-time persistence checkpoint has proven
     * that its already-produced engine results are completely available.
     */
    public synchronized void recoverCompletedPersistence() {
        recoverCompletedPersistence(normalizationApplied, normalizationEngine);
    }

    /**
     * Finishes a proven post-engine checkpoint without discarding the compact
     * liftover audit needed by the persisted job schema.
     */
    public synchronized void recoverCompletedPersistence(boolean recoveredNormalizationApplied,
                                                          String recoveredNormalizationEngine) {
        this.normalizationApplied = recoveredNormalizationApplied;
        this.normalizationEngine = recoveredNormalizationEngine;
        this.errorMessage = null;
        transitionTo(VariantAnalysisStatus.READY);
    }

    public synchronized void applyPreflightSummary(VcfPreflightSummary preflightSummary) {
        this.preflightSummary = preflightSummary;
    }

    public synchronized void fail(String errorMessage) {
        this.errorMessage = errorMessage;
        transitionTo(VariantAnalysisStatus.FAILED);
    }

    public synchronized void applyPhenotypeSummary(PhenotypeAwareSummary phenotypeSummary) {
        this.phenotypeSummary = phenotypeSummary == null
                ? PhenotypeAwareSummary.disabled()
                : phenotypeSummary;
    }

    public synchronized void applyEngineMetadata(EngineMetadata engineMetadata) {
        this.engineMetadata = engineMetadata == null
                ? EngineMetadata.varvis(EngineExecutionStatus.NOT_REQUESTED)
                : engineMetadata;
        this.updatedAt = Instant.now();
    }

    /**
     * Restores engine provenance that was persisted separately from the job row.
     * Rehydration is a read operation and must preserve the job row's persisted
     * {@code updatedAt}; callers performing a business update must continue to use
     * {@link #applyEngineMetadata(EngineMetadata)}.
     */
    public synchronized void restoreEngineMetadata(EngineMetadata engineMetadata) {
        this.engineMetadata = engineMetadata == null
                ? EngineMetadata.varvis(EngineExecutionStatus.NOT_REQUESTED)
                : engineMetadata;
    }

    private <T> List<T> immutableCopy(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public String getId() {
        return id;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public GenomeAssembly getGenomeAssembly() {
        return genomeAssembly;
    }

    public AnalysisMode getAnalysisMode() {
        return analysisMode;
    }

    public String getRequestedSampleId() {
        return requestedSampleId;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public Path getUploadedFile() {
        return uploadedFile;
    }

    public String getStoredFileName() {
        return uploadedFile.getFileName().toString();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public VariantAnalysisStatus getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
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

    public VariantQcSummary getQcSummary() {
        return qcSummary;
    }

    public VcfPreflightSummary getPreflightSummary() {
        return preflightSummary;
    }

    public List<VariantQcObservation> getObservations() {
        return observations;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public PhenotypeAwareSummary getPhenotypeSummary() {
        return phenotypeSummary == null ? PhenotypeAwareSummary.disabled() : phenotypeSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public EngineMetadata getEngineMetadata() {
        return engineMetadata;
    }
}
