package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Path;
import org.nbscn.varvis.variantanalysis.provider.Pp1LiteratureJobs;
import org.nbscn.varvis.variantanalysis.service.AcmgBackgroundSnapshot;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessWorkflows;
import org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress;
import org.nbscn.varvis.variantanalysis.service.AcmgRunControl;
import org.nbscn.varvis.variantanalysis.service.AcmgSubmission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Guided CURRENT workflow; original callers and persistence format remain compatible. */
@RestController
@RequestMapping("/variant-analyses/{id}/acmg")
@ConditionalOnProperty(name="varvis.datasource.enabled", havingValue="true", matchIfMissing=true)
public class AcmgBackgroundController {
    private final AcmgBusinessWorkflows workflows;
    private final Path config;
    private final AcmgExecutionProgress progress = new AcmgExecutionProgress();
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public AcmgBackgroundController(AcmgBusinessWorkflows workflows,
            @Value("${varvis.literature-workflow-config:../local/private/config/denovo-literature-workflow/config.json}") String config) {
        this.workflows = workflows; this.config = Path.of(config);
    }

    @GetMapping("/background-options")
    public ObjectNode prepare(@PathVariable("id") String id, @RequestParam(defaultValue="20") int topN) {
        if (topN < 1 || topN > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOP_N_OUT_OF_RANGE");
        var options = workflows.inputOptions(id, topN, "CURRENT");
        ObjectNode literature;
        try (var jobs = new Pp1LiteratureJobs(config)) {
            literature = jobs.contextOptions(options);
            literature.put("availability", "AVAILABLE");
        } catch (IOException | RuntimeException error) {
            // Unavailable supplementary knowledge must not erase ordinary relationships.
            literature = json.createObjectNode();
            literature.put("availability", "UNAVAILABLE");
            literature.put("reasonCode", "LITERATURE_CONTEXT_SOURCE_UNAVAILABLE");
            literature.putArray("contexts");
        }
        return AcmgBackgroundSnapshot.prepare(options, literature);
    }

    @PostMapping(value="/confirmed-runs", consumes="application/json")
    public ObjectNode submit(@PathVariable("id") String id, HttpServletRequest request) throws IOException {
        int limit = AcmgSubmission.MAX_BYTES + 1024;
        if (request.getContentLengthLong() > limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "ACMG_REQUEST_SIZE_INVALID");
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "ACMG_REQUEST_SIZE_INVALID");
        final ObjectNode envelope;
        try {
            var parsed = json.readTree(bytes);
            if (!(parsed instanceof ObjectNode object) || object.size() != 3 ||
                    !object.path("confirmed").isBoolean() || !object.path("confirmed").asBoolean() ||
                    !object.path("backgroundSha256").isTextual() || !object.path("declaration").isObject())
                throw new IllegalArgumentException();
            envelope = object;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ACMG_BACKGROUND_CONFIRMATION_REQUIRED");
        }
        var declaration = envelope.path("declaration");
        if (!declaration.path("topN").isInt()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOP_N_OUT_OF_RANGE");
        String operation = request.getHeader("X-Varvis-Progress-Id");
        if (operation == null) return resultView(submitChecked(id, envelope),request);
        final AcmgExecutionProgress.Session session;
        try { session = progress.begin(id, operation); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.CONFLICT, invalid.getMessage()); }
        try (session) {
            try {
                var result = submitChecked(id, envelope);
                session.complete(result);
                return resultView(result,request);
            } catch (ResponseStatusException rejected) {
                session.fail(rejected.getReason());
                throw rejected;
            } catch (AcmgRunControl.Stopped stopped) {
                session.fail("ACMG_USER_STOPPED");
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ACMG_USER_STOPPED");
            } catch (IOException | RuntimeException failed) {
                session.fail(null);
                throw failed;
            }
        }
    }

    private ObjectNode resultView(ObjectNode result, HttpServletRequest request) {
        return org.nbscn.varvis.variantanalysis.service.AcmgResultViews.submissionView(result,request.getHeader("X-Varvis-Result-View"));
    }

    @GetMapping("/operations/{operationId}/progress")
    public ObjectNode progress(@PathVariable("id") String id, @PathVariable("operationId") String operationId) {
        return progress.read(id, operationId);
    }

    private ObjectNode submitChecked(String id, ObjectNode envelope) throws IOException {
        var declaration = envelope.path("declaration");
        var current = prepare(id, declaration.path("topN").asInt());
        var options = current.path("options");
        if (options.path("executionSourceRecovered").isBoolean() && !options.path("executionSourceRecovered").asBoolean())
            throw unavailableBackground(options, "SOURCE_RECOVERY", "EXECUTION_SOURCE_UNAVAILABLE");
        if (options.path("runtimeConfigured").isBoolean() && !options.path("runtimeConfigured").asBoolean())
            throw unavailableBackground(options, "RUNTIME_INITIALIZATION", "ACMG_BACKGROUND_RUNTIME_UNAVAILABLE");
        try { AcmgBackgroundSnapshot.verify(current, envelope.path("backgroundSha256").asText()); }
        catch (IllegalArgumentException changed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACMG_BACKGROUND_CHANGED_RECONFIRM");
        }
        // Existing submit independently validates choices/source identity and persists the exact
        // declaration, full context and request hash. No client facts gain new trust here.
        return workflows.current().submit(id, json.writeValueAsBytes(declaration));
    }

    private static ResponseStatusException unavailableBackground(com.fasterxml.jackson.databind.JsonNode options,
            String stage, String fallback) {
        AcmgExecutionProgress.stage(stage, null, null, null);
        for (var blocker : options.path("blockers")) {
            String reason = blocker.path("reasonCode").asText();
            if (stage.equals(blocker.path("stage").asText()) && reason.matches("[A-Z][A-Z0-9_]{1,159}"))
                return new ResponseStatusException(HttpStatus.CONFLICT, reason);
        }
        return new ResponseStatusException(HttpStatus.CONFLICT, fallback);
    }
}
