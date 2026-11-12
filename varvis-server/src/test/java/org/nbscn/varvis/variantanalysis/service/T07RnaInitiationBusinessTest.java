package org.nbscn.varvis.variantanalysis.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent processes verify non-rescue synthetic Supporting/Moderate through normal selected business. */
class T07RnaInitiationBusinessTest {
    @TempDir Path temporary;

    @Test void realNormalInitiationBaselinesPersistAfterWriterExits() throws Exception {
        String directory = System.getProperty("varvis.t07.initiation.business.output");
        String state = System.getProperty("varvis.t07.initiation.business.state");
        Path output = Files.createDirectories(directory == null ? temporary.resolve("output") : Path.of(directory));
        Path database = Files.createDirectories(state == null ? temporary.resolve("writer") : Path.of(state));
        Path java = Path.of(System.getProperty("java.home"), "bin/java.exe");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path python = Path.of(Objects.requireNonNull(System.getProperty("varvis.pp1.python"), "Existing Python must be explicit"));
        run(java, classpath, T07RnaInitiationBusinessWriter.class.getName(), List.of(database.toString(), output.toString(), python.toString()), output.resolve("writer.log"));
        run(java, classpath, T07FullRnaBusinessReader.class.getName(), List.of(database.toString(), output.toString()), output.resolve("reader.log"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest = mapper.readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
        var receipt = mapper.readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertEquals(13, receipt.path("runs").size()); assertTrue(receipt.path("passed").asBoolean());
        assertTrue(receipt.path("writerExitedBeforeReader").asBoolean()); assertEquals(0, receipt.path("forbiddenScientificFactoryCalls").asInt(-1));
        for (var run : receipt.path("runs")) assertEquals(2,run.path("candidateBackgroundsVerified").asInt(),run::toPrettyString);
        var moderate=StreamSupport.stream(receipt.path("runs").spliterator(),false)
                .filter(run->"PVS1_SINGLE_MODERATE".equals(run.path("name").asText())).findFirst().orElseThrow();
        assertEquals(8,moderate.path("initiationSourcesVerified").asInt(),moderate::toPrettyString);
        assertFalse(manifest.path("functionalRescueSourceCreated").asBoolean()); assertFalse(manifest.path("observedTranslationClaimed").asBoolean());
    }

    private static void run(Path java, String classpath, String entry, List<String> args, Path log) throws Exception {
        var command = new java.util.ArrayList<>(List.of(java.toString(), "-Xmx768m", "-Xshare:off", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-ea", "-cp", classpath, entry));
        command.addAll(args); var process = new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(240, TimeUnit.SECONDS), "Initiation child did not finish; see " + log);
            assertEquals(0, process.exitValue(), () -> "Child failed: " + entry + "\n" + read(log)); assertFalse(process.isAlive());
        } finally {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
        }
    }
    private static String read(Path file) { try { return Files.readString(file); } catch (java.io.IOException failure) { return failure.toString(); } }
}
