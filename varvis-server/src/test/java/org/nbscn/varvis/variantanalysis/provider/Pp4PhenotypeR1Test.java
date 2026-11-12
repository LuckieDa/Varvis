package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pp4TestMaterials.*;

/** Frozen reviewer inputs never pass through an issuer. New semantic probes use explicit R1 material. */
class Pp4PhenotypeR1Test {
    private static final String Y="/observations/0/diagnosticYield";
    @TestFactory Stream<DynamicTest> frozenReviewerBytes() {
        String[][] cases={{"alpha-ascertainment-unknown","COHORT_ASCERTAINMENT_UNESTABLISHED"},{"beta-ascertainment-positive-only","COHORT_ASCERTAINMENT_INCOMPATIBLE"},{"alpha-alternative-method-unknown","ALTERNATIVE_METHOD_UNESTABLISHED"},{"beta-phenocopy-method-unknown","ALTERNATIVE_METHOD_UNESTABLISHED"}};
        return Arrays.stream(cases).map(c->DynamicTest.dynamicTest(c[0],()->{
            byte[] bytes=resource("frozen/"+c[0]+"-input.json");var pins=M.readTree(resource("frozen/"+c[0]+"-pins.json"));
            assertEquals(pins.path("rawSha256").asText(),raw(bytes));
            var loaded=Pp4PhenotypeBundle.load(M,bytes,pins.path("rawSha256").asText(),pins.path("canonicalSha256").asText());
            var actual=new Pp4PhenotypeCriterionEvaluator(M).evaluate(loaded);artifact("r1-frozen-"+c[0],(ObjectNode)M.readTree(bytes),actual);
            assertMinimal(actual,c[1]);assertEquals(M.readTree(bytes),actual.path("sourceBundle"));
        }));
    }
    private static void assertMinimal(ObjectNode out,String reason) {
        assertEquals("NOT_EVALUATED",out.at("/evaluationResult/status").asText());assertEquals("PP4_"+reason,out.at("/evaluationResult/reasonCode").asText());
        assertEquals("UNKNOWN",out.at("/criterionApplicability/status").asText());assertEquals(out.path("evaluationResult"),out.at("/criterionEvaluations/0/evaluationResult"));
        assertEquals(out.path("criterionApplicability"),out.at("/criterionEvaluations/0/criterionApplicability"));
        for(String field:List.of("evidence","evaluation","evaluationId","evidenceId","deterministicTrace","rawPoints","appliedPoints","rawHalfPoints","appliedHalfPoints","appliedStrength","baseStrength"))assertFalse(out.has(field),field);
        assertEquals(Set.of("criterionId","evaluationResult","criterionApplicability"),Pp4PhenotypeCoreTest.fieldSet(out.at("/criterionEvaluations/0")));
    }
    @TestFactory Stream<DynamicTest> reviewedModesAndDeclarations() {
        List<DynamicTest> tests=new ArrayList<>();
        for(String background:List.of("alpha","beta")) {
            tests.add(DynamicTest.dynamicTest(background+"-legitimate",()->{var r=base(background);var out=evaluate(r);assertEquals("MET",out.at("/evaluation/evaluationResult/status").asText());assertEquals("2",out.path("appliedPoints").asText());}));
            for(String mode:List.of("UNKNOWN","UNSUPPORTED_SAMPLING","POSITIVE_ONLY","GENOTYPE_FIRST","RESOLVED_CASES_ONLY"))tests.add(DynamicTest.dynamicTest(background+"-"+mode,()->{
                var r=base(background);obj(r,Y).put("ascertainment",mode);reissue(r,"diagnosticYield");var out=evaluate(r);String reason=Set.of("UNKNOWN","UNSUPPORTED_SAMPLING").contains(mode)?"COHORT_ASCERTAINMENT_UNESTABLISHED":"COHORT_ASCERTAINMENT_INCOMPATIBLE";expected(M.createObjectNode().put("reasonCode","PP4_"+reason));assertMinimal(out,reason);
            }));
            for(String field:List.of("eligibility","testing","resultSelection"))for(String value:List.of("UNKNOWN","CONTRADICTORY"))tests.add(DynamicTest.dynamicTest(background+"-"+field+"-"+value,()->{
                var r=base(background);obj(r,Y+"/ascertainmentDetails").put(field,value);reissue(r,"diagnosticYield");var out=evaluate(r);String reason=value.equals("UNKNOWN")?"COHORT_ASCERTAINMENT_UNESTABLISHED":"COHORT_ASCERTAINMENT_INCOMPATIBLE";expected(M.createObjectNode().put("reasonCode","PP4_"+reason));assertMinimal(out,reason);
            }));
            tests.add(DynamicTest.dynamicTest(background+"-positive-selection-with-valid-mode",()->{var r=base(background);obj(r,Y+"/ascertainmentDetails").put("resultSelection","POSITIVE_ONLY");reissue(r,"diagnosticYield");assertMinimal(evaluate(r),"COHORT_ASCERTAINMENT_INCOMPATIBLE");}));
            tests.add(DynamicTest.dynamicTest(background+"-missing-details",()->{var r=base(background);obj(r,Y).remove("ascertainmentDetails");reissue(r,"diagnosticYield");assertMinimal(evaluate(r),"COHORT_ASCERTAINMENT_UNESTABLISHED");}));
            for(int i=0;i<2;i++){final int index=i;tests.add(DynamicTest.dynamicTest(background+"-alternative-method-"+i,()->{var r=base(background);obj(r,"/observations/0/phenotypeReview/alternatives/"+index).put("method","UNKNOWN");reissue(r,"phenotypeReview");assertMinimal(evaluate(r),"ALTERNATIVE_METHOD_UNESTABLISHED");}));}
            tests.add(DynamicTest.dynamicTest(background+"-source-projection-unchanged",()->{var r=base(background);obj(r,Y+"/ascertainmentDetails").put("testing","POSITIVE_CASES_TESTED");pinsOnly(r);assertMinimal(evaluate(r),"SOURCE_FACTS_MISMATCH");}));
            tests.add(DynamicTest.dynamicTest(background+"-old-part-pins",()->{var r=base(background);String oldPin=r.at("/observations/0/assessment/partPins/diagnosticYield").asText();obj(r,Y+"/ascertainmentDetails").put("testing","POSITIVE_CASES_TESTED");reissue(r,"diagnosticYield");obj(r,"/observations/0/assessment/partPins").put("diagnosticYield",oldPin);assertMinimal(evaluate(r),"PART_PIN_MISMATCH");}));
        }
        return tests.stream();
    }
}
