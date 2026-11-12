package org.nbscn.varvis.variantanalysis.service.llmshadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmShadowInputAssemblerTest {

    @Test
    void emitsOnlyOrdinalsAllowlistedKeysFiniteScoresAndFixedTokens() throws Exception {
        VariantInterpretation variant = mock(VariantInterpretation.class);
        Map<String, ScoreBreakdownComponent> components = new LinkedHashMap<String, ScoreBreakdownComponent>();
        components.put("clinical_evidence", new ScoreBreakdownComponent(3.0d,
                Collections.singletonList("patient-17 C:/private/case.vcf GENE1 HP:0000001")));
        components.put("sample_secret_path", new ScoreBreakdownComponent(99.0d,
                Collections.singletonList("must never cross boundary")));
        components.put("qc_quality", new ScoreBreakdownComponent(Double.NaN,
                Collections.singletonList("not finite")));
        components.put("population_frequency", new ScoreBreakdownComponent(Double.POSITIVE_INFINITY,
                Collections.singletonList("not finite")));
        components.put("splice_prediction", new ScoreBreakdownComponent(Double.NEGATIVE_INFINITY,
                Collections.singletonList("not finite")));
        when(variant.getScoreComponents()).thenReturn(components);
        when(variant.isFullInterpretationAvailable()).thenReturn(true);

        LlmShadowInput input = new LlmShadowInputAssembler()
                .assemble("llm-shadow-input-v1", Arrays.asList(variant));

        assertEquals(1, input.getCandidates().size());
        LlmShadowInput.Candidate candidate = input.getCandidates().get(0);
        assertEquals(1, candidate.getCandidateOrdinal());
        assertEquals("COMPLETE", candidate.getCompleteness());
        assertEquals("PERSISTED_COMPONENTS_AVAILABLE", candidate.getAvailabilityMeaning());
        assertEquals(1, candidate.getComponents().size());
        assertEquals("clinical_evidence", candidate.getComponents().get(0).getKey());
        assertEquals(3.0d, candidate.getComponents().get(0).getScore());
        assertEquals("POSITIVE", candidate.getComponents().get(0).getDirection());
        String boundary = new ObjectMapper().writeValueAsString(input);
        assertEquals("{\"schemaVersion\":\"llm-shadow-input-v1\",\"candidates\":[{"
                + "\"candidateOrdinal\":1,\"completeness\":\"COMPLETE\","
                + "\"availabilityMeaning\":\"PERSISTED_COMPONENTS_AVAILABLE\","
                + "\"components\":[{\"key\":\"clinical_evidence\",\"score\":3.0,"
                + "\"direction\":\"POSITIVE\"}]}]}", boundary);
        for (String canary : Arrays.asList("patient", "sample", "GENE1", "HP:", ".vcf",
                "variantId", "position", "chromosome", "resourceVersion", "C:/private")) {
            assertFalse(boundary.contains(canary), canary);
        }
    }
}
