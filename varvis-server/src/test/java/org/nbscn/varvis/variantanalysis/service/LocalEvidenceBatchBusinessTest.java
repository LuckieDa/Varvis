package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real publication, SQL repositories and isolated file H2; all fact transport is deliberately synthetic. */
class LocalEvidenceBatchBusinessTest {
    private static final String DECLARATION = "ordinary-local-computational-batch1-v1";
    @TempDir Path temporary;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessService legacy;
    private final AtomicInteger factCalls = new AtomicInteger();
    private final AtomicInteger populationFactories = new AtomicInteger();
    private boolean forbidExecution;
    private boolean predictionExplanation;
    private boolean benignPredictionPair;
    private final AtomicInteger proteinCalls = new AtomicInteger(), nativeCalls = new AtomicInteger();
    private GnomadLocalAllSites absenceResource;
    private Pm2LocalExomesProfile absenceProfile;
    private String configurationRevision = "synthetic-business-v1";

    @BeforeEach void setup() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        base.publish(false, GenomeAssembly.GRCh38, null);
        legacy = base.service;
        base.request.put("declarationVersion", DECLARATION);
        install(base.store);
        confirmRecommendations();
    }

    @AfterEach void close() { if (base != null) base.closeDatabase(); }

    private Supplier<LocalEvidenceBatchRuntime> localRuntime(java.util.function.Function<JsonNode, JsonNode> effects) {
        return () -> new LocalEvidenceBatchRuntime(base.mapper,
                base.mapper.createObjectNode().put("syntheticConfiguration", configurationRevision), () -> {
                    assertFalse(forbidExecution, "A saved read or exact repeat must not acquire a module");
                    populationFactories.incrementAndGet();
                    VariantEvidenceProvider provider = new VariantEvidenceProvider() {
                        @Override public String source() { return "GNOMAD"; }
                        @Override public String version() { return "gnomAD exomes v4.1 GRCh38"; }
                        @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                                List<VariantInterpretation> variants, AnalysisContext analysis) {
                            factCalls.incrementAndGet();
                            if (absenceResource != null) {
                                var profile = GnomadPopulationInputProfile.loadLocalBa1();
                                var binding = profile.getDocument().path("resources").get(0);
                                var details = new LinkedHashMap<String, Object>();
                                details.put("resourceExecutionStatus", "SUCCEEDED");
                                details.put("resourceAvailabilityStatus", "AVAILABLE");
                                details.put("queryOutcomeStatus", "NO_RECORD");
                                details.put("resourceApplicabilityStatus", "APPLICABLE");
                                details.put("populationResourceId", binding.path("resourceId").asText());
                                details.put("populationResourceIdentityFingerprintSha256", binding.path("identityFingerprintSha256").asText());
                                details.put("populationInputProfileId", profile.getProfileId());
                                details.put("populationInputProfileVersion", profile.getProfileVersion());
                                details.put("populationInputProfileSha256", profile.getProfileSha256());
                                var item = new EvidenceItem(source(), version(), "POPULATION", "NO_RECORD", null,
                                        "Synthetic absence", "Synthetic transport; not a real population assertion", details);
                                return Map.of(variants.get(0).getObservation().getKey(), List.of(item));
                            }
                            throw new IllegalStateException("SYNTHETIC_FACT_UNAVAILABLE");
                        }
                    };
                    try {
                        if (absenceResource != null) {
                            var constructor = Pm2LocalExomesRuntime.class.getDeclaredConstructor(
                                    com.fasterxml.jackson.databind.ObjectMapper.class, Pm2LocalExomesProfile.class,
                                    VariantEvidenceProvider.class, GnomadLocalAllSites.class);
                            constructor.setAccessible(true);
                            return constructor.newInstance(base.mapper, absenceProfile, provider, absenceResource);
                        }
                        var constructor = Pm2LocalExomesRuntime.class.getDeclaredConstructor(
                                com.fasterxml.jackson.databind.ObjectMapper.class, Pm2LocalExomesProfile.class, VariantEvidenceProvider.class);
                        constructor.setAccessible(true);
                        return constructor.newInstance(base.mapper, Pm2LocalExomesProfile.load(base.mapper), provider);
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                }, predictionExplanation ? () -> new SpliceAiProvider(new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties())
                        .openSharedSession(base.mapper) : null,
                predictionExplanation ? splice -> {
                    assertFalse(forbidExecution, "Exact reuse must not rebuild prediction execution");
                    if (benignPredictionPair) return benignPrediction(splice, effects);
                    var settings = new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
                    return new SelectedPredictionRuntime(base.mapper,
                            PredictionDevelopmentRuntime.configured(base.mapper, new AlphaMissenseProvider(settings)),
                            Hnf1aRevelRuntime.unavailable(base.mapper), null, null, splice,
                            plan -> base.mapper.createObjectNode().put("status", "UNAVAILABLE"));
                } : null, null);
    }

    private void install(AcmgBusinessStore store) {
        var suggestions = new AcmgRecommendations(base.mapper, candidate -> {
            throw new AssertionError("Local batch confirmation must not query prediction identities");
        });
        var service = new AcmgBusinessService(base.mapper, base.jobs, store, base.sources, base.runtime(),
                () -> { throw new AssertionError("The five-rule batch must not execute BA1"); }, suggestions);
        Path reviews = temporary.resolve("reviews");
        try { Files.createDirectories(reviews); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        base.service = AcmgBusinessService.localBatch(service, effects -> localRuntime(effects).get(), reviews);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    private void confirmRecommendations() {
        var options = base.service.inputOptions(base.analysisId, 2);
        var refs = base.request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var recommendation = options.at("/candidates/" + i + "/recommendation");
            var item = (ObjectNode) base.request.path("hypotheses").get(i);
            var hypothesis = (ObjectNode) item.path("hypothesis");
            hypothesis.put("hypothesisId", recommendation.path("hypothesisId").asText());
            JsonNode relation = null;
            for (var current : recommendation.path("relationships"))
                if (current.path("mondoId").equals(hypothesis.path("mondoId"))) relation = current;
            assertNotNull(relation, recommendation.toPrettyString());
            hypothesis.set("moi", relation.path("moi"));
            var transcript = (ObjectNode) item.path("transcript");
            JsonNode annotation = null;
            for (var current : recommendation.path("annotations"))
                if (current.path("annotationId").equals(recommendation.path("recommendedAnnotationId"))) annotation = current;
            assertNotNull(annotation, recommendation.toPrettyString());
            String accession = annotation.path("versionedTranscriptId").asText(); int dot = accession.lastIndexOf('.');
            transcript.put("accession", accession.substring(0, dot)).put("version", Integer.parseInt(accession.substring(dot + 1)))
                    .set("annotationSource", annotation.path("annotationSource"));
            transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            transcript.put("selectionPolicy", recommendation.path("policyId").asText() + "@" + recommendation.path("policyVersion").asText());
            ((ObjectNode) transcript.path("source")).put("recordLocator", "recommendations/" + recommendation.path("recommendationId").asText() + "/selection");
            var ref = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"))
                ref.set(field, recommendation.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey"));
            ref.set("annotationId", recommendation.path("recommendedAnnotationId"));
        }
    }

    private ObjectNode postBatch() throws Exception {
        var result = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andReturn();
        assertEquals(200, result.getResponse().getStatus(), () -> Objects.toString(result.getResolvedException())
                + "\n" + base.request.toPrettyString());
        return (ObjectNode) base.mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test void explicitWorkflowRoutesWithoutReplacingCurrentAndHistorySurvivesDisable() throws Exception {
        var controller = new AcmgBusinessController(new AcmgBusinessWorkflows(legacy, base.service));
        var http = MockMvcBuilders.standaloneSetup(controller).build();
        String prefix = "/variant-analyses/" + base.analysisId + "/acmg";
        var current = base.mapper.readTree(http.perform(get(prefix + "/input-options").param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals("CURRENT", current.path("selectedWorkflow").asText());
        assertEquals(2, current.path("availableWorkflows").size());
        assertNotEquals(DECLARATION, current.path("requiredDeclaration").asText());
        var selected = base.mapper.readTree(http.perform(get(prefix + "/input-options").param("topN", "2")
                .param("workflow", AcmgBusinessRuleScope.LOCAL_BATCH1)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(DECLARATION, selected.path("requiredDeclaration").asText());
        assertEquals(0, factCalls.get());
        http.perform(get(prefix + "/input-options").param("workflow", "ARBITRARY"))
                .andExpect(status().isBadRequest());
        byte[] bytes = base.mapper.writeValueAsBytes(base.request);
        http.perform(post(prefix + "/runs").contentType("application/json").content(requireNonNull(bytes)))
                .andExpect(status().isBadRequest()); // Local declaration cannot execute the old workflow.
        var first = base.mapper.readTree(http.perform(post(prefix + "/runs").param("workflow", AcmgBusinessRuleScope.LOCAL_BATCH1)
                .contentType("application/json").content(requireNonNull(bytes))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertFiveExplicitResults(first);
        int calls = factCalls.get(); forbidExecution = true;
        var repeated = base.mapper.readTree(http.perform(post(prefix + "/runs").param("workflow", AcmgBusinessRuleScope.LOCAL_BATCH1)
                .contentType("application/json").content(requireNonNull(bytes))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(first, repeated); assertEquals(calls, factCalls.get());
        var disabled = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(legacy)).build();
        disabled.perform(post(prefix + "/runs").param("workflow", AcmgBusinessRuleScope.LOCAL_BATCH1)
                .contentType("application/json").content(requireNonNull(bytes))).andExpect(status().isConflict());
        var history = base.mapper.readTree(disabled.perform(get(prefix + "/runs/" + first.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(first, history); assertEquals(calls, factCalls.get());
    }

    private void assertFiveExplicitResults(JsonNode response) {
        assertEquals(base.mapper.valueToTree(LocalEvidenceBatchRuntime.CRITERIA), response.path("enabledCriteria"));
        assertEquals(2, response.path("candidates").size(), response.toPrettyString());
        for (var candidate : response.path("candidates")) {
            assertFalse(candidate.path("attempts").isEmpty(), candidate.toPrettyString());
            for (var attempt : candidate.path("attempts")) {
                var criteria = attempt.at("/localBatch/criteria");
                assertEquals(5, criteria.size(), attempt.toPrettyString());
                var actual = new ArrayList<String>();
                for (var criterion : criteria) {
                    actual.add(criterion.path("criterionId").asText());
                    assertEquals("NOT_EVALUATED", criterion.path("status").asText());
                    assertFalse(criterion.path("reasonCode").asText().isBlank());
                    assertFalse(criterion.path("reason").asText().isBlank());
                }
                assertEquals(LocalEvidenceBatchRuntime.CRITERIA, actual);
            }
        }
    }

    @Test void persistedFiveRuleResultsSurviveHttpReadsDatabaseReopenAndExactRepeatWithoutNewFacts() throws Exception {
        var original = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        var options = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(base.mapper.valueToTree(LocalEvidenceBatchRuntime.CRITERIA), options.path("enabledCriteria"));
        assertEquals(0, factCalls.get()); assertEquals(0, populationFactories.get());
        var first = postBatch(); assertFiveExplicitResults(first);
        if (predictionExplanation) {
            var summary = first.at("/candidates/0/attempts/0/localBatch/criteria/1/details/predictionMechanisms");
            assertEquals("EXPLANATION_ONLY", summary.path("purpose").asText(), first.toPrettyString());
            assertEquals(2, summary.path("mechanisms").size());
            assertEquals("NOT_EVALUATED", first.at("/candidates/0/attempts/0/localBatch/criteria/1/status").asText());
        }
        assertEquals("COMPLETED_WITH_BLOCKERS", first.path("status").asText());
        assertEquals(2, factCalls.get(), first.toPrettyString()); assertEquals(1, populationFactories.get());
        String runId = first.path("runId").asText();
        assertNotNull(base.store.run(base.analysisId, runId).orElseThrow().responseJson());
        var privateData = base.mapper.readTree(base.store.run(base.analysisId, runId).orElseThrow().privateJson());
        assertFalse(privateData.path("sourceReceipts").isEmpty());
        assertEquals(privateData.at("/localBatch/receipts"), privateData.path("sourceReceipts"));
        assertEquals(privateData.at("/localBatch/sourceProvenance"), privateData.path("sourceProduction"));
        assertFalse(first.path("localBatch").has("receipts"));
        assertEquals(first, postBatch()); assertEquals(2, factCalls.get());
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN");
        base.recreateRepositories(); forbidExecution = true; install(base.store);
        var read = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, runId))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first, base.mapper.readTree(read.getResponse().getContentAsByteArray()));
        assertEquals(first, postBatch());
        base.http.perform(get("/variant-analyses/{id}/acmg/runs", base.analysisId)).andExpect(status().isOk());
        base.http.perform(get("/variant-analyses/{id}/candidates/{candidate}/acmg", base.analysisId,
                first.path("candidateIds").get(0).asText()).param("runId", runId)).andExpect(status().isOk());
        assertEquals(2, factCalls.get()); assertEquals(1, populationFactories.get());
        assertEquals(1, base.store.runs(base.analysisId).size());
        assertEquals(original, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
        for (String secret : List.of("synthetic-proband", "rawJson", "selectionReason", temporary.toString()))
            assertFalse(first.toString().contains(secret), secret);
        String fixtureOutput = System.getProperty("varvis.local.batch.fixture");
        if (fixtureOutput != null) Files.writeString(Path.of(fixtureOutput), base.mapper.writeValueAsString(first));
    }

    @Test void actualPredictionExplanationSurvivesTheSameSaveHttpReopenAndReuseChain() throws Exception {
        predictionExplanation = true; configurationRevision = "synthetic-prediction-explanation-v1";
        install(base.store); confirmRecommendations();
        persistedFiveRuleResultsSurviveHttpReadsDatabaseReopenAndExactRepeatWithoutNewFacts();
    }

    private SelectedPredictionRuntime benignPrediction(SpliceAiLocalObservationSession splice,
            java.util.function.Function<JsonNode, JsonNode> effects) {
        var mapper = base.mapper; var resources = mapper.createObjectNode();
        var resource = PredictionCoreTest.resource(mapper); resources.set("ALPHAMISSENSE", resource);
        resources.putObject("SPLICEAI").put("calibrationCompatibility", "UNVERIFIED");
        var core = new PredictionDevelopmentRuntime(mapper, PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json"),
                PredictionDevelopmentRuntime.load(mapper, "prediction-am-calibration-v1.json"), resources, request -> {
                    proteinCalls.incrementAndGet(); var raw = mapper.createObjectNode().put("status", "HIT").put("dataClass", "SYNTHETIC");
                    raw.set("queryAllele", request.path("queryAllele").deepCopy()); raw.set("resource", resource.deepCopy());
                    var rows = raw.putArray("rows");
                    for (var candidate : base.jobs.findInterpretations(base.analysisId,0,100)) for (var tx : candidate.getTranscriptAnnotations())
                        rows.addObject().put("transcriptId", tx.getVersionedTranscriptId()).put("proteinVariant", tx.getAminoAcidChange()).put("rawScore", "0.01");
                    return raw;
                }, true, effects);
        // Native protocol/annotation/real inference are validated separately. Here transport is synthetic.
        var nativeRuntime = org.mockito.Mockito.mock(NativeSpliceAiRuntime.class);
        var plan = org.mockito.Mockito.mock(NativeSpliceAiRuntime.Plan.class);
        org.mockito.Mockito.when(plan.snapshot()).thenReturn(mapper.createObjectNode().put("status", "PREPARED").put("dataClass", "SYNTHETIC"));
        org.mockito.Mockito.when(nativeRuntime.prepare(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(plan);
        org.mockito.Mockito.doAnswer(call -> { nativeCalls.incrementAndGet(); return null; }).when(nativeRuntime).compute(org.mockito.ArgumentMatchers.anyList());
        var raw = mapper.createObjectNode(); raw.putObject("mechanism").put("status", "CALIBRATED").put("direction", "BENIGN")
                .put("appliedStrength", "SUPPORTING").put("rawScore", "0.01").put("reasonCode", "CALIBRATED_INTERVAL");
        raw.putObject("observation").put("status", "HIT").put("dataClass", "SYNTHETIC").put("annotationFileSha256", "a".repeat(64));
        org.mockito.Mockito.when(nativeRuntime.evaluate(plan)).thenReturn(raw);
        org.mockito.Mockito.when(nativeRuntime.audit()).thenReturn(mapper.createObjectNode().put("batchInvocations", 1).put("dataClass", "SYNTHETIC"));
        return new SelectedPredictionRuntime(mapper, core, Hnf1aRevelRuntime.unavailable(mapper), null,null,splice,effects,null,null,nativeRuntime);
    }

    @Test void completeBenignPairPersistsWithActualEffectScopeAndReopensWithoutAnyPrediction() throws Exception {
        predictionExplanation = true; benignPredictionPair = true; configurationRevision = "synthetic-native-selected-v1";
        install(base.store); confirmRecommendations();
        var first = postBatch();
        var criteria = first.at("/candidates/0/attempts/0/localBatch/criteria");
        var bp4 = java.util.stream.StreamSupport.stream(criteria.spliterator(), false)
                .filter(c -> "ACMG_AMP_2015:BP4".equals(c.path("criterionId").asText())).findFirst().orElseThrow();
        assertEquals("MET", bp4.path("status").asText(), first.toPrettyString());
        assertEquals(1,nativeCalls.get()); assertTrue(proteinCalls.get()>0);
        String runId=first.path("runId").asText(); var stored=base.store.run(base.analysisId,runId).orElseThrow();
        assertTrue(stored.privateJson().contains("nativeSpliceResult"));
        int prior=proteinCalls.get(); assertEquals(first,postBatch());
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN");base.recreateRepositories();forbidExecution=true;install(base.store);
        assertEquals(first,base.service.read(base.analysisId,runId));assertEquals(first,postBatch());
        assertEquals(prior,proteinCalls.get());assertEquals(1,nativeCalls.get());
        assertEquals(stored.responseJson(),base.store.run(base.analysisId,runId).orElseThrow().responseJson());
        String output=System.getProperty("varvis.native.selected.fixture");
        if(output!=null) {
            Path directory=Path.of(output);Files.createDirectory(directory);
            Files.writeString(directory.resolve("saved-public.json"),stored.responseJson());
            Files.writeString(directory.resolve("saved-private.json"),stored.privateJson());
            Files.writeString(directory.resolve("saved-request.json"),stored.requestJson());
            Files.writeString(directory.resolve("reopened-public.json"),base.store.run(base.analysisId,runId).orElseThrow().responseJson());
            Files.writeString(directory.resolve("counts.json"),base.mapper.createObjectNode().put("proteinQueries",prior)
                    .put("nativeBatches",nativeCalls.get()).put("newAfterReopen",0).put("dataClass","SYNTHETIC").toPrettyString());
        }
    }

    @ParameterizedTest(name = "overlapping PM2 policies: {0}")
    @ValueSource(booleans = {false, true})
    void absenceAndOverlappingPoliciesSurviveSaveReopenAndRepeatWithoutAdditionalReads(boolean overlapping) throws Exception {
        Path data = temporary.resolve("synthetic-all-sites.bgz"), manifest = temporary.resolve("all-sites.json");
        String header = "locus\tAN\t" + String.join("\t", GnomadLocalAllSites.FLAGS);
        try (var out = new htsjdk.samtools.util.BlockCompressedOutputStream(data.toFile())) {
            out.write((header + "\nchr11:2\t100000\tfalse\tfalse\tfalse\tfalse\n"
                    + "chr13:3\t0\tfalse\tfalse\tfalse\tfalse\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        GnomadLocalAllSites.build(base.mapper, data, temporary.resolve("all-sites.bin"), manifest, GnomadLocalAllSites.sha(data));
        absenceResource = new GnomadLocalAllSites(base.mapper, manifest, GnomadLocalAllSites.sha(manifest));
        // Synthetic policies test business persistence without claiming approval for these actual gene/disease scopes.
        var generic = Pm2LocalExomesProfile.load(base.mapper).withLocalAbsence(absenceResource.binding(), true)
                .document().path("generalAbsencePolicy");
        var policies = new ArrayList<ObjectNode>();
        for (var item : base.request.path("hypotheses")) {
            var hypothesis = item.path("hypothesis"); var policy = (ObjectNode) generic.deepCopy();
            policy.put("policyId", "SYNTHETIC_ABSENCE_" + policies.size());
            policy.putObject("gene").set("hgncId", hypothesis.path("hgncId"));
            policy.putObject("disease").set("mondoId", hypothesis.path("mondoId"));
            policy.put("moi", switch (hypothesis.path("moi").asText()) {
                case "AR" -> "AUTOSOMAL_RECESSIVE";
                case "AD" -> "AUTOSOMAL_DOMINANT";
                default -> hypothesis.path("moi").asText();
            }); policies.add(policy);
        }
        if (overlapping) {
            policies.add(policies.get(0).deepCopy().put("policyId", "SYNTHETIC_ABSENCE_OVERLAP"));
        }
        var factory = Pm2LocalExomesProfile.class.getDeclaredMethod("synthetic", com.fasterxml.jackson.databind.ObjectMapper.class, List.class);
        factory.setAccessible(true);
        absenceProfile = ((Pm2LocalExomesProfile) factory.invoke(null, base.mapper, policies)).withLocalAbsence(absenceResource.binding(), false);
        configurationRevision = overlapping ? "synthetic-absence-overlap" : "synthetic-absence-unique";
        install(base.store); confirmRecommendations();
        var candidates = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        var first = postBatch();
        var evaluated = first.at("/candidates/0/attempts/0/localBatch/criteria/0");
        var insufficient = first.at("/candidates/1/attempts/0/localBatch/criteria/0");
        String reason = overlapping ? "PM2_POLICY_AMBIGUOUS" : "PM2_PROJECT_LOCAL_ABSENCE_MET";
        assertEquals(overlapping ? "NOT_EVALUATED" : "MET", evaluated.path("status").asText(), first::toPrettyString);
        assertEquals(overlapping ? "NONE" : "SUPPORTING", evaluated.path("appliedStrength").asText());
        assertEquals(reason, evaluated.path("reasonCode").asText());
        assertEquals("100000", evaluated.at("/details/populationFacts/detection/adjAn").asText());
        assertEquals("PM2_ALL_SITES_AN_INSUFFICIENT", insufficient.path("reasonCode").asText());
        assertEquals(2, factCalls.get()); assertEquals(2, absenceResource.queries());
        String id = first.path("runId").asText();
        var privateData = base.mapper.readTree(base.store.run(base.analysisId, id).orElseThrow().privateJson());
        var selected = privateData.findValues("policySelection").stream()
                .filter(selection -> overlapping
                        ? "PM2_POLICY_AMBIGUOUS".equals(selection.path("reasonCode").asText())
                        : "SYNTHETIC_ABSENCE_0".equals(selection.at("/policy/policyId").asText()))
                .findFirst().orElseThrow();
        assertEquals(overlapping ? "BLOCKED" : "APPLICABLE", selected.path("status").asText());
        assertEquals(!overlapping, selected.path("policy").isObject());
        assertTrue(privateData.toString().contains("allSitesObservation"));
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN"); base.recreateRepositories();
        forbidExecution = true; install(base.store);
        String historyPath = "/variant-analyses/" + base.analysisId + "/acmg/runs";
        String runPath = historyPath + "/" + id;
        var history = base.mapper.readTree(base.http.perform(get(historyPath)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(id, history.path(0).path("runId").asText());
        var index = base.mapper.readTree(base.http.perform(get(runPath + "/view")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        String viewSha = index.path("sourceViewSha256").asText();
        var detail = base.mapper.readTree(base.http.perform(get(runPath + "/view/background")
                        .param("candidateId", first.at("/candidates/0/candidateId").asText())
                        .param("backgroundIndex", "0").param("sourceSha256", viewSha))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(evaluated, detail.at("/candidates/0/attempts/0/localBatch/criteria/0"));
        var read = base.http.perform(get(runPath).param("sourceViewSha256", viewSha))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first, base.mapper.readTree(read.getResponse().getContentAsByteArray()));
        assertEquals(first, postBatch()); assertEquals(2, factCalls.get()); assertEquals(2, absenceResource.queries());
        assertEquals(candidates, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
    }

    @Test void explicitDifferentContextPreservesSourceAndSurvivesReopen() throws Exception {
        base.variantTsvOverride = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv(
                "1\t11\t2\tT\tA\tHBB\t3043\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf(
                "chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t0/1", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t0/1"));
        base.request.put("declarationVersion", DECLARATION);
        install(base.store); confirmRecommendations();
        var before = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        var first = postBatch();
        assertEquals("1.1.0", first.at("/executionProfile/profileVersion").asText());
        var attempt = first.at("/candidates/0/attempts/0");
        assertFalse(attempt.path("context").isNull(), first.toPrettyString());
        assertEquals("AUTOSOMAL_DOMINANT", attempt.at("/interpretationContext/rankingMoi").asText());
        assertEquals("AUTOSOMAL_RECESSIVE", attempt.at("/interpretationContext/interpretationMoi").asText());
        assertTrue(attempt.at("/interpretationContext/requiresExplicitSelection").asBoolean());
        assertTrue(attempt.at("/interpretationContext/reasonCode").isNull());
        assertTrue(factCalls.get() > 0, first.toPrettyString());
        int calls = factCalls.get(); String id = first.path("runId").asText();
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN"); base.recreateRepositories();
        forbidExecution = true; install(base.store);
        var read = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, id))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first, base.mapper.readTree(read.getResponse().getContentAsByteArray()));
        assertEquals(first, postBatch()); assertEquals(calls, factCalls.get());
        assertEquals(before, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
    }

    @Test void differentRankingRequiresExplicitContextWithoutExecutingAndSurvivesReopen() throws Exception {
        base.variantTsvOverride = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv(
                "1\t11\t2\tT\tA\tHBB\t3043\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf(
                "chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t0/1", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t0/1"));
        base.request.put("declarationVersion", DECLARATION).putArray("hypotheses");
        install(base.store);
        var refs = base.request.putArray("recommendations");
        var options = base.service.inputOptions(base.analysisId, 2);
        assertTrue(options.at("/candidates/0/recommendation/relationships/0/selectable").asBoolean());
        assertTrue(options.at("/candidates/0/recommendation/stableRelationshipKey").isNull());
        for (var candidate : options.path("candidates")) {
            var recommendation = candidate.path("recommendation"); var reference = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"))
                reference.set(field, recommendation.path(field));
            reference.putNull("stableRelationshipKey").putNull("annotationId");
        }
        var result = postBatch(); assertFiveExplicitResults(result);
        assertTrue(result.at("/candidates/0/attempts/0/blockers").toString()
                .contains("DISEASE_RELATIONSHIP_REVIEW_REQUIRED"), result.toPrettyString());
        assertEquals("EXPLICIT_INPUT_MISSING", result.at("/candidates/0/attempts/0/localBatch/criteria/1/reasonCode").asText());
        assertEquals("NOT_EVALUATED", result.at("/candidates/0/attempts/0/localBatch/criteria/1/status").asText());
        assertEquals(0, factCalls.get()); assertEquals(0, populationFactories.get());
        new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN"); base.recreateRepositories();
        forbidExecution = true; install(base.store);
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
        assertEquals(result, postBatch()); assertEquals(0, factCalls.get());
    }

    @Test void missingBackgroundRetainsEverySelectedCandidateAndFiveExplicitAbstentions() throws Exception {
        base.request.putArray("hypotheses");
        for (var ref : base.request.path("recommendations"))
            ((ObjectNode) ref).putNull("stableRelationshipKey").putNull("annotationId");
        var result = postBatch(); assertFiveExplicitResults(result);
        assertEquals(0, factCalls.get());
        assertEquals("COMPLETED_WITH_BLOCKERS", result.path("status").asText());
        assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
    }

    @Test void unavailableSourceRuntimeProducesFiveSavedReasonsWithoutAcquiringModules() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(requireNonNull(base.service), "runtime",
                (Supplier<ExplicitCaseInputCoordinator>) () -> { throw new IllegalArgumentException("SYNTHETIC_RUNTIME_UNAVAILABLE"); });
        var options = base.service.inputOptions(base.analysisId, 2);
        base.request.putArray("hypotheses");
        var refs = base.request.putArray("recommendations");
        for (var candidate : options.path("candidates")) {
            var recommendation = candidate.path("recommendation"); var reference = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"))
                reference.set(field, recommendation.path(field));
            reference.putNull("stableRelationshipKey").putNull("annotationId");
        }
        var response = postBatch(); assertFiveExplicitResults(response);
        assertEquals(0, factCalls.get()); assertEquals(0, populationFactories.get());
        assertEquals(response, base.service.read(base.analysisId, response.path("runId").asText()));
    }

    @Test void wrongDeclarationsAndHistoricalScopeCannotExecuteTheLocalBatch() throws Exception {
        for (String wrong : List.of("synthetic-v1", "ordinary-ui-v3", "ordinary-population-v1")) {
            var body = base.request.deepCopy().put("declarationVersion", wrong);
            base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                    .content(requireNonNull(base.mapper.writeValueAsBytes(body)))).andExpect(status().isBadRequest());
        }
        assertEquals(0, factCalls.get()); assertTrue(base.store.runs(base.analysisId).isEmpty());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> legacy.submit(base.analysisId, base.mapper.writeValueAsBytes(base.request))).getStatusCode().value());
        var legacyRequest = base.declaration();
        var old = legacy.submit(base.analysisId, base.mapper.writeValueAsBytes(legacyRequest));
        assertEquals(old, base.service.read(base.analysisId, old.path("runId").asText()));
        base.request.put("retryOf", old.path("runId").asText());
        assertRejectedRetry("LOCAL_BATCH_RETRY_IDENTITY_MISMATCH"); assertEquals(0, factCalls.get());
        base.request.remove("retryOf");
        var current = postBatch();
        assertNotEquals(old.path("runId"), current.path("runId"));
        assertFiveExplicitResults(current);
    }

    @Test void explicitRetryOfBlockedAndFailedSameScopeRunsCreatesASeparateSavedRun() throws Exception {
        var blocked = postBatch();
        base.request.put("retryOf", blocked.path("runId").asText());
        var retried = postBatch();
        assertNotEquals(blocked.path("runId"), retried.path("runId"));
        assertEquals(blocked.path("runId").asText(), retried.path("retryOf").asText());
        assertEquals(retried, postBatch()); assertEquals(4, factCalls.get());

        var failed = new AcmgBusinessStore(base.dataSource) {
            @Override public void finish(String id, String state, String response, String privateJson) {
                throw new IllegalStateException("SYNTHETIC_SAVE_FAILURE");
            }
        };
        install(failed); base.request.put("retryOf", retried.path("runId").asText());
        base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andExpect(status().isServiceUnavailable());
        var failedRun = base.store.runs(base.analysisId).stream().filter(r -> "FAILED".equals(r.status())).findFirst().orElseThrow();
        install(base.store); base.request.put("retryOf", failedRun.id());
        var recovered = postBatch(); assertFiveExplicitResults(recovered);
        assertEquals(failedRun.id(), recovered.path("retryOf").asText());
        assertEquals(recovered, postBatch()); assertEquals(8, factCalls.get());
    }

    @Test void retryRejectsDifferentSourceProfileScopeAndNonFailureStatusBeforeModuleAcquisition() throws Exception {
        var first = postBatch();
        var original = base.store.run(base.analysisId, first.path("runId").asText()).orElseThrow();
        for (String variant : List.of("source", "profile", "scope", "COMPLETED", "RUNNING")) {
            var identity = (ObjectNode) base.mapper.readTree(original.requestJson());
            ((ObjectNode) identity.path("declaration")).put("retryOf", "synthetic-parent-" + variant);
            if ("source".equals(variant)) identity.withObject("/localBatchPublishedInputIdentity").put("executionSourceSha256", "b".repeat(64));
            if ("profile".equals(variant)) identity.withObject("/executionProfile").put("profileVersion", "SYNTHETIC_OTHER_PROFILE");
            if ("scope".equals(variant)) identity.withObject("/declaration").put("declarationVersion", "synthetic-v1");
            String id = UUID.randomUUID().toString();
            assertTrue(base.store.claim(new AcmgBusinessStore.Run(id, base.analysisId,
                    new AcmgBusinessArtifacts(base.mapper).requestHash(identity), base.mapper.writeValueAsString(identity),
                    "RUNNING", Instant.now().toString(), null, null, null)));
            if ("COMPLETED".equals(variant)) base.store.finish(id, "COMPLETED", "{}", "{}");
            else if (!"RUNNING".equals(variant)) base.store.failed(id);
            base.request.put("retryOf", id);
            assertRejectedRetry(Set.of("COMPLETED", "RUNNING").contains(variant)
                    ? "RETRY_REQUIRES_TERMINAL_FAILURE" : "LOCAL_BATCH_RETRY_IDENTITY_MISMATCH");
        }
        assertEquals(2, factCalls.get());
    }

    private void assertRejectedRetry(String reason) throws Exception {
        int before = factCalls.get(); int modules = populationFactories.get();
        var rejected = assertThrows(ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(base.request)));
        assertEquals(409, rejected.getStatusCode().value());
        assertEquals(reason, rejected.getReason());
        assertEquals(before, factCalls.get()); assertEquals(modules, populationFactories.get());
    }
}
