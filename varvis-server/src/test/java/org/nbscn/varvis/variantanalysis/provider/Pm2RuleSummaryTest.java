package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pm2RuleSummaryTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void absenceDisplaysSavedAnThresholdAndRegionConditionsNeverInventsZeroFrequency()throws Exception{
        var saved=(ObjectNode)mapper.readTree("""
            {"policySelection":{"policy":{"policyId":"PROJECT_LOCAL_EXOMES_ABSENCE_V1","version":"1.0.0","route":"GENERAL_ABSENCE_ONLY",
            "allSitesCallability":{"exomes":{"minimumAdjAlleleNumber":{"rawValue":"2000","comparisonOperator":"GREATER_THAN"},
            "excludedWhenAnyFlagsTrue":["outside_broad_capture_region","outside_ukb_capture_region"]}}}},
            "observation":{"carrier":{"queryOutcome":{"status":"NO_RECORD"}}},
            "allSitesObservation":{"status":"HIT","adjAn":"1461824","regionFlags":{"outside_broad_capture_region":false,"outside_ukb_capture_region":false}}}
            """);
        String before=saved.toString();var display=Pm2RuleSummary.build(mapper,saved);
        assertEquals("ABSENCE",display.path("branch").asText());assertEquals(5,display.path("conditions").size());
        assertEquals("AN > 2000",display.at("/conditions/2/requirement").asText());
        assertEquals("1461824",display.at("/conditions/2/actual").asText());
        assertEquals("位于区域内",display.at("/conditions/3/actual").asText());
        assertFalse(display.toString().contains("AF = 0"));assertEquals(before,saved.toString());
        ((ObjectNode)saved.at("/policySelection/policy/allSitesCallability/exomes/minimumAdjAlleleNumber")).put("rawValue","10000");
        assertEquals("AN > 10000",Pm2RuleSummary.build(mapper,saved).at("/conditions/2/requirement").asText());
    }
    @Test void observedRarityUsesSavedMetricOperatorAndThreshold()throws Exception{
        var saved=mapper.readTree("""
            {"policySelection":{"policy":{"maximumAlleleFrequency":{"metricId":"gnomad.exomes.faf95max","rawValue":"0.00001","comparisonOperator":"LESS_THAN_OR_EQUAL"}}},
            "observation":{"carrier":{"queryOutcome":{"status":"HIT"}}},
            "evaluation":{"deterministicTrace":{"parameters":[{"name":"frequency.raw","value":"0.000001"}],
            "steps":[{"stepCode":"FREQUENCY_WITHIN_PM2_MAXIMUM","outcome":"TRUE"}]}}}
            """);
        var display=Pm2RuleSummary.build(mapper,saved);
        assertEquals("FAF95 ≤ 0.00001",display.at("/conditions/0/requirement").asText());
        assertEquals("0.000001",display.at("/conditions/0/actual").asText());assertEquals("TRUE",display.at("/conditions/0/recordedOutcome").asText());
    }
    @Test void absentPolicyDoesNotBorrowCurrentDefaults(){
        assertFalse(Pm2RuleSummary.build(mapper,mapper.createObjectNode()).has("conditions"));
    }
    @Test void specialistRuleUsesTheSavedVersionedAuthorityUrl() {
        String url = "https://cspec.genome.network/cspec/ui/svi/doc/GN147?version=2.0";
        var saved = mapper.createObjectNode();
        var policy = saved.putObject("policySelection").putObject("policy").put("version", "1.0.0");
        policy.putObject("authority").put("sourceUrl", url);
        var expected = mapper.createArrayNode().add(url);
        assertEquals(expected, Pm2RuleSummary.build(mapper, saved).path("sourceUrls"));
        policy.putArray("sourceUrls").add(url);
        assertEquals(expected, Pm2RuleSummary.build(mapper, saved).path("sourceUrls"));
        policy.remove("authority");
        assertEquals(expected, Pm2RuleSummary.build(mapper, saved).path("sourceUrls"));
    }
    @Test void verifiedSingletonShowsActuallyExecutedCountsInsteadOfUnexecutedFafThreshold()throws Exception{
        var saved=mapper.readTree("""
            {"policySelection":{"policy":{"maximumAlleleFrequency":{"metricId":"gnomad.exomes.faf95max","rawValue":"0.00001","comparisonOperator":"LESS_THAN"},
            "missingFafPolicy":{"mode":"VERIFIED_SINGLETON"}}},"observation":{"carrier":{"queryOutcome":{"status":"HIT"}}},
            "evaluation":{"deterministicTrace":{"parameters":[{"name":"population.observedAc","value":"1"},{"name":"population.observedAn","value":"10000"},
            {"name":"population.maximumAc","value":"1"},{"name":"population.minimumAn","value":"1"}],
            "steps":[{"stepCode":"PM2_ALLELE_COUNT_LIMIT_SATISFIED","outcome":"TRUE"}]}}}
            """);
        var display=Pm2RuleSummary.build(mapper,saved);assertEquals(2,display.path("conditions").size());
        assertEquals("AC ≤ 1",display.at("/conditions/0/requirement").asText());assertEquals("1",display.at("/conditions/0/actual").asText());
        assertEquals("AN ≥ 1",display.at("/conditions/1/requirement").asText());assertFalse(display.toString().contains("FAF95"));
        assertEquals("TRUE", display.at("/conditions/1/recordedOutcome").asText());
    }
    @Test void recordedThresholdWinsButConflictingRuleValuesRemainVisible()throws Exception{
        var saved=mapper.readTree("""
            {"policySelection":{"policy":{"maximumAlleleFrequency":{"metricId":"gnomad.exomes.faf95max","rawValue":"0.001","comparisonOperator":"LESS_THAN"}}},
            "observation":{"carrier":{"queryOutcome":{"status":"HIT"}}},"evaluation":{"deterministicTrace":{"parameters":[
            {"name":"threshold.raw","value":"0.0001"},{"name":"comparison.operator","value":"LESS_THAN_OR_EQUAL"},{"name":"frequency.raw","value":"0.00001"}]}}}
            """);
        var display=Pm2RuleSummary.build(mapper,saved);
        assertEquals("FAF95 ≤ 0.0001",display.at("/conditions/0/requirement").asText());
        assertEquals("频率阈值资料冲突",display.at("/conditions/1/label").asText());
        assertEquals("规则记录：< 0.001",display.at("/conditions/1/requirement").asText());
    }
    @Test void equivalentNumericNotationsDoNotCreateFalseConflict()throws Exception{
        var saved=mapper.readTree("""
            {"policySelection":{"policy":{"maximumAlleleFrequency":{"metricId":"gnomad.exomes.af","rawValue":"0.0001","comparisonOperator":"LESS_THAN"}}},
            "observation":{"carrier":{"queryOutcome":{"status":"HIT"}}},"evaluation":{"deterministicTrace":{"parameters":[
            {"name":"threshold.raw","value":"1e-4"},{"name":"comparison.operator","value":"LESS_THAN"},{"name":"frequency.raw","value":"0.00001"}]}}}
            """);
        assertEquals(1,Pm2RuleSummary.build(mapper,saved).path("conditions").size());
    }
    @Test void unavailableQueryIsNotDescribedAsAnObservedAllele()throws Exception{
        var saved=mapper.readTree("{\"policySelection\":{\"policy\":{}},\"observation\":{\"carrier\":{\"queryOutcome\":{\"status\":\"UNAVAILABLE\"}}}}");
        assertEquals("UNRESOLVED",Pm2RuleSummary.build(mapper,saved).path("branch").asText());
    }
}
