package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossTruncatingGroundTest {
    final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode model(int strand) {
        var m = mapper.createObjectNode().put("transcript", "NM_000001.1").put("geneSymbol", "GENE1")
                .put("assembly", "GRCh38").put("contig", "1").put("strand", strand).put("cdsSequence", "ATGAAATACAAATAA");
        m.putArray("exons").addObject().put("ordinal", 1).put("cdsStart", 1).put("cdsEnd", 15)
                .put("genomicStart", 100).put("genomicEnd", 114);
        return m;
    }
    private ObjectNode summary(String hgvs) {
        var s = mapper.createObjectNode().put("gene", "GENE1"); s.putArray("hgvs").add("NM_000001.1:c." + hgvs); return s;
    }
    private String claim(String type) { return "This " + type + " mutation causes a truncation 5' to c.10 in a biologically-relevant transcript (NM_000001.1)."; }

    @Test void distinctNonsenseAndFrameshiftMechanismsReplayInBothOrientationsWithoutClassifyingTarget() {
        for (int strand : new int[]{1, -1}) {
            var nonsense = Pvs1StartLossTruncatingGround.assess(mapper, claim("nonsense"), summary("4A>T"), model(strand));
            assertEquals("NON_START_LOSS_TRUNCATION_CONFIRMED", nonsense.path("status").asText());
            assertEquals(4, nonsense.path("prematureStopCdsPosition").asInt());
            assertTrue(nonsense.path("originalStartRetained").asBoolean());
            assertFalse(nonsense.path("independenceEstablished").asBoolean());
            assertFalse(nonsense.path("referencePathogenicityGranted").asBoolean());
            var frameshift = Pvs1StartLossTruncatingGround.assess(mapper, claim("frameshift"), summary("9del"), model(strand));
            assertEquals("NON_START_LOSS_TRUNCATION_CONFIRMED", frameshift.path("status").asText());
            assertEquals(7, frameshift.path("prematureStopCdsPosition").asInt());
        }
    }
    @Test void labelsAloneStartLossMissenseAndClaimTypeMismatchCannotSupplyNullGround() {
        for (String edit : java.util.List.of("1A>G", "4A>G", "9del"))
            assertEquals("UNRESOLVED", Pvs1StartLossTruncatingGround.assess(mapper, claim("nonsense"), summary(edit), model(1)).path("status").asText());
        assertEquals("UNRESOLVED", Pvs1StartLossTruncatingGround.assess(mapper, "PVS1 Met", summary("4A>T"), model(1)).path("status").asText());
        assertEquals("UNRESOLVED", Pvs1StartLossTruncatingGround.assess(mapper, claim("nonsense") + " This depends on a start-loss prediction.", summary("4A>T"), model(1)).path("status").asText());
    }
    @Test void inFrameCodonDeletionDoesNotTurnTheRelocatedNaturalStopIntoANewPrematureStop() {
        // ATG AAA TAC AAA TAA -> ATG TAC AAA TAA. The natural stop is still the
        // same TAA; its altered coordinate moves from13to10 solely by deletion.
        var result = Pvs1StartLossTruncatingGround.assess(mapper, claim("nonsense").replace("c.10", "c.15"), summary("4_6del"), model(1));
        assertEquals("UNRESOLVED", result.path("status").asText());
        assertEquals("CURATED_NONSENSE_LENGTH_CHANGE_UNSUPPORTED", result.path("reasonCode").asText());
        assertFalse(result.path("referencePathogenicityGranted").asBoolean());
    }
    @Test void versionMismatchAlleleContradictionAndBoundaryDisagreementRemainExplicit() {
        assertEquals("CURATED_TRUNCATING_TRANSCRIPT_MISMATCH", Pvs1StartLossTruncatingGround.assess(mapper,
                claim("nonsense").replace(".1)", ".2)"), summary("4A>T"), model(1)).path("reasonCode").asText());
        var s = summary("4A>T"); s.withArray("hgvs").add("NM_000001.1:c.7T>A");
        assertEquals("CURATED_TRUNCATING_ALLELE_CONFLICT", Pvs1StartLossTruncatingGround.assess(mapper, claim("nonsense"), s, model(1)).path("reasonCode").asText());
        assertEquals("CURATED_PREMATURE_STOP_NOT_CONFIRMED", Pvs1StartLossTruncatingGround.assess(mapper,
                claim("nonsense").replace("c.10", "c.4"), summary("4A>T"), model(1)).path("reasonCode").asText());
    }
    @Test void exactCuratedNmdStatementMustAgreeWithGeneAlleleAndExonButDoesNotPretendNmdWasReevaluated() {
        String text = "The NM_000001.1:c.4A>T (p.Lys2Ter) variant in GENE1 is a nonsense variant predicted to cause a premature stop codon in biologically-relevant-exon 1/2, leading to nonsense mediated decay in a gene in which loss-of-function is an established disease mechanism (PVS1).";
        var model = model(1).put("cdsSequence", "ATGAAA" + "CAA".repeat(30) + "TAA").put("lastJunctionCdsPosition", 80);
        model.withArray("exons").removeAll();
        model.withArray("exons").addObject().put("ordinal", 1).put("cdsStart", 1).put("cdsEnd", 80).put("genomicStart", 100).put("genomicEnd", 179);
        model.withArray("exons").addObject().put("ordinal", 2).put("cdsStart", 81).put("cdsEnd", 99).put("genomicStart", 300).put("genomicEnd", 318);
        var result = Pvs1StartLossTruncatingGround.assess(mapper, text, summary("4A>T"), model);
        assertEquals("NON_START_LOSS_TRUNCATION_CONFIRMED", result.path("status").asText());
        assertTrue(result.path("sourceNmdClaimPresent").asBoolean()); assertFalse(result.path("nmdReevaluated").asBoolean());
        for (String changed : java.util.List.of(text.replace("GENE1", "GENE2"), text.replace("1/2", "1/1"), text.replace("p.Lys2Ter", "p.Tyr2Ter")))
            assertEquals("UNRESOLVED", Pvs1StartLossTruncatingGround.assess(mapper, changed, summary("4A>T"), model).path("status").asText());
        assertEquals("CURATED_NMD_GEOMETRY_CONFLICT", Pvs1StartLossTruncatingGround.assess(mapper, text, summary("4A>T"), model.deepCopy().put("lastJunctionCdsPosition", 50)).path("reasonCode").asText());
    }
}
