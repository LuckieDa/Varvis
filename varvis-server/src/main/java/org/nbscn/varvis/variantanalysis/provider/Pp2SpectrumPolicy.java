package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Finite reviewed mechanism/spectrum contract; no inference from prediction or constraint scores. */
final class Pp2SpectrumPolicy {
    private final RuntimeEvidenceV2Adapter adapter;
    Pp2SpectrumPolicy(RuntimeEvidenceV2Adapter adapter) { this.adapter = adapter; }

    String validate(ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode model, ObjectNode material) {
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope",
                "appliedStrength", "transcriptModelBinding", "spectrumMaterialBinding", "scientificSources", "authoritySourceIds", "specification")))
            return "PP2_POLICY_OPERATOR_UNSUPPORTED";
        if (!"pp2-reviewed-missense-policy-v1".equals(policy.path("schemaVersion").asText())
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText()) || policy.at("/review/reviewId").asText().isBlank()
                || !"SUPPORTING".equals(policy.path("appliedStrength").asText())
                || policy.path("criterionIds").size() != 1 || !Pp2CriterionEvaluator.CRITERION.equals(policy.at("/criterionIds/0").asText()))
            return "PP2_POLICY_INVALID";
        if (!evaluationIdentityValid(context, policy, selection)) return "PP2_SELECTION_OR_AUTHORITY_INVALID";
        JsonNode scope = policy.path("scope");
        if (!only(scope, Set.of("hgncId", "mondoId", "moi", "assembly", "variantType", "transcriptMode", "transcripts"))
                || !context.at("/gene/hgncId").equals(scope.path("hgncId")) || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi")) || !"GRCh38".equals(scope.path("assembly").asText())
                || !"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText())
                || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText()) || scope.path("transcripts").size() != 1
                || !context.at("/transcript/accession").equals(scope.at("/transcripts/0/accession"))
                || !context.at("/transcript/version").equals(scope.at("/transcripts/0/version"))) return "PP2_POLICY_CONTEXT_MISMATCH";
        if (!bound(policy.path("transcriptModelBinding"), model, "modelId", "modelVersion")) return "PP2_MODEL_IDENTITY_INVALID";
        if (!bound(policy.path("spectrumMaterialBinding"), material, "materialId", "materialVersion")) return "PP2_SPECTRUM_IDENTITY_INVALID";
        if (!only(material, Set.of("schemaVersion", "materialId", "materialVersion", "modelSha256", "scope", "assembly", "contig",
                "hgncId", "transcript", "protein", "sourceRecords", "conditions", "coverage", "mechanismStatus"))
                || !"pp2-reviewed-spectrum-v1".equals(material.path("schemaVersion").asText())
                || !adapter.canonicalSha256(model).equals(material.path("modelSha256").asText())
                || !scope.equals(material.path("scope"))) return "PP2_SPECTRUM_IDENTITY_INVALID";
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein"))
            if (!material.path(field).equals(model.path(field))) return "PP2_SPECTRUM_IDENTITY_INVALID";
        String issue = CodingTranscriptMapping.modelIssue(model, context);
        if (issue != null) return issue.replace("CODING_TRANSCRIPT_MODEL_", "PP2_MODEL_");
        if (!CodingTranscriptMapping.modelSourcesBound(model, policy.path("scientificSources"))
                || !CodingTranscriptMapping.sourcesValid(material.path("sourceRecords"))) return "PP2_SOURCE_INVALID";
        for (JsonNode source : material.path("sourceRecords")) {
            boolean found = false;
            for (JsonNode trusted : policy.path("scientificSources")) if (source.equals(trusted)) found = true;
            if (!found || source.path("locator").asText().isBlank()) return "PP2_SOURCE_INVALID";
        }
        if (!"WHOLE_GENE_EXACT_DISEASE_MOI_TRANSCRIPT".equals(material.path("coverage").asText())) return "PP2_COVERAGE_UNSUPPORTED";
        if (!"SINGLE_REVIEWED_MECHANISM".equals(material.path("mechanismStatus").asText())) return "PP2_MECHANISM_UNRESOLVED";
        JsonNode spec = policy.path("specification");
        if (!only(spec, Set.of("authoritySourceId", "version", "locator", "pp2Allowed", "strengthCap", "exceptions", "extraConditions", "exceptionReview"))
                || !"SUPPORTING".equals(spec.path("strengthCap").asText()) || spec.path("locator").asText().isBlank()
                || !spec.path("pp2Allowed").isBoolean()) return "PP2_SPECIFICATION_INVALID";
        boolean authority = false;
        for (JsonNode source : policy.path("scientificSources"))
            if (spec.path("authoritySourceId").equals(source.path("sourceId")) && spec.path("version").equals(source.path("version")))
                for (JsonNode id : policy.path("authoritySourceIds")) if (id.equals(source.path("sourceId"))) authority = true;
        if (!authority) return "PP2_SPECIFICATION_AUTHORITY_INVALID";
        if (!spec.path("pp2Allowed").asBoolean()) return "PP2_SPECIFICATION_DISABLED";
        // Only reviewed absence of applicable exceptions is implemented. No opaque allow flag bypasses extra science.
        if (!spec.path("exceptions").isArray() || !spec.path("exceptions").isEmpty()
                || !spec.path("extraConditions").isArray() || !spec.path("extraConditions").isEmpty()) return "PP2_ADDITIONAL_CONDITIONS_UNSUPPORTED";
        if (!reviewValid(spec.path("exceptionReview"), policy.path("scientificSources"), "REVIEWED_NO_APPLICABLE_EXCEPTIONS", false))
            return "PP2_EXCEPTION_REVIEW_MISSING";
        JsonNode conditions = material.path("conditions");
        if (!only(conditions, Set.of("missenseMechanism", "lowBenignMissense")) || conditions.size() != 2) return "PP2_CONDITIONS_MISSING";
        // Validate both assertions completely before considering either known negative.
        for (String key : List.of("missenseMechanism", "lowBenignMissense")) {
            JsonNode row = conditions.path(key);
            if (!only(row, Set.of("conclusion", "basisType", "sourceIds", "clauseLocator", "statistics", "review"))) return "PP2_CONDITION_OPERATOR_UNSUPPORTED";
            if (!Set.of("SUPPORTED", "NOT_SUPPORTED").contains(row.path("conclusion").asText())) return "PP2_CONDITION_UNKNOWN_OR_CONFLICT";
            if (!sourceIds(row.path("sourceIds"), material.path("sourceRecords"))) return "PP2_CONDITION_SOURCE_MISSING";
            String type = row.path("basisType").asText();
            if ("AUTHORITY_CLAUSE".equals(type)) {
                if (row.path("clauseLocator").asText().isBlank() || row.has("statistics")) return "PP2_CLAUSE_LOCATOR_MISSING";
            } else if ("REVIEWED_COHORT".equals(type)) {
                JsonNode stats = row.path("statistics");
                if (!only(stats, Set.of("population", "cohort", "sampleInclusion", "variantInclusion", "denominator", "count", "countLocator",
                        "benignClassificationBasis", "deduplication", "selectionBiasReview", "scopeApplicability", "limitations"))) return "PP2_STATISTICS_INVALID";
                for (String field : List.of("population", "cohort", "sampleInclusion", "variantInclusion", "benignClassificationBasis", "deduplication", "limitations"))
                    if (!stats.path(field).isTextual() || stats.path(field).asText().isBlank()) return "PP2_STATISTICS_INCOMPLETE";
                if (!"REVIEWED_APPLICABLE".equals(stats.path("selectionBiasReview").asText())
                        || !"WHOLE_GENE_EXACT_SCOPE_REVIEWED".equals(stats.path("scopeApplicability").asText())) return "PP2_STATISTICS_APPLICABILITY_UNREVIEWED";
                boolean counts = stats.has("count") || stats.has("denominator");
                if (counts && (!stats.path("count").isIntegralNumber() || !stats.path("denominator").isIntegralNumber()
                        || !stats.path("count").canConvertToLong() || !stats.path("denominator").canConvertToLong()
                        || stats.path("count").asLong() < 0 || stats.path("denominator").asLong() <= 0
                        || stats.path("count").asLong() > stats.path("denominator").asLong())) return "PP2_STATISTICS_INVALID";
                if (!counts && stats.path("countLocator").asText().isBlank()) return "PP2_STATISTICS_INCOMPLETE";
            } else return "PP2_CONDITION_BASIS_UNSUPPORTED";
            if (!reviewValid(row.path("review"), material.path("sourceRecords"), "REVIEWED_APPLICABLE", true)) return "PP2_CONDITION_REVIEW_MISSING";
            if (!adapter.canonicalSha256(reviewProjection(policy, material, key)).equals(row.at("/review/contentSha256").asText()))
                return "PP2_CONDITION_REVIEW_IDENTITY_MISMATCH";
        }
        return null;
    }

    ObjectNode reviewProjection(ObjectNode policy, ObjectNode material, String key) {
        ObjectNode content = material.deepCopy(); content.remove("conditions");
        ObjectNode policyContent = policy.deepCopy(); policyContent.remove("spectrumMaterialBinding");
        ObjectNode facts = ((ObjectNode) material.path("conditions").path(key)).deepCopy(); facts.remove("review");
        content.set("policy", policyContent); content.put("condition", key); content.set("facts", facts); return content;
    }
    String resolve(ObjectNode material, ObjectNode observation) {
        observation.set("reviewedConditions", material.path("conditions").deepCopy());
        return Pp2ConditionDecision.reason(material.path("conditions"));
    }
    boolean evaluationIdentityValid(ObjectNode context, ObjectNode policy, ObjectNode receipt) {
        ObjectNode identity = receipt.deepCopy(); identity.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(identity)).equals(receipt.path("selectionId").asText())
                || !context.path("contextId").equals(receipt.path("contextId")) || !policy.path("policyId").equals(receipt.path("policyId"))
                || !policy.path("policyVersion").equals(receipt.path("policyVersion")) || !policy.path("criterionIds").equals(receipt.path("criterionIds"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText())
                || policy.path("policyId").asText().isBlank() || policy.path("policyVersion").asText().isBlank()
                || !CodingTranscriptMapping.sourcesValid(policy.path("scientificSources"))
                || policy.path("authoritySourceIds").size() != 1
                || !"authority-acmg-amp-2015-richards".equals(policy.at("/authoritySourceIds/0").asText())) return false;
        for (JsonNode source : policy.path("scientificSources")) if (source.path("locator").asText().isBlank()) return false;
        return sourceIds(policy.path("authoritySourceIds"), policy.path("scientificSources"));
    }
    private boolean bound(JsonNode binding, JsonNode material, String id, String version) {
        return !material.path(id).asText().isBlank() && !material.path(version).asText().isBlank()
                && binding.path(id).equals(material.path(id)) && binding.path(version).equals(material.path(version))
                && adapter.canonicalSha256(material).equals(binding.path("canonicalSha256").asText());
    }
    private static boolean reviewValid(JsonNode review, JsonNode sources, String status, boolean digest) {
        if (!only(review, Set.of("status", "reviewer", "evaluatedAt", "basis", "sourceIds", "contentSha256"))
                || !status.equals(review.path("status").asText()) || review.path("reviewer").asText().isBlank()
                || review.path("basis").asText().isBlank() || !sourceIds(review.path("sourceIds"), sources)
                || digest && !review.path("contentSha256").asText().matches("[0-9a-f]{64}")) return false;
        try { java.time.LocalDate.parse(review.path("evaluatedAt").asText()); return true; }
        catch (java.time.DateTimeException invalid) { return false; }
    }
    private static boolean sourceIds(JsonNode ids, JsonNode sources) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        var seen = new java.util.HashSet<String>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || !seen.add(id.asText())) return false;
            boolean found = false; for (JsonNode source : sources) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }
    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
}
