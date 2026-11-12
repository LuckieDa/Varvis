package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

/** Re-evaluates sealed local population facts, never trusts a supplied PM2 MET label.
 * A literature partner may have its own query context without becoming a patient candidate.
 * The owning service must supply source-verified identities and selected policy context.
 */
public final class Pm3RarityQualification {
    private final ObjectMapper mapper;
    public Pm3RarityQualification(ObjectMapper mapper) { this.mapper=Objects.requireNonNull(mapper); }

    /** Query a source-verified external allele using the same population transport as PM2.
     * Bind expected disease/allele before any I/O; never accept a model's rarity verdict.
     */
    public ObjectNode acquire(Pm2LocalExomesRuntime runtime, JsonNode expectedAllele,
            JsonNode expectedBackground, ObjectNode alleleContext,
            org.nbscn.varvis.variantanalysis.domain.VariantInterpretation reference,
            org.nbscn.varvis.variantanalysis.domain.AnalysisContext analysis,
            Pm2LocalExomesProfile profile,String sourceSnapshot,JsonNode sharedObservation) {
        if (expectedAllele==null || alleleContext==null
                || !expectedAllele.equals(alleleContext.at("/variant/evidenceQueryAllele"))
                || expectedBackground==null
                || List.of("gene","disease","moi","dataClass").stream().anyMatch(field ->
                    expectedBackground.path(field).isMissingNode()
                    || !expectedBackground.path(field).equals(alleleContext.path(field)))
                || !runtime.profile().equals(profile.document())) {
            return mapper.createObjectNode().put("schemaVersion","pm3-local-rarity-qualification-v1")
                    .put("qualification","CONFLICT").put("reasonCode","PM3_RARITY_QUERY_BINDING_MISMATCH")
                    .put("businessPublishable",false).put("providerCalls",0);
        }
        var query=runtime.evaluateReference(alleleContext,reference,analysis,sourceSnapshot,sharedObservation);
        ObjectNode result;
        if(query.has("observation")) {
            result=qualify(expectedAllele,expectedBackground,alleleContext,query.path("observation"),
                    profile,sourceSnapshot,query.get("allSitesObservation"));
        } else {
            result=mapper.createObjectNode().put("schemaVersion","pm3-local-rarity-qualification-v1")
                    .put("qualification","UNKNOWN").put("businessPublishable",false)
                    .put("scope","LOCAL_GNOMAD_EXOMES_ONLY").put("wholeGnomadConclusion",false);
            result.set("reasonCode",query.at("/evaluationResult/reasonCode"));
        }
        result.set("populationQuery",query);
        result.put("qualificationEvaluatorCalls",query.has("observation")?1:0);
        return result;
    }

    public ObjectNode qualify(JsonNode expectedAllele, JsonNode expectedBackground, JsonNode alleleContext,
            JsonNode observation, Pm2LocalExomesProfile profile, String sourceSnapshot, JsonNode allSites) {
        ObjectNode result=mapper.createObjectNode().put("schemaVersion","pm3-local-rarity-qualification-v1")
                .put("qualification","UNKNOWN").put("scope","LOCAL_GNOMAD_EXOMES_ONLY")
                .put("wholeGnomadConclusion",false).put("businessPublishable",false);
        try {
            if (expectedAllele==null || !expectedAllele.isObject() || expectedBackground==null || !expectedBackground.isObject())
                throw new IllegalArgumentException("PM3_RARITY_EXPECTED_BINDING_REQUIRED");
            if (alleleContext==null || !alleleContext.isObject() ||
                    !expectedAllele.equals(alleleContext.at("/variant/evidenceQueryAllele")))
                throw new IllegalArgumentException("PM3_RARITY_ALLELE_MISMATCH");
            for(String field:List.of("gene","disease","moi","dataClass"))
                if (expectedBackground.path(field).isMissingNode() || !expectedBackground.path(field).equals(alleleContext.path(field)))
                    throw new IllegalArgumentException("PM3_RARITY_BACKGROUND_MISMATCH");
            ObjectNode evaluation=new GnomadPopulationCriterionEvaluator(mapper)
                    .evaluatePm2LocalExomes(alleleContext,observation,profile,sourceSnapshot,allSites);
            result.set("evaluation",evaluation);
            result.set("queryAllele",expectedAllele.deepCopy());
            result.put("contextId",alleleContext.path("contextId").asText());
            result.put("observationId",observation.path("observationId").asText());
            result.put("sourceSnapshot",sourceSnapshot).put("profileSha256",profile.sha256());
            String status=evaluation.at("/evaluationResult/status").asText();
            result.put("qualification",switch(status) {case "MET"->"VERIFIED";case "NOT_MET"->"FAILED";default->"UNKNOWN";});
            result.put("reasonCode",evaluation.at("/evaluationResult/reasonCode").asText());
        } catch (IllegalArgumentException invalid) {
            String code=invalid.getMessage();
            result.put("reasonCode",code!=null && code.matches("[A-Z][A-Z0-9_]{1,160}")?code:"PM3_RARITY_INVALID_INPUT");
            if (code!=null && (code.contains("MISMATCH") || code.contains("CONFLICT") || code.contains("INTEGRITY")))
                result.put("qualification","CONFLICT");
        }
        return result;
    }
}
