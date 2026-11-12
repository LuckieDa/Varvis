package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class GnomadAllSitesHailBatchRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void successfulBatchStartsOneProcessAndReturnsStructuredResult() {
        FakeProcessRunner process = new FakeProcessRunner(Mode.SUCCEEDED, mapper);
        GnomadAllSitesHailBatchRunner runner = runner(process);

        GnomadAllSitesHailBatchRunner.BatchResult result = runner.run(request());

        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(0, result.getExitCode());
        assertNull(result.getReasonCode());
        assertEquals("batch-1", result.getResult().path("requestId").asText());
        assertEquals(1, process.calls);
        assertEquals(2, process.seenRequest.path("loci").size());
        assertEquals("--batch-request", process.command.get(process.command.size() - 4));
        assertEquals("--output", process.command.get(process.command.size() - 2));
        assertFalse(process.command.get(process.command.size() - 3).contains("\\"));
        assertFalse(process.command.get(process.command.size() - 1).contains("\\"));
        assertFalse(result.getResult().toString().contains(tempDir.toString()));
    }

    @Test
    void nonZeroProcessResultIsFailedAndNeverNoRecord() {
        FakeProcessRunner process = new FakeProcessRunner(Mode.FAILED, mapper);

        GnomadAllSitesHailBatchRunner.BatchResult result = runner(process).run(request());

        assertEquals("FAILED", result.getStatus());
        assertEquals("HAIL_BATCH_PROCESS_FAILED", result.getReasonCode());
        assertEquals(2, result.getExitCode());
        assertNull(result.getResult());
        assertEquals(1, process.calls);
    }

    @Test
    void timedOutProcessResultRemainsTimeout() {
        FakeProcessRunner process = new FakeProcessRunner(Mode.TIMEOUT, mapper);

        GnomadAllSitesHailBatchRunner.BatchResult result = runner(process).run(request());

        assertEquals("TIMEOUT", result.getStatus());
        assertEquals("HAIL_BATCH_TIMEOUT", result.getReasonCode());
        assertEquals(-1, result.getExitCode());
        assertNull(result.getResult());
        assertEquals(1, process.calls);
    }

    @Test
    void malformedSuccessfulOutputIsFailed() {
        FakeProcessRunner process = new FakeProcessRunner(Mode.MALFORMED, mapper);

        GnomadAllSitesHailBatchRunner.BatchResult result = runner(process).run(request());

        assertEquals("FAILED", result.getStatus());
        assertEquals("HAIL_BATCH_RESULT_INVALID", result.getReasonCode());
        assertNull(result.getResult());
        assertEquals(1, process.calls);
    }

    private GnomadAllSitesHailBatchRunner runner(ExternalProcessRunner processRunner) {
        return new GnomadAllSitesHailBatchRunner(
                mapper,
                List.of("python", "gnomad_all_sites_hail_probe.py", "--profile", "profile.json",
                        "--gcs-connector-jar", "connector.jar"),
                tempDir,
                Duration.ofSeconds(30),
                20,
                processRunner);
    }

    private ObjectNode request() {
        ObjectNode request = mapper.createObjectNode();
        request.put("requestSchemaVersion", GnomadAllSitesHailBatchRunner.REQUEST_SCHEMA_VERSION);
        request.put("requestId", "batch-1");
        request.put("assembly", "GRCh38");
        ArrayNode loci = request.putArray("loci");
        loci.addObject().put("locusId", "locus-1").put("canonicalContig", "1")
                .put("position", 12591);
        loci.addObject().put("locusId", "locus-2").put("canonicalContig", "3")
                .put("position", 10052405);
        return request;
    }

    private enum Mode {
        SUCCEEDED,
        FAILED,
        TIMEOUT,
        MALFORMED
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
            this.seenRequest = (ObjectNode) mapper.readTree(requestPath.toFile());
            if (mode == Mode.FAILED) {
                return new ProcessResult(2, "simulated failure", false);
            }
            if (mode == Mode.TIMEOUT) {
                return new ProcessResult(-1, "", true);
            }
            if (mode == Mode.MALFORMED) {
                Files.writeString(outputPath, "{not-json");
                return new ProcessResult(0, "SUCCEEDED", false);
            }
            mapper.writeValue(outputPath.toFile(), successfulResult(seenRequest));
            return new ProcessResult(0, "SUCCEEDED", false);
        }

        private ObjectNode successfulResult(ObjectNode request) {
            ObjectNode result = mapper.createObjectNode();
            result.put("resultSchemaVersion", GnomadAllSitesHailBatchRunner.RESULT_SCHEMA_VERSION);
            result.put("requestId", request.path("requestId").asText());
            result.put("status", "SUCCEEDED");
            result.putObject("profile").put("profileId", "synthetic-profile");
            result.putObject("execution").put("hailInitializationCount", 1);
            result.putArray("resources");
            return result;
        }

        private Path argumentPath(List<String> command, String name) {
            int index = command.indexOf(name);
            return java.nio.file.Paths.get(command.get(index + 1));
        }
    }
}
