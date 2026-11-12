package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in public fixed-point VEP-to-PVS1 smoke for the E3-L1A slice only. */
@EnabledIfSystemProperty(named = "varvis.e3l1a.runtime-smoke", matches = "true")
class Pvs1CriterionEvaluatorLocalIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @TempDir
    Path tempDirectory;

    @Test
    void publicHnf1aFixedPointProducesPvs1VeryStrong() throws Exception {
        VariantAnalysisProperties properties = localProperties();
        enableVep(properties);
        RuntimeEvidenceV2ProviderWiring wiring =
                new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                mapper.readTree(requiredManifest().toFile()), "VEP_116_GRCH38");
        VepTranscriptAnnotationProvider delegate = new VepTranscriptAnnotationProvider(properties);
        AtomicInteger invocations = new AtomicInteger();
        TranscriptAnnotationProvider provider = new TranscriptAnnotationProvider() {
            @Override
            public String source() {
                return delegate.source();
            }

            @Override
            public String version() {
                return delegate.version();
            }

            @Override
            public List<TranscriptConsequence> annotate(
                    VariantInterpretation variant, AnalysisContext context) {
                invocations.incrementAndGet();
                return delegate.annotate(variant, context);
            }
        };

        NormalizedVariantKey key = new NormalizedVariantKey(
                "GRCh38", "12", 120989032, "C", "T");
        ObjectNode context = hnf1aContext(key);
        ObjectNode atomic = wiring.annotateTranscriptProvider(
                context, provider, variant(key),
                new AnalysisContext("e3-l1a-public-fixed-point", GenomeAssembly.GRCh38,
                        AnalysisMode.WES), binding).get(0);
        Pvs1CriterionEvaluator evaluator = new Pvs1CriterionEvaluator(mapper);
        ObjectNode selection = evaluator.createZeroMatchSelection(context);
        ObjectNode evaluation = evaluator.evaluate(context, atomic, selection);

        assertEquals(1, invocations.get());
        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertEquals("ENST00000257555.11",
                measurement(atomic, "vep.selectedTranscriptId").path("rawValue").asText());
        assertTrue(List.of(measurement(atomic, "consequenceTerm").path("rawValue")
                .asText().split("&")).contains("stop_gained"));
        assertEquals("2/10", measurement(atomic, "vep.exon").path("rawValue").asText());
        assertEquals("ZERO_MATCH", selection.path("outcome").asText());
        assertEquals(0, selection.path("candidateOverlayIds").size());
        assertEquals("ACMG_AMP_2015:PVS1", evaluation.path("criterionId").asText());
        assertEquals("APPLICABLE",
                evaluation.path("criterionApplicability").path("status").asText());
        assertEquals("MET", evaluation.path("evaluationResult").path("status").asText());
        assertEquals("VERY_STRONG", evaluation.path("baseStrength").asText());
        assertEquals("VERY_STRONG", evaluation.path("appliedStrength").asText());
        assertFalse(evaluation.toString().contains("NOT_MET"));
        writeOutputs(binding, context, atomic, selection, evaluation, evaluator,
                invocations.get());
    }

    private void writeOutputs(RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                              ObjectNode context,
                              ObjectNode atomic,
                              ObjectNode selection,
                              ObjectNode evaluation,
                              Pvs1CriterionEvaluator evaluator,
                              int invocations) throws IOException {
        ObjectNode smoke = mapper.createObjectNode();
        smoke.put("schemaVersion", "e3-l1a-hnf1a-pvs1-criterion-smoke-v1");
        smoke.put("stage", "E3-L1A");
        smoke.put("status", "PASS");
        smoke.put("readiness", "E3_L1A_COMPLETED_PAUSED");
        smoke.put("dataClass", "PUBLIC_FIXED_POINT_WITH_SYNTHETIC_EVIDENCE_CONTEXT");
        ObjectNode boundary = smoke.putObject("boundary");
        boundary.put("exactFixedPointOnly", true);
        boundary.put("generalPvs1Implemented", false);
        boundary.put("patientFlowEnabled", false);
        boundary.put("classificationEnabled", false);
        boundary.put("patientDataUsed", false);
        boundary.put("clinicalValidationClaimed", false);

        ObjectNode fixedPoint = smoke.putObject("fixedPoint");
        fixedPoint.put("gene", "HNF1A");
        fixedPoint.put("hgncId", "HGNC:11621");
        fixedPoint.put("mondoId", "MONDO:0015967");
        fixedPoint.put("moi", "AUTOSOMAL_DOMINANT");
        fixedPoint.put("clinVarVariationId", "372380");
        fixedPoint.put("allele", "GRCh38:12:120989032:C:T");
        fixedPoint.put("selectedTranscript", "ENST00000257555.11");

        ObjectNode contracts = smoke.putObject("contracts");
        contracts.put("evaluationProfileId", Pvs1CriterionEvaluator.PROFILE_ID);
        contracts.put("evaluationProfileVersion", Pvs1CriterionEvaluator.PROFILE_VERSION);
        contracts.put("evaluationProfileCanonicalSha256", evaluator.getProfileSha256());
        contracts.put("prerequisiteProfileId", Pvs1PrerequisiteInputResolver.PROFILE_ID);
        contracts.put("prerequisiteProfileCanonicalSha256",
                new Pvs1PrerequisiteInputResolver(mapper).getProfileSha256());
        contracts.put("ruleRegistryId", evaluation.path("ruleBinding")
                .path("ruleRegistryId").asText());
        contracts.put("ruleRegistrySha256", evaluator.getRuleRegistrySha256());
        contracts.put("cspecRegistryId", selection.path("overlayRegistryId").asText());
        contracts.put("cspecRegistrySha256", evaluator.getCspecRegistrySha256());

        ObjectNode runtime = smoke.putObject("vepRuntime");
        runtime.put("resourceId", binding.getResourceId());
        runtime.put("resourceVersion", binding.getResourceVersion());
        runtime.put("identityFingerprintSha256", binding.getIdentityFingerprintSha256());
        runtime.put("assembly", binding.getAssembly());
        runtime.put("lofteeEnabled", false);
        runtime.put("invocationCount", invocations);
        runtime.put("resourceExecution", atomic.path("resourceExecution")
                .path("status").asText());
        runtime.put("resourceAvailability", atomic.path("resourceAvailability")
                .path("status").asText());
        runtime.put("queryOutcome", atomic.path("queryOutcome").path("status").asText());
        runtime.put("resourceApplicability", atomic.path("resourceApplicability")
                .path("status").asText());
        runtime.put("selectedTranscript",
                measurement(atomic, "vep.selectedTranscriptId").path("rawValue").asText());
        runtime.put("consequence",
                measurement(atomic, "consequenceTerm").path("rawValue").asText());
        runtime.put("exon", measurement(atomic, "vep.exon").path("rawValue").asText());
        runtime.put("evidenceId", atomic.path("evidenceId").asText());

        ObjectNode result = smoke.putObject("result");
        result.put("criterionId", evaluation.path("criterionId").asText());
        result.put("criterionApplicability", evaluation.path("criterionApplicability")
                .path("status").asText());
        result.put("evaluationResult", evaluation.path("evaluationResult")
                .path("status").asText());
        result.put("baseStrength", evaluation.path("baseStrength").asText());
        result.put("appliedStrength", evaluation.path("appliedStrength").asText());
        result.put("conflict", evaluation.path("conflict").path("status").asText());
        result.put("review", evaluation.path("review").path("status").asText());
        result.put("selectionOutcome", selection.path("outcome").asText());
        result.put("selectionId", selection.path("selectionId").asText());
        result.put("traceSha256", evaluation.path("deterministicTrace")
                .path("traceSha256").asText());
        result.put("evaluationId", evaluation.path("evaluationId").asText());
        result.set("evidenceIds", evaluation.path("evidenceIds").deepCopy());

        assertPortable(smoke);
        Path output = requiredOutput();
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), smoke);

        ObjectNode instances = mapper.createObjectNode();
        instances.set("evidenceContext", context.deepCopy());
        instances.set("atomicEvidence", atomic.deepCopy());
        instances.set("cspecSelection", selection.deepCopy());
        instances.set("criterionEvaluation", evaluation.deepCopy());
        assertPortable(instances);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                output.resolveSibling("e3-l1a-schema-instances.json").toFile(), instances);
    }

    private void assertPortable(JsonNode value) throws IOException {
        String serialized = mapper.writeValueAsString(value);
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
    }

    private ObjectNode hnf1aContext(NormalizedVariantKey key) throws IOException {
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key, "ENST00000257555");
        ((ObjectNode) context.path("gene"))
                .put("hgncId", "HGNC:11621")
                .put("symbol", "HNF1A");
        ((ObjectNode) context.path("disease"))
                .put("mondoId", "MONDO:0015967")
                .put("label", "monogenic diabetes");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript"))
                .put("accession", "ENST00000257555")
                .put("version", 11)
                .put("selectionPolicy", "PINNED_MANE_SELECT")
                .put("annotationSourceVersion", "116.0");
        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", context.path("variant").path("alleleId").asText());
        projection.put("hgncId", "HGNC:11621");
        projection.put("mondoId", "MONDO:0015967");
        projection.put("moi", "AUTOSOMAL_DOMINANT");
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", "ENST00000257555");
        transcript.put("version", 11);
        projection.put("patientContextId",
                context.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId",
                context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
        return context;
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        VariantQcObservation observation = new VariantQcObservation(
                key, "e3-l1a-synthetic-context", "0/1", 30, 99, "18,12", 0.4d,
                "PASS", "SNV", "public-fixed-point");
        return new VariantInterpretation(
                key.canonicalId(), observation, Collections.emptyList(), Collections.emptyList());
    }

    private JsonNode measurement(JsonNode evidence, String metricId) {
        for (JsonNode measurement : evidence.path("measurements")) {
            if (metricId.equals(measurement.path("metricId").asText())) return measurement;
        }
        return mapper.missingNode();
    }

    private VariantAnalysisProperties localProperties() throws IOException {
        Path localConfig = requiredPath(
                Paths.get("config", "variant-analysis-local.properties"),
                Paths.get("varvis-server", "config", "variant-analysis-local.properties"));
        Properties local = new Properties();
        try (Reader reader = Files.newBufferedReader(localConfig, StandardCharsets.UTF_8)) {
            local.load(reader);
        }
        String resourceRoot = firstText(
                System.getProperty("varvis.e3l1a.resource-root"),
                System.getenv("VARVIS_RESOURCE_ROOT"));
        String projectRoot = firstText(
                System.getProperty("varvis.e3l1a.project-root"),
                System.getenv("VARVIS_PROJECT_ROOT"));
        assertNotNull(resourceRoot, "Set varvis.e3l1a.resource-root for the opt-in smoke");
        assertNotNull(projectRoot, "Set varvis.e3l1a.project-root for the opt-in smoke");
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("e3-l1a-ignored-local-config", local));
        Map<String, Object> roots = new LinkedHashMap<>();
        roots.put("VARVIS_RESOURCE_ROOT", resourceRoot);
        roots.put("VARVIS_PROJECT_ROOT", projectRoot);
        environment.getPropertySources().addFirst(
                new MapPropertySource("e3-l1a-local-roots", roots));
        return Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalStateException("local config did not bind"));
    }

    private void enableVep(VariantAnalysisProperties properties) {
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setLofteeEnabled(false);
        properties.getVep().setTempDir(tempDirectory.resolve("vep").toString());
        properties.getVep().setCleanupTemp(true);
        VariantAnalysisProperties.Vep.ResourceProfile profile =
                properties.getVep().getResources().get("GRCh38");
        assertNotNull(profile, "Missing local GRCh38 VEP profile");
        profile.setEnabled(true);
        assertFalse(properties.getVep().isLofteeEnabled());
    }

    private Path requiredManifest() {
        return requiredPath(
                Paths.get("..", "docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"),
                Paths.get("docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"));
    }

    private Path requiredOutput() {
        String configured = System.getProperty("varvis.e3l1a.smoke-output");
        assertNotNull(firstText(configured), "Set varvis.e3l1a.smoke-output below target");
        Path output = Paths.get(configured.trim());
        if (!output.isAbsolute()) output = Paths.get("").toAbsolutePath().resolve(output);
        output = output.normalize();
        Path target = (Files.isDirectory(Paths.get("varvis-server"))
                ? Paths.get("varvis-server", "target") : Paths.get("target"))
                .toAbsolutePath().normalize();
        assertTrue(output.startsWith(target), "Smoke output must remain below target");
        return output;
    }

    private Path requiredPath(Path... candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)) return absolute;
        }
        throw new IllegalStateException("Required local E3-L1A input is missing");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }
}
