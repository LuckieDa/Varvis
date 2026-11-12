package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phen2GeneKnowledgebaseGenePhenotypePriorProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void aggregatesNormalizedPriorForSubmittedHpoSet() throws Exception {
        Path knowledgebase = Files.createDirectories(tempDir.resolve("Knowledgebase"));
        writeCandidateList(knowledgebase, "HP:0000001",
                "1\tGENE1\t1\t10.0\tSeedGene",
                "2\tGENE2\t2\t5.0\tPredicted");
        writeCandidateList(knowledgebase, "HP:0000002",
                "1\tGENE3\t3\t8.0\tSeedGene",
                "2\tGENE1\t1\t2.0\tPredicted");

        GenePhenotypePriorSummary summary = provider(tempDir, 50).summarize(
                PhenotypeInputNormalizer.summarize(Arrays.asList("HP:0000002", "HP:0000001"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals("PHEN2GENE_LOCAL_KB", summary.getSource());
        assertEquals(3, summary.getGenesWithPrior());
        GenePhenotypePrior first = summary.getTopGenePriors().get(0);
        assertEquals("GENE1", first.getGeneSymbol());
        assertEquals(1.0d, first.getPhenotypeScore(), 0.00001d);
        assertEquals(1, first.getPhenotypeRank());
        assertEquals(Arrays.asList("HP:0000001", "HP:0000002"), first.getMatchedHpoTerms());
        assertEquals("GENE3", summary.getTopGenePriors().get(1).getGeneSymbol());
        assertEquals(8.0d / 12.0d, summary.getTopGenePriors().get(1).getPhenotypeScore(), 0.00001d);
    }

    @Test
    void appliesDisplayLimitAfterBuildingFullRanking() throws Exception {
        Path knowledgebase = Files.createDirectories(tempDir.resolve("Knowledgebase"));
        writeCandidateList(knowledgebase, "HP:0000001",
                "1\tGENE1\t1\t10.0\tSeedGene",
                "2\tGENE2\t2\t5.0\tPredicted");

        GenePhenotypePriorSummary summary = provider(tempDir, 1).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0000001"))
        );

        assertEquals(1, summary.getGenesWithPrior());
        assertEquals("GENE1", summary.getTopGenePriors().get(0).getGeneSymbol());
    }

    @Test
    void partialHpoCoverageRemainsAvailableAndExplained() throws Exception {
        Path knowledgebase = Files.createDirectories(tempDir.resolve("Knowledgebase"));
        writeCandidateList(knowledgebase, "HP:0000001",
                "1\tGENE1\t1\t10.0\tSeedGene");

        GenePhenotypePriorSummary summary = provider(tempDir, 50).summarize(
                PhenotypeInputNormalizer.summarize(Arrays.asList("HP:0000001", "HP:0000002"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertTrue(summary.getWarnings().toString().contains("PHEN2GENE_KB_PARTIAL_HPO_COVERAGE"));
        assertFalse(summary.getWarnings().toString().contains(tempDir.toString()));
    }

    @Test
    void invalidKnowledgebasePathReturnsExplicitUnavailableReason() throws Exception {
        Path file = tempDir.resolve("not-a-directory.tsv");
        Files.write(file, Collections.singletonList("not a directory"), StandardCharsets.UTF_8);

        GenePhenotypePriorSummary summary = provider(file, 50).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0000001"))
        );

        assertEquals(GenePhenotypePriorStatus.UNAVAILABLE, summary.getStatus());
        assertTrue(summary.getWarnings().toString().contains("PHEN2GENE_KB_PATH_NOT_DIRECTORY"));
        assertFalse(summary.getWarnings().toString().contains(file.toString()));
    }

    @Test
    void factorySelectsKnowledgebaseProvider() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypePrior().setSource("PHEN2GENE_LOCAL_KB");

        assertTrue(GenePhenotypePriorProviderFactory.create(properties)
                instanceof Phen2GeneKnowledgebaseGenePhenotypePriorProvider);
    }

    private Phen2GeneKnowledgebaseGenePhenotypePriorProvider provider(Path root, int maxGenes) {
        VariantAnalysisProperties.PhenotypePrior properties = new VariantAnalysisProperties.PhenotypePrior();
        properties.setEnabled(true);
        properties.setSource("PHEN2GENE_LOCAL_KB");
        properties.setLocalFile(root.toString());
        properties.setVersion("Phen2Gene-test;hpo2gene-test");
        properties.setMaxGenes(maxGenes);
        return new Phen2GeneKnowledgebaseGenePhenotypePriorProvider(properties);
    }

    private void writeCandidateList(Path knowledgebase, String hpoTerm, String... rows) throws Exception {
        Path file = knowledgebase.resolve(hpoTerm.replace(':', '_') + ".candidate_gene_list");
        StringBuilder content = new StringBuilder("Rank\tGene\tID\tScore\tStatus\n");
        for (String row : rows) {
            content.append(row).append('\n');
        }
        Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
    }
}
