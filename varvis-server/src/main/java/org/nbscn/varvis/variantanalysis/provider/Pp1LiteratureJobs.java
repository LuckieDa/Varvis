package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService.Pp1LiteratureAttempt;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService.Pp1LiteraturePreparation;

/** Durable PP1 source work using the existing bounded, cancellable queue.
 * Only the server's prepared binding enters this adapter. A browser never
 * supplies knowledge, ledger contributions, artifact paths or scientific scores.
 */
public final class Pp1LiteratureJobs implements AutoCloseable {
    public static final String VERSION = "pp1-paper-job-v2";
    public static final String ADAPTER_VERSION = "pp1-literature-source-adapter-2.2.0";
    private static final int MAX_PROCESSING_ATTEMPTS = 3;
    private static final int MAX_DISCOVERY_ATTEMPTS = 3;
    private static final List<String> DISCOVERY_DEPENDENCIES=List.of("discover_public_literature.py", "literature_search_transport.py",
            "literature_allele_identity.py", "prepare_literature.py", "select_public_papers.py", "fetch_public_literature.py", "litvar_literature.py");
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter canonical = new RuntimeEvidenceV2Adapter(json);
    private final Path config;
    private final DeNovoSourceTasks tasks;
    private final ExternalProcessRunner runner;

    public static Pp1SelectedBatch.Source businessSource(Path path) {
        return new Pp1SelectedBatch.Source() {
            @Override public boolean verifiesPublishedPp4Exclusion() { return true; }
            @Override public ObjectNode identity() {
                try(var jobs=new Pp1LiteratureJobs(path)) {
                    var c=jobs.configuration();var out=jobs.json.createObjectNode().put("version",VERSION).put("adapterVersion",ADAPTER_VERSION)
                            .put("workflowConfigSha256",FamilyDeNovoJson.sha256(read(jobs.config,16384)));
                    out.set("implementation",jobs.implementation(c));return out;
                } catch(IOException unavailable) {
                    return new ObjectMapper().createObjectNode().put("version",VERSION).put("adapterVersion",ADAPTER_VERSION).put("status","CONFIGURATION_UNAVAILABLE");
                }
            }
            @Override public ObjectNode capture(String analysis,ObjectNode recovered) throws IOException {
                try(var jobs=new Pp1LiteratureJobs(path)){return jobs.capture(analysis,recovered);}
            }
            @Override public ObjectNode evaluate(ObjectNode snapshot,ObjectNode binding) throws IOException {
                try(var jobs=new Pp1LiteratureJobs(path)){return jobs.evaluate(snapshot,binding);}
            }
        };
    }

    public Pp1LiteratureJobs(Path config) {
        this(config, new DeNovoSourceTasks(1, 2, 540000, true), new DeNovoSourceProcessRunner(java.time.Duration.ofSeconds(540)));
    }
    /** Read only pinned local source scopes. No model, network or patient diagnosis. */
    public ObjectNode contextOptions(ObjectNode candidates) throws IOException {
        var c=configuration();Path project=Path.of(c.path("projectRoot").asText()).toRealPath();
        String pin=FamilyDeNovoJson.sha256(read(config,16384));
        Path base=local(c,"work").resolve("context-options");Files.createDirectories(base);
        Path folder=Files.createTempDirectory(base,"read-");Path out=folder.resolve("result.json");
        var process=runner.run(List.of(local(c,"python").toString(),"-E","-s","-B",
                project.resolve("scripts/denovo/literature_context_options.py").toString(),"--config",config.toString(),
                "--output",out.toString()),project,java.time.Duration.ofSeconds(30));
        if(!process.isSuccess())throw new IOException("LITERATURE_CONTEXT_SOURCE_UNAVAILABLE");
        var value=json.readTree(read(out,4*1024*1024));
        if(!"literature-context-options-v1".equals(value.path("schemaVersion").asText())
                || !pin.equals(value.path("workflowConfigSha256").asText())
                || !pin.equals(FamilyDeNovoJson.sha256(read(config,16384)))
                || !value.path("contexts").isArray())throw new IOException("LITERATURE_CONTEXT_SOURCE_CHANGED");
        var result=((ObjectNode)value).deepCopy();var rows=result.putArray("contexts");
        for(var row:value.path("contexts"))for(var candidate:candidates.path("candidates"))
            if(row.path("geneSymbol").equals(candidate.path("geneSymbol"))
                    && row.path("hgncId").equals(candidate.at("/catalog/hgncId"))) {
                rows.add(row.deepCopy());break;
            }
        return result;
    }

    /** An offline inventory only. Every retained target still passes the normal source worker. */
    public ObjectNode planLocalReview(Map<Pp1LiteratureAttempt, Pp1LiteraturePreparation> targets) {
        var input = json.createObjectNode().put("schemaVersion","pp1-local-review-input-v1");
        var rows = input.putArray("attempts");
        targets.forEach((key, material) -> {
            var row=rows.addObject().put("candidateId",key.candidateId()).put("hypothesisId",key.hypothesisId());
            row.set("target",material.target());
            row.set("selection",material.binding().path("selection"));
        });
        var plan=json.createObjectNode().put("schemaVersion","pp1-local-review-plan-v1");
        var pending=plan.putArray("attempts");
        if(rows.isEmpty()) return plan;
        Path folder=null;
        try {
            var c=configuration();
            Path project=Path.of(c.path("projectRoot").asText()).toRealPath();
            String configPin=FamilyDeNovoJson.sha256(read(config,16384));
            Path base=local(c,"work").resolve("pp1-local-review");
            Files.createDirectories(base);
            folder=Files.createTempDirectory(base,"read-");
            Path request=folder.resolve("request.json"), output=folder.resolve("result.json");
            byte[] bytes=json.writeValueAsBytes(input);
            Files.write(request,bytes,StandardOpenOption.CREATE_NEW);
            var process=runner.run(List.of(local(c,"python").toString(),"-E","-s","-B",
                    project.resolve("scripts/denovo/plan_pp1_local_review.py").toString(),"--config",config.toString(),
                    "--request",request.toString(),"--output",output.toString()),project,java.time.Duration.ofSeconds(45));
            if(!process.isSuccess()) throw new IOException("PP1_LOCAL_REVIEW_WORKER_UNAVAILABLE");
            var result=json.readTree(read(output,262144));
            if(result==null || !configPin.equals(result.path("workflowConfigSha256").asText())
                    || !configPin.equals(FamilyDeNovoJson.sha256(read(config,16384)))
                    || !FamilyDeNovoJson.sha256(bytes).equals(result.path("requestSha256").asText())
                    || !result.path("rows").isArray() || result.path("rows").size()!=rows.size())
                throw new IOException("PP1_LOCAL_REVIEW_RESULT_INVALID");
            var seen=new java.util.HashSet<Pp1LiteratureAttempt>();
            int hits=0;
            for(var row:result.path("rows")) {
                var key=new Pp1LiteratureAttempt(row.path("candidateId").asText(),row.path("hypothesisId").asText());
                if(!seen.add(key) || !targets.containsKey(key) || !row.path("needsWorker").isBoolean()
                        || !row.path("localMatch").isBoolean() || row.path("localMatch").asBoolean() && !row.path("needsWorker").asBoolean())
                    throw new IOException("PP1_LOCAL_REVIEW_RESULT_INVALID");
                if(row.path("localMatch").asBoolean()) hits++;
                if(row.path("needsWorker").asBoolean()) pending.addObject().put("candidateId",key.candidateId()).put("hypothesisId",key.hypothesisId());
            }
            plan.put("localMatches",hits).put("offlineMisses",rows.size()-pending.size());
        } catch(IOException | IllegalArgumentException unavailable) {
            pending.removeAll();
            for(var key:targets.keySet()) pending.addObject().put("candidateId",key.candidateId()).put("hypothesisId",key.hypothesisId());
            plan.put("reasonCode","PP1_LOCAL_REVIEW_UNAVAILABLE");
        } finally {
            if(folder!=null) try {
                Files.deleteIfExists(folder.resolve("request.json"));
                Files.deleteIfExists(folder.resolve("result.json"));
                Files.deleteIfExists(folder);
            } catch(IOException ignored) { /* Private scratch is retained on cleanup failure. */ }
        }
        return plan;
    }
    Pp1LiteratureJobs(Path config, DeNovoSourceTasks tasks, ExternalProcessRunner runner) {
        this.config = config.toAbsolutePath().normalize(); this.tasks = tasks; this.runner = runner;
    }
    private ObjectNode configuration() throws IOException {
        var c = json.readTree(read(config, 16384));
        if (!(c instanceof ObjectNode object) || !"denovo-public-literature-workflow-v1".equals(c.path("schemaVersion").asText())
                || !c.path("enabled").asBoolean(false)) throw new IOException("PP1_WORKFLOW_DISABLED");
        return object;
    }
    private Path local(ObjectNode c, String field) throws IOException {
        Path root = Path.of(c.path("projectRoot").asText()).toRealPath();
        String name = c.path(field).asText();
        Path value = root.resolve(name).normalize();
        if (name.isBlank() || Path.of(name).isAbsolute() || !value.startsWith(root.resolve("local")))
            throw new IOException("PP1_PATH_OUTSIDE_LOCAL");
        Path existing = value;
        while (!Files.exists(existing) && existing.getParent() != null) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(root.resolve("local").toRealPath())) throw new IOException("PP1_PATH_OUTSIDE_LOCAL");
        return value;
    }
    private Path root(ObjectNode c, String analysis) throws IOException {
        if (analysis == null || analysis.isBlank()) throw new IOException("PP1_ANALYSIS_REQUIRED");
        return local(c, "work").resolve("pp1-paper-tasks")
                .resolve(FamilyDeNovoJson.sha256(analysis.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Avoid repeating two 64-character identities in deeply nested Python
     * artifact paths on Windows. The complete task hash still binds analysis,
     * request, configuration and implementation; nothing is truncated. */
    private Path paper(ObjectNode c, String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("PP1_TASK_ID_INVALID");
        return local(c, "work").resolve("pp1-artifacts").resolve(id).resolve("paper");
    }
    private ObjectNode implementation(ObjectNode c) throws IOException {
        Path scripts = Path.of(c.path("projectRoot").asText()).toRealPath().resolve("scripts/denovo");
        var pins = json.createObjectNode();
        try (var paths = Files.list(scripts)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().endsWith(".py")
                    && !p.getFileName().toString().startsWith("test_")).sorted().toList())
                pins.put(p.getFileName().toString(), FamilyDeNovoJson.sha256(read(p, 524288)));
        }
        pins.put("bundle_replay_profiles.json", FamilyDeNovoJson.sha256(read(scripts.resolve("bundle_replay_profiles.json"), 524288)));
        if (c.hasNonNull("library")) {
            Path reviewIndex = local(c, "library").resolve("manual-reviews/index.json");
            if (Files.isRegularFile(reviewIndex))
                pins.put("manualLiteratureReviewIndexSha256", FamilyDeNovoJson.sha256(read(reviewIndex, 1048576)));
        }
        Path coreProfiles=scripts.resolve("core_review_replay_profiles.json");
        if(Files.isRegularFile(coreProfiles))pins.put("core_review_replay_profiles.json",FamilyDeNovoJson.sha256(read(coreProfiles,524288)));
        if ("PUBLISHED_CSPEC".equals(c.at("/pp1Knowledge/kind").asText())) {
            for (String relative : PUBLISHED_SOURCE_DEPENDENCIES) {
                Path p = scripts.getParent().resolve(relative).toRealPath();
                if (!p.startsWith(scripts.getParent().toRealPath())) throw new IOException("PP1_SOURCE_CODE_OUTSIDE_PROJECT");
                pins.put("scripts/" + relative, FamilyDeNovoJson.sha256(read(p, 524288)));
            }
        }
        return pins;
    }

    static final List<String> PUBLISHED_SOURCE_DEPENDENCIES = List.of(
            "acmg-generalization/bs1_expert_batch.py", "acmg-generalization/bs1_expert_interval.py",
            "acmg-generalization/bs1_knowledge.py", "acmg-generalization/build_transcript_model.py",
            "acmg-generalization/cspec_ba1_semantics.py", "acmg-generalization/fetch_source.py",
            "acmg-generalization/import_cspec_catalog.py", "acmg-generalization/normalize_public_panel.py",
            "e4_a1a/prepare_binding_resources.py", "pvs1/assemble_materials.py",
            "pvs1/prepare_applicability_sources.py", "pvs1/prepare_g2p_mechanisms.py");

    public ObjectNode startPrepared(String analysis, ObjectNode binding, ObjectNode target, String pmcid, String bundlePin) throws IOException {
        return startPrepared(analysis, binding, target, pmcid, bundlePin, null);
    }
    public ObjectNode startPrepared(String analysis, ObjectNode binding, ObjectNode target, String pmcid, String bundlePin, ObjectNode coverage) throws IOException {
        var request = json.createObjectNode().put("schemaVersion", "pp1-public-paper-task-v1")
                .put("selectionSha256", binding.at("/selection/selectionKey").asText())
                .put("pmcid", pmcid).put("bundleSha256", bundlePin);
        request.set("analysisBinding", binding.deepCopy()); request.set("target", target.deepCopy());
        if (coverage != null) request.set("searchAliasCoverage", coverage.deepCopy());
        return start(analysis, request);
    }
    public ObjectNode startDiscovery(String analysis, ObjectNode binding, ObjectNode target, String parentId) throws IOException {
        return startDiscovery(analysis, binding, target, parentId, null);
    }
    public ObjectNode startDiscovery(String analysis, ObjectNode binding, ObjectNode target, String parentId, ObjectNode coverage) throws IOException {
        var request=json.createObjectNode().put("schemaVersion","pp1-public-discovery-task-v1")
                .put("selectionSha256",binding.at("/selection/selectionKey").asText());
        request.set("analysisBinding",binding.deepCopy());request.set("target",target.deepCopy());
        if (coverage != null) request.set("searchAliasCoverage", coverage.deepCopy());
        if(parentId!=null) {
            if(!parentId.matches("[a-f0-9]{64}"))throw new IOException("PP1_CONTINUATION_ID_INVALID");
            var c=configuration();Path directory=root(c,analysis),folder=directory.resolve(parentId);
            var stored=tasks.read(directory,parentId);byte[] raw=stored.result();
            if(!"READY".equals(stored.status().path("status").asText()) || raw==null)throw new IOException("PP1_CONTINUATION_NOT_READY");
            byte[] identityRaw=read(folder.resolve("identity.json"),1048576);
            if(!parentId.equals(FamilyDeNovoJson.sha256(identityRaw)))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
            var identity=json.readTree(identityRaw);var old=identity.path("request");var result=json.readTree(raw);
            if ("pp1-public-paper-task-v1".equals(old.path("schemaVersion").asText()))
                return continueProcessing(analysis,binding,target,parentId,coverage);
            if(!VERSION.equals(identity.path("version").asText()))throw new IOException("PP1_TASK_VERSION_STALE");
            if(!old.path("analysisBinding").equals(binding) || !old.path("target").equals(target)
                    || !old.path("searchAliasCoverage").equals(request.path("searchAliasCoverage")))
                throw new IOException("PP1_CONTINUATION_SCOPE_OR_STATE");
            var checked=discoveryCheckpoint(c,analysis,parentId,identity,result);
            Path project=Path.of(c.path("projectRoot").asText()).toRealPath();
            request.putObject("continuation").put("parentTaskId",parentId).put("parentResultSha256",FamilyDeNovoJson.sha256(raw))
                    .put("stateSha256",checked.statePin()).put("statePath",project.relativize(checked.state()).toString().replace('\\','/'))
                    .put("manifestPath",project.relativize(checked.manifest()).toString().replace('\\','/')).put("manifestSha256",checked.manifestPin());
        }
        return start(analysis,request);
    }

    private record DiscoveryCheckpoint(int attempt, Path state, String statePin, Path manifest, String manifestPin) { }

    /** Current server authority for a discovery continuation. The persisted
     * resume hint alone never establishes pending work or paid-call safety. */
    private DiscoveryCheckpoint discoveryCheckpoint(ObjectNode c,String analysis,String id,JsonNode identity,JsonNode result) throws IOException {
        if(!VERSION.equals(identity.path("version").asText()))throw new IOException("PP1_TASK_VERSION_STALE");
        var request=identity.path("request");
        if(!analysis.equals(identity.path("analysisId").asText())
                || !"pp1-public-discovery-task-v1".equals(request.path("schemaVersion").asText()))
            throw new IOException("PP1_CONTINUATION_SCOPE_OR_STATE");
        if(!(identity.path("implementation") instanceof ObjectNode pins))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
        verifyConfiguration(c,identity.path("configurationSha256").asText(),identity.path("modelConfigurationSha256").asText(),pins);
        validateResult(request,result,identity.path("requestSha256").asText(),identity.path("configurationSha256").asText());
        if(reconciliationRequired(result))throw new IOException("PP1_MODEL_BUDGET_RECONCILIATION_REQUIRED");
        int attempt=identity.path("discoveryGeneration").asInt(1);
        if(identity.has("discoveryGeneration") && (!identity.path("discoveryGeneration").isIntegralNumber() || attempt<1))
            throw new IOException("PP1_DISCOVERY_GENERATION_INVALID");
        if(attempt>=MAX_DISCOVERY_ATTEMPTS)throw new IOException("PP1_DISCOVERY_CONTINUATION_LIMIT");
        Path state=paper(c,id).resolve("discovery-state.json"),manifest=paper(c,id).resolve("artifacts.json");
        byte[] stateRaw=read(state,64*1024*1024),manifestRaw=read(manifest,65536);
        String statePin=FamilyDeNovoJson.sha256(stateRaw),manifestPin=FamilyDeNovoJson.sha256(manifestRaw);
        if(!statePin.equals(result.at("/discoveryState/sha256").asText())
                || !manifestPin.equals(result.path("artifactManifestSha256").asText()))throw new IOException("PP1_CONTINUATION_BYTES_CHANGED");
        var persisted=json.readTree(stateRaw);var artifacts=json.readTree(manifestRaw);
        var checkpoint=persisted.path("checkpoint");var pending=persisted.path("pendingCandidates");var queries=checkpoint.path("queries");
        if(!"denovo-discovery-batch-v1".equals(persisted.path("schemaVersion").asText())
                || !"LITVAR_ONLY".equals(persisted.path("searchMode").asText())
                || !"LITVAR_ONLY".equals(checkpoint.path("searchMode").asText())
                || !persisted.path("discoveryComplete").isBoolean() || !pending.isArray() || pending.size()>200
                || !persisted.path("attemptedPaperKeys").isArray() || persisted.path("attemptedPaperKeys").size()>100000
                || !queries.isArray() || !checkpoint.path("deferredCandidates").isArray()
                || !artifacts.path("papers").isArray() || artifacts.path("papers").size()>8
                || !discoverySelectionPin(request).equals(persisted.path("selectionPin").asText()))
            throw new IOException("PP1_DISCOVERY_STATE_IDENTITY_INVALID");
        for(var candidate:pending)if(!candidate.isObject())throw new IOException("PP1_DISCOVERY_STATE_IDENTITY_INVALID");
        for(String name:DISCOVERY_DEPENDENCIES)if(!pins.path(name).isTextual()
                || !pins.path(name).equals(checkpoint.path("implementation").path(name)))
            throw new IOException("PP1_DISCOVERY_STATE_IMPLEMENTATION_CHANGED");
        int unfinished=0;
        for(var query:queries) {
            if(!query.isObject() || !query.path("status").isTextual()
                    || query.has("executionComplete") && !query.path("executionComplete").isBoolean())
                throw new IOException("PP1_DISCOVERY_STATE_IDENTITY_INVALID");
            if(!query.path("executionComplete").asBoolean() && !Set.of("SKIPPED_PRECISE_HIT","BLOCKED_PRECISE_INCOMPLETE")
                    .contains(query.path("status").asText()))unfinished++;
        }
        var summary=result.path("discoveryState");
        if(!summary.path("resumeAvailable").isBoolean() || !summary.path("resumeAvailable").asBoolean()
                || !summary.path("pendingPapers").isIntegralNumber() || summary.path("pendingPapers").asInt(-1)!=pending.size()
                || !summary.path("pendingQueries").isIntegralNumber() || summary.path("pendingQueries").asInt(-1)!=unfinished
                || !persisted.at("/summary/pendingQueries").isIntegralNumber() || persisted.at("/summary/pendingQueries").asInt(-1)!=unfinished)
            throw new IOException("PP1_NO_PENDING_DISCOVERY_WORK");
        boolean unfinishedSearch=!persisted.path("discoveryComplete").asBoolean()
                && (unfinished>0 || !checkpoint.path("deferredCandidates").isEmpty());
        if(pending.isEmpty() && !unfinishedSearch)throw new IOException("PP1_NO_PENDING_DISCOVERY_WORK");
        return new DiscoveryCheckpoint(attempt,state,statePin,manifest,manifestPin);
    }

    private String discoverySelectionPin(JsonNode request) {
        var selection=json.createObjectNode();selection.putArray("genes").add(request.at("/target/geneSymbol"));
        var target=((ObjectNode)request.path("target")).deepCopy();var allele=target.putObject("allele");
        allele.set("genomeAssembly",request.at("/analysisBinding/selection/allele/assembly"));
        allele.set("chromosome",request.at("/analysisBinding/selection/allele/contig"));
        for(String key:List.of("position","reference","alternate"))allele.set(key,request.at("/analysisBinding/selection/allele/"+key));
        selection.putArray("targets").add(target);return canonical.canonicalSha256(selection);
    }

    static boolean reconciliationRequired(JsonNode result) {
        for(String path:List.of("/recovery","/reading/recovery","/readingState/recovery","/readingState/sourceExecutionRecovery")) {
            var recovery=result.at(path);
            if(recovery.path("sharedBudgetReconciliationRequired").asBoolean()
                    || "EXECUTION_REVIEW_REQUIRED".equals(recovery.path("status").asText()))return true;
        }
        if("CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT".equals(result.path("callAccountingStatus").asText())
                || "CHECK_SHARED_LEDGER".equals(result.at("/readingLoop/failedAttemptAccounting").asText())
                || "FAILED_OR_UNKNOWN_RESERVATION_RETAINED".equals(result.path("modelChargeStatus").asText()))return true;
        if(result.path("stopReason").asText().startsWith("DENOVO_MODEL_")
                || "PP1_READING_EXECUTION_FAILED".equals(result.path("stopReason").asText()))return true;
        for(var issue:result.path("issues"))if(issue.asText().startsWith("DENOVO_MODEL_")
                || "PP1_READING_EXECUTION_FAILED".equals(issue.asText()))return true;
        for(var paper:result.path("papers"))if(reconciliationRequired(paper))return true;
        return false;
    }

    /** Explicit continuation of one immutable fixed-paper task. It never changes
     * a saved evaluation, reuses its score, or retries an unreconciled model call. */
    public ObjectNode continueProcessing(String analysis,ObjectNode binding,ObjectNode target,String parentId,
            ObjectNode coverage) throws IOException {
        if(parentId==null || !parentId.matches("[a-f0-9]{64}"))throw new IOException("PP1_CONTINUATION_ID_INVALID");
        var c=configuration();Path directory=root(c,analysis),folder=directory.resolve(parentId);
        var stored=tasks.read(directory,parentId);byte[] raw=stored.result();
        if(!"READY".equals(stored.status().path("status").asText()) || raw==null)throw new IOException("PP1_CONTINUATION_NOT_READY");
        byte[] identityRaw=read(folder.resolve("identity.json"),1048576);
        if(!parentId.equals(FamilyDeNovoJson.sha256(identityRaw)))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
        var identity=json.readTree(identityRaw);var old=identity.path("request");var result=json.readTree(raw);
        var wantedCoverage=coverage==null?json.missingNode():coverage;
        if(!VERSION.equals(identity.path("version").asText()) || !analysis.equals(identity.path("analysisId").asText())
                || !old.path("analysisBinding").equals(binding) || !old.path("target").equals(target)
                || !old.path("searchAliasCoverage").equals(wantedCoverage)
                || !"pp1-public-paper-task-v1".equals(old.path("schemaVersion").asText())
                || !processingContinuationAvailable(result))throw new IOException("PP1_CONTINUATION_SCOPE_OR_STATE");
        if(identity.path("processingGeneration").asInt(1)>=MAX_PROCESSING_ATTEMPTS)
            throw new IOException("PP1_PROCESSING_CONTINUATION_LIMIT");
        if(!identity.path("implementation").isObject())throw new IOException("PP1_TASK_IDENTITY_CHANGED");
        verifyFrozenConfiguration(c,identity.path("configurationSha256").asText(),identity.path("modelConfigurationSha256").asText());
        validateResult(old,result,identity.path("requestSha256").asText(),identity.path("configurationSha256").asText());
        byte[] manifest=read(paper(c,parentId).resolve("artifacts.json"),65536);
        if(!result.path("artifactManifestSha256").asText().equals(FamilyDeNovoJson.sha256(manifest)))
            throw new IOException("PP1_CONTINUATION_BYTES_CHANGED");
        var request=((ObjectNode)old).deepCopy();request.remove("processingContinuation");
        request.putObject("processingContinuation").put("parentTaskId",parentId)
                .put("parentResultSha256",FamilyDeNovoJson.sha256(raw));
        return start(analysis,request);
    }

    static boolean processingContinuationAvailable(JsonNode result) {
        if(!"pp1-paper-process-v1".equals(result.path("schemaVersion").asText()))return false;
        if(reconciliationRequired(result))return false;
        // A failed paid attempt remains under the shared ledger's stop and
        // reconciliation rules; it is not a signal for automatic retry.
        for(var issue:result.path("issues"))if(issue.asText().startsWith("DENOVO_MODEL_"))return false;
        String stop=result.at("/reading/stopReason").asText(result.path("stopReason").asText());
        if(Set.of("PP1_SOURCE_FILE_UNAVAILABLE","PP1_SOURCE_IO_FAILED").contains(result.path("stopReason").asText()))
            return repairedSourcePostprocessingAvailable(result);
        var resumable=Set.of("PP1_MODEL_DISABLED_CACHE_MISSING","PP1_CALL_LIMIT","PP1_TIME_LIMIT","PP1_USER_CANCELLED",
                "PP1_READING_MODEL_DISABLED_CACHE_MISSING","PP1_READING_CALL_LIMIT","PP1_READING_TIME_LIMIT",
                "PP1_READING_CANCELLED","PP1_PEDIGREE_MODEL_DISABLED_CACHE_MISSING","PP1_PEDIGREE_PAIR_CALL_CAPACITY",
                "PP1_PEDIGREE_PAIR_TIME_REQUIRED","CALL_LIMIT","ROUND_LIMIT","TIME_LIMIT","USER_CANCELLED");
        if(resumable.contains(stop))return true;
        for(var issue:result.path("issues"))if(resumable.contains(issue.asText()))return true;
        return false;
    }
    static boolean repairedSourcePostprocessingAvailable(JsonNode result) {
        var state=result.path("readingState");var checkpoint=state.path("checkpoint");var pins=checkpoint.path("acceptedArtifactSha256s");
        if(!Set.of("PEDIGREE_CHECKPOINT_CAPTURE","REVIEW_CACHE_REGISTRATION","PUBLIC_SOURCE_PROJECTION").contains(state.path("stage").asText())
                || !checkpoint.path("pmcid").equals(result.path("pmcid")) || !checkpoint.path("bundleSha256").equals(result.path("bundleSha256"))
                || !checkpoint.path("pmcid").asText().matches("PMC[1-9][0-9]*") || !checkpoint.path("bundleSha256").asText().matches("[a-f0-9]{64}")
                || !pins.isArray() || pins.isEmpty() || pins.size()>32
                || state.at("/available/acceptedReviewArtifacts").asInt(-1)!=pins.size())return false;
        var unique=new java.util.HashSet<String>();
        for(var pin:pins)if(!pin.isTextual() || !pin.asText().matches("[a-f0-9]{64}") || !unique.add(pin.asText()))return false;
        return true;
    }
    ObjectNode start(String analysis, ObjectNode request) throws IOException {
        var c = configuration(); var binding = request.path("analysisBinding");
        var fields = new java.util.HashSet<String>(); request.fieldNames().forEachRemaining(fields::add);
        boolean discovery="pp1-public-discovery-task-v1".equals(request.path("schemaVersion").asText());
        var expected=new java.util.HashSet<>(Set.of("schemaVersion","selectionSha256","target","analysisBinding"));
        if (request.has("searchAliasCoverage")) expected.add("searchAliasCoverage");
        if(discovery) {
            if(request.has("continuation")) {
                expected.add("continuation");var origin=request.path("continuation");
                if(!origin.isObject() || origin.size()!=6
                        || !origin.path("parentTaskId").asText().matches("[a-f0-9]{64}")
                        || !origin.path("parentResultSha256").asText().matches("[a-f0-9]{64}"))
                    throw new IOException("PP1_DISCOVERY_CONTINUATION_INVALID");
            }
        } else {expected.add("pmcid");expected.add("bundleSha256");}
        if(request.has("processingContinuation")) {
            expected.add("processingContinuation");var parent=request.path("processingContinuation");
            if(discovery || !parent.isObject() || parent.size()!=2
                    || !parent.path("parentTaskId").asText().matches("[a-f0-9]{64}")
                    || !parent.path("parentResultSha256").asText().matches("[a-f0-9]{64}"))
                throw new IOException("PP1_PROCESSING_CONTINUATION_INVALID");
        }
        if (!fields.equals(expected)
                || !discovery && (!"pp1-public-paper-task-v1".equals(request.path("schemaVersion").asText())
                || !request.path("pmcid").asText().matches("PMC[1-9][0-9]*")
                || !request.path("bundleSha256").asText().matches("[a-f0-9]{64}"))
                || !"pp1-analysis-binding-v1".equals(binding.path("schemaVersion").asText())
                || !request.path("selectionSha256").asText().matches("[a-f0-9]{64}")
                || !request.path("selectionSha256").equals(binding.at("/selection/selectionKey"))
                || !request.path("selectionSha256").asText().equals(canonical.canonicalSha256(binding.path("identity")))
                || !request.at("/target/geneSymbol").equals(binding.at("/selection/geneSymbol")))
            throw new IOException("PP1_SERVER_REQUEST_BINDING_INVALID");
        var target = request.path("target"); var aliases = target.path("variantAliases");
        if (!target.isObject() || target.size() != 2 || !target.path("geneSymbol").asText().matches("[A-Za-z0-9_-]{1,40}")
                || !aliases.isArray() || aliases.isEmpty() || aliases.size() > 20)
            throw new IOException("PP1_PUBLIC_TARGET_REQUIRED");
        var unique = new java.util.HashSet<String>();
        for (var alias : aliases) if (!alias.isTextual() || alias.asText().isBlank() || alias.asText().length() > 200
                || !unique.add(alias.asText()) || alias.asText().chars().anyMatch(x -> x < 32))
            throw new IOException("PP1_PUBLIC_TARGET_REQUIRED");
        if (request.has("searchAliasCoverage")) {
            var coverage = request.path("searchAliasCoverage");
            var deferred = coverage.path("deferredAliases");
            if (!"PP1_MANE_CANONICAL_FIRST_MAX20_V1".equals(coverage.path("policy").asText())
                    || !coverage.path("retrievalOnly").asBoolean() || !deferred.isArray()
                    || coverage.path("selectedAliases").asInt(-1) != aliases.size()
                    || coverage.path("omittedAliases").asInt(-1) != deferred.size()
                    || coverage.path("availableAliases").asInt(-1) != aliases.size() + deferred.size())
                throw new IOException("PP1_SEARCH_ALIAS_COVERAGE_INVALID");
            for (var alias : deferred) if (!alias.isTextual() || alias.asText().isBlank() || alias.asText().length() > 200
                    || !unique.add(alias.asText()) || alias.asText().chars().anyMatch(x -> x < 32))
                throw new IOException("PP1_SEARCH_ALIAS_COVERAGE_INVALID");
        }
        Path project = Path.of(c.path("projectRoot").asText()).toRealPath();
        var pins = implementation(c); byte[] input = json.writeValueAsBytes(request);
        if (input.length > 262144) throw new IOException("PP1_REQUEST_SIZE_LIMIT");
        String inputPin = FamilyDeNovoJson.sha256(input), configPin = FamilyDeNovoJson.sha256(read(config, 16384)),
                modelPin = FamilyDeNovoJson.sha256(read(local(c, "modelConfig"), 16384));
        var identity = json.createObjectNode().put("version", VERSION).put("adapterVersion",ADAPTER_VERSION).put("analysisId", analysis)
                .put("requestSha256", inputPin).put("configurationSha256", configPin).put("modelConfigurationSha256", modelPin);
        identity.set("request", request.deepCopy()); identity.set("implementation", pins);
        if(request.has("continuation")) {
            String parent=request.at("/continuation/parentTaskId").asText();
            byte[] previousIdentity=read(root(c,analysis).resolve(parent).resolve("identity.json"),1048576);
            if(!parent.equals(FamilyDeNovoJson.sha256(previousIdentity)))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
            var previous=json.readTree(previousIdentity);var stored=tasks.read(root(c,analysis),parent);byte[] body=stored.result();
            if(!"READY".equals(stored.status().path("status").asText()) || body==null
                    || !request.at("/continuation/parentResultSha256").asText().equals(FamilyDeNovoJson.sha256(body)))
                throw new IOException("PP1_DISCOVERY_CONTINUATION_INVALID");
            var previousScope=previous.path("request").deepCopy();var wantedScope=request.deepCopy();
            if(!(previousScope instanceof ObjectNode oldRequest))throw new IOException("PP1_DISCOVERY_CONTINUATION_INVALID");
            oldRequest.remove("continuation");wantedScope.remove("continuation");
            if(!oldRequest.equals(wantedScope))throw new IOException("PP1_CONTINUATION_SCOPE_OR_STATE");
            var checked=discoveryCheckpoint(c,analysis,parent,previous,json.readTree(body));var origin=request.path("continuation");
            if(!origin.path("stateSha256").asText().equals(checked.statePin())
                    || !origin.path("manifestSha256").asText().equals(checked.manifestPin())
                    || !origin.path("statePath").asText().equals(project.relativize(checked.state()).toString().replace('\\','/'))
                    || !origin.path("manifestPath").asText().equals(project.relativize(checked.manifest()).toString().replace('\\','/')))
                throw new IOException("PP1_DISCOVERY_CONTINUATION_INVALID");
            identity.put("discoveryGeneration",checked.attempt()+1);
        }
        if(request.has("processingContinuation")) {
            String parent=request.at("/processingContinuation/parentTaskId").asText();
            byte[] parentIdentity=read(root(c,analysis).resolve(parent).resolve("identity.json"),1048576);
            if(!parent.equals(FamilyDeNovoJson.sha256(parentIdentity)))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
            var previous=json.readTree(parentIdentity);int generation=previous.path("processingGeneration").asInt(1)+1;
            var stored=tasks.read(root(c,analysis),parent);byte[] previousResult=stored.result();
            if(!(previous.path("request") instanceof ObjectNode previousRequest))throw new IOException("PP1_PROCESSING_CONTINUATION_INVALID");
            var previousScope=previousRequest.deepCopy();previousScope.remove("processingContinuation");
            var wantedScope=request.deepCopy();wantedScope.remove("processingContinuation");
            if(!"READY".equals(stored.status().path("status").asText()) || previousResult==null
                    || !request.at("/processingContinuation/parentResultSha256").asText().equals(FamilyDeNovoJson.sha256(previousResult))
                    || !previousScope.equals(wantedScope) || !VERSION.equals(previous.path("version").asText())
                    || !analysis.equals(previous.path("analysisId").asText())
                    || !configPin.equals(previous.path("configurationSha256").asText())
                    || !modelPin.equals(previous.path("modelConfigurationSha256").asText())
                    || generation>MAX_PROCESSING_ATTEMPTS)throw new IOException("PP1_PROCESSING_CONTINUATION_INVALID");
            var priorResult=json.readTree(previousResult);
            if(reconciliationRequired(priorResult))throw new IOException("PP1_MODEL_BUDGET_RECONCILIATION_REQUIRED");
            if(!processingContinuationAvailable(priorResult))throw new IOException("PP1_CONTINUATION_SCOPE_OR_STATE");
            identity.put("processingGeneration",generation);
            if(!pins.equals(previous.path("implementation")) || !ADAPTER_VERSION.equals(previous.path("adapterVersion").asText())) {
                if(!previous.path("implementation").isObject())throw new IOException("PP1_TASK_IDENTITY_CHANGED");
                identity.putObject("sourceReassembly").put("method","CURRENT_QUALIFICATION_OVER_PINNED_SOURCE_ARTIFACTS")
                        .put("parentImplementationSha256",canonical.canonicalSha256(previous.path("implementation")))
                        .put("currentImplementationSha256",canonical.canonicalSha256(pins))
                        .put("parentAdapterVersion",previous.path("adapterVersion").asText("LEGACY_UNDECLARED"))
                        .put("currentAdapterVersion",ADAPTER_VERSION).put("savedScoreAdopted",false);
            }
        }
        byte[] identityRaw = json.writeValueAsBytes(identity); String id = FamilyDeNovoJson.sha256(identityRaw);
        Path directory = root(c, analysis);
        var started = tasks.start(directory, identityRaw, remaining -> {
            Path folder = directory.resolve(id), in = folder.resolve("request.json"), out = paper(c,id);
            String stage="DEPENDENCY_CHECK";
            try {
            verifyConfiguration(c, configPin, modelPin, pins);
            Files.createDirectories(out.getParent());
            Files.write(in, input, StandardOpenOption.CREATE_NEW);
            var command = List.of(local(c, "python").toString(), "-E", "-s", "-B",
                    project.resolve("scripts/denovo/run_pp1_paper_task.py").toString(), "--request", in.toString(),
                    "--request-sha256", inputPin, "--config", config.toString(), "--output", out.toString());
            stage="SOURCE_WORKER";var process = runner.run(command, project, remaining);
            if (!process.isSuccess()) throw new DeNovoSourceTasks.SourceFailure(workerFailureCode(process));
            stage="SOURCE_RESULT_READ";
            byte[] raw = read(out.resolve("result.json"), 32*1024*1024);
            var result = json.readTree(raw);
            stage="SOURCE_RESULT_BINDING";
            validateResult(request, result, inputPin, configPin);
            stage="SOURCE_ARTIFACT_MANIFEST";
            if(!result.path("artifactManifestSha256").asText().equals(FamilyDeNovoJson.sha256(read(out.resolve("artifacts.json"),65536))))
                throw new IOException("PP1_ARTIFACT_MANIFEST_CHANGED");
            stage="FINAL_DEPENDENCY_CHECK";verifyConfiguration(c, configPin, modelPin, pins);
            return raw;
            } catch(IOException failure) {
                String message=failure.getMessage();String reason=message!=null && message.matches("(?:PP1|DENOVO)_[A-Z0-9_]{1,140}")
                        ?message:"PP1_SOURCE_STAGE_IO_FAILED";
                var diagnostic=json.createObjectNode().put("schemaVersion","pp1-source-stage-failure-v1").put("taskId",id)
                        .put("requestSha256",inputPin).put("stage",stage).put("reasonCode",reason)
                        .put("exceptionType",failure.getClass().getSimpleName()).put("biologicalAbsenceInferred",false);
                try {Files.write(folder.resolve("processing-failure.json"),json.writeValueAsBytes(diagnostic),StandardOpenOption.CREATE_NEW);}
                catch(IOException unavailable) {failure.addSuppressed(unavailable);}
                if(failure instanceof DeNovoSourceTasks.SourceFailure typed)throw typed;
                throw new DeNovoSourceTasks.SourceFailure(reason);
            }
        });
        return response(started,analysis);
    }
    private void verifyConfiguration(ObjectNode c, String configPin, String modelPin, ObjectNode pins) throws IOException {
        verifyFrozenConfiguration(c,configPin,modelPin);
        if(!pins.equals(implementation(c))) throw new IOException("PP1_TASK_DEPENDENCY_CHANGED");
    }
    private void verifyFrozenConfiguration(ObjectNode c,String configPin,String modelPin) throws IOException {
        if(!configPin.equals(FamilyDeNovoJson.sha256(read(config,16384)))
                || !modelPin.equals(FamilyDeNovoJson.sha256(read(local(c,"modelConfig"),16384))))
            throw new IOException("PP1_TASK_DEPENDENCY_CHANGED");
    }
    static String workerFailureCode(ProcessResult process) {
        if(process.isTimedOut())return "PP1_WORKER_TIMEOUT";
        // Preserve only a terminal typed error code. Raw transport details,
        // paths, source text and credentials must never become a public reason.
        var code=java.util.regex.Pattern.compile("(?m)^(?:ValueError|RuntimeError): ((?:PP1|DENOVO)_[A-Z0-9_]{1,140})\\s*$").matcher(process.getOutput());
        String last=null;while(code.find())last=code.group(1);
        if(last!=null)return last;
        if(process.getOutput().contains("ModuleNotFoundError:"))return "PP1_WORKER_DEPENDENCY_UNAVAILABLE";
        if(process.getOutput().contains("FileNotFoundError:"))return "PP1_WORKER_LOCAL_RESOURCE_UNAVAILABLE";
        return "PP1_WORKER_FAILED";
    }
    private void validateResult(JsonNode request, JsonNode result, String inputPin, String configPin) throws IOException {
        if (request.has("searchAliasCoverage") && !request.path("searchAliasCoverage").equals(result.path("searchAliasCoverage")))
            throw new IOException("PP1_SEARCH_ALIAS_COVERAGE_CHANGED");
        boolean discovery="pp1-public-discovery-task-v1".equals(request.path("schemaVersion").asText());
        if (!(discovery?"pp1-discovery-process-v1":"pp1-paper-process-v1").equals(result.path("schemaVersion").asText())
                || !Set.of("PARTIAL", "EVALUATED").contains(result.path("status").asText())
                || !inputPin.equals(result.path("requestSha256").asText())
                || !configPin.equals(result.path("workflowConfigSha256").asText())
                || !canonical.canonicalSha256(request.path("analysisBinding")).equals(result.path("analysisBindingSha256").asText())
                || result.path("businessPublishable").asBoolean(true)) throw new IOException("PP1_WORKER_RESULT_INVALID");
        for (String key : discovery?List.of("selectionSha256"):List.of("pmcid", "bundleSha256", "selectionSha256"))
            if (!request.path(key).equals(result.path(key))) throw new IOException("PP1_WORKER_SOURCE_MISMATCH");
        if (!request.at("/target/geneSymbol").equals(result.at("/target/geneSymbol"))
                || !result.at("/target/variantAliases").isArray() || result.at("/target/variantAliases").isEmpty())
            throw new IOException("PP1_WORKER_TARGET_MISMATCH");
        var allowed = new java.util.HashSet<String>(); request.at("/target/variantAliases").forEach(a -> {
            String notation=publicNotation(a.asText());allowed.add(notation);
            // The paper reader may retain both a supplied versioned spelling
            // and its printed short c./p. form. Derive only from the requested
            // alias, never strip a substituted transcript/version in the result.
            var qualified=java.util.regex.Pattern.compile("^(?:N[MRP]_[0-9]+\\.[1-9][0-9]*|ENS[TP][0-9]+\\.[1-9][0-9]*):([cp]\\..+)$").matcher(notation);
            if(qualified.matches())allowed.add(qualified.group(1));
        });
        for (var a : result.at("/target/variantAliases")) if (!a.isTextual() || !allowed.contains(publicNotation(a.asText())))
            throw new IOException("PP1_WORKER_TARGET_MISMATCH");
        if(discovery) {
            if(!"LITVAR_ONLY".equals(result.path("searchPolicy").asText()) || !result.path("papers").isArray()
                    || result.path("papers").size()>8)throw new IOException("PP1_DISCOVERY_RESULT_INVALID");
            for(var paper:result.path("papers")) {
                if(!paper.path("pmcid").asText().matches("PMC[1-9][0-9]*")
                        || !paper.path("bundleSha256").asText().matches("[a-f0-9]{64}"))throw new IOException("PP1_DISCOVERY_PAPER_INVALID");
                var selected=((ObjectNode)request).deepCopy();selected.put("schemaVersion","pp1-public-paper-task-v1");
                selected.remove("searchAliasCoverage"); // Coverage belongs to discovery, not each paper.
                selected.set("pmcid",paper.path("pmcid"));selected.set("bundleSha256",paper.path("bundleSha256"));
                var p=((ObjectNode)paper).deepCopy();p.put("requestSha256",inputPin).put("workflowConfigSha256",configPin);
                p.set("selectionSha256",request.path("selectionSha256"));
                p.put("analysisBindingSha256",canonical.canonicalSha256(request.path("analysisBinding")));
                validateResult(selected,p,inputPin,configPin);
            }
        }
        if (!discovery && "EVALUATED".equals(result.path("status").asText())) {
            if (!request.at("/analysisBinding/selection").equals(result.at("/evaluation/target")))
                throw new IOException("PP1_WORKER_EVALUATION_SCOPE_MISMATCH");
            var original = request.at("/analysisBinding/ledger");
            if (!original.equals(result.at("/evaluation/ledger")))
                Pp1SelectedBatch.verifyPublishedLedger(json, original, result.path("evaluation"));
        }
    }
    private static String publicNotation(String value) {
        // Printed notation only; transcript/genomic identity stays in source qualification.
        String v=value.replaceAll("\\s+", "");
        int colon=v.indexOf(':');
        if(colon>0 && v.substring(0,colon).matches("N[MRP]_[0-9]+\\.[1-9][0-9]*|ENS[TP][0-9]+\\.[1-9][0-9]*"))
            return v.substring(0,colon+1)+publicNotation(v.substring(colon+1));
        var m=java.util.regex.Pattern.compile("p\\.?\\(([A-Za-z*]+[1-9][0-9]*[A-Za-z*=]+)\\)").matcher(v);
        return m.matches()?"p."+m.group(1):v;
    }
    public ObjectNode capture(String analysis,ObjectNode recovered) throws IOException {
        if(!recovered.path("executionSourceRecovered").asBoolean(false))throw new IOException("PP1_EXECUTION_SOURCE_REQUIRED");
        var c=configuration();Path directory=root(c,analysis), project=Path.of(c.path("projectRoot").asText()).toRealPath();
        String configPin=FamilyDeNovoJson.sha256(read(config,16384)),modelPin=FamilyDeNovoJson.sha256(read(local(c,"modelConfig"),16384));
        var pins=implementation(c);var snapshot=json.createObjectNode().put("schemaVersion","pp1-business-source-snapshot-v1").put("analysisId",analysis);
        var documents=snapshot.putArray("documents");var excluded=snapshot.putArray("excludedTasks");
        var candidates=new java.util.HashSet<String>();recovered.path("candidates").forEach(r->candidates.add(r.path("candidateId").asText()));
        long bytes=0;
        if(Files.isDirectory(directory))try(var paths=Files.list(directory)) {
            var folders=paths.filter(Files::isDirectory).filter(p->p.getFileName().toString().matches("[a-f0-9]{64}")).sorted().limit(1001).toList();
            if(folders.size()>1000)throw new IOException("PP1_TASK_INVENTORY_LIMIT");
            for(var folder:folders) {
                String id=folder.getFileName().toString();var stored=tasks.read(directory,id);
                if(!"READY".equals(stored.status().path("status").asText())) {
                    excluded.addObject().put("taskId",id).put("reasonCode",stored.status().path("reasonCode").asText("PP1_TASK_NOT_READY"));continue;
                }
                byte[] identityRaw=read(folder.resolve("identity.json"),1048576);
                if(!id.equals(FamilyDeNovoJson.sha256(identityRaw)))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
                var identity=json.readTree(identityRaw);var request=identity.path("request");
                if(!VERSION.equals(identity.path("version").asText())) {
                    excluded.addObject().put("taskId",id).put("reasonCode","PP1_TASK_VERSION_STALE");continue;
                }
                if(!analysis.equals(identity.path("analysisId").asText())
                        || !candidates.contains(request.at("/analysisBinding/identity/candidateId").asText())
                        || !configPin.equals(identity.path("configurationSha256").asText())
                        || !modelPin.equals(identity.path("modelConfigurationSha256").asText())
                        || !pins.equals(identity.path("implementation"))) {
                    excluded.addObject().put("taskId",id).put("reasonCode","PP1_TASK_SELECTION_OR_DEPENDENCY_STALE");continue;
                }
                byte[] raw=stored.result();if(raw==null)throw new IOException("PP1_READY_RESULT_MISSING");
                bytes+=raw.length;if(bytes>64L*1024*1024 || documents.size()>=32)throw new IOException("PP1_CAPTURE_DOCUMENT_LIMIT");
                var result=json.readTree(raw);validateResult(request,result,identity.path("requestSha256").asText(),configPin);
                byte[] manifest=read(paper(c,id).resolve("artifacts.json"),65536);
                if(!result.path("artifactManifestSha256").asText().equals(FamilyDeNovoJson.sha256(manifest)))throw new IOException("PP1_ARTIFACT_MANIFEST_CHANGED");
                var inventory=json.readTree(manifest);
                if("pp1-public-discovery-task-v1".equals(request.path("schemaVersion").asText())) {
                    if(!inventory.path("papers").isArray() || inventory.path("papers").size()>8)throw new IOException("PP1_ARTIFACT_MANIFEST_INVALID");
                    var seen=new java.util.HashSet<String>();
                    for(var paper:result.path("papers")) {
                        if(!seen.add(paper.path("pmcid").asText()))throw new IOException("PP1_DISCOVERY_DUPLICATE_PAPER");
                        JsonNode entry=null;
                        for(var m:inventory.path("papers"))if(m.path("pmcid").equals(paper.path("pmcid"))
                                && m.path("bundleSha256").equals(paper.path("bundleSha256")) && m.path("target").equals(paper.path("target"))) {
                            if(entry!=null)throw new IOException("PP1_ARTIFACT_MANIFEST_INVALID");entry=m;
                        }
                        var selected=((ObjectNode)request).deepCopy();selected.put("schemaVersion","pp1-public-paper-task-v1");
                        selected.set("pmcid",paper.path("pmcid"));selected.set("bundleSha256",paper.path("bundleSha256"));
                        if(entry==null && "EVALUATED".equals(paper.path("status").asText()))throw new IOException("PP1_ARTIFACT_MANIFEST_MISSING");
                        bytes+=captureDocument(documents,id,selected,paper,entry==null?json.createArrayNode():entry.path("acceptedArtifacts"),entry==null?json.createArrayNode():entry.path("pedigreeReviews"),c,project);
                    }
                } else {
                    bytes+=captureDocument(documents,id,request,result,inventory.path("acceptedArtifacts"),inventory.path("pedigreeReviews"),c,project);
                }
                if(bytes>128L*1024*1024)throw new IOException("PP1_CAPTURE_BYTES_LIMIT");
            }
        }
        verifyConfiguration(c,configPin,modelPin,pins);
        snapshot.put("snapshotSha256",canonical.canonicalSha256(snapshot));return snapshot;
    }
    private long captureDocument(com.fasterxml.jackson.databind.node.ArrayNode documents,String id,JsonNode request,
            JsonNode result,JsonNode originals,JsonNode figures,ObjectNode c,Path project) throws IOException {
        if(documents.size()>=32)throw new IOException("PP1_CAPTURE_DOCUMENT_LIMIT");
        if(!originals.isArray() || originals.size()>32)throw new IOException("PP1_ARTIFACT_MANIFEST_INVALID");
        var doc=documents.addObject().put("taskId",id).put("resultSha256",canonical.canonicalSha256(result));
        doc.set("request",request.deepCopy());doc.set("result",result.deepCopy());var artifacts=doc.putArray("artifacts");long bytes=0;
        for(var item:originals) {
            Path artifact=Path.of(item.asText()).toRealPath();
            if(!artifact.startsWith(local(c,"work").toRealPath()))throw new IOException("PP1_ARTIFACT_OUTSIDE_WORK");
            byte[] original=read(artifact,16*1024*1024);bytes+=original.length;
            if(bytes>128L*1024*1024)throw new IOException("PP1_CAPTURE_BYTES_LIMIT");
            artifacts.addObject().put("path",project.relativize(artifact).toString().replace('\\','/')).put("sha256",FamilyDeNovoJson.sha256(original));
        }
        if(!figures.isMissingNode()) {
            if(!figures.isArray() || figures.size()>8)throw new IOException("PP1_PEDIGREE_CAPTURE_LIMIT");
            var frozen=doc.putArray("pedigreeReviews");
            for(var figure:figures) {
                if(!figure.isObject() || figure.size()!=3)throw new IOException("PP1_PEDIGREE_CAPTURE_SCHEMA");
                var row=frozen.addObject();
                for(String key:List.of("source","image","pair")) {
                    var item=figure.path(key);Path p=Path.of(item.path("path").asText()).toRealPath();
                    if(!p.startsWith(local(c,"work").toRealPath()))throw new IOException("PP1_PEDIGREE_CAPTURE_PATH");
                    byte[] body=read(p,key.equals("source")?65536:4*1024*1024);bytes+=body.length;
                    String pin=FamilyDeNovoJson.sha256(body);
                    if(!pin.equals(item.path("sha256").asText()))throw new IOException("PP1_PEDIGREE_CAPTURE_CHANGED");
                    row.putObject(key).put("path",project.relativize(p).toString().replace('\\','/')).put("sha256",pin);
                }
            }
        }
        return bytes;
    }
    private ObjectNode evaluate(ObjectNode snapshot,ObjectNode binding) throws IOException {
        var c=configuration();Path project=Path.of(c.path("projectRoot").asText()).toRealPath();
        String configPin=FamilyDeNovoJson.sha256(read(config,16384)),modelPin=FamilyDeNovoJson.sha256(read(local(c,"modelConfig"),16384));
        var pins=implementation(c);Path base=local(c,"work").resolve("pp1-evaluations");Files.createDirectories(base);
        Path folder=Files.createTempDirectory(base,"evaluation-");
        var spec=json.createObjectNode().put("schemaVersion","pp1-business-evaluation-v1");
        spec.set("analysisBinding",binding);spec.set("documents",snapshot.path("documents"));
        byte[] raw=json.writeValueAsBytes(spec);String sha=FamilyDeNovoJson.sha256(raw);
        Path in=folder.resolve("request.json"),out=folder.resolve("evaluation");Files.write(in,raw,StandardOpenOption.CREATE_NEW);
        var command=List.of(local(c,"python").toString(),"-E","-s","-B",project.resolve("scripts/denovo/evaluate_pp1_snapshot.py").toString(),
                "--spec",in.toString(),"--spec-sha256",sha,"--config",config.toString(),"--output",out.toString());
        var process=runner.run(command,project,java.time.Duration.ofSeconds(60));
        if(!process.isSuccess())throw new IOException(process.isTimedOut()?"PP1_REPLAY_TIMEOUT":"PP1_REPLAY_FAILED");
        var result=json.readTree(read(out.resolve("result.json"),32*1024*1024));
        if(!(result instanceof ObjectNode object) || !sha.equals(result.path("requestSha256").asText())
                || !configPin.equals(result.path("workflowConfigSha256").asText()))throw new IOException("PP1_REPLAY_RESULT_INVALID");
        verifyConfiguration(c,configPin,modelPin,pins);return object;
    }
    public ObjectNode read(String analysis, String id) throws IOException { return response(tasks.read(root(configuration(), analysis), id),analysis); }
    public ObjectNode cancel(String analysis, String id) throws IOException { return response(tasks.cancel(root(configuration(), analysis), id),analysis); }
    public List<ObjectNode> list(String analysis) throws IOException {
        Path directory = root(configuration(), analysis);
        if (!Files.isDirectory(directory)) return List.of();
        try (var entries = Files.list(directory)) {
            var result = new ArrayList<ObjectNode>();
            for (Path p : entries.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).limit(20).toList())
                result.add(response(tasks.read(directory, p.getFileName().toString()),analysis));
            return result;
        }
    }
    private ObjectNode response(DeNovoSourceTasks.Read read,String analysis) throws IOException {
        var result = read.status().put("schemaVersion", "pp1-public-literature-task-v1").put("canRetry", false)
                .put("canContinueDiscovery",false);
        byte[] bytes = read.result();
        if (bytes != null) {
            JsonNode content=json.readTree(bytes);result.set("result",content);
            Path identityFile=root(configuration(),analysis).resolve(result.path("taskId").asText()).resolve("identity.json");
            byte[] identityRaw=read(identityFile,1048576);
            if(!result.path("taskId").asText().equals(FamilyDeNovoJson.sha256(identityRaw)))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
            var identity=json.readTree(identityRaw);int generation=identity.path("processingGeneration").asInt(1);
            result.put("sharedBudgetReconciliationRequired",reconciliationRequired(content));
            if("pp1-public-discovery-task-v1".equals(identity.at("/request/schemaVersion").asText())) {
                result.put("discoveryAttempt",identity.path("discoveryGeneration").asInt(1))
                        .put("maximumDiscoveryAttempts",MAX_DISCOVERY_ATTEMPTS);
                if("READY".equals(result.path("status").asText())) {
                    try {
                        discoveryCheckpoint(configuration(),analysis,result.path("taskId").asText(),identity,content);
                        result.put("canContinueDiscovery",true);
                    } catch(IOException blocked) {
                        String reason=blocked.getMessage();
                        result.put("discoveryContinuationBlockedReason",reason!=null && reason.matches("PP1_[A-Z0-9_]{1,140}")
                                ?reason:"PP1_DISCOVERY_CHECKPOINT_UNAVAILABLE");
                    }
                }
            }
            result.put("processingAttempt",generation).put("maximumProcessingAttempts",MAX_PROCESSING_ATTEMPTS)
                    .put("canContinueProcessing","READY".equals(result.path("status").asText())
                            && generation<MAX_PROCESSING_ATTEMPTS && processingContinuationAvailable(content));
            if(result.path("canContinueProcessing").asBoolean() && Set.of("PP1_SOURCE_FILE_UNAVAILABLE","PP1_SOURCE_IO_FAILED").contains(content.path("stopReason").asText()))
                result.put("resourceRepairRequired",true);
            if(result.path("canContinueProcessing").asBoolean()) {
                try {
                    if(!(identity.path("implementation") instanceof ObjectNode pins))throw new IOException("PP1_TASK_IDENTITY_CHANGED");
                    verifyFrozenConfiguration(configuration(),identity.path("configurationSha256").asText(),identity.path("modelConfigurationSha256").asText());
                    result.put("sourceReassemblyRequired",!pins.equals(implementation(configuration()))
                            || !ADAPTER_VERSION.equals(identity.path("adapterVersion").asText()));
                } catch(IOException stale) {
                    result.put("canContinueProcessing",false).put("processingContinuationBlockedReason","PP1_TASK_DEPENDENCY_CHANGED");
                }
            }
            if(identity.path("sourceReassembly").isObject())result.set("sourceReassembly",identity.path("sourceReassembly").deepCopy());
            if(generation>=MAX_PROCESSING_ATTEMPTS && processingContinuationAvailable(content))
                result.put("processingContinuationBlockedReason","PP1_PROCESSING_CONTINUATION_LIMIT");
            if("pp1-paper-process-v1".equals(content.path("schemaVersion").asText()) && reconciliationRequired(content))
                result.put("processingContinuationBlockedReason","PP1_MODEL_BUDGET_RECONCILIATION_REQUIRED");
        }
        return result;
    }
    private static byte[] read(Path path, int max) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(max+1);
            if (bytes.length == 0 || bytes.length > max) throw new IOException("PP1_FILE_LIMIT");
            return bytes;
        }
    }
    @Override public void close() { tasks.close(); }
}
