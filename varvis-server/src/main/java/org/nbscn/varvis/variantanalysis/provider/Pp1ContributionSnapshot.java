package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.lang.Nullable;
import org.nbscn.varvis.variantanalysis.service.CurrentAcmgEvaluationSession;

/** A server-owned, frozen inventory of adopted contributions, not a patient declaration,
 * historical score or author estimate. No production PP4 adoption source is installed here. */
public final class Pp1ContributionSnapshot {
    public static final String CAPABILITY = "TRUSTED_CURRENT_CONTRIBUTION_SNAPSHOT_V1";
    public static final String SCHEMA = "pp1-current-contribution-snapshot-v1";
    public interface Source {
        ObjectNode identity();
        ObjectNode capture(String analysisId, ObjectNode recovered) throws IOException;
    }
    private final ObjectMapper json;
    @Nullable private final ObjectNode snapshot;
    private final String issue;
    @Nullable private final CurrentAcmgEvaluationSession currentSession;

    private Pp1ContributionSnapshot(ObjectMapper json, @Nullable ObjectNode snapshot, String issue) {
        this(json, snapshot, issue, null);
    }
    private Pp1ContributionSnapshot(ObjectMapper json, @Nullable ObjectNode snapshot, String issue,
            @Nullable CurrentAcmgEvaluationSession currentSession) {
        this.json = json; this.snapshot = snapshot == null ? null : snapshot.deepCopy(); this.issue = issue;
        this.currentSession = currentSession;
    }
    static Pp1ContributionSnapshot missing(ObjectMapper json) {
        return new Pp1ContributionSnapshot(json, null, "PP1_CURRENT_CONTRIBUTION_SOURCE_UNAVAILABLE");
    }
    static Pp1ContributionSnapshot capture(ObjectMapper json, @Nullable Source source, ObjectNode sourceIdentity,
            String analysis, ObjectNode recovered) {
        if (source == null) return missing(json);
        try {
            require(sourceIdentity.equals(source.identity()), "PP1_CONTRIBUTION_SOURCE_CHANGED");
            require("CURRENT_EVALUATION_ONLY".equals(sourceIdentity.path("scope").asText())
                    && "SERVICE_OWNED".equals(sourceIdentity.path("origin").asText()), "PP1_CONTRIBUTION_SOURCE_NOT_CURRENT");
            ObjectNode captured = source.capture(analysis, recovered.deepCopy());
            require(captured != null, "PP1_CONTRIBUTION_SNAPSHOT_REQUIRED");
            if (captured == null) return missing(json);
            // Validate and retain the same owned bytes. A later source identity
            // callback must not mutate its returned node under a verified pin.
            ObjectNode value = captured.deepCopy();
            require(json.writeValueAsBytes(value).length <= 1048576, "PP1_CONTRIBUTION_SNAPSHOT_LIMIT");
            var content = value.deepCopy(); content.remove("snapshotSha256");
            require(SCHEMA.equals(value.path("schemaVersion").asText()) && analysis.equals(value.path("analysisId").asText())
                    && digest(json, sourceIdentity).equals(value.path("sourceIdentitySha256").asText())
                    && digest(json, content).equals(value.path("snapshotSha256").asText())
                    && value.path("entries").isArray() && value.path("entries").size() <= 256,
                    "PP1_CONTRIBUTION_SNAPSHOT_INVALID");
            CurrentAcmgEvaluationSession nativeSession = null;
            if (source instanceof Pp1CurrentContributionSource nativeSource) {
                nativeSession = nativeSource.currentSession();
                ObjectNode sessionIdentity = nativeSession.identity();
                require(sessionIdentity.equals(value.path("currentSessionIdentity"))
                        && analysis.equals(sessionIdentity.path("analysisId").asText())
                        && digest(json, sessionIdentity).equals(value.path("currentSessionIdentitySha256").asText()),
                        "PP1_CURRENT_CONTRIBUTION_SESSION_CAPTURE_INVALID");
            } else require(!value.has("currentSessionIdentity") && !value.has("currentSessionIdentitySha256"),
                    "PP1_CURRENT_CONTRIBUTION_NATIVE_SOURCE_REQUIRED");
            require(sourceIdentity.equals(source.identity()), "PP1_CONTRIBUTION_SOURCE_CHANGED");
            return new Pp1ContributionSnapshot(json, value, "", nativeSession);
        } catch (IOException | IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            return new Pp1ContributionSnapshot(json, null, message != null && message.matches("PP1_[A-Z0-9_]+")
                    ? message : "PP1_CONTRIBUTION_CAPTURE_FAILED");
        }
    }
    ObjectNode summary() {
        var snapshot = this.snapshot;
        var out = json.createObjectNode().put("capability", CAPABILITY).put("status", snapshot == null ? "UNKNOWN" : "CAPTURED");
        if (snapshot == null) out.put("reasonCode", issue);
        else out.put("snapshotSha256", snapshot.path("snapshotSha256").asText())
                .put("sourceIdentitySha256", snapshot.path("sourceIdentitySha256").asText());
        return out;
    }
    ObjectNode originals() { return snapshot == null ? summary() : snapshot.deepCopy(); }

    /** The complete current binding, not just gene/disease or enabled criteria, selects an entry.
     * A COMPLETE marker is meaningful only from the configured internal Source; HTTP has no such port. */
    ObjectNode apply(ObjectNode binding, @Nullable String analysis) {
        if (currentSession != null) return currentUnknown(binding.deepCopy(), "PP1_CURRENT_CONTRIBUTION_TARGET_SESSION_REQUIRED");
        return applyEntries(binding, analysis);
    }

    /** The active target is a native post-claim capability, never a saved audit JSON.
     * Per-run identity is separate from the stable source dependency and selection hash.
     */
    ObjectNode apply(ObjectNode binding, @Nullable String analysis, @Nullable CurrentAcmgEvaluationSession targetSession) {
        var result = binding.deepCopy();
        CurrentAcmgEvaluationSession capturedSession = currentSession;
        if (capturedSession == null) return currentUnknown(result, snapshot == null ? issue : "PP1_CURRENT_CONTRIBUTION_NATIVE_SOURCE_REQUIRED");
        if (targetSession == null) return currentUnknown(result, "PP1_CURRENT_CONTRIBUTION_TARGET_SESSION_REQUIRED");
        ObjectNode target = targetSession.identity();
        if (analysis == null || !analysis.equals(target.path("analysisId").asText()))
            return currentUnknown(result, "PP1_CONTRIBUTION_ANALYSIS_MISMATCH");
        if (!capturedSession.identity().equals(target)) return currentUnknown(result, "PP1_CURRENT_CONTRIBUTION_SESSION_MISMATCH");
        String pin = digest(json, binding.path("identity"));
        var expectedAttempt = targetSession.attempts().stream().filter(attempt ->
                "READY".equals(attempt.path("bindingStatus").asText())
                && pin.equals(attempt.path("bindingIdentitySha256").asText())
                && attempt.at("/binding/identity").equals(binding.path("identity"))
                && attempt.at("/binding/selection").equals(binding.path("selection"))
                && attempt.at("/binding/ledger/scope").equals(binding.at("/ledger/scope"))
                && attempt.at("/binding/ledger/allele").equals(binding.at("/ledger/allele"))
                && immutableLedger(attempt.at("/binding/ledger")).equals(immutableLedger(binding.path("ledger")))).findFirst();
        if (expectedAttempt.isEmpty() || !target.path("executionSourceSha256").equals(binding.at("/identity/sourceSnapshotSha256")))
            return currentUnknown(result, "PP1_CURRENT_CONTRIBUTION_SESSION_SCOPE_MISMATCH");
        if (hasCurrentOccupancy(binding.path("ledger"))) {
            // Exact replay of this same frozen native inventory is idempotent.
            // A different pre-existing allocation is retained as audit, never erased.
            var baseline = (ObjectNode) expectedAttempt.orElseThrow().path("binding");
            ObjectNode expected = applyEntries(baseline, analysis);
            if (expected.path("ledger").equals(binding.path("ledger"))) return result;
            return currentUnknown(result, "PP1_CURRENT_CONTRIBUTION_EXISTING_OCCUPANCY_UNRESOLVED");
        }
        return applyEntries(binding, analysis);
    }

    private ObjectNode applyEntries(ObjectNode binding, @Nullable String analysis) {
        var result = binding.deepCopy(); var ledger = (ObjectNode) result.path("ledger");
        ObjectNode retained = snapshot;
        if (retained == null) return unknown(result, issue);
        if (analysis == null || !analysis.equals(retained.path("analysisId").asText()))
            return unknown(result, "PP1_CONTRIBUTION_ANALYSIS_MISMATCH");
        String bindingPin = digest(json, binding.path("identity")); JsonNode selected = null;
        for (var entry : retained.path("entries")) if (bindingPin.equals(entry.path("bindingIdentitySha256").asText())) {
            if (selected != null) return unknown(result, "PP1_CONTRIBUTION_SCOPE_AMBIGUOUS");
            selected = entry;
        }
        if (selected == null) return unknown(result, "PP1_CURRENT_CONTRIBUTION_SCOPE_REQUIRED");
        try {
            require(selected.path("scope").equals(ledger.path("scope")) && selected.path("allele").equals(ledger.path("allele")),
                    "PP1_CONTRIBUTION_SCOPE_MISMATCH");
            var coverage = selected.path("coverage");
            if (currentSession != null && !"COMPLETE".equals(coverage.path("status").asText())) {
                String reason = coverage.path("reasonCode").asText();
                return unknown(result, reason.matches("PP1_[A-Z0-9_]+") ? reason : "PP1_CONTRIBUTION_INVENTORY_INCOMPLETE");
            }
            require("COMPLETE".equals(coverage.path("status").asText()) && hash(coverage.path("provenanceSha256").asText()),
                    "PP1_CONTRIBUTION_INVENTORY_INCOMPLETE");
            Set<String> checked = identifiers(coverage.path("checkedResultSha256s"), true);
            require(!checked.isEmpty(), "PP1_CONTRIBUTION_INVENTORY_PROOF_REQUIRED");
            require(selected.path("contributions").isArray() && selected.path("contributions").size() <= 256,
                    "PP1_CONTRIBUTION_RECORDS_REQUIRED");
            Map<String, JsonNode> unique = new LinkedHashMap<>();
            var events = new TreeSet<String>(); var facts = new TreeSet<String>(); BigDecimal pp4 = BigDecimal.ZERO;
            boolean pp4Adopted = false;
            for (var contribution : selected.path("contributions")) {
                String id = contribution.path("contributionId").asText();
                require(hash(id) && "ADOPTED".equals(contribution.path("status").asText())
                        && Set.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4").contains(contribution.path("criterionId").asText())
                        && checked.contains(contribution.path("sourceResultSha256").asText()), "PP1_CONTRIBUTION_RECORD_INVALID");
                var old = unique.putIfAbsent(id, contribution);
                require(old == null || old.equals(contribution), "PP1_CONTRIBUTION_RECORD_CONFLICT");
                if (old != null) continue;
                Set<String> addedEvents = identifiers(contribution.path("eventIds"), false);
                Set<String> addedFacts = identifiers(contribution.path("exclusiveFactIds"), false);
                BigDecimal points = pointValue(contribution.path("points"), "PP1_CONTRIBUTION_POINTS_INVALID");
                require(addedEvents.stream().noneMatch(events::contains) && addedFacts.stream().noneMatch(facts::contains),
                        "PP1_CONTRIBUTION_FACT_ALLOCATION_CONFLICT");
                if ("ACMG_AMP_2015:PP4".equals(contribution.path("criterionId").asText())) {
                    // The source supplies the adopted PP4 allocation. Separate PP4 evaluations
                    // are alternatives, not scores to add under a new rule invented here.
                    require(!pp4Adopted, "PP1_CONTRIBUTION_PP4_ALLOCATION_CONFLICT");
                    pp4Adopted = true; pp4 = points;
                }
                else require(!addedEvents.isEmpty() || !addedFacts.isEmpty(), "PP1_CONTRIBUTION_FAMILY_IDENTITY_REQUIRED");
                events.addAll(addedEvents); facts.addAll(addedFacts);
            }
            require(events.size() <= 256 && facts.size() <= 256, "PP1_CONTRIBUTION_FACTS_LIMIT");
            ledger.put("status", pp4.signum() == 0 && events.isEmpty() && facts.isEmpty() ? "KNOWN_EMPTY" : "KNOWN")
                    // Python Decimal and the existing frontend exact-decimal reader both accept strings.
                    // Retain arbitrary precision rather than passing an adoption amount through double.
                    .put("pp4Points", pp4.stripTrailingZeros().toPlainString()).put("reasonCode", "PP1_VERIFIED_CURRENT_CONTRIBUTION_INVENTORY")
                    .put("contributionSnapshotSha256", retained.path("snapshotSha256").asText())
                    .put("contributionSourceIdentitySha256", retained.path("sourceIdentitySha256").asText())
                    .put("inventoryProvenanceSha256", coverage.path("provenanceSha256").asText());
            var eventIds = ledger.putArray("eventIds"); events.forEach(eventIds::add);
            var factIds = ledger.putArray("exclusiveFactIds"); facts.forEach(factIds::add);
            return result;
        } catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            return unknown(result, message != null && message.matches("PP1_[A-Z0-9_]+")
                    ? message : "PP1_CONTRIBUTION_RECORD_INVALID");
        }
    }
    private static JsonNode immutableLedger(JsonNode ledger) {
        if (!ledger.isObject()) return ledger;
        ObjectNode identity = ((ObjectNode) ledger).deepCopy();
        identity.remove(List.of("status", "reasonCode", "pp4Points", "eventIds", "exclusiveFactIds",
                "contributionSnapshotSha256", "contributionSourceIdentitySha256", "inventoryProvenanceSha256",
                "unmergedCurrentOccupancy"));
        return identity;
    }
    private static boolean hasCurrentOccupancy(JsonNode ledger) {
        return ledger.path("unmergedCurrentOccupancy").isObject()
                || !ledger.path("eventIds").isEmpty() || !ledger.path("exclusiveFactIds").isEmpty()
                || !ledger.path("pp4Points").isNull() && !ledger.path("pp4Points").isMissingNode()
                || Set.of("KNOWN", "KNOWN_EMPTY").contains(ledger.path("status").asText());
    }
    private ObjectNode currentUnknown(ObjectNode binding, String reason) {
        JsonNode before = binding.path("ledger").deepCopy();
        ObjectNode result = unknown(binding, reason);
        if (hasCurrentOccupancy(before)) {
            ObjectNode ledger = (ObjectNode) result.path("ledger");
            JsonNode previous = before.path("unmergedCurrentOccupancy");
            boolean sameBlockedOccupancy = previous.isObject() && "UNKNOWN".equals(before.path("status").asText())
                    && before.path("pp4Points").isNull()
                    && before.path("eventIds").equals(previous.path("eventIds"))
                    && before.path("exclusiveFactIds").equals(previous.path("exclusiveFactIds"));
            ledger.set("unmergedCurrentOccupancy", sameBlockedOccupancy ? previous.deepCopy() : before);
            if (before.path("eventIds").isArray()) ledger.set("eventIds", before.path("eventIds").deepCopy());
            if (before.path("exclusiveFactIds").isArray()) ledger.set("exclusiveFactIds", before.path("exclusiveFactIds").deepCopy());
        }
        return result;
    }
    private ObjectNode unknown(ObjectNode binding, String reason) {
        var ledger = (ObjectNode) binding.path("ledger");
        ledger.put("status", "UNKNOWN").putNull("pp4Points"); ledger.put("reasonCode", reason);
        ledger.putArray("eventIds"); ledger.putArray("exclusiveFactIds"); return binding;
    }
    private static Set<String> identifiers(JsonNode values, boolean sha) {
        require(values.isArray() && values.size() <= 256, "PP1_CONTRIBUTION_FACTS_INVALID");
        var out = new TreeSet<String>();
        for (var value : values) {
            require(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 256
                    && value.asText().codePoints().noneMatch(Character::isISOControl)
                    && (!sha || hash(value.asText())), "PP1_CONTRIBUTION_FACTS_INVALID");
            out.add(value.asText());
        }
        return out;
    }
    static String digest(ObjectMapper json, JsonNode value) { return new RuntimeEvidenceV2Adapter(json).canonicalSha256(value); }
    static BigDecimal pointValue(JsonNode value, String reason) {
        require((value.isNumber() || value.isTextual()) && value.asText().length() <= 1024
                && value.asText().matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"), reason);
        try {
            BigDecimal points = new BigDecimal(value.asText());
            require(points.signum() >= 0 && points.compareTo(BigDecimal.valueOf(5)) <= 0
                    && Math.abs((long) points.scale()) <= 1024, reason);
            return points;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException(reason); }
    }
    private static boolean hash(String value) { return value.matches("[a-f0-9]{64}"); }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
