package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Native SNV-to-codon/protein consistency only; no functional or computational evidence policy. */
final class CodingMissenseResolver {
    private CodingMissenseResolver() { }

    static String resolve(JsonNode context, TranscriptConsequence tx, JsonNode model,
                          ObjectNode identity, RuntimeEvidenceV2Adapter adapter) {
        String modelIssue = CodingTranscriptMapping.modelIssue(model, context);
        if (modelIssue != null) return modelIssue.replace("CODING_TRANSCRIPT_MODEL_", "CODING_MISSENSE_MODEL_");
        if (tx == null) return "CODING_MISSENSE_ANNOTATION_MISSING";
        if (!model.path("transcript").asText().equals(tx.getVersionedTranscriptId())
                || !context.at("/gene/symbol").asText().equals(tx.getGeneSymbol())
                || !context.at("/transcript/annotationSourceVersion").asText().equals(tx.getVersion()))
            return "CODING_MISSENSE_ANNOTATION_IDENTITY_MISMATCH";
        String versioned = model.path("transcript").asText();
        String accession = context.at("/transcript/accession").asText();
        for (String alias : new String[] {tx.getTranscriptId(), tx.getTranscriptAccession()}) {
            if (alias != null && !alias.isBlank() && !alias.equals(versioned) && !alias.equals(accession))
                return "CODING_MISSENSE_ANNOTATION_IDENTITY_MISMATCH";
        }
        Set<String> terms = new HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")));
        if (!terms.contains("missense_variant") || terms.stream().anyMatch(t -> t.contains("splice") || t.contains("stop")
                || Set.of("intron_variant", "start_lost", "frameshift_variant").contains(t)))
            return "CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED";
        String genomicReference = context.at("/variant/evidenceQueryAllele/reference").asText();
        String genomicAlternate = context.at("/variant/evidenceQueryAllele/alternate").asText();
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText())
                || !genomicReference.matches("[ACGT]") || !genomicAlternate.matches("[ACGT]")
                || genomicReference.equals(genomicAlternate)) return "CODING_MISSENSE_NATIVE_SNV_REQUIRED";
        JsonNode genomicNode = context.at("/variant/evidenceQueryAllele/position");
        if (!genomicNode.isIntegralNumber() || !genomicNode.canConvertToInt() || genomicNode.asInt() < 1)
            return "CODING_MISSENSE_GENOMIC_POSITION_INVALID";
        int genomic = genomicNode.asInt();
        int c = CodingTranscriptMapping.cdsPosition(model, genomic);
        String reference = CodingTranscriptMapping.orientAllele(model, genomicReference);
        String alternate = CodingTranscriptMapping.orientAllele(model, genomicAlternate);
        String cds = model.path("cdsSequence").asText();
        if (c < 4 || c > cds.length() - 3) return "CODING_MISSENSE_START_OR_NONCODING_EFFECT_NOT_SUPPORTED";
        if (cds.charAt(c - 1) != reference.charAt(0)) return "CODING_MISSENSE_CDS_REFERENCE_MISMATCH";
        JsonNode exon = CodingTranscriptMapping.exonForGenomicInterval(model, genomic, genomic);
        if (exon == null || !(exon.path("ordinal").asInt() + "/" + model.path("exons").size()).equals(tx.getExon()))
            return "CODING_MISSENSE_EXON_IDENTITY_MISMATCH";
        String transcript = model.path("transcript").asText();
        if (!(transcript + ":c." + c + reference + ">" + alternate).equals(tx.getHgvsc()))
            return "CODING_MISSENSE_GENOMIC_CODING_IDENTITY_MISMATCH";
        int start = (c - 1) / 3 * 3;
        String original = cds.substring(start, start + 3);
        var mutated = new StringBuilder(original); mutated.setCharAt((c - 1) % 3, alternate.charAt(0));
        String changed = mutated.toString();
        char refAa = TruncatingSequence.translateCodon(original), altAa = TruncatingSequence.translateCodon(changed);
        if (refAa == altAa || refAa == '*' || altAa == '*') return "CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED";
        int position = start / 3 + 1;
        String protein = model.path("protein").asText();
        String hgvsp = protein + ":p." + TruncatingSequence.aminoName(refAa) + position + TruncatingSequence.aminoName(altAa);
        if (!protein.equals(tx.getProteinIdentity()) || !hgvsp.equals(tx.getHgvsp())
                || !Integer.toString(position).equals(tx.getProteinPosition())
                || !(refAa + "/" + altAa).equals(tx.getAminoAcids())
                || !(original + "/" + changed).equals(Objects.toString(tx.getCodons(), "").toUpperCase(Locale.ROOT)))
            return "CODING_MISSENSE_CODING_PROTEIN_IDENTITY_MISMATCH";
        identity.put("proteinVariantKey", "" + refAa + position + altAa).put("hgvsp", hgvsp).put("hgvsc", tx.getHgvsc())
                .put("refCodon", original).put("altCodon", changed).put("cdsPosition", c).put("proteinPosition", position)
                .put("refAminoAcid", Character.toString(refAa)).put("altAminoAcid", Character.toString(altAa))
                .put("nativeModelSha256", adapter.canonicalSha256(model));
        identity.set("allele", context.at("/variant/evidenceQueryAllele").deepCopy());
        return null;
    }
}
