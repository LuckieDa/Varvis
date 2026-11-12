package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.ExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.ProcessResult;
import org.nbscn.varvis.variantanalysis.provider.VepOwnedProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.VepCommandBuilder;
import org.nbscn.varvis.variantanalysis.provider.VepCommandOptions;
import org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser;
import org.nbscn.varvis.variantanalysis.provider.VepParseResult;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfile;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfileResolution;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfileResolver;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VepTranscriptAnnotationEnrichmentService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VepTranscriptAnnotationEnrichmentService.class);
    private static final Pattern VEP_LIFECYCLE_EVENT_PATTERN =
            Pattern.compile("^VEP transcript annotation ([A-Z_]+)(?::.*)?$");
    private static final Pattern VEP_PENDING_SELECTED_EVENT_PATTERN =
            Pattern.compile("^VEP transcript annotation PENDING: selected (\\d+) top candidates.*$");

    private final VariantAnalysisJobRepository repository;
    private final VariantAnalysisProperties properties;
    private final VepResourceValidator resourceValidator;
    private final VepCommandBuilder commandBuilder;
    private final VepJsonOutputParser outputParser;
    private final ExternalProcessRunner processRunner;
    private final TranscriptAnnotationService transcriptAnnotationService;
    private final VepResourceProfileResolver profileResolver;

    public VepTranscriptAnnotationEnrichmentService(VariantAnalysisJobRepository repository,
                                                    VariantAnalysisProperties properties,
                                                    VepResourceValidator resourceValidator,
                                                    VepCommandBuilder commandBuilder,
                                                    VepJsonOutputParser outputParser,
                                                    ExternalProcessRunner processRunner,
                                                    TranscriptAnnotationService transcriptAnnotationService) {
        this.repository = repository;
        this.properties = properties;
        this.resourceValidator = resourceValidator;
        this.commandBuilder = commandBuilder;
        this.outputParser = outputParser;
        this.processRunner = VepOwnedProcessRunner.select(properties, processRunner);
        this.transcriptAnnotationService = transcriptAnnotationService;
        this.profileResolver = new VepResourceProfileResolver(properties);
    }

    @Async("variantAnalysisExecutor")
    public void process(String analysisId) {
        enrichNow(analysisId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedEnrichment() {
        for (VariantAnalysisJob job : repository.findAll()) {
            if (job == null || job.getStatus() != VariantAnalysisStatus.READY) {
                continue;
            }
            InterruptedEnrichment interrupted = interruptedEnrichment(repository.findEvents(job.getId()));
            if (interrupted == null) {
                continue;
            }
            String warning = "VEP transcript enrichment was interrupted by server restart; "
                    + "base analysis results remain available.";
            appendSummaryEvent(job.getId(), new VepEnrichmentSummary(
                    "UNAVAILABLE",
                    interrupted.selectedCount,
                    0,
                    0,
                    interrupted.selectedCount,
                    0,
                    0,
                    Collections.singletonList(warning)
            ));
            LOGGER.warn("Recovered interrupted VEP transcript enrichment for analysis job {}", job.getId());
        }
    }

    public VepEnrichmentSummary enrichNow(String analysisId) {
        long started = System.nanoTime();
        VariantAnalysisJob job = repository.findById(analysisId).orElse(null);
        if (job == null || job.getStatus() != VariantAnalysisStatus.READY) {
            return VepEnrichmentSummary.notRequested();
        }
        VepReadinessReport readiness = resourceValidator.validate(job.getGenomeAssembly());
        if (!readiness.isEnabled()) {
            VepEnrichmentSummary summary = VepEnrichmentSummary.skipped(
                    "DISABLED",
                    "VEP transcript annotation is disabled; ANN fallback remains active."
            );
            appendSummaryEvent(analysisId, summary);
            return summary;
        }
        if (!readiness.isExecutionEnabled()) {
            VepEnrichmentSummary summary = VepEnrichmentSummary.skipped(
                    "UNAVAILABLE",
                    "VEP execution is disabled; ANN fallback remains active."
            );
            appendSummaryEvent(analysisId, summary);
            return summary;
        }
        if (!"READY".equals(readiness.getStatus())) {
            VepEnrichmentSummary summary = VepEnrichmentSummary.skipped(
                    "UNAVAILABLE",
                    readiness.getExplanation()
            );
            appendSummaryEvent(analysisId, summary);
            return summary;
        }

        int maxCandidates = normalizedMaxCandidates();
        int batchSize = normalizedBatchSize(maxCandidates);
        List<VariantInterpretation> candidates = repository.findInterpretationSummaries(
                analysisId,
                0,
                maxCandidates,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        repository.appendEvent(
                analysisId,
                VariantAnalysisStatus.READY,
                "VEP transcript annotation PENDING: selected " + candidates.size()
                        + " top candidates, batchSize=" + batchSize
        );
        if (candidates.isEmpty()) {
            VepEnrichmentSummary summary = new VepEnrichmentSummary(
                    "COMPLETED",
                    0,
                    0,
                    0,
                    0,
                    0,
                    elapsedMillis(started),
                    Collections.emptyList()
            );
            appendSummaryEvent(analysisId, summary);
            return summary;
        }

        repository.appendEvent(
                analysisId,
                VariantAnalysisStatus.READY,
                "VEP transcript annotation RUNNING"
        );
        try {
            VepAnnotationResult result = annotateCandidates(
                    candidates,
                    "VEP_COMPUTED",
                    "VEP_CACHED",
                    "VEP_COMPUTED",
                    job.getGenomeAssembly()
            );
            repository.updateTranscriptAnnotations(analysisId, result.getAnnotationsByVariantId());
            VepEnrichmentSummary summary = result.getSummary();
            appendSummaryEvent(analysisId, summary);
            LOGGER.info(
                    "VEP transcript annotation job {} completed: status={}, selected={}, success={}, failed={}, cacheHit={}, elapsedMs={}",
                    analysisId,
                    summary.getStatus(),
                    summary.getSelectedCount(),
                    summary.getSuccessCount(),
                    summary.getFailedCount(),
                    summary.getCacheHitCount(),
                    summary.getElapsedMillis()
            );
            return summary;
        } catch (RuntimeException ex) {
            String warning = "VEP transcript enrichment failed unexpectedly: " + safeMessage(ex);
            VepEnrichmentSummary summary = new VepEnrichmentSummary(
                    "FAILED",
                    candidates.size(),
                    0,
                    0,
                    candidates.size(),
                    0,
                    elapsedMillis(started),
                    Collections.singletonList(warning)
            );
            appendSummaryEvent(analysisId, summary);
            LOGGER.warn("VEP transcript annotation job {} failed unexpectedly: {}", analysisId, safeMessage(ex));
            return summary;
        }
    }

    public VepAnnotationResult annotateCandidates(List<VariantInterpretation> candidates,
                                                  String computedSource,
                                                  String cachedSource,
                                                  String placeholderSource) {
        return annotateCandidates(
                candidates,
                computedSource,
                cachedSource,
                placeholderSource,
                "FAILED",
                "FAILED",
                GenomeAssembly.GRCh38
        );
    }

    public VepAnnotationResult annotateCandidates(List<VariantInterpretation> candidates,
                                                  String computedSource,
                                                  String cachedSource,
                                                  String placeholderSource,
                                                  GenomeAssembly assembly) {
        return annotateCandidates(
                candidates,
                computedSource,
                cachedSource,
                placeholderSource,
                "FAILED",
                "FAILED",
                assembly
        );
    }

    public VepAnnotationResult annotateCandidates(List<VariantInterpretation> candidates,
                                                  String computedSource,
                                                  String cachedSource,
                                                  String placeholderSource,
                                                  String failureStatus,
                                                  String timeoutStatus) {
        return annotateCandidates(
                candidates,
                computedSource,
                cachedSource,
                placeholderSource,
                failureStatus,
                timeoutStatus,
                GenomeAssembly.GRCh38
        );
    }

    public VepAnnotationResult annotateCandidates(List<VariantInterpretation> candidates,
                                                  String computedSource,
                                                  String cachedSource,
                                                  String placeholderSource,
                                                  String failureStatus,
                                                  String timeoutStatus,
                                                  GenomeAssembly assembly) {
        return annotateCandidates(
                candidates,
                computedSource,
                cachedSource,
                placeholderSource,
                failureStatus,
                timeoutStatus,
                normalizedBatchSize(candidates == null ? 0 : candidates.size()),
                Duration.ofSeconds(Math.max(1, properties.getVep().getTimeoutSeconds())),
                properties.getVep().getTempDir(),
                assembly
        );
    }

    public VepAnnotationResult annotateCandidates(List<VariantInterpretation> candidates,
                                                  String computedSource,
                                                  String cachedSource,
                                                  String placeholderSource,
                                                  String failureStatus,
                                                  String timeoutStatus,
                                                  int requestedBatchSize,
                                                  Duration timeout,
                                                  Path tempDir) {
        return annotateCandidates(
                candidates,
                computedSource,
                cachedSource,
                placeholderSource,
                failureStatus,
                timeoutStatus,
                requestedBatchSize,
                timeout,
                tempDir,
                GenomeAssembly.GRCh38
        );
    }

    public VepAnnotationResult annotateCandidates(List<VariantInterpretation> candidates,
                                                  String computedSource,
                                                  String cachedSource,
                                                  String placeholderSource,
                                                  String failureStatus,
                                                  String timeoutStatus,
                                                  int requestedBatchSize,
                                                  Duration timeout,
                                                  Path tempDir,
                                                  GenomeAssembly assembly) {
        long started = System.nanoTime();
        if (candidates == null || candidates.isEmpty()) {
            VepEnrichmentSummary summary = new VepEnrichmentSummary(
                    "COMPLETED",
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    elapsedMillis(started),
                    Collections.emptyList()
            );
            return new VepAnnotationResult(Collections.emptyMap(), summary);
        }
        VepResourceProfileResolution resolution = profileResolver.resolve(assembly);
        if (!resolution.isResolved()) {
            String message = "VEP resource profile is unavailable for "
                    + (assembly == null ? "unknown" : assembly.name()) + ": " + resolution.getReason();
            Map<String, List<TranscriptConsequence>> placeholders =
                    placeholders(candidates, "ASSEMBLY_UNSUPPORTED".equals(resolution.getStatus())
                            ? "ASSEMBLY_UNSUPPORTED"
                            : failureStatus, message, placeholderSource);
            VepEnrichmentSummary summary = new VepEnrichmentSummary(
                    resolution.getStatus(),
                    candidates.size(),
                    0,
                    0,
                    candidates.size(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    candidates.size(),
                    0,
                    elapsedMillis(started),
                    Collections.singletonList(message)
            );
            return new VepAnnotationResult(placeholders, summary);
        }
        VepReadinessReport readiness = resourceValidator.validate(assembly);
        if (!readyForExecution(readiness)) {
            String message = readiness == null
                    ? "VEP resource readiness could not be evaluated."
                    : readiness.getExplanation();
            String status = readiness != null && "ASSEMBLY_UNSUPPORTED".equals(readiness.getStatus())
                    ? "ASSEMBLY_UNSUPPORTED"
                    : "UNAVAILABLE";
            Map<String, List<TranscriptConsequence>> placeholders =
                    placeholders(candidates, status, message, placeholderSource);
            VepEnrichmentSummary summary = new VepEnrichmentSummary(
                    status,
                    candidates.size(),
                    0,
                    0,
                    candidates.size(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    candidates.size(),
                    0,
                    elapsedMillis(started),
                    Collections.singletonList(message)
            );
            return new VepAnnotationResult(placeholders, summary);
        }
        VepResourceProfile profile = resolution.getProfile();
        int batchSize = normalizedBatchSize(requestedBatchSize, candidates.size());
        Map<String, List<TranscriptConsequence>> annotationsByVariantId =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        Map<TranscriptAnnotationCacheKey, CandidateGroup> groups =
                new LinkedHashMap<TranscriptAnnotationCacheKey, CandidateGroup>();
        for (VariantInterpretation candidate : candidates) {
            TranscriptAnnotationCacheKey key = transcriptAnnotationService.cacheKey(candidate);
            CandidateGroup group = groups.get(key);
            if (group == null) {
                group = new CandidateGroup(candidate);
                groups.put(key, group);
            }
            group.add(candidate);
        }
        List<CandidateGroup> uncached = new ArrayList<CandidateGroup>();
        int cacheHitCount = 0;
        int cacheMissCount = 0;
        int successCount = 0;
        int unavailableCount = 0;
        int annotatedAvailableCount = 0;
        for (CandidateGroup group : groups.values()) {
            List<TranscriptConsequence> cached = transcriptAnnotationService.cached(group.representative);
            if (cached != null && !cached.isEmpty()) {
                List<TranscriptConsequence> cachedWithMetadata = withMetadata(cached, cachedSource);
                for (VariantInterpretation candidate : group.members) {
                    annotationsByVariantId.put(candidate.getVariantId(), cachedWithMetadata);
                }
                cacheHitCount += group.members.size();
                successCount += group.members.size();
                if (availableTranscriptResult(cachedWithMetadata)) {
                    annotatedAvailableCount += group.members.size();
                } else {
                    unavailableCount += group.members.size();
                }
            } else {
                cacheMissCount++;
                uncached.add(group);
            }
        }

        List<String> warnings = new ArrayList<String>();
        int failedCount = 0;
        int providerBatchCount = 0;
        int providerTimeoutCount = 0;
        int providerErrorCount = 0;
        for (int start = 0; start < uncached.size(); start += batchSize) {
            List<CandidateGroup> groupBatch = uncached.subList(
                    start,
                    Math.min(start + batchSize, uncached.size())
            );
            List<VariantInterpretation> batch = representatives(groupBatch);
            providerBatchCount++;
            BatchResult batchResult = runBatch(
                    batch,
                    computedSource,
                    placeholderSource,
                    failureStatus,
                    timeoutStatus,
                    timeout,
                    tempDir,
                    profile
            );
            warnings.addAll(batchResult.warnings);
            if (batchResult.timedOut) {
                providerTimeoutCount++;
            } else if (batchResult.providerError) {
                providerErrorCount++;
            }
            for (Map.Entry<String, List<TranscriptConsequence>> entry
                    : batchResult.annotationsByVariantId.entrySet()) {
                CandidateGroup group = findGroupByVariantId(groupBatch, entry.getKey());
                if (group == null) {
                    warnings.add("VEP returned an unmapped candidate ID: " + entry.getKey());
                    continue;
                }
                List<TranscriptConsequence> annotations = entry.getValue();
                for (VariantInterpretation candidate : group.members) {
                    annotationsByVariantId.put(candidate.getVariantId(), annotations);
                }
                if (successfulTranscriptResult(annotations)) {
                    transcriptAnnotationService.cache(group.representative, annotations);
                    successCount += group.members.size();
                    if (availableTranscriptResult(annotations)) {
                        annotatedAvailableCount += group.members.size();
                    } else {
                        unavailableCount += group.members.size();
                    }
                } else {
                    failedCount += group.members.size();
                    unavailableCount += group.members.size();
                }
            }
        }
        VepEnrichmentSummary summary = new VepEnrichmentSummary(
                status(candidates.size(), successCount, failedCount),
                candidates.size(),
                successCount + failedCount,
                successCount,
                failedCount,
                cacheHitCount,
                cacheMissCount,
                providerBatchCount,
                providerTimeoutCount,
                providerErrorCount,
                unavailableCount,
                annotatedAvailableCount,
                elapsedMillis(started),
                warnings
        );
        return new VepAnnotationResult(annotationsByVariantId, summary);
    }

    private BatchResult runBatch(List<VariantInterpretation> batch,
                                 String computedSource,
                                 String placeholderSource,
                                 String failureStatus,
                                 String timeoutStatus,
                                 Duration timeout,
                                 Path tempDir,
                                 VepResourceProfile profile) {
        Path workspace = null;
        try {
            workspace = createWorkspace("varvis-vep-batch-", tempDir);
            Path input = workspace.resolve("input.vcf");
            Path output = workspace.resolve("output.json");
            writeBatchVcf(batch, input);
            List<String> command = commandBuilder.build(new VepCommandOptions(input, output), profile);
            LOGGER.debug(
                    "Running VEP batch for {} candidates with command {}",
                    batch.size(),
                    sanitizeCommand(command)
            );
            ProcessResult result = processRunner.run(
                    command,
                    workspace,
                    normalizedTimeout(timeout)
            );
            if (!result.isSuccess()) {
                String message = result.isTimedOut()
                        ? "VEP command timed out."
                        : "VEP command failed with exit code " + result.getExitCode()
                                + (result.getExecutionReceipt().isEmpty() ? "" : ": " + result.getExecutionReceipt().get("failure"));
                return BatchResult.failed(
                        placeholders(
                                batch,
                                result.isTimedOut() ? timeoutStatus : failureStatus,
                                message,
                                placeholderSource,
                                profile
                        ),
                        message,
                        result.isTimedOut()
                );
            }
            VepParseResult parseResult =
                    outputParser.parseByVariantId(output, computedSource, profile.getVersion());
            Map<String, List<TranscriptConsequence>> annotationsByVariantId =
                    new LinkedHashMap<String, List<TranscriptConsequence>>(
                            parseResult.getAnnotationsByVariantId()
                    );
            for (VariantInterpretation candidate : batch) {
                List<TranscriptConsequence> annotations =
                        annotationsByVariantId.get(candidate.getVariantId());
                if (annotations == null || annotations.isEmpty()) {
                    annotationsByVariantId.put(candidate.getVariantId(), Collections.singletonList(
                            placeholder(
                                    "VEP completed but returned no transcript consequence for this variant.",
                                    "NO_RECORD",
                                    placeholderSource,
                                    profile
                            )
                    ));
                }
            }
            List<String> warnings = new ArrayList<String>(parseResult.getWarnings());
            if (!result.getExecutionReceipt().isEmpty()) warnings.add("VEP execution metadata: "
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.getExecutionReceipt()));
            return new BatchResult(annotationsByVariantId, warnings, false, false);
        } catch (IOException ex) {
            String message = "VEP execution path failed: " + safeMessage(ex);
            return BatchResult.failed(
                    placeholders(batch, failureStatus, message, placeholderSource, profile),
                    message,
                    false
            );
        } finally {
            cleanup(workspace);
        }
    }

    private Path createWorkspace(String prefix, Path tempDir) throws IOException {
        if (tempDir == null) {
            return Files.createTempDirectory(prefix);
        }
        Files.createDirectories(tempDir);
        return Files.createTempDirectory(tempDir, prefix);
    }

    private void writeBatchVcf(List<VariantInterpretation> batch, Path input) throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add("##fileformat=VCFv4.2");
        lines.add("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO");
        for (VariantInterpretation candidate : batch) {
            NormalizedVariantKey key = candidate.getObservation() == null
                    ? null
                    : candidate.getObservation().getKey();
            if (key == null) {
                continue;
            }
            lines.add(vcfChromosome(key.getChromosome())
                    + "\t" + key.getPosition()
                    + "\t" + candidate.getVariantId()
                    + "\t" + key.getReference()
                    + "\t" + key.getAlternate()
                    + "\t.\tPASS\t.");
        }
        Files.write(input, lines, StandardCharsets.UTF_8);
    }

    private String vcfChromosome(String chromosome) {
        if (chromosome == null || chromosome.toLowerCase().startsWith("chr")) {
            return chromosome;
        }
        return "chr" + chromosome;
    }

    private List<TranscriptConsequence> withMetadata(List<TranscriptConsequence> annotations, String source) {
        if (annotations == null || annotations.isEmpty()) {
            return Collections.emptyList();
        }
        List<TranscriptConsequence> result = new ArrayList<TranscriptConsequence>(annotations.size());
        Instant timestamp = Instant.now();
        for (TranscriptConsequence annotation : annotations) {
            String status = annotation.getStatus() == null ? "COMPLETED" : annotation.getStatus();
            result.add(annotation.withAnnotationMetadata(
                    source,
                    status,
                    annotation.getVersion(),
                    timestamp
            ));
        }
        return result;
    }

    private boolean successfulTranscriptResult(List<TranscriptConsequence> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return false;
        }
        for (TranscriptConsequence annotation : annotations) {
            String status = annotation == null ? null : annotation.getStatus();
            if ("COMPLETED".equals(status) || "NO_RECORD".equals(status)) {
                return true;
            }
        }
        return false;
    }

    private boolean availableTranscriptResult(List<TranscriptConsequence> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return false;
        }
        for (TranscriptConsequence annotation : annotations) {
            if (annotation != null
                    && ("COMPLETED".equals(annotation.getStatus()) || "AVAILABLE".equals(annotation.getStatus()))
                    && hasText(annotation.getGeneSymbol())) {
                return true;
            }
        }
        return false;
    }

    private List<VariantInterpretation> representatives(List<CandidateGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Collections.emptyList();
        }
        List<VariantInterpretation> result = new ArrayList<VariantInterpretation>(groups.size());
        for (CandidateGroup group : groups) {
            result.add(group.representative);
        }
        return result;
    }

    private CandidateGroup findGroupByVariantId(List<CandidateGroup> groups, String variantId) {
        if (groups == null || variantId == null) {
            return null;
        }
        for (CandidateGroup group : groups) {
            if (group.representative.getVariantId().equals(variantId)) {
                return group;
            }
        }
        return null;
    }

    private Map<String, List<TranscriptConsequence>> placeholders(List<VariantInterpretation> batch,
                                                                  String status,
                                                                  String summary,
                                                                  String source) {
        return placeholders(batch, status, summary, source, null);
    }

    private Map<String, List<TranscriptConsequence>> placeholders(List<VariantInterpretation> batch,
                                                                  String status,
                                                                  String summary,
                                                                  String source,
                                                                  VepResourceProfile profile) {
        Map<String, List<TranscriptConsequence>> result =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        if (batch == null || batch.isEmpty()) {
            return result;
        }
        for (VariantInterpretation candidate : batch) {
            result.put(candidate.getVariantId(), Collections.singletonList(placeholder(summary, status, source, profile)));
        }
        return result;
    }

    private TranscriptConsequence placeholder(String summary,
                                              String status,
                                              String source,
                                              VepResourceProfile profile) {
        return TranscriptConsequence.statusPlaceholder(
                summary,
                source,
                status,
                profile == null ? properties.getVep().getVersion() : profile.getVersion(),
                Instant.now()
        );
    }

    private String status(int selectedCount, int successCount, int failedCount) {
        if (selectedCount == 0) {
            return "COMPLETED";
        }
        if (successCount == selectedCount && failedCount == 0) {
            return "COMPLETED";
        }
        if (successCount > 0) {
            return "PARTIAL";
        }
        return failedCount > 0 ? "FAILED" : "PENDING";
    }

    private int normalizedMaxCandidates() {
        return Math.max(1, properties.getVep().getMaxCandidates());
    }

    private int normalizedBatchSize(int maxCandidates) {
        int batchSize = Math.max(1, properties.getVep().getBatchSize());
        return Math.min(batchSize, Math.max(1, maxCandidates));
    }

    private int normalizedBatchSize(int requestedBatchSize, int maxCandidates) {
        int batchSize = Math.max(1, requestedBatchSize);
        return Math.min(batchSize, Math.max(1, maxCandidates));
    }

    private Duration normalizedTimeout(Duration timeout) {
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            return timeout;
        }
        return Duration.ofSeconds(Math.max(1, properties.getVep().getTimeoutSeconds()));
    }

    private boolean readyForExecution(VepReadinessReport readiness) {
        return readiness != null
                && readiness.isEnabled()
                && readiness.isExecutionEnabled()
                && "READY".equals(readiness.getStatus());
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    void appendSummaryEvent(String analysisId, VepEnrichmentSummary summary) {
        for (String warning : summary.getWarnings()) if (warning.startsWith("VEP execution metadata: "))
            repository.appendEvent(analysisId, VariantAnalysisStatus.READY, warning);
        repository.appendEvent(
                analysisId,
                VariantAnalysisStatus.READY,
                "VEP transcript annotation " + summary.getStatus()
                        + ": selected=" + summary.getSelectedCount()
                        + ", processed=" + summary.getProcessedCount()
                        + ", success=" + summary.getSuccessCount()
                        + ", failed=" + summary.getFailedCount()
                        + ", cacheHit=" + summary.getCacheHitCount()
                        + ", cacheMiss=" + summary.getCacheMissCount()
                        + ", batches=" + summary.getProviderBatchCount()
                        + ", timeouts=" + summary.getProviderTimeoutCount()
                        + ", providerErrors=" + summary.getProviderErrorCount()
                        + ", unavailable=" + summary.getUnavailableCount()
                        + ", annotatedAvailable=" + summary.getAnnotatedAvailableCount()
                        + ", elapsedMs=" + summary.getElapsedMillis()
                        + (summary.getWarnings().isEmpty()
                        ? ""
                        : ", warnings=" + summary.getWarnings().size())
        );
    }

    private InterruptedEnrichment interruptedEnrichment(List<org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent> events) {
        String latestStatus = null;
        int selectedCount = 0;
        if (events != null) {
            for (org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent event : events) {
                String message = event == null || event.getMessage() == null ? "" : event.getMessage();
                Matcher matcher = VEP_LIFECYCLE_EVENT_PATTERN.matcher(message);
                if (!matcher.matches()) {
                    continue;
                }
                latestStatus = matcher.group(1);
                Matcher pendingMatcher = VEP_PENDING_SELECTED_EVENT_PATTERN.matcher(message);
                if (pendingMatcher.matches()) {
                    selectedCount = Integer.parseInt(pendingMatcher.group(1));
                }
            }
        }
        if (!"PENDING".equals(latestStatus) && !"RUNNING".equals(latestStatus)) {
            return null;
        }
        return new InterruptedEnrichment(selectedCount);
    }

    private static class InterruptedEnrichment {

        private final int selectedCount;

        private InterruptedEnrichment(int selectedCount) {
            this.selectedCount = selectedCount;
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)[a-z]:\\\\[^\\s,;]+", "[local-path-redacted]");
    }

    private void cleanup(Path workspace) {
        if (workspace == null) {
            return;
        }
        if (!properties.getVep().isCleanupTemp() || VepOwnedProcessRunner.preserveWorkspace(properties)) {
            LOGGER.info("Keeping VEP temporary workspace for inspection: {}", workspace);
            return;
        }
        try {
            Files.deleteIfExists(workspace.resolve("input.vcf"));
            Files.deleteIfExists(workspace.resolve("output.json"));
            Files.deleteIfExists(workspace);
        } catch (IOException ignored) {
            // Best-effort cleanup; temp VEP batch workspaces are not part of the repository.
        }
    }

    private List<String> sanitizeCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sanitized = new ArrayList<String>(command.size());
        for (String token : command) {
            sanitized.add(sanitizeText(token));
        }
        return sanitized;
    }

    private String sanitizeText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        return text.replaceAll("(?i)[a-z]:\\\\[^\\s,;]+", "[local-path-redacted]");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static class CandidateGroup {

        private final VariantInterpretation representative;
        private final List<VariantInterpretation> members = new ArrayList<VariantInterpretation>();

        private CandidateGroup(VariantInterpretation representative) {
            this.representative = representative;
        }

        private void add(VariantInterpretation candidate) {
            members.add(candidate);
        }
    }

    public static class VepEnrichmentSummary {

        private final String status;
        private final int selectedCount;
        private final int processedCount;
        private final int successCount;
        private final int failedCount;
        private final int cacheHitCount;
        private final int cacheMissCount;
        private final int providerBatchCount;
        private final int providerTimeoutCount;
        private final int providerErrorCount;
        private final int unavailableCount;
        private final int annotatedAvailableCount;
        private final long elapsedMillis;
        private final List<String> warnings;

        public VepEnrichmentSummary(String status,
                                    int selectedCount,
                                    int processedCount,
                                    int successCount,
                                    int failedCount,
                                    int cacheHitCount,
                                    long elapsedMillis,
                                    List<String> warnings) {
            this(
                    status,
                    selectedCount,
                    processedCount,
                    successCount,
                    failedCount,
                    cacheHitCount,
                    0,
                    0,
                    0,
                    0,
                    Math.max(0, selectedCount - successCount),
                    successCount,
                    elapsedMillis,
                    warnings
            );
        }

        public VepEnrichmentSummary(String status,
                                    int selectedCount,
                                    int processedCount,
                                    int successCount,
                                    int failedCount,
                                    int cacheHitCount,
                                    int cacheMissCount,
                                    int providerBatchCount,
                                    int providerTimeoutCount,
                                    int providerErrorCount,
                                    int unavailableCount,
                                    int annotatedAvailableCount,
                                    long elapsedMillis,
                                    List<String> warnings) {
            this.status = status;
            this.selectedCount = selectedCount;
            this.processedCount = processedCount;
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.cacheHitCount = cacheHitCount;
            this.cacheMissCount = cacheMissCount;
            this.providerBatchCount = providerBatchCount;
            this.providerTimeoutCount = providerTimeoutCount;
            this.providerErrorCount = providerErrorCount;
            this.unavailableCount = unavailableCount;
            this.annotatedAvailableCount = annotatedAvailableCount;
            this.elapsedMillis = elapsedMillis;
            this.warnings = warnings == null || warnings.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(warnings));
        }

        public static VepEnrichmentSummary notRequested() {
            return skipped("NOT_REQUESTED", "Variant analysis is not ready.");
        }

        public static VepEnrichmentSummary skipped(String status, String warning) {
            return new VepEnrichmentSummary(
                    status,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    warning == null ? Collections.emptyList() : Collections.singletonList(warning)
            );
        }

        public String getStatus() {
            return status;
        }

        public int getSelectedCount() {
            return selectedCount;
        }

        public int getProcessedCount() {
            return processedCount;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public int getCacheHitCount() {
            return cacheHitCount;
        }

        public int getCacheMissCount() {
            return cacheMissCount;
        }

        public int getProviderBatchCount() {
            return providerBatchCount;
        }

        public int getProviderTimeoutCount() {
            return providerTimeoutCount;
        }

        public int getProviderErrorCount() {
            return providerErrorCount;
        }

        public int getUnavailableCount() {
            return unavailableCount;
        }

        public int getAnnotatedAvailableCount() {
            return annotatedAvailableCount;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    public static class VepAnnotationResult {

        private final Map<String, List<TranscriptConsequence>> annotationsByVariantId;
        private final VepEnrichmentSummary summary;

        public VepAnnotationResult(Map<String, List<TranscriptConsequence>> annotationsByVariantId,
                                   VepEnrichmentSummary summary) {
            this.annotationsByVariantId = annotationsByVariantId == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, List<TranscriptConsequence>>(
                    annotationsByVariantId
            ));
            this.summary = summary;
        }

        public Map<String, List<TranscriptConsequence>> getAnnotationsByVariantId() {
            return annotationsByVariantId;
        }

        public VepEnrichmentSummary getSummary() {
            return summary;
        }
    }

    private static class BatchResult {

        private final Map<String, List<TranscriptConsequence>> annotationsByVariantId;
        private final List<String> warnings;
        private final boolean timedOut;
        private final boolean providerError;

        private BatchResult(Map<String, List<TranscriptConsequence>> annotationsByVariantId,
                            List<String> warnings,
                            boolean timedOut,
                            boolean providerError) {
            this.annotationsByVariantId = annotationsByVariantId == null
                    ? Collections.emptyMap()
                    : annotationsByVariantId;
            this.warnings = warnings == null ? Collections.emptyList() : warnings;
            this.timedOut = timedOut;
            this.providerError = providerError;
        }

        private static BatchResult failed(Map<String, List<TranscriptConsequence>> annotationsByVariantId,
                                          String warning,
                                          boolean timedOut) {
            return new BatchResult(
                    annotationsByVariantId,
                    warning == null ? Collections.emptyList() : Collections.singletonList(warning),
                    timedOut,
                    !timedOut
            );
        }
    }
}
