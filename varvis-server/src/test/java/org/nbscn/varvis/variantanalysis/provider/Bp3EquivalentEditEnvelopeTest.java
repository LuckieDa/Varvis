package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Bp3EquivalentEditEnvelopeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void trueDelinsIncludesEveryReplacedReferenceResidue() {
        var f = new Pm4TestFixtures(mapper, 1);
        var facts = mapper.createObjectNode().put("referenceCds", Pm4TestFixtures.CDS)
                .put("alteredCds", "ATGGAAGCTGAAGGCTTTAAATAA").put("referenceEditStart0", 6)
                .put("referenceEditEnd0", 9).put("insertedSequence", "GCTGAA").put("exonOrdinal", 1);
        assertNull(Bp3EquivalentEditEnvelope.resolveComplex(f.model, facts));
        var envelope = facts.path("equivalentEditEnvelope");
        assertEquals(6, envelope.path("firstStart0").asInt());
        assertEquals(1, envelope.path("equivalentPositionCount").asInt());
        assertEquals(3, envelope.path("deletedLength").asInt());
        assertEquals(6, envelope.path("insertedLength").asInt());
        assertEquals(3, envelope.path("proteinStart").asInt());
        assertEquals(3, envelope.path("proteinEnd").asInt());
    }

    @Test void complexPathRetainsTheCompleteRepeatShiftEnvelopeForEquivalentPureIndels() {
        var f = new Pm4TestFixtures(mapper, 1);
        var a = mapper.createObjectNode().put("referenceCds", Pm4TestFixtures.CDS)
                .put("alteredCds", "ATGGAACCTCCTGGCTTTAAATAA").put("referenceEditStart0", 6)
                .put("referenceEditEnd0", 9).put("insertedSequence", "CCTCCT").put("exonOrdinal", 1);
        var b = a.deepCopy();
        assertNull(Bp3EquivalentEditEnvelope.resolve(f.model, a));
        assertNull(Bp3EquivalentEditEnvelope.resolveComplex(f.model, b));
        assertEquals(a.path("equivalentEditEnvelope"), b.path("equivalentEditEnvelope"));
        assertTrue(b.at("/equivalentEditEnvelope/equivalentPositionCount").asInt() > 1);
    }

    @Test void aMinimalCdsPlacementCannotHideAPhysicalCrossExonReplacement() {
        var f = new Pm4TestFixtures(mapper, 1);
        f.model.withObject("/exons/0").put("cdsEnd", 7);
        var facts = mapper.createObjectNode().put("referenceCds", Pm4TestFixtures.CDS)
                .put("alteredCds", "ATGGAAGCTGAAGGCTTTAAATAA").put("referenceEditStart0", 6)
                .put("referenceEditEnd0", 9).put("insertedSequence", "GCTGAA").put("exonOrdinal", 1);
        assertEquals("BP3_EQUIVALENT_ENVELOPE_EXON_BOUNDARY", Bp3EquivalentEditEnvelope.resolveComplex(f.model, facts));
    }
}
