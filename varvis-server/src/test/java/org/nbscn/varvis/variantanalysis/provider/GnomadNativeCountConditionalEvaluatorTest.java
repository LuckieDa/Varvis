package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Actual source-bound count producer and public consumer with controlled native
 * record bytes and a fictional admitted policy. This never adopts all of GN027. */
class GnomadNativeCountConditionalEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final PopulationFrequencyRuntimeTest facts = new PopulationFrequencyRuntimeTest();
    private final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
    private final Ba1ConditionalCatalogTest catalogs = new Ba1ConditionalCatalogTest();

    private record NativeInput(String alleleId, ObjectNode carrier, String raw,
                               Ba1NativeIndividualCountSupply.Material material) { }

    private NativeInput input(String homo, String faf, String an, String fault) throws Exception {
        Map<String, String> values = facts.jointInfo(faf, an, "0.1");
        String af = new BigDecimal("100").divide(new BigDecimal(an), MathContext.DECIMAL64).toPlainString();
        values.put("AF_grpmax_joint", af); values.put("AF_joint_nfe", af);
        if (homo == null) values.remove("nhomalt_joint"); else {
            values.put("nhomalt_joint", homo); values.put("nhomalt_joint_nfe", homo); values.put("nhomalt_grpmax_joint", homo);
        }
        String filter = "PASS";
        switch (fault) {
            case "NONE" -> { }
            case "MISSING_FAF" -> values.remove("fafmax_faf95_max_joint");
            case "FILTER" -> filter = "EXOMES_FILTERED";
            case "SUBTYPE_FILTER" -> values.put("genomes_filters", "AC0");
            case "DISCORDANT" -> values.put("stat_union_p_value", "0.00001");
            case "INCOMPLETE_AN" -> values.remove("AN_joint");
            case "INVALID_HOMO" -> values.put("nhomalt_joint", "51");
            case "AF_COUNT" -> values.put("AF_joint_nfe", "0.5");
            case "FAF_GROUP" -> values.put("faf95_joint_nfe", "0.00001");
            case "FAF_ABOVE_AF" -> {
                values.put("faf95_joint_nfe", "0.02"); values.put("faf95_joint", "0.02");
                values.put("fafmax_faf95_max_joint", "0.02");
            }
            default -> throw new AssertionError(fault);
        }
        var context = facts.diseaseContext("MYH7");
        var observation = facts.observation(context, values, filter);
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var carrier = wiring.reparsePopulationObservation(context, observation, "a".repeat(64),
                GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile).carrier();
        String raw = observation.path("rawPopulationRecord").asText();
        wiring.preserveBa1SubtypeFilters(carrier, raw);
        String allele = context.at("/variant/alleleId").asText();
        return new NativeInput(allele, carrier, raw,
                Ba1NativeIndividualCountSupply.read(mapper, profile, allele, carrier, raw));
    }

    private ObjectNode selection() throws Exception {
        return new Ba1SpecificationCatalog(mapper, catalogs.snapshot()).select(catalogs.mapping(), facts.diseaseContext("MYH7"));
    }

    private ObjectNode evaluate(NativeInput input) throws Exception {
        return new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(input.alleleId(), input.carrier(), profile,
                selection(), null, sha(input.raw()), input.material());
    }

    @ParameterizedTest @CsvSource({"0,NOT_EVALUATED,UNKNOWN", "9,NOT_EVALUATED,UNKNOWN", "10,MET,TRUE", "11,MET,TRUE"})
    void sourceQualifiedHomozygotesProveOnlyAPositiveLowerBound(String homo, String expected, String countTruth) throws Exception {
        var result = evaluate(input(homo, "0.001", "10000", "NONE"));
        assertEquals(expected, result.at("/evaluationResult/status").asText(), result.toPrettyString());
        var details = result.path("ruleConditionEvaluation");
        assertEquals("FALSE", details.path("frequencyTruth").asText());
        assertEquals(countTruth, details.path("countTruth").asText());
        assertEquals("UNVERIFIED_OR_PARTIAL", details.path("countScopeCompleteness").asText());
        assertEquals("QUALIFIED_LOWER_BOUND", details.at("/observedHomozygoteStatistic/qualificationStatus").asText());
        assertEquals(homo, details.at("/observedHomozygoteStatistic/rawValue").asText());
        assertFalse(details.at("/countSourceQualification/materialId").asText().isBlank());
        assertFalse(details.at("/countSourceQualification/missing").isEmpty());
        assertEquals("MET".equals(expected) ? "STANDALONE" : "NONE", result.path("appliedStrength").asText());
    }

    @ParameterizedTest @CsvSource({"NONE,1999", "MISSING_FAF,10000"})
    void qualifiedCountArmDoesNotRequireFrequencyArmCompletion(String fault, String an) throws Exception {
        var result = evaluate(input("10", "0.001", an, fault));
        assertEquals("MET", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/frequencyTruth").asText());
        assertEquals("TRUE", result.at("/ruleConditionEvaluation/countTruth").asText());
    }

    @Test void absentNativeCountIsNotAQualifiedZero() throws Exception {
        var result = evaluate(input(null, "0.001", "10000", "NONE"));
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/countTruth").asText());
        assertFalse(result.at("/ruleConditionEvaluation/observedHomozygoteStatistic").has("rawValue"));
    }

    @ParameterizedTest @ValueSource(strings = {"FILTER", "SUBTYPE_FILTER", "DISCORDANT", "INCOMPLETE_AN", "INVALID_HOMO",
            "AF_COUNT", "FAF_GROUP", "FAF_ABOVE_AF"})
    void nativeCountDoesNotWaiveSharedQualityOrCountDenominatorChecks(String fault) throws Exception {
        var result = evaluate(input("10", "0.003", "10000", fault));
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("NONE", result.path("appliedStrength").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"RAW_SHA", "CARRIER", "TIME", "ALLELE"})
    void materialCannotBeReusedForAnUnboundQueryEvenWithTrueFrequency(String fault) throws Exception {
        var nativeInput = input("10", "0.003", "10000", "NONE");
        var carrier = nativeInput.carrier().deepCopy();
        String allele = nativeInput.alleleId(), rawSha = sha(nativeInput.raw());
        switch (fault) {
            case "RAW_SHA" -> rawSha = "d".repeat(64);
            case "CARRIER" -> carrier.put("sourcePayloadSha256", "e".repeat(64));
            case "TIME" -> carrier.put("producedAt", "2026-09-03T00:00:00Z");
            case "ALLELE" -> allele = "allele-sha256-" + "f".repeat(64);
            default -> throw new AssertionError(fault);
        }
        var result = new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(allele, carrier, profile,
                selection(), null, rawSha, nativeInput.material());
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("NONE", result.path("appliedStrength").asText());
        assertEquals("UNKNOWN", result.at("/ruleConditionEvaluation/countTruth").asText());
    }

    @Test void sourceMaterialNeitherGrantsPublicationAdoptionNorRestoresAuthorityFromPublicJson() throws Exception {
        var nativeInput = input("10", "0.001", "10000", "NONE");
        var publicCopy = nativeInput.material().publicDetails(); publicCopy.put("status", "FORGED");
        assertNotEquals("FORGED", nativeInput.material().publicDetails().path("status").asText());
        assertEquals("MET", evaluate(nativeInput).at("/evaluationResult/status").asText());
        var blocked = selection(); blocked.put("status", "BLOCKED").put("reasonCode", "BA1_GLOBAL_PUBLICATION_TEXT_UNSUPPORTED");
        var result = new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(nativeInput.alleleId(), nativeInput.carrier(), profile,
                blocked, null, sha(nativeInput.raw()), nativeInput.material());
        assertEquals("BA1_GLOBAL_PUBLICATION_TEXT_UNSUPPORTED", result.at("/evaluationResult/reasonCode").asText());
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
        assertFalse(result.has("ruleConditionEvaluation"));
    }

    @Test @EnabledIfSystemProperty(named = "varvis.t03.native.real.raw", matches = ".+")
    void retainedOriginalCountsUseNativeSupplyWithExplicitlySyntheticQueryEnvelope() throws Exception {
        Path path = Path.of(System.getProperty("varvis.t03.native.real.raw"));
        byte[] bytes = Files.readAllBytes(path);
        String retained = new String(bytes, StandardCharsets.UTF_8);
        assertEquals("74bd94f674f76ab931d187e46db06e41c5c4003e47d3cf83f96fa6d9f9441e76", sha(retained));
        assertTrue(retained.endsWith("\n")); String raw = retained.substring(0, retained.length() - 1);
        assertFalse(raw.contains("\n")); assertFalse(raw.contains("\r"));
        var columns = raw.split("\t", -1);
        assertEquals("chrX", columns[0]); assertEquals("153691089", columns[1]);
        assertEquals("G", columns[3]); assertEquals("A", columns[4]);
        var info = new LinkedHashMap<String, String>();
        for (String item : columns[7].split(";")) {
            var pair = item.split("=", 2);
            assertNull(info.put(pair[0], pair.length == 2 ? pair[1] : "true"));
        }
        // Source RAW and parser/header/method pins are real. This transport envelope,
        // timestamp and normalizer are explicit test controls, never a fresh query,
        // reference verification or actual disease-policy adoption.
        var seed = input("0", "0.001", "10000", "NONE");
        var carrier = seed.carrier().deepCopy();
        var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "X")
                .put("position", 153691089).put("reference", "G").put("alternate", "A");
        carrier.set("queryAllele", allele);
        var fingerprint = mapper.createObjectNode().put("resourceId", carrier.at("/source/sourceId").asText());
        fingerprint.set("queryAllele", allele); carrier.withObject("/queryOutcome").put("queryFingerprint", adapter.canonicalSha256(fingerprint));
        carrier.set("measurements", mapper.valueToTree(profile.parse(columns[6], info, 0, 1).getMeasurements()));
        new RuntimeEvidenceV2ProviderWiring(mapper, adapter).preserveBa1SubtypeFilters(carrier, raw);
        var normalizer = mapper.createObjectNode();
        var sourceNormalizer = facts.diseaseContext("MYH7").at("/variant/normalization");
        for (String key : java.util.List.of("method", "methodVersion", "referenceSequenceSha256", "refValidation"))
            normalizer.set(key, sourceNormalizer.path(key));
        String alleleId = adapter.canonicalAlleleId(allele, normalizer);
        var material = Ba1NativeIndividualCountSupply.read(mapper, profile, alleleId, carrier, raw);
        assertNull(material.issue(mapper, profile, alleleId, carrier, sha(raw)), material.publicDetails().toPrettyString());
        var counts = material.counts(); assertNotNull(counts, material.publicDetails().toPrettyString());
        assertEquals(java.math.BigInteger.ZERO, counts.homozygotes());
        assertEquals(java.math.BigInteger.ONE, counts.hemizygotes(), "Source-qualified NON_PAR XY lower bound, not a generic AC conversion");
        assertEquals(java.math.BigInteger.valueOf(13), material.upperBound(), "ALL raw AC13, not the smaller adj AC6, bounds the full source callset");
        assertEquals(Ba1ConditionalFrequencyRule.CountScopeCompleteness.UNVERIFIED_OR_PARTIAL, material.completeness());
        assertEquals("QUALIFIED_BOUNDS", material.publicDetails().path("status").asText());
        var report = mapper.createObjectNode().put("layer", "REAL_RETAINED_RAW_SOURCE_DEFINITION_AND_NATIVE_SUPPLY_SYNTHETIC_QUERY_ENVELOPE")
                .put("freshQuery", false).put("normalBusiness", false).put("diseaseAdoption", false)
                .put("syntheticBindings", "query transport envelope, timestamps and normalizer; no new reference authority")
                .put("rawSha256IncludingLF", sha(retained)).put("rawSha256WithoutLF", sha(raw));
        report.set("material", material.publicDetails());
        String output = System.getProperty("varvis.t03.native.real.output");
        if (output != null) Files.write(Path.of(output), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    private static String sha(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
