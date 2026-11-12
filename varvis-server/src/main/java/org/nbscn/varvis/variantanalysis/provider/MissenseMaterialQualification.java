package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Validates preserved external declarations or explicit source-verified research use; never invents approval. */
public final class MissenseMaterialQualification {
    public static final String VERSION = "1.0.0";
    private static final List<String> HEADER = List.of("schemaVersion", "bundleId", "bundleVersion", "review", "activation");
    private MissenseMaterialQualification() { }

    static ObjectNode preserve(ObjectMapper mapper, ObjectNode candidate, ObjectNode approval) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var metadata = candidate.deepCopy();
        metadata.remove(List.of("bundleHeader", "catalog", "policies", "missenseMaterials"));
        var proof = mapper.createObjectNode().put("schemaVersion", "missense-material-qualification-v1")
                .put("candidateSha256", adapter.canonicalSha256(candidate)).put("approvalCanonicalSha256", adapter.canonicalSha256(approval));
        proof.set("candidateMetadata", metadata); proof.set("externalApproval", approval.deepCopy());
        return proof;
    }

    static void validate(ObjectMapper mapper, ObjectNode bundle, ObjectNode proof) {
        if (MissenseResearchQualification.SCHEMA.equals(proof.path("schemaVersion").asText())) {
            MissenseResearchQualification.validate(mapper, bundle, proof);
            return;
        }
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        require("missense-material-qualification-v1".equals(proof.path("schemaVersion").asText())
                && MissenseComparisonPolicy.only(proof, Set.of("schemaVersion", "candidateSha256", "approvalCanonicalSha256", "candidateMetadata", "externalApproval"))
                && proof.path("candidateMetadata") instanceof ObjectNode && proof.path("externalApproval") instanceof ObjectNode,
                "MISSENSE_MATERIAL_QUALIFICATION_INVALID");
        var candidate = ((ObjectNode) proof.path("candidateMetadata")).deepCopy();
        require(MissenseComparisonPolicy.only(candidate, Set.of("schemaVersion", "draft", "dataClass", "purpose", "criterion", "backgrounds", "sources", "comparatorProfile", "contextUse"))
                && "missense-preparation-candidate-v1".equals(candidate.path("schemaVersion").asText())
                && candidate.path("draft").isBoolean() && candidate.path("draft").booleanValue(),
                "MISSENSE_MATERIAL_CANDIDATE_INVALID");
        var header = mapper.createObjectNode(); HEADER.forEach(field -> header.set(field, bundle.path(field).deepCopy()));
        candidate.set("bundleHeader", header); candidate.set("catalog", bundle.path("catalog").deepCopy());
        candidate.set("policies", bundle.path("policies").deepCopy()); candidate.set("missenseMaterials", bundle.at("/materials/missenseMaterials").deepCopy());
        String sha = adapter.canonicalSha256(candidate);
        var approval = proof.path("externalApproval");
        String criterion = "reviewed-rule-bundle-ps1-v3".equals(bundle.path("schemaVersion").asText())
                ? MissenseComparisonRuntime.PS1 : MissenseComparisonRuntime.PM5;
        require(sha.equals(proof.path("candidateSha256").asText()) && sha.equals(approval.path("candidateSha256").asText())
                && adapter.canonicalSha256(approval).equals(proof.path("approvalCanonicalSha256").asText())
                && "missense-preparation-approval-v1".equals(approval.path("schemaVersion").asText())
                && "REVIEWED_IMPLEMENTED".equals(approval.path("status").asText()) && !approval.path("reviewId").asText().isBlank()
                && criterion.equals(candidate.path("criterion").asText()) && criterion.equals(approval.path("criterion").asText())
                && candidate.path("backgrounds").equals(approval.path("backgrounds"))
                && candidate.path("dataClass").equals(approval.path("dataClass")), "MISSENSE_MATERIAL_APPROVAL_BINDING_MISMATCH");
        String dataClass = candidate.path("dataClass").asText();
        require(Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(dataClass), "MISSENSE_MATERIAL_DATA_CLASS_INVALID");
        if ("SYNTHETIC".equals(dataClass)) require("TEST_ONLY".equals(candidate.path("purpose").asText()), "SYNTHETIC_TEST_ONLY_REQUIRED");
        if (candidate.has("contextUse")) {
            validateUse(candidate.path("contextUse"), dataClass, candidate.path("purpose").asText());
            require(candidate.path("contextUse").equals(approval.path("contextUse")), "MISSENSE_CONTEXT_USE_NOT_APPROVED");
        } else require(!approval.has("contextUse"), "MISSENSE_CONTEXT_USE_NOT_IN_CANDIDATE");
    }

    static void validateUse(JsonNode use, String dataClass, String purpose) {
        require(use.isObject() && MissenseComparisonPolicy.only(use, Set.of("schemaVersion", "allowedDataClasses", "purpose"))
                && "missense-context-use-v1".equals(use.path("schemaVersion").asText())
                && "PROJECT_RESEARCH".equals(use.path("purpose").asText())
                && use.path("allowedDataClasses").isArray() && !use.path("allowedDataClasses").isEmpty(), "MISSENSE_CONTEXT_USE_INVALID");
        Set<String> seen = new HashSet<>();
        for (var item : use.path("allowedDataClasses")) {
            String target = item.asText();
            require(item.isTextual() && Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(target) && seen.add(target), "MISSENSE_CONTEXT_USE_INVALID");
            if ("PRIVACY_BOUNDED_RUNTIME".equals(target))
                require("PUBLIC_REFERENCE".equals(dataClass) && !purpose.isBlank() && !"TEST_ONLY".equals(purpose), "MISSENSE_SYNTHETIC_RUNTIME_USE_FORBIDDEN");
        }
    }

    @Nullable static String issue(@Nullable ObjectNode proof, JsonNode context) {
        if (proof == null) return "REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE";
        var metadata = proof.path("candidateMetadata"); String target = context.path("dataClass").asText();
        if (!metadata.has("contextUse")) return "SYNTHETIC".equals(target) && "SYNTHETIC".equals(metadata.path("dataClass").asText())
                ? null : "MISSENSE_CONTEXT_USE_NOT_APPROVED";
        for (var permitted : metadata.at("/contextUse/allowedDataClasses")) if (target.equals(permitted.asText())) return null;
        return "MISSENSE_CONTEXT_DATA_CLASS_NOT_APPROVED";
    }
    private static void require(boolean allowed, String reason) { if (!allowed) throw new IllegalArgumentException(reason); }
}
