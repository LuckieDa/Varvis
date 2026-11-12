package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Central projection from actual CURRENT HTTP results and private executed modules.
 * Ranking, annotation, reference and source observations remain synthetic engineering
 * inputs; no region/function qualification or scientific evaluation is stubbed here. */
class ProteinLengthCentralPublicBusinessTest {
    @TempDir static Path sharedReference;
    @TempDir Path temporary;
    private AcmgBusinessIntegrationTest base;
    private Pm4Bp3ComplexPublicFixture materials;

    @AfterEach void close() { if (base != null) base.closeDatabase(); }

    private void prepare(String scenario) throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        materials = new Pm4Bp3ComplexPublicFixture(base.mapper, temporary.resolve("resources"), sharedReference);
        boolean stopLoss = "stop".equals(scenario);
        if (stopLoss) materials.stopLossFirst(); else materials.repeatFirst();
        materials.applyNormalizationTo(base.properties); base.fixture.sources.add(materials.referenceLedger());
        base.producer = materials.producer(base.fixture.sources);
        base.fixture.annotations = new LinkedHashMap<>(base.fixture.annotations);
        var first = materials.annotation("HBB");
        if (!stopLoss) first.put("consequence", "protein_altering_variant");
        base.fixture.annotations.put("public-hbb-compatible-e7v", new ArrayList<>(List.of(base.mapper.treeToValue(first, TranscriptConsequence.class))));
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(base.mapper.treeToValue(materials.annotation("GJB2"), TranscriptConsequence.class)));
        String position = stopLoss ? "118" : "105", ref = stopLoss ? "TAA" : "ACCT", alt = stopLoss ? "CGA" : "AGCTGAA";
        base.variantTsvOverride = tsv("1\t11\t" + position + "\t" + ref + "\t" + alt + "\tHBB\t3043\tAR\t0.8",
                "2\t13\t105\tACCT\tAGCTGAA\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t" + position + "\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\tGT\t0/1",
                "chr13\t105\t.\tACCT\tAGCTGAA\t.\tPASS\t.\tGT\t0/1"));
        base.recreateService(() -> {
            base.invocations.incrementAndGet();
            return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources);
        });
        var metadata = Bs1Runtime.localExomes(base.mapper, new VariantAnalysisProperties(), false);
        var population = AcmgBusinessService.formalPopulation(base.service, () -> metadata);
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population, materials::centralRuntime, temporary.resolve("reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    private ObjectNode confirmed() throws Exception {
        var options = base.service.inputOptions(base.analysisId, 2);
        var request = base.request.deepCopy().put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        request.putArray("selectedCriteria").add(GeneralPm4Runtime.CRITERION).add(GeneralBp3Runtime.CRITERION);
        var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var rec = options.at("/candidates/" + i + "/recommendation");
            var item = (ObjectNode) request.path("hypotheses").get(i); var h = (ObjectNode) item.path("hypothesis");
            h.set("hypothesisId", rec.path("hypothesisId")); JsonNode relationship = null, annotation = null;
            for (var row : rec.path("relationships")) if (row.path("mondoId").equals(h.path("mondoId"))) relationship = row;
            for (var row : rec.path("annotations")) if (row.path("annotationId").equals(rec.path("recommendedAnnotationId"))) annotation = row;
            var relation = Objects.requireNonNull(relationship, rec::toPrettyString);
            var selected = Objects.requireNonNull(annotation, rec::toPrettyString);
            h.set("moi", relation.path("moi"));
            String tx = selected.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
            var transcript = (ObjectNode) item.path("transcript");
            transcript.put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText());
            transcript.set("annotationSource", selected.path("annotationSource")); transcript.set("annotationSourceVersion", selected.path("annotationSourceVersion"));
            ((ObjectNode) transcript.path("source")).put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var choice = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) choice.set(field, rec.path(field));
            choice.set("stableRelationshipKey", relation.path("stableRelationshipKey")); choice.set("annotationId", selected.path("annotationId"));
        }
        return request;
    }

    private JsonNode criterion(JsonNode saved, int candidate, String code) {
        for (var row : saved.at("/candidates/" + candidate + "/attempts/0/localBatch/criteria"))
            if (("ACMG_AMP_2015:" + code).equals(row.path("criterionId").asText())) return row;
        throw new AssertionError("Missing selected " + code);
    }
    private JsonNode publicCriterion(JsonNode projected, int candidate, String code) {
        for (var row : projected.at("/results/" + candidate + "/localBatch/criteria"))
            if (("ACMG_AMP_2015:" + code).equals(row.path("criterionId").asText())) return row;
        throw new AssertionError("Missing projected " + code);
    }
    private JsonNode facts(JsonNode criterion, String code) { return criterion.at("/details/" + ("PM4".equals(code) ? "proteinLengthFacts" : "bp3Facts")); }
    private ObjectNode wrapperCriterion(ObjectNode full, String code) {
        for (var row : full.at("/results/0/localBatch/criteria"))
            if (("ACMG_AMP_2015:" + code).equals(row.path("criterionId").asText())) return (ObjectNode) row;
        throw new AssertionError("Missing private wrapper " + code);
    }
    private void unavailable(JsonNode value, String fault) {
        assertEquals("UNAVAILABLE", value.at("/sequenceMaterial/status").asText(), fault);
        assertEquals("UNAVAILABLE", value.at("/adoptionProof/status").asText(), fault);
        assertNotEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", value.path("annotationCorrespondence").asText(), fault);
    }

    @ParameterizedTest @ValueSource(strings = {"stop", "repeat"})
    void currentSavedModulesAreTheOnlySourceOfPublicProteinProofs(String scenario) throws Exception {
        prepare(scenario);
        for (String key : List.of("pm4", "proteinLengthMaterials", "bp3")) assertEquals(0, materials.configurationCalls().path(key).asInt(), key);
        assertEquals(ProteinLengthPublicProof.identity(base.mapper), materials.configurationIdentity().path("proteinLengthPublicProjectionCapability"));
        for (String key : List.of("pm4", "proteinLengthMaterials", "bp3")) assertEquals(0, materials.configurationCalls().path(key).asInt(), key);
        var request = confirmed(); var saved = base.postRequest(request);
        var first = criterion(saved, 0, "PM4"); var pm4 = facts(first, "PM4");
        assertEquals("stop".equals(scenario) ? "MET" : "NOT_MET", first.path("status").asText());
        assertEquals("stop".equals(scenario) ? "MODERATE" : "NONE", first.path("appliedStrength").asText());
        assertEquals("AVAILABLE", pm4.at("/adoptionProof/status").asText(), pm4.toPrettyString());
        assertEquals("READY", pm4.at("/sequenceMaterial/status").asText());
        assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", pm4.path("annotationCorrespondence").asText());
        var bp3Row = criterion(saved, 1, "BP3"); var bp3 = facts(bp3Row, "BP3");
        assertEquals("NOT_EVALUATED", bp3Row.path("status").asText()); assertEquals("NONE", bp3Row.path("appliedStrength").asText());
        assertEquals("READY", bp3.at("/sequenceMaterial/status").asText());
        assertEquals("UNAVAILABLE", bp3.at("/adoptionProof/status").asText());
        assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", bp3.path("annotationCorrespondence").asText());
        var stored = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        var full = (ObjectNode) base.mapper.readTree(stored.privateJson()).path("localBatch"); var before = full.deepCopy();
        assertEquals(pm4, facts(publicCriterion(LocalEvidenceBatchRuntime.publicView(base.mapper, full), 0, "PM4"), "PM4"));
        var raw = (ObjectNode) full.at("/modules/pm4/results/0");
        var stale = raw.path("sourceProjection").deepCopy();
        assertEquals("PENDING_COMPLETE_CDS_AND_PROTEIN_COMPARISON", stale.path("annotationCorrespondence").asText());
        var cached = full.deepCopy();
        ((ObjectNode) cached.at("/modules/pm4/results/0")).putObject("publicSummary").put("schemaVersion", "pm4-general-facts-v1").put("status", "FORGED");
        wrapperCriterion(cached, "PM4").withObject("details").putObject("proteinLengthFacts").putObject("adoptionProof").put("status", "UNAVAILABLE");
        assertEquals(pm4, facts(publicCriterion(LocalEvidenceBatchRuntime.publicView(base.mapper, cached), 0, "PM4"), "PM4"), "Cached summaries cannot override the actual module");
        ((ObjectNode) cached.at("/modules/bp3/results/1")).putObject("publicSummary").put("schemaVersion", "bp3-general-facts-v1").putObject("adoptionProof").put("status", "AVAILABLE");
        ((ObjectNode) cached.at("/results/1/localBatch/criteria/1/details")).putObject("bp3Facts").putObject("adoptionProof").put("status", "AVAILABLE");
        assertEquals(bp3, facts(publicCriterion(LocalEvidenceBatchRuntime.publicView(base.mapper, cached), 1, "BP3"), "BP3"), "A cached BP3 label cannot adopt missing functional qualification");
        var mutations = base.mapper.createArrayNode();
        for (String fault : List.of("pointer-other-candidate", "pointer-container", "pointer-other-module", "wrapper-candidate", "wrapper-hypothesis", "wrapper-context",
                "wrapper-status", "wrapper-strength", "original-evaluation", "raw-evaluation", "raw-policy", "module-policy", "source-pin", "module-source-pin", "swap-results")) {
            var changed = full.deepCopy(); var wrapper = (ObjectNode) changed.at("/results/0");
            var outer = wrapperCriterion(changed, "PM4"); var details = (ObjectNode) outer.path("details");
            var actual = (ObjectNode) changed.at("/modules/pm4/results/0");
            switch (fault) {
                case "pointer-other-candidate" -> details.put("modulePointer", "/modules/pm4/results/1");
                case "pointer-container" -> details.put("modulePointer", "/modules/pm4");
                case "pointer-other-module" -> details.put("modulePointer", "/modules/bp3/results/0");
                case "wrapper-candidate" -> wrapper.put("candidateId", "another-candidate");
                case "wrapper-hypothesis" -> wrapper.put("hypothesisId", "another-hypothesis");
                case "wrapper-context" -> ((ObjectNode) wrapper.path("localBatch")).put("contextId", "another-context");
                case "wrapper-status" -> outer.put("status", "NOT_EVALUATED");
                case "wrapper-strength" -> outer.put("appliedStrength", "STRONG");
                case "original-evaluation" -> details.putObject("originalResult").put("status", "FORGED");
                case "raw-evaluation" -> actual.withObject("evaluation").put("appliedStrength", "STRONG");
                case "raw-policy" -> actual.withObject("policy").put("policyVersion", "0.0.0");
                case "module-policy" -> ((ObjectNode) changed.at("/modules/pm4/policy")).put("policyVersion", "0.0.0");
                case "source-pin" -> changed.put("sourceSnapshotSha256", "0".repeat(64));
                case "module-source-pin" -> ((ObjectNode) changed.at("/modules/pm4")).put("sourceSnapshotSha256", "0".repeat(64));
                case "swap-results" -> {
                    var results = (ArrayNode) changed.at("/modules/pm4/results"); var second = results.get(1).deepCopy();
                    results.set(1, results.get(0).deepCopy()); results.set(0, second);
                }
                default -> throw new AssertionError(fault);
            }
            assertNotEquals(full, changed, fault); var snapshot = changed.deepCopy();
            var projected = publicCriterion(LocalEvidenceBatchRuntime.publicView(base.mapper, changed), 0, "PM4");
            unavailable(facts(projected, "PM4"), fault);
            for (String field : List.of("status", "appliedStrength", "reasonCode")) assertEquals(outer.path(field), projected.path(field), fault);
            assertEquals(snapshot, changed, "Projection must not repair or rescore supplied science: " + fault);
            mutations.addObject().put("fault", fault).put("proof", "UNAVAILABLE").put("inputUnchanged", true);
        }
        assertEquals(before, full); assertEquals(stale, raw.path("sourceProjection"));
        assertTrue(materials.configurationIdentity().path("proteinLengthPublicProjectionCapability").isObject());
        assertEquals(0, materials.configurationCalls().path("actualStubCount").asInt());
        var counts = materials.configurationCalls(); var live = materials.liveRuntimeCounters(); int coordinators = base.invocations.get();
        assertEquals(saved, base.postRequest(request)); assertEquals(saved, base.service.read(base.analysisId, stored.id()));
        var http = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, stored.id())).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(saved, base.mapper.readTree(http.getContentAsByteArray()));
        for (String key : List.of("pm4", "proteinLengthMaterials", "bp3", "transcriptMaterials", "pvs1", "population", "actualStubCount"))
            assertEquals(counts.path(key), materials.configurationCalls().path(key), key);
        for (String key : List.of("initializedPm4Runtimes", "pm4Evaluations")) assertEquals(live.path(key), materials.liveRuntimeCounters().path(key), key);
        assertEquals(coordinators, base.invocations.get()); assertEquals(stored, base.store.run(base.analysisId, stored.id()).orElseThrow());
        anotherJvm(scenario, stored, saved, mutations);
    }

    private void anotherJvm(String scenario, AcmgBusinessStore.Run stored, ObjectNode saved, ArrayNode mutations) throws Exception {
        Path bundle = Files.createDirectories(temporary.resolve("reopen"));
        Files.write(bundle.resolve("run.json"), base.mapper.writeValueAsBytes(stored));
        Files.writeString(bundle.resolve("public.json"), Objects.requireNonNull(stored.responseJson()));
        Files.writeString(bundle.resolve("private.json"), Objects.requireNonNull(stored.privateJson()));
        var source = base.store.source(base.analysisId).orElseThrow();
        Files.write(bundle.resolve("source.json"), base.mapper.writeValueAsBytes(source)); Files.writeString(bundle.resolve("source-content.json"), source.json());
        var manifest = base.mapper.createObjectNode().put("analysisId", base.analysisId)
                .put("expectedSourceFile", bundle.resolve("source.json").toString()).put("expectedSourceJsonFile", bundle.resolve("source-content.json").toString());
        manifest.putArray("runs").addObject().put("scenario", scenario).put("runId", stored.id())
                .put("expectedRunFile", bundle.resolve("run.json").toString()).put("expectedPublicFile", bundle.resolve("public.json").toString())
                .put("expectedPrivateFile", bundle.resolve("private.json").toString());
        var originals = manifest.putArray("originals");
        for (Path file : materials.originalFiles()) {
            assertTrue(Files.size(file) <= 64L * 1024 * 1024, "The chromosome-sized shared reference must not be loaded or copied");
            originals.addObject().put("path", file.toAbsolutePath().toString()).put("rawSha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        Path manifestFile = bundle.resolve("manifest.json"); Files.write(manifestFile, base.mapper.writeValueAsBytes(manifest));
        base.closeDatabase();
        var child = new SystemExternalProcessRunner().run(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Pm4Bp3ComplexStoreReader.class.getName(),
                temporary.toAbsolutePath().toString(), manifestFile.toAbsolutePath().toString(), bundle.resolve("child").toString()), temporary, Duration.ofSeconds(45));
        assertTrue(child.isSuccess(), child.getOutput());
        var receipt = base.mapper.readTree(Files.readAllBytes(bundle.resolve("child/reader-receipt.json")));
        assertEquals("PASS", receipt.path("status").asText()); assertEquals(0, receipt.path("runtimeCalls").asInt());
        assertEquals(saved, base.mapper.readTree(Files.readAllBytes(bundle.resolve("child/" + scenario + "/full-export.json"))));
        String output = System.getProperty("central.protein.output");
        if (output != null) {
            Path folder = Files.createDirectories(Path.of(output).resolve(scenario + "-" + base.analysisId.substring(0, 8)));
            Files.write(folder.resolve("public.json"), base.mapper.writeValueAsBytes(saved));
            Files.writeString(folder.resolve("private.json"), Objects.requireNonNull(stored.privateJson()));
            Files.write(folder.resolve("mutations.json"), base.mapper.writeValueAsBytes(mutations));
            Files.write(folder.resolve("calls.json"), base.mapper.writeValueAsBytes(materials.configurationCalls()));
            Files.write(folder.resolve("runtime.json"), base.mapper.writeValueAsBytes(materials.liveRuntimeCounters()));
            try (var paths = Files.walk(bundle.resolve("child"))) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    var to = folder.resolve("child").resolve(bundle.resolve("child").relativize(file)); Files.createDirectories(to.getParent()); Files.copy(file, to);
                }
            }
        }
    }
}
