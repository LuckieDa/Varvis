package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.exception.VariantAnalysisInputException;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisJobProcessor;
import org.nbscn.varvis.variantanalysis.service.VariantEnhancementProcessor;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.nbscn.varvis.variantanalysis.service.VcfInputPreflightService;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInputResolver;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisServiceImplVcfInputPreflightTest {

    @TempDir
    Path tempDir;

    @Test
    void productExomiserSubmissionRunsContentPreflightExactlyOnceAndEmitsASafeAggregateEvent()
            throws Exception {
        VariantAnalysisJobRepository repository = repositoryWithoutReuse();
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VcfInputPreflightService preflight = spy(new VcfInputPreflightService());
        VariantAnalysisServiceImpl service = service(
                exomiserProperties(tempDir.resolve("product")), repository, processor, preflight);

        VariantAnalysisJob job = service.submit(
                validVcf(), "GRCh38", "WES", "synthetic", Collections.emptyList());

        verify(preflight, times(1)).inspect(any(Path.class), eq("synthetic"));
        verify(repository).save(eq(job));
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
        verify(repository).appendEvent(
                eq(job.getId()),
                eq(job.getStatus()),
                eq("Input content preflight passed: records=1, alternateAlleles=1, "
                        + "selectedCalledGenotypes=1, warnings=0")
        );
    }

    @Test
    void benchmarkSubmissionNeverCallsProductContentPreflight() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VcfInputPreflightService preflight = spy(new VcfInputPreflightService());
        VariantAnalysisServiceImpl service = service(
                exomiserProperties(tempDir.resolve("benchmark")), repository, processor, preflight);
        BenchmarkTelemetryCollector telemetry = new BenchmarkTelemetryCollector(
                "synthetic-attempt", "synthetic-request", "synthetic-configuration", Collections.emptyList());

        VariantAnalysisJob job = service.submitBenchmark(
                validVcf(), "GRCh38", "WES", "synthetic", Collections.emptyList(),
                Collections.emptyList(), telemetry);

        verify(preflight, never()).inspect(any(Path.class), anyString());
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class), eq(telemetry));
    }

    @Test
    void legacySubmissionNeverCallsProductContentPreflight() throws Exception {
        VariantAnalysisJobRepository repository = repositoryWithoutReuse();
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VcfInputPreflightService preflight = spy(new VcfInputPreflightService());
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir.resolve("legacy"));
        properties.setDefaultEngine(AnalysisEngine.VARVIS);
        VariantAnalysisServiceImpl service = service(properties, repository, processor, preflight);

        VariantAnalysisJob job = service.submit(
                validVcf(), "GRCh38", "WES", "synthetic", Collections.emptyList());

        verify(preflight, never()).inspect(any(Path.class), anyString());
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void malformedTailFailsBeforePersistenceAndProcessingAndCleansThePrivateWorkspace()
            throws Exception {
        Path storageRoot = tempDir.resolve("failed");
        VariantAnalysisJobRepository repository = repositoryWithoutReuse();
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisServiceImpl service = service(
                exomiserProperties(storageRoot),
                repository,
                processor,
                new VcfInputPreflightService()
        );
        MockMultipartFile malformed = new MockMultipartFile(
                "vcfFile",
                "synthetic.vcf",
                "text/plain",
                (validVcfText() + "1\tnot-a-position\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class,
                () -> service.submit(
                        malformed, "GRCh38", "WES", "synthetic", Collections.emptyList())
        );

        assertEquals("INVALID_INPUT", error.getErrorCode());
        assertEquals(
                "VCF_CONTENT_PARSE_FAILED: uploaded VCF content could not be parsed completely.",
                error.getMessage()
        );
        verify(repository, never()).save(any(VariantAnalysisJob.class));
        verify(repository, never()).appendEvent(anyString(), any(), anyString());
        verify(processor, never()).process(anyString(), any(PhenotypeAwareSummary.class));
        assertTrue(Files.isDirectory(storageRoot));
        try (java.util.stream.Stream<Path> remaining = Files.list(storageRoot)) {
            assertEquals(0L, remaining.count());
        }
    }

    @Test
    void legacyReadyJobWithoutScientificEquivalenceAttestationIsIgnored() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob reusable = reusableExomiserJob(tempDir.resolve("existing"));
        when(repository.findReusableReadyJob(
                anyString(), eq("GRCh38"), eq("WES"), eq("synthetic")
        )).thenReturn(Optional.of(reusable));
        VcfInputPreflightService preflight = spy(new VcfInputPreflightService());
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisServiceImpl service = service(
                exomiserProperties(tempDir.resolve("reuse-submission")),
                repository,
                processor,
                preflight
        );

        VariantAnalysisJob returned = service.submit(
                validVcf(), "GRCh38", "WES", "synthetic", Collections.emptyList());

        assertTrue(!reusable.getId().equals(returned.getId()));
        verify(preflight, times(1)).inspect(any(Path.class), eq("synthetic"));
        verify(repository, never()).findReusableReadyJob(
                anyString(), anyString(), anyString(), anyString()
        );
        verify(repository).save(returned);
        verify(repository).appendEvent(returned.getId(), returned.getStatus(), "Uploaded VCF stored");
        verify(processor).process(eq(returned.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void legacyFiveSevenAndEightArgumentConstructorsRemainAvailable() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantEnhancementRepository enhancements = mock(VariantEnhancementRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantEnhancementProcessor enhancementProcessor = mock(VariantEnhancementProcessor.class);

        assertNotNull(new VariantAnalysisServiceImpl(
                repository, enhancements, processor, enhancementProcessor, properties));
        assertNotNull(new VariantAnalysisServiceImpl(
                repository, enhancements, processor, enhancementProcessor, properties,
                null, new TrioPipelineInputResolver()));
        assertNotNull(new VariantAnalysisServiceImpl(
                repository, enhancements, processor, enhancementProcessor, properties,
                null, new TrioPipelineInputResolver(), new VcfHeaderSampleDetectionService()));
    }

    private VariantAnalysisServiceImpl service(VariantAnalysisProperties properties,
                                               VariantAnalysisJobRepository repository,
                                               VariantAnalysisJobProcessor processor,
                                               VcfInputPreflightService preflight) {
        return new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties,
                null,
                new TrioPipelineInputResolver(),
                new VcfHeaderSampleDetectionService(),
                preflight
        );
    }

    // Seed the deprecated reuse API to preserve the legacy-input test fixture.
    private VariantAnalysisJobRepository repositoryWithoutReuse() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findReusableReadyJob(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        return repository;
    }

    private VariantAnalysisProperties exomiserProperties(Path storageRoot) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(storageRoot);
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        return properties;
    }

    private VariantAnalysisJob reusableExomiserJob(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        VariantAnalysisJob job = new VariantAnalysisJob(
                "existing-job",
                "synthetic.vcf",
                "existing-sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "synthetic",
                workspace,
                workspace.resolve("synthetic.vcf")
        );
        job.complete(
                "synthetic",
                false,
                "COPY_ONLY",
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );
        job.applyEngineMetadata(EngineMetadata.exomiserRanked("synthetic", "synthetic", "synthetic"));
        return job;
    }

    private MockMultipartFile validVcf() {
        return new MockMultipartFile(
                "vcfFile",
                "synthetic.vcf",
                "text/plain",
                validVcfText().getBytes(StandardCharsets.UTF_8)
        );
    }

    private String validVcfText() {
        return "##fileformat=VCFv4.2\n"
                + "##reference=GRCh38\n"
                + "##contig=<ID=1,length=248956422>\n"
                + "##INFO=<ID=SYNTHETIC,Number=1,Type=Integer,Description=\"Synthetic marker\">\n"
                + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                + "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Read depth\">\n"
                + "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n"
                + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allele depth\">\n"
                + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic\n"
                + "1\t100\t.\tA\tC\t50\tPASS\tSYNTHETIC=1\tGT:DP:GQ:AD\t0/1:20:99:10,10\n";
    }
}
