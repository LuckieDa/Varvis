package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Finite pinned BP3 region and independent functional applicability contract. No scientific approval is inferred. */
final class Bp3RegionPolicy {
    private static final String CRITERION = Bp3CriterionEvaluator.CRITERION;
    private final RuntimeEvidenceV2Adapter adapter;
    Bp3RegionPolicy(RuntimeEvidenceV2Adapter adapter) { this.adapter = adapter; }
    String validate(ObjectNode context, ObjectNode policy, ObjectNode receipt, ObjectNode model, ObjectNode regions) {
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope", "appliedStrength",
                "transcriptModelBinding", "regionMaterialBinding", "scientificSources", "authoritySourceIds", "branch", "allowBp3", "cdsSha256", "proteinSha256"))
                || !only(policy.path("scope"), Set.of("hgncId", "mondoId", "moi", "assembly", "variantType", "transcriptMode", "transcripts")))
            return "BP3_POLICY_OPERATOR_UNSUPPORTED";
        if (!"bp3-simple-inframe-policy-v1".equals(policy.path("schemaVersion").asText())
                || !"SIMPLE_INFRAME_INDEL_REVIEWED_REPEAT".equals(policy.path("branch").asText())
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || policy.at("/review/reviewId").asText().isBlank()
                || !"SUPPORTING".equals(policy.path("appliedStrength").asText())
                || policy.path("criterionIds").size() != 1 || !CRITERION.equals(policy.at("/criterionIds/0").asText()))
            return "BP3_POLICY_INVALID";
        if (!policy.path("allowBp3").isBoolean() || !policy.path("allowBp3").asBoolean()) return "BP3_POLICY_DISABLED";
        ObjectNode receiptIdentity = receipt.deepCopy(); receiptIdentity.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(receiptIdentity)).equals(receipt.path("selectionId").asText())
                || !context.path("contextId").equals(receipt.path("contextId"))
                || !policy.path("policyId").equals(receipt.path("policyId"))
                || !policy.path("policyVersion").equals(receipt.path("policyVersion"))
                || !policy.path("criterionIds").equals(receipt.path("criterionIds"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText())) return "BP3_SELECTION_IDENTITY_INVALID";
        JsonNode scope = policy.path("scope");
        if (!context.at("/gene/hgncId").equals(scope.path("hgncId")) || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi")) || !"GRCh38".equals(scope.path("assembly").asText())
                || !"NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT".equals(scope.path("variantType").asText())
                || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText()) || scope.path("transcripts").size() != 1
                || !context.at("/transcript/accession").equals(scope.at("/transcripts/0/accession"))
                || !context.at("/transcript/version").equals(scope.at("/transcripts/0/version"))) return "BP3_POLICY_CONTEXT_MISMATCH";
        if (!bound(policy.path("transcriptModelBinding"), model, "modelId", "modelVersion")) return "BP3_TRANSCRIPT_MODEL_IDENTITY_INVALID";
        if (!bound(policy.path("regionMaterialBinding"), regions, "materialId", "materialVersion")) return "BP3_REGION_MATERIAL_IDENTITY_INVALID";
        if (!CodingTranscriptMapping.sourcesValid(policy.path("scientificSources"))
                || !CodingTranscriptMapping.modelSourcesBound(model, policy.path("scientificSources"))
                || !CodingTranscriptMapping.sourcesValid(regions.path("sourceRecords"))) return "BP3_MATERIAL_SOURCE_INVALID";
        for (JsonNode source : regions.path("sourceRecords")) {
            boolean found = false;
            for (JsonNode trusted : policy.path("scientificSources")) if (source.equals(trusted)) found = true;
            if (!found) return "BP3_MATERIAL_SOURCE_INVALID";
        }
        if (policy.path("authoritySourceIds").isEmpty() || !policy.path("authoritySourceIds").isArray()) return "BP3_POLICY_AUTHORITY_MISSING";
        for (JsonNode id : policy.path("authoritySourceIds")) if (!id.isTextual() || id.asText().isBlank()) return "BP3_POLICY_AUTHORITY_MISSING";
        if (!only(regions, Set.of("schemaVersion", "materialId", "materialVersion", "modelSha256", "assembly", "contig", "hgncId", "transcript", "protein", "sourceRecords", "intervals", "coordinateSystem")))
            return "BP3_REGION_OPERATOR_UNSUPPORTED";
        if (!"bp3-region-material-v1".equals(regions.path("schemaVersion").asText())
                || !adapter.canonicalSha256(model).equals(regions.path("modelSha256").asText())) return "BP3_REGION_MATERIAL_IDENTITY_INVALID";
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein"))
            if (!regions.path(field).equals(model.path(field))) return "BP3_REGION_MATERIAL_IDENTITY_INVALID";
        if (!"REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE".equals(regions.path("coordinateSystem").asText())) return "BP3_REGION_COORDINATES_INVALID";
        String modelIssue = CodingTranscriptMapping.modelIssue(model, context);
        if (modelIssue != null) return modelIssue.replace("CODING_TRANSCRIPT_MODEL_", "BP3_TRANSCRIPT_MODEL_");
        if (!ProteinLengthChangeObservation.sha(model.path("cdsSequence").asText()).equals(policy.path("cdsSha256").asText())
                || !ProteinLengthChangeObservation.sha(ProteinLengthChangeObservation.translate(model.path("cdsSequence").asText())).equals(policy.path("proteinSha256").asText()))
            return "BP3_MODEL_SEQUENCE_SHA_MISMATCH";
        if (!evaluationIdentityValid(context, policy, receipt)) return "BP3_POLICY_AUTHORITY_INVALID";
        for (JsonNode source : policy.path("scientificSources")) if (source.path("locator").asText().isBlank()) return "BP3_MATERIAL_SOURCE_INVALID";
        return validateRegions(regions, policy, model.path("proteinLength").asInt());
    }

    private boolean bound(JsonNode binding, JsonNode material, String id, String version) {
        return !material.path(id).asText().isBlank() && !material.path(version).asText().isBlank()
                && binding.path(id).equals(material.path(id)) && binding.path(version).equals(material.path(version))
                && adapter.canonicalSha256(material).equals(binding.path("canonicalSha256").asText());
    }


    boolean evaluationIdentityValid(ObjectNode context, ObjectNode policy, ObjectNode receipt) {
        ObjectNode identity = receipt.deepCopy(); identity.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(identity)).equals(receipt.path("selectionId").asText())
                || !context.path("contextId").equals(receipt.path("contextId"))
                || !policy.path("policyId").equals(receipt.path("policyId")) || !policy.path("policyVersion").equals(receipt.path("policyVersion"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText())
                || policy.path("policyId").asText().isBlank() || policy.path("policyVersion").asText().isBlank()
                || !CodingTranscriptMapping.sourcesValid(policy.path("scientificSources"))
                || policy.path("authoritySourceIds").size() != 1
                || !"authority-acmg-amp-2015-richards".equals(policy.at("/authoritySourceIds/0").asText())) return false;
        for (JsonNode id : policy.path("authoritySourceIds")) {
            boolean found = false;
            for (JsonNode source : policy.path("scientificSources")) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }

    private String validateRegions(ObjectNode regions, ObjectNode policy, int proteinLength) {
        JsonNode intervals = regions.path("intervals");
        if (!intervals.isArray() || intervals.isEmpty()) return "BP3_REGION_COVERAGE_MISSING";
        if (intervals.size() > 1000) return "BP3_REGION_MATERIAL_INVALID";
        // Validate every assertion before examining any known negative; mixed unknown/conflict never short-circuits.
        for (JsonNode row : intervals) {
            if (!only(row, Set.of("regionId", "start", "end", "repeatStatus", "functionalStatus", "sourceIds", "functionalReview"))
                    || row.path("regionId").asText().isBlank() || !row.path("start").isIntegralNumber() || !row.path("end").isIntegralNumber()
                    || !row.path("start").canConvertToInt() || !row.path("end").canConvertToInt()
                    || row.path("start").asInt() < 1 || row.path("end").asInt() < row.path("start").asInt() || row.path("end").asInt() > proteinLength)
                return "BP3_REGION_MATERIAL_INVALID";
            if (!Set.of("REPEAT", "NON_REPEAT").contains(row.path("repeatStatus").asText())
                    || !Set.of("NO_KNOWN_FUNCTION", "KNOWN_FUNCTION").contains(row.path("functionalStatus").asText()))
                return "BP3_REGION_FACT_UNKNOWN";
            if (!sourceIds(row.path("sourceIds"), regions.path("sourceRecords"))) return "BP3_REGION_SOURCE_MISSING";
            JsonNode review = row.path("functionalReview");
            if (!only(review, Set.of("status", "reviewer", "evaluatedAt", "basis", "sourceIds", "contentSha256"))
                    || !"REVIEWED_APPLICABLE".equals(review.path("status").asText())
                    || review.path("reviewer").asText().isBlank() || review.path("basis").asText().isBlank()
                    || !sourceIds(review.path("sourceIds"), regions.path("sourceRecords"))) return "BP3_FUNCTIONAL_REVIEW_MISSING";
            try { java.time.LocalDate.parse(review.path("evaluatedAt").asText()); }
            catch (java.time.DateTimeException invalid) { return "BP3_FUNCTIONAL_REVIEW_DATE_INVALID"; }
            ObjectNode binding = regions.deepCopy(); binding.remove("intervals");
            ObjectNode facts = ((ObjectNode) row).deepCopy(); facts.remove("functionalReview");
            binding.set("region", facts); binding.set("scope", policy.path("scope").deepCopy());
            if (!adapter.canonicalSha256(binding).equals(review.path("contentSha256").asText())) return "BP3_FUNCTIONAL_REVIEW_IDENTITY_MISMATCH";
        }
        for (int i = 0; i < intervals.size(); i++) for (int j = 0; j < i; j++)
            if (intervals.path(i).path("start").asInt() <= intervals.path(j).path("end").asInt()
                    && intervals.path(j).path("start").asInt() <= intervals.path(i).path("end").asInt()) return "BP3_REGION_CONFLICT";
        return null;
    }

    String resolve(ObjectNode regions, ObjectNode observation) {
        int start = observation.at("/equivalentEditEnvelope/proteinStart").asInt();
        int end = observation.at("/equivalentEditEnvelope/proteinEnd").asInt();
        for (JsonNode row : regions.path("intervals")) if (row.path("start").asInt() <= start && row.path("end").asInt() >= end) {
            observation.set("selectedRegion", row.deepCopy());
            if ("NON_REPEAT".equals(row.path("repeatStatus").asText())) return "BP3_NON_REPEAT_REGION";
            if ("KNOWN_FUNCTION".equals(row.path("functionalStatus").asText())) return "BP3_KNOWN_FUNCTION";
            return null;
        }
        return "BP3_EQUIVALENT_ENVELOPE_REGION_COVERAGE_INCOMPLETE";
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
