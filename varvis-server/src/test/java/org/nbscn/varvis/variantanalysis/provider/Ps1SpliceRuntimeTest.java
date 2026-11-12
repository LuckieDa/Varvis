package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks.CatalogSnapshot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Invented predictions and mocked resource boundaries; exercises the real orchestration and comparison. */
class Ps1SpliceRuntimeTest {
    final ObjectMapper m=new ObjectMapper().findAndRegisterModules();
    Pm4TestFixtures f;
    ObjectNode policy,reference,rna;
    Ps1SpliceKnowledge knowledge; MissenseNativeSpliceBridge raw; GencodeTranscriptIndex index;
    ClinVarTabixProvider clinvar;
    Ps1SpliceRuntime setup() throws Exception {
        f=new Pm4TestFixtures(m,1);f.variant(110,"G","A","6+5G>A",null);
        f.model.put("geneId","ENSG000001.1");var e=f.model.putArray("exons");
        e.addObject().put("ordinal",1).put("genomicStart",100).put("genomicEnd",105).put("cdsStart",1).put("cdsEnd",6);
        e.addObject().put("ordinal",2).put("genomicStart",200).put("genomicEnd",214).put("cdsStart",7).put("cdsEnd",21);
        var tx=f.annotation("6+5G>A",null,"splice_region_variant&intron_variant").put("geneId","ENSG000001").put("source","VEP");
        f.candidate.applyTranscriptAnnotations(List.of(m.treeToValue(tx,TranscriptConsequence.class)));
        assertNull(CodingTranscriptMapping.modelIssue(f.model,f.context));
        var supply=mock(CodingTranscriptMaterialSupply.class);var material=mock(CodingTranscriptMaterialSupply.Material.class);
        when(material.ready()).thenReturn(true);when(material.snapshot()).thenReturn(m.createObjectNode().put("status","READY"));when(material.model()).thenAnswer(a->f.model.deepCopy());
        when(supply.resolveStructure(any(),any())).thenReturn(material);
        index=mock(GencodeTranscriptIndex.class);when(index.annotation(any())).thenAnswer(a->f.model.deepCopy());when(index.identity()).thenReturn(m.createObjectNode().put("synthetic",true));
        knowledge=mock(Ps1SpliceKnowledge.class);when(knowledge.identity(any())).thenReturn(m.createObjectNode().put("synthetic",true));
        policy=m.createObjectNode().put("similarityMode","NO_WEAKER");policy.putObject("scope").put("geneSymbol","HBB").put("mondoId","MONDO:0000001").put("expertOrgId","1");policy.putArray("supportedOriginalVersions").add("1.1");
        when(knowledge.select(any(),any())).thenAnswer(a->policy.deepCopy());
        reference=m.createObjectNode().put("scv","SCV000000001.1").put("variationId","1").put("geneSymbol","HBB").put("mondoId","MONDO:0000001").put("expertOrgId","1").put("originalVersion","1.1").put("classification","PATHOGENIC");
        reference.putObject("allele").put("assembly","GRCh38").put("contig","11").put("position",106).put("reference","G").put("alternate","C");reference.putArray("criteria").add("PVS1").add("PP1_Strong").add("PS1_Supporting");
        when(knowledge.references()).thenAnswer(a->m.createArrayNode().add(reference.deepCopy()));
        clinvar=mock(ClinVarTabixProvider.class);var discovery=mock(ClinVarTabixProvider.ComparatorDiscoveryResult.class);var found=mock(ClinVarTabixProvider.ComparatorCandidate.class);
        when(found.getVariationId()).thenReturn(1L);when(found.getExactAllele()).thenReturn(new NormalizedVariantKey("GRCh38","11",106,"G","C"));
        when(discovery.getReadinessStatus()).thenReturn("READY");when(discovery.getCandidates()).thenReturn(List.of(found));when(clinvar.discoverBoundedComparators(any(),anyInt(),anyInt())).thenReturn(discovery);
        raw=mock(MissenseNativeSpliceBridge.class);when(raw.verifyGtAgMotif(any(),any())).thenReturn(m.createObjectNode().put("donor","GT").put("acceptor","AG"));
        when(raw.audit()).thenReturn(m.createObjectNode().put("synthetic",true));when(raw.run(any(),any(),anyList())).thenAnswer(a->SpliceAiRawBatchRunner.BatchResult.succeeded(batch(),0));
        var catalog=mock(CatalogSnapshot.class);rna=m.createObjectNode();rna.putObject("material").put("status","AVAILABLE").putObject("catalog").putArray("records");when(catalog.content()).thenAnswer(a->rna.deepCopy());
        return new Ps1SpliceRuntime(m,supply,knowledge,clinvar,raw,catalog,index);
    }
    ObjectNode batch() {
        var b=m.createObjectNode().put("status","SUCCEEDED");var rows=b.putArray("results");
        for(int pos:List.of(110,106)) {
            var row=rows.addObject().put("queryId",pos==110?"TARGET":"SCV000000001.1").put("annotationFileSha256","a".repeat(64));
            row.putObject("allele").put("alternate",pos==110?"A":"C");row.set("selectedAnnotation",NativeSpliceAiRuntime.selectedAnnotation(m,f.model));
            var ann=row.putArray("annotationRows").addObject().put("geneSymbol","HBB").put("contig","11").put("strand","+").put("start",100).put("end",214);
            var exons=ann.putArray("exons");exons.addArray().add(100).add(105);exons.addArray().add(200).add(214);
            var score=row.putArray("annotations").addObject().put("geneSymbol","HBB").put("allele",pos==110?"A":"C");var ds=score.putObject("deltaScores");var dp=score.putObject("deltaPositions");
            for(String kind:List.of("AG","AL","DG","DL")){ds.put("DS_"+kind,kind.equals("DL")?"0.98":"0.00");dp.put("DP_"+kind,kind.equals("DL")?105-pos:0);}
        }
        return b;
    }
    @Test void sourceQualifiedComparisonPreservesOriginalCandidateAndProducesModerateSummary() throws Exception {
        var runtime=setup();var before=m.valueToTree(f.candidate);var result=runtime.evaluate(f.context,f.candidate,null);
        assertEquals("MET",result.path("status").asText(),result.toPrettyString());assertEquals("MODERATE",result.at("/selectedComparison/comparison/appliedStrength").asText());
        assertEquals("PP3",result.path("baseline").asText());assertEquals(before,m.valueToTree(f.candidate));verify(raw,times(1)).run(any(),any(),anyList());
        assertEquals("SCV000000001.1",result.at("/publicSummary/referenceScv").asText());assertFalse(result.at("/publicSummary/observedRna").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE","VERSION","RNA","RNA_UNAVAILABLE","MODEL"})
    void missingEvidenceOrConflictingContextDoesNotInvokePrediction(String kind) throws Exception {
        var runtime=setup();
        switch(kind){
            case "SOURCE" -> when(knowledge.references()).thenReturn(m.createArrayNode());
            case "VERSION" -> reference.put("originalVersion","9.0");
            case "RNA" -> rna.withObject("/material/catalog").withArray("records").addObject().set("allele",f.context.at("/variant/evidenceQueryAllele"));
            case "RNA_UNAVAILABLE" -> rna.withObject("/material").put("status","UNAVAILABLE");
            case "MODEL" -> when(index.annotation(any())).thenReturn(f.model.deepCopy().put("transcript","ENST000002.1"));
        }
        assertEquals("NOT_EVALUATED",runtime.evaluate(f.context,f.candidate,null).path("status").asText());verify(raw,never()).run(any(),any(),anyList());
    }
    @Test void nativeFailureRemainsSoftwareFailureNotMissingDatabaseRecord() throws Exception {
        var runtime=setup();when(raw.run(any(),any(),anyList())).thenReturn(SpliceAiRawBatchRunner.BatchResult.failed("NATIVE_SPLICE_PROCESS_FAILED",1));
        var result=runtime.evaluate(f.context,f.candidate,null);assertEquals("NATIVE_SPLICE_PROCESS_FAILED",result.path("reasonCode").asText());assertFalse(result.has("selectedComparison"));
    }
    @Test void competingEventHasSpecificReasonAndCannotBecomeMet() throws Exception {
        var runtime=setup();var batch=batch();((ObjectNode)batch.at("/results/0/annotations/0/deltaScores")).put("DS_DG","0.30");
        when(raw.run(any(),any(),anyList())).thenReturn(SpliceAiRawBatchRunner.BatchResult.succeeded(batch,0));var result=runtime.evaluate(f.context,f.candidate,null);
        assertEquals("PS1_SPLICE_MULTIPLE_OR_CRYPTIC_EVENTS_UNSUPPORTED",result.path("reasonCode").asText());assertEquals("NOT_EVALUATED",result.path("status").asText());
    }

    @Test void explicitProfileScopeComparesTheSecondaryEventInsteadOfBlanketRejection() throws Exception {
        var runtime=setup();policy.put("eventScope","MATCHED_NATIVE_LOSS_PROFILE").put("similarityMode","PUBLISHED_RELATIVE_TEN_PERCENT");
        var batch=batch();
        for(int i=0;i<2;i++) {
            ((ObjectNode)batch.at("/results/"+i+"/annotations/0/deltaScores")).put("DS_DG",i==0?"0.23":"0.25");
            ((ObjectNode)batch.at("/results/"+i+"/annotations/0/deltaPositions")).put("DP_DG",i==0?-10:-6);
        }
        when(raw.run(any(),any(),anyList())).thenReturn(SpliceAiRawBatchRunner.BatchResult.succeeded(batch,0));
        var result=runtime.evaluate(f.context,f.candidate,null);
        assertEquals("MET",result.path("status").asText(),result.toPrettyString());
        assertEquals(100,result.at("/publicSummary/comparison/targetEvent/profile/DG/position").asInt());
        assertFalse(result.at("/publicSummary/observedRna").asBoolean());
    }
}
