package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit synthetic target-allele AC eligibility, not a reading of GN149 observation semantics.
 * Uses actual policy qualification and statistic algorithms, with synthetic source-field controls.
 * Supplied FAF95 controls are not claimed to have been recomputed from AC/AN. */
class Bs1SexChromosomeEligibilityTest {
    private static final String LOCATOR = "SYNTHETIC: explicit target alternate allele AC eligibility";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Bs1SexChromosomeStatisticTest old = new Bs1SexChromosomeStatisticTest();

    private ObjectNode record(boolean af, boolean joint, String operator, String minimum) {
        var record = joint ? new Bs1JointSexChromosomeRuntimeTest().record(af)
                : new Bs1SexChromosomeRuntimeTest().record("GT");
        var statistic = record.withObject("/expertPolicy/statistic");
        statistic.put("metricId", joint ? (af ? Bs1SexChromosomePolicy.JOINT_AF : Bs1SexChromosomePolicy.JOINT_FAF95)
                : (af ? Bs1SexChromosomePolicy.AF : Bs1SexChromosomePolicy.FAF95));
        statistic.put("confidence", af ? "POINT_ESTIMATE" : "95_PERCENT_LOWER_BOUND")
                .put("sourceTerm", af ? "AF" : "FAF95").put("interpretation", af ? "TARGET_ALT_AF" : "TARGET_ALT_FAF95");
        statistic.putArray("ancestries").add("afr").add("nfe");
        statistic.putObject("eligibleAc").put("operator", operator).put("decimal", minimum).put("unit", "alleles")
                .put("sourceTerm", "AC").put("sourceId", record.at("/sources/0/sourceId").asText()).put("locator", LOCATOR);
        record.withObject("/expertPolicy/tiers/0/conditions/0").put("fact", af ? "AF" : "FAF95");
        qualify(record, joint, true); return record;
    }

    private void qualify(ObjectNode record, boolean joint, boolean bindEligible) {
        if (joint) new Bs1JointSexChromosomeRuntimeTest().qualify(record);
        else Bs1SexChromosomeStatisticTest.qualify(record);
        if (bindEligible) record.withArray("/qualification/sourceBindings").addObject()
                .put("sourceId", record.at("/sources/0/sourceId").asText()).put("locator", LOCATOR)
                .put("contentSha256", "a".repeat(64));
        new Bs1KnowledgeCatalogTest().review(record);
        if (joint) record.withObject("/review").put("dataScopePolicyId", Bs1SexChromosomePolicy.JOINT_SCOPE);
    }

    private ObjectNode policy(boolean af, boolean joint, String operator, String minimum) {
        var policy = Bs1KnowledgeCatalog.policy(record(af, joint, operator, minimum));
        assertNull(Bs1Policy.issue(policy), policy.toPrettyString()); return policy;
    }

    private ObjectNode derived(boolean joint) {
        var d = old.derived();
        if (joint) d.put("schemaVersion", GnomadSexStratifiedObservation.JOINT_VERSION).put("dataset", "JOINT");
        return d;
    }

    private ObjectNode populationPair(boolean joint) {
        var d = derived(joint);
        old.row(d, "nfe", "XY", "1", "2000", "0.0005", "0.000005");
        old.row(d, "afr", "XY", "2", "100000", "0.00002", "0.000003");
        return d;
    }

    private ObjectNode select(JsonNode data, JsonNode policy) { return Bs1SexChromosomeStatistic.selectVerified(mapper, data, policy); }
    private String reason(JsonNode result) { return result.at("/issues/0/reasonCode").asText(); }

    @ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void countEligibilityExcludesAHighFrequencyPopulationBeforeChoosingMaximum(boolean af, boolean joint) throws Exception {
        var p = policy(af, joint, "GT", "1"); var data = populationPair(joint); var before = data.deepCopy();
        var r = select(data, p);
        assertEquals("READY", r.path("status").asText(), r.toPrettyString());
        assertEquals("afr", r.at("/facts/ancestry").asText()); assertEquals("2", r.at("/facts/AC").asText());
        assertEquals("100000", r.at("/facts/AN").asText());
        assertEquals("EXCLUDED", r.at("/frequencySelection/populations/1/status").asText());
        assertEquals("BS1_SEX_AC_INSUFFICIENT", r.at("/frequencySelection/populations/1/reasonCode").asText());
        assertEquals(joint ? Bs1SexChromosomeStatistic.JOINT_AC_ELIGIBILITY_VERSION : Bs1SexChromosomeStatistic.AC_ELIGIBILITY_VERSION,
                r.path("adapterVersion").asText());
        assertEquals(before, data);
        String output = System.getProperty("varvis.t03.bs1.eligibility.output");
        if (output != null && !output.isBlank()) {
            var folder = Path.of(output); Files.createDirectories(folder);
            var sample = mapper.createObjectNode().put("dataClass", "SYNTHETIC")
                    .put("layer", "ACTUAL_POLICY_QUALIFICATION_AND_VERIFIED_STATISTIC_CORE_NOT_CURRENT_OR_CLINICAL_ADOPTION")
                    .put("thresholdSourceMeaning", "EXPLICIT_TARGET_ALLELE_AC_CONTROL_NOT_GN149_OBSERVATION_INTERPRETATION");
            sample.set("policy", p); sample.set("derivedSourceFields", data); sample.set("qualityDecision", r);
            Files.write(folder.resolve((joint ? "joint" : "exomes") + "-" + (af ? "af" : "faf95") + ".json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample));
        }
    }

    @ParameterizedTest @CsvSource({"GT,1,afr", "GT,0,nfe", "GTE,1,nfe", "GTE,2,afr"})
    void exactSourceOperatorControlsMinimumCountBoundary(String operator, String minimum, String expected) {
        var p = policy(false, false, operator, minimum);
        assertEquals(expected, select(populationPair(false), p).at("/facts/ancestry").asText());
    }

    @Test void knownInsufficientCountDoesNotRequireUnusedFrequencyButUnknownCountCannotBeExcluded() {
        var p = policy(false, true, "GT", "1"); var d = populationPair(true);
        var low = (ObjectNode) d.path("strata").path(0);
        low.withObject("/measurements").remove(List.of("AF", "faf95")); d.put("status", "INCOMPLETE");
        assertEquals("READY", select(d, p).path("status").asText());
        low.withObject("/measurements").remove("AC");
        var missing = select(d, p); assertEquals("BLOCKED", missing.path("status").asText());
        assertEquals("BS1_SEX_AC_INVALID_OR_MISSING", reason(missing));
    }

    @Test void allKnownLowCountsRemainUnevaluableAndNeverBecomeZeroFrequency() {
        var p = policy(false, false, "GT", "2"); var r = select(populationPair(false), p);
        assertEquals("BLOCKED", r.path("status").asText());
        assertEquals("BS1_SEX_NO_ELIGIBLE_POPULATION", reason(r)); assertTrue(r.path("facts").isEmpty());
        for (var row : r.at("/frequencySelection/populations")) assertEquals("EXCLUDED", row.path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"NEGATIVE", "FRACTIONAL", "OVER_AN", "MISSING", "UNIT"})
    void invalidOrUnknownCountCannotPassAsAnInsufficientKnownPopulation(String fault) {
        var p = policy(true, false, "GT", "1"); var d = populationPair(false);
        var measures = ((ObjectNode) d.path("strata").path(0)).withObject("/measurements");
        switch (fault) {
            case "NEGATIVE" -> measures.withObject("AC").put("rawValue", "-1");
            case "FRACTIONAL" -> measures.withObject("AC").put("rawValue", "0.5");
            case "OVER_AN" -> measures.withObject("AC").put("rawValue", "2001");
            case "MISSING" -> measures.remove("AC");
            case "UNIT" -> measures.withObject("AC").put("unit", "people");
            default -> fail("unknown fault");
        }
        var r = select(d, p); assertEquals("BLOCKED", r.path("status").asText());
        assertEquals("BS1_SEX_AC_INVALID_OR_MISSING", reason(r));
        assertEquals("UNRESOLVED", r.at("/frequencySelection/populations/1/status").asText());
    }

    @Test void recordFilterAndEligiblePopulationQualityConflictsRemainBlockers() {
        var p = policy(true, true, "GT", "1"); var d = populationPair(true);
        d.put("recordFilter", "LowQual");
        assertEquals("BS1_JOINT_FILTER_NOT_PASS", reason(select(d, p)));
        d.put("recordFilter", "PASS"); ((ObjectNode) d.path("strata").path(1)).withObject("/measurements/AF").put("rawValue", "0.1");
        assertEquals("BS1_SEX_COUNT_AF_CONFLICT", reason(select(d, p)));
    }

    @ParameterizedTest @ValueSource(strings = {"OPERATOR", "NEGATIVE", "FRACTION", "UNIT", "TERM", "UNKNOWN_KEY", "SOURCE_TYPE", "LOCATOR_TYPE", "SOURCE", "LOCATOR", "MISSING_BINDING"})
    void eligibilityIsStrictAndSourceBoundRatherThanAnObservationOrPersonCount(String fault) {
        var rec = record(false, false, "GT", "1"); var qualifier = rec.withObject("/expertPolicy/statistic/eligibleAc");
        switch (fault) {
            case "OPERATOR" -> qualifier.put("operator", "LT");
            case "NEGATIVE" -> qualifier.put("decimal", "-1");
            case "FRACTION" -> qualifier.put("decimal", "1.5");
            case "UNIT" -> qualifier.put("unit", "people");
            case "TERM" -> qualifier.put("sourceTerm", "nhomalt");
            case "UNKNOWN_KEY" -> qualifier.put("inferredFromPatientSex", true);
            case "SOURCE_TYPE" -> qualifier.put("sourceId", 123);
            case "LOCATOR_TYPE" -> qualifier.put("locator", 123);
            case "SOURCE" -> qualifier.put("sourceId", "unknown-source");
            case "LOCATOR" -> qualifier.put("locator", "SYNTHETIC: unbound locator");
            case "MISSING_BINDING" -> { }
            default -> fail("unknown fault");
        }
        qualify(rec, false, !"MISSING_BINDING".equals(fault));
        var p = Bs1KnowledgeCatalog.policy(rec);
        assertEquals(List.of("SOURCE", "LOCATOR", "MISSING_BINDING").contains(fault)
                ? "BS1_SEX_AC_REQUIREMENT_SOURCE_UNBOUND" : "BS1_SEX_AC_REQUIREMENT_INVALID", Bs1Policy.issue(p));
    }

    @Test void absentNewQualifierPreservesOldMaximumAndMethodVersions() {
        for (boolean joint : new boolean[]{false, true}) {
            var rec = record(false, joint, "GT", "1"); rec.withObject("/expertPolicy/statistic").remove("eligibleAc");
            qualify(rec, joint, false); var p = Bs1KnowledgeCatalog.policy(rec); assertNull(Bs1Policy.issue(p));
            var r = select(populationPair(joint), p);
            assertEquals("nfe", r.at("/facts/ancestry").asText());
            assertEquals(joint ? Bs1SexChromosomeStatistic.JOINT_VERSION : Bs1SexChromosomeStatistic.VERSION, r.path("adapterVersion").asText());
        }
    }
}
