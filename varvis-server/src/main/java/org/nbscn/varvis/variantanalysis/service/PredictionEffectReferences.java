package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;

/** Read-only adapter for the existing local functional adoption store. No new evidence or adoption.
 * Coverage is the declared local store, not an assertion about external experiments. */
public final class PredictionEffectReferences {
    private final ObjectMapper mapper;
    private final AcmgBusinessStore store;
    private final Path reviewRoot;
    private final BiFunction<String, String, ObjectNode> savedRead;
    public PredictionEffectReferences(ObjectMapper mapper, AcmgBusinessStore store, Path reviewRoot,
                                      BiFunction<String, String, ObjectNode> savedRead) {
        this.mapper = mapper; this.store = store; this.reviewRoot = reviewRoot; this.savedRead = savedRead;
    }
    public Snapshot capture(String analysisId, Set<String> candidates) {
        var first = store.withAnalysisLock(analysisId, () -> captureLocked(analysisId, candidates));
        // Retry only a classified transient read, in a fresh transaction. Never retry or
        // downgrade corrupt digests, missing sources or ambiguous adoption records.
        return "EFFECT_SOURCE_READ_TEMPORARY_FAILURE".equals(first.body.path("reasonCode").asText())
                ? store.withAnalysisLock(analysisId, () -> captureLocked(analysisId, candidates)) : first;
    }
    private Snapshot captureLocked(String analysisId, Set<String> candidates) {
        var body = mapper.createObjectNode().put("schemaVersion", "prediction-effect-snapshot-v1")
                .put("analysisId", analysisId).put("coverage", "COMPLETE_FOR_DECLARED_SCOPE")
                .put("sourceStore", "ACMG_BUSINESS_RUNS_AND_FUNCTIONAL_EXPERIMENT_REVIEWS")
                .put("captureMethod", "FUNCTIONAL_RUN_SCOPED_REVIEWS_V3")
                .put("externalDeclaration", "UNSPECIFIED").put("externalWorldCovered", false);
        body.set("candidateIds", mapper.valueToTree(new TreeSet<>(candidates))); var entries = body.putArray("entries");
        String stage = "HISTORY_INDEX";
        try {
            var runs = store.effectScopeRuns(analysisId);
            if (runs.size() > 1000) throw new IllegalStateException("EFFECT_SCOPE_RUN_LIMIT_EXCEEDED");
            var reviews = new FunctionalExperimentReviews(mapper, reviewRoot, savedRead, (a, action) -> action.get());
            long totalBytes = 0;
            for (var indexed : runs) {
                var run = indexed;
                stage = "REQUEST_INTEGRITY";
                // Prediction/population runs cannot be functional adoption subjects. Excluding them
                // prevents the new prediction run itself from invalidating every repeated POST.
                JsonNode request = mapper.readTree(run.requestJson());
                if (!new AcmgBusinessArtifacts(mapper).requestHash(request).equals(run.requestSha256())) throw new IllegalStateException("EFFECT_REQUEST_INTEGRITY_FAILURE");
                var enabled = request.at("/executionProfile/enabledCriteria");
                if (enabled.isArray() && !contains(enabled, "ACMG_AMP_2015:PS3") && !contains(enabled, "ACMG_AMP_2015:BS3")) continue;
                stage = "FUNCTIONAL_SOURCE_READ";
                // Only a functional run can have an adoption review. An empty, successfully
                // inventoried scope needs no directory; a missing store for an actual or
                // legacy/unknown functional run must still block reuse.
                if (!Files.isDirectory(reviewRoot) || !Files.isReadable(reviewRoot)) throw new IllegalStateException("EFFECT_REVIEW_STORE_UNAVAILABLE");
                run = store.run(analysisId, run.id()).orElseThrow();
                if (run.responseJson() == null) throw new IllegalStateException("EFFECT_LINKED_RUN_NOT_READABLE");
                if (run.responseJson().length() > 8 * 1024 * 1024) throw new IllegalStateException("EFFECT_LINKED_RUN_SIZE_LIMIT");
                totalBytes += run.responseJson().length();
                if (totalBytes > 32 * 1024 * 1024) throw new IllegalStateException("EFFECT_SCOPE_SIZE_LIMIT");
                if (!AcmgBusinessStore.sha(run.responseJson()).equals(run.responseSha256())) throw new IllegalStateException("EFFECT_RESPONSE_INTEGRITY_FAILURE");
                var saved = savedRead.apply(analysisId, run.id()); // Existing request/response/private digest validation.
                var reviewRecords = reviews.read(analysisId, run.id()).path("records");
                for (var candidate : saved.path("candidates")) if (candidates.contains(candidate.path("candidateId").asText()))
                    for (var attempt : candidate.path("attempts")) {
                        if (!attempt.path("declaredEvidenceReferences").isMissingNode() && !attempt.path("declaredEvidenceReferences").isEmpty()) {
                            body.put("externalDeclaration", "REFERENCED");
                            throw new IllegalStateException("EFFECT_DECLARED_REFERENCES_UNRESOLVED");
                        }
                        var f = attempt.path("functional"); if (!f.isObject()) continue;
                        if (!f.path("atomicEvidence").isArray() || !f.path("evaluation").isObject()) {
                            if ("MET".equals(f.at("/evaluation/evaluationResult/status").asText())) throw new IllegalStateException("EFFECT_SOURCE_LINKS_MISSING");
                            continue; // An explicit unevaluated disposition has no observation or adoption.
                        }
                        var entry = entries.addObject().put("runId", run.id()).put("runSha256", run.responseSha256())
                                .put("candidateId", candidate.path("candidateId").asText());
                        entry.set("functional", f.deepCopy());
                        var linked = entry.putArray("reviews");
                        for (var r : reviewRecords) if (f.at("/evaluation/evaluationId").equals(r.at("/reviewRecord/subjectId"))) linked.add(r.deepCopy());
                    }
            }
        } catch (Exception unavailable) {
            body.put("coverage", "UNAVAILABLE").put("failureStage", stage);
            String reason = unavailable.getMessage();
            body.put("reasonCode", unavailable instanceof org.springframework.dao.TransientDataAccessException
                    || unavailable instanceof org.springframework.dao.RecoverableDataAccessException ? "EFFECT_SOURCE_READ_TEMPORARY_FAILURE"
                    : reason != null && reason.matches("EFFECT_[A-Z_]+") ? reason : "EFFECT_SOURCE_INTEGRITY_OR_READ_FAILURE");
        }
        // The stable digest deliberately excludes read time; entries include all source/review revisions.
        String sha = new AcmgBusinessArtifacts(mapper).requestHash(body);
        body.put("snapshotSha256", sha).put("readAt", Instant.now().toString());
        return new Snapshot(body);
    }
    public final class Snapshot {
        private final ObjectNode body;
        private Snapshot(ObjectNode body) { this.body = body.deepCopy(); }
        public String identity() { return body.path("snapshotSha256").asText(); }
        public ObjectNode readiness() {
            var out = mapper.createObjectNode().put("dependency", "SAVED_FUNCTIONAL_REFERENCES")
                    .put("status", "COMPLETE_FOR_DECLARED_SCOPE".equals(body.path("coverage").asText()) ? "READY" : "UNAVAILABLE")
                    .put("sourceSha256", identity()).put("coverage", body.path("coverage").asText());
            for (String field : List.of("reasonCode", "failureStage")) if (body.has(field)) out.set(field, body.path(field));
            return out;
        }
        public ObjectNode resolve(JsonNode plan) {
            var out = mapper.createObjectNode().put("schemaVersion", "prediction-effect-references-v1")
                    .put("status", "UNAVAILABLE").put("coverage", body.path("coverage").asText()).put("restriction", "UNKNOWN")
                    .put("externalDeclaration", body.path("externalDeclaration").asText()).put("externalWorldCovered", false)
                    .put("sourceSha256", identity()).put("contextId", plan.at("/context/contextId").asText());
            var scope = out.putObject("scope").put("analysisId", body.path("analysisId").asText()).put("candidateId", plan.path("candidateId").asText())
                    .put("sourceStore", body.path("sourceStore").asText()).put("mechanisms", "PROTEIN_MISSENSE,SPLICING");
            scope.set("queryAllele", plan.path("queryAllele")); scope.set("contextId", plan.at("/context/contextId"));
            out.putObject("snapshotIdentity").put("sha256", identity()).put("readAt", body.path("readAt").asText());
            var refs = out.putArray("references");
            try {
                if (!"COMPLETE_FOR_DECLARED_SCOPE".equals(body.path("coverage").asText())) throw new IllegalStateException(body.path("reasonCode").asText());
                if (!contains(body.path("candidateIds"), plan.path("candidateId").asText())) throw new IllegalStateException("EFFECT_CANDIDATE_OUTSIDE_SCOPE");
                var context = plan.path("context"); new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(context);
                if (!context.at("/variant/evidenceQueryAllele").equals(plan.path("queryAllele")))
                    throw new IllegalStateException("EFFECT_QUERY_ALLELE_MISMATCH");
                for (var entry : body.path("entries")) {
                    if (!entry.path("candidateId").equals(plan.path("candidateId"))) continue;
                    var f = entry.path("functional"); var fc = f.path("context");
                    new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(fc);
                    if (!fc.path("contextId").equals(context.path("contextId"))) continue;
                    if (!fc.at("/variant/evidenceQueryAllele").equals(context.at("/variant/evidenceQueryAllele"))) throw new IllegalStateException("EFFECT_ALLELE_MISMATCH");
                    JsonNode head = null; var predecessors = new HashSet<String>();
                    for (var r : entry.path("reviews")) predecessors.add(r.at("/reviewRecord/previousReviewRecordId").asText());
                    for (var r : entry.path("reviews")) if (!predecessors.contains(r.at("/reviewRecord/reviewRecordId").asText())) {
                        if (head != null) throw new IllegalStateException("EFFECT_REVIEW_CHAIN_AMBIGUOUS"); head = r;
                    }
                    if (head == null && "ACCEPTED".equals(f.at("/evaluation/review/decision").asText())) throw new IllegalStateException("EFFECT_ADOPTION_LINK_UNRESOLVED");
                    boolean adopted = head != null && "ACCEPTED".equals(head.at("/reviewRecord/decision").asText());
                    if (!(f.path("evaluation") instanceof ObjectNode evaluation)) throw new IllegalStateException("EFFECT_EVALUATION_MISSING");
                    var projection = evaluation.deepCopy(); projection.remove(List.of("evaluationId", "review", "conflict"));
                    if (!("evaluation-sha256-" + new AcmgBusinessArtifacts(mapper).requestHash(projection)).equals(evaluation.path("evaluationId").asText()))
                        throw new IllegalStateException("EFFECT_EVALUATION_IDENTITY_INVALID");
                    var used = f.at("/evaluation/evidenceIds");
                    for (var id : used) {
                        JsonNode atomic = null;
                        for (var a : f.path("atomicEvidence")) if (id.equals(a.path("evidenceId"))) {
                            if (atomic != null) throw new IllegalStateException("EFFECT_ATOMIC_LINK_AMBIGUOUS"); atomic = a;
                        }
                        if (atomic == null || !atomic.path("contextId").equals(context.path("contextId"))
                                || !id.asText().equals("evidence-sha256-" + atomic.at("/provenance/identityProjectionSha256").asText()))
                            throw new IllegalStateException("EFFECT_ATOMIC_LINK_INVALID");
                        if (!(atomic instanceof ObjectNode atomicObject) || !new RuntimeEvidenceV2Adapter(mapper).savedAtomicIdentityMatches(atomicObject)
                                || !"FUNCTIONAL_STUDY".equals(atomic.path("evidenceType").asText())) throw new IllegalStateException("EFFECT_ATOMIC_IDENTITY_INVALID");
                        String mechanism = null;
                        // Generic measurement semantics, independent of gene or a prediction score.
                        for (var metric : atomic.path("measurements")) if ("transactivation.publishedRange".equals(metric.path("metricId").asText())) mechanism = "PROTEIN_MISSENSE";
                        if (adopted && (mechanism == null || atomic.path("measurements").size() != 1 || atomic.path("correlationGroupId").asText().isBlank())) throw new IllegalStateException("EFFECT_MECHANISM_LINK_UNKNOWN");
                        var ref = refs.addObject().put("sourceRunId", entry.path("runId").asText()).put("sourceRunSha256", entry.path("runSha256").asText())
                                .put("evaluationId", f.at("/evaluation/evaluationId").asText()).put("savedEvidenceId", id.asText())
                                .put("effectId", atomic.path("correlationGroupId").asText()).put("mechanism", mechanism == null ? "UNKNOWN" : mechanism)
                                .put("criterionId", "ACMG_AMP_2015:BS3".equals(f.path("criterionId").asText()) ? "ACMG_AMP_2015:BP4" : "ACMG_AMP_2015:PP3")
                                .put("usage", adopted ? "EXPLICITLY_ADOPTED" : "OBSERVED_NOT_ADOPTED").put("action", adopted ? "BLOCK_DUPLICATE" : "ALLOW");
                        if (head != null) ref.put("reviewSnapshotSha256", head.path("snapshotSha256").asText())
                                .put("reviewRecordId", head.at("/reviewRecord/reviewRecordId").asText())
                                .put("actorIdentityAssurance", head.path("actorIdentityAssurance").asText());
                    }
                    if (adopted && used.isEmpty()) throw new IllegalStateException("EFFECT_ADOPTED_EVIDENCE_LINK_MISSING");
                }
                boolean found = false; for (var ref : refs) found |= "BLOCK_DUPLICATE".equals(ref.path("action").asText());
                out.put("status", "VERIFIED_SAVED_REFERENCES").put("restriction", found ? "RESTRICTION_FOUND" : "NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE");
            } catch (Exception invalid) {
                String reason = invalid.getMessage();
                out.put("coverage", "INCOMPLETE").put("reasonCode", reason != null && reason.matches("EFFECT_[A-Z_]+") ? reason : "EFFECT_CONTEXT_OR_LINK_INVALID");
                out.put("failureStage", body.path("failureStage").asText("CONTEXT_AND_REFERENCE_BINDING"));
            }
            return out;
        }
    }
    private static boolean contains(JsonNode array, String value) { for (var item : array) if (value.equals(item.asText())) return true; return false; }
}
