package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual durable Jobs and current controls with explicit synthetic worker/source data.
 * These checks do not claim live LitVar/model or clinical source qualification. */
class T02Pp1DiscoveryControlsTest {
    @TempDir(factory=DeNovoCurrentTestDirectory.class) Path temporary;

    private Pp1LiteratureJobsTest fixture(String label) {
        var f=new Pp1LiteratureJobsTest();f.root=temporary.resolve(Integer.toUnsignedString(label.hashCode(),36));return f;
    }
    private ExternalProcessRunner worker(Pp1LiteratureJobsTest f,Path config,AtomicInteger calls,
            Consumer<ObjectNode> changeState,Consumer<ObjectNode> changeResult) throws Exception {
        var base=f.discoveryRunner(config,calls);
        return (command,cwd,remaining)->{
            var exit=base.run(command,cwd,remaining);Path folder=Path.of(command.get(command.indexOf("--output")+1));
            Path statePath=folder.resolve("discovery-state.json"),resultPath=folder.resolve("result.json");
            var state=(ObjectNode)f.json.readTree(Files.readAllBytes(statePath));var result=(ObjectNode)f.json.readTree(Files.readAllBytes(resultPath));
            changeState.accept(state);byte[] raw=f.json.writeValueAsBytes(state);Files.write(statePath,raw);
            result.withObject("discoveryState").put("sha256",FamilyDeNovoJson.sha256(raw));changeResult.accept(result);
            Files.write(resultPath,f.json.writeValueAsBytes(result));return exit;
        };
    }
    private Path paper(Pp1LiteratureJobsTest f,String id) {return f.root.resolve("local/work/pp1-artifacts").resolve(id).resolve("paper");}
    private Path task(Pp1LiteratureJobsTest f,String id) {
        return f.root.resolve("local/work/pp1-paper-tasks").resolve(FamilyDeNovoJson.sha256("analysis".getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve(id);
    }
    private ObjectNode directContinuation(Pp1LiteratureJobsTest f,String id,ObjectNode saved) throws Exception {
        var request=(ObjectNode)f.json.readTree(Files.readAllBytes(task(f,id).resolve("request.json")));
        Path state=paper(f,id).resolve("discovery-state.json"),manifest=paper(f,id).resolve("artifacts.json");
        request.putObject("continuation").put("parentTaskId",id).put("parentResultSha256",saved.path("resultSha256").asText())
                .put("stateSha256",FamilyDeNovoJson.sha256(Files.readAllBytes(state)))
                .put("statePath",f.root.relativize(state).toString().replace('\\','/'))
                .put("manifestPath",f.root.relativize(manifest).toString().replace('\\','/'))
                .put("manifestSha256",FamilyDeNovoJson.sha256(Files.readAllBytes(manifest)));
        return request;
    }
    private void publish(String label,Pp1LiteratureJobsTest f,String id,ObjectNode response) throws Exception {
        String destination=System.getProperty("t02.pp1.discovery.output","");
        if(destination.isBlank())return;
        Path out=Files.createDirectories(Path.of(destination).resolve(label));
        Files.write(out.resolve("actual-task-response.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsBytes(response));
        for(String name:List.of("identity.json","request.json","result.json"))Files.copy(task(f,id).resolve(name),out.resolve(name),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for(String name:List.of("discovery-state.json","artifacts.json"))Files.copy(paper(f,id).resolve(name),out.resolve(name),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(out.resolve("source-layer.txt"),"SYNTHETIC_TEST_ONLY_REAL_DURABLE_JOBS_NO_LIVE_PROVIDER_OR_CLINICAL_ADOPTION");
    }

    @Test void currentDiscoveryPermissionContinuesWithFiniteAttemptsAndImmutableParents() throws Exception {
        var f=fixture("finite");Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),f.discoveryRunner(config,calls))) {
            String id=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();
            for(int attempt=1;attempt<=3;attempt++) {
                var saved=f.await(jobs,id);assertEquals("READY",saved.path("status").asText());
                assertEquals(attempt,saved.path("discoveryAttempt").asInt());assertEquals(3,saved.path("maximumDiscoveryAttempts").asInt());
                assertEquals(attempt<3,saved.path("canContinueDiscovery").asBoolean());assertFalse(saved.path("canContinueProcessing").asBoolean());
                assertFalse(saved.path("sharedBudgetReconciliationRequired").asBoolean());assertEquals(attempt,calls.get());
                publish("attempt-"+attempt,f,id,saved);
                byte[] original=Files.readAllBytes(task(f,id).resolve("result.json"));
                if(attempt<3) {
                    String parent=id;String child=jobs.startDiscovery("analysis",binding,target,parent).path("taskId").asText();f.await(jobs,child);
                    assertNotEquals(parent,child);assertEquals(child,jobs.startDiscovery("analysis",binding,target,parent).path("taskId").asText());
                    assertArrayEquals(original,Files.readAllBytes(task(f,parent).resolve("result.json")));assertEquals(saved,jobs.read("analysis",parent));
                    id=child;
                } else {
                    String finalId=id;
                    assertEquals("PP1_DISCOVERY_CONTINUATION_LIMIT",saved.path("discoveryContinuationBlockedReason").asText());
                    assertEquals("PP1_DISCOVERY_CONTINUATION_LIMIT",assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",binding,target,finalId)).getMessage());
                    assertEquals("PP1_DISCOVERY_CONTINUATION_LIMIT",assertThrows(java.io.IOException.class,()->jobs.start("analysis",directContinuation(f,finalId,saved))).getMessage());
                }
            }
            assertEquals(3,calls.get());assertEquals(3,jobs.list("analysis").size());
        }
    }

    @Test void rootAndPaperAccountingMarkersBlockCurrentDiscoveryAndBothStartEntrypoints() throws Exception {
        for(String marker:List.of("ROOT_ISSUE","PAPER_RECOVERY","ADVICE_RESERVATION")) {
            var f=fixture("accounting-"+marker);Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
            var transport=worker(f,config,calls,state->{},result->{
                if(marker.equals("PAPER_RECOVERY")) ((ObjectNode)result.path("papers").get(0)).putObject("readingState").putObject("sourceExecutionRecovery").put("sharedBudgetReconciliationRequired",true);
                else if(marker.equals("ADVICE_RESERVATION"))result.put("modelChargeStatus","FAILED_OR_UNKNOWN_RESERVATION_RETAINED");
                else result.putArray("issues").add("DENOVO_MODEL_TIMEOUT");
            });
            try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),transport)) {
                var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
                String id=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();var saved=f.await(jobs,id);
                assertFalse(saved.path("canContinueDiscovery").asBoolean());assertTrue(saved.path("sharedBudgetReconciliationRequired").asBoolean());
                assertEquals("PP1_MODEL_BUDGET_RECONCILIATION_REQUIRED",saved.path("discoveryContinuationBlockedReason").asText());
                publish("accounting-"+marker,f,id,saved);
                byte[] original=Files.readAllBytes(task(f,id).resolve("result.json"));
                assertEquals("PP1_MODEL_BUDGET_RECONCILIATION_REQUIRED",assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",binding,target,id)).getMessage());
                var direct=directContinuation(f,id,saved);
                assertEquals("PP1_MODEL_BUDGET_RECONCILIATION_REQUIRED",assertThrows(java.io.IOException.class,()->jobs.start("analysis",direct)).getMessage());
                assertEquals(saved,jobs.read("analysis",id));assertEquals(1,calls.get());assertEquals(1,jobs.list("analysis").size());
                assertArrayEquals(original,Files.readAllBytes(task(f,id).resolve("result.json")));
            }
        }
    }

    @Test void resumeHintWithoutActualPendingCandidateQueryOrDeferredWorkCannotContinue() throws Exception {
        for(boolean complete:List.of(false,true)) {
            var f=fixture("no-pending-"+complete);Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
            var transport=worker(f,config,calls,state->{state.putArray("pendingCandidates");state.put("discoveryComplete",complete);},
                    result->result.withObject("discoveryState").put("pendingPapers",0));
            try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),transport)) {
                String id=jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),null).path("taskId").asText();var saved=f.await(jobs,id);
                assertFalse(saved.path("canContinueDiscovery").asBoolean());assertEquals("PP1_NO_PENDING_DISCOVERY_WORK",saved.path("discoveryContinuationBlockedReason").asText());
                publish("no-pending-"+complete,f,id,saved);
                assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),id));
                assertEquals(1,calls.get());
            }
        }
    }

    @Test void genuineUnfinishedQueryCanContinueWithoutAPendingPaper() throws Exception {
        var f=fixture("query");Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        var transport=worker(f,config,calls,state->{
            state.putArray("pendingCandidates");state.put("discoveryComplete",false);state.withObject("summary").put("pendingQueries",1);
            state.withObject("checkpoint").putArray("queries").addObject().put("status","TIME_LIMIT").put("executionComplete",false);
        },result->result.withObject("discoveryState").put("pendingPapers",0).put("pendingQueries",1));
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),transport)) {
            var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
            String id=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();var saved=f.await(jobs,id);
            assertTrue(saved.path("canContinueDiscovery").asBoolean(),saved.toPrettyString());
            publish("pending-query",f,id,saved);
            String child=jobs.startDiscovery("analysis",binding,target,id).path("taskId").asText();f.await(jobs,child);assertEquals(2,calls.get());
        }
    }

    @Test void wrongSelectedStateAndWrongImplementationDoNotReceiveCurrentPermission() throws Exception {
        for(String error:List.of("SELECTION","IMPLEMENTATION","COUNT")) {
            var f=fixture("wrong-"+error);Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
            var transport=worker(f,config,calls,state->{
                if(error.equals("SELECTION"))state.put("selectionPin","0".repeat(64));
                if(error.equals("IMPLEMENTATION"))state.withObject("checkpoint").withObject("implementation").put("litvar_literature.py","0".repeat(64));
            },result->{if(error.equals("COUNT"))result.withObject("discoveryState").put("pendingPapers",2);});
            try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),transport)) {
                String id=jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),null).path("taskId").asText();var saved=f.await(jobs,id);
                assertFalse(saved.path("canContinueDiscovery").asBoolean());
                assertEquals(error.equals("IMPLEMENTATION")?"PP1_DISCOVERY_STATE_IMPLEMENTATION_CHANGED":error.equals("COUNT")?"PP1_NO_PENDING_DISCOVERY_WORK":"PP1_DISCOVERY_STATE_IDENTITY_INVALID",
                        saved.path("discoveryContinuationBlockedReason").asText());
                assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),id));
                assertEquals(1,calls.get());
            }
        }
    }

    @Test void changedStateBytesOrManifestDisableCurrentPermissionWithoutRewritingTheOriginal() throws Exception {
        var f=fixture("bytes");Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),f.discoveryRunner(config,calls))) {
            String id=jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),null).path("taskId").asText();var saved=f.await(jobs,id);
            byte[] result=Files.readAllBytes(task(f,id).resolve("result.json"));
            for(String name:List.of("discovery-state.json","artifacts.json")) {
                Path file=paper(f,id).resolve(name);byte[] original=Files.readAllBytes(file);Files.writeString(file,"{}");
                var blocked=jobs.read("analysis",id);assertFalse(blocked.path("canContinueDiscovery").asBoolean());
                assertEquals("PP1_CONTINUATION_BYTES_CHANGED",blocked.path("discoveryContinuationBlockedReason").asText());
                assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",(ObjectNode)request.path("analysisBinding"),(ObjectNode)request.path("target"),id));
                Files.write(file,original);assertEquals(saved,jobs.read("analysis",id));
            }
            assertArrayEquals(result,Files.readAllBytes(task(f,id).resolve("result.json")));assertEquals(1,calls.get());
        }
    }

    @Test void changedBindingTargetAndDirectContinuationPinsCannotLaunchAWorker() throws Exception {
        var f=fixture("scope");Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),f.discoveryRunner(config,calls))) {
            var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
            String id=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();var saved=f.await(jobs,id);
            var wrong=binding.deepCopy();wrong.withObject("identity").put("candidateId","another");
            assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",wrong,target,id));
            var other=target.deepCopy();other.putArray("variantAliases").add("c.1A>C");
            assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",binding,other,id));
            for(String key:List.of("parentResultSha256","stateSha256","manifestSha256","statePath","manifestPath")) {
                var direct=directContinuation(f,id,saved);direct.withObject("continuation").put(key,key.endsWith("Path")?"local/other":"0".repeat(64));
                assertThrows(java.io.IOException.class,()->jobs.start("analysis",direct),key);
            }
            assertEquals(saved,jobs.read("analysis",id));assertEquals(1,calls.get());assertEquals(1,jobs.list("analysis").size());
        }
    }

    @Test void changedModelOrImplementationDisablesDiscoveryWithoutResigningSource() throws Exception {
        var f=fixture("dependencies");Path config=f.setup();var calls=new AtomicInteger();var request=f.request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),f.discoveryRunner(config,calls))) {
            var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
            String id=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();var saved=f.await(jobs,id);
            byte[] identity=Files.readAllBytes(task(f,id).resolve("identity.json"));
            for(Path file:List.of(f.root.resolve("local/model.json"),f.root.resolve("scripts/denovo/run_pp1_paper_task.py"))) {
                byte[] original=Files.readAllBytes(file);Files.writeString(file,"changed dependency");
                var blocked=jobs.read("analysis",id);assertFalse(blocked.path("canContinueDiscovery").asBoolean());
                assertEquals("PP1_TASK_DEPENDENCY_CHANGED",blocked.path("discoveryContinuationBlockedReason").asText());
                assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",binding,target,id));
                Files.write(file,original);assertEquals(saved,jobs.read("analysis",id));
            }
            assertArrayEquals(identity,Files.readAllBytes(task(f,id).resolve("identity.json")));assertEquals(1,calls.get());
        }
    }
}
