package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Normal CURRENT18 coding escape with actual Python source assembly and TEST_ONLY originals.
 * Ranking transport, toy reference readiness and published annotations are explicitly synthetic;
 * PVS1 qualification, source identity, sequence reconstruction and scoring are never stubbed.
 */
class Pvs1CodingCurrentBusinessTest {
    @TempDir Path temporary;
    private Bs1KnowledgeBusinessTest populationFixture;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessWorkflows workflows;
    private Pvs1CodingCurrentFixture pvs1;
    private ReviewedRuleBundle activeBundle;
    private String activeAdoptionSha;
    private UnifiedEighteenCompanionFixtures companions;
    private final Map<String,Integer> factories = new LinkedHashMap<>();
    private List<String> available;
    private ObjectNode options;
    private String scenario;
    private Path archive;
    @AfterEach void close() { if (populationFixture != null) populationFixture.close(); }

    private void prepare(String mode) throws Exception { prepare(mode,mode); }
    private void prepare(String mode,String scenarioName) throws Exception { prepare(mode,scenarioName,null); }
    private void prepare(String mode,String scenarioName,String control) throws Exception {
        scenario = scenarioName;
        populationFixture = new Bs1KnowledgeBusinessTest(); populationFixture.temporary = temporary; populationFixture.setup();
        base = populationFixture.local.fixture.base;
        // Existing accepted relationship rows are a binding fixture, not human sequence assertions.
        // Both transcripts, CDS, alleles, policies and experimental originals remain TEST_ONLY.
        pvs1 = new Pvs1CodingCurrentFixture(base.mapper, temporary.resolve("pvs1"), mode, data -> {
            for (int i=0;i<2;i++) {
                var choice = base.fixture.choices.path("fixedChoices").get(i);
                String hgnc = choice.path("hgncId").asText(), symbol = choice.path("symbol").asText();
                data.models.get(i).put("hgncId",hgnc).put("geneSymbol",symbol);
                data.policies.get(i).withObject("scope").put("hgncId",hgnc)
                        .put("mondoId",choice.path("syntheticHypothesisMondoId").asText()).put("moi",choice.path("syntheticHypothesisMoi").asText());
                data.annotations.get(i).put("geneSymbol",symbol);
                if (!data.spliceOutcomes.isEmpty()) data.spliceOutcomes.get(i).put("hgncId",hgnc)
                        .put("mondoId",choice.path("syntheticHypothesisMondoId").asText()).put("moi",choice.path("syntheticHypothesisMoi").asText());
            }
        }, Path.of(Objects.requireNonNull(System.getProperty("varvis.pp1.python"),"Existing Python required")));
        activeBundle=pvs1.bundle;activeAdoptionSha=pvs1.knowledgeAdoptionSha256;
        assertEquals(2,pvs1.assemblyAudit.path("boundPolicies").asInt(),pvs1.assemblyAudit.toPrettyString());
        assertEquals(0,pvs1.assemblyAudit.path("blockedPolicies").asInt());
        if(control!=null) corruptQualification(control);
        var a=pvs1.data.alleles.get(0); var b=pvs1.data.alleles.get(1);
        base.variantTsvOverride=tsv("1\t11\t"+a.getPosition()+"\t"+a.getReference()+"\t"+a.getAlternate()+"\tHBB\t3043\tAR\t0.8",
                "2\t13\t"+b.getPosition()+"\t"+b.getReference()+"\t"+b.getAlternate()+"\tGJB2\t2706\tAR\t0.7");
        base.syntheticPublicationEnrichment=rows -> {
            for(var row:rows) try { row.applyTranscriptAnnotations(List.of(pvs1.annotation("HBB".equals(row.getExomiserCandidateFeatures().getGeneSymbol())?0:1))); }
            catch(Exception failure) { throw new IllegalStateException(failure); }
        };
        base.publish(false,GenomeAssembly.GRCh38,vcf("11\t"+a.getPosition()+"\t.\t"+a.getReference()+"\t"+a.getAlternate()+"\t.\tPASS\t.\tGT\t.",
                "13\t"+b.getPosition()+"\t.\t"+b.getReference()+"\t"+b.getAlternate()+"\t.\tPASS\t.\tGT\t."));
        reference();
        populationFixture.local.fixture.wire(); populationFixture.wire();
        var population=base.service;
        companions=new UnifiedEighteenCompanionFixtures(base.mapper,temporary.resolve("companions"),pvs1.modelDirectory,pvs1.modelManifestSha256,pvs1::readReference);
        Path python=Path.of(Objects.requireNonNull(System.getProperty("varvis.pp1.python"),"Explicit existing Python is required"));
        Path workflow=companions.prepareEmptyLiteratureWorkflow(Path.of("../scripts/denovo"),python);
        var populationSettings=populationFixture.local.fixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString());
        populationSettings.getGnomad().setBa1LocalExomesEnabled(true);
        populationSettings.getGnomad().setGrch38Files(String.join(",",List.of("11","13").stream()
                .map(c->temporary.resolve("gnomad.exomes.v4.1.sites.chr"+c+".vcf.bgz").toString()).toList()));
        var localSettings=new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        localSettings.getClinVar().setEnabled(true);localSettings.getClinVar().setGrch38File(temporary.resolve("missing-clinvar.vcf.gz").toString());
        localSettings.getSpliceAi().setEnabled(true);localSettings.getSpliceAi().setGrch38File(temporary.resolve("missing-spliceai.vcf.gz").toString());
        localSettings.getAlphaMissense().setEnabled(true);localSettings.getAlphaMissense().setGrch38File(temporary.resolve("missing-alphamissense.tsv.gz").toString());
        var rarity=new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity() { return populationFixture.local.fixture.ba1.identity(); }
            public Pm2LocalExomesRuntime create() { return new Pm2LocalExomesRuntime(base.mapper,populationSettings); }
        };
        var local=AcmgBusinessService.localBatchWithEffectIdentity(population,(effects,pin)-> {
            try {
                var c=new UnifiedEighteenCompanionFixtures(base.mapper,temporary.resolve("companions"),pvs1.modelDirectory,pvs1.modelManifestSha256,pvs1::readReference);
                return new LocalEvidenceBatchRuntime(base.mapper,base.mapper.createObjectNode().put("testDataClass","TEST_ONLY_CODING_CURRENT")
                        .put("pvs1BundleRawSha256",pvs1.bundleRawSha256).put("pvs1KnowledgeSha256",activeAdoptionSha),
                        ()->opened("PM2",c.pm2Factory(populationSettings).get()),c.spliceFactory(localSettings),c.predictionFactory(localSettings,effects),c.reference(localSettings),c.bp7(),
                        ()->opened("PM4",c.pm4()),()->opened("PVS1",new Pvs1SelectedBatch(base.mapper,activeBundle)),()->opened("BP3",c.bp3()),
                        ()->opened("PM1",c.pm1()),()->opened("PP2",c.pp2(false)),()->opened("BP1",c.bp1(effects,pin)),false,
                        c.deNovo(),c.pm3(workflow,rarity),c.pp1(workflow));
            } catch(Exception failure) { throw new IllegalArgumentException("TEST_ONLY_COMPANION_CONFIGURATION_FAILED",failure); }
        },temporary.resolve("reviews"));
        base.service=AcmgBusinessService.unifiedLocal(population,local);
        workflows=new AcmgBusinessWorkflows(base.service,null);
        base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
        options=(ObjectNode)base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options",base.analysisId)
                .param("topN","2").param("workflow","CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        available=new ArrayList<>(); options.path("enabledCriteria").forEach(c->available.add(c.asText()));
        assertEquals(18,available.size(),options.toPrettyString());
        assertEquals(Set.of("BA1","BS1","PM2","PP3","BP4","PS1","PM5","BP7","PVS1","PM4","BP3","PM1","PP2","BP1","PS2","PM6","PM3","PP1"),
                new HashSet<>(available.stream().map(c->c.substring(c.indexOf(':')+1)).toList()));
        assertTrue(factories.isEmpty(),"Configuration and input options must not execute evaluators");
        base.request=confirmed();
        String output=System.getProperty("varvis.pvs1.coding.output");
        if(output!=null) {
            archive=Files.createDirectories(Path.of(output).resolve(scenario));artifact("input-options.json",options);artifact("input-pins.json",pvs1.pinnedFiles());
            artifact("fixed-source-expectations.json",base.mapper.createObjectNode().put("mode",mode).put("expectedStatus","MET")
                    .put("expectedStrength",mode.equals("COMPLEX")?"MODERATE":"STRONG").put("basis","Existing independent coding escape vectors; expected before evaluation"));
            // These are only the tiny generated test inputs, not a real genome/resource copy.
            try(var paths=Files.walk(pvs1.root)) {
                for(Path file:paths.filter(Files::isRegularFile).toList()) {
                    assertTrue(Files.size(file)<1024*1024,"Only tiny TEST_ONLY inputs may be retained here");
                    Path target=archive.resolve("inputs").resolve(pvs1.root.relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);
                }
            }
        }
    }

    private String rawSha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private void corruptQualification(String control) throws Exception {
        if(control.equals("WRONG_MODEL")) {
            var doc=pvs1.document();var models=(ObjectNode)doc.at("/materials/transcriptModels");
            String key=pvs1.data.policies.get(0).at("/transcriptModelBinding/canonicalSha256").asText();
            ((ObjectNode)models.path(key)).put("modelVersion","TEST_ONLY_CORRUPTED_AFTER_ORIGINAL_PIN");
            byte[] raw=base.mapper.writeValueAsBytes(doc);Path file=temporary.resolve("wrong-model-bundle.json");Files.write(file,raw);
            activeBundle=ReviewedRuleBundle.loadPvs1Selected(base.mapper,file,rawSha(raw),new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(doc),
                    new Pvs1TranscriptMaterialSupply(base.mapper,pvs1.codingMaterialSupply()),pvs1.knowledge);
            return;
        }
        var adoption=pvs1.adoption();
        if(control.equals("MISSING_QUALIFICATION"))adoption.putArray("entries");
        else if(control.equals("WRONG_SOURCE"))((ObjectNode)adoption.at("/entries/0/qualification/sourceVerification/0/source")).put("sha256","f".repeat(64));
        else throw new IllegalArgumentException("Unknown test control");
        byte[] raw=base.mapper.writeValueAsBytes(adoption);Path file=temporary.resolve(control+"-adoption.json");Files.write(file,raw);
        activeAdoptionSha=rawSha(raw);activeBundle=pvs1.reloadBundleFrom(file,activeAdoptionSha);
    }

    private <T> T opened(String name,T value) { factories.merge(name,1,(existingCount, addedCount) -> existingCount + addedCount); return value; }

    private void reference() throws Exception {
        var p=base.properties.getNormalization();p.setGrch38Reference(pvs1.fastaPath.toString());p.setGrch38ReferenceSha256(pvs1.referenceSha256);
        p.setGrch38ProfileId("TEST_ONLY_CODING_CURRENT_REFERENCE");
        var before=Files.readAttributes(pvs1.fastaPath,BasicFileAttributes.class);String start=Instant.now().toString();
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pvs1.fastaPath)));String end=Instant.now().toString();
        var after=Files.readAttributes(pvs1.fastaPath,BasicFileAttributes.class);
        assertEquals(pvs1.referenceSha256,digest);assertEquals(before.size(),after.size());assertEquals(before.lastModifiedTime(),after.lastModifiedTime());assertEquals(before.fileKey(),after.fileKey());
        var receipt=base.mapper.createObjectNode().put("resourceId","TEST_ONLY_CODING_CURRENT_REFERENCE").put("resourceVersion","1")
                .put("assembly","GRCh38").put("logicalName",pvs1.fastaPath.getFileName().toString()).put("expectedSha256",digest).put("observedSha256",digest)
                .put("hashStatus","VERIFIED").put("hashAlgorithm","SHA-256").put("hashScope","RAW_BYTES").put("unchangedDuringHash",true)
                .put("byteLength",after.size()).put("mtimeNs",after.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt",start).put("completedAt",end).put("indexSha256",pvs1.fastaIndexSha256);
        var envelope=base.mapper.createObjectNode().put("source","TEST_ONLY_CODING_CURRENT_REFERENCE").put("version","1").put("dataClass","SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt",receipt);
        var ledger=ExplicitContextSourceLedger.create(base.mapper,envelope);base.fixture.sources.add(ledger);
        var validator=new NormalizationResourceValidator(base.properties) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly,"READY","TEST_ONLY_CODING_CURRENT_REFERENCE",pvs1.fastaPath.getFileName().toString(),digest,
                        true,true,2,"NO_CHR_PREFIX",List.of(),List.of("TEST_ONLY_TINY_FASTA_READINESS"));
            }
        };
        base.producer=new NativeSnvSourceProducer(base.mapper,base.properties,pvs1.root,List.copyOf(base.fixture.sources),ledger.reference("checksum"),validator);
    }

    private ObjectNode confirmed() {
        var request=base.request.deepCopy().put("declarationVersion",AcmgBusinessRuleScope.UNIFIED_FOURTEEN_DECLARATION);
        var refs=request.putArray("recommendations");var hypotheses=request.putArray("hypotheses");
        for(int i=0;i<2;i++) {
            var row=options.path("candidates").get(i);var rec=row.path("recommendation");JsonNode relation=null,annotation=null;
            String mondo=base.fixture.choices.at("/fixedChoices/"+i+"/syntheticHypothesisMondoId").asText();
            for(var r:rec.path("relationships")) if(r.path("selectable").asBoolean()&&mondo.equals(r.path("mondoId").asText())) relation=r;
            for(var a:rec.path("annotations")) if(a.path("contextSelectable").asBoolean()) { annotation=a;break; }
            if(relation==null || annotation==null)throw new AssertionError(rec.toPrettyString());
            var source=base.declaredSource("recommendations/"+rec.path("recommendationId").asText()+"/selection");
            var item=hypotheses.addObject().put("affectedStatus","UNKNOWN");var h=item.putObject("hypothesis");
            h.set("candidateId",row.path("candidateId"));h.set("hypothesisId",rec.path("hypothesisId"));
            for(String key:List.of("hgncId","mondoId","moi"))h.set(key,relation.path(key));h.set("provenance",source);
            String tx=annotation.path("versionedTranscriptId").asText();int dot=tx.lastIndexOf('.');
            var t=item.putObject("transcript").put("accession",tx.substring(0,dot)).put("version",Integer.parseInt(tx.substring(dot+1)))
                    .put("selectionPolicy",rec.path("policyId").asText()+"@"+rec.path("policyVersion").asText())
                    .put("selectionReason","TEST_ONLY explicit current background; no clinical approval");
            t.set("annotationSource",annotation.path("annotationSource"));t.set("annotationSourceVersion",annotation.path("annotationSourceVersion"));t.set("source",source);
            var ref=refs.addObject();for(String key:List.of("candidateId","recommendationId","policyVersion","policyHash","generatedAt"))ref.set(key,rec.path(key));
            ref.set("stableRelationshipKey",relation.path("stableRelationshipKey"));ref.set("annotationId",annotation.path("annotationId"));
        }return request;
    }

    private ObjectNode submit(List<String> selection,String label) throws Exception {
        base.request.set("selectedCriteria",base.mapper.valueToTree(selection));base.request.put("operationId",UUID.randomUUID().toString());
        var formal=base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,10));
        var saved=base.postRequest(base.request);assertNotEquals("FAILED",saved.path("status").asText(),saved.toPrettyString());
        var ordered=available.stream().filter(selection::contains).toList();assertEquals(base.mapper.valueToTree(ordered),saved.path("enabledCriteria"));
        assertEquals("acmg-unified-local-pp1-v1",saved.at("/executionProfile/profileId").asText());
        for(var candidate:saved.path("candidates"))for(var attempt:candidate.path("attempts")) {
            var ids=new ArrayList<String>();for(var criterion:attempt.at("/localBatch/criteria"))ids.add(criterion.path("criterionId").asText());
            assertEquals(ordered.stream().filter(c->!Set.of("ACMG_AMP_2015:BA1","ACMG_AMP_2015:BS1").contains(c)).toList(),ids,saved.toPrettyString());
        }
        assertEquals(formal,base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,10)));
        var calls=new LinkedHashMap<>(factories);int scores=activeBundle.pvs1EvaluatorCalls(),reads=pvs1.referenceReads.get();
        long pp1Evaluations=pp1Evaluations();
        String populationQueries=Files.exists(temporary.resolve("queries.txt"))?Files.readString(temporary.resolve("queries.txt")):"";
        assertEquals(saved,base.postRequest(base.request),"Same explicit request must reuse its durable result");
        assertEquals(calls,factories);assertEquals(scores,activeBundle.pvs1EvaluatorCalls());assertEquals(reads,pvs1.referenceReads.get());
        assertEquals(pp1Evaluations,pp1Evaluations(),"Saved-result reuse must not invoke the PP1 Python evaluator again");
        assertEquals(populationQueries,Files.exists(temporary.resolve("queries.txt"))?Files.readString(temporary.resolve("queries.txt")):"");
        var full=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());assertEquals(saved,full);
        var index=base.service.readView(base.analysisId,saved.path("runId").asText(),null,0,null);
        for(var candidate:saved.path("candidates"))for(int i=0;i<candidate.path("attempts").size();i++)
            assertEquals(candidate.path("attempts").get(i),base.service.readView(base.analysisId,saved.path("runId").asText(),
                    candidate.path("candidateId").asText(),i,index.path("sourceViewSha256").asText()).at("/candidates/0/attempts/"+i));
        assertEquals(calls,factories);assertEquals(scores,activeBundle.pvs1EvaluatorCalls());
        artifact(label+"-product.json",saved);artifact(label+"-request.json",base.request);
        var counts=base.mapper.createObjectNode().put("pvs1CoreEvaluators",scores).put("pvs1CoreRuntimes",activeBundle.pvs1RuntimeCalls())
                .put("pvs1CoreProjections",activeBundle.pvs1ProjectionCalls()).put("referenceReads",reads)
                .put("sameRequestNewPvs1CoreEvaluationCalls",activeBundle.pvs1EvaluatorCalls()-scores).put("historyNewPvs1CoreEvaluationCalls",0)
                .put("literatureWorkflowModelAllowed",false).put("literatureWorkflowPublicNetworkAllowed",false)
                .put("externalIoVerificationLayer","CONFIGURATION_GUARDED_NOT_NETWORK_MEASUREMENT");
        counts.put("actualOfflinePp1PythonEvaluations",pp1Evaluations);
        counts.set("scientificFactoriesOpened",base.mapper.valueToTree(calls));artifact(label+"-calls.json",counts);
        return saved;
    }

    private JsonNode pvs1(JsonNode saved,int candidate,int background) {
        for(var row:saved.at("/candidates/"+candidate+"/attempts/"+background+"/localBatch/criteria"))
            if(Pvs1SelectedBatch.CRITERION.equals(row.path("criterionId").asText()))return row;
        throw new AssertionError("No actual PVS1 criterion in product response: "+saved.toPrettyString());
    }
    private void artifact(String name,JsonNode value) throws Exception {
        if(archive!=null)Files.write(archive.resolve(name),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
    }
    private long pp1Evaluations() throws Exception {
        Path directory=companions.root.resolve("empty-literature-project/local/work/pp1-evaluations");
        if(!Files.isDirectory(directory))return 0;
        try(var paths=Files.list(directory)) { return paths.filter(Files::isDirectory).count(); }
    }

    @ParameterizedTest @ValueSource(strings={"STOP","SIMPLE","COMPLEX"})
    void qualifiedCodingEscapeReachesCurrentSingleRelatedAndAll(String mode) throws Exception {
        prepare(mode);String strength=mode.equals("COMPLEX")?"MODERATE":"STRONG";
        var single=submit(List.of(Pvs1SelectedBatch.CRITERION),"single");
        assertEquals(Set.of("PVS1"),factories.keySet());
        for(int i=0;i<2;i++)assertCoding(single,i,strength,mode);
        var related=submit(List.of(Pvs1SelectedBatch.CRITERION,"ACMG_AMP_2015:PM4","ACMG_AMP_2015:BP3"),"related");
        for(int i=0;i<2;i++)assertCoding(related,i,strength,mode);
        var all=submit(available,"all");for(int i=0;i<2;i++)assertCoding(all,i,strength,mode);
        independentReader(all,"all");
    }
    private void assertCoding(JsonNode saved,int index,String strength,String mode) {
        var criterion=pvs1(saved,index,0);assertEquals("MET",criterion.path("status").asText(),criterion.toPrettyString());
        assertEquals(strength,criterion.path("appliedStrength").asText(),criterion.toPrettyString());
        var facts=criterion.at("/details/pvs1Facts");var proof=facts.path("escapeAdoptionProof");
        assertTrue(facts.path("sourceQualifiedResearch").asBoolean(),facts.toPrettyString());
        assertEquals("AVAILABLE",proof.path("status").asText(),proof.toPrettyString());
        assertEquals("STRONG",proof.at("/strengthDerivation/sourceRuleStrength").asText());
        assertEquals(strength,proof.at("/strengthDerivation/adoptedStrength").asText());
        assertEquals(mode.equals("COMPLEX")?"MODERATE":"VERY_STRONG",proof.at("/strengthDerivation/parentPolicyCap").asText());
        assertFalse(proof.path("strengthDerivation").has("spliceScopeCap"));assertFalse(proof.has("localReviews"));
        assertFalse(proof.path("sources").isEmpty());assertFalse(facts.has("spliceAssessment"));
        var coding=facts.path("codingEscapeAssessment");assertEquals("AVAILABLE",coding.path("status").asText(),coding.toPrettyString());
        assertEquals("VERIFIED",coding.at("/sourceQualification/status").asText(),coding.toPrettyString());
        assertEquals(600,proof.at("/transcriptModelFacts/cdsLength").asInt());
        assertEquals(199,proof.at("/transcriptModelFacts/proteinLength").asInt());
        assertEquals(mode.equals("STOP")?167:11,proof.at("/proteinEffect/firstAlteredResidue").asInt());
        assertEquals(mode.equals("STOP")?499:mode.equals("COMPLEX")?61:index==0?64:61,facts.path("ptcStart").asInt());
        assertEquals(mode.equals("STOP")?501:mode.equals("COMPLEX")?63:index==0?66:63,facts.path("ptcEnd").asInt());
        assertTrue(pvs1.referenceReads.get()>0,"Current qualified path must actually verify the reference");
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_QUALIFICATION","WRONG_SOURCE","WRONG_MODEL"})
    void qualifiedCurrentDoesNotUseMissingOrSubstitutedMaterials(String control) throws Exception {
        prepare("STOP",control,control);var saved=submit(List.of(Pvs1SelectedBatch.CRITERION),"single");
        var first=pvs1(saved,0,0);assertEquals("NOT_EVALUATED",first.path("status").asText(),first.toPrettyString());
        String reason=control.equals("MISSING_QUALIFICATION")?"PVS1_SOURCE_POLICY_CONDITIONS_MISSING":control.equals("WRONG_SOURCE")
                ?"PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED":"REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE";
        assertEquals(reason,first.path("reasonCode").asText(),first.toPrettyString());
        assertNotEquals("AVAILABLE",first.at("/details/pvs1Facts/escapeAdoptionProof/status").asText());
        assertFalse(first.at("/details/pvs1Facts/sourceQualifiedResearch").asBoolean());
        if(!control.equals("MISSING_QUALIFICATION"))assertCoding(saved,1,"STRONG","STOP");
    }

    @Test void actualSourceByteCorruptionCannotAcquireNewQualification() throws Exception {
        prepare("STOP","COMPILER_REJECTS_CHANGED_SOURCE");
        Path source=pvs1.root.resolve("test-only-coding-source-0.json");
        byte[] original=Files.readAllBytes(source);Files.writeString(source,new String(original,java.nio.charset.StandardCharsets.UTF_8)+"\nchanged after original SHA pin\n");
        var rejected=pvs1.reassembleInto(pvs1.root.resolve("rejected-source"));
        assertEquals(1,rejected.path("blockedPolicies").asInt(),rejected.toPrettyString());
        assertEquals(1,rejected.path("boundPolicies").asInt());
        assertTrue(rejected.at("/policies/0/reasonCodes").toString().contains("POLICY_SOURCE_BYTES_REQUIRED"),rejected.toPrettyString());
        assertEquals(0,activeBundle.pvs1EvaluatorCalls(),"Rejected reassembly must not execute scientific scoring");
        artifact("rejected-assembly-audit.json",rejected);
        if(archive!=null) {
            Files.copy(source,archive.resolve("changed-original.txt"));
            for(String file:List.of("rejected-source.log","rejected-source-execution.json"))Files.copy(pvs1.root.resolve(file),archive.resolve(file));
        }
    }

    @Test void savedLegacyRawIsNotPromotedToNewSourceQualification() throws Exception {
        Path original=Path.of(Objects.requireNonNull(System.getProperty("varvis.pvs1.coding.legacy"),"Retained original legacy product required"));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();byte[] before=Files.readAllBytes(original);var legacy=mapper.readTree(before);
        for(var candidate:legacy.path("candidates")) {
            var raw=candidate.at("/attempts/0/pvs1");assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText());
            assertFalse(raw.has("escapeAdoptionProof"));assertFalse(raw.at("/knowledgeAssessment/adoption").has("qualification"));
        }
        assertArrayEquals(before,Files.readAllBytes(original),"History read must not alter or requalify original bytes");
    }

    private void independentReader(ObjectNode saved,String label) throws Exception {
        Path dir=Files.createDirectories(temporary.resolve("reader-"+label));var record=base.store.run(base.analysisId,saved.path("runId").asText()).orElseThrow();
        Files.writeString(dir.resolve("expected-public.json"),Objects.requireNonNull(record.responseJson()));
        Files.writeString(dir.resolve("expected-private.json"),Objects.requireNonNull(record.privateJson()));
        Files.writeString(dir.resolve("expected-request.json"),Objects.requireNonNull(record.requestJson()));
        int scores=activeBundle.pvs1EvaluatorCalls();base.closeDatabase();
        var cmd=new ArrayList<String>();cmd.add(Path.of(System.getProperty("java.home"),"bin/java.exe").toString());
        String head=System.getProperty("varvis.pvs1.unified18.sourceHead");if(head!=null)cmd.add("-Dvarvis.pvs1.unified18.sourceHead="+head);
        cmd.addAll(List.of("-cp",System.getProperty("java.class.path"),Pvs1UnifiedEighteenHistoryReader.class.getName(),temporary.toString(),base.analysisId,saved.path("runId").asText(),dir.toString()));
        var process=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve("reader.log").toFile()).start();
        if(!process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"Reader could not be stopped");
            fail("Isolated store reader timed out; child was terminated");
        }
        assertEquals(0,process.exitValue(),Files.readString(dir.resolve("reader.log")));
        var receipt=base.mapper.readTree(Files.readAllBytes(dir.resolve("reader-receipt.json")));
        assertEquals("PASS",receipt.path("status").asText());assertNotEquals(ProcessHandle.current().pid(),receipt.path("processId").asLong());
        assertArrayEquals(Files.readAllBytes(dir.resolve("expected-public.json")),Files.readAllBytes(dir.resolve("full-export.json")));
        assertEquals(scores,activeBundle.pvs1EvaluatorCalls());
        if(archive!=null) {
            var out=Files.createDirectories(archive.resolve("reader-"+label));
            for(String name:List.of("expected-public.json","expected-private.json","expected-request.json","full-export.json","reader-receipt.json","reader.log"))
                Files.copy(dir.resolve(name),out.resolve(name));
        }
    }
}
