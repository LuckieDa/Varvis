package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp1TestMaterials.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp1SegregationCriterionEvaluatorTest.minimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.charset.StandardCharsets;
import java.util.*;

class Pp1SegregationIdentityTest {
    @ParameterizedTest @ValueSource(strings={"missing","blank"})
    void familyContextRequired(String variant)throws Exception{var r=base("alpha-1");var p=obj(r,"/context/patientContext");if(variant.equals("missing"))p.remove("familyContextId");else p.put("familyContextId"," ");minimal(evaluate(r),"PP1_FAMILY_CONTEXT_REQUIRED");}
    @ParameterizedTest @ValueSource(strings={"facts","segregationReview","diseaseApplicability","locusAttribution"})
    void sourceRawAndCanonicalAndProjectionHaveSeparateReasons(String part)throws Exception{
        for(String kind:List.of("raw","canonical","projection","parse","float","nfc-collision")){
            var r=base("alpha-1");var source=obj(observation(r),"/"+part+"/sources/0");
            if(kind.equals("raw"))source.put("rawSha256","0".repeat(64));else if(kind.equals("canonical"))source.put("canonicalSha256","0".repeat(64));else {
                String text=switch(kind){case "projection"->"{\"unrelated\":\"valid but different\"}";case "float"->"{\"value\":0.25}";case "nfc-collision"->"{\"é\":1,\"e\u0301\":2}";default->"{broken";};source.put("rawUtf8",text).put("rawSha256",raw(text.getBytes(StandardCharsets.UTF_8)));if(kind.equals("projection"))source.put("canonicalSha256",hash(M.readTree(text)));
            }
            pinsOnly(r);var result=evaluate(r);artifact("source-"+part+"-"+kind,r,result);
            minimal(result,"PP1_"+switch(kind){case "raw"->"SOURCE_RAW_PIN_MISMATCH";case "canonical"->"SOURCE_CANONICAL_PIN_MISMATCH";case "projection"->"SOURCE_FACTS_MISMATCH";default->"SOURCE_DOCUMENT_INVALID";});
        }
    }
    @Test void staleReviewPolicyAndRegistryReject()throws Exception{
        var r=base("alpha-1");obj(observation(r),"/assessment/partPins").put("facts","0".repeat(64));minimal(evaluate(r),"PP1_PART_PIN_MISMATCH");
        r=base("alpha-1");obj(r,"/policy/partPins").put("facts","0".repeat(64));minimal(evaluate(r),"PP1_PART_PIN_MISMATCH");
        r=base("alpha-1");obj(observation(r),"/assessment").put("assessmentVersion","OTHER");minimal(evaluate(r),"PP1_ASSESSMENT_PIN_MISMATCH");
        r=base("alpha-1");obj(r,"/registryBinding").put("registrySha256","0".repeat(64));minimal(evaluate(r),"PP1_REGISTRY_OR_AUTHORITY_INVALID");
    }
    @ParameterizedTest @ValueSource(strings={"sourceId","version","url","sha256","publicationDate","publicationStatus","normativeEligibility","authorityCutoff"})
    void fullAuthorityIdentityBound(String field)throws Exception{var r=base("alpha-1");obj(r,"/policy/scientificSources/0").put(field,"WRONG");catalog(r);minimal(evaluate(r),"PP1_REGISTRY_OR_AUTHORITY_INVALID");}
    @Test void nfcEquivalentSourceIdentityPassesWithoutMutation()throws Exception{
        var r=base("alpha-1");var f=obj(observation(r),"/facts");String old=f.at("/sources/0/sourceId").asText(),nfc="TEST_é",nfd="TEST_e\u0301";
        String text=M.writeValueAsString(f).replace(old,nfd);observation(r).set("facts",M.readTree(text));obj(observation(r),"/facts/sources/0").put("sourceId",nfc);reissue(r,"facts");var before=r.deepCopy();assertEquals("MET",evaluate(r).at("/evaluation/evaluationResult/status").asText());assertEquals(before,r);
    }
    @Test void strictIndependentLoaderRejectsBrokenTransport()throws Exception{
        for(String text:List.of("{} {}","{\"x\":1,\"x\":2}","{\"é\":1,\"e\u0301\":2}","{\"x\":NaN}","/*comment*/{}","{\"x\":1,}","[]")){
            byte[] b=text.getBytes(StandardCharsets.UTF_8);var permissive=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS);
            assertThrows(IllegalArgumentException.class,()->Pp1SegregationBundle.load(permissive,b,raw(b),"0".repeat(64)));
        }
        for(String field:List.of("unknown","ageYears","targetAltIndex")){
            var r=base("alpha-1");if(field.equals("unknown"))r.put("unknown",true);else if(field.equals("ageYears"))obj(observation(r),"/facts/members/0/clinical").put("ageYears",new java.math.BigInteger("9223372036854775808"));else obj(observation(r),"/facts/records/0/observation").put("targetAltIndex","1");assertThrows(IllegalArgumentException.class,()->load(r));
        }
        var r=base("alpha-1");byte[] b=M.writeValueAsBytes(r);assertThrows(IllegalArgumentException.class,()->Pp1SegregationBundle.load(M,b,"0".repeat(64),hash(r)));assertThrows(IllegalArgumentException.class,()->Pp1SegregationBundle.load(M,b,raw(b),"0".repeat(64)));
        byte[] large=new byte[4*1024*1024+1];assertThrows(IllegalArgumentException.class,()->Pp1SegregationBundle.load(M,large,raw(large),"0".repeat(64)));
        String deep="{\"x\":".repeat(34)+"0"+"}".repeat(34);byte[] d=deep.getBytes(StandardCharsets.UTF_8);assertThrows(IllegalArgumentException.class,()->Pp1SegregationBundle.load(M,d,raw(d),"0".repeat(64)));
    }
    @ParameterizedTest @CsvSource({"SPECIALIZED_UNIMPLEMENTED,APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED","SPECIALIZED_VERSION_CONFLICT,APPLICABLE_SPECIALIZED_VERSION_CONFLICT","SPECIALIZED_UNRESOLVED,APPLICABLE_SPECIALIZED_CLAUSE_UNRESOLVED","SPECIALIZED_IMPLEMENTED,PP1_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED"})
    void specializedNeverFallsBack(String disposition,String reason)throws Exception{var r=base("alpha-1");obj(r,"/catalog/entries/0").put("disposition",disposition);catalog(r);minimal(evaluate(r),reason.startsWith("PP1_")?reason:"PP1_"+reason);}
    @Test void catalogIntegrityAndReviewAndCompleteness()throws Exception{
        var r=base("alpha-1");r.put("catalogSha256","0".repeat(64));minimal(evaluate(r),"PP1_CATALOG_INTEGRITY_MISMATCH");
        r=base("alpha-1");obj(r,"/catalog/review").put("status","UNREVIEWED");catalog(r);minimal(evaluate(r),"PP1_CATALOG_IDENTITY_OR_REVIEW_INCOMPLETE");
        r=base("alpha-1");obj(r,"/catalog/entries/0").put("searchStatus","INCOMPLETE");catalog(r);minimal(evaluate(r),"PP1_CLAUSE_SEARCH_INCOMPLETE");
        r=base("alpha-1");var entry=obj(r,"/catalog/entries/0").deepCopy().put("entryId","OTHER_ENTRY");((ArrayNode)r.at("/catalog/entries")).add(entry);catalog(r);minimal(evaluate(r),"PP1_CLAUSE_SELECTION_CONFLICT");
        r=base("alpha-1");obj(r,"/catalog/entries/0/scope").put("transcriptMode","EXACT_ACCESSIONS").putArray("transcripts").addObject().put("accession","NM_000001").put("version",1);catalog(r);minimal(evaluate(r),"PP1_POSSIBLE_SPECIALIZED_TRANSCRIPT_SCOPE_UNRESOLVED");
    }
    @Test void mixedCriterionPolicyCannotEnterPp1()throws Exception{var r=base("alpha-1");((ArrayNode)r.at("/policy/criterionIds")).add("ACMG_AMP_2015:PS2");catalog(r);var result=evaluate(r);artifact("mixed-policy",r,result);minimal(result,"PP1_POLICY_CRITERION_SCOPE_MISMATCH");}
    @Test void allOldSelectorsStayIsolated()throws Exception{
        var r=base("alpha-1");var c=obj(r,"/catalog");var p=M.createArrayNode().add(r.path("policy"));String sha=r.path("catalogSha256").asText();
        for(var selector:List.of(new AcmgSpecificationSelector(M,c,sha,p),AcmgSpecificationSelector.forPs2Versioned(M,c,sha,p),AcmgSpecificationSelector.forPm6Versioned(M,c,sha,p),AcmgSpecificationSelector.forPm3Versioned(M,c,sha,p),AcmgSpecificationSelector.forBp2Versioned(M,c,sha,p)))assertNull(selector.select(r.path("context"),"ACMG_AMP_2015:PP1").selectedPolicy());
        for(String code:List.of("PS2","PM6","PM3","BP2","PP4"))assertNull(AcmgSpecificationSelector.forPp1Versioned(M,c,sha,p).select(r.path("context"),"ACMG_AMP_2015:"+code).selectedPolicy());
    }
    @Test void successorRegistryOnlyChangesAllowedFields()throws Exception{
        JsonNode old,current;try(var a=getClass().getResourceAsStream("/acmg/rule-registry-v2/acmg-rule-registry-bp2-phase-core-2026-09-30.json");var b=getClass().getResourceAsStream(Pp1SegregationCriterionEvaluator.REGISTRY)){old=M.readTree(a);current=M.readTree(b);}
        assertEquals(28,current.path("criteria").size());int unchanged=0;for(int i=0;i<28;i++){var before=(ObjectNode)old.path("criteria").get(i);var after=(ObjectNode)current.path("criteria").get(i);if(before.path("code").asText().equals("PP1")){var x=before.deepCopy();var y=after.deepCopy();for(String key:List.of("implementationStatus","automationDisposition","notes")){x.remove(key);y.remove(key);}assertEquals(x,y);}else{assertEquals(before,after);unchanged++;}}assertEquals(27,unchanged);
    }
}
