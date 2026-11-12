package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Shared, source-bound adoption checks. Scientific interpretation is explicit
 * review input, never inferred from four numbers or from a matching hash. */
final class Bs1KnowledgeQualification {
    static final String VERSION = "bs1-knowledge-qualification-v1";
    static final List<String> CATEGORIES = List.of("SOURCE_COMPLETENESS", "PARAMETER_SCOPE", "POPULATION_METRIC",
            "ADDITIONAL_CONDITIONS", "EXCEPTIONS");
    private Bs1KnowledgeQualification() { }

    static ObjectNode payload(JsonNode record) {
        var value = ((ObjectNode) record).deepCopy(); value.remove(List.of("review", "qualification")); return value;
    }

    static String issue(JsonNode record) {
        if (!record.has("qualification")) return record.has("modelRestrictions") || record.has("populationStatistic") ? "BS1_QUALIFICATION_REQUIRED" : null;
        if (record.has("populationStatistic") && (!record.path("populationStatistic").isObject()
                || "EXPERT_POLICY".equals(record.path("kind").asText()))) return "BS1_MODEL_STATISTIC_INVALID";
        var q = record.path("qualification");
        if (!Bs1Policy.keys(q, Set.of("schemaVersion", "payloadSha256", "scope", "sourceBindings", "checks",
                "reviewer", "reviewedAt", "basis", "priorReview")) || !VERSION.equals(q.path("schemaVersion").asText())
                || !Bs1KnowledgeCatalog.hash(payload(record)).equals(q.path("payloadSha256").asText())
                || !record.path("scope").equals(q.path("scope")) || q.path("reviewer").asText().isBlank()
                || q.path("reviewedAt").asText().isBlank() || q.path("basis").asText().isBlank())
            return "BS1_QUALIFICATION_BINDING_INVALID";
        var prior = q.path("priorReview");
        if (!prior.isObject() || !"REVIEWED_FOR_LOCAL_USE".equals(prior.path("status").asText())
                || !q.path("payloadSha256").equals(prior.path("contentSha256"))
                || !(Bs1SexChromosomePolicy.isJoint(record.path("expertPolicy")) ? Bs1SexChromosomePolicy.JOINT_SCOPE : "BS1_LOCAL_EXOMES_V1")
                        .equals(prior.path("dataScopePolicyId").asText())
                || prior.path("reviewer").asText().isBlank() || prior.path("reviewedAt").asText().isBlank() || prior.path("basis").asText().isBlank())
            return "BS1_QUALIFICATION_PRIOR_ADOPTION_INVALID";
        for (String key : List.of("reviewer", "reviewedAt", "basis"))
            if (!q.path(key).equals(record.path("review").path(key))) return "BS1_QUALIFICATION_ATTRIBUTION_MISMATCH";
        for (String key : List.of("strength", "expertSpecificationCheck"))
            if (!prior.path(key).equals(record.path("review").path(key))) return "BS1_QUALIFICATION_PRIOR_ADOPTION_CHANGED";
        var sources = new HashSet<String>();
        for (var s : record.path("sources")) sources.add(s.path("sourceId").asText());
        var bound = new HashSet<String>(); var refs = new HashSet<String>();
        if (!q.path("sourceBindings").isArray() || q.path("sourceBindings").isEmpty()) return "BS1_QUALIFICATION_SOURCE_REQUIRED";
        for (var b : q.path("sourceBindings")) {
            String id = b.path("sourceId").asText(), locator = b.path("locator").asText();
            if (!Bs1Policy.keys(b, Set.of("sourceId", "locator", "contentSha256")) || !sources.contains(id)
                    || locator.isBlank() || !Bs1Policy.sha(b.path("contentSha256")) || !refs.add(id + "|" + locator))
                return "BS1_QUALIFICATION_SOURCE_INVALID";
            bound.add(id);
        }
        if (!bound.containsAll(sources)) return "BS1_QUALIFICATION_SOURCE_INCOMPLETE";
        var checks = q.path("checks");
        if (!Bs1Policy.keys(checks, Set.copyOf(CATEGORIES)) || checks.size() != CATEGORIES.size()) return "BS1_QUALIFICATION_CHECKS_INCOMPLETE";
        String unresolved = null;
        for (String category : CATEGORIES) {
            var c = checks.path(category);
            if (!Bs1Policy.keys(c, Set.of("status", "basis", "sourceIds")) || c.path("basis").asText().isBlank()
                    || !c.path("sourceIds").isArray() || c.path("sourceIds").isEmpty()) return "BS1_QUALIFICATION_CHECK_INVALID";
            var ids = new HashSet<String>();
            for (var id : c.path("sourceIds")) if (!id.isTextual() || !bound.contains(id.asText()) || !ids.add(id.asText()))
                return "BS1_QUALIFICATION_CHECK_SOURCE_UNBOUND";
            String expected = "PARAMETER_SCOPE".equals(category)
                    ? ("EXPERT_POLICY".equals(record.path("kind").asText()) ? "PUBLISHED_THRESHOLD_NOT_RECALCULATED" : "MODEL_PARAMETERS_MATCHED")
                    : "SATISFIED";
            String status = c.path("status").asText();
            if (!expected.equals(status) && !"UNRESOLVED".equals(status)) return "BS1_QUALIFICATION_CHECK_INVALID";
            if ("UNRESOLVED".equals(status) && unresolved == null) unresolved = "BS1_QUALIFICATION_" + category + "_UNRESOLVED";
        }
        if (unresolved != null) return unresolved;
        if ("EXPERT_POLICY".equals(record.path("kind").asText())) {
            if (record.has("modelRestrictions")) return "BS1_QUALIFICATION_EXPERT_RESTRICTION_CONFLICT";
            if (!record.path("exceptionSet").equals(record.at("/expertPolicy/exceptionSet"))) return "BS1_QUALIFICATION_EXCEPTION_CONFLICT";
        } else if (!record.has("modelRestrictions")) return "BS1_MODEL_RESTRICTIONS_REQUIRED";
        if (record.has("modelRestrictions")) {
            var r = record.path("modelRestrictions");
            if (!Bs1Policy.keys(r, Set.of("variantTypes", "transcripts", "commonConditions"))
                    || !r.path("variantTypes").isArray() || r.path("variantTypes").isEmpty()
                    || !r.path("transcripts").isArray() || !r.path("commonConditions").isArray())
                return "BS1_MODEL_RESTRICTIONS_INVALID";
            var variants = new HashSet<String>();
            for (var v : r.path("variantTypes")) if (!v.isTextual() || !Set.of("SNV", "SMALL_INSERTION", "SMALL_DELETION").contains(v.asText())
                    || !variants.add(v.asText())) return "BS1_MODEL_RESTRICTIONS_INVALID";
        }
        var exceptions = record.path("exceptionSet");
        if (!Bs1Policy.keys(exceptions, Set.of("setId", "version", "criterionId", "status", "entries", "sourceLocator"))
                || !"COMPLETE_IN_PINNED_SCOPE".equals(exceptions.path("status").asText()) || !exceptions.path("entries").isArray())
            return "BS1_QUALIFICATION_EXCEPTIONS_UNRESOLVED";
        var entries = new HashSet<String>();
        for (var e : exceptions.path("entries")) {
            if (!e.path("hgncId").equals(record.at("/scope/hgncId")) || !e.path("mondoId").equals(record.at("/scope/mondoId"))
                    || e.path("entryId").asText().isBlank() || !entries.add(e.path("entryId").asText())
                    || !"VERIFIED_ALLELE".equals(e.path("identityStatus").asText())
                    || !"NOT_APPLICABLE".equals(e.path("disposition").asText())
                    || !Bs1Policy.validScopedAllele(e.path("queryAllele"), Bs1SexChromosomePolicy.isStatistic(record.path("expertPolicy"))) || !Bs1Policy.sources(e.path("mappingSource")))
                return "BS1_QUALIFICATION_EXCEPTION_MAPPING_UNRESOLVED";
            for (var source : e.path("mappingSource")) {
                boolean found = false;
                for (var known : record.path("sources")) {
                    boolean equal = true;
                    for (String key : List.of("sourceId", "url", "version", "sha256")) equal &= known.path(key).equals(source.path(key));
                    found |= equal;
                }
                if (!found) return "BS1_QUALIFICATION_EXCEPTION_SOURCE_UNBOUND";
            }
        }
        return null;
    }

    static void applyModelRestrictions(JsonNode record, ObjectNode policy) {
        if (!record.has("modelRestrictions")) return;
        var r = record.path("modelRestrictions");
        policy.withObject("/scope").set("variantTypes", r.path("variantTypes").deepCopy());
        if (!r.path("transcripts").isEmpty()) policy.withObject("/scope").set("transcripts", r.path("transcripts").deepCopy());
        policy.withObject("/requiredContext").put("transcript", r.path("transcripts").isEmpty() ? "NOT_REQUIRED_BY_POLICY" : "REQUIRED_BY_SCOPE");
        policy.set("commonConditions", r.path("commonConditions").deepCopy());
    }
}
