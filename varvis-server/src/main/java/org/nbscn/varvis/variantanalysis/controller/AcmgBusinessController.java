package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService;
import org.nbscn.varvis.variantanalysis.service.AcmgSubmission;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessWorkflows;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/variant-analyses/{id}")
@ConditionalOnProperty(name = "varvis.datasource.enabled", havingValue = "true", matchIfMissing = true)
public class AcmgBusinessController {
    private final AcmgBusinessService service;
    private final AcmgBusinessWorkflows workflows;
    public AcmgBusinessController(AcmgBusinessService service) { this(new AcmgBusinessWorkflows(service, null)); }
    @org.springframework.beans.factory.annotation.Autowired
    public AcmgBusinessController(AcmgBusinessWorkflows workflows) {
        this.workflows = workflows; this.service = workflows.current();
    }
    @ExceptionHandler({org.springframework.dao.QueryTimeoutException.class, org.springframework.dao.PessimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public java.util.Map<String, String> databaseBusy(RuntimeException failure) {
        return java.util.Map.of("message", "ACMG_DATABASE_BUSY");
    }
    @GetMapping("/acmg/input-options")
    public ObjectNode inputs(@PathVariable("id") String id, @RequestParam(defaultValue = "20") String topN,
            @RequestParam(defaultValue = AcmgBusinessWorkflows.CURRENT) String workflow) {
        if (!topN.matches("[1-9][0-9]{0,2}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOP_N_OUT_OF_RANGE");
        return workflows.inputOptions(id, Integer.parseInt(topN), workflow);
    }
    @PostMapping(value = "/acmg/runs", consumes = "application/json")
    public ObjectNode submit(@PathVariable("id") String id, HttpServletRequest request,
            @RequestParam(defaultValue = AcmgBusinessWorkflows.CURRENT) String workflow) throws IOException {
        // Bound actual streaming bytes as well as Content-Length, before Jackson allocates a body tree.
        if (request.getContentLengthLong() > AcmgSubmission.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "ACMG_REQUEST_SIZE_INVALID");
        byte[] bytes = request.getInputStream().readNBytes(AcmgSubmission.MAX_BYTES + 1);
        if (bytes.length > AcmgSubmission.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "ACMG_REQUEST_SIZE_INVALID");
        return workflows.select(workflow).submit(id, bytes);
    }
    @GetMapping("/acmg/runs")
    public List<ObjectNode> list(@PathVariable("id") String id) { return service.list(id); }
    @GetMapping("/acmg/runs/{runId}")
    public ObjectNode read(@PathVariable("id") String id, @PathVariable("runId") String runId,
            @RequestParam(required=false) String sourceViewSha256) {
        ObjectNode full=service.read(id, runId);
        if (sourceViewSha256!=null && !sourceViewSha256.equals(org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(full.toString())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"ACMG_RESULT_VIEW_CHANGED");
        return full;
    }
    @GetMapping("/acmg/runs/{runId}/view")
    public ObjectNode view(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return service.readView(id,runId,null,0,null);
    }
    @GetMapping("/acmg/runs/{runId}/view/background")
    public ObjectNode background(@PathVariable("id") String id, @PathVariable("runId") String runId,
            @RequestParam String candidateId, @RequestParam int backgroundIndex, @RequestParam String sourceSha256) {
        if (backgroundIndex<0 || !sourceSha256.matches("[a-f0-9]{64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ACMG_RESULT_VIEW_IDENTITY_INVALID");
        return service.readView(id,runId,candidateId,backgroundIndex,sourceSha256);
    }
    @GetMapping("/acmg/runs/{runId}/control")
    public ObjectNode controlStatus(@PathVariable("id") String id,@PathVariable("runId") String runId) { return service.controlStatus(id,runId); }
    @PostMapping("/acmg/runs/{runId}/control/{action}")
    public ObjectNode control(@PathVariable("id") String id,@PathVariable("runId") String runId,@PathVariable("action") String action) {
        return service.control(id,runId,action);
    }
    @GetMapping("/acmg/runs/{runId}/denovo-source")
    public ObjectNode deNovoSource(@PathVariable("id") String id, @PathVariable("runId") String runId,
            @RequestParam(defaultValue = AcmgBusinessWorkflows.CURRENT) String workflow) {
        return workflows.select(workflow).deNovoSourceTaskStatus(id,runId);
    }
    @GetMapping("/candidates/{candidateId}/acmg")
    public ObjectNode candidate(@PathVariable("id") String id, @PathVariable("candidateId") String candidateId,
                                @RequestParam("runId") String runId) { return service.candidate(id, candidateId, runId); }
}
