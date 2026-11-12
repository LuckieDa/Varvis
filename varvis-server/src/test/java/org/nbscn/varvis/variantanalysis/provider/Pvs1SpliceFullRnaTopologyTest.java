package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independently fixed RNA oracles over real opaque supply issuance and a synthetic
 * tiny genome. Mechanical declarations here are explicitly not experimental RNA,
 * verified phase, scientific qualification, or PVS1 adoption.
 * R = CC + ATG AAA CCC GGG TTT GAA TAA + GGG TGA (29 bases).
 * Reference CDS is RNA [2,23); RNA exon ends are 8/17/25/29 on both genomic strands.
 */
class Pvs1SpliceFullRnaTopologyTest {
    @TempDir Path root;

    private CodingTranscriptRnaFixture fixture(int strand) throws Exception {
        return new CodingTranscriptRnaFixture(root.resolve("rna"), strand);
    }
    private CodingTranscriptEdits.RnaReference reference(CodingTranscriptRnaFixture f) {
        return CodingTranscriptEdits.bindRna(f.mapper, f.context, f.token);
    }
    private CodingTranscriptEdits.Edit edit(CodingTranscriptRnaFixture f, String id, int start, int end, String insert) {
        return new CodingTranscriptEdits.Edit(id, CodingTranscriptEdits.Origin.SPLICE_PROJECTION, start, end, insert,
                f.token.referenceRnaSequence().substring(start, end), "e".repeat(64),
                f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele")), 0);
    }
    private CodingTranscriptEdits.RnaResult result(CodingTranscriptRnaFixture f, int start, int end, String insert) {
        return apply(f, List.of(edit(f, "TEST_ONLY_RNA_EDIT", start, end, insert)));
    }
    private CodingTranscriptEdits.RnaResult apply(CodingTranscriptRnaFixture f, List<CodingTranscriptEdits.Edit> edits) {
        return CodingTranscriptEdits.applyRna(reference(f), new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES,
                f.token.referenceRnaSha256(), CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, edits));
    }
    private Pvs1SpliceTopology.RnaTopologyDeclaration declaration(CodingTranscriptRnaFixture f, CodingTranscriptEdits.RnaResult result,
            Pvs1SpliceTopology.RnaTopologyCompleteness completeness, List<Integer> junctions) {
        return new Pvs1SpliceTopology.RnaTopologyDeclaration(f.token.modelSha256(), f.token.referenceRnaSha256(), result.alteredRnaSha256(),
                f.adapter.canonicalSha256(result.snapshot(f.mapper)), completeness, junctions);
    }
    private Pvs1SpliceTopology.FullRnaProjection complete(CodingTranscriptRnaFixture f, CodingTranscriptEdits.RnaResult result, Integer... junctions) {
        return Pvs1SpliceTopology.projectFullRna(result, declaration(f, result, Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE, List.of(junctions)));
    }
    private int firstStopRna1(String rna, int selectedStartRna1) {
        for (int start0 = selectedStartRna1 - 1; start0 + 3 <= rna.length(); start0 += 3) {
            String codon = rna.substring(start0, start0 + 3);
            if (codon.equals("TAA") || codon.equals("TAG") || codon.equals("TGA")) return start0 + 1;
        }
        return 0;
    }
    private void noQualification(Pvs1SpliceTopology.FullRnaProjection projection) {
        assertEquals("NOT_GRANTED_BY_OPERATOR", projection.sourceQualificationStatus());
        var audit = projection.snapshot();
        assertEquals("NOT_GRANTED_BY_OPERATOR", audit.path("sourceQualificationStatus").asText());
        for (String field : List.of("scientificQualificationGranted", "phaseQualificationGranted", "ejcQualificationGranted"))
            assertFalse(audit.path(field).asBoolean(), field);
        assertFalse(audit.has("appliedStrength"));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void declaredInExonInsertionHasNoAutomaticJunctionAtEitherInsertionEndpoint(int strand) throws Exception {
        var f = fixture(strand);var r = result(f, 11, 11, "TAA");
        assertEquals("CCATGAAACCCTAAGGGTTTGAATAAGGGTGA", r.alteredRna());
        assertEquals(12, firstStopRna1(r.alteredRna(), 3));
        var p = complete(f, r, 8, 20, 28);
        assertEquals(List.of(8, 20, 28), p.junctions0());assertEquals(28, p.lastJunctionRnaPosition0());
        assertEquals(4, p.exons().size());assertEquals(List.of(2), p.exons().get(1).referenceOrdinals());
        assertTrue(p.exons().get(1).hasUnassignedOrigin(), "The declaration does not create reference identity for inserted RNA");
        assertTrue(p.originSpans().stream().anyMatch(s -> s.alteredStart0() == 11 && s.alteredEnd0() == 14
                && s.referenceStart0() == null && s.referenceExonOrdinal() == null));
        var stop = p.classifyTriplet(12, 14);
        assertEquals(List.of(0, 0, 0), stop.referencePositions1());assertFalse(stop.crossesJunction());
        assertEquals(2, stop.endExon().alteredOrdinal());assertTrue(p.nativeStart().retainedContiguously());noQualification(p);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void nativeStopDeletionRetainsTheRealUtrAndActualRnaJunctions(int strand) throws Exception {
        var f = fixture(strand);var r = result(f, 20, 23, "");
        assertEquals("CCATGAAACCCGGGTTTGAAGGGTGA", r.alteredRna()); // MKPGFEG*
        assertEquals(26, r.alteredRna().length());assertEquals(24, firstStopRna1(r.alteredRna(), 3));
        var p = complete(f, r, 8, 17, 22);
        assertEquals("REMOVED_OR_REPLACED", p.nativeStop().status());assertNull(p.nativeStop().alteredStartRna1());
        assertEquals(List.of(0, 0, 0), p.nativeStop().alteredPositions1());assertEquals(22, p.lastJunctionRnaPosition0());
        assertEquals(List.of(27, 28, 29), p.classifyTriplet(24, 26).referencePositions1());
        assertEquals(4, p.classifyTriplet(24, 26).endExon().alteredOrdinal());noQualification(p);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void cdsUtrCrossingDeletionKeepsThePartiallyRetainedNativeStopDistinct(int strand) throws Exception {
        var f = fixture(strand);var r = result(f, 21, 24, "");
        assertEquals("CCATGAAACCCGGGTTTGAATGGTGA", r.alteredRna()); // MKPGFEW*
        assertEquals(24, firstStopRna1(r.alteredRna(), 3));
        var p = complete(f, r, 8, 17, 22);
        assertEquals("PARTIALLY_RETAINED", p.nativeStop().status());assertEquals(List.of(21, 0, 0), p.nativeStop().alteredPositions1());
        assertTrue(p.snapshot().at("/nativeStop/alteredPositions1/1").isNull());
        assertEquals(List.of(21, 25, 26), p.classifyTriplet(21, 23).referencePositions1());
        assertTrue(p.classifyTriplet(21, 23).crossesJunction());noQualification(p);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void startDeletionDoesNotChooseAnotherOriginAndDoesNotEraseKnownRnaTopology(int strand) throws Exception {
        var f = fixture(strand);var r = result(f, 2, 5, "");
        assertEquals("CCAAACCCGGGTTTGAATAAGGGTGA", r.alteredRna());
        var p = complete(f, r, 5, 14, 22);
        assertEquals("REMOVED_OR_REPLACED", p.nativeStart().status());assertNull(p.nativeStart().alteredStartRna1());
        assertEquals(List.of(0, 0, 0), p.nativeStart().alteredPositions1());
        assertEquals(22, p.lastJunctionRnaPosition0(), "Known RNA geometry is independent of whether an initiation mechanism exists");
        assertEquals(Boolean.FALSE, p.singleExon());assertFalse(p.snapshot().has("proteinSequence"));noQualification(p);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void wholeFirstExonRemovalIncludesItsUtrAndPreservesReferenceOrdinals(int strand) throws Exception {
        var f = fixture(strand);var r = result(f, 0, 8, "");
        assertEquals("CCCGGGTTTGAATAAGGGTGA", r.alteredRna());assertEquals(21, r.alteredRna().length());
        var p = complete(f, r, 9, 17);
        assertEquals(new Pvs1SpliceTopology.FullRnaExon(1, 0, 9, List.of(2), false), p.exons().get(0));
        assertEquals(List.of(3), p.exons().get(1).referenceOrdinals());assertEquals(List.of(4), p.exons().get(2).referenceOrdinals());
        assertNull(p.nativeStart().alteredStartRna1());assertEquals(17, p.lastJunctionRnaPosition0());noQualification(p);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void wholeLastExonRemovalDoesNotRetainItsOldJunctionOrInventATail(int strand) throws Exception {
        var f = fixture(strand);var r = result(f, 25, 29, "");
        assertEquals("CCATGAAACCCGGGTTTGAATAAGG", r.alteredRna());assertEquals(25, r.alteredRna().length());
        var p = complete(f, r, 8, 17);
        assertEquals(3, p.exons().size());assertEquals(17, p.lastJunctionRnaPosition0());
        assertEquals(List.of(21, 22, 23), p.nativeStop().alteredPositions1());assertTrue(p.nativeStop().retainedContiguously());noQualification(p);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void declaredRetainedIntronMergesTwoReferenceExonsWithoutOwnerDerivedEjcs(int strand) throws Exception {
        var f = fixture(strand);var r = result(f, 8, 8, "ACGT");
        assertEquals("CCATGAAAACGTCCCGGGTTTGAATAAGGGTGA", r.alteredRna());assertEquals(33, r.alteredRna().length());
        var p = complete(f, r, 21, 29);
        assertEquals(List.of(21, 29), p.junctions0());
        assertEquals(new Pvs1SpliceTopology.FullRnaExon(1, 0, 21, List.of(1, 2), true), p.exons().get(0));
        assertEquals(List.of(3), p.exons().get(1).referenceOrdinals());assertEquals(List.of(4), p.exons().get(2).referenceOrdinals());
        assertEquals(3, p.exons().size());noQualification(p);
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "empty", "knownSubset"})
    void unknownTopologyNeverPromotesKnownBoundariesOrUnknownOwnersToActualLastJunction(String kind) throws Exception {
        var f = fixture(1);var r = result(f, 11, 11, "TAA");
        var d = "absent".equals(kind) ? null : declaration(f, r, Pvs1SpliceTopology.RnaTopologyCompleteness.UNKNOWN,
                "knownSubset".equals(kind) ? List.of(8, 20, 28) : List.of());
        var p = Pvs1SpliceTopology.projectFullRna(r, d);
        assertEquals(Pvs1SpliceTopology.RnaTopologyCompleteness.UNKNOWN, p.completeness());
        assertNull(p.lastJunctionRnaPosition0());assertNull(p.singleExon());assertTrue(p.exons().isEmpty());
        assertFalse(p.originSpans().isEmpty());assertTrue(p.nativeStart().retainedContiguously());
        assertEquals("PVS1_SPLICE_FULL_RNA_TOPOLOGY_UNRESOLVED", assertThrows(IllegalArgumentException.class,
                () -> p.classifyTriplet(12, 14)).getMessage());noQualification(p);
    }

    @ParameterizedTest @ValueSource(strings = {"model", "reference", "altered", "result"})
    void everyDeclarationPinMustMatchTheActualOpaqueResult(String fault) throws Exception {
        var f = fixture(1);var r = result(f, 11, 11, "TAA");
        var d = declaration(f, r, Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE, List.of(8, 20, 28));String wrong = "0".repeat(64);
        var bad = new Pvs1SpliceTopology.RnaTopologyDeclaration("model".equals(fault) ? wrong : d.modelSha256(),
                "reference".equals(fault) ? wrong : d.referenceRnaSha256(), "altered".equals(fault) ? wrong : d.alteredRnaSha256(),
                "result".equals(fault) ? wrong : d.resultSha256(), d.completeness(), d.junctions0());
        assertEquals("PVS1_SPLICE_FULL_RNA_TOPOLOGY_DECLARATION_BINDING_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> Pvs1SpliceTopology.projectFullRna(r, bad)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"zero", "negative", "end", "beyond", "duplicate", "unsorted"})
    void invalidDeclaredBoundariesCannotCreateExons(String fault) throws Exception {
        var f = fixture(1);var r = apply(f, List.of());
        var junctions = switch (fault) {
            case "zero" -> List.of(0);case "negative" -> List.of(-1);case "end" -> List.of(29);
            case "beyond" -> List.of(30);case "duplicate" -> List.of(8, 8);case "unsorted" -> List.of(17, 8);
            default -> throw new AssertionError(fault);
        };
        assertEquals("PVS1_SPLICE_FULL_RNA_TOPOLOGY_DECLARATION_INVALID", assertThrows(IllegalArgumentException.class,
                () -> Pvs1SpliceTopology.projectFullRna(r, declaration(f, r, Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE, junctions))).getMessage());
    }

    @Test void aCompleteZeroJunctionDeclarationIsSingleExonAndAllReturnedStructuresAreImmutable() throws Exception {
        var f = fixture(1);var r = apply(f, List.of());var p = complete(f, r);
        assertEquals(Boolean.TRUE, p.singleExon());assertNull(p.lastJunctionRnaPosition0());assertEquals(1, p.exons().size());
        assertEquals(List.of(1, 2, 3, 4), p.exons().get(0).referenceOrdinals());
        assertThrows(UnsupportedOperationException.class, () -> p.junctions0().add(8));
        assertThrows(UnsupportedOperationException.class, () -> p.exons().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.originSpans().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.nativeStart().alteredPositions1().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.exons().get(0).referenceOrdinals().clear());
        var snapshot = p.snapshot();snapshot.put("sourceQualificationStatus", "CALLER_CHANGED");
        assertEquals("NOT_GRANTED_BY_OPERATOR", p.snapshot().path("sourceQualificationStatus").asText());
        assertEquals("1.1.0", Pvs1SpliceTopology.VERSION);assertEquals("1.0.0", Pvs1SpliceTopology.FULL_RNA_VERSION);noQualification(p);
    }

    @Test void aTripletCanCrossThreeDeclaredActualExonsAndRejectsInvalidRanges() throws Exception {
        var f = fixture(1);var r = apply(f, List.of());var p = complete(f, r, 11, 12);
        var triplet = p.classifyTriplet(11, 13);
        assertEquals(3, triplet.exons().size());assertTrue(triplet.crossesJunction());
        assertEquals(1, triplet.startExon().alteredOrdinal());assertEquals(3, triplet.endExon().alteredOrdinal());
        assertEquals(List.of(11, 12, 13), triplet.referencePositions1());
        for (int[] span : List.of(new int[]{0, 2}, new int[]{1, 4}, new int[]{28, 30}))
            assertEquals("PVS1_SPLICE_FULL_RNA_TOPOLOGY_TRIPLET_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> p.classifyTriplet(span[0], span[1])).getMessage());
        assertThrows(UnsupportedOperationException.class, () -> triplet.exons().clear());
    }

    @Test void nativeCodonRepartitioningIsNotContiguousRetention() throws Exception {
        var f = fixture(1);var r = result(f, 3, 3, "CCC");var p = complete(f, r, 11, 20, 28);
        assertEquals("REPARTITIONED", p.nativeStart().status());assertEquals(List.of(3, 7, 8), p.nativeStart().alteredPositions1());
        assertNull(p.nativeStart().alteredStartRna1());assertFalse(p.nativeStart().retainedContiguously());
        assertEquals(28, p.lastJunctionRnaPosition0());noQualification(p);
    }

    @Test void completeSnapshotBindingIncludesDuplicateSourceAliasesEvenWhenSequenceIsIdentical() throws Exception {
        var f = fixture(1);var first = result(f, 11, 11, "TAA");
        var d = declaration(f, first, Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE, List.of(8, 20, 28));
        var aliases = apply(f, List.of(edit(f, "one", 11, 11, "TAA"), edit(f, "two", 11, 11, "TAA")));
        assertEquals(first.alteredRna(), aliases.alteredRna());
        assertEquals("PVS1_SPLICE_FULL_RNA_TOPOLOGY_DECLARATION_BINDING_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> Pvs1SpliceTopology.projectFullRna(aliases, d)).getMessage());
        noQualification(complete(f, aliases, 8, 20, 28));
    }

    @Test void declarationCopiesCallerListAndEmptyRnaIsNotOneExon() throws Exception {
        var f = fixture(1);var r = apply(f, List.of());var positions = new ArrayList<>(List.of(8, 17, 25));
        var declaration = declaration(f, r, Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE, positions);positions.clear();
        assertEquals(List.of(8, 17, 25), Pvs1SpliceTopology.projectFullRna(r, declaration).junctions0());
        var empty = result(f, 0, 29, "");var projection = complete(f, empty);
        assertEquals(Boolean.FALSE, projection.singleExon());assertTrue(projection.exons().isEmpty());
        assertNull(projection.lastJunctionRnaPosition0());assertEquals("REMOVED_OR_REPLACED", projection.nativeStart().status());noQualification(projection);
    }
}
