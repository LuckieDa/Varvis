package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Finite rule mechanics only: no ACMG score, approval, source reader or GT/ploidy inference.
 * The existing consumer must independently verify original policy bytes/selection, source
 * identity, statistic interpretation, quality and exceptions before supplying typed facts.
 * Original-text replay below checks consistency, not the source artifact's SHA or authority.
 */
public final class Ba1ConditionalFrequencyRule {
    public static final String FAMILY = "FAF95_OR_HOMO_HEMIZYGOTE_COUNT_V1";
    public static final String VERSION = "a01-ba1-faf-or-individual-count-v1";
    private static final String TARGET = "gnomad.joint.faf95max";
    private static final Set<String> ANCESTRIES = Set.of("afr", "amr", "eas", "mid", "nfe", "sas");
    private static final String NUMBER = "(?:0(?:\\.[0-9]+)?|1(?:\\.0+)?)";
    private static final Pattern ORIGINAL = Pattern.compile(
            "Applicable when Grpmax Filtering Allele Frequency is\\s*(?<op>>=|>|≥)\\s*(?<value>" + NUMBER + ")"
                    + "\\s*\\((?<percent>[0-9]+(?:\\.[0-9]+)?)%\\) OR\\s*(?:>=|≥)\\s*(?<count>[1-9][0-9]*) "
                    + "homo- or hemizygotes \\(or the sum of total hemi- and homozygotes\\) are present in the most current "
                    + "version of gnomAD available at the time of curation\\.");
    private Ba1ConditionalFrequencyRule() { }

    public enum Truth { TRUE, FALSE, UNKNOWN }
    /** This is an attestation by the original source consumer, never inferred here from sex, karyotype or a profile. */
    public enum SumRelationship { SAME_SOURCE_DISJOINT, UNRESOLVED }
    /** Completeness of the adopted count-arm population, separately verified by the source consumer. */
    public enum CountScopeCompleteness { COMPLETE, UNVERIFIED_OR_PARTIAL }
    public record FrequencyFact(@Nullable BigDecimal faf95, @Nullable BigInteger alleleNumber, @Nullable String ancestry) { }
    /** Each non-null count is already source-qualified, internally deduplicated qualifying individuals
     * within the adopted rule population. It may cover a verified subset; it is never AC_XY or
     * an inferred hemizygote count. A compatible/disjoint relationship permits addition but does
     * not attest that every source, sample or quality stratum in the rule has been covered.
     */
    public record IndividualCounts(@Nullable BigInteger homozygotes, @Nullable BigInteger hemizygotes,
                                   @Nullable SumRelationship relationship) { }
    public record Decision(Truth truth, Truth frequencyTruth, Truth countTruth, String reasonCode,
                           String frequencyReasonCode, String countReasonCode) { }
    private record Branch(Truth truth, String reason) { }

    /** Null means only that this complete finite representation is internally supported. */
    @Nullable public static String issue(@Nullable JsonNode clause) {
        if (!keys(clause, Set.of("disposition", "reasonCode", "semanticFamily", "originalMetric", "originalStatisticText",
                "originalText", "sourceRefs", "conditions", "derivedPolicyVersion", "expression", "migration", "clauses")))
            return "BA1_CONDITIONAL_CLAUSE_FIELDS_UNSUPPORTED";
        if (clause == null) return "BA1_CONDITIONAL_CLAUSE_FIELDS_UNSUPPORTED";
        if (!"CONDITIONAL".equals(clause.path("disposition").asText()) || !FAMILY.equals(clause.path("semanticFamily").asText())
                || !VERSION.equals(clause.path("derivedPolicyVersion").asText()) || !"FAF".equals(clause.path("originalMetric").asText())
                || !"CSPEC_BA1_COMPLETE_FAF_OR_INDIVIDUAL_COUNT_FAMILY".equals(clause.path("reasonCode").asText())
                || !"Grpmax Filtering Allele Frequency".equals(clause.path("originalStatisticText").asText()))
            return "BA1_CONDITIONAL_FAMILY_UNSUPPORTED";
        if (!uniqueStrings(clause.path("conditions"), Set.of("GNOMAD_FILTERED_VARIANTS_EXCLUDED"), false))
            return "BA1_CONDITIONAL_EXTRA_CONDITIONS_UNSUPPORTED";
        if (clause.has("clauses") && !clause.path("clauses").isArray()) return "BA1_CONDITIONAL_ORIGINAL_CLAUSES_INVALID";
        if (!sources(clause.path("sourceRefs"))) return "BA1_CONDITIONAL_SOURCE_IDENTITY_INVALID";
        var migration = clause.path("migration");
        if (!keys(migration, Set.of("status", "reviewId", "reviewSha256", "sources", "basis", "reviewRole"))
                || !"REVIEWED".equals(migration.path("status").asText()) || !text(migration.path("reviewId"))
                || !hash(migration.path("reviewSha256")) || !sources(migration.path("sources"))
                || !"EXPLICIT_FAF_DEFINITION_ALIGNMENT".equals(migration.path("basis").asText())
                || !"PROJECT_SOURCE_DERIVATION_NOT_NEW_VCEP_OR_CLINICAL_APPROVAL".equals(migration.path("reviewRole").asText()))
            return "BA1_CONDITIONAL_FAF_MIGRATION_INVALID";
        boolean supportingSource = false;
        for (var required : clause.path("sourceRefs")) {
            boolean included = false;
            for (var actual : migration.path("sources")) if (required.equals(actual)) included = true;
            if (!included) return "BA1_CONDITIONAL_MIGRATION_RULE_SOURCE_MISMATCH";
        }
        for (var actual : migration.path("sources")) {
            boolean isRule = false;
            for (var required : clause.path("sourceRefs"))
                if (actual.path("url").equals(required.path("url")) && actual.path("sha256").equals(required.path("sha256"))) isRule = true;
            if (!isRule) supportingSource = true;
        }
        if (!supportingSource) return "BA1_CONDITIONAL_FAF_GUIDANCE_SOURCE_MISSING";
        var expression = clause.path("expression");
        if (!keys(expression, Set.of("operator", "frequency", "individualCount")) || !"ANY".equals(expression.path("operator").asText()))
            return "BA1_CONDITIONAL_EXPRESSION_UNSUPPORTED";
        var frequency = expression.path("frequency");
        if (!keys(frequency, Set.of("metric", "comparator", "value", "unit", "minimumAn", "minimumAnComparator", "ancestries"))
                || !TARGET.equals(frequency.path("metric").asText()) || !"fraction".equals(frequency.path("unit").asText())
                || !Set.of("GREATER_THAN", "GREATER_THAN_OR_EQUAL").contains(frequency.path("comparator").asText())
                || !"2000".equals(frequency.path("minimumAn").asText()) || !frequency.path("minimumAn").isTextual()
                || !"GREATER_THAN_OR_EQUAL".equals(frequency.path("minimumAnComparator").asText())
                || !uniqueStrings(frequency.path("ancestries"), ANCESTRIES, true))
            return "BA1_CONDITIONAL_FREQUENCY_RULE_UNSUPPORTED";
        var count = expression.path("individualCount");
        if (!keys(count, Set.of("operator", "value", "unit", "populationScope", "counts", "sumOnlyIfMutuallyExclusive"))
                || !"GREATER_THAN_OR_EQUAL".equals(count.path("operator").asText()) || !"individuals".equals(count.path("unit").asText())
                || !"SAME_DEDUPLICATED_SOURCE".equals(count.path("populationScope").asText())
                || !uniqueStrings(count.path("counts"), Set.of("HOMOZYGOUS", "HEMIZYGOUS"), true)
                || !count.path("sumOnlyIfMutuallyExclusive").isBoolean() || !count.path("sumOnlyIfMutuallyExclusive").booleanValue())
            return "BA1_CONDITIONAL_COUNT_RULE_UNSUPPORTED";
        String originalText = clause.path("originalText").textValue();
        if (originalText == null || originalText.isBlank()) return "BA1_CONDITIONAL_ORIGINAL_TEXT_REQUIRED";
        String normalized = HtmlUtils.htmlUnescape(originalText).replace("**", "")
                .replaceAll("(?m)^\\s*[*-]\\s+", "").replaceAll("(?U)\\s+", " ").trim();
        var match = ORIGINAL.matcher(normalized);
        if (!match.matches()) return "BA1_CONDITIONAL_ORIGINAL_TEXT_UNSUPPORTED";
        try {
            if (!frequency.path("value").isTextual() || !frequency.path("value").textValue().matches(NUMBER)
                    || !count.path("value").isTextual() || !count.path("value").textValue().matches("[1-9][0-9]*"))
                return "BA1_CONDITIONAL_THRESHOLD_INVALID";
            var threshold = new BigDecimal(frequency.path("value").textValue());
            if (threshold.signum() <= 0 || threshold.compareTo(BigDecimal.ONE) > 0)
                return "BA1_CONDITIONAL_THRESHOLD_INVALID";
            if (threshold.compareTo(new BigDecimal(match.group("value"))) != 0
                    || threshold.multiply(BigDecimal.valueOf(100)).compareTo(new BigDecimal(match.group("percent"))) != 0
                    || !(">".equals(match.group("op")) ? "GREATER_THAN" : "GREATER_THAN_OR_EQUAL").equals(frequency.path("comparator").asText())
                    || !new BigInteger(count.path("value").textValue()).equals(new BigInteger(match.group("count"))))
                return "BA1_CONDITIONAL_ORIGINAL_EXPRESSION_MISMATCH";
        } catch (NumberFormatException invalid) { return "BA1_CONDITIONAL_THRESHOLD_INVALID"; }
        return null;
    }

    public static Decision evaluate(@Nullable JsonNode clause, @Nullable FrequencyFact frequency,
                                    @Nullable IndividualCounts counts) {
        return evaluate(clause, frequency, counts, null);
    }

    /** countUpperBound is an already verified bound covering every individual counted by
     * this arm in the same deduplicated cohort, target ALT, release and compatible call set.
     * An ALL target-allele AC can prove such a bound only after the original consumer verifies
     * that coverage and each counted person contributes at least one target allele. Ancestry
     * subsets, AC_XY or separate dataset/components cannot be substituted. This parameter is
     * not source qualification and never becomes an exact person count. Its whole-population
     * coverage proof is independent of completeness of the supplied exact class counts.
     */
    public static Decision evaluate(@Nullable JsonNode clause, @Nullable FrequencyFact frequency,
                                    @Nullable IndividualCounts counts, @Nullable BigInteger countUpperBound) {
        return evaluate(clause, frequency, counts, countUpperBound, CountScopeCompleteness.UNVERIFIED_OR_PARTIAL);
    }

    /** COMPLETE requires verified coverage of both classes over every source, release, target ALT,
     * sample and QC stratum adopted by this count arm. Non-null fields, zero, HIT, an ALL label or
     * SAME_SOURCE_DISJOINT do not provide that proof. Only a below-threshold exact sum needs this
     * completeness attestation; a qualified subset can already prove a positive lower bound.
     * Null completeness is conservative. This internal mathematical API is not an adoption gate.
     */
    public static Decision evaluate(@Nullable JsonNode clause, @Nullable FrequencyFact frequency,
                                    @Nullable IndividualCounts counts, @Nullable BigInteger countUpperBound,
                                    @Nullable CountScopeCompleteness completeness) {
        String issue = issue(clause);
        if (issue != null || clause == null) return new Decision(Truth.UNKNOWN, Truth.UNKNOWN, Truth.UNKNOWN,
                issue == null ? "BA1_CONDITIONAL_CLAUSE_FIELDS_UNSUPPORTED" : issue, "RULE_UNAVAILABLE", "RULE_UNAVAILABLE");
        var frequencyResult = frequency(clause.path("expression").path("frequency"), frequency);
        var countResult = counts(clause.path("expression").path("individualCount"), counts, countUpperBound, completeness);
        Truth truth = frequencyResult.truth() == Truth.TRUE || countResult.truth() == Truth.TRUE ? Truth.TRUE
                : frequencyResult.truth() == Truth.FALSE && countResult.truth() == Truth.FALSE ? Truth.FALSE : Truth.UNKNOWN;
        return new Decision(truth, frequencyResult.truth(), countResult.truth(), truth == Truth.TRUE
                ? "BA1_CONDITIONAL_ANY_SATISFIED" : truth == Truth.FALSE ? "BA1_CONDITIONAL_ALL_BRANCHES_NOT_SATISFIED"
                : "BA1_CONDITIONAL_REQUIRED_FACTS_UNRESOLVED", frequencyResult.reason(), countResult.reason());
    }

    private static Branch frequency(JsonNode rule, @Nullable FrequencyFact fact) {
        if (fact == null) return new Branch(Truth.UNKNOWN, "FAF95_AN_OR_ANCESTRY_MISSING");
        var faf = fact.faf95(); var an = fact.alleleNumber(); var ancestry = fact.ancestry();
        if (faf == null || an == null || ancestry == null)
            return new Branch(Truth.UNKNOWN, "FAF95_AN_OR_ANCESTRY_MISSING");
        if (faf.signum() < 0 || faf.compareTo(BigDecimal.ONE) > 0 || an.signum() <= 0)
            return new Branch(Truth.UNKNOWN, "FAF95_OR_AN_INVALID");
        if (!ANCESTRIES.contains(ancestry)) return new Branch(Truth.UNKNOWN, "FAF95_ANCESTRY_NOT_ELIGIBLE");
        if (an.compareTo(new BigInteger(rule.path("minimumAn").asText())) < 0)
            return new Branch(Truth.UNKNOWN, "FAF95_SELECTED_ANCESTRY_AN_INSUFFICIENT");
        int comparison = faf.compareTo(new BigDecimal(rule.path("value").asText()));
        boolean met = "GREATER_THAN".equals(rule.path("comparator").asText()) ? comparison > 0 : comparison >= 0;
        return new Branch(met ? Truth.TRUE : Truth.FALSE, met ? "FAF95_THRESHOLD_SATISFIED" : "FAF95_THRESHOLD_NOT_SATISFIED");
    }

    private static Branch counts(JsonNode rule, @Nullable IndividualCounts fact, @Nullable BigInteger upperBound,
                                 @Nullable CountScopeCompleteness completeness) {
        var homo = fact == null ? null : fact.homozygotes();
        var hemi = fact == null ? null : fact.hemizygotes();
        if (homo != null && homo.signum() < 0 || hemi != null && hemi.signum() < 0)
            return new Branch(Truth.UNKNOWN, upperBound == null ? "INDIVIDUAL_COUNT_INVALID" : "INDIVIDUAL_COUNT_UPPER_BOUND_CONFLICT");
        var threshold = new BigInteger(rule.path("value").asText());
        if (upperBound != null) {
            boolean conflict = upperBound.signum() < 0 || homo != null && homo.compareTo(upperBound) > 0
                    || hemi != null && hemi.compareTo(upperBound) > 0;
            if (fact != null && fact.relationship() == SumRelationship.SAME_SOURCE_DISJOINT && homo != null && hemi != null)
                conflict |= homo.add(hemi).compareTo(upperBound) > 0;
            if (conflict) return new Branch(Truth.UNKNOWN, "INDIVIDUAL_COUNT_UPPER_BOUND_CONFLICT");
            if (upperBound.compareTo(threshold) < 0)
                return new Branch(Truth.FALSE, "INDIVIDUAL_COUNT_TOTAL_UPPER_BOUND_BELOW_THRESHOLD");
        }
        if (fact == null) return new Branch(Truth.UNKNOWN, upperBound == null
                ? "INDIVIDUAL_COUNTS_MISSING" : "INDIVIDUAL_COUNT_UPPER_BOUND_NOT_CONCLUSIVE");
        if (homo != null && homo.compareTo(threshold) >= 0 || hemi != null && hemi.compareTo(threshold) >= 0)
            return new Branch(Truth.TRUE, "INDIVIDUAL_COUNT_SINGLE_CLASS_THRESHOLD_SATISFIED");
        if (homo == null || hemi == null) return new Branch(Truth.UNKNOWN, "INDIVIDUAL_COUNT_CLASS_MISSING");
        if (fact.relationship() != SumRelationship.SAME_SOURCE_DISJOINT)
            return new Branch(Truth.UNKNOWN, "INDIVIDUAL_COUNT_SUM_RELATIONSHIP_UNRESOLVED");
        if (homo.add(hemi).compareTo(threshold) >= 0)
            return new Branch(Truth.TRUE, "INDIVIDUAL_COUNT_DISJOINT_SUM_THRESHOLD_SATISFIED");
        return completeness == CountScopeCompleteness.COMPLETE
                ? new Branch(Truth.FALSE, "INDIVIDUAL_COUNT_DISJOINT_SUM_THRESHOLD_NOT_SATISFIED")
                : new Branch(Truth.UNKNOWN, "INDIVIDUAL_COUNT_COVERAGE_INCOMPLETE");
    }

    private static boolean keys(@Nullable JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) return false;
        var fields = node.fieldNames(); while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
        return true;
    }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.textValue().isBlank(); }
    private static boolean hash(JsonNode value) { return value.isTextual() && value.textValue().matches("[0-9a-f]{64}"); }
    private static boolean sources(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) return false;
        for (var source : node) {
            if (!keys(source, Set.of("url", "sha256", "version", "specificationId", "jsonPointer", "authoritySourceId"))
                    || !text(source.path("url")) || !source.path("url").textValue().startsWith("https://") || !hash(source.path("sha256"))) return false;
            for (String optional : Set.of("version", "specificationId", "jsonPointer", "authoritySourceId"))
                if (source.has(optional) && !text(source.path(optional))) return false;
        }
        return true;
    }
    private static boolean uniqueStrings(JsonNode node, Set<String> allowed, boolean allRequired) {
        if (!node.isArray()) return false;
        Set<String> values = new HashSet<>();
        for (var value : node) if (!value.isTextual() || !allowed.contains(value.textValue()) || !values.add(value.textValue())) return false;
        return !allRequired || values.equals(allowed);
    }
}
