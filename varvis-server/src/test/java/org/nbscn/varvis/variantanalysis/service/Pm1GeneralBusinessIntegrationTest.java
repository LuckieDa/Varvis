package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TEST_ONLY positive/negative strand sources, ordinary MockMvc business flow and isolated file H2. */
class Pm1GeneralBusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    Pm1GeneralTestFixture fixture;
    ReviewedRuleBundle bundle;
    ObjectNode document;
    final AtomicInteger legacyPm1Calls = new AtomicInteger(), forbiddenProviderCalls = new AtomicInteger();
    final AtomicInteger referenceReadinessChecks = new AtomicInteger();
    String scenario;

    @BeforeEach void setup(TestInfo info) throws Exception {
        scenario = info.getTestMethod().orElseThrow().getName() + "-" + info.getDisplayName().replaceAll("[^a-zA-Z0-9_-]", "_");
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        fixture = new Pm1GeneralTestFixture(base.mapper); document = fixture.document();
        artifact("fixed-materials", document);
        for (int i = 0; i < 2; i++) artifact("fixed-target-" + i, base.mapper.valueToTree(fixture.target(i)));
        base.fixture.annotations = new LinkedHashMap<>();
        base.fixture.annotations.put("public-hbb-compatible-e7v", List.of(fixture.annotation(0)));
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(fixture.annotation(1)));
        base.variantTsvOverride = tsv("1\t11\t105\tA\tC\tHBB\t3043\tAR\t0.8", "2\t13\t213\tC\tA\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t105\t.\tA\tC\t.\tPASS\t.\tGT\t.", "chr13\t213\t.\tC\tA\t.\tPASS\t.\tGT\t."));
        tinyReference(); bundle = fixture.load(temporary.resolve("materials"), document); wire();
    }
    @AfterEach void close() throws Exception {
        if (base == null) return;
        try {
            if (bundle != null) artifact("final-counts", counts());
        } finally { base.closeDatabase(); }
        Path output = artifactDirectory();
        if (output != null) {
            Path database = temporary.resolve("business-db.mv.db");
            if (Files.isRegularFile(database)) Files.copy(database, output.resolve("business-db.mv.db"), StandardCopyOption.REPLACE_EXISTING);
            for (String relative : List.of("pm1-test-only.fa", "pm1-test-only.fa.fai", "materials/test-only-pm1-bundle.json",
                    "updated-materials/test-only-pm1-bundle.json", "damaged-materials/test-only-pm1-bundle.json")) {
                Path actual = temporary.resolve(relative);
                if (Files.isRegularFile(actual)) Files.copy(actual, output.resolve("actual-" + relative.replace('/', '-')), StandardCopyOption.REPLACE_EXISTING);
            }
            artifact("cleanup", base.mapper.createObjectNode().put("explicitH2Shutdown", true)
                    .put("lockFileAbsent", !Files.exists(temporary.resolve("business-db.lock.db")))
                    .put("newJvmStarted", false).put("realHttpServerStarted", false));
        }
    }
    private void wire() {
        var current = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources,
                () -> { base.invocations.incrementAndGet(); return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources); },
                () -> { forbiddenProviderCalls.incrementAndGet(); throw new IllegalStateException("TEST_ONLY_BA1_FORBIDDEN"); },
                AcmgRecommendations.current(base.mapper, null),
                () -> { forbiddenProviderCalls.incrementAndGet(); throw new IllegalStateException("TEST_ONLY_PM2_FORBIDDEN"); },
                () -> { forbiddenProviderCalls.incrementAndGet(); throw new IllegalStateException("TEST_ONLY_PVS1_FORBIDDEN"); },
                () -> { forbiddenProviderCalls.incrementAndGet(); throw new IllegalStateException("TEST_ONLY_MISSENSE_FORBIDDEN"); }, null,
                () -> { forbiddenProviderCalls.incrementAndGet(); throw new IllegalStateException("TEST_ONLY_FUNCTIONAL_FORBIDDEN"); },
                null, null, null, null, null,
                () -> { legacyPm1Calls.incrementAndGet(); throw new IllegalStateException("TEST_ONLY_LEGACY_PM1_FORBIDDEN"); }, true);
        base.service = AcmgBusinessService.pm1Development(current, bundle);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    private ObjectNode options() throws Exception {
        return (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId).param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private ObjectNode confirmed(ObjectNode options) {
        ObjectNode request = base.request.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.PM1_DECLARATION);
        var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            JsonNode r = options.at("/candidates/" + i + "/recommendation");
            ObjectNode item = (ObjectNode) request.path("hypotheses").get(i), hypothesis = (ObjectNode) item.path("hypothesis");
            hypothesis.put("hypothesisId", r.path("hypothesisId").asText()); item.put("affectedStatus", "UNKNOWN");
            JsonNode relation = null;
            for (JsonNode rel : r.path("relationships")) if (rel.path("mondoId").equals(hypothesis.path("mondoId"))) relation = rel;
            if (relation == null) throw new AssertionError(r.toPrettyString());
            hypothesis.set("moi", relation.path("moi"));
            JsonNode annotation = r.path("annotations").get(0);
            if (annotation == null) throw new AssertionError(r.toPrettyString());
            assertTrue(annotation.path("ruleContextReady").asBoolean(), r.toPrettyString());
            ObjectNode transcript = (ObjectNode) item.path("transcript");
            String versionedTranscript = annotation.path("versionedTranscriptId").asText();
            int dot = versionedTranscript.lastIndexOf('.');
            transcript.put("accession", versionedTranscript.substring(0, dot)).put("version", Integer.parseInt(versionedTranscript.substring(dot + 1)));
            transcript.put("selectionPolicy", annotation.path("ruleContextSelectionPolicy").asText());
            transcript.put("selectionReason", "TEST_ONLY fixed synthetic annotation and reviewed finite region; no real VEP or scientific approval");
            transcript.set("annotationSource", annotation.path("annotationSource")); transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            ((ObjectNode) transcript.path("source")).put("source", "TEST_ONLY_PM1_SYNTHETIC_SELECTION")
                    .put("recordLocator", "recommendations/" + r.path("recommendationId").asText() + "/selection");
            ObjectNode ref = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, r.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
        }
        return request;
    }
    private JsonNode evaluation(JsonNode saved, int index) { return saved.at("/candidates/" + index + "/attempts/0/pm1/evaluation"); }
    private ObjectNode counts() {
        return base.mapper.createObjectNode().put("coordinator", base.invocations.get()).put("legacyPm1Supplier", legacyPm1Calls.get())
                .put("otherScientificProviderSuppliers", forbiddenProviderCalls.get()).put("pm1Runtime", bundle.pm1RuntimeCalls())
                .put("positionCalculations", bundle.pm1PositionCalculations()).put("evaluator", bundle.pm1EvaluatorCalls())
                .put("referenceReadinessChecks", referenceReadinessChecks.get());
    }
    @Test void bothStrandsSaveReadRebuildAndReuseWithoutReevaluatingEvidence() throws Exception {
        var options = options(); var request = confirmed(options); var before = counts();
        assertEquals(AcmgBusinessRuleScope.PM1_DECLARATION, options.path("requiredDeclaration").asText());
        assertEquals(0, bundle.pm1RuntimeCalls());
        var saved = base.postRequest(request); artifact("submit", saved);
        assertEquals("COMPLETED", saved.path("status").asText(), saved.toPrettyString());
        for (int i = 0; i < 2; i++) {
            var evaluation = evaluation(saved, i);
            assertEquals("MET", evaluation.at("/evaluationResult/status").asText(), saved.toPrettyString());
            assertEquals(i == 0 ? "MODERATE" : "SUPPORTING", evaluation.path("appliedStrength").asText());
            assertTrue(saved.at("/candidates/" + i + "/enabledRuleResultsComplete").asBoolean());
            assertEquals(1, saved.at("/candidates/" + i + "/attempts/0/pm1/criterionEvaluations").size());
        }
        assertEquals(2, bundle.pm1RuntimeCalls()); assertEquals(2, bundle.pm1PositionCalculations()); assertEquals(2, bundle.pm1EvaluatorCalls());
        assertEquals(0, legacyPm1Calls.get()); assertEquals(0, forbiddenProviderCalls.get());
        var afterSave = counts();
        base.store = new AcmgBusinessStore(base.dataSource); base.sources = new AcmgExecutionSources(base.mapper, base.store); wire();
        var afterRebuild = counts(); assertEquals(afterSave, afterRebuild);
        String run = saved.path("runId").asText();
        JsonNode reopened = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, reopened); var afterGet = counts(); assertEquals(afterSave, afterGet);
        var reused = base.postRequest(request); assertEquals(saved, reused); var afterReuse = counts();
        // Resubmission rebinds the source before finding the saved run.
        assertEquals(afterSave.deepCopy().put("coordinator", afterSave.path("coordinator").asInt() + 1), afterReuse);
        assertEquals(1, base.store.runs(base.analysisId).size());
        artifact("input-options", options); artifact("request", request); artifact("get-after-store-service-rebuild", reopened); artifact("reuse", reused);
        var steps = base.mapper.createObjectNode(); steps.set("beforeSubmit", before); steps.set("afterSave", afterSave);
        steps.set("afterStoreServiceRebuild", afterRebuild); steps.set("afterGet", afterGet); steps.set("afterReuse", afterReuse); artifact("observed-counts", steps);
    }
    @Test void unconfirmedForgedOrWrongDeclarationsNeverEnterNewOrLegacyPm1() throws Exception {
        var request = confirmed(options()); var before = counts();
        var badDeclaration = request.deepCopy().put("declarationVersion", "ordinary-ui-v3");
        reject(badDeclaration, 400);
        var unconfirmed = request.deepCopy(); unconfirmed.remove("recommendations"); reject(unconfirmed, 400);
        for (String field : List.of("policy", "nativeModel", "regions", "reviewStatus")) {
            var forged = request.deepCopy(); forged.putObject(field).put("approved", true); reject(forged, 400);
        }
        assertEquals(before, counts()); assertTrue(base.store.runs(base.analysisId).isEmpty()); artifact("rejection-counts", counts());
    }
    @ParameterizedTest @ValueSource(strings = {"MATERIAL", "MODEL", "POLICY"})
    void newMaterialIdentityRejectsOldConfirmationAndCannotRewriteHistoricalGet(String changed) throws Exception {
        var originalDocument = document.deepCopy(); var request = confirmed(options()); var saved = base.postRequest(request);
        String originalRun = saved.path("runId").asText(); var priorCounts = counts();
        // Editing the disk file cannot mutate the service's immutable material or saved JSON.
        Files.writeString(temporary.resolve("materials/test-only-pm1-bundle.json"), "{\"TEST_ONLY_DAMAGED_FILE\":true}");
        assertEquals(saved, base.service.read(base.analysisId, originalRun)); assertEquals(priorCounts, counts());
        switch (changed) {
            case "MATERIAL" -> fixture.regions.get(0).put("materialVersion", "2");
            case "MODEL" -> fixture.models.get(0).put("modelVersion", "2");
            case "POLICY" -> fixture.policies.get(0).put("policyVersion", "2");
            default -> fail(changed);
        }
        document = fixture.document();
        bundle = fixture.load(temporary.resolve("updated-materials"), document); wire();
        var beforeOldConfirmation = counts(); reject(request, 409);
        assertEquals(beforeOldConfirmation.path("pm1Runtime"), counts().path("pm1Runtime"));
        assertEquals(0, bundle.pm1RuntimeCalls());
        var newOptions = options(); var next = base.postRequest(confirmed(newOptions));
        assertEquals("COMPLETED", next.path("status").asText(), next.toPrettyString());
        assertNotEquals(originalRun, next.path("runId").asText());
        assertEquals(saved, base.service.read(base.analysisId, originalRun)); assertEquals(2, base.store.runs(base.analysisId).size());
        assertNotEquals(originalDocument, document); assertEquals(2, bundle.pm1RuntimeCalls());
        assertEquals(0, legacyPm1Calls.get()); assertEquals(0, forbiddenProviderCalls.get());
        artifact("original-request", request); artifact("original-saved-run", saved); artifact("updated-materials", document);
        artifact("updated-options", newOptions); artifact("new-run", next); artifact("history-after-new-policy", base.service.read(base.analysisId, originalRun));
    }
    @Test void damagedPinnedModelRemainsCandidateLocalAndCannotBorrowLegacyPm1() throws Exception {
        String modelSha = document.at("/policies/0/transcriptModelBinding/canonicalSha256").asText();
        ((ObjectNode) document.at("/materials/transcriptModels").path(modelSha)).put("modelVersion", "TAMPERED_TEST_ONLY");
        artifact("damaged-materials", document); bundle = fixture.load(temporary.resolve("damaged-materials"), document); wire();
        var saved = base.postRequest(confirmed(options())); artifact("damaged-material-submit", saved);
        assertEquals("NOT_EVALUATED", saved.at("/candidates/0/attempts/0/pm1/evaluationResult/status").asText());
        assertEquals("PM1_MODEL_SHA_MISMATCH", saved.at("/candidates/0/attempts/0/pm1/evaluationResult/reasonCode").asText());
        assertEquals("MET", evaluation(saved, 1).at("/evaluationResult/status").asText());
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
        assertTrue(saved.at("/candidates/0/enabledRuleResultsComplete").asBoolean());
        assertTrue(saved.at("/candidates/1/enabledRuleResultsComplete").asBoolean());
        assertEquals(1, bundle.pm1RuntimeCalls()); assertEquals(0, legacyPm1Calls.get()); assertEquals(0, forbiddenProviderCalls.get());
    }
    @Test void changedPublishedCandidateCannotReusePreviouslySavedMet() throws Exception {
        var request = confirmed(options()); var saved = base.postRequest(request); artifact("original-saved-run", saved);
        assertEquals("MET", evaluation(saved, 0).at("/evaluationResult/status").asText(), saved.toPrettyString());
        var published = base.jobs.findInterpretations(base.analysisId, 0, 2);
        published.get(0).applyTranscriptAnnotations(List.of(fixture.annotation(0).withAnnotationMetadata("VEP", "COMPLETED", "116.0",
                java.time.Instant.parse("2026-09-26T12:00:00Z"))));
        base.jobs.replaceInterpretations(base.analysisId, published);
        artifact("changed-published-candidate", base.mapper.valueToTree(published.get(0)));
        int runtimeBefore = bundle.pm1RuntimeCalls();
        var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(Objects.requireNonNull(base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        artifact("changed-candidate-repeat-response", base.mapper.createObjectNode().put("httpStatus", response.getStatus())
                .put("error", response.getErrorMessage()).put("body", response.getContentAsString()));
        assertEquals(409, response.getStatus()); assertEquals(runtimeBefore, bundle.pm1RuntimeCalls());
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        assertEquals(1, base.store.runs(base.analysisId).size());
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING", "DUPLICATE", "WRONG_CRITERION", "DAMAGED", "SECOND_ATTEMPT_MISSING"})
    void onlyOneCompletePm1ResultCanCompleteTheSummary(String fault) throws Exception {
        var saved = base.postRequest(confirmed(options())); var altered = saved.deepCopy();
        ObjectNode pm1 = (ObjectNode) altered.at("/candidates/0/attempts/0/pm1");
        var evaluations = (com.fasterxml.jackson.databind.node.ArrayNode) pm1.path("criterionEvaluations");
        switch (fault) {
            case "MISSING" -> evaluations.removeAll();
            case "DUPLICATE" -> evaluations.add(evaluations.get(0).deepCopy());
            case "WRONG_CRITERION" -> pm1.put("criterionId", "ACMG_AMP_2015:PM4");
            case "DAMAGED" -> ((ObjectNode) pm1.path("evaluation")).remove("deterministicTrace");
            case "SECOND_ATTEMPT_MISSING" -> {
                var attempt = ((ObjectNode) altered.at("/candidates/0/attempts/0")).deepCopy(); attempt.remove("pm1");
                ((com.fasterxml.jackson.databind.node.ArrayNode) altered.at("/candidates/0/attempts")).add(attempt);
            }
            default -> fail(fault);
        }
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(Objects.requireNonNull(base.service),
                "finishCurrentScope", altered, base.mapper.createObjectNode());
        assertEquals("COMPLETED_WITH_BLOCKERS", altered.path("status").asText());
        assertFalse(altered.at("/candidates/0/enabledRuleResultsComplete").asBoolean());
        assertTrue(altered.at("/candidates/1/enabledRuleResultsComplete").asBoolean());
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText())); artifact("altered-summary", altered);
    }
    private void reject(ObjectNode request, int expected) throws Exception {
        var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                .content(Objects.requireNonNull(base.mapper.writeValueAsBytes(request)))).andExpect(status().is(expected)).andReturn().getResponse();
        artifact("rejected-" + UUID.randomUUID(), base.mapper.createObjectNode().put("httpStatus", response.getStatus())
                .put("error", response.getErrorMessage()).set("request", request));
    }
    private void tinyReference() throws Exception {
        String first = "A".repeat(99) + fixture.genomicSequence(0), second = "A".repeat(199) + fixture.genomicSequence(1);
        Path fasta = temporary.resolve("pm1-test-only.fa"), index = Path.of(fasta + ".fai");
        Files.writeString(fasta, ">chr11\n" + first + "\n>chr13\n" + second + "\n");
        Files.writeString(index, "chr11\t" + first.length() + "\t7\t" + first.length() + "\t" + (first.length() + 1)
                + "\nchr13\t" + second.length() + "\t" + (first.length() + 15) + "\t" + second.length() + "\t" + (second.length() + 1) + "\n");
        String sha = sha(Files.readAllBytes(fasta));
        var receipt = base.mapper.createObjectNode().put("resourceId", "TEST_ONLY_PM1_FASTA").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", "pm1-test-only.fa").put("expectedSha256", sha).put("observedSha256", sha)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt", "2026-09-26T00:00:00Z").put("completedAt", "2026-09-26T00:00:01Z")
                .put("indexSha256", sha(Files.readAllBytes(index))).put("resourceDataClass", "SYNTHETIC");
        var envelope = base.mapper.createObjectNode().put("source", "TEST_ONLY_PM1_REFERENCE").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        var ledger = ExplicitContextSourceLedger.create(base.mapper, envelope); base.fixture.sources.add(ledger);
        var properties = new VariantAnalysisProperties(); properties.getNormalization().setGrch38Reference(fasta.toString());
        properties.getNormalization().setGrch38ReferenceSha256(sha); properties.getNormalization().setGrch38ProfileId("TEST_ONLY_PM1");
        var validator = new NormalizationResourceValidator(properties) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                referenceReadinessChecks.incrementAndGet();
                return new NormalizationReferenceReadiness(assembly, "READY", "TEST_ONLY_PM1", "pm1-test-only.fa", sha,
                        true, true, 2, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TEST_READINESS"));
            }
        };
        base.producer = new NativeSnvSourceProducer(base.mapper, properties, temporary, base.fixture.sources, ledger.reference("checksum"), validator);
        artifact("reference-receipt", receipt);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Path artifactDirectory() throws Exception {
        String output = System.getProperty("varvis.pm1.artifacts"); if (output == null) return null;
        Path dir = Path.of(output).resolve("business").resolve(scenario); Files.createDirectories(dir); return dir;
    }
    private void artifact(String name, JsonNode value) throws Exception {
        Path dir = artifactDirectory(); if (dir == null) return;
        Files.writeString(dir.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
}
