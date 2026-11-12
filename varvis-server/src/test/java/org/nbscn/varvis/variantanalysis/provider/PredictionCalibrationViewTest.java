package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PredictionCalibrationViewTest {
    @Test void schemaNotBusinessProfileSelectsReadOnlyStrength() throws Exception {
        var f = new PredictionEligibilityFixtures(); ObjectNode standard = f.standard("BENIGN");
        standard.put("businessProfile", "v1"); ObjectNode before = standard.deepCopy();
        var view = PredictionCalibrationView.read(standard);
        assertEquals("", view.issue()); assertEquals("SUPPORTING", view.sourceStrength()); assertEquals(before, standard);
        ObjectNode legacy = f.mapper.createObjectNode().put("schemaVersion", "prediction-calibration-result-v1")
                .put("status", "CALIBRATED").put("direction", "HARMFUL").put("appliedStrength", "MODERATE").put("businessProfile", "v3");
        legacy.putObject("calibration").put("schemaVersion", "prediction-calibration-v1"); before = legacy.deepCopy();
        view = PredictionCalibrationView.read(legacy); assertEquals("", view.issue()); assertEquals("MODERATE", view.sourceStrength());
        assertEquals(before, legacy);
    }

    @ParameterizedTest @ValueSource(strings = {"schema", "missing", "none", "gray", "unknown", "reference", "status"})
    void malformedCalibrationRemainsReadableButDoesNotYieldTrustedStrength(String mode) throws Exception {
        var f = new PredictionEligibilityFixtures(); ObjectNode saved = f.standard("BENIGN");
        switch (mode) {
            case "schema" -> saved.put("schemaVersion", "unknown-v3");
            case "missing" -> saved.remove("calibratedStrength");
            case "none" -> saved.put("calibratedStrength", "NONE");
            case "gray" -> saved.put("direction", "UNINFORMATIVE");
            case "unknown" -> saved.put("calibratedStrength", "VERY_STRONG");
            case "reference" -> saved.remove("calibrationId");
            case "status" -> saved.put("status", "NOT_EVALUATED");
            default -> fail();
        }
        ObjectNode before = saved.deepCopy(); var view = PredictionCalibrationView.read(saved);
        assertFalse(view.issue().isBlank()); assertEquals("NONE", view.sourceStrength()); assertEquals(before, saved);
        f.prediction(0).set("calibration", saved); f.seal();
        var result = f.result(0, "BP4"); assertNotEquals("PRECONDITIONS_PASS", result.path("status").asText());
        assertEquals("NONE", result.path("proposedApplicationStrength").asText());
    }

    @Test void missingExplicitStrengthMappingDoesNotClampOrDefault() throws Exception {
        var f = new PredictionEligibilityFixtures(); ((ObjectNode) f.prediction(0).path("calibration")).put("calibratedStrength", "MODERATE"); f.seal();
        assertEquals("STRENGTH_MAPPING_MISSING", f.result(0, "BP4").path("reasonCode").asText());
        assertEquals("MODERATE", f.result(0, "BP4").at("/sourceCalibratedStrength/PROTEIN").asText());
        assertEquals("NONE", f.result(0, "BP4").path("proposedApplicationStrength").asText());
    }
}
