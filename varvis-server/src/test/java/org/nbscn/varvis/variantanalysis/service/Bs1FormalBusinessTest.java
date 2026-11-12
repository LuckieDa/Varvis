package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic H2/HTTP fixture. Its non-released gene backgrounds must not be silently enabled. */
class Bs1FormalBusinessTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    private Bs1AcceptanceBusinessTest acceptance;
    private Bs1BusinessIntegrationTest fixture;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessService formal;
    @BeforeEach void setup() throws Exception {
        acceptance = new Bs1AcceptanceBusinessTest(); acceptance.temporary = temporary; acceptance.setup();
        fixture = acceptance.fixture; base = acceptance.base; formal = acceptance.formal;
    }
    @AfterEach void close() { acceptance.close(); }
    @Test void formalSuccessorDoesNotReuseAcceptanceRunAndKeepsHistoricalReadOnly() throws Exception {
        var accepted = fixture.fixture.post();
        base.service = AcmgBusinessService.formalPopulation(formal, () -> Bs1Runtime.configured(base.mapper,
                Bs1Runtime.formalPolicies(base.mapper), fixture.fixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString()), null).withFormalActivation(true));
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        var options = base.service.inputOptions(base.analysisId, 2);
        assertEquals("4.0.0", options.at("/executionProfile/profileVersion").asText());
        assertTrue(options.at("/executionProfile/formalEnabled").asBoolean());
        var selections = base.request.putArray("recommendations");
        for (var row : options.path("candidates")) {
            var selected = selections.addObject(); var recommendation = row.path("recommendation");
            for (String key : new String[]{"candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"}) selected.set(key, recommendation.path(key));
            selected.putNull("stableRelationshipKey"); selected.putNull("annotationId");
        }
        var saved = fixture.fixture.post();
        assertNotEquals(accepted.path("runId"), saved.path("runId"));
        assertEquals(0, saved.at("/bs1Calls/provider").asInt());
        assertEquals(accepted, base.service.read(base.analysisId, accepted.path("runId").asText()));
        int count = Files.readAllLines(temporary.resolve("queries.txt")).size();
        assertEquals(saved, fixture.fixture.post()); assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        assertEquals(count, Files.readAllLines(temporary.resolve("queries.txt")).size());
        base.request.put("declarationVersion", "ordinary-ui-v3");
        var rejected = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(base.request)));
        assertEquals(400, rejected.getStatusCode().value());
        base.request.put("declarationVersion", AcmgBusinessRuleScope.BS1_DECLARATION);
        ((com.fasterxml.jackson.databind.node.ObjectNode) base.request.at("/recommendations/0")).put("candidateId", "wrong");
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(base.request))).getStatusCode().value());
        assertEquals(count, Files.readAllLines(temporary.resolve("queries.txt")).size());

    }
}
