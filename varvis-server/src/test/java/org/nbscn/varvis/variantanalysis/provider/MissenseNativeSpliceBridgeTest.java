package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import static org.junit.jupiter.api.Assertions.*;

class MissenseNativeSpliceBridgeTest {
    final NativeSpliceAiRuntimeTest fixture = new NativeSpliceAiRuntimeTest();
    final com.fasterxml.jackson.databind.ObjectMapper mapper = fixture.f.mapper;
    final AtomicInteger calls = new AtomicInteger(); final AtomicLong clock = new AtomicLong();
    NativeSpliceAiRuntimeTest.Fixture input;
    Consumer<ObjectNode> mutate = response -> { };

    MissenseNativeSpliceBridge bridge() throws Exception {
        input = fixture.input();
        return new MissenseNativeSpliceBridge(mapper, (c,s,n) -> new CodingTranscriptMaterialSupply.ReferenceSpan(
                input.candidate().getObservation().getKey().getReference().repeat(n), mapper.createObjectNode()
                        .put("expectedSha256", input.context().at("/variant/normalization/referenceSequenceSha256").asText())),
                mapper.createObjectNode().put("test", "SYNTHETIC_TRANSPORT"), Duration.ofSeconds(120), clock::get, (request, budget) -> {
                    calls.incrementAndGet(); assertTrue(request.path("variants").size() <= 10);
                    var response = mapper.createObjectNode().put("status", "SUCCEEDED").put("annotationFileSha256", "a".repeat(64));
                    var rows = response.putArray("results");
                    for (var unit : request.path("variants")) {
                        var row = fixture.fact(input); row.set("queryId", unit.path("queryId")); row.set("allele", unit.path("allele"));
                        ((ObjectNode)row.path("referenceWindow")).put("start", unit.at("/allele/position").asInt()-9999);
                        rows.add(row);
                    }
                    mutate.accept(response); return response;
                });
    }
    List<SpliceAiRawBatchRunner.Query> queries(int count) {
        var out = new ArrayList<SpliceAiRawBatchRunner.Query>(); var base = input.candidate().getObservation().getKey();
        for (int i=0;i<count;i++) out.add(new SpliceAiRawBatchRunner.Query("Q"+i, new NormalizedVariantKey("GRCh38", base.getChromosome(),
                base.getPosition()+i, base.getReference(), base.getAlternate()), input.model().path("geneSymbol").asText(), input.model().path("transcript").asText()));
        return out;
    }
    @Test void sameFactsSharedAcrossPs1Pm5AndOutputMutationCannotPoisonCache() throws Exception {
        var b=bridge(); var q=queries(4); var before=input.context().deepCopy();
        var one=b.run(input.context(),input.model(),q); assertEquals("SUCCEEDED",one.getStatus(),one.getReasonCode());
        assertEquals(1,calls.get()); assertEquals(4,one.getResult().path("results").size());
        one.getResult().put("status","TAMPERED");
        var two=b.run(input.context(),input.model(),q); assertEquals("SUCCEEDED",two.getResult().path("status").asText());
        assertEquals(1,calls.get()); assertEquals(4,b.audit().path("reusedUnits").asInt()); assertEquals(before,input.context());
        assertFalse(b.identity().path("scientificApprovalGranted").asBoolean());
        assertEquals(b.resourceSha256(),two.getResult().path("nativeResourceSha256").asText());
        fixture.f.adapter.canonicalSha256(two.getResult());
    }
    @Test void boundedChunksAndReorderedQueryLabelsReuseFacts() throws Exception {
        var b=bridge(); var q=queries(21); assertEquals("SUCCEEDED",b.run(input.context(),input.model(),q).getStatus());
        assertEquals(3,calls.get());
        var renamed=new ArrayList<SpliceAiRawBatchRunner.Query>();
        var reverse = new ArrayList<>(q); java.util.Collections.reverse(reverse);
        for(var v:reverse)renamed.add(new SpliceAiRawBatchRunner.Query("renamed-"+v.getQueryId(),v.getKey(),v.getGeneSymbol(),v.getSelectedTranscriptId()));
        var out=b.run(input.context(),input.model(),renamed); assertEquals("SUCCEEDED",out.getStatus());
        assertEquals("renamed-Q20",out.getResult().at("/results/0/queryId").asText()); assertEquals(3,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"EXON","STRAND","GENE","DUPLICATE","REF","DP","SCORE","ALLELE","NO_RECORD","TX_RECEIPT","ANN_SHA","QUERY_ID","DUPLICATE_ROW"})
    void corruptFactsFailClosedAndAreNotRetried(String kind) throws Exception {
        var b=bridge(); mutate=response->{
            var row=(ObjectNode)response.at("/results/0");
            switch(kind){
                case "EXON" -> ((com.fasterxml.jackson.databind.node.ArrayNode)row.at("/annotationRows/0/exons/0")).set(0,mapper.getNodeFactory().numberNode(19001));
                case "STRAND" -> ((ObjectNode)row.at("/annotationRows/0")).put("strand","-");
                case "GENE" -> ((ObjectNode)row.at("/annotationRows/0")).put("geneSymbol","OTHER");
                case "DUPLICATE" -> row.withArray("annotations").add(row.at("/annotations/0").deepCopy());
                case "REF" -> ((ObjectNode)row.path("referenceWindow")).put("sha256","0".repeat(64));
                case "DP" -> ((ObjectNode)row.at("/annotations/0/deltaPositions")).put("DP_AG",5000);
                case "SCORE" -> ((ObjectNode)row.at("/annotations/0/deltaScores")).put("DS_AG","1.01");
                case "ALLELE" -> ((ObjectNode)row.at("/annotations/0")).put("allele","WRONG");
                case "NO_RECORD" -> row.put("status","NO_RECORD");
                case "TX_RECEIPT" -> ((ObjectNode)row.path("selectedAnnotation")).put("transcriptId","ENST00000000001.99");
                case "ANN_SHA" -> row.put("annotationFileSha256","b".repeat(64));
                case "QUERY_ID" -> row.put("queryId","another");
                case "DUPLICATE_ROW" -> response.withArray("results").add(row.deepCopy());
            }
        };
        var first=b.run(input.context(),input.model(),queries(1));assertEquals("FAILED",first.getStatus());
        assertEquals(first.getReasonCode(),b.run(input.context(),input.model(),queries(1)).getReasonCode()); assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"TIMEOUT","BUDGET","INTERRUPTED","CLEANUP"})
    void stopWholeRequestAfterBoundedFailure(String kind) throws Exception {
        var b=bridge(); mutate=response->{
            switch(kind){
                case "TIMEOUT" -> response.put("status","UNAVAILABLE").put("reasonCode","NATIVE_SPLICE_TIMEOUT");
                case "BUDGET" -> clock.set(Duration.ofSeconds(120).toNanos());
                case "INTERRUPTED" -> Thread.currentThread().interrupt();
                case "CLEANUP" -> response.put("status","UNAVAILABLE").put("reasonCode","NATIVE_PROCESS_CLEANUP_UNCONFIRMED");
            }
        };
        try { assertEquals("FAILED",b.run(input.context(),input.model(),queries(21)).getStatus());assertEquals(1,calls.get()); }
        finally { Thread.interrupted(); }
        assertEquals("FAILED",b.run(input.context(),input.model(),queries(22)).getStatus()); assertEquals(1,calls.get());
    }
    @Test void limitAndMissingTargetDoNotInvokeScience() throws Exception {
        var b=bridge();assertEquals("FAILED",b.run(input.context(),input.model(),queries(51)).getStatus());
        assertEquals("NATIVE_SPLICE_TARGET_QUERY_REQUIRED",b.run(input.context(),input.model(),queries(2).subList(1,2)).getReasonCode());
        assertEquals(0,calls.get());
    }
    @Test void contextAndModelMismatchCannotUseOtherTranscript() throws Exception {
        var b=bridge();var wrong=input.model().deepCopy().put("transcript","ENST00000000001.99");
        assertEquals("NATIVE_SPLICE_CONTEXT_TRANSCRIPT_MISMATCH",b.run(input.context(),wrong,queries(1)).getReasonCode()); assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void motifReadsOnlyFourQualifiedBasesAndOrientsNegativeStrand(int strand) throws Exception {
        input=fixture.input();var reads=new ArrayList<Integer>();
        var b=new MissenseNativeSpliceBridge(mapper,(contig,start,length)->{
            reads.add(start);assertEquals(2,length);
            String bases=reads.size()==1 ? strand==1 ? "GT":"AC" : strand==1 ? "AG":"CT";
            return new CodingTranscriptMaterialSupply.ReferenceSpan(bases,mapper.createObjectNode().put("expectedSha256",input.context().at("/variant/normalization/referenceSequenceSha256").asText()));
        },mapper.createObjectNode(),Duration.ofSeconds(30),clock::get,(req,budget)->{fail("Motif must not run prediction");return null;});
        var motif=mapper.createObjectNode().put("strand",strand).put("donorBoundary",100).put("acceptorBoundary",200);
        var proof=b.verifyGtAgMotif(input.context(),motif);assertEquals("GT",proof.path("donor").asText());assertEquals("AG",proof.path("acceptor").asText());
        assertEquals(strand==1?List.of(101,198):List.of(98,201),reads);
    }
    @ParameterizedTest @ValueSource(strings={"MOTIF","PIN"})
    void unqualifiedReferenceOrNonGtAgStopsBeforePrediction(String kind) throws Exception {
        input=fixture.input();
        var b=new MissenseNativeSpliceBridge(mapper,(c,s,n)->new CodingTranscriptMaterialSupply.ReferenceSpan(kind.equals("MOTIF")?"GC":"GT",mapper.createObjectNode()
            .put("expectedSha256",kind.equals("PIN")?"0".repeat(64):input.context().at("/variant/normalization/referenceSequenceSha256").asText())),mapper.createObjectNode(),Duration.ofSeconds(30),clock::get,(req,budget)->{fail("No prediction");return null;});
        assertThrows(IllegalArgumentException.class,()->b.verifyGtAgMotif(input.context(),mapper.createObjectNode().put("strand",1).put("donorBoundary",100).put("acceptorBoundary",200)));
    }
}
