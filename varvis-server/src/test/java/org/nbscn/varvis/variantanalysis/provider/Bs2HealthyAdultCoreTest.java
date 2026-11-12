package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bs2TestMaterials.*;

class Bs2HealthyAdultCoreTest {
    static final String O="/observations/0/",H=O+"healthyAdultReview",D=O+"diseaseApplicability",F=O+"facts",U=O+"evidenceUse";
    @TestFactory Stream<DynamicTest> fixedBackgrounds(){return Stream.of("alpha","beta").map(key->DynamicTest.dynamicTest(key,()->{
        byte[] b=resource(key+"-bundle.json");var pins=M.readTree(resource(key+"-pins.json"));var expected=M.readTree(resource(key+"-expected.json"));
        var out=checkBytes("fixed-"+key,b,pins.path("rawSha256").asText(),pins.path("canonicalSha256").asText(),"");
        assertEquals(expected.path("evidenceGroupId"),out.path("evidenceGroupId"));assertEquals(expected.path("ageYears"),out.at("/healthyAdultReview/ageYears"));
        assertEquals(expected.path("examinations").asInt(),out.at("/healthyAdultReview/findings").size());assertEquals("UNKNOWN",out.at("/sourceBundle/observations/0/facts/clinical/familyHistory").asText());
    }));}
    @TestFactory Stream<DynamicTest> acceptedBoundaries(){return Stream.of("0/1","1/0","0|1","1|0","age18","age130","penetrance0","penetrance17").map(v->DynamicTest.dynamicTest(v,()->{
        var r=base("alpha");
        if(v.startsWith("age")){int age=Integer.parseInt(v.substring(3));obj(r,H).put("ageYears",age);obj(r,F+"/clinical").put("age",String.valueOf(age));for(var f:r.at(H+"/findings"))((ObjectNode)f).put("ageYears",age);}
        else if(v.startsWith("penetrance"))obj(r,D).put("fullyPenetrantAgeYears",Integer.parseInt(v.substring(10)));
        else {obj(r,F+"/records/0/observation").put("genotype",v).put("phased",v.contains("|"));obj(r,F+"/records/0").put("rawSampleValue",v+":40:60:20,20:PASS");}
        reissue(r,PARTS.toArray(String[]::new));check("accepted-"+v.replace('|','p').replace('/','u'),r,"");
    }));}
    record Mutation(String name,String reason,Consumer<ObjectNode> edit) { }
    record Chronology(String name, String observed, String assessed, boolean compatible) { }
    @TestFactory Stream<DynamicTest> sameCompletedAgeChronology() {
        var dates = List.of(
                new Chronology("three-years", "2023-10-04T00:00:00Z", "2026-10-04T00:00:00Z", false),
                new Chronology("earlier-long-validity", "2026-07-06T00:00:00Z", "2026-10-04T00:00:00Z", true),
                new Chronology("ordinary-before-anniversary", "2025-10-05T00:00:00Z", "2026-10-04T00:00:00Z", true),
                new Chronology("ordinary-anniversary", "2025-10-04T00:00:00Z", "2026-10-04T00:00:00Z", false),
                new Chronology("leap-365-days-compatible", "2023-03-01T00:00:00Z", "2024-02-29T00:00:00Z", true),
                new Chronology("leap-366-days-anniversary", "2023-03-01T00:00:00Z", "2024-03-01T00:00:00Z", false),
                new Chronology("leap-day-before-anniversary", "2024-02-29T00:00:00Z", "2025-02-28T00:00:00Z", true),
                new Chronology("leap-day-next-march", "2024-02-29T00:00:00Z", "2025-03-01T00:00:00Z", false),
                new Chronology("utc-offset-same-instant", "2026-10-03T20:00:00-04:00", "2026-10-04T00:00:00Z", true),
                new Chronology("anniversary-before-clock-time", "2025-10-04T23:00:00Z", "2026-10-04T00:00:00Z", false));
        return Stream.of("alpha", "beta").flatMap(key -> dates.stream().map(d -> DynamicTest.dynamicTest(key + "-" + d.name(), () -> {
            var input = base(key);
            obj(input, H).put("assessedAt", d.assessed());
            obj(input, F + "/clinical").put("observedAt", d.assessed());
            for (var finding : input.at(H + "/findings")) ((ObjectNode) finding).put("observedAt", d.observed());
            for (var requirement : input.at(D + "/definition/requirements")) ((ObjectNode) requirement).put("validityDays", 3650);
            reissue(input, "facts", "healthyAdultReview", "diseaseApplicability");
            var out = check("chronology-" + key + "-" + d.name(), input, d.compatible() ? "" : "EXAM_AGE_TIME_CONFLICT");
            if (!d.compatible()) {
                var expected = M.createObjectNode().put("criterionId", Bs2HealthyAdultCriterionEvaluator.CRITERION);
                expected.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", "BS2_EXAM_AGE_TIME_CONFLICT");
                expected.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", "BS2_EXAM_AGE_TIME_CONFLICT");
                assertEquals(expected, out.at("/criterionEvaluations/0"));
                assertEquals(expected.path("evaluationResult"), out.path("evaluationResult"));
                assertEquals(expected.path("criterionApplicability"), out.path("criterionApplicability"));
                assertFalse(out.has("policySelection"));
            }
        })));
    }
    static Mutation mut(String name,String reason,Consumer<ObjectNode> edit){return new Mutation(name,reason,edit);}
    @TestFactory Stream<DynamicTest> semanticGates(){List<Mutation> cases=new ArrayList<>();
        for(long age:new long[]{-1,0,17,131,4294967322L})cases.add(mut("age-"+age,"ADULT_AGE_UNESTABLISHED",r->{obj(r,H).put("ageYears",age);obj(r,F+"/clinical").put("age",String.valueOf(age));}));
        cases.add(mut("age-null","ADULT_AGE_UNESTABLISHED",r->obj(r,H).putNull("ageYears")));
        cases.add(mut("age-unit","ADULT_AGE_UNESTABLISHED",r->obj(r,H).put("ageUnit","MONTHS")));
        cases.add(mut("age-fact-conflict","ADULT_AGE_UNESTABLISHED",r->obj(r,F+"/clinical").put("age","27")));
        cases.add(mut("assessment-time","CLINICAL_TIME_CONFLICT",r->obj(r,H).put("assessedAt","2026-10-03T00:00:00Z")));
        cases.add(mut("date-invalid","CLINICAL_TIME_INVALID",r->obj(r,H).put("assessedAt","UNKNOWN")));
        cases.add(mut("review-before-clinical","CLINICAL_TIME_CONFLICT",r->obj(r,H+"/review").put("date","2026-10-03T00:00:00Z")));
        cases.add(mut("source-after-review","REVIEW_TIME_CONFLICT",r->obj(r,H+"/sources/0").put("acquiredAt","2027-01-01T00:00:00Z")));
        for(long age:new long[]{-1,18,100,4294967297L})cases.add(mut("penetrance-"+age,"EARLY_FULL_PENETRANCE_UNESTABLISHED",r->obj(r,D).put("fullyPenetrantAgeYears",age)));
        for(String model:List.of("UNKNOWN","REDUCED","AGE_DEPENDENT_LATE","CONFLICT"))cases.add(mut("model-"+model,"EARLY_FULL_PENETRANCE_UNESTABLISHED",r->obj(r,D).put("model",model)));
        cases.add(mut("late-onset","EARLY_FULL_PENETRANCE_UNESTABLISHED",r->obj(r,D).put("onset","LATE_ONSET")));
        cases.add(mut("circular-basis","DISEASE_BASIS_UNESTABLISHED",r->obj(r,D).put("independence","CURRENT_UNAFFECTED_CARRIER")));
        cases.add(mut("basis-no-person-exclusion","DISEASE_BASIS_UNESTABLISHED",r->obj(r,D).putArray("excludedPersonIds")));
        cases.add(mut("mechanism-conflict","DISEASE_MECHANISM_OR_SUBTYPE_UNESTABLISHED",r->obj(r,D).put("mechanism","CONFLICT")));
        cases.add(mut("subtype-mismatch","DISEASE_MECHANISM_OR_SUBTYPE_UNESTABLISHED",r->obj(r,H).put("subtype","OTHER")));
        cases.add(mut("sex-patient-conflict","SEX_APPLICABILITY_UNESTABLISHED",r->obj(r,H).put("sex","MALE")));
        cases.add(mut("sex-scope-conflict","SEX_APPLICABILITY_UNESTABLISHED",r->obj(r,D).putArray("sexScope").add("MALE")));
        cases.add(mut("population-unknown","POPULATION_APPLICABILITY_UNESTABLISHED",r->obj(r,H).put("populationId","UNKNOWN")));
        cases.add(mut("population-other","POPULATION_APPLICABILITY_UNESTABLISHED",r->obj(r,H).put("populationId","OTHER")));
        for(String s:List.of("GNOMAD_POPULATION_RECORD","UNDIAGNOSED_SCREENING_LABEL","UNKNOWN"))cases.add(mut("source-type-"+s,"DIRECT_HEALTHY_CLINICAL_MATERIAL_REQUIRED",r->obj(r,H).put("sourceType",s)));
        for(String s:List.of("UNKNOWN","PRESENT","ABNORMAL"))cases.add(mut("exam-result-"+s,"EXAM_NEGATIVE_RESULT_UNESTABLISHED",r->obj(r,H+"/findings/0").put("result",s)));
        cases.add(mut("method-unknown","EXAM_METHOD_OR_COMPLETION_UNESTABLISHED",r->obj(r,H+"/findings/0").put("methodId","UNKNOWN")));
        cases.add(mut("exam-not-done","EXAM_METHOD_OR_COMPLETION_UNESTABLISHED",r->obj(r,H+"/findings/0").put("examination","NOT_PERFORMED")));
        cases.add(mut("exam-not-applicable","EXAM_METHOD_OR_COMPLETION_UNESTABLISHED",r->obj(r,H+"/findings/0").put("applicability","UNKNOWN")));
        cases.add(mut("exam-age-conflict","EXAM_AGE_CONFLICT",r->obj(r,H+"/findings/0").put("ageYears",24)));
        cases.add(mut("expired-exam","EXAM_EXPIRED_OR_TIME_CONFLICT",r->obj(r,H+"/findings/0").put("observedAt","2020-01-01T00:00:00Z")));
        cases.add(mut("future-exam","EXAM_EXPIRED_OR_TIME_CONFLICT",r->obj(r,H+"/findings/0").put("observedAt","2027-01-01T00:00:00Z")));
        cases.add(mut("age-window","EXAM_AGE_WINDOW_UNSUPPORTED",r->obj(r,D+"/definition/requirements/0").put("ageMaximumYears",20)));
        cases.add(mut("window-invalid","EXAM_VALIDITY_WINDOW_UNESTABLISHED",r->obj(r,D+"/definition/requirements/0").put("validityDays",-1)));
        cases.add(mut("positive-fact-retained","CLINICAL_FACT_ROSTER_CONFLICT",r->obj(r,F+"/clinical").putArray("phenotypeObservations").add("POSITIVE_FINDING")));
        cases.add(mut("missing-exam","CLINICAL_FACT_ROSTER_CONFLICT",r->((ArrayNode)r.at(H+"/findings")).remove(0)));
        cases.add(mut("missing-required-exam","EXAM_REQUIRED_MISSING",r->{((ArrayNode)r.at(H+"/findings")).remove(0);((ArrayNode)r.at(F+"/clinical/exclusions")).remove(0);}));
        cases.add(mut("definition-method-conflict","EXAM_DEFINITION_RESULT_CONFLICT",r->obj(r,D+"/definition/requirements/0").put("methodId","DIFFERENT")));
        for(String kind:List.of("PRESENT","UNKNOWN"))cases.add(mut("masking-"+kind,"MASKING_FACTOR_UNESTABLISHED",r->obj(r,H+"/masking/0").put("status",kind)));
        cases.add(mut("masking-missing","MASKING_ROSTER_INCOMPLETE",r->((ArrayNode)r.at(H+"/masking")).remove(0)));
        for(String field:List.of("recordFilter","sampleFilter"))cases.add(mut("failure-"+field,field.equals("recordFilter")?"RECORD_FILTER_NOT_PASS":"RECORD_RAW_PROJECTION_CONFLICT",r->obj(r,F+"/records/0").put(field,"LowQual")));
        cases.add(mut("raw-ft-failed-material-pass","SAMPLE_FT_NOT_PASS",r->obj(r,F+"/records/0").put("rawSampleValue","0/1:40:60:20,20:LowQual")));
        cases.add(mut("raw-ft-missing","RECORD_FT_REQUIRED",r->{obj(r,F+"/records/0").put("rawSampleValue","0/1:40:60:20,20");obj(r,F+"/members/0/detection").put("sampleFormat","GT:DP:GQ:AD");}));
        cases.add(mut("raw-ft-dot","RECORD_FT_REQUIRED",r->obj(r,F+"/records/0").put("rawSampleValue","0/1:40:60:20,20:.")));
        cases.add(mut("raw-dp-low","RECORD_QUALITY_UNSUPPORTED",r->obj(r,F+"/records/0").put("rawSampleValue","0/1:10:60:5,5:PASS")));
        cases.add(mut("raw-gq-low","RECORD_QUALITY_UNSUPPORTED",r->obj(r,F+"/records/0").put("rawSampleValue","0/1:40:10:20,20:PASS")));
        cases.add(mut("raw-ad-conflict","RECORD_QUALITY_UNSUPPORTED",r->obj(r,F+"/records/0").put("rawSampleValue","0/1:40:60:10,10:PASS")));
        cases.add(mut("raw-integer-overflow","RECORD_INTEGER_INVALID",r->obj(r,F+"/records/0").put("rawSampleValue","0/1:4294967336:60:20,20:PASS")));
        cases.add(mut("raw-gt-conflict","RECORD_RAW_PROJECTION_CONFLICT",r->obj(r,F+"/records/0").put("rawSampleValue","1|0:40:60:20,20:PASS")));
        cases.add(mut("record-sample","RECORD_BINDING_MISMATCH",r->obj(r,F+"/records/0").put("sampleId","OTHER")));
        cases.add(mut("record-gene","RECORD_BINDING_MISMATCH",r->obj(r,F+"/records/0").put("geneSymbol","OTHER")));
        cases.add(mut("record-allele","RECORD_BINDING_MISMATCH",r->obj(r,F+"/records/0/variantKey").put("position",1)));
        cases.add(mut("person-mismatch","PROBAND_BINDING_MISMATCH",r->obj(r,F+"/members/0").put("memberId","OTHER")));
        cases.add(mut("known-mosaic","CLINICAL_FACT_BINDING_INVALID",r->obj(r,F+"/clinical").put("mosaicism","KNOWN")));
        cases.add(mut("alt-overflow","DETECTION_UNESTABLISHED",r->obj(r,F+"/members/0/detection").put("targetAltIndex",4294967297L)));
        cases.add(mut("ploidy-overflow","DETECTION_UNESTABLISHED",r->obj(r,F+"/members/0/detection").put("ploidy",4294967298L)));
        cases.add(mut("detection-unknown-method","DETECTION_UNESTABLISHED",r->obj(r,F+"/members/0/detection").put("method","UNKNOWN")));
        cases.add(mut("nfc-duplicate-member","SINGLE_PROBAND_REQUIRED",r->{var member=obj(r,F+"/members/0");member.put("memberId","test-é");((ArrayNode)r.at(F+"/members")).add(member.deepCopy().put("memberId","test-e\u0301"));}));
        cases.add(mut("nfc-exam-alias","EXAM_IDENTITY_DUPLICATE",r->{obj(r,H+"/findings/0").put("findingId","test-é");obj(r,H+"/findings/1").put("findingId","test-e\u0301");}));
        cases.add(mut("multiple-observations","SINGLE_OBSERVATION_REQUIRED_NO_AGGREGATION",r->((ArrayNode)r.path("observations")).add(r.at("/observations/0").deepCopy())));
        cases.add(mut("family-missing","FAMILY_CONTEXT_REQUIRED",r->obj(r,"/context/patientContext").remove("familyContextId")));
        cases.add(mut("use-missing","SHARED_CLINICAL_USE_ROSTER_INCOMPLETE",r->((ArrayNode)r.at(U+"/uses")).remove(0)));
        for(int i=0;i<4;i++){final int index=i;cases.add(mut("use-occupied-"+i,"SHARED_CLINICAL_EVIDENCE_OCCUPIED_OR_CONFLICT",r->obj(r,U+"/uses/"+index).put("status","APPLIED")));}
        cases.add(mut("use-unknown","SHARED_CLINICAL_EVIDENCE_OCCUPIED_OR_CONFLICT",r->obj(r,U+"/uses/0").put("status","UNKNOWN")));
        cases.add(mut("use-wrong-person","EVIDENCE_USE_BINDING_MISMATCH",r->obj(r,U).put("personId","OTHER")));
        cases.add(mut("use-wrong-source","EVIDENCE_USE_BINDING_MISMATCH",r->obj(r,U).putArray("clinicalSourceIds").add("OTHER")));
        cases.add(mut("wrong-disease-binding","PART_BINDING_MISMATCH",r->obj(r,D+"/binding").put("mondoId","MONDO:0000001")));
        cases.add(mut("review-unknown","REVIEW_REQUIRED",r->obj(r,H+"/review").put("status","UNKNOWN")));
        return cases.stream().map(v->DynamicTest.dynamicTest(v.name,()->{var r=base("alpha");v.edit.accept(r);reissue(r,PARTS.toArray(String[]::new));check(v.name,r,v.reason);}));
    }
    @TestFactory Stream<DynamicTest> unsupportedGenotypes(){return Stream.of("0/0","1/1","1","0/2","./.").map(gt->DynamicTest.dynamicTest(gt,()->{
        var r=base("alpha");var rec=obj(r,F+"/records/0");String ad=gt.equals("0/0")?"40,0":"0,40";
        rec.put("rawSampleValue",gt+":40:60:"+ad+":PASS");obj(rec,"/observation").put("genotype",gt).put("alleleDepth",ad).put("phased",false).put("call",gt.equals("0/0")?"HOM_REF":"HOM_ALT");
        reissue(r,"facts");check("gt-"+gt.replace('/','u').replace('.','x'),r,gt.equals("0/0")||gt.equals("1/1")?"TARGET_HET_REQUIRED":"RECORD_DIPLOID_BIALLELIC_GT_REQUIRED");
    }));}
    @TestFactory Stream<DynamicTest> scopeAndSelector(){return Stream.of("AR","X","INDEL","mapping","specialized","specialized-implemented","specialized-conflict","catalog-incomplete","tx-missing","policy-scope","family-other","sex-unknown").map(kind->DynamicTest.dynamicTest(kind,()->{
        var r=base("alpha");String reason;
        switch(kind){
            case "AR" -> {obj(r,"/context").put("moi","AUTOSOMAL_RECESSIVE");for(String p:PARTS)obj(r,O+p+"/binding").put("moi","AUTOSOMAL_RECESSIVE");obj(r,F+"/clinical/binding").put("moi","AUTOSOMAL_RECESSIVE");obj(r,F+"/members/0/detection/binding").put("moi","AUTOSOMAL_RECESSIVE");contextIdentity(r);reason="NATIVE_AUTOSOMAL_AD_SNV_REQUIRED";}
            case "X","INDEL","mapping" -> {
                var v=obj(r,"/context/variant");
                for(String field:List.of("inputAllele","normalizedAllele","evidenceQueryAllele")){
                    if(kind.equals("X"))obj(v,"/"+field).put("contig","X");
                    if(kind.equals("INDEL"))obj(v,"/"+field).put("alternate","TC");
                    if(kind.equals("mapping"))obj(v,"/"+field).put("assembly","GRCh37");
                }
                if(kind.equals("INDEL"))v.put("variantType","SMALL_INSERTION");
                if(kind.equals("mapping")){
                    obj(v,"/evidenceQueryAllele").put("assembly","GRCh38");var lineage=obj(v,"/assemblyLineage");lineage.put("mappingStatus","MAPPED");
                    var map=lineage.putObject("coordinateMapping").put("method","TEST_ONLY_MAPPING").put("methodVersion","1").put("refAltValidation","MATCH");map.set("sourceAllele",v.path("normalizedAllele").deepCopy());map.set("targetAllele",v.path("evidenceQueryAllele").deepCopy());
                }
                alleleIdentity(r);new RuntimeEvidenceV2Adapter(M).validateVersionedContext(obj(r,"/context"));reason="NATIVE_AUTOSOMAL_AD_SNV_REQUIRED";
            }
            case "specialized" -> {obj(r,"/catalog/entries/0").put("disposition","SPECIALIZED_UNIMPLEMENTED");reason="APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED";}
            case "specialized-implemented" -> {obj(r,"/catalog/entries/0").put("disposition","SPECIALIZED_IMPLEMENTED");reason="SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED";}
            case "specialized-conflict" -> {obj(r,"/catalog/entries/0").put("disposition","SPECIALIZED_VERSION_CONFLICT");reason="APPLICABLE_SPECIALIZED_VERSION_CONFLICT";}
            case "catalog-incomplete" -> {obj(r,"/catalog/entries/0").put("searchStatus","INCOMPLETE");reason="CLAUSE_SEARCH_INCOMPLETE";}
            case "tx-missing" -> {obj(r,"/catalog/entries/0/scope").put("transcriptMode","EXACT_ACCESSIONS").putArray("transcripts").addObject().put("accession","NM_000001").put("version",1);reason="POSSIBLE_SPECIALIZED_TRANSCRIPT_SCOPE_UNRESOLVED";}
            case "policy-scope" -> {obj(r,"/policy/scope").put("mondoId","MONDO:0000001");reason="POLICY_CONTEXT_SCOPE_MISMATCH";}
            case "family-other" -> {obj(r,"/context/patientContext").put("familyContextId","family-context-test-other");contextIdentity(r);reason="CONTEXT_BINDING_MISMATCH";}
            default -> {obj(r,"/context/patientContext").put("sex","UNKNOWN");contextIdentity(r);reason="SEX_APPLICABILITY_UNESTABLISHED";}
        }
        reissue(r,PARTS.toArray(String[]::new));check("scope-"+kind,r,reason);
    }));}
    @TestFactory Stream<DynamicTest> sourceAndPinGates(){return Stream.of("raw","canonical","projection","parse","float","part-pin","assessment","registry","authority","catalog").map(kind->DynamicTest.dynamicTest(kind,()->{
        var r=base("alpha");String reason;var s=obj(r,H+"/sources/0");
        switch(kind){
            case "raw" -> {s.put("rawSha256","0".repeat(64));reason="SOURCE_RAW_PIN_MISMATCH";}
            case "canonical" -> {s.put("canonicalSha256","0".repeat(64));reason="SOURCE_CANONICAL_PIN_MISMATCH";}
            case "projection" -> {s.put("rawUtf8","{\"wrong\":\"source\"}").put("rawSha256",raw(s.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8))).put("canonicalSha256",hash(M.readTree(s.path("rawUtf8").asText())));reason="SOURCE_FACTS_MISMATCH";}
            case "parse","float" -> {s.put("rawUtf8",kind.equals("parse")?"{":"{\"value\":0.25}");s.put("rawSha256",raw(s.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8)));reason="SOURCE_DOCUMENT_INVALID";}
            case "part-pin" -> {obj(r,H).put("healthStatus","UNKNOWN");reissue(r,"healthyAdultReview");obj(r,"/policy/partPins").put("healthyAdultReview","0".repeat(64));catalog(r);check("pin-"+kind,r,"PART_PIN_MISMATCH");return;}
            case "assessment" -> {obj(r,O+"assessment").put("contentSha256","0".repeat(64));check("pin-"+kind,r,"ASSESSMENT_PIN_MISMATCH");return;}
            case "registry" -> {obj(r,"/registryBinding").put("registrySha256","0".repeat(64));reason="REGISTRY_OR_AUTHORITY_INVALID";}
            case "authority" -> {obj(r,"/policy/scientificSources/0").put("sha256","0".repeat(64));reason="REGISTRY_OR_AUTHORITY_INVALID";}
            default -> {r.put("catalogSha256","0".repeat(64));check("pin-"+kind,r,"CATALOG_INTEGRITY_MISMATCH");return;}
        }
        pinsOnly(r);check("pin-"+kind,r,reason);
    }));}
    @Test void selectorIsolation()throws Exception{
        var r=base("alpha");var c=obj(r,"/context");var cat=obj(r,"/catalog");var p=M.createArrayNode().add(r.path("policy"));String pin=r.path("catalogSha256").asText();
        var selectors=List.of(new AcmgSpecificationSelector(M,cat,pin,p),AcmgSpecificationSelector.forPs2Versioned(M,cat,pin,p),AcmgSpecificationSelector.forPm6Versioned(M,cat,pin,p),AcmgSpecificationSelector.forPm3Versioned(M,cat,pin,p),AcmgSpecificationSelector.forBp2Versioned(M,cat,pin,p),AcmgSpecificationSelector.forPp1Versioned(M,cat,pin,p),AcmgSpecificationSelector.forBs4Versioned(M,cat,pin,p),AcmgSpecificationSelector.forPp4Versioned(M,cat,pin,p));
        for(var s:selectors)assertNull(s.select(c,"ACMG_AMP_2015:BS2").selectedPolicy());var bs2=AcmgSpecificationSelector.forBs2Versioned(M,cat,pin,p);
        for(String criterion:List.of("PS2","PM6","PM3","BP2","PP1","BS4","PP4","BA1"))assertNull(bs2.select(c,"ACMG_AMP_2015:"+criterion).selectedPolicy());
        obj(r,"/policy").putArray("criterionIds").add("ACMG_AMP_2015:BS2").add("ACMG_AMP_2015:PP4");pinsOnly(r);
        var mixed=AcmgSpecificationSelector.forBs2Versioned(M,obj(r,"/catalog"),r.path("catalogSha256").asText(),M.createArrayNode().add(r.path("policy"))).select(obj(r,"/context"),"ACMG_AMP_2015:BS2");
        assertNull(mixed.selectedPolicy());assertEquals("POLICY_CRITERION_SCOPE_MISMATCH",mixed.receipt().path("reasonCode").asText());
        check("mixed-criteria",r,"POLICY_CRITERION_SCOPE_MISMATCH");
    }
    @TestFactory Stream<DynamicTest> embeddedSourceBoundaries(){return Stream.of("facts","diseaseApplicability","evidenceUse").map(part->DynamicTest.dynamicTest(part,()->{
        var r=base("alpha");var source=obj(r,O+part+"/sources/0");source.put("rawUtf8","{\"value\":0.25}").put("rawSha256",raw("{\"value\":0.25}".getBytes(StandardCharsets.UTF_8)));
        pinsOnly(r);check("embedded-float-"+part,r,"SOURCE_DOCUMENT_INVALID");
    }));}
    @TestFactory Stream<DynamicTest> oldPp4TextRemainsNe(){return Stream.of("alpha","beta").map(key->DynamicTest.dynamicTest(key,()->{
        byte[] bytes;
        try(var stream=Bs2HealthyAdultCoreTest.class.getResourceAsStream("/pp4-phenotype-core/"+key+"-bundle.json")){assertNotNull(stream);bytes=stream.readAllBytes();}
        var root=(ObjectNode)M.readTree(bytes);Path d=artifacts("old-pp4-text-"+key);Files.write(d.resolve("loader-bytes.json"),bytes);
        Files.writeString(d.resolve("expected.txt"),"PP4_COHORT_ASCERTAINMENT_UNESTABLISHED");Files.writeString(d.resolve("pins.json"),M.writeValueAsString(M.createObjectNode().put("rawSha256",raw(bytes)).put("canonicalSha256",hash(root))));
        var out=new Pp4PhenotypeCriterionEvaluator(M).evaluate(Pp4PhenotypeBundle.load(M,bytes,raw(bytes),hash(root)));Files.write(d.resolve("output.json"),M.writeValueAsBytes(out));
        assertEquals("PP4_COHORT_ASCERTAINMENT_UNESTABLISHED",out.at("/evaluationResult/reasonCode").asText());assertFalse(out.has("evidence"));assertFalse(out.has("evaluation"));
    }));}
    @TestFactory Stream<DynamicTest> strictBytes(){return Stream.of("raw-pin","canonical-pin","duplicate","trailing","unknown-field","decimal-age","depth","size").map(kind->DynamicTest.dynamicTest(kind,()->{
        var r=base("alpha");byte[] bytes=M.writeValueAsBytes(r);String canonical=hash(r),expected;
        switch(kind){case "raw-pin" -> expected="RAW_PIN_MISMATCH";case "canonical-pin" -> expected="CANONICAL_PIN_MISMATCH";
            case "duplicate" -> {bytes="{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8);expected="JSON_INVALID";}
            case "trailing" -> {bytes="{} {}".getBytes(StandardCharsets.UTF_8);expected="SINGLE_OBJECT_REQUIRED";}
            case "unknown-field" -> {r.put("unknown",true);bytes=M.writeValueAsBytes(r);canonical=hash(r);expected="UNKNOWN_FIELD_unknown";}
            case "decimal-age" -> {obj(r,H).put("ageYears",26.5);bytes=M.writeValueAsBytes(r);expected="CANONICAL_INPUT_INVALID";}
            case "depth" -> {bytes=("{\"a\":".repeat(40)+"1"+"}".repeat(40)).getBytes(StandardCharsets.UTF_8);expected="JSON_INVALID";}
            default -> {bytes=new byte[Ps2DeNovoBundle.MAX_BYTES+1];expected="SIZE_LIMIT";}
        }
        final byte[] input=bytes;final String rp=kind.equals("raw-pin")?"0".repeat(64):raw(bytes),cp=kind.equals("canonical-pin")?"0".repeat(64):canonical;
        Path d=artifacts("strict-"+kind);Files.write(d.resolve("loader-bytes.json"),input);Files.writeString(d.resolve("expected.txt"),"BS2_"+expected);Files.writeString(d.resolve("pins.json"),M.writeValueAsString(M.createObjectNode().put("rawSha256",rp).put("canonicalSha256",cp)));
        var error=assertThrows(IllegalArgumentException.class,()->Bs2HealthyAdultBundle.load(M,input,rp,cp));Files.writeString(d.resolve("refusal.txt"),error.toString());assertEquals("BS2_"+expected,error.getMessage());
    }));}
}
