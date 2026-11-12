package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Test-only independent canonical mirror and explicit source reissuer; never repairs fixed fixtures. */
final class Bs2TestMaterials {
    static final ObjectMapper M = new ObjectMapper();
    static final List<String> PARTS = List.of("facts","healthyAdultReview","diseaseApplicability","evidenceUse");
    static byte[] resource(String name) throws Exception {
        try(var s=Bs2TestMaterials.class.getResourceAsStream("/bs2-healthy-adult-core/"+name)) {
            if(s==null)throw new IllegalArgumentException(name);return s.readAllBytes();
        }
    }
    static ObjectNode base(String name)throws Exception{return (ObjectNode)M.readTree(resource(name+"-bundle.json"));}
    static ObjectNode obj(JsonNode n,String pointer){return (ObjectNode)n.at(pointer);}
    static String raw(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static JsonNode canonical(JsonNode n){
        if(n.isTextual())return M.getNodeFactory().textNode(Normalizer.normalize(n.asText(),Normalizer.Form.NFC));
        if(n.isFloatingPointNumber())throw new IllegalArgumentException("integer-only independent mirror");
        if(n.isObject()){TreeMap<String,JsonNode> sorted=new TreeMap<>();n.fieldNames().forEachRemaining(k->sorted.put(k,n.path(k)));ObjectNode r=M.createObjectNode();sorted.forEach((k,v)->r.set(Normalizer.normalize(k,Normalizer.Form.NFC),canonical(v)));return r;}
        if(n.isArray()){var r=M.createArrayNode();n.forEach(v->r.add(canonical(v)));return r;}return n.deepCopy();
    }
    static String hash(JsonNode n)throws Exception{return raw(M.writeValueAsBytes(canonical(n)));}
    static void reissue(ObjectNode root,String... parts)throws Exception{
        for(String part:parts){ObjectNode d=obj(root,"/observations/0/"+part),projection=d.deepCopy();projection.remove("sources");String text=M.writeValueAsString(projection);
            for(var source:d.path("sources"))((ObjectNode)source).put("rawUtf8",text).put("rawSha256",raw(text.getBytes(StandardCharsets.UTF_8))).put("canonicalSha256",hash(projection));}
        pinsOnly(root);
    }
    static void pinsOnly(ObjectNode root)throws Exception{
        ObjectNode o=obj(root,"/observations/0"),a=obj(o,"/assessment"),p=obj(root,"/policy");
        for(String part:PARTS){obj(a,"/partPins").put(part,hash(o.path(part)));obj(p,"/partPins").put(part,hash(o.path(part)));}
        a.remove("contentSha256");a.put("contentSha256",hash(a));p.put("assessmentSha256",hash(a));catalog(root);
    }
    static void catalog(ObjectNode root)throws Exception{obj(root,"/catalog/entries/0/policyBinding").put("policySha256",hash(root.path("policy")));root.put("catalogSha256",hash(root.path("catalog")));}
    static Set<String> keys(JsonNode n){Set<String> s=new HashSet<>();n.fieldNames().forEachRemaining(s::add);return s;}
    static Path artifacts(String name)throws Exception{
        String destination=System.getProperty("varvis.bs2.artifacts");if(destination==null)throw new IllegalStateException("Explicit task artifact directory required");
        Path d=Path.of(destination,name);if(Files.exists(d))throw new IllegalStateException("Do not overwrite "+d);Files.createDirectories(d);return d;
    }
    static ObjectNode check(String name,ObjectNode root,String reason)throws Exception{return checkBytes(name,M.writeValueAsBytes(root),raw(M.writeValueAsBytes(root)),hash(root),reason);}
    static ObjectNode checkBytes(String name,byte[] bytes,String rawPin,String canonicalPin,String reason)throws Exception {
        Path d=artifacts(name);Files.write(d.resolve("loader-bytes.json"),bytes);
        Files.write(d.resolve("pins.json"),M.writeValueAsBytes(M.createObjectNode().put("rawSha256",rawPin).put("canonicalSha256",canonicalPin)));
        Files.write(d.resolve("expected.json"),M.writeValueAsBytes(M.createObjectNode().put("status",reason.isEmpty()?"MET":"NOT_EVALUATED").put("reasonCode",reason.isEmpty()?"BS2_REVIEWED_HEALTHY_ADULT_EARLY_FULL_PENETRANCE":"BS2_"+reason).put("expectationBeforeExecution",true)));
        try {
            var loaded=Bs2HealthyAdultBundle.load(M,bytes,rawPin,canonicalPin);ObjectNode before=loaded.snapshot();
            var out=new Bs2HealthyAdultCriterionEvaluator(M).evaluate(loaded);Files.write(d.resolve("output.json"),M.writeValueAsBytes(out));
            org.junit.jupiter.api.Assertions.assertEquals(before,loaded.snapshot());org.junit.jupiter.api.Assertions.assertEquals(before,out.path("sourceBundle"));
            if(reason.isEmpty()) {
                org.junit.jupiter.api.Assertions.assertEquals("MET",out.at("/evaluation/evaluationResult/status").asText(),out.toString());
                org.junit.jupiter.api.Assertions.assertEquals("STRONG",out.at("/evaluation/baseStrength").asText());org.junit.jupiter.api.Assertions.assertEquals("STRONG",out.at("/evaluation/appliedStrength").asText());
                org.junit.jupiter.api.Assertions.assertEquals("BENIGN",out.path("direction").asText());org.junit.jupiter.api.Assertions.assertEquals("UNDETERMINED",out.at("/evidence/direction").asText());
                org.junit.jupiter.api.Assertions.assertArrayEquals(M.writeValueAsBytes(out),M.writeValueAsBytes(new Bs2HealthyAdultCriterionEvaluator(M).evaluate(loaded)));
                var e=obj(out,"/evaluation").deepCopy();String id=e.remove("evaluationId").asText();e.remove(List.of("conflict","review"));org.junit.jupiter.api.Assertions.assertEquals("evaluation-sha256-"+hash(e),id);
                var trace=obj(e,"/deterministicTrace").deepCopy();String traceId=trace.remove("traceSha256").asText();org.junit.jupiter.api.Assertions.assertEquals(hash(trace),traceId);
            }else{
                org.junit.jupiter.api.Assertions.assertEquals("BS2_"+reason,out.at("/evaluationResult/reasonCode").asText(),out.toString());
                var expectedKeys=new HashSet<>(Set.of("criterionId","resultMode","sourceOfTruth","classificationEnabled","adoptionSupported","sourceBundle","evaluationResult","criterionApplicability","criterionEvaluations"));if(out.has("policySelection"))expectedKeys.add("policySelection");
                org.junit.jupiter.api.Assertions.assertEquals(expectedKeys,keys(out));org.junit.jupiter.api.Assertions.assertEquals(Set.of("status","reasonCode"),keys(out.path("evaluationResult")));org.junit.jupiter.api.Assertions.assertEquals(Set.of("status","reasonCode"),keys(out.path("criterionApplicability")));
                org.junit.jupiter.api.Assertions.assertEquals("NOT_EVALUATED",out.at("/evaluationResult/status").asText());org.junit.jupiter.api.Assertions.assertEquals("UNKNOWN",out.at("/criterionApplicability/status").asText());org.junit.jupiter.api.Assertions.assertEquals(out.at("/evaluationResult/reasonCode"),out.at("/criterionApplicability/reasonCode"));
                org.junit.jupiter.api.Assertions.assertEquals(1,out.path("criterionEvaluations").size());org.junit.jupiter.api.Assertions.assertEquals(Set.of("criterionId","evaluationResult","criterionApplicability"),keys(out.at("/criterionEvaluations/0")));
            }
            org.junit.jupiter.api.Assertions.assertFalse(out.path("classificationEnabled").asBoolean());org.junit.jupiter.api.Assertions.assertFalse(out.path("adoptionSupported").asBoolean());org.junit.jupiter.api.Assertions.assertFalse(out.has("points"));return out;
        }catch(Exception|AssertionError failure){Files.writeString(d.resolve("failure.txt"),failure.toString());throw failure;}
    }
    static void contextIdentity(ObjectNode root)throws Exception {
        var c=obj(root,"/context");var p=M.createObjectNode().put("domain","varvis-evidence-context-v3");
        for(String k:List.of("schemaVersion","dataClass","scope","moi","patientContext"))p.set(k,c.path(k));p.set("alleleId",c.at("/variant/alleleId"));p.set("hgncId",c.at("/gene/hgncId"));p.set("mondoId",c.at("/disease/mondoId"));
        c.put("contextId","context-sha256-"+hash(p));
        replaceBinding(root,c.path("contextId").asText());
    }
    static void alleleIdentity(ObjectNode root)throws Exception {
        var v=obj(root,"/context/variant");var normalizer=M.createObjectNode();
        for(String k:List.of("method","methodVersion","referenceSequenceSha256","refValidation"))normalizer.set(k,v.path("normalization").path(k));
        var projection=M.createObjectNode();projection.set("normalizedAllele",v.path("normalizedAllele"));projection.set("normalizer",normalizer);
        String id="allele-sha256-"+hash(projection);v.put("alleleId",id);
        for(String part:PARTS){var binding=obj(root,"/observations/0/"+part+"/binding");binding.put("alleleId",id);binding.set("allele",v.path("normalizedAllele").deepCopy());}
        obj(root,"/observations/0/facts/clinical").set("binding",root.at("/observations/0/facts/binding").deepCopy());
        obj(root,"/observations/0/facts/members/0/detection").set("binding",root.at("/observations/0/facts/binding").deepCopy());contextIdentity(root);
    }
    private static void replaceBinding(JsonNode n,String id){if(n.isObject()&&n.has("contextId"))((ObjectNode)n).put("contextId",id);for(var child:n)if(child.isContainerNode())replaceBinding(child,id);}
}
