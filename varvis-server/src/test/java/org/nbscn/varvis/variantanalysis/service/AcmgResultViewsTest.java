package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AcmgResultViewsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode fixture() {
        var run=mapper.createObjectNode().put("analysisId","a").put("runId","r").put("status","COMPLETED_WITH_BLOCKERS");
        run.putObject("localBatch").put("duplicatedMaterial","not for the index");
        run.putArray("candidateIds").add("c1").add("c2");
        var candidates=run.putArray("candidates");
        for (String id:new String[]{"c1","c2"}) {
            var c=candidates.addObject().put("candidateId",id);
            c.putObject("ba1").put("keep","whole allele result");
            c.putObject("ba1ResultSelection").put("effectiveResultPath","/attempts/1/ba1");
            var attempts=c.putArray("attempts");
            for(int i=0;i<2;i++) {
                var a=attempts.addObject().put("candidateId",id).put("hypothesisId",id+"-"+i);
                a.putObject("context").put("contextId",id+"-ctx-"+i);
                a.putObject("localBatch").put("unchanged","status/strength/claims/refs "+i);
            }
        }
        return run;
    }
    @Test void indexIsNotAResultAndBackgroundPreservesOriginalPositionsAndAllSelectedFacts() {
        var full=fixture(); String before=full.toString();
        var index=AcmgResultViews.index(full,"sha");
        assertEquals(AcmgResultViews.INDEX,index.path("viewKind").asText());
        assertFalse(index.path("completeExport").asBoolean());
        assertFalse(index.has("localBatch"));
        assertFalse(index.at("/candidates/0/attempts/0").has("localBatch"));
        for (int c=0;c<2;c++) for(int a=0;a<2;a++) {
            var view=AcmgResultViews.background(full,"sha","c"+(c+1),a);
            assertEquals(full.at("/candidates/"+c+"/attempts/"+a),view.at("/candidates/0/attempts/"+a));
            assertEquals(full.at("/candidates/"+c+"/ba1"),view.at("/candidates/0/ba1"));
            assertEquals(2,view.at("/candidates/0/attempts").size());
        }
        assertEquals(before,full.toString());
        assertThrows(IllegalArgumentException.class,()->AcmgResultViews.background(full,"sha","absent",0));
        assertThrows(IllegalArgumentException.class,()->AcmgResultViews.background(full,"sha","c1",2));
    }
    @Test void indexRetainsDeclaredBackgroundIdentityWithoutBindingProvenance() {
        var full = fixture();
        var attempt = (ObjectNode) full.at("/candidates/0/attempts/0");
        attempt.putNull("context").putObject("binding").put("candidateId", "c1").put("hypothesisId", "c1-0")
                .put("mondoId", "MONDO:0007947").putObject("provenance").put("privateDetail", "raw");
        var binding = AcmgResultViews.index(full, "sha").at("/candidates/0/attempts/0/binding");
        assertEquals("MONDO:0007947", binding.path("mondoId").asText());
        assertFalse(binding.has("provenance"));
        assertTrue(AcmgResultViews.index(full, "sha").at("/candidates/0/attempts/0/context").isNull());
    }
    @Test void cacheRequiresLiveFingerprintAndDoesNotLeakMutableViewsOrMixRuns() {
        var cache=new AcmgResultViews(); var loads=new AtomicInteger(); var full=fixture();
        java.util.function.Supplier<ObjectNode> loader=()->{loads.incrementAndGet();return full.deepCopy();};
        var index=cache.read(()->"stored-bytes-a",loader,null,0,null);
        String sha=index.path("sourceViewSha256").asText();
        index.at("/candidates/0/attempts/0/context").deepCopy();
        ((ObjectNode)index.at("/candidates/0/attempts/0/context")).put("contextId","tampered");
        var view=cache.read(()->"stored-bytes-a",loader,"c1",0,sha);
        assertEquals("c1-ctx-0",view.at("/candidates/0/attempts/0/context/contextId").asText());
        assertEquals(1,loads.get());
        assertThrows(IllegalStateException.class,()->cache.read(()->"stored-bytes-a",loader,"c1",0,"wrong"));
        full.put("status","COMPLETED");
        assertThrows(IllegalStateException.class,()->cache.read(()->"changed-actual-bytes",loader,"c1",0,sha));
        assertEquals(2,loads.get());
    }
    @Test void concurrentChangeAndIntegrityFailureCannotEnterCacheAndPendingIsNotCached() {
        var cache=new AcmgResultViews(); var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->cache.read(()->"version"+calls.incrementAndGet(),this::fixture,null,0,null));
        assertThrows(IllegalStateException.class,()->cache.read(()->"v",()->{throw new IllegalStateException("corrupt private");},null,0,null));
        var pending=mapper.createObjectNode().put("status","RUNNING"); calls.set(0);
        for(int i=0;i<2;i++) assertEquals(pending,cache.read(()->"v",()->{calls.incrementAndGet();return pending;},null,0,null));
        assertEquals(2,calls.get());
    }
    @Test void submissionResponseOnlyProjectsOnExplicitOptIn() {
        var full=fixture(); var original=full.deepCopy();
        assertSame(full,AcmgResultViews.submissionView(full,null));
        assertSame(full,AcmgResultViews.submissionView(full,"unknown"));
        assertEquals(AcmgResultViews.INDEX,AcmgResultViews.submissionView(full,"index").path("viewKind").asText());
        assertEquals(original,full);
        var pending=mapper.createObjectNode().put("status","RUNNING");
        assertSame(pending,AcmgResultViews.submissionView(pending,"index"));
    }
    @Test void lazyViewsKeepIndependentSavedRootBindingsWithoutDuplicatingScientificPayloads() {
        var full=fixture(); full.put("executionSourceSha256","a".repeat(64));
        var batch=(ObjectNode)full.path("localBatch");
        batch.put("schemaVersion","local-evidence-batch-v1").put("sourceSnapshotSha256","a".repeat(64));
        batch.putObject("identity").putObject("fullRnaExecution").put("capabilityConfigured",true);
        batch.putObject("fullRnaObservedSourceIdentity").put("archiveRequired",true).put("indexSha256","b".repeat(64));
        batch.put("fullRnaArchiveSha256","c".repeat(64));
        batch.putObject("modules").put("largeOriginal","private scientific payload");
        batch.putArray("results").addObject().put("largeTrace","not part of the header");
        batch.putObject("fullRnaArchive").put("originalUtf8","never a lazy header field");
        String original=full.toString();
        var cache=new AcmgResultViews();
        var index=cache.read(()->"fixed-stored-bytes",()->full.deepCopy(),null,0,null);
        String sha=index.path("sourceViewSha256").asText();
        assertEquals(org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(original),sha);
        var background=cache.read(()->"fixed-stored-bytes",()->{fail("verified cache must be reused");return full;},"c1",1,sha);
        var submission=AcmgResultViews.submissionView(full,"index");
        for(var view:java.util.List.of(index,background,submission)) {
            assertEquals(full.path("executionSourceSha256"),view.path("executionSourceSha256"));
            assertEquals(5,view.path("localBatch").size());
            for(String key:new String[]{"schemaVersion","sourceSnapshotSha256","identity","fullRnaObservedSourceIdentity","fullRnaArchiveSha256"})
                assertEquals(batch.path(key),view.path("localBatch").path(key));
            assertFalse(view.path("localBatch").has("modules"));
            assertFalse(view.path("localBatch").has("results"));
            assertFalse(view.path("localBatch").has("fullRnaArchive"));
            assertFalse(view.path("localBatch").has("duplicatedMaterial"));
            assertEquals(sha,view.path("sourceViewSha256").asText());
        }
        ((ObjectNode)index.at("/localBatch/identity/fullRnaExecution")).put("capabilityConfigured",false);
        assertTrue(background.at("/localBatch/identity/fullRnaExecution/capabilityConfigured").asBoolean());
        assertEquals(original,full.toString());
    }
}
