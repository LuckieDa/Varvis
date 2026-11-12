package org.nbscn.varvis.variantanalysis.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse;
import org.nbscn.varvis.variantanalysis.persistence.AmbiguousVariantIdException;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisJobProcessor;
import org.nbscn.varvis.variantanalysis.service.VariantEnhancementProcessor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisRankingExplanationIntegrationTest {

    @Test
    void explanationReadsPersistedCandidateWithoutSavingOrProcessing() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantEnhancementRepository enhancementRepository = mock(VariantEnhancementRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantEnhancementProcessor enhancementProcessor = mock(VariantEnhancementProcessor.class);
        VariantAnalysisJob job = mock(VariantAnalysisJob.class);
        VariantInterpretation variant = new VariantInterpretation(
                "synthetic-variant", null, Collections.emptyList(), Collections.emptyList());
        when(repository.findById("analysis-1")).thenReturn(Optional.of(job));
        when(repository.findInterpretation("analysis-1", "synthetic-variant")).thenReturn(Optional.of(variant));
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository, enhancementRepository, processor, enhancementProcessor, new VariantAnalysisProperties());

        CandidateRankingExplanationResponse response =
                service.explainVariantRanking("analysis-1", "synthetic-variant");

        assertEquals("synthetic-variant", response.getVariantId());
        assertEquals("candidate-ranking-explanation-v1", response.getSchemaVersion());
        assertNull(response.getCandidateId());
        assertNull(response.getCanonicalVariantId());
        String json = new ObjectMapper().writeValueAsString(response);
        assertFalse(json.contains("\"candidateId\""));
        assertFalse(json.contains("\"canonicalVariantId\""));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(VariantAnalysisJob.class));
        verify(processor, never()).process(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary.class));
    }

    @Test
    void candidateExplanationUsesExactCandidateRowIdentity() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantInterpretation candidate = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        "GRCh38|1|100|A|T", 2, "GENE2", "AUTOSOMAL_RECESSIVE", 2
                ),
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );
        when(repository.findById("analysis-1")).thenReturn(Optional.of(mock(VariantAnalysisJob.class)));
        when(repository.findCandidate("analysis-1", "exomiser-v1-00000002"))
                .thenReturn(Optional.of(candidate));

        CandidateRankingExplanationResponse response = service(repository)
                .explainCandidateRanking("analysis-1", "exomiser-v1-00000002");

        assertEquals("candidate-ranking-explanation-v2", response.getSchemaVersion());
        assertEquals("exomiser-v1-00000002", response.getCandidateId());
        assertEquals("GRCh38|1|100|A|T", response.getCanonicalVariantId());
        assertEquals(response.getCanonicalVariantId(), response.getVariantId());
    }

    @Test
    void ambiguousLegacyVariantIdReturnsConflictInsteadOfFirstRow() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("analysis-1")).thenReturn(Optional.of(mock(VariantAnalysisJob.class)));
        when(repository.findInterpretation("analysis-1", "GRCh38|1|100|A|T"))
                .thenThrow(new AmbiguousVariantIdException("GRCh38|1|100|A|T"));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service(repository).getVariant("analysis-1", "GRCh38|1|100|A|T")
        );

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals(true, java.util.Objects.requireNonNull(failure.getReason()).contains("AMBIGUOUS_VARIANT_ID"));
    }

    @Test
    void migratedDuplicateLegacyCandidateRequiresReanalysisInsteadOfFalsePrecision() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantInterpretation first = new VariantInterpretation(
                CandidateIdentity.legacy("legacy-v1-0000000000", "GRCh38|1|100|A|T"),
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );
        VariantInterpretation second = new VariantInterpretation(
                CandidateIdentity.legacy("legacy-v1-0000000001", "GRCh38|1|100|A|T"),
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );
        when(repository.findById("analysis-1")).thenReturn(Optional.of(mock(VariantAnalysisJob.class)));
        when(repository.findCandidate("analysis-1", "legacy-v1-0000000000"))
                .thenReturn(Optional.of(first));
        when(repository.findInterpretationsByVariantId("analysis-1", "GRCh38|1|100|A|T"))
                .thenReturn(Arrays.asList(first, second));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service(repository).getCandidate("analysis-1", "legacy-v1-0000000000")
        );

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals(true, java.util.Objects.requireNonNull(failure.getReason()).contains("LEGACY_CANDIDATE_IDENTITY_UNCERTAIN"));
    }

    @Test
    void missingAnalysisKeepsNotFoundSemanticsWithoutReadingCandidates() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("missing-analysis")).thenReturn(Optional.empty());
        VariantAnalysisServiceImpl service = service(repository);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.explainVariantRanking("missing-analysis", "synthetic-variant"));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verify(repository, never()).findInterpretation(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingVariantKeepsNotFoundSemantics() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("analysis-1")).thenReturn(Optional.of(mock(VariantAnalysisJob.class)));
        when(repository.findInterpretation("analysis-1", "missing-variant")).thenReturn(Optional.empty());
        VariantAnalysisServiceImpl service = service(repository);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.explainVariantRanking("analysis-1", "missing-variant"));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
    }

    private VariantAnalysisServiceImpl service(VariantAnalysisJobRepository repository) {
        return new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                new VariantAnalysisProperties());
    }
}
