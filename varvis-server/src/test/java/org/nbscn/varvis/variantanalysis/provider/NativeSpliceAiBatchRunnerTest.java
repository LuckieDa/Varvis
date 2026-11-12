package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Windows job control with a tiny stdlib-only adapter; no scientific model executes. */
class NativeSpliceAiBatchRunnerTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    NativeSpliceAiBatchRunner runner() throws Exception {
        String python = System.getProperty("varvis.test.nativePython", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && !python.isBlank());
        Path script = temporary.resolve("sleep.py");
        Files.writeString(script, """
                import subprocess, sys, time
                from pathlib import Path
                child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
                Path(__file__).with_suffix('.pid').write_text(str(child.pid))
                time.sleep(30)
                """);
        Path binding = temporary.resolve("binding.json");
        mapper.writeValue(binding.toFile(), mapper.createObjectNode().put("schemaVersion", "spliceai-native-business-identity-v1")
                .put("version", NativeSpliceAiBatchRunner.VERSION).put("adapterSha256", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(script))));
        return new NativeSpliceAiBatchRunner(mapper, List.of(python, script.toString()), temporary.resolve("unused.fa"), binding,
                CodingTranscriptMaterialSupply.sha(Files.readAllBytes(binding)), temporary.resolve("work"), Duration.ofSeconds(30));
    }
    ObjectNode request() {
        var request = mapper.createObjectNode().put("requestSchemaVersion", "spliceai-native-selected-request-v1");
        request.putArray("variants").addObject(); return request;
    }
    @Test void versionOnePointOneKeepsItsOriginalBindingShapeWhileWideRequiresItsOwnSettings() throws Exception {
        Path script = temporary.resolve("adapter.py");
        Files.writeString(script, "# This test checks binding admission only.");
        Path binding = temporary.resolve("identity.json");
        var legacy = mapper.createObjectNode().put("schemaVersion", "spliceai-native-business-identity-v1")
                .put("version", "1.1.0").put("adapterSha256", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(script)));
        assertEquals(legacy, bind(script, binding, legacy).identity());

        var wide = legacy.deepCopy().put("version", "1.2.0");
        var missing = assertThrows(IllegalArgumentException.class, () -> bind(script, binding, wide));
        assertEquals("NATIVE_BINDING_INVALID", missing.getMessage());
        wide.put("executionDistanceBp", 10000).put("mask", 0);
        assertEquals(wide, bind(script, binding, wide).identity());
        wide.put("executionDistanceBp", 4999);
        var incorrect = assertThrows(IllegalArgumentException.class, () -> bind(script, binding, wide));
        assertEquals("NATIVE_BINDING_INVALID", incorrect.getMessage());
    }

    private NativeSpliceAiBatchRunner bind(Path script, Path binding, ObjectNode identity) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(identity);
        Files.write(binding, bytes);
        return new NativeSpliceAiBatchRunner(mapper, List.of(script.toString(), script.toString()), temporary.resolve("unused.fa"), binding,
                CodingTranscriptMaterialSupply.sha(bytes), temporary.resolve("work"), Duration.ofSeconds(30));
    }

    @Test void remainingBudgetBoundsActualProcessAndStopsItsChild() throws Exception {
        var runner = runner(); long started = System.nanoTime();
        var result = runner.run(request(), Duration.ofSeconds(2));
        assertEquals("NATIVE_SPLICE_TIMEOUT", result.path("reasonCode").asText(), result.toPrettyString());
        assertTrue(result.at("/processReceipt/ownedProcessesStopped").asBoolean(), result.toPrettyString());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 8);
        Path pid = temporary.resolve("sleep.pid"); assertTrue(Files.exists(pid), "the child was actually launched");
        var child = ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElse(null);
        assertFalse(child != null && child.isAlive());
        String output = System.getProperty("varvis.test.nativeReceiptDirectory", "");
        if (!output.isBlank()) {
            Files.createDirectories(Path.of(output)); mapper.writeValue(Path.of(output, "timeout-process.json").toFile(), result);
        }
    }
    @Test void exhaustedBudgetAndPreInterruptionNeverCreateWorkDirectory() throws Exception {
        var runner = runner();
        assertEquals("NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED", runner.run(request(), Duration.ZERO).path("reasonCode").asText());
        var oversized = request(); for (int i = 1; i < 11; i++) oversized.withArray("variants").addObject();
        assertEquals("NATIVE_BATCH_LIMIT_EXCEEDED", runner.run(oversized).path("reasonCode").asText());
        try {
            Thread.currentThread().interrupt();
            assertEquals("NATIVE_SPLICE_INTERRUPTED", runner.run(request(), Duration.ofSeconds(1)).path("reasonCode").asText());
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(temporary.resolve("work")));
    }
}
