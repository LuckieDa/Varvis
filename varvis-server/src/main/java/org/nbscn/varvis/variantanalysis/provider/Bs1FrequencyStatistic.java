package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Explicit target-allele AF, never a renamed FAF or an inferred reference frequency. */
final class Bs1FrequencyStatistic {
    static final String VERSION = "bs1-target-af-v1";
    static final String METRIC = "gnomad.exomes.eligible_ancestry_max_af";
    private Bs1FrequencyStatistic() { }
    static boolean isAf(JsonNode policy) { return METRIC.equals(policy.at("/statistic/metricId").asText()); }

    static String issue(JsonNode policy) {
        var s = policy.path("statistic");
        if (!Bs1KnowledgeCatalog.isPolicy(policy) || !policy.at("/knowledgeRecord/qualification").isObject())
            return "BS1_AF_QUALIFICATION_REQUIRED";
        if (!Bs1Policy.keys(s, Set.of("metricId", "unit", "confidence", "ancestries", "sourceTerm", "interpretation", "interpretationBasis", "eligibleAn"))
                || !"fraction".equals(s.path("unit").asText()) || !"POINT_ESTIMATE".equals(s.path("confidence").asText())
                || !s.path("interpretationBasis").isTextual() || s.path("interpretationBasis").asText().isBlank()) return "BS1_AF_STATISTIC_INVALID";
        String term = s.path("sourceTerm").asText();
        if (!("AF".equals(term) && "TARGET_ALT_AF".equals(s.path("interpretation").asText())
                || "MAF".equals(term) && "TARGET_ALT_AF_NOT_ABOVE_HALF".equals(s.path("interpretation").asText())))
            return "BS1_AF_SOURCE_INTERPRETATION_REQUIRED";
        var ancestries = new HashSet<String>();
        if (!s.path("ancestries").isArray() || s.path("ancestries").isEmpty()) return "BS1_AF_ANCESTRY_SCOPE_REQUIRED";
        for (var a : s.path("ancestries")) if (!a.isTextual() || !Set.of("afr", "amr", "eas", "nfe", "sas", "mid").contains(a.asText())
                || !ancestries.add(a.asText())) return "BS1_AF_ANCESTRY_SCOPE_UNSUPPORTED";
        var an = s.path("eligibleAn"); var n = number(an.path("decimal").asText());
        if (!Bs1Policy.keys(an, Set.of("operator", "decimal")) || !Set.of("GT", "GTE").contains(an.path("operator").asText())
                || !an.path("decimal").isTextual() || n == null || !integer(n) || n.compareTo(new BigDecimal("2000")) < 0)
            return "BS1_AF_AN_REQUIREMENT_INVALID";
        return null;
    }

    static ObjectNode select(ObjectNode result, JsonNode carrier, JsonNode policy) {
        var s = policy.path("statistic");
        result.put("adapterVersion", VERSION);
        var selection = result.putObject("frequencySelection").put("version", VERSION);
        selection.set("statistic", s.deepCopy());
        var rows = selection.putArray("populations"); var issues = (ArrayNode) result.path("issues");
        var facts = (ObjectNode) result.path("facts");
        var names = new TreeSet<String>(); s.path("ancestries").forEach(a -> names.add(a.asText()));
        BigDecimal minimum = new BigDecimal(s.at("/eligibleAn/decimal").asText());
        PopulationCounts best = null;
        for (String ancestry : names) {
            var row = rows.addObject().put("ancestry", ancestry);
            String prefix = "gnomad.exomes.ancestry." + ancestry + ".";
            String anText = Bs1Quality.measure(carrier, prefix + "an"), acText = Bs1Quality.measure(carrier, prefix + "ac"),
                    afText = Bs1Quality.measure(carrier, prefix + "af");
            row.putObject("raw").put("AN", anText).put("AC", acText).put("AF", afText);
            BigDecimal an = number(anText), ac = number(acText), af = number(afText);
            String failure = null;
            if (an == null || !integer(an) || an.signum() < 0) failure = "BS1_AF_POPULATION_AN_INVALID";
            else if (an.compareTo(minimum) < 0 || an.compareTo(minimum) == 0 && "GT".equals(s.at("/eligibleAn/operator").asText())) {
                row.put("status", "EXCLUDED").put("reasonCode", "BS1_AF_POPULATION_AN_INSUFFICIENT"); continue;
            } else if (ac == null || !integer(ac) || ac.signum() < 0 || ac.compareTo(an) > 0) failure = "BS1_AF_POPULATION_AC_INVALID";
            else if (af == null || !Bs1Quality.fraction(af)) failure = "BS1_AF_POPULATION_AF_INVALID";
            else if (!Bs1Quality.countsAgree(ac, an, af)) failure = "BS1_AF_POPULATION_COUNT_CONFLICT";
            else if ("MAF".equals(s.path("sourceTerm").asText()) && (af.compareTo(new BigDecimal("0.5")) > 0
                    || ac.multiply(BigDecimal.valueOf(2)).compareTo(an) > 0)) failure = "BS1_MAF_TARGET_ALLELE_INTERPRETATION_UNRESOLVED";
            if (failure != null) {
                row.put("status", "UNRESOLVED").put("reasonCode", failure);
                issues.addObject().put("reasonCode", failure).put("field", ancestry); continue;
            }
            if (ac == null || an == null || af == null) {
                throw new IllegalStateException("Validated BS1 population counts are missing");
            }
            row.put("status", "ELIGIBLE");
            // Compare exact counts for ranking: rounded VCF AF can tie or misorder populations.
            if (best == null || ac.multiply(best.an()).compareTo(best.ac().multiply(an)) > 0) {
                best = new PopulationCounts(ancestry, af, ac, an);
            }
        }
        if (best == null && issues.isEmpty()) issues.addObject().put("reasonCode", "BS1_AF_NO_ELIGIBLE_POPULATION").put("field", "AN");
        if (best != null) {
            selection.put("selectedAncestry", best.ancestry()).put("comparisonBasis", "EXACT_TARGET_AC_OVER_AN")
                    .put("tieBreak", "ANCESTRY_CODE_ASCENDING");
            facts.put("ancestry", best.ancestry()).put("AF", best.af().toPlainString())
                    .put("AC", best.ac().toPlainString()).put("AN", best.an().toPlainString());
        }
        return result.put("status", issues.isEmpty() ? "READY" : "BLOCKED");
    }

    static String condition(JsonNode condition, Map<String, BigDecimal> facts) {
        // AF threshold comparisons use AC/AN without decimal rounding; raw AF remains in the evidence.
        if (!"AF".equals(condition.path("fact").asText())) return Bs1Policy.condition(condition, facts);
        var ac = facts.get("AC"); var an = facts.get("AN");
        if (ac == null || an == null || an.signum() <= 0) return "UNKNOWN";
        int comparison = ac.compareTo(new BigDecimal(condition.path("decimal").asText()).multiply(an));
        return switch (condition.path("operator").asText()) {
            case "GT" -> comparison > 0 ? "TRUE" : "FALSE";
            case "GTE" -> comparison >= 0 ? "TRUE" : "FALSE";
            case "LT" -> comparison < 0 ? "TRUE" : "FALSE";
            case "LTE" -> comparison <= 0 ? "TRUE" : "FALSE";
            default -> "UNKNOWN";
        };
    }
    private static BigDecimal number(String value) {
        if (value == null || value.length() > 80) return null;
        var n = Bs1Quality.number(value);
        return n != null && n.scale() >= -100 && n.scale() <= 100 && n.precision() <= 50 ? n : null;
    }
    private static boolean integer(BigDecimal n) { return n.stripTrailingZeros().scale() <= 0; }

    private record PopulationCounts(String ancestry, BigDecimal af, BigDecimal ac, BigDecimal an) {}
}
