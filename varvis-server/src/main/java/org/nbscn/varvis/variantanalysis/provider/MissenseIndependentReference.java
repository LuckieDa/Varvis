package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Checks an external, source-bound review; never infers evidence from a ClinVar label or prose. */
final class MissenseIndependentReference {
    static final String COMBINATION = "ACMG_2015_NON_LOF_V1";
    static final String RETAIN_OR_DOWNGRADE = "PRESERVE_ORIGINAL_ALLOW_P_TO_LP";
    private static final Set<String> CODES = Set.of("PS2", "PS3", "PS4", "PM1", "PM2", "PM3", "PM6", "PP1", "PP2", "PP3", "PP4");
    private MissenseIndependentReference() { }

    @Nullable static String policyIssue(JsonNode policy) {
        if (!policy.has("referenceQualification")) return null;
        JsonNode route = policy.path("referenceQualification");
        return !MissenseComparisonPolicy.only(route, Set.of("mode", "combination", "specificationVersion", "sourceIds", "classificationHandling"))
                || !"EXPERT_OR_REVIEWED_INDEPENDENT".equals(route.path("mode").asText())
                || !COMBINATION.equals(route.path("combination").asText())
                || !MissenseComparisonPolicy.text(route.path("specificationVersion"))
                || !MissenseComparisonPolicy.boundSources(policy, route.path("sourceIds"))
                || route.has("classificationHandling") && !RETAIN_OR_DOWNGRADE.equals(route.path("classificationHandling").asText())
                ? "REFERENCE_QUALIFICATION_POLICY_UNSUPPORTED" : null;
    }

    @Nullable static String issue(JsonNode policy, JsonNode ref, RuntimeEvidenceV2Adapter adapter) {
        if (!policy.has("referenceQualification")) return "REFERENCE_INDEPENDENT_ROUTE_NOT_ENABLED";
        String policyIssue = policyIssue(policy);
        if (policyIssue != null) return policyIssue;
        JsonNode q = ref.path("qualification"), original = ref.path("sourceAssertion"), independent = ref.path("independence");
        boolean separated = "missense-independent-reference-v2".equals(q.path("schemaVersion").asText());
        if (separated && !RETAIN_OR_DOWNGRADE.equals(policy.at("/referenceQualification/classificationHandling").asText()))
            return "REFERENCE_ADOPTION_CLASSIFICATION_ROUTE_NOT_ENABLED";
        if (!MissenseComparisonPolicy.only(q, Set.of("schemaVersion", "status", "reviewId", "combination", "specificationVersion",
                "sourceAssertionSha256", "conflictReview", "independenceRationale", "sourceIds", "evidence", "adoptedClassification"))
                || !separated && (!"missense-independent-reference-v1".equals(q.path("schemaVersion").asText()) || q.has("adoptedClassification"))
                || !"REVIEWED".equals(q.path("status").asText()) || !MissenseComparisonPolicy.text(q.path("reviewId"))
                || !"REVIEWED_NO_UNRESOLVED_CONFLICT".equals(q.path("conflictReview").asText())
                || !MissenseComparisonPolicy.text(q.path("independenceRationale"))) return "REFERENCE_INDEPENDENT_REVIEW_MISSING";
        if (!COMBINATION.equals(q.path("combination").asText())
                || !q.path("specificationVersion").equals(policy.at("/referenceQualification/specificationVersion"))
                || !q.path("specificationVersion").equals(independent.path("originalSpecificationVersion")))
            return "REFERENCE_INDEPENDENT_SPECIFICATION_MISMATCH";
        if (!original.isObject() || !MissenseComparisonPolicy.hash(q.path("sourceAssertionSha256"))
                || !q.path("sourceAssertionSha256").asText().equals(adapter.canonicalSha256(original))
                || !ref.path("scv").equals(original.path("scv"))
                || !normalized(ref.path("classification")).equals(normalized(original.path("classification")))
                || !ref.path("evaluationDate").asText().equals(ClinVarAssertionDate.iso(original.path("dateLastEvaluated").asText())))
            return "REFERENCE_INDEPENDENT_ASSERTION_BINDING_MISMATCH";
        if (!MissenseComparisonPolicy.boundSources(policy, q.path("sourceIds"))) return "REFERENCE_INDEPENDENT_SOURCE_UNBOUND";
        JsonNode evidence = q.path("evidence");
        if (!evidence.isArray() || evidence.isEmpty() || evidence.size() > CODES.size()) return "REFERENCE_INDEPENDENT_EVIDENCE_INCOMPLETE";
        Set<String> codes = new HashSet<>(), groups = new HashSet<>();
        int strong = 0, moderate = 0, supporting = 0;
        for (JsonNode row : evidence) {
            String code = row.path("code").asText();
            if (!MissenseComparisonPolicy.only(row, Set.of("code", "strength", "independenceGroup", "sourceIds", "sourceLocator", "rationale"))
                    || !CODES.contains(code) || !codes.add(code)
                    || !MissenseComparisonPolicy.text(row.path("independenceGroup")) || !groups.add(row.path("independenceGroup").asText())
                    || !MissenseComparisonPolicy.text(row.path("sourceLocator")) || !MissenseComparisonPolicy.text(row.path("rationale")))
                return "REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT";
            if (!MissenseComparisonPolicy.boundSources(policy, row.path("sourceIds"))) return "REFERENCE_INDEPENDENT_SOURCE_UNBOUND";
            switch (row.path("strength").asText()) {
                case "STRONG" -> strong++;
                case "MODERATE" -> moderate++;
                case "SUPPORTING" -> supporting++;
                default -> { return "REFERENCE_INDEPENDENT_STRENGTH_UNSUPPORTED"; }
            }
        }
        // De novo alternatives cannot be counted twice. Dependencies on comparison alleles are checked by the runtime.
        if (codes.contains("PS2") && codes.contains("PM6")) return "REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT";
        Set<String> declared = new HashSet<>();
        for (JsonNode criterion : independent.path("criteria")) {
            String code = criterion.isTextual() ? criterion.asText() : criterion.path("code").asText();
            if (!declared.add(code)) return "REFERENCE_INDEPENDENT_CRITERIA_MISMATCH";
        }
        if (!codes.equals(declared)) return "REFERENCE_INDEPENDENT_CRITERIA_MISMATCH";
        String classification = combine(strong, moderate, supporting);
        if (classification == null) return "REFERENCE_INDEPENDENT_EVIDENCE_INSUFFICIENT";
        String sourceClass = normalized(ref.path("classification"));
        String adopted = separated ? q.path("adoptedClassification").asText() : sourceClass;
        if (separated && (!Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(adopted)
                || !(sourceClass.equals(adopted) || "PATHOGENIC".equals(sourceClass) && "LIKELY_PATHOGENIC".equals(adopted))))
            return "REFERENCE_ADOPTION_CLASSIFICATION_CHANGE_UNSUPPORTED";
        if (!classification.equals(adopted)
                || !classification.equals(normalized(independent.path("classificationAfterExclusion"))))
            return "REFERENCE_INDEPENDENT_CLASSIFICATION_MISMATCH";
        return null;
    }

    /** Call only after issue() and original assertion checks have succeeded. */
    static String adoptedClassification(JsonNode ref) {
        return "missense-independent-reference-v2".equals(ref.at("/qualification/schemaVersion").asText())
                ? ref.at("/qualification/adoptedClassification").asText() : normalized(ref.path("classification"));
    }

    /** ACMG/AMP 2015 Table 5, non-LoF pathogenic evidence subset; no patient classification or Bayesian substitution. */
    @Nullable static String combine(int strong, int moderate, int supporting) {
        if (strong >= 2 || strong >= 1 && (moderate >= 3 || moderate >= 2 && supporting >= 2 || moderate >= 1 && supporting >= 4))
            return "PATHOGENIC";
        if (strong >= 1 && (moderate >= 1 || supporting >= 2) || moderate >= 3
                || moderate >= 2 && supporting >= 2 || moderate >= 1 && supporting >= 4) return "LIKELY_PATHOGENIC";
        return null;
    }

    private static String normalized(JsonNode value) { return value.asText().trim().toUpperCase(Locale.ROOT).replace(' ', '_'); }
}
