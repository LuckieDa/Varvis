package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real normal CURRENT writer, invoked in its own JVM. Synthetic sources and annotation
 * transport only; actual parser/ranking/recommendation/selection/factories/scorers/persistence.
 * No named gene is a biological positive; the sequence/function rules are invented controls.
 */
public final class T07FullRnaBusinessWriter {
    private static final String C="ACMG_AMP_2015:", PREFIX=Pm4Bp3ComplexPublicFixture.PREFIX;
    private final Path root,output,python;
    private final boolean synonymousTerminal;
    private AcmgBusinessIntegrationTest base;
    private Pm4Bp3ComplexPublicFixture transport;
    private T07FullRnaBusinessMaterials materials;
    private Ba1BusinessIntegrationTest populationTransport;
    private ObjectNode manifest;
    private final Map<String,TranscriptConsequence> annotations=new LinkedHashMap<>();
    private List<String> enabled;
    private T07FullRnaBusinessWriter(Path root,Path output,Path python,boolean synonymousTerminal){this.root=root;this.output=output;this.python=python;this.synonymousTerminal=synonymousTerminal;}
    public static void main(String[] args) throws Exception {
        assertTrue(args.length==3||args.length==4);if(args.length==4)assertEquals("SYNONYMOUS_TERMINAL",args[3]);
        var writer=new T07FullRnaBusinessWriter(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),args.length==4);
        Files.createDirectories(writer.root);Files.createDirectories(writer.output);
        try{writer.prepare();if(writer.synonymousTerminal)writer.runSynonymousTerminal();else writer.run();}finally{if(writer.base!=null)writer.base.closeDatabase();}
        writer.manifest.put("writerDatabaseClosed",true).put("writerCompleted",true);writer.write(writer.output.resolve("reader-manifest.json"),writer.manifest);
    }
    private void prepare() throws Exception {
        base=new AcmgBusinessIntegrationTest(){
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations(){
                var mocked=mock(VepTranscriptAnnotationEnrichmentService.class);
                when(mocked.annotateCandidates(any(),any(),any(),any(),any(GenomeAssembly.class))).thenAnswer(call->{
                    List<VariantInterpretation> candidates=call.getArgument(0);var values=new LinkedHashMap<String,List<TranscriptConsequence>>();
                    for(var candidate:candidates)values.put(candidate.getVariantId(),List.of(annotations.get(candidate.getExomiserCandidateFeatures().getGeneSymbol())));
                    return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(values,VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                });return mocked;
            }
        };
        base.temporary=root;base.setup();transport=new Pm4Bp3ComplexPublicFixture(base.mapper,root.resolve("transport"),root.resolve("reference"));
        transport.applyNormalizationTo(base.properties);base.fixture.sources.add(transport.referenceLedger());base.producer=transport.producer(base.fixture.sources);
        for(String gene:List.of("HBB","GJB2")) {
            var annotation=transport.annotation(gene);String tx=transport.model(gene).path("transcript").asText();
            annotation.put("hgvsCoding",tx+(synonymousTerminal?":c.9T>C":":c.8C>T")).put("consequence",synonymousTerminal?"splice_region_variant&synonymous_variant":"splice_region_variant&missense_variant")
                    .put("hgvsProtein",transport.model(gene).path("protein").asText()+(synonymousTerminal?":p.Pro3Pro":":p.Pro3Leu"))
                    .put("proteinPosition","3").put("aminoAcids",synonymousTerminal?"P/P":"P/L").put("codons",synonymousTerminal?"CCT/CCC":"CCT/CTT");
            annotations.put(gene,base.mapper.treeToValue(annotation,TranscriptConsequence.class));
        }
        int position=synonymousTerminal?108:107;String ref=synonymousTerminal?"T":"C",alt=synonymousTerminal?"C":"T";
        base.variantTsvOverride=tsv("1\t11\t"+position+"\t"+ref+"\t"+alt+"\tHBB\t3043\tAR\t0.8","2\t13\t"+position+"\t"+ref+"\t"+alt+"\tGJB2\t2706\tAR\t0.7");
        base.publish(false,GenomeAssembly.GRCh38,vcf("chr11\t"+position+"\t.\t"+ref+"\t"+alt+"\t.\tPASS\t.\tGT\t0/1","chr13\t"+position+"\t.\t"+ref+"\t"+alt+"\t.\tPASS\t.\tGT\t0/1"));
        materials=new T07FullRnaBusinessMaterials(base.mapper,root.resolve("rna"),transport,base.fixture.choices.path("fixedChoices"),position,ref,alt);materials.configureCompanions(python);
        populationTransport=new Ba1BusinessIntegrationTest();populationTransport.base=base;populationTransport.temporary=root;populationTransport.am=new BusinessAlphaMissenseFixture();
        populationTransport.configureCatalog(new Ba1SpecificationCatalog(base.mapper,populationTransport.syntheticCatalog("SYNTHETIC_T07_OTHER_CRITERIA_NOT_ORACLES")));
        install();
        manifest=base.mapper.createObjectNode().put("schemaVersion","t07-normal-full-rna-writer-reader-v1").put("analysisId",base.analysisId)
                .put("writerPid",ProcessHandle.current().pid()).put("dataClass","SYNTHETIC").put("humanClinicalValidation",false).put("newVepRun",false)
                .put("coreFactoryOverridden",false).put("expectedPvs1",synonymousTerminal?"NOT_SELECTED":"STRONG_WHEN_REVIEWED").put("expectedPm4","MODERATE_WHEN_APPLICABLE")
                .put("independentOracle",synonymousTerminal?"c9T>C is synonymous; RNA stop/cross-UTR loss preserves MEPGFK prefix and extends to eight residues.":"CCT codon P3 removed: MEPGFK* -> MEGFK*. P3-only source rule is Strong; source review is invented.");manifest.putArray("runs");
        write(output.resolve("transport-scope.json"),transport.scope());
    }
    private void install() {
        populationTransport.wire();
        var population=AcmgBusinessService.formalPopulation(base.service,()->Bs1Runtime.localExomes(base.mapper,new VariantAnalysisProperties(),false));
        var local=AcmgBusinessService.localBatchWithEffectIdentity(population,(effects,pin)->transport.centralRuntime(effects,pin),root.resolve("effect-reviews"));
        base.service=AcmgBusinessService.unifiedLocal(population,local);base.http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    private ObjectNode request(List<String> selection) throws Exception {
        var options=base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options",base.analysisId).param("topN","2").param("workflow","CURRENT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        enabled=new ArrayList<>();options.path("enabledCriteria").forEach(x->enabled.add(x.asText()));assertEquals(18,enabled.size(),options::toPrettyString);
        var request=base.request.deepCopy().put("declarationVersion",options.at("/executionProfile/requiredDeclaration").asText());request.set("selectedCriteria",base.mapper.valueToTree(selection));
        var hypotheses=request.putArray("hypotheses");var refs=request.putArray("recommendations");
        for(int i=0;i<2;i++) {
            var rec=options.at("/candidates/"+i+"/recommendation");var choice=base.fixture.choices.at("/fixedChoices/"+i);JsonNode relation=null,annotation=null;
            for(var r:rec.path("relationships"))if(r.path("selectable").asBoolean()&&choice.path("syntheticHypothesisMondoId").equals(r.path("mondoId")))relation=r;
            for(var a:rec.path("annotations"))if(a.path("contextSelectable").asBoolean()&&choice.path("transcript").equals(a.path("versionedTranscriptId")))annotation=a;
            if(relation==null||annotation==null)throw new AssertionError(rec.toPrettyString());
            var item=hypotheses.addObject().put("affectedStatus","UNKNOWN");var h=item.putObject("hypothesis");h.set("candidateId",rec.path("candidateId"));h.set("hypothesisId",rec.path("hypothesisId"));
            for(String k:List.of("hgncId","mondoId","moi"))h.set(k,relation.path(k));h.set("provenance",base.declaredSource("private/t07/synthetic-hypothesis"));
            String tx=choice.path("transcript").asText();int dot=tx.lastIndexOf('.');var t=item.putObject("transcript").put("accession",tx.substring(0,dot)).put("version",Integer.parseInt(tx.substring(dot+1)))
                    .put("selectionPolicy",rec.path("policyId").asText()+"@"+rec.path("policyVersion").asText()).put("selectionReason","Explicit SYNTHETIC T07 normal background, no clinical assertion");
            t.set("annotationSource",annotation.path("annotationSource"));t.set("annotationSourceVersion",annotation.path("annotationSourceVersion"));t.set("source",base.declaredSource("recommendations/"+rec.path("recommendationId").asText()+"/selection"));
            var ref=refs.addObject();for(String k:List.of("candidateId","recommendationId","policyVersion","policyHash","generatedAt"))ref.set(k,rec.path(k));
            ref.set("stableRelationshipKey",relation.path("stableRelationshipKey"));ref.set("annotationId",annotation.path("annotationId"));
        }
        write(output.resolve("input-options.json"),options);return request;
    }
    private ObjectNode submit(List<String> codes,String name) throws Exception {
        var req=request(codes);if(name.startsWith("CORRUPTION_"))req.withObject("patient").put("patientContextId","SYNTHETIC_T07_"+name);
        var ranking=base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,100));
        var saved=base.postRequest(req);assertNotEquals("FAILED",saved.path("status").asText(),saved::toPrettyString);
        assertEquals(base.mapper.valueToTree(enabled.stream().filter(codes::contains).toList()),saved.path("enabledCriteria"),saved::toPrettyString);
        assertEquals(ranking,base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,100)));
        String runId=saved.path("runId").asText();var run=base.store.run(base.analysisId,runId).orElseThrow();
        Path folder=Files.createDirectories(output.resolve(name));
        Files.writeString(folder.resolve("expected-public.json"),run.responseJson(),StandardCharsets.UTF_8);Files.writeString(folder.resolve("expected-private.json"),run.privateJson(),StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("expected-request.json"),run.requestJson(),StandardCharsets.UTF_8);write(folder.resolve("request.json"),req);
        var full=base.http.perform(get("/variant-analyses/{a}/acmg/runs/{r}",base.analysisId,runId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Files.write(folder.resolve("expected-controller-full.json"),full);assertEquals(saved,base.mapper.readTree(full));
        manifest.withArray("runs").addObject().put("name",name).put("runId",runId).put("expectedHttpStatus",200);
        var privateData=base.mapper.readTree(run.privateJson());write(folder.resolve("private-batch.json"),privateData.path("localBatch"));
        var calls=transport.configurationCalls();assertEquals(0,calls.path("actualStubCount").asInt());
        var repeat=base.postRequest(req);assertEquals(saved,repeat,"Same explicit source/request must use durable cached result");
        // Factory construction may occur to capture configuration; saved reads must not perform scientific factory methods.
        for(String method:List.of("pm4","proteinLengthMaterials","bp3","pvs1"))assertEquals(calls.path(method),transport.configurationCalls().path(method),method);
        write(folder.resolve("actual-central-calls.json"),transport.configurationCalls());
        return (ObjectNode)privateData.path("localBatch");
    }
    private void run() throws Exception {
        var neutral=submit(List.of(C+"PVS1"),"NEUTRAL_PREPARATION");
        for(var raw:neutral.at("/modules/pvs1/results"))assertEquals("NOT_EVALUATED",raw.at("/evaluation/evaluationResult/status").asText());
        materials.bindReviewedEffects(neutral);install();
        var single=submit(List.of(C+"PVS1"),"PVS1_SINGLE");verifyStrong(single);
        var related=submit(List.of(C+"PVS1",C+"PM4",C+"BP3"),"RELATED");verifyStrong(related);verifyAllocation(related);
        var all=submit(List.copyOf(enabled),"CURRENT18");verifyStrong(all);verifyAllocation(all);
        // Independent length selection keeps the PVS1 capability available but disables its LoF knowledge.
        transport.env().withProperty(PREFIX+"pvs1-selected-knowledge-enabled","false");install();
        var pm4=submit(List.of(C+"PM4"),"PM4_ONLY_NO_PVS1_KNOWLEDGE");
        for(var raw:pm4.at("/modules/pm4/results")){assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);assertEquals("MODERATE",raw.at("/evaluation/appliedStrength").asText());}
        assertEquals(0,pm4.at("/modules/pvs1/results").size());
        materials.neutralRnaEvent("SYNTHETIC_INSERT_CCC",9,9,"CCC","ATGGAACTTCCCGGCTTTAAATAAGCTGGTTGACCC");install();
        verifyLength(submit(List.of(C+"PM4"),"RNA_INSERTION"),7,1);
        materials.neutralRnaEvent("SYNTHETIC_REMOVE_STOP",18,21,"","ATGGAACTTGGCTTTAAAGCTGGTTGACCC");install();
        verifyUnresolvedExtension(submit(List.of(C+"PM4"),"RNA_STOP_REMOVAL"));
        materials.neutralRnaEvent("SYNTHETIC_CROSS_CDS_UTR",19,22,"","ATGGAACTTGGCTTTAAATCTGGTTGACCC");install();
        verifyUnresolvedExtension(submit(List.of(C+"PM4"),"RNA_CROSS_CDS_UTR"));
        var previousRun=lastRunId();Files.writeString(materials.originalFile(0),"{}",StandardCharsets.UTF_8);install();
        var changed=submit(List.of(C+"PM4"),"CHANGED_ORIGINAL");assertNotEquals(previousRun,lastRunId());
        assertEquals("NOT_EVALUATED",changed.at("/modules/pm4/results/0/evaluation/evaluationResult/status").asText(),changed::toPrettyString);
        assertTrue(changed.toString().contains("ORIGINAL_SHA_MISMATCH"),changed::toPrettyString);
        assertEquals("FULL_RNA_STOP_LOSS_UPSTREAM_PROTEIN_CHANGE",changed.at("/modules/pm4/results/1/evaluation/evaluationResult/reasonCode").asText(),"Unaffected second source keeps its actual existing boundary, not a source failure");
        materials.writeBundle();install();
        submit(List.of(C+"PM4"),"CORRUPTION_BOTH_ARCHIVE_REFERENCES");corruptSaved("CORRUPTION_BOTH_ARCHIVE_REFERENCES",false);
        submit(List.of(C+"PM4"),"CORRUPTION_BOTH_BATCHES");corruptSaved("CORRUPTION_BOTH_BATCHES",true);
        materials.noIndex();install();submit(List.of(C+"PM4"),"NO_INDEX_LEGACY");
        finishArtifacts();
    }
    private void runSynonymousTerminal() throws Exception {
        transport.env().withProperty(PREFIX+"pvs1-selected-knowledge-enabled","false");
        materials.neutralRnaEvent("SYNTHETIC_SYNONYMOUS_REMOVE_STOP",18,21,"","ATGGAACCCGGCTTTAAAGCTGGTTGACCC");install();
        verifyLength(submit(List.of(C+"PM4"),"SYNONYMOUS_STOP_REMOVAL"),8,2);
        materials.neutralRnaEvent("SYNTHETIC_SYNONYMOUS_CROSS_UTR",19,22,"","ATGGAACCCGGCTTTAAATCTGGTTGACCC");install();
        verifyLength(submit(List.of(C+"PM4"),"SYNONYMOUS_CROSS_CDS_UTR"),8,2);finishArtifacts();
    }
    private void finishArtifacts() throws Exception {
        var source=base.store.source(base.analysisId).orElseThrow();Files.writeString(output.resolve("expected-source.json"),source.json(),StandardCharsets.UTF_8);manifest.put("sourceSha256",source.sha256());
        write(output.resolve("final-central-calls.json"),transport.configurationCalls());
    }
    private static void verifyUnresolvedExtension(JsonNode batch) {
        assertEquals(2,batch.at("/modules/pm4/results").size());assertEquals(2,batch.at("/fullRnaArchive/materialsBySha256").size());
        for(var raw:batch.at("/modules/pm4/results")) {
            assertEquals("NOT_EVALUATED",raw.at("/evaluation/evaluationResult/status").asText());assertEquals("NONE",raw.at("/evaluation/appliedStrength").asText());
            assertEquals("FULL_RNA_STOP_LOSS_UPSTREAM_PROTEIN_CHANGE",raw.at("/evaluation/evaluationResult/reasonCode").asText());
        }
        for(var material:batch.at("/fullRnaArchive/materialsBySha256")) {assertEquals(8,material.at("/consequenceFacts/alteredProteinLength").asInt());assertEquals(2,material.at("/consequenceFacts/netProteinLengthChange").asInt());}
    }
    private String lastRunId() {var runs=manifest.withArray("runs");return runs.get(runs.size()-1).path("runId").asText();}
    private static void verifyLength(JsonNode batch,int length,int delta) {
        assertEquals(2,batch.at("/modules/pm4/results").size());
        for(var raw:batch.at("/modules/pm4/results")) {
            assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);
            assertEquals("MODERATE",raw.at("/evaluation/appliedStrength").asText());
        }
        assertEquals(0,batch.at("/modules/pvs1/results").size(),"Neutral length paths must not require or execute PVS1");
        for(var material:batch.at("/fullRnaArchive/materialsBySha256")) {
            assertEquals(length,material.at("/consequenceFacts/alteredProteinLength").asInt(),material::toPrettyString);
            assertEquals(delta,material.at("/consequenceFacts/netProteinLengthChange").asInt());
        }
    }
    /** Deliberate damage to this test's own isolated H2 only; recompute ordinary hashes
     * so the saved immutable request/profile requirement, rather than a stale SHA, must reject it. */
    private void corruptSaved(String name,boolean wholeBatches) throws Exception {
        var entry=(ObjectNode)manifest.withArray("runs").get(manifest.withArray("runs").size()-1);assertEquals(name,entry.path("name").asText());
        String runId=entry.path("runId").asText();var before=base.store.run(base.analysisId,runId).orElseThrow();
        var pub=(ObjectNode)base.mapper.readTree(before.responseJson());var privateData=(ObjectNode)base.mapper.readTree(before.privateJson());
        assertTrue(privateData.at("/localBatch/fullRnaObservedSourceIdentity/archiveRequired").asBoolean());
        if(wholeBatches) {pub.remove("localBatch");privateData.remove("localBatch");}
        else for(var batch:List.of(pub.withObject("localBatch"),privateData.withObject("localBatch"))) {
            batch.withObject("identity").remove("fullRnaExecution");batch.remove(List.of("fullRnaArchive","fullRnaArchiveSha256","fullRnaObservedSourceIdentity"));
        }
        String damagedPrivate=base.mapper.writeValueAsString(privateData);pub.put("privateArtifactSha256",org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(damagedPrivate));
        String damagedPublic=base.mapper.writeValueAsString(pub);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(java.util.Objects.requireNonNull(base.dataSource));
        assertEquals(1,jdbc.update("UPDATE variant_analysis_acmg_run SET response_json=?,response_sha256=?,private_json=? WHERE run_id=?",damagedPublic,
                org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(damagedPublic),damagedPrivate,runId));
        Path folder=output.resolve(name);Files.writeString(folder.resolve("before-corruption-public.json"),before.responseJson(),StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("before-corruption-private.json"),before.privateJson(),StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("expected-public.json"),damagedPublic,StandardCharsets.UTF_8);Files.writeString(folder.resolve("expected-private.json"),damagedPrivate,StandardCharsets.UTF_8);
        entry.put("expectedHttpStatus",503).put("ordinaryHashesRepaired",true).put("savedRequestUnchanged",true);
        base.http.perform(get("/variant-analyses/{a}/acmg/runs/{r}",base.analysisId,runId)).andExpect(status().isServiceUnavailable());
    }
    private static void verifyStrong(JsonNode batch) {
        assertEquals(2,batch.at("/modules/pvs1/results").size(),batch::toPrettyString);
        for(var raw:batch.at("/modules/pvs1/results")) {assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);assertEquals("STRONG",raw.at("/evaluation/appliedStrength").asText());
            assertEquals("1.10.1",raw.at("/evaluation/deterministicTrace/evaluatorVersion").asText());}
        assertEquals(2,batch.at("/fullRnaArchive/originalsByRawSha256").size());
    }
    private static void verifyAllocation(JsonNode batch) {
        for(var raw:batch.at("/modules/pm4/results")) {assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);
            assertEquals("MODERATE",raw.at("/evaluation/appliedStrength").asText());assertEquals("NOT_COUNTED",raw.at("/fullRnaCurrentUse/claim/application").asText());}
        for(var raw:batch.at("/modules/pvs1/results"))assertEquals("ADOPTED",raw.at("/fullRnaCurrentUse/claim/application").asText());
    }
    private void write(Path file,JsonNode doc) throws Exception {Files.write(file,base.mapper.writeValueAsBytes(doc));}
}
