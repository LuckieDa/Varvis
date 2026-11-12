package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VcfPreflightSummary;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhenotypePriorGeneIntervalServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void intervalFileRescueIsAssemblyAwareAndMatchesPriorGenesOnly() throws Exception {
        Path intervalFile = tempDir.resolve("gene-intervals.tsv");
        Files.write(intervalFile, Arrays.asList(
                "assembly\tgene_symbol\tchromosome\tstart\tend",
                "GRCh37\tPPA2\tchr4\t106300000\t106400000",
                "GRCh38\tPPA2\tchr4\t105900000\t106000000",
                "GRCh37\tOTHER\tchr4\t106300000\t106400000"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setPhenotypePriorRescueIntervalFile(intervalFile.toString());

        PhenotypePriorGeneIntervalService service =
                new PhenotypePriorGeneIntervalService(properties, null);
        VariantInterpretation ppa2Variant = variant(
                "GRCh37",
                "chr4",
                106320322,
                "C",
                "T"
        );

        AnalysisContext grch37Context = largeFileContext(GenomeAssembly.GRCh37, summaryWithPrior("PPA2"));
        assertNotNull(service);
        assertNotNull(grch37Context);
        Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> grch37Matches =
                service.rescueMatches(Collections.singletonList(ppa2Variant), grch37Context);
        Map<VariantInterpretation, PhenotypePriorGeneIntervalService.GeneIntervalMatch> grch38Matches =
                service.rescueMatches(
                        Collections.singletonList(ppa2Variant),
                        largeFileContext(GenomeAssembly.GRCh38, summaryWithPrior("PPA2"))
                );

        assertTrue(grch37Matches.containsKey(ppa2Variant));
        assertEquals("PPA2", grch37Matches.get(ppa2Variant).getGeneSymbol());
        assertTrue(grch38Matches.isEmpty());
    }

    @Test
    void missingIntervalFileIsExplicitlyUnavailableAndDoesNotCrash() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setPhenotypePriorRescueIntervalFile(
                tempDir.resolve("missing.tsv").toString()
        );
        PhenotypePriorGeneIntervalService service =
                new PhenotypePriorGeneIntervalService(properties, null);

        PhenotypePriorGeneIntervalService.RescueEvaluation evaluation =
                service.evaluateRescueMatches(
                        Collections.singletonList(variant("GRCh37", "chr4", 106320322, "C", "T")),
                        largeFileContext(GenomeAssembly.GRCh37, summaryWithPrior("PPA2"))
                );

        assertEquals("AVAILABLE", evaluation.getPhenotypePriorStatus());
        assertEquals(1, evaluation.getPhenotypePriorGeneCount());
        assertEquals("INTERVAL_RESOURCE_UNAVAILABLE", evaluation.getIntervalResourceStatus());
        assertEquals(0, evaluation.getRescueCandidateCount());
        assertTrue(evaluation.getMatches().isEmpty());
    }

    @Test
    void invalidIntervalFileIsReportedAsInvalid() throws Exception {
        Path intervalFile = tempDir.resolve("invalid.tsv");
        Files.write(intervalFile, Arrays.asList(
                "assembly\tgene_symbol\tchromosome\tstart\tend",
                "GRCh37\tPPA2\tchr4\tbad\t106400000"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setPhenotypePriorRescueIntervalFile(intervalFile.toString());
        PhenotypePriorGeneIntervalService service =
                new PhenotypePriorGeneIntervalService(properties, null);

        PhenotypePriorGeneIntervalService.RescueEvaluation evaluation =
                service.evaluateRescueMatches(
                        Collections.singletonList(variant("GRCh37", "chr4", 106320322, "C", "T")),
                        largeFileContext(GenomeAssembly.GRCh37, summaryWithPrior("PPA2"))
                );

        assertEquals("INVALID", evaluation.getIntervalResourceStatus());
        assertEquals(0, evaluation.getRescueCandidateCount());
        assertEquals(1, evaluation.getWarnings().size());
    }

    @Test
    void noPhenotypePriorIsDistinctFromIntervalResourceUnavailable() throws Exception {
        Path intervalFile = tempDir.resolve("gene-intervals.tsv");
        Files.write(intervalFile, Arrays.asList(
                "assembly\tgene_symbol\tchromosome\tstart\tend",
                "GRCh37\tPPA2\tchr4\t106300000\t106400000"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setPhenotypePriorRescueIntervalFile(intervalFile.toString());
        PhenotypePriorGeneIntervalService service =
                new PhenotypePriorGeneIntervalService(properties, null);

        PhenotypePriorGeneIntervalService.RescueEvaluation evaluation =
                service.evaluateRescueMatches(
                        Collections.singletonList(variant("GRCh37", "chr4", 106320322, "C", "T")),
                        largeFileContext(GenomeAssembly.GRCh37, PhenotypeAwareSummary.disabled())
                );

        assertEquals("NO_HPO_INPUT", evaluation.getPhenotypePriorStatus());
        assertEquals("NO_PHENOTYPE_PRIOR", evaluation.getIntervalResourceStatus());
        assertEquals(0, evaluation.getPhenotypePriorGeneCount());
        assertTrue(evaluation.getMatches().isEmpty());
    }

    @Test
    void rescueExpandsBeyondDisplayedPriorWithoutExpandingRankingSummary() throws Exception {
        Path knowledgebase = Files.createDirectories(tempDir.resolve("Knowledgebase"));
        Files.write(
                knowledgebase.resolve("HP_0000001.candidate_gene_list"),
                Arrays.asList(
                        "Rank\tGene\tID\tScore\tStatus",
                        "1\tGENE1\t1\t10.0\tSeedGene",
                        "2\tGENE2\t2\t9.0\tPredicted",
                        "3\tGENE3\t3\t8.0\tPredicted"
                ),
                StandardCharsets.UTF_8
        );
        Path intervalFile = tempDir.resolve("expanded-gene-intervals.tsv");
        Files.write(intervalFile, Arrays.asList(
                "assembly\tgene_symbol\tchromosome\tstart\tend",
                "GRCh37\tGENE3\tchr4\t106300000\t106400000"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setSource("PHEN2GENE_LOCAL_KB");
        properties.getPhenotypePrior().setLocalFile(knowledgebase.toString());
        properties.getPhenotypePrior().setVersion("test-kb");
        properties.getPhenotypePrior().setMaxGenes(1);
        properties.getLargeFile().setPhenotypePriorRescueMaxGenes(3);
        properties.getLargeFile().setPhenotypePriorRescueIntervalFile(intervalFile.toString());
        PhenotypeAwareSummary input = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0000001")
        );
        PhenotypeAwareSummary displayedSummary = input.withGenePriorSummary(
                GenePhenotypePriorProviderFactory.create(properties).summarize(input)
        );

        PhenotypePriorGeneIntervalService.RescueEvaluation evaluation =
                new PhenotypePriorGeneIntervalService(properties, null).evaluateRescueMatches(
                        Collections.singletonList(variant("GRCh37", "chr4", 106320322, "C", "T")),
                        largeFileContext(GenomeAssembly.GRCh37, displayedSummary)
                );

        assertEquals(1, displayedSummary.getGenePriorSummary().getGenesWithPrior());
        assertEquals(3, evaluation.getPhenotypePriorGeneCount());
        assertEquals(1, evaluation.getRescueCandidateCount());
        assertEquals("GENE3", evaluation.getMatches().values().iterator().next().getGeneSymbol());
    }

    @Test
    void rescueCapsVariantsPerGeneByQcConfidence() throws Exception {
        Path intervalFile = tempDir.resolve("capped-gene-intervals.tsv");
        Files.write(intervalFile, Arrays.asList(
                "assembly\tgene_symbol\tchromosome\tstart\tend",
                "GRCh37\tPPA2\tchr4\t106300000\t106400000"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setPhenotypePriorRescueIntervalFile(intervalFile.toString());
        properties.getLargeFile().setPhenotypePriorRescueMaxVariantsPerGene(2);
        VariantInterpretation low = variant("GRCh37", "chr4", 106320320, "C", "T", 20, 40, 0.30d);
        VariantInterpretation high = variant("GRCh37", "chr4", 106320322, "C", "T", 120, 99, 0.50d);
        VariantInterpretation medium = variant("GRCh37", "chr4", 106320324, "C", "T", 80, 80, 0.45d);

        PhenotypePriorGeneIntervalService.RescueEvaluation evaluation =
                new PhenotypePriorGeneIntervalService(properties, null).evaluateRescueMatches(
                        Arrays.asList(low, high, medium),
                        largeFileContext(GenomeAssembly.GRCh37, summaryWithPrior("PPA2"))
                );

        assertEquals(2, evaluation.getRescueCandidateCount());
        assertTrue(evaluation.getMatches().containsKey(high));
        assertTrue(evaluation.getMatches().containsKey(medium));
        assertTrue(evaluation.getWarnings().toString().contains("PHENOTYPE_PRIOR_RESCUE_PER_GENE_CAP"));
    }

    @Test
    void rescuePrefersExonAndSpliceBoundaryBeforeQcWithinGeneCap() throws Exception {
        Path intervalFile = tempDir.resolve("feature-gene-intervals.tsv");
        Files.write(intervalFile, Arrays.asList(
                "assembly\tgene_symbol\tchromosome\tstart\tend\tfeature_type",
                "GRCh37\tPPA2\tchr4\t106290234\t106395238\tGENE",
                "GRCh37\tPPA2\tchr4\t106320194\t106320321\tEXON",
                "GRCh37\tPPA2\tchr4\t106367540\t106367659\tEXON"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setPhenotypePriorRescueIntervalFile(intervalFile.toString());
        properties.getLargeFile().setPhenotypePriorRescueMaxVariantsPerGene(2);
        properties.getLargeFile().setPhenotypePriorRescuePaddingBp(2);
        VariantInterpretation deepIntronHighDp =
                variant("GRCh37", "chr4", 106350000, "C", "T", 500, 99, 0.50d);
        VariantInterpretation spliceBoundary =
                variant("GRCh37", "chr4", 106320322, "C", "T", 146, 99, 0.50d);
        VariantInterpretation exon =
                variant("GRCh37", "chr4", 106367589, "T", "C", 293, 99, 0.50d);

        PhenotypePriorGeneIntervalService.RescueEvaluation evaluation =
                new PhenotypePriorGeneIntervalService(properties, null).evaluateRescueMatches(
                        Arrays.asList(deepIntronHighDp, spliceBoundary, exon),
                        largeFileContext(GenomeAssembly.GRCh37, summaryWithPrior("PPA2"))
                );

        assertEquals(2, evaluation.getRescueCandidateCount());
        assertTrue(evaluation.getMatches().containsKey(spliceBoundary));
        assertTrue(evaluation.getMatches().containsKey(exon));
        assertFalse(evaluation.getMatches().containsKey(deepIntronHighDp));
    }

    @Test
    void springContextUsesBoundPropertiesConstructor() throws Exception {
        Path intervalFile = tempDir.resolve("spring-bound-intervals.tsv");
        Files.write(intervalFile, Arrays.asList(
                "assembly\tgene_symbol\tchromosome\tstart\tend\tfeature_type",
                "GRCh37\tGENE1\tchr4\t100\t200\tEXON"
        ), StandardCharsets.UTF_8);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("variant-analysis.large-file.phenotype-prior-rescue-enabled", "true");
        values.put("variant-analysis.large-file.phenotype-prior-rescue-max-genes", "5000");
        values.put("variant-analysis.large-file.phenotype-prior-rescue-interval-file",
                intervalFile.toString());

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test-rescue-config", values)
            );
            context.register(PropertiesTestConfig.class, PhenotypePriorGeneIntervalService.class);
            context.refresh();

            PhenotypePriorGeneIntervalService.RescueEvaluation evaluation = context
                    .getBean(PhenotypePriorGeneIntervalService.class)
                    .evaluateRescueMatches(
                            Collections.singletonList(variant("GRCh37", "chr4", 150, "A", "G")),
                            largeFileContext(GenomeAssembly.GRCh37, summaryWithPrior("GENE1"))
                    );

            assertEquals("AVAILABLE", evaluation.getIntervalResourceStatus());
            assertEquals(1, evaluation.getRescueCandidateCount());
        } finally {
            context.close();
        }
    }

    @Configuration
    @EnableConfigurationProperties(VariantAnalysisProperties.class)
    static class PropertiesTestConfig {
    }

    private static VariantInterpretation variant(String assembly,
                                                 String chromosome,
                                                 int position,
                                                 String reference,
                                                 String alternate) {
        return variant(assembly, chromosome, position, reference, alternate, 30, 99, 0.4d);
    }

    private static VariantInterpretation variant(String assembly,
                                                 String chromosome,
                                                 int position,
                                                 String reference,
                                                 String alternate,
                                                 int depth,
                                                 int genotypeQuality,
                                                 double alleleBalance) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey(assembly, chromosome, position, reference, alternate),
                "proband",
                "0/1",
                depth,
                genotypeQuality,
                "18,12",
                alleleBalance,
                "PASS",
                "SNV",
                chromosome + "|" + position + "|" + reference + "|" + alternate + "|1"
        );
        return new VariantInterpretation(
                assembly + ":" + chromosome + ":" + position + ":" + reference + ":" + alternate,
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private static AnalysisContext largeFileContext(GenomeAssembly assembly,
                                                    PhenotypeAwareSummary phenotypeSummary) {
        return new AnalysisContext(
                "analysis",
                assembly,
                AnalysisMode.WES,
                new VcfPreflightSummary(
                        100000,
                        100000,
                        0,
                        1,
                        "proband",
                        100000,
                        0,
                        0,
                        0,
                        false,
                        false,
                        true,
                        Collections.singletonMap("PASS", 100000),
                        true,
                        "test large-file mode"
                ),
                phenotypeSummary
        );
    }

    private static PhenotypeAwareSummary summaryWithPrior(String geneSymbol) {
        GenePhenotypePrior prior = new GenePhenotypePrior(
                geneSymbol,
                null,
                0.99d,
                1,
                "TEST_PRIOR",
                "test",
                Collections.singletonList("HP:0000001"),
                Collections.singletonList("test phenotype prior"),
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.EPOCH,
                Collections.emptyList()
        );
        GenePhenotypePriorSummary priorSummary = new GenePhenotypePriorSummary(
                true,
                "TEST_PRIOR",
                "test",
                GenePhenotypePriorStatus.AVAILABLE,
                1,
                1,
                1,
                Collections.singletonList(prior),
                Collections.emptyList(),
                Instant.EPOCH
        );
        return new PhenotypeAwareSummary(
                1,
                1,
                0,
                0,
                Collections.singletonList("HP:0000001"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                false,
                priorSummary
        );
    }
}
