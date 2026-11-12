package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.entity.variant.GeneCoordinateEntity;
import org.nbscn.varvis.service.variant.VariantGeneService;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PhenotypePriorGeneIntervalService {

    private final VariantAnalysisProperties properties;
    private final VariantGeneService variantGeneService;
    private final ConcurrentMap<IntervalResourceKey, IntervalLoadResult> intervalResourceCache =
            new ConcurrentHashMap<IntervalResourceKey, IntervalLoadResult>();

    @Autowired
    public PhenotypePriorGeneIntervalService(VariantAnalysisProperties properties,
                                             ObjectProvider<VariantGeneService> variantGeneServiceProvider) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.variantGeneService = variantGeneServiceProvider == null
                ? null
                : variantGeneServiceProvider.getIfAvailable();
    }

    protected PhenotypePriorGeneIntervalService() {
        this.properties = new VariantAnalysisProperties();
        this.variantGeneService = null;
    }

    public Map<VariantInterpretation, GeneIntervalMatch> rescueMatches(
            List<VariantInterpretation> interpretations,
            AnalysisContext context) {
        return evaluateRescueMatches(interpretations, context).getMatches();
    }

    public RescueEvaluation evaluateRescueMatches(
            List<VariantInterpretation> interpretations,
            AnalysisContext context) {
        int totalCandidates = interpretations == null ? 0 : interpretations.size();
        String effectiveAssembly = effectiveAssembly(context);
        int candidateCap = properties.getLargeFile().normalizedCandidateHardLimit();
        if (!isEnabled(context)) {
            return RescueEvaluation.empty(
                    totalCandidates,
                    "DISABLED",
                    "DISABLED",
                    effectiveAssembly,
                    candidateCap
            );
        }
        if (interpretations == null || interpretations.isEmpty()) {
            return RescueEvaluation.empty(
                    totalCandidates,
                    phenotypePriorStatus(context == null ? null : context.getPhenotypeSummary()),
                    "NOT_EVALUATED",
                    effectiveAssembly,
                    candidateCap
            );
        }
        List<GenePhenotypePrior> priors = priorGenes(context.getPhenotypeSummary());
        if (priors.isEmpty()) {
            return RescueEvaluation.empty(
                    totalCandidates,
                    phenotypePriorStatus(context.getPhenotypeSummary()),
                    "NO_PHENOTYPE_PRIOR",
                    effectiveAssembly,
                    candidateCap
            );
        }
        IntervalLoadResult intervalResult =
                intervalsByGene(priors, context.getGenomeAssembly());
        Map<String, List<GeneInterval>> intervalsByGene = intervalResult.intervals;
        if (intervalsByGene.isEmpty()) {
            return new RescueEvaluation(
                    Collections.<VariantInterpretation, GeneIntervalMatch>emptyMap(),
                    totalCandidates,
                    "AVAILABLE",
                    priors.size(),
                    intervalResult.status,
                    intervalResult.sourceVersion,
                    0,
                    0,
                    priors.size(),
                    effectiveAssembly,
                    candidateCap,
                    intervalResult.warnings
            );
        }
        IntervalLookup intervalLookup = IntervalLookup.from(intervalsByGene);
        MatchSelection selection = selectMatches(interpretations, intervalLookup);
        Map<VariantInterpretation, GeneIntervalMatch> matches = selection.matches;
        List<String> warnings = new ArrayList<String>(intervalResult.warnings);
        if (matches.size() < selection.potentialMatchCount) {
            warnings.add("PHENOTYPE_PRIOR_RESCUE_PER_GENE_CAP: "
                    + (selection.potentialMatchCount - matches.size())
                    + " overlapping variant(s) were not protected by the per-gene rescue cap.");
        }
        int mappedPriorGeneCount = intervalsByGene.keySet().size();
        return new RescueEvaluation(
                matches,
                totalCandidates,
                "AVAILABLE",
                priors.size(),
                intervalResult.status,
                intervalResult.sourceVersion,
                mappedPriorGeneCount,
                matches.size(),
                Math.max(0, priors.size() - mappedPriorGeneCount),
                effectiveAssembly,
                candidateCap,
                warnings
        );
    }

    private boolean isEnabled(AnalysisContext context) {
        return context != null
                && context.isLargeFileMode()
                && properties.getLargeFile().isEnabled()
                && properties.getLargeFile().isPhenotypePriorRescueEnabled();
    }

    private List<GenePhenotypePrior> priorGenes(PhenotypeAwareSummary phenotypeSummary) {
        if (phenotypeSummary == null || !phenotypeSummary.isEnabled()) {
            return Collections.emptyList();
        }
        GenePhenotypePriorSummary priorSummary = rescuePriorSummary(phenotypeSummary);
        if (priorSummary == null
                || priorSummary.getStatus() != GenePhenotypePriorStatus.AVAILABLE
                || priorSummary.getTopGenePriors().isEmpty()) {
            return Collections.emptyList();
        }
        int max = properties.getLargeFile().normalizedPhenotypePriorRescueMaxGenes();
        if (max <= 0) {
            return Collections.emptyList();
        }
        List<GenePhenotypePrior> result = new ArrayList<GenePhenotypePrior>();
        Set<String> seen = new LinkedHashSet<String>();
        for (GenePhenotypePrior prior : priorSummary.getTopGenePriors()) {
            if (prior == null || !StringUtils.hasText(prior.getGeneSymbol())) {
                continue;
            }
            String normalized = normalizeGene(prior.getGeneSymbol());
            if (seen.add(normalized)) {
                result.add(prior);
            }
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    private GenePhenotypePriorSummary rescuePriorSummary(PhenotypeAwareSummary phenotypeSummary) {
        GenePhenotypePriorSummary summary = phenotypeSummary == null
                ? null
                : phenotypeSummary.getGenePriorSummary();
        int maximum = properties.getLargeFile().normalizedPhenotypePriorRescueMaxGenes();
        if (summary == null
                || summary.getStatus() != GenePhenotypePriorStatus.AVAILABLE
                || maximum <= summary.getTopGenePriors().size()) {
            return summary;
        }
        VariantAnalysisProperties.PhenotypePrior configured = properties.getPhenotypePrior();
        VariantAnalysisProperties expandedProperties = new VariantAnalysisProperties();
        VariantAnalysisProperties.PhenotypePrior expanded = expandedProperties.getPhenotypePrior();
        expanded.setEnabled(configured.isEnabled());
        expanded.setSource(configured.getSource());
        expanded.setVersion(configured.getVersion());
        expanded.setMaxGenes(maximum);
        expanded.setFailOnMalformedRow(configured.isFailOnMalformedRow());
        expanded.setRequireMatchingHpo(configured.isRequireMatchingHpo());
        expanded.setFormat(configured.getFormat());
        expanded.setLocalFile(configured.getLocalFile() == null
                ? null
                : configured.getLocalFile().toString());
        expanded.setManifestFile(configured.getManifestFile() == null
                ? null
                : configured.getManifestFile().toString());
        GenePhenotypePriorSummary expandedSummary =
                GenePhenotypePriorProviderFactory.create(expandedProperties).summarize(phenotypeSummary);
        return expandedSummary.getStatus() == GenePhenotypePriorStatus.AVAILABLE
                ? expandedSummary
                : summary;
    }

    private String phenotypePriorStatus(PhenotypeAwareSummary phenotypeSummary) {
        if (phenotypeSummary == null || !phenotypeSummary.isEnabled()) {
            return "NO_HPO_INPUT";
        }
        GenePhenotypePriorSummary priorSummary = phenotypeSummary.getGenePriorSummary();
        if (priorSummary == null) {
            return "NO_PRIOR_SUMMARY";
        }
        return priorSummary.getStatus() == null ? "UNKNOWN" : priorSummary.getStatus().name();
    }

    private String effectiveAssembly(AnalysisContext context) {
        GenomeAssembly assembly = context == null ? null : context.getGenomeAssembly();
        return assembly == null ? "UNRESOLVED" : assembly.name();
    }

    private IntervalLoadResult intervalsByGene(List<GenePhenotypePrior> priors,
                                               GenomeAssembly assembly) {
        IntervalLoadResult intervalFileResult = intervalFileMatches(priors, assembly);
        Map<String, List<GeneInterval>> result =
                new LinkedHashMap<String, List<GeneInterval>>(intervalFileResult.intervals);
        addVarvisGeneCoordinateMatches(result, priors, assembly);
        String status = result.isEmpty() ? intervalFileResult.status : "AVAILABLE";
        return new IntervalLoadResult(
                result,
                status,
                intervalFileResult.sourceVersion,
                intervalFileResult.warnings
        );
    }

    private IntervalLoadResult intervalFileMatches(List<GenePhenotypePrior> priors,
                                                   GenomeAssembly assembly) {
        if (assembly == null) {
            return IntervalLoadResult.empty("ASSEMBLY_UNSUPPORTED", "UNRESOLVED");
        }
        Path intervalFile = properties.getLargeFile().getPhenotypePriorRescueIntervalFile();
        if (intervalFile == null || !Files.isRegularFile(intervalFile)) {
            return IntervalLoadResult.empty("INTERVAL_RESOURCE_UNAVAILABLE", sourceVersion(intervalFile));
        }
        IntervalResourceKey cacheKey = intervalResourceKey(intervalFile, assembly);
        IntervalLoadResult cached = intervalResourceCache.get(cacheKey);
        if (cached == null) {
            cached = loadIntervalResource(cacheKey, intervalFile, assembly);
            IntervalLoadResult existing = intervalResourceCache.putIfAbsent(cacheKey, cached);
            if (existing != null) {
                cached = existing;
            }
        }
        if (!"AVAILABLE".equals(cached.status)) {
            return cached;
        }
        Map<String, List<GeneInterval>> result = new LinkedHashMap<String, List<GeneInterval>>();
        for (GenePhenotypePrior prior : priors) {
            if (prior == null || !StringUtils.hasText(prior.getGeneSymbol())) {
                continue;
            }
            String gene = normalizeGene(prior.getGeneSymbol());
            List<GeneInterval> intervals = cached.intervals.get(gene);
            if (intervals == null) {
                continue;
            }
            for (GeneInterval interval : intervals) {
                addInterval(result, gene, interval.withPrior(prior));
            }
        }
        return new IntervalLoadResult(
                result,
                result.isEmpty() ? "EMPTY" : "AVAILABLE",
                cached.sourceVersion,
                cached.warnings
        );
    }

    private IntervalLoadResult loadIntervalResource(IntervalResourceKey cacheKey,
                                                    Path intervalFile,
                                                    GenomeAssembly assembly) {
        Map<String, List<GeneInterval>> result = new LinkedHashMap<String, List<GeneInterval>>();
        try (BufferedReader reader = Files.newBufferedReader(intervalFile, StandardCharsets.UTF_8)) {
            Header header = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] columns = split(trimmed);
                if (columns.length < 5) {
                    throw new InvalidIntervalFileException("Interval TSV row has fewer than 5 columns.");
                }
                if (header == null && looksLikeHeader(columns)) {
                    header = Header.from(columns);
                    continue;
                }
                GeneInterval interval = parseInterval(
                        columns,
                        header,
                        assembly,
                        Collections.<String>emptySet(),
                        Collections.<String, GenePhenotypePrior>emptyMap()
                );
                if (interval != null) {
                    addInterval(result, normalizeGene(interval.getGeneSymbol()), interval);
                }
            }
        } catch (InvalidIntervalFileException ex) {
            return IntervalLoadResult.warning("INVALID", cacheKey.sourceVersion, ex.getMessage());
        } catch (IOException ex) {
            return IntervalLoadResult.warning(
                    "INTERVAL_RESOURCE_UNAVAILABLE",
                    cacheKey.sourceVersion,
                    "Interval TSV could not be read: " + ex.getMessage()
            );
        }
        return new IntervalLoadResult(
                result,
                result.isEmpty() ? "EMPTY" : "AVAILABLE",
                cacheKey.sourceVersion,
                Collections.<String>emptyList()
        );
    }

    private IntervalResourceKey intervalResourceKey(Path intervalFile, GenomeAssembly assembly) {
        try {
            return new IntervalResourceKey(
                    intervalFile.toAbsolutePath().normalize().toString(),
                    Files.size(intervalFile),
                    Files.getLastModifiedTime(intervalFile).toMillis(),
                    assembly.name()
            );
        } catch (IOException ex) {
            return new IntervalResourceKey(
                    intervalFile.toAbsolutePath().normalize().toString(),
                    -1L,
                    -1L,
                    assembly.name()
            );
        }
    }

    private void addVarvisGeneCoordinateMatches(Map<String, List<GeneInterval>> result,
                                                List<GenePhenotypePrior> priors,
                                                GenomeAssembly assembly) {
        if (!properties.getLargeFile().isPhenotypePriorRescueUseVarvisGeneCoordinates()
                || variantGeneService == null
                || assembly == null
                || !assembly.name().equalsIgnoreCase(
                        properties.getLargeFile().getPhenotypePriorRescueGeneCoordinateAssembly())) {
            return;
        }
        List<String> unresolved = new ArrayList<String>();
        for (GenePhenotypePrior prior : priors) {
            String gene = normalizeGene(prior.getGeneSymbol());
            if (!result.containsKey(gene)) {
                unresolved.add(prior.getGeneSymbol());
            }
        }
        if (unresolved.isEmpty()) {
            return;
        }
        List<GeneCoordinateEntity> coordinates = variantGeneService.listGeneCoordinates(unresolved);
        Map<String, GenePhenotypePrior> priorByGene = priorByGene(priors);
        for (GeneCoordinateEntity coordinate : coordinates) {
            if (coordinate == null
                    || !StringUtils.hasText(coordinate.getGeneSymbol())
                    || !StringUtils.hasText(coordinate.getChromosome())
                    || coordinate.getStartBp() == null
                    || coordinate.getEndBp() == null) {
                continue;
            }
            String gene = normalizeGene(coordinate.getGeneSymbol());
            GenePhenotypePrior prior = priorByGene.get(gene);
            if (prior == null) {
                continue;
            }
            addInterval(result, gene, new GeneInterval(
                    coordinate.getGeneSymbol(),
                    coordinate.getChromosome(),
                    coordinate.getStartBp().longValue(),
                    coordinate.getEndBp().longValue(),
                    prior.getPhenotypeRank(),
                    prior.getPhenotypeScore(),
                    "VARVIS_GENE_COORDINATE",
                    "GENE"
            ));
        }
    }

    private GeneInterval parseInterval(String[] columns,
                                       Header header,
                                       GenomeAssembly requestedAssembly,
                                       Set<String> priorGenes,
                                       Map<String, GenePhenotypePrior> priorByGene) {
        String assembly = value(columns, header, "assembly", 0);
        String gene = value(columns, header, "gene", 1);
        String chromosome = value(columns, header, "chromosome", 2);
        String startText = value(columns, header, "start", 3);
        String endText = value(columns, header, "end", 4);
        String featureType = value(columns, header, "feature_type", 5);
        if (!StringUtils.hasText(assembly)
                || requestedAssembly == null
                || !requestedAssembly.name().equalsIgnoreCase(assembly.trim())
                || !StringUtils.hasText(gene)
                || (priorGenes != null
                && !priorGenes.isEmpty()
                && !priorGenes.contains(normalizeGene(gene)))
                || !StringUtils.hasText(chromosome)
                || !StringUtils.hasText(startText)
                || !StringUtils.hasText(endText)) {
            return null;
        }
        Long start = parseLong(startText);
        Long end = parseLong(endText);
        if (start == null || end == null) {
            throw new InvalidIntervalFileException("Interval TSV row has invalid coordinates.");
        }
        GenePhenotypePrior prior = priorByGene == null ? null : priorByGene.get(normalizeGene(gene));
        return new GeneInterval(
                gene,
                chromosome,
                start.longValue(),
                end.longValue(),
                prior == null ? 0 : prior.getPhenotypeRank(),
                prior == null ? 0.0d : prior.getPhenotypeScore(),
                "INTERVAL_FILE",
                normalizeFeatureType(featureType)
        );
    }

    private GeneIntervalMatch match(VariantInterpretation interpretation,
                                    IntervalLookup intervalLookup) {
        VariantQcObservation observation = interpretation == null ? null : interpretation.getObservation();
        NormalizedVariantKey key = observation == null ? null : observation.getKey();
        if (key == null || key.getPosition() <= 0 || !StringUtils.hasText(key.getChromosome())) {
            return null;
        }
        String chromosome = normalizeChromosome(key.getChromosome());
        long start = key.getPosition();
        long end = start + Math.max(1, safeLength(key.getReference())) - 1;
        int padding = properties.getLargeFile().normalizedPhenotypePriorRescuePaddingBp();
        GeneInterval interval = intervalLookup.bestMatch(chromosome, start, end, padding);
        return interval == null ? null : new GeneIntervalMatch(
                interval.getGeneSymbol(),
                interval.getChromosome(),
                interval.getStart(),
                interval.getEnd(),
                interval.getPhenotypeRank(),
                interval.getPhenotypeScore(),
                interval.getSource(),
                interval.getFeatureType()
        );
    }

    private MatchSelection selectMatches(List<VariantInterpretation> interpretations,
                                         IntervalLookup intervalLookup) {
        int maximum = properties.getLargeFile().normalizedPhenotypePriorRescueMaxVariantsPerGene();
        Map<String, List<VariantIntervalMatch>> byGene =
                new LinkedHashMap<String, List<VariantIntervalMatch>>();
        Map<VariantInterpretation, GeneIntervalMatch> unlimited =
                maximum <= 0
                        ? new IdentityHashMap<VariantInterpretation, GeneIntervalMatch>()
                        : null;
        int potentialMatchCount = 0;
        for (VariantInterpretation interpretation : interpretations) {
            GeneIntervalMatch match = match(interpretation, intervalLookup);
            if (match == null) {
                continue;
            }
            potentialMatchCount++;
            if (unlimited != null) {
                unlimited.put(interpretation, match);
                continue;
            }
            String gene = normalizeGene(match.getGeneSymbol());
            List<VariantIntervalMatch> values = byGene.get(gene);
            if (values == null) {
                values = new ArrayList<VariantIntervalMatch>();
                byGene.put(gene, values);
            }
            values.add(new VariantIntervalMatch(interpretation, match));
            Collections.sort(values, rescueCandidateComparator());
            if (values.size() > maximum) {
                values.remove(values.size() - 1);
            }
        }
        if (unlimited != null) {
            return new MatchSelection(unlimited, potentialMatchCount);
        }
        Map<VariantInterpretation, GeneIntervalMatch> result =
                new IdentityHashMap<VariantInterpretation, GeneIntervalMatch>();
        for (List<VariantIntervalMatch> values : byGene.values()) {
            for (VariantIntervalMatch value : values) {
                result.put(value.variant, value.match);
            }
        }
        return new MatchSelection(result, potentialMatchCount);
    }

    private Comparator<VariantIntervalMatch> rescueCandidateComparator() {
        return new Comparator<VariantIntervalMatch>() {
            @Override
            public int compare(VariantIntervalMatch left, VariantIntervalMatch right) {
                int feature = Integer.compare(
                        featurePriority(right.match.getFeatureType()),
                        featurePriority(left.match.getFeatureType())
                );
                if (feature != 0) {
                    return feature;
                }
                int quality = Long.compare(candidateConfidence(right.variant), candidateConfidence(left.variant));
                return quality != 0 ? quality : compareKeys(left.variant, right.variant);
            }
        };
    }

    private static int featurePriority(String featureType) {
        return "EXON".equalsIgnoreCase(featureType) ? 1 : 0;
    }

    private static String normalizeFeatureType(String featureType) {
        return "EXON".equalsIgnoreCase(featureType == null ? "" : featureType.trim())
                ? "EXON"
                : "GENE";
    }

    private long candidateConfidence(VariantInterpretation interpretation) {
        VariantQcObservation observation = interpretation == null ? null : interpretation.getObservation();
        if (observation == null) {
            return 0L;
        }
        long score = carriesAlt(observation.getGenotype()) ? 1_000_000_000_000L : 0L;
        if (isPass(observation.getFilterStatus())) {
            score += 100_000_000_000L;
        }
        score += Math.min(999, safeInt(observation.getGenotypeQuality())) * 1_000_000L;
        score += Math.min(100_000, safeInt(observation.getDepth())) * 10L;
        score += alleleBalanceConfidence(observation);
        return score;
    }

    private boolean carriesAlt(String genotype) {
        if (!StringUtils.hasText(genotype)) {
            return false;
        }
        for (String allele : genotype.replace('|', '/').split("/")) {
            if (!".".equals(allele.trim()) && !"0".equals(allele.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isPass(String filterStatus) {
        return !StringUtils.hasText(filterStatus)
                || ".".equals(filterStatus.trim())
                || "PASS".equalsIgnoreCase(filterStatus.trim());
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value.intValue());
    }

    private long alleleBalanceConfidence(VariantQcObservation observation) {
        Double balance = observation.getAlleleBalance();
        if (balance == null || Double.isNaN(balance.doubleValue()) || Double.isInfinite(balance.doubleValue())) {
            return 0L;
        }
        String genotype = observation.getGenotype() == null ? "" : observation.getGenotype();
        double expected = genotype.matches("[1-9][0-9]*[/|][1-9][0-9]*") ? 1.0d : 0.5d;
        return Math.max(0L, Math.round((1.0d - Math.min(1.0d, Math.abs(balance - expected))) * 1000.0d));
    }

    private int compareKeys(VariantInterpretation left, VariantInterpretation right) {
        NormalizedVariantKey leftKey = left == null || left.getObservation() == null
                ? null
                : left.getObservation().getKey();
        NormalizedVariantKey rightKey = right == null || right.getObservation() == null
                ? null
                : right.getObservation().getKey();
        return stableKey(leftKey).compareTo(stableKey(rightKey));
    }

    private String stableKey(NormalizedVariantKey key) {
        if (key == null) {
            return "";
        }
        return normalizeChromosome(key.getChromosome())
                + ':' + String.format(Locale.ROOT, "%012d", key.getPosition())
                + ':' + String.valueOf(key.getReference())
                + ':' + String.valueOf(key.getAlternate());
    }

    private Map<String, GenePhenotypePrior> priorByGene(List<GenePhenotypePrior> priors) {
        Map<String, GenePhenotypePrior> result = new LinkedHashMap<String, GenePhenotypePrior>();
        for (GenePhenotypePrior prior : priors) {
            String gene = normalizeGene(prior.getGeneSymbol());
            if (!result.containsKey(gene)) {
                result.put(gene, prior);
            }
        }
        return result;
    }

    private String sourceVersion(Path intervalFile) {
        return intervalFile == null ? "UNCONFIGURED" : intervalFile.getFileName().toString();
    }

    private void addInterval(Map<String, List<GeneInterval>> result, String gene, GeneInterval interval) {
        List<GeneInterval> intervals = result.get(gene);
        if (intervals == null) {
            intervals = new ArrayList<GeneInterval>();
            result.put(gene, intervals);
        }
        intervals.add(interval);
    }

    private String[] split(String line) {
        String[] tab = line.split("\\t", -1);
        if (tab.length >= 5) {
            return tab;
        }
        return line.split(",", -1);
    }

    private boolean looksLikeHeader(String[] columns) {
        if (columns == null) {
            return false;
        }
        for (String column : columns) {
            String normalized = normalizeHeader(column);
            if ("gene".equals(normalized) || "genesymbol".equals(normalized)
                    || "gene_symbol".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String value(String[] columns, Header header, String field, int fallbackIndex) {
        int index = header == null ? fallbackIndex : header.index(field, fallbackIndex);
        if (columns == null || index < 0 || index >= columns.length) {
            return null;
        }
        return columns[index] == null ? null : columns[index].trim();
    }

    private Long parseLong(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.valueOf(text.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int safeLength(String value) {
        return value == null || value.isEmpty() ? 1 : value.length();
    }

    private String normalizeGene(String gene) {
        return gene == null ? "" : gene.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeChromosome(String chromosome) {
        if (chromosome == null) {
            return "";
        }
        String value = chromosome.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("chr")) {
            value = value.substring(3);
        }
        if ("M".equalsIgnoreCase(value)) {
            return "MT";
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }

    public static class GeneIntervalMatch {

        private final String geneSymbol;
        private final String chromosome;
        private final long start;
        private final long end;
        private final int phenotypeRank;
        private final double phenotypeScore;
        private final String source;
        private final String featureType;

        public GeneIntervalMatch(String geneSymbol,
                                 String chromosome,
                                 long start,
                                 long end,
                                 int phenotypeRank,
                                 double phenotypeScore,
                                 String source) {
            this(geneSymbol, chromosome, start, end, phenotypeRank, phenotypeScore, source, "GENE");
        }

        public GeneIntervalMatch(String geneSymbol,
                                 String chromosome,
                                 long start,
                                 long end,
                                 int phenotypeRank,
                                 double phenotypeScore,
                                 String source,
                                 String featureType) {
            this.geneSymbol = geneSymbol;
            this.chromosome = chromosome;
            this.start = Math.min(start, end);
            this.end = Math.max(start, end);
            this.phenotypeRank = phenotypeRank;
            this.phenotypeScore = phenotypeScore;
            this.source = source;
            this.featureType = normalizeFeatureType(featureType);
        }

        public String getGeneSymbol() {
            return geneSymbol;
        }

        public String getChromosome() {
            return chromosome;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }

        public int getPhenotypeRank() {
            return phenotypeRank;
        }

        public double getPhenotypeScore() {
            return phenotypeScore;
        }

        public String getSource() {
            return source;
        }

        public String getFeatureType() {
            return featureType;
        }
    }

    public static class RescueEvaluation {

        private final Map<VariantInterpretation, GeneIntervalMatch> matches;
        private final int totalCandidates;
        private final String phenotypePriorStatus;
        private final int phenotypePriorGeneCount;
        private final String intervalResourceStatus;
        private final String intervalSourceVersion;
        private final int intervalMappedPriorGeneCount;
        private final int rescueCandidateCount;
        private final int rescueMissingIntervalGeneCount;
        private final String effectiveAssembly;
        private final int candidateCap;
        private final List<String> warnings;

        public RescueEvaluation(Map<VariantInterpretation, GeneIntervalMatch> matches,
                                int totalCandidates,
                                String phenotypePriorStatus,
                                int phenotypePriorGeneCount,
                                String intervalResourceStatus,
                                String intervalSourceVersion,
                                int intervalMappedPriorGeneCount,
                                int rescueCandidateCount,
                                int rescueMissingIntervalGeneCount,
                                String effectiveAssembly,
                                int candidateCap,
                                List<String> warnings) {
            this.matches = matches == null
                    ? Collections.<VariantInterpretation, GeneIntervalMatch>emptyMap()
                    : Collections.unmodifiableMap(matches);
            this.totalCandidates = Math.max(0, totalCandidates);
            this.phenotypePriorStatus = phenotypePriorStatus;
            this.phenotypePriorGeneCount = Math.max(0, phenotypePriorGeneCount);
            this.intervalResourceStatus = intervalResourceStatus;
            this.intervalSourceVersion = intervalSourceVersion;
            this.intervalMappedPriorGeneCount = Math.max(0, intervalMappedPriorGeneCount);
            this.rescueCandidateCount = Math.max(0, rescueCandidateCount);
            this.rescueMissingIntervalGeneCount = Math.max(0, rescueMissingIntervalGeneCount);
            this.effectiveAssembly = effectiveAssembly;
            this.candidateCap = Math.max(0, candidateCap);
            this.warnings = immutableWarnings(warnings);
        }

        public static RescueEvaluation empty(int totalCandidates,
                                             String phenotypePriorStatus,
                                             String intervalResourceStatus,
                                             String effectiveAssembly,
                                             int candidateCap) {
            return new RescueEvaluation(
                    Collections.<VariantInterpretation, GeneIntervalMatch>emptyMap(),
                    totalCandidates,
                    phenotypePriorStatus,
                    0,
                    intervalResourceStatus,
                    "UNCONFIGURED",
                    0,
                    0,
                    0,
                    effectiveAssembly,
                    candidateCap,
                    Collections.<String>emptyList()
            );
        }

        public Map<VariantInterpretation, GeneIntervalMatch> getMatches() {
            return matches;
        }

        public int getTotalCandidates() {
            return totalCandidates;
        }

        public String getPhenotypePriorStatus() {
            return phenotypePriorStatus;
        }

        public int getPhenotypePriorGeneCount() {
            return phenotypePriorGeneCount;
        }

        public String getIntervalResourceStatus() {
            return intervalResourceStatus;
        }

        public String getIntervalSourceVersion() {
            return intervalSourceVersion;
        }

        public int getIntervalMappedPriorGeneCount() {
            return intervalMappedPriorGeneCount;
        }

        public int getRescueCandidateCount() {
            return rescueCandidateCount;
        }

        public int getRescueMissingIntervalGeneCount() {
            return rescueMissingIntervalGeneCount;
        }

        public String getEffectiveAssembly() {
            return effectiveAssembly;
        }

        public int getCandidateCap() {
            return candidateCap;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        private static List<String> immutableWarnings(List<String> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<String>(source));
        }
    }

    private static final class VariantIntervalMatch {
        private final VariantInterpretation variant;
        private final GeneIntervalMatch match;

        private VariantIntervalMatch(VariantInterpretation variant, GeneIntervalMatch match) {
            this.variant = variant;
            this.match = match;
        }
    }

    private static final class MatchSelection {
        private final Map<VariantInterpretation, GeneIntervalMatch> matches;
        private final int potentialMatchCount;

        private MatchSelection(Map<VariantInterpretation, GeneIntervalMatch> matches,
                               int potentialMatchCount) {
            this.matches = matches;
            this.potentialMatchCount = potentialMatchCount;
        }
    }

    private static final class IntervalLookup {
        private final Map<String, ChromosomeIntervals> byChromosome;

        private IntervalLookup(Map<String, ChromosomeIntervals> byChromosome) {
            this.byChromosome = byChromosome;
        }

        private static IntervalLookup from(Map<String, List<GeneInterval>> intervalsByGene) {
            Map<String, List<GeneInterval>> grouped = new LinkedHashMap<String, List<GeneInterval>>();
            for (List<GeneInterval> intervals : intervalsByGene.values()) {
                for (GeneInterval interval : intervals) {
                    List<GeneInterval> chromosomeIntervals = grouped.get(interval.getChromosome());
                    if (chromosomeIntervals == null) {
                        chromosomeIntervals = new ArrayList<GeneInterval>();
                        grouped.put(interval.getChromosome(), chromosomeIntervals);
                    }
                    chromosomeIntervals.add(interval);
                }
            }
            Map<String, ChromosomeIntervals> indexed =
                    new LinkedHashMap<String, ChromosomeIntervals>();
            for (Map.Entry<String, List<GeneInterval>> entry : grouped.entrySet()) {
                indexed.put(entry.getKey(), new ChromosomeIntervals(entry.getValue()));
            }
            return new IntervalLookup(indexed);
        }

        private GeneInterval bestMatch(String chromosome, long start, long end, int padding) {
            ChromosomeIntervals intervals = byChromosome.get(normalizeChromosome(chromosome));
            return intervals == null ? null : intervals.bestMatch(start, end, padding);
        }
    }

    private static final class ChromosomeIntervals {
        private final List<GeneInterval> intervals;
        private final long[] prefixMaximumEnd;

        private ChromosomeIntervals(List<GeneInterval> source) {
            this.intervals = new ArrayList<GeneInterval>(source);
            Collections.sort(this.intervals, new Comparator<GeneInterval>() {
                @Override
                public int compare(GeneInterval left, GeneInterval right) {
                    int start = Long.compare(left.getStart(), right.getStart());
                    if (start != 0) {
                        return start;
                    }
                    int rank = Integer.compare(left.getPhenotypeRank(), right.getPhenotypeRank());
                    return rank != 0 ? rank : left.getGeneSymbol().compareToIgnoreCase(right.getGeneSymbol());
                }
            });
            this.prefixMaximumEnd = new long[this.intervals.size()];
            long maximum = Long.MIN_VALUE;
            for (int index = 0; index < this.intervals.size(); index++) {
                maximum = Math.max(maximum, this.intervals.get(index).getEnd());
                this.prefixMaximumEnd[index] = maximum;
            }
        }

        private GeneInterval bestMatch(long queryStart, long queryEnd, int padding) {
            int normalizedPadding = Math.max(0, padding);
            int index = upperBound(queryEnd + normalizedPadding) - 1;
            GeneInterval best = null;
            while (index >= 0 && prefixMaximumEnd[index] + normalizedPadding >= queryStart) {
                GeneInterval candidate = intervals.get(index);
                if (candidate.overlaps(candidate.getChromosome(), queryStart, queryEnd, normalizedPadding)
                        && better(candidate, best)) {
                    best = candidate;
                }
                index--;
            }
            return best;
        }

        private int upperBound(long queryEnd) {
            int low = 0;
            int high = intervals.size();
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (intervals.get(middle).getStart() <= queryEnd) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }

        private boolean better(GeneInterval candidate, GeneInterval current) {
            if (current == null) {
                return true;
            }
            int rank = Integer.compare(candidate.getPhenotypeRank(), current.getPhenotypeRank());
            if (rank != 0) {
                return rank < 0;
            }
            int score = Double.compare(candidate.getPhenotypeScore(), current.getPhenotypeScore());
            if (score != 0) {
                return score > 0;
            }
            int feature = Integer.compare(
                    featurePriority(candidate.getFeatureType()),
                    featurePriority(current.getFeatureType())
            );
            return feature != 0
                    ? feature > 0
                    : candidate.getGeneSymbol().compareToIgnoreCase(current.getGeneSymbol()) < 0;
        }
    }

    private static class IntervalLoadResult {

        private final Map<String, List<GeneInterval>> intervals;
        private final String status;
        private final String sourceVersion;
        private final List<String> warnings;

        private IntervalLoadResult(Map<String, List<GeneInterval>> intervals,
                                   String status,
                                   String sourceVersion,
                                   List<String> warnings) {
            this.intervals = intervals == null
                    ? new LinkedHashMap<String, List<GeneInterval>>()
                    : intervals;
            this.status = status;
            this.sourceVersion = sourceVersion;
            this.warnings = warnings == null ? Collections.<String>emptyList() : warnings;
        }

        private static IntervalLoadResult empty(String status, String sourceVersion) {
            return new IntervalLoadResult(
                    new LinkedHashMap<String, List<GeneInterval>>(),
                    status,
                    sourceVersion,
                    Collections.<String>emptyList()
            );
        }

        private static IntervalLoadResult warning(String status, String sourceVersion, String warning) {
            return new IntervalLoadResult(
                    new LinkedHashMap<String, List<GeneInterval>>(),
                    status,
                    sourceVersion,
                    Collections.singletonList(warning)
            );
        }
    }

    private static final class IntervalResourceKey {

        private final String path;
        private final long size;
        private final long lastModified;
        private final String assembly;
        private final String sourceVersion;

        private IntervalResourceKey(String path, long size, long lastModified, String assembly) {
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
            this.assembly = assembly;
            String safeName = path.replace('\\', '/');
            this.sourceVersion = safeName.substring(safeName.lastIndexOf('/') + 1)
                    + "|bytes=" + size
                    + "|modified=" + lastModified
                    + "|assembly=" + assembly;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IntervalResourceKey)) {
                return false;
            }
            IntervalResourceKey that = (IntervalResourceKey) other;
            return size == that.size
                    && lastModified == that.lastModified
                    && path.equals(that.path)
                    && assembly.equals(that.assembly);
        }

        @Override
        public int hashCode() {
            int result = path.hashCode();
            result = 31 * result + Long.valueOf(size).hashCode();
            result = 31 * result + Long.valueOf(lastModified).hashCode();
            result = 31 * result + assembly.hashCode();
            return result;
        }
    }

    private static class InvalidIntervalFileException extends RuntimeException {

        private InvalidIntervalFileException(String message) {
            super(message);
        }
    }

    private static class GeneInterval {

        private final String geneSymbol;
        private final String chromosome;
        private final long start;
        private final long end;
        private final int phenotypeRank;
        private final double phenotypeScore;
        private final String source;
        private final String featureType;

        private GeneInterval(String geneSymbol,
                             String chromosome,
                             long start,
                             long end,
                             int phenotypeRank,
                             double phenotypeScore,
                             String source,
                             String featureType) {
            this.geneSymbol = geneSymbol;
            this.chromosome = normalizeChromosome(chromosome);
            this.start = Math.min(start, end);
            this.end = Math.max(start, end);
            this.phenotypeRank = phenotypeRank;
            this.phenotypeScore = phenotypeScore;
            this.source = source;
            this.featureType = normalizeFeatureType(featureType);
        }

        private GeneInterval withPrior(GenePhenotypePrior prior) {
            return new GeneInterval(
                    geneSymbol,
                    chromosome,
                    start,
                    end,
                    prior == null ? 0 : prior.getPhenotypeRank(),
                    prior == null ? 0.0d : prior.getPhenotypeScore(),
                    source,
                    featureType
            );
        }

        private boolean overlaps(String normalizedChromosome, long queryStart, long queryEnd, int padding) {
            long paddedStart = Math.max(1L, start - padding);
            long paddedEnd = end + padding;
            return chromosome.equals(normalizedChromosome)
                    && queryStart <= paddedEnd
                    && queryEnd >= paddedStart;
        }

        private String getGeneSymbol() {
            return geneSymbol;
        }

        private String getChromosome() {
            return chromosome;
        }

        private long getStart() {
            return start;
        }

        private long getEnd() {
            return end;
        }

        private int getPhenotypeRank() {
            return phenotypeRank;
        }

        private double getPhenotypeScore() {
            return phenotypeScore;
        }

        private String getSource() {
            return source;
        }

        private String getFeatureType() {
            return featureType;
        }
    }

    private static class Header {

        private final Map<String, Integer> columns;

        private Header(Map<String, Integer> columns) {
            this.columns = columns;
        }

        private static Header from(String[] values) {
            Map<String, Integer> columns = new LinkedHashMap<String, Integer>();
            for (int index = 0; index < values.length; index++) {
                String normalized = normalizeHeader(values[index]);
                columns.put(normalized, index);
                if ("gene_symbol".equals(normalized) || "genesymbol".equals(normalized)) {
                    columns.put("gene", index);
                } else if ("chr".equals(normalized) || "chrom".equals(normalized)) {
                    columns.put("chromosome", index);
                } else if ("start_bp".equals(normalized)) {
                    columns.put("start", index);
                } else if ("end_bp".equals(normalized)) {
                    columns.put("end", index);
                } else if ("genome_assembly".equals(normalized)) {
                    columns.put("assembly", index);
                }
            }
            return new Header(columns);
        }

        private int index(String field, int fallback) {
            Integer index = columns.get(field);
            return index == null ? fallback : index.intValue();
        }
    }
}
