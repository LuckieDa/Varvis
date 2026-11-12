package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ClinVarMissenseResourceReadinessTest {
    @Test
    void transportNewlinesRetainBothIdentitiesAndContentTamperingIsRejected() throws Exception {
        var mapper = new ObjectMapper();
        String original = "{\n  \"version\": \"1\"\n}\n";
        var profile = mapper.createObjectNode();
        var lineage = profile.putObject("lineage");
        lineage.put("comparatorManifestRawSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(original.getBytes(StandardCharsets.UTF_8))));
        lineage.put("comparatorManifestCanonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mapper.readTree(original)));
        ClinVarMissenseResourceReadiness.verifyManifestIdentity(mapper, original.getBytes(StandardCharsets.UTF_8), profile);
        ClinVarMissenseResourceReadiness.verifyManifestIdentity(mapper, original.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), profile);
        assertThrows(IllegalArgumentException.class, () -> ClinVarMissenseResourceReadiness.verifyManifestIdentity(mapper,
                original.replace("1", "2").getBytes(StandardCharsets.UTF_8), profile));
        lineage.put("comparatorManifestCanonicalSha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ClinVarMissenseResourceReadiness.verifyManifestIdentity(mapper,
                original.getBytes(StandardCharsets.UTF_8), profile));
    }
}
