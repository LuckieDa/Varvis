package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Precise failure explanations do not widen transcript or score eligibility. */
class AlphaMissenseAssociationReasonTest {
    final ObjectMapper mapper = new ObjectMapper();
    TranscriptConsequence tx() throws Exception {
        return mapper.treeToValue(mapper.createObjectNode().put("transcriptAccession", "ENST000001.10")
                .put("proteinPosition", "3").put("aminoAcids", "A/V"), TranscriptConsequence.class);
    }
    ObjectNode observation(String transcript, String protein) {
        var raw = mapper.createObjectNode();
        raw.putArray("rows").addObject().put("transcriptId", transcript).put("proteinVariant", protein).put("rawScore", "0.9876");
        return raw;
    }
    @Test void existingAlleleWithDifferentVersionIsNotReportedAsAbsentAndCannotLoseVersion() throws Exception {
        var raw = observation("ENST000001.9", "A3V"); var before = raw.deepCopy();
        var result = PredictionObservationAssociation.alpha(mapper, raw, tx(), null);
        assertEquals("IDENTITY_UNRESOLVED", result.path("status").asText());
        assertEquals("ALPHAMISSENSE_TRANSCRIPT_VERSION_MISMATCH", result.path("reasonCode").asText());
        assertFalse(result.has("rawScore")); assertEquals(before, raw);
        ((ObjectNode) raw.at("/rows/0")).put("transcriptId", "ENST000001");
        assertEquals("IDENTITY_UNRESOLVED", PredictionObservationAssociation.alpha(mapper, raw, tx(), null).path("status").asText());
    }
    @Test void exactTranscriptWrongProteinHasItsOwnReason() throws Exception {
        var result = PredictionObservationAssociation.alpha(mapper, observation("ENST000001.10", "A4V"), tx(), null);
        assertEquals("ALPHAMISSENSE_PROTEIN_IDENTITY_MISMATCH", result.path("reasonCode").asText());
        assertFalse(result.has("rawScore"));
    }
    @Test void emptyRowsAndDifferentAccessionsRemainDistinct() throws Exception {
        var empty = mapper.createObjectNode(); empty.putArray("rows");
        assertEquals("ALPHAMISSENSE_NO_MATCHING_RECORD", PredictionObservationAssociation.alpha(mapper, empty, tx(), null).path("reasonCode").asText());
        assertEquals("TRANSCRIPT_PROTEIN_IDENTITY_UNRESOLVED", PredictionObservationAssociation.alpha(mapper,
                observation("ENST000002.10", "A3V"), tx(), null).path("reasonCode").asText());
    }
    @Test void exactMatchAndConflictingScoresRetainOriginalRules() throws Exception {
        var raw = observation("ENST000001.10", "A3V");
        var result = PredictionObservationAssociation.alpha(mapper, raw, tx(), null);
        assertEquals("ASSOCIATED", result.path("status").asText()); assertEquals("0.9876", result.path("rawScore").asText());
        raw.withArray("rows").add(raw.at("/rows/0").deepCopy());
        ((ObjectNode) raw.at("/rows/1")).put("rawScore", "0.1");
        assertEquals("AMBIGUOUS_RECORD", PredictionObservationAssociation.alpha(mapper, raw, tx(), null).path("status").asText());
    }
    @Test void approvedFullSequenceBridgeStillWorksButLabelAloneDoesNot() throws Exception {
        var raw = observation("ENST000001.9", "A3V");
        var bridge = mapper.createObjectNode().put("status", "VERIFIED_SEQUENCE_EQUIVALENCE")
                .put("sourceTranscriptId", "ENST000001.9").put("targetTranscriptId", "ENST000001.10");
        assertEquals("IDENTITY_UNRESOLVED", PredictionObservationAssociation.alpha(mapper, raw, tx(), bridge).path("status").asText());
        bridge.put("sourceProvenanceSha256", "a".repeat(64)).put("sourceCds", "ATGGCTGCTTAA").put("targetCds", "ATGGCTGCTTAA")
                .put("cdsCanonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mapper.getNodeFactory().textNode("ATGGCTGCTTAA")));
        assertEquals("ASSOCIATED", PredictionObservationAssociation.alpha(mapper, raw, tx(), bridge).path("status").asText());
    }
    @Test void explanatoryPublicProjectionDoesNotExposeOriginalRowsOrGrantCalibration() {
        var raw = mapper.createObjectNode(); raw.putObject("plan").put("route", "EXISTING_DEVELOPMENT_CORE");
        raw.withObject("/plan").putObject("developmentPreparation").put("model", "ALPHAMISSENSE");
        raw.putObject("mechanisms").putObject("PROTEIN_MISSENSE").put("status", "NOT_EVALUATED")
                .put("reasonCode", "ALPHAMISSENSE_TRANSCRIPT_VERSION_MISMATCH");
        var summary = PredictionMechanismSummary.publicView(mapper, PredictionMechanismSummary.fromResult(mapper, raw));
        assertTrue(summary.at("/mechanisms/0/reason").asText().contains("有该等位基因记录"));
        assertEquals("NOT_EVALUATED", summary.at("/mechanisms/0/status").asText());
        assertEquals("NONE", summary.at("/mechanisms/0/appliedStrength").asText());
        assertFalse(summary.toString().contains("sourceRow"));
    }
}
