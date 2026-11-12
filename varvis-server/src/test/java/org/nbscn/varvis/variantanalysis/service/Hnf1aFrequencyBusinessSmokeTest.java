package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.nbscn.varvis.variantanalysis.service.impl.PicardVcfBuildConverter;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real, fixed public six-allele panel; synthetic GT and disease declaration; isolated file H2/HTTP controllers. */
class Hnf1aFrequencyBusinessSmokeTest {
    private static final Map<Integer, String> EXPECTED = Map.of(120978778, "NOT_MET", 120978819, "NOT_MET",
            120978828, "NOT_MET", 120988847, "MET", 120989017, "NOT_EVALUATED", 120993678, "NOT_MET");
    private IntronicScopeBusinessSmokeTest smoke;
    private final List<Pm2SupportingRuntime> populations = new ArrayList<>();
    private int factories, ba1Providers, ba1Evaluators;

    @Test @EnabledIfSystemProperty(named = "varvis.bs1.real", matches = "true")
    void fixedPanelThroughFormalAnalysisExplicitFrequencySaveAndReconstruction() throws Exception {
        Path root = Path.of("target/bs1-hnf1a").toAbsolutePath(); Files.createDirectories(root);
        Path work = Files.createDirectory(root.resolve("backend-" + UUID.randomUUID()));
        smoke = IntronicScopeBusinessSmokeTest.positiveAt(work); smoke.setup(); smoke.artifacts = work;
        var base = smoke.base;
        base.properties.getAlphaMissense().setEnabled(false);
        base.properties.getVep().setTempDir(work.resolve("vep-temp").toString());
        smoke.environment.setProperty("variant-analysis.acmg-business.pm2-all-sites-command-file", "");
        smoke.environment.setProperty("variant-analysis.acmg-business.spliceai-raw-command-json", "[]");
        var report = base.mapper.createObjectNode().put("status", "BLOCKED").put("dataClass", "SYNTHETIC")
                .put("environment", "ISOLATED_MOCKMVC_FILE_H2_REAL_EXOMISER_VEP_JOINT")
                .put("pageCovered", false).put("fixedAlleleDenominator", 6).put("topN", 20);
        System.out.println("BS1_BACKEND_WORK=" + work);
        try {
            JsonNode panel;
            try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/acmg/bs1-hnf1a-public-controls-v1.json"))) { panel = base.mapper.readTree(stream); }
            write(work, "fixed-panel.json", panel);
            write(work, "actual-configuration.json", smoke.configurationProof());
            var scope = AnalysisScopePolicy.resolve(base.properties.getExomiser(), AnalysisScopePolicy.Scope.STANDARD, false);
            Files.writeString(work.resolve("effective-standard.yml"), scope.effectiveYaml(), StandardOpenOption.CREATE_NEW);
            StringBuilder vcf = new StringBuilder("##fileformat=VCFv4.2\n##reference=GRCh38\n##contig=<ID=chr12,length=133275309>\n"
                    + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allelic depths\">\n"
                    + "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n"
                    + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n");
            for (var control : panel.path("controls")) {
                String[] a = control.path("allele").asText().split(":");
                vcf.append("chr12\t").append(a[2]).append("\t.\t").append(a[3]).append('\t').append(a[4])
                        .append("\t99\tPASS\t.\tGT:AD:DP:GQ\t0/1:10,10:20:99\n");
            }
            Files.writeString(work.resolve("fixed-public.vcf"), vcf.toString(), StandardOpenOption.CREATE_NEW);
            var validator = new VepResourceValidator(base.properties);
            var annotation = new VepTranscriptAnnotationEnrichmentService(base.jobs, base.properties, validator, new VepCommandBuilder(base.properties),
                    new VepJsonOutputParser(base.mapper), smoke.vepRunner(), new TranscriptAnnotationService(base.properties,
                    new AnnTranscriptAnnotationProvider(), new InMemoryTranscriptAnnotationCache(), validator));
            var enrichment = new VariantInterpretationServiceImpl(null, List.of(), new TransparentVariantRankingService(base.properties));
            var processor = new VariantAnalysisJobProcessor(base.jobs, new PicardVcfBuildConverter(base.properties), mock(VcfNormalizer.class),
                    mock(VcfQcService.class), enrichment, base.properties, annotation);
            ReflectionTestUtils.setField(processor, "acmgBusinessEnabled", true); processor.setAcmgExecutionSources(base.sources);
            processor.setExomiserEngineAdapter(new ConfiguredExomiserEngineAdapter(base.properties, (cmd, dir, out, err, env, timeout) -> {
                smoke.exomiserCalls++; return new SystemExomiserProcessRunner().run(cmd, dir, out, err, env, timeout);
            }, new ExomiserRuntimeValidator()));
            var upload = smoke.analysisHttp(smoke.analysisService(processor));
            var uploaded = base.mapper.readTree(upload.perform(multipart("/variant-analyses")
                    .file(new MockMultipartFile("vcfFile", "public-bs1-hnf1a.vcf", "text/plain", vcf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("genomeAssembly", "GRCh38").param("analysisMode", "WES").param("analysisScope", "STANDARD").param("sampleId", "synthetic-proband"))
                    .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray());
            base.analysisId = uploaded.path("id").asText(); report.put("analysisId", base.analysisId); write(work, "upload-response.json", uploaded);
            var job = base.jobs.findById(base.analysisId).orElseThrow(); write(work, "formal-job.json", base.mapper.valueToTree(job));
            assertEquals(VariantAnalysisStatus.READY, job.getStatus(), base.mapper.writeValueAsString(job));
            var candidates = base.jobs.findInterpretations(base.analysisId, 0, 20); write(work, "formal-topn.json", base.mapper.valueToTree(candidates));
            report.put("formalCandidateRows", candidates.size());
            assertTrue(candidates.stream().map(c -> c.getObservation().getKey().getPosition()).collect(java.util.stream.Collectors.toSet()).containsAll(EXPECTED.keySet()));
            verifyFrequencyBusiness(work, report);
            assertEquals(1, smoke.exomiserCalls); assertEquals(1, smoke.vepCalls);
        } catch (Exception | AssertionError failure) { report.put("failure", failure.toString()); throw failure; }
        finally { report.set("actualCalls", base.mapper.valueToTree(calls())); write(work, "result.json", report); base.closeDatabase(); }
    }


    @Test @EnabledIfSystemProperty(named = "varvis.bs1.resume", matches = "true")
    void frequencyOnlyRecheckReusesRegisteredFormalArtifactsWithoutModels() throws Exception {
        Path source = Path.of(Objects.requireNonNull(System.getProperty("varvis.bs1.resumeWork"))).toAbsolutePath().normalize();
        // Check the junction target for containment, while preserving the originally registered logical task path.
        assertTrue(source.toRealPath().startsWith(Path.of("target/bs1-hnf1a").toRealPath()));
        Path work = Files.createDirectory(source.resolve("frequency-recheck-" + UUID.randomUUID()));
        smoke = IntronicScopeBusinessSmokeTest.positiveAt(source);
        var base = smoke.base;
        base.properties = new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        base.properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        var configured = new Properties();
        try (var in = Files.newInputStream(Path.of("config/variant-analysis-local.properties"))) { configured.load(in); }
        smoke.environment = new org.springframework.mock.env.MockEnvironment().withProperty("VARVIS_RESOURCE_ROOT",
                Objects.requireNonNull(System.getProperty("varvis.e4b3.resourceRoot")));
        smoke.environment.getPropertySources().addLast(new org.springframework.core.env.PropertiesPropertySource("isolated-variant-settings", configured));
        org.springframework.boot.context.properties.bind.Binder.get(smoke.environment).bind("variant-analysis",
                org.springframework.boot.context.properties.bind.Bindable.ofInstance(base.properties));
        base.properties.setStorageRoot(source.resolve("jobs")); base.properties.getPersistence().setTablePrefix("");
        base.properties.getAlphaMissense().setEnabled(false);
        smoke.environment.setProperty("variant-analysis.acmg-business.pm2-all-sites-command-file", "");
        smoke.environment.setProperty("variant-analysis.acmg-business.spliceai-raw-command-json", "[]");
        base.dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:file:" + source.resolve("business-db").toString().replace('\\', '/') + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        base.recreateRepositories();
        var original = base.mapper.readTree(Files.readString(source.resolve("first-response.json")));
        base.analysisId = original.path("analysisId").asText();
        var report = base.mapper.createObjectNode().put("status", "BLOCKED").put("dataClass", "SYNTHETIC")
                .put("analysisId", base.analysisId).put("fixedAlleleDenominator", 6).put("topN", 20)
                .put("environment", "ISOLATED_SAME_FORMAL_ARTIFACTS_FREQUENCY_RECHECK").put("pageCovered", false);
        System.out.println("BS1_FREQUENCY_RECHECK=" + work);
        try {
            var job = base.jobs.findById(base.analysisId).orElseThrow(); assertEquals(VariantAnalysisStatus.READY, job.getStatus());
            var rows = base.jobs.findInterpretations(base.analysisId, 0, 20);
            assertEquals(original.path("executionSourceSha256").asText(), base.sources.recover(job, rows).snapshotSha256());
            try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/acmg/bs1-hnf1a-public-controls-v1.json"))) {
                assertEquals(base.mapper.readTree(in), base.mapper.readTree(Files.readString(source.resolve("fixed-panel.json")))) ;
            }
            var reuse = base.mapper.createObjectNode().put("analysisId", base.analysisId)
                    .put("executionSourceSha256", original.path("executionSourceSha256").asText())
                    .put("formalEvidenceDirectory", source.getFileName().toString()).put("formalCandidateRows", rows.size())
                    .put("newExomiserCalls", 0).put("newVepCalls", 0)
                    .put("inputVcfSha256", smoke.hash(Files.readString(source.resolve("fixed-public.vcf"))));
            write(work, "formal-artifact-reuse.json", reuse);
            verifyFrequencyBusiness(work, report);
            assertEquals(0, smoke.exomiserCalls); assertEquals(0, smoke.vepCalls);
        } catch (Exception | AssertionError failure) { report.put("failure", failure.toString()); throw failure; }
        finally { report.set("actualCalls", base.mapper.valueToTree(calls())); write(work, "result.json", report); base.closeDatabase(); }
    }

    private void verifyFrequencyBusiness(Path work, ObjectNode report) throws Exception {
        var base = smoke.base;
        wire();
        var options = base.service.inputOptions(base.analysisId, 20); write(work, "input-options.json", options);
        var request = declaration(options); write(work, "first-request.json", request);
        var before = calls(); var saved = post(request); write(work, "first-response.json", saved); event(work, "first", before);
        assertEquals(9, saved.path("enabledCriteria").size());
        var actual = new TreeMap<Integer, String>(); var states = report.putArray("frequencyResults");
        for (var row : saved.path("candidates")) for (var attempt : row.path("attempts")) {
            var frequency = attempt.path("hnf1aFrequency");
            int pos = attempt.at("/context/variant/evidenceQueryAllele/position").asInt();
            if (!EXPECTED.containsKey(pos)) continue;
            assertEquals(AcmgRecommendations.FREQUENCY_CONTEXT_SELECTION, attempt.at("/context/transcript/selectionPolicy").asText());
            String state = frequency.at("/criterionEvaluations/1/evaluationResult/status").asText(); actual.put(pos, state);
            states.addObject().put("position", pos).put("BS1", state)
                    .put("diseaseBA1", frequency.at("/criterionEvaluations/0/evaluationResult/status").asText())
                    .put("PM2", attempt.at("/pm2/evaluation/evaluationResult/status").asText())
                    .set("measurements", frequency.at("/evidence/measurements"));
        }
        report.set("actualBs1States", base.mapper.valueToTree(actual));
        // Preserve the result before assertions so a genuine reachability or quality failure is reviewable.
        write(work, "evaluated-report.json", report);
        assertEquals(EXPECTED, actual);
        assertEquals(1, saved.at("/pm2Calls/provider").asInt());
        assertEquals(0, saved.at("/pm2Calls/allSitesBatch").asInt());
        assertEquals(0, saved.at("/hnf1aFrequencyCalls/provider").asInt());
        assertEquals(0, saved.at("/ba1Calls/provider").asInt());
        assertEquals(0, smoke.spliceBatches);
        before = calls(); base.closeDatabase(); base.recreateRepositories(); wire();
        var read = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        write(work, "reconstructed-get.json", read); event(work, "reconstructed-get", before); assertTrue(saved.equals(read), "Reconstructed GET must equal saved JSON"); assertEquals(before, calls());
        before = calls(); var repeated = post(request); write(work, "repeat-response.json", repeated); event(work, "repeat", before);
        assertTrue(saved.equals(repeated), "Repeated POST must return the same saved JSON"); assertEquals(before, calls());
        report.put("status", "SIX_FIXED_CONTROLS_FORMAL_BUSINESS_VERIFIED").put("readbackIdentical", true).put("repeatIdentical", true);
    }

    @SuppressWarnings("unchecked")
    private void wire() {
        var base = smoke.base;
        base.service = new AcmgBusinessConfig().acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, smoke.environment);
        var service = Objects.requireNonNull(base.service);
        var population = (Supplier<Pm2SupportingRuntime>) Objects.requireNonNull(ReflectionTestUtils.getField(service, "pm2Runtime"));
        ReflectionTestUtils.setField(service, "pm2Runtime", (Supplier<Pm2SupportingRuntime>) () -> { factories++; var runtime = population.get(); populations.add(runtime); return runtime; });
        var ba1 = (Supplier<AcmgBusinessService.Ba1Runtime>) Objects.requireNonNull(ReflectionTestUtils.getField(service, "ba1Runtime"));
        ReflectionTestUtils.setField(service, "ba1Runtime", (Supplier<AcmgBusinessService.Ba1Runtime>) () -> {
            factories++; var runtime = ba1.get(); var counted = spy(runtime.evaluator());
            doAnswer(call -> { var result = (ObjectNode) call.callRealMethod(); ba1Providers += result.path("providerCalls").asInt();
                ba1Evaluators += result.path("evaluatorCalls").asInt(); return result; }).when(counted).evaluate(anyString(), any(), any(), any(), anyString());
            return new AcmgBusinessService.Ba1Runtime(runtime.producer(), counted);
        });
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
    }
    private ObjectNode declaration(ObjectNode options) {
        var request = smoke.base.mapper.createObjectNode().put("topN", 20).put("declarationVersion", "ordinary-ui-v3")
                .put("sampleId", "synthetic-proband").put("dataClass", "SYNTHETIC");
        request.putObject("patient").put("patientContextId", "patient-context-public-bs1-hnf1a").put("role", "PROBAND").put("sex", "UNKNOWN")
                .set("source", smoke.source("bs1-fixed-public-panel/engineering-case"));
        request.putArray("hypotheses"); var selections = request.putArray("recommendations"); int confirmed = 0;
        for (var row : options.path("candidates")) {
            var r = row.path("recommendation"); var selection = selections.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) selection.set(field, r.path(field));
            JsonNode relation = null;
            for (var rel : r.path("relationships")) if (rel.path("selectable").asBoolean() && "HGNC:11621".equals(rel.path("hgncId").asText())
                    && "MONDO:0015967".equals(rel.path("mondoId").asText())
                    && "AUTOSOMAL_DOMINANT".equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rel.path("moi").asText()))) { assertNull(relation); relation = rel; }
            selection.putNull("annotationId");
            if (relation == null) { selection.putNull("stableRelationshipKey"); continue; }
            assertTrue(r.at("/hnf1aFrequencyContextPreparation/available").asBoolean(), r.toString());
            selection.set("stableRelationshipKey", relation.path("stableRelationshipKey")); confirmed++;
        }
        assertEquals(6, confirmed, "Fixed AD control declarations must be reachable without a prediction transcript");
        return request;
    }
    private ObjectNode post(ObjectNode request) throws Exception {
        var response = smoke.base.http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/{id}/acmg/runs", smoke.base.analysisId).contentType("application/json")
                .content(Objects.requireNonNull(smoke.base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getErrorMessage());
        return (ObjectNode) smoke.base.mapper.readTree(response.getContentAsByteArray());
    }
    private Map<String, Integer> calls() {
        return Map.of("exomiser", smoke.exomiserCalls, "vep", smoke.vepCalls, "populationFactories", factories,
                "pm2Provider", populations.stream().mapToInt(p -> Objects.requireNonNull(p).providerCalls()).sum(),
                "pm2Evaluator", populations.stream().mapToInt(p -> Objects.requireNonNull(p).evaluatorCalls()).sum(),
                "allSitesBatch", populations.stream().mapToInt(p -> Objects.requireNonNull(p).allSitesBatches()).sum(),
                "ba1Provider", ba1Providers, "ba1Evaluator", ba1Evaluators, "spliceBatch", smoke.spliceBatches);
    }
    private void event(Path work, String name, Map<String, Integer> before) throws Exception {
        var delta = new TreeMap<String, Integer>(); calls().forEach((k, v) -> delta.put(k, v - before.get(k)));
        write(work, name + "-call-increment.json", smoke.base.mapper.valueToTree(delta));
    }
    private static void write(Path work, String name, JsonNode value) throws Exception {
        Files.writeString(work.resolve(name), value.toPrettyString(), StandardOpenOption.CREATE_NEW);
    }
}
