package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual JDBC/controller persistence with synthetic analysis and prediction transports. */
class Ps1Pm5BusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    @BeforeEach void setup() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        base.publish(false, GenomeAssembly.GRCh38, null);
    }
    @AfterEach void close() { base.closeDatabase(); }
    void wire(String sourceVersion) {
        base.service = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.runtime(),
                () -> { throw new IllegalArgumentException("BA1_RUNTIME_NOT_CONFIGURED"); }, AcmgRecommendations.unavailable(base.mapper),
                () -> { throw new AssertionError("Outside-scope candidate must not query missense resources"); },
                base.mapper.createObjectNode().put("referenceEvidenceVersion", sourceVersion));
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    @Test void unchangedRequestReadsIdenticallyAfterRecreationWithoutQueriesAndSourceChangeSeparatesReuse() throws Exception {
        wire("v1"); ObjectNode first = base.postRequest(base.request); int calls = base.invocations.get();
        assertTrue(first.path("enabledCriteria").toString().contains("ACMG_AMP_2015:PS1"));
        assertTrue(first.path("enabledCriteria").toString().contains("ACMG_AMP_2015:PM5"));
        assertTrue(first.path("enabledCriteria").toString().contains("ACMG_AMP_2015:PM2"));
        assertTrue(first.path("enabledCriteria").toString().contains("ACMG_AMP_2015:PVS1"));
        assertEquals("NOT_APPLICABLE", first.at("/candidates/0/attempts/0/missenseComparison/criterionEvaluations/0/criterionApplicability/status").asText());
        assertEquals(0, first.at("/missenseCalls/provider").asInt());
        base.recreateRepositories(); wire("v1");
        var read = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, first.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(first, read); assertEquals(first, base.postRequest(base.request)); assertEquals(calls, base.invocations.get());
        wire("v2"); ObjectNode changed = base.postRequest(base.request);
        assertNotEquals(first.path("runId"), changed.path("runId"));
        assertNotEquals(first.path("requestSha256"), changed.path("requestSha256"));
        assertEquals(first, base.service.read(base.analysisId, first.path("runId").asText()));
        assertEquals(changed, base.postRequest(base.request));
    }
    @Test void openingInputOptionsDoesNotEvaluatePs1Pm5() {
        wire("v1"); var options = base.service.inputOptions(base.analysisId, 2);
        assertEquals(0, options.path("evaluatorCalls").asInt());
        assertEquals(0, base.store.runs(base.analysisId).size());
    }
}
