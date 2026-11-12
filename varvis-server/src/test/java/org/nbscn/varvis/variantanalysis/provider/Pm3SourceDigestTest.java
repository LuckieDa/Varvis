package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class Pm3SourceDigestTest {
    final ObjectMapper json = new ObjectMapper();

    @Test void oldIntegerOnlyDigestRemainsCompatible() throws Exception {
        var old = json.readTree("{\"z\":[1,true,null],\"a\":\"é\"}");
        assertEquals(new RuntimeEvidenceV2Adapter(json).canonicalSha256(old), Pm3SourceDigest.sha256(json,old));
    }

    @Test void decimalSourceSurvivesRoundTripAndTamperingStillFails() throws Exception {
        var content = json.createObjectNode().put("schemaVersion","pm3-business-source-snapshot-v1").put("analysisId","analysis");
        var result = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree("{\"retrievedEpoch\":1793368306.0243733,\"reportedRatio\":0.1234567890123456789012345}");
        content.putArray("documents").addObject().set("result",result);
        String original = content.toString();
        content.put("snapshotSha256",Pm3SourceDigest.sha256(json,content));
        var restored = (ObjectNode)json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(json.writeValueAsBytes(content));
        assertEquals(content,restored);
        assertEquals(original,restored.deepCopy().without("snapshotSha256").toString());
        var good = module(restored);assertEquals("PM3_SOURCE_SNAPSHOT_CAPTURED",good.prepare("analysis",json.createObjectNode()).path("reasonCode").asText());
        restored.withObject("/documents/0/result").put("retrievedEpoch",1);
        assertEquals("PM3_SOURCE_SNAPSHOT_INVALID",module(restored).prepare("analysis",json.createObjectNode()).path("reasonCode").asText());
        assertThrows(IllegalArgumentException.class,()->new RuntimeEvidenceV2Adapter(json).canonicalSha256(result));
    }

    @Test void nonFiniteNumbersRemainRejected() {
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertEquals("PM3_SOURCE_NON_FINITE_NUMBER",assertThrows(IllegalArgumentException.class,
                    ()->Pm3SourceDigest.sha256(json,json.createObjectNode().put("value",value))).getMessage());
    }

    private Pm3SelectedBatch module(ObjectNode snapshot) {
        return new Pm3SelectedBatch(json,new Pm3SelectedBatch.Source(){
            public ObjectNode identity(){return json.createObjectNode().put("testOnly",true);}
            public ObjectNode capture(String a,ObjectNode b){return snapshot.deepCopy();}
        });
    }
}
