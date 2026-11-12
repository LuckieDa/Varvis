package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Bounded general BP1: three separately reviewed facts, never the inverse of PP2. */
final class Bp1MechanismPolicy {
    private final RuntimeEvidenceV2Adapter adapter;
    Bp1MechanismPolicy(RuntimeEvidenceV2Adapter adapter) { this.adapter = adapter; }

    String validate(ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode model, ObjectNode material) {
        if (!"GENERAL_POLICY_SELECTED".equals(selection.path("outcome").asText())) return "BP1_SPECIALIZED_POLICY_UNSUPPORTED";
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope",
                "appliedStrength", "transcriptModelBinding", "mechanismMaterialBinding", "scientificSources", "authoritySourceIds", "specification")))
            return "BP1_POLICY_OPERATOR_UNSUPPORTED";
        if (!"bp1-reviewed-mechanism-policy-v1".equals(policy.path("schemaVersion").asText())
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText()) || !text(policy.at("/review/reviewId"))
                || !"SUPPORTING".equals(policy.path("appliedStrength").asText())
                || policy.path("criterionIds").size() != 1 || !Bp1CriterionEvaluator.CRITERION.equals(policy.at("/criterionIds/0").asText()))
            return "BP1_POLICY_INVALID";
        if (!evaluationIdentityValid(context, policy, selection)) return "BP1_SELECTION_OR_AUTHORITY_INVALID";
        JsonNode scope = policy.path("scope");
        if (!only(scope, Set.of("hgncId", "mondoId", "moi", "assembly", "variantType", "transcriptMode", "transcripts"))
                || !context.at("/gene/hgncId").equals(scope.path("hgncId")) || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi")) || !"GRCh38".equals(scope.path("assembly").asText())
                || !"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText())
                || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText()) || scope.path("transcripts").size() != 1
                || !context.at("/transcript/accession").equals(scope.at("/transcripts/0/accession"))
                || !context.at("/transcript/version").equals(scope.at("/transcripts/0/version"))) return "BP1_POLICY_CONTEXT_MISMATCH";
        if (!bound(policy.path("transcriptModelBinding"), model, "modelId", "modelVersion")) return "BP1_MODEL_IDENTITY_INVALID";
        if (!bound(policy.path("mechanismMaterialBinding"), material, "materialId", "materialVersion")) return "BP1_MECHANISM_IDENTITY_INVALID";
        if (!only(material, Set.of("schemaVersion", "materialId", "materialVersion", "modelSha256", "scope", "assembly", "contig",
                "hgncId", "transcript", "protein", "sourceRecords", "truncatingMechanism", "reviewedVariantApplicability", "mechanismStatus"))
                || !"bp1-reviewed-mechanism-v1".equals(material.path("schemaVersion").asText())
                || !adapter.canonicalSha256(model).equals(material.path("modelSha256").asText())
                || !scope.equals(material.path("scope"))) return "BP1_MECHANISM_IDENTITY_INVALID";
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein"))
            if (!material.path(field).equals(model.path(field))) return "BP1_MECHANISM_IDENTITY_INVALID";
        String issue = CodingTranscriptMapping.modelIssue(model, context);
        if (issue != null) return issue.replace("CODING_TRANSCRIPT_MODEL_", "BP1_MODEL_");
        if (!CodingTranscriptMapping.modelSourcesBound(model, policy.path("scientificSources"))
                || !CodingTranscriptMapping.sourcesValid(material.path("sourceRecords"))) return "BP1_SOURCE_INVALID";
        for (JsonNode source : material.path("sourceRecords")) {
            boolean found = false;
            for (JsonNode trusted : policy.path("scientificSources")) if (source.equals(trusted)) found = true;
            if (!found || !text(source.path("locator"))) return "BP1_SOURCE_INVALID";
        }
        if (!"SINGLE_REVIEWED_MECHANISM".equals(material.path("mechanismStatus").asText())) return "BP1_MECHANISM_UNRESOLVED";
        JsonNode spec = policy.path("specification");
        if (!only(spec, Set.of("authoritySourceId", "version", "locator", "bp1Allowed", "semantics", "strengthCap", "extraConditions"))
                || !"SUPPORTING".equals(spec.path("strengthCap").asText()) || !text(spec.path("locator"))
                || !spec.path("bp1Allowed").isBoolean()) return "BP1_SPECIFICATION_INVALID";
        boolean authority = false;
        for (JsonNode source : policy.path("scientificSources"))
            if (spec.path("authoritySourceId").equals(source.path("sourceId")) && spec.path("version").equals(source.path("version")))
                for (JsonNode id : policy.path("authoritySourceIds")) if (id.equals(source.path("sourceId"))) authority = true;
        if (!authority) return "BP1_SPECIFICATION_AUTHORITY_INVALID";
        if (!spec.path("bp1Allowed").asBoolean()) return "BP1_SPECIFICATION_DISABLED_UNSUPPORTED";
        if (!"GENERAL_MISSENSE_IN_TRUNCATING_DOMINANT_DISEASE".equals(spec.path("semantics").asText())
                || !spec.path("extraConditions").isArray() || !spec.path("extraConditions").isEmpty()) return "BP1_ADDITIONAL_CONDITIONS_UNSUPPORTED";
        JsonNode applicability = material.path("reviewedVariantApplicability");
        if (!only(applicability, Set.of("identity", "exceptions", "splicing")) || applicability.size() != 3) return "BP1_VARIANT_APPLICABILITY_MISSING";
        JsonNode mechanism = material.path("truncatingMechanism"), exceptions = applicability.path("exceptions"), splicing = applicability.path("splicing");
        if (!only(mechanism, Set.of("conclusion", "basisType", "sourceIds", "clauseLocator", "statistics", "review"))) return "BP1_MECHANISM_FACT_MISSING";
        if (!only(exceptions, Set.of("conclusion", "searchStatus", "searchedDomains", "sourceIds", "locator", "findings", "review"))) return "BP1_EXCEPTION_FACT_MISSING";
        if (!only(splicing, Set.of("conclusion", "sourceIds", "locator", "method", "limitations", "review"))) return "BP1_SPLICING_FACT_MISSING";
        // No negative is considered until every necessary fact and its own review is complete.
        if (!Set.of("SUPPORTED", "NOT_SUPPORTED").contains(mechanism.path("conclusion").asText())
                || !Set.of("NO_APPLICABLE_EXCEPTION", "EXCEPTION_APPLIES").contains(exceptions.path("conclusion").asText())
                || !Set.of("SUPPORTED", "KNOWN_SPLICE_IMPACT").contains(splicing.path("conclusion").asText())) return "BP1_CONDITION_UNKNOWN_OR_CONFLICT";
        String type = mechanism.path("basisType").asText();
        if ("AUTHORITY_CLAUSE".equals(type)) {
            if (!text(mechanism.path("clauseLocator")) || mechanism.has("statistics")) return "BP1_CLAUSE_LOCATOR_MISSING";
        } else if ("REVIEWED_COHORT".equals(type)) {
            JsonNode stats = mechanism.path("statistics");
            if (!only(stats, Set.of("cohort", "sampleInclusion", "variantInclusion", "denominator", "count", "countLocator",
                    "deduplication", "selectionBiasReview", "scopeApplicability", "limitations"))) return "BP1_STATISTICS_INVALID";
            for (String field : List.of("cohort", "sampleInclusion", "variantInclusion", "deduplication", "limitations"))
                if (!text(stats.path(field))) return "BP1_STATISTICS_INCOMPLETE";
            if (!"REVIEWED_APPLICABLE".equals(stats.path("selectionBiasReview").asText())
                    || !"EXACT_SCOPE_REVIEWED".equals(stats.path("scopeApplicability").asText())) return "BP1_STATISTICS_APPLICABILITY_UNREVIEWED";
            boolean counts = stats.has("count") || stats.has("denominator");
            if (counts && (!stats.path("count").isIntegralNumber() || !stats.path("denominator").isIntegralNumber()
                    || !stats.path("count").canConvertToLong() || !stats.path("denominator").canConvertToLong()
                    || stats.path("count").asLong() < 0 || stats.path("denominator").asLong() <= 0
                    || stats.path("count").asLong() > stats.path("denominator").asLong())) return "BP1_STATISTICS_INVALID";
            if (!counts && !text(stats.path("countLocator"))) return "BP1_STATISTICS_INCOMPLETE";
        } else return "BP1_MECHANISM_BASIS_UNSUPPORTED";
        if (!"COMPLETE".equals(exceptions.path("searchStatus").asText())
                || !exactDomains(exceptions.path("searchedDomains"))) return "BP1_EXCEPTION_SEARCH_INCOMPLETE";
        if (!text(exceptions.path("locator")) || !exceptions.path("findings").isArray()) return "BP1_EXCEPTION_SOURCE_MISSING";
        boolean hasException = "EXCEPTION_APPLIES".equals(exceptions.path("conclusion").asText());
        if (hasException == exceptions.path("findings").isEmpty()) return "BP1_EXCEPTION_FACT_CONFLICT";
        for (JsonNode finding : exceptions.path("findings"))
            if (!only(finding, Set.of("type", "locator", "sourceIds")) || !text(finding.path("locator"))
                    || !sourceIds(finding.path("sourceIds"), material.path("sourceRecords"))
                    || !Set.of("KNOWN_PATHOGENIC_MISSENSE", "REVIEWED_FUNCTIONAL_REGION").contains(finding.path("type").asText())) return "BP1_EXCEPTION_MECHANISM_UNSUPPORTED";
        for (String field : List.of("locator", "method", "limitations")) if (!text(splicing.path(field))) return "BP1_SPLICING_REVIEW_INCOMPLETE";
        for (String key : List.of("truncatingMechanism", "exceptions", "splicing")) {
            JsonNode row = key.equals("truncatingMechanism") ? mechanism : applicability.path(key);
            if (!sourceIds(row.path("sourceIds"), material.path("sourceRecords"))) return "BP1_CONDITION_SOURCE_MISSING";
            if (!reviewValid(row.path("review"), material.path("sourceRecords"))) return "BP1_" + key.toUpperCase(java.util.Locale.ROOT) + "_REVIEW_MISSING";
        }
        for (String key : List.of("truncatingMechanism", "exceptions", "splicing")) {
            JsonNode row = key.equals("truncatingMechanism") ? mechanism : applicability.path(key);
            if (!adapter.canonicalSha256(reviewProjection(policy, material, key)).equals(row.at("/review/contentSha256").asText())) return "BP1_REVIEW_IDENTITY_MISMATCH";
        }
        return null;
    }

    ObjectNode reviewProjection(ObjectNode policy, ObjectNode material, String key) {
        ObjectNode content = material.deepCopy();
        // Only the three mutually circular digest references and the binding back to this material are excluded.
        ((ObjectNode) content.at("/truncatingMechanism/review")).remove("contentSha256");
        for (String name : List.of("exceptions", "splicing")) ((ObjectNode) content.at("/reviewedVariantApplicability/" + name + "/review")).remove("contentSha256");
        ObjectNode policyContent = policy.deepCopy(); policyContent.remove("mechanismMaterialBinding");
        content.set("policy", policyContent); content.put("reviewedCondition", key); return content;
    }

    String resolve(ObjectNode material, ObjectNode model, ObjectNode observation) {
        JsonNode identity = material.at("/reviewedVariantApplicability/identity");
        if (!only(identity, Set.of("allele", "transcript", "protein", "modelSha256", "hgvsc", "hgvsp", "proteinVariantKey")) || identity.size() != 7
                || !identity.path("allele").equals(observation.path("allele"))
                || !identity.path("transcript").equals(model.path("transcript")) || !identity.path("protein").equals(model.path("protein"))
                || !identity.path("modelSha256").equals(observation.path("nativeModelSha256"))) return "BP1_VARIANT_APPLICABILITY_IDENTITY_MISMATCH";
        for (String key : List.of("hgvsc", "hgvsp", "proteinVariantKey")) if (!identity.path(key).equals(observation.path(key))) return "BP1_VARIANT_APPLICABILITY_IDENTITY_MISMATCH";
        observation.set("truncatingMechanism", material.path("truncatingMechanism").deepCopy());
        observation.set("reviewedVariantApplicability", material.path("reviewedVariantApplicability").deepCopy());
        if ("KNOWN_SPLICE_IMPACT".equals(material.at("/reviewedVariantApplicability/splicing/conclusion").asText())) return "BP1_SPLICE_IMPACT_UNSUPPORTED";
        if ("NOT_SUPPORTED".equals(material.at("/truncatingMechanism/conclusion").asText())) return "BP1_TRUNCATING_MECHANISM_NOT_SUPPORTED";
        if ("EXCEPTION_APPLIES".equals(material.at("/reviewedVariantApplicability/exceptions/conclusion").asText())) return "BP1_MISSENSE_EXCEPTION_APPLIES";
        return null;
    }
    boolean evaluationIdentityValid(ObjectNode context, ObjectNode policy, ObjectNode receipt) {
        ObjectNode identity = receipt.deepCopy(); identity.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !"GENERAL_POLICY_SELECTED".equals(receipt.path("outcome").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(identity)).equals(receipt.path("selectionId").asText())
                || !context.path("contextId").equals(receipt.path("contextId")) || !policy.path("policyId").equals(receipt.path("policyId"))
                || !policy.path("policyVersion").equals(receipt.path("policyVersion")) || !policy.path("criterionIds").equals(receipt.path("criterionIds"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText())
                || !text(policy.path("policyId")) || !text(policy.path("policyVersion"))
                || !CodingTranscriptMapping.sourcesValid(policy.path("scientificSources"))
                || policy.path("authoritySourceIds").size() != 1
                || !"authority-acmg-amp-2015-richards".equals(policy.at("/authoritySourceIds/0").asText())) return false;
        for (JsonNode source : policy.path("scientificSources")) if (!text(source.path("locator"))) return false;
        return sourceIds(policy.path("authoritySourceIds"), policy.path("scientificSources"));
    }
    private boolean bound(JsonNode binding, JsonNode material, String id, String version) {
        return text(material.path(id)) && text(material.path(version)) && binding.path(id).equals(material.path(id))
                && binding.path(version).equals(material.path(version)) && adapter.canonicalSha256(material).equals(binding.path("canonicalSha256").asText());
    }
    private static boolean exactDomains(JsonNode domains) {
        if (!domains.isArray() || domains.size() != 4) return false;
        var seen = new java.util.HashSet<String>(); for (JsonNode domain : domains) { if (!domain.isTextual()) return false; seen.add(domain.asText()); }
        return seen.equals(Set.of("PATHOGENIC_MISSENSE", "FUNCTIONAL_REGIONS", "GOF_DN", "MIXED_MECHANISM"));
    }
    private static boolean reviewValid(JsonNode review, JsonNode sources) {
        if (!only(review, Set.of("status", "reviewer", "evaluatedAt", "basis", "sourceIds", "contentSha256"))
                || !"REVIEWED_APPLICABLE".equals(review.path("status").asText()) || !text(review.path("reviewer"))
                || !text(review.path("basis")) || !sourceIds(review.path("sourceIds"), sources)
                || !review.path("contentSha256").asText().matches("[0-9a-f]{64}")) return false;
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
    private static boolean text(JsonNode node) { return node.isTextual() && !node.asText().isBlank(); }
    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
}
