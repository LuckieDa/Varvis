package org.nbscn.varvis.variantanalysis.controller;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisExportService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisPreflightService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisDiagnosticsControllerTest {

    @Test
    void delegatesReadOnlyDiagnosticsRequest() {
        VariantAnalysisService service = mock(VariantAnalysisService.class);
        VariantAnalysisDiagnosticResponse expected = response();
        when(service.diagnostics("analysis-1")).thenReturn(expected);

        VariantAnalysisDiagnosticResponse actual = controller(service).diagnostics("analysis-1");

        assertSame(expected, actual);
        verify(service).diagnostics("analysis-1");
    }

    @Test
    void preservesServiceNotFoundResponse() {
        VariantAnalysisService service = mock(VariantAnalysisService.class);
        ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        when(service.diagnostics("missing")).thenThrow(notFound);

        ResponseStatusException actual = assertThrows(
                ResponseStatusException.class,
                () -> controller(service).diagnostics("missing")
        );

        assertSame(notFound, actual);
    }

    private VariantAnalysisDiagnosticResponse response() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        return new VariantAnalysisDiagnosticResponse(
                VariantAnalysisDiagnosticResponse.RunState.RUNNING,
                VariantAnalysisDiagnosticResponse.Stage.RANKING,
                VariantAnalysisDiagnosticResponse.FailureCode.NONE,
                VariantAnalysisDiagnosticResponse.FailureCategory.NONE,
                VariantAnalysisDiagnosticResponse.NextAction.WAIT_FOR_COMPLETION,
                VariantAnalysisDiagnosticResponse.RetryDisposition.NOT_APPLICABLE,
                timestamp,
                timestamp
        );
    }

    private VariantAnalysisController controller(VariantAnalysisService service) {
        return new VariantAnalysisController(
                service,
                mock(VariantAnalysisExportService.class),
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties()
        );
    }
}
