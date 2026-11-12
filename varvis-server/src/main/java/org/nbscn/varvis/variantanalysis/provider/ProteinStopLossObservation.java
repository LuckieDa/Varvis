package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Predicted sequence extension from a qualified reference transcript, never observed RNA/protein. */
final class ProteinStopLossObservation {
    static final String VERSION = "1.0.0";
    static final int MAXIMUM_TAIL_BASES = 100_000;
    private ProteinStopLossObservation() { }

    static boolean annotated(TranscriptConsequence annotation) {
        return Arrays.asList(Objects.toString(annotation.getConsequence(), "").split("[&,]"))
                .contains("stop_lost");
    }

    static String prepare(JsonNode context, JsonNode model, TranscriptConsequence annotation, ObjectNode out) {
        String issue = CodingTranscriptMapping.modelIssue(model, context);
        if (issue != null) return issue.replace("CODING_TRANSCRIPT_MODEL_", "PM4_TRANSCRIPT_MODEL_");
        if (!"GRCh38".equals(model.path("assembly").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !context.at("/variant/normalizedAllele").equals(context.at("/variant/evidenceQueryAllele")))
            return "PM4_NATIVE_GRCH38_REQUIRED";
        if (!model.path("transcript").asText().equals(annotation.getVersionedTranscriptId())
                || !model.path("geneSymbol").asText().equals(annotation.getGeneSymbol()))
            return "PM4_ANNOTATION_IDENTITY_MISMATCH";
        var allele = context.at("/variant/evidenceQueryAllele");
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        if (!ref.matches("[ACGT]") || !alt.matches("[ACGT]") || ref.equals(alt)
                || !"SNV".equals(context.at("/variant/variantType").asText())) return "PM4_STOP_LOSS_SIMPLE_SNV_REQUIRED";
        String consequence = Objects.toString(annotation.getConsequence(), "");
        if (consequence.contains("splice") || consequence.contains("intron")) return "PM4_SPLICE_BRANCH_UNSUPPORTED";
        if (!annotated(annotation) || consequence.contains("frameshift") || consequence.contains("stop_gained")
                || consequence.contains("start_lost")) return "PM4_STOP_LOSS_CONSEQUENCE_MISMATCH";
        int position = allele.path("position").asInt();
        int c = CodingTranscriptMapping.cdsPosition(model, position);
        String cds = model.path("cdsSequence").asText();
        if (c < cds.length() - 2 || c > cds.length()) return "PM4_STOP_LOSS_OUTSIDE_TERMINAL_CODON";
        String orientedRef = CodingTranscriptMapping.orientAllele(model, ref);
        String orientedAlt = CodingTranscriptMapping.orientAllele(model, alt);
        if (!cds.substring(c - 1, c).equals(orientedRef)) return "PM4_REFERENCE_MISMATCH";
        var exon = CodingTranscriptMapping.exonForGenomicInterval(model, position, position);
        if (exon == null || !(exon.path("ordinal").asInt() + "/" + model.path("exons").size()).equals(annotation.getExon()))
            return "PM4_EXON_ANNOTATION_MISMATCH";
        String hgvsc = model.path("transcript").asText() + ":c." + c + orientedRef + ">" + orientedAlt;
        if (!hgvsc.equals(annotation.getHgvsc())) return "PM4_HGVSC_SEQUENCE_MISMATCH";
        String changed = cds.substring(0, c - 1) + orientedAlt + cds.substring(c);
        String changedCodon = changed.substring(changed.length() - 3);
        char first = TruncatingSequence.translateCodon(changedCodon);
        if (first == '*') return "PM4_STOP_LOSS_NOT_CONFIRMED";
        out.put("branch", "STOP_LOSS_SNV").put("methodVersion", VERSION)
                .put("coordinateSystem", "TRANSCRIPT_CDS_RELATIVE_ONE_BASED")
                .put("cdsPosition", c).put("reference", ref).put("alternate", alt)
                .put("transcriptReference", orientedRef).put("transcriptAlternate", orientedAlt)
                .put("referenceStopCodon", cds.substring(cds.length() - 3)).put("alteredStopCodon", changedCodon)
                .put("firstExtensionResidue", String.valueOf(first)).put("referenceStopCdsPosition", cds.length() - 2)
                .put("referenceProteinLength", model.path("proteinLength").asInt())
                .put("referenceCdsLength", cds.length()).put("referenceCdsSha256", ProteinLengthChangeObservation.sha(cds))
                .put("referenceProteinSha256", ProteinLengthChangeObservation.sha(ProteinLengthChangeObservation.translate(cds)))
                .put("transcript", model.path("transcript").asText()).put("protein", model.path("protein").asText())
                .put("netNucleotideChange", 0).put("hgvsc", hgvsc).put("hgvscSequenceEquivalent", true)
                .put("independentRnaVerification", false).put("interpretation", "REFERENCE_BASED_PREDICTED_PROTEIN_EXTENSION")
                .put("maximumThreePrimeWindowBases", MAXIMUM_TAIL_BASES)
                .put("windowLimitBasis", "TECHNICAL_BOUND_NOT_A_SCIENTIFIC_LENGTH_THRESHOLD");
        return null;
    }

    /** Explicit verified-small-allele path; the original SNV entry point remains unchanged. */
    static String prepareComplex(JsonNode context, JsonNode model, TranscriptConsequence annotation, ObjectNode out) {
        String issue = ProteinLengthChangeObservation.complexEdit(context, model, annotation, out);
        if (issue != null) return issue;
        if (out.path("netNucleotideChange").asInt() % 3 != 0) return "PM4_FRAMESHIFT_UNSUPPORTED";
        String consequence = Objects.toString(annotation.getConsequence(), "");
        if (consequence.contains("splice") || consequence.contains("intron")) return "PM4_SPLICE_BRANCH_UNSUPPORTED";
        if (!annotated(annotation) || consequence.contains("frameshift") || consequence.contains("stop_gained")
                || consequence.contains("start_lost")) return "PM4_STOP_LOSS_CONSEQUENCE_MISMATCH";
        String cds = out.path("referenceCds").asText(), changed = out.path("alteredCds").asText();
        int start = out.path("referenceEditStart0").asInt(), end = out.path("referenceEditEnd0").asInt();
        if (end <= cds.length() - 3 || start >= cds.length()) return "PM4_STOP_LOSS_OUTSIDE_TERMINAL_CODON";
        if (start < 3 || !changed.startsWith("ATG")) return "PM4_START_AFFECTED_UNSUPPORTED";
        String referenceProtein = ProteinLengthChangeObservation.translate(cds);
        String changedProtein = ProteinLengthChangeObservation.translate(changed);
        int referenceLength = model.path("proteinLength").asInt();
        // An extension annotation cannot conceal a second protein substitution/deletion upstream.
        if (changedProtein.length() <= referenceLength
                || !changedProtein.startsWith(referenceProtein.substring(0, referenceLength)))
            return "PM4_STOP_LOSS_UPSTREAM_PROTEIN_CHANGE_UNSUPPORTED";
        if (changedProtein.indexOf('*') >= 0 && changedProtein.indexOf('*') <= referenceLength)
            return "PM4_STOP_LOSS_NOT_CONFIRMED";
        out.put("branch", "VERIFIED_STOP_LOSS_SMALL_ALLELE").put("methodVersion", "2.0.0")
                .put("referenceStopCodon", cds.substring(cds.length() - 3))
                .put("alteredStopCodon", changed.substring(referenceLength * 3, referenceLength * 3 + 3))
                .put("firstExtensionResidue", String.valueOf(changedProtein.charAt(referenceLength)))
                .put("referenceStopCdsPosition", cds.length() - 2).put("referenceProteinLength", referenceLength)
                .put("referenceProteinSha256", ProteinLengthChangeObservation.sha(referenceProtein))
                .put("independentRnaVerification", false).put("interpretation", "REFERENCE_BASED_PREDICTED_PROTEIN_EXTENSION")
                .put("maximumThreePrimeWindowBases", MAXIMUM_TAIL_BASES)
                .put("windowLimitBasis", "TECHNICAL_BOUND_NOT_A_SCIENTIFIC_LENGTH_THRESHOLD");
        out.remove("referenceCds");
        return null;
    }

    static String finish(JsonNode model, TranscriptConsequence annotation, String tail, int availableTailBases, ObjectNode out) {
        if ("VERIFIED_STOP_LOSS_SMALL_ALLELE".equals(out.path("branch").asText()))
            return finishComplex(model, annotation, tail, availableTailBases, out);
        out.put("availableThreePrimeBases", availableTailBases).put("inspectedThreePrimeBases", tail.length())
                .put("threePrimeWindowSha256", ProteinLengthChangeObservation.sha(tail))
                .put("threePrimeWindowComplete", tail.length() == availableTailBases);
        if (tail.isEmpty()) return "PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_UNAVAILABLE";
        if (!tail.matches("[ACGT]+") || tail.length() > MAXIMUM_TAIL_BASES || availableTailBases < tail.length())
            return "PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_INVALID";
        String extensionSequence = out.path("alteredStopCodon").asText() + tail;
        String extensionProtein = ProteinLengthChangeObservation.translate(extensionSequence);
        int added = extensionProtein.indexOf('*');
        if (added < 1) return tail.length() < availableTailBases ? "PM4_STOP_LOSS_NEW_STOP_BEYOND_TECHNICAL_WINDOW"
                : "PM4_STOP_LOSS_NEW_STOP_NOT_FOUND_IN_TRANSCRIPT";
        String cds = model.path("cdsSequence").asText();
        String alteredCds = cds.substring(0, cds.length() - 3) + extensionSequence.substring(0, (added + 1) * 3);
        String alteredProtein = ProteinLengthChangeObservation.translate(alteredCds);
        int referenceLength = model.path("proteinLength").asInt();
        out.put("netAminoAcidChange", added).put("alteredProteinLength", referenceLength + added)
                .put("alteredStopCdsPosition", cds.length() - 2 + added * 3)
                .put("alteredCdsLength", alteredCds.length()).put("alteredCdsSha256", ProteinLengthChangeObservation.sha(alteredCds))
                .put("alteredProteinSha256", ProteinLengthChangeObservation.sha(alteredProtein))
                .put("extensionProteinSha256", ProteinLengthChangeObservation.sha(extensionProtein.substring(0, added)))
                .put("newTerminationResolved", true);
        String protein = Objects.toString(annotation.getHgvsp(), "");
        if (!protein.isBlank()) {
            String prefix = model.path("protein").asText() + ":p.";
            if (!protein.startsWith(prefix)) return "PM4_HGVSP_UNSUPPORTED";
            String change = protein.substring(prefix.length());
            if (change.startsWith("(") && change.endsWith(")")) change = change.substring(1, change.length() - 1);
            var match = Pattern.compile("^(?:Ter|\\*)([1-9][0-9]{0,8})([A-Z][a-z]{2}|[A-Z])ext(?:Ter|\\*)([1-9][0-9]{0,8}|\\?)$").matcher(change);
            if (!match.matches()) return "PM4_HGVSP_UNSUPPORTED";
            char first = extensionProtein.charAt(0);
            if (Integer.parseInt(match.group(1)) != referenceLength + 1
                    || !(match.group(2).equals(String.valueOf(first)) || match.group(2).equals(TruncatingSequence.aminoName(first)))
                    || !"?".equals(match.group(3)) && Integer.parseInt(match.group(3)) != added)
                return "PM4_HGVSP_SEQUENCE_MISMATCH";
            out.put("hgvsp", protein).put("hgvspIdentityConsistent", true)
                    .put("hgvspExtensionLengthSpecified", !"?".equals(match.group(3)))
                    .put("hgvspSequenceEquivalent", !"?".equals(match.group(3)));
        }
        out.put("predictedHgvsp", model.path("protein").asText() + ":p.(Ter" + (referenceLength + 1)
                + TruncatingSequence.aminoName(extensionProtein.charAt(0)) + "extTer" + added + ")")
                .put("sequenceDerivationStatus", "REFERENCE_CDS_THREE_PRIME_SEQUENCE_AND_STORED_ANNOTATION_AGREE");
        return null;
    }

    private static String finishComplex(JsonNode model, TranscriptConsequence annotation, String tail, int availableTailBases, ObjectNode out) {
        String changed = out.path("alteredCds").asText();
        out.remove("alteredCds");
        out.put("availableThreePrimeBases", availableTailBases).put("inspectedThreePrimeBases", tail.length())
                .put("threePrimeWindowSha256", ProteinLengthChangeObservation.sha(tail))
                .put("threePrimeWindowComplete", tail.length() == availableTailBases);
        if (tail.isEmpty() && ProteinLengthChangeObservation.translate(changed).indexOf('*') < 0)
            return "PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_UNAVAILABLE";
        if (!tail.matches("[ACGT]*") || tail.length() > MAXIMUM_TAIL_BASES || availableTailBases < tail.length())
            return "PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_INVALID";
        String translated = ProteinLengthChangeObservation.translate(changed + tail);
        int stop = translated.indexOf('*'), referenceLength = model.path("proteinLength").asInt();
        if (stop < 0) return tail.length() < availableTailBases ? "PM4_STOP_LOSS_NEW_STOP_BEYOND_TECHNICAL_WINDOW"
                : "PM4_STOP_LOSS_NEW_STOP_NOT_FOUND_IN_TRANSCRIPT";
        if (stop <= referenceLength) return "PM4_STOP_LOSS_NOT_CONFIRMED";
        String extension = translated.substring(referenceLength, stop);
        int added = extension.length();
        String alteredCds = (changed + tail).substring(0, (stop + 1) * 3);
        out.put("netAminoAcidChange", added).put("alteredProteinLength", stop).put("alteredStopCdsPosition", stop * 3 + 1)
                .put("alteredCdsLength", alteredCds.length()).put("alteredCdsSha256", ProteinLengthChangeObservation.sha(alteredCds))
                .put("alteredProteinSha256", ProteinLengthChangeObservation.sha(translated.substring(0, stop + 1)))
                .put("extensionProteinSha256", ProteinLengthChangeObservation.sha(extension)).put("newTerminationResolved", true);
        String protein = Objects.toString(annotation.getHgvsp(), "");
        if (!protein.isBlank()) {
            String prefix = model.path("protein").asText() + ":p.";
            if (!protein.startsWith(prefix)) return "PM4_HGVSP_UNSUPPORTED";
            String value = protein.substring(prefix.length());
            if (value.startsWith("(") && value.endsWith(")")) value = value.substring(1, value.length() - 1);
            var match = Pattern.compile("^(?:Ter|\\*)([1-9][0-9]{0,8})([A-Z][a-z]{2}|[A-Z])ext(?:Ter|\\*)([1-9][0-9]{0,8}|\\?)$").matcher(value);
            if (!match.matches()) return "PM4_HGVSP_UNSUPPORTED";
            if (Integer.parseInt(match.group(1)) != referenceLength + 1
                    || !(match.group(2).equals(String.valueOf(extension.charAt(0))) || match.group(2).equals(TruncatingSequence.aminoName(extension.charAt(0))))
                    || !"?".equals(match.group(3)) && Integer.parseInt(match.group(3)) != added) return "PM4_HGVSP_SEQUENCE_MISMATCH";
            out.put("hgvsp", protein).put("hgvspIdentityConsistent", true)
                    .put("hgvspExtensionLengthSpecified", !"?".equals(match.group(3)))
                    .put("hgvspSequenceEquivalent", !"?".equals(match.group(3)));
        }
        out.put("predictedHgvsp", model.path("protein").asText() + ":p.(Ter" + (referenceLength + 1)
                + TruncatingSequence.aminoName(extension.charAt(0)) + "extTer" + added + ")")
                .put("sequenceDerivationStatus", "REFERENCE_CDS_THREE_PRIME_SEQUENCE_AND_STORED_ANNOTATION_AGREE");
        return null;
    }
}
