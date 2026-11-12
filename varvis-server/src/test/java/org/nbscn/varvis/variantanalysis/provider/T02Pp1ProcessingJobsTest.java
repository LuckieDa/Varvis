package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic worker transport only. Real task identity/queue/parent pins and adapter paths;
 * actual source reading and Python qualification are checked separately, not stubbed here. */
class T02Pp1ProcessingJobsTest {
    @TempDir(factory = DeNovoCurrentTestDirectory.class) Path temporary;

    private Pp1LiteratureJobsTest fixture() {
        var fixture=new Pp1LiteratureJobsTest();fixture.root=temporary;return fixture;
    }

    private ExternalProcessRunner worker(Pp1LiteratureJobsTest f,Path config,AtomicInteger calls,boolean finish) {
        var original=f.runner(config,calls);
        return (command,cwd,remaining)-> {
            var exit=original.run(command,cwd,remaining);
            Path resultFile=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
            var result=(ObjectNode)f.json.readTree(Files.readAllBytes(resultFile));
            if(!finish || calls.get()==1) {
                result.put("status","PARTIAL").put("stopReason","PP1_CALL_LIMIT");
                result.putArray("issues").add("PP1_CALL_LIMIT");
            } else result.put("stopReason","EXTERNAL_REQUIREMENT");
            Files.write(resultFile,f.json.writeValueAsBytes(result));return exit;
        };
    }

    @Test void existingParentEndpointContinuesFixedPaperImmutablyAndReopenIsReadOnly() throws Exception {
        var f=fixture();Path config=f.setup();var calls=new AtomicInteger();var request=f.request();String parent,child;
        ObjectNode before,after;
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker(f,config,calls,true))) {
            parent=jobs.start("analysis",request).path("taskId").asText();before=f.await(jobs,parent);
            assertTrue(before.path("canContinueProcessing").asBoolean());assertFalse(before.path("canRetry").asBoolean());
            child=jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),parent).path("taskId").asText();
            assertNotEquals(parent,child);after=f.await(jobs,child);assertEquals("READY",after.path("status").asText(),after.toPrettyString());
            assertEquals(2,after.path("processingAttempt").asInt());assertFalse(after.path("canContinueProcessing").asBoolean());
            assertEquals(before,jobs.read("analysis",parent));
            assertEquals(child,jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),parent).path("taskId").asText());
            assertEquals(2,calls.get());
        }
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker(f,config,calls,true))) {
            assertEquals(before,jobs.read("analysis",parent));assertEquals(after,jobs.read("analysis",child));
            assertEquals(2,jobs.list("analysis").size());assertEquals(2,calls.get());
        }
    }

    @Test void repeatedPartialParentsHaveFiniteProcessingGenerationAndNoAutomaticRetry() throws Exception {
        var f=fixture();Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker(f,config,calls,false))) {
            String id=jobs.start("analysis",request).path("taskId").asText();
            for(int generation=1;generation<=3;generation++) {
                var saved=f.await(jobs,id);assertEquals(generation,saved.path("processingAttempt").asInt());
                assertEquals(generation<3,saved.path("canContinueProcessing").asBoolean());assertEquals(generation,calls.get());
                if(generation<3)id=jobs.continueProcessing("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),id,null).path("taskId").asText();
                else {
                    String finalId=id;
                    assertEquals("PP1_PROCESSING_CONTINUATION_LIMIT",saved.path("processingContinuationBlockedReason").asText());
                    assertThrows(java.io.IOException.class,()->jobs.continueProcessing("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),finalId,null));
                }
            }
            assertEquals(3,calls.get());
        }
    }

    @Test void wrongBindingOrChangedManifestCannotContinueOrRunWorker() throws Exception {
        var f=fixture();Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker(f,config,calls,false))) {
            String parent=jobs.start("analysis",request).path("taskId").asText();f.await(jobs,parent);
            var wrong=((ObjectNode)request.path("analysisBinding")).deepCopy();wrong.withObject("identity").put("candidateId","other");
            assertThrows(java.io.IOException.class,()->jobs.continueProcessing("analysis",wrong,(ObjectNode)request.path("target"),parent,null));
            Files.writeString(temporary.resolve("local/work/pp1-artifacts").resolve(parent).resolve("paper/artifacts.json"),"{}");
            assertThrows(java.io.IOException.class,()->jobs.continueProcessing("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),parent,null));
            assertEquals(1,calls.get());
        }
    }

    @Test void genuineAbsenceExternalNeedsAndUnreconciledModelFailuresDoNotRequestRetry() {
        var f=fixture();var result=f.json.createObjectNode().put("schemaVersion","pp1-paper-process-v1");
        for(String reason:List.of("PP1_CALL_LIMIT","PP1_READING_TIME_LIMIT","ROUND_LIMIT","PP1_MODEL_DISABLED_CACHE_MISSING")) {
            result.put("stopReason",reason);assertTrue(Pp1LiteratureJobs.processingContinuationAvailable(result),reason);
        }
        for(String reason:List.of("NO_UNREAD_RELEVANT_BLOCK","AVAILABLE_SCOPE_REVIEWED","EXTERNAL_REQUIREMENT","SOURCE_REVIEW_REJECTED_PENDING")) {
            result.put("stopReason",reason);assertFalse(Pp1LiteratureJobs.processingContinuationAvailable(result),reason);
        }
        result.put("stopReason","PP1_CALL_LIMIT").putArray("issues").add("DENOVO_MODEL_TIMEOUT");
        assertFalse(Pp1LiteratureJobs.processingContinuationAvailable(result));
    }

    @Test void dependencyChangesDisableProcessingWithoutMutatingSavedTask() throws Exception {
        var f=fixture();Path config=f.setup();var calls=new AtomicInteger();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker(f,config,calls,false))) {
            String parent=jobs.start("analysis",f.request()).path("taskId").asText();var before=f.await(jobs,parent);
            assertTrue(before.path("canContinueProcessing").asBoolean());
            Files.writeString(temporary.resolve("local/model.json"),"{\"changedModel\":true}");
            var after=jobs.read("analysis",parent);assertFalse(after.path("canContinueProcessing").asBoolean());
            assertEquals("PP1_TASK_DEPENDENCY_CHANGED",after.path("processingContinuationBlockedReason").asText());
            assertEquals(before.path("result"),after.path("result"));assertEquals(1,calls.get());
        }
    }

    @Test void currentImplementationReassemblesRawSourceAsNewChildAndNeverReusesOldScore() throws Exception {
        var f=fixture();Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker(f,config,calls,true))) {
            String parent=jobs.start("analysis",request).path("taskId").asText();var before=f.await(jobs,parent);
            Files.writeString(temporary.resolve("scripts/denovo/run_pp1_paper_task.py"),"# updated source processing implementation");
            var refreshed=jobs.read("analysis",parent);assertTrue(refreshed.path("canContinueProcessing").asBoolean());
            assertTrue(refreshed.path("sourceReassemblyRequired").asBoolean());assertEquals(before.path("result"),refreshed.path("result"));
            String child=jobs.continueProcessing("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),parent,null).path("taskId").asText();
            var after=f.await(jobs,child);assertEquals("READY",after.path("status").asText());
            assertEquals("CURRENT_QUALIFICATION_OVER_PINNED_SOURCE_ARTIFACTS",after.at("/sourceReassembly/method").asText());
            assertFalse(after.at("/sourceReassembly/savedScoreAdopted").asBoolean(true));assertEquals(2,calls.get());
            assertEquals(before.path("result"),jobs.read("analysis",parent).path("result"));
        }
    }

    @Test void typedWorkerFailureKeepsSafeCauseWithoutRawOutput() {
        assertEquals("PP1_PROCESSING_CONTINUATION_MANIFEST_CHANGED",Pp1LiteratureJobs.workerFailureCode(new ProcessResult(1,
                "Traceback...private source text\nValueError: PP1_PROCESSING_CONTINUATION_MANIFEST_CHANGED\n",false)));
        assertEquals("PP1_WORKER_LOCAL_RESOURCE_UNAVAILABLE",Pp1LiteratureJobs.workerFailureCode(new ProcessResult(1,"FileNotFoundError: private local path",false)));
        assertEquals("PP1_WORKER_FAILED",Pp1LiteratureJobs.workerFailureCode(new ProcessResult(1,"unsafe transport or credential body",false)));
        assertEquals("PP1_WORKER_TIMEOUT",Pp1LiteratureJobs.workerFailureCode(new ProcessResult(1,"private",true)));
    }

    @Test void actualFailedTaskRetainsSafeCauseAndBoundStageWithoutRetry() throws Exception {
        var f=fixture();Path config=f.setup();var calls=new AtomicInteger();
        ExternalProcessRunner failed=(command,cwd,remaining)->{
            calls.incrementAndGet();return new ProcessResult(1,"private worker trace\nValueError: PP1_PROCESSING_CONTINUATION_MANIFEST_CHANGED\n",false);
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),failed)) {
            String id=jobs.start("analysis",f.request()).path("taskId").asText();var done=f.await(jobs,id);
            assertEquals("FAILED",done.path("status").asText());assertFalse(done.path("canRetry").asBoolean());
            assertEquals("PP1_PROCESSING_CONTINUATION_MANIFEST_CHANGED",done.path("reasonCode").asText());
            Path file=temporary.resolve("local/work/pp1-paper-tasks").resolve(FamilyDeNovoJson.sha256("analysis".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .resolve(id).resolve("processing-failure.json");
            var diagnostic=f.json.readTree(Files.readAllBytes(file));
            assertEquals(id,diagnostic.path("taskId").asText());assertEquals("SOURCE_WORKER",diagnostic.path("stage").asText());
            assertFalse(diagnostic.path("biologicalAbsenceInferred").asBoolean());assertFalse(diagnostic.toString().contains("private worker trace"));
            assertEquals(done,jobs.read("analysis",id));assertEquals(1,calls.get());
        }
    }

    @Test void printedShortAliasFromRequestedTranscriptIsAllowedButSubstitutedVersionIsNot() throws Exception {
        var f=fixture();Path config=f.setup();var calls=new AtomicInteger();var original=f.runner(config,calls);
        for(String reported:List.of("c.1A>G","NM_000001.2:c.1A>G","NM_123456.1:c.1A>G","c.1A>C")) {
            var request=f.request();request.withObject("target").putArray("variantAliases").add("NM_000001.1:c.1A>G");
            ExternalProcessRunner printed=(command,cwd,remaining)->{
                var exit=original.run(command,cwd,remaining);Path file=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
                var value=(ObjectNode)f.json.readTree(Files.readAllBytes(file));value.withObject("target").putArray("variantAliases").add(reported);
                Files.write(file,f.json.writeValueAsBytes(value));return exit;
            };
            // Different declared source implementation makes each transport run.
            Files.writeString(temporary.resolve("scripts/denovo/run_pp1_paper_task.py"),"# printed source "+reported);
            try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),printed)) {
                var done=f.await(jobs,jobs.start("analysis",request).path("taskId").asText());
                assertEquals(reported.equals("c.1A>G")?"READY":"FAILED",done.path("status").asText(),done.toPrettyString());
                if(!reported.equals("c.1A>G"))assertEquals("PP1_WORKER_TARGET_MISMATCH",done.path("reasonCode").asText());
            }
        }
    }

    @Test void repairedResourceContinuationRequiresReviewedSourceAndKnownPostprocessingStage() {
        var f=fixture();var value=f.json.createObjectNode().put("schemaVersion","pp1-paper-process-v1")
                .put("pmcid","PMC123").put("bundleSha256","b".repeat(64)).put("stopReason","PP1_SOURCE_IO_FAILED");
        var state=value.putObject("readingState").put("stage","REVIEW_CACHE_REGISTRATION");
        state.putObject("available").put("acceptedReviewArtifacts",1);
        state.putObject("checkpoint").put("pmcid","PMC123").put("bundleSha256","b".repeat(64))
                .putArray("acceptedArtifactSha256s").add("a".repeat(64));
        assertTrue(Pp1LiteratureJobs.processingContinuationAvailable(value));
        state.put("stage","SOURCE_EXTRACTION");assertFalse(Pp1LiteratureJobs.processingContinuationAvailable(value));
        state.put("stage","REVIEW_CACHE_REGISTRATION");state.withObject("checkpoint").put("pmcid","PMC456");
        assertFalse(Pp1LiteratureJobs.processingContinuationAvailable(value));
        state.withObject("checkpoint").put("pmcid","PMC123").putArray("acceptedArtifactSha256s");
        assertFalse(Pp1LiteratureJobs.processingContinuationAvailable(value));
    }
}
