package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProteinStopLossMaterialSupplyTest {
    @TempDir Path root;
    GeneralPm4StopLossTest fixture(int strand, String tail, String hgvsp) throws Exception {
        var f = new GeneralPm4StopLossTest(); f.root = root; f.setup(strand, tail, hgvsp); return f;
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void gencodeSuppliesSplicedUtrWithoutChangingLegacyModelIdentity(int strand) throws Exception {
        var f = fixture(strand, "GCTGGTTGACCC", "(Ter7GlnextTer3)"); var supply = f.supply(true);
        var structure = supply.resolveStructure(f.data.context, f.data.candidate);
        assertTrue(structure.ready(), structure.snapshot().toString());
        int before = f.bases.get(); var result = supply.resolveStopLoss(f.data.context, f.data.candidate);
        assertTrue(result.ready(), result.snapshot().toString()); assertEquals(structure.model(), result.model());
        assertEquals(3, f.bases.get() - before, "GENCODE receipt witness plus material receipt and allele REF");
        assertTrue(result.snapshot().path("modelCacheHit").asBoolean());
        assertEquals(3, result.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
        assertFalse(supply.resolveProteinLengthChange(f.data.context, f.data.candidate).ready(), "No stop loss silently added to the legacy indel contract");
        assertFalse(supply.resolve(f.data.context, f.data.candidate).ready(), "A stop-loss fact does not qualify a missense model");
        assertFalse(structure.snapshot().has("alleleAssociation"));
    }
    @ParameterizedTest @ValueSource(strings = {"", "GC", "GCTGGTCCC", "WINDOW"})
    void missingTerminationIsSpecificAndNeverInvented(String tail) throws Exception {
        boolean bounded = "WINDOW".equals(tail);
        var f = fixture(1, bounded ? "GCT".repeat(33333) + "TAA" : tail, "Ter7GlnextTer?");
        var result = f.supply(false).resolveStopLoss(f.data.context, f.data.candidate);
        assertFalse(result.ready());
        assertEquals(tail.isEmpty() ? "PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_UNAVAILABLE"
                : bounded ? "PM4_STOP_LOSS_NEW_STOP_BEYOND_TECHNICAL_WINDOW" : "PM4_STOP_LOSS_NEW_STOP_NOT_FOUND_IN_TRANSCRIPT",
                result.snapshot().path("reasonCode").asText(), result.snapshot().toString());
        var facts = result.snapshot().path("alleleAssociation");
        assertFalse(facts.has("netAminoAcidChange"));
        assertEquals(bounded ? 100000 : tail.length(), facts.path("inspectedThreePrimeBases").asInt());
    }
    @Test void earlyNewStopDoesNotRequireTheWholeUtrToFitTheExtensionWindow() throws Exception {
        var f = fixture(1, "GCTTAA" + "GCT".repeat(34000), "Ter7GlnextTer2");
        var r = f.supply(false).resolveStopLoss(f.data.context, f.data.candidate);
        assertTrue(r.ready(), r.snapshot().toString());
        assertFalse(r.snapshot().at("/alleleAssociation/threePrimeWindowComplete").asBoolean(true));
        assertEquals(2, r.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
    }
    @ParameterizedTest @ValueSource(strings = {"Ter7GlnextTer?", "*7Qext*3", "(Ter7GlnextTer3)", "NONE"})
    void annotationMayBePartialButCannotSubstituteForSequence(String protein) throws Exception {
        var f = fixture(-1, "GCTGGTTGACCC", "NONE".equals(protein) ? null : protein);
        var r = f.supply(false).resolveStopLoss(f.data.context, f.data.candidate);
        assertTrue(r.ready(), r.snapshot().toString());
        assertEquals(3, r.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
        if (protein.contains("?")) assertFalse(r.snapshot().at("/alleleAssociation/hgvspSequenceEquivalent").asBoolean(true));
    }
    @ParameterizedTest @CsvSource({"19,T,C,Q,Gln,1", "20,A,C,S,Ser,1", "21,A,C,Y,Tyr,2"})
    void allThreeStopBasesIncludingSplitCodonUseTheSameTranscriptFrame(int c, String ref, String alt, String aa, String name, int exon) throws Exception {
        var f = fixture(-1, "GCTGGTTGACCC", null);
        var e = f.data.model.path("exons").get(exon - 1);
        int genomic = e.path("genomicEnd").asInt() - (c - e.path("cdsStart").asInt());
        String coding = c + ref + ">" + alt;
        f.data.variant(genomic, TruncatingSequence.reverseComplement(ref), TruncatingSequence.reverseComplement(alt), coding, null);
        f.annotation = f.data.annotation(coding, "Ter7" + name + "extTer3", "stop_lost").put("exon", exon + "/3"); f.applyAnnotation();
        var r = f.supply(false).resolveStopLoss(f.data.context, f.data.candidate);
        assertTrue(r.ready(), r.snapshot().toString());
        assertEquals(aa, r.snapshot().at("/alleleAssociation/firstExtensionResidue").asText());
        assertEquals(3, r.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
    }
    @ParameterizedTest @CsvSource({
            "proteinLength,PM4_HGVSP_SEQUENCE_MISMATCH", "proteinIdentity,PM4_HGVSP_UNSUPPORTED",
            "proteinResidue,PM4_HGVSP_SEQUENCE_MISMATCH", "coding,PM4_HGVSC_SEQUENCE_MISMATCH",
            "exon,PM4_EXON_ANNOTATION_MISMATCH", "splice,PM4_SPLICE_BRANCH_UNSUPPORTED",
            "consequence,PM4_STOP_LOSS_CONSEQUENCE_MISMATCH", "version,LENGTH_CHANGE_ANNOTATION_NOT_QUALIFIED",
            "timestamp,LENGTH_CHANGE_ANNOTATION_NOT_QUALIFIED", "duplicate,LENGTH_CHANGE_SELECTED_TRANSCRIPT_NOT_UNIQUE"})
    void mismatchedOrIncompleteAnnotationsRemainRefusals(String mode, String expected) throws Exception {
        var f = fixture(1, "GCTGGTTGACCC", "Ter7GlnextTer3");
        switch (mode) {
            case "proteinLength" -> f.annotation.put("hgvsProtein", "ENSP000001.1:p.Ter7GlnextTer4");
            case "proteinIdentity" -> f.annotation.put("hgvsProtein", "ENSP000001.2:p.Ter7GlnextTer3");
            case "proteinResidue" -> f.annotation.put("hgvsProtein", "ENSP000001.1:p.Ter7ArgextTer3");
            case "coding" -> f.annotation.put("hgvsCoding", "ENST000001.1:c.20T>C");
            case "exon" -> f.annotation.put("exon", "2/3");
            case "splice" -> f.annotation.put("consequence", "stop_lost&splice_region_variant");
            case "consequence" -> f.annotation.put("consequence", "missense_variant");
            case "version" -> { f.annotation.put("version", "115.0"); f.data.context.withObject("transcript").put("annotationSourceVersion", "115.0"); }
            case "timestamp" -> f.annotation.remove("timestamp");
            case "duplicate" -> { }
            default -> throw new AssertionError(mode);
        }
        f.applyAnnotation();
        if ("duplicate".equals(mode)) {
            var original = f.data.candidate.getTranscriptAnnotations().get(0);
            f.data.candidate.applyTranscriptAnnotations(List.of(original, original));
        }
        f.data.context.put("contextId", f.data.adapter.canonicalContextId(f.data.context));
        var r = f.supply(false).resolveStopLoss(f.data.context, f.data.candidate);
        assertFalse(r.ready(), r.snapshot().toString()); assertEquals(expected, r.snapshot().path("reasonCode").asText());
    }
    @Test void cachedSequenceNeverCachesTheAlleleDecisionOrOverridesAChangedReference() throws Exception {
        var f = fixture(1, "GCTGGTTGACCC", "Ter7GlnextTer3"); var supply = f.supply(false);
        var first = supply.resolveStopLoss(f.data.context, f.data.candidate); assertTrue(first.ready());
        first.snapshot().removeAll(); requireNonNull(first.model()).removeAll();
        f.annotation.put("hgvsProtein", "ENSP000001.1:p.Ter7GlnextTer4"); f.applyAnnotation();
        assertEquals("PM4_HGVSP_SEQUENCE_MISMATCH", supply.resolveStopLoss(f.data.context, f.data.candidate).snapshot().path("reasonCode").asText());
        f.annotation.put("hgvsProtein", "ENSP000001.1:p.Ter7GlnextTer3"); f.applyAnnotation();
        f.referenceVersion = "TEST_ONLY_REFERENCE_2"; f.spliced = f.spliced.replace("GCTGGT", "GCTGAT");
        assertEquals("TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH", supply.resolveStopLoss(f.data.context, f.data.candidate).snapshot().path("reasonCode").asText());
    }
    @Test void stopRetainedAndNonterminalSnvCannotBeCalledExtension() throws Exception {
        var f = fixture(1, "GCTGGTTGACCC", null);
        f.data.variant(119, "A", "G", "20A>G", null);
        f.annotation = f.data.annotation("20A>G", null, "stop_lost").put("exon", "1/3"); f.applyAnnotation();
        var supply = f.supply(false);
        assertEquals("PM4_STOP_LOSS_NOT_CONFIRMED", supply.resolveStopLoss(f.data.context, f.data.candidate).snapshot().path("reasonCode").asText());
        f.data.variant(103, "G", "T", "4G>T", null);
        f.annotation = f.data.annotation("4G>T", null, "stop_lost").put("exon", "1/3"); f.applyAnnotation();
        assertEquals("PM4_STOP_LOSS_OUTSIDE_TERMINAL_CODON", supply.resolveStopLoss(f.data.context, f.data.candidate).snapshot().path("reasonCode").asText());
        assertEquals(0, f.bases.get());
    }
    @Test void interruptedWorkNeverReadsOrClearsCancellation() throws Exception {
        var f = fixture(1, "GCTGGTTGACCC", null); var supply = f.supply(false);
        Thread.currentThread().interrupt();
        try {
            assertEquals("TRANSCRIPT_MATERIAL_INTERRUPTED", supply.resolveStopLoss(f.data.context, f.data.candidate).snapshot().path("reasonCode").asText());
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0, f.bases.get());
        } finally { Thread.interrupted(); }
    }
}
