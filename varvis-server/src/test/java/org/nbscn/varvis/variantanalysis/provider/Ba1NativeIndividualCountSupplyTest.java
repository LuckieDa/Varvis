package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic exact RAW/transport counterexamples using the real pinned profile, header,
 * source supply and parser. They do not turn synthetic rows into actual public observations,
 * adopt GN027, establish clinical health or demonstrate normal business/persistence.
 */
class Ba1NativeIndividualCountSupplyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
    private final PopulationFrequencyRuntimeTest facts = new PopulationFrequencyRuntimeTest();
    private record Input(ObjectNode carrier, String raw, String alleleId) { }

    private Input input(String homo, boolean extensions) throws Exception {
        Map<String, String> info = facts.jointInfo("0.001", "10000", "0.1");
        info.put("nhomalt_joint", homo);
        return input(info, extensions);
    }
    private Input input(Map<String, String> info, boolean extensions) throws Exception {
        var context = facts.diseaseContext("MYH7");
        var observation = facts.observation(context, info, "PASS");
        String raw = observation.path("rawPopulationRecord").asText();
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var carrier = wiring.reparsePopulationObservation(context, observation, "a".repeat(64),
                GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile).carrier();
        if (extensions) wiring.preserveBa1SubtypeFilters(carrier, raw);
        return new Input(carrier, raw, context.at("/variant/alleleId").asText());
    }
    private Ba1NativeIndividualCountSupply.Material read(Input value) {
        return Ba1NativeIndividualCountSupply.read(mapper, profile, value.alleleId(), value.carrier(), value.raw());
    }
    private String sha(String raw) { return org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(raw); }

    @ParameterizedTest @CsvSource({"0,UNKNOWN", "9,UNKNOWN", "10,TRUE", "11,TRUE"})
    void onlyNativeHomozygotePositiveLowerBoundIsIssued(String homo, String expected) throws Exception {
        var input = input(homo, true); byte[] carrier = mapper.writeValueAsBytes(input.carrier());
        var material = read(input);
        assertNull(material.issue(mapper, profile, input.alleleId(), input.carrier(), sha(input.raw())));
        var counts = material.counts(); assertNotNull(counts);
        assertEquals(new BigInteger(homo), counts.homozygotes()); assertNull(counts.hemizygotes());
        assertEquals(Ba1ConditionalFrequencyRule.SumRelationship.UNRESOLVED, counts.relationship());
        assertEquals(Ba1ConditionalFrequencyRule.CountScopeCompleteness.UNVERIFIED_OR_PARTIAL, material.completeness());
        var catalogs = new Ba1ConditionalCatalogTest();
        JsonNode clause = new Ba1SpecificationCatalog(mapper, catalogs.snapshot())
                .select(catalogs.mapping(), facts.diseaseContext("MYH7")).at("/policy/clause");
        var decision = Ba1ConditionalFrequencyRule.evaluate(clause,
                new Ba1ConditionalFrequencyRule.FrequencyFact(new BigDecimal("0.001"), new BigInteger("2000"), "nfe"),
                counts, null, material.completeness());
        assertEquals(expected, decision.countTruth().name());
        assertNotEquals(Ba1ConditionalFrequencyRule.Truth.FALSE, decision.countTruth());
        var details = material.publicDetails();
        assertEquals("QUALIFIED_LOWER_BOUND", details.path("status").asText());
        assertEquals("ALL", details.at("/binding/ancestry").asText());
        assertEquals("ALL", details.at("/binding/sex").asText());
        assertEquals("ADJ", details.at("/binding/genotypeQualityScope").asText());
        assertEquals("UNVERIFIED_OR_PARTIAL", details.path("countScopeCompleteness").asText());
        assertFalse(details.path("materialId").asText().isBlank());
        assertArrayEquals(carrier, mapper.writeValueAsBytes(input.carrier()));
        details.put("status", "TAMPERED"); details.withObject("/binding").put("ancestry", "nfe");
        assertEquals("QUALIFIED_LOWER_BOUND", material.publicDetails().path("status").asText());
        assertEquals("ALL", material.publicDetails().at("/binding/ancestry").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void baseAndExactSubtypeExtensionsHaveTheSameCountMeaning(boolean extensions) throws Exception {
        var value = input("10", extensions); var material = read(value);
        assertNotNull(material.counts()); assertNull(material.issue(mapper, profile, value.alleleId(), value.carrier(), sha(value.raw())));
        var counts = material.counts(); assertNotNull(counts); assertEquals(new BigInteger("10"), counts.homozygotes());
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_HOMO", "ONLY_RAW_HOMO", "ONLY_ANCESTRY_HOMO"})
    void missingAllAdjHomozygotesIsNotZeroOrAnotherCohort(String fault) throws Exception {
        var info = facts.jointInfo("0.001", "10000", "0.1"); info.remove("nhomalt_joint");
        if ("ONLY_RAW_HOMO".equals(fault)) info.put("nhomalt_joint_raw", "1000");
        if ("ONLY_ANCESTRY_HOMO".equals(fault)) info.put("nhomalt_joint_nfe", "10");
        var value = input(info, true); var material = read(value);
        assertNull(material.counts()); assertEquals("BA1_NATIVE_COUNT_HOMOZYGOTES_MISSING", material.reasonCode());
        assertNull(material.issue(mapper, profile, value.alleleId(), value.carrier(), sha(value.raw())));
    }

    @ParameterizedTest @ValueSource(strings = {"UNIT", "CALIBRATION", "DUPLICATE", "DUPLICATE_DIFFERENT_VALUE", "ONE_EXTENSION", "FAKE_EXTENSION", "EXTRA_EXTENSION", "SOURCE_RELEASE", "SOURCE_ID", "SOURCE_FINGERPRINT", "SOURCE_TIME", "DUPLICATE_RECORD", "TARGET_ALT", "TARGET_POSITION", "RAW_DUPLICATE_INFO", "RAW_TWO_RECORDS"})
    void rawSourceScopeAndCardinalityCannotBeSelfDeclared(String fault) throws Exception {
        var value = input("10", true); var carrier = value.carrier().deepCopy(); String raw = value.raw();
        JsonNode homo = null;
        for (JsonNode measurement : carrier.path("measurements"))
            if ("gnomad.joint.nhomalt".equals(measurement.path("metricId").asText())) homo = measurement;
        assertNotNull(homo);
        switch (fault) {
            case "UNIT" -> ((ObjectNode) homo).put("unit", "alleles");
            case "CALIBRATION" -> ((ObjectNode) homo).put("calibrationProfileId", "wrong");
            case "DUPLICATE" -> ((ArrayNode) carrier.path("measurements")).add(homo.deepCopy());
            case "DUPLICATE_DIFFERENT_VALUE" -> ((ArrayNode) carrier.path("measurements")).add(((ObjectNode) homo.deepCopy()).put("rawValue", "11"));
            case "ONE_EXTENSION" -> ((ArrayNode) carrier.path("measurements")).remove(carrier.path("measurements").size() - 1);
            case "FAKE_EXTENSION" -> ((ObjectNode) carrier.path("measurements").get(carrier.path("measurements").size() - 1)).put("rawValue", "PASS").put("normalizedValue", "PASS");
            case "EXTRA_EXTENSION" -> ((ArrayNode) carrier.path("measurements")).addObject().put("metricId", "qualifiedCount").put("rawValue", "10");
            case "SOURCE_RELEASE" -> carrier.withObject("/source").put("resourceVersion", "gnomAD joint v4.1.1 GRCh38");
            case "SOURCE_ID" -> carrier.withObject("/source").put("sourceId", "EXOMES");
            case "SOURCE_FINGERPRINT" -> carrier.withObject("/source").put("resourceSha256", "0".repeat(64));
            case "SOURCE_TIME" -> carrier.put("producedAt", "2026-11-04T00:00:00Z");
            case "DUPLICATE_RECORD" -> { ((ArrayNode) carrier.at("/source/sourceRecords")).add(carrier.at("/source/sourceRecords/0").deepCopy()); carrier.withObject("/queryOutcome").put("recordCount", 2); }
            case "TARGET_ALT" -> raw = raw.replace("\tG\tA\t", "\tG\tT\t");
            case "TARGET_POSITION" -> raw = raw.replace("\t23400000\t", "\t23400001\t");
            case "RAW_DUPLICATE_INFO" -> raw += ";nhomalt_joint=10";
            case "RAW_TWO_RECORDS" -> raw += "\n" + raw;
            default -> throw new AssertionError(fault);
        }
        var material = Ba1NativeIndividualCountSupply.read(mapper, profile, value.alleleId(), carrier, raw);
        assertNull(material.counts(), fault); assertNotNull(material.issue(mapper, profile, value.alleleId(), carrier, sha(raw)), fault);
    }

    @ParameterizedTest @CsvSource({"10,19,10000", "10,100,19", "10,100,0", "0,101,100"})
    void inconsistentAlleleAndDiploidCountsCannotMintIndividuals(String hom, String ac, String an) throws Exception {
        var info = facts.jointInfo("0.001", "10000", "0.1"); info.put("nhomalt_joint", hom);
        info.put("AC_joint", ac); info.put("AN_joint", an);
        var value = input(info, true); var material = read(value);
        assertNull(material.counts()); assertEquals("BA1_NATIVE_COUNT_AC_AN_HOMOZYGOTE_INCONSISTENT", material.reasonCode());
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "1.0", "1e309", "999999999999999999999999999999999999999999999999999999999999999999999"})
    void invalidOrExcessiveIntegerLexemesNeverBecomePeople(String homo) throws Exception {
        var material = read(input(homo, true)); assertNull(material.counts());
    }

    @Test void bindingCannotBeReusedForDifferentRawContextProfileCarrierOrTimestamp() throws Exception {
        var value = input("10", true); var material = read(value);
        assertNotNull(material.issue(mapper, profile, value.alleleId(), value.carrier(), "a".repeat(64)));
        assertNotNull(material.issue(mapper, profile, "allele-sha256-" + "a".repeat(64), value.carrier(), sha(value.raw())));
        assertNotNull(material.issue(mapper, GnomadPopulationInputProfile.loadLocalBa1(), value.alleleId(), value.carrier(), sha(value.raw())));
        var modified = value.carrier().deepCopy(); modified.put("producedAt", "2026-11-04T00:00:00Z");
        assertNotNull(material.issue(mapper, profile, value.alleleId(), modified, sha(value.raw())));
        modified = value.carrier().deepCopy(); modified.put("sourcePayloadSha256", "a".repeat(64));
        assertNotNull(material.issue(mapper, profile, value.alleleId(), modified, sha(value.raw())));
        value.carrier().withObject("/queryAllele").put("position", 23400001);
        assertNotNull(material.issue(mapper, profile, value.alleleId(), value.carrier(), sha(value.raw())));
        var counts = material.counts(); assertNotNull(counts);
        assertEquals(new BigInteger("10"), counts.homozygotes(), "Immutable issuance has no alias to caller JSON");
    }

    @Test void countSourceDoesNotBorrowTheFrequencyArmsAnOrMissingFafRequirement() throws Exception {
        var info = facts.jointInfo("0.001", "1999", "0.1"); info.put("nhomalt_joint", "10");
        info.remove("fafmax_faf95_max_joint");
        var material = read(input(info, true));
        var counts = material.counts(); assertNotNull(counts); assertEquals(new BigInteger("10"), counts.homozygotes());
        assertEquals("UNVERIFIED_OR_PARTIAL", material.publicDetails().path("countScopeCompleteness").asText());
    }

    @Test void noRecordNeverProvidesZeroPeopleOrACompleteNegative() throws Exception {
        var value = input("10", false); var carrier = value.carrier().deepCopy();
        carrier.putArray("measurements"); carrier.withObject("/source").putArray("sourceRecords");
        carrier.withObject("/queryOutcome").put("status", "NO_RECORD").put("recordCount", 0);
        var material = Ba1NativeIndividualCountSupply.read(mapper, profile, value.alleleId(), carrier, "");
        assertNull(material.counts()); assertEquals("BA1_NATIVE_COUNT_NO_RECORD", material.reasonCode());
        assertNull(material.issue(mapper, profile, value.alleleId(), carrier, sha("")));
        assertEquals(Ba1ConditionalFrequencyRule.CountScopeCompleteness.UNVERIFIED_OR_PARTIAL, material.completeness());
    }

    @Test void noPublicJsonConstructorOrConfigurableClinicalQualifierExists() throws Exception {
        assertFalse(Modifier.isPublic(Ba1NativeIndividualCountSupply.class.getModifiers()));
        for (var constructor : Ba1NativeIndividualCountSupply.Material.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for (var method : Ba1NativeIndividualCountSupply.class.getDeclaredMethods())
            assertFalse(Modifier.isPublic(method.getModifiers()));
        var value = input("10", false);
        var unsupported = Ba1NativeIndividualCountSupply.read(mapper, GnomadPopulationInputProfile.loadLocalBa1(), value.alleleId(), value.carrier(), value.raw());
        assertNull(unsupported.counts()); assertEquals("BA1_NATIVE_COUNT_PROFILE_UNSUPPORTED", unsupported.reasonCode());
        var identity = Ba1NativeIndividualCountSupply.identity(mapper);
        assertEquals(7, identity.path("sources").size()); assertFalse(identity.path("diseasePolicyApprovalGranted").asBoolean());
        assertTrue(new String(GnomadSexStratifiedObservation.loadPinnedJointHeader(), StandardCharsets.UTF_8)
                .contains("ID=nhomalt_joint,Number=A,Type=Integer,Description=\"Count of homozygous individuals in joint dataset\""));
    }
}
