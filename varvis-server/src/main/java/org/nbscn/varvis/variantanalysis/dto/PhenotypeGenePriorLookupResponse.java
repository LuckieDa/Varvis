package org.nbscn.varvis.variantanalysis.dto;

import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PhenotypeGenePriorLookupResponse {

    private final String requestedGene;
    private final String normalizedGene;
    private final GenePhenotypePriorStatus status;
    private final boolean found;
    private final Integer phenotypeRank;
    private final Double phenotypeScore;
    private final List<String> matchedHpoTerms;
    private final String source;
    private final String sourceVersion;
    private final int effectiveTopK;
    private final boolean inEffectiveTopK;
    private final String message;

    public PhenotypeGenePriorLookupResponse(String requestedGene,
                                            String normalizedGene,
                                            GenePhenotypePriorStatus status,
                                            boolean found,
                                            Integer phenotypeRank,
                                            Double phenotypeScore,
                                            List<String> matchedHpoTerms,
                                            String source,
                                            String sourceVersion,
                                            int effectiveTopK,
                                            boolean inEffectiveTopK,
                                            String message) {
        this.requestedGene = requestedGene;
        this.normalizedGene = normalizedGene;
        this.status = status;
        this.found = found;
        this.phenotypeRank = phenotypeRank;
        this.phenotypeScore = phenotypeScore;
        this.matchedHpoTerms = matchedHpoTerms == null ? Collections.<String>emptyList() : matchedHpoTerms;
        this.source = source;
        this.sourceVersion = sourceVersion;
        this.effectiveTopK = Math.max(0, effectiveTopK);
        this.inEffectiveTopK = inEffectiveTopK;
        this.message = message;
    }

    public static PhenotypeGenePriorLookupResponse from(String requestedGene,
                                                        GenePhenotypePriorSummary summary,
                                                        int effectiveTopK) {
        String normalizedGene = normalizeGene(requestedGene);
        GenePhenotypePriorStatus status = summary == null
                ? GenePhenotypePriorStatus.INVALID_INPUT
                : summary.getStatus();
        if (normalizedGene == null) {
            return unavailable(requestedGene, normalizedGene, status, summary, effectiveTopK,
                    "No gene symbol was provided for phenotype prior lookup.");
        }
        if (summary == null || summary.getTopGenePriors().isEmpty()) {
            return unavailable(requestedGene, normalizedGene, status, summary, effectiveTopK,
                    normalizedGene + " cannot be checked because no phenotype gene prior is available.");
        }
        GenePhenotypePrior exact = null;
        for (GenePhenotypePrior prior : summary.getTopGenePriors()) {
            if (prior != null && normalizedGene.equals(normalizeGene(prior.getGeneSymbol()))) {
                exact = prior;
                break;
            }
        }
        if (exact == null) {
            return unavailable(requestedGene, normalizedGene, status, summary, effectiveTopK,
                    normalizedGene + " was not found in the phenotype prior gene list.");
        }
        boolean inTopK = effectiveTopK <= 0 || exact.getPhenotypeRank() <= effectiveTopK;
        String message = inTopK
                ? normalizedGene + " is in the top-" + effectiveTopK
                        + " phenotype prior, rank=" + exact.getPhenotypeRank() + "."
                : normalizedGene + " not in top-" + effectiveTopK
                        + " phenotype prior, rank=" + exact.getPhenotypeRank() + ".";
        return new PhenotypeGenePriorLookupResponse(
                requestedGene,
                normalizedGene,
                status,
                true,
                exact.getPhenotypeRank(),
                exact.getPhenotypeScore(),
                exact.getMatchedHpoTerms(),
                exact.getSource(),
                exact.getSourceVersion(),
                effectiveTopK,
                inTopK,
                message
        );
    }

    private static PhenotypeGenePriorLookupResponse unavailable(String requestedGene,
                                                                String normalizedGene,
                                                                GenePhenotypePriorStatus status,
                                                                GenePhenotypePriorSummary summary,
                                                                int effectiveTopK,
                                                                String message) {
        return new PhenotypeGenePriorLookupResponse(
                requestedGene,
                normalizedGene,
                status,
                false,
                null,
                null,
                Collections.<String>emptyList(),
                summary == null ? null : summary.getSource(),
                summary == null ? null : summary.getSourceVersion(),
                effectiveTopK,
                false,
                message
        );
    }

    private static String normalizeGene(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    public String getRequestedGene() {
        return requestedGene;
    }

    public String getNormalizedGene() {
        return normalizedGene;
    }

    public GenePhenotypePriorStatus getStatus() {
        return status;
    }

    public boolean isFound() {
        return found;
    }

    public Integer getPhenotypeRank() {
        return phenotypeRank;
    }

    public Double getPhenotypeScore() {
        return phenotypeScore;
    }

    public List<String> getMatchedHpoTerms() {
        return matchedHpoTerms;
    }

    public String getSource() {
        return source;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public int getEffectiveTopK() {
        return effectiveTopK;
    }

    public boolean isInEffectiveTopK() {
        return inEffectiveTopK;
    }

    public String getMessage() {
        return message;
    }
}
