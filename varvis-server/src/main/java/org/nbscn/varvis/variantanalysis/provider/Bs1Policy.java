package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Finite BS1 policy language. All knowledge is data; unknown conditions fail closed. */
final class Bs1Policy {
    static final String SCHEMA = "population-frequency-policy-v2";
    static final String CRITERION = "ACMG_AMP_2015:BS1";
    private static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE");
    private Bs1Policy() { }

    static String issue(JsonNode policy) {
        if (policy == null || !policy.isObject() || !SCHEMA.equals(policy.path("schemaVersion").asText())
                || !CRITERION.equals(policy.path("criterionId").asText())) return "BS1_POLICY_SCHEMA_INVALID";
        if (!keys(policy, Set.of("schemaVersion", "policyId", "version", "criterionId", "sources", "sourceCompleteness",
                "semanticSupport", "identityResolution", "activation", "scope", "statistic", "tiers", "commonConditions",
                "exceptionSet", "requiredContext", "qualityContract", "resourceCompatibility", "interpretation",
                "dataScope", "sourcePolicyId", "sourcePolicyVersion", "sourcePolicySha256", "knowledgeRecord", "knowledgeDecision"))) return "BS1_POLICY_UNKNOWN_CONDITION";
        if (Bs1KnowledgeCatalog.isPolicy(policy)) {
            String knowledgeIssue = Bs1KnowledgeCatalog.issue(policy);
            if (knowledgeIssue != null) return knowledgeIssue;
        } else if (policy.has("knowledgeRecord") || policy.has("knowledgeDecision")) return "BS1_POLICY_UNKNOWN_CONDITION";
        if (policy.path("policyId").asText().isBlank() || policy.path("version").asText().isBlank()
                || !sources(policy.path("sources"))) return "BS1_POLICY_SOURCE_REQUIRED";
        if (!"COMPLETE".equals(policy.path("sourceCompleteness").asText())) return "BS1_POLICY_SOURCE_INCOMPLETE";
        if (!"SUPPORTED".equals(policy.path("semanticSupport").asText())) return "BS1_POLICY_SEMANTICS_UNSUPPORTED";
        if (!"RESOLVED".equals(policy.path("identityResolution").asText())) return "BS1_POLICY_IDENTITY_UNRESOLVED";
        var scope = policy.path("scope");
        boolean sexStatistic = Bs1SexChromosomePolicy.isStatistic(policy);
        Set<String> modes = sexStatistic ? Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "Y_LINKED")
                : Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE");
        if (!keys(scope, sexStatistic ? Set.of("hgncId", "mondoId", "modes", "geneModes", "modeSelection", "variantTypes", "transcripts", "sexChromosome")
                : Set.of("hgncId", "mondoId", "modes", "geneModes", "modeSelection", "variantTypes", "transcripts"))
                || !scope.path("hgncId").asText().matches("HGNC:[1-9][0-9]*")
                || !scope.path("mondoId").asText().matches("MONDO:[0-9]{7}")
                || !strings(scope.path("modes"), modes)
                || !strings(scope.path("variantTypes"), Set.of("SNV", "SMALL_INSERTION", "SMALL_DELETION"))
                || !Set.of("EXACT_DECLARED_MODE", "DUAL_MODE_USE_AR").contains(scope.path("modeSelection").asText())) return "BS1_POLICY_SCOPE_UNSUPPORTED";
        if (scope.has("geneModes") && !strings(scope.path("geneModes"), modes))
            return "BS1_POLICY_MODE_SELECTION_INVALID";
        if ("DUAL_MODE_USE_AR".equals(scope.path("modeSelection").asText())
                && !(contains(scope.path("geneModes"), "AUTOSOMAL_DOMINANT") && contains(scope.path("geneModes"), "AUTOSOMAL_RECESSIVE")))
            return "BS1_POLICY_MODE_SELECTION_INVALID";
        var statistic = policy.path("statistic");
        boolean targetAf = Bs1FrequencyStatistic.isAf(policy) || Bs1SexChromosomePolicy.isAf(policy);
        if (sexStatistic) {
            String metricIssue = Bs1SexChromosomePolicy.issue(policy);
            if (metricIssue != null) return metricIssue;
        } else if (targetAf) {
            String metricIssue = Bs1FrequencyStatistic.issue(policy);
            if (metricIssue != null) return metricIssue;
        } else if (!keys(statistic, Set.of("metricId", "unit", "confidence", "ancestries"))
                || !(Bs1LocalExomes.isPolicy(policy) ? "gnomad.exomes.faf95max" : "gnomad.joint.faf95max").equals(statistic.path("metricId").asText())
                || !"fraction".equals(statistic.path("unit").asText())
                || !"95_PERCENT_LOWER_BOUND".equals(statistic.path("confidence").asText())
                || !strings(statistic.path("ancestries"), Set.of("afr", "amr", "eas", "nfe", "sas", "mid"))) return "BS1_POLICY_STATISTIC_UNSUPPORTED";
        if (!conditions(policy.path("commonConditions"), Bs1KnowledgeCatalog.isPolicy(policy), targetAf)) return "BS1_POLICY_UNKNOWN_CONDITION";
        var required = policy.path("requiredContext");
        if (!keys(required, Set.of("transcript")) || !Set.of("NOT_REQUIRED_BY_POLICY", "REQUIRED_BY_SCOPE").contains(required.path("transcript").asText()))
            return "BS1_POLICY_CONTEXT_REQUIREMENTS_INVALID";
        if ("REQUIRED_BY_SCOPE".equals(required.path("transcript").asText())) {
            if (!scope.path("transcripts").isArray() || scope.path("transcripts").isEmpty()) return "BS1_POLICY_TRANSCRIPT_SCOPE_INVALID";
            for (var tx : scope.path("transcripts")) if (!keys(tx, Set.of("accession", "version"))
                    || !tx.path("accession").asText().matches("(?:NM|NR)_[0-9]+|ENST[0-9]+")
                    || !tx.path("version").isIntegralNumber() || tx.path("version").asInt() < 1) return "BS1_POLICY_TRANSCRIPT_SCOPE_INVALID";
        }
        else if (scope.has("transcripts") && (!scope.path("transcripts").isArray() || !scope.path("transcripts").isEmpty()))
            return "BS1_POLICY_TRANSCRIPT_REQUIREMENT_CONFLICT";
        if (policy.has("interpretation") && !policy.path("interpretation").isTextual()) return "BS1_POLICY_UNKNOWN_CONDITION";
        var tiers = policy.path("tiers"); var priorities = new HashSet<String>();
        if (!tiers.isArray() || tiers.isEmpty()) return "BS1_POLICY_TIERS_REQUIRED";
        for (var tier : tiers) {
            if (!keys(tier, Set.of("tierId", "moi", "priority", "strength", "conditions", "sourceLocator"))
                    || tier.path("tierId").asText().isBlank() || tier.path("sourceLocator").asText().isBlank()
                    || !(contains(scope.path("modes"), tier.path("moi").asText())
                        || "DUAL_MODE_USE_AR".equals(scope.path("modeSelection").asText()) && "AUTOSOMAL_RECESSIVE".equals(tier.path("moi").asText()))
                    || !tier.path("priority").isIntegralNumber() || tier.path("priority").asInt() < 1
                    || !priorities.add(tier.path("moi").asText() + ":" + tier.path("priority").asText())
                    || !Set.of("STRONG", "SUPPORTING").contains(tier.path("strength").asText())
                    || tier.path("conditions").isEmpty() || !conditions(tier.path("conditions"), Bs1KnowledgeCatalog.isPolicy(policy), targetAf)) return "BS1_POLICY_TIER_UNSUPPORTED";
        }
        var compatibility = policy.path("resourceCompatibility");
        if (!keys(compatibility, Set.of("status", "resourceVersion", "resourceSha256", "basis"))
                || !sha(compatibility.path("resourceSha256")) || compatibility.path("basis").asText().isBlank()) return "BS1_RESOURCE_CONTRACT_INVALID";
        if (Bs1LocalExomes.isPolicy(policy) && !Bs1LocalExomes.validPolicyScope(policy)) return "BS1_LOCAL_SCOPE_INVALID";
        return Bs1Quality.issue(policy.path("qualityContract"));
    }

    static ObjectNode select(ObjectMapper mapper, List<JsonNode> policies, JsonNode context) {
        var receipt = mapper.createObjectNode().put("schemaVersion", "bs1-selection-v1").put("criterionId", CRITERION)
                .put("contextId", context.path("contextId").asText());
        var matches = policies.stream().filter(p -> p.at("/scope/hgncId").equals(context.at("/gene/hgncId"))
                && p.at("/scope/mondoId").equals(context.at("/disease/mondoId"))
                && (contains(p.at("/scope/modes"), context.path("moi").asText())
                    || !"RESOLVED".equals(p.path("identityResolution").asText()) && p.at("/scope/modes").isEmpty())).toList();
        if (matches.size() != 1) return blocked(receipt, matches.isEmpty() ? "BS1_POLICY_NO_RECORD" : "BS1_POLICY_AMBIGUOUS");
        var policy = matches.get(0); var adapter = new RuntimeEvidenceV2Adapter(mapper);
        receipt.set("policy", policy.deepCopy()); receipt.put("policySha256", adapter.canonicalSha256(policy));
        String issue = issue(policy);
        if (issue != null) return blocked(receipt, issue);
        String mode = "DUAL_MODE_USE_AR".equals(policy.at("/scope/modeSelection").asText()) ? "AUTOSOMAL_RECESSIVE" : context.path("moi").asText();
        receipt.put("selectedMode", mode).put("modeSelection", policy.at("/scope/modeSelection").asText());
        receipt.put("transcriptRequirement", policy.at("/requiredContext/transcript").asText());
        if (!contains(policy.at("/scope/variantTypes"), context.at("/variant/variantType").asText())) return blocked(receipt, "BS1_VARIANT_SCOPE_UNSUPPORTED");
        if (Bs1SexChromosomePolicy.isStatistic(policy)) {
            var region = SexChromosomeRegionResolver.assess(mapper, context.at("/variant/evidenceQueryAllele"));
            receipt.set("sexChromosomeRegion", region);
            String regionIssue = Bs1SexChromosomePolicy.regionIssue(mapper, policy, context);
            if (regionIssue != null) return blocked(receipt, regionIssue);
        } else if (!context.at("/variant/evidenceQueryAllele/contig").asText().matches("[1-9]|1[0-9]|2[0-2]"))
            return blocked(receipt, "BS1_SEX_CHROMOSOME_NOT_IMPLEMENTED");
        if ("REQUIRED_BY_SCOPE".equals(receipt.path("transcriptRequirement").asText())) {
            if (!context.has("transcript")) return blocked(receipt.put("status", "NEEDS_INPUT"), "BS1_TRANSCRIPT_REQUIRED_BY_SCOPE");
            boolean match = false;
            for (var tx : policy.at("/scope/transcripts")) match |= tx.path("accession").equals(context.at("/transcript/accession")) && tx.path("version").equals(context.at("/transcript/version"));
            if (!match) return blocked(receipt, "BS1_TRANSCRIPT_VERSION_NOT_BRIDGED");
        }
        ObjectNode exceptions = exceptions(mapper, policy, context); receipt.set("exceptionResolution", exceptions);
        if ("UNRESOLVED".equals(exceptions.path("status").asText())) {
            receipt.put("transcriptRequirement", "REQUIRED_BY_EXCEPTION");
            return blocked(receipt, "BS1_EXCEPTION_IDENTITY_UNRESOLVED");
        }
        if ("HIT".equals(exceptions.path("status").asText())) return blocked(receipt.put("applicability", "NOT_APPLICABLE"), "BS1_POLICY_EXCEPTION_HIT");
        if (!"COMPATIBLE".equals(policy.at("/resourceCompatibility/status").asText())) return blocked(receipt, "BS1_RESOURCE_COMPATIBILITY_PENDING");
        if (!Set.of("ISOLATED_DEVELOPMENT_ONLY", "LOCAL_BUSINESS_RELEASE_V1", "LOCAL_EXOMES_BUSINESS_V1", Bs1KnowledgeCatalog.ACTIVATION, Bs1KnowledgeCatalog.JOINT_ACTIVATION).contains(policy.path("activation").asText())) return blocked(receipt, "BS1_POLICY_NOT_EXECUTABLE");
        receipt.put("status", "READY");
        receipt.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(receipt));
        return receipt;
    }

    static ObjectNode exceptions(ObjectMapper mapper, JsonNode policy, JsonNode context) {
        var result = mapper.createObjectNode(); var checks = result.putArray("checks");
        var set = policy.path("exceptionSet");
        if (!keys(set, Set.of("setId", "version", "criterionId", "status", "entries", "sourceLocator"))
                || !CRITERION.equals(set.path("criterionId").asText()) || set.path("setId").asText().isBlank()
                || set.path("version").asText().isBlank() || set.path("sourceLocator").asText().isBlank()
                || !"COMPLETE_IN_PINNED_SCOPE".equals(set.path("status").asText()) || !set.path("entries").isArray()) return result.put("status", "UNRESOLVED");
        result.put("setSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(set));
        boolean unresolved = false, hit = false;
        for (var entry : set.path("entries")) {
            var check = checks.addObject().put("entryId", entry.path("entryId").asText());
            if (!keys(entry, Set.of("entryId", "hgncId", "mondoId", "transcript", "cdna", "protein", "clinvarId", "sourceLocator",
                    "identityStatus", "queryAllele", "mappingSource", "disposition")) || entry.path("entryId").asText().isBlank()
                    || !entry.path("hgncId").asText().matches("HGNC:[1-9][0-9]*")
                    || !entry.path("mondoId").asText().matches("MONDO:[0-9]{7}")) {
                check.put("status", "UNRESOLVED"); unresolved = true; continue;
            }
            if (!entry.path("hgncId").equals(context.at("/gene/hgncId")) || !entry.path("mondoId").equals(context.at("/disease/mondoId"))) {
                check.put("status", "OUT_OF_SCOPE"); continue;
            }
            if (!"VERIFIED_ALLELE".equals(entry.path("identityStatus").asText()) || !sources(entry.path("mappingSource"))
                    || !"NOT_APPLICABLE".equals(entry.path("disposition").asText())
                    || !validScopedAllele(entry.path("queryAllele"), Bs1SexChromosomePolicy.isStatistic(policy))
                    || !entry.at("/queryAllele/assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))) {
                check.put("status", "UNRESOLVED"); unresolved = true; continue;
            }
            boolean same = entry.path("queryAllele").equals(context.at("/variant/evidenceQueryAllele"));
            check.put("status", same ? "HIT" : "NOT_HIT_IN_VERIFIED_SCOPE"); hit |= same;
        }
        return result.put("status", hit ? "HIT" : unresolved ? "UNRESOLVED" : set.path("entries").isEmpty()
                ? "NOT_REQUIRED_BY_POLICY" : "NOT_HIT_IN_VERIFIED_SCOPE");
    }

    static String condition(JsonNode condition, Map<String, BigDecimal> facts) {
        BigDecimal actual = facts.get(condition.path("fact").asText());
        if (actual == null) return "UNKNOWN";
        int c = actual.compareTo(new BigDecimal(condition.path("decimal").asText()));
        return switch (condition.path("operator").asText()) {
            case "GT" -> c > 0 ? "TRUE" : "FALSE";
            case "GTE" -> c >= 0 ? "TRUE" : "FALSE";
            case "LT" -> c < 0 ? "TRUE" : "FALSE";
            case "LTE" -> c <= 0 ? "TRUE" : "FALSE";
            default -> "UNKNOWN";
        };
    }
    static boolean validScopedAllele(JsonNode allele, boolean sexChromosome) {
        if (!sexChromosome) return validAllele(allele);
        if (!Set.of("X", "Y").contains(allele.path("contig").asText())) return false;
        var region = SexChromosomeRegionResolver.assess(new ObjectMapper(), allele);
        return "RESOLVED".equals(region.path("status").asText())
                && Set.of("PAR", "NON_PAR").contains(region.path("region").asText());
    }
    static boolean validAllele(JsonNode allele) {
        return keys(allele, Set.of("assembly", "contig", "position", "reference", "alternate"))
                && "GRCh38".equals(allele.path("assembly").asText())
                && allele.path("contig").asText().matches("[1-9]|1[0-9]|2[0-2]")
                && allele.path("position").isIntegralNumber() && allele.path("position").asLong() > 0
                && allele.path("reference").asText().matches("[ACGT]{1,50}")
                && allele.path("alternate").asText().matches("[ACGT]{1,50}")
                && !allele.path("reference").equals(allele.path("alternate"));
    }
    private static boolean conditions(JsonNode conditions, boolean allowModel, boolean targetAf) {
        if (!conditions.isArray()) return false;
        for (var c : conditions) {
            if (!keys(c, Set.of("fact", "operator", "decimal", "unit", "originalText", "basis", "sourceLocator"))
                    || !Set.of(targetAf ? "AF" : "FAF95", "AN", "AC").contains(c.path("fact").asText())
                    || !OPERATORS.contains(c.path("operator").asText()) || !c.path("decimal").isTextual()
                    || c.path("originalText").asText().isBlank() || c.path("sourceLocator").asText().isBlank()
                    || !(Set.of("OFFICIAL_CONDITION", "LOCAL_ENGINEERING_SAFEGUARD").contains(c.path("basis").asText())
                        || allowModel && "REVIEWED_MODEL_CONDITION".equals(c.path("basis").asText()))) return false;
            try {
                var d = new BigDecimal(c.path("decimal").asText());
                boolean faf = Set.of("FAF95", "AF").contains(c.path("fact").asText());
                if (d.signum() < 0 || faf && d.compareTo(BigDecimal.ONE) > 0 || !faf && d.stripTrailingZeros().scale() > 0
                        || !(faf ? "fraction" : "alleles").equals(c.path("unit").asText())) return false;
            } catch (NumberFormatException invalid) { return false; }
        }
        return true;
    }
    static boolean keys(JsonNode value, Set<String> allowed) {
        if (!value.isObject()) return false;
        for (var it = value.fieldNames(); it.hasNext();) if (!allowed.contains(it.next())) return false;
        return true;
    }
    static boolean sha(JsonNode value) { return value.asText().matches("[0-9a-f]{64}"); }
    static boolean contains(JsonNode values, String value) { for (var v : values) if (value.equals(v.asText())) return true; return false; }
    private static boolean strings(JsonNode values, Set<String> allowed) {
        if (!values.isArray() || values.isEmpty()) return false;
        var unique = new HashSet<String>();
        for (var v : values) if (!v.isTextual() || !allowed.contains(v.asText()) || !unique.add(v.asText())) return false;
        return true;
    }
    static boolean sources(JsonNode sources) {
        if (!sources.isArray() || sources.isEmpty()) return false;
        for (var s : sources) if (!keys(s, Set.of("sourceId", "url", "version", "sha256", "locator"))
                || s.path("sourceId").asText().isBlank() || !s.path("url").asText().startsWith("https://")
                || s.path("version").asText().isBlank() || !sha(s.path("sha256")) || s.path("locator").asText().isBlank()) return false;
        return true;
    }
    private static ObjectNode blocked(ObjectNode result, String reason) {
        if (!result.has("status")) result.put("status", "BLOCKED");
        return result.put("reasonCode", reason);
    }
}
