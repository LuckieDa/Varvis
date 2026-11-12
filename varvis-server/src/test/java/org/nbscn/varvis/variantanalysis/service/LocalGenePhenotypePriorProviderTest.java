package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGenePhenotypePriorProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesLocalTsvAndRanksMatchedGenePriors() throws Exception {
        GenePhenotypePriorSummary summary = enabledProvider(50).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertTrue(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals("LOCAL_GENE_PRIOR", summary.getSource());
        assertEquals("mock-v1", summary.getSourceVersion());
        assertEquals(2, summary.getGenesWithPrior());
        assertEquals(2, summary.getTopGenePriors().size());

        GenePhenotypePrior first = summary.getTopGenePriors().get(0);
        assertEquals("MECP2", first.getGeneSymbol());
        assertEquals("ENSG00000169057", first.getGeneId());
        assertEquals(0.95d, first.getPhenotypeScore(), 0.0001d);
        assertEquals(1, first.getPhenotypeRank());
        assertEquals(Collections.singletonList("HP:0001250"), first.getMatchedHpoTerms());

        GenePhenotypePrior second = summary.getTopGenePriors().get(1);
        assertEquals("SCN1A", second.getGeneSymbol());
        assertEquals(2, second.getPhenotypeRank());
    }

    @Test
    void mergesDuplicateGeneMatchesAcrossHpoTermsUsingHighestScore() throws Exception {
        GenePhenotypePriorSummary summary = enabledProvider(50).summarize(
                PhenotypeInputNormalizer.summarize(Arrays.asList("HP:0001250", "HP:0004322"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals(3, summary.getGenesWithPrior());
        GenePhenotypePrior mecp2 = summary.getTopGenePriors().get(0);
        assertEquals("MECP2", mecp2.getGeneSymbol());
        assertEquals(0.95d, mecp2.getPhenotypeScore(), 0.0001d);
        assertEquals(Arrays.asList("HP:0001250", "HP:0004322"), mecp2.getMatchedHpoTerms());
        assertTrue(String.valueOf(mecp2.getEvidence()).contains("Mock seizure phenotype association"));
        assertTrue(String.valueOf(mecp2.getEvidence()).contains("Mock secondary phenotype association"));
    }

    @Test
    void reportsMalformedRowsWithoutFailingSummary() throws Exception {
        GenePhenotypePriorSummary summary = enabledProvider(50).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertTrue(String.valueOf(summary.getWarnings())
                .contains("Malformed local gene prior row skipped"));
    }

    @Test
    void appliesMaxGenesLimitAfterRanking() throws Exception {
        GenePhenotypePriorSummary summary = enabledProvider(1).summarize(
                PhenotypeInputNormalizer.summarize(Arrays.asList("HP:0001250", "HP:0004322"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals(1, summary.getGenesWithPrior());
        assertEquals(1, summary.getTopGenePriors().size());
        assertEquals("MECP2", summary.getTopGenePriors().get(0).getGeneSymbol());
    }

    @Test
    void returnsNoPriorWhenValidHpoTermsHaveNoLocalMatch() throws Exception {
        GenePhenotypePriorSummary summary = enabledProvider(50).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0000118"))
        );

        assertTrue(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.NO_PRIOR, summary.getStatus());
        assertEquals(0, summary.getGenesWithPrior());
        assertTrue(summary.getTopGenePriors().isEmpty());
        assertTrue(String.valueOf(summary.getWarnings()).contains("absence of a prior is not benign evidence"));
    }

    @Test
    void emptyLocalFileReturnsNoPriorWithoutTreatingAbsenceAsBenign() throws Exception {
        Path emptyFile = tempDir.resolve("empty-gene-prior.tsv");
        Files.write(emptyFile, Collections.<String>emptyList());
        VariantAnalysisProperties.PhenotypePrior properties = baseProperties(50);
        properties.setLocalFile(emptyFile.toString());

        GenePhenotypePriorSummary summary = new LocalGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertTrue(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.NO_PRIOR, summary.getStatus());
        assertTrue(summary.getTopGenePriors().isEmpty());
        assertTrue(String.valueOf(summary.getWarnings()).contains("not benign evidence"));
        assertFalse(String.valueOf(summary.getWarnings()).contains(emptyFile.toString()));
    }

    @Test
    void disabledProviderDoesNotGenerateGenePriors() throws Exception {
        VariantAnalysisProperties.PhenotypePrior properties = baseProperties(50);
        properties.setEnabled(false);

        GenePhenotypePriorSummary summary = new LocalGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertFalse(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.DISABLED, summary.getStatus());
        assertEquals(0, summary.getGenesWithPrior());
        assertTrue(String.valueOf(summary.getWarnings()).contains("provider is disabled"));
    }

    @Test
    void missingLocalFileDoesNotFailAndReturnsUnavailable() throws Exception {
        VariantAnalysisProperties.PhenotypePrior properties = baseProperties(50);
        properties.setLocalFile(tempDir.resolve("missing.tsv").toString());

        GenePhenotypePriorSummary summary = new LocalGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertTrue(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.UNAVAILABLE, summary.getStatus());
        assertEquals(0, summary.getGenesWithPrior());
        String warnings = String.valueOf(summary.getWarnings());
        assertTrue(warnings.contains("Provider unavailable does not imply low risk"));
        assertFalse(warnings.contains(tempDir.toString()));
    }

    @Test
    void invalidHpoInputReturnsInvalidInputSummary() throws Exception {
        GenePhenotypePriorSummary summary = enabledProvider(50).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("not-hpo"))
        );

        assertFalse(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.INVALID_INPUT, summary.getStatus());
        assertEquals(1, summary.getInputHpoCount());
        assertEquals(0, summary.getValidHpoCount());
    }

    @Test
    void noHpoInputKeepsNotGeneratedSummary() throws Exception {
        GenePhenotypePriorSummary summary = enabledProvider(50).summarize(
                PhenotypeInputNormalizer.summarize(Collections.<String>emptyList())
        );

        assertFalse(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.INVALID_INPUT, summary.getStatus());
        assertTrue(summary.getWarnings().isEmpty());
    }

    private LocalGenePhenotypePriorProvider enabledProvider(int maxGenes) throws Exception {
        return new LocalGenePhenotypePriorProvider(baseProperties(maxGenes));
    }

    private VariantAnalysisProperties.PhenotypePrior baseProperties(int maxGenes) throws Exception {
        VariantAnalysisProperties.PhenotypePrior properties = new VariantAnalysisProperties.PhenotypePrior();
        properties.setEnabled(true);
        properties.setSource("LOCAL_GENE_PRIOR");
        properties.setVersion("mock-v1");
        properties.setMaxGenes(maxGenes);
        properties.setLocalFile(fixturePath().toString());
        return properties;
    }

    private Path fixturePath() throws Exception {
        URL resource = getClass().getResource("/variantanalysis/phenotype-prior/mock-gene-prior.tsv");
        assertNotNull(resource);
        return Paths.get(resource.toURI());
    }
}
