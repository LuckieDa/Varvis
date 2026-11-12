package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.*;
import org.nbscn.varvis.variantanalysis.controller.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.impl.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fixed public panel; real upload/service/Exomiser/VEP/REF/SpliceAI/gnomAD, file H2, synthetic case declarations. */
class IntronicScopeBusinessSmokeTest {
    static final String VCF = "##fileformat=VCFv4.2\n##reference=GRCh38\n##contig=<ID=chr11,length=135086622>\n##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n"
            +"chr11\t5226820\tpublic-hbb-intron-minus21\tC\tT\t.\tPASS\t.\tGT\t1/1\n";
    Path work;
    String vcf, allele, hgvsc, ref;
    int position;
    IntronicScopeBusinessSmokeTest() { configure(Path.of("target/e4-b3-bp7-acceptance"),false); }
    static IntronicScopeBusinessSmokeTest positiveAt(Path directory) {
        var smoke=new IntronicScopeBusinessSmokeTest();smoke.configure(directory,true);return smoke;
    }
    private void configure(Path directory,boolean positive) {
        work=directory.toAbsolutePath();artifacts=work;
        position=positive?5225756:5226820;ref=positive?"T":"C";
        allele=positive?"GRCh38:11:5225756:T:G":"GRCh38:11:5226820:C:T";
        hgvsc=positive?"ENST00000335295.4:c.316-30A>C":"ENST00000335295.4:c.93-21G>A";
        vcf=positive?VCF.replace("5226820","5225756").replace("public-hbb-intron-minus21","public-hbb-intron-minus30").replace("\tC\tT\t","\tT\tG\t"):VCF;
        panelPath=Path.of(positive?"../scripts/e4_b3/bp7-positive-control-v1.json":"../scripts/e4_b3/bp7-public-panel-v1.json");
    }
    Path artifacts;
    final AcmgBusinessIntegrationTest base=new AcmgBusinessIntegrationTest();
    MockEnvironment environment;
    int exomiserCalls, vepCalls;
    int providerCalls, evaluatorCalls, spliceBatches;
    ObjectNode rawSpliceResult;
    Path panelPath;
    String hash(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    ObjectNode configurationProof() throws Exception {
        var proof=base.mapper.createObjectNode();
        // Private acceptance artifact only; never returned through a product API.
        for (String suffix:List.of("enabled","binding-directory","resource-root","reference-receipt-file","reference-receipt-sha256",
                "reference-receipt-locator","spliceai-raw-command-json","spliceai-reference-fasta","spliceai-timeout-seconds"))
            proof.put(suffix,environment.getRequiredProperty("variant-analysis.acmg-business."+suffix));
        proof.set("exomiser",base.mapper.valueToTree(base.properties.getExomiser()));
        proof.set("vep",base.mapper.valueToTree(base.properties.getVep()));
        return proof;
    }
    List<String> spliceCommand() throws Exception {
        return new ArrayList<>(base.mapper.readValue(environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-raw-command-json"),
                base.mapper.getTypeFactory().constructCollectionType(List.class,String.class)));
    }
    Map<String,Integer> calls() { return Map.of("provider",providerCalls,"evaluator",evaluatorCalls,"spliceBatch",spliceBatches,"exomiser",exomiserCalls,"vep",vepCalls); }
    Map<String,Integer> increment(Map<String,Integer> before) {
        var delta=new TreeMap<String,Integer>();calls().forEach((key,value)->delta.put(key,value-before.get(key)));return delta;
    }
    private static @org.springframework.lang.NonNull Object requiredField(Object target, String name) {
        return Objects.requireNonNull(ReflectionTestUtils.getField(Objects.requireNonNull(target), Objects.requireNonNull(name)), name);
    }
    @SuppressWarnings("unchecked")
    void verifyConfiguredJavaRuntime(ObjectNode report) {
        var service=new AcmgBusinessConfig().acmgBusinessService(base.mapper,base.jobs,base.store,base.sources,base.properties,environment);
        var factory=(Supplier<ExplicitCaseInputCoordinator>)requiredField(service,"runtime");
        var dispatcher=requiredField(factory.get(),"dispatcher");
        var spliceFactory=(Supplier<SpliceAiBusinessRuntime>)requiredField(dispatcher,"spliceRuntime");
        assertNotNull(spliceFactory.get());
        report.put("configuredJavaRuntimeConstructed",true).put("readinessModelInvocations",0);
    }
    void setup() throws Exception {
        Files.createDirectories(work);base.temporary=work;base.setup();
        var configured=new Properties();try(var in=Files.newInputStream(Path.of("config/variant-analysis-local.properties"))){configured.load(in);}
        var allowed=new Properties();configured.forEach((k,v)->{ if(k.toString().startsWith("variant-analysis.")) allowed.put(k,v); });
        environment=new MockEnvironment().withProperty("VARVIS_RESOURCE_ROOT",Objects.requireNonNull(System.getProperty("varvis.e4b3.resourceRoot"), "varvis.e4b3.resourceRoot is required"));
        environment.getPropertySources().addLast(new PropertiesPropertySource("existing-local-variant-settings",allowed));
        Binder.get(environment).bind("variant-analysis",Bindable.ofInstance(base.properties));
        base.properties.setStorageRoot(work.resolve("jobs"));base.properties.getPersistence().setTablePrefix("");
        base.properties.getPersistence().setRetryBackoff(Duration.ZERO);
        assertTrue(base.properties.getVep().isEnabled());assertTrue(base.properties.getVep().isExecutionEnabled());
        base.recreateRepositories();
    }
    ExternalProcessRunner vepRunner() { return (command,directory,timeout)->{
        assertTrue(System.getProperty("varvis.e4b3.scopeResumeAnalysisId","").isBlank(),"Resume must never execute VEP again");
        vepCalls++;return new SystemExternalProcessRunner().run(command,directory,timeout);
    }; }
    ObjectNode savedEvidenceProof(ObjectNode report) throws Exception {
        Path manifest=work.resolve("artifact-manifest.json");
        String expected=System.getProperty("varvis.e4b3.scopeEvidenceManifestSha256","");
        assertTrue(expected.matches("[a-f0-9]{64}"),"Supply the previously recorded evidence manifest SHA-256");
        assertEquals(expected,IntronicScopeRuntimeProbe.sha(manifest));
        var inventory=base.mapper.readTree(Files.readString(manifest));
        for(String name:List.of("preflight-report.json","effective-analysis.yml","frequency-query.json","splice-readiness-initial.json","blocked-runtime-saved.json")) {
            JsonNode entry=null;for(var row:inventory.path("artifacts"))if(name.equals(row.path("file").asText())){assertNull(entry);entry=row;}
            assertNotNull(entry);assertEquals(entry.path("sha256").asText(),IntronicScopeRuntimeProbe.sha(work.resolve(name)),name);
        }
        var proof=(ObjectNode)base.mapper.readTree(Files.readString(work.resolve("preflight-report.json")));
        assertEquals(hash(Files.readString(panelPath)),proof.path("panelSha256").asText());
        assertEquals(vcf,Files.readString(work.resolve("fixed-public-panel.vcf")));assertEquals(hash(vcf),proof.path("vcfSha256").asText());
        assertEquals(configurationProof(),proof.path("businessConfiguration"));
        var policy=AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false);
        assertEquals(policy.policySha256(),proof.path("policySha256").asText());assertEquals(policy.effectiveYaml(),Files.readString(work.resolve("effective-analysis.yml")));
        assertEquals("MATCH",proof.at("/referenceObservations/0/status").asText());
        boolean selected=false;for(var rows:proof.path("annotations"))for(var tx:rows)if(matchesSavedTranscript(tx))selected=true;
        assertTrue(selected,"Saved VEP evidence does not contain the fixed selected transcript");
        var ledger=ExplicitContextSourceLedger.load(base.mapper,Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
        var receipt=ledger.resolve(ledger.reference("reference")).path("actualChecksumReceipt");
        Path fasta=base.properties.getNormalization().referenceFor(GenomeAssembly.GRCh38).toAbsolutePath().normalize();
        assertEquals(Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")).resolve(receipt.path("logicalName").asText()).toAbsolutePath().normalize(),fasta);
        assertEquals(receipt.path("byteLength").asLong(),Files.size(fasta));
        assertEquals(receipt.path("mtimeNs").asLong(),Files.getLastModifiedTime(fasta).to(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(receipt.path("indexSha256").asText(),IntronicScopeRuntimeProbe.sha(Path.of(fasta+".fai")));
        assertEquals(receipt.path("observedSha256").asText(),base.properties.getNormalization().referenceSha256For(GenomeAssembly.GRCh38));
        // Native REF validation and VEP/SpliceAI have separately registered GRCh38 references.
        // Verify each against its own accepted identity, without equating their files or hashes.
        Path rawFasta=Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-reference-fasta"));
        var rawIdentity=base.mapper.readTree(Files.readString(work.resolve("splice-readiness-initial.json"))).path("reference");
        assertEquals("GRCh38",rawIdentity.path("assembly").asText());assertEquals("GRCh38",receipt.path("assembly").asText());
        assertTrue(Files.isRegularFile(rawFasta));assertEquals(rawIdentity.path("byteLength").asLong(),Files.size(rawFasta));
        assertEquals(rawIdentity.path("indexSha256").asText(),IntronicScopeRuntimeProbe.sha(Path.of(rawFasta+".fai")));
        Path database=Path.of(proof.at("/businessConfiguration/exomiser/dataDirectory").asText()).resolve("2602_hg38/2602_hg38_variants.mv.db");
        assertEquals(proof.at("/frequencyPrecheck/databaseBytes").asLong(),Files.size(database));
        report.put("evidenceManifestSha256",expected).put("savedPreflightSha256",IntronicScopeRuntimeProbe.sha(work.resolve("preflight-report.json")))
                .put("refEvidenceReused",true).put("vepEvidenceReused",true).put("fullReferenceHashComputed",false);
        report.set("referenceReceipt",receipt);report.set("spliceReferenceIdentity",rawIdentity);report.set("reusedReferenceObservations",proof.path("referenceObservations"));
        return proof;
    }
    boolean matchesSavedTranscript(JsonNode saved) throws Exception {
        var tx=base.mapper.treeToValue(saved,TranscriptConsequence.class);
        return "ENST00000335295.4".equals(tx.getVersionedTranscriptId())&&hgvsc.equals(tx.getHgvsc())
                &&"VEP".equals(tx.getSource())&&new IntronicSplicePolicy(base.mapper).blocker(tx)==null;
    }
    ObjectNode scriptIdentities() throws Exception {
        var identities=base.mapper.createObjectNode();
        for(String path:List.of("scripts/e3_c2r/run_spliceai_raw_batch_wsl.sh","scripts/e4_b3/run_spliceai_business.py","scripts/e3_c2r/spliceai_raw_resource_probe.py","docs/execution/E3_C2R_SPLICEAI_RAW_RESOURCE_PROFILE.json"))
            identities.put(path,IntronicScopeRuntimeProbe.sha(Path.of("..").resolve(path)));
        return identities;
    }
    @Test @EnabledIfSystemProperty(named="varvis.e4b3.scopeRuntimePreflight", matches="true")
    void runtimeOnlyPreflightReusesVerifiedSavedEvidence() throws Exception {
        setup();Path output=work.resolve("runtime-preflight-"+UUID.randomUUID()+".json");
        var report=base.mapper.createObjectNode().put("status","BLOCKED").put("dataClass","SYNTHETIC").put("createdAt",java.time.Instant.now().toString());
        try {
            var proof=savedEvidenceProof(report);
            for(String key:List.of("panelSha256","vcfSha256","policySha256","businessConfiguration"))report.set(key,proof.path(key));
            report.set("scriptIdentities",scriptIdentities());verifyConfiguredJavaRuntime(report);
            var runtime=IntronicScopeRuntimeProbe.run(base.mapper,spliceCommand(),environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-reference-fasta"),work,new SystemExternalProcessRunner());
            report.set("runtimeProbe",runtime);assertEquals("READY",runtime.path("status").asText(),runtime.toPrettyString());
            assertEquals(base.mapper.readTree(Files.readString(work.resolve("splice-readiness-initial.json"))),runtime.path("result"),"Fresh runtime/resource identity differs from accepted identity");
            report.put("status","READY");
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;
        } finally {
            report.set("observedCalls",base.mapper.valueToTree(calls()));Files.writeString(output,report.toPrettyString(),StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_PREFLIGHT_REPORT="+output);base.closeDatabase();
        }
    }
    @Test @EnabledIfSystemProperty(named="varvis.e4b3.scopePreflight", matches="true")
    void preflightFixedAllelesBeforeExomiser() throws Exception {
        setup();var report=base.mapper.createObjectNode().put("dataClass","SYNTHETIC").put("inputVariantCount",1).put("status","BLOCKED");
        try {
            report.put("panelSha256",hash(Files.readString(panelPath))).put("vcfSha256",hash(vcf));
            report.set("businessConfiguration",configurationProof());
            verifyConfiguredJavaRuntime(report);
            var frequency=base.mapper.readTree(Files.readString(work.resolve("frequency-query.json")));
            assertEquals("15.1.0",base.properties.getExomiser().getVersion());assertEquals("2602",base.properties.getExomiser().getDataVersion());
            var frequencyRow=java.util.stream.StreamSupport.stream(frequency.path("alleles").spliterator(),false)
                    .filter(r->allele.equals(r.path("allele").asText())).findFirst().orElseThrow();
            assertEquals("PASS",frequencyRow.path("frequencyFilter").asText());
            assertTrue(new java.math.BigDecimal(frequencyRow.path("selectedMaxPercent").asText()).compareTo(new java.math.BigDecimal("0.1"))<0);
            report.set("frequencyPrecheck",frequency);
            var policy=AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false);
            Files.writeString(work.resolve("effective-analysis.yml"),policy.effectiveYaml());
            report.put("policySha256",policy.policySha256());
            var ledger=ExplicitContextSourceLedger.load(base.mapper,Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),environment.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var reference=new NativeSnvReference(base.mapper,base.properties,new NormalizationResourceValidator(base.properties),
                    Path.of(environment.getRequiredProperty("variant-analysis.acmg-business.resource-root")),List.of(ledger),ledger.reference("reference"));
            var refs=report.putArray("referenceObservations");
            var observation=reference.observe("chr11",position,ref);refs.add(observation.observation());assertEquals("MATCH",observation.observation().path("status").asText());
            Path input=work.resolve("fixed-public-panel.vcf"),output=work.resolve("preflight-vep.jsonl");Files.writeString(input,vcf);
            var outcome=vepRunner().run(new VepCommandBuilder(base.properties).build(new VepCommandOptions(input,output)),work,Duration.ofMinutes(3));
            assertTrue(outcome.isSuccess(),outcome.getOutput());
            var parsed=new VepJsonOutputParser(base.mapper).parseByVariantId(output,"VEP","116.0").getAnnotationsByVariantId();
            report.set("annotations",base.mapper.valueToTree(parsed));
            assertTrue(parsed.values().stream().flatMap(rows -> rows.stream()).anyMatch(t->"ENST00000335295.4".equals(t.getVersionedTranscriptId())
                    && hgvsc.equals(t.getHgvsc()) && new IntronicSplicePolicy(base.mapper).blocker(t)==null));
            var runtime=IntronicScopeRuntimeProbe.run(base.mapper,spliceCommand(),environment.getRequiredProperty("variant-analysis.acmg-business.spliceai-reference-fasta"),work,new SystemExternalProcessRunner());
            report.set("runtimeProbe",runtime);assertEquals("READY",runtime.path("status").asText(),runtime.toPrettyString());report.set("runtimeReadiness",runtime.path("result"));
            report.put("status","READY");
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;
        } finally {report.put("vepProcessCalls",vepCalls);Files.writeString(work.resolve("preflight-report.json"),report.toPrettyString());base.closeDatabase();}
    }
    @Test @EnabledIfSystemProperty(named="varvis.e4b3.scopeReal", matches="true")
    void ordinaryScopeUploadExecuteSaveRecreateAndReuse() throws Exception {
        setup();artifacts=Files.createDirectory(work.resolve("business-attempt-"+UUID.randomUUID()));
        var report=base.mapper.createObjectNode().put("dataClass","SYNTHETIC").put("status","BLOCKED");long start=System.nanoTime();
        try {
            String runtimeProof=System.getProperty("varvis.e4b3.scopeRuntimeProof","");
            var proof=base.mapper.readTree(Files.readString(runtimeProof.isBlank()?work.resolve("preflight-report.json"):Path.of(runtimeProof)));
            assertEquals("READY",proof.path("status").asText());
            if(!runtimeProof.isBlank()) {
                savedEvidenceProof(report);verifyConfiguredJavaRuntime(report);
                assertEquals(report.path("evidenceManifestSha256"),proof.path("evidenceManifestSha256"));
                assertEquals(report.path("savedPreflightSha256"),proof.path("savedPreflightSha256"));
                assertEquals(scriptIdentities(),proof.path("scriptIdentities"));
                assertEquals("READY",proof.at("/runtimeProbe/status").asText());
                assertEquals(proof.at("/runtimeProbe/outputSha256").asText(),IntronicScopeRuntimeProbe.sha(Path.of(proof.at("/runtimeProbe/outputFile").asText())));
                report.put("runtimePreflightFile",runtimeProof).put("runtimePreflightSha256",IntronicScopeRuntimeProbe.sha(Path.of(runtimeProof)));
            }
            assertEquals(proof.path("policySha256").asText(),AnalysisScopePolicy.resolve(base.properties.getExomiser(),AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false).policySha256());
            assertEquals(vcf,Files.readString(work.resolve("fixed-public-panel.vcf")));
            assertEquals(hash(vcf),proof.path("vcfSha256").asText());assertEquals(hash(Files.readString(panelPath)),proof.path("panelSha256").asText());
            assertEquals(configurationProof(),proof.path("businessConfiguration"),"Business configuration changed since preflight");
            var validator=new VepResourceValidator(base.properties);
            var annotation=new VepTranscriptAnnotationEnrichmentService(base.jobs,base.properties,validator,new VepCommandBuilder(base.properties),
                    new VepJsonOutputParser(base.mapper),vepRunner(),new TranscriptAnnotationService(base.properties,new AnnTranscriptAnnotationProvider(),new InMemoryTranscriptAnnotationCache(),validator));
            var enrichment=mock(VariantInterpretationService.class);when(enrichment.enrichEvidenceOnly(any(),any())).thenAnswer(a->a.getArgument(0));
            var processor=new VariantAnalysisJobProcessor(base.jobs,new PicardVcfBuildConverter(base.properties),mock(VcfNormalizer.class),mock(VcfQcService.class),enrichment,base.properties,annotation);
            org.springframework.test.util.ReflectionTestUtils.setField(processor,"acmgBusinessEnabled",true);
            processor.setAcmgExecutionSources(base.sources);
            processor.setExomiserEngineAdapter(new ConfiguredExomiserEngineAdapter(base.properties,(cmd,dir,out,err,env,timeout)->{
                assertTrue(System.getProperty("varvis.e4b3.scopeResumeAnalysisId","").isBlank(),"Resume must reuse the persisted analysis, never execute Exomiser again");
                exomiserCalls++;return new SystemExomiserProcessRunner().run(cmd,dir,out,err,env,timeout);
            },new ExomiserRuntimeValidator()));
            var service=analysisService(processor);
            var upload=analysisHttp(service);
            var jobResponse=base.mapper.readTree(upload.perform(multipart("/variant-analyses").file(new MockMultipartFile("vcfFile","public-intronic-scope.vcf","text/plain",vcf.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("genomeAssembly","GRCh38").param("analysisMode","WES").param("analysisScope","INCLUDE_INTRONS").param("sampleId","synthetic-proband"))
                    .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray());
            base.analysisId=jobResponse.path("id").asText();
            String resumeId=System.getProperty("varvis.e4b3.scopeResumeAnalysisId","");
            if(!resumeId.isBlank()){assertEquals(resumeId,base.analysisId);assertEquals(0,exomiserCalls);report.put("resumedAnalysisId",resumeId);}
            report.put("analysisId",base.analysisId);
            var job=base.jobs.findById(base.analysisId).orElseThrow();
            assertEquals(VariantAnalysisStatus.READY,job.getStatus(),job.getErrorMessage());
            assertEquals("INCLUDE_INTRONS",AnalysisScopePolicy.publicDetails(job).get("scope"));
            var candidates=base.jobs.findInterpretations(base.analysisId,0,20);report.set("formalCandidates",base.mapper.valueToTree(candidates));report.put("candidateCount",candidates.size());
            assertEquals(1,candidates.size(),"Fixed public allele did not reach formal Top-N");
            assertEquals(allele.replace(':','|'),candidates.get(0).getObservation().getKey().canonicalId().replace("chr11","11"));
            if ("GRCh38:11:5225756:T:G".equals(allele)) {
                var features=candidates.get(0).getExomiserCandidateFeatures();
                assertTrue(AnalysisScopePolicy.read(job).supplementsZeroPassed());
                assertEquals("result.jsonl",features.getVariantArtifactName());
                assertEquals(0.0,features.getVariantScore());assertEquals(0.0,features.getGeneCombinedScore());
                assertEquals(0.0,candidates.get(0).getRankingScore());assertEquals("AUTOSOMAL_RECESSIVE",features.getNormalizedModeOfInheritance());
                report.put("outputCoverageVerified",true);
            }
            // Drop all in-memory sources before preparing inputs; use only persisted real execution provenance.
            base.closeDatabase();base.recreateRepositories();
            wireBusiness();
            var options=base.service.inputOptions(base.analysisId,20);Files.writeString(artifacts.resolve("input-options-before-scores.json"),options.toPrettyString());
            var request=declaration(options);Files.writeString(artifacts.resolve("explicit-public-test-declaration.json"),request.toPrettyString());
            String retryOf=System.getProperty("varvis.e4b3.scopeRetryOf","");
            JsonNode previous=null;
            if(!retryOf.isBlank()) {
                previous=base.service.read(base.analysisId,retryOf);assertEquals("COMPLETED_WITH_BLOCKERS",previous.path("status").asText());
                Files.writeString(artifacts.resolve("retry-source-run.json"),previous.toPrettyString());
                request.put("retryOf",retryOf);Files.writeString(artifacts.resolve("explicit-public-test-declaration.json"),request.toPrettyString());report.put("retryOf",retryOf);
            }
            var before=calls();
            var saved=observedPost(request,"first-post");Files.writeString(artifacts.resolve("saved-business.json"),saved.toPrettyString());
            report.put("runId",saved.path("runId").asText());
            report.set("firstRequestCallIncrement",base.mapper.valueToTree(increment(before)));
            report.put("providerCalls",saved.path("providerCalls").asInt()).put("evaluatorCalls",saved.path("evaluatorCalls").asInt());
            report.set("candidates",saved.path("candidates"));report.set("ba1Calls",saved.path("ba1Calls"));
            if(previous!=null) {
                assertEquals(previous.at("/candidates/0/ba1"),saved.at("/candidates/0/ba1"));
                assertEquals("NOT_MET",saved.at("/candidates/0/ba1/evaluation/evaluationResult/status").asText());
                String reusedFrom=saved.at("/candidates/0/ba1ReusedFromRunId").asText();assertFalse(reusedFrom.isBlank());
                assertEquals(saved.at("/candidates/0/ba1"),base.service.read(base.analysisId,reusedFrom).at("/candidates/0/ba1"));
                assertEquals(0,saved.at("/ba1Calls/provider").asInt());assertEquals(0,saved.at("/ba1Calls/evaluator").asInt());
                report.put("ba1SavedIdentityVerified",true).put("ba1ReusedFromRunId",reusedFrom);
            }
            var firstDelta=increment(before);
            base.closeDatabase();base.recreateRepositories();wireBusiness();
            var beforeRead=calls();
            var read=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            Files.writeString(artifacts.resolve("saved-get-response.json"),read.toPrettyString(),StandardOpenOption.CREATE_NEW);
            recordHttp("saved-get","GET","/variant-analyses/"+base.analysisId+"/acmg/runs/"+saved.path("runId").asText(),null,read,increment(beforeRead));
            assertEquals(saved.path("runId"),read.path("runId"));assertEquals(saved,read);report.set("readbackCallIncrement",base.mapper.valueToTree(increment(beforeRead)));
            assertTrue(increment(beforeRead).values().stream().allMatch(n->n==0));
            var beforeRepeat=calls();var repeated=observedPost(request,"repeat-post");assertEquals(saved,repeated);assertEquals(saved.path("runId"),repeated.path("runId"));
            var repeatDelta=increment(beforeRepeat);report.set("repeatRequestCallIncrement",base.mapper.valueToTree(repeatDelta));
            assertTrue(repeatDelta.values().stream().allMatch(n->n==0),repeatDelta.toString());
            var noAnalysis=mock(VariantAnalysisJobProcessor.class);var recoveredAnalysis=analysisService(noAnalysis);
            var reused=recoveredAnalysis.submit(new MockMultipartFile("vcfFile","public-intronic-scope.vcf","text/plain",vcf.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"GRCh38","WES","synthetic-proband",List.of(),null,null,
                    org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest.disabled(),null,null,"INCLUDE_INTRONS");
            assertEquals(base.analysisId,reused.getId());
            verifyNoInteractions(noAnalysis);
            report.put("readbackIdentical",true).put("analysisReused",true);
            assertEquals(1,spliceBatches,"A real configured raw batch must execute");
            assertEquals("COMPLETED",saved.path("status").asText());
            assertEquals(saved.path("providerCalls").asInt(),firstDelta.get("provider"));
            assertEquals(saved.path("evaluatorCalls").asInt(),firstDelta.get("evaluator"));
            boolean met=IntronicScopeEvidenceAssertions.verify(saved,rawSpliceResult,base.mapper);
            report.put("pageCovered",false).put("realEvaluationVerified",true).put("bp7MetCovered",met)
                    .put("status",met?"REAL_BP7_MET_VERIFIED":"REAL_EXECUTION_VERIFIED_BP7_MET_NOT_COVERED");
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;
        } finally { report.set("observedCalls",base.mapper.valueToTree(calls()));report.put("exomiserProcessCalls",exomiserCalls).put("vepProcessCalls",vepCalls).put("wallSeconds",(System.nanoTime()-start)/1_000_000_000.0);
            Files.writeString(artifacts.resolve("real-result.json"),report.toPrettyString());System.out.println("BUSINESS_ATTEMPT="+artifacts);base.closeDatabase(); }
    }
    @Test @EnabledIfSystemProperty(named="varvis.e4b3.scopeReadbackAnalysisId", matches=".+")
    void persistedRealAnalysisScopeReadsAndReusesWithoutExecutingAgain() throws Exception {
        setup();
        try {
            String id=System.getProperty("varvis.e4b3.scopeReadbackAnalysisId");
            var processor=mock(VariantAnalysisJobProcessor.class);
            var http=analysisHttp(analysisService(processor));
            var read=base.mapper.readTree(http.perform(get("/variant-analyses/{id}",id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray());
            assertEquals("READY",read.path("status").asText());
            assertEquals("INCLUDE_INTRONS",read.at("/analysisScopePolicy/scope").asText());
            var reused=base.mapper.readTree(http.perform(multipart("/variant-analyses")
                    .file(new MockMultipartFile("vcfFile","public-intronic-scope.vcf","text/plain",vcf.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("genomeAssembly","GRCh38").param("analysisMode","WES").param("analysisScope","INCLUDE_INTRONS").param("sampleId","synthetic-proband"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertEquals(id,reused.path("id").asText());
            assertEquals(read.path("analysisScopePolicy"),reused.path("analysisScopePolicy"));
            verifyNoInteractions(processor);
            if(Boolean.getBoolean("varvis.e4b3.scopeReadbackOnly")) {
                base.analysisId=id;wireBusiness();
                var saved=base.mapper.readTree(Files.readString(work.resolve("saved-business.json")));
                var request=(ObjectNode)base.mapper.readTree(Files.readString(work.resolve("explicit-public-test-declaration.json")));
                var before=calls();
                var result=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",id,saved.path("runId").asText()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                assertEquals(saved,result);var readDelta=increment(before);var beforeRepeat=calls();
                assertEquals(saved,base.postRequest(request));var repeatDelta=increment(beforeRepeat);
                assertTrue(increment(before).values().stream().allMatch(n->n==0));
                var evidence=base.mapper.createObjectNode().put("analysisId",id).put("runId",saved.path("runId").asText())
                        .put("savedStatus",saved.path("status").asText()).put("readbackIdentical",true).put("repeatIdentical",true);
                evidence.set("readbackAndRepeatCallIncrement",base.mapper.valueToTree(increment(before)));
                evidence.set("readbackCallIncrement",base.mapper.valueToTree(readDelta));
                evidence.set("repeatRequestCallIncrement",base.mapper.valueToTree(repeatDelta));
                Files.writeString(work.resolve("persisted-business-readback.json"),evidence.toPrettyString());
            }
            Files.writeString(work.resolve("actual-analysis-readback.json"),base.mapper.createObjectNode().put("analysisId",id)
                    .put("scopeRestored",true).put("analysisReused",true).put("additionalAnalysisCalls",0)
                    .set("analysisScopePolicy",read.path("analysisScopePolicy")).toPrettyString());
        } finally {base.closeDatabase();}
    }
    ObjectNode observedPost(ObjectNode request,String label) throws Exception {
        var before=calls();var result=base.postRequest(request);
        Files.writeString(artifacts.resolve(label+"-request.json"),request.toPrettyString(),StandardOpenOption.CREATE_NEW);
        Files.writeString(artifacts.resolve(label+"-response.json"),result.toPrettyString(),StandardOpenOption.CREATE_NEW);
        recordHttp(label,"POST","/variant-analyses/"+base.analysisId+"/acmg/runs",request,result,increment(before));return result;
    }
    void recordHttp(String label,String method,String uri,JsonNode request,JsonNode response,Map<String,Integer> delta) throws Exception {
        var event=base.mapper.createObjectNode().put("method",method).put("uri",uri).put("httpStatus",200)
                .put("completedAt",java.time.Instant.now().toString()).put("runId",response.path("runId").asText())
                .put("responseSha256",hash(response.toPrettyString())).put("transport","Spring MockMvc controller dispatch");
        if(request!=null)event.put("requestSha256",hash(request.toPrettyString()));
        event.set("actualCallIncrement",base.mapper.valueToTree(delta));
        Files.writeString(artifacts.resolve(label+"-http-event.json"),event.toPrettyString(),StandardOpenOption.CREATE_NEW);
    }
    VariantAnalysisServiceImpl analysisService(VariantAnalysisJobProcessor processor) {
        return new VariantAnalysisServiceImpl(base.jobs,mock(VariantEnhancementRepository.class),processor,mock(VariantEnhancementProcessor.class),base.properties);
    }
    MockMvc analysisHttp(VariantAnalysisServiceImpl service) {
        return MockMvcBuilders.standaloneSetup(new VariantAnalysisController(service,mock(VariantAnalysisExportService.class),mock(VariantAnalysisPreflightService.class),new VcfHeaderSampleDetectionService(),base.properties)).build();
    }
    void wireBusiness() {
        base.service=new AcmgBusinessConfig().acmgBusinessService(base.mapper,base.jobs,base.store,base.sources,base.properties,environment);
        observeBusiness();
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    @SuppressWarnings("unchecked")
    void observeBusiness() {
        // Wrap real configured callables for observation; never substitute fixture results or replay scores.
        var service=Objects.requireNonNull(base.service, "business service");
        var factory=(Supplier<ExplicitCaseInputCoordinator>)requiredField(service,"runtime");
        ReflectionTestUtils.setField(service,"runtime",(Supplier<ExplicitCaseInputCoordinator>)()->{
            assertFalse(Boolean.getBoolean("varvis.e4b3.scopeReadbackOnly"),"Readback must not construct an evaluation runtime");
            var coordinator=factory.get();
            var dispatcher=requiredField(coordinator,"dispatcher");
            var spliceFactory=(Supplier<SpliceAiBusinessRuntime>)requiredField(dispatcher,"spliceRuntime");
            ReflectionTestUtils.setField(dispatcher,"spliceRuntime",(Supplier<SpliceAiBusinessRuntime>)()->{
                var runtime=Objects.requireNonNull(spliceFactory.get(), "SpliceAI runtime");
                var actual=(Function<List<SpliceAiRawBatchRunner.Query>,SpliceAiRawBatchRunner.BatchResult>)requiredField(runtime,"run");
                ReflectionTestUtils.setField(runtime,"run",(Function<List<SpliceAiRawBatchRunner.Query>,SpliceAiRawBatchRunner.BatchResult>)queries->{
                    spliceBatches++;var result=actual.apply(queries);rawSpliceResult=result.getResult();
                    try {Files.writeString(artifacts.resolve("actual-raw-batch.json"),rawSpliceResult==null?base.mapper.createObjectNode()
                            .put("status",result.getStatus()).put("reasonCode",result.getReasonCode()).toPrettyString():rawSpliceResult.toPrettyString());}
                    catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}return result;
                });return runtime;
            });
            var counted=spy(coordinator);
            doAnswer(call->{var result=(ExplicitCaseInputCoordinator.Result)call.callRealMethod();
                providerCalls+=result.adapterResult().dispatch().providerCalls();evaluatorCalls+=result.adapterResult().dispatch().evaluatorCalls();return result;
            }).when(counted).dispatch(any(),any(),any(),any(),anyList(),anyInt(),anyList(),anyMap());
            return counted;
        });
        var ba1Factory=(Supplier<AcmgBusinessService.Ba1Runtime>)requiredField(service,"ba1Runtime");
        ReflectionTestUtils.setField(service,"ba1Runtime",(Supplier<AcmgBusinessService.Ba1Runtime>)()->{
            assertFalse(Boolean.getBoolean("varvis.e4b3.scopeReadbackOnly"),"Readback must not construct a BA1 runtime");
            var runtime=ba1Factory.get();var counted=spy(runtime.evaluator());
            doAnswer(call->{providerCalls++;evaluatorCalls++;return call.callRealMethod();}).when(counted).evaluate(anyString(),any(),any());
            return new AcmgBusinessService.Ba1Runtime(runtime.producer(),counted);
        });
    }
    ObjectNode source(String locator) {return base.mapper.createObjectNode().put("source","SYNTHETIC_PUBLIC_PANEL_DECLARATION").put("version","1").put("recordLocator",locator);}
    ObjectNode declaration(ObjectNode options) {
        var request=base.mapper.createObjectNode().put("topN",20).put("declarationVersion","ordinary-ui-v3").put("sampleId","synthetic-proband").put("dataClass","SYNTHETIC");
        request.putObject("patient").put("patientContextId","patient-context-public-intronic-scope").put("role","PROBAND").put("sex","UNKNOWN").set("source",source("public-panel/case"));
        var hypotheses=request.putArray("hypotheses");var selections=request.putArray("recommendations");
        for(var row:options.path("candidates")) {
            var r=row.path("recommendation");JsonNode relation=null;
            for(var rel:r.path("relationships")) if(rel.path("selectable").asBoolean() && "HGNC:4827".equals(rel.path("hgncId").asText())
                    && "MONDO:0013517".equals(rel.path("mondoId").asText())
                    && "AUTOSOMAL_RECESSIVE".equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rel.path("moi").asText()))){assertNull(relation);relation=rel;}
            assertNotNull(relation,"Fixed public hypothesis does not match current source-backed candidate/MOI/catalog: "+r);
            var selection=selections.addObject();for(String field:List.of("candidateId","recommendationId","policyVersion","policyHash","generatedAt"))selection.set(field,r.path(field));
            selection.set("stableRelationshipKey",relation==null?base.mapper.nullNode():relation.path("stableRelationshipKey"));
            var tx=r.path("resourceCompatibleTranscript");selection.set("annotationId",tx.isObject()?tx.path("annotationId"):base.mapper.nullNode());
            assertEquals("ENST00000335295.4",tx.path("versionedTranscriptId").asText(),"Pinned transcript missing; do not silently select another");
            assertEquals("SPLICEAI_INTRONIC",tx.path("predictionPath").asText());
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
