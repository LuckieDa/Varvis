package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finite application metadata, inspected only AFTER the existing selector. No thresholds. */
final class PredictionApplicationPolicy {
    private PredictionApplicationPolicy() { }

    /** Separate finite vocabulary; never inject application into a closed clause policy. */
    static final class ClauseSidecar {
        private final ObjectNode value;
        private final String issue;
        private ClauseSidecar(ObjectNode value, String issue) { this.value = value.deepCopy(); this.issue = issue; }
        ObjectNode json() { return value.deepCopy(); }
        String issue() { return issue; }
    }

    static ClauseSidecar clause(ObjectMapper mapper, JsonNode policy, SavedPredictionApplicationInputs.Input input, String criterion) {
        var hash = new RuntimeEvidenceV2Adapter(mapper);
        ObjectNode empty = mapper.createObjectNode();
        try {
            SavedPredictionApplicationInputs.require(input.sourceKind() == SavedPredictionApplicationInputs.SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1
                    && input.issue().isEmpty(), "CLAUSE_TRUSTED_SOURCE_REQUIRED");
            JsonNode settings = input.settings(); String policySha = hash.canonicalSha256(policy);
            JsonNode list = settings.path("clauseApplicationSidecars"), selected = null;
            require(list.isArray(), "CLAUSE_APPLICATION_SIDECAR_MISSING");
            for (JsonNode sidecar : list) {
                require(sidecar.isObject(), "CLAUSE_APPLICATION_SIDECAR_INVALID");
                if (!policySha.equals(sidecar.path("policySha256").asText())) continue;
                require(selected == null, "CLAUSE_APPLICATION_SIDECAR_DUPLICATE"); selected = sidecar;
            }
            if (!(selected instanceof ObjectNode sidecar)) throw new IllegalArgumentException("CLAUSE_APPLICATION_SIDECAR_MISSING");
            fields(sidecar, Set.of("schemaVersion", "policyId", "policyVersion", "policySha256", "template", "templateSha256", "adoptionId", "modelProofId"));
            require("prediction-clause-application-sidecar-v1".equals(sidecar.path("schemaVersion").asText())
                    && policy.path("policyId").equals(sidecar.path("policyId")) && policy.path("policyVersion").equals(sidecar.path("policyVersion")), "CLAUSE_APPLICATION_SIDECAR_BINDING_MISMATCH");
            JsonNode template = sidecar.path("template");
            fields(template, Set.of("templateId", "version", "criteria", "mechanisms", "requiredCoverage"));
            require(SavedPredictionApplicationInputs.nonblank(template, "templateId", "version")
                    && hash.canonicalSha256(template).equals(sidecar.path("templateSha256").asText()), "APPLICATION_TEMPLATE_BINDING_INVALID");
            require(template.path("criteria").isObject() && template.path("criteria").size() == 1
                    && template.path("mechanisms").isObject() && template.path("mechanisms").size() == 1, "CLAUSE_MECHANISM_PLAN_UNSUPPORTED");
            JsonNode plan = template.path("criteria").path(criterion);
            fields(plan, Set.of("mode", "mechanisms"));
            require("SINGLE".equals(plan.path("mode").asText()) && plan.path("mechanisms").isArray() && plan.path("mechanisms").size() == 1
                    && plan.path("mechanisms").get(0).isTextual(), "CLAUSE_MECHANISM_PLAN_UNSUPPORTED");
            JsonNode mechanism = template.path("mechanisms").path(plan.path("mechanisms").get(0).asText());
            fields(mechanism, Set.of("sourceKind", "model", "policyBinding", "compatibilityId", "strengthMap"));
            require(input.sourceKind().name().equals(mechanism.path("sourceKind").asText()) && "REVEL".equals(mechanism.path("model").asText())
                    && SavedPredictionApplicationInputs.nonblank(mechanism, "compatibilityId"), "CLAUSE_MECHANISM_BINDING_INVALID");
            fields(mechanism.path("policyBinding"), Set.copyOf(BINDING));
            for (String field : BINDING) require(mechanism.path("policyBinding").path(field).equals(sidecar.path(field)), "CLAUSE_MECHANISM_BINDING_INVALID");
            require(mechanism.path("strengthMap").isObject() && mechanism.path("strengthMap").size() == 1
                    && "SUPPORTING".equals(mechanism.at("/strengthMap/SUPPORTING").asText()), "CLAUSE_SUPPORTING_STRENGTH_MAP_REQUIRED");
            JsonNode adoption = settings.path("adoptions").path(sidecar.path("adoptionId").asText());
            reviewedReference(adoption, "LOCAL_APPLICATION_ADOPTION_REQUIRED");
            for (String field : BINDING) require(adoption.path(field).equals(sidecar.path(field)), "LOCAL_APPLICATION_ADOPTION_REQUIRED");
            for (JsonNode proof : java.util.List.of(adoption, settings.path("modelProofs").path(sidecar.path("modelProofId").asText()))) {
                reviewedReference(proof, "MODEL_SELECTION_PROOF_MISSING");
                for (String field : BINDING) require(proof.path(field).equals(sidecar.path(field)), "MODEL_SELECTION_PROOF_MISSING");
                require(proof.path("templateSha256").equals(sidecar.path("templateSha256"))
                        && input.document("run").path("runId").equals(proof.path("sourceRunId"))
                        && settings.at("/task/purpose").equals(proof.path("purpose"))
                        && policySha.equals(proof.path("policySha256").asText()), "MODEL_SELECTION_PROOF_MISSING");
            }
            JsonNode proof = settings.path("modelProofs").path(sidecar.path("modelProofId").asText());
            require(Set.of("PRESELECTED", "REVIEWED_SAVED_REUSE").contains(proof.path("kind").asText())
                    && proof.path("preparationIds").isArray() && !proof.path("preparationIds").isEmpty(), "MODEL_SELECTION_PROOF_MISSING");
            require(template.path("requiredCoverage").isArray() && !template.path("requiredCoverage").isEmpty(), "EFFECT_REQUIREMENT_MISSING");
            for (JsonNode name : template.path("requiredCoverage")) require(name.isTextual() && !name.asText().isBlank(), "EFFECT_REQUIREMENT_MISSING");
            return new ClauseSidecar(sidecar, "");
        } catch (RuntimeException invalid) { return new ClauseSidecar(empty, SavedPredictionApplicationInputs.safeClauseIssue(invalid)); }
    }

    static void reviewedReference(JsonNode value, String reason) {
        require("REVIEWED".equals(value.path("status").asText()) && SavedPredictionApplicationInputs.nonblank(value, "reviewId", "reviewerId", "version", "sourceSha256")
                && value.path("sourceSha256").asText().matches("[0-9a-f]{64}"), reason);
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        require(value.isObject(), "CLAUSE_METADATA_OBJECT_REQUIRED");
        for (var names = value.fieldNames(); names.hasNext();) require(allowed.contains(names.next()), "CLAUSE_METADATA_FIELD_UNSUPPORTED");
    }
    private static void require(boolean valid, String reason) { SavedPredictionApplicationInputs.require(valid, reason); }
    private static final List<String> BINDING = List.of("policyId", "policyVersion", "policySha256");

    static String issue(ObjectMapper mapper, JsonNode policy, JsonNode settings, String criterion) {
        var hash = new RuntimeEvidenceV2Adapter(mapper);
        JsonNode app = policy.path("application"), template = app.path("template");
        if (!"prediction-application-policy-v1".equals(app.path("schemaVersion").asText())) return "APPLICATION_POLICY_SCHEMA_UNSUPPORTED";
        if (!SavedPredictionApplicationInputs.nonblank(template, "templateId", "version")
                || !hash.canonicalSha256(template).equals(app.path("templateSha256").asText())) return "APPLICATION_TEMPLATE_BINDING_INVALID";
        JsonNode adoption = settings.path("adoptions").path(app.path("adoptionId").asText());
        if (!"REVIEWED".equals(adoption.path("status").asText())
                || !SavedPredictionApplicationInputs.nonblank(adoption, "reviewId", "reviewerId", "version")
                || !adoption.path("policyId").equals(policy.path("policyId"))
                || !adoption.path("policyVersion").equals(policy.path("policyVersion"))
                || !hash.canonicalSha256(policy).equals(adoption.path("policySha256").asText())) return "LOCAL_APPLICATION_ADOPTION_REQUIRED";
        JsonNode plan = template.path("criteria").path(criterion);
        if (!Set.of("SINGLE", "PRIORITY_OR", "ALL", "MISSING_FALLBACK").contains(plan.path("mode").asText())) return "MECHANISM_COMBINATION_UNSUPPORTED";
        JsonNode order = plan.path("mechanisms");
        if (!order.isArray() || order.isEmpty() || "SINGLE".equals(plan.path("mode").asText()) && order.size() != 1
                || "MISSING_FALLBACK".equals(plan.path("mode").asText()) && order.size() != 2) return "MECHANISM_PLAN_INVALID";
        Set<String> names = new HashSet<>();
        for (JsonNode name : order) {
            if (!name.isTextual() || name.asText().isBlank() || !names.add(name.asText())) return "MECHANISM_PLAN_INVALID";
            JsonNode mechanism = template.path("mechanisms").path(name.asText());
            if (!SavedPredictionApplicationInputs.nonblank(mechanism, "model", "calibrationId", "calibrationVersion", "calibrationContractSha256", "compatibilityId")
                    || !mechanism.path("strengthMap").isObject()) return "MECHANISM_BINDING_MISSING";
            for (JsonNode mapped : mechanism.path("strengthMap"))
                if (!Set.of("SUPPORTING", "MODERATE", "STRONG").contains(mapped.asText())) return "STRENGTH_MAPPING_UNSUPPORTED";
        }
        if ("ALL".equals(plan.path("mode").asText()) && !names.contains(plan.path("strengthMechanism").asText())) return "STRENGTH_MECHANISM_MISSING";
        if ("MISSING_FALLBACK".equals(plan.path("mode").asText())
                && (!plan.path("fallbackReasons").isArray() || plan.path("fallbackReasons").isEmpty())) return "FALLBACK_REASON_PLAN_MISSING";
        if (plan.path("fallbackReasons").isArray()) for (JsonNode reason : plan.path("fallbackReasons"))
            if (!"NO_RECORD".equals(reason.asText())) return "FALLBACK_REASON_UNSUPPORTED";
        JsonNode proof = settings.path("modelProofs").path(app.path("modelProofId").asText());
        if (!Set.of("PRESELECTED", "REVIEWED_SAVED_REUSE").contains(proof.path("kind").asText())
                || !SavedPredictionApplicationInputs.nonblank(proof, "reviewId", "reviewerId", "sourceSha256")
                || !proof.path("templateSha256").equals(app.path("templateSha256"))
                || !proof.path("sourceRunId").equals(settings.path("runId"))) return "MODEL_SELECTION_PROOF_MISSING";
        if (!template.path("requiredCoverage").isArray() || template.path("requiredCoverage").isEmpty()) return "EFFECT_REQUIREMENT_MISSING";
        return "";
    }
}
