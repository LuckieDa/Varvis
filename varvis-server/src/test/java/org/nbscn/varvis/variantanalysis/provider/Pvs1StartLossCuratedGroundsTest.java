package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossCuratedGroundsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Pvs1StartLossOriginalRuleReplayTest.Fixture fixture() throws Exception {
        return new Pvs1StartLossOriginalRuleReplayTest().nativeFixture();
    }
    private ObjectNode assess(Pvs1StartLossOriginalRuleReplayTest.Fixture f) {
        return Pvs1StartLossCuratedGrounds.assess(mapper, f.summary(), f.original(), f.specification());
    }
    private ObjectNode claim(Pvs1StartLossOriginalRuleReplayTest.Fixture f, int line) {
        return (ObjectNode) f.original().at("/evidenceLine/" + line + "/evidenceItem/0/evidenceLine/0/evidenceItem/0/contribution/0");
    }
    @Test void nativeExpertCuratedGroundsRemainPathogenicWithoutPopulationOrGeneContextCodes() throws Exception {
        var out = assess(fixture());
        assertEquals("INDEPENDENT_CURATED_BASIS_ESTABLISHED", out.path("status").asText(), out.toPrettyString());
        assertEquals("Pathogenic", out.at("/independentCombination/classification").asText());
        assertEquals(3, out.at("/independentCombination/appliedCodes").size());
        assertTrue(out.path("independenceEstablished").asBoolean());
        assertFalse(out.path("referenceQualificationComplete").asBoolean());
        assertFalse(out.path("primaryExperimentReevaluated").asBoolean());
        assertFalse(out.path("privateCaseRecordsReconstructed").asBoolean());
        assertEquals("NOT_USED", out.at("/grounds/1/disposition").asText()); // PP2
        assertEquals("NOT_USED", out.at("/grounds/3/disposition").asText()); // PM2
    }
    @Test void sourceClassificationAloneCannotSurviveLostOrContradictoryGrounds() throws Exception {
        var f = fixture();
        claim(f, 4).put("comments", claim(f, 4).path("comments").asText().replace("-1.549791026", "0.5"));
        var out = assess(f);
        assertFalse(out.path("independenceEstablished").asBoolean());
        assertEquals("INDEPENDENT_CURATED_GROUNDS_NOT_SUFFICIENT_FOR_PATHOGENIC", out.path("reasonCode").asText());
        assertEquals("ORIGINAL_CLASSIFICATION_REPLAYED", out.path("originalReplayStatus").asText());
        assertNotEquals("Pathogenic", out.at("/independentCombination/classification").asText());
    }
    @Test void hypotheticalOriginAndRepeatedAccessionAreNotConvertedToCases() throws Exception {
        var f = fixture();
        var origin = claim(f, 0); String text = origin.path("comments").asText();
        origin.put("comments", text + " This was inferred from the start-loss prediction.");
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
        origin.put("comments", text.replace("SCV005768039.2", "SCV001765320.4"));
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
        origin.put("comments", text.replace("SCV005768039.2", "SCV001765320.5"));
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
    }
    @Test void unboundAttributionOrPublicationCannotSupplyIndependentGrounds() throws Exception {
        var f = fixture();
        ((ObjectNode) claim(f, 0).path("agent")).put("id", "CG-PCER-AGENT:CG_99999_EP1");
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
        f = fixture();
        ((ObjectNode) f.original().at("/evidenceLine/4/evidenceItem/0/evidenceLine/0/evidenceItem/0"))
                .put("traceableAuthorStatement", "https://www.ncbi.nlm.nih.gov/pubmed/123");
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
    }
    @Test void unknownPredictorAndAdditionalScientificQualifierCannotBeIgnored() throws Exception {
        var f = fixture();
        claim(f, 2).put("comments", "PVS1 score > 0.7 (score of this variant = 0.753)");
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
        f = fixture();
        ((ObjectNode) f.original().at("/evidenceLine/4/evidenceItem/0/evidenceLine/0/evidenceItem/0"))
                .put("description", "This assay has subsequently failed replication.");
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
    }
    @Test void withdrawnReferenceAndMissingDatesAreNotQualified() throws Exception {
        var f = fixture(); f.summary().put("retracted", true);
        assertFalse(assess(f).path("independenceEstablished").asBoolean());
        f = fixture(); f.summary().remove("approvedDate");
        assertEquals("CURATED_REFERENCE_FACTS_INVALID", assess(f).path("reasonCode").asText());
    }
    @Test void numericalPredictionOfAnInitiatorEditCannotBeRetainedAsIndependentStartLossEvidence() throws Exception {
        var f = fixture();
        // Synthetic adversarial source: preserve the original combination and
        // measured/de-novo reports but move the reference edit to the initiator.
        f.summary().putArray("hgvs").add("NM_000314.8:c.1A>G");
        var prediction = claim(f, 2); prediction.put("comments", prediction.path("comments").asText().replace("REVEL", "CADD"));
        var result = assess(f);
        assertFalse(result.path("independenceEstablished").asBoolean());
        assertEquals("NON_START_LOSS_COMPUTATIONAL_SCOPE_NOT_ESTABLISHED", result.at("/grounds/2/reasonCode").asText());
        assertEquals("NOT_USED", result.at("/grounds/2/disposition").asText());
        assertEquals("INDEPENDENT_CURATED_GROUND", result.at("/grounds/0/disposition").asText());
        assertEquals("INDEPENDENT_CURATED_GROUND", result.at("/grounds/4/disposition").asText());
        assertNotEquals("Pathogenic", result.at("/independentCombination/classification").asText());
    }
}
