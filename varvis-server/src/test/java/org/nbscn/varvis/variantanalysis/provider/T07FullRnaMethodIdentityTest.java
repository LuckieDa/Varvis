package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exact presentation identity, not a source token or a grant of scientific authority. */
class T07FullRnaMethodIdentityTest {
    @TempDir Path root;
    @Test void actualCurrentSourceRuntimeKeepsTheCorrectedExecutedTuple() throws Exception {
        var f=new FullRnaInitiationFixture(root,1);var source=f.rna.source();
        var runtime=new TruncatingNmdPvs1Runtime(f.rna.f.mapper,new TruncatingNmdPvs1RuntimeTest().resourceProfile(),f.rna.f.model,true);
        var raw=runtime.evaluateFullRna(f.rna.f.context,f.rna.f.candidate,f.data.policy(),f.data.selection(),source);
        assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);
        var actual=FullRnaCurrentUse.pvs1MethodMetadata(f.rna.f.mapper,raw,false,"1.10.1");
        assertEquals("1.10.1",actual.path("methodVersion").asText());assertEquals("EXECUTED",actual.path("evaluationState").asText());
        assertEquals("1.10.1",actual.path("evaluationMethodVersion").asText());
        assertEquals("1.10.1",Pvs1SelectedBatch.publicSummary(f.rna.f.mapper,raw).path("methodVersion").asText());
        assertThrows(IllegalArgumentException.class,()->FullRnaCurrentUse.pvs1MethodMetadata(f.rna.f.mapper,raw,false,"1.9.0"));
    }
    @ParameterizedTest @CsvSource({"1.10.1,1.1.0","1.10.0,1.1.1","1.9.0,1.1.1","1.10.2,1.1.1"})
    void mixedOrUnknownTuplesCannotBorrowTheNewRegistryEntry(String evaluator,String module) {
        var mapper=new ObjectMapper();var raw=envelope(mapper,evaluator,module);
        assertThrows(IllegalArgumentException.class,()->FullRnaCurrentUse.pvs1MethodMetadata(mapper,raw,false,evaluator));
        assertThrows(IllegalArgumentException.class,()->Pvs1SelectedBatch.publicSummary(mapper,raw));
    }
    @Test void unexecutedSavedCurrentTargetIsNotReportedAsActualExecution() {
        var mapper=new ObjectMapper();var raw=envelope(mapper,"1.10.1","1.1.1");raw.remove("resolvedInput");
        ((ObjectNode)raw.path("evaluation")).remove("deterministicTrace");
        raw.putObject("publicSummary").put("schemaVersion","pvs1-selected-facts-v1").put("method","SOURCE_BOUND_FULL_RNA_CONSEQUENCE").put("methodVersion","1.10.1");
        var actual=Pvs1SelectedBatch.publicSummary(mapper,raw);
        assertEquals("1.10.1",actual.path("methodVersion").asText());assertEquals("NOT_EXECUTED",actual.path("evaluationState").asText());
        assertTrue(actual.path("evaluationMethodVersion").isNull());
        assertThrows(IllegalArgumentException.class,()->FullRnaCurrentUse.pvs1MethodMetadata(mapper,raw,false,"1.9.0"));
    }
    @Test void currentParentTupleDoesNotAuthorizeAnUnknownInitiationChild() {
        var mapper=new ObjectMapper();var raw=envelope(mapper,"1.10.1","1.1.1");
        raw.withObject("resolvedInput").putObject("fullRnaInitiationAssessment").put("schemaVersion","pvs1-full-rna-initiation-assessment-v1").put("methodVersion","1.0.1");
        assertThrows(IllegalArgumentException.class,()->FullRnaCurrentUse.pvs1MethodMetadata(mapper,raw,false,"1.10.1"));
    }
    private static ObjectNode envelope(ObjectMapper mapper,String evaluator,String module) {
        var raw=mapper.createObjectNode();var evaluation=raw.putObject("evaluation");
        evaluation.putObject("evaluationResult").put("status","NOT_EVALUATED").put("reasonCode","SYNTHETIC_METADATA_CONTRACT_NOT_SCIENTIFIC_EXECUTION");
        evaluation.putObject("deterministicTrace").put("evaluatorId",TruncatingNmdPvs1Runtime.EVALUATOR_ID).put("evaluatorVersion",evaluator);
        raw.putObject("resolvedInput").putObject("fullRnaAssessment").put("schemaVersion","pvs1-full-rna-assessment-v1").put("methodVersion",module).put("status","NOT_EVALUATED");
        return raw;
    }
}
