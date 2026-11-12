package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic selected-boundary mocks; actual discovery/export/draft/bridge/preparer remain in use. */
class MissenseSelectedReferencePreparationTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private MissenseReferenceSnapshotExportTest.Fixture fixture(String scenario) throws Exception {
        return new MissenseReferenceSnapshotExportTest().new Fixture(temporary.resolve("sources"), 0, scenario, "7/19");
    }
    private SelectedEvidencePreparation selected(MissenseReferenceSnapshotExportTest.Fixture f) {
        var selected = mock(SelectedEvidencePreparation.class);
        var blocked = new Attempt("blocked", "h", null, null, null, null,
                List.of(new Blocker("CONTEXT_NOT_READY", "NORMALIZATION_SOURCE_CHANGED", "source")));
        var rows = List.of(new CandidateAssembly("selected", List.of(
                new Attempt("selected", "one", null, null, null, f.context, List.of()),
                new Attempt("selected", "two", null, null, null, f.context, List.of()))),
                new CandidateAssembly("blocked", List.of(blocked)));
        when(selected.assembly()).thenReturn(new AssemblyResult("SYNTHETIC", 2, 2,
                List.of("selected", "blocked"), rows, List.of(), false));
        when(selected.provenance()).thenReturn(mapper.createObjectNode().put("dataClass", "SYNTHETIC"));
        f.target.applyTranscriptAnnotations(List.of(f.selected));
        for (String id : List.of("one", "two")) {
            var projection = mock(LegacyContextProjection.class);
            var context = f.context.deepCopy();
            if ("two".equals(id)) ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0099999");
            context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(context));
            var snapshot = mapper.createObjectNode().put("testScope", "MOCK_SELECTED_BOUNDARY").put("bindingSha256", id);
            snapshot.set("evaluationContext", context);
            when(projection.context()).thenAnswer(call -> context.deepCopy());
            when(projection.snapshot()).thenAnswer(call -> snapshot.deepCopy());
            when(projection.queryCandidate()).thenReturn(f.target);
            when(selected.legacyProjection("selected", id)).thenReturn(projection);
        }
        return selected;
    }

    @Test void batchPreservesRowsBindsDistinctDraftsAndKeepsMaskedFactsUnapproved() throws Exception {
        var f = fixture("NORMAL"); var selected = selected(f);
        var prep = new MissenseSelectedReferencePreparation(mapper, f.builder, (key, dataClass) -> masked(key));
        var batch = prep.prepare(selected, f.sources, temporary.resolve("batch"));
        assertEquals(2, batch.prepared().size(), batch.report().toPrettyString());
        assertEquals(2, batch.report().path("candidates").size());
        assertEquals("NORMALIZATION_SOURCE_CHANGED", batch.report().at("/candidates/1/attempts/0/reasonCode").asText());
        verify(selected, never()).legacyProjection("blocked", "h");
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
        assertNotEquals(batch.prepared().get(0).report().path("preparationId"), batch.prepared().get(1).report().path("preparationId"));
        assertNotEquals(batch.prepared().get(0).report().at("/draft/draftId"), batch.prepared().get(1).report().at("/draft/draftId"));
        var first = batch.prepared().get(0).report();
        assertTrue(first.at("/search/completeWithinScope").asBoolean());
        assertEquals("UNREVIEWED", first.at("/draft/reviewStatus").asText());
        assertEquals("MASKED", first.at("/sharedSpliceObservations/0/observation/sourceRecord/resource/mode").asText());
        assertEquals("NOT_ESTABLISHED", first.at("/sharedSpliceObservations/0/observation/rnaApproval").asText());
        assertEquals("0.0100", first.at("/sharedSpliceObservations/0/observation/sourceRecord/records/0/DS_AG").asText());
        var saved = first.at("/sharedSpliceObservations/0");
        var restored = ExplicitContextSourceLedger.verifiedBytes(mapper,
                saved.at("/sourceReceipt/rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                saved.at("/sourceReceipt/sha256").asText());
        var reference = mapper.treeToValue(saved.at("/observation/sourceReference"), ExplicitContextSourceLedger.SourceRef.class);
        assertEquals(saved.at("/observation/sourceRecord"), restored.resolve(reference));
        assertEquals(2, first.path("sharedSpliceObservations").size());
        assertFalse(Files.exists(temporary.resolve("batch/attempt-000/bundle.json")));
        batch.report().removeAll(); assertEquals(2, batch.report().path("preparedCount").asInt());
        assertEquals(batch.report(), mapper.readTree(temporary.resolve("batch/preparation-report.json").toFile()));
    }

    @Test void partialAnnotationNeverClaimsCompleteSearchAndSpliceFailureLeavesDraftAvailable() throws Exception {
        var f = fixture("PARTIAL"); var selected = selected(f);
        var prep = new MissenseSelectedReferencePreparation(mapper, f.builder, (key, dataClass) -> { throw new IllegalStateException("failed"); });
        var batch = prep.prepare(selected, f.sources, temporary.resolve("batch"));
        assertEquals(2, batch.prepared().size(), batch.report().toPrettyString());
        assertFalse(batch.prepared().get(0).report().at("/search/completeWithinScope").asBoolean());
        assertEquals(2, batch.prepared().get(0).report().at("/search/candidateCount").asInt());
        assertEquals(1, batch.prepared().get(0).report().at("/search/referenceCount").asInt());
        assertEquals("DRAFT_GENERATED", batch.prepared().get(0).report().path("status").asText());
        assertEquals("NOT_EVALUATED", batch.prepared().get(0).report().at("/sharedSpliceObservations/0/status").asText());
    }

    @Test void missingSelectedAnnotationStopsBeforeDiscoveryAndDoesNotFillAnotherCandidate() throws Exception {
        var f = fixture("NORMAL"); var selected = selected(f);
        when(selected.legacyProjection("selected", "one")).thenThrow(new IllegalArgumentException("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED"));
        when(selected.legacyProjection("selected", "two")).thenThrow(new IllegalArgumentException("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED"));
        var batch = new MissenseSelectedReferencePreparation(mapper, f.builder, null).prepare(selected, f.sources, temporary.resolve("batch"));
        assertEquals(0, batch.prepared().size()); assertEquals(0, f.discoveryCalls); assertEquals(0, f.annotationCalls);
        assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", batch.report().at("/candidates/0/attempts/0/reasonCode").asText());
    }

    @Test void candidateWithoutAttemptsRetainsExplicitMissingContextWithoutInventingHypothesis() throws Exception {
        var f = fixture("NORMAL"); var selected = mock(SelectedEvidencePreparation.class);
        when(selected.assembly()).thenReturn(new AssemblyResult("SYNTHETIC", 1, 1,
                List.of("selected"), List.of(new CandidateAssembly("selected", List.of())), List.of(), false));
        var provenance = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("source", "SYNTHETIC_ORIGINAL");
        when(selected.provenance()).thenReturn(provenance);
        var batch = new MissenseSelectedReferencePreparation(mapper, f.builder, null)
                .prepare(selected, f.sources, temporary.resolve("batch"));
        var row = batch.report().at("/candidates/0");
        assertEquals("selected", row.path("candidateId").asText());
        assertEquals("BLOCKED", row.path("status").asText());
        assertEquals("SELECTED_CONTEXT_UNAVAILABLE", row.path("reasonCode").asText());
        assertTrue(row.path("attempts").isEmpty()); assertFalse(row.has("hypothesisId"));
        assertEquals(provenance, batch.report().path("selectedPreparationProvenance"));
        assertEquals(f.sources, batch.report().path("sourceSnapshot"));
        assertEquals(0, batch.prepared().size()); assertEquals(0, f.discoveryCalls); assertEquals(0, f.annotationCalls);
        verify(selected, never()).legacyProjection(anyString(), anyString());
    }

    @Test void externalFirstReviewStillStopsAtSeparateFinalApproval() throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temporary.resolve("reviewed-source"), 0);
        var prep = new MissenseSelectedReferencePreparation(mapper, f.upstream.builder, null);
        var report = prep.continueReviewed(f.manifestPath, f.pin(), temporary.resolve("continuation"));
        assertEquals("BRIDGED", report.at("/reviewBridge/bridgeStatus").asText(), report.toPrettyString());
        assertEquals("EXTERNAL_APPROVAL_MISSING", report.at("/materialPreparation/reasonCode").asText());
        assertFalse(Files.exists(temporary.resolve("continuation/material/bundle.json")));
        assertEquals("2026-02-03", f.selectedAssertion.at("/sourceAssertion/dateLastEvaluated").asText());
        assertEquals("UNREVIEWED", f.referenceDraft.path("reviewStatus").asText());
    }

    @Test void privacyRuntimeIsNotRelabeledAsPublicOrSyntheticAndStopsBeforeProviders() throws Exception {
        var f = fixture("NORMAL"); f.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        var selected = selected(f); f.sources.put("dataClass", "PUBLIC_REFERENCE").put("purpose", "PUBLIC_REFERENCE_REVIEW");
        var batch = new MissenseSelectedReferencePreparation(mapper, f.builder, null).prepare(selected, f.sources, temporary.resolve("batch"));
        assertEquals(0, batch.prepared().size()); assertEquals(0, f.discoveryCalls); assertEquals(0, f.annotationCalls);
        assertEquals("REFERENCE_DRAFT_DATA_CLASS_UNSUPPORTED", batch.report().at("/candidates/0/attempts/0/reasonCode").asText());
        assertEquals("PRIVACY_BOUNDED_RUNTIME", f.context.path("dataClass").asText());
    }

    private MissenseSelectedReferencePreparation.SpliceFact masked(NormalizedVariantKey key) {
        try {
            var allele = mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                    .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
            var raw = mapper.createObjectNode().put("schemaVersion", SharedSpliceObservation.SOURCE_SCHEMA);
            raw.set("queryAllele", allele); raw.putObject("resource").put("mode", "MASKED").put("annotation", "UNKNOWN");
            raw.putObject("resourceExecution").put("status", "SUCCEEDED"); raw.putObject("resourceAvailability").put("status", "AVAILABLE");
            raw.putObject("resourceApplicability").put("status", "APPLICABLE"); raw.putObject("queryOutcome").put("status", "HIT");
            var row = raw.putArray("records").addObject().put("allele", key.getAlternate());
            for (String event : List.of("AG", "AL", "DG", "DL")) row.put("DS_" + event, "0.0100").put("DP_" + event, -2);
            var envelope = mapper.createObjectNode().put("source", "SYNTHETIC_SPLICE").put("version", "1").put("dataClass", "SYNTHETIC");
            envelope.putObject("records").set("observation", raw);
            byte[] bytes = mapper.writeValueAsBytes(envelope);
            String sha = Pm4MaterialPreparation.sha(bytes);
            var ledger = ExplicitContextSourceLedger.verifiedBytes(mapper, bytes, sha);
            var observation = SharedSpliceObservation.capture(mapper, allele, new ExplicitContextSourceLedger.SourceRef("SYNTHETIC_SPLICE", "1", "observation", sha), List.of(ledger));
            return new MissenseSelectedReferencePreparation.SpliceFact(observation, ledger);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
