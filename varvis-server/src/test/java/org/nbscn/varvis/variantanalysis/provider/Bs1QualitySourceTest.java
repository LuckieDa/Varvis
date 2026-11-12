package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic quality-boundary controls; parent sealing and sex-statistic qualification are separate checks. */
class Bs1QualitySourceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String HEADER = "b".repeat(64), RESOURCE = "c".repeat(64);
    private static final String JOINT_AF = "gnomad.joint.sex_ancestry_max_af";
    private static final String JOINT_FAF = "gnomad.joint.sex_ancestry_max_faf95";

    private ObjectNode policy() {
        var value = mapper.createObjectNode();
        value.putObject("statistic").put("metricId", JOINT_AF).putArray("ancestries").add("nfe");
        var quality = value.putObject("qualityContract").put("contractId", "synthetic-joint-source-quality").put("version", "1.0.0");
        quality.putObject("releaseSemantics").put("version", "4.1").put("headerSha256", HEADER)
                .put("subtypeDot", "UNKNOWN_REQUIRES_NATIVE").put("missingSubtype", "OPTIONAL_WITH_NATIVE_PROOF")
                .put("pValueField", "stat_union_p_value").put("pValueThreshold", "0.0001")
                .put("pMissing", "REQUIRED_OR_UNKNOWN").put("notApplicableBasis", "");
        return value;
    }

    private ObjectNode observation(String filter, String exomes, String genomes, String p) {
        var value = mapper.createObjectNode().put("observationId", "synthetic-joint-parent").put("outputCompleteness", "VERIFIED");
        value.putObject("queryAllele").put("assembly", "GRCh38").put("contig", "12").put("position", 120988847)
                .put("reference", "G").put("alternate", "A");
        value.putObject("headerContract").put("sha256", HEADER).put("resourceFingerprintSha256", RESOURCE);
        value.putObject("resourceContract").putObject("identity").put("release", "4.1");
        var carrier = value.putObject("carrier");
        carrier.putObject("source").put("resourceSha256", RESOURCE);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        carrier.putObject("queryOutcome").put("status", "HIT"); carrier.putArray("measurements");
        // These source-defined count fields are only a synthetic payload here; this quality gate never selects them.
        String info = "AC_joint_nfe_XY=21;AN_joint_nfe_XY=10000;AF_joint_nfe_XY=0.0021"
                + ";exomes_filters=" + exomes + ";genomes_filters=" + genomes
                + (p == null ? "" : ";stat_union_p_value=" + p)
                + ";stat_union_test_name=cmh;stat_union_gen_ancs=nfe";
        value.put("rawRecord", "chr12\t120988847\t.\tG\tA\t.\t" + filter + "\t" + info);
        return value;
    }

    private ObjectNode source(ObjectNode observation, ObjectNode policy, ObjectNode nativeFacts) {
        return Bs1Quality.assessSource(mapper, observation, policy, nativeFacts);
    }
    private ObjectNode source(ObjectNode observation) { return source(observation, policy(), mapper.createObjectNode()); }
    private List<String> reasons(JsonNode decision) {
        return java.util.stream.StreamSupport.stream(decision.path("issues").spliterator(), false)
                .map(issue -> issue.path("reasonCode").asText()).toList();
    }
    private ObjectNode nativeFacts(ObjectNode observation, String exomes, String genomes) {
        var facts = mapper.createObjectNode();
        if (exomes != null) facts.set("exomes", Bs1NativeFilterSupplement.proof(mapper, "exomes", observation, exomes));
        if (genomes != null) facts.set("genomes", Bs1NativeFilterSupplement.proof(mapper, "genomes", observation, genomes));
        return facts;
    }
    private String nativeRow(String filter) { return "chr12\t120988847\t.\tG\tA\t.\t" + filter + "\t.\n"; }

    @Test void sourceGateDoesNotRequireOrCreateAggregateFrequencyFacts() {
        var observation = observation("PASS", "PASS", "PASS", "0.5"); var before = observation.deepCopy();
        var result = source(observation);
        assertEquals("READY", result.path("status").asText()); assertTrue(result.path("facts").isEmpty());
        assertTrue(result.path("issues").isEmpty()); assertEquals(before, observation);
        var legacy = Bs1Quality.assess(mapper, observation, policy(), mapper.createObjectNode());
        assertEquals("BLOCKED", legacy.path("status").asText());
        assertEquals(List.of("BS1_FAF_ANCESTRY_UNSUPPORTED"), reasons(legacy));
        var normalized = legacy.deepCopy(); normalized.putArray("issues"); normalized.put("status", "READY");
        assertEquals(normalized, result);
    }

    @Test void legacyAggregateDecisionRetainsItsFullOutputAndIssueOrder() throws Exception {
        var observation = observation("PASS", "PASS", "PASS", "0.5");
        var measurements = observation.withArray("/carrier/measurements");
        for (String[] measure : List.of(new String[]{"gnomad.joint.faf95max.ancestry", "nfe"},
                new String[]{"gnomad.joint.faf95max", "0.001"}, new String[]{"gnomad.joint.ancestry.nfe.faf95", "0.001"},
                new String[]{"gnomad.joint.ancestry.nfe.an", "10000"}, new String[]{"gnomad.joint.ancestry.nfe.ac", "20"},
                new String[]{"gnomad.joint.ancestry.nfe.af", "0.002"}))
            measurements.addObject().put("metricId", measure[0]).put("rawValue", measure[1]);
        var expected = (ObjectNode) mapper.readTree("""
                {"schemaVersion":"bs1-quality-decision-v2","adapterVersion":"bs1-joint-filter-adapter-v1",
                 "issues":[],"facts":{"ancestry":"nfe","FAF95":"0.001","AN":"10000","AC":"20"},
                 "rawFieldStates":{"exomes_filters":{"state":"PASS","raw":"PASS"},"genomes_filters":{"state":"PASS","raw":"PASS"}},
                 "filterCondition":{"observedJointFilter":"PASS","basis":"EXPLICIT_SUBTYPE_OR_NATIVE_FACTS",
                   "sourceEvidenceIds":["synthetic-joint-parent"],"status":"SATISFIED","decision":"SEE_FILTER_CHECKS"},
                 "discordance":{"rawState":"VALUE","field":"stat_union_p_value","testName":"cmh","ancestries":"nfe",
                   "signalThreshold":"0.0001","dispositionBasis":"LOCAL_ENGINEERING_SAFEGUARD",
                   "applicability":"APPLICABLE","observedP":"0.5","signal":false},"nativeFacts":{},"status":"READY"}
                """);
        var policy = policy(); expected.set("contract", policy.path("qualityContract"));
        expected.put("contractSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(policy.path("qualityContract")));
        assertEquals(expected, Bs1Quality.assess(mapper, observation, policy, mapper.createObjectNode()));
        observation.withObject("/carrier").putArray("measurements");
        observation.put("rawRecord", observation.path("rawRecord").asText().replace("exomes_filters=PASS", "exomes_filters=AS_VQSR"));
        assertEquals(List.of("BS1_FAF_ANCESTRY_UNSUPPORTED", "BS1_JOINT_SUBTYPE_FILTER_CONFLICT"),
                reasons(Bs1Quality.assess(mapper, observation, policy, mapper.createObjectNode())));
    }

    @Test void sourceGateKeepsJointAndSubtypeFailures() {
        for (String filter : List.of("EXOMES_FILTERED", "GENOMES_FILTERED", "BOTH_FILTERED", ".")) {
            var observation = observation(filter, "PASS", "PASS", "0.5");
            var result = source(observation, policy(), nativeFacts(observation, nativeRow("PASS"), nativeRow("PASS")));
            assertEquals("BLOCKED", result.path("status").asText());
            assertEquals("BS1_JOINT_FILTER_NOT_PASS", reasons(result).get(0));
            assertTrue(Bs1Quality.supplementNeeds(observation, policy()).isEmpty());
        }
        var filtered = source(observation("PASS", "AS_VQSR", "PASS", "0.5"));
        assertEquals(List.of("BS1_JOINT_SUBTYPE_FILTER_CONFLICT"), reasons(filtered));
        assertEquals("CONFLICT", filtered.at("/filterCondition/status").asText());
        assertEquals(List.of("BS1_REQUIRED_FILTER_INFO_MISSING"), reasons(source(observation("PASS", "", "PASS", "0.5"))));
    }

    @Test void nativeConflictsAndTamperedFactsRemainBlocking() {
        var observation = observation("PASS", "PASS", "PASS", "0.5");
        assertEquals(List.of("BS1_JOINT_SUBTYPE_FILTER_CONFLICT"),
                reasons(source(observation, policy(), nativeFacts(observation, nativeRow("AS_VQSR"), null))));
        assertEquals(List.of("BS1_SUBTYPE_PRESENCE_CONFLICT"),
                reasons(source(observation, policy(), nativeFacts(observation, "", null))));
        var facts = nativeFacts(observation, nativeRow("PASS"), null);
        facts.withObject("/exomes").put("jointObservationId", "another-parent");
        assertTrue(reasons(source(observation, policy(), facts)).contains("BS1_NATIVE_FACT_UNRESOLVED"));
        observation = observation("PASS", ".", ".", "0.5");
        assertTrue(reasons(source(observation, policy(), nativeFacts(observation, "", ""))).contains("BS1_JOINT_NATIVE_PRESENCE_CONFLICT"));
    }

    @Test void sourceGatePreservesPValueBoundaryAndMissingApplicabilityRules() {
        assertEquals("READY", source(observation("PASS", "PASS", "PASS", "0.0001")).path("status").asText());
        assertEquals(List.of("BS1_LOCAL_DISCORDANCE_REVIEW_REQUIRED"),
                reasons(source(observation("PASS", "PASS", "PASS", "0.000099999"))));
        for (String p : List.of("-0.1", "1.1", "bad", ""))
            assertEquals(List.of("BS1_DISCORDANCE_INPUT_INVALID"), reasons(source(observation("PASS", "PASS", "PASS", p))));
        for (String p : new String[]{null, "."})
            assertEquals(List.of("BS1_DISCORDANCE_APPLICABILITY_OR_INPUT_UNKNOWN"), reasons(source(observation("PASS", "PASS", "PASS", p))));
        var policy = policy(); policy.withObject("/qualityContract/releaseSemantics")
                .put("pMissing", "ONE_DATASET_ABSENT_WITH_NATIVE_PROOF").put("notApplicableBasis", "SYNTHETIC one dataset absent");
        var observation = observation("PASS", ".", ".", ".");
        var accepted = source(observation, policy, nativeFacts(observation, "", nativeRow("PASS")));
        assertEquals("READY", accepted.path("status").asText());
        assertEquals("NOT_APPLICABLE", accepted.at("/discordance/applicability").asText());
        assertEquals("BLOCKED", source(observation, policy, mapper.createObjectNode()).path("status").asText());
    }

    @Test void pValueSelectsTheTargetAlternate() {
        var observation = observation("PASS", "PASS", "PASS", "0.000001,0.5");
        observation.put("rawRecord", observation.path("rawRecord").asText().replace("\tG\tA\t", "\tG\tC,A\t")
                .replace("stat_union_test_name=cmh", "stat_union_test_name=chi_squared,cmh"));
        var result = source(observation); assertEquals("READY", result.path("status").asText());
        assertEquals("0.5", result.at("/discordance/observedP").asText());
        assertEquals("cmh", result.at("/discordance/testName").asText());
        observation.put("rawRecord", observation.path("rawRecord").asText().replace("0.000001,0.5", "0.5,0.000001"));
        assertEquals(List.of("BS1_LOCAL_DISCORDANCE_REVIEW_REQUIRED"), reasons(source(observation)));
    }

    @Test void bindingAvailabilityAndNoRecordRemainDistinct() {
        for (String path : List.of("/headerContract/sha256", "/resourceContract/identity/release", "/carrier/source/resourceSha256")) {
            var observation = observation("PASS", "PASS", "PASS", "0.5");
            int split = path.lastIndexOf('/'); observation.withObject(path.substring(0, split)).put(path.substring(split + 1), "wrong");
            assertEquals(List.of("BS1_HEADER_RELEASE_BINDING_INVALID"), reasons(source(observation)));
        }
        var observation = observation("PASS", "PASS", "PASS", "0.5");
        observation.withObject("/carrier/resourceExecution").put("status", "FAILED");
        assertEquals(List.of("BS1_RESOURCE_EXECUTION_UNAVAILABLE"), reasons(source(observation)));
        observation.withObject("/carrier/resourceExecution").put("status", "SUCCEEDED");
        observation.withObject("/carrier/queryOutcome").put("status", "NO_RECORD"); observation.put("rawRecord", "");
        assertEquals(List.of("BS1_NO_RECORD"), reasons(source(observation)));
        assertTrue(source(observation).path("facts").isEmpty());
    }

    @Test void optionalSubtypeValuesStillRequireTheBoundJointDefinition() {
        var policy = policy(); policy.withObject("/qualityContract/releaseSemantics")
                .put("jointFilterDefinition", "GNOMAD_4_1_JOINT_FILTER")
                .put("missingSubtype", "OPTIONAL_WITH_BOUND_JOINT_FILTER").put("subtypeDot", "RAW_UNKNOWN_FILTER_BY_BOUND_JOINT");
        var observation = observation("PASS", ".", ".", "0.5");
        var contract = Bs1JointFilterSource.contract(mapper);
        String fingerprint = contract.path("resourceFingerprintSha256").asText();
        observation.withObject("/resourceContract").put("resourceVersion", "gnomAD joint v4.1 GRCh38")
                .put("identityFingerprintSha256", fingerprint);
        observation.withObject("/headerContract").put("resourceFingerprintSha256", fingerprint);
        observation.withObject("/carrier/source").put("resourceSha256", fingerprint);
        String uri = contract.at("/headers/12/url").asText();
        observation.putObject("transport").put("querySource", "remote-joint-vcf-population-input")
                .put("sourceDataset", "joint").put("gnomadVersion", "gnomAD joint v4.1 GRCh38")
                .put("resourceUri", uri).put("resourceIndexUri", uri + ".tbi");
        var bound = source(observation, policy, mapper.createObjectNode());
        assertEquals("READY", bound.path("status").asText());
        assertEquals("SATISFIED_BY_JOINT_FILTER_DEFINITION", bound.at("/filterCondition/decision").asText());
        assertTrue(bound.path("facts").isEmpty());
        observation.withObject("/transport").put("sourceDataset", "exomes");
        assertTrue(reasons(source(observation, policy, mapper.createObjectNode())).contains("BS1_JOINT_FILTER_DEFINITION_UNBOUND"));
    }

    @Test void onlyTheTwoJointSexMetricsUseSourceOnlySupplementPreflight() {
        var observation = observation("PASS", ".", ".", "0.5");
        for (String metric : List.of(JOINT_AF, JOINT_FAF)) {
            var policy = policy(); policy.withObject("/statistic").put("metricId", metric);
            assertEquals(Set.of("exomes", "genomes"), Bs1Quality.supplementNeeds(observation, policy), metric);
        }
        for (String metric : List.of("gnomad.joint.faf95max", "gnomad.exomes.sex_ancestry_max_af", "gnomad.joint.sex_ancestry_max_unknown")) {
            var policy = policy(); policy.withObject("/statistic").put("metricId", metric);
            assertTrue(Bs1Quality.supplementNeeds(observation, policy).isEmpty(), metric);
        }
        var filtered = observation("PASS", "AS_VQSR", ".", "0.5");
        assertTrue(Bs1Quality.supplementNeeds(filtered, policy()).isEmpty());
        var significant = observation("PASS", ".", ".", "0.00001");
        assertTrue(Bs1Quality.supplementNeeds(significant, policy()).isEmpty());
    }
}
