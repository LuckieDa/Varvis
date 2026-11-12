package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Pm6BusinessFixture.at;

/** Real HTTP/core/JDBC and micro-VCF publication; upstream scientific transport is TEST_ONLY replay. */
class Pm6BusinessIntegrationTest {
    Pm6BusinessFixture b;
    @BeforeEach void setup() throws Exception { b = new Pm6BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }

    @ParameterizedTest @ValueSource(strings = {"alpha", "beta", "half", "zero", "unknown", "source-invalid"})
    void savedResultsRetainFractionalCoreAndRebuildWithNoExtraCalls(String scene) throws Exception {
        b.start(scene.equals("beta") ? "beta" : "alpha");
        if (scene.equals("half")) b.issue("half", n -> at(n, "/observations/0/facts/clinical").put("phenotypeCategory", "CONSISTENT").put("heterogeneity", "NOT_HIGH"));
        if (scene.equals("zero")) b.issue("zero", n -> at(n, "/observations/0/facts/clinical").put("phenotypeCategory", "NOT_CONSISTENT"));
        if (scene.equals("unknown")) b.issue("unknown", n -> at(n, "/observations/0/facts/clinical/applicability").put("singleTrioScope", "UNKNOWN"));
        if (scene.equals("source-invalid")) {
            Path original = Path.of("../local/development/handoffs/PM6-denovo-core-r1-review-20260929/root-01/green-01/artifacts/source-boundary/PM6_PARENTAGE-finite-float-loader-bytes.json");
            byte[] raw = Files.readAllBytes(original);
            assertEquals("84ce52df6686c9cdfe3ae2c76f877a943092da46c1cf580272006952edbaccf9", Pm6BusinessFixture.sha(raw));
            b.saveNew("source-invalid", raw); // exact prior bytes; never fix/reissue the malformed source report
        }
        var options = b.options(); assertEquals(0, b.core.get());
        assertEquals("BOUND_NOT_EVALUATED", options.at("/pm6Preparation/status").asText());
        assertEquals(b.bundle.at("/observations/0/parentageBasis"), options.at("/pm6Preparation/parentageBasis"));
        assertEquals(b.bundle.at("/context/patientContext/affectedStatus"), options.at("/pm6Preparation/patientContext/affectedStatus"));
        var request = b.request(options); var saved = b.submit(request); b.verifyArtifact(saved);
        var core = saved.at("/candidates/0/attempts/0/pm6");
        if (scene.equals("unknown") || scene.equals("source-invalid")) {
            assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
            assertEquals(scene.equals("unknown") ? "PM6_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE" : "PM6_PARENTAGE_SOURCE_DOCUMENT_INVALID", core.at("/evaluationResult/reasonCode").asText());
            assertEquals(3, core.at("/criterionEvaluations/0").size()); assertFalse(core.has("deNovoPoints")); assertFalse(core.has("evaluation"));
        } else {
            assertEquals("COMPLETED", saved.path("status").asText());
            double points = scene.equals("beta") ? 0.25 : scene.equals("half") ? 0.5 : scene.equals("zero") ? 0 : 1;
            assertEquals(points, core.path("deNovoPoints").asDouble());
            assertEquals(points >= 0.5 ? "MET" : "NOT_MET", core.at("/evaluation/evaluationResult/status").asText());
            assertEquals(points == 1 ? "MODERATE" : points == 0.5 ? "SUPPORTING" : "NONE", core.at("/evaluation/appliedStrength").asText());
            if (points == 0.25) assertEquals("PM6_SINGLE_OBSERVATION_BELOW_SUPPORTING_THRESHOLD", core.at("/evaluation/evaluationResult/reasonCode").asText());
        }
        assertEquals(1, b.core.get()); b.rebuildAndCompare(request, saved);
        Files.move(b.material, b.temporary.resolve("retained-material.json")); b.wire(); var before = b.counts();
        assertEquals(saved, b.service.read(b.analysisId, saved.path("runId").asText())); assertEquals(saved, b.submit(request)); assertEquals(before, b.counts());
        assertEquals("PM6_MATERIAL_UNAVAILABLE", assertThrows(ResponseStatusException.class, () -> b.service.inputOptions(b.analysisId, 1)).getReason());
        var other = request.deepCopy(); at(other, "/pm6Selection").put("caseId", "other"); b.reject(other, "PM6_MATERIAL_UNAVAILABLE");
        b.env.withProperty("variant-analysis.acmg-business.pm6-bundle-sha256", requireNonNull("0".repeat(64))); b.wire(); b.reject(request, "PM6_MATERIAL_UNAVAILABLE");
        assertEquals(saved, b.service.read(b.analysisId, saved.path("runId").asText())); assertEquals(1, b.core.get());
    }

    @Test void exactSelectionStrictRequestsAndScopeIsolation() throws Exception {
        b.start("alpha"); var r = b.request(b.options());
        b.reject(r.deepCopy().put("declarationVersion", "ordinary-ui-v3"), "PM6_EXPLICIT_DECLARATION_REQUIRED");
        b.reject(r.deepCopy().put("topN", 2), "PM6_TOP_ONE_REQUIRED");
        var no = r.deepCopy(); no.remove("pm6Selection"); b.reject(no, "PM6_EXPLICIT_CONFIRMATION_REQUIRED");
        for (JsonNode flag : List.of(b.mapper.getNodeFactory().booleanNode(false), b.mapper.nullNode())) {
            var wrong = r.deepCopy(); at(wrong, "/pm6Selection").set("confirmed", flag); b.reject(wrong, "PM6_EXPLICIT_CONFIRMATION_REQUIRED");
        }
        var text = r.deepCopy(); at(text, "/pm6Selection").put("confirmed", "true"); b.reject(text, "ACMG_REQUEST_INVALID");
        for (String field : List.of("candidateId", "contextId", "observationId", "occurrenceId", "caseId", "materialBindingSha256", "preparationToken", "policyVersion")) {
            var wrong = r.deepCopy(); at(wrong, "/pm6Selection").put(field, "other"); b.reject(wrong, "PM6_SELECTION_STALE");
        }
        for (String field : List.of("patientContextId", "familyContextId", "role", "sex")) { var wrong = r.deepCopy(); at(wrong, "/patient").put(field, "other"); b.reject(wrong, "PM6_PATIENT_CONFLICT"); }
        b.reject(r.deepCopy().put("sampleId", "other"), "PM6_SAMPLE_CONFLICT");
        b.reject(r.deepCopy().put("dataClass", "PRIVACY_BOUNDED_RUNTIME"), "PM6_DATA_CLASS_CONFLICT");
        for (String field : List.of("facts", "parentageBasis", "context", "path", "policy", "evaluation", "annotation", "transcript")) {
            var wrong = r.deepCopy(); at(wrong, "/pm6Selection").putObject(field); b.reject(wrong, "ACMG_REQUEST_INVALID");
        }
        for (String field : List.of("association", "calibration", "revelApplication")) { var wrong = r.deepCopy(); wrong.putObject(field); b.reject(wrong, "PM6_EXCLUSIVE_SELECTION_REQUIRED"); }
        var wrong = r.deepCopy(); wrong.putArray("recommendations"); b.reject(wrong, "PM6_EXCLUSIVE_SELECTION_REQUIRED");
        wrong = r.deepCopy(); wrong.putArray("hypotheses").addObject(); b.reject(wrong, "PM6_EXCLUSIVE_SELECTION_REQUIRED");
        wrong = r.deepCopy(); wrong.putArray("pm6Selection").add(r.path("pm6Selection")); b.reject(wrong, "ACMG_REQUEST_INVALID");
        for (String raw : List.of(b.mapper.writeValueAsString(r) + " {}", b.mapper.writeValueAsString(r).replace("\"topN\":1", "\"topN\":1,\"topN\":1"))) {
            assertEquals("ACMG_REQUEST_INVALID", assertThrows(ResponseStatusException.class, () -> b.service.submit(b.analysisId, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getReason());
        }
        var config = new AcmgBusinessConfig();
        var ps2Env = new MockEnvironment().withProperty("variant-analysis.acmg-business.enabled", "true").withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PS2_DEVELOPMENT);
        var ps2 = config.acmgBusinessService(b.mapper, b.jobs, b.store, b.sources, b.properties, ps2Env);
        var legacy = config.acmgBusinessService(b.mapper, b.jobs, b.store, b.sources, b.properties, new MockEnvironment());
        for (var svc : List.of(ps2, legacy)) {
            assertEquals("PM6_SCOPE_REQUIRED", assertThrows(ResponseStatusException.class, () -> svc.submit(b.analysisId, b.mapper.writeValueAsBytes(r))).getReason());
            var both = r.deepCopy(); both.set("ps2Selection", r.path("pm6Selection")); both.putObject("revelApplication");
            assertEquals("PM6_SCOPE_REQUIRED", assertThrows(ResponseStatusException.class, () -> svc.submit(b.analysisId, b.mapper.writeValueAsBytes(both))).getReason());
            var token = r.deepCopy(); token.remove("pm6Selection"); token.put("declarationVersion", "ordinary-ui-v3"); at(token, "/patient").put("patientContextId", Pm6DeNovoContextPreparation.TOKEN + "0".repeat(64));
            assertEquals("PM6_SCOPE_REQUIRED", assertThrows(ResponseStatusException.class, () -> svc.submit(b.analysisId, b.mapper.writeValueAsBytes(token))).getReason());
        }
        var both = r.deepCopy(); both.set("ps2Selection", r.path("pm6Selection")); b.reject(both, "PS2_SCOPE_REQUIRED");
        b.reject(r.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.PS2_DECLARATION), "PS2_SCOPE_REQUIRED");
        var ps2Token = r.deepCopy(); at(ps2Token, "/pm6Selection").put("preparationToken", Ps2DeNovoContextPreparation.TOKEN + "0".repeat(64)); b.reject(ps2Token, "PS2_SCOPE_REQUIRED");
        assertEquals(0, b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty()); b.submit(r);
    }

    @Test void changedSidecarWithSameIdsHasNewBindingAndOldPinsReject() throws Exception {
        b.start("alpha"); var oldOptions = b.options(); var oldRequest = b.request(oldOptions); var saved = b.submit(oldRequest);
        var original = b.bundle.deepCopy();
        for (String field : List.of("result", "limitations", "review", "conclusion")) {
            b.bundle = original.deepCopy();
            b.issue("sidecar-" + field, n -> { var relation = at(n, "/observations/0/parentageBasis/relations/0");
                if (field.equals("review")) at(relation, "/review").put("basis", "TEST_ONLY changed reviewed rationale");
                else if (field.equals("conclusion")) { relation.put("conclusion", "CONFIRMED"); at(n, "/observations/0/facts/parentage/0").put("status", "CONFIRMED"); }
                else relation.put(field, "TEST_ONLY changed " + field);
            });
            b.reject(oldRequest, "PM6_SELECTION_STALE"); var current = b.options();
            assertNotEquals(oldOptions.at("/pm6Selection/preparationToken"), current.at("/pm6Selection/preparationToken"));
            assertNotEquals(oldOptions.at("/pm6Selection/materialBindingSha256"), current.at("/pm6Selection/materialBindingSha256"));
            assertEquals(saved, b.service.read(b.analysisId, saved.path("runId").asText()));
        }
        byte[] changed = b.mapper.writeValueAsBytes(b.bundle); b.env.withProperty("variant-analysis.acmg-business.pm6-bundle-file-sha256", requireNonNull("0".repeat(64))); b.wire(); b.reject(oldRequest, "PM6_RAW_PIN_MISMATCH");
        b.pins(changed); Files.write(b.material, changed); b.env.withProperty("variant-analysis.acmg-business.pm6-bundle-sha256", requireNonNull("0".repeat(64))); b.wire(); b.reject(oldRequest, "PM6_CANONICAL_PIN_MISMATCH");
        assertEquals(1, b.core.get());
    }

    @Test void formalFirstAndUnavailableSourceCannotBeFilledFromBundle() throws Exception {
        b.decoyFirst = true; b.start("alpha");
        assertEquals("PM6_CORE_ALLELE_CONFLICT", assertThrows(ResponseStatusException.class, () -> b.service.inputOptions(b.analysisId, 1)).getReason());
        assertEquals(0, b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty());
    }

    @Test void sourceAndCandidateRevisionCannotReuseSavedIdentityOrFillQualityGaps() throws Exception {
        b.start("alpha"); var request = b.request(b.options()); var saved = b.submit(request);
        var original = (ObjectNode) b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
        var source = original.deepCopy(); source.put("requestSha256", "a".repeat(64)); b.mutatePublishedSource(source);
        b.reject(request, "PM6_SELECTION_STALE"); b.mutatePublishedSource(original);
        source = original.deepCopy(); source.put("sampleId", "other"); b.mutatePublishedSource(source);
        b.reject(request, "EXECUTION_SNAPSHOT_TASK_MISMATCH"); b.mutatePublishedSource(original);
        var candidate = b.mapper.<ObjectNode>valueToTree(b.jobs.findInterpretations(b.analysisId, 0, 1).get(0));
        var changed = candidate.deepCopy(); at(changed, "/analysisVcfCall").put("depth", 41);
        b.store.saveCandidateAnnotationPayload(b.analysisId, changed.path("candidateId").asText(), b.mapper.writeValueAsString(changed));
        source = original.deepCopy(); at(source, "/candidates/0").set("published", changed); at(source, "/candidates/0/engineCandidate/analysisVcfCall").put("depth", 41); b.mutatePublishedSource(source);
        b.reject(request, "PM6_PROBAND_QUALITY_CONFLICT"); assertEquals(1, b.core.get());
        assertEquals(saved, b.service.read(b.analysisId, saved.path("runId").asText()));
    }

    @Test void changedSidecarCannotKeepOldOuterOrReviewPinsAndMultiObservationIsRejected() throws Exception {
        b.start("alpha"); var original = b.bundle.deepCopy(); var request = b.request(b.options());
        at(b.bundle, "/observations/0/parentageBasis/relations/0").put("result", "TEST_ONLY_CHANGED_WITHOUT_REVIEW");
        byte[] bytes = b.mapper.writeValueAsBytes(b.bundle);
        Path bad = b.temporary.resolve("changed-with-old-pins.json"); Files.write(bad, bytes, StandardOpenOption.CREATE_NEW);
        Files.write(b.archive.resolve("changed-with-old-pins-loader-bytes.json"), bytes, StandardOpenOption.CREATE_NEW);
        b.env.withProperty("variant-analysis.acmg-business.pm6-bundle-file", requireNonNull(bad.toString())); b.wire();
        b.reject(request, "PM6_RAW_PIN_MISMATCH");
        b.saveNew("new-outer-old-review", bytes);
        var saved = b.submit(b.request(b.options()));
        assertEquals("PM6_PARENTAGE_BASIS_PIN_MISMATCH", saved.at("/candidates/0/attempts/0/pm6/evaluationResult/reasonCode").asText());
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
        b.bundle = original; ((com.fasterxml.jackson.databind.node.ArrayNode) b.bundle.path("observations")).add(b.bundle.path("observations").path(0).deepCopy());
        b.saveNew("two-observations", b.mapper.writeValueAsBytes(b.bundle));
        assertEquals("PM6_SINGLE_OBSERVATION_REQUIRED", assertThrows(ResponseStatusException.class, () -> b.service.inputOptions(b.analysisId, 1)).getReason());
        assertEquals(1, b.core.get());
    }

    @Test void retryCannotBorrowAnotherScopeAndStoredIntegrityIsStillEnforced() throws Exception {
        b.start("alpha"); var request = b.request(b.options());
        var foreign = b.mapper.createObjectNode();
        foreign.set("declaration", b.mapper.valueToTree(new AcmgSubmission(1, AcmgBusinessRuleScope.PS2_DECLARATION, null, "test-sample-alpha-proband", "SYNTHETIC", null, List.of())));
        foreign.set("executionProfile", AcmgExecutionProfile.ps2Development(b.mapper, b.mapper.createObjectNode()).document());
        String id = UUID.randomUUID().toString();
        assertTrue(b.store.claim(new AcmgBusinessStore.Run(id, b.analysisId, b.canonical(foreign), b.mapper.writeValueAsString(foreign), "RUNNING", "2026-09-29T00:00:00Z", null, null, null)));
        b.store.failed(id); b.artifact("TEST_ONLY-foreign-scope-predecessor", b.mapper.valueToTree(b.store.run(b.analysisId, id).orElseThrow()));
        b.reject(request.deepCopy().put("retryOf", id), "PM6_RETRY_SCOPE_MISMATCH"); assertEquals(0, b.core.get());
        var saved = b.submit(request); var original = b.store.run(b.analysisId, saved.path("runId").asText()).orElseThrow();
        new JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?", "{}", original.id());
        assertEquals("ACMG_SAVED_RESULT_INTEGRITY_FAILURE", assertThrows(ResponseStatusException.class, () -> b.service.read(b.analysisId, original.id())).getReason());
        b.reject(request, "ACMG_SAVED_RESULT_INTEGRITY_FAILURE"); assertEquals(1, b.core.get());
        new JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?", original.responseJson(), original.id());
        assertEquals(saved, b.service.read(b.analysisId, original.id()));
    }

    @Test @SuppressWarnings("removal") // Existing JDK21 test-only network/exec positive controls; no null-analysis suppression.
    void configurationAndReachableForbiddenEntriesStayUnused() throws Exception {
        b.start("alpha"); var config = new AcmgBusinessConfig();
        b.env.withProperty("variant-analysis.acmg-business.enabled", "false");
        assertEquals("PM6_BUSINESS_EXECUTION_DISABLED", assertThrows(IllegalArgumentException.class, () -> config.acmgBusinessService(b.mapper, b.jobs, b.store, b.sources, b.properties, b.env)).getMessage());
        b.env.withProperty("variant-analysis.acmg-business.enabled", "true");
        for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only")) {
            b.env.withProperty("variant-analysis.acmg-business." + flag, "true");
            assertEquals("PM6_EXECUTION_MODE_CONFLICT", assertThrows(IllegalArgumentException.class, () -> config.acmgBusinessService(b.mapper, b.jobs, b.store, b.sources, b.properties, b.env)).getMessage());
            b.env.withProperty("variant-analysis.acmg-business." + flag, "false");
        }
        for (String field : Pm6BusinessFixture.supplierFields()) { Object supplier = ReflectionTestUtils.getField(requireNonNull(b.service), requireNonNull(field)); assertInstanceOf(Supplier.class, supplier); assertThrows(AssertionError.class, () -> ((Supplier<?>) requireNonNull(supplier)).get()); }
        assertEquals(8, b.forbidden.get()); b.artifact("supplier-positive-controls", b.counts());
        var network = new AtomicInteger(); var exec = new AtomicInteger(); SecurityManager prior = System.getSecurityManager();
        var guard = new SecurityManager() {
            @Override public void checkPermission(java.security.Permission p) { }
            @Override public void checkConnect(String host, int port) { network.incrementAndGet(); throw new SecurityException("TEST_ONLY_NETWORK_DENIED"); }
            @Override public void checkExec(String command) { exec.incrementAndGet(); throw new SecurityException("TEST_ONLY_EXEC_DENIED"); }
        };
        try {
            System.setSecurityManager(guard); assertThrows(SecurityException.class, () -> guard.checkConnect("127.0.0.1", 1)); assertThrows(SecurityException.class, () -> guard.checkExec("TEST_ONLY_NOT_EXECUTED"));
            var r = b.request(b.options()); var saved = b.submit(r); var before = b.counts();
            assertEquals(saved, b.service.read(b.analysisId, saved.path("runId").asText())); assertEquals(saved, b.submit(r)); assertEquals(before, b.counts());
            assertEquals(1, network.get()); assertEquals(1, exec.get()); assertEquals(8, b.forbidden.get()); assertEquals(1, b.core.get());
            b.artifact("forbidden-after-chain", b.mapper.createObjectNode().put("network", network.get()).put("exec", exec.get()).set("counts", b.counts()));
        } finally { System.setSecurityManager(prior); }
    }
}
