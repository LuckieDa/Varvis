package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, strand-aware CDS derivation. All scientific branch limits are supplied by the caller. */
final class TruncatingFrameshiftResolver {
    // Deliberately bounded HGVS grammar. Equivalence is established by the entire altered CDS,
    // never by accepting a regex match or by comparing VCF and HGVS coordinates directly.
    private final Pattern deletionOrDuplication;
    private final Pattern insertionPattern;
    private static final Set<String> STOPS = Set.of("TAA", "TAG", "TGA");
    private final JsonNode model;
    private final JsonNode policy;

    private final String transcript;
    private final String exonRelevance;
    private final String mechanism;
    private final boolean complexAlleles;

    TruncatingFrameshiftResolver(JsonNode model, JsonNode policy, String transcript,
                                String exonRelevance, String mechanism) {
        this(model, policy, transcript, exonRelevance, mechanism, false);
    }

    /** Explicit mechanical opt-in. Scientific policy and clinical qualification remain caller-owned. */
    TruncatingFrameshiftResolver(JsonNode model, JsonNode policy, String transcript,
                                String exonRelevance, String mechanism, boolean complexAlleles) {
        this.model = model;
        this.complexAlleles = complexAlleles;
        this.policy = policy;
        this.transcript = transcript;
        this.exonRelevance = exonRelevance;
        this.mechanism = mechanism;
        String prefix = "^" + Pattern.quote(transcript) + ":c\\.";
        deletionOrDuplication = Pattern.compile(prefix + "([1-9][0-9]*)(?:_([1-9][0-9]*))?(del|dup)([ACGT]*)$");
        insertionPattern = Pattern.compile(prefix + "([1-9][0-9]*)_([1-9][0-9]*)ins([ACGT]+)$");
    }

    String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode input) {
        return complexAlleles ? resolveComplex(context, tx, input) : resolveSimple(context, tx, input, false);
    }

    String deriveGenomicStop(JsonNode context, TranscriptConsequence tx, ObjectNode input) {
        return resolveSimple(context, tx, input, true);
    }

    private String resolveSimple(JsonNode context, TranscriptConsequence tx, ObjectNode input, boolean sequenceOnly) {
        int strand = model.path("strand").asInt();
        if (strand != 1 && strand != -1) return "PVS1_TRANSCRIPT_NOT_SUPPORTED";
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        String ref = allele.path("reference").asText();
        String alt = allele.path("alternate").asText();
        boolean insertion = ref.length() == 1 && alt.length() > 1 && alt.startsWith(ref);
        boolean deletion = alt.length() == 1 && ref.length() > 1 && ref.startsWith(alt);
        int limit = policy.path("frameshift").path("maximumAlleleLengthIncludingAnchor").asInt();
        if (!(insertion || deletion) || Math.max(ref.length(), alt.length()) > limit
                || ref.charAt(ref.length() - 1) == alt.charAt(alt.length() - 1)
                || !(insertion ? "SMALL_INSERTION" : "SMALL_DELETION")
                        .equals(context.at("/variant/variantType").asText()))
            return "PVS1_SIMPLE_SHORT_INDEL_REQUIRED";
        int delta = alt.length() - ref.length();
        input.put("variantMechanism", "FRAMESHIFT").put("netLengthChange", delta)
                .put("coordinateSystem", "ALTERED_CDS");
        if (delta % 3 == 0) return "PVS1_FRAMESHIFT_NOT_CONFIRMED";

        int genomicStart = allele.path("position").asInt();
        int genomicEnd = Math.addExact(genomicStart, ref.length() - 1);
        JsonNode exon = referenceExon(genomicStart, genomicEnd);
        if (exon == null) return "PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED";
        int c = CodingTranscriptMapping.cdsPosition(model, strand == 1 ? genomicStart : genomicEnd);
        String transcriptRef = CodingTranscriptMapping.orientAllele(model, ref);
        String transcriptAlt = CodingTranscriptMapping.orientAllele(model, alt);
        String cds = model.path("cdsSequence").asText();
        if (c < 1 || c - 1 + ref.length() > cds.length() - 3)
            return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        if (!cds.substring(c - 1, c - 1 + ref.length()).equals(transcriptRef))
            return "PVS1_CDS_REFERENCE_MISMATCH";
        // The VCF anchor is verified but not replaced. Coordinates below are zero-based
        // boundaries in the reference CDS, making insertions an empty replacement interval.
        int editStart = strand == 1 ? c : c - 1;
        int editEnd = editStart + ref.length() - 1;
        if (insertion && genomicStart + 1 > exon.path("genomicEnd").asInt())
            return "PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED";
        int ordinal = exon.path("ordinal").asInt();
        if (!(ordinal + "/" + model.path("exons").size()).equals(tx.getExon()))
            return "PVS1_EXON_STRUCTURE_MISMATCH";
        String inserted = strand == 1 ? transcriptAlt.substring(1) : transcriptAlt.substring(0, transcriptAlt.length() - 1);
        String changed = cds.substring(0, editStart) + inserted + cds.substring(editEnd);
        input.put("cdsPosition", editStart + 1).put("anchorReferenceCdsPosition", strand == 1 ? c : c + ref.length() - 1)
                .put("referenceEditStart0", editStart).put("referenceEditEnd0", editEnd)
                .put("deletedSequence", strand == 1 ? transcriptRef.substring(1) : transcriptRef.substring(0, transcriptRef.length() - 1)).put("insertedSequence", inserted)
                .put("exonOrdinal", ordinal).put("exonTotal", model.path("exons").size())
                .put(transcript.startsWith("ENST") ? "ensemblTranscript" : "selectedTranscript", transcript)
                .put("refseqTranscript", model.path("refseqTranscript").asText())
                .put("referenceCdsSha256", sha(cds)).put("alteredCdsSequence", changed)
                .put("alteredCdsSha256", sha(changed)).put("alteredCdsLength", changed.length());

        HgvsEdit hgvs = codingEdit(Objects.toString(tx.getHgvsc(), ""), cds);
        if (hgvs == null) return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        if (!sameCodingExon(hgvs.start(), hgvs.end(), exon))
            return "PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED";
        String hgvsChanged = cds.substring(0, hgvs.start()) + hgvs.inserted() + cds.substring(hgvs.end());
        if (!changed.equals(hgvsChanged)) return "PVS1_HGVSC_SEQUENCE_MISMATCH";
        input.put("hgvscSequenceEquivalent", true).put("hgvscReferenceEditStart0", hgvs.start())
                .put("hgvscReferenceEditEnd0", hgvs.end()).put("hgvscInsertedSequence", hgvs.inserted());

        return resolveProtein(tx, input, cds, changed, exon, editStart, editEnd, inserted, delta, null, sequenceOnly);
    }

    private String resolveComplex(JsonNode context, TranscriptConsequence tx, ObjectNode input) {
        int strand = model.path("strand").asInt();
        if (strand != 1 && strand != -1) return "PVS1_TRANSCRIPT_NOT_SUPPORTED";
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        String ref = allele.path("reference").asText();
        String alt = allele.path("alternate").asText();
        int limit = policy.at("/frameshift/maximumAlleleLengthIncludingAnchor").asInt();
        if (!ref.matches("[ACGT]+") || !alt.matches("[ACGT]+") || ref.equals(alt)
                || Math.max(ref.length(), alt.length()) > limit)
            return "PVS1_SHORT_CODING_ALLELE_REQUIRED";
        String type = ref.length() == alt.length() ? ref.length() == 1 ? "SNV" : "MNV"
                : ref.length() == 1 ? "SMALL_INSERTION" : alt.length() == 1 ? "SMALL_DELETION" : "SMALL_DELINS";
        if (!type.equals(context.at("/variant/variantType").asText())) {
            input.put("codingEditFailure", "VARIANT_TYPE_ALLELE_MISMATCH");
            return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        }
        // The reported type does not establish pure insertion/deletion semantics.
        // Equal-length MNV and in-frame delins remain outside this consumer.
        if ((alt.length() - ref.length()) % 3 == 0) return "PVS1_FRAMESHIFT_NOT_CONFIRMED";
        if (!allele.path("position").isIntegralNumber() || !allele.path("position").canConvertToInt()
                || allele.path("position").asInt() < 1
                || (long) allele.path("position").asInt() + ref.length() - 1 > Integer.MAX_VALUE)
            return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        int genomicStart = allele.path("position").asInt();
        int genomicEnd = genomicStart + ref.length() - 1;
        JsonNode exon = referenceExon(genomicStart, genomicEnd);
        if (exon == null) return "PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED";
        int c = CodingTranscriptMapping.cdsPosition(model, strand == 1 ? genomicStart : genomicEnd);
        String cds = model.path("cdsSequence").asText();
        String transcriptRef = CodingTranscriptMapping.orientAllele(model, ref);
        if (c < 1 || (long) c - 1 + ref.length() > cds.length() - 3)
            return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        if (!cds.substring(c - 1, c - 1 + ref.length()).equals(transcriptRef))
            return "PVS1_CDS_REFERENCE_MISMATCH";
        int ordinal = exon.path("ordinal").asInt();
        if (!(ordinal + "/" + model.path("exons").size()).equals(tx.getExon()))
            return "PVS1_EXON_STRUCTURE_MISMATCH";
        ObjectMapper mapper = new ObjectMapper();
        RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
        try {
            var reference = CodingTranscriptEdits.bind(mapper, context, model, adapter.canonicalSha256(model), sha(cds));
            var edit = CodingTranscriptEdits.projectAllele(reference, allele, adapter.canonicalSha256(allele));
            int start = edit.referenceEditStart0();
            int end = edit.referenceEditEnd0();
            if (!sameCodingExon(start, end, exon) || end > cds.length() - 3)
                return "PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED";
            var derivation = CodingTranscriptEdits.apply(reference, List.of(edit));
            String changed = derivation.alteredCds();
            int delta = derivation.delta();
            input.put("variantMechanism", "FRAMESHIFT").put("netLengthChange", delta)
                    .put("coordinateSystem", "ALTERED_CDS").put("cdsPosition", start + 1)
                    .put("alleleReferenceCdsStart", c).put("alleleReferenceCdsEnd", c + ref.length() - 1)
                    .put("referenceEditStart0", start).put("referenceEditEnd0", end)
                    .put("deletedSequence", edit.expectedReference()).put("insertedSequence", edit.insertedSequence())
                    .put("exonOrdinal", ordinal).put("exonTotal", model.path("exons").size())
                    .put(transcript.startsWith("ENST") ? "ensemblTranscript" : "selectedTranscript", transcript)
                    .put("refseqTranscript", model.path("refseqTranscript").asText())
                    .put("referenceCdsSha256", reference.cdsSha256()).put("alteredCdsSequence", changed)
                    .put("alteredCdsSha256", sha(changed)).put("alteredCdsLength", changed.length());
            if (ref.charAt(0) == alt.charAt(0))
                input.put("anchorReferenceCdsPosition", strand == 1 ? c : c + ref.length() - 1);
            else input.putNull("anchorReferenceCdsPosition");
            input.set("codingEditDerivation", derivation.snapshot(mapper));
            if (delta % 3 == 0) return "PVS1_FRAMESHIFT_NOT_CONFIRMED";
            String hgvsc = Objects.toString(tx.getHgvsc(), "");
            var hgvs = CodingTranscriptEdits.codingHgvsEdit(reference, hgvsc, sha(hgvsc));
            if (!sameCodingExon(hgvs.referenceEditStart0(), hgvs.referenceEditEnd0(), exon)
                    || hgvs.referenceEditEnd0() > cds.length() - 3)
                return "PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED";
            if (!CodingTranscriptEdits.sequenceEquivalent(reference, edit, hgvs))
                return "PVS1_HGVSC_SEQUENCE_MISMATCH";
            input.put("hgvscSequenceEquivalent", true).put("hgvscReferenceEditStart0", hgvs.referenceEditStart0())
                    .put("hgvscReferenceEditEnd0", hgvs.referenceEditEnd0()).put("hgvscInsertedSequence", hgvs.insertedSequence());
            return resolveProtein(tx, input, cds, changed, exon, start, end, edit.insertedSequence(), delta, derivation, false);
        } catch (CodingTranscriptEdits.Rejected rejected) {
            input.put("codingEditFailure", rejected.reasonCode());
            return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        } catch (IllegalArgumentException invalid) {
            input.put("codingEditFailure", "CODING_EDIT_INPUT_INVALID");
            return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        }
    }

    private String resolveProtein(TranscriptConsequence tx, ObjectNode input, String cds, String changed,
                                  JsonNode exon, int editStart, int editEnd, String inserted, int delta,
                                  CodingTranscriptEdits.Result derivation, boolean sequenceOnly) {
        int ordinal = exon.path("ordinal").asInt();
        String referenceProtein = translate(cds);
        String changedProtein = translate(changed);
        int stop = changedProtein.indexOf('*');
        if (stop < 0) return "PVS1_TERMINATION_UNRESOLVED";
        int first = 0;
        while (first < Math.min(referenceProtein.length(), changedProtein.length())
                && referenceProtein.charAt(first) == changedProtein.charAt(first)) first++;
        if (first >= referenceProtein.length() - 1 || first > stop)
            return "PVS1_PTC_NOT_PREMATURE";
        int ptcStart = stop * 3 + 1;
        int ptcEnd = ptcStart + 2;
        int referencePtcStart = referencePosition(ptcStart, editStart, editEnd, inserted.length(), derivation);
        int referencePtcMiddle = referencePosition(ptcStart + 1, editStart, editEnd, inserted.length(), derivation);
        int referencePtcEnd = referencePosition(ptcEnd, editStart, editEnd, inserted.length(), derivation);
        boolean singleExon = model.path("exons").size() == 1;
        int referenceJunction = model.path("lastJunctionCdsPosition").asInt();
        int changedJunction = changedBoundary(referenceJunction, editStart, editEnd, delta, derivation);
        if (changedJunction < 0) return "PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED";
        var ptcExons = alteredPtcExons(ptcStart, ptcEnd, editStart, editEnd, delta, derivation);
        input.put("firstChangedProteinPosition", first + 1).put("proteinPosition", first + 1)
                .put("refCodon", cds.substring(first * 3, first * 3 + 3))
                .put("altCodon", changed.substring(first * 3, first * 3 + 3))
                .put("terminationDistance", stop - first + 1).put("ptcStart", ptcStart).put("ptcEnd", ptcEnd)
                .put("referenceLastJunctionCdsPosition", referenceJunction)
                .put("mutantLastJunctionCdsPosition", changedJunction)
                .put("lastJunctionCdsPosition", changedJunction)
                .put("distanceToLastJunction", changedJunction - ptcEnd)
                .put("alteredProteinThroughTermination", changedProtein.substring(0, stop + 1));
        if (singleExon) input.putNull("referenceLastJunctionCdsPosition").putNull("mutantLastJunctionCdsPosition")
                .putNull("lastJunctionCdsPosition").putNull("distanceToLastJunction");
        if (referencePtcStart > 0) input.put("ptcReferenceStart", referencePtcStart);
        else input.putNull("ptcReferenceStart");
        if (referencePtcMiddle > 0) input.put("ptcReferenceMiddle", referencePtcMiddle);
        else input.putNull("ptcReferenceMiddle");
        if (referencePtcEnd > 0) input.put("ptcReferenceEnd", referencePtcEnd);
        else input.putNull("ptcReferenceEnd");
        input.put("ptcReferenceMapping", referencePtcStart > 0 && referencePtcMiddle > 0 && referencePtcEnd > 0
                ? "REFERENCE_BASES" : "CONTAINS_INSERTED_BASES");
        if (ptcExons.isEmpty()) return "PVS1_PTC_EXON_GEOMETRY_UNRESOLVED";
        JsonNode lastPtcExon = ptcExons.get(ptcExons.size() - 1);
        input.put("ptcExonOrdinal", lastPtcExon.path("ordinal").asInt());
        if (ptcExons.size() > 1) {
            var ordinals = input.putArray("ptcExonOrdinals");
            ptcExons.forEach(carrier -> ordinals.add(carrier.path("ordinal").asInt()));
        }
        // A stop at/after the shifted native termination region is not a confirmed premature stop.
        // The original stop base lies AFTER any insertion at its reference boundary.
        int nativeStopStart = (derivation == null
                ? changedBoundary(cds.length() - 3, editStart, editEnd, delta)
                : derivation.mapReferenceBoundary(cds.length() - 3, CodingTranscriptEdits.Bias.AFTER)) + 1;
        if (nativeStopStart < 1) return "PVS1_CODING_INDEL_MAPPING_UNRESOLVED";
        if (ptcStart >= nativeStopStart) return "PVS1_PTC_NOT_PREMATURE";
        String proteinIssue = proteinIssue(tx, referenceProtein, changedProtein, first, stop, cds, changed, editStart, editEnd);
        if (proteinIssue != null) return proteinIssue;
        input.put("sequenceDerivationStatus", "GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE");
        if (sequenceOnly) return null;

        if (editStart + 1 < policy.path("minimumCdsPosition").asInt() || ordinal < policy.path("firstExon").asInt())
            return "PVS1_START_PROXIMAL_BRANCH_NOT_IMPLEMENTED";
        // Compare the altered PTC and the shifted last junction in the SAME transcript coordinates.
        // The caller pins the minimum distance; no gene-specific threshold is inferred here.
        if (singleExon || changedJunction - ptcEnd < policy.path("frameshift").path("minimumDistanceToLastJunction").asInt()
                || ordinal > policy.path("lastExon").asInt()
                || lastPtcExon.path("ordinal").asInt() > policy.path("lastExon").asInt())
            return "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED";
        int variantExonLength = alteredLength(exon, ordinal, delta);
        int ptcExonLength = ptcExons.stream().mapToInt(carrier -> alteredLength(carrier, ordinal, delta)).max().orElseThrow();
        input.put("alteredVariantExonLength", variantExonLength).put("alteredPtcExonLength", ptcExonLength);
        if (variantExonLength > policy.path("maximumExonLength").asInt()
                || ptcExonLength > policy.path("maximumExonLength").asInt())
            return "PVS1_LONG_EXON_BRANCH_NOT_IMPLEMENTED";
        input.put("nmdPrediction", "PREDICTED_NMD").put("exonRelevance", exonRelevance)
                .put("mechanism", mechanism);
        return null;
    }

    private String proteinIssue(TranscriptConsequence tx, String original, String changed, int first, int stop,
                                String cds, String alteredCds, int editStart, int editEnd) {
        if (tx.getHgvsp() == null || tx.getProteinPosition() == null || tx.getAminoAcids() == null || tx.getCodons() == null)
            return "PVS1_FRAMESHIFT_ANNOTATION_INCOMPLETE";
        char from = original.charAt(first);
        char to = changed.charAt(first);
        String expected = model.path("protein").asText() + ":p." + TruncatingSequence.aminoName(from) + (first + 1)
                + (to == '*' ? "Ter" : TruncatingSequence.aminoName(to) + "fsTer" + (stop - first + 1));
        if (!expected.equals(tx.getHgvsp())) return "PVS1_FRAMESHIFT_PROTEIN_MISMATCH";
        // VEP's location/codon fields describe the allele-overlapping reference codons, whereas
        // HGVSp can start later after an unchanged amino-acid prefix (e.g. HNF1A c.872dup).
        String position;
        String refCodons;
        String altCodons;
        if (editStart == editEnd && editStart % 3 == 0) {
            position = (editStart / 3) + "-" + (editStart / 3 + 1);
            refCodons = "";
            altCodons = alteredCds.substring(editStart, editStart + alteredCds.length() - cds.length());
        } else {
            int firstCodon = editStart / 3;
            int lastCodon = Math.max(editStart, editEnd - 1) / 3;
            position = firstCodon == lastCodon ? Integer.toString(firstCodon + 1)
                    : (firstCodon + 1) + "-" + (lastCodon + 1);
            int lo = firstCodon * 3;
            int hi = (lastCodon + 1) * 3;
            refCodons = cds.substring(lo, hi);
            altCodons = alteredCds.substring(lo, hi + alteredCds.length() - cds.length());
        }
        String codons = (refCodons.isEmpty() ? "-" : refCodons) + "/" + (altCodons.isEmpty() ? "-" : altCodons);
        if (!position.equals(tx.getProteinPosition()) || !codons.equals(tx.getCodons().toUpperCase(Locale.ROOT))
                || !localAminoAcids(refCodons, altCodons).equals(tx.getAminoAcids()))
            return "PVS1_FRAMESHIFT_PROTEIN_MISMATCH";
        return null;
    }

    static String localAminoAcids(String referenceCodons, String alternateCodons) {
        String reference = localPeptide(referenceCodons);
        String alternate = localPeptide(alternateCodons);
        return reference.equals(alternate) ? reference : reference + "/" + alternate;
    }

    static String localPeptide(String codons) {
        if (codons.isEmpty()) return "-";
        // Ensembl release/116 TranscriptVariationAllele::peptide translates ALL local
        // complete codons. Unlike the CDS PTC scan, an internal '*' does not end this field.
        StringBuilder peptide = new StringBuilder();
        for (int i = 0; i + 3 <= codons.length(); i += 3)
            peptide.append(TruncatingSequence.translateCodon(codons.substring(i, i + 3)));
        if (codons.length() % 3 != 0 && !"*".contentEquals(peptide)) peptide.append('X');
        return peptide.toString();
    }

    private HgvsEdit codingEdit(String hgvsc, String cds) {
        var match = deletionOrDuplication.matcher(hgvsc);
        if (match.matches()) {
            int first = Integer.parseInt(match.group(1));
            int last = match.group(2) == null ? first : Integer.parseInt(match.group(2));
            if (first > last || first < 1 || last > cds.length() - 3) return null;
            String sequence = cds.substring(first - 1, last);
            if (!match.group(4).isEmpty() && !sequence.equals(match.group(4))) return null;
            return "del".equals(match.group(3)) ? new HgvsEdit(first - 1, last, "")
                    : new HgvsEdit(last, last, sequence);
        }
        match = insertionPattern.matcher(hgvsc);
        if (!match.matches()) return null;
        int first = Integer.parseInt(match.group(1));
        int last = Integer.parseInt(match.group(2));
        if (last != first + 1 || first < 1 || last > cds.length() - 3) return null;
        return new HgvsEdit(first, first, match.group(3));
    }

    private boolean sameCodingExon(int start, int end, JsonNode exon) {
        int lo = exon.path("cdsStart").asInt();
        int hi = exon.path("cdsEnd").asInt();
        // Include both neighbors of an insertion; a deletion must be entirely inside this exon.
        return start == end ? start >= lo && start < hi : start + 1 >= lo && end <= hi;
    }

    private JsonNode referenceExon(int start, int end) {
        return CodingTranscriptMapping.exonForGenomicInterval(model, start, end);
    }

    private static int alteredLength(JsonNode exon, int variantOrdinal, int delta) {
        return exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt() + 1
                + (exon.path("ordinal").asInt() == variantOrdinal ? delta : 0);
    }

    private List<JsonNode> alteredPtcExons(int ptcStart, int ptcEnd, int editStart, int editEnd, int delta,
                                          CodingTranscriptEdits.Result derivation) {
        var exons = new ArrayList<JsonNode>();
        int coveredBases = 0;
        if (derivation != null) {
            for (var span : derivation.exons()) {
                int overlap = Math.min(ptcEnd, span.alteredCdsEnd0()) - Math.max(ptcStart - 1, span.alteredCdsStart0());
                if (overlap > 0) {
                    exons.add(model.path("exons").path(span.referenceOrdinal() - 1));
                    coveredBases += overlap;
                }
            }
        } else {
            for (var exon : model.path("exons")) {
                int lo = changedBoundary(exon.path("cdsStart").asInt() - 1, editStart, editEnd, delta) + 1;
                int hi = changedBoundary(exon.path("cdsEnd").asInt(), editStart, editEnd, delta);
                int overlap = Math.min(ptcEnd, hi) - Math.max(ptcStart, lo) + 1;
                if (overlap > 0) {
                    exons.add(exon);
                    coveredBases += overlap;
                }
            }
        }
        return coveredBases == 3 ? exons : List.of();
    }

    private static int changedBoundary(int boundary, int start, int end, int delta,
                                       CodingTranscriptEdits.Result derivation) {
        if (derivation == null) return changedBoundary(boundary, start, end, delta);
        return derivation.mapReferenceBoundary(boundary, CodingTranscriptEdits.Bias.BEFORE);
    }

    private static int referencePosition(int alteredPosition, int start, int end, int insertedLength,
                                         CodingTranscriptEdits.Result derivation) {
        if (derivation == null) return referencePosition(alteredPosition, start, end, insertedLength);
        return derivation.alteredToReferenceBase(alteredPosition);
    }

    private static int changedBoundary(int boundary, int start, int end, int delta) {
        if (boundary <= start) return boundary;
        if (boundary >= end) return boundary + delta;
        throw new IllegalArgumentException("Boundary intersects deletion");
    }

    private static int referencePosition(int alteredPosition, int start, int end, int insertedLength) {
        if (alteredPosition <= start) return alteredPosition;
        if (alteredPosition <= start + insertedLength) return -1;
        return alteredPosition - insertedLength + end - start;
    }

    private static String translate(String sequence) {
        StringBuilder protein = new StringBuilder();
        for (int i = 0; i + 3 <= sequence.length(); i += 3) {
            String codon = sequence.substring(i, i + 3);
            protein.append(TruncatingSequence.translateCodon(codon));
            if (STOPS.contains(codon)) break;
        }
        return protein.toString();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record HgvsEdit(int start, int end, String inserted) { }
}
