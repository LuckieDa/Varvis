package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A1-only coding identity across versions of the SAME RefSeq accession.
 * Complete RNA identity is not claimed. Never used by NMD or splice consumers. */
final class Pvs1StartLossCodingScopeBridge {
    static final String VERSION = "1.0.0";
    private Pvs1StartLossCodingScopeBridge() { }
    static final class Verified {
        private final ObjectNode context, audit;
        private Verified(ObjectNode context, ObjectNode audit) { this.context = context.deepCopy(); this.audit = audit.deepCopy(); }
        ObjectNode sourceContext() { return context.deepCopy(); }
        ObjectNode audit() { return audit.deepCopy(); }
    }
    private record Feature(String type, String location, String qualifiers) { }
    private record Record(String transcript, String rna, String cds, String protein, String proteinId, String ccds,
                          List<String> codingExons, int firstCodingExon) { }

    static Verified verify(ObjectMapper mapper, ObjectNode context, JsonNode model, String sourceTranscript,
            Pvs1StartLossPublicSources sources) {
        String execution = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        var referenceContext = Pvs1StartLossPublishedRule.sourceContext(context, model);
        String selected = referenceContext.at("/transcript/accession").asText() + "." + referenceContext.at("/transcript/version").asText();
        require(selected.matches("NM_[0-9]+\\.[1-9][0-9]*") && sourceTranscript.matches("NM_[0-9]+\\.[1-9][0-9]*")
                && selected.substring(0, selected.indexOf('.')).equals(sourceTranscript.substring(0, sourceTranscript.indexOf('.')))
                && !selected.equals(sourceTranscript), "ACCESSION_SCOPE_MISMATCH");
        require("GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                && "SNV".equals(context.at("/variant/variantType").asText())
                && execution.equals(model.path("transcript").asText())
                && (execution.equals(selected) || PublishedCriterionKnowledge.exactBridge(selected, model))
                && CodingTranscriptMapping.modelIssue(model, context) == null, "MODEL_OR_VARIANT_SCOPE_INVALID");
        String symbol = context.at("/gene/symbol").asText(), hgnc = context.at("/gene/hgncId").asText();
        var oldSource = sources.refseqGenBank(sourceTranscript); var currentSource = sources.refseqGenBank(selected);
        Record old = read(oldSource, sourceTranscript, symbol, hgnc), current = read(currentSource, selected, symbol, hgnc);
        String selectedProtein = execution.equals(selected) ? model.path("protein").asText() : model.at("/transcriptBridge/refseqProtein").asText();
        require(current.proteinId().equals(selectedProtein), "REFERENCE_VERIFIED_PROTEIN_MISMATCH");
        require(old.cds().equals(current.cds()) && old.protein().equals(current.protein())
                && old.proteinId().equals(current.proteinId()) && old.ccds().equals(current.ccds())
                && old.codingExons().equals(current.codingExons()) && old.firstCodingExon() == current.firstCodingExon(),
                "CODING_STRUCTURE_OR_IDENTITY_MISMATCH");
        require(current.cds().equals(model.path("cdsSequence").asText()), "REFERENCE_VERIFIED_CDS_MISMATCH");
        var modelExons = new ArrayList<String>(); int ordinal = 0, firstCodingExon = 0;
        for (var exon : model.path("exons")) {
            ordinal++; int lo = Math.max(1, exon.path("cdsStart").asInt()), hi = Math.min(current.cds().length(), exon.path("cdsEnd").asInt());
            if (lo <= hi) { if (firstCodingExon == 0) firstCodingExon = ordinal; modelExons.add(lo + ".." + hi); }
        }
        require(current.codingExons().equals(modelExons) && firstCodingExon == current.firstCodingExon(), "REFERENCE_VERIFIED_CODING_EXONS_MISMATCH");
        int genomic = context.at("/variant/evidenceQueryAllele/position").asInt();
        var start = Pvs1StartLossAutomaticPreparation.startPositions(model); int position = start.indexOf(genomic);
        require(position >= 0, "TARGET_NOT_IN_SAME_CODING_START");
        String ref = CodingTranscriptMapping.orientAllele(model, context.at("/variant/evidenceQueryAllele/reference").asText());
        String alt = CodingTranscriptMapping.orientAllele(model, context.at("/variant/evidenceQueryAllele/alternate").asText());
        require(ref.matches("[ACGT]") && alt.matches("[ACGT]") && !ref.equals(alt)
                && current.cds().substring(position, position + 1).equals(ref), "TARGET_EDIT_MISMATCH");
        var sourceContext = context.deepCopy(); int dot = sourceTranscript.indexOf('.');
        sourceContext.withObject("transcript").put("accession", sourceTranscript.substring(0, dot))
                .put("version", Integer.parseInt(sourceTranscript.substring(dot + 1)));
        var audit = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-coding-scope-bridge-v1")
                .put("methodVersion", VERSION).put("status", "CODING_START_SCOPE_VERIFIED")
                .put("scope", "CODING_START_LOSS_ONLY").put("sourceTranscript", sourceTranscript).put("selectedTranscript", execution)
                .put("selectedRefseqTranscript", selected)
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(current.cds())).put("proteinId", current.proteinId()).put("ccds", current.ccds())
                .put("sourceRnaSha256", CodingTranscriptMaterialSupply.sha(old.rna())).put("selectedRnaSha256", CodingTranscriptMaterialSupply.sha(current.rna()))
                .put("fullRnaEqual", old.rna().equals(current.rna())).put("fullTranscriptEquivalenceGranted", false)
                .put("firstCodingExon", current.firstCodingExon()).put("targetCodingPosition", position + 1).put("executionContextUnchanged", true);
        audit.set("sourceRecord", oldSource.receipt()); audit.set("selectedRecord", currentSource.receipt());
        var positions = audit.putArray("codingStartGenomicPositions"); start.forEach(positions::add);
        var exons = audit.putArray("codingExonBoundaries"); current.codingExons().forEach(exons::add);
        return new Verified(sourceContext, audit);
    }

    private static Record read(Pvs1StartLossPublicSources.Snapshot source, String transcript, String symbol, String hgnc) {
        require("AVAILABLE".equals(source.receipt().path("status").asText()) && source.document().path("genbank").isTextual(), "GENBANK_UNAVAILABLE");
        String raw = source.document().path("genbank").asText();
        require(raw.length() <= 2_000_000 && source.receipt().path("sha256").asText().equals(
                CodingTranscriptMaterialSupply.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))), "GENBANK_BYTES_MISMATCH");
        var version = Pattern.compile("(?m)^VERSION +([^ \\r\\n]+) *\\r?$").matcher(raw);
        require(version.find() && transcript.equals(version.group(1)) && !version.find(), "GENBANK_TRANSCRIPT_MISMATCH");
        require(raw.contains("REVIEWED REFSEQ") && raw.matches("(?s).*\\n  ORGANISM +Homo sapiens\\r?\\n.*")
                && raw.contains("/db_xref=\"taxon:9606\""), "GENBANK_SOURCE_SCOPE_INVALID");
        int features = raw.indexOf("\nFEATURES "), origin = raw.indexOf("\nORIGIN");
        require(features >= 0 && origin > features && raw.indexOf("\nORIGIN", origin + 1) < 0 && raw.stripTrailing().endsWith("//"), "GENBANK_RECORD_INVALID");
        String body = raw.substring(origin).replaceFirst("^\\nORIGIN[^\\r\\n]*\\r?\\n", "").replaceFirst("(?s)\\r?\\n//\\s*$", "");
        require(body.matches("[0-9acgtACGT\\s]+"), "GENBANK_RNA_INVALID");
        String rna = body.replaceAll("[0-9\\s]", "").toUpperCase(java.util.Locale.ROOT);
        var parsed = new ArrayList<Feature>(); var matcher = Pattern.compile("(?m)^ {5}([A-Za-z_]+) +([^\\r\\n]+)\\r?$").matcher(raw.substring(features, origin));
        String type = "", location = ""; int end = 0; String featureText = raw.substring(features, origin);
        while (matcher.find()) {
            if (!type.isEmpty()) parsed.add(new Feature(type, location, featureText.substring(end, matcher.start())));
            type = matcher.group(1); location = matcher.group(2).strip(); end = matcher.end();
        }
        if (!type.isEmpty()) parsed.add(new Feature(type, location, featureText.substring(end)));
        var cdsFeatures = parsed.stream().filter(f -> f.type().equals("CDS")).toList();
        var geneFeatures = parsed.stream().filter(f -> f.type().equals("gene")).toList();
        require(cdsFeatures.size() == 1 && geneFeatures.size() == 1 && symbol.equals(quoted(geneFeatures.get(0).qualifiers(), "gene"))
                && geneFeatures.get(0).qualifiers().contains("/db_xref=\"HGNC:" + hgnc + "\""), "GENBANK_GENE_MISMATCH");
        var feature = cdsFeatures.get(0);
        require(symbol.equals(quoted(feature.qualifiers(), "gene")) && feature.qualifiers().matches("(?s).*?/codon_start=1(?:\\s|$).*")
                && !Pattern.compile("/(?:transl_except|exception|ribosomal_slippage)\\b").matcher(feature.qualifiers()).find()
                && (!feature.qualifiers().contains("/transl_table=") || feature.qualifiers().matches("(?s).*?/transl_table=1(?:\\s|$).*")), "GENBANK_CDS_UNSUPPORTED");
        int[] bounds = span(feature.location()); require(bounds[1] <= rna.length(), "GENBANK_CDS_RANGE_INVALID");
        String cds = rna.substring(bounds[0] - 1, bounds[1]);
        require(cds.startsWith("ATG") && cds.length() >= 6 && cds.length() % 3 == 0, "GENBANK_CDS_INVALID");
        var translated = new StringBuilder();
        for (int i = 0; i < cds.length(); i += 3) {
            char aa = TruncatingSequence.translateCodon(cds.substring(i, i + 3));
            require((aa == '*') == (i == cds.length() - 3), "GENBANK_TRANSLATION_INVALID");
            if (aa != '*') translated.append(aa);
        }
        String protein = quoted(feature.qualifiers(), "translation").replaceAll("\\s", "");
        require(protein.contentEquals(translated), "GENBANK_TRANSLATION_MISMATCH");
        String proteinId = quoted(feature.qualifiers(), "protein_id");
        var ccds = Pattern.compile("/db_xref=\"CCDS:(CCDS[0-9]+\\.[1-9][0-9]*)\"").matcher(feature.qualifiers());
        require(proteinId.matches("NP_[0-9]+\\.[1-9][0-9]*") && ccds.find(), "GENBANK_CODING_IDENTITY_MISSING");
        String ccdsId = ccds.group(1); require(!ccds.find(), "GENBANK_CODING_IDENTITY_AMBIGUOUS");
        int previous = 0, ordinal = 0, first = 0; var coding = new ArrayList<String>();
        for (var exon : parsed) if (exon.type().equals("exon")) {
            int[] exonBounds = span(exon.location()); ordinal++;
            require(exonBounds[0] == previous + 1 && exonBounds[1] <= rna.length(), "GENBANK_EXON_CHAIN_INVALID"); previous = exonBounds[1];
            int lo = Math.max(bounds[0], exonBounds[0]), hi = Math.min(bounds[1], exonBounds[1]);
            if (lo <= hi) { if (first == 0) first = ordinal; coding.add((lo - bounds[0] + 1) + ".." + (hi - bounds[0] + 1)); }
        }
        require(previous == rna.length() && !coding.isEmpty(), "GENBANK_EXON_CHAIN_INCOMPLETE");
        return new Record(transcript, rna, cds, protein, proteinId, ccdsId, coding, first);
    }
    private static String quoted(String qualifiers, String key) {
        var match = Pattern.compile("/" + key + "=\"([^\"]*)\"", Pattern.DOTALL).matcher(qualifiers);
        require(match.find(), "GENBANK_QUALIFIER_MISSING"); String value = match.group(1);
        require(!match.find(), "GENBANK_QUALIFIER_AMBIGUOUS"); return value;
    }
    private static int[] span(String location) {
        require(location.matches("[1-9][0-9]{0,8}\\.\\.[1-9][0-9]{0,8}"), "GENBANK_LOCATION_UNSUPPORTED");
        String[] parts = location.split("\\.\\."); int start = Integer.parseInt(parts[0]), end = Integer.parseInt(parts[1]);
        require(end >= start, "GENBANK_LOCATION_INVALID"); return new int[]{start, end};
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException("PVS1_START_LOSS_CODING_BRIDGE_" + reason);
    }
}
