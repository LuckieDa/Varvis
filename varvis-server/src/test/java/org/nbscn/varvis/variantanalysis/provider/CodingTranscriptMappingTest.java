package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Pure synthetic coordinates and standard-code sequences; no scientific gene-policy claim. */
class CodingTranscriptMappingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void codingValidationDoesNotSupplySpecialTopologySequenceProof() {
        ObjectNode model = singleExon(1);
        assertNull(CodingTranscriptMapping.modelIssue(model, context()));
        assertEquals("PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING", TruncatingPvs1Model.issue(model, context()));
        model.put("schemaVersion", "pvs1-transcript-model-v1");
        assertNull(CodingTranscriptMapping.modelIssue(model, context()));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void completeUntranslatedExonsKeepTheirOrdinalsAndNeverBecomeCoding(int strand) {
        ObjectNode model = singleExon(strand);
        var exons = model.putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", strand == 1 ? 10 : 300)
                .put("genomicEnd", strand == 1 ? 12 : 302).put("cdsStart", -5).put("cdsEnd", -3);
        exons.addObject().put("ordinal", 2).put("genomicStart", strand == 1 ? 20 : 200)
                .put("genomicEnd", strand == 1 ? 22 : 202).put("cdsStart", -2).put("cdsEnd", 0);
        exons.addObject().put("ordinal", 3).put("genomicStart", 100).put("genomicEnd", 105)
                .put("cdsStart", 1).put("cdsEnd", 6);
        assertNull(CodingTranscriptMapping.modelIssue(model, context()));
        assertEquals(0, CodingTranscriptMapping.cdsPosition(model, strand == 1 ? 10 : 302));
        assertEquals(0, CodingTranscriptMapping.cdsPosition(model, strand == 1 ? 22 : 200));
        assertEquals(1, CodingTranscriptMapping.cdsPosition(model, strand == 1 ? 100 : 105));
        assertEquals(6, CodingTranscriptMapping.cdsPosition(model, strand == 1 ? 105 : 100));
        assertEquals(3, CodingTranscriptMapping.exonForGenomicInterval(model, 100, 105).path("ordinal").asInt());
        model.put("lastJunctionCdsPosition", 0);
        assertEquals("PVS1_TRANSCRIPT_MODEL_JUNCTION_INVALID", TruncatingPvs1Model.issue(model, context()));

        ObjectNode complete = model.deepCopy();
        ((ObjectNode) model.path("exons").path(2)).put("cdsStart", 2).put("cdsEnd", 7);
        assertEquals("CODING_TRANSCRIPT_MODEL_EXON_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
        model = complete.deepCopy();
        ((ObjectNode) model.path("exons").path(2)).put("ordinal", 1);
        assertEquals("CODING_TRANSCRIPT_MODEL_EXON_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
        model = complete.deepCopy(); model.withArray("exons").remove(2);
        assertEquals("CODING_TRANSCRIPT_MODEL_EXON_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
    }

    @ParameterizedTest @CsvSource({"1,103,1", "1,108,6", "1,102,0", "1,99,0", "-1,105,1", "-1,100,6", "-1,106,0", "-1,99,0"})
    void genomicAndCdsCoordinatesAreOneBasedAndUtrIsNotCoding(int strand, int genomic, int expected) {
        assertEquals(expected, CodingTranscriptMapping.cdsPosition(singleExon(strand), genomic));
    }

    @ParameterizedTest @CsvSource({"1,ACGT,ACGT", "1,AGG,AGG", "-1,AGG,CCT", "-1,A,T"})
    void allelesAreOrientedAsSequencesNotOnlySingleBases(int strand, String forward, String transcript) {
        assertEquals(transcript, CodingTranscriptMapping.orientAllele(singleExon(strand), forward));
    }

    @Test void entireIntervalMustBeWithinOneUniqueExon() {
        ObjectNode model = singleExon(1);
        assertSame(model.path("exons").path(0), CodingTranscriptMapping.exonForGenomicInterval(model, 100, 108));
        assertNull(CodingTranscriptMapping.exonForGenomicInterval(model, 99, 103));
        assertNull(CodingTranscriptMapping.exonForGenomicInterval(model, 108, 109));
        assertThrows(IllegalArgumentException.class, () -> CodingTranscriptMapping.exonForGenomicInterval(model, 108, 100));
        assertThrows(IllegalArgumentException.class, () -> CodingTranscriptMapping.cdsPosition(model, 0));
        model.withArray("exons").add(model.path("exons").path(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> CodingTranscriptMapping.exonForGenomicInterval(model, 103, 103));
    }

    @Test void modelValidationRejectsIncompleteIdentitySequenceAndCoordinateContradictions() {
        assertEquals("CODING_TRANSCRIPT_MODEL_IDENTITY_INVALID", CodingTranscriptMapping.modelIssue(null, context()));
        ObjectNode model = singleExon(1);
        model.put("cdsSequence", "ATGTAGTAA").put("cdsLength", 9).put("proteinLength", 2);
        assertEquals("CODING_TRANSCRIPT_MODEL_SEQUENCE_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
        model = singleExon(1); ((ObjectNode) model.path("exons").path(0)).put("cdsEnd", 7);
        assertEquals("CODING_TRANSCRIPT_MODEL_EXON_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
        model = singleExon(1); model.put("cdsLength", "6");
        assertEquals("CODING_TRANSCRIPT_MODEL_SEQUENCE_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
        for (String field : new String[] {"strand", "cdsLength", "proteinLength"}) {
            model = singleExon(1); model.put(field, (1L << 32) + model.path(field).asInt());
            assertEquals("CODING_TRANSCRIPT_MODEL_SEQUENCE_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
        }
        model = singleExon(1); model.put("hgncId", "HGNC:2");
        assertEquals("CODING_TRANSCRIPT_MODEL_CONTEXT_MISMATCH", CodingTranscriptMapping.modelIssue(model, context()));
    }

    @Test void noImplicitStrandOrAmbiguousBaseFallback() {
        ObjectNode model = singleExon(1);
        assertThrows(IllegalArgumentException.class, () -> CodingTranscriptMapping.orientAllele(model, "N"));
        assertThrows(IllegalArgumentException.class, () -> CodingTranscriptMapping.orientAllele(model, ""));
        model.put("strand", 0);
        assertThrows(IllegalArgumentException.class, () -> CodingTranscriptMapping.orientAllele(model, "A"));
        assertThrows(IllegalArgumentException.class, () -> CodingTranscriptMapping.cdsPosition(model, 103));
    }

    @Test void nmdJunctionValidationRemainsSeparateFromTheSharedCodingModel() {
        ObjectNode model = singleExon(1);
        model.put("cdsSequence", "ATGCCACCATAA").put("cdsLength", 12).put("proteinLength", 3).put("lastJunctionCdsPosition", 7);
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 105)
                .put("cdsStart", 1).put("cdsEnd", 6);
        model.withArray("exons").addObject().put("ordinal", 2).put("genomicStart", 200).put("genomicEnd", 205)
                .put("cdsStart", 7).put("cdsEnd", 12);
        assertNull(CodingTranscriptMapping.modelIssue(model, context()));
        assertEquals("PVS1_TRANSCRIPT_MODEL_JUNCTION_INVALID", TruncatingPvs1Model.issue(model, context()));
        model.put("lastJunctionCdsPosition", 6);
        assertNull(TruncatingPvs1Model.issue(model, context()));
        model.put("lastJunctionCdsPosition", (1L << 32) + 6);
        assertEquals("PVS1_TRANSCRIPT_MODEL_JUNCTION_INVALID", TruncatingPvs1Model.issue(model, context()));
        assertNull(CodingTranscriptMapping.exonForGenomicInterval(model, 105, 200));
        assertEquals(7, CodingTranscriptMapping.cdsPosition(model, 200));
    }

    private ObjectNode context() {
        ObjectNode context = mapper.createObjectNode();
        context.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "SYNTHETIC");
        context.putObject("transcript").put("accession", "NM_000001").put("version", 1);
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1");
        return context;
    }

    @Test void pinnedOfflineCacheModelHasNoFabricatedRestUrlAndIsNotPolicyAuthority() {
        ObjectNode model = cacheModel();
        ObjectNode original = model.deepCopy();
        assertNull(CodingTranscriptMapping.modelIssue(model, context()));
        assertFalse(CodingTranscriptMapping.sourcesValid(model.path("sourceRecords")));
        assertEquals(original, model);
        model.put("transcript", "ENST000001.1").put("protein", "ENSP000001.1");
        ObjectNode ensembl = context(); ensembl.withObject("transcript").put("accession", "ENST000001");
        assertNull(CodingTranscriptMapping.modelIssue(model, ensembl));
        assertEquals("CODING_TRANSCRIPT_MODEL_CONTEXT_MISMATCH", CodingTranscriptMapping.modelIssue(model, context()));
    }

    @ParameterizedTest @ValueSource(strings = {"sourceId", "version", "sourceKind", "sha256", "objectSha256",
            "requestSha256", "exportSha256", "exportManifestSha256", "exporterSha256", "cacheInfoSha256", "apiSourceSha256"})
    void offlineCacheCannotLoseOrInventItsCapturedIdentities(String field) {
        ObjectNode model = cacheModel();
        ((ObjectNode) model.path("sourceRecords").path(1)).remove(field);
        assertEquals("CODING_TRANSCRIPT_MODEL_SOURCE_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
        model = cacheModel(); ((ObjectNode) model.path("sourceRecords").path(1)).put(field, "invalid");
        assertEquals("CODING_TRANSCRIPT_MODEL_SOURCE_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
    }

    @Test void completeApiPinsAndIndependentRnaRemainRequiredForCachedModel() {
        ObjectNode model = cacheModel();
        ObjectNode cache = (ObjectNode) model.path("sourceRecords").path(1);
        cache.put("url", "https://example.invalid/not-an-actual-rest-source");
        assertSourceInvalid(model);
        model = cacheModel(); model.withArray("sourceRecords").remove(0); assertSourceInvalid(model);
        model = cacheModel(); model.withArray("sourceRecords").add(model.path("sourceRecords").path(1).deepCopy()); assertSourceInvalid(model);
        model = cacheModel(); model.withObject("transcriptBridge").put("refseqProtein", "NP_999999.1"); assertSourceInvalid(model);
        model = cacheModel(); model.withObject("transcriptBridge").put("status", "CDS_ONLY"); assertSourceInvalid(model);
        model = cacheModel(); model.withObject("sequenceVerification").put("cdsSha256", "b".repeat(64)); assertSourceInvalid(model);
        model = cacheModel(); model.withObject("sequenceVerification").put("queriedBases", (1L << 32) + 9); assertSourceInvalid(model);
        model = cacheModel(); model.withObject("sequenceVerification").put("queriedBases", 5); assertSourceInvalid(model);
        model = cacheModel(); model.withObject("sequenceVerification").put("queriedBases", 6); assertSourceInvalid(model);
        model = cacheModel(); model.withObject("sequenceVerification").put("queriedBases", 1_000_001); assertSourceInvalid(model);
        model = cacheModel();
        ((ObjectNode) model.at("/sourceRecords/1/apiSourceSha256")).remove("Bio/EnsEMBL/Biotype.pm"); assertSourceInvalid(model);
        model = cacheModel();
        ((ObjectNode) model.at("/sourceRecords/1/apiSourceSha256")).put("arbitrary.pm", "a".repeat(64)); assertSourceInvalid(model);
        model = cacheModel();
        ((ObjectNode) model.at("/sourceRecords/1/apiSourceSha256")).put("Bio/EnsEMBL/Biotype.pm", 1); assertSourceInvalid(model);
        model = singleExon(1); ((ObjectNode) model.path("sourceRecords").path(0)).remove("url"); assertSourceInvalid(model);
    }

    private void assertSourceInvalid(ObjectNode model) {
        assertEquals("CODING_TRANSCRIPT_MODEL_SOURCE_INVALID", CodingTranscriptMapping.modelIssue(model, context()));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "unknown"})
    void cacheIdentityCannotDowngradeToAnHttpsSource(String kind) {
        ObjectNode model = cacheModel();
        ObjectNode cache = (ObjectNode) model.path("sourceRecords").path(1);
        if ("missing".equals(kind)) cache.remove("sourceKind"); else cache.put("sourceKind", "UNKNOWN_TYPING");
        cache.put("url", "https://example.invalid/not-the-cache");
        assertSourceInvalid(model);
        cache.remove("apiSourceSha256"); cache.remove("cacheInfoSha256");
        model.withArray("sourceRecords").remove(0);
        model.remove("sequenceVerification"); model.remove("transcriptBridge");
        assertSourceInvalid(model);
    }

    @ParameterizedTest @ValueSource(strings = {"sourceId", "version", "objectSha256", "requestSha256", "exportSha256",
            "exportManifestSha256", "exporterSha256", "cacheInfoSha256", "apiSourceSha256"})
    void anyRemainingCacheMarkerRequiresTheCompleteTypedContract(String field) {
        ObjectNode model = singleExon(1);
        ObjectNode source = (ObjectNode) model.path("sourceRecords").path(0);
        source.set(field, cacheModel().path("sourceRecords").path(1).path(field));
        assertSourceInvalid(model);
    }

    private ObjectNode cacheModel() {
        ObjectNode model = singleExon(1);
        ((ObjectNode) model.path("sourceRecords").path(0)).put("sourceKind", "NCBI_VERSIONED_REFSEQ_GENBANK")
                .put("sourceId", "RefSeq:NM_000001.1");
        model.putObject("transcriptBridge").put("refseqTranscript", "NM_000001.1").put("refseqProtein", "NP_000001.1")
                .put("ensemblTranscript", "ENST000001.1").put("ensemblProtein", "ENSP000001.1")
                .put("status", "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE");
        model.putObject("sequenceVerification").put("fastaIndexSha256", "a".repeat(64))
                .put("fullSplicedTranscriptSha256", "b".repeat(64)).put("queriedBases", 9)
                .put("cdsSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha("ATGTAA"));
        ObjectNode cache = model.withArray("sourceRecords").addObject()
                .put("sourceKind", "VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST")
                .put("sourceId", "VEP116_CACHE:ENST000001.1").put("version", "116.GRCh38:exporter-1.0.1");
        for (String field : new String[] {"sha256", "objectSha256", "requestSha256", "exportSha256",
                "exportManifestSha256", "exporterSha256", "cacheInfoSha256"}) cache.put(field, "a".repeat(64));
        ObjectNode api = cache.putObject("apiSourceSha256");
        for (String field : new String[] {"ApiVersion", "Biotype", "CoordSystem", "Exon", "Feature", "Slice", "Transcript", "Translation"})
            api.put("Bio/EnsEMBL/" + field + ".pm", "a".repeat(64));
        api.put("Bio/EnsEMBL/Variation/Utils/BaseVepPlugin.pm", "a".repeat(64));
        return model;
    }

    private ObjectNode singleExon(int strand) {
        ObjectNode model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "synthetic-model").put("modelVersion", "1").put("assembly", "GRCh38").put("contig", "1")
                .put("hgncId", "HGNC:1").put("geneSymbol", "SYNTHETIC").put("transcript", "NM_000001.1")
                .put("protein", "NP_000001.1").put("strand", strand).put("cdsSequence", "ATGTAA").put("cdsLength", 6).put("proteinLength", 1);
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 108)
                .put("cdsStart", -2).put("cdsEnd", 6);
        model.putArray("sourceRecords").addObject().put("sourceId", "synthetic-source").put("version", "1")
                .put("url", "https://example.invalid/synthetic").put("sha256", "a".repeat(64));
        return model;
    }
}
