package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp4TestMaterials.*;

/** Hand-fixed scientific expectations; mutation issuer only prepares explicitly changed TEST_ONLY cases. */
class Pp4PhenotypeCoreTest {
    static final String P="/observations/0/";
    static void ne(ObjectNode root,String reason)throws Exception {
        var out=evaluate(root);
        expected(M.createObjectNode().put("status","NOT_EVALUATED").put("reasonCode","PP4_"+reason).put("minimal",true));
        assertEquals("PP4_"+reason,out.at("/evaluationResult/reasonCode").asText(),out.toString());
        assertEquals("NOT_EVALUATED",out.at("/evaluationResult/status").asText());
        assertEquals("UNKNOWN",out.at("/criterionApplicability/status").asText());
        for(String key:List.of("rawPoints","appliedPoints","evidence","evaluation","appliedStrength","evaluationId","deterministicTrace"))assertFalse(out.has(key),key);
        assertEquals(Set.of("criterionId","evaluationResult","criterionApplicability"),fieldSet(out.at("/criterionEvaluations/0")));
        assertArrayEquals(M.writeValueAsBytes(root),M.writeValueAsBytes(out.path("sourceBundle")));
    }
    static Set<String> fieldSet(JsonNode n){Set<String> s=new HashSet<>();n.fieldNames().forEachRemaining(s::add);return s;}
    static void counts(ObjectNode root,int n,int den)throws Exception {
        var y=obj(root,P+"diagnosticYield"); y.put("geneSpecificDiagnosedCount",n).put("eligibleTestedCount",den);
        ObjectNode template=((ObjectNode)y.path("cases").get(0)).deepCopy(); var cases=y.putArray("cases");
        for(int i=0;i<den;i++) {var c=template.deepCopy().put("caseId","TEST_ONLY_rate_case_"+i).put("personId","TEST_ONLY_rate_person_"+i).put("familyId","TEST_ONLY_rate_family_"+i).put("diagnosedInGene",i<n);var ids=c.putArray("diagnosticAlleleIds");if(i<n)ids.add("TEST_ONLY_diagnostic_allele_"+i);cases.add(c);}
        reissue(root,"diagnosticYield");
    }
    @TestFactory Stream<DynamicTest> fixedBackgrounds() {
        return Stream.of("alpha","beta").map(name->DynamicTest.dynamicTest(name,()->{
            byte[] bytes=resource(name+"-bundle.json");var pins=M.readTree(resource(name+"-pins.json"));
            assertEquals(raw(bytes),pins.path("rawSha256").asText());var input=(ObjectNode)M.readTree(bytes);
            var loaded=Pp4PhenotypeBundle.load(M,bytes,pins.path("rawSha256").asText(),pins.path("canonicalSha256").asText());
            var result=new Pp4PhenotypeCriterionEvaluator(M).evaluate(loaded);artifact("fixed-"+name,input,result);
            var expected=M.readTree(resource(name+"-expected.json")); assertEquals(expected.path("status"),result.at("/evaluation/evaluationResult/status"),result.toString());
            for(String k:List.of("rawPoints","appliedPoints","evidenceGroupId"))assertEquals(expected.path(k),result.path(k));
            assertEquals(expected.path("strength"),result.at("/evaluation/appliedStrength"));
            assertEquals(expected.path("thresholdPerThousand"),result.at("/diagnosticYield/thresholdPerThousand"));
            assertArrayEquals(M.writeValueAsBytes(result),M.writeValueAsBytes(new Pp4PhenotypeCriterionEvaluator(M).evaluate(loaded)));
            assertEquals(input,loaded.snapshot());assertEquals("UNKNOWN",result.at("/sourceBundle/observations/0/facts/clinical/familyHistory").asText());
            assertTrue(result.at("/sourceBundle/observations/0/facts/parentage").isEmpty()); assertFalse(result.has("pp1"));
            assertEquals("UNDETERMINED",result.at("/evidence/direction").asText());
        }));
    }
    @TestFactory Stream<DynamicTest> rationalTable() {
        int[] thresholds={191,254,330,415,506,596,680,754,816,864,902,930,950,965,975,983,988,992,994,996,997,998,999};
        List<DynamicTest> tests=new ArrayList<>();
        for(int i=0;i<thresholds.length;i++){ final int row=i; for(int delta:new int[]{-1,0,1}){
            final int shift=delta;tests.add(DynamicTest.dynamicTest("table-"+thresholds[i]+"-"+delta,()->{
                long n=thresholds[row]*1000L+shift;
                if(row==0){assertThrows(IllegalArgumentException.class,()->Pp4DiagnosticYield.calculate(n,1_000_000));receipt("table-"+row+"-"+shift,M.createObjectNode().put("numerator",n).put("denominator",1_000_000).put("expected","NE_OUTSIDE_PROJECT_DOMAIN").put("actual","NE_OUTSIDE_PROJECT_DOMAIN"));return;}
                var result=Pp4DiagnosticYield.calculate(n,1_000_000);assertEquals(row+2+(shift<0?-1:0),result.rawHalfPoints());assertEquals(Math.min(result.rawHalfPoints(),10),result.appliedHalfPoints());
                receipt("table-"+row+"-"+shift,M.createObjectNode().put("numerator",n).put("denominator",1_000_000).put("expectedRawHalfPoints",row+2+(shift<0?-1:0)).put("actualRawHalfPoints",result.rawHalfPoints()).put("actualAppliedHalfPoints",result.appliedHalfPoints()));
            }));}}
        tests.add(DynamicTest.dynamicTest("first-row-inside-project-domain",()->assertEquals(2,Pp4DiagnosticYield.calculate(201,1000).rawHalfPoints())));
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> endToEndRates() {
        // Numerator / 1000, raw half points, applied half points, strength. Independently fixed table expectations.
        String[] vectors={"199,NE","200,NE","201,2,2,SUPPORTING","254,3,3,SUPPORTING","330,4,4,MODERATE","415,5,5,MODERATE","500,5,5,MODERATE","506,6,6,MODERATE","596,7,7,MODERATE","680,8,8,STRONG","754,9,9,STRONG","816,10,10,STRONG","958,14,10,STRONG","999,24,10,STRONG","1000,NE"};
        return Arrays.stream(vectors).map(v->DynamicTest.dynamicTest("rate-"+v,()->{
            String[] x=v.split(",");var root=base("alpha");counts(root,Integer.parseInt(x[0]),1000);
            if(x[1].equals("NE")){ne(root,"YIELD_OUTSIDE_BOUNDED_INTERVAL");return;}
            var out=evaluate(root);expected(M.createObjectNode().put("status","MET").put("rawHalfPoints",Integer.parseInt(x[1])).put("appliedHalfPoints",Integer.parseInt(x[2])).put("strength",x[3]));assertEquals(Integer.parseInt(x[1]),out.path("rawHalfPoints").asInt(),out.toString());assertEquals(Integer.parseInt(x[2]),out.path("appliedHalfPoints").asInt());assertEquals(x[3],out.at("/evaluation/appliedStrength").asText());assertEquals(1,out.path("criterionEvaluations").size());
            assertEquals(Integer.parseInt(x[1])>10?"SINGLE_PP4_CAP_5":"NOT_CAPPED",out.path("capReason").asText());assertFalse(out.has("pp1"));
        }));
    }
    record Mutation(String name,String reason,Consumer<ObjectNode> edit,String[] parts) { }
    static Mutation mut(String name,String reason,Consumer<ObjectNode> edit,String...parts){return new Mutation(name,reason,edit,parts);}
    @TestFactory Stream<DynamicTest> semanticGates() {
        List<Mutation> list=new ArrayList<>();
        list.add(mut("age-unknown","AGE_APPLICABILITY_UNESTABLISHED",r->obj(r,P+"phenotypeReview").putNull("ageYears"),"phenotypeReview"));
        list.add(mut("age-outside","AGE_APPLICABILITY_UNESTABLISHED",r->{obj(r,P+"phenotypeReview").put("ageYears",2);obj(r,P+"facts/clinical").put("age","2");},"facts","phenotypeReview"));
        list.add(mut("missing-finding","CLINICAL_ROSTER_MISMATCH",r->((ArrayNode)r.at(P+"phenotypeReview/findings")).remove(0),"phenotypeReview"));
        list.add(mut("unknown-finding","CLINICAL_FINDING_UNESTABLISHED",r->obj(r,P+"phenotypeReview/findings/0").put("presence","UNKNOWN"),"phenotypeReview"));
        list.add(mut("absent-not-examined","CLINICAL_FINDING_UNESTABLISHED",r->obj(r,P+"phenotypeReview/findings/1").put("examination","NOT_PERFORMED"),"phenotypeReview"));
        list.add(mut("opposite-required","PHENOTYPE_REQUIREMENT_NOT_MET",r->obj(r,P+"phenotypeReview/definition/requirements/0").put("presence","ABSENT"),"phenotypeReview"));
        list.add(mut("only-label","PHENOTYPE_DEFINITION_UNESTABLISHED",r->obj(r,P+"phenotypeReview/definition").putArray("requirements"),"phenotypeReview"));
        list.add(mut("clinical-conflicting-summary","CLINICAL_DEFINITION_CONFLICT",r->obj(r,P+"facts/clinical").put("phenotypeCategory","NOT_CONSISTENT"),"facts"));
        list.add(mut("unknown-exam-method","CLINICAL_FINDING_UNESTABLISHED",r->obj(r,P+"phenotypeReview/findings/0").put("method","UNKNOWN"),"phenotypeReview"));
        list.add(mut("delete-exclusion-with-original-fact","CLINICAL_ROSTER_MISMATCH",r->((ArrayNode)r.at(P+"phenotypeReview/findings")).remove(1),"phenotypeReview"));
        list.add(mut("alternative-detected","ALTERNATIVE_EXPLANATION_UNRESOLVED",r->obj(r,P+"phenotypeReview/alternatives/0").put("detected",true),"phenotypeReview"));
        list.add(mut("formal-linkage","ALTERNATIVE_OR_FORMAL_ANALYSIS_REQUIRED",r->obj(r,P+"phenotypeReview").put("linkage","REQUIRED"),"phenotypeReview"));
        list.add(mut("circular-validity","GENE_DISEASE_BASIS_UNESTABLISHED",r->obj(r,P+"phenotypeReview/geneDiseaseBasis").put("independence","CURRENT_VARIANT_PP4"),"phenotypeReview"));
        list.add(mut("cohort-current-family","CURRENT_CASE_OR_FAMILY_IN_COHORT",r->obj(r,P+"diagnosticYield/cases/0").set("familyId",r.at(P+"facts/familyId")),"diagnosticYield"));
        list.add(mut("cohort-current-case","CURRENT_CASE_OR_FAMILY_IN_COHORT",r->obj(r,P+"diagnosticYield/cases/0").set("caseId",r.at(P+"facts/caseId")),"diagnosticYield"));
        list.add(mut("circular-diagnosis","COHORT_CIRCULAR_CLASSIFICATION",r->obj(r,P+"diagnosticYield/cases/0").putArray("diagnosticAlleleIds").add(r.at(P+"facts/binding/alleleId")),"diagnosticYield"));
        list.add(mut("ineligible-denominator","COHORT_DENOMINATOR_INELIGIBLE",r->obj(r,P+"diagnosticYield/cases/0").put("phenotypeEligible",false),"diagnosticYield"));
        list.add(mut("count-source-semantic","COHORT_COUNTS_MISMATCH",r->obj(r,P+"diagnosticYield").put("geneSpecificDiagnosedCount",34),"diagnosticYield"));
        list.add(mut("declared-rate","DECLARED_YIELD_MISMATCH",r->obj(r,P+"diagnosticYield").put("declaredYield","0.99"),"diagnosticYield"));
        list.add(mut("percent-declaration","DECLARED_YIELD_INVALID",r->obj(r,P+"diagnosticYield").put("declaredYield","33"),"diagnosticYield"));
        list.add(mut("declared-points","DECLARED_POINTS_MISMATCH",r->obj(r,P+"diagnosticYield").put("declaredRawHalfPoints",20),"diagnosticYield"));
        for(String key:List.of("method","testVersion","populationId"))list.add(mut("cohort-"+key,"YIELD_COMPARABILITY_OR_INDEPENDENCE_UNESTABLISHED",r->obj(r,P+"diagnosticYield").put(key,"UNKNOWN"),"diagnosticYield"));
        for(String key:List.of("unit","statistic","denominatorDefinition"))list.add(mut("wrong-"+key,"YIELD_DEFINITION_INVALID",r->obj(r,P+"diagnosticYield").put(key,"POSITIVE_ALL_GENES_PERCENT"),"diagnosticYield"));
        for(long count:new long[]{0,-1,1_000_000_001L,4294967297L})list.add(mut("invalid-den-"+count,"YIELD_COUNTS_INVALID",r->obj(r,P+"diagnosticYield").put("eligibleTestedCount",count),"diagnosticYield"));
        for(long count:new long[]{-1,101})list.add(mut("invalid-num-"+count,"YIELD_COUNTS_INVALID",r->obj(r,P+"diagnosticYield").put("geneSpecificDiagnosedCount",count),"diagnosticYield"));
        list.add(mut("nfc-person-duplicate","COHORT_IDENTITY_DUPLICATE",r->{obj(r,P+"diagnosticYield/cases/0").put("personId","TEST_ONLY_é");obj(r,P+"diagnosticYield/cases/1").put("personId","TEST_ONLY_e\u0301");},"diagnosticYield"));
        list.add(mut("candidate-unresolved","CANDIDATE_UNRESOLVED_NO_REALLOCATION",r->obj(r,P+"locusAttribution/candidates/1").put("disposition","UNRESOLVED"),"locusAttribution"));
        list.add(mut("allele-duplicate","CANDIDATE_IDENTITY_DUPLICATE",r->obj(r,P+"locusAttribution/candidates/1").set("alleleId",r.at(P+"locusAttribution/candidates/0/alleleId")),"locusAttribution"));
        list.add(mut("bs4-rescaling","LOCUS_COVERAGE_OR_ALLOCATION_UNESTABLISHED",r->obj(r,P+"locusAttribution").put("allocation","BS4_RENORMALIZED"),"locusAttribution"));
        list.add(mut("no-target","SOLE_TARGET_REQUIRED",r->obj(r,P+"locusAttribution").putArray("candidates"),"locusAttribution"));
        list.add(mut("unknown-roster","LOCUS_COVERAGE_OR_ALLOCATION_UNESTABLISHED",r->obj(r,P+"locusAttribution").put("rosterStatus","UNKNOWN"),"locusAttribution"));
        list.add(mut("missing-use","SHARED_EVIDENCE_ROSTER_INCOMPLETE",r->((ArrayNode)r.at(P+"locusAttribution/usedEvidence")).remove(0),"locusAttribution"));
        for(String state:List.of("APPLIED","UNKNOWN"))list.add(mut("occupied-"+state,"SHARED_EVIDENCE_OCCUPIED_OR_CONFLICT",r->obj(r,P+"locusAttribution/usedEvidence/0").put("status",state),"locusAttribution"));
        for(int i=0;i<4;i++){final int index=i;list.add(mut("clinical-used-"+i,"CLINICAL_EVIDENCE_OCCUPIED_OR_CONFLICT",r->obj(r,P+"locusAttribution/clinicalUse/"+index).put("status","APPLIED"),"locusAttribution"));}
        list.add(mut("duplicate-observation","SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION",r->((ArrayNode)r.path("observations")).add(r.at(P.substring(0,P.length()-1)).deepCopy())));
        list.add(mut("wrong-case","CLINICAL_CASE_MISMATCH",r->obj(r,P+"phenotypeReview").put("caseId","TEST_ONLY_OTHER_CASE"),"phenotypeReview"));
        list.add(mut("wrong-sample","RECORD_BINDING_MISMATCH",r->obj(r,P+"facts/records/0").put("sampleId","TEST_ONLY_OTHER_SAMPLE"),"facts"));
        list.add(mut("wrong-gene","RECORD_BINDING_MISMATCH",r->obj(r,P+"facts/records/0").put("geneSymbol","TEST_ONLY_OTHER_GENE"),"facts"));
        list.add(mut("wrong-build","RECORD_BINDING_MISMATCH",r->obj(r,P+"facts/records/0/variantKey").put("assembly","GRCh37"),"facts"));
        list.add(mut("wrong-allele","RECORD_BINDING_MISMATCH",r->obj(r,P+"facts/records/0/variantKey").put("alternate","T"),"facts"));
        list.add(mut("raw-ft-lowqual","SAMPLE_FT_NOT_PASS",r->obj(r,P+"facts/records/0").put("rawSampleValue","0/1:40:60:20,20:LowQual"),"facts"));
        list.add(mut("record-filter","RECORD_FILTER_NOT_PASS",r->obj(r,P+"facts/records/0").put("recordFilter","LowQual"),"facts"));
        list.add(mut("missing-ft","RECORD_FT_REQUIRED",r->{obj(r,P+"facts/members/0/detection").put("sampleFormat","GT:DP:GQ:AD");obj(r,P+"facts/records/0").put("rawSampleValue","0/1:40:60:20,20");},"facts"));
        list.add(mut("dot-ft","RECORD_FT_REQUIRED",r->obj(r,P+"facts/records/0").put("rawSampleValue","0/1:40:60:20,20:."),"facts"));
        list.add(mut("low-depth","RECORD_QUALITY_UNSUPPORTED",r->obj(r,P+"facts/records/0").put("rawSampleValue","0/1:10:60:5,5:PASS"),"facts"));
        list.add(mut("bad-gq","RECORD_QUALITY_UNSUPPORTED",r->obj(r,P+"facts/records/0").put("rawSampleValue","0/1:40:10:20,20:PASS"),"facts"));
        list.add(mut("bad-ad","RECORD_QUALITY_UNSUPPORTED",r->obj(r,P+"facts/records/0").put("rawSampleValue","0/1:40:60:19,20:PASS"),"facts"));
        list.add(mut("gt-conflict","RECORD_RAW_PROJECTION_CONFLICT",r->obj(r,P+"facts/records/0").put("rawSampleValue","1/1:40:60:0,40:PASS"),"facts"));
        list.add(mut("alt-wrap","DETECTION_UNESTABLISHED",r->obj(r,P+"facts/members/0/detection").put("targetAltIndex",4294967297L),"facts"));
        list.add(mut("review-status","REVIEW_REQUIRED",r->obj(r,P+"phenotypeReview/review").put("status","UNKNOWN"),"phenotypeReview"));
        return list.stream().map(v->DynamicTest.dynamicTest(v.name,()->{var root=base("alpha");v.edit.accept(root);if(v.parts.length>0)reissue(root,v.parts);ne(root,v.reason);}));
    }
    @TestFactory Stream<DynamicTest> rawGtControls(){return Stream.of("0/1","1/0","0|1","1|0").map(gt->DynamicTest.dynamicTest(gt,()->{var r=base("alpha");obj(r,P+"facts/records/0").put("rawSampleValue",gt+":40:60:20,20:PASS");obj(r,P+"facts/records/0/observation").put("genotype",gt).put("phased",gt.contains("|"));reissue(r,"facts");assertEquals("MET",evaluate(r).at("/evaluation/evaluationResult/status").asText());}));}
    @Test void missingFamilyAndSourceFailures()throws Exception {
        var r=base("alpha");obj(r,"/context/patientContext").remove("familyContextId");ne(r,"FAMILY_CONTEXT_REQUIRED");
        for(String raw:List.of("{\"finite\":0.25}","{\"é\":1,\"e\\u0301\":2}","{\"broken\":")){
            r=base("alpha");obj(r,P+"facts/sources/0").put("rawUtf8",raw).put("rawSha256",raw(raw.getBytes(StandardCharsets.UTF_8)));pinsOnly(r);ne(r,"SOURCE_DOCUMENT_INVALID");
        }
        r=base("alpha");obj(r,P+"facts/sources/0").put("rawSha256","0".repeat(64));pinsOnly(r);ne(r,"SOURCE_RAW_PIN_MISMATCH");
        r=base("alpha");obj(r,P+"facts/sources/0").put("canonicalSha256","0".repeat(64));pinsOnly(r);ne(r,"SOURCE_CANONICAL_PIN_MISMATCH");
        r=base("alpha");obj(r,P+"facts/clinical").put("age","13");pinsOnly(r);ne(r,"SOURCE_FACTS_MISMATCH");
        r=base("alpha");obj(r,P+"assessment/partPins").put("facts","0".repeat(64));ne(r,"PART_PIN_MISMATCH");
        r=base("alpha");obj(r,P+"assessment").put("contentSha256","0".repeat(64));ne(r,"ASSESSMENT_PIN_MISMATCH");
        r=base("alpha");obj(r,"/registryBinding").put("registrySha256","0".repeat(64));ne(r,"REGISTRY_OR_AUTHORITY_INVALID");
        r=base("alpha");obj(r,"/policy/scientificSources/0").put("sha256","0".repeat(64));catalog(r);ne(r,"REGISTRY_OR_AUTHORITY_INVALID");
    }
    @Test void strictLoader()throws Exception {
        var r=base("alpha");byte[] bytes=M.writeValueAsBytes(r);assertThrows(IllegalArgumentException.class,()->Pp4PhenotypeBundle.load(M,bytes,"0".repeat(64),hash(r)));
        assertThrows(IllegalArgumentException.class,()->Pp4PhenotypeBundle.load(M,bytes,raw(bytes),"0".repeat(64)));
        for(String text:List.of("{}{}","{\"a\":1,\"a\":2}","{\"v\":NaN}","{\"é\":1,\"e\\u0301\":2}","{\"v\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}")) {byte[] b=text.getBytes(StandardCharsets.UTF_8);assertThrows(IllegalArgumentException.class,()->Pp4PhenotypeBundle.load(M,b,raw(b),"0".repeat(64)));}
        var extra=base("alpha");obj(extra,P+"phenotypeReview").put("hpoSimilarity",99);assertThrows(IllegalArgumentException.class,()->load(extra));
        byte[] large=new byte[1_048_577];assertThrows(IllegalArgumentException.class,()->Pp4PhenotypeBundle.load(M,large,raw(large),"0".repeat(64)));
    }
    @Test void selectionAndRegistryIsolation()throws Exception {
        var r=base("alpha");var c=obj(r,"/context");var cat=obj(r,"/catalog");var p=M.createArrayNode().add(r.path("policy"));String pin=r.path("catalogSha256").asText();
        var selectors=List.of(new AcmgSpecificationSelector(M,cat,pin,p),AcmgSpecificationSelector.forPs2Versioned(M,cat,pin,p),AcmgSpecificationSelector.forPm6Versioned(M,cat,pin,p),AcmgSpecificationSelector.forPm3Versioned(M,cat,pin,p),AcmgSpecificationSelector.forBp2Versioned(M,cat,pin,p),AcmgSpecificationSelector.forPp1Versioned(M,cat,pin,p),AcmgSpecificationSelector.forBs4Versioned(M,cat,pin,p));
        for(var s:selectors)assertNull(s.select(c,"ACMG_AMP_2015:PP4").selectedPolicy());
        var s=AcmgSpecificationSelector.forPp4Versioned(M,cat,pin,p);assertNotNull(s.select(c,"ACMG_AMP_2015:PP4").selectedPolicy());assertNull(s.select(c,"ACMG_AMP_2015:PP1").selectedPolicy());
        for(String state:List.of("SPECIALIZED_UNIMPLEMENTED","SPECIALIZED_VERSION_CONFLICT")){var v=base("alpha");obj(v,"/catalog/entries/0").put("disposition",state);v.put("catalogSha256",hash(v.path("catalog")));var out=evaluate(v);assertEquals("NOT_EVALUATED",out.at("/evaluationResult/status").asText());assertFalse(out.has("evidence"));}
        var v=base("alpha");obj(v,"/catalog/entries/0/scope").put("transcriptMode","EXACT_ACCESSIONS").putArray("transcripts").addObject().put("accession","NM_999999").put("version",1);v.put("catalogSha256",hash(v.path("catalog")));ne(v,"POSSIBLE_SPECIALIZED_TRANSCRIPT_SCOPE_UNRESOLVED");
        v=base("alpha");obj(v,"/catalog/entries/0").put("searchStatus","INCOMPLETE");v.put("catalogSha256",hash(v.path("catalog")));ne(v,"CLAUSE_SEARCH_INCOMPLETE");
        v=base("alpha");obj(v,"/catalog/entries/0/policyBinding").put("policySha256","0".repeat(64));v.put("catalogSha256",hash(v.path("catalog")));ne(v,"POLICY_INTEGRITY_MISMATCH");
        try(var oldStream=getClass().getResourceAsStream(Bs4NonSegregationCriterionEvaluator.REGISTRY);var newStream=getClass().getResourceAsStream(Pp4PhenotypeCriterionEvaluator.REGISTRY)){
            assertNotNull(oldStream);assertNotNull(newStream);var old=M.readTree(oldStream);var next=M.readTree(newStream);int unchanged=0;
            for(var criterion:old.path("criteria")){var other=StreamSupport(next.path("criteria")).filter(x->x.path("criterionId").equals(criterion.path("criterionId"))).findFirst().orElseThrow();if(!criterion.path("code").asText().equals("PP4")){assertEquals(criterion,other);unchanged++;}else for(String field:List.of("original2015","currentGeneral","allowedStrengths","direction"))assertEquals(criterion.path(field),other.path(field));}assertEquals(27,unchanged);
        }
    }
    static Stream<JsonNode> StreamSupport(JsonNode n){return java.util.stream.StreamSupport.stream(n.spliterator(),false);}
    static void receipt(String name,JsonNode data)throws Exception {String dest=System.getProperty("varvis.pp4.artifacts");if(dest!=null){Path d=Path.of(dest,"vectors");Files.createDirectories(d);Files.write(d.resolve(name+".json"),M.writeValueAsBytes(data));}}
    @Test void independentIdentitiesAndStableGroup()throws Exception {
        var root=base("alpha");var out=evaluate(root);var evaluation=obj(out,"/evaluation").deepCopy();String id=evaluation.remove("evaluationId").asText();evaluation.remove(List.of("conflict","review"));assertEquals("evaluation-sha256-"+hash(evaluation),id);
        var trace=obj(out,"/evaluation/deterministicTrace").deepCopy();String sha=trace.remove("traceSha256").asText();assertEquals(hash(trace),sha);
        var receipt=obj(out,"/policySelection").deepCopy();String selection=receipt.remove("selectionId").asText();assertEquals("cspec-selection-sha256-"+hash(receipt),selection);
        var adapter=new RuntimeEvidenceV2Adapter(M);assertTrue(adapter.savedAtomicIdentityMatches(obj(out,"/evidence")));
        var b=base("beta");counts(b,33,100);var other=evaluate(b);assertEquals(out.path("rawPoints"),other.path("rawPoints"));assertEquals(out.at("/evaluation/appliedStrength"),other.at("/evaluation/appliedStrength"));
        for(String part:PARTS)obj(root,P+part+"/binding").put("observationId","TEST_ONLY_NEW_OBSERVATION");obj(root,P+"facts").put("observationId","TEST_ONLY_NEW_OBSERVATION");obj(root,P+"facts/clinical/binding").put("observationId","TEST_ONLY_NEW_OBSERVATION");obj(root,P+"facts/members/0/detection/binding").put("observationId","TEST_ONLY_NEW_OBSERVATION");reissue(root,PARTS.toArray(String[]::new));var changed=evaluate(root);assertEquals(out.path("evidenceGroupId"),changed.path("evidenceGroupId"));
    }
}
