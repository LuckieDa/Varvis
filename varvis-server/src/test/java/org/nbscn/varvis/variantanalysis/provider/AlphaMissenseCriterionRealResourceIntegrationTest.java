package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in E3-C1 fixed-point smoke against one public AlphaMissense GRCh38 record.
 * The interpretation context is synthetic and this is not clinical validation.
 */
class AlphaMissenseCriterionRealResourceIntegrationTest {

    private static final String RUN_PROPERTY = "varvis.e3c1.runtime-smoke";
    private static final String RESOURCE_PROPERTY = "varvis.e3c1.alphamissense-grch38";
    private static final String OUTPUT_PROPERTY = "varvis.e3c1.smoke-output";
    private static final String RESOURCE_ID = "ALPHAMISSENSE_2023_08_03_GRCH38_TABIX";
    private static final String TRANSCRIPT_ACCESSION = "ENST00000335137";
    private static final int TRANSCRIPT_VERSION = 4;
    private static final String VERSIONED_TRANSCRIPT = "ENST00000335137.4";
    private static final String PROTEIN_CHANGE = "V2L";
    private static final String PROTEIN_ID = "Q8NH21";
    private static final String EXPECTED_SCORE = "0.2937";
    private static final String SELECTION_ID = "cspec-selection-sha256-"
            + "0000000000000000000000000000000000000000000000000000000000000000";
    private static final Pattern WINDOWS_ABSOLUTE_PATH =
            Pattern.compile("(?i)(?:^|[\\\"\\s])[a-z]:[\\\\/]");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    void defaultCheckLoadsAndBindsTheProfileWithoutAccessingTheLocalResource() {
        ObjectNode profile = AlphaMissenseCriterionEvaluator.loadProfile(mapper);
        ObjectNode registry = AlphaMissenseCriterionEvaluator.loadSuccessorRegistry(mapper);
        RuntimeEvidenceV2ProviderWiring wiring =
                new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding =
                wiring.bind(profile, RESOURCE_ID);

        assertEquals(RESOURCE_ID, binding.getResourceId());
        assertEquals("ALPHAMISSENSE", binding.getProvider());
        assertEquals("GRCh38", binding.getAssembly());
        assertEquals("2023-08-03", binding.getResourceVersion());
        assertEquals("acmg-rule-registry-e3-c1-2026-09-02",
                registry.path("registryId").asText());
        assertTrue(profile.path("activation").path("runtimeBound").asBoolean());
        assertTrue(profile.path("activation").path("evaluationEnabled").asBoolean());
        assertFalse(profile.path("activation").path("patientFlowEnabled").asBoolean(true));
        assertFalse(profile.path("activation").path("classificationEnabled").asBoolean(true));
    }

    @Test
    @EnabledIfSystemProperty(named = RUN_PROPERTY, matches = "true")
    void publicFixedPointFlowsThroughProviderWiringAdapterAndEvaluator() throws Exception {
        ObjectNode profile = AlphaMissenseCriterionEvaluator.loadProfile(mapper);
        ObjectNode registry = AlphaMissenseCriterionEvaluator.loadSuccessorRegistry(mapper);
        RuntimeEvidenceV2ProviderWiring wiring =
                new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding =
                wiring.bind(profile, RESOURCE_ID);

        Path resource = configuredResource();
        VariantAnalysisProperties properties = properties(binding, resource);
        AlphaMissenseProvider provider = new AlphaMissenseProvider(properties);
        NormalizedVariantKey key = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "1", 69094, "G", "T");
        ObjectNode context = syntheticContext(key);
        VariantInterpretation variant = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        variant.applyTranscriptAnnotations(List.of(selectedTranscript()));
        AnalysisContext analysisContext = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);

        List<ObjectNode> evidence = wiring.annotateVariantProvider(
                context, provider, variant, analysisContext, binding);
        assertEquals(1, evidence.size());
        ObjectNode atomic = evidence.get(0);
        assertAtomic(atomic, context, binding);

        AlphaMissenseCriterionEvaluator evaluator =
                new AlphaMissenseCriterionEvaluator(mapper);
        List<ObjectNode> evaluations = evaluator.evaluate(
                context, atomic, profile, registry, SELECTION_ID);
        assertEvaluation(evaluations, "ACMG_AMP_2015:PP3");
        assertEvaluation(evaluations, "ACMG_AMP_2015:BP4");

        ObjectNode report = report(profile, binding, context, atomic, evaluations);
        String serialized = mapper.writeValueAsString(report);
        assertNoLocalPath(serialized);
        writeReportIfRequested(report);
    }

    private Path configuredResource() {
        String configured = System.getProperty(RESOURCE_PROPERTY);
        assertNotNull(configured, RESOURCE_PROPERTY + " must identify the local BGZF file");
        assertFalse(configured.trim().isEmpty(),
                RESOURCE_PROPERTY + " must identify the local BGZF file");
        Path resource = Paths.get(configured.trim()).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(resource), RESOURCE_PROPERTY + " is not a readable file");
        Path index = resource.resolveSibling(resource.getFileName() + ".tbi");
        assertTrue(Files.isRegularFile(index), RESOURCE_PROPERTY + " has no Tabix index");
        return resource;
    }

    private VariantAnalysisProperties properties(
            RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
            Path resource) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getAlphaMissense().setEnabled(true);
        properties.getAlphaMissense().setVersion(binding.getResourceVersion());
        properties.getAlphaMissense().setGrch38File(resource.toString());
        properties.getAlphaMissense().setTimeout(Duration.ofSeconds(30));
        return properties;
    }

    private ObjectNode syntheticContext(NormalizedVariantKey key) throws IOException {
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key, TRANSCRIPT_ACCESSION);
        ObjectNode transcript = (ObjectNode) context.path("transcript");
        transcript.put("version", TRANSCRIPT_VERSION);
        transcript.put("selectionPolicy", "SYNTHETIC_PINNED_TRANSCRIPT");
        transcript.put("annotationSourceVersion", "116.0");

        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", context.path("variant").path("alleleId").asText());
        projection.put("hgncId", context.path("gene").path("hgncId").asText());
        projection.put("mondoId", context.path("disease").path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode transcriptIdentity = projection.putObject("transcript");
        transcriptIdentity.put("accession", TRANSCRIPT_ACCESSION);
        transcriptIdentity.put("version", TRANSCRIPT_VERSION);
        projection.put("patientContextId",
                context.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId",
                context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
        return context;
    }

    private TranscriptConsequence selectedTranscript() {
        return new TranscriptConsequence(
                "SYNTH1", "HGNC:1", TRANSCRIPT_ACCESSION,
                VERSIONED_TRANSCRIPT + ":c.4G>T", PROTEIN_ID + ":p.Val2Leu",
                "missense_variant", "HGNC:1", TRANSCRIPT_ACCESSION,
                "protein_coding", "MODERATE", "1/1", null,
                true, true, null, "2", "V/L", "Gtg/Ttg",
                null, null, null, "VEP_SYNTHETIC", "COMPLETED", "116.0",
                Instant.parse("2026-09-02T00:00:00Z")
        );
    }

    private void assertAtomic(
            ObjectNode atomic,
            ObjectNode context,
            RuntimeEvidenceV2ProviderWiring.ResourceBinding binding) {
        assertEquals("atomic-evidence-v2", atomic.path("schemaVersion").asText());
        assertEquals(context.path("contextId").asText(), atomic.path("contextId").asText());
        assertEquals(binding.getResourceId(), atomic.path("source").path("sourceId").asText());
        assertEquals(binding.getResourceVersion(),
                atomic.path("source").path("resourceVersion").asText());
        assertEquals(binding.getIdentityFingerprintSha256(),
                atomic.path("source").path("resourceSha256").asText());
        assertEquals("SUCCEEDED", atomic.path("resourceExecution").path("status").asText());
        assertEquals("AVAILABLE", atomic.path("resourceAvailability").path("status").asText());
        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertEquals(1, atomic.path("queryOutcome").path("recordCount").asInt());
        assertEquals("APPLICABLE",
                atomic.path("resourceApplicability").path("status").asText());
        assertEquals(EXPECTED_SCORE,
                measurement(atomic, "alphamissense.amPathogenicity")
                        .path("rawValue").asText());
        assertEquals(VERSIONED_TRANSCRIPT,
                measurement(atomic, "alphamissense.transcriptId")
                        .path("rawValue").asText());
        assertEquals(PROTEIN_CHANGE,
                measurement(atomic, "alphamissense.proteinVariant")
                        .path("rawValue").asText());
        assertEquals(PROTEIN_ID,
                measurement(atomic, "alphamissense.uniprotId")
                        .path("rawValue").asText());
        assertTrue(measurement(atomic, "alphamissense.exactAlleleMatch")
                .path("rawValue").asBoolean());
        assertTrue(measurement(atomic, "alphamissense.transcriptProteinMatch")
                .path("rawValue").asBoolean());
    }

    private JsonNode measurement(ObjectNode atomic, String metricId) {
        for (JsonNode value : atomic.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) return value;
        }
        throw new AssertionError("Missing measurement " + metricId);
    }

    private void assertEvaluation(List<ObjectNode> evaluations, String criterionId) {
        ObjectNode evaluation = evaluations.stream()
                .filter(value -> criterionId.equals(value.path("criterionId").asText()))
                .findFirst().orElseThrow();
        assertEquals("criterion-evaluation-v2", evaluation.path("schemaVersion").asText());
        assertEquals("APPLICABLE",
                evaluation.path("criterionApplicability").path("status").asText());
        assertEquals("NOT_MET", evaluation.path("evaluationResult").path("status").asText());
        assertEquals("SUPPORTING", evaluation.path("baseStrength").asText());
        assertEquals("NONE", evaluation.path("appliedStrength").asText());
        assertEquals("COMPLETED",
                evaluation.path("deterministicTrace").path("executionStatus").asText());
    }

    private ObjectNode report(
            ObjectNode profile,
            RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
            ObjectNode context,
            ObjectNode atomic,
            List<ObjectNode> evaluations) {
        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", "e3-c1-alphamissense-pp3-bp4-smoke-v1");
        report.put("status", "PASS");
        report.put("resourceDataClass", "PUBLIC");
        report.put("contextDataClass", "SYNTHETIC");
        report.put("clinicalValidation", false);
        report.put("patientFlowEnabled", false);
        report.put("classificationEnabled", false);
        ArrayNode chain = report.putArray("runtimeChain");
        chain.add("AlphaMissenseProvider");
        chain.add("RuntimeEvidenceV2ProviderWiring");
        chain.add("RuntimeEvidenceV2Adapter");
        chain.add("atomic-evidence-v2");
        chain.add("AlphaMissenseCriterionEvaluator");
        ObjectNode profileSummary = report.putObject("profile");
        profileSummary.put("profileId", profile.path("profileId").asText());
        profileSummary.put("profileVersion", profile.path("profileVersion").asText());
        profileSummary.put("profileSha256", adapter.canonicalSha256(profile));
        ObjectNode resource = report.putObject("resource");
        resource.put("resourceId", binding.getResourceId());
        resource.put("resourceVersion", binding.getResourceVersion());
        resource.put("assembly", binding.getAssembly());
        resource.put("identityFingerprintSha256", binding.getIdentityFingerprintSha256());
        ObjectNode fixedPoint = report.putObject("fixedPoint");
        fixedPoint.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        fixedPoint.put("selectedTranscript", VERSIONED_TRANSCRIPT);
        fixedPoint.put("proteinChange", PROTEIN_CHANGE);
        fixedPoint.put("expectedScore", EXPECTED_SCORE);
        fixedPoint.put("expectedPp3", "NOT_MET");
        fixedPoint.put("expectedBp4", "NOT_MET");
        report.set("context", context.deepCopy());
        report.set("atomicEvidence", atomic.deepCopy());
        report.set("criterionEvaluations", mapper.valueToTree(evaluations));
        return report;
    }

    private void assertNoLocalPath(String serialized) {
        assertFalse(WINDOWS_ABSOLUTE_PATH.matcher(serialized).find());
        assertFalse(serialized.contains("/mnt/"));
        assertFalse(serialized.contains("/home/"));
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
        assertTrue(output.startsWith(target),
                OUTPUT_PROPERTY + " must remain below varvis-server/target");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }
}
