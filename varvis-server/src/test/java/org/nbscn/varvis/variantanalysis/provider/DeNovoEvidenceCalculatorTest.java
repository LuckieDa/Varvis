package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Invented cases only. No clinical facts, upstream source qualification or model call is claimed. */
class DeNovoEvidenceCalculatorTest {
    private static final Group GROUP = new Group("GRCh38:1:100:A:G", "HGNC:SYNTHETIC", "MONDO:SYNTHETIC", "AD");
    private static final Policy POLICY = new Policy("SYNTHETIC-acmg2015-svi1.1", true, false);
    private final DeNovoEvidenceCalculator calculator = new DeNovoEvidenceCalculator();

    @TestFactory Stream<DynamicTest> frozenSingleEventMatrix() throws Exception {
        var stream = getClass().getResourceAsStream("/denovo-automatic/frozen-expectations.tsv");
        assertNotNull(stream);
        List<String> lines;
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { lines = reader.lines().skip(1).toList(); }
        return lines.stream().map(line -> {
            String[] row = line.split("\t");
            return DynamicTest.dynamicTest(row[0], () -> {
                boolean confirmed = Boolean.parseBoolean(row[1]);
                Result result = run(observation("a", "event-a", confirmed, Phenotype.valueOf(row[2])));
                assertEquals(Integer.parseInt(row[3]), result.effectiveQuarterPoints());
                assertEquals(row[4], result.status()); assertEquals(row[5], result.strength());
                assertEquals("MET".equals(row[4]) ? (confirmed ? "PS2" : "PM6") : "NONE", result.code());
            });
        });
    }

    @Test void mixedConfirmedAndAssumedGivesOneVeryStrongCode() {
        Result result = run(observation("a", "1", true, Phenotype.HIGHLY_SPECIFIC),
                observation("b", "2", false, Phenotype.HIGHLY_SPECIFIC), observation("c", "3", false, Phenotype.HIGHLY_SPECIFIC));
        assertEquals("PS2", result.code()); assertEquals("VERY_STRONG", result.strength());
        assertEquals(16, result.effectiveQuarterPoints()); assertEquals("4", result.points().toPlainString());
    }

    @Test void onlyPositiveConfirmedContributionSelectsPs2() {
        Result result = run(observation("a", "1", true, Phenotype.NOT_CONSISTENT), observation("b", "2", false, Phenotype.HIGHLY_SPECIFIC));
        assertEquals("PM6", result.code()); assertEquals("MODERATE", result.strength());
    }

    @Test void allAssumedCanAccumulateBeyondOriginalModerateLevel() {
        List<Observation> cases = new ArrayList<>();
        for (int n = 0; n < 4; n++) cases.add(observation("case-" + n, "event-" + n, false, Phenotype.HIGHLY_SPECIFIC));
        Result result = calculator.evaluate(GROUP, POLICY, cases);
        assertEquals("PM6", result.code()); assertEquals("VERY_STRONG", result.strength());
    }

    @Test void rasopathyFullPointsKeepAssumedEventsInPm6AndPreserveUnresolvedVeryStrongScope() {
        var mode = new ObjectMapper().createObjectNode().put("mode", "RASOPATHY_POINTS");
        mode.putArray("sourceIds").add("expert");
        var policy = new Policy("SYNTHETIC-expert", true, false,
                DeNovoEventPolicy.read(mode, "APPLICABLE_IMPLEMENTED", Set.of("expert")));
        var observations = new ArrayList<Observation>();
        for (int i = 1; i <= 4; i++) {
            var original = observation("case-" + i, "family-" + i, false, Phenotype.RASOPATHY_COMPATIBLE);
            var f = original.facts();
            observations.add(withFacts(original, new Facts(GROUP, f.proband(), f.mother(), f.father(),
                    f.maternity(), f.paternity(), f.phenotype(), FamilyHistory.UNKNOWN)));
        }
        var single = calculator.evaluate(GROUP, policy, observations.subList(0, 1));
        assertEquals("PM6", single.code()); assertEquals("MODERATE", single.strength());
        var pair = calculator.evaluate(GROUP, policy, observations.subList(0, 2));
        assertEquals("PM6", pair.code()); assertEquals("STRONG", pair.strength());
        assertEquals(2, pair.events().size());
        assertEquals(4, pair.events().get(0).rawQuarterPoints());
        assertEquals(4, pair.events().get(1).rawQuarterPoints());
        var unresolved = calculator.evaluate(GROUP, policy, observations);
        assertEquals("DENOVO_EXPERT_STRENGTH_SCOPE_UNRESOLVED", unresolved.reasonCode());
        assertEquals(16, unresolved.effectiveQuarterPoints()); assertEquals("NONE", unresolved.code());
        assertEquals("NOT_EVALUATED", calculator.evaluate(GROUP, POLICY, observations).status());

        var f = observations.get(0).facts();
        var confirmed = new Facts(GROUP, f.proband(), f.mother(), f.father(), Parentage.CONFIRMED, Parentage.CONFIRMED,
                f.phenotype(), FamilyHistory.UNKNOWN);
        assertEquals("DENOVO_FAMILY_HISTORY_UNRESOLVED", DeNovoEvidenceCalculator.prerequisite(confirmed, policy));
        var missingFather = new Facts(GROUP, f.proband(), f.mother(), TargetTest.UNKNOWN,
                f.maternity(), f.paternity(), f.phenotype(), f.familyHistory());
        assertEquals("DENOVO_PATERNAL_TARGET_TEST_REQUIRED", DeNovoEvidenceCalculator.prerequisite(missingFather, policy));
    }

    @Test void highHeterogeneityCapAppliesToWholeCategory() {
        for (boolean confirmed : List.of(true, false)) {
            List<Observation> cases = new ArrayList<>();
            for (int n = 0; n < (confirmed ? 3 : 5); n++) cases.add(observation("case-" + n, "event-" + n, confirmed, Phenotype.CONSISTENT_HIGH_HETEROGENEITY));
            Result result = calculator.evaluate(GROUP, POLICY, cases);
            assertEquals(confirmed ? 6 : 5, result.rawQuarterPoints());
            assertEquals(4, result.effectiveQuarterPoints()); assertEquals(4, result.highHeterogeneityEffectiveQuarterPoints());
            assertEquals("MODERATE", result.strength());
        }
    }

    @Test void phenotypeOnlyPolicySeparatesZeroMissingAndUnsupportedPositive() {
        var mode = new ObjectMapper().createObjectNode().put("mode", "SVI_1_1_PHENOTYPE_ONLY");
        mode.putArray("sourceIds").add("expert");
        var policy = new Policy("SYNTHETIC-expert", true, false,
                DeNovoEventPolicy.read(mode, "APPLICABLE_IMPLEMENTED", Set.of("expert")));
        var negative = calculator.evaluate(GROUP, policy,
                List.of(observation("negative", "one", false, Phenotype.NOT_CONSISTENT)));
        assertEquals("NOT_MET", negative.status());
        assertEquals("DENOVO_PHENOTYPE_NOT_CONSISTENT", negative.reasonCode());
        assertEquals("ZERO_SUPPORT", negative.events().get(0).disposition());
        assertEquals(0, negative.effectiveQuarterPoints());
        var unreported = observation("missing", "two", false, Phenotype.UNKNOWN);
        var missing = calculator.evaluate(GROUP, policy, List.of(new Observation(unreported.observationId(), unreported.eventId(),
                SourceKind.LITERATURE_CASE, unreported.facts(), unreported.independence(), unreported.sourceRefs(),
                List.of("DENOVO_PHENOTYPE_BASIS_REQUIRED"))));
        assertEquals("NOT_EVALUATED", missing.status());
        assertEquals("EXCLUDED", missing.events().get(0).disposition());
        assertEquals("DENOVO_PHENOTYPE_BASIS_REQUIRED", missing.events().get(0).reasonCode());
        var positive = calculator.evaluate(GROUP, policy,
                List.of(observation("positive", "three", true, Phenotype.HIGHLY_SPECIFIC)));
        assertEquals("NOT_EVALUATED", positive.status());
        assertEquals("DENOVO_EXPERT_POSITIVE_POINTS_UNIMPLEMENTED", positive.events().get(0).reasonCode());
    }

    @Test void capDoesNotCapOtherPhenotypeCategories() {
        List<Observation> cases = new ArrayList<>();
        cases.add(observation("specific", "1", true, Phenotype.HIGHLY_SPECIFIC));
        for (int n = 0; n < 3; n++) cases.add(observation("hetero" + n, "h" + n, true, Phenotype.CONSISTENT_HIGH_HETEROGENEITY));
        Result result = calculator.evaluate(GROUP, POLICY, cases);
        assertEquals(14, result.rawQuarterPoints()); assertEquals(12, result.effectiveQuarterPoints());
        assertEquals("STRONG", result.strength());
    }

    @Test void sameCaseCurrentAndLiteratureNeverDoubleCount() {
        Observation first = observation("a", "same-event", true, Phenotype.CONSISTENT);
        Observation paper = new Observation("b", first.eventId(), SourceKind.LITERATURE_CASE, first.facts(),
                Independence.ESTABLISHED, List.of("synthetic-paper-2#row-1"), List.of());
        Result result = run(first, paper);
        assertEquals(4, result.effectiveQuarterPoints()); assertEquals("DUPLICATE", result.events().get(1).disposition());
        assertEquals(paper.sourceRefs(), result.events().get(1).sourceRefs());
    }

    @Test void changingFactsOfSameOccurrenceDoesNotSelectHigherScore() {
        Result result = run(observation("a", "same", false, Phenotype.CONSISTENT), observation("b", "same", true, Phenotype.HIGHLY_SPECIFIC));
        assertEquals("NOT_EVALUATED", result.status()); assertEquals(0, result.effectiveQuarterPoints());
        assertTrue(result.events().stream().allMatch(e -> e.reasonCode().equals("DENOVO_OCCURRENCE_FACT_CONFLICT")));
    }

    @Test void missingFatherNeverBecomesPm6() {
        Observation o = observation("a", "1", false, Phenotype.CONSISTENT);
        Facts f = o.facts();
        Result result = run(withFacts(o, new Facts(GROUP, f.proband(), f.mother(), TargetTest.UNKNOWN, f.maternity(), f.paternity(), f.phenotype(), f.familyHistory())));
        assertEquals("NOT_EVALUATED", result.status()); assertEquals("NONE", result.code());
        assertEquals("DENOVO_PATERNAL_TARGET_TEST_REQUIRED", result.events().get(0).reasonCode());
    }

    @Test void qualityConflictAndMissingTestAreDifferent() {
        Observation o = observation("a", "1", true, Phenotype.CONSISTENT); Facts f = o.facts();
        for (TargetTest bad : List.of(TargetTest.UNKNOWN, TargetTest.QUALITY_FAILED, TargetTest.CONFLICT)) {
            Result result = run(withFacts(o, new Facts(GROUP, f.proband(), bad, f.father(), f.maternity(), f.paternity(), f.phenotype(), f.familyHistory())));
            assertEquals("NOT_EVALUATED", result.status());
            assertEquals(switch (bad) { case UNKNOWN -> "DENOVO_MATERNAL_TARGET_TEST_REQUIRED";
                case QUALITY_FAILED -> "DENOVO_TARGET_TEST_QUALITY_FAILED"; default -> "DENOVO_TARGET_TEST_CONFLICT"; }, result.events().get(0).reasonCode());
        }
    }

    @Test void inheritedAndAbsentTargetAreEvaluatedZeroSupport() {
        Observation o = observation("a", "1", true, Phenotype.CONSISTENT); Facts f = o.facts();
        Result inherited = run(withFacts(o, new Facts(GROUP, f.proband(), TargetTest.PRESENT, f.father(), f.maternity(), f.paternity(), f.phenotype(), f.familyHistory())));
        assertEquals("NOT_MET", inherited.status()); assertEquals("DENOVO_PARENTAL_TARGET_PRESENT", inherited.events().get(0).reasonCode());
        Result absent = run(withFacts(o, new Facts(GROUP, TargetTest.ABSENT_QUALIFIED, f.mother(), f.father(), f.maternity(), f.paternity(), f.phenotype(), f.familyHistory())));
        assertEquals("NOT_MET", absent.status()); assertEquals("DENOVO_PROBAND_TARGET_ABSENT", absent.events().get(0).reasonCode());
    }

    @Test void reviewedTransmissionIsZeroSupportAndCannotQualifyTwoNegativeParents() {
        Observation parent = observation("a", "inherited", false, Phenotype.CONSISTENT);
        Facts family = parent.facts();
        var inherited = withFacts(parent, new Facts(GROUP, family.proband(), TargetTest.PRESENT, family.father(),
                family.maternity(), family.paternity(), family.phenotype(), FamilyHistory.INHERITED));
        var zero = run(inherited);
        assertEquals("NOT_MET", zero.status());
        assertEquals("ZERO_SUPPORT", zero.events().get(0).disposition());
        assertEquals("DENOVO_PARENTAL_TARGET_PRESENT", zero.events().get(0).reasonCode());
        var missing = withFacts(inherited, new Facts(GROUP, family.proband(), TargetTest.PRESENT, TargetTest.UNKNOWN,
                family.maternity(), family.paternity(), family.phenotype(), FamilyHistory.INHERITED));
        assertEquals("NOT_EVALUATED", run(missing).status());
        assertTrue(run(missing).events().get(0).details().contains("DENOVO_PATERNAL_TARGET_TEST_REQUIRED"));
        var contradicts = withFacts(parent, new Facts(GROUP, family.proband(), family.mother(), family.father(),
                family.maternity(), family.paternity(), family.phenotype(), FamilyHistory.INHERITED));
        assertEquals("NOT_EVALUATED", run(contradicts).status());
        var confirmed = observation("b", "independent-confirmed", true, Phenotype.CONSISTENT_HIGH_HETEROGENEITY);
        var assumed = observation("c", "independent-assumed", false, Phenotype.CONSISTENT_HIGH_HETEROGENEITY);
        var below = run(inherited, assumed);
        assertEquals(1, below.effectiveQuarterPoints());
        assertEquals("NOT_MET", below.status());
        var combined = run(inherited, confirmed);
        assertEquals(2, combined.effectiveQuarterPoints());
        assertEquals("PS2", combined.code());
        assertEquals("SUPPORTING", combined.strength());
        var above = run(inherited, confirmed, assumed);
        assertEquals(3, above.effectiveQuarterPoints());
        assertEquals("SUPPORTING", above.strength());
    }

    @Test void unknownPhenotypeDoesNotMeanInconsistent() {
        Result result = run(observation("a", "1", true, Phenotype.UNKNOWN));
        assertEquals("NOT_EVALUATED", result.status());
        assertEquals("DENOVO_PHENOTYPE_BASIS_REQUIRED", result.events().get(0).reasonCode());
    }

    @Test void inconclusiveAndExcludedParentageCannotDowngradeToAssumed() {
        Observation o = observation("a", "1", true, Phenotype.HIGHLY_SPECIFIC); Facts f = o.facts();
        for (Parentage p : List.of(Parentage.INCONCLUSIVE, Parentage.EXCLUDED)) {
            Result result = run(withFacts(o, new Facts(GROUP, f.proband(), f.mother(), f.father(), p, f.paternity(), f.phenotype(), f.familyHistory())));
            assertEquals("NOT_EVALUATED", result.status());
            assertEquals("DENOVO_PARENTAGE_" + p.name(), result.events().get(0).reasonCode());
        }
    }

    @Test void assumedUnstatedAndUntestedStayDistinctAndRequireExplicitPolicy() {
        Observation o = observation("a", "1", false, Phenotype.CONSISTENT); Facts f = o.facts();
        for (Parentage p : List.of(Parentage.ASSUMED_NOT_TESTED, Parentage.ASSUMED_NOT_REPORTED)) {
            Observation current = withFacts(o, new Facts(GROUP, f.proband(), f.mother(), f.father(), p, p, f.phenotype(), f.familyHistory()));
            assertEquals("PM6", run(current).code());
            assertEquals("NOT_EVALUATED", calculator.evaluate(GROUP, new Policy("strict-unresolved", false, false), List.of(current)).status());
        }
    }

    @Test void unresolvedExpertPolicyDoesNotFallBackToGeneral() {
        Result result = calculator.evaluate(GROUP, new Policy("unimplemented-expert", true, true),
                List.of(observation("a", "1", true, Phenotype.HIGHLY_SPECIFIC)));
        assertEquals("NOT_EVALUATED", result.status()); assertEquals("DENOVO_EXPERT_POLICY_UNRESOLVED", result.reasonCode());
    }

    @Test void siblingMosaicAndFamilyUnknownDoNotGetCounted() {
        Observation o = observation("a", "1", true, Phenotype.HIGHLY_SPECIFIC); Facts f = o.facts();
        for (FamilyHistory history : List.of(FamilyHistory.UNKNOWN, FamilyHistory.CONFLICT, FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE)) {
            Result result = run(withFacts(o, new Facts(GROUP, f.proband(), f.mother(), f.father(), f.maternity(), f.paternity(), f.phenotype(), history)));
            assertEquals("NOT_EVALUATED", result.status()); assertEquals("NONE", result.code());
        }
    }

    @Test void possibleOverlapCannotIncreaseScoreButIndependentValidCaseSurvives() {
        Observation o = observation("b", "2", true, Phenotype.HIGHLY_SPECIFIC);
        Observation overlap = new Observation(o.observationId(), o.eventId(), o.sourceKind(), o.facts(), Independence.POSSIBLE_OVERLAP, o.sourceRefs(), List.of());
        Result result = run(observation("a", "1", false, Phenotype.CONSISTENT), overlap);
        assertEquals("PM6", result.code()); assertEquals(2, result.effectiveQuarterPoints()); assertTrue(result.hasExcludedObservations());
    }

    @Test void documentProblemsRemainVisibleWithoutManufacturingZeroEvidence() {
        Observation o = observation("a", "1", true, Phenotype.HIGHLY_SPECIFIC);
        for (Observation bad : List.of(new Observation(o.observationId(), o.eventId(), o.sourceKind(), o.facts(), o.independence(), List.of(), List.of()),
                new Observation(o.observationId(), o.eventId(), o.sourceKind(), o.facts(), o.independence(), o.sourceRefs(), List.of("QUOTE_DOES_NOT_SUPPORT_PATERNAL_TEST")))) {
            Result result = run(bad); assertEquals("NOT_EVALUATED", result.status()); assertEquals("EXCLUDED", result.events().get(0).disposition());
            assertEquals(bad.qualificationIssues(), result.events().get(0).details());
        }
    }

    @Test void differentDiseaseOrAlleleCannotBeSummed() {
        Observation o = observation("b", "2", true, Phenotype.HIGHLY_SPECIFIC); Facts f = o.facts();
        for (Group other : List.of(new Group(GROUP.alleleKey(), GROUP.hgncId(), "MONDO:OTHER", "AD"),
                new Group("GRCh38:1:101:A:G", GROUP.hgncId(), GROUP.diseaseId(), "AD"))) {
            Observation wrong = withFacts(o, new Facts(other, f.proband(), f.mother(), f.father(), f.maternity(), f.paternity(), f.phenotype(), f.familyHistory()));
            Result result = run(observation("a", "1", true, Phenotype.HIGHLY_SPECIFIC), wrong);
            assertEquals("DENOVO_GROUP_IDENTITY_CONFLICT", result.reasonCode()); assertEquals("NOT_EVALUATED", result.status());
        }
    }

    @Test void duplicateObservationIdIsNotTreatedAsIndependent() {
        assertEquals("DENOVO_OBSERVATION_ID_COLLISION", run(observation("a", "1", true, Phenotype.CONSISTENT),
                observation("a", "2", true, Phenotype.CONSISTENT)).reasonCode());
    }

    @Test void inputOrderDoesNotChangeResultOrCap() {
        List<Observation> cases = new ArrayList<>(List.of(observation("b", "2", false, Phenotype.HIGHLY_SPECIFIC),
                observation("a", "1", true, Phenotype.CONSISTENT_HIGH_HETEROGENEITY), observation("c", "3", false, Phenotype.CONSISTENT)));
        Result expected = calculator.evaluate(GROUP, POLICY, cases);
        for (int n = 0; n < cases.size(); n++) { Collections.rotate(cases, 1); assertEquals(expected, calculator.evaluate(GROUP, POLICY, cases)); }
    }

    @Test void noRecordsIsNotNotMetAndUnsupportedInheritanceStaysExplicit() {
        assertEquals("NOT_EVALUATED", run().status()); assertEquals("DENOVO_NO_OBSERVATIONS", run().reasonCode());
        assertEquals("DENOVO_SPECIAL_POLICY_REQUIRED", calculator.evaluate(new Group(GROUP.alleleKey(), GROUP.hgncId(), GROUP.diseaseId(), "AR"), POLICY, List.of()).reasonCode());
        assertEquals("DENOVO_INHERITANCE_NOT_SUPPORTED", calculator.evaluate(new Group(GROUP.alleleKey(), GROUP.hgncId(), GROUP.diseaseId(), "OTHER"), POLICY, List.of()).reasonCode());
    }

    @Test void collectionsAreImmutableAndInputIsBounded() {
        Observation o = observation("a", "1", true, Phenotype.CONSISTENT);
        assertThrows(UnsupportedOperationException.class, () -> o.sourceRefs().add("extra"));
        assertThrows(UnsupportedOperationException.class, () -> run(o).events().clear());
        assertThrows(IllegalArgumentException.class, () -> calculator.evaluate(GROUP, POLICY, Collections.nCopies(1001, o)));
    }

    private Result run(Observation... observations) { return calculator.evaluate(GROUP, POLICY, List.of(observations)); }
    private static Observation observation(String id, String event, boolean confirmed, Phenotype phenotype) {
        Parentage parentage = confirmed ? Parentage.CONFIRMED : Parentage.ASSUMED_NOT_TESTED;
        Facts facts = new Facts(GROUP, TargetTest.PRESENT, TargetTest.ABSENT_QUALIFIED, TargetTest.ABSENT_QUALIFIED,
                parentage, parentage, phenotype, FamilyHistory.COMPATIBLE);
        return new Observation(id, event, SourceKind.CURRENT_CASE, facts, Independence.ESTABLISHED, List.of("SYNTHETIC-report#" + id), List.of());
    }
    private static Observation withFacts(Observation o, Facts facts) {
        return new Observation(o.observationId(), o.eventId(), o.sourceKind(), facts, o.independence(), o.sourceRefs(), o.qualificationIssues());
    }
}
