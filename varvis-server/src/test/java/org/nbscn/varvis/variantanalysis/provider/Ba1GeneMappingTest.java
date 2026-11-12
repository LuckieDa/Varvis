package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

class Ba1GeneMappingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private Ba1SpecificationCatalog catalog() {
        var snapshot = mapper.createObjectNode();
        var genes = snapshot.putArray("geneMappings");
        var first = genes.addObject().put("hgncId", "HGNC:100");
        first.putArray("ensemblGeneIds").add("ENSG_SYNTHETIC_ONE");
        first.putArray("geneSymbols").add("SYNTHETIC_ONE");
        var second = genes.addObject().put("hgncId", "HGNC:200");
        second.putArray("ensemblGeneIds").add("ENSG_SYNTHETIC_TWO");
        second.putArray("geneSymbols").add("SYNTHETIC_TWO");
        snapshot.putObject("geneMappingSource").put("status", "COMPLETE").put("rawSha256", "a".repeat(64))
                .put("url", "https://example.test/synthetic-hgnc").put("recordCount", 2);
        return new Ba1SpecificationCatalog(mapper, snapshot);
    }

    private TranscriptConsequence annotation(String geneId, String symbol) throws Exception {
        return mapper.treeToValue(mapper.createObjectNode().put("geneId", geneId).put("geneSymbol", symbol)
                .put("source", "VEP_CACHED").put("version", "116.0").put("status", "COMPLETED"), TranscriptConsequence.class);
    }

    private ObjectNode mapping(List<TranscriptConsequence> annotations) {
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38", "1", 100, "A", "T"));
        candidate.applyTranscriptAnnotations(annotations);
        return catalog().mapping(candidate);
    }

    @Test void allSuccessAndDuplicateAnnotationsKeepOneIdentityAndEveryCheckedRow() throws Exception {
        var a = annotation("ENSG_SYNTHETIC_ONE", "SYNTHETIC_ONE");
        var result = mapping(List.of(a, a));
        assertEquals("RESOLVED", result.path("status").asText());
        assertEquals(1, result.path("hgncIds").size());
        assertEquals(2, result.path("sources").size());
        assertEquals(2, result.path("checkedAnnotationCount").asInt());
        assertEquals(0, result.path("uncheckedAnnotationCount").asInt());
        assertFalse(result.has("failure"));
    }

    @Test void laterMissingMappingRetainsSuccessFailureAndUncheckedBoundary() throws Exception {
        var a = annotation("ENSG_SYNTHETIC_ONE", "SYNTHETIC_ONE");
        var result = mapping(List.of(a, annotation("ENSG_SYNTHETIC_MISSING", null), a));
        assertEquals("UNRESOLVED", result.path("status").asText());
        assertEquals(0, result.path("hgncIds").size());
        assertEquals(1, result.path("sources").size());
        assertEquals(0, result.at("/sources/0/annotationIndex").asInt());
        assertEquals(1, result.at("/failure/annotationIndex").asInt());
        assertEquals("BA1_MAPPING_ENSEMBL_NOT_FOUND", result.at("/failure/reasonCode").asText());
        assertEquals("geneId", result.at("/failure/field").asText());
        assertEquals("ENSG_SYNTHETIC_MISSING", result.at("/failure/geneId").asText());
        assertTrue(result.at("/failure/geneSymbol").isNull());
        assertEquals(0, result.at("/failure/ensemblMatches").size());
        assertEquals(2, result.path("checkedAnnotationCount").asInt());
        assertEquals(1, result.path("uncheckedAnnotationCount").asInt());
        assertEquals(result, mapper.readTree(mapper.writeValueAsBytes(result)));
    }

    @Test void identifierConflictPreservesBothLookupResults() throws Exception {
        var result = mapping(List.of(annotation("ENSG_SYNTHETIC_ONE", "SYNTHETIC_TWO")));
        assertEquals("BA1_MAPPING_IDENTIFIER_CONFLICT", result.at("/failure/reasonCode").asText());
        assertEquals("HGNC:100", result.at("/failure/ensemblMatches/0").asText());
        assertEquals("HGNC:200", result.at("/failure/symbolMatches/0").asText());
        assertEquals("UNRESOLVED", result.path("status").asText());
    }
}
