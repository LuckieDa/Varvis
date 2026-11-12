package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Self-owned synthetic snapshots: consistent hashes must not hide cross-source splices. */
class PredictionEligibilitySourceBindingTest {
    @Test void completeSourceChainRetainsPositiveAndOppositeDirection() throws Exception {
        var f = new PredictionEligibilityFixtures();
        assertEquals("PRECONDITIONS_PASS", f.result(0, "BP4").path("status").asText());
        assertEquals("NO_SUPPORT", f.result(0, "PP3").path("status").asText());
        assertNotEquals(f.prediction(0).path("resultId"), f.prediction(0).at("/calibration/savedSource/childResultId"));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "candidate", "result", "observation", "null", "object", "extra"})
    void requestReferencesBindActualParent(String kind) throws Exception {
        var f = new PredictionEligibilityFixtures(); f.refreshSourceLinks = false;
        ObjectNode ref = f.docs.get("request").withObject("/declaration/calibration");
        ObjectNode first = (ObjectNode) ref.path("results").get(0);
        switch (kind) {
            case "missing" -> ref.remove("results");
            case "duplicate" -> ref.withArray("results").add(first.deepCopy());
            case "extra" -> ref.withArray("results").addObject().put("candidateId", "another").put("sourceResultId", "another").put("sourceObservationSha256", "another");
            case "candidate" -> first.put("candidateId", "another");
            case "result" -> first.set("sourceResultId", ref.path("results").get(1).path("sourceResultId"));
            case "observation" -> first.set("sourceObservationSha256", ref.path("results").get(1).path("sourceObservationSha256"));
            case "null" -> ref.putNull("results");
            case "object" -> ref.putObject("results");
            default -> fail();
        }
        f.docs.get("private").set("declaration", f.docs.get("request").path("declaration").deepCopy());
        f.seal(); rejected(f.assess().size() == 1 ? f.assess().get(0).json() : f.result(0, "BP4"));
    }

    @ParameterizedTest @ValueSource(strings = {"analysisId", "candidateId", "childRunId", "childResultId", "parentRunId", "observationSha256", "sourceRowSha256", "selection", "associationInput", "resource", "missing"})
    void calibrationCannotBorrowSameScoreAndContract(String field) throws Exception {
        var f = new PredictionEligibilityFixtures(); f.refreshSourceLinks = false;
        ObjectNode source = (ObjectNode) f.prediction(0).at("/calibration/savedSource");
        if (field.equals("missing")) ((ObjectNode) f.prediction(0).path("calibration")).remove("savedSource");
        else source.set(field, f.prediction(1).at("/calibration/savedSource/" + field));
        // Run/analysis/ancestor are shared in this batch: use a distinct synthetic identity.
        if (List.of("analysisId", "childRunId", "parentRunId").contains(field)) source.put(field, "different-source");
        if (field.equals("resource")) source.putObject(field).put("model", "different-model");
        f.seal(); rejected(f.result(0, "BP4"));
        assertFalse(f.result(0, "BP4").has("sourceCalibratedStrength"));
        assertEquals("PRECONDITIONS_PASS", f.result(1, "PP3").path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"object", "null", "element", "settings", "parent", "publication"})
    void malformedCandidateContainersAreStructured(String kind) throws Exception {
        var f = new PredictionEligibilityFixtures();
        switch (kind) {
            case "object" -> f.docs.get("run").putObject("candidates").put("a", 1).put("b", 2);
            case "null" -> f.docs.get("run").putNull("candidates");
            case "element" -> f.docs.get("run").withArray("candidates").set(0, f.mapper.nullNode());
            case "settings" -> f.settings.withArray("candidates").set(0, f.mapper.nullNode());
            case "parent" -> f.docs.get("parent").putObject("candidates");
            case "publication" -> f.docs.get("publication").putNull("candidates");
            default -> fail();
        }
        f.sealBytes(); rejected(assertDoesNotThrow(f::assess).get(0).json());
    }

    @ParameterizedTest @ValueSource(strings = {"missingCompatibility", "unadoptedVersion", "failed", "incomplete", "complete"})
    void fallbackCannotLaunderPrimaryQuery(String kind) throws Exception {
        var f = fallback();
        if (kind.equals("missingCompatibility")) ((ObjectNode) f.template(0).at("/mechanisms/PROTEIN")).put("compatibilityId", "absent");
        ObjectNode observation = (ObjectNode) f.prediction(0).path("observation");
        if (kind.equals("unadoptedVersion")) ((ObjectNode) observation.path("resource")).put("dataVersion", "unadopted");
        if (kind.equals("failed")) observation.withObject("/execution").put("status", "FAILED");
        if (kind.equals("incomplete")) observation.remove("execution");
        syncObservation(f, observation); f.seal();
        ObjectNode receipt = f.result(0, "PP3"); rejected(receipt);
        assertTrue(receipt.path("mechanisms").has("PROTEIN"));
        assertEquals("HARMFUL", receipt.at("/mechanisms/SPLICE/state").asText());
        if (!List.of("failed", "incomplete").contains(kind)) {
            assertEquals("UNSUPPORTED", receipt.path("status").asText());
            assertEquals("PRIMARY_NO_RECORD_PROVENANCE_UNSUPPORTED", receipt.path("reasonCode").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"inputArtifacts", "compatibilityEvidenceSha256", "localAdoption", "requestAdoption", "requestContract"})
    void savedAdoptionAndArchiveReferencesCannotBeSelfRebound(String field) throws Exception {
        var f = new PredictionEligibilityFixtures(); f.refreshSourceLinks = false;
        ObjectNode source = (ObjectNode) f.prediction(0).at("/calibration/savedSource");
        switch (field) {
            case "inputArtifacts" -> source.withObject("/inputArtifacts").put("child", "0".repeat(64));
            case "compatibilityEvidenceSha256" -> source.put(field, "0".repeat(64));
            case "localAdoption" -> source.withObject("/localAdoption").put("reviewId", "other");
            case "requestAdoption" -> f.docs.get("request").withObject("/declaration/calibration").put("adoptionSha256", "0".repeat(64));
            case "requestContract" -> f.docs.get("request").withObject("/declaration/calibration").put("calibrationCanonicalSha256", "0".repeat(64));
            default -> fail();
        }
        f.docs.get("private").set("declaration", f.docs.get("request").path("declaration").deepCopy());
        f.seal(); rejected(f.result(0, "BP4"));
        assertFalse(f.result(0, "BP4").path("sourceCalibratedStrength").has("PROTEIN"));
    }

    @Test void associationDeclarationsAreExplicitlyUnsupportedNotMisreadAsCalibration() throws Exception {
        var f = new PredictionEligibilityFixtures(); f.settings.put("requestSourcePointer", "/declaration/association");
        f.docs.get("request").withObject("/declaration/association").putArray("selections"); f.seal();
        JsonNode result = f.assess().get(0).json();
        assertEquals("ASSOCIATION_REQUEST_SOURCE_UNSUPPORTED", result.path("reasonCode").asText());
        assertEquals("UNSUPPORTED", result.path("status").asText());
    }

    @Test void settingsCanonicalFailuresAndNullAreStructuredBeforeAssessment() throws Exception {
        var f = new PredictionEligibilityFixtures(); f.settings.put("unsupportedFloatingPoint", 0.5);
        JsonNode result = assertDoesNotThrow(f::assess).get(0).json(); rejected(result);
        assertEquals("SETTINGS_CANONICAL_FORMAT_INVALID", result.path("reasonCode").asText());
        var input = SavedPredictionApplicationInputs.verify(f.mapper, f.artifacts, null, f.roots);
        assertEquals("SETTINGS_OBJECT_MISSING", input.issue());
    }

    @Test void absentAndUnusedCandidateReferencesAreRejectedWithoutReplacingOtherCandidate() throws Exception {
        var f = new PredictionEligibilityFixtures(); f.refreshSourceLinks = false;
        f.docs.get("request").withObject("/declaration/calibration").withArray("results").remove(0);
        f.docs.get("private").set("declaration", f.docs.get("request").path("declaration").deepCopy()); f.seal();
        assertEquals("CALIBRATION_REQUEST_CANDIDATE_MISSING", f.assess().get(0).json().path("reasonCode").asText());
        f = new PredictionEligibilityFixtures(); f.refreshSourceLinks = false;
        f.docs.get("request").withObject("/declaration/calibration").withArray("results").addObject()
                .put("candidateId", "TEST_ONLY_0").put("sourceResultId", "unselected-result").put("sourceObservationSha256", "unselected-observation");
        f.docs.get("private").set("declaration", f.docs.get("request").path("declaration").deepCopy()); f.seal();
        assertEquals("CALIBRATION_REQUEST_UNUSED_RESULT", f.result(0, "BP4").path("reasonCode").asText());
        assertEquals("PRECONDITIONS_PASS", f.result(1, "PP3").path("status").asText());
    }

    static PredictionEligibilityFixtures fallback() throws Exception {
        var f = new PredictionEligibilityFixtures();
        f.addMechanism(0, "SPLICE", "HARMFUL", f.prediction(0).at("/observation/queryAllele"));
        f.template(0).withObject("/mechanisms").set("SPLICE", f.template(0).at("/mechanisms/PROTEIN").deepCopy());
        for (String code : List.of("PP3", "BP4")) {
            ObjectNode plan = (ObjectNode) f.template(0).at("/criteria/" + code);
            plan.put("mode", code.equals("PP3") ? "MISSING_FALLBACK" : "ALL").put("strengthMechanism", "PROTEIN");
            plan.putArray("mechanisms").add("PROTEIN").add("SPLICE");
        }
        ((ObjectNode) f.template(0).at("/criteria/PP3")).putArray("fallbackReasons").add("NO_RECORD");
        ObjectNode observation = (ObjectNode) f.prediction(0).path("observation");
        observation.put("status", "NO_RECORD").putArray("rows"); observation.putObject("execution").put("status", "SUCCEEDED");
        // A missing query has no fabricated row or calibrated score.
        f.prediction(0).remove("calibration");
        syncObservation(f, observation); f.seal(); return f;
    }

    static void syncObservation(PredictionEligibilityFixtures f, ObjectNode observation) {
        f.digest(observation, "observationSha256", "");
        ((ObjectNode) f.prediction(0).path("selection")).set("sourceObservationSha256", observation.path("observationSha256"));
        ((ObjectNode) f.docs.get("parent").path("candidates").get(0).at("/attempts/0/prediction")).set("observation", observation.deepCopy());
    }

    private static void rejected(JsonNode receipt) {
        assertTrue(List.of("BLOCKED", "UNSUPPORTED").contains(receipt.path("status").asText()), receipt.toPrettyString());
        assertEquals("NONE", receipt.path("appliedStrength").asText());
        assertEquals("NONE", receipt.path("proposedApplicationStrength").asText());
        for (String flag : List.of("applicationReady", "formalEnabled", "criterionEvaluationCreated")) assertFalse(receipt.path(flag).asBoolean());
        assertFalse(receipt.path("reasonCode").asText().isBlank());
    }
}
