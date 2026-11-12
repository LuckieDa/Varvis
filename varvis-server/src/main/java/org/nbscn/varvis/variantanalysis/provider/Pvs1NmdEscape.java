package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Explicit source-bound non-NMD decisions; no gene names, default domains or inferred population facts. */
final class Pvs1NmdEscape {
    static final String SCHEMA = "pvs1-truncating-nmd-policy-v3";
    static final String VERSION = "1.2.0";
    private Pvs1NmdEscape() { }
    private static boolean supports(JsonNode policy) {
        return SCHEMA.equals(policy.path("schemaVersion").asText())
                || Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText());
    }

    static String policyIssue(JsonNode policy, JsonNode model) {
        var scope=policy.path("escapeScope");
        if (Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText()) && scope.isMissingNode()) return null;
        if (!supports(policy))
            return scope.isMissingNode()?null:"PVS1_ESCAPE_POLICY_INVALID";
        if (!scope.isObject() || !"REVIEWED_FOR_PVS1_ESCAPE".equals(scope.path("status").asText())
                || !text(scope.path("reviewId"))) return "PVS1_ESCAPE_SCOPE_NOT_REVIEWED";
        if (!only(scope,Set.of("status","reviewId","basis","sourceIds","exonAssessments","rules","positionConditions")) || !sourced(scope,policy)
                || scope.has("positionConditions") && !Pvs1NmdConditions.validConditions(policy,scope.path("positionConditions")))
            return "PVS1_ESCAPE_POLICY_INVALID";
        var exons=scope.path("exonAssessments");var ordinals=new HashSet<Integer>();
        if (!exons.isArray() || exons.size()>model.path("exons").size()) return "PVS1_ESCAPE_POLICY_INVALID";
        for(var e:exons) if (!only(e,Set.of("ordinal","biologicalRelevance","populationLof","basis","sourceIds"))
                || !positive(e.path("ordinal")) || e.path("ordinal").asInt()>model.path("exons").size()
                || !ordinals.add(e.path("ordinal").asInt()) || !sourced(e,policy)
                || !Set.of("PRESENT","ABSENT","UNKNOWN").contains(e.path("biologicalRelevance").asText())
                || !Set.of("NOT_FREQUENT","FREQUENT","UNKNOWN").contains(e.path("populationLof").asText()))
            return "PVS1_ESCAPE_POLICY_INVALID";
        var rules=scope.path("rules");var ids=new HashSet<String>();
        if (!rules.isArray() || rules.isEmpty() || rules.size()>32) return "PVS1_ESCAPE_POLICY_INVALID";
        for(var r:rules) {
            if (!text(r.path("ruleId")) || !ids.add(r.path("ruleId").asText()) || !sourced(r,policy)
                    || !Set.of("VERY_STRONG","STRONG","MODERATE","SUPPORTING").contains(r.path("strength").asText()))
                return "PVS1_ESCAPE_POLICY_INVALID";
            if ("CRITICAL_REGION".equals(r.path("predicate").asText())) {
                if (!only(r,Set.of("ruleId","predicate","start","end","minimumAffectedResidues","strength","basis","sourceIds"))
                        || !positive(r.path("start")) || !positive(r.path("end"))
                        || r.path("end").asInt()<r.path("start").asInt() || r.path("end").asInt()>model.path("proteinLength").asInt()
                        || !positive(r.path("minimumAffectedResidues"))
                        || r.path("minimumAffectedResidues").asInt()>r.path("end").asInt()-r.path("start").asInt()+1)
                    return "PVS1_ESCAPE_POLICY_INVALID";
            } else if ("PROTEIN_LOSS_FRACTION".equals(r.path("predicate").asText())) {
                if (!only(r,Set.of("ruleId","predicate","metric","comparison","numerator","denominator","strength","basis","sourceIds"))
                        || !Set.of("REFERENCE_TAIL_ALTERED_OR_REMOVED","NET_PROTEIN_SHORTENING").contains(r.path("metric").asText())
                        || !Set.of("GT","LT").contains(r.path("comparison").asText())
                        || !positive(r.path("numerator")) || !positive(r.path("denominator"))
                        || r.path("numerator").asInt()>=r.path("denominator").asInt()
                        || "VERY_STRONG".equals(r.path("strength").asText())) return "PVS1_ESCAPE_POLICY_INVALID";
            } else return "PVS1_ESCAPE_POLICY_INVALID";
        }
        return null;
    }

    static String evaluate(ObjectNode policy, JsonNode model, ObjectNode input, JsonNode selection) {
        var scope=policy.path("escapeScope");
        int ptcExon=input.has("ptcExonOrdinal")?input.path("ptcExonOrdinal").asInt():input.path("exonOrdinal").asInt();
        int exonTotal=model.path("exons").size();
        // Source assessments below keep reference ordinals. Only this mechanical
        // NMD check uses altered ordinals after a qualified splice reconstruction.
        if (input.has("alteredExonTotal")) {
            var count=input.path("alteredExonTotal");var ordinal=input.path("ptcAlteredExonOrdinal");
            if (!positive(count) || !positive(ordinal) || ordinal.asInt()>count.asInt()
                    || !"SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE".equals(input.path("sequenceDerivationStatus").asText()))
                return "PVS1_ESCAPE_ALTERED_TOPOLOGY_UNRESOLVED";
            ptcExon=ordinal.asInt();exonTotal=count.asInt();
            if (exonTotal==1 && (!input.path("lastJunctionCdsPosition").isNull()
                    || !input.path("distanceToLastJunction").isNull())) return "PVS1_ESCAPE_ALTERED_TOPOLOGY_UNRESOLVED";
            if (exonTotal>1) {
                var junction=input.path("lastJunctionCdsPosition");var distance=input.path("distanceToLastJunction");
                var ptcEnd=input.path("ptcEnd");
                if (!junction.isIntegralNumber() || !junction.canConvertToInt() || !distance.isIntegralNumber()
                        || !distance.canConvertToInt() || !positive(ptcEnd)
                        || (long) junction.asInt()-ptcEnd.asInt()!=distance.asInt())
                    return "PVS1_ESCAPE_ALTERED_TOPOLOGY_UNRESOLVED";
            }
        }
        if (!supports(policy) || scope.isMissingNode()) return exonTotal == 1
                ? "PVS1_SINGLE_EXON_ESCAPE_CONDITIONS_MISSING" : "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED";
        // Exclusion by a stricter NMD policy is not biological evidence of escape.
        if(input.path("distanceToLastJunction").asInt()>=51 && ptcExon<exonTotal)
            return "PVS1_ESCAPE_NMD_STATUS_UNRESOLVED";
        if(scope.has("positionConditions")) {
            String issue=Pvs1NmdConditions.evaluateConditions(scope.path("positionConditions"),input,"escapePositionConditionChecks");
            if(issue!=null)return issue;
        }
        int length=model.path("proteinLength").asInt();
        int first=input.has("firstChangedProteinPosition")?input.path("firstChangedProteinPosition").asInt():input.path("proteinPosition").asInt();
        int ptc=input.path("ptcStart").asInt();
        if (first<1 || first>length || ptc<1 || (ptc-1)%3!=0 || first>(ptc-1)/3+1)
            return "PVS1_ESCAPE_PROTEIN_EFFECT_UNRESOLVED";
        int alteredLength=(ptc-1)/3;
        var audit=input.putObject("escapeAssessment").put("schemaVersion","pvs1-nmd-escape-assessment-v1")
                .put("firstAlteredResidue",first).put("referenceProteinLength",length).put("alteredProteinLength",alteredLength)
                .put("referenceResiduesAffected",length-first+1).put("netProteinShortening",Math.max(0,length-alteredLength));
        audit.set("reviewId",scope.path("reviewId").deepCopy());
        var affectedOrdinals=audit.putArray("affectedReferenceExonOrdinals");
        for(var exon:model.path("exons"))
            if(exon.path("cdsEnd").asInt()>=first*3-2 && exon.path("cdsStart").asInt()<=length*3)
                affectedOrdinals.add(exon.path("ordinal").asInt());
        input.put("nmdPrediction","NMD_NOT_PREDICTED").put("selectedTranscript",model.path("transcript").asText())
                .put("sequenceDerivationStatus","GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE");
        var checks=audit.putArray("exonChecks");boolean populationUnknown=false,relevanceUnknown=false;
        int absent=0,frequent=0;
        for(var exon:model.path("exons")) {
            if (exon.path("cdsEnd").asInt()<first*3-2 || exon.path("cdsStart").asInt()>length*3) continue;
            JsonNode fact=null;
            for(var f:scope.path("exonAssessments")) if(f.path("ordinal").equals(exon.path("ordinal")))fact=f;
            if(fact==null)return "PVS1_ESCAPE_EXON_ASSESSMENT_MISSING";
            checks.add(fact.deepCopy());
            if("ABSENT".equals(fact.path("biologicalRelevance").asText()))absent++;
            if("FREQUENT".equals(fact.path("populationLof").asText()))frequent++;
            if("UNKNOWN".equals(fact.path("biologicalRelevance").asText())
                    || "PRESENT".equals(fact.path("biologicalRelevance").asText())
                       && !TruncatingNmdPolicy.contains(policy.at("/nmdScope/relevantExonOrdinals"),exon.path("ordinal").asInt()))
                relevanceUnknown=true;
            if(!"NOT_FREQUENT".equals(fact.path("populationLof").asText()))populationUnknown=true;
        }
        // A tolerated/irrelevant distal exon cannot negate damage to another relevant exon.
        if(checks.isEmpty())return "PVS1_ESCAPE_EXON_ASSESSMENT_MISSING";
        if(absent==checks.size())return "PVS1_ESCAPE_EXON_NOT_RELEVANT";
        if(frequent==checks.size())return "PVS1_ESCAPE_EXON_LOF_TOLERANT";
        if(absent>0||frequent>0)return "PVS1_ESCAPE_EXON_FACTS_CONFLICT";
        if(relevanceUnknown)return "PVS1_ESCAPE_EXON_RELEVANCE_UNRESOLVED";
        var matches=new ArrayList<JsonNode>();var ruleChecks=audit.putArray("ruleChecks");boolean equalBoundary=false,missingPopulation=false;
        for(var rule:scope.path("rules")) {
            var row=ruleChecks.addObject();row.set("rule",rule.deepCopy());boolean hit;
            if("CRITICAL_REGION".equals(rule.path("predicate").asText())) {
                int affected=Math.max(0,Math.min(length,rule.path("end").asInt())-Math.max(first,rule.path("start").asInt())+1);
                row.put("affectedResiduesInRegion",affected);hit=affected>=rule.path("minimumAffectedResidues").asInt();
            } else {
                int numerator=audit.path("REFERENCE_TAIL_ALTERED_OR_REMOVED".equals(rule.path("metric").asText())?"referenceResiduesAffected":"netProteinShortening").asInt();
                long left=(long)numerator*rule.path("denominator").asInt();long right=(long)length*rule.path("numerator").asInt();
                row.put("actualNumerator",numerator).put("actualDenominator",length).put("leftProduct",left).put("rightProduct",right);
                if(populationUnknown){row.put("status","POPULATION_UNRESOLVED");missingPopulation=true;continue;}
                equalBoundary|=left==right;
                hit="GT".equals(rule.path("comparison").asText())?left>right:left<right;
            }
            row.put("status",hit?"MATCH":"NO_MATCH");if(hit)matches.add(rule);
        }
        if(matches.size()>1)return "PVS1_ESCAPE_RULES_AMBIGUOUS";
        if(matches.isEmpty())return missingPopulation?"PVS1_ESCAPE_POPULATION_TOLERANCE_UNRESOLVED"
                :equalBoundary?"PVS1_ESCAPE_FRACTION_BOUNDARY_UNSPECIFIED":"PVS1_ESCAPE_NO_REVIEWED_RULE_MATCH";
        if(missingPopulation)return "PVS1_ESCAPE_POPULATION_TOLERANCE_UNRESOLVED";
        var rule=matches.get(0);
        if("VERY_STRONG".equals(rule.path("strength").asText()) && !"SPECIALIZED_POLICY_SELECTED".equals(selection.path("outcome").asText()))
            return "PVS1_ESCAPE_SPECIALIZED_STRENGTH_REQUIRED";
        audit.set("selectedRule",rule.deepCopy());audit.put("status","MET");
        return null;
    }

    static boolean negative(String issue) {
        return "PVS1_ESCAPE_EXON_LOF_TOLERANT".equals(issue)||"PVS1_ESCAPE_EXON_NOT_RELEVANT".equals(issue);
    }
    private static boolean sourced(JsonNode value,JsonNode policy) {
        if(!text(value.path("basis"))||!value.path("sourceIds").isArray()||value.path("sourceIds").isEmpty())return false;
        var ids=new HashSet<String>();
        for(var id:value.path("sourceIds")) {
            if(!text(id)||!ids.add(id.asText()))return false;
            boolean found=false;for(var source:policy.path("scientificSources"))if(id.equals(source.path("sourceId")))found=true;
            if(!found)return false;
        }
        return true;
    }
    private static boolean only(JsonNode n,Set<String> fields){if(!n.isObject())return false;var names=n.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))return false;return true;}
    private static boolean positive(JsonNode n){return n.isIntegralNumber()&&n.canConvertToInt()&&n.asInt()>0;}
    private static boolean text(JsonNode n){return n.isTextual()&&!n.asText().isBlank();}
}
