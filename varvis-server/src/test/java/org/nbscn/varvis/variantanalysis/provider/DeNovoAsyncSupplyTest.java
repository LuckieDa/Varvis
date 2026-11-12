package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DeNovoAsyncSupplyTest {
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper();
    @Test void pendingSourceDoesNotBlockAndCompletedExactSourceIsReusedWithoutExecution() throws Exception {
        Path scripts=Path.of(System.getProperty("denovo.test.scripts","../scripts/denovo"));
        var targets=mapper.createArrayNode();var target=targets.addObject().put("hgncId","HGNC:999999997");
        target.putObject("allele").put("assembly","GRCh38").put("contig","1").put("position",123).put("reference","A").put("alternate","G");
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);var calls=new AtomicInteger();
        ExternalProcessRunner runner=(command,cwd,timeout)->{
            calls.incrementAndGet();entered.countDown();
            try { if (!gate.await(3,TimeUnit.SECONDS)) throw new java.io.IOException("synthetic gate timeout"); }
            catch (InterruptedException interrupted) {Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
            Path input=Path.of(command.get(command.indexOf("--request")+1));byte[] raw=Files.readAllBytes(input);var req=mapper.readTree(raw);
            var out=mapper.createObjectNode().put("schemaVersion",DeNovoLiteratureSupply.VERSION).put("requestSha256",FamilyDeNovoJson.sha256(raw)).put("modelCalls",0).put("clinicalApproval","NONE");
            out.set("implementation",req.path("implementation"));out.putObject("originals");out.putArray("documents");
            var t=target.deepCopy();t.putArray("reasonCodes");out.putArray("targets").add(t);out.putObject("retrieval").put("networkRequests",0);
            Files.write(Path.of(command.get(command.indexOf("--output")+1)),mapper.writeValueAsBytes(out));
            return new ProcessResult(0,"SYNTHETIC slow source, no network",false);
        };
        try(var tasks=new DeNovoSourceTasks(1,1)) {
            var base=new DeNovoLiteratureSupply(mapper,Path.of("unused-python"),scripts,temp.resolve("work"),temp.resolve("cache"),false,runner);
            var supplier=base.asynchronous(tasks,runner);
            var pending=assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->supplier.prepare(targets,mapper.createArrayNode(),mapper.createArrayNode(),Map.of()));
            assertEquals("DENOVO_SUPPLY_PENDING",pending.issue());assertTrue(entered.await(2,TimeUnit.SECONDS));
            String id=pending.snapshot().path("taskId").asText();assertTrue(id.matches("[a-f0-9]{64}"));
            assertEquals(0,supplier.executions());assertEquals(0,supplier.networkRequests());
            assertEquals("DENOVO_SUPPLY_PENDING",base.asynchronous(tasks,runner).prepare(targets,mapper.createArrayNode(),mapper.createArrayNode(),Map.of()).issue());
            gate.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!"READY".equals(supplier.taskStatus(id).path("status").asText())&&System.nanoTime()<deadline) Thread.sleep(10);
            var ready=base.asynchronous(tasks,runner).prepare(targets,mapper.createArrayNode(),mapper.createArrayNode(),Map.of());
            assertNull(ready.issue());assertEquals("READY",ready.snapshot().at("/sourceTask/status").asText());assertEquals(1,calls.get());
            assertEquals("DENOVO_SUPPLY_PENDING",pending.issue());assertTrue(pending.originals().isEmpty());
        }
    }
}
