package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Internal read-only schema adapter. Never compares a score or authorizes adoption. */
final class PredictionCalibrationView {
    private PredictionCalibrationView() { }

    record View(String schema, String direction, String sourceStrength, String issue) { }

    static View read(JsonNode saved) {
        String schema = saved.path("schemaVersion").asText();
        boolean standard = "prediction-standard-calibration-result-v1".equals(schema);
        if (!standard && !"prediction-calibration-result-v1".equals(schema))
            return invalid(schema, "CALIBRATION_SCHEMA_UNSUPPORTED");
        if (!"CALIBRATED".equals(saved.path("status").asText()))
            return invalid(schema, "SAVED_CALIBRATION_NOT_COMPLETE");
        String direction = saved.path("direction").asText();
        String strength = saved.path(standard ? "calibratedStrength" : "appliedStrength").asText();
        if (!("UNINFORMATIVE".equals(direction) && "NONE".equals(strength)
                || Set.of("HARMFUL", "BENIGN").contains(direction)
                && Set.of("SUPPORTING", "MODERATE", "STRONG").contains(strength)))
            return invalid(schema, "CALIBRATION_DIRECTION_STRENGTH_INVALID");
        if (standard) {
            if (saved.path("calibrationId").asText().isBlank() || saved.path("calibrationVersion").asText().isBlank()
                    || !"NONE".equals(saved.path("appliedStrength").asText())
                    || !"NOT_EVALUATED".equals(saved.path("applicationStatus").asText())
                    || !saved.path("sources").isObject()) return invalid(schema, "CALIBRATION_CONTRACT_REFERENCE_MISSING");
            if (saved.has("band") && (!saved.at("/band/direction").equals(saved.path("direction"))
                    || !saved.at("/band/calibratedStrength").equals(saved.path("calibratedStrength"))))
                return invalid(schema, "CALIBRATION_BAND_LABEL_MISMATCH");
        } else if (!"prediction-calibration-v1".equals(saved.at("/calibration/schemaVersion").asText())) {
            return invalid(schema, "CALIBRATION_CONTRACT_REFERENCE_MISSING");
        }
        return new View(schema, direction, strength, "");
    }

    private static View invalid(String schema, String reason) {
        return new View(schema, "UNKNOWN", "NONE", reason);
    }
}
