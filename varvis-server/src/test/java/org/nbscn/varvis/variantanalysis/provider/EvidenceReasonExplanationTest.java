package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceReasonExplanationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void qualityFailureIsDataRejectionAndCannotBecomeExecutionFailureOrNegative() throws Exception {
        var e = json.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "PM2_LOCAL_INTERVAL_QC_FAILED").put("appliedStrength", "NONE");
        String before = e.toString();
        var out = EvidenceReasonExplanation.build(json, e);
        assertEquals("QUALITY_REJECTED", out.path("category").asText());
        assertEquals(out, json.readTree(json.writeValueAsBytes(out)));
        assertEquals(before, e.toString());
        assertFalse(out.has("appliedStrength"));
    }

    @Test void scientificResultWinsOverAnyDescriptiveReason() {
        for (var status : new String[] {"MET", "NOT_MET", "UNKNOWN", "FAILED"}) {
            var e = json.createObjectNode().put("status", status).put("reasonCode", "PM2_LOCAL_INTERVAL_QC_FAILED");
            assertNull(EvidenceReasonExplanation.build(json, e));
        }
        assertNull(EvidenceReasonExplanation.build(json, json.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "NEW_UNKNOWN_FAILED")));
    }

    @Test void atomicAndBatchFormsAgreeAndEveryKnownDefinitionHasActionableFields() throws Exception {
        for (var code : EvidenceReasonExplanation.definitions().keySet()) {
            var e = json.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", code);
            var wrapped = json.createObjectNode(); wrapped.set("evaluationResult", e.deepCopy());
            var d = EvidenceReasonExplanation.build(json, e);
            assertEquals(d, EvidenceReasonExplanation.build(json, wrapped));
            for (var field : new String[]{"category", "summary", "missing", "impact"}) assertFalse(d.path(field).asText().isBlank());
        }
        // Optional parity artifact for the frontend regression; no production path or private data.
        String output = System.getProperty("explanation.catalog.output");
        if (output != null) Files.write(Path.of(output), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(EvidenceReasonExplanation.definitions()));
    }
    @Test void publicTransportPreservesExplanationAndDoesNotModifyOriginalEvidence() throws Exception {
        var full = json.createObjectNode();
        var batch = full.putArray("results").addObject().putObject("localBatch").put("schemaVersion", "local-evidence-batch-v1")
                .put("candidateId", "candidate-test").put("hypothesisId", "hypothesis-test").put("contextId", "context-test");
        var criteria = batch.putArray("criteria");
        var row = criteria.addObject().put("criterionId", "ACMG_AMP_2015:PM2").put("status", "NOT_EVALUATED")
                .put("appliedStrength", "NONE").put("reasonCode", "PM2_LOCAL_INTERVAL_QC_FAILED");
        row.putObject("details").put("modulePointer", "/modules/pm2/results/0").putObject("originalResult").put("status", "NOT_EVALUATED");
        String before = full.toString();
        var view = LocalEvidenceBatchRuntime.publicView(json, full);
        assertEquals("QUALITY_REJECTED", view.at("/results/0/localBatch/criteria/0/details/explanation/category").asText());
        assertEquals("NONE", view.at("/results/0/localBatch/criteria/0/appliedStrength").asText());
        assertEquals("/modules/pm2/results/0", view.at("/results/0/localBatch/criteria/0/details/modulePointer").asText());
        assertTrue(view.at("/results/0/localBatch/criteria/0/details/originalResultSha256").asText().matches("[a-f0-9]{64}"));
        assertEquals(view, json.readTree(json.writeValueAsBytes(view)));
        assertEquals(before, full.toString());
    }
}
