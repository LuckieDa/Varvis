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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class Phen2GeneImportGenePhenotypePriorProvider implements GenePhenotypePriorProvider {

    private static final Pattern HPO_ID = Pattern.compile("^HP:\\d{7}$");
    private static final String SOURCE = "PHEN2GENE_IMPORT";
    private static final String DEFAULT_VERSION = "phen2gene-import-v1";
    private static final String DEFAULT_EVIDENCE = "Imported Phen2Gene candidate gene prior";
    private static final String TSV_FORMAT = "TSV";
    private static final List<String> REQUIRED_COLUMNS = Arrays.asList(
            "hpo_terms",
            "gene_symbol",
            "phenotype_score",
            "phenotype_rank",
            "source",
            "source_version"
    );

    private final VariantAnalysisProperties.PhenotypePrior properties;

    public Phen2GeneImportGenePhenotypePriorProvider(VariantAnalysisProperties properties) {
        this(properties == null ? null : properties.getPhenotypePrior());
    }

    public Phen2GeneImportGenePhenotypePriorProvider(VariantAnalysisProperties.PhenotypePrior properties) {
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
                    configuredVersion(),
                    GenePhenotypePriorStatus.INVALID_INPUT,
                    inputCount,
                    validCount,
                    Collections.emptyList(),
                    Collections.singletonList("No valid HPO terms available for Phen2Gene import prior generation."),
                    generatedAt
            );
        }
        if (!properties.isEnabled()) {
            return summary(
                    false,
                    configuredVersion(),
                    GenePhenotypePriorStatus.DISABLED,
                    inputCount,
                    validCount,
                    Collections.emptyList(),
                    Collections.singletonList("Phen2Gene import provider is disabled; no gene prior was generated."),
                    generatedAt
            );
        }
        if (!TSV_FORMAT.equalsIgnoreCase(trimToDefault(properties.getFormat(), TSV_FORMAT))) {
            return unavailable(inputCount, validCount, "Phen2Gene import provider currently supports TSV only.", generatedAt);
        }

        Path localFile = properties.getLocalFile();
        if (localFile == null) {
            return unavailable(inputCount, validCount, "Phen2Gene import file is not configured.", generatedAt);
        }
        if (!Files.isRegularFile(localFile)) {
            return unavailable(inputCount, validCount, "Phen2Gene import file is not readable.", generatedAt);
        }

        Set<String> validHpoTerms = new LinkedHashSet<String>(phenotypeSummary.getValidHpoTerms());
        List<String> warnings = new ArrayList<String>();
        Map<String, AggregatedGenePrior> byGene = new LinkedHashMap<String, AggregatedGenePrior>();
        try (BufferedReader reader = Files.newBufferedReader(localFile, StandardCharsets.UTF_8)) {
            parseTsv(reader, validHpoTerms, byGene, warnings, generatedAt);
        } catch (MalformedPhen2GeneImportException ex) {
            return unavailable(inputCount, validCount, ex.getMessage(), generatedAt);
        } catch (IOException ex) {
            return unavailable(inputCount, validCount, "Phen2Gene import file could not be read.", generatedAt);
        }

        List<GenePhenotypePrior> priors = limit(rankedPriors(byGene, generatedAt));
        if (priors.isEmpty()) {
            List<String> noPriorWarnings = new ArrayList<String>(warnings);
            noPriorWarnings.add(
                    "No Phen2Gene import prior matched the valid HPO terms; absence of a prior is not benign evidence."
            );
            return summary(
                    true,
                    configuredSummaryVersion(priors),
                    GenePhenotypePriorStatus.NO_PRIOR,
                    inputCount,
                    validCount,
                    Collections.emptyList(),
                    noPriorWarnings,
                    generatedAt
            );
        }
        List<String> resultWarnings = new ArrayList<String>(warnings);
        resultWarnings.add("Phen2Gene import prior is gene-level candidate support only; it is not a diagnosis.");
        return summary(
                true,
                configuredSummaryVersion(priors),
                GenePhenotypePriorStatus.AVAILABLE,
                inputCount,
                validCount,
                priors,
                resultWarnings,
                generatedAt
        );
    }

    private void parseTsv(BufferedReader reader,
                          Set<String> validHpoTerms,
                          Map<String, AggregatedGenePrior> byGene,
                          List<String> warnings,
                          Instant generatedAt) throws IOException {
        Map<String, Integer> header = null;
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line == null || line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            if (header == null) {
                header = parseHeader(columns, lineNumber);
                continue;
            }
            RowPrior row = parseRow(columns, header, lineNumber, validHpoTerms, warnings);
            if (row == null) {
                continue;
            }
            String normalizedGene = row.geneSymbol.toUpperCase(Locale.ROOT);
            AggregatedGenePrior aggregate = byGene.get(normalizedGene);
            if (aggregate == null) {
                aggregate = new AggregatedGenePrior(row.geneSymbol);
                byGene.put(normalizedGene, aggregate);
            }
            aggregate.add(row);
        }
        if (header == null) {
            malformed(warnings, 0, "Phen2Gene import TSV header is missing.");
        }
    }

    private Map<String, Integer> parseHeader(String[] columns, int lineNumber) {
        Map<String, Integer> header = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < columns.length; index++) {
            String name = normalizeHeader(columns[index]);
            if (name != null && !header.containsKey(name)) {
                header.put(name, index);
            }
        }
        for (String required : REQUIRED_COLUMNS) {
            if (!header.containsKey(required)) {
                throw new MalformedPhen2GeneImportException(
                        "Phen2Gene import TSV header is missing required column " + required
                                + " at line " + lineNumber + "."
                );
            }
        }
        return header;
    }

    private RowPrior parseRow(String[] columns,
                              Map<String, Integer> header,
                              int lineNumber,
                              Set<String> validHpoTerms,
                              List<String> warnings) {
        String hpoTermsText = column(columns, header, "hpo_terms");
        String geneSymbol = column(columns, header, "gene_symbol");
        String geneId = emptyToNull(column(columns, header, "gene_id"));
        String scoreText = column(columns, header, "phenotype_score");
        String rankText = column(columns, header, "phenotype_rank");
        String rowSource = column(columns, header, "source");
        String rowVersion = emptyToNull(column(columns, header, "source_version"));
        String matchedHpoText = column(columns, header, "matched_hpo_terms");
        String evidenceText = column(columns, header, "evidence");
        String status = emptyToNull(column(columns, header, "status"));

        List<String> rowHpoTerms = hpoTerms(hpoTermsText);
        List<String> matchedHpoTerms = hpoTerms(matchedHpoText);
        if (rowHpoTerms.isEmpty()
                || geneSymbol == null
                || geneSymbol.trim().isEmpty()
                || rowSource == null
                || rowSource.trim().isEmpty()
                || scoreText == null
                || scoreText.trim().isEmpty()
                || rankText == null
                || rankText.trim().isEmpty()) {
            malformed(warnings, lineNumber, "Malformed Phen2Gene import row skipped at line " + lineNumber + ".");
            return null;
        }

        if (properties.isRequireMatchingHpo()
                && !intersects(rowHpoTerms, validHpoTerms)
                && !intersects(matchedHpoTerms, validHpoTerms)) {
            return null;
        }

        double score;
        int rank;
        try {
            score = Double.parseDouble(scoreText.trim());
            rank = Integer.parseInt(rankText.trim());
        } catch (NumberFormatException ex) {
            malformed(warnings, lineNumber, "Malformed Phen2Gene import row skipped at line " + lineNumber + ".");
            return null;
        }
        if (Double.isNaN(score) || Double.isInfinite(score) || rank < 1) {
            malformed(warnings, lineNumber, "Malformed Phen2Gene import row skipped at line " + lineNumber + ".");
            return null;
        }

        List<String> supportTerms = matchedHpoTerms.isEmpty() ? rowHpoTerms : matchedHpoTerms;
        List<String> outputTerms = properties.isRequireMatchingHpo()
                ? intersection(supportTerms, validHpoTerms)
                : supportTerms;
        if (outputTerms.isEmpty() && properties.isRequireMatchingHpo()) {
            outputTerms = intersection(rowHpoTerms, validHpoTerms);
        }
        if (outputTerms.isEmpty()) {
            outputTerms = supportTerms;
        }

        List<String> evidence = new ArrayList<String>();
        evidence.add(trimToDefault(evidenceText, DEFAULT_EVIDENCE));
        if (status != null) {
            evidence.add("Phen2Gene status: " + status);
        }

        return new RowPrior(
                geneSymbol.trim(),
                geneId,
                score,
                rank,
                rowVersion == null ? configuredVersion() : rowVersion,
                outputTerms,
                evidence
        );
    }

    private void malformed(List<String> warnings, int lineNumber, String warning) {
        if (properties.isFailOnMalformedRow()) {
            throw new MalformedPhen2GeneImportException(warning);
        }
        if (lineNumber == 0) {
            warnings.add(warning);
            return;
        }
        warnings.add(warning);
    }

    private List<GenePhenotypePrior> rankedPriors(Map<String, AggregatedGenePrior> byGene,
                                                  Instant generatedAt) {
        if (byGene.isEmpty()) {
            return Collections.emptyList();
        }
        List<AggregatedGenePrior> aggregates =
                new ArrayList<AggregatedGenePrior>(byGene.values());
        Collections.sort(aggregates, new Comparator<AggregatedGenePrior>() {
            @Override
            public int compare(AggregatedGenePrior left, AggregatedGenePrior right) {
                int scoreCompare = Double.compare(right.score, left.score);
                if (scoreCompare != 0) {
                    return scoreCompare;
                }
                int rankCompare = Integer.compare(left.bestRank, right.bestRank);
                if (rankCompare != 0) {
                    return rankCompare;
                }
                return left.geneSymbol.compareToIgnoreCase(right.geneSymbol);
            }
        });
        List<GenePhenotypePrior> priors = new ArrayList<GenePhenotypePrior>();
        for (AggregatedGenePrior aggregate : aggregates) {
            priors.add(new GenePhenotypePrior(
                    aggregate.geneSymbol,
                    aggregate.geneId,
                    aggregate.score,
                    aggregate.bestRank,
                    SOURCE,
                    aggregate.sourceVersion,
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

    private GenePhenotypePriorSummary unavailable(int inputCount,
                                                  int validCount,
                                                  String warning,
                                                  Instant generatedAt) {
        return summary(
                true,
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
                                              String version,
                                              GenePhenotypePriorStatus status,
                                              int inputCount,
                                              int validCount,
                                              List<GenePhenotypePrior> priors,
                                              List<String> warnings,
                                              Instant generatedAt) {
        return new GenePhenotypePriorSummary(
                enabled,
                SOURCE,
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

    private String configuredSummaryVersion(List<GenePhenotypePrior> priors) {
        if (priors != null && !priors.isEmpty()) {
            return priors.get(0).getSourceVersion();
        }
        return configuredVersion();
    }

    private String configuredVersion() {
        String version = properties.getVersion();
        if (version == null || version.trim().isEmpty() || "mock-v1".equals(version.trim())) {
            return DEFAULT_VERSION;
        }
        return version.trim();
    }

    private String column(String[] columns, Map<String, Integer> header, String name) {
        Integer index = header.get(name);
        if (index == null || index < 0 || index >= columns.length) {
            return null;
        }
        return columns[index] == null ? null : columns[index].trim();
    }

    private String normalizeHeader(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> hpoTerms(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String token : value.split(";")) {
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            String normalized = token.trim().toUpperCase(Locale.ROOT);
            if (HPO_ID.matcher(normalized).matches() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private boolean intersects(List<String> values, Set<String> validHpoTerms) {
        if (values == null || values.isEmpty() || validHpoTerms == null || validHpoTerms.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (validHpoTerms.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> intersection(List<String> values, Set<String> validHpoTerms) {
        if (values == null || values.isEmpty() || validHpoTerms == null || validHpoTerms.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            if (validHpoTerms.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private String trimToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static class RowPrior {

        private final String geneSymbol;
        private final String geneId;
        private final double score;
        private final int rank;
        private final String sourceVersion;
        private final List<String> matchedHpoTerms;
        private final List<String> evidence;

        private RowPrior(String geneSymbol,
                         String geneId,
                         double score,
                         int rank,
                         String sourceVersion,
                         List<String> matchedHpoTerms,
                         List<String> evidence) {
            this.geneSymbol = geneSymbol;
            this.geneId = geneId;
            this.score = score;
            this.rank = rank;
            this.sourceVersion = sourceVersion;
            this.matchedHpoTerms = matchedHpoTerms;
            this.evidence = evidence;
        }
    }

    private static class AggregatedGenePrior {

        private final String geneSymbol;
        private String geneId;
        private double score = Double.NEGATIVE_INFINITY;
        private int bestRank = Integer.MAX_VALUE;
        private String sourceVersion = DEFAULT_VERSION;
        private final Set<String> matchedHpoTerms = new LinkedHashSet<String>();
        private final Set<String> evidence = new LinkedHashSet<String>();

        private AggregatedGenePrior(String geneSymbol) {
            this.geneSymbol = geneSymbol;
        }

        private void add(RowPrior row) {
            matchedHpoTerms.addAll(row.matchedHpoTerms);
            evidence.addAll(row.evidence);
            if (row.score > score || (Double.compare(row.score, score) == 0 && row.rank < bestRank)) {
                score = row.score;
                geneId = row.geneId;
                sourceVersion = row.sourceVersion;
            }
            if (row.rank < bestRank) {
                bestRank = row.rank;
            }
        }
    }

    private static class MalformedPhen2GeneImportException extends RuntimeException {

        private MalformedPhen2GeneImportException(String message) {
            super(message);
        }
    }
}
