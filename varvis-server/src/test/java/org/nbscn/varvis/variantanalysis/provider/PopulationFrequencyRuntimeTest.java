package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic developer regressions, not public-allele or business acceptance.
 * Decimal boundaries were fixed before implementation from GN017 v3.1 and GN002 v2.0.
 * Source identities and selection catalogs below are explicitly synthetic test bindings.
 */
class PopulationFrequencyRuntimeTest {
    private static final String BA1 = PopulationFrequencyRuntime.BA1;
    private static final String BS1 = PopulationFrequencyRuntime.BS1;
    private static final String EXECUTION = "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final PopulationFrequencyRuntime runtime = new PopulationFrequencyRuntime(mapper);
    private final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadBusinessPm2Supporting();

    @ParameterizedTest
    @CsvSource({
        "HNF1A,0.0000329999999999999999,NOT_MET,NOT_MET", "HNF1A,0.000033,NOT_MET,MET",
        "HNF1A,0.0001,MET,MET", "MYH7,0.0000999999999999999999,NOT_MET,NOT_MET",
        "MYH7,0.0001,NOT_MET,MET", "MYH7,0.0009999999999999999999,NOT_MET,MET",
        "MYH7,0.001,MET,MET", "MYH7,0.001000000000000000001,MET,MET"
    })
    void sharedAlgorithmUsesIndependentDiseaseDecimalBoundaries(String gene, String frequency,
                                                                String ba1, String bs1) throws Exception {
        var context = diseaseContext(gene);
        var policy = policy(gene);
        var observation = observation(context, jointInfo(frequency, "10000", "0.1"), "PASS");
        var original = observation.deepCopy();
        var first = evaluate(context, observation, policy, BA1);
        var second = evaluate(context, observation, policy, BS1);
        assertEquals(ba1, status(first), first.toString());
        assertEquals(bs1, status(second), second.toString());
        for (var result : List.of(first, second)) {
            assertEquals(0, result.path("providerCalls").asInt());
            assertEquals(1, result.path("evaluatorCalls").asInt());
            assertEquals("1.2.0-disease-policy", result.at("/criterionEvaluations/0/deterministicTrace/evaluatorVersion").asText());
            assertHashes((ObjectNode) result.at("/criterionEvaluations/0"));
        }
        var overlap = runtime.summarizeOverlap(List.of(first, second));
        assertEquals("MET".equals(ba1) || "MET".equals(bs1) ? 1 : 0, overlap.path("populationStrengthCount").asInt());
        if ("MET".equals(ba1)) assertEquals(BA1, overlap.path("preferredPopulationCriterion").asText());
        assertEquals(original, observation);
        assertEquals(first, evaluate(context, observation, policy, BA1));
    }

    @Test void minimumCohortIsRuleSpecificAndNeverANegativeFrequencyResult() throws Exception {
        var context = diseaseContext("HNF1A"); var policy = policy("HNF1A");
        var observation = observation(context, jointInfo("0.0001", "1999", "0.1"), "PASS");
        assertEquals("POPULATION_FREQUENCY_COHORT_SIZE_REVIEW_REQUIRED", reason(evaluate(context, observation, policy, BA1)));
        assertEquals("MET", status(evaluate(context, observation, policy, BS1)));
        context = diseaseContext("MYH7"); policy = policy("MYH7");
        observation = observation(context, jointInfo("0.001", "9999", "0.1"), "PASS");
        assertEquals("POPULATION_FREQUENCY_COHORT_SIZE_REVIEW_REQUIRED", reason(evaluate(context, observation, policy, BS1)));
        ((ObjectNode) policy.at("/officialConditions")).remove("BA1");
        assertEquals("POPULATION_FREQUENCY_THRESHOLD_INVALID", reason(evaluate(context, observation, policy, BA1)));
        assertEquals("POPULATION_FREQUENCY_COHORT_SIZE_REVIEW_REQUIRED", reason(evaluate(context, observation, policy, BS1)));
    }

    @Test void diseaseTranscriptMoiAssemblyAndPolicySourceCannotBeInferred() throws Exception {
        for (String fault : List.of("GENE", "DISEASE", "MOI", "PM2_ONLY", "TRANSCRIPT", "ASSEMBLY", "DRAFT", "UNITS", "SOURCE", "PROFILE", "COHORT", "CRITERION", "PRECISION", "PRECISION_SOURCE")) {
            var context = diseaseContext("MYH7"); var policy = policy("MYH7");
            var observation = observation(context, jointInfo("0.001", "10000", "0.1"), "PASS");
            switch (fault) {
                case "GENE" -> ((ObjectNode) policy.path("scope")).put("hgncId", "HGNC:11621");
                case "DISEASE" -> ((ObjectNode) policy.path("scope")).put("mondoId", "MONDO:0005021");
                case "MOI" -> ((ObjectNode) policy.path("scope")).put("moi", "AUTOSOMAL_RECESSIVE");
                case "PM2_ONLY" -> ((ObjectNode) context.path("transcript")).put("selectionPolicy", "PM2_CONTEXT_ONLY");
                case "TRANSCRIPT" -> ((ObjectNode) policy.path("scope")).putArray("transcripts").addObject().put("accession", "NM_000257").put("version", 99);
                case "ASSEMBLY" -> ((ObjectNode) policy.path("scope")).put("assembly", "GRCh37");
                case "DRAFT" -> ((ObjectNode) policy.path("review")).put("status", "DRAFT");
                case "UNITS" -> ((ObjectNode) policy.path("officialConditions")).put("unit", "percent");
                case "SOURCE" -> ((ObjectNode) policy.path("sources").get(0)).remove("snapshotSha256");
                case "PROFILE" -> policy.put("inputProfileSha256", "f".repeat(64));
                case "COHORT" -> ((ObjectNode) policy.path("engineeringConditions")).putArray("ancestryApplicability");
                case "CRITERION" -> policy.putArray("criterionIds").add(BA1);
                case "PRECISION" -> ((ObjectNode) policy.path("engineeringConditions")).remove("afCountPrecision");
                case "PRECISION_SOURCE" -> ((ObjectNode) policy.path("engineeringConditions")).putArray("afCountPrecisionSourceIds").add("unbound");
                default -> throw new AssertionError(fault);
            }
            var result = evaluate(context, observation, policy, BS1);
            assertEquals("NOT_EVALUATED", status(result), fault + result);
            assertFalse(reason(result).isBlank(), fault + result);
        }
    }

    @Test void selectorGapsDoNotSilentlyFallBackAndReceiptsBindPolicyAndContext() throws Exception {
        var context = diseaseContext("MYH7"); var policy = policy("MYH7");
        var observation = observation(context, jointInfo("0.001", "10000", "0.1"), "PASS");
        var receipt = receipt(context, policy, BS1);
        receipt.put("outcome", "SPECIALIZED_POLICY_GAP").put("reasonCode", "KNOWN_SPECIALIZED_RULE_NOT_IMPLEMENTED");
        rehashReceipt(receipt);
        assertEquals("KNOWN_SPECIALIZED_RULE_NOT_IMPLEMENTED", reason(runtime.evaluate(context, observation, EXECUTION, null, receipt)));
        receipt = receipt(context, policy, BS1);
        ((ObjectNode) policy.at("/officialConditions/BS1")).put("decimal", "0.00001");
        assertEquals("POPULATION_FREQUENCY_SELECTION_POLICY_MISMATCH", reason(runtime.evaluate(context, observation, EXECUTION, policy, receipt)));
        receipt = receipt(context, policy, BS1); receipt.put("catalogVersion", "forged");
        assertEquals("POPULATION_FREQUENCY_SELECTION_IDENTITY_INVALID", reason(runtime.evaluate(context, observation, EXECUTION, policy, receipt)));
    }

    @Test void qualityFailuresMissingObservationsAndTamperingNeverBecomeNotMet() throws Exception {
        for (String fault : List.of("NO_P", "DISCORDANT", "FILTER", "FAF_ANCESTRY", "FAF_ABOVE_AF", "SUBTYPE", "AN_ZERO", "FOUNDER", "RAW")) {
            var context = diseaseContext("MYH7"); var info = jointInfo("0.001", "10000", "0.1");
            String filter = "PASS";
            switch (fault) {
                case "NO_P" -> info.remove("stat_union_p_value");
                case "DISCORDANT" -> info.put("stat_union_p_value", "0.0000999999");
                case "FILTER" -> filter = "EXOMES_FILTERED";
                case "FAF_ANCESTRY" -> info.put("faf95_joint_nfe", "0.00001");
                case "FAF_ABOVE_AF" -> info.put("AF_joint_nfe", "0.0001");
                case "SUBTYPE" -> info.put("exomes_filters", "AC0");
                case "AN_ZERO" -> info.put("AN_joint_nfe", "0");
                case "FOUNDER" -> info.put("fafmax_faf95_max_gen_anc_joint", "fin");
                case "RAW" -> { }
                default -> throw new AssertionError(fault);
            }
            var observation = observation(context, info, filter);
            if ("RAW".equals(fault)) observation.put("rawPopulationRecord", observation.path("rawPopulationRecord").asText().replace("0.001", "0.0001"));
            assertEquals("NOT_EVALUATED", status(evaluate(context, observation, policy("MYH7"), BS1)), fault);
        }
        var context = diseaseContext("MYH7");
        assertEquals("POPULATION_FREQUENCY_OBSERVATION_MISSING", reason(evaluate(context, null, policy("MYH7"), BS1)));
        var absent = observation(context, jointInfo("0.001", "10000", "0.1"), "PASS");
        var carrier = baseCarrier(context);
        ((ObjectNode) carrier.path("queryOutcome")).put("status", "NO_RECORD").put("recordCount", 0);
        ((ArrayNode) carrier.at("/source/sourceRecords")).removeAll();
        absent.set("evidence", adapter.adapt(context, carrier)); absent.remove("rawPopulationRecord");
        assertEquals("NO_RECORD_IS_NOT_FREQUENCY_ZERO", reason(evaluate(context, absent, policy("MYH7"), BS1)));
    }

    @ParameterizedTest
    @CsvSource({
        "0,10000,0.01,POPULATION_FREQUENCY_COUNT_AF_CONFLICT",
        "1,10000,0.01,POPULATION_FREQUENCY_COUNT_AF_CONFLICT",
        "1,30000,3.33333e-5,OK", "1,30000,0.0000333333,OK",
        "1,30000,3.33334e-5,POPULATION_FREQUENCY_COUNT_AF_CONFLICT",
        "1,40000,0.00002,OK", "1,40000,0.00003,OK",
        "4000,12000,0.3333333432674408,OK", "4000,12000,0.333334,POPULATION_FREQUENCY_COUNT_AF_CONFLICT"
    })
    void alleleCountsMustAgreeWithFrequencyWithinReportedDecimalRounding(String ac, String an, String af,
                                                                         String expected) throws Exception {
        // Exact rational examples: 1/30000 recurs; 1/40000 is exactly halfway between 0.00002 and 0.00003.
        var context = diseaseContext("MYH7"); var info = jointInfo("0.000001", an, "0.1");
        info.put("AC_joint_nfe", ac); info.put("AF_joint_nfe", af);
        info.put("AC_grpmax_joint", ac); info.put("AF_grpmax_joint", af);
        info.put("AC_joint", ac); info.put("AN_joint", an); info.put("AF_joint", af);
        var result = evaluate(context, observation(context, info, "PASS"), policy("MYH7"), BS1);
        if ("OK".equals(expected)) assertEquals("NOT_MET", status(result), result.toString());
        else {
            assertEquals("NOT_EVALUATED", status(result), result.toString());
            assertEquals(expected, reason(result));
        }
        if ("0".equals(ac)) {
            info.put("fafmax_faf95_max_joint", "0.001"); info.put("faf95_joint_nfe", "0.001");
            var high = evaluate(context, observation(context, info, "PASS"), policy("MYH7"), BS1);
            assertEquals("POPULATION_FREQUENCY_COUNT_AF_CONFLICT", reason(high));
        }
    }

    @Test void upperBoundsAndSupportingIntervalsCannotBeSilentlyDiscarded() throws Exception {
        var context = diseaseContext("MYH7"); var observation = observation(context, jointInfo("0.01", "10000", "0.1"), "PASS");
        for (String operation : List.of("upperBound", "upperDecimal", "interval", "strength")) {
            var policy = policy("MYH7");
            ((ObjectNode) policy.at("/officialConditions/BS1")).put(operation, "strength".equals(operation) ? "SUPPORTING" : "0.005");
            assertEquals("POPULATION_FREQUENCY_POLICY_OPERATOR_UNSUPPORTED", reason(evaluate(context, observation, policy, BS1)));
            // The unimplemented BS1 operation does not disable the separately selected BA1 clause.
            assertEquals("MET", status(evaluate(context, observation, policy, BA1)));
        }
    }

    @Test void overlapRequiresSameContextAndObservationForPositiveNegativeAndRefusedMembers() throws Exception {
        var context = diseaseContext("MYH7"); var policy = policy("MYH7");
        var observed = observation(context, jointInfo("0.0001", "10000", "0.1"), "PASS");
        var met = evaluate(context, observed, policy, BS1);
        var notMet = evaluate(context, observed, policy, BA1);
        assertEquals("MET", status(met)); assertEquals("NOT_MET", status(notMet));
        assertEquals(1, runtime.summarizeOverlap(List.of(met, notMet)).path("populationStrengthCount").asInt());

        var otherContext = diseaseContext("HNF1A");
        var otherObservation = observation(otherContext, jointInfo("0.00001", "10000", "0.1"), "PASS");
        var otherNegative = evaluate(otherContext, otherObservation, policy("HNF1A"), BA1);
        assertEquals("NOT_MET", status(otherNegative));
        assertThrows(IllegalArgumentException.class, () -> runtime.summarizeOverlap(List.of(met, otherNegative)));
        assertThrows(IllegalArgumentException.class, () -> runtime.summarizeOverlap(List.of(otherNegative, met)));

        var changedObservation = observation(context, jointInfo("0.00001", "10000", "0.1"), "PASS");
        var differentObservedNegative = evaluate(context, changedObservation, policy, BA1);
        assertEquals("NOT_MET", status(differentObservedNegative));
        assertThrows(IllegalArgumentException.class, () -> runtime.summarizeOverlap(List.of(met, differentObservedNegative)));

        var refusedSameContext = evaluate(context, null, policy, BA1);
        assertEquals("NOT_EVALUATED", status(refusedSameContext));
        assertEquals(1, runtime.summarizeOverlap(List.of(refusedSameContext, met)).path("populationStrengthCount").asInt());
        var refusedOtherContext = evaluate(otherContext, null, policy("HNF1A"), BA1);
        assertThrows(IllegalArgumentException.class, () -> runtime.summarizeOverlap(List.of(met, refusedOtherContext)));

        var conflictingRuntimeContext = notMet.deepCopy(); conflictingRuntimeContext.set("context", otherContext);
        assertThrows(IllegalArgumentException.class, () -> runtime.summarizeOverlap(List.of(met, conflictingRuntimeContext)));
    }

    @Test void missingSubtypeInfoIsDistinctFromAnExplicitDotAndLegacyPolicyIsUnchanged() throws Exception {
        var context = diseaseContext("HNF1A"); var info = jointInfo("0.0001", "10000", "0.1");
        var policy = policy("HNF1A");
        for (String value : List.of(".", "PASS")) {
            info.put("exomes_filters", value); info.put("genomes_filters", value);
            var result = evaluate(context, observation(context, info, "PASS"), policy, BS1);
            assertEquals("MET", status(result), result.toString());
            assertTrue(result.at("/observedInputCompleteness/exomesFilterInfoPresent").asBoolean());
        }
        for (String missing : List.of("exomes_filters", "genomes_filters")) {
            var absent = new LinkedHashMap<>(info); absent.remove(missing);
            var observation = observation(context, absent, "PASS");
            assertEquals("POPULATION_FREQUENCY_SUBTYPE_FILTER_PRESENCE_REQUIRED", reason(evaluate(context, observation, policy, BS1)));
            assertEquals("MET", new Hnf1aPopulationRuntime(mapper).evaluate(context, observation, EXECUTION)
                    .at("/criterionEvaluations/1/evaluationResult/status").asText());
            absent.put(missing, "");
            assertEquals("POPULATION_FREQUENCY_SUBTYPE_FILTER_VALUE_REQUIRED",
                    reason(evaluate(context, observation(context, absent, "PASS"), policy, BS1)));
        }
    }

    private ObjectNode evaluate(ObjectNode context, ObjectNode observation, ObjectNode policy, String criterion) {
        return runtime.evaluate(context, observation, EXECUTION, policy, receipt(context, policy, criterion));
    }

    ObjectNode policy(String gene) {
        var value = GnomadPopulationInputProfile.loadHnf1aFrequency().getDocument().path("diseaseFrequencyPolicy").deepCopy();
        ObjectNode policy = (ObjectNode) value;
        policy.put("schemaVersion", "population-frequency-policy-v1").put("policyId", "SYNTHETIC_DEVELOPER_" + gene)
                .put("inputProfileSha256", GnomadPopulationInputProfile.loadExecutableV2().getProfileSha256());
        policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "synthetic-fixture-not-clinical-review");
        policy.putArray("criterionIds").add(BA1).add(BS1);
        policy.putArray("sources").addObject().put("authoritySourceId", "authority-synthetic-development")
                .put("url", "https://example.org/synthetic-population-test").put("version", "1").put("snapshotSha256", "b".repeat(64));
        var quality = (ObjectNode) policy.path("engineeringConditions");
        quality.putObject("minimumSelectedAncestryAn").put("BA1", "2000").put("BS1", "1");
        quality.put("minimumAnBasis", "Existing HNF1A engineering bounds; MYH7 10000 is a conservative development restriction, not a VCEP exclusion.");
        quality.put("afCountPrecision", "VCF_FLOAT32_WITH_DECIMAL_ROUNDING");
        quality.putArray("afCountPrecisionSourceIds").add("authority-synthetic-development");
        quality.putArray("applicableAncestries").add("nfe").add("afr");
        quality.putArray("ancestryApplicability").addObject().put("ancestry", "nfe").put("status", "REVIEWED_APPLICABLE")
                .put("basis", "Synthetic cohort applicability only; not a public-population acceptance claim.")
                .putArray("authoritySourceIds").add("authority-synthetic-development");
        if ("MYH7".equals(gene)) {
            ((ObjectNode) policy.path("scope")).put("hgncId", "HGNC:7577").put("mondoId", "MONDO:0005045");
            ((ObjectNode) policy.at("/officialConditions/BA1")).put("decimal", "0.001");
            ((ObjectNode) policy.at("/officialConditions/BS1")).put("decimal", "0.0001");
            quality.putObject("minimumSelectedAncestryAn").put("BA1", "10000").put("BS1", "10000");
        }
        return policy;
    }

    private ObjectNode receipt(ObjectNode context, ObjectNode policy, String criterion) {
        var receipt = mapper.createObjectNode().put("schemaVersion", "acmg-specification-selection-v1")
                .put("contextId", context.path("contextId").asText()).put("outcome", "SPECIALIZED_POLICY_SELECTED")
                .put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText())
                .put("policySha256", adapter.canonicalSha256(policy)).put("catalogId", "synthetic")
                .put("catalogVersion", "1").put("catalogSha256", "c".repeat(64));
        receipt.putArray("criterionIds").add(criterion); rehashReceipt(receipt); return receipt;
    }

    private void rehashReceipt(ObjectNode receipt) {
        receipt.remove("selectionId");
        receipt.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(receipt));
    }

    ObjectNode observation(ObjectNode context, Map<String, String> info, String filter) {
        var carrier = baseCarrier(context);
        carrier.set("measurements", mapper.valueToTree(profile.parse(filter, info, 0, 1).getMeasurements()));
        var allele = context.at("/variant/evidenceQueryAllele");
        String raw = "chr" + allele.path("contig").asText() + "\t" + allele.path("position").asInt() + "\t.\t"
                + allele.path("reference").asText() + "\t" + allele.path("alternate").asText() + "\t.\t" + filter + "\t"
                + info.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(";"));
        var result = mapper.createObjectNode().put("executionSourceSha256", EXECUTION)
                .put("profileSha256", profile.getProfileSha256()).put("rawPopulationRecord", raw);
        result.set("context", context.deepCopy()); result.set("evidence", adapter.adapt(context, carrier)); return result;
    }

    private String status(JsonNode result) { return result.at("/criterionEvaluations/0/evaluationResult/status").asText(); }
    private String reason(JsonNode result) { return result.at("/criterionEvaluations/0/evaluationResult/reasonCode").asText(); }

    private void assertHashes(ObjectNode evaluation) {
        var trace = ((ObjectNode) evaluation.path("deterministicTrace")).deepCopy();
        assertEquals(trace.remove("traceSha256").asText(), adapter.canonicalSha256(trace));
        var identity = evaluation.deepCopy();
        String id = identity.remove("evaluationId").asText(); identity.remove(List.of("conflict", "review"));
        assertEquals("evaluation-sha256-" + adapter.canonicalSha256(identity), id);
    }

    ObjectNode diseaseContext(String gene) throws IOException {
        var context = context("HNF1A".equals(gene) ? "12" : "14", "HNF1A".equals(gene) ? 120988847 : 23400000, "G", "A");
        ((ObjectNode) context.path("gene")).put("hgncId", "HNF1A".equals(gene) ? "HGNC:11621" : "HGNC:7577").put("symbol", gene);
        ((ObjectNode) context.path("disease")).put("mondoId", "HNF1A".equals(gene) ? "MONDO:0015967" : "MONDO:0005045");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("selectionPolicy", org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.FREQUENCY_CONTEXT_SELECTION);
        refreshContextId(context); return context;
    }


    private ObjectNode baseCarrier(ObjectNode context) {
        JsonNode resource = profile.getDocument().path("resources").get(0);
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "POPULATION_FREQUENCY");
        carrier.set("queryAllele", context.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", resource.path("resourceId").asText());
        java.util.Objects.requireNonNull(resource, "Test resource must exist in the registry");
        source.put("resourceVersion", resource.path("resourceVersion").asText());
        source.put("resourceSha256", resource.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "GENOMIC_REGION");
        locator.put("locatorValue", "GNOMAD_JOINT_V4_1_GRCH38_SITES:GRCh38:exact-allele");
        locator.put("sourceRecordId", "GNOMAD_JOINT_V4_1_GRCH38_SITES:exact-allele");
        locator.put("artifactSha256", resource.path("identityFingerprintSha256").asText());
        locator.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode record = mapper.createObjectNode();
        record.put("recordId", "GNOMAD_JOINT_V4_1_GRCH38_SITES:exact-allele");
        record.set("locator", locator.deepCopy());
        source.putArray("sourceRecords").add(record);
        source.putArray("sourceLocators").add(locator);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED")
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        ObjectNode queryFingerprint = mapper.createObjectNode();
        queryFingerprint.put("resourceId", resource.path("resourceId").asText());
        queryFingerprint.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(queryFingerprint));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putArray("measurements");
        carrier.put("producedAt", "2026-09-02T00:00:00Z");
        carrier.put("sourcePayloadSha256",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return carrier;
    }

    Map<String, String> jointInfo(String faf95max, String selectedAn, String pValue) {
        Map<String, String> info = new LinkedHashMap<>();
        String selectedAf = switch (selectedAn) {
            case "1999" -> "0.05002501251";
            case "9999" -> "0.01000100010";
            default -> "0.01";
        };
        info.put("AC_joint", "100");
        info.put("AN_joint", "10000");
        info.put("AF_joint", "0.01");
        info.put("nhomalt_joint", "0");
        info.put("grpmax_joint", "nfe");
        info.put("AC_grpmax_joint", "100");
        info.put("AN_grpmax_joint", selectedAn);
        info.put("AF_grpmax_joint", selectedAf);
        info.put("nhomalt_grpmax_joint", "0");
        info.put("faf95_joint", faf95max);
        info.put("fafmax_faf95_max_joint", faf95max);
        info.put("fafmax_faf95_max_gen_anc_joint", "nfe");
        info.put("AC_joint_nfe", "100");
        info.put("AN_joint_nfe", selectedAn);
        info.put("AF_joint_nfe", selectedAf);
        info.put("nhomalt_joint_nfe", "0");
        info.put("faf95_joint_nfe", faf95max);
        info.put("stat_union_p_value", pValue);
        info.put("stat_union_test_name", "cmh");
        info.put("stat_union_gen_ancs", "nfe");
        info.put("exomes_filters", ".");
        info.put("genomes_filters", ".");
        return info;
    }

    private ObjectNode context(String contig, int position, String reference, String alternate)
            throws IOException {
        ObjectNode context;
        try (InputStream input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            context = (ObjectNode) mapper.readTree(input).path("context").deepCopy();
        }
        ObjectNode allele = mapper.createObjectNode();
        allele.put("assembly", "GRCh38").put("contig", contig).put("position", position)
                .put("reference", reference).put("alternate", alternate);
        ObjectNode variant = (ObjectNode) context.path("variant");
        variant.set("inputAllele", allele.deepCopy());
        variant.set("normalizedAllele", allele.deepCopy());
        variant.set("evidenceQueryAllele", allele.deepCopy());
        ObjectNode normalization = (ObjectNode) variant.path("normalization");
        ObjectNode alleleProjection = mapper.createObjectNode();
        alleleProjection.set("normalizedAllele", allele.deepCopy());
        ObjectNode normalizer = alleleProjection.putObject("normalizer");
        for (String field : List.of("method", "methodVersion", "referenceSequenceSha256", "refValidation"))
            normalizer.set(field, normalization.path(field).deepCopy());
        variant.put("alleleId", "allele-sha256-" + adapter.canonicalSha256(alleleProjection));
        refreshContextId(context);
        return context;
    }

    private void refreshContextId(ObjectNode context) {
        ObjectNode variant = (ObjectNode) context.path("variant");
        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", variant.path("alleleId").asText());
        projection.put("hgncId", context.path("gene").path("hgncId").asText());
        projection.put("mondoId", context.path("disease").path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", context.path("transcript").path("accession").asText());
        transcript.set("version", context.path("transcript").path("version").deepCopy());
        projection.put("patientContextId", context.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId", context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
    }
}
