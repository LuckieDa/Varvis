package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.ExplicitCaseInputCoordinator.*;
import static org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;

/** Actual selected-source/context preparation and public consumers on invented sequence/VEP inputs.
 * This does not start the HTTP/store chain or create scientific region/function approval.
 */
class GeneralComplexProteinLengthBusinessTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String CDS = "ATGGAACCTGGCTTTAAATAA";
    private static final String CHANGED_CDS = "ATGGAAGCTGAAGGCTTTAAATAA";
    private static final String TRANSCRIPT = "ENST00000647020.1";
    private record Prepared(SelectedEvidencePreparation selected, VariantInterpretation original, JsonNode before,
            ObjectNode model, AtomicInteger normalizations, AtomicInteger referenceReads, String referenceSha) { }
    private record Runtimes(GeneralPm4Runtime pm4, GeneralBp3Runtime bp3, CodingTranscriptMaterialSupply material) { }

    @Test void selectedNormalizedComplexAlleleUsesTheRealFactoryAndRetainsMissingQualification() throws Exception {
        var p = prepare(false, false);
        var runtimes = runtimes(p, true);
        String id = p.original().getCandidateId();
        assertEquals(105, p.original().getObservation().getKey().getPosition());
        assertEquals(106, p.selected().context(id, "context-one").at("/variant/evidenceQueryAllele/position").asInt());
        assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> p.selected().legacyProjection(id, "context-one")).getMessage());
        var results = mapper.createArrayNode();
        for (String hypothesis : List.of("context-one", "context-two")) {
            var pm4 = runtimes.pm4().evaluate(p.selected(), id, hypothesis);
            var bp3 = runtimes.bp3().evaluate(p.selected(), id, hypothesis);
            for (var result : List.of(pm4, bp3)) {
                assertEquals("READY", result.at("/sequenceMaterial/status").asText(), result.toPrettyString());
                assertEquals("NOT_EVALUATED", result.path("status").asText(), result.toPrettyString());
                assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
                assertEquals("CODING_SEQUENCE_COMPARISON_REQUIRED_V3_TO_V2", result.at("/sourceProjection/projectionMethod").asText());
                assertEquals("PENDING_COMPLETE_CDS_AND_PROTEIN_COMPARISON", result.at("/sourceProjection/annotationCorrespondence").asText());
                assertEquals(sha(CHANGED_CDS), result.at("/sequenceMaterial/alleleAssociation/alteredCdsSha256").asText());
                assertEquals(sha("MEAEGFK*"), result.at("/sequenceMaterial/alleleAssociation/alteredProteinSha256").asText());
                assertTrue(result.at("/sequenceMaterial/alleleAssociation/hgvscSequenceEquivalent").asBoolean());
                assertTrue(result.at("/sequenceMaterial/alleleAssociation/hgvspSequenceEquivalent").asBoolean());
                assertEquals(ProteinLengthMaterialFactory.CAPABILITY_ID,
                        result.at("/sequenceMaterial/sequenceCapability/capabilityId").asText());
                assertFalse(result.at("/sequenceMaterial/sequenceCapability/qualificationGranted").asBoolean(true));
                assertFalse(result.at("/sequenceMaterial/sequenceCapability/scoringPermissionGranted").asBoolean(true));
                assertEquals(1, result.at("/sequenceMaterial/alleleAssociation/netAminoAcidChange").asInt());
                assertFalse(result.at("/sequenceMaterial/scientificEvaluationPerformed").asBoolean(true));
                assertFalse(result.path("publicSummary").toString().contains(CHANGED_CDS));
                assertEquals(p.before().at("/observation/key"), result.at("/sourceProjection/originalAnnotationAllele"));
            }
            assertEquals("PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED", pm4.path("reasonCode").asText());
            assertEquals("BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", bp3.path("reasonCode").asText());
            if (!results.isEmpty()) assertTrue(pm4.at("/sequenceMaterial/modelCacheHit").asBoolean());
            results.add(pm4); results.add(bp3);
        }
        assertEquals(1, p.normalizations().get());
        assertEquals(p.before(), mapper.valueToTree(p.original()));
        assertEquals(p.before(), p.selected().formalCandidate(id));
        artifact("ready-with-missing-qualification", p, results);
    }

    @Test void wrongHgvsCannotAcquireReadinessThroughTheSequenceProjection() throws Exception {
        var p = prepare(true, false);
        var runtimes = runtimes(p, true);
        var results = mapper.createArrayNode();
        for (var result : List.of(runtimes.pm4().evaluate(p.selected(), p.original().getCandidateId(), "context-one"),
                runtimes.bp3().evaluate(p.selected(), p.original().getCandidateId(), "context-one"))) {
            assertEquals("NOT_EVALUATED", result.path("status").asText(), result.toPrettyString());
            assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
            assertEquals("UNAVAILABLE", result.at("/sequenceMaterial/status").asText());
            assertEquals("PM4_HGVSC_SEQUENCE_MISMATCH", result.path("reasonCode").asText());
            assertFalse(result.has("annotationCorrespondence"));
            results.add(result);
        }
        assertEquals(p.before(), mapper.valueToTree(p.original()));
        artifact("wrong-hgvs", p, results);
    }

    @Test void oldConstructorStillRejectsComplexMaterialAndDoesNotChangeProjectionPolicy() throws Exception {
        var p = prepare(false, false);
        var old = runtimes(p, false);
        String id = p.original().getCandidateId();
        var bridge = p.selected().codingSequenceProjection(id, "context-one");
        var material = old.material().resolveProteinLengthChange(bridge.context(), bridge.queryCandidate());
        assertFalse(material.ready());
        assertEquals("PM4_COMPLEX_ALLELE_UNSUPPORTED", material.snapshot().path("reasonCode").asText());
        var pm4 = old.pm4().evaluate(p.selected(), id, "context-one");
        var bp3 = old.bp3().evaluate(p.selected(), id, "context-one");
        assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", pm4.path("reasonCode").asText());
        assertEquals("PM4_COMPLEX_ALLELE_UNSUPPORTED", bp3.path("reasonCode").asText());
        assertEquals("NONE", pm4.at("/evaluation/appliedStrength").asText());
        assertEquals("NONE", bp3.at("/evaluation/appliedStrength").asText());
        assertEquals(p.before(), mapper.valueToTree(p.original()));
        artifact("legacy-default", p, mapper.createArrayNode().add(pm4).add(bp3));
    }

    @ParameterizedTest @ValueSource(strings = {"PM4", "BP3"})
    void selectedSourceBlockerIsNotReplacedByTheTrustedTechnicalCapability(String criterion) throws Exception {
        var p = prepare(false, true);
        var runtimes = runtimes(p, true);
        var result = "PM4".equals(criterion) ? runtimes.pm4().evaluate(p.selected(), p.original().getCandidateId(), "context-one")
                : runtimes.bp3().evaluate(p.selected(), p.original().getCandidateId(), "context-one");
        assertEquals("SYNTHETIC_SOURCE_CHANGED", result.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", result.path("status").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertFalse(result.has("sequenceMaterial"));
        assertEquals(0, p.referenceReads().get());
        assertEquals(0, p.normalizations().get());
        assertEquals(p.before(), mapper.valueToTree(p.original()));
    }

    @ParameterizedTest @ValueSource(strings = {"PM4", "BP3", "BOTH"})
    void actualSelectedBatchExecutesOnlyTheBoundCriteriaAndReusesItsResult(String selection) throws Exception {
        var p = prepare(false, false);
        var actual = runtimes(p, true);
        var pm4Factories = new AtomicInteger();
        var bp3Factories = new AtomicInteger();
        var pvs1Factories = new AtomicInteger();
        // The current batch constructor requires the full extended capability shape. These
        // actual BP7 metadata and the forbidden PVS1 factory remain entirely unselected.
        var bp7 = new Bp7SelectedBatch(mapper, new SplicingExperimentChecks(mapper,
                new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"),
                temporary.resolve("unused-splicing-experiments"), (annotation, request) -> {
                    throw new AssertionError("Unselected BP7 must not acquire experiment material");
                }).captureCatalog());
        var batch = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode()
                .put("testConfiguration", "SYNTHETIC_A5_COMPLEX_SELECTED_BATCH"), null, null, null, null, bp7,
                () -> { pm4Factories.incrementAndGet(); return actual.pm4(); },
                () -> { pvs1Factories.incrementAndGet(); throw new AssertionError("Unselected PVS1 factory must remain closed"); },
                () -> { bp3Factories.incrementAndGet(); return actual.bp3(); });
        boolean pm4Selected = !"BP3".equals(selection), bp3Selected = !"PM4".equals(selection);
        List<String> criteria = "BOTH".equals(selection) ? List.of(GeneralPm4Runtime.CRITERION, GeneralBp3Runtime.CRITERION)
                : List.of(pm4Selected ? GeneralPm4Runtime.CRITERION : GeneralBp3Runtime.CRITERION);
        batch.bindExecutionCriteria(criteria);
        assertEquals(0, pm4Factories.get()); assertEquals(0, bp3Factories.get());
        assertEquals(0, actual.pm4().evaluations()); assertEquals(0, actual.bp3().evaluations());
        var analysis = new AnalysisContext(p.selected().assembly().analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES);
        String sourceSha = sha("SYNTHETIC_A5_SELECTED_BATCH_SOURCE");
        var first = batch.execute(p.selected(), analysis, sourceSha);
        var visible = batch.publicView(first);
        assertEquals("EXECUTED", first.path("executionMode").asText());
        assertEquals(mapper.valueToTree(criteria), first.path("executionCriteria"));
        assertEquals(2, first.path("results").size()); assertEquals(2, visible.path("results").size());
        assertEquals(pm4Selected ? 1 : 0, pm4Factories.get());
        assertEquals(bp3Selected ? 1 : 0, bp3Factories.get());
        assertEquals(pm4Selected ? 2 : 0, actual.pm4().evaluations());
        assertEquals(bp3Selected ? 2 : 0, actual.bp3().evaluations());
        assertEquals(0, pvs1Factories.get()); assertEquals(0, bp7.evaluatorCalls());
        for (String module : List.of("pm2", "prediction", "reference", "bp7", "pvs1", "pm1", "pp2", "bp1", "deNovo", "pm3", "pp1"))
            assertFalse(first.path("modules").has(module), "Unselected module must remain unopened: " + module);
        assertEquals(pm4Selected, first.path("modules").has("pm4"));
        assertEquals(bp3Selected, first.path("modules").has("bp3"));
        assertEquals(0, first.at("/modules/sharedSplice/providerCalls").asInt(-1));
        int uncachedModels = 0, sequenceChecks = 0;
        JsonNode verification = null;
        for (String module : List.of("pm4", "bp3")) {
            if (!first.path("modules").has(module)) continue;
            var outputs = first.at("/modules/" + module + "/results");
            assertEquals(2, outputs.size());
            assertEquals(2, first.at("/modules/" + module + "/evaluatorCalls").asInt());
            for (var output : outputs) {
                sequenceChecks++;
                assertEquals("READY", output.at("/sequenceMaterial/status").asText(), output.toPrettyString());
                assertEquals("NOT_EVALUATED", output.path("status").asText());
                assertEquals("NONE", output.at("/evaluation/appliedStrength").asText());
                assertEquals(sha(CHANGED_CDS), output.at("/sequenceMaterial/alleleAssociation/alteredCdsSha256").asText());
                if (!output.at("/sequenceMaterial/modelCacheHit").asBoolean()) uncachedModels++;
                if (verification == null) verification = output.at("/sequenceMaterial/verification");
                else assertEquals(verification, output.at("/sequenceMaterial/verification"));
            }
        }
        assertEquals(criteria.size() * 2, sequenceChecks);
        assertEquals(1, uncachedModels, "Both selected consumers and both contexts share one verified model cache");
        for (int row = 0; row < 2; row++) {
            var local = first.at("/results/" + row + "/localBatch/criteria");
            var publicLocal = visible.at("/results/" + row + "/localBatch/criteria");
            assertEquals(criteria.size(), local.size()); assertEquals(criteria.size(), publicLocal.size());
            for (int index = 0; index < criteria.size(); index++) {
                var criterion = local.path(index); var publicCriterion = publicLocal.path(index);
                assertEquals(criteria.get(index), criterion.path("criterionId").asText());
                assertEquals(criterion.path("criterionId"), publicCriterion.path("criterionId"));
                assertEquals("NOT_EVALUATED", publicCriterion.path("status").asText());
                assertEquals("NONE", publicCriterion.path("appliedStrength").asText());
                String pointer = publicCriterion.at("/details/modulePointer").asText();
                assertEquals("READY", first.at(pointer + "/sequenceMaterial/status").asText());
            }
        }
        assertFalse(visible.toString().contains(CHANGED_CDS));
        int readsBeforeRepeat = p.referenceReads().get();
        var repeated = batch.execute(p.selected(), analysis, sourceSha);
        assertEquals("REQUEST_RESULT_REUSE", repeated.path("executionMode").asText());
        assertEquals(0, repeated.path("newEvaluatorInvocations").asInt(-1));
        assertEquals(0, repeated.path("newProviderInvocations").asInt(-1));
        assertEquals(first.path("results"), repeated.path("results"));
        assertEquals(first.path("modules"), repeated.path("modules"));
        assertEquals(readsBeforeRepeat, p.referenceReads().get());
        assertEquals(pm4Selected ? 1 : 0, pm4Factories.get()); assertEquals(bp3Selected ? 1 : 0, bp3Factories.get());
        assertEquals(pm4Selected ? 2 : 0, actual.pm4().evaluations()); assertEquals(bp3Selected ? 2 : 0, actual.bp3().evaluations());
        assertEquals(0, pvs1Factories.get()); assertEquals(0, bp7.evaluatorCalls());
        assertEquals(1, p.normalizations().get()); assertEquals(p.before(), mapper.valueToTree(p.original()));
        batchArtifacts(selection, p, first, visible, repeated);
    }

    private void batchArtifacts(String selection, Prepared prepared, ObjectNode actualBatch, ObjectNode publicResponse,
            ObjectNode repeated) throws Exception {
        String directory = System.getProperty("a5.round4.artifacts");
        if (directory == null) return;
        Path output = Files.createDirectories(Path.of(directory).resolve("batch-" + selection.toLowerCase(java.util.Locale.ROOT)));
        var scope = mapper.createObjectNode().put("schemaVersion", "a5-round4-selected-batch-engineering-v1")
                .put("dataClass", "SYNTHETIC").put("normalSelectedPreparationExecuted", true).put("actualLocalBatchExecuted", true)
                .put("normalHttpOrStoreExecuted", false).put("centralConfigurationFactoryIntegrated", false)
                .put("normalizationTransport", "SYNTHETIC_BCFTOOLS_SHAPED_OUTPUT").put("selection", selection)
                .put("scientificQualificationGranted", false).put("normalizationCalls", prepared.normalizations().get())
                .put("referenceBasesRead", prepared.referenceReads().get());
        for (var file : List.of(Map.entry("scope.json", scope), Map.entry("actual-batch.json", actualBatch),
                Map.entry("public-response.json", publicResponse), Map.entry("repeated-batch.json", repeated)))
            Files.write(output.resolve(file.getKey()), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(file.getValue()));
    }

    private Prepared prepare(boolean wrongHgvs, boolean sourceBlocked) throws Exception {
        var source = new TopNSelectedNormalizationTest(); source.temporary = temporary;
        source.mapper.findAndRegisterModules();
        var nativeInput = source.fixture("A".repeat(99) + CDS);
        var data = new Pm4TestFixtures(mapper, 1);
        data.model.put("transcript", TRANSCRIPT).put("protein", "ENSP00000494175.1");
        data.model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", sha(CDS)).put("cdsSha256", sha(CDS));
        String coding = wrongHgvs ? "7_9delinsGCTGAG" : "7_9delinsGCTGAA";
        var annotation = data.annotation(coding, "Pro3delinsAlaGlu", "protein_altering_variant").put("source", "VEP");
        var run = ParsedCandidateFixture.run(temporary.resolve("run"), "a5-complex-selected-synthetic", GenomeAssembly.GRCh38,
                ParsedCandidateFixture.vcf("chr11\t105\t.\tACCT\tAGCTGAA\t.\tPASS\t.\tGT\t0/1"),
                ParsedCandidateFixture.tsv("1\t11\t105\tACCT\tAGCTGAA\tHBB\t3043\tAR\t0.8"));
        var original = run.execution().getInterpretations().get(0);
        original.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        var before = mapper.valueToTree(original);
        var normalizations = new AtomicInteger();
        VcfNormalizer normalized = (input, directory, assembly) -> {
            normalizations.incrementAndGet();
            var output = Files.writeString(directory.resolve("synthetic-normalized.vcf"),
                    "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr11\t106\tVARVIS_SELECTED_ALT\tCCT\tGCTGAA\t.\tPASS\t.\n");
            var manifest = new NormalizationRunManifest("PASSED", "GRCh38", "SYNTHETIC",
                    nativeInput.properties().getNormalization().referenceSha256For(GenomeAssembly.GRCh38), "bcftools", "SYNTHETIC", "SYNTHETIC",
                    false, 1, 1, 0, 1, 0, 1, "2026-10-10T00:00:00Z", "2026-10-10T00:00:01Z", null, null, "SYNTHETIC");
            return new NormalizationResult(output, true, "SYNTHETIC", List.of(), manifest);
        };
        ReflectionTestUtils.setField(requireNonNull(nativeInput.producer()), "selectedNormalizer", new SelectedAlleleNormalizer(mapper, normalized));
        var mapping = new ExplicitCaseInputCoordinatorTest(); mapping.temporary = temporary; mapping.setup();
        var provenance = new HypothesisProvenance("SYNTHETIC_CALLER", "1.0.0", "synthetic/a5-complex-selected");
        var choice = mapping.choices.path("fixedChoices").get(0);
        var tx = new TranscriptSelection("ENST00000647020", 1, choice.path("selectionPolicy").asText(), "VEP", "116.0",
                provenance, "Invented CDS/VEP sequence correspondence; no scientific adoption");
        var hypotheses = new ArrayList<HypothesisInput>();
        for (String hypothesis : List.of("context-one", "context-two")) hypotheses.add(new HypothesisInput(
                new PatientDiseaseHypothesis(original.getCandidateId(), hypothesis, "HGNC:4827", "MONDO:0013517", "AR", provenance), "UNKNOWN", tx));
        var declaration = new CaseDeclaration(run.job().getId(), run.execution().getInputSource(), "synthetic-proband", "SYNTHETIC",
                new PatientDeclaration("patient-context-a5-complex-selected", "PROBAND", "UNKNOWN", null, provenance), hypotheses);
        var coordinator = new ExplicitCaseInputCoordinator(mapper, mapping.resolver, nativeInput.producer(), mapping.sources);
        var blockers = sourceBlocked ? Map.of(original.getCandidateId(), new ExplicitEvidenceContextAssembler.Blocker("CONTEXT_NOT_READY", "SYNTHETIC_SOURCE_CHANGED", "source"))
                : Map.<String, ExplicitEvidenceContextAssembler.Blocker>of();
        var selected = coordinator.prepareSelected(run.job(), run.conversion(), run.execution(),
                new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES), run.execution().getInterpretations(),
                1, List.of(declaration), blockers);
        return new Prepared(selected, original, before, data.model, normalizations, new AtomicInteger(),
                nativeInput.properties().getNormalization().referenceSha256For(GenomeAssembly.GRCh38));
    }

    private Runtimes runtimes(Prepared p, boolean reviewedCapability) throws Exception {
        Path materialRoot = temporary.resolve("materials");
        CodingTranscriptMaterialSupply.ReferenceReader reader = (contig, start, length) -> {
            assertEquals("11", contig); p.referenceReads().addAndGet(length);
            return new CodingTranscriptMaterialSupply.ReferenceSpan(CDS.substring(start - 100, start - 100 + length),
                    mapper.createObjectNode().put("expectedSha256", p.referenceSha()).put("dataClass", "SYNTHETIC"));
        };
        // Reuse only the established fake-source resource writer. The evaluated runtime below
        // receives a fresh real factory supply, never the support helper's material or judgment.
        GeneralPm4TestSupport.create(mapper, materialRoot, p.model(), reader, false, false, false);
        String manifestSha = sha(Files.readAllBytes(materialRoot.resolve("manifest.json")));
        var supply = reviewedCapability ? ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, materialRoot, manifestSha, reader, null)
                : new CodingTranscriptMaterialSupply(mapper, materialRoot, manifestSha, reader);
        Path uniprotRoot = materialRoot.resolve("uniprot");
        var uniprot = new UniProtProteinRegionSupply(mapper, uniprotRoot, sha(Files.readAllBytes(uniprotRoot.resolve("manifest.json"))));
        var combined = new CombinedProteinRegionSupply(mapper, materialRoot.resolve("combined.json"), sha(Files.readAllBytes(materialRoot.resolve("combined.json"))));
        var guard = PredictionSpecializationGuard.load(mapper, materialRoot.resolve("catalog.json"), sha(Files.readAllBytes(materialRoot.resolve("catalog.json"))));
        var pm4 = new GeneralPm4Runtime(mapper, supply, uniprot, combined, new Pm4ProteinRegionAssessment(mapper), guard);
        var bp3 = new GeneralBp3Runtime(mapper, pm4.sharedFactSupply(), new Bp3ProteinRegionAssessment(mapper), guard);
        return new Runtimes(pm4, bp3, supply);
    }

    private void artifact(String scenario, Prepared prepared, JsonNode results) throws Exception {
        String directory = System.getProperty("a5.round4.artifacts");
        if (directory == null) return;
        Path output = Files.createDirectories(Path.of(directory).resolve(scenario));
        var document = mapper.createObjectNode().put("schemaVersion", "a5-round4-selected-complex-consumer-engineering-v1")
                .put("dataClass", "SYNTHETIC").put("normalSelectedPreparationExecuted", true)
                .put("normalHttpOrStoreExecuted", false).put("scientificQualificationGranted", false)
                .put("normalizationTransport", "SYNTHETIC_BCFTOOLS_SHAPED_OUTPUT")
                .put("normalizationCalls", prepared.normalizations().get()).put("referenceBasesRead", prepared.referenceReads().get());
        document.set("formalCandidate", prepared.before());
        document.set("publicSummaries", mapper.createArrayNode());
        for (var result : results) document.withArray("publicSummaries").add(result.path("publicSummary"));
        document.set("actualConsumerResults", results);
        Files.write(output.resolve("actual-selected-consumer.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
    }

    private static String sha(String value) throws Exception { return sha(value.getBytes(StandardCharsets.US_ASCII)); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
