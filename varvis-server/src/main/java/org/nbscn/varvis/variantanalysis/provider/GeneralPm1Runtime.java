package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Selected sequence facts and data-driven published PM1 location clauses. */
public final class GeneralPm1Runtime {
    public static final String VERSION = "1.0.0";
    public static final String CRITERION = "ACMG_AMP_2015:PM1";
    private final ObjectMapper mapper;
    private final CodingTranscriptMaterialSupply materials;
    private final PublishedCriterionKnowledge knowledge;
    private final PublishedPm1RegionAssessment assessment;
    @Nullable private final Supplier<Pm1ReviewedRegions> reviewedRegions;
    @Nullable private final Pm1ClinVarIntake clinvarIntake;
    private int evaluations;
    public GeneralPm1Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials, PublishedCriterionKnowledge knowledge) {
        this(mapper, materials, knowledge, null);
    }
    public GeneralPm1Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials,
            PublishedCriterionKnowledge knowledge, @Nullable Supplier<Pm1ReviewedRegions> reviewedRegions) {
        this(mapper, materials, knowledge, reviewedRegions, null);
    }
    public GeneralPm1Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials,
            PublishedCriterionKnowledge knowledge, @Nullable Supplier<Pm1ReviewedRegions> reviewedRegions,
            @Nullable Pm1ClinVarIntake clinvarIntake) {
        this.mapper = Objects.requireNonNull(mapper); this.materials = Objects.requireNonNull(materials);
        this.knowledge = Objects.requireNonNull(knowledge); assessment = new PublishedPm1RegionAssessment(mapper);
        this.reviewedRegions = reviewedRegions;
        this.clinvarIntake = clinvarIntake;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("policyId", "PROJECT_PUBLISHED_PM1_PROTEIN_LOCATIONS").put("policyVersion", VERSION)
                .put("criterionId", CRITERION).put("clinicalValidationCompleted", false).put("createsReviewReceipt", false)
                .put("automaticNegativeFromNoHit", false).put("interpretationVersion", PublishedPm1RegionAssessment.VERSION);
        out.set("sequenceMaterial", materials.identity()); out.set("knowledge", knowledge.identity());
        out.set("conditionalPeerExecution", executionIdentity(mapper)); return out;
    }
    /** Code capability only, safe to include in request identity before any resource lookup. */
    public static ObjectNode executionIdentity(ObjectMapper mapper) {
        return mapper.createObjectNode().put("schemaVersion", "pm1-current-peer-execution-v1")
                .put("methodVersion", "1.0.0").put("peerContractVersion", PublishedPm1CrossCriterionConditions.VERSION)
                .put("selectedOutputScopeExpanded", false).put("selectionAbsenceMeansNegative", false)
                .put("proteinDeletionPolicySha256", PublishedPm1Deletion.RESOURCE_SHA)
                .put("dependencyPlanning", "VERIFIED_MATCHED_SOURCE_SUPPORT_ONLY");
    }

    /** Request-owned preparation. Snapshots/JSON cannot construct this capability. */
    static final class Prepared {
        private final GeneralPm1Runtime owner;
        private final ObjectNode base, context, selection, model, preview;
        private final JsonNode association;
        private final List<String> peers;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Prepared(GeneralPm1Runtime owner, ObjectNode base, ObjectNode context, ObjectNode selection, ObjectNode model,
                JsonNode association, ObjectNode preview, List<String> peers) {
            this.owner = owner;
            this.base = base.deepCopy(); this.context = context == null ? null : context.deepCopy();
            this.selection = selection == null ? null : selection.deepCopy(); this.model = model == null ? null : model.deepCopy();
            this.association = association == null ? null : association.deepCopy(); this.preview = preview.deepCopy();
            this.peers = List.copyOf(peers);
        }
        List<String> requiredPeers() { return peers; }
        ObjectNode projection() { return base.path("sourceProjection").isObject()
                ? ((ObjectNode) base.path("sourceProjection")).deepCopy() : null; }
    }
    public int evaluations() { return evaluations; }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId) {
        return evaluate(prepare(selected, candidateId, hypothesisId), null, null);
    }
    Prepared prepare(SelectedEvidencePreparation selected, String candidateId, String hypothesisId) {
        var out = mapper.createObjectNode().put("candidateId", candidateId).put("hypothesisId", hypothesisId);
        try {
            var projection = selected.legacyProjection(candidateId, hypothesisId); out.set("sourceProjection", projection.snapshot());
            return prepare(projection.context(), projection.queryCandidate(), out);
        } catch (RuntimeException invalid) { return refused(out, safeReason(invalid)); }
    }
    ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode out) {
        return evaluate(prepare(context, candidate, out), null, null);
    }
    private Prepared refused(ObjectNode out, String reason) {
        return new Prepared(this, out, null, null, null, null, refusal(reason), List.of());
    }
    private ObjectNode refusal(String reason) {
        return mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", reason).put("appliedStrength", "NONE");
    }
    private Prepared prepare(ObjectNode context, VariantInterpretation candidate, ObjectNode out) {
        out.set("policy", identity()); out.put("contextId", context.path("contextId").asText());
        try {
            var deletionScope = "SMALL_DELETION".equals(context.at("/variant/variantType").asText())
                    ? PublishedPm1Deletion.scope(context) : null;
            if (deletionScope != null) {
                var tx = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper))
                        .selectedVepConsequence(context, candidate);
                if (tx != null && PublishedPm1Deletion.includesDeletion(SelectedCriterionRouting.consequenceTerms(tx.getConsequence()))) {
                    var result = PublishedPm1Deletion.assess(mapper, deletionScope, context, candidate, tx, materials, out);
                    return new Prepared(this, out, context, null, null, null, result, List.of());
                }
            }
            var supplied = materials.resolve(context, candidate); out.set("sequenceMaterial", supplied.snapshot());
            var model = supplied.model();
            if (!supplied.ready() || model == null) return refused(out,
                    supplied.snapshot().path("reasonCode").asText("PM1_SEQUENCE_MATERIAL_UNAVAILABLE"));
            // Public identity comes from the verified model, not an annotation or the allele delta.
            out.withObject("/sequenceMaterial").set("transcript", model.path("transcript").deepCopy());
            out.withObject("/sequenceMaterial").set("protein", model.path("protein").deepCopy());
            var selection = knowledge.selectForVerifiedMissense(CRITERION, context, supplied); out.set("knowledgeSelection", selection);
            var association = supplied.snapshot().path("alleleAssociation");
            if (!"READY".equals(selection.path("status").asText())) {
                String reason = selection.path("reasonCode").asText();
                if (Pm1ReviewedRegions.ABSENT_PUBLISHED_BACKGROUND.equals(reason)) {
                    var reviewed = reviewedRegions == null ? null : reviewedRegions.get().assess(context, model, association);
                    if (reviewed != null && reviewed.path("reviewedRegion").isObject())
                        out.set("reviewedRegion", reviewed.path("reviewedRegion").deepCopy());
                    var result = reviewed == null ? refusal(reason) : reviewed;
                    return new Prepared(this, out, context, null, model, association, result, List.of());
                }
                return refused(out, reason);
            }
            // No acquisition in the planner. Invalid source/sequence, non-matches and explicit
            // exclusions never cause another criterion to be prepared just for PM1.
            var preview = assessment.assess(selection, model, association, context, null);
            var peers = new java.util.ArrayList<String>();
            if ("SUPPORT".equals(preview.at("/selectedRule/effect").asText())
                    && "PM1_PUBLISHED_PEER_RESULT_UNAVAILABLE".equals(preview.path("reasonCode").asText()))
                for (var relation : selection.at("/record/interpretation/crossCriterionRelations"))
                    if (!"NOT_MUTUALLY_EXCLUSIVE".equals(relation.path("relation").asText())) peers.add(relation.path("criterionId").asText());
            return new Prepared(this, out, context, selection, model, association, preview, peers);
        } catch (RuntimeException invalid) { return refused(out, safeReason(invalid)); }
    }
    ObjectNode evaluate(Prepared prepared, PublishedPm1CrossCriterionConditions.Facts facts, String sourceSha256) {
        if (prepared.base.has("contextId")) evaluations++;
        var out = prepared.base.deepCopy();
        if (sourceSha256 != null) out.put("executionSourceSha256", sourceSha256);
        if (prepared.owner != this) return finish(out, "NOT_EVALUATED", "PM1_PREPARATION_OWNER_MISMATCH", "NONE");
        if (!prepared.peers.isEmpty() && facts != null && !PublishedPm1CrossCriterionConditions.sameCurrentExecution(
                facts, prepared.projection(), sourceSha256, mapper))
            return finish(out, "NOT_EVALUATED", "PM1_PEER_SOURCE_SNAPSHOT_MISMATCH", "NONE");
        var result = prepared.preview.deepCopy();
        if (prepared.selection != null && !prepared.peers.isEmpty())
            result = assessment.assess(prepared.selection, prepared.model, prepared.association, prepared.context, facts);
        if (prepared.selection != null) out.set("regionAssessment", result);
        if (clinvarIntake != null && prepared.selection == null && prepared.context != null && prepared.model != null
                && prepared.association != null && !"PM1_REVIEWED_REGION_SPECIALIZATION_UNRESOLVED".equals(result.path("reasonCode").asText()))
            out.set("clinvarIntake", clinvarIntake.collect(prepared.context, prepared.model, prepared.association, out.path("reviewedRegion")));
        return finish(out, result.path("status").asText(), result.path("reasonCode").asText(), result.path("appliedStrength").asText());
    }
    private ObjectNode finish(ObjectNode out, String status, String reason, String strength) {
        out.put("status", status).put("reasonCode", reason);
        var evaluation = out.putObject("evaluation").put("criterionId", CRITERION).put("appliedStrength", "MET".equals(status) ? strength : "NONE");
        evaluation.putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        out.set("publicSummary", publicSummary(out)); return out;
    }
    public static ObjectNode publicSummary(ObjectNode out) {
        var summary = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("schemaVersion", "pm1-general-facts-v1")
                .put("policyVersion", VERSION).put("scope", "PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS")
                .put("clinicalValidationCompleted", false).put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION")
                .put("benignCountsInferred", false).put("automaticNegativeFromNoHit", false);
        for (String field : List.of("transcript", "protein")) {
            var value = out.path("sequenceMaterial").path(field); if (!value.isMissingNode()) summary.set(field, value.deepCopy());
        }
        for (String field : List.of("proteinPosition", "refAminoAcid", "altAminoAcid")) {
            var value = out.at("/sequenceMaterial/alleleAssociation/" + field); if (!value.isMissingNode()) summary.set(field, value.deepCopy());
        }
        for (String field : List.of("declaredTranscript", "executionTranscript")) {
            var value = out.path("knowledgeSelection").path(field); if (!value.isMissingNode()) summary.set(field, value.deepCopy());
        }
        for (String field : List.of("sourceDisposition", "sourceReasonCode", "sourceClausePointer")) {
            var value = out.path("knowledgeSelection").path(field); if (value.isTextual()) summary.set(field, value.deepCopy());
        }
        var key = out.at("/knowledgeSelection/record/specificationKey"); if (key.isTextual()) summary.set("specificationKey", key.deepCopy());
        var selected = out.at("/regionAssessment/selectedRule"); if (selected.isObject()) summary.set("selectedRule", selected.deepCopy());
        var deletion = out.path("proteinDeletion");
        if (deletion.isObject()) summary.set("proteinDeletion", deletion.deepCopy());
        var reviewed = out.path("reviewedRegion");
        if (reviewed.isObject()) {
            summary.put("scope", "PROJECT_RESEARCH_REVIEWED_REGIONS").put("qualificationBasis", "REVIEWED_REGIONAL_EVIDENCE");
            summary.set("reviewedRegion", reviewed.deepCopy());
        }
        var clinvarIntake = out.path("clinvarIntake");
        if (clinvarIntake.isObject()) summary.set("clinvarIntake", clinvarIntake.deepCopy());
        for (String field : List.of("conditionChecks", "crossCriterionRelations", "crossCriterionConditionCheck", "sourceOverlapResolution")) {
            var value = out.path("regionAssessment").path(field);
            if (value.isArray() || value.isObject()) summary.set(field, value.deepCopy());
        }
        if (out.path("peerPreparationIssues").isArray()) summary.set("peerPreparationIssues", out.path("peerPreparationIssues").deepCopy());
        if (out.path("executionSourceSha256").asText().matches("[a-f0-9]{64}")) {
            var proof = summary.putObject("currentEvaluationBinding").put("schemaVersion", "pm1-current-evaluation-binding-v1")
                    .put("executionSourceSha256", out.path("executionSourceSha256").asText())
                    .put("candidateId", out.path("candidateId").asText()).put("hypothesisId", out.path("hypothesisId").asText())
                    .put("contextId", out.path("contextId").asText())
                    .put("projectionBindingSha256", out.at("/sourceProjection/bindingSha256").asText());
            proof.put("originalContextId", out.at("/sourceProjection/originalContext/contextId").asText())
                    .put("evaluationContextId", out.path("contextId").asText());
            var hasher = new RuntimeEvidenceV2Adapter(new ObjectMapper());
            if (reviewed.isObject()) proof.put("reviewedRegionSha256", hasher.canonicalSha256(reviewed));
            if (clinvarIntake.isObject()) proof.put("clinvarIntakeSha256", hasher.canonicalSha256(clinvarIntake));
            if (out.at("/sourceProjection/originalContext").isObject()) {
                proof.put("originalContextSha256", hasher.canonicalSha256(out.at("/sourceProjection/originalContext")))
                        .put("evaluationContextSha256", hasher.canonicalSha256(out.at("/sourceProjection/evaluationContext")))
                        .put("queryAlleleSha256", hasher.canonicalSha256(out.at("/sourceProjection/evaluationContext/variant/evidenceQueryAllele")));
            }
            var evaluation = out.path("evaluation");
            proof.put("status", evaluation.at("/evaluationResult/status").asText())
                    .put("appliedStrength", evaluation.path("appliedStrength").asText())
                    .put("reasonCode", evaluation.at("/evaluationResult/reasonCode").asText());
        }
        return summary;
    }
    static String safeReason(RuntimeException invalid) {
        String message = invalid.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{3,160}") ? message : "PM1_RUNTIME_INPUT_INVALID";
    }
}
