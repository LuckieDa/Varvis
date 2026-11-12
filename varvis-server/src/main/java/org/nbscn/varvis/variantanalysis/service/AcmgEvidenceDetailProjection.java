package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.provider.Pm2RuleSummary;
import org.nbscn.varvis.variantanalysis.provider.PredictionMechanismSummary;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;

/** Optional display facts from the SAME integrity-verified saved batch, not a history migration. */
public final class AcmgEvidenceDetailProjection {
    private AcmgEvidenceDetailProjection() { }
    public static ObjectNode enrich(ObjectMapper mapper,ObjectNode response,JsonNode privateBatch,
            JsonNode sameRunPrivateInventory,JsonNode sameRunVerifiedClinicalEvidence){
        var view=enrich(mapper,response,privateBatch);
        AcmgClinicalDetailProjection.attach(mapper,view,privateBatch,sameRunPrivateInventory,sameRunVerifiedClinicalEvidence);
        return view;
    }
    public static ObjectNode enrich(ObjectMapper mapper,ObjectNode response,JsonNode privateBatch){
        var view=response.deepCopy();var hash=new RuntimeEvidenceV2Adapter(mapper);
        for(var candidate:view.path("candidates"))for(var attempt:candidate.path("attempts"))for(var criterion:attempt.at("/localBatch/criteria")){
            String code=criterion.path("criterionId").asText(),pointer=criterion.at("/details/modulePointer").asText();
            if(!code.matches("ACMG_AMP_2015:(?:PM2|PP3|BP4)")||!pointer.matches("/modules/(?:pm2|prediction)/results/[0-9]+"))continue;
            if(!pointer.startsWith("/modules/"+(code.endsWith(":PM2")?"pm2":"prediction")+"/"))continue;
            JsonNode original=privateBatch.at(pointer),context=attempt.path("context");
            JsonNode binding=code.endsWith(":PM2")?original:original.path("plan");
            if(!candidate.path("candidateId").isTextual()||!attempt.path("hypothesisId").isTextual()
                    ||!candidate.path("candidateId").equals(binding.path("candidateId"))||!attempt.path("hypothesisId").equals(binding.path("hypothesisId"))
                    ||!context.isObject()||!matchesContext(mapper,code,context,original,binding))continue;
            JsonNode saved=null;int matches=0;
            for(var row:privateBatch.path("results"))if(candidate.path("candidateId").equals(row.path("candidateId"))&&attempt.path("hypothesisId").equals(row.path("hypothesisId"))){
                for(var item:row.at("/localBatch/criteria"))if(code.equals(item.path("criterionId").asText())){saved=item;matches++;}
            }
            if(saved==null||matches!=1||!criterion.path("status").equals(saved.path("status"))||!criterion.path("appliedStrength").equals(saved.path("appliedStrength"))
                    ||!criterion.path("reasonCode").equals(saved.path("reasonCode"))||!pointer.equals(saved.at("/details/modulePointer").asText())
                    ||!criterion.at("/details/originalResultSha256").asText().equals(hash.canonicalSha256(saved.at("/details/originalResult"))))continue;
            if(!(criterion.path("details") instanceof ObjectNode details))continue;
            if(code.endsWith(":PM2")){
                if(!saved.at("/details/originalResult").equals(original.path("evaluation")))continue;
                var summary=Pm2RuleSummary.build(mapper,original);
                if(summary.path("conditions").isArray())details.set("populationRule",summary);
            }else{
                if(!original.path("mechanismSummary").isObject())continue;
                var evaluation=original.path("criteria").path(code.substring(code.indexOf(':')+1));
                if(!saved.at("/details/originalResult").equals(evaluation))continue;
                var summary=PredictionMechanismSummary.fromResult(mapper,original);
                var savedMechanisms=details.path("predictionMechanisms").path("mechanisms");
                for(var mechanism:savedMechanisms)for(var raw:summary.path("mechanisms"))if(mechanism instanceof ObjectNode target
                        &&mechanism.path("mechanism").equals(raw.path("mechanism"))&&mechanism.path("model").equals(raw.path("model"))
                        &&mechanism.path("status").equals(raw.path("status"))&&mechanism.path("direction").equals(raw.path("direction"))
                        &&mechanism.path("appliedStrength").equals(raw.path("appliedStrength")))
                    for(String field:new String[]{"values","thresholds"})if(raw.has(field))target.set(field,raw.path(field).deepCopy());
                String selected=evaluation.path("selectedMechanism").asText();
                if(code.endsWith(":PP3")&&"MET".equals(evaluation.path("status").asText())
                        && ("PROTEIN_MISSENSE".equals(selected)||"SPLICING".equals(selected)))
                    details.put("selectedPredictionMechanism",selected);
            }
        }
        return view;
    }

    private static boolean matchesContext(ObjectMapper mapper,String code,JsonNode context,JsonNode original,JsonNode binding){
        if(context.equals(binding.path("context")))return true;
        if(code.endsWith(":PM2"))return false;
        // The saved workbench uses v3; prediction used its bound legacy v2 projection.
        var projection=original.path("sourceProjection");
        if(!(projection instanceof ObjectNode projected)||!projection.equals(binding.path("sourceProjection"))
                ||!context.equals(projection.path("originalContext"))||!binding.path("context").equals(projection.path("evaluationContext"))
                ||!binding.path("candidateId").equals(projection.path("candidateId"))
                ||!binding.path("hypothesisId").equals(projection.path("hypothesisId")))return false;
        try{
            var body=projected.deepCopy();body.remove("bindingSha256");
            return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(body).equals(projection.path("bindingSha256").asText())
                    && binding.path("context").equals(LegacyContextProjection.projectContext(mapper,(ObjectNode)context));
        }catch(RuntimeException invalid){return false;}
    }
}
