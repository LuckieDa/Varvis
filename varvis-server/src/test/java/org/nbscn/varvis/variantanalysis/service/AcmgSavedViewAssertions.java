package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import static org.junit.jupiter.api.Assertions.*;

/** Exact saved science, allowing only the explicitly added read-only D18 view fields. */
final class AcmgSavedViewAssertions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AcmgSavedViewAssertions() { }

    static void attempt(JsonNode expected, JsonNode actual, JsonNode full) {
        JsonNode normalized = actual.deepCopy();
        stripClinical(expected, normalized, full);
        assertTrue(expected.equals(normalized), () -> "Saved attempt differs outside its read-only clinical extension: " + difference(expected, normalized, ""));
    }

    static void view(byte[] expectedBytes, byte[] actualBytes, JsonNode full) throws Exception {
        if (Arrays.equals(expectedBytes, actualBytes)) return;
        JsonNode expected = Objects.requireNonNull(JSON.readTree(expectedBytes));
        var actual = (ObjectNode) Objects.requireNonNull(JSON.readTree(actualBytes));
        assertEquals(expected.path("viewKind"), actual.path("viewKind"));
        assertEquals(expected.path("sourceViewSha256"), actual.path("sourceViewSha256"));
        if (!expected.has("executionSourceSha256") && actual.has("executionSourceSha256")) {
            assertEquals(full.path("executionSourceSha256"), actual.path("executionSourceSha256"));
            actual.remove("executionSourceSha256");
        }
        if (!expected.has("localBatch") && actual.has("localBatch")) {
            assertTrue(actual.path("localBatch").isObject(), "Result-view source header must be an object");
            Set<String> allowed = Set.of("schemaVersion", "sourceSnapshotSha256", "identity",
                    "fullRnaObservedSourceIdentity", "fullRnaArchiveSha256");
            var fields = actual.path("localBatch").fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                assertTrue(allowed.contains(field), "Unexpected result-view root payload: " + field);
                assertEquals(full.path("localBatch").path(field), actual.path("localBatch").path(field));
            }
            actual.remove("localBatch");
        }
        assertEquals(expected.path("candidates").size(), actual.path("candidates").size());
        for (int c = 0; c < expected.path("candidates").size(); c++) {
            var oldCandidate = expected.path("candidates").path(c); var newCandidate = actual.path("candidates").path(c);
            assertEquals(oldCandidate.path("candidateId"), newCandidate.path("candidateId"));
            assertEquals(oldCandidate.path("attempts").size(), newCandidate.path("attempts").size());
            for (int a = 0; a < oldCandidate.path("attempts").size(); a++)
                stripClinical(oldCandidate.path("attempts").path(a), newCandidate.path("attempts").path(a), full);
        }
        assertTrue(expected.equals(actual), () -> "Saved view differs outside the explicit D18 display fields: " + difference(expected, actual, ""));
    }

    private static void stripClinical(JsonNode expected, JsonNode actual, JsonNode full) {
        var oldCriteria = expected.at("/localBatch/criteria"); var newCriteria = actual.at("/localBatch/criteria");
        assertEquals(oldCriteria.size(), newCriteria.size());
        for (int i = 0; i < newCriteria.size(); i++) {
            var old = oldCriteria.path(i); var current = newCriteria.path(i);
            assertEquals(old.path("criterionId"), current.path("criterionId"));
            if (old.at("/details/clinicalContext").isMissingNode() && current.at("/details/clinicalContext").isObject()) {
                assertEquals("ACMG_AMP_2015:PP1", current.path("criterionId").asText());
                var projection = current.at("/details/clinicalContext");
                assertEquals("saved-clinical-detail-v1", projection.path("schemaVersion").asText());
                assertEquals("DISPLAY_ONLY", projection.path("projectionRole").asText());
                assertTrue(projection.path("historyOnly").asBoolean());
                assertFalse(projection.path("authorityRestorationSupported").asBoolean(true));
                var hash = new RuntimeEvidenceV2Adapter(JSON);
                var body = ((ObjectNode) projection).deepCopy(); body.remove("projectionSha256");
                assertEquals(hash.canonicalSha256(body), projection.path("projectionSha256").asText());
                if ("CURRENT_SAMPLE_LINKED".equals(projection.path("status").asText())) {
                    assertEquals(full.path("analysisId"), projection.at("/binding/analysisId"));
                    assertEquals(full.path("runId"), projection.at("/binding/runId"));
                    assertEquals(expected.path("candidateId"), projection.at("/binding/candidateId"));
                    assertEquals(expected.path("hypothesisId"), projection.at("/binding/hypothesisId"));
                    assertEquals(hash.canonicalSha256(expected.path("context")), projection.at("/binding/preparedContextSha256").asText());
                } else assertFalse(projection.has("member"), "Unbound source must not display a current patient");
                ((ObjectNode) current.path("details")).remove("clinicalContext");
            }
        }
    }

    private static String difference(JsonNode a, JsonNode b, String pointer) {
        if (a.equals(b)) return "";
        if (a.isObject() && b.isObject()) {
            var keys = new java.util.TreeSet<String>();
            a.fieldNames().forEachRemaining(keys::add); b.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) if (!a.path(key).equals(b.path(key)))
                return difference(a.path(key), b.path(key), pointer + "/" + key);
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return pointer + "/length";
            for (int i = 0; i < a.size(); i++) if (!a.path(i).equals(b.path(i)))
                return difference(a.path(i), b.path(i), pointer + "/" + i);
        }
        return pointer;
    }
}
