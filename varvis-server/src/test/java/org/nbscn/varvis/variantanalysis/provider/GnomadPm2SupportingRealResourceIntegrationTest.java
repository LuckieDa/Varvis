package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in public-resource E3-P1B smoke; all variant context remains synthetic. */
class GnomadPm2SupportingRealResourceIntegrationTest {

    private static final String RUN_PROPERTY = "varvis.e3p1b.runtime-smoke";
    private static final String TABIX_PROPERTY = "varvis.e3p1b.tabix-executable";
    private static final String HAIL_COMMAND_FILE_PROPERTY = "varvis.e3p1b.hail-command-file";
    private static final String OUTPUT_PROPERTY = "varvis.e3p1b.smoke-output";
    private static final String SELECTION = "cspec-selection-sha256-"
            + "0000000000000000000000000000000000000000000000000000000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    void defaultCheckKeepsPm2OutsidePatientAndClassificationFlows() {
        GnomadPopulationInputProfile profile =
                GnomadPopulationInputProfile.loadExecutablePm2Supporting();

        assertTrue(profile.getDocument().path("activation").path("runtimeBound").asBoolean());
        assertTrue(profile.getDocument().path("activation").path("evaluationEnabled").asBoolean());
        assertFalse(profile.getDocument().path("activation").path("patientFlowEnabled").asBoolean(true));
        assertFalse(profile.getDocument().path("activation").path("classificationEnabled").asBoolean(true));
        assertTrue(profile.getDocument().path("evaluation").path("criteria").get(2)
                .path("populationRule").path("policySnapshot").path("records").isEmpty());
    }

    @Test
    @EnabledIfSystemProperty(named = RUN_PROPERTY, matches = "true")
    void publicJointAbsenceAndAllSitesHitsFlowToPolicyGatedPm2() throws Exception {
        GnomadPopulationInputProfile profile =
                GnomadPopulationInputProfile.loadExecutablePm2Supporting();
        RuntimeEvidenceV2ProviderWiring wiring =
                new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        NormalizedVariantKey key = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "1", 12591, "C", "A");
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key);
        VariantInterpretation variant = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        AnalysisContext analysisContext = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);

        RuntimeEvidenceV2ProviderWiring.ResourceBinding jointBinding = wiring.bind(
                profile.getDocument(), GnomadPopulationInputProfile.JOINT_RESOURCE_ID);
        GnomadTabixProvider jointProvider = new GnomadTabixProvider(
                jointProperties(profile), profile);
        Map<NormalizedVariantKey, JsonNode> contexts = Map.of(key, context);
        ObjectNode joint = only(wiring.annotateVariantProviderBatch(
                contexts, jointProvider, List.of(variant), analysisContext, jointBinding), key);
        assertEquals("NO_RECORD", joint.path("queryOutcome").path("status").asText());
        assertTrue(joint.path("measurements").isEmpty());

        GnomadAllSitesHailBatchRunner runner = new GnomadAllSitesHailBatchRunner(
                mapper, hailBaseCommand(), targetDirectory(), Duration.ofMinutes(20), 500);
        List<ObjectNode> allSites = wiring.annotateGnomadAllSitesBatch(
                contexts, List.of(variant), runner, profile).get(key);
        assertNotNull(allSites);
        assertEquals(2, allSites.size());
        ObjectNode exomes = bySource(allSites,
                GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID);
        ObjectNode genomes = bySource(allSites,
                GnomadPopulationInputProfile.GENOME_ALL_SITES_RESOURCE_ID);
        assertEquals("HIT", exomes.path("queryOutcome").path("status").asText());
        assertEquals("HIT", genomes.path("queryOutcome").path("status").asText());
        assertTrue(new java.math.BigInteger(measurement(exomes,
                "gnomad.exomes.allSites.adjAn").path("rawValue").asText()).signum() > 0);
        assertTrue(new java.math.BigInteger(measurement(genomes,
                "gnomad.genomes.allSites.adjAn").path("rawValue").asText()).signum() > 0);

        ObjectNode evaluation = new GnomadPopulationCriterionEvaluator(mapper)
                .evaluatePm2Supporting(context, joint, exomes, genomes, profile,
                        profile.getSuccessorRegistryDocument(), SELECTION);
        assertEquals("NOT_EVALUATED",
                evaluation.path("evaluationResult").path("status").asText());
        assertEquals("PM2_POLICY_NOT_FOUND_OR_AMBIGUOUS",
                evaluation.path("evaluationResult").path("reasonCode").asText());

        ObjectNode report = report(profile, context, joint, exomes, genomes, evaluation);
        assertNoMachinePath(mapper.writeValueAsString(report));
        writeReport(report);
    }

    private VariantAnalysisProperties jointProperties(GnomadPopulationInputProfile profile) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getGnomad().setEnabled(true);
        properties.getGnomad().setPopulationInputEnabled(true);
        properties.getGnomad().setAllowFullVcfFallback(false);
        properties.getGnomad().setAllowVcfInfoFallback(false);
        properties.getGnomad().setParallelism(1);
        properties.getGnomad().setTimeout(Duration.ofMinutes(3));
        properties.getGnomad().setVersion(profile.getDocument().path("resources").get(0)
                .path("resourceVersion").asText());
        String tabix = requiredProperty(TABIX_PROPERTY);
        properties.getGnomad().setTabixExecutable(tabix);
        return properties;
    }

    private List<String> hailBaseCommand() throws Exception {
        Path commandFile = Paths.get(requiredProperty(HAIL_COMMAND_FILE_PROPERTY))
                .toAbsolutePath().normalize();
        List<String> command = mapper.readValue(commandFile.toFile(),
                new TypeReference<List<String>>() {});
        assertFalse(command.isEmpty());
        return command;
    }

    private ObjectNode only(Map<NormalizedVariantKey, List<ObjectNode>> values,
                            NormalizedVariantKey key) {
        List<ObjectNode> evidence = values.get(key);
        assertNotNull(evidence);
        assertEquals(1, evidence.size());
        return evidence.get(0);
    }

    private ObjectNode bySource(List<ObjectNode> values, String sourceId) {
        return values.stream().filter(value -> sourceId.equals(
                        value.path("source").path("sourceId").asText()))
                .findFirst().orElseThrow();
    }

    private JsonNode measurement(ObjectNode evidence, String metricId) {
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) return value;
        }
        throw new AssertionError("Missing measurement " + metricId);
    }

    private ObjectNode report(GnomadPopulationInputProfile profile,
                              ObjectNode context,
                              ObjectNode joint,
                              ObjectNode exomes,
                              ObjectNode genomes,
                              ObjectNode evaluation) {
        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", "e3-p1b-pm2-supporting-smoke-v1");
        report.put("status", "PASS");
        report.put("contextDataClass", "SYNTHETIC");
        report.put("resourceDataClass", "PUBLIC");
        report.put("clinicalValidation", false);
        report.put("patientFlowEnabled", false);
        report.put("classificationEnabled", false);
        report.put("hailBatchProcessCount", 1);
        report.put("validatedHailInitializationCount", 1);
        report.put("allSitesResourceQueryCount", 2);
        report.putObject("profile").put("profileId", profile.getProfileId())
                .put("profileVersion", profile.getProfileVersion())
                .put("profileSha256", profile.getProfileSha256());
        report.set("context", context.deepCopy());
        ObjectNode evidence = report.putObject("atomicEvidence");
        evidence.set("joint", joint.deepCopy());
        evidence.set("exomesAllSites", exomes.deepCopy());
        evidence.set("genomesAllSites", genomes.deepCopy());
        report.set("pm2SupportingEvaluation", evaluation.deepCopy());
        ArrayNode boundaries = report.putArray("boundaries");
        boundaries.add("PUBLIC_FIXED_LOCUS_ONLY");
        boundaries.add("NO_PRODUCTION_PM2_POLICY");
        boundaries.add("NOT_CLINICAL_VALIDATION");
        return report;
    }

    private void writeReport(ObjectNode report) throws Exception {
        String configured = requiredProperty(OUTPUT_PROPERTY);
        Path output = Paths.get(configured).toAbsolutePath().normalize();
        assertTrue(output.startsWith(targetDirectory()),
                OUTPUT_PROPERTY + " must remain below varvis-server/target");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private Path targetDirectory() {
        return Paths.get("target").toAbsolutePath().normalize();
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, name + " is required for the opt-in smoke");
        assertFalse(value.trim().isEmpty(), name + " must not be blank");
        return value.trim();
    }

    private void assertNoMachinePath(String serialized) {
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
        assertFalse(serialized.contains("file:\\"));
    }
}
