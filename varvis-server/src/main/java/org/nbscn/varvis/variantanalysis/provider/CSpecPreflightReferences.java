package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks caller-supplied open request envelopes for one CSpec selection.
 * This helper neither creates review events nor executes review decisions or a review lifecycle.
 */
final class CSpecPreflightReferences {
    private static final Set<String> REVIEW_FIELDS = Set.of("schemaVersion", "reviewRecordId", "contextId",
            "subjectType", "subjectId", "eventType", "status", "decision", "actor", "reasonCode", "createdAt",
            "rationaleSummary");
    private static final List<String> REVIEW_UNSUPPORTED_FIELDS = List.of("sourceLocators", "previousReviewRecordId", "override");
    private static final Set<String> CONFLICT_FIELDS = Set.of("conflictId", "conflictType", "participantIds",
            "resolutionStatus", "detectedAt", "reasonCode");
    private static final Set<String> ACTOR_ROLES = Set.of("GENETICIST", "LAB_SCIENTIST", "CURATOR", "SYSTEM_ADMINISTRATOR");

    private CSpecPreflightReferences() { }

    static Validation validate(ObjectMapper mapper, RuntimeEvidenceV2Adapter adapter,
                               String contextId, String selectionId, List<String> candidateOverlayIds,
                               boolean conflictRequired, List<JsonNode> reviews, List<JsonNode> conflicts) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(adapter, "adapter");
        List<JsonNode> reviewRecords = copyRecords(reviews);
        List<JsonNode> conflictRecords = copyRecords(conflicts);
        try {
            require(matches(contextId, "context-sha256-[0-9a-f]{64}"), "CSPEC_REFERENCE_CONTEXT_INVALID", "contextId");
            require(matches(selectionId, "cspec-selection-sha256-[0-9a-f]{64}"), "CSPEC_REFERENCE_SELECTION_INVALID", "selectionId");
            if (!(candidateOverlayIds != null && (conflictRequired ? candidateOverlayIds.size() >= 2 : candidateOverlayIds.size() == 1))) { throw new Invalid("CSPEC_REFERENCE_CANDIDATES_INVALID", "candidateOverlayIds"); }
            Set<String> participants = new HashSet<>();
            participants.add(selectionId);
            for (String overlayId : candidateOverlayIds) {
                require(matches(overlayId, "cspec-overlay-[a-z0-9][a-z0-9._-]{2,127}")
                                && matches(overlayId, "[A-Za-z][A-Za-z0-9._:-]{2,127}") && participants.add(overlayId),
                        "CSPEC_REFERENCE_CANDIDATES_INVALID", "candidateOverlayIds");
            }
            require(!reviewRecords.isEmpty(), "CSPEC_REVIEW_RECORDS_REQUIRED", "reviewRecords");
            require(conflictRequired || conflictRecords.isEmpty(), "CSPEC_CONFLICT_UNEXPECTED", "conflicts");
            require(!conflictRequired || !conflictRecords.isEmpty(), "CSPEC_CONFLICT_RECORDS_REQUIRED", "conflicts");
            String reason = conflictRequired ? "CSPEC_MULTIPLE_EXACT_MATCHES" : "CSPEC_MATCH_NOT_EXECUTABLE";
            Set<String> reviewIds = new HashSet<>();
            for (int index = 0; index < reviewRecords.size(); index++) {
                String field = "reviewRecords[" + index + "]";
                JsonNode review = reviewRecords.get(index);
                validateReview(adapter, review, contextId, selectionId, reason, field);
                require(reviewIds.add(text(review, "reviewRecordId")), "CSPEC_REVIEW_ID_DUPLICATE", field + ".reviewRecordId");
            }
            Set<String> conflictIds = new HashSet<>();
            for (int index = 0; index < conflictRecords.size(); index++) {
                String field = "conflicts[" + index + "]";
                JsonNode conflict = conflictRecords.get(index);
                validateConflict(mapper, adapter, conflict, participants, reason, field);
                require(conflictIds.add(text(conflict, "conflictId")), "CSPEC_CONFLICT_ID_DUPLICATE", field + ".conflictId");
            }
            return new Validation(reviewRecords, conflictRecords, null, null);
        } catch (Invalid failure) {
            return new Validation(reviewRecords, conflictRecords, failure.reason, failure.field);
        }
    }

    private static void validateReview(RuntimeEvidenceV2Adapter adapter, JsonNode review,
                                       String contextId, String selectionId, String reason, String field) {
        if (!(review != null && review.isObject())) { throw new Invalid("CSPEC_REVIEW_RECORD_INVALID", field); }
        for (String unsupported : REVIEW_UNSUPPORTED_FIELDS) {
            require(!review.has(unsupported), "CSPEC_REVIEW_EVENT_UNSUPPORTED", field + "." + unsupported);
        }
        onlyFields(review, REVIEW_FIELDS, "CSPEC_REVIEW_FIELD_UNKNOWN", field);
        require("review-record-v2".equals(text(review, "schemaVersion")), "CSPEC_REVIEW_SCHEMA_INVALID", field + ".schemaVersion");
        require(contextId.equals(text(review, "contextId")), "CSPEC_REVIEW_CONTEXT_CONFLICT", field + ".contextId");
        require("CSPEC_SELECTION".equals(text(review, "subjectType")) && selectionId.equals(text(review, "subjectId")),
                "CSPEC_REVIEW_SUBJECT_CONFLICT", field + ".subjectId");
        require("REQUESTED".equals(text(review, "eventType")) && "REQUIRED".equals(text(review, "status"))
                        && "NONE".equals(text(review, "decision")),
                "CSPEC_REVIEW_EVENT_UNSUPPORTED", field + ".eventType");
        JsonNode actor = review.path("actor");
        require(actor.isObject(), "CSPEC_REVIEW_ACTOR_INVALID", field + ".actor");
        onlyFields(actor, Set.of("actorId", "role"), "CSPEC_REVIEW_FIELD_UNKNOWN", field + ".actor");
        require(matches(text(actor, "actorId"), "reviewer-[a-z0-9][a-z0-9._-]{7,95}")
                        && text(actor, "role") != null && ACTOR_ROLES.contains(text(actor, "role")),
                "CSPEC_REVIEW_ACTOR_INVALID", field + ".actor");
        require(reason.equals(text(review, "reasonCode")), "CSPEC_REVIEW_REASON_CONFLICT", field + ".reasonCode");
        require(utc(text(review, "createdAt")), "CSPEC_REVIEW_TIME_INVALID", field + ".createdAt");
        if (review.has("rationaleSummary")) {
            String rationale = text(review, "rationaleSummary");
            require(rationale != null && rationale.codePointCount(0, rationale.length()) >= 1
                            && rationale.codePointCount(0, rationale.length()) <= 1000,
                    "CSPEC_REVIEW_RATIONALE_INVALID", field + ".rationaleSummary");
        }
        require(matches(text(review, "reviewRecordId"), "review-sha256-[0-9a-f]{64}"),
                "CSPEC_REVIEW_IDENTITY_MISMATCH", field + ".reviewRecordId");
        ObjectNode projection = ((ObjectNode) review).deepCopy();
        projection.remove("reviewRecordId");
        require(("review-sha256-" + adapter.canonicalSha256(projection)).equals(text(review, "reviewRecordId")),
                "CSPEC_REVIEW_IDENTITY_MISMATCH", field + ".reviewRecordId");
    }

    private static void validateConflict(ObjectMapper mapper, RuntimeEvidenceV2Adapter adapter, JsonNode conflict,
                                         Set<String> expectedParticipants, String reason, String field) {
        if (!(conflict != null && conflict.isObject())) { throw new Invalid("CSPEC_CONFLICT_RECORD_INVALID", field); }
        require(!conflict.has("resolutionReviewRecordId"), "CSPEC_CONFLICT_EVENT_UNSUPPORTED", field + ".resolutionReviewRecordId");
        onlyFields(conflict, CONFLICT_FIELDS, "CSPEC_CONFLICT_FIELD_UNKNOWN", field);
        require("OPEN".equals(text(conflict, "resolutionStatus"))
                        && "RULE_PROFILE_MULTIPLE_MATCH".equals(text(conflict, "conflictType")),
                "CSPEC_CONFLICT_EVENT_UNSUPPORTED", field + ".resolutionStatus");
        require(!conflict.has("reasonCode") || reason.equals(text(conflict, "reasonCode")),
                "CSPEC_CONFLICT_REASON_CONFLICT", field + ".reasonCode");
        require(utc(text(conflict, "detectedAt")), "CSPEC_CONFLICT_TIME_INVALID", field + ".detectedAt");
        JsonNode ids = conflict.path("participantIds");
        require(ids.isArray(), "CSPEC_CONFLICT_PARTICIPANTS_CONFLICT", field + ".participantIds");
        Set<String> actual = new TreeSet<>();
        for (JsonNode id : ids) {
            require(id.isTextual() && expectedParticipants.contains(id.asText()) && actual.add(id.asText()),
                    "CSPEC_CONFLICT_PARTICIPANTS_CONFLICT", field + ".participantIds");
        }
        require(actual.equals(expectedParticipants), "CSPEC_CONFLICT_PARTICIPANTS_CONFLICT", field + ".participantIds");
        require(matches(text(conflict, "conflictId"), "conflict-sha256-[0-9a-f]{64}"),
                "CSPEC_CONFLICT_IDENTITY_MISMATCH", field + ".conflictId");
        ObjectNode projection = ((ObjectNode) conflict).deepCopy();
        projection.remove("conflictId");
        ArrayNode sorted = mapper.createArrayNode();
        actual.forEach(sorted::add);
        projection.set("participantIds", sorted);
        require(("conflict-sha256-" + adapter.canonicalSha256(projection)).equals(text(conflict, "conflictId")),
                "CSPEC_CONFLICT_IDENTITY_MISMATCH", field + ".conflictId");
    }

    private static void onlyFields(JsonNode value, Set<String> allowed, String reason, String field) {
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            require(allowed.contains(name), reason, field + "." + name);
        }
    }

    private static boolean utc(String value) {
        if (!matches(value, "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|\\+00:00)")) {
            return false;
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            return parsed.getYear() > 0 && ZoneOffset.UTC.equals(parsed.getOffset());
        } catch (DateTimeParseException failure) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText() : null;
    }
    private static boolean matches(String value, String pattern) { return value != null && value.matches(pattern); }
    private static void require(boolean condition, String reason, String field) {
        if (!condition) { throw new Invalid(reason, field); }
    }
    private static List<JsonNode> copyRecords(List<JsonNode> input) {
        if (input == null) { return List.of(); }
        List<JsonNode> result = new ArrayList<>(input.size());
        for (JsonNode node : input) { result.add(node == null ? null : node.deepCopy()); }
        return Collections.unmodifiableList(result);
    }

    record Validation(List<JsonNode> reviews, List<JsonNode> conflicts, String reasonCode, String field) {
        Validation { reviews = copyRecords(reviews); conflicts = copyRecords(conflicts); }
        boolean valid() { return reasonCode == null; }
        @Override public List<JsonNode> reviews() { return copyRecords(reviews); }
        @Override public List<JsonNode> conflicts() { return copyRecords(conflicts); }
    }

    private static final class Invalid extends RuntimeException {
        private final String reason;
        private final String field;
        private Invalid(String reason, String field) { this.reason = reason; this.field = field; }
    }
}
