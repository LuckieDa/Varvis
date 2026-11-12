package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in E3-P0 point-query smoke against the ignored local gnomAD full-sites
 * configuration. It prepares population-frequency input only and performs no
 * ACMG criterion evaluation or clinical validation.
 */
@EnabledIfSystemProperty(named = "varvis.e3p0.runtime-smoke", matches = "true")
class GnomadPopulationInputLocalIntegrationTest {

    private static final Pattern FULL_SITES_NAME = Pattern.compile(
            "gnomad\\.exomes\\.v4\\.1\\.sites\\.chr(.+)\\.vcf\\.bgz");
    private static final Set<String> FORBIDDEN_RESULTS = Set.of(
            "MET", "NOT_MET", "BA1", "BS1", "PM2", "PM2_SUPPORTING");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    void fullSitesInputPreservesIdentityQualityAndNoCriterionSemantics() throws Exception {
        VariantAnalysisProperties properties = localProperties();
        properties.getGnomad().setEnabled(true);
        properties.getGnomad().setPopulationInputEnabled(true);
        properties.getGnomad().setAllowFullVcfFallback(false);
        properties.getGnomad().setAllowVcfInfoFallback(false);
        properties.getGnomad().setParallelism(1);

        assertTrue(properties.getGnomad().isPopulationInputEnabled());
        assertFalse(properties.getGnomad().isAllowFullVcfFallback());
        assertFalse(properties.getGnomad().isAllowVcfInfoFallback());

        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                profile.getDocument(), profile.getFullSitesResourceId());
        assertEquals("GNOMAD", binding.getProvider());
        assertEquals("GRCh38", binding.getAssembly());
        assertEquals(properties.getGnomad().getVersion(), binding.getResourceVersion());

        ObjectNode resourceSummary = verifyResourceMetadata(
                properties.getGnomad().getGrch38Files(), profile.getDocument(), binding);
        GnomadTabixProvider provider = new GnomadTabixProvider(properties);

        ObjectNode pass = query(
                key(12591, "C", "T"), provider, wiring, binding);
        assertAtomic(pass, binding, "HIT");
        assertMeasurement(pass, "gnomad.exomes.af", "0.000101231", "0.000101231");
        assertMeasurement(pass, "gnomad.exomes.faf95max", "2.82600e-05", "0.00002826");
        assertMeasurement(pass, "gnomad.inputQualityStatus", "PASS", "PASS");
        assertMeasurement(pass, "gnomad.recordInputReadinessStatus", "READY", "READY");
        assertMeasurement(pass, "gnomad.generalCriterionDatasetReadinessStatus",
                "NOT_READY", "NOT_READY");
        assertHasRawMeasurementContaining(pass, "JOINT_OR_COMBINED_DATASET_UNAVAILABLE");
        assertHasRawMeasurementContaining(pass, "ALL_SITES_AN_UNAVAILABLE");

        ObjectNode nonPass = query(
                key(12138, "C", "A"), provider, wiring, binding);
        assertAtomic(nonPass, binding, "HIT");
        assertMeasurement(nonPass, "gnomad.exomes.filter", "AS_VQSR", "AS_VQSR");
        assertMeasurement(nonPass, "gnomad.recordInputReadinessStatus",
                "NOT_READY", "NOT_READY");
        assertHasRawMeasurementContaining(nonPass, "FILTER_NOT_PASS");

        ObjectNode noRecord = query(
                key(12591, "C", "A"), provider, wiring, binding);
        assertAtomic(noRecord, binding, "NO_RECORD");
        assertEquals(0, noRecord.path("source").path("sourceRecords").size());
        assertEquals(0, noRecord.path("measurements").size());

        for (ObjectNode atomic : List.of(pass, nonPass, noRecord)) {
            assertNoCriterionResult(atomic);
            assertNoAbsolutePath(atomic.toString());
        }

        ObjectNode report = mapper.createObjectNode();
        report.put("schemaVersion", "e3-p0-gnomad-population-input-smoke-v1");
        report.put("status", "PASS");
        report.put("contextDataClass", "SYNTHETIC");
        report.put("inputPreparationOnly", true);
        report.put("clinicalValidation", false);
        report.put("criterionEvaluationPerformed", false);
        report.put("byteIntegrityClaimed", false);
        ObjectNode profileSummary = mapper.createObjectNode();
        profileSummary.put("profileId", profile.getProfileId());
        profileSummary.put("profileVersion", profile.getProfileVersion());
        profileSummary.put("profileSha256", profile.getProfileSha256());
        report.set("profile", profileSummary);
        report.set("resource", resourceSummary);
        ArrayNode cases = mapper.createArrayNode();
        cases.add(caseSummary("PASS_HIT", pass));
        cases.add(caseSummary("NON_PASS_HIT", nonPass));
        cases.add(caseSummary("NO_RECORD", noRecord));
        report.set("cases", cases);

        String serialized = mapper.writeValueAsString(report);
        assertNoAbsolutePath(serialized);
        writeReportIfRequested(report);
    }

    private ObjectNode query(NormalizedVariantKey key,
                             GnomadTabixProvider provider,
                             RuntimeEvidenceV2ProviderWiring wiring,
                             RuntimeEvidenceV2ProviderWiring.ResourceBinding binding) {
        ObjectNode context = syntheticContext(key);
        List<ObjectNode> results = wiring.annotateVariantProvider(
                context, provider, variant(key), analysisContext(key), binding);
        assertNotNull(results);
        assertEquals(1, results.size());
        ObjectNode atomic = results.get(0);
        assertEquals(context.path("contextId").asText(), atomic.path("contextId").asText());
        return atomic;
    }

    private void assertAtomic(ObjectNode atomic,
                              RuntimeEvidenceV2ProviderWiring.ResourceBinding binding,
                              String expectedQueryOutcome) {
        assertEquals("atomic-evidence-v2", atomic.path("schemaVersion").asText());
        assertEquals("UNDETERMINED", atomic.path("direction").asText());
        assertEquals(binding.getResourceId(), atomic.path("source").path("sourceId").asText());
        assertEquals(binding.getResourceVersion(),
                atomic.path("source").path("resourceVersion").asText());
        assertEquals(binding.getIdentityFingerprintSha256(),
                atomic.path("source").path("resourceSha256").asText());
        assertEquals("SUCCEEDED", atomic.path("resourceExecution").path("status").asText());
        assertEquals("AVAILABLE", atomic.path("resourceAvailability").path("status").asText());
        assertEquals(expectedQueryOutcome, atomic.path("queryOutcome").path("status").asText());
        assertEquals("APPLICABLE", atomic.path("resourceApplicability").path("status").asText());
        if ("HIT".equals(expectedQueryOutcome)) {
            assertTrue(atomic.path("source").path("sourceRecords").size() > 0);
            assertTrue(atomic.path("measurements").size() > 0);
        }
    }

    private void assertMeasurement(ObjectNode atomic,
                                   String metricId,
                                   String expectedRaw,
                                   String expectedNormalized) {
        JsonNode value = measurement(atomic, metricId);
        assertNotNull(value, "Missing measurement " + metricId);
        assertEquals(expectedRaw, value.path("rawValue").asText());
        assertEquals(expectedNormalized, value.path("normalizedValue").asText());
    }

    private JsonNode measurement(ObjectNode atomic, String metricId) {
        for (JsonNode value : atomic.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) return value;
        }
        return null;
    }

    private void assertHasRawMeasurementContaining(ObjectNode atomic, String expectedFragment) {
        for (JsonNode value : atomic.path("measurements")) {
            if (value.path("rawValue").asText().contains(expectedFragment)) return;
        }
        throw new AssertionError(
                "Missing population input measurement fragment " + expectedFragment);
    }

    private void assertNoCriterionResult(JsonNode node) {
        if (node.isTextual()) {
            assertFalse(FORBIDDEN_RESULTS.contains(node.asText().toUpperCase()),
                    "Unexpected criterion result in E3-P0 atomic evidence: " + node.asText());
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                assertFalse("criterionEvaluations".equals(field.getKey()));
                assertNoCriterionResult(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) assertNoCriterionResult(value);
        }
    }

    private ObjectNode verifyResourceMetadata(List<Path> files,
                                              JsonNode profileDocument,
                                              RuntimeEvidenceV2ProviderWiring.ResourceBinding binding)
            throws IOException {
        JsonNode resource = resource(profileDocument, binding.getResourceId());
        JsonNode identity = resource.path("identity");
        int expectedDataCount = identity.path("dataComponentCount").asInt();
        int expectedIndexCount = identity.path("indexComponentCount").asInt();
        long dataBytes = 0L;
        long indexBytes = 0L;
        int staleIndexWarningCount = 0;
        Set<String> chromosomes = new TreeSet<String>();

        assertEquals(expectedDataCount, files.size());
        for (Path file : files) {
            assertTrue(Files.isRegularFile(file), "Configured gnomAD data component is missing");
            Matcher matcher = FULL_SITES_NAME.matcher(file.getFileName().toString());
            assertTrue(matcher.matches(), "Configured gnomAD component has an unexpected logical name");
            assertTrue(chromosomes.add(matcher.group(1)), "Duplicate configured gnomAD chromosome");
            dataBytes += Files.size(file);

            Path index = file.resolveSibling(file.getFileName().toString() + ".tbi");
            assertTrue(Files.isRegularFile(index), "Configured gnomAD index component is missing");
            indexBytes += Files.size(index);
            if (Files.getLastModifiedTime(index).compareTo(Files.getLastModifiedTime(file)) < 0) {
                staleIndexWarningCount++;
            }
        }

        Set<String> expectedChromosomes = new TreeSet<String>();
        for (JsonNode chromosome : identity.path("chromosomes")) {
            expectedChromosomes.add(chromosome.asText());
        }
        assertEquals(expectedChromosomes, chromosomes);
        assertEquals(expectedIndexCount, files.size());
        assertEquals(identity.path("dataTotalBytes").asLong(), dataBytes);
        assertEquals(identity.path("indexTotalBytes").asLong(), indexBytes);

        ObjectNode summary = mapper.createObjectNode();
        summary.put("resourceId", binding.getResourceId());
        summary.put("resourceVersion", binding.getResourceVersion());
        summary.put("assembly", binding.getAssembly());
        summary.put("identityFingerprintSha256", binding.getIdentityFingerprintSha256());
        summary.put("logicalNamePattern", identity.path("logicalNamePattern").asText());
        summary.put("indexLogicalNamePattern", identity.path("indexLogicalNamePattern").asText());
        summary.put("dataComponentCount", files.size());
        summary.put("dataTotalBytes", dataBytes);
        summary.put("indexComponentCount", files.size());
        summary.put("indexTotalBytes", indexBytes);
        summary.put("staleIndexWarningCount", staleIndexWarningCount);
        return summary;
    }

    private JsonNode resource(JsonNode profileDocument, String resourceId) {
        JsonNode match = null;
        for (JsonNode value : profileDocument.path("resources")) {
            if (resourceId.equals(value.path("resourceId").asText())) {
                assertTrue(match == null, "Profile resourceId is duplicated");
                match = value;
            }
        }
        assertNotNull(match, "Profile resource identity is missing");
        return match;
    }

    private ObjectNode caseSummary(String caseId, ObjectNode atomic) {
        ObjectNode value = mapper.createObjectNode();
        value.put("caseId", caseId);
        value.put("evidenceId", atomic.path("evidenceId").asText());
        value.set("resourceExecution", atomic.path("resourceExecution").deepCopy());
        value.set("resourceAvailability", atomic.path("resourceAvailability").deepCopy());
        value.set("queryOutcome", atomic.path("queryOutcome").deepCopy());
        value.set("resourceApplicability", atomic.path("resourceApplicability").deepCopy());
        value.put("measurementCount", atomic.path("measurements").size());
        putMeasurement(value, atomic, "gnomad.exomes.filter", "filter");
        putMeasurement(value, atomic,
                "gnomad.recordInputReadinessStatus", "inputReadiness");
        putMeasurement(value, atomic,
                "gnomad.recordInputReadinessReasonCodes", "inputReasonCodes");
        putMeasurement(value, atomic,
                "gnomad.generalCriterionDatasetReadinessStatus", "generalDatasetReadiness");
        putMeasurement(value, atomic,
                "gnomad.generalCriterionDatasetReadinessReasonCodes", "generalDatasetReasonCodes");
        putMeasurement(value, atomic, "gnomad.exomes.af", "alleleFrequencyRaw");
        putMeasurement(value, atomic, "gnomad.exomes.faf95max", "faf95MaxRaw");
        return value;
    }

    private void putMeasurement(ObjectNode target,
                                ObjectNode atomic,
                                String metricId,
                                String targetField) {
        JsonNode measurement = measurement(atomic, metricId);
        if (measurement != null) target.put(targetField, measurement.path("rawValue").asText());
    }

    private ObjectNode syntheticContext(NormalizedVariantKey key) {
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
        normalization.put("method", "e3-p0-synthetic-noop-normalizer");
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
        gene.put("hgncId", "HGNC:1");
        gene.put("symbol", "SYNTH1");
        context.set("gene", gene);
        ObjectNode disease = mapper.createObjectNode();
        disease.put("mondoId", "MONDO:0000001");
        disease.put("label", "Synthetic E3-P0 context only");
        context.set("disease", disease);
        context.put("moi", "AUTOSOMAL_DOMINANT");

        ObjectNode transcript = mapper.createObjectNode();
        transcript.put("accession", "NM_000001");
        transcript.put("version", 1);
        transcript.put("selectionPolicy", "E3_P0_SYNTHETIC_PINNED");
        transcript.put("annotationSourceVersion", "E3_P0_SYNTHETIC");
        context.set("transcript", transcript);

        ObjectNode patient = mapper.createObjectNode();
        patient.put("patientContextId", "patient-context-e3-p0-synthetic-proband");
        patient.put("familyContextId", "family-context-e3-p0-synthetic-family");
        patient.put("role", "PROBAND");
        patient.put("affectedStatus", "AFFECTED");
        patient.put("sex", "UNKNOWN");
        context.set("patientContext", patient);

        ObjectNode completeness = mapper.createObjectNode();
        completeness.put("status", "COMPLETE");
        completeness.set("missingFields", mapper.createArrayNode());
        context.set("contextCompleteness", completeness);

        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", scope.deepCopy());
        projection.put("alleleId", variant.path("alleleId").asText());
        projection.put("hgncId", gene.path("hgncId").asText());
        projection.put("mondoId", disease.path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode projectedTranscript = mapper.createObjectNode();
        projectedTranscript.put("accession", transcript.path("accession").asText());
        projectedTranscript.put("version", transcript.path("version").asInt());
        projection.set("transcript", projectedTranscript);
        projection.put("patientContextId", patient.path("patientContextId").asText());
        projection.put("familyContextId", patient.path("familyContextId").asText());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
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

    private NormalizedVariantKey key(int position, String reference, String alternate) {
        return NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "1", position, reference, alternate);
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        VariantQcObservation observation = new VariantQcObservation(
                key, "e3-p0-synthetic-proband", "0/1", 30, 99, "18,12", 0.4d,
                "PASS", "SNV", "e3-p0-synthetic-vcf-record");
        return new VariantInterpretation(
                key.canonicalId(), observation, Collections.emptyList(), Collections.emptyList());
    }

    private AnalysisContext analysisContext(NormalizedVariantKey key) {
        return new AnalysisContext(
                "e3-p0-gnomad-population-input-smoke",
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
                System.getProperty("varvis.e3p0.resource-root"),
                System.getenv("VARVIS_RESOURCE_ROOT"));
        assertNotNull(resourceRoot,
                "Set varvis.e3p0.resource-root or VARVIS_RESOURCE_ROOT for the opt-in smoke");
        String projectRoot = firstText(
                System.getProperty("varvis.e3p0.project-root"),
                System.getenv("VARVIS_PROJECT_ROOT"),
                resourceRoot);

        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("e3p0-ignored-local-config", local));
        Map<String, Object> roots = new LinkedHashMap<String, Object>();
        roots.put("VARVIS_RESOURCE_ROOT", resourceRoot);
        roots.put("VARVIS_PROJECT_ROOT", projectRoot);
        environment.getPropertySources().addFirst(new MapPropertySource("e3p0-local-roots", roots));
        return Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                        "variant-analysis local config did not bind"));
    }

    private void writeReportIfRequested(ObjectNode report) throws IOException {
        String configured = firstText(System.getProperty("varvis.e3p0.smoke-output"));
        if (configured == null) return;
        Path output = Paths.get(configured);
        if (!output.isAbsolute()) output = Paths.get("").toAbsolutePath().resolve(output);
        output = output.normalize();
        Path target = requiredTargetDirectory();
        assertTrue(output.startsWith(target),
                "varvis.e3p0.smoke-output must remain below varvis-server/target");
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
        throw new IllegalStateException("Required local E3-P0 input is missing");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private void assertNoAbsolutePath(String serialized) {
        assertFalse(serialized.matches("(?s).*[A-Za-z]:\\\\.*"));
        assertFalse(serialized.contains("file:/"));
    }
}
