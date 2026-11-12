package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInputStatus;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhenotypeInputNormalizerTest {

    @Test
    void acceptsCanonicalHpoId() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001250")
        );

        assertTrue(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(1, summary.getInputHpoCount());
        assertEquals(1, summary.getValidHpoCount());
        assertEquals(0, summary.getInvalidHpoCount());
        assertEquals(Collections.singletonList("HP:0001250"), summary.getValidHpoTerms());
        assertTrue(summary.getWarnings().contains(PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING));
        assertEquals(PhenotypeInputStatus.VALID, summary.getPhenotypeInputs().get(0).getStatus());
        assertEquals(GenePhenotypePriorStatus.INVALID_INPUT, summary.getGenePriorSummary().getStatus());
        assertFalse(summary.getGenePriorSummary().isEnabled());
    }

    @Test
    void normalizesLowercaseHpoPrefix() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("hp:0001250")
        );

        assertEquals(Collections.singletonList("HP:0001250"), summary.getValidHpoTerms());
        assertEquals("HP:0001250", summary.getPhenotypeInputs().get(0).getHpoId());
        assertEquals("hp:0001250", summary.getPhenotypeInputs().get(0).getMatchedTerm());
    }

    @Test
    void rejectsShortAndFreeTextTermsWithoutFailingSummary() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Arrays.asList("HP:1250", "abc")
        );

        assertTrue(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(2, summary.getInputHpoCount());
        assertEquals(0, summary.getValidHpoCount());
        assertEquals(2, summary.getInvalidHpoCount());
        assertEquals(Arrays.asList("HP:1250", "abc"), summary.getInvalidHpoTerms());
        assertEquals(PhenotypeInputStatus.INVALID_FORMAT, summary.getPhenotypeInputs().get(0).getStatus());
        assertEquals(PhenotypeInputStatus.INVALID_FORMAT, summary.getPhenotypeInputs().get(1).getStatus());
    }

    @Test
    void deDuplicatesValidHpoTermsAndDoesNotCountDuplicatesAsInvalid() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Arrays.asList("HP:0001250", "hp:0001250", "HP:0004322")
        );

        assertEquals(3, summary.getInputHpoCount());
        assertEquals(2, summary.getValidHpoCount());
        assertEquals(0, summary.getInvalidHpoCount());
        assertEquals(1, summary.getDuplicateHpoCount());
        assertEquals(Arrays.asList("HP:0001250", "HP:0004322"), summary.getValidHpoTerms());
        PhenotypeInput duplicate = summary.getPhenotypeInputs().get(1);
        assertEquals(PhenotypeInputStatus.DUPLICATE, duplicate.getStatus());
        assertTrue(duplicate.getWarnings().get(0).contains("Duplicate HPO term ignored"));
    }

    @Test
    void emptyInputReturnsDisabledNoOpSummary() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Arrays.asList(null, "", "   ")
        );

        assertFalse(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(0, summary.getInputHpoCount());
        assertEquals(0, summary.getValidHpoCount());
        assertEquals(0, summary.getInvalidHpoCount());
        assertEquals(0, summary.getDuplicateHpoCount());
        assertTrue(summary.getValidHpoTerms().isEmpty());
        assertTrue(summary.getWarnings().isEmpty());
        assertFalse(summary.getGenePriorSummary().isEnabled());
    }

    @Test
    void tokenizesRepeatedCommaAndWhitespaceSeparatedTerms() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Arrays.asList("HP:0001250, hp:0004322", "HP:0000118")
        );

        assertEquals(3, summary.getInputHpoCount());
        assertEquals(3, summary.getValidHpoCount());
        assertEquals(Arrays.asList("HP:0001250", "HP:0004322", "HP:0000118"), summary.getValidHpoTerms());
    }

    @Test
    void attachesConfirmedProvenanceToNormalizedHpoInput() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001695"),
                Collections.singletonList(
                        "{\"hpoId\":\"HP:0001695\",\"label\":\"Cardiac arrest\","
                                + "\"source\":\"CLINICAL_FEATURE_CONFIRMED\","
                                + "\"matchedTerm\":\"心跳骤停\",\"confidence\":0.98}"
                )
        );

        PhenotypeInput input = summary.getPhenotypeInputs().get(0);
        assertEquals(PhenotypeInputStatus.VALID, input.getStatus());
        assertEquals("HP:0001695", input.getHpoId());
        assertEquals("Cardiac arrest", input.getLabel());
        assertEquals("CLINICAL_FEATURE_CONFIRMED", input.getSource());
        assertEquals("心跳骤停", input.getMatchedTerm());
        assertEquals(0.98d, input.getConfidence(), 0.0001d);
    }
}
