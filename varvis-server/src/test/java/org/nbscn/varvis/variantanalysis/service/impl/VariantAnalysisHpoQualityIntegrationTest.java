package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.nbscn.varvis.exception.VariantAnalysisInputException;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.HpoInputQualityService;
import org.nbscn.varvis.variantanalysis.service.HpoTermResolver;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisJobProcessor;
import org.nbscn.varvis.variantanalysis.service.VariantEnhancementProcessor;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.nbscn.varvis.variantanalysis.service.VcfInputPreflightService;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInputResolver;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VariantAnalysisHpoQualityIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void benchmarkSubmissionKeepsLegacyNormalizerAndNeverTouchesResolver() throws Exception {
        VariantAnalysisJobRepository repository = repositoryWithoutReuse();
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisServiceImpl service = service(
                repository,
                processor,
                new HpoInputQualityService(new ThrowingResolver())
        );
        BenchmarkTelemetryCollector telemetry = new BenchmarkTelemetryCollector(
                "synthetic-attempt",
                repeat('a', 64),
                repeat('b', 64),
                Collections.emptyList()
        );

        service.submitBenchmark(
                vcf(),
                "GRCh38",
                "WES",
                null,
                Collections.nCopies(401, "legacy-invalid-term"),
                Collections.emptyList(),
                telemetry
        );

        verify(processor).process(anyString(), any(PhenotypeAwareSummary.class), any(BenchmarkTelemetryCollector.class));
    }

    @Test
    void productInputBudgetFailsBeforeResolverRepositoryOrProcessor() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisServiceImpl service = service(
                repository,
                processor,
                new HpoInputQualityService(new ThrowingResolver())
        );

        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class,
                () -> service.submit(vcf(), "GRCh38", "WES", null,
                        Collections.nCopies(401, "HP:0000001"))
        );

        assertEquals("HPO_INPUT_LIMIT_EXCEEDED", error.getErrorCode());
        assertEquals("HPO_INPUT_LIMIT_EXCEEDED", error.getMessage());
        verifyNoInteractions(repository, processor);
    }

    @Test
    void productUnknownTermFailsBeforePersistenceOrProcessing() {
        VariantAnalysisJobRepository repository = repositoryWithoutReuse();
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        CountingResolver resolver = new CountingResolver(false);
        VariantAnalysisServiceImpl service = service(
                repository,
                processor,
                new HpoInputQualityService(resolver)
        );

        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class,
                () -> service.submit(vcf(), "GRCh38", "WES", null,
                        Collections.singletonList("HP:9999999"))
        );

        assertTrue(error.getMessage().contains("HPO_TERM_UNKNOWN"));
        assertEquals(1, resolver.resolveCalls);
        verify(repository, never()).save(any());
        verify(processor, never()).process(anyString(), any(PhenotypeAwareSummary.class));
    }

    @Test
    void productPersistsExcludedTermsButSendsOnlyPositiveTermsToAlgorithmFields() throws Exception {
        VariantAnalysisJobRepository repository = repositoryWithoutReuse();
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        CountingResolver resolver = new CountingResolver(true);
        VariantAnalysisServiceImpl service = service(
                repository,
                processor,
                new HpoInputQualityService(resolver)
        );

        service.submit(
                vcf(),
                "GRCh38",
                "WES",
                null,
                Collections.singletonList("HP:0000001"),
                Collections.emptyList(),
                Collections.singletonList("HP:0000002"),
                TrioAnalysisInputRequest.disabled(),
                null,
                null
        );

        ArgumentCaptor<PhenotypeAwareSummary> summaryCaptor =
                ArgumentCaptor.forClass(PhenotypeAwareSummary.class);
        verify(processor).process(anyString(), summaryCaptor.capture());
        PhenotypeAwareSummary summary = summaryCaptor.getValue();
        assertEquals(Collections.singletonList("HP:0000001"), summary.getValidHpoTerms());
        assertEquals(Collections.singletonList("HP:0000002"), summary.getExcludedHpoTerms());
        assertTrue(summary.getPhenotypeInputs().stream()
                .anyMatch(input -> input.isExcluded() && "HP:0000002".equals(input.getHpoId())));
        assertEquals(2, resolver.resolveCalls);
        verify(repository).save(any());
    }

    @Test
    void legacyReadyJobIsNotReusedEvenWhenOnlyExplicitlyAbsentTermsDiffer() throws Exception {
        CountingResolver resolver = new CountingResolver(true);
        HpoInputQualityService qualityService = new HpoInputQualityService(resolver);
        PhenotypeAwareSummary existingSummary = qualityService.summarizeProduct(
                Collections.singletonList("HP:0000001"),
                null,
                Collections.singletonList("HP:0000002")
        );
        VariantAnalysisJob reusable = new VariantAnalysisJob(
                "existing-analysis",
                "synthetic.vcf",
                repeat('c', 64),
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                tempDir.resolve("existing"),
                tempDir.resolve("existing/synthetic.vcf"),
                existingSummary
        );
        reusable.complete(null, false, "COPY_ONLY", null,
                Collections.emptyList(), Collections.emptyList());
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findReusableReadyJob(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.of(reusable));
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisServiceImpl service = service(repository, processor, qualityService);

        VariantAnalysisJob submitted = service.submit(
                vcf(),
                "GRCh38",
                "WES",
                null,
                Collections.singletonList("HP:0000001"),
                Collections.emptyList(),
                Collections.singletonList("HP:0000003"),
                TrioAnalysisInputRequest.disabled(),
                null,
                null
        );

        assertTrue(!"existing-analysis".equals(submitted.getId()));
        verify(repository, never()).findReusableReadyJob(
                anyString(), anyString(), anyString(), any()
        );
        verify(repository).save(any(VariantAnalysisJob.class));
        verify(processor).process(anyString(), any(PhenotypeAwareSummary.class));
    }

    private VariantAnalysisServiceImpl service(VariantAnalysisJobRepository repository,
                                               VariantAnalysisJobProcessor processor,
                                               HpoInputQualityService qualityService) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        return new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties,
                null,
                new TrioPipelineInputResolver(),
                new VcfHeaderSampleDetectionService(),
                new VcfInputPreflightService(),
                qualityService
        );
    }

    // Seed the deprecated reuse API to preserve the legacy-input test fixture.
    private VariantAnalysisJobRepository repositoryWithoutReuse() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findReusableReadyJob(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        return repository;
    }

    private MockMultipartFile vcf() {
        return new MockMultipartFile(
                "vcfFile",
                "synthetic.vcf",
                "text/plain",
                ("##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "1\t100\t.\tA\tG\t.\tPASS\t.\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private String repeat(char value, int count) {
        return String.valueOf(value).repeat(count);
    }

    private static final class ThrowingResolver implements HpoTermResolver {
        @Override
        public Resolution resolve(String hpoId) {
            throw new AssertionError("Benchmark submission must not call the HPO resolver");
        }

        @Override
        public boolean isAvailable() {
            throw new AssertionError("Benchmark submission must not inspect the HPO resolver");
        }

        @Override
        public String getDataVersion() {
            throw new AssertionError("Benchmark submission must not inspect the HPO resolver");
        }
    }

    private static final class CountingResolver implements HpoTermResolver {
        private final boolean current;
        private int resolveCalls;

        private CountingResolver(boolean current) {
            this.current = current;
        }

        @Override
        public Resolution resolve(String hpoId) {
            resolveCalls++;
            return current
                    ? new Resolution(hpoId, hpoId, null, "Synthetic " + hpoId,
                    HpoOntologyTermStatus.CURRENT, true, null)
                    : new Resolution(hpoId, null, null, null,
                    HpoOntologyTermStatus.UNKNOWN, false, "HPO_TERM_UNKNOWN");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getDataVersion() {
            return "synthetic-v1";
        }
    }
}
