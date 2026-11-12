package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic one-exon R2Q; this is a sequence test, not a clinical variant or assay. */
class CodingMissenseResolverTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void independentlyReconstructsSameAminoAcidOnBothStrands(int strand) throws Exception {
        ObjectNode identity = mapper.createObjectNode();
        assertNull(CodingMissenseResolver.resolve(context(strand), tx(), model(strand), identity, adapter));
        assertEquals("R2Q", identity.path("proteinVariantKey").asText());
        assertEquals("CGA", identity.path("refCodon").asText());
        assertEquals("CAA", identity.path("altCodon").asText());
        assertEquals(5, identity.path("cdsPosition").asInt());
        assertEquals("R", identity.path("refAminoAcid").asText());
        assertEquals("Q", identity.path("altAminoAcid").asText());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void untranslatedFirstExonDoesNotShiftCodingOrProteinCoordinates(int strand) throws Exception {
        ObjectNode model = model(strand);
        ObjectNode coding = ((ObjectNode) model.path("exons").path(0)).deepCopy().put("ordinal", 2);
        model.putArray("exons").addObject().put("ordinal", 1)
                .put("genomicStart", strand == 1 ? 50 : 200).put("genomicEnd", strand == 1 ? 52 : 202)
                .put("cdsStart", -2).put("cdsEnd", 0);
        model.withArray("exons").add(coding);
        ObjectNode annotation = txNode().put("exon", "2/2"), identity = mapper.createObjectNode();
        assertNull(CodingMissenseResolver.resolve(context(strand), mapper.treeToValue(annotation, TranscriptConsequence.class), model, identity, adapter));
        assertEquals("R2Q", identity.path("proteinVariantKey").asText());
        assertEquals(5, identity.path("cdsPosition").asInt());
        assertEquals("NM_000001.1:c.5G>A", identity.path("hgvsc").asText());
        assertEquals("CODING_MISSENSE_EXON_IDENTITY_MISMATCH", CodingMissenseResolver.resolve(context(strand), tx(), model, mapper.createObjectNode(), adapter));
    }

    @ParameterizedTest @ValueSource(strings = {"hgvsCoding", "hgvsProtein", "proteinPosition", "aminoAcids", "codons", "exon", "transcriptId", "transcriptAccession", "geneSymbol"})
    void rejectsContradictoryStoredAnnotation(String field) throws Exception {
        ObjectNode annotation = txNode();
        annotation.put(field, "incorrect");
        TranscriptConsequence changed = mapper.treeToValue(annotation, TranscriptConsequence.class);
        assertNotNull(CodingMissenseResolver.resolve(context(1), changed, model(1), mapper.createObjectNode(), adapter));
    }

    @Test void rejectsFalseSnvReferencesSpliceAndStartScopeWithoutWritingIdentity() throws Exception {
        ObjectNode context = context(1), output = mapper.createObjectNode();
        ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("reference", "C");
        assertEquals("CODING_MISSENSE_CDS_REFERENCE_MISMATCH", CodingMissenseResolver.resolve(context, tx(), model(1), output, adapter));
        assertTrue(output.isEmpty());
        context = context(1); ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("alternate", "GA");
        assertEquals("CODING_MISSENSE_NATIVE_SNV_REQUIRED", CodingMissenseResolver.resolve(context, tx(), model(1), output, adapter));
        ObjectNode annotation = txNode(); annotation.put("consequence", "missense_variant&splice_region_variant");
        assertEquals("CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED", CodingMissenseResolver.resolve(context(1), mapper.treeToValue(annotation, TranscriptConsequence.class), model(1), output, adapter));
    }

    @Test void modelValidationDoesNotRequireLoFOrNmdJunction() throws Exception {
        ObjectNode model = model(1);
        assertFalse(model.has("lastJunctionCdsPosition"));
        assertEquals(1, model.path("exons").size());
        assertNull(CodingMissenseResolver.resolve(context(1), tx(), model, mapper.createObjectNode(), adapter));
        model.put("cdsSequence", "ATGTGATAA");
        assertEquals("CODING_MISSENSE_MODEL_SEQUENCE_INVALID", CodingMissenseResolver.resolve(context(1), tx(), model, mapper.createObjectNode(), adapter));
    }

    @Test void integralOverflowCannotAliasARealGenomicPosition() throws Exception {
        ObjectNode context = context(1);
        ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("position", 4294967400L);
        assertEquals("CODING_MISSENSE_GENOMIC_POSITION_INVALID", CodingMissenseResolver.resolve(context, tx(), model(1), mapper.createObjectNode(), adapter));
    }

    private ObjectNode context(int strand) {
        ObjectNode context = mapper.createObjectNode();
        context.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "SYNTHETIC");
        context.putObject("transcript").put("accession", "NM_000001").put("version", 1).put("annotationSourceVersion", "116.0");
        ObjectNode variant = context.putObject("variant").put("variantType", "SNV");
        variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        variant.putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1").put("position", 104)
                .put("reference", strand == 1 ? "G" : "C").put("alternate", strand == 1 ? "A" : "T");
        return context;
    }

    private ObjectNode model(int strand) {
        ObjectNode model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "synthetic-coding-model").put("modelVersion", "1").put("hgncId", "HGNC:1")
                .put("geneSymbol", "SYNTHETIC").put("assembly", "GRCh38").put("contig", "1").put("strand", strand)
                .put("transcript", "NM_000001.1").put("protein", "NP_000001.1").put("cdsSequence", "ATGCGATAA")
                .put("cdsLength", 9).put("proteinLength", 2);
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 108).put("cdsStart", 1).put("cdsEnd", 9);
        model.putArray("sourceRecords").addObject().put("sourceId", "synthetic-sequence").put("version", "1")
                .put("url", "https://example.invalid/synthetic").put("sha256", "a".repeat(64));
        return model;
    }

    private TranscriptConsequence tx() throws Exception {
        return mapper.treeToValue(txNode(), TranscriptConsequence.class);
    }
    private ObjectNode txNode() {
        ObjectNode tx = mapper.createObjectNode().put("geneSymbol", "SYNTHETIC").put("transcriptAccession", "NM_000001")
                .put("transcriptId", "NM_000001.1").put("version", "116.0")
                .put("hgvsCoding", "NM_000001.1:c.5G>A").put("hgvsProtein", "NP_000001.1:p.Arg2Gln").put("proteinPosition", "2")
                .put("aminoAcids", "R/Q").put("codons", "CGA/CAA")
                .put("consequence", "missense_variant").put("exon", "1/1");
        return tx;
    }
}
