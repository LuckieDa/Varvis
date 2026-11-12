package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Policy qualification of verified, source-defined ancestry/karyotype allele statistics. */
final class Bs1SexChromosomeStatistic {
    static final String VERSION = "bs1-sex-stratified-frequency-v1";
    static final String JOINT_VERSION = "bs1-joint-sex-stratified-frequency-v1";
    static final String AC_ELIGIBILITY_VERSION = "bs1-sex-stratified-frequency-v2";
    static final String JOINT_AC_ELIGIBILITY_VERSION = "bs1-joint-sex-stratified-frequency-v2";
    static String version(JsonNode policy) {
        boolean joint = Bs1SexChromosomePolicy.isJoint(policy);
        return policy.path("statistic").has("eligibleAc")
                ? (joint ? JOINT_AC_ELIGIBILITY_VERSION : AC_ELIGIBILITY_VERSION) : (joint ? JOINT_VERSION : VERSION);
    }
    private Bs1SexChromosomeStatistic() { }

    static boolean isStatistic(JsonNode policy) { return Bs1SexChromosomePolicy.isStatistic(policy); }

    /** Production boundary: derive anew from the parent RAW and the fixed source header. */
    static ObjectNode select(ObjectMapper mapper, JsonNode parent, JsonNode context, String sourceSnapshot, JsonNode policy) {
        return select(mapper, parent, context, sourceSnapshot, policy, mapper.createObjectNode());
    }

    static ObjectNode select(ObjectMapper mapper, JsonNode parent, JsonNode context, String sourceSnapshot, JsonNode policy, JsonNode nativeFacts) {
        var result = result(mapper, policy);
        String policyIssue = Bs1SexChromosomePolicy.issue(policy);
        if (policyIssue != null) return blocked(result, policyIssue, "policy");
        String regionIssue = Bs1SexChromosomePolicy.regionIssue(mapper, policy, context);
        if (regionIssue != null) return blocked(result, regionIssue, "region");
        try {
            boolean joint = Bs1SexChromosomePolicy.isJoint(policy);
            var derived = joint ? GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, sourceSnapshot)
                    : GnomadSexStratifiedObservation.derive(mapper, parent, context, sourceSnapshot,
                            GnomadSexStratifiedObservation.loadPinnedHeader());
            var selected = selectVerified(mapper, derived, policy);
            if (joint) {
                var sourceQuality = Bs1Quality.assessSource(mapper, parent, policy, nativeFacts);
                selected.set("sourceQualityDecision", sourceQuality);
                // Source quality issues take precedence; statistical absence is not a frequency result.
                if (!"READY".equals(sourceQuality.path("status").asText())) {
                    var issues = mapper.createArrayNode(); issues.addAll((ArrayNode) sourceQuality.path("issues"));
                    issues.addAll((ArrayNode) selected.path("issues")); selected.set("issues", issues);
                    selected.put("status", "BLOCKED");
                }
            }
            return selected;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            String sourceFailure = invalid.getMessage();
            if (sourceFailure != null && sourceFailure.matches("(?:GNOMAD|POPULATION)_[A-Z0-9_]+"))
                result.put("sourceFailureCode", sourceFailure);
            return blocked(result, "BS1_SEX_SOURCE_VERIFICATION_FAILED", "source");
        }
    }

    /** Lexical core for independent synthetic controls; callers outside this helper use select. */
    static ObjectNode selectVerified(ObjectMapper mapper, JsonNode derived, JsonNode policy) {
        var result = result(mapper, policy);
        String policyIssue = Bs1SexChromosomePolicy.issue(policy);
        if (policyIssue != null) return blocked(result, policyIssue, "policy");
        result.set("sexStratifiedObservation", derived.deepCopy());
        var issues = (ArrayNode) result.path("issues");
        boolean joint = Bs1SexChromosomePolicy.isJoint(policy);
        if (!(joint ? GnomadSexStratifiedObservation.JOINT_VERSION : GnomadSexStratifiedObservation.VERSION).equals(derived.path("schemaVersion").asText())
                || !(joint ? "JOINT" : "EXOMES").equals(derived.path("dataset").asText()) || !"4.1".equals(derived.path("release").asText())
                || !"NOT_EVALUATED".equals(derived.path("statisticalQualification").asText()))
            return blocked(result, "BS1_SEX_SOURCE_CONTRACT_INVALID", "sexStratifiedObservation");
        if ("NO_RECORD".equals(derived.path("status").asText())) return blocked(result, "BS1_NO_RECORD", "queryOutcome");
        if (!List.of("OBSERVED", "INCOMPLETE").contains(derived.path("status").asText()))
            return blocked(result, "BS1_RESOURCE_EXECUTION_UNAVAILABLE", "resourceExecution");
        String filter = derived.path("recordFilter").asText();
        result.putObject("filterCondition").put("basis", joint ? "BOUND_PUBLISHER_JOINT_FILTER" : "LOCAL_EXOMES_NATIVE_FILTER").put("observedFilter", filter)
                .put("status", "PASS".equals(filter) ? "SATISFIED" : "FAILED");
        if (!"PASS".equals(filter)) add(issues, joint ? "BS1_JOINT_FILTER_NOT_PASS" : "BS1_LOCAL_FILTER_NOT_PASS", "FILTER");
        var statistic = policy.path("statistic"); boolean afMetric = Bs1SexChromosomePolicy.isAf(policy);
        var selection = result.putObject("frequencySelection").put("version", version(policy));
        selection.set("statistic", statistic.deepCopy());
        selection.put("sourceObservationId", derived.path("observationId").asText());
        var rows = selection.putArray("populations");
        var ancestries = new TreeSet<String>(); statistic.path("ancestries").forEach(a -> ancestries.add(a.asText()));
        var karyotypes = new TreeSet<String>(); statistic.path("karyotypes").forEach(k -> karyotypes.add(k.asText()));
        BigDecimal minimum = new BigDecimal(statistic.at("/eligibleAn/decimal").asText());
        Choice best = null;
        for (String ancestry : ancestries) for (String karyotype : karyotypes) {
            var row = rows.addObject().put("ancestry", ancestry).put("karyotype", karyotype);
            var matches = new ArrayList<JsonNode>();
            for (var source : derived.path("strata"))
                if (ancestry.equals(source.path("ancestry").asText()) && karyotype.equals(source.path("karyotype").asText())) matches.add(source);
            String field = ancestry + ":" + karyotype;
            if (matches.size() != 1) {
                unresolved(row, issues, matches.isEmpty() ? "BS1_SEX_STRATUM_MISSING" : "BS1_SEX_STRATUM_AMBIGUOUS", field); continue;
            }
            var source = matches.get(0); row.set("sourceStratum", source.deepCopy());
            if (!"ALLELES".equals(source.path("denominatorUnit").asText())) {
                unresolved(row, issues, "BS1_SEX_DENOMINATOR_UNSUPPORTED", field); continue;
            }
            var measures = source.path("measurements");
            BigDecimal an = value(measures.path("AN"), "alleles");
            if (an == null || !integer(an) || an.signum() < 0) {
                unresolved(row, issues, "BS1_SEX_AN_INVALID_OR_MISSING", field); continue;
            }
            // Known low support is excluded before inspecting unused frequency inputs. Unknown support is never excluded.
            if (an.compareTo(minimum) < 0 || an.compareTo(minimum) == 0 && "GT".equals(statistic.at("/eligibleAn/operator").asText())) {
                row.put("status", "EXCLUDED").put("reasonCode", "BS1_SEX_AN_INSUFFICIENT"); continue;
            }
            BigDecimal ac = value(measures.path("AC"), "alleles");
            if (ac == null || !integer(ac) || ac.signum() < 0 || ac.compareTo(an) > 0) {
                unresolved(row, issues, "BS1_SEX_AC_INVALID_OR_MISSING", field); continue;
            }
            // The source's explicit target-allele count eligibility applies to each population
            // before comparing maxima. It is not an inferred patient/observation count or tier rule.
            if (statistic.has("eligibleAc")) {
                var eligibleAc = statistic.path("eligibleAc");
                BigDecimal minimumAc = new BigDecimal(eligibleAc.path("decimal").asText());
                int comparison = ac.compareTo(minimumAc);
                if (comparison < 0 || comparison == 0 && "GT".equals(eligibleAc.path("operator").asText())) {
                    row.put("status", "EXCLUDED").put("reasonCode", "BS1_SEX_AC_INSUFFICIENT"); continue;
                }
            }
            BigDecimal af = value(measures.path("AF"), "fraction");
            if (af == null || !Bs1Quality.fraction(af)) {
                unresolved(row, issues, "BS1_SEX_AF_INVALID_OR_MISSING", field); continue;
            }
            if (!Bs1Quality.countsAgree(ac, an, af)) {
                unresolved(row, issues, "BS1_SEX_COUNT_AF_CONFLICT", field); continue;
            }
            BigDecimal faf = BigDecimal.ZERO;
            if (!afMetric) {
                var fafMeasure = measures.path("faf95");
                if (!"APPLICABLE".equals(fafMeasure.path("applicability").asText())
                        || !"95_PERCENT_LOWER_BOUND".equals(fafMeasure.path("confidence").asText())) {
                    unresolved(row, issues, "BS1_SEX_FAF_APPLICABILITY_UNRESOLVED", field); continue;
                }
                var observedFaf = value(fafMeasure, "fraction");
                if (observedFaf == null || !Bs1Quality.fraction(observedFaf) || observedFaf.compareTo(af) > 0) {
                    unresolved(row, issues, "BS1_SEX_FAF_INVALID_OR_MISSING", field); continue;
                }
                faf = observedFaf;
            }
            row.put("status", "ELIGIBLE");
            boolean better = best == null || (afMetric
                    ? ac.multiply(best.an()).compareTo(best.ac().multiply(an)) > 0 : faf.compareTo(best.faf()) > 0);
            if (better) best = new Choice(ancestry, karyotype, ac, an, af, faf);
        }
        if (best == null && issues.isEmpty()) add(issues, "BS1_SEX_NO_ELIGIBLE_POPULATION", "AN");
        if (best != null) {
            selection.put("selectedAncestry", best.ancestry()).put("selectedKaryotype", best.karyotype())
                    .put("comparisonBasis", afMetric ? "EXACT_TARGET_AC_OVER_AN" : "SOURCE_STRATUM_FAF95")
                    .put("tieBreak", "ANCESTRY_THEN_KARYOTYPE_ASCENDING");
            var facts = (ObjectNode) result.path("facts");
            facts.put("ancestry", best.ancestry()).put("karyotype", best.karyotype())
                    .put("AC", best.ac().toPlainString()).put("AN", best.an().toPlainString()).put("AF", best.af().toPlainString());
            if (!afMetric) facts.put("FAF95", best.faf().toPlainString());
        }
        return result.put("status", issues.isEmpty() ? "READY" : "BLOCKED");
    }

    static String condition(JsonNode condition, Map<String, BigDecimal> facts) {
        return Bs1FrequencyStatistic.condition(condition, facts);
    }

    private static ObjectNode result(ObjectMapper mapper, JsonNode policy) {
        var result = mapper.createObjectNode().put("schemaVersion", "bs1-quality-decision-v2").put("adapterVersion", version(policy))
                .put("contractSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(policy.path("qualityContract")));
        result.set("contract", policy.path("qualityContract").deepCopy());
        result.set("dataScope", policy.path("dataScope").deepCopy());
        result.putArray("issues"); result.putObject("facts"); return result;
    }

    private static BigDecimal value(JsonNode measure, String unit) {
        return "PRESENT".equals(measure.path("state").asText()) && unit.equals(measure.path("unit").asText())
                ? Bs1SexChromosomePolicy.number(measure.path("rawValue").asText()) : null;
    }
    private static boolean integer(BigDecimal value) { return value.stripTrailingZeros().scale() <= 0; }
    private record Choice(String ancestry, String karyotype, BigDecimal ac, BigDecimal an, BigDecimal af, BigDecimal faf) { }
    private static void unresolved(ObjectNode row, ArrayNode issues, String code, String field) {
        row.put("status", "UNRESOLVED").put("reasonCode", code); add(issues, code, field);
    }
    private static void add(ArrayNode issues, String code, String field) { issues.addObject().put("reasonCode", code).put("field", field); }
    private static ObjectNode blocked(ObjectNode result, String code, String field) {
        add((ArrayNode) result.path("issues"), code, field); return result.put("status", "BLOCKED");
    }
}
