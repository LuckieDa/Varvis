package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcmgControlReadAndReuseTest {
    final ObjectMapper json=new ObjectMapper();
    final AcmgBusinessStore store=mock(AcmgBusinessStore.class);
    final AcmgBusinessService service=mock(AcmgBusinessService.class,CALLS_REAL_METHODS);
    final AcmgBusinessArtifacts artifacts=new AcmgBusinessArtifacts(json);
    AcmgControlReadAndReuseTest() {
        var jobs=mock(VariantAnalysisJobRepository.class);
        when(jobs.findById("a")).thenReturn(Optional.of(mock(VariantAnalysisJob.class)));
        ReflectionTestUtils.setField(requireNonNull(service),"mapper",json);ReflectionTestUtils.setField(requireNonNull(service),"jobs",jobs);
        ReflectionTestUtils.setField(requireNonNull(service),"store",store);ReflectionTestUtils.setField(requireNonNull(service),"artifacts",artifacts);
    }
    @Test void oldRunningRecordIsUnknownNotAutomaticExecutionAndListDoesNotReadPayloads() {
        var row=new AcmgBusinessStore.Run("r","a",artifacts.requestHash(json.createObjectNode()),"{}","RUNNING","now",null,null,null);
        when(store.runs("a")).thenReturn(List.of(row));when(store.metadata("a","r")).thenReturn(Optional.of(row));when(store.run("a","r")).thenReturn(Optional.of(row));
        assertEquals("RECOVERY_REQUIRED",service.list("a").get(0).path("status").asText());verify(store,never()).run(anyString(),anyString());
        assertEquals("RECOVERY_REQUIRED",service.read("a","r").path("status").asText());
        verify(service,never()).submit(anyString(),any(byte[].class));
    }
    @Test void twoAllelesUseOneFullyVerifiedHistoryReadAndNewInvocationRechecksIntegrity() throws Exception {
        String hash=artifacts.requestHash(json.createObjectNode());String privateJson="{\"declaration\":{}}";
        var response=json.createObjectNode().put("runId","r").put("analysisId","a").put("requestSha256",hash).put("status","COMPLETED")
                .put("privateArtifactSha256",AcmgBusinessStore.sha(privateJson));
        var rows=response.putArray("candidates");
        for(String key:List.of("k1","k2")) {
            var ba1=rows.addObject().put("candidateId",key).putObject("ba1").put("reuseKey",key);
            ba1.putObject("evidence").putObject("resourceExecution").put("status","SUCCEEDED");
            ba1.withObject("/evidence").putObject("resourceAvailability").put("status","AVAILABLE");ba1.putObject("evaluation");
        }
        String raw=response.toString();
        var row=new AcmgBusinessStore.Run("r","a",hash,"{}","COMPLETED","now",AcmgBusinessStore.sha(raw),raw,privateJson);
        when(store.runs("a")).thenReturn(List.of(row));when(store.run("a","r")).thenReturn(Optional.of(row));
        var cache=new AcmgBusinessService.Ba1HistoryIndex();
        ObjectNode first=ReflectionTestUtils.invokeMethod(requireNonNull(service),"reusableBa1","a","k1",cache);
        ObjectNode second=ReflectionTestUtils.invokeMethod(requireNonNull(service),"reusableBa1","a","k2",cache);
        assertEquals("k1",requireNonNull(first).at("/ba1/reuseKey").asText());assertEquals("k2",requireNonNull(second).at("/ba1/reuseKey").asText());
        verify(store,times(1)).run("a","r");
        verify(store,times(1)).runs("a");
        first.removeAll();
        ObjectNode again=ReflectionTestUtils.invokeMethod(requireNonNull(service),"reusableBa1","a","k1",cache);
        assertEquals("k1",requireNonNull(again).at("/ba1/reuseKey").asText());
        assertNull(ReflectionTestUtils.invokeMethod(requireNonNull(service),"reusableBa1","a","missing",cache));
        assertNull(ReflectionTestUtils.invokeMethod(requireNonNull(service),"reusableBa1","a","missing",cache));
        verify(store,times(1)).runs("a");
        var corrupt=new AcmgBusinessStore.Run("r","a",hash,"{}","COMPLETED","now","bad",raw,privateJson);
        when(store.run("a","r")).thenReturn(Optional.of(corrupt));
        assertNull(ReflectionTestUtils.invokeMethod(requireNonNull(service),"reusableBa1","a","k1",new AcmgBusinessService.Ba1HistoryIndex()));
        verify(store,times(2)).run("a","r");
    }
}
