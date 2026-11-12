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

/** Normal CURRENT18 service/controller/store with real loaders and TEST_ONLY small originals.
 * Ranking transport, toy reference readiness and published annotations are explicitly synthetic;
 * PVS1 qualification, source identity, sequence reconstruction and scoring are never stubbed.
 */
class UnifiedEighteenPvs1BusinessTest {
    @TempDir Path temporary;
    private Bs1KnowledgeBusinessTest populationFixture;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessWorkflows workflows;
    private Pvs1UnifiedEighteenFixture pvs1;
    private UnifiedEighteenCompanionFixtures companions;
    private final Map<String,Integer> factories = new LinkedHashMap<>();
    private List<String> available;
    private ObjectNode options;
    private String scenario;
    private Path archive;
    // Fixed independently of the tested loaders/runtime; never inferred from returned JSON.
    private static final Map<String,String> REFUSALS=Map.ofEntries(
            Map.entry("NEGATIVE_INFRAME","PVS1_SPLICE_INFRAME_EXON_NOT_RELEVANT"),
            Map.entry("NEGATIVE_ESCAPE","PVS1_ESCAPE_EXON_LOF_TOLERANT"),
            Map.entry("MISSING_ESCAPE_SCOPE","PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED"),
            Map.entry("MISSING_RESIDUAL_RNA","PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED"),
            Map.entry("MISSING_REVIEW","PVS1_SOURCE_QUALIFICATION_SPLICE_REVIEW_REQUIRED"),
            Map.entry("WRONG_SOURCE_PIN","PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED"),
            Map.entry("WRONG_MODEL_PIN","REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE"),
            Map.entry("WRONG_OUTCOME_PIN","PVS1_SPLICE_MODEL_IDENTITY_MISMATCH"),
            Map.entry("WRONG_TARGET","PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED"),
            Map.entry("UNRESOLVED_SPLICE_PRODUCTS","PVS1_SPLICE_OUTCOME_UNRESOLVED"),
            Map.entry("WRONG_SPLICE_REFERENCE_WINDOW","PVS1_SPLICE_CURRENT_REFERENCE_WINDOW_MISMATCH"));

    @AfterEach void close() { if (populationFixture != null) populationFixture.close(); }

    private void prepare(String mode) throws Exception { prepare(mode,mode); }
    private void prepare(String mode,String scenarioName) throws Exception {
        scenario = scenarioName;
        populationFixture = new Bs1KnowledgeBusinessTest(); populationFixture.temporary = temporary; populationFixture.setup();
        base = populationFixture.local.fixture.base;
        // Existing accepted relationship rows are a binding fixture, not human sequence assertions.
        // Both transcripts, CDS, alleles, policies and experimental originals remain TEST_ONLY.
        pvs1 = new Pvs1UnifiedEighteenFixture(base.mapper, temporary.resolve("pvs1"), mode, data -> {
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
        });
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
        companions=new UnifiedEighteenCompanionFixtures(base.mapper,temporary.resolve("companions"),pvs1);
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
                var c=new UnifiedEighteenCompanionFixtures(base.mapper,temporary.resolve("companions"),pvs1);
                return new LocalEvidenceBatchRuntime(base.mapper,base.mapper.createObjectNode().put("testDataClass","TEST_ONLY_UNIFIED18")
                        .put("pvs1BundleRawSha256",pvs1.bundleRawSha256).put("pvs1KnowledgeSha256",pvs1.knowledgeAdoptionSha256),
                        ()->opened("PM2",c.pm2Factory(populationSettings).get()),c.spliceFactory(localSettings),c.predictionFactory(localSettings,effects),c.reference(localSettings),c.bp7(),
                        ()->opened("PM4",c.pm4()),()->opened("PVS1",pvs1.selectedBatch()),()->opened("BP3",c.bp3()),
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
        String output=System.getProperty("varvis.pvs1.unified18.output");
        if(output!=null) {
            archive=Files.createDirectories(Path.of(output).resolve(scenario));artifact("input-options.json",options);artifact("input-pins.json",pvs1.pinnedFiles());
            artifact("fixed-source-expectations.json",base.mapper.valueToTree(pvs1.data.expected));
            // These are only the tiny generated test inputs, not a real genome/resource copy.
            try(var paths=Files.walk(pvs1.root)) {
                for(Path file:paths.filter(Files::isRegularFile).toList()) {
                    assertTrue(Files.size(file)<1024*1024,"Only tiny TEST_ONLY inputs may be retained here");
                    Path target=archive.resolve("inputs").resolve(pvs1.root.relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);
                }
            }
        }
    }

    private <T> T opened(String name,T value) { factories.merge(name,1,(existingCount, addedCount) -> existingCount + addedCount); return value; }

    private void reference() throws Exception {
        var p=base.properties.getNormalization();p.setGrch38Reference(pvs1.fastaPath.toString());p.setGrch38ReferenceSha256(pvs1.referenceSha256);
        p.setGrch38ProfileId("TEST_ONLY_UNIFIED18_REFERENCE");
        var before=Files.readAttributes(pvs1.fastaPath,BasicFileAttributes.class);String start=Instant.now().toString();
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pvs1.fastaPath)));String end=Instant.now().toString();
        var after=Files.readAttributes(pvs1.fastaPath,BasicFileAttributes.class);
        assertEquals(pvs1.referenceSha256,digest);assertEquals(before.size(),after.size());assertEquals(before.lastModifiedTime(),after.lastModifiedTime());assertEquals(before.fileKey(),after.fileKey());
        var receipt=base.mapper.createObjectNode().put("resourceId","TEST_ONLY_UNIFIED18_REFERENCE").put("resourceVersion","1")
                .put("assembly","GRCh38").put("logicalName",pvs1.fastaPath.getFileName().toString()).put("expectedSha256",digest).put("observedSha256",digest)
                .put("hashStatus","VERIFIED").put("hashAlgorithm","SHA-256").put("hashScope","RAW_BYTES").put("unchangedDuringHash",true)
                .put("byteLength",after.size()).put("mtimeNs",after.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt",start).put("completedAt",end).put("indexSha256",pvs1.fastaIndexSha256);
        var envelope=base.mapper.createObjectNode().put("source","TEST_ONLY_UNIFIED18_REFERENCE").put("version","1").put("dataClass","SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt",receipt);
        var ledger=ExplicitContextSourceLedger.create(base.mapper,envelope);base.fixture.sources.add(ledger);
        var validator=new NormalizationResourceValidator(base.properties) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly,"READY","TEST_ONLY_UNIFIED18_REFERENCE",pvs1.fastaPath.getFileName().toString(),digest,
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
        var calls=new LinkedHashMap<>(factories);int scores=pvs1.bundle.pvs1EvaluatorCalls(),reads=pvs1.referenceReads.get();
        long pp1Evaluations=pp1Evaluations();
        String populationQueries=Files.exists(temporary.resolve("queries.txt"))?Files.readString(temporary.resolve("queries.txt")):"";
        assertEquals(saved,base.postRequest(base.request),"Same explicit request must reuse its durable result");
        assertEquals(calls,factories);assertEquals(scores,pvs1.bundle.pvs1EvaluatorCalls());assertEquals(reads,pvs1.referenceReads.get());
        assertEquals(pp1Evaluations,pp1Evaluations(),"Saved-result reuse must not invoke the PP1 Python evaluator again");
        assertEquals(populationQueries,Files.exists(temporary.resolve("queries.txt"))?Files.readString(temporary.resolve("queries.txt")):"");
        var full=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",base.analysisId,saved.path("runId").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());assertEquals(saved,full);
        var index=base.service.readView(base.analysisId,saved.path("runId").asText(),null,0,null);
        for(var candidate:saved.path("candidates"))for(int i=0;i<candidate.path("attempts").size();i++)
            assertEquals(candidate.path("attempts").get(i),base.service.readView(base.analysisId,saved.path("runId").asText(),
                    candidate.path("candidateId").asText(),i,index.path("sourceViewSha256").asText()).at("/candidates/0/attempts/"+i));
        assertEquals(calls,factories);assertEquals(scores,pvs1.bundle.pvs1EvaluatorCalls());
        artifact(label+"-product.json",saved);artifact(label+"-request.json",base.request);
        var counts=base.mapper.createObjectNode().put("pvs1CoreEvaluators",scores).put("pvs1CoreRuntimes",pvs1.bundle.pvs1RuntimeCalls())
                .put("pvs1CoreProjections",pvs1.bundle.pvs1ProjectionCalls()).put("referenceReads",reads)
                .put("sameRequestNewPvs1CoreEvaluationCalls",pvs1.bundle.pvs1EvaluatorCalls()-scores).put("historyNewPvs1CoreEvaluationCalls",0)
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
    private void assertPositive(JsonNode saved,int index,String strength) {
        var result=pvs1(saved,index,0);assertEquals("MET",result.path("status").asText(),result.toPrettyString());
        assertEquals(strength,result.path("appliedStrength").asText(),result.toPrettyString());
        assertTrue(result.at("/details/pvs1Facts/sourceQualifiedResearch").asBoolean(),result.toPrettyString());
        if(strength.equals("STRONG")) {
            var proof=result.at("/details/pvs1Facts/escapeAdoptionProof");
            assertEquals("AVAILABLE",proof.path("status").asText(),proof.toPrettyString());
            assertEquals("STRONG",proof.at("/strengthDerivation/adoptedStrength").asText());
            assertFalse(proof.path("sources").isEmpty());
        }
    }
    private void artifact(String name,JsonNode value) throws Exception {
        if(archive!=null)Files.write(archive.resolve(name),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
    }
    private long pp1Evaluations() throws Exception {
        Path directory=companions.root.resolve("empty-literature-project/local/work/pp1-evaluations");
        if(!Files.isDirectory(directory))return 0;
        try(var paths=Files.list(directory)) { return paths.filter(Files::isDirectory).count(); }
    }

    private ObjectNode routingAttempt(String moi) {
        var value = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); value.putArray("blockers");
        value.putObject("context").put("contextId", "bound-context").put("moi", moi);
        return value;
    }
    private ObjectNode pm3Exclusion(String moi) {
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("criterionId", "ACMG_AMP_2015:PM3")
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                .put("reasonCode", "ROUTING_PM3_NON_RECESSIVE_CONTEXT");
        var details = result.putObject("details").put("originalResultSha256", "a".repeat(64));
        details.putObject("materialPreparation").put("status", "NOT_REQUIRED").put("reasonCode", "ROUTING_PM3_NON_RECESSIVE_CONTEXT");
        details.putObject("applicabilityRouting").put("status", "NOT_APPLICABLE")
                .put("reasonCode", "ROUTING_PM3_NON_RECESSIVE_CONTEXT").put("skipSpecializedAcquisition", true)
                .put("contextId", "bound-context").put("moi", moi);
        return result;
    }
    @ParameterizedTest @ValueSource(strings={"AUTOSOMAL_DOMINANT", "X_LINKED_DOMINANT", "MITOCHONDRIAL"})
    void exactPm3InheritanceScopeCompletesWithoutTranscriptAndPreservesOriginalScience(String moi) {
        var result = pm3Exclusion(moi); var before = result.deepCopy();
        assertTrue(AcmgBusinessService.completedRoutingExclusion(result, routingAttempt(moi)));
        assertEquals(before, result); assertEquals("NOT_EVALUATED", result.path("status").asText());
        assertEquals("NONE", result.path("appliedStrength").asText());
    }
    @Test void missingOrDifferentPm3RouteScopeCannotBorrowTheCurrentNonRecessiveContext() {
        for (String fault : List.of("context-missing", "context-other", "moi-missing", "moi-other")) {
            var result = pm3Exclusion("AUTOSOMAL_DOMINANT"); var route = (ObjectNode) result.at("/details/applicabilityRouting");
            switch (fault) {
                case "context-missing" -> route.remove("contextId");
                case "context-other" -> route.put("contextId", "another-context");
                case "moi-missing" -> route.remove("moi");
                case "moi-other" -> route.put("moi", "AUTOSOMAL_RECESSIVE");
                default -> throw new AssertionError(fault);
            }
            assertFalse(AcmgBusinessService.completedRoutingExclusion(result, routingAttempt("AUTOSOMAL_DOMINANT")), fault);
        }
    }
    @ParameterizedTest @ValueSource(strings={"AUTOSOMAL_RECESSIVE", "UNKNOWN"})
    void recessiveOrUnknownPm3ScopeDoesNotBecomeCompletedExclusion(String moi) {
        assertFalse(AcmgBusinessService.completedRoutingExclusion(pm3Exclusion(moi), routingAttempt(moi)));
    }

    @Test void badOriginalRecordCannotBeHiddenByANormallyExcludedCriterion() throws Exception {
        prepare("INFRAME", "ROUTED_BAD_SOURCE");
        var snapshot = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        ((ObjectNode) snapshot.at("/candidates/0/matchedRecord")).put("recordOrdinal", 99);
        base.replaceSnapshot(snapshot);
        // Obtain the current recommendation identities after changing the source.
        // The blocked candidate stays unresolved rather than borrowing an old approval.
        options = (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "2").param("workflow", "CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        artifact("changed-input-options.json", options);
        var healthy = ((ObjectNode) base.request.at("/hypotheses/1")).deepCopy();
        var refs = base.request.putArray("recommendations");
        var hypotheses = base.request.putArray("hypotheses");
        for (int i = 0; i < 2; i++) {
            var rec = options.at("/candidates/" + i + "/recommendation");
            var ref = refs.addObject();
            for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(key, rec.path(key));
            if (i == 0) { ref.putNull("stableRelationshipKey").putNull("annotationId"); continue; }
            JsonNode relation = null, annotation = null;
            for (var r : rec.path("relationships")) if (r.path("selectable").asBoolean()
                    && r.path("mondoId").equals(healthy.at("/hypothesis/mondoId"))) relation = r;
            for (var a : rec.path("annotations")) if (a.path("contextSelectable").asBoolean()) { annotation = a; break; }
            assertNotNull(relation); assertNotNull(annotation);
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
            var source = base.declaredSource("recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var hypothesis = (ObjectNode) healthy.path("hypothesis");
            hypothesis.set("hypothesisId", rec.path("hypothesisId")); hypothesis.set("provenance", source);
            ((ObjectNode) healthy.path("transcript")).set("source", source);
            hypotheses.add(healthy);
        }
        var saved = submit(List.of("ACMG_AMP_2015:PM5"), "bad-source");
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText(), saved.toPrettyString());
        assertTrue(factories.isEmpty(), "Neither an unresolved candidate nor an excluded branch may open a scientific module");
        var bad = saved.at("/candidates/0/attempts/0");
        assertFalse(bad.path("blockers").isEmpty(), bad.toPrettyString());
        assertTrue(bad.toString().contains("ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED") || bad.toString().contains("MATCHED_RECORD"), bad.toPrettyString());
        for (var criterion : bad.at("/localBatch/criteria")) assertFalse(AcmgBusinessService.completedRoutingExclusion(criterion, bad));
        var good = saved.at("/candidates/1/attempts/0");
        assertTrue(good.path("blockers").isEmpty(), good.toPrettyString());
        assertEquals(1, good.at("/localBatch/criteria").size());
        var criterion = good.at("/localBatch/criteria/0");
        assertEquals("NOT_EVALUATED", criterion.path("status").asText());
        assertEquals("NONE", criterion.path("appliedStrength").asText());
        assertTrue(AcmgBusinessService.completedRoutingExclusion(criterion, good), criterion.toPrettyString());
        independentReader(saved, "bad-source");
    }

    @ParameterizedTest @ValueSource(strings={"PM5", "PM5,PP2,BP1"})
    void verifiedRoutingExclusionsFinishWithoutChangingOriginalScience(String codes) throws Exception {
        prepare("INFRAME", "ONLY_ROUTED_" + codes.replace(',', '_'));
        var selection = Arrays.stream(codes.split(",")).map(c -> "ACMG_AMP_2015:" + c).toList();
        var saved = submit(selection, "routed");
        assertEquals("COMPLETED", saved.path("status").asText(), saved.toPrettyString());
        assertTrue(factories.isEmpty(), "Excluded specialized modules must not be opened");
        for (var candidate : saved.path("candidates")) {
            assertTrue(candidate.path("enabledRuleResultsComplete").asBoolean());
            for (var attempt : candidate.path("attempts")) for (var criterion : attempt.at("/localBatch/criteria")) {
                assertEquals("NOT_EVALUATED", criterion.path("status").asText());
                assertEquals("NONE", criterion.path("appliedStrength").asText());
                assertEquals("NOT_APPLICABLE", criterion.at("/details/applicabilityRouting/status").asText());
                assertTrue(AcmgBusinessService.completedRoutingExclusion(criterion, attempt));
                var changed = criterion.deepCopy();
                ((ObjectNode) changed.at("/details/applicabilityRouting")).put("status", "UNRESOLVED");
                assertFalse(AcmgBusinessService.completedRoutingExclusion(changed, attempt));
                ((ObjectNode) changed.at("/details/applicabilityRouting")).put("status", "SOFTWARE_UNSUPPORTED");
                assertFalse(AcmgBusinessService.completedRoutingExclusion(changed, attempt));
                changed = criterion.deepCopy(); ((ObjectNode) changed.at("/details")).remove("applicabilityRouting");
                assertFalse(AcmgBusinessService.completedRoutingExclusion(changed, attempt));
                changed = criterion.deepCopy(); ((ObjectNode) changed.at("/details/applicabilityRouting")).put("contextId", "different");
                assertFalse(AcmgBusinessService.completedRoutingExclusion(changed, attempt));
                var blocked = attempt.deepCopy(); ((ObjectNode) blocked).withArray("blockers").add("SOURCE_UNVERIFIED");
                assertFalse(AcmgBusinessService.completedRoutingExclusion(criterion, blocked));
            }
        }
        independentReader(saved, "routed");
    }

    @ParameterizedTest @ValueSource(strings={"INFRAME","ESCAPE","NMD"})
    void current18SingleRelatedAndAllUseRealPvs1AndDurableScope(String mode) throws Exception {
        prepare(mode);String strength=mode.equals("INFRAME")?"MODERATE":mode.equals("ESCAPE")?"STRONG":"VERY_STRONG";
        var single=submit(List.of(Pvs1SelectedBatch.CRITERION),"single");
        assertEquals(Set.of("PVS1"),factories.keySet());for(int i=0;i<2;i++)assertPositive(single,i,strength);
        var related=submit(List.of(Pvs1SelectedBatch.CRITERION,"ACMG_AMP_2015:PM4","ACMG_AMP_2015:BP3"),"related");
        assertEquals(Set.of("PVS1","PM4","BP3"),factories.keySet());for(int i=0;i<2;i++)assertPositive(related,i,strength);
        var all=submit(available,"all");for(int i=0;i<2;i++)assertPositive(all,i,strength);
        assertTrue(pp1Evaluations()>0,"All18 must reach the actual offline PP1 adapter, not a missing configuration stub");
        for(var candidate:all.path("candidates")) for(var criterion:candidate.at("/attempts/0/localBatch/criteria"))
            if(Pp1SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText())) {
                assertEquals("PP1_LITERATURE_REQUIREMENTS_UNRESOLVED",criterion.path("reasonCode").asText(),criterion.toPrettyString());
                assertEquals("PP1_NO_REPLAYABLE_SELECTED_SOURCE",criterion.at("/details/pp1Facts/evaluation/needs/0/reasonCode").asText(),criterion.toPrettyString());
            }
        for(int i=0;i<2;i++)assertEquals(pvs1(single,i,0).path("appliedStrength"),pvs1(all,i,0).path("appliedStrength"));
        independentReader(all,"all");
    }

    @ParameterizedTest @ValueSource(strings={"NEGATIVE_INFRAME","NEGATIVE_ESCAPE","MISSING_ESCAPE_SCOPE","MISSING_RESIDUAL_RNA","MISSING_REVIEW","WRONG_SOURCE_PIN","WRONG_MODEL_PIN","WRONG_OUTCOME_PIN","WRONG_TARGET","UNRESOLVED_SPLICE_PRODUCTS","WRONG_SPLICE_REFERENCE_WINDOW"})
    void refusalIsBoundToBadCandidateAndDoesNotHidePositiveControl(String mode) throws Exception {
        prepare(mode);var saved=submit(List.of(Pvs1SelectedBatch.CRITERION),"single");var result=pvs1(saved,0,0);
        boolean negative=mode.startsWith("NEGATIVE");assertEquals(negative?"NOT_MET":"NOT_EVALUATED",result.path("status").asText(),result.toPrettyString());
        assertNotEquals("MET",result.path("status").asText());
        assertEquals(REFUSALS.get(mode),result.path("reasonCode").asText(),result.toPrettyString());
        assertPositive(saved,1,mode.equals("NEGATIVE_INFRAME")?"MODERATE":"STRONG");
    }

    @Test void sameCandidateDifferentDiseaseCannotBorrowPvs1SourceOrScore() throws Exception {
        prepare("ESCAPE","MULTI_BACKGROUND");
        var hypotheses=(com.fasterxml.jackson.databind.node.ArrayNode)base.request.path("hypotheses");
        var first=(ObjectNode)hypotheses.get(0);var second=first.deepCopy();
        ((ObjectNode)second.path("hypothesis")).put("mondoId","MONDO:0099999");
        for(var item:List.of(first,second)) {
            var h=(ObjectNode)item.path("hypothesis");String id=h.path("candidateId").asText()+"-background-"
                    +h.path("hgncId").asText().replace("HGNC:","")+"-"+h.path("mondoId").asText().replace("MONDO:","")+"-"+h.path("moi").asText();
            h.put("hypothesisId",id);
        }
        hypotheses.add(second);
        var choice=(ObjectNode)base.request.at("/recommendations/0");
        JsonNode annotationId=choice.path("annotationId").deepCopy();choice.putNull("stableRelationshipKey").putNull("annotationId");
        var backgrounds=choice.putArray("backgrounds");
        for(var item:List.of(first,second)) {
            JsonNode relationship=null;
            for(var relation:options.at("/candidates/0/recommendation/relationships"))
                if(relation.path("mondoId").equals(item.at("/hypothesis/mondoId")))relationship=relation;
            if(relationship==null)throw new AssertionError("Both synthetic diseases must be actual selectable binding rows");
            var b=backgrounds.addObject();b.set("hypothesisId",item.at("/hypothesis/hypothesisId"));
            b.set("stableRelationshipKey",relationship.path("stableRelationshipKey"));b.set("annotationId",annotationId);
        }
        var saved=submit(List.of(Pvs1SelectedBatch.CRITERION),"multi");assertEquals(2,saved.at("/candidates/0/attempts").size());
        assertPositive(saved,0,"STRONG");var wrong=pvs1(saved,0,1);
        assertEquals("NOT_EVALUATED",wrong.path("status").asText(),wrong.toPrettyString());
        assertEquals("PVS1_MECHANISM_SOURCE_NOT_FOUND",wrong.path("reasonCode").asText(),wrong.toPrettyString());
        assertNotEquals("STRONG",wrong.path("appliedStrength").asText());
        assertPositive(saved,1,"STRONG");
        independentReader(saved,"multi");
    }

    private void independentReader(ObjectNode saved,String label) throws Exception {
        Path dir=Files.createDirectories(temporary.resolve("reader-"+label));var record=base.store.run(base.analysisId,saved.path("runId").asText()).orElseThrow();
        Files.writeString(dir.resolve("expected-public.json"),Objects.requireNonNull(record.responseJson()));
        Files.writeString(dir.resolve("expected-private.json"),Objects.requireNonNull(record.privateJson()));
        Files.writeString(dir.resolve("expected-request.json"),Objects.requireNonNull(record.requestJson()));
        int scores=pvs1.bundle.pvs1EvaluatorCalls();base.closeDatabase();
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
        assertEquals(scores,pvs1.bundle.pvs1EvaluatorCalls());
        if(archive!=null) {
            var out=Files.createDirectories(archive.resolve("reader-"+label));
            for(String name:List.of("expected-public.json","expected-private.json","expected-request.json","full-export.json","reader-receipt.json","reader.log"))
                Files.copy(dir.resolve(name),out.resolve(name));
        }
    }
}
