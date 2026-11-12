package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.regex.Pattern;
import org.nbscn.varvis.variantanalysis.domain.BoundedSequenceDeletion;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Sequence facts only, with no disease policy or evidence strength. Coordinates are reference CDS boundaries. */
final class ProteinLengthChangeObservation {
    // Existing complete-context / ReviewedRuleContextPreparation engineering limit, including VCF anchor.
    static final int MAXIMUM_ALLELE_LENGTH = 50;
    private ProteinLengthChangeObservation() { }

    static final String FULL_RNA_VERSION = "1.0.0";

    /** Exact protein effects from an opaque original replay, with no criterion adoption.
     * No genomic/HGVS edit is manufactured from an RNA product. */
    static String resolveFullRna(ObjectMapper mapper, JsonNode context,
            @org.springframework.lang.Nullable Pvs1SourceQualification.SourceBoundFullRna source, ObjectNode out) {
        out.put("schemaVersion", "full-rna-protein-length-observation-v1").put("methodVersion", FULL_RNA_VERSION)
                .put("coordinateSystem", "SOURCE_BOUND_RNA_AND_REFERENCE_PROTEIN")
                .put("criterionQualificationGranted", false).put("functionalQualificationGranted", false);
        if (source == null) return "FULL_RNA_LENGTH_SOURCE_REQUIRED";
        var facts = source.facts().snapshot();var material = source.result().reference().material();
        if (!new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(context).equals(material.contextSha256()))
            return "FULL_RNA_LENGTH_CONTEXT_BINDING_MISMATCH";
        out.put("sourceOriginalSha256", source.originalSha256()).put("factsSha256", source.facts().canonicalSha256())
                .put("mechanicalEffectSha256", source.facts().mechanicalEffectSha256())
                .put("transcript", material.model().path("transcript").asText()).put("protein", material.model().path("protein").asText());
        out.set("sourceReplay", source.provenance());out.set("binding", facts.path("binding").deepCopy());
        out.set("nativeStart", facts.path("nativeStart").deepCopy());out.set("nativeStop", facts.path("nativeStop").deepCopy());
        out.set("firstStop", facts.path("firstStop").deepCopy());out.set("codingEffects", facts.path("codingEffects").deepCopy());
        for (String key : List.of("referenceProteinLength", "alteredProteinLength", "netProteinLengthChange"))
            out.set(key, facts.path(key).deepCopy());
        if (!source.facts().nativeStartRetained()) return "FULL_RNA_LENGTH_INITIATION_UNRESOLVED";
        if (!facts.path("alteredProteinLength").isIntegralNumber()) return "FULL_RNA_LENGTH_TERMINATION_UNRESOLVED";
        var coding = facts.path("codingEffects");
        String ref = coding.path("referenceProteinThroughTermination").asText();
        String alt = facts.path("alteredProteinThroughFirstTermination").asText();
        int length = material.model().path("proteinLength").asInt();
        if (!ref.equals(translate(material.model().path("cdsSequence").asText())) || ref.length() != length + 1
                || !ref.endsWith("*") || !alt.endsWith("*") || alt.indexOf('*') != alt.length() - 1)
            return "FULL_RNA_LENGTH_PROTEIN_BINDING_MISMATCH";
        int delta = alt.length() - ref.length();
        out.put("netAminoAcidChange", delta).put("referenceProtein", ref).put("alteredProtein", alt)
                .put("referenceProteinSha256", sha(ref)).put("alteredProteinSha256", sha(alt));
        if (coding.path("localInFrameLengthChangeResolved").asBoolean()) {
            if (delta == 0) return "FULL_RNA_NO_NET_PROTEIN_LENGTH_CHANGE";
            out.put("branch", "SOURCE_BOUND_RNA_INFRAME_LENGTH_CHANGE")
                    .put("sequenceDerivationStatus", "ACTUAL_RNA_CODING_FRAME_AND_PROTEIN_AGREE");
        } else if (!facts.at("/nativeStop/retainedContiguously").asBoolean() && delta > 0) {
            if (!alt.startsWith(ref.substring(0, length))) return "FULL_RNA_STOP_LOSS_UPSTREAM_PROTEIN_CHANGE";
            out.put("branch", "SOURCE_BOUND_RNA_STOP_LOSS_EXTENSION").put("newTerminationResolved", true)
                    .put("sequenceDerivationStatus", "ACTUAL_RNA_NATIVE_STOP_REMOVAL_AND_EXTENSION_AGREE");
        } else return "FULL_RNA_LENGTH_REQUIRES_TRUNCATING_OR_OTHER_MECHANISM";
        out.set("touchedReferenceResidueRanges", coding.path("touchedReferenceResidueRanges").deepCopy());
        out.set("proteinEffectReferenceResidueRanges", coding.path("proteinEffectReferenceResidueRanges").deepCopy());
        out.set("proteinEffectAlteredResidueRanges", coding.path("proteinEffectAlteredResidueRanges").deepCopy());
        out.set("wholeDeletedReferenceResidueRanges", coding.path("wholeDeletedReferenceResidueRanges").deepCopy());
        return null;
    }

    /** Explicit complex-small-allele path. Legacy reviewed evaluators continue calling resolve. */
    static String resolveComplex(JsonNode context, JsonNode model, TranscriptConsequence annotation, ObjectNode out) {
        String issue = complexEdit(context, model, annotation, out);
        if (issue != null) return issue;
        int delta = out.path("netNucleotideChange").asInt();
        if (delta % 3 != 0) return "PM4_FRAMESHIFT_UNSUPPORTED";
        if (delta == 0) return "PM4_NO_NET_LENGTH_CHANGE";
        String consequence = Objects.toString(annotation.getConsequence(), "");
        if (consequence.contains("splice") || consequence.contains("intron")) return "PM4_SPLICE_BRANCH_UNSUPPORTED";
        String cds = out.path("referenceCds").asText(), changed = out.path("alteredCds").asText();
        String referenceProtein = translate(cds), changedProtein = translate(changed);
        int start = out.path("referenceEditStart0").asInt(), end = out.path("referenceEditEnd0").asInt();
        if (start < 3 || !changed.startsWith("ATG")) return "PM4_START_AFFECTED_UNSUPPORTED";
        if (end > cds.length() - 3 || start > cds.length() - 3 || !changed.endsWith(cds.substring(cds.length() - 3)))
            return "PM4_STOP_LOSS_UNSUPPORTED";
        if (changedProtein.indexOf('*') != changedProtein.length() - 1) return "PM4_PREMATURE_STOP_UNSUPPORTED";
        if (consequence.contains("frameshift") || consequence.contains("stop_") || consequence.contains("start_lost"))
            return "PM4_CONSEQUENCE_SEQUENCE_MISMATCH";
        out.put("branch", "VERIFIED_INFRAME_SMALL_ALLELE").put("netAminoAcidChange", delta / 3)
                .put("referenceProteinLength", referenceProtein.length() - 1).put("alteredProteinLength", changedProtein.length() - 1)
                .put("referenceStopCdsPosition", cds.length() - 2).put("alteredStopCdsPosition", changed.length() - 2)
                .put("referenceProtein", referenceProtein).put("alteredProtein", changedProtein)
                .put("referenceProteinSha256", sha(referenceProtein)).put("alteredProteinSha256", sha(changedProtein));
        if (annotation.getHgvsp() != null && !annotation.getHgvsp().isBlank()) {
            issue = proteinIssue(annotation.getHgvsp(), model.path("protein").asText(), referenceProtein, changedProtein);
            if (issue != null) return issue;
            out.put("hgvspSequenceEquivalent", true).put("hgvsp", annotation.getHgvsp());
        }
        out.put("sequenceDerivationStatus", "GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE");
        return null;
    }

    /** Technical facts shared by the explicit length and stop-loss paths; no clinical adoption. */
    static String complexEdit(JsonNode context, JsonNode model, TranscriptConsequence annotation, ObjectNode out) {
        String issue = CodingTranscriptMapping.modelIssue(model, context);
        if (issue != null) return issue.replace("CODING_TRANSCRIPT_MODEL_", "PM4_TRANSCRIPT_MODEL_");
        if (!"GRCh38".equals(model.path("assembly").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !context.at("/variant/normalizedAllele").equals(context.at("/variant/evidenceQueryAllele")))
            return "PM4_NATIVE_GRCH38_REQUIRED";
        if (!model.path("transcript").asText().equals(annotation.getVersionedTranscriptId())
                || !model.path("geneSymbol").asText().equals(annotation.getGeneSymbol())) return "PM4_ANNOTATION_IDENTITY_MISMATCH";
        var allele = context.at("/variant/evidenceQueryAllele");
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        if (!ref.matches("[ACGT]{1,50}") || !alt.matches("[ACGT]{1,50}")) return "PM4_SHORT_ALLELE_REQUIRED";
        if (ref.equals(alt)) return "PM4_NO_NET_LENGTH_CHANGE";
        boolean insertion = ref.length() == 1 && alt.length() > 1 && alt.startsWith(ref);
        String type = ref.length() == alt.length() ? ref.length() == 1 ? "SNV" : "MNV"
                : ref.length() == 1 ? "SMALL_INSERTION" : alt.length() == 1 ? "SMALL_DELETION" : "SMALL_DELINS";
        if (!type.equals(context.at("/variant/variantType").asText())) return "PM4_VARIANT_TYPE_MISMATCH";
        var mapper = new ObjectMapper(); var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var reference = CodingTranscriptEdits.bind(mapper, context, model, adapter.canonicalSha256(model), sha(model.path("cdsSequence").asText()));
        CodingTranscriptEdits.Edit edit;
        try { edit = CodingTranscriptEdits.projectAllele(reference, allele, adapter.canonicalSha256(allele)); }
        catch (IllegalArgumentException invalid) { return editIssue(invalid); }
        CodingTranscriptEdits.Edit hgvs;
        try { hgvs = CodingTranscriptEdits.codingHgvsEdit(reference, Objects.toString(annotation.getHgvsc(), ""), sha(Objects.toString(annotation.getHgvsc(), ""))); }
        catch (IllegalArgumentException invalid) { return "PM4_HGVSC_UNSUPPORTED_OR_INVALID"; }
        if (!CodingTranscriptEdits.sequenceEquivalent(reference, edit, hgvs)) return "PM4_HGVSC_SEQUENCE_MISMATCH";
        var changed = CodingTranscriptEdits.apply(reference, List.of(edit));
        String cds = reference.cds(), altered = changed.alteredCds();
        int start = edit.referenceEditStart0(), end = edit.referenceEditEnd0();
        int first = 0, last = 0;
        for (var exon : model.path("exons")) {
            int lo = exon.path("cdsStart").asInt(), hi = exon.path("cdsEnd").asInt();
            boolean overlaps = start == end ? edit.insertionExonOrdinal() == exon.path("ordinal").asInt()
                    : start < hi && end >= lo;
            if (overlaps) { if (first == 0) first = exon.path("ordinal").asInt(); last = exon.path("ordinal").asInt(); }
        }
        String exonText = (first == last ? String.valueOf(first) : first + "-" + last) + "/" + model.path("exons").size();
        if (first == 0 || !exonText.equals(annotation.getExon())) return "PM4_EXON_ANNOTATION_MISMATCH";
        if (first != last || !sameExon(new Edit(hgvs.referenceEditStart0(), hgvs.referenceEditEnd0(), hgvs.insertedSequence()),
                model.path("exons").path(first - 1))) return "PM4_HGVSC_EXON_BOUNDARY_UNSUPPORTED";
        out.put("coordinateSystem", "REFERENCE_CDS_ZERO_BASED_HALF_OPEN")
                .put("referenceEditStart0", start).put("referenceEditEnd0", end).put("insertedSequence", edit.insertedSequence())
                .put("deletedSequence", cds.substring(start, end)).put("exonOrdinal", first).put("lastExonOrdinal", last)
                .put("genomicCoverageStart", allele.path("position").asInt())
                .put("genomicCoverageEnd", Math.addExact(allele.path("position").asInt(), insertion ? 1 : ref.length() - 1))
                .put("reference", ref).put("alternate", alt).put("transcriptReference", CodingTranscriptMapping.orientAllele(model, ref))
                .put("transcriptAlternate", CodingTranscriptMapping.orientAllele(model, alt))
                .put("referenceCds", cds).put("alteredCds", altered).put("referenceCdsLength", cds.length()).put("alteredCdsLength", altered.length())
                .put("referenceCdsSha256", sha(cds)).put("alteredCdsSha256", sha(altered)).put("netNucleotideChange", changed.delta())
                .put("transcript", model.path("transcript").asText()).put("protein", model.path("protein").asText())
                .put("maximumAlleleLengthIncludingAnchor", MAXIMUM_ALLELE_LENGTH)
                .put("lengthLimitBasis", "EXISTING_COMPLETE_CONTEXT_ENGINEERING_LIMIT_NOT_PM4_SCIENTIFIC_THRESHOLD")
                .put("hgvsc", annotation.getHgvsc()).put("hgvscSequenceEquivalent", true).put("editDerivation", "CODING_TRANSCRIPT_EDITS_S0");
        return null;
    }

    private static String editIssue(IllegalArgumentException invalid) {
        String reason = Objects.toString(invalid.getMessage(), "");
        if (reason.contains("REFERENCE_MISMATCH")) return "PM4_REFERENCE_MISMATCH";
        return reason.matches("[A-Z][A-Z0-9_]{0,160}") ? "PM4_" + reason : "PM4_COMPLEX_EDIT_INVALID";
    }

    static String resolve(JsonNode context, JsonNode model, TranscriptConsequence annotation, ObjectNode out) {
        return resolve(context, model, annotation, out, false);
    }

    static String resolveDeletionForLocation(JsonNode context, JsonNode model, TranscriptConsequence annotation, ObjectNode out) {
        if (!"SMALL_DELETION".equals(context.at("/variant/variantType").asText())) return "PM1_EXACT_DELETION_REQUIRED";
        return resolve(context, model, annotation, out, true);
    }

    private static String resolve(JsonNode context, JsonNode model, TranscriptConsequence annotation, ObjectNode out,
            boolean deletionLocation) {
        String issue = CodingTranscriptMapping.modelIssue(model, context);
        if (issue != null) return issue.replace("CODING_TRANSCRIPT_MODEL_", "PM4_TRANSCRIPT_MODEL_");
        if (!"GRCh38".equals(model.path("assembly").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !context.at("/variant/normalizedAllele").equals(context.at("/variant/evidenceQueryAllele")))
            return "PM4_NATIVE_GRCH38_REQUIRED";
        if (!model.path("transcript").asText().equals(annotation.getVersionedTranscriptId())
                || !model.path("geneSymbol").asText().equals(annotation.getGeneSymbol()))
            return "PM4_ANNOTATION_IDENTITY_MISMATCH";
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        boolean extended = deletionLocation && BoundedSequenceDeletion.NORMALIZATION_METHOD.equals(context.at("/variant/normalization/method").asText())
                && BoundedSequenceDeletion.extended(ref, alt);
        if (!extended && (!ref.matches("[ACGT]{1,50}") || !alt.matches("[ACGT]{1,50}"))) return "PM4_SHORT_ALLELE_REQUIRED";
        if (ref.equals(alt)) return "PM4_NO_NET_LENGTH_CHANGE";
        boolean insertion = ref.length() == 1 && alt.length() > 1 && alt.startsWith(ref);
        boolean deletion = alt.length() == 1 && ref.length() > 1 && ref.startsWith(alt);
        if (!insertion && !deletion) return "PM4_COMPLEX_ALLELE_UNSUPPORTED";
        if (!(insertion ? "SMALL_INSERTION" : "SMALL_DELETION").equals(context.at("/variant/variantType").asText()))
            return "PM4_VARIANT_TYPE_MISMATCH";
        int delta = alt.length() - ref.length();
        if (delta % 3 != 0) return "PM4_FRAMESHIFT_UNSUPPORTED";
        String consequence = Objects.toString(annotation.getConsequence(), "");
        if (consequence.contains("splice") || consequence.contains("intron")) return "PM4_SPLICE_BRANCH_UNSUPPORTED";
        int genomic = allele.path("position").asInt();
        int end = Math.addExact(genomic, ref.length() - 1);
        // Include both genomic neighbors of an insertion, as well as the verified VCF anchor.
        int coveredEnd = insertion ? Math.addExact(genomic, 1) : end;
        JsonNode exon = CodingTranscriptMapping.exonForGenomicInterval(model, genomic, coveredEnd);
        if (exon == null) return "PM4_EXON_BOUNDARY_UNSUPPORTED";
        int strand = model.path("strand").asInt();
        int c = CodingTranscriptMapping.cdsPosition(model, strand == 1 ? genomic : end);
        String cds = model.path("cdsSequence").asText();
        if (c < 1 || c - 1 + ref.length() > cds.length()
                || CodingTranscriptMapping.cdsPosition(model, coveredEnd) == 0)
            return "PM4_CODING_BOUNDARY_UNSUPPORTED";
        String orientedRef = CodingTranscriptMapping.orientAllele(model, ref);
        String orientedAlt = CodingTranscriptMapping.orientAllele(model, alt);
        if (!cds.substring(c - 1, c - 1 + ref.length()).equals(orientedRef)) return "PM4_REFERENCE_MISMATCH";
        if (!(exon.path("ordinal").asInt() + "/" + model.path("exons").size()).equals(annotation.getExon()))
            return "PM4_EXON_ANNOTATION_MISMATCH";
        int start = strand == 1 ? c : c - 1;
        int finish = start + ref.length() - 1;
        String inserted = strand == 1 ? orientedAlt.substring(1) : orientedAlt.substring(0, orientedAlt.length() - 1);
        String changed = cds.substring(0, start) + inserted + cds.substring(finish);
        String referenceProtein = translate(cds), changedProtein = translate(changed);
        out.put("branch", insertion ? "SIMPLE_INFRAME_INSERTION" : "SIMPLE_INFRAME_DELETION")
                .put("coordinateSystem", "REFERENCE_CDS_ZERO_BASED_HALF_OPEN")
                .put("referenceEditStart0", start).put("referenceEditEnd0", finish)
                .put("genomicCoverageStart", genomic).put("genomicCoverageEnd", coveredEnd)
                .put("reference", ref).put("alternate", alt).put("transcriptReference", orientedRef)
                .put("transcriptAlternate", orientedAlt).put("insertedSequence", inserted)
                .put("deletedSequence", cds.substring(start, finish)).put("exonOrdinal", exon.path("ordinal").asInt())
                .put("referenceCdsLength", cds.length()).put("alteredCdsLength", changed.length())
                .put("referenceCdsSha256", sha(cds)).put("alteredCdsSha256", sha(changed))
                .put("referenceProteinSha256", sha(referenceProtein)).put("alteredProteinSha256", sha(changedProtein))
                .put("referenceProteinLength", referenceProtein.length() - 1)
                .put("alteredProteinLength", changedProtein.endsWith("*") ? changedProtein.length() - 1 : changedProtein.length())
                .put("netNucleotideChange", delta).put("netAminoAcidChange", delta / 3)
                .put("referenceStopCdsPosition", cds.length() - 2)
                .put("alteredStopCdsPosition", changedProtein.indexOf('*') < 0 ? -1 : changedProtein.indexOf('*') * 3 + 1)
                .put("referenceProtein", referenceProtein).put("alteredProtein", changedProtein)
                .put("referenceCds", cds).put("alteredCds", changed)
                .put("transcript", model.path("transcript").asText()).put("protein", model.path("protein").asText())
                .put("maximumAlleleLengthIncludingAnchor", extended ? BoundedSequenceDeletion.MAX_ANCHORED_LENGTH : MAXIMUM_ALLELE_LENGTH)
                .put("lengthLimitBasis", extended ? "BOUNDED_NATIVE_PM1_DELETION" : "EXISTING_COMPLETE_CONTEXT_ENGINEERING_LIMIT_NOT_PM4_SCIENTIFIC_THRESHOLD");
        if (start < 3 || !changed.startsWith("ATG")) return "PM4_START_AFFECTED_UNSUPPORTED";
        if (finish > cds.length() - 3 || start > cds.length() - 3 || !changed.endsWith(cds.substring(cds.length() - 3)))
            return "PM4_STOP_LOSS_UNSUPPORTED";
        if (changedProtein.indexOf('*') != changedProtein.length() - 1) return "PM4_PREMATURE_STOP_UNSUPPORTED";
        if (consequence.contains("frameshift") || consequence.contains("stop_") || consequence.contains("start_lost"))
            return "PM4_CONSEQUENCE_SEQUENCE_MISMATCH";
        Edit hgvs = codingEdit(Objects.toString(annotation.getHgvsc(), ""), model.path("transcript").asText(), cds);
        if (hgvs == null) return "PM4_HGVSC_UNSUPPORTED_OR_INVALID";
        if (!sameExon(hgvs, exon)) return "PM4_HGVSC_EXON_BOUNDARY_UNSUPPORTED";
        if (!changed.equals(cds.substring(0, hgvs.start) + hgvs.inserted + cds.substring(hgvs.end)))
            return "PM4_HGVSC_SEQUENCE_MISMATCH";
        out.put("hgvscSequenceEquivalent", true).put("hgvsc", annotation.getHgvsc());
        if (annotation.getHgvsp() != null && !annotation.getHgvsp().isBlank()) {
            String proteinIssue = proteinIssue(annotation.getHgvsp(), model.path("protein").asText(), referenceProtein, changedProtein);
            if (proteinIssue != null) return proteinIssue;
            out.put("hgvspSequenceEquivalent", true).put("hgvsp", annotation.getHgvsp());
        }
        out.put("sequenceDerivationStatus", "GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE");
        return null;
    }

    private static Edit codingEdit(String value, String transcript, String cds) {
        var edit = Pattern.compile("^" + Pattern.quote(transcript) + ":c\\.([1-9][0-9]{0,8})(?:_([1-9][0-9]{0,8}))?(del|dup)([ACGT]*)$").matcher(value);
        if (edit.matches()) {
            int first = Integer.parseInt(edit.group(1)), last = edit.group(2) == null ? first : Integer.parseInt(edit.group(2));
            if (first > last || last > cds.length()) return null;
            String bases = cds.substring(first - 1, last);
            if (!edit.group(4).isEmpty() && !bases.equals(edit.group(4))) return null;
            return "del".equals(edit.group(3)) ? new Edit(first - 1, last, "") : new Edit(last, last, bases);
        }
        edit = Pattern.compile("^" + Pattern.quote(transcript) + ":c\\.([1-9][0-9]{0,8})_([1-9][0-9]{0,8})ins([ACGT]+)$").matcher(value);
        if (!edit.matches()) return null;
        int left = Integer.parseInt(edit.group(1)), right = Integer.parseInt(edit.group(2));
        return right == left + 1 && right <= cds.length() ? new Edit(left, left, edit.group(3)) : null;
    }

    private static boolean sameExon(Edit edit, JsonNode exon) {
        int lo = exon.path("cdsStart").asInt(), hi = exon.path("cdsEnd").asInt();
        return edit.start == edit.end ? edit.start >= lo && edit.start < hi : edit.start + 1 >= lo && edit.end <= hi;
    }

    /** Bounded explicit three-letter del/dup/ins/delins; validate residues and complete sequence equivalence. */
    private static String proteinIssue(String value, String proteinId, String original, String changed) {
        var m = Pattern.compile("^" + Pattern.quote(proteinId) + ":p\\.([A-Z][a-z]{2})([1-9][0-9]{0,8})(?:_([A-Z][a-z]{2})([1-9][0-9]{0,8}))?(delins|del|dup|ins)((?:[A-Z][a-z]{2})*)$").matcher(value);
        if (!m.matches()) return "PM4_HGVSP_UNSUPPORTED";
        int first = Integer.parseInt(m.group(2)), last = m.group(4) == null ? first : Integer.parseInt(m.group(4));
        if (first > last || last >= original.length() || !TruncatingSequence.aminoName(original.charAt(first - 1)).equals(m.group(1))
                || m.group(3) != null && !TruncatingSequence.aminoName(original.charAt(last - 1)).equals(m.group(3)))
            return "PM4_HGVSP_SEQUENCE_MISMATCH";
        StringBuilder inserted = new StringBuilder();
        for (int i = 0; i < m.group(6).length(); i += 3) {
            String name = m.group(6).substring(i, i + 3); char found = '?';
            for (char aa : "ACDEFGHIKLMNPQRSTVWY".toCharArray()) if (TruncatingSequence.aminoName(aa).equals(name)) found = aa;
            if (found == '?') return "PM4_HGVSP_UNSUPPORTED";
            inserted.append(found);
        }
        String reconstructed;
        switch (m.group(5)) {
            case "del":
                if (!inserted.isEmpty()) return "PM4_HGVSP_UNSUPPORTED";
                reconstructed = original.substring(0, first - 1) + original.substring(last); break;
            case "dup":
                if (!inserted.isEmpty()) return "PM4_HGVSP_UNSUPPORTED";
                reconstructed = original.substring(0, last) + original.substring(first - 1, last) + original.substring(last); break;
            case "ins":
                if (last != first + 1 || inserted.isEmpty()) return "PM4_HGVSP_UNSUPPORTED";
                reconstructed = original.substring(0, first) + inserted + original.substring(first); break;
            default:
                if (inserted.isEmpty()) return "PM4_HGVSP_UNSUPPORTED";
                reconstructed = original.substring(0, first - 1) + inserted + original.substring(last);
        }
        return reconstructed.equals(changed) ? null : "PM4_HGVSP_SEQUENCE_MISMATCH";
    }

    static String translate(String cds) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i + 3 <= cds.length(); i += 3) value.append(TruncatingSequence.translateCodon(cds.substring(i, i + 3)));
        return value.toString();
    }
    static String sha(String sequence) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sequence.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Edit(int start, int end, String inserted) { }
}
