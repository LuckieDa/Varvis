package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.impl.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/** Fixed CFTR public SNV through real engines/controllers; isolated H2, never patient data. */
class CftrSynonymousBusinessSmokeTest extends IntronicScopeBusinessSmokeTest {
    CftrSynonymousBusinessSmokeTest() {
        work=Path.of("target/bp7-cftr-acceptance").toAbsolutePath(); artifacts=work;
        vcf="##fileformat=VCFv4.2\n##reference=GRCh38\n##contig=<ID=chr7,length=159345973>\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n"
            +"chr7\t117540145\tpublic-CFTR-rs1800083\tC\tT\t.\tPASS\t.\tGT\t1/1\n";
    }
    List<Integer> positions() { return List.of(117540145); }
    String referenceAllele() { return "C"; }
    int formalCount() { return 1; }
    int evaluatedCount() { return 1; }
    boolean usePhenotype() { return true; }

    @Test @EnabledIfSystemProperty(named="varvis.bp7.cftr.phase", matches="preflight")
    void synonymousPreflight() throws Exception {
        setup();artifacts=Files.createDirectory(work.resolve("preflight-"+UUID.randomUUID()));var report=base.mapper.createObjectNode().put("status","BLOCKED").put("dataClass","SYNTHETIC");
        try {
            report.put("sampleRationale", "Synthetic homozygous single-point engineering sample, not a patient. GT 1/1 tests the existing CFTR/cystic fibrosis AR relationship HGNC:1884/MONDO:0009061 (catalog row 3511). HP:0002205 recurrent respiratory infections is an illustrative CF-compatible symptom, not patient evidence. Inputs fixed before inference; no assertion that this allele causes CF.");
            var frequency=base.mapper.readTree(Files.readAllBytes(work.resolve("frequency-query.json")));
            assertEquals("GRCh38:7:117540145:C:T",frequency.at("/alleles/0/allele").asText());
            assertEquals("PASS",frequency.at("/alleles/0/frequencyFilter").asText());
            assertTrue(new java.math.BigDecimal(frequency.at("/alleles/0/selectedMaxPercent").asText()).compareTo(new java.math.BigDecimal("0.1"))<0);
            report.set("frequency",frequency);
            report.put("vcfSha256",hash(vcf));Files.writeString(work.resolve("fixed-panel.vcf"),vcf);
            var policy=AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.STANDARD,false);
            Files.writeString(work.resolve("effective-analysis.yml"),policy.effectiveYaml());report.put("policySha256",policy.policySha256());
            var ledger=ExplicitContextSourceLedger.load(base.mapper,Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference=new NativeSnvReference(base.mapper,base.properties,new NormalizationResourceValidator(base.properties),Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")),List.of(ledger),ledger.reference("reference"));
            var refs=report.putArray("reference");for(int pos:positions()) {
                var observed=reference.observe("chr7",pos,referenceAllele());refs.add(observed.observation());assertEquals("MATCH",observed.observation().path("status").asText());
            }
            Path output=artifacts.resolve("preflight-vep.jsonl");
            var command=new VepCommandBuilder(base.properties).build(new VepCommandOptions(work.resolve("fixed-panel.vcf"),output));
            report.set("vepCommand",base.mapper.valueToTree(command));report.put("vepTimeoutSeconds",base.properties.getVep().getTimeoutSeconds());
            var outcome=vepRunner().run(command,work,Duration.ofSeconds(base.properties.getVep().getTimeoutSeconds()));Files.writeString(artifacts.resolve("vep-process.log"),outcome.getOutput());
            assertTrue(outcome.isSuccess(),outcome.getOutput());assertTrue(Files.isRegularFile(output),"VEP exited without its declared output: "+outcome.getOutput());
            var parsed=new VepJsonOutputParser(base.mapper).parseByVariantId(output,"VEP","116.0").getAnnotationsByVariantId();report.set("annotations",base.mapper.valueToTree(parsed));
            for(var rows:parsed.values()) {
                var selected=rows.stream().filter(t->"ENST00000003084.11".equals(t.getVersionedTranscriptId())).findFirst().orElseThrow();
                assertNotNull(selected.getSynonymousExonPosition(),selected.toString());assertNull(new IntronicSplicePolicy(base.mapper).blocker(selected));
            }
            assertEquals(positions().size(),parsed.size());verifyConfiguredJavaRuntime(report);
            var ready=IntronicScopeRuntimeProbe.run(base.mapper,spliceCommand(),environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-reference-fasta"),work,new SystemExternalProcessRunner());
            report.set("runtime",ready);assertEquals("READY",ready.path("status").asText(),ready.toPrettyString());report.put("status","READY");
        } finally {report.set("calls",base.mapper.valueToTree(calls()));Files.writeString(artifacts.resolve("preflight.json"),report.toPrettyString());Files.writeString(work.resolve("preflight.json"),report.toPrettyString());base.closeDatabase();}
    }
    @Test @EnabledIfSystemProperty(named="varvis.bp7.cftr.phase", matches="analysis|evaluation")
    void synonymousRealBusiness() throws Exception {
        setup();artifacts=Files.createDirectory(work.resolve("attempt-"+UUID.randomUUID()));
        if(Boolean.getBoolean("varvis.bp7.cftr.trace")) {
            var command=spliceCommand();
            for(int i=0;i<command.size();i++) if(command.get(i).endsWith("/run_spliceai_business.py"))
                command.set(i,command.get(i).replace("/run_spliceai_business.py","/trace_spliceai_business.py"));
            Path diagnostic=Files.createDirectory(artifacts.resolve("cli-diagnostic")).toRealPath();
            String path=diagnostic.toString().replace('\\','/');
            command.add("--diagnostic-directory");command.add("/mnt/"+Character.toLowerCase(path.charAt(0))+path.substring(2));
            environment.withProperty("variant-analysis.acmg-business.spliceai-raw-command-json",Objects.requireNonNull(base.mapper.writeValueAsString(command)));
        }
        var report=base.mapper.createObjectNode().put("status","BLOCKED").put("pageCovered",false);
        try {
            assertEquals("READY",base.mapper.readTree(Files.readString(work.resolve("preflight.json"))).path("status").asText());
            assertEquals(vcf,Files.readString(work.resolve("fixed-panel.vcf")));
            if ("analysis".equals(System.getProperty("varvis.bp7.cftr.phase"))) {
                var validator=new VepResourceValidator(base.properties);
                var annotation=new VepTranscriptAnnotationEnrichmentService(base.jobs,base.properties,validator,new VepCommandBuilder(base.properties),
                    new VepJsonOutputParser(base.mapper),vepRunner(),new TranscriptAnnotationService(base.properties,new AnnTranscriptAnnotationProvider(),new InMemoryTranscriptAnnotationCache(),validator));
                var enrichment=mock(VariantInterpretationService.class);when(enrichment.enrichEvidenceOnly(any(),any())).thenAnswer(a->a.getArgument(0));
                var processor=new VariantAnalysisJobProcessor(base.jobs,new PicardVcfBuildConverter(base.properties),mock(VcfNormalizer.class),mock(VcfQcService.class),enrichment,base.properties,annotation);
                ReflectionTestUtils.setField(processor,"acmgBusinessEnabled",true);processor.setAcmgExecutionSources(base.sources);
                processor.setExomiserEngineAdapter(new ConfiguredExomiserEngineAdapter(base.properties,(cmd,dir,out,err,env,timeout)->{exomiserCalls++;return new SystemExomiserProcessRunner().run(cmd,dir,out,err,env,timeout);},new ExomiserRuntimeValidator()));
                var upload=multipart("/variant-analyses")
                    .file(new MockMultipartFile("vcfFile","public-synonymous.vcf","text/plain",vcf.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("genomeAssembly","GRCh38").param("analysisMode","WES").param("analysisScope","STANDARD")
                    .param("sampleId","synthetic-proband");
                if(usePhenotype()) upload.param("hpoTerms","HP:0002205");
                var response=base.mapper.readTree(analysisHttp(analysisService(processor)).perform(upload).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray());
                base.analysisId=response.path("id").asText();Files.writeString(work.resolve("analysis-id.txt"),base.analysisId);
            } else base.analysisId=Files.readString(work.resolve("analysis-id.txt")).trim();
            report.put("analysisId",base.analysisId);
            var job=base.jobs.findById(base.analysisId).orElseThrow();assertEquals(VariantAnalysisStatus.READY,job.getStatus(),job.getErrorMessage());
            assertEquals("STANDARD",AnalysisScopePolicy.publicDetails(job).get("scope"));
            var candidates=base.jobs.findInterpretations(base.analysisId,0,20);report.set("formalCandidates",base.mapper.valueToTree(candidates));assertEquals(formalCount(),candidates.size());
            assertEquals(new HashSet<>(positions()),candidates.stream().map(c->c.getObservation().getKey().getPosition()).collect(java.util.stream.Collectors.toSet()));
            base.closeDatabase();base.recreateRepositories();wireBusiness();
            var options=base.service.inputOptions(base.analysisId,20);Files.writeString(artifacts.resolve("input-options.json"),options.toPrettyString());
            var request=declaration(options);
            String retry=System.getProperty("varvis.bp7.cftr.retryOf");if(retry!=null)request.put("retryOf",retry);
            Files.writeString(artifacts.resolve("declaration.json"),request.toPrettyString());
            if(retry==null)Files.writeString(work.resolve("declaration.json"),request.toPrettyString());
            if("analysis".equals(System.getProperty("varvis.bp7.cftr.phase"))) {report.put("status","FORMAL_CANDIDATES_READY");return;}
            assertEquals(evaluatedCount(),request.path("hypotheses").size(),"Exactly the predeclared CFTR AR control must be executable");
            var before=calls();var saved=observedPost(request,"first-post");Files.writeString(artifacts.resolve("saved-business.json"),saved.toPrettyString());
            report.set("firstIncrement",base.mapper.valueToTree(increment(before)));report.put("runId",saved.path("runId").asText());
            base.closeDatabase();base.recreateRepositories();wireBusiness();
            before=calls();var read=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            recordHttp("rebuilt-get","GET","/variant-analyses/"+base.analysisId+"/acmg/runs/"+saved.path("runId").asText(),null,read,increment(before));assertEquals(saved,read);assertTrue(increment(before).values().stream().allMatch(n->n==0));
            before=calls();var repeated=observedPost(request,"repeat-post");assertEquals(saved,repeated);assertTrue(increment(before).values().stream().allMatch(n->n==0));
            report.put("rebuildReadAndRepeatIdentical",true);assertEquals("COMPLETED",saved.path("status").asText());assertEquals(1,spliceBatches);
            int evaluatedControls=0;
            for(var candidate:saved.path("candidates")) {
                var attempt=candidate.path("attempts").get(0);assertNotNull(attempt);
                if (!attempt.path("transcriptInput").isObject()) {assertFalse(attempt.path("blockers").isEmpty());continue;}
                evaluatedControls++;assertEquals("SPLICEAI_SYNONYMOUS",attempt.at("/transcriptInput/predictionPath").asText());
                long pos=attempt.at("/transcriptInput/synonymousExonPosition/position").asLong();
                var evaluations=attempt.path("criterionEvaluations");assertEquals(3,evaluations.size());
                assertEquals(117540145,pos);
                assertTrue(List.of("MET","NOT_MET","NOT_EVALUATED").contains(evaluations.get(2).at("/evaluationResult/status").asText()));
                report.set("actualEvaluations",evaluations);

            }
            assertEquals(evaluatedCount(),evaluatedControls);
            verifyExperimentChecks(saved,report);
            report.put("status","REAL_CFTR_BUSINESS_VERIFIED");
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;
        } finally {report.set("calls",base.mapper.valueToTree(calls()));Files.writeString(artifacts.resolve("report.json"),report.toPrettyString());System.out.println("SYNONYMOUS_ATTEMPT="+artifacts);base.closeDatabase();}
    }

    void verifyExperimentChecks(ObjectNode saved,ObjectNode report) throws Exception {
        var before=calls();ObjectNode first=null;
        String run=saved.path("runId").asText();
        for(int phase=0;phase<2;phase++) {
            if(phase==1) {base.closeDatabase();base.recreateRepositories();wireBusiness();}
            var checks=new SplicingExperimentChecks(base.mapper,new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"),work.resolve("checks"),base.service::read);
            var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new org.nbscn.varvis.variantanalysis.controller.SplicingExperimentCheckController(checks)).build();
            String url="/variant-analyses/"+base.analysisId+"/acmg/runs/"+run+"/splicing-checks";
            var check=(ObjectNode)base.mapper.readTree(http.perform(post(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            if(first==null)first=check;else assertEquals(first,check);
            for(String suffix:List.of("","/export")) assertEquals(check,base.mapper.readTree(http.perform(get(url+"/"+check.path("checkId").asText()+suffix)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
            assertEquals(2,check.path("checks").size());
            for(var row:check.path("checks")) assertEquals(117540145,row.at("/context/variant/inputAllele/position").asInt());
            assertEquals(saved,base.service.read(base.analysisId,run));
            Files.writeString(artifacts.resolve("experiment-check-phase-"+phase+".json"),check.toPrettyString());
        }
        report.set("experimentCheck",first);report.set("checkIncrement",base.mapper.valueToTree(increment(before)));
        assertTrue(increment(before).values().stream().allMatch(n->n==0));
    }
    ObjectNode declaration(ObjectNode options) {
        var request=base.mapper.createObjectNode().put("topN",20).put("declarationVersion","ordinary-ui-v3").put("sampleId","synthetic-proband").put("dataClass","SYNTHETIC");
        request.putObject("patient").put("patientContextId","patient-context-public-synonymous-cftr").put("role","PROBAND").put("sex","UNKNOWN").set("source",source("public-panel/case"));
        var hypotheses=request.putArray("hypotheses");var selections=request.putArray("recommendations");
        for(var row:options.path("candidates")) {
            var r=row.path("recommendation");JsonNode relation=null;
            for(var rel:r.path("relationships")) if(rel.path("selectable").asBoolean() && "HGNC:1884".equals(rel.path("hgncId").asText())
                    && "MONDO:0009061".equals(rel.path("mondoId").asText())
                    && "AUTOSOMAL_RECESSIVE".equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rel.path("moi").asText()))){assertNull(relation);relation=rel;}
            // Only the independently declared CFTR AR catalog context is selected; retain other formal rows with explicit missing input.
            var selection=selections.addObject();for(String field:List.of("candidateId","recommendationId","policyVersion","policyHash","generatedAt"))selection.set(field,r.path(field));
            selection.set("stableRelationshipKey",relation==null?base.mapper.nullNode():relation.path("stableRelationshipKey"));
            var tx=r.path("resourceCompatibleTranscript");selection.set("annotationId",tx.isObject()?tx.path("annotationId"):base.mapper.nullNode());
            assertEquals("ENST00000003084.11",tx.path("versionedTranscriptId").asText(),"Pinned transcript missing; do not silently select another");
            assertEquals("SPLICEAI_SYNONYMOUS",tx.path("predictionPath").asText());
            if(relation==null||!tx.isObject())continue;
            var item=hypotheses.addObject().put("affectedStatus","UNKNOWN");var h=item.putObject("hypothesis");
            h.set("candidateId",row.path("candidateId"));h.set("hypothesisId",r.path("hypothesisId"));
            for(String field:List.of("hgncId","mondoId","moi"))h.set(field,relation.path(field));h.set("provenance",source("public-panel/explicit-catalog-hypothesis"));
            String versioned=tx.path("versionedTranscriptId").asText();int dot=versioned.lastIndexOf('.');
            item.putObject("transcript").put("accession",versioned.substring(0,dot)).put("version",Integer.parseInt(versioned.substring(dot+1)))
                    .put("annotationSource",tx.path("annotationSource").asText()).put("annotationSourceVersion",tx.path("annotationSourceVersion").asText())
                    .put("selectionPolicy",r.path("policyId").asText()+"@"+r.path("policyVersion").asText())
                    .put("selectionReason","Explicit public engineering panel confirmation of source-backed VEP preference before model scores; not a clinical diagnosis")
                    .set("source",source("recommendations/"+r.path("recommendationId").asText()+"/selection"));
        }
        return request;
    }
}
