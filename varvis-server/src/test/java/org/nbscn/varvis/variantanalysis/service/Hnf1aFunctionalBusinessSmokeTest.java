package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.controller.FunctionalExperimentReviewController;
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
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in public allele panel, synthetic genotype, real configured Exomiser/VEP and isolated file H2. */
class Hnf1aFunctionalBusinessSmokeTest {
    private static final String TX = "ENST00000257555.11";
    private IntronicScopeBusinessSmokeTest smoke;
    private int functionalLookups, functionalEvaluations, functionalRuntimeConstructions;
    private Path work;
    private String retryOf;

    @Test @EnabledIfSystemProperty(named="varvis.functional.real",matches="true")
    void fixedPanelThroughFormalAnalysisSaveReadRestartRepeatAndAdoption() throws Exception {
        var parent=Path.of("target/hnf1a-functional-20260910").toAbsolutePath(); Files.createDirectories(parent);
        work=Files.createDirectory(parent.resolve("backend-"+UUID.randomUUID()));
        smoke=IntronicScopeBusinessSmokeTest.positiveAt(work);smoke.setup();var base=smoke.base;smoke.artifacts=work;
        for(String suffix:List.of("ba1-enabled","pm2-enabled","pvs1-enabled","ps1-pm5-enabled"))
            smoke.environment.setProperty("variant-analysis.acmg-business."+suffix,"false");
        smoke.environment.setProperty("variant-analysis.acmg-business.ps3-enabled","true");
        var report=base.mapper.createObjectNode().put("status","BLOCKED").put("pageCovered",false)
                .put("environment","ISOLATED_MOCKMVC_FILE_H2").put("dataClass","SYNTHETIC")
                .put("publicAllelesSyntheticGenotypes",true).put("scope","STANDARD").put("topN",20);
        System.out.println("FUNCTIONAL_BACKEND_WORK="+work);
        try {
            var profile=Hnf1aFunctionalRuntime.loadProfile(base.mapper);
            JsonNode facts;
            try(var input=Objects.requireNonNull(getClass().getResourceAsStream(profile.at("/factPackage/classpathResource").asText()))) {facts=base.mapper.readTree(input);}
            write("fixed-panel.json",facts);
            var policy=AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.STANDARD,false);
            Files.writeString(work.resolve("effective-standard.yml"),policy.effectiveYaml(),StandardOpenOption.CREATE_NEW);
            write("actual-configuration.json",smoke.configurationProof());
            report.put("effectiveYamlSha256",IntronicScopeRuntimeProbe.sha(work.resolve("effective-standard.yml")))
                    .put("templateSha256",IntronicScopeRuntimeProbe.sha(Path.of(base.properties.getExomiser().getAnalysisTemplate())));
            report.putArray("disabledUnrelatedBusinessSlices").add("BA1").add("PM2").add("PVS1").add("PS1_PM5");
            var ledger=ExplicitContextSourceLedger.load(base.mapper,Path.of(smoke.environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),smoke.environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference=new NativeSnvReference(base.mapper,base.properties,new NormalizationResourceValidator(base.properties),Path.of(smoke.environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")),List.of(ledger),ledger.reference("reference"));
            var refs=report.putArray("referenceObservations");
            StringBuilder vcf=new StringBuilder("##fileformat=VCFv4.2\n##reference=GRCh38\n##contig=<ID=chr12,length=133275309>\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allelic depths\">\n##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n");
            for(var record:facts.path("records")) {
                var a=record.path("allele");var ref=reference.observe("chr12",a.path("position").asInt(),a.path("reference").asText());
                refs.add(ref.observation());assertEquals("MATCH",ref.observation().path("status").asText());
                vcf.append("chr12\t").append(a.path("position").asText()).append("\t.\t").append(a.path("reference").asText()).append('\t').append(a.path("alternate").asText()).append("\t99\tPASS\t.\tGT:AD:DP:GQ\t0/1:10,10:20:99\n");
            }
            Files.writeString(work.resolve("fixed-public.vcf"),vcf.toString(),StandardOpenOption.CREATE_NEW);
            var validator=new VepResourceValidator(base.properties);
            var annotation=new VepTranscriptAnnotationEnrichmentService(base.jobs,base.properties,validator,new VepCommandBuilder(base.properties),new VepJsonOutputParser(base.mapper),smoke.vepRunner(),new TranscriptAnnotationService(base.properties,new AnnTranscriptAnnotationProvider(),new InMemoryTranscriptAnnotationCache(),validator));
            var enrichment=new VariantInterpretationServiceImpl(null,List.of(),new TransparentVariantRankingService(base.properties));
            var normalizer=mock(VcfNormalizer.class);var qc=mock(VcfQcService.class);
            var processor=new VariantAnalysisJobProcessor(base.jobs,new PicardVcfBuildConverter(base.properties),normalizer,qc,enrichment,base.properties,annotation);
            ReflectionTestUtils.setField(processor,"acmgBusinessEnabled",true);processor.setAcmgExecutionSources(base.sources);
            processor.setExomiserEngineAdapter(new ConfiguredExomiserEngineAdapter(base.properties,(cmd,dir,out,err,env,timeout)->{
                smoke.exomiserCalls++;return new SystemExomiserProcessRunner().run(cmd,dir,out,err,env,timeout);
            },new ExomiserRuntimeValidator()));
            var upload=smoke.analysisHttp(smoke.analysisService(processor));
            var uploaded=base.mapper.readTree(upload.perform(multipart("/variant-analyses").file(new MockMultipartFile("vcfFile","public-functional.vcf","text/plain",vcf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("genomeAssembly","GRCh38").param("analysisMode","WES").param("analysisScope","STANDARD").param("sampleId","synthetic-proband"))
                    .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray());
            base.analysisId=uploaded.path("id").asText();report.put("analysisId",base.analysisId);write("upload-response.json",uploaded);
            var job=base.jobs.findById(base.analysisId).orElseThrow();assertEquals(VariantAnalysisStatus.READY,job.getStatus(),base.mapper.writeValueAsString(job));
            assertEquals(policy.policySha256(),AnalysisScopePolicy.read(job).policySha256());verifyNoInteractions(normalizer,qc);
            var candidates=base.jobs.findInterpretations(base.analysisId,0,20);write("formal-topn.json",base.mapper.valueToTree(candidates));
            var observed=report.putArray("formalAlleles");for(var candidate:candidates) observed.add(base.mapper.valueToTree(candidate.getObservation().getKey()));
            evaluate(report);
            assertEquals(1,smoke.exomiserCalls);assertEquals(1,smoke.vepCalls);assertEquals(0,smoke.spliceBatches);
            report.put("status","REAL_PIPELINE_AND_SAVED_BUSINESS_VERIFIED");
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;}
        finally {report.set("actualCalls",base.mapper.valueToTree(calls()));write("result.json",report);base.closeDatabase();}
    }
    private void evaluate(ObjectNode report) throws Exception {
        var base=smoke.base;wire();var options=base.service.inputOptions(base.analysisId,20);write("input-options.json",options);
        assertTrue(options.path("executionSourceRecovered").asBoolean(),options.path("blockers").toString());
        var request=declaration(options);if(retryOf!=null)request.put("retryOf",retryOf);write("first-request.json",request);
        var before=calls();var saved=postRun(request);event("first",saved,before);report.set("runId",saved.path("runId"));
        var outcomes=report.putArray("functionalOutcomes");JsonNode positive=null;
        for(var c:saved.path("candidates"))for(var attempt:c.path("attempts")) {
            var f=attempt.path("functional");if(!f.has("recordId"))continue;
            outcomes.addObject().set("recordId",f.path("recordId"));var o=(ObjectNode)outcomes.get(outcomes.size()-1);
            o.set("evaluation",f.path("evaluation"));
            if("P112L".equals(f.path("recordId").asText())) {assertEquals("MET",f.at("/evaluation/evaluationResult/status").asText());positive=f;}
            else assertEquals("NOT_EVALUATED",f.at("/evaluation/evaluationResult/status").asText());
        }
        report.put("publicP112LBusinessPositiveCovered",positive!=null);
        before=calls();base.closeDatabase();base.recreateRepositories();wire();
        var read=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved,read);assertEquals(before,calls());event("restart-get",read,before);
        var repeated=postRun(request);assertEquals(saved,repeated);assertEquals(before,calls());event("repeat",repeated,before);
        if(positive!=null) verifyReview(saved,positive);
        report.put("readbackIdentical",true).put("repeatIdentical",true).put("reviewBackendCovered",positive!=null);
    }
    @Test @EnabledIfSystemProperty(named="varvis.functional.resume",matches=".+")
    void verifySavedFormalAnalysisWithoutRepeatingVepOrExomiser() throws Exception {
        Path root=Path.of(System.getProperty("varvis.functional.resume")).toAbsolutePath().normalize();
        assertEquals(Path.of("target/hnf1a-functional-20260910").toRealPath(),root.toRealPath().getParent());
        smoke=IntronicScopeBusinessSmokeTest.positiveAt(root);var base=smoke.base;
        work=Files.createDirectory(root.resolve("verification-"+UUID.randomUUID()));smoke.artifacts=work;
        var settings=new Properties();try(var input=Files.newInputStream(Path.of("config/variant-analysis-local.properties"))){settings.load(input);}
        var allowed=new Properties();settings.forEach((k,v)->{if(k.toString().startsWith("variant-analysis."))allowed.put(k,v);});
        smoke.environment=new org.springframework.mock.env.MockEnvironment().withProperty("VARVIS_RESOURCE_ROOT",Objects.requireNonNull(System.getProperty("varvis.e4b3.resourceRoot")));
        smoke.environment.getPropertySources().addLast(new org.springframework.core.env.PropertiesPropertySource("existing-local-variant-settings",allowed));
        for(String suffix:List.of("ba1-enabled","pm2-enabled","pvs1-enabled","ps1-pm5-enabled"))smoke.environment.setProperty("variant-analysis.acmg-business."+suffix,"false");
        smoke.environment.setProperty("variant-analysis.acmg-business.ps3-enabled","true");
        base.properties=new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        org.springframework.boot.context.properties.bind.Binder.get(smoke.environment).bind("variant-analysis",org.springframework.boot.context.properties.bind.Bindable.ofInstance(base.properties));
        base.properties.setStorageRoot(root.resolve("jobs"));base.properties.getPersistence().setTablePrefix("");
        base.dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:file:"+root.resolve("business-db").toString().replace('\\','/')+";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;IFEXISTS=TRUE","sa","");
        base.recreateRepositories();
        var original=base.mapper.readTree(Files.readAllBytes(root.resolve("result.json")));base.analysisId=original.path("analysisId").asText();retryOf=original.path("runId").asText();
        if(Files.isRegularFile(root.resolve("final-verification.json"))) {
            String last=base.mapper.readTree(Files.readAllBytes(root.resolve("final-verification.json"))).path("directory").asText();
            assertTrue(last.matches("verification-[a-f0-9-]{36}"));
            retryOf=base.mapper.readTree(Files.readAllBytes(root.resolve(last).resolve("result.json"))).path("runId").asText();
        }
        var report=base.mapper.createObjectNode().put("status","BLOCKED").put("analysisId",base.analysisId).put("pageCovered",false).put("dataClass","SYNTHETIC")
                .put("pipelineReused",true).put("retryOf",retryOf).put("correction","EVIDENCE_V2_REVIEW_ENVELOPE_AND_BUSINESS_SERIALIZATION_HASH");
        try {
            var job=base.jobs.findById(base.analysisId).orElseThrow();assertEquals(VariantAnalysisStatus.READY,job.getStatus());
            assertEquals(AnalysisScopePolicy.Scope.STANDARD,AnalysisScopePolicy.read(job).scope());
            assertEquals(base.mapper.readTree(Files.readAllBytes(root.resolve("actual-configuration.json"))),smoke.configurationProof());
            var candidates=base.jobs.findInterpretations(base.analysisId,0,20);write("formal-topn.json",base.mapper.valueToTree(candidates));
            var prior=base.mapper.readTree(Files.readAllBytes(root.resolve("formal-topn.json")));
            assertEquals(prior.size(),candidates.size());for(int i=0;i<candidates.size();i++)assertEquals(prior.get(i).path("candidateId").asText(),candidates.get(i).getCandidateId());
            evaluate(report);assertEquals(0,smoke.exomiserCalls);assertEquals(0,smoke.vepCalls);assertEquals(0,smoke.spliceBatches);
            assertEquals(3,report.path("functionalOutcomes").size());
            assertTrue(report.path("publicP112LBusinessPositiveCovered").asBoolean());
            assertTrue(report.path("reviewBackendCovered").asBoolean());
            report.put("status","REAL_PIPELINE_AND_SAVED_BUSINESS_VERIFIED");
            Files.writeString(root.resolve("final-verification.json"),base.mapper.createObjectNode().put("directory",work.getFileName().toString()).toPrettyString());
        } catch(Exception|AssertionError failure){report.put("failure",failure.toString());throw failure;}
        finally {report.set("actualCalls",base.mapper.valueToTree(calls()));write("result.json",report);base.closeDatabase();System.out.println("FUNCTIONAL_VERIFICATION_WORK="+work);}
    }
    private void verifyReview(JsonNode saved,JsonNode functional) throws Exception {
        var base=smoke.base;String url="/variant-analyses/"+base.analysisId+"/acmg/runs/"+saved.path("runId").asText()+"/functional-reviews";
        var input=base.mapper.createObjectNode().put("evaluationId",functional.at("/evaluation/evaluationId").asText())
                .put("factPackageSha256",functional.at("/atomicEvidence/0/source/resourceSha256").asText()).put("profileSha256",Hnf1aFunctionalRuntime.PROFILE_SHA256)
                .put("decision","ACCEPTED").put("actorId","reviewer-synthetic-functional-qa").put("role","SYSTEM_ADMINISTRATOR")
                .put("rationale","Synthetic engineering reviewer: verify explicit adoption persistence only; not a clinical decision.");
        var before=calls();var accepted=base.mapper.readTree(base.http.perform(post(url).contentType("application/json").content(Objects.requireNonNull(base.mapper.writeValueAsBytes(input))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());write("review-accepted.json",accepted);
        base.closeDatabase();base.recreateRepositories();wire();
        var repeated=base.mapper.readTree(base.http.perform(post(url).contentType("application/json").content(Objects.requireNonNull(base.mapper.writeValueAsBytes(input))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());assertEquals(accepted,repeated);
        var exported=base.mapper.readTree(base.http.perform(get(url+"/export")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());write("review-export.json",exported);
        assertEquals(before,calls());assertEquals(saved,base.service.read(base.analysisId,saved.path("runId").asText()));
    }
    @SuppressWarnings("unchecked")
    private void wire() {
        var base=smoke.base;base.service=new AcmgBusinessConfig().acmgBusinessService(base.mapper,base.jobs,base.store,base.sources,base.properties,smoke.environment);
        smoke.observeBusiness();var service=Objects.requireNonNull(base.service);
        var factory=(Supplier<Hnf1aFunctionalRuntime>)Objects.requireNonNull(ReflectionTestUtils.getField(service,"functionalRuntime"));
        ReflectionTestUtils.setField(service,"functionalRuntime",(Supplier<Hnf1aFunctionalRuntime>)()->{
            functionalRuntimeConstructions++;var runtime=spy(Objects.requireNonNull(factory.get()));
            doAnswer(call->{var result=(ObjectNode)call.callRealMethod();functionalLookups+=result.path("providerCalls").asInt();functionalEvaluations+=result.path("evaluatorCalls").asInt();return result;}).when(runtime).evaluate(any(),any());return runtime;
        });
        var reviews=new FunctionalExperimentReviews(base.mapper,base.properties.getStorageRoot().resolve("functional-experiment-reviews"),service::read,(id,operation)->base.store.withAnalysisLock(id,operation));
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service),new FunctionalExperimentReviewController(reviews)).build();
    }
    private ObjectNode declaration(ObjectNode options) {
        var mapper=smoke.base.mapper;var request=mapper.createObjectNode().put("topN",20).put("declarationVersion","ordinary-ui-v3").put("sampleId","synthetic-proband").put("dataClass","SYNTHETIC");
        request.putObject("patient").put("patientContextId","patient-context-public-functional").put("role","PROBAND").put("sex","UNKNOWN").set("source",smoke.source("functional-public-panel/case"));
        var hypotheses=request.putArray("hypotheses");var selections=request.putArray("recommendations");
        for(var row:options.path("candidates")) {
            var r=row.path("recommendation");JsonNode relation=null;
            for(var rel:r.path("relationships")) if(rel.path("selectable").asBoolean()&&"HGNC:11621".equals(rel.path("hgncId").asText())&&"MONDO:0015967".equals(rel.path("mondoId").asText())&&"AUTOSOMAL_DOMINANT".equals(PatientDiseaseContextBindingResolver.normalizeMoi(rel.path("moi").asText()))) relation=rel;
            var selection=selections.addObject();for(String f:List.of("candidateId","recommendationId","generatedAt","policyVersion","policyHash"))selection.set(f,r.path(f));
            var tx=r.path("resourceCompatibleTranscript");
            if(relation==null || !TX.equals(tx.path("versionedTranscriptId").asText())) {selection.putNull("stableRelationshipKey").putNull("annotationId");continue;}
            selection.set("stableRelationshipKey",relation.path("stableRelationshipKey"));selection.set("annotationId",tx.path("annotationId"));
            var item=hypotheses.addObject().put("affectedStatus","UNKNOWN");var h=item.putObject("hypothesis");h.set("candidateId",row.path("candidateId"));h.set("hypothesisId",r.path("hypothesisId"));
            for(String f:List.of("hgncId","mondoId","moi"))h.set(f,relation.path(f));h.set("provenance",smoke.source("functional-public-panel/explicit-hypothesis"));
            item.putObject("transcript").put("accession","ENST00000257555").put("version",11).put("annotationSource",tx.path("annotationSource").asText())
                    .put("annotationSourceVersion",tx.path("annotationSourceVersion").asText()).put("selectionPolicy",r.path("policyId").asText()+"@"+r.path("policyVersion").asText())
                    .put("selectionReason","Explicit public engineering panel confirmation; not a diagnosis").set("source",smoke.source("recommendations/"+r.path("recommendationId").asText()+"/selection"));
        }
        return request;
    }
    private ObjectNode postRun(ObjectNode request) throws Exception {
        var base=smoke.base;var response=base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).contentType("application/json").content(Objects.requireNonNull(base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
        if(response.getStatus()!=200)write("http-failure.json",base.mapper.createObjectNode().put("status",response.getStatus()).put("body",response.getContentAsString()).put("error",response.getErrorMessage()));
        assertEquals(200,response.getStatus(),response.getErrorMessage());return (ObjectNode)base.mapper.readTree(response.getContentAsByteArray());
    }
    private Map<String,Integer> calls() {return Map.of("exomiser",smoke.exomiserCalls,"vep",smoke.vepCalls,"spliceBatch",smoke.spliceBatches,"otherProvider",smoke.providerCalls,"otherEvaluator",smoke.evaluatorCalls,"functionalLookup",functionalLookups,"functionalEvaluator",functionalEvaluations,"functionalRuntimeConstruction",functionalRuntimeConstructions);}
    private void event(String label,JsonNode saved,Map<String,Integer> before) throws Exception {var delta=new TreeMap<String,Integer>();calls().forEach((k,v)->delta.put(k,v-before.get(k)));write(label+"-response.json",saved);write(label+"-event.json",smoke.base.mapper.valueToTree(delta));}
    private void write(String name,JsonNode value) throws Exception {Files.writeString(work.resolve(name),value.toPrettyString(),StandardOpenOption.CREATE_NEW);}
}
