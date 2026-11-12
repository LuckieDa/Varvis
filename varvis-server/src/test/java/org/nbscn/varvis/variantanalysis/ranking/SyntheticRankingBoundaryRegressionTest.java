package org.nbscn.varvis.variantanalysis.ranking;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Purely synthetic boundary coverage for the product ranking contract. */
class SyntheticRankingBoundaryRegressionTest {

    private final TransparentVariantRankingService ranking =
            new TransparentVariantRankingService(new VariantAnalysisProperties());

    @Test
    void nullAndEmptyInputsRemainNoOp() {
        assertDoesNotThrow(() -> ranking.rank(null));
        assertDoesNotThrow(() -> ranking.rank(Collections.<VariantInterpretation>emptyList()));
        assertTrue(RankingContract.order(null).isEmpty());
        assertTrue(RankingContract.order(Collections.<VariantInterpretation>emptyList()).isEmpty());
    }

    @Test
    void populationAndPredictionThresholdsAreInclusiveOnlyAtTheBoundary() {
        VariantInterpretation belowFrequency = variant(101, 30, 99, 0.4d,
                evidence("GNOMAD", 0.009999d));
        VariantInterpretation atFrequency = variant(102, 30, 99, 0.4d,
                evidence("GNOMAD", 0.01d));
        VariantInterpretation belowCadd = variant(103, 30, 99, 0.4d,
                evidence("GNOMAD", 0.001d), evidence("CADD", 14.999999d));
        VariantInterpretation atCadd = variant(104, 30, 99, 0.4d,
                evidence("GNOMAD", 0.001d), evidence("CADD", 15.0d));
        VariantInterpretation belowSplice = variant(105, 30, 99, 0.4d,
                evidence("GNOMAD", 0.001d), evidence("SPLICEAI", 0.499999d));
        VariantInterpretation atSplice = variant(106, 30, 99, 0.4d,
                evidence("GNOMAD", 0.001d), evidence("SPLICEAI", 0.5d));

        ranking.rank(Arrays.asList(
                belowFrequency, atFrequency, belowCadd, atCadd, belowSplice, atSplice));

        assertNotEquals("FILTERED_COMMON", belowFrequency.getPriorityTier());
        assertEquals("FILTERED_COMMON", atFrequency.getPriorityTier());
        assertEquals("LOW_PRIORITY", belowCadd.getPriorityTier());
        assertEquals("REVIEW", atCadd.getPriorityTier());
        assertEquals("LOW_PRIORITY", belowSplice.getPriorityTier());
        assertEquals("HIGH_PRIORITY", atSplice.getPriorityTier());
    }

    @Test
    void qualityValuesAtThresholdPassAndValuesJustBelowFail() {
        VariantInterpretation atBoundary = variant(201, 10, 20, 0.2d,
                evidence("GNOMAD", 0.001d));
        VariantInterpretation belowBoundary = variant(202, 9, 19, 0.199999d,
                evidence("GNOMAD", 0.001d));

        ranking.rank(Arrays.asList(atBoundary, belowBoundary));

        assertNotEquals("FILTERED_QC", atBoundary.getPriorityTier());
        assertEquals("FILTERED_QC", belowBoundary.getPriorityTier());
    }

    @Test
    void invalidNumericEvidenceCannotElevateCandidatesAndFreshRunsAreDeterministic() {
        List<VariantInterpretation> first = invalidNumericCandidates();
        List<VariantInterpretation> second = invalidNumericCandidates();

        ranking.rank(first);
        ranking.rank(second);

        for (int index = 0; index < first.size(); index++) {
            VariantInterpretation left = first.get(index);
            VariantInterpretation right = second.get(index);
            assertTrue(Double.isFinite(left.getRankingScore()));
            assertFalse("HIGH_PRIORITY".equals(left.getPriorityTier()));
            assertEquals(left.getPriorityTier(), right.getPriorityTier());
            assertEquals(left.getRankingScore(), right.getRankingScore());
            assertEquals(left.getScoreBreakdown(), right.getScoreBreakdown());
        }
        assertEquals(ids(first), ids(RankingContract.order(first)));
        assertEquals(ids(RankingContract.order(first)), ids(RankingContract.order(second)));
    }

    private List<VariantInterpretation> invalidNumericCandidates() {
        List<VariantInterpretation> values = new ArrayList<VariantInterpretation>();
        values.add(variant(301, 30, 99, 0.4d,
                evidence("GNOMAD", Double.NaN),
                evidence("CADD", Double.POSITIVE_INFINITY),
                evidence("SPLICEAI", Double.NEGATIVE_INFINITY)));
        values.add(variant(302, 30, 99, 0.4d,
                evidence("GNOMAD", -0.001d)));
        values.add(variant(303, 30, 99, 0.4d,
                evidence("GNOMAD", null)));
        values.add(variant(304, 30, 99, 0.4d,
                evidence("GNOMAD", 1.000001d)));
        values.add(variant(305, 30, 99, 0.4d,
                evidence("GNOMAD", Double.POSITIVE_INFINITY)));
        values.add(variant(306, 30, 99, 0.4d,
                evidence("GNOMAD", Double.NEGATIVE_INFINITY)));
        return values;
    }

    private List<String> ids(List<VariantInterpretation> variants) {
        List<String> ids = new ArrayList<String>();
        for (VariantInterpretation variant : variants) {
            ids.add(variant.getVariantId());
        }
        return ids;
    }

    private VariantInterpretation variant(int position,
                                          Integer depth,
                                          Integer genotypeQuality,
                                          Double alleleBalance,
                                          EvidenceItem... evidence) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", position, "A", "G"),
                "synthetic-sample",
                "0/1",
                depth,
                genotypeQuality,
                "10,10",
                alleleBalance,
                "PASS",
                "SNV",
                null
        );
        return new VariantInterpretation(
                "synthetic-" + position,
                observation,
                Collections.singletonList(
                        new TranscriptConsequence("GENE1", null, null, null, null, "missense_variant")),
                Arrays.asList(evidence)
        );
    }

    private EvidenceItem evidence(String source, Double score) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("synthetic", Boolean.TRUE);
        return new EvidenceItem(
                source,
                "synthetic-v1",
                "GNOMAD".equals(source) ? "POPULATION" : "COMPUTATIONAL",
                "APPLICABLE",
                score,
                "synthetic " + source,
                "Synthetic boundary fixture.",
                details
        );
    }
}
