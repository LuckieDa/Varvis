package org.nbscn.varvis.variantanalysis.service.impl;

import org.nbscn.varvis.utils.VcfUploadUtils;
import org.nbscn.varvis.exception.VariantAnalysisInputException;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.domain.VcfPreflightSummary;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse;
import org.nbscn.varvis.variantanalysis.dto.PhenotypeGenePriorLookupResponse;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisVariantPageResponse;
import org.nbscn.varvis.variantanalysis.dto.VcfSampleDetectionResult;
import org.nbscn.varvis.variantanalysis.persistence.AmbiguousVariantIdException;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.GenePhenotypePriorProvider;
import org.nbscn.varvis.variantanalysis.service.CandidateRankingExplanationAssembler;
import org.nbscn.varvis.variantanalysis.service.GenePhenotypePriorProviderFactory;
import org.nbscn.varvis.variantanalysis.service.HpoInputQualityService;
import org.nbscn.varvis.variantanalysis.service.PhenotypeInputNormalizer;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisJobProcessor;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisDiagnosticAssembler;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.nbscn.varvis.variantanalysis.service.VariantEnhancementProcessor;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.nbscn.varvis.variantanalysis.service.VcfInputPreflightService;
import org.nbscn.varvis.variantanalysis.service.VcfTabixIndexValidator;
import org.nbscn.varvis.variantanalysis.trio.RequestBackedTrioPipelineInputProvider;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInputResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

@Service
public class VariantAnalysisServiceImpl implements VariantAnalysisService {

    @Override
    public List<VariantAnalysisJob> findHistory(MultipartFile vcfFile) throws IOException {
        VcfUploadUtils.validateUpload(vcfFile, properties.getMaxUploadSizeBytes(), properties.getMaxExpandedSizeBytes());
        return repository.findHistoryByFileSha256(sha256(vcfFile));
    }

    private static final CandidateRankingExplanationAssembler RANKING_EXPLANATION_ASSEMBLER =
            new CandidateRankingExplanationAssembler();
    private static final VariantAnalysisDiagnosticAssembler DIAGNOSTIC_ASSEMBLER =
            new VariantAnalysisDiagnosticAssembler();

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 1000;

    private final VariantAnalysisJobRepository repository;
    private final VariantEnhancementRepository enhancementRepository;
    private final VariantAnalysisJobProcessor processor;
    private final VariantEnhancementProcessor enhancementProcessor;
    private final VariantAnalysisProperties properties;
    private final GenePhenotypePriorProvider genePhenotypePriorProvider;
    private final RequestBackedTrioPipelineInputProvider trioPipelineInputProvider;
    private final TrioPipelineInputResolver trioPipelineInputResolver;
    private final VcfHeaderSampleDetectionService vcfHeaderSampleDetectionService;
    private final VcfInputPreflightService vcfInputPreflightService;
    private final HpoInputQualityService hpoInputQualityService;
    private final ConcurrentMap<String, PhenotypeAwareSummary> phenotypeSummaries =
            new ConcurrentHashMap<String, PhenotypeAwareSummary>();

    @Autowired
    public VariantAnalysisServiceImpl(VariantAnalysisJobRepository repository,
                                      VariantEnhancementRepository enhancementRepository,
                                      VariantAnalysisJobProcessor processor,
                                      VariantEnhancementProcessor enhancementProcessor,
                                      VariantAnalysisProperties properties,
                                      RequestBackedTrioPipelineInputProvider trioPipelineInputProvider,
                                      TrioPipelineInputResolver trioPipelineInputResolver,
                                      VcfHeaderSampleDetectionService vcfHeaderSampleDetectionService,
                                      VcfInputPreflightService vcfInputPreflightService) {
        this(
                repository,
                enhancementRepository,
                processor,
                enhancementProcessor,
                properties,
                trioPipelineInputProvider,
                trioPipelineInputResolver,
                vcfHeaderSampleDetectionService,
                vcfInputPreflightService,
                HpoInputQualityService.fromProperties(properties)
        );
    }

    public VariantAnalysisServiceImpl(VariantAnalysisJobRepository repository,
                                      VariantEnhancementRepository enhancementRepository,
                                      VariantAnalysisJobProcessor processor,
                                      VariantEnhancementProcessor enhancementProcessor,
                                      VariantAnalysisProperties properties,
                                      RequestBackedTrioPipelineInputProvider trioPipelineInputProvider,
                                      TrioPipelineInputResolver trioPipelineInputResolver,
                                      VcfHeaderSampleDetectionService vcfHeaderSampleDetectionService,
                                      VcfInputPreflightService vcfInputPreflightService,
                                      HpoInputQualityService hpoInputQualityService) {
        this.repository = repository;
        this.enhancementRepository = enhancementRepository;
        this.processor = processor;
        this.enhancementProcessor = enhancementProcessor;
        this.properties = properties;
        this.genePhenotypePriorProvider = GenePhenotypePriorProviderFactory.create(properties);
        this.trioPipelineInputProvider = trioPipelineInputProvider;
        this.trioPipelineInputResolver = trioPipelineInputResolver == null
                ? new TrioPipelineInputResolver()
                : trioPipelineInputResolver;
        this.vcfHeaderSampleDetectionService = vcfHeaderSampleDetectionService == null
                ? new VcfHeaderSampleDetectionService()
                : vcfHeaderSampleDetectionService;
        this.vcfInputPreflightService = vcfInputPreflightService == null
                ? new VcfInputPreflightService()
                : vcfInputPreflightService;
        this.hpoInputQualityService = hpoInputQualityService == null
                ? HpoInputQualityService.fromProperties(properties)
                : hpoInputQualityService;
    }

    public VariantAnalysisServiceImpl(VariantAnalysisJobRepository repository,
                                      VariantEnhancementRepository enhancementRepository,
                                      VariantAnalysisJobProcessor processor,
                                      VariantEnhancementProcessor enhancementProcessor,
                                      VariantAnalysisProperties properties,
                                      RequestBackedTrioPipelineInputProvider trioPipelineInputProvider,
                                      TrioPipelineInputResolver trioPipelineInputResolver,
                                      VcfHeaderSampleDetectionService vcfHeaderSampleDetectionService) {
        this(
                repository,
                enhancementRepository,
                processor,
                enhancementProcessor,
                properties,
                trioPipelineInputProvider,
                trioPipelineInputResolver,
                vcfHeaderSampleDetectionService,
                new VcfInputPreflightService()
        );
    }

    public VariantAnalysisServiceImpl(VariantAnalysisJobRepository repository,
                                      VariantEnhancementRepository enhancementRepository,
                                      VariantAnalysisJobProcessor processor,
                                      VariantEnhancementProcessor enhancementProcessor,
                                      VariantAnalysisProperties properties,
                                      RequestBackedTrioPipelineInputProvider trioPipelineInputProvider,
                                      TrioPipelineInputResolver trioPipelineInputResolver) {
        this(
                repository,
                enhancementRepository,
                processor,
                enhancementProcessor,
                properties,
                trioPipelineInputProvider,
                trioPipelineInputResolver,
                new VcfHeaderSampleDetectionService()
        );
    }

    public VariantAnalysisServiceImpl(VariantAnalysisJobRepository repository,
                                      VariantEnhancementRepository enhancementRepository,
                                      VariantAnalysisJobProcessor processor,
                                      VariantEnhancementProcessor enhancementProcessor,
                                      VariantAnalysisProperties properties) {
        this(
                repository,
                enhancementRepository,
                processor,
                enhancementProcessor,
                properties,
                null,
                new TrioPipelineInputResolver(),
                new VcfHeaderSampleDetectionService()
        );
    }

    @Override
    public VariantAnalysisJob submit(MultipartFile vcfFile,
                                     String genomeAssembly,
                                     String analysisMode,
                                     String sampleId,
                                     List<String> hpoTerms) throws IOException {
        return submit(vcfFile, genomeAssembly, analysisMode, sampleId, hpoTerms, null);
    }

    @Override
    public VariantAnalysisJob submit(MultipartFile vcfFile,
                                     String genomeAssembly,
                                     String analysisMode,
                                     String sampleId,
                                     List<String> hpoTerms,
                                     List<String> hpoProvenance) throws IOException {
        return submit(
                vcfFile,
                genomeAssembly,
                analysisMode,
                sampleId,
                hpoTerms,
                hpoProvenance,
                TrioAnalysisInputRequest.disabled(),
                null,
                null
        );
    }

    @Override
    public VariantAnalysisJob submit(MultipartFile vcfFile,
                                     String genomeAssembly,
                                     String analysisMode,
                                     String sampleId,
                                     List<String> hpoTerms,
                                     List<String> hpoProvenance,
                                     TrioAnalysisInputRequest trioInput,
                                     MultipartFile fatherVcfFile,
                                     MultipartFile motherVcfFile) throws IOException {
        return submit(vcfFile, genomeAssembly, analysisMode, sampleId, hpoTerms, hpoProvenance,
                null, trioInput, fatherVcfFile, motherVcfFile);
    }

    @Override
    public VariantAnalysisJob submit(MultipartFile vcfFile,
                                     String genomeAssembly,
                                     String analysisMode,
                                     String sampleId,
                                     List<String> hpoTerms,
                                     List<String> hpoProvenance,
                                     List<String> excludedHpoTerms,
                                     TrioAnalysisInputRequest trioInput,
                                     MultipartFile fatherVcfFile,
                                     MultipartFile motherVcfFile) throws IOException {
        return submit(vcfFile, genomeAssembly, analysisMode, sampleId, hpoTerms, hpoProvenance,
                excludedHpoTerms, trioInput, fatherVcfFile, motherVcfFile, null, false);
    }

    @Override
    public VariantAnalysisJob submit(MultipartFile vcfFile, String genomeAssembly, String analysisMode, String sampleId,
                                     List<String> hpoTerms, List<String> hpoProvenance, List<String> excludedHpoTerms,
                                     TrioAnalysisInputRequest trioInput, MultipartFile fatherVcfFile, MultipartFile motherVcfFile,
                                     String analysisScope, boolean createNewAnalysis) throws IOException {
        AnalysisScopePolicy.Scope scope;
        try { scope = AnalysisScopePolicy.parse(analysisScope); }
        catch (IllegalArgumentException invalid) { throw new VariantAnalysisInputException("INVALID_INPUT", invalid.getMessage()); }
        return submitInternal(vcfFile, genomeAssembly, analysisMode, sampleId, hpoTerms, hpoProvenance,
                excludedHpoTerms, trioInput, fatherVcfFile, motherVcfFile, BenchmarkTelemetryCollector.disabled(), scope,
                createNewAnalysis);
    }

    @Override
    public VariantAnalysisJob submitBenchmark(MultipartFile vcfFile,
                                              String genomeAssembly,
                                              String analysisMode,
                                              String sampleId,
                                              List<String> hpoTerms,
                                              List<String> hpoProvenance,
                                              BenchmarkTelemetryCollector telemetry) throws IOException {
        if (telemetry == null || !telemetry.isEnabled()) {
            throw new IllegalArgumentException("BENCHMARK_TELEMETRY_REQUIRED");
        }
        return submitInternal(vcfFile, genomeAssembly, analysisMode, sampleId, hpoTerms,
                hpoProvenance, null, TrioAnalysisInputRequest.disabled(), null, null, telemetry,
                AnalysisScopePolicy.Scope.STANDARD, false);
    }

    private VariantAnalysisJob submitInternal(MultipartFile vcfFile,
                                              String genomeAssembly,
                                              String analysisMode,
                                              String sampleId,
                                              List<String> hpoTerms,
                                              List<String> hpoProvenance,
                                              List<String> excludedHpoTerms,
                                              TrioAnalysisInputRequest trioInput,
                                              MultipartFile fatherVcfFile,
                                              MultipartFile motherVcfFile,
                                              BenchmarkTelemetryCollector telemetry, AnalysisScopePolicy.Scope scope,
                                              boolean createNewAnalysis) throws IOException {
        VcfUploadUtils.UploadMetadata metadata = VcfUploadUtils.validateUpload(
                vcfFile,
                properties.getMaxUploadSizeBytes(),
                properties.getMaxExpandedSizeBytes()
        );
        TrioAnalysisInputRequest normalizedTrioInput =
                trioInput == null ? TrioAnalysisInputRequest.disabled() : trioInput;
        validateTrioInput(normalizedTrioInput);
        GenomeAssembly assembly = GenomeAssembly.require(genomeAssembly);
        AnalysisMode mode = AnalysisMode.require(analysisMode);
        String normalizedSampleId = StringUtils.hasText(sampleId) ? sampleId.trim() : null;
        boolean exomiser = properties.getDefaultEngine() == AnalysisEngine.EXOMISER;
        boolean singleVariantReview = scope == AnalysisScopePolicy.Scope.SINGLE_VARIANT_REVIEW;
        if (singleVariantReview && (assembly != GenomeAssembly.GRCh38 || normalizedTrioInput.isEnabled())) {
            throw new VariantAnalysisInputException("INVALID_INPUT",
                    "SINGLE_VARIANT_REVIEW_SCOPE_UNSUPPORTED: use GRCh38 and a single-sample VCF without trio input.");
        }
        VcfSampleDetectionResult inputDetection = null;
        if (exomiser) {
            validateExomiserProductInput(metadata, normalizedTrioInput, assembly);
            if (normalizedTrioInput.isEnabled() && !StringUtils.hasText(normalizedSampleId)) {
                normalizedSampleId = normalizedTrioInput.getProbandSampleId();
            }
            if (!StringUtils.hasText(normalizedSampleId)) {
                throw new VariantAnalysisInputException(
                        "INVALID_INPUT", "A VCF sample ID is required for this analysis.");
            }
            String requiredSampleId = Objects.requireNonNull(normalizedSampleId, "normalizedSampleId");
            inputDetection = detectSamples(vcfFile, "VCF");
            validateDetectedAssembly(inputDetection, assembly);
            requireSamplesPresent(inputDetection, "EXOMISER_SAMPLE_ID_NOT_FOUND", requiredSampleId);
            if (normalizedTrioInput.isEnabled()
                    && !requiredSampleId.equals(normalizedTrioInput.getProbandSampleId())) {
                throw new VariantAnalysisInputException(
                        "INVALID_INPUT",
                        "TRIO_PROBAND_SAMPLE_CONFLICT: selected sample ID must equal the explicit trio proband sample ID."
                );
            }
        }
        PhenotypeAwareSummary normalizedPhenotypeSummary = telemetry.isEnabled()
                ? PhenotypeInputNormalizer.summarize(hpoTerms, hpoProvenance)
                : hpoInputQualityService.summarizeProduct(hpoTerms, hpoProvenance, excludedHpoTerms);
        if (exomiser && normalizedPhenotypeSummary.getInvalidHpoCount() > 0) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT", "Each HPO term must use the HP:1234567 format.");
        }
        validateTrioSampleMapping(normalizedTrioInput, vcfFile, fatherVcfFile, motherVcfFile);
        repository.verifyPersistenceSchema();
        final PhenotypeAwareSummary phenotypeSummary = normalizedPhenotypeSummary.withGenePriorSummary(
                genePhenotypePriorProvider.summarize(normalizedPhenotypeSummary)
        );
        AnalysisScopePolicy.Policy scopePolicy = null;
        if (exomiser) {
            try { scopePolicy = AnalysisScopePolicy.resolve(properties.getExomiser(), scope, hasObservedHpo(phenotypeSummary)); }
            catch (IOException | IllegalArgumentException unavailable) {
                if (scope != AnalysisScopePolicy.Scope.STANDARD) throw new VariantAnalysisInputException("INVALID_INPUT", unavailable.getMessage());
                // Keep the old default configuration failure path; without a valid policy no cache hit is allowed.
            }
        } else if (scope != AnalysisScopePolicy.Scope.STANDARD) throw new VariantAnalysisInputException("INVALID_INPUT", "ANALYSIS_SCOPE_ENGINE_UNSUPPORTED");
        String fileSha256 = sha256(vcfFile);
        Optional<VariantAnalysisJob> reusableJob = createNewAnalysis ? Optional.empty() : findAttestedReusableJob(
                fileSha256,
                assembly,
                mode,
                normalizedSampleId,
                phenotypeSummary,
                normalizedTrioInput,
                telemetry, scopePolicy
        );
        if (reusableJob.isPresent()) {
            VariantAnalysisJob existing = reusableJob.get();
            rememberPhenotypeSummary(existing.getId(), existing.getPhenotypeSummary());
            return existing;
        }
        String id = UUID.randomUUID().toString();
        Path workspace = createWorkspace(id);
        Path uploadedFile = VcfUploadUtils.createStoredFile(workspace, metadata.getExtension());
        Path fatherUploadedFile = null;
        Path motherUploadedFile = null;
        try {
            try (InputStream inputStream = vcfFile.getInputStream()) {
                Files.copy(inputStream, uploadedFile, StandardCopyOption.REPLACE_EXISTING);
            }
            VcfInputPreflightService.Result inputContentPreflight = null;
            if (exomiser && !telemetry.isEnabled()) {
                inputContentPreflight = singleVariantReview
                        ? vcfInputPreflightService.inspectSingleVariant(uploadedFile, normalizedSampleId)
                        : vcfInputPreflightService.inspect(uploadedFile, normalizedSampleId);
            }
            VcfTabixIndexValidator.PreparedVcf preparedVcf = exomiser
                    ? VcfTabixIndexValidator.prepareForExomiser(uploadedFile, metadata.getExtension())
                    : VcfTabixIndexValidator.PreparedVcf.unindexed(uploadedFile);
            Path executionVcf = preparedVcf.getExecutionVcf();
            fatherUploadedFile = storeOptionalTrioFile(normalizedTrioInput, fatherVcfFile, workspace);
            motherUploadedFile = storeOptionalTrioFile(normalizedTrioInput, motherVcfFile, workspace);
            TrioPipelineInput resolvedTrioInput = trioPipelineInputResolver.resolve(
                    normalizedTrioInput,
                    executionVcf,
                    fatherUploadedFile,
                    motherUploadedFile
            );
            if (!fileSha256.equals(sha256(uploadedFile))) {
                throw new IOException("Stored VCF digest does not match the validated upload");
            }
            VariantAnalysisJob job = new VariantAnalysisJob(
                    id,
                    metadata.getOriginalFileName(),
                    fileSha256,
                    assembly,
                    mode,
                    normalizedSampleId,
                    workspace,
                    executionVcf,
                    phenotypeSummary
            );
            if (scopePolicy != null) AnalysisScopePolicy.save(job, scopePolicy);
            initializeEngineMetadata(job, phenotypeSummary);
            rememberPhenotypeSummary(id, phenotypeSummary);
            repository.save(job);
            registerTrioInput(id, resolvedTrioInput);
            repository.appendEvent(id, job.getStatus(), "Uploaded VCF stored");
            if (inputContentPreflight != null) {
                repository.appendEvent(id, job.getStatus(), inputContentPreflight.safeEventMessage());
            }
            if (inputDetection != null) {
                repository.appendEvent(
                        id,
                        job.getStatus(),
                        "Input preflight: " + assembly.name()
                                + " (" + inputDetection.getAssemblyConfidence() + "), "
                                + inputDetection.getSamples().size() + " sample(s), "
                                + inputDetection.getContigStyle() + " contigs, "
                                + inputDetection.getIndexPreparation()
                );
            }
            if (preparedVcf.getTabixIndex() != null) {
                repository.appendEvent(id, job.getStatus(), preparedVcf.isNormalizedToBgzf()
                        ? "Compressed VCF normalized to BGZF and indexed in the private job workspace"
                        : "Compressed VCF indexed in the private job workspace");
            }
            if (resolvedTrioInput.isPresent()) {
                repository.appendEvent(
                        id,
                        job.getStatus(),
                        trioInputCapturedMessage()
                );
            }
            if (phenotypeSummary.isEnabled()) {
                repository.appendEvent(
                        id,
                        job.getStatus(),
                        "Phenotype HPO input captured; phenotype-aware ranking will be attempted"
                );
            } else if (phenotypeSummary.hasAnyPhenotypeInput()) {
                repository.appendEvent(
                        id,
                        job.getStatus(),
                        "Explicitly absent phenotype HPO input captured as observational metadata"
                );
            }
            if (telemetry.isEnabled()) {
                processor.process(id, phenotypeSummary, telemetry);
            } else {
                processor.process(id, phenotypeSummary);
            }
            return job;
        } catch (IOException | RuntimeException ex) {
            phenotypeSummaries.remove(id);
            unregisterTrioInput(id);
            deleteWorkspace(workspace);
            throw ex;
        }
    }

    private void initializeEngineMetadata(VariantAnalysisJob job,
                                          PhenotypeAwareSummary phenotypeSummary) {
        if (properties.getDefaultEngine() == AnalysisEngine.EXOMISER) {
            job.applyEngineMetadata(EngineMetadata.exomiserRunning(
                    properties.getExomiser().getVersion(),
                    properties.getExomiser().getResourceFingerprint(),
                    hasObservedHpo(phenotypeSummary) ? "EXOMISER_G1" : "EXOMISER_G0"));
            return;
        }
        job.applyEngineMetadata(EngineMetadata.varvis(
                org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus.NOT_REQUESTED));
    }

    /**
     * Restores only a persisted result whose result-bearing inputs and terminal engine
     * provenance match the current request. Policy {@value #CROSS_JOB_REUSE_POLICY_VERSION}
     * is deliberately limited to native GRCh38 single-sample Exomiser runs because trio
     * pedigree inputs and liftover execution attestations are not persisted in the reusable
     * job identity.
     */
    private Optional<VariantAnalysisJob> findAttestedReusableJob(String fileSha256,
                                                                 GenomeAssembly assembly,
                                                                 AnalysisMode mode,
                                                                 String sampleId,
                                                                 PhenotypeAwareSummary phenotypeSummary,
                                                                 TrioAnalysisInputRequest trioInput,
                                                                 BenchmarkTelemetryCollector telemetry, AnalysisScopePolicy.Policy scopePolicy) {
        if (properties.getDefaultEngine() != AnalysisEngine.EXOMISER
                || !properties.getExomiser().isEnabled()
                || assembly != GenomeAssembly.GRCh38
                || (trioInput != null && trioInput.isEnabled())
                || (telemetry != null && telemetry.isEnabled())) {
            return Optional.empty();
        }
        String engineVersion = properties.getExomiser().getVersion();
        String resourceFingerprint = properties.getExomiser().getResourceFingerprint();
        if (!StringUtils.hasText(engineVersion) || !StringUtils.hasText(resourceFingerprint)) {
            return Optional.empty();
        }
        List<VariantAnalysisJob> candidates = repository.findReusableReadyJobs(
                fileSha256,
                assembly.name(),
                mode.name(),
                sampleId
        );
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String rankingMode = hasObservedHpo(phenotypeSummary) ? "EXOMISER_G1" : "EXOMISER_G0";
        for (VariantAnalysisJob candidate : candidates) {
            if (AnalysisScopePolicy.reusable(candidate, scopePolicy) && hasReusableResultAttestation(
                    candidate,
                    fileSha256,
                    assembly,
                    mode,
                    sampleId,
                    phenotypeSummary,
                    engineVersion,
                    resourceFingerprint,
                    rankingMode
            )) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean hasReusableResultAttestation(VariantAnalysisJob candidate,
                                                  String fileSha256,
                                                  GenomeAssembly assembly,
                                                  AnalysisMode mode,
                                                  String sampleId,
                                                  PhenotypeAwareSummary phenotypeSummary,
                                                  String engineVersion,
                                                  String resourceFingerprint,
                                                  String rankingMode) {
        if (candidate == null
                || candidate.getStatus() != VariantAnalysisStatus.READY
                || !Objects.equals(fileSha256, candidate.getFileSha256())
                || candidate.getGenomeAssembly() != assembly
                || candidate.getAnalysisMode() != mode
                || !Objects.equals(sampleId, candidate.getRequestedSampleId())
                || !Objects.equals(sampleId, candidate.getSelectedSampleId())
                || candidate.isNormalizationApplied()
                || !"EXOMISER".equals(candidate.getNormalizationEngine())
                || candidate.getUploadedFile() == null
                || !Files.isRegularFile(candidate.getUploadedFile())
                || repository.countInterpretations(candidate.getId()) <= 0
                || !phenotypeInputsEquivalent(phenotypeSummary, candidate.getPhenotypeSummary())) {
            return false;
        }
        EngineMetadata metadata = candidate.getEngineMetadata();
        return metadata != null
                && metadata.getEngine() == AnalysisEngine.EXOMISER
                && metadata.getStatus() == EngineExecutionStatus.RANKED
                && !metadata.isFallbackUsed()
                && !StringUtils.hasText(metadata.getUnavailableReason())
                && Objects.equals(engineVersion, metadata.getEngineVersion())
                && Objects.equals(resourceFingerprint, metadata.getResourceFingerprint())
                && Objects.equals(rankingMode, metadata.getRankingMode())
                && metadata.getOutputSha256() != null
                && metadata.getOutputSha256().matches("[0-9a-fA-F]{64}");
    }

    private boolean phenotypeInputsEquivalent(PhenotypeAwareSummary requested,
                                              PhenotypeAwareSummary persisted) {
        PhenotypeAwareSummary left = requested == null ? PhenotypeAwareSummary.disabled() : requested;
        PhenotypeAwareSummary right = persisted == null ? PhenotypeAwareSummary.disabled() : persisted;
        return left.getInputHpoCount() == right.getInputHpoCount()
                && left.getValidHpoCount() == right.getValidHpoCount()
                && left.getInvalidHpoCount() == right.getInvalidHpoCount()
                && left.getDuplicateHpoCount() == right.getDuplicateHpoCount()
                && sortedStrings(left.getValidHpoTerms()).equals(sortedStrings(right.getValidHpoTerms()))
                && sortedStrings(left.getInvalidHpoTerms()).equals(sortedStrings(right.getInvalidHpoTerms()))
                && sortedStrings(left.getExcludedHpoTerms()).equals(sortedStrings(right.getExcludedHpoTerms()))
                && phenotypeInputSignatures(left.getPhenotypeInputs())
                        .equals(phenotypeInputSignatures(right.getPhenotypeInputs()))
                && genePriorInputsEquivalent(left.getGenePriorSummary(), right.getGenePriorSummary());
    }

    private boolean genePriorInputsEquivalent(GenePhenotypePriorSummary left,
                                              GenePhenotypePriorSummary right) {
        GenePhenotypePriorSummary requested = left == null ? GenePhenotypePriorSummary.notGenerated() : left;
        GenePhenotypePriorSummary persisted = right == null ? GenePhenotypePriorSummary.notGenerated() : right;
        return requested.isEnabled() == persisted.isEnabled()
                && Objects.equals(requested.getSource(), persisted.getSource())
                && Objects.equals(requested.getSourceVersion(), persisted.getSourceVersion())
                && requested.getStatus() == persisted.getStatus()
                && requested.getInputHpoCount() == persisted.getInputHpoCount()
                && requested.getValidHpoCount() == persisted.getValidHpoCount()
                && requested.getGenesWithPrior() == persisted.getGenesWithPrior()
                && genePriorSignatures(requested.getTopGenePriors())
                        .equals(genePriorSignatures(persisted.getTopGenePriors()));
    }

    private List<String> phenotypeInputSignatures(List<PhenotypeInput> values) {
        if (values == null || values.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            return java.util.Collections.singletonList("INVALID_NULL_PHENOTYPE_INPUT");
        }
        return values.stream()
                .map(value -> String.join("\u001f",
                        nullSafe(value.getHpoId()),
                        nullSafe(value.getLabel()),
                        nullSafe(value.getSource()),
                        nullSafe(value.getStatus()),
                        nullSafe(value.getConfidence()),
                        nullSafe(value.getMatchedTerm()),
                        nullSafe(value.getRequestedHpoId()),
                        nullSafe(value.getReplacementHpoId()),
                        nullSafe(value.getOntologyLabel()),
                        nullSafe(value.getOntologyStatus()),
                        Boolean.toString(value.isExcluded()),
                        String.join("\u001e", sortedStrings(value.getWarnings()))))
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    private List<String> genePriorSignatures(List<GenePhenotypePrior> values) {
        if (values == null || values.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            return java.util.Collections.singletonList("INVALID_NULL_GENE_PRIOR");
        }
        return values.stream()
                .map(value -> String.join("\u001f",
                        nullSafe(value.getGeneSymbol()),
                        nullSafe(value.getGeneId()),
                        Double.toHexString(value.getPhenotypeScore()),
                        Integer.toString(value.getPhenotypeRank()),
                        nullSafe(value.getSource()),
                        nullSafe(value.getSourceVersion()),
                        String.join("\u001e", sortedStrings(value.getMatchedHpoTerms())),
                        String.join("\u001e", sortedStrings(value.getEvidence())),
                        nullSafe(value.getStatus()),
                        String.join("\u001e", sortedStrings(value.getWarnings()))))
                .collect(java.util.stream.Collectors.toList());
    }

    private List<String> sortedStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return values.stream()
                .map(this::nullSafe)
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean hasObservedHpo(PhenotypeAwareSummary summary) {
        return summary != null
                && summary.getValidHpoTerms() != null
                && !summary.getValidHpoTerms().isEmpty();
    }

    private void validateExomiserProductInput(VcfUploadUtils.UploadMetadata metadata,
                                              TrioAnalysisInputRequest trioInput,
                                              GenomeAssembly assembly) {
        if (assembly != GenomeAssembly.GRCh38 && assembly != GenomeAssembly.GRCh37) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT", "This analysis workflow accepts GRCh37 or GRCh38 VCF input.");
        }
        if (".bcf".equals(metadata.getExtension())) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT", "Use a VCF or BGZF-compressed VCF for this analysis.");
        }
        if (trioInput != null && trioInput.isEnabled()
                && !TrioAnalysisInputRequest.MODE_JOINT_VCF.equals(trioInput.getInputMode())) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT",
                    "TRIO_JOINT_VCF_REQUIRED: Exomiser family analysis requires one joint VCF containing the explicit proband, father and mother samples. Separate VCF files are not merged by this workflow."
            );
        }
        if (trioInput != null && trioInput.isEnabled() && !trioInput.hasExplicitPedigreeAttributes()) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT",
                    "TRIO_PEDIGREE_ATTRIBUTES_INCOMPLETE: explicit sex and affected status are required for proband, father and mother."
            );
        }
        if (trioInput != null && trioInput.isEnabled() && !trioInput.hasValidParentSexes()) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT",
                    "TRIO_PARENT_SEX_INVALID: father must be MALE and mother must be FEMALE."
            );
        }
    }

    private void validateDetectedAssembly(VcfSampleDetectionResult detection,
                                          GenomeAssembly selectedAssembly) {
        if (detection == null) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT", "VCF assembly detection did not return a result.");
        }
        if ("CONFLICTING_EVIDENCE".equals(detection.getAssemblyConfidence())) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT",
                    "ASSEMBLY_AMBIGUOUS: the VCF header contains conflicting GRCh37/GRCh38 evidence. "
                            + "Correct the source file or confirm a single source assembly before analysis."
            );
        }
        if (!StringUtils.hasText(detection.getAssemblyHint())) {
            return;
        }
        GenomeAssembly detectedAssembly = GenomeAssembly.require(detection.getAssemblyHint());
        if (detectedAssembly != selectedAssembly) {
            throw new VariantAnalysisInputException(
                    "INVALID_INPUT",
                    "ASSEMBLY_CONFLICT: VCF header evidence indicates " + detectedAssembly.name()
                            + " but the request selected " + selectedAssembly.name()
                            + ". Choose the matching input build; no automatic cross-build assumption was made."
            );
        }
    }

    private String trioInputCapturedMessage() {
        VariantAnalysisProperties.Trio trio = properties.getTrio();
        if (trio.isRankingEnabled() && trio.normalizedRankingMaxScoreContribution() > 0.0d) {
            return "Trio evidence input captured; supported segregation models may add a bounded ranking contribution up to "
                    + trio.normalizedRankingMaxScoreContribution()
                    + " points without overriding hard gates";
        }
        return "Trio evidence input captured; evidence-only segregation analysis will be attempted";
    }

    private void validateTrioInput(TrioAnalysisInputRequest request) {
        List<String> errors = request == null
                ? TrioAnalysisInputRequest.disabled().validationErrors()
                : request.validationErrors();
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errors.get(0));
        }
    }

    private void validateTrioSampleMapping(TrioAnalysisInputRequest request,
                                           MultipartFile primaryVcfFile,
                                           MultipartFile fatherVcfFile,
                                           MultipartFile motherVcfFile) {
        if (request == null || request.isDisabled()) {
            return;
        }
        if (TrioAnalysisInputRequest.MODE_JOINT_VCF.equals(request.getInputMode())) {
            VcfSampleDetectionResult result = detectSamples(primaryVcfFile, "joint VCF");
            requireSamplesPresent(
                    result,
                    "TRIO_SAMPLE_NOT_FOUND_IN_JOINT_VCF",
                    request.getProbandSampleId(),
                    request.getFatherSampleId(),
                    request.getMotherSampleId()
            );
            return;
        }
        if (TrioAnalysisInputRequest.MODE_THREE_SINGLE_SAMPLE_VCFS.equals(request.getInputMode())) {
            requireSamplesPresent(
                    detectSamples(primaryVcfFile, "proband VCF"),
                    "TRIO_PROBAND_SAMPLE_NOT_FOUND",
                    request.getProbandSampleId()
            );
            requireSamplesPresent(
                    detectSamples(fatherVcfFile, "father VCF"),
                    "TRIO_FATHER_SAMPLE_NOT_FOUND",
                    request.getFatherSampleId()
            );
            requireSamplesPresent(
                    detectSamples(motherVcfFile, "mother VCF"),
                    "TRIO_MOTHER_SAMPLE_NOT_FOUND",
                    request.getMotherSampleId()
            );
        }
    }

    private VcfSampleDetectionResult detectSamples(MultipartFile file, String label) {
        VcfSampleDetectionResult result = vcfHeaderSampleDetectionService.detect(file);
        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VCF_HEADER_READ_FAILED: " + label + " sample detection failed."
            );
        }
        if (!result.isSuccessful()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.valueOf(result.getErrorCode()) + ": " + label + " "
                            + String.valueOf(result.getErrorMessage())
            );
        }
        return result;
    }

    private void requireSamplesPresent(VcfSampleDetectionResult result,
                                       String errorCode,
                                       String... requiredSamples) {
        List<String> missing = new ArrayList<String>();
        List<String> available = result == null ? java.util.Collections.<String>emptyList() : result.getSamples();
        for (String required : requiredSamples) {
            if (!StringUtils.hasText(required) || !available.contains(required.trim())) {
                missing.add(required == null ? "(empty)" : required.trim());
            }
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    errorCode + ": sample(s) " + String.join(", ", missing)
                            + " were not found in VCF header samples "
                            + samplePreview(available) + "."
            );
        }
    }

    private String samplePreview(List<String> samples) {
        if (samples == null || samples.isEmpty()) {
            return "[]";
        }
        int previewSize = Math.min(samples.size(), 8);
        String preview = String.join(", ", samples.subList(0, previewSize));
        if (samples.size() > previewSize) {
            preview += ", ... (" + samples.size() + " total)";
        }
        return "[" + preview + "]";
    }

    private Path storeOptionalTrioFile(TrioAnalysisInputRequest request,
                                       MultipartFile file,
                                       Path workspace) throws IOException {
        if (request == null
                || !request.isEnabled()
                || !TrioAnalysisInputRequest.MODE_THREE_SINGLE_SAMPLE_VCFS.equals(request.getInputMode())) {
            return null;
        }
        if (file == null || file.isEmpty()) {
            return null;
        }
        VcfUploadUtils.UploadMetadata metadata = VcfUploadUtils.validateUpload(
                file,
                properties.getMaxUploadSizeBytes(),
                properties.getMaxExpandedSizeBytes()
        );
        Path storedFile = VcfUploadUtils.createStoredFile(workspace, metadata.getExtension());
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, storedFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return storedFile;
    }

    private void registerTrioInput(String id, TrioPipelineInput input) {
        if (trioPipelineInputProvider != null && input != null && input.isPresent()) {
            trioPipelineInputProvider.register(id, input);
        }
    }

    private void unregisterTrioInput(String id) {
        if (trioPipelineInputProvider != null) {
            trioPipelineInputProvider.remove(id);
        }
    }

    @Override
    public VariantAnalysisJob get(String id) {
        VariantAnalysisJob job = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant analysis job not found"));
        applyRememberedPhenotypeSummary(job);
        applyDerivedPreflightSummary(job);
        return job;
    }

    @Override
    public VariantAnalysisDiagnosticResponse diagnostics(String id) {
        VariantAnalysisJob job = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Variant analysis job not found"
                ));
        return DIAGNOSTIC_ASSEMBLER.assemble(job);
    }

    @Override
    public List<VariantAnalysisEvent> listEvents(String id) {
        get(id);
        return repository.findEvents(id);
    }

    @Override
    public VariantInterpretationCounts countInterpretations(String id) {
        get(id);
        return repository.countInterpretationViews(id);
    }

    @Override
    public VariantAnalysisVariantPageResponse listVariants(
            String id,
            Integer page,
            Integer pageSize,
            VariantInterpretationView view,
            VariantInterpretationFilter filter) {
        VariantAnalysisJob job = get(id);
        VariantInterpretationView normalizedView =
                view == null ? VariantInterpretationView.CANDIDATES : view;
        VariantInterpretationFilter normalizedFilter =
                filter == null ? VariantInterpretationFilter.empty() : filter;
        int normalizedPage = page == null || page < 1 ? DEFAULT_PAGE : page;
        int normalizedPageSize = pageSize == null || pageSize < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        VariantInterpretationCounts counts = repository.countInterpretationViews(job.getId());
        int total = normalizedFilter.isEmpty()
                ? counts.forView(normalizedView)
                : repository.countInterpretations(job.getId(), normalizedView, normalizedFilter);
        int offset = Math.min((normalizedPage - 1) * normalizedPageSize, total);
        return new VariantAnalysisVariantPageResponse(
                id,
                normalizedView,
                normalizedPage,
                normalizedPageSize,
                total,
                counts.getCandidates(),
                counts.getFrequencyFiltered(),
                counts.getAll(),
                counts,
                properties.getRanking().getCommonAlleleFrequencyThreshold(),
                repository.findInterpretationSummaries(
                        id,
                        offset,
                        normalizedPageSize,
                        normalizedView,
                        normalizedFilter
                )
        );
    }

    @Override
    public VariantAnalysisVariantPageResponse listDistinctVariants(String id, Integer page, Integer pageSize,
            VariantInterpretationView view, VariantInterpretationFilter filter) {
        get(id);
        var selectedView = view == null ? VariantInterpretationView.CANDIDATES : view;
        var selectedFilter = filter == null ? VariantInterpretationFilter.empty() : filter;
        var all = org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.summaries(repository, id,
                VariantInterpretationView.ALL, VariantInterpretationFilter.empty());
        var allGroups = org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.groups(all);
        var candidates = all.stream().filter(row -> !VariantInterpretationFilter.isFilteredCommonTier(row.getPriorityTier())
                && !"FILTERED_QC".equals(row.getPriorityTier())).toList();
        var frequency = all.stream().filter(row -> VariantInterpretationFilter.isFilteredCommonTier(row.getPriorityTier())).toList();
        var rows = selectedFilter.isEmpty() ? (selectedView == VariantInterpretationView.ALL ? all :
                selectedView == VariantInterpretationView.CANDIDATES ? candidates : frequency) :
                org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.summaries(repository, id, selectedView, selectedFilter);
        var groups = org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.groups(rows);
        int p = page == null ? 1 : Math.max(1, page), size = pageSize == null ? 50 : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int start = (int) Math.min((long) (p - 1) * size, groups.size());
        var keys = new java.util.ArrayList<>(groups.keySet()).subList(start, Math.min(start + size, groups.size()));
        var representatives = new java.util.ArrayList<VariantInterpretation>();
        var contexts = new java.util.LinkedHashMap<String, java.util.List<VariantInterpretation>>();
        for (var key : keys) {
            var representative = groups.get(key).get(0);
            representatives.add(representative);
            contexts.put(representative.getCandidateId(), allGroups.get(key));
        }
        return new VariantAnalysisVariantPageResponse(id, selectedView, p, size, groups.size(),
                org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.groups(candidates).size(),
                org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.groups(frequency).size(), allGroups.size(),
                repository.countInterpretationViews(id),
                properties.getRanking().getCommonAlleleFrequencyThreshold(), representatives).withDistinctContexts(contexts, rows.size());
    }

    @Override
    public VariantInterpretation getVariant(String id, String variantId) {
        get(id);
        try {
            return repository.findInterpretation(id, variantId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Variant interpretation not found"
                    ));
        } catch (AmbiguousVariantIdException ambiguous) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "AMBIGUOUS_VARIANT_ID: use candidateId to select one candidate row",
                    ambiguous
            );
        }
    }

    @Override
    public VariantInterpretation getCandidate(String id, String candidateId) {
        get(id);
        VariantInterpretation candidate = repository.findCandidate(id, candidateId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Candidate interpretation not found"
                ));
        if (isMigratedLegacyCandidate(candidate)
                && repository.findInterpretationsByVariantId(id, candidate.getVariantId()).size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "LEGACY_CANDIDATE_IDENTITY_UNCERTAIN: re-run the analysis to establish exact candidate rows"
            );
        }
        return candidate;
    }

    @Override
    public CandidateRankingExplanationResponse explainVariantRanking(
            String id, String variantId) {
        return RANKING_EXPLANATION_ASSEMBLER.assembleLegacy(id, getVariant(id, variantId));
    }

    @Override
    public CandidateRankingExplanationResponse explainCandidateRanking(
            String id, String candidateId) {
        return RANKING_EXPLANATION_ASSEMBLER.assemble(id, getCandidate(id, candidateId));
    }

    private boolean isMigratedLegacyCandidate(VariantInterpretation candidate) {
        return candidate != null
                && candidate.getCandidateIdentity() != null
                && "LEGACY".equals(candidate.getCandidateIdentity().getSource())
                && candidate.getCandidateId() != null
                && candidate.getCandidateId().matches("legacy-v1-[0-9]{10}");
    }

    @Override
    public PhenotypeGenePriorLookupResponse lookupPhenotypeGenePrior(String id, String gene) {
        VariantAnalysisJob job = get(id);
        PhenotypeAwareSummary summary = job.getPhenotypeSummary();
        if (summary == null || !summary.isEnabled() || summary.getValidHpoTerms().isEmpty()) {
            return PhenotypeGenePriorLookupResponse.from(
                    gene,
                    GenePhenotypePriorSummary.notGenerated(),
                    effectivePhenotypePriorTopK()
            );
        }
        GenePhenotypePriorSummary fullPriorSummary = fullGenePhenotypePriorProvider().summarize(summary);
        return PhenotypeGenePriorLookupResponse.from(gene, fullPriorSummary, effectivePhenotypePriorTopK());
    }

    @Override
    public VariantEnhancementTask submitEnhancement(String id, String provider, Integer candidateLimit) {
        VariantAnalysisJob job = get(id);
        if (job.getStatus() != VariantAnalysisStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Variant analysis must be ready before enhancement");
        }
        String normalizedProvider = provider == null ? "ALPHAGENOME" : provider.trim().toUpperCase();
        if (!"ALPHAGENOME".equals(normalizedProvider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only ALPHAGENOME enhancement is supported");
        }
        int defaultLimit = properties.getAlphaGenome().getDefaultCandidateLimit();
        int maxLimit = properties.getAlphaGenome().getMaxCandidateLimit();
        int normalizedLimit = candidateLimit == null || candidateLimit < 1
                ? defaultLimit
                : Math.min(candidateLimit, maxLimit);
        VariantEnhancementTask task = new VariantEnhancementTask(
                UUID.randomUUID().toString(),
                id,
                normalizedProvider,
                normalizedLimit
        );
        enhancementRepository.save(task);
        enhancementProcessor.process(task.getId());
        return task;
    }

    @Override
    public VariantEnhancementTask getEnhancement(String id, String enhancementId) {
        get(id);
        VariantEnhancementTask task = enhancementRepository.findById(enhancementId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Variant enhancement task not found"));
        if (!id.equals(task.getAnalysisId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant enhancement task not found");
        }
        return task;
    }

    @Override
    public void delete(String id) throws IOException {
        Optional<VariantAnalysisJob> existingJob = repository.findById(id);
        if (!existingJob.isPresent()) {
            phenotypeSummaries.remove(id);
            return;
        }
        VariantAnalysisJob job = existingJob.get();
        applyRememberedPhenotypeSummary(job);
        applyDerivedPreflightSummary(job);
        if (job.getStatus() != VariantAnalysisStatus.READY
                && job.getStatus() != VariantAnalysisStatus.FAILED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Active variant analysis jobs cannot be deleted"
            );
        }
        repository.deleteById(job.getId());
        phenotypeSummaries.remove(job.getId());
        deleteWorkspaceStrict(job.getWorkspace());
    }

    private void rememberPhenotypeSummary(String id, PhenotypeAwareSummary summary) {
        if (summary != null && summary.hasAnyPhenotypeInput()) {
            phenotypeSummaries.put(id, summary);
        } else {
            phenotypeSummaries.remove(id);
        }
    }

    private void applyRememberedPhenotypeSummary(VariantAnalysisJob job) {
        if (job == null || job.getId() == null) {
            return;
        }
        PhenotypeAwareSummary summary = phenotypeSummaries.get(job.getId());
        if (summary != null) {
            job.applyPhenotypeSummary(summary);
        }
    }

    private void applyDerivedPreflightSummary(VariantAnalysisJob job) {
        if (job == null || job.getPreflightSummary() != null || job.getQcSummary() == null) {
            return;
        }
        job.applyPreflightSummary(VcfPreflightSummary.fromQcSummary(
                job.getQcSummary(),
                job.getSelectedSampleId(),
                properties.getLargeFile()
        ));
    }

    private GenePhenotypePriorProvider fullGenePhenotypePriorProvider() {
        VariantAnalysisProperties.PhenotypePrior source = properties.getPhenotypePrior();
        VariantAnalysisProperties.PhenotypePrior copy = new VariantAnalysisProperties.PhenotypePrior();
        copy.setEnabled(source.isEnabled());
        copy.setSource(source.getSource());
        copy.setVersion(source.getVersion());
        copy.setMaxGenes(0);
        copy.setFailOnMalformedRow(source.isFailOnMalformedRow());
        copy.setRequireMatchingHpo(source.isRequireMatchingHpo());
        copy.setFormat(source.getFormat());
        copy.setLocalFile(source.getLocalFile() == null ? null : source.getLocalFile().toString());
        copy.setManifestFile(source.getManifestFile() == null ? null : source.getManifestFile().toString());
        return GenePhenotypePriorProviderFactory.create(phenotypeProperties(copy));
    }

    private VariantAnalysisProperties phenotypeProperties(VariantAnalysisProperties.PhenotypePrior phenotypePrior) {
        VariantAnalysisProperties copy = new VariantAnalysisProperties();
        copy.getPhenotypePrior().setEnabled(phenotypePrior.isEnabled());
        copy.getPhenotypePrior().setSource(phenotypePrior.getSource());
        copy.getPhenotypePrior().setVersion(phenotypePrior.getVersion());
        copy.getPhenotypePrior().setMaxGenes(phenotypePrior.getMaxGenes());
        copy.getPhenotypePrior().setFailOnMalformedRow(phenotypePrior.isFailOnMalformedRow());
        copy.getPhenotypePrior().setRequireMatchingHpo(phenotypePrior.isRequireMatchingHpo());
        copy.getPhenotypePrior().setFormat(phenotypePrior.getFormat());
        copy.getPhenotypePrior().setLocalFile(
                phenotypePrior.getLocalFile() == null ? null : phenotypePrior.getLocalFile().toString()
        );
        copy.getPhenotypePrior().setManifestFile(
                phenotypePrior.getManifestFile() == null ? null : phenotypePrior.getManifestFile().toString()
        );
        return copy;
    }

    private int effectivePhenotypePriorTopK() {
        int configured = properties.getPhenotypePrior().normalizedMaxGenes();
        return configured <= 0 ? properties.getLargeFile().normalizedPhenotypePriorRescueMaxGenes() : configured;
    }

    private Path createWorkspace(String id) throws IOException {
        Path root = properties.getStorageRoot().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        Path workspace = realRoot.resolve(id).normalize();
        if (!workspace.startsWith(realRoot) || !realRoot.equals(workspace.getParent())) {
            throw new IllegalStateException("Variant analysis workspace escapes the configured storage root");
        }
        Files.createDirectories(workspace);
        return workspace.toRealPath();
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return;
        }
        try {
            try (Stream<Path> paths = Files.walk(workspace)) {
                paths.sorted((left, right) -> right.compareTo(left))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                                // Best-effort cleanup after a failed upload.
                            }
                        });
            }
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed upload.
        }
    }

    private void deleteWorkspaceStrict(Path workspace) throws IOException {
        Path root = properties.getStorageRoot().toAbsolutePath().normalize();
        if (!Files.exists(root) || workspace == null || !Files.exists(workspace)) {
            return;
        }
        Path realRoot = root.toRealPath();
        Path realWorkspace = workspace.toRealPath();
        if (!realWorkspace.startsWith(realRoot) || !realRoot.equals(realWorkspace.getParent())) {
            throw new IOException("Variant analysis workspace escapes the configured storage root");
        }
        try {
            try (Stream<Path> paths = Files.walk(realWorkspace)) {
                paths.sorted((left, right) -> right.compareTo(left))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ex) {
                                throw new WorkspaceDeletionException(ex);
                            }
                        });
            }
        } catch (WorkspaceDeletionException ex) {
            throw (IOException) ex.getCause();
        }
    }

    private String sha256(Path file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file)) {
            return sha256(inputStream);
        }
    }

    private String sha256(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return sha256(inputStream);
        }
    }

    private String sha256(InputStream inputStream) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static class WorkspaceDeletionException extends RuntimeException {

        private WorkspaceDeletionException(IOException cause) {
            super(cause);
        }
    }

}
