package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual publication/recovery, prediction application, BP7 core and file H2.
 *  TSV, transcript geometry, reference and model transport are SYNTHETIC, not scientific validation. */
class Bp7TopNBusinessTest {
    @TempDir Path temporary;
    AcmgBusinessIntegrationTest base;
    AcmgBusinessService legacy;
    Resource catalog = new ClassPathResource("acmg/public-splicing-experiments-v1.json");
    final AtomicInteger modelCalls = new AtomicInteger(), modelUnits = new AtomicInteger(), factories = new AtomicInteger();
    boolean forbidden, enabled = true, proximal, missingPrediction;
    String score = "0.00", referenceSha;

    @BeforeEach void setup() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        reference();
        base.fixture.annotations = new LinkedHashMap<>(base.fixture.annotations);
        var parser = base.mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (var entry : base.fixture.annotations.entrySet()) {
            var annotations = new ArrayList<TranscriptConsequence>();
            for (var tx : entry.getValue()) {
                ObjectNode json = base.mapper.valueToTree(tx);
                json.put("consequence", "synonymous_variant").put("hgvsC", tx.getVersionedTranscriptId()+":c.1001T=").put("hgvsP", "p.=");
                json.remove(List.of("synonymousExonPosition", "bp7IntronicSignedOffset"));
                annotations.add(parser.treeToValue(json, TranscriptConsequence.class));
            }
            entry.setValue(annotations);
        }
        base.variantTsvOverride = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv("1\t11\t20000\tT\tA\tHBB\t3043\tAR\t0.8", "2\t13\t20000\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, "##fileformat=VCFv4.2\n##contig=<ID=chr11>\n##contig=<ID=chr13>\n"
                + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n"
                + "chr11\t20000\t.\tT\tA\t.\tPASS\t.\tGT\t.\nchr13\t20000\t.\tC\tT\t.\tPASS\t.\tGT\t.\n");
        legacy = base.service;
        base.request.put("declarationVersion", "ordinary-local-computational-batch1-v1");
        Files.createDirectories(temporary.resolve("reviews"));
        install(); confirmRecommendations();
    }
    @AfterEach void close() { if (base != null) base.closeDatabase(); }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    void reference() throws Exception {
        Path fasta=temporary.resolve("bp7-synthetic.fa"), index=Path.of(fasta+".fai");
        Files.writeString(fasta, ">chr11\n"+"T".repeat(40000)+"\n>chr13\n"+"C".repeat(40000)+"\n", StandardCharsets.US_ASCII);
        Files.writeString(index, "chr11\t40000\t7\t40000\t40001\nchr13\t40000\t40015\t40000\t40001\n");
        referenceSha=sha(Files.readAllBytes(fasta));
        var receipt=base.mapper.createObjectNode().put("resourceId","BP7_SYNTHETIC_REFERENCE").put("resourceVersion","1")
                .put("assembly","GRCh38").put("logicalName",fasta.getFileName().toString()).put("expectedSha256",referenceSha).put("observedSha256",referenceSha)
                .put("hashStatus","VERIFIED").put("hashAlgorithm","SHA-256").put("hashScope","RAW_BYTES").put("unchangedDuringHash",true)
                .put("byteLength",Files.size(fasta)).put("mtimeNs",Files.getLastModifiedTime(fasta).to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt","2026-09-01T00:00:00Z").put("completedAt","2026-09-01T00:00:01Z")
                .put("indexSha256",sha(Files.readAllBytes(index))).put("resourceDataClass","SYNTHETIC");
        var envelope=base.mapper.createObjectNode().put("source","BP7_SYNTHETIC_REFERENCE").put("version","1").put("dataClass","SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt",receipt);
        var ledger=ExplicitContextSourceLedger.create(base.mapper,envelope);base.fixture.sources.add(ledger);
        var settings=new VariantAnalysisProperties();
        settings.getNormalization().setGrch38Reference(fasta.toString());settings.getNormalization().setGrch38ReferenceSha256(referenceSha);
        settings.getNormalization().setGrch38ProfileId("BP7_SYNTHETIC");
        var validator=new NormalizationResourceValidator(settings) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly,"READY","BP7_SYNTHETIC","bp7-synthetic.fa",referenceSha,true,true,2,
                        "CHR_PREFIXED",List.of(),List.of("SYNTHETIC_TEST_READINESS"));
            }
        };
        base.producer=new NativeSnvSourceProducer(base.mapper,settings,temporary,base.fixture.sources,ledger.reference("checksum"),validator);
    }
    Bp7SelectedBatch bp7() {
        return new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,catalog,temporary.resolve("unused"),
                (a,r)->{ throw new AssertionError("Capture must not read a run"); }).captureCatalog());
    }
    void install() {
        var recommendations=new AcmgRecommendations(base.mapper,c->{throw new AssertionError("No prediction at confirmation");});
        var service=new AcmgBusinessService(base.mapper,base.jobs,base.store,base.sources,base.runtime(),
                ()->{throw new AssertionError("BA1 is outside this batch");},recommendations);
        base.service=AcmgBusinessService.localBatch(service,this::runtime,temporary.resolve("reviews"));
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    LocalEvidenceBatchRuntime runtime(Function<JsonNode,JsonNode> effects) {
        return new LocalEvidenceBatchRuntime(base.mapper,base.mapper.createObjectNode().put("fixture","BP7_SYNTHETIC"),
                null,()->new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(base.mapper),
                missingPrediction ? null : splice->{
                    assertFalse(forbidden,"Read/repeat must not construct a prediction runtime"); factories.incrementAndGet();
                    try { return new SelectedPredictionRuntime(base.mapper,
                            PredictionDevelopmentRuntime.configured(base.mapper,new AlphaMissenseProvider(new VariantAnalysisProperties())),
                            Hnf1aRevelRuntime.unavailable(base.mapper),null,null,splice,effects,null,null,nativeRuntime());
                    } catch(Exception e) { throw new IllegalStateException(e); }
                },null,enabled?bp7():null);
    }
    NativeSpliceAiRuntime nativeRuntime() throws Exception {
        var index=mock(GencodeTranscriptIndex.class);
        when(index.identity()).thenReturn(base.mapper.createObjectNode().put("method","EXACT_GENCODE_LOCAL_ANNOTATION").put("version","1.0.0")
                .put("manifestSha256","b".repeat(64)).put("sourceGtfSha256","c".repeat(64)).put("release",38));
        when(index.annotation(any())).thenAnswer(call->{
            ObjectNode c=call.getArgument(0);String tx=c.at("/transcript/accession").asText()+"."+c.at("/transcript/version").asInt();
            var selected=base.jobs.findInterpretations(base.analysisId,0,100).stream().flatMap(v->v.getTranscriptAnnotations().stream())
                    .filter(t->tx.equals(t.getVersionedTranscriptId())).findFirst().orElseThrow();
            int start=proximal?20000:19000,end=21000,length=end-start+1;
            var model=base.mapper.createObjectNode().put("transcript",tx).put("geneSymbol",selected.getGeneSymbol()).put("geneId",selected.getGeneId())
                    .put("contig",c.at("/variant/evidenceQueryAllele/contig").asText()).put("strand",1).put("assembly","GRCh38")
                    .put("hgncId",c.at("/gene/hgncId").asText()).put("rawGtfSha256","c".repeat(64)).put("cdsLength",length);
            model.putArray("exons").addObject().put("genomicStart",start).put("genomicEnd",end).put("cdsStart",1).put("cdsEnd",length);
            return model;
        });
        var doc=base.mapper.createObjectNode().put("schemaVersion",Ba1SpecificationCatalog.SCHEMA);
        doc.putObject("metadata").put("status","COMPLETE").put("entryCount",1);
        doc.putArray("entries").addObject().putObject("scope").putArray("hgncIds").add("HGNC:99999");
        Path guardFile=temporary.resolve("synthetic-spec.json");Files.write(guardFile,base.mapper.writeValueAsBytes(doc));
        var guard=PredictionSpecializationGuard.load(base.mapper,guardFile,sha(Files.readAllBytes(guardFile)));
        var runner=mock(NativeSpliceAiBatchRunner.class);
        when(runner.identity()).thenReturn(base.mapper.createObjectNode().put("dataClass","SYNTHETIC"));
        when((Duration) org.springframework.test.util.ReflectionTestUtils.invokeMethod(runner,"timeout")).thenReturn(Duration.ofSeconds(60));
        when(runner.run(any(),any())).thenAnswer(call->{
            assertFalse(forbidden);modelCalls.incrementAndGet();ObjectNode request=call.getArgument(0);
            modelUnits.addAndGet(request.path("variants").size());
            var output=base.mapper.createObjectNode().put("status","SUCCEEDED").put("annotationFileSha256","a".repeat(64));
            var results=output.putArray("results");
            for(var variant:request.path("variants")) {
                var ann=variant.path("selectedAnnotation");var allele=variant.path("allele");
                var row=results.addObject().put("status","HIT").put("exactAlleleMatch",true).put("annotationFileSha256","a".repeat(64));
                row.set("queryId",variant.path("queryId"));row.set("selectedAnnotation",ann.deepCopy());row.set("allele",allele.deepCopy());
                row.putObject("referenceWindow").put("start",allele.path("position").asInt()-9999).put("length",19999)
                        .put("sha256",sha(allele.path("reference").asText().repeat(19999).getBytes(StandardCharsets.US_ASCII)));
                var annotation= row.putArray("annotationRows").addObject().put("geneSymbol",ann.path("geneSymbol").asText())
                        .put("contig",ann.path("contig").asText()).put("strand","+").put("start",ann.path("start").asInt()).put("end",ann.path("end").asInt());
                annotation.set("exons",ann.path("exons").deepCopy());
                var scores=row.putArray("annotations").addObject().put("geneSymbol",ann.path("geneSymbol").asText()).put("allele",allele.path("alternate").asText());
                var ds=scores.putObject("deltaScores");var dp=scores.putObject("deltaPositions");
                for(String kind:List.of("AG","AL","DG","DL")) { ds.put("DS_"+kind,score);dp.put("DP_"+kind,0); }
            }
            return output;
        });
        return new NativeSpliceAiRuntime(base.mapper,index,(c,s,n)->new CodingTranscriptMaterialSupply.ReferenceSpan(
                (c.replace("chr", "").equals("11")?"T":"C").repeat(n),base.mapper.createObjectNode().put("expectedSha256",referenceSha)),guard,runner);
    }
    private void confirmRecommendations() {
        var options = base.service.inputOptions(base.analysisId, 2);
        var refs = base.request.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            var recommendation = options.at("/candidates/" + i + "/recommendation");
            var item = (ObjectNode) base.request.path("hypotheses").get(i);
            var hypothesis = (ObjectNode) item.path("hypothesis");
            hypothesis.put("hypothesisId", recommendation.path("hypothesisId").asText());
            JsonNode relation = null;
            for (var current : recommendation.path("relationships"))
                if (current.path("mondoId").equals(hypothesis.path("mondoId"))) relation = current;
            assertNotNull(relation, recommendation.toPrettyString());
            hypothesis.set("moi", relation.path("moi"));
            var transcript = (ObjectNode) item.path("transcript");
            JsonNode annotation = null;
            for (var current : recommendation.path("annotations"))
                if (current.path("annotationId").equals(recommendation.path("recommendedAnnotationId"))) annotation = current;
            assertNotNull(annotation, recommendation.toPrettyString());
            String accession = annotation.path("versionedTranscriptId").asText(); int dot = accession.lastIndexOf('.');
            transcript.put("accession", accession.substring(0, dot)).put("version", Integer.parseInt(accession.substring(dot + 1)))
                    .set("annotationSource", annotation.path("annotationSource"));
            transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            transcript.put("selectionPolicy", recommendation.path("policyId").asText() + "@" + recommendation.path("policyVersion").asText());
            ((ObjectNode) transcript.path("source")).put("recordLocator", "recommendations/" + recommendation.path("recommendationId").asText() + "/selection");
            var ref = refs.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt"))
                ref.set(field, recommendation.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey"));
            ref.set("annotationId", recommendation.path("recommendedAnnotationId"));
        }
    }


    JsonNode criterion(JsonNode response,int candidate,String code) {
        for(var value:response.at("/candidates/"+candidate+"/attempts/0/localBatch/criteria"))
            if(("ACMG_AMP_2015:"+code).equals(value.path("criterionId").asText())) return value;
        fail("Missing "+code+": "+response);return null;
    }
    ObjectNode postBatch() throws Exception {
        var result=base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).contentType("application/json")
                .content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andReturn();
        assertEquals(200,result.getResponse().getStatus(),()->Objects.toString(result.getResolvedException()));
        return (ObjectNode)base.mapper.readTree(result.getResponse().getContentAsByteArray());
    }
    void archive(String name,JsonNode content) throws Exception {
        String root=System.getProperty("bp7.business.evidence.dir");
        if(root!=null) Files.write(Path.of(root).resolve(name+".json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content));
    }
    @Test void completeSavedChainReusesPredictionAndPreservesAllJsonAcrossH2Reopen() throws Exception {
        var options=new AcmgBusinessWorkflows(legacy,base.service).inputOptions(base.analysisId,2,AcmgBusinessRuleScope.LOCAL_BATCH1);
        assertTrue(options.at("/availableWorkflows/1/label").asText().contains("BP7"));
        assertEquals(6,base.service.configuredCriteria().size());
        var original=base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,100));
        assertEquals(0,modelCalls.get());var first=postBatch();
        for(int i=0;i<2;i++) {
            assertEquals("MET",criterion(first,i,"BP7").path("status").asText(),first.toPrettyString());
            assertEquals("SUPPORTING",criterion(first,i,"BP7").path("appliedStrength").asText());
            assertEquals("MET",criterion(first,i,"BP4").path("status").asText());
            assertFalse(criterion(first,i,"BP7").at("/details/bp7/additionalPredictionPerformed").asBoolean());
        }
        assertEquals(1,modelCalls.get());assertEquals(2,modelUnits.get());
        String id=first.path("runId").asText();var stored=base.store.run(base.analysisId,id).orElseThrow();
        var privateData=base.mapper.readTree(stored.privateJson());var bp7=privateData.at("/localBatch/modules/bp7");
        assertEquals(2,bp7.path("evaluatorCalls").asInt());assertEquals(0,bp7.path("providerCalls").asInt());
        for(int i=0;i<2;i++) {
            var raw=bp7.path("results").get(i);
            assertEquals(privateData.at("/localBatch/modules/prediction/results/"+i+"/resultId"),raw.at("/sharedPrediction/selectedPredictionResultId"));
            assertEquals(bp7.at("/predictionPreparation/plans/"+i),privateData.at("/localBatch/modules/prediction/results/"+i+"/plan"));
            assertTrue(raw.at("/functionalEffectCheck").isObject());
        }
        assertFalse(first.toString().contains(temporary.toString().replace("\\","\\\\")));
        assertTrue(first.findValues("sourceSnapshot").isEmpty());assertTrue(first.findValues("predictionPreparation").isEmpty());
        archive("normal-public",first);archive("normal-private",privateData);archive("source",base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()));
        archive("normal-request",base.mapper.readTree(stored.requestJson()));
        var inputs=base.mapper.createObjectNode().put("dataClass","SYNTHETIC").put("modelTransport","MOCK_NOT_SCIENTIFIC");
        var source=base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        var paths=List.of(base.prepared.conversion().getOutputFile(),Path.of(source.path("workDirectory").asText()).resolve("output/result.variants.tsv"),
                temporary.resolve("bp7-synthetic.fa"),temporary.resolve("bp7-synthetic.fa.fai"));
        var files=inputs.putArray("rawFiles");
        for(var path:paths) {byte[] bytes=Files.readAllBytes(path);files.addObject().put("name",path.getFileName().toString())
                .put("byteLength",bytes.length).put("sha256",sha(bytes)).put("utf8",new String(bytes,StandardCharsets.UTF_8));}
        archive("normal-inputs",inputs);
        assertEquals(first,postBatch());new JdbcTemplate(requireNonNull(base.dataSource)).execute("SHUTDOWN");base.recreateRepositories();forbidden=true;install();
        var read=base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,id)).andExpect(status().isOk()).andReturn();
        assertEquals(first,base.mapper.readTree(read.getResponse().getContentAsByteArray()));assertEquals(first,postBatch());
        var restored=base.store.run(base.analysisId,id).orElseThrow();
        assertEquals(stored.responseJson(),restored.responseJson());assertEquals(stored.privateJson(),restored.privateJson());assertEquals(stored.requestJson(),restored.requestJson());
        base.http.perform(get("/variant-analyses/{id}/acmg/runs",base.analysisId)).andExpect(status().isOk());
        assertEquals(1,modelCalls.get());assertEquals(1,factories.get());assertEquals(1,base.store.runs(base.analysisId).size());
        assertEquals(original,base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,100)));
        archive("reopened-public",base.mapper.readTree(restored.responseJson()));archive("reopened-private",base.mapper.readTree(restored.privateJson()));
    }
    @ParameterizedTest @ValueSource(strings={"0.15","0.20"})
    void nonBenignIsNotMetInsteadOfMissing(String score) throws Exception {
        this.score=score;var result=postBatch();
        assertEquals("NOT_MET",criterion(result,0,"BP7").path("status").asText(),result.toPrettyString());
        assertEquals(1,modelCalls.get());archive("not-met-"+score,result);
    }
    @Test void excludedPositionIsExplicitlyNotApplicableInOrdinaryContract() throws Exception {
        proximal=true;var result=postBatch();var c=criterion(result,0,"BP7");
        assertEquals("NOT_EVALUATED",c.path("status").asText());assertEquals("NOT_APPLICABLE",c.at("/details/bp7/coreStatus").asText(),c.toPrettyString());
        assertEquals("BP7_POSITION_SPLICE_PROXIMAL",c.path("reasonCode").asText());archive("not-applicable",result);
    }
    @Test void missingCatalogIsNotAnEmptyExperimentSearchAndInvalidatesOldConfirmation() throws Exception {
        catalog=new FileSystemResource(requireNonNull(temporary.resolve("missing.json")));
        assertEquals("LOCAL_BATCH_DEPENDENCY_CHANGED_RESTART_REQUIRED",assertThrows(ResponseStatusException.class,
                ()->base.service.submit(base.analysisId,base.mapper.writeValueAsBytes(base.request))).getReason());
        install();assertThrows(ResponseStatusException.class,()->base.service.submit(base.analysisId,base.mapper.writeValueAsBytes(base.request)));assertEquals(0,modelCalls.get());
        confirmRecommendations();var out=postBatch();var c=criterion(out,0,"BP7");
        assertEquals("NOT_EVALUATED",c.path("status").asText());assertEquals("BP7_EXPERIMENT_SOURCE_UNAVAILABLE",c.path("reasonCode").asText());
        assertEquals("UNAVAILABLE",c.at("/details/bp7/catalogSourceStatus").asText());archive("missing-catalog",out);
    }
    @Test void disabledProfileKeepsFiveRulesAndReadsOldBp7History() throws Exception {
        var first=postBatch();enabled=false;install();confirmRecommendations();var old=base.service.read(base.analysisId,first.path("runId").asText());
        assertFalse(new AcmgBusinessWorkflows(legacy,base.service).inputOptions(base.analysisId,2,AcmgBusinessRuleScope.LOCAL_BATCH1)
                .at("/availableWorkflows/1/label").asText().contains("BP7"));
        assertEquals(first,old);var next=postBatch();
        assertEquals(5,next.at("/candidates/0/attempts/0/localBatch/criteria").size());
        assertFalse(next.at("/localBatch/modules").has("bp7"));assertEquals(2,modelCalls.get());
    }
    @Test void missingPredictionKeepsTopNAndDoesNotLaunchAnAlternative() throws Exception {
        missingPrediction=true;install();confirmRecommendations();var out=postBatch();
        assertEquals(2,out.path("candidates").size());assertEquals(0,modelCalls.get());assertEquals(0,factories.get());
        for(int i=0;i<2;i++) assertEquals("BP7_PREPARED_PLAN_UNAVAILABLE",criterion(out,i,"BP7").path("reasonCode").asText());
    }
    @Test void oneMissingBackgroundDoesNotReplaceOrDropTheSelectedCandidate() throws Exception {
        ((com.fasterxml.jackson.databind.node.ArrayNode)base.request.path("hypotheses")).remove(0);
        ((ObjectNode)base.request.at("/recommendations/0")).putNull("stableRelationshipKey").putNull("annotationId");
        var out=postBatch();assertEquals(2,out.path("candidates").size());assertEquals(1,modelCalls.get());assertEquals(1,modelUnits.get());
        assertEquals("EXPLICIT_INPUT_MISSING",criterion(out,0,"BP7").path("reasonCode").asText());
        assertEquals("MET",criterion(out,1,"BP7").path("status").asText());archive("top2-first-missing",out);
    }
    @Test void unavailableSourceProducesSixSavedAbstentionsAndZeroModelCalls() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(requireNonNull(base.service),"runtime",
                (java.util.function.Supplier<ExplicitCaseInputCoordinator>)()->{throw new IllegalArgumentException("SYNTHETIC_RUNTIME_UNAVAILABLE");});
        var options=base.service.inputOptions(base.analysisId,2);base.request.putArray("hypotheses");
        var refs=base.request.putArray("recommendations");
        for(var candidate:options.path("candidates")) {
            var r=candidate.path("recommendation");var ref=refs.addObject();
            for(String field:List.of("candidateId","recommendationId","policyVersion","policyHash","generatedAt")) ref.set(field,r.path(field));
            ref.putNull("stableRelationshipKey").putNull("annotationId");
        }
        var out=postBatch();assertEquals(2,out.path("candidates").size());assertEquals(0,modelCalls.get());assertEquals(0,factories.get());
        for(int i=0;i<2;i++) {
            assertEquals(6,out.at("/candidates/"+i+"/attempts/0/localBatch/criteria").size());
            assertEquals("NOT_EVALUATED",criterion(out,i,"BP7").path("status").asText());
        }
        assertEquals(out,base.service.read(base.analysisId,out.path("runId").asText()));archive("source-unavailable",out);
    }
    @Test void syntheticRecordedSpliceConflictIsSavedAndDoesNotBlockAnUnrelatedCandidate() throws Exception {
        ObjectNode doc;
        try(var in=catalog.getInputStream()) {doc=(ObjectNode)base.mapper.readTree(in);}
        doc.put("catalogId","synthetic-bp7-test").put("coverage","SYNTHETIC engine fixture, no literature assertion");
        var record=(ObjectNode)doc.path("records").get(0);record.put("recordId","synthetic-hbb-splice-conflict")
                .put("geneId","HGNC:4827").put("geneSymbol","HBB").put("summary","SYNTHETIC conflict fixture, not scientific data");
        record.set("allele",base.mapper.createObjectNode().put("assembly","GRCh38").put("contig","11").put("position",20000).put("reference","T").put("alternate","A"));
        var chosen=base.request.at("/hypotheses/0/transcript");
        ((ObjectNode)record.path("transcriptMapping")).put("ensembl",chosen.path("accession").asText()+"."+chosen.path("version").asInt());
        var mapping=(ObjectNode)record.path("transcriptMapping");String[] sourceRow=mapping.path("sourceRow").asText().split("\t",-1);
        sourceRow[2]="HGNC:4827";sourceRow[3]="HBB";sourceRow[7]=mapping.path("ensembl").asText();
        mapping.put("sourceRow",String.join("\t",sourceRow)).put("alleleMappingEvidence","SYNTHETIC altered fixture for business conflict test");
        var hashing=new SplicingExperimentChecks(base.mapper,catalog,temporary.resolve("unused"),(a,r)->base.mapper.createObjectNode());
        for(var value:doc.path("records")) {var r=(ObjectNode)value;r.remove("contentSha256");r.put("contentSha256",hashing.hash(r));}
        doc.remove("contentSha256");doc.put("contentSha256",hashing.hash(doc));
        catalog=new ByteArrayResource(requireNonNull(base.mapper.writeValueAsBytes(doc)));install();confirmRecommendations();
        var out=postBatch();var c=criterion(out,0,"BP7");
        assertEquals("NOT_EVALUATED",c.path("status").asText(),c.toPrettyString());
        assertEquals("BP7_RECORDED_ALTERED_SPLICING_REVIEW_REQUIRED",c.path("reasonCode").asText(),c.toPrettyString());
        assertTrue(c.at("/details/bp7/reviewRequired").asBoolean());assertEquals("MET",criterion(out,1,"BP7").path("status").asText());
        assertEquals(1,modelCalls.get());assertEquals(out,postBatch());assertEquals(1,modelCalls.get());archive("experiment-conflict",out);
    }
}
