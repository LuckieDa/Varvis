package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedPm5PtcTableTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SelectedCriterionRouting router = new SelectedCriterionRouting(mapper);

    @Test void onlyExactReleasedScopeUsesGenomicPtcBranch() {
        var context = context("HGNC:1101", "BRCA2", "MONDO:0700269", "AUTOSOMAL_DOMINANT");
        var current = annotation("frameshift_variant", "ENST00000380152.8");
        var route = router.decide(context, current, "MISSING");
        assertFalse(SelectedCriterionRouting.skip(route, "PM5"));
        assertEquals("PM5_PTC_DIRECT_GENOMIC_RULE_CHECK", route.at("/criteria/PM5/reasonCode").asText());
        assertEquals("GN097@1.2", route.at("/criteria/PM5/specification").asText());
        assertTrue(SelectedCriterionRouting.skip(route, "PP2"));
        assertEquals("UNRESOLVED", router.decide(context, annotation("frameshift_variant", "ENST00000380152.7"), "MISSING")
                .at("/criteria/PM5/status").asText());
        context.putObject("disease").put("mondoId", "MONDO:0011584");
        context.put("moi", "AUTOSOMAL_RECESSIVE");
        assertEquals("ROUTING_MISSENSE_REQUIRED", router.decide(context, current, "MISSING").at("/criteria/PM5/reasonCode").asText());
    }

    @Test void theRuleExcludesSpliceAndKeepsClassicMissenseForOtherBackgrounds() {
        var brca = context("HGNC:1100", "BRCA1", "MONDO:0700268", "AUTOSOMAL_DOMINANT");
        var splice = router.decide(brca, annotation("splice_donor_variant&stop_gained", "NM_007294.4"), "MISSING");
        assertTrue(SelectedCriterionRouting.skip(splice, "PM5"));
        assertEquals("PM5_PTC_SPLICE_MECHANISM_EXCLUDED", splice.at("/criteria/PM5/reasonCode").asText());
        var rnaOnly = router.decide(brca, annotation("splice_region_variant&synonymous_variant", "NM_007294.4"), "MISSING");
        assertEquals("NOT_APPLICABLE", rnaOnly.at("/criteria/PM5/status").asText());
        assertEquals("PM5_PTC_SPLICE_MECHANISM_EXCLUDED", rnaOnly.at("/criteria/PM5/reasonCode").asText());
        var mixed = router.decide(brca, annotation("splice_region_variant&frameshift_variant", "NM_007294.4"), "MISSING");
        assertEquals("UNRESOLVED", mixed.at("/criteria/PM5/status").asText());
        var missense = router.decide(brca, annotation("missense_variant", "NM_007294.4"), "MISSING");
        assertEquals("NOT_APPLICABLE", missense.at("/criteria/PM5/status").asText());
        var classic = router.decide(context("HGNC:8582", "PAH", "MONDO:0009861", "AUTOSOMAL_RECESSIVE"),
                annotation("missense_variant", "NM_000277.3"), "MISSING");
        assertFalse(SelectedCriterionRouting.skip(classic, "PM5"));
        assertEquals("ROUTING_ORIGINAL_RULE_CHECK_REQUIRED", classic.at("/criteria/PM5/reasonCode").asText());
    }

    @Test void stopExonAndStrictTerminalCutoffsComeFromFrozenWeights() {
        var brca2 = PublishedPm5PtcTable.select(context("HGNC:1101", "BRCA2", "MONDO:0700269", "AUTOSOMAL_DOMINANT"));
        assertNotNull(brca2);
        assertEquals("NONE", brca2.stopRow(6, 171).pm5Strength());
        assertTrue(brca2.stopRow(6, 171).pvs1Applicable());
        assertEquals("STRONG", brca2.stopRow(7, 182).pm5Strength());
        assertEquals("MODERATE", brca2.stopRow(4, 120).pm5Strength());
        assertEquals("SUPPORTING", brca2.stopRow(21, 2900).pm5Strength());
        assertEquals("STRONG", brca2.stopRow(27, 3309).pm5Strength());
        assertFalse(brca2.stopRow(27, 3310).pvs1Applicable());
        assertNull(brca2.stopRow(27, 3419));
        var brca1 = PublishedPm5PtcTable.select(context("HGNC:1100", "BRCA1", "MONDO:0700268", "AUTOSOMAL_DOMINANT"));
        assertNotNull(brca1);
        assertEquals("STRONG", brca1.stopRow(23, 1854).pm5Strength());
        assertFalse(brca1.stopRow(23, 1855).pvs1Applicable());
        assertEquals(50, brca1.rows().size() + brca2.rows().size());
    }

    private ObjectNode context(String hgnc, String gene, String disease, String moi) {
        var context = mapper.createObjectNode().put("contextId", "fixture").put("moi", moi);
        context.putObject("scope").put("analysisDomain", "GERMLINE");
        context.putObject("gene").put("hgncId", hgnc).put("symbol", gene);
        context.putObject("disease").put("mondoId", disease);
        return context;
    }

    private TranscriptConsequence annotation(String terms, String transcript) {
        var value = mock(TranscriptConsequence.class);
        when(value.getConsequence()).thenReturn(terms);
        when(value.getVersionedTranscriptId()).thenReturn(transcript);
        when(value.getVersion()).thenReturn("116.0");
        return value;
    }
}
