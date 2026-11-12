package org.nbscn.varvis.variantanalysis.controller;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.service.AcmgBackgroundSnapshot;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessWorkflows;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcmgBackgroundControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temp;

    @Test void preparationKeepsOrdinaryOptionsWhenSupplementaryKnowledgeIsUnavailable() {
        var workflows = mock(AcmgBusinessWorkflows.class);
        var options = json.createObjectNode().put("analysisId", "a").put("requestedN", 2);
        options.putArray("candidates").addObject().put("candidateId", "v1");
        when(workflows.inputOptions("a", 2, "CURRENT")).thenReturn(options);
        var controller = new AcmgBackgroundController(workflows, temp.resolve("missing.json").toString());
        var result = controller.prepare("a", 2);
        assertEquals(options, result.path("options"));
        assertEquals("AUTOMATIC_DISTINCT_CONTEXTS_V1", result.path("backgroundHandling").asText());
        assertEquals("UNAVAILABLE", result.at("/literature/availability").asText());
        assertTrue(result.path("backgroundSha256").asText().matches("[a-f0-9]{64}"));
        verify(workflows).inputOptions("a", 2, "CURRENT");
        verifyNoMoreInteractions(workflows);
    }

    @Test void invalidCountDoesNotReadOrEvaluateAnything() {
        var workflows = mock(AcmgBusinessWorkflows.class);
        var controller = new AcmgBackgroundController(workflows, "unused");
        for (int n : new int[]{0, -1, 51})
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.prepare("a", n)).getStatusCode().value());
        verifyNoInteractions(workflows);
    }

    @Test void confirmationCannotBeOmittedOrUseAnOldSource() throws Exception {
        var workflows = mock(AcmgBusinessWorkflows.class);
        var current = AcmgBackgroundSnapshot.prepare(json.createObjectNode().put("analysisId", "a"), json.createObjectNode());
        var controller = new AcmgBackgroundController(workflows, "unused") {
            @Override public ObjectNode prepare(String id, int topN) { return current; }
        };
        for (String body : new String[]{"{}", "{\"confirmed\":false,\"backgroundSha256\":\"x\",\"declaration\":{\"topN\":1}}",
                "{\"confirmed\":true,\"confirmed\":false}", "{} {}"}) {
            var req = new MockHttpServletRequest(); req.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.submit("a", req)).getStatusCode().value());
        }
        var envelope = json.createObjectNode().put("confirmed", true).put("backgroundSha256", "0".repeat(64));
        envelope.putObject("declaration").put("topN", 1);
        String staleOperation = UUID.randomUUID().toString();
        var stale = new MockHttpServletRequest(); stale.setContent(json.writeValueAsBytes(envelope));
        stale.addHeader("X-Varvis-Progress-Id", requireNonNull(staleOperation));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.submit("a", stale)).getStatusCode().value());
        var rejected = controller.progress("a", staleOperation);
        assertEquals("FAILED", rejected.path("status").asText());
        assertEquals("BACKGROUND_VERIFY", rejected.path("phase").asText());
        assertEquals("ACMG_BACKGROUND_CHANGED_RECONFIRM", rejected.path("reasonCode").asText());
        assertTrue(rejected.path("runId").isNull());
        verifyNoInteractions(workflows);
        // Only a current explicit confirmation delegates to the unchanged submission service.
        var service = mock(AcmgBusinessService.class);
        when(workflows.current()).thenReturn(service);
        var saved = json.createObjectNode().put("runId", "mock-only");
        when(service.submit(eq("a"), any(byte[].class))).thenReturn(saved);
        envelope.put("backgroundSha256", current.path("backgroundSha256").asText());
        var accepted = new MockHttpServletRequest(); accepted.setContent(json.writeValueAsBytes(envelope));
        assertEquals(saved, controller.submit("a", accepted));
        verify(service).submit("a", json.writeValueAsBytes(envelope.path("declaration")));
        // Opt-in changes only the transport shape after the same normal submit and confirmation.
        saved.putObject("localBatch"); saved.putArray("candidateIds"); saved.putArray("candidates");
        accepted.setContent(json.writeValueAsBytes(envelope)); accepted.addHeader("X-Varvis-Result-View","index");
        assertEquals("acmg-result-index-v1",controller.submit("a",accepted).path("viewKind").asText());
        assertFalse(saved.has("viewKind"));
    }

    @Test void sourceFailurePreservesTheActualBlockerAndOnlyFreshConfirmationSubmits() throws Exception {
        var workflows = mock(AcmgBusinessWorkflows.class);
        var controller = new AcmgBackgroundController(workflows, temp.resolve("missing.json").toString());
        var ready = json.createObjectNode().put("analysisId", "a").put("executionSourceRecovered", true);
        var blocked = ready.deepCopy().put("executionSourceRecovered", false);
        blocked.putArray("blockers").addObject().put("stage", "SOURCE_RECOVERY")
                .put("reasonCode", "EXECUTION_SNAPSHOT_TASK_MISMATCH");
        when(workflows.inputOptions("a", 1, "CURRENT")).thenReturn(ready, blocked, blocked, ready, ready);
        var initial = controller.prepare("a", 1);
        var envelope = json.createObjectNode().put("confirmed", true)
                .put("backgroundSha256", initial.path("backgroundSha256").asText());
        envelope.putObject("declaration").put("topN", 1);
        String operation = UUID.randomUUID().toString();
        var rejected = new MockHttpServletRequest();
        rejected.setContent(json.writeValueAsBytes(envelope));
        rejected.addHeader("X-Varvis-Progress-Id", requireNonNull(operation));
        var failure = assertThrows(ResponseStatusException.class, () -> controller.submit("a", rejected));
        assertEquals(409, failure.getStatusCode().value());
        assertEquals("EXECUTION_SNAPSHOT_TASK_MISMATCH", failure.getReason());
        var progress = controller.progress("a", operation);
        assertEquals("SOURCE_RECOVERY", progress.path("phase").asText());
        assertEquals("FAILED", progress.path("status").asText());
        assertEquals(failure.getReason(), progress.path("reasonCode").asText());
        assertTrue(progress.path("runId").isNull());
        assertEquals(progress, controller.progress("a", operation));
        assertEquals(blocked, controller.prepare("a", 1).path("options"));
        verify(workflows, never()).current();

        var service = mock(AcmgBusinessService.class);
        when(workflows.current()).thenReturn(service);
        var saved = json.createObjectNode().put("runId", "saved").put("status", "COMPLETED");
        when(service.submit(eq("a"), any(byte[].class))).thenReturn(saved);
        envelope.put("backgroundSha256", controller.prepare("a", 1).path("backgroundSha256").asText());
        var confirmed = new MockHttpServletRequest();
        confirmed.setContent(json.writeValueAsBytes(envelope));
        confirmed.addHeader("X-Varvis-Progress-Id", requireNonNull(UUID.randomUUID().toString()));
        assertEquals(saved, controller.submit("a", confirmed));
        verify(service).submit("a", json.writeValueAsBytes(envelope.path("declaration")));
    }

    @Test void snapshotIgnoresReadTimeButBindsSourceChoicesAndCount() {
        var a = json.createObjectNode().put("analysisId", "a").put("requestedN", 2).put("sourceSha256", "old");
        a.putObject("recommendation").put("generatedAt", "first");
        var literature = json.createObjectNode().put("workflowConfigSha256", "config");
        var first = AcmgBackgroundSnapshot.prepare(a.deepCopy(), literature.deepCopy());
        ((ObjectNode)a.path("recommendation")).put("generatedAt", "second");
        var second = AcmgBackgroundSnapshot.prepare(a.deepCopy(), literature.deepCopy());
        AcmgBackgroundSnapshot.verify(second, first.path("backgroundSha256").asText());
        a.put("sourceSha256", "changed");
        assertThrows(IllegalArgumentException.class, () -> AcmgBackgroundSnapshot.verify(
                AcmgBackgroundSnapshot.prepare(a, literature), first.path("backgroundSha256").asText()));
        assertThrows(IllegalArgumentException.class, () -> AcmgBackgroundSnapshot.verify(first, null));
        var changedCount = first.path("options").deepCopy(); ((ObjectNode)changedCount).put("requestedN", 3);
        assertNotEquals(first.path("backgroundSha256"), AcmgBackgroundSnapshot.prepare((ObjectNode)changedCount, literature).path("backgroundSha256"));
        literature.put("workflowConfigSha256", "new-config");
        assertNotEquals(first.path("backgroundSha256"), AcmgBackgroundSnapshot.prepare((ObjectNode)first.path("options"), literature).path("backgroundSha256"));
    }

    @Test void progressObservesTheSameSubmissionWithoutStartingAnother() throws Exception {
        var workflows = mock(AcmgBusinessWorkflows.class); var service = mock(AcmgBusinessService.class);
        when(workflows.current()).thenReturn(service);
        var current = AcmgBackgroundSnapshot.prepare(json.createObjectNode().put("analysisId", "a"), json.createObjectNode());
        var controller = new AcmgBackgroundController(workflows, "unused") {
            @Override public ObjectNode prepare(String id, int topN) { return current; }
        };
        String operation = java.util.UUID.randomUUID().toString();
        when(service.submit(eq("a"), any(byte[].class))).thenAnswer(call -> {
            org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", "v", "h", "PM4");
            var observed = controller.progress("a", operation);
            assertEquals("PM4", observed.path("criterion").asText());
            assertEquals("RUNNING", observed.path("status").asText());
            assertFalse(observed.path("executionReceipt").asBoolean());
            assertEquals("UNAVAILABLE", controller.progress("other", operation).path("status").asText());
            return json.createObjectNode().put("runId", "saved").put("status", "COMPLETED");
        });
        var envelope = json.createObjectNode().put("confirmed", true).put("backgroundSha256", current.path("backgroundSha256").asText());
        envelope.putObject("declaration").put("topN", 1);
        var request = new MockHttpServletRequest(); request.setContent(json.writeValueAsBytes(envelope));
        request.addHeader("X-Varvis-Progress-Id", requireNonNull(operation));
        assertEquals("saved", controller.submit("a", request).path("runId").asText());
        assertEquals("SAVED", controller.progress("a", operation).path("phase").asText());
        request.setContent(json.writeValueAsBytes(envelope));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.submit("a", request)).getStatusCode().value());
        verify(service, times(1)).submit(eq("a"), any(byte[].class));
    }
}
