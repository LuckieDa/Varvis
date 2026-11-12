package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpliceAiRawRealResourceIntegrationTest {

    private static final String RUN_PROPERTY = "varvis.e3c2.runReal";
    private static final String COMMAND_FILE_PROPERTY = "varvis.e3c2.baseCommandFile";
    private static final String REFERENCE_PROPERTY = "varvis.e3c2.referenceFasta";
    private static final String WORKING_DIRECTORY_PROPERTY = "varvis.e3c2.workingDirectory";
    private static final String OUTPUT_PROPERTY = "varvis.e3c2.output";
    private static final String RESOURCE_ID =
            "SPLICEAI_V1_3_1_RAW_GRCH38_LOCAL_COMPUTE";
    private static final String SELECTION_ID = "cspec-selection-sha256-"
            + "0000000000000000000000000000000000000000000000000000000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    void defaultCheckLoadsAndBindsProfileWithoutStartingSpliceAi() throws Exception {
        ObjectNode profile = profile();
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding =
                new RuntimeEvidenceV2ProviderWiring(mapper, adapter).bind(profile, RESOURCE_ID);

        assertEquals("SPLICEAI", binding.getProvider());
        assertEquals("GRCh38", binding.getAssembly());
        assertEquals("SpliceAI 1.3.1 raw, distance 4999", binding.getResourceVersion());
        assertTrue(profile.path("activation").path("runtimeBound").asBoolean());
        assertTrue(profile.path("activation").path("evaluationEnabled").asBoolean());
        assertFalse(profile.path("activation").path("patientFlowEnabled").asBoolean(true));
        assertFalse(profile.path("activation").path("classificationEnabled").asBoolean(true));
    }

    @Test
    @EnabledIfSystemProperty(named = RUN_PROPERTY, matches = "true")
    void publicFixedPointFlowsThroughRawProviderWiringAndAdapter() throws Exception {
        ObjectNode profile = profile();
        RuntimeEvidenceV2ProviderWiring wiring =
                new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(profile, RESOURCE_ID);
        NormalizedVariantKey key = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "1", 925775, "G", "T");
        ObjectNode context = context(key);
        VariantInterpretation variant = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        variant.applyTranscriptAnnotations(List.of(selectedTranscript()));
        SpliceAiRawBatchRunner runner = new SpliceAiRawBatchRunner(
                mapper, baseCommand(), reference(), workingDirectory(),
                Duration.ofMinutes(10), 10);

        List<ObjectNode> evidence = wiring.annotateSpliceAiRawBatch(
                Map.of(key, context),
                new SpliceAiProvider(new VariantAnalysisProperties()),
                List.of(variant),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                binding,
                runner).get(key);

        assertNotNull(evidence);
        assertEquals(1, evidence.size());
        ObjectNode atomic = evidence.get(0);
        assertEquals("atomic-evidence-v2", atomic.path("schemaVersion").asText());
        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertEquals("APPLICABLE",
                atomic.path("resourceApplicability").path("status").asText());
        assertEquals("0.96", measurement(atomic, "spliceai.maximumRawDeltaScore")
                .path("rawValue").asText());
        assertEquals("0.40", measurement(atomic, "spliceai.DS_AG")
                .path("rawValue").asText());
        assertEquals("SAMD11", measurement(atomic, "spliceai.geneSymbol")
                .path("rawValue").asText());
        List<ObjectNode> evaluations = new SpliceAiCriterionEvaluator(mapper).evaluate(
                context,
                atomic,
                "splice_region_variant",
                profile,
                SpliceAiCriterionEvaluator.loadRuleRegistry(mapper),
                SELECTION_ID);
        ObjectNode pp3 = criterion(evaluations, "ACMG_AMP_2015:PP3");
        ObjectNode bp4 = criterion(evaluations, "ACMG_AMP_2015:BP4");
        assertEquals("MET", pp3.path("evaluationResult").path("status").asText());
        assertEquals("SUPPORTING", pp3.path("appliedStrength").asText());
        assertEquals("NOT_MET", bp4.path("evaluationResult").path("status").asText());
        assertEquals("NONE", bp4.path("appliedStrength").asText());
        assertNoMachinePath(mapper.writeValueAsString(atomic));
        writeIfRequested(profile, context, atomic, evaluations);
    }

    private ObjectNode profile() throws Exception {
        return SpliceAiCriterionEvaluator.loadProfile(mapper);
    }

    private List<String> baseCommand() throws Exception {
        Path commandFile = Paths.get(requiredProperty(COMMAND_FILE_PROPERTY))
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(commandFile));
        List<String> command = mapper.readValue(
                commandFile.toFile(), new TypeReference<List<String>>() {});
        assertFalse(command.isEmpty());
        return command;
    }

    private Path reference() {
        Path value = Paths.get(requiredProperty(REFERENCE_PROPERTY)).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(value));
        return value;
    }

    private Path workingDirectory() {
        Path value = Paths.get(requiredProperty(WORKING_DIRECTORY_PROPERTY))
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(value));
        return value;
    }

    private ObjectNode context(NormalizedVariantKey key) throws Exception {
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key, "ENST00000342066");
        ObjectNode gene = (ObjectNode) context.path("gene");
        gene.put("hgncId", "HGNC:28706");
        gene.put("symbol", "SAMD11");
        ObjectNode transcript = (ObjectNode) context.path("transcript");
        transcript.put("version", 8);
        transcript.put("selectionPolicy", "SYNTHETIC_PINNED_TRANSCRIPT");
        transcript.put("annotationSourceVersion", "116.0");

        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", context.path("variant").path("alleleId").asText());
        projection.put("hgncId", gene.path("hgncId").asText());
        projection.put("mondoId", context.path("disease").path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode transcriptIdentity = projection.putObject("transcript");
        transcriptIdentity.put("accession", transcript.path("accession").asText());
        transcriptIdentity.put("version", transcript.path("version").asInt());
        projection.put("patientContextId",
                context.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId",
                context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
        return context;
    }

    private TranscriptConsequence selectedTranscript() {
        return new TranscriptConsequence(
                "SAMD11", "HGNC:28706", "ENST00000342066.8",
                "ENST00000342066.8:c.100+5G>T", null, "splice_region_variant",
                "HGNC:28706", "ENST00000342066.8", "Ensembl", "LOW", null, "1/1",
                true, true, null, null, null, null,
                null, null, null, "VEP_SYNTHETIC", "COMPLETED", "116.0",
                Instant.parse("2026-09-02T00:00:00Z"));
    }

    private JsonNode measurement(ObjectNode evidence, String metricId) {
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) return value;
        }
        throw new AssertionError("Missing measurement " + metricId);
    }

    private ObjectNode criterion(List<ObjectNode> evaluations, String criterionId) {
        return evaluations.stream()
                .filter(value -> criterionId.equals(value.path("criterionId").asText()))
                .findFirst().orElseThrow();
    }

    private void writeIfRequested(ObjectNode profile,
                                  ObjectNode context,
                                  ObjectNode atomic,
                                  List<ObjectNode> evaluations)
            throws Exception {
        String configured = System.getProperty(OUTPUT_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) return;
        Path output = Paths.get(configured.trim()).toAbsolutePath().normalize();
        Path target = Paths.get("target").toAbsolutePath().normalize();
        assertTrue(output.startsWith(target), OUTPUT_PROPERTY + " must remain below target");
        Files.createDirectories(output.getParent());
        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", "e3-c2-spliceai-raw-smoke-v1");
        report.put("status", "PASS");
        report.put("contextDataClass", "SYNTHETIC");
        report.put("resourceDataClass", "PUBLIC_FIXED_POINT");
        report.put("clinicalValidation", false);
        report.put("patientFlowEnabled", false);
        report.put("classificationEnabled", false);
        report.put("rawBatchProcessCount", 1);
        report.putObject("profile")
                .put("profileId", profile.path("profileId").asText())
                .put("profileVersion", profile.path("profileVersion").asText());
        report.set("context", context.deepCopy());
        report.set("atomicEvidence", atomic.deepCopy());
        report.set("criterionEvaluations", mapper.valueToTree(evaluations));
        assertNoMachinePath(mapper.writeValueAsString(report));
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
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
