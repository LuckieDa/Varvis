package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;
import static org.nbscn.varvis.variantanalysis.service.ExplicitCaseInputCoordinator.*;

/** Synthetic declarations test identity isolation, never scientific scoring. */
class AcmgMultiBackgroundTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ObjectNode binding = json.createObjectNode();
    final AcmgRecommendations policy;
    AcmgMultiBackgroundTest() {
        binding.putObject("pp1"); binding.putObject("deNovo");
        policy = new AcmgRecommendations(json, c -> { throw new AssertionError("no provider"); }, Clock.systemUTC()).withLocalBatch(binding);
    }
    ObjectNode current() {
        var c = json.createObjectNode().put("candidateId", "v1").put("recommendationId", "rec")
                .put("policyHash", "hash").put("hypothesisId", "legacy");
        c.putArray("annotations"); var r = c.putArray("relationships");
        for (int i = 1; i <= 2; i++) r.addObject().put("stableRelationshipKey", "r" + i).put("selectable", true)
                .put("hgncId", "HGNC:1").put("mondoId", "MONDO:000000" + i).put("moi", "AD");
        return c;
    }
    HypothesisInput hypothesis(int i) {
        var h = new PatientDiseaseHypothesis("v1", "unused", "HGNC:1", "MONDO:000000" + i, "AD",
                new HypothesisProvenance("EXPLICIT_USER_DECLARATION", "1", "test"));
        return new HypothesisInput(new PatientDiseaseHypothesis(h.candidateId(), AcmgRecommendations.backgroundId(h),
                h.hgncId(), h.mondoId(), h.moi(), h.provenance()), i == 1 ? "AFFECTED" : "UNKNOWN", null);
    }
    AcmgRecommendations.BackgroundSelection choice(int i) {
        return new AcmgRecommendations.BackgroundSelection(hypothesis(i).hypothesis().hypothesisId(), "r" + i, null);
    }
    AcmgRecommendations.Selection selection(List<AcmgRecommendations.BackgroundSelection> choices) {
        return new AcmgRecommendations.Selection("v1", "rec", "1.1.0-local-batch1", "hash", "time", null, null, choices);
    }
    @Test void bothBackgroundsValidateIndependentlyAndRoundTrip() throws Exception {
        var request = new AcmgSubmission(1, "test", null, "sample", "SYNTHETIC", null,
                List.of(hypothesis(1), hypothesis(2)), List.of(selection(List.of(choice(1), choice(2)))));
        var restored = AcmgSubmission.parse(json.writeValueAsBytes(request), json);
        policy.verify(restored.recommendations().get(0), current(), restored.hypotheses());
        assertEquals("AFFECTED", restored.hypotheses().get(0).affectedStatus());
        assertEquals("UNKNOWN", restored.hypotheses().get(1).affectedStatus());
        assertEquals(json.valueToTree(request), json.valueToTree(restored));
    }
    @Test void swappedRelationshipCannotBeBorrowedAcrossBackgrounds() {
        var wrong = new AcmgRecommendations.BackgroundSelection(choice(1).hypothesisId(), "r2", null);
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(wrong, choice(2))), current(), List.of(hypothesis(1), hypothesis(2))));
    }
    @Test void duplicateMissingAndUnboundContextsAreRejected() {
        var h = List.of(hypothesis(1), hypothesis(2));
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(choice(1), choice(1))), current(), h));
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(choice(1))), current(), h));
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(choice(1), choice(2))), current(), List.of(hypothesis(1))));
    }
    @Test void changedSourceAndUnrelatedAnnotationAreRejected() {
        var changed = current().put("recommendationId", "changed");
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(choice(1))), changed, List.of(hypothesis(1))));
        var wrong = new AcmgRecommendations.BackgroundSelection(choice(1).hypothesisId(), "r1", "another-transcript");
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(wrong)), current(), List.of(hypothesis(1))));
    }
    @Test void malformedIdentityFailsAsValidationNotNullPointer() {
        var h = hypothesis(1).hypothesis();
        var broken = new HypothesisInput(new PatientDiseaseHypothesis("v1", h.hypothesisId(), null, h.mondoId(), h.moi(), h.provenance()), "UNKNOWN", null);
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(choice(1))), current(), List.of(broken)));
    }
    @Test void oldSevenFieldRequestAndUnknownCandidateRemainSupported() {
        var old = new AcmgRecommendations.Selection("v1", "rec", "1.1.0-local-batch1", "hash", "time", null, null);
        assertFalse(json.valueToTree(old).has("backgrounds"));
        policy.verify(old, current(), List.of());
        policy.verify(selection(List.of()), current(), List.of());
    }
    @Test void backgroundIdentityIsCandidateScopedAndIndependentOfPatientState() {
        var h = hypothesis(1).hypothesis();
        var other = new PatientDiseaseHypothesis("v2", h.hypothesisId(), h.hgncId(), h.mondoId(), h.moi(), h.provenance());
        assertNotEquals(AcmgRecommendations.backgroundId(h), AcmgRecommendations.backgroundId(other));
    }
    @Test void literatureHypothesisReusesExactTranscriptWithoutBorrowingDiseaseApproval() {
        var c = current();
        c.withArray("annotations").addObject().put("annotationId", "a1").put("contextSelectable", true)
                .put("identityCompatible", true).put("versionedTranscriptId", "ENST000001.2")
                .put("annotationSource", "VEP").put("annotationSourceVersion", "116.0");
        var h = hypothesis(1);
        var tx = new TranscriptSelection("ENST000001", 2, "ORDINARY_CATALOG_AND_AM_IDENTITY@1.1.0-local-batch1",
                "VEP", "116.0", new HypothesisProvenance("EXPLICIT_USER_DECLARATION", "1", "recommendations/rec/selection"), "Existing identity");
        var choice = new AcmgRecommendations.BackgroundSelection(h.hypothesis().hypothesisId(), null, "a1");
        policy.verify(selection(List.of(choice)), c, List.of(new HypothesisInput(h.hypothesis(), "UNKNOWN", tx)));
        var wrong = new TranscriptSelection(tx.accession(), 3, tx.selectionPolicy(), tx.annotationSource(), tx.annotationSourceVersion(), tx.source(), tx.selectionReason());
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(choice)), c,
                List.of(new HypothesisInput(h.hypothesis(), "UNKNOWN", wrong))));
        assertThrows(IllegalArgumentException.class, () -> policy.verify(selection(List.of(choice)), c, List.of(h)));
    }
    @Test void savedReadOnlySnapshotValidatesEveryFrontendContextWhenProvided() throws Exception {
        var dir = System.getProperty("multi.background.fixture");
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null, "Optional local saved snapshot replay");
        var snapshot = json.readTree(Files.readAllBytes(Path.of(dir, "snapshot.json")));
        var request = AcmgSubmission.parse(Files.readAllBytes(Path.of(dir, "declaration.json")), json);
        var first = snapshot.at("/options/candidates/0/recommendation");
        var runtime = new AcmgRecommendations(json, c -> { throw new AssertionError("no provider"); }, Clock.systemUTC())
                .withUnifiedLocal(first.path("localBatchBinding"));
        for (var supplied : request.recommendations()) {
            ObjectNode recommendation = null;
            for (var c : snapshot.at("/options/candidates")) if (c.path("candidateId").asText().equals(supplied.candidateId()))
                recommendation = (ObjectNode)c.path("recommendation");
            assertNotNull(recommendation);
            runtime.verify(supplied, recommendation, request.hypotheses());
        }
        assertEquals(19, request.recommendations().size()); assertEquals(27, request.hypotheses().size());
    }
}
