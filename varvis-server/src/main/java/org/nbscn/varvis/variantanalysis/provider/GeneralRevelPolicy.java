package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;

/** Project-local adoption of Pejaver 2022 Table 2, constrained by Table 4. No clinical approval. */
public final class GeneralRevelPolicy {
    public static final String VERSION = "1.0.0";
    public static final String BRIDGE = "TECHNICAL_ACCESSION_WITH_CURRENT_CDS_AND_VEP_CHECK";
    private GeneralRevelPolicy() { }

    public static ObjectNode load(ObjectMapper mapper) {
        var policy = PredictionDevelopmentRuntime.load(mapper, "prediction-general-revel-v1.json");
        if (!VERSION.equals(policy.path("version").asText()) || !"REVEL".equals(policy.path("model").asText())
                || !"PROJECT_LOCAL_RESEARCH".equals(policy.path("adoption").asText()))
            throw new IllegalArgumentException("GENERAL_REVEL_POLICY_INVALID");
        String[] cuts = {"0", "0.003", "0.016", "0.183", "0.290", "0.644", "0.773", "0.932", "1"};
        String[] strengths = {"STRONG", "STRONG", "MODERATE", "SUPPORTING", "NONE", "SUPPORTING", "MODERATE", "STRONG"};
        var bands = policy.path("bands");
        if (!bands.isArray() || bands.size() != 8) throw new IllegalArgumentException("GENERAL_REVEL_BANDS_INVALID");
        for (int i = 0; i < 8; i++) {
            var b = bands.get(i);
            if (!cuts[i].equals(b.path("lower").asText()) || !cuts[i + 1].equals(b.path("upper").asText())
                    || !b.path("lowerInclusive").isBoolean() || !b.path("upperInclusive").isBoolean()
                    || b.path("lowerInclusive").asBoolean() != (i == 0 || i >= 5)
                    || b.path("upperInclusive").asBoolean() != (i < 4 || i == 7)
                    || !strengths[i].equals(b.path("calibratedStrength").asText())
                    || !(i < 4 ? "BENIGN" : i == 4 ? "UNINFORMATIVE" : "HARMFUL").equals(b.path("direction").asText()))
                throw new IllegalArgumentException("GENERAL_REVEL_BANDS_INVALID");
        }
        return policy;
    }

    static ObjectNode calibrate(ObjectMapper mapper, ObjectNode policy, String score) {
        var result = mapper.createObjectNode().put("operation", "PROJECT_LOCAL_REVEL_CALIBRATION")
                .put("rawScore", score).put("appliedStrength", "NONE").put("calibratedStrength", "NONE")
                .put("calibrationId", policy.path("policyId").asText()).put("calibrationVersion", VERSION)
                .put("clinicalAcceptance", false).put("automaticClassification", false);
        result.set("source", policy.path("source").deepCopy());
        result.set("combinationConstraints", policy.path("combinationConstraints").deepCopy());
        BigDecimal value = PredictionCalibration.decimal(score);
        if (value == null) return PredictionCalibration.refused(result, "REVEL_SCORE_MISSING_OR_INVALID");
        PredictionCalibration.compareStrengthIntervals(policy, value, result);
        result.put("appliedStrength", result.path("calibratedStrength").asText("NONE"));
        return result;
    }

    /** Local supported specializations are checked before acquisition, including unsupported entries. */
    static String specialIssue(ObjectMapper mapper, JsonNode context) {
        for (var spec : PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json").path("specializations")) {
            if (spec.path("hgncId").equals(context.at("/gene/hgncId"))
                    && spec.path("mondoId").equals(context.at("/disease/mondoId")) && spec.path("moi").equals(context.path("moi")))
                return "GENERAL_REVEL_SPECIALIZED_POLICY_REQUIRED";
        }
        return null;
    }

    static boolean sameResource(JsonNode expected, JsonNode actual) {
        for (String key : List.of("resourceId", "resourceVersion", "identityFingerprintSha256", "sourceArtifactSha256"))
            if (!expected.path(key).equals(actual.path(key)) || expected.path(key).asText().isBlank()) return false;
        return true;
    }
}
