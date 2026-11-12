package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class Pvs1PrerequisiteInputResolverTest {

    private static final String PROFILE_SHA256 =
            "66b9c089cbcf6f4eaedb6ba6de387fd00a11cfb0e52449ada9f0386bb8192822";
    private static final String VEP_SHA256 =
            "43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

    private ObjectNode context;
    private ObjectNode atomic;

    @BeforeEach
    void setUp() throws Exception {
        NormalizedVariantKey key = new NormalizedVariantKey(
                "GRCh38", "12", 120989032, "C", "T");
        context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
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
        refreshContextId(context);

        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(
                mapper, adapter, clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                vepManifest(), "VEP_116_GRCH38");
        atomic = wiring.annotateTranscriptProvider(
                context, fixedVepProvider(),
                RuntimeEvidenceV2ProviderWiringTest.variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), binding).get(0);
    }

    @Test
    void exactPinnedInputIsReadyAndPredictsNmdWithoutCriterionOutput() {
        Pvs1PrerequisiteInputResolver resolver =
                new Pvs1PrerequisiteInputResolver(mapper);

        Pvs1PrerequisiteInputResolver.Result result = resolver.resolve(context, atomic);

        assertEquals("INPUT_READY", result.getStatus());
        assertEquals("PREDICTED_NMD", result.getNmdPrediction());
        assertNull(result.getReasonCode());
        assertEquals(PROFILE_SHA256, result.getProfileSha256());
        assertEquals(context.path("contextId").asText(), result.getContextId());
        assertEquals(atomic.path("evidenceId").asText(), result.getEvidenceId());
        assertFalse(resolver.getProfile().path("activation")
                .path("evaluationEnabled").asBoolean(true));
        assertFalse(resolver.getProfile().path("fixedInputDecision")
                .path("pvs1CriterionEvaluationImplemented").asBoolean(true));
    }

    @Test
    void profilePinsFourDistinctAuthorityRolesAndTheirProvenance() {
        ObjectNode profile = Pvs1PrerequisiteInputResolver.loadProfile(mapper);
        JsonNode sources = profile.path("authorityBindings");

        assertEquals(4, sources.size());
        assertEquals("6195d0bf2f4cd7e86016ff13cdd78ad2696168b224fafc60f10cfc2409bc0ec1",
                source(sources, "CURRENT_GENE_DISEASE_LOF_MECHANISM_AND_PVS1_RULE")
                        .path("artifactSha256").asText());
        assertEquals("1293ca26fdf80715482f5cba7ff66458bcec76a5d3fde8d69c2f9c2b90d88178",
                source(sources, "HISTORICAL_FIXED_POINT_EXON_NMD_AND_LOF_EVIDENCE_ONLY")
                        .path("artifactSha256").asText());
        assertEquals("568eee6512c9c58ff82c25e533834209bc6b6dd09c089aa0ce4d4a86e0246f88",
                source(sources, "FIXED_POINT_VARIATION_AND_EXACT_ALLELE_IDENTITY")
                        .path("recordSha256").asText());
        assertEquals("55eedf80f2b268702f920ee8d0f7167f365d92caeb6b2ab0da1afac52db72245",
                source(sources, "FIXED_POINT_TRANSCRIPT_AND_PROTEIN_MANE_MAPPING")
                        .path("artifactSha256").asText());
    }

    @Test
    void wrongDiseaseMoiAndTranscriptAreNotReady() {
        ObjectNode wrongDisease = context.deepCopy();
        ((ObjectNode) wrongDisease.path("disease")).put("mondoId", "MONDO:0000001");
        assertNotReady(wrongDisease, atomic, "CONTEXT_MONDO_MISMATCH");

        ObjectNode wrongMoi = context.deepCopy();
        wrongMoi.put("moi", "AUTOSOMAL_RECESSIVE");
        assertNotReady(wrongMoi, atomic, "CONTEXT_MOI_MISMATCH");

        ObjectNode wrongTranscript = context.deepCopy();
        ((ObjectNode) wrongTranscript.path("transcript")).put("version", 10);
        assertNotReady(wrongTranscript, atomic, "CONTEXT_TRANSCRIPT_MISMATCH");

        ObjectNode wrongGene = context.deepCopy();
        ((ObjectNode) wrongGene.path("gene")).put("hgncId", "HGNC:6927");
        assertNotReady(wrongGene, atomic, "CONTEXT_HGNC_MISMATCH");
    }

    @Test
    void missingMalformedAndLateExonAreNotReady() {
        ObjectNode late = atomic.deepCopy();
        setMeasurement(late, "vep.exon", "9/10");
        assertNotReady(context, late, "VEP_EXON_NOT_NMD_READY");

        ObjectNode missing = atomic.deepCopy();
        removeMeasurement(missing, "vep.exon");
        assertNotReady(context, missing, "VEP_EXON_MISSING");

        ObjectNode malformed = atomic.deepCopy();
        setMeasurement(malformed, "vep.exon", "exon-two");
        assertNotReady(context, malformed, "VEP_EXON_MALFORMED");
    }

    @Test
    void unavailableNoRecordAndNonStopInputsAreNotReady() {
        ObjectNode unavailable = atomic.deepCopy();
        ((ObjectNode) unavailable.path("resourceAvailability")).put("status", "UNAVAILABLE");
        assertNotReady(context, unavailable, "VEP_RESOURCE_NOT_AVAILABLE");

        ObjectNode noRecord = atomic.deepCopy();
        ((ObjectNode) noRecord.path("queryOutcome")).put("status", "NO_RECORD");
        assertNotReady(context, noRecord, "VEP_QUERY_NOT_HIT");

        ObjectNode nonStop = atomic.deepCopy();
        setMeasurement(nonStop, "consequenceTerm", "frameshift_variant");
        assertNotReady(context, nonStop, "VEP_CONSEQUENCE_NOT_ALLOWED");
    }

    @Test
    void profileFingerprintAndVepSourceBindingAreRequired() {
        ObjectNode tamperedProfile = Pvs1PrerequisiteInputResolver.loadProfile(mapper);
        tamperedProfile.put("profileVersion", "1.0.1");
        Pvs1PrerequisiteInputResolver tampered =
                new Pvs1PrerequisiteInputResolver(mapper, tamperedProfile);
        assertEquals("PROFILE_IDENTITY_MISMATCH",
                tampered.resolve(context, atomic).getReasonCode());

        ObjectNode wrongResource = atomic.deepCopy();
        ((ObjectNode) wrongResource.path("source"))
                .put("resourceSha256", "0".repeat(64));
        assertNotReady(context, wrongResource, "VEP_SOURCE_IDENTITY_MISMATCH");
    }

    private void assertNotReady(JsonNode candidateContext,
                                ObjectNode candidateEvidence,
                                String reasonCode) {
        Pvs1PrerequisiteInputResolver.Result result =
                new Pvs1PrerequisiteInputResolver(mapper)
                        .resolve(candidateContext, candidateEvidence);
        assertEquals("INPUT_NOT_READY", result.getStatus());
        assertEquals("NOT_EVALUATED", result.getNmdPrediction());
        assertEquals(reasonCode, result.getReasonCode());
    }

    private JsonNode source(JsonNode sources, String role) {
        for (JsonNode source : sources) {
            if (role.equals(source.path("role").asText())) return source;
        }
        return mapper.missingNode();
    }

    private void setMeasurement(ObjectNode evidence, String metricId, String value) {
        JsonNode measurement = measurement(evidence, metricId);
        ((ObjectNode) measurement).put("rawValue", value).put("normalizedValue", value);
    }

    private void removeMeasurement(ObjectNode evidence, String metricId) {
        ArrayNode measurements = (ArrayNode) evidence.path("measurements");
        for (int index = measurements.size() - 1; index >= 0; index--) {
            if (metricId.equals(measurements.get(index).path("metricId").asText())) {
                measurements.remove(index);
            }
        }
    }

    private JsonNode measurement(JsonNode evidence, String metricId) {
        for (JsonNode measurement : evidence.path("measurements")) {
            if (metricId.equals(measurement.path("metricId").asText())) return measurement;
        }
        return mapper.missingNode();
    }

    private TranscriptAnnotationProvider fixedVepProvider() {
        TranscriptConsequence consequence = new TranscriptConsequence(
                "HNF1A", "ENSG00000135100", "ENST00000257555",
                "ENST00000257555.11:c.526C>T", "ENSP00000257555.5:p.Gln176Ter",
                "stop_gained", "ENSG00000135100", "ENST00000257555", "Ensembl",
                "HIGH", "2/10", null, true, true, false, "176", "Q/*", "Caa/Taa",
                null, null, null, "VEP_CACHED", "COMPLETED", "116.0", clock.instant());
        return new TranscriptAnnotationProvider() {
            @Override
            public String source() {
                return "VEP_CACHED";
            }

            @Override
            public String version() {
                return "116.0";
            }

            @Override
            public List<TranscriptConsequence> annotate(
                    VariantInterpretation variant, AnalysisContext context) {
                return Collections.singletonList(consequence);
            }
        };
    }

    private ObjectNode vepManifest() throws IOException {
        Path manifestPath = Files.isRegularFile(Paths.get("..", "docs", "execution",
                "E2_RESOURCE_IDENTITY_MANIFEST.json"))
                ? Paths.get("..", "docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json")
                : Paths.get("docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json");
        ObjectNode manifest = (ObjectNode) mapper.readTree(manifestPath.toFile());
        assertEquals(VEP_SHA256,
                Pvs1PrerequisiteInputResolver.loadProfile(mapper).path("vepInput")
                        .path("identityFingerprintSha256").asText());
        return manifest;
    }

    private void refreshContextId(ObjectNode value) throws IOException {
        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", value.path("scope").deepCopy());
        projection.put("alleleId", value.path("variant").path("alleleId").asText());
        projection.put("hgncId", value.path("gene").path("hgncId").asText());
        projection.put("mondoId", value.path("disease").path("mondoId").asText());
        projection.put("moi", value.path("moi").asText());
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", value.path("transcript").path("accession").asText());
        transcript.put("version", value.path("transcript").path("version").asInt());
        projection.put("patientContextId",
                value.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId",
                value.path("patientContext").path("familyContextId").deepCopy());
        value.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
    }
}
