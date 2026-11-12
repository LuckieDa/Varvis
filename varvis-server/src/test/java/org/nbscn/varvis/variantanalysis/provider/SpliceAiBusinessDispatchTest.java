package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpliceAiBusinessDispatchTest {
    final ExplicitAlphaMissenseDispatcherTest base = new ExplicitAlphaMissenseDispatcherTest();
    int batches;
    int queryCount;
    String score = "0.01";
    String gene = "SYNTH1";
    String failure;

    @ParameterizedTest @CsvSource({"7,MET", "-21,MET", "6,BLOCKED", "-20,BLOCKED", "8,MET", "-22,MET"})
    void profilePositionBoundaries(int offset, String expected) throws Exception {
        var f = fixture(1, offset);
        var am = base.new FixtureProvider("0.01", false, false);
        var result = dispatcher(am).dispatch(base.assembly(List.of(f)), List.of(f.candidate()), base.analysis);
        var a = result.candidates().get(0).attempts().get(0);
        assertEquals(0, am.calls);
        if ("BLOCKED".equals(expected)) {
            assertEquals(0, batches); assertEquals("BP7_INTRONIC_SPLICE_PROXIMAL", a.blockers().get(0).reasonCode());
        } else {
            assertTrue(a.blockers().isEmpty(), a.blockers().toString()); assertEquals(1, batches);
            assertEquals(expected, a.criterionEvaluations().get(2).at("/evaluationResult/status").asText());
            assertEquals(2, a.atomicEvidence().size());
            assertTrue(a.criterionEvaluations().get(2).path("evidenceIds").toString().contains(a.atomicEvidence().get(0).path("evidenceId").asText()));
            assertEquals(1, new AcmgBusinessArtifacts(base.mapper).bundles(result).size());
        }
    }
    @ParameterizedTest @CsvSource({"0.10,MET", "0.11,NOT_MET", "0.20,NOT_MET", "0.90,NOT_MET"})
    void sameSpliceBp4Dependency(String value, String expected) throws Exception {
        score = value;
        var f = fixture(1, 7);
        var a = dispatcher(base.new FixtureProvider("0.01", false, false)).dispatch(base.assembly(List.of(f)), List.of(f.candidate()), base.analysis).candidates().get(0).attempts().get(0);
        assertTrue(a.blockers().isEmpty(), a.blockers().toString());
        assertEquals(expected, a.criterionEvaluations().get(2).at("/evaluationResult/status").asText());
    }
    @ParameterizedTest @CsvSource({"FAILED", "TIMEOUT", "GENE_MISMATCH"})
    void failureIsNotBenignAndAmSiblingExecutes(String type) throws Exception {
        if (type.equals("GENE_MISMATCH")) gene = "OTHER"; else failure = type;
        var f = fixture(1, 7); var amInput = base.fixture(2, "SYNTH2", "HGNC:2", "MONDO:0000002", "ENST000002.4", null);
        var am = base.new FixtureProvider("0.01", false, false);
        var result = dispatcher(am).dispatch(base.assembly(List.of(f, amInput)), List.of(f.candidate(), amInput.candidate()), base.analysis);
        var a = result.candidates().get(0).attempts().get(0);
        assertTrue(a.blockers().isEmpty(), a.blockers().toString());
        assertEquals("NOT_EVALUATED", a.criterionEvaluations().get(1).at("/evaluationResult/status").asText());
        assertEquals("NOT_EVALUATED", a.criterionEvaluations().get(2).at("/evaluationResult/status").asText());
        assertEquals(1, am.calls); assertEquals(1, batches);
        assertEquals("MET", result.candidates().get(1).attempts().get(0).criterionEvaluations().get(1).at("/evaluationResult/status").asText());
    }
    @Test void duplicateAlleleContextsRetainRowsWithOneRawQuery() throws Exception {
        var a = fixture(1, 7); var b = base.fixture(2,"SYNTH1","HGNC:1","MONDO:0000001","ENST000002.4",null); setTranscript(b,7);
        var result = dispatcher(base.new FixtureProvider("0.99", false, false)).dispatch(base.assembly(List.of(a,b)), List.of(a.candidate(), b.candidate()), base.analysis);
        assertEquals(1, batches); assertEquals(1, queryCount); assertEquals(2, result.candidates().size());
        var one = result.candidates().get(0).attempts().get(0); var two = result.candidates().get(1).attempts().get(0);
        assertTrue(one.blockers().isEmpty(), one.blockers().toString()); assertTrue(two.blockers().isEmpty(), two.blockers().toString());
        assertNotEquals(one.context().path("contextId"), two.context().path("contextId"));
        assertNotEquals(one.atomicEvidence().get(0).path("evidenceId"), two.atomicEvidence().get(0).path("evidenceId"));
    }
    @Test void missingPositionAndCspecNeverStartRaw() throws Exception {
        var f = fixture(1, null);
        var result = dispatcher(base.new FixtureProvider("0.01", false, false)).dispatch(base.assembly(List.of(f)), List.of(f.candidate()), base.analysis);
        assertEquals("TRANSCRIPT_RELATIVE_POSITION_MISSING", result.candidates().get(0).attempts().get(0).blockers().get(0).reasonCode()); assertEquals(0,batches);
        var c = base.fixture(1,"CDH1","HGNC:1748","MONDO:0007648","NM_004360.5",null);
        setTranscript(c,7);
        result = dispatcher(base.new FixtureProvider("0.01", false, false)).dispatch(base.assembly(List.of(c)),List.of(c.candidate()),base.analysis);
        assertEquals(0,batches); assertEquals(0,result.providerCalls()); assertFalse(result.candidates().get(0).attempts().get(0).blockers().isEmpty());
    }
    ExplicitAlphaMissenseDispatcherTest.Fixture fixture(int ordinal, Integer offset) throws Exception {
        var f=base.fixture(ordinal,"SYNTH1","HGNC:1","MONDO:0000001","ENST000001.4",null); setTranscript(f,offset); return f;
    }
    void setTranscript(ExplicitAlphaMissenseDispatcherTest.Fixture f,Integer offset) {
        var t=f.candidate().getTranscriptAnnotations().get(0);
        f.candidate().applyTranscriptAnnotations(List.of(new TranscriptConsequence(t.getGeneSymbol(), null, t.getTranscriptAccession(),
                t.getVersionedTranscriptId()+":c.100"+(offset==null?"_101del":(offset>0?"+":"")+offset+"A>G"),null,"intron_variant",
                t.getGeneId(),t.getTranscriptId(),"protein_coding","MODIFIER",null,"1/2",true,true,false,
                null,null,null,null,null,null,"VEP_SYNTHETIC","COMPLETED","116.0", Instant.parse("2026-09-01T00:00:00Z"))));
    }
    ExplicitAlphaMissenseDispatcher dispatcher(AlphaMissenseProvider am) {
        return new ExplicitAlphaMissenseDispatcher(base.mapper,am,()->new SpliceAiBusinessRuntime(base.mapper,queries->{
            batches++; queryCount=queries.size();
            if(failure!=null) return "TIMEOUT".equals(failure)?SpliceAiRawBatchRunner.BatchResult.timeout(null):SpliceAiRawBatchRunner.BatchResult.failed("SYNTHETIC_FAILED",null);
            ObjectNode result=base.mapper.createObjectNode(); var rows=result.putArray("results");
            for(var q:queries){
                var row=rows.addObject().put("queryId",q.getQueryId()).put("status","HIT").put("exactAlleleMatch",true);
                var key=q.getKey(); row.putObject("allele").put("assembly",key.getGenomeAssembly()).put("contig",key.getChromosome()).put("position",key.getPosition()).put("reference",key.getReference()).put("alternate",key.getAlternate());
                var a=row.putArray("annotations").addObject().put("geneSymbol",gene).put("maximumRawScore",score);
                var ds=a.putObject("deltaScores"); var dp=a.putObject("deltaPositions");
                for(String name:List.of("AG","AL","DG","DL")){ds.put("DS_"+name,score);dp.put("DP_"+name,"0");}
            }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(result,0);
        }));
    }
}
