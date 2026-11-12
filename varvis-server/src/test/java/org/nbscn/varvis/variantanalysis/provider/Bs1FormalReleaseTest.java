package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Bs1FormalReleaseTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private Bs1Runtime runtime(boolean allowed) {
        return new Bs1Runtime(mapper, Bs1Runtime.formalPolicies(mapper), mock(VariantEvidenceProvider.class), null).withFormalActivation(allowed);
    }
    @Test void releaseChangesOnlyActivationVersionAndCorrectHeaderDescription() {
        var old = (ObjectNode) Bs1Runtime.policies(mapper).get(0).deepCopy();
        var released = (ObjectNode) Bs1Runtime.formalPolicies(mapper).get(0).deepCopy();
        assertEquals("d5e48e168e22082ed784a889481b8d9aa794e1880985b3a7d8895f12ab7490e0", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(old));
        assertEquals(1, Bs1Runtime.formalPolicies(mapper).size());
        assertEquals("1.2.0", released.path("version").asText()); assertNull(Bs1Policy.issue(released));
        assertTrue(released.at("/qualityContract/releaseSemantics/sourceLocator").asText().contains("#/headers/12"));
        for (String key : new String[]{"activation", "version"}) released.set(key, old.path(key));
        released.withObject("/qualityContract").set("version", old.at("/qualityContract/version"));
        released.withObject("/qualityContract/releaseSemantics").set("sourceLocator", old.at("/qualityContract/releaseSemantics/sourceLocator"));
        assertEquals(old, released, "No threshold, condition, scope, exception or observation changes");
        assertThrows(IllegalArgumentException.class, () -> new Bs1Runtime(mapper, Bs1Runtime.policies(mapper), mock(VariantEvidenceProvider.class), null).withFormalActivation(true));
    }
    @Test void formalPermissionIsEnforcedAndAllExecutionIdentitiesAreDistinct() {
        var formal = AcmgExecutionProfile.current(mapper, null, runtime(true).identity()).document();
        assertEquals(mapper.valueToTree(AcmgBusinessRuleScope.CURRENT), formal.path("enabledCriteria"));
        assertTrue(formal.path("formalEnabled").asBoolean());
        for (String flag : new String[]{"automaticTrigger", "classificationEnabled", "adoptionSupported"}) assertFalse(formal.path(flag).asBoolean(true));
        var historical = AcmgExecutionProfile.legacyTwoRule(mapper, null).document();
        var acceptance = AcmgExecutionProfile.bs1Acceptance(mapper, null, new Bs1Runtime(mapper, Bs1Runtime.policies(mapper), mock(VariantEvidenceProvider.class), null).identity()).document();
        assertNotEquals(formal.path("manifestSha256"), historical.path("manifestSha256"));
        assertNotEquals(formal.path("manifestSha256"), acceptance.path("manifestSha256"));
        var disabled = runtime(false);
        assertFalse(disabled.identity().path("formalEnabled").asBoolean());
        assertEquals("BS1_BUSINESS_EXECUTION_DISABLED", disabled.replay(mapper.createObjectNode(), mapper.createObjectNode(), "").at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals(0, disabled.counts().path("evaluator").asInt());
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "varvis.bs1.formal.savedRun", matches = ".+")
    void acceptedPublicObservationsReplayWithoutQueriesOrHistoricalMutation() throws Exception {
        Path source = Path.of(System.getProperty("varvis.bs1.formal.savedRun")); byte[] before = Files.readAllBytes(source);
        var saved = mapper.readTree(before); var current = runtime(true); int count = 0;
        for (var row : saved.path("candidates")) for (var attempt : row.path("attempts")) {
            var old = attempt.path("bs1"); if (!old.path("observation").isObject()) continue;
            var observation = old.path("observation");
            var actual = current.replay(old.path("context"), observation, observation.path("sourceSnapshot").asText());
            assertEquals(old.at("/evaluation/evaluationResult/status"), actual.at("/evaluation/evaluationResult/status"));
            assertEquals(old.at("/evaluation/appliedStrength"), actual.at("/evaluation/appliedStrength"));
            assertEquals(old.path("context"), actual.path("context")); assertEquals(observation, actual.path("observation"));
            assertNotEquals(old.path("evaluation"), actual.path("evaluation")); count++;
        }
        assertEquals(2, count); assertEquals(0, current.counts().path("provider").asInt());
        assertEquals(0, current.counts().path("native").asInt()); assertArrayEquals(before, Files.readAllBytes(source));
    }
}
