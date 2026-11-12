package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.GeneralBp3Runtime;
import org.nbscn.varvis.variantanalysis.provider.GeneralPm4Runtime;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual central configuration, public service/controller, selected source and file H2.
 * Reference bases, annotations and ranking are explicit synthetic engineering inputs;
 * no invented region/function review is used to manufacture a MET.
 */
class Pm4Bp3ComplexPublicBusinessTest {
    @TempDir static Path sharedReference;
    @TempDir Path temporary;
    private AcmgBusinessIntegrationTest base;
    private Pm4Bp3ComplexPublicFixture materials;
    private boolean forbidNewExecution;
    private ObjectNode lastInputOptions;

    @AfterEach void close() { if (base != null) base.closeDatabase(); }

    private void prepare(String fault, boolean stopLoss) throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        materials = new Pm4Bp3ComplexPublicFixture(base.mapper, temporary.resolve("resources"), sharedReference);
        if (stopLoss) materials.stopLossFirst();
        materials.applyNormalizationTo(base.properties);
        base.fixture.sources.add(materials.referenceLedger());
        base.producer = materials.producer(base.fixture.sources);
        base.fixture.annotations = new java.util.LinkedHashMap<>(base.fixture.annotations);
        var first = materials.annotation("HBB");
        if (!stopLoss) first.put("consequence", "protein_altering_variant");
        if ("hgvs".equals(fault)) first.put("hgvsCoding", "ENST00000647020.1:c.7_9delinsGCTGAG");
        base.fixture.annotations.put("public-hbb-compatible-e7v", new ArrayList<>(List.of(base.mapper.treeToValue(first, TranscriptConsequence.class))));
        if ("duplicate-vep".equals(fault)) base.fixture.annotations.get("public-hbb-compatible-e7v")
                .add(base.mapper.treeToValue(first, TranscriptConsequence.class));
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(base.mapper.treeToValue(materials.annotation("GJB2"), TranscriptConsequence.class)));
        String firstPosition = stopLoss ? "118" : "105", firstRef = stopLoss ? "TAA" : "ACCT", firstAlt = stopLoss ? "CGA" : "AGCTGAA";
        base.variantTsvOverride = tsv("1\t11\t" + firstPosition + "\t" + firstRef + "\t" + firstAlt + "\tHBB\t3043\tAR\t0.8",
                "2\t13\t105\tACCT\tAGCTGAA\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t" + firstPosition + "\t.\t" + firstRef + "\t" + firstAlt + "\t.\tPASS\t.\tGT\t0/1",
                "chr13\t105\t.\tACCT\tAGCTGAA\t.\tPASS\t.\tGT\t0/1"));
        switch (fault) {
            case "model" -> Files.writeString(materials.modelFile("HBB"), "{}");
            case "manifest" -> Files.writeString(materials.manifestFile(), "{}");
            case "receipt" -> materials.env().withProperty(Pm4Bp3ComplexPublicFixture.PREFIX + "reference-receipt-sha256", java.util.Objects.requireNonNull("f".repeat(64)));
            case "reference" -> materials.mismatchReferenceForFirst();
            default -> { }
        }
        if ("repeat".equals(fault)) materials.repeatFirst();
        install();
    }

    private void install() {
        base.recreateService(() -> {
            assertFalse(forbidNewExecution, "Saved reads/reuse cannot reconstruct the source coordinator");
            base.invocations.incrementAndGet();
            return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources);
        });
        var bs1Metadata = org.nbscn.varvis.variantanalysis.provider.Bs1Runtime.localExomes(base.mapper,
                new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties(), false);
        var population = AcmgBusinessService.formalPopulation(base.service, () -> bs1Metadata);
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population, (effects, pin) -> {
            // Reconstructing code/configuration identity is metadata only. The actual scientific
            // builder/evaluator counts below must remain unchanged on saved reads and reuse.
            return materials.centralRuntime(effects, pin);
        }, temporary.resolve("effect-reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    private ObjectNode confirmed(String selection) throws Exception {
        var options = base.service.inputOptions(base.analysisId, 2);
        lastInputOptions = options.deepCopy();
        ObjectNode request = base.request.deepCopy().put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        ArrayNode criteria = request.putArray("selectedCriteria");
        if (!"BP3".equals(selection)) criteria.add(GeneralPm4Runtime.CRITERION);
        if (!"PM4".equals(selection)) criteria.add(GeneralBp3Runtime.CRITERION);
        var refs = request.putArray("recommendations");
        for (int index = 0; index < 2; index++) {
            var rec = options.at("/candidates/" + index + "/recommendation");
            var item = (ObjectNode) request.path("hypotheses").get(index);
            var hypothesis = (ObjectNode) item.path("hypothesis");
            hypothesis.put("hypothesisId", rec.path("hypothesisId").asText());
            JsonNode relation = null, annotation = null;
            for (var row : rec.path("relationships")) if (row.path("mondoId").equals(hypothesis.path("mondoId"))) relation = row;
            for (var row : rec.path("annotations")) if (row.path("annotationId").equals(rec.path("recommendedAnnotationId"))) annotation = row;
            relation = java.util.Objects.requireNonNull(relation, rec::toPrettyString);
            if (annotation == null) {
                var ref = refs.addObject();
                for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, rec.path(field));
                ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.putNull("annotationId");
                item.put("omitUnqualifiedTranscript", true);
                continue;
            }
            hypothesis.set("moi", relation.path("moi"));
            String tx = annotation.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
            var transcript = (ObjectNode) item.path("transcript");
            transcript.put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText());
            transcript.set("annotationSource", annotation.path("annotationSource"));
            transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            ((ObjectNode) transcript.path("source")).put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var ref = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, rec.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
        }
        var hypotheses = (ArrayNode) request.path("hypotheses");
        for (int index = hypotheses.size() - 1; index >= 0; index--)
            if (hypotheses.path(index).path("omitUnqualifiedTranscript").asBoolean()) hypotheses.remove(index);
        return request;
    }

    private JsonNode criterion(JsonNode response, int candidate, String code) {
        for (var c : response.at("/candidates/" + candidate + "/attempts/0/localBatch/criteria"))
            if (("ACMG_AMP_2015:" + code).equals(c.path("criterionId").asText())) return c;
        throw new AssertionError("Missing selected criterion " + code + " at " + candidate + ": " + response.toPrettyString());
    }

    private JsonNode publicFacts(JsonNode value, String code) {
        return value.at("/details/" + ("PM4".equals(code) ? "proteinLengthFacts" : "bp3Facts"));
    }

    private void assertPublicProof(JsonNode value, String code, boolean materialReady, boolean adoptionAvailable) {
        var facts = publicFacts(value, code);
        assertTrue(facts.isObject(), value::toPrettyString);
        assertEquals(materialReady ? "READY" : "UNAVAILABLE", facts.at("/sequenceMaterial/status").asText(), facts::toPrettyString);
        if (materialReady)
            assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", facts.path("annotationCorrespondence").asText(), facts::toPrettyString);
        else
            assertNotEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", facts.path("annotationCorrespondence").asText(), facts::toPrettyString);
        var proof = facts.path("adoptionProof");
        assertEquals(adoptionAvailable ? "AVAILABLE" : "UNAVAILABLE", proof.path("status").asText(), facts::toPrettyString);
        assertTrue(proof.path("reasonCode").isTextual() && !proof.path("reasonCode").asText().isBlank(), facts::toPrettyString);
        if (adoptionAvailable) {
            var binding = proof.path("binding");
            assertTrue(binding.isObject(), proof::toPrettyString);
            for (String field : List.of("candidateId", "hypothesisId", "evaluationContextId", "transcript", "protein"))
                assertTrue(binding.path(field).isTextual() && !binding.path(field).asText().isBlank(), proof::toPrettyString);
            assertTrue(binding.path("queryAllele").isObject(), proof::toPrettyString);
            for (String field : List.of("projectionBindingSha256", "modelCanonicalSha256", "manifestSha256",
                    "modelSourceRecordsSha256", "referenceSha256", "storedAnnotationSha256", "policySha256", "resultSha256"))
                assertTrue(binding.path(field).asText().matches("[0-9a-f]{64}"), () -> "Missing binding " + field + ": " + proof.toPrettyString());
        }
    }

    private void assertUnexecutedProofUnavailable(JsonNode value, String code) {
        // Missing context/configuration can stop before a module has a result to summarize.
        var facts = publicFacts(value, code);
        assertNotEquals("READY", facts.at("/sequenceMaterial/status").asText(), value::toPrettyString);
        assertNotEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", facts.path("annotationCorrespondence").asText(), value::toPrettyString);
        assertNotEquals("AVAILABLE", facts.at("/adoptionProof/status").asText(), value::toPrettyString);
    }

    @ParameterizedTest @ValueSource(strings = {"PM4", "BP3", "BOTH"})
    void actualCentralBuilderSelectedSaveFreshJvmFullReadAndRepeatDoNotReexecute(String selection) throws Exception {
        prepare("none", false);
        var request = confirmed(selection);
        var originalCandidates = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var saved = base.postRequest(request);
        int selectedCount = "BOTH".equals(selection) ? 2 : 1;
        for (int index = 0; index < 2; index++) {
            assertEquals(selectedCount, saved.at("/candidates/" + index + "/attempts/0/localBatch/criteria").size(), saved::toPrettyString);
            for (String code : List.of("PM4", "BP3")) if ("BOTH".equals(selection) || selection.equals(code)) {
                var c = criterion(saved, index, code);
                assertEquals("NOT_EVALUATED", c.path("status").asText(), c::toPrettyString);
                assertEquals("NONE", c.path("appliedStrength").asText());
                assertEquals("PM4".equals(code) ? "PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED"
                        : "BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", c.path("reasonCode").asText());
                assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", c.at("/details/applicabilityRouting/reasonCode").asText(),
                        "Preserve the earlier immutable precheck while the selected module completes its own full comparison");
                assertPublicProof(c, code, true, false);
            }
        }
        var stored = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        var full = base.mapper.readTree(stored.privateJson()).path("localBatch");
        int uncached = 0;
        for (String module : List.of("pm4", "bp3")) {
            boolean selected = "BOTH".equals(selection) || selection.equalsIgnoreCase(module);
            if (!selected) { assertFalse(full.path("modules").has(module)); continue; }
            assertEquals(2, full.at("/modules/" + module + "/evaluatorCalls").asInt(-1));
            for (var result : full.at("/modules/" + module + "/results")) {
                assertEquals("READY", result.at("/sequenceMaterial/status").asText(), result::toPrettyString);
                assertEquals("A5_NATIVE_GRCH38_SMALL_ALLELE_SEQUENCE_FACTS", result.at("/sequenceMaterial/sequenceCapability/capabilityId").asText());
                assertEquals("COMPLETE_SEQUENCE_COMPARISON_PASSED", result.path("annotationCorrespondence").asText());
                assertEquals("PENDING_COMPLETE_CDS_AND_PROTEIN_COMPARISON", result.at("/sourceProjection/annotationCorrespondence").asText());
                assertTrue(result.at("/sequenceMaterial/alleleAssociation/hgvscSequenceEquivalent").asBoolean());
                if (!result.at("/sequenceMaterial/modelCacheHit").asBoolean()) uncached++;
            }
        }
        assertEquals(2, uncached, "One model proof per exact transcript; pair shares the same material instance");
        for (String module : List.of("pvs1", "bp7", "prediction", "pm2", "reference", "deNovo", "pm3", "pp1"))
            assertFalse(full.path("modules").has(module), "Unselected scientific module " + module);
        assertEquals(originalCandidates, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)));
        assertActualFactoryAndEvaluations(selection);
        var callsBefore = materials.configurationCalls();
        var countersBefore = materials.liveRuntimeCounters();
        int coordinators = base.invocations.get();
        assertEquals(saved, base.postRequest(request));
        assertScientificConfigurationUnchanged(callsBefore); assertRuntimeCountersUnchanged(countersBefore);
        assertEquals(coordinators, base.invocations.get());
        freshJvmAndReopen(selection, saved, request);
    }

    @Test void existingStopLossRuleCanMeetWithoutManufacturedRegionApproval() throws Exception {
        prepare("none", true); var saved = base.postRequest(confirmed("BOTH"));
        var c = criterion(saved, 0, "PM4");
        assertEquals("MET", c.path("status").asText(), saved::toPrettyString);
        assertEquals("MODERATE", c.path("appliedStrength").asText());
        assertEquals("PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET", c.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", criterion(saved, 1, "PM4").path("status").asText());
        assertEquals("PM4_NO_NET_LENGTH_CHANGE", criterion(saved, 0, "BP3").path("reasonCode").asText());
        assertPublicProof(c, "PM4", true, true);
        assertPublicProof(criterion(saved, 1, "PM4"), "PM4", true, false);
        assertPublicProof(criterion(saved, 0, "BP3"), "BP3", false, false);
        assertPublicProof(criterion(saved, 1, "BP3"), "BP3", true, false);
        var proof = base.mapper.readTree(base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().privateJson())
                .at("/localBatch/modules/pm4/results/0/sequenceMaterial/sequenceCapability");
        assertFalse(proof.path("qualificationGranted").asBoolean(true));
        assertFalse(proof.path("scoringPermissionGranted").asBoolean(true));
        assertActualFactoryAndEvaluations("BOTH");
        artifact("stop-loss-rule-control", saved, base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings = {"hgvs", "model", "manifest", "receipt", "duplicate-vep", "reference"})
    void materialOrSourceFailureStaysSpecificAndDoesNotBorrowAnotherCandidatesQualification(String fault) throws Exception {
        prepare(fault, false); var request = confirmed("BOTH");
        if ("duplicate-vep".equals(fault)) {
            assertEquals("NO_COMPATIBLE_TRANSCRIPT", lastInputOptions.at("/candidates/0/recommendation/transcriptStatus").asText());
            assertEquals(0, lastInputOptions.at("/candidates/0/recommendation/annotations").size());
            assertEquals(2, base.fixture.annotations.get("public-hbb-compatible-e7v").size());
        }
        var saved = base.postRequest(request);
        assertEquals(2, saved.path("candidates").size(), "The failing candidate must remain selected and visible");
        String expectedReason = switch (fault) {
            case "hgvs" -> "PM4_HGVSC_SEQUENCE_MISMATCH";
            case "model" -> "TRANSCRIPT_MATERIAL_BYTES_MISMATCH";
            case "reference" -> "TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH";
            case "duplicate-vep" -> "EXPLICIT_INPUT_MISSING";
            case "manifest", "receipt" -> "PM4_CONFIGURATION_UNAVAILABLE";
            default -> throw new AssertionError("Unknown failure control " + fault);
        };
        for (String code : List.of("PM4", "BP3")) {
            var c = criterion(saved, 0, code);
            assertEquals("NOT_EVALUATED", c.path("status").asText(), saved::toPrettyString);
            assertEquals("NONE", c.path("appliedStrength").asText());
            assertNotEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", c.path("reasonCode").asText(), "Precheck defer must not mask the actual material failure");
            assertEquals(expectedReason, c.path("reasonCode").asText());
            if (List.of("hgvs", "model", "reference").contains(fault)) assertPublicProof(c, code, false, false);
            else assertUnexecutedProofUnavailable(c, code);
            if (List.of("hgvs", "model", "reference", "duplicate-vep").contains(fault)) {
                assertEquals("NOT_EVALUATED", criterion(saved, 1, code).path("status").asText());
                assertEquals("PM4".equals(code) ? "PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED"
                        : "BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", criterion(saved, 1, code).path("reasonCode").asText());
                assertPublicProof(criterion(saved, 1, code), code, true, false);
            } else {
                assertUnexecutedProofUnavailable(criterion(saved, 1, code), code);
            }
        }
        var calls = materials.configurationCalls();
        assertEquals(1, calls.path("pm4").asInt()); assertEquals(1, calls.path("proteinLengthMaterials").asInt());
        assertEquals(0, calls.path("actualStubCount").asInt());
        var counters = materials.liveRuntimeCounters();
        assertEquals(saved, base.postRequest(request)); assertScientificConfigurationUnchanged(calls);
        assertRuntimeCountersUnchanged(counters);
        var folder = artifact("failure-" + fault, saved, base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow());
        Files.write(folder.resolve("confirmed-input-options.json"), base.mapper.writeValueAsBytes(lastInputOptions));
    }

    @Test void anotherCandidatesAnnotationCannotAuthorizeThisAllele() throws Exception {
        prepare("none", false);
        var request = confirmed("BOTH");
        ((ObjectNode) request.at("/recommendations/0")).set("annotationId", request.at("/recommendations/1/annotationId"));
        var result = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId)
                .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(request))))
                .andExpect(status().isConflict()).andReturn();
        var failure = assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, result.getResolvedException());
        assertEquals("RECOMMENDATION_STALE", failure.getReason());
        for (String key : List.of("pm4", "proteinLengthMaterials", "bp3", "pvs1", "population"))
            assertEquals(0, materials.configurationCalls().path(key).asInt(), key);
        assertTrue(base.store.runs(base.analysisId).isEmpty());
    }

    @Test void pinnedRepeatFactsExcludePm4WithoutInventingBp3FunctionKnowledge() throws Exception {
        prepare("repeat", false);
        var saved = base.postRequest(confirmed("BOTH"));
        var first = criterion(saved, 0, "PM4");
        assertEquals("NOT_MET", first.path("status").asText(), first::toPrettyString);
        assertEquals("NONE", first.path("appliedStrength").asText());
        assertEquals("PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET", first.path("reasonCode").asText());
        assertPublicProof(first, "PM4", true, true);
        assertPublicProof(criterion(saved, 0, "BP3"), "BP3", true, false);
        assertPublicProof(criterion(saved, 1, "PM4"), "PM4", true, false);
        assertPublicProof(criterion(saved, 1, "BP3"), "BP3", true, false);
        assertEquals("BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", criterion(saved, 0, "BP3").path("reasonCode").asText());
        assertEquals("PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED", criterion(saved, 1, "PM4").path("reasonCode").asText());
        assertActualFactoryAndEvaluations("BOTH");
        artifact("repeat-exclusion-control", saved, base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow());
    }

    private void assertActualFactoryAndEvaluations(String selection) {
        var calls = materials.configurationCalls();
        assertEquals(1, calls.path("pm4").asInt());
        assertEquals(1, calls.path("proteinLengthMaterials").asInt());
        assertEquals("PM4".equals(selection) ? 0 : 1, calls.path("bp3").asInt());
        assertEquals(0, calls.path("actualStubCount").asInt());
        var counters = materials.liveRuntimeCounters();
        assertEquals(1, counters.path("initializedPm4Runtimes").asInt());
        assertEquals("BP3".equals(selection) ? 0 : 2, counters.path("pm4Evaluations").asInt());
    }

    /** The public writer is a separate process, and must exit before the read process starts. */
    @Test void producerJvmExitsBeforeAnotherJvmReadsTheCompletePublicBusinessRecord() throws Exception {
        Path writerDirectory = Files.createDirectories(temporary.resolve("writer"));
        Files.createDirectories(writerDirectory.resolve("temp"));
        String configured = System.getProperty("a5.public.artifacts");
        Path root = configured == null ? temporary.resolve("artifacts") : Path.of(configured);
        Path output = Files.createDirectories(root.resolve("process-exit-" + java.util.UUID.randomUUID()));
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var runner = new SystemExternalProcessRunner();
        var writer = runner.run(List.of(java, "-Djava.io.tmpdir=" + writerDirectory.resolve("temp").toAbsolutePath(),
                "-Da5.public.bcftools=" + System.getProperty("a5.public.bcftools", "bcftools"), "-cp", classpath,
                Pm4Bp3ComplexPublicBusinessTest.class.getName(), writerDirectory.toAbsolutePath().toString(), output.toAbsolutePath().toString()),
                // The reused publication fixture reads versioned repository inputs relative to the module.
                // All generated data/database/reference paths remain isolated under writerDirectory.
                Path.of(System.getProperty("user.dir")).toAbsolutePath(), Duration.ofSeconds(60));
        Files.writeString(output.resolve("writer.log"), writer.getOutput());
        assertTrue(writer.isSuccess(), writer.getOutput());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var written = mapper.readTree(Files.readAllBytes(output.resolve("writer-result.json")));
        Path bundle = Path.of(written.path("bundle").asText());
        var reader = runner.run(List.of(java, "-cp", classpath, Pm4Bp3ComplexStoreReader.class.getName(),
                writerDirectory.toAbsolutePath().toString(), bundle.resolve("reader-manifest.json").toString(),
                output.resolve("reader").toAbsolutePath().toString()), writerDirectory, Duration.ofSeconds(40));
        Files.writeString(output.resolve("reader.log"), reader.getOutput());
        assertTrue(reader.isSuccess(), reader.getOutput());
        var receipt = mapper.readTree(Files.readAllBytes(output.resolve("reader/reader-receipt.json")));
        assertEquals("PASS", receipt.path("status").asText());
        assertEquals(0, receipt.path("runtimeCalls").asInt(-1));
        Files.write(output.resolve("process-lifecycle.json"), mapper.writeValueAsBytes(mapper.createObjectNode()
                .put("producerJvmExitedBeforeReaderLaunch", true).put("producerSuccess", writer.isSuccess())
                .put("readerSuccess", reader.isSuccess()).put("publicBusinessSelection", "PM4_AND_BP3")
                .put("readerReceipt", "reader/reader-receipt.json").put("referenceAndAnnotations", "SYNTHETIC")));
    }

    /** Test-only writer entry point. The caller awaits this JVM's successful exit before any read. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Writer requires isolated database and artifact directories");
        Path directory = Path.of(args[0]), output = Path.of(args[1]);
        if (!directory.isAbsolute() || !output.isAbsolute()) throw new IllegalArgumentException("Absolute isolated fixture paths required");
        var writer = new Pm4Bp3ComplexPublicBusinessTest();
        writer.temporary = directory;
        sharedReference = Files.createDirectories(directory.resolve("reference"));
        System.setProperty("a5.public.artifacts", output.toString());
        try {
            writer.prepare("none", false);
            var saved = writer.base.postRequest(writer.confirmed("BOTH"));
            writer.assertActualFactoryAndEvaluations("BOTH");
            for (int index = 0; index < 2; index++) for (String code : List.of("PM4", "BP3"))
                writer.assertPublicProof(writer.criterion(saved, index, code), code, true, false);
            var folder = writer.readerBundle("BOTH", saved);
            Files.write(output.resolve("writer-result.json"), writer.base.mapper.writeValueAsBytes(
                    writer.base.mapper.createObjectNode().put("bundle", folder.toString()).put("runId", saved.path("runId").asText())));
        } finally { writer.close(); }
    }

    private Path readerBundle(String scenario, ObjectNode saved) throws Exception {
        var run = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        Path folder = artifact(scenario, saved, run);
        var manifest = base.mapper.createObjectNode().put("analysisId", base.analysisId);
        var row = manifest.putArray("runs").addObject().put("scenario", scenario).put("runId", run.id());
        row.put("expectedRunFile", folder.resolve("expected-run.json").toString())
                .put("expectedPublicFile", folder.resolve("expected-public.json").toString())
                .put("expectedPrivateFile", folder.resolve("expected-private.json").toString());
        Path source = folder.resolve("expected-source.json"), sourceJson = folder.resolve("expected-source-content.json");
        var record = base.store.source(base.analysisId).orElseThrow();
        Files.write(source, base.mapper.writeValueAsBytes(record)); Files.writeString(sourceJson, record.json());
        manifest.put("expectedSourceFile", source.toString()).put("expectedSourceJsonFile", sourceJson.toString());
        var pins = manifest.putArray("originals");
        for (Path file : materials.originalFiles()) {
            // The generated shared reference has a streamed creation checksum receipt; do not
            // load its chromosome-sized synthetic padding into either JVM's object heap.
            if (Files.size(file) > 64L * 1024 * 1024) continue;
            pins.addObject().put("path", file.toAbsolutePath().toString())
                    .put("rawSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        Path file = folder.resolve("reader-manifest.json"); Files.write(file, base.mapper.writeValueAsBytes(manifest));
        return folder;
    }

    private void freshJvmAndReopen(String scenario, ObjectNode saved, ObjectNode request) throws Exception {
        var run = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        Path folder = readerBundle(scenario, saved), file = folder.resolve("reader-manifest.json");
        int coordinators = base.invocations.get(); var calls = materials.configurationCalls();
        var counters = materials.liveRuntimeCounters();
        base.closeDatabase();
        var child = new SystemExternalProcessRunner().run(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Pm4Bp3ComplexStoreReader.class.getName(), temporary.toAbsolutePath().toString(), file.toAbsolutePath().toString(),
                folder.resolve("fresh-jvm").toAbsolutePath().toString()), temporary, Duration.ofSeconds(40));
        Files.writeString(folder.resolve("fresh-jvm.log"), child.getOutput()); assertTrue(child.isSuccess(), child.getOutput());
        base.recreateRepositories(); forbidNewExecution = true; install();
        var read = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run.id()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, read); assertEquals(saved, base.postRequest(request));
        assertEquals(coordinators, base.invocations.get()); assertScientificConfigurationUnchanged(calls);
        assertRuntimeCountersUnchanged(counters);
        assertEquals(run, base.store.run(base.analysisId, run.id()).orElseThrow());
    }

    private void assertScientificConfigurationUnchanged(JsonNode before) {
        var after = materials.configurationCalls();
        for (String key : List.of("pm4", "proteinLengthMaterials", "bp3", "transcriptMaterials", "pvs1", "population", "actualStubCount"))
            assertEquals(before.path(key), after.path(key), "Read/reuse reopened scientific configuration: " + key);
    }

    private void assertRuntimeCountersUnchanged(JsonNode before) {
        var after = materials.liveRuntimeCounters();
        for (String key : List.of("initializedPm4Runtimes", "pm4Evaluations"))
            assertEquals(before.path(key), after.path(key), "Saved read/reuse re-evaluated an existing runtime: " + key);
    }

    private Path artifact(String scenario, ObjectNode saved, AcmgBusinessStore.Run run) throws Exception {
        String configured = System.getProperty("a5.public.artifacts");
        Path root = configured == null ? temporary.resolve("artifacts") : Path.of(configured);
        Path folder = Files.createDirectories(root.resolve(scenario + "-" + base.analysisId));
        Files.write(folder.resolve("actual-public.json"), base.mapper.writeValueAsBytes(saved));
        Files.writeString(folder.resolve("expected-public.json"), run.responseJson());
        Files.writeString(folder.resolve("expected-private.json"), run.privateJson());
        var privateResult = java.util.Objects.requireNonNull(base.mapper.readTree(run.privateJson()));
        var summaries = base.mapper.createObjectNode();
        for (String module : List.of("pm4", "bp3")) {
            var results = summaries.putArray(module);
            for (var result : privateResult.at("/localBatch/modules/" + module + "/results")) {
                var summary = results.addObject();
                for (String field : List.of("candidateId", "hypothesisId", "status", "reasonCode", "publicSummary"))
                    summary.set(field, result.path(field).deepCopy());
                var proof = result.at("/publicSummary/adoptionProof");
                if ("AVAILABLE".equals(proof.path("status").asText())) {
                    var binding = proof.path("binding"); var material = result.path("sequenceMaterial");
                    assertEquals(result.path("candidateId"), binding.path("candidateId"));
                    assertEquals(result.path("hypothesisId"), binding.path("hypothesisId"));
                    assertEquals(result.path("contextId"), binding.path("evaluationContextId"));
                    assertEquals(result.at("/sourceProjection/bindingSha256"), binding.path("projectionBindingSha256"));
                    assertEquals(material.path("queryAllele"), binding.path("queryAllele"));
                    assertEquals(material.path("modelCanonicalSha256"), binding.path("modelCanonicalSha256"));
                    assertEquals(material.path("manifestSha256"), binding.path("manifestSha256"));
                    assertEquals(material.at("/verification/referenceIdentity/expectedSha256"), binding.path("referenceSha256"));
                    assertEquals(material.at("/alleleAssociation/transcript"), binding.path("transcript"));
                    assertEquals(material.at("/alleleAssociation/protein"), binding.path("protein"));
                }
            }
        }
        for (var candidate : saved.path("candidates")) for (var attempt : candidate.path("attempts"))
            for (var value : attempt.at("/localBatch/criteria")) {
                String code = value.path("criterionId").asText().replace("ACMG_AMP_2015:", "");
                if (!List.of("PM4", "BP3").contains(code)) continue;
                String pointer = value.at("/details/modulePointer").asText();
                var actual = privateResult.path("localBatch").at(pointer);
                if (actual.path("publicSummary").isObject())
                    assertEquals(actual.path("publicSummary"), publicFacts(value, code), "HTTP facts must preserve the actual module summary");
            }
        Files.write(folder.resolve("actual-module-public-summaries.json"), base.mapper.writeValueAsBytes(summaries));
        Files.write(folder.resolve("expected-run.json"), base.mapper.writeValueAsBytes(run));
        Files.write(folder.resolve("actual-configuration-calls.json"), base.mapper.writeValueAsBytes(materials.configurationCalls()));
        Files.write(folder.resolve("actual-live-runtime-counters.json"), base.mapper.writeValueAsBytes(materials.liveRuntimeCounters()));
        Files.write(folder.resolve("actual-fixture-scope.json"), base.mapper.writeValueAsBytes(materials.scope()));
        Files.write(folder.resolve("scope.json"), base.mapper.writeValueAsBytes(base.mapper.createObjectNode()
                .put("referenceAndAnnotations", "SYNTHETIC_ENGINEERING_INPUTS_NOT_REAL_GRCH38_OR_SCIENTIFIC_APPROVAL")
                .put("actualCentralFactory", true).put("actualPublicControllerService", true).put("isolatedH2", true)
                .put("newVepOrModelOrNetworkCalls", 0).put("all18CriteriaTested", false)));
        return folder;
    }
}
