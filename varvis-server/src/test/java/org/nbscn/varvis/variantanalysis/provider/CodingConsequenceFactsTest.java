package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent mechanical expectations fixed before implementing CodingConsequenceFacts.
 * R = ATG AAA CCC GGG TTT GAA TAA = MKPGFE*.
 * 1. RNA c.7_9del + retained DNA c.13T>C:
 *    ATG AAA GGG CTT GAA TAA = MKGLE*. One net AA shorter; only reference P3
 *    is a whole-codon deletion. F5 is changed/re-encoded, not a second deletion.
 * 2. DNA c.8del + RNA c.12_13del:
 *    ATG AAA CCG GTT GAA TAA = MKPVE*. Local frame shifts and then restores.
 *    One net AA shorter, ZERO whole reference codons deleted; fused P remains P.
 * 3. RNA c.7_9del + coding insertion CCC at reference boundary12:
 *    ATG AAA GGG CCC TTT GAA TAA = MKGPFE*. Changed protein, ZERO net AA loss.
 * 4. Repeat R = ATG AAA CCC CCC TAA. Delete c.7_9 and insert CCC at boundary12:
 *    exactly the original CDS and MKPP*. Keep deletion/insertion provenance, but
 *    no protein sequence change or net protein loss can be asserted.
 * 5. R = ATG AAA TCT GGT CAA GAA TAA. RNA c.8_13del:
 *    ATG AAA TAA GAA TAA => MK*. The new STOP is made from reference bases
 *    [7,14,15], before the still-retained native STOP. This is not a local-loss fraction.
 * 6. R = ATG AAA TAA. Insert CCC at reference boundary1:
 *    A CCC TG AAA TAA => ACC CTG AAA TAA. Original start bases map to [1,5,6],
 *    all surviving but noncontiguous; they do not form a retained start codon.
 * 7. Same R, insert CCTAAC at reference boundary4:
 *    ATG A CCTAAC AA TAA => ATG ACC TAA CAA TAA. First STOP occupies [7,8,9],
 *    while reference K2 bases map to [4,11,12], straddling but not touching STOP.
 * All references, edits, event/phase pins below are SYNTHETIC, never source approval.
 */
class CodingConsequenceFactsTest {
    private static final String CDS = "ATGAAACCCGGGTTTGAATAA";
    private static final String BINDING = "b".repeat(64), GROUP = "c".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter hashes = new RuntimeEvidenceV2Adapter(mapper);

    private ObjectNode context() {
        var c = mapper.createObjectNode();
        c.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "SYNTHETIC");
        c.putObject("transcript").put("accession", "NM_000001").put("version", 1);
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1");
        return c;
    }
    private ObjectNode model(String cds, int strand) {
        var m = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "SYNTHETIC_CONSEQUENCE").put("modelVersion", "1")
                .put("assembly", "GRCh38").put("contig", "1").put("strand", strand)
                .put("hgncId", "HGNC:1").put("geneSymbol", "SYNTHETIC")
                .put("transcript", "NM_000001.1").put("protein", "NP_000001.1")
                .put("cdsSequence", cds).put("cdsLength", cds.length()).put("proteinLength", cds.length() / 3 - 1);
        int cut = Math.min(9, cds.length() - 3), first = strand == 1 ? 100 : 200, second = strand == 1 ? 200 : 100;
        var exons = m.putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", first).put("genomicEnd", first + cut - 1).put("cdsStart", 1).put("cdsEnd", cut);
        exons.addObject().put("ordinal", 2).put("genomicStart", second).put("genomicEnd", second + cds.length() - cut - 1).put("cdsStart", cut + 1).put("cdsEnd", cds.length());
        m.putArray("sourceRecords").addObject().put("sourceId", "SYNTHETIC").put("version", "1")
                .put("url", "https://example.invalid/synthetic").put("sha256", "a".repeat(64));
        return m;
    }
    private CodingTranscriptEdits.Reference reference(String cds, int strand) {
        return reference(model(cds, strand), context());
    }
    private CodingTranscriptEdits.Reference reference(ObjectNode model, ObjectNode context) {
        return CodingTranscriptEdits.bind(mapper, context, model, hashes.canonicalSha256(model), CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()));
    }
    private CodingTranscriptEdits.Edit coding(CodingTranscriptEdits.Reference r, String id, int start, int end, String inserted) {
        return new CodingTranscriptEdits.Edit(id, CodingTranscriptEdits.Origin.CODING_ALLELE, start, end, inserted,
                r.cds().substring(start, end), BINDING, GROUP, 0);
    }
    private CodingTranscriptEdits.Edit rna(CodingTranscriptEdits.Reference r, int start, int end) {
        return new CodingTranscriptEdits.Edit("rna", CodingTranscriptEdits.Origin.SPLICE_PROJECTION, start, end, "",
                r.cds().substring(start, end), BINDING, GROUP, 0);
    }
    private ObjectNode derive(CodingTranscriptEdits.Reference r, List<CodingTranscriptEdits.Edit> edits) {
        return CodingConsequenceFacts.derive(mapper, r, CodingTranscriptEdits.apply(r, edits)).snapshot();
    }
    private JsonNode referenceEffect(JsonNode facts, int residue) {
        for (var row : facts.path("referenceResidueEffects")) if (row.path("startReferenceResidue").asInt() <= residue
                && residue <= row.path("endReferenceResidue").asInt()) return row;
        throw new AssertionError("Reference effect missing for " + residue);
    }
    private JsonNode alteredEffect(JsonNode facts, int codon) {
        for (var row : facts.path("alteredCodonEffects")) if (row.path("startAlteredCodon").asInt() <= codon
                && codon <= row.path("endAlteredCodon").asInt()) return row;
        throw new AssertionError("Altered codon effect missing for " + codon);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void snvAndRnaDeletionKeepDeletionAndReplacementDistinctOnBothStrands(int strand) {
        var r = reference(CDS, strand); var edits = List.of(rna(r, 6, 9), coding(r, "snv", 12, 13, "C"));
        var f = derive(r, edits);
        assertEquals("MKGLE*", f.path("alteredProteinThroughTermination").asText());
        assertEquals(-1, f.path("netTranslatedAminoAcidChange").asInt());
        assertEquals(1, f.path("netDeletedProteinResidues").asInt());
        assertEquals(1, f.path("inFrameNetDeletedProteinResidues").asInt());
        assertEquals(1, f.path("wholeDeletedReferenceCodons").asInt());
        assertEquals(mapper.valueToTree(List.of(new CodingConsequenceFacts.ReferenceResidueRange(3, 3))), f.path("wholeDeletedReferenceResidueRanges"));
        assertEquals(mapper.valueToTree(List.of(new CodingConsequenceFacts.ReferenceResidueRange(3, 3), new CodingConsequenceFacts.ReferenceResidueRange(5, 5))), f.path("touchedReferenceResidueRanges"));
        assertEquals("WHOLE_REFERENCE_CODON_DELETED", referenceEffect(f, 3).path("effect").asText());
        assertEquals("REFERENCE_CODON_PARTIALLY_DELETED_OR_REPLACED", referenceEffect(f, 5).path("effect").asText());
        assertEquals("NATIVE_STOP", f.at("/termination/kind").asText());
        assertTrue(f.path("referenceStartRetained").asBoolean());
        assertEquals(f, derive(r, List.of(edits.get(1), edits.get(0))));
        assertFalse(f.path("scientificQualificationGranted").asBoolean(true));
    }

    @Test void transientFrameRestorationHasNetLossWithoutWholeCodonDeletion() {
        var r = reference(CDS, 1); var f = derive(r, List.of(coding(r, "delete-one", 7, 8, ""), rna(r, 11, 13)));
        assertEquals("MKPVE*", f.path("alteredProteinThroughTermination").asText());
        assertEquals(1, f.path("netDeletedProteinResidues").asInt());
        assertEquals(0, f.path("wholeDeletedReferenceCodons").asInt());
        assertTrue(f.path("frameRestoredAfterTransientChange").asBoolean());
        assertEquals(2, f.at("/frameChanges/0/frameAfter").asInt());
        assertEquals(0, f.at("/frameChanges/1/frameAfter").asInt());
        assertEquals("FUSED_REFERENCE_CODON", alteredEffect(f, 3).path("effect").asText());
        assertEquals("P", f.path("alteredProteinThroughTermination").asText().substring(2, 3));
    }

    @Test void insertionAndDeletionMayChangeProteinWithZeroNetLengthLoss() {
        var r = reference(CDS, 1); var f = derive(r, List.of(rna(r, 6, 9), coding(r, "insert", 12, 12, "CCC")));
        assertEquals("MKGPFE*", f.path("alteredProteinThroughTermination").asText());
        assertTrue(f.path("proteinSequenceChanged").asBoolean());
        assertEquals(0, f.path("netDeletedProteinResidues").asInt());
        assertEquals(1, f.path("wholeDeletedReferenceCodons").asInt());
        assertEquals("INSERTED_CODON", alteredEffect(f, 4).path("effect").asText());
        assertTrue(alteredEffect(f, 4).at("/referencePositionsAtFirstCodon/0").isNull());
    }

    @Test void equivalentRepeatSequenceNeverTurnsMechanicalCodonLossIntoProteinLoss() {
        var r = reference("ATGAAACCCCCCTAA", 1);
        var f = derive(r, List.of(rna(r, 6, 9), coding(r, "repeat-copy", 12, 12, "CCC")));
        assertEquals("MKPP*", f.path("alteredProteinThroughTermination").asText());
        assertEquals(f.at("/binding/referenceCdsSha256"), f.at("/binding/alteredCdsSha256"));
        assertFalse(f.path("proteinSequenceChanged").asBoolean(true));
        assertEquals(0, f.path("netDeletedProteinResidues").asInt());
        assertEquals(1, f.path("wholeDeletedReferenceCodons").asInt());
        assertFalse(f.path("functionalQualificationGranted").asBoolean(true));
    }

    @Test void junctionPtcRetainsItsExactNoncontiguousReferenceBasesAndCannotBeLocalLoss() {
        var r = reference("ATGAAATCTGGTCAAGAATAA", 1); var f = derive(r, List.of(rna(r, 7, 13)));
        assertEquals("MK*", f.path("alteredProteinThroughTermination").asText());
        assertEquals("PREMATURE_STOP", f.at("/termination/kind").asText());
        assertEquals(mapper.valueToTree(List.of(7, 14, 15)), f.at("/termination/referencePositions"));
        assertEquals("OVERLAPS_FIRST_STOP", referenceEffect(f, 3).path("translationStatus").asText());
        assertEquals(7, f.at("/termination/alteredStart1").asInt());
        assertEquals(13, f.at("/nativeStop/alteredPositions/0").asInt());
        assertEquals(4, f.path("netDeletedProteinResidues").asInt());
        assertTrue(f.path("inFrameNetDeletedProteinResidues").isNull());
    }

    @Test void insertedStopPositionsAreNullAndAnEditedCdsCanResolveItsOwnExtension() {
        var r = reference("ATGAAATAA", 1);
        var f = derive(r, List.of(coding(r, "terminal-replacement", 6, 9, "CAATAG")));
        assertEquals("MKQ*", f.path("alteredProteinThroughTermination").asText());
        assertEquals("DOWNSTREAM_STOP_IN_DECLARED_SEQUENCE", f.at("/termination/kind").asText());
        assertEquals(1, f.path("netTranslatedAminoAcidChange").asInt());
        assertEquals(3, f.at("/termination/referencePositions").size());
        f.at("/termination/referencePositions").forEach(p -> assertTrue(p.isNull()));
        assertEquals("REENCODED_CODON", alteredEffect(f, 3).path("effect").asText());
        assertFalse(f.path("fullRnaOrEjcProofGranted").asBoolean(true));
    }

    @Test void aMissingStopDoesNotInventUtrSequenceOrACompleteProteinLength() {
        var r = reference("ATGAAATAA", 1); var f = derive(r, List.of(coding(r, "stop-loss", 6, 7, "C")));
        assertEquals("MKQ", f.path("alteredProteinThroughTermination").asText());
        assertEquals("NO_STOP_IN_DECLARED_CDS", f.at("/termination/kind").asText());
        assertTrue(f.path("alteredProteinLength").isNull());
        assertTrue(f.path("netDeletedProteinResidues").isNull());
        assertTrue(f.at("/termination/alteredStart1").isNull());
        assertEquals("CDS_ONLY", f.path("sequenceScope").asText());
    }

    @Test void removedStartDoesNotChooseADownstreamAtgAsFunctionalInitiation() {
        var r = reference("ATGAAAATGTAA", 1); var f = derive(r, List.of(rna(r, 0, 3)));
        assertEquals("KM*", f.path("alteredProteinThroughTermination").asText());
        assertFalse(f.path("referenceStartRetained").asBoolean(true));
        assertFalse(f.path("downstreamInitiationChosen").asBoolean(true));
        assertEquals("REFERENCE_READING_FRAME_FROM_FIRST_DECLARED_CDS_BASE", f.path("translationAssumption").asText());
        assertTrue(f.path("netDeletedProteinResidues").isNull());
        f.at("/startCodon/alteredPositions").forEach(p -> assertTrue(p.isNull()));
    }

    @Test void survivingStartBasesMustRemainContiguousToDescribeARetainedStartCodon() {
        var r = reference("ATGAAATAA", 1);
        var f = derive(r, List.of(coding(r, "split-start", 1, 1, "CCC")));
        assertEquals(CodingTranscriptMaterialSupply.sha("ACCCTGAAATAA"), f.at("/binding/alteredCdsSha256").asText());
        assertEquals("TLK*", f.path("alteredProteinThroughTermination").asText());
        assertEquals(mapper.valueToTree(List.of(1, 5, 6)), f.at("/startCodon/alteredPositions"));
        assertEquals("REFERENCE_START_BASES_RETAINED_NONCONTIGUOUS", f.at("/startCodon/status").asText());
        assertFalse(f.path("nativeStartRetained").asBoolean(true));
        assertFalse(f.path("referenceStartRetained").asBoolean(true));
        assertFalse(f.path("downstreamInitiationChosen").asBoolean(true));
        assertTrue(f.path("netDeletedProteinResidues").isNull());
        var offset = derive(r, List.of(coding(r, "before-start", 0, 0, "CCC")));
        assertEquals(mapper.valueToTree(List.of(4, 5, 6)), offset.at("/startCodon/alteredPositions"));
        assertEquals("REFERENCE_START_RETAINED_AT_DIFFERENT_OFFSET", offset.at("/startCodon/status").asText());
        assertFalse(offset.path("nativeStartRetained").asBoolean(true));
    }

    @Test void aReferenceCodonStraddlingAnInsertedStopDoesNotOverlapAnyStopBase() {
        var r = reference("ATGAAATAA", 1);
        var f = derive(r, List.of(coding(r, "straddle-stop", 4, 4, "CCTAAC")));
        assertEquals(CodingTranscriptMaterialSupply.sha("ATGACCTAACAATAA"), f.at("/binding/alteredCdsSha256").asText());
        assertEquals("MT*", f.path("alteredProteinThroughTermination").asText());
        assertEquals("PREMATURE_STOP", f.at("/termination/kind").asText());
        assertEquals(7, f.at("/termination/alteredStart1").asInt());
        assertEquals(9, f.at("/termination/alteredEnd1").asInt());
        assertEquals(mapper.valueToTree(List.of(4, 11, 12)), referenceEffect(f, 2).path("alteredPositionsAtFirstReferenceCodon"));
        assertEquals("STRADDLES_FIRST_STOP_WITHOUT_BASE_OVERLAP", referenceEffect(f, 2).path("translationStatus").asText());
        assertEquals("BEFORE_FIRST_STOP", referenceEffect(f, 1).path("translationStatus").asText());
        assertTrue(f.path("nativeStartRetained").asBoolean());
    }

    @Test void resultReferenceIdentityIsCheckedBeforeAnyFactsAreReturned() {
        var r = reference(CDS, 1); var result = CodingTranscriptEdits.apply(r, List.of());
        var changedModel = model(CDS, 1).put("modelVersion", "another");
        assertEquals("CODING_CONSEQUENCE_MODEL_BINDING_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> CodingConsequenceFacts.derive(mapper, reference(changedModel, context()), result)).getMessage());
        var changedContext = context().put("anotherContext", true);
        assertEquals("CODING_CONSEQUENCE_CONTEXT_BINDING_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> CodingConsequenceFacts.derive(mapper, reference(model(CDS, 1), changedContext), result)).getMessage());
    }

    @Test void aliasesAreNotAppliedTwiceButEveryOriginalBindingChangesTheWitness() {
        var r = reference(CDS, 1); var first = coding(r, "alias-a", 12, 13, "C"); var second = coding(r, "alias-b", 12, 13, "C");
        var single = derive(r, List.of(first)); var aliases = derive(r, List.of(second, first));
        assertEquals(single.path("alteredProteinThroughTermination"), aliases.path("alteredProteinThroughTermination"));
        assertNotEquals(single.at("/binding/editFactsSha256"), aliases.at("/binding/editFactsSha256"));
        assertNotEquals(hashes.canonicalSha256(single), hashes.canonicalSha256(aliases));
        assertEquals(single.path("mechanicalEffectSha256"), aliases.path("mechanicalEffectSha256"));
        assertEquals(1, aliases.at("/editCounts/applied").asInt());
        assertEquals(1, aliases.at("/editCounts/duplicateSequenceAliases").asInt());
        assertEquals(2, aliases.at("/editFacts/edits").size());
        assertEquals(hashes.canonicalSha256(aliases.path("editFacts")), aliases.at("/binding/editFactsSha256").asText());
        assertEquals("CODING_EDIT_DUPLICATE_EDIT_ID", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> CodingTranscriptEdits.apply(r, List.of(first, first))).reasonCode());
        var unphased = new CodingTranscriptEdits.Edit("unphased", first.origin(), 12, 13, "C", "T", BINDING, "", 0);
        assertEquals("CODING_EDIT_SAME_ALLELE_BINDING_REQUIRED", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> CodingTranscriptEdits.apply(r, List.of(first, unphased))).reasonCode());
    }

    @Test void evidenceReviewHashAndAliasIdsChangeFullFactsWithoutChangingMechanicalEffect() {
        var r = reference(CDS, 1); var edit = coding(r, "before-review", 12, 13, "C");
        var changedBinding = new CodingTranscriptEdits.Edit(edit.id(), edit.origin(), edit.referenceEditStart0(), edit.referenceEditEnd0(),
                edit.insertedSequence(), edit.expectedReference(), "d".repeat(64), edit.alleleGroupSha256(), edit.insertionExonOrdinal());
        var first = CodingConsequenceFacts.derive(mapper, r, CodingTranscriptEdits.apply(r, List.of(edit)));
        var reviewed = CodingConsequenceFacts.derive(mapper, r, CodingTranscriptEdits.apply(r, List.of(changedBinding)));
        assertNotEquals(first.canonicalSha256(), reviewed.canonicalSha256());
        assertEquals(first.mechanicalEffectIdentity(), reviewed.mechanicalEffectIdentity());
        assertEquals(first.mechanicalEffectSha256(), reviewed.mechanicalEffectSha256());
        var operation = first.mechanicalEffectIdentity().at("/appliedOperations/0");
        var fields = new java.util.HashSet<String>(); operation.fieldNames().forEachRemaining(fields::add);
        assertEquals(java.util.Set.of("origin", "start0", "end0", "expectedReference", "insertedSequence", "insertionExonOrdinal", "alleleGroupSha256"), fields);
        assertFalse(first.mechanicalEffectIdentity().toString().contains("evidenceBindingSha256"));
        assertFalse(first.mechanicalEffectIdentity().toString().contains("editFactsSha256"));
    }

    @Test void operationGroupModelAndContextRemainBoundInMechanicalIdentity() {
        var r = reference(CDS, 1); var edit = coding(r, "change", 12, 13, "C");
        var baseline = derive(r, List.of(edit)).path("mechanicalEffectSha256");
        assertNotEquals(baseline, derive(r, List.of(coding(r, "change", 12, 13, "A"))).path("mechanicalEffectSha256"));
        var group = new CodingTranscriptEdits.Edit(edit.id(), edit.origin(), edit.referenceEditStart0(), edit.referenceEditEnd0(),
                edit.insertedSequence(), edit.expectedReference(), edit.evidenceBindingSha256(), "e".repeat(64), edit.insertionExonOrdinal());
        assertNotEquals(baseline, derive(r, List.of(group)).path("mechanicalEffectSha256"));
        var otherModel = reference(model(CDS, 1).put("modelVersion", "other"), context());
        assertNotEquals(baseline, derive(otherModel, List.of(coding(otherModel, "change", 12, 13, "C"))).path("mechanicalEffectSha256"));
        var otherContext = reference(model(CDS, 1), context().put("alleleIdentityWitness", "different-context"));
        assertNotEquals(baseline, derive(otherContext, List.of(coding(otherContext, "change", 12, 13, "C"))).path("mechanicalEffectSha256"));
    }

    @Test void immutableSnapshotsCompactUnchangedCodonsAndRemainCanonicallySerializable() throws Exception {
        var r = reference(CDS, 1); var result = CodingTranscriptEdits.apply(r, List.of());
        var facts = CodingConsequenceFacts.derive(mapper, r, result); var before = facts.snapshot();
        assertEquals("1.0.1", before.path("methodVersion").asText());
        assertEquals("1.0.1", facts.mechanicalEffectIdentity().path("methodVersion").asText());
        assertEquals(hashes.canonicalSha256(before), facts.canonicalSha256());
        assertEquals(hashes.canonicalSha256(facts.mechanicalEffectIdentity()), facts.mechanicalEffectSha256());
        facts.mechanicalEffectIdentity().removeAll();
        assertFalse(facts.mechanicalEffectIdentity().isEmpty());
        assertTrue(facts.nativeStartRetained()); assertEquals("NATIVE_STOP", facts.firstStopKind());
        assertEquals(Boolean.FALSE, facts.proteinSequenceChanged());
        assertTrue(facts.wholeDeletedReferenceResidueRanges().isEmpty());
        assertTrue(facts.touchedReferenceResidueRanges().isEmpty());
        assertEquals(1, before.path("referenceResidueEffects").size());
        assertTrue(before.path("alteredCodonEffects").size() <= 2);
        assertFalse(before.path("proteinSequenceChanged").asBoolean(true));
        facts.snapshot().removeAll(); assertEquals(before, facts.snapshot());
        assertEquals(hashes.canonicalSha256(before), hashes.canonicalSha256(mapper.readTree(mapper.writeValueAsBytes(before))));
        assertFalse(before.toString().contains("\"strength\""));
    }
}
