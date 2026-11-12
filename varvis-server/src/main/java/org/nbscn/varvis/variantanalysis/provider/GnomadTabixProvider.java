package org.nbscn.varvis.variantanalysis.provider;

import htsjdk.tribble.readers.TabixReader;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class GnomadTabixProvider implements VariantEvidenceProvider {
    static final String LOCAL_POPULATION_READER_VERSION = "HTSJDK_LOCAL_POPULATION_V1";
    private static final long LOCAL_MAX_BYTES = 16 * 1024 * 1024;
    private final boolean localPopulationInProcess;
    private final Map<String, BoundedRangeTabix.Binding> rangeBindings;
    static Map<String, Object> localPopulationTransportIdentity() {
        return Map.of("version", LOCAL_POPULATION_READER_VERSION,
                "libraryVersion", Objects.toString(TabixReader.class.getPackage().getImplementationVersion(), "UNKNOWN"),
                "maximumOutputBytes", LOCAL_MAX_BYTES, "maximumRecords", MAX_BATCH_VARIANTS);
    }
    public Map<String, Object> transportIdentity() { return localPopulationInProcess ? localPopulationTransportIdentity()
            : rangeBindings.isEmpty() ? Map.of("version", "TABIX")
            : Map.of("version", BoundedRangeTabix.VERSION, "bindings", rangeBindings); }

    private static final Logger LOGGER = LoggerFactory.getLogger(GnomadTabixProvider.class);
    private static final Pattern GNOMAD_CHROMOSOME_PATTERN =
            Pattern.compile("(?i)(?:^|[._-])(chr(?:[0-9]+|x|y|m|mt))[._-]");
    private static final int MAX_BATCH_VARIANTS = 25000;

    private final VariantAnalysisProperties properties;
    private final GnomadPopulationInputProfile populationInputProfile;
    private final boolean explicitPopulationInputProfile;
    private final boolean retainBa1RawRecord;
    private final List<Map<String, Object>> queryDiagnostics = Collections.synchronizedList(new ArrayList<>());
    public List<Map<String, Object>> queryDiagnostics() {
        synchronized (queryDiagnostics) { return List.copyOf(queryDiagnostics); }
    }
    private final ConcurrentMap<String, EvidenceItem> cache = new ConcurrentHashMap<String, EvidenceItem>();
    private final ConcurrentMap<String, List<String>> frequencyFieldCache =
            new ConcurrentHashMap<String, List<String>>();

    @org.springframework.beans.factory.annotation.Autowired
    public GnomadTabixProvider(VariantAnalysisProperties properties) {
        this(
                properties,
                properties.getGnomad().isPopulationInputEnabled()
                        ? GnomadPopulationInputProfile.loadDefault()
                        : null,
                false
        );
    }

    GnomadTabixProvider(VariantAnalysisProperties properties,
                        GnomadPopulationInputProfile populationInputProfile) {
        this(properties, populationInputProfile, true);
    }

    private GnomadTabixProvider(VariantAnalysisProperties properties,
                                GnomadPopulationInputProfile populationInputProfile,
                                boolean explicitPopulationInputProfile) {
        this(properties, populationInputProfile, explicitPopulationInputProfile, false);
    }

    GnomadTabixProvider(VariantAnalysisProperties properties, GnomadPopulationInputProfile populationInputProfile,
                       boolean explicitPopulationInputProfile, boolean retainBa1RawRecord) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.localPopulationInProcess = properties.getGnomad().isLocalPopulationInProcessEnabled();
        if (localPopulationInProcess) {
            if (!properties.getGnomad().isPopulationInputEnabled() || explicitPopulationInputProfile
                    || !"TABIX".equals(properties.getGnomad().getRemoteTransport()))
                throw new IllegalArgumentException("GNOMAD_LOCAL_READER_CONFIGURATION_CONFLICT");
            var chromosomes = new LinkedHashSet<String>();
            for (Path path : properties.getGnomad().getGrch38Files()) {
                requireLocalPopulationFile(path);
                String chromosome = chromosomeFromFile(path);
                // A receipt describes one exact full-sites resource, never an implicit dataset union.
                if ((chromosome == null && properties.getGnomad().getGrch38Files().size() > 1)
                        || (chromosome != null && !chromosomes.add(chromosome)))
                    throw new IllegalArgumentException("GNOMAD_LOCAL_MULTIPLE_RESOURCES_PER_CHROMOSOME");
            }
        }
        try { this.rangeBindings = BoundedRangeTabix.VERSION.equals(properties.getGnomad().getRemoteTransport())
                ? BoundedRangeTabix.bindings(properties.getGnomad().getRangeBindingsFile()) : Map.of(); }
        catch (IOException invalid) { throw new IllegalArgumentException("GNOMAD_RANGE_CONFIGURATION_INVALID", invalid); }
        this.populationInputProfile = populationInputProfile;
        this.explicitPopulationInputProfile = explicitPopulationInputProfile;
        this.retainBa1RawRecord = retainBa1RawRecord;
        if (explicitPopulationInputProfile && populationInputProfile == null) {
            throw new IllegalArgumentException("An explicit gnomAD population input profile is required");
        }
    }

    @Override
    public String source() {
        return "GNOMAD";
    }

    @Override
    public String version() {
        return properties.getGnomad().getVersion();
    }

    @Override
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants,
            AnalysisContext context) {
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!ProviderAssemblySupport.supportsOnlyGrch38(variants, context)) {
            return ProviderAssemblySupport.unsupportedForGrch38Only(
                    variants,
                    context,
                    source(),
                    version(),
                    "POPULATION",
                    "gnomAD GRCh38 compact/full indexes"
            );
        }
        long startedAtNanos = System.nanoTime();
        long startedMemoryBytes = usedMemoryBytes();
        if (!properties.getGnomad().isEnabled()) {
            addEvidence(result, variants, disabledEvidence("gnomAD local lookup is disabled"));
            logLookupMetrics(variants, result, startedAtNanos, startedMemoryBytes);
            return result;
        }
        boolean populationInputEnabled = properties.getGnomad().isPopulationInputEnabled();
        boolean remoteJointEnabled = populationInputEnabled
                && explicitPopulationInputProfile && populationInputProfile != null;
        boolean fullVcfFallbackAllowed = properties.getGnomad().isAllowFullVcfFallback();
        boolean compactConfigured = !properties.getGnomad().getGrch38CompactFiles().isEmpty();
        Map<String, List<Path>> compactFilesByChromosome =
                availableFilesByChromosome(properties.getGnomad().getGrch38CompactFiles());
        Map<String, List<Path>> fullFilesByChromosome = populationInputEnabled || fullVcfFallbackAllowed
                ? availableFilesByChromosome(properties.getGnomad().getGrch38Files())
                : Collections.emptyMap();
        if (populationInputEnabled && fullFilesByChromosome.isEmpty() && !remoteJointEnabled) {
            addUnavailable(result, variants,
                    "Configured gnomAD GRCh38 full-sites indexes are unavailable for population input");
            logLookupMetrics(variants, result, startedAtNanos, startedMemoryBytes);
            return result;
        }
        if (!populationInputEnabled && compactFilesByChromosome.isEmpty() && !fullVcfFallbackAllowed) {
            String message = compactConfigured
                    ? "Configured compact gnomAD GRCh38 indexes are unavailable"
                    : "gnomAD compact GRCh38 indexes are not configured; full VCF fallback is disabled";
            addUnavailable(result, variants, message);
            logLookupMetrics(variants, result, startedAtNanos, startedMemoryBytes);
            return result;
        }
        if (!populationInputEnabled
                && compactFilesByChromosome.isEmpty() && fullFilesByChromosome.isEmpty()) {
            addUnavailable(result, variants, "Configured gnomAD GRCh38 indexes are unavailable");
            logLookupMetrics(variants, result, startedAtNanos, startedMemoryBytes);
            return result;
        }
        Map<String, FileBatch> batches = new LinkedHashMap<String, FileBatch>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            List<Path> files;
            if (remoteJointEnabled) {
                files = Collections.emptyList();
            } else {
                files = populationInputEnabled
                        ? filesFor(fullFilesByChromosome, key)
                        : filesFor(compactFilesByChromosome, key);
            }
            if (!populationInputEnabled && files.isEmpty() && fullVcfFallbackAllowed) {
                files = filesFor(fullFilesByChromosome, key);
            }
            FileBatch requestedBatch = null;
            if (!files.isEmpty()) {
                requestedBatch = new FileBatch(files);
            } else if (remoteJointEnabled) {
                try {
                    RemoteJointTarget remoteTarget = remoteJointTarget(key.getChromosome());
                    if (remoteTarget != null) requestedBatch = new FileBatch(remoteTarget);
                } catch (IllegalArgumentException | IllegalStateException invalidProfile) {
                    result.put(key, Collections.singletonList(remoteUnavailableEvidence(
                            "The profile-pinned gnomAD joint HTTPS target is invalid for chromosome "
                                    + key.getChromosome())));
                    continue;
                }
            }
            if (requestedBatch == null) {
                result.put(key, Collections.singletonList(unavailableEvidence(
                        populationInputEnabled
                                ? "No configured or profile-pinned gnomAD GRCh38 target is available for chromosome "
                                + key.getChromosome()
                                : fullVcfFallbackAllowed
                                        ? "No configured gnomAD GRCh38 index is available for chromosome "
                                        + key.getChromosome()
                                        : "No configured compact gnomAD GRCh38 index is available for chromosome "
                                        + key.getChromosome()
                )));
                continue;
            }
            String cacheKey = cacheKey(requestedBatch, key);
            EvidenceItem evidence = rangeBindings.isEmpty() ? cache.get(cacheKey) : null;
            if (evidence != null) {
                result.put(key, Collections.singletonList(evidence));
                continue;
            }
            String batchKey = requestedBatch.batchKey();
            FileBatch batch = batches.get(batchKey);
            if (batch == null) {
                batch = requestedBatch;
                batches.put(batchKey, batch);
            }
            batch.variants.add(variant);
        }
        annotateFileGroups(splitLargeBatches(new ArrayList<FileBatch>(batches.values())), result);
        logLookupMetrics(variants, result, startedAtNanos, startedMemoryBytes);
        return result;
    }

    private List<FileBatch> splitLargeBatches(List<FileBatch> batches) {
        List<FileBatch> result = new ArrayList<FileBatch>();
        for (FileBatch batch : batches) {
            if (batch.variants.size() <= MAX_BATCH_VARIANTS) {
                result.add(batch);
                continue;
            }
            for (int start = 0; start < batch.variants.size(); start += MAX_BATCH_VARIANTS) {
                FileBatch chunk = batch.emptyCopy();
                int end = Math.min(start + MAX_BATCH_VARIANTS, batch.variants.size());
                chunk.variants.addAll(batch.variants.subList(start, end));
                result.add(chunk);
            }
        }
        return result;
    }

    private void annotateFileGroups(List<FileBatch> batches,
                                    Map<NormalizedVariantKey, List<EvidenceItem>> result) {
        int parallelism = Math.min(
                Math.max(1, properties.getGnomad().getParallelism()),
                batches.size()
        );
        if (parallelism <= 1 || batches.size() <= 1) {
            for (FileBatch batch : batches) {
                try {
                    applyBatchResult(batch, batchLookup(batch, batch.variants), result);
                } catch (RuntimeException ex) {
                    LOGGER.warn("gnomAD sequential lookup failed: {}",
                            SpecialistProviderSupport.rootMessage(ex));
                    applyFailureToUnresolved(batch, ex, result, null);
                }
            }
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<FileBatchResult>> futures = new ArrayList<Future<FileBatchResult>>();
            for (FileBatch batch : batches) {
                futures.add(executor.submit(() -> new FileBatchResult(
                        batch,
                        batchLookup(batch, batch.variants)
                )));
            }
            for (int index = 0; index < futures.size(); index++) {
                Future<FileBatchResult> future = futures.get(index);
                try {
                    FileBatchResult batchResult = future.get();
                    applyBatchResult(batchResult.batch, batchResult.evidenceByKey, result);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("gnomAD parallel lookup was interrupted");
                    for (int unresolved = index; unresolved < batches.size(); unresolved++) {
                        applyFailureToUnresolved(
                                batches.get(unresolved), ex, result, "PROVIDER_INTERRUPTED");
                    }
                    break;
                } catch (ExecutionException ex) {
                    LOGGER.warn("gnomAD parallel lookup failed: {}",
                            SpecialistProviderSupport.rootMessage(ex));
                    applyFailureToUnresolved(batches.get(index), ex, result, null);
                } catch (RuntimeException ex) {
                    LOGGER.warn("gnomAD parallel lookup failed: {}",
                            SpecialistProviderSupport.rootMessage(ex));
                    applyFailureToUnresolved(batches.get(index), ex, result, null);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void applyBatchResult(FileBatch batch,
                                  Map<NormalizedVariantKey, EvidenceItem> evidenceByKey,
                                  Map<NormalizedVariantKey, List<EvidenceItem>> result) {
        EvidenceItem noRecord = noRecordEvidence(batch);
        for (VariantInterpretation variant : batch.variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            EvidenceItem evidence = evidenceByKey.get(key);
            EvidenceItem resolved = evidence == null ? noRecord : evidence;
            cacheIfUseful(batch, key, resolved);
            result.put(key, Collections.singletonList(resolved));
        }
    }

    private void cacheIfUseful(FileBatch batch, NormalizedVariantKey key, EvidenceItem evidence) {
        if (evidence != null && "APPLICABLE".equals(evidence.getApplicability())) {
            if (rangeBindings.isEmpty()) cache.put(cacheKey(batch, key), evidence);
        }
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookup(FileBatch batch,
                                                                List<VariantInterpretation> variants) {
        if (batch.remoteTarget != null) {
            try {
                return batchLookupRemoteJoint(batch.remoteTarget, variants);
            } catch (IOException | RuntimeException failure) {
                Map<NormalizedVariantKey, EvidenceItem> failed =
                        new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
                String summary = "gnomAD remote joint lookup failed: "
                        + SpecialistProviderSupport.rootMessage(failure);
                for (VariantInterpretation variant : variants) {
                    failed.put(variant.getObservation().getKey(), remoteFailedEvidence(summary, failure));
                }
                return failed;
            }
        }
        return batchLookup(batch.files, variants);
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookup(List<Path> files,
                                                               List<VariantInterpretation> variants) {
        Map<NormalizedVariantKey, EvidenceItem> result =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        List<VariantInterpretation> remaining = new ArrayList<VariantInterpretation>(variants);
        String failureSummary = null;
        for (Path file : files) {
            if (remaining.isEmpty()) {
                break;
            }
            try {
                Map<NormalizedVariantKey, EvidenceItem> found = batchLookupFile(file, remaining);
                result.putAll(found);
                remaining = withoutResolved(remaining, found);
            } catch (IOException | RuntimeException ex) {
                failureSummary = "gnomAD local lookup failed for " + file + ": "
                        + SpecialistProviderSupport.rootMessage(ex);
                LOGGER.warn("{}", failureSummary);
            }
        }
        if (failureSummary != null) {
            for (VariantInterpretation variant : remaining) {
                result.put(
                        variant.getObservation().getKey(),
                        failedEvidence(failureSummary)
                );
            }
        }
        return result;
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupFile(Path file,
                                                                    List<VariantInterpretation> variants)
            throws IOException {
        if (properties.getGnomad().isPopulationInputEnabled()) {
            if (isCompactFile(file)) {
                throw new IOException(
                        "gnomAD population input requires a full-sites VCF, not a compact file");
            }
            return localPopulationInProcess ? batchLookupLocalPopulation(file, variants)
                    : batchLookupFileWithTabix(file, variants);
        }
        if (isCompactFile(file)) {
            return batchLookupCompactFile(file, variants);
        }
        try {
            return batchLookupFileWithBcftoolsQuery(file, variants);
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("gnomAD bcftools query failed for {}; falling back to tabix: {}",
                    file, SpecialistProviderSupport.rootMessage(ex));
        }
        try {
            return batchLookupFileWithTabix(file, variants);
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("gnomAD batch tabix lookup failed for {}; falling back to in-process tabix: {}",
                    file, SpecialistProviderSupport.rootMessage(ex));
            return batchLookupFileInProcess(file, variants);
        }
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupCompactFile(
            Path file,
            List<VariantInterpretation> variants) throws IOException {
        String tabixExecutable = properties.getGnomad().getTabixExecutable();
        if (!StringUtils.hasText(tabixExecutable)) {
            throw new IOException("variant-analysis.gnomad.tabix-executable is not configured");
        }
        Map<String, List<NormalizedVariantKey>> keysByPosition = keysByPosition(variants);
        if (keysByPosition.isEmpty()) {
            return Collections.emptyMap();
        }
        Path regions = Files.createTempFile("varvis-gnomad-compact-regions-", ".bed");
        Path output = Files.createTempFile("varvis-gnomad-compact-output-", ".tsv");
        try {
            writeRegions(file, regions, keysByPosition);
            List<String> command = new ArrayList<String>();
            command.add(tabixExecutable);
            if (properties.getGnomad().getTabixCacheMb() > 0) {
                command.add("--cache");
                command.add(String.valueOf(properties.getGnomad().getTabixCacheMb()));
            }
            command.add("-R");
            command.add(regions.toString());
            command.add(file.toAbsolutePath().normalize().toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
            boolean completed;
            try {
                completed = process.waitFor(
                        properties.getGnomad().getTimeout().toMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("gnomAD compact tabix lookup was interrupted", ex);
            }
            if (!completed) {
                process.destroyForcibly();
                waitForForcedExit(process);
                throw new IOException("gnomAD compact tabix lookup timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("gnomAD compact tabix lookup failed with exit code "
                        + process.exitValue() + ": " + readLimited(output));
            }
            return parseCompactOutput(file, output, keysByPosition);
        } finally {
            deleteTempFileQuietly(regions);
            deleteTempFileQuietly(output);
        }
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupFileWithBcftoolsQuery(
            Path file,
            List<VariantInterpretation> variants) throws IOException {
        String bcftoolsExecutable = properties.getGnomad().getBcftoolsExecutable();
        if (!StringUtils.hasText(bcftoolsExecutable)) {
            throw new IOException("variant-analysis.gnomad.bcftools-executable is not configured");
        }
        Map<String, List<NormalizedVariantKey>> keysByPosition = keysByPosition(variants);
        if (keysByPosition.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> frequencyFields = frequencyFields(file);
        if (frequencyFields.isEmpty()) {
            throw new IOException("No gnomAD allele-frequency INFO fields are available");
        }

        Path regions = Files.createTempFile("varvis-gnomad-regions-", ".bed");
        Path output = Files.createTempFile("varvis-gnomad-query-output-", ".tsv");
        Path error = Files.createTempFile("varvis-gnomad-query-error-", ".log");
        try {
            writeRegions(file, regions, keysByPosition);
            List<String> command = new ArrayList<String>();
            command.add(bcftoolsExecutable);
            command.add("query");
            command.add("-u");
            command.add("--regions-overlap");
            command.add("0");
            command.add("-R");
            command.add(regions.toString());
            command.add("-f");
            command.add(queryFormat(frequencyFields));
            command.add("-o");
            command.add(output.toString());
            command.add(file.toAbsolutePath().normalize().toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(error.toFile());
            Process process = builder.start();
            boolean completed;
            try {
                completed = process.waitFor(
                        properties.getGnomad().getTimeout().toMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("gnomAD bcftools query was interrupted", ex);
            }
            if (!completed) {
                process.destroyForcibly();
                waitForForcedExit(process);
                throw new IOException("gnomAD bcftools query timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("gnomAD bcftools query failed with exit code "
                        + process.exitValue() + ": " + readLimited(error));
            }
            return parseQueryOutput(file, output, frequencyFields, keysByPosition);
        } finally {
            deleteTempFileQuietly(regions);
            deleteTempFileQuietly(output);
            deleteTempFileQuietly(error);
        }
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupFileWithTabix(Path file,
                                                                             List<VariantInterpretation> variants)
            throws IOException {
        return batchLookupVcfWithTabix(file, null, variants);
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupRemoteJoint(
            RemoteJointTarget target,
            List<VariantInterpretation> variants) throws IOException {
        var settings = properties.getGnomad();
        if (!rangeBindings.isEmpty()) return batchLookupVcfWithTabix(null, target, variants);
        var positions = new LinkedHashMap<String, List<VariantInterpretation>>();
        for (var variant : variants) {
            var key = variant.getObservation().getKey();
            positions.computeIfAbsent(positionKey(key.getChromosome(), key.getPosition()), ignored -> new ArrayList<>()).add(variant);
        }
        if (settings.getRemoteMaxAttempts() <= 1 || positions.size() <= 1)
            return batchLookupVcfWithTabix(null, target, variants);
        long started = System.nanoTime();
        var configuredTotal = settings.getRemoteTotalTimeout();
        long total = configuredTotal == null ? settings.getTimeout().toMillis() : configuredTotal.toMillis();
        String parentQuery = GnomadQueryRunner.queryIdentity(target.resourceFileName + "|" + keysByPosition(variants).values());
        try {
            return batchLookupVcfWithTabix(null, target, variants, queryBudget(settings.getTimeout().toMillis(),
                    Math.min(total, settings.getTimeout().toMillis()), 1),
                    Map.of("parentQuerySha256", parentQuery, "retryPhase", "PRIMARY", "configuredGroupTotalTimeoutMillis", total));
        } catch (IOException failure) {
            if (Thread.currentThread().isInterrupted() || (!SpecialistProviderSupport.isTimeout(failure)
                    && !Objects.toString(failure.getMessage(), "").contains("failed with exit code"))) throw failure;
        }
        // One failed group becomes two smaller retry groups. No recursive fan-out and no new total budget.
        var groups = new ArrayList<List<VariantInterpretation>>();
        groups.add(new ArrayList<>()); groups.add(new ArrayList<>());
        int position = 0, middle = (positions.size() + 1) / 2;
        for (var values : positions.values()) groups.get(position++ < middle ? 0 : 1).addAll(values);
        var result = new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            long remaining = total - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long allocated = remaining / (groups.size() - i);
            try {
                if (Thread.currentThread().isInterrupted()) throw new IOException("gnomAD split retry interrupted before launch");
                if (allocated <= 0) throw new IOException("gnomAD split retry timed out before launch");
                var found = batchLookupVcfWithTabix(null, target, group, queryBudget(
                        Math.min(settings.getTimeout().toMillis(), allocated), allocated, settings.getRemoteMaxAttempts() - 1),
                        Map.of("parentQuerySha256", parentQuery, "retryPhase", "SPLIT_RETRY", "configuredGroupTotalTimeoutMillis", total));
                result.putAll(found);
            } catch (IOException | RuntimeException failure) {
                for (var variant : group) result.put(variant.getObservation().getKey(),
                        remoteFailedEvidence("gnomAD split retry failed: " + SpecialistProviderSupport.rootMessage(failure), failure));
            }
        }
        return result;
    }

    private VariantAnalysisProperties.Gnomad queryBudget(long timeout, long total, int attempts) {
        var value = new VariantAnalysisProperties.Gnomad();
        value.setTimeout(java.time.Duration.ofMillis(timeout));
        value.setRemoteTotalTimeout(java.time.Duration.ofMillis(total)); value.setRemoteMaxAttempts(attempts);
        Path cacheDirectory = properties.getGnomad().getRemoteIndexCacheDir();
        if (cacheDirectory != null) value.setRemoteIndexCacheDir(cacheDirectory.toString());
        return value;
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupVcfWithTabix(
            Path file,
            RemoteJointTarget remoteTarget,
            List<VariantInterpretation> variants) throws IOException {
        return batchLookupVcfWithTabix(file, remoteTarget, variants, properties.getGnomad(), Map.of());
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupVcfWithTabix(Path file, RemoteJointTarget remoteTarget,
            List<VariantInterpretation> variants, VariantAnalysisProperties.Gnomad budget, Map<String, Object> group) throws IOException {
        String tabixExecutable = properties.getGnomad().getTabixExecutable();
        if (!StringUtils.hasText(tabixExecutable)) {
            throw new IOException("variant-analysis.gnomad.tabix-executable is not configured");
        }
        Map<String, List<NormalizedVariantKey>> keysByPosition = keysByPosition(variants);
        if (keysByPosition.isEmpty()) {
            return Collections.emptyMap();
        }
        if (remoteTarget != null && !rangeBindings.isEmpty()) {
            var binding = rangeBindings.get(remoteTarget.dataUri);
            if (binding == null) throw new IOException("GNOMAD_RANGE_OBJECT_NOT_BOUND");
            Path output = Files.createTempFile("varvis-gnomad-range-", ".vcf");
            try {
                var regions = variants.stream().map(v -> "chr" + normalizeChromosomeToken(v.getObservation().getKey().getChromosome())
                        + ":" + v.getObservation().getKey().getPosition() + "-" + v.getObservation().getKey().getPosition()).distinct().toList();
                BoundedRangeTabix.query(remoteTarget.dataUri, remoteTarget.indexUri, binding, BoundedRangeTabix.Limits.joint(),
                        Objects.requireNonNull(properties.getGnomad().getRemoteIndexCacheDir()), regions, output, queryDiagnostics);
                return parseRemoteBatchOutput(remoteTarget, output, keysByPosition);
            } finally { Files.deleteIfExists(output); }
        }
        boolean remote = remoteTarget != null;
        Path regions = Files.createTempFile("varvis-gnomad-regions-", ".bed");
        Path output = Files.createTempFile("varvis-gnomad-output-", ".vcf");
        Path error = Files.createTempFile("varvis-gnomad-error-", ".log");
        try {
            if (remote) {
                writeRemoteRegions(remoteTarget, regions, keysByPosition);
            } else {
                writeRegions(file, regions, keysByPosition);
            }
            List<String> command = new ArrayList<String>();
            command.add(tabixExecutable);
            if (properties.getGnomad().getTabixCacheMb() > 0) {
                command.add("--cache");
                command.add(String.valueOf(properties.getGnomad().getTabixCacheMb()));
            }
            command.add("-R");
            command.add(regions.toString());
            command.add(remoteTarget != null
                    ? remoteTarget.dataUri
                    : file.toAbsolutePath().normalize().toString());
            String resource = remoteTarget == null ? Objects.requireNonNull(file).getFileName().toString() : remoteTarget.resourceFileName;
            String queryIdentity = GnomadQueryRunner.queryIdentity(
                    resource + "|" + keysByPosition.values());
            GnomadQueryRunner.run(command, output, error, remote, budget, resource,
                    queryIdentity, keysByPosition.size(), queryDiagnostics, group);
            return remote
                    ? parseRemoteBatchOutput(remoteTarget, output, keysByPosition)
                    : parseBatchOutput(file, output, keysByPosition);
        } finally {
            deleteTempFileQuietly(regions);
            deleteTempFileQuietly(output);
            deleteTempFileQuietly(error);
        }
    }

    private void waitForForcedExit(Process process) {
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteTempFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            LOGGER.debug("Unable to delete temporary gnomAD file {}: {}",
                    path, SpecialistProviderSupport.rootMessage(ex));
        }
    }

    private String readLimited(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            int length = Math.min(bytes.length, 2000);
            return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return SpecialistProviderSupport.rootMessage(ex);
        }
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupFileInProcess(Path file,
                                                                             List<VariantInterpretation> variants)
            throws IOException {
        Map<NormalizedVariantKey, EvidenceItem> result =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        try (TabixReader reader = new TabixReader(file.toAbsolutePath().normalize().toString())) {
            for (Map.Entry<String, List<VariantInterpretation>> entry : byChromosome(variants).entrySet()) {
                annotateChromosome(reader, file, entry.getKey(), entry.getValue(), result);
            }
        }
        return result;
    }

    /** Explicit local transport. No child process, remote lookup, or fallback is eligible here. */
    private Map<NormalizedVariantKey, EvidenceItem> batchLookupLocalPopulation(
            Path file, List<VariantInterpretation> variants) {
        var receipt = new LinkedHashMap<String, Object>();
        receipt.putAll(localPopulationTransportIdentity());
        receipt.put("resourceFileName", file.getFileName().toString());
        var exactQueries = new java.util.TreeSet<String>();
        for (var variant : variants) {
            var key = variant.getObservation().getKey();
            exactQueries.add(key.getGenomeAssembly() + ":" + key.getChromosome() + ":" + key.getPosition()
                    + ":" + key.getReference() + ":" + key.getAlternate());
        }
        receipt.put("querySha256", GnomadQueryRunner.queryIdentity(String.join("\n", exactQueries)));
        receipt.put("locusCount", keysByPosition(variants).size());
        receipt.put("queryMode", LOCAL_POPULATION_READER_VERSION);
        receipt.put("diagnosticScope", "TRANSPORT_EXECUTION_NOT_ALLELE_EVALUATION");
        receipt.put("processLaunches", 0);
        receipt.put("attempt", 1); receipt.put("maximumAttempts", 1);
        receipt.put("deadlineEnforcement", "COOPERATIVE_LOCAL_FILE_IO_NO_BACKGROUND_WORKER");
        receipt.put("startedAt", Instant.now().toString());
        long started = System.nanoTime();
        var found = new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        EvidenceItem failure = null;
        try {
            long allowed = properties.getGnomad().getTimeout().toNanos();
            if (allowed <= 0) throw new IOException("GNOMAD_LOCAL_TIMEOUT_INVALID");
            receipt.put("attemptTimeoutMillis", properties.getGnomad().getTimeout().toMillis());
            receipt.put("totalTimeoutMillis", properties.getGnomad().getTimeout().toMillis());
            var budget = new LocalPopulationBudget(started, allowed, receipt);
            budget.check();
            var identity = localPopulationFileIdentity(file);
            receipt.put("resourceIdentity", identity);
            receipt.put("resourceIdentitySha256", GnomadQueryRunner.queryIdentity(identity.toString()));
            receipt.put("indexOlderThanData", Files.getLastModifiedTime(Path.of(file + ".tbi"))
                    .compareTo(Files.getLastModifiedTime(file)) < 0);
            if (htsjdk.samtools.util.BlockCompressedInputStream.checkTermination(file.toFile())
                    != htsjdk.samtools.util.BlockCompressedInputStream.FileTermination.HAS_TERMINATOR_BLOCK)
                throw new IOException("GNOMAD_LOCAL_BGZF_INCOMPLETE");
            Path index = Path.of(file + ".tbi");
            // HTSJDK's constructor can leak its own index/data handles on malformed input.
            // Validate the bounded index with an owned stream, and independently own the data stream.
            try (var input = new htsjdk.samtools.util.BlockCompressedInputStream(index.toFile())) {
                new htsjdk.tribble.index.tabix.TabixIndex(input);
            }
            budget.check();
            try (var stream = new htsjdk.samtools.seekablestream.SeekableFileStream(file.toFile());
                 TabixReader reader = new TabixReader(file.toAbsolutePath().normalize().toString(),
                         index.toAbsolutePath().normalize().toString(), stream)) {
                for (var entry : byChromosome(variants).entrySet()) {
                    var sorted = new ArrayList<>(entry.getValue());
                    sorted.sort(Comparator.comparingInt(v -> v.getObservation().getKey().getPosition()));
                    for (var window : windows(entry.getKey(), sorted)) queryWindow(reader, file, window, found, budget);
                }
            }
            budget.check();
            if (!identity.equals(localPopulationFileIdentity(file))) throw new IOException("GNOMAD_LOCAL_RESOURCE_CHANGED");
            receipt.put("status", "SUCCEEDED"); receipt.put("complete", true);
        } catch (IOException | RuntimeException invalid) {
            found.clear(); // A partial read must never publish HIT or NO_RECORD.
            String message = Objects.toString(invalid.getMessage(), "");
            String reason = message.startsWith("GNOMAD_LOCAL_") ? message : "GNOMAD_LOCAL_READ_FAILED";
            boolean interrupted = Thread.currentThread().isInterrupted();
            boolean timeout = "GNOMAD_LOCAL_TIMEOUT".equals(reason);
            receipt.put("status", interrupted ? "INTERRUPTED" : timeout ? "TIMEOUT" : "FAILED");
            receipt.put("reasonCode", reason); receipt.put("complete", false);
            receipt.put("errorType", invalid.getClass().getSimpleName());
            failure = failedEvidence(timeout ? "gnomAD local indexed read timed out" : reason,
                    interrupted ? "PROVIDER_INTERRUPTED" : reason, null);
        } finally {
            receipt.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            receipt.put("completedAt", Instant.now().toString());
            synchronized (queryDiagnostics) {
                if (queryDiagnostics.size() >= 2000) queryDiagnostics.remove(0);
                queryDiagnostics.add(Collections.unmodifiableMap(new LinkedHashMap<>(receipt)));
            }
        }
        var result = new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        var absent = failure == null ? noRecordEvidence(new FileBatch(List.of(file))) : failure;
        for (var variant : variants) {
            var key = variant.getObservation().getKey();
            var item = found.getOrDefault(key, absent);
            var details = new LinkedHashMap<>(item.getDetails());
            details.put("localPopulationReadReceipt", Collections.unmodifiableMap(new LinkedHashMap<>(receipt)));
            result.put(key, new EvidenceItem(source(), version(), "POPULATION", item.getApplicability(),
                    item.getRawScore(), item.getLabel(), item.getSummary(), details));
        }
        return result;
    }

    private static void requireLocalPopulationFile(Path file) {
        String text = file.toString();
        if (text.startsWith("\\\\") || text.startsWith("//") || text.contains("://"))
            throw new IllegalArgumentException("GNOMAD_LOCAL_FILE_REQUIRED");
    }

    private static Map<String, Object> localPopulationFileIdentity(Path file) throws IOException {
        requireLocalPopulationFile(file);
        requireLocalPopulationFile(file.toRealPath());
        Path index = Path.of(file + ".tbi");
        requireLocalPopulationFile(index.toRealPath());
        if (!Files.isRegularFile(file) || !Files.isRegularFile(index) || Files.size(index) > 4 * 1024 * 1024)
            throw new IOException("GNOMAD_LOCAL_INDEX_UNAVAILABLE_OR_OVERSIZED");
        var result = new LinkedHashMap<String, Object>();
        result.put("locationSha256", GnomadQueryRunner.queryIdentity(file.toAbsolutePath().normalize().toString()));
        result.put("dataBytes", Files.size(file)); result.put("dataMtime", Files.getLastModifiedTime(file).toString());
        result.put("indexBytes", Files.size(index)); result.put("indexMtime", Files.getLastModifiedTime(index).toString());
        try {
            result.put("indexSha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(index))));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    private static final class LocalPopulationBudget {
        private final long started, allowed;
        private final Map<String, Object> receipt;
        private long bytes; private int records;
        LocalPopulationBudget(long started, long allowed, Map<String, Object> receipt) {
            this.started = started; this.allowed = allowed; this.receipt = receipt;
        }
        void check() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("GNOMAD_LOCAL_INTERRUPTED");
            if (System.nanoTime() - started >= allowed) throw new IOException("GNOMAD_LOCAL_TIMEOUT");
        }
        void consume(String line) throws IOException {
            check(); bytes += line.getBytes(StandardCharsets.UTF_8).length + 1; records++;
            receipt.put("readUtf8Bytes", bytes); receipt.put("recordsRead", records);
            if (bytes > LOCAL_MAX_BYTES || records > MAX_BATCH_VARIANTS) throw new IOException("GNOMAD_LOCAL_OUTPUT_LIMIT");
        }
    }

    private Map<String, List<NormalizedVariantKey>> keysByPosition(List<VariantInterpretation> variants) {
        Map<String, List<NormalizedVariantKey>> result =
                new TreeMap<String, List<NormalizedVariantKey>>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            result.computeIfAbsent(positionKey(key.getChromosome(), key.getPosition()),
                    ignored -> new ArrayList<NormalizedVariantKey>()).add(key);
        }
        return result;
    }

    private void writeRegions(Path file,
                              Path regions,
                              Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        List<String> lines = new ArrayList<String>(keysByPosition.size());
        for (List<NormalizedVariantKey> keys : keysByPosition.values()) {
            NormalizedVariantKey key = keys.get(0);
            int zeroBasedStart = Math.max(0, key.getPosition() - 1);
            lines.add(tabixChromosome(file, key.getChromosome()) + "\t" + zeroBasedStart + "\t" + key.getPosition());
        }
        Files.write(regions, lines, StandardCharsets.UTF_8);
    }

    private void writeRemoteRegions(RemoteJointTarget target,
                                    Path regions,
                                    Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        List<String> lines = new ArrayList<String>(keysByPosition.size());
        for (List<NormalizedVariantKey> keys : keysByPosition.values()) {
            NormalizedVariantKey key = keys.get(0);
            int zeroBasedStart = Math.max(0, key.getPosition() - 1);
            lines.add(target.resourceContig + "\t" + zeroBasedStart + "\t" + key.getPosition());
        }
        Files.write(regions, lines, StandardCharsets.UTF_8);
    }

    private String queryFormat(List<String> frequencyFields) {
        StringBuilder builder = new StringBuilder("%CHROM\\t%POS\\t%REF\\t%ALT");
        for (String field : frequencyFields) {
            builder.append("\\t%INFO/").append(field);
        }
        builder.append("\\t%INFO/AC\\t%INFO/AN\\n");
        return builder.toString();
    }

    private List<String> frequencyFields(Path file) {
        String cacheKey = file.toAbsolutePath().normalize().toString();
        return frequencyFieldCache.computeIfAbsent(cacheKey, ignored -> {
            Set<String> fields = new LinkedHashSet<String>();
            fields.addAll(properties.getGnomad().getAlleleFrequencyFields());
            fields.add("AF_grpmax");
            fields.add("AF_grpmax_non_ukb");
            try (VCFFileReader reader = new VCFFileReader(file.toFile(), false)) {
                for (VCFInfoHeaderLine headerLine : reader.getFileHeader().getInfoHeaderLines()) {
                    if (looksLikeAlleleFrequencyField(headerLine.getID())) {
                        fields.add(headerLine.getID());
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.debug("Unable to inspect gnomAD header fields for {}: {}",
                        file, SpecialistProviderSupport.rootMessage(ex));
            }
            fields.remove("");
            return Collections.unmodifiableList(new ArrayList<String>(fields));
        });
    }

    private Map<NormalizedVariantKey, EvidenceItem> parseQueryOutput(
            Path file,
            Path output,
            List<String> frequencyFields,
            Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        Map<NormalizedVariantKey, EvidenceItem> result =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 4) {
                    continue;
                }
                Integer position = parseInteger(columns[1]);
                if (position == null) {
                    continue;
                }
                String chromosome = normalizeChromosomeToken(columns[0]);
                List<NormalizedVariantKey> candidates = keysByPosition.get(positionKey(chromosome, position));
                if (candidates == null) {
                    continue;
                }
                for (NormalizedVariantKey key : candidates) {
                    if (result.containsKey(key)) {
                        continue;
                    }
                    int alternateIndex = alternateIndex(columns, key);
                    if (alternateIndex < 0) {
                        continue;
                    }
                    EvidenceItem evidence = evidenceFromQueryColumns(
                            file,
                            columns,
                            frequencyFields,
                            alternateIndex
                    );
                    if (evidence != null) {
                        result.put(key, evidence);
                    }
                }
            }
        }
        return result;
    }

    private Map<NormalizedVariantKey, EvidenceItem> parseCompactOutput(
            Path file,
            Path output,
            Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        Map<NormalizedVariantKey, EvidenceItem> result =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line) || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 5) {
                    continue;
                }
                Integer position = parseInteger(columns[1]);
                if (position == null) {
                    continue;
                }
                String chromosome = normalizeChromosomeToken(columns[0]);
                List<NormalizedVariantKey> candidates = keysByPosition.get(positionKey(chromosome, position));
                if (candidates == null) {
                    continue;
                }
                for (NormalizedVariantKey key : candidates) {
                    if (result.containsKey(key)
                            || !key.getReference().equalsIgnoreCase(columns[2])
                            || !key.getAlternate().equalsIgnoreCase(columns[3])) {
                        continue;
                    }
                    result.put(key, evidenceFromCompactColumns(file, columns));
                }
            }
        }
        return result;
    }

    private EvidenceItem evidenceFromCompactColumns(Path file, String[] columns) {
        if (columns.length >= 12) {
            Double alleleFrequency = SpecialistProviderSupport.parseDouble(columns[4]);
            Double popmaxFrequency = SpecialistProviderSupport.parseDouble(columns[5]);
            FrequencyMatch frequency = maximumCompactFrequency(alleleFrequency, popmaxFrequency);
            if (frequency.value == null) {
                frequency = compactFrequencyFromCounts(columns[6], columns[7]);
            }
            if (frequency.value == null) {
                return failedEvidence("Matched compact gnomAD record has no allele-frequency value");
            }
            Map<String, Object> details = baseDetails(file, "compact");
            details.put("alleleFrequency", alleleFrequency);
            details.put("populationMaximumAlleleFrequency", popmaxFrequency);
            details.put("alleleCount", SpecialistProviderSupport.parseDouble(columns[6]));
            details.put("alleleNumber", SpecialistProviderSupport.parseDouble(columns[7]));
            details.put("homozygousAlternateCount", SpecialistProviderSupport.parseDouble(columns[8]));
            details.put("sourceDataset", textOrDefault(columns[9], sourceDataset(file)));
            details.put("gnomadVersion", textOrDefault(columns[10], version()));
            details.put("filters", textOrDefault(columns[11], "."));
            markCompactRetrievalOnly(details);
            return applicableEvidence(file, frequency, details);
        }

        Double maximumAlleleFrequency = SpecialistProviderSupport.parseDouble(columns[4]);
        if (maximumAlleleFrequency == null) {
            return failedEvidence("Matched compact gnomAD record has no allele-frequency value");
        }
        Map<String, Object> details = baseDetails(file, "compact");
        details.put("sourceDataset", sourceDataset(file));
        details.put("gnomadVersion", version());
        markCompactRetrievalOnly(details);
        return applicableEvidence(file, new FrequencyMatch(
                maximumAlleleFrequency,
                columns.length > 5 ? textOrDefault(columns[5], "MAX_AF") : "MAX_AF"
        ), details);
    }

    private FrequencyMatch maximumCompactFrequency(Double alleleFrequency, Double popmaxFrequency) {
        if (alleleFrequency != null && popmaxFrequency != null) {
            return alleleFrequency >= popmaxFrequency
                    ? new FrequencyMatch(alleleFrequency, "AF")
                    : new FrequencyMatch(popmaxFrequency, "AF_popmax");
        }
        if (alleleFrequency != null) {
            return new FrequencyMatch(alleleFrequency, "AF");
        }
        if (popmaxFrequency != null) {
            return new FrequencyMatch(popmaxFrequency, "AF_popmax");
        }
        return new FrequencyMatch(null, null);
    }

    private FrequencyMatch compactFrequencyFromCounts(String alleleCountValue, String alleleNumberValue) {
        Double alleleCount = SpecialistProviderSupport.parseDouble(alleleCountValue);
        Double alleleNumber = SpecialistProviderSupport.parseDouble(alleleNumberValue);
        if (alleleCount == null || alleleNumber == null || alleleNumber <= 0.0d) {
            return new FrequencyMatch(null, null);
        }
        return new FrequencyMatch(alleleCount / alleleNumber, "AC/AN");
    }

    private int alternateIndex(String[] columns, NormalizedVariantKey key) {
        String[] alternates = columns[3].split(",");
        for (int index = 0; index < alternates.length; index++) {
            if (SpecialistProviderSupport.matchesCoordinates(
                    columns[0],
                    columns[1],
                    columns[2],
                    alternates[index],
                    key
            )) {
                return index;
            }
        }
        return -1;
    }

    private EvidenceItem evidenceFromQueryColumns(Path file,
                                                  String[] columns,
                                                  List<String> frequencyFields,
                                                  int alternateIndex) {
        FrequencyMatch frequency = maximumFrequency(columns, frequencyFields, alternateIndex);
        if (frequency.value == null) {
            frequency = alleleFrequencyFromCounts(columns, frequencyFields.size(), alternateIndex);
        }
        if (frequency.value == null) {
            return failedEvidence("Matched gnomAD record has no configured allele-frequency field");
        }
        return applicableEvidence(file, frequency, baseDetails(file, "full-vcf"));
    }

    private FrequencyMatch maximumFrequency(String[] columns,
                                            List<String> frequencyFields,
                                            int alternateIndex) {
        Double maximum = null;
        String maximumField = null;
        for (int index = 0; index < frequencyFields.size(); index++) {
            int columnIndex = 4 + index;
            if (columnIndex >= columns.length) {
                continue;
            }
            Double value = valueAt(columns[columnIndex], alternateIndex);
            if (value != null && value >= 0.0d && value <= 1.0d
                    && (maximum == null || value > maximum)) {
                maximum = value;
                maximumField = frequencyFields.get(index);
            }
        }
        return new FrequencyMatch(maximum, maximumField);
    }

    private FrequencyMatch alleleFrequencyFromCounts(String[] columns,
                                                     int frequencyFieldCount,
                                                     int alternateIndex) {
        int alleleCountIndex = 4 + frequencyFieldCount;
        int alleleNumberIndex = alleleCountIndex + 1;
        if (alleleNumberIndex >= columns.length) {
            return new FrequencyMatch(null, null);
        }
        Double alleleCount = valueAt(columns[alleleCountIndex], alternateIndex);
        Double alleleNumber = valueAt(columns[alleleNumberIndex], 0);
        if (alleleCount == null || alleleNumber == null || alleleNumber <= 0.0d) {
            return new FrequencyMatch(null, null);
        }
        return new FrequencyMatch(alleleCount / alleleNumber, "AC/AN");
    }

    private Map<NormalizedVariantKey, EvidenceItem> parseBatchOutput(
            Path file,
            Path output,
            Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        return parseVcfBatchOutput(file, null, output, keysByPosition);
    }

    private Map<NormalizedVariantKey, EvidenceItem> parseRemoteBatchOutput(
            RemoteJointTarget target,
            Path output,
            Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        return parseVcfBatchOutput(null, target, output, keysByPosition);
    }

    private Map<NormalizedVariantKey, EvidenceItem> parseVcfBatchOutput(
            Path file,
            RemoteJointTarget remoteTarget,
            Path output,
            Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        Map<NormalizedVariantKey, EvidenceItem> result =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        boolean remote = remoteTarget != null;
        String operation = remote ? "gnomAD remote joint tabix" : "gnomAD tabix";
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line) || line.startsWith("#") || line.startsWith("[W::")) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 8) {
                    throw new IOException(operation + " returned a malformed VCF record");
                }
                Integer position = parseInteger(columns[1]);
                if (position == null) {
                    throw new IOException(operation + " returned a VCF record with an invalid position");
                }
                String chromosome = normalizeChromosomeToken(columns[0]);
                List<NormalizedVariantKey> candidates = keysByPosition.get(positionKey(chromosome, position));
                if (candidates == null) {
                    continue;
                }
                for (NormalizedVariantKey key : candidates) {
                    if (result.containsKey(key)) {
                        continue;
                    }
                    EvidenceItem evidence = remote
                            ? parseRemoteColumns(remoteTarget, columns, key)
                            : parseColumns(file, columns, key);
                    if (evidence != null) {
                        result.put(key, evidence);
                    }
                }
            }
        }
        return result;
    }

    private String tabixChromosome(Path file, String chromosome) {
        String normalized = normalizeChromosomeToken(chromosome);
        String fileName = file == null || file.getFileName() == null
                ? ""
                : file.getFileName().toString().toLowerCase();
        if (fileName.contains("chr" + normalized.toLowerCase())) {
            return "MT".equals(normalized) ? "chrM" : "chr" + normalized;
        }
        return normalized;
    }

    private boolean isCompactFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase();
        return name.contains("max-af") || name.contains("compact");
    }

    private String positionKey(String chromosome, int position) {
        return normalizeChromosomeToken(chromosome) + ":" + position;
    }

    private RemoteJointTarget remoteJointTarget(String chromosome) {
        String normalized = normalizeChromosomeToken(chromosome);
        String dataUri = populationInputProfile.getRemoteJointUri(normalized);
        String indexUri = populationInputProfile.getRemoteJointIndexUri(normalized);
        String resourceFileName = dataUri.substring(dataUri.lastIndexOf('/') + 1);
        return new RemoteJointTarget(
                normalized,
                "MT".equals(normalized) ? "chrM" : "chr" + normalized,
                dataUri,
                indexUri,
                resourceFileName
        );
    }

    private List<VariantInterpretation> withoutResolved(
            List<VariantInterpretation> variants,
            Map<NormalizedVariantKey, EvidenceItem> found) {
        if (found.isEmpty()) {
            return variants;
        }
        List<VariantInterpretation> result = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation variant : variants) {
            if (!found.containsKey(variant.getObservation().getKey())) {
                result.add(variant);
            }
        }
        return result;
    }

    private Map<String, List<VariantInterpretation>> byChromosome(List<VariantInterpretation> variants) {
        Map<String, List<VariantInterpretation>> result =
                new LinkedHashMap<String, List<VariantInterpretation>>();
        for (VariantInterpretation variant : variants) {
            String chromosome = variant.getObservation().getKey().getChromosome();
            result.computeIfAbsent(chromosome, ignored -> new ArrayList<VariantInterpretation>())
                    .add(variant);
        }
        return result;
    }

    private void annotateChromosome(TabixReader reader,
                                    Path file,
                                    String chromosome,
                                    List<VariantInterpretation> variants,
                                    Map<NormalizedVariantKey, EvidenceItem> result) throws IOException {
        List<VariantInterpretation> sorted = new ArrayList<VariantInterpretation>(variants);
        sorted.sort(Comparator.comparingInt(
                variant -> variant.getObservation().getKey().getPosition()
        ));
        for (QueryWindow window : windows(chromosome, sorted)) {
            queryWindow(reader, file, window, result);
        }
    }

    private List<QueryWindow> windows(String chromosome, List<VariantInterpretation> sortedVariants) {
        List<QueryWindow> windows = new ArrayList<QueryWindow>();
        int maxWindowBp = Math.max(0, properties.getGnomad().getQueryWindowBp());
        int maxMergeGapBp = Math.max(0, properties.getGnomad().getQueryMergeGapBp());
        QueryWindow current = null;
        for (VariantInterpretation variant : sortedVariants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            if (current == null
                    || key.getPosition() - current.end > maxMergeGapBp
                    || key.getPosition() - current.start > maxWindowBp) {
                current = new QueryWindow(chromosome, key.getPosition());
                windows.add(current);
            }
            current.add(key);
        }
        return windows;
    }

    private void queryWindow(TabixReader reader,
                             Path file,
                             QueryWindow window,
                             Map<NormalizedVariantKey, EvidenceItem> result) throws IOException {
        queryWindow(reader, file, window, result, null);
    }

    private void queryWindow(TabixReader reader, Path file, QueryWindow window,
                             Map<NormalizedVariantKey, EvidenceItem> result, LocalPopulationBudget budget) throws IOException {
        for (String region : tabixRegions(window.chromosome, window.start, window.end)) {
            if (budget != null) budget.check();
            TabixReader.Iterator iterator = reader.query(region);
            String line;
            while (iterator != null && (line = iterator.next()) != null) {
                if (budget != null) budget.consume(line);
                if (line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 8) {
                    throw new IOException("gnomAD in-process tabix returned a malformed VCF record");
                }
                Integer position = parseInteger(columns[1]);
                if (position == null) {
                    throw new IOException(
                            "gnomAD in-process tabix returned a VCF record with an invalid position");
                }
                List<NormalizedVariantKey> candidates = window.keysByPosition.get(position);
                if (candidates == null) {
                    continue;
                }
                for (NormalizedVariantKey key : new LinkedHashSet<>(candidates)) {
                    if (result.containsKey(key) && budget == null) {
                        continue;
                    }
                    EvidenceItem evidence = parseColumns(file, columns, key);
                    if (evidence != null) {
                        if (budget != null && result.containsKey(key)) throw new IOException("GNOMAD_LOCAL_DUPLICATE_EXACT_RECORD");
                        result.put(key, evidence);
                    }
                }
            }
        }
    }

    private EvidenceItem parseColumns(Path file, String[] columns, NormalizedVariantKey key) {
        String[] alternates = columns[4].split(",");
        int alternateIndex = exactAlternateIndex(columns, alternates, key);
        if (alternateIndex < 0) {
            return null;
        }
        if (properties.getGnomad().isPopulationInputEnabled()) {
            return populationInputEvidence(file, columns, alternateIndex, alternates.length);
        }
        FrequencyMatch frequency = maximumFrequency(parseInfo(columns[7]), alternateIndex);
        if (frequency.value == null) {
            return failedEvidence("Matched gnomAD record has no configured allele-frequency field");
        }
        return applicableEvidence(file, frequency, baseDetails(file, "full-vcf"));
    }

    private EvidenceItem parseRemoteColumns(RemoteJointTarget target,
                                            String[] columns,
                                            NormalizedVariantKey key) {
        String[] alternates = columns[4].split(",");
        int alternateIndex = exactAlternateIndex(columns, alternates, key);
        if (alternateIndex < 0) {
            return null;
        }
        return populationInputEvidence(target, columns, alternateIndex, alternates.length);
    }

    private int exactAlternateIndex(String[] columns,
                                    String[] alternates,
                                    NormalizedVariantKey key) {
        for (int index = 0; index < alternates.length; index++) {
            if (SpecialistProviderSupport.matchesCoordinates(
                    columns[0], columns[1], columns[3], alternates[index], key)) {
                return index;
            }
        }
        return -1;
    }

    private EvidenceItem populationInputEvidence(Path file,
                                                  String[] columns,
                                                  int alternateIndex,
                                                  int alternateCount) {
        return populationInputEvidence(
                baseDetails(file, "full-vcf-population-input"),
                columns,
                alternateIndex,
                alternateCount
        );
    }

    private EvidenceItem populationInputEvidence(RemoteJointTarget target,
                                                  String[] columns,
                                                  int alternateIndex,
                                                  int alternateCount) {
        return populationInputEvidence(
                baseRemoteDetails(target),
                columns,
                alternateIndex,
                alternateCount
        );
    }

    private EvidenceItem populationInputEvidence(Map<String, Object> details,
                                                  String[] columns,
                                                  int alternateIndex,
                                                  int alternateCount) {
        if (populationInputProfile == null) {
            return failedEvidence("gnomAD population input profile is unavailable");
        }
        GnomadPopulationInputProfile.Result input = populationInputProfile.parse(
                columns[6], parseInfo(columns[7]), alternateIndex, alternateCount);
        if (retainBa1RawRecord || populationInputProfile.getDocument().at("/outputPolicy/retainRawQueryRecord").asBoolean(false))
            details.put("rawPopulationRecord", String.join("\t", columns));
        if (localPopulationInProcess) {
            details.put("rawPopulationRecord", String.join("\t", columns));
            details.put("matchedAlternateIndex", alternateIndex);
            details.put("recordAlternateCount", alternateCount);
        }
        addPopulationProfileDetails(details);
        details.put("populationInputQualityStatus", input.getInputQualityStatus());
        details.put("populationInputReadinessStatus", input.getInputReadinessStatus());
        details.put("populationInputReadinessReasonCodes", input.getReasonCodes());
        details.put("populationMeasurements", input.getMeasurements());
        details.put("criterionEvaluationEnabled",
                populationInputProfile.isCriterionEvaluationEnabled());
        details.put("legacySuggestionEligible", Boolean.FALSE);
        putRuntimeAxes(details, "SUCCEEDED", null, "AVAILABLE", null,
                "HIT", "APPLICABLE", null);
        return new EvidenceItem(
                source(), version(), "POPULATION", "APPLICABLE", null,
                "gnomAD population input",
                "Exact gnomAD population fields prepared without evaluating an ACMG criterion",
                details
        );
    }

    private EvidenceItem applicableEvidence(Path file,
                                            FrequencyMatch frequency,
                                            Map<String, Object> details) {
        details.put("maximumAlleleFrequency", frequency.value);
        details.put("frequencyField", frequency.field);
        details.put("value", frequency.value);
        putRuntimeAxes(details, "SUCCEEDED", null, "AVAILABLE", null, "HIT", "APPLICABLE", null);
        return new EvidenceItem(
                source(),
                version(),
                "POPULATION",
                "APPLICABLE",
                frequency.value,
                "Maximum allele frequency",
                "gnomAD local allele frequency",
                details
        );
    }

    private EvidenceItem noRecordEvidence(FileBatch batch) {
        boolean remote = batch.remoteTarget != null;
        List<Path> files = batch.files;
        Map<String, Object> details = remote
                ? baseRemoteDetails(batch.remoteTarget)
                : new LinkedHashMap<String, Object>();
        if (!remote) {
            details.put("source", "LOCAL_GNOMAD");
            details.put("timestamp", Instant.now().toString());
            details.put("querySource",
                    isCompactFile(files.isEmpty() ? null : files.get(0)) ? "compact" : "full-vcf");
            details.put("sourceDataset", files.isEmpty() ? "unknown" : sourceDataset(files.get(0)));
            details.put("gnomadVersion", version());
        }
        details.put("status", "NO_RECORD");
        details.put("interpretation", remote
                ? "No matching gnomAD joint record was found; this is not interpreted as AF=0"
                : "No matching gnomAD record was found; this is not interpreted as AF=0");
        if (properties.getGnomad().isPopulationInputEnabled() && populationInputProfile != null) {
            addPopulationProfileDetails(details);
            details.put("populationInputReadinessStatus", "NOT_READY");
            details.put("populationInputReadinessReasonCodes", List.of(
                    "NO_RECORD_WITHOUT_ALL_SITES_AN"));
            details.put("criterionEvaluationEnabled",
                    populationInputProfile.isCriterionEvaluationEnabled());
            details.put("legacySuggestionEligible", Boolean.FALSE);
        }
        putRuntimeAxes(details, "SUCCEEDED", null, "AVAILABLE", null, "NO_RECORD", "APPLICABLE", null);
        return new EvidenceItem(
                source(),
                version(),
                "POPULATION",
                "NO_RECORD",
                null,
                "No record",
                remote
                        ? "Variant has no record in the profile-pinned gnomAD joint target; "
                        + "absence and coverage are not established"
                        : properties.getGnomad().isPopulationInputEnabled()
                        ? "Variant has no record in the configured gnomAD GRCh38 full-sites exome indexes; "
                        + "absence and coverage are not established"
                        : "Variant has no record in the configured gnomAD GRCh38 compact indexes",
                details
        );
    }

    private EvidenceItem unavailableEvidence(String summary) {
        return unavailableEvidence(summary, "LOCAL_GNOMAD");
    }

    private EvidenceItem remoteUnavailableEvidence(String summary) {
        return unavailableEvidence(summary, "REMOTE_GNOMAD");
    }

    private EvidenceItem unavailableEvidence(String summary, String detailSource) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", detailSource);
        details.put("status", "UNAVAILABLE");
        details.put("interpretation", "gnomAD evidence is unavailable; absence is not interpreted as low risk");
        details.put("timestamp", Instant.now().toString());
        putRuntimeAxes(details, "NOT_RUN", "RESOURCE_UNAVAILABLE", "UNAVAILABLE",
                "RESOURCE_UNAVAILABLE", "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
        return new EvidenceItem(
                source(),
                version(),
                "POPULATION",
                "UNAVAILABLE",
                null,
                "Unavailable",
                summary,
                details
        );
    }

    private EvidenceItem disabledEvidence(String summary) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "LOCAL_GNOMAD");
        details.put("status", "DISABLED");
        details.put("interpretation", "gnomAD lookup is disabled; this is not a no-record result");
        details.put("timestamp", Instant.now().toString());
        putRuntimeAxes(details, "DISABLED", "PROVIDER_DISABLED", "UNKNOWN", "NOT_CHECKED",
                "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
        return new EvidenceItem(source(), version(), "POPULATION", "UNAVAILABLE", null,
                "Disabled", summary, details);
    }

    private EvidenceItem failedEvidence(String summary) {
        return failedEvidence(summary, null, null);
    }

    private EvidenceItem failedEvidence(String summary,
                                        String failureReason,
                                        Throwable failure) {
        return failedEvidence(summary, failureReason, failure, "LOCAL_GNOMAD");
    }

    private EvidenceItem failedEvidence(String summary,
                                        String failureReason,
                                        Throwable failure,
                                        String detailSource) {
        boolean timedOut = failure == null
                ? summary != null && summary.toLowerCase().contains("timed out")
                : SpecialistProviderSupport.isTimeout(failure);
        String status = timedOut ? "TIMEOUT" : "FAILED";
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", detailSource);
        details.put("status", status);
        details.put("interpretation", "gnomAD provider execution failed; this is not a no-record result");
        details.put("timestamp", Instant.now().toString());
        putRuntimeAxes(details, status, timedOut ? "PROVIDER_TIMEOUT"
                        : failureReason == null ? "PROVIDER_FAILED" : failureReason,
                "AVAILABLE", null, "NOT_QUERIED", "UNKNOWN", "NOT_EVALUATED");
        return new EvidenceItem(source(), version(), "POPULATION", "UNAVAILABLE", null,
                status, summary, details);
    }

    private EvidenceItem remoteFailedEvidence(String summary, Throwable failure) {
        return failedEvidence(summary, null, failure, "REMOTE_GNOMAD");
    }

    private Map<String, Object> baseDetails(Path file, String querySource) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "LOCAL_GNOMAD");
        details.put("status", "APPLICABLE");
        details.put("querySource", querySource);
        details.put("interpretation", properties.getGnomad().isPopulationInputEnabled()
                ? "Exact local gnomAD fields are prepared as input without evaluating a criterion"
                : "Maximum local gnomAD allele frequency is used as a rare-disease frequency gate");
        details.put("timestamp", Instant.now().toString());
        details.put("resourceFileName", file.getFileName().toString());
        details.put("sourceDataset", sourceDataset(file));
        details.put("gnomadVersion", version());
        return details;
    }

    private Map<String, Object> baseRemoteDetails(RemoteJointTarget target) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "REMOTE_GNOMAD");
        details.put("status", "APPLICABLE");
        details.put("querySource", "remote-joint-vcf-population-input");
        details.put("interpretation",
                "Exact profile-pinned remote gnomAD joint fields are prepared as input");
        details.put("timestamp", Instant.now().toString());
        details.put("resourceFileName", target.resourceFileName);
        details.put("resourceUri", target.dataUri);
        details.put("resourceIndexUri", target.indexUri);
        details.put("sourceDataset", "joint");
        details.put("gnomadVersion", version());
        return details;
    }

    private void addPopulationProfileDetails(Map<String, Object> details) {
        if (populationInputProfile == null) {
            return;
        }
        details.put("populationInputProfileId", populationInputProfile.getProfileId());
        details.put("populationInputProfileVersion", populationInputProfile.getProfileVersion());
        details.put("populationInputProfileSha256", populationInputProfile.getProfileSha256());
        details.put("populationResourceId", populationInputProfile.getPopulationResourceId());
        details.put("populationResourceIdentityFingerprintSha256",
                populationInputProfile.getPopulationResourceIdentityFingerprintSha256());
        details.put("populationDatasetType", populationInputProfile.getDatasetType());
    }

    private void markCompactRetrievalOnly(Map<String, Object> details) {
        details.put("populationInputReadinessStatus", "NOT_READY");
        details.put("populationInputReadinessReasonCodes",
                List.of("COMPACT_RETRIEVAL_ONLY"));
        details.put("criterionEvaluationEnabled", Boolean.FALSE);
        details.put("legacySuggestionEligible", Boolean.FALSE);
    }

    private void putRuntimeAxes(Map<String, Object> details,
                                String executionStatus,
                                String executionReason,
                                String availabilityStatus,
                                String availabilityReason,
                                String queryStatus,
                                String applicabilityStatus,
                                String applicabilityReason) {
        details.put("resourceExecutionStatus", executionStatus);
        if (executionReason != null) details.put("resourceExecutionReasonCode", executionReason);
        details.put("resourceAvailabilityStatus", availabilityStatus);
        if (availabilityReason != null) details.put("resourceAvailabilityReasonCode", availabilityReason);
        details.put("queryOutcomeStatus", queryStatus);
        details.put("resourceApplicabilityStatus", applicabilityStatus);
        if (applicabilityReason != null) details.put("resourceApplicabilityReasonCode", applicabilityReason);
    }

    private FrequencyMatch maximumFrequency(Map<String, String> info, int alternateIndex) {
        Double maximum = null;
        String maximumField = null;
        for (String field : properties.getGnomad().getAlleleFrequencyFields()) {
            Double value = valueAt(info.get(field), alternateIndex);
            if (value != null && (maximum == null || value > maximum)) {
                maximum = value;
                maximumField = field;
            }
        }
        for (Map.Entry<String, String> entry : info.entrySet()) {
            if (!looksLikeAlleleFrequencyField(entry.getKey())) {
                continue;
            }
            Double value = valueAt(entry.getValue(), alternateIndex);
            if (value != null && value >= 0.0d && value <= 1.0d
                    && (maximum == null || value > maximum)) {
                maximum = value;
                maximumField = entry.getKey();
            }
        }
        if (maximum == null) {
            Double computed = alleleFrequencyFromCounts(info, alternateIndex);
            if (computed != null) {
                maximum = computed;
                maximumField = "AC/AN";
            }
        }
        return new FrequencyMatch(maximum, maximumField);
    }

    private boolean looksLikeAlleleFrequencyField(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String normalized = key.toUpperCase();
        return "AF".equals(normalized)
                || normalized.startsWith("AF_")
                || normalized.endsWith("_AF")
                || normalized.contains("_AF_")
                || normalized.contains("ALLELE_FREQUENCY");
    }

    private Double alleleFrequencyFromCounts(Map<String, String> info, int alternateIndex) {
        Double alleleCount = valueAt(info.get("AC"), alternateIndex);
        Double alleleNumber = valueAt(info.get("AN"), 0);
        if (alleleCount == null || alleleNumber == null || alleleNumber <= 0.0d) {
            return null;
        }
        return alleleCount / alleleNumber;
    }

    private Double valueAt(String value, int alternateIndex) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String[] values = value.split(",");
        String selected = values.length > alternateIndex ? values[alternateIndex] : value;
        if (!StringUtils.hasText(selected) || ".".equals(selected)) {
            return null;
        }
        return SpecialistProviderSupport.parseDouble(selected);
    }

    private Map<String, String> parseInfo(String value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!StringUtils.hasText(value)) {
            return result;
        }
        for (String token : value.split(";")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            int separator = token.indexOf('=');
            if (separator < 0) {
                result.put(token, "true");
            } else {
                result.put(token.substring(0, separator), token.substring(separator + 1));
            }
        }
        return result;
    }

    private Map<String, List<Path>> availableFilesByChromosome(List<Path> files) {
        Map<String, List<Path>> result = new LinkedHashMap<String, List<Path>>();
        for (Path file : files) {
            if (indexedFileAvailable(file)) {
                String chromosome = chromosomeFromFile(file);
                if (chromosome == null) {
                    LOGGER.warn("Skipping gnomAD file without chromosome in file name: {}", file);
                    continue;
                }
                result.computeIfAbsent(chromosome, ignored -> new ArrayList<Path>()).add(file);
            }
        }
        return result;
    }

    private List<Path> filesFor(Map<String, List<Path>> filesByChromosome, NormalizedVariantKey key) {
        List<String> candidates = chromosomeCandidates(key.getChromosome());
        for (String chromosome : candidates) {
            List<Path> files = filesByChromosome.get(chromosome);
            if (files != null && !files.isEmpty()) {
                return files;
            }
        }
        return Collections.emptyList();
    }

    static String chromosomeFromFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        Matcher matcher = GNOMAD_CHROMOSOME_PATTERN.matcher(file.getFileName().toString());
        if (!matcher.find()) {
            return null;
        }
        return normalizeChromosomeToken(matcher.group(1));
    }

    private List<String> chromosomeCandidates(String chromosome) {
        if (!StringUtils.hasText(chromosome)) {
            return Collections.emptyList();
        }
        String normalized = normalizeChromosomeToken(chromosome);
        if ("MT".equals(normalized)) {
            List<String> result = new ArrayList<String>();
            result.add("MT");
            result.add("M");
            return result;
        }
        return Collections.singletonList(normalized);
    }

    private List<String> tabixRegions(String chromosome, int start, int end) {
        List<String> result = new ArrayList<String>();
        result.add(region(chromosome, start, end));
        String prefixed = "MT".equals(chromosome) ? "chrM" : "chr" + chromosome;
        if (!prefixed.equals(chromosome)) {
            result.add(region(prefixed, start, end));
        }
        return result;
    }

    private String region(String chromosome, int start, int end) {
        return chromosome + ":" + start + "-" + end;
    }

    private static String normalizeChromosomeToken(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.toLowerCase().startsWith("chr")) {
            normalized = normalized.substring(3);
        }
        if ("M".equalsIgnoreCase(normalized) || "MT".equalsIgnoreCase(normalized)) {
            return "MT";
        }
        return normalized.toUpperCase();
    }

    private boolean indexedFileAvailable(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        Path tbi = file.resolveSibling(file.getFileName() + ".tbi");
        Path csi = file.resolveSibling(file.getFileName() + ".csi");
        return Files.isRegularFile(tbi) || Files.isRegularFile(csi);
    }

    private void addUnavailable(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                List<VariantInterpretation> variants,
                                String summary) {
        addEvidence(result, variants, unavailableEvidence(summary));
    }

    private void applyFailureToUnresolved(FileBatch batch,
                                          Throwable failure,
                                          Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                          String failureReason) {
        String summary = "gnomAD local lookup failed: " + SpecialistProviderSupport.rootMessage(failure);
        EvidenceItem failed = failedEvidence(summary, failureReason, failure);
        for (VariantInterpretation variant : batch.variants) {
            result.putIfAbsent(
                    variant.getObservation().getKey(),
                    Collections.singletonList(failed)
            );
        }
    }

    private void addEvidence(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                             List<VariantInterpretation> variants,
                             EvidenceItem evidence) {
        for (VariantInterpretation variant : variants) {
            result.put(
                    variant.getObservation().getKey(),
                    Collections.singletonList(evidence)
            );
        }
    }

    private void logLookupMetrics(List<VariantInterpretation> variants,
                                  Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                  long startedAtNanos,
                                  long startedMemoryBytes) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        long memoryDeltaBytes = usedMemoryBytes() - startedMemoryBytes;
        int applicable = 0;
        int compactHits = 0;
        int noRecord = 0;
        int unavailable = 0;
        for (List<EvidenceItem> evidenceItems : result.values()) {
            if (evidenceItems == null || evidenceItems.isEmpty()) {
                continue;
            }
            EvidenceItem evidence = evidenceItems.get(0);
            if ("APPLICABLE".equals(evidence.getApplicability())) {
                applicable++;
                if ("compact".equals(evidence.getDetails().get("querySource"))) {
                    compactHits++;
                }
            } else if ("NO_RECORD".equals(evidence.getApplicability())) {
                noRecord++;
            } else if ("UNAVAILABLE".equals(evidence.getApplicability())) {
                unavailable++;
            }
        }
        double averageMillis = variants.isEmpty()
                ? 0.0d
                : (double) elapsedMillis / (double) variants.size();
        LOGGER.info(
                "gnomAD lookup completed: inputVariants={}, queriedVariants={}, elapsedMs={}, "
                        + "avgMsPerVariant={}, applicable={}, compactHits={}, noRecord={}, unavailable={}, "
                        + "memoryDeltaMb={}",
                variants.size(),
                result.size(),
                elapsedMillis,
                String.format(java.util.Locale.ROOT, "%.3f", averageMillis),
                applicable,
                compactHits,
                noRecord,
                unavailable,
                String.format(java.util.Locale.ROOT, "%.3f", memoryDeltaBytes / 1024.0d / 1024.0d)
        );
    }

    private long usedMemoryBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private String sourceDataset(Path file) {
        String name = file == null || file.getFileName() == null
                ? ""
                : file.getFileName().toString().toLowerCase();
        if (name.contains("genomes")) {
            return "genomes";
        }
        if (name.contains("exomes")) {
            return "exomes";
        }
        return "unknown";
    }

    private String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) && !".".equals(value) ? value : fallback;
    }

    private String cacheKey(FileBatch batch, NormalizedVariantKey key) {
        String localIdentity = "";
        if (localPopulationInProcess) {
            var identities = new ArrayList<Map<String, Object>>();
            try { for (var file : batch.files) identities.add(localPopulationFileIdentity(file)); }
            catch (IOException | RuntimeException unavailable) { return "UNAVAILABLE:" + java.util.UUID.randomUUID(); }
            localIdentity = "|" + LOCAL_POPULATION_READER_VERSION + "|" + GnomadQueryRunner.queryIdentity(identities.toString());
        }
        return (properties.getGnomad().isPopulationInputEnabled() ? "POPULATION_INPUT" : "RETRIEVAL")
                + "|" + batch.cacheIdentity() + localIdentity + "|"
                + key.getGenomeAssembly() + "|"
                + key.getChromosome() + "|"
                + key.getPosition() + "|"
                + key.getReference() + "|"
                + key.getAlternate();
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static class FileBatch {

        private final List<Path> files;
        private final RemoteJointTarget remoteTarget;
        private final List<VariantInterpretation> variants = new ArrayList<VariantInterpretation>();

        private FileBatch(List<Path> files) {
            this.files = Collections.unmodifiableList(new ArrayList<Path>(files));
            this.remoteTarget = null;
        }

        private FileBatch(RemoteJointTarget remoteTarget) {
            this.files = Collections.emptyList();
            this.remoteTarget = Objects.requireNonNull(remoteTarget, "remoteTarget");
        }

        private FileBatch emptyCopy() {
            return remoteTarget == null ? new FileBatch(files) : new FileBatch(remoteTarget);
        }

        private String batchKey() {
            return cacheIdentity();
        }

        private String cacheIdentity() {
            return remoteTarget == null
                    ? "LOCAL|" + files
                    : "REMOTE|" + remoteTarget.cacheIdentity();
        }
    }

    private static class RemoteJointTarget {

        private final String chromosome;
        private final String resourceContig;
        private final String dataUri;
        private final String indexUri;
        private final String resourceFileName;

        private RemoteJointTarget(String chromosome,
                                  String resourceContig,
                                  String dataUri,
                                  String indexUri,
                                  String resourceFileName) {
            this.chromosome = chromosome;
            this.resourceContig = resourceContig;
            this.dataUri = dataUri;
            this.indexUri = indexUri;
            this.resourceFileName = resourceFileName;
        }

        private String cacheIdentity() {
            return chromosome + "|" + dataUri + "|" + indexUri;
        }
    }

    private static class FileBatchResult {

        private final FileBatch batch;
        private final Map<NormalizedVariantKey, EvidenceItem> evidenceByKey;

        private FileBatchResult(FileBatch batch,
                                Map<NormalizedVariantKey, EvidenceItem> evidenceByKey) {
            this.batch = batch;
            this.evidenceByKey = evidenceByKey;
        }
    }

    private static class QueryWindow {

        private final String chromosome;
        private final int start;
        private int end;
        private final Map<Integer, List<NormalizedVariantKey>> keysByPosition =
                new TreeMap<Integer, List<NormalizedVariantKey>>();

        private QueryWindow(String chromosome, int start) {
            this.chromosome = chromosome;
            this.start = start;
            this.end = start;
        }

        private void add(NormalizedVariantKey key) {
            end = Math.max(end, key.getPosition());
            keysByPosition.computeIfAbsent(key.getPosition(), ignored -> new ArrayList<NormalizedVariantKey>())
                    .add(key);
        }
    }

    private static class FrequencyMatch {

        private final Double value;
        private final String field;

        private FrequencyMatch(Double value, String field) {
            this.value = value;
            this.field = field;
        }
    }
}
