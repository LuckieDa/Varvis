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


/** Opt-in public controls; actual configured engines and controller dispatch; isolated file H2. */
class SynonymousBusinessSmokeTest extends IntronicScopeBusinessSmokeTest {
    SynonymousBusinessSmokeTest() {
        work=Path.of("target/bp7-synonymous-acceptance").toAbsolutePath(); artifacts=work;
        vcf="##fileformat=VCFv4.2\n##reference=GRCh38\n##contig=<ID=chr1,length=248956422>\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n"
            +"chr1\t156138613\tClinVar-14500-negative\tC\tT\t.\tPASS\t.\tGT\t0/1\n"
            +"chr1\t156138697\tClinVar-66875-positive\tC\tT\t.\tPASS\t.\tGT\t0/1\n";
    }
    List<Integer> positions() { return List.of(156138613,156138697); }
    String referenceAllele() { return "C"; }
    int formalCount() { return 4; }
    int evaluatedCount() { return 2; }
    boolean usePhenotype() { return true; }
    String expectedBp7(long position) { return position==156138697?"MET":"NOT_MET"; }
    @Test @EnabledIfSystemProperty(named="varvis.bp7.syn.phase", matches="preflight")
    void synonymousPreflight() throws Exception {
        setup();var report=base.mapper.createObjectNode().put("status","BLOCKED").put("dataClass","SYNTHETIC");
        try {
            report.put("vcfSha256",hash(vcf));Files.writeString(work.resolve("fixed-panel.vcf"),vcf);
            var policy=AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.STANDARD,false);
            Files.writeString(work.resolve("effective-analysis.yml"),policy.effectiveYaml());report.put("policySha256",policy.policySha256());
            var ledger=ExplicitContextSourceLedger.load(base.mapper,Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference=new NativeSnvReference(base.mapper,base.properties,new NormalizationResourceValidator(base.properties),Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")),List.of(ledger),ledger.reference("reference"));
            var refs=report.putArray("reference");for(int pos:positions()) {
                var observed=reference.observe("chr1",pos,referenceAllele());refs.add(observed.observation());assertEquals("MATCH",observed.observation().path("status").asText());
            }
            Path output=work.resolve("preflight-vep.jsonl");
            var command=new VepCommandBuilder(base.properties).build(new VepCommandOptions(work.resolve("fixed-panel.vcf"),output));
            report.set("vepCommand",base.mapper.valueToTree(command));
            var outcome=vepRunner().run(command,work,Duration.ofMinutes(3));Files.writeString(work.resolve("vep-process.log"),outcome.getOutput());
            assertTrue(outcome.isSuccess(),outcome.getOutput());assertTrue(Files.isRegularFile(output),"VEP exited without its declared output: "+outcome.getOutput());
            var parsed=new VepJsonOutputParser(base.mapper).parseByVariantId(output,"VEP","116.0").getAnnotationsByVariantId();report.set("annotations",base.mapper.valueToTree(parsed));
            for(var rows:parsed.values()) {
                var selected=rows.stream().filter(t->"ENST00000368300.9".equals(t.getVersionedTranscriptId())).findFirst().orElseThrow();
                assertNotNull(selected.getSynonymousExonPosition(),selected.toString());assertNull(new IntronicSplicePolicy(base.mapper).blocker(selected));
            }
            assertEquals(positions().size(),parsed.size());verifyConfiguredJavaRuntime(report);
            var ready=IntronicScopeRuntimeProbe.run(base.mapper,spliceCommand(),environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-reference-fasta"),work,new SystemExternalProcessRunner());
            report.set("runtime",ready);assertEquals("READY",ready.path("status").asText(),ready.toPrettyString());report.put("status","READY");
        } finally {report.set("calls",base.mapper.valueToTree(calls()));Files.writeString(work.resolve("preflight.json"),report.toPrettyString());base.closeDatabase();}
    }
    @Test @EnabledIfSystemProperty(named="varvis.bp7.syn.phase", matches="analysis|evaluation")
    void synonymousRealBusiness() throws Exception {
        setup();artifacts=Files.createDirectory(work.resolve("attempt-"+UUID.randomUUID()));
        var report=base.mapper.createObjectNode().put("status","BLOCKED").put("pageCovered",false);
        try {
            assertEquals("READY",base.mapper.readTree(Files.readString(work.resolve("preflight.json"))).path("status").asText());
            assertEquals(vcf,Files.readString(work.resolve("fixed-panel.vcf")));
            if ("analysis".equals(System.getProperty("varvis.bp7.syn.phase"))) {
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
                if(usePhenotype()) upload.param("hpoTerms","HP:0001644");
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
            var request=declaration(options);Files.writeString(work.resolve("declaration.json"),request.toPrettyString());
            if("analysis".equals(System.getProperty("varvis.bp7.syn.phase"))) {report.put("status","FORMAL_CANDIDATES_READY");return;}
            assertEquals(evaluatedCount(),request.path("hypotheses").size(),"Exactly the two predeclared AD controls must be executable");
            var before=calls();var saved=observedPost(request,"first-post");Files.writeString(artifacts.resolve("saved-business.json"),saved.toPrettyString());
            report.set("firstIncrement",base.mapper.valueToTree(increment(before)));report.put("runId",saved.path("runId").asText());
            base.closeDatabase();base.recreateRepositories();wireBusiness();
            before=calls();var read=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            recordHttp("rebuilt-get","GET","/variant-analyses/"+base.analysisId+"/acmg/runs/"+saved.path("runId").asText(),null,read,increment(before));assertEquals(saved,read);assertTrue(increment(before).values().stream().allMatch(n->n==0));
            before=calls();var repeated=observedPost(request,"repeat-post");assertEquals(saved,repeated);assertTrue(increment(before).values().stream().allMatch(n->n==0));
            report.put("rebuildReadAndRepeatIdentical",true);assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());assertEquals(1,spliceBatches);
            int evaluatedControls=0;
            for(var candidate:saved.path("candidates")) {
                var attempt=candidate.path("attempts").get(0);assertNotNull(attempt);
                if (!attempt.path("transcriptInput").isObject()) {assertFalse(attempt.path("blockers").isEmpty());continue;}
                evaluatedControls++;assertEquals("SPLICEAI_SYNONYMOUS",attempt.at("/transcriptInput/predictionPath").asText());
                long pos=attempt.at("/transcriptInput/synonymousExonPosition/position").asLong();
                var evaluations=attempt.path("criterionEvaluations");assertEquals(3,evaluations.size());
                assertEquals(expectedBp7(pos),evaluations.get(2).at("/evaluationResult/status").asText());
                if("NOT_EVALUATED".equals(expectedBp7(pos))) assertEquals("NOT_APPLICABLE",evaluations.get(2).at("/criterionApplicability/status").asText());
                if(pos==156138697)assertEquals("SUPPORTING",evaluations.get(2).path("appliedStrength").asText());
            }
            assertEquals(evaluatedCount(),evaluatedControls);report.put("status","REAL_SYNONYMOUS_BP7_VERIFIED");
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;
        } finally {report.set("calls",base.mapper.valueToTree(calls()));Files.writeString(artifacts.resolve("report.json"),report.toPrettyString());System.out.println("SYNONYMOUS_ATTEMPT="+artifacts);base.closeDatabase();}
    }
    ObjectNode declaration(ObjectNode options) {
        var request=base.mapper.createObjectNode().put("topN",20).put("declarationVersion","ordinary-ui-v3").put("sampleId","synthetic-proband").put("dataClass","SYNTHETIC");
        request.putObject("patient").put("patientContextId","patient-context-public-synonymous-lmna").put("role","PROBAND").put("sex","UNKNOWN").set("source",source("public-panel/case"));
        var hypotheses=request.putArray("hypotheses");var selections=request.putArray("recommendations");
        for(var row:options.path("candidates")) {
            var r=row.path("recommendation");JsonNode relation=null;
            for(var rel:r.path("relationships")) if(rel.path("selectable").asBoolean() && "HGNC:6636".equals(rel.path("hgncId").asText())
                    && "MONDO:0007269".equals(rel.path("mondoId").asText())
                    && "AUTOSOMAL_DOMINANT".equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rel.path("moi").asText()))){assertNull(relation);relation=rel;}
            // Only the independently declared AD catalog context is selected; retain other formal rows with explicit missing input.
            var selection=selections.addObject();for(String field:List.of("candidateId","recommendationId","policyVersion","policyHash","generatedAt"))selection.set(field,r.path(field));
            selection.set("stableRelationshipKey",relation==null?base.mapper.nullNode():relation.path("stableRelationshipKey"));
            var tx=r.path("resourceCompatibleTranscript");selection.set("annotationId",tx.isObject()?tx.path("annotationId"):base.mapper.nullNode());
            assertEquals("ENST00000368300.9",tx.path("versionedTranscriptId").asText(),"Pinned transcript missing; do not silently select another");
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
