package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.impl.VariantInterpretationServiceImpl;
import org.nbscn.varvis.variantanalysis.service.impl.PicardVcfBuildConverter;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in fixed public PVS1 controls in the existing isolated HTTP/file-H2 harness. No listening service. */
class Hnf1aPvs1BusinessSmokeTest {
    private static final String TX = "ENST00000257555.11";
    private static final Map<Integer,String> EXPECTED = Map.of(120989017,"MET",120993678,"MET");
    private int pvs1Providers, pvs1Evaluators;
    private final List<Hnf1aPvs1Runtime> observedRuntimes = new ArrayList<>();
    private IntronicScopeBusinessSmokeTest smoke;

    @Test @EnabledIfSystemProperty(named="varvis.pvs1.real",matches="true")
    void publicControlsThroughAnalysisEvaluationSaveGetAndRepeat() throws Exception {
        Path parent = Path.of("target/pvs1-hnf1a-20260909").toAbsolutePath();
        Path work = Files.createDirectory(parent.resolve("backend-"+UUID.randomUUID()));
        smoke = IntronicScopeBusinessSmokeTest.positiveAt(work);
        smoke.setup(); var base = smoke.base; smoke.artifacts = work;
        var report = base.mapper.createObjectNode().put("status","BLOCKED").put("pageCovered",false)
                .put("environment","ISOLATED_MOCKMVC_FILE_H2").put("dataClass","SYNTHETIC");
        System.out.println("PVS1_BACKEND_WORK="+work);
        try {
            JsonNode panel;
            try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/acmg/pvs1-public-controls-v1.json"))) {
                panel = base.mapper.readTree(stream);
            }
            write(work,"fixed-panel.json",panel);
            assertEquals(panel.path("profileSha256").asText(),Hnf1aPvs1Runtime.PROFILE_SHA256);
            var policy = AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.STANDARD,false);
            // The frequency precheck used the configured preset. Normal no-HPO analysis removes only hiPhive.
            var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            assertEquals((Object)yaml.load(Files.readString(parent.resolve("effective-standard.yml"))),
                    (Object)yaml.load(AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.STANDARD,true).effectiveYaml()));
            write(work,"actual-configuration.json",smoke.configurationProof());
            Files.writeString(work.resolve("effective-standard.yml"),policy.effectiveYaml(),StandardOpenOption.CREATE_NEW);
            var frequency = base.mapper.readTree(Files.readString(parent.resolve("exomiser-frequency-precheck.json")));
            assertEquals("15.1.0",base.properties.getExomiser().getVersion()); assertEquals("2602",base.properties.getExomiser().getDataVersion());
            assertEquals(frequency.path("databaseBytes").asLong(),Files.size(Path.of(base.properties.getExomiser().getDataDirectory()).resolve("2602_hg38/2602_hg38_variants.mv.db")));
            for(var row:frequency.path("alleles")) if(!row.path("selectedMaxPercent").isNull()) {
                assertEquals("PASS",row.path("frequencyFilter").asText());
                assertTrue(new java.math.BigDecimal(row.path("selectedMaxPercent").asText()).compareTo(frequency.at("/inheritanceModesPercent/AUTOSOMAL_DOMINANT").decimalValue())<0);
            }
            write(work,"frequency-precheck.json",frequency);
            var ledger = ExplicitContextSourceLedger.load(base.mapper,Path.of(smoke.environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),smoke.environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference = new NativeSnvReference(base.mapper,base.properties,new NormalizationResourceValidator(base.properties),
                    Path.of(smoke.environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")),List.of(ledger),ledger.reference("reference"));
            var refs = report.putArray("referenceObservations");
            StringBuilder vcf = new StringBuilder("##fileformat=VCFv4.2\n##reference=GRCh38\n##contig=<ID=chr12,length=133275309>\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allelic depths\">\n##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n");
            for (var control : panel.path("controls")) {
                String[] a=control.path("allele").asText().split(":");
                var ref=reference.observe("chr12",Integer.parseInt(a[2]),a[3]); refs.add(ref.observation()); assertEquals("MATCH",ref.observation().path("status").asText());
                vcf.append("chr12\t").append(a[2]).append("\t.\t").append(a[3]).append('\t').append(a[4]).append("\t99\tPASS\t.\tGT:AD:DP:GQ\t0/1:10,10:20:99\n");
            }
            Path input=work.resolve("fixed-public.vcf"),vep=work.resolve("preflight-vep.jsonl");
            Files.writeString(input,vcf.toString(),StandardOpenOption.CREATE_NEW);
            String reusedPreflight=System.getProperty("varvis.pvs1.preflightDirectory", "");
            if (reusedPreflight.isBlank()) {
                var command=new VepCommandBuilder(base.properties).build(new VepCommandOptions(input,vep));
                write(work,"vep-preflight-command.json",base.mapper.valueToTree(command));
                var outcome=smoke.vepRunner().run(command,work,Duration.ofSeconds(Math.max(1,base.properties.getVep().getTimeoutSeconds())));
                write(work,"vep-preflight-process.json",base.mapper.valueToTree(outcome)); assertTrue(outcome.isSuccess(),outcome.getOutput());
            } else {
                Path previous=Path.of(reusedPreflight), retained=previous.resolve("preflight-vep.jsonl");
                assertTrue(Files.size(retained)<2*1024*1024);
                String sha=IntronicScopeRuntimeProbe.sha(retained);
                assertEquals(System.getProperty("varvis.pvs1.preflightSha256"),sha);
                assertEquals(Files.readString(previous.resolve("fixed-public.vcf")),vcf.toString());
                assertEquals(base.mapper.readTree(Files.readString(previous.resolve("actual-configuration.json"))),smoke.configurationProof());
                var process=base.mapper.readTree(Files.readString(previous.resolve("vep-preflight-process.json")));
                assertTrue(process.path("success").asBoolean());assertFalse(process.path("timedOut").asBoolean());assertEquals(0,process.path("exitCode").asInt(-1));
                Files.copy(retained,vep);
                write(work,"vep-preflight-reuse.json",base.mapper.createObjectNode().put("sha256",sha)
                        .put("sourceDirectory",previous.getFileName().toString()).put("newVepCalls",0));
            }
            // Public VCF records may all have ID='.'. Preflight checks each exact allele directly.
            var observations=base.mapper.createArrayNode();var positions=new HashSet<Integer>();
            for(String line:Files.readAllLines(vep)) if(!line.isBlank()) {
                var raw=base.mapper.readTree(line);int position=raw.path("start").asInt();assertTrue(positions.add(position));
                assertEquals("GRCh38",raw.path("assembly_name").asText());assertEquals("chr12",raw.path("seq_region_name").asText());
                JsonNode control=null;for(var c:panel.path("controls"))if(c.path("allele").asText().split(":")[2].equals(Integer.toString(position)))control=c;
                var allele=Objects.requireNonNull(control).path("allele").asText().split(":");assertEquals(allele[3]+"/"+allele[4],raw.path("allele_string").asText());
                JsonNode selected=null;for(var tx:raw.path("transcript_consequences")) if("ENST00000257555".equals(tx.path("transcript_id").asText()))selected=tx;
                var tx=Objects.requireNonNull(selected);assertEquals("HNF1A",tx.path("gene_symbol").asText());
                assertTrue(tx.path("hgvsc").asText().startsWith(TX+":"));assertTrue(tx.path("consequence_terms").toString().contains("stop_gained"));
                observations.addObject().put("position",position).set("transcript",tx);
            }
            assertEquals(EXPECTED.keySet(),positions);write(work,"preflight-annotations.json",observations);
            report.put("preflightPassed",true); write(work,"preflight-report.json",report);
            var validator=new VepResourceValidator(base.properties);
            var annotation=new VepTranscriptAnnotationEnrichmentService(base.jobs,base.properties,validator,new VepCommandBuilder(base.properties),
                    new VepJsonOutputParser(base.mapper),smoke.vepRunner(),new TranscriptAnnotationService(base.properties,new AnnTranscriptAnnotationProvider(),new InMemoryTranscriptAnnotationCache(),validator));
            var enrichment=new VariantInterpretationServiceImpl(null,List.of(),new TransparentVariantRankingService(base.properties));
            var processor=new VariantAnalysisJobProcessor(base.jobs,new PicardVcfBuildConverter(base.properties),mock(VcfNormalizer.class),mock(VcfQcService.class),enrichment,base.properties,annotation);
            ReflectionTestUtils.setField(processor,"acmgBusinessEnabled",true);processor.setAcmgExecutionSources(base.sources);
            processor.setExomiserEngineAdapter(new ConfiguredExomiserEngineAdapter(base.properties,(cmd,dir,out,err,env,timeout)->{
                smoke.exomiserCalls++;return new SystemExomiserProcessRunner().run(cmd,dir,out,err,env,timeout);
            },new ExomiserRuntimeValidator()));
            var upload=smoke.analysisHttp(smoke.analysisService(processor));
            var uploaded=base.mapper.readTree(upload.perform(multipart("/variant-analyses").file(new MockMultipartFile("vcfFile","public-pvs1.vcf","text/plain",vcf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("genomeAssembly","GRCh38").param("analysisMode","WES").param("analysisScope","STANDARD").param("sampleId","synthetic-proband"))
                    .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray());
            base.analysisId=uploaded.path("id").asText();report.put("analysisId",base.analysisId);write(work,"upload-response.json",uploaded);
            var job=base.jobs.findById(base.analysisId).orElseThrow();assertEquals(VariantAnalysisStatus.READY,job.getStatus(),base.mapper.writeValueAsString(job));
            assertEquals(AnalysisScopePolicy.Scope.STANDARD,AnalysisScopePolicy.read(job).scope());
            var candidates=base.jobs.findInterpretations(base.analysisId,0,20);write(work,"formal-topn.json",base.mapper.valueToTree(candidates));
            assertTrue(candidates.stream().map(c->c.getObservation().getKey().getPosition()).collect(java.util.stream.Collectors.toSet()).containsAll(EXPECTED.keySet()));
            evaluateSavedCandidates(work,report);
            assertEquals(1,smoke.exomiserCalls);assertEquals(reusedPreflight.isBlank()?2:1,smoke.vepCalls);assertEquals(0,smoke.spliceBatches);
            report.put("status","PVS1_TWO_DISTINCT_NMD_POSITIVES_BACKEND_VERIFIED").put("readbackIdentical",true).put("repeatIdentical",true);
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;}
        finally {report.set("actualCalls",base.mapper.valueToTree(calls()));write(work,"result.json",report);base.closeDatabase();}
    }
    private void evaluateSavedCandidates(Path work,ObjectNode report) throws Exception {
        var base=smoke.base;
            base.closeDatabase();base.recreateRepositories();wire();
            var options=base.service.inputOptions(base.analysisId,20);write(work,"input-options.json",options);
            var request=declaration(options);write(work,"first-request.json",request);
            var before=calls();var saved=post(work,"first",request);event(work,"first-post","POST",request,saved,before);
            assertEquals(0, saved.at("/pm2Calls/provider").asInt());
            assertEquals(0, saved.at("/pm2Calls/evaluator").asInt());
            assertEquals(2, saved.at("/pvs1Calls/evaluator").asInt());
            assertEquals(2, saved.at("/pvs1Calls/storedVepProjections").asInt());
            before=calls();base.closeDatabase();base.recreateRepositories();wire();
            var read=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            event(work,"saved-get","GET",null,read,before);assertEquals(saved,read);assertEquals(before,calls());
            before=calls();var repeated=post(work,"repeat",request);event(work,"repeat-post","POST",request,repeated,before);
            assertEquals(saved,repeated);assertEquals(saved.path("runId"),repeated.path("runId"));assertEquals(before,calls());
            var actual=new HashMap<Integer,String>();
            for(var candidate:saved.path("candidates")) for(var attempt:candidate.path("attempts")) {
                var pvs1=attempt.path("pvs1"); int pos=pvs1.at("/context/variant/evidenceQueryAllele/position").asInt();
                if(EXPECTED.containsKey(pos)) {
                    assertEquals("HGNC:11621",pvs1.at("/context/gene/hgncId").asText());assertEquals("MONDO:0015967",pvs1.at("/context/disease/mondoId").asText());
                    assertEquals("AUTOSOMAL_DOMINANT",pvs1.at("/context/moi").asText());
                    assertEquals(TX,pvs1.at("/context/transcript/accession").asText()+"."+pvs1.at("/context/transcript/version").asInt());
                    String state=pvs1.at("/evaluation/evaluationResult/status").asText();actual.put(pos,state);
                    if("MET".equals(state))assertEquals("VERY_STRONG",pvs1.at("/evaluation/appliedStrength").asText());
                }
            }
            report.set("actualPvs1States",base.mapper.valueToTree(actual));assertEquals(EXPECTED,actual);
    }
    @Test @EnabledIfSystemProperty(named="varvis.pvs1.resumeDirectory",matches=".+")
    void continueFormalCandidatesWithoutRepeatingAnalysis() throws Exception {
        Path previous=Path.of(System.getProperty("varvis.pvs1.resumeDirectory"));
        Path work=Files.createDirectory(Path.of("target/pvs1-hnf1a-20260909").toAbsolutePath().resolve("evaluation-"+UUID.randomUUID()));
        smoke=IntronicScopeBusinessSmokeTest.positiveAt(work);smoke.setup();var base=smoke.base;smoke.artifacts=work;
        var report=base.mapper.createObjectNode().put("status","BLOCKED").put("pageCovered",false)
                .put("environment","ISOLATED_MOCKMVC_COPY_OF_CLOSED_FILE_H2");
        try {
            base.closeDatabase();
            Path original=previous.resolve("business-db.mv.db"),copy=work.resolve("continued-db.mv.db");
            assertEquals(System.getProperty("varvis.pvs1.resumeDatabaseSha256"),IntronicScopeRuntimeProbe.sha(original));
            Files.copy(original,copy);assertEquals(IntronicScopeRuntimeProbe.sha(original),IntronicScopeRuntimeProbe.sha(copy));
            report.put("sourceDatabaseSha256",IntronicScopeRuntimeProbe.sha(original)).put("sourceDirectory",previous.getFileName().toString());
            base.dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:file:"+work.resolve("continued-db").toString().replace('\\','/')+";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE","sa","");
            base.properties.setStorageRoot(previous.resolve("jobs").toAbsolutePath());base.recreateRepositories();
            assertEquals(base.mapper.readTree(Files.readString(previous.resolve("actual-configuration.json"))),smoke.configurationProof());
            var uploaded=base.mapper.readTree(Files.readString(previous.resolve("upload-response.json")));base.analysisId=uploaded.path("id").asText();report.put("analysisId",base.analysisId);
            var job=base.jobs.findById(base.analysisId).orElseThrow();assertEquals(VariantAnalysisStatus.READY,job.getStatus());
            assertEquals(AnalysisScopePolicy.Scope.STANDARD,AnalysisScopePolicy.read(job).scope());
            var exactJson=base.mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            var saved=exactJson.readTree(Files.readString(previous.resolve("formal-topn.json")));
            var restored=exactJson.readTree(base.mapper.writeValueAsBytes(base.jobs.findInterpretations(base.analysisId,0,20)));
            // Full candidate JSON contains ranking decimals; compare exact decimal trees, not evidence-v2 identities.
            assertJsonContent(saved,restored,"formalTopN");
            write(work,"formal-topn.json",saved);evaluateSavedCandidates(work,report);
            assertEquals(0,smoke.exomiserCalls);assertEquals(0,smoke.vepCalls);assertEquals(0,smoke.spliceBatches);
            report.put("status","PVS1_TWO_DISTINCT_NMD_POSITIVES_BACKEND_VERIFIED").put("readbackIdentical",true).put("repeatIdentical",true);
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;}
        finally {report.set("actualCalls",base.mapper.valueToTree(calls()));write(work,"result.json",report);base.closeDatabase();System.out.println("PVS1_EVALUATION_WORK="+work);}
    }
    private static void assertJsonContent(JsonNode expected,JsonNode actual,String path) {
        assertNotNull(actual,path);
        if(expected.isNumber() && actual.isNumber()) {
            assertEquals(0,expected.decimalValue().compareTo(actual.decimalValue()),path);return;
        }
        assertEquals(expected.getNodeType(),actual.getNodeType(),path);
        if(expected.isObject()) {
            assertEquals(expected.size(),actual.size(),path);
            expected.fieldNames().forEachRemaining(name->assertJsonContent(expected.path(name),actual.get(name),path+"/"+name));
        } else if(expected.isArray()) {
            assertEquals(expected.size(),actual.size(),path);
            for(int i=0;i<expected.size();i++)assertJsonContent(expected.get(i),actual.get(i),path+"/"+i);
        } else assertEquals(expected,actual,path);
    }
    private Map<String,Integer> calls() {
        pvs1Providers=0;
        pvs1Evaluators=observedRuntimes.stream().mapToInt(v -> Objects.requireNonNull(v).evaluatorCalls()).sum();
        return Map.of("exomiser",smoke.exomiserCalls,"vep",smoke.vepCalls,"rawBatch",smoke.spliceBatches,
                "pvs1Provider",pvs1Providers,"pvs1Evaluator",pvs1Evaluators,"allProvider",smoke.providerCalls+pvs1Providers,"allEvaluator",smoke.evaluatorCalls+pvs1Evaluators,
                "storedVepProjections",observedRuntimes.stream().mapToInt(v -> Objects.requireNonNull(v).storedVepProjections()).sum());
    }
    @SuppressWarnings("unchecked")
    private void wire() {
        smoke.wireBusiness();
        var service=Objects.requireNonNull(smoke.base.service);
        var factory=(Supplier<Hnf1aPvs1Runtime>)Objects.requireNonNull(ReflectionTestUtils.getField(service,"pvs1Runtime"));
        ReflectionTestUtils.setField(service,"pvs1Runtime",(Supplier<Hnf1aPvs1Runtime>)()->{
            var runtime=Objects.requireNonNull(factory.get()); observedRuntimes.add(runtime);return runtime;
        });
        smoke.base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(smoke.base.service)).build();
    }
    private ObjectNode declaration(ObjectNode options) {
        var mapper=smoke.base.mapper;
        var request=mapper.createObjectNode().put("topN",20).put("declarationVersion","ordinary-ui-v3").put("sampleId","synthetic-proband").put("dataClass","SYNTHETIC");
        request.putObject("patient").put("patientContextId","patient-context-public-pvs1").put("role","PROBAND").put("sex","UNKNOWN").set("source",smoke.source("pvs1-public-panel/case"));
        var hypotheses=request.putArray("hypotheses");var selections=request.putArray("recommendations");
        for(var row:options.path("candidates")) {
            var r=row.path("recommendation");JsonNode relation=null;
            for(var rel:r.path("relationships")) if(rel.path("selectable").asBoolean()&&"HGNC:11621".equals(rel.path("hgncId").asText())&&"MONDO:0015967".equals(rel.path("mondoId").asText())&&"AUTOSOMAL_DOMINANT".equals(PatientDiseaseContextBindingResolver.normalizeMoi(rel.path("moi").asText()))) relation=rel;
            var selection=selections.addObject();for(String f:List.of("candidateId","recommendationId","generatedAt","policyVersion","policyHash"))selection.set(f,r.path(f));
            // Confirm every formal row, retaining explicit missing hypotheses for incompatible MOIs.
            if (relation == null) { selection.putNull("stableRelationshipKey").putNull("annotationId"); continue; }
            var tx=r.path("resourceCompatibleTranscript");assertEquals(TX,tx.path("versionedTranscriptId").asText());
            selection.set("stableRelationshipKey",relation.path("stableRelationshipKey"));selection.set("annotationId",tx.path("annotationId"));
            var item=hypotheses.addObject().put("affectedStatus","UNKNOWN");var h=item.putObject("hypothesis");h.set("candidateId",row.path("candidateId"));h.set("hypothesisId",r.path("hypothesisId"));
            for(String f:List.of("hgncId","mondoId","moi"))h.set(f,relation.path(f));h.set("provenance",smoke.source("pvs1-public-panel/explicit-hypothesis"));
            item.putObject("transcript").put("accession","ENST00000257555").put("version",11).put("annotationSource",tx.path("annotationSource").asText())
                    .put("annotationSourceVersion",tx.path("annotationSourceVersion").asText()).put("selectionPolicy",r.path("policyId").asText()+"@"+r.path("policyVersion").asText())
                    .put("selectionReason","Explicit public engineering control confirmation; not a diagnosis").set("source",smoke.source("recommendations/"+r.path("recommendationId").asText()+"/selection"));
        }
        assertEquals(EXPECTED.size(),hypotheses.size(),"All fixed AD controls must be reachable");
        var parsed=AcmgSubmission.parse(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),mapper);
        var verifier=AcmgRecommendations.unavailable(mapper);
        for(var choice:parsed.recommendations()) for(var row:options.path("candidates"))
            if(choice.candidateId().equals(row.path("candidateId").asText()))
                verifier.verify(choice,(ObjectNode)row.path("recommendation"),parsed.hypotheses());
        return request;
    }
    private ObjectNode post(Path work,String label,ObjectNode request) throws Exception {
        var response=smoke.base.http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/variant-analyses/{id}/acmg/runs",smoke.base.analysisId).contentType("application/json")
                .content(Objects.requireNonNull(smoke.base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        if(response.getStatus()!=200)write(work,label+"-http-failure.json",smoke.base.mapper.createObjectNode()
                .put("httpStatus",response.getStatus()).put("body",response.getContentAsString())
                .put("errorMessage",response.getErrorMessage()));
        assertEquals(200,response.getStatus(),response.getErrorMessage());
        return (ObjectNode)smoke.base.mapper.readTree(response.getContentAsByteArray());
    }
    private void event(Path work,String label,String method,JsonNode request,JsonNode result,Map<String,Integer> before)throws Exception {
        var delta=new TreeMap<String,Integer>();calls().forEach((k,v)->delta.put(k,v-before.get(k)));
        var e=smoke.base.mapper.createObjectNode().put("method",method).put("httpStatus",200).put("completedAt",java.time.Instant.now().toString())
                .put("transport","Actual MockMvc controller dispatch").put("runId",result.path("runId").asText());
        e.set("callIncrement",smoke.base.mapper.valueToTree(delta));if(request!=null)write(work,label+"-request.json",request);
        write(work,label+"-response.json",result);write(work,label+"-event.json",e);
    }
    private void write(Path directory,String name,JsonNode value)throws Exception {
        Files.writeString(directory.resolve(name),value.toPrettyString(),StandardOpenOption.CREATE_NEW);
    }
}
