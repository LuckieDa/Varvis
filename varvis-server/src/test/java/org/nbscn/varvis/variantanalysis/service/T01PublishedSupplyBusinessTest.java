package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real pinned source/model/reference, synthetic ranked controls, actual CURRENT service.
 * The JUnit process never writes the business DB. A separate Writer must exit before the Reader.
 * This does not assert fresh VEP execution, patient diagnosis, or science coverage of all18.
 */
@EnabledIfSystemProperty(named = "varvis.t01.integrationRequest", matches = ".+")
class T01PublishedSupplyBusinessTest {
    @TempDir Path temporary;

    @Test void normalSelectionsSurviveWriterExitAndIndependentHistoryExport() throws Exception {
        Path request = requiredFile("varvis.t01.integrationRequest");
        Path inputs = requiredFile("varvis.t01.inputs");
        Path python = requiredFile("varvis.pp1.python");
        String configured = System.getProperty("varvis.t01.output", "");
        Path output = configured.isBlank() ? temporary.resolve("artifacts")
                : Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectory(output);
        Path database = Files.createDirectory(temporary.resolve("writer-db"));
        long parentPid = ProcessHandle.current().pid();
        ObjectMapper mapper = new ObjectMapper();
        var receipt = mapper.createObjectNode().put("verificationLayer", "WRITER_EXIT_THEN_READ_ONLY_SERVICE_CONTROLLER_JVM")
                .put("parentProcessId", parentPid).put("startedAt", Instant.now().toString())
                .put("actualVepExecuted", false).put("clinicalValidation", false);
        var writer = child(inputs, T01PublishedSupplyFixture.class.getName(),
                List.of(database.toString(), output.toString(), request.toString(), python.toString()), output, "writer");
        assertFalse(writer.isAlive(), "Writer must exit before any Reader starts");
        var manifest = mapper.readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
        assertNotNull(manifest);
        assertEquals(writer.pid(), manifest.path("writerPid").asLong());
        assertNotEquals(parentPid, writer.pid());
        assertTrue(manifest.path("runs").size() >= 3, "Actual single, related and all results are required");
        receipt.put("writerProcessId", writer.pid()).put("writerExitCode", writer.exitValue())
                .put("writerExitedBeforeReader", true);
        var reader = child(inputs, T01PublishedSupplyHistoryReader.class.getName(),
                List.of(database.toString(), output.toString()), output, "reader");
        assertNotEquals(writer.pid(), reader.pid());
        assertNotEquals(parentPid, reader.pid());
        JsonNode reopened = mapper.readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertNotNull(reopened);
        assertTrue(reopened.path("passed").asBoolean(), reopened.toPrettyString());
        assertEquals(reader.pid(), reopened.path("processId").asLong());
        assertEquals(0, reopened.path("forbiddenRuntimeCalls").asInt(-1));
        receipt.put("readerProcessId", reader.pid()).put("readerExitCode", reader.exitValue())
                .put("completedAt", Instant.now().toString()).put("passed", true);
        receipt.set("reader", reopened);
        Files.write(output.resolve("process-contract-receipt.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
    }

    private static Path requiredFile(String key) throws Exception {
        Path path = Path.of(Objects.requireNonNull(System.getProperty(key), key + " is required")).toRealPath();
        assertTrue(Files.isRegularFile(path), key);
        return path;
    }

    private static Process child(Path inputs, String className, List<String> arguments,
                                 Path output, String label) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<String>();
        command.add(java); command.add("-Dvarvis.t01.inputs=" + inputs);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), className));
        command.addAll(arguments);
        Path log = output.resolve(label + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Task-owned child did not stop");
            fail(label + " child timed out; inspect " + log);
        }
        assertEquals(0, process.exitValue(), () -> {
            try { return label + " child failed: " + Files.readString(log); }
            catch (java.io.IOException error) { return label + " child failed; inspect " + log; }
        });
        return process;
    }
}
