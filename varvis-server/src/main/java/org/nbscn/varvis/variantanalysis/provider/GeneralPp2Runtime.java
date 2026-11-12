package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Local exact-sequence PP2 with published scope assertions, separate from reviewed-bundle adoption. */
public final class GeneralPp2Runtime {
    public static final String VERSION = "1.2.0";
    public static final String HCM_TRANSCRIPT_SCOPE_VERSION = Pp2HcmPublishedSource.TRANSCRIPT_SCOPE_VERSION;
    public static final String HCM_METHOD_TYPE = Pp2HcmPublishedSource.TYPE;
    public static final String CRITERION = "ACMG_AMP_2015:PP2";
    private final ObjectMapper mapper;
    private final CodingTranscriptMaterialSupply materials;
    private final PublishedCriterionKnowledge knowledge;
    private final boolean germlineDeclared;
    @Nullable private final Pp2OtherPhenotypeSource otherPhenotypes;
    private int evaluations;

    public GeneralPp2Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials, PublishedCriterionKnowledge knowledge) {
        this(mapper, materials, knowledge, false);
    }
    /** The caller must obtain this scope declaration explicitly; GT, a synthetic flag and inference are insufficient. */
    public GeneralPp2Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials, PublishedCriterionKnowledge knowledge,
            boolean germlineDeclared) {
        this(mapper, materials, knowledge, germlineDeclared, null);
    }
    public GeneralPp2Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials, PublishedCriterionKnowledge knowledge,
            boolean germlineDeclared, @Nullable Pp2OtherPhenotypeSource otherPhenotypes) {
        this.mapper = Objects.requireNonNull(mapper); this.materials = Objects.requireNonNull(materials);
        this.knowledge = Objects.requireNonNull(knowledge); this.germlineDeclared = germlineDeclared;
        this.otherPhenotypes = otherPhenotypes;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("policyId", "PROJECT_LOCAL_PUBLISHED_PP2").put("policyVersion", VERSION)
                .put("criterionId", CRITERION).put("adoptionMode", "PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS")
                .put("clinicalValidationCompleted", false).put("createsReviewReceipt", false)
                .put("germlineDeclared", germlineDeclared).put("constraintScoresRecalculated", false)
                .put("interpretationVersion", Pp2PublishedAssessment.VERSION).put("hcmMethodVersion", Pp2HcmPublishedSource.VERSION)
                .put("hcmTranscriptScopeVersion", HCM_TRANSCRIPT_SCOPE_VERSION);
        var source = otherPhenotypes;
        if (source == null) out.putObject("otherPhenotypeSource").put("configured", false);
        else out.set("otherPhenotypeSource", source.identity());
        out.set("materialSupply", materials.identity()); out.set("knowledge", knowledge.identity()); return out;
    }
    public int evaluations() { return evaluations; }
    public GeneralPp2Runtime withOtherPhenotypeSource(Pp2OtherPhenotypeSource source) {
        return new GeneralPp2Runtime(mapper, materials, knowledge, germlineDeclared, Objects.requireNonNull(source));
    }
    /** Background/type metadata decides whether the selected PP2 task needs the HCM dependency. */
    public boolean requiresOtherPhenotypeSource(ObjectNode context) {
        return knowledge.hasInterpretationForBackground(CRITERION, context, Pp2HcmPublishedSource.TYPE);
    }
    /** The caller retains this once-per-batch handle for identity, private archive and all row evaluations. */
    @Nullable public Pp2OtherPhenotypeSource.Observed prepareOtherPhenotypeSourceIdentity() {
        var source = otherPhenotypes; return source == null ? null : source.observe();
    }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId) {
        return evaluate(selected, candidateId, hypothesisId, null);
    }
    /** A normal batch passes its single pre-request observation; the object cannot be restored from JSON. */
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId,
            @Nullable Pp2OtherPhenotypeSource.Observed observed) {
        var out = mapper.createObjectNode().put("candidateId", candidateId).put("hypothesisId", hypothesisId);
        try {
            var projection = selected.legacyProjection(candidateId, hypothesisId); out.set("sourceProjection", projection.snapshot());
            return evaluate(projection.context(), projection.queryCandidate(), out, observed);
        } catch (RuntimeException invalid) { return finish(out, "NOT_EVALUATED", safeReason(invalid)); }
    }
    ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode out) {
        return evaluate(context, candidate, out, null);
    }
    ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode out,
            @Nullable Pp2OtherPhenotypeSource.Observed observed) {
        evaluations++; out.set("policy", identity()); out.put("contextId", context.path("contextId").asText());
        try {
            var execution = context;
            var supplied = materials.resolve(context, candidate); out.set("sequenceMaterial", supplied.snapshot());
            var model = supplied.model();
            if (!supplied.ready() || model == null)
                return finish(out, "NOT_EVALUATED", supplied.snapshot().path("reasonCode").asText("PP2_SEQUENCE_MATERIAL_UNAVAILABLE"));
            var selection = knowledge.selectForVerifiedMissense(CRITERION, context, supplied); out.set("knowledgeSelection", selection);
            if ("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED".equals(selection.path("reasonCode").asText())) {
                var alternative = executionContext(context, candidate, out);
                if (alternative != null) {
                    out.set("originalSequenceMaterial", supplied.snapshot());
                    supplied = materials.resolve(alternative, candidate);
                    out.set("sequenceMaterial", supplied.snapshot());
                    if (!supplied.ready()) return finish(out,"NOT_EVALUATED",supplied.snapshot().path("reasonCode").asText());
                    selection = knowledge.selectForVerifiedMissense(CRITERION, alternative, supplied);
                    out.set("knowledgeSelection",selection);
                    execution = alternative;
                } else if ("AMBIGUOUS".equals(out.at("/transcriptPreparation/status").asText()))
                    return finish(out,"NOT_EVALUATED","PP2_MULTIPLE_POLICY_TRANSCRIPTS_REQUIRE_RESOLUTION");
            }
            if (!"READY".equals(selection.path("status").asText()))
                return finish(out, "NOT_EVALUATED", selection.path("reasonCode").asText("PP2_PUBLISHED_KNOWLEDGE_UNAVAILABLE"));
            boolean hcm = Pp2HcmPublishedSource.TYPE.equals(selection.at("/record/interpretation/type").asText());
            if (hcm) out.set("hcmSourceMethod", Pp2HcmPublishedSource.inspect(mapper, selection).snapshot());
            String issue = Pp2PublishedAssessment.validate(selection, germlineDeclared);
            if (issue != null) return finish(out, "NOT_EVALUATED", issue);
            if (hcm) return evaluateHcm(context, execution, selection, supplied, out, observed);
            var conditions = selection.at("/record/interpretation/conditions"); out.set("publishedConditions", conditions.deepCopy());
            issue = Pp2ConditionDecision.reason(conditions);
            return finish(out, issue == null ? "MET" : "PP2_CONDITION_UNKNOWN_OR_CONFLICT".equals(issue) ? "NOT_EVALUATED" : "NOT_MET",
                    issue == null ? "PP2_PUBLISHED_MECHANISM_AND_LOW_BENIGN_BACKGROUND" : issue);
        } catch (RuntimeException invalid) { return finish(out, "NOT_EVALUATED", safeReason(invalid)); }
    }
    private ObjectNode evaluateHcm(ObjectNode prepared, ObjectNode execution, ObjectNode selection,
            CodingTranscriptMaterialSupply.Material material, ObjectNode out, @Nullable Pp2OtherPhenotypeSource.Observed observed) {
        if (!hcmContextsMatch(selection, prepared, execution))
            return finish(out, "NOT_EVALUATED", "PP2_HCM_EXECUTION_CONTEXT_SCOPE_MISMATCH");
        var binding = Pp2HcmPublishedSource.policyBinding(mapper, selection); var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var model = material.model();
        if (model == null) return finish(out, "NOT_EVALUATED", "PP2_HCM_EXECUTION_MATERIAL_REQUIRED");
        binding.put("preparedContextSha256", adapter.canonicalSha256(prepared))
                .put("executionContextSha256", adapter.canonicalSha256(execution))
                .put("executionModelSha256", adapter.canonicalSha256(model))
                .put("alleleAssociationSha256", adapter.canonicalSha256(material.snapshot().path("alleleAssociation")));
        out.set("hcmPolicyBinding", binding.deepCopy());
        var source = otherPhenotypes;
        if (source == null) return finish(out, "NOT_EVALUATED", "PP2_HCM_OTHER_PHENOTYPE_SOURCE_UNAVAILABLE");
        if (observed == null) return finish(out, "NOT_EVALUATED", "PP2_HCM_OTHER_PHENOTYPE_OBSERVATION_REQUIRED");
        String candidateId = out.path("candidateId").asText();
        String hypothesisId = out.path("hypothesisId").asText();
        if (hypothesisId.isBlank()) return finish(out, "NOT_EVALUATED", "PP2_HCM_EXPLICIT_HYPOTHESIS_REQUIRED");
        var captured = source.assess(observed, prepared, candidateId, hypothesisId, binding, material);
        var review = captured.review(); out.set("otherPhenotypeReview", review.snapshot());
        boolean matches = review.matches(observed, prepared, candidateId, hypothesisId, binding);
        out.put("hcmReviewBindingVerified", matches);
        if (!matches) return finish(out, "NOT_EVALUATED", "PP2_HCM_OTHER_PHENOTYPE_BINDING_MISMATCH");
        var decision = Pp2HcmConditionDecision.evaluate(review.outcome());
        return finish(out, decision.status(), decision.reasonCode());
    }
    /** Transport consistency after source/missense validation; this does not qualify a transcript or source. */
    static boolean hcmContextsMatch(ObjectNode selection, ObjectNode prepared, ObjectNode execution) {
        var scope = selection.at("/record/interpretation/sourceScope");
        if (!scope.isObject() || !prepared.at("/variant/evidenceQueryAllele").isObject()
                || !prepared.at("/variant/evidenceQueryAllele").equals(execution.at("/variant/evidenceQueryAllele"))
                || !prepared.path("patientContext").equals(execution.path("patientContext"))) return false;
        for (var context : List.of(prepared, execution))
            if (!scope.path("hgncId").equals(context.at("/gene/hgncId"))
                    || !scope.path("geneSymbol").equals(context.at("/gene/symbol"))
                    || !scope.path("mondoId").equals(context.at("/disease/mondoId"))
                    || !scope.path("moi").equals(context.path("moi"))
                    || !selection.at("/record/scope/assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))) return false;
        String executionTranscript = execution.at("/transcript/accession").asText() + "." + execution.at("/transcript/version").asText();
        return executionTranscript.equals(selection.path("executionTranscript").asText());
    }
    /** Prepare a rule-specific view; never alter the candidate, shared context or choose by score. */
    private ObjectNode executionContext(ObjectNode original, VariantInterpretation candidate, ObjectNode out) {
        var declared = knowledge.declaredTranscripts(CRITERION, original);
        var plan = out.putObject("transcriptPreparation").put("method","EXACT_PUBLISHED_SCOPE_TRANSCRIPT_V1")
                .put("originalTranscript",original.at("/transcript/accession").asText()+"."+original.at("/transcript/version").asText())
                .put("selectionUsesScore",false);
        plan.set("declaredTranscripts",mapper.valueToTree(declared));
        var proposals = new java.util.TreeMap<String,ObjectNode>();
        var annotations = new java.util.ArrayList<org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence>();
        if(candidate.getTranscriptAnnotations()!=null) annotations.addAll(candidate.getTranscriptAnnotations());
        if(candidate.getConsequences()!=null) annotations.addAll(candidate.getConsequences());
        for(var annotation:annotations) {
            String tx=annotation.getVersionedTranscriptId();
            if(tx==null || !tx.matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*")
                    || !original.at("/gene/symbol").asText().equals(annotation.getGeneSymbol())
                    || !"COMPLETED".equals(annotation.getStatus()) || annotation.getSource()==null
                    || !annotation.getSource().startsWith("VEP")
                    || !original.at("/transcript/annotationSourceVersion").asText().equals(annotation.getVersion())
                    || !materials.matchesDeclaredTranscript(original,tx,declared)) continue;
            var derived=original.deepCopy(); int dot=tx.lastIndexOf('.');
            ((ObjectNode)derived.path("transcript")).put("accession",tx.substring(0,dot)).put("version",Integer.parseInt(tx.substring(dot+1)));
            var wiring=new RuntimeEvidenceV2ProviderWiring(mapper,new RuntimeEvidenceV2Adapter(mapper));
            if(wiring.selectedVepConsequence(derived,candidate)==null) continue;
            derived.put("contextId","pp2-transcript-"+new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(derived));
            proposals.put(tx,derived);
        }
        plan.set("availableTranscripts",mapper.valueToTree(proposals.keySet()));
        plan.put("status",proposals.size()==1?"PREPARED":proposals.isEmpty()?"UNAVAILABLE":"AMBIGUOUS");
        if(proposals.size()!=1) return null;
        var chosen=proposals.firstEntry().getValue();plan.set("executionContext",chosen.deepCopy());return chosen;
    }
    private ObjectNode finish(ObjectNode out, String status, String reason) {
        out.put("status", status).put("reasonCode", reason);
        out.putObject("evaluation").put("criterionId", CRITERION).put("appliedStrength", "MET".equals(status) ? "SUPPORTING" : "NONE")
                .putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        out.set("publicSummary", publicSummary(out)); return out;
    }
    public static ObjectNode publicSummary(ObjectNode out) {
        var summary = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("schemaVersion", "pp2-general-facts-v1")
                .put("policyVersion", VERSION).put("scope", "PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS")
                .put("clinicalValidationCompleted", false).put("constraintScoresRecalculated", false).put("createsReviewReceipt", false)
                .put("germlineDeclared", out.at("/policy/germlineDeclared").asBoolean(false));
        for (String name : List.of("declaredTranscript", "executionTranscript", "sourceDisposition", "sourceReasonCode", "sourceClausePointer")) {
            var value = out.at("/knowledgeSelection/" + name); if (value.isTextual()) summary.set(name, value.deepCopy());
        }
        if(out.at("/knowledgeSelection/backgroundTranscriptSources").isArray())
            summary.set("backgroundTranscriptSources",out.at("/knowledgeSelection/backgroundTranscriptSources").deepCopy());
        if (out.at("/knowledgeSelection/sourceTranscriptScope").isObject())
            summary.set("sourceTranscriptScope", out.at("/knowledgeSelection/sourceTranscriptScope").deepCopy());
        if(out.has("transcriptPreparation")) summary.set("transcriptPreparation",out.path("transcriptPreparation").deepCopy());
        summary.put("specificationKey", out.at("/knowledgeSelection/record/specificationKey").asText())
                .put("knowledgeStatus", out.at("/knowledgeSelection/status").asText("NOT_CHECKED"));
        if (out.path("publishedConditions").isObject()) summary.set("conditions", out.path("publishedConditions").deepCopy());
        var spectrum = out.at("/knowledgeSelection/authoritySpectrum");
        if (spectrum.isObject()) {
            summary.set("authoritySpectrum", spectrum.deepCopy());
            var association = out.at("/sequenceMaterial/alleleAssociation");
            summary.put("proteinVariantKey", association.path("proteinVariantKey").asText());
            var sources = summary.putArray("sources");
            boolean constraint = Pp2PublishedSpectrum.CONSTRAINT_TYPE.equals(spectrum.path("method").asText());
            sources.addObject().put("title", constraint ? "ACGS 2024 v1.2" : "ACMG/AMP 2015")
                    .put("url", spectrum.at("/source/url").asText())
                    .put("locator", constraint ? Pp2PublishedSpectrum.ACGS_LOCATOR : "PP2 BP1 Variant spectrum · S19");
            sources.addObject().put("title", "ClinGen 基因–疾病审查").put("url", spectrum.at("/mechanismCorroboration/url").asText())
                    .put("locator", spectrum.at("/mechanismCorroboration/locator").asText());
            if (constraint && summary.path("conditions").isObject()) {
                var mechanismSource = summary.at("/conditions/missenseMechanism/source");
                var constraintSource = summary.at("/conditions/lowBenignMissense/source");
                sources.addObject().put("title", "错义致病机制研究").put("url", mechanismSource.path("url").asText())
                        .put("locator", mechanismSource.path("locator").asText());
                sources.addObject().put("title", "gnomAD 错义约束").put("url", constraintSource.path("url").asText())
                        .put("locator", constraintSource.path("locator").asText());
            }
        }
        if (out.path("hcmSourceMethod").isObject()) {
            summary.put("method", Pp2HcmPublishedSource.TYPE).put("methodVersion", Pp2HcmPublishedSource.VERSION)
                    .put("scope", "PROJECT_RESEARCH_PUBLISHED_HCM_ENRICHMENT");
            var method = out.path("hcmSourceMethod"); var sourceMethod = summary.putObject("hcmSourceMethod");
            for (String key : List.of("status", "reasonCode", "sourceBinding")) if (method.has(key)) sourceMethod.set(key, method.path(key).deepCopy());
            if (method.at("/sourceInterpretation/sourceScope").isObject()) sourceMethod.set("scope", method.at("/sourceInterpretation/sourceScope").deepCopy());
            if (out.path("hcmPolicyBinding").isObject()) summary.set("hcmPolicyBinding", out.path("hcmPolicyBinding").deepCopy());
            if (out.path("hcmReviewBindingVerified").asBoolean(false)) summary.set("otherPhenotypeReview", out.path("otherPhenotypeReview").deepCopy());
        }
        return summary;
    }
    private static String safeReason(RuntimeException invalid) {
        String message = invalid.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{2,160}") ? message : "PP2_GENERAL_RUNTIME_INVALID_INPUT";
    }
}
