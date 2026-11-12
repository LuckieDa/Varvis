package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Explicit project-local use of the existing standard-strength projection; the offline contract remains immutable. */
public final class GeneralAlphaMissensePolicy {
    public static final String VERSION = "1.0.0";
    private GeneralAlphaMissensePolicy() { }
    public static ObjectNode load(ObjectMapper mapper) {
        var standard = PredictionDevelopmentRuntime.load(mapper, "prediction-standard-offline/am-standard-1.0.0.json");
        if (PredictionCalibration.standardIssue(standard) != null) throw new IllegalArgumentException("AM_STANDARD_CONTRACT_INVALID");
        var policy = mapper.createObjectNode().put("policyId", "BERGQUIST_2025_AM_STANDARD_LOCAL").put("version", VERSION)
                .put("model", "ALPHAMISSENSE").put("adoption", "PROJECT_LOCAL_RESEARCH").put("clinicalAcceptance", false)
                .put("aggregation", "EXACT_SINGLE_MAJOR_TRANSCRIPT_ROW_NO_MAX_OR_VOTE")
                .put("specSearchScope", "LOCAL_SUPPORTED_CSPEC_AND_CONFIGURED_REVIEWED_POLICY_ONLY")
                .put("standardContractSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(standard));
        policy.set("bands", standard.path("bands").deepCopy()); policy.set("source", standard.path("sources").deepCopy());
        policy.set("sourcePointDiscrepancy", standard.path("discrepancy").deepCopy());
        policy.putObject("combinationConstraints").put("proteinOnly", true).put("completeBp4RequiresSplicing", true)
                .put("maximumPp3Strength", "STRONG").put("maximumBp4Strength", "MODERATE")
                .put("automaticCombinationAllowed", false).put("sameEffectAdoptionCheckRequired", true);
        return policy;
    }
    static boolean accepts(JsonNode resource) {
        return "ALPHAMISSENSE".equals(resource.path("model").asText()) && "2023-08-03".equals(resource.path("dataVersion").asText())
                && "am_pathogenicity".equals(resource.path("scoreField").asText())
                && "ORIGINAL_DECIMAL_STRING".equals(resource.path("scorePrecision").asText())
                && "https://storage.googleapis.com/dm_alphamissense/AlphaMissense_hg38.tsv.gz".equals(resource.path("sourceLocator").asText())
                && "9657246d5ed328ea13e28432130847cd11d2662657ed8ec56a65c08e6984c4d3".equals(resource.path("sha256").asText())
                && "9340e53c57b8a14a92e5d6d14d786714f65447d64a1c536b11adc2dd29dae58c".equals(resource.path("indexSha256").asText());
    }
    static ObjectNode calibrate(ObjectMapper mapper, ObjectNode policy, String score) {
        var out = mapper.createObjectNode().put("operation", "PROJECT_LOCAL_ALPHAMISSENSE_CALIBRATION")
                .put("rawScore", score).put("appliedStrength", "NONE").put("calibratedStrength", "NONE")
                .put("calibrationId", policy.path("policyId").asText()).put("calibrationVersion", VERSION)
                .put("clinicalAcceptance", false).put("automaticClassification", false);
        var value = PredictionCalibration.decimal(score);
        if (value == null) return PredictionCalibration.refused(out, "ALPHAMISSENSE_SCORE_MISSING_OR_INVALID");
        PredictionCalibration.compareStrengthIntervals(policy, value, out);
        out.put("appliedStrength", out.path("calibratedStrength").asText("NONE"));
        return out;
    }
}
