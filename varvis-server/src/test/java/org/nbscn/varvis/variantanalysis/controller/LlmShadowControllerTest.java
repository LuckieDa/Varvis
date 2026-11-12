package org.nbscn.varvis.variantanalysis.controller;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.dto.LlmShadowPreviewResponse;
import org.nbscn.varvis.variantanalysis.service.llmshadow.LlmShadowPreviewService;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmShadowControllerTest {

    @Test
    void delegatesReadOnlyPreviewRequest() {
        LlmShadowPreviewService service = mock(LlmShadowPreviewService.class);
        LlmShadowPreviewResponse expected = new LlmShadowPreviewResponse(
                LlmShadowPreviewResponse.Status.DISABLED,
                null,
                new LlmShadowPreviewResponse.Provenance(
                        "NONE", "llm-shadow-input-v1", 0, null));
        when(service.preview("analysis")).thenReturn(expected);

        LlmShadowPreviewResponse actual = new LlmShadowController(service).preview("analysis");

        assertSame(expected, actual);
        verify(service).preview("analysis");
    }
}
