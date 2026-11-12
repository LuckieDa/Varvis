package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.tribble.readers.TabixReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only E3-K0 probe. It checks whether the existing ClinVar and VEP paths
 * carry enough input for a later PS1/PM5 implementation; it never evaluates a
 * criterion.
 */
class ClinVarVepPs1Pm5ReadinessProbeTest {

    private static final String RUN_PROPERTY = "varvis.e3k0.runtime-smoke";
    private static final String CLINVAR_RESOURCE_ID = "CLINVAR_2026_03_21_GRCH38";
    private static final String VEP_RESOURCE_ID = "VEP_116_GRCH38";
    private static final String TRANSCRIPT = "ENST00000646891";
    private static final int TRANSCRIPT_VERSION = 2;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @TempDir
    Path tempDirectory;

    @Test
    void proteinTupleProbeSeparatesTheFourRequiredRelations() {
        ProteinTuple target = new ProteinTuple("ENST00000646891.2", "600", "V", "E");

        assertEquals(ProteinRelation.SAME_AMINO_ACID_CHANGE,
                relation(target, new ProteinTuple("ENST00000646891.2", "600", "V", "E")));
        assertEquals(ProteinRelation.SAME_RESIDUE_DIFFERENT_AMINO_ACID,
                relation(target, new ProteinTuple("ENST00000646891.2", "600", "V", "K")));
        assertEquals(ProteinRelation.DIFFERENT_TRANSCRIPT,
                relation(target, new ProteinTuple("ENST00000288602.6", "600", "V", "E")));
        assertEquals(ProteinRelation.DIFFERENT_PROTEIN_POSITION,
                relation(target, new ProteinTuple("ENST00000646891.2", "601", "V", "E")));
    }

    @Test
    void clinVarCarrierPreservesAvailableConditionAwareAggregateContext() throws Exception {
        NormalizedVariantKey key = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "7", 140753336, "A", "T");
        String version = "ClinVar 2026-03-21 GRCh38";
        ClinVarEvidenceMapper.Record record = new ClinVarEvidenceMapper.Record(
                "LOCAL_CLINVAR", version, "GRCh38", "Pathogenic",
                List.of("Pathogenic"), "reviewed_by_expert_panel",
                List.of(new ClinVarEvidenceMapper.Condition(
                        "Synthetic condition", "MONDO:0000001")),
                "13961", null, "15041", "rs113488022", "2026-01-01",
                "BRAF", "BRAF:1097", false);
        EvidenceItem item = ClinVarEvidenceMapper.record(key, record, "exact_allele");
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest(CLINVAR_RESOURCE_ID, "CLINVAR", "GRCh38", version),
                CLINVAR_RESOURCE_ID);

        ObjectNode atomic = wiring.annotateVariantProvider(
                RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key),
                fixedProvider(item, version),
                RuntimeEvidenceV2ProviderWiringTest.variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                binding).get(0);

        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertTrue(measurementIds(atomic).containsAll(Set.of(
                "clinicalSignificance", "variationId", "reviewStatus", "geneSymbol",
                "conflictState", "lastEvaluated", "condition.0.name",
                "condition.0.identifiers")));

        ClinVarEvidenceMapper.Record conflictRecord = new ClinVarEvidenceMapper.Record(
                "LOCAL_CLINVAR", version, "GRCh38",
                "Conflicting_classifications_of_pathogenicity",
                List.of("Pathogenic", "Likely_benign"),
                "criteria_provided,_conflicting_classifications",
                record.conditions, "13961", null, "15041", "rs113488022", null,
                "BRAF", "BRAF:1097", true);
        EvidenceItem conflict = ClinVarEvidenceMapper.record(key, conflictRecord, "exact_allele");
        ObjectNode preserved = wiring.annotateVariantProvider(
                RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key),
                fixedProvider(conflict, version),
                RuntimeEvidenceV2ProviderWiringTest.variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                binding).get(0);

        assertEquals("SUCCEEDED", preserved.path("resourceExecution").path("status").asText());
        assertEquals("AVAILABLE", preserved.path("resourceAvailability").path("status").asText());
        assertEquals("HIT", preserved.path("queryOutcome").path("status").asText());
        assertEquals("CONFLICTING_INTERPRETATIONS", measurement(preserved, "conflictState"));
    }

    @Test
    @EnabledIfSystemProperty(named = RUN_PROPERTY, matches = "true")
    void publicBrafFixedPointProducesPathFreeReadinessReport() throws Exception {
        VariantAnalysisProperties properties = localProperties();
        enableOnlyClinVarAndVep(properties);
        JsonNode manifest = mapper.readTree(requiredManifest().toFile());
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding clinVarBinding =
                wiring.bind(manifest, CLINVAR_RESOURCE_ID);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding vepBinding =
                wiring.bind(manifest, VEP_RESOURCE_ID);
        Path clinVarPath = properties.getClinVar().getGrch38File();

        HeaderSnapshot header = header(clinVarPath);
        assertEquals("2026-03-21", header.fileDate);
        assertEquals("ClinVar", header.source);
        assertEquals("GRCh38", header.reference);
        assertTrue(header.infoIds.containsAll(List.of(
                "ALLELEID", "CLNDN", "CLNDISDB", "CLNREVSTAT", "CLNSIG",
                "CLNSIGCONF", "CLNSIGSCV", "GENEINFO")));
        assertFalse(header.infoIds.contains("CLNDATE"));
        assertFalse(header.infoIds.contains("LastEvaluated"));
        JsonNode clinVarIdentity = resource(manifest, CLINVAR_RESOURCE_ID).path("identity");
        assertEquals(clinVarIdentity.path("dataByteLength").asLong(), Files.size(clinVarPath));
        Path clinVarIndex = clinVarPath.resolveSibling(clinVarPath.getFileName() + ".tbi");
        assertEquals(clinVarIdentity.path("indexByteLength").asLong(), Files.size(clinVarIndex));

        NormalizedVariantKey key = RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "7", 140753336, "A", "T");
        ObjectNode context = brafContext(key);
        VariantInterpretation variant = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        AnalysisContext analysisContext = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);

        ClinVarTabixProvider clinVar = new ClinVarTabixProvider(properties);
        EvidenceItem clinVarItem = clinVar.annotate(List.of(variant), analysisContext).get(key).get(0);
        assertEquals("CONFLICTING", clinVarItem.getEffectiveStatus());
        assertEquals("exact_allele", clinVarItem.getDetails().get("matchType"));
        assertEquals("13961", clinVarItem.getDetails().get("variationId"));
        assertEquals("BRAF", clinVarItem.getDetails().get("geneSymbol"));
        assertEquals("CONFLICTING_INTERPRETATIONS", clinVarItem.getDetails().get("conflictState"));
        assertFalse(clinVarItem.getDetails().containsKey("CLNSIGSCV"));
        assertFalse(clinVarItem.getDetails().containsKey("submittedAssertionAccessions"));

        String rawLine = exactLine(clinVarPath, key);
        String[] columns = rawLine.split("\t", -1);
        Map<String, String> rawInfo = info(columns[7]);
        assertEquals("13961", columns[2]);
        assertEquals(groupCount(rawInfo.get("CLNDN")), groupCount(rawInfo.get("CLNDISDB")));
        int providerConditionCount = ((List<?>) clinVarItem.getDetails().get("conditions")).size();
        assertEquals(groupCount(rawInfo.get("CLNDN")), providerConditionCount);
        assertTrue(rawInfo.containsKey("CLNSIGSCV"));
        assertEquals(List.of(rawInfo.get("CLNSIGSCV").split("\\|")),
                clinVarItem.getDetails().get("contributingSubmissionIdentifiers"));

        ObjectNode clinVarAtomic = wiring.annotateVariantProvider(
                context, clinVar, variant, analysisContext, clinVarBinding).get(0);
        assertEquals("SUCCEEDED", clinVarAtomic.path("resourceExecution").path("status").asText());
        assertEquals("AVAILABLE", clinVarAtomic.path("resourceAvailability").path("status").asText());
        assertEquals("HIT", clinVarAtomic.path("queryOutcome").path("status").asText());
        assertEquals("CONFLICTING_INTERPRETATIONS", measurement(clinVarAtomic, "conflictState"));

        VepTranscriptAnnotationProvider vep = new VepTranscriptAnnotationProvider(properties);
        ObjectNode vepAtomic = wiring.annotateTranscriptProvider(
                context, vep, variant, analysisContext, vepBinding).get(0);
        assertEquals("HIT", vepAtomic.path("queryOutcome").path("status").asText());
        assertEquals("ENST00000646891.2", measurement(vepAtomic, "vep.selectedTranscriptId"));
        assertEquals("ENSP00000493543.1:p.Val600Glu", measurement(vepAtomic, "hgvsp"));
        assertEquals("600", measurement(vepAtomic, "proteinPosition"));
        assertEquals("V/E", measurement(vepAtomic, "aminoAcids"));
        assertEquals("V600E", measurement(vepAtomic, "aminoAcidChange"));
        assertEquals("missense_variant", measurement(vepAtomic, "consequenceTerm"));

        ObjectNode verifiedCurrentState = mapper.createObjectNode();
        verifiedCurrentState.set("clinVar", clinVarAtomic);
        verifiedCurrentState.set("vep", vepAtomic);
        String serialized = mapper.writeValueAsString(verifiedCurrentState);
        assertFalse(serialized.contains("F:\\"));
        assertFalse(serialized.contains("D:\\"));
        assertFalse(serialized.contains("file:/"));
        assertFalse(serialized.contains("criterion-evaluation-v2"));
    }

    private ObjectNode brafContext(NormalizedVariantKey key) throws IOException {
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key, TRANSCRIPT);
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:1097").put("symbol", "BRAF");
        ((ObjectNode) context.path("transcript"))
                .put("version", TRANSCRIPT_VERSION)
                .put("selectionPolicy", "E3_K0_SYNTHETIC_PINNED")
                .put("annotationSourceVersion", "VEP 116.0");

        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", context.path("variant").path("alleleId").asText());
        projection.put("hgncId", "HGNC:1097");
        projection.put("mondoId", context.path("disease").path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", TRANSCRIPT);
        transcript.put("version", TRANSCRIPT_VERSION);
        projection.put("patientContextId",
                context.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId",
                context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
        return context;
    }

    private VariantEvidenceProvider fixedProvider(EvidenceItem item, String version) {
        return new VariantEvidenceProvider() {
            @Override public String source() { return "CLINVAR"; }
            @Override public String version() { return version; }
            @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                    List<VariantInterpretation> variants, AnalysisContext context) {
                return Collections.singletonMap(
                        variants.get(0).getObservation().getKey(), List.of(item));
            }
        };
    }

    private ObjectNode manifest(String resourceId,
                                String provider,
                                String assembly,
                                String resourceVersion) {
        ObjectNode identity = mapper.createObjectNode();
        identity.put("provider", provider);
        identity.put("assembly", assembly);
        identity.put("release", resourceVersion);
        ObjectNode resource = mapper.createObjectNode();
        resource.put("resourceId", resourceId);
        resource.put("provider", provider);
        resource.put("assembly", assembly);
        resource.put("resourceVersion", resourceVersion);
        resource.put("identityFingerprintSha256", adapter.canonicalSha256(identity));
        resource.set("identity", identity);
        ObjectNode manifest = mapper.createObjectNode();
        manifest.putArray("resources").add(resource);
        return manifest;
    }

    private Set<String> measurementIds(ObjectNode atomic) {
        Set<String> ids = new LinkedHashSet<String>();
        for (JsonNode value : atomic.path("measurements")) {
            ids.add(value.path("metricId").asText());
        }
        return ids;
    }

    private String measurement(ObjectNode atomic, String metricId) {
        for (JsonNode value : atomic.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                return value.path("rawValue").asText();
            }
        }
        throw new AssertionError("Missing measurement " + metricId);
    }

    private JsonNode resource(JsonNode manifest, String resourceId) {
        for (JsonNode resource : manifest.path("resources")) {
            if (resourceId.equals(resource.path("resourceId").asText())) return resource;
        }
        throw new IllegalStateException("Missing resource " + resourceId);
    }

    private HeaderSnapshot header(Path clinVarPath) throws IOException {
        String fileDate = null;
        String source = null;
        String reference = null;
        Set<String> infoIds = new LinkedHashSet<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(clinVarPath)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("##fileDate=")) fileDate = line.substring("##fileDate=".length());
                if (line.startsWith("##source=")) source = line.substring("##source=".length());
                if (line.startsWith("##reference=")) reference = line.substring("##reference=".length());
                if (line.startsWith("##INFO=<ID=")) {
                    int start = "##INFO=<ID=".length();
                    int end = line.indexOf(',', start);
                    if (end > start) infoIds.add(line.substring(start, end));
                }
                if (line.startsWith("#CHROM")) break;
            }
        }
        return new HeaderSnapshot(fileDate, source, reference, infoIds);
    }

    private String exactLine(Path clinVarPath, NormalizedVariantKey key) throws IOException {
        try (TabixReader reader = new TabixReader(clinVarPath.toString())) {
            TabixReader.Iterator iterator = reader.query(
                    key.getChromosome() + ":" + key.getPosition() + "-" + key.getPosition());
            String line;
            while (iterator != null && (line = iterator.next()) != null) {
                String[] columns = line.split("\t", -1);
                if (columns.length >= 8
                        && key.getReference().equals(columns[3])
                        && List.of(columns[4].split(",")).contains(key.getAlternate())) {
                    return line;
                }
            }
        }
        throw new IllegalStateException("The bounded ClinVar fixed point has no exact allele record");
    }

    private Map<String, String> info(String field) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String item : field.split(";")) {
            int separator = item.indexOf('=');
            if (separator > 0) values.put(item.substring(0, separator), item.substring(separator + 1));
        }
        return values;
    }

    private int groupCount(String value) {
        return value == null || value.isEmpty() || ".".equals(value)
                ? 0 : value.split("\\|", -1).length;
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
                System.getProperty("varvis.e3k0.resource-root"),
                System.getenv("VARVIS_RESOURCE_ROOT"));
        assertNotNull(resourceRoot,
                "Set varvis.e3k0.resource-root or VARVIS_RESOURCE_ROOT for the opt-in probe");
        String projectRoot = firstText(
                System.getProperty("varvis.e3k0.project-root"),
                System.getenv("VARVIS_PROJECT_ROOT"), resourceRoot);
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("e3k0-ignored-local-config", local));
        Map<String, Object> roots = new LinkedHashMap<String, Object>();
        roots.put("VARVIS_RESOURCE_ROOT", resourceRoot);
        roots.put("VARVIS_PROJECT_ROOT", projectRoot);
        environment.getPropertySources().addFirst(new MapPropertySource("e3k0-local-roots", roots));
        return Binder.get(environment)
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalStateException("variant-analysis local config did not bind"));
    }

    private void enableOnlyClinVarAndVep(VariantAnalysisProperties properties) {
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setAllowVcfInfoFallback(false);
        properties.getClinVar().getCompactIndex().setEnabled(false);
        properties.getVep().setEnabled(true);
        properties.getVep().setExecutionEnabled(true);
        properties.getVep().setLofteeEnabled(false);
        properties.getVep().setTempDir(tempDirectory.resolve("vep").toString());
        properties.getVep().setCleanupTemp(true);
        VariantAnalysisProperties.Vep.ResourceProfile grch38 = properties.getVep()
                .getResources().values().stream()
                .filter(profile -> "GRCh38".equals(profile.normalizedAssembly()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing local GRCh38 VEP profile"));
        grch38.setEnabled(true);
    }

    private Path requiredManifest() {
        String configured = System.getProperty("varvis.e3k0.manifest");
        if (firstText(configured) != null) return requiredPath(Paths.get(configured.trim()));
        return requiredPath(
                Paths.get("..", "docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"),
                Paths.get("docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json"));
    }

    private Path requiredPath(Path... candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)) return absolute;
        }
        throw new IllegalStateException("Required local E3-K0 input is missing");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private ProteinRelation relation(ProteinTuple target, ProteinTuple comparator) {
        if (!target.transcript.equals(comparator.transcript)) return ProteinRelation.DIFFERENT_TRANSCRIPT;
        if (!target.position.equals(comparator.position)) return ProteinRelation.DIFFERENT_PROTEIN_POSITION;
        if (!target.reference.equals(comparator.reference)) return ProteinRelation.DIFFERENT_REFERENCE_AMINO_ACID;
        return target.alternate.equals(comparator.alternate)
                ? ProteinRelation.SAME_AMINO_ACID_CHANGE
                : ProteinRelation.SAME_RESIDUE_DIFFERENT_AMINO_ACID;
    }

    private enum ProteinRelation {
        SAME_AMINO_ACID_CHANGE,
        SAME_RESIDUE_DIFFERENT_AMINO_ACID,
        DIFFERENT_TRANSCRIPT,
        DIFFERENT_PROTEIN_POSITION,
        DIFFERENT_REFERENCE_AMINO_ACID
    }

    private static final class ProteinTuple {
        private final String transcript;
        private final String position;
        private final String reference;
        private final String alternate;

        private ProteinTuple(String transcript, String position, String reference, String alternate) {
            this.transcript = transcript;
            this.position = position;
            this.reference = reference;
            this.alternate = alternate;
        }
    }

    private static final class HeaderSnapshot {
        private final String fileDate;
        private final String source;
        private final String reference;
        private final Set<String> infoIds;

        private HeaderSnapshot(String fileDate, String source, String reference, Set<String> infoIds) {
            this.fileDate = fileDate;
            this.source = source;
            this.reference = reference;
            this.infoIds = infoIds;
        }
    }
}
