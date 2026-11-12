package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/variant-analyses/{id}/acmg/runs/{runId}/splicing-checks")
@ConditionalOnProperty(name = "varvis.datasource.enabled", havingValue = "true", matchIfMissing = true)
public class SplicingExperimentCheckController {
    private final SplicingExperimentChecks checks;
    public SplicingExperimentCheckController(SplicingExperimentChecks checks) { this.checks = checks; }
    @PostMapping public ObjectNode current(@PathVariable String id, @PathVariable String runId) { return checks.checkCurrent(id, runId); }
    @GetMapping public List<ObjectNode> list(@PathVariable String id, @PathVariable String runId) { return checks.list(id, runId); }
    @GetMapping("/{checkId}") public ObjectNode read(@PathVariable String id, @PathVariable String runId, @PathVariable String checkId) { return checks.read(id, runId, checkId); }
    @GetMapping("/{checkId}/export") public ResponseEntity<ObjectNode> export(@PathVariable String id, @PathVariable String runId, @PathVariable String checkId) {
        ObjectNode value = checks.read(id, runId, checkId);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=splicing-check-" + checkId + ".json").body(value);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> unavailable(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("status", "UNAVAILABLE", "reasonCode", java.util.Objects.toString(error.getReason(), "CHECK_UNAVAILABLE")));
    }
}
