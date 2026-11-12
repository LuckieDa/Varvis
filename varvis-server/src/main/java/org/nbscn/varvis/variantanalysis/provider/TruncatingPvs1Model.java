package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;

/** Adds only the implemented NMD model boundary to the shared native coding-model checks. */
final class TruncatingPvs1Model {
    static final String TOPOLOGY_VERSION = "1.5.0";
    private TruncatingPvs1Model() { }

    static boolean extendedTopology(JsonNode model) {
        var exons = model.path("exons");
        return exons.isArray() && (exons.size() == 1 || exons.size() > 1
                && exons.path(exons.size() - 2).path("cdsEnd").asInt() >= model.path("cdsLength").asInt());
    }

    static void describeTopology(JsonNode model, com.fasterxml.jackson.databind.node.ObjectNode input) {
        if (!extendedTopology(model)) return;
        boolean single = model.path("exons").size() == 1;
        var t = input.putObject("transcriptTopology").put("version", TOPOLOGY_VERSION)
                .put("kind", single ? "SINGLE_EXON" : "LAST_JUNCTION_IN_3_PRIME_UTR")
                .put("exonCount", model.path("exons").size()).put("cdsLength", model.path("cdsLength").asInt())
                .put("fullSplicedTranscriptSha256", model.at("/sequenceVerification/fullSplicedTranscriptSha256").asText());
        if (single) t.putNull("referenceLastJunctionCdsPosition");
        else t.set("referenceLastJunctionCdsPosition", model.path("lastJunctionCdsPosition").deepCopy());
    }

    static String issue(JsonNode model, JsonNode context) {
        String common = CodingTranscriptMapping.modelIssue(model, context);
        if (common != null) return common.replace("CODING_TRANSCRIPT_MODEL_", "PVS1_TRANSCRIPT_MODEL_");
        JsonNode exons = model.path("exons");
        if (extendedTopology(model)) {
            if (!model.at("/sequenceVerification/fullSplicedTranscriptSha256").asText().matches("[0-9a-f]{64}")
                    || !CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText())
                            .equals(model.at("/sequenceVerification/cdsSha256").asText()))
                return "PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING";
            if (exons.size() == 1) return model.path("lastJunctionCdsPosition").isMissingNode()
                    || model.path("lastJunctionCdsPosition").isNull() ? null : "PVS1_SINGLE_EXON_JUNCTION_CONFLICT";
        }
        // A native STOP may begin immediately after, or span, this junction.
        // The shared model check already validates its complete three-base
        // sequence. NMD is evaluated from the actual premature STOP later;
        // reference STOP proximity is not a model-invalidating condition.
        int junction = exons.path(exons.size() - 2).path("cdsEnd").asInt();
        if (!model.path("lastJunctionCdsPosition").isIntegralNumber()
                || !model.path("lastJunctionCdsPosition").canConvertToInt()
                || model.path("lastJunctionCdsPosition").asInt() != junction || junction < 1)
            return "PVS1_TRANSCRIPT_MODEL_JUNCTION_INVALID";
        return null;
    }

    static boolean sourcesValid(JsonNode sources) { return CodingTranscriptMapping.sourcesValid(sources); }
    static boolean text(JsonNode node) { return CodingTranscriptMapping.text(node); }
}
