package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Pvs1StartLossRefSeqInventoryTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode context() {
        var c = mapper.createObjectNode(); c.putObject("gene").put("symbol", "TEST").put("hgncId", "HGNC:1");
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1"); return c;
    }
    ObjectNode metadata() {
        var d = mapper.createObjectNode().put("total_count", 1);
        var g = d.putArray("reports").addObject().putObject("gene").put("gene_id", "1").put("symbol", "TEST")
                .put("tax_id", "9606").put("transcript_count", 1);
        g.putObject("nomenclature_authority").put("authority", "HGNC").put("identifier", "HGNC:1"); return d;
    }
    ObjectNode products(boolean minus) {
        var d = mapper.createObjectNode().put("total_count", 1);
        var p = d.putArray("reports").addObject().putObject("product").put("gene_id", "1").put("symbol", "TEST").put("tax_id", "9606");
        var t = p.putArray("transcripts").addObject().put("accession_version", "NM_000001.1").put("length", 12);
        t.putObject("cds").put("accession_version", "NM_000001.1").putArray("range").addObject()
                .put("begin", "4").put("end", "12").put("orientation", "plus");
        var l = t.putArray("genomic_locations").addObject().put("genomic_accession_version", "NC_000001.11");
        String orientation = minus ? "minus" : "plus"; l.putObject("genomic_range").put("orientation", orientation);
        var e = l.putArray("exons");
        e.addObject().put("order", 1).put("begin", minus ? 200 : 100).put("end", minus ? 205 : 105).put("orientation", orientation);
        e.addObject().put("order", 2).put("begin", minus ? 100 : 200).put("end", minus ? 105 : 205).put("orientation", orientation); return d;
    }
    Pvs1StartLossPublicSources.Snapshot snapshot(ObjectNode d) throws Exception {
        return new Pvs1StartLossPublicSources.Snapshot(mapper.createObjectNode().put("status", "AVAILABLE")
                .put("sha256", CodingTranscriptMaterialSupply.sha(mapper.writeValueAsBytes(d))), d);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inclusiveTranscriptCoordinatesMapAcrossExonsWithoutClaimingFunction(boolean minus) throws Exception {
        var result = Pvs1StartLossRefSeqInventory.read(mapper, context(), snapshot(metadata()), snapshot(products(minus)));
        assertTrue(result.path("sourceCoverageComplete").asBoolean(), result.toPrettyString());
        var t = result.at("/transcripts/0"); assertEquals("AVAILABLE", t.path("structureStatus").asText());
        assertEquals(9, t.path("cdsLength").asInt());
        assertEquals(minus ? java.util.List.of(202, 201, 200) : java.util.List.of(103, 104, 105),
                Pvs1StartLossAutomaticPreparation.startPositions(t));
        assertFalse(t.path("sequenceVerified").asBoolean()); assertFalse(result.path("functionalCoverageComplete").asBoolean());
        var review = Pvs1StartLossTranscriptQualifier.structural(mapper, context(), t, result, "a".repeat(64));
        assertEquals("REFSEQ_SEQUENCE_NOT_VERIFIED", review.at("/findings/0/reasonCode").asText());
        assertFalse(review.path("structuralReviewComplete").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings = {"page", "count", "gene", "version", "length"})
    void incompleteOrMismatchedNativeRecordsNeverBecomeQualified(String mutation) throws Exception {
        var m = metadata(); var p = products(false);
        switch (mutation) {
            case "page" -> p.put("next_page_token", "next");
            case "count" -> ((ObjectNode)m.at("/reports/0/gene")).put("transcript_count", 2);
            case "gene" -> ((ObjectNode)m.at("/reports/0/gene/nomenclature_authority")).put("identifier", "HGNC:2");
            case "version" -> ((ObjectNode)p.at("/reports/0/product/transcripts/0/genomic_locations/0")).put("genomic_accession_version", "NC_000001.10");
            default -> ((ObjectNode)p.at("/reports/0/product/transcripts/0")).put("length", 13);
        }
        var result = Pvs1StartLossRefSeqInventory.read(mapper, context(), snapshot(m), snapshot(p));
        if (java.util.Set.of("version", "length").contains(mutation)) {
            assertEquals(1, result.path("transcripts").size());
            assertEquals("UNRESOLVED", result.at("/transcripts/0/structureStatus").asText());
        } else assertFalse(result.path("sourceCoverageComplete").asBoolean());
        assertFalse(result.path("functionalCoverageComplete").asBoolean());
    }
    @Test void explicitNoncodingProductIsExcludedBeforeRequiringProteinRnaVerification() throws Exception {
        var product = products(false);
        var row = (ObjectNode) product.at("/reports/0/product/transcripts/0");
        row.put("accession_version", "NR_000001.1").put("type", "NON_CODING"); row.remove("cds");
        var inventory = Pvs1StartLossRefSeqInventory.read(mapper, context(), snapshot(metadata()), snapshot(product));
        assertTrue(inventory.path("sourceCoverageComplete").asBoolean());
        assertEquals("ncRNA", inventory.at("/transcripts/0/transcriptType").asText());
        var model = mapper.createObjectNode().put("transcript", "NM_000001.1").put("strand", 1);
        model.putArray("exons").addObject().put("cdsStart", 1).put("cdsEnd", 3).put("genomicStart", 100).put("genomicEnd", 102);
        var review = Pvs1StartLossTranscriptQualifier.structural(mapper, context(), model, inventory, "a".repeat(64));
        assertEquals("ANNOTATED_NONCODING_TRANSCRIPT", review.at("/findings/0/reasonCode").asText());
        assertEquals("EXCLUDED", review.at("/findings/0/disposition").asText());
        assertFalse(review.path("complete").asBoolean()); // The selected protein transcript is not in this subset.
    }
    @Test void capturedNcbiOriginalReplaysExactMinusStrandGeometry() throws Exception {
        String root = System.getProperty("pvs1.nativeExpertRoot"); assumeTrue(root != null);
        var directory = Path.of(root, "local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        var gene = (ObjectNode) mapper.readTree(Files.readAllBytes(directory.resolve("ncbi-mecp2-gene.json")));
        var product = (ObjectNode) mapper.readTree(Files.readAllBytes(directory.resolve("ncbi-mecp2-product.json")));
        var c = context(); c.withObject("gene").put("symbol", "MECP2").put("hgncId", "HGNC:6990");
        c.withObject("variant").withObject("evidenceQueryAllele").put("contig", "X");
        var result = Pvs1StartLossRefSeqInventory.read(mapper, c, snapshot(gene), snapshot(product));
        assertTrue(result.path("sourceCoverageComplete").asBoolean(), result.toPrettyString());
        assertEquals(28, result.path("transcripts").size());
        var t = result.at("/transcripts/0"); assertEquals("NM_001110792.2", t.path("transcript").asText());
        assertEquals(java.util.List.of(154097665, 154097664, 154097663), Pvs1StartLossAutomaticPreparation.startPositions(t));
        assertFalse(result.path("functionalCoverageComplete").asBoolean());
    }
}
