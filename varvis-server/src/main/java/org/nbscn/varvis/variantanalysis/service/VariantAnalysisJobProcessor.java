package org.nbscn.varvis.variantanalysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.nbscn.varvis.utils.VcfUploadUtils;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizationRunManifest;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VcfQcResult;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VcfPreflightSummary;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.engine.ExomiserEngineAdapter;
import org.nbscn.varvis.variantanalysis.ranking.VariantRankingService;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineEvidenceService;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInputProvider;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class VariantAnalysisJobProcessor {
    private org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources acmgExecutionSources;

    @org.springframework.beans.factory.annotation.Value("${variant-analysis.acmg-business.enabled:false}")
    private boolean acmgBusinessEnabled;

    @Autowired(required = false)
    public void setAcmgExecutionSources(org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources sources) {
        this.acmgExecutionSources = sources;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantAnalysisJobProcessor.class);

    private final VariantAnalysisJobRepository repository;
    private final VcfBuildConverter buildConverter;
    private final VcfNormalizer normalizer;
    private final VcfQcService qcService;
    private final VariantInterpretationService interpretationService;
    private final VariantAnalysisProperties properties;
    private final VepTranscriptAnnotationEnrichmentService transcriptAnnotationEnrichmentService;
    private final PhenotypeAwareRerankingService phenotypeAwareRerankingService;
    private final PreRankingTranscriptAnnotationService preRankingTranscriptAnnotationService;
    private final VariantRankingService rankingService;
    private final TrioPipelineEvidenceService trioPipelineEvidenceService;
    private final TrioPipelineInputProvider trioPipelineInputProvider;
    private volatile ExomiserEngineAdapter exomiserEngineAdapter;

    @Autowired
    public VariantAnalysisJobProcessor(VariantAnalysisJobRepository repository,
                                       VcfBuildConverter buildConverter,
                                       VcfNormalizer normalizer,
                                       VcfQcService qcService,
                                       VariantInterpretationService interpretationService,
                                       VariantAnalysisProperties properties,
                                       VepTranscriptAnnotationEnrichmentService transcriptAnnotationEnrichmentService,
                                       PhenotypeAwareRerankingService phenotypeAwareRerankingService,
                                       PreRankingTranscriptAnnotationService preRankingTranscriptAnnotationService,
                                       VariantRankingService rankingService,
                                       TrioPipelineEvidenceService trioPipelineEvidenceService,
                                       TrioPipelineInputProvider trioPipelineInputProvider) {
        this.repository = repository;
        this.buildConverter = buildConverter;
        this.normalizer = normalizer;
        this.qcService = qcService;
        this.interpretationService = interpretationService;
        this.properties = properties;
        this.transcriptAnnotationEnrichmentService = transcriptAnnotationEnrichmentService;
        this.phenotypeAwareRerankingService = phenotypeAwareRerankingService == null
                ? new PhenotypeAwareRerankingService(properties)
                : phenotypeAwareRerankingService;
        this.preRankingTranscriptAnnotationService = preRankingTranscriptAnnotationService;
        this.rankingService = rankingService;
        this.trioPipelineEvidenceService = trioPipelineEvidenceService;
        this.trioPipelineInputProvider = trioPipelineInputProvider;
    }

    @Autowired(required = false)
    public void setExomiserEngineAdapter(ExomiserEngineAdapter exomiserEngineAdapter) {
        this.exomiserEngineAdapter = exomiserEngineAdapter;
    }

    public VariantAnalysisJobProcessor(VariantAnalysisJobRepository repository,
                                       VcfBuildConverter buildConverter,
                                       VcfNormalizer normalizer,
                                       VcfQcService qcService,
                                       VariantInterpretationService interpretationService,
                                       VariantAnalysisProperties properties,
                                       VepTranscriptAnnotationEnrichmentService transcriptAnnotationEnrichmentService,
                                       PhenotypeAwareRerankingService phenotypeAwareRerankingService,
                                       PreRankingTranscriptAnnotationService preRankingTranscriptAnnotationService,
                                       VariantRankingService rankingService) {
        this(
                repository,
                buildConverter,
                normalizer,
                qcService,
                interpretationService,
                properties,
                transcriptAnnotationEnrichmentService,
                phenotypeAwareRerankingService,
                preRankingTranscriptAnnotationService,
                rankingService,
                null,
                null
        );
    }

    public VariantAnalysisJobProcessor(VariantAnalysisJobRepository repository,
                                       VcfBuildConverter buildConverter,
                                       VcfNormalizer normalizer,
                                       VcfQcService qcService,
                                       VariantInterpretationService interpretationService,
                                       VariantAnalysisProperties properties,
                                       VepTranscriptAnnotationEnrichmentService transcriptAnnotationEnrichmentService,
                                       PhenotypeAwareRerankingService phenotypeAwareRerankingService) {
        this(
                repository,
                buildConverter,
                normalizer,
                qcService,
                interpretationService,
                properties,
                transcriptAnnotationEnrichmentService,
                phenotypeAwareRerankingService,
                null,
                null
        );
    }

    public VariantAnalysisJobProcessor(VariantAnalysisJobRepository repository,
                                       VcfBuildConverter buildConverter,
                                       VcfNormalizer normalizer,
                                       VcfQcService qcService,
                                       VariantInterpretationService interpretationService,
                                       VariantAnalysisProperties properties,
                                       VepTranscriptAnnotationEnrichmentService transcriptAnnotationEnrichmentService) {
        this(
                repository,
                buildConverter,
                normalizer,
                qcService,
                interpretationService,
                properties,
                transcriptAnnotationEnrichmentService,
                new PhenotypeAwareRerankingService(properties),
                null,
                null
        );
    }

    public VariantAnalysisJobProcessor(VariantAnalysisJobRepository repository,
                                       VcfBuildConverter buildConverter,
                                       VcfNormalizer normalizer,
                                       VcfQcService qcService,
                                       VariantInterpretationService interpretationService,
                                       VariantAnalysisProperties properties) {
        this(
                repository,
                buildConverter,
                normalizer,
                qcService,
                interpretationService,
                properties,
                null,
                new PhenotypeAwareRerankingService(properties),
                null,
                null
        );
    }

    @Async("variantAnalysisExecutor")
    public void process(String jobId) {
        processInternal(jobId, null, BenchmarkTelemetryCollector.disabled());
    }

    @Async("variantAnalysisExecutor")
    public void process(String jobId, PhenotypeAwareSummary phenotypeSummary) {
        processInternal(jobId, phenotypeSummary, BenchmarkTelemetryCollector.disabled());
    }

    @Async("variantAnalysisExecutor")
    public void process(String jobId,
                        PhenotypeAwareSummary phenotypeSummary,
                        BenchmarkTelemetryCollector benchmarkTelemetry) {
        processInternal(jobId, phenotypeSummary, benchmarkTelemetry);
    }

    private void processInternal(String jobId,
                                 PhenotypeAwareSummary phenotypeSummary,
                                 BenchmarkTelemetryCollector benchmarkTelemetry) {
        benchmarkTelemetry = benchmarkTelemetry == null
                ? BenchmarkTelemetryCollector.disabled() : benchmarkTelemetry;
        VariantAnalysisJob job = repository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        if (properties.getDefaultEngine() == AnalysisEngine.EXOMISER) {
            processExomiser(job, phenotypeSummary, benchmarkTelemetry);
            return;
        }
        job.applyEngineMetadata(EngineMetadata.varvisRunning());
        repository.save(job);
        if (phenotypeSummary != null) {
            job.applyPhenotypeSummary(phenotypeSummary);
            // Persist the normalized HPO/prior summary before long-running analysis stages.
            repository.save(job);
        }
        long jobStarted = System.nanoTime();
        long stageStarted = jobStarted;
        try {
            transitionTo(job, VariantAnalysisStatus.VALIDATING, "Validating uploaded VCF");
            VcfUploadUtils.validateStoredFile(
                    job.getUploadedFile(),
                    properties.getMaxUploadSizeBytes(),
                    properties.getMaxExpandedSizeBytes()
            );
            stageStarted = logStage(jobId, "validate upload", stageStarted);

            GenomeAssembly inputAssembly = job.getGenomeAssembly() == null
                    ? GenomeAssembly.GRCh38
                    : job.getGenomeAssembly();
            GenomeAssembly analysisAssembly = properties.resolveAnalysisAssembly(inputAssembly);
            transitionTo(job, VariantAnalysisStatus.NORMALIZING,
                    "Preparing VCF for " + analysisAssembly.name() + " analysis");
            BuildConversionResult conversion = buildConverter.convert(
                    job.getUploadedFile(),
                    job.getWorkspace(),
                    inputAssembly,
                    analysisAssembly
            );
            stageStarted = logStage(jobId, "prepare analysis build", stageStarted);

            transitionTo(job, VariantAnalysisStatus.NORMALIZING,
                    "Normalizing " + analysisAssembly.name() + " analysis VCF");
            NormalizationResult normalization = normalizer.normalize(
                    conversion.getOutputFile(),
                    job.getWorkspace(),
                    analysisAssembly
            );
            stageStarted = logStage(jobId, "normalize VCF", stageStarted);

            transitionTo(job, VariantAnalysisStatus.QC_EXTRACTING, "Extracting QC observations");
            VcfQcResult qcResult = qcService.extract(
                    normalization.getOutputFile(),
                    analysisAssembly,
                    job.getRequestedSampleId()
            );
            normalization = completeNormalizationManifest(job, normalization, qcResult);
            benchmarkTelemetry.observeNormalization(normalization, qcResult);
            VcfPreflightSummary preflightSummary = VcfPreflightSummary.fromQcResult(
                    qcResult.getSummary(),
                    qcResult.getObservations(),
                    qcResult.getSelectedSampleId(),
                    properties.getLargeFile()
            );
            job.applyPreflightSummary(preflightSummary);
            stageStarted = logStage(jobId, "extract QC observations", stageStarted);

            transitionTo(job, VariantAnalysisStatus.BASE_ANNOTATING, "Annotating normalized variants");
            AnalysisContext analysisContext =
                    new AnalysisContext(
                            jobId,
                            analysisAssembly,
                            job.getAnalysisMode(),
                            preflightSummary,
                            job.getPhenotypeSummary(),
                            benchmarkTelemetry
                    );
            List<VariantInterpretation> interpretations = interpretationService.interpret(
                    normalization.getOutputFile(),
                    qcResult.getObservations(),
                    analysisContext
            );
            stageStarted = logStage(jobId, "interpret variants", stageStarted);
            job.applyPhenotypeSummary(job.getPhenotypeSummary().withPhenotypeRescueSummary(
                    analysisContext.getPhenotypeRescueSummary()));
            PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary preRankingAnnotationSummary =
                    runPreRankingTranscriptAnnotation(jobId, interpretations, analysisContext);
            benchmarkTelemetry.observeVepSummary(preRankingAnnotationSummary);
            stageStarted = logStage(jobId, "pre-ranking transcript annotation", stageStarted);
            if (preRankingAnnotationSummary.isRankingRefreshRecommended() && rankingService != null) {
                rankingService.rank(interpretations);
                stageStarted = logStage(jobId, "refresh ranking after pre-ranking transcript annotation", stageStarted);
            }
            job.applyPreflightSummary(preflightWithFeatureFlags(preflightSummary, interpretations));
            PhenotypeAwareSummary rerankedSummary = phenotypeAwareRerankingService.apply(
                    interpretations,
                    job.getPhenotypeSummary()
            );
            job.applyPhenotypeSummary(rerankedSummary.withPhenotypeRescueSummary(
                    analysisContext.getPhenotypeRescueSummary()));
            stageStarted = logStage(jobId, "phenotype-aware reranking", stageStarted);
            runTrioEvidenceStage(job, interpretations, analysisAssembly);
            stageStarted = logStage(jobId, "trio segregation evidence", stageStarted);

            transitionTo(job, VariantAnalysisStatus.RANKING, "Persisting explainable variant ranking");
            job.complete(
                    qcResult.getSelectedSampleId(),
                    normalization.isNormalizationApplied(),
                    conversion.isConversionApplied()
                            ? conversion.getEngine() + " + " + normalization.getEngine()
                    : normalization.getEngine(),
                    qcResult.getSummary(),
                    properties.getPersistence().isPersistObservations()
                            ? qcResult.getObservations()
                            : Collections.emptyList(),
                    warnings(conversion, normalization, qcResult)
            );
            job.applyEngineMetadata(EngineMetadata.varvisRanked());
            repository.replaceObservations(jobId, qcResult.getObservations());
            stageStarted = logStage(jobId, "persist observations", stageStarted);
            repository.replaceInterpretations(jobId, interpretations);
            benchmarkTelemetry.observePersistence(interpretations, repository.countInterpretations(jobId));
            stageStarted = logStage(jobId, "persist interpretations", stageStarted);
            saveWithPersistenceRetry(job, "final Varvis job checkpoint");
            appendEventWithPersistenceRetry(jobId, VariantAnalysisStatus.READY,
                    "Variant analysis is ready", "final Varvis ready event");
            triggerTranscriptAnnotationEnrichment(jobId);
            LOGGER.info("Variant analysis job {} completed in {} ms", jobId, elapsedMillis(jobStarted));
        } catch (Exception ex) {
            LOGGER.warn("Variant analysis job {} failed: {}", jobId, rootMessage(ex));
            job.applyEngineMetadata(new EngineMetadata(
                    org.nbscn.varvis.variantanalysis.domain.AnalysisEngine.VARVIS,
                    "product",
                    "VARVIS_DEFAULT",
                    org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus.FAILED,
                    rootMessage(ex),
                    "",
                    "",
                    false
            ));
            job.fail(rootMessage(ex));
            recordFailure(job);
        }
    }

    private void processExomiser(VariantAnalysisJob job,
                                 PhenotypeAwareSummary phenotypeSummary,
                                 BenchmarkTelemetryCollector benchmarkTelemetry) {
        if (phenotypeSummary != null) {
            job.applyPhenotypeSummary(phenotypeSummary);
        }
        job.applyEngineMetadata(EngineMetadata.exomiserRunning(
                properties.getExomiser().getVersion(),
                properties.getExomiser().getResourceFingerprint(),
                job.getPhenotypeSummary() == null || job.getPhenotypeSummary().getValidHpoTerms().isEmpty()
                        ? "EXOMISER_G0" : "EXOMISER_G1"));
        repository.save(job);
        try {
            transitionTo(job, VariantAnalysisStatus.VALIDATING, "Validating configured analysis runtime");
            ExomiserEngineAdapter adapter = exomiserEngineAdapter;
            if (adapter == null) {
                failExomiser(job, "EXOMISER_ADAPTER_UNAVAILABLE");
                return;
            }
            GenomeAssembly inputAssembly = job.getGenomeAssembly() == null
                    ? GenomeAssembly.GRCh38 : job.getGenomeAssembly();
            BuildConversionResult conversion = buildConverter.convert(
                    job.getUploadedFile(), job.getWorkspace(), inputAssembly, GenomeAssembly.GRCh38
            );
            Path analysisVcf = conversion.getOutputFile();
            if (analysisVcf == null || !Files.isRegularFile(analysisVcf) || !Files.isReadable(analysisVcf)) {
                failExomiser(job, "EXOMISER_LIFTOVER_OUTPUT_UNREADABLE");
                return;
            }
            if (conversion.isConversionApplied()) {
                String message = conversion.getRejectedRecordCount() > 0
                        ? "PARTIAL_LIFTOVER: source=" + conversion.getSourceRecordCount()
                        + ", lifted=" + conversion.getAnalysisRecordCount()
                        + ", rejected=" + conversion.getRejectedRecordCount()
                        + "; rejected records are retained for audit and only the lifted subset is analyzed"
                        : "GRCh37 input converted to GRCh38 for analysis";
                repository.appendEvent(job.getId(), VariantAnalysisStatus.NORMALIZING, message);
                if (conversion.getAnalysisRecordCount() <= 0) {
                    failExomiser(job, "EXOMISER_LIFTOVER_NO_RECORDS:SOURCE="
                            + conversion.getSourceRecordCount() + ";REJECTED="
                            + conversion.getRejectedRecordCount());
                    return;
                }
            }
            TrioPipelineInput trioInput = exomiserTrioInput(job);
            EngineExecutionResult result = trioInput.isPresent()
                    ? adapter.prepare(job, analysisVcf, GenomeAssembly.GRCh38, trioInput)
                    : adapter.prepare(job, analysisVcf, GenomeAssembly.GRCh38);
            if (result == null || result.getMetadata() == null) {
                failExomiser(job, "EXOMISER_ADAPTER_RESULT_INVALID");
                return;
            }
            job.applyEngineMetadata(result.getMetadata());
            // Persist the engine terminal checkpoint before local enrichment or
            // result persistence. A restart can then close only a fully proven
            // output checkpoint without running Exomiser again.
            saveWithPersistenceRetry(job, "Exomiser terminal engine checkpoint");
            recordExomiserAttempt(job, result, trioInput);
            if (result.getMetadata().getStatus() != EngineExecutionStatus.RANKED) {
                String reason = result.getFailureCode();
                if (reason == null || reason.trim().isEmpty()) {
                    reason = result.getMetadata().getUnavailableReason();
                }
                failExomiser(job, reason == null || reason.trim().isEmpty()
                        ? "EXOMISER_EXECUTION_FAILED" : reason);
                return;
            }
            transitionTo(job, VariantAnalysisStatus.BASE_ANNOTATING,
                    "Adding configured local evidence to engine-ranked candidates");
            AnalysisContext evidenceContext = new AnalysisContext(
                    job.getId(),
                    GenomeAssembly.GRCh38,
                    job.getAnalysisMode(),
                    VcfPreflightSummary.empty(),
                    job.getPhenotypeSummary(),
                    benchmarkTelemetry
            );
            List<VariantInterpretation> interpretations = interpretationService.enrichEvidenceOnly(
                    result.getInterpretations(), evidenceContext
            );
            repository.appendEvent(job.getId(), VariantAnalysisStatus.RANKING,
                    "EXOMISER_CANDIDATE_AUDIT: engine-ranked=" + interpretations.size()
                            + (conversion.isConversionApplied()
                            ? ", lifted-input=" + conversion.getAnalysisRecordCount()
                            + ", rejected-input=" + conversion.getRejectedRecordCount()
                            : "")
                            + "; engine-primary composite ordering applied with configured local evidence");
            transitionTo(job, VariantAnalysisStatus.RANKING, "Persisting engine ranking output");
            job.complete(job.getRequestedSampleId(), conversion.isConversionApplied(),
                    conversion.isConversionApplied() ? liftoverEngineLabel(conversion) : "EXOMISER", null,
                    Collections.<VariantQcObservation>emptyList(), conversion.getWarnings());
            repository.replaceObservations(job.getId(), Collections.<VariantQcObservation>emptyList());
            repository.replaceInterpretations(job.getId(), interpretations);
            if (acmgExecutionSources != null) {
                prepareAcmgTranscriptAnnotations(job, conversion, result);
                try {
                    acmgExecutionSources.publish(job, conversion, result,
                            repository.findInterpretations(job.getId(), 0,
                                    org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.MAX_CANDIDATES));
                } catch (Exception unavailable) {
                    // Optional ACMG provenance must never fail an otherwise valid base analysis.
                    LOGGER.warn("ACMG execution source unavailable for analysis {}: {}", job.getId(),
                            org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.code(unavailable));
                }
            }
            benchmarkTelemetry.observePersistence(
                    interpretations,
                    repository.countInterpretations(job.getId())
            );
            saveWithPersistenceRetry(job, "final Exomiser job checkpoint");
            appendEventWithPersistenceRetry(job.getId(), VariantAnalysisStatus.READY,
                    "Variant analysis is ready", "final Exomiser ready event");
            LOGGER.info("Variant analysis job {} completed through configured engine", job.getId());
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("Variant analysis job {} engine execution failed: {}", job.getId(), rootMessage(ex));
            failExomiser(job, "EXOMISER_EXECUTION_FAILED:" + rootMessage(ex));
        } finally {
            if (trioPipelineInputProvider != null) {
                trioPipelineInputProvider.release(job);
            }
        }
    }

    private void prepareAcmgTranscriptAnnotations(VariantAnalysisJob job, BuildConversionResult conversion,
                                                  EngineExecutionResult execution) {
        if (!acmgBusinessEnabled || transcriptAnnotationEnrichmentService == null) {
            return;
        }
        try {
            if (conversion.isConversionApplied()) {
                var proof = conversion.getSourceProof();
                if (proof == null || execution.getInputSource() == null) return;
                org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.verifyTask(proof, job,
                        conversion.getOutputFile(), execution.getInputSource().observedVcfSha256());
            } else if (job.getGenomeAssembly() != GenomeAssembly.GRCh38) return;
            // The official order is already persisted. This bounded annotation-only call neither
            // invokes enhancement/ranking nor reads the input VCF again. Keep existing completed annotations.
            List<VariantInterpretation> selected = repository.findInterpretations(job.getId(), 0,
                    org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.MAX_CANDIDATES).stream()
                    .filter(candidate -> candidate.getTranscriptAnnotations().stream()
                            .noneMatch(annotation -> "COMPLETED".equals(annotation.getStatus())))
                    .collect(java.util.stream.Collectors.toList());
            if (selected.isEmpty()) return;
            var annotations = transcriptAnnotationEnrichmentService.annotateCandidates(
                    selected, "VEP", "VEP_CACHED", "VEP_UNAVAILABLE", GenomeAssembly.GRCh38);
            acmgExecutionSources.savePublicationAnnotations(job.getId(), selected, annotations.getAnnotationsByVariantId());
            // A successful provider return alone is not a saved annotation result.
            transcriptAnnotationEnrichmentService.appendSummaryEvent(job.getId(), annotations.getSummary());
        } catch (IOException | RuntimeException unavailable) {
            // Annotation gaps remain visible to the explicit input validator, without failing the base job.
            LOGGER.warn("ACMG transcript preparation unavailable for analysis {}: {}", job.getId(),
                    org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.code(unavailable));
            try {
                repository.appendEvent(job.getId(), VariantAnalysisStatus.READY,
                        "VEP transcript annotation FAILED: scope=ACMG_SOURCE_PUBLICATION; annotation preparation or persistence failed");
            } catch (RuntimeException eventUnavailable) {
                LOGGER.warn("ACMG annotation failure event unavailable for analysis {}", job.getId(), eventUnavailable);
            }
        }
    }

    private TrioPipelineInput exomiserTrioInput(VariantAnalysisJob job) {
        if (trioPipelineInputProvider == null) {
            return TrioPipelineInput.absent();
        }
        TrioPipelineInput input = trioPipelineInputProvider.inputFor(job);
        return input == null ? TrioPipelineInput.absent() : input;
    }

    private void recordExomiserAttempt(VariantAnalysisJob job,
                                       EngineExecutionResult result,
                                       TrioPipelineInput trioInput) {
        if (job == null || result == null) {
            return;
        }
        appendEventWithPersistenceRetry(job.getId(), VariantAnalysisStatus.VALIDATING,
                "EXOMISER_EXECUTION_PROVENANCE: request-sha256=" + result.getRequestSha256()
                        + ";command-sha256=" + result.getCommandSha256()
                        + ";output-sha256=" + (result.getMetadata() == null
                        ? "" : result.getMetadata().getOutputSha256())
                        + ";exit-code=" + (result.getExitCode() == null ? "UNAVAILABLE" : result.getExitCode()),
                "Exomiser execution provenance event");
        if (trioInput == null || !trioInput.isPresent() || trioInput.getSampleMapping() == null) {
            return;
        }
        appendEventWithPersistenceRetry(job.getId(), VariantAnalysisStatus.VALIDATING,
                "EXOMISER_TRIO_PEDIGREE: mode=" + trioInput.getInputMode()
                        + ";proband=" + trioInput.getSampleMapping().getProbandSampleId()
                        + ";father=" + trioInput.getSampleMapping().getFatherSampleId()
                        + ";mother=" + trioInput.getSampleMapping().getMotherSampleId()
                        + ";ped-sha256=" + result.getPedigreeSha256()
                        + ";input-sha256=" + job.getFileSha256(),
                "Exomiser trio provenance event");
    }

    private String liftoverEngineLabel(BuildConversionResult conversion) {
        if (conversion.getRejectedRecordCount() <= 0) {
            return "PICARD;LIFTOVER;SOURCE=" + conversion.getSourceRecordCount()
                    + ";LIFTED=" + conversion.getAnalysisRecordCount()
                    + ";REJECTED=" + conversion.getRejectedRecordCount();
        }
        // normalization_engine is VARCHAR(64) in the established schema.
        // Keep every audit count while using a compact, parseable representation.
        return "PICARD;PARTIAL_LIFTOVER:" + conversion.getRejectedRecordCount()
                + ";SOURCE=" + conversion.getSourceRecordCount()
                + ";LIFTED=" + conversion.getAnalysisRecordCount();
    }

    private void failExomiser(VariantAnalysisJob job, String reason) {
        EngineMetadata metadata = job.getEngineMetadata();
        if (metadata == null || metadata.getStatus() == EngineExecutionStatus.RUNNING) {
            job.applyEngineMetadata(EngineMetadata.exomiserFailed(
                    reason,
                    properties.getExomiser().getVersion(),
                    properties.getExomiser().getResourceFingerprint(),
                    job.getPhenotypeSummary() == null || job.getPhenotypeSummary().getValidHpoTerms().isEmpty()
                            ? "EXOMISER_G0" : "EXOMISER_G1"));
        }
        job.fail(reason);
        recordFailure(job);
    }

    private void triggerTranscriptAnnotationEnrichment(String jobId) {
        if (transcriptAnnotationEnrichmentService == null) {
            return;
        }
        transcriptAnnotationEnrichmentService.process(jobId);
    }

    private PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary runPreRankingTranscriptAnnotation(
            String jobId,
            List<VariantInterpretation> interpretations,
            AnalysisContext analysisContext) {
        if (preRankingTranscriptAnnotationService == null) {
            return PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary.skipped(
                    "SKIPPED",
                    "Pre-ranking transcript annotation service is not configured."
            );
        }
        try {
            PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                    preRankingTranscriptAnnotationService.annotateCandidates(interpretations, analysisContext);
            repository.appendEvent(jobId, VariantAnalysisStatus.BASE_ANNOTATING, summary.eventMessage());
            return summary;
        } catch (RuntimeException ex) {
            String message = "Pre-ranking transcript annotation PROVIDER_ERROR: " + rootMessage(ex);
            LOGGER.warn("Variant analysis job {} pre-ranking transcript annotation failed: {}", jobId, rootMessage(ex));
            repository.appendEvent(jobId, VariantAnalysisStatus.BASE_ANNOTATING, message);
            if (!properties.getVep().isPreRankingFailOpen()) {
                throw ex;
            }
            return PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary.skipped(
                    "PROVIDER_ERROR",
                    message
            );
        }
    }

    private TrioPipelineResult runTrioEvidenceStage(VariantAnalysisJob job,
                                                    List<VariantInterpretation> interpretations,
                                                    GenomeAssembly analysisAssembly) {
        if (trioPipelineEvidenceService == null || trioPipelineInputProvider == null) {
            return TrioPipelineResult.skipped("DISABLED", "Trio evidence service is not configured.");
        }
        TrioPipelineInput input = trioPipelineInputProvider.inputFor(job);
        TrioPipelineResult result =
                trioPipelineEvidenceService.attachEvidence(interpretations, analysisAssembly, input);
        if (result.isEnabled()) {
            repository.appendEvent(job.getId(), VariantAnalysisStatus.BASE_ANNOTATING, result.eventMessage());
        }
        return result;
    }

    private void transitionTo(VariantAnalysisJob job, VariantAnalysisStatus status, String message) {
        job.transitionTo(status);
        repository.save(job);
        repository.appendEvent(job.getId(), status, message);
    }

    private VcfPreflightSummary preflightWithFeatureFlags(VcfPreflightSummary preflightSummary,
                                                          List<VariantInterpretation> interpretations) {
        if (preflightSummary == null || interpretations == null || interpretations.isEmpty()) {
            return preflightSummary;
        }
        boolean hasAnn = false;
        boolean hasCsq = false;
        boolean hasInfoAf = false;
        for (VariantInterpretation interpretation : interpretations) {
            for (org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence consequence
                    : interpretation.getConsequences()) {
                String source = consequence.getSource();
                if ("ANN".equals(source)) {
                    hasAnn = true;
                }
                if ("CSQ".equals(source)) {
                    hasCsq = true;
                }
            }
            for (org.nbscn.varvis.variantanalysis.domain.EvidenceItem evidence
                    : interpretation.getEvidence()) {
                if ("GNOMAD".equals(evidence.getSource())
                        && "VCF_INFO".equals(evidence.getSourceVersion())
                        && "APPLICABLE".equals(evidence.getApplicability())) {
                    hasInfoAf = true;
                }
            }
        }
        return preflightSummary.withFeatureFlags(hasAnn, hasCsq, hasInfoAf);
    }

    private List<String> warnings(BuildConversionResult conversion,
                                  NormalizationResult normalization,
                                  VcfQcResult qcResult) {
        List<String> warnings = new ArrayList<String>();
        warnings.addAll(conversion.getWarnings());
        warnings.addAll(normalization.getWarnings());
        warnings.addAll(qcResult.getWarnings());
        return warnings;
    }

    private NormalizationResult completeNormalizationManifest(VariantAnalysisJob job,
                                                              NormalizationResult normalization,
                                                              VcfQcResult qcResult) throws java.io.IOException {
        if (normalization == null || normalization.getManifest() == null
                || qcResult == null || qcResult.getSummary() == null) {
            return normalization;
        }
        NormalizationRunManifest completed = normalization.getManifest()
                .withNormalizedAlleleCount(qcResult.getSummary().getVariantCount());
        NormalizationManifestWriter.write(job.getWorkspace(), completed);
        repository.appendEvent(
                job.getId(),
                VariantAnalysisStatus.QC_EXTRACTING,
                completed.eventMessage()
        );
        return normalization.withManifest(completed);
    }

    private long logStage(String jobId, String stage, long startedAtNanos) {
        long now = System.nanoTime();
        LOGGER.info(
                "Variant analysis job {} stage '{}' completed in {} ms",
                jobId,
                stage,
                TimeUnit.NANOSECONDS.toMillis(now - startedAtNanos)
        );
        return now;
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void recordFailure(VariantAnalysisJob job) {
        try {
            saveWithPersistenceRetry(job, "failed job checkpoint");
            appendEventWithPersistenceRetry(job.getId(), VariantAnalysisStatus.FAILED,
                    job.getErrorMessage(), "failed job event");
        } catch (Exception persistenceEx) {
            LOGGER.warn(
                    "Could not persist FAILED state for variant analysis job {} after processing error: {}",
                    job.getId(),
                    rootMessage(persistenceEx)
            );
        }
    }

    private void saveWithPersistenceRetry(VariantAnalysisJob job, String checkpoint) {
        int attempts = properties.getPersistence().normalizedRetryAttempts();
        long backoffMillis = properties.getPersistence().normalizedRetryBackoff().toMillis();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                repository.save(job);
                if (attempt > 1) {
                    LOGGER.info(
                            "Persisted {} for variant analysis job {} on retry {}/{}",
                            checkpoint,
                            job.getId(),
                            attempt,
                            attempts
                    );
                }
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (attempt >= attempts || !isTransientPersistenceFailure(failure)) {
                    break;
                }
                LOGGER.warn(
                        "Could not persist {} for variant analysis job {} on attempt {}/{}: {}",
                        checkpoint,
                        job.getId(),
                        attempt,
                        attempts,
                        rootMessage(failure)
                );
                if (backoffMillis > 0L) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(backoffMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw lastFailure;
                    }
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Persistence checkpoint failed without an exception")
                : lastFailure;
    }

    private void appendEventWithPersistenceRetry(String jobId,
                                                 VariantAnalysisStatus status,
                                                 String message,
                                                 String checkpoint) {
        int attempts = properties.getPersistence().normalizedRetryAttempts();
        long backoffMillis = properties.getPersistence().normalizedRetryBackoff().toMillis();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                repository.appendEvent(jobId, status, message);
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (attempt >= attempts || !isTransientPersistenceFailure(failure)) {
                    break;
                }
                LOGGER.warn(
                        "Could not persist {} for variant analysis job {} on attempt {}/{}: {}",
                        checkpoint,
                        jobId,
                        attempt,
                        attempts,
                        rootMessage(failure)
                );
                if (backoffMillis > 0L) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(backoffMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw lastFailure;
                    }
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Persistence event failed without an exception")
                : lastFailure;
    }

    private boolean isTransientPersistenceFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof TransientDataAccessException
                    || current instanceof SQLTransientException
                    || current instanceof SQLRecoverableException
                    || current instanceof SQLNonTransientConnectionException
                    || current instanceof SocketException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            if (current instanceof SQLException) {
                String sqlState = ((SQLException) current).getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("connection is not available")
                        || normalized.contains("connection reset")
                        || normalized.contains("connection refused")
                        || normalized.contains("communications link failure")
                        || normalized.contains("socket timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }
}
