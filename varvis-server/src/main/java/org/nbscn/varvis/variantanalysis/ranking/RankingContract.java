package org.nbscn.varvis.variantanalysis.ranking;

import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The single ordering contract shared by persisted analysis reads and case validation.
 *
 * <p>The database expresses the same order as {@code ranking_score DESC, variant_order ASC}.
 * For an in-memory run, the original list position is the persisted variant_order surrogate.
 * This preserves the production tie-break without inventing a safeId-based product order.</p>
 */
public final class RankingContract {

    public static final String VERSION = "ranking-contract-v1";

    private RankingContract() {
    }

    public static List<VariantInterpretation> order(List<VariantInterpretation> variants) {
        List<IndexedVariant> indexed = new ArrayList<IndexedVariant>();
        if (variants != null) {
            for (int index = 0; index < variants.size(); index++) {
                indexed.add(new IndexedVariant(variants.get(index), index));
            }
        }
        Collections.sort(indexed, new Comparator<IndexedVariant>() {
            @Override
            public int compare(IndexedVariant left, IndexedVariant right) {
                int score = Double.compare(scoreOrNegative(right.variant), scoreOrNegative(left.variant));
                return score != 0 ? score : Integer.compare(left.originalOrder, right.originalOrder);
            }
        });
        List<VariantInterpretation> ordered = new ArrayList<VariantInterpretation>(indexed.size());
        for (IndexedVariant value : indexed) {
            ordered.add(value.variant);
        }
        return ordered;
    }

    private static double scoreOrNegative(VariantInterpretation variant) {
        if (variant == null || variant.getRankingScore() == null) {
            return Double.NEGATIVE_INFINITY;
        }
        double score = variant.getRankingScore().doubleValue();
        return Double.isFinite(score) ? score : Double.NEGATIVE_INFINITY;
    }

    private static final class IndexedVariant {
        private final VariantInterpretation variant;
        private final int originalOrder;

        private IndexedVariant(VariantInterpretation variant, int originalOrder) {
            this.variant = variant;
            this.originalOrder = originalOrder;
        }
    }
}
