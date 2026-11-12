package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import static org.junit.jupiter.api.Assertions.*;

class Pm6RequestCompatibilityTest {
    @Test void nullablePm6LeavesActualHistoricalPs2RequestProfileAndTokenIdentitiesUnchanged() throws Exception {
        var mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
        var hash = new AcmgBusinessArtifacts(mapper);
        try (var in = getClass().getResourceAsStream("/pm6-denovo-business/ps2-historical-identities.json")) {
            assertNotNull(in);
            for (var old : mapper.readTree(in).path("cases")) {
                var parsed = AcmgSubmission.parse(mapper.writeValueAsBytes(old.path("request")), mapper);
                ObjectNode declaration = mapper.valueToTree(parsed); assertFalse(declaration.has("pm6Selection"));
                var identity = mapper.createObjectNode(); identity.set("declaration", declaration);
                identity.set("executionProfile", old.path("executionProfile")); identity.set("ps2PublishedInputIdentity", old.path("publishedInputIdentity"));
                assertEquals(old.path("expectedRequestSha256").asText(), hash.requestHash(identity));
                assertEquals(old.path("executionProfile"), AcmgExecutionProfile.ps2Development(mapper, old.path("materialPins")).document());
                ObjectNode preparation = ((ObjectNode) old.path("preparation")).deepCopy();
                String token = preparation.remove("preparationToken").asText();
                assertEquals(Ps2DeNovoContextPreparation.TOKEN + hash.requestHash(preparation), token);
                var legacyConstructor = new AcmgSubmission(parsed.topN(), parsed.declarationVersion(), parsed.retryOf(), parsed.sampleId(), parsed.dataClass(), parsed.patient(), parsed.hypotheses(), parsed.recommendations(), parsed.association(), parsed.calibration(), parsed.revelApplication(), parsed.ps2Selection());
                assertEquals(declaration, mapper.valueToTree(legacyConstructor));
            }
        }
    }
}
