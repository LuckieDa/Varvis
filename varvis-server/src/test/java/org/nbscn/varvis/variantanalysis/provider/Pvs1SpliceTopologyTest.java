package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Hand-counted toy transcript coordinates; no source qualification or clinical adoption. */
class Pvs1SpliceTopologyTest {
    final ObjectMapper mapper = new ObjectMapper();

    ObjectNode model(int length, int... bounds) {
        var model = mapper.createObjectNode().put("cdsSequence", "A".repeat(length)).put("cdsLength", length);
        var exons = model.putArray("exons");
        for (int i = 0; i < bounds.length; i += 2)
            exons.addObject().put("ordinal", i / 2 + 1).put("cdsStart", bounds[i]).put("cdsEnd", bounds[i + 1]);
        return model;
    }

    @Test void removesWholeInternalExonAndRenumbersOnlyAlteredOrdinals() {
        var model = model(30, 1, 6, 7, 10, 11, 23, 24, 30);
        var before = model.deepCopy();
        var result = Pvs1SpliceTopology.project(model, 6, 10, "A".repeat(26));
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, 1, 6),
                new Pvs1SpliceTopology.RetainedExon(3, 2, 7, 19),
                new Pvs1SpliceTopology.RetainedExon(4, 3, 20, 26)), result.retainedExons());
        assertEquals(List.of(new Pvs1SpliceTopology.Junction(1, 3, 1, 2, 6),
                new Pvs1SpliceTopology.Junction(3, 4, 2, 3, 19)), result.junctions());
        assertEquals(19, result.lastJunctionCdsPosition());
        assertEquals(26, result.alteredCdsLength());
        assertFalse(result.singleExon());
        assertEquals(before, model);
        assertThrows(UnsupportedOperationException.class, () -> result.retainedExons().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.junctions().clear());
    }

    @Test void deletingLastExonRemovesOriginalJunctionAndKeepsEarlierOne() {
        var result = Pvs1SpliceTopology.project(model(30, 1, 6, 7, 23, 24, 30), 23, 30, "A".repeat(23));
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, 1, 6),
                new Pvs1SpliceTopology.RetainedExon(2, 2, 7, 23)), result.retainedExons());
        assertEquals(6, result.lastJunctionCdsPosition());
    }

    @Test void deletingFirstExonCanLeaveOneExonWithoutInventingJunction() {
        var result = Pvs1SpliceTopology.project(model(30, 1, 6, 7, 30), 0, 6, "A".repeat(24));
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(2, 1, 1, 24)), result.retainedExons());
        assertTrue(result.singleExon());
        assertNull(result.lastJunctionCdsPosition());
        assertTrue(result.junctions().isEmpty());
        assertEquals(2, result.classifyPtc(1, 3).startExon().referenceOrdinal());
    }

    @Test void deletionFromOriginallySingleExonKeepsItsTopology() {
        var result = Pvs1SpliceTopology.project(model(30, -5, 36), 4, 8, "A".repeat(26));
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, -5, 32)), result.retainedExons());
        assertTrue(result.singleExon());
        assertNull(result.lastJunctionCdsPosition());
    }

    @Test void shorteningFirstAndLastExonsKeepsTheirRemainingBases() {
        var model = model(30, 1, 6, 7, 23, 24, 30);
        var first = Pvs1SpliceTopology.project(model, 0, 2, "A".repeat(28));
        assertEquals(new Pvs1SpliceTopology.RetainedExon(1, 1, 1, 4), first.retainedExons().get(0));
        assertEquals(21, first.lastJunctionCdsPosition());
        var last = Pvs1SpliceTopology.project(model, 26, 30, "A".repeat(26));
        assertEquals(new Pvs1SpliceTopology.RetainedExon(3, 3, 24, 26), last.retainedExons().get(2));
        assertEquals(23, last.lastJunctionCdsPosition());
    }

    @Test void preservesNegativeAndZeroUtrOffsetsAndMovesThreePrimeUtrJunction() {
        var result = Pvs1SpliceTopology.project(model(30, -8, 0, 1, 9, 10, 36, 37, 45), 3, 7, "A".repeat(26));
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, -8, 0),
                new Pvs1SpliceTopology.RetainedExon(2, 2, 1, 5),
                new Pvs1SpliceTopology.RetainedExon(3, 3, 6, 32),
                new Pvs1SpliceTopology.RetainedExon(4, 4, 33, 41)), result.retainedExons());
        assertEquals(List.of(0, 5, 32), result.junctions().stream().map(mappedItem -> mappedItem.alteredCdsPosition()).toList());
        assertEquals(32, result.lastJunctionCdsPosition());
    }

    @Test void cdsDeletionDoesNotSilentlyDeleteRetainedTerminalUtr() {
        var first = Pvs1SpliceTopology.project(model(30, -5, 6, 7, 30), 0, 6, "A".repeat(24));
        assertEquals(new Pvs1SpliceTopology.RetainedExon(1, 1, -5, 0), first.retainedExons().get(0));
        assertFalse(first.singleExon());
        assertEquals(0, first.lastJunctionCdsPosition());
        var last = Pvs1SpliceTopology.project(model(30, 1, 23, 24, 36), 23, 30, "A".repeat(23));
        assertEquals(new Pvs1SpliceTopology.RetainedExon(2, 2, 24, 29), last.retainedExons().get(1));
        assertEquals(23, last.lastJunctionCdsPosition());
    }

    @Test void explicitWholeFirstExonSkippingAlsoRemovesItsUtr() {
        var result = Pvs1SpliceTopology.project(model(30, -5, 6, 7, 30), 0, 6, "A".repeat(24), 1);
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(2, 1, 1, 24)), result.retainedExons());
        assertTrue(result.singleExon());
        assertNull(result.lastJunctionCdsPosition());
    }

    @Test void explicitWholeSkippingReanchorsRetainedFivePrimeUtr() {
        var result = Pvs1SpliceTopology.project(model(30, -8, -1, 0, 6, 7, 30), 0, 6, "A".repeat(24), 2);
        // Exon 2 loses one UTR base (old offset 0), so upstream -8..-1 becomes -7..0.
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, -7, 0),
                new Pvs1SpliceTopology.RetainedExon(3, 2, 1, 24)), result.retainedExons());
        assertEquals(0, result.lastJunctionCdsPosition());
    }

    @Test void explicitWholeLastCodingExonSkippingMovesFollowingUtrByActualExonLength() {
        var result = Pvs1SpliceTopology.project(model(30, 1, 23, 24, 36, 37, 45), 23, 30, "A".repeat(23), 2);
        // Seven CDS bases plus six UTR bases are removed; following 37..45 becomes 24..32.
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, 1, 23),
                new Pvs1SpliceTopology.RetainedExon(3, 2, 24, 32)), result.retainedExons());
        assertEquals(23, result.lastJunctionCdsPosition());
        assertEquals(23, result.alteredCdsLength());
    }

    @Test void explicitWholeTerminalExonSkippingCanRemoveItsUtrAndLeaveOneExon() {
        var result = Pvs1SpliceTopology.project(model(30, 1, 23, 24, 36), 23, 30, "A".repeat(23), 2);
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, 1, 23)), result.retainedExons());
        assertTrue(result.singleExon());
        assertNull(result.lastJunctionCdsPosition());
    }

    @Test void wholeExonDeclarationCannotDiscardUncoveredCdsOrAnotherExon() {
        var model = model(30, -5, 6, 7, 23, 24, 36);
        assertThrows(IllegalArgumentException.class, () -> Pvs1SpliceTopology.project(model, 1, 6, "A".repeat(25), 1));
        assertThrows(IllegalArgumentException.class, () -> Pvs1SpliceTopology.project(model, 6, 10, "A".repeat(26), 2));
        assertThrows(IllegalArgumentException.class, () -> Pvs1SpliceTopology.project(model, 6, 23, "A".repeat(13), 3));
        assertThrows(IllegalArgumentException.class, () -> Pvs1SpliceTopology.project(model, 6, 23, "A".repeat(13), 0));
        assertThrows(IllegalArgumentException.class, () -> Pvs1SpliceTopology.project(model, 6, 23, "A".repeat(13), 4));
    }

    @Test void fullExonRemovalAcrossMultipleExonsPreservesOnlyActualRemainingJunction() {
        var result = Pvs1SpliceTopology.project(model(30, 1, 6, 7, 10, 11, 23, 24, 30), 6, 23, "A".repeat(13));
        assertEquals(List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, 1, 6),
                new Pvs1SpliceTopology.RetainedExon(4, 2, 7, 13)), result.retainedExons());
        assertEquals(List.of(new Pvs1SpliceTopology.Junction(1, 4, 1, 2, 6)), result.junctions());
    }

    @Test void ptcAcrossJunctionRecordsBothCarrierExonsAndUsesFinalBaseExon() {
        var result = Pvs1SpliceTopology.project(model(30, 1, 6, 7, 10, 11, 23, 24, 30), 6, 10, "A".repeat(26));
        var crossing = result.classifyPtc(6, 8);
        assertTrue(crossing.crossesJunction());
        assertEquals(List.of(1, 3), crossing.exons().stream().map(mappedItem -> mappedItem.referenceOrdinal()).toList());
        assertEquals(1, crossing.startExon().referenceOrdinal());
        assertEquals(3, crossing.endExon().referenceOrdinal());
        assertEquals(2, crossing.endExon().alteredOrdinal());
        assertEquals(13, crossing.maximumExonLength());
        assertThrows(UnsupportedOperationException.class, () -> crossing.exons().clear());
        var exactEnd = result.classifyPtc(4, 6);
        assertFalse(exactEnd.crossesJunction());
        assertEquals(1, exactEnd.endExon().referenceOrdinal());
        assertEquals(3, result.classifyPtc(7, 9).startExon().referenceOrdinal());
    }

    @Test void aThreeBaseStopCanSpanThreeOneBaseExons() {
        var result = Pvs1SpliceTopology.project(model(12, 1, 3, 4, 4, 5, 5, 6, 12), 0, 2, "A".repeat(10));
        var crossing = result.classifyPtc(1, 3);
        assertEquals(List.of(1, 2, 3), crossing.exons().stream().map(mappedItem -> mappedItem.referenceOrdinal()).toList());
        assertEquals(3, crossing.endExon().referenceOrdinal());
        assertEquals(1, crossing.maximumExonLength());
    }

    @ParameterizedTest @ValueSource(strings = {"negative", "reversed", "empty", "beyond", "length", "sequence",
            "gap", "overlap", "ordinal", "missing", "incomplete", "empty-transcript", "length-field"})
    void invalidIntervalsAndIncompleteModelsFailClosed(String mutation) {
        var model = model(30, 1, 6, 7, 23, 24, 30);
        int start = 6, end = 10;
        String altered = "A".repeat(26);
        switch (mutation) {
            case "negative" -> start = -1;
            case "reversed" -> { start = 10; end = 6; }
            case "empty" -> end = 6;
            case "beyond" -> end = 31;
            case "length" -> altered = "A".repeat(25);
            case "sequence" -> altered = "N".repeat(26);
            case "gap" -> ((ObjectNode) model.path("exons").get(1)).put("cdsStart", 8);
            case "overlap" -> ((ObjectNode) model.path("exons").get(1)).put("cdsStart", 6);
            case "ordinal" -> ((ObjectNode) model.path("exons").get(1)).put("ordinal", 3);
            case "missing" -> ((ObjectNode) model.path("exons").get(1)).remove("cdsEnd");
            case "incomplete" -> ((ObjectNode) model.path("exons").get(2)).put("cdsEnd", 29);
            case "empty-transcript" -> { start = 0; end = 30; altered = ""; }
            case "length-field" -> model.put("cdsLength", 31);
            default -> throw new AssertionError(mutation);
        }
        int start0 = start, end0 = end;
        String changed = altered;
        var error = assertThrows(IllegalArgumentException.class, () -> Pvs1SpliceTopology.project(model, start0, end0, changed));
        assertEquals("PVS1_SPLICE_TOPOLOGY_INVALID", error.getMessage());
    }

    @Test void ptcClassificationRejectsWrongSpanAndOutsideCds() {
        var result = Pvs1SpliceTopology.project(model(30, 1, 30), 6, 10, "A".repeat(26));
        assertThrows(IllegalArgumentException.class, () -> result.classifyPtc(0, 2));
        assertThrows(IllegalArgumentException.class, () -> result.classifyPtc(1, 4));
        assertThrows(IllegalArgumentException.class, () -> result.classifyPtc(25, 27));
        assertThrows(IllegalArgumentException.class, () -> result.classifyPtc(4, 2));
    }
}
