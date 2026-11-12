package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Pattern;

/** Replays a bounded original curator statement against the reference-verified
 * CDS. Establishes a non-start-loss truncation mechanism, not pathogenicity,
 * NMD, the target's PVS1, or completeness of upstream reference qualification. */
final class Pvs1StartLossTruncatingGround {
    private static final Pattern CLAIM = Pattern.compile("^This (nonsense|frameshift) mutation causes a truncation 5[\u2032\u2019'] to c\\.([1-9][0-9]*) in a biologically-relevant transcript \\(((?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*)\\)\\.$");
    private static final Pattern NMD_CLAIM = Pattern.compile("^The ((?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*):c\\.([A-Za-z0-9_>]+)(?: \\((p\\.[A-Za-z0-9*]+)\\))? variant in ([A-Za-z0-9_.-]+) is a (nonsense|frameshift) variant(?: variant)? predicted to cause a premature stop codon in biologically-relevant-exon ([1-9][0-9]*)/([1-9][0-9]*),? leading to nonsense mediated decay in a gene in which loss-of-function is an established disease mechanism \\(PVS1\\)\\.$");
    private Pvs1StartLossTruncatingGround() { }

    static ObjectNode assess(ObjectMapper mapper, String claim, JsonNode summary, JsonNode model) {
        var out = mapper.createObjectNode().put("status", "UNRESOLVED").put("independenceEstablished", false)
                .put("reasonCode", "CURATED_TRUNCATING_MECHANISM_NOT_SUPPORTED");
        try {
            var text = CLAIM.matcher(claim); var nmd = NMD_CLAIM.matcher(claim);
            boolean cutoffClaim = text.matches(), nmdClaim = nmd.matches();
            if (!cutoffClaim && !nmdClaim) return out;
            String sourceTranscript = cutoffClaim ? text.group(3) : nmd.group(1);
            String mutationType = cutoffClaim ? text.group(1) : nmd.group(5);
            JsonNode sourceModel = model;
            if (!sourceTranscript.equals(model.path("transcript").asText())) {
                require(PublishedCriterionKnowledge.exactBridge(sourceTranscript, model), "CURATED_TRUNCATING_TRANSCRIPT_MISMATCH");
                var bridge = ((ObjectNode) model).deepCopy(); bridge.put("transcript", sourceTranscript); sourceModel = bridge;
            }
            String cds = model.path("cdsSequence").asText();
            require(cds.matches("[ACGT]+") && cds.length() >= 9 && cds.length() % 3 == 0 && cds.startsWith("ATG"), "CURATED_REFERENCE_CDS_UNAVAILABLE");
            for (int i = 0; i < cds.length(); i += 3)
                require((TruncatingSequence.translateCodon(cds.substring(i, i + 3)) == '*') == (i == cds.length() - 3), "CURATED_REFERENCE_CDS_INVALID");
            int cutoff = cutoffClaim ? Integer.parseInt(text.group(2)) : cds.length();
            require(cutoff > 3 && cutoff <= cds.length(), "CURATED_TRUNCATION_BOUNDARY_INVALID");
            String prefix = sourceTranscript + ":c.";
            Pvs1StartLossReferenceMapping.Result mapping = null;
            for (var description : summary.path("hgvs")) {
                if (!description.isTextual() || !description.asText().startsWith(prefix)) continue;
                var candidate = Pvs1StartLossReferenceMapping.map(sourceModel, description.asText());
                require(candidate.mapped(), candidate.reason());
                require(mapping == null || mapping.key().equals(candidate.key()), "CURATED_TRUNCATING_ALLELE_CONFLICT");
                mapping = candidate;
            }
            require(mapping != null, "CURATED_TRUNCATING_ALLELE_UNRESOLVED");
            mapping = java.util.Objects.requireNonNull(mapping);
            if (nmdClaim) {
                require(nmd.group(4).equals(summary.path("gene").asText()) && nmd.group(4).equals(model.path("geneSymbol").asText()), "CURATED_TRUNCATING_GENE_MISMATCH");
                var described = Pvs1StartLossReferenceMapping.map(sourceModel, prefix + nmd.group(2));
                require(described.mapped() && mapping.key().equals(described.key()), "CURATED_TRUNCATING_ALLELE_CONFLICT");
                int ordinal = Integer.parseInt(nmd.group(6)), count = Integer.parseInt(nmd.group(7));
                var exon = model.path("exons").path(ordinal - 1);
                require(count == model.path("exons").size() && exon.path("ordinal").asInt() == ordinal
                        && mapping.first() >= exon.path("cdsStart").asInt() && mapping.last() <= exon.path("cdsEnd").asInt(),
                        "CURATED_TRUNCATING_EXON_MISMATCH");
            }
            require(mapping.first() > 3 && mapping.exonCdsStart() > 0 && mapping.exonCdsEnd() <= cds.length(), "CURATED_TRUNCATION_AFFECTS_START");
            String altered = cds.substring(0, mapping.exonCdsStart() - 1) + mapping.alteredCodingExon() + cds.substring(mapping.exonCdsEnd());
            require(altered.startsWith("ATG"), "CURATED_TRUNCATION_AFFECTS_START");
            boolean frameshift = Math.floorMod(altered.length() - cds.length(), 3) != 0;
            require(frameshift == "frameshift".equals(mutationType), "CURATED_TRUNCATION_TYPE_MISMATCH");
            // An in-frame deletion moves the original natural stop to an earlier
            // altered coordinate without creating a premature stop. Supported
            // nonsense claims therefore require a length-preserving edit; other
            // in-frame length changes need a separate translated-coordinate proof.
            if (!frameshift) require(altered.length() == cds.length(), "CURATED_NONSENSE_LENGTH_CHANGE_UNSUPPORTED");
            int stop = 0;
            for (int i = 3; i + 3 <= altered.length(); i += 3)
                if (TruncatingSequence.translateCodon(altered.substring(i, i + 3)) == '*') { stop = i + 1; break; }
            require(stop > 3 && stop < cds.length() - 2 && stop < cutoff, "CURATED_PREMATURE_STOP_NOT_CONFIRMED");
            if (nmdClaim) {
                require(model.path("lastJunctionCdsPosition").isIntegralNumber(), "CURATED_NMD_GEOMETRY_UNAVAILABLE");
                int junction = model.path("lastJunctionCdsPosition").asInt();
                long alteredJunction = (long) junction + (mapping.last() <= junction ? altered.length() - cds.length() : 0);
                require(model.path("exons").size() > 1 && junction > 0 && junction < cds.length()
                        && alteredJunction - (stop + 2L) > 50, "CURATED_NMD_GEOMETRY_CONFLICT");
                out.put("nmdGeometryReplayed", true).put("alteredLastJunctionCdsPosition", alteredJunction)
                        .put("stopToLastJunctionBases", alteredJunction - (stop + 2L));
            }
            if (nmdClaim && nmd.group(3) != null) {
                int codon = (stop - 1) / 3 + 1;
                String expected = "p." + TruncatingSequence.aminoName(TruncatingSequence.translateCodon(cds.substring(stop - 1, stop + 2))) + codon;
                require(nmd.group(3).equals(expected + "Ter") || nmd.group(3).equals(expected + "*"), "CURATED_TRUNCATING_PROTEIN_MISMATCH");
            }
            return out.put("status", "NON_START_LOSS_TRUNCATION_CONFIRMED")
                    .put("reasonCode", "ORIGINAL_TRUNCATING_CLAIM_REPLAYED_FROM_CDS")
                    .put("mechanism", frameshift ? "FRAMESHIFT_PREMATURE_STOP" : "IN_FRAME_PREMATURE_STOP")
                    .put("transcript", sourceTranscript).put("executionTranscript", model.path("transcript").asText())
                    .put("sourceClaim", claim).put("sourceCutoffCdsPosition", cutoff).put("sourceNmdClaimPresent", nmdClaim)
                    .put("referenceCdsSha256", CodingTranscriptMaterialSupply.sha(cds))
                    .put("alteredCdsSha256", CodingTranscriptMaterialSupply.sha(altered))
                    .put("codingAlleleKey", mapping.key()).put("firstPossibleCodingPosition", mapping.first())
                    .put("lastPossibleCodingPosition", mapping.last()).put("prematureStopCdsPosition", stop)
                    .put("originalStartRetained", true).put("nmdReevaluated", false).put("referencePathogenicityGranted", false);
        } catch (IllegalArgumentException invalid) {
            String reason = invalid.getMessage();
            return out.put("reasonCode", reason != null && reason.matches("[A-Z_]+") ? reason : "CURATED_TRUNCATING_FACTS_INVALID");
        }
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
