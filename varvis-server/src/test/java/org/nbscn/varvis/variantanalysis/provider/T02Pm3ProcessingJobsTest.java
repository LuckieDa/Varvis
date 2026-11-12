package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real durable task/adapter controls; synthetic worker transport is explicit.
 * Python tests separately execute actual source registration, ledger and cache validation. */
class T02Pm3ProcessingJobsTest {
    @TempDir(factory=DeNovoCurrentTestDirectory.class) Path temporary;

    @Test void fixedPaperCheckpointWithoutReadingLoopContinuesWithinFiniteBudget() throws Exception {
        var f=new Pm3LiteratureJobsTest();f.root=temporary;Path config=f.setup();var calls=new AtomicInteger();
        var original=f.runner(config,calls,false);
        ExternalProcessRunner worker=(command,cwd,remaining)->{
            var exit=original.run(command,cwd,remaining);Path file=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
            var result=(ObjectNode)f.json.readTree(Files.readAllBytes(file));
            result.putObject("processingCheckpoint").put("schemaVersion","pm3-paper-processing-checkpoint-v1")
                    .putArray("stages").addObject().put("chunkIndex",4).put("stage","REVIEW").put("status","NOT_REVIEWED")
                    .put("modelCalls",0).put("reasonCode","DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED");
            Files.write(file,f.json.writeValueAsBytes(result));return exit;
        };
        var selected=f.json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        selected.putArray("candidates").addObject().put("candidateId","c1");
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            String selection=jobs.selectionOptions(selected).path("selectionSha256").asText();
            String parent=jobs.start("analysis",f.request().put("selectionSha256",selection)).path("taskId").asText();
            for(int generation=1;generation<=3;generation++) {
                var before=f.waitFor(jobs,parent);assertEquals("READY",before.path("status").asText());
                assertEquals(generation,before.path("processingAttempt").asInt());assertEquals(generation<3,before.path("canContinueProcessing").asBoolean());
                assertEquals(generation,calls.get());assertFalse(before.path("sharedBudgetReconciliationRequired").asBoolean());
                if(generation<3) {
                    String child=jobs.continueDiscovery("analysis",selected,selection,parent).path("taskId").asText();
                    assertNotEquals(parent,child);f.waitFor(jobs,child);assertEquals(before,jobs.read("analysis",parent));
                    assertEquals(child,jobs.continueDiscovery("analysis",selected,selection,parent).path("taskId").asText());parent=child;
                } else {
                    String finalId=parent;
                    assertThrows(java.io.IOException.class,()->jobs.continueDiscovery("analysis",selected,selection,finalId));
                    assertEquals("PM3_PROCESSING_CONTINUATION_LIMIT",before.path("processingContinuationBlockedReason").asText());
                }
            }
        }
        assertEquals(3,calls.get());
    }

    @Test void discoveryPendingCannotMaskFailedAttemptOrClearReconciliation() {
        var f=new Pm3LiteratureJobsTest();var result=f.json.createObjectNode();result.putObject("discoveryState").put("resumeAvailable",true);
        assertTrue(Pm3LiteratureJobs.continuationAvailable(result));
        result.putArray("papers").addObject().put("callAccountingStatus","CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT");
        assertTrue(Pm3LiteratureJobs.reconciliationRequired(result));assertFalse(Pm3LiteratureJobs.continuationAvailable(result));
    }

    @Test void actualReplayRegistryBytesParticipateInNewTaskIdentity() throws Exception {
        var f=new Pm3LiteratureJobsTest();f.root=temporary;Path config=f.setup();var calls=new AtomicInteger();
        Path registry=temporary.resolve("scripts/denovo/core_review_replay_profiles.json");Files.writeString(registry,"{}");
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),f.runner(config,calls,false))) {
            String first=jobs.start("analysis",f.request()).path("taskId").asText();var before=f.waitFor(jobs,first);
            Files.writeString(registry,"{\"changedProfile\":true}");
            String second=jobs.start("analysis",f.request()).path("taskId").asText();assertNotEquals(first,second);
            assertEquals("READY",f.waitFor(jobs,second).path("status").asText());assertEquals(2,calls.get());
            assertEquals(before.path("result"),jobs.read("analysis",first).path("result"));
        }
    }
    @Test void discoveryOriginCannotOmitOrChangeTheVerifiedProcessingParentAndState() throws Exception {
        var f=new Pm3LiteratureJobsTest();f.root=temporary;Path config=f.setup();var calls=new AtomicInteger();
        var base=f.runner(config,calls,false);
        ExternalProcessRunner worker=(command,cwd,timeout)->{
            var finished=base.run(command,cwd,timeout);Path output=Path.of(command.get(command.indexOf("--output")+1));
            byte[] state="{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(output.resolve("discovery-state.json"),state);
            var result=(ObjectNode)f.json.readTree(Files.readAllBytes(output.resolve("result.json")));
            result.putObject("discoveryState").put("resumeAvailable",true).put("sha256",FamilyDeNovoJson.sha256(state));
            Files.write(output.resolve("result.json"),f.json.writeValueAsBytes(result));return finished;
        };
        var request=f.request().put("schemaVersion","pm3-public-discovery-task-v1");request.remove(java.util.List.of("pmcid","bundleSha256"));
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            String id=jobs.start("analysis",request).path("taskId").asText();var saved=f.waitFor(jobs,id);
            var original=localWorkSnapshot(temporary.resolve("local/work"));
            for(String variant:java.util.List.of("MISSING_PROCESSING","OTHER_PARENT","OTHER_RESULT","OTHER_STATE")) {
                var child=request.deepCopy();
                child.putObject("processingContinuation").put("parentTaskId",id).put("parentResultSha256",saved.path("resultSha256").asText());
                var origin=child.putObject("discoveryContinuation").put("parentTaskId",id)
                        .put("parentResultSha256",saved.path("resultSha256").asText())
                        .put("stateSha256",saved.at("/result/discoveryState/sha256").asText()).put("statePath","local/unused-state.json");
                switch(variant) {
                    case "MISSING_PROCESSING" -> child.remove("processingContinuation");
                    case "OTHER_PARENT" -> origin.put("parentTaskId","a".repeat(64));
                    case "OTHER_RESULT" -> origin.put("parentResultSha256","b".repeat(64));
                    case "OTHER_STATE" -> origin.put("stateSha256","c".repeat(64));
                    default -> throw new AssertionError(variant);
                }
                assertEquals("PM3_DISCOVERY_CONTINUATION_INVALID",assertThrows(java.io.IOException.class,
                        ()->jobs.start("analysis",child)).getMessage(),variant);
                assertEquals(1,calls.get());assertEquals(1,jobs.list("analysis").size());
                assertEquals(saved,jobs.read("analysis",id));assertWorkSnapshotEquals(original,temporary.resolve("local/work"));
            }
        }
    }

    @Test void bothAccountingMarkersBlockRootAndNestedPaperDespitePendingWork() {
        var f=new Pm3LiteratureJobsTest();
        for(boolean nested:new boolean[]{false,true})for(String marker:java.util.List.of("READING_LOOP","CALL_ACCOUNTING","READING_ADVICE")) {
            var result=f.json.createObjectNode();
            result.putObject("discoveryState").put("resumeAvailable",true);
            result.putObject("processingCheckpoint").put("schemaVersion","pm3-paper-processing-checkpoint-v1")
                    .putArray("stages").addObject().put("stage","REVIEW").put("status","NOT_REVIEWED");
            assertTrue(Pm3LiteratureJobs.continuationAvailable(result));
            var marked=nested?result.putArray("papers").addObject().putArray("papers").addObject():result;
            if("READING_LOOP".equals(marker))marked.putObject("readingLoop").put("failedAttemptAccounting","CHECK_SHARED_LEDGER");
            else if("READING_ADVICE".equals(marker))marked.put("modelChargeStatus","FAILED_OR_UNKNOWN_RESERVATION_RETAINED");
            else marked.put("callAccountingStatus","CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT");
            assertTrue(Pm3LiteratureJobs.reconciliationRequired(result),result.toPrettyString());
            assertFalse(Pm3LiteratureJobs.continuationAvailable(result),result.toPrettyString());
        }
    }

    @Test void realJobIoFailureWithPendingWorkBlocksEveryContinuationWithoutChangingOriginals() throws Exception {
        failedRoundRequiresReconciliation("IO");
    }

    @Test void realJobTimeoutWithPendingWorkBlocksEveryContinuationWithoutChangingOriginals() throws Exception {
        failedRoundRequiresReconciliation("TIMEOUT");
    }

    @Test void realJobFailedProcessWithPendingWorkBlocksEveryContinuationWithoutChangingOriginals() throws Exception {
        failedRoundRequiresReconciliation("EXIT");
    }

    private void failedRoundRequiresReconciliation(String failure) throws Exception {
        var f=new Pm3LiteratureJobsTest();f.root=temporary;Path config=f.setup();
        var configuration=(ObjectNode)f.json.readTree(Files.readAllBytes(config));
        configuration.put("pm3NeedDrivenReading",true).put("allowModel",true);
        Files.write(config,f.json.writeValueAsBytes(configuration));
        var calls=new AtomicInteger();var base=f.runner(config,new AtomicInteger(),false);
        byte[] sourceOriginal="SYNTHETIC_TEST_ONLY retained worker source original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ExternalProcessRunner worker=(command,cwd,remaining)->{
            if(calls.getAndIncrement()>0) {
                if("IO".equals(failure))throw new java.io.IOException("synthetic transport failure");
                return new ProcessResult(-1,"synthetic process failure","TIMEOUT".equals(failure));
            }
            var exit=base.run(command,cwd,remaining);
            Path output=Path.of(command.get(command.indexOf("--output")+1));
            Files.write(output.resolve("source-original.txt"),sourceOriginal);
            Path file=output.resolve("result.json");var result=(ObjectNode)f.json.readTree(Files.readAllBytes(file));
            result.putArray("completedReviewChunks").add(43);
            result.putObject("readingPlan").putArray("pendingRelevantChunks").add(4);
            result.putObject("processingCheckpoint").put("schemaVersion","pm3-paper-processing-checkpoint-v1")
                    .putArray("stages").addObject().put("chunkIndex",4).put("stage","REVIEW").put("status","NOT_REVIEWED");
            result.putArray("issues");Files.write(file,f.json.writeValueAsBytes(result));return exit;
        };
        var selected=f.json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        selected.putArray("candidates").addObject().put("candidateId","c1");
        String id;ObjectNode saved;java.util.Map<String,byte[]> originalFiles;ObjectNode request;String selection;
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            selection=jobs.selectionOptions(selected).path("selectionSha256").asText();
            request=f.request().put("selectionSha256",selection);
            id=jobs.start("analysis",request).path("taskId").asText();saved=f.waitFor(jobs,id);
            assertEquals("READY",saved.path("status").asText(),saved.toPrettyString());
            assertEquals(2,calls.get());assertFalse(saved.path("canContinueProcessing").asBoolean());
            assertTrue(saved.path("sharedBudgetReconciliationRequired").asBoolean());
            assertEquals("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED",saved.path("processingContinuationBlockedReason").asText());
            assertEquals("TIMEOUT".equals(failure)?"PM3_WORKER_TIMEOUT":"PM3_WORKER_FAILED",saved.at("/result/readingLoop/stopReason").asText());
            assertEquals("CHECK_SHARED_LEDGER",saved.at("/result/readingLoop/failedAttemptAccounting").asText());
            assertEquals(43,saved.at("/result/completedReviewChunks/0").asInt());
            assertEquals(4,saved.at("/result/readingPlan/pendingRelevantChunks/0").asInt());
            assertArrayEquals(sourceOriginal,Files.readAllBytes(temporary.resolve("local/work/pm3-artifacts").resolve(id).resolve("paper/source-original.txt")));
            originalFiles=localWorkSnapshot(temporary.resolve("local/work"));
            var error=assertThrows(java.io.IOException.class,()->jobs.continueDiscovery("analysis",selected,selection,id));
            assertEquals("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED",error.getMessage());
            var bypass=request.deepCopy();bypass.putObject("processingContinuation").put("parentTaskId",id)
                    .put("parentResultSha256",saved.path("resultSha256").asText());
            var direct=assertThrows(java.io.IOException.class,()->jobs.start("analysis",bypass));
            assertEquals("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED",direct.getMessage());
            assertEquals(id,jobs.start("analysis",request).path("taskId").asText());
            assertEquals(saved,jobs.read("analysis",id));assertEquals(1,jobs.list("analysis").size());
            assertEquals(2,calls.get());assertWorkSnapshotEquals(originalFiles,temporary.resolve("local/work"));
        }
        try(var reopened=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            assertEquals(saved,reopened.read("analysis",id));
            assertEquals("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED",assertThrows(java.io.IOException.class,
                    ()->reopened.continueDiscovery("analysis",selected,selection,id)).getMessage());
            assertEquals(id,reopened.start("analysis",request).path("taskId").asText());
            assertEquals(2,calls.get());assertWorkSnapshotEquals(originalFiles,temporary.resolve("local/work"));
        }
    }

    private static java.util.Map<String,byte[]> localWorkSnapshot(Path root) throws Exception {
        var snapshot=new java.util.TreeMap<String,byte[]>();
        try(var paths=Files.walk(root)) {
            for(Path path:paths.filter(Files::isRegularFile).toList())snapshot.put(root.relativize(path).toString(),Files.readAllBytes(path));
        }
        return snapshot;
    }
    private static void assertWorkSnapshotEquals(java.util.Map<String,byte[]> expected,Path root) throws Exception {
        var actual=localWorkSnapshot(root);assertEquals(expected.keySet(),actual.keySet());
        for(var entry:expected.entrySet())assertArrayEquals(entry.getValue(),actual.get(entry.getKey()),entry.getKey());
    }

}
