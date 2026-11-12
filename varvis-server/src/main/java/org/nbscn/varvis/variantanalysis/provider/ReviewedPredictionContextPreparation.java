package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Confirms a persisted VEP context, without claiming that any predictor or policy is applicable. */
public final class ReviewedPredictionContextPreparation {
    public static final String VERSION = "1.0.0";
    public static final String PATH = "REVIEWED_PREDICTION_CONTEXT";
    private final ObjectNode identity;
    private final String selectionPolicy;

    public ReviewedPredictionContextPreparation(ObjectMapper mapper, JsonNode binding) {
        if (binding == null || !"reviewed-prediction-business-binding-v1".equals(binding.path("schemaVersion").asText())
                || !Set.of("CONFIGURED", "UNAVAILABLE").contains(binding.path("status").asText())
                || binding.path("automaticTrigger").asBoolean(true) || binding.path("classificationEnabled").asBoolean(true)
                || binding.path("adoptionSupported").asBoolean(true))
            throw new IllegalArgumentException("PREDICTION_CONTEXT_BINDING_INVALID");
        identity = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-context-preparation-v1")
                .put("version", VERSION).put("mode", "EXPLICIT_PERSISTED_VEP116_TRANSCRIPT_CONFIRMATION")
                .put("automaticTrigger", false).put("classificationEnabled", false)
                .put("criterionPolicySelected", false).put("predictionCompatibilityClaimed", false)
                .put("scoreLookups", 0);
        identity.set("predictionBusinessBinding", binding.deepCopy());
        selectionPolicy = PATH + "@" + VERSION + ":" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(identity);
    }

    public ObjectNode identity() { return identity.deepCopy(); }
    public String selectionPolicy() { return selectionPolicy; }
    public boolean accepts(String value) { return selectionPolicy.equals(value); }
    public static boolean requested(String value) { return value != null && value.startsWith(PATH + "@"); }
    public static boolean eligibleAnnotation(VariantInterpretation candidate, TranscriptConsequence annotation) {
        return ReviewedRuleContextPreparation.eligibleAnnotation(candidate, annotation);
    }
}
