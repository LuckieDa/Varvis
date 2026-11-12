package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;

/** General sequence/source acquisition with independent BP3 regional knowledge; no PM4 inversion. */
public final class GeneralBp3Runtime {
    public static final String VERSION = "1.1.0";
    public static final String CRITERION = "ACMG_AMP_2015:BP3";
    private final ObjectMapper mapper;
    private final ProteinLengthRegionFactSupply facts;
    private final Bp3ProteinRegionAssessment assessment;
    private final PredictionSpecializationGuard specializations;
    @org.springframework.lang.Nullable private final PublishedCriterionKnowledge knowledge;
    private int evaluations;
    public GeneralBp3Runtime(ObjectMapper mapper, ProteinLengthRegionFactSupply facts,
            Bp3ProteinRegionAssessment assessment, PredictionSpecializationGuard specializations) {
        this(mapper, facts, assessment, specializations, null);
    }
    public GeneralBp3Runtime(ObjectMapper mapper, ProteinLengthRegionFactSupply facts,
            Bp3ProteinRegionAssessment assessment, PredictionSpecializationGuard specializations,
            @org.springframework.lang.Nullable PublishedCriterionKnowledge knowledge) {
        this.mapper = mapper; this.facts = Objects.requireNonNull(facts); this.assessment = Objects.requireNonNull(assessment);
        this.specializations = Objects.requireNonNull(specializations);
        this.knowledge = knowledge;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("policyId", facts.complexAllelesEnabled()
                ? "PROJECT_LOCAL_BP3_VERIFIED_INFRAME_SMALL_ALLELE" : "PROJECT_LOCAL_BP3_SIMPLE_INFRAME").put("policyVersion", VERSION)
                .put("criterionId", CRITERION).put("clinicalValidationCompleted", false).put("functionalContextRequired", true)
                .put("automaticNegativeFromNoHit", false).put("combinedFunctionProjectionVersion", CombinedProteinRegionSupply.BP3_PROJECTION_VERSION)
                .put("codingSequenceProjectionVersion", org.nbscn.varvis.variantanalysis.service.LegacyContextProjection.CODING_SEQUENCE_VERSION);
        if (facts.complexAllelesEnabled()) out.set("publicProjectionCapability", ProteinLengthPublicProof.identity(mapper));
        out.set("factSupply", facts.identity()); out.set("regionAssessment", assessment.identity()); out.set("specializations", specializations.identity());
        var published=knowledge;
        if (published != null) { out.set("publishedKnowledge", published.identity()); out.put("publishedAssessmentVersion", PublishedBp3RegionAssessment.VERSION); }
        return out;
    }
    public int evaluations() { return evaluations; }
    public ObjectNode supplyCounts() { return facts.counts(); }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId) {
        var out = mapper.createObjectNode().put("candidateId", candidateId).put("hypothesisId", hypothesisId);
        try {
            var projection = selected.codingSequenceProjection(candidateId, hypothesisId); out.set("sourceProjection", projection.snapshot());
            return evaluate(projection.context(), projection.queryCandidate(), out);
        } catch (RuntimeException bad) { return finish(out, "NOT_EVALUATED", Bp3ProteinRegionAssessment.safeReason(bad)); }
    }
    ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode out) {
        evaluations++; out.set("policy", identity()); out.put("contextId", context.path("contextId").asText());
        CodingTranscriptMaterialSupply.Material proofMaterial = null;
        try {
            var spec = specializations.check(context); out.set("specializationSearch", spec);
            boolean known = "BLOCKED".equals(spec.path("status").asText());
            if (known && knowledge == null) return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", "BP3_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION");
            var supplied = facts.resolveSequence(context, candidate); out.setAll(supplied.snapshot()); var model = supplied.material().model();
            proofMaterial = supplied.material();
            if (!supplied.material().ready() || model == null) return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", out.at("/sequenceMaterial/reasonCode").asText("BP3_SEQUENCE_MATERIAL_UNAVAILABLE"));
            // Normalization equivalence alone is insufficient. The fact supply above reconstructs the
            // complete altered CDS and protein and checks original HGVSc/HGVSp against the new allele.
            out.put("annotationCorrespondence", "COMPLETE_SEQUENCE_COMPARISON_PASSED");
            var published=knowledge;
            if (published != null) {
                var selection = published.select(CRITERION, context, model); out.set("knowledgeSelection", selection);
                if ("READY".equals(selection.path("status").asText())) {
                    if ("SINGLE_SIMPLE_INFRAME_COMPLETE_EQUIVALENT_ENVELOPE".equals(selection.at("/record/interpretation/supportedVariantScope").asText())
                            && "complete-cds-minimal-replacement-envelope-v1".equals(supplied.material().snapshot()
                                    .at("/alleleAssociation/equivalentEditEnvelope/algorithmId").asText()))
                        return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", "BP3_PUBLISHED_COMPLEX_ALLELE_SCOPE_UNSUPPORTED");
                    var region = PublishedBp3RegionAssessment.assess(selection, model, supplied.material().snapshot().path("alleleAssociation"));
                    out.set("regionAssessment", region); return finishRegionWithProof(context, proofMaterial, out, region);
                }
                // Known source restrictions, mismatched models and conflicting policies cannot fall back.
                if (known || !"PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED".equals(selection.path("reasonCode").asText()))
                    return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", selection.path("reasonCode").asText());
            }
            supplied = facts.resolveRegions(supplied, true); out.setAll(supplied.snapshot());
            var mapped = (ObjectNode) out.path("proteinAnnotations"); var extra = (ObjectNode) out.path("combinedRegions");
            if (!"READY".equals(mapped.path("status").asText())) return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", mapped.path("reasonCode").asText());
            if (!"READY".equals(extra.path("status").asText())) return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", extra.path("reasonCode").asText());
            var region = assessment.assess(context, model, mapped, extra); out.set("regionAssessment", region);
            return finishRegionWithProof(context, proofMaterial, out, region);
        } catch (RuntimeException bad) { return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", Bp3ProteinRegionAssessment.safeReason(bad)); }
    }
    public static final String FULL_RNA_VERSION="1.0.0";
    public ObjectNode fullRnaIdentity(){return identity().put("fullRnaVersion",FULL_RNA_VERSION).put("fullRnaFactsVersion",CodingConsequenceFacts.RNA_VERSION)
            .put("fullRnaRegionVersion",ProteinLengthRegionFactSupply.FULL_RNA_REGION_VERSION);}
    public ObjectNode evaluateFullRna(SelectedEvidencePreparation selected,String candidateId,String hypothesisId,
            @org.springframework.lang.Nullable Pvs1SourceQualification.SourceBoundFullRna source) {
        var out=mapper.createObjectNode().put("candidateId",candidateId).put("hypothesisId",hypothesisId);
        try {var projection=selected.codingSequenceProjection(candidateId,hypothesisId);out.set("sourceProjection",projection.snapshot());
            return evaluateFullRna(projection.context(),projection.queryCandidate(),source,out);
        } catch(RuntimeException invalid){return finishFullRna(out,"NOT_EVALUATED",Bp3ProteinRegionAssessment.safeReason(invalid));}
    }
    ObjectNode evaluateFullRna(ObjectNode context,VariantInterpretation candidate,
            @org.springframework.lang.Nullable Pvs1SourceQualification.SourceBoundFullRna source,ObjectNode out) {
        evaluations++;out.remove(List.of("evaluation","publicSummary","sequenceMaterial","proteinRegionEffects","regionAssessment","fullRnaAssessment","fullRnaRegionAssessments","sharedEffectUse","knowledgeSelection","specializationSearch","regionSupply","proteinAnnotations","combinedRegions"));
        out.set("policy",fullRnaIdentity());out.put("contextId",context.path("contextId").asText());
        try {
            new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(context);
            var spec=specializations.check(context);out.set("specializationSearch",spec);boolean known="BLOCKED".equals(spec.path("status").asText());
            if(known)return finishFullRna(out,"NOT_EVALUATED","BP3_KNOWN_CSPEC_REQUIRES_FULL_RNA_CLAUSE_SELECTION");
            var sequence=facts.resolveFullRnaSequence(context,candidate,source);out.setAll(sequence.snapshot());
            if(!sequence.material().ready())return finishFullRna(out,"NOT_EVALUATED",sequence.material().snapshot().path("reasonCode").asText());
            String assay=Pvs1SourceQualification.fullRnaAssayIssue(source);if(assay!=null)return finishFullRna(out,"NOT_EVALUATED",assay);
            var model=sequence.material().model();if(model==null)return finishFullRna(out,"NOT_EVALUATED","FULL_RNA_MODEL_UNAVAILABLE");
            var association=sequence.material().snapshot().path("alleleAssociation");
            if(!"SOURCE_BOUND_RNA_INFRAME_LENGTH_CHANGE".equals(association.path("branch").asText()))
                return finishFullRna(out,"NOT_EVALUATED","BP3_SOURCE_BOUND_RNA_INFRAME_CHANGE_REQUIRED");
            var published=knowledge;
            if(published!=null) {
                var selection=published.select(CRITERION,context,model);out.set("knowledgeSelection",selection);
                if(!"PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED".equals(selection.path("reasonCode").asText()))
                    return finishFullRna(out,"NOT_EVALUATED","BP3_PUBLISHED_FULL_RNA_SCOPE_REQUIRES_EXPLICIT_CLAUSE");
            }
            out.putObject("sharedEffectUse").put("mechanicalEffectSha256",association.path("mechanicalEffectSha256").asText())
                    .put("sourceOriginalSha256",association.path("sourceOriginalSha256").asText()).put("independentFunctionalQualificationRequired",true);
            var regional=facts.resolveFullRnaRegions(sequence,true);out.setAll(regional.snapshot());var rows=out.putArray("fullRnaRegionAssessments");
            String failure=null;boolean excluded=false;
            for(var interval:out.at("/proteinRegionEffects/intervals")) {
                var row=rows.addObject();row.set("referenceProteinRange",interval.path("referenceProteinRange").deepCopy());
                if(!(interval.path("proteinAnnotations") instanceof ObjectNode mapped)||!(interval.path("combinedRegions") instanceof ObjectNode extra)) {
                    if(failure==null)failure="FULL_RNA_REGION_SOURCE_UNAVAILABLE";continue;
                }
                if(!"READY".equals(mapped.path("status").asText())||!"READY".equals(extra.path("status").asText())) {
                    String why=!"READY".equals(mapped.path("status").asText())?mapped.path("reasonCode").asText():extra.path("reasonCode").asText();row.put("reasonCode",why);
                    if(failure==null)failure=why;continue;
                }
                var region=assessment.assess(context,model,mapped,extra);row.set("regionAssessment",region);
                String state=region.path("regionState").asText();
                if("EXCLUDED".equals(state))excluded=true;
                else if(!"QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION".equals(state)&&failure==null)failure=region.path("reasonCode").asText();
            }
            if(failure!=null)return finishFullRna(out,"NOT_EVALUATED",failure);
            if(rows.isEmpty())return finishFullRna(out,"NOT_EVALUATED","FULL_RNA_REGION_EFFECT_INTERVALS_UNAVAILABLE");
            return finishFullRna(out,excluded?"NOT_MET":"MET",excluded?"BP3_SOURCE_BOUND_RNA_REGION_EXCLUDED":"BP3_SOURCE_BOUND_RNA_REPEAT_NO_FUNCTION_MET");
        } catch(RuntimeException invalid){return finishFullRna(out,"NOT_EVALUATED",Bp3ProteinRegionAssessment.safeReason(invalid));}
    }
    private ObjectNode finishFullRna(ObjectNode out,String status,String reason) {
        out.put("status",status).put("reasonCode",reason);var evaluation=out.putObject("evaluation").put("criterionId",CRITERION)
                .put("appliedStrength","MET".equals(status)?"SUPPORTING":"NONE");
        evaluation.putObject("evaluationResult").put("status",status).put("reasonCode",reason);
        var summary=out.putObject("fullRnaAssessment").put("schemaVersion","bp3-full-rna-assessment-v1").put("methodVersion",FULL_RNA_VERSION)
                .put("status",status).put("reasonCode",reason).put("clinicalValidationCompleted",false);
        for(String key:List.of("branch","sourceOriginalSha256","mechanicalEffectSha256","factsSha256","netAminoAcidChange")) {
            var value=out.at("/sequenceMaterial/alleleAssociation/"+key);if(!value.isMissingNode())summary.set(key,value.deepCopy());
        }
        return out;
    }

    private ObjectNode finishWithProof(ObjectNode context, @org.springframework.lang.Nullable CodingTranscriptMaterialSupply.Material material,
            ObjectNode out, String status, String reason) {
        finish(out, status, reason);
        ProteinLengthPublicProof.attach(mapper, out, material, context, CRITERION);
        out.set("publicSummary", publicSummary(out));
        return out;
    }
    private ObjectNode finishRegionWithProof(ObjectNode context, @org.springframework.lang.Nullable CodingTranscriptMaterialSupply.Material material,
            ObjectNode out, ObjectNode region) {
        String status = switch (region.path("regionState").asText()) {
            case "QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION" -> "MET";
            case "EXCLUDED" -> "NOT_MET";
            default -> "NOT_EVALUATED";
        };
        return finishWithProof(context, material, out, status, region.path("reasonCode").asText());
    }
    private ObjectNode finish(ObjectNode out, String status, String reason) {
        out.put("status", status).put("reasonCode", reason);
        var evaluation = out.putObject("evaluation").put("criterionId", CRITERION).put("appliedStrength", "MET".equals(status) ? "SUPPORTING" : "NONE");
        evaluation.putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        out.set("publicSummary", publicSummary(out)); return out;
    }
    public static ObjectNode publicSummary(ObjectNode out) {
        var summary = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("schemaVersion", "bp3-general-facts-v1")
                .put("policyVersion", VERSION).put("scope", "VERIFIED_INFRAME_LENGTH_CHANGE".equals(out.at("/sequenceMaterial/associationKind").asText())
                        ? "PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE" : "PROJECT_LOCAL_SIMPLE_INFRAME").put("clinicalValidationCompleted", false)
                .put("functionAbsenceInferredFromNoHit", false).put("regionState", out.at("/regionAssessment/regionState").asText("UNKNOWN"))
                .put("repeatObservation", out.at("/regionAssessment/repeatObservation").asText("INCOMPLETE"))
                .put("functionalLeadCount", out.at("/regionAssessment/functionalLeads").size());
        for (String field : List.of("transcript", "protein", "netAminoAcidChange")) {
            var value = out.at("/sequenceMaterial/alleleAssociation/" + field); if (!value.isMissingNode()) summary.set(field, value.deepCopy());
        }
        var range = out.at("/proteinAnnotations/queryRange"); if (range.isObject()) summary.set("queryRange", range.deepCopy());
        if (out.has("knowledgeSelection")) {
            var selection = out.path("knowledgeSelection");
            summary.put("knowledgeStatus", selection.path("status").asText()).put("knowledgeReason", selection.path("reasonCode").asText());
            if (selection.has("record")) {
                summary.put("specificationKey", selection.at("/record/specificationKey").asText())
                        .put("declaredTranscript", selection.path("declaredTranscript").asText())
                        .put("executionTranscript", selection.path("executionTranscript").asText())
                        .put("clauseSha256", selection.at("/record/clauseSha256").asText());
            }
        }
        if ("PUBLISHED_EXPERT_SPECIFICATION".equals(out.at("/regionAssessment/qualificationBasis").asText())) {
            summary.put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION").put("functionalAnnotationSearchPerformed", false);
            var region = out.path("regionAssessment");
            if (region.path("queryRange").isObject()) summary.set("queryRange", region.path("queryRange").deepCopy());
            if (region.path("selectedRule").isObject()) summary.set("selectedRule", region.path("selectedRule").deepCopy());
        }
        summary.setAll(ProteinLengthPublicProof.project(new ObjectMapper(), out));
        return summary;
    }
}
