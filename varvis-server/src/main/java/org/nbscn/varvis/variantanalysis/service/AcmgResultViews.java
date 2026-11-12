package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Supplier;

/** Read-only projections of an integrity-verified saved result. Never used for evaluation or export. */
public final class AcmgResultViews {
    public record VerifiedResult(ObjectNode response,java.util.function.UnaryOperator<ObjectNode> detailProjection) { }
    public static final String INDEX = "acmg-result-index-v1";
    public static final String BACKGROUND = "acmg-result-background-v1";
    private String fingerprint;
    private ObjectNode cached;
    private String cachedSha;

    /** One bounded cache entry; fingerprint checks actual stored bytes, not only declared hashes. */
    public synchronized ObjectNode read(Supplier<String> storageFingerprint, Supplier<ObjectNode> verifiedRead,
            String candidateId, int backgroundIndex, String expectedSha) {
        return readWithDetails(storageFingerprint,()->new VerifiedResult(verifiedRead.get(),java.util.function.UnaryOperator.identity()),
                candidateId,backgroundIndex,expectedSha);
    }
    public synchronized ObjectNode readWithDetails(Supplier<String> storageFingerprint,Supplier<VerifiedResult> verifiedRead,
            String candidateId,int backgroundIndex,String expectedSha){
        String before = storageFingerprint.get();
        ObjectNode full; String sha;
        if (cached != null && before.equals(fingerprint)) { full = cached; sha = cachedSha; }
        else {
            var verified = verifiedRead.get();full = verified.response();
            if (!before.equals(storageFingerprint.get())) throw new IllegalStateException("ACMG_SAVED_RESULT_CHANGED_DURING_READ");
            String serialized = full.toString();
            sha = org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(serialized);
            // Keep the immutable saved source identity; optional detail fields are
            // derived only after its fingerprint is calculated, never for export.
            full = verified.detailProjection().apply(full);
            cached = null; fingerprint = null; cachedSha = null;
            if (full.has("localBatch") && serialized.length() <= 64 * 1024 * 1024) {
                cached = full; fingerprint = before; cachedSha = sha;
            }
        }
        if (!full.has("localBatch") || !full.path("candidates").isArray()) return full.deepCopy();
        if (expectedSha != null && !expectedSha.equals(sha)) throw new IllegalStateException("ACMG_RESULT_VIEW_CHANGED");
        return candidateId == null ? index(full, sha) : background(full, sha, candidateId, backgroundIndex);
    }

    private static ObjectNode header(ObjectNode full, String sha, String kind) {
        ObjectNode out = full.objectNode();
        for (String key : new String[]{"runId","analysisId","status","createdAt","requestedN","selectedCount",
                "dataClass","executionProfile","enabledCriteria","declarationVersion","requestSha256","executionSourceSha256",
                "acceptanceScope","automaticTrigger","clinicalValidationCompleted","candidateOrderingChanged"})
            if (full.has(key)) out.set(key, full.get(key).deepCopy());
        ObjectNode batchIdentity = out.objectNode();
        for (String key : new String[]{"schemaVersion","sourceSnapshotSha256","identity",
                "fullRnaObservedSourceIdentity","fullRnaArchiveSha256","pp2OtherPhenotypeSourceIdentity","pp2OtherPhenotypeArchiveSha256",
                "pp2OtherPhenotypeDerivedArchiveSha256"})
            if (full.path("localBatch").has(key)) batchIdentity.set(key, full.path("localBatch").path(key).deepCopy());
        if (!batchIdentity.isEmpty()) out.set("localBatch", batchIdentity);
        out.put("viewKind", kind).put("sourceViewSha256", sha).put("completeExport", false);
        return out;
    }

    private static ObjectNode candidateIndex(JsonNode candidate) {
        ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        out.set("candidateId", candidate.path("candidateId"));
        for (String field : new String[]{"sampleCallSummary", "sampleCallAssociationStatus", "sampleCallSourceSnapshotSha256"})
            if (candidate.has(field)) out.set(field, candidate.path(field).deepCopy());
        if (candidate.has("rank")) out.set("rank", candidate.get("rank"));
        var attempts = out.putArray("attempts");
        for (JsonNode attempt : candidate.path("attempts")) {
            ObjectNode item = out.objectNode();
            for (String key : new String[]{"candidateId","hypothesisId","context","ba1Context","bs1Context"})
                if (attempt.has(key)) item.set(key, attempt.get(key).deepCopy());
            var binding = attempt.path("binding");
            if (attempt.path("context").isNull() && binding.isObject()) {
                var display = item.putObject("binding");
                for (String key : new String[]{"candidateId", "hypothesisId", "hgncId", "symbol", "mondoId", "diseaseLabel", "normalizedMoi"})
                    if (binding.path(key).isTextual()) display.set(key, binding.path(key));
            }
            if (attempt.path("bs1").has("context"))
                item.putObject("bs1").set("context",attempt.path("bs1").path("context").deepCopy());
            attempts.add(item);
        }
        return out;
    }

    public static ObjectNode index(ObjectNode full, String sha) {
        ObjectNode out = header(full, sha, INDEX);
        out.set("candidateIds", full.path("candidateIds").deepCopy());
        var rows = out.putArray("candidates");
        for (JsonNode candidate : full.path("candidates")) rows.add(candidateIndex(candidate));
        return out;
    }

    public static ObjectNode submissionView(ObjectNode full, String requestedView) {
        return "index".equals(requestedView) && full.has("localBatch") && full.path("candidates").isArray()
                ? index(full, org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(full.toString())) : full;
    }

    public static ObjectNode background(ObjectNode full, String sha, String id, int index) {
        JsonNode candidate = null;
        for (JsonNode row : full.path("candidates")) if (id.equals(row.path("candidateId").asText())) {
            if (candidate != null) throw new IllegalArgumentException("ACMG_CANDIDATE_IDENTITY_AMBIGUOUS");
            candidate = row;
        }
        if (candidate == null || index < 0 || index >= candidate.path("attempts").size())
            throw new IllegalArgumentException("ACMG_SAVED_BACKGROUND_NOT_FOUND");
        ObjectNode out = header(full, sha, BACKGROUND);
        out.put("candidateId", id).put("backgroundIndex", index);
        out.putArray("candidateIds").add(id);
        ObjectNode selected = candidateIndex(candidate);
        // Keep original positions: BA1 selections and other saved JSON pointers use attempt indices.
        ((com.fasterxml.jackson.databind.node.ArrayNode) selected.path("attempts")).set(index, candidate.path("attempts").get(index).deepCopy());
        for (String key : new String[]{"ba1","ba1ResultSelection","enabledRuleResultsComplete"})
            if (candidate.has(key)) selected.set(key, candidate.get(key).deepCopy());
        out.putArray("candidates").add(selected);
        return out;
    }
}
