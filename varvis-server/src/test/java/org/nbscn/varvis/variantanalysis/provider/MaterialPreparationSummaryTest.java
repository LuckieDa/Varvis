package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialPreparationSummaryTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void capturedButStaleDataNeverBecomesQualifiedOrPositive() throws Exception {
        var module = json.createObjectNode().put("status", "PREPARED");
        var snapshot = module.putObject("sourceSnapshot"); snapshot.putArray("documents").addObject();
        snapshot.putArray("excludedTasks").addObject().put("reasonCode", "PP1_TASK_VERSION_STALE").put("privatePath", "secret");
        var criterion = json.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "PP1_LITERATURE_REQUIREMENTS_UNRESOLVED");
        var out = MaterialPreparationSummary.build(json, criterion, json.createObjectNode(), module, "pp1", "/modules/pp1/results/0");
        assertEquals("UNRESOLVED", out.path("status").asText());
        assertEquals(1, out.path("capturedDocuments").asInt());
        assertFalse(out.toString().contains("secret"));
        assertFalse(out.has("appliedStrength"));
        assertEquals(out, json.readTree(json.writeValueAsBytes(out)));
    }
    @Test void skippedAcquisitionAndEvaluatedNegativeRemainDifferent() {
        var criterion = json.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "ROUTING_MISSENSE_REQUIRED");
        var dispatch = json.createObjectNode().put("skipSpecializedAcquisition", true);
        assertEquals("NOT_REQUIRED", MaterialPreparationSummary.build(json, criterion, dispatch, json.createObjectNode(), "pp2", "/modules/pp2").path("status").asText());
        criterion.put("status", "NOT_MET"); dispatch.put("skipSpecializedAcquisition", false);
        assertEquals("EVALUATED", MaterialPreparationSummary.build(json, criterion, dispatch, json.createObjectNode(), "pp2", "/modules/pp2").path("status").asText());
    }
    @Test void publicSummaryDoesNotLeakUnstructuredFailureText() {
        var criterion = json.createObjectNode().put("reasonCode", "C:/private/config/key");
        var module = json.createObjectNode().put("status", "secret error");
        var out = MaterialPreparationSummary.build(json, criterion, json.createObjectNode(), module, "pp1", "/modules/pp1");
        assertEquals("LOCAL_BATCH_SOURCE_REASON_REDACTED", out.path("reasonCode").asText());
        assertFalse(out.toString().contains("private")); assertFalse(out.toString().contains("secret"));
    }
    @Test void evaluatorInapplicabilityIsResolvedWithoutClaimingAcquisitionWasSkipped() throws Exception {
        var c = json.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("status", "NOT_EVALUATED")
                .put("appliedStrength", "NONE").put("reasonCode", "PVS1_CONSEQUENCE_NOT_APPLICABLE");
        var out = MaterialPreparationSummary.build(json, c, json.createObjectNode(), json.createObjectNode(), "pvs1", "/modules/pvs1/results/0");
        assertEquals("NOT_APPLICABLE", out.path("status").asText());
        assertEquals(out, json.readTree(json.writeValueAsBytes(out)));
        c.put("reasonCode", "PVS1_MECHANISM_SOURCE_NOT_FOUND");
        assertEquals("UNRESOLVED", MaterialPreparationSummary.build(json, c, json.createObjectNode(), json.createObjectNode(), "pvs1", "/modules/pvs1").path("status").asText());
    }
}
