package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisJobProcessor;
import org.nbscn.varvis.variantanalysis.service.VariantEnhancementProcessor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class VariantAnalysisDiagnosticsIntegrationTest {

    @Test
    void diagnosticsReadsOnlyTheJobRepositoryRecord() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantEnhancementRepository enhancementRepository = mock(VariantEnhancementRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantEnhancementProcessor enhancementProcessor = mock(VariantEnhancementProcessor.class);
        VariantAnalysisJob job = job();
        job.fail("EXOMISER_TIMEOUT:D:\\private\\patient.vcf");
        when(repository.findById("analysis-1")).thenReturn(Optional.of(job));
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                enhancementRepository,
                processor,
                enhancementProcessor,
                new VariantAnalysisProperties()
        );

        VariantAnalysisDiagnosticResponse response = service.diagnostics("analysis-1");

        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.ENGINE_TIMEOUT,
                response.getFailureCode());
        verify(repository).findById("analysis-1");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(enhancementRepository, processor, enhancementProcessor);
    }

    @Test
    void missingAnalysisKeepsNotFoundSemantics() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("missing")).thenReturn(Optional.empty());
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                new VariantAnalysisProperties()
        );

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.diagnostics("missing")
        );

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verify(repository).findById("missing");
        verifyNoMoreInteractions(repository);
    }

    private VariantAnalysisJob job() {
        return new VariantAnalysisJob(
                "analysis-1",
                "patient.vcf",
                "synthetic-sha256",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "PROBAND",
                Paths.get("synthetic-workspace"),
                Paths.get("synthetic-upload.vcf")
        );
    }
}
