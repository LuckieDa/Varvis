package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MissenseReferenceSessionTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private MissenseReferenceSnapshotExportTest.Fixture fixture(String scenario) throws Exception {
        var owner = new MissenseReferenceSnapshotExportTest();
        return owner.new Fixture(temporary.resolve("sources"), 0, scenario, "7/19");
    }

    @Test void reusesFactsAcrossBackgroundsButRebuildsTheirContextBindingsAndDrafts() throws Exception {
        var f = fixture("NORMAL"); var session = f.builder.openSession(f.sources);
        var first = session.buildWithSnapshots(f.context, f.target, f.selected, f.sources);
        var other = f.context.deepCopy(); ((ObjectNode) other.path("disease")).put("mondoId", "MONDO:0099999");
        other.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(other));
        var second = session.buildWithSnapshots(other, f.target, f.selected, f.sources);
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
        assertEquals(1, session.counts().path("sidecarCalls").asInt());
        assertNotEquals(first.getResult().path("contextId"), second.getResult().path("contextId"));
        assertEquals(other.path("contextId"), second.getResult().at("/comparators/0/targetContextId"));
        assertEquals(first.getResult().at("/comparators/0/submissions"), second.getResult().at("/comparators/0/submissions"));
        ((ObjectNode) first.getReferenceSnapshots().get(0).getAnnotation()).put("exon", "CORRUPTED_COPY");
        assertEquals("7/19", second.getReferenceSnapshots().get(0).getAnnotation().path("exon").asText());
        var exported = new MissenseReferenceSnapshotExport().write(new MissenseReferenceSnapshotExport().capture(second, f.sources), temporary.resolve("export"));
        assertTrue(exported.exported(), exported.report().toPrettyString());
        var draft = new MissenseReferenceDraftPreparation().prepare(exported.manifestPath(), exported.manifestSha256(), temporary.resolve("draft"));
        assertTrue(draft.generated()); assertEquals("UNREVIEWED", draft.report().path("reviewStatus").asText());
        assertEquals(second.getResult(), draft.report().path("sourceSearch"));
    }

    @Test void resourceChangeRejectsBeforeAnyReuse() throws Exception {
        var f = fixture("NORMAL"); var session = f.builder.openSession(f.sources);
        session.buildWithSnapshots(f.context, f.target, f.selected, f.sources);
        var changed = f.sources.deepCopy(); ((ObjectNode) changed.path("VEP")).put("identityFingerprintSha256", "f".repeat(64));
        assertEquals("REFERENCE_SESSION_RESOURCE_CHANGED", assertThrows(IllegalArgumentException.class,
                () -> session.buildWithSnapshots(f.context, f.target, f.selected, changed)).getMessage());
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
    }

    @Test void queryFailureIsDeduplicatedAsFailureAndAFreshRequestCanRetry() throws Exception {
        var f = fixture("DISCOVERY_FAILED"); var session = f.builder.openSession(f.sources);
        for (int i = 0; i < 2; i++) {
            var result = session.buildWithSnapshots(f.context, f.target, f.selected, f.sources).getResult();
            assertEquals("NOT_EVALUATED", result.path("status").asText());
            assertEquals("CLINVAR_BOUNDED_QUERY_FAILED", result.path("reasonCode").asText());
        }
        assertEquals(1, f.discoveryCalls); assertEquals(0, f.annotationCalls);
        f.builder.openSession(f.sources).buildWithSnapshots(f.context, f.target, f.selected, f.sources);
        assertEquals(2, f.discoveryCalls);
    }

    @Test void missingAnnotationsRemainIncompleteWithoutRepeatedWorkAcrossBackgrounds() throws Exception {
        var f = fixture("ANNOTATION_OMITTED"); var session = f.builder.openSession(f.sources);
        for (int i = 0; i < 2; i++) {
            var result = session.buildWithSnapshots(f.context, f.target, f.selected, f.sources).getResult();
            assertEquals("NOT_EVALUATED", result.path("status").asText());
            assertEquals("SELECTED_COMPARATOR_TRANSCRIPT_NOT_FOUND", result.at("/candidateDecisions/0/reasonCode").asText());
        }
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
    }
}
