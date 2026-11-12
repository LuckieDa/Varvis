package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.provider.Pp1AnalysisContext;

/** An in-memory capability for one claimed evaluation, never reconstructed from saved JSON.
 * This proves run and selected-input identity; it does not approve a scientific result.
 */
public final class CurrentAcmgEvaluationSession {
    private final ObjectNode identity;
    private final List<ObjectNode> attempts;

    private CurrentAcmgEvaluationSession(ObjectNode identity, List<ObjectNode> attempts) {
        this.identity = identity.deepCopy();
        this.attempts = attempts.stream().map(mappedItem -> mappedItem.deepCopy()).toList();
    }

    /** Service package only, after successful claim and actual typed context preparation.
     * Reuse/history paths must never call this issuer. Source and request pins are read from
     * the store rather than trusted from a recovered document or a caller's completeness flag.
     */
    static CurrentAcmgEvaluationSession issueAfterClaim(ObjectMapper json, AcmgBusinessStore store,
            String analysisId, String runId, String expectedRequestSha256, SelectedEvidencePreparation prepared) {
        return issueVerifiedSelection(json,store,analysisId,runId,expectedRequestSha256,prepared,null);
    }

    /** Same package-private issuer with the original typed ranking witness. A tree conversion
     * may normalize decimal scale; its reserialization is not the original DTO's raw bytes.
     * Both the persisted raw pin and every trusted prepared snapshot must match independently.
     */
    static CurrentAcmgEvaluationSession issueAfterClaim(ObjectMapper json, AcmgBusinessStore store,
            String analysisId, String runId, String expectedRequestSha256, SelectedEvidencePreparation prepared,
            List<org.nbscn.varvis.variantanalysis.domain.VariantInterpretation> ranked) {
        return issueVerifiedSelection(json,store,analysisId,runId,expectedRequestSha256,prepared,Objects.requireNonNull(ranked));
    }

    private static CurrentAcmgEvaluationSession issueVerifiedSelection(ObjectMapper json, AcmgBusinessStore store,
            String analysisId,String runId,String expectedRequestSha256,SelectedEvidencePreparation prepared,
            @org.springframework.lang.Nullable List<org.nbscn.varvis.variantanalysis.domain.VariantInterpretation> ranked) {
        Objects.requireNonNull(json); Objects.requireNonNull(store); Objects.requireNonNull(prepared);
        require(text(analysisId) && text(runId) && digest(expectedRequestSha256), "CURRENT_SESSION_IDENTITY_REQUIRED");
        var run = store.run(analysisId, runId).orElseThrow(() -> invalid("CURRENT_SESSION_CLAIM_REQUIRED"));
        require(analysisId.equals(run.analysisId()) && runId.equals(run.id()) && "RUNNING".equals(run.status())
                && expectedRequestSha256.equals(run.requestSha256()), "CURRENT_SESSION_CLAIM_MISMATCH");
        var hashes = new AcmgBusinessArtifacts(json);
        JsonNode request;
        try { request = json.readTree(run.requestJson()); }
        catch (IOException | IllegalArgumentException failure) { throw invalid("CURRENT_SESSION_REQUEST_INVALID"); }
        if (!(request != null && request.isObject() && expectedRequestSha256.equals(hashes.requestHash(request)))) {
            throw invalid("CURRENT_SESSION_REQUEST_PIN_MISMATCH");
        }
        String sourcePin = request.at("/localBatchPublishedInputIdentity/executionSourceSha256").asText();
        String rankingPin = request.at("/localBatchPublishedInputIdentity/candidatesRawSha256").asText();
        require(digest(sourcePin) && digest(rankingPin), "CURRENT_SESSION_PUBLISHED_INPUT_PIN_REQUIRED");
        var source = store.source(analysisId).orElseThrow(() -> invalid("CURRENT_SESSION_EXECUTION_SOURCE_REQUIRED"));
        require(sourcePin.equals(source.sha256()) && sourcePin.equals(AcmgBusinessStore.sha(source.json())),
                "CURRENT_SESSION_EXECUTION_SOURCE_MISMATCH");
        JsonNode declaredCriteria = request.at("/executionProfile/enabledCriteria");
        require(declaredCriteria.isArray() && !declaredCriteria.isEmpty(), "CURRENT_SESSION_CRITERIA_REQUIRED");
        var criteria = new ArrayList<String>();
        for (var criterion : declaredCriteria) {
            require(criterion.isTextual() && criterion.asText().matches("ACMG_AMP_2015:[A-Z]+[0-9]+"),
                    "CURRENT_SESSION_CRITERIA_INVALID");
            criteria.add(criterion.asText());
        }
        require(new HashSet<>(criteria).size() == criteria.size() && criteria.contains("ACMG_AMP_2015:PP1"),
                "CURRENT_SESSION_PP1_PROFILE_REQUIRED");
        var assembly = prepared.assembly();
        if (!(assembly != null && analysisId.equals(assembly.analysisId()) && assembly.unmatchedInputs().isEmpty()
                && assembly.selectedCount() > 0 && assembly.selectedCount() == assembly.candidateIds().size()
                && assembly.candidates().size() == assembly.selectedCount()
                && new HashSet<>(assembly.candidateIds()).size() == assembly.selectedCount())) {
            throw invalid("CURRENT_SESSION_SELECTED_COVERAGE_INVALID");
        }
        var ranking = json.createArrayNode();
        var bindings = new ArrayList<ObjectNode>();
        var seen = new HashSet<List<String>>();
        for (int index = 0; index < assembly.candidates().size(); index++) {
            var candidate = assembly.candidates().get(index);
            require(text(candidate.candidateId()) && candidate.candidateId().equals(assembly.candidateIds().get(index))
                    && !candidate.attempts().isEmpty(), "CURRENT_SESSION_SELECTED_COVERAGE_INVALID");
            ranking.add(prepared.formalCandidate(candidate.candidateId()));
            for (var attempt : candidate.attempts()) {
                require(candidate.candidateId().equals(attempt.candidateId()) && text(attempt.hypothesisId())
                        && seen.add(List.of(candidate.candidateId(), attempt.hypothesisId())) && seen.size() <= 256,
                        "CURRENT_SESSION_ATTEMPT_BINDING_INVALID");
                var entry = json.createObjectNode().put("candidateId", candidate.candidateId()).put("hypothesisId", attempt.hypothesisId());
                if (!attempt.blockers().isEmpty() || attempt.context() == null) {
                    entry.put("bindingStatus", "UNAVAILABLE");
                    entry.set("blockers", json.valueToTree(attempt.blockers()));
                    if (attempt.blockers().isEmpty()) entry.put("reasonCode", "SELECTED_CONTEXT_UNAVAILABLE");
                } else {
                    try {
                        var bound = new Pp1AnalysisContext(json).prepare(prepared, candidate.candidateId(), attempt.hypothesisId(), sourcePin, criteria);
                        entry.put("bindingStatus", "READY").put("bindingIdentitySha256", hashes.requestHash(bound.path("identity")));
                        entry.set("binding", bound);
                    } catch (IllegalArgumentException unavailable) {
                        entry.put("bindingStatus", "UNAVAILABLE").put("reasonCode", unavailable.getMessage());
                    }
                }
                bindings.add(entry);
            }
        }
        try {
            if(ranked==null) {
                // Preserve the earlier exact-tree path for existing callers.
                require(rankingPin.equals(AcmgBusinessStore.sha(json.writeValueAsString(ranking))),
                        "CURRENT_SESSION_SELECTED_RANKING_MISMATCH");
            } else {
                require(ranked.stream().allMatch(Objects::nonNull)
                        && assembly.candidateIds().equals(ranked.stream().map(value->value.getCandidateId()).toList())
                        && rankingPin.equals(AcmgBusinessStore.sha(json.writeValueAsString(ranked)))
                        && ranking.equals(json.valueToTree(ranked)), "CURRENT_SESSION_SELECTED_RANKING_MISMATCH");
            }
        } catch (IOException failure) { throw invalid("CURRENT_SESSION_SELECTED_RANKING_INVALID"); }
        var identity = json.createObjectNode().put("schemaVersion", "current-acmg-evaluation-session-v1")
                .put("analysisId", analysisId).put("runId", runId).put("requestSha256", expectedRequestSha256)
                .put("evaluationId", UUID.randomUUID().toString()).put("executionSourceSha256", sourcePin)
                .put("selectedBindingsSha256", hashes.requestHash(json.valueToTree(bindings)));
        identity.set("enabledCriteria", json.valueToTree(criteria.stream().sorted().toList()));
        return new CurrentAcmgEvaluationSession(identity, bindings);
    }

    public ObjectNode identity() { return identity.deepCopy(); }
    public List<ObjectNode> attempts() { return attempts.stream().map(mappedItem -> mappedItem.deepCopy()).toList(); }
    public ObjectNode snapshot() {
        var value = identity(); value.set("attempts", identity.arrayNode().addAll(attempts())); return value;
    }
    private static boolean text(String value) { return value != null && !value.isBlank() && value.length() <= 256 && value.chars().noneMatch(Character::isISOControl); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void require(boolean value, String reason) { if (!value) throw invalid(reason); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
