package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog;
import org.nbscn.varvis.variantanalysis.provider.Bs1Runtime;
import org.nbscn.varvis.variantanalysis.provider.CodingTranscriptMaterialSupply;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.nbscn.varvis.variantanalysis.provider.SelectedPredictionRuntime;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual CURRENT central factory, selected confirmation/controller and file H2.
 * Complete real CSpec conditions are paired with explicitly SYNTHETIC sequence,
 * candidate and publication transport. No biological RAS transcript, VEP execution,
 * LoF mechanism qualification, clinical approval or new BP1 MET is asserted.
 * Run with t05.ras.raw pointing to the fixed raw-015-contents-35.json capture.
 */
class T05Bp1TruncatingBusinessTest {
    private static final Path LOCAL = Path.of("D:/LSD/Project/Code/local");
    private static final String RAW_SHA = "0c112a41986f1e0b85491717e1d1b474e38cf639e68c3579a21294d5ad164d6c";
    private static final String PREPARED_SHA = "3aebb1a815a53f966f33f73fdc4c727f69ac9305bf563d776715d965b952d84b";
    private static final String BS1_SHA = "8a69e91de4bba06324f34ac7c97203b532bda90bc82a1138657347cc5e3ff892";
    private static final String PP2_SHA = "d633875c63d21bb736fba881931b00b124b5c119fd10d277f3482d7485dbfd30";
    private static final String BLOCKED = "BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED";
    private static final String PREFIX = Pm4Bp3ComplexPublicFixture.PREFIX;
    private static final List<Scope> SCOPES = List.of(
            new Scope("PTPN11", "5781", "HGNC:9644", "NM_002834.5", "NP_999901.1", "HBB", "GN043@2.3", 0),
            new Scope("MAP2K1", "5604", "HGNC:6840", "NM_002755.4", "NP_999902.1", "GJB2", "GN045@2.3", 2));
    private record Scope(String gene, String entrez, String hgnc, String transcript, String syntheticProtein,
                         String transportGene, String specification, int rawIndex) { }
    @TempDir Path temporary;
    private AcmgBusinessIntegrationTest base;
    private Pm4Bp3ComplexPublicFixture transport;
    private final Map<String, TranscriptConsequence> annotations = new LinkedHashMap<>();
    private Path rawFile, preparedFile, bs1File, pp2File, usedPreparedFile;
    private String usedPreparedSha = PREPARED_SHA;
    private boolean syntheticNoLof, structuredSourceEnabled;
    private final boolean current18 = Boolean.getBoolean("t05.current18");
    private boolean forbidNewExecution, nonsense;
    private Ba1BusinessIntegrationTest ba1Transport;
    private List<String> selectedCriteria = List.of();
    private CodingTranscriptMaterialSupply observedPublishedMaterial;
    private static final String PURE_MISSENSE = "BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE";
    private static final String KNOWN_LOF = "BP1_GENE_HAS_ESTABLISHED_LOF_DISEASE_CORRELATION";
    private static final String LOF_UNKNOWN = "BP1_NO_ESTABLISHED_LOF_DISEASE_CORRELATION_NOT_VERIFIED";

    @AfterEach void close() { if (base != null) base.closeDatabase(); }

    @ParameterizedTest @ValueSource(strings = {"BP1", "BP1_AND_PP2", "ALL_CONFIGURED"})
    void pureMissenseIsSourceInapplicableThroughCurrentSelectionSaveReadAndReuse(String selection) throws Exception {
        var saved = runScenario(selection, false);
        if ("BP1".equals(selection)) assertEquals("COMPLETED", saved.path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"BP1", "BP1_AND_PP2", "ALL_CONFIGURED"})
    void codingNonsenseChecksKnownLofAndMissingQualificationWithoutAwardingBp1(String selection) throws Exception {
        runScenario(selection, true);
    }

    @Test void bp1OnlyKeepsUnknownWhenConfiguredStructuredSourceHasNoExactScope() throws Exception {
        structuredSourceEnabled = true;
        runScenario("BP1", true);
    }

    @ParameterizedTest @ValueSource(strings = {"BP1", "BP1_AND_PP2"})
    void historicalProjectTemplateCannotAcquireScientificAuthorityInNormalSavedBusiness(String selection) throws Exception {
        syntheticNoLof = true;
        runScenario(selection, true);
    }

    private void configureSources() {
        String suppliedRaw = System.getProperty("t05.ras.raw");
        Assumptions.assumeTrue(suppliedRaw != null, "Requires the fixed cached real CSpec raw capture");
        rawFile = Path.of(suppliedRaw);
        preparedFile = configuredPath("t05.bp1.prepared", "development/handoffs/acmg-remaining-20261104/T05/prepared-bp1-subtypes-02/compiled.json");
        bs1File = configuredPath("t05.bp1.relationships", "resources/acmg/bs1-af-intervals-20261009/bundle.json");
        pp2File = configuredPath("t05.bp1.pp2", "runtime/bp7-evidence-workbench/materials/fourteen-v1/pp2-knowledge.json");
    }

    private ObjectNode runScenario(String selection, boolean codingNonsense) throws Exception {
        configureSources(); nonsense = codingNonsense; prepare();
        String scenario = (syntheticNoLof ? "SYNTHETIC_NO_LOF_" : "") + (nonsense ? "NONSENSE_" : "MISSENSE_") + selection;
        ObjectNode request = confirmed(selection);
        artifact(scenario, "fixed-input-and-independent-expectations", expectations(selection));
        var rankingBefore = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var saved = base.postRequest(request);
        assertCurrentResult(saved, selection);
        assertEquals(rankingBefore, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)));
        assertEquals(0, transport.configurationCalls().path("actualStubCount").asInt(-1));
        assertFalse(transport.configurationCalls().path("coreFactoryOverridden").asBoolean(true));
        var materialRoles = assertMaterialSupplyRoles();
        artifact(scenario, "material-supply-role-observation", materialRoles);
        var run = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        var privateBatch = base.mapper.readTree(run.privateJson()).path("localBatch");
        assertCurrentPublicBindings(saved, privateBatch);
        assertEquals(2, privateBatch.at("/modules/bp1/evaluatorCalls").asInt(-1));
        assertEquals(2, privateBatch.at("/modules/bp1/results").size());
        for (int i = 0; i < SCOPES.size(); i++) {
            var result = privateBatch.at("/modules/bp1/results/" + i);
            assertEquals("READY", result.at("/sequenceMaterial/status").asText(), result::toPrettyString);
            assertEquals("BLOCKED", result.at("/publishedKnowledge/status").asText());
            assertEquals(SCOPES.get(i).specification(), result.at("/publishedKnowledge/record/specificationKey").asText());
            assertEquals(expectedReason(i), result.path("reasonCode").asText());
            assertEquals(expectedConditionStatus(i), result.at("/truncatingConditionCheck/status").asText());
            if (!nonsense) assertEquals("NOT_APPLICABLE", result.at("/evaluation/criterionApplicability/status").asText());
            assertFalse(result.has("rawSpliceExecution")); assertFalse(result.has("rawSpliceResult"));
        }
        if ("BP1".equals(selection)) assertFalse(privateBatch.path("modules").has("pp2"));
        else if (!nonsense) assertEquals(2, privateBatch.at("/modules/pp2/evaluatorCalls").asInt(-1));
        else assertEquals(0, privateBatch.at("/modules/pp2/results").size(), "The old PP2 missense exclusion remains independent");
        assertFalse(Files.exists(temporary.resolve("transport/working/native-reference-splice")));
        artifact(scenario, "expected-public", saved);
        artifact(scenario, "expected-run", base.mapper.valueToTree(run));
        artifact(scenario, "expected-private", base.mapper.readTree(run.privateJson()));
        var calls = scientificCalls(); int coordinators = base.invocations.get();
        assertTrue(calls.path("nativeSourceProductions").asLong() > 0, "The no-reexecution check observes the real source production path");
        assertEquals(saved, fullGet(run.id())); assertEquals(coordinators, base.invocations.get(), "History never constructs a coordinator");
        assertEquals(saved, base.postRequest(request));
        assertEquals(calls, scientificCalls());
        assertEquals(coordinators + 1, base.invocations.get(), "Repeat validates the recommendations before looking up the saved request");
        base.closeDatabase(); base.recreateRepositories(); forbidNewExecution = true; install();
        int recreatedCoordinators = base.invocations.get();
        assertEquals(saved, fullGet(run.id()));
        assertEquals(recreatedCoordinators, base.invocations.get());
        var history = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs", base.analysisId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertTrue(history.isArray()); assertEquals(1, history.size());
        assertEquals(run.id(), history.path(0).path("runId").asText());
        assertEquals(base.mapper.valueToTree(base.service.list(base.analysisId)), history);
        forbidNewExecution = false;
        assertEquals(saved, base.postRequest(request));
        assertEquals(run, base.store.run(base.analysisId, run.id()).orElseThrow(), "Every stored Run field is unchanged");
        assertEquals(calls, scientificCalls()); assertEquals(recreatedCoordinators + 1, base.invocations.get());
        assertEquals(1, base.store.runs(base.analysisId).size());
        assertEquals(materialRoles, assertMaterialSupplyRoles(), "Saved reads and reuse retain the actual material owners");
        verifyOriginalPins();
        return saved;
    }

    /** Root/attempt expectations come from the real saved execution and prepared wrapper,
     * not from the public facts whose association is being checked. */
    private void assertCurrentPublicBindings(JsonNode saved, JsonNode privateBatch) {
        var projected = LocalEvidenceBatchRuntime.publicView(base.mapper, privateBatch);
        for (int index = 0; index < SCOPES.size(); index++) {
            JsonNode actual = privateBatch.at("/modules/bp1/results/" + index), wrapper = privateBatch.at("/results/" + index);
            JsonNode entry = null;
            for (var value : projected.at("/results/" + index + "/localBatch/criteria"))
                if ("ACMG_AMP_2015:BP1".equals(value.path("criterionId").asText())) entry = value;
            assertNotNull(entry); JsonNode proof = entry.at("/details/bp1Facts/currentBinding");
            assertEquals("bp1-current-module-binding-v1", proof.path("schemaVersion").asText(), entry::toPrettyString);
            assertEquals(saved.path("executionSourceSha256"), proof.path("executionSourceSha256"));
            assertEquals(wrapper.path("candidateId"), proof.path("candidateId")); assertEquals(wrapper.path("hypothesisId"), proof.path("hypothesisId"));
            assertEquals(wrapper.at("/context/contextId"), proof.path("originalContextId"));
            assertEquals(actual.path("contextId"), proof.path("evaluationContextId"));
            assertEquals(actual.at("/evaluation/evaluationResult/status"), proof.path("status"));
            assertEquals(actual.at("/evaluation/appliedStrength"), proof.path("appliedStrength"));
            assertEquals(actual.at("/evaluation/evaluationResult/reasonCode"), proof.path("reasonCode"));
            assertEquals(actual.at("/publishedKnowledge/record/clauseSha256"), proof.at("/observedSourcePins/clauseSha256"));
            assertEquals(actual.at("/sequenceMaterial/modelCanonicalSha256"), proof.at("/observedSourcePins/modelCanonicalSha256"));
            assertEquals("TRANSPORT_JSON_VALUE_C14N_V1", proof.path("auditHashMethod").asText());
            assertNotEquals(entry.at("/details/originalResultSha256").asText(), proof.path("actualModuleSha256").asText(), "Criterion hash does not identify a whole module");
            assertFalse(proof.toString().contains("cdsSequence"));
        }
    }

    private void prepare() throws Exception {
        base = new AcmgBusinessIntegrationTest() {
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() {
                // Publication transport only: no factory/evaluator/material qualification is stubbed.
                var publication = mock(VepTranscriptAnnotationEnrichmentService.class);
                when(publication.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
                    List<VariantInterpretation> candidates = call.getArgument(0);
                    var values = new LinkedHashMap<String, List<TranscriptConsequence>>();
                    for (var candidate : candidates) values.put(candidate.getVariantId(),
                            List.of(annotations.get(candidate.getExomiserCandidateFeatures().getGeneSymbol())));
                    return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(values,
                            VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                });
                return publication;
            }
        };
        base.temporary = temporary; base.setup(); verifyOriginalPins(); verifyCompleteSources();
        transport = new Pm4Bp3ComplexPublicFixture(base.mapper, temporary.resolve("transport"), temporary.resolve("synthetic-reference"));
        transport.applyNormalizationTo(base.properties);
        base.fixture.sources.add(transport.referenceLedger());
        Path bindings = configuredPath("t05.bp1.binding-directory", "resources/acmg-context/e4-a1a-2026-09-05");
        base.fixture.resolver = PatientDiseaseContextBindingResolver.load(base.mapper, bindings.resolve("manifest.json"),
                bindings.resolve("mane-crosswalk.json"), bindings.resolve("clingen-catalog.json"))
                .withBs1Knowledge(base.mapper, Bs1KnowledgeCatalog.load(bs1File, BS1_SHA));
        base.producer = spy(transport.producer(base.fixture.sources));
        writeSyntheticModels(); configurePublished();
        if (current18) configureCurrentEighteen();
        String position = nonsense ? "115" : "107", reference = nonsense ? "A" : "C";
        base.variantTsvOverride = tsv("1\t11\t" + position + "\t" + reference + "\tT\tPTPN11\t5781\tAD\t0.8",
                "2\t13\t" + position + "\t" + reference + "\tT\tMAP2K1\t5604\tAD\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t" + position + "\t.\t" + reference + "\tT\t.\tPASS\t.\tGT\t0/1",
                "chr13\t" + position + "\t.\t" + reference + "\tT\t.\tPASS\t.\tGT\t0/1"));
        install();
    }

    private void writeSyntheticModels() throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("synthetic-t05-models"));
        var manifest = base.mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1")
                .put("dataClass", "SYNTHETIC").put("biologicalRasTranscriptAsserted", false);
        var entries = manifest.putArray("entries");
        for (var scope : SCOPES) {
            var model = transport.model(scope.transportGene());
            model.put("modelId", "SYNTHETIC_T05_SOURCE_ROUTING_" + scope.gene()).put("geneSymbol", scope.gene())
                    .put("hgncId", scope.hgnc()).put("transcript", scope.transcript()).put("protein", scope.syntheticProtein())
                    .put("dataClass", "SYNTHETIC");
            model.putArray("sourceRecords").addObject().put("sourceId", "SYNTHETIC_T05_SEQUENCE_TRANSPORT")
                    .put("version", "1").put("url", "https://example.invalid/t05-synthetic-only")
                    .put("sha256", sha(Pm4Bp3ComplexPublicFixture.FULL_SPLICED.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            Path file = directory.resolve(scope.transcript() + ".model.json");
            byte[] bytes = base.mapper.writeValueAsBytes(model); Files.write(file, bytes);
            entries.addObject().put("transcript", scope.transcript()).put("file", file.getFileName().toString())
                    .put("fileBytes", bytes.length).put("fileSha256", sha(bytes))
                    .put("canonicalSha256", new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(model))
                    .put("modelId", model.path("modelId").asText()).put("modelVersion", "1");
            var annotation = base.mapper.createObjectNode().put("geneSymbol", scope.gene()).put("entrezGeneId", scope.entrez())
                    .put("transcriptAccession", scope.transcript()).put("hgvsCoding", scope.transcript() + (nonsense ? ":c.16A>T" : ":c.8C>T"))
                    .put("hgvsProtein", scope.syntheticProtein() + (nonsense ? ":p.Lys6Ter" : ":p.Pro3Leu"))
                    .put("consequence", nonsense ? "stop_gained" : "missense_variant")
                    .put("proteinPosition", nonsense ? "6" : "3").put("aminoAcids", nonsense ? "K/*" : "P/L")
                    .put("codons", nonsense ? "AAA/TAA" : "CCT/CTT").put("exon", "1/1")
                    .put("maneSelect", true).put("source", "VEP").put("status", "COMPLETED").put("version", "116.0")
                    .put("timestamp", Instant.parse("2026-11-04T00:00:00Z").toString());
            annotations.put(scope.gene(), base.mapper.treeToValue(annotation, TranscriptConsequence.class));
        }
        Path file = directory.resolve("manifest.json"); Files.write(file, base.mapper.writeValueAsBytes(manifest));
        transport.env().withProperty(PREFIX + "transcript-material-directory", java.util.Objects.requireNonNull(directory.toString()))
                .withProperty(PREFIX + "transcript-material-manifest-sha256", java.util.Objects.requireNonNull(sha(Files.readAllBytes(file))));
    }

    private void configurePublished() throws Exception {
        usedPreparedFile = preparedFile;
        if (syntheticNoLof) prepareSyntheticReview();
        for (String code : List.of("pm1", "pp2", "bp1")) transport.env().withProperty(PREFIX + "local-batch-" + code + "-enabled", "true");
        transport.env().withProperty(PREFIX + "unified-local-workflow-enabled", "true")
                .withProperty(PREFIX + "local-batch-workflow-enabled", "true")
                .withProperty(PREFIX + "bs1-knowledge-file", java.util.Objects.requireNonNull(bs1File.toString())).withProperty(PREFIX + "bs1-knowledge-sha256", BS1_SHA)
                .withProperty(PREFIX + "pm1-published-knowledge-file", java.util.Objects.requireNonNull(configuredPath("t05.bp1.pm1", "runtime/bp7-evidence-workbench/materials/fourteen-v1/pm1-knowledge.json").toString()))
                .withProperty(PREFIX + "pm1-published-knowledge-sha256", "c8d0ac10dcce4885626567b1f0cde17c5a2a17287e5407ee354a552ac92ede6f")
                .withProperty(PREFIX + "local-batch-pp2-germline-declared", "true")
                .withProperty(PREFIX + "bp1-published-knowledge-file", java.util.Objects.requireNonNull(usedPreparedFile.toString())).withProperty(PREFIX + "bp1-published-knowledge-sha256", java.util.Objects.requireNonNull(usedPreparedSha))
                .withProperty(PREFIX + "pp2-published-knowledge-file", java.util.Objects.requireNonNull(pp2File.toString())).withProperty(PREFIX + "pp2-published-knowledge-sha256", PP2_SHA)
                .withProperty(PREFIX + "native-splice-python-file", java.util.Objects.requireNonNull(configuredPath("t05.bp1.native-python", "tools/VarvisSpliceAIRuntime/native-windows-v1/venv/Scripts/python.exe").toString()))
                .withProperty(PREFIX + "native-splice-adapter-file", java.util.Objects.requireNonNull(System.getProperty("t05.bp1.native-adapter", "D:/LSD/Project/Code/scripts/e4_b3/run_spliceai_native_business.py")))
                .withProperty(PREFIX + "native-splice-binding-file", java.util.Objects.requireNonNull(configuredPath("t05.bp1.native-binding", "development/handoffs/PP3-BP4-selected-splice-20261012/dev-01/runtime-binding.json").toString()))
                .withProperty(PREFIX + "native-splice-binding-sha256", "9880ad744c3d8017028c7439adc314d554405703d5a2beed3e51490ec468e1bf")
                .withProperty(PREFIX + "native-splice-reference-fasta", java.util.Objects.requireNonNull(transport.referenceFile().toString()));
        if (structuredSourceEnabled) configureStructuredSource();
    }

    private void configureStructuredSource() {
        // Neither synthetic RAS scope matches the retained public knowledge.
        Path materialRoot = LOCAL.resolve("runtime/bp7-evidence-workbench/materials");
        Path qualified = materialRoot.resolve("pvs1-source-qualified-20261016");
        transport.env().withProperty(PREFIX + "pvs1-selected-knowledge-enabled", "true")
                .withProperty(PREFIX + "pvs1-local-material-supply-enabled", "true")
                .withProperty(PREFIX + "pvs1-bundle-file", java.util.Objects.requireNonNull(qualified.resolve("bundle.json").toString()))
                .withProperty(PREFIX + "pvs1-bundle-file-sha256", "dcd894558eadd5da854cdb5e00edb15503e6e0b14041d16f254b14f1087d30fb")
                .withProperty(PREFIX + "pvs1-bundle-sha256", "cc6453ec04ec892c5f57dd6766a8d2de46951f653b66d782ee6f3f86e1a21024")
                .withProperty(PREFIX + "pvs1-knowledge-adoption-file", java.util.Objects.requireNonNull(qualified.resolve("knowledge-adoption.json").toString()))
                .withProperty(PREFIX + "pvs1-knowledge-adoption-sha256", "5c8273c94f5fa5ea16ebc4f6722bee0d3c4cb35f296e81455ad6ce2dc46a84d0")
                .withProperty(PREFIX + "pvs1-knowledge-source-file", java.util.Objects.requireNonNull(materialRoot.resolve("eleven-v1/g2p-mechanism-candidates.json").toString()))
                .withProperty(PREFIX + "pvs1-knowledge-source-sha256", "6399590f4f5fc7d3bd33d9d183633fdf69a3f5bc03582d155e7af0bc605adf67");
    }

    /** Extend the same actual configuration factory; empty companion inventories grant no observations. */
    private void configureCurrentEighteen() throws Exception {
        Path python = Path.of(java.util.Objects.requireNonNull(System.getProperty("t05.current18.python")));
        Path scripts = Path.of(java.util.Objects.requireNonNull(System.getProperty("t05.current18.scripts")));
        Path companionRoot = temporary.resolve("current18");
        Path workflow = org.nbscn.varvis.variantanalysis.provider.UnifiedEighteenCompanionFixtures
                .prepareEmptyLiteratureWorkflow(base.mapper, companionRoot, scripts, python);
        Path denovoRoot = companionRoot.resolve("denovo");
        new org.nbscn.varvis.variantanalysis.provider.DeNovoBatchFixture(denovoRoot);
        Path denovoManifest = denovoRoot.resolve("manifest.json");
        transport.env().withProperty("varvis.literature-workflow-config", java.util.Objects.requireNonNull(workflow.toString()))
                .withProperty(PREFIX + "local-batch-pm3-literature-enabled", "true")
                .withProperty(PREFIX + "local-batch-pp1-literature-enabled", "true")
                .withProperty(PREFIX + "local-batch-denovo-enabled", "true")
                .withProperty(PREFIX + "local-batch-denovo-use", "SYNTHETIC_VALIDATION")
                .withProperty(PREFIX + "local-batch-denovo-material-file", java.util.Objects.requireNonNull(denovoManifest.toString()))
                .withProperty(PREFIX + "local-batch-denovo-material-sha256", java.util.Objects.requireNonNull(sha(Files.readAllBytes(denovoManifest))));
        ba1Transport = new Ba1BusinessIntegrationTest(); ba1Transport.base = base; ba1Transport.temporary = temporary;
        ba1Transport.am = new org.nbscn.varvis.variantanalysis.provider.BusinessAlphaMissenseFixture();
        var catalog = ba1Transport.syntheticCatalog("SYNTHETIC_T05_CURRENT18_EMPTY_SPECIFICATION_INVENTORY");
        var mappings = catalog.withArray("geneMappings");
        for (var scope : SCOPES) mappings.addObject().put("hgncId", scope.hgnc()).putArray("geneSymbols").add(scope.gene());
        byte[] mappingBytes = base.mapper.writeValueAsBytes(mappings);
        catalog.putObject("geneMappingSource").put("status", "COMPLETE").put("rawSha256", sha(mappingBytes))
                .put("url", "https://example.test/SYNTHETIC/t05/gene-mapping").put("recordCount", mappings.size());
        Path catalogFile = temporary.resolve("synthetic-ba1-catalog.json"); Files.write(catalogFile, base.mapper.writeValueAsBytes(catalog));
        var capturedCatalog = base.mapper.readTree(Files.readAllBytes(catalogFile));
        if (!(capturedCatalog instanceof ObjectNode object)) throw new IllegalArgumentException("TEST_CATALOG_OBJECT_REQUIRED");
        ba1Transport.configureCatalog(new org.nbscn.varvis.variantanalysis.provider.Ba1SpecificationCatalog(base.mapper, object));
    }

    /** New raw source is explicitly SYNTHETIC. The admitted real BP1 rules/notes remain byte-for-byte unchanged. */
    private void prepareSyntheticReview() throws Exception {
        var document = (ObjectNode) base.mapper.readTree(Files.readAllBytes(preparedFile));
        ObjectNode original = null;
        for (var spec : document.path("specifications"))
            if ("GN045@2.3".equals(spec.path("specificationKey").asText())) original = ((ObjectNode) spec.path("original")).deepCopy();
        assertNotNull(original);
        original.put("entId", "GN999");
        var today = java.time.LocalDate.now(java.time.Clock.systemUTC());
        original.withObject("/entContent").put("version", "1.0.0")
                .put("description", "SYNTHETIC_NO_LOF_REVIEW_ENGINEERING_CONTROL_NOT_REAL_SCIENTIFIC_EVIDENCE")
                .put("notes", "No established germline loss-of-function disease association is known for MAP2K1 (HGNC:6840) across all human diseases. Expert review date: "
                        + today.minusDays(1) + ". Valid through: " + today.plusDays(7) + ".");
        original.withObject("/entContent").putArray("states").addObject().put("current", true).put("name", "Released")
                .putObject("event").put("name", "cspec-released").put("timeStamp", today + "T00:00:00Z");
        original.withObject("/ld").remove("File");
        byte[] raw = base.mapper.writeValueAsBytes(base.mapper.createObjectNode().set("data", base.mapper.createArrayNode().add(original)));
        String rawSha = sha(raw);
        var spec = document.withArray("specifications").addObject().put("specificationKey", "GN999@1.0.0")
                .put("entitySha256", new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(original));
        spec.set("original", original);
        spec.putObject("source").put("rawSha256", rawSha).put("jsonPointer", "/data/0")
                .put("sourceUrl", "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/GN999");
        document.putArray("bp1LofAssociationRawSources").addObject().put("rawSha256", rawSha)
                .put("rawJsonUtf8", new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        usedPreparedFile = temporary.resolve("synthetic-no-lof-compiled.json");
        byte[] compiled = base.mapper.writeValueAsBytes(document); Files.write(usedPreparedFile, compiled); usedPreparedSha = sha(compiled);
        artifact("SYNTHETIC_NO_LOF_SOURCE", "synthetic-raw-original", base.mapper.readTree(raw));
        artifact("SYNTHETIC_NO_LOF_SOURCE", "compiled-accepted-artifact", document);
    }

    private void install() {
        java.util.function.Supplier<ExplicitCaseInputCoordinator> coordinator = () -> {
            assertFalse(forbidNewExecution, "History and exact repeat cannot rebuild the source coordinator");
            base.invocations.incrementAndGet();
            return new ExplicitCaseInputCoordinator(base.mapper, base.fixture.resolver, base.producer, base.fixture.sources);
        };
        if (ba1Transport == null) base.recreateService(coordinator);
        else base.service = new AcmgBusinessService(base.mapper, base.jobs, base.store, base.sources, coordinator,
                () -> new AcmgBusinessService.Ba1Runtime(base.producer, ba1Transport.ba1), AcmgRecommendations.unavailable(base.mapper),
                () -> { throw new IllegalArgumentException("PM2_RUNTIME_NOT_CONFIGURED"); },
                () -> { throw new IllegalArgumentException("PVS1_RUNTIME_NOT_CONFIGURED"); },
                () -> { throw new IllegalArgumentException("PS1_PM5_RUNTIME_NOT_CONFIGURED"); }, null,
                () -> new org.nbscn.varvis.variantanalysis.provider.Hnf1aFunctionalRuntime(base.mapper), null, null, null, null, ba1Transport.ba1Binding);
        var population = AcmgBusinessService.formalPopulation(base.service,
                () -> Bs1Runtime.localExomes(base.mapper, new VariantAnalysisProperties(), false));
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population, transport::centralRuntime, temporary.resolve("effect-reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    private ObjectNode confirmed(String selection) throws Exception {
        var options = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId).param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var request = base.request.deepCopy().put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        assertTrue(options.at("/executionProfile/enabledCriteria").toString().contains("ACMG_AMP_2015:BP1"));
        var selected = request.putArray("selectedCriteria"); var codes = new ArrayList<String>();
        for (var code : options.at("/executionProfile/enabledCriteria"))
            if ("ALL_CONFIGURED".equals(selection) || "ACMG_AMP_2015:BP1".equals(code.asText())
                    || "BP1_AND_PP2".equals(selection) && "ACMG_AMP_2015:PP2".equals(code.asText())) {
                selected.add(code.asText()); codes.add(code.asText());
            }
        selectedCriteria = List.copyOf(codes);
        if ("ALL_CONFIGURED".equals(selection)) assertEquals(current18 ? 18 : 14, selectedCriteria.size(), "Selected scope comes from the actual configured factory");
        var hypotheses = request.putArray("hypotheses"); var references = request.putArray("recommendations");
        assertEquals(2, options.path("candidates").size());
        for (int i = 0; i < SCOPES.size(); i++) {
            var scope = SCOPES.get(i); var rec = options.at("/candidates/" + i + "/recommendation");
            JsonNode relationship = null, annotation = null;
            for (var row : rec.path("relationships")) if (scope.hgnc().equals(row.path("hgncId").asText())
                    && "MONDO:0021060".equals(row.path("mondoId").asText())) relationship = row;
            for (var row : rec.path("annotations")) if (scope.transcript().equals(row.path("versionedTranscriptId").asText())) annotation = row;
            assertNotNull(relationship, rec::toPrettyString); assertNotNull(annotation, rec::toPrettyString);
            var item = hypotheses.addObject().put("affectedStatus", "AFFECTED");
            item.putObject("hypothesis").put("candidateId", rec.path("candidateId").asText()).put("hypothesisId", rec.path("hypothesisId").asText())
                    .put("hgncId", scope.hgnc()).put("mondoId", "MONDO:0021060").set("moi", relationship.path("moi"));
            item.withObject("hypothesis").set("provenance", base.declaredSource("private/synthetic-t05/hypothesis"));
            int dot = scope.transcript().lastIndexOf('.');
            var tx = item.putObject("transcript").put("accession", scope.transcript().substring(0, dot))
                    .put("version", Integer.parseInt(scope.transcript().substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                    .put("selectionReason", "Explicit synthetic transport of the exact real CSpec scope");
            tx.set("annotationSource", annotation.path("annotationSource")); tx.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            tx.putObject("source").put("source", "EXPLICIT_SYNTHETIC_T05_SELECTION").put("version", "1")
                    .put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var reference = references.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) reference.set(field, rec.path(field));
            reference.set("stableRelationshipKey", relationship.path("stableRelationshipKey")); reference.set("annotationId", annotation.path("annotationId"));
        }
        return request;
    }

    private String expectedApplicationStatus(int index) { return !nonsense ? "NOT_EVALUATED" : index == 0 ? "NOT_MET" : "NOT_EVALUATED"; }
    private String expectedStrength(int index) { return "MET".equals(expectedApplicationStatus(index)) ? "SUPPORTING" : "NONE"; }
    private String expectedConditionStatus(int index) { return !nonsense ? "NOT_APPLICABLE" : expectedApplicationStatus(index); }
    private String expectedReason(int index) { return !nonsense ? PURE_MISSENSE : index == 0 ? KNOWN_LOF
            : syntheticNoLof ? "BP1_NO_LOF_LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW" : LOF_UNKNOWN; }

    private void assertCurrentResult(JsonNode saved, String selection) {
        assertEquals(base.mapper.valueToTree(selectedCriteria), saved.path("enabledCriteria"));
        for (int i = 0; i < SCOPES.size(); i++) {
            var criteria = saved.at("/candidates/" + i + "/attempts/0/localBatch/criteria");
            var localCodes = selectedCriteria.stream().filter(c -> !List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1").contains(c)).toList();
            assertEquals(localCodes.size(), criteria.size()); boolean bp1Seen = false;
            var seen = new java.util.HashSet<String>();
            for (var criterion : criteria) {
                String code = criterion.path("criterionId").asText(); assertTrue(seen.add(code));
                assertTrue(localCodes.contains(code)); assertFalse(criterion.path("reasonCode").asText().isBlank());
                if ("ACMG_AMP_2015:PP2".equals(code)) {
                    assertEquals(nonsense ? "NOT_EVALUATED" : "MET", criterion.path("status").asText(), criterion::toPrettyString);
                    assertEquals(nonsense ? "NONE" : "SUPPORTING", criterion.path("appliedStrength").asText());
                    assertEquals(nonsense ? "ROUTING_MISSENSE_REQUIRED" : "PP2_PUBLISHED_MECHANISM_AND_LOW_BENIGN_BACKGROUND", criterion.path("reasonCode").asText());
                    if (nonsense) assertEquals("NOT_APPLICABLE", criterion.at("/details/applicabilityRouting/status").asText());
                    continue;
                }
                if (!"ACMG_AMP_2015:BP1".equals(code)) continue;
                bp1Seen = true;
                // The criterion application contract keeps an inapplicable result as NOT_EVALUATED;
                // its completed, source-specific applicability is exposed independently below.
                assertEquals(expectedApplicationStatus(i), criterion.path("status").asText(), criterion::toPrettyString);
                assertEquals(expectedStrength(i), criterion.path("appliedStrength").asText()); assertEquals(expectedReason(i), criterion.path("reasonCode").asText());
                var facts = criterion.at("/details/bp1Facts");
                assertEquals("bp1-general-facts-v1", facts.path("schemaVersion").asText());
                assertEquals(SCOPES.get(i).specification(), facts.path("sourceSpecification").asText());
                assertEquals("BLOCKED", facts.path("knowledgeStatus").asText());
                assertEquals("UNSUPPORTED_CONDITIONS", facts.path("knowledgeDisposition").asText());
                var check = facts.path("truncatingConditionCheck");
                assertEquals(expectedConditionStatus(i), check.path("status").asText());
                assertEquals(expectedReason(i), check.path("reasonCode").asText()); assertEquals(expectedStrength(i), check.path("appliedStrength").asText());
                assertFalse(facts.path("clinicalValidationCompleted").asBoolean(true));
                if (nonsense) {
                    assertEquals("TRUNCATION_CANDIDATE", check.at("/sequenceConsequence/status").asText());
                    assertFalse(check.at("/available/functionalLossEstablishedBySequence").asBoolean(true));
                    assertFalse(check.at("/required/missingRecordMeansNoCorrelation").asBoolean(true));
                    assertEquals(i == 0 ? "CONTRAINDICATION_ESTABLISHED" : "UNKNOWN", check.at("/lofDiseaseCorrelation/status").asText());
                    if (i == 1 && !syntheticNoLof) assertTrue(check.path("missing").size() > 0);
                    if (i == 1 && syntheticNoLof) {
                        assertFalse(check.at("/noEstablishedLofQualification/absenceQualificationGranted").asBoolean(true));
                        assertEquals("UNKNOWN", check.at("/noEstablishedLofQualification/status").asText());
                        assertFalse(check.path("missing").isEmpty());
                    }
                } else assertEquals("VERIFIED_PURE_CODING_MISSENSE", check.at("/available/variantEffect").asText());
            }
            assertTrue(bp1Seen); assertEquals(new java.util.HashSet<>(localCodes), seen);
        }
    }

    @Test void producerJvmExitsBeforeIndependentJvmReadsTheFullNonsenseBusinessRecord() throws Exception {
        Assumptions.assumeTrue(System.getProperty("t05.ras.raw") != null, "Requires the fixed cached real CSpec raw capture");
        Path writerDirectory = Files.createDirectories(temporary.resolve("writer"));
        Path output = Files.createDirectories(temporary.resolve("process-proof"));
        Files.createDirectories(writerDirectory.resolve("temp"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ArrayList<String>(List.of(java, "-Djava.io.tmpdir=" + writerDirectory.resolve("temp").toAbsolutePath()));
        for (String key : System.getProperties().stringPropertyNames().stream().sorted().toList())
            if (key.startsWith("t05.") || List.of("a5.public.bcftools", "varvis.n01.bcftools").contains(key))
                command.add("-D" + key + "=" + System.getProperty(key));
        command.addAll(List.of("-cp", classpath, T05Bp1TruncatingBusinessTest.class.getName(),
                writerDirectory.toAbsolutePath().toString(), output.toAbsolutePath().toString()));
        Path module = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(module.resolve("src/test/resources"))) module = module.resolve("varvis-server");
        assertTrue(Files.isDirectory(module.resolve("src/test/resources")), "The writer requires the actual module fixture inputs");
        var runner = new SystemExternalProcessRunner();
        var writer = runner.run(command, module, Duration.ofSeconds(120));
        Files.writeString(output.resolve("writer.log"), writer.getOutput());
        assertTrue(writer.isSuccess(), writer.getOutput());
        // This line is reached only after the independent writer JVM has exited successfully.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var written = mapper.readTree(Files.readAllBytes(output.resolve("writer-result.json")));
        var reader = runner.run(List.of(java, "-cp", classpath, Pm4Bp3ComplexStoreReader.class.getName(),
                writerDirectory.toAbsolutePath().toString(), written.path("manifest").asText(),
                output.resolve("reader").toAbsolutePath().toString()), writerDirectory, Duration.ofSeconds(60));
        Files.writeString(output.resolve("reader.log"), reader.getOutput()); assertTrue(reader.isSuccess(), reader.getOutput());
        var receipt = mapper.readTree(Files.readAllBytes(output.resolve("reader/reader-receipt.json")));
        assertEquals("PASS", receipt.path("status").asText()); assertEquals(0, receipt.path("runtimeCalls").asInt(-1));
        assertTrue(receipt.path("storeBytesExact").asBoolean()); assertTrue(receipt.path("controllerJsonEqual").asBoolean());
        assertTrue(receipt.path("storedSourceFieldsEqual").asBoolean());
        Files.write(output.resolve("process-lifecycle.json"), mapper.writeValueAsBytes(mapper.createObjectNode()
                .put("producerJvmExitedBeforeReaderLaunch", true).put("producerSuccess", true).put("readerSuccess", true)
                .put("selection", current18 ? "ALL_CONFIGURED_CURRENT18" : "ALL_CONFIGURED_CURRENT14").put("referenceAndAnnotations", "SYNTHETIC")
                .put("scientificQualificationGranted", false).put("allCurrent18CriteriaCovered", current18)
                .put("readerReceipt", "reader/reader-receipt.json")));
        retainProcessProof(output, writerDirectory, mapper);
    }

    /** Keep actual process receipts before the fixture's isolated temporary database is removed. */
    private static void retainProcessProof(Path output, Path writerDirectory,
            com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        String configured = System.getProperty("t05.bp1.artifacts");
        if (configured == null) return;
        Path archive = Files.createDirectories(Path.of(configured).toAbsolutePath().normalize().resolve("STRICT_WRITER_READER"));
        Path source = output.toRealPath();
        if (archive.startsWith(source)) throw new IllegalArgumentException("Process archive must not recurse into its source");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = archive.resolve(source.relativize(file)).normalize();
                if (!target.startsWith(archive)) throw new IllegalArgumentException("Process archive path escaped");
                Files.createDirectories(target.getParent());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        var written = mapper.readTree(Files.readAllBytes(output.resolve("writer-result.json")));
        var manifest = mapper.readTree(Files.readAllBytes(Path.of(written.path("manifest").asText())));
        var retained = mapper.createObjectNode().put("schemaVersion", "t05-bp1-process-proof-retention-v1")
                .put("databaseArchived", false).put("largeSyntheticReferenceArchived", false)
                .put("originalManifestPathsRemainHistorical", true);
        var files = retained.putArray("files"); Path writer = writerDirectory.toRealPath();
        for (var original : manifest.path("originals")) {
            Path file = Path.of(original.path("path").asText()).toRealPath();
            if (!file.startsWith(writer)) continue; // Global read-only sources remain referenced by their original pins.
            Path target = archive.resolve("fixture-originals").resolve(writer.relativize(file)).normalize();
            if (!target.startsWith(archive)) throw new IllegalArgumentException("Original archive path escaped");
            byte[] raw = Files.readAllBytes(file);
            assertEquals(original.path("rawSha256").asText(), sha(raw));
            Files.createDirectories(target.getParent()); Files.write(target, raw);
            files.addObject().put("originalPath", file.toString()).put("archivedRelativePath", archive.relativize(target).toString())
                    .put("rawSha256", sha(raw)).put("bytes", raw.length);
        }
        Files.write(archive.resolve("retention.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(retained));
    }

    /** Actual public business writer. The parent waits for this JVM to exit before starting the reader. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Writer requires isolated database and output directories");
        Path directory = Path.of(args[0]), output = Path.of(args[1]);
        if (!directory.isAbsolute() || !output.isAbsolute()) throw new IllegalArgumentException("Absolute fixture paths required");
        Files.createDirectories(directory); Files.createDirectories(output);
        System.setProperty("t05.bp1.artifacts", output.toString());
        var writer = new T05Bp1TruncatingBusinessTest(); writer.temporary = directory;
        try {
            var saved = writer.runScenario("ALL_CONFIGURED", true);
            Path manifest = writer.readerBundle("NONSENSE_ALL_CONFIGURED", saved, output.resolve("bundle"));
            Files.write(output.resolve("writer-result.json"), writer.base.mapper.writeValueAsBytes(writer.base.mapper.createObjectNode()
                    .put("manifest", manifest.toString()).put("analysisId", writer.base.analysisId).put("runId", saved.path("runId").asText())));
        } finally { writer.close(); }
    }

    private Path readerBundle(String scenario, ObjectNode saved, Path directory) throws Exception {
        Files.createDirectories(directory);
        var run = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        Path runFile = directory.resolve("expected-run.json"), publicFile = directory.resolve("expected-public.json"), privateFile = directory.resolve("expected-private.json");
        Files.write(runFile, base.mapper.writeValueAsBytes(run));
        Files.writeString(publicFile, run.responseJson()); Files.writeString(privateFile, run.privateJson());
        var source = base.store.source(base.analysisId).orElseThrow();
        Path sourceFile = directory.resolve("expected-source.json"), sourceJson = directory.resolve("expected-source-content.json");
        Files.write(sourceFile, base.mapper.writeValueAsBytes(source)); Files.writeString(sourceJson, source.json());
        var manifest = base.mapper.createObjectNode().put("analysisId", base.analysisId)
                .put("expectedSourceFile", sourceFile.toAbsolutePath().toString()).put("expectedSourceJsonFile", sourceJson.toAbsolutePath().toString());
        manifest.putArray("runs").addObject().put("scenario", scenario).put("runId", run.id())
                .put("expectedRunFile", runFile.toAbsolutePath().toString()).put("expectedPublicFile", publicFile.toAbsolutePath().toString())
                .put("expectedPrivateFile", privateFile.toAbsolutePath().toString());
        var originals = manifest.putArray("originals");
        var paths = new java.util.LinkedHashSet<Path>(List.of(rawFile, preparedFile, bs1File, pp2File));
        if (usedPreparedFile != null) paths.add(usedPreparedFile);
        if (current18) paths.addAll(List.of(temporary.resolve("synthetic-ba1-catalog.json"), temporary.resolve("synthetic-tabix.cmd")));
        paths.addAll(transport.originalFiles());
        try (var files = Files.list(temporary.resolve("synthetic-t05-models"))) { files.filter(Files::isRegularFile).forEach(paths::add); }
        for (Path path : paths) {
            if (Files.size(path) > 64L * 1024 * 1024) continue; // The reference has a streamed creation/checksum receipt.
            originals.addObject().put("path", path.toAbsolutePath().toString()).put("rawSha256", sha(Files.readAllBytes(path)));
        }
        Path file = directory.resolve("reader-manifest.json"); Files.write(file, base.mapper.writeValueAsBytes(manifest)); return file.toAbsolutePath();
    }

    private void verifyCompleteSources() throws Exception {
        var raw = base.mapper.readTree(Files.readAllBytes(rawFile)); var prepared = base.mapper.readTree(Files.readAllBytes(preparedFile));
        for (var scope : SCOPES) {
            JsonNode specification = null, record = null;
            for (var row : prepared.path("specifications")) if (scope.specification().equals(row.path("specificationKey").asText())) specification = row;
            for (var row : prepared.path("records")) if (scope.specification().equals(row.path("specificationKey").asText())
                    && "MONDO:0021060".equals(row.at("/scope/mondoId").asText())) record = row;
            assertNotNull(specification); assertNotNull(record);
            assertEquals(raw.path("data").get(scope.rawIndex()), specification.path("original"), "Complete original source retained");
            assertEquals(RAW_SHA, specification.at("/source/rawSha256").asText());
            assertEquals("UNSUPPORTED_CONDITIONS", record.path("status").asText()); assertEquals(BLOCKED, record.path("reasonCode").asText());
            assertEquals("PUBLISHED_TRUNCATING_GOF_SUPPORTING_V1", record.at("/interpretation/type").asText());
        }
    }
    private void verifyOriginalPins() throws Exception {
        for (var pin : Map.of(rawFile, RAW_SHA, preparedFile, PREPARED_SHA, bs1File, BS1_SHA, pp2File, PP2_SHA).entrySet())
            assertEquals(pin.getValue(), sha(Files.readAllBytes(pin.getKey())), pin.getKey().toString());
        if (usedPreparedFile != null) assertEquals(usedPreparedSha, sha(Files.readAllBytes(usedPreparedFile)));
    }
    /** Observe existing spies and lambda captures only; never invoke a supplier or replace a factory. */
    private ObjectNode assertMaterialSupplyRoles() throws ReflectiveOperationException {
        var configurations = (List<?>) existingField(transport, "configurations");
        var batches = (List<?>) existingField(transport, "batches");
        Class<?> configurationType = Class.forName("org.nbscn.varvis.variantanalysis.config.LocalEvidenceBatchConfiguration");
        int publishedCalls = 0, predictionCalls = 0;
        var locations = base.mapper.createArrayNode();
        for (Object configuration : configurations) for (var invocation : mockingDetails(configuration).getInvocations()) {
            if (!"transcriptMaterials".equals(invocation.getMethod().getName())) continue;
            String location = invocation.getLocation().toString(), role = null;
            for (var method : configurationType.getDeclaredMethods()) {
                if (!method.getName().startsWith("lambda$create$") || !location.contains("." + method.getName() + "(")) continue;
                // Classify the real caller by its declared return type, not line numbers or lambda numbering.
                if (method.getReturnType() == CodingTranscriptMaterialSupply.class) role = "PUBLISHED_PM1_PP2_BP1";
                if (method.getReturnType() == SelectedPredictionRuntime.class) role = "SELECTED_PREDICTION_RUNTIME";
            }
            assertNotNull(role, "Unexpected material constructor caller: " + location);
            if ("PUBLISHED_PM1_PP2_BP1".equals(role)) publishedCalls++; else predictionCalls++;
            locations.addObject().put("owner", role).put("actualInvocationLocation", location);
        }
        assertEquals(1, publishedCalls, "One shared material initialization for the published PM1/PP2/BP1 group");
        boolean predictionSelected = selectedCriteria.stream().anyMatch(List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BP7")::contains);
        assertEquals(predictionSelected ? 1 : 0, predictionCalls, "The selected prediction runtime owns its independent material supply");
        assertEquals(publishedCalls + predictionCalls, transport.configurationCalls().path("transcriptMaterials").asInt(-1));
        var initializedSuppliers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        var initializedMaterials = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<CodingTranscriptMaterialSupply, Boolean>());
        for (Object batch : batches) {
            Object sharedSupplier = null; CodingTranscriptMaterialSupply[] sharedCache = null;
            for (String factory : List.of("pm1Factory", "pp2Factory", "bp1Factory")) {
                Object supplier = capturedPublishedMaterialSupplier(existingField(batch, factory));
                var cache = capturedMaterialCache(supplier);
                if (sharedSupplier == null) { sharedSupplier = supplier; sharedCache = cache; }
                else {
                    assertSame(sharedSupplier, supplier, "All three published factories must capture the same request-owned supplier");
                    assertSame(sharedCache, cache, "All three published factories must retain the same material slot");
                }
            }
            assertNotNull(sharedCache); assertEquals(1, sharedCache.length);
            if (sharedCache[0] != null) { initializedSuppliers.add(sharedSupplier); initializedMaterials.add(sharedCache[0]); }
        }
        assertEquals(1, initializedSuppliers.size(), "Only the actually executed request initializes a published material owner");
        assertEquals(1, initializedMaterials.size(), "Actual published cache contains exactly one material instance");
        var actualMaterial = initializedMaterials.iterator().next();
        if (observedPublishedMaterial == null) observedPublishedMaterial = actualMaterial;
        else assertSame(observedPublishedMaterial, actualMaterial, "Saved reads/reuse keep the same already initialized published material instance");
        var result = base.mapper.createObjectNode().put("schemaVersion", "t05-material-owner-observation-v1")
                .put("publishedMaterialInitializations", publishedCalls).put("predictionMaterialInitializations", predictionCalls)
                .put("sharedPublishedSupplierCount", initializedSuppliers.size()).put("sharedPublishedMaterialInstanceCount", initializedMaterials.size())
                .put("supplierCallsByObservation", 0).put("scientificQualificationGranted", false);
        result.set("actualCallSites", locations); return result;
    }

    private static Object capturedPublishedMaterialSupplier(Object factory) throws ReflectiveOperationException {
        assertNotNull(factory); Object found = null;
        for (var capture : factory.getClass().getDeclaredFields()) {
            if (!java.util.function.Supplier.class.isAssignableFrom(capture.getType())) continue;
            capture.setAccessible(true); Object candidate = capture.get(factory);
            if (candidate == null) continue;
            boolean hasMaterialSlot = java.util.Arrays.stream(candidate.getClass().getDeclaredFields())
                    .anyMatch(field -> field.getType() == CodingTranscriptMaterialSupply[].class);
            if (!hasMaterialSlot) continue;
            assertNull(found, "Published factory must have one typed material supplier capture"); found = candidate;
        }
        assertNotNull(found, "Actual published material supplier capture is required"); return found;
    }
    private static CodingTranscriptMaterialSupply[] capturedMaterialCache(Object supplier) throws ReflectiveOperationException {
        CodingTranscriptMaterialSupply[] found = null;
        for (var capture : supplier.getClass().getDeclaredFields()) if (capture.getType() == CodingTranscriptMaterialSupply[].class) {
            assertNull(found, "One actual material cache slot is required"); capture.setAccessible(true);
            found = (CodingTranscriptMaterialSupply[]) capture.get(supplier);
        }
        assertNotNull(found); return found;
    }
    private static Object existingField(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private ObjectNode scientificCalls() {
        var observed = transport.configurationCalls(); var scientific = base.mapper.createObjectNode();
        for (String field : List.of("pm4", "proteinLengthMaterials", "bp3", "transcriptMaterials", "pvs1", "population", "actualStubCount"))
            scientific.set(field, observed.path(field));
        scientific.put("nativeSourceProductions", mockingDetails(base.producer).getInvocations().stream()
                .filter(call -> call.getMethod().getName().startsWith("produce")).count());
        if (current18) try {
            Path queries = temporary.resolve("queries.txt");
            scientific.put("ba1RawQueries", Files.exists(queries) ? Files.readAllLines(queries).size() : 0);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("TEST_QUERY_OBSERVATION_UNAVAILABLE", invalid); }
        return scientific;
    }
    private JsonNode fullGet(String id) throws Exception {
        return base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private ObjectNode expectations(String selection) {
        var out = base.mapper.createObjectNode().put("schemaVersion", "t05-bp1-current-truncating-condition-expectations-v2")
                .put("selection", selection).put("dataClass", "SYNTHETIC").put("realVepExecuted", false)
                .put("biologicalRasReferenceAsserted", false).put("lofQualificationFabricated", false)
                .put("trueLofPositiveCovered", false).put("noEstablishedLofQualificationAvailable", false)
                .put("noLofSourceIsSyntheticEngineeringControl", syntheticNoLof).put("realNoLofQualificationAdopted", false)
                .put("configuredCurrentCriteria", current18 ? 18 : 14).put("allCurrent18CriteriaCovered", current18)
                .put("rawSha256", RAW_SHA).put("preparedSha256", usedPreparedSha);
        var cases = out.putArray("cases");
        for (int i = 0; i < SCOPES.size(); i++) {
            var scope = SCOPES.get(i);
            cases.addObject().put("gene", scope.gene()).put("hgncId", scope.hgnc()).put("transcript", scope.transcript())
                    .put("engineeringAllele", ("HBB".equals(scope.transportGene()) ? "11" : "13") + (nonsense ? ":115:A:T" : ":107:C:T"))
                    .put("engineeringCoding", nonsense ? "c.16A>T" : "c.8C>T").put("engineeringProtein", nonsense ? "Lys6Ter" : "Pro3Leu")
                    .put("specification", scope.specification()).put("conditionStatus", expectedConditionStatus(i))
                    .put("applicationStatus", expectedApplicationStatus(i))
                    .put("strength", expectedStrength(i)).put("reasonCode", expectedReason(i));
        }
        return out;
    }
    private void artifact(String scenario, String name, JsonNode value) throws Exception {
        String root = System.getProperty("t05.bp1.artifacts"); if (root == null) return;
        Path directory = Files.createDirectories(Path.of(root).toAbsolutePath().normalize().resolve(scenario));
        Files.write(directory.resolve(name + ".json"), base.mapper.writeValueAsBytes(value));
    }
    private static Path configuredPath(String key, String relative) { return Path.of(System.getProperty(key, LOCAL.resolve(relative).toString())); }
    private static String sha(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
