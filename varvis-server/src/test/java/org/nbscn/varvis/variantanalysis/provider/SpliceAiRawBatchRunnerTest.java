package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpliceAiRawBatchRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path reference;

    @BeforeEach
    void createReference() throws IOException {
        reference = tempDir.resolve("reference.fa");
        Files.writeString(reference, ">1\nA\n");
    }

    @Test
    void successfulBatchStartsOneProcessAndUsesSafeStableQueryIds() {
        FakeProcessRunner process = new FakeProcessRunner(Mode.SUCCEEDED, mapper);
        SpliceAiRawBatchRunner runner = runner(process);

        SpliceAiRawBatchRunner.BatchResult result = runner.run(List.of(
                query("1", 925775, "G", "T"),
                query("1", 925776, "A", "G")
        ));

        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(1, process.calls);
        assertEquals(2, process.seenRequest.path("variants").size());
        assertEquals(4999, process.seenRequest.path("distanceBp").asInt());
        assertEquals(0, process.seenRequest.path("mask").asInt());
        String queryId = process.seenRequest.path("variants").get(0).path("queryId").asText();
        assertTrue(queryId.matches("spliceai-query-sha256-[0-9a-f]{64}"));
        assertFalse(queryId.contains("|"));
        assertEquals("--reference-fasta", process.command.get(process.command.size() - 6));
        assertEquals("--batch-request", process.command.get(process.command.size() - 4));
        assertEquals("--output", process.command.get(process.command.size() - 2));
        assertFalse(process.command.get(process.command.size() - 5).contains("\\"));
        assertFalse(process.command.get(process.command.size() - 3).contains("\\"));
        assertFalse(process.command.get(process.command.size() - 1).contains("\\"));
    }

    @Test
    void structuredFailureAndTimeoutRemainDistinctFromNoRecord() {
        SpliceAiRawBatchRunner.BatchResult failed =
                runner(new FakeProcessRunner(Mode.STRUCTURED_FAILED, mapper)).run(List.of(query()));
        SpliceAiRawBatchRunner.BatchResult timeout =
                runner(new FakeProcessRunner(Mode.STRUCTURED_TIMEOUT, mapper)).run(List.of(query()));

        assertEquals("FAILED", failed.getStatus());
        assertEquals("SPLICEAI_CLI_FAILED", failed.getReasonCode());
        assertNull(failed.getResult());
        assertEquals("TIMEOUT", timeout.getStatus());
        assertEquals("SPLICEAI_CLI_TIMEOUT", timeout.getReasonCode());
        assertNull(timeout.getResult());
    }

    @Test
    void alleleOrExactMatchMismatchIsAnInvalidResult() {
        SpliceAiRawBatchRunner.BatchResult result =
                runner(new FakeProcessRunner(Mode.INVALID_ALLELE, mapper)).run(List.of(query()));

        assertEquals("FAILED", result.getStatus());
        assertEquals("SPLICEAI_BATCH_RESULT_INVALID", result.getReasonCode());
    }

    private SpliceAiRawBatchRunner runner(ExternalProcessRunner processRunner) {
        return new SpliceAiRawBatchRunner(
                mapper, List.of("wsl.exe", "bash", "spliceai-wrapper.sh", "python", "probe.py"),
                reference, tempDir, Duration.ofSeconds(30), 10, processRunner);
    }

    private SpliceAiRawBatchRunner.Query query() {
        return query("1", 925775, "G", "T");
    }

    private SpliceAiRawBatchRunner.Query query(
            String contig, int position, String referenceAllele, String alternate) {
        NormalizedVariantKey key = new NormalizedVariantKey(
                "GRCh38", contig, position, referenceAllele, alternate);
        return new SpliceAiRawBatchRunner.Query(
                SpliceAiRawBatchRunner.stableQueryId(key), key, "SAMD11", "ENST00000342066.8");
    }

    private enum Mode {
        SUCCEEDED,
        STRUCTURED_FAILED,
        STRUCTURED_TIMEOUT,
        INVALID_ALLELE
    }

    private static final class FakeProcessRunner implements ExternalProcessRunner {

        private final Mode mode;
        private final ObjectMapper mapper;
        private int calls;
        private List<String> command;
        private ObjectNode seenRequest;

        private FakeProcessRunner(Mode mode, ObjectMapper mapper) {
            this.mode = mode;
            this.mapper = mapper;
        }

        @Override
        public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException {
            calls++;
            this.command = command;
            Path requestPath = argumentPath(command, "--batch-request");
            Path outputPath = argumentPath(command, "--output");
            seenRequest = (ObjectNode) mapper.readTree(requestPath.toFile());
            ObjectNode result = mapper.createObjectNode();
            result.put("resultSchemaVersion", SpliceAiRawBatchRunner.RESULT_SCHEMA_VERSION);
            result.put("requestId", seenRequest.path("requestId").asText());
            ArrayNode results = result.putArray("results");
            if (mode == Mode.STRUCTURED_FAILED || mode == Mode.STRUCTURED_TIMEOUT) {
                result.put("status", mode == Mode.STRUCTURED_TIMEOUT ? "TIMEOUT" : "FAILED");
                result.put("reasonCode", mode == Mode.STRUCTURED_TIMEOUT
                        ? "SPLICEAI_CLI_TIMEOUT" : "SPLICEAI_CLI_FAILED");
            } else {
                result.put("status", "SUCCEEDED");
                for (com.fasterxml.jackson.databind.JsonNode requested : seenRequest.path("variants")) {
                    ObjectNode value = results.addObject();
                    value.put("queryId", requested.path("queryId").asText());
                    value.set("allele", requested.path("allele").deepCopy());
                    value.put("status", "HIT");
                    value.put("exactAlleleMatch", true);
                    ObjectNode annotation = value.putArray("annotations").addObject();
                    annotation.put("geneSymbol", requested.path("geneSymbol").asText());
                    annotation.putObject("deltaScores")
                            .put("DS_AG", "0.40").put("DS_AL", "0.00")
                            .put("DS_DG", "0.96").put("DS_DL", "0.44");
                    annotation.putObject("deltaPositions")
                            .put("DP_AG", 147).put("DP_AL", 1098)
                            .put("DP_DG", -2).put("DP_DL", 25);
                    annotation.put("maximumRawScore", "0.96");
                }
                if (mode == Mode.INVALID_ALLELE) {
                    results.get(0).path("allele").deepCopy();
                    ((ObjectNode) results.get(0).path("allele")).put("alternate", "A");
                }
            }
            mapper.writeValue(outputPath.toFile(), result);
            return new ProcessResult(0, "completed", false);
        }

        private Path argumentPath(List<String> command, String name) {
            return java.nio.file.Paths.get(command.get(command.indexOf(name) + 1));
        }
    }
}
