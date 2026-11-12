package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** NCBI Datasets native product coordinates are one-based inclusive. Coding
 * geometry is derived from transcript-relative CDS and ordered genomic exons.
 * Protein-coding/model labels are never treated as functional rescue. */
final class Pvs1StartLossRefSeqInventory {
    private Pvs1StartLossRefSeqInventory() { }

    static ObjectNode read(ObjectMapper mapper, JsonNode context,
            Pvs1StartLossPublicSources.Snapshot metadata, Pvs1StartLossPublicSources.Snapshot products) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-refseq-inventory-v1")
                .put("status", "UNAVAILABLE").put("sourceCoverageComplete", false).put("functionalCoverageComplete", false);
        out.put("coverage", "NCBI_DATASETS_PRODUCT_REPORT");
        out.set("geneSource", metadata.receipt()); out.set("productSource", products.receipt());
        var rows = out.putArray("transcripts");
        try {
            require("AVAILABLE".equals(metadata.receipt().path("status").asText())
                    && "AVAILABLE".equals(products.receipt().path("status").asText()), "REFSEQ_SOURCE_UNAVAILABLE");
            JsonNode genes = metadata.document(), data = products.document();
            require(pageComplete(genes) && pageComplete(data), "REFSEQ_PAGE_INCOMPLETE");
            List<JsonNode> matches = new ArrayList<>();
            for (var report : genes.path("reports")) {
                var gene = report.path("gene");
                if ("HGNC".equals(gene.at("/nomenclature_authority/authority").asText())
                        && context.at("/gene/hgncId").equals(gene.at("/nomenclature_authority/identifier"))) matches.add(gene);
            }
            require(matches.size() == 1, "REFSEQ_GENE_IDENTITY_UNRESOLVED");
            var gene = matches.get(0);
            require("9606".equals(gene.path("tax_id").asText()) && context.at("/gene/symbol").equals(gene.path("symbol"))
                    && gene.path("gene_id").asText().matches("[1-9][0-9]*"), "REFSEQ_GENE_IDENTITY_UNRESOLVED");
            matches.clear();
            for (var report : data.path("reports")) if (gene.path("gene_id").equals(report.at("/product/gene_id"))) matches.add(report.path("product"));
            require(matches.size() == 1, "REFSEQ_PRODUCT_IDENTITY_UNRESOLVED");
            var product = matches.get(0); var transcripts = product.path("transcripts");
            require(product.path("symbol").equals(gene.path("symbol")) && "9606".equals(product.path("tax_id").asText())
                    && transcripts.isArray() && !transcripts.isEmpty() && transcripts.size() <= 1000
                    && gene.path("transcript_count").isIntegralNumber() && gene.path("transcript_count").asInt() == transcripts.size(),
                    "REFSEQ_TRANSCRIPT_COUNT_MISMATCH");
            var seen = new HashSet<String>(); int ordinal = 0;
            for (var tx : transcripts) {
                String accession = tx.path("accession_version").asText();
                require(accession.matches("[NX][MR]_[0-9]+\\.[1-9][0-9]*") && seen.add(accession), "REFSEQ_TRANSCRIPT_IDENTITY_INVALID");
                var row = rows.addObject().put("transcript", accession).put("geneSymbol", gene.path("symbol").asText())
                        .put("hgncId", context.at("/gene/hgncId").asText()).put("assembly", "GRCh38")
                        .put("contig", context.at("/variant/evidenceQueryAllele/contig").asText())
                        .put("sourceSha256", products.receipt().path("sha256").asText())
                        .put("sourceLocator", "/reports/" + reportIndex(data, product) + "/product/transcripts/" + ordinal++)
                        .put("sourceTranscriptType", tx.path("type").asText()).put("structureStatus", "UNRESOLVED");
                if (tx.path("ensembl_transcript").isTextual()) row.set("declaredEnsemblAlias", tx.path("ensembl_transcript"));
                if ("NON_CODING".equals(tx.path("type").asText()) && !tx.hasNonNull("cds")) {
                    row.put("transcriptType", "ncRNA").put("structureStatus", "ANNOTATED_NONCODING")
                            .put("reasonCode", "REFSEQ_EXPLICIT_NONCODING_PRODUCT");
                    continue;
                }
                try { geometry(mapper, context, tx, row); row.put("structureStatus", "AVAILABLE"); }
                catch (IllegalArgumentException invalid) { row.put("reasonCode", invalid.getMessage()); }
            }
            return out.put("status", "AVAILABLE").put("sourceCoverageComplete", true).put("geneId", gene.path("gene_id").asText())
                    .put("reasonCode", "REFSEQ_ENUMERATED_FUNCTION_NOT_INFERRED");
        } catch (IllegalArgumentException invalid) { return out.put("reasonCode", invalid.getMessage()); }
    }

    private static int reportIndex(JsonNode data, JsonNode product) {
        for (int i = 0; i < data.path("reports").size(); i++) if (data.path("reports").get(i).path("product").equals(product)) return i;
        throw new IllegalArgumentException("REFSEQ_PRODUCT_IDENTITY_UNRESOLVED");
    }
    private static boolean pageComplete(JsonNode page) {
        return page.path("reports").isArray() && page.path("reports").size() <= 1000
                && page.path("total_count").asText().matches("[0-9]{1,4}")
                && page.path("total_count").asInt(-1) == page.path("reports").size()
                && (!page.has("next_page_token") || page.path("next_page_token").isTextual() && page.path("next_page_token").asText().isEmpty());
    }
    private static void geometry(ObjectMapper mapper, JsonNode context, JsonNode tx, ObjectNode row) {
        String contig = context.at("/variant/evidenceQueryAllele/contig").asText();
        int[] versions = {11,12,12,12,10,12,14,11,12,11,10,12,11,9,10,10,11,10,10,11,9,11};
        int chromosome = "X".equals(contig) ? 23 : "Y".equals(contig) ? 24 : contig.matches("[1-9][0-9]?") ? Integer.parseInt(contig) : 0;
        require(chromosome >= 1 && chromosome <= 24 && "GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText()), "REFSEQ_ASSEMBLY_UNSUPPORTED");
        String accession = "NC_%06d.%d".formatted(chromosome, chromosome <= 22 ? versions[chromosome - 1] : chromosome == 23 ? 11 : 10);
        List<JsonNode> locations = new ArrayList<>();
        for (var location : tx.path("genomic_locations")) if (accession.equals(location.path("genomic_accession_version").asText())) locations.add(location);
        require(locations.size() == 1, "REFSEQ_PRIMARY_LOCATION_UNRESOLVED");
        var location = locations.get(0); String orientation = location.at("/genomic_range/orientation").asText();
        require(Set.of("plus", "minus").contains(orientation) && location.path("exons").isArray()
                && !location.path("exons").isEmpty() && location.path("exons").size() <= 1000, "REFSEQ_EXONS_UNRESOLVED");
        int strand = "plus".equals(orientation) ? 1 : -1; row.put("strand", strand);
        require(tx.at("/cds/accession_version").equals(tx.path("accession_version"))
                && tx.at("/cds/range").isArray() && tx.at("/cds/range").size() == 1
                && "plus".equals(tx.at("/cds/range/0/orientation").asText()), "REFSEQ_CDS_UNSUPPORTED");
        long cdsStart = coordinate(tx.at("/cds/range/0/begin")), cdsEnd = coordinate(tx.at("/cds/range/0/end"));
        long length = coordinate(tx.path("length"));
        require(cdsStart <= cdsEnd && cdsEnd <= length && (cdsEnd - cdsStart + 1) % 3 == 0, "REFSEQ_CDS_INVALID");
        List<JsonNode> exons = new ArrayList<>(); location.path("exons").forEach(exons::add);
        exons.sort(Comparator.comparingLong(e -> e.path("order").asLong()));
        var coding = mapper.createArrayNode(); long transcriptPosition = 1, previousStart = 0, previousEnd = 0; int order = 1;
        var transcriptExons = row.putArray("transcriptExons");
        for (var exon : exons) {
            long start = coordinate(exon.path("begin")), end = coordinate(exon.path("end"));
            require(start <= end && exon.path("order").isIntegralNumber() && exon.path("order").asInt() == order++
                    && orientation.equals(exon.path("orientation").asText())
                    && (previousStart == 0 || strand == 1 && start > previousEnd || strand == -1 && end < previousStart), "REFSEQ_EXON_ORDER_INVALID");
            long transcriptEnd = transcriptPosition + end - start;
            transcriptExons.addObject().put("genomicStart", start).put("genomicEnd", end);
            long lo = Math.max(cdsStart, transcriptPosition), hi = Math.min(cdsEnd, transcriptEnd);
            if (lo <= hi) {
                long a = strand == 1 ? start + lo - transcriptPosition : end - hi + transcriptPosition;
                long b = strand == 1 ? start + hi - transcriptPosition : end - lo + transcriptPosition;
                coding.addObject().put("genomicStart", a).put("genomicEnd", b).put("cdsStart", lo - cdsStart + 1).put("cdsEnd", hi - cdsStart + 1);
            }
            transcriptPosition = transcriptEnd + 1; previousStart = start; previousEnd = end;
        }
        require(transcriptPosition - 1 == length && !coding.isEmpty(), "REFSEQ_TRANSCRIPT_LENGTH_MISMATCH");
        row.put("cdsLength", cdsEnd - cdsStart + 1).put("transcriptType", "protein_coding").set("exons", coding);
        row.put("transcriptLength", length).put("transcriptCdsStart", cdsStart).put("transcriptCdsEnd", cdsEnd);
        row.put("sequenceVerified", false); // Geometry is not reference-sequence verification.
    }
    private static long coordinate(JsonNode value) {
        require(value.asText().matches("[1-9][0-9]{0,9}"), "REFSEQ_COORDINATE_INVALID");
        long n = Long.parseLong(value.asText()); require(n <= Integer.MAX_VALUE, "REFSEQ_COORDINATE_INVALID"); return n;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
