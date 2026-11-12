package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MissenseWideSpliceBridgeTest {
    final MissenseNativeSpliceBridgeTest f=new MissenseNativeSpliceBridgeTest();
    MissenseNativeSpliceBridge bridge() throws Exception {
        f.input=f.fixture.input();
        return new MissenseNativeSpliceBridge(f.mapper,(c,s,n)->new CodingTranscriptMaterialSupply.ReferenceSpan(
            f.input.candidate().getObservation().getKey().getReference().repeat(n),f.mapper.createObjectNode()
                .put("expectedSha256",f.input.context().at("/variant/normalization/referenceSequenceSha256").asText())),
            f.mapper.createObjectNode().put("version","1.2.0").put("executionDistanceBp",10000),Duration.ofSeconds(120),f.clock::get,
            (request,budget)->{
                f.calls.incrementAndGet();assertEquals(10000,request.path("distanceBp").asInt());
                var response=f.mapper.createObjectNode().put("status","SUCCEEDED").put("annotationFileSha256","a".repeat(64));
                for(var unit:request.path("variants")) {
                    var row=f.fixture.fact(f.input);row.set("queryId",unit.path("queryId"));row.set("allele",unit.path("allele"));
                    ((ObjectNode)row.path("referenceWindow")).put("start",unit.at("/allele/position").asInt()-15000)
                        .put("length",30001).put("sha256",CodingTranscriptMaterialSupply.sha(unit.at("/allele/reference").asText().repeat(30001)));
                    row.put("distanceBp",10000).put("mask",0);
                    for(String k:java.util.List.of("AG","AL","DG","DL")) {
                        ((ObjectNode)row.at("/annotations/0/deltaScores")).put("DS_"+k,"0.10");
                        ((ObjectNode)row.at("/annotations/0/deltaPositions")).put("DP_"+k,10000);
                        row.withObject("/officialRefAltScores").putObject(k).put("position",10000)
                            .put("referenceScore",k.endsWith("G")?"0.4":"0.5").put("alternateScore",k.endsWith("G")?"0.5":"0.4");
                    }
                    response.withArray("results").add(row);
                }
                f.mutate.accept(response);return response;
            },10000);
    }
    @Test void wholeWindowAndScoreWitnessThenExactReuse() throws Exception {
        var b=bridge();var one=b.run(f.input.context(),f.input.model(),f.queries(1));assertEquals("SUCCEEDED",one.getStatus(),one.getReasonCode());
        assertEquals(10000,b.identity().path("distance").asInt());assertEquals("SUCCEEDED",b.run(f.input.context(),f.input.model(),f.queries(1)).getStatus());assertEquals(1,f.calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","POSITION","VALUE","DIFFERENCE","WINDOW","DISTANCE","DP"})
    void wrongOrShortWindowFactNeverAccepted(String fault) throws Exception {
        var b=bridge();f.mutate=response->{var row=(ObjectNode)response.at("/results/0");switch(fault){
            case "MISSING"->row.remove("officialRefAltScores");
            case "POSITION"->((ObjectNode)row.at("/officialRefAltScores/AG")).put("position",9999);
            case "VALUE"->((ObjectNode)row.at("/officialRefAltScores/AG")).put("referenceScore","NaN");
            case "DIFFERENCE"->((ObjectNode)row.at("/officialRefAltScores/AG")).put("referenceScore","0.1");
            case "WINDOW"->((ObjectNode)row.path("referenceWindow")).put("length",19999);
            case "DISTANCE"->row.put("distanceBp",4999);
            default->((ObjectNode)row.at("/annotations/0/deltaPositions")).put("DP_AG",10001);
        }};
        var result=b.run(f.input.context(),f.input.model(),f.queries(1));assertEquals("FAILED",result.getStatus());
        assertEquals("FAILED",b.run(f.input.context(),f.input.model(),f.queries(1)).getStatus());assertEquals(1,f.calls.get());
    }
}
