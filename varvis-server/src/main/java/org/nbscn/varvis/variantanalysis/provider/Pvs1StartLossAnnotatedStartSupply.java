package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Completes missing annotated starts, not isoform function. In particular an
 * NMD label is never used as evidence that the transcript cannot rescue. */
final class Pvs1StartLossAnnotatedStartSupply {
    static final String VERSION = "1.0.0";
    private Pvs1StartLossAnnotatedStartSupply() { }
    static ObjectNode supplement(ObjectMapper mapper, ObjectNode context, ObjectNode inventory,
            Pvs1StartLossPublicSources sources, CodingTranscriptMaterialSupply reference) {
        var result = inventory.deepCopy(); int attempted = 0;
        for (var node : result.path("transcripts")) {
            if (!(node instanceof ObjectNode row) || "AVAILABLE".equals(row.path("structureStatus").asText())
                    || !Set.of("nonsense_mediated_decay", "retained_intron").contains(row.path("transcriptType").asText())) continue;
            if (attempted++ >= 32) {
                row.put("startVerificationReason", "PVS1_ANNOTATED_START_REQUEST_LIMIT"); continue;
            }
            var source = sources.ensemblTranscript(row.path("transcript").asText()); row.set("startCodonSource", source.receipt());
            try { qualify(mapper, context, row, source, reference); }
            catch (Exception invalid) {
                String message = invalid.getMessage();
                row.put("codingStartReferenceVerified", false).put("startVerificationReason",
                        message != null && message.matches("PVS1_ANNOTATED_START_[A-Z_]+") ? message : "PVS1_ANNOTATED_START_UNAVAILABLE");
            }
        }
        return result;
    }
    private static void qualify(ObjectMapper mapper, ObjectNode context, ObjectNode row,
            Pvs1StartLossPublicSources.Snapshot source, CodingTranscriptMaterialSupply reference) throws Exception {
        require("AVAILABLE".equals(source.receipt().path("status").asText()), "SOURCE_UNAVAILABLE");
        var doc = source.document(); String transcript = row.path("transcript").asText();
        require(doc.path("version").isIntegralNumber() && doc.path("version").canConvertToInt()
                && transcript.equals(doc.path("id").asText() + "." + doc.path("version").asInt())
                && "Transcript".equals(doc.path("object_type").asText()) && "homo_sapiens".equals(doc.path("species").asText())
                && "GRCh38".equals(doc.path("assembly_name").asText()) && row.path("contig").equals(doc.path("seq_region_name"))
                && row.path("geneId").asText().matches("ENSG[0-9]+\\.[1-9][0-9]*")
                && row.path("geneId").asText().split("\\.")[0].equals(doc.path("Parent").asText())
                && row.path("transcriptType").equals(doc.path("biotype")), "TRANSCRIPT_OR_GENE_IDENTITY_MISMATCH");
        int strand = doc.path("strand").asInt();
        require(!row.has("strand") || row.path("strand").isIntegralNumber() && row.path("strand").asInt() == strand,
                "SOURCE_STRAND_CONFLICT");
        require(doc.path("strand").isIntegralNumber() && Math.abs(strand) == 1 && doc.path("Exon").isArray() && !doc.path("Exon").isEmpty()
                && doc.path("Exon").size() <= 1000, "EXON_SCOPE_INVALID");
        var translation = doc.path("Translation");
        require(translation.path("id").asText().matches("ENSP[0-9]+") && translation.path("version").isIntegralNumber() && translation.path("version").asInt() > 0
                && translation.path("Parent").equals(doc.path("id")) && translation.path("length").asInt() > 0
                && coordinate(translation.path("start")) <= coordinate(translation.path("end")), "TRANSLATION_UNAVAILABLE");
        List<JsonNode> exons = new ArrayList<>(); doc.path("Exon").forEach(exons::add);
        exons.sort(Comparator.comparingInt(e -> strand * e.path("start").asInt()));
        int previousStart = 0, previousEnd = 0; var positions = new ArrayList<Integer>();
        int start = strand == 1 ? translation.path("start").asInt() : translation.path("end").asInt();
        boolean active = false; var observedExons = mapper.createArrayNode();
        for (var exon : exons) {
            int lo = coordinate(exon.path("start")), hi = coordinate(exon.path("end"));
            require(lo <= hi && exon.path("strand").asInt() == strand && row.path("contig").equals(exon.path("seq_region_name"))
                    && "GRCh38".equals(exon.path("assembly_name").asText())
                    && (previousStart == 0 || strand == 1 && lo > previousEnd || strand == -1 && hi < previousStart), "EXON_CHAIN_INVALID");
            previousStart = lo; previousEnd = hi; observedExons.addObject().put("genomicStart", lo).put("genomicEnd", hi);
            if (start >= lo && start <= hi) active = true;
            if (active && positions.size() < 3) {
                int base = positions.isEmpty() ? start : strand == 1 ? lo : hi;
                for (int p = base; p >= lo && p <= hi && positions.size() < 3; p += strand) positions.add(p);
            }
        }
        require(positions.size() == 3, "START_GEOMETRY_UNRESOLVED");
        JsonNode knownExons = row.path("transcriptExons").isArray() ? row.path("transcriptExons") : row.path("exons");
        if (knownExons.isArray()) {
            var originalExons = mapper.createArrayNode();
            for (var exon : knownExons) originalExons.addObject().put("genomicStart", coordinate(exon.path("genomicStart")))
                    .put("genomicEnd", coordinate(exon.path("genomicEnd")));
            require(originalExons.equals(observedExons), "SOURCE_EXON_CHAIN_CONFLICT");
        }
        var bases = new StringBuilder(); JsonNode referenceIdentity = null;
        row.put("strand", strand);
        for (int position : positions) {
            var span = reference.startLossReference(context, row.path("contig").asText(), position, 1);
            require(referenceIdentity == null || referenceIdentity.equals(span.identity()), "REFERENCE_CHANGED"); referenceIdentity = span.identity();
            bases.append(CodingTranscriptMapping.orientAllele(row, span.bases()));
        }
        require("ATG".contentEquals(bases), "REFERENCE_CODON_NOT_ATG");
        row.put("codingStartReferenceVerified", true).put("startVerificationMethod", VERSION);
        var startPositions = row.putArray("verifiedStartPositions"); positions.forEach(startPositions::add);
        row.set("transcriptExons", observedExons); row.remove("startVerificationReason");
    }
    private static int coordinate(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0, "COORDINATE_INVALID"); return value.asInt();
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException("PVS1_ANNOTATED_START_" + reason);
    }
}
