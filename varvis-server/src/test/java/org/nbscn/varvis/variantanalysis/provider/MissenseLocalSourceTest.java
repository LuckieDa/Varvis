package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Local source identity is a content pin, never an approval or a downloadable upstream URL. */
class MissenseLocalSourceTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void localIdentityMustExactlyBindItsDigest() {
        ObjectNode record = mapper.createObjectNode().put("sourceId", "LOCAL_REVIEW").put("version", "1")
                .put("sha256", "a".repeat(64)).put("url", "urn:varvis:source:sha256:" + "a".repeat(64));
        assertTrue(MissenseComparisonPolicy.sourcesValid(mapper.createArrayNode().add(record)));
        for (String locator : new String[]{"urn:varvis:source:sha256:" + "b".repeat(64),
                "urn:varvis:source:sha256:" + "a".repeat(64) + "#reviewed", "file:///private/review.json", "D:/private/review.json"}) {
            record.put("url", locator);
            assertFalse(MissenseComparisonPolicy.sourcesValid(mapper.createArrayNode().add(record)), locator);
        }
    }

    @Test void preparationChecksActualLocalBytesAndStillRequiresExternalApproval() throws Exception {
        var f = new MissensePreparationFixture(temp.resolve("fixture"), new Ps1TestCatalog(mapper), false);
        ObjectNode review = mapper.createObjectNode().put("scope", "TEST_ONLY local prior judgment");
        ObjectNode descriptor = f.file("local-review.json", review);
        String digest = descriptor.path("rawSha256").asText();
        ObjectNode record = mapper.createObjectNode().put("sourceId", "LOCAL_REVIEW").put("version", "1")
                .put("sha256", digest).put("url", "urn:varvis:source:sha256:" + digest);
        descriptor.put("format", "JSON").put("sourceDigestKind", "RAW_BYTES").set("sourceRecord", record);
        f.manifest.withArray("sources").add(descriptor);
        f.replace(0, "policy", p -> p.withArray("sources").add(record));
        var pending = f.prepare(temp.resolve("pending"));
        assertEquals("EXTERNAL_APPROVAL_MISSING", pending.report().path("reasonCode").asText());
        assertEquals("PASS", pending.report().path("staticEngineeringValidation").asText());
        assertFalse(Files.exists(temp.resolve("pending/bundle.json")));
        Files.writeString(f.root.resolve("local-review.json"), "{\"scope\":\"changed\"}");
        var changed = f.prepare(temp.resolve("changed"));
        assertEquals("INPUT_RAW_SHA_MISMATCH", changed.report().path("reasonCode").asText());
        assertFalse(Files.exists(temp.resolve("changed/candidate.json")));
    }
}
