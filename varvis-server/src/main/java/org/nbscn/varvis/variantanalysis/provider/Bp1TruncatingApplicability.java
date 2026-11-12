package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A source-subtype applicability gate for the existing verified-missense path.
 * It cannot award BP1 or qualify LoF, canonical splice, start loss or disease-mechanism absence.
 */
final class Bp1TruncatingApplicability {
    private Bp1TruncatingApplicability() { }

    static ObjectNode forVerifiedMissense(ObjectMapper mapper, ObjectNode selection, ObjectNode context,
            CodingTranscriptMaterialSupply.Material material) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp1-truncating-applicability-v1")
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("clinicalValidationCompleted", false)
                .put("reasonCode", "BP1_TRUNCATING_VERIFIED_MISSENSE_MATERIAL_REQUIRED");
        out.putObject("required").put("variantEffect", "TRUNCATING_WITH_LOSS_OF_FUNCTION")
                .put("diseaseMechanism", "GAIN_OF_FUNCTION").put("geneLofDiseaseCorrelation", "NO_ESTABLISHED_CORRELATION")
                .put("germlineOnly", true).put("noRecordMeansNoCorrelation", false);
        try {
            Bp1PublishedPolicy.validateTruncatingSource(selection);
            var model = material.model(); var audit = material.snapshot(); var association = audit.path("alleleAssociation");
            if (!material.ready() || model == null || !"EXACT_MODEL_REFERENCE_AND_VEP_AGREE".equals(audit.path("reasonCode").asText())) return out;
            var adapter = new RuntimeEvidenceV2Adapter(mapper); adapter.validateCompleteContext(context);
            Bp1PublishedPolicy.require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()), "BP1_TRUNCATING_CONTEXT_MISMATCH");
            for (String[] binding : new String[][] { {"hgncId", "/gene/hgncId"}, {"mondoId", "/disease/mondoId"},
                    {"moi", "/moi"}, {"assembly", "/variant/evidenceQueryAllele/assembly"} })
                Bp1PublishedPolicy.require(selection.path("record").path("scope").path(binding[0]).equals(context.at(binding[1])), "BP1_TRUNCATING_SOURCE_CONTEXT_MISMATCH");
            Bp1PublishedPolicy.require(adapter.canonicalSha256(model).equals(association.path("nativeModelSha256").asText())
                    && context.at("/variant/evidenceQueryAllele").equals(association.path("allele")), "BP1_TRUNCATING_MATERIAL_CONTEXT_MISMATCH");
            String reference = association.path("refAminoAcid").asText(), alternate = association.path("altAminoAcid").asText();
            Bp1PublishedPolicy.require(reference.matches("[ACDEFGHIKLMNPQRSTVWY]") && alternate.matches("[ACDEFGHIKLMNPQRSTVWY]")
                    && !reference.equals(alternate), "BP1_TRUNCATING_VERIFIED_MISSENSE_MATERIAL_REQUIRED");
            out.putObject("available").put("variantEffect", "VERIFIED_PURE_CODING_MISSENSE")
                    .put("modelCanonicalSha256", adapter.canonicalSha256(model)).put("materialSha256", adapter.canonicalSha256(audit));
            out.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
            out.set("sourceClause", selection.path("record").path("clauseSha256").deepCopy());
            return out.put("status", "NOT_APPLICABLE").put("reasonCode", "BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE");
        } catch (IllegalArgumentException invalid) {
            return out.put("reasonCode", invalid.getMessage());
        }
    }
}
