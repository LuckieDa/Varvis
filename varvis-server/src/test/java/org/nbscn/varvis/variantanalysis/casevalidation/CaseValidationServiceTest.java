package org.nbscn.varvis.variantanalysis.casevalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.service.PhenotypeAwareRerankingService;
import org.nbscn.varvis.variantanalysis.service.PhenotypeInputNormalizer;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseValidationServiceTest {

    private final CaseValidationService service = new CaseValidationService(new ObjectMapper());

    @Test
    void loadsSyntheticCaseSchemaAndValidatesHpoTerms() throws Exception {
        CaseValidationCase validationCase = loadSyntheticCase();

        assertEquals("synthetic-phenotype-reranking-demo", validationCase.getCaseId());
        assertEquals("GRCh38", validationCase.getAssembly());
        assertEquals(Arrays.asList("HP:0001250", "HP:0004322"), validationCase.getHpoTerms());
        assertEquals(Arrays.asList(1, 5, 10, 50), validationCase.getExpected().getHitK());
        assertTrue(service.validationErrors(validationCase).isEmpty());

        validationCase.setHpoTerms(Collections.singletonList("bad-hpo"));
        assertFalse(service.validationErrors(validationCase).isEmpty());
    }

    @Test
    void comparesNoHpoAndWithHpoRanksUsingSyntheticTruthVariant() throws Exception {
        CaseValidationCase validationCase = loadSyntheticCase();
        VariantInterpretation truthNoHpo = variant("truth", "1", 101, "A", "G", "SYNPHENO1", 20.0d);
        VariantInterpretation decoyNoHpo = variant("decoy", "2", 202, "C", "T", "DECOY1", 25.0d);
        VariantInterpretation truthWithHpo = variant("truth", "chr1", 101, "A", "G", "SYNPHENO1", 20.0d);
        VariantInterpretation decoyWithHpo = variant("decoy", "2", 202, "C", "T", "DECOY1", 25.0d);

        PhenotypeAwareSummary summary = enabledReranker().apply(
                Arrays.asList(truthWithHpo, decoyWithHpo),
                summaryWithPrior(GenePhenotypePriorStatus.AVAILABLE,
                        Collections.singletonList(prior("SYNPHENO1", 1.0d, 1)))
        );

        CaseValidationResult result = service.validate(
                validationCase,
                new CaseValidationRun("no-hpo", Arrays.asList(decoyNoHpo, truthNoHpo),
                        PhenotypeAwareSummary.disabled(), 0, 0, 0, "synthetic", "opts", null),
                new CaseValidationRun("with-hpo", Arrays.asList(decoyWithHpo, truthWithHpo),
                        summary, 1, 1, 1, "synthetic", "opts", null)
        );

        CaseValidationResult.TruthVariantResult truth = result.getTruthVariantResults().get(0);
        assertTrue(truth.isExactMatch());
        assertFalse(truth.isGeneLevelFallback());
        assertEquals(Integer.valueOf(2), truth.getRankNoHpo());
        assertEquals(Integer.valueOf(1), truth.getRankWithHpo());
        assertEquals(Integer.valueOf(1), truth.getRankDelta());
        assertFalse(truth.getHitNoHpo().get(1));
        assertTrue(truth.getHitWithHpo().get(1));
        assertTrue(truth.isPhenotypeSupportPresent());
        assertEquals("AVAILABLE", truth.getGeneAnnotationStatus());
        assertNull(truth.getMissReason());

        CaseValidationResult.TruthGeneResult gene = result.getTruthGeneResults().get(0);
        assertEquals(Integer.valueOf(2), gene.getBestRankNoHpo());
        assertEquals(Integer.valueOf(1), gene.getBestRankWithHpo());
        assertTrue(gene.isPhenotypeSupportPresent());
    }

    @Test
    void reportsGeneLevelFallbackWhenExactAlleleDoesNotMatch() throws Exception {
        CaseValidationCase validationCase = loadSyntheticCase();
        VariantInterpretation sameGeneDifferentAllele =
                variant("same-gene", "1", 999, "C", "T", "SYNPHENO1", 30.0d);

        CaseValidationResult result = service.validate(
                validationCase,
                CaseValidationRun.of("no-hpo", Collections.singletonList(sameGeneDifferentAllele)),
                CaseValidationRun.of("with-hpo", Collections.singletonList(sameGeneDifferentAllele))
        );

        CaseValidationResult.TruthVariantResult truth = result.getTruthVariantResults().get(0);
        assertFalse(truth.isExactMatch());
        assertTrue(truth.isGeneLevelFallback());
        assertEquals(CaseValidationMissReason.VARIANT_NORMALIZATION_MISMATCH, truth.getMissReason());
    }

    @Test
    void reportsAnnotationUnavailableWithoutCallingItBenign() throws Exception {
        CaseValidationCase validationCase = loadSyntheticCase();
        VariantInterpretation missingGene = variant("missing-gene", "1", 101, "A", "G", null, 30.0d);

        CaseValidationResult result = service.validate(
                validationCase,
                CaseValidationRun.of("no-hpo", Collections.singletonList(missingGene)),
                CaseValidationRun.of("with-hpo", Collections.singletonList(missingGene))
        );

        CaseValidationResult.TruthVariantResult truth = result.getTruthVariantResults().get(0);
        assertEquals("GENE_ANNOTATION_UNAVAILABLE", truth.getGeneAnnotationStatus());
        assertEquals(CaseValidationMissReason.GENE_ANNOTATION_UNAVAILABLE, truth.getMissReason());
        assertFalse(String.valueOf(truth.getMissReason()).toLowerCase().contains("benign"));
    }

    @Test
    void reportsPhenotypePriorUnavailableSeparatelyFromLowRisk() throws Exception {
        CaseValidationCase validationCase = loadSyntheticCase();
        VariantInterpretation truth = variant("truth", "1", 101, "A", "G", "SYNPHENO1", 30.0d);

        CaseValidationResult result = service.validate(
                validationCase,
                CaseValidationRun.of("no-hpo", Collections.singletonList(truth)),
                new CaseValidationRun("with-hpo", Collections.singletonList(truth),
                        summaryWithPrior(GenePhenotypePriorStatus.UNAVAILABLE,
                                Collections.<GenePhenotypePrior>emptyList()),
                        0, 0, 0, "synthetic", "opts", null)
        );

        assertEquals(CaseValidationMissReason.PHENOTYPE_PRIOR_UNAVAILABLE,
                result.getTruthVariantResults().get(0).getMissReason());
    }

    @Test
    void hardGateIsReportedAndPhenotypeSupportDoesNotOverrideIt() throws Exception {
        CaseValidationCase validationCase = loadSyntheticCase();
        VariantInterpretation common = variant("common", "1", 101, "A", "G", "SYNPHENO1", 40.0d);
        common.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_COMMON,
                true,
                true,
                false,
                null,
                Collections.singletonList("gnomAD common")
        );
        common.applyRanking("FILTERED_COMMON", -1000.0d, Collections.emptyMap(),
                Collections.singletonList("Filtered common"), Collections.emptyList());

        PhenotypeAwareSummary summary = enabledReranker().apply(
                Collections.singletonList(common),
                summaryWithPrior(GenePhenotypePriorStatus.AVAILABLE,
                        Collections.singletonList(prior("SYNPHENO1", 1.0d, 1)))
        );

        CaseValidationResult result = service.validate(
                validationCase,
                CaseValidationRun.of("no-hpo", Collections.singletonList(common)),
                new CaseValidationRun("with-hpo", Collections.singletonList(common),
                        summary, 0, 0, 0, "synthetic", "opts", null)
        );

        CaseValidationResult.TruthVariantResult truth = result.getTruthVariantResults().get(0);
        assertEquals("FILTERED_COMMON", truth.getHardGateStatus());
        assertEquals(CaseValidationMissReason.FILTERED_COMMON, truth.getMissReason());
        assertFalse(truth.isPhenotypeSupportPresent());
    }

    @Test
    void writesJsonCsvAndMarkdownReports(@TempDir Path tempDir) throws Exception {
        CaseValidationCase validationCase = loadSyntheticCase();
        VariantInterpretation truth = variant("truth", "1", 101, "A", "G", "SYNPHENO1", 30.0d);
        CaseValidationResult result = service.validate(
                validationCase,
                CaseValidationRun.of("no-hpo", Collections.singletonList(truth)),
                CaseValidationRun.of("with-hpo", Collections.singletonList(truth))
        );

        CaseValidationReportWriter.ReportPaths paths =
                new CaseValidationReportWriter(new ObjectMapper()).write(tempDir, result);

        assertTrue(Files.size(paths.getJson()) > 0);
        assertTrue(Files.size(paths.getCsv()) > 0);
        assertTrue(Files.size(paths.getMarkdown()) > 0);
        String csv = new String(Files.readAllBytes(paths.getCsv()), StandardCharsets.UTF_8);
        String markdown = new String(Files.readAllBytes(paths.getMarkdown()), StandardCharsets.UTF_8);
        assertTrue(csv.contains("caseId,assembly,inputAssembly,annotationAssembly,resourceAssembly"));
        assertTrue(csv.contains("truthType,geneSymbol,variant,rankNoHpo,rankWithHpo"));
        assertTrue(markdown.contains("No-HPO vs With-HPO Case Validation"));
        assertTrue(markdown.contains("SYNPHENO1"));
    }

    private CaseValidationCase loadSyntheticCase() throws Exception {
        Path path = Paths.get(resourceUri("variantanalysis/casevalidation/synthetic-no-hpo-with-hpo.case.json"));
        CaseValidationCase validationCase = service.load(path);
        assertNotNull(validationCase);
        return validationCase;
    }

    private java.net.URI resourceUri(String resource) throws URISyntaxException {
        return Thread.currentThread().getContextClassLoader().getResource(resource).toURI();
    }

    private PhenotypeAwareRerankingService enabledReranker() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypeRanking().setEnabled(true);
        properties.getPhenotypeRanking().setMaxScoreBoost(8.0d);
        return new PhenotypeAwareRerankingService(properties);
    }

    private PhenotypeAwareSummary summaryWithPrior(GenePhenotypePriorStatus status,
                                                   List<GenePhenotypePrior> priors) {
        return PhenotypeInputNormalizer.summarize(Arrays.asList("HP:0001250", "HP:0004322"))
                .withGenePriorSummary(new GenePhenotypePriorSummary(
                        true,
                        "SYNTHETIC_GENE_PRIOR",
                        "test-v1",
                        status,
                        2,
                        2,
                        priors == null ? 0 : priors.size(),
                        priors,
                        Collections.emptyList(),
                        Instant.parse("2026-06-30T00:00:00Z")
                ));
    }

    private GenePhenotypePrior prior(String gene, double score, int rank) {
        return new GenePhenotypePrior(
                gene,
                "ENSG_" + gene,
                score,
                rank,
                "SYNTHETIC_GENE_PRIOR",
                "test-v1",
                Arrays.asList("HP:0001250", "HP:0004322"),
                Collections.singletonList("synthetic phenotype-gene support"),
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.parse("2026-06-30T00:00:00Z"),
                Collections.emptyList()
        );
    }

    private VariantInterpretation variant(String id,
                                          String chrom,
                                          int pos,
                                          String ref,
                                          String alt,
                                          String gene,
                                          double score) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", chrom, pos, ref, alt),
                "synthetic-proband",
                "0/1",
                40,
                99,
                "20,20",
                0.5d,
                "PASS",
                "SNV",
                chrom + "\t" + pos + "\t.\t" + ref + "\t" + alt + "\t.\tPASS\t."
        );
        VariantInterpretation variant = new VariantInterpretation(
                id,
                observation,
                gene == null
                        ? Collections.<TranscriptConsequence>emptyList()
                        : Collections.singletonList(new TranscriptConsequence(
                                gene,
                                "ENSG_" + gene,
                                "ENST_" + gene,
                                "c.1A>G",
                                "p.Lys1Arg",
                                "missense_variant"
                        )),
                Collections.emptyList()
        );
        variant.applyRanking("REVIEW", score, Collections.singletonMap("base", score),
                Collections.singletonList("base ranking"), Collections.emptyList());
        return variant;
    }
}
