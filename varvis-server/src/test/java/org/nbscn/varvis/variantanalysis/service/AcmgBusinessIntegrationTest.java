package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;

/** HTTP, actual JDBC and actual publication/one scan; synthetic runners/declarations, opt-in real REF/AM. */
class AcmgBusinessIntegrationTest {
    @Test void publicHhtReplaySurvivesStorageHistoryAndFullExport() throws Exception {
        String input = System.getProperty("varvis.pp1.hht.public-replay", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(input.isBlank(), "Explicit reviewed public PP1 replay required");
        Path path = Path.of(input);
        var replay = mapper.readTree(Files.readAllBytes(path));
        var assessment = replay.path("evaluation");
        assertEquals("ENG", assessment.at("/target/geneSymbol").asText());
        assertEquals("HHT_CURACAO_AFFECTED_ONLY", assessment.at("/context/policy/affectedDefinition").asText());
        assertTrue(assessment.path("needs").isArray());
        assertEquals("VERIFIED_CONDITIONS_IMPLEMENTED", assessment.at("/context/ruleAttachments/0/status").asText());

        publish(false, GenomeAssembly.GRCh38, null);
        assertPublicPp1ReplayRoundTrip(path, replay);
    }

    @Test void reviewedPublicPp1ReplaySurvivesStorageHistoryAndFullExport() throws Exception {
        String input = System.getProperty("varvis.pp1.public-replay", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(input.isBlank(), "Explicit reviewed public PP1 replay required");
        Path path = Path.of(input);
        var replay = mapper.readTree(Files.readAllBytes(path));
        var assessment = replay.path("evaluation");
        var target = assessment.path("target");
        var allele = target.path("allele");
        assertEquals("GRCh38", allele.path("assembly").asText());
        assertEquals("MET", assessment.path("status").asText());
        assertEquals(assessment.at("/meiosisAssessment/informativeMeioses").asInt(-1), assessment.path("events").size());
        assertTrue(assessment.path("events").size() > 0);
        for (var event : assessment.path("events")) {
            assertFalse(event.path("eventId").asText().isBlank());
            assertFalse(event.path("sourceRefs").isEmpty());
        }
        String contig = allele.path("contig").asText();
        String position = allele.path("position").asText();
        String ref = allele.path("reference").asText();
        String alt = allele.path("alternate").asText();
        String gene = target.path("geneSymbol").asText();
        String inheritance = target.path("inheritance").asText();
        // A coordinate-only VCF scopes the saved result to the reviewed public allele.
        // The fixture contains no patient genotype and does not re-score the replay.
        expectedPublishedCount = 1;
        variantTsvOverride = tsv(String.join("\t", "1", contig, position, ref, alt, gene, "0", inheritance, "0.8"));
        publish(false, GenomeAssembly.GRCh38,
                vcf(String.join("\t", "chr" + contig, position, ".", ref, alt, ".", "PASS", ".", "GT", ".")));
        assertPublicPp1ReplayRoundTrip(path, replay);
    }

    private void assertPublicPp1ReplayRoundTrip(Path input, JsonNode replay) throws Exception {
        var assessment = replay.path("evaluation");
        recreateService(runtime());
        String runId = UUID.randomUUID().toString();
        String request = "{}";
        String requestSha = new org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter(mapper)
                .canonicalSha256(mapper.readTree(request));
        var privateData = mapper.createObjectNode(); privateData.putObject("declaration");
        privateData.set("pp1SourceReplay", replay);
        String privateJson = privateData.toString();
        String terminal = "MET".equals(assessment.path("status").asText()) ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS";
        var expected = mapper.createObjectNode().put("analysisId", analysisId).put("runId", runId)
                .put("requestSha256", requestSha).put("status", terminal).put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
        var criterion = expected.putArray("candidates").addObject().putArray("attempts").addObject()
                .putObject("localBatch").putArray("criteria").addObject().put("criterionId", "ACMG_AMP_2015:PP1");
        criterion.putObject("details").set("pp1Facts", replay);
        assertTrue(store.claim(new AcmgBusinessStore.Run(runId, analysisId, requestSha, request, "RUNNING", "now", null, null, null)));
        store.finish(runId, terminal, expected.toString(), privateJson);
        assertEquals(expected, service.read(analysisId, runId));
        closeDatabase(); recreateRepositories(); recreateService(runtime());
        var reopened = store.run(analysisId, runId).orElseThrow();
        assertEquals(privateJson, reopened.privateJson());
        assertEquals(expected, service.read(analysisId, runId));
        byte[] history = http.perform(get("/variant-analyses/{id}/acmg/runs", analysisId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(runId, mapper.readTree(history).get(0).path("runId").asText());
        byte[] exported = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysisId, runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(expected, mapper.readTree(exported));
        assertEquals(0, invocations.get(), "History and export must reuse the saved result");
        Files.write(input.resolveSibling("isolated-storage-full-export.json"), exported);
    }

    @Test void savedReadViewsPreserveExportAndRejectWrongScopeAndCorruptionAfterCacheHit() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        recreateService(runtime());
        String runId=UUID.randomUUID().toString(), request="{}", privateJson="{\"declaration\":{}}";
        String hash=new AcmgBusinessArtifacts(mapper).requestHash(mapper.readTree(request));
        var full=mapper.createObjectNode().put("analysisId",analysisId).put("runId",runId).put("requestSha256",hash)
                .put("status","COMPLETED_WITH_BLOCKERS").put("privateArtifactSha256",AcmgBusinessStore.sha(privateJson));
        full.putObject("localBatch").put("testOnly",true); full.putArray("candidateIds").add("c");
        var candidate=full.putArray("candidates").addObject().put("candidateId","c");
        candidate.putArray("attempts").addObject().put("hypothesisId","h").putObject("context").put("contextId","ctx");
        assertTrue(store.claim(new AcmgBusinessStore.Run(runId,analysisId,hash,request,"RUNNING","now",null,null,null)));
        store.finish(runId,"COMPLETED_WITH_BLOCKERS",full.toString(),privateJson);
        String root="/variant-analyses/"+analysisId+"/acmg/runs/"+runId;
        var index=mapper.readTree(http.perform(get(root+"/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(AcmgResultViews.INDEX,index.path("viewKind").asText());
        assertFalse(index.has("localBatch"));
        String sha=index.path("sourceViewSha256").asText();
        for (int i=0;i<2;i++) {
            var view=mapper.readTree(http.perform(get(root+"/view/background").param("candidateId","c").param("backgroundIndex","0").param("sourceSha256",sha))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals(full.at("/candidates/0/attempts/0"),view.at("/candidates/0/attempts/0"));
        }
        http.perform(get(root+"/view/background").param("candidateId","missing").param("backgroundIndex","0").param("sourceSha256",sha)).andExpect(status().isNotFound());
        http.perform(get(root+"/view/background").param("candidateId","c").param("backgroundIndex","0").param("sourceSha256","0".repeat(64))).andExpect(status().isConflict());
        assertEquals(full,mapper.readTree(http.perform(get(root)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        assertEquals(full,mapper.readTree(http.perform(get(root).param("sourceViewSha256",sha)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        http.perform(get(root).param("sourceViewSha256","0".repeat(64))).andExpect(status().isConflict());
        recreateRepositories(); recreateService(runtime()); // History read using fresh store/service, no projection persisted.
        assertEquals(index,mapper.readTree(http.perform(get(root+"/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update("UPDATE variant_analysis_acmg_run SET private_json=? WHERE run_id=?","{\"declaration\":{\"altered\":true}}",runId);
        http.perform(get(root+"/view")).andExpect(status().isServiceUnavailable());
        assertEquals(0,invocations.get());
    }
    @TempDir Path temporary;
    final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
            .enable(SerializationFeature.INDENT_OUTPUT) // Matches the actual Spring application configuration.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    ExplicitCaseInputCoordinatorTest fixture;
    DriverManagerDataSource dataSource;
    VariantAnalysisProperties properties;
    JdbcVariantAnalysisJobRepository jobs;
    AcmgBusinessStore store;
    AcmgExecutionSources sources;
    NativeSnvSourceProducer producer;
    AcmgBusinessService service;
    MockMvc http;
    String analysisId;
    Prepared prepared;
    ObjectNode request;
    AtomicInteger invocations = new AtomicInteger();
    boolean real;
    String variantTsvOverride;
    org.nbscn.varvis.variantanalysis.engine.ExomiserEngineAdapter engineAdapterOverride;
    int expectedPublishedCount = 2;
    boolean annotationUnavailable;
    boolean annotationDisabled;
    boolean firstAlreadyAnnotated;
    java.util.function.Consumer<List<VariantInterpretation>> syntheticPublicationEnrichment = rows -> { };
    final AtomicInteger annotationRunnerCalls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        fixture = new ExplicitCaseInputCoordinatorTest(); fixture.temporary = temporary; fixture.setup();
        properties = new VariantAnalysisProperties(); properties.setStorageRoot(temporary.resolve("jobs")); properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        properties.getPersistence().setRetryBackoff(Duration.ZERO);
        // File-backed H2, using the actual SQL scripts and actual repositories, not an in-memory repository fake.
        dataSource = new DriverManagerDataSource("jdbc:h2:file:" + temporary.resolve("business-db").toString().replace('\\', '/') + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/variant-analysis-schema.sql"), new ClassPathResource("db/exomiser-analysis-schema.sql"), new ClassPathResource("db/acmg-business-schema.sql")).execute(dataSource);
        recreateRepositories();
        producer = fixture.tinyReference(r -> {}).producer();
    }
    @AfterEach void closeDatabase() { if (dataSource != null) new JdbcTemplate(dataSource).execute("SHUTDOWN"); }
    void recreateRepositories() {
        jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(java.util.Objects.requireNonNull(dataSource)), new DataSourceTransactionManager(java.util.Objects.requireNonNull(dataSource)), properties, mapper);
        store = new AcmgBusinessStore(dataSource); sources = new AcmgExecutionSources(mapper, store);
    }
    Supplier<ExplicitCaseInputCoordinator> runtime() {
        return () -> {
            invocations.incrementAndGet(); AcmgBusinessActivation.verify(mapper);
            var p = new VariantAnalysisProperties();
            if (real) {
                p.getAlphaMissense().setEnabled(true); p.getAlphaMissense().setVersion("2023-08-03");
                p.getAlphaMissense().setGrch38File(System.getProperty("varvis.e4a3a.alphamissense-grch38")); p.getAlphaMissense().setTimeout(Duration.ofSeconds(30));
            }
            return new ExplicitCaseInputCoordinator(mapper, fixture.resolver, producer, fixture.sources,
                    new ExplicitAlphaMissenseDispatcher(mapper, new AlphaMissenseProvider(p)));
        };
    }
    void recreateService(Supplier<ExplicitCaseInputCoordinator> runtime) {
        service = new AcmgBusinessService(mapper, jobs, store, sources, runtime);
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
    }
    void publish(boolean realCoordinates, GenomeAssembly assembly, String overrideVcf) throws Exception {
        analysisId = UUID.randomUUID().toString();
        String vcf = overrideVcf == null ? vcf(realCoordinates ? "chr11\t5227002\tpublic-hbb-compatible-e7v\tT\tA\t.\tPASS\t.\tGT\t." : "chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t.",
                realCoordinates ? "chr13\t20189473\tpublic-gjb2-20189473-ct\tC\tT\t.\tPASS\t.\tGT\t." : "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t.") : overrideVcf;
        prepared = ParsedCandidateFixture.prepare(properties.getStorageRoot().resolve(analysisId), analysisId, assembly, vcf,
                variantTsvOverride != null ? variantTsvOverride : tsv(realCoordinates ? "1\t11\t5227002\tT\tA\tHBB\t3043\tAR\t0.8" : "1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8",
                        realCoordinates ? "2\t13\t20189473\tC\tT\tGJB2\t2706\tAR\t0.7" : "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7"), null, false);
        jobs.save(prepared.job());
        var converter = mock(VcfBuildConverter.class);
        when(converter.convert(any(), any(), any(), any())).thenReturn(assembly == GenomeAssembly.GRCh37
                ? new BuildConversionResult(prepared.conversion().getOutputFile(), true, "SYNTHETIC_LIFTOVER", List.of(), null, 0, 2, 2)
                : prepared.conversion());
        var enrichment = mock(VariantInterpretationService.class);
        when(enrichment.enrichEvidenceOnly(any(), any())).thenAnswer(call -> {
            List<VariantInterpretation> rows = call.getArgument(0);
            if (firstAlreadyAnnotated) rows.get(0).applyTranscriptAnnotations(fixture.annotations.get("public-hbb-compatible-e7v"));
            syntheticPublicationEnrichment.accept(rows);
            return rows;
        });
        var annotation = publicationAnnotations();
        var processor = new VariantAnalysisJobProcessor(jobs, converter, mock(VcfNormalizer.class), mock(VcfQcService.class), enrichment, properties, annotation);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "acmgBusinessEnabled", !annotationDisabled);
        processor.setExomiserEngineAdapter(engineAdapterOverride == null ? prepared.adapter() : engineAdapterOverride); // Actual adapter prepares, parses and scans inside process().
        processor.setAcmgExecutionSources(sources);
        processor.process(analysisId);
        assertEquals(VariantAnalysisStatus.READY, jobs.findById(analysisId).orElseThrow().getStatus(), jobs.findById(analysisId).orElseThrow().getErrorMessage());
        if (expectedPublishedCount >= 0) assertEquals(expectedPublishedCount, jobs.findInterpretations(analysisId, 0, 100).size());
        assertTrue(store.source(analysisId).isPresent(), "Actual publication must create the source; tests never construct InputSource");
        request = declaration(); recreateService(runtime());
    }
    VepTranscriptAnnotationEnrichmentService publicationAnnotations() throws Exception {
        if (real) {
            // Real service, command builder and parser. The runner replays retained public VEP records;
            // only the transport ID is rebound to the actual tiny batch ID after exact allele verification.
            // This is NOT a new VEP executable run or a fresh VEP resource readiness acceptance.
            properties.getVep().setEnabled(true); properties.getVep().setExecutionEnabled(true);
            properties.getVep().setExecutable("synthetic-retained-vep-replay"); properties.getVep().setCacheDir(temporary.toString());
            properties.getVep().setFasta(temporary.resolve("synthetic-profile-only.fa").toString());
            properties.getVep().setVersion("116.0"); properties.getVep().setEnsemblCacheVersion("116_GRCh38");
            var validator = mock(VepResourceValidator.class); var readiness = mock(VepReadinessReport.class);
            when(readiness.isEnabled()).thenReturn(true); when(readiness.isExecutionEnabled()).thenReturn(true); when(readiness.getStatus()).thenReturn("READY");
            when(validator.validate(GenomeAssembly.GRCh38)).thenReturn(readiness);
            ExternalProcessRunner replay = (command, directory, timeout) -> {
                annotationRunnerCalls.incrementAndGet();
                Path input = Path.of(command.get(command.indexOf("--input_file") + 1));
                Path output = Path.of(command.get(command.indexOf("--output_file") + 1));
                var records = new ArrayList<JsonNode>();
                for (String line : Files.readAllLines(temporary.resolve("retained-real-vep.jsonl"))) if (!line.isBlank()) records.add(mapper.readTree(line));
                var emitted = new StringBuilder();
                for (String line : Files.readAllLines(input)) {
                    if (line.startsWith("#")) continue;
                    String[] fields = line.split("\\t");
                    var matches = records.stream().filter(r -> r.path("seq_region_name").asText().replace("chr", "").equals(fields[0].replace("chr", ""))
                            && r.path("start").asLong() == Long.parseLong(fields[1])
                            && r.path("allele_string").asText().equals(fields[3] + "/" + fields[4])).toList();
                    assertEquals(1, matches.size(), "Replay must match the preselected public allele, not manufacture annotation");
                    ObjectNode record = matches.get(0).deepCopy(); record.put("id", fields[2]);
                    emitted.append(mapper.writeValueAsString(record)).append('\n');
                }
                Files.writeString(output, emitted); return new ProcessResult(0, "SYNTHETIC_REPLAY_OF_RETAINED_REAL_VEP", false);
            };
            return new VepTranscriptAnnotationEnrichmentService(jobs, properties, validator, new VepCommandBuilder(properties),
                    new VepJsonOutputParser(mapper), replay, new TranscriptAnnotationService(properties, new AnnTranscriptAnnotationProvider(), new InMemoryTranscriptAnnotationCache(), validator));
        }
        var annotation = mock(VepTranscriptAnnotationEnrichmentService.class);
        when(annotation.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
            if (annotationUnavailable) throw new IllegalStateException("SYNTHETIC_ANNOTATION_UNAVAILABLE");
            List<VariantInterpretation> rows = call.getArgument(0);
            var values = new LinkedHashMap<String, List<TranscriptConsequence>>();
            for (var c : rows) {
                String gene = c.getExomiserCandidateFeatures().getGeneSymbol();
                var combined = new ArrayList<>(values.getOrDefault(c.getVariantId(), List.of()));
                combined.addAll("CDH1".equals(gene) ? List.of(new TranscriptConsequence("CDH1", "999", "NM_004360.5", "NM_004360.5:c.20A>T", "NP_004351.1:p.Glu7Val", "missense_variant")
                        .withAnnotationMetadata("SYNTHETIC_CSPEC_ANNOTATION", "COMPLETED", "1", java.time.Instant.parse("2026-09-01T00:00:00Z")))
                        : fixture.annotations.get("HBB".equals(gene) ? "public-hbb-compatible-e7v" : "public-gjb2-20189473-ct"));
                values.put(c.getVariantId(), combined);
            }
            return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(values, VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
        });
        return annotation;
    }
    @Test void annotationPreparationPreservesCompletedSameAlleleSibling() throws Exception {
        firstAlreadyAnnotated = true;
        variantTsvOverride = tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8", "2\t11\t2\tT\tA\tGJB2\t2706\tAR\t0.7");
        publish(false, GenomeAssembly.GRCh38, vcf("chr11\t2\t.\tT\tG,A\t.\tPASS\t.\tGT\t."));
        var rows = jobs.findInterpretations(analysisId, 0, 2);
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(fixture.annotations.get("public-hbb-compatible-e7v"))),
                mapper.readTree(mapper.writeValueAsBytes(rows.get(0).getTranscriptAnnotations())));
        assertEquals(2, postRequest(request).path("providerCalls").asInt());
    }
    @Test void wholeNumberScoresSurviveSnapshotRoundTripButChangedNumbersRemainBlocked() throws Exception {
        variantTsvOverride = tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t1.0", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        publish(false, GenomeAssembly.GRCh38, null);
        assertEquals(2, postRequest(request).path("providerCalls").asInt());
        ObjectNode snapshot = (ObjectNode) mapper.readTree(store.source(analysisId).orElseThrow().json());
        ((ObjectNode) snapshot.at("/candidates/0/published")).put("rankingScore", 0.999999);
        replaceSnapshot(snapshot);
        request.put("declarationVersion", "changed-number");
        var changed = postRequest(request);
        assertEquals(1, changed.path("providerCalls").asInt());
        assertTrue(changed.at("/candidates/0/attempts/0/blockers").toString().contains("EXECUTION_SNAPSHOT_CANDIDATE_CHANGED"));
    }
    @Test void bodyAndCollectionSizeLimitsAreAppliedBeforeExecution() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json")
                .content(new byte[AcmgSubmission.MAX_BYTES + 1])).andExpect(status().isPayloadTooLarge());
        var bad = request.deepCopy(); var entries = bad.putArray("hypotheses"); for (int i = 0; i < 201; i++) entries.add(request.path("hypotheses").get(0));
        http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json")
                .content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(bad)))).andExpect(status().isBadRequest());
        assertEquals(0, invocations.get()); assertTrue(store.runs(analysisId).isEmpty());
    }
    @Test void unavailablePublicationAnnotationsLeaveBaseResultsReadableAndCannotExecute() throws Exception {
        annotationUnavailable = true; publish(false, GenomeAssembly.GRCh38, null);
        var saved = postRequest(request); assertEquals(0, saved.path("providerCalls").asInt()); assertEquals(0, saved.path("evaluatorCalls").asInt());
        assertEquals(2, saved.path("candidates").size()); assertTrue(saved.toString().contains("TRANSCRIPT_ANNOTATION_NOT_MATCHED"));
        assertEquals(VariantAnalysisStatus.READY, jobs.findById(analysisId).orElseThrow().getStatus());
    }
    @Test void disabledBusinessDoesNotPrepareAnnotationsOrChangeCandidateRanking() throws Exception {
        annotationDisabled = true; publish(false, GenomeAssembly.GRCh38, null);
        assertTrue(jobs.findInterpretations(analysisId, 0, 2).stream().allMatch(c -> c.getTranscriptAnnotations().isEmpty()));
        assertEquals(0, postRequest(request).path("providerCalls").asInt());
    }
    ObjectNode declaration() {
        ObjectNode value = mapper.createObjectNode().put("topN", 2).put("declarationVersion", "synthetic-v1")
                .put("sampleId", "synthetic-proband").put("dataClass", "SYNTHETIC");
        value.putObject("patient").put("patientContextId", "patient-context-e4-b1-synthetic").put("role", "PROBAND").put("sex", "UNKNOWN")
                .set("source", declaredSource("private/synthetic-proband/patient"));
        var entries = value.putArray("hypotheses");
        for (var candidate : jobs.findInterpretations(analysisId, 0, 2)) {
            JsonNode choice = fixture.choices.path("fixedChoices").get("HBB".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol()) ? 0 : 1);
            var item = entries.addObject().put("affectedStatus", "AFFECTED");
            item.putObject("hypothesis").put("candidateId", candidate.getCandidateId()).put("hypothesisId", "local")
                    .put("hgncId", choice.path("hgncId").asText()).put("mondoId", choice.path("syntheticHypothesisMondoId").asText())
                    .put("moi", choice.path("syntheticHypothesisMoi").asText()).set("provenance", declaredSource("private/synthetic-proband/hypothesis"));
            String tx = choice.path("transcript").asText(); int dot = tx.lastIndexOf('.');
            item.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                    .put("selectionPolicy", choice.path("selectionPolicy").asText()).put("annotationSource", "VEP").put("annotationSourceVersion", "116.0")
                    .put("selectionReason", choice.path("selectionBasis").asText()).set("source", mapper.createObjectNode()
                            .put("source", "EXPLICIT_COMPATIBILITY_TEST_SELECTION").put("version", "1")
                            .put("recordLocator", "src/test/resources/e4-a2b/hbb-gjb2-explicit-compatible-v1.json#" + choice.path("fixtureId").asText()));
        }
        return value;
    }
    ObjectNode declaredSource(String locator) { return mapper.createObjectNode().put("source", "SYNTHETIC_CALLER_DECLARATION").put("version", "1.0.0").put("recordLocator", locator); }
    ObjectNode postRequest(ObjectNode body) throws Exception {
        return (ObjectNode) mapper.readTree(http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json").content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(body))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    @Test void ordinaryInputOptionsUseFormalRowsAndSourcesWithoutExecuting() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var before = mapper.valueToTree(jobs.findInterpretations(analysisId, 0, 100));
        var value = mapper.readTree(http.perform(get("/variant-analyses/{id}/acmg/input-options", analysisId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(20, value.path("requestedN").asInt());
        assertEquals(2, value.path("selectedCount").asInt());
        assertTrue(value.path("executionSourceRecovered").asBoolean(), value.toPrettyString());
        assertTrue(value.path("runtimeConfigured").asBoolean());
        assertEquals("synthetic-proband", value.path("sampleId").asText());
        assertEquals(0, value.path("providerCalls").asInt()); assertEquals(0, value.path("evaluatorCalls").asInt());
        assertTrue(store.runs(analysisId).isEmpty());
        for (int i = 0; i < 2; i++) {
            assertEquals(before.get(i).path("candidateId"), value.path("candidateIds").get(i));
            assertEquals(before.get(i).path("transcriptAnnotations"), value.at("/candidates/" + i + "/transcriptAnnotations"));
            assertEquals("CATALOG_REFERENCE_NOT_PATIENT_DIAGNOSIS", value.at("/candidates/" + i + "/catalog/claim").asText());
        }
        assertFalse(value.toString().contains(temporary.toString()));
        request.put("topN", 20).put("dataClass", "PRIVACY_BOUNDED_RUNTIME").put("declarationVersion", "ordinary-contract-v1");
        // Synthetic engine/REF here tests the ordinary request class, not a real patient acceptance.
        var saved = postRequest(request);
        assertEquals(2, saved.path("selectedCount").asInt()); assertEquals(2, saved.path("providerCalls").asInt());
        assertEquals(saved, postRequest(request));
        assertEquals(before, mapper.valueToTree(jobs.findInterpretations(analysisId, 0, 100)));
        assertEquals("PRIVACY_BOUNDED_RUNTIME", mapper.readTree(store.run(analysisId, saved.path("runId").asText()).orElseThrow().requestJson()).at("/declaration/dataClass").asText());
    }

    @Test void pm2MissingConfirmationDiagnosticsSurviveNormalSaveAndRead() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var population = new Pm2SupportingRuntime(mapper, properties);
        service = new AcmgBusinessService(mapper, jobs, store, sources, runtime(),
                () -> { throw new IllegalArgumentException("BA1_RUNTIME_NOT_CONFIGURED"); },
                AcmgRecommendations.unavailable(mapper), () -> population);
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
        var options = service.inputOptions(analysisId, 2);
        request.put("declarationVersion", "ordinary-ui-v3").putArray("hypotheses");
        var selections = request.putArray("recommendations");
        for (var row : options.path("candidates")) {
            var r = row.path("recommendation");
            var s = selections.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash")) s.set(field, r.path(field));
            s.putNull("stableRelationshipKey").putNull("annotationId");
        }
        var saved = postRequest(request);
        assertEquals(0, saved.at("/pm2Calls/provider").asInt(-1));
        assertEquals(0, saved.at("/pm2Calls/evaluator").asInt(-1));
        assertEquals(0, saved.at("/pm2Calls/allSitesBatch").asInt(-1));
        for (int i = 0; i < 2; i++) {
            var expected = options.at("/candidates/" + i + "/recommendation/pm2ContextPreparation/diagnostics");
            var actual = saved.at("/candidates/" + i + "/attempts/0/pm2");
            assertEquals(expected, actual.path("contextPreparation"));
            assertEquals(expected.path("reasonCode"), actual.at("/evaluationResult/reasonCode"));
            assertEquals(saved.at("/candidates/" + i + "/candidateId"), actual.at("/contextPreparation/candidateId"));
        }
        assertEquals(saved, service.read(analysisId, saved.path("runId").asText()));
        assertEquals(saved, postRequest(request));
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "101", "1.0", "true", "4294967297", "01"})
    void inputOptionsDoNotCoerceTopN(String invalid) throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        http.perform(get("/variant-analyses/{id}/acmg/input-options", analysisId).param("topN", invalid)).andExpect(status().isBadRequest());
        assertEquals(0, invocations.get()); assertTrue(store.runs(analysisId).isEmpty());
    }
    @Test void inputOptionsPreserveTwentyFormalRowsAndRejectIdentityOrOrderAnomalies() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById(analysisId)).thenReturn(jobs.findById(analysisId));
        var candidates = new ArrayList<VariantInterpretation>();
        var ranking = new ArrayList<PersistedRankingCandidate>();
        for (int i = 0; i < 20; i++) {
            var c = mock(VariantInterpretation.class);
            when(c.getCandidateId()).thenReturn("ordinary-engineering-row-" + i);
            when(c.getRankingScore()).thenReturn(1.0 - i * 0.01);
            candidates.add(c); ranking.add(PersistedRankingCandidate.from(c, i));
        }
        when(repository.findInterpretations(analysisId, 0, 20)).thenReturn(candidates);
        when(repository.findRankingPage(analysisId, 0, 20)).thenReturn(ranking);
        var reader = new AcmgBusinessService(mapper, repository, store, sources, () -> { throw new IllegalStateException("ACMG_BUSINESS_EXECUTION_DISABLED"); });
        var boundary = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(reader)).build();
        var value = reader.inputOptions(analysisId, 20);
        assertEquals(20, value.path("selectedCount").asInt());
        for (int i = 0; i < 20; i++) assertEquals(candidates.get(i).getCandidateId(), value.path("candidateIds").get(i).asText());
        Collections.swap(ranking, 0, 1);
        boundary.perform(get("/variant-analyses/{id}/acmg/input-options", analysisId)).andExpect(status().isConflict());
        Collections.swap(ranking, 0, 1);
        when(candidates.get(1).getCandidateId()).thenReturn("ordinary-engineering-row-0");
        ranking.set(1, PersistedRankingCandidate.from(candidates.get(1), 1));
        boundary.perform(get("/variant-analyses/{id}/acmg/input-options", analysisId)).andExpect(status().isConflict());
        assertTrue(store.runs(analysisId).isEmpty());
    }
    @Test void oldInputOptionsKeepCandidateRowsAndHistoryWithExplicitSourceGap() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var saved = postRequest(request);
        new JdbcTemplate(Objects.requireNonNull(dataSource)).update("DELETE FROM variant_analysis_acmg_source WHERE analysis_id=?", analysisId);
        var value = service.inputOptions(analysisId, 20);
        assertEquals(2, value.path("candidates").size()); assertFalse(value.path("executionSourceRecovered").asBoolean());
        assertTrue(value.toString().contains("EXECUTION_SOURCE_UNAVAILABLE"), value.toPrettyString());
        assertEquals(saved, service.read(analysisId, saved.path("runId").asText()));
    }
    @Test void submitPersistRecreateReadAndRepeatKeepOriginalIdentityAndPrivateProjection() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var original = mapper.valueToTree(jobs.findInterpretations(analysisId, 0, 100));
        ObjectNode first = postRequest(request);
        assertEquals(2, first.path("providerCalls").asInt(), first.toPrettyString()); assertEquals(2, first.path("bundles").size());
        assertEquals("COMPLETED_WITH_BLOCKERS", first.path("status").asText()); // Disabled engineering AM, genuine NOT_EVALUATED.
        assertEquals(first, postRequest(request)); assertEquals(1, invocations.get());
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).execute("SHUTDOWN");
        recreateRepositories(); recreateService(() -> { fail("Read must never execute"); return null; });
        var read = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysisId, first.path("runId").asText())).andExpect(status().isOk()).andReturn();
        assertEquals(first, mapper.readTree(read.getResponse().getContentAsByteArray()));
        assertEquals(first, postRequest(request));
        String json = first.toString(); for (String secret : List.of("synthetic-proband", "rawJson", "receivedAt", "selectionReason", temporary.toString())) assertFalse(json.contains(secret), secret);
        assertEquals(original, mapper.valueToTree(jobs.findInterpretations(analysisId, 0, 100)));
        var candidate = http.perform(get("/variant-analyses/{id}/candidates/{candidate}/acmg", analysisId, first.path("candidateIds").get(0).asText()).param("runId", first.path("runId").asText())).andExpect(status().isOk()).andReturn();
        assertEquals(first.path("bundles").get(0), mapper.readTree(candidate.getResponse().getContentAsByteArray()).path("bundles").get(0));
        export(first, "engineering");
    }
    @ParameterizedTest @ValueSource(strings = {"4294967297", "-4294967295", "9223372036854775808123", "0", "-1", "1.5", "true", "\"1\"", "null"})
    void strictHttpVersionDoesNotCoerce(String invalid) throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        ((ObjectNode) request.at("/hypotheses/0/transcript")).set("version", mapper.readTree(invalid));
        if (invalid.equals("null")) { assertEquals(1, postRequest(request).path("providerCalls").asInt()); }
        else http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json").content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(request)))).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings = {"1.5", "true", "\"1\"", "4294967297", "0", "101", "null"})
    void strictTopN(String invalid) throws Exception {
        publish(false, GenomeAssembly.GRCh38, null); request.set("topN", mapper.readTree(invalid));
        http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json").content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(request)))).andExpect(status().isBadRequest());
        assertEquals(0, store.runs(analysisId).size());
    }
    @Test void clientCannotSubmitWitnessContextOrPathsAndDuplicateKeysFail() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        for (String key : List.of("executionSource", "context", "normalization", "binding", "resourcePath", "analysisId")) {
            ObjectNode bad = request.deepCopy(); bad.put(key, "READY");
            http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json").content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(bad)))).andExpect(status().isBadRequest());
        }
        http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json").content(java.util.Objects.requireNonNull(request.toString().replace("\"topN\":2", "\"topN\":2,\"topN\":1")))).andExpect(status().isBadRequest());
        assertEquals(0, invocations.get());
    }
    @Test void duplicateConcurrencyRevisionAndFailureRetry() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); Supplier<ExplicitCaseInputCoordinator> delegate = runtime();
        recreateService(() -> { entered.countDown(); try { assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new RuntimeException(ex); } return delegate.get(); });
        var executor = Executors.newSingleThreadExecutor(); byte[] body = mapper.writeValueAsBytes(request);
        try {
            var running = executor.submit(() -> service.submit(analysisId, body)); assertTrue(entered.await(20, TimeUnit.SECONDS));
            var second = new AcmgBusinessService(mapper, jobs, new AcmgBusinessStore(dataSource), sources, () -> { fail("Concurrent duplicate must not run"); return null; });
            assertEquals("RUNNING", second.submit(analysisId, body).path("status").asText());
            release.countDown(); var saved = running.get(30, TimeUnit.SECONDS); assertEquals(saved, second.submit(analysisId, body));
            recreateService(runtime()); request.put("declarationVersion", "synthetic-v2");
            var revision = postRequest(request); assertNotEquals(saved.path("runId"), revision.path("runId"));
            request.put("retryOf", revision.path("runId").asText()); var retried = postRequest(request);
            assertNotEquals(revision.path("runId"), retried.path("runId")); assertEquals(3, invocations.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void retryIdentityUsesRequestCanonicalizationButRejectsChangedInputs() throws Exception {
        recreateService(runtime());
        var original = mapper.createObjectNode(); original.putObject("declaration").put("topN",19);
        original.putObject("executionProfile").put("count",1L);
        var restored = (ObjectNode) mapper.readTree(mapper.writeValueAsString(original));
        assertNotEquals(original,restored); // LongNode becomes IntNode, with unchanged JSON meaning.
        ((ObjectNode) original.path("declaration")).put("retryOf","old-run");
        assertTrue(service.sameRetryIdentity(restored,original));
        ((ObjectNode) original.path("declaration")).put("topN",18);
        assertFalse(service.sameRetryIdentity(restored,original));
        assertFalse(restored.path("declaration").has("retryOf"));
    }
    @Test void failedRunExposesOnlyVerifiedRetryScope() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var identity = mapper.createObjectNode();
        identity.putObject("executionProfile").put("profileId", "test-current-scope");
        identity.putObject("declaration").put("privateCase", "never expose this");
        String id = UUID.randomUUID().toString();
        assertTrue(store.claim(new AcmgBusinessStore.Run(id, analysisId, new AcmgBusinessArtifacts(mapper).requestHash(identity),
                mapper.writeValueAsString(identity), "RUNNING", "now", null, null, null)));
        store.failed(id);
        var read = service.read(analysisId, id);
        assertEquals(identity.path("executionProfile"), read.path("retryExecutionProfile"));
        assertFalse(read.path("resultsAvailable").asBoolean()); assertFalse(read.toString().contains("privateCase"));
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update("UPDATE variant_analysis_acmg_run SET request_json='{}' WHERE run_id=?", id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.read(analysisId, id));
    }
    @Test void failedSaveCannotReportSuccessAndExplicitRetryCreatesNewRun() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var failing = new AcmgBusinessStore(dataSource) { @Override public void finish(String id, String status, String response, String privateJson) { throw new IllegalStateException("synthetic storage failure"); } };
        service = new AcmgBusinessService(mapper, jobs, failing, sources, runtime()); http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
        http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json").content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(request)))).andExpect(status().isServiceUnavailable());
        var old = store.runs(analysisId).get(0); assertEquals("FAILED", old.status()); assertNull(old.responseJson());
        recreateService(runtime()); assertEquals("FAILED", postRequest(request).path("status").asText()); assertEquals(1, invocations.get());
        request.put("retryOf", old.id()); assertEquals(2, postRequest(request).path("providerCalls").asInt()); assertEquals(2, invocations.get());
    }
    @Test void resultAboveOld128MiBLimitRetainsSourcesAcrossRestartHistoryAndExport() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var expectedPrivateSha = new java.util.concurrent.atomic.AtomicReference<String>();
        store = new AcmgBusinessStore(dataSource) {
            @Override public void finish(String id, String state, String publicJson, String privateJson) {
                try {
                    var details = (ObjectNode) mapper.readTree(privateJson);
                    var originals = details.putArray("largeSourceOriginalsForStorageRegression");
                    // Distinct source records with exact quotes/locators, not one oversized JSON string.
                    String quote = "原文😀 quoted observation\\n".repeat(50000);
                    for (int i = 0; i < 85; i++) originals.addObject().put("locator", "paper/table/" + i).put("quote", quote);
                    String complete = mapper.writeValueAsString(details);
                    assertTrue(complete.getBytes(StandardCharsets.UTF_8).length > 128L * 1024 * 1024);
                    expectedPrivateSha.set(AcmgBusinessStore.sha(complete));
                    var result = (ObjectNode) mapper.readTree(publicJson);
                    result.put("privateArtifactSha256", expectedPrivateSha.get());
                    super.finish(id, state, mapper.writeValueAsString(result), complete);
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }
        };
        sources = new AcmgExecutionSources(mapper, store); recreateService(runtime());
        var saved = postRequest(request); String runId = saved.path("runId").asText();
        assertEquals(1, invocations.get());
        // Close the file database and recreate repositories, service and HTTP controller.
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).execute("SHUTDOWN"); recreateRepositories(); recreateService(runtime());
        var restored = store.run(analysisId, runId).orElseThrow();
        assertEquals(expectedPrivateSha.get(), AcmgBusinessStore.sha(restored.privateJson()));
        var originals = mapper.readTree(restored.privateJson()).path("largeSourceOriginalsForStorageRegression");
        assertEquals(85, originals.size()); assertEquals("paper/table/84", originals.get(84).path("locator").asText());
        var history = http.perform(get("/variant-analyses/{id}/acmg/runs", analysisId)).andExpect(status().isOk()).andReturn();
        assertEquals(runId, mapper.readTree(history.getResponse().getContentAsByteArray()).get(0).path("runId").asText());
        var read = http.perform(get("/variant-analyses/{id}/acmg/runs/{runId}", analysisId, runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(saved, mapper.readTree(read));
        // The workbench export serializes this full GET result; no details are projected away.
        var export = temporary.resolve("acmg-" + runId + ".json"); Files.write(export, read);
        assertEquals(saved, mapper.readTree(export.toFile()));
        assertEquals(saved, postRequest(request)); assertEquals(1, invocations.get(), "read/export/repeat must not re-evaluate");
    }
    @Test void modelExecutionReleasesJdbcConnectionAndReadbackFollowsCommit() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var borrowed = new AtomicInteger();
        var tracked = new DelegatingDataSource(Objects.requireNonNull(dataSource)) {
            @Override @org.springframework.lang.NonNull public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection(); borrowed.incrementAndGet();
                var closed = new java.util.concurrent.atomic.AtomicBoolean();
                return Objects.requireNonNull((java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            try { return method.invoke(connection, args); }
                            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            finally { if (method.getName().equals("close") && closed.compareAndSet(false, true)) borrowed.decrementAndGet(); }
                        }));
            }
        };
        store = new AcmgBusinessStore(tracked) {
            @Override public Optional<Run> run(String analysis, String id) {
                var result = super.run(analysis, id);
                if (result.isPresent() && result.get().responseJson() != null)
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                            "A successful response must be read after commit, not from the pending transaction");
                return result;
            }
        };
        sources = new AcmgExecutionSources(mapper, store);
        Supplier<ExplicitCaseInputCoordinator> delegate = runtime();
        recreateService(() -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, borrowed.get(), "Long model work must not hold an idle JDBC connection");
            return delegate.get();
        });
        var saved = postRequest(request);
        assertEquals(saved, postRequest(request)); assertEquals(1, invocations.get());
        assertEquals(0, borrowed.get());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void commitConnectionFailurePreservesDurableOutcomeWithoutAutomaticEvaluationRetry(boolean committed) throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var failCommit = new java.util.concurrent.atomic.AtomicBoolean(false);
        var disconnected = new DelegatingDataSource(Objects.requireNonNull(dataSource)) {
            @Override @org.springframework.lang.NonNull public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return Objects.requireNonNull((java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("commit") && failCommit.compareAndSet(true, false)) {
                                // Both possible outcomes of losing the connection during COMMIT: rollback,
                                // or the server committed but its acknowledgement never reached the client.
                                if (committed) connection.commit(); else connection.rollback();
                                throw new java.sql.SQLException("Synthetic connection reset during commit", "08S01");
                            }
                            try { return method.invoke(connection, args); }
                            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        }));
            }
        };
        store = new AcmgBusinessStore(disconnected);
        var delegate = runtime();
        recreateService(() -> { failCommit.set(true); return delegate.get(); }); // Fail publication, after the lock precheck.
        var outcome = http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json")
                .content(Objects.requireNonNull(mapper.writeValueAsBytes(request)))).andExpect(status().isServiceUnavailable()).andReturn();
        assertTrue(Objects.requireNonNull(outcome.getResolvedException(), "resolved exception").getMessage().contains("ACMG_EXECUTION_OR_SAVE_FAILED"));
        assertFalse(failCommit.get());
        var record = store.runs(analysisId).get(0);
        assertNotEquals("RUNNING", record.status());
        var saved = service.read(analysisId, record.id());
        assertEquals(saved, postRequest(request)); assertEquals(1, invocations.get());
        if (committed) {
            assertTrue(saved.has("privateArtifactSha256"), "Never overwrite an already committed result with FAILED");
            assertEquals(2, saved.path("selectedCount").asInt());
        } else {
            assertEquals("FAILED", saved.path("status").asText()); assertFalse(saved.path("resultsAvailable").asBoolean());
            recreateRepositories(); recreateService(runtime()); request.put("retryOf", record.id());
            var retry = postRequest(request);
            assertNotEquals(record.id(), retry.path("runId").asText()); assertEquals(2, invocations.get());
        }
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    void boundedLockWaitReportsBusyAndPreservesExplicitRetrySemantics(int failingLock) throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var lockCalls = new AtomicInteger();
        var failAt = new AtomicInteger(failingLock);
        var rollbacks = new AtomicInteger();
        var waitingDatabase = new DelegatingDataSource(Objects.requireNonNull(dataSource)) {
            @Override @org.springframework.lang.NonNull public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return Objects.requireNonNull((java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            try {
                                if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
                                var value = method.invoke(connection, args);
                                if (method.getName().equals("prepareStatement") && ((String) args[0]).endsWith("FOR UPDATE")) {
                                    var statement = (java.sql.PreparedStatement) value;
                                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                            new Class<?>[]{java.sql.PreparedStatement.class}, (p, m, a) -> {
                                                if (m.getName().equals("executeQuery")) {
                                                    assertEquals(30, statement.getQueryTimeout(), "Actual locking statement must have a bounded JDBC deadline");
                                                    if (lockCalls.incrementAndGet() == failAt.get())
                                                        throw new java.sql.SQLTimeoutException("Synthetic metadata lock wait", "S1T00");
                                                }
                                                try { return m.invoke(statement, a); }
                                                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                            });
                                }
                                return value;
                            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        }));
            }
        };
        store = new AcmgBusinessStore(waitingDatabase); recreateService(runtime());
        var response = http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId).contentType("application/json")
                .content(Objects.requireNonNull(mapper.writeValueAsBytes(request)))).andExpect(status().isServiceUnavailable()).andReturn();
        assertEquals("ACMG_DATABASE_BUSY", mapper.readTree(response.getResponse().getContentAsByteArray()).path("message").asText());
        assertEquals(1, rollbacks.get());
        assertEquals(failingLock - 1, invocations.get(), "Busy precheck must not call a model; publication failure must not automatically rerun it");
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        if (failingLock == 1) {
            assertTrue(store.runs(analysisId).isEmpty(), "Precheck failure must not strand a RUNNING claim");
        } else {
            var record = store.runs(analysisId).get(0);
            var failed = service.read(analysisId, record.id());
            assertEquals("FAILED", failed.path("status").asText());
            assertFalse(failed.path("resultsAvailable").asBoolean());
            assertEquals(failed, postRequest(request));
            assertEquals(1, invocations.get());
            request.put("retryOf", record.id());
        }
        var recovered = postRequest(request);
        assertEquals(failingLock, invocations.get());
        int completedLockCalls = lockCalls.get();
        failAt.set(completedLockCalls + 1);
        assertEquals(recovered, postRequest(request), "Persisted duplicate must bypass a newly blocked publication lock");
        assertEquals(completedLockCalls, lockCalls.get());
        assertEquals(failingLock, invocations.get());
    }
    @ParameterizedTest @ValueSource(strings = {"4294967297", "-4294967295", "9223372036854775808123", "0", "-1", "1.5", "true", "\"1\"", "null", "2"})
    void recoveredR1OrdinalBlocksOnlyCurrentCandidateAfterRehash(String ordinal) throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        ObjectNode snapshot = (ObjectNode) mapper.readTree(store.source(analysisId).orElseThrow().json());
        ((ObjectNode) snapshot.at("/candidates/0/matchedRecord")).set("recordOrdinal", mapper.readTree(ordinal));
        replaceSnapshot(snapshot); var saved = postRequest(request);
        assertEquals(1, saved.path("providerCalls").asInt(), saved.toPrettyString());
        assertTrue(saved.toString().contains("ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED") || saved.toString().contains("MATCHED_RECORD"));
        assertTrue(saved.at("/candidates/0/attempts/0/context").isNull()); assertFalse(saved.at("/candidates/1/attempts/0/context").isNull());
    }
    void replaceSnapshot(ObjectNode value) throws Exception {
        String json = mapper.writeValueAsString(value); new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update("UPDATE variant_analysis_acmg_source SET snapshot_json=?,snapshot_sha256=? WHERE analysis_id=?", json, AcmgBusinessStore.sha(json), analysisId);
    }
    @Test void oldCorruptAndTaskMismatchSourcesNeverRunButBaseCandidatesStayReadable() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null); var saved = store.source(analysisId).orElseThrow();
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update("UPDATE variant_analysis_acmg_source SET snapshot_json='{}' WHERE analysis_id=?", analysisId);
        var corrupt = postRequest(request); assertEquals(0, corrupt.path("providerCalls").asInt()); assertTrue(corrupt.toString().contains("EXECUTION_SNAPSHOT_HASH_MISMATCH"));
        ObjectNode mismatch = (ObjectNode) mapper.readTree(saved.json()); mismatch.put("analysisId", "wrong"); replaceSnapshot(mismatch); request.put("declarationVersion", "task-mismatch");
        assertTrue(postRequest(request).toString().contains("EXECUTION_SNAPSHOT_TASK_MISMATCH"));
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update("DELETE FROM variant_analysis_acmg_source WHERE analysis_id=?", analysisId); request.put("declarationVersion", "old-task");
        assertTrue(postRequest(request).toString().contains("EXECUTION_SOURCE_UNAVAILABLE")); assertEquals(2, jobs.findInterpretations(analysisId, 0, 2).size());
        assertEquals(0, invocations.get());
    }
    @ParameterizedTest @ValueSource(strings = {"outputSha256", "engineVersion", "resourceFingerprint", "rankingMode"})
    void rehashedSnapshotMustMatchCurrentPersistedExecution(String field) throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        ObjectNode value = (ObjectNode) mapper.readTree(store.source(analysisId).orElseThrow().json());
        value.put(field, "outputSha256".equals(field) ? "f".repeat(64) : "different-execution"); replaceSnapshot(value);
        var saved = postRequest(request); assertTrue(saved.toString().contains("EXECUTION_SNAPSHOT_EXECUTION_MISMATCH"));
        assertEquals(0, saved.path("providerCalls").asInt()); assertEquals(0, saved.path("evaluatorCalls").asInt());
        assertEquals(0, invocations.get());
    }
    @Test void candidateAnnotationMismatchIsIsolatedAndSampleMismatchIsBlocked() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null); ObjectNode value = (ObjectNode) mapper.readTree(store.source(analysisId).orElseThrow().json());
        ((ObjectNode) value.at("/candidates/0/published/transcriptAnnotations/0")).put("version", "wrong-annotation-source-version"); replaceSnapshot(value);
        assertEquals(1, postRequest(request).path("providerCalls").asInt());
        request.put("sampleId", "wrong-sample").put("declarationVersion", "wrong-sample"); assertEquals(0, postRequest(request).path("providerCalls").asInt());
    }
    @Test void refFailureConversionAndMissingHypothesisDoNotRunOrRefill() throws Exception {
        publish(false, GenomeAssembly.GRCh37, null); assertEquals(0, postRequest(request).path("providerCalls").asInt());
        publish(false, GenomeAssembly.GRCh38, null); Files.writeString(temporary.resolve("tiny.fa"), ">chr11\nAACG\n>chr13\nAACT\n");
        assertEquals(0, postRequest(request).path("providerCalls").asInt()); // Trusted reference applicability changed.
        producer = fixture.tinyReference(r -> {}).producer(); request.put("declarationVersion", "missing-hypothesis"); ((ArrayNode) request.path("hypotheses")).remove(0);
        var saved = postRequest(request); assertEquals(2, saved.path("selectedCount").asInt()); assertEquals(1, saved.path("providerCalls").asInt()); assertEquals(2, saved.path("candidates").size());
    }
    @Test void multiHypothesesAndDiseaseConflictRetainRowsAndUnrelatedDisease() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        ObjectNode first = (ObjectNode) request.at("/hypotheses/0"), extra = first.deepCopy();
        ((ObjectNode) extra.path("hypothesis")).put("hypothesisId", "other-local").put("mondoId", "MONDO:0099999"); extra.put("affectedStatus", "UNAFFECTED");
        ((ArrayNode) request.path("hypotheses")).add(extra);
        assertEquals(3, postRequest(request).path("providerCalls").asInt());
        request.put("declarationVersion", "conflict"); ObjectNode conflict = first.deepCopy(); conflict.put("affectedStatus", "UNAFFECTED"); ((ObjectNode) conflict.path("hypothesis")).put("hypothesisId", "conflict"); ((ArrayNode) request.path("hypotheses")).add(conflict);
        var saved = postRequest(request); assertEquals(2, saved.path("providerCalls").asInt()); assertEquals(2, saved.path("candidates").size());
    }
    @Test void sameAlleleDifferentCandidatesAndAltTwoAreNotMerged() throws Exception {
        variantTsvOverride = tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8", "2\t11\t2\tT\tA\tGJB2\t2706\tAR\t0.7");
        publish(false, GenomeAssembly.GRCh38, vcf("chr11\t2\t.\tT\tG,A\t.\tPASS\t.\tGT\t."));
        var saved = postRequest(request); assertEquals(2, saved.path("providerCalls").asInt(), saved.toPrettyString());
        assertNotEquals(saved.path("candidateIds").get(0), saved.path("candidateIds").get(1));
        assertEquals(saved.at("/bundles/0/bundle/context/variant/alleleId"), saved.at("/bundles/1/bundle/context/variant/alleleId"));
        assertNotEquals(saved.at("/bundles/0/bundle/context/contextId"), saved.at("/bundles/1/bundle/context/contextId"));
        var privateData = mapper.readTree(store.run(analysisId, saved.path("runId").asText()).orElseThrow().privateJson());
        assertTrue(privateData.toString().contains("selectedAltIndex\\\":2"));
    }
    @Test void cspecMetadataGateHasZeroProviderAndEvaluatorCallsAndNoBundle() throws Exception {
        variantTsvOverride = tsv("1\t11\t2\tT\tA\tCDH1\t999\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        publish(false, GenomeAssembly.GRCh38, null);
        request.put("topN", 1); ((ArrayNode) request.path("hypotheses")).remove(1);
        ObjectNode h = (ObjectNode) request.at("/hypotheses/0/hypothesis"); h.put("hgncId", "HGNC:1748").put("mondoId", "MONDO:0007648").put("moi", "AD");
        ObjectNode tx = (ObjectNode) request.at("/hypotheses/0/transcript"); tx.put("accession", "NM_004360").put("version", 5).put("selectionPolicy", "EXPLICIT_SYNTHETIC_CSPEC_TEST")
                .put("annotationSource", "SYNTHETIC_CSPEC_ANNOTATION").put("annotationSourceVersion", "1");
        var saved = postRequest(request); assertEquals(0, saved.path("providerCalls").asInt()); assertEquals(0, saved.path("evaluatorCalls").asInt());
        assertTrue(saved.toString().contains("CSPEC_MATCH_NOT_EXECUTABLE"), saved.toPrettyString()); assertEquals(0, saved.path("bundles").size());
    }
    @Test void runtimeConfigurationRegistrationAndDisabledFallback() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var config = new AcmgBusinessConfig();
        service = config.acmgBusinessService(mapper, jobs, store, sources, properties, new org.springframework.mock.env.MockEnvironment());
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
        assertTrue(postRequest(request).toString().contains("ACMG_BUSINESS_EXECUTION_DISABLED"));
    }
    @Test void savedResultCorruptionDoesNotReturnSuccess() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null); var saved = postRequest(request);
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update("UPDATE variant_analysis_acmg_run SET private_json='{}' WHERE run_id=?", saved.path("runId").asText());
        http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysisId, saved.path("runId").asText())).andExpect(status().isServiceUnavailable());
        assertEquals(1, invocations.get());
    }
    @Test void providerFailureIsPersistedAsNotEvaluatedAndDoesNotStopSibling() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var provider = new AlphaMissenseProvider(new VariantAnalysisProperties()) {
            int calls;
            @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedTranscripts(List<VariantInterpretation> rows, AnalysisContext context,
                    Map<NormalizedVariantKey, TranscriptConsequence> transcripts) {
                if (++calls == 1) throw new IllegalStateException("synthetic provider failure");
                return super.annotateSelectedTranscripts(rows, context, transcripts);
            }
        };
        recreateService(() -> new ExplicitCaseInputCoordinator(mapper, fixture.resolver, producer, fixture.sources, new ExplicitAlphaMissenseDispatcher(mapper, provider)));
        var saved = postRequest(request); assertEquals(2, saved.path("providerCalls").asInt()); assertEquals(2, saved.path("evaluatorCalls").asInt());
        assertEquals("NOT_EVALUATED", saved.at("/candidates/0/attempts/0/criterionEvaluations/0/evaluationResult/status").asText());
        assertEquals("FAILED", saved.at("/candidates/0/attempts/0/atomicEvidence/0/resourceExecution/status").asText());
        assertEquals(2, saved.path("bundles").size());
    }
    @Test void analysisDeletionAlsoRemovesPrivateDeclarationAndSource() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null); postRequest(request);
        jobs.deleteById(analysisId);
        assertTrue(store.source(analysisId).isEmpty()); assertTrue(store.runs(analysisId).isEmpty());
    }
    @Test void fileKeyApplicabilityAndUnavailableFileKeyAreExplicit() throws Exception {
        publish(false, GenomeAssembly.GRCh38, null);
        var snapshot = mapper.readTree(store.source(analysisId).orElseThrow().json());
        if (snapshot.path("fileKey").isNull()) {
            // This Windows filesystem exposes no key. Do not claim a physical replacement test on it.
            ((ObjectNode) snapshot).put("fileKey", "INVALID_BORROWED_FILE_IDENTITY"); replaceSnapshot((ObjectNode) snapshot);
            var blocked = postRequest(request); assertEquals(0, blocked.path("providerCalls").asInt());
            assertTrue(blocked.toString().contains("EXECUTION_VCF_ARTIFACT_CHANGED")); return;
        }
        Path vcf = prepared.conversion().getOutputFile(), replacement = vcf.resolveSibling("replacement-owned-test.vcf");
        var time = Files.getLastModifiedTime(vcf); Files.write(replacement, Files.readAllBytes(vcf)); Files.setLastModifiedTime(replacement, time);
        Files.move(replacement, vcf, StandardCopyOption.REPLACE_EXISTING);
        var saved = postRequest(request); assertEquals(0, saved.path("providerCalls").asInt()); assertTrue(saved.toString().contains("EXECUTION_VCF_ARTIFACT_CHANGED"));
    }
    @Test void invalidSameAlleleSourceDoesNotPolluteValidSibling() throws Exception {
        variantTsvOverride = tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8", "2\t11\t2\tT\tA\tGJB2\t2706\tAR\t0.7");
        publish(false, GenomeAssembly.GRCh38, vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."));
        ObjectNode snapshot = (ObjectNode) mapper.readTree(store.source(analysisId).orElseThrow().json());
        ((ObjectNode) snapshot.at("/candidates/0/matchedRecord")).put("recordOrdinal", 2); replaceSnapshot(snapshot);
        assertEquals(1, postRequest(request).path("providerCalls").asInt());
    }
    @Test @EnabledIfSystemProperty(named="varvis.e4b1.local-smoke", matches="true")
    void realHbbGjb2BusinessSubmitPersistAndApiReadback() throws Exception {
        real = true; Path binding = Path.of(System.getProperty("varvis.e4a3a.binding-resource-dir"));
        fixture.resolver = PatientDiseaseContextBindingResolver.load(mapper, binding.resolve("manifest.json"), binding.resolve("mane-crosswalk.json"), binding.resolve("clingen-catalog.json"));
        Path root = Path.of(System.getProperty("varvis.e4a3b.resource-root")); var settings = new VariantAnalysisProperties();
        var checksum = fixture.sources.get(0).resolve(fixture.reference).path("actualChecksumReceipt");
        settings.getNormalization().setGrch38Reference(root.resolve(checksum.path("logicalName").asText()).toString()); settings.getNormalization().setGrch38ReferenceSha256(checksum.path("expectedSha256").asText()); settings.getNormalization().setGrch38ProfileId("grch38-primary-chr");
        producer = new NativeSnvSourceProducer(mapper, settings, root, List.of(fixture.sources.get(0)), fixture.reference);
        publish(true, GenomeAssembly.GRCh38, null);
        assertEquals(1, annotationRunnerCalls.get(), "The actual publication annotation service must consume the retained public VEP records in one bounded batch");
        // Use the real Spring registration and server-only resource settings for the business smoke.
        properties.getNormalization().setGrch38Reference(settings.getNormalization().getGrch38Reference().toString());
        properties.getNormalization().setGrch38ReferenceSha256(settings.getNormalization().getGrch38ReferenceSha256());
        properties.getNormalization().setGrch38ProfileId("grch38-primary-chr");
        properties.getAlphaMissense().setEnabled(true); properties.getAlphaMissense().setVersion("2023-08-03");
        properties.getAlphaMissense().setGrch38File(System.getProperty("varvis.e4a3a.alphamissense-grch38")); properties.getAlphaMissense().setTimeout(Duration.ofSeconds(30));
        var holder = new java.util.concurrent.atomic.AtomicReference<ObjectNode>();
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(AcmgBusinessConfig.class)
                .withBean("varvisDataSource", javax.sql.DataSource.class, () -> dataSource)
                .withBean(ObjectMapper.class, () -> mapper).withBean(VariantAnalysisProperties.class, () -> properties)
                .withBean(VariantAnalysisJobRepository.class, () -> jobs)
                .withPropertyValues("variant-analysis.acmg-business.enabled=true",
                        "variant-analysis.acmg-business.binding-directory=" + binding,
                        "variant-analysis.acmg-business.resource-root=" + root,
                        "variant-analysis.acmg-business.reference-receipt-file=" + temporary.resolve("reference-ledger.json"),
                        "variant-analysis.acmg-business.reference-receipt-sha256=" + sha(Files.readAllBytes(temporary.resolve("reference-ledger.json"))),
                        "variant-analysis.acmg-business.reference-receipt-locator=reference")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    service = context.getBean(AcmgBusinessService.class);
                    http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
                    holder.set(postRequest(request)); assertEquals(holder.get(), postRequest(request));
                });
        var saved = holder.get(); export(saved, "real-exact");
        assertEquals("COMPLETED", saved.path("status").asText(), saved.toPrettyString()); assertEquals(2, saved.path("providerCalls").asInt()); assertEquals(2, saved.path("evaluatorCalls").asInt());
        assertEquals(2, saved.path("bundles").size());
        for (var row : saved.path("candidates")) {
            var attempt = row.path("attempts").get(0); assertEquals("ZERO_MATCH", attempt.at("/selection/outcome").asText());
            for (String metric : List.of("exactAlleleMatch", "transcriptProteinMatch")) {
                var matches = new ArrayList<JsonNode>(); attempt.at("/atomicEvidence/0/measurements").forEach(m -> { if (("alphamissense." + metric).equals(m.path("metricId").asText())) matches.add(m); });
                assertEquals(1, matches.size()); assertTrue(matches.get(0).path("rawValue").booleanValue()); assertTrue(matches.get(0).path("normalizedValue").booleanValue());
            }
            attempt.path("criterionEvaluations").forEach(e -> assertEquals("COMPLETED", e.at("/deterministicTrace/executionStatus").asText()));
        }
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).execute("SHUTDOWN");
        recreateRepositories(); recreateService(() -> { fail("Readback cannot execute resources"); return null; });
        var read = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysisId, saved.path("runId").asText())).andExpect(status().isOk()).andReturn();
        assertEquals(saved, mapper.readTree(read.getResponse().getContentAsByteArray())); assertEquals(saved, postRequest(request));
        assertEquals(0, invocations.get(), "Real smoke uses Spring configuration, not the engineering runtime supplier");
    }
    void export(ObjectNode saved, String label) throws Exception {
        String directory = System.getProperty("varvis.e4b1.output-dir"); if (directory == null) return;
        var run = store.run(analysisId, saved.path("runId").asText()).orElseThrow();
        ObjectNode archive = mapper.createObjectNode().set("apiResult", saved);
        archive.put("persistedResponseRaw", run.responseJson()).put("persistedResponseSha256", run.responseSha256())
                .put("persistedPrivateRaw", run.privateJson());
        archive.set("explicitSelectionProvenance", fixture.choices.deepCopy());
        ((ObjectNode) archive.path("explicitSelectionProvenance")).put("vepOutputSha256", "741f0001460957669c1bc5661908f0eabf86fc32e21e050f3e724074c5eb574d");
        ((ObjectNode) archive.path("explicitSelectionProvenance")).put("annotationUse", real ? "RETAINED_REAL_VEP_FIXED_ALLELES_NO_NEW_VEP_RUN" : "SYNTHETIC_ENGINEERING_REUSE_OF_TRANSCRIPT_FIELDS");
        archive.putObject("candidateInputFixture").put("annotationPreparation", real ? "ACTUAL_VEP_SERVICE_AND_PARSER_WITH_SYNTHETIC_RETAINED_RECORD_REPLAY" : "SYNTHETIC_ANNOTATION_PROVIDER")
                .put("syntheticVepRunnerCalls", annotationRunnerCalls.get()).put("runner", "SYNTHETIC_TSV_RUNNER_NOT_REAL_EXOMISER").put("patientDataClass", "SYNTHETIC").put("rankingDataClass", "SYNTHETIC")
                .put("rawAnalysisVcf", Files.readString(prepared.conversion().getOutputFile())).put("rawVariantTsv", Files.readString(
                        Path.of(mapper.readTree(store.source(analysisId).orElseThrow().json()).path("workDirectory").asText()).resolve("output/result.variants.tsv")));
        ObjectNode f = (ObjectNode) archive.path("candidateInputFixture"); f.put("variantTsvSha256", sha(f.path("rawVariantTsv").asText().getBytes(StandardCharsets.UTF_8)))
                .put("analysisVcfSha256", prepared.job().getFileSha256()).put("originalRecordNullCount", 2);
        var snapshot = store.source(analysisId).orElseThrow(); archive.put("sourceSnapshotRaw", snapshot.json()).put("sourceSnapshotSha256", snapshot.sha256());
        Path file = Path.of(directory).resolve("e4-b1-" + label + ".json"); Files.createDirectories(file.getParent());
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(archive).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
