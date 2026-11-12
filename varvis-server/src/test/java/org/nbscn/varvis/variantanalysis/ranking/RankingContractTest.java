package org.nbscn.varvis.variantanalysis.ranking;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankingContractTest {

    @Test
    void ordersByScoreThenOriginalPersistedOrderWithoutUsingVariantId() {
        VariantInterpretation first = variant("z-id", 10.0d);
        VariantInterpretation second = variant("a-id", 10.0d);
        VariantInterpretation higher = variant("later-id", 20.0d);

        assertEquals(
                Arrays.asList(higher, first, second),
                RankingContract.order(Arrays.asList(first, second, higher))
        );
    }

    @Test
    void nullScoresAreLastAndTieOrderRemainsStable() {
        VariantInterpretation first = variant("first", null);
        VariantInterpretation second = variant("second", null);

        assertEquals(
                Arrays.asList(first, second),
                RankingContract.order(Arrays.asList(first, second))
        );
    }

    @Test
    void nonFiniteScoresAreFailSafeAndKeepSourceOrderBehindFiniteScores() {
        VariantInterpretation nan = variant("nan", Double.NaN);
        VariantInterpretation positiveInfinity = variant("positive-infinity", Double.POSITIVE_INFINITY);
        VariantInterpretation finite = variant("finite", 1.0d);
        VariantInterpretation negativeInfinity = variant("negative-infinity", Double.NEGATIVE_INFINITY);

        assertEquals(
                Arrays.asList(finite, nan, positiveInfinity, negativeInfinity),
                RankingContract.order(Arrays.asList(nan, positiveInfinity, finite, negativeInfinity))
        );
    }

    private VariantInterpretation variant(String id, Double score) {
        VariantInterpretation value = new VariantInterpretation(
                id, null, Collections.emptyList(), Collections.emptyList(),
                "CANDIDATE", score, Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyList()
        );
        return value;
    }
}
