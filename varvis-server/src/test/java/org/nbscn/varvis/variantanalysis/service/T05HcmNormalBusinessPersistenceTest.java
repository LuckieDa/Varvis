package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.Bs1Runtime;
import org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog;
import org.nbscn.varvis.variantanalysis.provider.CodingTranscriptMaterialSupply;
import org.nbscn.varvis.variantanalysis.provider.GencodeTranscriptIndex;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.nbscn.varvis.variantanalysis.provider.UnifiedEighteenCompanionFixtures;
import org.nbscn.varvis.variantanalysis.provider.DeNovoBatchFixture;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Normal publication and central configuration with a synthetic SNV on a complete real reference model.
 * Source-scope absence remains UNKNOWN. No real patient, VEP execution, or PP2 positive is asserted. */
class T05HcmNormalBusinessPersistenceTest {
    private static final String P = "variant-analysis.acmg-business.", C = "ACMG_AMP_2015:";
    private static final String BOUNDED_QUALIFICATION = "pp2-bounded-literature-qualification-v1";
    private static final String REF_SHA = "8d5e868ddc6d9600b82cd7b7e625c240a43f369f6f1a9f16b0c516eb781e7988";
    private static final String LEDGER_SHA = "c7f794b9f7831c6130ea190729777473931cb4046993027aec52f16711a31a7b";
    private static final String INDEX_SHA = "44a09eb4bfc2e1cf5c4e5d7bc90b56958866e2f3fe6b9fce6697554c87e8cc42";
    @TempDir Path temporary;

    @Test void fullReferenceHcmUnknownSurvivesNormalSelectedRunsWriterExitAndReadOnlyExport() throws Exception {
        String configured = System.getProperty("t05.hcm.normal.output");
        Path output = Files.createDirectories(configured == null ? temporary.resolve("products") : Path.of(configured));
        Path state = Files.createDirectories(temporary.resolve("state"));
        Path writerLog = output.resolve("writer.log"), readerLog = output.resolve("reader.log");
        var writer = start(Writer.class, state, output, writerLog); waitFor(writer, writerLog);
        assertFalse(writer.isAlive());
        var mapper = new ObjectMapper();
        var manifest = mapper.readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
        assertEquals(writer.pid(), manifest.path("writerPid").asLong());
        assertTrue(manifest.path("databaseClosed").asBoolean());
        Path database = state.resolve("business-db.mv.db");
        String before = sha(Files.readAllBytes(database));
        var reader = start(Reader.class, state, output, readerLog); waitFor(reader, readerLog);
        assertNotEquals(writer.pid(), reader.pid()); assertFalse(reader.isAlive());
        assertEquals(before, sha(Files.readAllBytes(database)), "Read-only Reader cannot rewrite H2 bytes");
        var receipt = mapper.readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertTrue(receipt.path("passed").asBoolean()); assertEquals(4, receipt.path("runs").size());
        assertTrue(receipt.path("writerExitedBeforeReader").asBoolean());
    }

    @Test void nonzeroLiteratureCoreAndIndependentQuestionsSurviveNormalWriterExitAndReadOnlyExport() throws Exception {
        String configured = System.getProperty("t05.hcm.nonzero.output");
        Path output = Files.createDirectories(configured == null ? temporary.resolve("nonzero-products") : Path.of(configured));
        Path shortRoot = Files.createDirectories(Path.of(required("t05.hcm.sourceRoot")).resolve("local/temp"));
        Path state = Files.createTempDirectory(shortRoot, "p2n-");
        Path writerLog = output.resolve("writer.log"), readerLog = output.resolve("reader.log");
        var writer = start(NonzeroWriter.class, state, output, writerLog); waitFor(writer, writerLog);
        var mapper = new ObjectMapper();
        var manifest = mapper.readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
        assertEquals(writer.pid(), manifest.path("writerPid").asLong());
        assertTrue(manifest.path("databaseClosed").asBoolean()); assertEquals(1, manifest.path("runs").size());
        assertEquals("SYNTHETIC_PUBLIC_FORMAT_ARTICLE_AND_MODEL_RESPONSES_WITH_REAL_REFERENCE", manifest.path("sourceMaterialClass").asText());
        Path database = state.resolve("business-db.mv.db"); String before = sha(Files.readAllBytes(database));
        var reader = start(Reader.class, state, output, readerLog); waitFor(reader, readerLog);
        assertFalse(writer.isAlive()); assertFalse(reader.isAlive()); assertNotEquals(writer.pid(), reader.pid());
        assertEquals(before, sha(Files.readAllBytes(database)), "Read-only Reader cannot rewrite H2 bytes");
        var receipt = mapper.readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertTrue(receipt.path("passed").asBoolean()); assertEquals(1, receipt.path("runs").size());
        assertTrue(receipt.path("writerExitedBeforeReader").asBoolean()); assertTrue(receipt.path("nonzeroRawReadingVerified").asBoolean());
        assertTrue(receipt.path("nonzeroBoundedQualificationVerified").asBoolean());
        assertEquals(0, receipt.path("forbiddenScientificFactories").asInt(-1));
    }

    private static Process start(Class<?> entry, Path state, Path output, Path log) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin/java.exe");
        if (!Files.exists(java)) java = Path.of(System.getProperty("java.home"), "bin/java");
        var command = new ArrayList<String>(List.of(java.toString(), "-Xmx768m", "-Xshare:off", "-ea", "-Dfile.encoding=UTF-8"));
        for (String key : List.of("t05.hcm.sourceRoot", "t05.hcm.knowledge", "varvis.pp1.python", "t05.hcm.scripts", "t05.hcm.nonzero.seedBuilder")) {
            String value = System.getProperty(key); if (value != null) command.add("-D" + key + "=" + value);
        }
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                entry.getName(), state.toString(), output.toString()));
        return new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void waitFor(Process process, Path log) throws Exception {
        boolean finished = false;
        try {
            for (int i = 0; i < 4 && !finished; i++) finished = process.waitFor(45, TimeUnit.SECONDS);
            assertTrue(finished, "Child timeout; see " + log);
            assertEquals(0, process.exitValue(), () -> readLog(log));
        } finally {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
        }
    }
    private static String readLog(Path log) { try { return Files.readString(log); } catch (Exception failed) { return failed.toString(); } }
    @org.springframework.lang.NonNull
    private static String sha(byte[] bytes) { return java.util.Objects.requireNonNull(Bs1KnowledgeCatalog.sha(bytes)); }
    @org.springframework.lang.NonNull
    private static String path(Path value) { return java.util.Objects.requireNonNull(value.toString()); }

    public static final class NonzeroWriter {
        private NonzeroWriter() { }
        public static void main(String[] args) throws Exception {
            assertEquals(2, args.length); var writer = new Writer(Path.of(args[0]), Path.of(args[1]));
            try { writer.prepare(); writer.runNonzero(); }
            finally { if (writer.base != null) writer.base.closeDatabase(); }
            writer.manifest.put("databaseClosed", true).put("writerCompleted", true);
            writer.write(writer.output.resolve("reader-manifest.json"), writer.manifest);
        }
    }

    public static final class Writer {
        private final Path state, output, code, knowledge, python, scripts;
        private AcmgBusinessIntegrationTest base;
        private ObjectNode model, manifest;
        private TranscriptConsequence annotation;
        private Path workflow;
        private List<String> enabled;
        private Writer(Path state, Path output) {
            this.state = state; this.output = output;
            code = Path.of(required("t05.hcm.sourceRoot")); knowledge = Path.of(required("t05.hcm.knowledge"));
            python = Path.of(required("varvis.pp1.python")); scripts = Path.of(required("t05.hcm.scripts"));
        }
        public static void main(String[] args) throws Exception {
            assertEquals(2, args.length); var writer = new Writer(Path.of(args[0]), Path.of(args[1]));
            try { writer.prepare(); writer.run(); }
            finally { if (writer.base != null) writer.base.closeDatabase(); }
            writer.manifest.put("databaseClosed", true).put("writerCompleted", true);
            writer.write(writer.output.resolve("reader-manifest.json"), writer.manifest);
        }
        private void prepare() throws Exception {
            base = new AcmgBusinessIntegrationTest() {
                @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() {
                    var transport = mock(VepTranscriptAnnotationEnrichmentService.class);
                    when(transport.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
                        List<VariantInterpretation> rows = call.getArgument(0);
                        var values = new LinkedHashMap<String, List<TranscriptConsequence>>();
                        for (var row : rows) values.put(row.getVariantId(), List.of(annotation));
                        return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(values,
                                VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                    }); return transport;
                }
            };
            base.temporary = state; base.setup(); reference(base.properties, code);
            var ledger = ExplicitContextSourceLedger.load(base.mapper, ledgerFile(), LEDGER_SHA);
            base.fixture.sources.add(ledger);
            base.producer = new NativeSnvSourceProducer(base.mapper, base.properties, code.resolve("local/resources"), base.fixture.sources, ledger.reference("reference"));
            Path bindings = code.resolve("local/resources/acmg-context/e4-a1a-2026-09-05");
            base.fixture.resolver = PatientDiseaseContextBindingResolver.load(base.mapper, bindings.resolve("manifest.json"),
                    bindings.resolve("mane-crosswalk.json"), bindings.resolve("clingen-catalog.json"));
            var reader = new CodingTranscriptReferenceReader(base.mapper, base.properties, code.resolve("local/resources"), ledger, "reference");
            var context = base.mapper.createObjectNode();
            context.putObject("gene").put("symbol", "TPM1").put("hgncId", "HGNC:12010");
            context.putObject("transcript").put("accession", "ENST00000403994").put("version", 9);
            context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "15");
            context.withObject("variant").putObject("normalization").put("referenceSequenceSha256", REF_SHA);
            var index = new GencodeTranscriptIndex(base.mapper, indexDirectory(), INDEX_SHA);
            model = index.model(context, reader);
            assertEquals(1217, model.path("transcriptLength").asInt()); assertEquals(855, model.path("cdsLength").asInt());
            assertEquals(284, model.path("proteinLength").asInt()); assertEquals(10, model.path("exons").size());
            assertEquals("G", reader.read("15", 63042833, 1).bases());
            String codon = model.path("cdsSequence").asText().substring(3, 6);
            assertEquals("GAC", codon);
            var a = base.mapper.createObjectNode().put("geneSymbol", "TPM1").put("transcriptAccession", "ENST00000403994")
                    .put("transcriptId", "ENST00000403994.9").put("hgvsCoding", "ENST00000403994.9:c.4G>A")
                    .put("hgvsProtein", "ENSP00000385107.4:p.Asp2Asn").put("consequence", "missense_variant")
                    .put("exon", "1/10").put("proteinPosition", "2").put("aminoAcids", "D/N").put("codons", codon + "/A" + codon.substring(1))
                    .put("source", "VEP_SYNTHETIC_T05_REAL_REFERENCE_CONTROL").put("status", "COMPLETED").put("version", "116.0")
                    .put("timestamp", "2026-09-01T00:00:00Z");
            annotation = base.mapper.treeToValue(a, TranscriptConsequence.class);
            base.expectedPublishedCount = 1; base.variantTsvOverride = tsv("1\t15\t63042833\tG\tA\tTPM1\t7168\tAD\t0.8");
            base.publish(false, GenomeAssembly.GRCh38, vcf("chr15\t63042833\t.\tG\tA\t.\tPASS\t.\tGT\t0/1"));
            assertEquals("TPM1", base.jobs.findInterpretations(base.analysisId, 0, 1).get(0).getCandidateIdentity().getGeneSymbol());
            var source = base.store.source(base.analysisId).orElseThrow();
            Files.writeString(output.resolve("expected-source.json"), source.json(), StandardCharsets.UTF_8);
            write(output.resolve("actual-model.json"), model); write(output.resolve("synthetic-annotation.json"), a);
            manifest = base.mapper.createObjectNode().put("schemaVersion", "t05-hcm-normal-writer-reader-v1")
                    .put("analysisId", base.analysisId).put("sourceSha256", source.sha256()).put("writerPid", ProcessHandle.current().pid())
                    .put("dataClass", "SYNTHETIC").put("liveVepExecuted", false).put("realPatient", false)
                    .put("clinicalPp2Positive", false).put("coreFactoryOverridden", false)
                    .put("referenceSha256", REF_SHA).put("fullModelSha256", new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(model));
            manifest.putArray("runs");
        }
        private void run() throws Exception {
            workflow = state.resolve("missing-workflow.json"); install(false);
            var missing = submit("MISSING_CONFIGURATION_PP2", List.of(C + "PP2"));
            workflow = UnifiedEighteenCompanionFixtures.prepareEmptyLiteratureWorkflow(base.mapper, state.resolve("empty-scope"), scripts, python);
            completePp2WorkerProject();
            install(false);
            var scoped = submit("UNRESOLVED_SCOPE_PP2", List.of(C + "PP2"));
            assertNotEquals(missing.path("runId"), scoped.path("runId"));
            assertNotEquals(missing.path("requestSha256"), scoped.path("requestSha256"));
            assertNotEquals(missing.at("/localBatch/pp2OtherPhenotypeSourceIdentity/observationSha256"),
                    scoped.at("/localBatch/pp2OtherPhenotypeSourceIdentity/observationSha256"));
            assertEquals(missing.path("enabledCriteria"), scoped.path("enabledCriteria"));
            install(true);
            submit("RELATED", List.of(C + "PM1", C + "PP2", C + "BP1"));
            submit("CURRENT18", enabled);
        }
        private void completePp2WorkerProject() throws Exception {
            var config = (ObjectNode) base.mapper.readTree(Files.readAllBytes(workflow));
            Path project = Path.of(config.path("projectRoot").asText());
            Path target = Files.createDirectories(project.resolve("scripts/ps1_pm5"));
            try (var sources = Files.list(scripts.getParent().resolve("ps1_pm5"))) {
                for (var source : sources.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".py")
                                && !path.getFileName().toString().startsWith("test_")).toList())
                    Files.copy(source, target.resolve(source.getFileName()));
            }
            Files.createDirectories(project.resolve("local/pp2-search-cache"));
            config.put("searchCache", "local/pp2-search-cache");
            Files.write(workflow, base.mapper.writeValueAsBytes(config));
        }

        private void runNonzero() throws Exception {
            workflow = UnifiedEighteenCompanionFixtures.prepareEmptyLiteratureWorkflow(base.mapper, state.resolve("n"), scripts, python);
            completePp2WorkerProject();
            var ledger = ExplicitContextSourceLedger.load(base.mapper, ledgerFile(), LEDGER_SHA);
            var reader = new CodingTranscriptReferenceReader(base.mapper, base.properties, code.resolve("local/resources"), ledger, "reference");
            var index = new GencodeTranscriptIndex(base.mapper, indexDirectory(), INDEX_SHA);
            var candidate = base.jobs.findInterpretations(base.analysisId, 0, 1).get(0);
            var context = base.mapper.createObjectNode(); context.putObject("gene").put("symbol", model.path("geneSymbol").asText()).put("hgncId", model.path("hgncId").asText());
            context.putObject("transcript").put("accession", "ENST00000403994").put("version", 9).put("annotationSourceVersion", annotation.getVersion());
            var variant = context.putObject("variant").put("variantType", "SNV");
            variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
            variant.putObject("normalization").put("referenceSequenceSha256", REF_SHA);
            var key = candidate.getObservation().getKey();
            variant.putObject("evidenceQueryAllele").put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                    .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
            var material = new CodingTranscriptMaterialSupply(base.mapper, null, "NOT_CONFIGURED", reader, index).resolve(context, candidate);
            assertTrue(material.ready(), material.snapshot()::toPrettyString);
            write(output.resolve("seed-material.json"), material.snapshot());
            var config = base.mapper.readTree(Files.readAllBytes(workflow)); Path project = Path.of(config.path("projectRoot").asText());
            Path builder = Path.of(required("t05.hcm.nonzero.seedBuilder")).toRealPath();
            Path log = output.resolve("seed-builder.log"), seed = Files.createDirectories(output.resolve("seed"));
            var process = new ProcessBuilder(python.toString(), builder.toString(), "--project", project.toString(), "--workflow", workflow.toString(),
                    "--material", output.resolve("seed-material.json").toString(), "--model", output.resolve("actual-model.json").toString(),
                    "--output", seed.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            waitFor(process, log);
            var seedReceipt = base.mapper.readTree(Files.readAllBytes(seed.resolve("seed-receipt.json")));
            assertFalse(seedReceipt.path("sourceReviewInjected").asBoolean(true)); assertFalse(seedReceipt.path("clinicalPositive").asBoolean(true));
            assertEquals(6, seedReceipt.path("syntheticTransportCalls").asInt());
            manifest.put("nonzeroLiterature", true).put("sourceMaterialClass", seedReceipt.path("materialSource").asText())
                    .put("stateDirectory", state.toString()).put("seedReceiptSha256", sha(Files.readAllBytes(seed.resolve("seed-receipt.json"))));
            install(false); var result = submit("NONZERO_LITERATURE_PP2", List.of(C + "PP2"));
            var saved = base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow();
            var data = base.mapper.readTree(saved.privateJson()); verifyNonzero(base.mapper, data.path("localBatch"), seedReceipt);
            write(output.resolve("actual-nonzero-review.json"), data.at("/localBatch/modules/pp2/results/0/otherPhenotypeReview"));
            assertEquals(seedReceipt.path("budgetSha256").asText(), sha(Files.readAllBytes(Path.of(seedReceipt.path("budgetPath").asText()))));
        }
        private MockEnvironment environment(boolean companions) throws Exception {
            var env = new MockEnvironment().withProperty(P + "enabled", "true").withProperty(P + "ba1-local-exomes-enabled", "true")
                    .withProperty(P + "binding-directory", path(code.resolve("local/resources/acmg-context/e4-a1a-2026-09-05")))
                    .withProperty(P + "resource-root", path(code.resolve("local/resources")))
                    .withProperty(P + "reference-receipt-file", path(ledgerFile())).withProperty(P + "reference-receipt-sha256", LEDGER_SHA)
                    .withProperty(P + "reference-receipt-locator", "reference").withProperty(P + "working-root", path(state.resolve("working")))
                    .withProperty(P + "gencode-transcript-index-directory", path(indexDirectory()))
                    .withProperty(P + "gencode-transcript-index-manifest-sha256", INDEX_SHA)
                    .withProperty(P + "pp2-published-knowledge-file", path(knowledge))
                    .withProperty(P + "pp2-published-knowledge-sha256", sha(Files.readAllBytes(knowledge)))
                    .withProperty(P + "local-batch-pp2-germline-declared", "true")
                    .withProperty(P + "local-batch-denovo-public-network-enabled", "false")
                    .withProperty("varvis.literature-workflow-config", path(workflow));
            for (String flag : List.of("bp7", "pvs1", "pm4", "bp3", "pm1", "pp2", "bp1")) env.withProperty(P + "local-batch-" + flag + "-enabled", "true");
            if (companions) {
                Path dn = state.resolve("denovo");
                if (!Files.exists(dn.resolve("manifest.json"))) new DeNovoBatchFixture(dn);
                env.withProperty(P + "local-batch-pm3-literature-enabled", "true").withProperty(P + "local-batch-pp1-literature-enabled", "true")
                        .withProperty(P + "local-batch-denovo-enabled", "true").withProperty(P + "local-batch-denovo-use", "SYNTHETIC_VALIDATION")
                        .withProperty(P + "local-batch-denovo-material-file", path(dn.resolve("manifest.json")))
                        .withProperty(P + "local-batch-denovo-material-sha256", sha(Files.readAllBytes(dn.resolve("manifest.json"))));
            }
            return env;
        }
        private void install(boolean companions) throws Exception {
            var config = new AcmgBusinessConfig();
            var currentEnv = environment(companions);
            var current = config.acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, currentEnv);
            var population = AcmgBusinessService.formalPopulation(current, () -> Bs1Runtime.localExomes(base.mapper, new VariantAnalysisProperties(), false));
            var localEnv = environment(companions).withProperty(P + "acceptance-scope", "LOCAL_COMPUTATIONAL_BATCH1")
                    .withProperty(P + "unified-local-workflow-enabled", "true").withProperty(P + "local-batch-workflow-enabled", "true");
            // This is the real public configuration entry; no central factory or scientific method is substituted.
            var local = config.acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, localEnv);
            base.service = AcmgBusinessService.unifiedLocal(population, local);
            base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        }
        private ObjectNode request(List<String> selection) throws Exception {
            var options = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                            .param("topN", "1").param("workflow", "CURRENT"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            enabled = new ArrayList<>(); options.path("enabledCriteria").forEach(x -> enabled.add(x.asText()));
            assertTrue(enabled.contains(C + "PP2"), options::toPrettyString);
            var rec = options.at("/candidates/0/recommendation"); JsonNode relation = null, tx = null;
            for (var row : rec.path("relationships")) if ("HGNC:12010".equals(row.path("hgncId").asText())
                    && "MONDO:0005045".equals(row.path("mondoId").asText()) && row.path("selectable").asBoolean()) relation = row;
            for (var row : rec.path("annotations")) if ("ENST00000403994.9".equals(row.path("versionedTranscriptId").asText())
                    && row.path("contextSelectable").asBoolean()) tx = row;
            assertNotNull(relation, rec::toPrettyString); assertNotNull(tx, rec::toPrettyString);
            var request = base.request.deepCopy().put("topN", 1).put("dataClass", "SYNTHETIC")
                    .put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
            request.set("selectedCriteria", base.mapper.valueToTree(selection));
            var item = request.putArray("hypotheses").addObject().put("affectedStatus", "UNKNOWN");
            var hypothesis = item.putObject("hypothesis");
            hypothesis.set("candidateId", rec.path("candidateId")); hypothesis.set("hypothesisId", rec.path("hypothesisId"));
            for (String key : List.of("hgncId", "mondoId", "moi")) hypothesis.set(key, relation.path(key));
            hypothesis.set("provenance", base.declaredSource("private/t05/synthetic-hcm-hypothesis"));
            var transcript = item.putObject("transcript").put("accession", "ENST00000403994").put("version", 9)
                    .put("selectionPolicy", tx.path("ruleContextReady").asBoolean() ? tx.path("ruleContextSelectionPolicy").asText()
                            : rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                    .put("selectionReason", "Explicit synthetic HCM background on complete real reference material");
            transcript.set("annotationSource", tx.path("annotationSource")); transcript.set("annotationSourceVersion", tx.path("annotationSourceVersion"));
            transcript.set("source", base.declaredSource("recommendations/" + rec.path("recommendationId").asText() + "/selection"));
            var selected = request.putArray("recommendations").addObject();
            for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) selected.set(key, rec.path(key));
            selected.set("stableRelationshipKey", relation.path("stableRelationshipKey")); selected.set("annotationId", tx.path("annotationId"));
            write(output.resolve("latest-input-options.json"), options); return request;
        }
        private ObjectNode submit(String name, List<String> selection) throws Exception {
            var request = request(selection); if ("CURRENT18".equals(name)) assertEquals(18, selection.size());
            var ranking = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
            var result = postPreservingFailure(name, request);
            assertEquals("COMPLETED_WITH_BLOCKERS", result.path("status").asText(), result::toPrettyString);
            assertEquals(ranking, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
            var run = base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow();
            var savedRequest = base.mapper.readTree(run.requestJson()); var privateData = base.mapper.readTree(run.privateJson());
            var batch = privateData.path("localBatch"); var raw = batch.at("/modules/pp2/results/0");
            assertEquals(1, batch.at("/modules/pp2/results").size(), batch::toPrettyString);
            assertEquals("READY", raw.at("/sequenceMaterial/status").asText(), raw::toPrettyString);
            assertEquals(new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(model), raw.at("/sequenceMaterial/modelCanonicalSha256").asText());
            assertEquals("NOT_EVALUATED", raw.path("status").asText(), raw::toPrettyString);
            assertEquals("NONE", raw.at("/evaluation/appliedStrength").asText()); assertEquals("UNKNOWN", raw.at("/otherPhenotypeReview/outcome").asText());
            assertTrue(raw.path("hcmReviewBindingVerified").asBoolean());
            assertEquals(raw.path("hypothesisId"), raw.at("/otherPhenotypeReview/binding/hypothesisId"));
            assertEquals(savedRequest.path("pp2OtherPhenotypeSourceSnapshot"), batch.path("pp2OtherPhenotypeSourceIdentity"));
            assertEquals("MISSING_CONFIGURATION_PP2".equals(name) ? "UNAVAILABLE" : "READ",
                    batch.at("/pp2OtherPhenotypeSourceIdentity/readerStatus").asText(), batch::toPrettyString);
            if (!"MISSING_CONFIGURATION_PP2".equals(name)) {
                assertEquals("pp2-other-phenotype-workflow-reader-v1", batch.at("/pp2OtherPhenotypeArchive/sourceArchive/readerFacts/methodVersion").asText());
                assertEquals(1, batch.at("/pp2OtherPhenotypeArchive/sourceArchive/readerFacts/discoveryScopes").size());
            }
            assertTrue(batch.path("pp2OtherPhenotypeArchiveSha256").asText().matches("[a-f0-9]{64}"));
            var actual = new ArrayList<String>(); result.path("enabledCriteria").forEach(x -> actual.add(x.asText()));
            assertEquals(selection.size(), actual.size()); assertTrue(actual.containsAll(selection));
            for (var criterion : batch.at("/results/0/localBatch/criteria")) assertNotEquals("MET", criterion.path("status").asText(), "No source-positive oracle was supplied");
            Path folder = Files.createDirectories(output.resolve(name));
            Files.writeString(folder.resolve("expected-public.json"), run.responseJson(), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("expected-private.json"), run.privateJson(), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("expected-request.json"), run.requestJson(), StandardCharsets.UTF_8);
            write(folder.resolve("request.json"), request);
            byte[] full = base.http.perform(get("/variant-analyses/{a}/acmg/runs/{r}", base.analysisId, run.id()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Files.write(folder.resolve("expected-controller-full.json"), full);
            assertEquals(result, base.mapper.readTree(full)); assertEquals(result, base.postRequest(request));
            manifest.withArray("runs").addObject().put("name", name).put("runId", run.id()).put("selectedCount", selection.size())
                    .put("requestSha256", run.requestSha256()).put("rawStatus", raw.path("status").asText())
                    .put("sourceObservationSha256", batch.at("/pp2OtherPhenotypeSourceIdentity/observationSha256").asText());
            return result;
        }
        private ObjectNode postPreservingFailure(String name, ObjectNode request) throws Exception {
            var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId)
                    .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
            if (response.getStatus() != 200) {
                Path failure = Files.createDirectories(output.resolve(name));
                Files.write(failure.resolve("failed-http-response.json"), response.getContentAsByteArray());
                for (var row : base.store.runs(base.analysisId)) if ("FAILED".equals(row.status())) {
                    if (row.responseJson() != null) Files.writeString(failure.resolve("failed-saved-public.json"), row.responseJson(), StandardCharsets.UTF_8);
                    if (row.privateJson() != null) Files.writeString(failure.resolve("failed-saved-private.json"), row.privateJson(), StandardCharsets.UTF_8);
                    Files.writeString(failure.resolve("failed-saved-request.json"), row.requestJson(), StandardCharsets.UTF_8);
                }
            }
            assertEquals(200, response.getStatus(), response.getContentAsString());
            return (ObjectNode) base.mapper.readTree(response.getContentAsByteArray());
        }
        private Path ledgerFile() { return code.resolve("local/private/receipts/reference38-runtime.json"); }
        private Path indexDirectory() { return code.resolve("local/resources/gencode/derived/v38-transcripts-a1-inventory-20261102"); }
        private void write(Path file, JsonNode value) throws Exception { Files.write(file, base.mapper.writeValueAsBytes(value)); }
    }

    public static final class Reader {
        private Reader() { }
        public static void main(String[] args) throws Exception {
            assertEquals(2, args.length); Path state = Path.of(args[0]), output = Path.of(args[1]);
            var mapper = Jackson2ObjectMapperBuilder.json().build().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var manifest = mapper.readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
            long writer = manifest.path("writerPid").asLong();
            assertTrue(ProcessHandle.of(writer).isEmpty() || !ProcessHandle.of(writer).orElseThrow().isAlive());
            assertTrue(manifest.path("writerCompleted").asBoolean()); assertTrue(manifest.path("databaseClosed").asBoolean());
            var ds = new DriverManagerDataSource("jdbc:h2:file:" + state.resolve("business-db").toString().replace('\\', '/')
                    + ";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            var properties = new VariantAnalysisProperties(); properties.setStorageRoot(state.resolve("jobs")); properties.getPersistence().setRetryBackoff(Duration.ZERO);
            var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(ds), new DataSourceTransactionManager(ds), properties, mapper);
            var store = new AcmgBusinessStore(ds); var forbidden = new AtomicInteger();
            var service = new AcmgBusinessService(mapper, jobs, store, new AcmgExecutionSources(mapper, store),
                    () -> { forbidden.incrementAndGet(); throw new AssertionError("HISTORY_CANNOT_CREATE_SCIENTIFIC_RUNTIME"); });
            var http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
            String analysis = manifest.path("analysisId").asText(); var originalSource = store.source(analysis).orElseThrow();
            assertArrayEquals(Files.readAllBytes(output.resolve("expected-source.json")), originalSource.json().getBytes(StandardCharsets.UTF_8));
            var receipt = mapper.createObjectNode().put("schemaVersion", "t05-hcm-normal-reader-v1").put("writerPid", writer)
                    .put("readerPid", ProcessHandle.current().pid()).put("writerExitedBeforeReader", true).put("readOnlyH2", true)
                    .put("newClinicalPositive", false).put("userActualDownload", "NOT_RUN_USER_OWNED");
            var runs = receipt.putArray("runs");
            for (var row : manifest.path("runs")) {
                String id = row.path("runId").asText(), name = row.path("name").asText(); Path folder = output.resolve(name);
                var before = store.run(analysis, id).orElseThrow();
                assertArrayEquals(Files.readAllBytes(folder.resolve("expected-public.json")), before.responseJson().getBytes(StandardCharsets.UTF_8));
                assertArrayEquals(Files.readAllBytes(folder.resolve("expected-private.json")), before.privateJson().getBytes(StandardCharsets.UTF_8));
                assertArrayEquals(Files.readAllBytes(folder.resolve("expected-request.json")), before.requestJson().getBytes(StandardCharsets.UTF_8));
                String route = "/variant-analyses/" + analysis + "/acmg/runs/" + id;
                byte[] full = http.perform(get(route)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                assertArrayEquals(Files.readAllBytes(folder.resolve("expected-controller-full.json")), full);
                var result = mapper.readTree(full); assertEquals(result, service.read(analysis, id));
                assertTrue(result.at("/pp2OtherPhenotypeEvidence/archiveSha256").asText().matches("[a-f0-9]{64}"));
                if (manifest.path("nonzeroLiterature").asBoolean()) {
                    var seed = mapper.readTree(Files.readAllBytes(output.resolve("seed/seed-receipt.json")));
                    assertEquals(manifest.path("seedReceiptSha256").asText(), sha(Files.readAllBytes(output.resolve("seed/seed-receipt.json"))));
                    verifyNonzero(mapper, mapper.readTree(before.privateJson()).path("localBatch"), seed);
                    assertEquals(seed.path("budgetSha256").asText(), sha(Files.readAllBytes(Path.of(seed.path("budgetPath").asText()))));
                    assertEquals(result.at("/pp2OtherPhenotypeDerivedEvidence/archiveSha256"),
                            mapper.readTree(before.privateJson()).at("/localBatch/pp2OtherPhenotypeDerivedArchiveSha256"));
                    receipt.put("nonzeroRawReadingVerified", true).put("nonzeroBoundedQualificationVerified", true)
                            .put("newModelCalls", 0).put("newNetworkRequests", 0);
                }
                var index = mapper.readTree(http.perform(get(route + "/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                String candidate = result.at("/candidates/0/candidateId").asText();
                byte[] background = http.perform(get(route + "/view/background").param("candidateId", candidate).param("backgroundIndex", "0")
                                .param("sourceSha256", index.path("sourceViewSha256").asText()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                assertEquals(result.at("/candidates/0/attempts/0"), mapper.readTree(background).at("/candidates/0/attempts/0"));
                assertEquals(before, store.run(analysis, id).orElseThrow());
                Files.write(folder.resolve("reader-controller-full-export.json"), full); Files.write(folder.resolve("reader-background.json"), background);
                Files.write(folder.resolve("reader-index.json"), mapper.writeValueAsBytes(index));
                runs.addObject().put("name", name).put("runId", id).put("exactStoredPublicPrivateRequest", true).put("exactFullExport", true)
                        .put("exportSha256", sha(full)).put("sourceArchiveSha256", result.at("/pp2OtherPhenotypeEvidence/archiveSha256").asText());
            }
            assertEquals(0, forbidden.get()); assertEquals(originalSource, store.source(analysis).orElseThrow());
            if (manifest.path("nonzeroLiterature").asBoolean()) assertEquals(1, service.list(analysis).size());
            else assertEquals(4, service.list(analysis).size());
            receipt.put("forbiddenScientificFactories", forbidden.get()).put("passed", true);
            Files.write(output.resolve("reader-receipt.json"), mapper.writeValueAsBytes(receipt));
        }
    }

    private static void verifyNonzero(ObjectMapper mapper, JsonNode batch, JsonNode seed) throws Exception {
        var raw = batch.at("/modules/pp2/results/0"); var review = raw.path("otherPhenotypeReview");
        assertEquals("NOT_EVALUATED", raw.path("status").asText()); assertEquals("NONE", raw.at("/evaluation/appliedStrength").asText());
        assertEquals("UNKNOWN", review.path("outcome").asText()); assertEquals(0, review.path("qualifiedEvidence").size());
        var nativeScopes = new ArrayList<JsonNode>();
        for (var scope : review.path("requiredScope"))
            if ("EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS".equals(scope.path("scope").asText())) nativeScopes.add(scope);
        assertEquals(1, nativeScopes.size(), "Raw article extraction cannot remove or duplicate the native scope requirement");
        assertEquals("INCOMPLETE", nativeScopes.get(0).path("status").asText());
        var summary = review.path("literatureAssessment");
        assertEquals("1.2.0", summary.path("readingMethodVersion").asText());
        assertEquals("NOT_COMPLETED", summary.path("scopeStatus").asText()); assertEquals(0, summary.path("assessedPapers").asInt(-1));
        assertEquals(BOUNDED_QUALIFICATION, summary.path("qualificationVersion").asText(), summary::toPrettyString);
        assertEquals("INCOMPLETE", summary.path("qualificationScopeStatus").asText());
        assertEquals(1, summary.path("papers").size());
        var paper = summary.at("/papers/0");
        assertEquals(seed.path("pmcid"), paper.path("pmcid")); assertEquals(seed.path("rawArticleSha256"), paper.path("sourceRawSha256"));
        assertEquals("RAW_WORK_SCOPE_READ", paper.path("status").asText());
        assertEquals(1, paper.path("cases").size()); assertEquals(4, paper.path("factsInSource").asInt());
        assertEquals(3, paper.path("relationshipRecords").size());
        var kinds = new java.util.HashSet<String>();
        for (var row : paper.path("relationshipRecords")) {
            String kind = row.path("materialKindProposal").asText();
            kinds.add(kind);
            boolean individual = "INDIVIDUAL_CASE".equals(kind);
            assertEquals(individual ? "EXACT" : "UNRESOLVED", row.path("targetMatch").asText(), row::toPrettyString);
            assertEquals("UNRESOLVED", row.path("disposition").asText());
            assertEquals(individual ? "PP2_LITERATURE_AFFIRMATIVE_SOURCE_HCM_CLINICAL_BACKGROUND_NOT_ESTABLISHED"
                    : "PP2_LITERATURE_AGGREGATE_FUNCTIONAL_OR_AUTHOR_MATERIAL_NEEDS_INDEPENDENT_CONTEXT",
                    row.at("/qualificationGaps/0/reasonCode").asText());
            assertEquals(1, row.path("facts").size()); assertEquals(seed.path("rawArticleSha256"), row.at("/anchor/sourceRawSha256"));
        }
        assertEquals(java.util.Set.of("INDIVIDUAL_CASE", "COHORT", "FUNCTIONAL_STUDY"), kinds);
        var coverage = paper.path("questionCoverage");
        assertEquals("pp2-relationship-material-v1", coverage.path("questionVersion").asText());
        assertEquals("RAW_QUESTION_SCOPE_READ", coverage.path("status").asText());
        assertEquals(3, coverage.path("expectedPlans").asInt()); assertEquals(3, coverage.path("checkedPlans").asInt());
        assertEquals("NOT_GRANTED", coverage.path("relationQualification").asText());
        var derived = batch.path("pp2OtherPhenotypeDerivedArchive"); var originals = derived.path("originals");
        long bytes = 0;
        for (var name = originals.fieldNames(); name.hasNext();) {
            String pin = name.next(); var value = originals.path(pin);
            byte[] body = java.util.Base64.getDecoder().decode(value.path("base64").asText());
            assertEquals(pin, sha(body)); assertEquals(body.length, value.path("bytes").asInt());
            assertTrue(body.length <= 4 * 1024 * 1024); bytes += body.length;
        }
        assertTrue(bytes <= 32 * 1024 * 1024);
        assertTrue(originals.has(seed.path("rawArticleSha256").asText()));
        for (var artifact : seed.path("cacheArtifacts")) assertTrue(originals.has(artifact.path("sha256").asText()), artifact::toPrettyString);
        var ledgerRaw = java.util.Base64.getDecoder().decode(originals.path(summary.path("readingLedgerRawSha256").asText()).path("base64").asText());
        var ledger = mapper.readTree(ledgerRaw);
        assertEquals("1.2.0", ledger.path("methodVersion").asText()); assertEquals("RAW_SCOPE_READ", ledger.path("status").asText());
        assertTrue(ledger.path("readingComplete").asBoolean());
        assertEquals(0, ledger.path("modelCalls").asInt(-1)); assertEquals(0, ledger.path("modelAttempts").asInt(-1));
        assertEquals(0, ledger.path("networkRequests").asInt(-1)); assertEquals(6, ledger.path("cacheHits").asInt());
        assertEquals(3, ledger.at("/papers/0/checkedChunks").size()); assertEquals(3, ledger.at("/papers/0/relationshipChunks").size());
        for (var chunk : ledger.at("/papers/0/relationshipChunks")) {
            assertEquals("PP2", chunk.path("originalExtractionCriterion").asText());
            assertEquals("pp2-relationship-material-v1", chunk.path("originalExtractionProfile").asText());
        }
        var qualificationBytes = java.util.Base64.getDecoder().decode(originals.path(summary.path("qualificationLedgerRawSha256").asText())
                .path("base64").asText());
        var qualification = mapper.readTree(qualificationBytes);
        assertEquals("UNKNOWN", qualification.path("outcome").asText());
        var bounded = qualification.path("boundedLiteratureReview");
        assertEquals(BOUNDED_QUALIFICATION, bounded.path("qualificationVersion").asText());
        assertEquals("INCOMPLETE", bounded.path("scopeStatus").asText());
        assertEquals(summary.path("readingLedgerRawSha256"), bounded.path("readingLedgerRawSha256"));
        assertEquals(derived.path("methodSourcesSha256"), bounded.path("methodSourcesSha256"));
        assertEquals(review.at("/binding/contextSha256"), bounded.at("/materialIdentity/contextSha256"));
        assertEquals(review.at("/binding/hcmPolicyBindingSha256"), bounded.at("/materialIdentity/hcmPolicyBindingSha256"));
        assertEquals(raw.at("/sequenceMaterial/modelCanonicalSha256"), bounded.at("/materialIdentity/nativeModelSha256"));
        assertEquals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(raw.at("/sequenceMaterial/alleleAssociation")),
                bounded.at("/materialIdentity/alleleAssociationSha256").asText());
        assertEquals(seed.at("/alleleAssociation/allele"), bounded.path("targetAllele"));
        assertFalse(bounded.path("positiveOtherPhenotypeGranted").asBoolean(true));
        assertFalse(bounded.path("scientificClassificationCreated").asBoolean(true));
        var observations = bounded.at("/papers/0/observations");
        assertEquals(3, observations.size());
        var exact = new ArrayList<JsonNode>();
        for (var observation : observations) {
            assertEquals("UNRESOLVED", observation.path("disposition").asText());
            assertTrue(observation.path("clinicalEvidenceRefs").isArray());
            assertEquals(0, observation.path("clinicalEvidenceRefs").size());
            if ("EXACT".equals(observation.path("targetMatch").asText())) exact.add(observation);
        }
        assertEquals(1, exact.size());
        var observed = exact.get(0);
        assertEquals("1", observed.at("/caseKey/caseLabel").asText());
        assertEquals(seed.at("/alleleAssociation/allele"), observed.path("normalizedSourceAllele"));
        assertEquals(seed.path("rawArticleSha256"), observed.at("/identityEvidenceRefs/0/sourceRawSha256"));
        assertTrue(observed.at("/identityEvidenceRefs/0/quote").asText().contains(seed.at("/alleleAssociation/hgvsc").asText()));
    }
    private static String required(String key) { return java.util.Objects.requireNonNull(System.getProperty(key), key); }
    private static void reference(VariantAnalysisProperties properties, Path code) {
        var normalization = properties.getNormalization();
        normalization.setGrch38Reference(code.resolve("local/resources/reference/grch38.p14.primary-chr.fa").toString());
        normalization.setGrch38ReferenceSha256(REF_SHA); normalization.setGrch38ProfileId("grch38-primary-chr");
        normalization.setGrch38ContigAliasFile(code.resolve("varvis-server/config/grch38-canonical-to-chr-contigs.tsv").toString());
    }
}
