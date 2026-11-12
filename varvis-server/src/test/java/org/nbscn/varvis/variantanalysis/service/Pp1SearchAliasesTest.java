package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pp1SearchAliasesTest {
    final ObjectMapper json = new ObjectMapper();
    ObjectNode candidate(int count) {
        var c = json.createObjectNode(); var rows = c.putArray("transcriptAnnotations");
        for (int i=1;i<=count;i++) rows.addObject().put("geneSymbol","GENE1")
                .put("transcriptAccession","ENST"+i).put("hgvsc","ENST"+i+".1:c."+i+"A>G");
        return c;
    }
    @Test void transcriptRichCandidateKeepsMainTranscriptAndEveryDeferredName() {
        var c=candidate(40);var before=c.deepCopy();
        ((ObjectNode)c.at("/transcriptAnnotations/39")).put("maneSelect",true);
        var value=Pp1SearchAliases.select(json,c,"GENE1");
        assertEquals("c.40A>G",value.target().at("/variantAliases/0").asText());
        assertEquals(20,value.target().path("variantAliases").size());
        assertEquals(40,value.coverage().path("availableAliases").asInt());
        assertEquals(20,value.coverage().path("deferredAliases").size());
        var all=new java.util.HashSet<String>();value.target().path("variantAliases").forEach(a->all.add(a.asText()));
        value.coverage().path("deferredAliases").forEach(a->all.add(a.asText()));assertEquals(40,all.size());
        assertEquals(before.at("/transcriptAnnotations/0"),c.at("/transcriptAnnotations/0"));
        var reversed=c.deepCopy();var rows=reversed.putArray("transcriptAnnotations");
        for(int i=39;i>=0;i--)rows.add(c.path("transcriptAnnotations").get(i));
        assertEquals(value,Pp1SearchAliases.select(json,reversed,"GENE1"));
    }
    @Test void deduplicatesEquivalentHintsButDoesNotBorrowOtherGenesOrInventNotation() {
        var c=candidate(1);var rows=(com.fasterxml.jackson.databind.node.ArrayNode)c.path("transcriptAnnotations");
        rows.add(rows.get(0).deepCopy());
        rows.addObject().put("geneSymbol","OTHER").put("maneSelect",true).put("hgvsc","c.999A>G");
        rows.addObject().put("geneSymbol","GENE1").put("hgvsc","untrusted text");
        var result=Pp1SearchAliases.select(json,c,"GENE1");
        assertEquals(1,result.target().path("variantAliases").size());
        assertEquals(0,result.coverage().path("omittedAliases").asInt());
        assertEquals("PP1_PUBLIC_SEARCH_ALIAS_MISSING",assertThrows(IllegalArgumentException.class,
                ()->Pp1SearchAliases.select(json,c,"MISSING")).getMessage());
    }
    @Test void priorityIsManeThenClinicalThenCanonicalAndBoundaryDoesNotReject() {
        var c=candidate(21);
        ((ObjectNode)c.at("/transcriptAnnotations/20")).put("canonical",true);
        ((ObjectNode)c.at("/transcriptAnnotations/19")).put("manePlusClinical",true);
        ((ObjectNode)c.at("/transcriptAnnotations/18")).put("maneSelect",true);
        var a=Pp1SearchAliases.select(json,c,"GENE1").target().path("variantAliases");
        assertEquals("c.19A>G",a.get(0).asText());assertEquals("c.20A>G",a.get(1).asText());assertEquals("c.21A>G",a.get(2).asText());
        assertEquals(0,Pp1SearchAliases.select(json,candidate(20),"GENE1").coverage().path("omittedAliases").asInt());
    }
}
