package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.casevalidation.CaseValidationCase;
import org.nbscn.varvis.variantanalysis.casevalidation.CaseValidationReportWriter;
import org.nbscn.varvis.variantanalysis.casevalidation.CaseValidationResult;
import org.nbscn.varvis.variantanalysis.casevalidation.CaseValidationRun;
import org.nbscn.varvis.variantanalysis.casevalidation.CaseValidationService;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.provider.AnnTranscriptAnnotationProvider;
import org.nbscn.varvis.variantanalysis.provider.ExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.ProcessResult;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.VepCommandBuilder;
import org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.nbscn.varvis.variantanalysis.service.impl.VariantAnalysisServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.VcfInfoBasicVariantAnnotator;
import org.nbscn.varvis.variantanalysis.service.impl.VcfQcServiceImpl;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Grch37EndToEndSyntheticSmokeTest {

    private static final String RUN_ENV = "VARVIS_RUN_GRCH37_E2E_SMOKE";
    private static final String SAMPLE_ID = "SYNTHETIC_SAMPLE";
    private static final String TRUTH_VARIANT_ID = "GRCh37:7:140453136:A:T";
    private static final String TRUTH_GENE = "BRAF";
    private static final String TRUTH_HPO = "HP:0001250";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void syntheticGrch37E2eSmokeIsDisabledByDefaultAndLocalFolderIsIgnored() throws Exception {
        assertTrue(isLocalFolderIgnored(), "varvis-server/local/ must remain git-ignored for E2E artifacts");
        if (!enabled(RUN_ENV)) {
            assertFalse(enabled(RUN_ENV));
        }
    }

    @Test
    void syntheticGrch37VcfNoHpoWithHpoEndToEndSmoke() throws Exception {
        Assumptions.assumeTrue(enabled(RUN_ENV),
                "Set " + RUN_ENV + "=true to run the synthetic GRCh37 E2E smoke.");

        Path root = localRoot();
        SyntheticFixture fixture = writeSyntheticFixture(root);
        assertReferenceAlleles(fixture);

        VariantAnalysisProperties properties = properties(root, fixture.phen2GeneImport);
        VepResourceValidator validator = new VepResourceValidator(properties);
        VepReadinessReport readiness = validator.validate(GenomeAssembly.GRCh37);
        Assumptions.assumeTrue("READY".equals(readiness.getStatus()) && readiness.isExecutionEnabled(),
                readiness.getExplanation());

        Files.deleteIfExists(fixture.cacheFile);
        CountingExternalProcessRunner runner = new CountingExternalProcessRunner();
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisServiceImpl service = analysisService(repository, properties, validator, runner,
                fixture.cacheFile);

        VariantAnalysisJob noHpoJob = service.submit(
                multipart(fixture.vcf),
                "GRCh37",
                "WES",
                SAMPLE_ID,
                Collections.<String>emptyList()
        );
        assertEquals(VariantAnalysisStatus.READY, noHpoJob.getStatus());
        List<VariantInterpretation> noHpoVariants = variants(repository, noHpoJob.getId());
        PreRankingMetrics noHpoMetrics = preRankingMetrics(repository, noHpoJob.getId());
        int processInvocationsAfterNoHpo = runner.invocationCount();

        VariantAnalysisJob withHpoJob = service.submit(
                multipart(fixture.vcf),
                "GRCh37",
                "WES",
                SAMPLE_ID,
                Collections.singletonList(TRUTH_HPO)
        );
        assertEquals(VariantAnalysisStatus.READY, withHpoJob.getStatus());
        List<VariantInterpretation> withHpoVariants = variants(repository, withHpoJob.getId());
        PreRankingMetrics withHpoMetrics = preRankingMetrics(repository, withHpoJob.getId());

        assertEquals(GenomeAssembly.GRCh37, noHpoJob.getGenomeAssembly());
        assertEquals(GenomeAssembly.GRCh37, withHpoJob.getGenomeAssembly());
        assertEquals("GRCh37", noHpoVariants.get(0).getObservation().getKey().getGenomeAssembly());
        assertEquals("GRCh37", withHpoVariants.get(0).getObservation().getKey().getGenomeAssembly());
        assertTrue(noHpoMetrics.cacheMissCount >= 7);
        assertEquals(0, noHpoMetrics.cacheHitCount);
        assertTrue(noHpoMetrics.providerBatchCount >= 1);
        assertTrue(noHpoMetrics.annotatedAvailableCount >= 7);
        assertTrue(processInvocationsAfterNoHpo >= 1);
        assertTrue(withHpoMetrics.cacheHitCount >= 7);
        assertEquals(0, withHpoMetrics.cacheMissCount);
        assertEquals(0, withHpoMetrics.providerBatchCount);
        assertEquals(processInvocationsAfterNoHpo, runner.invocationCount());
        assertTrue(runner.invokedWith("--assembly", "GRCh37"));
        assertTrue(runner.invokedPathContaining("GRCh37"));
        assertFalse(runner.invokedPathContaining("GRCh38"));

        VariantInterpretation noHpoTruth = variantById(noHpoVariants, TRUTH_VARIANT_ID);
        VariantInterpretation withHpoTruth = variantById(withHpoVariants, TRUTH_VARIANT_ID);
        assertHasBrafAnnotation(noHpoTruth);
        assertHasBrafAnnotation(withHpoTruth);
        PhenotypeAwareSummary phenotypeSummary = withHpoJob.getPhenotypeSummary();
        assertTrue(phenotypeSummary.isRankingApplied());
        assertTrue(phenotypeSummary.getCandidatesWithPhenotypeSupport() >= 1);
        assertTrue(phenotypeSupportPresent(withHpoTruth));

        CaseValidationCase validationCase = writeAndLoadCase(fixture);
        CaseValidationRun noHpoRun = caseRun("synthetic-grch37-no-hpo", noHpoVariants,
                noHpoJob.getPhenotypeSummary(), noHpoMetrics);
        CaseValidationRun withHpoRun = caseRun("synthetic-grch37-with-hpo", withHpoVariants,
                withHpoJob.getPhenotypeSummary(), withHpoMetrics);
        CaseValidationService validationService = new CaseValidationService(objectMapper);
        CaseValidationResult result = validationService.validate(validationCase, noHpoRun, withHpoRun);
        CaseValidationReportWriter.ReportPaths reportPaths =
                new CaseValidationReportWriter(objectMapper).write(fixture.reportDirectory, result);

        CaseValidationResult.TruthVariantResult truthResult = result.getTruthVariantResults().get(0);
        assertTrue(truthResult.isExactMatch());
        assertEquals("AVAILABLE", truthResult.getGeneAnnotationStatus());
        assertTrue(truthResult.isPhenotypeSupportPresent());
        assertNotNull(truthResult.getRankNoHpo());
        assertNotNull(truthResult.getRankWithHpo());
        assertTrue(truthResult.getRankWithHpo() < truthResult.getRankNoHpo(),
                "with-HPO ranking should improve the synthetic truth variant rank");
        assertEquals(Boolean.TRUE, truthResult.getHitWithHpo().get(1));
        assertEquals("GRCh37", result.getInputAssembly());
        assertEquals("GRCh37", result.getAnnotationAssembly());
        assertEquals("GRCh37", result.getResourceAssembly());
        assertEquals("GRCh38", result.getKnowledgeAssembly());
        assertFalse(result.isLiftoverApplied());
        assertEquals("NOT_APPLIED", result.getLiftoverStatus());
        assertTrue(Files.size(reportPaths.getJson()) > 0);
        assertTrue(Files.size(reportPaths.getCsv()) > 0);
        assertTrue(Files.size(reportPaths.getMarkdown()) > 0);
        String markdown = new String(Files.readAllBytes(reportPaths.getMarkdown()), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("Input assembly: GRCh37"));
        assertTrue(markdown.contains("Resource assembly: GRCh37"));
        assertTrue(markdown.contains("LiftOver applied: false"));
        assertFalse(markdown.contains("D:\\"));

        System.out.println("P4.3_NO_HPO candidates=" + noHpoVariants.size()
                + " cacheMiss=" + noHpoMetrics.cacheMissCount
                + " cacheHit=" + noHpoMetrics.cacheHitCount
                + " providerBatchCount=" + noHpoMetrics.providerBatchCount
                + " annotatedAvailable=" + noHpoMetrics.annotatedAvailableCount
                + " truthRank=" + truthResult.getRankNoHpo());
        System.out.println("P4.3_WITH_HPO candidates=" + withHpoVariants.size()
                + " cacheMiss=" + withHpoMetrics.cacheMissCount
                + " cacheHit=" + withHpoMetrics.cacheHitCount
                + " providerBatchCount=" + withHpoMetrics.providerBatchCount
                + " annotatedAvailable=" + withHpoMetrics.annotatedAvailableCount
                + " truthRank=" + truthResult.getRankWithHpo()
                + " phenotypeSupport=" + truthResult.isPhenotypeSupportPresent());
        System.out.println("P4.3_RANK_DELTA truthGene=" + TRUTH_GENE
                + " rankNoHpo=" + truthResult.getRankNoHpo()
                + " rankWithHpo=" + truthResult.getRankWithHpo()
                + " delta=" + truthResult.getRankDelta()
                + " hitAt1=" + truthResult.getHitWithHpo().get(1));
        System.out.println("P4.3_REPORT markdown=" + root.relativize(reportPaths.getMarkdown())
                + " json=" + root.relativize(reportPaths.getJson())
                + " csv=" + root.relativize(reportPaths.getCsv()));
    }

    private VariantAnalysisServiceImpl analysisService(InMemoryVariantAnalysisJobRepository repository,
                                                       VariantAnalysisProperties properties,
                                                       VepResourceValidator validator,
                                                       CountingExternalProcessRunner runner,
                                                       Path cacheFile) {
        IsolatedTranscriptAnnotationCache cache = new IsolatedTranscriptAnnotationCache(cacheFile);
        TranscriptAnnotationService transcriptAnnotationService = new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                cache,
                validator
        );
        VepTranscriptAnnotationEnrichmentService vepService = new VepTranscriptAnnotationEnrichmentService(
                repository,
                properties,
                validator,
                new VepCommandBuilder(properties),
                new VepJsonOutputParser(objectMapper),
                runner,
                transcriptAnnotationService
        );
        PreRankingTranscriptAnnotationService preRankingService =
                new PreRankingTranscriptAnnotationService(properties, validator, vepService);
        TransparentVariantRankingService rankingService = new TransparentVariantRankingService(properties);
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                preserveInputBuildConverter(),
                copyOnlyNormalizer(),
                new VcfQcServiceImpl(),
                new VariantInterpretationServiceImpl(
                        new VcfInfoBasicVariantAnnotator(properties),
                        Collections.emptyList(),
                        rankingService,
                        transcriptAnnotationService,
                        null,
                        properties
                ),
                properties,
                null,
                new PhenotypeAwareRerankingService(properties),
                preRankingService,
                rankingService
        );
        return new VariantAnalysisServiceImpl(
                repository,
                new NoOpVariantEnhancementRepository(),
                processor,
                null,
                properties
        );
    }

    private VcfBuildConverter preserveInputBuildConverter() {
        return (inputFile, workspace, inputAssembly, analysisAssembly) -> {
            assertEquals(inputAssembly, analysisAssembly);
            return new BuildConversionResult(inputFile, false, "NONE", Collections.<String>emptyList());
        };
    }

    private VcfNormalizer copyOnlyNormalizer() {
        return (inputFile, workspace, genomeAssembly) -> {
            Path output = workspace.resolve("normalized.vcf");
            Files.copy(inputFile, output, StandardCopyOption.REPLACE_EXISTING);
            return new NormalizationResult(output, false, "COPY_ONLY", Collections.<String>emptyList());
        };
    }

    private VariantAnalysisProperties properties(Path root, Path phen2GeneImport) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(root.resolve("workspaces"));
        properties.setMaxUploadSizeBytes(10L * 1024L * 1024L);
        properties.setMaxExpandedSizeBytes(20L * 1024L * 1024L);
        properties.setAnalysisAssemblyPolicy("PRESERVE_INPUT");
        properties.getPhenotypeRanking().setEnabled(true);
        properties.getPhenotypeRanking().setMaxScoreBoost(8.0d);
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setSource("PHEN2GENE_IMPORT");
        properties.getPhenotypePrior().setVersion("p4.3-synthetic-phen2gene");
        properties.getPhenotypePrior().setMaxGenes(50);
        properties.getPhenotypePrior().setLocalFile(phen2GeneImport.toString());
        VariantAnalysisProperties.Vep vep = properties.getVep();
        vep.setEnabled(true);
        vep.setExecutionEnabled(true);
        vep.setExecutable(requiredEnv("VARVIS_VEP_GRCH37_EXECUTABLE", "VARVIS_VEP_EXECUTABLE"));
        vep.setCacheDir(requiredPath("VARVIS_VEP_GRCH37_CACHE", "VARVIS_VEP_CACHE_DIR").toString());
        vep.setFasta(requiredPath("VARVIS_VEP_GRCH37_FASTA", "VARVIS_VEP_FASTA").toString());
        vep.setVersion(valueOrDefault(env("VARVIS_VEP_GRCH37_VERSION", "VARVIS_VEP_VERSION"), "116.0"));
        vep.setEnsemblCacheVersion(valueOrDefault(
                env("VARVIS_VEP_GRCH37_CACHE_VERSION", "VARVIS_ENSEMBL_CACHE_VERSION"),
                "116_GRCh37"
        ));
        vep.setManeVersion(valueOrDefault(env("VARVIS_MANE_VERSION"), "not-configured"));
        vep.setLofteeVersion(valueOrDefault(env("VARVIS_LOFTEE_VERSION"), "not-configured"));
        vep.setPreRankingEnabled(true);
        vep.setPreRankingMaxCandidates(20);
        vep.setPreRankingBatchSize(20);
        vep.setPreRankingCacheEnabled(true);
        vep.setPreRankingFailOpen(false);
        vep.setPreRankingTimeoutSeconds(intValue(env("VARVIS_VEP_PRE_RANKING_TIMEOUT_SECONDS"), 180));
        vep.setPreRankingTempDir(root.resolve("vep-temp").toString());
        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(true);
        profile.setAssembly("GRCh37");
        profile.setExecutable(vep.getExecutable());
        profile.setCacheDir(vep.getCacheDir() == null ? null : vep.getCacheDir().toString());
        profile.setFasta(vep.getFasta() == null ? null : vep.getFasta().toString());
        profile.setVersion(vep.getVersion());
        profile.setEnsemblCacheVersion(vep.getEnsemblCacheVersion());
        profile.setManeVersion(vep.getManeVersion());
        profile.setLofteeVersion(vep.getLofteeVersion());
        vep.getResources().put("GRCh37", profile);
        return properties;
    }

    private SyntheticFixture writeSyntheticFixture(Path root) throws Exception {
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("reports"));
        Path vcf = root.resolve("synthetic-grch37-e2e.vcf");
        Path phen2Gene = root.resolve("synthetic-grch37-e2e-phen2gene.tsv");
        Path caseJson = root.resolve("synthetic-grch37-e2e-case.json");
        Path cache = root.resolve("synthetic-grch37-e2e-cache.json");
        List<SyntheticVariant> variants = syntheticVariants();
        List<String> lines = new ArrayList<String>();
        lines.add("##fileformat=VCFv4.2");
        lines.add("##reference=GRCh37");
        lines.add("##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">");
        lines.add("##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Read depth\">");
        lines.add("##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">");
        lines.add("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t" + SAMPLE_ID);
        for (SyntheticVariant variant : variants) {
            lines.add("chr" + variant.chrom + "\t" + variant.pos + "\t" + variant.id + "\t"
                    + variant.ref + "\t" + variant.alt + "\t100\tPASS\t.\tGT:DP:GQ\t0/1:40:99");
        }
        Files.write(vcf, lines, StandardCharsets.UTF_8);
        Files.write(phen2Gene, phen2GeneRows(), StandardCharsets.UTF_8);
        writeCaseJson(caseJson);
        return new SyntheticFixture(vcf, phen2Gene, caseJson, cache, root.resolve("reports"), variants);
    }

    private List<String> phen2GeneRows() {
        List<String> rows = new ArrayList<String>();
        rows.add("hpo_terms\tgene_symbol\tgene_id\tphenotype_score\tphenotype_rank\t"
                + "source\tsource_version\tmatched_hpo_terms\tevidence\tstatus");
        rows.add(String.join("\t", Arrays.asList(
                TRUTH_HPO,
                TRUTH_GENE,
                "ENSG00000157764",
                "0.95",
                "1",
                "PHEN2GENE",
                "p4.3-synthetic-phen2gene",
                TRUTH_HPO,
                "Synthetic BRAF prior for technical GRCh37 E2E smoke",
                "SeedGene"
        )));
        return rows;
    }

    private void writeCaseJson(Path caseJson) throws IOException {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("caseId", "synthetic-grch37-e2e-braf");
        root.put("description", "Synthetic non-patient GRCh37 E2E smoke for native VEP routing and HPO reranking.");
        root.put("assembly", "GRCh37");
        root.put("vcfPath", "local/grch37-e2e-smoke/synthetic-grch37-e2e.vcf");
        root.put("probandSampleId", SAMPLE_ID);
        root.put("hpoTerms", Collections.singletonList(TRUTH_HPO));
        Map<String, Object> truthVariant = new LinkedHashMap<String, Object>();
        truthVariant.put("chrom", "7");
        truthVariant.put("pos", 140453136);
        truthVariant.put("ref", "A");
        truthVariant.put("alt", "T");
        truthVariant.put("geneSymbol", TRUTH_GENE);
        truthVariant.put("transcript", "ENST00000288602");
        truthVariant.put("zygosity", "het");
        truthVariant.put("label", "synthetic-truth");
        truthVariant.put("note", "Synthetic BRAF V600E GRCh37 smoke truth variant; not a patient record.");
        root.put("truthVariants", Collections.singletonList(truthVariant));
        Map<String, Object> truthGene = new LinkedHashMap<String, Object>();
        truthGene.put("geneSymbol", TRUTH_GENE);
        truthGene.put("label", "synthetic-truth");
        root.put("truthGenes", Collections.singletonList(truthGene));
        Map<String, Object> expected = new LinkedHashMap<String, Object>();
        expected.put("requireGeneAnnotation", true);
        expected.put("requirePhenotypeSupport", true);
        expected.put("compareNoHpoWithHpo", true);
        expected.put("hitK", Arrays.asList(1, 5, 10, 50));
        root.put("expected", expected);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "synthetic-p4.3");
        metadata.put("deidentified", true);
        metadata.put("patientData", false);
        root.put("metadata", metadata);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(caseJson.toFile(), root);
    }

    private CaseValidationCase writeAndLoadCase(SyntheticFixture fixture) throws IOException {
        CaseValidationService service = new CaseValidationService(objectMapper);
        CaseValidationCase validationCase = service.load(fixture.caseJson);
        service.validateCase(validationCase);
        return validationCase;
    }

    private List<SyntheticVariant> syntheticVariants() {
        return Arrays.asList(
                new SyntheticVariant("truth_braf", "7", 140453136, "A", "T"),
                new SyntheticVariant("decoy_kras", "12", 25398284, "C", "A"),
                new SyntheticVariant("decoy_egfr", "7", 55259515, "T", "G"),
                new SyntheticVariant("decoy_pik3ca", "3", 178936091, "G", "A"),
                new SyntheticVariant("decoy_tp53", "17", 7579472, "G", "A"),
                new SyntheticVariant("decoy_fgfr3", "4", 1806119, "G", "A"),
                new SyntheticVariant("decoy_hbb", "11", 5248232, "T", "A")
        );
    }

    private void assertReferenceAlleles(SyntheticFixture fixture) throws IOException {
        Path fai = requiredPath("VARVIS_VEP_GRCH37_FASTA", "VARVIS_VEP_FASTA").resolveSibling(
                requiredPath("VARVIS_VEP_GRCH37_FASTA", "VARVIS_VEP_FASTA").getFileName().toString() + ".fai"
        );
        Assumptions.assumeTrue(Files.isRegularFile(fai), "FASTA .fai is required for REF sanity checks.");
        FastaIndex index = FastaIndex.load(fai);
        Path fasta = requiredPath("VARVIS_VEP_GRCH37_FASTA", "VARVIS_VEP_FASTA");
        for (SyntheticVariant variant : fixture.variants) {
            String actual = index.baseAt(fasta, variant.chrom, variant.pos);
            assertEquals(variant.ref, actual, "Synthetic GRCh37 REF must match FASTA for " + variant.id);
        }
    }

    private CaseValidationRun caseRun(String mode,
                                      List<VariantInterpretation> variants,
                                      PhenotypeAwareSummary phenotypeSummary,
                                      PreRankingMetrics metrics) {
        return new CaseValidationRun(
                mode,
                variants,
                phenotypeSummary,
                metrics.cacheHitCount,
                metrics.cacheMissCount,
                metrics.providerBatchCount,
                metrics.providerName,
                metrics.providerVersion,
                metrics.cacheVersion,
                metrics.optionsHash,
                "GRCh37",
                "GRCh37",
                "GRCh37",
                "GRCh38",
                false,
                "NOT_APPLIED",
                Collections.singletonList("Synthetic non-patient P4.3 smoke; not a benchmark.")
        );
    }

    private MockMultipartFile multipart(Path vcf) throws IOException {
        return new MockMultipartFile(
                "file",
                "synthetic-grch37-e2e.vcf",
                "text/plain",
                Files.readAllBytes(vcf)
        );
    }

    private List<VariantInterpretation> variants(InMemoryVariantAnalysisJobRepository repository, String id) {
        return repository.findInterpretations(id, 0, repository.countInterpretations(id));
    }

    private VariantInterpretation variantById(List<VariantInterpretation> variants, String id) {
        for (VariantInterpretation variant : variants) {
            if (id.equals(variant.getVariantId())) {
                return variant;
            }
        }
        throw new AssertionError("Missing variant " + id);
    }

    private void assertHasBrafAnnotation(VariantInterpretation variant) {
        assertNotNull(variant);
        for (TranscriptConsequence annotation : variant.getTranscriptAnnotations()) {
            if (TRUTH_GENE.equals(annotation.getGeneSymbol())) {
                assertEquals("COMPLETED", annotation.getStatus());
                assertTrue(StringUtils.hasText(annotation.getConsequence()));
                assertTrue(StringUtils.hasText(annotation.getTranscriptId())
                        || StringUtils.hasText(annotation.getTranscriptAccession()));
                return;
            }
        }
        throw new AssertionError("Expected BRAF transcript annotation.");
    }

    private boolean phenotypeSupportPresent(VariantInterpretation variant) {
        return variant != null
                && variant.getScoreComponents() != null
                && variant.getScoreComponents().containsKey("phenotype_support");
    }

    private PreRankingMetrics preRankingMetrics(InMemoryVariantAnalysisJobRepository repository, String id) {
        PreRankingMetrics result = null;
        for (VariantAnalysisEvent event : repository.findEvents(id)) {
            if (event.getMessage() != null
                    && event.getMessage().startsWith("Pre-ranking transcript annotation ")) {
                result = PreRankingMetrics.parse(event.getMessage());
            }
        }
        if (result == null) {
            throw new AssertionError("Missing pre-ranking transcript annotation event for " + id);
        }
        return result;
    }

    private Path localRoot() {
        String configured = env("VARVIS_GRCH37_E2E_ROOT");
        Path root = StringUtils.hasText(configured)
                ? Paths.get(configured)
                : Paths.get("local", "grch37-e2e-smoke");
        return root.toAbsolutePath().normalize();
    }

    private boolean isLocalFolderIgnored() throws Exception {
        Process process = new ProcessBuilder("git", "check-ignore", "-q", "varvis-server/local/probe.tmp")
                .directory(Paths.get("..").toFile())
                .start();
        return process.waitFor() == 0;
    }

    private String requiredEnv(String... names) {
        String value = env(names);
        Assumptions.assumeTrue(StringUtils.hasText(value),
                "Missing required environment variable: " + String.join(" or ", names));
        return value.trim();
    }

    private Path requiredPath(String... names) {
        String value = requiredEnv(names);
        Path path = Paths.get(value);
        Assumptions.assumeTrue(Files.exists(path), "Missing path for " + String.join(" or ", names));
        return path;
    }

    private String env(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean enabled(String name) {
        return "true".equalsIgnoreCase(env(name));
    }

    private int intValue(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static class SyntheticFixture {
        private final Path vcf;
        private final Path phen2GeneImport;
        private final Path caseJson;
        private final Path cacheFile;
        private final Path reportDirectory;
        private final List<SyntheticVariant> variants;

        private SyntheticFixture(Path vcf,
                                 Path phen2GeneImport,
                                 Path caseJson,
                                 Path cacheFile,
                                 Path reportDirectory,
                                 List<SyntheticVariant> variants) {
            this.vcf = vcf;
            this.phen2GeneImport = phen2GeneImport;
            this.caseJson = caseJson;
            this.cacheFile = cacheFile;
            this.reportDirectory = reportDirectory;
            this.variants = variants;
        }
    }

    private static class SyntheticVariant {
        private final String id;
        private final String chrom;
        private final int pos;
        private final String ref;
        private final String alt;

        private SyntheticVariant(String id, String chrom, int pos, String ref, String alt) {
            this.id = id;
            this.chrom = chrom;
            this.pos = pos;
            this.ref = ref;
            this.alt = alt;
        }
    }

    private static class PreRankingMetrics {
        private int cacheHitCount;
        private int cacheMissCount;
        private int providerBatchCount;
        private int annotatedAvailableCount;
        private String providerName;
        private String providerVersion;
        private String cacheVersion;
        private String optionsHash;

        private static PreRankingMetrics parse(String message) {
            PreRankingMetrics metrics = new PreRankingMetrics();
            String[] parts = message.split(",");
            for (String part : parts) {
                int index = part.indexOf('=');
                if (index < 0) {
                    continue;
                }
                String key = part.substring(0, index).trim();
                String value = part.substring(index + 1).trim();
                if ("cacheHit".equals(key)) {
                    metrics.cacheHitCount = intValue(value);
                } else if ("cacheMiss".equals(key)) {
                    metrics.cacheMissCount = intValue(value);
                } else if ("batches".equals(key)) {
                    metrics.providerBatchCount = intValue(value);
                } else if ("annotatedAvailable".equals(key)) {
                    metrics.annotatedAvailableCount = intValue(value);
                } else if ("provider".equals(key)) {
                    metrics.providerName = value;
                } else if ("providerVersion".equals(key)) {
                    metrics.providerVersion = value;
                } else if ("cacheVersion".equals(key)) {
                    metrics.cacheVersion = value;
                } else if ("optionsHash".equals(key)) {
                    metrics.optionsHash = value;
                }
            }
            return metrics;
        }

        private static int intValue(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    private static class CountingExternalProcessRunner implements ExternalProcessRunner {
        private final ExternalProcessRunner delegate = new SystemExternalProcessRunner();
        private final List<List<String>> commands = new ArrayList<List<String>>();

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
            commands.add(new ArrayList<String>(command));
            return delegate.run(command, workingDirectory, timeout);
        }

        private int invocationCount() {
            return commands.size();
        }

        private boolean invokedWith(String option, String value) {
            for (List<String> command : commands) {
                for (int index = 0; index < command.size() - 1; index++) {
                    if (option.equals(command.get(index)) && value.equals(command.get(index + 1))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean invokedPathContaining(String value) {
            for (List<String> command : commands) {
                for (String part : command) {
                    if (part != null && part.contains(value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private class IsolatedTranscriptAnnotationCache implements TranscriptAnnotationCache {
        private final Path file;

        private IsolatedTranscriptAnnotationCache(Path file) {
            this.file = file;
        }

        @Override
        public List<TranscriptConsequence> get(TranscriptAnnotationCacheKey key) {
            CacheRecord record = find(key);
            if (record == null || record.annotations == null || record.annotations.isEmpty()) {
                return null;
            }
            List<TranscriptConsequence> result = new ArrayList<TranscriptConsequence>();
            for (AnnotationRecord annotation : record.annotations) {
                result.add(annotation.toTranscriptConsequence());
            }
            return result;
        }

        @Override
        public void put(TranscriptAnnotationCacheKey key, List<TranscriptConsequence> annotations) {
            if (key == null || annotations == null || annotations.isEmpty()) {
                return;
            }
            CacheFile cacheFile = read();
            List<CacheRecord> retained = new ArrayList<CacheRecord>();
            String hash = key.cacheKeyHash();
            for (CacheRecord record : cacheFile.records) {
                if (!hash.equals(record.cacheKeyHash)) {
                    retained.add(record);
                }
            }
            retained.add(CacheRecord.from(key, annotations));
            cacheFile.records = retained;
            write(cacheFile);
        }

        private CacheRecord find(TranscriptAnnotationCacheKey key) {
            if (key == null || !Files.isRegularFile(file)) {
                return null;
            }
            String hash = key.cacheKeyHash();
            for (CacheRecord record : read().records) {
                if (hash.equals(record.cacheKeyHash)) {
                    return record;
                }
            }
            return null;
        }

        private CacheFile read() {
            if (!Files.isRegularFile(file)) {
                return new CacheFile();
            }
            try {
                CacheFile cache = objectMapper.readValue(file.toFile(), CacheFile.class);
                return cache == null ? new CacheFile() : cache;
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read isolated E2E transcript annotation cache.", ex);
            }
        }

        private void write(CacheFile cache) {
            try {
                Files.createDirectories(file.getParent());
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), cache);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to write isolated E2E transcript annotation cache.", ex);
            }
        }
    }

    public static class CacheFile {
        public List<CacheRecord> records = new ArrayList<CacheRecord>();
    }

    public static class CacheRecord {
        public String cacheKeyHash;
        public String genomeAssembly;
        public String chromosome;
        public int position;
        public String reference;
        public String alternate;
        public String providerName;
        public String providerVersion;
        public String cacheResourceVersion;
        public String optionsHash;
        public List<AnnotationRecord> annotations = new ArrayList<AnnotationRecord>();

        public static CacheRecord from(TranscriptAnnotationCacheKey key, List<TranscriptConsequence> annotations) {
            CacheRecord record = new CacheRecord();
            record.cacheKeyHash = key.cacheKeyHash();
            record.genomeAssembly = key.getGenomeAssembly();
            record.chromosome = key.getChromosome();
            record.position = key.getPosition();
            record.reference = key.getReference();
            record.alternate = key.getAlternate();
            record.providerName = key.getProviderName();
            record.providerVersion = key.getProviderVersion();
            record.cacheResourceVersion = key.getCacheResourceVersion();
            record.optionsHash = key.getOptionsHash();
            for (TranscriptConsequence annotation : annotations) {
                record.annotations.add(AnnotationRecord.from(annotation));
            }
            return record;
        }
    }

    public static class AnnotationRecord {
        public String geneSymbol;
        public String entrezGeneId;
        public String transcriptAccession;
        public String hgvsCoding;
        public String hgvsProtein;
        public String consequence;
        public String geneId;
        public String transcriptId;
        public String transcriptSource;
        public String impact;
        public String source;
        public String status;
        public String version;
        public String timestamp;

        public static AnnotationRecord from(TranscriptConsequence annotation) {
            AnnotationRecord record = new AnnotationRecord();
            record.geneSymbol = annotation.getGeneSymbol();
            record.entrezGeneId = annotation.getEntrezGeneId();
            record.transcriptAccession = annotation.getTranscriptAccession();
            record.hgvsCoding = annotation.getHgvsCoding();
            record.hgvsProtein = annotation.getHgvsProtein();
            record.consequence = annotation.getConsequence();
            record.geneId = annotation.getGeneId();
            record.transcriptId = annotation.getTranscriptId();
            record.transcriptSource = annotation.getTranscriptSource();
            record.impact = annotation.getImpact();
            record.source = annotation.getSource();
            record.status = annotation.getStatus();
            record.version = annotation.getVersion();
            record.timestamp = annotation.getTimestamp() == null ? null : annotation.getTimestamp().toString();
            return record;
        }

        public TranscriptConsequence toTranscriptConsequence() {
            return new TranscriptConsequence(
                    geneSymbol,
                    entrezGeneId,
                    transcriptAccession,
                    hgvsCoding,
                    hgvsProtein,
                    consequence,
                    geneId,
                    transcriptId,
                    transcriptSource,
                    impact,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    source,
                    status,
                    version,
                    timestamp == null ? null : Instant.parse(timestamp)
            );
        }
    }

    private static class FastaIndex {
        private final Map<String, FastaRecord> records;

        private FastaIndex(Map<String, FastaRecord> records) {
            this.records = records;
        }

        private static FastaIndex load(Path fai) throws IOException {
            Map<String, FastaRecord> records = new LinkedHashMap<String, FastaRecord>();
            try (BufferedReader reader = Files.newBufferedReader(fai, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\t");
                    if (fields.length < 5) {
                        continue;
                    }
                    records.put(fields[0], new FastaRecord(
                            Long.parseLong(fields[2]),
                            Integer.parseInt(fields[3]),
                            Integer.parseInt(fields[4])
                    ));
                }
            }
            return new FastaIndex(records);
        }

        private String baseAt(Path fasta, String chrom, int oneBasedPosition) throws IOException {
            FastaRecord record = records.get(chrom);
            if (record == null) {
                record = records.get("chr" + chrom);
            }
            assertNotNull(record, "Missing FASTA index record for " + chrom);
            long zeroBased = oneBasedPosition - 1L;
            long offset = record.offset
                    + (zeroBased / record.lineBases) * record.lineWidth
                    + (zeroBased % record.lineBases);
            byte[] buffer = new byte[1];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(fasta.toFile(), "r")) {
                raf.seek(offset);
                raf.readFully(buffer);
            }
            return new String(buffer, StandardCharsets.US_ASCII).toUpperCase();
        }
    }

    private static class FastaRecord {
        private final long offset;
        private final int lineBases;
        private final int lineWidth;

        private FastaRecord(long offset, int lineBases, int lineWidth) {
            this.offset = offset;
            this.lineBases = lineBases;
            this.lineWidth = lineWidth;
        }
    }

    private static class NoOpVariantEnhancementRepository implements VariantEnhancementRepository {
        @Override
        public void save(VariantEnhancementTask task) {
        }

        @Override
        public Optional<VariantEnhancementTask> findById(String id) {
            return Optional.empty();
        }

        @Override
        public int failInterruptedTasks() {
            return 0;
        }
    }
}
