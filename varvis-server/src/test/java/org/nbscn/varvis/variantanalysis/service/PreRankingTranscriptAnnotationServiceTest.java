package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.AnnTranscriptAnnotationProvider;
import org.nbscn.varvis.variantanalysis.provider.ExternalProcessRunner;
import org.nbscn.varvis.variantanalysis.provider.ProcessResult;
import org.nbscn.varvis.variantanalysis.provider.VepCommandBuilder;
import org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreRankingTranscriptAnnotationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void nakedCandidateReceivesGeneBeforePhenotypeReranking() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        properties.getPhenotypeRanking().setEnabled(true);
        VariantInterpretation naked = nakedVariant("candidate", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "stop_gained");
        PreRankingTranscriptAnnotationService service = service(properties, runner);

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service.annotateCandidates(Collections.singletonList(naked), context(GenomeAssembly.GRCh38));
        new TransparentVariantRankingService(properties).rank(Collections.singletonList(naked));
        PhenotypeAwareSummary phenotype = new PhenotypeAwareRerankingService(properties)
                .apply(Collections.singletonList(naked), summaryWithPrior("MECP2"));

        assertEquals("AVAILABLE", summary.getStatus());
        assertEquals(1, summary.getSuccessCount());
        assertEquals("MECP2", naked.getTranscriptAnnotations().get(0).getGeneSymbol());
        assertEquals("PRE_RANKING_VEP", naked.getTranscriptAnnotations().get(0).getSource());
        assertEquals("HIGH_PRIORITY", naked.getPriorityTier());
        assertTrue(phenotype.isRankingApplied());
        assertTrue(naked.getScoreComponents().containsKey("phenotype_support"));
    }

    @Test
    void providerFailureAddsStatusButDoesNotThrow() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VariantInterpretation naked = nakedVariant("candidate", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.FAIL, "MECP2", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Collections.singletonList(naked),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals("PROVIDER_ERROR", summary.getStatus());
        assertEquals(1, summary.getFailedCount());
        assertEquals(1, summary.getProviderErrorCount());
        assertEquals("PROVIDER_ERROR", naked.getTranscriptAnnotations().get(0).getStatus());
    }

    @Test
    void providerTimeoutAddsStatusButDoesNotThrowWhenFailOpen() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VariantInterpretation naked = nakedVariant("candidate", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.TIMEOUT, "MECP2", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Collections.singletonList(naked),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals("TIMEOUT", summary.getStatus());
        assertEquals(1, summary.getFailedCount());
        assertEquals(1, summary.getProviderTimeoutCount());
        assertTrue(summary.isFailOpenUsed());
        assertEquals("TIMEOUT", naked.getTranscriptAnnotations().get(0).getStatus());
    }

    @Test
    void failOpenFalsePropagatesProviderFailure() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        properties.getVep().setPreRankingFailOpen(false);
        VariantInterpretation naked = nakedVariant("candidate", "PASS", "0/1", 0.0001d);

        assertThrows(IllegalStateException.class, () -> service(
                properties,
                new FakeRunner(FakeRunner.Mode.FAIL, "MECP2", "missense_variant")
        ).annotateCandidates(Collections.singletonList(naked), context(GenomeAssembly.GRCh38)));
    }

    @Test
    void cacheHitDoesNotCallProviderAgain() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        InMemoryTranscriptAnnotationCache cache = new InMemoryTranscriptAnnotationCache();
        VariantInterpretation first = nakedVariant("first", "PASS", "0/1", 0.0001d);
        FakeRunner firstRunner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "stop_gained");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary firstSummary =
                service(properties, firstRunner, cache).annotateCandidates(
                        Collections.singletonList(first),
                        context(GenomeAssembly.GRCh38)
                );

        VariantInterpretation second = nakedVariant("second", "PASS", "0/1", 0.0001d);
        FakeRunner secondRunner = new FakeRunner(FakeRunner.Mode.FAIL, "MECP2", "stop_gained");
        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary secondSummary =
                service(properties, secondRunner, cache).annotateCandidates(
                        Collections.singletonList(second),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals("AVAILABLE", firstSummary.getStatus());
        assertEquals(1, firstRunner.calls);
        assertEquals("AVAILABLE", secondSummary.getStatus());
        assertEquals(1, secondSummary.getCacheHitCount());
        assertEquals(0, secondSummary.getCacheMissCount());
        assertEquals(0, secondRunner.calls);
        assertEquals("MECP2", second.getTranscriptAnnotations().get(0).getGeneSymbol());
    }

    @Test
    void duplicateVariantWithinAnalysisCallsProviderOnce() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VariantInterpretation first = nakedVariant("first", "PASS", "0/1", 0.0001d);
        VariantInterpretation duplicate = nakedVariant("duplicate", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "stop_gained");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Arrays.asList(first, duplicate),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals(2, summary.getSelectedCount());
        assertEquals(1, summary.getCacheMissCount());
        assertEquals(1, summary.getProviderBatchCount());
        assertEquals(2, summary.getSuccessCount());
        assertEquals(1, runner.calls);
        assertEquals(1, runner.variantIds.size());
        assertEquals("MECP2", duplicate.getTranscriptAnnotations().get(0).getGeneSymbol());
    }

    @Test
    void chrPrefixNormalizesToSameCacheKey() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        InMemoryTranscriptAnnotationCache cache = new InMemoryTranscriptAnnotationCache();
        VariantInterpretation chr4 = nakedVariant("first", "chr4", 100, "A", "G", "PASS", "0/1", 0.0001d);
        FakeRunner firstRunner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "stop_gained");
        service(properties, firstRunner, cache).annotateCandidates(
                Collections.singletonList(chr4),
                context(GenomeAssembly.GRCh38)
        );

        VariantInterpretation four = nakedVariant("second", "4", 100, "A", "G", "PASS", "0/1", 0.0001d);
        FakeRunner secondRunner = new FakeRunner(FakeRunner.Mode.FAIL, "MECP2", "stop_gained");
        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, secondRunner, cache).annotateCandidates(
                        Collections.singletonList(four),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals(1, summary.getCacheHitCount());
        assertEquals(0, secondRunner.calls);
    }

    @Test
    void alternateAllelesUseSeparateCacheEntries() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VariantInterpretation altG = nakedVariant("altG", "1", 100, "A", "G", "PASS", "0/1", 0.0001d);
        VariantInterpretation altT = nakedVariant("altT", "1", 100, "A", "T", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "stop_gained");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Arrays.asList(altG, altT),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals(2, summary.getCacheMissCount());
        assertEquals(2, runner.variantIds.size());
    }

    @Test
    void cacheFailureDoesNotFailAnnotationWhenProviderWorks() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VariantInterpretation naked = nakedVariant("candidate", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "stop_gained");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner, new ThrowingTranscriptAnnotationCache()).annotateCandidates(
                        Collections.singletonList(naked),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals("AVAILABLE", summary.getStatus());
        assertEquals(1, runner.calls);
        assertEquals("MECP2", naked.getTranscriptAnnotations().get(0).getGeneSymbol());
    }

    @Test
    void assemblyMismatchMarksUnsupportedWithoutRunningVep() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        VariantInterpretation naked = nakedVariant("candidate", "GRCh37", "1", 100, "A", "G", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Collections.singletonList(naked),
                        context(GenomeAssembly.GRCh37)
                );

        assertEquals("UNAVAILABLE", summary.getStatus());
        assertEquals(0, runner.calls);
        assertEquals("UNAVAILABLE", naked.getTranscriptAnnotations().get(0).getStatus());
        assertTrue(naked.getTranscriptAnnotations().get(0).getConsequence().contains("RESOURCE_PROFILE_MISSING"));
    }

    @Test
    void grch37ProfileWithMissingResourcesDoesNotRunProvider() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        configureGrch37Profile(properties, false);
        VariantInterpretation naked = nakedVariant(
                "candidate",
                "GRCh37",
                "1",
                100,
                "A",
                "G",
                "PASS",
                "0/1",
                0.0001d
        );
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Collections.singletonList(naked),
                        context(GenomeAssembly.GRCh37)
                );

        assertEquals("UNAVAILABLE", summary.getStatus());
        assertEquals(0, runner.calls);
        assertEquals("UNAVAILABLE", naked.getTranscriptAnnotations().get(0).getStatus());
        assertTrue(naked.getTranscriptAnnotations().get(0).getConsequence().contains("required resources are missing"));
    }

    @Test
    void grch37ProfileRunsOnlyGrch37CommandWhenResourcesAreReady() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        Path grch37Cache = configureGrch37Profile(properties, true);
        VariantInterpretation naked = nakedVariant(
                "candidate",
                "GRCh37",
                "1",
                100,
                "A",
                "G",
                "PASS",
                "0/1",
                0.0001d
        );
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "DEMO37", "splice_donor_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Collections.singletonList(naked),
                        context(GenomeAssembly.GRCh37)
                );

        assertEquals("AVAILABLE", summary.getStatus());
        assertEquals(1, runner.calls);
        assertTrue(runner.lastCommand.contains("GRCh37"));
        assertTrue(runner.lastCommand.contains(grch37Cache.toString().replace('\\', '/')));
        assertFalse(runner.lastCommand.stream().anyMatch(token -> token.contains("GRCh38.fa")));
        assertEquals("DEMO37", naked.getTranscriptAnnotations().get(0).getGeneSymbol());
    }

    @Test
    void cacheKeySeparatesIdenticalAllelesByAssembly() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        configureGrch37Profile(properties, true);
        TranscriptAnnotationService transcriptAnnotationService = new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                new InMemoryTranscriptAnnotationCache()
        );
        VariantInterpretation grch37 = nakedVariant(
                "grch37",
                "GRCh37",
                "1",
                100,
                "A",
                "G",
                "PASS",
                "0/1",
                0.0001d
        );
        VariantInterpretation grch38 = nakedVariant(
                "grch38",
                "GRCh38",
                "1",
                100,
                "A",
                "G",
                "PASS",
                "0/1",
                0.0001d
        );

        assertFalse(transcriptAnnotationService.cacheKey(grch37)
                .equals(transcriptAnnotationService.cacheKey(grch38)));
    }

    @Test
    void candidateCapRecordsSkippedCount() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        properties.getVep().setPreRankingMaxCandidates(1);
        VariantInterpretation first = nakedVariant("first", "PASS", "0/1", 0.0001d);
        VariantInterpretation second = nakedVariant("second", "PASS", "0/1", 0.0001d);
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Arrays.asList(first, second),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals("AVAILABLE", summary.getStatus());
        assertEquals(2, summary.getTotalCandidateCount());
        assertEquals(2, summary.getEligibleCandidateCount());
        assertEquals(1, summary.getSelectedCount());
        assertEquals(1, summary.getPreRankingCandidateCap());
        assertEquals(1, summary.getSkippedByCapCount());
        assertEquals(Integer.valueOf(1), summary.getSkippedByReason().get("CANDIDATE_CAP"));
        assertTrue(String.valueOf(summary.getWarnings()).contains("PRE_RANKING_CANDIDATE_CAP_EXCEEDED"));
        assertTrue(summary.eventMessage().contains("preRankingCandidateCap=1"));
        assertTrue(summary.eventMessage().contains("selectedForVepCount=1"));
        assertEquals(1, runner.calls);
        assertEquals(1, first.getTranscriptAnnotations().size());
        assertTrue(second.getTranscriptAnnotations().isEmpty());
    }

    @Test
    void candidateCapProtectsPhenotypeIntervalRescueBeforeVep() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        properties.getVep().setPreRankingMaxCandidates(1);
        VariantInterpretation ordinary = nakedVariant("ordinary", "PASS", "0/1", 0.0001d);
        VariantInterpretation rescued = nakedVariant(
                "rescued", "1", 200, "A", "G", "PASS", "0/1", 0.0001d
        );
        rescued.applyLargeFileClassification(
                VariantAnalysisBucket.CANDIDATE,
                true,
                false,
                false,
                null,
                Collections.singletonList(
                        "LARGE_FILE_GATE_PHENOTYPE_PRIOR_GENE_INTERVAL: retained before hard cap because "
                                + "GENE_LONG_TAIL is a phenotype-prior gene rank 3932 and overlaps "
                                + "1:100-300 from TEST_INTERVAL"
                )
        );
        FakeRunner runner = new FakeRunner(FakeRunner.Mode.SUCCESS, "GENE_LONG_TAIL", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, runner).annotateCandidates(
                        Arrays.asList(ordinary, rescued),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals(1, summary.getSelectedCount());
        assertTrue(ordinary.getTranscriptAnnotations().isEmpty());
        assertEquals(1, rescued.getTranscriptAnnotations().size());
        assertTrue(runner.variantIds.contains(rescued.getVariantId()));
    }

    @Test
    void syntheticLargeCandidateFunnelRecordsCapTelemetry() throws IOException {
        VariantAnalysisProperties cappedProperties = readyProperties(true);
        cappedProperties.getVep().setPreRankingMaxCandidates(3000);
        cappedProperties.getVep().setPreRankingBatchSize(1000);
        FakeRunner cappedRunner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary cappedSummary =
                service(cappedProperties, cappedRunner).annotateCandidates(
                        syntheticCandidates(3500),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals("AVAILABLE", cappedSummary.getStatus());
        assertEquals(3500, cappedSummary.getTotalCandidateCount());
        assertEquals(3500, cappedSummary.getEligibleCandidateCount());
        assertEquals(3000, cappedSummary.getPreRankingCandidateCap());
        assertEquals(3000, cappedSummary.getSelectedCount());
        assertEquals(500, cappedSummary.getSkippedByCapCount());
        assertEquals(Integer.valueOf(500), cappedSummary.getSkippedByReason().get("CANDIDATE_CAP"));
        assertEquals(3, cappedRunner.calls);
        assertEquals(3000, cappedRunner.variantIds.size());
        assertTrue(String.valueOf(cappedSummary.getWarnings()).contains("PRE_RANKING_CANDIDATE_CAP_EXCEEDED"));
        assertTrue(cappedSummary.eventMessage().contains("inputVariantCount=3500"));
        assertTrue(cappedSummary.eventMessage().contains("preRankingCandidateCap=3000"));
        assertTrue(cappedSummary.eventMessage().contains("selectedForVepCount=3000"));
        assertTrue(cappedSummary.eventMessage().contains("skippedByCap=500"));

        VariantAnalysisProperties expandedProperties = readyProperties(true);
        expandedProperties.getVep().setPreRankingMaxCandidates(5000);
        expandedProperties.getVep().setPreRankingBatchSize(1000);
        FakeRunner expandedRunner = new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "missense_variant");

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary expandedSummary =
                service(expandedProperties, expandedRunner).annotateCandidates(
                        syntheticCandidates(3500),
                        context(GenomeAssembly.GRCh38)
                );

        assertEquals("AVAILABLE", expandedSummary.getStatus());
        assertEquals(3500, expandedSummary.getTotalCandidateCount());
        assertEquals(3500, expandedSummary.getEligibleCandidateCount());
        assertEquals(5000, expandedSummary.getPreRankingCandidateCap());
        assertEquals(3500, expandedSummary.getSelectedCount());
        assertEquals(0, expandedSummary.getSkippedByCapCount());
        assertEquals(4, expandedRunner.calls);
        assertEquals(3500, expandedRunner.variantIds.size());
        assertTrue(expandedSummary.eventMessage().contains("preRankingCandidateCap=5000"));
        assertTrue(expandedSummary.eventMessage().contains("selectedForVepCount=3500"));
        assertTrue(expandedSummary.eventMessage().contains("skippedByCap=0"));
    }

    @Test
    void hardGateVariantIsNotAnnotatedOrPhenotypeBoosted() throws IOException {
        VariantAnalysisProperties properties = readyProperties(true);
        properties.getPhenotypeRanking().setEnabled(true);
        VariantInterpretation common = nakedVariant("common", "PASS", "0/1", 0.5d);
        new TransparentVariantRankingService(properties).rank(Collections.singletonList(common));
        assertEquals("FILTERED_COMMON", common.getPriorityTier());

        PreRankingTranscriptAnnotationService.PreRankingAnnotationSummary summary =
                service(properties, new FakeRunner(FakeRunner.Mode.SUCCESS, "MECP2", "stop_gained"))
                        .annotateCandidates(Collections.singletonList(common), context(GenomeAssembly.GRCh38));
        PhenotypeAwareSummary phenotype = new PhenotypeAwareRerankingService(properties)
                .apply(Collections.singletonList(common), summaryWithPrior("MECP2"));

        assertEquals("NOT_REQUESTED", summary.getStatus());
        assertEquals(1, summary.getTotalCandidateCount());
        assertEquals(0, summary.getEligibleCandidateCount());
        assertTrue(common.getTranscriptAnnotations().isEmpty());
        assertFalse(phenotype.isRankingApplied());
        assertFalse(common.getScoreComponents().containsKey("phenotype_support"));
    }

    @Test
    void rankingReadsPreRankingTranscriptAnnotations() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantInterpretation variant = nakedVariant("candidate", "PASS", "0/1", 0.0001d);
        variant.applyTranscriptAnnotations(Collections.singletonList(new TranscriptConsequence(
                "MECP2",
                "ENSG00000169057",
                "ENST00000303391",
                "c.1A>T",
                "p.Lys1Ter",
                "stop_gained",
                "ENSG00000169057",
                "ENST00000303391",
                "protein_coding",
                "HIGH",
                null,
                null,
                true,
                true,
                null,
                "1",
                null,
                null,
                null,
                null,
                null,
                "PRE_RANKING_VEP",
                "COMPLETED",
                "vep-test",
                Instant.now()
        )));

        new TransparentVariantRankingService(properties).rank(Collections.singletonList(variant));

        assertEquals("HIGH_PRIORITY", variant.getPriorityTier());
    }

    private PreRankingTranscriptAnnotationService service(VariantAnalysisProperties properties,
                                                          FakeRunner runner) {
        return service(properties, runner, new InMemoryTranscriptAnnotationCache());
    }

    private PreRankingTranscriptAnnotationService service(VariantAnalysisProperties properties,
                                                          FakeRunner runner,
                                                          TranscriptAnnotationCache cache) {
        TranscriptAnnotationService transcriptAnnotationService = new TranscriptAnnotationService(
                properties,
                new AnnTranscriptAnnotationProvider(),
                cache
        );
        VepTranscriptAnnotationEnrichmentService vepService = new VepTranscriptAnnotationEnrichmentService(
                new InMemoryVariantAnalysisJobRepository(),
                properties,
                new VepResourceValidator(properties),
                new VepCommandBuilder(properties),
                new VepJsonOutputParser(new ObjectMapper()),
                runner,
                transcriptAnnotationService
        );
        return new PreRankingTranscriptAnnotationService(
                properties,
                new VepResourceValidator(properties),
                vepService
        );
    }

    private VariantAnalysisProperties readyProperties(boolean executionEnabled) throws IOException {
        Path executable = tempDir.resolve("vep");
        Path cache = tempDir.resolve("cache");
        Path fasta = tempDir.resolve("GRCh38.fa");
        Files.write(executable, new byte[] {1});
        Files.createDirectories(cache);
        Files.write(fasta, new byte[] {1});
        Files.write(tempDir.resolve("GRCh38.fa.fai"), new byte[] {1});

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(executionEnabled);
        properties.getVep().setExecutable(executable.toString());
        properties.getVep().setCacheDir(cache.toString());
        properties.getVep().setFasta(fasta.toString());
        properties.getVep().setVersion("vep-test");
        properties.getVep().setEnsemblCacheVersion("110_GRCh38");
        properties.getVep().setManeVersion("MANE-1.4");
        properties.getVep().setLofteeVersion("loftee-1.0");
        return properties;
    }

    private AnalysisContext context(GenomeAssembly assembly) {
        return new AnalysisContext("analysis", assembly, AnalysisMode.WES);
    }

    private VariantInterpretation nakedVariant(String id, String filter, String genotype, double gnomadAf) {
        return nakedVariant(id, "1", 100, "A", "G", filter, genotype, gnomadAf);
    }

    private VariantInterpretation nakedVariant(String id,
                                               String chromosome,
                                               int position,
                                               String reference,
                                               String alternate,
                                               String filter,
                                               String genotype,
                                               double gnomadAf) {
        return nakedVariant(id, "GRCh38", chromosome, position, reference, alternate, filter, genotype, gnomadAf);
    }

    private VariantInterpretation nakedVariant(String id,
                                               String assembly,
                                               String chromosome,
                                               int position,
                                               String reference,
                                               String alternate,
                                               String filter,
                                               String genotype,
                                               double gnomadAf) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey(assembly, chromosome, position, reference, alternate),
                "proband",
                genotype,
                30,
                99,
                "18,12",
                0.4d,
                filter,
                "SNV",
                chromosome + "\t" + position + "\t.\t" + reference + "\t" + alternate
        );
        return new VariantInterpretation(
                id,
                observation,
                Collections.<TranscriptConsequence>emptyList(),
                Collections.singletonList(gnomad(gnomadAf))
        );
    }

    private List<VariantInterpretation> syntheticCandidates(int count) {
        List<VariantInterpretation> variants = new ArrayList<VariantInterpretation>();
        for (int index = 0; index < count; index++) {
            variants.add(nakedVariant(
                    "synthetic-" + index,
                    "1",
                    100000 + index,
                    "A",
                    "G",
                    "PASS",
                    "0/1",
                    0.0001d
            ));
        }
        return variants;
    }

    private Path configureGrch37Profile(VariantAnalysisProperties properties, boolean ready) throws IOException {
        Path executable = tempDir.resolve("vep");
        if (!Files.exists(executable)) {
            Files.write(executable, new byte[] {1});
        }
        Path cache = tempDir.resolve("cache-GRCh37");
        Path fasta = tempDir.resolve("GRCh37.fa");
        if (ready) {
            Files.createDirectories(cache);
            Files.write(fasta, new byte[] {1});
            Files.write(tempDir.resolve("GRCh37.fa.fai"), new byte[] {1});
        }
        VariantAnalysisProperties.Vep.ResourceProfile profile =
                new VariantAnalysisProperties.Vep.ResourceProfile();
        profile.setEnabled(true);
        profile.setAssembly("GRCh37");
        profile.setExecutable(executable.toString());
        profile.setCacheDir(cache.toString());
        profile.setFasta(fasta.toString());
        profile.setVersion("vep-test");
        profile.setEnsemblCacheVersion("110_GRCh37");
        profile.setManeVersion("MANE-1.4");
        profile.setLofteeVersion("loftee-1.0");
        properties.getVep().getResources().put("GRCh37", profile);
        return cache;
    }

    private EvidenceItem gnomad(double af) {
        return new EvidenceItem(
                "GNOMAD",
                "test",
                "POPULATION_FREQUENCY",
                "APPLICABLE",
                af,
                "gnomAD AF",
                "Synthetic gnomAD evidence",
                Collections.singletonMap("af", af)
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
                Collections.singletonList(prior),
                Collections.emptyList(),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        return PhenotypeInputNormalizer.summarize(Collections.singletonList("HP:0001250"))
                .withGenePriorSummary(priorSummary);
    }

    private static class FakeRunner implements ExternalProcessRunner {

        private enum Mode {
            SUCCESS,
            FAIL,
            TIMEOUT
        }

        private final Mode mode;
        private final String gene;
        private final String consequence;
        private final List<String> variantIds = new ArrayList<String>();
        private List<String> lastCommand = Collections.emptyList();
        private int calls;

        private FakeRunner(Mode mode, String gene, String consequence) {
            this.mode = mode;
            this.gene = gene;
            this.consequence = consequence;
        }

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
            this.calls++;
            this.lastCommand = new ArrayList<String>(command);
            if (mode == Mode.FAIL) {
                return new ProcessResult(2, "simulated failure", false);
            }
            if (mode == Mode.TIMEOUT) {
                return new ProcessResult(1, "simulated timeout", true);
            }
            Path output = outputPath(command);
            StringBuilder json = new StringBuilder();
            for (String id : variantIds(inputPath(command))) {
                variantIds.add(id);
                json.append("{\"id\":\"").append(id).append("\",")
                        .append("\"transcript_consequences\":[{")
                        .append("\"gene_symbol\":\"").append(gene).append("\",")
                        .append("\"gene_id\":\"ENSG00000169057\",")
                        .append("\"transcript_id\":\"ENST00000303391\",")
                        .append("\"consequence_terms\":[\"").append(consequence).append("\"],")
                        .append("\"impact\":\"HIGH\",")
                        .append("\"canonical\":1,")
                        .append("\"mane_select\":\"MANE_SELECT\",")
                        .append("\"hgvsc\":\"c.1A>T\",")
                        .append("\"hgvsp\":\"p.Lys1Ter\"")
                        .append("}]}\n");
            }
            Files.write(output, json.toString().getBytes(StandardCharsets.UTF_8));
            return new ProcessResult(0, "ok", false);
        }

        private Path inputPath(List<String> command) {
            int index = command.indexOf("--input_file");
            return java.nio.file.Paths.get(command.get(index + 1));
        }

        private Path outputPath(List<String> command) {
            int index = command.indexOf("--output_file");
            return java.nio.file.Paths.get(command.get(index + 1));
        }

        private List<String> variantIds(Path input) throws IOException {
            java.util.ArrayList<String> ids = new java.util.ArrayList<String>();
            for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                ids.add(fields[2]);
            }
            return ids;
        }
    }

    private static class ThrowingTranscriptAnnotationCache implements TranscriptAnnotationCache {

        @Override
        public List<TranscriptConsequence> get(
                org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey key) {
            throw new IllegalStateException("cache unavailable");
        }

        @Override
        public void put(org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey key,
                        List<TranscriptConsequence> annotations) {
            throw new IllegalStateException("cache unavailable");
        }
    }
}
