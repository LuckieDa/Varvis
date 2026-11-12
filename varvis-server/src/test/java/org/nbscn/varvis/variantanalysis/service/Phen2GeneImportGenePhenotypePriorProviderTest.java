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

class Phen2GeneImportGenePhenotypePriorProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesStandardPhen2GeneImportTsv() throws Exception {
        Path file = writeImport(
                row("HP:0001250;HP:0004322", "MECP2", "4204", "0.89883", "4",
                        "PHEN2GENE", "phen2gene-import-v1", "HP:0001250;HP:0004322",
                        "Phen2Gene candidate gene prior", "SeedGene"),
                row("HP:9999999", "OTHER", "1", "0.99", "1",
                        "PHEN2GENE", "phen2gene-import-v1", "HP:9999999",
                        "Unmatched row", "SeedGene")
        );

        GenePhenotypePriorSummary summary = provider(file).summarize(
                PhenotypeInputNormalizer.summarize(Arrays.asList("HP:0001250", "HP:0004322"))
        );

        assertTrue(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals("PHEN2GENE_IMPORT", summary.getSource());
        assertEquals("phen2gene-import-v1", summary.getSourceVersion());
        assertEquals(1, summary.getGenesWithPrior());
        GenePhenotypePrior prior = summary.getTopGenePriors().get(0);
        assertEquals("MECP2", prior.getGeneSymbol());
        assertEquals("4204", prior.getGeneId());
        assertEquals(0.89883d, prior.getPhenotypeScore(), 0.00001d);
        assertEquals(4, prior.getPhenotypeRank());
        assertEquals(Arrays.asList("HP:0001250", "HP:0004322"), prior.getMatchedHpoTerms());
        assertTrue(String.valueOf(prior.getEvidence()).contains("Phen2Gene candidate gene prior"));
        assertTrue(String.valueOf(prior.getEvidence()).contains("Phen2Gene status: SeedGene"));
    }

    @Test
    void usesOnlyRowsMatchingInputHpoByDefault() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "MECP2", "4204", "0.80", "5",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Matched", "SeedGene"),
                row("HP:0004322", "FGFR3", "2261", "0.99", "1",
                        "PHEN2GENE", "p2g-v1", "HP:0004322", "Unmatched", "SeedGene")
        );

        GenePhenotypePriorSummary summary = provider(file).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals(1, summary.getTopGenePriors().size());
        assertEquals("MECP2", summary.getTopGenePriors().get(0).getGeneSymbol());
    }

    @Test
    void canReadAllRowsWhenMatchingHpoIsNotRequired() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "MECP2", "4204", "0.80", "5",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Matched", "SeedGene"),
                row("HP:0004322", "FGFR3", "2261", "0.99", "1",
                        "PHEN2GENE", "p2g-v1", "HP:0004322", "Unmatched but imported", "SeedGene")
        );
        VariantAnalysisProperties.PhenotypePrior properties = properties(file);
        properties.setRequireMatchingHpo(false);

        GenePhenotypePriorSummary summary = new Phen2GeneImportGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals(2, summary.getGenesWithPrior());
        assertEquals("FGFR3", summary.getTopGenePriors().get(0).getGeneSymbol());
        assertEquals(Collections.singletonList("HP:0004322"),
                summary.getTopGenePriors().get(0).getMatchedHpoTerms());
    }

    @Test
    void duplicateGeneRowsKeepHighestScoreBestRankAndMergeDetails() throws Exception {
        Path file = writeImport(
                row("HP:0004322", "MECP2", "4204", "0.70", "1",
                        "PHEN2GENE", "p2g-low", "HP:0004322", "Short stature support", "Predicted"),
                row("HP:0001250", "MECP2", "4204", "0.95", "4",
                        "PHEN2GENE", "p2g-high", "HP:0001250", "Seizure support", "SeedGene")
        );

        GenePhenotypePriorSummary summary = provider(file).summarize(
                PhenotypeInputNormalizer.summarize(Arrays.asList("HP:0001250", "HP:0004322"))
        );

        GenePhenotypePrior prior = summary.getTopGenePriors().get(0);
        assertEquals("MECP2", prior.getGeneSymbol());
        assertEquals(0.95d, prior.getPhenotypeScore(), 0.0001d);
        assertEquals(1, prior.getPhenotypeRank());
        assertEquals("p2g-high", prior.getSourceVersion());
        assertEquals(Arrays.asList("HP:0004322", "HP:0001250"), prior.getMatchedHpoTerms());
        assertTrue(String.valueOf(prior.getEvidence()).contains("Short stature support"));
        assertTrue(String.valueOf(prior.getEvidence()).contains("Seizure support"));
    }

    @Test
    void malformedRowsAreSkippedByDefaultWithWarning() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "MECP2", "4204", "0.80", "5",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Matched", "SeedGene"),
                "HP:0001250\tBAD\t4204\tnot-a-score\t5\tPHEN2GENE\tp2g-v1\tHP:0001250\tBad\tSeedGene"
        );

        GenePhenotypePriorSummary summary = provider(file).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getStatus());
        assertEquals(1, summary.getGenesWithPrior());
        assertTrue(String.valueOf(summary.getWarnings()).contains("Malformed Phen2Gene import row skipped"));
    }

    @Test
    void malformedRowsCanMakeProviderUnavailableWithoutBlockingJob() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "MECP2", "4204", "0.80", "5",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Matched", "SeedGene"),
                "HP:0001250\tBAD\t4204\tnot-a-score\t5\tPHEN2GENE\tp2g-v1\tHP:0001250\tBad\tSeedGene"
        );
        VariantAnalysisProperties.PhenotypePrior properties = properties(file);
        properties.setFailOnMalformedRow(true);

        GenePhenotypePriorSummary summary = new Phen2GeneImportGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(GenePhenotypePriorStatus.UNAVAILABLE, summary.getStatus());
        assertTrue(summary.getTopGenePriors().isEmpty());
        String warnings = String.valueOf(summary.getWarnings());
        assertTrue(warnings.contains("Provider unavailable does not imply low risk"));
        assertFalse(warnings.contains(tempDir.toString()));
    }

    @Test
    void missingImportFileDoesNotExposeLocalPath() throws Exception {
        Path missingFile = tempDir.resolve("missing-phen2gene.tsv");

        GenePhenotypePriorSummary summary = provider(missingFile).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(GenePhenotypePriorStatus.UNAVAILABLE, summary.getStatus());
        String warnings = String.valueOf(summary.getWarnings());
        assertTrue(warnings.contains("Provider unavailable does not imply low risk"));
        assertFalse(warnings.contains(missingFile.toString()));
    }

    @Test
    void noMatchingHpoReturnsNoPriorWithoutBenignInterpretation() throws Exception {
        Path file = writeImport(
                row("HP:0004322", "FGFR3", "2261", "0.99", "1",
                        "PHEN2GENE", "p2g-v1", "HP:0004322", "Unmatched", "SeedGene")
        );

        GenePhenotypePriorSummary summary = provider(file).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(GenePhenotypePriorStatus.NO_PRIOR, summary.getStatus());
        assertTrue(summary.getTopGenePriors().isEmpty());
        assertTrue(String.valueOf(summary.getWarnings()).contains("not benign evidence"));
    }

    @Test
    void disabledProviderDoesNotGeneratePrior() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "MECP2", "4204", "0.80", "5",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Matched", "SeedGene")
        );
        VariantAnalysisProperties.PhenotypePrior properties = properties(file);
        properties.setEnabled(false);

        GenePhenotypePriorSummary summary = new Phen2GeneImportGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertFalse(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.DISABLED, summary.getStatus());
    }

    @Test
    void maxGenesLimitIsAppliedAfterRanking() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "GENE1", "1", "0.95", "1",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Top", "SeedGene"),
                row("HP:0001250", "GENE2", "2", "0.90", "2",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Second", "SeedGene")
        );
        VariantAnalysisProperties.PhenotypePrior properties = properties(file);
        properties.setMaxGenes(1);

        GenePhenotypePriorSummary summary = new Phen2GeneImportGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals(1, summary.getGenesWithPrior());
        assertEquals(1, summary.getTopGenePriors().size());
        assertEquals("GENE1", summary.getTopGenePriors().get(0).getGeneSymbol());
    }

    @Test
    void blankRowVersionFallsBackToConfiguredVersion() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "MECP2", "4204", "0.80", "5",
                        "PHEN2GENE", "", "HP:0001250", "Matched", "SeedGene")
        );
        VariantAnalysisProperties.PhenotypePrior properties = properties(file);
        properties.setVersion("configured-phen2gene-version");

        GenePhenotypePriorSummary summary = new Phen2GeneImportGenePhenotypePriorProvider(properties).summarize(
                PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
        );

        assertEquals("configured-phen2gene-version", summary.getSourceVersion());
        assertEquals("configured-phen2gene-version",
                summary.getTopGenePriors().get(0).getSourceVersion());
    }

    @Test
    void noHpoInputKeepsNotGeneratedSummary() throws Exception {
        Path file = writeImport(
                row("HP:0001250", "MECP2", "4204", "0.80", "5",
                        "PHEN2GENE", "p2g-v1", "HP:0001250", "Matched", "SeedGene")
        );

        GenePhenotypePriorSummary summary = provider(file).summarize(
                PhenotypeInputNormalizer.summarize(Collections.<String>emptyList())
        );

        assertFalse(summary.isEnabled());
        assertEquals(GenePhenotypePriorStatus.INVALID_INPUT, summary.getStatus());
        assertTrue(summary.getWarnings().isEmpty());
    }

    private Phen2GeneImportGenePhenotypePriorProvider provider(Path file) {
        return new Phen2GeneImportGenePhenotypePriorProvider(properties(file));
    }

    private VariantAnalysisProperties.PhenotypePrior properties(Path file) {
        VariantAnalysisProperties.PhenotypePrior properties = new VariantAnalysisProperties.PhenotypePrior();
        properties.setEnabled(true);
        properties.setSource("PHEN2GENE_IMPORT");
        properties.setVersion("phen2gene-import-v1");
        properties.setMaxGenes(50);
        properties.setLocalFile(file.toString());
        return properties;
    }

    private Path writeImport(String... rows) throws Exception {
        Path file = tempDir.resolve("phen2gene-import.tsv");
        StringBuilder content = new StringBuilder();
        content.append("hpo_terms\tgene_symbol\tgene_id\tphenotype_score\tphenotype_rank\t")
                .append("source\tsource_version\tmatched_hpo_terms\tevidence\tstatus\n");
        for (String row : rows) {
            content.append(row).append('\n');
        }
        Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String row(String hpoTerms,
                       String geneSymbol,
                       String geneId,
                       String score,
                       String rank,
                       String source,
                       String sourceVersion,
                       String matchedHpoTerms,
                       String evidence,
                       String status) {
        return String.join("\t", Arrays.asList(
                hpoTerms,
                geneSymbol,
                geneId,
                score,
                rank,
                source,
                sourceVersion,
                matchedHpoTerms,
                evidence,
                status
        ));
    }
}
