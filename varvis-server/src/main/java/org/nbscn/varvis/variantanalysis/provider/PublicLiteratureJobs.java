package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** A separate, explicit public-source workflow. Never calls evidence evaluators or writes ACMG runs. */
public final class PublicLiteratureJobs implements AutoCloseable {
    public static final String VERSION = "denovo-public-literature-workflow-v1";
    private final ObjectMapper json = new ObjectMapper();
    private final Path configuration;
    private final DeNovoSourceTasks tasks;
    private final ExternalProcessRunner runner;
    public PublicLiteratureJobs(Path configuration) {
        this(configuration,new DeNovoSourceTasks(1, 2, 540000, true),new DeNovoSourceProcessRunner(Duration.ofSeconds(540)));
    }
    PublicLiteratureJobs(Path configuration, DeNovoSourceTasks tasks, ExternalProcessRunner runner) {
        this.configuration=configuration.toAbsolutePath().normalize(); this.tasks=tasks; this.runner=runner;
    }
    private ObjectNode configuration() throws IOException {
        if (!Files.isRegularFile(configuration)) return json.createObjectNode().put("enabled",false);
        var value=(ObjectNode)json.readTree(read(configuration,16384));
        if (!VERSION.equals(value.path("schemaVersion").asText())) throw new IOException("LITERATURE_CONFIG_INVALID");
        return value;
    }
    public ObjectNode options() throws IOException {
        var c=configuration();
        int readingCalls=c.path("maximumReadingCalls").asInt(8);
        if(c.path("needDrivenReading").asBoolean() && (readingCalls<2 || readingCalls>32))
            throw new IOException("LITERATURE_READING_LIMIT_INVALID");
        var priority=c.path("readingPriority");
        boolean rankEnabled=priority.path("enabled").asBoolean(true);
        int prefetch=rankEnabled?priority.path("maximumPrefetch").asInt(20):2;
        int deep=rankEnabled?priority.path("maximumDeepRead").asInt(5):2;
        int rankCalls=rankEnabled?priority.path("maximumCalls").asInt(2):0;
        if(prefetch<1 || prefetch>50 || deep<1 || deep>20 || deep>prefetch || rankCalls<0 || rankCalls>8)
            throw new IOException("LITERATURE_PRIORITY_LIMIT_INVALID");
        return json.createObjectNode().put("schemaVersion",VERSION).put("enabled",c.path("enabled").asBoolean())
            .put("publicNetworkEnabled",c.path("allowPublicNetwork").asBoolean())
            .put("modelEnabled",c.path("allowModel").asBoolean()).put("maximumPapers",deep)
            .put("maximumPrefetchPapers",prefetch).put("readingPriorityEnabled",rankEnabled)
            .put("maximumPriorityCalls",rankCalls)
            .put("maximumModelChunks",(c.path("needDrivenReading").asBoolean()?readingCalls:2)+rankCalls)
            .put("needDrivenReading",c.path("needDrivenReading").asBoolean())
            .put("maximumModelCalls",(c.path("needDrivenReading").asBoolean()?readingCalls:2)+rankCalls)
            .put("candidateAcceptanceEnabled",c.hasNonNull("candidateRepository"))
            .put("budgetSecondsIncludingQueue",540).put("scientificExecution",false)
            .put("maximumSearchPages",20).put("searchPolicy","LITVAR_ONLY")
            .put("meaning","SELECTED_VARIANT_SEARCH_PUBLIC_PAPERS_CANDIDATE_REVIEW_NOT_ADOPTION");
    }
    private Path local(ObjectNode c,String field) throws IOException {
        Path root=Path.of(c.path("projectRoot").asText()).toAbsolutePath().normalize();
        Path value=root.resolve(c.path(field).asText()).normalize();
        if (!value.startsWith(root.resolve("local")) || value.equals(root.resolve("local"))) throw new IOException("LITERATURE_CONFIG_PATH_INVALID");
        return value;
    }
    private Path taskRoot(ObjectNode c,String analysis) throws IOException {
        return local(c,"work").resolve("tasks").resolve(FamilyDeNovoJson.sha256(analysis.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public ObjectNode start(String analysis,ObjectNode selected,String expectedSelection) throws IOException {
        return start(analysis,selected,expectedSelection,"LITVAR_ONLY");
    }
    public ObjectNode start(String analysis,ObjectNode selected,String expectedSelection,String searchMode) throws IOException {
        return start(analysis,selected,expectedSelection,searchMode,Set.of());
    }
    public ObjectNode start(String analysis,ObjectNode selected,String expectedSelection,String searchMode,Set<String> candidateIds) throws IOException {
        if(searchMode==null || !Set.of("LITVAR_ONLY").contains(searchMode))
            throw new IOException("LITERATURE_SEARCH_MODE_RETIRED_USE_LITVAR_ONLY");
        return start(analysis,selected,expectedSelection,null,null,searchMode,candidateIds);
    }
    public ObjectNode continuePaper(String analysis,ObjectNode selected,String expectedSelection,String parent,String pmcid) throws IOException {
        if(parent==null || !parent.matches("[a-f0-9]{64}") || pmcid==null || !pmcid.matches("PMC[1-9][0-9]*"))
            throw new IOException("LITERATURE_CONTINUATION_ID_INVALID");
        return start(analysis,selected,expectedSelection,parent,pmcid,"LITVAR_ONLY",Set.of());
    }
    public ObjectNode continueDiscovery(String analysis,ObjectNode selected,String expectedSelection,String parent) throws IOException {
        if(parent==null || !parent.matches("[a-f0-9]{64}")) throw new IOException("LITERATURE_CONTINUATION_ID_INVALID");
        var previous=discoveryContinuation(configuration(),analysis,selected,parent);
        return start(analysis,selected,expectedSelection,parent,null,previous.path("searchMode").asText(),Set.of());
    }
    private ObjectNode discoveryContinuation(ObjectNode c,String analysis,ObjectNode selected,String parent) throws IOException {
        var stored=tasks.read(taskRoot(c,analysis),parent);byte[] raw=stored.result();
        if(!"READY".equals(stored.status().path("status").asText()) || raw==null)
            throw new IOException("LITERATURE_CONTINUATION_PARENT_NOT_READY");
        requireSameSelection(c,analysis,selected,parent);
        var state=json.readTree(raw).path("discoveryState");
        if(!state.path("resumeAvailable").asBoolean() || !state.path("sha256").asText().matches("[a-f0-9]{64}"))
            throw new IOException("LITERATURE_DISCOVERY_CONTINUATION_NOT_AVAILABLE");
        Path file=taskRoot(c,analysis).resolve(parent).resolve("discovery-state.json");
        byte[] bytes=read(file,67108864);
        if(!FamilyDeNovoJson.sha256(bytes).equals(state.path("sha256").asText()))
            throw new IOException("LITERATURE_DISCOVERY_STATE_PIN_MISMATCH");
        String mode=state.path("searchMode").asText();
        if(!Set.of("LITVAR_ONLY").contains(mode)) throw new IOException("LITERATURE_SEARCH_MODE_RETIRED_USE_LITVAR_ONLY");
        Path root=Path.of(c.path("projectRoot").asText()).toAbsolutePath().normalize();
        return json.createObjectNode().put("parentTaskId",parent).put("parentResultSha256",FamilyDeNovoJson.sha256(raw))
                .put("stateSha256",state.path("sha256").asText()).put("statePath",root.relativize(file).toString())
                .put("searchMode",mode);
    }
    /** Explicit source intake, without model calls, scoring or scientific adoption. */
    public ObjectNode acceptPaper(String analysis,ObjectNode selected,String expectedSelection,String parent,String pmcid) throws IOException {
        var c=configuration();
        if(!c.path("enabled").asBoolean() || !c.hasNonNull("candidateRepository")) throw new IOException("LITERATURE_CANDIDATE_ACCEPTANCE_DISABLED");
        if(parent==null||!parent.matches("[a-f0-9]{64}")||pmcid==null||!pmcid.matches("PMC[1-9][0-9]*")) throw new IOException("LITERATURE_CONTINUATION_ID_INVALID");
        if(!expectedSelection.equals(FamilyDeNovoJson.sha256(json.writeValueAsBytes(boundSelection(selected,c))))) throw new IOException("LITERATURE_SELECTION_CHANGED_REFRESH_REQUIRED");
        var stored=tasks.read(taskRoot(c,analysis),parent);byte[] raw=stored.result();
        if(!"READY".equals(stored.status().path("status").asText())||raw==null) throw new IOException("LITERATURE_CONTINUATION_PARENT_NOT_READY");
        requireSameSelection(c,analysis,selected,parent);
        var result=json.readTree(raw);JsonNode paper=null;
        for(var entry:result.path("papers")) if(pmcid.equals(entry.path("pmcid").asText())) {
            if(paper!=null) throw new IOException("LITERATURE_CONTINUATION_PAPER_AMBIGUOUS");paper=entry;
        }
        if(paper==null||!paper.path("candidateIntake").path("sha256").asText().matches("[a-f0-9]{64}")) throw new IOException("LITERATURE_CANDIDATE_INTAKE_UNAVAILABLE");
        String filename=pmcid+"-candidate-intake.json";
        byte[] intake=read(taskRoot(c,analysis).resolve(parent).resolve("intakes").resolve(filename),16777216);
        if(!FamilyDeNovoJson.sha256(intake).equals(paper.path("candidateIntake").path("sha256").asText())) throw new IOException("LITERATURE_INTAKE_PIN_MISMATCH");
        Path work=Files.createTempDirectory(local(c,"work"),"source-acceptance-");
        Path input=work.resolve("intake.json"),output=work.resolve("check.json");Files.write(input,intake,StandardOpenOption.CREATE_NEW);
        Path project=Path.of(c.path("projectRoot").asText()).toAbsolutePath().normalize();
        var process=runner.run(List.of(local(c,"python").toString(),"-E","-s","-B",project.resolve("scripts/denovo/check_literature_intake.py").toString(),
                "--intake",input.toString(),"--output",output.toString()),project,Duration.ofSeconds(60));
        if(!process.isSuccess()) throw new IOException(process.isTimedOut()?"LITERATURE_CANDIDATE_CHECK_TIMEOUT":"LITERATURE_CANDIDATE_CHECK_FAILED");
        if(!c.equals(configuration()) || !expectedSelection.equals(FamilyDeNovoJson.sha256(json.writeValueAsBytes(boundSelection(selected,c)))))
            throw new IOException("LITERATURE_SELECTION_CHANGED_REFRESH_REQUIRED");
        return new DeNovoCandidateRepository(local(c,"candidateRepository")).accept(intake,(ObjectNode)json.readTree(read(output,65536)),parent);
    }
    private void requireSameSelection(ObjectNode c,String analysis,ObjectNode selected,String parent) throws IOException {
        var old=json.readTree(read(taskRoot(c,analysis).resolve(parent).resolve("identity.json"),DeNovoSourceTasks.MAX_IDENTITY));
        var oldSelection=json.createObjectNode();oldSelection.set("requestedN",old.path("selection").path("requestedN"));
        oldSelection.set("candidates",old.path("selection").path("candidates"));
        if(!analysis.equals(old.path("analysisId").asText())||!selection(selected).equals(oldSelection)) throw new IOException("LITERATURE_CONTINUATION_SELECTION_CHANGED");
        if(old.path("selection").has("searchTargets") && !searchTargets(selected).equals(old.path("selection").path("searchTargets")))
            throw new IOException("LITERATURE_CONTINUATION_SELECTION_CHANGED");
    }
    private ObjectNode continuation(ObjectNode c,String analysis,ObjectNode selected,String parent,String pmcid) throws IOException {
        var stored=tasks.read(taskRoot(c,analysis),parent);
        byte[] raw=stored.result();
        if(!"READY".equals(stored.status().path("status").asText()) || raw==null)
            throw new IOException("LITERATURE_CONTINUATION_PARENT_NOT_READY");
        requireSameSelection(c,analysis,selected,parent);
        var previous=json.readTree(raw); JsonNode paper=null;
        for(var row:previous.path("papers")) if(pmcid.equals(row.path("pmcid").asText())) {
            if(paper!=null) throw new IOException("LITERATURE_CONTINUATION_PAPER_AMBIGUOUS");
            paper=row;
        }
        if(paper==null || !paper.path("bundleSha256").asText().matches("[a-f0-9]{64}")
                || !paper.path("plannedChunks").canConvertToInt() || paper.path("plannedChunks").asInt()<1
                || !paper.path("chunks").isArray() || paper.path("chunks").size()>64)
            throw new IOException("LITERATURE_CONTINUATION_PAPER_INVALID");
        boolean pending=false;
        for(var row:paper.path("unprocessedChunks")) pending|=Set.of("MODEL_DISABLED_NO_PAID_CALL","MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS")
                .contains(row.path("reasonCode").asText());
        for(var row:paper.path("sourceReview").path("pendingTargets"))
            pending|="SOURCE_REVIEW_BATCH_LIMIT".equals(row.path("reasonCode").asText());
        if(paper.has("needReading")) {
            pending=paper.path("continuation").path("eligible").asBoolean()
                && Set.of("READING_CHUNK_LIMIT","DENOVO_BUNDLE_READING_CALL_LIMIT",
                    "DENOVO_BUNDLE_READING_TIME_LIMIT","DENOVO_BUNDLE_READING_MODEL_DISABLED")
                    .contains(paper.path("continuation").path("reasonCode").asText());
        }
        if(!pending || paper.has("continuation") && !paper.path("continuation").path("eligible").asBoolean())
            throw new IOException("LITERATURE_CONTINUATION_NOT_AVAILABLE");
        for(var issue:previous.path("issues")) if(!"CANDIDATE_REVIEW_NOT_SCIENTIFIC_ADOPTION".equals(issue.asText()))
            throw new IOException("LITERATURE_CONTINUATION_FAILURE_REQUIRES_REVIEW");
        var result=json.createObjectNode().put("parentTaskId",parent).put("parentResultSha256",FamilyDeNovoJson.sha256(raw))
                .put("pmcid",pmcid).put("bundleSha256",paper.path("bundleSha256").asText()).put("plannedChunks",paper.path("plannedChunks").asInt());
        var completed=result.putArray("completedTargets"); var seen=new HashSet<Integer>();
        for(var chunk:paper.path("chunks")) {
            int index=chunk.path("chunkIndex").asInt(-1);
            if(index<0 || index>=paper.path("plannedChunks").asInt() || !seen.add(index)
                    || !chunk.path("artifactSha256").asText().matches("[a-f0-9]{64}"))
                throw new IOException("LITERATURE_CONTINUATION_CHECKPOINT_INVALID");
            completed.addObject().put("chunkIndex",index).put("artifactSha256",chunk.path("artifactSha256").asText());
        }
        return result;
    }
    private ObjectNode start(String analysis,ObjectNode selected,String expectedSelection,String parent,String pmcid,String searchMode,
            Set<String> candidateIds) throws IOException {
        var c=configuration();
        if(!c.path("enabled").asBoolean()) throw new IOException("LITERATURE_WORKFLOW_DISABLED");
        ObjectNode selection=boundSelection(selected,c);
        String pin=FamilyDeNovoJson.sha256(json.writeValueAsBytes(selection));
        if(!pin.equals(expectedSelection)) throw new IOException("LITERATURE_SELECTION_CHANGED_REFRESH_REQUIRED");
        var request=json.createObjectNode().put("schemaVersion",VERSION).put("selectionSha256",pin);
        request.put("searchMode",searchMode);
        var discoveryIds=new LinkedHashSet<>(candidateIds);
        if(parent!=null) {
            if(!parent.matches("[a-f0-9]{64}")) throw new IOException("LITERATURE_CONTINUATION_ID_INVALID");
            var parentIdentity=json.readTree(read(taskRoot(c,analysis).resolve(parent).resolve("identity.json"),DeNovoSourceTasks.MAX_IDENTITY));
            for(var id:parentIdentity.path("discoveryCandidateIds")) discoveryIds.add(id.asText());
        }
        var discovery=selected.deepCopy();
        if(!discoveryIds.isEmpty()) {
            var rows=discovery.putArray("candidates");
            for(var candidate:selected.path("candidates"))
                if(discoveryIds.contains(candidate.path("candidateId").asText())) rows.add(candidate);
            if(rows.size()!=discoveryIds.size()) throw new IOException("LITERATURE_DISCOVERY_SCOPE_INVALID");
        }
        var targets=searchTargets(discovery);
        var genes=request.putArray("genes"); var unique=new LinkedHashSet<String>();
        for(var row:targets) {
            String gene=row.path("geneSymbol").asText("");
            if(gene.matches("[A-Za-z][A-Za-z0-9.-]{0,39}")) unique.add(gene);
        }
        if(unique.isEmpty()) throw new IOException("LITERATURE_SELECTED_GENE_MISSING");
        unique.forEach(genes::add);
        request.set("searchTargets",targets);
        if(parent!=null) {
            if(pmcid!=null) request.set("continuation",continuation(c,analysis,selected,parent,pmcid));
            else {
                var state=discoveryContinuation(c,analysis,selected,parent);state.remove("searchMode");
                request.set("discoveryContinuation",state);
            }
        }
        Path project=Path.of(c.path("projectRoot").asText()).toAbsolutePath().normalize();
        Path scripts=project.resolve("scripts/denovo");
        var implementations=json.createObjectNode();
        // Capture all small implementation files: task replay never uses changed worker code.
        try(var files=Files.list(scripts)) {
            for(Path path:files.filter(p->p.getFileName().toString().endsWith(".py") && !p.getFileName().toString().startsWith("test_")).sorted().toList())
                implementations.put(path.getFileName().toString(),FamilyDeNovoJson.sha256(read(path,524288)));
        }
        var identity=json.createObjectNode().put("version",VERSION).put("analysisId",analysis);
        if(!discoveryIds.isEmpty()) {
            var ids=identity.putArray("discoveryCandidateIds");
            for(var candidate:discovery.path("candidates")) ids.add(candidate.path("candidateId").asText());
        }
        identity.set("selection",selection);identity.set("request",request);identity.set("configuration",c);
        identity.set("implementation",implementations);
        // Index changes create a new explicitly submitted job, never an automatic retry.
        Path index=local(c,"library").resolve("evidence/PS2/index.json");
        identity.put("sourceIndexSha256",FamilyDeNovoJson.sha256(read(index,1048576)));
        byte[] bytes=json.writeValueAsBytes(identity), input=json.writeValueAsBytes(request);
        Path root=taskRoot(c,analysis);
        var result=tasks.start(root,bytes,remaining->{
            for(var it=implementations.properties().iterator();it.hasNext();) {
                var entry=it.next();
                if(!entry.getValue().asText().equals(FamilyDeNovoJson.sha256(read(scripts.resolve(entry.getKey()),524288))))
                    throw new DeNovoSourceTasks.SourceFailure("LITERATURE_IMPLEMENTATION_CHANGED");
            }
            if(!c.equals(configuration()) || !selection.equals(boundSelection(selected,c)))
                throw new DeNovoSourceTasks.SourceFailure("LITERATURE_CONFIGURATION_OR_SOURCE_CHANGED");
            if(parent!=null && pmcid!=null && !request.path("continuation").equals(continuation(c,analysis,selected,parent,pmcid)))
                throw new DeNovoSourceTasks.SourceFailure("LITERATURE_CONTINUATION_PARENT_CHANGED");
            if(parent!=null && pmcid==null) {
                var state=discoveryContinuation(c,analysis,selected,parent);state.remove("searchMode");
                if(!request.path("discoveryContinuation").equals(state))
                    throw new DeNovoSourceTasks.SourceFailure("LITERATURE_CONTINUATION_PARENT_CHANGED");
            }
            Path directory=Files.createTempDirectory(local(c,"work"),"execution-");
            // Keep the short execution directory addressable after cancellation
            // or restart, including failed model artifacts not in the intake.
            Path taskDirectory=root.resolve(FamilyDeNovoJson.sha256(bytes));
            var execution=json.createObjectNode().put("schemaVersion","denovo-execution-location-v1")
                    .put("path",project.relativize(directory).toString())
                    .put("selectionSha256",pin).put("identitySha256",FamilyDeNovoJson.sha256(bytes));
            Files.write(taskDirectory.resolve("execution-location.json"),json.writeValueAsBytes(execution),StandardOpenOption.CREATE_NEW);
            Path in=directory.resolve("request.json"),out=directory.resolve("result.json");
            Files.write(in,input,StandardOpenOption.CREATE_NEW);
            var command=List.of(local(c,"python").toString(),"-B",scripts.resolve("public_literature_workflow.py").toString(),
                "--request",in.toString(),"--output",out.toString(),"--config",configuration.toString(),
                "--remaining-seconds",Long.toString(Math.max(1,remaining.toSeconds()-2)));
            var process=runner.run(command,project,remaining);
            if(process.isTimedOut()) throw new DeNovoSourceTasks.SourceFailure("LITERATURE_WORKFLOW_TIMEOUT");
            if(process.getExitCode()!=0) throw new DeNovoSourceTasks.SourceFailure("LITERATURE_WORKFLOW_PROCESS_FAILED");
            byte[] raw=read(out,25165824);var value=json.readTree(raw);
            if(!VERSION.equals(value.path("schemaVersion").asText()) || !pin.equals(value.path("selectionSha256").asText())
                || value.path("businessPublishable").asBoolean(true) || value.path("scientificExecution").asBoolean(true))
                throw new DeNovoSourceTasks.SourceFailure("LITERATURE_WORKFLOW_RESULT_INVALID");
            // Keep immutable intake alongside the durable task, not only in an
            // execution scratch directory. This does not activate it for scoring.
            Path artifacts=root.resolve(FamilyDeNovoJson.sha256(bytes)).resolve("intakes");
            if(value.has("discoveryState")) {
                var state=value.path("discoveryState");
                if(!"discovery-state.json".equals(state.path("path").asText()))
                    throw new DeNovoSourceTasks.SourceFailure("LITERATURE_DISCOVERY_STATE_PATH_INVALID");
                byte[] stateBytes=read(directory.resolve("pipeline/discovery-state.json"),67108864);
                if(!FamilyDeNovoJson.sha256(stateBytes).equals(state.path("sha256").asText()))
                    throw new DeNovoSourceTasks.SourceFailure("LITERATURE_DISCOVERY_STATE_PIN_MISMATCH");
                Files.write(root.resolve(FamilyDeNovoJson.sha256(bytes)).resolve("discovery-state.json"),stateBytes,StandardOpenOption.CREATE_NEW);
            }
            for(var paper:value.path("papers")) if(paper.has("candidateIntake")) {
                String paperId=paper.path("pmcid").asText(); var intake=paper.path("candidateIntake");
                if(!paperId.matches("PMC[1-9][0-9]*") || !(paperId+"-candidate-intake.json").equals(intake.path("path").asText()))
                    throw new DeNovoSourceTasks.SourceFailure("LITERATURE_INTAKE_PATH_INVALID");
                byte[] artifact=read(directory.resolve("pipeline").resolve(intake.path("path").asText()),16777216);
                if(!FamilyDeNovoJson.sha256(artifact).equals(intake.path("sha256").asText()))
                    throw new DeNovoSourceTasks.SourceFailure("LITERATURE_INTAKE_PIN_MISMATCH");
                Files.createDirectories(artifacts);
                Files.write(artifacts.resolve(intake.path("path").asText()),artifact,StandardOpenOption.CREATE_NEW);
            }
            return raw;
        });
        return response(result);
    }
    public ObjectNode selectionOptions(ObjectNode selected) throws IOException {
        var result=options();var selection=boundSelection(selected,configuration());
        result.put("selectionSha256",FamilyDeNovoJson.sha256(json.writeValueAsBytes(selection)));
        result.set("selection",selection);return result;
    }
    private ObjectNode boundSelection(ObjectNode selected,ObjectNode c) throws IOException {
        var result=selection(selected);
        result.set("searchTargets",searchTargets(selected, result.putObject("variantAliasCoverage")));
        result.put("variantAliasSelectionPolicy", "MANE_CANONICAL_FIRST_MAX64_RETRIEVAL_ONLY");
        result.put("searchPolicy","LITVAR_ONLY");
        if(c.hasNonNull("coreContext")) {
            Path project=Path.of(c.path("projectRoot").asText()).toRealPath();
            var context=c.path("coreContext");
            Path path=project.resolve(context.path("path").asText()).toRealPath();
            if(!path.startsWith(project.resolve("local"))) throw new IOException("LITERATURE_CONTEXT_PATH_INVALID");
            byte[] raw=read(path,65536);
            String pin=FamilyDeNovoJson.sha256(raw);
            if(!pin.equals(context.path("sha256").asText())) throw new IOException("LITERATURE_CONTEXT_CHANGED");
            var pins=result.putObject("searchSourcePins");pins.put("context",pin);
            var records=json.readTree(raw).path("identity").path("clinvar");
            if(!records.isMissingNode() && (!records.isArray() || records.size()>100)) throw new IOException("LITERATURE_CLINVAR_SOURCE_LIMIT");
            for(var record:records) {
                Path source=project.resolve(record.path("path").asText()).toRealPath();
                if(!source.startsWith(project.resolve("local"))) throw new IOException("LITERATURE_CONTEXT_PATH_INVALID");
                String actual=FamilyDeNovoJson.sha256(read(source,4194304));
                if(!actual.equals(record.path("sha256").asText())) throw new IOException("LITERATURE_CLINVAR_SOURCE_CHANGED");
                pins.put(record.path("accession").asText(),actual);
            }
        }
        result.put("configurationSha256",FamilyDeNovoJson.sha256(json.writeValueAsBytes(c)));
        if(c.has("library")) result.put("sourceIndexSha256",FamilyDeNovoJson.sha256(read(local(c,"library").resolve("evidence/PS2/index.json"),1048576)));
        if(c.has("modelConfig")) result.put("modelConfigurationSha256",FamilyDeNovoJson.sha256(read(local(c,"modelConfig"),16384)));
        if(c.hasNonNull("candidateRepository")) result.put("candidateSnapshotSha256",new DeNovoCandidateRepository(local(c,"candidateRepository")).snapshot().sha256());
        if(c.has("projectRoot")) {
            var files=json.createObjectNode();
            try(var stream=Files.list(Path.of(c.path("projectRoot").asText()).resolve("scripts/denovo"))) {
                for(Path p:stream.filter(p->p.getFileName().toString().endsWith(".py")&&!p.getFileName().toString().startsWith("test_")).sorted().toList())
                    files.put(p.getFileName().toString(),FamilyDeNovoJson.sha256(read(p,524288)));
            }
            result.put("implementationSha256",FamilyDeNovoJson.sha256(json.writeValueAsBytes(files)));
        }
        return result;
    }
    /** Local allele matching plus allowlisted search terms; no sample or phenotype projection. */
    private com.fasterxml.jackson.databind.node.ArrayNode searchTargets(ObjectNode selected) throws IOException {
        return searchTargets(selected, null);
    }
    private com.fasterxml.jackson.databind.node.ArrayNode searchTargets(ObjectNode selected, ObjectNode coverage) throws IOException {
        var targets=json.createArrayNode();
        for(var candidate:selected.path("candidates")) {
            String gene=candidate.path("geneSymbol").asText("");
            if(!gene.matches("[A-Za-z][A-Za-z0-9.-]{0,39}")) continue;
            var row=targets.addObject().put("geneSymbol",gene);
            var original=candidate.path("allele");
            if(original.isObject()) {
                var allele=row.putObject("allele");
                for(String key:List.of("genomeAssembly","chromosome","position","reference","alternate"))
                    allele.set(key,original.path(key));
            } else row.putNull("allele");
            var aliases=new LinkedHashSet<String>();
            var transcripts = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            candidate.path("transcriptAnnotations").forEach(transcripts::add);
            transcripts.sort(java.util.Comparator.comparingInt(tx -> tx.path("maneSelect").asBoolean() ? 0 :
                    tx.path("manePlusClinical").asBoolean() ? 1 : tx.path("canonical").asBoolean() ? 2 : 3));
            for(var tx:transcripts) {
                if(!gene.equals(tx.path("geneSymbol").asText())) continue;
                for(String field:List.of("hgvsc","hgvsp","hgvsCoding","hgvsProtein")) {
                    String value=tx.path(field).asText("");
                    if(!value.matches("[A-Za-z0-9_=*+().:>/-]{1,160}")) continue;
                    if(!value.matches("(?:(?:NM_|NR_|NP_|ENST|ENSP)[0-9]+(?:\\.[0-9]+)?:)?(?:[cn]\\.[*+-]?[0-9].*|p\\.\\(?[A-Za-z*]{1,4}[0-9].*)")) continue;
                    String shortForm=value.substring(value.lastIndexOf(':')+1);
                    if(!shortForm.matches("[cnp]\\..+")) continue;
                    // Short and versioned forms are retrieval aliases only, never allele equivalence.
                    aliases.add(shortForm);aliases.add(value);
                }
            }
            // A transcript-rich allele must not abort discovery for every other selected allele.
            // These bounded aliases are retrieval hints, never proof of equivalence or exhaustive coverage.
            var values=row.putArray("variantAliases");aliases.stream().limit(64).forEach(values::add);
            if (coverage != null) coverage.putObject(candidate.path("candidateId").asText())
                    .put("availableAliases", aliases.size()).put("selectedAliases", values.size())
                    .put("omittedAliases", aliases.size() - values.size());
        }
        if(targets.isEmpty()) throw new IOException("LITERATURE_SELECTED_GENE_MISSING");
        return targets;
    }
    private ObjectNode selection(ObjectNode selected) throws IOException {
        if(!selected.path("executionSourceRecovered").asBoolean() || !selected.path("candidates").isArray()
            || selected.path("candidates").isEmpty() || selected.path("candidates").size()>50)
            throw new IOException("LITERATURE_FORMAL_SOURCE_REQUIRED");
        var selection=json.createObjectNode();
        selection.set("requestedN",selected.get("requestedN"));
        var rows=selection.putArray("candidates");
        for(var candidate:selected.path("candidates")) {
            var row=rows.addObject();
            // Missing optional fields serialize as null; keep the in-memory
            // projection identical to its durable JSON after a restart.
            for(String field:List.of("candidateId","rank","geneSymbol","candidateIdentity","allele")) row.set(field,candidate.get(field));
        }
        return selection;
    }
    public ObjectNode read(String analysis,String id) throws IOException { return response(tasks.read(taskRoot(configuration(),analysis),id)); }
    public ObjectNode cancel(String analysis,String id) throws IOException { return response(tasks.cancel(taskRoot(configuration(),analysis),id)); }
    public List<ObjectNode> list(String analysis) throws IOException {
        var c=configuration();if(!c.has("work")) return List.of();
        var root=taskRoot(c,analysis);if(!Files.isDirectory(root)) return List.of();
        try(var files=Files.list(root)) {
            var result=new ArrayList<ObjectNode>();
            for(Path p:files.filter(Files::isDirectory).sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).limit(10).toList())
                result.add(response(tasks.read(root,p.getFileName().toString())));
            return result;
        }
    }
    private ObjectNode response(DeNovoSourceTasks.Read read) throws IOException {
        var status=read.status();status.put("canRetry",false);
        if(read.result()!=null) status.set("result",json.readTree(read.result()));
        return status;
    }
    private static byte[] read(Path file,int limit) throws IOException {
        try(var stream=Files.newInputStream(file)) {var bytes=stream.readNBytes(limit+1);if(bytes.length==0||bytes.length>limit) throw new IOException("LITERATURE_FILE_LIMIT");return bytes;}
    }
    @Override public void close() {tasks.close();}
}
