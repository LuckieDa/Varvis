package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.impl.PicardVcfBuildConverter;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public engineering input, isolated HTTP/H2, actual configured resources. No clinical case or listening service. */
class Pm2MultigeneBusinessSmokeTest {
    private IntronicScopeBusinessSmokeTest smoke;
    private Path work;
    private final List<Pm2SupportingRuntime> runtimes = new ArrayList<>();

    @Test @EnabledIfSystemProperty(named="varvis.pm2.formal.real", matches="true")
    void oneRetainedPublicCandidateThroughCurrentPageDeclarationAndSavedRun() throws Exception {
        setup("formal-top1"); var base = smoke.base;
        try {
            Path prior = Path.of(System.getProperty("varvis.pm2.formal.prior"));
            assertTrue(Files.size(prior.resolve("business-db.mv.db")) < 64L * 1024 * 1024);
            base.closeDatabase();
            Files.copy(prior.resolve("business-db.mv.db"), work.resolve("business-db.mv.db"), StandardCopyOption.REPLACE_EXISTING);
            base.recreateRepositories();
            base.analysisId = base.mapper.readTree(prior.resolve("result.json").toFile()).path("analysisId").asText();
            var source = base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
            base.properties.setStorageRoot(Path.of(source.path("uploadedFile").asText()).getParent().getParent());
            base.recreateRepositories();
            // Actual production wiring; no retained observation injection or policy changes.
            base.service = new org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig().acmgBusinessService(
                    base.mapper, base.jobs, base.store, base.sources, base.properties, smoke.environment);
            base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
            var options = base.service.inputOptions(base.analysisId, 1); write("input-options.json", options);
            assertTrue(options.path("executionSourceRecovered").asBoolean(), options.path("blockers").toString());
            var request = declaration(options); write("request.json", request);
            var saved = post(request); write("new-run.json", saved);
            assertEquals(1, saved.path("selectedCount").asInt());
            assertTrue(saved.at("/pm2Calls/provider").asInt() > 0, saved.path("pm2Calls").toString());
            assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
            assertEquals(saved, post(request));
            assertEquals(0, smoke.exomiserCalls); assertEquals(0, smoke.vepCalls);
            write("result.json", base.mapper.createObjectNode().put("analysisId", base.analysisId)
                    .put("runId", saved.path("runId").asText()).put("pageCovered", false)
                    .put("dataClass", "SYNTHETIC").put("source", "RETAINED_PUBLIC_ANALYSIS_NORMAL_SOURCE_RECOVERY")
                    .put("newExomiserCalls", 0).put("newVepCalls", 0).put("savedReadbackEqual", true)
                    .set("pm2", saved.at("/candidates/0/attempts/0/pm2")));
        } finally { base.closeDatabase(); }
    }

    private void setup(String phase) throws Exception {
        Path root = Path.of("target/pm2-general-20260909").toAbsolutePath();
        work = Files.createDirectory(root.resolve(phase + "-" + UUID.randomUUID()));
        smoke = IntronicScopeBusinessSmokeTest.positiveAt(work); smoke.setup();
        System.out.println("PM2_MULTIGENE_WORK=" + work);
        String commandFile = System.getProperty("varvis.pm2.multigene.allSitesCommand", "");
        if (!commandFile.isBlank()) smoke.environment.withProperty("variant-analysis.acmg-business.pm2-all-sites-command-file", commandFile);
        if (Boolean.getBoolean("varvis.pm2.multigene.withoutSpliceRuntime"))
            smoke.environment.withProperty("variant-analysis.acmg-business.spliceai-raw-command-json", "[]");
    }

    @Test @EnabledIfSystemProperty(named="varvis.pm2.multigene.preflight", matches="true")
    void preflightPublicPoolBeforeFixingAnalysisInput() throws Exception {
        setup("preflight"); var base = smoke.base;
        var report = base.mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("status", "BLOCKED");
        try {
            var pool = base.mapper.readTree(Path.of(System.getProperty("varvis.pm2.multigene.pool")).toFile());
            write("candidate-pool.json", pool); write("configuration.json", smoke.configurationProof());
            var policy = AnalysisScopePolicy.resolve(base.properties.getExomiser(), AnalysisScopePolicy.Scope.INCLUDE_INTRONS, true);
            Files.writeString(work.resolve("effective-analysis.yml"), policy.effectiveYaml());
            var checksum = ExplicitContextSourceLedger.load(base.mapper,
                    Path.of(smoke.environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),
                    smoke.environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference = new NativeSnvReference(base.mapper, base.properties, new NormalizationResourceValidator(base.properties),
                    Path.of(smoke.environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")), List.of(checksum), checksum.reference("reference"));
            var refs = report.putArray("referenceObservations");
            var rows = new ArrayList<JsonNode>(); pool.path("controls").forEach(rows::add);
            rows.sort(Comparator.comparingInt((JsonNode n) -> Integer.parseInt(n.path("allele").asText().split(":")[1]))
                    .thenComparingInt(n -> Integer.parseInt(n.path("allele").asText().split(":")[2])));
            StringBuilder vcf = new StringBuilder("##fileformat=VCFv4.2\n##reference=GRCh38\n");
            var contigs = new LinkedHashSet<String>(); for (var row : rows) contigs.add(row.path("allele").asText().split(":")[1]);
            for (var contig : contigs) vcf.append("##contig=<ID=chr").append(contig).append(">\n");
            vcf.append("##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allelic depths\">\n##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n");
            var command = new ArrayList<String>(List.of(base.properties.getExomiser().getJavaExecutable(), "--class-path",
                    Path.of(base.properties.getExomiser().getCliJar()).getParent().resolve("lib") + "/*",
                    Path.of("../scripts/pm2/QueryExomiserFrequencyBatch.java").toAbsolutePath().normalize().toString(),
                    Path.of(base.properties.getExomiser().getDataDirectory()).resolve("2602_hg38/2602_hg38_variants.mv.db").toString(),
                    work.resolve("effective-analysis.yml").toString(), work.resolve("frequency-query.json").toString()));
            for (var row : rows) {
                String[] a = row.path("allele").asText().split(":");
                var observed = reference.observe("chr" + a[1], Integer.parseInt(a[2]), a[3]);
                refs.add(observed.observation()); assertEquals("MATCH", observed.observation().path("status").asText());
                boolean dominant = Set.of("HNF1A", "HNF4A", "ACTA1").contains(row.path("gene").asText());
                vcf.append("chr").append(a[1]).append('\t').append(a[2]).append("\t.\t").append(a[3]).append('\t').append(a[4])
                        .append("\t99\tPASS\t.\tGT:AD:DP:GQ\t").append(dominant ? "0/1:20,20:40:99\n" : "1/1:0,40:40:99\n");
                command.add(String.join(":", Arrays.copyOfRange(a, 1, 5)));
            }
            Path input = work.resolve("pool.vcf"), output = work.resolve("preflight-vep.jsonl"); Files.writeString(input, vcf.toString());
            write("frequency-command.json", base.mapper.valueToTree(command));
            var frequency = new SystemExternalProcessRunner().run(command, work, Duration.ofMinutes(3));
            write("frequency-process.json", base.mapper.valueToTree(frequency)); assertTrue(frequency.isSuccess(), frequency.getOutput());
            var vepCommand = new VepCommandBuilder(base.properties).build(new VepCommandOptions(input, output));
            write("vep-command.json", base.mapper.valueToTree(vepCommand));
            var vep = smoke.vepRunner().run(vepCommand, work, Duration.ofSeconds(base.properties.getVep().getTimeoutSeconds()));
            write("vep-process.json", base.mapper.valueToTree(vep)); assertTrue(vep.isSuccess(), vep.getOutput());
            report.put("status", "PREFLIGHT_COMPLETE_NOT_BUSINESS_ACCEPTED");
        } finally { report.set("calls", base.mapper.valueToTree(smoke.calls())); write("result.json", report); base.closeDatabase(); }
    }

    @Test @EnabledIfSystemProperty(named="varvis.pm2.multigene.real", matches="true")
    void formalTop20SaveRecreateAndRepeat() throws Exception {
        setup("business"); var base = smoke.base;
        var report = base.mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("pageCovered", false).put("status", "BLOCKED");
        try {
            Path panel = Path.of(System.getProperty("varvis.pm2.multigene.fixedVcf"));
            assertEquals(System.getProperty("varvis.pm2.multigene.fixedSha256"), IntronicScopeRuntimeProbe.sha(panel));
            byte[] vcf = Files.readAllBytes(panel); Files.write(work.resolve("fixed-public.vcf"), vcf);
            write("configuration.json", smoke.configurationProof());
            String resume = System.getProperty("varvis.pm2.multigene.resume", "");
            if (!resume.isBlank()) {
                Path prior = Path.of(resume).toAbsolutePath().normalize();
                assertNotEquals(work, prior);
                assertEquals(vcf.length, Files.size(prior.resolve("fixed-public.vcf")));
                assertEquals(IntronicScopeRuntimeProbe.sha(panel), IntronicScopeRuntimeProbe.sha(prior.resolve("fixed-public.vcf")));
                base.closeDatabase();
                Files.copy(prior.resolve("business-db.mv.db"), work.resolve("business-db.mv.db"), StandardCopyOption.REPLACE_EXISTING);
                // Repositories reconstruct the uploaded path from this root; preserve the actual execution identity.
                base.properties.setStorageRoot(Path.of(System.getProperty("varvis.pm2.multigene.analysisWork", prior.toString())).toAbsolutePath().normalize().resolve("jobs"));
                base.recreateRepositories();
                var priorResult = base.mapper.readTree(prior.resolve("result.json").toFile());
                base.analysisId = priorResult.path("analysisId").asText();
                report.put("resumedAnalysis", true).put("priorDatabaseSha256", databaseSha(prior.resolve("business-db.mv.db")));
                write("prior-analysis-result.json", priorResult);
            } else {
            var validator = new VepResourceValidator(base.properties);
            var annotation = new VepTranscriptAnnotationEnrichmentService(base.jobs, base.properties, validator, new VepCommandBuilder(base.properties),
                    new VepJsonOutputParser(base.mapper), smoke.vepRunner(), new TranscriptAnnotationService(base.properties,
                    new AnnTranscriptAnnotationProvider(), new InMemoryTranscriptAnnotationCache(), validator));
            var processor = new VariantAnalysisJobProcessor(base.jobs, new PicardVcfBuildConverter(base.properties), mock(VcfNormalizer.class), mock(VcfQcService.class),
                    new VariantInterpretationServiceImpl(null, List.of(), new TransparentVariantRankingService(base.properties)), base.properties, annotation);
            ReflectionTestUtils.setField(processor, "acmgBusinessEnabled", true); processor.setAcmgExecutionSources(base.sources);
            processor.setExomiserEngineAdapter(new ConfiguredExomiserEngineAdapter(base.properties, (cmd, dir, out, err, env, timeout) -> {
                smoke.exomiserCalls++; return new SystemExomiserProcessRunner().run(cmd, dir, out, err, env, timeout);
            }, new ExomiserRuntimeValidator()));
            var http = smoke.analysisHttp(smoke.analysisService(processor));
            var upload = base.mapper.readTree(http.perform(multipart("/variant-analyses").file(new MockMultipartFile("vcfFile", "public-multigene.vcf", "text/plain", vcf))
                    .param("genomeAssembly", "GRCh38").param("analysisMode", "WES").param("analysisScope", "INCLUDE_INTRONS").param("sampleId", "synthetic-proband"))
                    .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray());
            write("upload.json", upload); base.analysisId = upload.path("id").asText(); report.put("analysisId", base.analysisId);
            }
            report.put("analysisId", base.analysisId);
            assertEquals(VariantAnalysisStatus.READY, base.jobs.findById(base.analysisId).orElseThrow().getStatus());
            var candidates = base.jobs.findInterpretations(base.analysisId, 0, 20); write("formal-top20.json", base.mapper.valueToTree(candidates));
            assertEquals(20, candidates.size());
            base.closeDatabase(); base.recreateRepositories(); wire();
            var options = base.service.inputOptions(base.analysisId, 20); write("input-options.json", options);
            var request = declaration(options); write("request.json", request);
            var saved = post(request); write("first-response.json", saved); write("first-calls.json", base.mapper.valueToTree(calls()));
            String replay = System.getProperty("varvis.pm2.multigene.replayPopulation", "");
            if (!replay.isBlank()) {
                Path comparison = Path.of(System.getProperty("varvis.pm2.multigene.resume")).resolve("first-response.json");
                var old = base.mapper.readTree(comparison.toFile());
                report.put("comparisonResponseSha256", databaseSha(comparison));
                report.put("retainedPopulationResponseSha256", databaseSha(Path.of(replay)));
                report.put("populationMode", "RETAINED_ACTUAL_EVIDENCE_PLUS_MISSING_ALLELES_ONLY");
                for (int i = 0; i < 20; i++) {
                    var a = old.path("candidates").get(i).at("/attempts/0/pm2");
                    var b = saved.path("candidates").get(i).at("/attempts/0/pm2");
                    if (i != 14) {
                        var beforeResult = a.has("evaluation") ? a.at("/evaluation/evaluationResult") : a.path("evaluationResult");
                        var afterResult = b.has("evaluation") ? b.at("/evaluation/evaluationResult") : b.path("evaluationResult");
                        if (!beforeResult.equals(afterResult)) {
                            // The old helper implicitly selected the only selectable row even when the page kept the relationship unconfirmed.
                            assertTrue(a.has("evaluation"));
                            assertTrue(request.path("recommendations").get(i).path("stableRelationshipKey").isNull());
                            assertTrue(options.path("candidates").get(i).at("/recommendation/stableRelationshipKey").isNull());
                            assertEquals("PM2_VERIFIED_CONTEXT_MISSING", afterResult.path("reasonCode").asText());
                        }
                    }
                    else {
                        assertEquals("HNF1A", candidates.get(i).getExomiserCandidateFeatures().getGeneSymbol());
                        assertTrue(Set.of("MET", "NOT_MET").contains(b.at("/evaluation/evaluationResult/status").asText()), b.toPrettyString());
                        assertEquals(AcmgRecommendations.PM2_CONTEXT_SELECTION, b.at("/context/transcript/selectionPolicy").asText());
                        assertTrue(saved.path("candidates").get(i).at("/attempts/0/criterionEvaluations").isEmpty());
                    }
                }
                assertEquals(0, smoke.exomiserCalls); assertEquals(0, smoke.calls().get("vep"));
                assertEquals(0, smoke.calls().get("spliceBatch"));
                assertEquals(Integer.getInteger("varvis.pm2.multigene.expectedNewPopulationBatches", 1), calls().get("pm2Provider"));
                assertEquals(0, calls().get("allSitesBatch"));
                assertEquals(0, saved.at("/ba1Calls/provider").asInt());
            }
            var before = calls(); base.closeDatabase(); base.recreateRepositories(); wire();
            var read = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            write("saved-get.json", read); assertEquals(saved, read); assertEquals(before, calls());
            var repeated = post(request); write("repeat-post.json", repeated); assertEquals(saved, repeated); assertEquals(before, calls());
            var genes = new HashSet<String>(); var alleles = new HashSet<String>(); var states = new TreeMap<String, Integer>();
            int evaluatedNonVhl = 0, index = 0;
            for (var row : saved.path("candidates")) {
                var original = candidates.get(index++); assertEquals(original.getCandidateId(), row.path("candidateId").asText());
                genes.add(original.getExomiserCandidateFeatures().getGeneSymbol()); alleles.add(original.getObservation().getKey().toString());
                for (var attempt : row.path("attempts")) {
                    var pm2 = attempt.path("pm2"); assertTrue(pm2.isObject());
                    var evaluation = pm2.has("evaluation") ? pm2.path("evaluation") : pm2;
                    String state = evaluation.at("/evaluationResult/status").asText();
                    states.merge(state, 1, (a, b) -> Objects.requireNonNull(a) + Objects.requireNonNull(b));
                    if (Set.of("MET", "NOT_MET").contains(state) && !"VHL".equals(original.getExomiserCandidateFeatures().getGeneSymbol())) evaluatedNonVhl++;
                }
            }
            report.put("formalCandidates", candidates.size()).put("independentAlleles", alleles.size()).put("geneCount", genes.size()).put("evaluatedNonVhl", evaluatedNonVhl);
            report.set("states", base.mapper.valueToTree(states)); report.set("genes", base.mapper.valueToTree(genes));
            assertTrue(genes.size() >= 4); assertTrue(evaluatedNonVhl >= 3); assertEquals(20, index);
            report.put("readbackIdentical", true).put("repeatIdentical", true).put("status", "TOP20_BACKEND_VERIFIED");
        } catch (Exception | AssertionError failure) { report.put("failure", failure.toString()); throw failure; }
        finally { report.set("calls", base.mapper.valueToTree(calls())); write("result.json", report); base.closeDatabase(); }
    }

    private ObjectNode declaration(ObjectNode options) throws Exception {
        Path output = work.resolve("page-declaration.json");
        var generated = new SystemExternalProcessRunner().run(List.of("node.exe",
                Path.of("../scripts/pm2/build_business_declaration.mjs").toAbsolutePath().normalize().toString(),
                work.resolve("input-options.json").toString(), output.toString()), work, Duration.ofSeconds(30));
        assertTrue(generated.isSuccess(), generated.getOutput());
        var result = (ObjectNode) smoke.base.mapper.readTree(output.toFile());
        assertEquals(options.path("analysisId").asText(), smoke.base.analysisId);
        return result;
    }

    @SuppressWarnings("unchecked") private void wire() {
        smoke.wireBusiness();
        var service = Objects.requireNonNull(smoke.base.service);
        var factory = (Supplier<Pm2SupportingRuntime>) Objects.requireNonNull(ReflectionTestUtils.getField(service, "pm2Runtime"));
        ReflectionTestUtils.setField(service, "pm2Runtime", (Supplier<Pm2SupportingRuntime>) () -> {
            var runtime = Objects.requireNonNull(factory.get());
            replayPopulation(runtime);
            runtimes.add(runtime); return runtime;
        });
        smoke.base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(smoke.base.service)).build();
    }
    @SuppressWarnings("unchecked") private void replayPopulation(@org.springframework.lang.NonNull Pm2SupportingRuntime runtime) {
        String path = System.getProperty("varvis.pm2.multigene.replayPopulation", "");
        if (path.isBlank()) return;
        try {
            var saved = smoke.base.mapper.readTree(Path.of(path).toFile());
            var joint = (Map<NormalizedVariantKey, ObjectNode>) Objects.requireNonNull(ReflectionTestUtils.getField(runtime, "jointEvidence"));
            var alleles = (Map<NormalizedVariantKey, JsonNode>) Objects.requireNonNull(ReflectionTestUtils.getField(runtime, "preparedAlleles"));
            var sites = (Map<NormalizedVariantKey, List<ObjectNode>>) Objects.requireNonNull(ReflectionTestUtils.getField(runtime, "allSitesEvidence"));
            var raw = (Map<NormalizedVariantKey, String>) Objects.requireNonNull(ReflectionTestUtils.getField(runtime, "rawRecords"));
            for (var row : saved.path("candidates")) for (var attempt : row.path("attempts")) {
                var pm2 = attempt.path("pm2"); if (!pm2.has("evidence")) continue;
                assertEquals(GnomadPopulationInputProfile.loadBusinessPm2Supporting().getProfileSha256(), pm2.path("profileSha256").asText());
                var allele = pm2.at("/context/variant/evidenceQueryAllele");
                var key = new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(), allele.path("position").asInt(),
                        allele.path("reference").asText(), allele.path("alternate").asText());
                joint.put(key, (ObjectNode) pm2.path("evidence").deepCopy()); alleles.put(key, allele.deepCopy());
                if (pm2.path("rawPopulationRecord").isTextual()) raw.put(key, pm2.path("rawPopulationRecord").asText());
                var entries = new ArrayList<ObjectNode>();
                for (var e : pm2.path("allSitesEvidence")) entries.add((ObjectNode) e.deepCopy());
                sites.put(key, entries);
            }
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Retained population evidence unreadable", invalid); }
    }
    private Map<String, Integer> calls() {
        var counts = new TreeMap<>(smoke.calls());
        counts.put("pm2Provider", runtimes.stream().mapToInt(r -> Objects.requireNonNull(r).providerCalls()).sum());
        counts.put("pm2Evaluator", runtimes.stream().mapToInt(r -> Objects.requireNonNull(r).evaluatorCalls()).sum());
        counts.put("allSitesBatch", runtimes.stream().mapToInt(r -> Objects.requireNonNull(r).allSitesBatches()).sum()); return counts;
    }
    private ObjectNode post(ObjectNode request) throws Exception {
        var exchange = smoke.base.http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/{id}/acmg/runs", smoke.base.analysisId)
                .contentType("application/json").content(Objects.requireNonNull(smoke.base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        assertEquals(200, exchange.getStatus(), exchange.getErrorMessage()); return (ObjectNode) smoke.base.mapper.readTree(exchange.getContentAsByteArray());
    }
    private void write(String name, JsonNode value) throws Exception { Files.writeString(work.resolve(name), value.toPrettyString(), StandardOpenOption.CREATE_NEW); }
    private String databaseSha(Path database) throws Exception {
        assertTrue(Files.size(database) <= 64L * 1024 * 1024, "Only this bounded engineering database is accepted");
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(database)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test @EnabledIfSystemProperty(named="varvis.pm2.multigene.recheck", matches=".+")
    void recheckActualSavedEvidenceWithoutCallingResources() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Path input = Path.of(System.getProperty("varvis.pm2.multigene.recheck"));
        String before = databaseSha(input);
        var saved = mapper.readTree(input.toFile());
        var profile = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var output = mapper.createObjectNode().put("status", "READ_ONLY_ACTUAL_EVIDENCE_REEVALUATION")
                .put("sourceResponseSha256", before).put("providerCalls", 0).put("modelCalls", 0);
        var values = output.putArray("evaluations");
        for (var candidate : saved.path("candidates")) for (var attempt : candidate.path("attempts")) {
            var pm2 = attempt.path("pm2"); if (!pm2.has("evaluation")) continue;
            assertEquals(profile.getProfileSha256(), pm2.path("profileSha256").asText());
            ObjectNode exomes = null, genomes = null;
            for (var evidence : pm2.path("allSitesEvidence")) {
                if (profile.getDocument().at("/resources/1/resourceId").equals(evidence.at("/source/sourceId"))) exomes = (ObjectNode) evidence;
                if (profile.getDocument().at("/resources/2/resourceId").equals(evidence.at("/source/sourceId"))) genomes = (ObjectNode) evidence;
            }
            var result = new GnomadPopulationCriterionEvaluator(mapper).evaluatePm2Supporting(pm2.path("context"), (ObjectNode) pm2.path("evidence"),
                    exomes, genomes, profile, profile.getSuccessorRegistryDocument(), pm2.at("/policySelection/selectionId").asText());
            assertEquals(pm2.at("/evaluation/evaluationResult"), result.path("evaluationResult"));
            assertEquals(pm2.at("/evaluation/appliedStrength"), result.path("appliedStrength"));
            values.add(result);
        }
        assertEquals(14, values.size()); assertEquals(before, databaseSha(input));
        Files.writeString(Path.of(System.getProperty("varvis.pm2.multigene.recheckOutput")), output.toPrettyString(), StandardOpenOption.CREATE_NEW);
    }
}
