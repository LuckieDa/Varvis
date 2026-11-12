package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** All rows are retained; an exact selected identity is required before calibration. */
public final class PredictionObservationAssociation {
    public static final String VERSION = "prediction-observation-association-v1";
    private PredictionObservationAssociation() { }
    public static ObjectNode alpha(ObjectMapper mapper, JsonNode observation, TranscriptConsequence transcript, JsonNode bridge) {
        var out = mapper.createObjectNode().put("status", "IDENTITY_UNRESOLVED");
        var matches = out.putArray("matchedRowIndices");
        if (!observation.path("rows").isArray()) return out.put("reasonCode", "PREDICTION_ROWS_MISSING");
        int index = 0; String score = null;
        boolean versionConflict = false, proteinConflict = false;
        String selected = transcript.getVersionedTranscriptId();
        for (var row : observation.path("rows")) {
            String source = row.path("transcriptId").asText();
            boolean sameTranscript = source.equals(selected);
            if (!sameTranscript && selected != null && selected.matches("ENST[0-9]+\\.[1-9][0-9]*")
                    && source.matches("ENST[0-9]+\\.[1-9][0-9]*")
                    && source.substring(0, source.indexOf('.')).equals(selected.substring(0, selected.indexOf('.'))))
                versionConflict = true;
            if (sameTranscript && !row.path("proteinVariant").asText().equals(transcript.getAminoAcidChange())) proteinConflict = true;
            boolean exact = sameTranscript;
            if (!exact) exact = bridgeMatches(row, transcript, bridge, mapper);
            if (exact && row.path("proteinVariant").asText().equals(transcript.getAminoAcidChange())) {
                matches.add(index); String current = row.path("rawScore").asText();
                if (score != null && !score.equals(current)) return out.put("status", "AMBIGUOUS_RECORD").put("reasonCode", "SAME_IDENTITY_SCORE_CONFLICT");
                score = current;
            }
            index++;
        }
        if (score == null) return out.put("reasonCode", observation.path("rows").isEmpty() ? "ALPHAMISSENSE_NO_MATCHING_RECORD"
                : proteinConflict ? "ALPHAMISSENSE_PROTEIN_IDENTITY_MISMATCH"
                : versionConflict ? "ALPHAMISSENSE_TRANSCRIPT_VERSION_MISMATCH" : "TRANSCRIPT_PROTEIN_IDENTITY_UNRESOLVED");
        return out.put("status", "ASSOCIATED").put("rawScore", score).put("reasonCode", "EXACT_OR_VERIFIED_SEQUENCE_BRIDGE");
    }
    private static boolean bridgeMatches(JsonNode row, TranscriptConsequence tx, JsonNode bridge, ObjectMapper mapper) {
        if (bridge == null || !"VERIFIED_SEQUENCE_EQUIVALENCE".equals(bridge.path("status").asText())
                || !row.path("transcriptId").equals(bridge.path("sourceTranscriptId"))
                || !bridge.path("targetTranscriptId").asText().equals(tx.getVersionedTranscriptId())
                || !bridge.path("sourceProvenanceSha256").asText().matches("[0-9a-f]{64}")) return false;
        String source = bridge.path("sourceCds").asText(), target = bridge.path("targetCds").asText();
        // These are trusted resource inputs, never request-provided bridge witnesses. Removing accession
        // versions alone is insufficient. Equal full CDS establishes equal protein sequence on either strand.
        return source.length() >= 3 && source.length() % 3 == 0 && source.matches("[ACGT]+") && source.equals(target)
                && new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mapper.getNodeFactory().textNode(source)).equals(bridge.path("cdsCanonicalSha256").asText());
    }
    public static ObjectNode spliceEvents(ObjectMapper mapper, JsonNode record, long position) {
        var out = mapper.createObjectNode().put("status", "PARSED"); var events = out.putArray("events");
        java.math.BigDecimal max = java.math.BigDecimal.ZERO;
        for (String kind : new String[]{"AG", "AL", "DG", "DL"}) {
            String raw = record.path("DS_" + kind).asText(); var score = PredictionCalibration.decimal(raw);
            var delta = record.path("DP_" + kind);
            if (score == null || !delta.isIntegralNumber() || !delta.canConvertToLong()) return out.put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_EVENT_FIELDS_INCOMPLETE");
            long eventPosition;
            try { eventPosition = Math.addExact(position, delta.asLong()); } catch (ArithmeticException invalid) { return out.put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_EVENT_POSITION_INVALID"); }
            if (eventPosition < 1) return out.put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_EVENT_POSITION_INVALID");
            events.addObject().put("kind", kind).put("rawScore", raw).put("deltaPosition", delta.asLong()).put("genomicPosition", eventPosition)
                    .put("geneSymbol", record.path("geneSymbol").asText()).put("transcriptId", record.path("transcriptId").asText());
            max = max.max(score);
        }
        return out.put("rawScore", max.toPlainString()).put("positionSemantics", "GENOMIC_OFFSET_NO_SECOND_STRAND_FLIP");
    }
}
