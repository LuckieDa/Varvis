package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent sequence/mapping oracles, fixed before running the RNA core.
 * R = CC ATG AAA CCC GGG TTT GAA TAA GGG TGA, length29, reference CDS RNA3..23.
 * b8 + GCT => CC ATG AAA GCT CCC GGG TTT GAA TAA GGG TGA, new bases9..11.
 * [2,5) removes ATG; [20,23) removes native TAA but retains all GGGTGA UTR.
 * [0,2) + [23,29) removes only the two UTRs, leaving the original 21nt CDS.
 * [8,11) RNA deletion plus actual c5A>G DNA => CC ATG AGA GGG TTT GAA TAA GGG TGA.
 * No declared grouping/hash below is phase, RNA-assay, topology, or scientific qualification.
 */
class CodingTranscriptRnaEditsTest {
    @TempDir Path root;
    private static final String RNA = CodingTranscriptRnaFixture.DEFAULT_RNA, EVIDENCE = "b".repeat(64);
    private CodingTranscriptRnaFixture fixture(int strand) throws Exception { return new CodingTranscriptRnaFixture(root, strand); }
    private CodingTranscriptEdits.RnaReference reference(CodingTranscriptRnaFixture f) { return CodingTranscriptEdits.bindRna(f.mapper, f.context, f.token); }
    private String group(CodingTranscriptRnaFixture f) { return f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele")); }
    private CodingTranscriptEdits.Edit edit(CodingTranscriptRnaFixture f, String id, CodingTranscriptEdits.Origin origin,
            int start, int end, String inserted, int owner) {
        return new CodingTranscriptEdits.Edit(id, origin, start, end, inserted, RNA.substring(start, end), EVIDENCE, group(f), owner);
    }
    private CodingTranscriptEdits.Edit rna(CodingTranscriptRnaFixture f, String id, int start, int end, String inserted) {
        return edit(f, id, CodingTranscriptEdits.Origin.SPLICE_PROJECTION, start, end, inserted, 0);
    }
    private CodingTranscriptEdits.RnaEditSet set(CodingTranscriptRnaFixture f, List<CodingTranscriptEdits.Edit> edits) {
        return new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES, f.token.referenceRnaSha256(),
                CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, edits);
    }
    private CodingTranscriptEdits.RnaResult apply(CodingTranscriptRnaFixture f, List<CodingTranscriptEdits.Edit> edits) {
        return CodingTranscriptEdits.applyRna(reference(f), set(f, edits));
    }
    private void rejected(String code, Runnable action) {
        assertEquals("CODING_EDIT_" + code, assertThrows(CodingTranscriptEdits.Rejected.class, action::run).reasonCode());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void genuineOpaqueReferenceKeepsTheFullRnaAndCdsCoordinateScopesDistinct(int strand) throws Exception {
        var f = fixture(strand); var r = reference(f);
        assertEquals(RNA, r.referenceRna()); assertEquals(29, r.referenceRna().length());
        assertSame(f.token, r.material()); assertEquals(2, r.cdsStartRna0()); assertEquals(23, r.cdsEndRna0());
        assertEquals("ATGAAACCCGGGTTTGAATAA", r.referenceRna().substring(r.cdsStartRna0(), r.cdsEndRna0()));
        rejected("RNA_COORDINATE_SCOPE_REQUIRED", () -> new CodingTranscriptEdits.RnaEditSet("REFERENCE_CDS_0_BASED_HALF_OPEN",
                f.token.referenceRnaSha256(), CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, List.of()));
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void spliceInsertionAtAnExonBoundaryHasUnknownBaseAndOwnerOriginsWithoutInventingAJunction(int strand) throws Exception {
        var f = fixture(strand); var result = apply(f, List.of(rna(f, "insert", 8, 8, "GCT")));
        assertEquals("CCATGAAAGCTCCCGGGTTTGAATAAGGGTGA", result.alteredRna()); assertEquals(3, result.delta());
        for (int i = 9; i <= 11; i++) { assertEquals(0, result.alteredToReferenceRnaBase(i)); assertEquals(0, result.alteredReferenceExonOrdinal(i)); }
        assertEquals(8, result.alteredToReferenceRnaBase(8)); assertEquals(9, result.alteredToReferenceRnaBase(12));
        assertEquals(12, result.referenceRnaToAlteredBase(9)); assertEquals(1, result.alteredReferenceExonOrdinal(8));
        assertEquals(2, result.alteredReferenceExonOrdinal(12));
        assertEquals(8, result.mapReferenceRnaBoundary(8, CodingTranscriptEdits.Bias.BEFORE));
        assertEquals(11, result.mapReferenceRnaBoundary(8, CodingTranscriptEdits.Bias.AFTER));
        var snapshot = result.snapshot(f.mapper);
        assertEquals("NOT_DERIVED_FROM_SEQUENCE_EDITS", snapshot.path("topologyStatus").asText());
        assertTrue(snapshot.path("lastJunctionRnaPosition").isNull());
        var inserted = snapshot.withArray("originSpans").get(1);
        assertEquals(8, inserted.path("alteredStart0").asInt()); assertEquals(11, inserted.path("alteredEnd0").asInt());
        assertTrue(inserted.path("referenceStart0").isNull()); assertTrue(inserted.path("referenceExonOrdinal").isNull());
        assertFalse(snapshot.path("scientificQualificationGranted").asBoolean(true)); assertFalse(snapshot.path("phaseQualificationGranted").asBoolean(true));
        rejected("RNA_INSERTION_OWNER_REQUIRES_TOPOLOGY_SOURCE", () -> apply(f,
                List.of(edit(f, "self-owned", CodingTranscriptEdits.Origin.SPLICE_PROJECTION, 8, 8, "GCT", 1))));
    }
    @ParameterizedTest @CsvSource({"1,start", "-1,start", "1,stop", "-1,stop", "1,bothUtrs", "-1,bothUtrs",
            "1,startBoundary", "-1,startBoundary", "1,stopBoundary", "-1,stopBoundary"})
    void exactRnaIntervalsCanRemoveStartStopAndCdsUtrBoundariesWithoutPretendingTheyAreCds(int strand, String vector) throws Exception {
        var f = fixture(strand); List<CodingTranscriptEdits.Edit> edits; String expected;
        switch (vector) {
            case "start" -> { edits = List.of(rna(f, "start", 2, 5, "")); expected = "CCAAACCCGGGTTTGAATAAGGGTGA"; }
            case "stop" -> { edits = List.of(rna(f, "stop", 20, 23, "")); expected = "CCATGAAACCCGGGTTTGAAGGGTGA"; }
            case "bothUtrs" -> { edits = List.of(rna(f, "left", 0, 2, ""), rna(f, "right", 23, 29, "")); expected = "ATGAAACCCGGGTTTGAATAA"; }
            case "startBoundary" -> { edits = List.of(rna(f, "left-boundary", 1, 4, "")); expected = "CGAAACCCGGGTTTGAATAAGGGTGA"; }
            case "stopBoundary" -> { edits = List.of(rna(f, "right-boundary", 22, 25, "")); expected = "CCATGAAACCCGGGTTTGAATAGTGA"; }
            default -> throw new AssertionError(vector);
        }
        var result = apply(f, edits); assertEquals(expected, result.alteredRna());
        assertEquals(CodingTranscriptMaterialSupply.sha(expected), result.alteredRnaSha256());
        for (var edit : edits) for (int p = edit.referenceEditStart0() + 1; p <= edit.referenceEditEnd0(); p++) assertEquals(0, result.referenceRnaToAlteredBase(p));
        assertEquals(RNA, reference(f).referenceRna()); assertEquals(2, reference(f).cdsStartRna0());
    }
    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "TRANS"})
    void groupingMustBeDeclaredSinglePhysicalAlleleButStillGrantsNoPhaseQualification(String grouping) throws Exception {
        var f = fixture(1); var set = new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES,
                f.token.referenceRnaSha256(), CodingTranscriptEdits.RnaGrouping.valueOf(grouping), List.of(rna(f, "event", 8, 11, "")));
        rejected("RNA_PHASE_SCOPE_UNRESOLVED_OR_TRANS", () -> CodingTranscriptEdits.applyRna(reference(f), set));
    }
    @Test void wrongContextNullOrForgedTokensAndWrongReferenceHashCannotBind() throws Exception {
        var f = fixture(1); var wrong = f.context.deepCopy().put("differentBackground", true);
        rejected("RNA_CONTEXT_BINDING_MISMATCH", () -> CodingTranscriptEdits.bindRna(f.mapper, wrong, f.token));
        rejected("RNA_VERIFIED_MATERIAL_REQUIRED", () -> CodingTranscriptEdits.bindRna(f.mapper, f.context, null));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> f.mapper.treeToValue(f.token.snapshot(), CodingTranscriptMaterialSupply.VerifiedRna.class));
        var wrongHash = new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES, "f".repeat(64),
                CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, List.of());
        rejected("RNA_REFERENCE_BINDING_MISMATCH", () -> CodingTranscriptEdits.applyRna(reference(f), wrongHash));
    }
    @Test void aliasesAreReducedOnlyAfterEveryOriginalGroupAndIdIsChecked() throws Exception {
        var f = fixture(1); var a = rna(f, "a", 8, 11, ""); var b = rna(f, "b", 8, 11, "");
        var result = apply(f, List.of(a, b)); assertEquals(1, result.appliedEdits().size());
        assertEquals("CCATGAAAGGGTTTGAATAAGGGTGA", result.alteredRna());
        assertEquals("DUPLICATE_SEQUENCE_EDIT", result.snapshot(f.mapper).at("/edits/1/status").asText());
        rejected("DUPLICATE_EDIT_ID", () -> apply(f, List.of(a, a)));
        for (String group : List.of("", "e".repeat(64))) {
            var rebound = new CodingTranscriptEdits.Edit(b.id(), b.origin(), 8, 11, "", "CCC", EVIDENCE, group, 0);
            rejected("RNA_ALLELE_GROUP_BINDING_MISMATCH", () -> apply(f, List.of(a, rebound)));
        }
        var reversed = apply(f, List.of(b, a)); assertEquals(result.snapshot(f.mapper), reversed.snapshot(f.mapper));
    }
    @Test void partialOverlapAndCodingInsertionsAtEitherRnaDeletionBoundaryAreRejected() throws Exception {
        var f = fixture(1); var deletion = rna(f, "rna", 8, 14, "");
        rejected("COMPOSITE_EDIT_PARTIAL_OVERLAP", () -> apply(f, List.of(deletion,
                edit(f, "coding", CodingTranscriptEdits.Origin.CODING_ALLELE, 12, 15, "A", 2))));
        for (int boundary : List.of(8, 14)) rejected("COMPOSITE_INSERTION_BOUNDARY_AMBIGUOUS", () -> apply(f, List.of(deletion,
                edit(f, "coding", CodingTranscriptEdits.Origin.CODING_ALLELE, boundary, boundary, "A", 2))));
        rejected("OVERLAPPING_EDITS", () -> apply(f, List.of(rna(f, "first", 8, 11, ""), rna(f, "second", 10, 12, ""))));
    }
    @Test void aCodingChangeRemovedByRnaIsRetainedAsAnInputButNeverAppliedAgain() throws Exception {
        var f = fixture(1); var result = apply(f, List.of(rna(f, "rna", 8, 11, ""),
                edit(f, "coding", CodingTranscriptEdits.Origin.CODING_ALLELE, 9, 10, "A", 2)));
        assertEquals("CCATGAAAGGGTTTGAATAAGGGTGA", result.alteredRna()); assertEquals(1, result.appliedEdits().size());
        assertEquals("REMOVED_BY_SPLICE", result.snapshot(f.mapper).at("/edits/0/status").asText());
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void completeProductIsOnlyAWitnessAfterActualDnaAndRnaOperationsAreAppliedOnce(int strand) throws Exception {
        var f = fixture(strand); var r = reference(f); var allele = f.context.at("/variant/evidenceQueryAllele");
        var dna = CodingTranscriptEdits.projectRnaAllele(r, allele, group(f));
        assertEquals(6, dna.referenceEditStart0()); assertEquals(7, dna.referenceEditEnd0()); assertEquals("G", dna.insertedSequence());
        String expected = "CCATGAGAGGGTTTGAATAAGGGTGA";
        var set = new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES, f.token.referenceRnaSha256(),
                CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, List.of(dna, rna(f, "rna", 8, 11, "")), expected);
        var result = CodingTranscriptEdits.applyRna(r, set); assertEquals(expected, result.alteredRna()); assertEquals(2, result.appliedEdits().size());
        assertTrue(result.snapshot(f.mapper).path("completeAllelicRnaWitnessVerified").asBoolean());
        var wrong = new CodingTranscriptEdits.RnaEditSet(set.coordinateSystem(), set.referenceRnaSha256(), set.grouping(), set.edits(), RNA);
        rejected("RNA_COMPLETE_PRODUCT_WITNESS_MISMATCH", () -> CodingTranscriptEdits.applyRna(r, wrong));
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void genomicAllelesInBothUtrsProjectToRealRnaCoordinatesOnBothStrands(int strand) throws Exception {
        var f = fixture(strand).alleleAtRna(1, "C", "T"); var r = reference(f);
        var first = CodingTranscriptEdits.projectRnaAllele(r, f.context.at("/variant/evidenceQueryAllele"), group(f));
        assertEquals(0, first.referenceEditStart0()); assertEquals(1, first.referenceEditEnd0()); assertEquals("C", first.expectedReference());
        assertEquals("TCATGAAACCCGGGTTTGAATAAGGGTGA", apply(f, List.of(first)).alteredRna());
        int genomic = f.context.at("/variant/evidenceQueryAllele/position").asInt();
        assertEquals(1, CodingTranscriptEdits.referenceRnaPositionForGenomicBase(r, genomic));
        f.alleleAtRna(29, "A", "C"); r = reference(f);
        var last = CodingTranscriptEdits.projectRnaAllele(r, f.context.at("/variant/evidenceQueryAllele"), group(f));
        assertEquals(28, last.referenceEditStart0()); assertEquals(29, last.referenceEditEnd0()); assertEquals("A", last.expectedReference());
        assertEquals("CCATGAAACCCGGGTTTGAATAAGGGTGC", apply(f, List.of(last)).alteredRna());
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void aFullyReadGenomicAlleleCrossingAnIntronCannotMasqueradeAsAnRnaDeletion(int strand) throws Exception {
        var f = fixture(strand); var model = f.model.deepCopy();
        for (int i = 0; i < 4; i++) {
            var exon = (ObjectNode) model.path("exons").get(i); int length = exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt() + 1;
            int lo = strand == 1 ? 100 + i * 20 : 160 - i * 20; exon.put("genomicStart", lo).put("genomicEnd", lo + length - 1);
        }
        f.configure(model, RNA);
        int first = strand == 1 ? 107 : 148, last = strand == 1 ? 120 : 160;
        f.withGenomicReference(first + 1, "A".repeat(last - first - 1));
        String ref = strand == 1 ? "A".repeat(13) + "C" : "G" + "A".repeat(11) + "T";
        f.alleleAtGenomic(first, ref, ref.substring(0, 1)); var r = reference(f);
        assertEquals(0, CodingTranscriptEdits.referenceRnaPositionForGenomicBase(r, first + 1));
        rejected("RNA_GENOMIC_BOUNDARY_REQUIRES_SOURCE_OUTCOME", () -> CodingTranscriptEdits.projectRnaAllele(r,
                f.context.at("/variant/evidenceQueryAllele"), group(f)));
    }
    @Test void sequenceReferenceAndMappingBoundsAreCheckedAndSnapshotsStayImmutable() throws Exception {
        var f = fixture(1); var result = apply(f, List.of(rna(f, "replace", 0, 2, "A")));
        assertEquals(0, result.alteredToReferenceRnaBase(0)); assertEquals(0, result.alteredToReferenceRnaBase(100));
        assertEquals(0, result.referenceRnaToAlteredBase(1)); assertEquals(0, result.referenceRnaToAlteredBase(30));
        assertEquals(-1, result.mapReferenceRnaBoundary(1, CodingTranscriptEdits.Bias.BEFORE));
        rejected("RNA_BOUNDARY_OUTSIDE_VERIFIED_REFERENCE", () -> result.mapReferenceRnaBoundary(30, CodingTranscriptEdits.Bias.BEFORE));
        rejected("EDIT_REFERENCE_MISMATCH", () -> apply(f, List.of(new CodingTranscriptEdits.Edit("bad", CodingTranscriptEdits.Origin.SPLICE_PROJECTION,
                0, 1, "", "A", EVIDENCE, group(f), 0))));
        var snapshot = result.snapshot(f.mapper); var before = snapshot.deepCopy(); snapshot.removeAll();
        assertEquals(before, result.snapshot(f.mapper)); assertEquals(before, f.mapper.readTree(f.mapper.writeValueAsBytes(before)));
        assertThrows(UnsupportedOperationException.class, () -> result.appliedEdits().clear());
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void pureGenomicDeletionKeepsAnEmptyInsertedDeltaOnEitherStrand(int strand) throws Exception {
        var f = fixture(strand).alleleAtRna(11, "CG", "C");
        var edit = CodingTranscriptEdits.projectRnaAllele(reference(f), f.context.at("/variant/evidenceQueryAllele"), group(f));
        assertEquals(11, edit.referenceEditStart0());assertEquals(12, edit.referenceEditEnd0());
        assertEquals("G", edit.expectedReference());assertEquals("", edit.insertedSequence());
        assertEquals("CCATGAAACCCGGTTTGAATAAGGGTGA", apply(f, List.of(edit)).alteredRna());
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void pureGenomicInsertionKeepsAnEmptyDeletedDeltaOnEitherStrand(int strand) throws Exception {
        var f = fixture(strand).alleleAtRna(10, "C", "CT");
        var edit = CodingTranscriptEdits.projectRnaAllele(reference(f), f.context.at("/variant/evidenceQueryAllele"), group(f));
        assertEquals(10, edit.referenceEditStart0());assertEquals(10, edit.referenceEditEnd0());
        assertEquals("", edit.expectedReference());assertEquals("T", edit.insertedSequence());
        assertEquals("CCATGAAACCTCGGGTTTGAATAAGGGTGA", apply(f, List.of(edit)).alteredRna());
    }
    @Test void conflictingAliasPreservesEveryOriginalBindingOnRejection() throws Exception {
        var f = new CodingTranscriptRnaFixture(root, 1);
        var reference = CodingTranscriptEdits.bindRna(f.mapper, f.context, f.token);
        String group = f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele"));
        var first = new CodingTranscriptEdits.Edit("first", CodingTranscriptEdits.Origin.SPLICE_PROJECTION, 8, 11, "", "CCC", "b".repeat(64), group, 0);
        var conflicting = new CodingTranscriptEdits.Edit("alias", CodingTranscriptEdits.Origin.SPLICE_PROJECTION, 8, 11, "", "CCC", "c".repeat(64), "d".repeat(64), 0);
        var set = new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES, f.token.referenceRnaSha256(),
                CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, List.of(first, conflicting));
        var rejected = assertThrows(CodingTranscriptEdits.Rejected.class, () -> CodingTranscriptEdits.applyRna(reference, set));
        assertEquals("CODING_EDIT_RNA_ALLELE_GROUP_BINDING_MISMATCH", rejected.reasonCode());
        assertEquals(List.of(first, conflicting), rejected.inputBindings());
        assertEquals("GROUP_CONFLICT_WITH_CURRENT_ALLELE", rejected.bindingStatus());
        assertFalse(rejected.snapshot(f.mapper).path("partialResultAvailable").asBoolean());
    }

}
