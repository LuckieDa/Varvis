package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pp1LiteratureJobsTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    @Test void publishedContextsAreFilteredByBothGeneAndHgncAndConfigPin() throws Exception {
        Path config=setup();var candidates=json.createObjectNode();
        candidates.putArray("candidates").addObject().put("geneSymbol","GENE1").putObject("catalog").put("hgncId","HGNC:1");
        var bad=new java.util.concurrent.atomic.AtomicBoolean(false);
        ExternalProcessRunner worker=(command,cwd,remaining)->{
            var out=json.createObjectNode().put("schemaVersion","literature-context-options-v1")
                .put("workflowConfigSha256",bad.get()?"wrong":FamilyDeNovoJson.sha256(Files.readAllBytes(config)));
            var rows=out.putArray("contexts");
            rows.addObject().put("geneSymbol","GENE1").put("hgncId","HGNC:1").put("mondoId","MONDO:0000001");
            rows.addObject().put("geneSymbol","GENE1").put("hgncId","HGNC:2");
            rows.addObject().put("geneSymbol","OTHER").put("hgncId","HGNC:1");
            Files.write(Path.of(command.get(command.indexOf("--output")+1)),json.writeValueAsBytes(out));
            return new ProcessResult(0,"",false);
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),worker)) {
            assertEquals(1,jobs.contextOptions(candidates).path("contexts").size());
            bad.set(true);assertThrows(java.io.IOException.class,()->jobs.contextOptions(candidates));
        }
    }
    Path setup() throws Exception {
        Files.createDirectories(root.resolve("scripts/denovo"));
        Files.writeString(root.resolve("scripts/denovo/run_pp1_paper_task.py"), "# synthetic worker");
        Files.writeString(root.resolve("scripts/denovo/bundle_replay_profiles.json"), "{}");
        Files.createDirectories(root.resolve("local")); Files.writeString(root.resolve("local/model.json"), "{}");
        var c = json.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1").put("enabled", true)
                .put("projectRoot", root.toString()).put("work", "local/work").put("modelConfig", "local/model.json")
                .put("python", "local/python.exe");
        Path config = root.resolve("local/workflow.json"); Files.write(config, json.writeValueAsBytes(c)); return config;
    }
    ObjectNode request() {
        var c = json.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        c.putObject("scope").put("analysisDomain", "GERMLINE"); c.putObject("gene").put("symbol", "GENE1");
        c.putObject("disease").put("mondoId", "MONDO:0000001");
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1")
                .put("position", 100).put("reference", "A").put("alternate", "G");
        var binding = new Pp1AnalysisContext(json).bind(c, "candidate", "hypothesis", "a".repeat(64), List.of("ACMG_AMP_2015:PP1"));
        var out = json.createObjectNode().put("schemaVersion", "pp1-public-paper-task-v1").put("pmcid", "PMC123")
                .put("bundleSha256", "b".repeat(64)).put("selectionSha256", binding.at("/selection/selectionKey").asText());
        out.set("analysisBinding", binding); out.putObject("target").put("geneSymbol", "GENE1").putArray("variantAliases").add("c.1A>G");
        return out;
    }
    ExternalProcessRunner runner(Path config, AtomicInteger calls) {
        return (command, cwd, remaining) -> {
            calls.incrementAndGet();
            byte[] input = Files.readAllBytes(Path.of(command.get(command.indexOf("--request")+1)));
            var request = json.readTree(input);
            var out = json.createObjectNode().put("schemaVersion", "pp1-paper-process-v1").put("status", "EVALUATED")
                    .put("businessPublishable", false).put("scientificExecution", true)
                    .put("requestSha256", FamilyDeNovoJson.sha256(input))
                    .put("workflowConfigSha256", FamilyDeNovoJson.sha256(Files.readAllBytes(config)))
                    .put("analysisBindingSha256", new RuntimeEvidenceV2Adapter(json).canonicalSha256(request.path("analysisBinding")));
            for (String key : List.of("pmcid", "bundleSha256", "target", "selectionSha256")) out.set(key, request.path(key));
            if (request.has("searchAliasCoverage")) out.set("searchAliasCoverage",request.path("searchAliasCoverage"));
            var eval = out.putObject("evaluation").put("status", "NOT_EVALUATED").put("scope", "SYNTHETIC_WORKER_ONLY");
            eval.set("target", request.at("/analysisBinding/selection")); eval.set("ledger", request.at("/analysisBinding/ledger"));
            Path directory = Path.of(command.get(command.indexOf("--output")+1)); Files.createDirectories(directory);
            byte[] manifest=json.writeValueAsBytes(json.createObjectNode().set("acceptedArtifacts",json.createArrayNode()));
            Files.write(directory.resolve("artifacts.json"),manifest);out.put("artifactManifestSha256",FamilyDeNovoJson.sha256(manifest));
            Files.write(directory.resolve("result.json"), json.writeValueAsBytes(out)); return new ProcessResult(0, "", false);
        };
    }
    ObjectNode await(Pp1LiteratureJobs jobs, String id) throws Exception {
        for (int i=0; i<150; i++) {
            var status = jobs.read("analysis", id);
            if (!List.of("QUEUED", "RUNNING").contains(status.path("status").asText())) return status;
            Thread.sleep(20);
        }
        throw new AssertionError("synthetic task did not finish");
    }
    @Test void durableResultDuplicateAndReopenDoNotRunAgain() throws Exception {
        Path config = setup(); var calls = new AtomicInteger(); String id; ObjectNode done;
        try (var jobs = new Pp1LiteratureJobs(config, new DeNovoSourceTasks(1,2,10000,true), runner(config,calls))) {
            id = jobs.start("analysis", request()).path("taskId").asText(); done = await(jobs,id);
            assertEquals("READY", done.path("status").asText(), done.toString());
            assertTrue(Files.isRegularFile(root.resolve("local/work/pp1-artifacts").resolve(id).resolve("paper/artifacts.json")));
            assertEquals(id, jobs.start("analysis", request()).path("taskId").asText()); assertEquals(1,calls.get());
            var recovered=json.createObjectNode().put("executionSourceRecovered",true);
            recovered.putArray("candidates").addObject().put("candidateId","candidate");
            assertEquals(1,jobs.capture("analysis",recovered).path("documents").size());
        }
        try (var jobs = new Pp1LiteratureJobs(config, new DeNovoSourceTasks(1,2,10000,true), runner(config,calls))) {
            assertEquals(done, jobs.read("analysis",id)); jobs.start("analysis",request()); assertEquals(1,calls.get());
            assertEquals(1,jobs.list("analysis").size()); assertEquals(0,jobs.list("other-analysis").size());
        }
    }
    @Test void aliasCoverageSurvivesSavedTaskReopenAndCannotBeTamperedWith() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var request=request();
        var coverage=request.putObject("searchAliasCoverage").put("policy","PP1_MANE_CANONICAL_FIRST_MAX20_V1")
                .put("retrievalOnly",true).put("availableAliases",2).put("selectedAliases",1).put("omittedAliases",1);
        coverage.putArray("deferredAliases").add("c.2A>G");
        String id;
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls))) {
            id=jobs.start("analysis",request).path("taskId").asText();
            var done=await(jobs,id);assertEquals("READY",done.path("status").asText(),done.toString());
            assertEquals(coverage,done.at("/result/searchAliasCoverage"));
            assertEquals(id,jobs.start("analysis",request).path("taskId").asText());assertEquals(1,calls.get());
            coverage.put("availableAliases",3);
            assertThrows(java.io.IOException.class,()->jobs.start("analysis",request));
        }
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls))) {
            assertEquals("c.2A>G",jobs.read("analysis",id).at("/result/searchAliasCoverage/deferredAliases/0").asText());
            assertEquals(1,calls.get());
        }
    }
    @Test void changedTargetOutputCannotBecomeReady() throws Exception {
        Path config=setup(); var calls=new AtomicInteger(); var real=runner(config,calls);
        ExternalProcessRunner wrong=(command,cwd,remaining)-> {
            var result=real.run(command,cwd,remaining); Path file=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
            var doc=(ObjectNode)json.readTree(Files.readAllBytes(file)); ((ObjectNode)doc.path("target")).put("geneSymbol","OTHER");
            Files.write(file,json.writeValueAsBytes(doc)); return result;
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),wrong)) {
            var done=await(jobs,jobs.start("analysis",request()).path("taskId").asText());
            assertEquals("FAILED",done.path("status").asText()); assertFalse(done.has("result"));
        }
    }
    @Test void printedNotationAcceptedButDifferentAlleleRejected() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var real=runner(config,calls);
        for(String alias:List.of("c.1A > G","c.1A>C")) {
            ExternalProcessRunner printed=(command,cwd,remaining)->{
                var exit=real.run(command,cwd,remaining);Path file=Path.of(command.get(command.indexOf("--output")+1)).resolve("result.json");
                var doc=(ObjectNode)json.readTree(Files.readAllBytes(file));
                ((ObjectNode)doc.path("target")).putArray("variantAliases").add(alias);
                Files.write(file,json.writeValueAsBytes(doc));return exit;
            };
            // Distinct identity so each mutation actually executes.
            Files.writeString(root.resolve("scripts/denovo/run_pp1_paper_task.py"),"# "+alias);
            try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),printed)) {
                var done=await(jobs,jobs.start("analysis",request()).path("taskId").asText());
                assertEquals(alias.contains(" > ")?"READY":"FAILED",done.path("status").asText());
            }
        }
    }
    @Test void frozenPedigreeOriginalsCapturedAndTamperRejected() throws Exception {
        Path config=setup();var original=runner(config,new AtomicInteger());
        ExternalProcessRunner withImage=(command,cwd,remaining)->{
            var exit=original.run(command,cwd,remaining);
            Path out=Path.of(command.get(command.indexOf("--output")+1));
            var manifest=(ObjectNode)json.readTree(Files.readAllBytes(out.resolve("artifacts.json")));
            var figure=manifest.putArray("pedigreeReviews").addObject();
            for(String key:List.of("source","image","pair")) {
                Path file=out.resolve(key+"-synthetic");byte[] bytes=("synthetic-"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(file,bytes);figure.putObject(key).put("path",file.toString()).put("sha256",FamilyDeNovoJson.sha256(bytes));
            }
            byte[] bytes=json.writeValueAsBytes(manifest);Files.write(out.resolve("artifacts.json"),bytes);
            var result=(ObjectNode)json.readTree(Files.readAllBytes(out.resolve("result.json")));
            result.put("artifactManifestSha256",FamilyDeNovoJson.sha256(bytes));Files.write(out.resolve("result.json"),json.writeValueAsBytes(result));
            return exit;
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),withImage)) {
            String id=jobs.start("analysis",request()).path("taskId").asText();assertEquals("READY",await(jobs,id).path("status").asText());
            var recovered=json.createObjectNode().put("executionSourceRecovered",true);
            recovered.putArray("candidates").addObject().put("candidateId","candidate");
            var snapshot=jobs.capture("analysis",recovered);
            assertEquals(1,snapshot.at("/documents/0/pedigreeReviews").size());
            Path image=root.resolve(snapshot.at("/documents/0/pedigreeReviews/0/image/path").asText());
            Files.writeString(image,"changed");
            assertTrue(assertThrows(java.io.IOException.class,()->jobs.capture("analysis",recovered)).getMessage().contains("PP1_PEDIGREE_CAPTURE_CHANGED"));
        }
    }
    @Test void mutatedSelectionPinRejectedBeforeWorker() throws Exception {
        Path config=setup(); var calls=new AtomicInteger(); var req=request(); req.put("selectionSha256","c".repeat(64));
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),runner(config,calls))) {
            assertThrows(java.io.IOException.class,()->jobs.start("analysis",req)); assertEquals(0,calls.get());
        }
    }
    @Test void cancellationInterruptsOwnedWorkerAndIsDurable() throws Exception {
        Path config=setup(); var begun=new CountDownLatch(1); var stopped=new CountDownLatch(1);
        ExternalProcessRunner blocked=(command,cwd,remaining)->{
            begun.countDown(); try { new CountDownLatch(1).await(); }
            catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
            finally{stopped.countDown();} return new ProcessResult(1,"cancelled",false);
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),blocked)) {
            String id=jobs.start("analysis",request()).path("taskId").asText(); assertTrue(begun.await(3,TimeUnit.SECONDS));
            jobs.cancel("analysis",id); assertTrue(stopped.await(3,TimeUnit.SECONDS));
            // The owned process exits before the queue publishes its terminal
            // receipt; wait for that durable state rather than race the latch.
            assertEquals("CANCELLED",await(jobs,id).path("status").asText());
        }
    }
    @Test void changedDependencyDuringWorkerPreventsPublication() throws Exception {
        Path config=setup(); var original=runner(config,new AtomicInteger());
        ExternalProcessRunner changed=(command,cwd,remaining)->{
            var result=original.run(command,cwd,remaining); Files.writeString(root.resolve("scripts/denovo/run_pp1_paper_task.py"),"# changed"); return result;
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),changed)) {
            var done=await(jobs,jobs.start("analysis",request()).path("taskId").asText()); assertEquals("FAILED",done.path("status").asText());
        }
    }
    @Test void changedPublishedSourceCompilerPreventsPublication() throws Exception {
        Path config=setup(); var c=(ObjectNode)json.readTree(Files.readAllBytes(config));
        c.putObject("pp1Knowledge").put("kind","PUBLISHED_CSPEC");Files.write(config,json.writeValueAsBytes(c));
        for(String relative:Pp1LiteratureJobs.PUBLISHED_SOURCE_DEPENDENCIES) {
            Path p=root.resolve("scripts").resolve(relative);Files.createDirectories(p.getParent());Files.writeString(p,"# synthetic source reader");
        }
        var original=runner(config,new AtomicInteger());
        ExternalProcessRunner changed=(command,cwd,remaining)->{
            var result=original.run(command,cwd,remaining);
            Files.writeString(root.resolve("scripts/pvs1/prepare_applicability_sources.py"),"# changed parser");return result;
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),changed)) {
            var done=await(jobs,jobs.start("analysis",request()).path("taskId").asText());
            assertEquals("FAILED",done.path("status").asText());
        }
    }
    @Test void changedReplayRegistryDuringWorkerPreventsPublication() throws Exception {
        Path config=setup();var original=runner(config,new AtomicInteger());
        ExternalProcessRunner changed=(command,cwd,remaining)->{
            var result=original.run(command,cwd,remaining);
            Files.writeString(root.resolve("scripts/denovo/bundle_replay_profiles.json"),"{\"changed\":true}");return result;
        };
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),changed)) {
            assertEquals("FAILED",await(jobs,jobs.start("analysis",request()).path("taskId").asText()).path("status").asText());
        }
    }
    static final List<String> DISCOVERY_DEPENDENCIES=List.of("discover_public_literature.py", "literature_search_transport.py",
            "literature_allele_identity.py", "prepare_literature.py", "select_public_papers.py", "fetch_public_literature.py", "litvar_literature.py");
    ExternalProcessRunner discoveryRunner(Path config,AtomicInteger calls) throws java.io.IOException {
        for(String name:DISCOVERY_DEPENDENCIES) {
            Path script=root.resolve("scripts/denovo").resolve(name);
            if(!Files.exists(script))Files.writeString(script,"# synthetic discovery implementation "+name);
        }
        return (command,cwd,remaining)-> {
            calls.incrementAndGet();byte[] input=Files.readAllBytes(Path.of(command.get(command.indexOf("--request")+1)));
            var request=json.readTree(input);Path folder=Path.of(command.get(command.indexOf("--output")+1));Files.createDirectories(folder);
            var out=json.createObjectNode().put("schemaVersion","pp1-discovery-process-v1").put("status","EVALUATED")
                    .put("searchPolicy","LITVAR_ONLY").put("businessPublishable",false)
                    .put("requestSha256",FamilyDeNovoJson.sha256(input))
                    .put("workflowConfigSha256",FamilyDeNovoJson.sha256(Files.readAllBytes(config)))
                    .put("analysisBindingSha256",new RuntimeEvidenceV2Adapter(json).canonicalSha256(request.path("analysisBinding")));
            out.set("selectionSha256",request.path("selectionSha256"));out.set("target",request.path("target"));
            if(request.has("searchAliasCoverage"))out.set("searchAliasCoverage",request.path("searchAliasCoverage"));
            var manifest=json.createObjectNode();var entries=manifest.putArray("papers");var papers=out.putArray("papers");
            for(var pmcid:List.of("PMC123","PMC456")) {
                var p=papers.addObject().put("schemaVersion","pp1-paper-process-v1").put("status","EVALUATED")
                        .put("businessPublishable",false).put("pmcid",pmcid).put("bundleSha256","b".repeat(64));p.set("target",request.path("target"));
                var eval=p.putObject("evaluation").put("status","NOT_EVALUATED");
                eval.set("target",request.at("/analysisBinding/selection"));eval.set("ledger",request.at("/analysisBinding/ledger"));
                var entry=entries.addObject().put("pmcid",pmcid).put("bundleSha256","b".repeat(64));
                entry.set("target",request.path("target"));entry.putArray("acceptedArtifacts");
            }
            var stateValue=discoveryState(request);
            byte[] m=json.writeValueAsBytes(manifest),state=json.writeValueAsBytes(stateValue);
            Files.write(folder.resolve("artifacts.json"),m);Files.write(folder.resolve("discovery-state.json"),state);
            out.put("artifactManifestSha256",FamilyDeNovoJson.sha256(m));
            out.putObject("discoveryState").put("resumeAvailable",true).put("sha256",FamilyDeNovoJson.sha256(state))
                    .put("pendingPapers",1).put("pendingQueries",0).put("searchMode","LITVAR_ONLY");
            Files.write(folder.resolve("result.json"),json.writeValueAsBytes(out));return new ProcessResult(0,"",false);
        };
    }
    ObjectNode discoveryState(com.fasterxml.jackson.databind.JsonNode request) throws java.io.IOException {
        var selection=json.createObjectNode();selection.putArray("genes").add(request.at("/target/geneSymbol"));
        var query=((ObjectNode)request.path("target")).deepCopy();
        var allele=query.putObject("allele");allele.set("genomeAssembly",request.at("/analysisBinding/selection/allele/assembly"));
        allele.set("chromosome",request.at("/analysisBinding/selection/allele/contig"));
        for(String key:List.of("position","reference","alternate"))allele.set(key,request.at("/analysisBinding/selection/allele/"+key));
        selection.putArray("targets").add(query);
        var state=json.createObjectNode().put("schemaVersion","denovo-discovery-batch-v1")
                .put("selectionPin",new RuntimeEvidenceV2Adapter(json).canonicalSha256(selection))
                .put("searchMode","LITVAR_ONLY").put("discoveryComplete",true);
        state.putArray("pendingCandidates").addObject().put("pmcid","PMC789");state.putArray("attemptedPaperKeys");
        state.putObject("summary").put("pendingQueries",0);
        var checkpoint=state.putObject("checkpoint").put("searchMode","LITVAR_ONLY");
        checkpoint.putArray("queries");checkpoint.putArray("deferredCandidates");
        var implementation=checkpoint.putObject("implementation");
        for(String name:DISCOVERY_DEPENDENCIES)implementation.put(name,FamilyDeNovoJson.sha256(Files.readAllBytes(root.resolve("scripts/denovo").resolve(name))));
        return state;
    }

    @Test void discoveryCapturesEachPaperAndContinuationIsIdempotent() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var request=request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),discoveryRunner(config,calls))) {
            var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
            String id=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();
            assertEquals("READY",await(jobs,id).path("status").asText());
            var recovered=json.createObjectNode().put("executionSourceRecovered",true);
            recovered.putArray("candidates").addObject().put("candidateId","candidate");
            var capture=jobs.capture("analysis",recovered);assertEquals(2,capture.path("documents").size());
            assertEquals("PMC456",capture.at("/documents/1/request/pmcid").asText());
            String next=jobs.startDiscovery("analysis",binding,target,id).path("taskId").asText();
            assertNotEquals(id,next);assertEquals("READY",await(jobs,next).path("status").asText());
            assertEquals(next,jobs.startDiscovery("analysis",binding,target,id).path("taskId").asText());assertEquals(2,calls.get());
        }
    }
    @Test void discoveryCoverageIsRetainedAcrossPaperCaptureAndContinuation() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var request=request();
        var coverage=json.createObjectNode().put("policy","PP1_MANE_CANONICAL_FIRST_MAX20_V1")
                .put("retrievalOnly",true).put("availableAliases",2).put("selectedAliases",1).put("omittedAliases",1);
        coverage.putArray("deferredAliases").add("c.2A>G");
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),discoveryRunner(config,calls))) {
            var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
            String id=jobs.startDiscovery("analysis",binding,target,null,coverage).path("taskId").asText();
            var done=await(jobs,id);assertEquals("READY",done.path("status").asText(),done.toString());
            assertEquals(coverage,done.at("/result/searchAliasCoverage"));
            var recovered=json.createObjectNode().put("executionSourceRecovered",true);
            recovered.putArray("candidates").addObject().put("candidateId","candidate");
            assertEquals(coverage,jobs.capture("analysis",recovered).at("/documents/0/request/searchAliasCoverage"));
            String next=jobs.startDiscovery("analysis",binding,target,id,coverage).path("taskId").asText();
            assertEquals("READY",await(jobs,next).path("status").asText());
            assertEquals(next,jobs.startDiscovery("analysis",binding,target,id,coverage).path("taskId").asText());
            assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",binding,target,id,null));
            assertEquals(2,calls.get());
        }
    }
    @Test void continuationRejectsChangedContextAndStateBeforeWorker() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var request=request();
        try(var jobs=new Pp1LiteratureJobs(config,new DeNovoSourceTasks(1,2,10000,true),discoveryRunner(config,calls))) {
            var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
            String id=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();await(jobs,id);
            var changed=binding.deepCopy();changed.withObject("/selection").put("diseaseId","MONDO:9999999");
            assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",changed,target,id));
            Files.writeString(root.resolve("local/work/pp1-artifacts").resolve(id).resolve("paper/discovery-state.json"),"{\"changed\":true}");
            assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",binding,target,id));assertEquals(1,calls.get());
        }
    }

    @Test void legacyTaskRemainsReadableButCannotUseNewArtifactLayout() throws Exception {
        Path config=setup();var calls=new AtomicInteger();var request=request();
        var queue=new DeNovoSourceTasks(1,2,10000,true);
        try(var jobs=new Pp1LiteratureJobs(config,queue,discoveryRunner(config,calls))) {
            var binding=(ObjectNode)request.path("analysisBinding");var target=(ObjectNode)request.path("target");
            String current=jobs.startDiscovery("analysis",binding,target,null).path("taskId").asText();await(jobs,current);
            Path directory=root.resolve("local/work/pp1-paper-tasks").resolve(FamilyDeNovoJson.sha256("analysis".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var legacy=(ObjectNode)json.readTree(Files.readAllBytes(directory.resolve(current).resolve("identity.json")));
            legacy.put("version","pp1-paper-job-v1");byte[] identity=json.writeValueAsBytes(legacy);
            byte[] result=queue.read(directory,current).result();assertNotNull(result);
            String old=queue.start(directory,identity,remaining->result).status().path("taskId").asText();
            assertEquals("READY",await(jobs,old).path("status").asText());
            var error=assertThrows(java.io.IOException.class,()->jobs.startDiscovery("analysis",binding,target,old));
            assertEquals("PP1_TASK_VERSION_STALE",error.getMessage());
            var recovered=json.createObjectNode().put("executionSourceRecovered",true);
            recovered.putArray("candidates").addObject().put("candidateId","candidate");
            var snapshot=jobs.capture("analysis",recovered);
            assertEquals(2,snapshot.path("documents").size());
            assertEquals("PP1_TASK_VERSION_STALE",snapshot.at("/excludedTasks/0/reasonCode").asText());
            assertArrayEquals(identity,Files.readAllBytes(directory.resolve(old).resolve("identity.json")));
            assertArrayEquals(result,queue.read(directory,old).result());assertEquals(1,calls.get());
        }
    }
}
