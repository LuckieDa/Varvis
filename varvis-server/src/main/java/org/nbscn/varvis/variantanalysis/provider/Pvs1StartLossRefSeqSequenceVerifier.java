package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;

/** Exact full-RNA/primary-reference reconciliation, not functional qualification.
 * No alias or accession prefix can substitute for sequence identity. */
final class Pvs1StartLossRefSeqSequenceVerifier {
    private Pvs1StartLossRefSeqSequenceVerifier() { }
    static ObjectNode verify(JsonNode context, ObjectNode inventory, Pvs1StartLossPublicSources sources,
            CodingTranscriptMaterialSupply supply) {
        var out = inventory.deepCopy(); var rows = new ArrayList<ObjectNode>();
        for (var row : out.path("transcripts")) if (row instanceof ObjectNode object) {
            object.put("sequenceVerified", false); object.remove(java.util.List.of("rnaSha256", "cdsSha256"));
            if ("AVAILABLE".equals(row.path("structureStatus").asText())) rows.add(object);
        }
        long bases = 0; int reads = 0, verified = 0;
        for (int offset = 0; offset < rows.size(); offset += 50) {
            var batch = rows.subList(offset, Math.min(offset + 50, rows.size()));
            var snapshot = sources.refseqSequences(batch.stream().map(r -> r.path("transcript").asText()).toList());
            for (var row : batch) {
                row.set("sequenceSource", snapshot.receipt());
                String sequence = snapshot.document().path("sequences").path(row.path("transcript").asText()).asText();
                try {
                    require("AVAILABLE".equals(snapshot.receipt().path("status").asText()) && !sequence.isEmpty(), "REFSEQ_EXACT_SEQUENCE_UNAVAILABLE");
                    require(sequence.matches("[ACGT]+") && sequence.length() == row.path("transcriptLength").asInt(), "REFSEQ_SEQUENCE_LENGTH_OR_BASES_INVALID");
                    require(bases + sequence.length() + 1 <= 2_000_000 && reads + row.path("transcriptExons").size() + 1 <= 5000,
                            "REFSEQ_REFERENCE_READ_BUDGET_EXHAUSTED");
                    String contig = row.path("contig").asText(); var reconstructed = new StringBuilder(); JsonNode identity = null;
                    for (var exon : row.path("transcriptExons")) {
                        int start = exon.path("genomicStart").asInt(), length = exon.path("genomicEnd").asInt() - start + 1;
                        bases += length; reads++;
                        var span = supply.startLossReference(context, contig, start, length);
                        require(identity == null || identity.equals(span.identity()), "REFSEQ_REFERENCE_CHANGED"); identity = span.identity();
                        reconstructed.append(CodingTranscriptMapping.orientAllele(row, span.bases()));
                    }
                    require(sequence.contentEquals(reconstructed), "REFSEQ_RNA_REFERENCE_SEQUENCE_MISMATCH");
                    int start = row.path("transcriptCdsStart").asInt(), end = row.path("transcriptCdsEnd").asInt();
                    require(start > 0 && end >= start && end <= sequence.length(), "REFSEQ_CDS_INVALID");
                    String cds = sequence.substring(start - 1, end);
                    require(cds.startsWith("ATG") && cds.length() % 3 == 0, "REFSEQ_CDS_START_UNSUPPORTED");
                    for (int i = 0; i < cds.length(); i += 3)
                        require((TruncatingSequence.translateCodon(cds.substring(i, i + 3)) == '*') == (i == cds.length() - 3), "REFSEQ_CDS_STOP_UNSUPPORTED");
                    reads++; bases++;
                    var after = supply.startLossReference(context, contig, row.at("/transcriptExons/0/genomicStart").asInt(), 1);
                    require(identity != null && identity.equals(after.identity()), "REFSEQ_REFERENCE_CHANGED");
                    row.put("sequenceVerified", true).put("rnaSha256", CodingTranscriptMaterialSupply.sha(sequence))
                            .put("cdsSha256", CodingTranscriptMaterialSupply.sha(cds));
                    row.remove("sequenceReasonCode"); verified++;
                } catch (Exception invalid) {
                    String reason = invalid.getMessage();
                    row.put("sequenceVerified", false).put("sequenceReasonCode", reason != null && reason.matches("[A-Z][A-Z0-9_]{1,120}")
                            ? reason : "REFSEQ_SEQUENCE_VERIFICATION_UNAVAILABLE");
                }
            }
        }
        return out.put("sequenceVerifiedCount", verified).put("referenceBasesRead", bases).put("referenceReads", reads);
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
