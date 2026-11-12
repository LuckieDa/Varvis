package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;

/** A lossless, explicitly bound view for existing v2 evaluators. It grants no source or scientific approval. */
public final class LegacyContextProjection {
    public static final String CODING_SEQUENCE_VERSION = "1.0.0";
    private final SelectedEvidencePreparation owner;
    private final String candidateId;
    private final String hypothesisId;
    private final ObjectNode context;
    private final ObjectNode snapshot;
    private final boolean sequenceVerificationRequired;

    LegacyContextProjection(ObjectMapper mapper, SelectedEvidencePreparation owner, String candidateId, String hypothesisId) {
        this(mapper, owner, candidateId, hypothesisId, false);
    }

    LegacyContextProjection(ObjectMapper mapper, SelectedEvidencePreparation owner, String candidateId, String hypothesisId,
            boolean sequenceVerificationRequired) {
        this.sequenceVerificationRequired = sequenceVerificationRequired;
        this.owner = owner;
        this.candidateId = candidateId;
        this.hypothesisId = hypothesisId;
        var original = owner.context(candidateId, hypothesisId);
        context = projectContext(mapper, original);
        // A compatible schema alone cannot establish annotation correspondence after normalization.
        if (sequenceVerificationRequired) owner.codingSequenceCandidate(candidateId, hypothesisId);
        else owner.queryCandidate(candidateId, hypothesisId, true);
        snapshot = mapper.createObjectNode().put("schemaVersion", "selected-legacy-context-projection-v1")
                .put("candidateId", candidateId).put("hypothesisId", hypothesisId)
                .put("projectionMethod", "VALIDATED_TRANSCRIPT_BOUND_V3_TO_V2")
                .put("applicationApproval", "NOT_GRANTED");
        if (sequenceVerificationRequired) {
            snapshot.put("projectionMethod", "CODING_SEQUENCE_COMPARISON_REQUIRED_V3_TO_V2")
                    .put("projectionVersion", CODING_SEQUENCE_VERSION)
                    .put("annotationCorrespondence", "PENDING_COMPLETE_CDS_AND_PROTEIN_COMPARISON");
            snapshot.set("originalAnnotationAllele", owner.formalCandidate(candidateId).at("/observation/key").deepCopy());
            // Annotations may contain binary prediction scores; bind their serialized snapshot
            // without treating those scores as canonical scientific identity values.
            snapshot.put("originalTranscriptAnnotationsJsonSha256", annotationSha(owner.formalCandidate(candidateId)))
                    .put("annotationHashMethod", "ORIGINAL_JSON_NODE_UTF8_SHA256");
        }
        snapshot.set("originalContext", original);
        var annotationSource = owner.queryAnnotationSource(candidateId, hypothesisId);
        if (annotationSource != null && !sequenceVerificationRequired)
            snapshot.set("queryAnnotationSource", mapper.valueToTree(annotationSource));
        snapshot.set("evaluationContext", context.deepCopy());
        snapshot.set("preparationProvenance", owner.provenance());
        snapshot.put("bindingSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(snapshot));
    }

    static ObjectNode projectContext(ObjectMapper mapper, ObjectNode original) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        if (!"evidence-context-v3".equals(original.path("schemaVersion").asText()))
            throw new IllegalArgumentException("LEGACY_PROJECTION_REQUIRES_SELECTED_V3_CONTEXT");
        adapter.validateVersionedContext(original);
        if (!original.path("transcript").isObject())
            throw new IllegalArgumentException("LEGACY_PROJECTION_TRANSCRIPT_REQUIRED");
        var projected = original.deepCopy();
        projected.put("schemaVersion", "evidence-context-v2");
        projected.put("contextId", adapter.canonicalContextId(projected));
        adapter.validateCompleteContext(projected);
        return projected;
    }

    public ObjectNode context() { return context.deepCopy(); }
    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String bindingSha256() { return snapshot.path("bindingSha256").asText(); }
    public VariantInterpretation queryCandidate() { return sequenceVerificationRequired
            ? owner.codingSequenceCandidate(candidateId, hypothesisId) : owner.queryCandidate(candidateId, hypothesisId, true); }

    private static String annotationSha(ObjectNode candidate) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(candidate.path("transcriptAnnotations").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
