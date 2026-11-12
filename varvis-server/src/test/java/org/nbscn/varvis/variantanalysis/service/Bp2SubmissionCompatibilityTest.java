package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Bp2SubmissionCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OLD_JSON = "{\"topN\":1,\"declarationVersion\":\"ordinary-ui-v3\",\"retryOf\":null,\"sampleId\":\"TEST_ONLY\",\"dataClass\":\"SYNTHETIC\",\"patient\":null,\"hypotheses\":[]}";
    @Test void preBp2ConstructorsAndExplicitNullKeepFixedLegacyBytesAndIdentity() throws Exception {
        var old = new AcmgSubmission(1,"ordinary-ui-v3",null,"TEST_ONLY","SYNTHETIC",null,List.of());
        var previousCanonical = new AcmgSubmission(1,"ordinary-ui-v3",null,"TEST_ONLY","SYNTHETIC",null,List.of(),null,null,null,null,null,null,null);
        for(var request:List.of(old,previousCanonical,AcmgSubmission.parse(OLD_JSON.getBytes(StandardCharsets.UTF_8),mapper),
                AcmgSubmission.parse((OLD_JSON.substring(0,OLD_JSON.length()-1)+",\"bp2Selection\":null}").getBytes(StandardCharsets.UTF_8),mapper))) {
            assertEquals(OLD_JSON,mapper.writeValueAsString(request));
            // Independently fixed sorted-key JSON SHA; no product-generated expected hash.
            assertEquals("904484e8aeb77d949c8401592e96b62d4e7ed41252452332cafd813040dd9565",new AcmgBusinessArtifacts(mapper).requestHash(mapper.valueToTree(request)));
        }
    }
    @Test void strictRequestBoundaryStillRejectsDuplicateUnknownOversizeAndCoercion() {
        for(String suffix:List.of(",\"topN\":1}",",\"bp2Selection\":{\"bundle\":{}}}",",\"unknown\":true}"))
            assertThrows(IllegalArgumentException.class,()->AcmgSubmission.parse((OLD_JSON.substring(0,OLD_JSON.length()-1)+suffix).getBytes(StandardCharsets.UTF_8),mapper));
        assertThrows(IllegalArgumentException.class,()->AcmgSubmission.parse(OLD_JSON.replace("\"topN\":1","\"topN\":\"1\"").getBytes(StandardCharsets.UTF_8),mapper));
        assertThrows(IllegalArgumentException.class,()->AcmgSubmission.parse(new byte[AcmgSubmission.MAX_BYTES+1],mapper));
    }
}
