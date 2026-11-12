package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H2 + actual publication/parser/source recovery + HTTP, with explicitly synthetic query transports. */
class Ba1BusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    BusinessAlphaMissenseFixture am;
    Ba1AlleleRuntime ba1;
    @org.springframework.lang.Nullable ObjectNode ba1Binding;
    @BeforeEach void setup() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        base.publish(false, GenomeAssembly.GRCh38, null);
        am = new BusinessAlphaMissenseFixture(); configure("0.055", "9000", "HIT");
    }
    @AfterEach void close() { base.closeDatabase(); }
    void configure(String frequency, String an, String mode) throws Exception {
        Path script = temporary.resolve("synthetic-tabix.cmd");
        String info = "AC_joint=500;AN_joint=10000;AF_joint=0.05;nhomalt_joint=25;grpmax_joint=nfe;AC_grpmax_joint=450;AN_grpmax_joint=9000;AF_grpmax_joint=0.05;nhomalt_grpmax_joint=22"
                + ";faf95_joint=0.054;fafmax_faf95_max_joint=" + frequency + ";fafmax_faf95_max_gen_anc_joint=nfe;AC_joint_nfe=450;AN_joint_nfe=" + an + ";AF_joint_nfe=0.05;nhomalt_joint_nfe=22;faf95_joint_nfe=0.054";
        String body = "@echo off\r\necho x>> \"%~dp0queries.txt\"\r\n";
        if (mode.equals("HIT")) body += "echo chr11\t2\t.\tT\tA\t.\tPASS\t" + info + "\r\necho chr13\t3\t.\tC\tT\t.\tPASS\t" + info + "\r\n";
        Files.writeString(script, body + "exit /b " + (mode.equals("FAILED") ? "7" : "0") + "\r\n");
        var p = populationSettings(script.toString()); ba1 = new Ba1AlleleRuntime(base.mapper, p); wire();
    }
    VariantAnalysisProperties populationSettings(String tabix) {
        var p = new VariantAnalysisProperties(); p.getGnomad().setEnabled(true); p.getGnomad().setPopulationInputEnabled(true);
        p.getGnomad().setVersion(GnomadPopulationInputProfile.loadExecutableV2().getDocument().path("resources").get(0).path("resourceVersion").asText());
        p.getGnomad().setTabixExecutable(tabix); p.getGnomad().setTimeout(Duration.ofSeconds(30)); p.getGnomad().setParallelism(1);
        p.getGnomad().setAllowFullVcfFallback(false); p.getGnomad().setAllowVcfInfoFallback(false); return p;
    }
    void wire() {
        wire(null);
    }
    void wire(@org.springframework.lang.Nullable PopulationFrequencyBundle bundle) {
        wire(bundle, null);
    }
    void wire(@org.springframework.lang.Nullable PopulationFrequencyBundle bundle,
              @org.springframework.lang.Nullable org.nbscn.varvis.variantanalysis.provider.ReviewedRuleBundle reviewed) {
        wire(bundle, reviewed, false);
    }
    void wire(@org.springframework.lang.Nullable PopulationFrequencyBundle bundle,
              @org.springframework.lang.Nullable org.nbscn.varvis.variantanalysis.provider.ReviewedRuleBundle reviewed,
              boolean disableReviewedCriteria) {
        wire(bundle, reviewed, disableReviewedCriteria, null);
    }
    void wire(@org.springframework.lang.Nullable PopulationFrequencyBundle bundle,
              @org.springframework.lang.Nullable ReviewedRuleBundle reviewed, boolean disableReviewedCriteria,
              @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts) {
        wire(bundle, reviewed, disableReviewedCriteria, ruleContexts, null);
    }
    void wire(@org.springframework.lang.Nullable PopulationFrequencyBundle bundle,
              @org.springframework.lang.Nullable ReviewedRuleBundle reviewed, boolean disableReviewedCriteria,
              @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts,
              @org.springframework.lang.Nullable ReviewedPredictionBusinessRuntime prediction) {
        base.service = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources,
                () -> new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources,
                        prediction != null ? new ExplicitAlphaMissenseDispatcher(base.mapper, am,
                                () -> { throw new IllegalArgumentException("SYNTHETIC_UNUSED_SPLICE"); },
                                () -> Hnf1aRevelRuntime.unavailable(base.mapper), ruleContexts, prediction, null)
                        : ruleContexts == null ? new ExplicitAlphaMissenseDispatcher(base.mapper, am)
                                : new ExplicitAlphaMissenseDispatcher(base.mapper, am,
                                    () -> { throw new IllegalArgumentException("SYNTHETIC_UNUSED_SPLICE"); },
                                    () -> Hnf1aRevelRuntime.unavailable(base.mapper), ruleContexts)),
                () -> new AcmgBusinessService.Ba1Runtime(base.producer, ba1), prediction != null
                        ? new AcmgRecommendations(base.mapper, ignored -> { throw new AssertionError("No AM identity lookup"); }, ruleContexts, prediction.contexts())
                        : ruleContexts == null
                        ? AcmgRecommendations.unavailable(base.mapper) : new AcmgRecommendations(base.mapper, am::lookupIdentities, ruleContexts),
                () -> { throw new IllegalArgumentException("PM2_RUNTIME_NOT_CONFIGURED"); },
                () -> { throw new IllegalArgumentException(disableReviewedCriteria ? "PVS1_BUSINESS_EXECUTION_DISABLED" : "PVS1_RUNTIME_NOT_CONFIGURED"); },
                () -> { throw new IllegalArgumentException("PS1_PM5_RUNTIME_NOT_CONFIGURED"); }, null,
                () -> {
                    if (disableReviewedCriteria) throw new IllegalArgumentException("PS3_BUSINESS_EXECUTION_DISABLED");
                    return new Hnf1aFunctionalRuntime(base.mapper);
                }, bundle, reviewed, ruleContexts, prediction, ba1Binding);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    ObjectNode post() throws Exception { return base.postRequest(base.request); }
    String ba1Status(ObjectNode r, int i) { return r.at("/candidates/" + i + "/ba1/evaluation/evaluationResult/status").asText(); }
    ObjectNode syntheticCatalog(String version) {
        var snapshot = base.mapper.createObjectNode().put("schemaVersion", "a01-ba1-cspec-snapshot-v1");
        var metadata = snapshot.putObject("metadata").put("snapshotId", version).put("sourceBase", "https://example.test/cspec")
                .put("status", "COMPLETE").put("publicationScope", "Released").put("declaredEntityCount", 0)
                .put("enumeratedEntityCount", 0).put("entryCount", 0).put("selectedReleasedCount", 0);
        metadata.putArray("pages").addObject().put("page", 0).put("returnedCount", 0).put("rawSha256", "a".repeat(64));
        metadata.put("compilerVersion", "test").put("captureManifestSha256", "a".repeat(64));
        metadata.putObject("batchReview").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ONLY")
                .put("compilerVersion", "test").put("sourceCaptureSha256", "a".repeat(64));
        snapshot.putArray("entries"); snapshot.putArray("geneMappings");
        return snapshot;
    }
    void configureCatalog(Ba1SpecificationCatalog catalog) throws Exception {
        configure("0.055", "9000", "HIT");
        Path script = temporary.resolve("synthetic-tabix.cmd");
        // New QC fixture is internally consistent; legacy fixtures and their historical expected results stay unchanged.
        String original = Files.readString(script);
        String consistent = original.replace("AC_joint=500;AN_joint=10000;AF_joint=0.05", "AC_joint=800;AN_joint=10000;AF_joint=0.08")
                .replace("AC_grpmax_joint=450;AN_grpmax_joint=9000;AF_grpmax_joint=0.05", "AC_grpmax_joint=720;AN_grpmax_joint=9000;AF_grpmax_joint=0.08")
                .replace("AC_joint_nfe=450;AN_joint_nfe=9000;AF_joint_nfe=0.05", "AC_joint_nfe=720;AN_joint_nfe=9000;AF_joint_nfe=0.08")
                .replace("faf95_joint_nfe=0.054", "faf95_joint_nfe=0.055;exomes_filters=.;genomes_filters=.;stat_union_p_value=0.5;stat_union_test_name=chi_squared;stat_union_gen_ancs=nfe");
        Files.writeString(script, consistent);
        ba1 = new Ba1AlleleRuntime(base.mapper, populationSettings(script.toString()), catalog);
        ba1Binding = ba1.identity(); wire();
    }
    @Test void sourceStorageFailureHasSafeStageAndBa1ReasonWithoutQueries() throws Exception {
        var failedSources = org.mockito.Mockito.mock(org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.class);
        org.mockito.Mockito.when(failedSources.recover(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("private SQL and patient values", new java.sql.SQLTransientConnectionException("secret endpoint")));
        var service = base.service;
        if (service == null) throw new IllegalStateException("TEST_SERVICE_MISSING");
        ReflectionTestUtils.setField(service, "sources", failedSources);
        var options = base.service.inputOptions(base.analysisId, 2);
        assertFalse(options.path("executionSourceRecovered").asBoolean());
        assertEquals("SOURCE_RECOVERY", options.at("/blockers/0/stage").asText());
        assertEquals("EXECUTION_SOURCE_STORAGE_UNAVAILABLE", options.at("/blockers/0/reasonCode").asText());
        var response = post();
        assertEquals("SOURCE_RECOVERY", response.at("/executionSourceFailure/stage").asText());
        assertEquals("java.sql.SQLTransientConnectionException", response.at("/executionSourceFailure/causeType").asText());
        assertEquals(0, response.at("/ba1Calls/provider").asInt());
        assertEquals(0, response.at("/ba1Calls/evaluator").asInt());
        for (var row : response.path("candidates")) assertTrue(row.path("ba1").toString().contains("EXECUTION_SOURCE_STORAGE_UNAVAILABLE"));
        assertFalse(response.toString().contains("private SQL"));
        assertFalse(response.toString().contains("secret endpoint"));
        assertEquals(response, base.service.read(base.analysisId, response.path("runId").asText()));
    }

    @Test void mappingFailureDiagnosticSurvivesSavedRunAndRepositoryRecreation() throws Exception {
        configureCatalog(new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("mapping-diagnostic")));
        var result = post();
        assertEquals("BA1_MAPPING_CATALOG_IDENTITY_INVALID", result.at("/candidates/0/ba1/generalization/selection/geneMapping/failure/reasonCode").asText());
        base.recreateRepositories(); wire();
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
    }

    @Test void catalogSuccessPersistsExactContextsAndDoesNotReuseOrRewriteLegacyResult() throws Exception {
        var historical = post(); assertEquals("MET", ba1Status(historical, 0));
        configureCatalog(new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("synthetic-catalog-v1")));
        var current = post();
        assertNotEquals(historical.path("runId"), current.path("runId"));
        assertEquals("acmg-explicit-business-pm2-conversion-v1", current.at("/executionProfile/profileId").asText());
        assertEquals("acmg-explicit-business-ba1-catalog-v2", current.at("/executionProfile/baseExecutionProfile/profileId").asText());
        assertEquals(2, current.at("/ba1Calls/provider").asInt());
        assertEquals(4, current.at("/ba1Calls/evaluator").asInt());
        for (var row : current.path("candidates")) {
            assertFalse(row.has("ba1ReusedFromRunId"));
            assertEquals(1, row.at("/ba1/contextResults").size());
            assertEquals(row.at("/attempts/0/context/contextId"), row.at("/ba1/contextResults/0/contextId"));
            assertEquals("SELECTED", row.at("/ba1/contextResults/0/selection/status").asText(), row.toPrettyString());
            assertEquals("MET", row.at("/ba1/contextResults/0/evaluation/evaluationResult/status").asText(), row.toPrettyString());
            assertEquals("SOURCE_ONLY", row.at("/ba1ResultSelection/legacyResultsRole").asText());
            assertFalse(row.at("/ba1ResultSelection/fallbackAllowed").asBoolean(true));
            assertEquals("/ba1/contextResults/0", row.at("/attempts/0/ba1ResultSelection/effectiveResultPath").asText());
        }
        assertEquals(historical, base.service.read(base.analysisId, historical.path("runId").asText()));
        int queries = Files.readAllLines(temporary.resolve("queries.txt")).size();
        assertEquals(current, post());
        base.recreateRepositories(); wire();
        assertEquals(current, base.service.read(base.analysisId, current.path("runId").asText()));
        assertEquals(current, post());
        assertEquals(queries, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertFalse(current.toString().contains(temporary.toString()));
    }
    @Test void changedDeclaredContextsReevaluateOnlyThatCandidateAndCatalogVersionSeparatesCache() throws Exception {
        configureCatalog(new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("synthetic-catalog-v1")));
        var first = post();
        var extra = ((ObjectNode) base.request.path("hypotheses").get(0)).deepCopy();
        ((ObjectNode) extra.path("hypothesis")).put("hypothesisId", "second-context");
        ((ArrayNode) base.request.path("hypotheses")).add(extra);
        var expanded = post();
        assertNotEquals(first.path("runId"), expanded.path("runId"));
        assertEquals(1, expanded.at("/candidates/0/ba1/contextResults").size());
        assertEquals(first.at("/candidates/0/ba1/reuseKey"), expanded.at("/candidates/0/ba1/reuseKey"));
        assertEquals(0, expanded.at("/ba1Calls/provider").asInt());
        assertEquals(0, expanded.at("/ba1Calls/evaluator").asInt());
        assertEquals(first.path("candidateIds"), expanded.path("candidateIds"));
        ((ArrayNode) base.request.path("hypotheses")).remove(2);
        ((ObjectNode) base.request.at("/hypotheses/0")).put("affectedStatus", "UNKNOWN");
        var changed = post();
        assertNotEquals(expanded.path("runId"), changed.path("runId"));
        assertEquals(1, changed.at("/candidates/0/ba1/contextResults").size());
        assertEquals(first.at("/candidates/0/attempts/0/context/contextId"), changed.at("/candidates/0/attempts/0/context/contextId"));
        assertNotEquals(first.at("/candidates/0/ba1/reuseKey"), changed.at("/candidates/0/ba1/reuseKey"));
        assertEquals(first.at("/candidates/1/ba1/reuseKey"), changed.at("/candidates/1/ba1/reuseKey"));
        assertTrue(changed.at("/candidates/1/ba1ReusedFromRunId").isTextual());
        assertEquals(1, changed.at("/ba1Calls/provider").asInt());
        assertEquals(2, changed.at("/ba1Calls/evaluator").asInt());
        configureCatalog(new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("synthetic-catalog-v2")));
        var updated = post();
        assertNotEquals(changed.path("runId"), updated.path("runId"));
        assertNotEquals(changed.at("/executionProfile/manifestSha256"), updated.at("/executionProfile/manifestSha256"));
        assertEquals(2, updated.at("/ba1Calls/provider").asInt());
        assertEquals(4, updated.at("/ba1Calls/evaluator").asInt());
        assertEquals(expanded, base.service.read(base.analysisId, expanded.path("runId").asText()));
    }
    @Test void unavailableCatalogCannotBeFilledByAnOldBa1MetAndMissingContextStaysEmpty() throws Exception {
        var old = post(); assertEquals("MET", ba1Status(old, 0));
        configureCatalog(Ba1SpecificationCatalog.unavailable(base.mapper, "BA1_CATALOG_CONFIGURATION_INVALID"));
        base.request.putArray("hypotheses"); base.request.putNull("patient");
        var blocked = post();
        assertEquals("NOT_EVALUATED", ba1Status(blocked, 0));
        assertEquals("BLOCKED", blocked.at("/candidates/0/ba1/generalization/selection/status").asText());
        assertTrue(blocked.at("/candidates/0/ba1/contextResults").isEmpty());
        assertFalse(blocked.at("/candidates/0/ba1ResultSelection/fallbackAllowed").asBoolean(true));
        assertFalse(blocked.at("/candidates/0").has("ba1ReusedFromRunId"));
        assertEquals(old, base.service.read(base.analysisId, old.path("runId").asText()));
    }
    @Test void actualRuntimeMustMatchPinnedBa1BindingBeforeAnyBa1Query() throws Exception {
        configureCatalog(new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("bound-catalog")));
        ba1 = new Ba1AlleleRuntime(base.mapper, populationSettings(temporary.resolve("synthetic-tabix.cmd").toString()),
                new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("different-runtime-catalog")));
        wire();
        var blocked = post();
        assertEquals(0, blocked.at("/ba1Calls/provider").asInt());
        assertEquals(0, blocked.at("/ba1Calls/evaluator").asInt());
        assertFalse(Files.exists(temporary.resolve("queries.txt")));
        assertEquals("BA1_RUNTIME_BINDING_MISMATCH", blocked.at("/candidates/0/ba1/evaluationResult/reasonCode").asText());
        assertEquals("SOURCE_ONLY", blocked.at("/candidates/0/ba1ResultSelection/legacyResultsRole").asText());
    }
    @Test void ba1ExecutionBindingIsCopiedAndOldNullIdentityRemainsExact() {
        var legacy = new AcmgExecutionProfile(base.mapper).document();
        assertEquals(legacy, new AcmgExecutionProfile(base.mapper, null, null, null, null, null, null, null).document());
        var runtime = new Ba1AlleleRuntime(base.mapper, populationSettings("synthetic-unexecuted-tabix"),
                new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("identity-only")));
        var identity = runtime.identity();
        var profile = new AcmgExecutionProfile(base.mapper, null, null, null, null, null, null, identity);
        var frozen = profile.document(); identity.put("algorithmVersion", "changed-after-construction");
        assertEquals(frozen, profile.document());
        assertEquals(legacy, frozen.path("baseExecutionProfile"));
        var missing = runtime.identity(); missing.remove("exceptionPolicySha256");
        assertThrows(IllegalArgumentException.class, () -> new AcmgExecutionProfile(base.mapper, null, null, null, null, null, null, missing));
    }
    @Test void ba1OnlyConfirmationDoesNotQueryOrEvaluateAnyOtherCriterion() throws Exception {
        configureCatalog(new Ba1SpecificationCatalog(base.mapper, syntheticCatalog("ba1-only-confirmation")));
        for (var item : base.request.path("hypotheses"))
            ((ObjectNode) item.path("transcript")).put("selectionPolicy", AcmgRecommendations.BA1_CONTEXT_SELECTION);
        var pm2 = org.mockito.Mockito.mock(Pm2SupportingRuntime.class);
        var population = org.mockito.Mockito.mock(PopulationFrequencyBundle.class);
        org.mockito.Mockito.when(population.identity()).thenReturn(base.mapper.createObjectNode().put("synthetic", true));
        var service = java.util.Objects.requireNonNull(base.service);
        ReflectionTestUtils.setField(service, "pm2Runtime", (java.util.function.Supplier<Pm2SupportingRuntime>) () -> pm2);
        ReflectionTestUtils.setField(service, "populationFrequency", population);
        var result = post();
        for (var row : result.path("candidates")) {
            assertEquals("MET", row.at("/ba1/contextResults/0/evaluation/evaluationResult/status").asText(), row.toPrettyString());
            var attempt = row.at("/attempts/0");
            assertEquals("BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED", attempt.at("/pm2/evaluationResult/reasonCode").asText());
            for (var e : attempt.at("/populationFrequency/criterionEvaluations")) {
                assertEquals("NOT_EVALUATED", e.at("/evaluationResult/status").asText());
                assertEquals("BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED", e.at("/evaluationResult/reasonCode").asText());
            }
            assertEquals("PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED", attempt.at("/pvs1/evaluationResult/reasonCode").asText());
            assertEquals("FUNCTIONAL_TRANSCRIPT_CONFIRMATION_REQUIRED", attempt.at("/functional/evaluationResult/reasonCode").asText());
            assertFalse(attempt.path("criterionEvaluations").toString().contains("\"MET\""));
        }
        assertEquals(0, am.calls);
        assertEquals(0, result.at("/pm2Calls/provider").asInt());
        assertEquals(0, result.at("/populationFrequencyCalls/evaluator").asInt());
        assertTrue(org.mockito.Mockito.mockingDetails(pm2).getInvocations().stream()
                .noneMatch(call -> Set.of("prepare", "evaluate", "reuseSuccessfulObservation").contains(call.getMethod().getName())));
        assertTrue(org.mockito.Mockito.mockingDetails(population).getInvocations().stream()
                .noneMatch(call -> "evaluate".equals(call.getMethod().getName())));
    }
    @Test void explicitRetryRequeriesTimeoutAndReusesOnlySuccessfulSibling() throws Exception {
        Path script = temporary.resolve("synthetic-tabix.cmd");
        String success = Files.readString(script);
        Files.writeString(script, "@echo off\r\necho %~5 | findstr /C:\"sites.chr11.\" >nul\r\n"
                + "if not errorlevel 1 (\r\necho simulated transport timed out 1>&2\r\nexit /b 7\r\n)\r\n" + success);
        var failed = post();
        assertEquals("TIMEOUT", failed.at("/candidates/0/ba1/evidence/resourceExecution/status").asText());
        assertEquals("SUCCEEDED", failed.at("/candidates/1/ba1/evidence/resourceExecution/status").asText());
        configure("0.055", "9000", "HIT");
        int before = Files.readAllLines(temporary.resolve("queries.txt")).size();
        assertEquals(failed, post());
        assertEquals(before, Files.readAllLines(temporary.resolve("queries.txt")).size());
        base.request.put("retryOf", failed.path("runId").asText());
        var retried = post();
        assertEquals(1, retried.at("/ba1Calls/provider").asInt());
        assertEquals(failed.path("runId"), retried.at("/candidates/1/ba1ReusedFromRunId"));
        assertEquals("SUCCEEDED", retried.at("/candidates/0/ba1/evidence/resourceExecution/status").asText());
        assertEquals(failed, base.service.read(base.analysisId, failed.path("runId").asText()));
        assertNotEquals(failed.path("runId"), retried.path("runId"));
        base.recreateRepositories(); wire();
        assertEquals(retried, post());
        assertEquals(before + 1, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    @Test void successfulPopulationResultIsNotReusedAcrossDataClassIdentity() throws Exception {
        base.request.putArray("hypotheses"); base.request.putNull("patient");
        var first = post(); assertEquals(2, first.at("/ba1Calls/provider").asInt());
        configure("0.055", "9000", "HIT");
        base.request.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        var changed = post();
        assertNotEquals(first.path("runId"), changed.path("runId"));
        assertEquals(2, changed.at("/ba1Calls/provider").asInt());
        for (var row : changed.path("candidates")) assertFalse(row.has("ba1ReusedFromRunId"));
    }
    @Test void explicitRetryStartsFreshPm2PopulationRuntimeWhileSameRequestDoesNot() throws Exception {
        Path script = temporary.resolve("synthetic-tabix.cmd"); String successful = Files.readString(script);
        Files.writeString(script, "@echo off\r\necho synthetic query timed out 1>&2\r\nexit /b 7\r\n");
        var instances = new ArrayList<Pm2SupportingRuntime>();
        base.service = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources,
                () -> new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources,
                        new ExplicitAlphaMissenseDispatcher(base.mapper, am)),
                () -> new AcmgBusinessService.Ba1Runtime(base.producer, ba1), AcmgRecommendations.unavailable(base.mapper), () -> {
                    var value = new Pm2SupportingRuntime(base.mapper, populationSettings(script.toString())); instances.add(value); return value;
                });
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        var failed = post(); assertEquals(1, instances.size());
        // This HBB fixture has no enabled policy: policy selection now prevents the query entirely.
        assertEquals("NOT_EVALUATED", failed.at("/candidates/0/attempts/0/pm2/evaluationResult/status").asText());
        assertEquals(0, failed.at("/pm2Calls/provider").asInt());
        Files.writeString(script, successful); assertEquals(failed, post()); assertEquals(1, instances.size());
        base.request.put("retryOf", failed.path("runId").asText()); var retry = post();
        assertEquals(2, instances.size());
        assertEquals("NOT_EVALUATED", retry.at("/candidates/0/attempts/0/pm2/evaluationResult/status").asText());
        assertEquals(0, retry.at("/pm2Calls/provider").asInt());
        assertEquals(retry, post()); assertEquals(2, instances.size());
        assertEquals(failed, base.service.read(base.analysisId, failed.path("runId").asText()));
    }
    @Test void currentRulesPersistReadAfterServiceRecreationAndSameRequestDoesNotExecuteAgain() throws Exception {
        var result = post(); assertEquals("MET", ba1Status(result, 0), result.toPrettyString());
        assertEquals(4, result.path("providerCalls").asInt()); assertEquals(4, result.path("evaluatorCalls").asInt());
        assertEquals(2, result.at("/ba1Calls/provider").asInt()); assertEquals(2, am.calls);
        assertEquals(base.mapper.valueToTree(new AcmgExecutionProfile(base.mapper).criteria()), result.path("enabledCriteria"));
        assertEquals("PM2_RUNTIME_NOT_CONFIGURED", result.at("/candidates/0/attempts/0/pm2/evaluationResult/reasonCode").asText());
        assertEquals("PVS1_RUNTIME_NOT_CONFIGURED", result.at("/candidates/0/attempts/0/pvs1/evaluationResult/reasonCode").asText());
        assertTrue(result.at("/candidates/0/enabledRuleResultsComplete").asBoolean());
        assertEquals(2, result.path("bundles").size(), "Existing context-bound AM bundles remain valid and separate from allele-only BA1");
        base.recreateRepositories(); wire();
        var read = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, result.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(result, read); assertEquals(result, post()); assertEquals(2, am.calls);
        assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertEquals(1, base.store.runs(base.analysisId).size());
        String publicJson = result.toString(); assertFalse(publicJson.contains("synthetic-proband")); assertFalse(publicJson.contains(temporary.toString()));
        assertFalse(result.at("/candidates/0/ba1/evaluation").has("contextId"));
        var evaluation = ((ObjectNode) result.at("/candidates/0/ba1/evaluation")).deepCopy();
        String identity = evaluation.remove("evaluationId").asText(); evaluation.remove(List.of("conflict", "review"));
        assertEquals("ba1-allele-evaluation-sha256-" + new AcmgBusinessArtifacts(base.mapper).requestHash(evaluation), identity);
    }
    @Test void noPatientDiseaseTranscriptOrHypothesesAreRequiredForBa1() throws Exception {
        base.request.putNull("patient"); base.request.putArray("hypotheses");
        var result = post(); assertEquals("MET", ba1Status(result, 0), result.toString());
        assertEquals(0, am.calls); assertEquals(2, result.at("/ba1Calls/provider").asInt());
        assertTrue(result.at("/candidates/0/attempts/0/criterionEvaluations").isEmpty());
        assertEquals(0, result.path("bundles").size());
    }
    @Test void manyHypothesesDoNotRepeatPopulationQueriesAndKeepOrder() throws Exception {
        var extra = ((ObjectNode) base.request.path("hypotheses").get(0)).deepCopy();
        ((ObjectNode) extra.path("hypothesis")).put("hypothesisId", "second");
        ((ArrayNode) base.request.path("hypotheses")).add(extra);
        var result = post(); assertEquals(3, am.calls); assertEquals(2, result.at("/ba1Calls/provider").asInt());
        assertEquals(2, result.at("/candidates/0/attempts").size());
        assertEquals(base.jobs.findRankingPage(base.analysisId, 0, 2).get(0).getCandidateId(), result.path("candidateIds").get(0).asText());
    }
    @ParameterizedTest @CsvSource({"0.05,9000,HIT,NOT_MET", "0.06,1999,HIT,NOT_EVALUATED", "0.06,9000,NO_RECORD,NOT_EVALUATED", "0.06,9000,FAILED,NOT_EVALUATED"})
    void populationGatesAndFailureCannotBlockAmOrInventBa1(String score, String an, String mode, String expected) throws Exception {
        configure(score, an, mode); var result = post(); assertEquals(expected, ba1Status(result, 0), result.toString());
        assertEquals(2, am.calls); assertEquals("MET", result.at("/candidates/0/attempts/0/criterionEvaluations/0/evaluationResult/status").asText());
        assertEquals(2, result.at("/ba1Calls/evaluator").asInt());
    }
    @Test void oversizedReceiptOrdinalBlocksOnlyItsCandidateForAllRules() throws Exception {
        ObjectNode snapshot = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        ((ObjectNode) snapshot.at("/candidates/0/matchedRecord")).put("recordOrdinal", 4294967297L);
        base.replaceSnapshot(snapshot); var result = post();
        assertTrue(result.at("/candidates/0/ba1/blockers").toString().contains("ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED"));
        assertEquals("MET", ba1Status(result, 1)); assertEquals(1, result.at("/ba1Calls/provider").asInt()); assertEquals(1, am.calls);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={2,3,4,5,6})
    void historicalProfilesAndChangedPm2PolicyRemainReadableAndCannotReuseCurrentRun(int criteria) throws Exception {
        var declaration = base.mapper.valueToTree(AcmgSubmission.parse(base.mapper.writeValueAsBytes(base.request), base.mapper));
        com.fasterxml.jackson.databind.JsonNode savedRequest = declaration;
        ObjectNode oldProfile = null;
        if (criteria >= 3) {
            String resource = criteria == 3 ? "e4-b2-v1" : criteria == 4 ? "e4-b3-v1" : criteria == 5 ? "pm2-v1" : "pvs1-hnf1a-v1";
            try (var input = getClass().getResourceAsStream("/acmg/acmg-business-execution-profile-" + resource + ".json")) {
                oldProfile = (ObjectNode) base.mapper.readTree(input);
            }
            if (criteria == 5) ((ObjectNode)oldProfile.at("/slices/4")).put("profileCanonicalSha256", "a".repeat(64));
            oldProfile.put("manifestSha256", new AcmgBusinessArtifacts(base.mapper).requestHash(oldProfile));
            savedRequest = base.mapper.createObjectNode().set("declaration", declaration);
            ((ObjectNode) savedRequest).set("executionProfile", oldProfile);
        }
        String hash = new AcmgBusinessArtifacts(base.mapper).requestHash(savedRequest), id = UUID.randomUUID().toString();
        base.store.claim(new AcmgBusinessStore.Run(id, base.analysisId, hash, base.mapper.writeValueAsString(savedRequest), "RUNNING", "2026-09-07T00:00:00Z", null, null, null));
        var privateData = base.mapper.createObjectNode().set("declaration", declaration); String privateJson = base.mapper.writeValueAsString(privateData);
        var old = base.mapper.createObjectNode().put("analysisId", base.analysisId).put("runId", id).put("requestSha256", hash).put("status", "COMPLETED")
                .put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
        if (oldProfile != null) { old.set("executionProfile", oldProfile); old.set("enabledCriteria", oldProfile.path("enabledCriteria")); }
        if (criteria == 6) old.putArray("candidates").addObject().put("enabledRuleResultsComplete", false);
        base.store.finish(id, "COMPLETED", base.mapper.writeValueAsString(old), privateJson);
        assertEquals(old, base.service.read(base.analysisId, id)); var current = post();
        assertNotEquals(id, current.path("runId").asText()); assertNotEquals(hash, current.path("requestSha256").asText());
        assertEquals(old, base.service.read(base.analysisId, id)); assertEquals(2, base.store.runs(base.analysisId).size());
    }
    @Test void completenessChecksExactIdsNotOnlyAListLength() {
        var profile = new AcmgExecutionProfile(base.mapper);
        assertFalse(AcmgExecutionProfile.exactCriteria(List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BP4"), profile.criteria()));
        assertFalse(AcmgExecutionProfile.exactCriteria(profile.contextCriteria(), profile.criteria()));
        assertTrue(AcmgExecutionProfile.exactCriteria(profile.criteria(), profile.criteria()));
    }
    @Test void pvs1CompletenessAcceptsEvaluationsAndRejectsMissingOrPartialResults() throws Exception {
        var saved = post();
        var result = saved.deepCopy();
        var attempt = (ObjectNode) result.at("/candidates/0/attempts/0");
        var disposition = (ObjectNode) attempt.path("pvs1").deepCopy();
        // Reuse the existing evaluator fixture's complete result shape. These are
        // structural summary tests, not fabricated scientific PVS1 observations.
        var evaluation = (ObjectNode) attempt.path("criterionEvaluations").get(0).deepCopy();
        evaluation.put("criterionId", Hnf1aPvs1Runtime.CRITERION).put("baseStrength", "VERY_STRONG");
        var full = base.mapper.createObjectNode().put("criterionId", Hnf1aPvs1Runtime.CRITERION);
        full.set("evaluation", evaluation);
        for (String state : List.of("MET", "NOT_MET", "NOT_EVALUATED")) {
            evaluation.put("appliedStrength", state.equals("MET") ? "VERY_STRONG" : "NONE");
            evaluation.putObject("evaluationResult").put("status", state).put("reasonCode", "SYNTHETIC_RESULT");
            attempt.set("pvs1", full); assertCompleteness(result, true);
        }
        var incompleteEvaluation = full.deepCopy(); ((ObjectNode) incompleteEvaluation.path("evaluation")).remove("evaluationId");
        var emptyEvaluation = disposition.deepCopy(); emptyEvaluation.putObject("evaluation");
        var missingReason = disposition.deepCopy(); ((ObjectNode) missingReason.path("evaluationResult")).remove("reasonCode");
        var blankReason = disposition.deepCopy(); ((ObjectNode) blankReason.path("evaluationResult")).put("reasonCode", " ");
        var wrongCriterion = disposition.deepCopy().put("criterionId", "ACMG_AMP_2015:PM2");
        var incompleteApplicability = disposition.deepCopy(); incompleteApplicability.putObject("criterionApplicability").put("status", "NOT_APPLICABLE");
        for (var invalid : List.of(base.mapper.createObjectNode(), incompleteEvaluation, emptyEvaluation,
                missingReason, blankReason, wrongCriterion, incompleteApplicability)) {
            attempt.set("pvs1", invalid); assertCompleteness(result, false);
        }
        attempt.remove("pvs1"); assertCompleteness(result, false);
        attempt.putNull("pvs1"); assertCompleteness(result, false);
        attempt.set("pvs1", disposition); assertCompleteness(result, true);
        disposition.putObject("criterionApplicability").put("status", "NOT_APPLICABLE").put("reasonCode", "PVS1_POLICY_NOT_APPLICABLE");
        assertCompleteness(result, true);
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()), "Summary probes must not alter stored responses");
        assertEquals(2, am.calls); assertEquals(2, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    @Test void pvs1CompletenessRequiresEveryAttemptAndAtLeastOneAttempt() throws Exception {
        var result = post().deepCopy();
        var attempts = (ArrayNode) result.at("/candidates/0/attempts");
        var first = (ObjectNode) attempts.get(0); var second = first.deepCopy();
        attempts.add(second); assertCompleteness(result, true);
        var value = second.remove("pvs1"); assertCompleteness(result, false);
        second.set("pvs1", value); first.remove("pvs1"); assertCompleteness(result, false);
        first.set("pvs1", value); assertCompleteness(result, true);
        attempts.removeAll(); assertCompleteness(result, false);
    }
    private void assertCompleteness(ObjectNode response, boolean expected) throws Exception {
        var job = base.jobs.findById(base.analysisId).orElseThrow();
        var ranked = base.jobs.findInterpretations(base.analysisId, 0, 2);
        var declaration = AcmgSubmission.parse(base.mapper.writeValueAsBytes(base.request), base.mapper);
        ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service), "appendBa1", job, declaration, ranked,
                base.sources.recover(job, ranked), response, base.mapper.createObjectNode(), declaration.hypotheses());
        assertEquals(expected, response.at("/candidates/0/enabledRuleResultsComplete").asBoolean());
        assertTrue(response.at("/candidates/1/enabledRuleResultsComplete").asBoolean(), "Unchanged sibling remains complete");
    }
    @Test @EnabledIfSystemProperty(named="varvis.e4b2.smoke", matches="true")
    void oneE3P1aPublicFixedPointThroughBusinessAndH2Readback() throws Exception {
        // Real REF + one actual joint query; ranking/annotation runner and patient data remain synthetic engineering inputs.
        base.variantTsvOverride = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv("1\t1\t12591\tC\tT\tHBB\t3043\tAR\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf("chr1\t12591\tE3-P1A-public\tC\tT\t.\tPASS\t.\tGT\t.", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t."));
        Path root = Path.of(System.getenv("VARVIS_RESOURCE_ROOT")); var checksum = base.fixture.sources.get(0).resolve(base.fixture.reference).path("actualChecksumReceipt");
        var settings = new VariantAnalysisProperties(); settings.getNormalization().setGrch38Reference(root.resolve(checksum.path("logicalName").asText()).toString());
        settings.getNormalization().setGrch38ReferenceSha256(checksum.path("expectedSha256").asText()); settings.getNormalization().setGrch38ProfileId("grch38-primary-chr");
        base.producer = new NativeSnvSourceProducer(base.mapper, settings, root, List.of(base.fixture.sources.get(0)), base.fixture.reference);
        ba1 = new Ba1AlleleRuntime(base.mapper, populationSettings(root.resolve("Tool/msys64/ucrt64/bin/tabix.exe").toString())); wire();
        base.request.put("topN", 1).putNull("patient"); base.request.putArray("hypotheses");
        var result = post(); Files.writeString(Path.of("target/e4-b2-public-fixed-point.json"), result.toPrettyString());
        assertEquals("NOT_MET", ba1Status(result, 0), "Bounded real smoke blocked: inspect the retained actual result; no retries");
        assertEquals(1, result.at("/ba1Calls/provider").asInt()); assertEquals(1, result.at("/ba1Calls/evaluator").asInt()); assertEquals(0, am.calls);
        base.recreateRepositories(); wire(); assertEquals(result, post());
    }
}
