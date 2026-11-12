package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateIdentityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sourceOrdinalDefinesStableAnalysisLocalIdentity() {
        CandidateIdentity first = CandidateIdentity.exomiser(
                "GRCh38|1|100|A|T", 1, "GENE1", "AUTOSOMAL_DOMINANT", 1
        );
        CandidateIdentity reparsed = CandidateIdentity.exomiser(
                "GRCh38|1|100|A|T", 1, "UPDATED_GENE", "UPDATED_MOI", 99
        );
        CandidateIdentity second = CandidateIdentity.exomiser(
                "GRCh38|1|100|A|T", 2, "GENE1", "AUTOSOMAL_DOMINANT", 1
        );

        assertEquals("exomiser-v1-00000001", first.getCandidateId());
        assertEquals(first.getCandidateId(), reparsed.getCandidateId());
        assertNotEquals(first.getCandidateId(), second.getCandidateId());
        assertEquals("GRCh38|1|100|A|T", first.getCanonicalVariantId());
    }

    @Test
    void interpretationRoundTripKeepsBothIdentities() throws Exception {
        CandidateIdentity identity = CandidateIdentity.exomiser(
                "GRCh38|1|100|A|T", 7, "GENE1", "AUTOSOMAL_RECESSIVE", 3
        );
        VariantInterpretation source = new VariantInterpretation(
                identity,
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );

        VariantInterpretation restored = objectMapper.readValue(
                objectMapper.writeValueAsString(source),
                VariantInterpretation.class
        );

        assertEquals("exomiser-v1-00000007", restored.getCandidateId());
        assertEquals("GRCh38|1|100|A|T", restored.getCanonicalVariantId());
        assertEquals(restored.getCanonicalVariantId(), restored.getVariantId());
        assertEquals("GENE1", restored.getCandidateIdentity().getGeneSymbol());
        assertEquals("AUTOSOMAL_RECESSIVE", restored.getCandidateIdentity().getModeOfInheritance());
    }

    @Test
    void legacyPayloadFallsBackToCanonicalVariantIdentity() throws Exception {
        VariantInterpretation restored = objectMapper.readValue(
                "{\"variantId\":\"GRCh38|1|100|A|T\"}",
                VariantInterpretation.class
        );

        assertTrue(restored.getCandidateId().matches("legacy-v1-[0-9a-f]{24}"));
        assertEquals("GRCh38|1|100|A|T", restored.getCanonicalVariantId());
        assertEquals("LEGACY", restored.getCandidateIdentity().getSource());
    }

    @Test
    void legacyVariantAliasDoesNotReplaceCanonicalObservationIdentity() {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "T"),
                "sample",
                "0/1",
                null,
                null,
                null,
                null,
                "PASS",
                "SNV",
                null
        );

        VariantInterpretation interpretation = new VariantInterpretation(
                "GRCh38:1:100:A:T",
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );

        assertEquals("GRCh38:1:100:A:T", interpretation.getVariantId());
        assertEquals("GRCh38|1|100|A|T", interpretation.getCanonicalVariantId());
        assertTrue(interpretation.getCandidateId().matches("legacy-v1-[0-9a-f]{24}"));
    }

    @Test
    void rejectsConflictingNestedAndTopLevelCandidateIds() {
        String payload = "{"
                + "\"variantId\":\"GRCh38|1|100|A|T\","
                + "\"candidateId\":\"exomiser-v1-00000002\","
                + "\"candidateIdentity\":{"
                + "\"schemaVersion\":\"candidate-identity-v1\","
                + "\"candidateId\":\"exomiser-v1-00000001\","
                + "\"canonicalVariantId\":\"GRCh38|1|100|A|T\","
                + "\"source\":\"EXOMISER\",\"sourceOrdinal\":1}}";

        Exception error = assertThrows(
                Exception.class,
                () -> objectMapper.readValue(payload, VariantInterpretation.class)
        );

        assertTrue(error.getMessage().contains("CANDIDATE_ID_MISMATCH"));
    }
}
