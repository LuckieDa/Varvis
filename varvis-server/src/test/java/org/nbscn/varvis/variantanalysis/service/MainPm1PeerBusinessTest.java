package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Normal CURRENT single/related/all-configured controls with actual PM1/PM2 cores.
 * A separate Writer must exit before the existing independently implemented read-only
 * Service/Controller Reader can inspect its file H2, full source graph and complete export.
 * Source/policy/sequence/population controls are synthetic; no clinical adoption is claimed.
 */
@EnabledIfSystemProperty(named = "varvis.main.pm1.business", matches = "true")
class MainPm1PeerBusinessTest {
    @TempDir Path temporary;
    @Test void currentPeerResultsPersistAfterWriterExitWithoutAnyScientificRerun() throws Exception {
        Path output = System.getProperty("varvis.main.pm1.business.artifacts") == null ? temporary.resolve("artifacts")
                : Path.of(System.getProperty("varvis.main.pm1.business.artifacts"));
        Files.createDirectories(output); Path database = Files.createDirectories(temporary.resolve("writer-db"));
        var writer = child(MainPm1PeerBusinessFixture.class.getName(), database, output, "writer");
        assertFalse(writer.isAlive());
        var manifest = new ObjectMapper().readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
        assertEquals(writer.pid(), manifest.path("writerPid").asLong()); assertEquals(3, manifest.path("runs").size());
        assertEquals(6, manifest.path("queries").asInt()); assertFalse(manifest.path("clinicalAdoption").asBoolean(true));
        assertEquals(6, manifest.path("pm2Queries").asInt());
        assertTrue(manifest.path("ba1QueryCommands").asLong() > 0, "All-selected BA1 uses its real synthetic raw-record transport");
        var reader = child(T01PublishedSupplyHistoryReader.class.getName(), database, output, "reader");
        assertNotEquals(writer.pid(), reader.pid());
        var receipt = new ObjectMapper().readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertTrue(receipt.path("passed").asBoolean(), receipt::toPrettyString);
        assertEquals(0, receipt.path("forbiddenRuntimeCalls").asInt(-1)); assertEquals(3, receipt.path("runs").size());
        assertTrue(receipt.path("writerProcessExited").asBoolean());
    }
    private Process child(String type, Path database, Path output, String name) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var command = List.of(java.toString(), "-Xmx1024m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                type, database.toString(), output.toString());
        Path log = output.resolve(name + ".log"); var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); fail(name + " timed out: " + log); }
        assertEquals(0, process.exitValue(), () -> {
            try { return name + " failed: " + Files.readString(log); } catch (Exception failure) { return log.toString(); }
        }); return process;
    }
}
