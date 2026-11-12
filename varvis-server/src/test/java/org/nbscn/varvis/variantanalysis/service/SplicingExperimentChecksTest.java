package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.SplicingExperimentCheckController;
import org.springframework.core.io.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.List;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SplicingExperimentChecksTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Resource catalog = new ClassPathResource("acmg/public-splicing-experiments-v1.json");
    ObjectNode document() throws Exception { try (var input = catalog.getInputStream()) { return (ObjectNode) mapper.readTree(input); } }
    SplicingExperimentChecks service(ObjectNode run, Resource resource) {
        return new SplicingExperimentChecks(mapper, resource, temporary, (analysis, id) -> {
            assertEquals("analysis", analysis); assertEquals("run", id); return run.deepCopy();
        });
    }
    ObjectNode run() { return mapper.createObjectNode().put("analysisId", "analysis").put("runId", "run"); }
    ObjectNode candidate(ObjectNode record, String name) {
        ObjectNode c = mapper.createObjectNode().put("candidateId", name);
        ObjectNode a = c.putArray("attempts").addObject();
        String tx = record.path("transcriptMapping").path("ensembl").asText();
        ObjectNode context = a.putObject("context").put("contextId", "context-" + name);
        ObjectNode variant = context.putObject("variant");
        variant.set("inputAllele", record.path("allele").deepCopy()); variant.set("evidenceQueryAllele", record.path("allele").deepCopy());
        variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        variant.putObject("normalization").put("refValidation", "MATCH");
        context.putObject("transcript").put("accession", tx.substring(0, tx.lastIndexOf('.'))).put("version", Integer.parseInt(tx.substring(tx.lastIndexOf('.') + 1)));
        context.putObject("gene").set("hgncId", record.path("geneId"));
        context.putObject("disease").put("mondoId", "MONDO:0007269").put("label", "DCM");
        a.putObject("transcriptInput").put("predictionPath", "SPLICEAI_SYNONYMOUS").put("versionedTranscriptId", tx).put("consequence", "synonymous_variant");
        ObjectNode evidence = a.putArray("atomicEvidence").addObject().put("evidenceId", "evidence-" + name).put("contextId", "context-" + name);
        evidence.putObject("source").put("sourceId", "SPLICEAI_FIXTURE");
        evidence.putObject("queryOutcome").put("status", "HIT"); evidence.putObject("resourceExecution").put("status", "SUCCEEDED");
        evidence.putObject("resourceAvailability").put("status", "AVAILABLE"); evidence.putObject("resourceApplicability").put("status", "APPLICABLE");
        ArrayNode metrics = evidence.putArray("measurements");
        metrics.addObject().put("metricId", "spliceai.exactAlleleMatch").put("normalizedValue", "true");
        metrics.addObject().put("metricId", "spliceai.selectedTranscriptId").put("normalizedValue", tx);
        metrics.addObject().put("metricId", "spliceai.maximumRawDeltaScore").put("normalizedValue", "0.09");
        metrics.addObject().put("metricId", "spliceai.geneContextMatch").put("normalizedValue", "true");
        ArrayNode evaluations = a.putArray("criterionEvaluations");
        for (String criterion : List.of("BP4", "BP7")) {
            ObjectNode e = evaluations.addObject().put("evaluationId", "eval-" + name + criterion).put("contextId", "context-" + name)
                    .put("criterionId", "ACMG_AMP_2015:" + criterion).put("appliedStrength", "SUPPORTING");
            e.putObject("criterionApplicability").put("status", "APPLICABLE"); e.putObject("evaluationResult").put("status", "MET");
            e.putArray("evidenceIds").add("evidence-" + name);
        }
        return c;
    }
    ObjectNode attempt(ObjectNode c) { return (ObjectNode) c.path("attempts").get(0); }
    ObjectNode record(int index) throws Exception { return (ObjectNode) document().path("records").get(index); }
    ObjectNode withCandidate(ObjectNode c) { ObjectNode r = run(); r.putArray("candidates").add(c); return r; }
    void state(ObjectNode result, String value) { assertEquals(value, result.path("checks").get(0).path("status").asText()); }

    @Test void productionCatalogAndCrossGeneMixedCandidates() throws Exception {
        ObjectNode lmna = candidate(record(0), "lmna"), cftr = candidate(record(1), "cftr");
        ObjectNode missing = candidate(record(0), "uncovered"), uncertain = candidate(record(0), "uncertain");
        ((ObjectNode) attempt(missing).path("context").path("variant").path("inputAllele")).put("position", 156138697);
        ((ObjectNode) attempt(missing).path("context").path("variant").path("evidenceQueryAllele")).put("position", 156138697);
        ((ObjectNode) attempt(uncertain).path("context").path("transcript")).put("version", 8);
        ((ObjectNode) attempt(uncertain).path("transcriptInput")).put("versionedTranscriptId", "ENST00000368300.8");
        ObjectNode r = run(); r.putArray("candidates").add(lmna).add(cftr).add(missing).add(uncertain);
        ObjectNode before = r.deepCopy(); var s = service(r, catalog); s.validateCatalog(document());
        var http = MockMvcBuilders.standaloneSetup(new SplicingExperimentCheckController(s)).build();
        ObjectNode result = (ObjectNode) mapper.readTree(http.perform(post("/variant-analyses/analysis/acmg/runs/run/splicing-checks"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(8, result.path("checks").size());
        for (int i : List.of(0, 1, 2, 3)) assertEquals("OBSERVATION_MISMATCH_REVIEW_REQUIRED", result.path("checks").get(i).path("status").asText());
        assertEquals("NO_MATCHING_RECORD", result.path("checks").get(4).path("reasonCode").asText());
        assertEquals("TRANSCRIPT_OR_GENE_APPLICABILITY_UNCERTAIN", result.path("checks").get(6).path("reasonCode").asText());
        assertEquals(before, r); // No mutation of the input, even in mixed candidates.
        assertEquals("NOT_ASSESSED_NO_CLINICAL_INFERENCE", result.path("checks").get(2).path("diseaseApplicability").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"allele", "assembly", "transcript", "version", "gene", "consequence", "context", "evidence", "failed", "notMet"})
    void strictMismatchNeverCreatesConflict(String change) throws Exception {
        ObjectNode c = candidate(record(0), "a"), a = attempt(c);
        switch (change) {
            case "allele" -> ((ObjectNode) a.path("context").path("variant").path("inputAllele")).put("alternate", "G");
            case "assembly" -> ((ObjectNode) a.path("context").path("variant").path("inputAllele")).put("assembly", "GRCh37");
            case "transcript" -> ((ObjectNode) a.path("context").path("transcript")).put("accession", "NM_000000");
            case "version" -> ((ObjectNode) a.path("context").path("transcript")).put("version", 8);
            case "gene" -> ((ObjectNode) a.path("context").path("gene")).put("hgncId", "HGNC:0");
            case "consequence" -> ((ObjectNode) a.path("transcriptInput")).put("consequence", "missense_variant");
            case "context" -> ((ObjectNode) a.path("criterionEvaluations").get(0)).put("contextId", "another");
            case "evidence" -> ((ObjectNode) a.path("atomicEvidence").get(0)).put("evidenceId", "unrelated");
            case "failed" -> ((ObjectNode) a.path("atomicEvidence").get(0).path("resourceExecution")).put("status", "FAILED");
            case "notMet" -> ((ObjectNode) a.path("criterionEvaluations").get(0).path("evaluationResult")).put("status", "NOT_MET");
            default -> throw new IllegalArgumentException();
        }
        state(service(withCandidate(c), catalog).checkCurrent("analysis", "run"), "UNVERIFIED");
    }

    @Test void verifiedManeRefseqPairMatchesButDiseaseDoesNotBecomeAnAssertion() throws Exception {
        ObjectNode rec = record(0), c = candidate(rec, "a"), a = attempt(c);
        ((ObjectNode) a.path("context").path("transcript")).put("accession", "NM_170707").put("version", 4);
        ((ObjectNode) a.path("transcriptInput")).put("versionedTranscriptId", "NM_170707.4");
        ((ObjectNode) a.path("atomicEvidence").get(0).path("measurements").get(1)).put("normalizedValue", "NM_170707.4");
        ((ObjectNode) a.path("context").path("disease")).put("label", "unrelated disease");
        ObjectNode value = service(withCandidate(c), catalog).checkCurrent("analysis", "run");
        state(value, "OBSERVATION_MISMATCH_REVIEW_REQUIRED");
        assertEquals("NOT_ASSESSED_NO_CLINICAL_INFERENCE", value.path("checks").get(0).path("diseaseApplicability").asText());
    }

    @Test void missingCorruptUnreadableAndTamperedMaterialsAreStructured() throws Exception {
        ObjectNode r = withCandidate(candidate(record(0), "a"));
        r.withArray("candidates").add(candidate(record(1), "b"));
        ObjectNode uncertain = candidate(record(0), "uncertain");
        ((ObjectNode) attempt(uncertain).path("context").path("transcript")).put("version", 8);
        r.withArray("candidates").add(uncertain);
        ObjectNode original = r.deepCopy();
        Resource missing = new FileSystemResource(requireNonNull(temporary.resolve("missing.json")));
        ObjectNode unavailable = service(r, missing).checkCurrent("analysis", "run");
        assertEquals(6, unavailable.path("checks").size());
        for (JsonNode check : unavailable.path("checks")) assertEquals("UNAVAILABLE", check.path("status").asText());
        Resource broken = new ByteArrayResource(requireNonNull("{".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("CATALOG_INVALID", service(r, broken).checkCurrent("analysis", "run").path("material").path("reasonCode").asText());
        Resource unreadable = new AbstractResource() {
            @Override public boolean exists() { return true; }
            @Override public @org.springframework.lang.NonNull String getDescription() { return "synthetic IO failure"; }
            @Override public @org.springframework.lang.NonNull java.io.InputStream getInputStream() throws java.io.IOException { throw new java.io.IOException("fixture"); }
        };
        assertEquals("CATALOG_READ_FAILED", service(r, unreadable).checkCurrent("analysis", "run").path("material").path("reasonCode").asText());
        ObjectNode doc = document(); ((ObjectNode) doc.path("records").get(0)).put("summary", "tampered");
        state(service(r, new ByteArrayResource(requireNonNull(mapper.writeValueAsBytes(doc)))).checkCurrent("analysis", "run"), "UNAVAILABLE");
        assertEquals(original, r);
    }

    @Test void damagedSavedSnapshotAndStorageFailureDoNotAlterComputation() throws Exception {
        ObjectNode r = withCandidate(candidate(record(0), "a")); var s = service(r, catalog);
        ObjectNode original = r.deepCopy(), saved = s.checkCurrent("analysis", "run");
        Path path;
        try (var files = Files.walk(temporary, 2)) { path = files.filter(p -> p.getFileName().toString().equals(saved.path("checkId").asText() + ".json")).findFirst().orElseThrow(); }
        Files.writeString(path, "{}");
        assertThrows(ResponseStatusException.class, () -> s.read("analysis", "run", saved.path("checkId").asText()));
        assertThrows(ResponseStatusException.class, () -> s.checkCurrent("analysis", "run"));
        Path blocked = temporary.resolve("not-a-directory"); Files.writeString(blocked, "fixture");
        var badStore = new SplicingExperimentChecks(mapper, catalog, blocked, (a, b) -> r.deepCopy());
        assertThrows(ResponseStatusException.class, () -> badStore.checkCurrent("analysis", "run"));
        assertEquals(original, r);
    }

    @Test void mixedHistoryKeepsValidVersionsAndDistinguishesUnavailableStorage() throws Exception {
        ObjectNode r = withCandidate(candidate(record(0), "a")); var s = service(r, catalog);
        ObjectNode saved = s.checkCurrent("analysis", "run"); String id = saved.path("checkId").asText();
        Path good;
        try (var files = Files.walk(temporary, 2)) {
            good = files.filter(p -> p.getFileName().toString().equals(id + ".json")).findFirst().orElseThrow();
        }
        byte[] original = Files.readAllBytes(good);
        String badId = "f".repeat(64); Path bad = good.resolveSibling(badId + ".json"); Files.writeString(bad, "{broken");
        var http = MockMvcBuilders.standaloneSetup(new SplicingExperimentCheckController(s)).build();
        String url = "/variant-analyses/analysis/acmg/runs/run/splicing-checks";
        JsonNode rows = mapper.readTree(http.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(2, rows.size());
        assertEquals("UNAVAILABLE", rows.get(0).path("availability").asText());
        assertEquals(badId, rows.get(0).path("checkId").asText());
        assertEquals("AVAILABLE", rows.get(1).path("availability").asText());
        assertEquals(saved, s.read("analysis", "run", id));
        http.perform(get(url + "/" + badId)).andExpect(status().isServiceUnavailable());
        assertArrayEquals(original, Files.readAllBytes(good)); assertEquals("{broken", Files.readString(bad));
        Path blocked = temporary.resolve("blocked-root"); Files.writeString(blocked, "fixture");
        var unavailable = new SplicingExperimentChecks(mapper, catalog, blocked, (a, b) -> r.deepCopy());
        assertEquals("CHECK_STORAGE_UNAVAILABLE", assertThrows(ResponseStatusException.class,
                () -> unavailable.list("analysis", "run")).getReason());
        assertTrue(new SplicingExperimentChecks(mapper, catalog, temporary.resolve("new-root"), (a, b) -> r.deepCopy()).list("analysis", "run").isEmpty());
    }

    @Test void oldAmIntronBoundaryAndBa1RemainReadableAndUnchanged() throws Exception {
        ObjectNode c = candidate(record(0), "a"), r = withCandidate(c), a = attempt(c);
        ((ObjectNode) a.path("transcriptInput")).put("predictionPath", "ALPHAMISSENSE_MISSENSE");
        assertTrue(service(r, catalog).checkCurrent("analysis", "run").path("checks").isEmpty());
        ((ObjectNode) a.path("transcriptInput")).put("predictionPath", "SPLICEAI_INTRONIC");
        state(service(r, catalog).checkCurrent("analysis", "run"), "UNVERIFIED");
        ((ObjectNode) a.path("transcriptInput")).put("predictionPath", "SPLICEAI_SYNONYMOUS");
        ((ObjectNode) a.path("criterionEvaluations").get(1).path("criterionApplicability")).put("status", "NOT_APPLICABLE");
        a.withArray("criterionEvaluations").addObject().put("criterionId", "ACMG_AMP_2015:BA1");
        ObjectNode original = r.deepCopy(); ObjectNode result = service(r, catalog).checkCurrent("analysis", "run");
        assertEquals(2, result.path("checks").size());
        assertEquals("CALCULATION_NOT_APPLICABLE", result.path("checks").get(1).path("reasonCode").asText());
        assertEquals(original, r);
    }

    @Test void httpVersionedReadExportRebuildAndConcurrentReuse() throws Exception {
        ObjectNode r = withCandidate(candidate(record(0), "a")); Path file = temporary.resolve("catalog.json");
        Files.write(file, mapper.writeValueAsBytes(document())); var s = service(r, new FileSystemResource(requireNonNull(file)));
        var http = MockMvcBuilders.standaloneSetup(new SplicingExperimentCheckController(s)).build();
        String base = "/variant-analyses/analysis/acmg/runs/run/splicing-checks";
        JsonNode first = mapper.readTree(http.perform(post(base)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String id = first.path("checkId").asText();
        for (String suffix : List.of("/" + id, "/" + id + "/export")) {
            assertEquals(first, mapper.readTree(http.perform(get(base + suffix)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        }
        ObjectNode changed = document();
        ObjectNode updated = (ObjectNode) changed.path("records").get(0);
        updated.put("version", "1.0.1").put("summary", "Updated test-only extraction; original observation retained.");
        updated.remove("contentSha256"); updated.put("contentSha256", s.hash(updated));
        changed.put("version", "1.0.1"); changed.remove("contentSha256"); changed.put("contentSha256", s.hash(changed));
        Files.write(file, mapper.writeValueAsBytes(changed));
        var rebuilt = service(r, new FileSystemResource(requireNonNull(file))); ObjectNode second = rebuilt.checkCurrent("analysis", "run");
        assertNotEquals(id, second.path("checkId").asText()); assertEquals(first, rebuilt.read("analysis", "run", id));
        assertEquals(2, rebuilt.list("analysis", "run").size());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> rebuilt.checkCurrent("analysis", "run"));
            var two = executor.submit(() -> service(r, new FileSystemResource(requireNonNull(file))).checkCurrent("analysis", "run"));
            assertEquals(one.get(), two.get());
        } finally { executor.shutdownNow(); }
        assertThrows(ResponseStatusException.class, () -> rebuilt.read("analysis", "run", "../x"));
        Files.delete(file); assertEquals(first, rebuilt.read("analysis", "run", id));
        assertEquals(2, rebuilt.list("analysis", "run").size());
    }
}
