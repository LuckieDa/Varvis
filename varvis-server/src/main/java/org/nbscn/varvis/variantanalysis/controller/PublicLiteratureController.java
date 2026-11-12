package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.provider.PublicLiteratureJobs;
import org.nbscn.varvis.variantanalysis.provider.Pm3LiteratureJobs;
import org.nbscn.varvis.variantanalysis.provider.Pp1LiteratureJobs;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService.Pp1LiteratureAttempt;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService.Pp1LiteraturePreparation;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessWorkflows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/variant-analyses/{id}/acmg/literature")
@ConditionalOnProperty(name="varvis.datasource.enabled",havingValue="true",matchIfMissing=true)
public class PublicLiteratureController implements AutoCloseable {
    private final AcmgBusinessWorkflows workflows;
    private final PublicLiteratureJobs jobs;
    private final Pm3LiteratureJobs pm3;
    private final Pp1LiteratureJobs pp1;
    private final org.nbscn.varvis.variantanalysis.provider.LiteratureSubmissionReceipts submissions;
    private final Map<String, Pp1BatchPlan> pp1Plans = new LinkedHashMap<>();
    private record Pp1BatchPlan(AcmgBusinessService workflow, Map<Pp1LiteratureAttempt, Pp1LiteraturePreparation> targets) { }
    public PublicLiteratureController(AcmgBusinessWorkflows workflows,
            String config) {this(workflows,config,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public PublicLiteratureController(AcmgBusinessWorkflows workflows,
            @Value("${varvis.literature-workflow-config:../local/private/config/denovo-literature-workflow/config.json}") String config,
            org.nbscn.varvis.variantanalysis.provider.Pm3LiteratureRaritySupply.Factory population) {
        this.workflows=workflows;this.jobs=new PublicLiteratureJobs(Path.of(config));this.pm3=new Pm3LiteratureJobs(Path.of(config),population);
        this.pp1=new Pp1LiteratureJobs(Path.of(config));
        this.submissions=new org.nbscn.varvis.variantanalysis.provider.LiteratureSubmissionReceipts(Path.of(config));
    }
    @GetMapping("/submissions/{operationId}")
    public ObjectNode submission(@PathVariable("id") String id,@PathVariable("operationId") String operationId) throws IOException {
        workflows.current().list(id); return submissions.read(id,operationId);
    }
    @GetMapping("/pp1/context-options")
    public ObjectNode pp1ContextOptions(@PathVariable("id") String id,@RequestParam(defaultValue="20") int topN) throws IOException {
        check(topN);return pp1.contextOptions(workflows.inputOptions(id,topN,"CURRENT"));
    }
    @PostMapping(value="/pp1/local-review",consumes="application/json")
    public ObjectNode planPp1(@PathVariable("id") String id,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        byte[] bytes=input.getInputStream().readNBytes(1024*1024+1);
        if(bytes.length>1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"LITERATURE_REQUEST_LIMIT");
        var workflow=workflows.current();
        var prepared=workflow.preparePp1LiteratureBatch(id,bytes);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var declaration=mapper.readTree(bytes);
        if (declaration == null || !declaration.isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PP1_CURRENT_DECLARATION_REQUIRED");
        var plan=pp1.planLocalReview(prepared.targets());
        String key=pp1PlanKey(id,declaration);
        synchronized(pp1Plans) {
            pp1Plans.remove(key);
            if(pp1Plans.size()>=16) pp1Plans.remove(pp1Plans.keySet().iterator().next());
            pp1Plans.put(key,new Pp1BatchPlan(workflow,prepared.targets()));
        }
        plan.put("planId",key);
        var blockers=plan.putArray("blockers");
        prepared.blockers().forEach((attempt,reason) -> blockers.addObject().put("candidateId",attempt.candidateId())
                .put("hypothesisId",attempt.hypothesisId()).put("reasonCode",reason));
        return plan;
    }

    private static String pp1PlanKey(String id, com.fasterxml.jackson.databind.JsonNode declaration) {
        return org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(id + "\n" + declaration);
    }
    @PostMapping(value="/pp1/tasks",consumes="application/json")
    public ObjectNode startPp1(@PathVariable("id") String id,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        byte[] bytes=input.getInputStream().readNBytes(1024*1024+1);
        if(bytes.length>1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"LITERATURE_REQUEST_LIMIT");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var request=mapper.readTree(bytes);var fields=new java.util.HashSet<String>();
        if(request!=null)request.fieldNames().forEachRemaining(fields::add);
        var base=java.util.Set.of("declaration","candidateId","hypothesisId","confirmPublicModel");
        var discovery=new java.util.HashSet<>(base);discovery.add("parentTaskId");
        var batch=new java.util.HashSet<>(base);batch.add("batchPlanId");
        var paper=new java.util.HashSet<>(base);paper.add("pmcid");paper.add("bundleSha256");
        if(request==null || !request.isObject()
                || !(fields.equals(base)||fields.equals(discovery)||fields.equals(paper)||fields.equals(batch))
                || !request.path("declaration").isObject() || !request.path("candidateId").isTextual()
                || request.path("candidateId").asText().isBlank() || !request.path("hypothesisId").isTextual()
                || request.path("hypothesisId").asText().isBlank() || !request.path("confirmPublicModel").isBoolean()
                || !request.path("confirmPublicModel").asBoolean()
                || fields.equals(paper) && (!request.path("pmcid").asText().matches("PMC[1-9][0-9]*")
                || !request.path("bundleSha256").asText().matches("[a-f0-9]{64}"))
                || fields.equals(batch) && !pp1PlanKey(id,request.path("declaration")).equals(request.path("batchPlanId").asText())
                || fields.equals(discovery) && !request.path("parentTaskId").asText().matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PP1_EXPLICIT_SELECTION_REQUIRED");
        return submissions.start(id,input.getHeader("X-Varvis-Submission-Id"),"pp1",bytes,() -> {
            var workflow=workflows.current();
            var key=new Pp1LiteratureAttempt(request.path("candidateId").asText(),request.path("hypothesisId").asText());
            Pp1BatchPlan plan;
            synchronized(pp1Plans) { plan=pp1Plans.get(request.path("batchPlanId").asText()); }
            var prepared=plan!=null && workflow==plan.workflow() ? plan.targets().get(key) : null;
            if(prepared==null) prepared=workflow.preparePp1Literature(id,mapper.writeValueAsBytes(request.path("declaration")),
                    key.candidateId(),key.hypothesisId());
        return fields.equals(paper)
                ? pp1.startPrepared(id,prepared.binding(),prepared.target(),request.path("pmcid").asText(),request.path("bundleSha256").asText(),prepared.aliasCoverage())
                : pp1.startDiscovery(id,prepared.binding(),prepared.target(),request.has("parentTaskId")?request.path("parentTaskId").asText():null,prepared.aliasCoverage());
        });
    }

    @GetMapping("/pp1/tasks") public List<ObjectNode> listPp1(@PathVariable("id") String id) throws IOException {
        workflows.current().list(id);return pp1.list(id);
    }
    @GetMapping("/pp1/tasks/{taskId}") public ObjectNode readPp1(@PathVariable("id") String id,@PathVariable("taskId") String taskId) throws IOException {
        workflows.current().list(id);return pp1.read(id,taskId);
    }
    @PostMapping("/pp1/tasks/{taskId}/cancel") public ObjectNode cancelPp1(@PathVariable("id") String id,@PathVariable("taskId") String taskId) throws IOException {
        workflows.current().list(id);return pp1.cancel(id,taskId);
    }
    @GetMapping("/pm3/options") public ObjectNode pm3Options(@PathVariable("id") String id,@RequestParam(defaultValue="20") int topN) throws IOException {
        check(topN);return pm3.selectionOptions(workflows.inputOptions(id,topN,"CURRENT"));
    }
    @PostMapping(value="/pm3/tasks",consumes="application/json")
    public ObjectNode startPm3(@PathVariable("id") String id,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        byte[] bytes=input.getInputStream().readNBytes(4097);
        if(bytes.length>4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"LITERATURE_REQUEST_LIMIT");
        var request=new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
        var fields=new java.util.HashSet<String>();if(request!=null) request.fieldNames().forEachRemaining(fields::add);
        boolean discovery=request!=null && !request.has("pmcid") && !request.has("bundleSha256");
        if(request==null || !request.isObject() || !fields.equals(discovery?java.util.Set.of("topN","candidateId","selectionSha256","confirmPublicModel"):java.util.Set.of("topN","candidateId","pmcid","bundleSha256","selectionSha256","confirmPublicModel"))
                || !request.path("topN").isInt() || !request.path("confirmPublicModel").isBoolean() || !request.path("confirmPublicModel").asBoolean()
                || !request.path("candidateId").isTextual() || request.path("candidateId").asText().isBlank()
                || !discovery && (!request.path("pmcid").asText().matches("PMC[1-9][0-9]*")
                || !request.path("bundleSha256").asText().matches("[a-f0-9]{64}"))
                || !request.path("selectionSha256").asText().matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PM3_EXPLICIT_SELECTION_REQUIRED");
        int topN=request.path("topN").asInt();check(topN);
        return submissions.start(id,input.getHeader("X-Varvis-Submission-Id"),"pm3",bytes,() ->
                pm3.startSelected(id,workflows.inputOptions(id,topN,"CURRENT"),request.path("selectionSha256").asText(),
                request.path("candidateId").asText(),discovery?null:request.path("pmcid").asText(),discovery?null:request.path("bundleSha256").asText()));
    }
    @GetMapping("/pm3/tasks") public List<ObjectNode> listPm3(@PathVariable("id") String id) throws IOException {
        workflows.current().list(id);return pm3.list(id);
    }
    @PostMapping("/pm3/tasks/{taskId}/cancel") public ObjectNode cancelPm3(@PathVariable("id") String id,@PathVariable("taskId") String taskId) throws IOException {
        workflows.current().list(id);return pm3.cancel(id,taskId);
    }
    @GetMapping("/pm3/tasks/{taskId}") public ObjectNode readPm3(@PathVariable("id") String id,@PathVariable("taskId") String taskId) throws IOException {
        workflows.current().list(id);return pm3.read(id,taskId);
    }
    @PostMapping(value="/pm3/tasks/{taskId}/continue",consumes="application/json")
    public ObjectNode continuePm3(@PathVariable("id") String id,@PathVariable("taskId") String parent,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        byte[] bytes=input.getInputStream().readNBytes(4097);
        if(bytes.length>4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"LITERATURE_REQUEST_LIMIT");
        var request=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
        var fields=new java.util.HashSet<String>();if(request!=null) request.fieldNames().forEachRemaining(fields::add);
        if(request==null || !request.isObject() || !fields.equals(java.util.Set.of("topN","selectionSha256","confirmPublicModel"))
                || !request.path("topN").isInt() || !request.path("confirmPublicModel").isBoolean() || !request.path("confirmPublicModel").asBoolean()
                || !request.path("selectionSha256").asText().matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PM3_EXPLICIT_SELECTION_REQUIRED");
        int topN=request.path("topN").asInt();check(topN);
        return pm3.continueDiscovery(id,workflows.inputOptions(id,topN,"CURRENT"),request.path("selectionSha256").asText(),parent);
    }
    @PostMapping(value="/local-review",consumes="application/json")
    public ObjectNode localReview(@PathVariable("id") String id,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        int limit=org.nbscn.varvis.variantanalysis.service.AcmgSubmission.MAX_BYTES;
        byte[] bytes=input.getInputStream().readNBytes(limit+1);
        if(bytes.length>limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"LITERATURE_REQUEST_LIMIT");
        return workflows.current().planDeNovoLiterature(id,bytes);
    }

    @GetMapping("/options") public ObjectNode options(@PathVariable("id") String id,@RequestParam(defaultValue="20") int topN) throws IOException {
        check(topN);return jobs.selectionOptions(workflows.inputOptions(id,topN,"CURRENT"));
    }
    @PostMapping(value="/tasks",consumes="application/json") public ObjectNode start(@PathVariable("id") String id,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        return submit(id,input,null,null,false);
    }
    @PostMapping(value="/tasks/{taskId}/papers/{pmcid}/continue",consumes="application/json")
    public ObjectNode continuePaper(@PathVariable("id") String id,@PathVariable("taskId") String parent,
            @PathVariable("pmcid") String pmcid,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        return submit(id,input,parent,pmcid,false);
    }
    @PostMapping(value="/tasks/{taskId}/discovery/continue",consumes="application/json")
    public ObjectNode continueDiscovery(@PathVariable("id") String id,@PathVariable("taskId") String parent,
            jakarta.servlet.http.HttpServletRequest input) throws IOException {
        return submit(id,input,parent,null,false);
    }
    @PostMapping(value="/tasks/{taskId}/papers/{pmcid}/accept",consumes="application/json")
    public ObjectNode acceptPaper(@PathVariable("id") String id,@PathVariable("taskId") String parent,
            @PathVariable("pmcid") String pmcid,jakarta.servlet.http.HttpServletRequest input) throws IOException {
        return submit(id,input,parent,pmcid,true);
    }
    private ObjectNode submit(String id,jakarta.servlet.http.HttpServletRequest input,String parent,String pmcid,boolean accept) throws IOException {
        byte[] bytes=input.getInputStream().readNBytes(4097);
        if(bytes.length>4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"LITERATURE_REQUEST_LIMIT");
        var request=new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
        String confirmation=accept?"confirmSourceIntake":"confirmPublicModel";
        boolean narrowed=request!=null && request.has("candidateIds");
        int fields=3+(request!=null && request.has("searchMode") && parent==null?1:0)+(narrowed?1:0);
        if(request==null || !request.isObject() || request.size()!=fields || !request.path("topN").isInt()
            || !request.path(confirmation).isBoolean() || !request.path(confirmation).asBoolean()
            || !request.path("selectionSha256").asText().matches("[a-f0-9]{64}")
            || request.has("searchMode") && (!request.path("searchMode").isTextual()
                || !java.util.Set.of("LITVAR_ONLY").contains(request.path("searchMode").asText())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"LITERATURE_EXPLICIT_CONFIRMATION_REQUIRED");
        var candidateIds=new java.util.LinkedHashSet<String>();
        if(narrowed) {
            var ids=request.path("candidateIds");
            if(parent!=null || !ids.isArray() || ids.isEmpty() || ids.size()>50)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"LITERATURE_DISCOVERY_SCOPE_INVALID");
            for(var candidate:ids) if(!candidate.isTextual() || candidate.asText().isBlank()
                    || candidate.asText().length()>200 || !candidateIds.add(candidate.asText()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"LITERATURE_DISCOVERY_SCOPE_INVALID");
        }
        int topN=request.path("topN").asInt();check(topN);
        if(accept) {
            var result=jobs.acceptPaper(id,workflows.inputOptions(id,topN,"CURRENT"),request.path("selectionSha256").asText(),parent,pmcid);
            workflows.refreshCurrentPublicSources();return result;
        }
        return parent==null ? submissions.start(id,input.getHeader("X-Varvis-Submission-Id"),"denovo",bytes,() ->
                jobs.start(id,workflows.inputOptions(id,topN,"CURRENT"),request.path("selectionSha256").asText(),
                        request.path("searchMode").asText("LITVAR_ONLY"),candidateIds))
                : pmcid==null ? jobs.continueDiscovery(id,workflows.inputOptions(id,topN,"CURRENT"),request.path("selectionSha256").asText(),parent)
                : jobs.continuePaper(id,workflows.inputOptions(id,topN,"CURRENT"),request.path("selectionSha256").asText(),parent,pmcid);
    }
    @GetMapping("/tasks") public List<ObjectNode> list(@PathVariable("id") String id) throws IOException {
        workflows.current().list(id);return jobs.list(id);
    }
    @GetMapping("/tasks/{taskId}") public ObjectNode read(@PathVariable("id") String id,@PathVariable("taskId") String taskId) throws IOException {
        workflows.current().list(id);return jobs.read(id,taskId);
    }
    @PostMapping("/tasks/{taskId}/cancel") public ObjectNode cancel(@PathVariable("id") String id,@PathVariable("taskId") String taskId) throws IOException {
        workflows.current().list(id);return jobs.cancel(id,taskId);
    }
    private static void check(int topN) {
        if(topN<1||topN>50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"TOP_N_OUT_OF_RANGE");
    }
    @ExceptionHandler(IOException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public java.util.Map<String,String> failure(IOException error) {
        var message=error.getMessage();
        return java.util.Map.of("message",message!=null && message.matches("[A-Z][A-Z0-9_]{0,160}") ? message : "LITERATURE_STORAGE_OR_CONFIGURATION_UNAVAILABLE");
    }
    @Override @jakarta.annotation.PreDestroy public void close() {jobs.close();pm3.close();pp1.close();}
}
