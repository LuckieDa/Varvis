package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.Locale;

/** Lightweight persisted ranking row; it deliberately does not deserialize evidence payloads. */
public final class PersistedRankingCandidate {

    private final String candidateId;
    private final String variantId;
    private final NormalizedVariantKey key;
    private final Double rankingScore;
    private final Integer variantOrder;

    public PersistedRankingCandidate(String variantId,
                                     NormalizedVariantKey key,
                                     Double rankingScore,
                                     Integer variantOrder) {
        this(null, variantId, key, rankingScore, variantOrder);
    }

    public PersistedRankingCandidate(String candidateId,
                                     String variantId,
                                     NormalizedVariantKey key,
                                     Double rankingScore,
                                     Integer variantOrder) {
        this.candidateId = candidateId;
        this.variantId = variantId;
        this.key = key;
        this.rankingScore = rankingScore;
        this.variantOrder = variantOrder;
    }

    public static PersistedRankingCandidate from(VariantInterpretation interpretation,
                                                  Integer variantOrder) {
        VariantQcObservation observation = interpretation == null ? null : interpretation.getObservation();
        return new PersistedRankingCandidate(
                interpretation == null ? null : interpretation.getCandidateId(),
                interpretation == null ? null : interpretation.getVariantId(),
                observation == null ? null : observation.getKey(),
                interpretation == null ? null : interpretation.getRankingScore(),
                variantOrder
        );
    }

    public static NormalizedVariantKey key(String assembly,
                                           String chromosome,
                                           Integer position,
                                           String reference,
                                           String alternate) {
        if (!hasText(assembly) || !hasText(chromosome) || position == null || position.intValue() <= 0
                || !hasText(reference) || !hasText(alternate)) {
            return null;
        }
        String normalizedChromosome = chromosome.trim();
        if (normalizedChromosome.toLowerCase(Locale.ROOT).startsWith("chr")) {
            normalizedChromosome = normalizedChromosome.substring(3);
        }
        return new NormalizedVariantKey(
                assembly.trim(),
                normalizedChromosome.toUpperCase(Locale.ROOT),
                position.intValue(),
                reference.trim().toUpperCase(Locale.ROOT),
                alternate.trim().toUpperCase(Locale.ROOT)
        );
    }

    public String getVariantId() {
        return variantId;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public NormalizedVariantKey getKey() {
        return key;
    }

    public Double getRankingScore() {
        return rankingScore;
    }

    public Integer getVariantOrder() {
        return variantOrder;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
