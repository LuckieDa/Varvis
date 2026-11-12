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
import java.util.regex.Pattern;

public class LocalGenePhenotypePriorProvider implements GenePhenotypePriorProvider {

    private static final Pattern HPO_ID = Pattern.compile("^HP:\\d{7}$");
    private static final String DEFAULT_SOURCE = "LOCAL_GENE_PRIOR";
    private static final String DEFAULT_VERSION = "mock-v1";

    private final VariantAnalysisProperties.PhenotypePrior properties;

    public LocalGenePhenotypePriorProvider(VariantAnalysisProperties properties) {
        this(properties == null ? null : properties.getPhenotypePrior());
    }

    public LocalGenePhenotypePriorProvider(VariantAnalysisProperties.PhenotypePrior properties) {
        this.properties = properties == null
                ? new VariantAnalysisProperties.PhenotypePrior()
                : properties;
    }

    public GenePhenotypePriorSummary summarize(PhenotypeAwareSummary phenotypeSummary) {
        Instant generatedAt = Instant.now();
        int inputCount = phenotypeSummary == null ? 0 : phenotypeSummary.getInputHpoCount();
        int validCount = phenotypeSummary == null ? 0 : phenotypeSummary.getValidHpoCount();
        String source = configuredSource();
        String version = configuredVersion();

        if (phenotypeSummary == null || !phenotypeSummary.isEnabled()) {
            return GenePhenotypePriorSummary.notGenerated();
        }
        if (validCount < 1) {
            return summary(
                    false,
                    source,
                    version,
                    GenePhenotypePriorStatus.INVALID_INPUT,
                    inputCount,
                    validCount,
                    Collections.emptyList(),
                    Collections.singletonList("No valid HPO terms available for local gene phenotype prior generation."),
                    generatedAt
            );
        }
        if (!properties.isEnabled()) {
            return summary(
                    false,
                    source,
                    version,
                    GenePhenotypePriorStatus.DISABLED,
                    inputCount,
                    validCount,
                    Collections.emptyList(),
                    Collections.singletonList("Gene phenotype prior provider is disabled; no gene prior was generated."),
                    generatedAt
            );
        }

        Path localFile = properties.getLocalFile();
        if (localFile == null) {
            return unavailable(inputCount, validCount, "Local gene phenotype prior file is not configured.", generatedAt);
        }
        if (!Files.isRegularFile(localFile)) {
            return unavailable(inputCount, validCount, "Local gene phenotype prior file is not readable.", generatedAt);
        }

        Set<String> validHpoTerms = new LinkedHashSet<String>(phenotypeSummary.getValidHpoTerms());
        List<String> warnings = new ArrayList<String>();
        Map<String, AggregatedGenePrior> byGene = new LinkedHashMap<String, AggregatedGenePrior>();
        try (BufferedReader reader = Files.newBufferedReader(localFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                parseLine(line, lineNumber, validHpoTerms, byGene, warnings);
            }
        } catch (IOException ex) {
            return unavailable(inputCount, validCount, "Local gene phenotype prior file could not be read.", generatedAt);
        }

        List<GenePhenotypePrior> priors = rankedPriors(byGene, source, version, generatedAt);
        if (priors.isEmpty()) {
            List<String> noPriorWarnings = new ArrayList<String>(warnings);
            noPriorWarnings.add(
                    "No local gene phenotype prior matched the valid HPO terms; absence of a prior is not benign evidence."
            );
            return summary(
                    true,
                    source,
                    version,
                    GenePhenotypePriorStatus.NO_PRIOR,
                    inputCount,
                    validCount,
                    Collections.emptyList(),
                    noPriorWarnings,
                    generatedAt
            );
        }
        List<String> resultWarnings = new ArrayList<String>(warnings);
        resultWarnings.add("Gene phenotype prior is gene-level candidate support only; it is not a diagnosis.");
        return summary(
                true,
                source,
                version,
                GenePhenotypePriorStatus.AVAILABLE,
                inputCount,
                validCount,
                limit(priors),
                resultWarnings,
                generatedAt
        );
    }

    private GenePhenotypePriorSummary unavailable(int inputCount,
                                                  int validCount,
                                                  String warning,
                                                  Instant generatedAt) {
        return summary(
                true,
                configuredSource(),
                configuredVersion(),
                GenePhenotypePriorStatus.UNAVAILABLE,
                inputCount,
                validCount,
                Collections.emptyList(),
                Collections.singletonList(warning + " Provider unavailable does not imply low risk."),
                generatedAt
        );
    }

    private GenePhenotypePriorSummary summary(boolean enabled,
                                              String source,
                                              String version,
                                              GenePhenotypePriorStatus status,
                                              int inputCount,
                                              int validCount,
                                              List<GenePhenotypePrior> priors,
                                              List<String> warnings,
                                              Instant generatedAt) {
        return new GenePhenotypePriorSummary(
                enabled,
                source,
                version,
                status,
                inputCount,
                validCount,
                priors == null ? 0 : priors.size(),
                priors,
                warnings,
                generatedAt
        );
    }

    private void parseLine(String line,
                           int lineNumber,
                           Set<String> validHpoTerms,
                           Map<String, AggregatedGenePrior> byGene,
                           List<String> warnings) {
        if (line == null || line.trim().isEmpty() || line.trim().startsWith("#")) {
            return;
        }
        String[] columns = line.split("\t", -1);
        if (lineNumber == 1 && columns.length > 0 && "hpo_id".equalsIgnoreCase(columns[0].trim())) {
            return;
        }
        if (columns.length < 6) {
            warnings.add("Malformed local gene prior row skipped at line " + lineNumber + ".");
            return;
        }
        String hpoId = columns[0].trim().toUpperCase(Locale.ROOT);
        String geneSymbol = columns[1].trim();
        String geneId = emptyToNull(columns[2]);
        String scoreText = columns[3].trim();
        String rankText = columns[4].trim();
        String evidence = columns[5].trim();
        if (!HPO_ID.matcher(hpoId).matches() || geneSymbol.isEmpty() || scoreText.isEmpty()) {
            warnings.add("Malformed local gene prior row skipped at line " + lineNumber + ".");
            return;
        }
        if (!validHpoTerms.contains(hpoId)) {
            return;
        }
        double score;
        int sourceRank;
        try {
            score = Double.parseDouble(scoreText);
            sourceRank = rankText.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(rankText);
        } catch (NumberFormatException ex) {
            warnings.add("Malformed local gene prior row skipped at line " + lineNumber + ".");
            return;
        }
        String normalizedGene = geneSymbol.toUpperCase(Locale.ROOT);
        AggregatedGenePrior aggregate = byGene.get(normalizedGene);
        if (aggregate == null) {
            aggregate = new AggregatedGenePrior(geneSymbol, geneId);
            byGene.put(normalizedGene, aggregate);
        }
        aggregate.add(hpoId, geneId, score, sourceRank, evidence);
    }

    private List<GenePhenotypePrior> rankedPriors(Map<String, AggregatedGenePrior> byGene,
                                                  String source,
                                                  String version,
                                                  Instant generatedAt) {
        if (byGene.isEmpty()) {
            return Collections.emptyList();
        }
        List<AggregatedGenePrior> aggregates = new ArrayList<AggregatedGenePrior>(byGene.values());
        Collections.sort(aggregates, new Comparator<AggregatedGenePrior>() {
            @Override
            public int compare(AggregatedGenePrior left, AggregatedGenePrior right) {
                int scoreCompare = Double.compare(right.score, left.score);
                if (scoreCompare != 0) {
                    return scoreCompare;
                }
                int rankCompare = Integer.compare(left.bestSourceRank, right.bestSourceRank);
                if (rankCompare != 0) {
                    return rankCompare;
                }
                return left.geneSymbol.compareToIgnoreCase(right.geneSymbol);
            }
        });
        List<GenePhenotypePrior> priors = new ArrayList<GenePhenotypePrior>();
        int rank = 1;
        for (AggregatedGenePrior aggregate : aggregates) {
            priors.add(new GenePhenotypePrior(
                    aggregate.geneSymbol,
                    aggregate.geneId,
                    aggregate.score,
                    rank++,
                    source,
                    version,
                    new ArrayList<String>(aggregate.matchedHpoTerms),
                    new ArrayList<String>(aggregate.evidence),
                    GenePhenotypePriorStatus.AVAILABLE,
                    generatedAt,
                    Collections.emptyList()
            ));
        }
        return priors;
    }

    private List<GenePhenotypePrior> limit(List<GenePhenotypePrior> priors) {
        int maxGenes = properties.normalizedMaxGenes();
        if (maxGenes == 0 || priors.size() <= maxGenes) {
            return priors;
        }
        return new ArrayList<GenePhenotypePrior>(priors.subList(0, maxGenes));
    }

    private String configuredSource() {
        String source = properties.getSource();
        return source == null || source.trim().isEmpty() ? DEFAULT_SOURCE : source.trim();
    }

    private String configuredVersion() {
        String version = properties.getVersion();
        return version == null || version.trim().isEmpty() ? DEFAULT_VERSION : version.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static class AggregatedGenePrior {

        private final String geneSymbol;
        private String geneId;
        private double score = Double.NEGATIVE_INFINITY;
        private int bestSourceRank = Integer.MAX_VALUE;
        private final Set<String> matchedHpoTerms = new LinkedHashSet<String>();
        private final Set<String> evidence = new LinkedHashSet<String>();

        private AggregatedGenePrior(String geneSymbol, String geneId) {
            this.geneSymbol = geneSymbol;
            this.geneId = geneId;
        }

        private void add(String hpoId, String rowGeneId, double rowScore, int sourceRank, String rowEvidence) {
            matchedHpoTerms.add(hpoId);
            if (rowEvidence != null && !rowEvidence.trim().isEmpty()) {
                evidence.add(hpoId + ": " + rowEvidence.trim());
            }
            if (rowScore > score) {
                score = rowScore;
                if (rowGeneId != null && !rowGeneId.trim().isEmpty()) {
                    geneId = rowGeneId.trim();
                }
            }
            if (sourceRank < bestSourceRank) {
                bestSourceRank = sourceRank;
            }
        }
    }
}
