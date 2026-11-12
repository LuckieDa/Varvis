package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit synthetic review-request events only; no reviewer action is performed by these tests. */
class CSpecPreflightReferencesTest {
    private static final String CONTEXT = "context-sha256-" + "a".repeat(64);
    private static final String SELECTION = "cspec-selection-sha256-" + "b".repeat(64);
    private static final String FIRST = "cspec-overlay-synthetic-a-1.0.0";
    private static final String SECOND = "cspec-overlay-synthetic-b-1.0.0";
    private static final String METADATA_REASON = "CSPEC_MATCH_NOT_EXECUTABLE";
    private static final String MULTIPLE_REASON = "CSPEC_MULTIPLE_EXACT_MATCHES";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    void acceptsCompleteExplicitMetadataReviewAndPreservesCallerDataDefensively() {
        ObjectNode review = review(false);
        JsonNode expected = review.deepCopy();
        CSpecPreflightReferences.Validation result = validate(false, List.of(review), List.of());
        assertTrue(result.valid());
        assertNull(result.reasonCode());
        assertNull(result.field());
        assertEquals(List.of(expected), result.reviews());
        assertTrue(result.conflicts().isEmpty());
        review.put("reasonCode", "CHANGED_AFTER_VALIDATION");
        ((ObjectNode) result.reviews().get(0)).put("reasonCode", "CHANGED_ACCESSOR_COPY");
        assertEquals(List.of(expected), result.reviews());
        assertThrows(UnsupportedOperationException.class, () -> result.reviews().add(mapper.createObjectNode()));
    }

    @Test
    void multipleMatchRequiresCompleteReviewAndExactConflictParticipantsWithCanonicalOrder() {
        ObjectNode review = review(true);
        ObjectNode conflict = conflict();
        JsonNode original = conflict.deepCopy();
        CSpecPreflightReferences.Validation result = validate(true, List.of(review), List.of(conflict));
        assertTrue(result.valid());
        assertEquals(List.of(original), result.conflicts());
        ObjectNode reordered = conflict.deepCopy();
        reordered.putArray("participantIds").add(FIRST).add(SECOND).add(SELECTION);
        assertEquals(conflict.path("conflictId"), stampConflict(reordered).path("conflictId"));
        assertTrue(validate(true, List.of(review), List.of(reordered)).valid());
        ObjectNode withoutOptionalReason = conflict.deepCopy();
        withoutOptionalReason.remove("reasonCode");
        assertTrue(validate(true, List.of(review), List.of(stampConflict(withoutOptionalReason))).valid());
        conflict.put("resolutionStatus", "RESOLVED");
        ((ObjectNode) result.conflicts().get(0)).put("resolutionStatus", "RESOLVED");
        assertEquals(List.of(original), result.conflicts());
    }

    @Test
    void missingEventsAndUnexpectedConflictsAreStructured() {
        reason("CSPEC_REVIEW_RECORDS_REQUIRED", validate(false, null, List.of()));
        reason("CSPEC_CONFLICT_RECORDS_REQUIRED", validate(true, List.of(review(true)), null));
        reason("CSPEC_CONFLICT_UNEXPECTED", validate(false, List.of(review(false)), List.of(conflict())));
        reason("CSPEC_REVIEW_RECORD_INVALID", validate(false, Arrays.asList((JsonNode) null), List.of()));
        reason("CSPEC_CONFLICT_RECORD_INVALID", validate(true, List.of(review(true)), Arrays.asList((JsonNode) null)));
    }

    @Test
    void selectionContextAndCandidateReferencesMustBeWellFormedUniqueAndBranchAppropriate() {
        reason("CSPEC_REFERENCE_CONTEXT_INVALID", CSpecPreflightReferences.validate(mapper, adapter, "wrong", SELECTION,
                List.of(FIRST), false, List.of(review(false)), List.of()));
        reason("CSPEC_REFERENCE_SELECTION_INVALID", CSpecPreflightReferences.validate(mapper, adapter, CONTEXT, "wrong",
                List.of(FIRST), false, List.of(review(false)), List.of()));
        for (List<String> candidates : Arrays.asList(null, List.<String>of(), List.of(FIRST, FIRST),
                Arrays.asList(FIRST, null), List.of(FIRST, "not-overlay"))) {
            reason("CSPEC_REFERENCE_CANDIDATES_INVALID", CSpecPreflightReferences.validate(mapper, adapter, CONTEXT, SELECTION,
                    candidates, true, List.of(review(true)), List.of(conflict())));
        }
        reason("CSPEC_REFERENCE_CANDIDATES_INVALID", CSpecPreflightReferences.validate(mapper, adapter, CONTEXT, SELECTION,
                List.of(FIRST, SECOND), false, List.of(review(false)), List.of()));
    }

    @Test
    void reviewMustBindThisContextSelectionAndBranchReason() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("contextId", "context-sha256-" + "c".repeat(64)),
                node -> node.put("subjectType", "CRITERION_EVALUATION"),
                node -> node.put("subjectId", "cspec-selection-sha256-" + "c".repeat(64)),
                node -> node.put("reasonCode", MULTIPLE_REASON))) {
            ObjectNode invalid = review(false);
            mutation.accept(invalid);
            assertFalse(validate(false, List.of(stampReview(invalid)), List.of()).valid());
        }
        ObjectNode wrongSubject = review(false);
        wrongSubject.put("subjectId", "cspec-selection-sha256-" + "c".repeat(64));
        reason("CSPEC_REVIEW_SUBJECT_CONFLICT", validate(false, List.of(stampReview(wrongSubject)), List.of()));
        reason("CSPEC_REVIEW_REASON_CONFLICT", validate(true, List.of(review(false)), List.of(conflict())));
    }

    @Test
    void rejectsUnsupportedReviewLifecycleAndOptionalExtensionsWithoutInventingEvents() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("eventType", "STARTED"), node -> node.put("eventType", "DECIDED"),
                node -> node.put("status", "IN_PROGRESS"), node -> node.put("status", "COMPLETED"),
                node -> node.put("decision", "ACCEPTED"), node -> node.putArray("sourceLocators"),
                node -> node.put("previousReviewRecordId", "review-sha256-" + "c".repeat(64)),
                node -> node.putObject("override"))) {
            ObjectNode invalid = review(false);
            mutation.accept(invalid);
            reason("CSPEC_REVIEW_EVENT_UNSUPPORTED", validate(false, List.of(invalid), List.of()));
        }
    }

    @Test
    void everyRequiredReviewFieldAndNestedActorFieldIsChecked() {
        for (String field : List.of("schemaVersion", "reviewRecordId", "contextId", "subjectType", "subjectId", "eventType",
                "status", "decision", "actor", "reasonCode", "createdAt")) {
            ObjectNode invalid = review(false);
            invalid.remove(field);
            assertFalse(validate(false, List.of(invalid), List.of()).valid(), field);
        }
        for (String field : List.of("actorId", "role")) {
            ObjectNode invalid = review(false);
            ((ObjectNode) invalid.path("actor")).remove(field);
            reason("CSPEC_REVIEW_ACTOR_INVALID", validate(false, List.of(invalid), List.of()));
        }
        ObjectNode actor = review(false);
        ((ObjectNode) actor.path("actor")).put("actorId", "reviewer-short");
        reason("CSPEC_REVIEW_ACTOR_INVALID", validate(false, List.of(actor), List.of()));
        ObjectNode extra = review(false);
        extra.put("unexpected", true);
        reason("CSPEC_REVIEW_FIELD_UNKNOWN", validate(false, List.of(extra), List.of()));
        ObjectNode actorExtra = review(false);
        ((ObjectNode) actorExtra.path("actor")).put("displayName", "Synthetic test");
        reason("CSPEC_REVIEW_FIELD_UNKNOWN", validate(false, List.of(actorExtra), List.of()));
    }

    @Test
    void optionalRationaleAndExplicitEventTimeArePartOfReviewIdentity() {
        ObjectNode review = review(false);
        String oldId = review.path("reviewRecordId").asText();
        review.put("rationaleSummary", "Synthetic review request");
        reason("CSPEC_REVIEW_IDENTITY_MISMATCH", validate(false, List.of(review), List.of()));
        stampReview(review);
        assertNotEquals(oldId, review.path("reviewRecordId").asText());
        assertTrue(validate(false, List.of(review), List.of()).valid());
        for (JsonNode invalidValue : List.of(mapper.getNodeFactory().textNode(""),
                mapper.getNodeFactory().textNode("a".repeat(1001)), mapper.getNodeFactory().numberNode(1))) {
            ObjectNode invalid = review(false);
            invalid.set("rationaleSummary", invalidValue);
            reason("CSPEC_REVIEW_RATIONALE_INVALID", validate(false, List.of(invalid), List.of()));
        }
        review.put("createdAt", "2026-09-05T01:00:00Z");
        reason("CSPEC_REVIEW_IDENTITY_MISMATCH", validate(false, List.of(review), List.of()));
        assertTrue(validate(false, List.of(stampReview(review)), List.of()).valid());
    }

    @Test
    void eventDatesMustBeRealUtcDatesAndRemainCallerSupplied() {
        for (String timestamp : List.of("2026-09-05T00:00:00.123Z", "2026-09-05T00:00:00+00:00")) {
            ObjectNode review = review(true).put("createdAt", timestamp);
            ObjectNode conflict = conflict().put("detectedAt", timestamp);
            assertTrue(validate(true, List.of(stampReview(review)), List.of(stampConflict(conflict))).valid());
        }
        for (String timestamp : List.of("2026-02-30T00:00:00Z", "0000-09-05T00:00:00Z", "2026-09-05",
                "2026-09-05T00:00:00", "2026-09-05T08:00:00+08:00", "2026-09-05T00:00:00-00:00")) {
            reason("CSPEC_REVIEW_TIME_INVALID", validate(false, List.of(review(false).put("createdAt", timestamp)), List.of()));
            reason("CSPEC_CONFLICT_TIME_INVALID", validate(true, List.of(review(true)), List.of(conflict().put("detectedAt", timestamp))));
        }
    }

    @Test
    void conflictParticipantsCannotOmitSelectionOrCandidatesOrAddUnknownOrDuplicateReferences() {
        for (List<String> ids : List.of(List.of(FIRST, SECOND), List.of(SELECTION, FIRST),
                List.of(SELECTION, FIRST, SECOND, "cspec-overlay-unknown-1.0.0"),
                List.of(SELECTION, FIRST, SECOND, FIRST))) {
            ObjectNode invalid = conflict();
            ArrayNode values = invalid.putArray("participantIds");
            ids.forEach(values::add);
            reason("CSPEC_CONFLICT_PARTICIPANTS_CONFLICT", validate(true, List.of(review(true)), List.of(invalid)));
        }
        ObjectNode wrongType = conflict();
        wrongType.putArray("participantIds").add(SELECTION).add(FIRST).add(2);
        reason("CSPEC_CONFLICT_PARTICIPANTS_CONFLICT", validate(true, List.of(review(true)), List.of(wrongType)));
    }

    @Test
    void conflictIsOpenMultipleMatchRecordWithoutUnsupportedSchemaOrResolutionFields() {
        for (String field : List.of("conflictId", "conflictType", "participantIds", "resolutionStatus", "detectedAt")) {
            ObjectNode invalid = conflict();
            invalid.remove(field);
            assertFalse(validate(true, List.of(review(true)), List.of(invalid)).valid(), field);
        }
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("resolutionStatus", "RESOLVED"), node -> node.put("conflictType", "SOURCE_VALUE_DISAGREEMENT"),
                node -> node.put("resolutionReviewRecordId", "review-sha256-" + "c".repeat(64)))) {
            ObjectNode invalid = conflict();
            mutation.accept(invalid);
            reason("CSPEC_CONFLICT_EVENT_UNSUPPORTED", validate(true, List.of(review(true)), List.of(invalid)));
        }
        ObjectNode extra = conflict().put("schemaVersion", "conflict-record-v2");
        reason("CSPEC_CONFLICT_FIELD_UNKNOWN", validate(true, List.of(review(true)), List.of(extra)));
        reason("CSPEC_CONFLICT_REASON_CONFLICT", validate(true, List.of(review(true)), List.of(conflict().put("reasonCode", METADATA_REASON))));
    }

    @Test
    void identitiesAndDuplicateEventIdsCannotBeAcceptedOrSilentlyDeduplicated() {
        ObjectNode review = review(false);
        reason("CSPEC_REVIEW_ID_DUPLICATE", validate(false, List.of(review, review), List.of()));
        reason("CSPEC_REVIEW_IDENTITY_MISMATCH", validate(false,
                List.of(review(false).put("reviewRecordId", "review-sha256-" + "c".repeat(64))), List.of()));
        ObjectNode conflict = conflict();
        reason("CSPEC_CONFLICT_ID_DUPLICATE", validate(true, List.of(review(true)), List.of(conflict, conflict)));
        reason("CSPEC_CONFLICT_IDENTITY_MISMATCH", validate(true, List.of(review(true)),
                List.of(conflict().put("conflictId", "conflict-sha256-" + "c".repeat(64)))));
        ObjectNode changedTime = conflict().put("detectedAt", "2026-09-05T00:00:01Z");
        reason("CSPEC_CONFLICT_IDENTITY_MISMATCH", validate(true, List.of(review(true)), List.of(changedTime)));
    }

    private CSpecPreflightReferences.Validation validate(boolean multiple, List<JsonNode> reviews, List<JsonNode> conflicts) {
        return CSpecPreflightReferences.validate(mapper, adapter, CONTEXT, SELECTION,
                multiple ? List.of(FIRST, SECOND) : List.of(FIRST), multiple, reviews, conflicts);
    }

    private void reason(String reason, CSpecPreflightReferences.Validation result) {
        assertFalse(result.valid());
        assertEquals(reason, result.reasonCode());
        assertNotNull(result.field());
    }

    private ObjectNode review(boolean multiple) {
        ObjectNode review = mapper.createObjectNode().put("schemaVersion", "review-record-v2")
                .put("contextId", CONTEXT).put("subjectType", "CSPEC_SELECTION").put("subjectId", SELECTION)
                .put("eventType", "REQUESTED").put("status", "REQUIRED").put("decision", "NONE")
                .put("reasonCode", multiple ? MULTIPLE_REASON : METADATA_REASON).put("createdAt", "2026-09-05T00:00:00Z");
        review.putObject("actor").put("actorId", "reviewer-synthetic-curator").put("role", "CURATOR");
        return stampReview(review);
    }

    private ObjectNode stampReview(ObjectNode review) {
        review.remove("reviewRecordId");
        review.put("reviewRecordId", "review-sha256-" + adapter.canonicalSha256(review));
        return review;
    }

    private ObjectNode conflict() {
        ObjectNode conflict = mapper.createObjectNode().put("conflictType", "RULE_PROFILE_MULTIPLE_MATCH")
                .put("resolutionStatus", "OPEN").put("reasonCode", MULTIPLE_REASON).put("detectedAt", "2026-09-05T00:00:00Z");
        conflict.putArray("participantIds").add(SECOND).add(SELECTION).add(FIRST);
        return stampConflict(conflict);
    }

    private ObjectNode stampConflict(ObjectNode conflict) {
        conflict.remove("conflictId");
        ObjectNode projection = conflict.deepCopy();
        ArrayNode sorted = mapper.createArrayNode();
        StreamSupport.stream(conflict.path("participantIds").spliterator(), false)
                .map((JsonNode value) -> value.asText()).sorted().forEach(sorted::add);
        projection.set("participantIds", sorted);
        conflict.put("conflictId", "conflict-sha256-" + adapter.canonicalSha256(projection));
        return conflict;
    }
}
