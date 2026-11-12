package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual saved normal-Service SYNTHETIC generic RNA exclusion. Read only: no source generator, issuer or scoring run. */
@EnabledIfSystemProperty(named="varvis.test.saved-rna-exclusion-dir", matches=".+")
class T07FullRnaSavedExclusionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode saved, batch, privateData;
    @BeforeEach void readActualSavedProducts() throws Exception {
        var directory = Path.of(System.getProperty("varvis.test.saved-rna-exclusion-dir"));
        saved = read(directory, "expected-controller-full.json", "54c9b345d4a79312b1d662afd74163e7c12492c26027839248cba40791efdaec");
        batch = read(directory, "private-batch.json", "da3845b44e25647f3d85957ab19d2b383726456b1057e4c757ad98e6e6e667bb");
        privateData = read(directory, "expected-private.json", "af95c466782bd541b93dd635e8d8a757c701a2d1a6601a133e2020df851d9b6f");
        assertEquals(privateData.path("localBatch"), batch);
    }
    private ObjectNode read(Path directory, String name, String sha) throws Exception {
        try (var stream = Files.newInputStream(directory.resolve(name))) {
            byte[] bytes = stream.readNBytes(4_000_001); assertTrue(bytes.length <= 4_000_000);
            assertEquals(sha, CodingTranscriptMaterialSupply.sha(bytes)); return (ObjectNode) mapper.readTree(bytes);
        }
    }
    private ObjectNode criterion(int index) { return (ObjectNode) saved.at("/candidates/" + index + "/attempts/0/localBatch/criteria/0"); }
    private ObjectNode actualOriginal(int index) { return (ObjectNode) batch.at("/results/" + index + "/localBatch/criteria/0/details/originalResult"); }
    private boolean completed(int index) {
        var candidate = saved.at("/candidates/" + index); var attempt = candidate.at("/attempts/0");
        return LocalEvidenceBatchRuntime.completedFullRnaExclusion(mapper, batch, candidate, attempt, criterion(index));
    }
    @Test void actualRedactedControllerResultsCompleteBothRowsWithoutChangingAtomicScienceOrAnySavedInput() {
        String publicBefore = saved.toString(), privateBefore = batch.toString(), allPrivateBefore = privateData.toString();
        assertEquals(2, saved.path("candidates").size());
        for (int index = 0; index < 2; index++) {
            var row = criterion(index); assertEquals(Pvs1SelectedBatch.CRITERION, row.path("criterionId").asText());
            assertEquals("NOT_APPLICABLE", row.path("status").asText()); assertEquals("NONE", row.path("appliedStrength").asText());
            assertFalse(row.path("details").has("originalResult")); assertTrue(row.at("/details/originalResultSha256").isTextual());
            var original = actualOriginal(index); assertEquals("NOT_EVALUATED", original.at("/evaluationResult/status").asText());
            assertEquals("NOT_APPLICABLE", original.at("/criterionApplicability/status").asText()); assertEquals("COMPLETED", original.at("/deterministicTrace/executionStatus").asText());
            assertTrue(completed(index));
        }
        assertEquals(publicBefore, saved.toString()); assertEquals(privateBefore, batch.toString()); assertEquals(allPrivateBefore, privateData.toString());
    }
    @Test void existingUnredactedModuleShapeRequiresTheActualObjectAndAnyPresentDigestRemainsMandatory() {
        var details = criterion(0).withObject("details"); details.set("originalResult", actualOriginal(0).deepCopy());
        assertTrue(completed(0)); details.remove("originalResultSha256"); assertTrue(completed(0));
        details.put("originalResultSha256", "b".repeat(64)); assertFalse(completed(0));
        details.putNull("originalResultSha256"); assertFalse(completed(0));
    }
    @ParameterizedTest @ValueSource(strings={"wrongDigest", "nullDigest", "missingBoth", "visibleConflict", "visibleNull", "wrongSource", "reasonOnly", "privateAtomic", "wrongContext"})
    void incorrectDigestOrAnyBrokenSavedSourceProofIsNotACompletedExclusion(String fault) {
        var view = criterion(0); var details = view.withObject("details");
        switch (fault) {
            case "wrongDigest" -> details.put("originalResultSha256", "b".repeat(64));
            case "nullDigest" -> details.putNull("originalResultSha256");
            case "missingBoth" -> details.remove(List.of("originalResult", "originalResultSha256"));
            case "visibleConflict" -> { details.set("originalResult", actualOriginal(0).deepCopy()); details.withObject("originalResult").withObject("criterionApplicability").put("status", "UNKNOWN"); }
            case "visibleNull" -> details.putNull("originalResult");
            case "wrongSource" -> batch.put("sourceSnapshotSha256", "b".repeat(64));
            case "reasonOnly" -> details.remove(List.of("pvs1Facts", "fullRnaUse"));
            case "privateAtomic" -> actualOriginal(0).withObject("criterionApplicability").put("status", "UNKNOWN");
            case "wrongContext" -> ((ObjectNode) saved.at("/candidates/0/attempts/0")).put("hypothesisId", "other-background");
            default -> throw new AssertionError(fault);
        }
        assertFalse(completed(0), fault);
    }
}
