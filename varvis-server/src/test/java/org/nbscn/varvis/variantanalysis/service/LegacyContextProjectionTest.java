package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LegacyContextProjectionTest {
    @TempDir Path temporary;

    private SelectedEvidencePreparationTest.Prepared prepare(boolean changed, boolean sourceBlocked) throws Exception {
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = temporary;
        return fixture.prepare(false, sourceBlocked, changed, 1, true);
    }

    @Test void preservesEverySemanticFieldAndBindsDistinctContextsWithoutChangingOriginal() throws Exception {
        var p = prepare(false, false); var id = p.original().getCandidateId();
        var original = p.result().context(id, "context-one");
        var bridge = p.result().legacyProjection(id, "context-one");
        assertSame(bridge, p.result().legacyProjection(id, "context-one"));
        assertNotSame(bridge, p.result().codingSequenceProjection(id, "context-one"));
        assertNotSame(bridge, p.result().legacyProjection(id, "context-two"));
        assertEquals(new LegacyContextProjection(p.mapper(), p.result(), id, "context-one").snapshot(), bridge.snapshot());
        var adapter = new RuntimeEvidenceV2Adapter(p.mapper());
        adapter.validateCompleteContext(bridge.context());
        adapter.validateVersionedContext(original);
        assertNotEquals(original.path("contextId"), bridge.context().path("contextId"));
        var restored = bridge.context();
        restored.set("schemaVersion", original.path("schemaVersion"));
        restored.set("contextId", original.path("contextId"));
        assertEquals(original, restored, "Only schema identity changes, no fields are dropped or invented");
        assertEquals(original, bridge.snapshot().path("originalContext"));
        assertEquals("NOT_GRANTED", bridge.snapshot().path("applicationApproval").asText());
        var forHash = bridge.snapshot(); forHash.remove("bindingSha256");
        assertEquals(adapter.canonicalSha256(forHash), bridge.bindingSha256());
        assertNotEquals(bridge.bindingSha256(), p.result().legacyProjection(id, "context-two").bindingSha256());
        bridge.context().removeAll(); bridge.snapshot().removeAll();
        assertFalse(bridge.context().isEmpty()); assertFalse(bridge.snapshot().isEmpty());
        assertEquals(p.before(), p.mapper().valueToTree(bridge.queryCandidate()));
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }

    @Test void missingTranscriptOrTamperedSourceIdentityCannotBeProjected() throws Exception {
        var p = prepare(false, false); var adapter = new RuntimeEvidenceV2Adapter(p.mapper());
        var context = p.result().context(p.original().getCandidateId(), "context-one");
        context.put("contextId", "context-sha256-" + "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> LegacyContextProjection.projectContext(p.mapper(), context));
        context.remove("transcript"); context.put("contextId", adapter.canonicalVersionedContextId(context));
        adapter.validateVersionedContext(context);
        assertEquals("LEGACY_PROJECTION_TRANSCRIPT_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> LegacyContextProjection.projectContext(p.mapper(), context)).getMessage());
    }

    @Test void changedCoordinatesRequireNewAnnotationBeforeAnyLegacyEvaluation() throws Exception {
        var p = prepare(true, false);
        assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> p.result().legacyProjection(p.original().getCandidateId(), "context-one")).getMessage());
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }

    @Test void priorSourceBlockerCannotBeProjectedAway() throws Exception {
        var p = prepare(false, true);
        assertEquals("SYNTHETIC_SOURCE_CHANGED", assertThrows(IllegalArgumentException.class,
                () -> p.result().legacyProjection(p.original().getCandidateId(), "context-one")).getMessage());
    }

    @Test void sequenceProjectionCarriesOriginalClaimButDoesNotApproveItsNewCoordinates() throws Exception {
        var p = prepare(true, false); var id = p.original().getCandidateId();
        var bridge = p.result().codingSequenceProjection(id, "context-one");
        var view = p.mapper().valueToTree(bridge.queryCandidate());
        assertEquals(1, view.at("/observation/key/position").asInt());
        assertEquals(p.before().path("transcriptAnnotations"), view.path("transcriptAnnotations"));
        assertEquals("PENDING_COMPLETE_CDS_AND_PROTEIN_COMPARISON", bridge.snapshot().path("annotationCorrespondence").asText());
        assertEquals(p.before().at("/observation/key"), bridge.snapshot().path("originalAnnotationAllele"));
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
        assertTrue(view.path("evidence").isEmpty());
        assertTrue(p.mapper().valueToTree(p.result().queryCandidate(id, "context-one", false)).path("transcriptAnnotations").isEmpty());
        assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> p.result().legacyProjection(id, "context-one")).getMessage());
    }

    @Test void sequenceProjectionCannotBypassPublishedSourceBlocker() throws Exception {
        var p = prepare(true, true);
        assertEquals("SYNTHETIC_SOURCE_CHANGED", assertThrows(IllegalArgumentException.class,
                () -> p.result().codingSequenceProjection(p.original().getCandidateId(), "context-one")).getMessage());
    }
}
