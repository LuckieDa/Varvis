package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Ba1ConditionalFrequencyRule.*;

/** Synthetic mechanical controls; neither original-source verification nor scientific adoption. */
class Ba1ConditionalFrequencyRuleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    static ObjectNode clause(ObjectMapper mapper) throws Exception {
        var rule = (ObjectNode) mapper.readTree("""
                {"disposition":"CONDITIONAL","reasonCode":"CSPEC_BA1_COMPLETE_FAF_OR_INDIVIDUAL_COUNT_FAMILY",
                 "semanticFamily":"FAF95_OR_HOMO_HEMIZYGOTE_COUNT_V1","originalMetric":"FAF",
                 "originalStatisticText":"Grpmax Filtering Allele Frequency",
                 "originalText":"Applicable when Grpmax Filtering Allele Frequency is ≥0.002 (0.2%) OR ≥10 homo- or hemizygotes (or the sum of total hemi- and homozygotes) are present in the most current version of gnomAD available at the time of curation.",
                 "conditions":[],"derivedPolicyVersion":"a01-ba1-faf-or-individual-count-v1",
                 "expression":{"operator":"ANY","frequency":{"metric":"gnomad.joint.faf95max",
                    "comparator":"GREATER_THAN_OR_EQUAL","value":"0.002","unit":"fraction","minimumAn":"2000",
                    "minimumAnComparator":"GREATER_THAN_OR_EQUAL","ancestries":["afr","amr","eas","mid","nfe","sas"]},
                   "individualCount":{"operator":"GREATER_THAN_OR_EQUAL","value":"10","unit":"individuals",
                    "populationScope":"SAME_DEDUPLICATED_SOURCE","counts":["HOMOZYGOUS","HEMIZYGOUS"],"sumOnlyIfMutuallyExclusive":true}},
                 "migration":{"status":"REVIEWED","reviewId":"SYNTHETIC_ONLY_NOT_APPROVAL",
                    "basis":"EXPLICIT_FAF_DEFINITION_ALIGNMENT",
                    "reviewRole":"PROJECT_SOURCE_DERIVATION_NOT_NEW_VCEP_OR_CLINICAL_APPROVAL"}}
                """);
        var source = mapper.createObjectNode().put("url", "https://example.test/SYNTHETIC/rule")
                .put("sha256", "a".repeat(64)).put("jsonPointer", "/SYNTHETIC/BA1/text");
        rule.putArray("sourceRefs").add(source);
        rule.withObject("/migration").put("reviewSha256", "b".repeat(64)).putArray("sources")
                .add(mapper.createObjectNode().put("url", "https://example.test/SYNTHETIC/guidance").put("sha256", "c".repeat(64)))
                .add(source.deepCopy());
        return rule;
    }

    @Test void completeFiniteRuleIsSupportedWithoutAnAdoptionDecision() throws Exception {
        var rule = clause(mapper);
        assertNull(issue(rule));
        var decision = evaluate(rule, null, null);
        assertEquals(Truth.UNKNOWN, decision.truth());
        var json = mapper.valueToTree(decision);
        for (String field : List.of("evaluationResult", "criterionId", "strength", "score", "clinicalValidationCompleted", "approved"))
            assertFalse(json.has(field));
        assertEquals("a01-ba1-faf-or-individual-count-v1", VERSION);
    }

    @Test void trueFrequencyNeedsNoInventedCountAndFalseFrequencyDoesNotEraseUnknownCount() throws Exception {
        var rule = clause(mapper);
        var met = evaluate(rule, frequency("0.002", "2000", "nfe"), null);
        assertEquals(Truth.TRUE, met.truth()); assertEquals(Truth.TRUE, met.frequencyTruth()); assertEquals(Truth.UNKNOWN, met.countTruth());
        var unresolved = evaluate(rule, frequency("0.001", "2000", "nfe"), null);
        assertEquals(Truth.UNKNOWN, unresolved.truth()); assertEquals(Truth.FALSE, unresolved.frequencyTruth());
        assertEquals("INDIVIDUAL_COUNTS_MISSING", unresolved.countReasonCode());
    }

    @Test void aVerifiedSingleCountCanSatisfyTheRuleWithoutFrequencyOrASum() throws Exception {
        var rule = clause(mapper);
        for (var counts : List.of(counts("10",null,SumRelationship.UNRESOLVED),counts(null,"10",null))) {
            var result = evaluate(rule, null, counts);
            assertEquals(Truth.TRUE,result.truth());assertEquals(Truth.UNKNOWN,result.frequencyTruth());
            assertEquals(Truth.TRUE,result.countTruth());
        }
        assertEquals(Truth.TRUE, evaluate(rule,frequency("0.001","1999","nfe"),counts("10",null,null)).truth(),
                "A frequency-arm AN requirement must not erase an independently qualified count arm");
    }

    @Test void mutuallyExclusiveSameSourceCountsCanSumAndUnknownCountsAreNotZero() throws Exception {
        var rule = clause(mapper);var low = frequency("0.001","2000","nfe");
        assertEquals(Truth.TRUE,evaluate(rule,low,counts("4","6",SumRelationship.SAME_SOURCE_DISJOINT)).truth());
        assertEquals(Truth.FALSE,evaluate(rule,low,counts("4","5",SumRelationship.SAME_SOURCE_DISJOINT),null,CountScopeCompleteness.COMPLETE).truth());
        assertEquals(Truth.UNKNOWN,evaluate(rule,low,counts("4","6",SumRelationship.UNRESOLVED)).truth());
        assertEquals(Truth.UNKNOWN,evaluate(rule,low,counts("4","5",null)).truth());
        assertEquals(Truth.UNKNOWN,evaluate(rule,low,counts("0",null,SumRelationship.SAME_SOURCE_DISJOINT)).truth());
        assertEquals(Truth.FALSE,evaluate(rule,low,counts("0","0",SumRelationship.SAME_SOURCE_DISJOINT),null,CountScopeCompleteness.COMPLETE).truth());
        assertEquals(Truth.UNKNOWN,evaluate(rule,low,counts("-1","10",SumRelationship.SAME_SOURCE_DISJOINT)).truth());
    }

    @Test void frequencyEligibilityAndInvalidFactsRemainUnknownRatherThanFalse() throws Exception {
        var rule = clause(mapper);var zero = counts("0","0",SumRelationship.SAME_SOURCE_DISJOINT);
        for (var fact : List.of(frequency("-0.1","2000","nfe"),frequency("1.1","2000","nfe"),
                frequency("0.002","0","nfe"),frequency("0.002","1999","nfe"),frequency("0.002","2000","ami"),
                frequency("0.002","2000","XY"),new FrequencyFact(null,new BigInteger("2000"),"nfe"),
                new FrequencyFact(new BigDecimal("0.002"),null,"nfe"),new FrequencyFact(new BigDecimal("0.002"),new BigInteger("2000"),null))) {
            var result = evaluate(rule,fact,zero);assertEquals(Truth.UNKNOWN,result.truth());assertEquals(Truth.UNKNOWN,result.frequencyTruth());
        }
        assertEquals(Truth.FALSE,evaluate(rule,frequency("0","2000","nfe"),zero,null,CountScopeCompleteness.COMPLETE).truth(),
                "A known zero supplied after original HIT verification differs from missing/NO_RECORD; caller must never invent it");
    }

    @Test void sourceOperatorsAndExactNumericBoundariesArePreserved() throws Exception {
        var rule = clause(mapper);var zero = counts("0","0",SumRelationship.SAME_SOURCE_DISJOINT);
        assertEquals(Truth.FALSE,evaluate(rule,frequency("0.0019999999999999999999999999999999999","2000","nfe"),zero,null,CountScopeCompleteness.COMPLETE).truth());
        assertEquals(Truth.TRUE,evaluate(rule,frequency("0.002","2000","nfe"),zero,null,CountScopeCompleteness.COMPLETE).truth());
        rule.put("originalText",rule.path("originalText").asText().replace("≥0.002",">0.002"));
        rule.withObject("/expression/frequency").put("comparator","GREATER_THAN");assertNull(issue(rule));
        assertEquals(Truth.FALSE,evaluate(rule,frequency("0.002","2000","nfe"),zero,null,CountScopeCompleteness.COMPLETE).truth());
        assertEquals(Truth.TRUE,evaluate(rule,frequency("0.0020000000000000000000000000000000001","2000","nfe"),zero).truth());
        rule.put("originalText",rule.path("originalText").asText().replace(">0.002 (0.2%)",">0.004 (0.4%)").replace("≥10", "≥12"));
        rule.withObject("/expression/frequency").put("value","0.004");rule.withObject("/expression/individualCount").put("value","12");
        assertNull(issue(rule));assertEquals(Truth.FALSE,evaluate(rule,frequency("0.003","2000","nfe"),counts("11","0",SumRelationship.SAME_SOURCE_DISJOINT),null,CountScopeCompleteness.COMPLETE).truth());
        assertEquals(Truth.TRUE,evaluate(rule,null,counts("12",null,null)).truth());
    }

    @ParameterizedTest @ValueSource(strings={"root-extra","frequency-extra","count-extra","migration-extra","source-extra",
            "operator-all","source-sha","source-url","migration-review","migration-basis","migration-role",
            "guidance-missing","rule-source-missing","source-empty","minimum-an-low","unit-alleles","count-zero",
            "count-number","frequency-number","ancestry-founder","ancestry-subset","ancestry-duplicate",
            "conditions-unknown","conditions-duplicate","conditions-nonarray","population-scope","sum-unchecked",
            "count-one-class","count-duplicate","original-extra","original-comparator","original-count","original-percent","legacy-scalar"})
    void unsupportedOrTamperedContractsCannotFallBackToScalarOrScore(String fault) throws Exception {
        var rule=clause(mapper);
        switch(fault) {
            case "root-extra" -> rule.put("adoptionApproved",true);
            case "frequency-extra" -> rule.withObject("/expression/frequency").put("choosePatientSex",true);
            case "count-extra" -> rule.withObject("/expression/individualCount").put("inferHemiFromAcXY",true);
            case "migration-extra" -> rule.withObject("/migration").put("clinicalApproval",true);
            case "source-extra" -> ((ObjectNode)rule.at("/sourceRefs/0")).put("ignoreUnknown",true);
            case "operator-all" -> rule.withObject("/expression").put("operator","ALL");
            case "source-sha" -> ((ObjectNode)rule.at("/sourceRefs/0")).put("sha256","changed");
            case "source-url" -> ((ObjectNode)rule.at("/sourceRefs/0")).put("url","http://example.test/rule");
            case "migration-review" -> rule.withObject("/migration").put("reviewSha256","changed");
            case "migration-basis" -> rule.withObject("/migration").put("basis","AC_XY_TO_PERSON_COUNT");
            case "migration-role" -> rule.withObject("/migration").put("reviewRole","CLINICALLY_APPROVED");
            case "guidance-missing" -> rule.withObject("/migration").putArray("sources").add(rule.at("/sourceRefs/0").deepCopy());
            case "rule-source-missing" -> rule.withObject("/migration").withArray("/sources").remove(1);
            case "source-empty" -> rule.putArray("sourceRefs");
            case "minimum-an-low" -> rule.withObject("/expression/frequency").put("minimumAn","1");
            case "unit-alleles" -> rule.withObject("/expression/individualCount").put("unit","alleles");
            case "count-zero" -> rule.withObject("/expression/individualCount").put("value","0");
            case "count-number" -> rule.withObject("/expression/individualCount").put("value",10);
            case "frequency-number" -> rule.withObject("/expression/frequency").put("value",0.002);
            case "ancestry-founder" -> rule.withObject("/expression/frequency").withArray("/ancestries").add("ami");
            case "ancestry-subset" -> rule.withObject("/expression/frequency").putArray("ancestries").add("nfe");
            case "ancestry-duplicate" -> rule.withObject("/expression/frequency").withArray("/ancestries").add("nfe");
            case "conditions-unknown" -> rule.putArray("conditions").add("IN_ADULTS_ONLY");
            case "conditions-duplicate" -> rule.putArray("conditions").add("GNOMAD_FILTERED_VARIANTS_EXCLUDED").add("GNOMAD_FILTERED_VARIANTS_EXCLUDED");
            case "conditions-nonarray" -> rule.put("conditions","NONE");
            case "population-scope" -> rule.withObject("/expression/individualCount").put("populationScope","EXOMES_PLUS_GENOMES");
            case "sum-unchecked" -> rule.withObject("/expression/individualCount").put("sumOnlyIfMutuallyExclusive",false);
            case "count-one-class" -> rule.withObject("/expression/individualCount").putArray("counts").add("HOMOZYGOUS");
            case "count-duplicate" -> rule.withObject("/expression/individualCount").withArray("/counts").add("HOMOZYGOUS");
            case "original-extra" -> rule.put("originalText",rule.path("originalText").asText()+" Except founder variants.");
            case "original-comparator" -> rule.put("originalText",rule.path("originalText").asText().replace("≥0.002", ">0.002"));
            case "original-count" -> rule.put("originalText",rule.path("originalText").asText().replace("≥10", "≥11"));
            case "original-percent" -> rule.put("originalText",rule.path("originalText").asText().replace("(0.2%)", "(0.02%)"));
            case "legacy-scalar" -> rule.put("disposition","THRESHOLD");
            default -> throw new AssertionError(fault);
        }
        assertNotNull(issue(rule),fault);
        assertEquals(Truth.UNKNOWN,evaluate(rule,frequency("0.99","9000","nfe"),counts("99","99",SumRelationship.SAME_SOURCE_DISJOINT)).truth());
    }

    @Test void aVerifiedTotalUpperBoundCanProveFalseWithoutInventingAnExactMissingClass() throws Exception {
        var rule=clause(mapper);var low=frequency("0.00000974000","246676","nfe");
        var incomplete=counts("0",null,SumRelationship.UNRESOLVED);
        var without=evaluate(rule,low,incomplete);
        assertEquals(Truth.UNKNOWN,without.truth());assertEquals(Truth.UNKNOWN,without.countTruth());
        var bounded=evaluate(rule,low,incomplete,new BigInteger("6"));
        assertEquals(Truth.FALSE,bounded.truth());assertEquals(Truth.FALSE,bounded.countTruth());
        assertEquals("INDIVIDUAL_COUNT_TOTAL_UPPER_BOUND_BELOW_THRESHOLD",bounded.countReasonCode());
        assertNull(incomplete.hemizygotes()); // Exact hemizygote count remains unknown, never silently set to zero.
        assertEquals(Truth.FALSE,evaluate(rule,low,null,new BigInteger("6")).truth());
        assertEquals(Truth.TRUE,evaluate(rule,frequency("0.002","2000","nfe"),null,new BigInteger("6")).truth());
    }

    @Test void upperBoundBelowAndAtThresholdHaveDifferentMeaningsAndNeverProveTrue() throws Exception {
        var rule=clause(mapper);var low=frequency("0.001","2000","nfe");
        for(String bound:List.of("0","1","9"))
            assertEquals(Truth.FALSE,evaluate(rule,low,null,new BigInteger(bound)).countTruth());
        for(String bound:List.of("10","11","999999999999999999999999999999999999999")) {
            var result=evaluate(rule,low,null,new BigInteger(bound));
            assertEquals(Truth.UNKNOWN,result.truth());assertEquals(Truth.UNKNOWN,result.countTruth());
        }
        assertEquals(Truth.UNKNOWN,evaluate(rule,null,null,new BigInteger("9")).truth());
        assertEquals(Truth.FALSE,evaluate(rule,null,null,new BigInteger("9")).countTruth());
    }

    @Test void negativeOrContradictoryBoundsRemainUnknownConflictsEvenWhenAnIndividualClassMeetsThreshold() throws Exception {
        var rule=clause(mapper);var low=frequency("0.001","2000","nfe");
        record Case(IndividualCounts counts,BigInteger bound) { }
        for(var input:List.of(new Case(null,new BigInteger("-1")),
                new Case(counts("7",null,null),new BigInteger("6")),
                new Case(counts(null,"7",null),new BigInteger("6")),
                new Case(counts("4","3",SumRelationship.SAME_SOURCE_DISJOINT),new BigInteger("6")),
                new Case(counts("10",null,null),new BigInteger("9")),
                new Case(counts("-1",null,null),new BigInteger("6")),
                new Case(counts(null,"-1",null),new BigInteger("6")))) {
            var result=evaluate(rule,low,input.counts(),input.bound());
            assertEquals(Truth.UNKNOWN,result.truth());assertEquals(Truth.UNKNOWN,result.countTruth());
            assertEquals("INDIVIDUAL_COUNT_UPPER_BOUND_CONFLICT",result.countReasonCode());
        }
    }

    @Test void theBoundChecksOnlyAnActuallyVerifiedSumAndDoesNotInventItsRelationship() throws Exception {
        var rule=clause(mapper);var low=frequency("0.001","2000","nfe");
        var relationUnknown=counts("5","5",SumRelationship.UNRESOLVED);
        assertEquals(Truth.FALSE,evaluate(rule,low,relationUnknown,new BigInteger("6")).countTruth(),
                "A verified total bound can rule out ten distinct individuals without summing unqualified classes");
        assertEquals(Truth.UNKNOWN,evaluate(rule,low,relationUnknown,new BigInteger("10")).countTruth());
        assertEquals(Truth.TRUE,evaluate(rule,low,counts("10",null,null),new BigInteger("10")).countTruth());
        assertEquals(Truth.TRUE,evaluate(rule,low,counts("5","5",SumRelationship.SAME_SOURCE_DISJOINT),new BigInteger("10")).countTruth());
    }

    @Test void threeArgumentAndExplicitNullBoundAreIdenticalAndAllFactsRemainUnchanged() throws Exception {
        var rule=clause(mapper);byte[] before=mapper.writeValueAsBytes(rule);
        var low=frequency("0.001","2000","nfe");
        for(var input:List.of(counts("0",null,null),counts("-1","10",null),
                counts("4","6",SumRelationship.SAME_SOURCE_DISJOINT),counts("4","5",SumRelationship.UNRESOLVED)))
            assertEquals(evaluate(rule,low,input),evaluate(rule,low,input,null));
        assertEquals(evaluate(rule,null,null),evaluate(rule,null,null,null));
        var original=counts("4",null,null);var bound=new BigInteger("6");
        evaluate(rule,low,original,bound);
        assertEquals(new BigInteger("4"),original.homozygotes());assertNull(original.hemizygotes());
        assertEquals(new BigInteger("6"),bound);assertArrayEquals(before,mapper.writeValueAsBytes(rule));
    }

    @Test void aDisjointSubsetCannotProveTheFullPopulationBelowThreshold() throws Exception {
        var rule=clause(mapper);var low=frequency("0.001","2000","nfe");
        for(var subset:List.of(counts("4","5",SumRelationship.SAME_SOURCE_DISJOINT),
                counts("0","0",SumRelationship.SAME_SOURCE_DISJOINT))) {
            for(var result:List.of(evaluate(rule,low,subset),evaluate(rule,low,subset,null),
                    evaluate(rule,low,subset,null,CountScopeCompleteness.UNVERIFIED_OR_PARTIAL),
                    evaluate(rule,low,subset,null,null))) {
                assertEquals(Truth.UNKNOWN,result.truth());assertEquals(Truth.UNKNOWN,result.countTruth());
                assertEquals("INDIVIDUAL_COUNT_COVERAGE_INCOMPLETE",result.countReasonCode());
            }
            assertEquals(Truth.FALSE,evaluate(rule,low,subset,null,CountScopeCompleteness.COMPLETE).truth());
        }
    }

    @Test void completenessCannotSupplyAClassOrQualifyAnOverlappingSum() throws Exception {
        var rule=clause(mapper);var low=frequency("0.001","2000","nfe");
        for(var counts:List.of(counts("4","6",SumRelationship.UNRESOLVED),
                counts("0",null,SumRelationship.SAME_SOURCE_DISJOINT)))
            assertEquals(Truth.UNKNOWN,evaluate(rule,low,counts,null,CountScopeCompleteness.COMPLETE).countTruth());
    }

    @Test void qualifiedSubsetLowerBoundsRemainSufficientWithoutCompleteness() throws Exception {
        var rule=clause(mapper);var low=frequency("0.001","2000","nfe");
        for(var subset:List.of(counts("4","6",SumRelationship.SAME_SOURCE_DISJOINT),
                counts("10",null,null),counts(null,"10",null))) {
            assertEquals(Truth.TRUE,evaluate(rule,low,subset).truth());
            assertEquals(Truth.TRUE,evaluate(rule,null,subset,null,CountScopeCompleteness.UNVERIFIED_OR_PARTIAL).truth());
        }
    }

    @Test void fullUpperBoundAndExactCountCompletenessAreIndependent() throws Exception {
        var rule=clause(mapper);var low=frequency("0.001","2000","nfe");
        var subset=counts("4","5",SumRelationship.SAME_SOURCE_DISJOINT);
        assertEquals(Truth.UNKNOWN,evaluate(rule,low,subset,new BigInteger("10")).countTruth());
        assertEquals(Truth.FALSE,evaluate(rule,low,subset,new BigInteger("9")).countTruth());
        assertEquals(Truth.FALSE,evaluate(rule,low,subset,new BigInteger("10"),CountScopeCompleteness.COMPLETE).countTruth());
        var conflict=evaluate(rule,low,counts("6","5",SumRelationship.SAME_SOURCE_DISJOINT),new BigInteger("10"),
                CountScopeCompleteness.COMPLETE);
        assertEquals(Truth.UNKNOWN,conflict.countTruth());
        assertEquals("INDIVIDUAL_COUNT_UPPER_BOUND_CONFLICT",conflict.countReasonCode());
    }

    @Test void representationsAndFactsAreReadOnlyAndDiagnosticExamplesCanBeExported() throws Exception {
        var rule=clause(mapper);byte[] before=mapper.writeValueAsBytes(rule);
        var examples=mapper.createArrayNode();
        for (var entry:List.of(
                evaluate(rule,frequency("0.002","2000","nfe"),null),
                evaluate(rule,frequency("0.001","2000","nfe"),null),
                evaluate(rule,null,counts("10",null,SumRelationship.UNRESOLVED)),
                evaluate(rule,frequency("0.001","2000","nfe"),counts("4","6",SumRelationship.SAME_SOURCE_DISJOINT)),
                evaluate(rule,frequency("0.001","2000","nfe"),counts("4","6",SumRelationship.UNRESOLVED)),
                evaluate(rule,frequency("0.001","2000","nfe"),counts("4","5",SumRelationship.SAME_SOURCE_DISJOINT)),
                evaluate(rule,frequency("0.001","2000","nfe"),counts("4","5",SumRelationship.SAME_SOURCE_DISJOINT),null,CountScopeCompleteness.COMPLETE),
                evaluate(rule,frequency("0.001","2000","nfe"),counts("0",null,SumRelationship.UNRESOLVED),new BigInteger("6")),
                evaluate(rule,frequency("0.001","2000","nfe"),null,new BigInteger("10")),
                evaluate(rule,frequency("0.001","2000","nfe"),counts("4","3",SumRelationship.SAME_SOURCE_DISJOINT),new BigInteger("6")))) examples.add(mapper.valueToTree(entry));
        assertArrayEquals(before,mapper.writeValueAsBytes(rule));
        String output=System.getProperty("varvis.t03.ba1.conditional.output");
        if(output!=null && !output.isBlank()) {
            Path path=Path.of(output);Files.createDirectories(path);
            var sample=mapper.createObjectNode().put("layer","SYNTHETIC_RULE_MECHANICS_NOT_ACMG_ADOPTION")
                    .put("sourceQualityAndExceptions","CALLER_MUST_VERIFY_NOT_TESTED_AS_APPROVED_HERE");
            sample.set("clause",rule);sample.set("decisions",examples);
            Files.write(path.resolve("ba1-conditional-direct-decisions.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample));
        }
    }

    private static FrequencyFact frequency(String faf,String an,String ancestry) {
        return new FrequencyFact(new BigDecimal(faf),new BigInteger(an),ancestry);
    }
    private static IndividualCounts counts(String homo,String hemi,SumRelationship relationship) {
        return new IndividualCounts(homo==null?null:new BigInteger(homo),hemi==null?null:new BigInteger(hemi),relationship);
    }
}
