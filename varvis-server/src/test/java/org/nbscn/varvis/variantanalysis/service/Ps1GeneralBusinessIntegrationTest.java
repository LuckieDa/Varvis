package org.nbscn.varvis.variantanalysis.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
/** SYNTHETIC / TEST_ONLY normal options, submit, exact discovery, public comparator and file H2. */
class Ps1GeneralBusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    Ps1TestCatalog fixture;
    ObjectNode document;
    ReviewedRuleBundle bundle;
    @BeforeEach void setup() throws Exception {
        setup(true);
    }
    void setup(boolean legacyBundle) throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        fixture = new Ps1TestCatalog(base.mapper);
        base.fixture.annotations = new LinkedHashMap<>();
        base.fixture.annotations.put("public-hbb-compatible-e7v", List.of(base.mapper.treeToValue(fixture.annotation(0, false), TranscriptConsequence.class)));
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(base.mapper.treeToValue(fixture.annotation(1, false), TranscriptConsequence.class)));
        base.variantTsvOverride = tsv("1\t11\t105\tA\tC\tHBB\t3043\tAR\t0.8", "2\t13\t105\tA\tC\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t105\t.\tA\tC\t.\tPASS\t.\tGT\t.", "chr13\t105\t.\tA\tC\t.\tPASS\t.\tGT\t."));
        tinyReference(); if (legacyBundle) { document = fixture.document(); wire(); }
    }
    @AfterEach void close() { if (base != null) base.closeDatabase(); }
    void wire() throws Exception {
        bundle = fixture.load(temporary, document);
        base.recreateService(() -> { base.invocations.incrementAndGet(); return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources); });
        base.service = AcmgBusinessService.ps1Development(base.service, bundle);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    ObjectNode options() throws Exception {
        return (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId).param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    JsonNode evaluation(JsonNode run, int index) { return run.at("/candidates/" + index + "/attempts/0/missenseComparison/criterionEvaluations/0"); }
    @Test void twoBackgroundsSavedReopenedAndReusedWithoutAnyNewCalls() throws Exception {
        ObjectNode options = options(); assertEquals(0, fixture.discoveryCalls);
        var request = confirmedRequest(options); var saved = base.postRequest(request);
        artifact("TWO_BACKGROUNDS", "submit", saved);
        assertSummary(saved, "COMPLETED");
        for (int i = 0; i < 2; i++) {
            assertEquals("MET", evaluation(saved, i).at("/evaluationResult/status").asText(), saved.toPrettyString());
            assertEquals(i == 0 ? "STRONG" : "MODERATE", evaluation(saved, i).path("appliedStrength").asText());
            assertEquals(MissenseComparisonRuntime.PS1, evaluation(saved, i).path("criterionId").asText());
            assertDigests(evaluation(saved, i));
        }
        assertEquals(2, fixture.discoveryCalls); assertEquals(2, fixture.annotationCalls); assertEquals(2, fixture.spliceCalls);
        assertEquals(2, saved.at("/missenseCalls/evaluator").asInt());
        String run = saved.path("runId").asText(); base.closeDatabase();
        assertTrue(Files.size(temporary.resolve("business-db.mv.db")) > 0);
        base.recreateRepositories(); wire();
        int invocationsBeforeReuse = base.invocations.get();
        JsonNode reopened = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(invocationsBeforeReuse, base.invocations.get());
        assertEquals(saved, reopened);
        assertEquals(saved, base.postRequest(request));
        assertEquals(1, base.store.runs(base.analysisId).size());
        // Resubmission revalidates the confirmed background before reusing the saved evaluation.
        assertEquals(invocationsBeforeReuse + 1, base.invocations.get());
        assertEquals(2, fixture.discoveryCalls); assertEquals(2, fixture.annotationCalls); assertEquals(2, fixture.spliceCalls);
        artifact("TWO_BACKGROUNDS", "input-options", options); artifact("TWO_BACKGROUNDS", "request", request); artifact("TWO_BACKGROUNDS", "submit", saved);
        artifact("TWO_BACKGROUNDS", "get-reopened", reopened); artifact("TWO_BACKGROUNDS", "bundle", document);
        artifact("TWO_BACKGROUNDS", "counters", base.mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("fileH2Reopened", true).put("discovery", 2).put("vepSnapshotBatches", 2).put("spliceSyntheticBatches", 2).put("additionalCallsOnGetAndReuse", 0));
        // New pinned source material is a new execution identity; old GET stays byte-for-byte equivalent.
        fixture.materials.get(0).put("version", "2"); document = fixture.document(); wire();
        var updated = base.postRequest(confirmedRequest(options())); assertNotEquals(run, updated.path("runId").asText());
        assertEquals(saved, base.service.read(base.analysisId, run)); assertEquals(2, base.store.runs(base.analysisId).size());
        artifact("TWO_BACKGROUNDS", "updated-pin", updated);
    }
    void assertDigests(JsonNode value) {
        ObjectNode evaluation = ((ObjectNode) value).deepCopy(), trace = ((ObjectNode) value.path("deterministicTrace")).deepCopy();
        String traceSha = trace.path("traceSha256").asText(); trace.remove("traceSha256");
        assertEquals(traceSha, fixture.canonicalSha256(trace));
        String id = evaluation.path("evaluationId").asText(); evaluation.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + fixture.canonicalSha256(evaluation), id);
    }
    @Test void httpCannotSupplyTrustedMaterialsOrReuseAnOldConfirmation() throws Exception {
        var request = confirmedRequest(options());
        for (String field : List.of("policy", "referenceAudit", "materialsSha256")) {
            var forged = request.deepCopy(); forged.putObject(field).put("approved", true);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(forged)));
        }
        var unconfirmed = request.deepCopy(); unconfirmed.remove("recommendations");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(unconfirmed)));
        fixture.materials.get(0).put("version", "2"); document = fixture.document(); wire();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(request)));
        assertEquals(0, fixture.discoveryCalls); assertEquals(0, fixture.spliceCalls); assertTrue(base.store.runs(base.analysisId).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"DIFFERENT", "PARTIAL_DISCOVERY", "INDEPENDENCE", "SPLICE", "MATERIAL", "MATERIAL_SHA"})
    void structuredFailuresRemainDistinctAndCandidateLocal(String scenario) throws Exception {
        ObjectNode ref = (ObjectNode) fixture.materials.get(0).at("/referenceAudit/references/0");
        if (scenario.equals("DIFFERENT") || scenario.equals("PARTIAL_DISCOVERY")) {
            fixture.different = true;
            fixture.incidentalAssertion = true;
            fixture.incomplete = scenario.equals("PARTIAL_DISCOVERY");
            for (int i = 0; i < 2; i++) {
                ObjectNode r = (ObjectNode) fixture.materials.get(i).at("/referenceAudit/references/0");
                var allele = fixture.allele(fixture.materials.get(i).at("/nativeModel/contig").asText(), 103, "G", "A");
                r.set("allele", allele); r.set("annotation", fixture.annotation(i, true));
                ((ObjectNode) fixture.materials.get(i).at("/rnaReview/comparisons/0")).set("referenceAllele", allele);
            }
        } else if (scenario.equals("INDEPENDENCE")) ref.remove("independence");
        else if (scenario.equals("SPLICE")) fixture.missingSplice = true;
        document = fixture.document();
        if (scenario.equals("MATERIAL")) ((ObjectNode) document.at("/materials/missenseMaterials")).remove(document.at("/policies/0/materialsSha256").asText());
        if (scenario.equals("MATERIAL_SHA")) ((ObjectNode) document.at("/materials/missenseMaterials").path(document.at("/policies/0/materialsSha256").asText())).put("version", "unbound");
        wire(); var saved = base.postRequest(confirmedRequest(options()));
        artifact(scenario, "submit", saved);
        assertSummary(saved, scenario.equals("DIFFERENT") ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS");
        assertEquals(scenario.equals("DIFFERENT") ? "NOT_MET" : "NOT_EVALUATED", evaluation(saved, 0).at("/evaluationResult/status").asText(), saved.toPrettyString());
        if (scenario.equals("DIFFERENT")) {
            assertDigests(evaluation(saved, 0));
            assertEquals("NONE", evaluation(saved, 0).path("appliedStrength").asText());
            var comparisons = saved.at("/candidates/0/attempts/0/missenseComparison/referenceAssessments");
            assertEquals(2, comparisons.size());
            for (var comparison : comparisons) {
                assertEquals("PS1_DIFFERENT_PROTEIN_SUBSTITUTION", comparison.path("reasonCode").asText());
                assertTrue(comparison.path("nativeProteinIdentity").hasNonNull("proteinVariantKey"));
            }
        }
        else assertEquals(switch (scenario) {
            case "INDEPENDENCE" -> "REFERENCE_NOT_INDEPENDENT";
            case "MATERIAL" -> "PS1_REFERENCE_MATERIALS_MISSING";
            case "MATERIAL_SHA" -> "PS1_REFERENCE_MATERIALS_SHA_MISMATCH";
            case "PARTIAL_DISCOVERY" -> "MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE";
            default -> "SPLICE_EVIDENCE_UNAVAILABLE";
        }, evaluation(saved, 0).at("/evaluationResult/reasonCode").asText());
        if (scenario.equals("INDEPENDENCE") || scenario.startsWith("MATERIAL")) assertEquals("MET", evaluation(saved, 1).at("/evaluationResult/status").asText(), saved.toPrettyString());
        artifact(scenario, "submit", saved);
    }
    void assertSummary(JsonNode saved, String status) {
        assertAll(() -> assertEquals(status, saved.path("status").asText()),
                () -> assertTrue(saved.at("/candidates/0/enabledRuleResultsComplete").asBoolean()),
                () -> assertTrue(saved.at("/candidates/1/enabledRuleResultsComplete").asBoolean()));
    }
    @ParameterizedTest @ValueSource(strings={"MISSING", "DUPLICATE", "DAMAGED", "WRONG_CRITERION", "REASONLESS", "SECOND_ATTEMPT_MISSING"})
    void invalidPs1ResultsCannotCompleteTheBusinessSummary(String fault) throws Exception {
        var saved = base.postRequest(confirmedRequest(options()));
        var damaged = saved.deepCopy();
        var evaluations = (com.fasterxml.jackson.databind.node.ArrayNode) damaged.at("/candidates/0/attempts/0/missenseComparison/criterionEvaluations");
        ObjectNode evaluation = (ObjectNode) evaluations.get(0);
        switch (fault) {
            case "MISSING" -> evaluations.removeAll();
            case "DUPLICATE" -> evaluations.add(evaluation.deepCopy());
            case "DAMAGED" -> evaluation.remove("deterministicTrace");
            case "WRONG_CRITERION" -> evaluation.put("criterionId", MissenseComparisonRuntime.PM5);
            case "REASONLESS" -> {
                evaluation.removeAll(); evaluation.put("criterionId", MissenseComparisonRuntime.PS1);
                evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED");
            }
            case "SECOND_ATTEMPT_MISSING" -> {
                var attempt = ((ObjectNode) damaged.at("/candidates/0/attempts/0")).deepCopy();
                attempt.remove("missenseComparison");
                ((com.fasterxml.jackson.databind.node.ArrayNode) damaged.at("/candidates/0/attempts")).add(attempt);
            }
            default -> fail(fault);
        }
        var service = base.service;
        if (service == null) throw new IllegalStateException("Test service was not initialized");
        var stored = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        var privateData = (ObjectNode) base.mapper.readTree(stored.privateJson());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "finishCurrentScope", damaged, privateData);
        assertEquals("COMPLETED_WITH_BLOCKERS", damaged.path("status").asText());
        assertFalse(damaged.at("/candidates/0/enabledRuleResultsComplete").asBoolean());
        assertTrue(damaged.at("/candidates/1/enabledRuleResultsComplete").asBoolean());
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        artifact("SUMMARY_" + fault, "summary", damaged);
    }
    ObjectNode confirmedRequest(ObjectNode options) {
        ObjectNode request = base.request.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.PS1_DECLARATION);
        var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            JsonNode r = options.at("/candidates/" + i + "/recommendation");
            ObjectNode item = (ObjectNode) request.path("hypotheses").get(i), hypothesis = (ObjectNode) item.path("hypothesis");
            hypothesis.put("hypothesisId", r.path("hypothesisId").asText()); item.put("affectedStatus", "UNKNOWN");
            JsonNode relation = null;
            for (JsonNode rel : r.path("relationships")) if (rel.path("mondoId").equals(hypothesis.path("mondoId"))) relation = rel;
            assertNotNull(relation, r.toPrettyString()); hypothesis.set("moi", relation.path("moi"));
            JsonNode annotation = r.path("annotations").get(0); assertNotNull(annotation);
            assertTrue(annotation.path("ruleContextReady").asBoolean(), r.toPrettyString());
            ObjectNode transcript = (ObjectNode) item.path("transcript");
            transcript.put("selectionPolicy", annotation.path("ruleContextSelectionPolicy").asText());
            transcript.set("annotationSource", annotation.path("annotationSource"));
            transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            ((ObjectNode) transcript.path("source")).put("recordLocator", "recommendations/" + r.path("recommendationId").asText() + "/selection");
            ObjectNode ref = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, r.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
        }
        return request;
    }

    void tinyReference() throws Exception {
        String sequence = "A".repeat(99) + Ps1TestCatalog.CDS;
        Path fasta = temporary.resolve("ps1-test-only.fa"), index = Path.of(fasta + ".fai");
        Files.writeString(fasta, ">chr11\n" + sequence + "\n>chr13\n" + sequence + "\n");
        Files.writeString(index, "chr11\t120\t7\t120\t121\nchr13\t120\t135\t120\t121\n");
        String sha = sha(Files.readAllBytes(fasta));
        var receipt = base.mapper.createObjectNode().put("resourceId", "TEST_ONLY_PS1_FASTA").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", "ps1-test-only.fa").put("expectedSha256", sha).put("observedSha256", sha)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt", "2026-09-23T00:00:00Z").put("completedAt", "2026-09-23T00:00:01Z")
                .put("indexSha256", sha(Files.readAllBytes(index))).put("resourceDataClass", "SYNTHETIC");
        var envelope = base.mapper.createObjectNode().put("source", "TEST_ONLY_PS1_REFERENCE").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        var ledger = ExplicitContextSourceLedger.create(base.mapper, envelope); base.fixture.sources.add(ledger);
        var properties = new VariantAnalysisProperties(); properties.getNormalization().setGrch38Reference(fasta.toString());
        properties.getNormalization().setGrch38ReferenceSha256(sha); properties.getNormalization().setGrch38ProfileId("TEST_ONLY_PS1");
        var validator = new NormalizationResourceValidator(properties) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly, "READY", "TEST_ONLY_PS1", "ps1-test-only.fa", sha,
                        true, true, 2, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TEST_READINESS"));
            }
        };
        base.producer = new NativeSnvSourceProducer(base.mapper, properties, temporary, base.fixture.sources, ledger.reference("checksum"), validator);
    }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    void artifact(String scenario, String name, JsonNode value) throws Exception {
        String output = System.getProperty("varvis.ps1.artifacts"); if (output == null) return;
        Path dir = Path.of(output).resolve(scenario); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
}
