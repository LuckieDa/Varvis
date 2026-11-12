package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.ProcessResult;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic probe regression only; no real runtime or resource invocation. */
class IntronicScopeRuntimeProbeTest {
    @TempDir Path work;
    final ObjectMapper mapper=new ObjectMapper();
    @Test void savedTranscriptUsesExistingDerivedIdentityWithoutAnUnpersistedField() throws Exception {
        var smoke=new IntronicScopeBusinessSmokeTest();
        var tx=smoke.base.mapper.createObjectNode().put("transcriptAccession","ENST00000335295")
                .put("hgvsCoding","ENST00000335295.4:c.93-21G>A").put("consequence","intron_variant").put("source","VEP");
        assertFalse(tx.has("versionedTranscriptId"));assertTrue(smoke.matchesSavedTranscript(tx));
        tx.put("hgvsCoding","ENST00000335295.5:c.93-21G>A");assertFalse(smoke.matchesSavedTranscript(tx));
        tx.put("hgvsCoding","ENST00000335295.4:c.93-20G>A");assertFalse(smoke.matchesSavedTranscript(tx));
    }
    @Test void rejectsStaleSuccessAndRetainsTimeoutAndMissingFreshOutput() throws Exception {
        Path stale=work.resolve("splice-readiness.json");Files.writeString(stale,"{\"installedAssetHashesVerified\":true}");
        var timeout=IntronicScopeRuntimeProbe.run(mapper,List.of("configured-python"),"reference.fa",work,
                (command,cwd,limit)->new ProcessResult(-1,"diagnostic timeout",true));
        assertEquals("BLOCKED",timeout.path("status").asText());assertTrue(timeout.at("/process/timedOut").asBoolean());
        assertEquals(-1,timeout.at("/process/exitCode").asInt());assertEquals("diagnostic timeout",timeout.at("/process/output").asText());
        var absent=IntronicScopeRuntimeProbe.run(mapper,List.of("configured-python"),"reference.fa",work,
                (command,cwd,limit)->new ProcessResult(0,"READY without a new file",false));
        assertEquals("BLOCKED",absent.path("status").asText());assertTrue(absent.path("failure").asText().contains("FRESH_READINESS_OUTPUT_MISSING"));
        assertNotEquals(timeout.path("outputFile"),absent.path("outputFile"));
        assertEquals(timeout,mapper.readTree(Files.readString(Path.of(timeout.path("recordFile").asText()))));
        assertEquals("{\"installedAssetHashesVerified\":true}",Files.readString(stale));
    }
    @Test void acceptsOnlyOwnNewOutputAndRecordsExactInvocation() throws Exception {
        var report=IntronicScopeRuntimeProbe.run(mapper,List.of("python","business.py","--timeout-seconds","540"),"F:/reference.fa",work,
                (command,cwd,limit)->{
                    assertEquals(work,cwd);assertEquals(120,limit.toSeconds());
                    assertEquals("F:/reference.fa",command.get(command.indexOf("--reference-fasta")+1));
                    Path output=Path.of(command.get(command.indexOf("--output")+1));assertFalse(Files.exists(output));
                    Files.writeString(output,"{\"installedAssetHashesVerified\":true,\"marker\":\"fresh-synthetic\"}");
                    return new ProcessResult(0,"fresh",false);
                });
        assertEquals("READY",report.path("status").asText());assertFalse(report.at("/process/timedOut").asBoolean());
        assertEquals("fresh-synthetic",report.at("/result/marker").asText());
        assertEquals(IntronicScopeRuntimeProbe.sha(Path.of(report.path("outputFile").asText())),report.path("outputSha256").asText());
        assertTrue(report.path("elapsedSeconds").asDouble()>=0);assertEquals("python",report.path("command").get(0).asText());
    }
}
