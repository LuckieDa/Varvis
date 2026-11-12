package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Set;

/** Start-loss identity checks deliberately do not require PTC or NMD junction conditions. */
final class Pvs1StartLossPolicy {
    static final String SCHEMA = "pvs1-start-loss-policy-v1";
    static final String ALGORITHM = "CLINGEN_SVI_2018_START_LOSS";
    private Pvs1StartLossPolicy() { }

    @org.springframework.lang.Nullable
    static String bindingIssue(ObjectNode context, ObjectNode policy, ObjectNode receipt,
                               ObjectNode model, RuntimeEvidenceV2Adapter adapter) {
        ObjectNode identity = receipt.deepCopy(); identity.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(identity)).equals(receipt.path("selectionId").asText())
                || !context.path("contextId").equals(receipt.path("contextId"))
                || !singleCriterion(receipt.path("criterionIds")) || !singleCriterion(policy.path("criterionIds"))
                || !policy.path("policyId").equals(receipt.path("policyId"))
                || !policy.path("policyVersion").equals(receipt.path("policyVersion"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText())
                || receipt.path("catalogId").asText().isBlank() || receipt.path("catalogVersion").asText().isBlank()
                || !receipt.path("catalogSha256").asText().matches("[0-9a-f]{64}")
                || receipt.path("catalogEntryId").asText().isBlank() || receipt.path("searchScope").asText().isBlank())
            return "PVS1_START_LOSS_POLICY_SELECTION_IDENTITY_INVALID";
        try { Instant.parse(receipt.path("searchedAt").asText()); }
        catch (RuntimeException invalid) { return "PVS1_START_LOSS_POLICY_SELECTION_IDENTITY_INVALID"; }
        if (!"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || policy.at("/review/reviewId").asText().isBlank()) return "PVS1_START_LOSS_POLICY_UNRESOLVED";
        JsonNode scope = policy.path("scope"), transcript = scope.path("transcripts");
        if (!context.at("/gene/hgncId").equals(scope.path("hgncId"))
                || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi"))
                || !context.at("/variant/evidenceQueryAllele/assembly").equals(scope.path("assembly"))
                || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText())
                || !transcript.isArray() || transcript.size() != 1
                || !context.at("/transcript/accession").equals(transcript.path(0).path("accession"))
                || !context.at("/transcript/version").equals(transcript.path(0).path("version")))
            return "PVS1_START_LOSS_POLICY_CONTEXT_MISMATCH";
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !Set.of("NATIVE_NONPROJECTED_SNV", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT").contains(scope.path("variantType").asText()))
            return "PVS1_START_LOSS_SCOPE_UNSUPPORTED";
        if (!adapter.canonicalSha256(model).equals(policy.at("/transcriptModelBinding/canonicalSha256").asText())
                || !model.path("modelId").equals(policy.at("/transcriptModelBinding/modelId"))
                || !model.path("modelVersion").equals(policy.at("/transcriptModelBinding/modelVersion")))
            return "PVS1_START_LOSS_MODEL_BINDING_MISMATCH";
        JsonNode sources = policy.path("scientificSources");
        if (!TruncatingPvs1Model.sourcesValid(sources) || !bound(receipt.path("sourceIds"), sources)
                || !bound(policy.at("/mechanism/sourceIds"), sources)
                || policy.at("/mechanism/mechanismId").asText().isBlank()
                || !policy.path("authoritySourceIds").isArray() || policy.path("authoritySourceIds").isEmpty())
            return "PVS1_START_LOSS_POLICY_OR_LOF_REVIEW_UNAVAILABLE";
        var authorities = new java.util.HashSet<String>();
        for (var id : policy.path("authoritySourceIds"))
            if (!id.isTextual() || id.asText().isBlank() || !authorities.add(id.asText()))
                return "PVS1_START_LOSS_POLICY_OR_LOF_REVIEW_UNAVAILABLE";
        return null;
    }

    static String status(JsonNode policy) {
        JsonNode assessment = policy.path("startLossAssessment");
        if (!SCHEMA.equals(policy.path("schemaVersion").asText())
                || assessment.has("schemaVersion") && !SCHEMA.equals(assessment.path("schemaVersion").asText())
                || !"REVIEWED_IMPLEMENTED".equals(assessment.at("/review/status").asText())) return "UNRESOLVED";
        String status = assessment.path("status").asText("UNRESOLVED");
        if (!"PERMITS".equals(status)) return status;
        var fields = assessment.fieldNames();
        while (fields.hasNext()) if (!Set.of("schemaVersion", "review", "status", "algorithmId", "sourceBindings", "assessedTranscripts").contains(fields.next()))
            return "UNSUPPORTED";
        return ALGORITHM.equals(assessment.path("algorithmId").asText())
                && "MODERATE".equals(policy.path("appliedStrength").asText()) ? "PERMITS" : "UNSUPPORTED";
    }

    private static boolean bound(JsonNode ids, JsonNode sources) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        var seen = new java.util.HashSet<String>();
        for (var id : ids) {
            boolean found = false;
            if (!id.isTextual() || !seen.add(id.asText())) return false;
            for (var source : sources) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }

    private static boolean singleCriterion(JsonNode criteria) {
        return criteria.isArray() && criteria.size() == 1 && TruncatingNmdPvs1Runtime.CRITERION.equals(criteria.path(0).asText());
    }
}
