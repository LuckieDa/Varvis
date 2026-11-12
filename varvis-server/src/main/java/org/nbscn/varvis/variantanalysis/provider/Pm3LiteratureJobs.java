package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Durable fixed-paper worker. Caller builds requests from recovered Top-N identity;
 * this is not a client-supplied scientific result or an ACMG evaluation endpoint.
 */
public final class Pm3LiteratureJobs implements AutoCloseable {
    public static final String ADAPTER_VERSION="pm3-literature-source-adapter-1.1.0";
    private static final int MAX_PROCESSING_ATTEMPTS=3;
    public static Pm3SelectedBatch.Source businessSource(Path path,Pm3LiteratureRaritySupply.Factory population) {
        return new Pm3SelectedBatch.Source() {
            public ObjectNode identity() {
                var json=new ObjectMapper();var out=json.createObjectNode().put("version","pm3-business-source-v1").put("adapterVersion",ADAPTER_VERSION)
                        .put("implementationScopeVersion","PM3_DEPENDENCIES_V2");
                try (var jobs=new Pm3LiteratureJobs(path,population)) {
                    out.put("workflowConfigSha256",FamilyDeNovoJson.sha256(read(path,16384)));
                    out.put("manualReviewIndexSha256",reviewIndexPin(jobs.local(jobs.configuration(),"library")));
                } catch(IOException invalid){out.put("status","PM3_WORKFLOW_CONFIGURATION_UNAVAILABLE");}
                out.set("population",population.identity());return out;
            }
            public ObjectNode capture(String analysis,ObjectNode recovered) throws IOException {
                try(var jobs=new Pm3LiteratureJobs(path,population)){return jobs.capture(analysis,recovered);}
            }
            public ObjectNode captureAutomatic(String analysis,ObjectNode recovered) throws IOException {
                try(var jobs=new Pm3LiteratureJobs(path,population)){return jobs.capture(analysis,recovered,true);}
            }
            public boolean acquire(String analysis,ObjectNode recovered,String candidate,boolean localPass,long deadline)
                    throws IOException {
                try(var jobs=new Pm3LiteratureJobs(path,population)) {
                    var c=jobs.configuration();
                    if(!localPass && !c.path("allowPublicNetwork").asBoolean() && !c.path("allowModel").asBoolean())
                        return false;
                    var selection=jobs.selectionOptions(recovered).path("selectionSha256").asText();
                    var task=jobs.startSelected(analysis,recovered,selection,candidate,null,null,true,localPass);
                    String id=task.path("taskId").asText();
                    while(Set.of("QUEUED","RUNNING").contains(task.path("status").asText())) {
                        org.nbscn.varvis.variantanalysis.service.AcmgRunControl.checkpoint();
                        if(System.nanoTime()>=deadline) {
                            jobs.cancel(analysis,id);
                            throw new IOException("PM3_AUTOMATIC_SOURCE_TIME_LIMIT");
                        }
                        try { Thread.sleep(150); }
                        catch(InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            jobs.cancel(analysis,id);
                            throw new IOException("PM3_AUTOMATIC_SOURCE_INTERRUPTED",interrupted);
                        }
                        task=jobs.read(analysis,id);
                    }
                    return true;
                }
            }
        };
    }
    private final ObjectMapper json=new ObjectMapper();
    private final Path config;
    private final DeNovoSourceTasks tasks;
    private final ExternalProcessRunner runner;
    private final Pm3LiteratureRaritySupply.Factory population;
    public Pm3LiteratureJobs(Path config) {
        this(config,null);
    }
    public Pm3LiteratureJobs(Path config,Pm3LiteratureRaritySupply.Factory population) {
        this(config,new DeNovoSourceTasks(1,2,540000,true),new DeNovoSourceProcessRunner(Duration.ofSeconds(540)),population);
    }
    Pm3LiteratureJobs(Path config,DeNovoSourceTasks tasks,ExternalProcessRunner runner) {
        this(config,tasks,runner,null);
    }
    Pm3LiteratureJobs(Path config,DeNovoSourceTasks tasks,ExternalProcessRunner runner,Pm3LiteratureRaritySupply.Factory population) {
        this.config=config.toAbsolutePath().normalize();this.tasks=tasks;this.runner=runner;
        this.population=population==null?new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity(){return json.createObjectNode().put("status","NOT_CONFIGURED");}
            public Pm2LocalExomesRuntime create(){throw new IllegalArgumentException("PM3_POPULATION_NOT_CONFIGURED");}
        }:population;
    }
    private ObjectNode configuration() throws IOException {
        JsonNode c=json.readTree(read(config,16384));
        if (!(c instanceof ObjectNode object) || !"denovo-public-literature-workflow-v1".equals(c.path("schemaVersion").asText())
                || !c.path("enabled").asBoolean()) throw new IOException("PM3_WORKFLOW_DISABLED");
        return object;
    }
    private Path local(ObjectNode c,String field) throws IOException {
        Path root=Path.of(c.path("projectRoot").asText()).toRealPath();
        Path relative=Path.of(c.path(field).asText());
        Path path=root.resolve(relative).normalize();
        if(relative.isAbsolute() || !path.startsWith(root.resolve("local")) || path.equals(root.resolve("local")))
            throw new IOException("PM3_LOCAL_PATH_REQUIRED");
        return path;
    }

    /** An isolated checkout can run the same pinned resources owned by the project. */
    private Path workerProject(ObjectNode configuration) throws IOException {
        Path project = Path.of(configuration.path("projectRoot").asText()).toRealPath();
        if (!configuration.has("pm3WorkerCheckout")) return project;
        var setting = configuration.path("pm3WorkerCheckout");
        if (!setting.isTextual() || setting.asText().isBlank()) {
            throw new IOException("PM3_WORKER_CHECKOUT_INVALID");
        }
        Path checkouts = project.resolve("local/development/checkouts");
        Path checkout = local(configuration, "pm3WorkerCheckout").toRealPath();
        Path scripts = checkout.resolve("scripts/denovo");
        if (checkout.equals(checkouts) || !checkout.startsWith(checkouts) ||
                !Files.isDirectory(scripts) || !scripts.toRealPath().startsWith(checkout)) {
            throw new IOException("PM3_WORKER_CHECKOUT_INVALID");
        }
        return checkout;
    }
    private Path taskRoot(ObjectNode c,String analysis) throws IOException {
        if(analysis==null || analysis.isBlank()) throw new IOException("PM3_ANALYSIS_ID_REQUIRED");
        return local(c,"work").resolve("pm3-paper-tasks").resolve(FamilyDeNovoJson.sha256(analysis.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    /** The complete task ID already binds analysis and request. Avoid repeating
     * both 64-character hashes in owned native process and Python artifact paths. */
    private Path paper(ObjectNode c,String id,String round) throws IOException {
        if(!id.matches("[a-f0-9]{64}") || !round.matches("paper(?:-round-[0-9]{1,3})?"))throw new IOException("PM3_READING_OUTPUT_INVALID");
        return local(c,"work").resolve("pm3-artifacts").resolve(id).resolve(round);
    }
    /** Only the server's recovered selection is accepted here, never request JSON. */
    public ObjectNode selectionOptions(ObjectNode recovered) throws IOException {
        var selection=selection(recovered);
        var c=configuration();
        var out=json.createObjectNode().put("schemaVersion","pm3-literature-options-v1")
                .put("selectionSha256",FamilyDeNovoJson.sha256(json.writeValueAsBytes(selection)))
                .put("enabled",true).put("modelEnabled",c.path("allowModel").asBoolean(false))
                .put("publicNetworkEnabled",c.path("allowPublicNetwork").asBoolean(false))
                .put("scientificExecution",false);
        return out.set("selection",selection);
    }
    private ObjectNode selection(ObjectNode recovered) throws IOException {
        if(!recovered.path("executionSourceRecovered").asBoolean() || !recovered.path("candidates").isArray()
                || recovered.path("candidates").isEmpty() || recovered.path("candidates").size()>50)
            throw new IOException("PM3_FORMAL_SOURCE_REQUIRED");
        var selection=json.createObjectNode();selection.set("requestedN",recovered.path("requestedN"));
        var rows=selection.putArray("candidates");var ids=new HashSet<String>();
        for(var candidate:recovered.path("candidates")) {
            String id=candidate.path("candidateId").asText();
            if(id.isBlank() || !ids.add(id)) throw new IOException("PM3_CANDIDATE_ID_INVALID");
            var row=rows.addObject();
            for(String field:List.of("candidateId","rank","geneSymbol","candidateIdentity","allele","transcriptAnnotations"))
                row.set(field,candidate.path(field).isMissingNode()?json.nullNode():candidate.path(field));
        }
        return selection;
    }
    public ObjectNode startSelected(String analysis,ObjectNode recovered,String expectedSelection,String candidateId,
                                    String pmcid,String bundlePin) throws IOException {
        return startSelected(analysis,recovered,expectedSelection,candidateId,pmcid,bundlePin,false,false);
    }
    private ObjectNode startSelected(String analysis,ObjectNode recovered,String expectedSelection,String candidateId,
                                    String pmcid,String bundlePin,boolean automatic,boolean localPass) throws IOException {
        var options=selectionOptions(recovered);
        if(!options.path("selectionSha256").asText().equals(expectedSelection)) throw new IOException("PM3_SELECTION_CHANGED_REFRESH_REQUIRED");
        JsonNode selected=null;
        for(var candidate:options.path("selection").path("candidates"))
            if(candidateId.equals(candidate.path("candidateId").asText())) selected=candidate;
        if(selected==null) throw new IOException("PM3_CANDIDATE_OUTSIDE_TOP_N");
        String gene=selected.path("geneSymbol").asText();
        if(!gene.matches("[A-Za-z][A-Za-z0-9.-]{0,39}")) throw new IOException("PM3_TARGET_GENE_REQUIRED");
        var allele=selected.path("allele");
        // Coding SNVs are already minimal. Other alleles must use the shared
        // reference normalizer before comparison; never silently compare raw indels.
        if(!"GRCh38".equals(allele.path("genomeAssembly").asText())
                || !allele.path("chromosome").asText().matches("[1-9]|1[0-9]|2[0-2]")
                || !allele.path("position").isInt() || allele.path("position").asInt()<1
                || allele.path("position").asInt()>300000000
                || !allele.path("reference").asText().matches("[ACGT]")
                || !allele.path("alternate").asText().matches("[ACGT]")
                || allele.path("reference").equals(allele.path("alternate")))
            throw new IOException("PM3_SELECTED_IDENTITY_SUPPLY_NOT_SUPPORTED");
        var request=json.createObjectNode().put("schemaVersion","pm3-public-paper-task-v1").put("pmcid",pmcid)
                .put("bundleSha256",bundlePin).put("selectionSha256",expectedSelection);
        var target=request.putObject("target").put("geneSymbol",gene);var aliases=new LinkedHashSet<String>();
        var transcripts = new java.util.ArrayList<JsonNode>();
        selected.path("transcriptAnnotations").forEach(transcripts::add);
        transcripts.sort(java.util.Comparator.comparingInt(tx -> tx.path("maneSelect").asBoolean() ? 0 :
                tx.path("manePlusClinical").asBoolean() ? 1 : tx.path("canonical").asBoolean() ? 2 : 3));
        for(var tx:transcripts) {
            if(!gene.equals(tx.path("geneSymbol").asText())) continue;
            for(String field:List.of("hgvsc","hgvsp","hgvsCoding","hgvsProtein")) {
                String value=tx.path(field).asText();
                if(value.length()>300)continue;
                if(value.matches("(?:N[MRP]_[0-9]+\\.[1-9][0-9]*:)?[cgp]\\.[A-Za-z0-9_*+?>=().;\\[\\]-]+")) aliases.add(value);
                // VEP normally supplies Ensembl accessions. Its reported short
                // form is a discovery hint only; source/sequence checks still
                // have to establish the literature allele independently.
                if(value.matches("(?:ENST|ENSP)[0-9]+\\.[1-9][0-9]*:[cp]\\.[A-Za-z0-9_*+?>=().;\\[\\]-]+"))
                    aliases.add(value.substring(value.indexOf(':')+1));
            }
        }
        if(aliases.isEmpty()) throw new IOException("PM3_VARIANT_SEARCH_ALIAS_MISSING");
        // Retrieval hints only: retain all source annotations in the selection binding.
        var values=target.putArray("variantAliases");aliases.stream().limit(32).forEach(values::add);
        var identity=request.putObject("selectedAllele").put("assembly","GRCh38");
        identity.set("contig",allele.path("chromosome"));
        for(String field:List.of("position","reference","alternate")) identity.set(field,allele.path(field));
        if(pmcid==null && bundlePin==null) {
            request.put("schemaVersion","pm3-public-discovery-task-v1");request.remove(List.of("pmcid","bundleSha256"));
        }
        return start(analysis,request,automatic,localPass);
    }
    public ObjectNode start(String analysis,ObjectNode serverRequest) throws IOException {
        return start(analysis,serverRequest,false,false);
    }
    private ObjectNode start(String analysis,ObjectNode serverRequest,boolean automatic,boolean localPass) throws IOException {
        ObjectNode c=configuration(),request=serverRequest.deepCopy();
        boolean needDriven=!localPass && c.path("pm3NeedDrivenReading").asBoolean(false);
        int maximumRounds=c.path("pm3MaximumReadingRounds").asInt(32);
        if(needDriven && (maximumRounds<1 || maximumRounds>128
                || c.has("pm3MaximumReadingRounds") && !c.path("pm3MaximumReadingRounds").isIntegralNumber()))
            throw new IOException("PM3_READING_ROUND_LIMIT_INVALID");
        boolean discovery="pm3-public-discovery-task-v1".equals(request.path("schemaVersion").asText());
        Set<String> fields=new HashSet<>();request.fieldNames().forEachRemaining(fields::add);
        var expected=new HashSet<>(discovery?Set.of("schemaVersion","target","selectedAllele","selectionSha256"):Set.of("schemaVersion","pmcid","bundleSha256","target","selectedAllele","selectionSha256"));
        if(discovery && request.has("discoveryContinuation")) {
            expected.add("discoveryContinuation");var origin=request.path("discoveryContinuation");
            if(!request.has("processingContinuation") || !origin.isObject() || origin.size()!=4
                    || !origin.path("parentTaskId").equals(request.at("/processingContinuation/parentTaskId"))
                    || !origin.path("parentResultSha256").equals(request.at("/processingContinuation/parentResultSha256"))
                    || !origin.path("stateSha256").asText().matches("[a-f0-9]{64}")
                    || !origin.path("statePath").isTextual() || origin.path("statePath").asText().isBlank())
                throw new IOException("PM3_DISCOVERY_CONTINUATION_INVALID");
        }
        if(request.has("processingContinuation")) {
            expected.add("processingContinuation");var origin=request.path("processingContinuation");
            if(!origin.isObject() || origin.size()!=2 || !origin.path("parentTaskId").asText().matches("[a-f0-9]{64}")
                    || !origin.path("parentResultSha256").asText().matches("[a-f0-9]{64}"))
                throw new IOException("PM3_PROCESSING_CONTINUATION_INVALID");
        }
        if(!fields.equals(expected)
                || !discovery && (!"pm3-public-paper-task-v1".equals(request.path("schemaVersion").asText())
                || !request.path("pmcid").asText().matches("PMC[1-9][0-9]*")
                || !request.path("bundleSha256").asText().matches("[a-f0-9]{64}"))
                || !request.path("selectionSha256").asText().matches("[a-f0-9]{64}")) throw new IOException("PM3_TASK_REQUEST_INVALID");
        Path project=workerProject(c),scripts=project.resolve("scripts/denovo");
        var implementation=implementation(project);
        Path library=local(c,"library"),model=local(c,"modelConfig"),index=library.resolve("evidence/PM3/index.json");
        String configPin=FamilyDeNovoJson.sha256(read(config,16384)),modelPin=FamilyDeNovoJson.sha256(read(model,16384)),
                indexPin=localPass?optionalPin(index):indexPin(index,discovery), reviewPin=reviewIndexPin(library);
        if(!c.equals(json.readTree(read(config,16384)))) throw new IOException("PM3_CONFIGURATION_CHANGED");
        byte[] input=json.writeValueAsBytes(request);String inputPin=FamilyDeNovoJson.sha256(input);
        var identity=json.createObjectNode().put("version","pm3-paper-job-v1").put("adapterVersion",ADAPTER_VERSION).put("analysisId",analysis)
                .put("requestSha256",inputPin).put("configurationSha256",configPin)
                .put("modelConfigurationSha256",modelPin).put("sourceIndexSha256",indexPin);
        identity.put("manualReviewIndexSha256",reviewPin);
        if(automatic)identity.put("automaticSource",true);
        if(localPass)identity.put("localFirstPass",true);
        identity.set("implementation",implementation);identity.set("request",request);
        if(request.has("processingContinuation")) {
            String parent=request.at("/processingContinuation/parentTaskId").asText();
            byte[] previousRaw=read(taskRoot(c,analysis).resolve(parent).resolve("identity.json"),65536);
            if(!parent.equals(FamilyDeNovoJson.sha256(previousRaw)))throw new IOException("PM3_TASK_IDENTITY_CHANGED");
            var previous=json.readTree(previousRaw);var stored=tasks.read(taskRoot(c,analysis),parent);byte[] body=stored.result();
            if(!(previous.path("request") instanceof ObjectNode old))throw new IOException("PM3_PROCESSING_CONTINUATION_INVALID");
            var oldScope=old.deepCopy();oldScope.remove(List.of("processingContinuation","discoveryContinuation"));
            var newScope=request.deepCopy();newScope.remove(List.of("processingContinuation","discoveryContinuation"));
            int generation=previous.path("processingGeneration").asInt(1)+1;
            if(!"READY".equals(stored.status().path("status").asText()) || body==null
                    || !request.at("/processingContinuation/parentResultSha256").asText().equals(FamilyDeNovoJson.sha256(body))
                    || !analysis.equals(previous.path("analysisId").asText()) || !oldScope.equals(newScope)
                    || !configPin.equals(previous.path("configurationSha256").asText())
                    || !modelPin.equals(previous.path("modelConfigurationSha256").asText()))throw new IOException("PM3_PROCESSING_CONTINUATION_INVALID");
            var previousResult=json.readTree(body);
            if(request.has("discoveryContinuation") && !request.at("/discoveryContinuation/stateSha256")
                    .equals(previousResult.at("/discoveryState/sha256")))throw new IOException("PM3_DISCOVERY_CONTINUATION_INVALID");
            if(reconciliationRequired(previousResult))throw new IOException("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED");
            if(generation>MAX_PROCESSING_ATTEMPTS)throw new IOException("PM3_PROCESSING_CONTINUATION_LIMIT");
            if(!continuationAvailable(previousResult))throw new IOException("PM3_NO_PENDING_PROCESSING_WORK");
            identity.put("processingGeneration",generation);
        }
        var populationIdentity=population.identity().deepCopy();
        identity.put("populationSupplyVersion",Pm3LiteratureRaritySupply.VERSION);
        identity.put("assessmentVersion",Pm3LiteratureAssessment.VERSION);
        identity.put("processRunnerVersion",DeNovoSourceProcessRunner.VERSION);
        if(needDriven)identity.put("readingFeedbackVersion",Pm3ReadingFeedback.VERSION);
        identity.put("populationConfigurationSha256",FamilyDeNovoJson.sha256(json.writeValueAsBytes(populationIdentity)));
        byte[] identityRaw=json.writeValueAsBytes(identity);String id=FamilyDeNovoJson.sha256(identityRaw);
        Path root=taskRoot(c,analysis);
        var read=tasks.start(root,identityRaw,remaining->{
            long started=System.nanoTime();
            if(!configPin.equals(FamilyDeNovoJson.sha256(read(config,16384)))
                    || !modelPin.equals(FamilyDeNovoJson.sha256(read(model,16384)))
                    || !indexPin.equals(localPass?optionalPin(index):indexPin(index,discovery))
                    || !reviewPin.equals(reviewIndexPin(library)))
                throw new DeNovoSourceTasks.SourceFailure("PM3_CONFIGURATION_OR_INDEX_CHANGED");
            for(var entry:implementation.properties())
                if(!entry.getValue().asText().equals(FamilyDeNovoJson.sha256(read(scripts.resolve(entry.getKey()),524288))))
                    throw new DeNovoSourceTasks.SourceFailure("PM3_IMPLEMENTATION_CHANGED");
            Path folder=root.resolve(id),in=folder.resolve("request.json");
            Files.write(in,input,StandardOpenOption.CREATE_NEW);
            var rounds=json.createArrayNode();ObjectNode feedback=json.createObjectNode()
                    .put("schemaVersion",Pm3ReadingFeedback.VERSION).put("requestSha256",inputPin).put("cacheOnly",true);
            feedback.putArray("papers");
            int modelCalls=0,calculatorCalls=0;String lastProgress=null;ObjectNode lastEnriched=null;
            var accumulatedPapers=new LinkedHashMap<String,JsonNode>();
            var raritySupply=new Pm3LiteratureRaritySupply(json,population);
            for(int round=0;round<(needDriven?maximumRounds:1);round++) {
            Path out=paper(c,id,round==0?"paper":"paper-round-"+round);Files.createDirectories(out.getParent());
            var command=new ArrayList<>(List.of(local(c,"python").toString(),"-E","-s","-B",scripts.resolve("run_pm3_paper_task.py").toString(),
                    "--request",in.toString(),"--request-sha256",inputPin,"--config",config.toString(),"--output",out.toString()));
            if(localPass)command.add("--local-first-pass");
            if(needDriven) {
                Path feedbackPath=out.getParent().resolve("reading-feedback-"+round+".json");byte[] feedbackRaw=json.writeValueAsBytes(feedback);
                Files.write(feedbackPath,feedbackRaw,StandardOpenOption.CREATE_NEW);
                command.addAll(List.of("--reading-feedback",feedbackPath.toString(),"--reading-feedback-sha256",FamilyDeNovoJson.sha256(feedbackRaw)));
            }
            var roundRemaining=remaining.minusNanos(Math.min(remaining.toNanos(),System.nanoTime()-started));
            ProcessResult process;
            try {process=runner.run(command,project,roundRemaining);}
            catch(IOException workerFailure) {
                if(lastEnriched==null)throw workerFailure;
                var partial=lastEnriched.deepCopy();var state=(ObjectNode)partial.path("readingLoop");
                state.put("state","INCOMPLETE").put("stopReason","PM3_WORKER_FAILED").put("failedRound",round)
                        .put("failedAttemptAccounting","CHECK_SHARED_LEDGER");
                partial.put("status","PARTIAL");checkpoint(folder,partial);return json.writeValueAsBytes(partial);
            }
            if(!process.isSuccess()) {
                String reason=process.isTimedOut()?"PM3_WORKER_TIMEOUT":"PM3_WORKER_FAILED";
                if(lastEnriched!=null) {
                    var partial=lastEnriched.deepCopy();var state=(ObjectNode)partial.path("readingLoop");
                    state.put("state","INCOMPLETE").put("stopReason",reason).put("failedRound",round)
                            .put("failedAttemptAccounting","CHECK_SHARED_LEDGER");
                    partial.put("status","PARTIAL");
                    checkpoint(folder,partial);return json.writeValueAsBytes(partial);
                }
                throw new DeNovoSourceTasks.SourceFailure(reason);
            }
            byte[] raw=read(out.resolve("result.json"),32*1024*1024);JsonNode result=json.readTree(raw);
            if(!(discovery?"pm3-discovery-process-v1":"pm3-paper-process-v1").equals(result.path("schemaVersion").asText())
                    || !inputPin.equals(result.path("requestSha256").asText())
                    || !configPin.equals(result.path("workflowConfigSha256").asText())
                    || result.path("businessPublishable").asBoolean(true) || result.path("scientificExecution").asBoolean(true)
                    || !Set.of("PARTIAL","SOURCE_REVIEWED_SEMANTICS_PENDING").contains(result.path("status").asText()))
                throw new DeNovoSourceTasks.SourceFailure("PM3_WORKER_RESULT_INVALID");
            for(String field:discovery?List.of("target","selectionSha256"):List.of("pmcid","bundleSha256","target","selectionSha256"))
                if(!request.path(field).equals(result.path(field))) throw new DeNovoSourceTasks.SourceFailure("PM3_WORKER_RESULT_BINDING_MISMATCH");
            if(!reviewPin.equals(reviewIndexPin(library)))
                throw new DeNovoSourceTasks.SourceFailure("PM3_MANUAL_REVIEW_INDEX_CHANGED");
            if(!populationIdentity.equals(population.identity()))
                throw new DeNovoSourceTasks.SourceFailure("PM3_POPULATION_CONFIGURATION_CHANGED");
            if(needDriven && discovery) {
                for(var paper:result.path("papers")) {
                    String pmcid=paper.path("pmcid").asText();JsonNode prior=accumulatedPapers.get(pmcid);
                    if(prior!=null && !prior.path("bundleSha256").equals(paper.path("bundleSha256")))
                        throw new DeNovoSourceTasks.SourceFailure("PM3_READING_SOURCE_CHANGED");
                    accumulatedPapers.put(pmcid,paper.deepCopy());
                }
                ((ObjectNode)result).set("papers",json.valueToTree(accumulatedPapers.values()));
            }
            var enriched=raritySupply.enrich((ObjectNode)result,request,analysis,
                    remaining.minusNanos(Math.min(remaining.toNanos(),System.nanoTime()-started)));
            if(!populationIdentity.equals(population.identity()))
                throw new DeNovoSourceTasks.SourceFailure("PM3_POPULATION_CONFIGURATION_CHANGED");
            var assessment=new Pm3LiteratureAssessment(json).assess(enriched,request);
            enriched.set("pm3Assessment",assessment);
            boolean calculated=assessment.path("pm3CalculatorCalls").asInt()>0;
            enriched.put("pm3ScientificExecution",calculated)
                    .put("scientificExecution",enriched.path("scientificExecution").asBoolean() || calculated);
            if(!needDriven)return json.writeValueAsBytes(enriched);
            modelCalls+=result.path("modelCalls").asInt();calculatorCalls+=assessment.path("pm3CalculatorCalls").asInt();
            feedback=new Pm3ReadingFeedback(json).build(enriched,assessment,inputPin,c.path("pm3ReadingStopWhenEvaluable").asBoolean(true));
            var discoveryState=enriched.path("discoveryState");
            if(discovery && discoveryState.path("resumeAvailable").asBoolean()) {
                Path stateFile=out.resolve("discovery-state.json");
                if(!discoveryState.path("sha256").asText().equals(FamilyDeNovoJson.sha256(read(stateFile,64*1024*1024))))
                    throw new DeNovoSourceTasks.SourceFailure("PM3_DISCOVERY_STATE_CHANGED");
                feedback.putObject("discoveryContinuation").put("parentTaskId",id)
                        .put("parentResultSha256",FamilyDeNovoJson.sha256(raw))
                        .put("stateSha256",discoveryState.path("sha256").asText())
                        .put("statePath",project.relativize(stateFile).toString());
            }
            var entry=rounds.addObject().put("round",round).put("outputDirectory",out.getFileName().toString())
                    .put("workerResultSha256",FamilyDeNovoJson.sha256(raw)).put("modelCalls",result.path("modelCalls").asInt());
            entry.set("feedback",feedback.deepCopy());entry.set("assessment",assessment.deepCopy());
            Files.write(out.resolve("assessed-result.json"),json.writeValueAsBytes(enriched),StandardOpenOption.CREATE_NEW);
            // Re-evaluation is over cumulative observations, never a sum of old scores.
            var progressNode=json.createObjectNode();progressNode.set("papers",feedback.path("papers"));
            progressNode.set("discovery",discoveryState);
            String progress=FamilyDeNovoJson.sha256(json.writeValueAsBytes(progressNode));
            boolean proceed=feedback.path("canContinueWithinTask").asBoolean();
            String stop=null;
            if(proceed && !c.path("allowModel").asBoolean(false))stop="PM3_MODEL_DISABLED_PENDING_READING";
            if(reconciliationRequired(enriched))stop="PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED";
            else if(proceed && progress.equals(lastProgress))stop="PM3_READING_NO_PROGRESS";
            else if(proceed && round+1>=maximumRounds)stop="PM3_READING_ROUND_LIMIT";
            else if(proceed && remaining.toNanos()-(System.nanoTime()-started)<5_000_000_000L)stop="PM3_READING_TIME_LIMIT";
            if(stop!=null){feedback.put("state","INCOMPLETE").put("canContinueWithinTask",false).put("stopReason",stop);proceed=false;}
            var loop=enriched.putObject("readingLoop").put("schemaVersion","pm3-reading-loop-v1")
                    .put("state",feedback.path("state").asText()).put("finalOutputDirectory",out.getFileName().toString())
                    .put("roundCount",round+1).put("modelCalls",modelCalls).put("pm3CalculatorCalls",calculatorCalls)
                    .put("fullPaperRead",false);
            loop.set("feedback",feedback.deepCopy());loop.set("rounds",rounds.deepCopy());
            enriched.put("modelCalls",modelCalls);
            checkpoint(folder,enriched);lastEnriched=enriched;
            if(!proceed)return json.writeValueAsBytes(enriched);
            lastProgress=progress;
            }
            throw new DeNovoSourceTasks.SourceFailure("PM3_READING_LOOP_EXIT_INVALID");
        });
        return response(read,analysis);
    }
    private void checkpoint(Path folder,ObjectNode value) throws IOException {
        Path temporary=folder.resolve("reading-checkpoint.tmp"),target=folder.resolve("reading-checkpoint.json");
        Files.write(temporary,json.writeValueAsBytes(value));
        try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    public ObjectNode continueDiscovery(String analysis,ObjectNode recovered,String expectedSelection,String parentId) throws IOException {
        if(!parentId.matches("[a-f0-9]{64}")) throw new IOException("PM3_PARENT_TASK_INVALID");
        var c=configuration();var root=taskRoot(c,analysis);var prior=tasks.read(root,parentId);
        if(!"READY".equals(prior.status().path("status").asText()) || prior.result()==null) throw new IOException("PM3_PARENT_RESULT_REQUIRED");
        byte[] identityRaw=read(root.resolve(parentId).resolve("identity.json"),65536);
        if(!parentId.equals(FamilyDeNovoJson.sha256(identityRaw)))throw new IOException("PM3_TASK_IDENTITY_CHANGED");
        var identity=json.readTree(identityRaw);
        var result=json.readTree(prior.result());byte[] raw=read(root.resolve(parentId).resolve("request.json"),65536);
        var request=(ObjectNode)json.readTree(raw);
        if(!FamilyDeNovoJson.sha256(raw).equals(result.path("requestSha256").asText())
                || !analysis.equals(identity.path("analysisId").asText()) || !identity.path("request").equals(request)
                || !Set.of("pm3-public-discovery-task-v1","pm3-public-paper-task-v1").contains(request.path("schemaVersion").asText())
                || !expectedSelection.equals(request.path("selectionSha256").asText())
                || !expectedSelection.equals(selectionOptions(recovered).path("selectionSha256").asText()))
            throw new IOException("PM3_CONTINUATION_SELECTION_CHANGED");
        var state=result.path("discoveryState");
        if(reconciliationRequired(result))throw new IOException("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED");
        if(identity.path("processingGeneration").asInt(1)>=MAX_PROCESSING_ATTEMPTS)throw new IOException("PM3_PROCESSING_CONTINUATION_LIMIT");
        if(!continuationAvailable(result))throw new IOException("PM3_NO_PENDING_PROCESSING_WORK");
        // New immutable task identity; old results and transport receipts stay intact.
        // The worker reconstructs facts from validated raw caches using current code.
        request.remove("discoveryContinuation");
        request.putObject("processingContinuation").put("parentTaskId",parentId)
                .put("parentResultSha256",FamilyDeNovoJson.sha256(prior.result()));
        if(state.path("resumeAvailable").asBoolean()) {
            if(!state.path("sha256").asText().matches("[a-f0-9]{64}"))throw new IOException("PM3_DISCOVERY_STATE_CHANGED");
            String finalFolder=result.path("readingLoop").path("finalOutputDirectory").asText("paper");
            if(!finalFolder.matches("paper(?:-round-[0-9]{1,3})?"))throw new IOException("PM3_READING_OUTPUT_INVALID");
            Path statePath=paper(c,parentId,finalFolder).resolve("discovery-state.json");
            // Existing durable tasks keep their original directories and bytes.
            if(!Files.isRegularFile(statePath))statePath=root.resolve(parentId).resolve(finalFolder).resolve("discovery-state.json");
            if(!state.path("sha256").asText().equals(FamilyDeNovoJson.sha256(read(statePath,64*1024*1024))))
                throw new IOException("PM3_DISCOVERY_STATE_CHANGED");
            Path project=Path.of(c.path("projectRoot").asText()).toRealPath();
            request.putObject("discoveryContinuation").put("parentTaskId",parentId)
                    .put("parentResultSha256",FamilyDeNovoJson.sha256(prior.result()))
                    .put("stateSha256",state.path("sha256").asText()).put("statePath",project.relativize(statePath).toString());
        }
        return start(analysis,request);
    }
    static boolean continuationAvailable(JsonNode result) {
        if(reconciliationRequired(result))return false;
        if(result.path("discoveryState").path("resumeAvailable").asBoolean())return true;
        var checkpoint=result.path("processingCheckpoint");
        if("pm3-paper-processing-checkpoint-v1".equals(checkpoint.path("schemaVersion").asText())) {
            for(var stage:checkpoint.path("stages"))if(Set.of("NOT_EXTRACTED","NOT_REVIEWED").contains(stage.path("status").asText()))return true;
        }
        var loop=result.path("readingLoop");var feedback=loop.path("feedback");
        String stop=loop.path("stopReason").asText(feedback.path("stopReason").asText());
        if(!"INCOMPLETE".equals(loop.path("state").asText()) || !Set.of("PM3_WORKER_FAILED","PM3_WORKER_TIMEOUT",
                "PM3_READING_TIME_LIMIT","PM3_READING_ROUND_LIMIT","PM3_MODEL_DISABLED_PENDING_READING").contains(stop))return false;
        // Conflicts/unsupported rules and no-progress stops are not retry requests.
        if(feedback.path("sourceConflict").asBoolean())return false;
        return feedback.path("hasPendingRelevantWork").asBoolean()
                || java.util.stream.StreamSupport.stream(feedback.path("papers").spliterator(),false)
                    .anyMatch(p->p.path("pendingRelevantChunks").isArray() && !p.path("pendingRelevantChunks").isEmpty());
    }
    static boolean reconciliationRequired(JsonNode result) {
        if("CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT".equals(result.path("callAccountingStatus").asText())
                || "CHECK_SHARED_LEDGER".equals(result.path("readingLoop").path("failedAttemptAccounting").asText())
                || "FAILED_OR_UNKNOWN_RESERVATION_RETAINED".equals(result.path("modelChargeStatus").asText()))return true;
        for(var paper:result.path("papers"))if(reconciliationRequired(paper))return true;
        return false;
    }
    public ObjectNode read(String analysis,String id) throws IOException {return response(tasks.read(taskRoot(configuration(),analysis),id),analysis);}
    public ObjectNode cancel(String analysis,String id) throws IOException {return response(tasks.cancel(taskRoot(configuration(),analysis),id),analysis);}
    /** Read-only business capture. A request binds the complete immutable snapshot
     * before cache lookup; new papers cannot silently change a saved ACMG result. */
    public ObjectNode capture(String analysis,ObjectNode recovered) throws IOException {
        return capture(analysis,recovered,false);
    }
    private ObjectNode capture(String analysis,ObjectNode recovered,boolean includeAutomatic) throws IOException {
        var c=configuration();var root=taskRoot(c,analysis);
        String selectionPin=selectionOptions(recovered).path("selectionSha256").asText();
        String configPin=FamilyDeNovoJson.sha256(read(config,16384));
        String populationPin=FamilyDeNovoJson.sha256(json.writeValueAsBytes(population.identity()));
        Path library=local(c,"library");
        String reviewPin=reviewIndexPin(library);
        Path project=workerProject(c);
        var snapshot=json.createObjectNode().put("schemaVersion","pm3-business-source-snapshot-v1")
                .put("analysisId",analysis).put("selectionSha256",selectionPin)
                .put("configurationSha256",configPin).put("populationConfigurationSha256",populationPin)
                .put("manualReviewIndexSha256",reviewPin);
        var documents=snapshot.putArray("documents");var excluded=snapshot.putArray("excludedTasks");
        long total=0;
        if(Files.isDirectory(root))try(var paths=Files.list(root)) {
            var folders=paths.filter(Files::isDirectory).filter(p->p.getFileName().toString().matches("[a-f0-9]{64}"))
                    .sorted().limit(1001).toList();
            if(folders.size()>1000)throw new IOException("PM3_TASK_INVENTORY_LIMIT");
            ObjectNode currentImplementation=null;
            for(var folder:folders) {
                String id=folder.getFileName().toString();
                var stored=tasks.read(root,id);
                Path identityFile=folder.resolve("identity.json");
                if(Files.notExists(identityFile))continue;
                byte[] identityRaw=read(identityFile,65536);
                if(!id.equals(FamilyDeNovoJson.sha256(identityRaw)))throw new IOException("PM3_TASK_IDENTITY_CHANGED");
                var binding=json.readTree(identityRaw);var request=binding.path("request");
                if(!includeAutomatic && binding.path("automaticSource").asBoolean())continue;
                if(!"READY".equals(stored.status().path("status").asText())) {
                    excluded.addObject().put("taskId",id).put("reasonCode",stored.status().path("reasonCode").asText("PM3_TASK_NOT_READY"));continue;
                }
                String reason=!analysis.equals(binding.path("analysisId").asText())?"PM3_TASK_ANALYSIS_MISMATCH"
                        :!selectionPin.equals(request.path("selectionSha256").asText())?"PM3_TASK_SELECTION_STALE"
                        :!configPin.equals(binding.path("configurationSha256").asText())?"PM3_TASK_CONFIGURATION_STALE"
                        :!reviewPin.equals(binding.path("manualReviewIndexSha256").asText("ABSENT"))?"PM3_MANUAL_REVIEW_INDEX_CHANGED"
                        :!populationPin.equals(binding.path("populationConfigurationSha256").asText())?"PM3_TASK_POPULATION_STALE"
                        :!Pm3LiteratureRaritySupply.VERSION.equals(binding.path("populationSupplyVersion").asText())
                            || !Pm3LiteratureAssessment.VERSION.equals(binding.path("assessmentVersion").asText())?"PM3_TASK_METHOD_STALE":null;
                boolean independentEntryPoint=false;
                if(reason==null) {
                    if(currentImplementation==null)currentImplementation=implementation(project);
                    if(!(binding.path("implementation") instanceof ObjectNode previous))reason="PM3_TASK_METHOD_STALE";
                    else {
                        var applicable=previous.deepCopy();
                        // Old jobs bound every Python file, including an independent PP1 launcher.
                        // Only remove it when the current source proves it is not a dependency;
                        // all remaining files, additions and deletions must match exactly.
                        if(!currentImplementation.has("run_pp1_paper_task.py"))
                            independentEntryPoint=applicable.remove("run_pp1_paper_task.py")!=null;
                        if(!compatibleImplementation(previous,currentImplementation))reason="PM3_TASK_METHOD_STALE";
                    }
                }
                if(reason!=null){excluded.addObject().put("taskId",id).put("reasonCode",reason);continue;}
                byte[] bytes=stored.result();
                if(bytes==null)throw new IOException("PM3_READY_TASK_RESULT_MISSING");
                total+=bytes.length;if(total>128L*1024*1024)throw new IOException("PM3_BUSINESS_SOURCE_SIZE_LIMIT");
                var result=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bytes);
                if(!request.isObject() || !binding.path("requestSha256").equals(result.path("requestSha256"))
                        || !FamilyDeNovoJson.sha256(json.writeValueAsBytes(request)).equals(binding.path("requestSha256").asText())
                        || !selectionPin.equals(result.path("selectionSha256").asText())
                        || !configPin.equals(result.path("workflowConfigSha256").asText())
                        || !Set.of("pm3-paper-process-v1","pm3-discovery-process-v1").contains(result.path("schemaVersion").asText()))
                    throw new IOException("PM3_TASK_RESULT_BINDING_INVALID");
                var doc=documents.addObject().put("taskId",id).put("resultSha256",FamilyDeNovoJson.sha256(bytes));
                doc.putObject("implementationValidation").put("method","PM3_DEPENDENCIES_V2")
                        .put("dependencySha256",new RuntimeEvidenceV2Adapter(json).canonicalSha256(currentImplementation))
                        .put("legacyIndependentPp1EntryPointExcluded",independentEntryPoint)
                        .put("savedScoreAdopted",false);
                doc.set("request",request.deepCopy());doc.set("result",result);
            }
        }
        if(!configPin.equals(FamilyDeNovoJson.sha256(read(config,16384))) || !reviewPin.equals(reviewIndexPin(library))
                || !populationPin.equals(FamilyDeNovoJson.sha256(json.writeValueAsBytes(population.identity()))))
            throw new IOException("PM3_CAPTURE_CONFIGURATION_CHANGED");
        snapshot.put("snapshotSha256",Pm3SourceDigest.sha256(json,snapshot));
        return snapshot;
    }
    ObjectNode implementation(Path project) throws IOException {
        Path scripts=project.resolve("scripts/denovo");
        var sources=new TreeMap<String,byte[]>();
        try(var paths=Files.list(scripts)) {
            for(Path p:paths.filter(p->p.getFileName().toString().endsWith(".py") && !p.getFileName().toString().startsWith("test_")).sorted().toList())
                sources.put(p.getFileName().toString(),read(p,524288));
        }
        for(String registry:List.of("bundle_replay_profiles.json","core_review_replay_profiles.json")) {
            Path file=scripts.resolve(registry);
            if(Files.isRegularFile(file))sources.put(registry,read(file,524288));
        }
        String caseRulesSource="../../varvis-server/src/main/resources/acmg/pm3-published-case-rules.json";
        Path caseRules=scripts.resolve(caseRulesSource);
        if(Files.isRegularFile(caseRules))sources.put(caseRulesSource,read(caseRules,524288));
        for(String name:List.of("../ps1_pm5/prepare_reference_evidence.py","../ps1_pm5/prepare_expert_reference_evidence.py",
                "../pvs1/prepare_applicability_sources.py","../pvs1/assemble_materials.py","../acmg-generalization/import_cspec_catalog.py"))
            sources.put(name,read(scripts.resolve(name),524288));
        boolean independent=sources.entrySet().stream().filter(e->!e.getKey().equals("run_pp1_paper_task.py"))
                .map(e->new String(e.getValue(),java.nio.charset.StandardCharsets.UTF_8))
                .noneMatch(s->s.contains("run_pp1_paper_task") || s.contains("__import__") || s.contains("importlib") || s.contains("runpy")
                        || java.util.regex.Pattern.compile("\\b(?:exec|eval)\\s*\\(").matcher(s).find());
        if(independent)sources.remove("run_pp1_paper_task.py");
        var out=json.createObjectNode();sources.forEach((name,bytes)->out.put(name,FamilyDeNovoJson.sha256(bytes)));
        return out;
    }
    static boolean compatibleImplementation(ObjectNode previous,ObjectNode current) {
        var applicable=previous.deepCopy();
        if(!current.has("run_pp1_paper_task.py"))applicable.remove("run_pp1_paper_task.py");
        return !current.isEmpty() && applicable.equals(current);
    }
    public List<ObjectNode> list(String analysis) throws IOException {
        Path root=taskRoot(configuration(),analysis);
        if(!Files.isDirectory(root)) return List.of();
        try(var files=Files.list(root)) {
            var result=new ArrayList<ObjectNode>();
            for(Path path:files.filter(Files::isDirectory)
                    .filter(p->p.getFileName().toString().matches("[a-f0-9]{64}"))
                    .sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).limit(20).toList())
                result.add(response(tasks.read(root,path.getFileName().toString()),analysis));
            return result;
        }
    }
    private ObjectNode response(DeNovoSourceTasks.Read read,String analysis) throws IOException {
        var result=read.status();result.put("schemaVersion","pm3-public-literature-task-v1")
                .put("canRetry",false).put("scientificExecution",false);
        if(read.result()!=null) {
            var content=json.readTree(read.result());result.set("result",content);
            result.put("scientificExecution",content.path("scientificExecution").asBoolean(false));
            result.put("pm3ScientificExecution",content.path("pm3ScientificExecution").asBoolean(false));
            result.put("canContinueProcessing","READY".equals(result.path("status").asText()) && continuationAvailable(content));
            Path file=taskRoot(configuration(),analysis).resolve(result.path("taskId").asText()).resolve("identity.json");
            byte[] identityRaw=read(file,65536);
            if(!result.path("taskId").asText().equals(FamilyDeNovoJson.sha256(identityRaw)))throw new IOException("PM3_TASK_IDENTITY_CHANGED");
            var binding=json.readTree(identityRaw);int generation=binding.path("processingGeneration").asInt(1);
            result.put("processingAttempt",generation).put("maximumProcessingAttempts",MAX_PROCESSING_ATTEMPTS)
                    .put("sharedBudgetReconciliationRequired",reconciliationRequired(content));
            if(result.path("canContinueProcessing").asBoolean()) {
                var c=configuration();
                if(!binding.path("configurationSha256").asText().equals(FamilyDeNovoJson.sha256(read(config,16384)))
                        || !binding.path("modelConfigurationSha256").asText().equals(FamilyDeNovoJson.sha256(read(local(c,"modelConfig"),16384))))
                    result.put("canContinueProcessing",false).put("processingContinuationBlockedReason","PM3_TASK_CONFIGURATION_STALE");
            }
            if(generation>=MAX_PROCESSING_ATTEMPTS)result.put("canContinueProcessing",false)
                    .put("processingContinuationBlockedReason","PM3_PROCESSING_CONTINUATION_LIMIT");
            if(reconciliationRequired(content))result.put("processingContinuationBlockedReason","PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED");
        }
        if(!result.has("pm3ScientificExecution"))result.put("pm3ScientificExecution",false);
        return result;
    }
    private static byte[] read(Path path,int max) throws IOException {
        try(var in=Files.newInputStream(path)) {byte[] raw=in.readNBytes(max+1);if(raw.length==0 || raw.length>max) throw new IOException("PM3_FILE_LIMIT");return raw;}
    }
    private static String reviewIndexPin(Path library) throws IOException {
        return optionalPin(library.resolve("manual-reviews/index.json"));
    }
    private static String optionalPin(Path path) throws IOException {
        return Files.notExists(path)?"ABSENT":FamilyDeNovoJson.sha256(read(path,1048576));
    }
    private static String indexPin(Path path,boolean discovery) throws IOException {
        // Acquisition changes this index itself. Its contents are verified per
        // paper by Python; they must not turn a repeat submission into a new job.
        return discovery?"DYNAMIC_ACQUISITION":FamilyDeNovoJson.sha256(read(path,1048576));
    }
    @Override public void close(){tasks.close();}
}
