package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.GeneratedMissenseReferenceFixture;
import org.nbscn.varvis.variantanalysis.provider.MissenseComparisonRuntime;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessRuleScope;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** New generated file chain, original public runtime, MockMvc and task-owned file H2; SYNTHETIC only. */
class GeneratedMissenseReferenceBusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    Ps1GeneralBusinessIntegrationTest shared;
    GeneratedMissenseReferenceFixture fixture;
    ObjectMapper mapper;
    String scenario;
    ArrayNode steps;
    boolean h2Reopened;
    boolean expertReuse;
    boolean indexed;
    boolean directResidue;
    boolean sourceAutomated;

    @org.junit.jupiter.api.Test
    void sourceQualifiedLibraryAndPolicyRnaReachOriginalBusinessWithoutPairApproval() throws Exception {
        sourceAutomated = true;
        verifyBusiness(true, false);
    }

    @org.junit.jupiter.api.Test
    void directResidueRuleUsesOriginalPreparationSaveReopenAndExactReuse() throws Exception {
        directResidue = true;
        verifyBusiness(true, false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void indexedBatchMaterialsRetainSaveReopenGetAndZeroExecutionReuse(boolean pm5) throws Exception {
        indexed = true;
        verifyBusiness(pm5, true);
    }

    @SuppressWarnings("unchecked")
    private org.nbscn.varvis.variantanalysis.provider.ReviewedRuleBundle loadBusinessBundle() throws Exception {
        var original = fixture.loadBundle();
        if (!indexed) return original;
        Path root = temporary.resolve("batch-materials");
        if (!Files.exists(root)) {
            Path seed = fixture.root.resolve("background-1/bridge/manifest-with-external-final-approval.json");
            var manifest = mapper.createObjectNode().put("schemaVersion", "missense-material-batch-manifest-v1");
            manifest.putArray("entries").addObject().put("entryId", "qualified").putObject("preparationManifest")
                    .put("path", seed.toString()).put("rawSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(Files.readString(seed)));
            Path input = temporary.resolve("batch-input.json"); mapper.writeValue(input.toFile(),manifest);
            var result = new org.nbscn.varvis.variantanalysis.provider.MissenseMaterialBatchPreparation().prepare(input,
                    org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(Files.readString(input)),root);
            assertTrue(result.complete(),result.report().toPrettyString());
        }
        Path index = root.resolve("qualified-index.json");
        var selection = org.nbscn.varvis.variantanalysis.provider.MissenseMaterialIndex.load(index,
                org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(Files.readString(index)));
        var supplier = (java.util.function.Supplier<MissenseComparisonRuntime>)org.springframework.test.util.ReflectionTestUtils.getField(requireNonNull(original),"missense");
        assertNotNull(supplier);
        return selection.bind(fixture.criterion(),fixture.context(0),mapper.valueToTree(fixture.targetAnnotation(0)),supplier,original.identity());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void independentlyFixedSourcesReachRuntimeSaveGetAndReuse(boolean pm5) throws Exception {
        verifyBusiness(pm5, false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reviewedIndependentSourcesReachRuntimeSaveGetAndReuse(boolean pm5) throws Exception {
        verifyBusiness(pm5, true);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void adoptedLpAndExpertSourcePacketSurviveOriginalPreparationAndPersistence(boolean pm5) throws Exception {
        verifyBusiness(pm5, true, true);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reusedExpertJudgmentsReachRuntimeAndPersistenceWithoutReclassification(boolean pm5) throws Exception {
        expertReuse = true;
        verifyBusiness(pm5, false, false);
    }

    private void verifyBusiness(boolean pm5, boolean independent) throws Exception {
        verifyBusiness(pm5, independent, false);
    }
    private void verifyBusiness(boolean pm5, boolean independent, boolean adopted) throws Exception {
        scenario = (pm5 ? "PM5" : "PS1") + (independent ? "_INDEPENDENT" : "") + (adopted ? "_ADOPTED" : "") + (expertReuse ? "_REUSED_EXPERT" : "") + (directResidue ? "_DIRECT_RESIDUE" : "") + (indexed ? "_INDEXED" : "") + (sourceAutomated ? "_SOURCE_AUTOMATED" : "");
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        mapper = base.mapper; steps = mapper.createArrayNode();
        fixture = new GeneratedMissenseReferenceFixture(temporary.resolve("generated"), mapper, pm5,
                sourceAutomated ? GeneratedMissenseReferenceFixture.Scenario.SOURCE_AUTOMATED : directResidue ? GeneratedMissenseReferenceFixture.Scenario.DIRECT_RESIDUE : expertReuse ? GeneratedMissenseReferenceFixture.Scenario.REUSED_EXPERT : adopted ? GeneratedMissenseReferenceFixture.Scenario.ADOPTED : independent ? GeneratedMissenseReferenceFixture.Scenario.INDEPENDENT : GeneratedMissenseReferenceFixture.Scenario.NORMAL);
        ObjectNode beforeCapture = measure("before-capture"); fixture.build(); ObjectNode afterCapture = measure("after-capture");
        assertDelta(beforeCapture, afterCapture, "captureDiscovery", 2);
        assertDelta(beforeCapture, afterCapture, "captureAnnotation", 2);
        for (String field : List.of("runtimeDiscovery", "runtimeAnnotation", "splice", "runtimeSupplier"))
            assertDelta(beforeCapture, afterCapture, field, 0);

        // Inject the independently fixed DTOs before actual publication. Never call the old setup that publishes its own.
        base.fixture.annotations = new LinkedHashMap<>();
        base.fixture.annotations.put("public-hbb-compatible-e7v", List.of(fixture.targetAnnotation(0)));
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(fixture.targetAnnotation(1)));
        base.variantTsvOverride = tsv("1\t11\t105\tA\tC\tHBB\t3043\tAR\t0.8", "2\t13\t105\tA\tC\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t105\t.\tA\tC\t.\tPASS\t.\tGT\t.", "chr13\t105\t.\tA\tC\t.\tPASS\t.\tGT\t."));
        shared = new Ps1GeneralBusinessIntegrationTest(); shared.base = base; shared.temporary = temporary;
        shared.tinyReference();
        var bundle = loadBusinessBundle();
        base.recreateService(() -> {
            base.invocations.incrementAndGet();
            return new ExplicitCaseInputCoordinator(mapper, base.fixture.resolver, base.producer, base.fixture.sources);
        });
        base.service = pm5 ? AcmgBusinessService.pm5Development(base.service, bundle) : AcmgBusinessService.ps1Development(base.service, bundle);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        var published = base.jobs.findInterpretations(base.analysisId, 0, 2);
        artifact("published-rows", mapper.valueToTree(published));
        artifact("publication-source", mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()));
        for (int i = 0; i < 2; i++) assertEquals(mapper.valueToTree(List.of(fixture.targetAnnotation(i))), mapper.valueToTree(published.get(i).getTranscriptAnnotations()));

        ObjectNode beforeOptions = measure("before-options");
        ObjectNode options = shared.options(); artifact("options", options);
        ObjectNode afterOptions = measure("after-options");
        for (String field : List.of("runtimeDiscovery", "runtimeAnnotation", "splice", "runtimeSupplier")) assertDelta(beforeOptions, afterOptions, field, 0);
        assertEquals(2, options.path("selectedCount").asInt());
        ObjectNode request = shared.confirmedRequest(options).put("declarationVersion", pm5 ? AcmgBusinessRuleScope.PM5_DECLARATION : AcmgBusinessRuleScope.PS1_DECLARATION);
        artifact("request", request);
        ObjectNode saved = base.postRequest(request); artifact("submit", saved);
        ObjectNode afterSubmit = measure("after-first-submit");
        assertEquals("COMPLETED", saved.path("status").asText(), saved.toPrettyString());
        for (String field : List.of("runtimeDiscovery", "runtimeAnnotation", "splice", "runtimeSupplier")) assertDelta(afterOptions, afterSubmit, field, 2);
        assertDelta(afterOptions, afterSubmit, "coordinator", 2); // Submission rechecks selection, then executes the batch.
        assertEquals(2, saved.at("/missenseCalls/evaluator").asInt(), "Success trace is separate from measured runtime supplier entries");
        for (int i = 0; i < 2; i++) {
            JsonNode comparison = saved.at("/candidates/" + i + "/attempts/0/missenseComparison");
            JsonNode evaluation = comparison.at("/criterionEvaluations/0");
            assertEquals("MET", evaluation.at("/evaluationResult/status").asText(), comparison.toPrettyString());
            assertEquals(pm5 ? (i == 0 && !adopted ? "MODERATE" : "SUPPORTING") : (i == 0 && !adopted ? "STRONG" : "MODERATE"), evaluation.path("appliedStrength").asText());
            assertEquals(pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1, evaluation.path("criterionId").asText());
            assertEquals(1, comparison.path("evaluatorCalls").asInt());
            if (independent) {
                assertEquals(adopted ? "missense-independent-reference-v2" : "missense-independent-reference-v1", comparison.at("/references/0/qualification/schemaVersion").asText());
                assertEquals("criteria provided, single submitter", comparison.at("/references/0/sourceAssertion/reviewStatus").asText());
            }
            if (adopted) {
                assertEquals(i == 0 ? "PATHOGENIC" : "LIKELY_PATHOGENIC", comparison.at("/references/0/classification").asText());
                assertEquals("LIKELY_PATHOGENIC", comparison.at("/references/0/adoptedClassification").asText());
                assertEquals("SOURCE_NAVIGATION_ONLY", comparison.at("/references/0/expertEvidence/use").asText());
                assertEquals("UNREVIEWED", comparison.at("/references/0/expertEvidence/record/reviewStatus").asText());
                assertEquals("PS1", comparison.at("/references/0/expertEvidence/record/appliedComparisonCriteria/0").asText());
                assertEquals(fixture.selectedAssertion(i).path("sourceAssertion"), comparison.at("/references/0/sourceAssertion"));
            }
            assertEvaluationIdentity(evaluation); assertLineage(i, comparison);
            if (sourceAutomated) {
                assertEquals("PRESERVE_SOURCE_VERIFIED_EXPERT_CLASSIFICATION", comparison.at("/references/0/expertClassificationAdoption").asText());
                assertFalse(comparison.at("/rnaApplicability/pairSpecificManualApprovalRequired").asBoolean(true));
                assertTrue(comparison.at("/rnaApplicability/bothNativeRawChecksRequired").asBoolean());
                assertFalse(comparison.at("/rnaApplicability/normalRnaClaimed").asBoolean(true));
                assertEquals("UNREVIEWED", comparison.at("/references/0/expertEvidence/record/reviewStatus").asText());
            }
            if (expertReuse) {
                assertEquals("PRESERVE_REVIEWED_EXPERT_CLASSIFICATION", comparison.at("/references/0/expertClassificationAdoption").asText());
                assertFalse(comparison.at("/references/0").has("qualification"));
                assertEquals(i == 0 ? "PATHOGENIC" : "LIKELY_PATHOGENIC", comparison.at("/references/0/classification").asText());
                assertEquals("UNREVIEWED", comparison.at("/references/0/expertEvidence/record/reviewStatus").asText());
            }
        }
        String runId = saved.path("runId").asText();
        JsonNode got = mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        artifact("get", got); ObjectNode afterGet = measure("after-get");
        assertEquals(saved, got); assertEquals(afterSubmit, afterGet, "GET must not create a coordinator or invoke any synthetic provider/runtime");
        JsonNode reused = base.postRequest(request); artifact("reuse", reused); ObjectNode afterReuse = measure("after-identical-post");
        assertEquals(saved, reused); assertEquals(afterGet, afterReuse); assertEquals(1, base.store.runs(base.analysisId).size());
        if (independent || expertReuse || directResidue || sourceAutomated) {
            base.closeDatabase(); base.recreateRepositories();
            base.recreateService(() -> { throw new AssertionError("Restored result must not construct a scientific coordinator"); });
            var restoredBundle = loadBusinessBundle();
            base.service = pm5 ? AcmgBusinessService.pm5Development(base.service, restoredBundle) : AcmgBusinessService.ps1Development(base.service, restoredBundle);
            base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
            JsonNode reopened = mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, runId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            JsonNode repeated = base.postRequest(request);
            assertEquals(saved, reopened); assertEquals(saved, repeated);
            assertEquals(afterReuse, measure("after-h2-reopen-get-and-reuse"));
            assertEquals(1, base.store.runs(base.analysisId).size()); h2Reopened = true;
            artifact("get-reopened", reopened); artifact("reuse-reopened", repeated);
        }
        artifact("positive-verification", mapper.createObjectNode().put("savedRunCount", base.store.runs(base.analysisId).size())
                .put("getEqualsSaved", saved.equals(got)).put("reuseEqualsSaved", saved.equals(reused))
                .put("successfulEvaluatorTraceCount", saved.at("/missenseCalls/evaluator").asInt())
                .put("getMeasuredCountersUnchanged", afterSubmit.equals(afterGet)).put("reuseMeasuredCountersUnchanged", afterGet.equals(afterReuse))
                .put("h2Reopened", h2Reopened).put("runtimeMapper", "EXISTING_SPRING_BUSINESS_MAPPER"));
        // One date-drift control reuses the already approved positive PS1 bundle, without rewriting it or its sources.
        if (!pm5) {
            byte[] originalBundle = Files.readAllBytes(fixture.bundlePath());
            JsonNode originalSources = fixture.rawSources().deepCopy();
            ObjectNode beforeDrift = measure("before-date-drift");
            ObjectNode drifted = fixture.loadBundleWithDateDrift().evaluate(MissenseComparisonRuntime.PS1, fixture.context(0), fixture.target(0));
            artifact("negative-date-drift", drifted); ObjectNode afterDrift = measure("after-date-drift");
            assertRuntimeRefusal(drifted, "REFERENCE_ASSERTION_VERSION_MISMATCH", true);
            assertDelta(beforeDrift, afterDrift, "runtimeSupplier", 1);
            assertDelta(beforeDrift, afterDrift, "runtimeDiscovery", 1);
            assertDelta(beforeDrift, afterDrift, "runtimeAnnotation", 1);
            assertDelta(beforeDrift, afterDrift, "splice", 0);
            assertArrayEquals(originalBundle, Files.readAllBytes(fixture.bundlePath()));
            assertEquals(originalSources, fixture.rawSources(), "Original fixed source is preserved beside its explicit runtime update");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"SOURCE_VUS", "PM5_INCOMPLETE"})
    void singleCauseRuntimeRefusalsRemainReachableThroughGeneratedFiles(String fault) throws Exception {
        scenario = fault; mapper = new AcmgBusinessIntegrationTest().mapper; steps = mapper.createArrayNode();
        boolean pm5 = fault.equals("PM5_INCOMPLETE");
        fixture = new GeneratedMissenseReferenceFixture(temporary.resolve("generated"), mapper, pm5,
                GeneratedMissenseReferenceFixture.Scenario.valueOf(fault));
        measure("before-capture"); fixture.build(); ObjectNode before = measure("after-capture-before-runtime");
        ObjectNode result = fixture.loadBundle().evaluate(pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1, fixture.context(0), fixture.target(0));
        artifact("runtime-refusal", result); ObjectNode after = measure("after-runtime");
        assertRuntimeRefusal(result, pm5 ? "MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE" : "REFERENCE_ASSERTION_CONFLICT", !pm5);
        assertDelta(before, after, "runtimeSupplier", 1); assertDelta(before, after, "runtimeDiscovery", 1); assertDelta(before, after, "runtimeAnnotation", 1);
        if (pm5) {
            assertDelta(before, after, "splice", 1);
            assertFalse(result.path("references").isEmpty(), result.toPrettyString());
            assertTrue(result.path("proteinComparisons").isArray() && result.path("proteinComparisons").isEmpty());
            assertEquals("SAME_AMINO_ACID_CHANGE", result.at("/referenceAssessments/0/proteinRelation").asText());
            boolean incomplete = false;
            for (JsonNode decision : result.at("/comparatorInput/candidateDecisions"))
                if ("NOT_EVALUATED".equals(decision.path("status").asText()) && "SELECTED_COMPARATOR_TRANSCRIPT_NOT_FOUND".equals(decision.path("reasonCode").asText())) incomplete = true;
            assertTrue(incomplete, result.toPrettyString());
            assertTrue(result.at("/comparatorInput/candidateDecisions").size() >= 2);
        } else {
            assertDelta(before, after, "splice", 0);
            JsonNode source = fixture.selectedAssertion(0);
            assertEquals("Uncertain significance", source.path("sourceAssertion").path("classification").asText());
            JsonNode policy = fixture.bundleDocument().path("policies").get(0);
            JsonNode audited = fixture.bundleDocument().at("/materials/missenseMaterials").path(policy.path("materialsSha256").asText()).at("/referenceAudit/references/0");
            assertEquals("PATHOGENIC", audited.path("classification").asText());
        }
    }

    private void assertRuntimeRefusal(JsonNode result, String reason, boolean referenceRejection) {
        assertEquals("READY", result.at("/comparatorInput/status").asText(), result.toPrettyString());
        assertEquals("NOT_EVALUATED", result.at("/criterionEvaluations/0/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(reason, result.at("/criterionEvaluations/0/evaluationResult/reasonCode").asText(), result.toPrettyString());
        assertEquals(0, result.path("evaluatorCalls").asInt());
        if (referenceRejection) {
            assertEquals("REJECTED", result.at("/referenceAssessments/0/status").asText());
            assertEquals(reason, result.at("/referenceAssessments/0/reasonCode").asText());
            assertFalse(result.at("/referenceAssessments/0/evidenceId").asText().isBlank());
        }
    }

    private void assertLineage(int background, JsonNode comparison) throws Exception {
        JsonNode bundle = fixture.bundleDocument(), policy = bundle.path("policies").get(background);
        JsonNode material = bundle.at("/materials/missenseMaterials").path(policy.path("materialsSha256").asText());
        JsonNode reference = material.at("/referenceAudit/references/0");
        JsonNode draft = fixture.selectedReference(background), assertion = fixture.selectedAssertion(background).path("sourceAssertion");
        JsonNode source = fixture.source(background);
        JsonNode captured = mapper.readTree(fixture.root.resolve("background-" + background + "/snapshot/reference-000.json").toFile());
        assertEquals(source.path("referenceAnnotation"), captured.path("annotation"));
        assertEquals(source.path("referenceAnnotation"), draft.path("annotation"));
        assertEquals(source.path("sourceAssertion"), assertion);
        assertEquals(source.path("referenceAllele"), draft.path("allele"));
        assertEquals(source.path("variationId").asText(), draft.path("variationId").asText());
        for (String field : List.of("allele", "variationId", "annotation")) assertEquals(draft.path(field), reference.path(field));
        assertEquals(assertion, reference.path("sourceAssertion")); assertEquals(assertion.path("dateLastEvaluated"), reference.path("evaluationDate"));
        assertEquals(assertion.path("scv"), reference.path("scv"));
        Path bridgeDirectory = fixture.root.resolve("background-" + background + "/bridge");
        JsonNode bridge = mapper.readTree(bridgeDirectory.resolve("manifest.json").toFile());
        for (String role : List.of("referenceAudit", "rnaReview")) {
            JsonNode descriptor = bridge.at("/backgrounds/" + background + "/" + role);
            JsonNode bridged = mapper.readTree(bridgeDirectory.resolve(descriptor.path("path").asText()).normalize().toFile());
            assertEquals(descriptor.path("canonicalSha256").asText(), fixture.canonicalSha256(bridged));
            assertEquals(bridged, material.path(role), "Bridge output must survive final preparation unchanged: " + role);
        }
        assertTrue(reference.path("annotation").has("hgvsc") && reference.path("annotation").has("hgvsp") && reference.path("annotation").has("timestamp"));
        assertEquals(reference.path("annotation"), comparison.at("/references/0/annotation"));
        assertEquals(reference.path("sourceAssertion"), comparison.at("/references/0/sourceAssertion"));
        assertEquals(reference.path("evaluationDate"), comparison.at("/references/0/evaluationDate"));
        assertEquals(reference.path("referenceId"), comparison.at("/scientificDecision/selectedReferenceId"));
        assertEquals(policy, comparison.path("policy"));
        assertEquals(fixture.canonicalSha256(policy), comparison.at("/policySelection/policySha256").asText());
        assertEquals(fixture.canonicalSha256(material), comparison.at("/comparisonTrace/materialsSha256").asText());
        String evidenceId = comparison.at("/scientificDecision/evidenceIds/0").asText(); assertFalse(evidenceId.isBlank());
        JsonNode selectedAtomic = null;
        for (JsonNode atomic : comparison.path("atomicEvidence")) if (evidenceId.equals(atomic.path("evidenceId").asText())) selectedAtomic = atomic;
        assertNotNull(selectedAtomic);
        JsonNode actualComparator = comparison.at("/comparatorInput/comparators/0");
        assertEquals(assertion, actualComparator.at("/submissions/0"), "Fresh sidecar query must preserve the independent assertion");
        assertEquals(source.path("variationId"), actualComparator.path("variationId"));
        for (String field : List.of("position", "reference", "alternate"))
            assertEquals(source.path("referenceAllele").path(field), actualComparator.path("exactAllele").path(field));
        assertEquals(source.at("/referenceAllele/assembly"), actualComparator.at("/exactAllele/genomeAssembly"));
        assertEquals(source.at("/referenceAllele/contig"), actualComparator.at("/exactAllele/chromosome"));
        for (String field : List.of("scv", "classification", "reviewStatus", "dateLastEvaluated", "geneSymbol"))
            assertEquals(assertion.path(field).asText(), metric(selectedAtomic, "clinvar.assertion." + field));
        assertEquals(source.path("variationId").asText(), metric(selectedAtomic, "clinvar.comparator.variationId"));
        for (String field : List.of("assembly", "contig", "position", "reference", "alternate"))
            assertEquals(source.path("referenceAllele").path(field).asText(), metric(selectedAtomic, "clinvar.comparator." + field));
        assertEquals(evidenceId, comparison.at("/referenceAssessments/0/evidenceId").asText());
        JsonNode pair = material.at("/rnaReview/comparisons/0");
        if (sourceAutomated) {
            assertTrue(material.at("/rnaReview/comparisons").isEmpty());
            assertEquals("PREDICTION_AND_REGISTERED_CONCERNS", material.at("/rnaReview/mode").asText());
        } else {
            assertEquals(fixture.context(background).at("/variant/evidenceQueryAllele"), pair.path("targetAllele"));
            assertEquals(reference.path("allele"), pair.path("referenceAllele"));
            assertEquals(material.at("/nativeModel/transcript"), pair.path("transcript"));
            assertFalse(pair.path("reviewId").asText().isBlank()); assertFalse(pair.path("sourceIds").isEmpty());
        }
        assertEquals(2, comparison.path("spliceEvidence").size());
        for (JsonNode evidence : comparison.path("spliceEvidence")) {
            assertEquals("0.01", evidence.path("maximumScore").asText());
            assertFalse(evidence.path("normalRnaClaimed").asBoolean(true));
        }
        ObjectNode link = mapper.createObjectNode().put("background", background).put("referenceId", reference.path("referenceId").asText())
                .put("assertionDraftId", fixture.selectedAssertion(background).path("assertionDraftId").asText())
                .put("sourceScv", assertion.path("scv").asText()).put("sourceDate", assertion.path("dateLastEvaluated").asText())
                .put("materialsSha256", fixture.canonicalSha256(material)).put("policySha256", fixture.canonicalSha256(policy))
                .put("evidenceId", evidenceId).put("fullAnnotationSha256", fixture.canonicalSha256(reference.path("annotation")))
                .put("fullSourceAssertionSha256", fixture.canonicalSha256(assertion));
        link.set("rnaPair", pair.deepCopy()); link.set("runtimeAtomic", selectedAtomic.deepCopy());
        link.set("freshRuntimeComparator", actualComparator.deepCopy()); artifact("lineage-" + background, link);
    }

    private String metric(JsonNode atomic, String id) {
        for (JsonNode value : atomic.path("measurements"))
            if (id.equals(value.path("metricId").asText())) return value.path("rawValue").asText();
        fail("Missing actual runtime measurement " + id); return "";
    }

    private void assertEvaluationIdentity(JsonNode value) {
        ObjectNode trace = ((ObjectNode) value.path("deterministicTrace")).deepCopy(); String pin = trace.path("traceSha256").asText(); trace.remove("traceSha256");
        assertEquals(pin, fixture.canonicalSha256(trace));
        ObjectNode evaluation = ((ObjectNode) value).deepCopy(); String id = evaluation.path("evaluationId").asText(); evaluation.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + fixture.canonicalSha256(evaluation), id);
    }
    private ObjectNode measure(String phase) throws Exception {
        ObjectNode measured = fixture.counts().deepCopy(); measured.put("coordinator", base == null ? 0 : base.invocations.get());
        ObjectNode row = steps.addObject().put("phase", phase); row.set("measured", measured.deepCopy());
        artifact("step-counts", steps); return measured;
    }
    private void assertDelta(JsonNode before, JsonNode after, String field, int expected) {
        assertTrue(before.has(field) && after.has(field), "Actual measured counter required: " + field);
        assertEquals(expected, after.path(field).asInt() - before.path(field).asInt(), field + "\n" + before + "\n" + after);
    }
    private void artifact(String name, JsonNode value) throws Exception {
        Path dir = temporary.resolve("observations"); Files.createDirectories(dir);
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(name + ".json").toFile(), value);
    }
    @AfterEach void closeAndArchive() throws Exception {
        try {
            if (base != null) {
                base.closeDatabase();
                artifact("database-cleanup", mapper.createObjectNode().put("isolation", "TASK_OWNED_FILE_H2")
                        .put("shutdownStatementCompleted", true).put("lockFileExistsAfterShutdown", Files.exists(temporary.resolve("business-db.lock.db")))
                        .put("reopened", h2Reopened).put("originalDatabaseTouched", false));
                assertFalse(Files.exists(temporary.resolve("business-db.lock.db")));
            }
        } finally {
            if (fixture != null) artifact("source-read-receipts", fixture.sourceReads());
            String output = System.getProperty("varvis.missense.generated.artifacts");
            if (output != null && scenario != null) {
                Path destination = Path.of(output).resolve(scenario); Files.createDirectories(destination);
                if (Files.isDirectory(temporary.resolve("generated"))) copyTree(temporary.resolve("generated"), destination.resolve("generated"));
                if (Files.isDirectory(temporary.resolve("observations"))) copyTree(temporary.resolve("observations"), destination.resolve("observations"));
                if (Files.isRegularFile(temporary.resolve("business-db.mv.db"))) Files.copy(temporary.resolve("business-db.mv.db"), destination.resolve("business-db.mv.db"));
            }
        }
    }
    private void copyTree(Path source, Path target) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path out = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(out); else Files.copy(file, out);
            }
        }
    }
}
