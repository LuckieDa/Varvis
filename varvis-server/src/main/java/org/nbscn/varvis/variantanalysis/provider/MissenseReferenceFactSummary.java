package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Display-only projection of captured facts. It never grants reference eligibility. */
public final class MissenseReferenceFactSummary {
    public static final String VERSION = "1.0.0";
    private static final String SCHEMA = "missense-reference-facts-v1";
    private static final Set<String> RELATIONS = Set.of("SAME_AMINO_ACID_CHANGE", "SAME_RESIDUE_DIFFERENT_AMINO_ACID");
    private static final Set<String> STATES = Set.of("NOT_APPLICABLE", "UNAVAILABLE", "INCOMPLETE", "COMPLETE");
    private static final List<String> COUNTS = List.of("discoveredCandidates", "comparableReferences", "criterionMatches", "excludedCandidates", "unresolvedCandidates");
    private MissenseReferenceFactSummary() { }

    public static ObjectNode build(ObjectMapper mapper, JsonNode reference, String criterionId) {
        var out = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("version", VERSION)
                .put("purpose", "EXPLANATION_ONLY").put("scientificApprovalGranted", false).put("criterionId", criterionId);
        var captured = reference.at("/capture/result");
        String reason = captured.path("reasonCode").asText(reference.path("reasonCode").asText(
                captured.isObject() ? "REFERENCE_COMPARATOR_FACTS_RECORDED" : "REFERENCE_FACTS_NOT_RECORDED"));
        int excluded = 0, unresolved = 0;
        for (var decision : captured.path("candidateDecisions")) {
            if ("NOT_APPLICABLE".equals(decision.path("status").asText())) excluded++;
            else if (!"READY".equals(decision.path("status").asText())) unresolved++;
        }
        String search = "UNAVAILABLE";
        if (Set.of("SELECTED_TARGET_MISSENSE_CONSEQUENCE_REQUIRED", "TARGET_NATIVE_SNV_REQUIRED").contains(reason)) search = "NOT_APPLICABLE";
        else if ("READY".equals(captured.at("/discovery/readinessStatus").asText()))
            search = unresolved > 0 || !(Set.of("READY", "NO_RECORD").contains(captured.path("status").asText())
                    || "NO_UNIQUE_CONDITION_AWARE_COMPARATOR".equals(reason)) ? "INCOMPLETE" : "COMPLETE";
        out.put("searchStatus", search).put("searchReasonCode", safeReason(reason));
        out.put("discoveredCandidates", captured.path("candidateDecisions").size()).put("excludedCandidates", excluded)
                .put("unresolvedCandidates", unresolved).put("comparableReferences", captured.path("comparators").size());
        int matches = 0;
        var records = out.putArray("references");
        var ps1 = reference.at("/evaluations/PS1");
        var differentSubstitutions = new LinkedHashMap<String, JsonNode>();
        if (MissenseComparisonRuntime.PS1.equals(criterionId)) {
            for (var assessment : ps1.path("referenceAssessments")) {
                if (MissenseComparisonRuntime.PS1_DIFFERENT_SUBSTITUTION.equals(assessment.path("reasonCode").asText()))
                    differentSubstitutions.merge(assessment.path("variationId").asText(), assessment,
                            (prior, next) -> "ELIGIBLE".equals(next.path("status").asText()) ? next : prior);
            }
        }
        for (var row : captured.path("comparators")) {
            String relation = row.path("proteinRelation").asText();
            String variationId = row.path("variationId").asText();
            if (matches(criterionId, relation)) matches++;
            var record = records.addObject().put("variationId", variationId).put("proteinRelation", relation)
                    .put("sourceAssertionCount", row.path("submissions").size());
            var assessment = differentSubstitutions.get(variationId);
            if (assessment != null) record.putObject("nativeProteinComparison")
                    .put("target", ps1.at("/targetIdentity/proteinVariantKey").asText())
                    .put("reference", assessment.at("/nativeProteinIdentity/proteinVariantKey").asText())
                    .put("independentReference", "ELIGIBLE".equals(assessment.path("status").asText()));
        }
        out.put("criterionMatches", matches);
        return publicView(mapper, out, criterionId);
    }

    /** Fail closed for malformed saved summaries, and copy only known safe scalar fields. */
    public static ObjectNode publicView(ObjectMapper mapper, JsonNode summary, String criterionId) {
        var empty = mapper.createObjectNode();
        if (!Set.of("ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5").contains(criterionId)
                || !criterionId.equals(summary.path("criterionId").asText())
                || !SCHEMA.equals(summary.path("schemaVersion").asText()) || !VERSION.equals(summary.path("version").asText())
                || !"EXPLANATION_ONLY".equals(summary.path("purpose").asText())
                || !summary.path("scientificApprovalGranted").isBoolean() || summary.path("scientificApprovalGranted").asBoolean()
                || !STATES.contains(summary.path("searchStatus").asText()) || !summary.path("references").isArray()) return empty;
        for (String count : COUNTS) if (!count(summary.path(count), 256)) return empty;
        int refs = summary.path("comparableReferences").asInt();
        if (refs != summary.path("references").size() || refs + summary.path("excludedCandidates").asInt()
                + summary.path("unresolvedCandidates").asInt() != summary.path("discoveredCandidates").asInt()) return empty;
        if (Set.of("NOT_APPLICABLE", "UNAVAILABLE").contains(summary.path("searchStatus").asText()) && refs != 0) return empty;
        if ("COMPLETE".equals(summary.path("searchStatus").asText()) && summary.path("unresolvedCandidates").asInt() != 0) return empty;
        var result = mapper.createObjectNode();
        for (String field : List.of("schemaVersion", "version", "purpose", "scientificApprovalGranted", "criterionId", "searchStatus"))
            result.set(field, summary.path(field).deepCopy());
        result.put("searchReasonCode", safeReason(summary.path("searchReasonCode").asText()));
        for (String count : COUNTS) result.set(count, summary.path(count));
        var rows = result.putArray("references"); var ids = new HashSet<String>(); int matched = 0;
        for (var row : summary.path("references")) {
            String id = row.path("variationId").asText(), relation = row.path("proteinRelation").asText();
            if (!id.matches("[1-9][0-9]{0,17}") || !ids.add(id) || !RELATIONS.contains(relation)
                    || !count(row.path("sourceAssertionCount"), 1024)) return empty;
            if (matches(criterionId, relation)) matched++;
            var record = rows.addObject().put("variationId", id).put("proteinRelation", relation)
                    .put("sourceAssertionCount", row.path("sourceAssertionCount").asInt());
            var comparison = row.path("nativeProteinComparison");
            if (MissenseComparisonRuntime.PS1.equals(criterionId) && "SAME_RESIDUE_DIFFERENT_AMINO_ACID".equals(relation)
                    && comparison.path("independentReference").isBoolean()) {
                String target = comparison.path("target").asText(), reference = comparison.path("reference").asText();
                if (differentSubstitutions(target, reference)) record.putObject("nativeProteinComparison")
                        .put("target", target).put("reference", reference)
                        .put("independentReference", comparison.path("independentReference").asBoolean());
            }
        }
        return matched == summary.path("criterionMatches").asInt() ? result : empty;
    }

    private static boolean differentSubstitutions(String target, String reference) {
        String key = "[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]{0,5}[ACDEFGHIKLMNPQRSTVWY]";
        return target.matches(key) && reference.matches(key) && !target.equals(reference)
                && target.length() == reference.length() && target.regionMatches(0, reference, 0, target.length() - 1);
    }

    private static boolean count(JsonNode value, int maximum) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0 && value.asInt() <= maximum;
    }
    private static boolean matches(String criterion, String relation) {
        return ("ACMG_AMP_2015:PS1".equals(criterion) ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID").equals(relation);
    }
    private static String safeReason(String value) { return value.matches("[A-Z][A-Z0-9_]{0,200}") ? value : "REFERENCE_SOURCE_REASON_UNAVAILABLE"; }
}
