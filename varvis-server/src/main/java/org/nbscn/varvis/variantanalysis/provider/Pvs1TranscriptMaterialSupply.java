package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Technical projection for the existing NMD resolver. Never creates a clinical policy or selection receipt. */
public final class Pvs1TranscriptMaterialSupply {
    public static final String VERSION = "1.0.0";
    public static final String BOUND_VERSION = "1.3.0";
    public record Material(ObjectNode audit, @Nullable ObjectNode model) {
        public Material { audit = audit.deepCopy(); model = model == null ? null : model.deepCopy(); }
        @Override public ObjectNode audit() { return audit.deepCopy(); }
        @Override @Nullable public ObjectNode model() { var snapshot = model;return snapshot == null ? null : snapshot.deepCopy(); }
        public boolean ready() { return model != null; }
    }
    private final CodingTranscriptMaterialSupply supply;
    private final RuntimeEvidenceV2Adapter adapter;

    public Pvs1TranscriptMaterialSupply(ObjectMapper mapper, CodingTranscriptMaterialSupply supply) {
        this.supply = java.util.Objects.requireNonNull(supply);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
    }

    public ObjectNode identity() {
        var identity = supply.structureIdentity().objectNode().put("method", "PVS1_NMD_TRANSCRIPT_MATERIAL_SUPPLY")
                .put("version", VERSION).put("clinicalAcceptance", false).put("networkAllowed", false);
        identity.set("structureSupply", supply.structureIdentity());
        return identity;
    }

    /** New source-qualified path. Legacy bundles keep their original identity and behavior. */
    public ObjectNode boundIdentity() {
        return identity().put("version", BOUND_VERSION).put("bindingMethod", "EXACT_FULL_TRANSCRIPT_EQUIVALENCE")
                .put("currentReferenceRequired", true).put("transcriptVersionBridging", false);
    }

    /** The source-reviewed model remains the scientific anchor, not a required technical file format.
     * Verify current local sequence and every exon before reusing that unchanged anchor. A matching
     * CDS alone is insufficient for NMD: UTR sequence, exon junctions and strand must also agree.
     */
    public Material resolveBound(ObjectNode context, VariantInterpretation candidate, ObjectNode boundModel) {
        var audit = boundIdentity().put("status", "UNAVAILABLE");
        String issue = TruncatingPvs1Model.issue(boundModel, context);
        if (issue != null) return unavailable(audit, issue);
        var supplied = resolve(context, candidate, true);
        audit.set("structure", supplied.audit().path("structure").deepCopy());
        ObjectNode technical = supplied.model();
        if (technical == null) return unavailable(audit, supplied.audit().path("reasonCode").asText());
        audit.put("suppliedModelCanonicalSha256", adapter.canonicalSha256(technical))
                .put("boundModelCanonicalSha256", adapter.canonicalSha256(boundModel));
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein", "strand", "cdsLength", "proteinLength"))
            if (!boundModel.path(field).equals(technical.path(field)))
                return unavailable(audit.put("mismatchedField", field), "PVS1_SUPPLIED_TRANSCRIPT_IDENTITY_MISMATCH");
        for (String field : List.of("cdsSha256", "fullSplicedTranscriptSha256")) {
            String expected = boundModel.path("sequenceVerification").path(field).asText();
            if (!expected.matches("[0-9a-f]{64}"))
                return unavailable(audit, "PVS1_BOUND_FULL_TRANSCRIPT_PROOF_MISSING");
            if (!expected.equals(technical.path("sequenceVerification").path(field).asText()))
                return unavailable(audit.put("mismatchedField", field), "PVS1_SUPPLIED_TRANSCRIPT_SEQUENCE_MISMATCH");
        }
        if (!boundModel.path("cdsSequence").equals(technical.path("cdsSequence")))
            return unavailable(audit, "PVS1_SUPPLIED_TRANSCRIPT_SEQUENCE_MISMATCH");
        JsonNode exons = boundModel.path("exons"), actual = technical.path("exons");
        if (exons.size() != actual.size()) return unavailable(audit, "PVS1_SUPPLIED_TRANSCRIPT_STRUCTURE_MISMATCH");
        for (int i = 0; i < exons.size(); i++)
            for (String field : List.of("ordinal", "genomicStart", "genomicEnd", "cdsStart", "cdsEnd"))
                if (!exons.path(i).path(field).equals(actual.path(i).path(field)))
                    return unavailable(audit.put("mismatchedExon", i + 1).put("mismatchedField", field),
                            "PVS1_SUPPLIED_TRANSCRIPT_STRUCTURE_MISMATCH");
        if (boundModel.path("exons").size() != 1 && !boundModel.path("lastJunctionCdsPosition").equals(technical.path("lastJunctionCdsPosition")))
            return unavailable(audit, "PVS1_SUPPLIED_TRANSCRIPT_STRUCTURE_MISMATCH");
        audit.put("status", "READY").put("reasonCode", "PVS1_FULL_TRANSCRIPT_SEQUENCE_AND_STRUCTURE_VERIFIED")
                .put("policyModelUnchanged", true).put("alleleEffectVerified", false)
                .put("verifiedExonCount", exons.size());
        // Do not transfer technical source labels into the reviewed model or synthesize a new review.
        return new Material(audit, boundModel);
    }

    /** Opaque full reference RNA; this grants no reviewed PVS1 policy, outcome, phase or function. */
    public CodingTranscriptMaterialSupply.Material resolveRna(ObjectNode context, VariantInterpretation candidate) {
        return supply.resolveRna(context, candidate);
    }

    String spliceReferenceIssue(JsonNode context, JsonNode window, ObjectNode audit) {
        return supply.spliceReferenceIssue(context, window, audit);
    }

    public Material resolve(ObjectNode context, VariantInterpretation candidate) {
        return resolve(context, candidate, false);
    }

    private Material resolve(ObjectNode context, VariantInterpretation candidate, boolean allowExtendedTopology) {
        var material = supply.resolveStructure(context, candidate);
        var audit = identity().put("status", "UNAVAILABLE");
        audit.set("structure", material.snapshot());
        ObjectNode model = material.model();
        if (model == null) return unavailable(audit, material.snapshot().path("reasonCode").asText());
        var exons = model.path("exons");
        boolean single = exons.size() == 1;
        if (single && !allowExtendedTopology) return unavailable(audit, "PVS1_SINGLE_EXON_NMD_SCOPE_NOT_IMPLEMENTED");
        int junction = single ? 0 : exons.path(exons.size() - 2).path("cdsEnd").asInt();
        if (!single && (junction < 1 || !allowExtendedTopology && junction >= model.path("cdsLength").asInt() - 3))
            return unavailable(audit, "PVS1_NMD_JUNCTION_OUTSIDE_IMPLEMENTED_SCOPE");
        if (!model.has("lastJunctionCdsPosition")) {
            String originalSha = adapter.canonicalSha256(model);
            model.putObject("technicalModelBinding").put("modelId", model.path("modelId").asText())
                    .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", originalSha);
            model.put("modelId", model.path("modelId").asText() + "_PVS1_NMD_STRUCTURE")
                    .put("modelVersion", allowExtendedTopology ? BOUND_VERSION : VERSION)
                    .put("usageScope", "PVS1_NMD_TECHNICAL_STRUCTURE_ONLY");
            if (single) model.putNull("lastJunctionCdsPosition");
            else model.put("lastJunctionCdsPosition", junction);
        }
        String issue = TruncatingPvs1Model.issue(model, context);
        if (issue != null) return unavailable(audit, issue);
        audit.put("status", "READY").put("reasonCode", "PVS1_TECHNICAL_MODEL_READY_REVIEWED_POLICY_REQUIRED")
                .put("modelCanonicalSha256", adapter.canonicalSha256(model)).put("alleleEffectVerified", false);
        return new Material(audit, model);
    }

    private static Material unavailable(ObjectNode audit, String reason) {
        return new Material(audit.put("reasonCode", reason), null);
    }
}
