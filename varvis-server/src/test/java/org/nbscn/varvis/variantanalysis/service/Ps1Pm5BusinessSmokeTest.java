package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.controller.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.impl.VariantAnalysisServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.PicardVcfBuildConverter;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Public controls, real engine/provider calls, file H2. MockMvc is not network HTTP or browser acceptance. */
class Ps1Pm5BusinessSmokeTest {
    private static final int EVALUATION_TOP_N = 3;
    private static final Map<String, String> CONTROLS = Map.of(
            "PS1_POSITIVE", "GRCh38:7:140801537:T:A",
            "PM5_POSITIVE", "GRCh38:7:140801533:A:C",
            "NEGATIVE", "GRCh38:7:140801537:T:G");
    private static final String VCF = "##fileformat=VCFv4.2\n##reference=GRCh38\n"
            + "##contig=<ID=chr7,length=159345973>\n"
            + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
            + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n"
            + "chr7\t140801533\tpublic-pm5-control\tA\tC\t.\tPASS\t.\tGT\t0/1\n"
            + "chr7\t140801537\tpublic-ps1-control\tT\tA\t.\tPASS\t.\tGT\t0/1\n"
            + "chr7\t140801537\tpublic-negative-control\tT\tG\t.\tPASS\t.\tGT\t0/1\n";
    private final AcmgBusinessIntegrationTest base = new AcmgBusinessIntegrationTest();
    private final Map<String, Integer> counters = new TreeMap<>();
    private Path work;
    private MockEnvironment environment;

    @Test
    @EnabledIfSystemProperty(named = "varvis.ps1pm5.real", matches = "true")
    void realPublicControlsThroughUploadEvaluateSaveRebuildAndReuse() throws Exception {
        work = Files.createDirectories(Path.of("target/ps1-pm5-acceptance/attempt-" + UUID.randomUUID()).toAbsolutePath());
        for (String name : List.of("exomiser", "vepPreflight", "vepPublication", "vepComparator", "clinvarDiscovery",
                "missenseRuntime", "spliceRawBatch", "spliceQuery", "provider", "evaluator", "ba1Provider", "ba1Evaluator",
                "predictionProvider", "predictionEvaluator", "missenseProvider", "missenseEvaluator", "coordinatorFactory", "amIdentityLookup"))
            counters.put(name, 0);
        ObjectNode report = base.mapper.createObjectNode().put("status", "BLOCKED").put("dataClass", "SYNTHETIC")
                .put("transport", "Spring MockMvc controller dispatch").put("pageCovered", false).put("networkHttpCovered", false)
                .put("evaluationTopN", EVALUATION_TOP_N).put("evaluationScope", "BRAF-RASopathy AD; original formal candidate order retained");
        long start = System.nanoTime();
        try {
            setup();
            wireBusiness(); // Validate configured profile identities before any engine or model execution.
            assertZero(snapshot());
            report.put("configuredBusinessConstructedBeforeEngines", true);
            String resumeAttempt = System.getProperty("varvis.ps1pm5.resumeAttempt", "");
            if (resumeAttempt.isBlank()) {
                preflight(report);
                publish(report);
            } else {
                resumePublishedAnalysis(Path.of(resumeAttempt), report);
            }
            base.closeDatabase(); base.recreateRepositories(); wireBusiness();
            ObjectNode options = observedHttp(base.http, get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                    .param("topN", Integer.toString(EVALUATION_TOP_N)), "input-options");
            ObjectNode request = declaration(options); save("explicit-request.json", request);
            Map<String, Integer> before = snapshot();
            ObjectNode saved = observedHttp(base.http, post("/variant-analyses/{id}/acmg/runs", base.analysisId)
                    .contentType("application/json").content(Objects.requireNonNull(base.mapper.writeValueAsBytes(request))), "first-post");
            report.set("firstRequestCallIncrement", base.mapper.valueToTree(delta(before)));
            report.put("runId", saved.path("runId").asText()); save("saved-business.json", saved);
            int bundleIndex = 0;
            for (JsonNode candidate : saved.path("candidates")) for (JsonNode attempt : candidate.path("attempts")) {
                JsonNode bundle = attempt.path("missenseComparison").path("bundle");
                if (bundle.isObject()) save("missense-bundle-" + (++bundleIndex) + ".json", bundle);
            }
            report.set("scientificOutcomes", scientificOutcomes(saved));
            before = snapshot();
            ObjectNode read = observedHttp(base.http, get("/variant-analyses/{id}/acmg/runs/{run}",
                    base.analysisId, saved.path("runId").asText()), "saved-get");
            assertEquals(saved, read); assertZero(delta(before));
            report.put("savedGetIdentical", true).set("savedGetCallIncrement", base.mapper.valueToTree(delta(before)));
            base.closeDatabase(); base.recreateRepositories(); wireBusiness();
            assertEquals(saved.path("executionProfile"), ((AcmgExecutionProfile) requiredField(base.service, "executionProfile")).document(),
                    "Rebuilt execution profile changed; stop before repeat POST can trigger new evidence calls");
            before = snapshot();
            ObjectNode rebuilt = observedHttp(base.http, get("/variant-analyses/{id}/acmg/runs/{run}",
                    base.analysisId, saved.path("runId").asText()), "rebuilt-get");
            assertEquals(saved, rebuilt); assertZero(delta(before));
            report.put("rebuiltGetIdentical", true).set("rebuiltGetCallIncrement", base.mapper.valueToTree(delta(before)));
            before = snapshot();
            ObjectNode repeated = observedHttp(base.http, post("/variant-analyses/{id}/acmg/runs", base.analysisId)
                    .contentType("application/json").content(Objects.requireNonNull(base.mapper.writeValueAsBytes(request))), "repeat-post");
            assertEquals(saved.path("runId"), repeated.path("runId")); assertEquals(saved, repeated); assertZero(delta(before));
            report.put("repeatIdentical", true).set("repeatRequestCallIncrement", base.mapper.valueToTree(delta(before)));
            report.put("engineeringSaveReusePassed", true);
            JsonNode outcomes = report.path("scientificOutcomes");
            assertAll("Separate scientific controls; persistence proof is retained even if a control is blocked",
                    () -> assertEquals("MET", outcomes.at("/PS1_POSITIVE/PS1/status").asText(), outcomes.toPrettyString()),
                    () -> assertEquals("MET", outcomes.at("/PM5_POSITIVE/PM5/status").asText(), outcomes.toPrettyString()),
                    () -> assertEquals("NOT_MET", outcomes.at("/PM5_POSITIVE/PS1/status").asText(), "Different amino-acid substitution does not meet PS1"),
                    () -> assertEquals("NOT_MET", outcomes.at("/PS1_POSITIVE/PM5/status").asText(), "PS1 takes precedence over PM5"),
                    () -> assertEquals("NOT_EVALUATED", outcomes.at("/NEGATIVE/PS1/status").asText(), "Self-reference cannot establish independent pathogenicity"),
                    () -> assertEquals("NOT_EVALUATED", outcomes.at("/NEGATIVE/PM5/status").asText(), "Missing independent evidence remains unevaluated"));
            report.put("status", "REAL_PS1_PM5_POSITIVE_NEGATIVE_SAVE_REUSE_VERIFIED");
        } catch (Exception | AssertionError failure) {
            report.put("failure", failure.toString()); throw failure;
        } finally {
            report.set("actualCalls", base.mapper.valueToTree(snapshot()));
            report.put("wallSeconds", (System.nanoTime() - start) / 1_000_000_000.0);
            save("acceptance-report.json", report);
            System.out.println("PS1_PM5_ACCEPTANCE=" + work);
            base.closeDatabase();
        }
    }

    private void resumePublishedAnalysis(Path requested, ObjectNode report) throws Exception {
        Path root = Path.of("target/ps1-pm5-acceptance").toRealPath();
        Path previous = requested.toAbsolutePath().normalize().toRealPath();
        assertEquals(root, previous.getParent(), "Resume only a direct attempt owned by this worktree");
        assertTrue(previous.getFileName().toString().matches("attempt-[a-f0-9-]{36}"), "Expected an isolated attempt directory");
        assertNotEquals(work.toRealPath(), previous, "Resume output must be a new attempt");
        Path originalReport = previous.resolve("acceptance-report.json");
        JsonNode prior = base.mapper.readTree(Files.readAllBytes(originalReport));
        JsonNode preflight = base.mapper.readTree(Files.readAllBytes(previous.resolve("preflight-report.json")));
        assertEquals("READY", preflight.path("status").asText());
        assertEquals(prior.path("preflight"), preflight, "Original report and preflight disagree");
        assertEquals(VCF, Files.readString(previous.resolve("public-controls.vcf"), StandardCharsets.UTF_8));
        assertEquals(sha(VCF.getBytes(StandardCharsets.UTF_8)), preflight.path("vcfSha256").asText());
        JsonNode originalPolicy = base.mapper.readTree(Files.readAllBytes(previous.resolve("scientific-policy.json")));
        ObjectNode currentPolicy = ClinVarMissenseBusinessRuntime.loadProfile(base.mapper);
        assertEquals(originalPolicy, currentPolicy, "Scientific policy changed: this saved preflight cannot be reused");
        JsonNode originalConfig = base.mapper.readTree(Files.readAllBytes(previous.resolve("configuration-identity.json")));
        JsonNode currentConfig = base.mapper.readTree(Files.readAllBytes(work.resolve("configuration-identity.json")));
        assertEquals(originalConfig.path("sha256"), currentConfig.path("sha256"), "Runtime configuration changed since preflight");
        String originalStorage = originalConfig.path("storageRoot").asText();
        assertFalse(originalStorage.isBlank(), "Original storage root is missing");
        Path originalStorageRoot = Path.of(originalStorage).toRealPath();
        assertEquals(previous.resolve("jobs").toRealPath(), originalStorageRoot, "Resume storage must belong to the original branch attempt");
        var currentAnalysisPolicy = AnalysisScopePolicy.resolve(base.properties.getExomiser(), AnalysisScopePolicy.Scope.STANDARD, false);
        assertEquals(currentAnalysisPolicy.policySha256(), preflight.path("analysisPolicySha256").asText());
        assertEquals(currentAnalysisPolicy.effectiveYaml(), Files.readString(previous.resolve("effective-analysis.yml"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(previous.resolve("business-db.mv.db")), "Existing branch database is required");
        base.closeDatabase(); // Close only the empty database created in the new attempt by setup().
        // JDBC reconstructs job paths from this root; keep the original engine/source paths intact.
        base.properties.setStorageRoot(originalStorageRoot);
        base.dataSource = new DriverManagerDataSource("jdbc:h2:file:" + previous.resolve("business-db").toString().replace('\\', '/')
                + ";IFEXISTS=TRUE;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        base.recreateRepositories();
        base.analysisId = prior.path("analysisId").asText();
        assertFalse(base.analysisId.isBlank(), "Original analysis ID is missing");
        var job = base.jobs.findById(base.analysisId).orElseThrow();
        assertEquals(VariantAnalysisStatus.READY, job.getStatus(), job.getErrorMessage());
        assertTrue(job.getWorkspace().toAbsolutePath().normalize().startsWith(previous.resolve("jobs")), "Unexpected original job workspace");
        assertEquals("STANDARD", AnalysisScopePolicy.publicDetails(job).get("scope"));
        var candidates = base.jobs.findInterpretations(base.analysisId, 0, 20);
        var identities = new LinkedHashMap<String, String>();
        for (var candidate : candidates) identities.put(candidate.getCandidateId(),
                candidate.getObservation().getKey().canonicalId().replace("chr7", "7").replace('|', ':'));
        JsonNode originalIdentities = base.mapper.readTree(Files.readAllBytes(previous.resolve("formal-identities.json")));
        assertEquals(prior.path("formalIdentities"), originalIdentities);
        assertEquals(originalIdentities, base.mapper.valueToTree(identities), "Persisted formal candidates changed");
        JsonNode originalCandidates = base.mapper.readTree(Files.readAllBytes(previous.resolve("formal-candidates.json")));
        var originalOrder = new ArrayList<String>();
        originalCandidates.forEach(candidate -> originalOrder.add(candidate.path("candidateId").asText()));
        assertEquals(originalOrder, candidates.stream().map(candidate -> Objects.requireNonNull(candidate).getCandidateId()).toList(),
                "Formal candidate order changed");
        var recovered = base.sources.recover(job, candidates);
        assertTrue(recovered.candidateBlockers().isEmpty(), recovered.candidateBlockers().toString());
        save("formal-candidates.json", base.mapper.valueToTree(candidates));
        save("formal-identities.json", base.mapper.valueToTree(identities));
        save("scientific-policy.json", currentPolicy);
        save("reused-preflight-report.json", preflight);
        report.put("analysisId", base.analysisId).put("formalCandidateCount", candidates.size())
                .put("resumedAttempt", previous.getFileName().toString()).put("priorReportSha256", sha(Files.readAllBytes(originalReport)))
                .put("priorPreflightSha256", sha(Files.readAllBytes(previous.resolve("preflight-report.json"))))
                .put("recoveredExecutionSourceSha256", recovered.snapshotSha256()).put("preflightReused", true)
                .put("publicationReused", true).put("resumedStorageRoot", originalStorageRoot.toString())
                .put("reportWork", work.toString()).put("vepTemp", Objects.requireNonNull(base.properties.getVep().getTempDir()).toString())
                .put("runtimeOutputPolicy", "Reports and VEP temporary output use the new attempt; runtime UUID outputs may append under the original branch-owned storage root without replacing original engine artifacts");
        report.set("priorActualCalls", prior.path("actualCalls"));
        report.set("resumeReadCallIncrement", base.mapper.valueToTree(snapshot()));
        report.set("formalIdentities", base.mapper.valueToTree(identities));
        report.set("preflight", preflight);
        assertZero(snapshot());
    }

    private void setup() throws Exception {
        Path config = Path.of(Objects.requireNonNull(System.getProperty("varvis.ps1pm5.config"), "varvis.ps1pm5.config required")).toAbsolutePath().normalize();
        assertTrue(config.startsWith(Path.of("target/ps1-pm5-private").toAbsolutePath().normalize()), "Use branch-private configuration");
        base.temporary = work; base.setup();
        Properties configured = new Properties();
        try (var input = Files.newInputStream(config)) { configured.load(input); }
        Properties allowed = new Properties();
        configured.forEach((key, value) -> { if (key.toString().startsWith("variant-analysis.")) allowed.put(key, value); });
        environment = new MockEnvironment().withProperty("VARVIS_RESOURCE_ROOT",
                Objects.requireNonNull(System.getProperty("varvis.ps1pm5.resourceRoot"), "varvis.ps1pm5.resourceRoot required"));
        environment.getPropertySources().addLast(new PropertiesPropertySource("ps1-pm5-private-variant-settings", allowed));
        Binder.get(environment).bind("variant-analysis", Bindable.ofInstance(base.properties));
        base.properties.setStorageRoot(work.resolve("jobs"));
        base.properties.getVep().setTempDir(work.resolve("vep-temp").toString());
        base.properties.getPersistence().setTablePrefix(""); base.properties.getPersistence().setRetryBackoff(Duration.ZERO);
        base.recreateRepositories();
        assertTrue(base.properties.getVep().isEnabled()); assertTrue(base.properties.getVep().isExecutionEnabled());
        assertTrue(base.properties.getStorageRoot().toAbsolutePath().normalize().startsWith(work));
        save("configuration-identity.json", base.mapper.createObjectNode().put("sha256", sha(Files.readAllBytes(config)))
                .put("storageRoot", base.properties.getStorageRoot().toString())
                .put("vepTemp", Objects.requireNonNull(base.properties.getVep().getTempDir()).toString()));
    }

    private void preflight(ObjectNode report) throws Exception {
        ObjectNode proof = base.mapper.createObjectNode().put("status", "BLOCKED");
        save("public-controls.vcf", VCF); proof.put("vcfSha256", sha(VCF.getBytes(StandardCharsets.UTF_8)));
        proof.set("controls", base.mapper.valueToTree(CONTROLS));
        save("scientific-policy.json", ClinVarMissenseBusinessRuntime.loadProfile(base.mapper));
        try {
            Path sidecarPath = Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.clinvar-sidecar-file"));
            var sidecarIdentity = ClinVarMissenseResourceReadiness.verify(base.mapper, base.properties, sidecarPath,
                    Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.clinvar-sidecar-manifest")));
            proof.set("comparatorResourceIdentity", sidecarIdentity);
            var sidecar = new ClinVarComparatorSidecar(base.mapper, sidecarPath, sidecarIdentity.path("resourceId").asText(),
                    sidecarIdentity.path("resourceVersion").asText(), sidecarIdentity.path("identityFingerprintSha256").asText());
            JsonNode audit;
            try (var in = getClass().getResourceAsStream(ClinVarMissenseBusinessRuntime.AUDIT_RESOURCE)) {
                audit = base.mapper.readTree(Objects.requireNonNull(in));
            }
            var references = proof.putArray("referencePrecheck");
            for (var ref : audit.path("references")) {
                var key = new NormalizedVariantKey(ref.path("assembly").asText(), ref.path("chromosome").asText(),
                        ref.path("position").asInt(), ref.path("referenceAllele").asText(), ref.path("alternateAllele").asText());
                add("clinvarSidecarPreflight", 1);
                var observed = sidecar.lookup(key, ref.path("clinvarVariationId").asText());
                references.add(base.mapper.valueToTree(observed));
                assertEquals(ClinVarComparatorSidecar.Status.HIT, observed.getStatus(), ref.toString());
                var record = Objects.requireNonNull(observed.getRecord());
                assertTrue(java.util.stream.StreamSupport.stream(record.getSubmissions().spliterator(), false)
                        .anyMatch(s -> ref.path("scvAccession").asText().equals(s.path("scv").asText())), "Audited SCV missing from actual sidecar");
            }
            Path frequencyPath = Path.of("target/ps1-pm5-private/frequency-query.json");
            JsonNode frequency = base.mapper.readTree(Files.readAllBytes(frequencyPath));
            save("frequency-query.json", frequency); proof.put("frequencySha256", sha(Files.readAllBytes(frequencyPath)));
            assertEquals("GRCh38", frequency.path("assembly").asText());
            assertEquals(base.properties.getExomiser().getVersion(), frequency.path("exomiserVersion").asText());
            assertEquals(base.properties.getExomiser().getDataVersion(), frequency.path("dataVersion").asText());
            assertTrue(frequency.path("readOnly").asBoolean());
            for (String allele : CONTROLS.values()) {
                List<JsonNode> matches = new ArrayList<>();
                frequency.path("alleles").forEach(row -> { if (allele.equals(row.path("allele").asText())) matches.add(row); });
                assertEquals(1, matches.size(), "Missing or ambiguous frequency proof: " + allele);
                assertTrue(List.of("PASS", "NO_SELECTED_RECORD_NOT_ZERO").contains(matches.get(0).path("frequencyFilter").asText()),
                        "Frequency precheck failed or unknown format: " + matches.get(0));
            }
            proof.put("frequencyNoRecordMeansZero", false).put("allOriginalFiltersRequireFormalOutput", true);
            var policy = AnalysisScopePolicy.resolve(base.properties.getExomiser(), AnalysisScopePolicy.Scope.STANDARD, false);
            save("effective-analysis.yml", policy.effectiveYaml()); proof.put("analysisPolicySha256", policy.policySha256());
            var ledger = ExplicitContextSourceLedger.load(base.mapper,
                    Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),
                    environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference = new NativeSnvReference(base.mapper, base.properties, new NormalizationResourceValidator(base.properties),
                    Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")), List.of(ledger), ledger.reference("reference"));
            var observations = proof.putArray("referenceObservations");
            for (String allele : CONTROLS.values()) {
                String[] fields = allele.split(":");
                var observed = reference.observe("chr" + fields[1], Integer.parseInt(fields[2]), fields[3]);
                observations.add(observed.observation()); assertEquals("MATCH", observed.observation().path("status").asText(), allele);
            }
            Path output = work.resolve("preflight-vep.jsonl");
            var result = countedVep("vepPreflight", new SystemExternalProcessRunner()).run(
                    new VepCommandBuilder(base.properties).build(new VepCommandOptions(work.resolve("public-controls.vcf"), output)),
                    work, Duration.ofSeconds(Math.max(1, base.properties.getVep().getTimeoutSeconds())));
            assertTrue(result.isSuccess(), result.getOutput());
            var parsed = new VepJsonOutputParser(base.mapper).parseByVariantId(output, "VEP", base.properties.getVep().getVersion()).getAnnotationsByVariantId();
            proof.set("annotations", base.mapper.valueToTree(parsed));
            assertEquals(3, parsed.size(), "All public target annotations required before analysis");
            for (var annotations : parsed.values()) assertTrue(annotations.stream().anyMatch(tx ->
                    "BRAF".equals(tx.getGeneSymbol()) && "ENST00000646891.2".equals(tx.getVersionedTranscriptId())),
                    "Public target needs the versioned policy transcript");
            List<String> command = base.mapper.readValue(environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-raw-command-json"),
                    base.mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            var runtime = IntronicScopeRuntimeProbe.run(base.mapper, command,
                    environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-reference-fasta"), work, new SystemExternalProcessRunner());
            proof.set("spliceReadiness", runtime); assertEquals("READY", runtime.path("status").asText(), runtime.toPrettyString());
            proof.put("readinessModelInferenceCalls", 0).put("status", "READY");
        } finally { save("preflight-report.json", proof); report.set("preflight", proof); }
    }

    private void publish(ObjectNode report) throws Exception {
        var validator = new VepResourceValidator(base.properties);
        var annotation = new VepTranscriptAnnotationEnrichmentService(base.jobs, base.properties, validator,
                new VepCommandBuilder(base.properties), new VepJsonOutputParser(base.mapper),
                countedVep("vepPublication", new SystemExternalProcessRunner()),
                new TranscriptAnnotationService(base.properties, new AnnTranscriptAnnotationProvider(), new InMemoryTranscriptAnnotationCache(), validator));
        // Legacy enrichment is not responsible for Exomiser candidates or ACMG evidence.
        var enrichment = mock(VariantInterpretationService.class);
        when(enrichment.enrichEvidenceOnly(any(), any())).thenAnswer(call -> call.getArgument(0));
        var processor = new VariantAnalysisJobProcessor(base.jobs, new PicardVcfBuildConverter(base.properties),
                mock(VcfNormalizer.class), mock(VcfQcService.class), enrichment, base.properties, annotation);
        ReflectionTestUtils.setField(processor, "acmgBusinessEnabled", true); processor.setAcmgExecutionSources(base.sources);
        processor.setExomiserEngineAdapter(new ConfiguredExomiserEngineAdapter(base.properties, (command, directory, output, error, env, timeout) -> {
            add("exomiser", 1); return new SystemExomiserProcessRunner().run(command, directory, output, error, env, timeout);
        }, new ExomiserRuntimeValidator()));
        var service = new VariantAnalysisServiceImpl(base.jobs, mock(VariantEnhancementRepository.class), processor,
                mock(VariantEnhancementProcessor.class), base.properties);
        MockMvc upload = MockMvcBuilders.standaloneSetup(new VariantAnalysisController(service,
                mock(VariantAnalysisExportService.class), mock(VariantAnalysisPreflightService.class),
                new VcfHeaderSampleDetectionService(), base.properties)).build();
        ObjectNode response = observedHttp(upload, multipart("/variant-analyses")
                .file(new MockMultipartFile("vcfFile", "public-ps1-pm5.vcf", "text/plain", VCF.getBytes(StandardCharsets.UTF_8)))
                .param("genomeAssembly", "GRCh38").param("analysisMode", "WES")
                .param("analysisScope", "STANDARD").param("sampleId", "synthetic-proband"), "upload");
        base.analysisId = response.path("id").asText(); report.put("analysisId", base.analysisId);
        var job = base.jobs.findById(base.analysisId).orElseThrow();
        assertEquals(VariantAnalysisStatus.READY, job.getStatus(), job.getErrorMessage());
        assertEquals("STANDARD", AnalysisScopePolicy.publicDetails(job).get("scope"));
        var candidates = base.jobs.findInterpretations(base.analysisId, 0, 20);
        save("formal-candidates.json", base.mapper.valueToTree(candidates));
        var identities = new LinkedHashMap<String, String>();
        for (var candidate : candidates) identities.put(candidate.getCandidateId(),
                candidate.getObservation().getKey().canonicalId().replace("chr7", "7").replace('|', ':'));
        save("formal-identities.json", base.mapper.valueToTree(identities));
        report.set("formalIdentities", base.mapper.valueToTree(identities)); report.put("formalCandidateCount", candidates.size());
        assertFalse(candidates.isEmpty(), "No formal candidates: do not inject controls into Top-N");
        assertTrue(base.store.source(base.analysisId).isPresent(), "Publication must preserve actual engine provenance");
    }

    private void wireBusiness() {
        base.service = new AcmgBusinessConfig().acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, environment);
        observeBusiness(); base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }

    @SuppressWarnings("unchecked")
    private void observeBusiness() {
        var service = Objects.requireNonNull(base.service);
        Object recommendations = requiredField(service, "recommendations");
        var identityLookup = (Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup>) requiredField(recommendations, "lookup");
        ReflectionTestUtils.setField(recommendations, "lookup", (Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup>) candidate -> {
            add("amIdentityLookup", 1); return identityLookup.apply(candidate);
        });
        Supplier<ExplicitCaseInputCoordinator> factory = (Supplier<ExplicitCaseInputCoordinator>) requiredField(service, "runtime");
        ReflectionTestUtils.setField(service, "runtime", (Supplier<ExplicitCaseInputCoordinator>) () -> {
            add("coordinatorFactory", 1); var coordinator = spy(Objects.requireNonNull(factory.get()));
            doAnswer(call -> {
                var result = (ExplicitCaseInputCoordinator.Result) call.callRealMethod();
                var dispatch = Objects.requireNonNull(result).adapterResult().dispatch();
                add("predictionProvider", dispatch.providerCalls()); add("predictionEvaluator", dispatch.evaluatorCalls());
                add("provider", dispatch.providerCalls()); add("evaluator", dispatch.evaluatorCalls()); return result;
            }).when(coordinator).dispatch(any(), any(), any(), any(), anyList(), anyInt(), anyList(), anyMap());
            return coordinator;
        });
        Supplier<AcmgBusinessService.Ba1Runtime> ba1 = (Supplier<AcmgBusinessService.Ba1Runtime>) requiredField(service, "ba1Runtime");
        ReflectionTestUtils.setField(service, "ba1Runtime", (Supplier<AcmgBusinessService.Ba1Runtime>) () -> {
            var runtime = Objects.requireNonNull(ba1.get()); var evaluator = spy(runtime.evaluator());
            doAnswer(call -> {
                ObjectNode result = (ObjectNode) call.callRealMethod();
                int p = Objects.requireNonNull(result).path("providerCalls").asInt(), e = result.path("evaluatorCalls").asInt();
                add("ba1Provider", p); add("ba1Evaluator", e); add("provider", p); add("evaluator", e); return result;
            }).when(evaluator).evaluate(anyString(), any(), any());
            return new AcmgBusinessService.Ba1Runtime(runtime.producer(), evaluator);
        });
        Supplier<ClinVarMissenseBusinessRuntime> missense = (Supplier<ClinVarMissenseBusinessRuntime>) requiredField(service, "missenseRuntime");
        ReflectionTestUtils.setField(service, "missenseRuntime", (Supplier<ClinVarMissenseBusinessRuntime>) () -> {
            var runtime = Objects.requireNonNull(missense.get());
            Object comparator = requiredField(runtime, "comparator");
            var clinvar = spy((ClinVarTabixProvider) requiredField(comparator, "clinVarProvider"));
            doAnswer(call -> { add("clinvarDiscovery", 1); return call.callRealMethod(); })
                    .when(clinvar).discoverBoundedComparators(any(), anyInt(), anyInt());
            ReflectionTestUtils.setField(comparator, "clinVarProvider", clinvar);
            var sidecar = spy((ClinVarComparatorSidecar) requiredField(comparator, "sidecar"));
            doAnswer(call -> { add("clinvarSidecarLookup", 1); return call.callRealMethod(); })
                    .when(sidecar).lookup(any(), anyString());
            ReflectionTestUtils.setField(comparator, "sidecar", sidecar);
            Object vep = requiredField(comparator, "vepProvider");
            ReflectionTestUtils.setField(vep, "processRunner", countedVep("vepComparator", (ExternalProcessRunner) requiredField(vep, "processRunner")));
            var actual = (Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult>) requiredField(runtime, "splice");
            ReflectionTestUtils.setField(runtime, "splice", (Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult>) queries -> {
                add("spliceRawBatch", 1); add("spliceQuery", queries.size()); var result = actual.apply(queries);
                try {
                    save("raw-batch-" + counters.get("spliceRawBatch") + "-queries.json", base.mapper.valueToTree(queries));
                    if (result != null && result.getResult() != null) save("raw-batch-" + counters.get("spliceRawBatch") + "-response.json", result.getResult());
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                return result;
            });
            ClinVarMissenseBusinessRuntime counted = spy(runtime);
            doAnswer(call -> {
                add("missenseRuntime", 1); ObjectNode result = (ObjectNode) call.callRealMethod();
                int p = Objects.requireNonNull(result).path("providerCalls").asInt(), e = result.path("evaluatorCalls").asInt();
                add("missenseProvider", p); add("missenseEvaluator", e); add("provider", p); add("evaluator", e);
                save("missense-runtime-" + counters.get("missenseRuntime") + ".json", result); return result;
            }).when(counted).evaluate(any(), any(), any());
            return counted;
        });
    }

    private ObjectNode declaration(ObjectNode options) {
        var request = base.mapper.createObjectNode().put("topN", EVALUATION_TOP_N).put("declarationVersion", "ordinary-ui-v3")
                .put("sampleId", "synthetic-proband").put("dataClass", "SYNTHETIC");
        request.putObject("patient").put("patientContextId", "patient-context-public-ps1-pm5")
                .put("role", "PROBAND").put("sex", "UNKNOWN").set("source", source("public-panel/case"));
        var hypotheses = request.putArray("hypotheses"); var selections = request.putArray("recommendations");
        for (JsonNode row : options.path("candidates")) {
            JsonNode recommendation = row.path("recommendation"), relation = null;
            for (JsonNode candidate : recommendation.path("relationships")) {
                if (candidate.path("selectable").asBoolean() && "HGNC:1097".equals(candidate.path("hgncId").asText())
                        && "VCEP_SPECIFICATION_SCOPE".equals(candidate.path("relationshipType").asText())
                        && "AUTOSOMAL_DOMINANT".equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(candidate.path("moi").asText()))) {
                    assertNull(relation, "Ambiguous VCEP relationship"); relation = candidate;
                }
            }
            assertNotNull(relation, "Public RASopathy hypothesis must be explicitly selectable from source-backed options");
            JsonNode requiredRelation = Objects.requireNonNull(relation), transcript = recommendation.path("ps1Pm5CompatibleTranscript");
            assertEquals("ENST00000646891.2", transcript.path("versionedTranscriptId").asText(), "Use the source-backed PS1/PM5 policy transcript");
            assertTrue(transcript.path("ps1Pm5Compatible").asBoolean());
            assertFalse(transcript.path("identityCompatible").asBoolean(), "PS1/PM5 applicability must not fabricate AM compatibility");
            assertTrue(recommendation.path("resourceCompatibleTranscript").isNull(), "The actual AM/VEP version mismatch is retained");
            assertEquals("AVAILABLE", recommendation.at("/resourceIdentity/status").asText());
            assertTrue(java.util.stream.StreamSupport.stream(recommendation.at("/resourceIdentity/records").spliterator(), false)
                    .anyMatch(record -> "ENST00000646891.1".equals(record.path("transcriptId").asText())), "Preserve actual AM transcript version .1");
            var selection = selections.addObject(); selection.set("candidateId", row.path("candidateId"));
            for (String field : List.of("recommendationId", "policyVersion", "policyHash", "generatedAt")) selection.set(field, recommendation.path(field));
            selection.set("stableRelationshipKey", requiredRelation.path("stableRelationshipKey")); selection.set("annotationId", transcript.path("annotationId"));
            var item = hypotheses.addObject().put("affectedStatus", "UNKNOWN"); var hypothesis = item.putObject("hypothesis");
            hypothesis.set("candidateId", row.path("candidateId")); hypothesis.set("hypothesisId", recommendation.path("hypothesisId"));
            for (String field : List.of("hgncId", "mondoId", "moi")) hypothesis.set(field, requiredRelation.path(field));
            hypothesis.set("provenance", source("public-panel/explicit-rasopathy-hypothesis"));
            String versioned = transcript.path("versionedTranscriptId").asText(); int dot = versioned.lastIndexOf('.');
            item.putObject("transcript").put("accession", versioned.substring(0, dot)).put("version", Integer.parseInt(versioned.substring(dot + 1)))
                    .put("annotationSource", transcript.path("annotationSource").asText()).put("annotationSourceVersion", transcript.path("annotationSourceVersion").asText())
                    .put("selectionPolicy", recommendation.path("policyId").asText() + "@" + recommendation.path("policyVersion").asText())
                    .put("selectionReason", "Explicit source-backed public RASopathy AD test hypothesis and PS1/PM5 policy transcript confirmation; AM version compatibility is not established; not a clinical diagnosis")
                    .set("source", source("recommendations/" + recommendation.path("recommendationId").asText() + "/selection"));
        }
        return request;
    }

    private ObjectNode scientificOutcomes(ObjectNode saved) throws Exception {
        JsonNode identities = base.mapper.readTree(Files.readAllBytes(work.resolve("formal-identities.json")));
        ObjectNode result = base.mapper.createObjectNode();
        for (var control : CONTROLS.entrySet()) {
            ObjectNode outcome = result.putObject(control.getKey()).put("allele", control.getValue()).put("formalCandidateFound", false);
            for (JsonNode candidate : saved.path("candidates")) {
                if (!control.getValue().equals(identities.path(candidate.path("candidateId").asText()).asText())) continue;
                outcome.put("formalCandidateFound", true).set("candidateId", candidate.path("candidateId"));
                for (JsonNode attempt : candidate.path("attempts")) {
                    JsonNode comparison = attempt.path("missenseComparison"); outcome.set("blockers", comparison.path("blockers"));
                    for (JsonNode evaluation : comparison.path("criterionEvaluations")) {
                        String criterion = evaluation.path("criterionId").asText().replace("ACMG_AMP_2015:", "");
                        ObjectNode value = outcome.putObject(criterion).put("status", evaluation.at("/evaluationResult/status").asText())
                                .put("applicability", evaluation.at("/criterionApplicability/status").asText()).put("appliedStrength", evaluation.path("appliedStrength").asText());
                        value.set("reasonCode", evaluation.at("/evaluationResult/reasonCode"));
                    }
                }
            }
        }
        return result;
    }

    private ObjectNode observedHttp(MockMvc http, RequestBuilder request, String name) throws Exception {
        Map<String, Integer> before = snapshot(); var result = http.perform(Objects.requireNonNull(request)).andReturn();
        byte[] content = result.getResponse().getContentAsByteArray(); Files.write(work.resolve(name + "-response.json"), content);
        byte[] body = result.getRequest().getContentAsByteArray();
        if (body != null && body.length > 0) Files.write(work.resolve(name + "-request.json"), body);
        save(name + "-http-event.json", base.mapper.createObjectNode().put("transport", "Spring MockMvc controller dispatch")
                .put("method", result.getRequest().getMethod()).put("uri", result.getRequest().getRequestURI())
                .put("httpStatus", result.getResponse().getStatus()).put("responseSha256", sha(content))
                .set("actualCallIncrement", base.mapper.valueToTree(delta(before))));
        assertTrue(result.getResponse().getStatus() >= 200 && result.getResponse().getStatus() < 300,
                name + ": " + result.getResponse().getContentAsString(Objects.requireNonNull(StandardCharsets.UTF_8)));
        return (ObjectNode) base.mapper.readTree(content);
    }
    private ExternalProcessRunner countedVep(String name, ExternalProcessRunner actual) {
        return (command, directory, timeout) -> { add(name, 1); return actual.run(command, directory, timeout); };
    }
    private ObjectNode source(String locator) {
        return base.mapper.createObjectNode().put("source", "SYNTHETIC_PUBLIC_PANEL_DECLARATION").put("version", "1").put("recordLocator", locator);
    }
    private static @org.springframework.lang.NonNull Object requiredField(Object target, String name) {
        return Objects.requireNonNull(ReflectionTestUtils.getField(Objects.requireNonNull(target), Objects.requireNonNull(name)), name);
    }
    private void add(String key, int value) {
        counters.merge(key, value, (left, right) -> Objects.requireNonNull(left) + Objects.requireNonNull(right));
    }
    private Map<String, Integer> snapshot() { return new TreeMap<>(counters); }
    private Map<String, Integer> delta(Map<String, Integer> before) {
        var result = new TreeMap<String, Integer>(); counters.forEach((key, value) -> result.put(key, value - before.getOrDefault(key, 0))); return result;
    }
    private static void assertZero(Map<String, Integer> calls) { assertTrue(calls.values().stream().allMatch(value -> value == 0), calls.toString()); }
    private void save(String name, JsonNode value) throws java.io.IOException { save(name, value.toPrettyString()); }
    private void save(String name, String value) throws java.io.IOException { Files.writeString(work.resolve(name), value, StandardCharsets.UTF_8); }
    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
}
