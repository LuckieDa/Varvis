package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a Phen2Gene-compatible gene prior from the local HPO2Gene knowledgebase.
 * The knowledgebase is queried once per normalized HPO set, not once per variant.
 */
public class Phen2GeneKnowledgebaseGenePhenotypePriorProvider implements GenePhenotypePriorProvider {

    private static final String SOURCE = "PHEN2GENE_LOCAL_KB";
    private static final String DEFAULT_VERSION = "Phen2Gene-local-kb-unknown-version";
    private static final String FILE_SUFFIX = ".candidate_gene_list";
    private static final int CACHE_LIMIT = 64;
    private static final Map<String, CachedResult> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, CachedResult>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    private final VariantAnalysisProperties.PhenotypePrior properties;

    public Phen2GeneKnowledgebaseGenePhenotypePriorProvider(VariantAnalysisProperties properties) {
        this(properties == null ? null : properties.getPhenotypePrior());
    }

    public Phen2GeneKnowledgebaseGenePhenotypePriorProvider(
            VariantAnalysisProperties.PhenotypePrior properties) {
        this.properties = properties == null
                ? new VariantAnalysisProperties.PhenotypePrior()
                : properties;
    }

    @Override
    public GenePhenotypePriorSummary summarize(PhenotypeAwareSummary phenotypeSummary) {
        Instant generatedAt = Instant.now();
        int inputCount = phenotypeSummary == null ? 0 : phenotypeSummary.getInputHpoCount();
        int validCount = phenotypeSummary == null ? 0 : phenotypeSummary.getValidHpoCount();
        if (phenotypeSummary == null || !phenotypeSummary.isEnabled()) {
            return GenePhenotypePriorSummary.notGenerated();
        }
        if (validCount < 1) {
            return summary(
                    false,
                    GenePhenotypePriorStatus.INVALID_INPUT,
                    inputCount,
                    validCount,
                    Collections.<GenePhenotypePrior>emptyList(),
                    Collections.singletonList("No valid HPO terms are available for local Phen2Gene lookup."),
                    generatedAt
            );
        }
        if (!properties.isEnabled()) {
            return summary(
                    false,
                    GenePhenotypePriorStatus.DISABLED,
                    inputCount,
                    validCount,
                    Collections.<GenePhenotypePrior>emptyList(),
                    Collections.singletonList("Local Phen2Gene knowledgebase provider is disabled."),
                    generatedAt
            );
        }

        Path knowledgebase = knowledgebaseDirectory(properties.getLocalFile());
        if (knowledgebase == null) {
            return unavailable(
                    inputCount,
                    validCount,
                    "PHEN2GENE_KB_NOT_CONFIGURED: knowledgebase directory is not configured.",
                    generatedAt
            );
        }
        if (!Files.isDirectory(knowledgebase)) {
            return unavailable(
                    inputCount,
                    validCount,
                    "PHEN2GENE_KB_PATH_NOT_DIRECTORY: configured knowledgebase path is not a readable directory.",
                    generatedAt
            );
        }

        List<String> hpoTerms = new ArrayList<String>(phenotypeSummary.getValidHpoTerms());
        Collections.sort(hpoTerms);
        ResourceResolution resources = resolveResources(knowledgebase, hpoTerms);
        if (resources.files.isEmpty()) {
            return unavailable(
                    inputCount,
                    validCount,
                    "PHEN2GENE_KB_TERMS_UNAVAILABLE: no candidate-gene file exists for the valid HPO terms.",
                    generatedAt
            );
        }

        String cacheKey = cacheKey(knowledgebase, hpoTerms, resources);
        CachedResult cached = CACHE.get(cacheKey);
        if (cached == null) {
            try {
                cached = load(resources);
            } catch (MalformedKnowledgebaseException ex) {
                return unavailable(inputCount, validCount, ex.getMessage(), generatedAt);
            } catch (IOException ex) {
                return unavailable(
                        inputCount,
                        validCount,
                        "PHEN2GENE_KB_READ_ERROR: candidate-gene resources could not be read.",
                        generatedAt
                );
            }
            CACHE.put(cacheKey, cached);
        }

        List<String> warnings = new ArrayList<String>(cached.warnings);
        if (!resources.missingTerms.isEmpty()) {
            warnings.add("PHEN2GENE_KB_PARTIAL_HPO_COVERAGE: "
                    + resources.missingTerms.size()
                    + " valid HPO term(s) have no local candidate-gene file.");
        }
        List<GenePhenotypePrior> priors = priors(cached.rows, generatedAt);
        if (priors.isEmpty()) {
            warnings.add("No gene prior was produced; absence of a prior is not benign evidence.");
            return summary(
                    true,
                    GenePhenotypePriorStatus.NO_PRIOR,
                    inputCount,
                    validCount,
                    priors,
                    warnings,
                    generatedAt
            );
        }
        warnings.add("Local Phen2Gene prior is gene-level candidate support only; it is not a diagnosis.");
        return summary(
                true,
                GenePhenotypePriorStatus.AVAILABLE,
                inputCount,
                validCount,
                priors,
                warnings,
                generatedAt
        );
    }

    private Path knowledgebaseDirectory(Path configured) {
        if (configured == null) {
            return null;
        }
        Path nested = configured.resolve("Knowledgebase");
        return Files.isDirectory(nested) ? nested : configured;
    }

    private ResourceResolution resolveResources(Path knowledgebase, List<String> hpoTerms) {
        List<ResourceFile> files = new ArrayList<ResourceFile>();
        List<String> missingTerms = new ArrayList<String>();
        for (String hpoTerm : hpoTerms) {
            Path file = knowledgebase.resolve(hpoTerm.replace(':', '_') + FILE_SUFFIX);
            if (!Files.isRegularFile(file)) {
                missingTerms.add(hpoTerm);
                continue;
            }
            try {
                files.add(new ResourceFile(
                        hpoTerm,
                        file,
                        Files.size(file),
                        Files.getLastModifiedTime(file).toMillis()
                ));
            } catch (IOException ex) {
                missingTerms.add(hpoTerm);
            }
        }
        return new ResourceResolution(files, missingTerms);
    }

    private String cacheKey(Path knowledgebase,
                            List<String> hpoTerms,
                            ResourceResolution resources) {
        StringBuilder key = new StringBuilder();
        key.append(knowledgebase.toAbsolutePath().normalize())
                .append('|')
                .append(configuredVersion())
                .append('|')
                .append(String.join(",", hpoTerms));
        for (ResourceFile resource : resources.files) {
            key.append('|')
                    .append(resource.hpoTerm)
                    .append(':')
                    .append(resource.size)
                    .append(':')
                    .append(resource.lastModifiedMillis);
        }
        return key.toString();
    }

    private CachedResult load(ResourceResolution resources) throws IOException {
        Map<String, AggregatedGene> byGene = new LinkedHashMap<String, AggregatedGene>();
        List<String> warnings = new ArrayList<String>();
        for (ResourceFile resource : resources.files) {
            loadCandidateFile(resource, byGene, warnings);
        }
        List<AggregatedGene> aggregates = new ArrayList<AggregatedGene>(byGene.values());
        Collections.sort(aggregates, new Comparator<AggregatedGene>() {
            @Override
            public int compare(AggregatedGene left, AggregatedGene right) {
                int score = Double.compare(right.totalScore, left.totalScore);
                if (score != 0) {
                    return score;
                }
                int rank = Integer.compare(left.bestSourceRank, right.bestSourceRank);
                return rank != 0 ? rank : left.geneSymbol.compareToIgnoreCase(right.geneSymbol);
            }
        });
        double maximum = aggregates.isEmpty() ? 0.0d : aggregates.get(0).totalScore;
        List<CachedPriorRow> rows = new ArrayList<CachedPriorRow>();
        for (int index = 0; index < aggregates.size(); index++) {
            AggregatedGene aggregate = aggregates.get(index);
            rows.add(new CachedPriorRow(
                    aggregate.geneSymbol,
                    aggregate.geneId,
                    maximum <= 0.0d ? 0.0d : aggregate.totalScore / maximum,
                    index + 1,
                    new ArrayList<String>(aggregate.matchedHpoTerms),
                    new ArrayList<String>(aggregate.statuses)
            ));
        }
        return new CachedResult(rows, warnings);
    }

    private void loadCandidateFile(ResourceFile resource,
                                   Map<String, AggregatedGene> byGene,
                                   List<String> warnings) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(resource.path, StandardCharsets.UTF_8)) {
            Map<String, Integer> header = null;
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (header == null) {
                    header = header(columns);
                    continue;
                }
                try {
                    addRow(resource.hpoTerm, columns, header, byGene);
                } catch (IllegalArgumentException ex) {
                    String warning = "PHEN2GENE_KB_MALFORMED_ROW: malformed candidate-gene row "
                            + lineNumber + " for " + resource.hpoTerm + ".";
                    if (properties.isFailOnMalformedRow()) {
                        throw new MalformedKnowledgebaseException(warning);
                    }
                    warnings.add(warning);
                }
            }
            if (header == null) {
                throw new MalformedKnowledgebaseException(
                        "PHEN2GENE_KB_HEADER_MISSING: candidate-gene header is missing for "
                                + resource.hpoTerm + "."
                );
            }
        }
    }

    private Map<String, Integer> header(String[] columns) {
        Map<String, Integer> header = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < columns.length; index++) {
            String name = columns[index] == null
                    ? ""
                    : columns[index].trim().toLowerCase(Locale.ROOT);
            header.put(name, index);
        }
        for (String required : new String[]{"rank", "gene", "id", "score", "status"}) {
            if (!header.containsKey(required)) {
                throw new MalformedKnowledgebaseException(
                        "PHEN2GENE_KB_HEADER_INVALID: required column " + required + " is missing."
                );
            }
        }
        return header;
    }

    private void addRow(String hpoTerm,
                        String[] columns,
                        Map<String, Integer> header,
                        Map<String, AggregatedGene> byGene) {
        String gene = value(columns, header.get("gene"));
        String geneId = value(columns, header.get("id"));
        String scoreText = value(columns, header.get("score"));
        String rankText = value(columns, header.get("rank"));
        String status = value(columns, header.get("status"));
        if (gene == null || scoreText == null || rankText == null) {
            throw new IllegalArgumentException("required value missing");
        }
        double score = Double.parseDouble(scoreText);
        int rank = Integer.parseInt(rankText);
        if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.0d || rank < 1) {
            throw new IllegalArgumentException("invalid score or rank");
        }
        String normalized = gene.toUpperCase(Locale.ROOT);
        AggregatedGene aggregate = byGene.get(normalized);
        if (aggregate == null) {
            aggregate = new AggregatedGene(gene, geneId);
            byGene.put(normalized, aggregate);
        }
        aggregate.add(score, rank, hpoTerm, status);
    }

    private String value(String[] columns, Integer index) {
        if (index == null || index.intValue() < 0 || index.intValue() >= columns.length) {
            return null;
        }
        String value = columns[index.intValue()];
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private List<GenePhenotypePrior> priors(List<CachedPriorRow> rows, Instant generatedAt) {
        int maximum = properties.normalizedMaxGenes();
        int size = maximum <= 0 ? rows.size() : Math.min(maximum, rows.size());
        List<GenePhenotypePrior> priors = new ArrayList<GenePhenotypePrior>();
        for (int index = 0; index < size; index++) {
            CachedPriorRow row = rows.get(index);
            List<String> evidence = new ArrayList<String>();
            evidence.add("Phen2Gene HPO2Gene knowledgebase candidate prior");
            if (!row.statuses.isEmpty()) {
                evidence.add("Phen2Gene status: " + String.join(", ", row.statuses));
            }
            priors.add(new GenePhenotypePrior(
                    row.geneSymbol,
                    row.geneId,
                    row.normalizedScore,
                    row.rank,
                    SOURCE,
                    configuredVersion(),
                    row.matchedHpoTerms,
                    evidence,
                    GenePhenotypePriorStatus.AVAILABLE,
                    generatedAt,
                    Collections.<String>emptyList()
            ));
        }
        return priors;
    }

    private GenePhenotypePriorSummary unavailable(int inputCount,
                                                  int validCount,
                                                  String reason,
                                                  Instant generatedAt) {
        return summary(
                true,
                GenePhenotypePriorStatus.UNAVAILABLE,
                inputCount,
                validCount,
                Collections.<GenePhenotypePrior>emptyList(),
                Collections.singletonList(reason + " Provider unavailable does not imply low risk."),
                generatedAt
        );
    }

    private GenePhenotypePriorSummary summary(boolean enabled,
                                              GenePhenotypePriorStatus status,
                                              int inputCount,
                                              int validCount,
                                              List<GenePhenotypePrior> priors,
                                              List<String> warnings,
                                              Instant generatedAt) {
        return new GenePhenotypePriorSummary(
                enabled,
                SOURCE,
                configuredVersion(),
                status,
                inputCount,
                validCount,
                priors == null ? 0 : priors.size(),
                priors,
                warnings,
                generatedAt
        );
    }

    private String configuredVersion() {
        String version = properties.getVersion();
        return version == null || version.trim().isEmpty() || "mock-v1".equals(version.trim())
                ? DEFAULT_VERSION
                : version.trim();
    }

    private static final class ResourceResolution {
        private final List<ResourceFile> files;
        private final List<String> missingTerms;

        private ResourceResolution(List<ResourceFile> files, List<String> missingTerms) {
            this.files = files;
            this.missingTerms = missingTerms;
        }
    }

    private static final class ResourceFile {
        private final String hpoTerm;
        private final Path path;
        private final long size;
        private final long lastModifiedMillis;

        private ResourceFile(String hpoTerm, Path path, long size, long lastModifiedMillis) {
            this.hpoTerm = hpoTerm;
            this.path = path;
            this.size = size;
            this.lastModifiedMillis = lastModifiedMillis;
        }
    }

    private static final class AggregatedGene {
        private final String geneSymbol;
        private final String geneId;
        private double totalScore;
        private int bestSourceRank = Integer.MAX_VALUE;
        private final Set<String> matchedHpoTerms = new LinkedHashSet<String>();
        private final Set<String> statuses = new LinkedHashSet<String>();

        private AggregatedGene(String geneSymbol, String geneId) {
            this.geneSymbol = geneSymbol;
            this.geneId = geneId;
        }

        private void add(double score, int rank, String hpoTerm, String status) {
            totalScore += score;
            bestSourceRank = Math.min(bestSourceRank, rank);
            matchedHpoTerms.add(hpoTerm);
            if (status != null) {
                statuses.add(status);
            }
        }
    }

    private static final class CachedPriorRow {
        private final String geneSymbol;
        private final String geneId;
        private final double normalizedScore;
        private final int rank;
        private final List<String> matchedHpoTerms;
        private final List<String> statuses;

        private CachedPriorRow(String geneSymbol,
                               String geneId,
                               double normalizedScore,
                               int rank,
                               List<String> matchedHpoTerms,
                               List<String> statuses) {
            this.geneSymbol = geneSymbol;
            this.geneId = geneId;
            this.normalizedScore = normalizedScore;
            this.rank = rank;
            this.matchedHpoTerms = Collections.unmodifiableList(matchedHpoTerms);
            this.statuses = Collections.unmodifiableList(statuses);
        }
    }

    private static final class CachedResult {
        private final List<CachedPriorRow> rows;
        private final List<String> warnings;

        private CachedResult(List<CachedPriorRow> rows, List<String> warnings) {
            this.rows = Collections.unmodifiableList(new ArrayList<CachedPriorRow>(rows));
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        }
    }

    private static final class MalformedKnowledgebaseException extends RuntimeException {
        private MalformedKnowledgebaseException(String message) {
            super(message);
        }
    }
}
