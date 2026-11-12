package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic source-qualified records, actual identity builders; never scientific source adoption.
 * Frozen pre-feature field shapes are checked separately from new method declaration sets. */
class Bs1CountEligibilityIdentityTest {
    private static final String LOCATOR = "SYNTHETIC: target allele AC eligibility";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    @TempDir Path temporary;

    private ObjectNode record(boolean joint, boolean eligible, String disease) {
        var record = joint ? new Bs1JointSexChromosomeRuntimeTest().record(true)
                : new Bs1SexChromosomeRuntimeTest().record("GT");
        record.put("recordId", "SYNTHETIC_IDENTITY_" + disease);
        record.withObject("/scope").put("mondoId", disease);
        record.withObject("/expertPolicy/scope").put("mondoId", disease);
        if (eligible) record.withObject("/expertPolicy/statistic").putObject("eligibleAc")
                .put("operator", "GT").put("decimal", "1").put("unit", "alleles").put("sourceTerm", "AC")
                .put("sourceId", record.at("/sources/0/sourceId").asText()).put("locator", LOCATOR);
        if (joint) new Bs1JointSexChromosomeRuntimeTest().qualify(record);
        else Bs1SexChromosomeStatisticTest.qualify(record);
        if (eligible) record.withArray("/qualification/sourceBindings").addObject()
                .put("sourceId", record.at("/sources/0/sourceId").asText()).put("locator", LOCATOR)
                .put("contentSha256", "a".repeat(64));
        new Bs1KnowledgeCatalogTest().review(record);
        if (joint) record.withObject("/review").put("dataScopePolicyId", Bs1SexChromosomePolicy.JOINT_SCOPE);
        assertNull(Bs1Policy.issue(Bs1KnowledgeCatalog.policy(record)), record.toPrettyString());
        return record;
    }

    private Bs1KnowledgeCatalog catalog(List<ObjectNode> records) throws Exception {
        var bundle = mapper.createObjectNode().put("schemaVersion", "bs1-knowledge-bundle-v1").put("version", "SYNTHETIC_T03_IDENTITY");
        var sources = bundle.putArray("sources"); var seen = new java.util.HashSet<String>();
        for (var record : records) for (var source : record.path("sources"))
            if (seen.add(source.path("sourceId").asText())) sources.add(source.deepCopy());
        var rows = bundle.putArray("records"); records.forEach(rows::add); bundle.putArray("backgrounds");
        byte[] raw = mapper.writeValueAsBytes(bundle); Path path = temporary.resolve(UUID.randomUUID() + ".json");
        Files.write(path, raw); return Bs1KnowledgeCatalog.load(path, Bs1KnowledgeCatalog.sha(raw));
    }

    private List<JsonNode> policies(List<ObjectNode> records) {
        return records.stream().<JsonNode>map(Bs1KnowledgeCatalog::policy).toList();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pureV1CatalogRetainsEveryPreFeatureFieldAndHasNoNewSet(boolean joint) throws Exception {
        var record = record(joint, false, "MONDO:0999001"); var catalog = catalog(List.of(record));
        var actual = catalog.identity();
        var expected = mapper.createObjectNode().put("schemaVersion", "bs1-knowledge-identity-v1")
                .put("rawSha256", actual.path("rawSha256").asText()).put("version", "SYNTHETIC_T03_IDENTITY")
                .put("modelVersion", Bs1MaximumCredibleFrequency.VERSION).put("qualificationVersion", Bs1KnowledgeQualification.VERSION)
                .put("sexChromosomeStatisticVersion", joint ? Bs1SexChromosomeStatistic.JOINT_VERSION : Bs1SexChromosomeStatistic.VERSION)
                .put("sexChromosomeRegionVersion", SexChromosomeRegionResolver.VERSION)
                .put("sexChromosomeRegionCatalogSha256", SexChromosomeRegionResolver.CATALOG_SHA256);
        if (joint) expected.put("jointSexChromosomeStatisticVersion", Bs1SexChromosomeStatistic.JOINT_VERSION)
                .put("jointSexObservationVersion", GnomadSexStratifiedObservation.JOINT_VERSION)
                .put("jointSexHeaderSha256", GnomadSexStratifiedObservation.JOINT_HEADER_SHA256)
                .put("jointSexFilterSourceSha256", Bs1KnowledgeCatalog.hash(Bs1JointFilterSource.sexContract(mapper)));
        assertEquals(expected, actual); assertEquals(expected, catalog.identity());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pureV2CatalogAndBothRuntimeKindsDeclareTheirActualMethod(boolean joint) throws Exception {
        var records = List.of(record(joint, true, "MONDO:0999001"));
        String version = joint ? Bs1SexChromosomeStatistic.JOINT_AC_ELIGIBILITY_VERSION : Bs1SexChromosomeStatistic.AC_ELIGIBILITY_VERSION;
        var identities = new ArrayList<ObjectNode>(); identities.add(catalog(records).identity());
        identities.add(runtime(policies(records), false)); identities.add(runtime(policies(records), true));
        int ordinal = 0;
        for (var identity : identities) {
            emit("pure-v2-" + (joint ? "joint" : "exomes") + "-" + ordinal++, identity);
            assertEquals(version, identity.path("sexChromosomeStatisticVersion").asText());
            assertEquals(mapper.valueToTree(List.of(version)), identity.path("sexChromosomeStatisticVersions"));
            assertEquals(version, identity.path(joint ? "jointSexChromosomeStatisticVersion" : "exomesSexChromosomeStatisticVersion").asText());
            if (identity.has("jointSexStatisticVersion")) assertEquals(version, identity.path("jointSexStatisticVersion").asText());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mixedV1V2MethodDeclarationsAreOrderIndependentInCatalogAndRuntime(boolean joint) throws Exception {
        var v1 = record(joint, false, "MONDO:0999001"); var v2 = record(joint, true, "MONDO:0999002");
        var forward = List.of(v1, v2); var reverse = List.of(v2, v1);
        var versions = new TreeSet<String>(); versions.add(Bs1SexChromosomeStatistic.version(Bs1KnowledgeCatalog.policy(v1)));
        versions.add(Bs1SexChromosomeStatistic.version(Bs1KnowledgeCatalog.policy(v2)));
        var a = catalog(forward).identity(); var b = catalog(reverse).identity();
        assertEquals(versionProjection(a), versionProjection(b));
        assertNotEquals(a.path("rawSha256"), b.path("rawSha256"), "Original source order is still bound, unlike method declarations");
        var identities = new ArrayList<ObjectNode>(); identities.add(a); identities.add(b);
        for (boolean routed : new boolean[]{false, true}) {
            var first = runtime(policies(forward), routed); var second = runtime(policies(reverse), routed);
            assertEquals(versionProjection(first), versionProjection(second));
            assertNotEquals(first.path("policiesSha256"), second.path("policiesSha256"));
            identities.add(first); identities.add(second);
        }
        int ordinal = 0;
        for (var identity : identities) {
            emit("mixed-" + (joint ? "joint" : "exomes") + "-" + ordinal++, identity);
            assertEquals("MIXED", identity.path("sexChromosomeStatisticVersion").asText());
            assertEquals(mapper.valueToTree(new ArrayList<>(versions)), identity.path("sexChromosomeStatisticVersions"));
            assertEquals("MIXED", identity.path(joint ? "jointSexChromosomeStatisticVersion" : "exomesSexChromosomeStatisticVersion").asText());
            if (identity.has("jointSexStatisticVersion")) assertEquals("MIXED", identity.path("jointSexStatisticVersion").asText());
        }
    }

    @ParameterizedTest @CsvSource({"false,true", "true,false", "true,true"})
    void sourceSpecificSetsDoNotReportAnUnrelatedDatasetMethod(boolean exomesV2, boolean jointV2) throws Exception {
        var e = record(false, exomesV2, "MONDO:0999001"); var j = record(true, jointV2, "MONDO:0999002");
        var a = catalog(List.of(e, j)).identity(); var b = catalog(List.of(j, e)).identity();
        assertEquals(versionProjection(a), versionProjection(b));
        assertEquals("MIXED", a.path("sexChromosomeStatisticVersion").asText());
        assertEquals(exomesV2 ? Bs1SexChromosomeStatistic.AC_ELIGIBILITY_VERSION : Bs1SexChromosomeStatistic.VERSION,
                a.path("exomesSexChromosomeStatisticVersion").asText());
        assertEquals(jointV2 ? Bs1SexChromosomeStatistic.JOINT_AC_ELIGIBILITY_VERSION : Bs1SexChromosomeStatistic.JOINT_VERSION,
                a.path("jointSexChromosomeStatisticVersion").asText());
        assertEquals(1, a.path("exomesSexChromosomeStatisticVersions").size());
        assertEquals(1, a.path("jointSexChromosomeStatisticVersions").size());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pureV1RuntimeKindsRetainTheEntirePreFeatureIdentityShape(boolean joint) {
        var records = policies(List.of(record(joint, false, "MONDO:0999001")));
        for (boolean routed : new boolean[]{false, true}) assertEquals(legacyRuntimeIdentity(records, routed), runtime(records, routed));
    }

    private void emit(String name, ObjectNode identity) throws Exception {
        String output = System.getProperty("varvis.t03.bs1.identity.output");
        if (output == null || output.isBlank()) return;
        var folder = Path.of(output); Files.createDirectories(folder);
        var sample = mapper.createObjectNode().put("dataClass", "SYNTHETIC")
                .put("layer", "ACTUAL_IDENTITY_PROJECTION_NOT_POPULATION_QUERY_OR_CURRENT_ACCEPTANCE")
                .put("scope", "METHOD_DECLARATIONS_ARE_NOT_SCIENTIFIC_ADOPTION");
        sample.set("identity", identity); Files.write(folder.resolve(name + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample));
    }

    private ObjectNode versionProjection(ObjectNode identity) {
        var result = mapper.createObjectNode();
        for (var it = identity.properties().iterator(); it.hasNext();) {
            var item = it.next(); if (item.getKey().toLowerCase(java.util.Locale.ROOT).contains("statistic")) result.set(item.getKey(), item.getValue());
        }
        return result;
    }

    private ObjectNode runtime(List<JsonNode> records, boolean routed) {
        var exomes = new UnusedProvider("EXOMES"); var joint = new UnusedProvider("JOINT");
        return (routed ? Bs1Runtime.routedDevelopment(mapper, records, exomes, joint, null)
                : new Bs1Runtime(mapper, records, exomes, null)).identity();
    }

    /** Pre-feature contract expectation, intentionally independent of the new version-set projector. */
    private ObjectNode legacyRuntimeIdentity(List<JsonNode> records, boolean routed) {
        var result = mapper.createObjectNode();
        if (routed) {
            result.put("schemaVersion", "bs1-policy-routed-runtime-identity-v1")
                    .put("activationContract", PopulationRuntimeRouting.ACTIVATION).put("formalEnabled", false)
                    .put("contextVersion", "evidence-context-v3").put("observationVersion", PopulationObservation.VERSION)
                    .put("evaluatorVersion", Bs1CriterionEvaluator.VERSION).put("qualityAdapterVersion", PopulationRuntimeRouting.VERSION)
                    .put("policiesSha256", adapter.canonicalSha256(mapper.valueToTree(records)))
                    .put("nativeSourceSha256", adapter.canonicalSha256(Bs1NativeFilterSupplement.contract(mapper)))
                    .put("jointFilterSourceSha256", adapter.canonicalSha256(Bs1JointFilterSource.contract(mapper)))
                    .put("localExomesAdapterVersion", Bs1LocalExomes.VERSION)
                    .put("exomesSexObservationVersion", GnomadSexStratifiedObservation.VERSION)
                    .put("exomesSexHeaderSha256", GnomadSexStratifiedObservation.HEADER_SHA256).put("nativeSupplementEnabled", false);
            result.set("sourceRouting", PopulationRuntimeRouting.development(mapper, new UnusedProvider("EXOMES"), new UnusedProvider("JOINT")).identity());
        } else {
            result.put("schemaVersion", "bs1-runtime-identity-v1").put("evaluatorVersion", Bs1CriterionEvaluator.VERSION)
                    .put("qualityAdapterVersion", Bs1JointFilterSource.VERSION)
                    .put("jointFilterSourceSha256", adapter.canonicalSha256(Bs1JointFilterSource.contract(mapper)))
                    .put("contextVersion", "evidence-context-v3").put("observationVersion", PopulationObservation.VERSION)
                    .put("policiesSha256", adapter.canonicalSha256(mapper.valueToTree(records)))
                    .put("inputProfileSha256", GnomadPopulationInputProfile.loadExecutableV2().getProfileSha256())
                    .put("nativeSourceSha256", adapter.canonicalSha256(Bs1NativeFilterSupplement.contract(mapper)))
                    .put("nativeSupplementEnabled", false).put("formalEnabled", false);
        }
        if (records.stream().anyMatch(Bs1SexChromosomePolicy::isJoint)) result.put("jointSexStatisticVersion", Bs1SexChromosomeStatistic.JOINT_VERSION)
                .put("jointSexObservationVersion", GnomadSexStratifiedObservation.JOINT_VERSION)
                .put("jointSexHeaderSha256", GnomadSexStratifiedObservation.JOINT_HEADER_SHA256)
                .put("jointSexFilterSourceSha256", adapter.canonicalSha256(Bs1JointFilterSource.sexContract(mapper)));
        return result;
    }

    private static final class UnusedProvider implements VariantEvidenceProvider {
        private final String dataset;
        UnusedProvider(String dataset) { this.dataset = dataset; }
        @Override public String source() { return "GNOMAD"; }
        @Override public String version() { return "SYNTHETIC_" + dataset; }
        @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext analysis) {
            throw new AssertionError("Identity projection must not acquire population facts");
        }
    }
}
