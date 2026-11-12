package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.lang.Nullable;

/** A declared computational screen, not an experimental-normal-RNA assertion. */
final class MissenseRnaApplicability {
    static final String MODE = "PREDICTION_AND_REGISTERED_CONCERNS";
    static final String STATUS = "SOURCE_SCREENED_PREDICTION_REQUIRED";
    static final String COVERAGE = "PINNED_AVAILABLE_REPORTS_NOT_EXHAUSTIVE";
    private MissenseRnaApplicability() { }

    static boolean automated(JsonNode value) { return MODE.equals(value.path("mode").asText()); }

    @Nullable static String policyIssue(JsonNode policy, String criterion) {
        if (!policy.has("rnaApplicability")) return null;
        JsonNode route = policy.path("rnaApplicability"), splice = policy.path("splicePolicy");
        BigDecimal maximum = MissenseComparisonPolicy.decimal(splice.path("maximumScore"));
        if (!Set.of(MissenseComparisonRuntime.PS1, MissenseComparisonRuntime.PM5).contains(criterion)
                || !MissenseComparisonPolicy.only(route, Set.of("mode", "sourceCoverage", "sourceIds"))
                || !automated(route) || !COVERAGE.equals(route.path("sourceCoverage").asText())
                || !MissenseComparisonPolicy.boundSources(policy, route.path("sourceIds"))
                || maximum == null || maximum.signum() < 0 || maximum.compareTo(BigDecimal.ONE) > 0)
            return "MISSENSE_AUTOMATED_RNA_POLICY_INVALID";
        return null;
    }

    @Nullable static String materialIssue(JsonNode policy, JsonNode rna) {
        if (!automated(policy.path("rnaApplicability")) || !automated(rna)
                || !STATUS.equals(rna.path("status").asText())
                || !COVERAGE.equals(rna.path("sourceCoverage").asText())
                || !"UNKNOWN".equals(rna.path("rnaEffect").asText())
                || !rna.path("normalRnaClaimed").isBoolean() || rna.path("normalRnaClaimed").asBoolean()
                || !MissenseComparisonPolicy.text(rna.path("reviewId"))
                || !MissenseComparisonPolicy.boundSources(policy, rna.path("sourceIds"))
                || !rna.path("knownConcerns").isArray() || rna.path("knownConcerns").size() > 1024
                || !rna.path("comparisons").isArray() || !rna.path("comparisons").isEmpty())
            return "MISSENSE_AUTOMATED_RNA_MATERIAL_INVALID";
        for (JsonNode concern : rna.path("knownConcerns"))
            if (!concern.path("allele").isObject() || !MissenseComparisonPolicy.text(concern.path("reason"))
                    || !MissenseComparisonPolicy.boundSources(policy, concern.path("sourceIds")))
                return "MISSENSE_AUTOMATED_RNA_CONCERN_UNBOUND";
        return null;
    }
}
