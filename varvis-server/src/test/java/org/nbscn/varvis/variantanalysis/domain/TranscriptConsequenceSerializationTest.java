package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptConsequenceSerializationTest {
    // Historical published representation; its bytes participate in request reuse identity.
    private static final List<String> FIELDS = List.of("geneSymbol", "entrezGeneId", "transcriptAccession",
            "hgvsCoding", "hgvsProtein", "consequence", "geneId", "transcriptId", "transcriptSource",
            "impact", "exon", "intron", "canonical", "maneSelect", "manePlusClinical", "proteinPosition",
            "aminoAcids", "codons", "lof", "lofFilter", "lofFlags", "source", "status", "version",
            "timestamp", "hgvsp", "hgvsc");

    @Test void publicationOrderAndHgvsAliasesRemainStable() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var annotation = mapper.readValue("""
                {"geneSymbol":"TEST", "transcriptAccession":"ENST000001.2",
                 "hgvsCoding":"ENST000001.2:c.10A>G", "hgvsProtein":"ENSP000001.1:p.Lys4Glu",
                 "consequence":"missense_variant", "timestamp":"2026-10-10T01:02:03.123456700Z"}
                """, TranscriptConsequence.class);
        var tree = mapper.valueToTree(annotation);
        var fields = new ArrayList<String>(); tree.fieldNames().forEachRemaining(fields::add);
        assertEquals(FIELDS, fields);
        assertEquals(tree.path("hgvsCoding"), tree.path("hgvsc"));
        assertEquals(tree.path("hgvsProtein"), tree.path("hgvsp"));
        assertEquals("2026-10-10T01:02:03.123456700Z", tree.path("timestamp").asText());
        assertEquals(mapper.writeValueAsString(annotation), mapper.writeValueAsString(
                mapper.treeToValue(tree, TranscriptConsequence.class)));
    }

    @Test void meaningfulAnnotationChangeStillChangesPublishedBytes() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var first = new TranscriptConsequence("TEST", "1", "ENST000001.2", "c.10A>G", "p.Lys4Glu", "missense_variant");
        var changed = new TranscriptConsequence("TEST", "1", "ENST000001.2", "c.10A>T", "p.Lys4Ter", "stop_gained");
        assertNotEquals(mapper.writeValueAsString(first), mapper.writeValueAsString(changed));
    }

    @Test void optionalExonPositionRemainsCompleteAndBeforeHgvsAliases() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var input = mapper.createObjectNode().put("geneSymbol", "TEST").put("consequence", "synonymous_variant");
        var position = new SynonymousExonPosition("GRCh38", "1", 102, "A", "G", "ENST000001.2",
                "ENSE000001", 1, 100, 110, "ENSE000001|2|start|11", "116.0", SynonymousExonPosition.PLUGIN_SHA256);
        input.set("synonymousExonPosition", mapper.valueToTree(position));
        var annotation = mapper.treeToValue(input, TranscriptConsequence.class);
        var actual = mapper.valueToTree(annotation);
        assertEquals(input.path("synonymousExonPosition"), actual.path("synonymousExonPosition"));
        var expectedFields = new ArrayList<>(FIELDS); expectedFields.add(25, "synonymousExonPosition");
        var fields = new ArrayList<String>(); actual.fieldNames().forEachRemaining(fields::add);
        assertEquals(expectedFields, fields);
        assertEquals(mapper.writeValueAsString(annotation), mapper.writeValueAsString(mapper.treeToValue(actual, TranscriptConsequence.class)));
    }
}
