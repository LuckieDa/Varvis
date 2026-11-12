package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.nbscn.varvis.variantanalysis.config.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual ordinary config/recommendation/controller/store with toy files, mocked tiny-FASTA readiness and retained annotation publication. */
class Bp1BusinessIntegrationTest {
    Path temporary;
    AcmgBusinessIntegrationTest base;
    Bp1BusinessFixture fixture;
    ReviewedRuleBundle bundle;
    ObjectNode document;
    MockEnvironment env;
    String scenario;
    final AtomicInteger forbidden = new AtomicInteger(), legacy = new AtomicInteger(), coordinator = new AtomicInteger(), reference = new AtomicInteger();
    final AtomicInteger annotationPublications = new AtomicInteger();
    final AtomicInteger durableClaimsObservedBeforeScience = new AtomicInteger();
    final List<ReviewedRuleBundle> bundles = new ArrayList<>();
    MockedConstruction<NormalizationResourceValidator> readiness;
    @BeforeEach void setup(TestInfo info) throws Exception {
        temporary = Bp1TestDirectory.create("business");
        scenario = info.getTestMethod().orElseThrow().getName() + "-" + info.getDisplayName().replaceAll("[^a-zA-Z0-9_-]", "_");
        base = new AcmgBusinessIntegrationTest() {
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() {
                var annotation = mock(VepTranscriptAnnotationEnrichmentService.class);
                when(annotation.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
                    annotationPublications.incrementAndGet();
                    List<VariantInterpretation> rows = call.getArgument(0);var result = new LinkedHashMap<String, List<TranscriptConsequence>>();
                    for (var c : rows) result.put(c.getVariantId(), List.of(Bp1BusinessIntegrationTest.this.fixture.annotation("11".equals(c.getObservation().getKey().getChromosome().replace("chr", "")) ? 0 : 1)));
                    return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(result, VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                });return annotation;
            }
        };
        base.temporary = temporary;base.setup();
    }
    private void prepare() throws Exception {
        fixture = new Bp1BusinessFixture(base.mapper);document = fixture.document();
        artifact("fixed-materials", document);artifact("fixed-independent-expected", base.mapper.valueToTree(fixture.expected));
        artifact("fixed-annotations", base.mapper.valueToTree(fixture.annotations));artifact("fixed-alleles", base.mapper.valueToTree(fixture.alleles));
        var a = fixture.alleles.get(0);var b = fixture.alleles.get(1);
        base.variantTsvOverride = tsv("1\t11\t" + a.getPosition() + "\t" + a.getReference() + "\t" + a.getAlternate() + "\tTEST_BP1_PLUS\t999101\tAD\t0.8",
                "2\t13\t" + b.getPosition() + "\t" + b.getReference() + "\t" + b.getAlternate() + "\tTEST_BP1_MINUS\t999102\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t" + a.getPosition() + "\t.\t" + a.getReference() + "\t" + a.getAlternate() + "\t.\tPASS\t.\tGT\t.",
                "chr13\t" + b.getPosition() + "\t.\t" + b.getReference() + "\t" + b.getAlternate() + "\t.\tPASS\t.\tGT\t."));
        bindings();tinyReference();
        env = new MockEnvironment().withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.BP1_DEVELOPMENT)
                .withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.binding-directory", Objects.requireNonNull(temporary.resolve("bindings").toString()))
                .withProperty("variant-analysis.acmg-business.resource-root", Objects.requireNonNull(temporary.toString()))
                .withProperty("variant-analysis.acmg-business.reference-receipt-file", Objects.requireNonNull(temporary.resolve("bp1-reference-ledger.json").toString()))
                .withProperty("variant-analysis.acmg-business.reference-receipt-sha256", Objects.requireNonNull(sha(Files.readAllBytes(temporary.resolve("bp1-reference-ledger.json")))))
                .withProperty("variant-analysis.acmg-business.reference-receipt-locator", "checksum");
        writeBundle();wire();
    }
    private void writeBundle() throws Exception {
        Path file = temporary.resolve("test-only-bp1-bundle.json");byte[] raw = base.mapper.writeValueAsBytes(document);Files.write(file, raw);
        env.withProperty("variant-analysis.acmg-business.bp1-bundle-file", Objects.requireNonNull(file.toString()))
                .withProperty("variant-analysis.acmg-business.bp1-bundle-file-sha256", Objects.requireNonNull(sha(raw)))
                .withProperty("variant-analysis.acmg-business.bp1-bundle-sha256", Objects.requireNonNull(fixture.canonical(document)));
        artifact("configured-bundle-" + bundles.size(), document);
        Path archive = output();
        if (archive != null) Files.write(archive.resolve("configured-bundle-" + bundles.size() + "-raw.json"), raw, StandardOpenOption.CREATE_NEW);
    }
    private void wire() {
        base.service = new AcmgBusinessConfig().acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, env);
        var configuredService = Objects.requireNonNull(base.service);
        Object rules = ReflectionTestUtils.getField(configuredService, "reviewedRules");
        if (!(rules instanceof ReviewedRuleBundle configuredBundle)) throw new AssertionError("Missing BP1 bundle");
        bundle = configuredBundle;bundles.add(bundle);
        var observedRuntimeCalls=spy(new AtomicInteger());
        doAnswer(call -> {
            assertTrue(base.store.runs(base.analysisId).stream().anyMatch(run -> "RUNNING".equals(run.status())), "Durable claim must precede BP1 runtime/science");
            durableClaimsObservedBeforeScience.incrementAndGet();return call.callRealMethod();
        }).when(observedRuntimeCalls).incrementAndGet();
        ReflectionTestUtils.setField(configuredBundle,"bp1Runtimes",observedRuntimeCalls);
        Object original = ReflectionTestUtils.getField(configuredService, "runtime");
        if (!(original instanceof Supplier<?> supplier)) throw new AssertionError("Missing configured coordinator");
        ReflectionTestUtils.setField(configuredService, "runtime", (Supplier<ExplicitCaseInputCoordinator>) () -> { coordinator.incrementAndGet();return (ExplicitCaseInputCoordinator) supplier.get(); });
        ReflectionTestUtils.setField(configuredService, "pvs1Runtime", (Supplier<Hnf1aPvs1Runtime>) () -> { legacy.incrementAndGet();throw new AssertionError("OLD_HNF1A_SUPPLIER_FORBIDDEN"); });
        for (String field : List.of("ba1Runtime", "pm2Runtime", "missenseRuntime", "functionalRuntime", "pm1Runtime", "hnf1aFrequencyRuntime"))
            ReflectionTestUtils.setField(configuredService, Objects.requireNonNull(field), (Supplier<Object>) () -> { forbidden.incrementAndGet();throw new AssertionError("OTHER_RULE_SUPPLIER_FORBIDDEN"); });
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    private ObjectNode options() throws Exception {
        return (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId).param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private ObjectNode confirmed(ObjectNode options) {
        var request = base.request.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.BP1_DECLARATION);var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var r = options.at("/candidates/" + i + "/recommendation");var rel = r.path("relationships").path(0);var annotation = r.path("annotations").path(0);
            assertTrue(annotation.path("ruleContextReady").asBoolean(), options.toPrettyString());assertFalse(rel.isMissingNode(), options.toPrettyString());
            var item = (ObjectNode) request.path("hypotheses").get(i);var hyp = (ObjectNode) item.path("hypothesis");
            hyp.put("hypothesisId", r.path("hypothesisId").asText());for (String f : List.of("hgncId", "mondoId", "moi")) hyp.set(f, rel.path(f));item.put("affectedStatus", "UNKNOWN");
            var tx = (ObjectNode) item.path("transcript");String versioned = annotation.path("versionedTranscriptId").asText();int dot = versioned.lastIndexOf('.');
            tx.put("accession", versioned.substring(0, dot)).put("version", Integer.parseInt(versioned.substring(dot + 1)))
                    .put("selectionPolicy", annotation.path("ruleContextSelectionPolicy").asText()).put("selectionReason", "TEST_ONLY explicit context confirmation, not scientific approval");
            tx.set("annotationSource", annotation.path("annotationSource"));tx.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            ((ObjectNode) tx.path("source")).put("source", "TEST_ONLY_BP1_SELECTION").put("recordLocator", "recommendations/" + r.path("recommendationId").asText() + "/selection");
            var ref = refs.addObject();for (String f : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(f, r.path(f));
            ref.set("stableRelationshipKey", rel.path("stableRelationshipKey"));ref.set("annotationId", annotation.path("annotationId"));
        }return request;
    }
    private ObjectNode counts() {
        return base.mapper.createObjectNode().put("coordinator", coordinator.get()).put("durableClaimsObservedBeforeScience",durableClaimsObservedBeforeScience.get()).put("oldHnf1aSupplier", legacy.get()).put("otherSuppliers", forbidden.get())
                .put("sequenceCalculations", bundles.stream().mapToInt(b -> Objects.requireNonNull(b).bp1SequenceCalls()).sum())
                .put("runtime", bundles.stream().mapToInt(b -> Objects.requireNonNull(b).bp1RuntimeCalls()).sum())
                .put("projection", bundles.stream().mapToInt(b -> Objects.requireNonNull(b).bp1ProjectionCalls()).sum())
                .put("evaluator", bundles.stream().mapToInt(b -> Objects.requireNonNull(b).bp1EvaluatorCalls()).sum()).put("referenceReadiness", reference.get())
                .put("syntheticAnnotationPublicationTransport", annotationPublications.get());
    }
    @Test
    void configToControllerFullSaveRebuildGetAndReuse() throws Exception {
        prepare();var options = options();var request = confirmed(options);var before = counts();
        artifact("input-options", options);artifact("request", request);var saved = base.postRequest(request);artifact("submit", saved);
        assertEquals("COMPLETED", saved.path("status").asText(), saved.toPrettyString());
        assertEquals(List.of(Bp1CriterionEvaluator.CRITERION), base.mapper.convertValue(options.path("enabledCriteria"), List.class));
        for (int i = 0; i < 2; i++) {
            var p = saved.at("/candidates/" + i + "/attempts/0/bp1");var e = p.path("evaluation");var expected = fixture.expected.get(i);
            assertEquals("MET", e.at("/evaluationResult/status").asText());assertEquals("SUPPORTING", e.path("appliedStrength").asText());
            for (String key : List.of("refCodon", "altCodon", "proteinVariantKey")) assertEquals(expected.path(key), p.at("/resolvedInput/" + key));
            assertEquals("SUPPORTED", p.at("/resolvedInput/truncatingMechanism/conclusion").asText());
            assertEquals("SUPPORTED", p.at("/resolvedInput/reviewedVariantApplicability/splicing/conclusion").asText());
            assertEquals("acmg-rule-registry-bp1-missense-2026-09-28", p.at("/registryBinding/registryId").asText());
            assertEquals(Bp1CriterionEvaluator.VERSION, e.at("/deterministicTrace/evaluatorVersion").asText());
            assertEquals(fixture.canonical(fixture.policies.get(i)), fixture.canonical(p.path("policy")));
            assertEquals(fixture.canonical(fixture.models.get(i)), p.at("/resolvedInput/transcriptModelSha256").asText());
            assertEquals(1, p.path("criterionEvaluations").size());
            assertTrue(saved.at("/candidates/" + i + "/enabledRuleResultsComplete").asBoolean());
        }
        var after = counts();assertEquals(2, after.path("runtime").asInt());assertEquals(2, after.path("projection").asInt());assertEquals(2, after.path("evaluator").asInt());assertEquals(2, after.path("sequenceCalculations").asInt());
        artifact("saved-store", base.mapper.readTree(base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().responseJson()));
        shutdownAndRebuild();var afterRebuild = counts();assertEquals(after, afterRebuild);
        var getResult = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, getResult);var afterGet = counts();var reuse = base.postRequest(request);assertEquals(saved, reuse);assertEquals(after, counts());
        assertEquals(1, base.store.runs(base.analysisId).size());artifact("get-after-store-service-rebuild", getResult);artifact("reuse", reuse);
        ObjectNode c = base.mapper.createObjectNode();c.set("beforeSubmit", before);c.set("afterSave", after);c.set("afterStoreServiceRebuild", afterRebuild);c.set("afterGet", afterGet);c.set("afterReuse", counts());artifact("observed-counts", c);
    }
    @Test void requestsCannotForgeMaterialsOrSkipConfirmationOrCrossScope() throws Exception {
        prepare();var request = confirmed(options());var before = counts();
        reject(request.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.PM1_DECLARATION), 400);
        var no = request.deepCopy();no.remove("recommendations");reject(no, 400);
        for (String field : List.of("model", "policy", "appliedStrength", "reviewStatus")) { var forged = request.deepCopy();forged.put(field, "APPROVED");reject(forged, 400); }
        var stale = request.deepCopy();((ObjectNode) stale.at("/recommendations/0")).put("annotationId", "other");reject(stale, 409);
        var token = request.deepCopy();((ObjectNode) token.at("/hypotheses/0/transcript")).put("selectionPolicy", "REVIEWED_RULE_CONTEXT@8.0.0:" + "0".repeat(64));reject(token, 409);
        var crossed = request.deepCopy();((ObjectNode) crossed.at("/hypotheses/0/hypothesis")).put("moi", "AUTOSOMAL_RECESSIVE");reject(crossed, 409);
        for(String field:List.of("hgncId","mondoId","hypothesisId")){
            var wrong=request.deepCopy();((ObjectNode)wrong.at("/hypotheses/0/hypothesis")).set(field,request.at("/hypotheses/1/hypothesis/"+field));reject(wrong,409);
        }
        for(String field:List.of("accession","version","annotationSource","annotationSourceVersion")){
            var wrong=request.deepCopy();((ObjectNode)wrong.at("/hypotheses/0/transcript")).set(field,field.equals("version")?base.mapper.getNodeFactory().numberNode(2):base.mapper.getNodeFactory().textNode(field.equals("accession")?"ENST000102":field.equals("annotationSource")?"VEP_CACHED":"115.0"));reject(wrong,409);
        }
        assertEquals(before.path("runtime"), counts().path("runtime"));assertEquals(0, forbidden.get());assertEquals(0, legacy.get());assertTrue(base.store.runs(base.analysisId).isEmpty());
    }
    @Test void unknownConditionIsSavedWithBlockersAndFullHistory() throws Exception {
        prepare();
        fixture.fixtures.get(0).mechanism.withObject("reviewedVariantApplicability").withObject("splicing").put("conclusion", "UNKNOWN"); document = fixture.document(); writeBundle(); wire();
        var opts = options(); var request = confirmed(opts); var beforeSubmit=counts();var saved = base.postRequest(request);
        var value = saved.at("/candidates/0/attempts/0/bp1");
        assertEquals("NOT_EVALUATED", value.at("/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("BP1_CONDITION_UNKNOWN_OR_CONFLICT", value.at("/evaluationResult/reasonCode").asText());
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
        assertEquals("MET",saved.at("/candidates/1/attempts/0/bp1/evaluation/evaluationResult/status").asText());
        assertFalse(value.path("criterionEvaluations").path(0).has("appliedStrength"));
        artifact("input-options", opts); artifact("request", request); artifact("submit", saved);
        artifact("saved-store", base.mapper.readTree(base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().responseJson()));
        var before = counts(); shutdownAndRebuild();var afterRebuild=counts();
        var history = base.service.read(base.analysisId, saved.path("runId").asText()); assertEquals(saved, history);var afterGet=counts();
        assertEquals(saved, base.postRequest(request)); assertEquals(before, counts()); assertEquals(1, base.store.runs(base.analysisId).size());
        artifact("get-after-store-service-rebuild", history); artifact("reuse", base.postRequest(request));
        ObjectNode counters=base.mapper.createObjectNode();counters.set("beforeSubmit",beforeSubmit);counters.set("afterSave",before);counters.set("afterStoreServiceRebuild",afterRebuild);counters.set("afterGet",afterGet);counters.set("afterReuse",counts());artifact("observed-counts",counters);
    }
    @Test void changedMaterialAndCandidateInvalidateOldConfirmationAndHistoryRemainsFrozen() throws Exception {
        prepare();var request = confirmed(options());var saved = base.postRequest(request);var before = counts();
        Files.writeString(temporary.resolve("test-only-bp1-bundle.json"), "BROKEN_TEST_ONLY");
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));assertEquals(before, counts());
        assertThrows(IllegalArgumentException.class, () -> new AcmgBusinessConfig().acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, env));
        // Same IDs and versions, changed reviewed content: stale confirmation must not reuse the saved MET.
        fixture.fixtures.get(0).row("truncatingMechanism").put("conclusion","NOT_SUPPORTED");document = fixture.document();writeBundle();wire();
        reject(request, 409);assertEquals(before.path("runtime"), counts().path("runtime"));
        var newRequest = confirmed(options());var changed = base.postRequest(newRequest);assertNotEquals(saved.path("runId"), changed.path("runId"));
        assertEquals("NOT_MET",changed.at("/candidates/0/attempts/0/bp1/evaluation/evaluationResult/status").asText());
        var candidate = base.jobs.findInterpretations(base.analysisId, 0, 2).get(0);
        candidate.applyTranscriptAnnotations(List.of(fixture.annotation(0).withAnnotationMetadata("VEP", "COMPLETED", "116.0", java.time.Instant.parse("2026-09-27T00:00:00Z"))));
        replaceCandidateAndSource(candidate);var prior = counts();reject(newRequest, 409);assertEquals(prior.path("runtime"), counts().path("runtime"));
        var sourceRequest=confirmed(options());var sourceChanged=base.postRequest(sourceRequest);
        assertNotEquals(changed.path("runId"),sourceChanged.path("runId"));
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));assertEquals(3, base.store.runs(base.analysisId).size());
        artifact("changed-source-request",sourceRequest);artifact("changed-source-run",sourceChanged);
        artifact("original-request", request);artifact("original-saved", saved);artifact("updated-request", newRequest);artifact("updated-run", changed);artifact("history", base.service.read(base.analysisId, saved.path("runId").asText()));
    }
    private void shutdownAndRebuild() throws Exception {
        var before=counts();base.closeDatabase();
        assertFalse(Files.exists(temporary.resolve("business-db.lock.db")));
        Path file=temporary.resolve("business-db.mv.db");assertTrue(Files.isRegularFile(file));
        artifact("pre-rebuild-h2-shutdown",base.mapper.createObjectNode().put("explicitShutdown",true).put("lockFileAbsent",true)
                .put("file",file.toString()).put("bytes",Files.size(file)).put("rawSha256",sha(Files.readAllBytes(file))).set("counts",before));
        base.recreateRepositories();wire();assertEquals(before,counts());
    }
    private void replaceCandidateAndSource(VariantInterpretation candidate) throws Exception {
        var rows = new ArrayList<>(base.jobs.findInterpretations(base.analysisId, 0, 2));rows.set(0, candidate);
        base.jobs.replaceInterpretations(base.analysisId, rows);
        ObjectNode source = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        ObjectNode row = (ObjectNode) source.at("/candidates/0");row.set("published", base.mapper.valueToTree(candidate));row.set("engineCandidate", base.mapper.valueToTree(candidate));base.replaceSnapshot(source);
        artifact("changed-source", source);
    }
    private void reject(ObjectNode request, int statusCode) throws Exception {
        var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json").content(Objects.requireNonNull(base.mapper.writeValueAsBytes(request))))
                .andExpect(status().is(statusCode)).andReturn().getResponse();
        artifact("rejected-" + UUID.randomUUID(), base.mapper.createObjectNode().put("httpStatus", response.getStatus()).put("error", response.getErrorMessage()).set("request", request));
    }
    private void bindings() throws Exception {
        Path dir = temporary.resolve("bindings");Files.createDirectories(dir);var manifest = (ObjectNode) base.mapper.readTree(Files.readAllBytes(temporary.resolve("manifest.json")));
        for (String name : List.of("mane", "clingen")) {
            var rows = base.mapper.createArrayNode();
            for (int i = 0; i < 2; i++) {
                var row = rows.addObject().put("hgncId", fixture.models.get(i).path("hgncId").asText()).put("symbol", fixture.models.get(i).path("geneSymbol").asText()).put("testDataClass", "TEST_ONLY_FICTIONAL");
                if (name.equals("mane")) row.put("ncbiGeneId", "99910" + (i + 1)).putArray("sourceRows").add(i + 1);
                else row.put("mondoId", "MONDO:099910" + (i + 1)).put("moi", i == 0 ? "AD" : "AR").put("diseaseLabel", "TEST_ONLY fictional condition " + i)
                        .put("classification", "Definitive").put("classificationDate", "2026-09-27").put("gcep", "TEST_ONLY").put("reportLocator", "test-only/" + i).put("sourceRow", i + 1);
            }
            byte[] bytes = base.mapper.writeValueAsBytes(rows);Files.write(dir.resolve(name.equals("mane") ? "mane-crosswalk.json" : "clingen-catalog.json"), bytes);
            ((ObjectNode) manifest.at("/resources/" + name)).put("derivedSha256", sha(bytes)).put("derivedRecordCount", 2).put("filterPolicy", "TEST_ONLY_FICTIONAL_NOT_PUBLIC_FACTS");
        }
        Files.write(dir.resolve("manifest.json"), base.mapper.writeValueAsBytes(manifest));
    }
    private void tinyReference() throws Exception {
        StringBuilder fasta = new StringBuilder(), index = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            char[] seq = new char[23000];Arrays.fill(seq, 'A');var m = fixture.models.get(i);String cds = m.path("cdsSequence").asText();
            for (var exon : m.path("exons")) for (int c = exon.path("cdsStart").asInt(); c <= exon.path("cdsEnd").asInt(); c++) {
                int g = m.path("strand").asInt() == 1 ? exon.path("genomicStart").asInt() + c - exon.path("cdsStart").asInt() : exon.path("genomicEnd").asInt() - c + exon.path("cdsStart").asInt();
                char b = cds.charAt(c - 1);seq[g - 1] = i == 0 ? b : "TGCA".charAt("ACGT".indexOf(b));
            }
            String contig = i == 0 ? "chr11" : "chr13";fasta.append('>').append(contig).append('\n');
            index.append(contig).append('\t').append(seq.length).append('\t').append(fasta.length()).append('\t').append(seq.length).append('\t').append(seq.length + 1).append('\n');
            fasta.append(seq).append('\n');
        }
        Path file = temporary.resolve("test-only-bp1.fa");Files.writeString(file, fasta);Files.writeString(Path.of(file + ".fai"), index);String digest = sha(Files.readAllBytes(file));
        base.properties.getNormalization().setGrch38Reference(file.toString());base.properties.getNormalization().setGrch38ReferenceSha256(digest);base.properties.getNormalization().setGrch38ProfileId("TEST_ONLY_BP1");
        var receipt = base.mapper.createObjectNode().put("resourceId", "TEST_ONLY_BP1_FASTA").put("resourceVersion", "1").put("assembly", "GRCh38")
                .put("logicalName", "test-only-bp1.fa").put("expectedSha256", digest).put("observedSha256", digest).put("hashStatus", "VERIFIED")
                .put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true).put("byteLength", Files.size(file))
                .put("mtimeNs", Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS)).put("indexSha256", sha(Files.readAllBytes(Path.of(file + ".fai"))))
                .put("startedAt", "2026-09-27T00:00:00Z").put("completedAt", "2026-09-27T00:00:01Z").put("resourceDataClass", "SYNTHETIC");
        var ledger = base.mapper.createObjectNode().put("source", "TEST_ONLY_BP1").put("version", "1").put("dataClass", "SYNTHETIC");ledger.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        Files.write(temporary.resolve("bp1-reference-ledger.json"), base.mapper.writeValueAsBytes(ledger));
        readiness = mockConstruction(NormalizationResourceValidator.class, (mock, context) -> when(mock.validate(GenomeAssembly.GRCh38)).thenAnswer(call -> {
            reference.incrementAndGet();return new NormalizationReferenceReadiness(GenomeAssembly.GRCh38, "READY", "TEST_ONLY_BP1", "test-only-bp1.fa", digest, true, true, 2, "CHR_PREFIXED", List.of(), List.of("TEST_ONLY_TINY_FASTA_READINESS"));
        }));
    }
    @AfterEach void close() throws Exception {
        if (readiness != null) readiness.close();
        if (base == null) return;
        try { if (bundle != null) artifact("final-counts", counts()); }
        finally { base.closeDatabase(); }
        Path out = output();if (out != null) {
            // Closed database and all micro-inputs/publication files, including failure runs; never real resources.
            try (var walk = Files.walk(temporary)) { for (Path p : walk.filter(Files::isRegularFile).toList()) { Path dest = out.resolve("runtime").resolve(temporary.relativize(p));Files.createDirectories(dest.getParent());Files.copy(p, dest); } }
            artifact("cleanup", base.mapper.createObjectNode().put("explicitH2Shutdown", true).put("lockFileAbsent", !Files.exists(temporary.resolve("business-db.lock.db"))).put("realServiceStarted", false));
        }
        assertEquals(0, forbidden.get());assertEquals(0, legacy.get());
    }
    private Path output() throws Exception { String root = System.getProperty("varvis.bp1.artifacts");if (root == null) return null;Path p = Path.of(root).resolve("business").resolve(scenario);Files.createDirectories(p);return p; }
    private void artifact(String name, JsonNode value) throws Exception { Path out = output();if (out != null) Files.writeString(out.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
