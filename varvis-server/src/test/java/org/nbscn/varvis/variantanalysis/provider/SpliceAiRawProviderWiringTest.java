package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpliceAiRawProviderWiringTest {

    private static final String PROFILE_RESOURCE =
            "/acmg/spliceai-pp3-bp4-supporting-profile-v1.json";
    private static final String RESOURCE_ID =
            "SPLICEAI_V1_3_1_RAW_GRCH38_LOCAL_COMPUTE";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @TempDir
    Path tempDir;

    private Path reference;
    private ObjectNode profile;
    private RuntimeEvidenceV2ProviderWiring wiring;
    private RuntimeEvidenceV2ProviderWiring.ResourceBinding binding;

    @BeforeEach
    void setUp() throws Exception {
        reference = tempDir.resolve("reference.fa");
        Files.writeString(reference, ">1\nA\n");
        profile = (ObjectNode) mapper.readTree(getClass().getResourceAsStream(PROFILE_RESOURCE));
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        binding = wiring.bind(profile, RESOURCE_ID);
    }

    @Test
    void twoVariantsUseOneRawProcessAndProduceExactDecimalMeasurements() throws Exception {
        NormalizedVariantKey firstKey = key(925775, "G", "T");
        NormalizedVariantKey secondKey = key(925776, "A", "G");
        VariantInterpretation first = variant(firstKey, "splice_region_variant");
        VariantInterpretation second = variant(secondKey, "intron_variant");
        Map<NormalizedVariantKey, JsonNode> contexts = new LinkedHashMap<>();
        contexts.put(firstKey, context(firstKey));
        contexts.put(secondKey, context(secondKey));
        RecordingProcessRunner process = new RecordingProcessRunner(mapper, "SYNTH1");

        Map<NormalizedVariantKey, List<ObjectNode>> result = wiring.annotateSpliceAiRawBatch(
                contexts,
                new SpliceAiProvider(new VariantAnalysisProperties()),
                List.of(first, second),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(firstKey),
                binding,
                runner(process));

        assertEquals(1, process.calls);
        assertEquals(2, process.seenRequest.path("variants").size());
        ObjectNode atomic = only(result, firstKey);
        assertEquals("COMPUTATIONAL_PREDICTION", atomic.path("evidenceType").asText());
        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertEquals("APPLICABLE",
                atomic.path("resourceApplicability").path("status").asText());
        assertEquals("0.96", measurement(atomic, "spliceai.maximumRawDeltaScore")
                .path("rawValue").asText());
        assertEquals("0.40", measurement(atomic, "spliceai.DS_AG")
                .path("rawValue").asText());
        assertEquals("0.4", measurement(atomic, "spliceai.DS_AG")
                .path("normalizedValue").asText());
        assertEquals("1098", measurement(atomic, "spliceai.DP_AL")
                .path("rawValue").asText());
        assertTrue(measurement(atomic, "spliceai.exactAlleleMatch")
                .path("rawValue").asBoolean());
        assertTrue(measurement(atomic, "spliceai.geneContextMatch")
                .path("rawValue").asBoolean());
        assertEquals(binding.getIdentityFingerprintSha256(),
                atomic.path("source").path("resourceSha256").asText());
    }

    @Test
    void exactAlleleWithDifferentGeneRemainsHitButIsNotEvaluated() throws Exception {
        NormalizedVariantKey key = key(925775, "G", "T");
        VariantInterpretation variant = variant(key, "synonymous_variant");
        RecordingProcessRunner process = new RecordingProcessRunner(mapper, "OTHER1");

        ObjectNode atomic = only(wiring.annotateSpliceAiRawBatch(
                Map.of(key, context(key)),
                new SpliceAiProvider(new VariantAnalysisProperties()),
                List.of(variant),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                binding,
                runner(process)), key);

        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertEquals("UNKNOWN", atomic.path("resourceApplicability").path("status").asText());
        assertEquals("SPLICEAI_GENE_CONTEXT_MISMATCH",
                atomic.path("resourceApplicability").path("reasonCode").asText());
        assertTrue(measurement(atomic, "spliceai.exactAlleleMatch")
                .path("rawValue").asBoolean());
        assertFalse(measurement(atomic, "spliceai.geneContextMatch")
                .path("rawValue").asBoolean());
        assertFalse(hasMeasurement(atomic, "spliceai.maximumRawDeltaScore"));
    }

    @Test
    void missenseAndCanonicalSpliceConsequencesNeverStartRawProcess() throws Exception {
        NormalizedVariantKey missenseKey = key(925775, "G", "T");
        NormalizedVariantKey donorKey = key(925776, "A", "G");
        RecordingProcessRunner process = new RecordingProcessRunner(mapper, "SYNTH1");

        Map<NormalizedVariantKey, List<ObjectNode>> values = wiring.annotateSpliceAiRawBatch(
                Map.of(missenseKey, context(missenseKey), donorKey, context(donorKey)),
                new SpliceAiProvider(new VariantAnalysisProperties()),
                List.of(variant(missenseKey, "missense_variant"),
                        variant(donorKey, "splice_donor_variant")),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(missenseKey),
                binding,
                runner(process));

        assertEquals(0, process.calls);
        assertEquals("NOT_APPLICABLE",
                only(values, missenseKey).path("resourceApplicability").path("status").asText());
        assertEquals("NOT_APPLICABLE",
                only(values, donorKey).path("resourceApplicability").path("status").asText());
    }

    @Test
    void noRecordFailureAndTimeoutRemainSeparateResourceOutcomes() throws Exception {
        ObjectNode noRecord = rawOutcome(Outcome.NO_RECORD);
        ObjectNode failed = rawOutcome(Outcome.FAILED);
        ObjectNode timeout = rawOutcome(Outcome.TIMEOUT);

        assertEquals("SUCCEEDED", noRecord.path("resourceExecution").path("status").asText());
        assertEquals("NO_RECORD", noRecord.path("queryOutcome").path("status").asText());
        assertTrue(noRecord.path("measurements").isEmpty());
        assertEquals("FAILED", failed.path("resourceExecution").path("status").asText());
        assertEquals("NOT_QUERIED", failed.path("queryOutcome").path("status").asText());
        assertTrue(failed.path("measurements").isEmpty());
        assertEquals("TIMEOUT", timeout.path("resourceExecution").path("status").asText());
        assertEquals("NOT_QUERIED", timeout.path("queryOutcome").path("status").asText());
        assertTrue(timeout.path("measurements").isEmpty());
    }

    private ObjectNode rawOutcome(Outcome outcome) throws Exception {
        NormalizedVariantKey key = key(925775, "G", "T");
        RecordingProcessRunner process = new RecordingProcessRunner(mapper, outcome, "SYNTH1");
        return only(wiring.annotateSpliceAiRawBatch(
                Map.of(key, context(key)),
                new SpliceAiProvider(new VariantAnalysisProperties()),
                List.of(variant(key, "splice_region_variant")),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                binding,
                runner(process)), key);
    }

    private SpliceAiRawBatchRunner runner(ExternalProcessRunner process) {
        return new SpliceAiRawBatchRunner(
                mapper, List.of("python", "spliceai_raw_resource_probe.py"),
                reference, tempDir, Duration.ofSeconds(30), 10, process);
    }

    private NormalizedVariantKey key(int position, String referenceAllele, String alternate) {
        return RuntimeEvidenceV2ProviderWiringTest.key(
                "GRCh38", "1", position, referenceAllele, alternate);
    }

    private ObjectNode context(NormalizedVariantKey key) throws IOException {
        return RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key, "NM_000001");
    }

    private VariantInterpretation variant(NormalizedVariantKey key, String consequence) {
        VariantInterpretation variant = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        variant.applyTranscriptAnnotations(List.of(selected(consequence)));
        return variant;
    }

    private TranscriptConsequence selected(String consequence) {
        return new TranscriptConsequence(
                "SYNTH1", "HGNC:1", "NM_000001.1",
                "NM_000001.1:c.100A>G", null, consequence,
                "HGNC:1", "NM_000001.1", "RefSeq", "LOW", null, "1/1",
                true, true, null, null, null, null,
                null, null, null, "VEP_SYNTHETIC", "COMPLETED", "synthetic-1",
                Instant.parse("2026-09-02T00:00:00Z"));
    }

    private ObjectNode only(Map<NormalizedVariantKey, List<ObjectNode>> values,
                            NormalizedVariantKey key) {
        List<ObjectNode> evidence = values.get(key);
        assertNotNull(evidence);
        assertEquals(1, evidence.size());
        return evidence.get(0);
    }

    private JsonNode measurement(ObjectNode evidence, String metricId) {
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) return value;
        }
        throw new AssertionError("Missing measurement " + metricId);
    }

    private boolean hasMeasurement(ObjectNode evidence, String metricId) {
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) return true;
        }
        return false;
    }

    private static final class RecordingProcessRunner implements ExternalProcessRunner {

        private final ObjectMapper mapper;
        private final Outcome outcome;
        private final String outputGene;
        private int calls;
        private ObjectNode seenRequest;

        private RecordingProcessRunner(ObjectMapper mapper, String outputGene) {
            this(mapper, Outcome.HIT, outputGene);
        }

        private RecordingProcessRunner(ObjectMapper mapper, Outcome outcome, String outputGene) {
            this.mapper = mapper;
            this.outcome = outcome;
            this.outputGene = outputGene;
        }

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException {
            calls++;
            Path request = argumentPath(command, "--batch-request");
            Path output = argumentPath(command, "--output");
            seenRequest = (ObjectNode) mapper.readTree(request.toFile());
            ObjectNode result = mapper.createObjectNode();
            result.put("resultSchemaVersion", SpliceAiRawBatchRunner.RESULT_SCHEMA_VERSION);
            result.put("requestId", seenRequest.path("requestId").asText());
            ArrayNode results = result.putArray("results");
            if (outcome == Outcome.FAILED || outcome == Outcome.TIMEOUT) {
                result.put("status", outcome.name());
                result.put("reasonCode", outcome == Outcome.TIMEOUT
                        ? "SPLICEAI_CLI_TIMEOUT" : "SPLICEAI_CLI_FAILED");
                mapper.writeValue(output.toFile(), result);
                return new ProcessResult(0, "completed", false);
            }
            result.put("status", "SUCCEEDED");
            for (JsonNode requested : seenRequest.path("variants")) {
                ObjectNode value = results.addObject();
                value.put("queryId", requested.path("queryId").asText());
                value.set("allele", requested.path("allele").deepCopy());
                if (outcome == Outcome.NO_RECORD) {
                    value.put("status", "NO_RECORD");
                    value.put("exactAlleleMatch", false);
                    value.putArray("annotations");
                    continue;
                }
                value.put("status", "HIT");
                value.put("exactAlleleMatch", true);
                ObjectNode annotation = value.putArray("annotations").addObject();
                annotation.put("geneSymbol", outputGene);
                annotation.putObject("deltaScores")
                        .put("DS_AG", "0.40").put("DS_AL", "0.00")
                        .put("DS_DG", "0.96").put("DS_DL", "0.44");
                annotation.putObject("deltaPositions")
                        .put("DP_AG", 147).put("DP_AL", 1098)
                        .put("DP_DG", -2).put("DP_DL", 25);
                annotation.put("maximumRawScore", "0.96");
            }
            mapper.writeValue(output.toFile(), result);
            return new ProcessResult(0, "completed", false);
        }

        private Path argumentPath(List<String> command, String name) {
            return java.nio.file.Paths.get(command.get(command.indexOf(name) + 1));
        }
    }

    private enum Outcome {
        HIT,
        NO_RECORD,
        FAILED,
        TIMEOUT
    }
}
