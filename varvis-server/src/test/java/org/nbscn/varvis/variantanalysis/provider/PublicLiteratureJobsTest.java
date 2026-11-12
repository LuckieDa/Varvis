package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PublicLiteratureJobsTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    @Test void transcriptRichTargetIsBoundedWithoutBlockingOtherAlleles() throws Exception {
        var input=selected();var first=(ObjectNode)input.path("candidates").get(0);
        var tx=first.putArray("transcriptAnnotations");
        for(int i=1;i<=40;i++)tx.addObject().put("geneSymbol","GENE1").put("hgvsc","NM_123.1:c."+i+"A>G");
        tx.addObject().put("geneSymbol","GENE1").put("hgvsc","NM_123.1:c.999A>G").put("maneSelect",true);
        try(var jobs=new PublicLiteratureJobs(config(true))) {
            var value=jobs.selectionOptions(input);
            assertEquals(2,value.at("/selection/searchTargets").size());
            assertEquals(64,value.at("/selection/searchTargets/0/variantAliases").size());
            assertEquals("c.999A>G",value.at("/selection/searchTargets/0/variantAliases/0").asText());
            assertEquals(18,value.at("/selection/variantAliasCoverage/one/omittedAliases").asInt());
            assertFalse(value.path("modelEnabled").asBoolean());
        }
    }
    @Test void priorityAndPrefetchAreVisibleAndBoundedWithoutEnablingModel() throws Exception {
        Path path=config(true);
        try(var jobs=new PublicLiteratureJobs(path)) {
            var options=jobs.options();
            assertEquals(20,options.path("maximumPrefetchPapers").asInt());
            assertEquals(5,options.path("maximumPapers").asInt());
            assertEquals(4,options.path("maximumModelCalls").asInt());
            assertFalse(options.path("modelEnabled").asBoolean());
            var c=(ObjectNode)json.readTree(path.toFile());c.putObject("readingPriority").put("maximumPrefetch",51);
            json.writeValue(path.toFile(),c);
            assertThrows(java.io.IOException.class,jobs::options);
            c.putObject("readingPriority").put("enabled",false);json.writeValue(path.toFile(),c);
            assertEquals(2,jobs.options().path("maximumPapers").asInt());
        }
    }
    ObjectNode selected() {
        var x=json.createObjectNode().put("executionSourceRecovered",true).put("requestedN",2);
        var a=x.putArray("candidates");
        a.addObject().put("candidateId","one").put("rank",1).put("geneSymbol","GENE1").put("sampleId","PRIVATE_MUST_NOT_LEAVE");
        a.addObject().put("candidateId","two").put("rank",2).put("geneSymbol","GENE2");return x;
    }
    Path config(boolean enabled) throws Exception {
        Files.createDirectories(root.resolve("scripts/denovo"));Files.writeString(root.resolve("scripts/denovo/public_literature_workflow.py"),"# synthetic");
        Files.createDirectories(root.resolve("local/library/evidence/PS2"));Files.writeString(root.resolve("local/library/evidence/PS2/index.json"),"{}");
        Files.createDirectories(root.resolve("local/work"));
        var c=json.createObjectNode().put("schemaVersion",PublicLiteratureJobs.VERSION).put("projectRoot",root.toString())
            .put("enabled",enabled).put("allowPublicNetwork",false).put("allowModel",false)
            .put("work","local/work").put("python","local/python.exe").put("library","local/library");
        Path path=root.resolve("config.json");json.writeValue(path.toFile(),c);return path;
    }
    @Test void duplicateSubmissionReadAndRestartNeverExecuteAgain() throws Exception {
        var calls=new AtomicInteger();Path c=config(true);String id;
        ExternalProcessRunner runner=(command,cwd,timeout)->{
            calls.incrementAndGet();
            Path input=Path.of(command.get(command.indexOf("--request")+1));
            var request=json.readTree(input.toFile());
            assertEquals("LITVAR_ONLY",request.path("searchMode").asText());assertEquals(5,request.size());assertFalse(request.toString().contains("PRIVATE_MUST_NOT_LEAVE"));
            var out=json.createObjectNode().put("schemaVersion",PublicLiteratureJobs.VERSION)
                .put("selectionSha256",request.path("selectionSha256").asText()).put("businessPublishable",false)
                .put("scientificExecution",false).put("status","PARTIAL").put("modelCalls",1);
            json.writeValue(Path.of(command.get(command.indexOf("--output")+1)).toFile(),out);
            return new ProcessResult(0,"synthetic",false);
        };
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),runner)) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            id=jobs.start("analysis",selected(),pin).path("taskId").asText();
            for(int i=0;i<200&&!jobs.read("analysis",id).has("result");i++) Thread.sleep(10);
            var value=jobs.read("analysis",id);
            assertEquals("READY",value.path("status").asText());assertTrue(value.path("modelCalls").isNull());
            assertEquals(1,value.path("result").path("modelCalls").asInt());
            assertEquals(id,jobs.start("analysis",selected(),pin).path("taskId").asText());
            assertEquals(1,calls.get());assertFalse(value.path("canRetry").asBoolean());
            Path task=root.resolve("local/work/tasks")
                .resolve(FamilyDeNovoJson.sha256("analysis".getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve(id);
            var execution=json.readTree(task.resolve("execution-location.json").toFile());
            assertEquals(id,execution.path("identitySha256").asText());
            Path executionPath=root.resolve(execution.path("path").asText()).normalize();
            assertTrue(executionPath.startsWith(root.resolve("local/work")));
            assertTrue(Files.isRegularFile(executionPath.resolve("request.json")));
        }
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),(cmd,cwd,t)->{fail("restart executed");return null;})) {
            assertEquals("READY",jobs.read("analysis",id).path("status").asText());
            assertEquals(1,jobs.list("analysis").size());assertTrue(jobs.list("other-analysis").isEmpty());
        }
    }
    @Test void retiredDiscoveryModesCannotStart() throws Exception {
        try(var jobs=new PublicLiteratureJobs(config(true),new DeNovoSourceTasks(1,1,5000,true),
                (cmd,cwd,t)->{fail("retired mode executed");return null;})) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            for(String mode : new String[]{"ORIGINAL","COMBINED"})
                assertThrows(java.io.IOException.class,()->jobs.start("analysis",selected(),pin,mode));
            assertTrue(jobs.list("analysis").isEmpty());
        }
    }
    @Test void missingFormalSourceAndStaleIndexCannotLaunch() throws Exception {
        Path c=config(true);
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),(cmd,cwd,t)->{fail();return null;})) {
            var s=selected();String pin=jobs.selectionOptions(s).path("selectionSha256").asText();
            Files.writeString(root.resolve("local/library/evidence/PS2/index.json"),"{\"changed\":true}");
            assertThrows(java.io.IOException.class,()->jobs.start("analysis",s,pin));
            s.put("executionSourceRecovered",false);
            assertThrows(java.io.IOException.class,()->jobs.selectionOptions(s));
        }
    }
    @Test void disabledDoesNotCreateJobs() throws Exception {
        try(var jobs=new PublicLiteratureJobs(config(false))) {
            assertFalse(jobs.options().path("enabled").asBoolean());
            assertThrows(java.io.IOException.class,()->jobs.start("analysis",selected(),"a".repeat(64)));
            assertTrue(jobs.list("analysis").isEmpty());
        }
    }
    @Test void selectedVariantTermsAreBoundAndPrivateFieldsAreNeverProjected() throws Exception {
        Path c=config(true);var selected=selected();
        var first=(ObjectNode)selected.path("candidates").get(0);
        first.putObject("allele").put("genomeAssembly","GRCh38").put("chromosome","3").put("position",123)
            .put("reference","G").put("alternate","A").put("sampleId","PRIVATE_MUST_NOT_LEAVE");
        var tx=first.putArray("transcriptAnnotations");
        tx.addObject().put("geneSymbol","GENE1").put("hgvsc","NM_123.1:c.123G>A").put("hgvsp","NP_123.1:p.Arg41Lys")
            .put("patientName","PRIVATE_MUST_NOT_LEAVE");
        tx.addObject().put("geneSymbol","OTHER").put("hgvsc","NM_456.1:c.999G>A");
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),(cmd,cwd,t)->{fail();return null;})) {
            var options=jobs.selectionOptions(selected);
            var signals=options.at("/selection/searchTargets");
            assertEquals("LITVAR_ONLY",options.path("searchPolicy").asText());
            assertTrue(signals.toString().contains("NM_123.1:c.123G>A"));
            assertTrue(signals.toString().contains("p.Arg41Lys"));
            assertFalse(signals.toString().contains("999G>A"));
            assertFalse(signals.toString().contains("PRIVATE_MUST_NOT_LEAVE"));
            String pin=options.path("selectionSha256").asText();
            ((ObjectNode)tx.get(0)).put("hgvsc","NM_123.1:c.124G>A");
            assertThrows(java.io.IOException.class,()->jobs.start("analysis",selected,pin));
        }
    }
    @Test void changedPinnedClinvarSourceRevokesPreparedSearch() throws Exception {
        Path c=config(true);
        Path source=root.resolve("local/clinvar.xml");Files.writeString(source,"synthetic raw");
        var context=json.createObjectNode().put("schemaVersion","denovo-core-context-config-v1");
        context.putObject("identity").putArray("clinvar").addObject().put("path","local/clinvar.xml")
            .put("sha256",FamilyDeNovoJson.sha256(Files.readAllBytes(source))).put("accession","VCV000000001");
        Path contextPath=root.resolve("local/context.json");json.writeValue(contextPath.toFile(),context);
        var config=(ObjectNode)json.readTree(c.toFile());
        config.putObject("coreContext").put("path","local/context.json").put("sha256",FamilyDeNovoJson.sha256(Files.readAllBytes(contextPath)));
        json.writeValue(c.toFile(),config);
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),(cmd,cwd,t)->{fail();return null;})) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            Files.writeString(source,"changed");
            assertThrows(java.io.IOException.class,()->jobs.start("analysis",selected(),pin));
        }
    }
    @Test void paidFailureHasUnknownCountAndCannotAutomaticallyRetry() throws Exception {
        Path c=config(true);
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),(cmd,cwd,t)->{throw new java.io.IOException("unknown charge");})) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            String id=jobs.start("a",selected(),pin).path("taskId").asText();
            for(int i=0;i<200&&!jobs.read("a",id).path("status").asText().equals("FAILED");i++) Thread.sleep(10);
            var result=jobs.read("a",id);assertEquals("FAILED",result.path("status").asText());
            assertTrue(result.path("modelCalls").isNull());assertFalse(result.path("canRetry").asBoolean());
        }
    }
    private ObjectNode ready(PublicLiteratureJobs jobs,String id) throws Exception {
        for(int i=0;i<300&&!jobs.read("analysis",id).has("result");i++) Thread.sleep(10);
        var result=jobs.read("analysis",id);assertEquals("READY",result.path("status").asText(),result.toString());return result;
    }
    @Test void discoveryModeAndContinuationAreBoundDurableAndIdempotent() throws Exception {
        Path c=config(true);var executions=new AtomicInteger();String parent,child;
        ExternalProcessRunner runner=(command,cwd,timeout)->{
            executions.incrementAndGet();
            var request=json.readTree(Path.of(command.get(command.indexOf("--request")+1)).toFile());
            assertEquals("LITVAR_ONLY",request.path("searchMode").asText());
            if(request.has("discoveryContinuation")) {
                var resume=request.path("discoveryContinuation");
                byte[] state=Files.readAllBytes(root.resolve(resume.path("statePath").asText()));
                assertEquals(resume.path("stateSha256").asText(),FamilyDeNovoJson.sha256(state));
            }
            Path output=Path.of(command.get(command.indexOf("--output")+1));
            Path pipeline=output.getParent().resolve("pipeline");Files.createDirectories(pipeline);
            byte[] state="{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(pipeline.resolve("discovery-state.json"),state);
            var result=json.createObjectNode().put("schemaVersion",PublicLiteratureJobs.VERSION)
                .put("selectionSha256",request.path("selectionSha256").asText()).put("businessPublishable",false)
                .put("scientificExecution",false).put("modelCalls",0);
            result.putObject("discoveryState").put("path","discovery-state.json")
                .put("sha256",FamilyDeNovoJson.sha256(state)).put("searchMode","LITVAR_ONLY")
                .put("resumeAvailable",!request.has("discoveryContinuation"));
            result.putArray("papers");json.writeValue(output.toFile(),result);
            return new ProcessResult(0,"synthetic checkpoint",false);
        };
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),runner)) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            assertThrows(java.io.IOException.class,()->jobs.start("analysis",selected(),pin,"INVALID"));
            parent=jobs.start("analysis",selected(),pin,"LITVAR_ONLY").path("taskId").asText();ready(jobs,parent);
            child=jobs.continueDiscovery("analysis",selected(),pin,parent).path("taskId").asText();ready(jobs,child);
            assertEquals(2,executions.get());
            assertEquals(child,jobs.continueDiscovery("analysis",selected(),pin,parent).path("taskId").asText());
            assertEquals(2,executions.get());
            assertThrows(java.io.IOException.class,()->jobs.continueDiscovery("analysis",selected(),pin,child));
            assertThrows(java.io.IOException.class,()->jobs.continueDiscovery("other",selected(),pin,parent));
        }
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),(cmd,cwd,t)->{fail("must reuse");return null;})) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            assertEquals(child,jobs.continueDiscovery("analysis",selected(),pin,parent).path("taskId").asText());
            Path state=root.resolve("local/work/tasks").resolve(FamilyDeNovoJson.sha256("analysis".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .resolve(parent).resolve("discovery-state.json");
            Files.writeString(state,"changed");
            assertThrows(java.io.IOException.class,()->jobs.continueDiscovery("analysis",selected(),pin,parent));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"targets","review","need-reading"})
    void fixedPaperContinuationIsIdempotentAndSurvivesRestart(String mode) throws Exception {
        Path c=config(true);var executions=new AtomicInteger();String child,parent;
        ExternalProcessRunner runner=(command,cwd,timeout)->{
            executions.incrementAndGet();var request=json.readTree(Path.of(command.get(command.indexOf("--request")+1)).toFile());
            boolean resume=request.has("continuation");
            if(resume) {
                assertEquals("PMC123",request.at("/continuation/pmcid").asText());
                assertEquals(1,request.at("/continuation/completedTargets").size());
                assertFalse(request.toString().contains("PRIVATE_MUST_NOT_LEAVE"));
            }
            var output=json.createObjectNode().put("schemaVersion",PublicLiteratureJobs.VERSION).put("selectionSha256",request.path("selectionSha256").asText())
                    .put("businessPublishable",false).put("scientificExecution",false).put("status","PARTIAL").put("modelCalls",0);
            output.putArray("issues").add("CANDIDATE_REVIEW_NOT_SCIENTIFIC_ADOPTION");
            var paper=output.putArray("papers").addObject().put("pmcid","PMC123").put("bundleSha256","a".repeat(64)).put("plannedChunks",3);
            paper.putArray("chunks").addObject().put("chunkIndex",0).put("artifactSha256","b".repeat(64));
            if(mode.equals("review")) paper.putObject("sourceReview").putArray("pendingTargets").addObject().put("chunkIndex",0).put("reasonCode","SOURCE_REVIEW_BATCH_LIMIT");
            else paper.putArray("unprocessedChunks").addObject().put("chunkIndex",1).put("reasonCode","MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS");
            paper.putObject("continuation").put("eligible",!resume);
            if(mode.equals("need-reading")) {
                paper.putObject("needReading").put("status",resume?"MATERIAL_READY":"PARTIAL");
                ((ObjectNode)paper.path("continuation")).put("reasonCode",resume?"READY_FOR_ORIGINAL_EVALUATION":"READING_CHUNK_LIMIT");
                paper.putArray("unprocessedChunks");
            }
            json.writeValue(Path.of(command.get(command.indexOf("--output")+1)).toFile(),output);
            return new ProcessResult(0,"synthetic continuation",false);
        };
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),runner)) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            parent=jobs.start("analysis",selected(),pin).path("taskId").asText();ready(jobs,parent);
            child=jobs.continuePaper("analysis",selected(),pin,parent,"PMC123").path("taskId").asText();ready(jobs,child);
            assertNotEquals(parent,child);assertEquals(2,executions.get());
            assertEquals(child,jobs.continuePaper("analysis",selected(),pin,parent,"PMC123").path("taskId").asText());
            assertEquals(2,executions.get());
            assertThrows(java.io.IOException.class,()->jobs.continuePaper("analysis",selected(),pin,child,"PMC123"));
            assertThrows(java.io.IOException.class,()->jobs.continuePaper("other",selected(),pin,parent,"PMC123"));
            assertThrows(java.io.IOException.class,()->jobs.continuePaper("analysis",selected(),pin,parent,"PMC999"));
            var changed=selected();((ObjectNode)changed.path("candidates").get(0)).put("geneSymbol","CHANGED");
            String changedPin=jobs.selectionOptions(changed).path("selectionSha256").asText();
            assertThrows(java.io.IOException.class,()->jobs.continuePaper("analysis",changed,changedPin,parent,"PMC123"));
        }
        try(var jobs=new PublicLiteratureJobs(c,new DeNovoSourceTasks(1,1,5000,true),(cmd,cwd,t)->{fail("restarted continuation");return null;})) {
            String pin=jobs.selectionOptions(selected()).path("selectionSha256").asText();
            assertEquals(child,jobs.continuePaper("analysis",selected(),pin,parent,"PMC123").path("taskId").asText());
            assertEquals("READY",jobs.read("analysis",child).path("status").asText());
        }
    }
}
