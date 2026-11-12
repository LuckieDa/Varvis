package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phen2GeneImportLocalSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "phen2gene.import.file", matches = ".+")
    void readsExternalImportAndAppliesCandidatePriorWithoutChangingHardGates() {
        String importFile = System.getProperty("phen2gene.import.file");
        String sourceVersion = System.getProperty(
                "phen2gene.source.version",
                "phen2gene-api-unknown-version"
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setSource("PHEN2GENE_IMPORT");
        properties.getPhenotypePrior().setLocalFile(importFile);
        properties.getPhenotypePrior().setVersion(sourceVersion);
        properties.getPhenotypePrior().setMaxGenes(50);
        properties.getPhenotypeRanking().setEnabled(true);

        PhenotypeAwareSummary input = PhenotypeInputNormalizer.summarize(
                Arrays.asList("HP:0001250", "HP:0004322")
        );
        GenePhenotypePriorSummary priorSummary =
                new Phen2GeneImportGenePhenotypePriorProvider(properties).summarize(input);

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, priorSummary.getStatus());
        assertEquals("PHEN2GENE_IMPORT", priorSummary.getSource());
        assertEquals(sourceVersion, priorSummary.getSourceVersion());
        assertFalse(priorSummary.getTopGenePriors().isEmpty());
        assertEquals("FGFR3", priorSummary.getTopGenePriors().get(0).getGeneSymbol());
        assertEquals(
                Arrays.asList("HP:0001250", "HP:0004322"),
                priorSummary.getTopGenePriors().get(0).getMatchedHpoTerms()
        );

        VariantInterpretation candidate = variant("PASS", "0/1", "FGFR3");
        candidate.applyRanking(
                "REVIEW",
                10.0d,
                Collections.singletonMap("base", 10.0d),
                Collections.emptyMap(),
                "Base review tier",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        VariantInterpretation common = variant("PASS", "0/1", "FGFR3");
        common.applyRanking(
                "FILTERED_COMMON",
                -20.0d,
                Collections.singletonMap("population_frequency", -20.0d),
                Collections.emptyMap(),
                "Common frequency hard gate",
                Collections.emptyList(),
                Collections.singletonList("Common population frequency"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );

        PhenotypeAwareSummary result = new PhenotypeAwareRerankingService(properties).apply(
                Arrays.asList(candidate, common),
                input.withGenePriorSummary(priorSummary)
        );

        assertTrue(result.isRankingApplied());
        assertTrue(candidate.getScoreComponents().containsKey("phenotype_support"));
        assertEquals("FILTERED_COMMON", common.getPriorityTier());
        assertFalse(common.getScoreComponents().containsKey("phenotype_support"));
    }

    private VariantInterpretation variant(String filterStatus, String genotype, String gene) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "4", 1800000, "A", "G"),
                "synthetic",
                genotype,
                30,
                99,
                "18,12",
                0.4d,
                filterStatus,
                "SNV",
                "synthetic-record"
        );
        return new VariantInterpretation(
                "GRCh38:4:1800000:A:G:" + gene,
                observation,
                Collections.singletonList(
                        new TranscriptConsequence(gene, null, null, null, null, "missense_variant")
                ),
                Collections.emptyList()
        );
    }
}
