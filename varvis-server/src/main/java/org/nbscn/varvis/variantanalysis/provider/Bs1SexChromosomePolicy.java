package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** Additional finite contract for source-qualified expert sex-chromosome thresholds. */
final class Bs1SexChromosomePolicy {
    static final String VERSION = "bs1-sex-chromosome-policy-v1";
    static final String AF = "gnomad.exomes.sex_ancestry_max_af";
    static final String FAF95 = "gnomad.exomes.sex_ancestry_max_faf95";
    static final String JOINT_AF = "gnomad.joint.sex_ancestry_max_af";
    static final String JOINT_FAF95 = "gnomad.joint.sex_ancestry_max_faf95";
    static final String JOINT_SCOPE = "BS1_JOINT_SEX_V1";
    private static final Set<String> ANCESTRIES = Set.of("afr", "amr", "eas", "nfe", "sas", "mid");
    private static final Set<String> MODES = Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE",
            "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "Y_LINKED");
    private Bs1SexChromosomePolicy() { }

    static boolean isPolicy(JsonNode policy) {
        return isStatistic(policy) || policy.at("/scope/sexChromosome").isObject();
    }

    static boolean isStatistic(JsonNode policy) {
        return Set.of(AF, FAF95, JOINT_AF, JOINT_FAF95).contains(policy.at("/statistic/metricId").asText());
    }

    static boolean isAf(JsonNode policy) { return Set.of(AF, JOINT_AF).contains(policy.at("/statistic/metricId").asText()); }

    static boolean isJoint(JsonNode policy) {
        return Set.of(JOINT_AF, JOINT_FAF95).contains(policy.at("/statistic/metricId").asText());
    }

    static com.fasterxml.jackson.databind.node.ObjectNode jointQuality(ObjectMapper mapper) {
        var quality = ((com.fasterxml.jackson.databind.node.ObjectNode) Bs1Runtime.policies(mapper).get(0).path("qualityContract")).deepCopy();
        quality.withObject("releaseSemantics").put("sourceLocator", "gnomad-joint-4.1-sex-observation-header.vcf.txt: independently captured X/Y headers; header identity only");
        return quality;
    }

    static com.fasterxml.jackson.databind.node.ObjectNode jointScope(ObjectMapper mapper) {
        return mapper.createObjectNode().put("policyId", JOINT_SCOPE).put("dataset", "JOINT").put("release", "4.1")
                .put("description", "Publisher JOINT ancestry/karyotype allele statistics with an explicitly source-qualified expert threshold.")
                .put("authorityNote", "No exome/genome sample summation; source karyotype does not establish patient ploidy or clinical adoption.");
    }

    /** The ordinary policy validator still applies its shared schema, conditions and quality checks. */
    static String issue(JsonNode policy) {
        if (!isStatistic(policy)) return "BS1_SEX_STATISTIC_UNSUPPORTED";
        var record = policy.path("knowledgeRecord");
        if (!Bs1KnowledgeCatalog.isPolicy(policy) || !"EXPERT_POLICY".equals(record.path("kind").asText())
                || !"Released".equals(record.path("publicationStatus").asText()) || record.has("model")
                || policy.at("/knowledgeDecision/calculation").isObject()) return "BS1_SEX_EXPERT_POLICY_REQUIRED";
        if (!record.has("qualification")) return "BS1_SEX_QUALIFICATION_REQUIRED";
        if (!Bs1Policy.keys(record.path("scope"), Set.of("hgncId", "mondoId", "modes", "sexChromosome")))
            return "BS1_SEX_RECORD_SCOPE_UNSUPPORTED";
        String qualification = Bs1KnowledgeQualification.issue(record);
        if (qualification != null) return qualification;
        if (!record.at("/expertPolicy/statistic").equals(policy.path("statistic"))
                || !record.at("/expertPolicy/scope").equals(policy.path("scope"))
                || !record.at("/scope/modes").equals(policy.at("/scope/modes"))
                || !record.path("sources").equals(policy.path("sources"))
                || !record.at("/expertPolicy/tiers").equals(policy.path("tiers"))
                || !record.at("/expertPolicy/commonConditions").equals(policy.path("commonConditions")))
            return "BS1_SEX_EXPERT_DERIVATION_CONFLICT";
        var scope = policy.path("scope"); var sex = scope.path("sexChromosome");
        if (!Bs1Policy.keys(sex, Set.of("assemblies", "contigs", "regions", "regionCatalogSha256", "requiredDataset", "sourceId", "sourceLocator"))
                || !strings(sex.path("assemblies"), Set.of("GRCh37", "GRCh38"))
                || !strings(sex.path("contigs"), Set.of("X", "Y"))
                || !strings(sex.path("regions"), Set.of("PAR", "NON_PAR"))
                || !Set.of("EXOMES", "JOINT").contains(sex.path("requiredDataset").asText())
                || !SexChromosomeRegionResolver.CATALOG_SHA256.equals(sex.path("regionCatalogSha256").asText())
                || !source(policy, sex.path("sourceId").asText())
                || sex.path("sourceLocator").asText().isBlank()
                || !sex.equals(record.at("/scope/sexChromosome"))
                || !sex.equals(record.at("/expertPolicy/scope/sexChromosome"))) return "BS1_SEX_REGION_SCOPE_INVALID";
        if (!strings(scope.path("modes"), MODES) || !"EXACT_DECLARED_MODE".equals(scope.path("modeSelection").asText()))
            return "BS1_SEX_MOI_UNSUPPORTED";
        for (var mode : scope.path("modes")) {
            if (mode.asText().startsWith("X_LINKED") && !only(sex.path("contigs"), "X")
                    || "Y_LINKED".equals(mode.asText()) && (!only(sex.path("contigs"), "Y") || !only(sex.path("regions"), "NON_PAR"))
                    || mode.asText().startsWith("AUTOSOMAL") && !only(sex.path("regions"), "PAR"))
                return "BS1_SEX_MOI_REGION_CONFLICT";
        }
        var statistic = policy.path("statistic"); boolean af = isAf(policy);
        if (!Bs1Policy.keys(statistic, Set.of("metricId", "unit", "confidence", "ancestries", "karyotypes", "eligibleAn",
                "sourceTerm", "interpretation", "interpretationBasis", "sourceId", "locator", "eligibleAc"))
                || !"fraction".equals(statistic.path("unit").asText())
                || !(af ? "POINT_ESTIMATE" : "95_PERCENT_LOWER_BOUND").equals(statistic.path("confidence").asText())
                || !(af ? "AF" : "FAF95").equals(statistic.path("sourceTerm").asText())
                || !(af ? "TARGET_ALT_AF" : "TARGET_ALT_FAF95").equals(statistic.path("interpretation").asText())
                || statistic.path("interpretationBasis").asText().isBlank()
                || !strings(statistic.path("ancestries"), ANCESTRIES)
                || !strings(statistic.path("karyotypes"), Set.of("ALL", "XX", "XY"))
                || !source(policy, statistic.path("sourceId").asText()) || statistic.path("locator").asText().isBlank())
            return "BS1_SEX_STATISTIC_INVALID";
        var eligible = statistic.path("eligibleAn"); var minimum = number(eligible.path("decimal").asText());
        if (!Bs1Policy.keys(eligible, Set.of("operator", "decimal")) || !Set.of("GT", "GTE").contains(eligible.path("operator").asText())
                || !eligible.path("decimal").isTextual() || minimum == null || minimum.stripTrailingZeros().scale() > 0
                || minimum.compareTo(new BigDecimal("2000")) < 0) return "BS1_SEX_AN_REQUIREMENT_INVALID";
        if (statistic.has("eligibleAc")) {
            var eligibleAc = statistic.path("eligibleAc");
            var minimumAc = number(eligibleAc.path("decimal").asText());
            if (!Bs1Policy.keys(eligibleAc, Set.of("operator", "decimal", "unit", "sourceTerm", "sourceId", "locator"))
                    || !Set.of("GT", "GTE").contains(eligibleAc.path("operator").asText())
                    || !eligibleAc.path("decimal").isTextual()
                    || !eligibleAc.path("decimal").asText().matches("0|[1-9][0-9]*")
                    || minimumAc == null || minimumAc.signum() < 0 || minimumAc.stripTrailingZeros().scale() > 0
                    || !"alleles".equals(eligibleAc.path("unit").asText()) || !"AC".equals(eligibleAc.path("sourceTerm").asText())
                    || !eligibleAc.path("sourceId").isTextual() || !eligibleAc.path("locator").isTextual())
                return "BS1_SEX_AC_REQUIREMENT_INVALID";
            if (!source(policy, eligibleAc.path("sourceId").asText())
                    || !sourceLocationBound(record, eligibleAc.path("sourceId").asText(), eligibleAc.path("locator").asText()))
                return "BS1_SEX_AC_REQUIREMENT_SOURCE_UNBOUND";
        }
        if (!conditions(policy.path("commonConditions"), af, false)) return "BS1_SEX_CONDITION_UNSUPPORTED";
        if (!policy.path("tiers").isArray() || policy.path("tiers").isEmpty()) return "BS1_SEX_TIERS_REQUIRED";
        for (var tier : policy.path("tiers")) {
            if (!Set.of("STRONG", "SUPPORTING").contains(tier.path("strength").asText())
                    || !Bs1Policy.contains(scope.path("modes"), tier.path("moi").asText())
                    || tier.path("sourceLocator").asText().isBlank() || !conditions(tier.path("conditions"), af, true))
                return "BS1_SEX_TIER_UNSUPPORTED";
        }
        if (!sourceLocationBound(record, sex.path("sourceId").asText(), sex.path("sourceLocator").asText())
                || !sourceLocationBound(record, statistic.path("sourceId").asText(), statistic.path("locator").asText()))
            return "BS1_SEX_SOURCE_LOCATOR_UNBOUND";
        for (var exception : policy.at("/exceptionSet/entries")) for (var source : exception.path("mappingSource"))
            if (!sourceLocationBound(record, source.path("sourceId").asText(), source.path("locator").asText()))
                return "BS1_SEX_SOURCE_LOCATOR_UNBOUND";
        for (var tier : policy.path("tiers")) {
            if (!uniqueLocationBound(record, tier.path("sourceLocator").asText()))
                return "BS1_SEX_THRESHOLD_SOURCE_LOCATOR_UNBOUND";
            for (var condition : tier.path("conditions"))
                if ("OFFICIAL_CONDITION".equals(condition.path("basis").asText())
                        && !uniqueLocationBound(record, condition.path("sourceLocator").asText()))
                    return "BS1_SEX_THRESHOLD_SOURCE_LOCATOR_UNBOUND";
        }
        for (var condition : policy.path("commonConditions"))
            if ("OFFICIAL_CONDITION".equals(condition.path("basis").asText())
                    && !uniqueLocationBound(record, condition.path("sourceLocator").asText()))
                return "BS1_SEX_THRESHOLD_SOURCE_LOCATOR_UNBOUND";
        if (!((isJoint(policy) ? "JOINT" : "EXOMES").equals(sex.path("requiredDataset").asText())))
            return "BS1_SEX_DATASET_STATISTIC_MISMATCH";
        if (isJoint(policy)) {
            var mapper = new ObjectMapper();
            var resource = GnomadPopulationInputProfile.loadExecutableV2().getDocument().path("resources").path(0);
            if (!jointQuality(mapper).equals(policy.path("qualityContract")) || !jointScope(mapper).equals(policy.path("dataScope"))
                    || !resource.path("resourceVersion").equals(policy.at("/resourceCompatibility/resourceVersion"))
                    || !resource.path("identityFingerprintSha256").equals(policy.at("/resourceCompatibility/resourceSha256")))
                return "BS1_SEX_JOINT_SOURCE_CONTRACT_INVALID";
        }
        return null;
    }

    /** Region assignment is recomputed from the complete allele event, never a policy-supplied label. */
    static String regionIssue(ObjectMapper mapper, JsonNode policy, JsonNode context) {
        var allele = context.at("/variant/evidenceQueryAllele");
        var sex = policy.at("/scope/sexChromosome");
        if (!policy.at("/scope/hgncId").equals(context.at("/gene/hgncId"))
                || !policy.at("/scope/mondoId").equals(context.at("/disease/mondoId"))) return "BS1_SEX_CONTEXT_SCOPE_MISMATCH";
        if (!Bs1Policy.contains(policy.at("/scope/modes"), context.path("moi").asText())) return "BS1_SEX_MOI_OUT_OF_SCOPE";
        if (!Bs1Policy.contains(sex.path("assemblies"), allele.path("assembly").asText())) return "BS1_SEX_ASSEMBLY_OUT_OF_SCOPE";
        if (!Bs1Policy.contains(sex.path("contigs"), allele.path("contig").asText())) return "BS1_SEX_CONTIG_OUT_OF_SCOPE";
        var region = SexChromosomeRegionResolver.assess(mapper, allele);
        if (!sex.path("regionCatalogSha256").equals(region.path("sourceCatalogSha256"))) return "BS1_SEX_REGION_CATALOG_MISMATCH";
        String value = region.path("region").asText();
        if (!"RESOLVED".equals(region.path("status").asText()) || !Set.of("PAR", "NON_PAR").contains(value))
            return "BS1_SEX_REGION_UNRESOLVED";
        return Bs1Policy.contains(sex.path("regions"), value) ? null : "BS1_SEX_REGION_OUT_OF_SCOPE";
    }

    private static boolean conditions(JsonNode values, boolean af, boolean thresholdRequired) {
        if (!values.isArray()) return false;
        boolean threshold = false;
        for (var condition : values) {
            String fact = condition.path("fact").asText(); boolean frequency = (af ? "AF" : "FAF95").equals(fact);
            var value = number(condition.path("decimal").asText());
            if (!Set.of(af ? "AF" : "FAF95", "AC", "AN").contains(fact)
                    || !Set.of("GT", "GTE", "LT", "LTE").contains(condition.path("operator").asText())
                    || !condition.path("decimal").isTextual() || value == null || value.signum() < 0
                    || frequency && value.compareTo(BigDecimal.ONE) > 0 || !frequency && value.stripTrailingZeros().scale() > 0
                    || !(frequency ? "fraction" : "alleles").equals(condition.path("unit").asText())
                    || !("OFFICIAL_CONDITION".equals(condition.path("basis").asText())
                        || !frequency && "LOCAL_ENGINEERING_SAFEGUARD".equals(condition.path("basis").asText()))
                    || condition.path("originalText").asText().isBlank() || condition.path("sourceLocator").asText().isBlank()) return false;
            threshold |= frequency;
        }
        return !thresholdRequired || threshold;
    }

    private static boolean uniqueLocationBound(JsonNode record, String locator) {
        if (locator.isBlank()) return false;
        int matches = 0;
        for (var binding : record.at("/qualification/sourceBindings"))
            if (locator.equals(binding.path("locator").asText())) matches++;
        return matches == 1;
    }

    private static boolean sourceLocationBound(JsonNode record, String id, String locator) {
        for (var binding : record.at("/qualification/sourceBindings"))
            if (id.equals(binding.path("sourceId").asText()) && locator.equals(binding.path("locator").asText())) return true;
        return false;
    }

    private static boolean source(JsonNode policy, String id) {
        if (id.isBlank()) return false;
        int policyCount = 0, recordCount = 0;
        for (var source : policy.path("sources")) if (id.equals(source.path("sourceId").asText())) policyCount++;
        for (var source : policy.at("/knowledgeRecord/sources")) if (id.equals(source.path("sourceId").asText())) recordCount++;
        return policyCount == 1 && recordCount == 1;
    }

    private static boolean only(JsonNode values, String expected) { return values.size() == 1 && expected.equals(values.path(0).asText()); }
    private static boolean strings(JsonNode values, Set<String> allowed) {
        if (!values.isArray() || values.isEmpty()) return false;
        var seen = new HashSet<String>();
        for (var value : values) if (!value.isTextual() || !allowed.contains(value.asText()) || !seen.add(value.asText())) return false;
        return true;
    }
    static BigDecimal number(String text) {
        if (text == null || text.length() > 80) return null;
        var value = Bs1Quality.number(text);
        return value != null && value.scale() >= -100 && value.scale() <= 100 && value.precision() <= 50 ? value : null;
    }
}
