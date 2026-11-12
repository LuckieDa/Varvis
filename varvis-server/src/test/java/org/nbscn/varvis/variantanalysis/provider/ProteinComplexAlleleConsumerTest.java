package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Independent synthetic expectations fixed from MEPGFK*, never clinical observations. */
class ProteinComplexAlleleConsumerTest {
    @TempDir Path root;

    @ParameterizedTest @CsvSource({"1,106,CCT,GCTGAA", "-1,112,AGG,TTCAGC"})
    void verifiedDelinsHasTheHandCalculatedCdsAndProteinOnBothStrands(int strand, int position, String ref, String alt) throws Exception {
        var f = length(strand, position, ref, alt, "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        var legacy = f.prebuilt().resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertFalse(legacy.ready());
        assertEquals("PM4_COMPLEX_ALLELE_UNSUPPORTED", legacy.snapshot().path("reasonCode").asText());
        var result = f.prebuilt(true).resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertTrue(result.ready(), result.snapshot().toPrettyString());
        var facts = result.snapshot().path("alleleAssociation");
        assertEquals(3, facts.path("netNucleotideChange").asInt());
        assertEquals(1, facts.path("netAminoAcidChange").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha("ATGGAAGCTGAAGGCTTTAAATAA"), facts.path("alteredCdsSha256").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEAEGFK*"), facts.path("alteredProteinSha256").asText());
        assertEquals("READY", facts.path("equivalentEditEnvelopeStatus").asText());
        assertEquals(3, facts.at("/equivalentEditEnvelope/proteinStart").asInt());
        assertEquals(3, facts.at("/equivalentEditEnvelope/proteinEnd").asInt());
        assertFalse(facts.has("alteredCds"));
    }

    @ParameterizedTest @CsvSource({
        "GCTG,7_9delinsGCTG,SMALL_DELINS,PM4_FRAMESHIFT_UNSUPPORTED",
        "GAA,7_9delinsGAA,MNV,PM4_NO_NET_LENGTH_CHANGE"
    })
    void frameshiftAndEqualLengthMnvNeverSupplyPm4LengthFacts(String alt, String hgvs, String type, String reason) throws Exception {
        var f = length(1, 106, "CCT", alt, hgvs, null, type);
        var material = f.prebuilt(true).resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertFalse(material.ready());
        assertEquals(reason, material.snapshot().path("reasonCode").asText(), material.snapshot().toPrettyString());
        assertFalse(material.snapshot().has("alleleAssociation"));
    }

    @ParameterizedTest @CsvSource({
        "C,AGCT,7delinsAGCT,Pro3delinsSerSer,SMALL_INSERTION,ATGGAAAGCTCTGGCTTTAAATAA,MESSGFK*,1",
        "CCTG,A,7_10delinsA,Pro3_Gly4delinsSer,SMALL_DELETION,ATGGAAAGCTTTAAATAA,MESFK*,-1"
    })
    void existingLengthBasedVariantTypesDoNotRequireAVcfAnchor(String ref, String alt, String hgvs, String protein,
            String type, String cds, String translated, int delta) throws Exception {
        var f = length(1, 106, ref, alt, hgvs, protein, type);
        var material = f.prebuilt(true).resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertTrue(material.ready(), material.snapshot().toPrettyString());
        assertEquals(delta, material.snapshot().at("/alleleAssociation/netAminoAcidChange").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha(cds), material.snapshot().at("/alleleAssociation/alteredCdsSha256").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha(translated), material.snapshot().at("/alleleAssociation/alteredProteinSha256").asText());
    }

    @Test void sequenceEquivalentHgvsCannotMoveAPhysicalDeletionAcrossAnIntron() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var f = new Pm4TestFixtures(mapper, 1);
        f.model.put("cdsSequence", "ATGGAAGAAGAATAA").put("cdsLength", 15).put("proteinLength", 4);
        var exons = f.model.putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 106).put("cdsStart", 1).put("cdsEnd", 7);
        exons.addObject().put("ordinal", 2).put("genomicStart", 200).put("genomicEnd", 207).put("cdsStart", 8).put("cdsEnd", 15);
        f.variant(201, "AGAA", "A", "7_9del", null);
        var annotation = mapper.treeToValue(f.annotation("7_9del", null, "inframe_deletion").put("exon", "2/2"), TranscriptConsequence.class);
        assertEquals("PM4_HGVSC_EXON_BOUNDARY_UNSUPPORTED",
                ProteinLengthChangeObservation.resolveComplex(f.context, f.model, annotation, mapper.createObjectNode()));
    }

    @ParameterizedTest @CsvSource({"CGA,Arg,3,MEPGFKRAG*", "CAGGCT,Gln,4,MEPGFKQAAG*"})
    void stopLossUsesTheEntireChangedCdsAndVerifiedTail(String alternate, String first, int added, String protein) throws Exception {
        var f = stop(alternate, first, added);
        var old = f.supply(false).resolveStopLoss(f.data.context, f.data.candidate);
        assertFalse(old.ready());
        assertEquals("PM4_STOP_LOSS_SIMPLE_SNV_REQUIRED", old.snapshot().path("reasonCode").asText());
        var material = f.supply(false, true).resolveStopLoss(f.data.context, f.data.candidate);
        assertTrue(material.ready(), material.snapshot().toPrettyString());
        var facts = material.snapshot().path("alleleAssociation");
        assertEquals(added, facts.path("netAminoAcidChange").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha(protein), facts.path("alteredProteinSha256").asText());
        assertTrue(facts.path("newTerminationResolved").asBoolean());
        assertFalse(facts.has("alteredCds"));
    }

    @Test void frameshiftingStopDelinsCannotEnterPm4ThroughTheStopLossRoute() throws Exception {
        var f = stop("CAGG", "Gln", 3);
        var material = f.supply(false, true).resolveStopLoss(f.data.context, f.data.candidate);
        assertFalse(material.ready());
        assertEquals("PM4_FRAMESHIFT_UNSUPPORTED", material.snapshot().path("reasonCode").asText());
    }

    @Test void aStopRetainingMnvIsNotAnExtension() throws Exception {
        var f = stop("TAG", "Gln", 3);
        var material = f.supply(false, true).resolveStopLoss(f.data.context, f.data.candidate);
        assertFalse(material.ready());
        assertEquals("PM4_STOP_LOSS_NOT_CONFIRMED", material.snapshot().path("reasonCode").asText());
    }

    @Test void aNewTerminationInsideTheVerifiedInsertedCdsDoesNotRequireInventedUtr() throws Exception {
        var f = stop("CAGTAG", "Gln", 1);
        f.spliced = Pm4TestFixtures.CDS;
        f.data.model.withObject("/exons/0").put("genomicEnd", 120).put("cdsEnd", 21);
        f.data.model.withObject("/sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(f.spliced));
        var material = f.supply(false, true).resolveStopLoss(f.data.context, f.data.candidate);
        assertTrue(material.ready(), material.snapshot().toPrettyString());
        assertEquals(0, material.snapshot().at("/alleleAssociation/availableThreePrimeBases").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEPGFKQ*"), material.snapshot().at("/alleleAssociation/alteredProteinSha256").asText());
    }

    @Test void generalPm4RequiresItsOwnNonRepeatQualificationForTheComplexSequence() throws Exception {
        var f = length(1, 106, "CCT", "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        var runtime = GeneralPm4TestSupport.create(f.mapper, root.resolve("runtime"), f.data.model, f::read,
                false, true, false, "REVIEWED_ONLY", raw -> { }, true);
        var result = runtime.evaluate(f.data.context, f.data.candidate, f.mapper.createObjectNode());
        assertEquals("MET", result.path("status").asText(), result.toPrettyString());
        assertEquals("PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE", result.at("/publicSummary/scope").asText());
        assertEquals("REVIEWED_COVERAGE", result.at("/publicSummary/qualificationBasis").asText());
    }

    @ParameterizedTest @CsvSource({"MISSING,false,NOT_EVALUATED", "NO_KNOWN_FUNCTION,false,MET",
            "KNOWN_FUNCTION,false,NOT_MET", "NO_KNOWN_FUNCTION,true,NOT_EVALUATED"})
    void generalBp3KeepsItsIndependentFunctionalQualification(String function, boolean domainConflict, String expected) throws Exception {
        var f = length(1, 106, "CCT", "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        var directory = root.resolve("runtime");
        var pm4 = GeneralPm4TestSupport.create(f.mapper, directory, f.data.model, f::read,
                true, false, false, "REVIEWED_ONLY", raw -> { if (!domainConflict) raw.withArray("features").remove(1); }, true);
        var bp3 = GeneralPm4TestSupport.bp3(f.mapper, directory, pm4, f.data.context, f.data.candidate, function);
        var result = bp3.evaluate(f.data.context, f.data.candidate, f.mapper.createObjectNode());
        assertEquals(expected, result.path("status").asText(), result.toPrettyString());
        if (domainConflict) assertEquals("BP3_FUNCTIONAL_ANNOTATION_CONFLICT_REVIEW_REQUIRED", result.path("reasonCode").asText());
        assertEquals("PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE", result.at("/publicSummary/scope").asText());
    }

    private ProteinLengthChangeMaterialSupplyTest length(int strand, int position, String ref, String alt,
            String hgvs, String protein, String type) throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest(); f.root = root;
        f.setup(strand, position, ref, alt, hgvs, protein);
        type(f.data, type);
        return f;
    }

    private GeneralPm4StopLossTest stop(String alternate, String first, int added) throws Exception {
        var f = new GeneralPm4StopLossTest(); f.root = root;
        f.setup(1, "GCTGGTTGACCC", null);
        f.data.model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100)
                .put("genomicEnd", 100 + f.spliced.length() - 1).put("cdsStart", 1).put("cdsEnd", f.spliced.length());
        String coding = "19_21delins" + alternate;
        f.data.variant(118, "TAA", alternate, coding, null);
        f.annotation = f.data.annotation(coding, "Ter7" + first + "extTer" + added, "stop_lost").put("exon", "1/1");
        f.applyAnnotation();
        type(f.data, alternate.length() == 3 ? "MNV" : "SMALL_DELINS");
        return f;
    }

    private static void type(Pm4TestFixtures f, String type) {
        ((ObjectNode) f.context.path("variant")).put("variantType", type);
        f.context.put("contextId", f.adapter.canonicalContextId(f.context));
    }
}
