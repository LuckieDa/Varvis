package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic boundary checks; no local model or resource qualification is implied. */
class Bp4CompleteMechanismTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode plan() {
        var plan = PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json");
        plan.putObject("context").put("contextId", "synthetic-bp4");
        plan.set("effectRestrictions", PredictionCoreTest.restrictions(mapper, plan));
        return plan;
    }
    ObjectNode mechanisms() {
        var all = mapper.createObjectNode();
        all.putObject("PROTEIN_MISSENSE").put("status", "CALIBRATED").put("direction", "BENIGN").put("appliedStrength", "MODERATE");
        all.putObject("SPLICING").put("status", "CALIBRATED").put("direction", "BENIGN").put("appliedStrength", "SUPPORTING");
        return all;
    }
    @Test void bothBenignUsePrimaryStrengthWithoutAddingAndMissingSpliceCannotPass() {
        var p = plan(); var m = mechanisms();
        var good = PredictionMechanismApplication.apply(mapper, p, m, null);
        assertEquals("MET", good.at("/BP4/status").asText());
        assertEquals("MODERATE", good.at("/BP4/appliedStrength").asText());
        m.remove("SPLICING");
        assertEquals("NOT_EVALUATED", PredictionMechanismApplication.apply(mapper, p, m, null).at("/BP4/status").asText());
    }
    @Test void incompleteOrCorruptCoveragePlanCannotProduceBp4() {
        for (String bad : new String[]{"MISSING", "EMPTY", "OBJECT", "DUPLICATE", "UNKNOWN", "NO_PRIMARY"}) {
            var p = plan();
            switch (bad) {
                case "MISSING" -> p.remove("requiredBp4Mechanisms");
                case "EMPTY" -> p.putArray("requiredBp4Mechanisms");
                case "OBJECT" -> p.putObject("requiredBp4Mechanisms");
                case "DUPLICATE" -> p.withArray("requiredBp4Mechanisms").add("SPLICING");
                case "UNKNOWN" -> p.withArray("requiredBp4Mechanisms").add("UNKNOWN");
                case "NO_PRIMARY" -> p.putArray("requiredBp4Mechanisms").add("SPLICING");
                default -> throw new AssertionError(bad);
            }
            var result = PredictionMechanismApplication.apply(mapper, p, mechanisms(), null);
            assertEquals("NOT_EVALUATED", result.at("/BP4/status").asText(), bad);
            assertEquals("BP4_REQUIRED_MECHANISM_PLAN_INVALID", result.at("/BP4/reasonCode").asText(), bad);
        }
    }
    @Test void invalidCalibrationStateIsUnknownNotNegativeOrAdoptable() {
        for (String bad : new String[]{"DIRECTION", "STRENGTH", "ZERO_STRENGTH"}) {
            var m = mechanisms(); var s = (ObjectNode)m.path("SPLICING");
            if ("DIRECTION".equals(bad)) s.remove("direction");
            else s.put("appliedStrength", "STRENGTH".equals(bad) ? "INVALID" : "NONE");
            assertEquals("NOT_EVALUATED", PredictionMechanismApplication.apply(mapper, plan(), m, null).at("/BP4/status").asText(), bad);
        }
    }
    @Test void spliceOnlyAndGrayOrHarmfulRemainDistinct() {
        var p = plan(); p.put("primaryMechanism", "SPLICING");
        p.putArray("requiredBp4Mechanisms").add("SPLICING"); p.putArray("allowedPp3Mechanisms").add("SPLICING");
        var m = mechanisms(); m.remove("PROTEIN_MISSENSE");
        assertEquals("MET", PredictionMechanismApplication.apply(mapper, p, m, null).at("/BP4/status").asText());
        ((ObjectNode)m.path("SPLICING")).put("direction", "UNINFORMATIVE").put("appliedStrength", "NONE");
        assertEquals("NOT_MET", PredictionMechanismApplication.apply(mapper, p, m, null).at("/BP4/status").asText());
        ((ObjectNode)m.path("SPLICING")).put("direction", "HARMFUL").put("appliedStrength", "SUPPORTING");
        assertEquals("MET", PredictionMechanismApplication.apply(mapper, p, m, null).at("/PP3/status").asText());
        assertEquals("NOT_MET", PredictionMechanismApplication.apply(mapper, p, m, null).at("/BP4/status").asText());
    }
    @Test void missensePlanCannotOmitSplicingAndWindowFailureIsPubliclyExplained() {
        var p = plan(); p.putArray("requiredBp4Mechanisms").add("PROTEIN_MISSENSE");
        var result = PredictionMechanismApplication.apply(mapper, p, mechanisms(), null);
        assertEquals("NOT_EVALUATED", result.at("/BP4/status").asText());
        assertEquals("UNKNOWN", result.at("/BP4/criterionApplicability/status").asText());
        var saved = mapper.createObjectNode(); saved.set("plan", plan()); saved.set("mechanisms", mechanisms());
        ((ObjectNode)saved.at("/mechanisms/SPLICING")).put("status", "NOT_EVALUATED")
                .put("direction", "UNRESOLVED").put("appliedStrength", "NONE").put("reasonCode", "SPLICE_EVENT_OUTSIDE_CONFIGURED_WINDOW");
        var publicView = PredictionMechanismSummary.publicView(mapper, PredictionMechanismSummary.fromResult(mapper, saved));
        assertTrue(publicView.at("/mechanisms/1/reason").asText().contains("超出本次 SpliceAI 的预测窗口"));
        assertEquals("NOT_EVALUATED", publicView.at("/mechanisms/1/status").asText());
        assertEquals("NONE", publicView.at("/mechanisms/1/appliedStrength").asText());
    }
    @Test void rawEventOutsideConfiguredWindowCannotBecomeBenign() throws Exception {
        var f = new PredictionCoreTest();
        var resource = PredictionCoreTest.resource(mapper).put("model", "SPLICEAI").put("scoreMode", "RAW").put("distance", 4999).put("mask", 0);
        ((ObjectNode)resource.path("compatibilityEvidence")).put("calibrationId", "CLINGEN_SVI_SPLICE_2024_03_22_SECTION_5");
        var a = resource.putObject("annotation").put("status", "VERIFIED").put("source", "synthetic")
                .put("version", "synthetic").put("assembly", "GRCh38");
        for (String field : new String[]{"transcriptSetSha256", "referenceSha256", "annotationSha256", "calibrationEvidenceSha256"}) a.put(field, "e".repeat(64));
        var allele = new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh38", "1", 20000, "A", "G");
        var row = mapper.createObjectNode().put("transcriptId", f.tx().getVersionedTranscriptId()).put("geneId", f.tx().getGeneId());
        row.set("queryAllele", mapper.valueToTree(allele));
        for (String kind : new String[]{"AG", "AL", "DG", "DL"}) row.put("DS_" + kind, "0.01").put("DP_" + kind, 0);
        for (int offset : new int[]{-5000, 5000, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            row.put("DP_AG", offset);
            var result = SpliceAiBusinessRuntime.calibrateDevelopment(mapper, row, resource, resource, f.tx(), allele);
            assertEquals("NOT_EVALUATED", result.path("status").asText(), "offset=" + offset);
            assertEquals("SPLICE_EVENT_OUTSIDE_CONFIGURED_WINDOW", result.path("reasonCode").asText());
        }
        for (int offset : new int[]{-4999, 4999}) {
            row.put("DP_AG", offset);
            assertEquals("BENIGN", SpliceAiBusinessRuntime.calibrateDevelopment(mapper, row, resource, resource, f.tx(), allele).path("direction").asText());
        }
    }
}
