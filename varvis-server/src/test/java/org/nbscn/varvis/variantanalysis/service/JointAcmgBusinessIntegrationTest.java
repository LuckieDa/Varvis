package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile;
import org.nbscn.varvis.variantanalysis.provider.ClinVarMissenseBusinessRuntime;
import org.nbscn.varvis.variantanalysis.provider.Hnf1aPvs1Runtime;
import org.nbscn.varvis.variantanalysis.provider.Hnf1aPm1Runtime;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Joint engineering regression: MockMvc controllers/private H2 for saved results; synthetic inputs for append gates.
 * Exact public allele/transcript fields exercise production runtimes, not a new real-resource acceptance. */
class JointAcmgBusinessIntegrationTest {
    @TempDir Path temporary;
    private Ba1BusinessIntegrationTest population;
    private AcmgBusinessIntegrationTest base;

    @BeforeEach void setup() throws Exception {
        population = new Ba1BusinessIntegrationTest(); population.temporary = temporary; population.setup();
        base = population.base;
    }
    @AfterEach void close() { if (population != null) population.close(); }

    @Test void incompleteBusinessAttemptKeepsPopulationRefusalInsteadOfFailingRun() throws Exception {
        ObjectNode document;
        try (var source = getClass().getResourceAsStream("/acmg/generalization/population-myh7-hcm-bundle-v1.json")) {
            if (source == null) throw new AssertionError("Reviewed policy candidate missing");
            document = (ObjectNode) base.mapper.readTree(source);
        }
        var bundle = new org.nbscn.varvis.variantanalysis.provider.PopulationFrequencyBundle(base.mapper, document,
                new AcmgBusinessArtifacts(base.mapper).requestHash(document));
        population.wire(bundle);
        var response = base.mapper.createObjectNode().put("status", "COMPLETED_WITH_BLOCKERS");
        response.putArray("candidates").addObject().put("candidateId", "synthetic-blocked-candidate")
                .putArray("attempts").addObject().putNull("context");
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service), "appendPopulationFrequency", null, response));
        var frequency = response.at("/candidates/0/attempts/0/populationFrequency");
        assertEquals(2, frequency.path("criterionEvaluations").size());
        assertEquals(0, response.at("/populationFrequencyCalls/evaluator").asInt());
        assertEquals("NOT_COMBINABLE", frequency.at("/overlap/status").asText());
    }

    @Test void optionalPopulationPolicyChangesPostIdentityAndSavedGetSurvivesRebuildWithoutNewCalls() throws Exception {
        var old = population.post();
        byte[] oldBytes = getSavedBytes(old.path("runId").asText());
        var bundle = org.nbscn.varvis.variantanalysis.provider.PopulationFrequencyBundle.unavailable(base.mapper, "a".repeat(64));
        population.wire(bundle);
        var current = population.post();
        assertNotEquals(old.path("runId"), current.path("runId"));
        assertNotEquals(old.path("requestSha256"), current.path("requestSha256"));
        assertEquals("acmg-explicit-business-generalization-v1", current.at("/executionProfile/profileId").asText());
        assertEquals(0, current.at("/populationFrequencyCalls/provider").asInt());
        assertEquals(0, current.at("/populationFrequencyCalls/evaluator").asInt());
        for (var row : current.path("candidates")) for (var attempt : row.path("attempts"))
            for (var evaluation : attempt.at("/populationFrequency/criterionEvaluations"))
                assertEquals("POPULATION_POLICY_CONFIGURATION_UNAVAILABLE", evaluation.at("/evaluationResult/reasonCode").asText());
        int amCalls = population.am.calls;
        int populationQueries = Files.readAllLines(temporary.resolve("queries.txt")).size();
        byte[] currentBytes = getSavedBytes(current.path("runId").asText());
        assertEquals(current, population.post());
        base.closeDatabase(); base.recreateRepositories(); population.wire(bundle);
        assertEquals(current, population.post());
        assertEquals(amCalls, population.am.calls);
        assertEquals(populationQueries, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertArrayEquals(oldBytes, getSavedBytes(old.path("runId").asText()));
        assertArrayEquals(currentBytes, getSavedBytes(current.path("runId").asText()));
        population.wire(org.nbscn.varvis.variantanalysis.provider.PopulationFrequencyBundle.unavailable(base.mapper, "b".repeat(64)));
        assertNotEquals(current.path("runId"), population.post().path("runId"));
    }

    @Test void reviewedRuleConfigurationHasIndependentDurableIdentityWithoutReinterpretingSavedRuns() throws Exception {
        var old = population.post();
        byte[] oldBytes = getSavedBytes(old.path("runId").asText());
        var bundle = org.nbscn.varvis.variantanalysis.provider.ReviewedRuleBundle.unavailable(base.mapper, "c".repeat(64));
        population.wire(null, bundle);
        var current = population.post();
        assertNotEquals(old.path("runId"), current.path("runId"));
        assertEquals("acmg-explicit-business-generalization-v2", current.at("/executionProfile/profileId").asText());
        for (var row : current.path("candidates")) for (var attempt : row.path("attempts"))
            for (String field : List.of("pvs1", "functional")) {
                assertEquals("REVIEWED_RULE_CONFIGURATION_UNAVAILABLE", attempt.path(field).at("/evaluationResult/reasonCode").asText());
                assertFalse(attempt.path(field).path("adoptionSupported").asBoolean(true));
            }
        int amCalls = population.am.calls;
        int queries = Files.readAllLines(temporary.resolve("queries.txt")).size();
        byte[] currentBytes = getSavedBytes(current.path("runId").asText());
        assertEquals(current, population.post());
        base.closeDatabase(); base.recreateRepositories(); population.wire(null, bundle);
        assertEquals(current, population.post());
        assertArrayEquals(oldBytes, getSavedBytes(old.path("runId").asText()));
        assertArrayEquals(currentBytes, getSavedBytes(current.path("runId").asText()));
        assertEquals(amCalls, population.am.calls);
        assertEquals(queries, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }

    @Test void explicitRuleDisablePrecedesNewBundleSelectionAndEvaluation() throws Exception {
        var bundle = org.nbscn.varvis.variantanalysis.provider.ReviewedRuleBundle.unavailable(base.mapper, "c".repeat(64));
        population.wire(null, bundle, true);
        var current = population.post();
        for (var row : current.path("candidates")) for (var attempt : row.path("attempts")) {
            assertEquals("PVS1_BUSINESS_EXECUTION_DISABLED", attempt.at("/pvs1/evaluationResult/reasonCode").asText());
            assertEquals("PS3_BUSINESS_EXECUTION_DISABLED", attempt.at("/functional/evaluationResult/reasonCode").asText());
            assertFalse(attempt.path("pvs1").has("bundleIdentity"), "Disabled rules must not reach optional bundle selection");
            assertFalse(attempt.path("functional").has("bundleIdentity"));
        }
        assertEquals(0, current.at("/pvs1Calls/evaluator").asInt());
        assertEquals(0, current.at("/functionalCalls/provider").asInt());
        assertEquals(0, current.at("/functionalCalls/evaluator").asInt());
    }

    @Test void ruleContextPreparationHasSeparateDurableIdentityAndNoCallsOnSavedReuse() throws Exception {
        var document = org.nbscn.varvis.variantanalysis.provider.ReviewedRuleContextPreparationTest.emptyBundleDocument(base.mapper, "1");
        var bundle = new org.nbscn.varvis.variantanalysis.provider.ReviewedRuleBundle(base.mapper, document,
                new AcmgBusinessArtifacts(base.mapper).requestHash(document));
        population.wire(null, bundle);
        var previous = population.post();
        byte[] previousBytes = getSavedBytes(previous.path("runId").asText());
        var capability = new org.nbscn.varvis.variantanalysis.provider.ReviewedRuleContextPreparation(base.mapper, bundle.identity());
        population.wire(null, bundle, false, capability);
        var current = population.post();
        assertNotEquals(previous.path("runId"), current.path("runId"));
        assertNotEquals(previous.path("requestSha256"), current.path("requestSha256"));
        assertEquals("acmg-explicit-business-generalization-v3", current.at("/executionProfile/profileId").asText());
        assertEquals(capability.identity(), current.at("/executionProfile/ruleContextPreparationBinding"));
        int amCalls = population.am.calls;
        int queries = Files.readAllLines(temporary.resolve("queries.txt")).size();
        byte[] currentBytes = getSavedBytes(current.path("runId").asText());
        assertEquals(current, population.post());
        base.closeDatabase(); base.recreateRepositories(); population.wire(null, bundle, false, capability);
        assertEquals(current, population.post());
        assertArrayEquals(previousBytes, getSavedBytes(previous.path("runId").asText()));
        assertArrayEquals(currentBytes, getSavedBytes(current.path("runId").asText()));
        assertEquals(amCalls, population.am.calls);
        assertEquals(queries, Files.readAllLines(temporary.resolve("queries.txt")).size());
    }
    @Test void predictionUnavailableSuccessorPersistsAndSavedReuseNeverQueries() throws Exception {
        var previous = population.post(); byte[] previousBytes = getSavedBytes(previous.path("runId").asText());
        var prediction = org.nbscn.varvis.variantanalysis.provider.ReviewedPredictionBusinessRuntime.unavailable(base.mapper, "a".repeat(64), "b".repeat(64));
        for (var hypothesis : base.request.path("hypotheses")) ((ObjectNode) hypothesis.path("transcript")).put("selectionPolicy", prediction.contexts().selectionPolicy());
        population.wire(null, null, false, null, prediction);
        int beforeAm = population.am.calls;
        var current = population.post(); byte[] currentBytes = getSavedBytes(current.path("runId").asText());
        assertEquals(beforeAm, population.am.calls);
        assertEquals("acmg-explicit-business-generalization-v4", current.at("/executionProfile/profileId").asText());
        assertEquals(prediction.identity(), current.at("/executionProfile/predictionBusinessBinding"));
        assertNotEquals(previous.path("runId"), current.path("runId"));
        assertNotEquals(previous.path("requestSha256"), current.path("requestSha256"));
        assertTrue(current.at("/candidates/0/attempts/0/transcriptInput/reviewedPrediction").isObject(), current.toPrettyString());
        int queries = Files.readAllLines(temporary.resolve("queries.txt")).size();
        assertEquals(current, population.post());
        base.closeDatabase(); base.recreateRepositories();
        population.wire(null, null, false, null, org.nbscn.varvis.variantanalysis.provider.ReviewedPredictionBusinessRuntime.unavailable(base.mapper, "a".repeat(64), "b".repeat(64)));
        assertEquals(current, population.post());
        assertArrayEquals(currentBytes, getSavedBytes(current.path("runId").asText()));
        assertArrayEquals(previousBytes, getSavedBytes(previous.path("runId").asText()));
        assertEquals(beforeAm, population.am.calls); assertEquals(queries, Files.readAllLines(temporary.resolve("queries.txt")).size());
        var changed = org.nbscn.varvis.variantanalysis.provider.ReviewedPredictionBusinessRuntime.unavailable(base.mapper, "c".repeat(64), "d".repeat(64));
        population.wire(null, null, false, null, changed);
        var next = population.post(); assertNotEquals(current.path("requestSha256"), next.path("requestSha256"));
        assertArrayEquals(currentBytes, getSavedBytes(current.path("runId").asText()));
        assertEquals(beforeAm, population.am.calls);
    }

    @Test void ordinaryPopulationConfirmationDoesNotAuthorizeOtherwiseEligiblePvs1() throws Exception {
        var helpers = new AcmgRecommendationsTest();
        var candidate = hnf1a();
        var offered = helpers.candidate("HNF1A", candidate.getTranscriptAnnotations().get(0));
        when(offered.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        when(offered.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(offered.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var catalog = helpers.catalog("Definitive"); catalog.put("hgncId", "HGNC:11621");
        ((ObjectNode) catalog.at("/relationships/0")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A")
                .put("mondoId", "MONDO:0015967").put("moi", "AD");
        var policy = AcmgRecommendations.unavailable(base.mapper);
        var recommendation = policy.recommend("synthetic-joint-confirmation", offered, catalog, "a".repeat(64));
        assertTrue(recommendation.at("/pm2ContextPreparation/available").asBoolean());
        var selection = new AcmgRecommendations.Selection(recommendation.path("candidateId").asText(),
                recommendation.path("recommendationId").asText(), AcmgRecommendations.VERSION,
                recommendation.path("policyHash").asText(), null, recommendation.path("stableRelationshipKey").asText(), null);
        var request = new AcmgSubmission(1, "ordinary-ui-v3", null, "synthetic-proband", "SYNTHETIC", null, List.of(), List.of(selection));
        policy.verify(selection, recommendation, List.of());
        var prepared = policy.withPopulationContexts(request, base.mapper.createArrayNode().add(recommendation));
        assertEquals(1, prepared.size());
        assertEquals(AcmgRecommendations.FREQUENCY_CONTEXT_SELECTION, prepared.get(0).transcript().selectionPolicy());
        var context = context(candidate, "HGNC:11621", "MONDO:0015967", "ENST00000257555", 11);
        ((ObjectNode) context.path("transcript")).put("selectionPolicy", prepared.get(0).transcript().selectionPolicy());
        assertPvs1ConfirmationGate(candidate, context, "SELECTION_POLICY");
    }

    @ParameterizedTest @ValueSource(strings = {"SELECTION_POLICY", "PREDICTION_PATH", "BOTH"})
    void bothPopulationOnlyMarkersBlockPvs1AndMissenseBeforeScientificCalls(String marker) throws Exception {
        var pvs1 = hnf1a();
        assertPvs1ConfirmationGate(pvs1, context(pvs1, "HGNC:11621", "MONDO:0015967", "ENST00000257555", 11), marker);
        var braf = braf();
        var context = context(braf, "HGNC:1097", "MONDO:0021060", "ENST00000646891", 2);
        var attempts = response(braf, context);
        mark((ObjectNode) attempts.at("/candidates/0/attempts/0"), marker);
        var dispatches = new AtomicInteger();
        Supplier<ClinVarMissenseBusinessRuntime> factory = () -> {
            dispatches.incrementAndGet();
            return new ClinVarMissenseBusinessRuntime(base.mapper, null, null);
        };
        ReflectionTestUtils.setField(Objects.requireNonNull(base.service), "missenseRuntime", factory);
        ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service), "appendMissense", List.of(braf), attempts);
        assertEquals(0, dispatches.get());
        assertEquals("PS1_PM5_TRANSCRIPT_CONFIRMATION_REQUIRED", attempts.at("/candidates/0/attempts/0/missenseComparison/blockers/0/reasonCode").asText());
        assertEquals(0, attempts.at("/missenseCalls/provider").asInt());
        assertEquals(0, attempts.at("/missenseCalls/evaluator").asInt());
        assertEquals(0, attempts.at("/missenseCalls/rawBatch").asInt());

        var explicit = response(braf, context);
        ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service), "appendMissense", List.of(braf), explicit);
        assertEquals(1, dispatches.get(), "Explicit transcript must reach the production missense runtime");
        assertEquals("COMPARATOR_INPUT_UNAVAILABLE", explicit.at("/candidates/0/attempts/0/missenseComparison/blockers/0/reasonCode").asText(),
                "The synthetic test deliberately has no external comparator; the valid context passed scientific scope admission");
    }

    private void assertPvs1ConfirmationGate(VariantInterpretation candidate, ObjectNode context, String marker) throws Exception {
        var runtime = new Hnf1aPvs1Runtime(base.mapper);
        ReflectionTestUtils.setField(Objects.requireNonNull(base.service), "pvs1Runtime", (Supplier<Hnf1aPvs1Runtime>) () -> runtime);
        var rows = base.jobs.findInterpretations(base.analysisId, 0, 2);
        var recovered = base.sources.recover(base.jobs.findById(base.analysisId).orElseThrow(), rows);
        var blocked = response(candidate, context);
        mark((ObjectNode) blocked.at("/candidates/0/attempts/0"), marker);
        ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service), "appendPvs1", List.of(candidate), recovered, blocked);
        assertEquals("PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED", blocked.at("/candidates/0/attempts/0/pvs1/evaluationResult/reasonCode").asText());
        assertEquals(0, runtime.evaluatorCalls()); assertEquals(0, runtime.storedVepProjections());

        var selected = context.deepCopy();
        ((ObjectNode) selected.path("transcript")).put("selectionPolicy", AcmgRecommendations.POLICY_ID + "@" + AcmgRecommendations.VERSION);
        selected.put("contextId", new RuntimeEvidenceV2Adapter(base.mapper).canonicalContextId(selected));
        var explicit = response(candidate, selected);
        ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service), "appendPvs1", List.of(candidate), recovered, explicit);
        assertEquals("MET", explicit.at("/candidates/0/attempts/0/pvs1/evaluation/evaluationResult/status").asText());
        assertEquals("VERY_STRONG", explicit.at("/candidates/0/attempts/0/pvs1/evaluation/appliedStrength").asText());
        assertEquals(1, runtime.evaluatorCalls()); assertEquals(1, runtime.storedVepProjections());
    }

    @ParameterizedTest @ValueSource(strings = {"pm2", "pvs1", "pm1", "missenseComparison"})
    void completenessRequiresEveryAttemptAndRejectsMissingEmptyOrUnreasonedResults(String field) throws Exception {
        var saved = population.post();
        var result = saved.deepCopy();
        var attempts = (ArrayNode) result.at("/candidates/0/attempts");
        var first = (ObjectNode) attempts.get(0); var second = first.deepCopy(); attempts.add(second);
        assertCompleteness(result, true);
        var original = Objects.requireNonNull(second.remove(field));
        assertCompleteness(result, false);
        second.putObject(field); assertCompleteness(result, false);
        var unreasoned = original.deepCopy();
        if ("missenseComparison".equals(field)) {
            ((ObjectNode) unreasoned).putArray("blockers");
            for (var evaluation : unreasoned.path("criterionEvaluations")) {
                ((ObjectNode) evaluation.path("evaluationResult")).remove("reasonCode");
                ((ObjectNode) evaluation.path("criterionApplicability")).remove("reasonCode");
            }
        } else ((ObjectNode) unreasoned.path("evaluationResult")).remove("reasonCode");
        second.set(field, unreasoned); assertCompleteness(result, false);
        var emptyEvaluation = base.mapper.createObjectNode();
        if ("missenseComparison".equals(field)) {
            var list = emptyEvaluation.putArray("criterionEvaluations");
            list.addObject().put("criterionId", "ACMG_AMP_2015:PS1"); list.addObject().put("criterionId", "ACMG_AMP_2015:PM5");
        } else emptyEvaluation.put("criterionId", "ACMG_AMP_2015:" + field.toUpperCase(java.util.Locale.ROOT)).putObject("evaluation");
        second.set(field, emptyEvaluation); assertCompleteness(result, false);
        second.set(field, original); assertCompleteness(result, true);
        attempts.removeAll(); assertCompleteness(result, false);
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()), "Summary checks never rewrite saved snapshots");
    }

    @ParameterizedTest
    @ValueSource(strings = {"acmg-business-execution-profile-pvs1-hnf1a-v1.json",
            "acmg-business-execution-profile-joint-v1.json", "acmg-business-execution-profile-joint-v2.json",
            "acmg-business-execution-profile-joint-v3.json", "acmg-business-execution-profile-joint-v4.json", "acmg-business-execution-profile-joint-v5.json",
            "acmg-business-execution-profile-joint-v6.json", "acmg-business-execution-profile-bs1-hnf1a-v1.json",
            "history/bs1-335c8ac/acmg-business-execution-profile-joint-v4.json",
            "history/ps3-ee7289c/acmg-business-execution-profile-joint-v1.json",
            "acmg-business-execution-profile-joint-v7.json", "acmg-business-execution-profile-pm1-hnf1a-v1.json"})
    void historicalProfileGetIsImmutableAndSuccessorPostHasNewIdentityThenReusesAfterRebuild(String historicalResource) throws Exception {
        var declaration = base.mapper.valueToTree(AcmgSubmission.parse(base.mapper.writeValueAsBytes(base.request), base.mapper));
        ObjectNode historical;
        try (var input = getClass().getResourceAsStream("/acmg/" + historicalResource)) {
            historical = (ObjectNode) base.mapper.readTree(Objects.requireNonNull(input));
        }
        var artifacts = new AcmgBusinessArtifacts(base.mapper);
        historical.put("manifestSha256", artifacts.requestHash(historical));
        var oldRequest = base.mapper.createObjectNode(); oldRequest.set("declaration", declaration); oldRequest.set("executionProfile", historical);
        String oldHash = artifacts.requestHash(oldRequest), oldId = UUID.randomUUID().toString();
        base.store.claim(new AcmgBusinessStore.Run(oldId, base.analysisId, oldHash, base.mapper.writeValueAsString(oldRequest), "RUNNING", "2026-09-09T00:00:00Z", null, null, null));
        var privateData = base.mapper.createObjectNode().set("declaration", declaration); String privateJson = base.mapper.writeValueAsString(privateData);
        boolean beforePeptideFix = historicalResource.equals("acmg-business-execution-profile-joint-v2.json");
        String oldStatus = beforePeptideFix ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED";
        var old = base.mapper.createObjectNode().put("analysisId", base.analysisId).put("runId", oldId).put("requestSha256", oldHash)
                .put("status", oldStatus).put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
        old.set("executionProfile", historical); old.set("enabledCriteria", historical.path("enabledCriteria"));
        var oldCandidate = old.putArray("candidates").addObject().put("enabledRuleResultsComplete", beforePeptideFix);
        if (beforePeptideFix) {
            // Minimal cache fixture, not a scientific VEP envelope: preserve the former saved rejection shape.
            // The provider test separately proves the matching Y/SX sequence now evaluates to MET.
            oldCandidate.putArray("attempts").addObject().putObject("pvs1").putObject("evaluation").putObject("evaluationResult")
                    .put("status", "NOT_EVALUATED").put("reasonCode", "PVS1_FRAMESHIFT_PROTEIN_MISMATCH");
        }
        String oldJson = base.mapper.writeValueAsString(old);
        base.store.finish(oldId, oldStatus, oldJson, privateJson);
        byte[] oldHttpBytes = getSavedBytes(oldId);
        assertEquals(old, getSaved(oldId));
        if (beforePeptideFix) assertEquals("PVS1_FRAMESHIFT_PROTEIN_MISMATCH",
                getSaved(oldId).at("/candidates/0/attempts/0/pvs1/evaluation/evaluationResult/reasonCode").asText());
        assertEquals(0, population.am.calls); assertFalse(Files.exists(temporary.resolve("queries.txt")));
        var current = population.post();
        assertNotEquals(oldId, current.path("runId").asText()); assertNotEquals(oldHash, current.path("requestSha256").asText());
        assertEquals("acmg-explicit-business-joint-v8", current.at("/executionProfile/profileId").asText());
        assertEquals("1.7.0", current.at("/executionProfile/manifestVersion").asText());
        for (var criterion : historical.path("enabledCriteria"))
            assertTrue(base.mapper.convertValue(current.path("enabledCriteria"), List.class).contains(criterion.asText()),
                    "The successor retains every historical enabled rule");
        assertTrue(AcmgExecutionProfile.exactCriteria(base.mapper.convertValue(current.path("enabledCriteria"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }),
                List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BP7", "ACMG_AMP_2015:PM2", "ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5", "ACMG_AMP_2015:PVS1", "ACMG_AMP_2015:BS1", "ACMG_AMP_2015:PS3", "ACMG_AMP_2015:PM1")));
        int amCalls = population.am.calls, populationQueries = Files.readAllLines(temporary.resolve("queries.txt")).size();
        base.closeDatabase(); base.recreateRepositories(); population.wire();
        assertEquals(old, getSaved(oldId)); assertEquals(current, getSaved(current.path("runId").asText()));
        var repeated = population.post();
        assertEquals(current, repeated); assertEquals(current.path("runId"), repeated.path("runId"));
        assertEquals(amCalls, population.am.calls); assertEquals(populationQueries, Files.readAllLines(temporary.resolve("queries.txt")).size());
        assertArrayEquals(oldHttpBytes, getSavedBytes(oldId), "Reevaluation and repository reconstruction preserve historical HTTP bytes");
        assertEquals(oldJson, base.store.run(base.analysisId, oldId).orElseThrow().responseJson());
        assertEquals(2, base.store.runs(base.analysisId).size());
    }

    private ObjectNode getSaved(String run) throws Exception {
        return (ObjectNode) base.mapper.readTree(getSavedBytes(run));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"120988841,335,112,2,CCG,CTG,P,L,Pro,Leu,SUPPORTING", "120993601,608,203,3,CGT,CAT,R,H,Arg,His,MODERATE", "120999580,1721,574,9,AGC,AAC,S,N,Ser,Asn,NONE"})
    void pm1RunsWithUnavailablePredictionsAndPreservesTheirDisposition(int g,int c,int p,int exon,String refCodon,String altCodon,String aa,String altAa,String refName,String altName,String strength) throws Exception {
        int offset=(c-1)%3;
        var v=candidate(new NormalizedVariantKey("GRCh38","12",g,refCodon.substring(offset,offset+1),altCodon.substring(offset,offset+1)));
        v.applyTranscriptAnnotations(List.of(new TranscriptConsequence("HNF1A","ENSG00000135100","ENST00000257555",
                "ENST00000257555.11:c."+c+v.getObservation().getKey().getReference()+">"+v.getObservation().getKey().getAlternate(),
                "ENSP00000257555.5:p."+refName+p+altName,"missense_variant","ENSG00000135100","ENST00000257555","Ensembl","MODERATE",exon+"/10",null,
                true,true,false,Integer.toString(p),aa+"/"+altAa,refCodon+"/"+altCodon,null,null,null,"VEP_CACHED","COMPLETED","116.0",Instant.parse("2026-09-10T00:00:00Z"))));
        var ctx=context(v,"HGNC:11621","MONDO:0015967","ENST00000257555",11);
        var response=response(v,ctx); var attempt=(ObjectNode)response.at("/candidates/0/attempts/0");
        var prediction=attempt.putObject("transcriptInput").put("predictionPath","HNF1A_REVEL").put("reasonCode","REVEL_RESOURCE_UNAVAILABLE");
        attempt.putArray("blockers").addObject().put("reasonCode","REVEL_RESOURCE_UNAVAILABLE");
        var original=prediction.deepCopy(); var rows=base.jobs.findInterpretations(base.analysisId,0,2);
        var restored=base.sources.recover(base.jobs.findById(base.analysisId).orElseThrow(),rows);
        var runtime=new Hnf1aPm1Runtime(base.mapper);
        ReflectionTestUtils.setField(Objects.requireNonNull(base.service),"pm1Runtime",(Supplier<Hnf1aPm1Runtime>)()->runtime);
        ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service),"appendPm1",List.of(v),restored,response);
        assertEquals(strength,attempt.at("/pm1/evaluation/appliedStrength").asText());
        assertEquals(strength.equals("NONE")?"NOT_MET":"MET",attempt.at("/pm1/evaluation/evaluationResult/status").asText());
        assertEquals(original,attempt.path("transcriptInput")); assertEquals(0,population.am.calls);
        assertEquals(0,response.at("/pm1Calls/provider").asInt()); assertEquals(1,response.at("/pm1Calls/evaluator").asInt());
        assertEquals("COMPLETED",response.path("status").asText(),"A PM1 negative is an evaluated result, not a blocker");
        for(String marker:List.of("SELECTION_POLICY","PREDICTION_PATH","BOTH")) {
            var blocked=response(v,ctx);mark((ObjectNode)blocked.at("/candidates/0/attempts/0"),marker);
            ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service),"appendPm1",List.of(v),restored,blocked);
            assertEquals("PM1_TRANSCRIPT_CONFIRMATION_REQUIRED",blocked.at("/candidates/0/attempts/0/pm1/evaluationResult/reasonCode").asText());
            assertEquals(0,blocked.at("/pm1Calls/evaluator").asInt());
        }
    }
    private byte[] getSavedBytes(String run) throws Exception {
        return base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
    private void assertCompleteness(ObjectNode result, boolean expected) throws Exception {
        var job = base.jobs.findById(base.analysisId).orElseThrow(); var rows = base.jobs.findInterpretations(base.analysisId, 0, 2);
        ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service), "appendBa1", job,
                AcmgSubmission.parse(base.request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), base.mapper), rows,
                base.sources.recover(job, rows), result, base.mapper.createObjectNode(), List.of());
        assertEquals(expected, result.at("/candidates/0/enabledRuleResultsComplete").asBoolean());
        assertTrue(result.at("/candidates/1/enabledRuleResultsComplete").asBoolean(), "Unchanged sibling remains complete");
    }
    private ObjectNode response(VariantInterpretation candidate, ObjectNode context) {
        var response = base.mapper.createObjectNode().put("status", "COMPLETED").put("providerCalls", 0).put("evaluatorCalls", 0);
        response.putArray("bundles");
        response.putArray("candidates").addObject().put("candidateId", candidate.getCandidateId()).putArray("attempts").addObject().set("context", context.deepCopy());
        return response;
    }
    private void mark(ObjectNode attempt, String marker) {
        if (!"PREDICTION_PATH".equals(marker)) {
            var context = (ObjectNode) attempt.path("context");
            ((ObjectNode) context.path("transcript")).put("selectionPolicy", AcmgRecommendations.PM2_CONTEXT_SELECTION);
            context.put("contextId", new RuntimeEvidenceV2Adapter(base.mapper).canonicalContextId(context));
        }
        if (!"SELECTION_POLICY".equals(marker)) attempt.putObject("transcriptInput").put("predictionPath", "PM2_CONTEXT_ONLY").put("predictionTranscriptConfirmed", false);
    }
    private VariantInterpretation hnf1a() {
        var candidate = candidate(new NormalizedVariantKey("GRCh38", "12", 120989017, "C", "T"));
        candidate.applyTranscriptAnnotations(List.of(new TranscriptConsequence("HNF1A", "ENSG00000135100", "ENST00000257555",
                "ENST00000257555.11:c.511C>T", "ENSP00000257555.5:p.Arg171Ter", "stop_gained", "ENSG00000135100", "ENST00000257555", "Ensembl",
                "HIGH", "2/10", null, true, true, false, "171", "R/*", "CGA/TGA", null, null, null, "VEP_CACHED", "COMPLETED", "116.0", Instant.parse("2026-09-09T00:00:00Z"))));
        return candidate;
    }
    private VariantInterpretation braf() throws Exception {
        var candidate = candidate(new NormalizedVariantKey("GRCh38", "7", 140801537, "T", "A"));
        var annotation = base.mapper.createObjectNode().put("geneSymbol", "BRAF").put("transcriptId", "ENST00000646891.2")
                .put("transcriptAccession", "ENST00000646891.2").put("hgvsProtein", "ENSP00000493543.1:p.Leu245Phe")
                .put("consequence", "missense_variant").put("source", "VEP").put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-09-09T00:00:00Z");
        candidate.applyTranscriptAnnotations(List.of(Objects.requireNonNull(base.mapper.treeToValue(annotation, TranscriptConsequence.class)))); return candidate;
    }
    private VariantInterpretation candidate(NormalizedVariantKey key) {
        return new VariantInterpretation(key.canonicalId(), new VariantQcObservation(key, "synthetic-proband", "0/1", 30, 99, "18,12", 0.4d,
                "PASS", "SNV", "synthetic-joint-gate-input"), List.of(), List.of());
    }
    private ObjectNode context(VariantInterpretation candidate, String gene, String disease, String transcript, int version) throws Exception {
        ObjectNode context;
        try (var input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            context = (ObjectNode) base.mapper.readTree(Objects.requireNonNull(input)).path("context").deepCopy();
        }
        var key = candidate.getObservation().getKey(); var variant = (ObjectNode) context.path("variant");
        var allele = base.mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(field, allele.deepCopy());
        var adapter = new RuntimeEvidenceV2Adapter(base.mapper); variant.put("alleleId", adapter.canonicalAlleleId(allele, variant.path("normalization")));
        ((ObjectNode) context.path("gene")).put("hgncId", gene).put("symbol", candidate.getTranscriptAnnotations().get(0).getGeneSymbol());
        ((ObjectNode) context.path("disease")).put("mondoId", disease).put("label", "Synthetic joint dispatch context");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("accession", transcript).put("version", version).put("annotationSourceVersion", "116.0")
                .put("selectionPolicy", AcmgRecommendations.POLICY_ID + "@" + AcmgRecommendations.VERSION);
        context.put("contextId", adapter.canonicalContextId(context)); adapter.validateCompleteContext(context); return context;
    }
}
