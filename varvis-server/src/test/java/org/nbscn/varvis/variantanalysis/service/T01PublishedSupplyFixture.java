package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Isolated Writer: actual source knowledge, native reference/material verification and PM1 scoring.
 * Ranking, patient declarations and six model-derived annotations are synthetic controls, not an
 * executed VEP run or a clinical CURRENT18 validation. Other domains retain absent/synthetic inputs.
 * This process saves ordinary HTTP/service results and closes H2; it never launches a history reader.
 */
public final class T01PublishedSupplyFixture {
    private static final String PM1 = "ACMG_AMP_2015:PM1";
    private final Path dbRoot, outputRoot, python;
    private final ObjectNode integration, inputs;
    private final JsonNode catalogMoiMappings;
    private final Bs1KnowledgeBusinessTest populationFixture;
    private final AcmgBusinessIntegrationTest base;
    private final ObjectMapper mapper;
    private final AtomicInteger referenceQueries = new AtomicInteger();
    private final List<GeneralPm1Runtime> engines = new ArrayList<>();
    private final Map<String, Integer> factories = new LinkedHashMap<>();
    private final Map<String, String> configurationBindings = new LinkedHashMap<>();
    private final Map<String, JsonNode> vectorsByCandidate = new LinkedHashMap<>();
    private final Map<Long, TranscriptConsequence> annotationsByPosition = new LinkedHashMap<>();
    private final CodingTranscriptMaterialSupply.ReferenceReader reference;
    private final AcmgBusinessService population;
    private final Path workflow;
    private final VariantAnalysisProperties localSettings, populationSettings;
    private final ObjectNode readerManifest;
    private final JsonNode rankingBefore;
    private List<String> available;
    private ObjectNode options, confirmed;
    private Path knowledgeFile;
    private String knowledgePin;

    public static void main(String[] args) throws Exception {
        require(args.length == 4, "T01_WRITER_ARGUMENTS_REQUIRED");
        Path db = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(db); Files.createDirectories(output);
        require(!Files.exists(db.resolve("business-db.mv.db")), "T01_EXISTING_DATABASE_REFUSED");
        var writer = new T01PublishedSupplyFixture(db, output, Path.of(args[2]), Path.of(args[3]));
        try {
            writer.execute();
            var source = writer.base.store.source(writer.base.analysisId).orElseThrow();
            assertEquals(source.sha256(), AcmgBusinessStore.sha(source.json()));
            create(output.resolve("expected-source.json"), source.json().getBytes(StandardCharsets.UTF_8));
            writer.readerManifest.put("sourceSha256", source.sha256());
        } finally {
            writer.base.closeDatabase();
        }
        writer.readerManifest.put("databaseClosed", true).put("writerCompleted", true);
        writer.write(output.resolve("reader-manifest.json"), writer.readerManifest);
    }

    private T01PublishedSupplyFixture(Path dbRoot, Path outputRoot, Path requestFile, Path python) throws Exception {
        this.dbRoot = dbRoot; this.outputRoot = outputRoot; this.python = python.toRealPath();
        require(Files.isRegularFile(this.python), "T01_EXISTING_PYTHON_REQUIRED");
        var bootstrap = new ObjectMapper().findAndRegisterModules();
        Path inputsFile = Path.of(Objects.requireNonNull(System.getProperty("varvis.t01.inputs"), "varvis.t01.inputs is required"));
        integration = object(bootstrap.readTree(Files.readAllBytes(requestFile)));
        inputs = object(bootstrap.readTree(Files.readAllBytes(inputsFile)));
        require("t01-normal-integration-request-v1".equals(integration.path("schemaVersion").asText())
                && "t01-normal-business-local-inputs-v1".equals(inputs.path("schemaVersion").asText()), "T01_INPUT_SCHEMA_INVALID");
        populationFixture = new Bs1KnowledgeBusinessTest(); populationFixture.temporary = dbRoot; populationFixture.setup();
        base = populationFixture.local.fixture.base; mapper = base.mapper;
        knowledgeFile = Path.of(text(integration.path("knowledge"), "file"));
        knowledgePin = text(integration.path("knowledge"), "rawSha256");
        verify(knowledgeFile, knowledgePin);
        verify(Path.of(text(integration.path("nativeModel"), "file")), text(integration.path("nativeModel"), "rawSha256"));
        for (String name : List.of("manifest", "mane", "clingen")) verify(inputs.path("binding").path(name));
        catalogMoiMappings = object(mapper.readTree(Files.readAllBytes(
                Path.of(text(inputs.at("/binding/manifest"), "file"))))).at("/intakePolicy/catalogMoiMappings");
        require(catalogMoiMappings.isObject() && !catalogMoiMappings.isEmpty(), "T01_SOURCE_MOI_MAPPING_REQUIRED");
        verify(inputs.path("referenceReceipt")); verify(inputs.path("referenceIndex")); verify(inputs.path("referenceAlias"));
        verify(inputs.path("modelManifest"));
        var normalization = base.properties.getNormalization();
        normalization.setGrch38Reference(text(inputs, "referenceFasta"));
        normalization.setGrch38ReferenceSha256(text(inputs, "referenceSha256"));
        normalization.setGrch38ProfileId(text(inputs, "referenceProfileId"));
        normalization.setGrch38ContigAliasFile(text(inputs, "contigAliasFile"));
        assertEquals("READY", new NormalizationResourceValidator(base.properties).validate(GenomeAssembly.GRCh38).getStatus());
        var receipt = inputs.path("referenceReceipt");
        var ledger = ExplicitContextSourceLedger.load(mapper, Path.of(text(receipt, "file")), text(receipt, "sha256"));
        base.fixture.sources.add(ledger);
        String locator = text(receipt, "locator");
        Path resourceRoot = Path.of(text(inputs, "resourceRoot"));
        base.producer = new NativeSnvSourceProducer(mapper, base.properties, resourceRoot, List.of(ledger), ledger.reference(locator));
        var actualReader = new CodingTranscriptReferenceReader(mapper, base.properties, resourceRoot, ledger, locator);
        reference = (contig, start, length) -> { referenceQueries.incrementAndGet(); return actualReader.read(contig, start, length); };
        var binding = inputs.path("binding");
        base.fixture.resolver = PatientDiseaseContextBindingResolver.load(mapper,
                Path.of(text(binding.path("manifest"), "file")), Path.of(text(binding.path("mane"), "file")),
                Path.of(text(binding.path("clingen"), "file")));
        publish();
        rankingBefore = mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        populationFixture.local.fixture.wire(); populationFixture.wire(); population = base.service;
        var companions = companions();
        workflow = companions.prepareEmptyLiteratureWorkflow(Path.of(text(inputs, "mainProjectRoot")).resolve("scripts/denovo"), this.python);
        populationSettings = populationFixture.local.fixture.populationSettings(dbRoot.resolve("synthetic-tabix.cmd").toString());
        populationSettings.getGnomad().setBa1LocalExomesEnabled(true);
        populationSettings.getGnomad().setGrch38Files(dbRoot.resolve("honestly-missing-gnomad-chr12.vcf.bgz").toString());
        localSettings = new VariantAnalysisProperties();
        localSettings.getClinVar().setEnabled(true); localSettings.getClinVar().setGrch38File(dbRoot.resolve("honestly-missing-clinvar.vcf.gz").toString());
        localSettings.getSpliceAi().setEnabled(true); localSettings.getSpliceAi().setGrch38File(dbRoot.resolve("honestly-missing-spliceai.vcf.gz").toString());
        localSettings.getAlphaMissense().setEnabled(true); localSettings.getAlphaMissense().setGrch38File(dbRoot.resolve("honestly-missing-alphamissense.tsv.gz").toString());
        readerManifest = mapper.createObjectNode().put("schemaVersion", "t01-published-supply-reader-manifest-v1")
                .put("analysisId", base.analysisId).put("writerPid", ProcessHandle.current().pid()).put("dbDirectory", dbRoot.toString());
        readerManifest.putArray("runs");
        var source = mapper.createObjectNode().put("schemaVersion", "t01-published-supply-source-evidence-v1")
                .put("sourceHead", System.getProperty("varvis.t01.sourceHead", inputs.path("sourceHead").asText()))
                .put("integrationRequestRawSha256", rawSha(requestFile)).put("executionInputsRawSha256", rawSha(inputsFile))
                .put("referenceReceiptReused", true).put("fullFastaRehashedByWriter", false)
                .put("annotationDerivation", "SYNTHETIC_CONTROL_FROM_ACTUAL_PINNED_MODEL_NOT_EXECUTED_VEP")
                .put("rankingAndPatientDeclarations", "SYNTHETIC_CONTROLS")
                .put("otherDomains", "ACTUAL_OPERATORS_WITH_ABSENT_OR_SYNTHETIC_INPUTS_NOT_CLINICAL_CURRENT18_VALIDATION")
                .put("modelOrPublicNetworkCallsAssertedMeasured", false).put("newScientificApprovalCreated", false);
        source.set("integrationRequest", integration.deepCopy()); source.set("inputs", inputs.deepCopy());
        write(outputRoot.resolve("source-evidence.json"), source);
        wire();
    }

    private UnifiedEighteenCompanionFixtures companions() throws Exception {
        return new UnifiedEighteenCompanionFixtures(mapper, dbRoot.resolve("companions"), Path.of(text(inputs, "modelDirectory")),
                text(inputs, "modelManifestSha256"), reference);
    }

    private void publish() throws Exception {
        var vectors = integration.path("candidateVectors");
        require(vectors.isArray() && vectors.size() == 6 && inputs.path("candidateVectors").size() == 6, "T01_SIX_FIXED_VECTORS_REQUIRED");
        String symbol = inputs.at("/actualBindingMatches/mane/0/symbol").asText();
        String entrez = inputs.at("/actualBindingMatches/mane/0/ncbiGeneId").asText();
        require(!symbol.isBlank() && entrez.matches("[1-9][0-9]*"), "T01_ACTUAL_GENE_BINDING_REQUIRED");
        var rows = new ArrayList<String>(); var vcfRows = new ArrayList<String>();
        for (int i = 0; i < vectors.size(); i++) {
            JsonNode vector = vectors.get(i), local = inputs.path("candidateVectors").get(i), variant = vector.path("variant");
            assertEquals(vector.path("caseId"), local.path("caseId")); assertEquals(variant, local.path("variant"));
            var annotation = Objects.requireNonNull(mapper.treeToValue(local.path("executionAnnotation"), TranscriptConsequence.class));
            assertTrue(Objects.toString(annotation.getSource(), "").startsWith("VEP_SYNTHETIC")); assertEquals("COMPLETED", annotation.getStatus());
            assertEquals(vector.path("nativeTranscript").asText(), annotation.getVersionedTranscriptId());
            assertNull(annotationsByPosition.put(variant.path("pos").asLong(), annotation));
            String chr = variant.path("contig").asText(), pos = variant.path("pos").asText();
            String ref = variant.path("ref").asText(), alt = variant.path("alt").asText();
            rows.add((i + 1) + "\t" + chr + "\t" + pos + "\t" + ref + "\t" + alt + "\t" + symbol + "\t" + entrez + "\tAD\t0." + (9 - i));
            vcfRows.add(chr + "\t" + pos + "\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\tGT\t.");
        }
        base.expectedPublishedCount = 6; base.variantTsvOverride = tsv(rows.toArray(String[]::new));
        // The existing publication fixture skips completed annotations. Its fallback maps remain defined,
        // while each published allele is enriched independently by its exact genomic position.
        base.fixture.annotations = new LinkedHashMap<>(base.fixture.annotations);
        base.fixture.annotations.put("public-hbb-compatible-e7v", List.of(annotationsByPosition.values().iterator().next()));
        base.fixture.annotations.put("public-gjb2-20189473-ct", List.of(annotationsByPosition.values().iterator().next()));
        base.syntheticPublicationEnrichment = published -> published.forEach(row -> {
            var key = row.getObservation().getKey();
            var annotation = annotationsByPosition.get((long) key.getPosition());
            if (annotation == null) throw new IllegalArgumentException("T01_UNEXPECTED_PUBLISHED_ALLELE");
            row.applyTranscriptAnnotations(List.of(annotation));
        });
        base.publish(false, GenomeAssembly.GRCh38, vcf(vcfRows.toArray(String[]::new)));
        var published = base.jobs.findInterpretations(base.analysisId, 0, 100);
        assertEquals(6, published.size());
        for (int i = 0; i < published.size(); i++) {
            var row = published.get(i); JsonNode vector = vectors.get(i);
            assertEquals(vector.at("/variant/pos").asLong(), (long) row.getObservation().getKey().getPosition());
            assertEquals(1, row.getTranscriptAnnotations().size());
            assertEquals(annotationsByPosition.get(vector.at("/variant/pos").asLong()).getHgvsProtein(), row.getTranscriptAnnotations().get(0).getHgvsProtein());
            vectorsByCandidate.put(row.getCandidateId(), vector);
        }
    }

    private void wire() throws Exception {
        var rarity = new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity() { return populationFixture.local.fixture.ba1.identity(); }
            public Pm2LocalExomesRuntime create() { return new Pm2LocalExomesRuntime(mapper, populationSettings); }
        };
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population, (effects, pin) -> {
            try {
                var c = companions();
                var configuration = mapper.createObjectNode().put("testDataClass", "T01_ACTUAL_PUBLISHED_SOURCE_WITH_SYNTHETIC_SELECTION")
                        .put("publishedKnowledgeRawSha256", knowledgePin).put("modelManifestSha256", text(inputs, "modelManifestSha256"))
                        .put("referenceSha256", text(inputs, "referenceSha256")).put("referenceReceiptSha256", text(inputs.path("referenceReceipt"), "sha256"))
                        .put("executionInputsSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(inputs))
                        .put("emptyPublishedKnowledgeSha256", c.publishedKnowledgeSha256).put("emptyUniProtIndexSha256", c.uniProtManifestSha256)
                        .put("emptySpecializationCatalogSha256", c.specializationCatalogSha256).put("literatureWorkflowSha256", rawSha(workflow));
                configuration.set("population", populationFixture.local.fixture.ba1.identity());
                return new LocalEvidenceBatchRuntime(mapper, configuration,
                        () -> opened("PM2", c.pm2Factory(populationSettings).get()), c.spliceFactory(localSettings), c.predictionFactory(localSettings, effects),
                        c.reference(localSettings), c.bp7(), () -> opened("PM4", c.pm4()), this::missingPvs1FromActualConfiguration,
                        () -> opened("BP3", c.bp3()), () -> {
                            factories.merge("PM1", 1, (existingCount, addedCount) -> existingCount + addedCount);
                            try {
                                var engine = new GeneralPm1Runtime(mapper, c.missenseMaterials, PublishedCriterionKnowledge.load(mapper, knowledgeFile, knowledgePin));
                                engines.add(engine); return engine;
                            } catch (IOException unavailable) { throw new IllegalArgumentException("PM1_PUBLISHED_KNOWLEDGE_UNAVAILABLE", unavailable); }
                        }, () -> opened("PP2", c.pp2(false)), () -> opened("BP1", c.bp1(effects, pin)), false,
                        c.deNovo(), c.pm3(workflow, rarity), c.pp1(workflow));
            } catch (RuntimeException invalid) { throw invalid; }
            catch (Exception invalid) { throw new IllegalArgumentException("T01_COMPANION_CONFIGURATION_UNAVAILABLE", invalid); }
        }, dbRoot.resolve("effect-reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(new AcmgBusinessWorkflows(base.service, null))).build();
        options = object(mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "6").param("workflow", "CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        available = new ArrayList<>(); options.path("enabledCriteria").forEach(c -> available.add(c.asText()));
        assertEquals(18, new HashSet<>(available).size(), options.toPrettyString());
        confirmed = confirm();
    }

    /** Invoke the production factory's genuine missing-source check; never return a fabricated result. */
    private Pvs1SelectedBatch missingPvs1FromActualConfiguration() {
        factories.merge("PVS1_CONFIGURATION", 1, (existingCount, addedCount) -> existingCount + addedCount);
        try {
            Class<?> type = Class.forName("org.nbscn.varvis.variantanalysis.config.LocalEvidenceBatchConfiguration");
            var constructor = type.getDeclaredConstructor(ObjectMapper.class, VariantAnalysisProperties.class, Environment.class, AlphaMissensePhaseBudget.class);
            constructor.setAccessible(true);
            var configuration = constructor.newInstance(mapper, localSettings, new MockEnvironment(), null);
            var method = type.getDeclaredMethod("pvs1"); method.setAccessible(true);
            return (Pvs1SelectedBatch) method.invoke(configuration);
        } catch (InvocationTargetException invoked) {
            if (invoked.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("T01_PVS1_FACTORY_INVOCATION_FAILED", invoked.getCause());
        } catch (ReflectiveOperationException invalid) { throw new IllegalStateException("T01_PVS1_FACTORY_API_CHANGED", invalid); }
    }

    private ObjectNode confirm() {
        var request = base.request.deepCopy().put("topN", 6).put("declarationVersion", AcmgBusinessRuleScope.UNIFIED_FOURTEEN_DECLARATION);
        var refs = request.putArray("recommendations"); var hypotheses = request.putArray("hypotheses");
        assertEquals(6, options.path("candidates").size(), options.toPrettyString());
        for (var row : options.path("candidates")) {
            var rec = row.path("recommendation"); JsonNode relation = null, annotation = null;
            for (var r : rec.path("relationships")) if (r.path("selectable").asBoolean()
                    && integration.at("/scope/hgncId").equals(r.path("hgncId")) && integration.at("/scope/mondoId").equals(r.path("mondoId"))
                    && (integration.at("/scope/moi").equals(r.path("moi"))
                        || integration.at("/scope/moi").equals(catalogMoiMappings.path(r.path("moi").asText())))) relation = r;
            JsonNode vector = vectorsByCandidate.get(row.path("candidateId").asText()); assertNotNull(vector);
            for (var a : rec.path("annotations")) if (a.path("contextSelectable").asBoolean()
                    && vector.path("nativeTranscript").equals(a.path("versionedTranscriptId"))) annotation = a;
            if (relation == null || annotation == null) throw new AssertionError(rec.toPrettyString());
            var source = base.declaredSource("recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var item = hypotheses.addObject().put("affectedStatus", "UNKNOWN"); var h = item.putObject("hypothesis");
            h.set("candidateId", row.path("candidateId")); h.set("hypothesisId", rec.path("hypothesisId"));
            for (String key : List.of("hgncId", "mondoId", "moi")) h.set(key, relation.path(key)); h.set("provenance", source);
            String tx = annotation.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
            var t = item.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                    .put("selectionReason", "Explicit model-derived synthetic control; VEP was not executed; not a patient diagnosis");
            t.set("annotationSource", annotation.path("annotationSource")); t.set("annotationSourceVersion", annotation.path("annotationSourceVersion")); t.set("source", source);
            var ref = refs.addObject(); for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(key, rec.path(key));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
        }
        return request;
    }

    private void execute() throws Exception {
        assertTrue(factories.isEmpty(), "Configuration/input-options must not acquire scientific modules");
        submit("single", List.of(PM1), null);
        submit("related", List.of(PM1, "ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5"), null);
        submit("all", available, null);
        requestControls();
        Path goodFile = knowledgeFile; String goodPin = knowledgePin;
        knowledgePin = "0".repeat(64); wire();
        submit("wrong-source-pin", List.of(PM1), "PUBLISHED_KNOWLEDGE_PIN_MISMATCH");
        knowledgeFile = goodFile; knowledgePin = goodPin;
        var duplicate = object(mapper.readTree(Files.readAllBytes(goodFile)));
        ObjectNode exact = null;
        for (var row : duplicate.path("records")) if (integration.at("/scope/hgncId").equals(row.at("/scope/hgncId"))
                && integration.at("/scope/mondoId").equals(row.at("/scope/mondoId")) && PM1.equals(row.path("criterionId").asText())) exact = (ObjectNode) row;
        if (exact == null) throw new IllegalStateException("T01_EXACT_SOURCE_ROW_REQUIRED");
        duplicate.withArray("records").add(exact.deepCopy().put("recordId", "T01_ADVERSARIAL_DUPLICATE_SCOPE_NOT_AN_ADOPTION"));
        knowledgeFile = outputRoot.resolve("adversarial-duplicate-knowledge.json"); write(knowledgeFile, duplicate); knowledgePin = rawSha(knowledgeFile); wire();
        submit("duplicate-source-scope", List.of(PM1), "PUBLISHED_KNOWLEDGE_MULTIPLE_POLICIES_REQUIRE_SELECTION");
        assertEquals(configurationBindings.get("single"), configurationBindings.get("related"));
        assertEquals(configurationBindings.get("single"), configurationBindings.get("all"));
        assertNotEquals(configurationBindings.get("single"), configurationBindings.get("wrong-source-pin"),
                "Actual pinned source changes must change the runtime configuration identity");
        assertNotEquals(configurationBindings.get("single"), configurationBindings.get("duplicate-source-scope"));
        var identityReceipt = mapper.createObjectNode().put("normalSelectionsShareSourceBinding", true)
                .put("sourceMutationChangesRuntimeBinding", true).put("oldRunsRetainedForSeparateJvmRawVerification", true);
        identityReceipt.set("configurationSha256ByScenario", mapper.valueToTree(configurationBindings));
        write(outputRoot.resolve("configuration-binding-controls.json"), identityReceipt);
        assertEquals(rankingBefore, mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
    }

    private void submit(String name, List<String> selection, String expectedFailure) throws Exception {
        var request = confirmed.deepCopy(); request.set("selectedCriteria", mapper.valueToTree(selection));
        var before = counts(); var product = base.postRequest(request);
        assertNotEquals("FAILED", product.path("status").asText(), product.toPrettyString());
        String binding = product.at("/executionProfile/moduleBindings/pm1/runtimeBinding/configurationSha256").asText();
        assertTrue(binding.matches("[0-9a-f]{64}"));
        assertNull(configurationBindings.put(name, binding));
        var ordered = available.stream().filter(selection::contains).toList(); assertEquals(mapper.valueToTree(ordered), product.path("enabledCriteria"));
        assertEquals(6, product.path("candidates").size(), product.toPrettyString());
        for (var candidate : product.path("candidates")) {
            JsonNode vector = vectorsByCandidate.get(candidate.path("candidateId").asText()); assertNotNull(vector);
            assertEquals(1, candidate.path("attempts").size(), "Every fixed candidate must reach its actual selected disease context");
            for (var attempt : candidate.path("attempts")) {
                var actualCriteria = new ArrayList<String>();
                attempt.at("/localBatch/criteria").forEach(c -> actualCriteria.add(c.path("criterionId").asText()));
                assertEquals(ordered.stream().filter(c -> !List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1").contains(c)).toList(),
                        actualCriteria, "The requested local criteria must actually be present");
                var criterion = criterion(attempt, PM1);
                if (expectedFailure == null) {
                    assertEquals(vector.path("expectedStatus").asText(), criterion.path("status").asText(), criterion.toPrettyString());
                    assertEquals(vector.path("expectedStrength").asText(), criterion.path("appliedStrength").asText(), criterion.toPrettyString());
                    String reason = switch (vector.path("proteinPosition").asInt()) {
                        case 130, 273 -> "PM1_PUBLISHED_CRITICAL_LOCATION_MATCH";
                        case 129, 274 -> "PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED";
                        case 281, 631 -> "PM1_PUBLISHED_EXPLICIT_EXCLUSION";
                        default -> throw new AssertionError("Independent T01 source expectation missing");
                    };
                    assertEquals(reason, criterion.path("reasonCode").asText(), criterion.toPrettyString());
                    assertEquals("EXECUTABLE", criterion.at("/details/pm1Facts/sourceDisposition").asText());
                    assertEquals("GN017@3.1", criterion.at("/details/pm1Facts/specificationKey").asText());
                } else {
                    assertEquals("NOT_EVALUATED", criterion.path("status").asText(), criterion.toPrettyString());
                    assertEquals("NONE", criterion.path("appliedStrength").asText()); assertEquals(expectedFailure, criterion.path("reasonCode").asText(), criterion.toPrettyString());
                }
            }
        }
        var after = counts();
        if (expectedFailure == null) {
            assertEquals(6, after.path("pm1Evaluations").asInt() - before.path("pm1Evaluations").asInt(),
                    "All six real PM1 evaluations must execute in each normal selection");
            assertTrue(after.path("codingReferenceQueries").asInt() > before.path("codingReferenceQueries").asInt(),
                    "Real coding reference reads must execute before scientific assertions can pass");
        }
        assertEquals(product, base.postRequest(request)); assertEquals(after, counts(), "Exact durable reuse must not call PM1, its factory or reference again");
        String runId = product.path("runId").asText();
        var reopened = mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()); assertEquals(product, reopened);
        var index = base.service.readView(base.analysisId, runId, null, 0, null);
        for (var candidate : product.path("candidates")) for (int i = 0; i < candidate.path("attempts").size(); i++)
            assertEquals(candidate.path("attempts").get(i), base.service.readView(base.analysisId, runId,
                    candidate.path("candidateId").asText(), i, index.path("sourceViewSha256").asText()).at("/candidates/0/attempts/" + i));
        assertEquals(after, counts(), "All saved details/whole JSON reads must remain read-only");
        assertEquals(rankingBefore, mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
        Path directory = Files.createDirectories(outputRoot.resolve(name));
        var stored = base.store.run(base.analysisId, runId).orElseThrow();
        create(directory.resolve("expected-public.json"), Objects.requireNonNull(stored.responseJson()).getBytes(StandardCharsets.UTF_8));
        create(directory.resolve("expected-private.json"), Objects.requireNonNull(stored.privateJson()).getBytes(StandardCharsets.UTF_8));
        create(directory.resolve("expected-request.json"), Objects.requireNonNull(stored.requestJson()).getBytes(StandardCharsets.UTF_8));
        create(directory.resolve("actual-product.json"), stored.responseJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(product, mapper.readTree(stored.responseJson()));
        var countArtifact = mapper.createObjectNode(); countArtifact.set("before", before); countArtifact.set("afterSave", after); countArtifact.set("afterReuseAndReads", counts());
        write(directory.resolve("counts.json"), countArtifact); write(directory.resolve("input-options.json"), options);
        readerManifest.withArray("runs").addObject().put("name", name).put("runId", runId);
    }

    private void requestControls() throws Exception {
        var receipt = mapper.createObjectNode().put("schemaVersion", "t01-normal-http-negative-controls-v1"); var cases = receipt.putArray("cases");
        for (String kind : List.of("DISEASE", "MOI", "TRANSCRIPT", "DUPLICATE_CRITERION")) {
            var request = confirmed.deepCopy(); request.putArray("selectedCriteria").add(PM1);
            if (kind.equals("DISEASE")) request.withObject("/hypotheses/0/hypothesis").put("mondoId", "MONDO:0099999");
            if (kind.equals("MOI")) request.withObject("/hypotheses/0/hypothesis").put("moi", "AUTOSOMAL_RECESSIVE");
            if (kind.equals("TRANSCRIPT")) request.withObject("/hypotheses/0/transcript").put("version", 999);
            if (kind.equals("DUPLICATE_CRITERION")) request.withArray("selectedCriteria").add(PM1);
            var before = counts(); int runs = base.store.runs(base.analysisId).size();
            var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).param("workflow", "CURRENT")
                    .contentType("application/json").content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(request)))).andReturn().getResponse();
            boolean duplicateCriterion = kind.equals("DUPLICATE_CRITERION");
            assertEquals(duplicateCriterion ? 400 : 409, response.getStatus(), kind + ": " + response.getContentAsString());
            assertEquals(duplicateCriterion ? "ACMG_REQUEST_INVALID" : "RECOMMENDATION_STALE", response.getErrorMessage());
            assertEquals(before, counts(), "Unconfirmed background/transcript and duplicate selection must fail before scientific calls");
            assertEquals(runs, base.store.runs(base.analysisId).size());
            cases.addObject().put("name", kind).put("httpStatus", response.getStatus()).put("reasonCode", response.getErrorMessage())
                    .put("body", response.getContentAsString());
        }
        write(outputRoot.resolve("request-negative-controls.json"), receipt);
    }

    private ObjectNode counts() {
        var counts = mapper.createObjectNode().put("pm1Evaluations", engines.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum())
                .put("codingReferenceQueries", referenceQueries.get()).put("legacyCoordinatorSupplierCalls", base.invocations.get());
        counts.set("factories", mapper.valueToTree(factories)); return counts;
    }
    private <T> T opened(String name, T value) { factories.merge(name, 1, (existingCount, addedCount) -> existingCount + addedCount); return value; }
    private static JsonNode criterion(JsonNode attempt, String id) {
        for (var c : attempt.at("/localBatch/criteria")) if (id.equals(c.path("criterionId").asText())) return c;
        throw new AssertionError("T01 expected criterion missing: " + attempt.toPrettyString());
    }
    private static ObjectNode object(JsonNode node) {
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("T01_OBJECT_REQUIRED");
        return object;
    }
    private static String text(JsonNode node, String field) { String value = node.path(field).asText(); require(!value.isBlank(), "T01_INPUT_FIELD_REQUIRED_" + field); return value; }
    private static String rawSha(Path file) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("T01_SHA256_NOT_AVAILABLE", unavailable);
        }
    }
    private static void verify(Path file, String pin) throws IOException { require(pin.matches("[0-9a-f]{64}") && Files.isRegularFile(file) && pin.equals(rawSha(file)), "T01_PIN_MISMATCH"); }
    private static void verify(JsonNode reference) throws IOException { verify(Path.of(text(reference, "file")), text(reference, "sha256")); }
    private void write(Path file, JsonNode value) throws IOException { create(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    private static void create(Path file, byte[] raw) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, raw, StandardOpenOption.CREATE_NEW); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
