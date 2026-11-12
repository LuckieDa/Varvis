package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import static org.junit.jupiter.api.Assertions.*;

/** Independently fixed toy sequences; no clinical mechanism or evidence-strength claim. */
class TruncatingFrameshiftComplexAlleleTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TruncatingNmdPvs1RuntimeTest fixture = new TruncatingNmdPvs1RuntimeTest();

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void replacementDeletionUsesActualCdsDeltaAndReferencePtcOnBothStrands(int strand) throws Exception {
        var data = replacement(strand, "G", "31_32delinsG", "Pro11AspfsTer11", "CCA/GA", "P/X");
        ObjectNode beforeModel = data.model.deepCopy();
        ObjectNode beforeContext = data.context.deepCopy();
        ObjectNode input = mapper.createObjectNode();

        assertNull(resolver(data, true).resolve(data.context, annotation(data), input));

        String reference = "ATG" + "CCA".repeat(19) + "CTAACC" + "CCA".repeat(177) + "TAA";
        assertEquals(reference.substring(0, 30) + "G" + reference.substring(32), input.path("alteredCdsSequence").asText());
        assertEquals(-1, input.path("netLengthChange").asInt());
        assertEquals(30, input.path("referenceEditStart0").asInt());
        assertEquals(32, input.path("referenceEditEnd0").asInt());
        assertEquals("CC", input.path("deletedSequence").asText());
        assertTrue(input.path("anchorReferenceCdsPosition").isNull());
        assertEquals("G", input.path("insertedSequence").asText());
        assertEquals(11, input.path("firstChangedProteinPosition").asInt());
        assertEquals(61, input.path("ptcStart").asInt());
        assertEquals(63, input.path("ptcEnd").asInt());
        assertEquals(62, input.path("ptcReferenceStart").asInt());
        assertEquals(63, input.path("ptcReferenceMiddle").asInt());
        assertEquals(64, input.path("ptcReferenceEnd").asInt());
        assertEquals("REFERENCE_BASES", input.path("ptcReferenceMapping").asText());
        assertEquals(119, input.path("mutantLastJunctionCdsPosition").asInt());
        assertEquals(56, input.path("distanceToLastJunction").asInt());
        assertEquals(11, input.path("alteredVariantExonLength").asInt());
        assertEquals("GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE", input.path("sequenceDerivationStatus").asText());
        assertEquals(beforeModel, data.model);
        assertEquals(beforeContext, data.context);
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void stopCreatedEntirelyInsideDelinsHasNoReferenceBaseCoordinates(int strand) throws Exception {
        var data = replacement(strand, "TGA", "31_32delinsTGA", "Pro11Ter", "CCA/TGAA", "P/*");
        ObjectNode input = mapper.createObjectNode();

        assertNull(resolver(data, true).resolve(data.context, annotation(data), input));

        assertEquals(1, input.path("netLengthChange").asInt());
        assertEquals(31, input.path("ptcStart").asInt());
        assertEquals(33, input.path("ptcEnd").asInt());
        assertTrue(input.path("ptcReferenceStart").isNull());
        assertTrue(input.path("ptcReferenceMiddle").isNull());
        assertTrue(input.path("ptcReferenceEnd").isNull());
        assertEquals("CONTAINS_INSERTED_BASES", input.path("ptcReferenceMapping").asText());
        assertEquals(121, input.path("mutantLastJunctionCdsPosition").asInt());
        assertEquals(88, input.path("distanceToLastJunction").asInt());
        assertEquals("MPPPPPPPPP*", input.path("alteredProteinThroughTermination").asText());
    }

    @ParameterizedTest @CsvSource({"true,1", "false,1", "true,-1", "false,-1"})
    void optedInSimpleEditsKeepExistingDerivedFacts(boolean insertion, int strand) throws Exception {
        var data = fixture.frameshift(insertion, strand, 120);
        ObjectNode legacy = mapper.createObjectNode();
        ObjectNode extended = mapper.createObjectNode();
        assertNull(resolver(data, false).resolve(data.context, annotation(data), legacy));
        assertNull(resolver(data, true).resolve(data.context, annotation(data), extended));
        legacy.properties().iterator().forEachRemaining(field -> assertEquals(field.getValue(), extended.path(field.getKey()), field.getKey()));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void existingConstructorKeepsHistoricalSimpleAlleleBoundary(int strand) throws Exception {
        var data = replacement(strand, "G", "31_32delinsG", "Pro11AspfsTer11", "CCA/GA", "P/X");
        var original = new TruncatingFrameshiftResolver(data.model, mechanics(data), data.model.path("transcript").asText(),
                "SYNTHETIC_EXON_RELEVANCE", "SYNTHETIC_MECHANISM_ONLY");
        assertEquals("PVS1_SIMPLE_SHORT_INDEL_REQUIRED", original.resolve(data.context, annotation(data), mapper.createObjectNode()));
    }

    @ParameterizedTest @CsvSource({"GT,31_32delinsGT", "GCTAA,31_32delinsGCTAA"})
    void equalLengthMnvAndInFrameDelinsCannotBecomeFrameshift(String inserted, String hgvs) throws Exception {
        var data = replacement(1, inserted, hgvs, "invalid", "invalid", "invalid");
        ObjectNode input = mapper.createObjectNode();
        assertEquals("PVS1_FRAMESHIFT_NOT_CONFIRMED", resolver(data, true).resolve(data.context, annotation(data), input));
        assertFalse(input.has("nmdPrediction"));
    }

    @ParameterizedTest @ValueSource(strings = {"32_33delinsG", "999999999999999999999delinsG", "31_32delinsN"})
    void disagreeingOrUnmappableHgvsReturnsStructuredFailure(String coding) throws Exception {
        var data = replacement(1, "G", coding, "Pro11AspfsTer11", "CCA/GA", "P/X");
        ObjectNode input = mapper.createObjectNode();
        String issue = assertDoesNotThrow(() -> resolver(data, true).resolve(data.context, annotation(data), input));
        assertEquals(coding.startsWith("32_") ? "PVS1_HGVSC_SEQUENCE_MISMATCH" : "PVS1_CODING_INDEL_MAPPING_UNRESOLVED", issue);
        assertFalse(input.has("nmdPrediction"));
    }

    @Test void referenceConflictIsNotTreatedAsAnUnsupportedAnnotation() throws Exception {
        var data = replacement(1, "G", "31_32delinsG", "Pro11AspfsTer11", "CCA/GA", "P/X");
        ((ObjectNode) data.context.at("/variant/evidenceQueryAllele")).put("reference", "TT");
        assertEquals("PVS1_CDS_REFERENCE_MISMATCH", resolver(data, true).resolve(data.context, annotation(data), mapper.createObjectNode()));
    }

    @Test void genomicEditSpanningAnIntronDoesNotInferASplicedResult() throws Exception {
        var data = replacement(1, "G", "36_37delinsG", "invalid", "invalid", "invalid");
        ((ObjectNode) data.context.at("/variant/evidenceQueryAllele"))
                .put("position", fixture.genomic(data.model, 36)).put("reference", "AC");
        ObjectNode input = mapper.createObjectNode();
        assertEquals("PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED", resolver(data, true).resolve(data.context, annotation(data), input));
        assertFalse(input.has("nmdPrediction"));
    }

    @Test void wrongProteinAnnotationCannotAuthorizeASequenceConfirmedReplacement() throws Exception {
        var data = replacement(1, "G", "31_32delinsG", "Pro11HisfsTer11", "CCA/GA", "P/X");
        ObjectNode input = mapper.createObjectNode();
        assertEquals("PVS1_FRAMESHIFT_PROTEIN_MISMATCH", resolver(data, true).resolve(data.context, annotation(data), input));
        assertEquals(61, input.path("ptcStart").asInt());
        assertFalse(input.has("nmdPrediction"));
    }

    private TruncatingNmdPvs1RuntimeTest.Inputs replacement(int strand, String inserted, String coding,
                                                          String protein, String codons, String amino) throws Exception {
        var data = fixture.frameshift(false, strand, 120);
        ObjectNode tx = fixture.annotation(data, "frameshift_variant", coding, protein, "3/10", "11", amino, codons);
        String genomicAlt = CodingTranscriptMapping.orientAllele(data.model, inserted);
        fixture.candidate(data, fixture.genomic(data.model, strand == 1 ? 31 : 32), strand == 1 ? "CC" : "GG", genomicAlt, tx);
        String type = inserted.length() == 2 ? "MNV" : inserted.length() == 1 ? "SMALL_DELETION" : "SMALL_DELINS";
        ((ObjectNode) data.context.path("variant")).put("variantType", type);
        return data;
    }

    private TranscriptConsequence annotation(TruncatingNmdPvs1RuntimeTest.Inputs data) {
        return data.candidate.getTranscriptAnnotations().get(0);
    }

    private ObjectNode mechanics(TruncatingNmdPvs1RuntimeTest.Inputs data) {
        return TruncatingNmdPolicy.mechanics(data.policy, data.model);
    }

    private TruncatingFrameshiftResolver resolver(TruncatingNmdPvs1RuntimeTest.Inputs data, boolean complex) {
        return new TruncatingFrameshiftResolver(data.model, mechanics(data), data.model.path("transcript").asText(),
                "SYNTHETIC_EXON_RELEVANCE", "SYNTHETIC_MECHANISM_ONLY", complex);
    }
}
