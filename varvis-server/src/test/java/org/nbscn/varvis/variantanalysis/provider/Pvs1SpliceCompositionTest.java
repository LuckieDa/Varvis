package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed independent vectors: reference ATG|AAA|CCC|GGG|TTT|TAA, RNA removes CCC. */
class Pvs1SpliceCompositionTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final CodingTranscriptEditsTest fixture = new CodingTranscriptEditsTest();
    record Data(ObjectNode model, ObjectNode context, ObjectNode fact) { }

    Data data(int strand, int genomic, String ref, String alt) {
        var model = fixture.model(strand);
        var context = fixture.context();
        var allele = context.withObject("variant").withObject("evidenceQueryAllele");
        allele.put("position", genomic).put("reference", ref).put("alternate", alt);
        var fact = mapper.createObjectNode().put("transcriptModelSha256", adapter.canonicalSha256(model));
        fact.set("allele", allele.deepCopy());
        fact.set("sourceRecords", model.path("sourceRecords").deepCopy());
        fact.putObject("event").put("type", "EXON_SHORTENING").put("exonOrdinal", 1).put("deleteStartCds", 7).put("deleteEndCds", 9);
        var composition = fact.putObject("codingEditComposition").put("schemaVersion", Pvs1SpliceComposition.SCHEMA)
                .put("status", "REVIEWED_FOR_SAME_ALLELE_COMPOSITION").put("reviewId", "SYNTHETIC_REVIEW")
                .put("basis", "Synthetic same physical allele, not scientific adoption")
                .put("alleleGroupSha256", adapter.canonicalSha256(allele))
                .put("referenceCdsSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(model.path("cdsSequence").asText()))
                .put("spliceEventSha256", adapter.canonicalSha256(fact.path("event"))).put("sequenceScope", "REFERENCE_CDS_DELETION");
        composition.putArray("sourceIds").add("SYNTHETIC");
        return new Data(model, context, fact);
    }
    Pvs1SpliceComposition.Composition compose(Data data) {
        return Pvs1SpliceComposition.compose(mapper, data.context(), data.model(), data.fact(), 6, 9);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void retainedDelinsAndReferenceMappingAreIdenticalOnBothStrands(int strand) {
        var data = data(strand, 103 + (strand == -1 ? 100 : 0), strand == 1 ? "AAA" : "TTT", "CG");
        var result = compose(data);
        assertEquals("ATGCGGGGTTTTAA", result.result().alteredCds());
        assertEquals(-4, result.result().delta());
        assertTrue(result.retainedCodingEdit());
        assertEquals(0, result.result().alteredToReferenceBase(4));
        assertEquals(0, result.result().alteredToReferenceBase(5));
        assertEquals(10, result.result().alteredToReferenceBase(6));
        assertEquals(12, result.nativeStopStart1());
        assertEquals(3, result.referenceEventStart0());
        assertEquals(9, result.referenceEventEnd0());
    }

    @Test void insertedStopBasesNeverGetReferenceCoordinates() {
        var result = compose(data(1, 102, "G", "GTGA"));
        assertEquals("ATGTGAAAAGGGTTTTAA", result.result().alteredCds());
        assertEquals(0, result.result().delta());
        assertEquals(0, result.result().alteredToReferenceBase(4));
        assertEquals(0, result.result().alteredToReferenceBase(5));
        assertEquals(0, result.result().alteredToReferenceBase(6));
        assertEquals(16, result.nativeStopStart1());
    }

    @Test void codingChangeInsideRnaDeletionDisappearsAndAnIdenticalDnaDeletionIsNotCountedTwice() {
        for (var data : java.util.List.of(data(1, 107, "C", "T"), data(1, 105, "ACCC", "A"))) {
            data.fact().remove("codingEditComposition");
            var result = compose(data);
            assertEquals("ATGAAAGGGTTTTAA", result.result().alteredCds());
            assertEquals(-3, result.result().delta());
            assertFalse(result.retainedCodingEdit());
            assertEquals("CODING_COMPONENT_REMOVED_BY_SPLICE", result.snapshot().path("compositionStatus").asText());
        }
    }

    @Test void completeAllelicCdsWitnessIsVerifiedAgainstOneCompositionWithoutReapplyingDna() {
        var data = data(1, 103, "AAA", "CG");
        data.fact().withObject("codingEditComposition").put("sequenceScope", "COMPLETE_ALLELIC_CDS")
                .put("alteredCdsSequence", "ATGCGGGGTTTTAA");
        var result = compose(data);
        assertEquals("ATGCGGGGTTTTAA", result.result().alteredCds());
        assertTrue(result.snapshot().path("completeAllelicCdsWitnessVerified").asBoolean());
        data.fact().withObject("codingEditComposition").put("alteredCdsSequence", "ATGAAAGGGTTTTAA");
        assertEquals("PVS1_SPLICE_COMPOSITION_COMPLETE_ALLELIC_CDS_MISMATCH",
                assertThrows(IllegalArgumentException.class, () -> compose(data)).getMessage());
    }

    @Test void duplicateHgvsAliasIsDeduplicatedOnlyAfterBothRepresentationsAreChecked() {
        var data = data(1, 103, "AAA", "CG");
        data.fact().withObject("codingEditComposition").put("codingHgvsc", "NM_000001.1:c.4_6delinsCG");
        var result = compose(data);
        assertEquals("ATGCGGGGTTTTAA", result.result().alteredCds());
        assertEquals(3, result.snapshot().path("edits").size());
        data.fact().withObject("codingEditComposition").put("codingHgvsc", "NM_000001.1:c.4A>T");
        assertEquals("PVS1_SPLICE_COMPOSITION_CODING_HGVS_ALLELE_MISMATCH",
                assertThrows(IllegalArgumentException.class, () -> compose(data)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"group", "event", "cds", "source", "review", "allele", "retained-unreviewed"})
    void noFalseSourceOrAlleleGroupBindingIsAccepted(String mutation) {
        var data = data(1, 103, "AAA", "CG");
        var declaration = data.fact().withObject("codingEditComposition");
        String reason;
        switch (mutation) {
            case "group" -> { declaration.put("alleleGroupSha256", "f".repeat(64)); reason = "SAME_ALLELE_BINDING_MISMATCH"; }
            case "event" -> { declaration.put("spliceEventSha256", "f".repeat(64)); reason = "SPLICE_EVENT_BINDING_MISMATCH"; }
            case "cds" -> { declaration.put("referenceCdsSha256", "f".repeat(64)); reason = "REFERENCE_CDS_BINDING_MISMATCH"; }
            case "source" -> { declaration.putArray("sourceIds").add("UNBOUND"); reason = "NOT_REVIEWED"; }
            case "review" -> { declaration.put("status", "UNREVIEWED"); reason = "NOT_REVIEWED"; }
            case "allele" -> { ((ObjectNode) data.fact().path("allele")).put("alternate", "TT"); reason = "ALLELE_IDENTITY_MISMATCH"; }
            case "retained-unreviewed" -> { data.fact().remove("codingEditComposition"); reason = "RETAINED_CODING_ALLELE_NOT_SUPPORTED"; }
            default -> throw new AssertionError();
        }
        String expected = "retained-unreviewed".equals(mutation) ? "PVS1_SPLICE_" + reason : "PVS1_SPLICE_COMPOSITION_" + reason;
        assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> compose(data)).getMessage());
    }

    @Test void partialOverlapAndInsertionsAtTheSpliceBoundaryKeepCoreRejections() {
        assertEquals("CODING_EDIT_COMPOSITE_EDIT_PARTIAL_OVERLAP", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> compose(data(1, 105, "ACC", "T"))).reasonCode());
        assertEquals("CODING_EDIT_COMPOSITE_INSERTION_BOUNDARY_AMBIGUOUS", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> compose(data(1, 105, "A", "AG"))).reasonCode());
    }

    @Test void crossIntronDnaIsNotRescuedByAHgvsComponentAndFullCdsWitness() {
        var data = data(1, 107, "CCGG", "C");
        data.fact().withObject("codingEditComposition").put("codingHgvsc", "NM_000001.1:c.8_9del")
                .put("sequenceScope", "COMPLETE_ALLELIC_CDS").put("alteredCdsSequence", "ATGAAAGGGTTTTAA");
        assertEquals("CODING_EDIT_GENOMIC_BOUNDARY_REQUIRES_SPLICE_RESULT", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> compose(data)).reasonCode());
    }

    @Test void fullIntronRemovalBetweenUntranslatedExonsCannotBecomeACodingOnlyTopology() {
        var data = data(1, 104, "AGGGG", "A");
        var exons = data.model().putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 104).put("cdsStart", -8).put("cdsEnd", -4);
        exons.addObject().put("ordinal", 2).put("genomicStart", 109).put("genomicEnd", 112).put("cdsStart", -3).put("cdsEnd", 0);
        exons.addObject().put("ordinal", 3).put("genomicStart", 200).put("genomicEnd", 217).put("cdsStart", 1).put("cdsEnd", 18);
        data.fact().put("transcriptModelSha256", adapter.canonicalSha256(data.model()));
        assertEquals("PVS1_SPLICE_COMPOSITION_GENOMIC_JUNCTION_TOPOLOGY_UNSUPPORTED",
                assertThrows(IllegalArgumentException.class, () -> compose(data)).getMessage());
    }

    @Test void composedTopologyUsesActualDeltaAndPreservesNegativeAndThreePrimeUtrExons() {
        var data = data(1, 103, "AAA", "CG");
        var exons = data.model().withArray("exons");
        ((ObjectNode) exons.path(0)).put("cdsStart", -5).put("genomicStart", 94);
        ((ObjectNode) exons.path(1)).put("cdsEnd", 21).put("genomicEnd", 211);
        exons.addObject().put("ordinal", 3).put("cdsStart", 22).put("cdsEnd", 27).put("genomicStart", 300).put("genomicEnd", 305);
        data.fact().put("transcriptModelSha256", adapter.canonicalSha256(data.model()));
        var result = compose(data);
        var topology = Pvs1SpliceTopology.project(data.model(), 6, 9, result.result(), null);
        assertEquals(java.util.List.of(new Pvs1SpliceTopology.RetainedExon(1, 1, -5, 5),
                new Pvs1SpliceTopology.RetainedExon(2, 2, 6, 17),
                new Pvs1SpliceTopology.RetainedExon(3, 3, 18, 23)), topology.retainedExons());
        assertEquals(17, topology.lastJunctionCdsPosition());
        assertEquals(14, topology.alteredCdsLength());
        var ptc = topology.classifyPtc(4, 6);
        assertTrue(ptc.crossesJunction());
        assertEquals(2, ptc.endExon().referenceOrdinal());
        assertFalse(data.model().has("sequenceVerification"));
        assertFalse(result.snapshot().path("fullRnaOrEjcProofGranted").asBoolean());
    }

    @Test void mixedUtrAndIntronicDnaCannotBeMisreadAsWhollyIntronic() {
        var data = data(1, 210, "ACCC", "A");
        ((ObjectNode) data.model().path("exons").path(1)).put("cdsEnd", 21).put("genomicEnd", 211);
        data.fact().put("transcriptModelSha256", adapter.canonicalSha256(data.model()));
        assertEquals("CODING_EDIT_GENOMIC_BOUNDARY_REQUIRES_SPLICE_RESULT", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> compose(data)).reasonCode());
    }
}
