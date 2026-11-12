package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class Pm3RarityQualificationTest {
    final Pm2LocalExomesTest f=new Pm2LocalExomesTest();
    ObjectNode run(ObjectNode c,ObjectNode o) {
        return new Pm3RarityQualification(f.mapper).qualify(c.at("/variant/evidenceQueryAllele"),c,c,o,
                Pm2LocalExomesProfile.synthetic(f.mapper,List.of(f.policy(9))),Pm2LocalExomesTest.SOURCE,null);
    }
    @Test void actualPm2EvaluationSuppliesRarityAndPreservesScope() throws Exception {
        var c=f.context(9);
        for(String[] vector:List.of(new String[]{"1","2000","VERIFIED"},new String[]{"2","2000","FAILED"},new String[]{"1","1999","UNKNOWN"})) {
            var result=run(c,f.observation(c,f.info(vector[0],vector[1],"0.000001"),"PASS"));
            assertEquals(vector[2],result.path("qualification").asText(),result.toPrettyString());
            assertFalse(result.path("wholeGnomadConclusion").asBoolean());
            assertFalse(result.path("businessPublishable").asBoolean());
            assertTrue(result.has("evaluation"));
        }
    }
    @Test void forgedSavedResultDoesNotCreateQualification() throws Exception {
        var c=f.context(9);var o=f.observation(c,f.info("2","2000","0.000001"),"PASS");
        o.putObject("evaluationResult").put("status","MET");
        var result=run(c,o);
        assertNotEquals("VERIFIED",result.path("qualification").asText());
    }
    @Test void otherAlleleOrDiseaseCannotBeBorrowed() throws Exception {
        var c=f.context(9);var o=f.observation(c,f.info("1","2000","0.000001"),"PASS");
        var expected=c.deepCopy();expected.withObject("/disease").put("mondoId","MONDO:9999999");
        var service=new Pm3RarityQualification(f.mapper);
        var profile=Pm2LocalExomesProfile.synthetic(f.mapper,List.of(f.policy(9)));
        assertEquals("CONFLICT",service.qualify(c.at("/variant/evidenceQueryAllele"),expected,c,o,profile,Pm2LocalExomesTest.SOURCE,null).path("qualification").asText());
        var allele=((ObjectNode)c.at("/variant/evidenceQueryAllele")).deepCopy();allele.put("position",1);
        assertEquals("CONFLICT",service.qualify(allele,c,c,o,profile,Pm2LocalExomesTest.SOURCE,null).path("qualification").asText());
    }
}
