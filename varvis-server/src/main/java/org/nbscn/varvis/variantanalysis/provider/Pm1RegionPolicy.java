package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite reviewed PM1 material contract. It validates declarations, never discovers or approves regions. */
final class Pm1RegionPolicy {
    private static final Set<String> REVIEW_FIELDS = Set.of("status", "reviewId", "sourceIds", "clause", "scopeSha256");
    private static final Set<String> DISTRIBUTIONS = Set.of("SATISFIED", "NOT_SATISFIED", "UNKNOWN");
    private final RuntimeEvidenceV2Adapter adapter;

    Pm1RegionPolicy(RuntimeEvidenceV2Adapter adapter) { this.adapter = adapter; }

    String validate(ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode model, ObjectNode regions) {
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope", "branch",
                "mechanism", "consequenceScope", "protein", "transcriptModelBinding", "regionMaterialBinding",
                "scientificSources", "authoritySourceIds"))
                || !only(policy.path("scope"), Set.of("hgncId", "mondoId", "moi", "assembly", "variantType", "transcriptMode", "transcripts")))
            return "PM1_POLICY_OPERATOR_UNSUPPORTED";
        if (!policyIdentityValid(policy)
                || !"NATIVE_MISSENSE_PROTEIN_REGION".equals(policy.path("branch").asText())
                || !"DISEASE_SPECIFIC_CRITICAL_PROTEIN_LOCATION".equals(policy.path("mechanism").asText())
                || !"PURE_MISSENSE_SNV".equals(policy.path("consequenceScope").asText())) return "PM1_POLICY_INVALID";
        if (!selectionIdentityValid(context, policy, selection)) return "PM1_SELECTION_IDENTITY_INVALID";
        JsonNode scope = policy.path("scope");
        if (!context.at("/gene/hgncId").equals(scope.path("hgncId")) || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi")) || !"GRCh38".equals(scope.path("assembly").asText())
                || !"GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                || !"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText())
                || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText())
                || !scope.path("transcripts").isArray() || scope.path("transcripts").size() != 1
                || !only(scope.at("/transcripts/0"), Set.of("accession", "version"))
                || !context.at("/transcript/accession").equals(scope.at("/transcripts/0/accession"))
                || !context.at("/transcript/version").equals(scope.at("/transcripts/0/version"))) return "PM1_POLICY_CONTEXT_MISMATCH";
        if (!bound(policy.path("transcriptModelBinding"), model, "modelId", "modelVersion")
                || !policy.path("protein").equals(model.path("protein"))) return "PM1_TRANSCRIPT_MODEL_IDENTITY_INVALID";
        if (!bound(policy.path("regionMaterialBinding"), regions, "materialId", "materialVersion")) return "PM1_REGION_MATERIAL_IDENTITY_INVALID";
        JsonNode sources = policy.path("scientificSources");
        if (!sources(sources) || !CodingTranscriptMapping.modelSourcesBound(model, sources)
                || !sources(regions.path("sourceRecords"))) return "PM1_MATERIAL_SOURCE_INVALID";
        for (JsonNode source : regions.path("sourceRecords")) if (!contains(sources, source)) return "PM1_MATERIAL_SOURCE_INVALID";
        String authority = authorityIssue(policy);
        if (authority != null) return authority;
        String review = reviewIssue(policy.path("review"), scope, sources);
        if (review != null) return "PM1_POLICY_" + review;
        String modelIssue = CodingTranscriptMapping.modelIssue(model, context);
        if (modelIssue != null) return modelIssue.replace("CODING_TRANSCRIPT_MODEL_", "CODING_MISSENSE_MODEL_");
        if (!only(regions, Set.of("schemaVersion", "materialId", "materialVersion", "modelSha256", "assembly", "contig",
                "hgncId", "transcript", "protein", "sourceRecords", "review", "reviewedCoverage", "rules", "precedence")))
            return "PM1_REGION_OPERATOR_UNSUPPORTED";
        if (!"pm1-protein-region-material-v1".equals(regions.path("schemaVersion").asText())
                || !adapter.canonicalSha256(model).equals(regions.path("modelSha256").asText())) return "PM1_REGION_MATERIAL_IDENTITY_INVALID";
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein"))
            if (!regions.path(field).equals(model.path(field))) return "PM1_REGION_MATERIAL_IDENTITY_INVALID";
        ObjectNode reviewedScope = regions.deepCopy();
        reviewedScope.retain(List.of("modelSha256", "assembly", "contig", "hgncId", "transcript", "protein", "reviewedCoverage"));
        review = reviewIssue(regions.path("review"), reviewedScope, regions.path("sourceRecords"));
        if (review != null) return "PM1_REGION_" + review;
        int length = model.path("proteinLength").asInt();
        JsonNode coverage = regions.path("reviewedCoverage");
        if (!coverage.isArray() || coverage.isEmpty()) return "PM1_REGION_COVERAGE_MISSING";
        for (int i = 0; i < coverage.size(); i++) {
            JsonNode row = coverage.path(i);
            if (!only(row, Set.of("start", "end"))) return "PM1_REGION_OPERATOR_UNSUPPORTED";
            if (!interval(row, length)) return "PM1_REGION_INTERVAL_INVALID";
            for (int j = 0; j < i; j++) if (overlap(row, coverage.path(j))) return "PM1_REGION_COVERAGE_AMBIGUOUS";
        }
        JsonNode rules = regions.path("rules");
        if (!rules.isArray() || rules.size() > 1000) return "PM1_REGION_MATERIAL_INVALID";
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rules) {
            if (!only(row, Set.of("ruleId", "start", "end", "referenceAminoAcid", "regionType", "effect", "strength", "qualification",
                    "pathogenicDistribution", "benignDistribution", "review"))) return "PM1_REGION_OPERATOR_UNSUPPORTED";
            if (!text(row.path("ruleId")) || !ids.add(row.path("ruleId").asText())) return "PM1_REGION_MATERIAL_INVALID";
            if (!interval(row, length)) return "PM1_REGION_INTERVAL_INVALID";
            if (!Set.of("MUTATIONAL_HOTSPOT", "CRITICAL_FUNCTIONAL_REGION", "CRITICAL_RESIDUE", "ORDINARY_DOMAIN", "EXCLUSION")
                    .contains(row.path("regionType").asText())
                    || !Set.of("SUPPORT", "EXCLUDE").contains(row.path("effect").asText())
                    || !Set.of("QUALIFIED", "UNQUALIFIED", "UNKNOWN").contains(row.path("qualification").asText())
                    || !DISTRIBUTIONS.contains(row.path("pathogenicDistribution").asText())
                    || !DISTRIBUTIONS.contains(row.path("benignDistribution").asText())) return "PM1_REGION_OPERATOR_UNSUPPORTED";
            if (!("SUPPORT".equals(row.path("effect").asText())
                    ? Set.of("MODERATE", "SUPPORTING").contains(row.path("strength").asText())
                    : "NONE".equals(row.path("strength").asText()))) return "PM1_STRENGTH_UNSUPPORTED";
            if ("EXCLUSION".equals(row.path("regionType").asText()) != "EXCLUDE".equals(row.path("effect").asText()))
                return "PM1_REGION_MATERIAL_INVALID";
            if ("CRITICAL_RESIDUE".equals(row.path("regionType").asText()) && !row.has("referenceAminoAcid"))
                return "PM1_REFERENCE_RESIDUE_MISSING";
            if (row.has("referenceAminoAcid")) {
                if (row.path("start").asInt() != row.path("end").asInt() || !row.path("referenceAminoAcid").asText().matches("[ACDEFGHIKLMNPQRSTVWY]"))
                    return "PM1_REFERENCE_RESIDUE_INVALID";
                int start = (row.path("start").asInt() - 1) * 3;
                if (!Character.toString(TruncatingSequence.translateCodon(model.path("cdsSequence").asText().substring(start, start + 3)))
                        .equals(row.path("referenceAminoAcid").asText())) return "PM1_REFERENCE_RESIDUE_MISMATCH";
            }
            review = reviewIssue(row.path("review"), withoutReview(row), regions.path("sourceRecords"));
            if (review != null && !"REVIEW_MISSING".equals(review)) return "PM1_REGION_" + review;
        }
        JsonNode precedence = regions.path("precedence");
        if (!precedence.isArray() || precedence.size() > 1000) return "PM1_REGION_PRECEDENCE_INVALID";
        Set<String> pairs = new HashSet<>();
        Map<String, Set<String>> graph = graph(precedence);
        for (JsonNode relation : precedence) {
            if (!only(relation, Set.of("higherRuleId", "lowerRuleId", "review"))) return "PM1_REGION_OPERATOR_UNSUPPORTED";
            String high = relation.path("higherRuleId").asText(), low = relation.path("lowerRuleId").asText();
            if (!ids.contains(high) || !ids.contains(low) || high.equals(low) || !pairs.add(high + "\n" + low))
                return "PM1_REGION_PRECEDENCE_INVALID";
            review = reviewIssue(relation.path("review"), withoutReview(relation), regions.path("sourceRecords"));
            if (review != null) return "PM1_REGION_PRECEDENCE_" + review;
            if (reaches(graph, low, high, new HashSet<>())) return "PM1_REGION_PRECEDENCE_CONFLICT";
        }
        return null;
    }

    /** The v2 envelope may name only an actual policy selection and its complete authority identities. */
    boolean evaluationIdentityValid(ObjectNode context, ObjectNode policy, ObjectNode selection) {
        return policyIdentityValid(policy) && sources(policy.path("scientificSources")) && authorityIssue(policy) == null
                && selectionIdentityValid(context, policy, selection);
    }

    private static boolean policyIdentityValid(JsonNode policy) {
        return "pm1-protein-region-policy-v1".equals(policy.path("schemaVersion").asText())
                && text(policy.path("policyId")) && policy.path("policyId").asText().length() <= 128
                && text(policy.path("policyVersion")) && policy.path("policyVersion").asText().length() <= 64
                && policy.path("criterionIds").isArray() && policy.path("criterionIds").size() == 1
                && Pm1CriterionEvaluator.CRITERION.equals(policy.at("/criterionIds/0").asText());
    }

    private boolean selectionIdentityValid(ObjectNode context, ObjectNode policy, ObjectNode selection) {
        ObjectNode receiptIdentity = selection.deepCopy(); receiptIdentity.remove("selectionId");
        return "acmg-specification-selection-v1".equals(selection.path("schemaVersion").asText())
                && Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(selection.path("outcome").asText())
                && ("cspec-selection-sha256-" + adapter.canonicalSha256(receiptIdentity)).equals(selection.path("selectionId").asText())
                && context.path("contextId").equals(selection.path("contextId"))
                && policy.path("policyId").equals(selection.path("policyId"))
                && policy.path("policyVersion").equals(selection.path("policyVersion"))
                && policy.path("criterionIds").equals(selection.path("criterionIds"))
                && adapter.canonicalSha256(policy).equals(selection.path("policySha256").asText());
    }

    private static String authorityIssue(JsonNode policy) {
        if (!sourceIds(policy.path("authoritySourceIds"), policy.path("scientificSources"))) return "PM1_POLICY_AUTHORITY_MISSING";
        for (JsonNode id : policy.path("authoritySourceIds"))
            if (!id.asText().matches("authority-[a-z0-9][a-z0-9._-]{2,95}")) return "PM1_POLICY_AUTHORITY_INVALID";
        return null;
    }

    String resolve(ObjectNode regions, ObjectNode observation) {
        int position = observation.path("proteinPosition").asInt();
        boolean covered = false;
        for (JsonNode row : regions.path("reviewedCoverage")) if (covers(row, position)) covered = true;
        if (!covered) return "PM1_REGION_COVERAGE_INCOMPLETE";
        List<JsonNode> matches = new ArrayList<>();
        var matched = observation.putArray("matchedRules");
        for (JsonNode row : regions.path("rules")) if (covers(row, position)) { matches.add(row); matched.add(row.deepCopy()); }
        if (matches.isEmpty()) return "PM1_COVERED_POSITION_NOT_SUPPORTED";
        // A priority declaration orders reviewed decisions; it cannot turn missing science into a decision.
        for (JsonNode row : matches) {
            String review = reviewIssue(row.path("review"), withoutReview(row), regions.path("sourceRecords"));
            if (review != null) return "PM1_REGION_" + review;
            if ("ORDINARY_DOMAIN".equals(row.path("regionType").asText())) return "PM1_ORDINARY_DOMAIN_NOT_QUALIFIED";
            if ("UNKNOWN".equals(row.path("qualification").asText())) return "PM1_REGION_QUALIFICATION_UNKNOWN";
            if ("SUPPORT".equals(row.path("effect").asText()) && ("UNKNOWN".equals(row.path("pathogenicDistribution").asText())
                    || "UNKNOWN".equals(row.path("benignDistribution").asText()))) return "PM1_DISTRIBUTION_UNKNOWN";
        }
        JsonNode winner = Pm1RegionSelection.choose(matches, regions.path("precedence"));
        if (winner == null) return "PM1_REGION_CONFLICT";
        observation.set("selectedRule", winner.deepCopy());
        observation.set("reviewedPrecedence", regions.path("precedence").deepCopy());
        String review = reviewIssue(winner.path("review"), withoutReview(winner), regions.path("sourceRecords"));
        if (review != null) return "PM1_REGION_" + review;
        if ("ORDINARY_DOMAIN".equals(winner.path("regionType").asText())) return "PM1_ORDINARY_DOMAIN_NOT_QUALIFIED";
        if ("UNKNOWN".equals(winner.path("qualification").asText())) return "PM1_REGION_QUALIFICATION_UNKNOWN";
        if ("UNQUALIFIED".equals(winner.path("qualification").asText())) return "PM1_REGION_NOT_QUALIFIED";
        if ("EXCLUDE".equals(winner.path("effect").asText())) return "PM1_EXPLICIT_EXCLUSION";
        if ("UNKNOWN".equals(winner.path("pathogenicDistribution").asText()) || "UNKNOWN".equals(winner.path("benignDistribution").asText()))
            return "PM1_DISTRIBUTION_UNKNOWN";
        if ("NOT_SATISFIED".equals(winner.path("benignDistribution").asText())) return "PM1_BENIGN_DISTRIBUTION_NOT_MET";
        if ("NOT_SATISFIED".equals(winner.path("pathogenicDistribution").asText())) return "PM1_PATHOGENIC_DISTRIBUTION_NOT_MET";
        observation.put("strength", winner.path("strength").asText());
        return null;
    }

    private boolean bound(JsonNode binding, JsonNode material, String id, String version) {
        return only(binding, Set.of(id, version, "canonicalSha256")) && text(material.path(id)) && text(material.path(version))
                && binding.path(id).equals(material.path(id)) && binding.path(version).equals(material.path(version))
                && adapter.canonicalSha256(material).equals(binding.path("canonicalSha256").asText());
    }
    private String reviewIssue(JsonNode review, JsonNode scope, JsonNode sources) {
        if (!review.isObject()) return "REVIEW_MISSING";
        if (!only(review, REVIEW_FIELDS)) return "OPERATOR_UNSUPPORTED";
        if (!Set.of("REVIEWED_IMPLEMENTED", "UNREVIEWED").contains(review.path("status").asText())) return "REVIEW_STATUS_UNSUPPORTED";
        if (!"REVIEWED_IMPLEMENTED".equals(review.path("status").asText())) return "REVIEW_MISSING";
        if (!text(review.path("reviewId")) || !text(review.path("clause")) || !sourceIds(review.path("sourceIds"), sources))
            return "REVIEW_SOURCE_MISSING";
        return adapter.canonicalSha256(scope).equals(review.path("scopeSha256").asText()) ? null : "REVIEW_SCOPE_MISMATCH";
    }
    private static boolean sources(JsonNode sources) {
        if (!CodingTranscriptMapping.sourcesValid(sources)) return false;
        for (JsonNode source : sources) if (!only(source, Set.of("sourceId", "version", "url", "sha256", "locator", "sourceKind"))
                || !text(source.path("locator"))) return false;
        return true;
    }
    private static boolean sourceIds(JsonNode ids, JsonNode sources) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        Set<String> seen = new HashSet<>();
        for (JsonNode id : ids) {
            if (!text(id) || !seen.add(id.asText())) return false;
            boolean found = false;
            for (JsonNode source : sources) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }
    private static boolean contains(JsonNode nodes, JsonNode sought) { for (JsonNode value : nodes) if (value.equals(sought)) return true; return false; }
    private static boolean interval(JsonNode row, int length) {
        return row.path("start").isIntegralNumber() && row.path("start").canConvertToInt()
                && row.path("end").isIntegralNumber() && row.path("end").canConvertToInt()
                && row.path("start").asInt() >= 1 && row.path("end").asInt() >= row.path("start").asInt() && row.path("end").asInt() <= length;
    }
    private static boolean overlap(JsonNode a, JsonNode b) { return a.path("start").asInt() <= b.path("end").asInt() && b.path("start").asInt() <= a.path("end").asInt(); }
    private static boolean covers(JsonNode row, int position) { return Pm1RegionSelection.covers(row, position); }
    private static ObjectNode withoutReview(JsonNode value) { ObjectNode copy = ((ObjectNode) value).deepCopy(); copy.remove("review"); return copy; }
    private static Map<String, Set<String>> graph(JsonNode relations) { return Pm1RegionSelection.graph(relations); }
    private static boolean reaches(Map<String, Set<String>> graph, String from, String to, Set<String> visited) {
        return Pm1RegionSelection.reaches(graph, from, to, visited);
    }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
}
