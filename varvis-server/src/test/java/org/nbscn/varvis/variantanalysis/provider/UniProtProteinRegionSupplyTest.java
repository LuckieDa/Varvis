package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** All records here are SYNTHETIC/TEST_ONLY; upstream Swiss-Prot labels do not grant scientific review. */
class UniProtProteinRegionSupplyTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    ObjectNode model() { return new Pm4TestFixtures(mapper, 1).model; }
    ObjectNode raw(String id) {
        var node = mapper.createObjectNode().put("primaryAccession", id).put("entryType", "UniProtKB reviewed (Swiss-Prot)");
        node.putObject("organism").put("taxonId", 9606);
        node.putObject("entryAudit").put("entryVersion", 3).put("sequenceVersion", 2);
        node.putObject("sequence").put("value", "MEPGFK").put("length", 6);
        var refs = node.putArray("uniProtKBCrossReferences");
        refs.addObject().put("database", "HGNC").put("id", "HGNC:4827");
        refs.addObject().put("database", "Ensembl").put("id", "ENST000001.1")
                .putArray("properties").addObject().put("key", "ProteinId").put("value", "ENSP000001.1");
        node.putArray("features").add(feature("Repeat", 2, 5)).add(feature("Domain", 3, 4));
        return node;
    }
    ObjectNode feature(String type, int start, int end) {
        var f = mapper.createObjectNode().put("type", type).put("description", "SYNTHETIC_TEST_ONLY");
        var location = f.putObject("location");
        location.putObject("start").put("value", start).put("modifier", "EXACT");
        location.putObject("end").put("value", end).put("modifier", "EXACT");
        f.putArray("evidences").addObject().put("evidenceCode", "ECO:0000255").put("source", "TEST_ONLY").put("id", "synthetic");
        return f;
    }
    ObjectNode acquisition(ObjectNode raw) throws Exception {
        String id = raw.path("primaryAccession").asText(); byte[] bytes = mapper.writeValueAsBytes(raw);
        Files.write(root.resolve(id + ".json"), bytes);
        return mapper.createObjectNode().put("accession", id).put("success", true).put("status", 200)
                .put("url", "https://rest.uniprot.org/uniprotkb/" + id + ".json")
                .put("startedAt", "2026-10-13T00:00:00Z").put("finishedAt", "2026-10-13T00:00:01Z")
                .put("headers", "X-UniProt-Release: 2026_03\r\n").put("bytes", bytes.length)
                .put("sha256", CodingTranscriptMaterialSupply.sha(bytes));
    }
    UniProtProteinRegionSupply supply(ObjectNode... records) throws Exception {
        var receipts = mapper.createArrayNode();
        for (var raw : records) receipts.add(acquisition(raw));
        Files.write(root.resolve("acquisition.json"), mapper.writeValueAsBytes(receipts));
        var manifest = UniProtProteinRegionPreparation.prepare(mapper, root);
        return fromManifest(manifest);
    }
    UniProtProteinRegionSupply fromManifest(ObjectNode manifest) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
        return new UniProtProteinRegionSupply(mapper, root, CodingTranscriptMaterialSupply.sha(bytes));
    }

    @Test void fullSequenceAndVersionedPairMapRepeatAndFunctionWithoutApprovingEitherRule() throws Exception {
        var raw = raw("P12345"); var sourceBefore = raw.deepCopy(); var model = model(); var modelBefore = model.deepCopy();
        var result = supply(raw).mapModel(model, 2, 4);
        assertEquals("READY", result.path("status").asText(), result.toString());
        assertEquals(2, result.path("features").size());
        assertEquals(raw.path("features").get(0), mapper.readTree(result.at("/features/0/rawFeatureJson").asText()));
        assertTrue(result.at("/features/0/coversEntireQueryRange").asBoolean());
        assertFalse(result.at("/features/1/coversEntireQueryRange").asBoolean());
        assertEquals(1, result.at("/featureSummary/repeatOverlaps").asInt());
        assertEquals(1, result.at("/featureSummary/otherRegionOverlaps").asInt());
        assertEquals("UNREVIEWED", result.path("scientificAcceptance").asText());
        assertFalse(result.path("negativeCoverageEstablished").asBoolean(true));
        assertEquals("NOT_EVALUATED", result.path("repeatConclusion").asText());
        assertEquals("NOT_EVALUATED", result.path("functionalConclusion").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEPGFK"), result.path("proteinSequenceSha256WithoutStop").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEPGFK*"), result.path("translatedSequenceSha256WithStop").asText());
        assertEquals(sourceBefore, raw); assertEquals(modelBefore, model);
        assertFalse(result.toString().contains(Pm4TestFixtures.CDS));
    }

    @Test void refseqPairSupportedWithoutGeneSymbolHeuristics() throws Exception {
        var source = raw("P12345"); var refs = (ArrayNode) source.path("uniProtKBCrossReferences"); refs.remove(1);
        refs.addObject().put("database", "RefSeq").put("id", "NP_000001.2").putArray("properties")
                .addObject().put("key", "NucleotideSequenceId").put("value", "NM_000001.3");
        var model = model().put("transcript", "NM_000001.3").put("protein", "NP_000001.2").put("geneSymbol", "DIFFERENT_DISPLAY_NAME");
        assertEquals("READY", supply(source).mapModel(model, 2, 3).path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings={"protein-version", "transcript-version", "unversioned", "sequence", "gene", "duplicate-property", "isoform"})
    void noNameLengthOrNeighborVersionFallback(String kind) throws Exception {
        var raw = raw("P12345"); var model = model(); var xref = (ObjectNode) raw.path("uniProtKBCrossReferences").get(1);
        switch (kind) {
            case "protein-version" -> model.put("protein", "ENSP000001.2");
            case "transcript-version" -> model.put("transcript", "ENST000001.2");
            case "unversioned" -> xref.put("id", "ENST000001");
            case "sequence" -> ((ObjectNode) raw.path("sequence")).put("value", "MEPGFL");
            case "gene" -> model.put("hgncId", "HGNC:999");
            case "duplicate-property" -> xref.withArray("properties").addObject().put("key", "ProteinId").put("value", "ENSP000001.1");
            case "isoform" -> xref.put("isoformId", "P12345-2");
            default -> fail();
        }
        var result = supply(raw).mapModel(model, 2, 3);
        assertEquals("UNAVAILABLE", result.path("status").asText(), result.toString());
        assertFalse(result.has("features"));
        assertTrue(result.path("reasonCode").asText().startsWith("PROTEIN_REGION_"));
    }

    @Test void explicitDisplayedIsoformAcceptedButOtherIsoformFeatureNotMapped() throws Exception {
        var raw = raw("P12345"); ((ObjectNode) raw.path("uniProtKBCrossReferences").get(1)).put("isoformId", "P12345-1");
        raw.putArray("comments").addObject().put("commentType", "ALTERNATIVE PRODUCTS").putArray("isoforms")
                .addObject().put("isoformSequenceStatus", "Displayed").putArray("isoformIds").add("P12345-1");
        ((ObjectNode) raw.at("/features/0/location")).put("sequence", "P12345-2");
        var result = supply(raw).mapModel(model(), 2, 4);
        assertEquals("READY", result.path("status").asText());
        assertEquals(1, result.path("features").size());
        assertEquals("FEATURE_OTHER_ISOFORM", result.at("/unmappedFeatures/0/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings={"wrong-field", "described", "missing", "different-id"})
    void isoformRequiresNativeDisplayedIdentity(String fault) throws Exception {
        var raw = raw("P12345"); ((ObjectNode)raw.path("uniProtKBCrossReferences").get(1)).put("isoformId", "P12345-1");
        var isoform = raw.putArray("comments").addObject().put("commentType", "ALTERNATIVE PRODUCTS").putArray("isoforms").addObject();
        isoform.putArray("isoformIds").add("different-id".equals(fault) ? "P12345-2" : "P12345-1");
        if ("wrong-field".equals(fault)) isoform.put("sequenceStatus", "Displayed");
        else if (!"missing".equals(fault)) isoform.put("isoformSequenceStatus", "described".equals(fault) ? "Described" : "Displayed");
        var result = supply(raw).mapModel(model(),2,4);
        assertEquals("UNAVAILABLE", result.path("status").asText());
        assertEquals("PROTEIN_REGION_NONCANONICAL_ISOFORM_XREF", result.at("/mappingAttempts/0/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings={"fuzzy", "missing-position", "decimal", "negative", "reversed", "outside", "huge"})
    void unknownOrInvalidCoordinatesRemainUnmappedWithoutDroppingSourceEvidence(String kind) throws Exception {
        var raw = raw("P12345"); var loc = (ObjectNode) raw.at("/features/0/location");
        var start = (ObjectNode) loc.path("start"); var end = (ObjectNode) loc.path("end");
        switch (kind) {
            case "fuzzy" -> start.put("modifier", "OUTSIDE");
            case "missing-position" -> start.remove("value");
            case "decimal" -> start.put("value", 2.5);
            case "negative" -> start.put("value", -1);
            case "reversed" -> end.put("value", 1);
            case "outside" -> end.put("value", 7);
            case "huge" -> end.put("value", 4294967299L);
            default -> fail();
        }
        var result = supply(raw).mapModel(model(), 2, 3);
        assertEquals("READY", result.path("status").asText());
        assertEquals(1, result.path("unmappedFeatures").size());
        assertEquals(raw.path("features").get(0), mapper.readTree(result.at("/unmappedFeatures/0/rawFeatureJson").asText()));
        assertDoesNotThrow(() -> new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(result));
        assertEquals(0, result.at("/featureSummary/repeatOverlaps").asInt());
        assertFalse(result.path("negativeCoverageEstablished").asBoolean(true));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void emptyListAndNoOverlapAreNotNegativeScientificEvidence(boolean empty) throws Exception {
        var raw = raw("P12345"); if (empty) raw.putArray("features");
        var result = supply(raw).mapModel(model(), 6, 6);
        assertEquals("READY", result.path("status").asText());
        assertTrue(result.path("features").isEmpty());
        assertEquals("NOT_ASSERTED_COMPLETE", result.at("/featureSummary/annotationCoverage").asText());
        assertEquals("NOT_EVALUATED", result.path("functionalConclusion").asText());
    }

    @ParameterizedTest @ValueSource(strings={"missing-features", "taxon", "unreviewed", "length", "ambiguous-amino-acid", "hgnc", "sequence-version"})
    void malformedNativeRecordsRejectedBeforeIndexing(String kind) throws Exception {
        var raw = raw("P12345");
        switch (kind) {
            case "missing-features" -> raw.remove("features");
            case "taxon" -> ((ObjectNode) raw.path("organism")).put("taxonId", 10090);
            case "unreviewed" -> raw.put("entryType", "UniProtKB unreviewed (TrEMBL)");
            case "length" -> ((ObjectNode) raw.path("sequence")).put("length", 7);
            case "ambiguous-amino-acid" -> ((ObjectNode) raw.path("sequence")).put("value", "MEPGFX");
            case "hgnc" -> ((ArrayNode) raw.path("uniProtKBCrossReferences")).remove(0);
            case "sequence-version" -> ((ObjectNode) raw.path("entryAudit")).put("sequenceVersion", 0);
            default -> fail();
        }
        assertThrows(IllegalArgumentException.class, () -> supply(raw));
    }

    @Test void multipleMatchingRecordsBlockInsteadOfSelectingFavorableFeature() throws Exception {
        var a = raw("P12345"); var b = raw("P12346"); b.putArray("features");
        var result = supply(a,b).mapModel(model(), 2, 4);
        assertEquals("PROTEIN_REGION_MULTIPLE_SOURCE_RECORDS", result.path("reasonCode").asText());
        assertFalse(result.has("features")); assertEquals(2, result.path("mappingAttempts").size());
    }

    @ParameterizedTest @ValueSource(strings={"source", "manifest", "missing"})
    void oldSuccessNotReusedAfterResourceChanges(String kind) throws Exception {
        var supply = supply(raw("P12345")); assertEquals("READY", supply.mapModel(model(), 2, 4).path("status").asText());
        if (kind.equals("missing")) Files.delete(root.resolve("P12345.json"));
        else Files.writeString(root.resolve(kind.equals("source") ? "P12345.json" : "manifest.json"), "{}");
        var result = supply.mapModel(model(), 2, 4);
        assertEquals("UNAVAILABLE", result.path("status").asText()); assertFalse(result.has("features"));
    }

    @Test void geneIndexCannotBeForgedToAssociateDifferentGene() throws Exception {
        supply(raw("P12345")); var manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(root.resolve("manifest.json")));
        ((ObjectNode) manifest.path("entries").get(0)).putArray("hgncIds").add("HGNC:999");
        var result = fromManifest(manifest).mapModel(model().put("hgncId", "HGNC:999"), 2, 4);
        assertEquals("PROTEIN_REGION_INDEX_SOURCE_MISMATCH", result.path("reasonCode").asText());
    }

    @Test void pathTraversalAndDuplicateEntriesAreRejected() throws Exception {
        supply(raw("P12345")); var manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(root.resolve("manifest.json")));
        var entry = (ObjectNode) manifest.path("entries").get(0);
        entry.put("file", "../P12345.json"); assertThrows(IllegalArgumentException.class, () -> fromManifest(manifest));
        entry.put("file", "P12345.json"); manifest.withArray("entries").add(entry.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> fromManifest(manifest));
    }

    @Test void cancellationRemainsVisible() throws Exception {
        var supply = supply(raw("P12345")); Thread.currentThread().interrupt();
        try {
            assertEquals("PROTEIN_REGION_INTERRUPTED", supply.mapModel(model(), 2, 4).path("reasonCode").asText());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest @ValueSource(ints={0,7,Integer.MAX_VALUE})
    void queryRangeNeverClipped(int end) throws Exception {
        assertEquals("PROTEIN_REGION_RANGE_INVALID", supply(raw("P12345")).mapModel(model(), 2, end).path("reasonCode").asText());
    }

    @Test void productionMaterialRangeAndIdentityPassThroughWithoutManualProjection() throws Exception {
        var fixture = new ProteinLengthChangeMaterialSupplyTest(); fixture.root = Files.createDirectory(root.resolve("transcript"));
        fixture.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var material = fixture.prebuilt().resolveProteinLengthChange(fixture.data.context, fixture.data.candidate);
        var result = supply(raw("P12345")).resolve(material);
        assertEquals("READY", result.path("status").asText(), result.toString());
        assertEquals(material.snapshot().at("/alleleAssociation/equivalentEditEnvelope"), result.path("equivalentEditEnvelope"));
        assertEquals(fixture.data.context.path("contextId"), result.path("contextId"));
        assertEquals(fixture.data.context.at("/variant/evidenceQueryAllele"), result.path("queryAllele"));
        var unavailable = fixture.prebuilt().resolve(fixture.data.context, fixture.data.candidate);
        assertEquals("PROTEIN_REGION_SEQUENCE_MATERIAL_UNAVAILABLE", supply(raw("P12345")).resolve(unavailable).path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings={"checksum", "release", "url", "status", "time"})
    void acquisitionMustBindActualOfficialResponse(String kind) throws Exception {
        var receipt = acquisition(raw("P12345"));
        switch (kind) {
            case "checksum" -> receipt.put("sha256", "a".repeat(64));
            case "release" -> receipt.put("headers", "");
            case "url" -> receipt.put("url", "https://example.org/P12345.json");
            case "status" -> receipt.put("status", 404);
            case "time" -> receipt.put("finishedAt", "2020-01-01T00:00:00Z");
            default -> fail();
        }
        Files.write(root.resolve("acquisition.json"), mapper.writeValueAsBytes(mapper.createArrayNode().add(receipt)));
        assertThrows(IllegalArgumentException.class, () -> UniProtProteinRegionPreparation.prepare(mapper, root));
    }

    @Test void mixedReleasesRefusedAndFailedAcquisitionCountPreserved() throws Exception {
        var a = acquisition(raw("P12345")); var b = acquisition(raw("P12346")).put("headers", "X-UniProt-Release: 2025_01\n");
        var receipts = mapper.createArrayNode().add(a).add(b);
        Files.write(root.resolve("acquisition.json"), mapper.writeValueAsBytes(receipts));
        assertThrows(IllegalArgumentException.class, () -> UniProtProteinRegionPreparation.prepare(mapper, root));
        receipts.remove(1); receipts.addObject().put("accession", "P12346").put("success", false).put("error", "TEST_ONLY_TIMEOUT");
        Files.write(root.resolve("acquisition.json"), mapper.writeValueAsBytes(receipts));
        assertEquals(1, UniProtProteinRegionPreparation.prepare(mapper, root).path("failedAcquisitions").asInt());
        UniProtProteinRegionPreparation.main(new String[]{root.toString()});
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> UniProtProteinRegionPreparation.main(new String[]{root.toString()}));
    }

    @Test void duplicateJsonFieldsCannotAlterIdentity() throws Exception {
        var receipt = acquisition(raw("P12345"));
        byte[] bytes = Files.readAllBytes(root.resolve("P12345.json"));
        String altered = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("\"primaryAccession\":", "\"primaryAccession\":\"P99999\",\"primaryAccession\":");
        bytes = altered.getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(root.resolve("P12345.json"), bytes);
        receipt.put("bytes", bytes.length).put("sha256", CodingTranscriptMaterialSupply.sha(bytes));
        Files.write(root.resolve("acquisition.json"), mapper.writeValueAsBytes(mapper.createArrayNode().add(receipt)));
        assertThrows(java.io.IOException.class, () -> UniProtProteinRegionPreparation.prepare(mapper, root));
    }

    @Test void outOfRangeTaxonMustNotWrapToHuman() {
        var raw = raw("P12345"); ((ObjectNode) raw.path("organism")).put("taxonId", 4294976902L);
        assertThrows(IllegalArgumentException.class, () -> supply(raw));
    }

    @Test void outOfRangeHttpStatusMustNotWrapToSuccess() throws Exception {
        var receipt = acquisition(raw("P12345")).put("status", 4294967496L);
        Files.write(root.resolve("acquisition.json"), mapper.writeValueAsBytes(mapper.createArrayNode().add(receipt)));
        assertThrows(IllegalArgumentException.class, () -> UniProtProteinRegionPreparation.prepare(mapper, root));
    }

    @Test void emptyThenDuplicateIdentityPropertyCannotBecomeValid() throws Exception {
        var raw = raw("P12345"); var properties = (ArrayNode) raw.at("/uniProtKBCrossReferences/1/properties");
        ((ObjectNode) properties.get(0)).put("value", "");
        properties.addObject().put("key", "ProteinId").put("value", "ENSP000001.1");
        assertEquals("UNAVAILABLE", supply(raw).mapModel(model(), 2, 3).path("status").asText());
    }

    @Test void oldSnapshotFormatLoadsUnderExplicitNewReaderIdentity() throws Exception {
        supply(raw("P12345")); var manifest = (ObjectNode)mapper.readTree(Files.readAllBytes(root.resolve("manifest.json")));
        manifest.put("methodVersion", "1.0.0");
        var result = fromManifest(manifest).mapModel(model(),2,4);
        assertEquals("READY",result.path("status").asText());
        assertEquals("1.0.1",result.path("version").asText());
        manifest.put("methodVersion", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> fromManifest(manifest));
    }

    @Test void manifestByteCountMustMatchActualRecordEvenWithCorrectHash() throws Exception {
        supply(raw("P12345")); var manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(root.resolve("manifest.json")));
        ((ObjectNode) manifest.path("entries").get(0)).put("bytes", 1);
        var result = fromManifest(manifest).mapModel(model(), 2, 3);
        assertEquals("UNAVAILABLE", result.path("status").asText());
        assertEquals("PROTEIN_REGION_SOURCE_SIZE_MISMATCH", result.path("reasonCode").asText());
    }
}
