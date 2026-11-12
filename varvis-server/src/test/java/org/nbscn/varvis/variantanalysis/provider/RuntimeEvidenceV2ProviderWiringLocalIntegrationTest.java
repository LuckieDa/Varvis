package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in E2.1 smoke against the ignored local resource configuration. The
 * contexts are explicitly synthetic and the resulting evidence is not a
 * clinical validation or ACMG interpretation.
 */
@EnabledIfSystemProperty(named = "varvis.e21.runtime-smoke", matches = "true")
class RuntimeEvidenceV2ProviderWiringLocalIntegrationTest {

    private static final String GNOMAD_RESOURCE = "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT";
    private static final String CLINVAR_GRCH37_RESOURCE = "CLINVAR_2026_07_06_GRCH37";
    private static final String CLINVAR_GRCH38_RESOURCE = "CLINVAR_2026_03_21_GRCH38";
    private static final String VEP_GRCH37_RESOURCE = "VEP_116_GRCH37";
    private static final String VEP_GRCH38_RESOURCE = "VEP_116_GRCH38";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @TempDir
    Path tempDirectory;

    @Test
    void realProvidersPreserveManifestIdentityAndFourAxisSemantics() throws Exception {
        VariantAnalysisProperties properties = localProperties();
        enableOnlyRequiredRuntimePaths(properties);

        JsonNode manifest = mapper.readTree(requiredManifest().toFile());
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);

        RuntimeEvidenceV2ProviderWiring.ResourceBinding gnomadBinding =
                wiring.bind(manifest, GNOMAD_RESOURCE);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding clinvar37Binding =
                wiring.bind(manifest, CLINVAR_GRCH37_RESOURCE);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding clinvar38Binding =
                wiring.bind(manifest, CLINVAR_GRCH38_RESOURCE);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding vep37Binding =
                wiring.bind(manifest, VEP_GRCH37_RESOURCE);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding vep38Binding =
                wiring.bind(manifest, VEP_GRCH38_RESOURCE);

        GnomadTabixProvider gnomad = new GnomadTabixProvider(properties);
        ClinVarEvidenceProvider clinvar = new ClinVarEvidenceProvider(properties);
        VepTranscriptAnnotationProvider vep = new VepTranscriptAnnotationProvider(properties);

        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", "e2.1-runtime-provider-smoke-v1");
        report.put("status", "PASS");
        report.put("contextDataClass", "SYNTHETIC");
        report.put("contractEngineeringOnly", true);
        report.put("clinicalValidation", false);
        report.put("byteIntegrityClaimed", false);
        ArrayNode cases = mapper.createArrayNode();
        report.set("cases", cases);

        addVariantCase(cases, "GNOMAD_GRCH38_HIT", "GNOMAD", "HIT",
                key(GenomeAssembly.GRCh38, "1", 12138, "C", "A"),
                gnomad, gnomadBinding, wiring);
        addVariantCase(cases, "GNOMAD_GRCH38_NO_RECORD", "GNOMAD", "NO_RECORD",
                key(GenomeAssembly.GRCh38, "1", 12138, "C", "G"),
                gnomad, gnomadBinding, wiring);

        addVariantCase(cases, "CLINVAR_GRCH37_HIT", "CLINVAR", "HIT",
                key(GenomeAssembly.GRCh37, "1", 69134, "A", "G"),
                clinvar, clinvar37Binding, wiring);
        addVariantCase(cases, "CLINVAR_GRCH37_NO_RECORD", "CLINVAR", "NO_RECORD",
                key(GenomeAssembly.GRCh37, "1", 69134, "A", "C"),
                clinvar, clinvar37Binding, wiring);
        addVariantCase(cases, "CLINVAR_GRCH38_HIT", "CLINVAR", "HIT",
                key(GenomeAssembly.GRCh38, "1", 69134, "A", "G"),
                clinvar, clinvar38Binding, wiring);
        addVariantCase(cases, "CLINVAR_GRCH38_NO_RECORD", "CLINVAR", "NO_RECORD",
                key(GenomeAssembly.GRCh38, "1", 69134, "A", "C"),
                clinvar, clinvar38Binding, wiring);
        assertRecordId(cases, "CLINVAR_GRCH37_HIT", "2205837");
        assertRecordId(cases, "CLINVAR_GRCH38_HIT", "2205837");

        addTranscriptCase(cases, "VEP_GRCH37_HIT", "HIT",
                key(GenomeAssembly.GRCh37, "7", 140453136, "A", "T"),
                "ENST00000288602", 6, vep, vep37Binding, wiring);
        addTranscriptCase(cases, "VEP_GRCH37_NO_RECORD", "NO_RECORD",
                key(GenomeAssembly.GRCh37, "7", 140453136, "A", "T"),
                "ENST00000646891", 2, vep, vep37Binding, wiring);
        addTranscriptCase(cases, "VEP_GRCH38_HIT", "HIT",
                key(GenomeAssembly.GRCh38, "7", 140753336, "A", "T"),
                "ENST00000646891", 2, vep, vep38Binding, wiring);
        addTranscriptCase(cases, "VEP_GRCH38_NO_RECORD", "NO_RECORD",
                key(GenomeAssembly.GRCh38, "7", 140753336, "A", "T"),
                "ENST00000288602", 6, vep, vep38Binding, wiring);

        assertEquals(10, cases.size());
        String serialized = mapper.writeValueAsString(report);
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
        writeReport(report);
    }

    private void addVariantCase(ArrayNode cases,
                                String caseId,
                                String providerName,
                                String expectedQuery,
                                NormalizedVariantKey key,
                                VariantEvidenceProvider provider,
                                RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                                RuntimeEvidenceV2ProviderWiring wiring) {
        ObjectNode context = syntheticContext(key, "NM_000001", 1, "HGNC:1", "SYNTH1");
        List<ObjectNode> results = wiring.annotateVariantProvider(
                context, provider, variant(key), analysisContext(key), binding);
        ObjectNode atomic = onlyAtomic(results, caseId);
        assertAtomic(atomic, context, binding, expectedQuery);
        addCase(cases, caseId, providerName, key, atomic);
    }

    private void addTranscriptCase(ArrayNode cases,
                                   String caseId,
                                   String expectedQuery,
                                   NormalizedVariantKey key,
                                   String transcript,
                                   int transcriptVersion,
                                   TranscriptAnnotationProvider provider,
                                   RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                                   RuntimeEvidenceV2ProviderWiring wiring) {
        ObjectNode context = syntheticContext(
                key, transcript, transcriptVersion, "HGNC:1097", "BRAF");
        List<ObjectNode> results = wiring.annotateTranscriptProvider(
                context, provider, variant(key), analysisContext(key), binding);
        ObjectNode atomic = onlyAtomic(results, caseId);
        assertAtomic(atomic, context, binding, expectedQuery);
        if ("HIT".equals(expectedQuery)) {
            assertTrue(atomic.path("source").path("sourceRecords").get(0)
                    .path("recordId").asText().contains(
                            transcript + "." + transcriptVersion),
                    caseId + " did not locate the requested transcript version");
            assertTrue(measurement(atomic, "hgvsc")
                            .startsWith(transcript + "." + transcriptVersion + ":"),
                    caseId + " did not retain the exact transcript version");
        }
        addCase(cases, caseId, "VEP", key, atomic);
    }

    private ObjectNode onlyAtomic(List<ObjectNode> results, String caseId) {
        assertNotNull(results, caseId + " returned null");
        assertEquals(1, results.size(), caseId + " must produce one atomic result");
        return results.get(0);
    }

    private void assertAtomic(ObjectNode atomic,
                              ObjectNode context,
                              RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                              String expectedQuery) {
        assertEquals("atomic-evidence-v2", atomic.path("schemaVersion").asText());
        assertEquals(context.path("contextId").asText(), atomic.path("contextId").asText());
        assertEquals("UNDETERMINED", atomic.path("direction").asText());
        assertEquals(binding.getResourceId(), atomic.path("source").path("sourceId").asText());
        assertEquals(binding.getResourceVersion(),
                atomic.path("source").path("resourceVersion").asText());
        assertEquals(binding.getIdentityFingerprintSha256(),
                atomic.path("source").path("resourceSha256").asText());
        assertEquals("SUCCEEDED", atomic.path("resourceExecution").path("status").asText());
        assertEquals("AVAILABLE", atomic.path("resourceAvailability").path("status").asText());
        assertEquals(expectedQuery, atomic.path("queryOutcome").path("status").asText());
        assertEquals("APPLICABLE", atomic.path("resourceApplicability").path("status").asText());
        if ("NO_RECORD".equals(expectedQuery)) {
            assertEquals(0, atomic.path("source").path("sourceRecords").size());
            assertEquals(0, atomic.path("measurements").size());
        } else {
            assertTrue(atomic.path("source").path("sourceRecords").size() > 0);
            assertTrue(atomic.path("measurements").size() > 0);
        }
    }

    private void addCase(ArrayNode cases,
                         String caseId,
                         String provider,
                         NormalizedVariantKey key,
                         ObjectNode atomic) {
        ObjectNode value = mapper.createObjectNode();
        value.put("caseId", caseId);
        value.put("provider", provider);
        value.set("queryAllele", allele(key));
        value.set("source", atomic.path("source").deepCopy());
        value.set("resourceExecution", atomic.path("resourceExecution").deepCopy());
        value.set("resourceAvailability", atomic.path("resourceAvailability").deepCopy());
        value.set("queryOutcome", atomic.path("queryOutcome").deepCopy());
        value.set("resourceApplicability", atomic.path("resourceApplicability").deepCopy());
        value.put("evidenceId", atomic.path("evidenceId").asText());
        value.set("atomicEvidence", atomic.deepCopy());
        cases.add(value);
    }

    private String measurement(ObjectNode atomic, String metricId) {
        for (JsonNode value : atomic.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                return value.path("rawValue").asText();
            }
        }
        return "";
    }

    private void assertRecordId(ArrayNode cases, String caseId, String expectedRecordId) {
        for (JsonNode value : cases) {
            if (caseId.equals(value.path("caseId").asText())) {
                assertEquals(expectedRecordId,
                        value.path("source").path("sourceRecords").get(0)
                                .path("recordId").asText());
                return;
            }
        }
        throw new AssertionError("Missing smoke case " + caseId);
    }

    private ObjectNode syntheticContext(NormalizedVariantKey key,
                                        String transcriptAccession,
                                        int transcriptVersion,
                                        String hgncId,
                                        String geneSymbol) {
        ObjectNode context = mapper.createObjectNode();
        context.put("schemaVersion", "evidence-context-v2");
        context.put("canonicalizationProfile", "varvis-json-c14n-v1");
        context.put("dataClass", "SYNTHETIC");

        ObjectNode scope = mapper.createObjectNode();
        scope.put("analysisDomain", "GERMLINE");
        scope.put("diseaseModel", "MENDELIAN");
        scope.put("variantScope", "SMALL_SEQUENCE_VARIANT");
        context.set("scope", scope);

        ObjectNode allele = allele(key);
        ObjectNode normalization = mapper.createObjectNode();
        normalization.put("status", "UNCHANGED");
        normalization.put("method", "e2-1-synthetic-noop-normalizer");
        normalization.put("methodVersion", "1.0.0");
        normalization.put("referenceSequenceSha256",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        normalization.put("refValidation", "MATCH");

        ObjectNode alleleProjection = mapper.createObjectNode();
        alleleProjection.set("normalizedAllele", allele.deepCopy());
        ObjectNode normalizer = mapper.createObjectNode();
        for (String field : List.of(
                "method", "methodVersion", "referenceSequenceSha256", "refValidation")) {
            normalizer.set(field, normalization.path(field).deepCopy());
        }
        alleleProjection.set("normalizer", normalizer);

        ObjectNode variant = mapper.createObjectNode();
        variant.put("alleleId", "allele-sha256-" + adapter.canonicalSha256(alleleProjection));
        variant.put("variantType", "SNV");
        variant.set("inputAllele", allele.deepCopy());
        variant.set("normalizedAllele", allele.deepCopy());
        variant.set("evidenceQueryAllele", allele.deepCopy());
        variant.set("normalization", normalization);
        ObjectNode lineage = mapper.createObjectNode();
        lineage.put("mappingStatus", "NATIVE");
        variant.set("assemblyLineage", lineage);
        context.set("variant", variant);

        ObjectNode gene = mapper.createObjectNode();
        gene.put("hgncId", hgncId);
        gene.put("symbol", geneSymbol);
        context.set("gene", gene);
        ObjectNode disease = mapper.createObjectNode();
        disease.put("mondoId", "MONDO:0000001");
        disease.put("label", "Synthetic E2.1 runtime-wiring context");
        context.set("disease", disease);
        context.put("moi", "AUTOSOMAL_DOMINANT");

        ObjectNode transcript = mapper.createObjectNode();
        transcript.put("accession", transcriptAccession);
        transcript.put("version", transcriptVersion);
        transcript.put("selectionPolicy", "E2_1_SYNTHETIC_PINNED");
        transcript.put("annotationSourceVersion", "E2_1_SYNTHETIC");
        context.set("transcript", transcript);

        ObjectNode patient = mapper.createObjectNode();
        patient.put("patientContextId", "patient-context-e21-synthetic-proband");
        patient.put("familyContextId", "family-context-e21-synthetic-family");
        patient.put("role", "PROBAND");
        patient.put("affectedStatus", "AFFECTED");
        patient.put("sex", "UNKNOWN");
        context.set("patientContext", patient);

        ObjectNode completeness = mapper.createObjectNode();
        completeness.put("status", "COMPLETE");
        completeness.set("missingFields", mapper.createArrayNode());
        context.set("contextCompleteness", completeness);

        ObjectNode contextProjection = mapper.createObjectNode();
        contextProjection.set("scope", scope.deepCopy());
        contextProjection.put("alleleId", variant.path("alleleId").asText());
        contextProjection.put("hgncId", hgncId);
        contextProjection.put("mondoId", disease.path("mondoId").asText());
        contextProjection.put("moi", context.path("moi").asText());
        ObjectNode projectedTranscript = mapper.createObjectNode();
        projectedTranscript.put("accession", transcriptAccession);
        projectedTranscript.put("version", transcriptVersion);
        contextProjection.set("transcript", projectedTranscript);
        contextProjection.put("patientContextId", patient.path("patientContextId").asText());
        contextProjection.put("familyContextId", patient.path("familyContextId").asText());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(contextProjection));
        return context;
    }

    private ObjectNode allele(NormalizedVariantKey key) {
        ObjectNode allele = mapper.createObjectNode();
        allele.put("assembly", key.getGenomeAssembly());
        allele.put("contig", key.getChromosome());
        allele.put("position", key.getPosition());
        allele.put("reference", key.getReference());
        allele.put("alternate", key.getAlternate());
        return allele;
    }

    private NormalizedVariantKey key(GenomeAssembly assembly,
                                     String chromosome,
                                     int position,
                                     String reference,
                                     String alternate) {
        return NormalizedVariantKey.canonical(assembly, chromosome, position, reference, alternate);
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        VariantQcObservation observation = new VariantQcObservation(
                key, "e21-synthetic-proband", "0/1", 30, 99, "18,12", 0.4d,
                "PASS", "SNV", "e21-synthetic-vcf-record");
        return new VariantInterpretation(
                key.canonicalId(), observation, Collections.emptyList(), Collections.emptyList());
    }

    private AnalysisContext analysisContext(NormalizedVariantKey key) {
        return new AnalysisContext(
                "e2-1-runtime-provider-smoke",
                GenomeAssembly.valueOf(key.getGenomeAssembly()),
                AnalysisMode.WES);
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
                System.getProperty("varvis.e21.resource-root"),
                System.getenv("VARVIS_RESOURCE_ROOT"));
        assertNotNull(resourceRoot,
                "Set varvis.e21.resource-root or VARVIS_RESOURCE_ROOT for the opt-in local smoke");
        String projectRoot = firstText(
                System.getProperty("varvis.e21.project-root"),
                System.getenv("VARVIS_PROJECT_ROOT"),
                resourceRoot);

        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("e21-ignored-local-config", local));
        Map<String, Object> roots = new LinkedHashMap<String, Object>();
        roots.put("VARVIS_RESOURCE_ROOT", resourceRoot);
        roots.put("VARVIS_PROJECT_ROOT", projectRoot);
        environment.getPropertySources().addFirst(new MapPropertySource("e21-local-roots", roots));
        return Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalStateException("variant-analysis local config did not bind"));
    }

    private void enableOnlyRequiredRuntimePaths(VariantAnalysisProperties properties) {
        properties.getGnomad().setEnabled(true);
        properties.getGnomad().setAllowFullVcfFallback(false);
        properties.getGnomad().setAllowVcfInfoFallback(false);
        properties.getGnomad().setParallelism(1);

        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setAllowVcfInfoFallback(false);
        properties.getClinVar().getCompactIndex().setEnabled(false);

        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setLofteeEnabled(false);
        properties.getVep().setTempDir(tempDirectory.resolve("vep").toString());
        properties.getVep().setCleanupTemp(true);
        enableVepProfile(properties, "GRCh37");
        enableVepProfile(properties, "GRCh38");

        assertFalse(properties.getGnomad().isAllowFullVcfFallback());
        assertFalse(properties.getGnomad().isAllowVcfInfoFallback());
        assertFalse(properties.getClinVar().isAllowVcfInfoFallback());
        assertFalse(properties.getVep().isLofteeEnabled());
    }

    private void enableVepProfile(VariantAnalysisProperties properties, String assembly) {
        VariantAnalysisProperties.Vep.ResourceProfile match = null;
        for (Map.Entry<String, VariantAnalysisProperties.Vep.ResourceProfile> entry
                : properties.getVep().getResources().entrySet()) {
            if (assembly.equalsIgnoreCase(entry.getKey())
                    || assembly.equals(entry.getValue().normalizedAssembly())) {
                match = entry.getValue();
                break;
            }
        }
        assertNotNull(match, "Missing local VEP profile for " + assembly);
        match.setEnabled(true);
    }

    private Path requiredManifest() {
        String configured = System.getProperty("varvis.e21.manifest");
        if (configured != null && !configured.trim().isEmpty()) {
            return requiredPath(Paths.get(configured.trim()));
        }
        return requiredPath(
                Paths.get("..", "docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"),
                Paths.get("docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"));
    }

    private void writeReport(ObjectNode report) throws IOException {
        String configured = System.getProperty("varvis.e21.smoke-output");
        assertNotNull(firstText(configured),
                "Set varvis.e21.smoke-output to a file below varvis-server/target");
        Path output = Paths.get(configured.trim());
        if (!output.isAbsolute()) output = Paths.get("").toAbsolutePath().resolve(output);
        output = output.normalize();
        Path target = requiredTargetDirectory();
        assertTrue(output.startsWith(target),
                "varvis.e21.smoke-output must remain below varvis-server/target");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private Path requiredTargetDirectory() {
        Path first = Paths.get("target").toAbsolutePath().normalize();
        Path second = Paths.get("varvis-server", "target").toAbsolutePath().normalize();
        return Files.isDirectory(Paths.get("varvis-server")) ? second : first;
    }

    private Path requiredPath(Path... candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)) return absolute;
        }
        throw new IllegalStateException("Required local E2.1 input is missing");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }
}
