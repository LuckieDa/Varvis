package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Normal publication/CURRENT/HTTP/Bs1Runtime/H2 using the already adopted GN017 LOCAL_EXOMES policy.
 * MANE/ClinGen, the migrated reference receipt and all population rows are real pinned originals.
 * Candidate ranking, GT and patient declarations are SYNTHETIC. Annotation and population transports
 * replay preserved original bytes; no fresh VEP, Exomiser, population download or clinical claim.
 * This existing scalar control does not qualify T03's new OR/AC/sex-chromosome condition families.
 */
class T03RealFrequencySourceBusinessTest {
    private static final String BS1 = "ACMG_AMP_2015:BS1";
    private static final String RECEIPT_SHA = "c7f794b9f7831c6130ea190729777473931cb4046993027aec52f16711a31a7b";
    private static final String FASTA_SHA = "8d5e868ddc6d9600b82cd7b7e625c240a43f369f6f1a9f16b0c516eb781e7988";
    private static final String FAI_SHA = "7c75fc355d5d28470b0188e5a1c0697007f388cb8f9c899a14e4f13b8baf0176";
    private static final String POLICY_ID = "GN017_3_1_BS1_LOCAL_EXOMES";
    private static final List<Integer> POSITIONS = List.of(120988847, 120993678, 120989017);
    private static final List<String> REFS = List.of("G", "C", "C");
    private static final List<String> ALTS = List.of("A", "T", "T");
    private static final List<String> EXPECTED = List.of("MET", "NOT_MET", "NOT_EVALUATED");
    private static final List<String> RAW_SHAS = List.of(
            "dbfc8c34b011e2f46c8fa5b1ad48fcbc8f284c13f4fd1834ba42340368d15a9c",
            "1032fe522b0602f3552b0e9fe6bfa47d0244d574ad58f0e3f60c225a2b2b6c97",
            "f45061db2798440970211ce2e56615f44a5c8853ee87251e01265507344963f1");
    @TempDir Path temporary;
    private Path project, resources, originals, queryLog;
    private AcmgBusinessIntegrationTest base;
    private JsonNode retainedCandidates;
    private ObjectNode pins;
    private boolean databaseClosed;

    @BeforeEach void setup() throws Exception {
        String configured = System.getProperty("varvis.t03.real.frequency.project-root");
        assertNotNull(configured, "Explicit existing project root required; this test never substitutes synthetic scientific sources");
        assertFalse(configured.isBlank());
        project = Path.of(configured).toAbsolutePath().normalize();
        resources = project.resolve("local/resources");
        originals = project.resolve("local/development/handoffs/BS1-local-exomes-20261009/dev-01");
        base = new AcmgBusinessIntegrationTest() {
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() throws Exception {
                // Only a transport seam: exact allele association and untouched retained VEP annotations.
                // The production processor still publishes and validates its own execution/source snapshot.
                var replay = mock(VepTranscriptAnnotationEnrichmentService.class);
                when(replay.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
                    List<VariantInterpretation> rows = call.getArgument(0);
                    var values = new LinkedHashMap<String, List<TranscriptConsequence>>();
                    for (var candidate : rows) {
                        var key = candidate.getObservation().getKey();
                        JsonNode selected = null;
                        for (var retained : retainedCandidates) {
                            var original = retained.at("/observation/key");
                            if (key.getPosition() == original.path("position").asInt()
                                    && key.getReference().equals(original.path("reference").asText())
                                    && key.getAlternate().equals(original.path("alternate").asText())
                                    && "12".equals(original.path("chromosome").asText())) {
                                if (selected != null) assertEquals(selected, retained.path("transcriptAnnotations"),
                                        "Duplicate old AD/AR ranked rows must have identical source annotations");
                                selected = retained.path("transcriptAnnotations");
                            }
                        }
                        assertNotNull(selected, "Missing retained exact-allele VEP original: " + key);
                        values.put(candidate.getVariantId(), mapper.convertValue(selected,
                                new TypeReference<List<TranscriptConsequence>>() { }));
                    }
                    return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(values,
                            VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                });
                return replay;
            }
        };
        base.temporary = temporary;
        base.setup();
        pins = base.mapper.createObjectNode().put("layer", "REAL_PINNED_PUBLIC_SOURCES_SYNTHETIC_CASE")
                .put("candidateAndGt", "SYNTHETIC").put("annotation", "RETAINED_ACTUAL_VEP_116_NO_FRESH_VEP")
                .put("population", "EXACT_RETAINED_EXOMES_4_1_RAW_TRANSPORT_REPLAY")
                .put("sourcePolicy", "EXISTING_LOCAL_EXOMES_NOT_FULL_JOINT_ADOPTION");
        pins.putArray("files");
        retainedCandidates = base.mapper.readTree(pinned(originals.resolve("formal-candidates.json"),
                "87c406934e8e5fe7be508dd871092cb88a810b6d8e15e0623550af12969716d0"));
        Path binding = resources.resolve("acmg-context/e4-a1a-2026-09-05");
        pinned(binding.resolve("manifest.json"), "7019056f31ab44bb8955381baed1cbd24cb72c220898685e3dc7862128ba761b");
        pinned(binding.resolve("mane-crosswalk.json"), "93c11aaa99cfc1ffcd07d0e02b5f5d6ba5f07789b4340efe463add77de7234fc");
        pinned(binding.resolve("clingen-catalog.json"), "af957954c3ab82cb9c57dde002cd911cc7be88f71c807c216ad388f9f50623f4");
        pinned(project.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture/raw-010-contents-10.json"),
                "87d34bf90904413720591669d431018ea271d313da92b0d10fe46fc7d4fe2022");
        base.fixture.resolver = PatientDiseaseContextBindingResolver.load(base.mapper, binding.resolve("manifest.json"),
                binding.resolve("mane-crosswalk.json"), binding.resolve("clingen-catalog.json")).withLocalInterpretationContexts();
        Path receipt = project.resolve("local/private/receipts/reference38-runtime.json");
        JsonNode reference = base.mapper.readTree(pinned(receipt, RECEIPT_SHA)).at("/records/reference/actualChecksumReceipt");
        assertEquals("reference/grch38.p14.primary-chr.fa", reference.path("logicalName").asText());
        assertEquals(FASTA_SHA, reference.path("observedSha256").asText());
        Path fasta = resources.resolve(reference.path("logicalName").asText());
        assertEquals(reference.path("byteLength").asLong(), Files.size(fasta));
        assertEquals(reference.path("mtimeNs").asLong(), Files.getLastModifiedTime(fasta).to(TimeUnit.NANOSECONDS));
        pinned(Path.of(fasta + ".fai"), FAI_SHA); // 788-byte index only; never hash the 3GB FASTA.
        var ledger = ExplicitContextSourceLedger.load(base.mapper, receipt, RECEIPT_SHA);
        base.fixture.sources.clear();
        base.fixture.sources.add(ledger);
        base.fixture.reference = ledger.reference("reference");
        var normalization = new VariantAnalysisProperties();
        normalization.getNormalization().setGrch38Reference(fasta.toString());
        normalization.getNormalization().setGrch38ReferenceSha256(FASTA_SHA);
        normalization.getNormalization().setGrch38ProfileId(reference.path("resourceId").asText());
        assertEquals("READY", new NormalizationResourceValidator(normalization).validate(GenomeAssembly.GRCh38).getStatus());
        base.producer = new NativeSnvSourceProducer(base.mapper, normalization, resources,
                base.fixture.sources, base.fixture.reference); // Real receipt and actual indexed REF reader, no readiness override.
        var lines = new ArrayList<String>();
        var variants = new ArrayList<String>();
        for (int i = 0; i < POSITIONS.size(); i++) {
            lines.add((i + 1) + "\t12\t" + POSITIONS.get(i) + "\t" + REFS.get(i) + "\t" + ALTS.get(i)
                    + "\tHNF1A\t6927\tAD\t" + (0.9 - i * 0.1));
            variants.add("chr12\t" + POSITIONS.get(i) + "\tSYNTHETIC_CASE_PUBLIC_ALLELE_" + i + "\t"
                    + REFS.get(i) + "\t" + ALTS.get(i) + "\t99\tPASS\t.\tGT\t0/1");
        }
        base.expectedPublishedCount = 3;
        base.variantTsvOverride = tsv(lines.toArray(String[]::new));
        base.publish(false, GenomeAssembly.GRCh38, vcf(variants.toArray(String[]::new)));
        wire();
        archive("source-pins.json", pins);
    }

    private byte[] pinned(Path file, String expected) throws Exception {
        assertTrue(Files.size(file) <= 32L * 1024 * 1024, "Only small source files may enter this hash helper");
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(expected, org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.sha(bytes), file.toString());
        pins.withArray("files").addObject().put("file", file.toString()).put("bytes", bytes.length).put("sha256", expected);
        return bytes;
    }

    private void wire() throws Exception {
        queryLog = temporary.resolve("population-replay-queries.txt");
        Path replay = temporary.resolve("retained-public-population-tabix.cmd");
        StringBuilder command = new StringBuilder("@echo off\r\necho query>>\"%~dp0population-replay-queries.txt\"\r\n");
        for (int i = 0; i < POSITIONS.size(); i++) {
            Path file = originals.resolve("expected-" + POSITIONS.get(i) + ".raw");
            pinned(file, RAW_SHAS.get(i));
            // Untouched bounded original record superset. Exact query ALT, QC, parsing and judgments stay in production.
            command.append("type \"").append(file).append("\"\r\n");
        }
        command.append("exit /b 0\r\n");
        Files.writeString(replay, command, StandardCharsets.UTF_8);
        var settings = new VariantAnalysisProperties();
        var population = settings.getGnomad();
        population.setEnabled(true); population.setPopulationInputEnabled(true); population.setBa1LocalExomesEnabled(true);
        population.setVersion("gnomAD exomes v4.1 GRCh38"); population.setParallelism(1);
        population.setTabixExecutable(replay.toString()); population.setTabixCacheMb(0);
        population.setLocalPopulationInProcessEnabled(false); population.setAllowFullVcfFallback(false);
        population.setAllowVcfInfoFallback(false); population.setTimeout(Duration.ofSeconds(10));
        population.setRemoteTotalTimeout(Duration.ofSeconds(10)); population.setRemoteMaxAttempts(1);
        Path data = resources.resolve("gnomad/gnomad.exomes.v4.1.sites.chr12.vcf.bgz");
        assertTrue(Files.isRegularFile(data)); assertTrue(Files.isRegularFile(Path.of(data + ".tbi")));
        population.setGrch38Files(data.toString()); // Registered actual file identity, no new VCF or fake index.
        var fixture = new Ba1BusinessIntegrationTest();
        fixture.base = base; fixture.temporary = temporary; fixture.am = new BusinessAlphaMissenseFixture();
        fixture.ba1 = new Ba1AlleleRuntime(base.mapper, settings, Ba1SpecificationCatalog.bundled(base.mapper));
        fixture.ba1Binding = fixture.ba1.identity(); fixture.wire();
        var formal = AcmgBusinessService.formalPopulation(base.service,
                () -> Bs1Runtime.routed(base.mapper, true, settings, settings, true, null, null));
        Files.createDirectories(temporary.resolve("experiments"));
        var local = AcmgBusinessService.localBatch(formal, () -> new LocalEvidenceBatchRuntime(base.mapper,
                base.mapper.createObjectNode().put("dataClass", "SYNTHETIC")
                        .put("scope", "T03_REAL_SOURCE_SCALAR_CONTROL_OTHER_MODULES_UNCONFIGURED"),
                null, null, null, null,
                new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,
                        new ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                        (analysis, run) -> { throw new AssertionError("Catalog capture must not execute business"); })
                        .captureCatalog())));
        base.service = AcmgBusinessService.unifiedLocal(formal, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(new AcmgBusinessWorkflows(base.service, null))).build();
    }

    @Test void realSourceSingleCriterionKeepsMetNotMetAndExactAltUnknownThroughPublicDetailAndHistory() throws Exception {
        var rankingBefore = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        int queriesBefore = queries();
        JsonNode options = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "3").param("workflow", "CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        archive("input-options.json", options);
        assertTrue(options.path("executionSourceRecovered").asBoolean(), options.toPrettyString());
        assertEquals(3, options.path("selectedCount").asInt());
        base.request.put("topN", 3).put("dataClass", "SYNTHETIC")
                .put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        base.request.putArray("selectedCriteria").add(BS1); base.request.putArray("hypotheses");
        var choices = base.request.putArray("recommendations");
        for (var candidate : options.path("candidates")) {
            var recommendation = candidate.path("recommendation");
            assertEquals("MANE_GRCh38_SUMMARY", recommendation.at("/geneIdentitySource/resourceId").asText());
            assertEquals("CLINGEN_GENE_DISEASE_VALIDITY", recommendation.at("/relationshipSource/resourceId").asText());
            assertTrue(recommendation.at("/bs1ContextPreparation/available").asBoolean(), recommendation.toPrettyString());
            JsonNode selected = null;
            for (var relationship : recommendation.path("relationships")) {
                if ("HGNC:11621".equals(relationship.path("hgncId").asText())
                        && "MONDO:0015967".equals(relationship.path("mondoId").asText())
                        && "AD".equals(relationship.path("moi").asText())) {
                    for (var eligible : recommendation.at("/bs1ContextPreparation/eligibleRelationshipKeys"))
                        if (eligible.equals(relationship.path("stableRelationshipKey"))) selected = eligible;
                }
            }
            assertNotNull(selected, recommendation.toPrettyString());
            var choice = choices.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"))
                choice.set(field, recommendation.path(field));
            choice.set("stableRelationshipKey", selected); choice.putNull("annotationId");
        }
        assertEquals(queriesBefore, queries(), "Options must not query population sources");
        archive("request.json", base.request);
        var saved = base.postRequest(base.request);
        archive("public-result.json", saved);
        assertEquals(base.mapper.valueToTree(List.of(BS1)), saved.path("enabledCriteria"));
        assertEquals(3, saved.path("candidates").size(), saved.toPrettyString());
        for (int i = 0; i < POSITIONS.size(); i++) {
            var candidate = saved.path("candidates").path(i);
            var result = candidate.at("/attempts/0/bs1");
            assertEquals(EXPECTED.get(i), result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
            assertEquals(POLICY_ID, result.at("/preparation/policy/policyId").asText(), result.toPrettyString());
            if (i == 0) assertEquals("STRONG", result.at("/evaluation/appliedStrength").asText(), result.toPrettyString());
            if (i == 2) assertEquals("BS1_NO_RECORD", result.at("/evaluation/evaluationResult/reasonCode").asText(), result.toPrettyString());
        }
        assertEquals(rankingBefore, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
        assertTrue(queries() > queriesBefore, "The actual provider transport must execute during POST");
        int completedQueries = queries();
        assertEquals(saved, base.postRequest(base.request));
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        publicDetails(saved);
        assertEquals(completedQueries, queries(), "Repeat/read/detail cannot rerun evidence");
        var stored = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        assertTrue(stored.privateJson().contains(RECEIPT_SHA), "Private source graph must retain the actual reference receipt");
        archive("source-publication.json", base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()));
        archive("private-result.json", base.mapper.readTree(stored.privateJson()));
        freshHistory(saved, stored.responseJson(), stored.privateJson());
        assertEquals(completedQueries, queries(), "Independent historical reader cannot invoke providers");
    }

    private void publicDetails(JsonNode saved) throws Exception {
        String run = saved.path("runId").asText(), root = "/variant-analyses/" + base.analysisId + "/acmg/runs/" + run;
        JsonNode index = base.mapper.readTree(base.http.perform(get(root + "/view")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        archive("public-index.json", index);
        for (int i = 0; i < 3; i++) {
            String candidate = saved.path("candidates").path(i).path("candidateId").asText();
            JsonNode detail = base.mapper.readTree(base.http.perform(get(root + "/view/background")
                    .param("candidateId", candidate).param("backgroundIndex", "0")
                    .param("sourceSha256", index.path("sourceViewSha256").asText())).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray());
            archive("public-detail-" + i + ".json", detail);
            var result = detail.at("/candidates/0/attempts/0/bs1");
            assertEquals(saved.path("candidates").path(i).at("/attempts/0/bs1"), result);
            assertEquals(EXPECTED.get(i), result.at("/evaluation/evaluationResult/status").asText());
        }
    }

    private void freshHistory(JsonNode saved, String publicJson, String privateJson) throws Exception {
        byte[] full = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Files.writeString(temporary.resolve("expected-public.json"), publicJson);
        Files.writeString(temporary.resolve("expected-private.json"), privateJson);
        Files.write(temporary.resolve("expected-full-get.json"), full);
        // The writer/test JVM remains alive. This verifies a separate Reader JVM
        // after the writer's database closes, not reading after writer-process exit.
        base.closeDatabase(); databaseClosed = true;
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(javaExecutable)) javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("fresh-jvm-reader.log");
        Process reader = new ProcessBuilder(javaExecutable.toString(), "-cp", cp,
                Bs1RoutedPopulationBusinessTest.FreshJvmHistoryReader.class.getName(), temporary.toString(),
                base.analysisId, saved.path("runId").asText()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = reader.waitFor(45, TimeUnit.SECONDS);
        if (!ended) { reader.destroyForcibly(); reader.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(ended, "Existing historical Service reader timed out");
        assertEquals(0, reader.exitValue(), Files.readString(log));
        assertArrayEquals(full, Files.readAllBytes(temporary.resolve("fresh-jvm-export.json")));
        assertEquals(privateJson, Files.readString(temporary.resolve("fresh-jvm-private.json")));
        archive("fresh-jvm-full-get.json", base.mapper.readTree(full));
        archive("fresh-jvm-private.json", base.mapper.readTree(Files.readString(temporary.resolve("fresh-jvm-private.json"))));
        String output = System.getProperty("varvis.t03.real.frequency.output");
        if (output != null) Files.copy(log, Path.of(output).resolve("fresh-jvm-reader.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private int queries() throws Exception { return Files.exists(queryLog) ? Files.readAllLines(queryLog).size() : 0; }
    private void archive(String name, JsonNode value) throws Exception {
        String output = System.getProperty("varvis.t03.real.frequency.output");
        if (output == null || output.isBlank()) return;
        Path folder = Path.of(output); Files.createDirectories(folder);
        Files.write(folder.resolve(name), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }
    @AfterEach void close() { if (base != null && !databaseClosed) base.closeDatabase(); }
}
