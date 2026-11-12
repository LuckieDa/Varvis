package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Full synthetic source -> two independent test approvals -> original bundle/runtime, without rediscovery. */
class MissenseCapturedRuntimeTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void originalBundleConsumesSameCaptureAfterBothExternalTestApprovals(boolean pm5) throws Exception {
        var f = new GeneratedMissenseReferenceFixture(temporary.resolve("complete-chain"), mapper, pm5).build();
        for (int i = 0; i < 2; i++) {
            var bundle = f.loadBundleWithCapturedInput(i);
            var result = bundle.evaluate(f.criterion(), f.context(i), f.target(i));
            assertEquals("MET", result.at("/criterionEvaluations/0/evaluationResult/status").asText(), result.toPrettyString());
            assertEquals("SAME_INVOCATION_CAPTURE", result.path("referenceFactReuse").asText());
            assertEquals(0, result.path("providerCalls").asInt());
            assertEquals(f.selectedAssertion(i).path("sourceAssertion"), result.at("/references/0/sourceAssertion"));
            assertEquals(f.selectedAssertion(i).at("/sourceAssertion/dateLastEvaluated"), result.at("/references/0/evaluationDate"));
        }
        assertEquals(2, f.counts().path("captureDiscovery").asInt());
        assertEquals(2, f.counts().path("captureAnnotation").asInt());
        assertEquals(0, f.counts().path("runtimeDiscovery").asInt());
        assertEquals(0, f.counts().path("runtimeAnnotation").asInt());
        assertEquals(2, f.counts().path("splice").asInt());
    }

    @Test void capturedContextCannotBeBorrowedByAnotherBackground() throws Exception {
        var f = new GeneratedMissenseReferenceFixture(temporary.resolve("complete-chain"), mapper, false).build();
        var result = f.loadBundleWithCapturedInput(0).evaluate(f.criterion(), f.context(1), f.target(1));
        assertEquals("NOT_EVALUATED", result.at("/criterionEvaluations/0/evaluationResult/status").asText());
        assertEquals("MISSENSE_CAPTURE_CONTEXT_MISMATCH", result.at("/criterionEvaluations/0/evaluationResult/reasonCode").asText());
        assertEquals(0, f.counts().path("runtimeDiscovery").asInt());
        assertEquals(0, f.counts().path("splice").asInt());
    }

    @Test void capturedIncompleteSearchStillFailsTheOriginalPm5Gate() throws Exception {
        var f = new GeneratedMissenseReferenceFixture(temporary.resolve("incomplete"), mapper, true,
                GeneratedMissenseReferenceFixture.Scenario.PM5_INCOMPLETE).build();
        var result = f.loadBundleWithCapturedInput(0).evaluate(f.criterion(), f.context(0), f.target(0));
        assertEquals("NOT_EVALUATED", result.at("/criterionEvaluations/0/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(0, f.counts().path("runtimeDiscovery").asInt());
    }
}
