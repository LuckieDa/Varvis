package org.nbscn.varvis.variantanalysis.service.llmshadow;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.dto.LlmShadowPreviewResponse;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LlmShadowPreviewServiceTest {

    @Test
    void springConstructorAcceptsNoProviderBeans() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmShadowProvider> providers = mock(ObjectProvider.class);

        LlmShadowPreviewResponse response = new LlmShadowPreviewService(
                properties, repository, providers).preview("private-analysis-id");

        assertEquals("DISABLED", response.getStatus());
        verifyNoInteractions(repository, providers);
    }

    @Test
    void disabledReturnsBeforeRepositoryAndProvider() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLlmShadow().setPromptSchemaVersion("patient id and C:/private/case.vcf");
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        LlmShadowProvider provider = mock(LlmShadowProvider.class);

        LlmShadowPreviewResponse response = service(properties, repository, provider).preview("private-analysis-id");

        assertEquals("DISABLED", response.getStatus());
        assertEquals("NONE", response.getProvenance().getProvider());
        assertEquals("llm-shadow-input-v1", response.getProvenance().getSchemaVersion());
        assertFalse(response.getProvenance().isExternalTransmission());
        assertFalse(response.getProvenance().isRankingModified());
        assertFalse(response.getProvenance().isDiagnosisGenerated());
        assertNull(response.getPreviewText());
        assertNull(response.getProvenance().getInputFingerprint());
        verifyNoInteractions(repository, provider);
    }

    @Test
    void enabledNoneFailsClosedBeforeRepositoryAndProvider() {
        VariantAnalysisProperties properties = enabled("NONE");
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        LlmShadowProvider provider = mock(LlmShadowProvider.class);

        LlmShadowPreviewResponse response = service(properties, repository, provider).preview("private-analysis-id");

        assertEquals("PROVIDER_UNAVAILABLE", response.getStatus());
        assertEquals("NONE", response.getProvenance().getProvider());
        verifyNoInteractions(repository, provider);
    }

    @Test
    void enabledSyntheticProviderReceivesRestrictedInputAndCannotModifyPersistence() {
        VariantAnalysisProperties properties = enabled("SYNTHETIC_TEST");
        properties.getLlmShadow().setMaxOutputCharacters(128);
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        LlmShadowProvider provider = mock(LlmShadowProvider.class);
        VariantInterpretation variant = mock(VariantInterpretation.class);
        when(provider.providerName()).thenReturn("SYNTHETIC_TEST");
        when(provider.preview(any(LlmShadowInput.class))).thenReturn("<b>shadow</b>\u0000\npreview");
        when(repository.findById("analysis")).thenReturn(Optional.of(mock(VariantAnalysisJob.class)));
        when(repository.findInterpretationSummaries(any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(Collections.singletonList(variant));
        when(variant.getScoreComponents()).thenReturn(Collections.singletonMap(
                "population_frequency", new ScoreBreakdownComponent(-2.0d,
                        Collections.singletonList("sample patient path free text"))));
        when(variant.isFullInterpretationAvailable()).thenReturn(true);

        LlmShadowPreviewResponse response = service(properties, repository, provider).preview("analysis");

        assertEquals("PREVIEW_READY", response.getStatus());
        assertEquals("bshadow/b\npreview", response.getPreviewText());
        assertEquals(1, response.getProvenance().getCandidateCount());
        assertNotNull(response.getProvenance().getInputFingerprint());
        assertEquals(64, response.getProvenance().getInputFingerprint().length());
        verify(repository).findById("analysis");
        verify(repository).findInterpretationSummaries(
                eq("analysis"), eq(0), eq(10), any(), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void externalProviderIsRejectedBeforeRepositoryAndGeneration() {
        VariantAnalysisProperties properties = enabled("EXTERNAL_TEST");
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        LlmShadowProvider provider = mock(LlmShadowProvider.class);
        when(provider.providerName()).thenReturn("EXTERNAL_TEST");
        when(provider.externalTransmission()).thenReturn(true);

        LlmShadowPreviewResponse response = service(properties, repository, provider).preview("analysis");

        assertEquals("PROVIDER_UNAVAILABLE", response.getStatus());
        assertFalse(response.getProvenance().isExternalTransmission());
        verifyNoInteractions(repository);
        verify(provider, never()).preview(any());
    }

    @Test
    void providerMetadataFailureIsClosedBeforeRepository() {
        VariantAnalysisProperties properties = enabled("BROKEN");
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        LlmShadowProvider provider = mock(LlmShadowProvider.class);
        when(provider.providerName()).thenThrow(new IllegalStateException("private provider failure"));

        LlmShadowPreviewResponse response = service(properties, repository, provider).preview("analysis");

        assertEquals("PROVIDER_UNAVAILABLE", response.getStatus());
        assertNull(response.getPreviewText());
        verifyNoInteractions(repository);
        verify(provider, never()).preview(any());
    }

    @Test
    void duplicateProviderNamesFailClosedBeforeRepository() {
        VariantAnalysisProperties properties = enabled("LOCAL_DUPLICATE");
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        LlmShadowProvider first = mock(LlmShadowProvider.class);
        LlmShadowProvider second = mock(LlmShadowProvider.class);
        when(first.providerName()).thenReturn("LOCAL_DUPLICATE");
        when(second.providerName()).thenReturn("LOCAL_DUPLICATE");

        LlmShadowPreviewResponse response = new LlmShadowPreviewService(
                properties, repository, Arrays.asList(first, second)).preview("analysis");

        assertEquals("PROVIDER_UNAVAILABLE", response.getStatus());
        verifyNoInteractions(repository);
        verify(first, never()).preview(any());
        verify(second, never()).preview(any());
    }

    private VariantAnalysisProperties enabled(String provider) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLlmShadow().setEnabled(true);
        properties.getLlmShadow().setProvider(provider);
        return properties;
    }

    private LlmShadowPreviewService service(VariantAnalysisProperties properties,
                                            VariantAnalysisJobRepository repository,
                                            LlmShadowProvider provider) {
        return new LlmShadowPreviewService(properties, repository,
                Collections.singletonList(provider));
    }
}
