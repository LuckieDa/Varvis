package org.nbscn.varvis.variantanalysis.controller;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisExportService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisPreflightService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisRankingExplanationControllerTest {

    @Test
    void delegatesReadOnlyExplanationRequest() {
        VariantAnalysisService service = mock(VariantAnalysisService.class);
        CandidateRankingExplanationResponse expected = new CandidateRankingExplanationResponse(
                "analysis-1", "variant-1", "HIGH", 1.0, null,
                CandidateRankingExplanationResponse.Completeness.COMPLETE,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        when(service.explainVariantRanking("analysis-1", "variant-1")).thenReturn(expected);

        CandidateRankingExplanationResponse actual = controller(service)
                .explainVariantRanking("analysis-1", "variant-1");

        assertSame(expected, actual);
        verify(service).explainVariantRanking("analysis-1", "variant-1");
    }

    @Test
    void preservesServiceNotFoundResponse() {
        VariantAnalysisService service = mock(VariantAnalysisService.class);
        ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        when(service.explainVariantRanking("missing", "variant-1")).thenThrow(notFound);

        ResponseStatusException actual = assertThrows(ResponseStatusException.class,
                () -> controller(service).explainVariantRanking("missing", "variant-1"));

        assertSame(notFound, actual);
    }

    @Test
    void delegatesCandidateAwareExplanationRequest() {
        VariantAnalysisService service = mock(VariantAnalysisService.class);
        CandidateRankingExplanationResponse expected = new CandidateRankingExplanationResponse(
                "analysis-1",
                "exomiser-v1-00000002",
                "GRCh38|1|100|A|T",
                "GRCh38|1|100|A|T",
                "REVIEW",
                0.8,
                null,
                CandidateRankingExplanationResponse.Completeness.COMPLETE,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        when(service.explainCandidateRanking("analysis-1", "exomiser-v1-00000002"))
                .thenReturn(expected);

        CandidateRankingExplanationResponse actual = controller(service)
                .explainCandidateRanking("analysis-1", "exomiser-v1-00000002");

        assertSame(expected, actual);
        verify(service).explainCandidateRanking("analysis-1", "exomiser-v1-00000002");
    }

    private VariantAnalysisController controller(VariantAnalysisService service) {
        return new VariantAnalysisController(
                service,
                mock(VariantAnalysisExportService.class),
                mock(VariantAnalysisPreflightService.class),
                mock(VcfHeaderSampleDetectionService.class),
                new VariantAnalysisProperties());
    }
}
