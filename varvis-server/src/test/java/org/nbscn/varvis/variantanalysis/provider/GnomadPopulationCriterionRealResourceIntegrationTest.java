package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in E3-P1A smoke against two public gnomAD v4.1 joint exact alleles.
 * All context is synthetic and this test is not connected to patient analysis.
 */
class GnomadPopulationCriterionRealResourceIntegrationTest {

    private static final String RUN_PROPERTY = "varvis.e3p1a.runtime-smoke";
    private static final String OUTPUT_PROPERTY = "varvis.e3p1a.smoke-output";
    private static final String TABIX_PROPERTY = "varvis.e3p1a.tabix-executable";
    private static final String SELECTION_ID = "cspec-selection-sha256-"
            + "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String BA1 = "ACMG_AMP_2015:BA1";
    private static final String BS1 = "ACMG_AMP_2015:BS1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    void defaultCheckLoadsOnlyTheIsolatedProfileWithoutQueryingAResource() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();

        assertTrue(profile.isCriterionEvaluationEnabled());
        assertTrue(profile.getDocument().path("activation").path("runtimeBound").asBoolean());
        assertFalse(profile.getDocument().path("activation").path("patientFlowEnabled").asBoolean(true));
        assertFalse(profile.getDocument().path("activation").path("classificationEnabled").asBoolean(true));
        assertEquals(0, profile.getDocument().path("evaluation").path("criteria").get(1)
                .path("frequencyRule").path("thresholdSnapshot").path("records").size());
    }

    @Test
    @EnabledIfSystemProperty(named = RUN_PROPERTY, matches = "true")
    void publicJointBatchFlowsThroughProviderCarrierAdapterAndBa1Bs1Evaluator() throws Exception {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
        VariantAnalysisProperties properties = properties(profile);
        CountingGnomadProvider provider = new CountingGnomadProvider(properties, profile);
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                profile.getDocument(), profile.getPopulationResourceId());

        NormalizedVariantKey hitKey = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "1", 12591, "C", "T");
        NormalizedVariantKey noRecordKey = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "1", 12591, "C", "A");
        ObjectNode hitContext = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, hitKey);
        ObjectNode noRecordContext = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, noRecordKey);
        Map<NormalizedVariantKey, JsonNode> contexts = new LinkedHashMap<>();
        contexts.put(hitKey, hitContext);
        contexts.put(noRecordKey, noRecordContext);
        List<VariantInterpretation> variants = List.of(
                RuntimeEvidenceV2ProviderWiringTest.variant(hitKey),
                RuntimeEvidenceV2ProviderWiringTest.variant(noRecordKey));
        AnalysisContext analysisContext = RuntimeEvidenceV2ProviderWiringTest.analysisContext(hitKey);

        Map<NormalizedVariantKey, List<ObjectNode>> evidenceByAllele =
                wiring.annotateVariantProviderBatch(
                        contexts, provider, variants, analysisContext, binding);

        assertEquals(1, provider.getBatchInvocationCount());
        assertEquals(hitKey.getChromosome(), noRecordKey.getChromosome());
        ObjectNode hit = only(evidenceByAllele, hitKey);
        ObjectNode noRecord = only(evidenceByAllele, noRecordKey);
        assertAtomic(hit, hitContext, binding, "HIT");
        assertAtomic(noRecord, noRecordContext, binding, "NO_RECORD");
        assertEquals("true", measurement(hit, "gnomad.exactAlleleMatch").path("rawValue").asText());
        assertEquals("PASS", measurement(hit, "gnomad.joint.filter").path("rawValue").asText());
        BigDecimal frequency = new BigDecimal(
                measurement(hit, "gnomad.joint.faf95max").path("rawValue").asText());
        assertTrue(frequency.compareTo(new BigDecimal("0.05")) < 0);
        String ancestry = measurement(hit, "gnomad.joint.faf95max.ancestry")
                .path("rawValue").asText();
        BigInteger ancestryAn = new BigInteger(measurement(
                hit, "gnomad.joint.ancestry." + ancestry + ".an").path("rawValue").asText());
        assertTrue(ancestryAn.compareTo(BigInteger.valueOf(2000L)) >= 0);
        assertEquals(0, noRecord.path("measurements").size());

        GnomadPopulationCriterionEvaluator evaluator =
                new GnomadPopulationCriterionEvaluator(mapper);
        ObjectNode registry = profile.getSuccessorRegistryDocument();
        List<ObjectNode> hitEvaluations = evaluator.evaluate(
                hitContext, hit, profile, registry, SELECTION_ID);
        List<ObjectNode> noRecordEvaluations = evaluator.evaluate(
                noRecordContext, noRecord, profile, registry, SELECTION_ID);

        assertEvaluation(hitEvaluations, BA1, "NOT_MET", null, "COMPLETED");
        assertEvaluation(hitEvaluations, BS1, "NOT_EVALUATED",
                "BS1_THRESHOLD_NOT_FOUND", "NOT_RUN");
        assertEvaluation(noRecordEvaluations, BA1, "NOT_EVALUATED",
                "NO_RECORD_IS_NOT_FREQUENCY_ZERO", "NOT_RUN");
        assertEvaluation(noRecordEvaluations, BS1, "NOT_EVALUATED",
                "NO_RECORD_IS_NOT_FREQUENCY_ZERO", "NOT_RUN");

        ObjectNode report = report(profile, binding, hitContext, hit, hitEvaluations,
                noRecordContext, noRecord, noRecordEvaluations);
        String serialized = mapper.writeValueAsString(report);
        assertNoLocalPath(serialized);
        writeReportIfRequested(report);
    }

    private VariantAnalysisProperties properties(GnomadPopulationInputProfile profile) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getGnomad().setEnabled(true);
        properties.getGnomad().setPopulationInputEnabled(true);
        properties.getGnomad().setAllowFullVcfFallback(false);
        properties.getGnomad().setAllowVcfInfoFallback(false);
        properties.getGnomad().setParallelism(1);
        properties.getGnomad().setTimeout(Duration.ofMinutes(3));
        properties.getGnomad().setVersion(profile.getDocument().path("resources").get(0)
                .path("resourceVersion").asText());
        String tabix = System.getProperty(TABIX_PROPERTY, "tabix").trim();
        assertFalse(tabix.isEmpty(), TABIX_PROPERTY + " must name the existing Tabix executable");
        properties.getGnomad().setTabixExecutable(tabix);
        return properties;
    }

    private ObjectNode only(Map<NormalizedVariantKey, List<ObjectNode>> evidence,
                            NormalizedVariantKey key) {
        List<ObjectNode> values = evidence.get(key);
        assertNotNull(values);
        assertEquals(1, values.size());
        return values.get(0);
    }

    private void assertAtomic(ObjectNode atomic,
                              ObjectNode context,
                              RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                              String queryStatus) {
        assertEquals("atomic-evidence-v2", atomic.path("schemaVersion").asText());
        assertEquals(context.path("contextId").asText(), atomic.path("contextId").asText());
        assertEquals(binding.getResourceId(), atomic.path("source").path("sourceId").asText());
        assertEquals(binding.getResourceVersion(),
                atomic.path("source").path("resourceVersion").asText());
        assertEquals(binding.getIdentityFingerprintSha256(),
                atomic.path("source").path("resourceSha256").asText());
        assertEquals("SUCCEEDED", atomic.path("resourceExecution").path("status").asText());
        assertEquals("AVAILABLE", atomic.path("resourceAvailability").path("status").asText());
        assertEquals(queryStatus, atomic.path("queryOutcome").path("status").asText());
        assertEquals("APPLICABLE", atomic.path("resourceApplicability").path("status").asText());
    }

    private JsonNode measurement(ObjectNode atomic, String metricId) {
        for (JsonNode value : atomic.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) return value;
        }
        throw new AssertionError("Missing measurement " + metricId);
    }

    private void assertEvaluation(List<ObjectNode> evaluations,
                                  String criterionId,
                                  String expectedStatus,
                                  String expectedReason,
                                  String expectedExecution) {
        ObjectNode value = evaluations.stream()
                .filter(item -> criterionId.equals(item.path("criterionId").asText()))
                .findFirst().orElseThrow();
        assertEquals("criterion-evaluation-v2", value.path("schemaVersion").asText());
        assertEquals(expectedStatus, value.path("evaluationResult").path("status").asText());
        if (expectedReason != null) {
            assertEquals(expectedReason,
                    value.path("evaluationResult").path("reasonCode").asText());
        }
        assertEquals(expectedExecution,
                value.path("deterministicTrace").path("executionStatus").asText());
    }

    private ObjectNode report(GnomadPopulationInputProfile profile,
                              RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                              ObjectNode hitContext,
                              ObjectNode hit,
                              List<ObjectNode> hitEvaluations,
                              ObjectNode noRecordContext,
                              ObjectNode noRecord,
                              List<ObjectNode> noRecordEvaluations) {
        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", "e3-p1a-gnomad-population-criterion-smoke-v1");
        report.put("status", "PASS");
        report.put("contextDataClass", "SYNTHETIC");
        report.put("resourceDataClass", "PUBLIC");
        report.put("clinicalValidation", false);
        report.put("patientFlowEnabled", false);
        report.put("classificationEnabled", false);
        report.put("providerBatchInvocationCount", 1);
        ObjectNode profileSummary = report.putObject("profile");
        profileSummary.put("profileId", profile.getProfileId());
        profileSummary.put("profileVersion", profile.getProfileVersion());
        profileSummary.put("profileSha256", profile.getProfileSha256());
        ObjectNode resource = report.putObject("resource");
        resource.put("resourceId", binding.getResourceId());
        resource.put("resourceVersion", binding.getResourceVersion());
        resource.put("assembly", binding.getAssembly());
        resource.put("identityFingerprintSha256", binding.getIdentityFingerprintSha256());
        ArrayNode cases = report.putArray("cases");
        cases.add(caseValue("PASS_HIT_BA1_NOT_MET", hitContext, hit, hitEvaluations));
        cases.add(caseValue("EXACT_ALT_NO_RECORD", noRecordContext, noRecord, noRecordEvaluations));
        return report;
    }

    private ObjectNode caseValue(String caseId,
                                 ObjectNode context,
                                 ObjectNode atomic,
                                 List<ObjectNode> evaluations) {
        ObjectNode value = mapper.createObjectNode();
        value.put("caseId", caseId);
        value.set("context", context.deepCopy());
        value.set("atomicEvidence", atomic.deepCopy());
        value.set("criterionEvaluations", mapper.valueToTree(evaluations));
        return value;
    }

    private void assertNoLocalPath(String serialized) {
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
        assertFalse(serialized.contains("file:\\"));
    }

    private void writeReportIfRequested(ObjectNode report) throws IOException {
        String configured = System.getProperty(OUTPUT_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) return;
        Path output = Paths.get(configured.trim());
        if (!output.isAbsolute()) output = Paths.get("").toAbsolutePath().resolve(output);
        output = output.normalize();
        Path target = Paths.get("target").toAbsolutePath().normalize();
        assertTrue(output.startsWith(target), OUTPUT_PROPERTY + " must remain below varvis-server/target");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private static final class CountingGnomadProvider extends GnomadTabixProvider {
        private int batchInvocationCount;

        private CountingGnomadProvider(VariantAnalysisProperties properties,
                                       GnomadPopulationInputProfile profile) {
            super(properties, profile);
        }

        @Override
        public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                List<VariantInterpretation> variants, AnalysisContext context) {
            batchInvocationCount++;
            return super.annotate(variants, context);
        }

        private int getBatchInvocationCount() {
            return batchInvocationCount;
        }
    }
}
