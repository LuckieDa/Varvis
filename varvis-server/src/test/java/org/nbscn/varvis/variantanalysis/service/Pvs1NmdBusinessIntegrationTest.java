package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class Pvs1NmdBusinessIntegrationTest {
    private boolean policySourceMode;
    private boolean escapeMode;
    private boolean spliceMode;
    private String newSpliceMode;
    private String escapeProofCaps = "UNCAPPED";
    private String escapeProofFailure;
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    Pvs1NmdBusinessFixture fixture;
    ReviewedRuleBundle bundle;
    ObjectNode document;
    MockEnvironment env;
    String scenario;
    final AtomicInteger forbidden = new AtomicInteger(), legacy = new AtomicInteger(), coordinator = new AtomicInteger(), reference = new AtomicInteger();
    final AtomicInteger annotationPublications = new AtomicInteger();
    final List<ReviewedRuleBundle> bundles = new ArrayList<>();
    MockedConstruction<NormalizationResourceValidator> readiness;
    @BeforeEach void setup(TestInfo info) throws Exception {
        scenario = info.getTestMethod().orElseThrow().getName() + "-" + info.getDisplayName().replaceAll("[^a-zA-Z0-9_-]", "_");
        base = new AcmgBusinessIntegrationTest() {
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() {
                var annotation = mock(VepTranscriptAnnotationEnrichmentService.class);
                when(annotation.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
                    annotationPublications.incrementAndGet();
                    List<VariantInterpretation> rows = call.getArgument(0);var result = new LinkedHashMap<String, List<TranscriptConsequence>>();
                    for (var c : rows) result.put(c.getVariantId(), List.of(Pvs1NmdBusinessIntegrationTest.this.fixture.annotation("11".equals(c.getObservation().getKey().getChromosome().replace("chr", "")) ? 0 : 1)));
                    return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(result, VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                });return annotation;
            }
        };
        base.temporary = temporary;base.setup();
    }
    private void prepare(boolean frameshift) throws Exception {
        fixture = new Pvs1NmdBusinessFixture(base.mapper, frameshift, escapeMode);
        if (spliceMode) for (int i = 0; i < 2; i++) {
            var outcome = newSpliceMode == null ? Pvs1SpliceConsequenceTest.configure(fixture, i, escapeMode, i == 0)
                    : "SPLICE_ESCAPE".equals(newSpliceMode) ? configureRound4Escape(i)
                    : Pvs1SpliceRound3Fixture.configure(fixture, i, newSpliceMode);
            fixture.spliceOutcomes.add(outcome);
            if (newSpliceMode != null) Pvs1SpliceSourceQualificationTest.configureOriginals(base.mapper, fixture.policies.get(i), fixture.models.get(i), List.of(outcome));
        }
        document = fixture.document();
        artifact("fixed-materials", document);artifact("fixed-independent-expected", base.mapper.valueToTree(fixture.expected));
        artifact("fixed-annotations", base.mapper.valueToTree(fixture.annotations));artifact("fixed-alleles", base.mapper.valueToTree(fixture.alleles));
        var a = fixture.alleles.get(0);var b = fixture.alleles.get(1);
        base.variantTsvOverride = tsv("1\t11\t" + a.getPosition() + "\t" + a.getReference() + "\t" + a.getAlternate() + "\tTEST_NMD_PLUS\t999001\tAD\t0.8",
                "2\t13\t" + b.getPosition() + "\t" + b.getReference() + "\t" + b.getAlternate() + "\tTEST_NMD_MINUS\t999002\tAD\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t" + a.getPosition() + "\t.\t" + a.getReference() + "\t" + a.getAlternate() + "\t.\tPASS\t.\tGT\t.",
                "chr13\t" + b.getPosition() + "\t.\t" + b.getReference() + "\t" + b.getAlternate() + "\t.\tPASS\t.\tGT\t."));
        bindings();tinyReference();
        env = new MockEnvironment().withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PVS1_DEVELOPMENT)
                .withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.binding-directory", Objects.requireNonNull(temporary.resolve("bindings").toString()))
                .withProperty("variant-analysis.acmg-business.resource-root", Objects.requireNonNull(temporary.toString()))
                .withProperty("variant-analysis.acmg-business.reference-receipt-file", Objects.requireNonNull(temporary.resolve("nmd-reference-ledger.json").toString()))
                .withProperty("variant-analysis.acmg-business.reference-receipt-sha256", Objects.requireNonNull(sha(Files.readAllBytes(temporary.resolve("nmd-reference-ledger.json")))))
                .withProperty("variant-analysis.acmg-business.reference-receipt-locator", "checksum");
        writeBundle();wire();
    }
    private ObjectNode configureRound4Escape(int i) {
        /* Independent toy expectation, fixed before public proof implementation:
         * c.13_25del shifts the existing 600-base CDS; first altered residue 5,
         * PTC c.49_51 in altered CDS, retained last junction c.63 (distance 12).
         * Splitting reference exon 3 at c.62 gives a genuine PTC-spanning junction
         * after deletion, so this uses the new topology method on either strand.
         * The existing source rule critical-5 (5..190, minimum 1) yields STRONG.
         * Caps: UNCAPPED -> STRONG; PARENT_CAP -> MODERATE;
         * LOCAL_CAP (scope MODERATE, outcome SUPPORTING) -> SUPPORTING;
         * BOTH_CAPS (parent MODERATE, scope SUPPORTING, outcome STRONG) -> SUPPORTING.
         * Neither a cap nor this synthetic original creates scientific approval.
         */
        var outcome = Pvs1SpliceConsequenceTest.configure(fixture, i, true, false);
        var model = fixture.models.get(i);var policy = fixture.policies.get(i);var exons = model.putArray("exons");
        int start = 1;int[] ends = {12,25,62,76,600};
        for (int j=0;j<ends.length;j++) {
            int genomic = i == 0 ? 2000+j*1000 : 20000-j*1000;
            exons.addObject().put("ordinal",j+1).put("cdsStart",start).put("cdsEnd",ends[j])
                    .put("genomicStart",genomic).put("genomicEnd",genomic+ends[j]-start);start=ends[j]+1;
        }
        policy.withObject("nmdScope").putArray("relevantExonOrdinals").add(1).add(2).add(3).add(4).add(5);
        policy.withObject("nmdScope").putArray("engineeringSupportedExonOrdinals").add(1).add(2).add(3).add(4);
        policy.withObject("escapeScope").withArray("exonAssessments").addObject().put("ordinal",5)
                .put("biologicalRelevance","PRESENT").put("populationLof","NOT_FREQUENT")
                .put("basis","TEST_ONLY exact terminal exon facts retained after topology split")
                .putArray("sourceIds").add("synthetic-source");
        String strength = "STRONG";
        if (escapeProofCaps.equals("PARENT_CAP") || escapeProofCaps.equals("BOTH_CAPS")) {
            policy.put("appliedStrength","MODERATE");strength="MODERATE";
        }
        if (escapeProofCaps.equals("LOCAL_CAP")) {
            policy.withObject("spliceScope").put("maximumStrength","MODERATE");
            outcome.withObject("review").put("maximumStrength","SUPPORTING");strength="SUPPORTING";
        } else if (escapeProofCaps.equals("BOTH_CAPS")) {
            policy.withObject("spliceScope").put("maximumStrength","SUPPORTING");
            outcome.withObject("review").put("maximumStrength","STRONG");strength="SUPPORTING";
        }
        outcome.put("transcriptModelSha256",fixture.canonical(model));
        fixture.expected.get(i).put("status","MET").put("strength",strength);
        if ("NEGATIVE".equals(escapeProofFailure)) {
            for (var exon:policy.at("/escapeScope/exonAssessments")) ((ObjectNode)exon).put("populationLof","FREQUENT");
            fixture.expected.get(i).put("status","NOT_MET").put("strength","NONE").put("reasonCode","PVS1_ESCAPE_EXON_LOF_TOLERANT");
        } else if ("NO_REVIEWED_MATCH".equals(escapeProofFailure)) {
            ((ObjectNode)policy.at("/escapeScope/rules/0")).put("start",1).put("end",2);
            fixture.expected.get(i).put("status","NOT_EVALUATED").put("strength","NONE").put("reasonCode","PVS1_ESCAPE_NO_REVIEWED_RULE_MATCH");
        } else if ("MISSING_SCOPE".equals(escapeProofFailure)) {
            policy.remove("escapeScope");
            fixture.expected.get(i).put("status","NOT_EVALUATED").put("strength","NONE").put("reasonCode","PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED");
        }
        return outcome;
    }
    private void writeBundle() throws Exception {
        Path file = temporary.resolve("test-only-pvs1-bundle.json");byte[] raw = base.mapper.writeValueAsBytes(document);Files.write(file, raw);
        env.withProperty("variant-analysis.acmg-business.pvs1-bundle-file", Objects.requireNonNull(file.toString()))
                .withProperty("variant-analysis.acmg-business.pvs1-bundle-file-sha256", Objects.requireNonNull(sha(raw)))
                .withProperty("variant-analysis.acmg-business.pvs1-bundle-sha256", Objects.requireNonNull(fixture.canonical(document)));
        artifact("configured-bundle-" + bundles.size(), document);
    }
    private void wire() {
        base.service = new AcmgBusinessConfig().acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, env);
        var configuredService = Objects.requireNonNull(base.service);
        Object rules = ReflectionTestUtils.getField(configuredService, "reviewedRules");
        if (!(rules instanceof ReviewedRuleBundle configuredBundle)) throw new AssertionError("Missing PVS1 bundle");
        bundle = configuredBundle;bundles.add(bundle);
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
        var request = base.request.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.PVS1_DECLARATION);var refs = request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var r = options.at("/candidates/" + i + "/recommendation");var rel = r.path("relationships").path(0);var annotation = r.path("annotations").path(0);
            assertTrue(annotation.has("ruleContextReady") ? annotation.path("ruleContextReady").asBoolean() : annotation.path("contextSelectable").asBoolean(), options.toPrettyString());assertFalse(rel.isMissingNode(), options.toPrettyString());
            var item = (ObjectNode) request.path("hypotheses").get(i);var hyp = (ObjectNode) item.path("hypothesis");
            hyp.put("hypothesisId", r.path("hypothesisId").asText());for (String f : List.of("hgncId", "mondoId", "moi")) hyp.set(f, rel.path(f));item.put("affectedStatus", "UNKNOWN");
            var tx = (ObjectNode) item.path("transcript");String versioned = annotation.path("versionedTranscriptId").asText();int dot = versioned.lastIndexOf('.');
            tx.put("accession", versioned.substring(0, dot)).put("version", Integer.parseInt(versioned.substring(dot + 1)))
                    .put("selectionPolicy", annotation.path("ruleContextSelectionPolicy").asText(r.path("policyId").asText() + "@" + r.path("policyVersion").asText())).put("selectionReason", "TEST_ONLY explicit context confirmation, not scientific approval");
            tx.set("annotationSource", annotation.path("annotationSource"));tx.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            ((ObjectNode) tx.path("source")).put("source", "TEST_ONLY_NMD_SELECTION").put("recordLocator", "recommendations/" + r.path("recommendationId").asText() + "/selection");
            var ref = refs.addObject();for (String f : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(f, r.path(f));
            ref.set("stableRelationshipKey", rel.path("stableRelationshipKey"));ref.set("annotationId", annotation.path("annotationId"));
        }return request;
    }
    private ObjectNode counts() {
        return base.mapper.createObjectNode().put("coordinator", coordinator.get()).put("oldHnf1aSupplier", legacy.get()).put("otherSuppliers", forbidden.get())
                .put("runtime", bundles.stream().mapToInt(b -> Objects.requireNonNull(b).pvs1RuntimeCalls()).sum())
                .put("projection", bundles.stream().mapToInt(b -> Objects.requireNonNull(b).pvs1ProjectionCalls()).sum())
                .put("evaluator", bundles.stream().mapToInt(b -> Objects.requireNonNull(b).pvs1EvaluatorCalls()).sum()).put("referenceReadiness", reference.get())
                .put("syntheticAnnotationPublicationTransport", annotationPublications.get());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configToControllerFullSaveRebuildGetAndReuse(boolean frameshift) throws Exception {
        saveRebuildAndReuse(frameshift, false);
    }
    private boolean extendedForbid;
    private final AtomicInteger extendedFactories = new AtomicInteger();
    private void wireExtended() throws Exception {
        var reviews = Files.createDirectories(temporary.resolve("extended-reviews"));
        var bp7 = new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,
                new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                (a,r) -> { throw new AssertionError("No business at catalog capture"); }).captureCatalog());
        base.service = AcmgBusinessService.localBatch(base.service, effects -> new LocalEvidenceBatchRuntime(base.mapper,
                base.mapper.createObjectNode().put("synthetic", "nine-module-integration"), null, null, null, null, bp7,
                () -> { throw new IllegalArgumentException("PM4_TEST_RESOURCE_UNAVAILABLE"); },
                () -> { assertFalse(extendedForbid); extendedFactories.incrementAndGet(); return new Pvs1SelectedBatch(base.mapper, bundle); },
                () -> { throw new IllegalArgumentException("BP3_TEST_RESOURCE_UNAVAILABLE"); }), reviews);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    @ParameterizedTest @ValueSource(strings={"STOP", "FRAMESHIFT", "SPLICE_NMD", "SPLICE_ESCAPE"})
    void extendedBatchReusesActualSelectedCoreAndDurableResults(String mode) throws Exception {
        spliceMode = mode.startsWith("SPLICE"); escapeMode = mode.endsWith("ESCAPE");
        prepare("FRAMESHIFT".equals(mode)); enableLocalModels(); enableSelectedKnowledge(true); wireExtended();
        var input = options(); assertEquals(9, input.path("enabledCriteria").size()); assertEquals(0, extendedFactories.get());
        var request = confirmed(input).put("declarationVersion", AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        var formal = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var saved = base.postRequest(request);
        for (int i=0;i<2;i++) {
            var attempt = saved.at("/candidates/"+i+"/attempts/0");
            assertFalse(attempt.has("pvs1"), "No legacy second PVS1 execution");
            var result = attempt.at("/localBatch/criteria/6");
            assertEquals("ACMG_AMP_2015:PVS1", result.path("criterionId").asText());
            assertEquals("MET", result.path("status").asText(), saved.toPrettyString());
            assertEquals(fixture.expected.get(i).path("strength"), result.path("appliedStrength"));
            assertEquals(fixture.expected.get(i).path("ptcEnd"), result.at("/details/pvs1Facts/ptcEnd"));
            assertEquals("READY", result.at("/details/pvs1Facts/knowledgeStatus").asText());
            assertFalse(saved.toString().contains("alteredCdsSequence"));
            assertEquals("evidence-context-v3", attempt.at("/context/schemaVersion").asText());
        }
        assertEquals(1, extendedFactories.get()); assertEquals(0, legacy.get());
        assertEquals(formal, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,10)));
        var store = base.store.run(base.analysisId,saved.path("runId").asText()).orElseThrow();
        assertTrue(store.privateJson().contains("sourceProjection"));
        var before = counts(); assertEquals(2, before.path("evaluator").asInt());
        extendedForbid = true;
        assertEquals(saved, base.postRequest(request)); base.closeDatabase(); base.recreateRepositories(); wire(); wireExtended();
        assertEquals(saved, base.service.read(base.analysisId,saved.path("runId").asText()));
        assertEquals(saved, base.postRequest(request)); assertEquals(before, counts()); assertEquals(1, extendedFactories.get());
        var reopened = base.store.run(base.analysisId,saved.path("runId").asText()).orElseThrow();
        assertEquals(store.responseJson(),reopened.responseJson()); assertEquals(store.privateJson(),reopened.privateJson());
        String output=System.getProperty("varvis.eleven.artifacts");
        if(output!=null) {
            Path dir=Files.createDirectories(Path.of(output).resolve("pvs1-"+mode));
            Files.writeString(dir.resolve("saved-public.json"),store.responseJson());Files.writeString(dir.resolve("saved-private.json"),store.privateJson());
            Files.writeString(dir.resolve("reopened.json"),reopened.responseJson());Files.writeString(dir.resolve("request.json"),store.requestJson());
            Files.writeString(dir.resolve("counts.json"),counts().toString());
        }
    }
    @ParameterizedTest @ValueSource(strings={"INFRAME", "NEGATIVE", "TOPOLOGY", "COMPOSED"})
    void newSourceQualifiedSpliceMethodsReachPvs1OnlyBatchAndDurableHistory(String mode) throws Exception {
        newSpliceMode=mode;spliceMode=true;
        prepare(false);enableLocalModels();enableSelectedKnowledge(true);
        var single=confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.PVS1_DECLARATION);
        var one=submitRound3(single);
        wireExtended();
        var request=confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        var all=submitRound3(request);
        for(int i=0;i<2;i++) {
            var direct=one.at("/candidates/"+i+"/attempts/0/pvs1");var second=spliceCriterion(all,i);
            assertEquals(fixture.expected.get(i).path("status"),direct.at("/evaluation/evaluationResult/status"),one::toPrettyString);
            assertEquals(fixture.expected.get(i).path("strength"),direct.at("/evaluation/appliedStrength"));
            assertEquals(direct.at("/evaluation/evaluationResult/status"),second.path("status"));assertEquals(direct.at("/evaluation/appliedStrength"),second.path("appliedStrength"));
            var facts=second.at("/details/pvs1Facts");assertEquals("READY",facts.path("knowledgeStatus").asText(),all::toPrettyString);
            assertEquals(Pvs1SelectedBatch.SPLICE_VERSION,facts.path("methodVersion").asText());
            if(mode.equals("INFRAME")) assertEquals(4,facts.at("/inFrameAssessment/deletedProteinResidues").asInt());
            if(mode.equals("TOPOLOGY")) assertEquals(4,facts.at("/spliceAssessment/alteredTopology/exonCount").asInt());
            if(mode.equals("COMPOSED")) assertTrue(facts.path("codingEditComposition").isObject());
        }
        assertFalse(all.toString().contains("alteredCdsSequence"));
        var stored=base.store.run(base.analysisId,all.path("runId").asText()).orElseThrow();
        assertTrue(stored.privateJson().contains("spliceConsequenceMaterial"));
        assertTrue(stored.privateJson().contains("sourceProjection"));
        Path readback=Files.createDirectory(temporary.resolve("round3-readback"));
        Files.writeString(readback.resolve("expected-public.json"),stored.responseJson());Files.writeString(readback.resolve("expected-private.json"),stored.privateJson());
        var before=counts();extendedForbid=true;
        base.closeDatabase();
        String executable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        if(!Files.exists(Path.of(executable)))executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                Pvs1SpliceHistoryReader.class.getName(),temporary.toString(),base.analysisId,all.path("runId").asText(),readback.toString())
                .redirectErrorStream(true).redirectOutput(readback.resolve("reader.log").toFile()).start();
        boolean ended=child.waitFor(45,java.util.concurrent.TimeUnit.SECONDS);if(!ended)child.destroyForcibly();
        assertTrue(ended,"Independent history reader timed out");assertEquals(0,child.exitValue(),Files.readString(readback.resolve("reader.log")));
        assertEquals(stored.responseJson(),Files.readString(readback.resolve("full-export.json")));
        base.recreateRepositories();wire();wireExtended();
        var get=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,all.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(all,get);assertEquals(all,base.postRequest(request));assertEquals(before,counts());
        artifact("round3-single",one);artifact("round3-all",all);artifact("round3-counts",before);
    }
    @ParameterizedTest @ValueSource(strings={"UNCAPPED","PARENT_CAP","LOCAL_CAP","BOTH_CAPS"})
    void sourceQualifiedSpliceEscapeProofUsesActualRuleAndCapsAcrossBusinessSaveAndAnotherJvm(String caps) throws Exception {
        newSpliceMode="SPLICE_ESCAPE";spliceMode=true;escapeProofCaps=caps;
        prepare(false);enableLocalModels();enableSelectedKnowledge(true);
        var single=confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.PVS1_DECLARATION);
        var one=submitRound3(single);
        for(int i=0;i<2;i++) {
            var raw=one.at("/candidates/"+i+"/attempts/0/pvs1");
            assertRound4EscapeProof(Pvs1SelectedBatch.publicSummary(base.mapper,raw),raw,i);
        }
        wireExtended();
        var request=confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        var all=submitRound3(request);
        var stored=base.store.run(base.analysisId,all.path("runId").asText()).orElseThrow();
        var privateResult=base.mapper.readTree(stored.privateJson());
        for(int i=0;i<2;i++) {
            var result=spliceCriterion(all,i);var facts=result.at("/details/pvs1Facts");
            var raw=privateResult.at("/localBatch/modules/pvs1/results/"+i);
            assertEquals("MET",result.path("status").asText(),all::toPrettyString);
            assertEquals(fixture.expected.get(i).path("strength"),result.path("appliedStrength"));
            assertRound4EscapeProof(facts,raw,i);
        }
        assertFalse(all.toString().contains("alteredCdsSequence"));
        assertFalse(all.toString().contains("cdsSequence"));
        Path readback=Files.createDirectory(temporary.resolve("round4-escape-readback"));
        Files.writeString(readback.resolve("expected-public.json"),stored.responseJson());
        Files.writeString(readback.resolve("expected-private.json"),stored.privateJson());
        var before=counts();extendedForbid=true;base.closeDatabase();
        String executable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        if(!Files.exists(Path.of(executable)))executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                Pvs1SpliceHistoryReader.class.getName(),temporary.toString(),base.analysisId,all.path("runId").asText(),readback.toString())
                .redirectErrorStream(true).redirectOutput(readback.resolve("reader.log").toFile()).start();
        boolean ended=child.waitFor(45,java.util.concurrent.TimeUnit.SECONDS);if(!ended)child.destroyForcibly();
        assertTrue(ended,"Independent escape-proof history reader timed out");
        assertEquals(0,child.exitValue(),Files.readString(readback.resolve("reader.log")));
        assertEquals(stored.responseJson(),Files.readString(readback.resolve("full-export.json")));
        var exported=base.mapper.readTree(Files.readString(readback.resolve("full-export.json")));
        for(int i=0;i<2;i++) assertEquals(spliceCriterion(all,i).at("/details/pvs1Facts/escapeAdoptionProof"),
                spliceCriterion(exported,i).at("/details/pvs1Facts/escapeAdoptionProof"));
        base.recreateRepositories();wire();wireExtended();
        assertEquals(all,base.service.read(base.analysisId,all.path("runId").asText()));
        assertEquals(all,base.postRequest(request));assertEquals(before,counts());
        artifact("round4-escape-single",one);artifact("round4-escape-all",all);artifact("round4-escape-counts",before);
        artifact("round4-escape-independent-expected",base.mapper.valueToTree(fixture.expected));
    }
    private void assertRound4EscapeProof(JsonNode facts,JsonNode raw,int index) {
        assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);
        assertEquals(fixture.expected.get(index).path("strength"),raw.at("/evaluation/appliedStrength"));
        assertEquals("NMD_NOT_PREDICTED",raw.at("/resolvedInput/nmdPrediction").asText());
        assertEquals(49,raw.at("/resolvedInput/ptcStart").asInt());assertEquals(51,raw.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(12,raw.at("/resolvedInput/distanceToLastJunction").asInt());
        assertTrue(raw.at("/resolvedInput/spliceAssessment/ptcCrossesJunction").asBoolean());
        assertEquals(Pvs1SelectedBatch.SPLICE_VERSION,facts.path("methodVersion").asText());
        var proof=facts.path("escapeAdoptionProof");var rule=raw.at("/resolvedInput/escapeAssessment/selectedRule");
        assertEquals("pvs1-escape-adoption-proof-v1",proof.path("schemaVersion").asText(),facts::toPrettyString);
        assertEquals("1.0.0",proof.path("methodVersion").asText());assertEquals("AVAILABLE",proof.path("status").asText(),proof::toPrettyString);
        for(String field:List.of("ruleId","predicate","strength","sourceIds","start","end","minimumAffectedResidues"))
            assertEquals(rule.path(field),proof.path("selectedRule").path(field),field);
        assertEquals("STRONG",proof.at("/strengthDerivation/sourceRuleStrength").asText());
        assertEquals(raw.at("/policy/appliedStrength"),proof.at("/strengthDerivation/parentPolicyCap"));
        assertEquals(raw.at("/policy/spliceScope/maximumStrength"),proof.at("/strengthDerivation/spliceScopeCap"));
        assertEquals(raw.at("/spliceConsequenceMaterial/review/maximumStrength"),proof.at("/strengthDerivation/outcomeReviewCap"));
        assertEquals(raw.at("/resolvedInput/spliceAssessment/maximumStrength"),proof.at("/strengthDerivation/localMaximumStrength"));
        assertEquals(raw.at("/evaluation/appliedStrength"),proof.at("/strengthDerivation/adoptedStrength"));
        assertEquals(raw.path("policySha256"),proof.at("/bindings/policySha256"));
        assertEquals(fixture.canonical(rule),proof.at("/bindings/selectedRuleSha256").asText());
        assertEquals(fixture.canonical(raw.at("/policy/escapeScope")),proof.at("/bindings/escapeScopeSha256").asText());
        assertEquals(fixture.canonical(raw.path("resolvedInput")),proof.at("/bindings/resolvedInputSha256").asText());
        assertEquals(fixture.canonical(raw.path("policySelection")),proof.at("/bindings/selectionSha256").asText());
        assertEquals(fixture.canonical(raw.path("evaluation")),proof.at("/bindings/evaluationSha256").asText());
        assertEquals(raw.at("/evaluation/evaluationId"),proof.at("/bindings/evaluationId"));
        assertEquals(raw.at("/transcriptModelBinding/canonicalSha256"),proof.at("/bindings/transcriptModelSha256"));
        assertEquals(fixture.canonical(raw.path("spliceConsequenceMaterial")),proof.at("/bindings/spliceMaterialSha256").asText());
        assertEquals(raw.at("/policy/policyId"),proof.at("/policy/policyId"));
        assertEquals(raw.at("/policy/escapeScope/reviewId"),proof.at("/review/reviewId"));
        assertEquals("MATCH",proof.at("/ruleCheck/status").asText());
        assertEquals(raw.at("/resolvedInput/escapeAssessment/ruleChecks/0/affectedResiduesInRegion"),proof.at("/ruleCheck/affectedResiduesInRegion"));
        for(String field:List.of("hgncId","mondoId","moi","assembly","variantType"))
            assertEquals(raw.path("policy").path("scope").path(field),proof.path("scope").path(field),field);
        assertEquals(raw.at("/resolvedInput/selectedTranscript"),proof.at("/scope/transcript"));
        assertEquals(raw.at("/policySelection/outcome"),proof.at("/scope/selectionOutcome"));
        assertFalse(proof.path("sources").isEmpty());
        var source=raw.at("/policy/scientificSources/0");
        for(String field:List.of("sourceId","url","sha256"))assertEquals(source.path(field),proof.at("/sources/0").path(field),field);
        assertFalse(proof.path("scientificApprovalCreated").asBoolean(true));assertFalse(proof.path("rescoringPerformed").asBoolean(true));
    }
    @ParameterizedTest @ValueSource(strings={"NEGATIVE","NO_REVIEWED_MATCH","MISSING_SCOPE"})
    void sourceQualifiedSpliceEscapeNonadoptionKeepsTheActualDecisionAndSpecificGap(String failure) throws Exception {
        newSpliceMode="SPLICE_ESCAPE";spliceMode=true;escapeProofFailure=failure;
        prepare(false);enableLocalModels();enableSelectedKnowledge(true);
        var one=submitRound3(confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.PVS1_DECLARATION));
        wireExtended();var request=confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        var all=submitRound3(request);
        for(int i=0;i<2;i++) {
            var raw=one.at("/candidates/"+i+"/attempts/0/pvs1");var actual=spliceCriterion(all,i);var expected=fixture.expected.get(i);
            assertEquals(expected.path("status"),raw.at("/evaluation/evaluationResult/status"),raw::toPrettyString);
            assertEquals(expected.path("reasonCode"),raw.at("/evaluation/evaluationResult/reasonCode"));
            assertEquals(expected.path("status"),actual.path("status"));assertEquals(expected.path("strength"),actual.path("appliedStrength"));
            assertEquals(expected.path("reasonCode"),actual.path("reasonCode"));
            var proof=actual.at("/details/pvs1Facts/escapeAdoptionProof");var directFacts=Pvs1SelectedBatch.publicSummary(base.mapper,raw);
            if(failure.equals("MISSING_SCOPE")) {
                // The scope gate returned before escape execution; preserve that gap without inventing an adopted-method witness.
                assertTrue(proof.isMissingNode());assertFalse(directFacts.has("escapeAdoptionProof"));
                assertFalse(raw.path("resolvedInput").has("nmdPrediction"));
            } else {
                assertEquals("NOT_ADOPTED",proof.path("status").asText(),proof::toPrettyString);
                assertFalse(proof.has("selectedRule"));assertFalse(proof.has("strengthDerivation"));
                assertEquals("NOT_ADOPTED",directFacts.at("/escapeAdoptionProof/status").asText());
            }
        }
        var before=counts();extendedForbid=true;base.closeDatabase();base.recreateRepositories();wire();wireExtended();
        assertEquals(all,base.service.read(base.analysisId,all.path("runId").asText()));
        assertEquals(all,base.postRequest(request));assertEquals(before,counts());
        artifact("round4-escape-nonadoption-single",one);artifact("round4-escape-nonadoption-all",all);
    }
    @ParameterizedTest @ValueSource(strings={"RULE","RULE_SOURCE","PARENT_CAP","LOCAL_CAP"})
    void sourceQualifiedSpliceEscapeCannotBorrowOriginalQualificationForChangedRulesSourcesOrCaps(String mutation) throws Exception {
        newSpliceMode="SPLICE_ESCAPE";spliceMode=true;prepare(false);enableLocalModels();enableSelectedKnowledge(true);
        var policy=(ObjectNode)document.at("/policies/0");
        switch(mutation) {
            case "RULE" -> ((ObjectNode)policy.at("/escapeScope/rules/0")).put("ruleId","UNQUALIFIED_REPLACEMENT_RULE");
            case "RULE_SOURCE" -> ((ObjectNode)policy.at("/escapeScope/rules/0")).putArray("sourceIds").add("UNQUALIFIED_SOURCE");
            case "PARENT_CAP" -> policy.put("appliedStrength","SUPPORTING");
            case "LOCAL_CAP" -> policy.withObject("spliceScope").put("maximumStrength","SUPPORTING");
            default -> throw new AssertionError(mutation);
        }
        ((ObjectNode)document.at("/catalog/entries/0/policyBinding")).put("policySha256",fixture.canonical(policy));
        document.put("catalogSha256",fixture.canonical(document.path("catalog")));writeBundle();wire();
        var saved=submitRound3(confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.PVS1_DECLARATION));
        var refused=saved.at("/candidates/0/attempts/0/pvs1");
        var status=refused.has("evaluation")?refused.at("/evaluation/evaluationResult"):refused.path("evaluationResult");
        assertEquals("NOT_EVALUATED",status.path("status").asText(),saved::toPrettyString);
        assertEquals("PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH",status.path("reasonCode").asText(),refused::toPrettyString);
        assertFalse(Pvs1SelectedBatch.publicSummary(base.mapper,refused).at("/escapeAdoptionProof/status").asText().equals("AVAILABLE"));
        assertRound4EscapeProof(Pvs1SelectedBatch.publicSummary(base.mapper,saved.at("/candidates/1/attempts/0/pvs1")),
                saved.at("/candidates/1/attempts/0/pvs1"),1);
        artifact("round4-escape-source-refusal",saved);
    }
    @ParameterizedTest @ValueSource(strings={"REPLACED_REVIEW","REPLACED_SOURCE","MODEL_PIN","DUPLICATE","OTHER_ALLELE"})
    void sourceQualifiedBundleDoesNotBorrowApprovalForAnAlteredRuntimeOutcome(String mutation) throws Exception {
        newSpliceMode="INFRAME";spliceMode=true;prepare(false);enableLocalModels();enableSelectedKnowledge(true);
        var actual=(ObjectNode)document.at("/materials/pvs1SpliceOutcomes/0");String reason;
        switch(mutation) {
            case "REPLACED_REVIEW" -> {actual.withObject("review").put("reviewId","OTHER_UNQUALIFIED_REVIEW");reason="PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_UNBOUND";}
            case "REPLACED_SOURCE" -> {((ObjectNode)actual.at("/sourceRecords/0")).put("sha256","0".repeat(64));reason="PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_UNBOUND";}
            case "MODEL_PIN" -> {actual.put("transcriptModelSha256","0".repeat(64));reason="PVS1_SPLICE_MODEL_IDENTITY_MISMATCH";}
            case "DUPLICATE" -> {document.withObject("materials").withArray("pvs1SpliceOutcomes").add(actual.deepCopy().put("outcomeId","OTHER"));reason="PVS1_SPLICE_OUTCOMES_AMBIGUOUS";}
            case "OTHER_ALLELE" -> {actual.withObject("allele").put("position",actual.at("/allele/position").asInt()+1);reason="PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED";}
            default -> throw new AssertionError(mutation);
        }
        writeBundle();wire();var saved=submitRound3(confirmed(options()).put("declarationVersion",AcmgBusinessRuleScope.PVS1_DECLARATION));
        var refused=saved.at("/candidates/0/attempts/0/pvs1");var status=refused.has("evaluation")?refused.at("/evaluation/evaluationResult"):refused.path("evaluationResult");
        assertEquals("NOT_EVALUATED",status.path("status").asText(),saved::toPrettyString);assertEquals(reason,status.path("reasonCode").asText(),refused::toPrettyString);
        assertEquals("MET",saved.at("/candidates/1/attempts/0/pvs1/evaluation/evaluationResult/status").asText(),saved::toPrettyString);
        artifact("round3-source-refusal",saved);
    }
    private ObjectNode submitRound3(ObjectNode request) throws Exception {
        var response=base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId)
                .contentType(java.util.Objects.requireNonNull(org.springframework.http.MediaType.APPLICATION_JSON)).content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        return (ObjectNode)base.mapper.readTree(response.getContentAsByteArray());
    }
    private JsonNode spliceCriterion(JsonNode saved,int candidate) {
        for(var criterion:saved.at("/candidates/"+candidate+"/attempts/0/localBatch/criteria"))
            if(Pvs1SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText()))return criterion;
        throw new AssertionError(saved.toPrettyString());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void locallySuppliedModelsReachCoreSaveRebuildGetAndReuse(boolean frameshift) throws Exception {
        saveRebuildAndReuse(frameshift, true);
    }
    private void saveRebuildAndReuse(boolean frameshift, boolean localSupply) throws Exception {
        saveRebuildAndReuse(frameshift, localSupply, false);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedTopNKnowledgeReachesNmdAndDurableReuse(boolean frameshift) throws Exception {
        saveRebuildAndReuse(frameshift, true, true);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void positionalPoliciesReachSelectedTopNAndDurableReuse(boolean frameshift) throws Exception {
        saveRebuildAndReuse(frameshift, true, true, true);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reviewedOriginalSourcesReachTopNWithoutInventingG2pDiseaseMappings(boolean frameshift) throws Exception {
        policySourceMode = true;
        saveRebuildAndReuse(frameshift, true, true);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reviewedEscapePoliciesReachSelectedTopNAndDurableReuse(boolean frameshift) throws Exception {
        escapeMode=true;
        saveRebuildAndReuse(frameshift,true,true);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reviewedSpliceOutcomesReachSelectedTopNSaveAndDurableReuse(boolean escape) throws Exception {
        spliceMode = true;escapeMode = escape;
        saveRebuildAndReuse(false, true, true);
    }
    private void saveRebuildAndReuse(boolean frameshift, boolean localSupply, boolean selected) throws Exception {
        saveRebuildAndReuse(frameshift, localSupply, selected, false);
    }
    private void saveRebuildAndReuse(boolean frameshift, boolean localSupply, boolean selected, boolean positional) throws Exception {
        prepare(frameshift);
        if (positional) {
            for (var p : fixture.policies) {
                p.put("schemaVersion", "pvs1-truncating-nmd-policy-v2").put("policyVersion", "2");
                var c=p.withObject("nmdScope").putArray("positionConditions").addObject()
                        .put("conditionId", "TEST_ONLY_PTC_RANGE").put("coordinate", "ALTERED_PTC_SPAN")
                        .put("minimumInclusive", 1).put("maximumExclusive", 300).put("basis", "TEST_ONLY known toy transcript interval");
                c.putArray("sourceIds").add("synthetic-source");
            }
            document=fixture.document();writeBundle();wire();
        }
        if (localSupply) enableLocalModels();
        if (selected) enableSelectedKnowledge(true);
        assertEquals(spliceMode?"1.3.0":escapeMode?"1.2.0":"1.1.0",bundle.identity().at("/algorithms/"+TruncatingNmdPvs1Runtime.EVALUATOR_ID).asText());
        var formalBefore = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2));
        var options = options();var request = confirmed(options);var before = counts();
        artifact("input-options", options);artifact("request", request);var saved = base.postRequest(request);artifact("submit", saved);
        assertEquals("COMPLETED", saved.path("status").asText(), saved.toPrettyString());
        assertEquals(List.of(TruncatingNmdPvs1Runtime.CRITERION), base.mapper.convertValue(options.path("enabledCriteria"), List.class));
        for (int i = 0; i < 2; i++) {
            var p = saved.at("/candidates/" + i + "/attempts/0/pvs1");var e = p.path("evaluation");var expected = fixture.expected.get(i);
            assertEquals("MET", e.at("/evaluationResult/status").asText(),p.toPrettyString());assertEquals(expected.path("strength"),e.path("appliedStrength"));
            for (String f : List.of("ptcStart", "ptcEnd", "distanceToLastJunction")) assertEquals(expected.path(f), p.path("resolvedInput").path(f), f);
            if (frameshift) assertEquals(expected.path("lastJunction"), p.at("/resolvedInput/mutantLastJunctionCdsPosition"));
            assertEquals(expected.path("transcript"), p.at("/resolvedInput/selectedTranscript"));
            assertEquals(spliceMode ? "1.3.0" : escapeMode ? "1.2.0" : positional ? "1.1.0" : "1.0.4", e.at("/deterministicTrace/evaluatorVersion").asText());
            if (spliceMode) {
                assertEquals(fixture.spliceOutcomes.get(i), p.path("spliceConsequenceMaterial"));
                assertEquals(i == 0 ? "PREDICTED_CANONICAL" : "OBSERVED_RNA", p.at("/resolvedInput/spliceAssessment/evidenceKind").asText());
            }
            if(escapeMode) assertEquals("NMD_NOT_PREDICTED",p.at("/resolvedInput/nmdPrediction").asText());
            if (positional) assertEquals("PASS", p.at("/resolvedInput/positionConditionChecks/0/status").asText());
            assertEquals(fixture.canonical(fixture.policies.get(i)), p.path("policySha256").asText());
            assertEquals(fixture.canonical(fixture.models.get(i)), p.at("/transcriptModelBinding/canonicalSha256").asText());
            assertTrue(saved.at("/candidates/" + i + "/enabledRuleResultsComplete").asBoolean());
            if (localSupply) {
                assertEquals("READY", p.at("/transcriptMaterialSupply/status").asText());
                assertFalse(p.at("/transcriptMaterialSupply/clinicalAcceptance").asBoolean(true));
                assertTrue(p.at("/transcriptMaterialSupply/structure/referenceBasesRead").asInt() > 0);
            } else assertFalse(p.has("transcriptMaterialSupply"));
            if (selected) {
                assertEquals("READY", p.at("/knowledgeAssessment/status").asText());
                if (policySourceMode) assertEquals("NO_EXACT_BACKGROUND", p.at("/knowledgeAssessment/g2pConsistency").asText());
                assertEquals("evidence-context-v3", saved.at("/candidates/" + i + "/attempts/0/selectedContext/schemaVersion").asText());
                assertEquals("evidence-context-v2", saved.at("/candidates/" + i + "/attempts/0/context/schemaVersion").asText());
                assertTrue(p.path("selectedContextProjectionSha256").asText().matches("[0-9a-f]{64}"));
            }
        }
        var after = counts();assertEquals(2, after.path("runtime").asInt());assertEquals(2, after.path("projection").asInt());assertEquals(2, after.path("evaluator").asInt());
        artifact("saved-store", base.mapper.readTree(base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().responseJson()));
        if (localSupply) base.closeDatabase();
        base.recreateRepositories();wire();var afterRebuild = counts();assertEquals(after, afterRebuild);
        var getResult = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, getResult);var afterGet = counts();var reuse = base.postRequest(request);assertEquals(saved, reuse);assertEquals(after, counts());
        assertEquals(1, base.store.runs(base.analysisId).size());artifact("get-after-store-service-rebuild", getResult);artifact("reuse", reuse);
        assertEquals(formalBefore, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2)));
        if (selected) {
            var privateResult = base.mapper.readTree(base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().privateJson());
            assertEquals(2, privateResult.path("pvs1ContextProjections").size());
            assertFalse(privateResult.path("sourceReceipts").isEmpty());
            for (var projection : privateResult.path("pvs1ContextProjections")) {
                var normalized = projection.path("originalContext").deepCopy();
                ((ObjectNode) normalized).set("schemaVersion", projection.at("/evaluationContext/schemaVersion"));
                ((ObjectNode) normalized).set("contextId", projection.at("/evaluationContext/contextId"));
                assertEquals(normalized, projection.path("evaluationContext"));
            }
        }
        ObjectNode c = base.mapper.createObjectNode();c.set("beforeSubmit", before);c.set("afterSave", after);c.set("afterStoreServiceRebuild", afterRebuild);c.set("afterGet", afterGet);c.set("afterReuse", counts());artifact("observed-counts", c);
        String supplyArtifacts = System.getProperty("varvis.pvs1.supply.artifacts");
        if (localSupply && supplyArtifacts != null) {
            Path out = Path.of(supplyArtifacts).resolve((selected ? "selected-" : "") + (frameshift ? "frameshift" : "nonsense")); Files.createDirectories(out);
            for (var entry : Map.of("saved", saved, "get", getResult, "reuse", reuse, "counts", c,
                    "request", request, "bundle", document, "expected", base.mapper.valueToTree(fixture.expected),
                    "models", base.mapper.valueToTree(fixture.models)).entrySet())
                Files.write(out.resolve(entry.getKey() + ".json"), base.mapper.writeValueAsBytes(entry.getValue()));
            if (selected) {
                Files.writeString(out.resolve("private-store.json"), base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow().privateJson());
                Files.write(out.resolve("knowledge-source.json"), Files.readAllBytes(temporary.resolve("pvs1-source.json")));
                Files.write(out.resolve("knowledge-adoption.json"), Files.readAllBytes(temporary.resolve("pvs1-adoption.json")));
            }
        }
    }

    private void enableSelectedKnowledge(boolean approved) throws Exception {
        var facts = Pvs1KnowledgeCatalogTest.facts(base.mapper, fixture.policies);
        if (policySourceMode) {
            for (var r : facts.path("records")) {
                ((ObjectNode)r.path("sourceRecord")).put("disease MONDO", "MONDO:9999999");
                var sorted = new java.util.TreeMap<String,String>();
                r.path("sourceRecord").properties().forEach(e -> sorted.put(e.getKey(),e.getValue().asText()));
                ((ObjectNode)r).put("sourceRecordSha256",sha(base.mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(sorted)));
            }
        }
        byte[] raw = base.mapper.writeValueAsBytes(facts);
        Files.write(temporary.resolve("pvs1-source.json"), raw);
        var adoption = policySourceMode
                ? org.nbscn.varvis.variantanalysis.provider.Pvs1ReviewedSourceSupplyTest.use(base.mapper, facts, fixture.policies)
                : Pvs1KnowledgeCatalogTest.adoption(base.mapper, facts, sha(raw), fixture.policies);
        if (newSpliceMode != null) {
            adoption = Pvs1SpliceSourceQualificationTest.adoption(base.mapper, facts, fixture.policies);
            for (int i=0;i<2;i++) {
                var outcomes=List.of(fixture.spliceOutcomes.get(i));
                var originals=Pvs1SpliceSourceQualificationTest.configureOriginals(base.mapper,fixture.policies.get(i),fixture.models.get(i),outcomes);
                ((ObjectNode) adoption.at("/entries/"+i)).set("qualification",Pvs1SpliceSourceQualificationTest.qualification(base.mapper,
                        temporary.resolve("qualified-originals-"+i),fixture.policies.get(i),fixture.models.get(i),outcomes,originals));
            }
        }
        adoption.put("sourceCandidatesSha256",sha(raw));
        if (!approved) adoption.putArray("entries");
        byte[] review = base.mapper.writeValueAsBytes(adoption);
        Files.write(temporary.resolve("pvs1-adoption.json"), review);
        env.withProperty("variant-analysis.acmg-business.pvs1-selected-knowledge-enabled", "true")
                .withProperty("variant-analysis.acmg-business.pvs1-knowledge-source-file", java.util.Objects.requireNonNull(temporary.resolve("pvs1-source.json").toString()))
                .withProperty("variant-analysis.acmg-business.pvs1-knowledge-source-sha256", java.util.Objects.requireNonNull(sha(raw)))
                .withProperty("variant-analysis.acmg-business.pvs1-knowledge-adoption-file", java.util.Objects.requireNonNull(temporary.resolve("pvs1-adoption.json").toString()))
                .withProperty("variant-analysis.acmg-business.pvs1-knowledge-adoption-sha256", java.util.Objects.requireNonNull(sha(review)));
        wire();
    }

    @Test void selectedKnowledgeDraftIsSavedAsSpecificGapWithoutCoreExecution() throws Exception {
        prepare(false); enableLocalModels(); enableSelectedKnowledge(false);
        var request = confirmed(options()); var saved = base.postRequest(request);
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
        for (var row : saved.path("candidates")) {
            var p = row.at("/attempts/0/pvs1");
            assertEquals("PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING", p.at("/evaluationResult/reasonCode").asText(), saved.toPrettyString());
            assertEquals(1, p.at("/knowledgeAssessment/sourceRecords").size());
        }
        assertEquals(0, counts().path("runtime").asInt()); assertEquals(0, counts().path("evaluator").asInt());
        var before = counts(); base.closeDatabase(); base.recreateRepositories(); wire();
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        assertEquals(saved, base.postRequest(request)); assertEquals(before, counts());
    }

    @Test void selectedKnowledgeChangeInvalidatesConfirmationButKeepsSavedHistory() throws Exception {
        prepare(false); enableLocalModels(); enableSelectedKnowledge(true);
        var request = confirmed(options()); var saved = base.postRequest(request); var before = counts();
        enableSelectedKnowledge(false);
        reject(request, 409);
        // Recommendation verification may construct a coordinator; it must not acquire facts or run a core.
        var observed = counts(); before.remove("coordinator"); observed.remove("coordinator"); assertEquals(before, observed);
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        var next = base.postRequest(confirmed(options()));
        assertNotEquals(saved.path("runId"), next.path("runId"));
        assertEquals("PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING", next.at("/candidates/0/attempts/0/pvs1/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedPvs1RetainsSourceOrChangedQueryBlockerWithoutEvaluating(boolean sourceBlocked) throws Exception {
        prepare(false); enableLocalModels(); enableSelectedKnowledge(true);
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = Files.createDirectory(temporary.resolve("selected-blocker"));
        var prepared = fixture.prepare(false, sourceBlocked, true, 1, true);
        var response = base.mapper.createObjectNode(); response.set("candidates", base.mapper.valueToTree(prepared.result().assembly().candidates()));
        var before = counts();
        ReflectionTestUtils.invokeMethod(java.util.Objects.requireNonNull(base.service), "appendSelectedPvs1", prepared.result(), "a".repeat(64), response, base.mapper.createObjectNode());
        assertEquals(sourceBlocked ? "SYNTHETIC_SOURCE_CHANGED" : "QUERY_TRANSCRIPT_REANNOTATION_REQUIRED",
                response.at("/candidates/0/attempts/0/pvs1/evaluationResult/reasonCode").asText(), response.toPrettyString());
        assertEquals(1, response.path("candidates").size()); assertEquals(before, counts());
        assertEquals(prepared.before(), prepared.mapper().valueToTree(prepared.original()));
    }

    private void enableLocalModels() throws Exception {
        Path aliases = temporary.resolve("test-only-nmd-alias.tsv");
        Files.writeString(aliases, "11\tchr11\n13\tchr13\n");
        base.properties.getNormalization().setGrch38ContigAliasFile(aliases.toString());
        Path directory = temporary.resolve("local-models"); Files.createDirectories(directory);
        ObjectNode manifest = base.mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        var entries = manifest.putArray("entries");
        for (var model : fixture.models) {
            String cds = model.path("cdsSequence").asText();
            model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", sha(cds.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    .put("cdsSha256", sha(cds.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            byte[] raw = base.mapper.writeValueAsBytes(model); String tx = model.path("transcript").asText();
            Files.write(directory.resolve(tx + ".model.json"), raw);
            entries.addObject().put("transcript", tx).put("file", tx + ".model.json").put("fileBytes", raw.length)
                    .put("fileSha256", sha(raw)).put("canonicalSha256", fixture.canonical(model))
                    .put("modelId", model.path("modelId").asText()).put("modelVersion", model.path("modelVersion").asText());
        }
        byte[] raw = base.mapper.writeValueAsBytes(manifest); Files.write(directory.resolve("manifest.json"), raw);
        // TEST_ONLY material construction: bind to the final fixture model before execution.
        // Production never repairs a stale outcome hash; the core negative test rejects it.
        if (spliceMode) for (int i = 0; i < 2; i++)
            fixture.spliceOutcomes.get(i).put("transcriptModelSha256", fixture.canonical(fixture.models.get(i)));
        document = fixture.document(); document.withObject("materials").putObject("transcriptModels");
        env.withProperty("variant-analysis.acmg-business.pvs1-local-material-supply-enabled", "true")
                .withProperty("variant-analysis.acmg-business.transcript-material-directory", java.util.Objects.requireNonNull(directory.toString()))
                .withProperty("variant-analysis.acmg-business.transcript-material-manifest-sha256", java.util.Objects.requireNonNull(sha(raw)));
        writeBundle(); wire();
    }

    @Test void suppliedModelCannotReplaceCorruptEmbeddedModel() throws Exception {
        prepare(false); enableLocalModels();
        String pinned = fixture.canonical(fixture.models.get(0));
        document.withObject("/materials/transcriptModels").putObject(pinned).put("modelId", "CORRUPT");
        writeBundle(); wire();
        var refused = base.postRequest(confirmed(options())).at("/candidates/0/attempts/0/pvs1");
        assertEquals("REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE", refused.at("/evaluationResult/reasonCode").asText());
        assertFalse(refused.has("transcriptMaterialSupply"));
    }

    @Test void suppliedModelMustMatchPreexistingReviewedPolicyPin() throws Exception {
        prepare(false);
        var priorIdentity = bundle.identity(); enableLocalModels();
        assertNotEquals(priorIdentity, bundle.identity());
        assertEquals("PVS1_NMD_TRANSCRIPT_MATERIAL_SUPPLY", bundle.identity().at("/transcriptMaterialSupply/method").asText());
        // The local file is unchanged; only the synthetic reviewed policy now binds a different model.
        fixture.models.get(0).put("modelVersion", "OTHER_REVIEWED_VERSION");
        document = fixture.document(); document.withObject("materials").putObject("transcriptModels");
        writeBundle(); wire();
        var refused = base.postRequest(confirmed(options())).at("/candidates/0/attempts/0/pvs1");
        assertEquals("PVS1_SUPPLIED_MODEL_NOT_BOUND_TO_REVIEWED_POLICY", refused.at("/evaluationResult/reasonCode").asText());
        assertEquals("READY", refused.at("/transcriptMaterialSupply/status").asText());
        assertFalse(refused.has("evaluation"));
    }

    @Test void sourceMechanismCandidateDoesNotAuthorizeSuppliedModelEvaluation() throws Exception {
        prepare(false); enableLocalModels();
        fixture.policies.get(0).withObject("mechanism").put("status", "UNREVIEWED");
        document = fixture.document(); document.withObject("materials").putObject("transcriptModels");
        writeBundle(); wire();
        var refused = base.postRequest(confirmed(options())).at("/candidates/0/attempts/0/pvs1");
        assertEquals("PVS1_LOF_MECHANISM_NOT_ESTABLISHED", refused.at("/evaluationResult/reasonCode").asText());
        assertFalse(refused.has("transcriptMaterialSupply")); assertFalse(refused.has("evaluation"));
    }
    @Test void requestsCannotForgeMaterialsOrSkipConfirmationOrCrossScope() throws Exception {
        prepare(false);var request = confirmed(options());var before = counts();
        reject(request.deepCopy().put("declarationVersion", AcmgBusinessRuleScope.PM1_DECLARATION), 400);
        var no = request.deepCopy();no.remove("recommendations");reject(no, 400);
        for (String field : List.of("model", "policy", "appliedStrength", "reviewStatus")) { var forged = request.deepCopy();forged.put(field, "APPROVED");reject(forged, 400); }
        var stale = request.deepCopy();((ObjectNode) stale.at("/recommendations/0")).put("annotationId", "other");reject(stale, 409);
        assertEquals(before.path("runtime"), counts().path("runtime"));assertEquals(0, forbidden.get());assertEquals(0, legacy.get());assertTrue(base.store.runs(base.analysisId).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"MODEL_SHA", "LOF", "EXON", "ANNOTATION", "ESCAPE", "START", "AUTHORITY", "NO_POLICY", "AMBIGUOUS", "UNIMPLEMENTED", "POLICY_REVIEW", "CATALOG_SHA"})
    void singleCauseRefusalsReachTheirIntendedGate(String failure) throws Exception {
        prepare(false);String reason;
        switch (failure) {
            case "MODEL_SHA" -> { ((ObjectNode) document.at("/materials/transcriptModels").path(fixture.canonical(fixture.models.get(0)))).put("modelVersion", "TAMPERED");reason = "REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE"; }
            case "LOF" -> { fixture.policies.get(0).withObject("mechanism").put("status", "UNKNOWN");document = fixture.document();reason = "PVS1_LOF_MECHANISM_NOT_ESTABLISHED"; }
            case "EXON" -> { fixture.policies.get(0).withObject("nmdScope").putArray("sourceIds");document = fixture.document();reason = "PVS1_NMD_SCOPE_NOT_REVIEWED"; }
            case "AUTHORITY" -> { fixture.policies.get(0).putArray("authoritySourceIds");document = fixture.document();reason = "PVS1_POLICY_AUTHORITY_IDENTITY_INVALID"; }
            case "POLICY_REVIEW" -> { fixture.policies.get(0).withObject("review").put("status", "DRAFT");document = fixture.document();reason = "POLICY_NOT_REVIEWED_IMPLEMENTED"; }
            case "CATALOG_SHA" -> { reason = "CATALOG_INTEGRITY_MISMATCH"; }
            case "ESCAPE" -> { fixture.policies.get(0).withObject("nmdScope").put("minimumDistanceToLastJunction", 418);document = fixture.document();reason = "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED"; }
            case "ANNOTATION", "START" -> {
                var candidate = base.jobs.findInterpretations(base.analysisId, 0, 2).get(0);
                fixture.annotations.get(0).put(failure.equals("START") ? "consequence" : "codons", failure.equals("START") ? "start_lost" : "CGA/TGG");
                candidate.applyTranscriptAnnotations(List.of(fixture.annotation(0)));replaceCandidateAndSource(candidate);
                reason = failure.equals("START") ? "PVS1_START_LOSS_NOT_START_CODON" : "PVS1_PROTEIN_CODON_MISMATCH";
            }
            case "NO_POLICY" -> { document.withObject("catalog").putArray("entries");reason = "CLAUSE_SEARCH_SCOPE_NOT_COVERED"; }
            case "AMBIGUOUS" -> { var entries = document.withObject("catalog").withArray("entries");entries.add(((ObjectNode) entries.get(0)).deepCopy().put("entryId", "TEST_ONLY_DUPLICATE"));reason = "CLAUSE_SELECTION_CONFLICT"; }
            case "UNIMPLEMENTED" -> { ((ObjectNode) document.at("/catalog/entries/0")).put("disposition", "SPECIALIZED_UNIMPLEMENTED");reason = "APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED"; }
            default -> throw new AssertionError(failure);
        }
        document.put("catalogSha256", failure.equals("CATALOG_SHA") ? "0".repeat(64) : fixture.canonical(document.path("catalog")));writeBundle();wire();var request = confirmed(options());
        artifact("negative-request", request);var saved = base.postRequest(request);artifact("negative-submit", saved);
        var p = saved.at("/candidates/0/attempts/0/pvs1");var result = p.has("evaluation") ? p.at("/evaluation/evaluationResult") : p.path("evaluationResult");
        assertEquals("NOT_EVALUATED", result.path("status").asText(), saved.toPrettyString());assertEquals(reason, result.path("reasonCode").asText(), p.toPrettyString());
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());assertFalse(ReviewedRuleBundle.usesPreexistingClause(p));
        if (List.of("MODEL_SHA", "LOF", "EXON", "AUTHORITY", "NO_POLICY", "AMBIGUOUS", "UNIMPLEMENTED", "POLICY_REVIEW", "CATALOG_SHA").contains(failure)) assertFalse(p.has("evaluation"));
    }
    @Test void changedMaterialAndCandidateInvalidateOldConfirmationAndHistoryRemainsFrozen() throws Exception {
        prepare(false);var request = confirmed(options());var saved = base.postRequest(request);var before = counts();
        Files.writeString(temporary.resolve("test-only-pvs1-bundle.json"), "BROKEN_TEST_ONLY");
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));assertEquals(before, counts());
        assertThrows(IllegalArgumentException.class, () -> new AcmgBusinessConfig().acmgBusinessService(base.mapper, base.jobs, base.store, base.sources, base.properties, env));
        fixture.models.get(0).put("modelVersion", "2");fixture.policies.get(0).put("policyVersion", "2");document = fixture.document();writeBundle();wire();
        reject(request, 409);assertEquals(before.path("runtime"), counts().path("runtime"));
        var newRequest = confirmed(options());var changed = base.postRequest(newRequest);assertNotEquals(saved.path("runId"), changed.path("runId"));
        var candidate = base.jobs.findInterpretations(base.analysisId, 0, 2).get(0);
        candidate.applyTranscriptAnnotations(List.of(fixture.annotation(0).withAnnotationMetadata("VEP_SYNTHETIC", "COMPLETED", "116.0", java.time.Instant.parse("2026-09-27T00:00:00Z"))));
        replaceCandidateAndSource(candidate);var prior = counts();reject(newRequest, 409);assertEquals(prior.path("runtime"), counts().path("runtime"));
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));assertEquals(2, base.store.runs(base.analysisId).size());
        artifact("original-request", request);artifact("original-saved", saved);artifact("updated-request", newRequest);artifact("updated-run", changed);artifact("history", base.service.read(base.analysisId, saved.path("runId").asText()));
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
                if (name.equals("mane")) row.put("ncbiGeneId", "99900" + (i + 1)).putArray("sourceRows").add(i + 1);
                else row.put("mondoId", "MONDO:099900" + (i + 1)).put("moi", "AD").put("diseaseLabel", "TEST_ONLY fictional condition " + i)
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
            if (spliceMode) {
                var window = fixture.spliceOutcomes.get(i).path("referenceWindow");
                for (int j = 0; j < window.path("sequence").asText().length(); j++)
                    seq[window.path("start").asInt() - 1 + j] = window.path("sequence").asText().charAt(j);
            }
            String contig = i == 0 ? "chr11" : "chr13";fasta.append('>').append(contig).append('\n');
            index.append(contig).append('\t').append(seq.length).append('\t').append(fasta.length()).append('\t').append(seq.length).append('\t').append(seq.length + 1).append('\n');
            fasta.append(seq).append('\n');
        }
        Path file = temporary.resolve("test-only-nmd.fa");Files.writeString(file, fasta);Files.writeString(Path.of(file + ".fai"), index);String digest = sha(Files.readAllBytes(file));
        base.properties.getNormalization().setGrch38Reference(file.toString());base.properties.getNormalization().setGrch38ReferenceSha256(digest);base.properties.getNormalization().setGrch38ProfileId("TEST_ONLY_NMD");
        var receipt = base.mapper.createObjectNode().put("resourceId", "TEST_ONLY_NMD_FASTA").put("resourceVersion", "1").put("assembly", "GRCh38")
                .put("logicalName", "test-only-nmd.fa").put("expectedSha256", digest).put("observedSha256", digest).put("hashStatus", "VERIFIED")
                .put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true).put("byteLength", Files.size(file))
                .put("mtimeNs", Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS)).put("indexSha256", sha(Files.readAllBytes(Path.of(file + ".fai"))))
                .put("startedAt", "2026-09-27T00:00:00Z").put("completedAt", "2026-09-27T00:00:01Z").put("resourceDataClass", "SYNTHETIC");
        var ledger = base.mapper.createObjectNode().put("source", "TEST_ONLY_NMD").put("version", "1").put("dataClass", "SYNTHETIC");ledger.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        Files.write(temporary.resolve("nmd-reference-ledger.json"), base.mapper.writeValueAsBytes(ledger));
        readiness = mockConstruction(NormalizationResourceValidator.class, (mock, context) -> when(mock.validate(GenomeAssembly.GRCh38)).thenAnswer(call -> {
            reference.incrementAndGet();return new NormalizationReferenceReadiness(GenomeAssembly.GRCh38, "READY", "TEST_ONLY_NMD", "test-only-nmd.fa", digest, true, true, 2, "CHR_PREFIXED", List.of(), List.of("TEST_ONLY_TINY_FASTA_READINESS"));
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
    private Path output() throws Exception { String root = System.getProperty("varvis.pvs1.artifacts");if (root == null) return null;Path p = Path.of(root).resolve("business").resolve(scenario);Files.createDirectories(p);return p; }
    private void artifact(String name, JsonNode value) throws Exception { Path out = output();if (out != null) Files.writeString(out.resolve(name + ".json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
