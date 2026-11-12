package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.FunctionalExperimentReviews;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/variant-analyses/{id}/acmg/runs/{runId}/functional-reviews")
@ConditionalOnProperty(name = "varvis.datasource.enabled", havingValue = "true", matchIfMissing = true)
public class FunctionalExperimentReviewController {
    private final FunctionalExperimentReviews reviews;
    public FunctionalExperimentReviewController(FunctionalExperimentReviews reviews) { this.reviews = reviews; }
    @GetMapping public ObjectNode read(@PathVariable String id, @PathVariable String runId) { return reviews.read(id, runId); }
    @PostMapping public ObjectNode submit(@PathVariable String id, @PathVariable String runId, @RequestBody FunctionalExperimentReviews.Input input) {
        return reviews.submit(id, runId, input);
    }
    @GetMapping("/export") public ResponseEntity<ObjectNode> export(@PathVariable String id, @PathVariable String runId) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=functional-review.json").body(reviews.export(id, runId));
    }
}
