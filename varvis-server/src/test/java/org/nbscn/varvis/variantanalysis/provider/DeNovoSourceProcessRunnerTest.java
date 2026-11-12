package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local Python process trees only: synthetic sleeping workers, no network/model/science. */
class DeNovoSourceProcessRunnerTest {
    @TempDir(cleanup=CleanupMode.NEVER) Path temp;
    final ObjectMapper mapper=new ObjectMapper();
    List<String> command() throws Exception {
        String python=System.getProperty("denovo.test.python","missing-python");
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && Files.isRegularFile(Path.of(python)));
        String code="import os,sys,subprocess,time,json,pathlib; p=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)']); pathlib.Path(sys.argv[2]).parent.joinpath('pids.json').write_text(json.dumps({'parent':os.getpid(),'child':p.pid})); time.sleep(60)";
        return List.of(python,"-E","-s","-B","-c",code,"--output",temp.resolve("result.json").toString());
    }
    void stopped() throws Exception {
        var pids=mapper.readTree(Files.readAllBytes(temp.resolve("pids.json")));
        for (String name:List.of("parent","child")) assertFalse(ProcessHandle.of(pids.path(name).asLong()).map(mappedItem -> mappedItem.isAlive()).orElse(false),name);
        var receipt=mapper.readTree(Files.readAllBytes(temp.resolve("owned-process-result.json/execution.json")));
        assertTrue(receipt.path("exitConfirmed").asBoolean());assertTrue(receipt.path("cleanupWithinBudget").asBoolean());
    }
    @Test void deadlineTerminatesTheOwnedParentAndItsHttpStyleChild() throws Exception {
        var result=new DeNovoSourceProcessRunner().run(command(),temp,Duration.ofSeconds(2));
        assertTrue(result.isTimedOut());assertFalse(result.isSuccess());stopped();
    }
    @Test void siblingReadingRoundsRetainIndependentProcessReceipts() throws Exception {
        command(); // Verify that the real Windows/Python test environment is available.
        String python=System.getProperty("denovo.test.python");
        var runner=new DeNovoSourceProcessRunner();
        byte[] first=null;
        for(String round:List.of("paper","paper-round-1")) {
            var cmd=List.of(python,"-E","-s","-B","-c","print('completed')","--output",temp.resolve(round).toString());
            assertTrue(runner.run(cmd,temp,Duration.ofSeconds(10)).isSuccess());
            var receipt=Files.readAllBytes(temp.resolve("owned-process-"+round+"/execution.json"));
            assertTrue(mapper.readTree(receipt).path("exitConfirmed").asBoolean());
            if(first==null)first=receipt;
            else assertArrayEquals(first,Files.readAllBytes(temp.resolve("owned-process-paper/execution.json")));
        }
    }
    @Test void cancellingTheWaitingWorkerAlsoTerminatesItsProcessTree() throws Exception {
        var command=command();var finished=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(()->{try {new DeNovoSourceProcessRunner().run(command,temp,Duration.ofSeconds(30));}
                catch(java.io.IOException expected) {assertTrue(Thread.currentThread().isInterrupted());}
                finally {finished.countDown();}});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Files.exists(temp.resolve("pids.json"))&&System.nanoTime()<until) Thread.sleep(10);
            assertTrue(Files.isRegularFile(temp.resolve("pids.json")));future.cancel(true);
            assertTrue(finished.await(5,TimeUnit.SECONDS));stopped();
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
