package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.springframework.lang.Nullable;

/** Optional source-bound clause eligibility; it neither reads scores nor changes legacy policy domains. */
final class PredictionConsequenceScope {
    private static final Set<String> TERMS = Set.of("synonymous_variant", "intron_variant", "splice_region_variant");
    private static final Pattern INTRONIC_SNV = Pattern.compile(
            "^([^:]+):(?:c|n)\\.(?:\\*?[1-9][0-9]*|-[1-9][0-9]*)[+-][1-9][0-9]*([ACGT])>([ACGT])$");
    private PredictionConsequenceScope() { }

    @Nullable static String issue(JsonNode policy, TranscriptConsequence transcript, ObjectNode position, ObjectNode trace) {
        trace.set("policy", policy.path("consequenceScope").deepCopy());
        trace.put("selectedConsequence", transcript.getConsequence()).put("vepEvidenceId", position.path("evidenceId").asText());
        String raw = transcript.getConsequence();
        if (raw == null || raw.isBlank()) return refuse(trace, "PREDICTION_SELECTED_CONSEQUENCE_MISSING");
        var terms = new LinkedHashSet<String>();
        for (String part : raw.split("&", -1)) {
            String term = part.trim();
            if (!term.matches("[a-z][a-z0-9_]*") || !terms.add(term))
                return refuse(trace, "PREDICTION_SELECTED_CONSEQUENCE_MALFORMED");
        }
        var observed = trace.putArray("selectedConsequenceTerms"); terms.forEach(observed::add);
        boolean synonymous = terms.contains("synonymous_variant"), intronic = terms.contains("intron_variant");
        if (!TERMS.containsAll(terms) || synonymous == intronic)
            return refuse(trace, "PREDICTION_CONSEQUENCE_OUTSIDE_REVIEWED_SCOPE");
        trace.put("selectedClass", synonymous ? "SYNONYMOUS" : "INTRONIC");
        Integer offset = transcript.getBp7IntronicSignedOffset();
        if (synonymous && offset != null)
            return refuse(trace, "PREDICTION_CONSEQUENCE_POSITION_CLASS_MISMATCH");
        if (policy.has("intronicPositionScope")) {
            trace.set("intronicPositionPolicy", policy.path("intronicPositionScope").deepCopy());
            trace.put("intronicPositionApplicable", intronic);
            if (intronic) {
                // The existing getter parses only exact simple intronic substitution HGVSc.
                // The caller has already bound its version and projected this same stored VEP annotation.
                trace.put("hgvsc", transcript.getHgvsc());
                String hgvsc = transcript.getHgvsc();
                var substitution = hgvsc == null ? null : INTRONIC_SNV.matcher(hgvsc);
                if (offset == null || offset == 0 || substitution == null || !substitution.matches()
                        || substitution.group(2).equals(substitution.group(3))
                        || !substitution.group(1).equals(transcript.getVersionedTranscriptId()))
                    return refuse(trace, "PREDICTION_INTRONIC_POSITION_MISSING_OR_INVALID");
                JsonNode measured = null;
                for (JsonNode value : position.path("measurements")) if ("vep.intronicSignedOffset".equals(value.path("metricId").asText())) {
                    if (measured != null) return refuse(trace, "PREDICTION_INTRONIC_POSITION_AMBIGUOUS");
                    measured = value;
                }
                if (measured == null || !"base-offset".equals(measured.path("unit").asText())
                        || !measured.path("rawValue").isTextual() || !offset.toString().equals(measured.path("rawValue").asText())
                        || measured.has("normalizedValue") && !measured.path("normalizedValue").equals(measured.path("rawValue")))
                    return refuse(trace, "PREDICTION_INTRONIC_POSITION_IDENTITY_MISMATCH");
                trace.put("intronicSignedOffset", offset).put("positionSource", "EXACT_SELECTED_VEP_HGVSC")
                        .put("positionMetricId", "vep.intronicSignedOffset");
                if (Math.abs((long) offset) < 3)
                    return refuse(trace, "PREDICTION_INTRONIC_CANONICAL_SPLICE_POSITION");
            }
        }
        trace.put("status", "ELIGIBLE");
        return null;
    }

    private static String refuse(ObjectNode trace, String reason) {
        trace.put("status", "NOT_EVALUATED").put("reasonCode", reason);
        return reason;
    }
}
