package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;

/** Display the saved policy and recorded inputs; never evaluate PM2 or load today's configuration. */
public final class Pm2RuleSummary {
    private Pm2RuleSummary() { }
    public static ObjectNode build(ObjectMapper mapper,JsonNode result) {
        var out=mapper.createObjectNode().put("schemaVersion","pm2-rule-display-v1");
        var policy=result.at("/policySelection/policy");
        if(!policy.isObject())return out;
        String queryStatus=result.at("/observation/carrier/queryOutcome/status").asText();
        boolean absence="NO_RECORD".equals(queryStatus);
        out.put("branch",absence?"ABSENCE":"HIT".equals(queryStatus)?"OBSERVED":"UNRESOLVED").put("policyId",policy.path("policyId").asText())
                .put("policyVersion",policy.path("version").asText()).put("route",policy.path("route").asText());
        var sourceUrls = new LinkedHashSet<String>();
        for (var url : policy.path("sourceUrls"))
            if (url.isTextual() && url.asText().startsWith("https://")) sourceUrls.add(url.asText());
        var authorityUrl = policy.at("/authority/sourceUrl");
        if (authorityUrl.isTextual() && authorityUrl.asText().startsWith("https://")) sourceUrls.add(authorityUrl.asText());
        var urls = out.putArray("sourceUrls");
        sourceUrls.forEach(urls::add);
        var conditions=out.putArray("conditions");
        var parameters=result.at("/evaluation/deterministicTrace/parameters");
        if(!absence&&!"HIT".equals(queryStatus)){
            row(conditions,"精确变异查询","取得有效的精确变异查询结果",queryStatus,"",result);return out;
        }
        if(absence){
            row(conditions,"精确变异查询","未检出当前精确变异",queryStatus,"LOCAL_EXOMES_EXACT_ALLELE_ABSENT",result)
                    .put("recordedOutcome","TRUE");
            String detection=result.at("/allSitesObservation/status").asText();
            row(conditions,"位点检测记录","该位置有检测记录",detection,"",result)
                    .put("recordedOutcome",switch(detection){case "HIT"->"TRUE";case "NO_RECORD"->"FALSE";default->"UNKNOWN";});
            var rule=policy.at("/allSitesCallability/exomes");
            var minimum=rule.path("minimumAdjAlleleNumber");
            String threshold=recordedOrPolicy(parameters,"allSites.minimumAn",minimum.path("rawValue").asText());
            var comparator=operator(recordedOrPolicy(parameters,"allSites.comparisonOperator",minimum.path("comparisonOperator").asText()));
            if(!comparator.isEmpty()&&decimal(threshold)){
                String actual=result.at("/allSitesObservation/adjAn").asText();
                var condition=row(conditions,"检测等位基因数 AN","AN "+comparator+" "+threshold,actual,"",result);
                condition.put("recordedOutcome","HIT".equals(detection)?compare(actual,comparator,threshold):"UNKNOWN");
                mismatch(conditions,"检测量阈值",operator(minimum.path("comparisonOperator").asText())+" "+minimum.path("rawValue").asText(),comparator+" "+threshold);
            }
            for(var flag:rule.path("excludedWhenAnyFlagsTrue")){
                String label=switch(flag.asText()){
                    case "outside_broad_capture_region"->"Broad 捕获区域";
                    case "outside_ukb_capture_region"->"UKB 捕获区域";
                    case "outside_broad_calling_region"->"Broad 变异检测区域";
                    case "outside_ukb_calling_region"->"UKB 变异检测区域";
                    default->"";
                };
                var actual=result.path("allSitesObservation").path("regionFlags").path(flag.asText());
                if(!label.isEmpty())row(conditions,label,"位于区域内",actual.isBoolean()?(actual.asBoolean()?"位于区域外":"位于区域内"):"未保存","",result)
                        .put("recordedOutcome",!"HIT".equals(detection)||!actual.isBoolean()?"UNKNOWN":actual.asBoolean()?"FALSE":"TRUE");
            }
        }else{
            var maximum=policy.path("maximumAlleleFrequency");
            // A recorded singleton/count fallback is an AC/AN assessment, not an
            // executed FAF comparison. Show its saved parameters without new scoring.
            boolean countBranch=policy.has("maximumAlleleCount")||hasStep(result,"PM2_ALLELE_COUNT_LIMIT_SATISFIED");
            if(countBranch){
                String count=recordedOrPolicy(parameters,"population.maximumAc",policy.path("maximumAlleleCount").asText());
                String number=recordedOrPolicy(parameters,"population.minimumAn",policy.path("minimumObservedAlleleNumber").asText());
                if(decimal(count))row(conditions,"变异等位基因数 AC","AC ≤ "+count,parameter(parameters,"population.observedAc"),"PM2_ALLELE_COUNT_LIMIT_SATISFIED",result);
                if (decimal(number)) {
                    String actual = parameter(parameters, "population.observedAn");
                    var condition = row(conditions, "检测等位基因数 AN", "AN ≥ " + number, actual, "", result);
                    if (decimal(actual)) condition.put("recordedOutcome", compare(actual, "≥", number));
                }
            }else{
                String threshold=recordedOrPolicy(parameters,"threshold.raw",maximum.path("rawValue").asText());
                var comparator=operator(recordedOrPolicy(parameters,"comparison.operator",maximum.path("comparisonOperator").asText()));
                if(decimal(threshold)&&!comparator.isEmpty()){
                    String metric=recordedOrPolicy(parameters,"frequency.metricId",maximum.path("metricId").asText()).contains("faf95")?"FAF95":"AF";
                    row(conditions,metric+" 频率上限",metric+" "+comparator+" "+threshold,parameter(parameters,"frequency.raw"),"FREQUENCY_WITHIN_PM2_MAXIMUM",result);
                    mismatch(conditions,"频率阈值",operator(maximum.path("comparisonOperator").asText())+" "+maximum.path("rawValue").asText(),comparator+" "+threshold);
                }
            }
            if(!countBranch&&decimal(policy.path("maximumHomozygoteCount").asText()))
                row(conditions,"纯合人数","人数 ≤ "+policy.path("maximumHomozygoteCount").asText(),parameter(parameters,"population.homozygotes"),"PM2_HOMOZYGOTE_LIMIT_SATISFIED",result);
        }
        return out;
    }
    private static boolean decimal(String value){return value.matches("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");}
    private static String compare(String actual,String comparator,String threshold){
        if(actual.length()>80||threshold.length()>80||!decimal(actual)||!decimal(threshold))return "UNKNOWN";
        try{
            int difference=new java.math.BigDecimal(actual).compareTo(new java.math.BigDecimal(threshold));
            boolean meets=switch(comparator){case ">"->difference>0;case "≥"->difference>=0;case "<"->difference<0;case "≤"->difference<=0;default->false;};
            return meets?"TRUE":"FALSE";
        }catch(NumberFormatException invalid){return "UNKNOWN";}
    }
    private static String recordedOrPolicy(JsonNode parameters,String name,String fallback){
        for(var parameter:parameters)if(name.equals(parameter.path("name").asText()))return parameter(parameters,name);
        return fallback;
    }
    private static boolean hasStep(JsonNode result,String code){
        for(var step:result.at("/evaluation/deterministicTrace/steps"))if(code.equals(step.path("stepCode").asText()))return true;
        return false;
    }
    private static void mismatch(ArrayNode rows,String label,String declared,String executed){
        if(declared.isBlank()||declared.equals(executed))return;
        var left=declared.split(" ",-1);var right=executed.split(" ",-1);
        if(left.length==2&&right.length==2&&left[0].equals(right[0])&&decimal(left[1])&&decimal(right[1])
                &&new java.math.BigDecimal(left[1]).compareTo(new java.math.BigDecimal(right[1]))==0)return;
        rows.addObject().put("label",label+"资料冲突")
                .put("requirement","规则记录："+declared).put("actual","执行轨迹："+executed);
    }
    private static String operator(String token){return switch(token){case "GREATER_THAN"->">";case "GREATER_THAN_OR_EQUAL"->"≥";case "LESS_THAN"->"<";case "LESS_THAN_OR_EQUAL"->"≤";default->"";};}
    private static String parameter(JsonNode parameters,String name){
        String found="";int count=0;
        for(var parameter:parameters)if(name.equals(parameter.path("name").asText())){found=parameter.path("value").asText();count++;}
        return count==1?found:"";
    }
    private static ObjectNode row(ArrayNode rows,String label,String requirement,String actual,String step,JsonNode result){
        var row=rows.addObject().put("label",label).put("requirement",requirement).put("actual",actual.isBlank()?"未保存":actual);
        for(var recorded:result.at("/evaluation/deterministicTrace/steps"))if(!step.isEmpty()&&step.equals(recorded.path("stepCode").asText()))
            row.put("recordedOutcome",recorded.path("outcome").asText());
        return row;
    }
}
