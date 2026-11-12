package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independently hand-counted complete RNA vectors, synthetic trusted-reference seam only.
 * R = CC + ATG AAA CCC GGG TTT GAA TAA + GGG TGA, CDS RNA3..23.
 * Delete[20,23) => MKPGFEG*; delete[21,24) => MKPGFEW*, both new STOP r24..26.
 * Insert TAA at b11 => MKP*, all STOP bases inserted at r12..14.
 * Delete ATG[2,5) or split it with insertion b3 => no alternative initiation selected.
 */
class CodingConsequenceRnaFactsTest {
    @TempDir Path root;
    private CodingTranscriptEdits.RnaResult edit(CodingTranscriptRnaFixture f, int start, int end, String insert, String evidence) {
        var reference = CodingTranscriptEdits.bindRna(f.mapper, f.context, f.token);
        String group = f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele"));
        var op = new CodingTranscriptEdits.Edit("TEST_ONLY_RNA", CodingTranscriptEdits.Origin.SPLICE_PROJECTION,
                start, end, insert, reference.referenceRna().substring(start, end), evidence, group, 0);
        return CodingTranscriptEdits.applyRna(reference, new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES,
                f.token.referenceRnaSha256(), CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, List.of(op)));
    }
    private CodingConsequenceFacts.RnaFacts derive(CodingTranscriptRnaFixture f, CodingTranscriptEdits.RnaResult result) {
        return CodingConsequenceFacts.deriveRna(f.mapper, f.context, result);
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void nativeStopDeletionScansVerifiedThreePrimeRnaInsteadOfTruncatedCds(int strand) throws Exception {
        var f=new CodingTranscriptRnaFixture(root,strand);var facts=derive(f,edit(f,20,23,"","b".repeat(64)));var s=facts.snapshot();
        assertTrue(facts.nativeStartRetained());assertEquals("MKPGFEG*",s.path("alteredProteinThroughFirstTermination").asText());
        assertEquals("REMOVED_OR_REPLACED",s.at("/nativeStop/status").asText());
        assertEquals("NON_NATIVE_STOP_REFERENCE_STOP_NOT_RETAINED",facts.firstStopKind());
        assertEquals(24,s.at("/firstStop/alteredStartRna1").asInt());assertEquals(26,s.at("/firstStop/alteredEndRna1").asInt());
        assertEquals(1,s.path("netProteinLengthChange").asInt());
        for(var base:s.at("/firstStop/bases")) assertEquals("REFERENCE_UTR",base.path("origin").asText());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void crossCdsUtrDeletionKeepsPartialNativeStopIdentity(int strand) throws Exception {
        var f=new CodingTranscriptRnaFixture(root,strand);var s=derive(f,edit(f,21,24,"","b".repeat(64))).snapshot();
        assertEquals("MKPGFEW*",s.path("alteredProteinThroughFirstTermination").asText());
        assertEquals("PARTIALLY_RETAINED",s.at("/nativeStop/status").asText());
        assertEquals(21,s.at("/nativeStop/alteredPositions1/0").asInt());assertTrue(s.at("/nativeStop/alteredPositions1/1").isNull());
        assertEquals(24,s.at("/firstStop/alteredStartRna1").asInt());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void allInsertedStopRetainsNullReferenceAndNoAutomaticNmd(int strand) throws Exception {
        var f=new CodingTranscriptRnaFixture(root,strand);var s=derive(f,edit(f,11,11,"TAA","b".repeat(64))).snapshot();
        assertEquals("MKP*",s.path("alteredProteinThroughFirstTermination").asText());assertEquals(-3,s.path("netProteinLengthChange").asInt());
        assertEquals(12,s.at("/firstStop/alteredStartRna1").asInt());assertEquals("INSERTED_OR_REPLACEMENT_BASES",s.at("/firstStop/baseOrigin").asText());
        for(var position:s.at("/firstStop/referenceRnaPositions1")) assertTrue(position.isNull());
        assertEquals("NON_NATIVE_STOP_BEFORE_RETAINED_REFERENCE_STOP",s.at("/firstStop/kind").asText());
        assertFalse(s.path("scientificQualificationGranted").asBoolean());assertFalse(s.path("topologyQualificationGranted").asBoolean());assertFalse(s.has("nmdPrediction"));
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void startRemovalDoesNotUseRemainingRnaAsAnAlternativeCds(int strand) throws Exception {
        var f=new CodingTranscriptRnaFixture(root,strand);var facts=derive(f,edit(f,2,5,"","b".repeat(64)));var s=facts.snapshot();
        assertFalse(facts.nativeStartRetained());assertEquals(26,s.path("alteredRnaLength").asInt());
        assertTrue(s.path("firstStop").isNull());assertTrue(s.path("alteredProteinLength").isNull());assertTrue(s.path("alteredProteinThroughFirstTermination").isNull());
        assertEquals("REFERENCE_START_NOT_RETAINED_NO_ALTERNATE_INITIATION_SELECTED",s.path("translationStatus").asText());
    }
    @Test void survivingButFragmentedAtgIsNotAnInitiationAnchor() throws Exception {
        var f=new CodingTranscriptRnaFixture(root,1);var facts=derive(f,edit(f,3,3,"CCC","b".repeat(64)));var s=facts.snapshot();
        assertFalse(facts.nativeStartRetained());assertEquals("BASES_SURVIVE_NONCONTIGUOUS",s.at("/nativeStart/status").asText());
        assertEquals("[3,7,8]",s.at("/nativeStart/alteredPositions1").toString());assertTrue(s.path("firstStop").isNull());
    }
    @Test void fivePrimeUtrInsertionMovesTheSameNativeAnchor() throws Exception {
        var f=new CodingTranscriptRnaFixture(root,1);var facts=derive(f,edit(f,1,1,"AAA","b".repeat(64)));var s=facts.snapshot();
        assertTrue(facts.nativeStartRetained());assertEquals(6,s.path("alteredStartRna1").asInt());
        assertEquals("MKPGFE*",s.path("alteredProteinThroughFirstTermination").asText());assertEquals("NATIVE_REFERENCE_STOP",facts.firstStopKind());assertEquals(0,s.path("netProteinLengthChange").asInt());
    }
    @Test void noStopWithinDeclaredRnaDoesNotInventATerminalCodon() throws Exception {
        var f=new CodingTranscriptRnaFixture(root,1);var facts=derive(f,edit(f,20,29,"","b".repeat(64)));var s=facts.snapshot();
        assertEquals("MKPGFE",s.path("alteredProteinThroughFirstTermination").asText());assertEquals("NO_STOP_WITHIN_DECLARED_ALTERED_RNA",facts.firstStopKind());
        assertTrue(s.path("alteredProteinLength").isNull());assertTrue(s.path("netProteinLengthChange").isNull());
    }
    @Test void bindingsAndReviewIndependentMechanicalHashRemainSeparate() throws Exception {
        var f=new CodingTranscriptRnaFixture(root,1);var a=derive(f,edit(f,20,23,"","b".repeat(64)));var b=derive(f,edit(f,20,23,"","c".repeat(64)));
        assertEquals(a.mechanicalEffectSha256(),b.mechanicalEffectSha256());assertNotEquals(a.canonicalSha256(),b.canonicalSha256());
        var snapshot=a.snapshot();snapshot.put("scientificQualificationGranted",true);assertFalse(a.snapshot().path("scientificQualificationGranted").asBoolean());
        ObjectNode wrong=f.context.deepCopy().put("hypothesisId","WRONG");
        assertEquals("CODING_CONSEQUENCE_RNA_CONTEXT_BINDING_MISMATCH",assertThrows(IllegalArgumentException.class,
                ()->CodingConsequenceFacts.deriveRna(f.mapper,wrong,edit(f,20,23,"","b".repeat(64)))).getMessage());
    }
    private CodingTranscriptEdits.RnaResult operations(CodingTranscriptRnaFixture f, List<CodingTranscriptEdits.Edit> edits) {
        var reference = CodingTranscriptEdits.bindRna(f.mapper, f.context, f.token);
        return CodingTranscriptEdits.applyRna(reference, new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES,
                f.token.referenceRnaSha256(), CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE, edits));
    }
    private CodingTranscriptEdits.Edit rna(CodingTranscriptRnaFixture f, String id, int start, int end, String insert) {
        String group = f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele"));
        return new CodingTranscriptEdits.Edit(id, CodingTranscriptEdits.Origin.SPLICE_PROJECTION, start, end, insert,
                f.token.referenceRnaSequence().substring(start, end), "b".repeat(64), group, 0);
    }
    @Test void utrOnlyLengthChangeIsNotACodingFrameshift() throws Exception {
        var f = new CodingTranscriptRnaFixture(root, 1);
        var result = edit(f, 1, 1, "A", "b".repeat(64));var s = derive(f, result).snapshot();
        assertEquals(1, result.delta());assertEquals("MKPGFE*", s.path("alteredProteinThroughFirstTermination").asText());
        assertEquals(0, s.at("/codingEffects/referenceCdsIntervalLengthChange").asInt());
        assertEquals(0, s.at("/codingEffects/frameOffsetAtRetainedNativeStop").asInt());
        assertTrue(s.at("/codingEffects/localInFrameLengthChangeResolved").asBoolean());
        assertTrue(s.at("/codingEffects/touchedReferenceResidueRanges").isEmpty());
    }
    @Test void zeroTotalRnaDeltaCanHideAnActualCodingFrameshift() throws Exception {
        var f = new CodingTranscriptRnaFixture(root, 1);
        var result = operations(f, List.of(rna(f, "coding-insert", 5, 5, "A"), rna(f, "utr-delete", 23, 24, "")));
        var s = derive(f, result).snapshot();assertEquals(0, result.delta());
        assertEquals("MKTRV*", s.path("alteredProteinThroughFirstTermination").asText());
        assertEquals(1, s.at("/codingEffects/referenceCdsIntervalLengthChange").asInt());
        assertEquals(1, s.at("/codingEffects/frameOffsetAtRetainedNativeStop").asInt());
        assertFalse(s.at("/codingEffects/localInFrameLengthChangeResolved").asBoolean());
        assertEquals(18, s.at("/firstStop/alteredStartRna1").asInt());assertEquals(20, s.at("/firstStop/alteredEndRna1").asInt());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void fullRnaCodingEffectsSeparatePureDeletionFromRetainedReplacement(int strand) throws Exception {
        var f = new CodingTranscriptRnaFixture(root, strand).alleleAtRna(15, "T", "C");
        var reference = CodingTranscriptEdits.bindRna(f.mapper, f.context, f.token);
        String group = f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele"));
        var dna = CodingTranscriptEdits.projectRnaAllele(reference, f.context.at("/variant/evidenceQueryAllele"), group);
        var result = operations(f, List.of(rna(f, "splice", 8, 11, ""), dna));var s = derive(f, result).snapshot();
        assertEquals("MKGLE*", s.path("alteredProteinThroughFirstTermination").asText());
        assertEquals(-1, s.path("netProteinLengthChange").asInt());assertEquals(1, s.at("/codingEffects/wholeDeletedReferenceCodons").asInt());
        assertEquals("[{\"start\":3,\"end\":3}]", s.at("/codingEffects/wholeDeletedReferenceResidueRanges").toString());
        assertEquals("[{\"start\":3,\"end\":3},{\"start\":5,\"end\":5}]", s.at("/codingEffects/touchedReferenceResidueRanges").toString());
        assertTrue(s.at("/codingEffects/localInFrameLengthChangeResolved").asBoolean());
    }
    @Test void effectsBetweenSeparatedFrameChangesCannotDisappearFromFunctionalCoverage() throws Exception {
        var f=new CodingTranscriptRnaFixture(root,1);
        f.configure("CCATGCCCCAACCGCACCCACGCCAGCCCTAAGGGTGA",3,32,8,20,34,38);
        var result=operations(f,List.of(rna(f,"first-frame-change",9,10,""),rna(f,"frame-restored",24,26,"")));
        var s=derive(f,result).snapshot();
        assertEquals("MPHRTHAP*",s.path("alteredProteinThroughFirstTermination").asText());
        assertEquals("[{\"start\":3,\"end\":8}]",s.at("/codingEffects/touchedReferenceResidueRanges").toString());
        assertEquals("[{\"start\":3,\"end\":8}]",s.at("/codingEffects/proteinEffectReferenceResidueRanges").toString());
        assertEquals(-1,s.path("netProteinLengthChange").asInt());
        assertTrue(s.at("/codingEffects/localInFrameLengthChangeResolved").asBoolean());
    }
    @Test void compensatedCodingFrameshiftHasNoEntireReferenceCodonDeleted() throws Exception {
        var f = new CodingTranscriptRnaFixture(root, 1).alleleAtRna(9, "CC", "C");
        var reference = CodingTranscriptEdits.bindRna(f.mapper, f.context, f.token);
        String group = f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele"));
        var dna = CodingTranscriptEdits.projectRnaAllele(reference, f.context.at("/variant/evidenceQueryAllele"), group);
        var s = derive(f, operations(f, List.of(dna, rna(f, "splice", 13, 15, "")))).snapshot();
        assertEquals("MKPVE*", s.path("alteredProteinThroughFirstTermination").asText());
        assertEquals(0, s.at("/codingEffects/wholeDeletedReferenceCodons").asInt());
        assertEquals(-3, s.at("/codingEffects/referenceCdsIntervalLengthChange").asInt());
        assertEquals(0, s.at("/codingEffects/frameOffsetAtRetainedNativeStop").asInt());
        assertTrue(s.at("/codingEffects/localInFrameLengthChangeResolved").asBoolean());
    }

}
