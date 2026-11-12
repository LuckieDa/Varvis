package org.nbscn.varvis.variantanalysis.provider;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bs4TestMaterials.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
class Bs4NonSegregationCriterionEvaluatorTest {
 @ParameterizedTest @ValueSource(strings={"alpha-1","alpha-2","alpha-3","alpha-5","beta-1","beta-2","beta-3","beta-5"})
 void frozenExpected(String name)throws Exception {
  byte[] bytes=resource(name+"-bundle.json");var pins=M.readTree(resource(name+"-pins.json"));var expected=M.readTree(resource(name+"-expected.json"));
  var b=loadBytes(M,bytes,pins.path("rawSha256").asText(),pins.path("canonicalSha256").asText());var before=b.snapshot();var engine=new Bs4NonSegregationCriterionEvaluator(M);var result=engine.evaluate(b);artifact(name,before,result);
  assertEquals(before,b.snapshot());assertArrayEquals(M.writeValueAsBytes(result),M.writeValueAsBytes(engine.evaluate(b)));
  if(expected.path("status").asText().equals("NOT_EVALUATED")){minimal(result,expected.path("reasonCode").asText());return;}
  assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText(),result.toPrettyString());assertEquals("BENIGN",result.path("direction").asText());
  for(String field:List.of("rawPoints","appliedPoints","eventCount"))assertEquals(expected.path(field),result.path(field));assertEquals(expected.path("strength"),result.at("/evaluation/appliedStrength"));assertEquals("STRONG",result.at("/evaluation/baseStrength").asText());
  Set<String> actual=new TreeSet<>(),wanted=new TreeSet<>();expected.path("countedChildren").forEach(x->wanted.add(x.asText()));
  for(var event:result.path("nonSegregationEvents")){assertEquals("bs4-nonsegregation-sha256-"+hash(event.path("identity")),event.path("eventId").asText());assertEquals(3,event.path("records").size());assertEquals(2,event.path("parentage").size());if(event.path("counted").asBoolean())actual.add(event.at("/identity/childMemberId").asText());}
  assertEquals(wanted,actual);assertEquals(1,result.path("criterionEvaluations").size());assertFalse(result.path("classificationEnabled").asBoolean());assertFalse(result.path("adoptionSupported").asBoolean());
 }
 static void minimal(JsonNode result,String reason){assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText(),result.toPrettyString());assertEquals("UNKNOWN",result.at("/criterionApplicability/status").asText());assertEquals(reason,result.at("/evaluationResult/reasonCode").asText());
  for(String field:List.of("rawPoints","appliedPoints","eventCount","nonSegregationEvents","evidence","evaluation","appliedStrength","evaluationId","deterministicTrace"))assertFalse(result.has(field),field);assertEquals(3,result.at("/criterionEvaluations/0").size());}
 @Test void sourceAndObservationImmutable()throws Exception{var root=base("alpha-2");var bundle=load(root);var before=bundle.snapshot();root.removeAll();assertEquals(before,bundle.snapshot());var facts=bundle.observations().get(0);var snapshot=facts.snapshot();snapshot.removeAll();assertFalse(facts.snapshot().isEmpty());}
 @Test void sharedGroupIsExactlyPp1Domain()throws Exception{var r=base("alpha-2");assertEquals(new Pp1SegregationPolicy().evidenceGroup(r.path("context"),observation(r).path("facts")),evaluate(r).path("evidenceGroupId").asText());}
}
