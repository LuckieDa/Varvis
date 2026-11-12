package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pm6TestMaterials.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class Pm6DeNovoCoreTest {
    private Pm6DeNovoBundle load(ObjectNode b) throws Exception {
        byte[] bytes=M.writeValueAsBytes(b); return Pm6DeNovoBundle.load(M,bytes,raw(bytes),hash(b));
    }
    private ObjectNode evaluate(ObjectNode b) throws Exception { return new Pm6DeNovoCriterionEvaluator(M).evaluate(load(b)); }
    private void archive(String name,ObjectNode b,JsonNode result) throws Exception {
        Path dir=Path.of(System.getProperty("varvis.ps2.artifacts"),"pm6");Files.createDirectories(dir);
        Files.write(dir.resolve(name+"-input.json"),M.writerWithDefaultPrettyPrinter().writeValueAsBytes(b));
        Files.write(dir.resolve(name+"-result.json"),M.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        ObjectNode p=M.createObjectNode().put("rawInputSha256",raw(Files.readAllBytes(dir.resolve(name+"-input.json")))).put("canonicalInputSha256",hash(b)).put("rawResultSha256",raw(Files.readAllBytes(dir.resolve(name+"-result.json"))));
        Files.write(dir.resolve(name+"-pins.json"),M.writerWithDefaultPrettyPrinter().writeValueAsBytes(p));
    }
    private void minimal(ObjectNode result) {
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText(),result.toString());
        assertFalse(result.has("deNovoPoints"));assertFalse(result.has("evaluation"));
        for(String key:List.of("evaluationId","appliedStrength","deterministicTrace"))assertFalse(result.at("/criterionEvaluations/0").has(key));
    }
    @TestFactory Stream<DynamicTest> twoBackgroundsFourIndependentExpectations() throws Exception {
        List<DynamicTest> tests=new ArrayList<>();
        for(String bg:List.of("alpha","beta"))for(JsonNode v:resource("/pm6-denovo-core/expected.json").path("vectors"))tests.add(DynamicTest.dynamicTest(bg+v.path("points"),()->{
            ObjectNode b=base(bg);at(facts(b),"/clinical").put("phenotypeCategory",v.path("category").asText()).put("heterogeneity",v.path("heterogeneity").asText());seal(b);
            ObjectNode before=b.deepCopy(),r=evaluate(b);archive(bg+"-"+v.path("points"),b,r);
            assertEquals(0,v.path("points").decimalValue().compareTo(r.path("deNovoPoints").decimalValue()));
            assertEquals(v.path("status").asText(),r.at("/evaluation/evaluationResult/status").asText());assertEquals(v.path("strength").asText(),r.at("/evaluation/appliedStrength").asText());
            assertEquals(v.path("reason").asText(),r.at("/evaluation/evaluationResult/reasonCode").asText());assertEquals("MODERATE",r.at("/evaluation/baseStrength").asText());
            assertEquals("CASE_OBSERVATION",r.at("/evidence/evidenceType").asText());assertEquals("CORE_RESULT_ONLY",r.path("resultMode").asText());
            assertEquals(before,b);assertEquals(r,evaluate(b));assertEquals(r,M.readTree(M.writeValueAsBytes(r)));
            assertFalse(r.has("runId"));assertFalse(r.path("classificationEnabled").asBoolean());assertFalse(r.path("adoptionSupported").asBoolean());
            assertEquals("UNKNOWN",r.at("/sourceBundle/observations/0/facts/members/1/affectedStatus").asText());
        }));return tests.stream();
    }
    @Test void fixedBytesPinsAndAdapterBeforeIssuance() throws Exception {
        for(String bg:List.of("alpha","beta")){
            ObjectNode p=resource("/pm6-denovo-core/"+bg+"-pins.json");
            try(var in=getClass().getResourceAsStream("/pm6-denovo-core/"+bg+"-bundle.json")){
                assertNotNull(in);byte[] bytes=in.readAllBytes();Pm6DeNovoBundle b=Pm6DeNovoBundle.load(M,bytes,p.path("rawSha256").asText(),p.path("canonicalSha256").asText());
                assertEquals(M.readTree(M.writeValueAsBytes(create(bg))),b.snapshot()); // adapter uses Long nodes; compare the actual JSON boundary.
                ObjectNode copy=b.snapshot();copy.removeAll();assertFalse(b.snapshot().isEmpty());
                ObjectNode observation=b.observations().get(0).snapshot();observation.removeAll();assertFalse(b.observations().get(0).snapshot().isEmpty());
            }
        }
    }
    record Gate(String name,Consumer<ObjectNode> mutate,String reason) {}
    @TestFactory Stream<DynamicTest> validReissuedSemanticGateControls() {
        List<Gate> gates=new ArrayList<>();
        for(String value:List.of("NOT_PERFORMED","UNKNOWN","INVALID","CONFLICT"))gates.add(new Gate("test-"+value,b->at(basis(b),"/relations/0").put("testStatus",value),"PM6_PARENTAGE_TEST_NOT_COMPLETED"));
        for(String value:List.of("EXCLUDED","UNKNOWN","CONFLICT","UNRESOLVED"))gates.add(new Gate("conclusion-"+value,b->at(basis(b),"/relations/0").put("conclusion",value),"PM6_PARENTAGE_CONCLUSION_UNRESOLVED_OR_EXCLUDED"));
        for(String value:List.of("UNREVIEWED","UNKNOWN","CONFLICT"))gates.add(new Gate("review-"+value,b->at(basis(b),"/relations/0/review").put("status",value),"PM6_PARENTAGE_REVIEW_OR_SOURCE_INCOMPLETE"));
        gates.add(new Gate("date",b->at(basis(b),"/relations/0/review").put("date","invalid"),"PM6_PARENTAGE_REVIEW_OR_SOURCE_INCOMPLETE"));
        gates.add(new Gate("source-ref",b->at(basis(b),"/relations/0").putArray("sourceIds").add("wrong"),"PM6_PARENTAGE_REVIEW_OR_SOURCE_INCOMPLETE"));
        gates.add(new Gate("no-parentage-source",b->basis(b).putArray("sources"),"PM6_PARENTAGE_SOURCE_MISSING"));
        gates.add(new Gate("member",b->at(basis(b),"/relations/0").put("parentMemberId","wrong"),"PM6_PARENTAGE_MEMBER_MISMATCH"));
        gates.add(new Gate("sample",b->at(basis(b),"/relations/0").put("parentSampleId","wrong"),"PM6_PARENTAGE_MEMBER_MISMATCH"));
        gates.add(new Gate("duplicate-role",b->at(basis(b),"/relations/1").put("parentRole","FATHER"),"PM6_PARENTAGE_MEMBER_MISMATCH"));
        gates.add(new Gate("binding",b->at(basis(b),"/binding").put("familyContextId","wrong"),"PM6_PARENTAGE_BINDING_MISMATCH"));
        gates.add(new Gate("facts-sidecar",b->at(facts(b),"/parentage/0").put("status","CONFIRMED"),"PM6_PARENTAGE_FACTS_SIDECAR_CONFLICT"));
        for(String value:List.of("PRESENT","UNKNOWN","CONFLICT","UNRESOLVED"))gates.add(new Gate("family-"+value,b->at(facts(b),"/clinical/applicability").put("relatedFamilyHistory",value),"PM6_RELATED_FAMILY_HISTORY_NOT_ABSENT"));
        for(String value:List.of("OUT_OF_SCOPE","UNKNOWN","CONFLICT","UNRESOLVED"))gates.add(new Gate("scope-"+value,b->at(facts(b),"/clinical/applicability").put("singleTrioScope",value),"PM6_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE"));
        gates.add(new Gate("reviewed-only",b->at(facts(b),"/clinical").remove("applicability"),"PM6_CLINICAL_APPLICABILITY_MISSING"));
        gates.add(new Gate("mosaic",b->at(facts(b),"/clinical").put("mosaicism","KNOWN"),"PM6_MOSAICISM_SCOPE_UNSUPPORTED"));
        gates.add(new Gate("sibling",b->at(facts(b),"/clinical").put("siblingRecurrence","PRESENT"),"PM6_SIBLING_RECURRENCE_SCOPE_UNSUPPORTED"));
        gates.add(new Gate("phenotype-unknown",b->at(facts(b),"/clinical").put("phenotypeCategory","UNKNOWN"),"PM6_PHENOTYPE_ASSESSMENT_UNKNOWN"));
        gates.add(new Gate("heterogeneity-unknown",b->at(facts(b),"/clinical").put("heterogeneity","UNKNOWN"),"PM6_PHENOTYPE_ASSESSMENT_UNKNOWN"));
        for(int i=0;i<3;i++){final int n=i;gates.add(new Gate("coverage-"+i,b->at(facts(b),"/members/"+n+"/detection").put("coverage","UNKNOWN"),"PM6_DETECTION_QUALITY_INCOMPLETE"));gates.add(new Gate("quality-"+i,b->at(facts(b),"/members/"+n+"/detection").put("quality","LOW_QUALITY"),"PM6_DETECTION_QUALITY_INCOMPLETE"));}
        for(long value:new long[]{2,4294967297L,-4294967295L})gates.add(new Gate("alt-"+value,b->at(facts(b),"/members/0/detection").put("targetAltIndex",value),"PM6_ALLELIC_SCOPE_UNSUPPORTED"));
        for(long value:new long[]{3,4294967298L,-4294967294L})gates.add(new Gate("ploidy-"+value,b->at(facts(b),"/members/0/detection").put("ploidy",value),"PM6_ALLELIC_SCOPE_UNSUPPORTED"));
        for(String ft:List.of("LowQual",".",""))gates.add(new Gate("ft-"+(ft.isEmpty()?"empty":ft),b->{at(facts(b),"/members/1/detection").put("sampleFormat","GT:DP:GQ:AD:FT");at(facts(b),"/records/1").put("rawSampleValue","0/0:40:60:40,0:"+ft);},ft.equals("LowQual")?"PM6_RAW_FT_CONFLICT":"PM6_RAW_FT_MISSING"));
        gates.add(new Gate("ft-failed-consistent",b->{at(facts(b),"/members/1/detection").put("sampleFormat","GT:DP:GQ:AD:FT");at(facts(b),"/records/1").put("rawSampleValue","0/0:40:60:40,0:LowQual").put("sampleFilter","LowQual");},"PM6_DETECTION_QUALITY_INCOMPLETE"));
        for(String gt:List.of("0/.","0/2","0","0/1/1"))gates.add(new Gate("gt-"+gt.replace('/','_'),b->genotype(b,0,gt,"HET_ALT","21,19"),"PM6_GT_PARTIAL_OR_MULTIALLELIC_OR_PLOIDY_UNSUPPORTED"));
        gates.add(new Gate("hom-alt",b->genotype(b,0,"1/1","HOM_ALT","0,40"),"PM6_PROBAND_HOM_ALT_UNSUPPORTED"));
        gates.add(new Gate("gt-call",b->at(facts(b),"/records/0/observation").put("call","HOM_REF"),"PM6_GT_CALL_CONFLICT"));
        gates.add(new Gate("gt-raw",b->at(facts(b),"/records/0").put("rawSampleValue","0/0:40:60:21,19"),"PM6_RAW_GT_CONFLICT"));
        gates.add(new Gate("phased-conflict",b->at(facts(b),"/records/0/observation").put("phased",true),"PM6_RAW_GT_CONFLICT"));
        gates.add(new Gate("dp-conflict",b->at(facts(b),"/records/0/observation").put("depth",20),"PM6_RAW_SAMPLE_CONFLICT"));
        gates.add(new Gate("partial-before-negative",b->{genotype(b,1,"0/1","HET_ALT","20,20");at(facts(b),"/members/2/detection").put("quality","UNKNOWN");},"PM6_DETECTION_QUALITY_INCOMPLETE"));
        for(String key:List.of("sampleId","memberId"))gates.add(new Gate("nfc-"+key,b->{at(facts(b),"/members/1").put(key,"test-parent-é");at(facts(b),"/members/2").put(key,"test-parent-e\u0301");},"PM6_MEMBER_OR_SAMPLE_CONFLICT"));
        gates.add(new Gate("multi-observation",b->((com.fasterxml.jackson.databind.node.ArrayNode)b.path("observations")).add(b.path("observations").get(0).deepCopy()),"PM6_SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION"));
        return gates.stream().map(g->DynamicTest.dynamicTest(g.name,()->{ObjectNode b=base("alpha");g.mutate.accept(b);seal(b);ObjectNode r=evaluate(b);archive(g.name,b,r);minimal(r);assertEquals(g.reason,r.at("/evaluationResult/reasonCode").asText());}));
    }
    @Test void phasedAndFtPassAndCompleteNegativeControls() throws Exception {
        for(String gt:List.of("0/1","0|1","1|0")){
            ObjectNode b=base("alpha");genotype(b,0,gt,"HET_ALT","21,19");at(facts(b),"/members/1/detection").put("sampleFormat","GT:DP:GQ:AD:FT");at(facts(b),"/records/1").put("rawSampleValue","0/0:40:60:40,0:PASS");seal(b);ObjectNode r=evaluate(b);archive("valid-"+gt.replace('/','_').replace('|','p'),b,r);assertEquals("MET",r.at("/evaluation/evaluationResult/status").asText());
        }
        for(int n:new int[]{0,1}){ObjectNode b=base("alpha");genotype(b,n,n==0?"0/0":"0/1",n==0?"HOM_REF":"HET_ALT",n==0?"40,0":"20,20");seal(b);ObjectNode r=evaluate(b);archive("negative-"+n,b,r);assertEquals(0,r.path("deNovoPoints").decimalValue().signum());assertEquals(n==0?"PM6_PROBAND_TARGET_NOT_OBSERVED":"PM6_PARENTAL_TARGET_OBSERVED",r.at("/evaluation/evaluationResult/reasonCode").asText());}
    }
    @Test void sameSharedFactsMutualExclusionAndNoFallback() throws Exception {
        for(String pair:List.of("UU","UC","CU","CC","?U","!U")){
            ObjectNode pm=base("alpha");for(int i=0;i<2;i++){String c=switch(pair.charAt(i)){case 'U'->"ASSUMED";case 'C'->"CONFIRMED";case '?'->"UNKNOWN";default->"CONFLICT";};relation(pm,i,c);}seal(pm);
            ObjectNode ps=resource("/ps2-denovo-core/r1/alpha-bundle.json");at(ps,"/observations/0").set("facts",facts(pm).deepCopy());
            ObjectNode a=at(ps,"/observations/0/assessment");a.put("factsSha256",hash(facts(ps)));a.remove("contentSha256");a.put("contentSha256",hash(a));at(ps,"/policy").put("factsSha256",hash(facts(ps))).put("assessmentSha256",hash(a));pinCatalog(ps);
            assertEquals(facts(pm),facts(ps));byte[] bytes=M.writeValueAsBytes(ps);ObjectNode psResult=new Ps2DeNovoCriterionEvaluator(M).evaluate(Ps2DeNovoBundle.load(M,bytes,raw(bytes),hash(ps))),pmResult=evaluate(pm);
            archive("mutual-"+pair.replace('?','X').replace('!','Y'),pm,pmResult);archive("ps2-mutual-"+pair.replace('?','X').replace('!','Y'),ps,psResult);
            assertEquals(pair.equals("CC"),psResult.has("evaluation"));assertEquals(pair.matches("UU|UC|CU"),pmResult.has("evaluation"));
            assertFalse(psResult.has("pm6"));assertFalse(pmResult.has("ps2"));
        }
    }
    @TestFactory Stream<DynamicTest> strictShapesAndUntrustedInput() {
        List<Consumer<ObjectNode>> edits=List.of(b->b.put("allowPm6",true),b->b.put("points",1),b->at(basis(b),"/relations/0").put("method",""),b->at(basis(b),"/relations/0/review").remove("basis"),b->at(b,"/observations/0").remove("parentageBasis"),b->at(basis(b),"/relations/0").put("testStatus","TESTED"),b->at(facts(b),"/members/0/detection").put("ploidy",2.0),b->at(b,"/policy").put("strength","MODERATE"));
        List<DynamicTest> tests=new ArrayList<>();for(int i=0;i<edits.size();i++){int n=i;tests.add(DynamicTest.dynamicTest("shape-"+i,()->{ObjectNode b=base("alpha");edits.get(n).accept(b);assertThrows(IllegalArgumentException.class,()->load(b));}));}return tests.stream();
    }
    @Test void strictByteBoundaryAndPins() throws Exception {
        ObjectNode b=base("alpha");byte[] good=M.writeValueAsBytes(b);String json=new String(good,StandardCharsets.UTF_8);
        for(String bad:List.of(json+"{}",json.replaceFirst("\\{","{\"schemaVersion\":\"duplicate\","),"{\"é\":1,\"e\\u0301\":2}","{\"n\":NaN}","{\"x\":"+"[".repeat(35)+"0"+"]".repeat(35)+"}"," ".repeat(Ps2DeNovoBundle.MAX_BYTES+1))){byte[] bytes=bad.getBytes(StandardCharsets.UTF_8);assertThrows(IllegalArgumentException.class,()->Pm6DeNovoBundle.load(M,bytes,raw(bytes),hash(b)));}
        assertThrows(IllegalArgumentException.class,()->Pm6DeNovoBundle.load(M,good,"0".repeat(64),hash(b)));
        assertThrows(IllegalArgumentException.class,()->Pm6DeNovoBundle.load(M,good,raw(good),"0".repeat(64)));
    }
    @Test void changedContentInvalidatesOldReviewAndPolicyEvenWithSameIds() throws Exception {
        for(String field:List.of("testStatus","result","limitations","conclusion")){
            ObjectNode b=base("alpha");at(basis(b),"/relations/0").put(field,switch(field){case "testStatus"->"UNKNOWN";case "conclusion"->"UNKNOWN";default->"TEST_ONLY changed content";});source(basis(b));ObjectNode r=evaluate(b);archive("stale-"+field,b,r);minimal(r);assertEquals("PM6_PARENTAGE_BASIS_PIN_MISMATCH",r.at("/evaluationResult/reasonCode").asText());
        }
        ObjectNode b=base("alpha");at(basis(b),"/sources/0").put("rawSha256","0".repeat(64));pinReview(b);assertEquals("PM6_PARENTAGE_SOURCE_RAW_PIN_MISMATCH",evaluate(b).at("/evaluationResult/reasonCode").asText());
        b=base("alpha");at(basis(b),"/sources/0").put("canonicalSha256","0".repeat(64));pinReview(b);assertEquals("PM6_PARENTAGE_SOURCE_CANONICAL_PIN_MISMATCH",evaluate(b).at("/evaluationResult/reasonCode").asText());
        b=base("alpha");at(b,"/observations/0/assessment/parentage").put("basis","TEST_ONLY changed without new content identity");assertEquals("PM6_ASSESSMENT_PIN_MISMATCH",evaluate(b).at("/evaluationResult/reasonCode").asText());
    }
    @Test void selectorThreeRoutesAndSpecializationStayStrict() throws Exception {
        ObjectNode b=base("alpha"),c=at(b,"/context"),catalog=at(b,"/catalog");var policies=M.createArrayNode().add(b.path("policy"));
        assertNull(AcmgSpecificationSelector.forPs2Versioned(M,catalog,hash(catalog),policies).select(c,Pm6DeNovoCriterionEvaluator.CRITERION).selectedPolicy());
        assertNull(new AcmgSpecificationSelector(M,catalog,hash(catalog),policies).select(c,Pm6DeNovoCriterionEvaluator.CRITERION).selectedPolicy());
        assertNull(AcmgSpecificationSelector.forPm6Versioned(M,catalog,hash(catalog),policies).select(c,Ps2DeNovoCriterionEvaluator.CRITERION).selectedPolicy());
        for(String disposition:List.of("SPECIALIZED_UNIMPLEMENTED","SPECIALIZED_IMPLEMENTED","SPECIALIZED_VERSION_CONFLICT","SPECIALIZED_UNRESOLVED")){b=base("alpha");at(b,"/catalog/entries/0").put("disposition",disposition);pinCatalog(b);ObjectNode r=evaluate(b);archive(disposition,b,r);minimal(r);}
        for(String search:List.of("INCOMPLETE","FAILED")){b=base("alpha");at(b,"/catalog/entries/0").put("searchStatus",search);pinCatalog(b);minimal(evaluate(b));}
        b=base("alpha");at(b,"/policy/review").put("status","DISABLED");pinCatalog(b);minimal(evaluate(b));
        b=base("alpha");at(b,"/catalog/entries/0/scope").put("transcriptMode","EXACT_ACCESSIONS").putArray("transcripts").addObject().put("accession","NM_000001").put("version",1);pinCatalog(b);ObjectNode r=evaluate(b);archive("possible-tx",b,r);minimal(r);assertEquals("POSSIBLE_SPECIALIZED_TRANSCRIPT_SCOPE_UNRESOLVED",r.at("/policySelection/reasonCode").asText());
    }
    private void rebind(ObjectNode b) throws Exception {
        ObjectNode c=at(b,"/context"),v=at(c,"/variant"),f=facts(b),binding=at(f,"/binding");
        v.put("alleleId",A.canonicalAlleleId(v.path("normalizedAllele"),v.path("normalization")));c.put("contextId",A.canonicalVersionedContextId(c));
        binding.put("contextId",c.path("contextId").asText()).put("alleleId",v.path("alleleId").asText()).put("moi",c.path("moi").asText()).set("allele",v.path("evidenceQueryAllele").deepCopy());
        for(JsonNode m:f.path("members"))at(m,"/detection").set("binding",binding.deepCopy());for(JsonNode p:f.path("parentage"))((ObjectNode)p).set("binding",binding.deepCopy());
        at(f,"/clinical").set("binding",binding.deepCopy());basis(b).set("binding",binding.deepCopy());for(JsonNode r:basis(b).path("relations"))((ObjectNode)r).set("binding",binding.deepCopy());seal(b);
    }
    @TestFactory Stream<DynamicTest> nativeSingleTrioScopeOnly() {
        List<Gate> gates=new ArrayList<>();
        for(String moi:List.of("AUTOSOMAL_RECESSIVE","X_LINKED_DOMINANT","X_LINKED_RECESSIVE"))gates.add(new Gate(moi,b->at(b,"/context").put("moi",moi),"PM6_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED"));
        gates.add(new Gate("GRCh37",b->{for(String k:List.of("inputAllele","normalizedAllele","evidenceQueryAllele"))at(b,"/context/variant/"+k).put("assembly","GRCh37");},"PM6_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED"));
        gates.add(new Gate("indel",b->{at(b,"/context/variant").put("variantType","SMALL_INSERTION");for(String k:List.of("inputAllele","normalizedAllele","evidenceQueryAllele"))at(b,"/context/variant/"+k).put("alternate","AG");},"PM6_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED"));
        gates.add(new Gate("projected",b->{ObjectNode v=at(b,"/context/variant");at(v,"/inputAllele").put("assembly","GRCh37");at(v,"/normalizedAllele").put("assembly","GRCh37");ObjectNode map=at(v,"/assemblyLineage").put("mappingStatus","MAPPED").putObject("coordinateMapping");map.set("sourceAllele",v.path("normalizedAllele").deepCopy());map.set("targetAllele",v.path("evidenceQueryAllele").deepCopy());map.put("method","TEST_ONLY_projection").put("methodVersion","1").put("refAltValidation","MATCH");},"PM6_NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED"));
        return gates.stream().map(g->DynamicTest.dynamicTest(g.name,()->{ObjectNode b=base("alpha");g.mutate.accept(b);rebind(b);ObjectNode r=evaluate(b);archive(g.name,b,r);minimal(r);assertEquals(g.reason,r.at("/evaluationResult/reasonCode").asText());}));
    }
    @Test void sourceContentPinAndCatalogGuards() throws Exception {
        ObjectNode b=base("alpha");ObjectNode s=at(basis(b),"/sources/0"),report=(ObjectNode)M.readTree(s.path("rawUtf8").asText());at(report,"/relations/0").put("result","TEST_ONLY different report");String bytes=M.writeValueAsString(report);s.put("rawUtf8",bytes).put("rawSha256",raw(bytes.getBytes(StandardCharsets.UTF_8))).put("canonicalSha256",hash(report));pinReview(b);
        assertEquals("PM6_PARENTAGE_SOURCE_FACTS_MISMATCH",evaluate(b).at("/evaluationResult/reasonCode").asText());
        b=base("alpha");at(facts(b),"/clinical").put("age","TEST_ONLY changed age same observation ID");ObjectNode r=evaluate(b);archive("stale-shared-facts",b,r);assertEquals("PM6_PARENTAGE_FACTS_PIN_MISMATCH",r.at("/evaluationResult/reasonCode").asText());
        b=base("alpha");at(b,"/policy").put("policyVersion","same-id-new-version");minimal(evaluate(b));
        b=base("alpha");at(b,"/catalog").putArray("entries");pinCatalog(b);minimal(evaluate(b));
        b=base("alpha");at(b,"/catalog/entries/0/scope").put("hgncId","HGNC:999999");pinCatalog(b);minimal(evaluate(b));
        b=base("alpha");at(b,"/registryBinding").put("registrySha256","0".repeat(64));minimal(evaluate(b));
        // Two reports describing the same observation remain one contribution, never 2x points.
        b=base("alpha");ObjectNode copy=(ObjectNode)basis(b).path("sources").get(0).deepCopy();copy.put("sourceId","TEST_ONLY_SECOND_COPY").put("recordId","TEST_ONLY_SECOND_REPORT");((com.fasterxml.jackson.databind.node.ArrayNode)basis(b).path("sources")).add(copy);seal(b);r=evaluate(b);archive("same-observation-two-sources",b,r);assertEquals(1,r.path("deNovoPoints").asInt());assertEquals(1,r.path("observationCount").asInt());
    }
    @Test void successorPreservesAllOtherRulesAndScientificStrengths() throws Exception {
        ObjectNode old=resource(Ps2DeNovoCriterionEvaluator.REGISTRY),next=resource(Pm6DeNovoCriterionEvaluator.REGISTRY);
        try(var in=getClass().getResourceAsStream(Ps2DeNovoCriterionEvaluator.REGISTRY)){assertNotNull(in);assertEquals(raw(in.readAllBytes()),next.at("/lineage/baseRegistrySha256").asText());}
        assertEquals(old.path("authorityCatalogSha256"),next.path("authorityCatalogSha256"));assertEquals(28,next.path("criteria").size());
        for(int i=0;i<28;i++){JsonNode a=old.path("criteria").get(i),b=next.path("criteria").get(i);if("PM6".equals(a.path("code").asText())){assertEquals(a.path("currentGeneral"),b.path("currentGeneral"));assertTrue(b.at("/currentGeneral/allowedStrengths").toString().contains("VERY_STRONG"));}else assertEquals(a,b);}
    }
}
