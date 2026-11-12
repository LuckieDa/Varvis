package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic structural examples with explicit source-bound rules; not scientific adoption. */
class Pvs1ModelExceptionsTest {
    final Pvs1NmdEscapeTest helper = new Pvs1NmdEscapeTest();
    @TempDir Path root;

    void utrScope(ObjectNode policy) {
        policy.withObject("nmdScope").putObject("utrJunctionApplicability")
                .put("status", "REVIEWED_FOR_3_PRIME_UTR_JUNCTION")
                .put("basis", "SYNTHETIC explicit NMD applicability including the UTR junction and native termination; no scientific adoption")
                .putArray("sourceIds").add("synthetic-source");
    }

    TruncatingNmdPvs1RuntimeTest.Inputs data(String kind, int strand, boolean single, int junction) throws Exception {
        var f = helper.fixture;
        var d = kind.equals("STOP") ? f.nonsense(1, strand, single ? 499 : 568)
                : f.frameshift(kind.equals("INSERTION"), strand, 120);
        int g = d.context.at("/variant/evidenceQueryAllele/position").asInt();
        int c = CodingTranscriptMapping.cdsPosition(d.model, g);
        int length = d.model.path("cdsLength").asInt();
        int end = single ? length : junction;
        int startG = strand == 1 ? g - c + 1 : g + c - end;
        var exons = d.model.putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", startG).put("genomicEnd", startG + end - 1)
                .put("cdsStart", 1).put("cdsEnd", end);
        if (single) d.model.putNull("lastJunctionCdsPosition");
        else {
            int nextG = strand == 1 ? startG + end + 100 : startG - 130;
            exons.addObject().put("ordinal", 2).put("genomicStart", nextG).put("genomicEnd", nextG + 29)
                    .put("cdsStart", end + 1).put("cdsEnd", end + 30);
            d.model.put("lastJunctionCdsPosition", junction);
        }
        String rna = d.model.path("cdsSequence").asText() + "A".repeat(single ? 0 : end + 30 - length);
        d.model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(rna))
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(d.model.path("cdsSequence").asText()));
        var nmd = d.policy.withObject("nmdScope");
        nmd.putArray("relevantExonOrdinals").add(1);
        var supported = nmd.putArray("engineeringSupportedExonOrdinals");
        if (!single) { supported.add(1); nmd.withArray("relevantExonOrdinals").add(2); }
        nmd.put("longExonScope", "REVIEWED_WITHOUT_LENGTH_LIMIT");
        var annotation = helper.mapper.valueToTree(d.candidate.getTranscriptAnnotations().get(0));
        ((ObjectNode) annotation).put("exon", single ? "1/1" : "1/2");
        d.candidate.applyTranscriptAnnotations(List.of(helper.mapper.copy()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(annotation, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        return d;
    }

    @ParameterizedTest @CsvSource({"STOP,1", "STOP,-1", "INSERTION,1", "INSERTION,-1", "DELETION,1", "DELETION,-1"})
    void singleExonUsesExplicitEscapeEvidenceWithoutInventingAJunction(String kind, int strand) throws Exception {
        var d = data(kind, strand, true, 0);
        var s = Pvs1NmdEscapeTest.scope(d.policy, 1); Pvs1NmdEscapeTest.critical(s, 160, 190, 1, "STRONG");
        var result = helper.run(d);
        assertEquals("MET", helper.status(result).path("status").asText(), result.toPrettyString());
        assertEquals("STRONG", result.at("/evaluation/appliedStrength").asText());
        assertEquals("NMD_NOT_PREDICTED", result.at("/resolvedInput/nmdPrediction").asText());
        assertTrue(result.at("/resolvedInput/lastJunctionCdsPosition").isNull());
        assertTrue(result.at("/resolvedInput/distanceToLastJunction").isNull());
        assertEquals("SINGLE_EXON", result.at("/resolvedInput/transcriptTopology/kind").asText());
        assertEquals("1.5.0", result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void threePrimeUtrJunctionUsesActualSplicedDistance(int strand) throws Exception {
        var d = data("STOP", strand, false, 621);
        utrScope(d.policy);
        var r = helper.run(d);
        assertEquals("MET", helper.status(r).path("status").asText(), r.toPrettyString());
        assertEquals(51, r.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals("LAST_JUNCTION_IN_3_PRIME_UTR", r.at("/resolvedInput/transcriptTopology/kind").asText());
        assertEquals("PREDICTED_NMD", r.at("/resolvedInput/nmdPrediction").asText());
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void singleExonWithoutEscapeKnowledgeExplainsTheMissingConditions(int strand) throws Exception {
        var d = data("STOP", strand, true, 0);
        helper.reason(helper.run(d), "PVS1_SINGLE_EXON_ESCAPE_CONDITIONS_MISSING");
    }

    @ParameterizedTest @CsvSource({"INSERTION,1", "INSERTION,-1", "DELETION,1", "DELETION,-1"})
    void utrFrameshiftMovesTheRealJunctionInAlteredCoordinates(String kind, int strand) throws Exception {
        var d = data(kind, strand, false, 621); utrScope(d.policy);
        var r = helper.run(d); assertEquals("MET", helper.status(r).path("status").asText(), r.toPrettyString());
        int expected = kind.equals("INSERTION") ? 622 : 620;
        assertEquals(expected, r.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
        assertEquals(expected - (kind.equals("INSERTION") ? 66 : 63), r.at("/resolvedInput/distanceToLastJunction").asInt());
    }

    @Test void actualUtrStructureDoesNotInventScientificApplicability() throws Exception {
        var d = data("STOP", 1, false, 621);
        var r = helper.run(d); helper.reason(r, "PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED");
        assertEquals(51, r.at("/resolvedInput/distanceToLastJunction").asInt());
        assertFalse(r.path("resolvedInput").has("nmdPrediction"));
        utrScope(d.policy); d.policy.withObject("nmdScope").withObject("utrJunctionApplicability").putArray("sourceIds").add("unbound");
        r = helper.run(d); helper.reason(r, "PVS1_UTR_JUNCTION_APPLICABILITY_INVALID");
        assertEquals(0, r.path("evaluatorCalls").asInt());
    }

    @Test void exactFiftyBaseBoundaryUsesEscapeRuleAndDoesNotRoundUp() throws Exception {
        var d = data("STOP", 1, false, 620); utrScope(d.policy);
        var s = Pvs1NmdEscapeTest.scope(d.policy, 2); Pvs1NmdEscapeTest.critical(s, 160, 199, 1, "MODERATE");
        var r = helper.run(d); assertEquals("MET", helper.status(r).path("status").asText(), r.toPrettyString());
        assertEquals(50, r.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals("MODERATE", r.at("/evaluation/appliedStrength").asText());
        d.policy.withObject("nmdScope").put("minimumDistanceToLastJunction", 60);
        d = data("STOP", 1, false, 621); utrScope(d.policy);
        s = Pvs1NmdEscapeTest.scope(d.policy, 2); Pvs1NmdEscapeTest.critical(s, 160, 199, 1, "MODERATE");
        d.policy.withObject("nmdScope").put("minimumDistanceToLastJunction", 60);
        helper.reason(helper.run(d), "PVS1_ESCAPE_NMD_STATUS_UNRESOLVED");
    }

    @ParameterizedTest @ValueSource(strings={"PROOF", "JUNCTION", "RELEVANCE", "POPULATION", "TOLERANCE", "CONFLICT", "UNREVIEWED", "CODON", "CAP"})
    void singleExonMustKeepScientificAndSequenceGates(String failure) throws Exception {
        var d = data("STOP", 1, true, 0); var s = Pvs1NmdEscapeTest.scope(d.policy, 1);
        Pvs1NmdEscapeTest.length(s, "NET_PROTEIN_SHORTENING", "GT", 1, 10, "STRONG");
        var fact = (ObjectNode) s.at("/exonAssessments/0"); String expected;
        switch (failure) {
            case "PROOF" -> { d.model.remove("sequenceVerification"); expected = "PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING"; }
            case "JUNCTION" -> { d.model.put("lastJunctionCdsPosition", 0); expected = "PVS1_SINGLE_EXON_JUNCTION_CONFLICT"; }
            case "RELEVANCE" -> { fact.put("biologicalRelevance", "UNKNOWN"); expected = "PVS1_ESCAPE_EXON_RELEVANCE_UNRESOLVED"; }
            case "POPULATION" -> { fact.put("populationLof", "UNKNOWN"); expected = "PVS1_ESCAPE_POPULATION_TOLERANCE_UNRESOLVED"; }
            case "TOLERANCE" -> { fact.put("populationLof", "FREQUENT"); expected = "PVS1_ESCAPE_EXON_LOF_TOLERANT"; }
            case "CONFLICT" -> { s.withArray("rules").add(s.path("rules").get(0).deepCopy()); ((ObjectNode)s.at("/rules/1")).put("ruleId","second"); expected = "PVS1_ESCAPE_RULES_AMBIGUOUS"; }
            case "UNREVIEWED" -> { s.put("status","UNREVIEWED"); expected = "PVS1_ESCAPE_SCOPE_NOT_REVIEWED"; }
            case "CODON" -> { var a=(ObjectNode)helper.mapper.valueToTree(d.candidate.getTranscriptAnnotations().get(0)); a.put("codons","AAA/TGA"); d.candidate.applyTranscriptAnnotations(List.of(helper.mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(a,org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class))); expected="PVS1_PROTEIN_CODON_MISMATCH"; }
            case "CAP" -> { d.policy.put("appliedStrength","SUPPORTING"); expected="PVS1_NMD_ESCAPE_PREDICTED_LOF"; }
            default -> throw new AssertionError(failure);
        }
        var r = helper.run(d);
        if (failure.equals("CAP")) assertEquals("SUPPORTING",r.at("/evaluation/appliedStrength").asText());
        else helper.reason(r, expected);
        if (failure.equals("TOLERANCE")) assertEquals("NOT_MET", helper.status(r).path("status").asText());
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void mixedSpliceCannotEnterTheNewPureCodingPath(boolean single) throws Exception {
        var d=data("STOP",1,single,621);
        if(!single) utrScope(d.policy);
        var a=(ObjectNode)helper.mapper.valueToTree(d.candidate.getTranscriptAnnotations().get(0));
        a.put("consequence","stop_gained&splice_region_variant");
        d.candidate.applyTranscriptAnnotations(List.of(helper.mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(a,org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        helper.reason(helper.run(d),"PVS1_SPECIAL_TOPOLOGY_SPLICE_NOT_IMPLEMENTED");
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void selectedKnowledgePathSuppliesFullModelAndBindsNewVersion(boolean single) throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root=root;
        f.data=data("STOP",1,single,621); f.referenceModel=f.data.model.deepCopy();
        var s=Pvs1NmdEscapeTest.scope(f.data.policy, single?1:2); Pvs1NmdEscapeTest.critical(s,160,199,1,"STRONG");
        if(!single) utrScope(f.data.policy);
        // Extend only this synthetic reader's sequence beyond CDS for the complete RNA proof.
        if(!single) f.referenceModel.put("cdsSequence", f.data.model.path("cdsSequence").asText()+"A".repeat(51));
        var bound=f.data.model.deepCopy(); f.data.model.remove("lastJunctionCdsPosition");
        var bundle=Pvs1CodingSupplyIntegrationTest.load(root,f,bound,f.write());
        var r=bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION,f.data.context,f.data.candidate);
        assertEquals("MET",helper.status(r).path("status").asText(),r.toPrettyString());
        assertEquals("READY",r.at("/transcriptMaterialSupply/status").asText());
        assertEquals("1.5.0",bundle.identity().at("/algorithms/transcript-truncating-nmd-pvs1").asText());
        assertTrue(f.reads.get()>0);
        f.invalidReference=true;
        r=bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION,f.data.context,f.data.candidate);
        assertEquals("NOT_EVALUATED",helper.status(r).path("status").asText());
        assertEquals(0,r.path("evaluatorCalls").asInt());
    }
}
