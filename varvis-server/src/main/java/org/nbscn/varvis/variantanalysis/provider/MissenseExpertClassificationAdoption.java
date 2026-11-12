package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Preserves a prior reviewed expert classification; does not recombine or upgrade its evidence. */
final class MissenseExpertClassificationAdoption {
    static final String MODE = "PRESERVE_REVIEWED_EXPERT_CLASSIFICATION";
    static final String SOURCE_MODE = "PRESERVE_SOURCE_VERIFIED_EXPERT_CLASSIFICATION";
    static final String SOURCE_STATUS = "SOURCE_VERIFIED_NO_COMPARISON_DEPENDENCY";
    static final String SOURCE_DEPENDENCY = "EXPLICIT_FINAL_LIST_WITHOUT_PS1_PM5";
    private static final Set<String> CODES = Set.of("PS2", "PS3", "PS4", "PM1", "PM2", "PM3", "PM6", "PP1", "PP2", "PP3", "PP4");
    private static final Set<String> RETAINED_GAPS = Set.of("STRUCTURED_SPECIFICATION_VERSION_MISSING",
            "SUMMARY_VERSION_RETRACTION_AND_FINAL_CRITERIA_NOT_BOUND", "SEPIO_CRITERION_LABEL_MISSING");
    private MissenseExpertClassificationAdoption() { }

    @Nullable static String policyIssue(JsonNode policy) {
        if (!policy.has("expertSourceAdoption")) return null;
        JsonNode route = policy.path("expertSourceAdoption");
        if (!MissenseComparisonPolicy.only(route, Set.of("mode", "sourceIds", "versionHandling", "requiredExpertPanel"))
                || !Set.of(MODE, SOURCE_MODE).contains(route.path("mode").asText())
                || (SOURCE_MODE.equals(route.path("mode").asText())
                    ? !"PRESERVE_ORIGINAL_NOT_RECLASSIFIED".equals(route.path("versionHandling").asText())
                    : route.has("versionHandling"))
                || !MissenseComparisonPolicy.boundSources(policy, route.path("sourceIds")))
            return "EXPERT_CLASSIFICATION_POLICY_INVALID";
        if (route.has("requiredExpertPanel")) {
            JsonNode panel = route.path("requiredExpertPanel");
            if (!SOURCE_MODE.equals(route.path("mode").asText())
                    || !MissenseComparisonPolicy.only(panel, Set.of("affiliationId", "name", "clinvarOrgId", "clinvarSubmitterName"))
                    || !(panel.has("affiliationId") || panel.has("clinvarOrgId"))
                    || (panel.has("affiliationId") || panel.has("name"))
                        && !(MissenseComparisonPolicy.text(panel.path("affiliationId")) && MissenseComparisonPolicy.text(panel.path("name")))
                    || (panel.has("clinvarOrgId") || panel.has("clinvarSubmitterName"))
                        && !(MissenseComparisonPolicy.text(panel.path("clinvarOrgId")) && MissenseComparisonPolicy.text(panel.path("clinvarSubmitterName"))))
                return "EXPERT_REQUIRED_PANEL_POLICY_INVALID";
        }
        return null;
    }

    /** Some specifications require classification by a particular panel; this is policy data, not a gene whitelist. */
    @Nullable static String panelIssue(JsonNode reference, JsonNode policy) {
        JsonNode panel = policy.at("/expertSourceAdoption/requiredExpertPanel");
        if (panel.isMissingNode()) return null;
        JsonNode row = reference.at("/expertEvidence/record"), summary = row.path("originalExpertSummary");
        boolean matched = false;
        if (panel.has("affiliationId") && !summary.isMissingNode() && !summary.isNull()) {
            if (!panel.path("affiliationId").equals(summary.path("affiliationId")) || !panel.path("name").equals(summary.path("ep")))
                return "EXPERT_REQUIRED_PANEL_MISMATCH";
            matched = true;
        }
        if (panel.has("clinvarOrgId")) {
            JsonNode accession = row.at("/originalAssertion/accession");
            if (!panel.path("clinvarOrgId").equals(accession.path("OrgID"))
                    || !panel.path("clinvarSubmitterName").equals(accession.path("SubmitterName")))
                return "EXPERT_REQUIRED_PANEL_MISMATCH";
            matched = true;
        }
        return matched ? null : "EXPERT_REQUIRED_PANEL_MISMATCH";
    }

    @Nullable static String enabledIssue(JsonNode policy) {
        if (!policy.has("expertSourceAdoption")) return "EXPERT_CLASSIFICATION_ROUTE_NOT_ENABLED";
        return policyIssue(policy);
    }

    @Nullable static String sourceIssue(JsonNode reference, JsonNode row) {
        if (!Set.of(MODE, SOURCE_MODE).contains(reference.path("expertClassificationAdoption").asText()) || reference.has("qualification"))
            return "EXPERT_CLASSIFICATION_ROUTE_AMBIGUOUS";
        JsonNode original = row.path("originalAssertion"), review = reference.path("independence");
        String classification = original.path("classification").asText();
        String normalized = "Pathogenic".equalsIgnoreCase(classification) ? "PATHOGENIC"
                : "Likely pathogenic".equalsIgnoreCase(classification) ? "LIKELY_PATHOGENIC" : "";
        if (normalized.isEmpty() || !normalized.equals(reference.path("classification").asText())
                || !normalized.equals(review.path("classificationAfterExclusion").asText())
                || !Set.of("reviewed by expert panel", "practice guideline").contains(original.path("reviewStatus").asText())
                || !"current".equals(original.path("recordStatus").asText()))
            return "EXPERT_ORIGINAL_CLASSIFICATION_NOT_PRESERVED";
        boolean automatic = sourceVerified(reference);
        if (!(automatic ? SOURCE_STATUS : "REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5").equals(review.path("status").asText())
                || !MissenseComparisonPolicy.text(review.path("reviewId"))
                || !(automatic ? SOURCE_DEPENDENCY : "REVIEWED_NO_COMPARISON_DEPENDENCY").equals(review.path("dependencyReview").asText())
                || !review.path("dependencyAlleles").isArray() || !review.path("dependencyAlleles").isEmpty())
            return "EXPERT_PRIOR_INDEPENDENCE_REVIEW_REQUIRED";
        if (!reference.path("mondoId").equals(row.at("/disease/id"))) return "EXPERT_SOURCE_DISEASE_MISMATCH";
        if (row.hasNonNull("originalExpertSummary")) {
            JsonNode summary = row.path("originalExpertSummary");
            if (!summary.path("retracted").isBoolean() || summary.path("retracted").asBoolean()
                    || !classification.equalsIgnoreCase(summary.path("classification").asText())
                    || !reference.path("mondoId").equals(summary.path("mondoId")))
                return "EXPERT_SOURCE_SUMMARY_CONFLICT";
        }
        if (!row.path("gapReasons").isArray()) return "EXPERT_SOURCE_GAPS_UNRESOLVED";
        for (JsonNode gap : row.path("gapReasons")) {
            // Keep the source packet immutable. Only the exact mapping already checked
            // by rowIssue may discharge this factual gap in the derived qualification.
            boolean mapped = "EXACT_SCV_DISEASE_MAPPING_UNRESOLVED".equals(gap.asText())
                    && "EXACT_RECORD_BOUND_CONDITION_UNRESOLVED".equals(row.path("bindingStatus").asText())
                    && reference.path("mondoId").asText().equals(MissenseConditionMapping.resolve(
                            reference.at("/sourceAssertion/condition/submittedRaw").asText(),
                            reference.at("/sourceAssertion/condition/reportedRaw").asText(), reference.path("conditionMappings")));
            if (!gap.isTextual() || !RETAINED_GAPS.contains(gap.asText()) && !mapped)
                return "EXPERT_SOURCE_GAPS_UNRESOLVED";
        }
        JsonNode statements = row.path("sourceAppliedCriteriaStatements");
        if (!"EXPLICIT_SOURCE_LIST".equals(row.path("sourceAppliedCriteriaStatus").asText())
                || !statements.isArray() || statements.size() != 1)
            return "EXPERT_COMPLETE_FINAL_CRITERIA_REQUIRED";
        JsonNode statement = statements.get(0), criteria = statement.path("criteria");
        if (!"EXPLICIT_SOURCE_LIST".equals(statement.path("status").asText())
                || !statement.path("scientificAdoption").isBoolean() || statement.path("scientificAdoption").asBoolean()
                || !criteria.isArray() || criteria.isEmpty() || criteria.size() > CODES.size())
            return "EXPERT_COMPLETE_FINAL_CRITERIA_REQUIRED";
        JsonNode versions = statement.path("specificationVersionMentions");
        if (!versions.isArray() || (reference.has("originalSpecificationBinding")
                ? !versions.isEmpty() || !methodBindingMatches(reference, row)
                : versions.size() != 1 || !MissenseComparisonPolicy.text(versions.get(0))
                    || !versions.get(0).equals(review.path("originalSpecificationVersion"))))
            return "EXPERT_ORIGINAL_SPECIFICATION_MISMATCH";
        boolean locatorMatched = false, sourceBound = false;
        for (JsonNode comment : original.path("comments")) {
            if ("CLASSIFICATION".equals(comment.path("scope").asText()) && MissenseComparisonPolicy.text(comment.path("xpath"))
                    && comment.path("xpath").equals(statement.path("xpath"))
                    && comment.path("text").equals(statement.path("originalText"))) locatorMatched = true;
        }
        for (JsonNode id : row.path("sourceIds")) if (id.equals(statement.path("sourceId"))) sourceBound = true;
        if (!locatorMatched || !sourceBound) return "EXPERT_FINAL_CRITERIA_LOCATOR_MISMATCH";
        Set<String> expected = new HashSet<>(), codes = new HashSet<>();
        for (JsonNode criterion : criteria) {
            String token = criterion.path("original").asText(), code = criterion.path("code").asText();
            String suffix = criterion.path("strengthSuffix").isNull() ? "" : "_" + criterion.path("strengthSuffix").asText();
            if (!CODES.contains(code) || !codes.add(code) || !token.equals(code + suffix)
                    || !token.matches("(?:PS[234]|PM[1236]|PP[1234])(?:_(?:Strong|Moderate|Supporting|VeryStrong|VS))?"))
                return "EXPERT_FINAL_CRITERIA_UNSUPPORTED_OR_COMPARISON_DEPENDENT";
            expected.add(token);
        }
        // Cross-check the retained literal suffix; defaults and prose mentions are never adopted strengths.
        String suffix = statement.path("rawSuffix").asText();
        if (suffix.isEmpty() || !statement.path("originalText").asText().endsWith(suffix))
            return "EXPERT_FINAL_CRITERIA_LOCATOR_MISMATCH";
        String list = suffix.trim().replaceFirst("[).\\s]+$", "");
        Set<String> literals = new HashSet<>();
        for (String item : list.split(",", -1)) if (!literals.add(item.trim())) return "EXPERT_FINAL_CRITERIA_LOCATOR_MISMATCH";
        if (!literals.equals(expected)) return "EXPERT_FINAL_CRITERIA_LOCATOR_MISMATCH";
        Set<String> reviewed = new HashSet<>();
        if (!review.path("criteria").isArray()) return "EXPERT_PRIOR_CRITERIA_MISMATCH";
        for (JsonNode item : review.path("criteria")) if (!item.isTextual() || !reviewed.add(item.asText()))
            return "EXPERT_PRIOR_CRITERIA_MISMATCH";
        if (!reviewed.equals(expected)) return "EXPERT_PRIOR_CRITERIA_MISMATCH";
        if (!row.path("appliedComparisonCriteria").isArray() || !row.path("appliedComparisonCriteria").isEmpty())
            return "EXPERT_FINAL_CRITERIA_UNSUPPORTED_OR_COMPARISON_DEPENDENT";
        for (JsonNode item : row.path("summaryMetCriteria")) if (!codes.contains(item.path("code").asText())
                || !expected.contains(item.path("original").asText()))
            return "EXPERT_SOURCE_CRITERIA_CONFLICT";
        for (JsonNode item : row.path("criterionAssessments")) {
            if ("Met".equals(item.path("outcome").asText()) && !item.path("code").isNull()
                    && !codes.contains(item.path("code").asText())) return "EXPERT_SOURCE_CRITERIA_CONFLICT";
        }
        return null;
    }

    static boolean sourceVerified(JsonNode reference) {
        return SOURCE_MODE.equals(reference.path("expertClassificationAdoption").asText());
    }

    /** Derive a machine-checkable source fact; never label it as a new expert judgment. */
    static void deriveSourceFacts(ObjectNode reference, JsonNode row, String sourceHash) {
        ObjectNode independence = reference.putObject("independence")
                .put("status", SOURCE_STATUS).put("reviewId", "source-check-sha256-" + sourceHash)
                .put("dependencyReview", SOURCE_DEPENDENCY)
                .put("classificationAfterExclusion", reference.path("classification").asText());
        JsonNode versions = row.at("/sourceAppliedCriteriaStatements/0/specificationVersionMentions");
        independence.set("originalSpecificationVersion", reference.has("originalSpecificationBinding")
                ? reference.at("/originalSpecificationBinding/specificationVersion").deepCopy()
                : versions.size() == 1 ? versions.get(0).deepCopy() : reference.nullNode());
        var codes = independence.putArray("criteria");
        for (JsonNode item : row.at("/sourceAppliedCriteriaStatements/0/criteria")) codes.add(item.path("original").deepCopy());
        independence.putArray("dependencyAlleles");
        independence.set("sourceIds", row.path("sourceIds").deepCopy());
        ObjectNode mapping = sourceCondition(reference, row);
        if (mapping != null) reference.putArray("conditionMappings").add(mapping);
    }

    /** Exact submission local key links one original disease identifier to the same expert record.
     * This is not a general synonym/name mapping and never applies to another SCV. */
    @Nullable static ObjectNode sourceCondition(JsonNode reference, JsonNode row) {
        JsonNode summary = row.path("originalExpertSummary"), original = row.path("originalAssertion");
        String uuid = row.path("erepoUuid").asText();
        if (uuid.isBlank() || !uuid.equals(summary.path("uuid").asText())
                || !row.path("erepoVersion").equals(summary.path("docVersion"))
                || !row.path("variationId").asText().equals(summary.path("cvId").asText())
                || !reference.path("mondoId").equals(summary.path("mondoId"))
                || !reference.at("/sourceAssertion/scv").equals(original.path("scv"))) return null;
        Set<String> xrefs = new HashSet<>(), keys = new HashSet<>();
        conditionIds(original.path("condition"), xrefs);
        for (JsonNode submission : original.path("submissionIdentity")) {
            String key = submission.at("/attributes/localKey").asText();
            if (!key.isBlank()) keys.add(key);
        }
        if (xrefs.size() != 1 || keys.size() != 1) return null;
        String xref = xrefs.iterator().next();
        if (!keys.contains(uuid + "|" + xref)
                || !xref.equals(reference.at("/sourceAssertion/condition/submittedRaw").asText())) return null;
        ObjectNode mapping = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("schemaVersion", "reviewed-clinvar-condition-mapping-v1").put("status", "SOURCE_VERIFIED_EXACT")
                .put("relation", "EXACT_DISEASE").put("reviewId", "exact-expert-submission-" + original.path("scv").asText())
                .put("mondoId", summary.path("mondoId").asText());
        mapping.set("sourceCondition", reference.at("/sourceAssertion/condition").deepCopy());
        mapping.set("sourceIds", row.path("sourceIds").deepCopy());
        return mapping;
    }

    private static void conditionIds(JsonNode node, Set<String> ids) {
        if ("XRef".equals(node.path("tag").asText()) && MissenseComparisonPolicy.text(node.at("/attributes/DB"))
                && MissenseComparisonPolicy.text(node.at("/attributes/ID")))
            ids.add(node.at("/attributes/DB").asText() + ":" + node.at("/attributes/ID").asText());
        if (node.isContainerNode()) node.forEach(child -> conditionIds(child, ids));
    }

    /** A source citation binds the original method; it does not assert equivalence to a newer policy. */
    private static boolean methodBindingMatches(JsonNode reference, JsonNode row) {
        JsonNode binding = reference.path("originalSpecificationBinding");
        if (!MissenseComparisonPolicy.only(binding, Set.of("specificationVersion", "assertionMethodLabel", "url", "sourceId"))
                || !MissenseComparisonPolicy.text(binding.path("specificationVersion"))
                || !binding.path("specificationVersion").equals(reference.at("/independence/originalSpecificationVersion"))
                || !MissenseComparisonPolicy.text(binding.path("assertionMethodLabel"))
                || !MissenseComparisonPolicy.text(binding.path("sourceId"))
                || !binding.path("url").asText().startsWith("https://")) return false;
        JsonNode methods = row.at("/specification/originalClinVarMethod");
        // A conflicting explicit version must be resolved, never bypassed by a citation fallback.
        if (!methods.isArray() || row.at("/specification/originalAssertionMethod/version").isTextual()
                || !row.at("/specification/narrativeVersionMentions").isMissingNode()
                    && !row.at("/specification/narrativeVersionMentions").isEmpty()) return false;
        int methodCount = 0, matched = 0;
        for (JsonNode group : methods) {
            if (!"AttributeSet".equals(group.path("tag").asText())) continue;
            for (JsonNode attribute : group.path("children")) {
                if (!"Attribute".equals(attribute.path("tag").asText())
                        || !"AssertionMethod".equals(attribute.at("/attributes/Type").asText())) continue;
                methodCount++;
                if (!binding.path("assertionMethodLabel").equals(attribute.path("text"))) continue;
                int urls = 0, exactUrls = 0;
                for (JsonNode citation : group.path("children")) if ("Citation".equals(citation.path("tag").asText()))
                    for (JsonNode link : citation.path("children")) if ("URL".equals(link.path("tag").asText())) {
                        urls++;
                        if (binding.path("url").equals(link.path("text"))) exactUrls++;
                    }
                if (urls == 1 && exactUrls == 1) matched++;
            }
        }
        return methodCount == 1 && matched == 1;
    }

    @Nullable static String methodSourceIssue(JsonNode reference, JsonNode policy) {
        if (!reference.has("originalSpecificationBinding")) return null;
        JsonNode binding = reference.path("originalSpecificationBinding");
        JsonNode source = MissenseComparisonPolicy.source(policy, binding.path("sourceId").asText());
        boolean declared = false;
        for (JsonNode id : reference.path("sourceIds")) if (id.equals(binding.path("sourceId"))) declared = true;
        return source == null || !declared || !binding.path("url").equals(source.path("url"))
                || !MissenseComparisonPolicy.hash(source.path("sha256")) || !MissenseComparisonPolicy.text(source.path("version"))
                ? "EXPERT_ORIGINAL_SPECIFICATION_SOURCE_UNBOUND" : null;
    }
}
