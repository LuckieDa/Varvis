package org.nbscn.varvis.variantanalysis.controller;

import org.nbscn.varvis.variantanalysis.dto.LlmShadowPreviewResponse;
import org.nbscn.varvis.variantanalysis.service.llmshadow.LlmShadowPreviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LlmShadowController {

    private final LlmShadowPreviewService previewService;

    public LlmShadowController(LlmShadowPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/variant-analyses/{id}/llm-shadow-preview")
    public LlmShadowPreviewResponse preview(@PathVariable("id") String analysisId) {
        return previewService.preview(analysisId);
    }
}
