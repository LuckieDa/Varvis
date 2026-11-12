package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pm3LiteratureJobsTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    @Test void needDrivenRoundsReceiveAssessmentAndStopAfterCheckedScope() throws Exception {
        Path config=setup();var c=(ObjectNode)json.readTree(Files.readAllBytes(config));
        c.put("pm3NeedDrivenReading",true).put("allowModel",true).put("pm3MaximumReadingRounds",4);
        Files.write(config,json.writeValueAsBytes(c));var calls=new AtomicInteger();
        ExternalProcessRunner worker=(command,cwd,timeout)->{
            int n=calls.getAndIncrement();
            Path input=Path.of(command.get(command.indexOf("--request")+1));
            byte[] raw=Files.readAllBytes(input);var req=(ObjectNode)json.readTree(raw);
            var feedback=json.readTree(Files.readAllBytes(Path.of(command.get(command.indexOf("--reading-feedback")+1))));
            assertEquals(FamilyDeNovoJson.sha256(raw),feedback.path("requestSha256").asText());
            if(n==0)assertTrue(feedback.path("cacheOnly").asBoolean());
            else assertEquals("GENOTYPE",feedback.at("/papers/0/needs/0/group").asText());
            var out=req.deepCopy().put("schemaVersion","pm3-paper-process-v1").put("status","PARTIAL")
                .put("requestSha256",FamilyDeNovoJson.sha256(raw)).put("workflowConfigSha256",FamilyDeNovoJson.sha256(Files.readAllBytes(config)))
                .put("businessPublishable",false).put("scientificExecution",false).put("modelCalls",n==0?0:2)
                .put("readingCachePass",n==0).put("readingModelEnabled",true);
            var done=out.putArray("completedReviewChunks");for(int i=0;i<n;i++)done.add(i);
            var pending=out.putObject("readingPlan").put("scope","SYNTHETIC_CHECKED_SCOPE").putArray("pendingRelevantChunks");
            for(int i=n;i<2;i++)pending.add(i);
            out.putArray("issues");if(n==0)out.withArray("issues").add("MODEL_CACHE_MISSING");
            Path dest=Path.of(command.get(command.indexOf("--output")+1));Files.createDirectory(dest);
            Files.write(dest.resolve("result.json"),json.writeValueAsBytes(out));return new ProcessResult(0,"",false);
        };
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            String id=jobs.start("analysis",request()).path("taskId").asText();var done=waitFor(jobs,id);
            assertEquals("READY",done.path("status").asText(),done.toPrettyString());
            assertEquals(3,calls.get());assertEquals(4,done.at("/result/modelCalls").asInt());
            assertEquals("SEARCHED_SCOPE_INSUFFICIENT",done.at("/result/readingLoop/state").asText());
            assertEquals(3,done.at("/result/readingLoop/roundCount").asInt());
            jobs.start("analysis",request());assertEquals(3,calls.get());
        }
    }
    Path setup() throws Exception {
        prepareWorker(root);
        Files.createDirectories(root.resolve("local/library/evidence/PM3"));Files.writeString(root.resolve("local/library/evidence/PM3/index.json"),"{}");
        Files.writeString(root.resolve("local/model.json"),"{}");
        var c=json.createObjectNode().put("schemaVersion","denovo-public-literature-workflow-v1").put("enabled",true)
                .put("projectRoot",root.toString()).put("work","local/work").put("library","local/library")
                .put("modelConfig","local/model.json").put("python","local/python.exe");
        Path file=root.resolve("local/workflow.json");Files.write(file,json.writeValueAsBytes(c));return file;
    }

    private static void prepareWorker(Path checkout) throws Exception {
        for (String name : List.of("denovo/run_pm3_paper_task.py", "ps1_pm5/prepare_reference_evidence.py",
                "ps1_pm5/prepare_expert_reference_evidence.py", "pvs1/prepare_applicability_sources.py",
                "pvs1/assemble_materials.py", "acmg-generalization/import_cspec_catalog.py")) {
            Path path = checkout.resolve("scripts").resolve(name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "# synthetic worker source");
        }
    }

    @Test void isolatedCheckoutUsesSharedDataRootAndBindsItsOwnWorkerAtCapture() throws Exception {
        Path config = setup();
        String relative = "local/development/checkouts/pm3-isolated";
        Path project = root.toRealPath();
        Path checkout = project.resolve(relative);
        prepareWorker(checkout);
        Path workerSource = checkout.resolve("scripts/denovo/run_pm3_paper_task.py");
        var settings = (ObjectNode) json.readTree(Files.readAllBytes(config));
        settings.put("pm3WorkerCheckout", relative);
        Files.write(config, json.writeValueAsBytes(settings));
        var calls = new AtomicInteger();
        var normal = runner(config, calls, false);
        ExternalProcessRunner worker = (command, cwd, timeout) -> {
            assertEquals(checkout, cwd);
            assertTrue(command.contains(workerSource.toString()));
            assertEquals(project.resolve("local/python.exe").toString(), command.get(0));
            assertTrue(Path.of(command.get(command.indexOf("--output") + 1)).startsWith(project.resolve("local/work")));
            return normal.run(command, cwd, timeout);
        };
        var selected = json.createObjectNode().put("executionSourceRecovered", true).put("requestedN", 1);
        selected.putArray("candidates").addObject().put("candidateId", "c1");
        try (var jobs = new Pm3LiteratureJobs(config, new DeNovoSourceTasks(1, 2, 10000, true), worker)) {
            var request = request().put("selectionSha256", jobs.selectionOptions(selected).path("selectionSha256").asText());
            String id = jobs.start("analysis", request).path("taskId").asText();
            assertEquals("READY", waitFor(jobs, id).path("status").asText());
            assertEquals(1, jobs.capture("analysis", selected).path("documents").size());
            Files.writeString(workerSource, "# changed isolated worker");
            assertEquals("PM3_TASK_METHOD_STALE", jobs.capture("analysis", selected).at("/excludedTasks/0/reasonCode").asText());
            settings.put("pm3WorkerCheckout", "local/library");
            Files.write(config, json.writeValueAsBytes(settings));
            assertEquals("PM3_WORKER_CHECKOUT_INVALID",
                    assertThrows(java.io.IOException.class, () -> jobs.start("analysis", request)).getMessage());
            assertEquals(1, calls.get());
        }
    }
    @Test void laterWorkerFailureKeepsCheckpointAndNeverClaimsAbsence() throws Exception {
        Path config=setup();var c=(ObjectNode)json.readTree(Files.readAllBytes(config));
        c.put("pm3NeedDrivenReading",true).put("allowModel",true);Files.write(config,json.writeValueAsBytes(c));
        var calls=new AtomicInteger();var base=runner(config,new AtomicInteger(),false);
        ExternalProcessRunner worker=(command,cwd,timeout)->{
            if(calls.getAndIncrement()>0)throw new java.io.IOException("synthetic worker failure");
            var done=base.run(command,cwd,timeout);Path dest=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
            var result=(ObjectNode)json.readTree(Files.readAllBytes(dest));result.putArray("completedReviewChunks").add(43);
            result.putObject("readingPlan").putArray("pendingRelevantChunks").add(4);
            result.putArray("issues");Files.write(dest,json.writeValueAsBytes(result));return done;
        };
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            String id=jobs.start("analysis",request()).path("taskId").asText();var done=waitFor(jobs,id);
            assertEquals("READY",done.path("status").asText(),done.toString());
            assertEquals("INCOMPLETE",done.at("/result/readingLoop/state").asText());
            assertEquals("PM3_WORKER_FAILED",done.at("/result/readingLoop/stopReason").asText());
            assertEquals(43,done.at("/result/completedReviewChunks/0").asInt());
            assertEquals(2,calls.get());assertEquals(id,jobs.start("analysis",request()).path("taskId").asText());
        }
    }
    ObjectNode request() {
        var r=json.createObjectNode().put("schemaVersion","pm3-public-paper-task-v1").put("pmcid","PMC123")
                .put("bundleSha256","b".repeat(64)).put("selectionSha256","a".repeat(64));
        r.putObject("target").put("geneSymbol","EXAMPLE").putArray("variantAliases").add("c.1A>G");
        r.putObject("selectedAllele").put("assembly","GRCh38").put("contig","1").put("position",1).put("reference","A").put("alternate","G");
        return r;
    }
    @Test void modelDisabledPaperContinuesAsImmutableChildAndStartsWithCacheOnly() throws Exception {
        Path config=setup();var c=(ObjectNode)json.readTree(Files.readAllBytes(config));
        c.put("pm3NeedDrivenReading",true).put("allowModel",false);Files.write(config,json.writeValueAsBytes(c));
        var calls=new AtomicInteger();var base=runner(config,new AtomicInteger(),false);
        ExternalProcessRunner worker=(command,cwd,timeout)->{
            int n=calls.getAndIncrement();
            var feedback=json.readTree(Files.readAllBytes(Path.of(command.get(command.indexOf("--reading-feedback")+1))));
            assertTrue(feedback.path("cacheOnly").asBoolean());
            var finished=base.run(command,cwd,timeout);
            Path file=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
            var result=(ObjectNode)json.readTree(Files.readAllBytes(file));
            result.putArray("completedReviewChunks").add(43);
            var pending=result.putObject("readingPlan").putArray("pendingRelevantChunks");
            if(n==0)pending.add(4);else result.withArray("completedReviewChunks").add(4);
            result.putArray("issues");Files.write(file,json.writeValueAsBytes(result));return finished;
        };
        var selected=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        selected.putArray("candidates").addObject().put("candidateId","c1");
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            String pin=jobs.selectionOptions(selected).path("selectionSha256").asText();
            String parent=jobs.start("analysis",request().put("selectionSha256",pin)).path("taskId").asText();
            var before=waitFor(jobs,parent);assertTrue(before.path("canContinueProcessing").asBoolean(),before.toPrettyString());
            assertEquals("PM3_MODEL_DISABLED_PENDING_READING",before.at("/result/readingLoop/feedback/stopReason").asText());
            assertFalse(before.path("sharedBudgetReconciliationRequired").asBoolean());
            String child=jobs.continueDiscovery("analysis",selected,pin,parent).path("taskId").asText();
            assertNotEquals(parent,child);var after=waitFor(jobs,child);
            assertEquals("READY",after.path("status").asText(),after.toPrettyString());
            assertEquals(parent,after.at("/result/processingContinuation/parentTaskId").asText());
            assertEquals(2,after.at("/result/completedReviewChunks").size());
            assertFalse(after.path("canContinueProcessing").asBoolean());
            assertEquals(before,jobs.read("analysis",parent));
            assertEquals(child,jobs.continueDiscovery("analysis",selected,pin,parent).path("taskId").asText());
            assertEquals(2,calls.get());
            assertThrows(java.io.IOException.class,()->jobs.continueDiscovery("analysis",selected,pin,child));
        }
    }
    @Test void continuationRequiresPendingWorkAndDoesNotRetryConflictsOrNoProgress() {
        var result=json.createObjectNode();var loop=result.putObject("readingLoop").put("state","INCOMPLETE");
        var feedback=loop.putObject("feedback").put("hasPendingRelevantWork",true);
        for(String stop:List.of("PM3_READING_TIME_LIMIT","PM3_READING_ROUND_LIMIT","PM3_WORKER_FAILED","PM3_WORKER_TIMEOUT","PM3_MODEL_DISABLED_PENDING_READING")) {
            feedback.put("stopReason",stop);assertTrue(Pm3LiteratureJobs.continuationAvailable(result),stop);
        }
        feedback.put("sourceConflict",true);assertFalse(Pm3LiteratureJobs.continuationAvailable(result));
        feedback.put("sourceConflict",false).put("hasPendingRelevantWork",false);assertFalse(Pm3LiteratureJobs.continuationAvailable(result));
        feedback.putArray("papers").addObject().putArray("pendingRelevantChunks").add(4);
        assertTrue(Pm3LiteratureJobs.continuationAvailable(result));
        for(String stop:List.of("PM3_READING_NO_PROGRESS","PM3_EXTERNAL_OR_UNSUPPORTED_REQUIREMENT","UNKNOWN")) {
            feedback.put("stopReason",stop);assertFalse(Pm3LiteratureJobs.continuationAvailable(result));
        }
    }
    ExternalProcessRunner runner(Path config,AtomicInteger calls,boolean tamper) {
        return (command,cwd,timeout)->{
            calls.incrementAndGet();
            Path input=Path.of(command.get(command.indexOf("--request")+1));
            Path output=Path.of(command.get(command.indexOf("--output")+1));Files.createDirectory(output);
            byte[] raw=Files.readAllBytes(input);var req=(ObjectNode)json.readTree(raw);
            var result=req.deepCopy().put("schemaVersion","pm3-public-discovery-task-v1".equals(req.path("schemaVersion").asText())?"pm3-discovery-process-v1":"pm3-paper-process-v1").put("status","PARTIAL")
                    .put("requestSha256",tamper?"0".repeat(64):FamilyDeNovoJson.sha256(raw))
                    .put("workflowConfigSha256",FamilyDeNovoJson.sha256(Files.readAllBytes(config)))
                    .put("businessPublishable",false).put("scientificExecution",false).put("modelCalls",0);
            Files.write(output.resolve("result.json"),json.writeValueAsBytes(result));
            return new ProcessResult(0,"",false);
        };
    }
    ObjectNode waitFor(Pm3LiteratureJobs jobs,String id) throws Exception {
        long end=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<end) {
            var r=jobs.read("analysis",id);
            if(!java.util.Set.of("QUEUED","RUNNING").contains(r.path("status").asText())) return r;
            Thread.sleep(10);
        }
        throw new AssertionError("task did not finish");
    }
    @Test void durableRepeatAndRestartNeverRunTwice() throws Exception {
        Path config=setup();var calls=new AtomicInteger();String id;
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            id=jobs.start("analysis",request()).path("taskId").asText();
            var done=waitFor(jobs,id);assertEquals("READY",done.path("status").asText(),done.toPrettyString());
            assertEquals("PARTIAL",done.at("/result/status").asText());
            assertEquals(id,jobs.start("analysis",request()).path("taskId").asText());assertEquals(1,calls.get());
        }
        try(var restarted=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            var history=restarted.list("analysis");assertEquals(1,history.size());
            assertEquals(id,history.get(0).path("taskId").asText());
            assertEquals("pm3-public-literature-task-v1",history.get(0).path("schemaVersion").asText());
            assertTrue(restarted.list("other-analysis").isEmpty());assertEquals(1,calls.get());
            assertEquals("READY",restarted.start("analysis",request()).path("status").asText());assertEquals(1,calls.get());
        }
    }
    @Test void changedRequestReceiptRejected() throws Exception {
        Path config=setup();var calls=new AtomicInteger();
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,true))) {
            String id=jobs.start("analysis",request()).path("taskId").asText();
            var done=waitFor(jobs,id);assertEquals("FAILED",done.path("status").asText());
            assertFalse(done.path("canRetry").asBoolean());assertFalse(done.has("result"));
        }
    }
    @Test void qualifiedWorkerResultPersistsAndRepeatDoesNotQueryAgain() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var created=new AtomicInteger();
        var f=new Pm3LiteratureRaritySupplyTest();var row=f.row(true);var request=request();
        request.set("selectedAllele",row.at("/identities/target/allele"));
        var runtime=f.unavailable();var factory=f.factory(runtime,created);var base=runner(config,calls,false);
        ExternalProcessRunner worker=(command,cwd,timeout)->{
            var done=base.run(command,cwd,timeout);Path out=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
            var result=(ObjectNode)json.readTree(Files.readAllBytes(out));result.set("observations",f.paper(row).path("observations"));
            Files.write(out,json.writeValueAsBytes(result));return done;
        };
        String id;ObjectNode saved;
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker,factory)) {
            id=jobs.start("analysis",request).path("taskId").asText();var done=waitFor(jobs,id);
            assertEquals("READY",done.path("status").asText(),done.toPrettyString());saved=(ObjectNode)done.path("result");
            assertTrue(done.path("pm3ScientificExecution").asBoolean());
            assertTrue(saved.at("/pm3Assessment/pm3CalculatorCalls").asInt()>0);
            assertEquals("SPECIFICATION_SELECTION_PENDING",saved.at("/pm3Assessment/policyApplicability").asText());
            assertFalse(saved.path("businessPublishable").asBoolean());
            assertEquals("PM2_TEST_RESOURCE_UNAVAILABLE",saved.at("/observations/cases/0/resolvedObservations/0/rarityQualification/target/reasonCode").asText());
            assertEquals(1,created.get());assertEquals(id,jobs.start("analysis",request).path("taskId").asText());
        }
        try(var rebuilt=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker,factory)) {
            assertEquals(saved,rebuilt.read("analysis",id).path("result"));
            assertEquals(id,rebuilt.start("analysis",request).path("taskId").asText());
            assertEquals(1,calls.get());assertEquals(1,created.get());
        }
        org.mockito.Mockito.verify(runtime,org.mockito.Mockito.times(1)).evaluateReference(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.isNull());
    }
    @Test void changedPopulationPinInvalidatesReuseAndMidWorkerChangeRejectsEnrichment() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var version=new AtomicInteger();var create=new AtomicInteger();
        var factory=new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity(){return json.createObjectNode().put("version",version.get());}
            public Pm2LocalExomesRuntime create(){create.incrementAndGet();throw new AssertionError("No verified rows; no query expected");}
        };
        var base=runner(config,calls,false);
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),base,factory)) {
            String first=jobs.start("analysis",request()).path("taskId").asText();assertEquals("READY",waitFor(jobs,first).path("status").asText());
            version.incrementAndGet();String second=jobs.start("analysis",request()).path("taskId").asText();
            assertNotEquals(first,second);assertEquals("READY",waitFor(jobs,second).path("status").asText());
        }
        version.incrementAndGet();
        ExternalProcessRunner changed=(command,cwd,timeout)->{var r=base.run(command,cwd,timeout);version.incrementAndGet();return r;};
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),changed,factory)) {
            var r=waitFor(jobs,jobs.start("analysis",request()).path("taskId").asText());
            assertEquals("FAILED",r.path("status").asText());assertFalse(r.has("result"));
        }
        assertEquals(0,create.get());
    }
    @Test void discoveryDoesNotDependOnAnExistingOrUnchangedPm3Index() throws Exception {
        Path config=setup();Path index=root.resolve("local/library/evidence/PM3/index.json");Files.delete(index);
        var calls=new AtomicInteger();var request=request().put("schemaVersion","pm3-public-discovery-task-v1");
        request.remove(java.util.List.of("pmcid","bundleSha256"));
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            String id=jobs.start("analysis",request).path("taskId").asText();
            assertEquals("READY",waitFor(jobs,id).path("status").asText());
            Files.writeString(index,"{\"newlyAcquiredPaper\":true}");
            assertEquals(id,jobs.start("analysis",request).path("taskId").asText());assertEquals(1,calls.get());
        }
    }
    @Test void continuationUsesPinnedServerStateAndIsIdempotent() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var base=runner(config,calls,false);
        ExternalProcessRunner worker=(command,cwd,timeout)->{
            var finished=base.run(command,cwd,timeout);
            Path out=Path.of(command.get(command.indexOf("--output")+1));
            byte[] state="{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(out.resolve("discovery-state.json"),state);
            var result=(ObjectNode)json.readTree(Files.readAllBytes(out.resolve("result.json")));
            result.putObject("discoveryState").put("resumeAvailable",true).put("sha256",FamilyDeNovoJson.sha256(state));
            Files.write(out.resolve("result.json"),json.writeValueAsBytes(result));return finished;
        };
        var selected=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        selected.putArray("candidates").addObject().put("candidateId","c1");
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            String pin=jobs.selectionOptions(selected).path("selectionSha256").asText();
            var request=request().put("schemaVersion","pm3-public-discovery-task-v1").put("selectionSha256",pin);
            request.remove(java.util.List.of("pmcid","bundleSha256"));
            String parent=jobs.start("analysis",request).path("taskId").asText();assertEquals("READY",waitFor(jobs,parent).path("status").asText());
            assertThrows(java.io.IOException.class,()->jobs.continueDiscovery("analysis",selected,"0".repeat(64),parent));
            String child=jobs.continueDiscovery("analysis",selected,pin,parent).path("taskId").asText();
            assertNotEquals(parent,child);assertEquals("READY",waitFor(jobs,child).path("status").asText());
            assertEquals(child,jobs.continueDiscovery("analysis",selected,pin,parent).path("taskId").asText());assertEquals(2,calls.get());
        }
    }
    @Test void recoveredTopNBindingRejectsStaleOrForeignSelectionBeforeWorker() throws Exception {
        Path config=setup();var calls=new AtomicInteger();
        var selected=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1).put("sampleId","PRIVATE");
        var candidate=selected.putArray("candidates").addObject().put("candidateId","candidate-1").put("rank",1).put("geneSymbol","EXAMPLE");
        candidate.putObject("allele").put("genomeAssembly","GRCh38").put("chromosome","1").put("position",1).put("reference","A").put("alternate","G");
        candidate.putArray("transcriptAnnotations").addObject().put("geneSymbol","EXAMPLE").put("hgvsc","NM_000001.1:c.1A>G");
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            var options=jobs.selectionOptions(selected);assertFalse(options.toString().contains("PRIVATE"));
            String pin=options.path("selectionSha256").asText();
            assertThrows(java.io.IOException.class,()->jobs.startSelected("analysis",selected,"0".repeat(64),"candidate-1","PMC123","b".repeat(64)));
            assertThrows(java.io.IOException.class,()->jobs.startSelected("analysis",selected,pin,"outside","PMC123","b".repeat(64)));
            assertEquals(0,calls.get());
            String id=jobs.startSelected("analysis",selected,pin,"candidate-1","PMC123","b".repeat(64)).path("taskId").asText();
            var done=waitFor(jobs,id);assertEquals("READY",done.path("status").asText(),done.toString());
            assertEquals("NM_000001.1:c.1A>G",done.at("/result/target/variantAliases/0").asText());
            assertEquals(1,calls.get());
            candidate.withObject("allele").put("alternate","T");
            assertThrows(java.io.IOException.class,()->jobs.startSelected("analysis",selected,pin,"candidate-1","PMC123","b".repeat(64)));
            assertEquals(1,calls.get());
        }
    }
    @Test void businessCaptureIsImmutableReadOnlyAndRejectsChangedImplementationOrSelection() throws Exception {
        Path config=setup();var calls=new AtomicInteger();
        Files.writeString(root.resolve("scripts/denovo/run_pp1_paper_task.py"),"# independent PP1 entry point");
        var selected=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        selected.putArray("candidates").addObject().put("candidateId","c1");
        ObjectNode first;String id;
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            var request=request().put("selectionSha256",jobs.selectionOptions(selected).path("selectionSha256").asText());
            id=jobs.start("analysis",request).path("taskId").asText();assertEquals("READY",waitFor(jobs,id).path("status").asText());
            first=jobs.capture("analysis",selected);assertEquals(1,first.path("documents").size(),first.toPrettyString());
            assertEquals(1,calls.get());assertEquals(first,jobs.capture("analysis",selected));
        }
        try(var restarted=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            Files.writeString(root.resolve("scripts/denovo/run_pp1_paper_task.py"),"# unrelated PP1 change");
            assertEquals(first,restarted.capture("analysis",selected));assertEquals(1,calls.get());
            selected.put("requestedN",2);var stale=restarted.capture("analysis",selected);
            assertTrue(stale.path("documents").isEmpty());assertEquals("PM3_TASK_SELECTION_STALE",stale.at("/excludedTasks/0/reasonCode").asText());
            selected.put("requestedN",1);Files.writeString(root.resolve("scripts/denovo/run_pm3_paper_task.py"),"# changed method");
            stale=restarted.capture("analysis",selected);assertEquals("PM3_TASK_METHOD_STALE",stale.at("/excludedTasks/0/reasonCode").asText());
            assertEquals(1,calls.get());assertNotEquals(first.path("snapshotSha256"),stale.path("snapshotSha256"));
        }
    }
    @Test void decimalRetrievalMetadataCanBeCapturedWithoutNewWorkerOrLosingRawDigest() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var base=runner(config,calls,false);
        ExternalProcessRunner worker=(command,cwd,timeout)->{
            var done=base.run(command,cwd,timeout);
            Path output=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
            var result=(ObjectNode)json.readTree(Files.readAllBytes(output));
            result.putObject("discovery").putArray("retrievals").addObject()
                    .put("retrievedEpoch",new java.math.BigDecimal("1793368306.0243733"));
            Files.write(output,json.writeValueAsBytes(result));return done;
        };
        var selected=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        selected.putArray("candidates").addObject().put("candidateId","c1");
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            var request=request().put("selectionSha256",jobs.selectionOptions(selected).path("selectionSha256").asText());
            String id=jobs.start("analysis",request).path("taskId").asText();
            var ready=waitFor(jobs,id);assertEquals("READY",ready.path("status").asText());
            var snapshot=jobs.capture("analysis",selected);
            assertEquals(1,snapshot.path("documents").size());
            assertEquals(ready.path("resultSha256"),snapshot.at("/documents/0/resultSha256"));
            assertEquals(new java.math.BigDecimal("1793368306.0243733"),
                    snapshot.at("/documents/0/result/discovery/retrievals/0/retrievedEpoch").decimalValue());
            assertEquals(snapshot,jobs.capture("analysis",selected));assertEquals(1,calls.get());
            var batch=new Pm3SelectedBatch(json,new Pm3SelectedBatch.Source(){
                public ObjectNode identity(){return json.createObjectNode().put("testOnly",true);}
                public ObjectNode capture(String a,ObjectNode b){return snapshot;}
            });
            assertEquals("PM3_SOURCE_SNAPSHOT_CAPTURED",batch.prepare("analysis",selected).path("reasonCode").asText());
        }
    }
    @Test void legacyManifestMayDropOnlyProvenIndependentEntryPoint() throws Exception {
        Path config=setup();
        Files.writeString(root.resolve("scripts/denovo/run_pp1_paper_task.py"),"# independent");
        try(var jobs=new Pm3LiteratureJobs(config)) {
            var current=jobs.implementation(root);assertFalse(current.has("run_pp1_paper_task.py"));
            var old=current.deepCopy().put("run_pp1_paper_task.py","a".repeat(64));
            assertTrue(Pm3LiteratureJobs.compatibleImplementation(old,current));
            old.put("run_pm3_paper_task.py","b".repeat(64));
            assertFalse(Pm3LiteratureJobs.compatibleImplementation(old,current));
            old=current.deepCopy();old.remove("run_pm3_paper_task.py");
            assertFalse(Pm3LiteratureJobs.compatibleImplementation(old,current));
            old=current.deepCopy().put("unrecognized.py","c".repeat(64));
            assertFalse(Pm3LiteratureJobs.compatibleImplementation(old,current));
            for(String dependency:List.of("import run_pp1_paper_task","import importlib","__import__('worker')","import runpy")) {
                Files.writeString(root.resolve("scripts/denovo/run_pm3_paper_task.py"),dependency);
                var dependent=jobs.implementation(root);assertTrue(dependent.has("run_pp1_paper_task.py"));
                var bound=dependent.deepCopy();Files.writeString(root.resolve("scripts/denovo/run_pp1_paper_task.py"),UUID.randomUUID().toString());
                assertFalse(Pm3LiteratureJobs.compatibleImplementation(bound,jobs.implementation(root)));
            }
        }
    }
    @Test void ensemblVepAliasesAreSearchHintsWithoutChangingSelectedIdentity() throws Exception {
        Path config=setup();var calls=new AtomicInteger();
        var selected=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        var c=selected.putArray("candidates").addObject().put("candidateId","c1").put("geneSymbol","PAH");
        c.putObject("allele").put("genomeAssembly","GRCh38").put("chromosome","12").put("position",102840507).put("reference","G").put("alternate","A");
        var tx=c.putArray("transcriptAnnotations");
        tx.addObject().put("geneSymbol","PAH").put("hgvsc","ENST00000553106.6:c.1208C>T").put("hgvsp","ENSP00000448059.1:p.Ala403Val");
        tx.addObject().put("geneSymbol","OTHER").put("hgvsc","ENST00000000001.1:c.999G>A");
        tx.addObject().put("geneSymbol","PAH").put("hgvsc","ENST00000000001.1:c.1G>A private text");
        var before=selected.deepCopy();
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            String pin=jobs.selectionOptions(selected).path("selectionSha256").asText();
            var done=waitFor(jobs,jobs.startSelected("analysis",selected,pin,"c1",null,null).path("taskId").asText());
            assertEquals("READY",done.path("status").asText());
            assertEquals(json.createArrayNode().add("c.1208C>T").add("p.Ala403Val"),done.at("/result/target/variantAliases"));
            assertEquals(102840507,done.at("/result/selectedAllele/position").asInt());
            assertEquals(before,selected);assertEquals(1,calls.get());
        }
    }
    @Test void manyAliasesPreferManeAndKeepSelectionIntact() throws Exception {
        Path config=setup();var calls=new AtomicInteger();
        var selected=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",1);
        var c=selected.putArray("candidates").addObject().put("candidateId","c1").put("geneSymbol","PAH");
        c.putObject("allele").put("genomeAssembly","GRCh38").put("chromosome","12").put("position",102840507).put("reference","G").put("alternate","A");
        var tx=c.putArray("transcriptAnnotations");
        tx.addObject().put("geneSymbol","PAH").put("hgvsc","ENST00000553106.6:c.1208C>T").put("hgvsp","ENSP00000448059.1:p.Ala403Val");
        tx.addObject().put("geneSymbol","OTHER").put("hgvsc","ENST00000000001.1:c.999G>A");
        tx.addObject().put("geneSymbol","PAH").put("hgvsc","ENST00000000001.1:c.1G>A private text");
        for(int i=0;i<40;i++) tx.addObject().put("geneSymbol","PAH").put("hgvsc","c."+(2000+i)+"G>A");
        tx.addObject().put("geneSymbol","PAH").put("hgvsc","NM_000001.1:c.999A>G").put("maneSelect",true);
        var before=selected.deepCopy();
        try(var jobs=new Pm3LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls,false))) {
            String pin=jobs.selectionOptions(selected).path("selectionSha256").asText();
            var done=waitFor(jobs,jobs.startSelected("analysis",selected,pin,"c1",null,null).path("taskId").asText());
            assertEquals("READY",done.path("status").asText());
            assertEquals(32,done.at("/result/target/variantAliases").size());
            assertEquals("NM_000001.1:c.999A>G",done.at("/result/target/variantAliases/0").asText());
            assertEquals(102840507,done.at("/result/selectedAllele/position").asInt());
            assertEquals(before,selected);assertEquals(1,calls.get());
        }
    }
}
