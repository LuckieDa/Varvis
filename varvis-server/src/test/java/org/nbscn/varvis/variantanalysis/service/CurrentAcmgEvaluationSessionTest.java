package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Attempt;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Blocker;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.CandidateAssembly;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic service contract tests, not scientific qualification of PP4 or a patient source. */
class CurrentAcmgEvaluationSessionTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final String analysis = "synthetic-session-analysis";
    private final String runId = "synthetic-session-run";
    private AcmgBusinessStore store;
    private JdbcTemplate jdbc;
    private SelectedEvidencePreparation prepared;
    private ObjectNode request;
    private String hash;
    private String candidate;
    private NativeSnvSourceProducer.Production production;
    private ParsedCandidateFixture.Run execution;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:current-session-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE variant_analysis_acmg_source (analysis_id VARCHAR(64) PRIMARY KEY, snapshot_sha256 VARCHAR(64), snapshot_json CLOB)");
        jdbc.execute("CREATE TABLE variant_analysis_acmg_run (run_id VARCHAR(64) PRIMARY KEY, analysis_id VARCHAR(64), request_sha256 VARCHAR(64), request_json CLOB, status VARCHAR(32), created_at VARCHAR(40), response_sha256 VARCHAR(64), response_json CLOB, private_json CLOB, UNIQUE(analysis_id,request_sha256))");
        jdbc.execute("CREATE TABLE variant_analysis_acmg_chunk (run_id VARCHAR(64), column_name VARCHAR(16), chunk_index INT, payload_sha256 VARCHAR(64), payload_text CLOB)");
        store = new AcmgBusinessStore(ds);
        store.saveSource(analysis, "{\"source\":\"SYNTHETIC_EXECUTION_SOURCE\"}");
        execution = ParsedCandidateFixture.run(temporary.resolve("execution"), analysis, GenomeAssembly.GRCh38,
                ParsedCandidateFixture.vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."),
                ParsedCandidateFixture.tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8"));
        var fixture = new ExplicitCaseInputCoordinatorTest();
        fixture.temporary = temporary.resolve("reference"); Files.createDirectories(fixture.temporary);
        var producer = fixture.tinyReference(r -> { }).producer();
        production = producer.produce(execution.job(), execution.conversion(), execution.execution(),
                new AnalysisContext(analysis, GenomeAssembly.GRCh38, AnalysisMode.WES), execution.execution().getInterpretations(), 1, List.of());
        candidate = execution.execution().getInterpretations().get(0).getCandidateId();
        prepared = selected(List.of(attempt(candidate, "hypothesis", List.of())), List.of());
        request = json.createObjectNode();
        request.putObject("executionProfile").putArray("enabledCriteria").add("ACMG_AMP_2015:PP1");
        request.putObject("localBatchPublishedInputIdentity")
                .put("executionSourceSha256", store.source(analysis).orElseThrow().sha256())
                .put("candidatesRawSha256", AcmgBusinessStore.sha(json.writeValueAsString(execution.execution().getInterpretations())));
        claim();
    }

    @Test void issuedSessionAndReturnedViewsCannotBeMutated() {
        var session = issue();
        var original = session.snapshot();
        session.identity().put("runId", "attacker");
        session.attempts().get(0).withObject("/binding/identity").put("candidateId", "attacker");
        session.snapshot().withArray("attempts").removeAll();
        assertEquals(original, session.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> session.attempts().clear());
        assertEquals(1, session.attempts().size());
        assertEquals(candidate, session.attempts().get(0).path("candidateId").asText());
    }

    @Test void unavailableInventoryNeverConvertsUnselectedPp4IntoZero() {
        var session = issue();
        var inventory = CurrentAdoptionInventory.unavailable(session);
        assertTrue(inventory.matches(session)); assertFalse(inventory.isComplete());
        assertEquals("UNAVAILABLE", inventory.snapshot().path("status").asText());
        assertFalse(inventory.snapshot().path("complete").asBoolean(true));
        assertTrue(inventory.snapshot().at("/attempts/0/pp4Points").isNull());
        inventory.snapshot().put("complete", true).withArray("attempts").removeAll();
        inventory.producerIdentity().put("registrationStatus", "REGISTERED");
        assertFalse(inventory.isComplete()); assertEquals(1, inventory.snapshot().path("attempts").size());
        assertEquals("UNAVAILABLE", inventory.producerIdentity().path("registrationStatus").asText());
    }

    @Test void sameRunReissuedSessionCannotReuseAnEarlierInventory() {
        var first = issue(); var inventory = CurrentAdoptionInventory.unavailable(first); var next = issue();
        assertFalse(inventory.matches(next)); assertFalse(inventory.matches(null));
        assertEquals(inventory.producerIdentity(), CurrentAdoptionInventory.unavailable(next).producerIdentity());
        assertFalse(inventory.producerIdentity().has("runId"));
    }

    @Test void noPublicJsonConstructorOrSelfAssertedCompletenessIssuerExists() {
        for (var type : List.of(CurrentAcmgEvaluationSession.class, CurrentAdoptionInventory.class)) {
            assertTrue(Modifier.isFinal(type.getModifiers()));
            assertEquals(0, type.getConstructors().length);
            var issuers = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())).toList();
            if (type == CurrentAdoptionInventory.class) {
                assertEquals(1, issuers.size());
                var issuer = issuers.get(0);
                assertEquals("capture", issuer.getName());
                assertEquals(CurrentAdoptionInventory.class, issuer.getReturnType());
                assertArrayEquals(new Class<?>[] {CurrentAcmgEvaluationSession.class,
                        org.nbscn.varvis.variantanalysis.provider.Pp4CurrentAdoptionProducer.class}, issuer.getParameterTypes());
            } else assertTrue(issuers.isEmpty());
        }
    }

    @Test void savedOrForgedJsonCannotReconstructEitherNativeCapability() {
        var session = issue();
        var inventory = CurrentAdoptionInventory.unavailable(session);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(session.snapshot(), CurrentAcmgEvaluationSession.class));
        var forged = inventory.snapshot().put("status", "COMPLETE").put("complete", true);
        forged.withArray("attempts").removeAll();
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(forged, CurrentAdoptionInventory.class));
        assertFalse(inventory.isComplete());
    }

    @Test void unknownRunAndWrongRequestPinCannotIssueSession() {
        assertReason("CURRENT_SESSION_CLAIM_REQUIRED", () -> CurrentAcmgEvaluationSession.issueAfterClaim(json, store, analysis, "other", hash, prepared));
        assertReason("CURRENT_SESSION_CLAIM_MISMATCH", () -> CurrentAcmgEvaluationSession.issueAfterClaim(json, store, analysis, runId, "f".repeat(64), prepared));
    }

    @Test void completedRunCannotBePromotedToCurrentEvaluation() {
        jdbc.update("UPDATE variant_analysis_acmg_run SET status='SUCCEEDED' WHERE run_id=?", runId);
        assertReason("CURRENT_SESSION_CLAIM_MISMATCH", this::issue);
    }

    @Test void editedStoredRequestDoesNotKeepItsClaimedHash() {
        request.put("forged", true);
        jdbc.update("UPDATE variant_analysis_acmg_run SET request_json=? WHERE run_id=?", request.toString(), runId);
        assertReason("CURRENT_SESSION_REQUEST_PIN_MISMATCH", this::issue);
    }

    @Test void sourceBytesCannotChangeUnderAnOldSourcePin() {
        jdbc.update("UPDATE variant_analysis_acmg_source SET snapshot_json=? WHERE analysis_id=?", "{\"forged\":true}", analysis);
        assertEquals("ACMG_SOURCE_IDENTITY_CONFLICT", assertThrows(IllegalStateException.class, this::issue).getMessage());
    }

    @Test void selfConsistentRequestCannotSubstituteAnUnpublishedSource() throws Exception {
        request.withObject("/localBatchPublishedInputIdentity").put("executionSourceSha256", "f".repeat(64)); reclaim();
        assertReason("CURRENT_SESSION_EXECUTION_SOURCE_MISMATCH", this::issue);
    }

    @Test void changedSelectedRankingCannotUseTheClaimedRankingPin() {
        var ranked = execution.execution().getInterpretations();
        ranked.get(0).applyRanking("SYNTHETIC_CHANGED", 0.123, List.of(), List.of());
        prepared = selected(List.of(attempt(candidate, "hypothesis", List.of())), List.of());
        assertReason("CURRENT_SESSION_SELECTED_RANKING_MISMATCH", this::issue);
    }

    @Test void missingDuplicateOrMismatchedAttemptsCannotClaimCoverage() {
        prepared = selected(List.of(), List.of());
        assertReason("CURRENT_SESSION_SELECTED_COVERAGE_INVALID", this::issue);
        prepared = selected(List.of(attempt(candidate, "hypothesis", List.of()), attempt(candidate, "hypothesis", List.of())), List.of());
        assertReason("CURRENT_SESSION_ATTEMPT_BINDING_INVALID", this::issue);
        prepared = selected(List.of(attempt("other-candidate", "hypothesis", List.of())), List.of());
        assertReason("CURRENT_SESSION_ATTEMPT_BINDING_INVALID", this::issue);
        prepared = selected(List.of(attempt(candidate, "hypothesis", List.of())), List.of(attempt("other", "unmatched", List.of())));
        assertReason("CURRENT_SESSION_SELECTED_COVERAGE_INVALID", this::issue);
    }

    @Test void mixedReadyAndBlockedAttemptsRetainCoverageWithoutMintingABinding() {
        prepared = selected(List.of(attempt(candidate, "ready", List.of()),
                attempt(candidate, "blocked", List.of(new Blocker("BLOCKED", "SYNTHETIC_MISSING_SOURCE", "source")))), List.of());
        var session = issue();
        assertEquals(2, session.attempts().size());
        assertEquals("READY", session.attempts().get(0).path("bindingStatus").asText());
        var blocked = session.attempts().get(1);
        assertEquals("UNAVAILABLE", blocked.path("bindingStatus").asText());
        assertEquals("SYNTHETIC_MISSING_SOURCE", blocked.at("/blockers/0/reasonCode").asText());
        assertFalse(blocked.has("bindingIdentitySha256")); assertFalse(blocked.has("binding"));
        var inventory = CurrentAdoptionInventory.unavailable(session).snapshot();
        assertEquals(2, inventory.path("attempts").size());
        assertTrue(inventory.at("/attempts/1/pp4Points").isNull());
        assertEquals("SYNTHETIC_MISSING_SOURCE", inventory.at("/attempts/1/selectionBlockers/0/reasonCode").asText());
    }

    @Test void missingSelectedContextRetainsAnUnavailableAttempt() {
        prepared = selected(List.of(new Attempt(candidate, "hypothesis", null, null, null, null, List.of())), List.of());
        var attempt = issue().attempts().get(0);
        assertEquals("UNAVAILABLE", attempt.path("bindingStatus").asText());
        assertEquals("SELECTED_CONTEXT_UNAVAILABLE", attempt.path("reasonCode").asText());
        assertFalse(attempt.has("binding")); assertFalse(attempt.has("bindingIdentitySha256"));
    }

    @Test void unknownDiseaseAndMissingCriterionRemainUnavailable() throws Exception {
        var invalid = attempt(candidate, "hypothesis", List.of());
        var context = invalid.context(); context.withObject("/disease").put("mondoId", "UNKNOWN");
        prepared = selected(List.of(new Attempt(candidate, "hypothesis", null, null, null, context, List.of())), List.of());
        var attempt = issue().attempts().get(0);
        assertEquals("UNAVAILABLE", attempt.path("bindingStatus").asText());
        assertEquals("PP1_EXACT_DISEASE_CONTEXT_REQUIRED", attempt.path("reasonCode").asText());
        assertFalse(attempt.has("binding"));
        request.withObject("/executionProfile").putArray("enabledCriteria").add("ACMG_AMP_2015:PP4"); reclaim();
        assertReason("CURRENT_SESSION_PP1_PROFILE_REQUIRED", this::issue);
    }

    private CurrentAcmgEvaluationSession issue() { return CurrentAcmgEvaluationSession.issueAfterClaim(json, store, analysis, runId, hash, prepared); }
    private void claim() throws Exception {
        hash = new AcmgBusinessArtifacts(json).requestHash(request);
        assertTrue(store.claim(new AcmgBusinessStore.Run(runId, analysis, hash, json.writeValueAsString(request), "RUNNING", "2026-11-04T00:00:00Z", null, null, null)));
    }
    private void reclaim() throws Exception { jdbc.update("DELETE FROM variant_analysis_acmg_run WHERE run_id=?", runId); claim(); }
    private SelectedEvidencePreparation selected(List<Attempt> attempts, List<Attempt> unmatched) {
        var assembly = new AssemblyResult(analysis, 1, 1, List.of(candidate), List.of(new CandidateAssembly(candidate, attempts)), unmatched, false);
        return new SelectedEvidencePreparation(json, assembly, List.of(), production, execution.execution(), execution.execution().getInterpretations(), true);
    }
    private Attempt attempt(String id, String hypothesis, List<Blocker> blockers) {
        var context = json.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE").put("dataClass", "SYNTHETIC");
        context.putObject("scope").put("analysisDomain", "GERMLINE");
        context.putObject("gene").put("symbol", "SYNTHETIC_GENE");
        context.putObject("disease").put("mondoId", "MONDO:0000001");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "11")
                .put("position", 2).put("reference", "T").put("alternate", "A");
        return new Attempt(id, hypothesis, null, null, null, context, blockers);
    }
    private static void assertReason(String reason, org.junit.jupiter.api.function.Executable executable) {
        assertEquals(reason, assertThrows(IllegalArgumentException.class, executable).getMessage());
    }
}
