package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Independent test-only issuer. Fixed positive resources are never repaired at execution time. */
final class Pp1TestMaterials {
    static final ObjectMapper M = new ObjectMapper();
    static final List<String> PARTS = List.of("facts", "segregationReview", "diseaseApplicability", "locusAttribution");
    static byte[] resource(String name) throws Exception {
        try (var s=Pp1TestMaterials.class.getResourceAsStream("/pp1-segregation-core/"+name)) {
            if(s==null)throw new IllegalArgumentException(name);return s.readAllBytes();
        }
    }
    static ObjectNode base(String name)throws Exception{return (ObjectNode)M.readTree(resource(name+"-bundle.json"));}
    static ObjectNode obj(JsonNode n,String pointer){return (ObjectNode)n.at(pointer);}
    static ObjectNode observation(JsonNode n){return obj(n,"/observations/0");}
    static String raw(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static JsonNode canonical(JsonNode n){
        if(n.isTextual())return M.getNodeFactory().textNode(Normalizer.normalize(n.asText(),Normalizer.Form.NFC));
        if(n.isFloatingPointNumber())throw new IllegalArgumentException("integer-only independent mirror");
        if(n.isObject()){TreeMap<String,JsonNode> sorted=new TreeMap<>();n.fieldNames().forEachRemaining(k->sorted.put(k,n.path(k)));ObjectNode r=M.createObjectNode();sorted.forEach((k,v)->r.set(Normalizer.normalize(k,Normalizer.Form.NFC),canonical(v)));return r;}
        if(n.isArray()){var r=M.createArrayNode();n.forEach(v->r.add(canonical(v)));return r;}return n.deepCopy();
    }
    static String hash(JsonNode n)throws Exception{return raw(M.writeValueAsBytes(canonical(n)));}
    static Pp1SegregationBundle load(ObjectNode n)throws Exception{byte[] b=M.writeValueAsBytes(n);return Pp1SegregationBundle.load(M,b,raw(b),hash(n));}
    private static int artifactSequence;
    static ObjectNode evaluate(ObjectNode n)throws Exception{var before=n.deepCopy();var result=new Pp1SegregationCriterionEvaluator(M).evaluate(load(n));org.junit.jupiter.api.Assertions.assertEquals(before,n);artifact(String.format("auto-%04d",++artifactSequence),n,result);return result;}
    static void reissue(ObjectNode root,String... parts)throws Exception{
        for(String part:parts){ObjectNode d=obj(observation(root),"/"+part),projection=d.deepCopy();projection.remove("sources");String text=M.writeValueAsString(projection);
            for(var source:d.path("sources"))((ObjectNode)source).put("rawUtf8",text).put("rawSha256",raw(text.getBytes(StandardCharsets.UTF_8))).put("canonicalSha256",hash(projection));}
        pinsOnly(root);
    }
    static void pinsOnly(ObjectNode root)throws Exception{
        ObjectNode o=observation(root),a=obj(o,"/assessment"),p=obj(root,"/policy");
        for(String part:PARTS){obj(a,"/partPins").put(part,hash(o.path(part)));obj(p,"/partPins").put(part,hash(o.path(part)));}
        a.remove("contentSha256");a.put("contentSha256",hash(a));p.put("assessmentSha256",hash(a));catalog(root);
    }
    static void catalog(ObjectNode root)throws Exception{obj(root,"/catalog/entries/0/policyBinding").put("policySha256",hash(root.path("policy")));root.put("catalogSha256",hash(root.path("catalog")));}
    static void artifact(String name,ObjectNode input,ObjectNode output)throws Exception{
        String destination=System.getProperty("varvis.pp1.artifacts");if(destination==null)return;
        Path d=Path.of(destination,name);Files.createDirectories(d);byte[] b=M.writeValueAsBytes(input);
        Files.write(d.resolve("loader-bytes.json"),b);Files.write(d.resolve("output.json"),M.writeValueAsBytes(output));
        Files.write(d.resolve("pins.json"),M.writeValueAsBytes(M.createObjectNode().put("rawSha256",raw(b)).put("canonicalSha256",hash(input))));
    }
    static void call(ObjectNode record,String gt){var g=obj(record,"/observation");boolean het=gt.charAt(0)!=gt.charAt(2);String ad=het?"20,20":gt.charAt(0)=='0'?"40,0":"0,40";
        g.put("genotype",gt).put("phased",gt.contains("|")).put("call",het?"HET_ALT":gt.charAt(0)=='0'?"HOM_REF":"HOM_ALT").put("alleleDepth",ad);record.put("rawSample",gt+":40:60:"+ad+":PASS");}
}
