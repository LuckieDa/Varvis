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

/** TEST_ONLY: real parser/publication, normal business API, file JDBC; no scientific provider calls. */
class Pm4BusinessIntegrationTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    Pm4TestFixtures fixture;
    ObjectNode document;
    ReviewedRuleBundle bundle;

    @BeforeEach void setup() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        fixture = new Pm4TestFixtures(base.mapper, 1);
        fixture.model.put("transcript", "ENST00000647020.1");
        ((ObjectNode) fixture.policy.path("scope")).put("mondoId", "MONDO:0013517");
        ((ObjectNode) fixture.policy.at("/scope/transcripts/0")).put("accession", "ENST00000647020"); fixture.bind();
        // Replace both persisted transcript records before publication; no retained scientific annotation enters the run.
        base.fixture.annotations = new LinkedHashMap<>();
        base.fixture.annotations.put("public-hbb-compatible-e7v", List.of(base.mapper.treeToValue(
                fixture.annotation("6_7insGCT", "Glu2_Pro3insAla", "inframe_insertion"), TranscriptConsequence.class)));
        ObjectNode other = fixture.annotation("6_7insGCT", "Glu2_Pro3insAla", "inframe_insertion")
                .put("geneSymbol", "GJB2").put("transcriptAccession", "ENST00000382848.5")
                .put("hgvsCoding", "ENST00000382848.5:c.6_7insGCT");
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(base.mapper.treeToValue(other, TranscriptConsequence.class)));
        base.variantTsvOverride = tsv("1\t11\t105\tA\tAGCT\tHBB\t3043\tAR\t0.8", "2\t13\t105\tA\tAGCT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t105\t.\tA\tAGCT\t.\tPASS\t.\tGT\t.", "chr13\t105\t.\tA\tAGCT\t.\tPASS\t.\tGT\t."));
        tinyReference(); document = fixture.document();
        // A selected second policy has a genuinely absent model: failure must remain candidate-local.
        ObjectNode policy2 = fixture.policy.deepCopy().put("policyId", "TEST_ONLY_MISSING_GJB2_MODEL");
        ObjectNode scope = (ObjectNode) policy2.path("scope"); scope.put("hgncId", "HGNC:4284").put("mondoId", "MONDO:0019497");
        ((ObjectNode) scope.at("/transcripts/0")).put("accession", "ENST00000382848").put("version", 5);
        ((ObjectNode) policy2.path("transcriptModelBinding")).put("canonicalSha256", "f".repeat(64));
        document.withArray("policies").add(policy2);
        ObjectNode entry = ((ObjectNode) document.at("/catalog/entries/0")).deepCopy().put("entryId", "TEST_ONLY_MISSING_MODEL_CLAUSE");
        entry.set("scope", scope.deepCopy()); entry.putObject("policyBinding").put("policyId", policy2.path("policyId").asText())
                .put("policyVersion", "1").put("policySha256", fixture.canonicalSha256(policy2));
        ((ObjectNode) document.path("catalog")).withArray("entries").add(entry);
        document.put("catalogSha256", fixture.canonicalSha256(document.path("catalog"))); wire();
    }

    @AfterEach void close() { if (base != null) base.closeDatabase(); }
    void wire() {
        bundle = fixture.bundle(document);
        base.recreateService(() -> {
            base.invocations.incrementAndGet();
            // Population/context assembly cannot dispatch any predictor or PVS1.
            return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources);
        });
        base.service = AcmgBusinessService.pm4Development(base.service, bundle);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    @ParameterizedTest @ValueSource(strings = {"NON_REPEAT", "REPEAT"})
    void normalOptionsSubmitFileSaveReopenGetAndReuse(String repeatStatus) throws Exception {
        if (repeatStatus.equals("REPEAT")) {
            String oldSha = fixture.canonicalSha256(fixture.regions);
            ((ObjectNode) fixture.regions.at("/intervals/0")).put("repeatStatus", "REPEAT"); fixture.bind();
            ((ObjectNode) document.at("/materials/pm4Regions")).remove(oldSha);
            ((ObjectNode) document.at("/materials/pm4Regions")).set(fixture.canonicalSha256(fixture.regions), fixture.regions);
            ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("policies")).set(0, fixture.policy);
            ((ObjectNode) document.at("/catalog/entries/0/policyBinding")).put("policySha256", fixture.canonicalSha256(fixture.policy));
            document.put("catalogSha256", fixture.canonicalSha256(document.path("catalog"))); wire();
        }
        var options = (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId).param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(0, options.path("evaluatorCalls").asInt()); assertEquals(0, bundle.lengthCalculations());
        var request = confirmedRequest(options); var before = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var unconfirmed = request.deepCopy(); unconfirmed.remove("recommendations");
        var rejection = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(unconfirmed)));
        assertEquals("RECOMMENDATION_REQUIRED", rejection.getReason()); assertEquals(0, bundle.lengthCalculations());
        var saved = base.postRequest(request);
        JsonNode positive = saved.at("/candidates/0/attempts/0/pm4");
        assertEquals(repeatStatus.equals("REPEAT") ? "NOT_MET" : "MET", positive.at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("PM4_MODEL_MISSING", saved.at("/candidates/1/attempts/0/pm4/evaluationResult/reasonCode").asText(), saved.toPrettyString());
        assertEquals(1, saved.at("/pm4Calls/lengthCalculations").asInt()); assertEquals(1, bundle.lengthCalculations());
        assertEquals(1, bundle.pm4EvaluatorCalls());
        assertEquals(1, saved.path("evaluatorCalls").asInt()); assertEquals(0, saved.path("providerCalls").asInt());
        for (String field : List.of("pvs1Calls", "predictionCalls", "ba1Calls", "pm2Calls", "ps3Calls"))
            assertEquals(0, saved.at("/" + field + "/evaluator").asInt());
        assertEquals(before, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)));
        assertEquals(1, base.store.runs(base.analysisId).size());
        String run = saved.path("runId").asText();
        assertTrue(Files.size(temporary.resolve("business-db.mv.db")) > 0);
        var persisted = base.store.run(base.analysisId, run).orElseThrow();
        base.closeDatabase();
        long databaseBytes = Files.size(temporary.resolve("business-db.mv.db"));
        String databaseSha = sha(Files.readAllBytes(temporary.resolve("business-db.mv.db")));
        base.recreateRepositories(); wire(); // New store, source recovery, service, bundle and connection.
        var read = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, read);
        int runtimeBefore = base.invocations.get(); var repeated = base.postRequest(request);
        assertEquals(saved, repeated); assertEquals(0, bundle.lengthCalculations()); assertEquals(runtimeBefore, base.invocations.get());
        assertEquals(0, bundle.pm4EvaluatorCalls());
        assertEquals(1, base.store.runs(base.analysisId).size());
        var counters = base.mapper.createObjectNode().put("dataClass", "TEST_ONLY").put("realScientificResourceCalls", 0)
                .put("lengthCalculationsFirstSubmit", 1).put("lengthCalculationsAfterReopenAndReuse", bundle.lengthCalculations())
                .put("evaluatorCallsFirstSubmit", 1).put("evaluatorCallsAfterReopenAndReuse", bundle.pm4EvaluatorCalls())
                .put("runtimeInvocationsBeforeReuse", runtimeBefore).put("runtimeInvocationsAfterReuse", base.invocations.get())
                .put("runId", run).put("fileH2", true).put("reopenedAfterShutdown", true)
                .put("closedDatabaseBytes", databaseBytes).put("closedDatabaseRawSha256", databaseSha);
        artifact(repeatStatus, "bundle", document); artifact(repeatStatus, "input-options", options);
        artifact(repeatStatus, "request", request); artifact(repeatStatus, "submit", saved); artifact(repeatStatus, "get-reopened", read);
        artifact(repeatStatus, "reused", repeated); artifact(repeatStatus, "counters", counters);
        artifact(repeatStatus, "stored-request", base.mapper.readTree(persisted.requestJson()));
        artifact(repeatStatus, "execution-source", base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()));
    }

    ObjectNode confirmedRequest(ObjectNode options) {
        ObjectNode request = base.request.deepCopy().put("declarationVersion", Pm4CriterionEvaluator.DECLARATION);
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
        String sequence = "A".repeat(99) + Pm4TestFixtures.CDS;
        Path fasta = temporary.resolve("pm4-test-only.fa"), index = Path.of(fasta + ".fai");
        Files.writeString(fasta, ">chr11\n" + sequence + "\n>chr13\n" + sequence + "\n");
        Files.writeString(index, "chr11\t120\t7\t120\t121\nchr13\t120\t135\t120\t121\n");
        String sha = sha(Files.readAllBytes(fasta));
        var receipt = base.mapper.createObjectNode().put("resourceId", "TEST_ONLY_PM4_FASTA").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", "pm4-test-only.fa").put("expectedSha256", sha).put("observedSha256", sha)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt", "2026-09-23T00:00:00Z").put("completedAt", "2026-09-23T00:00:01Z")
                .put("indexSha256", sha(Files.readAllBytes(index))).put("resourceDataClass", "SYNTHETIC");
        var envelope = base.mapper.createObjectNode().put("source", "TEST_ONLY_PM4_REFERENCE").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        var ledger = ExplicitContextSourceLedger.create(base.mapper, envelope); base.fixture.sources.add(ledger);
        var properties = new VariantAnalysisProperties(); properties.getNormalization().setGrch38Reference(fasta.toString());
        properties.getNormalization().setGrch38ReferenceSha256(sha); properties.getNormalization().setGrch38ProfileId("TEST_ONLY_PM4");
        var validator = new NormalizationResourceValidator(properties) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly, "READY", "TEST_ONLY_PM4", "pm4-test-only.fa", sha,
                        true, true, 2, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TEST_READINESS"));
            }
        };
        base.producer = new NativeSnvSourceProducer(base.mapper, properties, temporary, base.fixture.sources, ledger.reference("checksum"), validator);
    }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    void artifact(String scenario, String name, JsonNode value) throws Exception {
        String output = System.getProperty("varvis.pm4.artifacts"); if (output == null) return;
        Path dir = Path.of(output).resolve(scenario); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
}
