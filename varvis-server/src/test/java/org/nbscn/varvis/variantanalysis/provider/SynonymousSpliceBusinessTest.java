package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class SynonymousSpliceBusinessTest {
    @ParameterizedTest @CsvSource({"0.10,NONE,MET", "0.11,NONE,NOT_MET", "0.20,NONE,NOT_MET", "0.01,TIMEOUT,NOT_EVALUATED", "0.01,FAILED,NOT_EVALUATED"})
    void synonymousRequiresValidSameRunBp4(String score, String failure, String expected) throws Exception {
        var helper = new SpliceAiBusinessDispatchTest(); helper.score=score;
        helper.failure="NONE".equals(failure)?null:failure;
        var f=helper.fixture(1,7);var t=f.candidate().getTranscriptAnnotations().get(0);var key=f.candidate().getObservation().getKey();
        var proof=new SynonymousExonPosition("GRCh38",key.getChromosome(),key.getPosition(),key.getReference(),key.getAlternate(),
                t.getVersionedTranscriptId(),"ENSE000001",1,key.getPosition()-1,key.getPosition()+8,"ENSE000001|1|start|10","116.0",SynonymousExonPosition.PLUGIN_SHA256);
        var syn=new TranscriptConsequence(t.getGeneSymbol(),null,t.getTranscriptAccession(),t.getVersionedTranscriptId()+":c.30A>G",null,
                "synonymous_variant",t.getGeneId(),t.getTranscriptId(),"protein_coding","LOW","1/2",null,true,true,false,
                null,null,null,null,null,null,"VEP_SYNTHETIC","COMPLETED","116.0",t.getTimestamp()).withSynonymousExonPosition(proof);
        f.candidate().applyTranscriptAnnotations(List.of(syn));
        var result=helper.dispatcher(helper.base.new FixtureProvider("0.99",false,false)).dispatch(helper.base.assembly(List.of(f)),List.of(f.candidate()),helper.base.analysis);
        var a=result.candidates().get(0).attempts().get(0);assertTrue(a.blockers().isEmpty());
        assertEquals(expected,a.criterionEvaluations().get(1).at("/evaluationResult/status").asText());
        assertEquals(expected,a.criterionEvaluations().get(2).at("/evaluationResult/status").asText());
        assertEquals(1,helper.batches);
    }
    @ParameterizedTest
    @CsvSource({"1,0,NOT_APPLICABLE", "1,1,MET", "1,96,MET", "1,97,NOT_APPLICABLE", "1,98,NOT_APPLICABLE", "1,99,NOT_APPLICABLE",
        "-1,0,NOT_APPLICABLE", "-1,1,MET", "-1,96,MET", "-1,97,NOT_APPLICABLE", "-1,98,NOT_APPLICABLE", "-1,99,NOT_APPLICABLE"})
    void boundariesUseTranscriptDirection(int strand, int offset, String expected) throws Exception {
        var helper = new SpliceAiBusinessDispatchTest();
        var f = helper.fixture(1, 7);
        var t = f.candidate().getTranscriptAnnotations().get(0);
        var key = f.candidate().getObservation().getKey();
        long start = key.getPosition() - (strand == 1 ? offset : 99 - offset);
        var proof = new SynonymousExonPosition("GRCh38", key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate(),
                t.getVersionedTranscriptId(), "ENSE000001", strand, start, start + 99,
                "ENSE000001|" + Math.min(offset, 99-offset) + "|" + ((strand == 1 ? offset : 99-offset) < 50 ? "start" : "end") + "|100",
                "116.0", SynonymousExonPosition.PLUGIN_SHA256);
        var syn = new TranscriptConsequence(t.getGeneSymbol(), null, t.getTranscriptAccession(), t.getVersionedTranscriptId()+":c.30A>G",
                "ENSP000001.1:p.Gly10=", "synonymous_variant", t.getGeneId(), t.getTranscriptId(), "protein_coding", "LOW", "1/3", null,
                true, true, false, "10", "G", "ggA/ggG", null, null, null, "VEP_SYNTHETIC", "COMPLETED", "116.0", t.getTimestamp())
                .withSynonymousExonPosition(proof);
        // Simulate persisted publication and service reconstruction before dispatch.
        var persistedMapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
        syn = persistedMapper.treeToValue(persistedMapper.valueToTree(syn), TranscriptConsequence.class);
        assertEquals(proof, syn.getSynonymousExonPosition());
        f.candidate().applyTranscriptAnnotations(List.of(syn));
        var am = helper.base.new FixtureProvider("0.99", false, false);
        var result = helper.dispatcher(am).dispatch(helper.base.assembly(List.of(f)), List.of(f.candidate()), helper.base.analysis);
        var a = result.candidates().get(0).attempts().get(0);
        assertTrue(a.blockers().isEmpty(), a.blockers().toString());
        assertEquals("SPLICEAI_SYNONYMOUS", a.transcriptInput().path("predictionPath").asText());
        assertEquals(0, am.calls); assertEquals(1, helper.batches);
        assertEquals("MET", a.criterionEvaluations().get(1).at("/evaluationResult/status").asText());
        var bp7 = a.criterionEvaluations().get(2);
        assertEquals(expected.equals("NOT_APPLICABLE") ? "NOT_EVALUATED" : expected, bp7.at("/evaluationResult/status").asText());
        if (expected.equals("NOT_APPLICABLE")) assertEquals(expected, bp7.at("/criterionApplicability/status").asText());
        assertEquals(1, new AcmgBusinessArtifacts(helper.base.mapper).bundles(result).size());
    }

    @Test @SuppressWarnings("unchecked")
    void existingIntronicRuntimeRemainsExecutable() throws Exception {
        var helper = new SpliceAiBusinessDispatchTest(); var f = helper.fixture(1,7);
        var dispatcher = helper.dispatcher(helper.base.new FixtureProvider("0.99",false,false));
        var result = dispatcher.dispatch(helper.base.assembly(List.of(f)), List.of(f.candidate()), helper.base.analysis);
        var a = result.candidates().get(0).attempts().get(0);
        var factory = (Supplier<SpliceAiBusinessRuntime>)Objects.requireNonNull(org.springframework.test.util.ReflectionTestUtils.getField(dispatcher,"spliceRuntime"));
        var runtime = Objects.requireNonNull(factory.get());
        var input = new SpliceAiBusinessRuntime.Input(a.context(), f.candidate(), f.candidate().getTranscriptAnnotations().get(0), a.preflight().selectionId());
        var output = runtime.evaluate(input,helper.base.analysis,runtime.compute(List.of(input)));
        assertEquals("MET",output.evaluations().get(2).at("/evaluationResult/status").asText());
    }
}
