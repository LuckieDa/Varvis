package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual publication/recovery and file H2. The upstream Exomiser transport replays TEST_ONLY TSV. */
class Ps2BusinessIntegrationTest {
    final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
    Path temporary, material, archive;
    String analysisId;
    DriverManagerDataSource dataSource;
    VariantAnalysisProperties properties;
    JdbcVariantAnalysisJobRepository jobs;
    AcmgBusinessStore store;
    AcmgExecutionSources sources;
    AcmgBusinessService service;
    MockEnvironment env;
    MockMvc http;
    ObjectNode bundle;
    final AtomicInteger core = new AtomicInteger(), prepares = new AtomicInteger(), loads = new AtomicInteger(), forbidden = new AtomicInteger();
    int artifactIndex;
    boolean decoyFirst;
    String probandFormat = "GT:DP:GQ:AD", probandSample = "0/1:40:60:21,19";
    @BeforeEach void setup() throws Exception {
        temporary = Ps2TestDirectory.create("business");
        archive = Path.of(System.getProperty("varvis.ps2.artifacts")).resolve(temporary.getFileName()); Files.createDirectories(archive);
        properties = new VariantAnalysisProperties(); properties.setStorageRoot(temporary.resolve("jobs")); properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        properties.getPersistence().setRetryBackoff(java.time.Duration.ZERO);
        dataSource = new DriverManagerDataSource("jdbc:h2:file:" + temporary.resolve("business-db").toString().replace('\\', '/')
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/variant-analysis-schema.sql"), new ClassPathResource("db/exomiser-analysis-schema.sql"),
                new ClassPathResource("db/acmg-business-schema.sql")).execute(dataSource); repositories();
    }
    void repositories() {
        jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(java.util.Objects.requireNonNull(dataSource)), new DataSourceTransactionManager(java.util.Objects.requireNonNull(dataSource)), properties, mapper);
        store = new AcmgBusinessStore(dataSource); sources = new AcmgExecutionSources(mapper, store);
    }
    @AfterEach void close() throws Exception {
        if (dataSource != null) { new JdbcTemplate(dataSource).execute("SHUTDOWN");
            assertFalse(Files.exists(temporary.resolve("business-db.lock.db")));
            artifact("final-shutdown", mapper.createObjectNode().put("mvDbRawSha256", sha(Files.readAllBytes(temporary.resolve("business-db.mv.db")))).put("lockAbsent", true)); }
    }
    void artifact(String name, JsonNode value) throws Exception {
        Files.write(archive.resolve(String.format("%03d-%s.json", artifactIndex++, name)), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }
    static String sha(byte[] bytes) throws Exception { return ParsedCandidateFixture.sha(bytes); }
    String canonical(JsonNode node) { return new AcmgBusinessArtifacts(mapper).requestHash(node); }
    void start(String name) throws Exception {
        byte[] raw;
        try (var input = getClass().getResourceAsStream("/ps2-denovo-core/r1/" + name + "-bundle.json")) { assertNotNull(input); raw = input.readAllBytes(); }
        bundle = (ObjectNode) mapper.readTree(raw); material = temporary.resolve("material.json"); Files.write(material, raw);
        env = new MockEnvironment().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PS2_DEVELOPMENT);
        pins(raw); publish(name); wire();
        Files.write(archive.resolve("original-bundle-raw.json"), raw, StandardOpenOption.CREATE_NEW);
        artifact("original-pins", mapper.createObjectNode().put("rawSha256", sha(raw)).put("canonicalSha256", canonical(bundle)));
    }
    void pins(byte[] raw) throws Exception {
        env.withProperty("variant-analysis.acmg-business.ps2-bundle-file", java.util.Objects.requireNonNull(material.toString()))
                .withProperty("variant-analysis.acmg-business.ps2-bundle-file-sha256", java.util.Objects.requireNonNull(sha(raw)))
                .withProperty("variant-analysis.acmg-business.ps2-bundle-sha256", java.util.Objects.requireNonNull(canonical(bundle)));
    }
    void publish(String name) throws Exception {
        analysisId = UUID.randomUUID().toString(); var allele = bundle.at("/context/variant/inputAllele");
        String sample = "test-sample-" + name + "-proband", contig = allele.path("contig").asText(), position = allele.path("position").asText();
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        String vcf = ParsedCandidateFixture.vcf("chr" + contig + "\t" + position + "\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\t" + probandFormat + "\t" + probandSample)
                .replace("synthetic-proband", sample).replace("#CHROM", "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"depth\">\n##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"quality\">\n##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"allele depth\">\n#CHROM");
        if (probandFormat.contains(":PS")) vcf = vcf.replace("#CHROM", "##FORMAT=<ID=PS,Number=1,Type=Integer,Description=\"TEST_ONLY phase set\">\n#CHROM");
        String tsv = ParsedCandidateFixture.tsv("1\t" + contig + "\t" + position + "\t" + ref + "\t" + alt + "\tTEST_ONLY_" + name.toUpperCase(Locale.ROOT) + "\t900001\tAD\t0.8");
        if (decoyFirst) {
            vcf += "chr" + contig + "\t10102\t.\tA\tG\t.\tPASS\t.\tGT:DP:GQ:AD\t0/1:40:60:21,19\n";
            tsv = ParsedCandidateFixture.tsv("1\t7\t10102\tA\tG\tTEST_ONLY_ALPHA\t900001\tAD\t0.9", "2\t7\t10101\tA\tG\tTEST_ONLY_ALPHA\t900001\tAD\t0.8");
        }
        var fixture = ParsedCandidateFixture.prepare(properties.getStorageRoot().resolve(analysisId), analysisId, GenomeAssembly.GRCh38, vcf, tsv, null, false);
        var old = fixture.job(); var job = new VariantAnalysisJob(analysisId, "TEST_ONLY.vcf", old.getFileSha256(), GenomeAssembly.GRCh38,
                AnalysisMode.WES, sample, properties.getStorageRoot().resolve(analysisId), old.getUploadedFile()); jobs.save(job);
        var converter = mock(VcfBuildConverter.class); when(converter.convert(any(), any(), any(), any())).thenReturn(fixture.conversion());
        var enrichment = mock(VariantInterpretationService.class); when(enrichment.enrichEvidenceOnly(any(), any())).thenAnswer(call -> call.getArgument(0));
        var annotation = mock(VepTranscriptAnnotationEnrichmentService.class);
        when(annotation.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenReturn(
                new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(Map.of(), VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested()));
        var processor = new VariantAnalysisJobProcessor(jobs, converter, mock(VcfNormalizer.class), mock(VcfQcService.class), enrichment, properties, annotation);
        ReflectionTestUtils.setField(processor, "acmgBusinessEnabled", true); processor.setExomiserEngineAdapter(fixture.adapter()); processor.setAcmgExecutionSources(sources);
        processor.process(analysisId);
        assertEquals(VariantAnalysisStatus.READY, jobs.findById(analysisId).orElseThrow().getStatus(), jobs.findById(analysisId).orElseThrow().getErrorMessage());
        assertEquals(decoyFirst ? 2 : 1, jobs.findInterpretations(analysisId, 0, 100).size()); assertTrue(store.source(analysisId).isPresent());
        artifact("published-source", mapper.readTree(store.source(analysisId).orElseThrow().json()));
        artifact("published-candidate", mapper.valueToTree(jobs.findInterpretations(analysisId, 0, 1)));
    }
    void wire() {
        service = new AcmgBusinessConfig().acmgBusinessService(mapper, jobs, store, sources, properties, env);
        Object value = ReflectionTestUtils.getField(java.util.Objects.requireNonNull(service), "ps2Preparation"); assertInstanceOf(Ps2DeNovoContextPreparation.class, value);
        var preparation = spy((Ps2DeNovoContextPreparation) value);
        doAnswer(call -> { loads.incrementAndGet(); return call.callRealMethod(); }).when(preparation).load();
        doAnswer(call -> { prepares.incrementAndGet(); return call.callRealMethod(); }).when(preparation).prepare(any(), any(), any(), any(), any());
        doAnswer(call -> { assertTrue(store.runs(analysisId).stream().anyMatch(r -> "RUNNING".equals(r.status())));
            core.incrementAndGet(); try { return call.callRealMethod(); } catch (Throwable failed) { failed.printStackTrace(); throw failed; } }).when(preparation).evaluate(any());
        ReflectionTestUtils.setField(java.util.Objects.requireNonNull(service), "ps2Preparation", preparation);
        for (String field : List.of("runtime", "ba1Runtime", "pm2Runtime", "pvs1Runtime", "missenseRuntime", "functionalRuntime", "pm1Runtime", "hnf1aFrequencyRuntime"))
            ReflectionTestUtils.setField(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(field), (Supplier<Object>) () -> { forbidden.incrementAndGet(); throw new AssertionError("FORBIDDEN_SUPPLIER_" + field); });
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
    }
    ObjectNode options() throws Exception {
        int before = core.get(); var value = (ObjectNode) mapper.readTree(http.perform(get("/variant-analyses/{id}/acmg/input-options", analysisId).param("topN", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()); assertEquals(before, core.get()); artifact("options", value); return value;
    }
    ObjectNode request(ObjectNode options) {
        var r = mapper.createObjectNode().put("topN", 1).put("declarationVersion", AcmgBusinessRuleScope.PS2_DECLARATION)
                .put("sampleId", options.path("sampleId").asText()).put("dataClass", "SYNTHETIC"); r.putArray("hypotheses");
        var patient = (ObjectNode) bundle.at("/context/patientContext").deepCopy(); patient.remove("affectedStatus"); r.set("patient", patient);
        r.set("ps2Selection", ((ObjectNode) options.path("ps2Selection")).deepCopy().put("confirmed", true)); return r;
    }
    ObjectNode submit(ObjectNode request) throws Exception {
        artifact("request", request); var result = http.perform(post("/variant-analyses/{id}/acmg/runs", analysisId)
                .contentType("application/json").content(java.util.Objects.requireNonNull(mapper.writeValueAsBytes(request)))).andReturn();
        if (result.getResponse().getStatus() != 200) {
            if (result.getResolvedException() != null) java.util.Objects.requireNonNull(result.getResolvedException()).printStackTrace();
            artifact("failed-runs", mapper.valueToTree(store.runs(analysisId)));
        }
        assertEquals(200, result.getResponse().getStatus()); var saved = (ObjectNode) mapper.readTree(result.getResponse().getContentAsByteArray());
        artifact("submit", saved); return saved;
    }
    ObjectNode counts() { return mapper.createObjectNode().put("prepare", prepares.get()).put("materialRead", loads.get()).put("core", core.get()).put("forbiddenSuppliers", forbidden.get()); }
    void rebuildAndCompare(ObjectNode request, ObjectNode saved) throws Exception {
        assertEquals(saved, mapper.readTree(store.run(analysisId, saved.path("runId").asText()).orElseThrow().responseJson())); artifact("counts-after-save", counts());
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).execute("SHUTDOWN"); assertFalse(Files.exists(temporary.resolve("business-db.lock.db")));
        artifact("shutdown", mapper.createObjectNode().put("dbSha256", sha(Files.readAllBytes(temporary.resolve("business-db.mv.db")))).put("lockAbsent", true));
        repositories(); wire(); var before = counts();
        var got = mapper.readTree(http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, got); artifact("get-after-store-service-rebuild", got); assertEquals(saved, submit(request)); assertEquals(before, counts());
        assertEquals(1, store.runs(analysisId).size()); assertEquals(0, forbidden.get()); artifact("counts-after-reuse", counts());
    }
    @ParameterizedTest @ValueSource(strings = {"alpha", "beta"})
    void fixedBackgroundsSaveRebuildAndUnavailableMaterialReuse(String name) throws Exception {
        start(name); var request = request(options()); var saved = submit(request); var ps2 = saved.at("/candidates/0/attempts/0/ps2");
        assertEquals("COMPLETED", saved.path("status").asText()); assertEquals("CORE_RESULT_ONLY", ps2.path("resultMode").asText());
        assertEquals("alpha".equals(name) ? 2.0 : 0.5, ps2.path("deNovoPoints").asDouble());
        assertEquals("alpha".equals(name) ? "STRONG" : "SUPPORTING", ps2.at("/evaluation/appliedStrength").asText());
        assertEquals(bundle, ps2.path("sourceBundle")); assertEquals(1, core.get()); rebuildAndCompare(request, saved);
        Files.move(material, temporary.resolve("material-retained.json")); wire(); var before = counts();
        assertEquals(saved, service.read(analysisId, saved.path("runId").asText())); assertEquals(saved, submit(request)); assertEquals(before, counts());
        assertEquals("PS2_MATERIAL_UNAVAILABLE", assertThrows(ResponseStatusException.class, () -> service.inputOptions(analysisId, 1)).getReason());
        var changed = request.deepCopy(); ((ObjectNode) changed.path("ps2Selection")).put("caseId", "other"); reject(changed, "PS2_MATERIAL_UNAVAILABLE");
        env.withProperty("variant-analysis.acmg-business.ps2-bundle-sha256", java.util.Objects.requireNonNull("0".repeat(64))); wire(); reject(request, "PS2_MATERIAL_UNAVAILABLE");
        assertEquals(saved, service.read(analysisId, saved.path("runId").asText())); assertEquals(1, core.get());
    }
    void reject(ObjectNode r, String reason) throws Exception {
        int before = core.get(); var failure = assertThrows(ResponseStatusException.class, () -> service.submit(analysisId, mapper.writeValueAsBytes(r)));
        assertEquals(reason, failure.getReason()); assertEquals(before, core.get()); artifact("rejected-request", r);
        artifact("rejection", mapper.createObjectNode().put("reason", failure.getReason()).put("coreDelta", core.get() - before));
    }
    void replaceMaterial(String resource) throws Exception {
        byte[] raw;
        try (var input = getClass().getResourceAsStream("/ps2-denovo-business/" + resource + ".json")) { assertNotNull(input); raw = input.readAllBytes(); }
        bundle = (ObjectNode) mapper.readTree(raw); Files.write(material, raw); pins(raw); wire();
        Files.write(archive.resolve(resource + "-raw.json"), raw, StandardOpenOption.CREATE_NEW);
    }
    @ParameterizedTest @ValueSource(strings = {"alpha-unknown", "alpha-ft-conflict"})
    void validMaterialWithClinicalGapOrR1FailureSavesMinimalResult(String resource) throws Exception {
        start("alpha"); replaceMaterial(resource); var r = request(options()); var saved = submit(r);
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText()); var coreResult = saved.at("/candidates/0/attempts/0/ps2");
        assertEquals(bundle, coreResult.path("sourceBundle")); assertEquals("NOT_EVALUATED", coreResult.at("/evaluationResult/status").asText());
        assertEquals("alpha-unknown".equals(resource) ? "PS2_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE" : "PS2_RAW_FT_CONFLICT", coreResult.at("/evaluationResult/reasonCode").asText());
        for (String field : List.of("deNovoPoints", "evaluation", "evaluationId", "appliedStrength", "deterministicTrace")) assertFalse(coreResult.has(field));
        assertEquals(3, coreResult.at("/criterionEvaluations/0").size()); assertEquals(1, core.get()); rebuildAndCompare(r, saved);
    }
    @Test void changedSameIdsMaterialAndPublishedSourceCannotReuseOldResult() throws Exception {
        start("alpha"); var oldRequest = request(options()); var oldSaved = submit(oldRequest);
        byte[] reformatted = (Files.readString(material) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(material, reformatted); pins(reformatted); wire(); reject(oldRequest, "PS2_SELECTION_STALE");
        assertEquals(oldSaved, service.read(analysisId, oldSaved.path("runId").asText())); assertEquals(1, core.get());
        replaceMaterial("alpha-unknown"); reject(oldRequest, "PS2_SELECTION_STALE");
        var newSaved = submit(request(options())); assertNotEquals(oldSaved.path("runId"), newSaved.path("runId"));
        assertEquals(oldSaved, service.read(analysisId, oldSaved.path("runId").asText())); assertEquals(2, core.get());
        var source = (ObjectNode) mapper.readTree(store.source(analysisId).orElseThrow().json());
        source.put("requestSha256", "a".repeat(64)); mutatePublishedSource(source);
        reject(oldRequest, "PS2_SELECTION_STALE"); assertEquals(2, core.get()); assertEquals(2, store.runs(analysisId).size());
    }
    @Test void materialMatchingSecondCandidateDoesNotSkipFormalFirst() throws Exception {
        decoyFirst = true; start("alpha");
        assertEquals("PS2_CORE_ALLELE_CONFLICT", assertThrows(ResponseStatusException.class, () -> service.inputOptions(analysisId, 1)).getReason());
        assertEquals(0, core.get()); assertTrue(store.runs(analysisId).isEmpty());
        artifact("formal-first-not-skipped", mapper.createObjectNode().put("reason", "PS2_CORE_ALLELE_CONFLICT").put("core", core.get()));
    }
    void mutatePublishedSource(ObjectNode source) throws Exception {
        String json = mapper.writeValueAsString(source);
        new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)).update("UPDATE variant_analysis_acmg_source SET snapshot_sha256=?,snapshot_json=? WHERE analysis_id=?",
                AcmgBusinessStore.sha(json), json, analysisId);
        artifact("TEST_ONLY-source-fault-injection", source);
    }
    @Test void currentObservedCallAndSourceMismatchesBlockBeforeCore() throws Exception {
        start("alpha"); var r = request(options());
        var original = (ObjectNode) mapper.readTree(store.source(analysisId).orElseThrow().json());
        var wrong = original.deepCopy(); wrong.put("sampleId", "other"); mutatePublishedSource(wrong);
        reject(r, "EXECUTION_SNAPSHOT_TASK_MISMATCH"); mutatePublishedSource(original);
        var candidate = mapper.<ObjectNode>valueToTree(jobs.findInterpretations(analysisId, 0, 1).get(0));
        var changed = candidate.deepCopy(); ((ObjectNode) changed.path("analysisVcfCall")).put("depth", 41);
        store.saveCandidateAnnotationPayload(analysisId, changed.path("candidateId").asText(), mapper.writeValueAsString(changed));
        var source = original.deepCopy(); ((ObjectNode) source.at("/candidates/0")).set("published", changed);
        ((ObjectNode) source.at("/candidates/0/engineCandidate/analysisVcfCall")).put("depth", 41); mutatePublishedSource(source);
        reject(r, "PS2_PROBAND_QUALITY_CONFLICT"); assertEquals(0, core.get());
        store.saveCandidateAnnotationPayload(analysisId, candidate.path("candidateId").asText(), mapper.writeValueAsString(candidate)); mutatePublishedSource(original);
        assertEquals("COMPLETED", submit(r).path("status").asText());
    }
    @Test void configurationScopeAndForbiddenSupplierProbes() throws Exception {
        start("alpha"); var valid = request(options()); var config = new AcmgBusinessConfig();
        env.withProperty("variant-analysis.acmg-business.enabled", "false");
        assertEquals("PS2_BUSINESS_EXECUTION_DISABLED", assertThrows(IllegalArgumentException.class,
                () -> config.acmgBusinessService(mapper, jobs, store, sources, properties, env)).getMessage());
        env.withProperty("variant-analysis.acmg-business.enabled", "true");
        for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only")) {
            env.withProperty("variant-analysis.acmg-business." + flag, "true");
            assertEquals("PS2_EXECUTION_MODE_CONFLICT", assertThrows(IllegalArgumentException.class,
                    () -> config.acmgBusinessService(mapper, jobs, store, sources, properties, env)).getMessage());
            env.withProperty("variant-analysis.acmg-business." + flag, "false");
        }
        // Positive controls prove these failure probes actually execute and count when reached.
        for (String field : List.of("runtime", "ba1Runtime", "pm2Runtime", "pvs1Runtime", "missenseRuntime", "functionalRuntime", "pm1Runtime", "hnf1aFrequencyRuntime")) {
            Object value = ReflectionTestUtils.getField(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(field)); assertInstanceOf(Supplier.class, value);
            assertThrows(AssertionError.class, () -> ((Supplier<?>) java.util.Objects.requireNonNull(value)).get());
        }
        assertEquals(8, forbidden.get()); artifact("forbidden-probe-positive-controls", counts()); forbidden.set(0);
        submit(valid); assertEquals(0, forbidden.get()); artifact("forbidden-probes-ps2-chain", counts());
        var legacy = config.acmgBusinessService(mapper, jobs, store, sources, properties, new MockEnvironment());
        assertEquals(AcmgBusinessRuleScope.CURRENT, ((AcmgExecutionProfile) Objects.requireNonNull(ReflectionTestUtils.getField(java.util.Objects.requireNonNull(legacy), "executionProfile"))).criteria());
        assertEquals("PS2_SCOPE_REQUIRED", assertThrows(ResponseStatusException.class, () -> legacy.submit(analysisId, mapper.writeValueAsBytes(valid))).getReason());
        var mixed = valid.deepCopy(); mixed.putObject("revelApplication");
        assertEquals("PS2_SCOPE_REQUIRED", assertThrows(ResponseStatusException.class, () -> legacy.submit(analysisId, mapper.writeValueAsBytes(mixed))).getReason());
        var tokenOnly = valid.deepCopy(); tokenOnly.remove("ps2Selection"); tokenOnly.put("declarationVersion", "ordinary-ui-v3");
        ((ObjectNode) tokenOnly.path("patient")).put("patientContextId", Ps2DeNovoContextPreparation.TOKEN + "0".repeat(64));
        assertEquals("PS2_SCOPE_REQUIRED", assertThrows(ResponseStatusException.class, () -> legacy.submit(analysisId, mapper.writeValueAsBytes(tokenOnly))).getReason());
    }
    @Test void pinsStrictBytesAndMissingSourceFailBeforeCore() throws Exception {
        start("alpha"); var r = request(options()); String raw = env.getProperty("variant-analysis.acmg-business.ps2-bundle-file-sha256");
        String canonicalPin = env.getProperty("variant-analysis.acmg-business.ps2-bundle-sha256");
        env.withProperty("variant-analysis.acmg-business.ps2-bundle-file-sha256", java.util.Objects.requireNonNull("0".repeat(64))); wire(); reject(r, "PS2_RAW_PIN_MISMATCH");
        env.withProperty("variant-analysis.acmg-business.ps2-bundle-file-sha256", Objects.requireNonNull(raw));
        env.withProperty("variant-analysis.acmg-business.ps2-bundle-sha256", java.util.Objects.requireNonNull("0".repeat(64))); wire(); reject(r, "PS2_CANONICAL_PIN_MISMATCH");
        env.withProperty("variant-analysis.acmg-business.ps2-bundle-sha256", Objects.requireNonNull(canonicalPin)); wire();
        byte[] invalid = "{} {}".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(material, invalid);
        env.withProperty("variant-analysis.acmg-business.ps2-bundle-file-sha256", java.util.Objects.requireNonNull(sha(invalid))); wire(); reject(r, "PS2_SINGLE_OBJECT_REQUIRED");
        assertEquals(0, core.get()); assertTrue(store.runs(analysisId).isEmpty());
    }
    @Test void bp1AndPvs1DispatchRejectPs2BeforeOldModesAndOldJsonStaysCompatible() throws Exception {
        start("alpha"); var r = request(options()); var configured = new AcmgBusinessConfig();
        for (String name : List.of("bp1", "pvs1")) {
            ObjectNode document = "bp1".equals(name) ? new Bp1BusinessFixture(mapper).document() : new Pvs1NmdBusinessFixture(mapper, false).document();
            Path file = temporary.resolve(name + "-compat.json"); byte[] raw = mapper.writeValueAsBytes(document); Files.write(file, raw);
            var oldEnv = new MockEnvironment().withProperty("variant-analysis.acmg-business.enabled", "true")
                    .withProperty("variant-analysis.acmg-business.acceptance-scope", "bp1".equals(name) ? AcmgBusinessRuleScope.BP1_DEVELOPMENT : AcmgBusinessRuleScope.PVS1_DEVELOPMENT)
                    .withProperty("variant-analysis.acmg-business." + name + "-bundle-file", java.util.Objects.requireNonNull(file.toString()))
                    .withProperty("variant-analysis.acmg-business." + name + "-bundle-file-sha256", java.util.Objects.requireNonNull(sha(raw)))
                    .withProperty("variant-analysis.acmg-business." + name + "-bundle-sha256", java.util.Objects.requireNonNull(canonical(document)));
            var old = configured.acmgBusinessService(mapper, jobs, store, sources, properties, oldEnv);
            var profile = (AcmgExecutionProfile) Objects.requireNonNull(ReflectionTestUtils.getField(java.util.Objects.requireNonNull(old), "executionProfile"));
            assertEquals(List.of("ACMG_AMP_2015:" + name.toUpperCase(Locale.ROOT)), profile.criteria());
            assertEquals("PS2_SCOPE_REQUIRED", assertThrows(ResponseStatusException.class, () -> old.submit(analysisId, mapper.writeValueAsBytes(r))).getReason());
            var noPs2 = r.deepCopy(); noPs2.remove("ps2Selection"); noPs2.put("declarationVersion", "ordinary-ui-v3");
            assertFalse(mapper.valueToTree(AcmgSubmission.parse(mapper.writeValueAsBytes(noPs2), mapper)).has("ps2Selection"));
            assertEquals(name.toUpperCase(Locale.ROOT) + "_EXPLICIT_DECLARATION_REQUIRED", assertThrows(ResponseStatusException.class,
                    () -> old.submit(analysisId, mapper.writeValueAsBytes(noPs2))).getReason());
            artifact(name + "-unchanged-profile", profile.document());
        }
        assertEquals(0, core.get()); assertEquals(0, forbidden.get());
    }
    @Test void duplicateConcurrentRequestsHaveOneDurableCoreCall() throws Exception {
        start("alpha"); var r = request(options()); byte[] raw = mapper.writeValueAsBytes(r);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var calls = pool.invokeAll(List.of((java.util.concurrent.Callable<ObjectNode>) () -> service.submit(analysisId, raw), () -> service.submit(analysisId, raw)));
            for (var call : calls) { var result = call.get(); artifact("concurrent-response", result); }
            assertEquals(1, core.get()); assertEquals(1, store.runs(analysisId).size());
            assertEquals("COMPLETED", service.submit(analysisId, raw).path("status").asText());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)); }
    }
    @Test void strictRequestAndFrozenIdentityGates() throws Exception {
        start("alpha"); var r = request(options());
        reject(r.deepCopy().put("declarationVersion", "ordinary-ui-v3"), "PS2_EXPLICIT_DECLARATION_REQUIRED");
        reject(r.deepCopy().put("topN", 2), "PS2_TOP_ONE_REQUIRED");
        var no = r.deepCopy(); no.remove("ps2Selection"); reject(no, "PS2_EXPLICIT_CONFIRMATION_REQUIRED");
        for (JsonNode flag : List.of(mapper.getNodeFactory().booleanNode(false), mapper.nullNode())) {
            var wrong = r.deepCopy(); ((ObjectNode) wrong.path("ps2Selection")).set("confirmed", flag); reject(wrong, "PS2_EXPLICIT_CONFIRMATION_REQUIRED"); }
        var textFlag = r.deepCopy(); ((ObjectNode) textFlag.path("ps2Selection")).put("confirmed", "true"); reject(textFlag, "ACMG_REQUEST_INVALID");
        for (String field : List.of("candidateId", "contextId", "observationId", "occurrenceId", "caseId", "materialBindingSha256", "preparationToken", "policyVersion")) {
            var wrong = r.deepCopy(); ((ObjectNode) wrong.path("ps2Selection")).put(field, "other"); reject(wrong, "PS2_SELECTION_STALE"); }
        for (String field : List.of("patientContextId", "familyContextId", "role", "sex")) {
            var wrong = r.deepCopy(); ((ObjectNode) wrong.path("patient")).put(field, "other"); reject(wrong, "PS2_PATIENT_CONFLICT"); }
        reject(r.deepCopy().put("sampleId", "other"), "PS2_SAMPLE_CONFLICT");
        for (String field : List.of("facts", "context", "path", "policy", "evaluation")) {
            var wrong = r.deepCopy(); ((ObjectNode) wrong.path("ps2Selection")).putObject(field); reject(wrong, "ACMG_REQUEST_INVALID"); }
        for (String field : List.of("association", "calibration", "revelApplication")) { var wrong = r.deepCopy(); wrong.putObject(field); reject(wrong, "PS2_EXCLUSIVE_SELECTION_REQUIRED"); }
        var rec = r.deepCopy(); rec.putArray("recommendations"); reject(rec, "PS2_EXCLUSIVE_SELECTION_REQUIRED");
        var multiple = r.deepCopy(); multiple.putArray("ps2Selection").add(r.path("ps2Selection")).add(r.path("ps2Selection")); reject(multiple, "ACMG_REQUEST_INVALID");
        var hypothesis = r.deepCopy(); hypothesis.putArray("hypotheses").addNull(); reject(hypothesis, "PS2_EXCLUSIVE_SELECTION_REQUIRED");
        assertEquals(0, core.get()); assertTrue(store.runs(analysisId).isEmpty()); assertEquals("COMPLETED", submit(r).path("status").asText());
    }
}
