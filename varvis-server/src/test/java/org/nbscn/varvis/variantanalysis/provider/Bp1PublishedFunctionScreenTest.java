package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Bp1PublishedFunctionScreenTest {
    final ObjectMapper mapper=new ObjectMapper();
    static ObjectNode empty(ObjectMapper mapper) {
        var doc=mapper.createObjectNode().put("schemaVersion",Bp1PublishedFunctionScreen.SCHEMA)
                .put("purpose","RESEARCH_REVIEW_BLOCK_ONLY").put("coverage","LOADED_PUBLICATIONS_ONLY")
                .put("clinicalAdoption",false).put("absenceMeansNormal",false);
        doc.putArray("screens");return doc;
    }
    ObjectNode model() {return mapper.createObjectNode().put("hgncId","HGNC:1").put("transcript","NM_000001.1")
            .put("protein","NP_000001.1").put("cdsSequence","ATGGCTGCTGCTTAA");}
    ObjectNode association(int position) {return mapper.createObjectNode().put("proteinPosition",position)
            .put("refAminoAcid","A").put("altAminoAcid","V").put("proteinVariantKey","A"+position+"V");}
    ObjectNode doc() {
        var doc=empty(mapper);var s=doc.withArray("screens").addObject().put("screenId","SYNTHETIC_ONLY")
                .put("hgncId","HGNC:1").put("transcript","NM_000001.1").put("protein","NP_000001.1")
                .put("cdsSha256",CodingTranscriptMaterialSupply.sha(model().path("cdsSequence").asText()));
        s.putObject("source").put("sourceId","TEST_ONLY").put("url","https://example.org/synthetic")
                .put("rawSha256","a".repeat(64));
        s.putArray("ranges").addObject().put("start",2).put("end",3);
        var row=s.putArray("records").addObject().put("proteinVariantKey","A2V").put("reportedClass","FUNCTIONAL")
                .put("sourceRow",10).put("sourceSheet","Synthetic");row.putObject("originalRow").put("effect","FUNCTIONAL");
        return doc;
    }
    @ParameterizedTest @ValueSource(strings={"DAMAGING","INTERMEDIATE"})
    void adverseAndIntermediateAreReviewNotAutomaticPathogenicOrBenign(String value) {
        var d=doc();((ObjectNode)d.at("/screens/0/records/0")).put("reportedClass",value);
        var result=new Bp1PublishedFunctionScreen(mapper,d).assess(model(),association(2));
        assertEquals("REVIEW_REQUIRED",result.path("status").asText());
        assertEquals(value,result.at("/matches/0/record/reportedClass").asText());
        assertFalse(result.path("clinicalAdoption").asBoolean(true));assertFalse(result.has("evaluation"));
    }
    @Test void functionalIsOnlyNoAdverseSignalAndFullRowRetained() {
        var d=doc();var result=new Bp1PublishedFunctionScreen(mapper,d).assess(model(),association(2));
        assertEquals("CHECKED",result.path("status").asText());assertFalse(result.path("absenceMeansNormal").asBoolean(true));
        assertEquals(d.at("/screens/0/records/0"),result.at("/matches/0/record"));
    }
    @Test void missingRowInsideCoveredRangeIsDifferentFromUnmeasuredOutside() {
        var screen=new Bp1PublishedFunctionScreen(mapper,doc());
        assertEquals("BP1_FUNCTION_SCREEN_RECORD_UNAVAILABLE",screen.assess(model(),association(3)).path("reasonCode").asText());
        var outside=screen.assess(model(),association(4));assertEquals("CHECKED",outside.path("status").asText());
        assertEquals("OUTSIDE_REPORTED_SCREEN_RANGES",outside.at("/matches/0/outcome").asText());
        assertFalse(outside.path("absenceMeansNormal").asBoolean(true));
    }
    @ParameterizedTest @ValueSource(strings={"PROTEIN","TRANSCRIPT","CDS"})
    void sameGeneDoesNotAllowUnverifiedProteinOrSequenceTransfer(String fault) {
        var m=model();m.put(fault.equals("PROTEIN")?"protein":fault.equals("TRANSCRIPT")?"transcript":"cdsSequence","OTHER");
        assertEquals("BP1_FUNCTION_SCREEN_SEQUENCE_UNRESOLVED",new Bp1PublishedFunctionScreen(mapper,doc()).assess(m,association(2)).path("reasonCode").asText());
    }
    @Test void verifiedBridgeAndExactCdsAllowTheSameProteinNumbering() {
        var m=model();m.put("transcript","ENST000001.1").put("protein","ENSP000001.1");
        m.putObject("transcriptBridge").put("status","FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE").put("refseqTranscript","NM_000001.1")
                .put("refseqProtein","NP_000001.1").put("ensemblTranscript","ENST000001.1").put("ensemblProtein","ENSP000001.1");
        assertEquals("CHECKED",new Bp1PublishedFunctionScreen(mapper,doc()).assess(m,association(2)).path("status").asText());
    }
    @Test void duplicatesUnknownClassesAndInvalidScopeNeverLoad() {
        for(String fault:new String[]{"DUPLICATE","CLASS","RANGE","NORMALITY","SOURCE"}) {
            var d=doc();switch(fault){
                case "DUPLICATE"->((ObjectNode)d.at("/screens/0")).withArray("records").add(d.at("/screens/0/records/0").deepCopy());
                case "CLASS"->((ObjectNode)d.at("/screens/0/records/0")).put("reportedClass","UNKNOWN");
                case "RANGE"->((ObjectNode)d.at("/screens/0/ranges/0")).put("start",4);
                case "NORMALITY"->d.put("absenceMeansNormal",true);
                default->((ObjectNode)d.at("/screens/0/source")).put("rawSha256","missing");
            }
            assertThrows(IllegalArgumentException.class,()->new Bp1PublishedFunctionScreen(mapper,d),fault);
        }
    }
}
