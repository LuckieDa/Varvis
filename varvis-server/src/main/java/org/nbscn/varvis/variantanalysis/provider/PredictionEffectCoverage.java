package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Compares supplied coverage only; never collects evidence or assumes global absence. */
final class PredictionEffectCoverage {
    private PredictionEffectCoverage() { }

    static String issue(JsonNode snapshot, JsonNode requirements, JsonNode candidate, JsonNode hypothesis, JsonNode task) {
        if (!"COMPLETE_FOR_DECLARED_SCOPE".equals(snapshot.path("status").asText())
                || !SavedPredictionApplicationInputs.nonblank(snapshot, "snapshotId", "version", "reviewId")
                || !snapshot.path("externalWorldCovered").isBoolean() || snapshot.path("externalWorldCovered").asBoolean()
                || !snapshot.path("effects").isArray()) return "EFFECT_COVERAGE_INCOMPLETE";
        if (!candidate.path("candidateId").equals(snapshot.path("candidateId"))
                || !hypothesis.path("hypothesisId").equals(snapshot.path("hypothesisId"))
                || !hypothesis.at("/context/contextId").equals(snapshot.path("contextId"))) return "EFFECT_SCOPE_MISMATCH";
        if (!"VERIFIED".equals(snapshot.path("equivalenceScope").asText())) return "EFFECT_EQUIVALENCE_SCOPE_UNKNOWN";
        for (JsonNode required : requirements) {
            if (!required.isTextual() || !"COMPLETE".equals(snapshot.path("coverage").path(required.asText()).asText()))
                return "EFFECT_COVERAGE_INCOMPLETE";
        }
        try {
            Instant asOf = Instant.parse(task.path("assessmentAt").asText());
            if (asOf.isBefore(Instant.parse(snapshot.path("validFrom").asText()))
                    || asOf.isAfter(Instant.parse(snapshot.path("validThrough").asText()))) return "EFFECT_SNAPSHOT_STALE";
        } catch (DateTimeParseException missing) { return "EFFECT_FRESHNESS_UNVERIFIED"; }
        for (JsonNode effect : snapshot.path("effects")) {
            String type = effect.path("kind").asText();
            if ("EXTERNAL_CLASSIFICATION_DISCORDANCE".equals(type)) continue;
            if ("IDENTITY_CONFLICT".equals(type)) return "EFFECT_IDENTITY_CONFLICT";
            if ("INTERNAL_EVIDENCE_CONFLICT".equals(type)) return "INTERNAL_EVIDENCE_CONFLICT";
            if (!"SAME_EFFECT".equals(type) || !SavedPredictionApplicationInputs.nonblank(effect, "referenceId", "sourceSha256")
                    || !"VERIFIED".equals(effect.path("equivalence").asText())) return "EFFECT_EQUIVALENCE_SCOPE_UNKNOWN";
            if ("ADOPTED".equals(effect.path("adoption").asText())) return "SAME_EFFECT_ALREADY_ADOPTED";
            if (!"OBSERVED_ONLY".equals(effect.path("adoption").asText())) return "EFFECT_ADOPTION_STATE_UNKNOWN";
        }
        return "";
    }
}
