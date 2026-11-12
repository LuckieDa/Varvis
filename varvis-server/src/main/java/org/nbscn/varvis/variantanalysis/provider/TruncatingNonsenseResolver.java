package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strand-aware single-base stop derivation. Policy bounds are supplied and never inferred. */
final class TruncatingNonsenseResolver {
    private final JsonNode model;
    private final JsonNode policy;
    private final String transcript;
    private final String exonRelevance;
    private final String mechanism;
    private final boolean legacy;
    private final Pattern codingPattern;

    TruncatingNonsenseResolver(JsonNode model, JsonNode policy, String transcript,
                              String exonRelevance, String mechanism, boolean legacy) {
        this.model = model;
        this.policy = policy;
        this.transcript = transcript;
        this.exonRelevance = exonRelevance;
        this.mechanism = mechanism;
        this.legacy = legacy;
        codingPattern = Pattern.compile("^" + Pattern.quote(transcript) + ":c\\.([1-9][0-9]*)([ACGT])>([ACGT])$");
    }

    String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode inputs) {
        return resolve(context, tx, inputs, false);
    }

    String deriveGenomicStop(JsonNode context, TranscriptConsequence tx, ObjectNode inputs) {
        return resolve(context, tx, inputs, true);
    }

    private String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode inputs, boolean sequenceOnly) {
        var coding = codingPattern.matcher(Objects.toString(tx.getHgvsc(), ""));
        if (!coding.matches()) return "PVS1_CODING_POSITION_UNRESOLVED";
        int c = Integer.parseInt(coding.group(1));
        int g = context.at("/variant/evidenceQueryAllele/position").asInt();
        String ref = context.at("/variant/evidenceQueryAllele/reference").asText();
        String alt = context.at("/variant/evidenceQueryAllele/alternate").asText();
        JsonNode exon = CodingTranscriptMapping.exonForGenomicInterval(model, g, g);
        String transcriptRef = CodingTranscriptMapping.orientAllele(model, ref);
        String transcriptAlt = CodingTranscriptMapping.orientAllele(model, alt);
        if (exon == null || c != CodingTranscriptMapping.cdsPosition(model, g)
                || !transcriptRef.equals(coding.group(2)) || !transcriptAlt.equals(coding.group(3))) return "PVS1_GENOMIC_CDS_MAPPING_MISMATCH";
        int ordinal = exon.path("ordinal").asInt();
        if (!(ordinal + "/" + model.path("exons").size()).equals(tx.getExon())) return "PVS1_EXON_STRUCTURE_MISMATCH";
        String cds = model.path("cdsSequence").asText();
        if (c < 1 || c > cds.length() - 3 || cds.charAt(c - 1) != transcriptRef.charAt(0)) return "PVS1_CDS_REFERENCE_MISMATCH";
        int codonStart = (c - 1) / 3 * 3;
        String original = cds.substring(codonStart, codonStart + 3);
        var substituted = new StringBuilder(original); substituted.setCharAt((c - 1) % 3, transcriptAlt.charAt(0));
        String alternate = substituted.toString();
        int amino = codonStart / 3 + 1;
        if (!Set.of("TAA", "TAG", "TGA").contains(alternate) || TruncatingSequence.translateCodon(original) == '*') return "PVS1_STOP_GAIN_NOT_CONFIRMED";
        if (!model.path("protein").asText().equals(tx.getProteinIdentity())
                || !(model.path("protein").asText() + ":p." + TruncatingSequence.aminoName(TruncatingSequence.translateCodon(original)) + amino + "Ter").equals(tx.getHgvsp())
                || !Integer.toString(amino).equals(tx.getProteinPosition())
                || !(TruncatingSequence.translateCodon(original) + "/*").equals(tx.getAminoAcids())
                || !(original + "/" + alternate).equals(Objects.toString(tx.getCodons(), "").toUpperCase(java.util.Locale.ROOT)))
            return "PVS1_PROTEIN_CODON_MISMATCH";
        int ptcEnd = codonStart + 3;
        boolean singleExon = model.path("exons").size() == 1;
        int distance = model.path("lastJunctionCdsPosition").asInt() - ptcEnd;
        inputs.put("cdsPosition", c).put("exonOrdinal", ordinal).put("exonTotal", model.path("exons").size()).put("refCodon", original)
                .put("altCodon", alternate).put("proteinPosition", amino).put("ptcStart", codonStart + 1).put("ptcEnd", ptcEnd)
                .put("lastJunctionCdsPosition", model.path("lastJunctionCdsPosition").asInt()).put("distanceToLastJunction", distance)
                .put(transcript.startsWith("ENST") ? "ensemblTranscript" : "selectedTranscript", transcript)
                .put("refseqTranscript", model.path("refseqTranscript").asText());
        if (sequenceOnly) return null;
        int maximumPtcExonLength = exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt() + 1;
        if (!legacy && (codonStart + 1 < exon.path("cdsStart").asInt() || ptcEnd > exon.path("cdsEnd").asInt())) {
            var ordinals = inputs.putArray("ptcExonOrdinals");
            for (var carrier : model.path("exons")) {
                if (carrier.path("cdsStart").asInt() > ptcEnd || carrier.path("cdsEnd").asInt() <= codonStart) continue;
                ordinals.add(carrier.path("ordinal").asInt());
                maximumPtcExonLength = Math.max(maximumPtcExonLength,
                        carrier.path("genomicEnd").asInt() - carrier.path("genomicStart").asInt() + 1);
            }
        }
        if (singleExon) inputs.putNull("lastJunctionCdsPosition").putNull("distanceToLastJunction");
        if (c < policy.path("minimumCdsPosition").asInt() || ordinal < policy.path("firstExon").asInt())
            return "PVS1_START_PROXIMAL_BRANCH_NOT_IMPLEMENTED";
        if (singleExon || (legacy ? ptcEnd >= policy.path("exclusivePtcEnd").asInt()
                : distance < policy.at("/frameshift/minimumDistanceToLastJunction").asInt())
                || ordinal > policy.path("lastExon").asInt())
            return "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED";
        if (maximumPtcExonLength > policy.path("maximumExonLength").asInt())
            return "PVS1_LONG_EXON_BRANCH_NOT_IMPLEMENTED";
        inputs.put("nmdPrediction", "PREDICTED_NMD").put("exonRelevance", exonRelevance)
                .put("mechanism", mechanism);
        return null;
    }
}
