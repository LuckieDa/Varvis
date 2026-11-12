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

/** Opt-in public fixed-point smoke; it produces no PVS1 criterion evaluation. */
@EnabledIfSystemProperty(named = "varvis.e3l0a.runtime-smoke", matches = "true")
class Pvs1PrerequisiteInputResolverLocalIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @TempDir
    Path tempDirectory;

    @Test
    void publicHnf1aFixedPointReachesPinnedPrerequisiteInput() throws Exception {
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
        VariantInterpretation variant = variant(key);
        ObjectNode atomic = wiring.annotateTranscriptProvider(
                context, provider, variant,
                new AnalysisContext("e3-l0a-public-fixed-point", GenomeAssembly.GRCh38,
                        AnalysisMode.WES), binding).get(0);
        Pvs1PrerequisiteInputResolver resolver =
                new Pvs1PrerequisiteInputResolver(mapper);
        Pvs1PrerequisiteInputResolver.Result result = resolver.resolve(context, atomic);

        assertEquals(1, invocations.get());
        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertEquals("ENST00000257555.11",
                measurement(atomic, "vep.selectedTranscriptId").path("rawValue").asText());
        assertTrue(List.of(measurement(atomic, "consequenceTerm").path("rawValue")
                .asText().split("&")).contains("stop_gained"));
        assertEquals("2/10", measurement(atomic, "vep.exon").path("rawValue").asText());
        assertEquals("2/10",
                measurement(atomic, "vep.exon").path("normalizedValue").asText());
        assertEquals("INPUT_READY", result.getStatus());
        assertEquals("PREDICTED_NMD", result.getNmdPrediction());
        writeSmoke(binding, context, atomic, result, invocations.get());
    }

    private void writeSmoke(RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                            ObjectNode context,
                            ObjectNode atomic,
                            Pvs1PrerequisiteInputResolver.Result result,
                            int invocations) throws IOException {
        ObjectNode smoke = mapper.createObjectNode();
        smoke.put("schemaVersion", "e3-l0a-hnf1a-pvs1-prerequisite-smoke-v1");
        smoke.put("stage", "E3-L0A");
        smoke.put("status", "PASS");
        smoke.put("readiness", "READY_FOR_E3_L1A");
        smoke.put("dataClass", "PUBLIC_FIXED_POINT_WITH_SYNTHETIC_EVIDENCE_CONTEXT");
        ObjectNode boundary = smoke.putObject("boundary");
        boundary.put("inputReadinessOnly", true);
        boundary.put("pvs1CriterionEvaluationProduced", false);
        boundary.put("pvs1StrengthProduced", false);
        boundary.put("classificationProduced", false);
        boundary.put("patientDataUsed", false);
        boundary.put("clinicalValidationClaimed", false);

        ObjectNode fixedPoint = smoke.putObject("fixedPoint");
        fixedPoint.put("gene", "HNF1A");
        fixedPoint.put("hgncId", "HGNC:11621");
        fixedPoint.put("mondoId", "MONDO:0015967");
        fixedPoint.put("moi", "AUTOSOMAL_DOMINANT");
        fixedPoint.put("clinVarVariationId", "372380");
        fixedPoint.put("clinGenAlleleRegistryId", "CA6831769");
        fixedPoint.put("allele", "GRCh38:12:120989032:C:T");
        fixedPoint.put("refSeq", "NM_000545.8:c.526C>T");
        fixedPoint.put("protein", "NP_000536.6:p.Gln176Ter");

        ObjectNode profile = smoke.putObject("profile");
        profile.put("profileId", result.getProfileId());
        profile.put("profileVersion", result.getProfileVersion());
        profile.put("profileSha256", result.getProfileSha256());
        profile.put("status", "INPUT_ONLY");

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
        runtime.put("hgvsc", measurement(atomic, "hgvsc").path("rawValue").asText());
        runtime.put("hgvsp", measurement(atomic, "hgvsp").path("rawValue").asText());
        runtime.put("sourcePayloadSha256",
                atomic.path("provenance").path("sourcePayloadSha256").asText());
        runtime.put("evidenceId", atomic.path("evidenceId").asText());

        ObjectNode resolution = smoke.putObject("resolution");
        resolution.put("status", result.getStatus());
        resolution.put("nmdPrediction", result.getNmdPrediction());
        resolution.putNull("reasonCode");
        resolution.put("contextId", context.path("contextId").asText());
        resolution.put("evidenceId", result.getEvidenceId());

        String serialized = mapper.writeValueAsString(smoke);
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
        Path output = requiredOutput();
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), smoke);
        ObjectNode schemaInstances = mapper.createObjectNode();
        schemaInstances.set("evidenceContext", context.deepCopy());
        schemaInstances.set("atomicEvidence", atomic.deepCopy());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                output.resolveSibling("e3-l0a-schema-instances.json").toFile(),
                schemaInstances);
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
                key, "e3-l0a-synthetic-context", "0/1", 30, 99, "18,12", 0.4d,
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
                System.getProperty("varvis.e3l0a.resource-root"),
                System.getenv("VARVIS_RESOURCE_ROOT"));
        String projectRoot = firstText(
                System.getProperty("varvis.e3l0a.project-root"),
                System.getenv("VARVIS_PROJECT_ROOT"));
        assertNotNull(resourceRoot, "Set varvis.e3l0a.resource-root for the opt-in smoke");
        assertNotNull(projectRoot, "Set varvis.e3l0a.project-root for the opt-in smoke");
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("e3-l0a-ignored-local-config", local));
        Map<String, Object> roots = new LinkedHashMap<>();
        roots.put("VARVIS_RESOURCE_ROOT", resourceRoot);
        roots.put("VARVIS_PROJECT_ROOT", projectRoot);
        environment.getPropertySources().addFirst(
                new MapPropertySource("e3-l0a-local-roots", roots));
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
        String configured = System.getProperty("varvis.e3l0a.smoke-output");
        assertNotNull(firstText(configured), "Set varvis.e3l0a.smoke-output below target");
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
        throw new IllegalStateException("Required local E3-L0A input is missing");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }
}
