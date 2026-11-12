package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PopulationObservationProfileTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadLocalBa1();
    static final String SOURCE = "a".repeat(64);

    ObjectNode archived() throws Exception {
        var fixture = new Bs1LocalExomesTest();
        var context = new PopulationFrequencyRuntimeTest().diseaseContext("HNF1A");
        var shared = fixture.shared(context, fixture.facts.info("0.00004", "10000"), "PASS");
        var old = mapper.createObjectNode().put("executionSourceSha256", SOURCE).put("profileSha256", profile.getProfileSha256());
        old.set("context", context); old.set("evidence", adapter.adapt(context, (ObjectNode) shared.path("evidence")));
        old.set("rawPopulationRecord", shared.path("rawPopulationRecord"));
        return old;
    }

    @Test void explicitExomesRestoreAndVerifyDoNotSelectJointDefaults() throws Exception {
        var old = archived(); var before = old.deepCopy();
        var restored = PopulationObservation.restore(mapper, old, SOURCE, "SYNTHETIC", profile, profile);
        PopulationObservation.verify(mapper, restored, old.path("context"), SOURCE, profile);
        assertEquals(profile.getDocument().path("fieldContract"), restored.path("headerContract"));
        assertEquals(old.path("rawPopulationRecord"), restored.path("rawRecord"));
        assertEquals(before, old);
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.verify(mapper, restored, old.path("context"), SOURCE));
        var readback = mapper.readTree(mapper.writeValueAsBytes(restored));
        PopulationObservation.verify(mapper, readback, old.path("context"), SOURCE, profile);
        assertEquals(restored, readback);
    }

    @Test void resourceScopeRawAndSourceTamperingAreRefused() throws Exception {
        var old = archived();
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.restore(mapper, old, SOURCE, "SYNTHETIC", profile, GnomadPopulationInputProfile.loadExecutableV2()));
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.restore(mapper, old, "b".repeat(64), "SYNTHETIC", profile, profile));
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.restore(mapper, old, SOURCE, "PRIVACY_BOUNDED_RUNTIME", profile, profile));
        var changed = old.deepCopy().put("rawPopulationRecord", old.path("rawPopulationRecord").asText().replace("PASS", "LowQual"));
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.restore(mapper, changed, SOURCE, "SYNTHETIC", profile, profile));
        var restored = PopulationObservation.restore(mapper, old, SOURCE, "SYNTHETIC", profile, profile);
        restored.put("profileSha256", "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.verify(mapper, restored, old.path("context"), SOURCE, profile));
    }

    @Test void sealedLocalFactsAreVerifiedForV3BackgroundsWithoutBorrowingEvaluations() throws Exception {
        var fixture = new Bs1LocalExomesTest(); var context = fixture.context("GN017");
        var shared = fixture.shared(context, fixture.facts.info("0.00004", "10000"), "PASS");
        var observation = Bs1LocalExomes.sharedObservation(mapper, context, shared, SOURCE);
        var restored = PopulationObservation.restoreSealed(mapper, observation, context, SOURCE, profile);
        assertEquals(observation, restored);
        var other = context.deepCopy(); other.withObject("/disease").put("mondoId", "MONDO:0005045");
        other.put("contextId", adapter.canonicalVersionedContextId(other));
        PopulationObservation.verify(mapper, restored, other, SOURCE, profile);
        assertNotEquals(context.path("contextId"), other.path("contextId"));
        assertFalse(restored.has("evaluation"));
        restored.removeAll(); assertFalse(observation.isEmpty());
    }
}
