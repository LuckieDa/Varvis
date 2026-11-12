package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JDBC/save/read plus existing append-only review files; synthetic case, no new resource query. */
class PredictionEffectReferencesTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AcmgBusinessArtifacts hashes = new AcmgBusinessArtifacts(mapper);
    AcmgBusinessStore store;
    ObjectNode saved, plan;
    FunctionalExperimentReviews reviews;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        // Minimal case identity table, real production source/run schema and real JDBC methods.
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE variant_analysis_job (id CHAR(36) PRIMARY KEY)");
        jdbc.update("INSERT INTO variant_analysis_job(id) VALUES (?)", "a");
        new ResourceDatabasePopulator(new ClassPathResource("db/acmg-business-schema.sql")).execute(ds);
        store = new AcmgBusinessStore(ds);
        var f = new Hnf1aFunctionalRuntimeTest().result("P112L");
        saved = mapper.createObjectNode().put("analysisId", "a").put("runId", "r");
        saved.putArray("candidates").addObject().put("candidateId", "candidate-a").putArray("attempts").addObject().set("functional", f);
        plan = PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json").put("candidateId", "candidate-a");
        plan.set("context", f.path("context")); plan.set("queryAllele", f.at("/context/variant/evidenceQueryAllele"));
        reviews = new FunctionalExperimentReviews(mapper, root, this::read, (a, action) -> action.get());
    }
    ObjectNode read(String a, String r) {
        var record = store.run(a, r).orElseThrow();
        assertEquals(AcmgBusinessStore.sha(record.responseJson()), record.responseSha256());
        try { return (ObjectNode) mapper.readTree(record.responseJson()); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    void persist() throws Exception {
        var request = mapper.createObjectNode().put("fixture", "synthetic");
        store.claim(new AcmgBusinessStore.Run("r", "a", hashes.requestHash(request), request.toString(), "RUNNING", "2026-09-17", null, null, null));
        store.finish("r", "COMPLETED", mapper.writeValueAsString(saved), "{}");
    }
    PredictionEffectReferences.Snapshot snapshot() { return new PredictionEffectReferences(mapper, store, root, this::read).capture("a", Set.of("candidate-a")); }
    void adopt() {
        var f = saved.at("/candidates/0/attempts/0/functional");
        reviews.submit("a", "r", new FunctionalExperimentReviews.Input(f.at("/evaluation/evaluationId").asText(), hashes.requestHash(f.path("factPackage")),
                f.path("profileSha256").asText(), null, "ACCEPTED", "reviewer-synthetic-qa", "SYSTEM_ADMINISTRATOR", "Synthetic persistence fixture only."));
    }
    @Test void completeEmptySnapshotIsLocalAndStableWithoutCreatingAReviewDirectory() {
        var first = snapshot(); var next = snapshot(); assertEquals(first.identity(), next.identity());
        var result = first.resolve(plan); assertEquals("NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE", result.path("restriction").asText());
        assertEquals("UNSPECIFIED", result.path("externalDeclaration").asText()); assertFalse(result.path("externalWorldCovered").asBoolean());
        var absent = new PredictionEffectReferences(mapper, store, root.resolve("missing"), this::read).capture("a", Set.of("candidate-a")).resolve(plan);
        assertEquals("NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE", absent.path("restriction").asText());
        assertFalse(absent.path("externalWorldCovered").asBoolean());
        assertFalse(Files.exists(root.resolve("missing")));
    }
    @Test void missingReviewDirectoryStillBlocksFunctionalOrLegacyRuns() throws Exception {
        persist();
        var absent = new PredictionEffectReferences(mapper, store, root.resolve("missing"), this::read).capture("a", Set.of("candidate-a")).resolve(plan);
        assertEquals("UNKNOWN", absent.path("restriction").asText());
        assertEquals("EFFECT_REVIEW_STORE_UNAVAILABLE", absent.path("reasonCode").asText());
    }
    @Test void verifiedNonFunctionalRunsNeedNoReviewDirectoryOrPayloadRead() {
        var request = mapper.createObjectNode();
        request.putObject("executionProfile").putArray("enabledCriteria").add("ACMG_AMP_2015:BP1");
        store.claim(new AcmgBusinessStore.Run("r", "a", hashes.requestHash(request), request.toString(), "RUNNING", "t", null, null, null));
        var absent = new PredictionEffectReferences(mapper, store, root.resolve("missing"), (a,r) -> { fail("No functional payload"); return null; })
                .capture("a", Set.of("candidate-a")).resolve(plan);
        assertEquals("VERIFIED_SAVED_REFERENCES", absent.path("status").asText());
        assertEquals("NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE", absent.path("restriction").asText());
    }
    @Test void largeHistoricalNonFunctionalRequestsPreserveScopeWithoutReadingResultPayloads() {
        var request = mapper.createObjectNode().put("sourceSnapshot", "汉字".repeat(200000));
        request.putObject("executionProfile").putArray("enabledCriteria").add("ACMG_AMP_2015:PM3");
        store.claim(new AcmgBusinessStore.Run("large", "a", hashes.requestHash(request), request.toString(), "RUNNING", "t", null, null, null));
        var actual = new PredictionEffectReferences(mapper, store, root.resolve("absent"), (a,r) -> { fail("Non-functional payload read"); return null; })
                .capture("a", Set.of("candidate-a"));
        assertEquals("READY", actual.readiness().path("status").asText());
        assertEquals("VERIFIED_SAVED_REFERENCES", actual.resolve(plan).path("status").asText());
    }
    @Test void observedMetIsNotAdoptedButVerifiedAdoptionChangesSnapshotAndBlocksProteinOnly() throws Exception {
        persist(); var observed = snapshot(); var before = observed.resolve(plan);
        assertEquals("OBSERVED_NOT_ADOPTED", before.at("/references/0/usage").asText());
        adopt(); var adopted = snapshot(); assertNotEquals(observed.identity(), adopted.identity());
        assertEquals("NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE", observed.resolve(plan).path("restriction").asText(), "Captured snapshot is immutable");
        var refs = adopted.resolve(plan); assertEquals("RESTRICTION_FOUND", refs.path("restriction").asText(), refs.toString());
        plan.set("effectRestrictions", refs);
        var mechanisms = mapper.createObjectNode();
        var harmful = PredictionCalibration.evaluate(mapper, PredictionDevelopmentRuntime.load(mapper, "prediction-am-calibration-v1.json"), "0.990", true);
        mechanisms.set("PROTEIN_MISSENSE", harmful); mechanisms.set("SPLICING", harmful);
        assertEquals("SAME_EFFECT_ALREADY_ADOPTED", PredictionMechanismApplication.apply(mapper, plan, mechanisms, null).at("/PP3/reasonCode").asText());
        plan.put("primaryMechanism", "SPLICING");
        assertEquals("MET", PredictionMechanismApplication.apply(mapper, plan, mechanisms, null).at("/PP3/status").asText(), "Protein adoption does not become a splice restriction");
    }
    @Test void differentBackgroundAndCandidateAreNeverBorrowed() throws Exception {
        persist(); adopt(); var different = plan.deepCopy(); var context = (ObjectNode)different.path("context");
        ((ObjectNode)context.path("disease")).put("mondoId", "MONDO:0000001");
        context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(context));
        assertEquals("NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE", snapshot().resolve(different).path("restriction").asText());
        different.put("candidateId", "candidate-other");
        assertEquals("EFFECT_CANDIDATE_OUTSIDE_SCOPE", snapshot().resolve(different).path("reasonCode").asText());
    }
    @Test void temporaryHistoryReadRetriesOnceButCorruptHistoryNeverBecomesEmpty() {
        var bounded = org.mockito.Mockito.mock(AcmgBusinessStore.class);
        org.mockito.Mockito.when(bounded.withAnalysisLock(org.mockito.ArgumentMatchers.eq("a"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(1)).get());
        org.mockito.Mockito.when(bounded.effectScopeRuns("a"))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("synthetic timeout"))
                .thenReturn(List.of());
        var adapter = new PredictionEffectReferences(mapper, bounded, root, this::read);
        assertEquals("READY", adapter.capture("a", Set.of("candidate-a")).readiness().path("status").asText());
        org.mockito.Mockito.verify(bounded, org.mockito.Mockito.times(2)).effectScopeRuns("a");
        org.mockito.Mockito.reset(bounded);
        org.mockito.Mockito.when(bounded.withAnalysisLock(org.mockito.ArgumentMatchers.eq("a"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(1)).get());
        org.mockito.Mockito.when(bounded.effectScopeRuns("a")).thenReturn(List.of(
                new AcmgBusinessStore.Run("r", "a", "bad", "{}", "COMPLETED", "t", null, null, null)));
        var broken = adapter.capture("a", Set.of("candidate-a"));
        assertEquals("EFFECT_REQUEST_INTEGRITY_FAILURE", broken.readiness().path("reasonCode").asText());
        assertEquals("REQUEST_INTEGRITY", broken.readiness().path("failureStage").asText());
        org.mockito.Mockito.verify(bounded).effectScopeRuns("a");
    }
    @Test void persistentTimeoutRemainsUnavailableAndQueryAlleleCannotBeSubstituted() {
        var bounded = org.mockito.Mockito.mock(AcmgBusinessStore.class);
        org.mockito.Mockito.when(bounded.withAnalysisLock(org.mockito.ArgumentMatchers.eq("a"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(1)).get());
        org.mockito.Mockito.when(bounded.effectScopeRuns("a")).thenThrow(new org.springframework.dao.QueryTimeoutException("synthetic"));
        var result = new PredictionEffectReferences(mapper, bounded, root, this::read).capture("a", Set.of("candidate-a")).resolve(plan);
        assertEquals("EFFECT_SOURCE_READ_TEMPORARY_FAILURE", result.path("reasonCode").asText());
        assertEquals("HISTORY_INDEX", result.path("failureStage").asText());
        org.mockito.Mockito.verify(bounded, org.mockito.Mockito.times(2)).effectScopeRuns("a");
        plan.set("queryAllele", plan.path("queryAllele").deepCopy());
        ((ObjectNode) plan.path("queryAllele")).put("position", 1);
        assertEquals("EFFECT_QUERY_ALLELE_MISMATCH", snapshot().resolve(plan).path("reasonCode").asText());
    }
    @Test void unresolvedDeclaredReferencesDoNotBecomeAnEmptySnapshot() throws Exception {
        ((ObjectNode)saved.at("/candidates/0/attempts/0")).putArray("declaredEvidenceReferences").add("unresolved-external-study"); persist();
        var result = snapshot().resolve(plan); assertEquals("UNKNOWN", result.path("restriction").asText());
        assertEquals("EFFECT_DECLARED_REFERENCES_UNRESOLVED", result.path("reasonCode").asText());
    }
    @Test void damagedAtomicIdentityCannotBeUsedEvenIfEnvelopeWasSaved() throws Exception {
        ((ObjectNode)saved.at("/candidates/0/attempts/0/functional/atomicEvidence/0/measurements/0")).put("rawValue", "tampered");
        persist(); var result = snapshot().resolve(plan);
        assertEquals("UNKNOWN", result.path("restriction").asText());
        assertEquals("EFFECT_ATOMIC_IDENTITY_INVALID", result.path("reasonCode").asText());
    }
    @Test void incompletePaginationIsExplicitAndDoesNotReadOtherCasePayloads() {
        var bounded = org.mockito.Mockito.mock(AcmgBusinessStore.class);
        org.mockito.Mockito.when(bounded.withAnalysisLock(org.mockito.ArgumentMatchers.eq("a"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(1)).get());
        org.mockito.Mockito.when(bounded.effectScopeRuns("a")).thenReturn(Collections.nCopies(1001,
                new AcmgBusinessStore.Run("r", "a", null, null, "COMPLETED", "t", null, null, null)));
        var adapter = new PredictionEffectReferences(mapper, bounded, root, (a,r) -> { fail("No payload read after scope limit"); return mapper.createObjectNode(); });
        assertEquals("EFFECT_SCOPE_RUN_LIMIT_EXCEEDED", adapter.capture("a", Set.of("candidate-a")).resolve(plan).path("reasonCode").asText());
    }
    @Test void corruptReviewAndMissingLinkedSourceStayUnknown() throws Exception {
        persist(); adopt();
        try (var paths = Files.walk(root, 2)) { var file = paths.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); Files.writeString(file, "{}"); }
        assertEquals("UNKNOWN", snapshot().resolve(plan).path("restriction").asText());
        store.claim(new AcmgBusinessStore.Run("pending", "a", "d".repeat(64), "{}", "RUNNING", "2026-09-18", null, null, null));
        assertEquals("UNKNOWN", snapshot().resolve(plan).path("restriction").asText());
    }
}
