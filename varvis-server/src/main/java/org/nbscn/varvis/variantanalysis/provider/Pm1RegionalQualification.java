package org.nbscn.varvis.variantanalysis.provider;

import static org.nbscn.varvis.variantanalysis.provider.Pm1ReviewedRegions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Recalculates PM1 from cited assertions and independently defined regional methods. */
final class Pm1RegionalQualification {
    private static final String DOCUMENTED = "DOCUMENTED_REGION";
    private static final String ENRICHMENT = "MISSENSE_ENRICHMENT";
    private static final Set<String> PATHOGENIC = Set.of("PATHOGENIC", "LIKELY_PATHOGENIC");
    private static final Set<String> BENIGN = Set.of("BENIGN", "LIKELY_BENIGN");
    private static final String UNCERTAIN = "UNCERTAIN_SIGNIFICANCE";
    private static final String CONFLICT = "PM1_REVIEWED_REGION_OBSERVATION_UNRESOLVED";
    private static final String BENIGN_PRESENT = "PM1_REVIEWED_REGION_BENIGN_OBSERVATION";

    private enum Role {
        INDEPENDENT_PATHOGENIC, QUALIFIED_BENIGN, CONFLICTING, UNRESOLVED,
        EXCLUDED_TARGET, EXCLUDED_DIFFERENT_BACKGROUND, EXCLUDED_DEPENDENT, EXCLUDED_UNCERTAIN
    }

    private record Comparison(int position, Role role) {}

    private Pm1RegionalQualification() {}

    static void validate(JsonNode record, Set<String> sources) {
        var survey = record.path("survey");
        check(only(survey, Set.of("start", "end", "status", "dataset", "sourceIds")) && interval(survey)
                && Set.of("COMPLETE", "INCOMPLETE").contains(survey.path("status").asText())
                && text(survey.path("dataset")));
        validateSources(survey, sources);

        var regions = record.path("regions");
        check(array(regions, 16));
        Set<String> regionIds = new HashSet<>();
        for (var region : regions) {
            String method = region.path("method").asText();
            String kind = region.path("kind").asText();
            check(only(region, Set.of("regionId", "label", "start", "end", "radius", "kind", "method",
                    "established", "rationale", "sourceIds", "calibration"))
                    && text(region.path("regionId")) && regionIds.add(region.path("regionId").asText())
                    && text(region.path("label")) && text(region.path("rationale"))
                    && Set.of("MUTATIONAL_HOTSPOT", "CRITICAL_FUNCTIONAL_REGION").contains(kind));
            validateSources(region, sources);
            if (DOCUMENTED.equals(method)) {
                check(interval(region) && region.path("established").isBoolean()
                        && !region.has("radius") && !region.has("calibration"));
            } else {
                check(ENRICHMENT.equals(method) && "MUTATIONAL_HOTSPOT".equals(kind)
                        && !region.has("established"));
                check(region.has("radius") ? positiveInt(region.path("radius"), 1000)
                        && !region.has("start") && !region.has("end") : interval(region));
                if (region.has("calibration")) validateCalibration(region.path("calibration"), sources);
            }
        }

        var observations = record.path("observations");
        check(array(observations, 512));
        Set<String> substitutions = new HashSet<>();
        for (var observation : observations) {
            int position = observation.path("proteinPosition").asInt();
            String ref = observation.path("refAminoAcid").asText();
            String alt = observation.path("altAminoAcid").asText();
            var assertions = observation.path("assertions");
            check(only(observation, Set.of("proteinPosition", "refAminoAcid", "altAminoAcid", "assertions"))
                    && positiveInt(observation.path("proteinPosition"), 100_000)
                    && Pm1RegionSelection.covers(survey, position)
                    && ref.matches(AA) && alt.matches(AA) && !ref.equals(alt)
                    && substitutions.add(ref + position + alt) && array(assertions, 12) && !assertions.isEmpty());
            Set<String> assertionIds = new HashSet<>();
            for (var assertion : assertions) {
                String classification = assertion.path("classification").asText();
                check(only(assertion, Set.of("assertionId", "classification", "diseaseScope", "basis", "rationale", "sourceIds"))
                        && text(assertion.path("assertionId")) && assertionIds.add(assertion.path("assertionId").asText())
                        && (PATHOGENIC.contains(classification) || BENIGN.contains(classification) || UNCERTAIN.equals(classification))
                        && Set.of("MATCHED", "DIFFERENT", "UNRESOLVED").contains(assertion.path("diseaseScope").asText())
                        && Set.of("INDEPENDENT", "DEPENDENT", "UNVERIFIED").contains(assertion.path("basis").asText())
                        && text(assertion.path("rationale")));
                validateSources(assertion, sources);
            }
        }
    }

    private static void validateCalibration(JsonNode calibration, Set<String> sources) {
        var levels = calibration.path("levels");
        check(only(calibration, Set.of("method", "version", "levels", "sourceIds"))
                && "FISHER_EXACT_ONE_SIDED".equals(calibration.path("method").asText())
                && text(calibration.path("version")) && array(levels, 2) && !levels.isEmpty());
        validateSources(calibration, sources);
        Set<String> strengths = new HashSet<>();
        for (var level : levels) {
            String strength = level.path("strength").asText();
            check(only(level, Set.of("strength", "minimumIndependentPathogenic", "minimumOddsRatio", "maximumPValue"))
                    && STRENGTHS.contains(strength) && strengths.add(strength)
                    && positiveInt(level.path("minimumIndependentPathogenic"), 512)
                    && decimal(level.path("minimumOddsRatio")).compareTo(BigDecimal.ONE) > 0);
            BigDecimal p = decimal(level.path("maximumPValue"));
            check(p.signum() > 0 && p.compareTo(BigDecimal.ONE) < 0);
        }
        if (levels.size() == 2) {
            var supporting = "SUPPORTING".equals(levels.path(0).path("strength").asText()) ? levels.path(0) : levels.path(1);
            var moderate = supporting == levels.path(0) ? levels.path(1) : levels.path(0);
            check(moderate.path("minimumIndependentPathogenic").asInt() >= supporting.path("minimumIndependentPathogenic").asInt()
                    && decimal(moderate.path("minimumOddsRatio")).compareTo(decimal(supporting.path("minimumOddsRatio"))) >= 0
                    && decimal(moderate.path("maximumPValue")).compareTo(decimal(supporting.path("maximumPValue"))) <= 0);
        }
    }

    static ObjectNode assess(ObjectNode out, ObjectNode facts, JsonNode record, JsonNode target, int length, String cds) {
        int position = target.path("proteinPosition").asInt();
        var candidates = new ArrayList<ObjectNode>();
        for (var definition : record.path("regions")) {
            var region = ((ObjectNode) definition).deepCopy();
            if (region.has("radius")) {
                int radius = region.path("radius").asInt();
                region.remove("radius");
                region.put("start", Math.max(1, position - radius)).put("end", Math.min(length, position + radius))
                        .put("windowRadius", radius);
            }
            check(region.path("end").asInt() <= length);
            if (Pm1RegionSelection.covers(region, position)) candidates.add(region);
        }
        if (candidates.isEmpty()) return Pm1ReviewedRegions.outsideRegions(out, facts, record, position, length);

        var survey = record.path("survey");
        check(survey.path("end").asInt() <= length);
        facts.set("survey", survey.deepCopy());
        var observations = facts.putArray("observations");
        var references = sourceIds(survey);
        var comparisons = new ArrayList<Comparison>();
        for (var observation : record.path("observations")) {
            int offset = (observation.path("proteinPosition").asInt() - 1) * 3;
            check(observation.path("refAminoAcid").asText().equals(
                    Character.toString(TruncatingSequence.translateCodon(cds.substring(offset, offset + 3)))));
            var role = qualify(observation, target);
            observations.add(((ObjectNode) observation).deepCopy().put("role", role.name()));
            for (var assertion : observation.path("assertions")) references.addAll(sourceIds(assertion));
            comparisons.add(new Comparison(observation.path("proteinPosition").asInt(), role));
        }

        var assessments = facts.putArray("assessments");
        for (var region : candidates) {
            references.addAll(sourceIds(region));
            if (region.has("calibration")) references.addAll(sourceIds(region.path("calibration")));
            assessments.add(evaluate(region, survey, comparisons));
        }
        addSources(facts, record, references);
        var selected = candidates.stream().min(Comparator.comparingInt(Pm1RegionalQualification::decisionOrder)
                .thenComparing(region -> !CONFLICT.equals(region.path("reasonCode").asText()))
                .thenComparing(region -> region.path("regionId").asText())).orElseThrow();
        facts.put("decisionRegionId", selected.path("regionId").asText());
        return out.put("status", selected.path("status").asText()).put("reasonCode", selected.path("reasonCode").asText())
                .put("appliedStrength", selected.path("appliedStrength").asText());
    }

    private static Role qualify(JsonNode observation, JsonNode target) {
        if (observation.path("proteinPosition").equals(target.path("proteinPosition"))
                && observation.path("altAminoAcid").equals(target.path("altAminoAcid"))) return Role.EXCLUDED_TARGET;

        var applicable = observation.path("assertions").valueStream()
                .filter(assertion -> !"DIFFERENT".equals(assertion.path("diseaseScope").asText())).toList();
        if (applicable.isEmpty()) return Role.EXCLUDED_DIFFERENT_BACKGROUND;
        var independentOrUnverified = applicable.stream()
                .filter(assertion -> !"DEPENDENT".equals(assertion.path("basis").asText())).toList();
        if (independentOrUnverified.isEmpty()) return Role.EXCLUDED_DEPENDENT;
        var matched = independentOrUnverified.stream()
                .filter(assertion -> "MATCHED".equals(assertion.path("diseaseScope").asText())).toList();
        boolean pathogenic = anyClassification(matched, PATHOGENIC);
        boolean benign = anyClassification(matched, BENIGN);
        var verified = independentOrUnverified.stream()
                .filter(assertion -> "MATCHED".equals(assertion.path("diseaseScope").asText())
                        && "INDEPENDENT".equals(assertion.path("basis").asText())).toList();
        if (pathogenic && benign) return Role.CONFLICTING;
        if (benign && anyClassification(matched, Set.of(UNCERTAIN))) return Role.CONFLICTING;
        // A possible opposing assertion in an unspecified disease is a gap, not a confirmed same-disease conflict.
        var unresolved = independentOrUnverified.stream()
                .filter(assertion -> "UNRESOLVED".equals(assertion.path("diseaseScope").asText())).toList();
        if (anyClassification(unresolved, PATHOGENIC) || anyClassification(unresolved, BENIGN)) return Role.UNRESOLVED;
        if (anyClassification(verified, BENIGN)) return Role.QUALIFIED_BENIGN;
        if (anyClassification(verified, PATHOGENIC)) return Role.INDEPENDENT_PATHOGENIC;
        return pathogenic || benign ? Role.UNRESOLVED : Role.EXCLUDED_UNCERTAIN;
    }

    private static boolean anyClassification(List<JsonNode> assertions, Set<String> classifications) {
        return assertions.stream().anyMatch(assertion -> classifications.contains(assertion.path("classification").asText()));
    }

    private static ObjectNode evaluate(ObjectNode region, JsonNode survey, List<Comparison> comparisons) {
        int[] inside = new int[Role.values().length];
        int[] outside = new int[inside.length];
        for (var comparison : comparisons) {
            var counts = Pm1RegionSelection.covers(region, comparison.position()) ? inside : outside;
            counts[comparison.role().ordinal()]++;
        }
        int pathogenic = inside[Role.INDEPENDENT_PATHOGENIC.ordinal()];
        int benign = inside[Role.QUALIFIED_BENIGN.ordinal()];
        int conflicting = inside[Role.CONFLICTING.ordinal()];
        int unresolved = inside[Role.UNRESOLVED.ordinal()];
        boolean complete = "COMPLETE".equals(survey.path("status").asText())
                && Pm1RegionSelection.covers(survey, region.path("start").asInt())
                && Pm1RegionSelection.covers(survey, region.path("end").asInt());
        region.putObject("comparisonCounts").put("pathogenicInside", pathogenic).put("benignInside", benign)
                .put("conflictingInside", conflicting).put("unresolvedInside", unresolved)
                .put("pathogenicOutside", outside[Role.INDEPENDENT_PATHOGENIC.ordinal()])
                .put("benignOutside", outside[Role.QUALIFIED_BENIGN.ordinal()]);
        region.put("surveyCompleteForRegion", complete);
        var issues = region.putArray("issues");
        if (benign > 0) issues.add(BENIGN_PRESENT);
        if (conflicting > 0) issues.add(CONFLICT);
        if (unresolved > 0) issues.add("PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE");
        if (!complete) issues.add("PM1_REVIEWED_REGION_BENIGN_SURVEY_INCOMPLETE");

        String strength;
        if (DOCUMENTED.equals(region.path("method").asText())) {
            if (!region.path("established").asBoolean()) issues.add("PM1_REVIEWED_REGION_REVIEW_PENDING");
            if (pathogenic == 0) issues.add("PM1_REVIEWED_REGION_INDEPENDENT_OBSERVATION_MISSING");
            strength = "MODERATE";
        } else {
            strength = enrichment(region);
            if (!region.has("calibration")) issues.add("PM1_REGION_ENRICHMENT_CALIBRATION_REQUIRED");
            if (!region.has("enrichment")) issues.add("PM1_REGION_ENRICHMENT_DATA_INSUFFICIENT");
        }
        if (benign > 0) return outcome(region, "NOT_MET", BENIGN_PRESENT, "NONE");
        if (!issues.isEmpty()) return outcome(region, "NOT_EVALUATED", issues.path(0).asText(), "NONE");
        if ("NONE".equals(strength)) return outcome(region, "NOT_MET", "PM1_REGION_ENRICHMENT_BELOW_THRESHOLD", "NONE");
        return outcome(region, "MET", "PM1_REVIEWED_REGION_MATCH", strength);
    }

    private static String enrichment(ObjectNode region) {
        var counts = region.path("comparisonCounts");
        int a = counts.path("pathogenicInside").asInt();
        int b = counts.path("benignInside").asInt();
        int c = counts.path("pathogenicOutside").asInt();
        int d = counts.path("benignOutside").asInt();
        var table = EnrichmentTable.calculate(a, b, c, d);
        if (table == null) return "NONE";
        region.putObject("enrichment").put("method", "FISHER_EXACT_ONE_SIDED")
                .put("oddsRatio", table.oddsRatio()).put("pValue", table.pValue());
        String strength = "NONE";
        for (var level : region.at("/calibration/levels")) {
            if (a >= level.path("minimumIndependentPathogenic").asInt() && table.passes(level)) {
                strength = level.path("strength").asText();
                if ("MODERATE".equals(strength)) break;
            }
        }
        return strength;
    }

    private record EnrichmentTable(int oddsNumerator, int oddsDenominator, BigInteger tail, BigInteger total) {
        @Nullable static EnrichmentTable calculate(int a, int b, int c, int d) {
            int inside = a + b;
            int pathogenic = a + c;
            int benign = b + d;
            if (inside == 0 || c + d == 0 || pathogenic == 0 || benign == 0) return null;
            BigInteger tail = BigInteger.ZERO;
            for (int count = a; count <= Math.min(inside, pathogenic); count++) {
                tail = tail.add(choose(pathogenic, count).multiply(choose(benign, inside - count)));
            }
            return new EnrichmentTable(a * d, b * c, tail, choose(pathogenic + benign, inside));
        }

        boolean passes(JsonNode level) {
            return BigDecimal.valueOf(oddsNumerator).compareTo(
                    decimal(level.path("minimumOddsRatio")).multiply(BigDecimal.valueOf(oddsDenominator))) >= 0
                    && new BigDecimal(tail).compareTo(decimal(level.path("maximumPValue")).multiply(new BigDecimal(total))) <= 0;
        }

        String oddsRatio() {
            return oddsDenominator == 0 ? "INFINITE"
                    : divide(BigDecimal.valueOf(oddsNumerator), BigDecimal.valueOf(oddsDenominator));
        }

        String pValue() {
            return divide(new BigDecimal(tail), new BigDecimal(total));
        }

        private static BigInteger choose(int n, int k) {
            BigInteger result = BigInteger.ONE;
            int steps = Math.min(k, n - k);
            for (int i = 1; i <= steps; i++) {
                result = result.multiply(BigInteger.valueOf(n - i + 1)).divide(BigInteger.valueOf(i));
            }
            return result;
        }

        private static String divide(BigDecimal numerator, BigDecimal denominator) {
            return numerator.divide(denominator, MathContext.DECIMAL128).stripTrailingZeros().toString();
        }
    }

    private static ObjectNode outcome(ObjectNode region, String status, String reasonCode, String strength) {
        return region.put("status", status).put("reasonCode", reasonCode).put("appliedStrength", strength);
    }

    private static int decisionOrder(JsonNode region) {
        return switch (region.path("status").asText()) {
            case "MET" -> "MODERATE".equals(region.path("appliedStrength").asText()) ? 0 : 1;
            case "NOT_EVALUATED" -> 2;
            default -> 3;
        };
    }

    private static boolean positiveInt(JsonNode value, int maximum) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 1 && value.asInt() <= maximum;
    }

    private static BigDecimal decimal(JsonNode value) {
        check(value.isTextual() && value.asText().matches("[0-9]{1,6}(?:\\.[0-9]{1,12})?"));
        return new BigDecimal(value.asText());
    }
}
