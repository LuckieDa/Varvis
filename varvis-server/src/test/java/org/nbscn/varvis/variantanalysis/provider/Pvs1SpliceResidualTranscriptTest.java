package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent residual-RNA conditions; real module/runtime, explicitly synthetic materials. */
class Pvs1SpliceResidualTranscriptTest {
    private final Pvs1SpliceConsequenceTest base = new Pvs1SpliceConsequenceTest();

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void predictionDoesNotRequireAnRnaAssayOrTurnUnknownIntoMeasuredAbsence(int strand) throws Exception {
        for (String residual : List.of("MISSING", "NOT_MEASURED", "UNKNOWN", "NOT_DETECTED_IN_ASSESSED_SCOPE")) {
            var data = base.data(strand, false, true);
            if (residual.equals("MISSING")) data.material().withObject("review").remove("residualNormalTranscript");
            else data.material().withObject("review").put("residualNormalTranscript", residual);
            var result = base.run(data);save("prediction-" + strand + "-" + residual, result);
            assertEquals("MET", base.status(result).path("status").asText(), result::toPrettyString);
            assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
            assertEquals("PREDICTED_CANONICAL", result.at("/resolvedInput/spliceAssessment/evidenceKind").asText());
            assertEquals(data.material(), result.path("spliceConsequenceMaterial"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void predictionCannotOverrideDetectedResidualOrAnUnrecognizedFinding(int strand) throws Exception {
        for (String residual : List.of("PRESENT", "DETECTED_IN_ASSESSED_SCOPE", "UNRECOGNIZED")) {
            var data = base.data(strand, false, true);
            data.material().withObject("review").put("residualNormalTranscript", residual);
            var result = base.run(data);save("conflict-" + strand + "-" + residual, result);
            unresolved(result);
            assertEquals(residual, result.at("/spliceConsequenceMaterial/review/residualNormalTranscript").asText());
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void explicitInvalidResidualTypesCannotBorrowThePredictionExemption(int strand) throws Exception {
        List<JsonNode> invalid = List.of(base.mapper.nullNode(), base.mapper.getNodeFactory().numberNode(0),
                base.mapper.getNodeFactory().booleanNode(false), base.mapper.createArrayNode(), base.mapper.createObjectNode());
        int index = 0;
        for (var value : invalid) {
            var data = base.data(strand, false, true);
            data.material().withObject("review").set("residualNormalTranscript", value);
            var result = base.run(data);save("invalid-" + strand + "-" + index++, result);unresolved(result);
            assertEquals(value, result.at("/spliceConsequenceMaterial/review/residualNormalTranscript"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void observedRnaStillNeedsTheExistingScopedAbsenceFinding(int strand) throws Exception {
        for (String residual : List.of("MISSING", "NOT_MEASURED", "UNKNOWN", "PRESENT", "DETECTED_IN_ASSESSED_SCOPE")) {
            var data = base.data(strand, false, false);
            if (residual.equals("MISSING")) data.material().withObject("review").remove("residualNormalTranscript");
            else data.material().withObject("review").put("residualNormalTranscript", residual);
            unresolved(base.run(data));
        }
        var good = base.run(base.data(strand, false, false));
        assertEquals("MET", base.status(good).path("status").asText(), good::toPrettyString);
    }

    private void unresolved(ObjectNode result) {
        var status = base.status(result);
        assertEquals("NOT_EVALUATED", status.path("status").asText(), result::toPrettyString);
        assertEquals("PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED", status.path("reasonCode").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("NOT_RUN", result.at("/evaluation/deterministicTrace/executionStatus").asText());
        assertNotEquals("MET", result.at("/resolvedInput/spliceAssessment/status").asText());
    }

    private void save(String name, ObjectNode result) throws Exception {
        String directory = System.getProperty("varvis.a2.boundary.output");
        if (directory == null) return;
        Path root = Path.of(directory);Files.createDirectories(root);
        Files.write(root.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),
                StandardOpenOption.CREATE_NEW);
    }
}
