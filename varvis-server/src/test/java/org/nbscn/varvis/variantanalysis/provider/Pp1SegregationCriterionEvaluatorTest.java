package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp1TestMaterials.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;

class Pp1SegregationCriterionEvaluatorTest {
    @ParameterizedTest @ValueSource(strings={"alpha-1","alpha-2","alpha-3","alpha-4","alpha-5","alpha-2-healthy","beta-1","beta-2","beta-3","beta-4","beta-5","beta-2-healthy"})
    void frozenExpectedCountsAndEvents(String name)throws Exception{
        byte[] bytes=resource(name+"-bundle.json");JsonNode pins=M.readTree(resource(name+"-pins.json")),expected=M.readTree(resource(name+"-expected.json"));
        var bundle=Pp1SegregationBundle.load(M,bytes,pins.path("rawSha256").asText(),pins.path("canonicalSha256").asText());var before=bundle.snapshot();
        var engine=new Pp1SegregationCriterionEvaluator(M);ObjectNode result=engine.evaluate(bundle);artifact(name,before,result);
        assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals(expected.path("rawPoints"),result.path("rawPoints"));assertEquals(expected.path("appliedPoints"),result.path("appliedPoints"));assertEquals(expected.path("strength"),result.at("/evaluation/appliedStrength"));
        assertEquals(1,result.path("criterionEvaluations").size());assertEquals("PATHOGENIC",result.path("direction").asText());assertEquals("NOT_EVALUATED",result.at("/pp4/status").asText());
        Set<String> counted=new TreeSet<>(),uncounted=new TreeSet<>(),ids=new HashSet<>();
        for(var event:result.path("transmissionEvents")){assertEquals(expected.path("transmittingParent"),event.at("/identity/parentMemberId"));assertTrue(ids.add(event.path("eventId").asText()));
            assertEquals("pp1-transmission-sha256-"+hash(event.path("identity")),event.path("eventId").asText());assertEquals(3,event.path("records").size());assertEquals(2,event.path("parentage").size());
            (event.path("counted").asBoolean()?counted:uncounted).add(event.at("/identity/childMemberId").asText());}
        Set<String> ec=new TreeSet<>(),eu=new TreeSet<>();expected.path("countedChildren").forEach(n->ec.add(n.asText()));expected.path("uncountedChildren").forEach(n->eu.add(n.asText()));assertEquals(ec,counted);assertEquals(eu,uncounted);
        assertArrayEquals(M.writeValueAsBytes(result),M.writeValueAsBytes(engine.evaluate(bundle)));assertEquals(before,bundle.snapshot());
    }
    static void minimal(JsonNode result,String reason){assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());assertEquals("UNKNOWN",result.at("/criterionApplicability/status").asText());assertEquals(reason,result.at("/evaluationResult/reasonCode").asText());
        for(String field:List.of("rawPoints","appliedPoints","transmissionEvents","evidence","evaluation","appliedStrength","evaluationId","deterministicTrace"))assertFalse(result.has(field),field);
        assertEquals(3,result.at("/criterionEvaluations/0").size());}
}
