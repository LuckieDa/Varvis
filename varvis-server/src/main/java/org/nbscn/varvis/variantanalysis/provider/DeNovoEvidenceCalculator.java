package org.nbscn.varvis.variantanalysis.provider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic SVI de novo arithmetic for qualified observations. This is not an
 * ingestion API: package-local inputs must be produced by a source qualifier.
 * Old PS2/PM6 bundles and their versioned evaluators deliberately remain intact.
 */
public final class DeNovoEvidenceCalculator {
    public static final String VERSION = "denovo-event-calculator-1.2.0";
    static final int MAX_OBSERVATIONS = 1000;

    enum SourceKind { CURRENT_CASE, LITERATURE_CASE }
    enum Parentage { CONFIRMED, ASSUMED_NOT_TESTED, ASSUMED_NOT_REPORTED, ASSUMED_NOT_CONFIRMED, INCONCLUSIVE, EXCLUDED }
    enum TargetTest { PRESENT, ABSENT_QUALIFIED, UNKNOWN, QUALITY_FAILED, CONFLICT }
    enum Phenotype { HIGHLY_SPECIFIC, RASOPATHY_COMPATIBLE, CONSISTENT, CONSISTENT_HIGH_HETEROGENEITY, NOT_CONSISTENT, UNKNOWN }
    enum FamilyHistory { COMPATIBLE, INHERITED, RELEVANT_HISTORY_PRESENT, UNKNOWN, CONFLICT, MOSAIC_OR_SIBLING_RECURRENCE }
    enum Independence { ESTABLISHED, POSSIBLE_OVERLAP }
    enum Status { MET, NOT_MET, NOT_EVALUATED }
    enum Strength { NONE, SUPPORTING, MODERATE, STRONG, VERY_STRONG }
    enum Code { NONE, PS2, PM6 }
    enum Disposition { COUNTED, ZERO_SUPPORT, DUPLICATE, EXCLUDED }

    record Group(String alleleKey, String hgncId, String diseaseId, String inheritance) {
        Group {
            requireText(alleleKey); requireText(hgncId); requireText(diseaseId); requireText(inheritance);
        }
    }

    /** No data-dependent policy changes: a selected policy is fixed before calculation. */
    record Policy(String id, boolean allowAssumedUntestedParentage, boolean applicableExpertPolicyUnresolved, DeNovoEventPolicy specialEvents) {
        Policy(String id, boolean assumed, boolean unresolved) { this(id, assumed, unresolved, null); }
        Policy { requireText(id); }
        boolean rasopathyPoints() { return specialEvents != null && specialEvents.rasopathyPoints(); }
        boolean phenotypeOnly() { return specialEvents != null && specialEvents.phenotypeOnly(); }
        boolean publishedPoints() { return specialEvents != null && specialEvents.publishedPoints(); }
    }

    record Facts(Group group, TargetTest proband, TargetTest mother, TargetTest father,
                 Parentage maternity, Parentage paternity, Phenotype phenotype, FamilyHistory familyHistory, DeNovoSpecialEvent.Verified special) {
        Facts(Group group, TargetTest proband, TargetTest mother, TargetTest father, Parentage maternity, Parentage paternity, Phenotype phenotype, FamilyHistory familyHistory) {
            this(group, proband, mother, father, maternity, paternity, phenotype, familyHistory, null);
        }
        Facts {
            Objects.requireNonNull(group); Objects.requireNonNull(proband); Objects.requireNonNull(mother);
            Objects.requireNonNull(father); Objects.requireNonNull(maternity); Objects.requireNonNull(paternity);
            Objects.requireNonNull(phenotype); Objects.requireNonNull(familyHistory);
        }
        boolean confirmed() { return maternity == Parentage.CONFIRMED && paternity == Parentage.CONFIRMED; }
        boolean hasNegativeParentalTests() {
            return proband == TargetTest.PRESENT && mother == TargetTest.ABSENT_QUALIFIED && father == TargetTest.ABSENT_QUALIFIED;
        }
    }

    /** eventId is a qualified occurrence identity, not an LLM-generated claim of independence. */
    record Observation(String observationId, String eventId, SourceKind sourceKind, Facts facts,
                       Independence independence, List<String> sourceRefs, List<String> qualificationIssues) {
        Observation {
            requireText(observationId); requireText(eventId); Objects.requireNonNull(sourceKind);
            Objects.requireNonNull(facts); Objects.requireNonNull(independence);
            sourceRefs = List.copyOf(sourceRefs); sourceRefs.forEach(DeNovoEvidenceCalculator::requireText);
            qualificationIssues = List.copyOf(qualificationIssues);
            qualificationIssues.forEach(DeNovoEvidenceCalculator::requireText);
        }
    }

    public record EventResult(String observationId, String eventId, String sourceKind, String disposition,
                              String reasonCode, int rawQuarterPoints, boolean confirmedParentage,
                              List<String> sourceRefs, List<String> details) {
        public EventResult { sourceRefs = List.copyOf(sourceRefs); details = List.copyOf(details); }
    }

    public record Result(String calculatorVersion, String policyId, String status, String code, String strength,
                         String reasonCode, int rawQuarterPoints, int effectiveQuarterPoints,
                         int highHeterogeneityRawQuarterPoints, int highHeterogeneityEffectiveQuarterPoints,
                         boolean hasExcludedObservations, List<EventResult> events, String calculationMethod, String strengthAdjustment) {
        public Result(String calculatorVersion, String policyId, String status, String code, String strength,
                      String reasonCode, int rawQuarterPoints, int effectiveQuarterPoints,
                      int highHeterogeneityRawQuarterPoints, int highHeterogeneityEffectiveQuarterPoints,
                      boolean hasExcludedObservations, List<EventResult> events) {
            this(calculatorVersion, policyId, status, code, strength, reasonCode, rawQuarterPoints, effectiveQuarterPoints,
                    highHeterogeneityRawQuarterPoints, highHeterogeneityEffectiveQuarterPoints, hasExcludedObservations, events, "SVI_POINTS", "NONE");
        }
        public Result { events = List.copyOf(events); }
        public BigDecimal points() { return BigDecimal.valueOf(effectiveQuarterPoints).divide(BigDecimal.valueOf(4)); }
    }

    Result evaluate(Group group, Policy policy, List<Observation> input) {
        Objects.requireNonNull(group); Objects.requireNonNull(policy);
        List<Observation> observations = List.copyOf(input);
        if (observations.size() > MAX_OBSERVATIONS) throw new IllegalArgumentException("DENOVO_OBSERVATION_LIMIT");
        List<EventResult> events = new ArrayList<>();
        if (!List.of("AD", "AR", "X_LINKED").contains(group.inheritance())) return unavailable(policy, "DENOVO_INHERITANCE_NOT_SUPPORTED", events);
        if (!"AD".equals(group.inheritance()) && policy.specialEvents() == null) return unavailable(policy, "DENOVO_SPECIAL_POLICY_REQUIRED", events);
        if (policy.applicableExpertPolicyUnresolved()) return unavailable(policy, "DENOVO_EXPERT_POLICY_UNRESOLVED", events);
        if (observations.isEmpty()) return unavailable(policy, "DENOVO_NO_OBSERVATIONS", events);

        // A contradictory shared identity is not an innocuous missing observation.
        if (observations.stream().anyMatch(o -> !group.equals(o.facts().group()))) {
            for (Observation o : observations) events.add(event(o, Disposition.EXCLUDED, "DENOVO_GROUP_IDENTITY_CONFLICT", 0));
            return unavailable(policy, "DENOVO_GROUP_IDENTITY_CONFLICT", events);
        }
        Map<String, Observation> ids = new LinkedHashMap<>();
        for (Observation o : observations) {
            if (ids.putIfAbsent(o.observationId(), o) != null) {
                for (Observation row : observations) events.add(event(row, Disposition.EXCLUDED, "DENOVO_OBSERVATION_ID_COLLISION", 0));
                return unavailable(policy, "DENOVO_OBSERVATION_ID_COLLISION", events);
            }
        }
        Map<String, List<Observation>> occurrences = new TreeMap<>();
        for (Observation o : observations) occurrences.computeIfAbsent(o.eventId(), key -> new ArrayList<>()).add(o);
        int normal = 0, heterogeneous = 0, evaluated = 0;
        int confirmedOccurrences = 0, assumedOccurrences = 0;
        Set<String> arStates = new java.util.HashSet<>();
        int fixedLevel = 0;
        for (List<Observation> occurrence : occurrences.values()) {
            occurrence.sort(Comparator.comparing(mappedItem -> mappedItem.observationId()));
            if (policy.publishedPoints() && occurrence.size() > 1 && occurrence.stream().anyMatch(o ->
                    o.qualificationIssues().stream().anyMatch(i -> i.contains("CONFLICT"))
                            || prerequisiteFailures(o.facts(), policy).stream().anyMatch(i -> i.contains("CONFLICT")))) {
                for (Observation o : occurrence) events.add(failedEvent(o, policy, "DENOVO_OCCURRENCE_FACT_CONFLICT"));
                continue;
            }
            List<Observation> usable = new ArrayList<>();
            for (Observation o : occurrence) {
                if (o.sourceRefs().isEmpty()) events.add(event(o, Disposition.EXCLUDED, "DENOVO_SOURCE_REQUIRED", 0));
                else if (!o.qualificationIssues().isEmpty()) events.add(failedEvent(o, policy, "DENOVO_FACTS_NOT_QUALIFIED"));
                else if (o.independence() != Independence.ESTABLISHED) events.add(event(o, Disposition.EXCLUDED, "DENOVO_INDEPENDENCE_UNRESOLVED", 0));
                else usable.add(o);
            }
            if (usable.isEmpty()) continue;
            boolean shared = usable.stream().allMatch(o -> o.facts().special() != null && o.facts().special().sharedOrigin());
            if (shared) {
                var origin = usable.get(0).facts().special();
                if (usable.stream().anyMatch(o -> !origin.compatibleSharedOrigin(o.facts().special()))) {
                    for (var o : usable) events.add(event(o, Disposition.EXCLUDED, "DENOVO_SHARED_ORIGIN_SOURCE_CONFLICT", 0));
                    continue;
                }
                var qualified = new ArrayList<Observation>();
                for (var o : usable) {
                    var f = effective(o.facts()); String issue = prerequisite(f, policy);
                    if (issue != null) events.add(failedEvent(o, policy, issue));
                    else qualified.add(o);
                }
                if (qualified.isEmpty()) continue;
                // One proven biological origin is not several independent mutations.
                // Select one qualified observation, never a fixed family point value.
                qualified.sort(Comparator.<Observation>comparingInt(o -> representativeSupport(effective(o.facts()), policy)).reversed()
                        .thenComparing(o -> !effective(o.facts()).confirmed()).thenComparing(mappedItem -> mappedItem.observationId()));
                usable = qualified;
                if ("AR".equals(group.inheritance())) for (var o : usable) {
                    var f = effective(o.facts()); if (quarterPoints(f) > 0) arStates.add(f.special().partnerStatus());
                }
            }
            Facts facts = effective(usable.get(0).facts());
            if (!shared && usable.stream().anyMatch(o -> !effective(o.facts()).equals(facts))) {
                for (Observation o : usable) events.add(event(o, Disposition.EXCLUDED, "DENOVO_OCCURRENCE_FACT_CONFLICT", 0));
                continue;
            }
            Observation first = usable.get(0);
            String failure = prerequisite(facts, policy);
            if (failure != null) {
                for (Observation o : usable) events.add(failedEvent(o, policy, failure));
                continue;
            }
            if (policy.specialEvents() != null && policy.specialEvents().tissueRule()) {
                int level = facts.special() == null ? -1 : facts.phenotype() == Phenotype.NOT_CONSISTENT ? 0 : facts.special().tissueStrength(facts);
                if (level < 0) {
                    for (Observation o : usable) events.add(event(o, Disposition.EXCLUDED, "DENOVO_TISSUE_OBSERVATION_CONFLICT", 0));
                    continue;
                }
                fixedLevel = Math.max(fixedLevel, level); evaluated++;
                events.add(event(first, level > 0 ? Disposition.COUNTED : Disposition.ZERO_SUPPORT,
                        level > 0 ? "DENOVO_EXPERT_TISSUE_STRENGTH" : "DENOVO_EXPERT_TISSUE_CONDITIONS_NOT_MET", 0));
                for (int i = 1; i < usable.size(); i++) events.add(event(usable.get(i), Disposition.DUPLICATE, "DENOVO_SAME_OCCURRENCE", 0));
                continue;
            }
            int points = quarterPoints(facts);
            if (points > 0 && "AR".equals(group.inheritance()) && facts.special() != null) arStates.add(facts.special().partnerStatus());
            evaluated++;
            if (facts.phenotype() == Phenotype.CONSISTENT_HIGH_HETEROGENEITY) heterogeneous += points;
            else normal += points;
            if (points > 0) {
                if (facts.confirmed()) confirmedOccurrences++;
                else assumedOccurrences++;
            }
            events.add(event(first, points == 0 ? Disposition.ZERO_SUPPORT : Disposition.COUNTED,
                    points == 0 ? zeroReason(facts) : "DENOVO_EVENT_INCLUDED", points));
            for (int i = 1; i < usable.size(); i++) events.add(event(usable.get(i), Disposition.DUPLICATE, "DENOVO_SAME_OCCURRENCE", 0));
        }
        events.sort(Comparator.comparing(mappedItem -> mappedItem.observationId()));
        if (evaluated == 0) return unavailable(policy, "DENOVO_NO_QUALIFIED_OCCURRENCES", events);
        if (policy.specialEvents() != null && policy.specialEvents().tissueRule())
            return new Result(VERSION, policy.id(), fixedLevel > 0 ? "MET" : "NOT_MET", fixedLevel > 0 ? "PS2" : "NONE",
                    fixedLevel == 2 ? "STRONG" : fixedLevel == 1 ? "MODERATE" : "NONE",
                    fixedLevel > 0 ? "DENOVO_EXPERT_TISSUE_STRENGTH" : "DENOVO_EXPERT_TISSUE_CONDITIONS_NOT_MET",
                    0, 0, 0, 0, events.stream().anyMatch(e -> Disposition.EXCLUDED.name().equals(e.disposition())), events,
                    "EXPERT_FIXED_STRENGTH", "NO_SVI_POINT_SUM");
        if (arStates.size() > 1) return new Result(VERSION, policy.id(), "NOT_EVALUATED", "NONE", "NONE",
                "DENOVO_AR_MIXED_REDUCTION_RULE_NOT_IMPLEMENTED", normal + heterogeneous, normal + Math.min(heterogeneous, 4),
                heterogeneous, Math.min(heterogeneous, 4), events.stream().anyMatch(e -> Disposition.EXCLUDED.name().equals(e.disposition())), events,
                "SVI_POINTS", "MIXED_AR_RULE_NOT_IMPLEMENTED");
        int capped = Math.min(heterogeneous, 4), total = normal + capped;
        Strength strength = total >= 16 ? Strength.VERY_STRONG : total >= 8 ? Strength.STRONG
                : total >= 4 ? Strength.MODERATE : total >= 2 ? Strength.SUPPORTING : Strength.NONE;
        if (policy.rasopathyPoints() && strength == Strength.VERY_STRONG
                && confirmedOccurrences < 2 && !(confirmedOccurrences == 1 && assumedOccurrences >= 2))
            return new Result(VERSION, policy.id(), "NOT_EVALUATED", "NONE", "NONE",
                    "DENOVO_EXPERT_STRENGTH_SCOPE_UNRESOLVED", normal + heterogeneous, total, heterogeneous, capped,
                    events.stream().anyMatch(e -> Disposition.EXCLUDED.name().equals(e.disposition())), events);
        String adjustment = "NONE";
        if ("AR".equals(group.inheritance()) && arStates.contains("NONE_IN_CHECKED_SCOPE")) {
            strength = switch (strength) { case VERY_STRONG -> Strength.STRONG; case STRONG -> Strength.MODERATE;
                case MODERATE -> Strength.SUPPORTING; case SUPPORTING, NONE -> Strength.NONE; };
            adjustment = "ONE_LEVEL_AR_NO_SECOND_P_LP";
        }
        var specialPolicy = policy.specialEvents();
        if (specialPolicy != null && specialPolicy.publishedPoints() && confirmedOccurrences == 0
                && strength.compareTo(specialPolicy.maximumPm6Strength()) > 0) {
            strength = specialPolicy.maximumPm6Strength();
            adjustment = "PM6_PUBLISHED_STRENGTH_CAP";
        }
        boolean met = strength != Strength.NONE;
        Code code = met ? (confirmedOccurrences > 0 || specialPolicy != null && specialPolicy.combinedPs2() ? Code.PS2 : Code.PM6) : Code.NONE;
        boolean phenotypeMismatch = (policy.phenotypeOnly() || policy.publishedPoints()) && events.stream().anyMatch(e ->
                "DENOVO_PHENOTYPE_NOT_CONSISTENT".equals(e.reasonCode()));
        boolean familyHistoryPresent = policy.publishedPoints() && events.stream().anyMatch(e ->
                "DENOVO_PUBLISHED_FAMILY_HISTORY_CONDITION_NOT_MET".equals(e.reasonCode()));
        return new Result(VERSION, policy.id(), (met ? Status.MET : Status.NOT_MET).name(), code.name(), strength.name(),
                "ONE_LEVEL_AR_NO_SECOND_P_LP".equals(adjustment) ? "DENOVO_AR_ONE_LEVEL_REDUCTION" : met ? "DENOVO_QUALIFIED_POINTS"
                        : phenotypeMismatch ? "DENOVO_PHENOTYPE_NOT_CONSISTENT" : familyHistoryPresent ? "DENOVO_PUBLISHED_FAMILY_HISTORY_CONDITION_NOT_MET"
                        : "DENOVO_BELOW_SUPPORTING", normal + heterogeneous, total,
                heterogeneous, capped, events.stream().anyMatch(e -> Disposition.EXCLUDED.name().equals(e.disposition())), events, "SVI_POINTS", adjustment);
    }

    static String prerequisite(Facts facts, Policy policy) {
        var failures = prerequisiteFailures(facts, policy);
        return failures.isEmpty() ? null : failures.get(0);
    }

    /** Full diagnostic list in the historical first-failure order. */
    static List<String> prerequisiteFailures(Facts facts, Policy policy) {
        facts = effective(facts);
        var failures = new ArrayList<String>();
        if (!"AD".equals(facts.group().inheritance()) && facts.special() == null) failures.add("DENOVO_SPECIAL_FACTS_REQUIRED");
        if (facts.special() != null) failures.addAll(facts.special().prerequisites(facts, policy.specialEvents()));
        if (policy.specialEvents() != null && policy.specialEvents().tissueRule()) {
            if (facts.special() == null) failures.add("DENOVO_SPECIAL_FACTS_REQUIRED");
            if (facts.phenotype() == Phenotype.UNKNOWN) failures.add("DENOVO_PHENOTYPE_BASIS_REQUIRED");
            if (facts.maternity() == Parentage.EXCLUDED || facts.paternity() == Parentage.EXCLUDED) failures.add("DENOVO_PARENTAGE_EXCLUDED");
            return List.copyOf(failures);
        }
        if (facts.maternity() == Parentage.EXCLUDED || facts.paternity() == Parentage.EXCLUDED) failures.add("DENOVO_PARENTAGE_EXCLUDED");
        if (facts.maternity() == Parentage.INCONCLUSIVE || facts.paternity() == Parentage.INCONCLUSIVE) failures.add("DENOVO_PARENTAGE_INCONCLUSIVE");
        if (facts.special() == null && facts.familyHistory() == FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE) failures.add("DENOVO_SPECIAL_FAMILY_MODEL_NOT_SUPPORTED");
        boolean establishedTransmission = facts.familyHistory() == FamilyHistory.INHERITED && "AD".equals(facts.group().inheritance())
                && facts.proband() == TargetTest.PRESENT
                && (facts.mother() == TargetTest.PRESENT && facts.father() == TargetTest.ABSENT_QUALIFIED
                || facts.father() == TargetTest.PRESENT && facts.mother() == TargetTest.ABSENT_QUALIFIED);
        // The phenotype-only check can rule out a contribution without qualifying family history for positive scoring.
        boolean familyHistoryNotRequired = (policy.rasopathyPoints() && !facts.confirmed() || policy.phenotypeOnly()
                || policy.publishedPoints() && (!facts.hasNegativeParentalTests()
                || facts.phenotype() == Phenotype.NOT_CONSISTENT || facts.phenotype() == Phenotype.UNKNOWN))
                && facts.familyHistory() == FamilyHistory.UNKNOWN;
        boolean relevantHistoryPresent = policy.publishedPoints() && facts.familyHistory() == FamilyHistory.RELEVANT_HISTORY_PRESENT;
        if (facts.special() == null && facts.familyHistory() != FamilyHistory.COMPATIBLE && !establishedTransmission
                && !familyHistoryNotRequired && !relevantHistoryPresent
                || facts.familyHistory() == FamilyHistory.CONFLICT)
            failures.add("DENOVO_FAMILY_HISTORY_UNRESOLVED");
        if (facts.proband() == TargetTest.CONFLICT || facts.mother() == TargetTest.CONFLICT || facts.father() == TargetTest.CONFLICT) failures.add("DENOVO_TARGET_TEST_CONFLICT");
        if (facts.proband() == TargetTest.QUALITY_FAILED || facts.mother() == TargetTest.QUALITY_FAILED || facts.father() == TargetTest.QUALITY_FAILED) failures.add("DENOVO_TARGET_TEST_QUALITY_FAILED");
        if (facts.proband() == TargetTest.UNKNOWN) failures.add("DENOVO_PROBAND_TARGET_TEST_REQUIRED");
        if (facts.mother() == TargetTest.UNKNOWN) failures.add("DENOVO_MATERNAL_TARGET_TEST_REQUIRED");
        if (facts.father() == TargetTest.UNKNOWN) failures.add("DENOVO_PATERNAL_TARGET_TEST_REQUIRED");
        if (facts.phenotype() == Phenotype.UNKNOWN) failures.add("DENOVO_PHENOTYPE_BASIS_REQUIRED");
        else if (policy.phenotypeOnly() && facts.phenotype() != Phenotype.NOT_CONSISTENT && facts.proband() == TargetTest.PRESENT
                && facts.mother() == TargetTest.ABSENT_QUALIFIED && facts.father() == TargetTest.ABSENT_QUALIFIED)
            failures.add("DENOVO_EXPERT_POSITIVE_POINTS_UNIMPLEMENTED");
        if (!facts.confirmed() && !policy.allowAssumedUntestedParentage()) failures.add("DENOVO_ASSUMED_PARENTAGE_POLICY_REQUIRED");
        return List.copyOf(failures);
    }

    private static int quarterPoints(Facts facts) {
        if (!facts.hasNegativeParentalTests() || facts.familyHistory() == FamilyHistory.RELEVANT_HISTORY_PRESENT) return 0;
        int confirmed = switch (facts.phenotype()) {
            case HIGHLY_SPECIFIC, RASOPATHY_COMPATIBLE -> 8;
            case CONSISTENT -> 4;
            case CONSISTENT_HIGH_HETEROGENEITY -> 2;
            case NOT_CONSISTENT -> 0;
            case UNKNOWN -> throw new IllegalStateException("Unqualified phenotype");
        };
        return facts.confirmed() ? confirmed : confirmed / 2;
    }

    private static String zeroReason(Facts facts) {
        if (facts.proband() != TargetTest.PRESENT) return "DENOVO_PROBAND_TARGET_ABSENT";
        if (facts.mother() == TargetTest.PRESENT || facts.father() == TargetTest.PRESENT) return "DENOVO_PARENTAL_TARGET_PRESENT";
        if (facts.familyHistory() == FamilyHistory.RELEVANT_HISTORY_PRESENT) return "DENOVO_PUBLISHED_FAMILY_HISTORY_CONDITION_NOT_MET";
        return "DENOVO_PHENOTYPE_NOT_CONSISTENT";
    }

    private static EventResult event(Observation o, Disposition disposition, String reason, int points) {
        return new EventResult(o.observationId(), o.eventId(), o.sourceKind().name(), disposition.name(), reason, points,
                effective(o.facts()).confirmed(), o.sourceRefs(), eventDetails(o));
    }

    private static int representativeSupport(Facts facts, Policy policy) {
        if (policy.specialEvents() != null && policy.specialEvents().tissueRule())
            return facts.phenotype() == Phenotype.NOT_CONSISTENT ? 0 : facts.special().tissueStrength(facts);
        return quarterPoints(facts);
    }
    private static Facts effective(Facts facts) { return facts.special() == null ? facts : facts.special().eventFacts(facts); }
    private static EventResult failedEvent(Observation o, Policy policy, String reason) {
        var prerequisites = prerequisiteFailures(o.facts(), policy);
        if ("DENOVO_FACTS_NOT_QUALIFIED".equals(reason) && o.qualificationIssues().size() == 1) {
            String issue = o.qualificationIssues().get(0);
            if (policy.publishedPoints() || policy.phenotypeOnly() && "DENOVO_PHENOTYPE_BASIS_REQUIRED".equals(issue))
                reason = issue;
        }
        var result = event(o, Disposition.EXCLUDED, reason, 0);
        var issues = new java.util.LinkedHashSet<>(result.details()); issues.addAll(prerequisites);
        return new EventResult(result.observationId(), result.eventId(), result.sourceKind(), result.disposition(), result.reasonCode(),
                result.rawQuarterPoints(), result.confirmedParentage(), result.sourceRefs(), List.copyOf(issues));
    }
    private static List<String> eventDetails(Observation o) {
        var out = new ArrayList<>(o.qualificationIssues());
        if (o.facts().special() != null) out.add("DENOVO_EVENT_KIND_" + o.facts().special().kind());
        return List.copyOf(out);
    }

    private static Result unavailable(Policy policy, String reason, List<EventResult> events) {
        return new Result(VERSION, policy.id(), Status.NOT_EVALUATED.name(), Code.NONE.name(), Strength.NONE.name(),
                reason, 0, 0, 0, 0, !events.isEmpty(), events);
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) throw new IllegalArgumentException("DENOVO_NONEMPTY_EXACT_ID_REQUIRED");
    }
}
