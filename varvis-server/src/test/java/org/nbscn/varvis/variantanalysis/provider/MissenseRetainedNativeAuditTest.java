package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic receipts exercise persistence mechanics, never substitute for real execution evidence. */
class MissenseRetainedNativeAuditTest {
    @TempDir Path root;
    final MissenseNativeSpliceBridgeTest f = new MissenseNativeSpliceBridgeTest();
    final AtomicInteger newCalls = new AtomicInteger();
    ObjectNode audit;
    List<SpliceAiRawBatchRunner.Query> queries;

    void seed() throws Exception {
        var first = f.bridge(); queries = f.queries(3);
        var result = first.run(f.input.context(), f.input.model(), queries);
        assertEquals("SUCCEEDED", result.getStatus()); audit = first.audit();
        var response = (ObjectNode) f.mapper.readTree(audit.path("nativeReceiptsJson").asText()).get(0);
        var execution = f.mapper.readTree(result.getResult().at("/results/0/nativeExecutionReceiptJson").asText());
        response.set("request", execution.path("request")); response.set("requestId", execution.at("/request/requestId"));
        response.put("resultSchemaVersion", "spliceai-native-business-result-v1").put("rawOutputSha256", "d".repeat(64));
        response.set("identity", f.mapper.readTree(audit.at("/identity/runtimeIdentityJson").asText()));
        response.putObject("processReceipt").put("testOnly", true).put("ownedProcessesStopped", true).put("exitConfirmed", true)
                .put("rootExitCodeObserved", 0).put("activeProcessCount", 0).put("cleanupTrigger", "NORMAL_EXIT");
        setResponse(response);
    }
    ObjectNode response() throws Exception { return (ObjectNode) f.mapper.readTree(audit.path("nativeReceiptsJson").asText()).get(0); }
    void setResponse(ObjectNode response) { audit.put("nativeReceiptsJson", f.mapper.createArrayNode().add(response).toString()); }
    Path save() throws Exception { Path p=root.resolve("audit.json"); Files.write(p, f.mapper.writeValueAsBytes(audit));return p; }
    String sha(Path p) throws Exception { return CodingTranscriptMaterialSupply.sha(Files.readAllBytes(p)); }
    MissenseNativeSpliceBridge fresh() {
        return new MissenseNativeSpliceBridge(f.mapper, (c,s,n) -> new CodingTranscriptMaterialSupply.ReferenceSpan(
                f.input.candidate().getObservation().getKey().getReference().repeat(n), f.mapper.createObjectNode()
                .put("expectedSha256", f.input.context().at("/variant/normalization/referenceSequenceSha256").asText())),
                f.mapper.createObjectNode().put("test", "SYNTHETIC_TRANSPORT"), Duration.ofSeconds(120), System::nanoTime,
                (request,budget) -> { newCalls.incrementAndGet(); return f.mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "TEST_ONLY_NEW_COMPUTE"); });
    }
    @Test void aNewBridgeReusesSubsetAndNewLabelsWithoutTransportOrApproval() throws Exception {
        seed(); var p=save(); var b=fresh();b.loadRetainedAudit(p,sha(p));
        var base=queries.get(0); var renamed=new SpliceAiRawBatchRunner.Query("NEW_TARGET_LABEL",base.getKey(),base.getGeneSymbol(),base.getSelectedTranscriptId());
        var result=b.run(f.input.context(),f.input.model(),List.of(renamed,queries.get(2)));
        assertEquals("SUCCEEDED",result.getStatus(),result.getReasonCode());assertEquals(0,newCalls.get());
        assertEquals(2,b.audit().path("retainedUnitsUsed").asInt());assertEquals(0,b.audit().path("batchInvocations").asInt());
        assertEquals("NEW_TARGET_LABEL",result.getResult().at("/results/0/queryId").asText());
        var provenance=f.mapper.readTree(result.getResult().at("/results/0/nativeExecutionReceiptJson").asText());
        assertEquals(sha(p),provenance.path("retainedAuditRawSha256").asText());
        assertEquals(3,provenance.at("/request/variants").size()); // Original batch, not a forged two-unit execution.
        assertFalse(b.identity().path("scientificApprovalGranted").asBoolean());
        result.getResult().put("status","CORRUPTED_VIEW");
        assertEquals("SUCCEEDED",b.run(f.input.context(),f.input.model(),queries).getStatus());assertEquals(0,newCalls.get());
    }
    @ParameterizedTest @ValueSource(strings={"PIN","RUNTIME","PROCESS","PROCESS_BOOLEAN","EXIT","DISTANCE","MASK","REQUEST","DUPLICATE","RAW_SHA","OUTPUT_IDENTITY"})
    void invalidRetainedEnvelopeCannotBeLoaded(String kind) throws Exception {
        seed();var response=response();
        switch(kind) {
            case "RUNTIME" -> ((ObjectNode)audit.path("identity")).put("version","wrong");
            case "PROCESS" -> ((ObjectNode)response.path("processReceipt")).put("ownedProcessesStopped",false);
            case "PROCESS_BOOLEAN" -> ((ObjectNode)response.path("processReceipt")).put("ownedProcessesStopped","true");
            case "EXIT" -> ((ObjectNode)response.path("processReceipt")).put("rootExitCodeObserved",1);
            case "DISTANCE" -> ((ObjectNode)response.path("request")).put("distanceBp",10000);
            case "MASK" -> ((ObjectNode)response.path("request")).put("mask",1);
            case "REQUEST" -> ((ObjectNode)response.path("request")).put("requestId","native-"+"0".repeat(64));
            case "DUPLICATE" -> response.withArray("results").set(1,response.at("/results/0").deepCopy());
            case "RAW_SHA" -> response.remove("rawOutputSha256");
            case "OUTPUT_IDENTITY" -> response.putObject("identity").put("test","OTHER_RUNTIME");
            default -> { }
        }
        setResponse(response);var p=save();var b=fresh();String hash=kind.equals("PIN")?"f".repeat(64):sha(p);
        assertThrows(IllegalArgumentException.class,()->b.loadRetainedAudit(p,hash));assertEquals(0,newCalls.get());
    }
    @ParameterizedTest @ValueSource(strings={"SCORE","DP","WINDOW","EXON","NO_HIT","ANNOTATION","ALLELE"})
    void restoredFactsPassTheSameLiveAssociationAndScoreChecks(String kind) throws Exception {
        seed();var response=response();var value=(ObjectNode)response.at("/results/0");
        switch(kind) {
            case "SCORE" -> ((ObjectNode)value.at("/annotations/0/deltaScores")).put("DS_AG","1.2");
            case "DP" -> ((ObjectNode)value.at("/annotations/0/deltaPositions")).put("DP_AG",5000);
            case "WINDOW" -> ((ObjectNode)value.path("referenceWindow")).put("sha256","0".repeat(64));
            case "EXON" -> ((com.fasterxml.jackson.databind.node.ArrayNode)value.at("/annotationRows/0/exons/0")).set(0,f.mapper.getNodeFactory().numberNode(19001));
            case "NO_HIT" -> value.put("status","NO_RECORD");
            case "ANNOTATION" -> ((ObjectNode)value.path("selectedAnnotation")).put("transcriptId","WRONG.1");
            case "ALLELE" -> ((ObjectNode)value.at("/annotations/0")).put("allele","WRONG");
            default -> throw new AssertionError(kind);
        }
        setResponse(response);var p=save();var b=fresh();b.loadRetainedAudit(p,sha(p));
        assertEquals("FAILED",b.run(f.input.context(),f.input.model(),queries).getStatus());
        assertEquals(0,newCalls.get());assertEquals(0,b.audit().path("acceptedUnits").asInt());
    }
    @Test void transcriptOrQualifiedReferenceChangesCannotBorrowCachedFacts() throws Exception {
        seed();var p=save();var b=fresh();b.loadRetainedAudit(p,sha(p));
        var wrong=f.input.model().deepCopy().put("transcript","ENST00000000001.2");
        assertEquals("NATIVE_SPLICE_CONTEXT_TRANSCRIPT_MISMATCH",b.run(f.input.context(),wrong,queries).getReasonCode());
        var context=f.input.context().deepCopy();((ObjectNode)context.at("/variant/normalization")).put("referenceSequenceSha256","1".repeat(64));
        context.put("contextId",f.fixture.f.adapter.canonicalContextId(context));
        assertEquals("FAILED",b.run(context,f.input.model(),queries).getStatus());assertEquals(0,newCalls.get());
    }
}
