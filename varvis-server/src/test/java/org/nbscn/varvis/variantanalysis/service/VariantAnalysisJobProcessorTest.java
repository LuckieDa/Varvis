package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.NormalizationRunManifest;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.service.impl.VcfQcServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.VcfInfoBasicVariantAnnotator;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAnalysisJobProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void completesCopyOnlyQcPipelineAndKeepsNoncodingVariant() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "ready-job",
                "##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=REGION,Number=1,Type=String,Description=\"Region classification\">\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr4\t400\t.\tG\tA\t.\tPASS\tREGION=intergenic\tGT\t0/1\n"
        );

        processor(repository, copyOnlyNormalizer()).process(job.getId());

        VariantAnalysisJob completed = repository.findById(job.getId()).orElse(null);
        assertNotNull(completed);
        assertEquals(VariantAnalysisStatus.READY, completed.getStatus());
        assertEquals("proband", completed.getSelectedSampleId());
        assertFalse(completed.isNormalizationApplied());
        assertEquals("COPY_ONLY", completed.getNormalizationEngine());
        assertEquals(1, completed.getQcSummary().getVariantCount());
        assertEquals(1, repository.countObservations(job.getId()));
        assertEquals(1, repository.countInterpretations(job.getId()));
        assertEquals("4", completed.getObservations().get(0).getKey().getChromosome());
        assertEquals(
                "UNRESOLVED",
                repository.findInterpretations(job.getId(), 0, 1).get(0).getPriorityTier()
        );
        assertFalse(completed.getWarnings().isEmpty());
    }

    @Test
    void completesNormalizationManifestWithQcAlleleCountAndEvent() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "normalization-manifest-job",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n"
        );
        VcfNormalizer manifestNormalizer = (inputFile, workspace, genomeAssembly) -> {
            Path output = workspace.resolve("normalized.vcf");
            Files.copy(inputFile, output);
            NormalizationRunManifest manifest = new NormalizationRunManifest(
                    "PASSED", "GRCh38", "test-profile", "sha256-test",
                    "bcftools", "test-version", "command-digest", false,
                    1, null, 0, 0, 0, 5L,
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z",
                    null, null, "NOT_COLLECTED_PERFORMANCE_POLICY"
            );
            return new NormalizationResult(
                    output, true, "bcftools norm + sort", Collections.emptyList(), manifest
            );
        };

        processor(repository, manifestNormalizer).process(job.getId());

        String manifestJson = new String(
                Files.readAllBytes(job.getWorkspace().resolve(NormalizationManifestWriter.FILE_NAME)),
                StandardCharsets.UTF_8
        );
        assertTrue(manifestJson.contains("\"normalizedAlleleCount\" : 1"));
        assertTrue(repository.findEvents(job.getId()).stream()
                .anyMatch(event -> event.getMessage().contains("Reference normalization PASSED")
                        && event.getMessage().contains("normalizedAlleles=1")));
    }

    @Test
    void canSkipObservationDetailsWhileKeepingInterpretationResults() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "skip-observation-job",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr4\t400\t.\tG\tA\t.\tPASS\t.\tGT\t0/1\n"
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPersistence().setPersistObservations(false);
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                noOpBuildConverter(),
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                new VariantInterpretationServiceImpl(
                        new VcfInfoBasicVariantAnnotator(),
                        Collections.emptyList(),
                        new TransparentVariantRankingService(properties)
                ),
                properties
        );

        processor.process(job.getId());

        VariantAnalysisJob completed = repository.findById(job.getId()).orElse(null);
        assertNotNull(completed);
        assertEquals(VariantAnalysisStatus.READY, completed.getStatus());
        assertEquals(1, completed.getQcSummary().getVariantCount());
        assertEquals(0, repository.countObservations(job.getId()));
        assertEquals(1, repository.countInterpretations(job.getId()));
        assertEquals("UNRESOLVED", repository.findInterpretations(job.getId(), 0, 1).get(0).getPriorityTier());
    }

    @Test
    void liftsGrch37InputBeforeGrch38Analysis() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "grch37-job",
                GenomeAssembly.GRCh37,
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n"
        );

        Path lifted = job.getWorkspace().resolve("lifted.vcf");
        Files.write(
                lifted,
                ("##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t200\t.\tC\tT\t.\tPASS\t.\tGT\t0/1\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        VcfBuildConverter converter = (inputFile, workspace, inputAssembly, analysisAssembly) -> {
            assertEquals(GenomeAssembly.GRCh37, inputAssembly);
            assertEquals(GenomeAssembly.GRCh38, analysisAssembly);
            return new BuildConversionResult(
                    lifted,
                    true,
                    "test-liftover",
                    Collections.singletonList("lifted to GRCh38")
            );
        };

        processor(repository, converter, copyOnlyNormalizer()).process(job.getId());

        VariantAnalysisJob completed = repository.findById(job.getId()).orElse(null);
        assertNotNull(completed);
        assertEquals(VariantAnalysisStatus.READY, completed.getStatus());
        assertEquals(GenomeAssembly.GRCh37, completed.getGenomeAssembly());
        assertEquals("test-liftover + COPY_ONLY", completed.getNormalizationEngine());
        assertEquals("GRCh38", completed.getObservations().get(0).getKey().getGenomeAssembly());
        assertEquals(200, completed.getObservations().get(0).getKey().getPosition());
        assertTrue(completed.getWarnings().contains("lifted to GRCh38"));
    }

    @Test
    void preservesGrch37InputWhenAnalysisAssemblyPolicyIsPreserveInput() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "native-grch37-job",
                GenomeAssembly.GRCh37,
                "##fileformat=VCFv4.2\n"
                        + "##reference=GRCh37\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr7\t140453136\t.\tA\tT\t.\tPASS\t.\tGT\t0/1\n"
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        VcfBuildConverter converter = (inputFile, workspace, inputAssembly, analysisAssembly) -> {
            assertEquals(GenomeAssembly.GRCh37, inputAssembly);
            assertEquals(GenomeAssembly.GRCh37, analysisAssembly);
            return new BuildConversionResult(
                    inputFile,
                    false,
                    "NONE",
                    Collections.emptyList()
            );
        };
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                converter,
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                new VariantInterpretationServiceImpl(
                        new VcfInfoBasicVariantAnnotator(),
                        Collections.emptyList(),
                        new TransparentVariantRankingService(properties)
                ),
                properties
        );

        processor.process(job.getId());

        VariantAnalysisJob completed = repository.findById(job.getId()).orElse(null);
        assertNotNull(completed);
        assertEquals(VariantAnalysisStatus.READY, completed.getStatus());
        assertEquals(GenomeAssembly.GRCh37, completed.getGenomeAssembly());
        assertEquals("GRCh37", completed.getObservations().get(0).getKey().getGenomeAssembly());
        assertEquals(140453136, completed.getObservations().get(0).getKey().getPosition());
        assertEquals("COPY_ONLY", completed.getNormalizationEngine());
    }

    @Test
    void failsMultiSamplePipelineWithoutExplicitSelection() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "failed-job",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\tsibling\n"
                        + "chr5\t500\t.\tT\tC\t.\tPASS\t.\tGT\t0/1\t0/0\n"
        );

        processor(repository, copyOnlyNormalizer()).process(job.getId());

        VariantAnalysisJob failed = repository.findById(job.getId()).orElse(null);
        assertNotNull(failed);
        assertEquals(VariantAnalysisStatus.FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("sampleId must explicitly select one sample"));
        assertEquals(0, repository.countObservations(job.getId()));
    }

    @Test
    void appliesPhenotypeRerankingWhenJobHasAvailableGenePrior() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "phenotype-job",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n"
        );
        job.applyPhenotypeSummary(summaryWithPrior("MECP2"));
        repository.save(job);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypeRanking().setEnabled(true);
        VariantInterpretationService interpretationService = (normalizedVcf, observations, context) -> {
            VariantInterpretation variant = baseReviewVariant(observations.get(0), "MECP2");
            return Collections.singletonList(variant);
        };
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                noOpBuildConverter(),
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                interpretationService,
                properties
        );

        processor.process(job.getId());

        VariantAnalysisJob completed = repository.findById(job.getId()).orElse(null);
        assertNotNull(completed);
        assertEquals(VariantAnalysisStatus.READY, completed.getStatus());
        assertTrue(completed.getPhenotypeSummary().isRankingApplied());
        assertEquals(1, completed.getPhenotypeSummary().getCandidatesWithPhenotypeSupport());
        VariantInterpretation persisted = repository.findInterpretations(job.getId(), 0, 1).get(0);
        assertEquals(17.2d, persisted.getRankingScore(), 0.0001d);
        assertTrue(persisted.getScoreComponents().containsKey("phenotype_support"));
        assertEquals("PHENOTYPE_GENE_PRIOR", evidence(persisted, "LOCAL_GENE_PRIOR").getEvidenceType());
    }

    @Test
    void appliesSuppliedPhenotypeSummaryWhenReloadedJobHasNoPersistedSummary() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "supplied-phenotype-job",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n"
        );
        assertFalse(job.getPhenotypeSummary().isEnabled());
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypeRanking().setEnabled(true);
        VariantInterpretationService interpretationService = (normalizedVcf, observations, context) -> {
            VariantInterpretation variant = baseReviewVariant(observations.get(0), "FGFR3");
            return Collections.singletonList(variant);
        };
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                noOpBuildConverter(),
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                interpretationService,
                properties
        );

        processor.process(job.getId(), summaryWithPrior("FGFR3"));

        VariantAnalysisJob completed = repository.findById(job.getId()).orElse(null);
        assertNotNull(completed);
        assertTrue(completed.getPhenotypeSummary().isRankingApplied());
        assertEquals(1, completed.getPhenotypeSummary().getCandidatesWithPhenotypeSupport());
        VariantInterpretation persisted = repository.findInterpretations(job.getId(), 0, 1).get(0);
        assertEquals(17.2d, persisted.getRankingScore(), 0.0001d);
        assertTrue(persisted.getScoreComponents().containsKey("phenotype_support"));
    }

    @Test
    void preRankingTranscriptAnnotationRunsBeforePhenotypeReranking() throws IOException {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = saveJob(
                repository,
                "pre-ranking-phenotype-job",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n"
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypeRanking().setEnabled(true);
        VariantInterpretationService interpretationService = (normalizedVcf, observations, context) ->
                Collections.singletonList(nakedVariant(observations.get(0)));
        PreRankingTranscriptAnnotationService preRankingService =
                new PreRankingTranscriptAnnotationService(properties, null, null) {
                    @Override
                    public PreRankingAnnotationSummary annotateCandidates(List<VariantInterpretation> variants,
                                                                          org.nbscn.varvis.variantanalysis.domain.AnalysisContext context) {
                        variants.get(0).applyTranscriptAnnotations(Collections.singletonList(new TranscriptConsequence(
                                "MECP2",
                                "ENSG00000169057",
                                "ENST00000303391",
                                "c.1A>T",
                                "p.Lys1Ter",
                                "stop_gained"
                        )));
                        return new PreRankingAnnotationSummary(
                                "AVAILABLE",
                                1,
                                1,
                                1,
                                0,
                                0,
                                0,
                                1,
                                0,
                                Collections.emptyList()
                        );
                    }
                };
        TransparentVariantRankingService rankingService = new TransparentVariantRankingService(properties);
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                noOpBuildConverter(),
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                interpretationService,
                properties,
                null,
                new PhenotypeAwareRerankingService(properties),
                preRankingService,
                rankingService
        );

        processor.process(job.getId(), summaryWithPrior("MECP2"));

        VariantInterpretation persisted = repository.findInterpretations(job.getId(), 0, 1).get(0);
        assertEquals("HIGH_PRIORITY", persisted.getPriorityTier());
        assertEquals("MECP2", persisted.getTranscriptAnnotations().get(0).getGeneSymbol());
        assertTrue(persisted.getScoreComponents().containsKey("phenotype_support"));
        assertTrue(repository.findById(job.getId()).get().getPhenotypeSummary().isRankingApplied());
    }

    private VariantAnalysisJobProcessor processor(InMemoryVariantAnalysisJobRepository repository,
                                                  VcfNormalizer normalizer) {
        return processor(repository, noOpBuildConverter(), normalizer);
    }

    private VariantAnalysisJobProcessor processor(InMemoryVariantAnalysisJobRepository repository,
                                                  VcfBuildConverter buildConverter,
                                                  VcfNormalizer normalizer) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        return new VariantAnalysisJobProcessor(
                repository,
                buildConverter,
                normalizer,
                new VcfQcServiceImpl(),
                new VariantInterpretationServiceImpl(
                        new VcfInfoBasicVariantAnnotator(),
                        Collections.emptyList(),
                        new TransparentVariantRankingService(properties)
                ),
                properties
        );
    }

    private VariantInterpretation baseReviewVariant(VariantQcObservation observation, String geneSymbol) {
        VariantInterpretation variant = new VariantInterpretation(
                observation.getKey().toString() + ":" + geneSymbol,
                observation,
                Collections.singletonList(new TranscriptConsequence(
                        geneSymbol,
                        null,
                        null,
                        null,
                        null,
                        "missense_variant"
                )),
                Collections.emptyList()
        );
        variant.applyRanking(
                "REVIEW",
                10.0d,
                Collections.singletonMap("base", 10.0d),
                Collections.emptyMap(),
                "Base review tier",
                Collections.singletonList("Base positive reason"),
                Collections.emptyList(),
                Collections.singletonList("Base rationale"),
                Collections.emptyList(),
                Collections.emptyList()
        );
        return variant;
    }

    private VariantInterpretation nakedVariant(VariantQcObservation observation) {
        return new VariantInterpretation(
                observation.getKey().toString() + ":naked",
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private PhenotypeAwareSummary summaryWithPrior(String geneSymbol) {
        GenePhenotypePrior prior = new GenePhenotypePrior(
                geneSymbol,
                "ENSG00000169057",
                0.95d,
                1,
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                Collections.singletonList("HP:0001250"),
                Collections.singletonList("HP:0001250: Mock phenotype-gene association"),
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Collections.emptyList()
        );
        GenePhenotypePriorSummary priorSummary = new GenePhenotypePriorSummary(
                true,
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                GenePhenotypePriorStatus.AVAILABLE,
                1,
                1,
                1,
                Arrays.asList(prior),
                Collections.emptyList(),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        return PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
                .withGenePriorSummary(priorSummary);
    }

    private EvidenceItem evidence(VariantInterpretation variant, String source) {
        for (EvidenceItem item : variant.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
    }

    private VcfBuildConverter noOpBuildConverter() {
        return (inputFile, workspace, inputAssembly, analysisAssembly) -> new BuildConversionResult(
                inputFile,
                false,
                "NONE",
                Collections.emptyList()
        );
    }

    private VcfNormalizer copyOnlyNormalizer() {
        return (inputFile, workspace, genomeAssembly) -> {
            Path output = workspace.resolve("qc-input.vcf");
            Files.copy(inputFile, output);
            return new NormalizationResult(
                    output,
                    false,
                    "COPY_ONLY",
                    Collections.singletonList("Reference-based normalization was not applied.")
            );
        };
    }

    private VariantAnalysisJob saveJob(InMemoryVariantAnalysisJobRepository repository,
                                       String id,
                                       String content) throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve(id));
        Path uploadedFile = workspace.resolve("input.vcf");
        Files.write(uploadedFile, content.getBytes(StandardCharsets.UTF_8));
        VariantAnalysisJob job = new VariantAnalysisJob(
                id,
                "input.vcf",
                "0",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                workspace,
                uploadedFile
        );
        repository.save(job);
        return job;
    }

    private VariantAnalysisJob saveJob(InMemoryVariantAnalysisJobRepository repository,
                                       String id,
                                       GenomeAssembly genomeAssembly,
                                       String content) throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve(id));
        Path uploadedFile = workspace.resolve("input.vcf");
        Files.write(uploadedFile, content.getBytes(StandardCharsets.UTF_8));
        VariantAnalysisJob job = new VariantAnalysisJob(
                id,
                "input.vcf",
                "0",
                genomeAssembly,
                AnalysisMode.WES,
                null,
                workspace,
                uploadedFile
        );
        repository.save(job);
        return job;
    }
}
