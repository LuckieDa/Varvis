package org.nbscn.varvis.variantanalysis.provider;

import htsjdk.tribble.readers.TabixReader;
import org.nbscn.varvis.utils.CoordinateUtils;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CaddTabixProvider implements VariantEvidenceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaddTabixProvider.class);

    private final VariantAnalysisProperties properties;
    private final ConcurrentMap<String, EvidenceItem> cache = new ConcurrentHashMap<String, EvidenceItem>();

    public CaddTabixProvider(VariantAnalysisProperties properties) {
        this.properties = properties;
    }

    @Override
    public String source() {
        return "CADD";
    }

    @Override
    public String version() {
        return properties.getCadd().getVersion();
    }

    @Override
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants,
            AnalysisContext context) {
        long started = System.nanoTime();
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!ProviderAssemblySupport.supportsOnlyGrch38(variants, context)) {
            Map<NormalizedVariantKey, List<EvidenceItem>> unsupported =
                    ProviderAssemblySupport.unsupportedForGrch38Only(
                            variants,
                            context,
                            source(),
                            version(),
                            "COMPUTATIONAL",
                            "CADD GRCh38 local files"
                    );
            logMetrics(variants == null ? 0 : variants.size(), 0, 0, unsupported, started);
            return unsupported;
        }
        if (!properties.getCadd().isEnabled()) {
            addUnavailable(result, variants, "CADD local lookup is disabled");
            logMetrics(variants.size(), 0, 0, result, started);
            return result;
        }

        List<VariantInterpretation> lookupCandidates = applyProviderSafetyLimit(variants, result);
        Map<Path, List<VariantInterpretation>> variantsByFile =
                new LinkedHashMap<Path, List<VariantInterpretation>>();
        int cacheHits = 0;
        for (VariantInterpretation variant : lookupCandidates) {
            Path sourceFile = sourceFile(variant);
            NormalizedVariantKey key = variant.getObservation().getKey();
            if (sourceFile == null || !Files.isRegularFile(sourceFile)
                    || !Files.isRegularFile(sourceFile.resolveSibling(sourceFile.getFileName() + ".tbi"))) {
                result.put(
                        key,
                        Collections.singletonList(EvidenceItem.unavailable(
                                source(),
                                version(),
                                "COMPUTATIONAL",
                                "Configured CADD local file or Tabix index is unavailable"
                        ))
                );
                continue;
            }
            EvidenceItem cached = cache.get(cacheKey(sourceFile, key));
            if (cached != null) {
                cacheHits++;
                result.put(key, Collections.singletonList(cached));
                continue;
            }
            variantsByFile.computeIfAbsent(sourceFile, ignored -> new ArrayList<VariantInterpretation>())
                    .add(variant);
        }

        int queuedForLookup = 0;
        for (Map.Entry<Path, List<VariantInterpretation>> entry : variantsByFile.entrySet()) {
            queuedForLookup += entry.getValue().size();
            annotateFile(entry.getKey(), entry.getValue(), result);
        }
        logMetrics(variants.size(), queuedForLookup, cacheHits, result, started);
        return result;
    }

    private List<VariantInterpretation> applyProviderSafetyLimit(
            List<VariantInterpretation> variants,
            Map<NormalizedVariantKey, List<EvidenceItem>> result) {
        int maxCandidates = properties.getCadd().normalizedMaxCandidates();
        if (maxCandidates <= 0 || variants.size() <= maxCandidates) {
            return variants;
        }
        String reason = "Skipped by CADD provider safety limit (max "
                + maxCandidates
                + "); CADD is auxiliary evidence and was not queried for this variant";
        for (int index = maxCandidates; index < variants.size(); index++) {
            VariantInterpretation variant = variants.get(index);
            result.put(
                    variant.getObservation().getKey(),
                    Collections.singletonList(skippedEvidence(reason))
            );
        }
        LOGGER.info(
                "CADD provider safety limit applied: input={}, queried={}, skipped={}",
                variants.size(),
                maxCandidates,
                variants.size() - maxCandidates
        );
        return new ArrayList<VariantInterpretation>(variants.subList(0, maxCandidates));
    }

    private void annotateFile(Path sourceFile,
                              List<VariantInterpretation> variants,
                              Map<NormalizedVariantKey, List<EvidenceItem>> result) {
        try {
            Map<NormalizedVariantKey, EvidenceItem> evidenceByKey = batchLookup(sourceFile, variants);
            for (VariantInterpretation variant : variants) {
                NormalizedVariantKey key = variant.getObservation().getKey();
                EvidenceItem evidence = evidenceByKey.get(key);
                EvidenceItem resolved = evidence == null ? absentEvidence() : evidence;
                cacheResolved(sourceFile, key, resolved);
                result.put(key, Collections.singletonList(resolved));
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("CADD batch tabix lookup failed for {}; evaluating fallback: {}",
                    sourceFile, rootMessage(ex));
            int fallbackLimit = properties.getCadd().normalizedFallbackMaxVariants();
            if (fallbackLimit <= 0 || variants.size() > fallbackLimit) {
                addUnavailable(
                        result,
                        variants,
                        "CADD batch lookup failed and slow fallback was skipped for "
                                + variants.size()
                                + " variants (fallback max "
                                + fallbackLimit
                                + "): "
                                + rootMessage(ex)
                );
                return;
            }
            annotateFileInProcess(sourceFile, variants, result);
        }
    }

    private void annotateFileInProcess(Path sourceFile,
                                       List<VariantInterpretation> variants,
                                       Map<NormalizedVariantKey, List<EvidenceItem>> result) {
        Map<NormalizedVariantKey, EvidenceItem> evidenceByKey =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        try (TabixReader reader = new TabixReader(sourceFile.toString())) {
            for (Map.Entry<String, List<VariantInterpretation>> entry : byChromosome(variants).entrySet()) {
                annotateChromosome(reader, entry.getKey(), entry.getValue(), evidenceByKey);
            }
            for (VariantInterpretation variant : variants) {
                NormalizedVariantKey key = variant.getObservation().getKey();
                EvidenceItem evidence = evidenceByKey.get(key);
                EvidenceItem resolved = evidence == null ? absentEvidence() : evidence;
                cacheResolved(sourceFile, key, resolved);
                result.put(key, Collections.singletonList(resolved));
            }
        } catch (IOException | RuntimeException ex) {
            addUnavailable(result, variants, "CADD local lookup failed: " + rootMessage(ex));
        }
    }

    private void cacheResolved(Path sourceFile, NormalizedVariantKey key, EvidenceItem evidence) {
        if (evidence != null
                && ("APPLICABLE".equals(evidence.getApplicability())
                || "NO_RECORD".equals(evidence.getApplicability()))) {
            cache.put(cacheKey(sourceFile, key), evidence);
        }
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookup(Path sourceFile,
                                                               List<VariantInterpretation> variants)
            throws IOException {
        Map<String, List<VariantInterpretation>> variantsByChromosome = byChromosome(variants);
        int parallelism = Math.min(
                Math.max(1, properties.getCadd().getParallelism()),
                variantsByChromosome.size()
        );
        if (parallelism <= 1 || variantsByChromosome.size() <= 1) {
            return batchLookupSequential(sourceFile, variants);
        }

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<Map<NormalizedVariantKey, EvidenceItem>>> futures =
                    new ArrayList<Future<Map<NormalizedVariantKey, EvidenceItem>>>();
            for (List<VariantInterpretation> chromosomeVariants : variantsByChromosome.values()) {
                futures.add(executor.submit(() -> batchLookupSequential(sourceFile, chromosomeVariants)));
            }
            Map<NormalizedVariantKey, EvidenceItem> result =
                    new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
            for (Future<Map<NormalizedVariantKey, EvidenceItem>> future : futures) {
                result.putAll(future.get());
            }
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("CADD tabix parallel batch lookup was interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException("CADD tabix parallel batch lookup failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupSequential(Path sourceFile,
                                                                         List<VariantInterpretation> variants)
            throws IOException {
        String tabixExecutable = properties.getCadd().getTabixExecutable();
        if (!StringUtils.hasText(tabixExecutable)) {
            throw new IOException("variant-analysis.cadd.tabix-executable is not configured");
        }
        Map<String, List<NormalizedVariantKey>> keysByPosition = keysByPosition(variants);
        if (keysByPosition.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<NormalizedVariantKey, EvidenceItem> result =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        List<Map.Entry<String, List<NormalizedVariantKey>>> entries =
                new ArrayList<Map.Entry<String, List<NormalizedVariantKey>>>(keysByPosition.entrySet());
        int batchSize = properties.getCadd().normalizedBatchSize();
        for (int start = 0; start < entries.size(); start += batchSize) {
            int end = Math.min(start + batchSize, entries.size());
            Map<String, List<NormalizedVariantKey>> chunk =
                    new TreeMap<String, List<NormalizedVariantKey>>();
            for (int index = start; index < end; index++) {
                Map.Entry<String, List<NormalizedVariantKey>> entry = entries.get(index);
                chunk.put(entry.getKey(), entry.getValue());
            }
            result.putAll(batchLookupChunk(sourceFile, tabixExecutable, chunk));
        }
        return result;
    }

    private Map<NormalizedVariantKey, EvidenceItem> batchLookupChunk(
            Path sourceFile,
            String tabixExecutable,
            Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        Path regions = Files.createTempFile("varvis-cadd-regions-", ".bed");
        Path output = Files.createTempFile("varvis-cadd-output-", ".tsv");
        try {
            writeRegions(regions, keysByPosition);
            ProcessBuilder builder = new ProcessBuilder(
                    tabixExecutable,
                    "-R",
                    regions.toString(),
                    sourceFile.toAbsolutePath().normalize().toString()
            );
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
            boolean completed;
            try {
                Duration timeout = properties.getCadd().normalizedBatchTimeout();
                completed = process.waitFor(
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("CADD tabix batch lookup was interrupted", ex);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("CADD tabix batch lookup timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("CADD tabix batch lookup failed with exit code " + process.exitValue());
            }
            return parseBatchOutput(output, keysByPosition);
        } finally {
            Files.deleteIfExists(regions);
            Files.deleteIfExists(output);
        }
    }

    private Map<String, List<NormalizedVariantKey>> keysByPosition(List<VariantInterpretation> variants) {
        Map<String, List<NormalizedVariantKey>> result =
                new TreeMap<String, List<NormalizedVariantKey>>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            String positionKey = positionKey(key.getChromosome(), key.getPosition());
            result.computeIfAbsent(positionKey, ignored -> new ArrayList<NormalizedVariantKey>())
                    .add(key);
        }
        return result;
    }

    private void writeRegions(Path regions, Map<String, List<NormalizedVariantKey>> keysByPosition)
            throws IOException {
        List<String> lines = new ArrayList<String>(keysByPosition.size());
        for (List<NormalizedVariantKey> keys : keysByPosition.values()) {
            NormalizedVariantKey key = keys.get(0);
            int zeroBasedStart = Math.max(0, key.getPosition() - 1);
            lines.add(key.getChromosome() + "\t" + zeroBasedStart + "\t" + key.getPosition());
        }
        Files.write(regions, lines, StandardCharsets.UTF_8);
    }

    private Map<NormalizedVariantKey, EvidenceItem> parseBatchOutput(
            Path output,
            Map<String, List<NormalizedVariantKey>> keysByPosition) throws IOException {
        Map<NormalizedVariantKey, EvidenceItem> result =
                new LinkedHashMap<NormalizedVariantKey, EvidenceItem>();
        int maxRows = Math.max(1000, keysByPosition.size() * 50);
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                rows++;
                if (rows > maxRows) {
                    throw new IOException("CADD tabix output exceeded safety row limit "
                            + maxRows
                            + " for "
                            + keysByPosition.size()
                            + " queried positions");
                }
                String[] columns = line.split("\t");
                if (columns.length < 6) {
                    continue;
                }
                Integer position = parseInteger(columns[1]);
                if (position == null) {
                    continue;
                }
                String chromosome = CoordinateUtils.normalizeChromosome(columns[0]);
                List<NormalizedVariantKey> candidates = keysByPosition.get(positionKey(chromosome, position));
                if (candidates == null) {
                    continue;
                }
                for (NormalizedVariantKey key : candidates) {
                    if (!key.getReference().equalsIgnoreCase(columns[2])
                            || !key.getAlternate().equalsIgnoreCase(columns[3])) {
                        continue;
                    }
                    result.put(key, applicableEvidence(columns));
                }
            }
        }
        return result;
    }

    private String positionKey(String chromosome, int position) {
        return chromosome + ":" + position;
    }

    private Map<String, List<VariantInterpretation>> byChromosome(List<VariantInterpretation> variants) {
        Map<String, List<VariantInterpretation>> result =
                new LinkedHashMap<String, List<VariantInterpretation>>();
        for (VariantInterpretation variant : variants) {
            String chromosome = variant.getObservation().getKey().getChromosome();
            result.computeIfAbsent(chromosome, key -> new ArrayList<VariantInterpretation>())
                    .add(variant);
        }
        return result;
    }

    private void annotateChromosome(TabixReader reader,
                                    String chromosome,
                                    List<VariantInterpretation> variants,
                                    Map<NormalizedVariantKey, EvidenceItem> result) throws IOException {
        List<VariantInterpretation> sorted = new ArrayList<VariantInterpretation>(variants);
        sorted.sort(Comparator.comparingInt(
                variant -> variant.getObservation().getKey().getPosition()
        ));
        for (QueryWindow window : windows(chromosome, sorted)) {
            queryWindow(reader, window, result);
        }
    }

    private List<QueryWindow> windows(String chromosome, List<VariantInterpretation> sortedVariants) {
        List<QueryWindow> windows = new ArrayList<QueryWindow>();
        int maxWindowBp = Math.max(0, properties.getCadd().getQueryWindowBp());
        int maxMergeGapBp = Math.max(0, properties.getCadd().getQueryMergeGapBp());
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
                             QueryWindow window,
                             Map<NormalizedVariantKey, EvidenceItem> result) throws IOException {
        String region = window.chromosome + ":" + window.start + "-" + window.end;
        TabixReader.Iterator iterator = reader.query(region);
        String line;
        while (iterator != null && (line = iterator.next()) != null) {
            String[] columns = line.split("\t");
            if (columns.length < 6) {
                continue;
            }
            Integer position = parseInteger(columns[1]);
            if (position == null) {
                continue;
            }
            List<NormalizedVariantKey> candidates = window.keysByPosition.get(position);
            if (candidates == null) {
                continue;
            }
            for (NormalizedVariantKey key : candidates) {
                if (!key.getReference().equalsIgnoreCase(columns[2])
                        || !key.getAlternate().equalsIgnoreCase(columns[3])) {
                    continue;
                }
                result.put(key, applicableEvidence(columns));
            }
        }
    }

    private EvidenceItem applicableEvidence(String[] columns) {
        Double rawScore = parseDouble(columns[4]);
        Double phred = parseDouble(columns[5]);
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("rawScore", rawScore);
        details.put("phred", phred);
        return new EvidenceItem(
                source(),
                version(),
                "COMPUTATIONAL",
                "APPLICABLE",
                phred,
                "CADD PHRED",
                "CADD PHRED score is a ranking aid, not a clinical classification",
                details
        );
    }

    private EvidenceItem absentEvidence() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("status", "NO_RECORD");
        details.put("interpretation", "No matching allele was found in the configured CADD file");
        return new EvidenceItem(
                source(),
                version(),
                "COMPUTATIONAL",
                "NO_RECORD",
                null,
                "No CADD record",
                "Variant is absent from the configured precomputed CADD file",
                details
        );
    }

    private EvidenceItem skippedEvidence(String reason) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("preScreen", "CADD_PROVIDER_LIMIT");
        details.put("reason", reason);
        return new EvidenceItem(
                source(),
                version(),
                "COMPUTATIONAL",
                "NOT_APPLICABLE",
                null,
                "CADD lookup skipped",
                reason,
                details
        );
    }

    private Path sourceFile(VariantInterpretation variant) {
        String variantType = variant.getObservation().getVariantType();
        return "SNV".equals(variantType)
                ? properties.getCadd().getGrch38SnvFile()
                : properties.getCadd().getGrch38IndelFile();
    }

    private void addUnavailable(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                List<VariantInterpretation> variants,
                                String summary) {
        for (VariantInterpretation variant : variants) {
            result.put(
                    variant.getObservation().getKey(),
                    Collections.singletonList(EvidenceItem.unavailable(
                            source(),
                            version(),
                            "COMPUTATIONAL",
                            summary
                    ))
            );
        }
    }

    private void logMetrics(int inputVariants,
                            int queuedForLookup,
                            int cacheHits,
                            Map<NormalizedVariantKey, List<EvidenceItem>> result,
                            long startedAtNanos) {
        int applicable = 0;
        int noRecord = 0;
        int unavailable = 0;
        int notApplicable = 0;
        for (List<EvidenceItem> evidenceItems : result.values()) {
            if (evidenceItems == null || evidenceItems.isEmpty()) {
                continue;
            }
            String applicability = evidenceItems.get(0).getApplicability();
            if ("APPLICABLE".equals(applicability)) {
                applicable++;
            } else if ("NO_RECORD".equals(applicability)) {
                noRecord++;
            } else if ("UNAVAILABLE".equals(applicability)) {
                unavailable++;
            } else if ("NOT_APPLICABLE".equals(applicability)) {
                notApplicable++;
            }
        }
        LOGGER.info(
                "CADD provider lookup completed: input={}, queued={}, cacheHits={}, APPLICABLE={}, NO_RECORD={}, UNAVAILABLE={}, NOT_APPLICABLE={}, elapsedMs={}",
                inputVariants,
                queuedForLookup,
                cacheHits,
                applicable,
                noRecord,
                unavailable,
                notApplicable,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        );
    }

    private String cacheKey(Path sourceFile, NormalizedVariantKey key) {
        return sourceFile.toAbsolutePath().normalize() + "|"
                + key.getGenomeAssembly() + "|"
                + key.getChromosome() + "|"
                + key.getPosition() + "|"
                + key.getReference() + "|"
                + key.getAlternate();
    }

    private Double parseDouble(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
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

}
