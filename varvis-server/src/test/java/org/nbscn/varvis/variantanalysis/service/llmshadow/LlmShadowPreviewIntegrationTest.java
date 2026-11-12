package org.nbscn.varvis.variantanalysis.service.llmshadow;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.dto.LlmShadowPreviewResponse;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmShadowPreviewIntegrationTest {

    @Test
    void readsPersistedSyntheticRankingWithoutMutatingIt() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantAnalysisJob job = mock(VariantAnalysisJob.class);
        when(job.getId()).thenReturn("synthetic-analysis");
        repository.save(job);
        VariantInterpretation candidate = mock(VariantInterpretation.class);
        when(candidate.getVariantId()).thenReturn("synthetic-variant");
        when(candidate.getScoreComponents()).thenReturn(Collections.singletonMap(
                "clinical_evidence", new ScoreBreakdownComponent(4.0d, Collections.emptyList())));
        when(candidate.isFullInterpretationAvailable()).thenReturn(true);
        repository.replaceInterpretations("synthetic-analysis", Collections.singletonList(candidate));

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLlmShadow().setEnabled(true);
        properties.getLlmShadow().setProvider("LOCAL_SYNTHETIC");
        LlmShadowProvider provider = new LlmShadowProvider() {
            @Override public String providerName() { return "LOCAL_SYNTHETIC"; }
            @Override public boolean externalTransmission() { return false; }
            @Override public String preview(LlmShadowInput input) { return "Synthetic shadow preview."; }
        };

        LlmShadowPreviewResponse response = new LlmShadowPreviewService(
                properties, repository, Collections.singletonList(provider))
                .preview("synthetic-analysis");

        assertEquals("PREVIEW_READY", response.getStatus());
        assertEquals("Synthetic shadow preview.", response.getPreviewText());
        assertSame(candidate, repository.findInterpretation(
                "synthetic-analysis", "synthetic-variant").orElse(null));
        assertEquals(4.0d, candidate.getScoreComponents().get("clinical_evidence").getScore());
    }
}
