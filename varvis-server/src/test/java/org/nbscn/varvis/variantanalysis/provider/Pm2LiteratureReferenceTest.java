package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

class Pm2LiteratureReferenceTest {
    final Pm2LocalExomesTest f=new Pm2LocalExomesTest();
    VariantInterpretation reference(ObjectNode context) {
        var a=context.at("/variant/evidenceQueryAllele");
        var key=new NormalizedVariantKey(a.path("assembly").asText(),a.path("contig").asText(),
                a.path("position").asInt(),a.path("reference").asText(),a.path("alternate").asText());
        return new VariantInterpretation(key.canonicalId(),new VariantQcObservation(key,null,null,null,null,
                null,null,null,null,null),List.of(),List.of());
    }
    AnalysisContext analysis=new AnalysisContext("literature-test",GenomeAssembly.GRCh38,AnalysisMode.WES);

    @Test void verifiedRawReuseEvaluatesReferenceWithoutPatientGenotypeOrProviderCall() throws Exception {
        var c=f.context(9);var provider=mock(VariantEvidenceProvider.class);
        var runtime=new Pm2LocalExomesRuntime(f.mapper,Pm2LocalExomesProfile.synthetic(f.mapper,List.of(f.policy(9))),provider);
        var raw=f.observation(c,f.info("1","2000","0.000001"),"PASS");
        var r=runtime.evaluateReference(c,reference(c),analysis,Pm2LocalExomesTest.SOURCE,raw);
        assertEquals("MET",r.at("/evaluation/evaluationResult/status").asText(),r.toPrettyString());
        assertFalse(r.path("patientCandidate").asBoolean());
        assertTrue(r.path("candidateId").isNull());
        var again=runtime.evaluateReference(c,reference(c),analysis,Pm2LocalExomesTest.SOURCE,null);
        assertEquals("MET",again.at("/evaluation/evaluationResult/status").asText(),again.toPrettyString());
        assertEquals(0,runtime.providerCalls());verifyNoInteractions(provider);
    }

    @Test void mismatchedAlleleIsRejectedBeforePopulationAcquisition() throws Exception {
        var c=f.context(9);var provider=mock(VariantEvidenceProvider.class);
        var runtime=new Pm2LocalExomesRuntime(f.mapper,Pm2LocalExomesProfile.synthetic(f.mapper,List.of(f.policy(9))),provider);
        var wrong=c.deepCopy();wrong.withObject("/variant/evidenceQueryAllele").put("position",1);
        var r=runtime.evaluateReference(c,reference(wrong),analysis,Pm2LocalExomesTest.SOURCE,null);
        assertEquals("PM2_QUERY_CONTEXT_ALLELE_MISMATCH",r.at("/evaluationResult/reasonCode").asText());
        assertEquals(0,runtime.providerCalls());assertEquals(0,runtime.evaluatorCalls());verifyNoInteractions(provider);
    }

    @Test void pm3BridgeBindsBackgroundBeforeQueryAndReevaluatesRawFacts() throws Exception {
        var c=f.context(9);var provider=mock(VariantEvidenceProvider.class);
        var profile=Pm2LocalExomesProfile.synthetic(f.mapper,List.of(f.policy(9)));
        var runtime=new Pm2LocalExomesRuntime(f.mapper,profile,provider);
        var bridge=new Pm3RarityQualification(f.mapper);
        var wrong=c.deepCopy();wrong.withObject("/disease").put("mondoId","MONDO:9999999");
        var rejected=bridge.acquire(runtime,c.at("/variant/evidenceQueryAllele"),wrong,c,reference(c),analysis,
                profile,Pm2LocalExomesTest.SOURCE,null);
        assertEquals("CONFLICT",rejected.path("qualification").asText());
        assertEquals(0,runtime.evaluatorCalls());verifyNoInteractions(provider);
        var raw=f.observation(c,f.info("1","2000","0.000001"),"PASS");
        var accepted=bridge.acquire(runtime,c.at("/variant/evidenceQueryAllele"),c,c,reference(c),analysis,
                profile,Pm2LocalExomesTest.SOURCE,raw);
        assertEquals("VERIFIED",accepted.path("qualification").asText(),accepted.toPrettyString());
        assertEquals(1,accepted.path("qualificationEvaluatorCalls").asInt());
        assertFalse(accepted.path("wholeGnomadConclusion").asBoolean());
        verifyNoInteractions(provider);
    }
}
